package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// FeedCalendar is the feed kind of a frame's `calendar` change (entity = frame id).
const FeedCalendar = "calendar"

// CalendarSource is one row of calendar_sources.
type CalendarSource struct {
	ID               uuid.UUID       `db:"id"`
	FrameID          uuid.UUID       `db:"frame_id"`
	Kind             string          `db:"kind"`
	Name             string          `db:"name"`
	Color            string          `db:"color"`
	Enabled          bool            `db:"enabled"`
	Position         int             `db:"position"`
	URLEnc           []byte          `db:"url_enc"`
	URLHint          *string         `db:"url_hint"`
	Username         *string         `db:"username"`
	PasswordEnc      []byte          `db:"password_enc"`
	CollectionURLEnc []byte          `db:"collection_url_enc"`
	ETag             *string         `db:"etag"`
	LastModified     *string         `db:"last_modified"`
	Raw              []byte          `db:"raw"`
	Status           string          `db:"status"`
	LastError        *string         `db:"last_error"`
	LastSyncedAt     *time.Time      `db:"last_synced_at"`
	LastAttemptAt    *time.Time      `db:"last_attempt_at"`
	Failures         int             `db:"failures"`
	NextSyncAt       time.Time       `db:"next_sync_at"`
	WindowKey        *string         `db:"window_key"`
	Events           json.RawMessage `db:"events"`
	EventCount       int             `db:"event_count"`
	CreatedBy        *uuid.UUID      `db:"created_by"`
	CreatedAt        time.Time       `db:"created_at"`
}

const calendarSourceCols = `id, frame_id, kind, name, color, enabled, position, url_enc, url_hint, username, password_enc,
	collection_url_enc, etag, last_modified, raw, status, last_error, last_synced_at, last_attempt_at, failures,
	next_sync_at, window_key, events, event_count, created_by, created_at`

// calendarSourceListCols leaves out the heavy raw data and expansion.
const calendarSourceListCols = `id, frame_id, kind, name, color, enabled, position, url_enc, url_hint, username, password_enc,
	collection_url_enc, etag, last_modified, NULL::bytea AS raw, status, last_error, last_synced_at, last_attempt_at,
	failures, next_sync_at, window_key, NULL::jsonb AS events, event_count, created_by, created_at`

// CalendarSourceOrder lists the local calendar first, then by position and age.
const calendarSourceOrder = `ORDER BY (kind = 'local') DESC, position, created_at, id`

// ListCalendarSources returns a frame's sources without raw data.
func (s *Store) ListCalendarSources(ctx context.Context, frameID uuid.UUID) ([]CalendarSource, error) {
	return many[CalendarSource](s.Pool.Query(ctx, `SELECT `+calendarSourceListCols+` FROM calendar_sources
		WHERE frame_id = $1 `+calendarSourceOrder, frameID))
}

// CalendarSourcesFull returns a frame's sources including raw data and expansions.
func (s *Store) CalendarSourcesFull(ctx context.Context, frameID uuid.UUID) ([]CalendarSource, error) {
	return many[CalendarSource](s.Pool.Query(ctx, `SELECT `+calendarSourceCols+` FROM calendar_sources
		WHERE frame_id = $1 `+calendarSourceOrder, frameID))
}

// GetCalendarSource returns one source (with raw data), or ErrNotFound.
func (s *Store) GetCalendarSource(ctx context.Context, id uuid.UUID) (*CalendarSource, error) {
	return one[CalendarSource](s.Pool.Query(ctx, `SELECT `+calendarSourceCols+` FROM calendar_sources WHERE id = $1`, id))
}

// CreateCalendarSource inserts a source at the end of the frame's list.
func (s *Store) CreateCalendarSource(ctx context.Context, c CalendarSource) (*CalendarSource, error) {
	return one[CalendarSource](s.Pool.Query(ctx, `
		INSERT INTO calendar_sources (id, frame_id, kind, name, color, enabled, position, url_enc, url_hint, username,
			password_enc, collection_url_enc, status, next_sync_at, created_by)
		VALUES ($1, $2, $3, $4, $5, $6,
			(SELECT coalesce(max(position), 0) + 1 FROM calendar_sources WHERE frame_id = $2),
			$7, $8, $9, $10, $11, $12, now(), $13)
		RETURNING `+calendarSourceListCols,
		c.ID, c.FrameID, c.Kind, c.Name, c.Color, c.Enabled, c.URLEnc, c.URLHint, c.Username, c.PasswordEnc,
		c.CollectionURLEnc, c.Status, c.CreatedBy))
}

// EnsureLocalCalendar returns the frame's local calendar, creating it if needed.
func (s *Store) EnsureLocalCalendar(ctx context.Context, q db.DBTX, frameID uuid.UUID, color string) (uuid.UUID, error) {
	var id uuid.UUID
	err := q.QueryRow(ctx, `
		INSERT INTO calendar_sources (id, frame_id, kind, name, color, status, position)
		VALUES ($1, $2, 'local', '', $3, 'ok', 0)
		ON CONFLICT (frame_id) WHERE kind = 'local' DO UPDATE SET updated_at = calendar_sources.updated_at
		RETURNING id`, NewID(), frameID, color).Scan(&id)
	return id, err
}

// UpdateCalendarSource saves the editable fields. A changed connection
// (connChanged) resets the cached data and schedules a sync right away.
func (s *Store) UpdateCalendarSource(ctx context.Context, c *CalendarSource, connChanged bool) (*CalendarSource, error) {
	return one[CalendarSource](s.Pool.Query(ctx, `
		UPDATE calendar_sources SET name = $2, color = $3, enabled = $4, url_enc = $5, url_hint = $6, username = $7,
			password_enc = $8, collection_url_enc = $9, updated_at = now(),
			etag = CASE WHEN $10 THEN NULL ELSE etag END,
			last_modified = CASE WHEN $10 THEN NULL ELSE last_modified END,
			failures = CASE WHEN $10 OR NOT enabled THEN 0 ELSE failures END,
			status = CASE WHEN NOT $4 THEN 'disabled' WHEN $10 OR NOT enabled OR status = 'disabled' THEN 'syncing' ELSE status END,
			next_sync_at = CASE WHEN $10 OR (NOT enabled AND $4) THEN now() ELSE next_sync_at END
		WHERE id = $1
		RETURNING `+calendarSourceListCols,
		c.ID, c.Name, c.Color, c.Enabled, c.URLEnc, c.URLHint, c.Username, c.PasswordEnc, c.CollectionURLEnc, connChanged))
}

// DeleteCalendarSource removes a source (and, for the local one, clears the accepted events).
func (tx *Tx) DeleteCalendarSource(ctx context.Context, c *CalendarSource) error {
	if _, err := tx.Exec(ctx, `DELETE FROM calendar_sources WHERE id = $1`, c.ID); err != nil {
		return err
	}
	if c.Kind == "local" {
		_, err := tx.Exec(ctx, `UPDATE event_invitations SET cleared_at = now() WHERE frame_id = $1 AND status = 'accepted' AND cleared_at IS NULL`, c.FrameID)
		return err
	}
	return nil
}

// RequestCalendarSync makes sources due now and marks them syncing. only
// limits it to one source; otherwise every enabled remote source of the frame.
func (s *Store) RequestCalendarSync(ctx context.Context, frameID uuid.UUID, only *uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `
		UPDATE calendar_sources SET next_sync_at = now(), status = 'syncing'
		WHERE frame_id = $1 AND kind <> 'local' AND enabled AND ($2::uuid IS NULL OR id = $2)`, frameID, only)
	return err
}

// DueCalendarSources lists enabled remote sources whose sync is due, oldest first.
func (s *Store) DueCalendarSources(ctx context.Context, now time.Time, limit int) ([]CalendarSource, error) {
	return many[CalendarSource](s.Pool.Query(ctx, `
		SELECT `+calendarSourceCols+` FROM calendar_sources cs
		WHERE cs.kind <> 'local' AND cs.enabled AND cs.next_sync_at <= $1
		  AND EXISTS (SELECT 1 FROM frames f WHERE f.id = cs.frame_id AND f.deleted_at IS NULL)
		ORDER BY cs.next_sync_at LIMIT $2`, now, limit))
}

// CalendarSyncing marks a sync attempt as started.
func (s *Store) CalendarSyncing(ctx context.Context, id uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `UPDATE calendar_sources SET status = 'syncing', last_attempt_at = now() WHERE id = $1`, id)
	return err
}

// CalendarSynced stores a successful sync.
func (s *Store) CalendarSynced(ctx context.Context, id uuid.UUID, raw []byte, etag, lastMod *string, next time.Time, windowKey string, events json.RawMessage, count int) error {
	_, err := s.Pool.Exec(ctx, `
		UPDATE calendar_sources SET raw = $2, etag = $3, last_modified = $4, status = 'ok', last_error = NULL, failures = 0,
			last_synced_at = now(), last_attempt_at = now(), next_sync_at = $5, window_key = $6, events = $7, event_count = $8
		WHERE id = $1 AND enabled`, id, raw, etag, lastMod, next, windowKey, events, count)
	return err
}

// CalendarSyncFailed records a failed sync; the previous events are kept.
func (s *Store) CalendarSyncFailed(ctx context.Context, id uuid.UUID, status, cause string, failures int, next time.Time) error {
	_, err := s.Pool.Exec(ctx, `
		UPDATE calendar_sources SET status = $2, last_error = $3, failures = $4, next_sync_at = $5, last_attempt_at = now()
		WHERE id = $1 AND enabled`, id, status, cause, failures, next)
	return err
}

// CalendarExpanded stores a re-expansion of the cached data for a new window.
func (s *Store) CalendarExpanded(ctx context.Context, id uuid.UUID, windowKey string, events json.RawMessage, count int) error {
	_, err := s.Pool.Exec(ctx, `UPDATE calendar_sources SET window_key = $2, events = $3, event_count = $4 WHERE id = $1`,
		id, windowKey, events, count)
	return err
}

// ---------------------------------------------------------------- frames with calendars

// CalendarFrame is a frame that has calendar sources, countdowns or a sent calendar.
type CalendarFrame struct {
	FrameID   uuid.UUID       `db:"id"`
	Settings  json.RawMessage `db:"settings"`
	WindowKey *string         `db:"window_key"`
}

// CalendarFrames lists live frames with calendar state.
func (s *Store) CalendarFrames(ctx context.Context) ([]CalendarFrame, error) {
	return many[CalendarFrame](s.Pool.Query(ctx, `
		SELECT f.id, f.settings, fc.window_key FROM frames f LEFT JOIN frame_calendar fc ON fc.frame_id = f.id
		WHERE f.deleted_at IS NULL AND (fc.frame_id IS NOT NULL
			OR EXISTS (SELECT 1 FROM calendar_sources cs WHERE cs.frame_id = f.id)
			OR EXISTS (SELECT 1 FROM calendar_countdowns cc WHERE cc.frame_id = f.id))
		ORDER BY f.id`))
}

// FrameCalendarState is what a frame was last sent.
type FrameCalendarState struct {
	Digest    string          `db:"digest"`
	WindowKey string          `db:"window_key"`
	Payload   json.RawMessage `db:"payload"`
}

// GetFrameCalendar returns the last sent calendar, or nil.
func (s *Store) GetFrameCalendar(ctx context.Context, q db.DBTX, frameID uuid.UUID) (*FrameCalendarState, error) {
	st, err := one[FrameCalendarState](q.Query(ctx, `SELECT digest, window_key, payload FROM frame_calendar WHERE frame_id = $1`, frameID))
	if err == ErrNotFound {
		return nil, nil
	}
	return st, err
}

// SetFrameCalendar stores a new payload (nil = "no calendar") and appends a `calendar` change.
func (tx *Tx) SetFrameCalendar(ctx context.Context, frameID uuid.UUID, digest, windowKey string, payload json.RawMessage) error {
	if _, err := tx.Exec(ctx, `
		INSERT INTO frame_calendar (frame_id, digest, window_key, payload, emitted_at) VALUES ($1, $2, $3, $4, now())
		ON CONFLICT (frame_id) DO UPDATE SET digest = EXCLUDED.digest, window_key = EXCLUDED.window_key,
			payload = EXCLUDED.payload, emitted_at = now()`, frameID, digest, windowKey, payload); err != nil {
		return err
	}
	return tx.FeedPut(ctx, frameID, FeedCalendar, frameID, false)
}

// ---------------------------------------------------------------- countdowns

// Countdown is a marked event with its snapshot.
type Countdown struct {
	EventID   string     `db:"event_id"`
	SourceID  *uuid.UUID `db:"source_id"`
	Title     string     `db:"title"`
	AllDay    bool       `db:"all_day"`
	StartAt   *time.Time `db:"start_at"`
	StartDate *time.Time `db:"start_date"`
	Color     string     `db:"color"`
}

func (s *Store) ListCountdowns(ctx context.Context, frameID uuid.UUID) ([]Countdown, error) {
	return many[Countdown](s.Pool.Query(ctx, `
		SELECT event_id, source_id, title, all_day, start_at, start_date, color FROM calendar_countdowns
		WHERE frame_id = $1 ORDER BY created_at`, frameID))
}

// PutCountdown inserts or refreshes a countdown snapshot.
func (s *Store) PutCountdown(ctx context.Context, q db.DBTX, frameID uuid.UUID, c Countdown) error {
	_, err := q.Exec(ctx, `
		INSERT INTO calendar_countdowns (frame_id, event_id, source_id, title, all_day, start_at, start_date, color)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (frame_id, event_id) DO UPDATE SET source_id = EXCLUDED.source_id, title = EXCLUDED.title,
			all_day = EXCLUDED.all_day, start_at = EXCLUDED.start_at, start_date = EXCLUDED.start_date, color = EXCLUDED.color`,
		frameID, c.EventID, c.SourceID, c.Title, c.AllDay, c.StartAt, c.StartDate, c.Color)
	return err
}

func (s *Store) DeleteCountdown(ctx context.Context, frameID uuid.UUID, eventID string) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM calendar_countdowns WHERE frame_id = $1 AND event_id = $2`, frameID, eventID)
	return err
}

// PruneCountdowns removes countdowns whose event passed more than a day ago.
func (s *Store) PruneCountdowns(ctx context.Context) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM calendar_countdowns
		WHERE coalesce(start_at, start_date::timestamptz) < now() - interval '2 days'`)
	return err
}

// ---------------------------------------------------------------- invitations

// Invitation is one event sent to a frame.
type Invitation struct {
	ID             uuid.UUID  `db:"id"`
	FrameID        uuid.UUID  `db:"frame_id"`
	SenderID       *uuid.UUID `db:"sender_id"`
	SenderName     string     `db:"sender_name"`
	Status         string     `db:"status"`
	Title          string     `db:"title"`
	AllDay         bool       `db:"all_day"`
	StartAt        *time.Time `db:"start_at"`
	EndAt          *time.Time `db:"end_at"`
	StartDate      *time.Time `db:"start_date"`
	EndDate        *time.Time `db:"end_date"`
	Location       *string    `db:"location"`
	Notes          *string    `db:"notes"`
	PhotoKey       *string    `db:"photo_key"`
	PhotoSHA256    *string    `db:"photo_sha256"`
	PhotoPlacement string     `db:"photo_placement"`
	CreatedAt      time.Time  `db:"created_at"`
	DecidedAt      *time.Time `db:"decided_at"`
	ClearedAt      *time.Time `db:"cleared_at"`
}

const invitationCols = `id, frame_id, sender_id, sender_name, status, title, all_day, start_at, end_at, start_date, end_date,
	location, notes, photo_key, photo_sha256, photo_placement, created_at, decided_at, cleared_at`

func (s *Store) CreateInvitation(ctx context.Context, q db.DBTX, in Invitation) (*Invitation, error) {
	return one[Invitation](q.Query(ctx, `
		INSERT INTO event_invitations (id, frame_id, sender_id, sender_name, title, all_day, start_at, end_at, start_date,
			end_date, location, notes, photo_key, photo_sha256, photo_placement)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)
		RETURNING `+invitationCols,
		in.ID, in.FrameID, in.SenderID, in.SenderName, in.Title, in.AllDay, in.StartAt, in.EndAt, in.StartDate, in.EndDate,
		in.Location, in.Notes, in.PhotoKey, in.PhotoSHA256, in.PhotoPlacement))
}

func (s *Store) GetInvitation(ctx context.Context, id uuid.UUID) (*Invitation, error) {
	return one[Invitation](s.Pool.Query(ctx, `SELECT `+invitationCols+` FROM event_invitations WHERE id = $1`, id))
}

// ListInvitations lists a frame's invitations, newest first; sender limits them to one sender.
func (s *Store) ListInvitations(ctx context.Context, frameID uuid.UUID, sender *uuid.UUID) ([]Invitation, error) {
	return many[Invitation](s.Pool.Query(ctx, `SELECT `+invitationCols+` FROM event_invitations
		WHERE frame_id = $1 AND ($2::uuid IS NULL OR sender_id = $2) ORDER BY created_at DESC LIMIT 200`, frameID, sender))
}

// PendingInvitations lists invitations waiting for the frame, oldest first.
func (s *Store) PendingInvitations(ctx context.Context, frameID uuid.UUID) ([]Invitation, error) {
	return many[Invitation](s.Pool.Query(ctx, `SELECT `+invitationCols+` FROM event_invitations
		WHERE frame_id = $1 AND status = 'pending' ORDER BY created_at`, frameID))
}

// AcceptedInvitations are the local calendar's events.
func (s *Store) AcceptedInvitations(ctx context.Context, frameID uuid.UUID) ([]Invitation, error) {
	return many[Invitation](s.Pool.Query(ctx, `SELECT `+invitationCols+` FROM event_invitations
		WHERE frame_id = $1 AND status = 'accepted' AND cleared_at IS NULL ORDER BY created_at`, frameID))
}

// DecideInvitation moves a pending invitation to status; false when it was not pending.
func (s *Store) DecideInvitation(ctx context.Context, q db.DBTX, id, frameID uuid.UUID, status string) (*Invitation, error) {
	inv, err := one[Invitation](q.Query(ctx, `
		UPDATE event_invitations SET status = $3, decided_at = now()
		WHERE id = $1 AND frame_id = $2 AND status = 'pending' RETURNING `+invitationCols, id, frameID, status))
	if err == ErrNotFound {
		return nil, nil
	}
	return inv, err
}

// FrameHasInvitationPhoto is used to authorise photo downloads.
func (s *Store) InvitationPhotoKey(ctx context.Context, id, frameID uuid.UUID) (*string, *string, error) {
	var key, sha *string
	err := s.Pool.QueryRow(ctx, `SELECT photo_key, photo_sha256 FROM event_invitations WHERE id = $1 AND frame_id = $2`, id, frameID).Scan(&key, &sha)
	if err == pgx.ErrNoRows {
		return nil, nil, ErrNotFound
	}
	return key, sha, err
}
