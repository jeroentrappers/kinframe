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
	"net/mail"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/shop"
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
	// DesktopClientID is the Logto Native app of the desktop client; empty on
	// servers bootstrapped before it existed.
	DesktopClientID string
	WebClientID     string
	DevAuth         bool
	// LogtoConfigFile is where `bootstrap-logto` writes the Logto client
	// settings; values from it fill in OIDC settings not set in the env.
	LogtoConfigFile string
	// LogtoEndpoint is Logto's public endpoint (the issuer without /oidc) and
	// LogtoURL where the server reaches it (KINFRAME_LOGTO_URL, default the
	// endpoint). With the bootstrap M2M app's credentials, deleting a kinframe
	// account also deletes its Logto account.
	LogtoEndpoint  string
	LogtoURL       string
	LogtoM2MID     string
	LogtoM2MSecret string

	TrustProxy bool

	Workers   int
	VipsBin   string
	VipsHdr   string
	FFmpegBin string
	FFprobe   string

	FeedRetention      time.Duration
	MinFrameAppVersion int
	// AppVersions is the family app version policy per platform (docs/api/README.md §5.15),
	// keyed by AppPlatforms; a platform the operator set nothing for is absent.
	AppVersions    map[string]AppVersionPolicy
	CommandTimeout time.Duration
	DefaultLimits  Limits
	LogLevel       slog.Level
	LogJSON        bool
	PushEnabled    bool
	// WebPush (RFC 8291/8292). The VAPID key pair comes from KINFRAME_VAPID_PUBLIC_KEY and
	// KINFRAME_VAPID_PRIVATE_KEY, or is generated once into <data dir>/vapid.json.
	VAPIDPublicKey  string // base64url uncompressed P-256 point
	VAPIDPrivateKey string // base64url scalar
	VAPIDSubject    string // mailto: or https: contact for push services
	// FCM for the family app's store builds: a Firebase service account JSON key (KINFRAME_FCM_CREDENTIALS_FILE).
	FCMCredentials []byte
	// APNs for the iOS app: a .p8 key (KINFRAME_APNS_KEY_FILE) with its key ID, the team ID and the bundle ID.
	APNsKey         []byte
	APNsKeyID       string
	APNsTeamID      string
	APNsTopic       string
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

	// Community plugin registry mirror (docs/plugins.md §7). Empty URL disables it.
	PluginRegistryURL      string // where index.json, its .sig and the bundles are
	PluginRegistryKey      string // base64 SubjectPublicKeyInfo of the registry's ECDSA P-256 key
	PluginRegistryInterval time.Duration

	// Where weather-station uploads are passed on (Weather Underground's own receiver); empty: not passed on.
	StationForwardURL string

	// Weather (Open-Meteo). Empty WeatherURL disables weather and /v1/geocode.
	WeatherURL     string
	GeocodingURL   string
	WeatherRefresh time.Duration

	// Calendars (README §5.8). CalendarSync is the interval between syncs of
	// a source; SecretsKey (optional) encrypts stored calendar credentials,
	// else a key derived from SigningKey is used.
	CalendarEnabled bool
	CalendarSync    time.Duration
	SecretsKey      []byte

	// Photo sources (docs/spec/photo-sources.md §11). PhotoSources turns
	// Immich and iCloud sources on, ICloudSources iCloud links on their own;
	// PhotoSourceSync is the sync interval (iCloud at least an hour).
	PhotoSources    bool
	ICloudSources   bool
	PhotoSourceSync time.Duration
	// SourcesPrivateNetworks is auto, on or off (§12.3); SourcesAllowedHosts
	// are host names or CIDRs always allowed; SourcesCAFile adds trusted CAs.
	SourcesPrivateNetworks string
	SourcesAllowedHosts    []string
	SourcesCAFile          string
	// SourcesAllowLoopback lets the guard reach 127.0.0.1 (tests with
	// httptest fakes only; there is no environment variable for it).
	SourcesAllowLoopback bool
	// Google Photos import (§7.5): off without a client id and secret.
	GoogleClientID     string
	GoogleClientSecret string

	// SMTP for the server's own mail (shop reservations); nil when
	// KINFRAME_SMTP_HOST is unset. The same settings configure Logto's
	// sign-in mails (bootstrap-logto).
	SMTP *shop.SMTP
	// Shop is the optional pre-order shop (KINFRAME_SHOP_FILE); nil = no shop.
	Shop *shop.Config
	// ShopMediaDir holds the shop's product photos (KINFRAME_SHOP_MEDIA_DIR).
	ShopMediaDir string
	// MollieAPIKey pays shop orders (KINFRAME_MOLLIE_API_KEY; test_… or live_…),
	// required when the shop file has payments.
	MollieAPIKey string
	// MollieBaseURL overrides the Mollie API endpoint (tests).
	MollieBaseURL string

	// Telegram bridge (docs/spec/telegram.md §14). An empty token (or mode
	// off) means no bot: discovery says telegram: false.
	TelegramBotToken       string
	TelegramMode           string // auto, webhook, polling, off
	TelegramWebhookURL     string // default <public URL>/v1/telegram/webhook
	TelegramAPIURL         string // default https://api.telegram.org; else a local Bot API server
	TelegramLocalFilesDir  string // the local server's --local directory as mounted here
	TelegramMaxDownload    int64  // bytes the bot downloads at most (20 MB cloud, 2000 MB local)
	TelegramWorkers        int    // chats processed in parallel
	TelegramWebhookIPCheck bool   // only Telegram's published addresses may call the webhook
	// TelegramCloudURL replaces Telegram's cloud Bot API (tests with a fake; no environment variable).
	TelegramCloudURL string

	// Chats (README §5.19): LinkPreviews (KINFRAME_LINK_PREVIEWS) lets the server fetch link previews.
	LinkPreviews bool

	// Email to frame (docs/spec/email-inbox.md), config/email.go.
	Email EmailConfig
}

// AppPlatforms are the family app platforms, as named in `X-Kinframe-App: kinframe-family-<platform>/…`.
var AppPlatforms = []string{"android", "ios", "macos"}

// AppVersionPolicy is one platform's KINFRAME_APP_<PLATFORM>_{MIN_BUILD,RECOMMENDED_BUILD,STORE_URL}.
// Zero builds and an empty URL mean "not set".
type AppVersionPolicy struct {
	MinBuild         int
	RecommendedBuild int
	StoreURL         string
}

// Limits are the operator-adjustable limits; these are the startup defaults.
type Limits struct {
	MaxItemsPerSend    int   `json:"max_items_per_send"`
	MaxVideoSeconds    int   `json:"max_video_seconds"`
	MaxUploadBytes     int64 `json:"max_upload_bytes"`
	TrashRetentionDays int   `json:"trash_retention_days"`
	// Feeds (docs/spec/feeds.md §6.6). Zero means the default.
	MaxFeedsPerUser         int `json:"max_feeds_per_user,omitempty"`
	MaxSubscriptionsPerFeed int `json:"max_subscriptions_per_feed,omitempty"`
	MaxFeedBackfillItems    int `json:"max_feed_backfill_items,omitempty"`
	// Birthdays (docs/spec/birthdays.md §12): pending and approved people per feed.
	MaxBirthdaysPerFeed int `json:"max_birthdays_per_feed,omitempty"`
	// Photo sources (docs/spec/photo-sources.md §11). Zero means the default.
	MaxPhotoSourcesPerTarget int   `json:"max_photo_sources_per_target,omitempty"`
	MaxPhotoSourcesPerUser   int   `json:"max_photo_sources_per_user,omitempty"`
	MaxPhotoSourceItems      int   `json:"max_photo_source_items,omitempty"`
	MaxPhotoSourceRunBytes   int64 `json:"max_photo_source_run_bytes,omitempty"`
	MaxGoogleImportItems     int   `json:"max_google_import_items,omitempty"`
	// Telegram (docs/spec/telegram.md §14). Zero means the default.
	MaxTelegramChatsPerTarget   int `json:"max_telegram_chats_per_target,omitempty"`
	MaxTelegramChatsPerUser     int `json:"max_telegram_chats_per_user,omitempty"`
	MaxTelegramItemsPerChatHour int `json:"max_telegram_items_per_chat_hour,omitempty"`
	// Email to frame (docs/spec/email-inbox.md §12.1). Zero means the default.
	MaxEmailAddressesPerTarget    int   `json:"max_email_addresses_per_target,omitempty"`
	MaxEmailSendersPerAddress     int   `json:"max_email_senders_per_address,omitempty"`
	MaxEmailMessageBytes          int64 `json:"max_email_message_bytes,omitempty"`
	MaxEmailMessagesPerAddressDay int   `json:"max_email_messages_per_address_day,omitempty"`
	MaxEmailHeldMessages          int   `json:"max_email_held_messages,omitempty"`
}

// Feed limit defaults.
const (
	DefaultMaxFeedsPerUser         = 20
	DefaultMaxSubscriptionsPerFeed = 100
	DefaultMaxFeedBackfillItems    = 5000
	DefaultMaxBirthdaysPerFeed     = 200

	DefaultMaxPhotoSourcesPerTarget       = 5
	DefaultMaxPhotoSourcesPerUser         = 20
	DefaultMaxPhotoSourceItems            = 10000
	DefaultMaxPhotoSourceRunBytes   int64 = 2 << 30
	DefaultMaxGoogleImportItems           = 500

	DefaultMaxTelegramChatsPerTarget   = 5
	DefaultMaxTelegramChatsPerUser     = 20
	DefaultMaxTelegramItemsPerChatHour = 300
)

// WithDefaults fills unset (zero) feed limits with their defaults.
func (l Limits) WithDefaults() Limits {
	if l.MaxFeedsPerUser <= 0 {
		l.MaxFeedsPerUser = DefaultMaxFeedsPerUser
	}
	if l.MaxSubscriptionsPerFeed <= 0 {
		l.MaxSubscriptionsPerFeed = DefaultMaxSubscriptionsPerFeed
	}
	if l.MaxFeedBackfillItems <= 0 {
		l.MaxFeedBackfillItems = DefaultMaxFeedBackfillItems
	}
	if l.MaxBirthdaysPerFeed <= 0 {
		l.MaxBirthdaysPerFeed = DefaultMaxBirthdaysPerFeed
	}
	if l.MaxPhotoSourcesPerTarget <= 0 {
		l.MaxPhotoSourcesPerTarget = DefaultMaxPhotoSourcesPerTarget
	}
	if l.MaxPhotoSourcesPerUser <= 0 {
		l.MaxPhotoSourcesPerUser = DefaultMaxPhotoSourcesPerUser
	}
	if l.MaxPhotoSourceItems <= 0 {
		l.MaxPhotoSourceItems = DefaultMaxPhotoSourceItems
	}
	if l.MaxPhotoSourceRunBytes <= 0 {
		l.MaxPhotoSourceRunBytes = DefaultMaxPhotoSourceRunBytes
	}
	if l.MaxGoogleImportItems <= 0 {
		l.MaxGoogleImportItems = DefaultMaxGoogleImportItems
	}
	if l.MaxTelegramChatsPerTarget <= 0 {
		l.MaxTelegramChatsPerTarget = DefaultMaxTelegramChatsPerTarget
	}
	if l.MaxTelegramChatsPerUser <= 0 {
		l.MaxTelegramChatsPerUser = DefaultMaxTelegramChatsPerUser
	}
	if l.MaxTelegramItemsPerChatHour <= 0 {
		l.MaxTelegramItemsPerChatHour = DefaultMaxTelegramItemsPerChatHour
	}
	l.emailDefaults()
	return l
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
		DesktopClientID:    env("KINFRAME_OIDC_DESKTOP_CLIENT_ID", ""),
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
			MaxItemsPerSend:          intv("KINFRAME_MAX_ITEMS_PER_SEND", 200),
			MaxVideoSeconds:          intv("KINFRAME_MAX_VIDEO_SECONDS", 300),
			MaxUploadBytes:           int64(intv("KINFRAME_MAX_UPLOAD_MB", 2048)) << 20,
			TrashRetentionDays:       intv("KINFRAME_TRASH_RETENTION_DAYS", 30),
			MaxFeedsPerUser:          intv("KINFRAME_MAX_FEEDS_PER_USER", DefaultMaxFeedsPerUser),
			MaxSubscriptionsPerFeed:  intv("KINFRAME_MAX_SUBSCRIPTIONS_PER_FEED", DefaultMaxSubscriptionsPerFeed),
			MaxFeedBackfillItems:     intv("KINFRAME_MAX_FEED_BACKFILL_ITEMS", DefaultMaxFeedBackfillItems),
			MaxBirthdaysPerFeed:      intv("KINFRAME_MAX_BIRTHDAYS_PER_FEED", DefaultMaxBirthdaysPerFeed),
			MaxPhotoSourcesPerTarget: intv("KINFRAME_MAX_PHOTO_SOURCES_PER_TARGET", DefaultMaxPhotoSourcesPerTarget),
			MaxPhotoSourcesPerUser:   intv("KINFRAME_MAX_PHOTO_SOURCES_PER_USER", DefaultMaxPhotoSourcesPerUser),
			MaxPhotoSourceItems:      intv("KINFRAME_MAX_PHOTO_SOURCE_ITEMS", DefaultMaxPhotoSourceItems),
			MaxPhotoSourceRunBytes:   int64(intv("KINFRAME_MAX_PHOTO_SOURCE_RUN_MB", int(DefaultMaxPhotoSourceRunBytes>>20))) << 20,
			MaxGoogleImportItems:     intv("KINFRAME_MAX_GOOGLE_IMPORT_ITEMS", DefaultMaxGoogleImportItems),

			MaxTelegramChatsPerTarget:   intv("KINFRAME_MAX_TELEGRAM_CHATS_PER_TARGET", DefaultMaxTelegramChatsPerTarget),
			MaxTelegramChatsPerUser:     intv("KINFRAME_MAX_TELEGRAM_CHATS_PER_USER", DefaultMaxTelegramChatsPerUser),
			MaxTelegramItemsPerChatHour: intv("KINFRAME_MAX_TELEGRAM_ITEMS_PER_CHAT_HOUR", DefaultMaxTelegramItemsPerChatHour),
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

		PluginRegistryURL:      env("KINFRAME_PLUGIN_REGISTRY_URL", DefaultPluginRegistryURL),
		PluginRegistryKey:      env("KINFRAME_PLUGIN_REGISTRY_KEY", DefaultPluginRegistryKey),
		PluginRegistryInterval: time.Duration(intv("KINFRAME_PLUGIN_REGISTRY_POLL_MINUTES", 60)) * time.Minute,

		StationForwardURL: env("KINFRAME_STATION_FORWARD_URL", "http://rtupdate.wunderground.com/weatherstation/updateweatherstation.php"),
	}
	for _, p := range AppPlatforms {
		prefix := "KINFRAME_APP_" + strings.ToUpper(p) + "_"
		v := AppVersionPolicy{
			MinBuild:         intv(prefix+"MIN_BUILD", 0),
			RecommendedBuild: intv(prefix+"RECOMMENDED_BUILD", 0),
			StoreURL:         env(prefix+"STORE_URL", ""),
		}
		if v.MinBuild < 0 {
			errs = append(errs, fmt.Errorf("%sMIN_BUILD must not be negative", prefix))
		}
		if v.RecommendedBuild < 0 {
			errs = append(errs, fmt.Errorf("%sRECOMMENDED_BUILD must not be negative", prefix))
		}
		if v.StoreURL != "" {
			if u, err := url.Parse(v.StoreURL); err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
				errs = append(errs, fmt.Errorf("%sSTORE_URL must be an http(s) URL, got %q", prefix, v.StoreURL))
			}
		}
		if v != (AppVersionPolicy{}) {
			if c.AppVersions == nil {
				c.AppVersions = map[string]AppVersionPolicy{}
			}
			c.AppVersions[p] = v
		}
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
	c.CalendarSync = time.Duration(intv("KINFRAME_CALENDAR_SYNC_MINUTES", 15)) * time.Minute
	c.CalendarEnabled = true
	switch strings.ToLower(env("KINFRAME_CALENDAR", "on")) {
	case "0", "off", "false", "no":
		c.CalendarEnabled = false
	}
	if c.CalendarSync < 5*time.Minute {
		errs = append(errs, errors.New("KINFRAME_CALENDAR_SYNC_MINUTES must be at least 5"))
	}
	onOff := func(k string) bool {
		switch strings.ToLower(env(k, "on")) {
		case "0", "off", "false", "no":
			return false
		}
		return true
	}
	c.PhotoSources = onOff("KINFRAME_PHOTO_SOURCES")
	c.ICloudSources = onOff("KINFRAME_ICLOUD_SOURCES")
	c.PhotoSourceSync = time.Duration(intv("KINFRAME_PHOTO_SOURCE_SYNC_MINUTES", 30)) * time.Minute
	if c.PhotoSourceSync < 10*time.Minute {
		errs = append(errs, errors.New("KINFRAME_PHOTO_SOURCE_SYNC_MINUTES must be at least 10"))
	}
	c.SourcesPrivateNetworks = strings.ToLower(env("KINFRAME_SOURCES_PRIVATE_NETWORKS", "auto"))
	switch c.SourcesPrivateNetworks {
	case "auto", "on", "off":
	case "1", "true", "yes":
		c.SourcesPrivateNetworks = "on"
	case "0", "false", "no":
		c.SourcesPrivateNetworks = "off"
	default:
		errs = append(errs, fmt.Errorf("KINFRAME_SOURCES_PRIVATE_NETWORKS must be auto, on or off, got %q", c.SourcesPrivateNetworks))
	}
	for _, h := range strings.Split(env("KINFRAME_SOURCES_ALLOWED_HOSTS", ""), ",") {
		if h = strings.TrimSpace(h); h != "" {
			c.SourcesAllowedHosts = append(c.SourcesAllowedHosts, h)
		}
	}
	c.SourcesCAFile = env("KINFRAME_SOURCES_CA_FILE", "")
	c.GoogleClientID = env("KINFRAME_GOOGLE_CLIENT_ID", "")
	if c.GoogleClientSecret, err = secretValue(get, "KINFRAME_GOOGLE_CLIENT_SECRET"); err != nil {
		errs = append(errs, err)
	}
	if (c.GoogleClientID == "") != (c.GoogleClientSecret == "") {
		errs = append(errs, errors.New("set both KINFRAME_GOOGLE_CLIENT_ID and KINFRAME_GOOGLE_CLIENT_SECRET (or _FILE), or neither"))
	}
	if sk, err := parseKey(env("KINFRAME_SECRETS_KEY", "")); err != nil {
		errs = append(errs, fmt.Errorf("KINFRAME_SECRETS_KEY: %w", err))
	} else {
		c.SecretsKey = sk
	}
	if err := loadTelegram(c, get, env, intv); err != nil {
		errs = append(errs, err)
	}
	if err := loadChat(c, env); err != nil {
		errs = append(errs, err)
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
	if host := env("KINFRAME_SMTP_HOST", ""); host != "" {
		c.SMTP = &shop.SMTP{Host: host, Port: intv("KINFRAME_SMTP_PORT", 587), User: env("KINFRAME_SMTP_USER", ""),
			Password: get("KINFRAME_SMTP_PASSWORD"), From: env("KINFRAME_SMTP_FROM", "")}
		if a, err := mail.ParseAddress(c.SMTP.From); err != nil || a.Address == "" {
			errs = append(errs, errors.New("KINFRAME_SMTP_FROM must be an email address when KINFRAME_SMTP_HOST is set"))
		}
		if c.SMTP.Port <= 0 || c.SMTP.Port > 65535 {
			errs = append(errs, fmt.Errorf("KINFRAME_SMTP_PORT: invalid port %d", c.SMTP.Port))
		}
	}
	if f := env("KINFRAME_SHOP_FILE", ""); f != "" {
		if c.Shop, err = shop.Load(f, c.ServerName); err != nil {
			errs = append(errs, fmt.Errorf("KINFRAME_SHOP_FILE: %w", err))
		} else if c.Shop != nil {
			if u, err := url.Parse(c.PublicURL); err == nil && c.Shop.IsStorefront(u.Hostname()) {
				errs = append(errs, fmt.Errorf("KINFRAME_SHOP_FILE: domains must not include the app's own host %q (KINFRAME_PUBLIC_URL)", u.Hostname()))
			}
			c.ShopMediaDir = env("KINFRAME_SHOP_MEDIA_DIR", "")
			if err := c.Shop.CheckMedia(c.ShopMediaDir); err != nil {
				errs = append(errs, fmt.Errorf("KINFRAME_SHOP_FILE: %w", err))
			}
			c.MollieAPIKey = get("KINFRAME_MOLLIE_API_KEY")
			if c.Shop.Checkout() && !strings.HasPrefix(c.MollieAPIKey, "test_") && !strings.HasPrefix(c.MollieAPIKey, "live_") {
				errs = append(errs, errors.New("KINFRAME_MOLLIE_API_KEY must be a Mollie API key (test_… or live_…) when the shop has payments"))
			}
		}
	}
	errs = append(errs, loadEmail(get, c)...)
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
		c.DesktopClientID = cmp.Or(c.DesktopClientID, lf.DesktopClientID)
		c.OIDCAudience = env("KINFRAME_OIDC_AUDIENCE", lf.APIResource)
	}
	c.OIDCAudience = cmp.Or(c.OIDCAudience, env("KINFRAME_OIDC_AUDIENCE", c.APIURL()))
	c.LogtoEndpoint = strings.TrimSuffix(c.OIDCIssuer, "/oidc")
	c.LogtoURL = strings.TrimRight(env("KINFRAME_LOGTO_URL", c.LogtoEndpoint), "/")
	c.LogtoM2MID, c.LogtoM2MSecret = env("KINFRAME_LOGTO_M2M_ID", ""), get("KINFRAME_LOGTO_M2M_SECRET")
	if (c.LogtoM2MID == "") != (c.LogtoM2MSecret == "") {
		errs = append(errs, errors.New("set both KINFRAME_LOGTO_M2M_ID and KINFRAME_LOGTO_M2M_SECRET, or neither"))
	}
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
		if f := env("KINFRAME_APNS_KEY_FILE", ""); f != "" {
			if c.APNsKey, err = os.ReadFile(f); err != nil {
				errs = append(errs, fmt.Errorf("KINFRAME_APNS_KEY_FILE: %w", err))
			}
			c.APNsKeyID, c.APNsTeamID = env("KINFRAME_APNS_KEY_ID", ""), env("KINFRAME_APNS_TEAM_ID", "")
			c.APNsTopic = env("KINFRAME_APNS_TOPIC", "app.kinframe.family")
			if c.APNsKeyID == "" || c.APNsTeamID == "" {
				errs = append(errs, errors.New("KINFRAME_APNS_KEY_FILE needs KINFRAME_APNS_KEY_ID and KINFRAME_APNS_TEAM_ID"))
			}
		}
		if f := env("KINFRAME_FCM_CREDENTIALS_FILE", ""); f != "" {
			if c.FCMCredentials, err = os.ReadFile(f); err != nil {
				errs = append(errs, fmt.Errorf("KINFRAME_FCM_CREDENTIALS_FILE: %w", err))
			}
		}
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
	Issuer          string `json:"issuer"`
	WebClientID     string `json:"web_client_id"`
	MobileClientID  string `json:"mobile_client_id"`
	DesktopClientID string `json:"desktop_client_id,omitempty"`
	APIResource     string `json:"api_resource"`
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

// The public community plugin registry and its signing key (public; the private key is only in the registry's CI
// and the operator's vault). Self-hosters can point at their own registry with their own key.
const (
	DefaultPluginRegistryURL = "https://github.com/jeroentrappers/kinframe-plugins/releases/download/registry"
	DefaultPluginRegistryKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEt0Z/3gbK9h78oVewOrMVTasFYG4af/w1D3eXmNJk2gCHgw8rEsrammSJXnMu6VXjp0rkz87UXXVOzaAep6fB+Q=="
)

// secretValue reads a secret from k, or from the file named by k_FILE
// (Docker secrets); surrounding whitespace is trimmed.
func secretValue(get func(string) string, k string) (string, error) {
	if v := strings.TrimSpace(get(k)); v != "" {
		return v, nil
	}
	f := strings.TrimSpace(get(k + "_FILE"))
	if f == "" {
		return "", nil
	}
	b, err := os.ReadFile(f)
	if err != nil {
		return "", fmt.Errorf("%s_FILE: %w", k, err)
	}
	return strings.TrimSpace(string(b)), nil
}
