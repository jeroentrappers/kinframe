package store

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// ---------------------------------------------------------------- users

type User struct {
	ID                 uuid.UUID  `db:"id"`
	Sub                *string    `db:"sub"`
	Email              *string    `db:"email"`
	DisplayName        string     `db:"display_name"`
	AvatarKey          *string    `db:"avatar_key"`
	ShareLocationNames bool       `db:"share_location_names"`
	Locale             *string    `db:"locale"`
	CreatedAt          time.Time  `db:"created_at"`
	DeletedAt          *time.Time `db:"deleted_at"`
}

const userCols = `id, sub, email, display_name, avatar_key, share_location_names, locale, created_at, deleted_at`

// DefaultDisplayName derives a display name from token claims (README §3.1).
func DefaultDisplayName(name, email string) string {
	if n := strings.TrimSpace(name); n != "" {
		return truncate(n, 40)
	}
	if local, _, ok := strings.Cut(email, "@"); ok && local != "" {
		return truncate(local, 40)
	}
	return "New member"
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n])
	}
	return s
}

// UpsertUserBySub returns the user for an identity subject, creating it on first use.
func (s *Store) UpsertUserBySub(ctx context.Context, sub, email, name string) (*User, error) {
	u, err := one[User](s.Pool.Query(ctx, `SELECT `+userCols+` FROM users WHERE sub = $1`, sub))
	if err != ErrNotFound {
		return u, err
	}
	var emailp *string
	if email != "" {
		emailp = &email
	}
	return one[User](s.Pool.Query(ctx, `
		INSERT INTO users (id, sub, email, display_name) VALUES ($1, $2, $3, $4)
		ON CONFLICT (sub) DO UPDATE SET sub = EXCLUDED.sub
		RETURNING `+userCols, NewID(), sub, emailp, DefaultDisplayName(name, email)))
}

func (s *Store) GetUser(ctx context.Context, q db.DBTX, id uuid.UUID) (*User, error) {
	return one[User](q.Query(ctx, `SELECT `+userCols+` FROM users WHERE id = $1`, id))
}

type UserPatch struct {
	DisplayName        *string
	ShareLocationNames *bool
	Locale             *string
}

func (s *Store) UpdateUser(ctx context.Context, q db.DBTX, id uuid.UUID, p UserPatch) (*User, error) {
	return one[User](q.Query(ctx, `
		UPDATE users SET display_name = COALESCE($2, display_name),
			share_location_names = COALESCE($3, share_location_names),
			locale = COALESCE($4, locale)
		WHERE id = $1 RETURNING `+userCols, id, p.DisplayName, p.ShareLocationNames, p.Locale))
}

func (s *Store) SetAvatar(ctx context.Context, id uuid.UUID, key *string) (*User, error) {
	return one[User](s.Pool.Query(ctx, `UPDATE users SET avatar_key = $2 WHERE id = $1 RETURNING `+userCols, id, key))
}

// SoftDeleteUser detaches the identity so a new sign-in creates a fresh user.
func (s *Store) SoftDeleteUser(ctx context.Context, q db.DBTX, id uuid.UUID) error {
	_, err := q.Exec(ctx, `UPDATE users SET sub = NULL, email = NULL, avatar_key = NULL, deleted_at = now() WHERE id = $1`, id)
	return err
}

// ---------------------------------------------------------------- push endpoints

type PushEndpoint struct {
	ID         uuid.UUID `db:"id"`
	UserID     uuid.UUID `db:"user_id"`
	Kind       string    `db:"kind"`
	Endpoint   string    `db:"endpoint"`
	P256dh     *string   `db:"p256dh"`
	Auth       *string   `db:"auth"`
	DeviceName *string   `db:"device_name"`
	Locale     *string   `db:"locale"`
	CreatedAt  time.Time `db:"created_at"`
}

const pushCols = `id, user_id, kind, endpoint, p256dh, auth, device_name, locale, created_at`

func (s *Store) UpsertPushEndpoint(ctx context.Context, e PushEndpoint) (*PushEndpoint, error) {
	return one[PushEndpoint](s.Pool.Query(ctx, `
		INSERT INTO push_endpoints (id, user_id, kind, endpoint, p256dh, auth, device_name, locale)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (user_id, kind, endpoint) DO UPDATE SET p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth,
			device_name = EXCLUDED.device_name, locale = EXCLUDED.locale
		RETURNING `+pushCols, NewID(), e.UserID, e.Kind, e.Endpoint, e.P256dh, e.Auth, e.DeviceName, e.Locale))
}

func (s *Store) ListPushEndpoints(ctx context.Context, userID uuid.UUID) ([]PushEndpoint, error) {
	return many[PushEndpoint](s.Pool.Query(ctx, `SELECT `+pushCols+` FROM push_endpoints WHERE user_id = $1 ORDER BY created_at`, userID))
}

func (s *Store) DeletePushEndpoint(ctx context.Context, userID, id uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM push_endpoints WHERE user_id = $1 AND id = $2`, userID, id)
	return err
}

func (s *Store) DeletePushEndpointByURL(ctx context.Context, endpoint string) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM push_endpoints WHERE endpoint = $1`, endpoint)
	return err
}

// ---------------------------------------------------------------- devices

type Device struct {
	ID                    uuid.UUID       `db:"id"`
	SecretHash            string          `db:"secret_hash"`
	FrameID               *uuid.UUID      `db:"frame_id"`
	Hardware              json.RawMessage `db:"hardware"`
	ScreenW               int             `db:"screen_w"`
	ScreenH               int             `db:"screen_h"`
	DensityDPI            *int            `db:"density_dpi"`
	Orientation           *string         `db:"orientation"`
	DecodeW               int             `db:"decode_w"`
	DecodeH               int             `db:"decode_h"`
	AppVersion            string          `db:"app_version"`
	AppVersionCode        int             `db:"app_version_code"`
	AppChannel            string          `db:"app_channel"`
	Locale                *string         `db:"locale"`
	Timezone              *string         `db:"timezone"`
	CreatedAt             time.Time       `db:"created_at"`
	RevokedAt             *time.Time      `db:"revoked_at"`
	LastSeenAt            *time.Time      `db:"last_seen_at"`
	LastHeartbeat         json.RawMessage `db:"last_heartbeat"`
	LastHeartbeatAt       *time.Time      `db:"last_heartbeat_at"`
	LastUncleanShutdownAt *time.Time      `db:"last_unclean_shutdown_at"`
	// Kind is "android" (the frame app) or "browser" (a virtual frame in a web browser).
	Kind string `db:"kind"`
}

// IsBrowser reports whether the device is a web browser enrolled as a virtual frame.
func (d *Device) IsBrowser() bool { return d.Kind == "browser" }

const deviceCols = `id, secret_hash, frame_id, hardware, screen_w, screen_h, density_dpi, orientation, decode_w, decode_h,
	app_version, app_version_code, app_channel, locale, timezone, created_at, revoked_at, last_seen_at,
	last_heartbeat, last_heartbeat_at, last_unclean_shutdown_at, kind`

func (s *Store) CreateDevice(ctx context.Context, d Device) (*Device, error) {
	return one[Device](s.Pool.Query(ctx, `
		INSERT INTO devices (id, secret_hash, hardware, screen_w, screen_h, density_dpi, orientation, decode_w, decode_h,
			app_version, app_version_code, app_channel, locale, timezone, kind)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, COALESCE(NULLIF($15, ''), 'android'))
		RETURNING `+deviceCols,
		d.ID, d.SecretHash, d.Hardware, d.ScreenW, d.ScreenH, d.DensityDPI, d.Orientation, d.DecodeW, d.DecodeH,
		d.AppVersion, d.AppVersionCode, d.AppChannel, d.Locale, d.Timezone, d.Kind))
}

func (s *Store) GetDevice(ctx context.Context, q db.DBTX, id uuid.UUID) (*Device, error) {
	return one[Device](q.Query(ctx, `SELECT `+deviceCols+` FROM devices WHERE id = $1`, id))
}

// TouchDevice records that the device was seen (at most once a minute).
func (s *Store) TouchDevice(ctx context.Context, id uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `UPDATE devices SET last_seen_at = now() WHERE id = $1 AND (last_seen_at IS NULL OR last_seen_at < now() - interval '1 minute')`, id)
	return err
}

func (s *Store) SetDeviceSeen(ctx context.Context, id uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `UPDATE devices SET last_seen_at = now() WHERE id = $1`, id)
	return err
}

func (s *Store) RecordHeartbeat(ctx context.Context, id uuid.UUID, hb json.RawMessage, appVersion string, appVersionCode *int, unclean bool) error {
	_, err := s.Pool.Exec(ctx, `
		UPDATE devices SET last_heartbeat = $2, last_heartbeat_at = now(), last_seen_at = now(),
			app_version = $3, app_version_code = COALESCE($4, app_version_code),
			last_unclean_shutdown_at = CASE WHEN $5 THEN now() ELSE last_unclean_shutdown_at END
		WHERE id = $1`, id, hb, appVersion, appVersionCode, unclean)
	return err
}

func (tx *Tx) RevokeDevice(ctx context.Context, id uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE devices SET revoked_at = now() WHERE id = $1 AND revoked_at IS NULL`, id)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `DELETE FROM device_codes WHERE device_id = $1`, id)
	return err
}

// ---------------------------------------------------------------- device codes

type DeviceCode struct {
	DeviceID  uuid.UUID `db:"device_id"`
	Code      string    `db:"code"`
	ExpiresAt time.Time `db:"expires_at"`
}

func (s *Store) GetDeviceCode(ctx context.Context, deviceID uuid.UUID) (*DeviceCode, error) {
	return one[DeviceCode](s.Pool.Query(ctx, `SELECT device_id, code, expires_at FROM device_codes WHERE device_id = $1`, deviceID))
}

// PutDeviceCode replaces the device's code. Returns a unique violation error if
// the code is taken (callers retry with another code).
func (s *Store) PutDeviceCode(ctx context.Context, deviceID uuid.UUID, code string, ttl time.Duration) (*DeviceCode, error) {
	if _, err := s.Pool.Exec(ctx, `DELETE FROM device_codes WHERE expires_at < now() - interval '1 day'`); err != nil {
		return nil, err
	}
	return one[DeviceCode](s.Pool.Query(ctx, `
		INSERT INTO device_codes (device_id, code, expires_at) VALUES ($1, $2, now() + make_interval(secs => $3))
		ON CONFLICT (device_id) DO UPDATE SET code = EXCLUDED.code, expires_at = EXCLUDED.expires_at, created_at = now()
		RETURNING device_id, code, expires_at`, deviceID, code, ttl.Seconds()))
}

// LookupCode finds the (non-revoked) device holding a code, expired or not.
func (s *Store) LookupCode(ctx context.Context, q db.DBTX, code string) (*DeviceCode, error) {
	return one[DeviceCode](q.Query(ctx, `
		SELECT c.device_id, c.code, c.expires_at FROM device_codes c JOIN devices d ON d.id = c.device_id
		WHERE c.code = $1 AND d.revoked_at IS NULL`, code))
}

func (tx *Tx) DeleteDeviceCode(ctx context.Context, deviceID uuid.UUID) error {
	_, err := tx.Exec(ctx, `DELETE FROM device_codes WHERE device_id = $1`, deviceID)
	return err
}
