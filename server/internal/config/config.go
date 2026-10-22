// Package config loads server configuration from environment variables.
// Every variable is documented in server/README.md.
package config

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Env        string // "development" or "production"
	Listen     string
	PublicURL  string // externally visible base URL, no trailing slash
	ServerName string
	Version    string

	DatabaseURL string
	DataDir     string // media, uploads
	WebDir      string // built SPA; empty disables static serving

	SigningKey []byte // HMAC key for device tokens, dev tokens and signed media URLs

	OIDCIssuer     string
	OIDCAudience   string
	OIDCJWKSURL    string
	MobileClientID string
	WebClientID    string
	DevAuth        bool

	TrustProxy bool

	Workers   int
	VipsBin   string
	VipsHdr   string
	FFmpegBin string
	FFprobe   string

	FeedRetention      time.Duration
	MinFrameAppVersion int
	CommandTimeout     time.Duration
	DefaultLimits      Limits
	LogLevel           slog.Level
	LogJSON            bool
	PushEnabled        bool
	UploadExpiry       time.Duration
	JanitorInterval    time.Duration
}

// Limits are the operator-adjustable limits; these are the startup defaults.
type Limits struct {
	MaxItemsPerSend    int   `json:"max_items_per_send"`
	MaxVideoSeconds    int   `json:"max_video_seconds"`
	MaxUploadBytes     int64 `json:"max_upload_bytes"`
	TrashRetentionDays int   `json:"trash_retention_days"`
}

func (c *Config) Production() bool { return c.Env == "production" }

// APIURL is the base of the versioned API.
func (c *Config) APIURL() string { return c.PublicURL + "/v1" }

// Load reads configuration from the environment.
func Load() (*Config, error) { return load(os.Getenv) }

func load(get func(string) string) (*Config, error) {
	env := func(k, def string) string {
		if v := strings.TrimSpace(get(k)); v != "" {
			return v
		}
		return def
	}
	var errs []error
	intv := func(k string, def int) int {
		v := env(k, "")
		if v == "" {
			return def
		}
		n, err := strconv.Atoi(v)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", k, err))
		}
		return n
	}
	boolv := func(k string) bool {
		switch strings.ToLower(env(k, "")) {
		case "1", "true", "yes", "on":
			return true
		}
		return false
	}

	c := &Config{
		Env:                env("KINFRAME_ENV", "development"),
		Listen:             env("KINFRAME_LISTEN", ":8110"),
		PublicURL:          strings.TrimRight(env("KINFRAME_PUBLIC_URL", "http://localhost:8110"), "/"),
		ServerName:         env("KINFRAME_SERVER_NAME", "kinframe"),
		Version:            env("KINFRAME_VERSION", "0.1.0"),
		DatabaseURL:        env("KINFRAME_DATABASE_URL", ""),
		DataDir:            env("KINFRAME_DATA_DIR", "./data"),
		WebDir:             env("KINFRAME_WEB_DIR", ""),
		OIDCIssuer:         strings.TrimRight(env("KINFRAME_OIDC_ISSUER", ""), "/"),
		OIDCJWKSURL:        env("KINFRAME_OIDC_JWKS_URL", ""),
		MobileClientID:     env("KINFRAME_OIDC_MOBILE_CLIENT_ID", ""),
		WebClientID:        env("KINFRAME_OIDC_WEB_CLIENT_ID", ""),
		DevAuth:            boolv("KINFRAME_DEV_AUTH"),
		TrustProxy:         boolv("KINFRAME_TRUST_PROXY"),
		Workers:            intv("KINFRAME_WORKERS", max(1, runtime.NumCPU()/2)),
		VipsBin:            env("KINFRAME_VIPS", "vips"),
		VipsHdr:            env("KINFRAME_VIPSHEADER", "vipsheader"),
		FFmpegBin:          env("KINFRAME_FFMPEG", "ffmpeg"),
		FFprobe:            env("KINFRAME_FFPROBE", "ffprobe"),
		FeedRetention:      time.Duration(intv("KINFRAME_FEED_RETENTION_DAYS", 30)) * 24 * time.Hour,
		MinFrameAppVersion: intv("KINFRAME_MIN_FRAME_APP_VERSION_CODE", 0),
		CommandTimeout:     time.Duration(intv("KINFRAME_COMMAND_TIMEOUT_SECONDS", 10)) * time.Second,
		DefaultLimits: Limits{
			MaxItemsPerSend:    intv("KINFRAME_MAX_ITEMS_PER_SEND", 200),
			MaxVideoSeconds:    intv("KINFRAME_MAX_VIDEO_SECONDS", 300),
			MaxUploadBytes:     int64(intv("KINFRAME_MAX_UPLOAD_MB", 2048)) << 20,
			TrashRetentionDays: intv("KINFRAME_TRASH_RETENTION_DAYS", 30),
		},
		LogJSON:         env("KINFRAME_LOG_FORMAT", "text") == "json",
		PushEnabled:     !boolv("KINFRAME_PUSH_DISABLED"),
		UploadExpiry:    24 * time.Hour,
		JanitorInterval: time.Duration(intv("KINFRAME_JANITOR_INTERVAL_SECONDS", 600)) * time.Second,
	}
	c.OIDCAudience = env("KINFRAME_OIDC_AUDIENCE", c.APIURL())
	if err := c.LogLevel.UnmarshalText([]byte(env("KINFRAME_LOG_LEVEL", "info"))); err != nil {
		errs = append(errs, fmt.Errorf("KINFRAME_LOG_LEVEL: %w", err))
	}

	if c.Env != "development" && c.Env != "production" {
		errs = append(errs, fmt.Errorf("KINFRAME_ENV must be development or production, got %q", c.Env))
	}
	if c.DatabaseURL == "" {
		errs = append(errs, errors.New("KINFRAME_DATABASE_URL is required"))
	}
	if c.Production() && c.DevAuth {
		errs = append(errs, errors.New("KINFRAME_DEV_AUTH must not be enabled when KINFRAME_ENV=production"))
	}
	if c.Production() && c.OIDCIssuer == "" {
		errs = append(errs, errors.New("KINFRAME_OIDC_ISSUER is required in production"))
	}
	if !c.DevAuth && c.OIDCIssuer == "" {
		errs = append(errs, errors.New("set KINFRAME_OIDC_ISSUER, or KINFRAME_DEV_AUTH=1 for local development"))
	}

	key, err := parseKey(env("KINFRAME_SIGNING_KEY", ""))
	switch {
	case err != nil:
		errs = append(errs, fmt.Errorf("KINFRAME_SIGNING_KEY: %w", err))
	case key == nil && c.Production():
		errs = append(errs, errors.New("KINFRAME_SIGNING_KEY is required in production (32+ random bytes, hex or base64)"))
	case key == nil:
		key, err = devKey(c.DataDir)
		if err != nil {
			errs = append(errs, fmt.Errorf("dev signing key: %w", err))
		}
	}
	c.SigningKey = key

	if len(errs) > 0 {
		return nil, errors.Join(errs...)
	}
	return c, nil
}

func parseKey(s string) ([]byte, error) {
	if s == "" {
		return nil, nil
	}
	var b []byte
	if h, err := hex.DecodeString(s); err == nil {
		b = h
	} else if d, err := base64.StdEncoding.DecodeString(s); err == nil {
		b = d
	} else if d, err := base64.RawURLEncoding.DecodeString(s); err == nil {
		b = d
	} else {
		return nil, errors.New("must be hex or base64")
	}
	if len(b) < 32 {
		return nil, errors.New("must be at least 32 bytes")
	}
	return b, nil
}

// devKey persists a random key in the data dir so dev restarts keep tokens valid.
func devKey(dataDir string) ([]byte, error) {
	path := filepath.Join(dataDir, "dev-signing-key")
	if b, err := os.ReadFile(path); err == nil {
		return parseKey(strings.TrimSpace(string(b)))
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, []byte(hex.EncodeToString(b)), 0o600); err != nil {
		return nil, err
	}
	return b, nil
}
