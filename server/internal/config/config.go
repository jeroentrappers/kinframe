// Package config loads server configuration from environment variables.
// Every variable is documented in server/README.md.
package config

import (
	"cmp"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
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
	DataDir     string // media (fs backend), uploads, scratch space
	Blob        BlobConfig
	WebDir      string // built SPA; empty disables static serving

	SigningKey []byte // HMAC key for device tokens, dev tokens and signed media URLs

	OIDCIssuer     string
	OIDCAudience   string
	OIDCJWKSURL    string
	MobileClientID string
	WebClientID    string
	DevAuth        bool
	// LogtoConfigFile is where `bootstrap-logto` writes the Logto client
	// settings; values from it fill in OIDC settings not set in the env.
	LogtoConfigFile string

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
	// WebPush (RFC 8291/8292). The VAPID key pair comes from KINFRAME_VAPID_PUBLIC_KEY and
	// KINFRAME_VAPID_PRIVATE_KEY, or is generated once into <data dir>/vapid.json.
	VAPIDPublicKey  string // base64url uncompressed P-256 point
	VAPIDPrivateKey string // base64url scalar
	VAPIDSubject    string // mailto: or https: contact for push services
	UploadExpiry    time.Duration
	JanitorInterval time.Duration

	// Frame app self-update mirror (docs/api/README.md §5.7).
	FrameReleasesRepo     string // GitHub owner/name; empty disables GitHub polling
	FrameReleasesAPI      string // GitHub API base URL
	FrameReleasesToken    string // optional GitHub token
	FrameReleasesDir      string // optional drop-in directory of APKs
	FrameReleasesInterval time.Duration

	// Apple Developer Team ID and bundle id of the iOS family app, for
	// /.well-known/apple-app-site-association (Universal Links for join and
	// invite links). An empty team ID disables the file.
	AppleTeamID   string
	AppleBundleID string

	// Weather (Open-Meteo). Empty WeatherURL disables weather and /v1/geocode.
	WeatherURL     string
	GeocodingURL   string
	WeatherRefresh time.Duration
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
	var err error
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

		FrameReleasesRepo:     env("KINFRAME_FRAME_RELEASES_REPO", "jeroentrappers/kinframe"),
		FrameReleasesAPI:      env("KINFRAME_FRAME_RELEASES_API", "https://api.github.com"),
		FrameReleasesToken:    env("KINFRAME_FRAME_RELEASES_GITHUB_TOKEN", ""),
		FrameReleasesDir:      env("KINFRAME_FRAME_RELEASES_DIR", ""),
		FrameReleasesInterval: time.Duration(intv("KINFRAME_FRAME_RELEASES_POLL_MINUTES", 30)) * time.Minute,
	}
	c.AppleTeamID = env("KINFRAME_APPLE_TEAM_ID", "")
	c.AppleBundleID = env("KINFRAME_APPLE_BUNDLE_ID", "app.kinframe.family")
	c.WeatherURL = strings.TrimRight(env("KINFRAME_OPEN_METEO_URL", "https://api.open-meteo.com"), "/")
	c.GeocodingURL = strings.TrimRight(env("KINFRAME_OPEN_METEO_GEOCODING_URL", "https://geocoding-api.open-meteo.com"), "/")
	c.WeatherRefresh = time.Duration(intv("KINFRAME_WEATHER_REFRESH_MINUTES", 30)) * time.Minute
	switch strings.ToLower(env("KINFRAME_WEATHER", "on")) {
	case "0", "off", "false", "no":
		c.WeatherURL, c.GeocodingURL = "", ""
	}
	if c.WeatherRefresh < 15*time.Minute {
		errs = append(errs, errors.New("KINFRAME_WEATHER_REFRESH_MINUTES must be at least 15"))
	}
	switch strings.ToLower(c.FrameReleasesRepo) {
	case "off", "none", "-":
		c.FrameReleasesRepo = ""
	}
	if c.FrameReleasesRepo != "" && strings.Count(c.FrameReleasesRepo, "/") != 1 {
		errs = append(errs, fmt.Errorf("KINFRAME_FRAME_RELEASES_REPO must be owner/name or off, got %q", c.FrameReleasesRepo))
	}
	if c.FrameReleasesInterval < time.Minute {
		errs = append(errs, errors.New("KINFRAME_FRAME_RELEASES_POLL_MINUTES must be at least 1"))
	}
	if c.Blob, err = LoadBlob(get, c.DataDir); err != nil {
		errs = append(errs, err)
	}
	c.LogtoConfigFile = env("KINFRAME_LOGTO_CONFIG_FILE", DefaultLogtoConfigFile(c.DataDir))
	if lf, err := ReadLogtoFile(c.LogtoConfigFile); err != nil {
		errs = append(errs, fmt.Errorf("KINFRAME_LOGTO_CONFIG_FILE: %w", err))
	} else if lf != nil {
		// Explicit env vars win; the bootstrap output fills the gaps.
		c.OIDCIssuer = cmp.Or(c.OIDCIssuer, strings.TrimRight(lf.Issuer, "/"))
		c.WebClientID = cmp.Or(c.WebClientID, lf.WebClientID)
		c.MobileClientID = cmp.Or(c.MobileClientID, lf.MobileClientID)
		c.OIDCAudience = env("KINFRAME_OIDC_AUDIENCE", lf.APIResource)
	}
	c.OIDCAudience = cmp.Or(c.OIDCAudience, env("KINFRAME_OIDC_AUDIENCE", c.APIURL()))
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
		errs = append(errs, errors.New("KINFRAME_OIDC_ISSUER is required in production (or run bootstrap-logto to write KINFRAME_LOGTO_CONFIG_FILE)"))
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

	if c.PushEnabled {
		pub, priv := env("KINFRAME_VAPID_PUBLIC_KEY", ""), env("KINFRAME_VAPID_PRIVATE_KEY", "")
		switch {
		case (pub == "") != (priv == ""):
			errs = append(errs, errors.New("set both KINFRAME_VAPID_PUBLIC_KEY and KINFRAME_VAPID_PRIVATE_KEY, or neither"))
		case pub == "":
			pub, priv, err = vapidKeys(c.DataDir)
			if err != nil {
				errs = append(errs, fmt.Errorf("VAPID keys: %w", err))
			}
		default:
			if err := checkVAPID(pub, priv); err != nil {
				errs = append(errs, fmt.Errorf("KINFRAME_VAPID_*: %w", err))
			}
		}
		c.VAPIDPublicKey, c.VAPIDPrivateKey = pub, priv
		c.VAPIDSubject = env("KINFRAME_VAPID_SUBJECT", "")
		if c.VAPIDSubject == "" {
			if strings.HasPrefix(c.PublicURL, "https://") {
				c.VAPIDSubject = c.PublicURL
			} else {
				c.VAPIDSubject = "mailto:kinframe@localhost"
			}
		}
		if !strings.HasPrefix(c.VAPIDSubject, "mailto:") && !strings.HasPrefix(c.VAPIDSubject, "https://") {
			errs = append(errs, errors.New("KINFRAME_VAPID_SUBJECT must start with mailto: or https://"))
		}
	}

	if len(errs) > 0 {
		return nil, errors.Join(errs...)
	}
	return c, nil
}

// AppleAppIDs lists the iOS apps allowed to open this server's links, or nil
// when no team is configured.
func (c *Config) AppleAppIDs() []string {
	if c.AppleTeamID == "" || c.AppleBundleID == "" {
		return nil
	}
	return []string{c.AppleTeamID + "." + c.AppleBundleID}
}

// LogtoFile is the Logto client configuration written by `bootstrap-logto`
// and served in discovery (/.well-known/kinframe).
type LogtoFile struct {
	Issuer         string `json:"issuer"`
	WebClientID    string `json:"web_client_id"`
	MobileClientID string `json:"mobile_client_id"`
	APIResource    string `json:"api_resource"`
}

// DefaultLogtoConfigFile is the bootstrap output location inside the data dir.
func DefaultLogtoConfigFile(dataDir string) string { return filepath.Join(dataDir, "logto.json") }

// ReadLogtoFile returns nil, nil when the file doesn't exist.
func ReadLogtoFile(path string) (*LogtoFile, error) {
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var lf LogtoFile
	if err := json.Unmarshal(b, &lf); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	return &lf, nil
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
