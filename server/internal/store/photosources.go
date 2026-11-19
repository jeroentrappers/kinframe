package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Photo sources (docs/spec/photo-sources.md §15).

// PhotoSource is a photo_sources row.
type PhotoSource struct {
	ID               uuid.UUID       `db:"id"`
	Provider         string          `db:"provider"`
	OwnerUserID      *uuid.UUID      `db:"owner_user_id"`
	FrameID          *uuid.UUID      `db:"frame_id"`
	AlbumID          *uuid.UUID      `db:"album_id"`
	FeedID           *uuid.UUID      `db:"feed_id"`
	Name             string          `db:"name"`
	URLEnc           []byte          `db:"url_enc"`
	URLHint          *string         `db:"url_hint"`
	ServerURL        *string         `db:"server_url"`
	APIKeyEnc        []byte          `db:"api_key_enc"`
	PasswordEnc      []byte          `db:"password_enc"`
	UpstreamAlbumID  *string         `db:"upstream_album_id"`
	UpstreamKey      string          `db:"upstream_key"`
	UpstreamHost     string          `db:"upstream_host"`
	ResolvedHost     *string         `db:"resolved_host"`
	History          string          `db:"history"`
	HistoryCount     *int            `db:"history_count"`
	OnUpstreamDelete string          `db:"on_upstream_delete"`
	IncludeVideos    bool            `db:"include_videos"`
	NotifyReactions  bool            `db:"notify_reactions"`
	Status           string          `db:"status"`
	Warnings         []string        `db:"warnings"`
	ErrorCode        *string         `db:"error_code"`
	ErrorDetail      *string         `db:"error_detail"`
	Failures         int             `db:"failures"`
	ErrorSince       *time.Time      `db:"error_since"`
	AttentionPushed  json.RawMessage `db:"attention_pushed"`
	PendingRemovals  int             `db:"pending_removals"`
	ChangeHint       *string         `db:"change_hint"`
	FullListingAt    *time.Time      `db:"full_listing_at"`
	UpstreamCount    *int            `db:"upstream_count"`
	InitialDone      bool            `db:"initial_done"`
	HoldSince        *time.Time      `db:"hold_since"`
	LastNewAt        *time.Time      `db:"last_new_at"`
	RunStartedAt     *time.Time      `db:"run_started_at"`
	LastRun          json.RawMessage `db:"last_run"`
	LastSyncedAt     *time.Time      `db:"last_synced_at"`
	LastAttemptAt    *time.Time      `db:"last_attempt_at"`
	NextSyncAt       time.Time       `db:"next_sync_at"`
	LastManualSyncAt *time.Time      `db:"last_manual_sync_at"`
	CreatedAt        time.Time       `db:"created_at"`
	UpdatedAt        time.Time       `db:"updated_at"`
}

const photoSourceCols = `ps.id, ps.provider, ps.owner_user_id, ps.frame_id, ps.album_id, ps.feed_id, ps.name, ps.url_enc, ps.url_hint,
	ps.server_url, ps.api_key_enc, ps.password_enc, ps.upstream_album_id, ps.upstream_key, ps.upstream_host, ps.resolved_host,
	ps.history, ps.history_count, ps.on_upstream_delete, ps.include_videos, ps.notify_reactions, ps.status, ps.warnings,
	ps.error_code, ps.error_detail, ps.failures, ps.error_since, ps.attention_pushed, ps.pending_removals, ps.change_hint,
	ps.full_listing_at, ps.upstream_count, ps.initial_done, ps.hold_since, ps.last_new_at, ps.run_started_at, ps.last_run,
	ps.last_synced_at, ps.last_attempt_at, ps.next_sync_at, ps.last_manual_sync_at, ps.created_at, ps.updated_at`

// PhotoSourceView is a source with the names its API form shows.
type PhotoSourceView struct {
	PhotoSource
	OwnerName      *string `db:"owner_name"`
	OwnerAvatarKey *string `db:"owner_avatar_key"`
	OwnerDeleted   bool    `db:"owner_deleted"`
	AlbumName      *string `db:"album_name"`
	FrameName      *string `db:"frame_name"`
	FeedName       *string `db:"feed_name"`
}

const photoSourceSelect = `SELECT ` + photoSourceCols + `,
	u.display_name AS owner_name, u.avatar_key AS owner_avatar_key, COALESCE(u.deleted_at IS NOT NULL, true) AS owner_deleted,
	a.name AS album_name, f.name AS frame_name, fd.name AS feed_name
	FROM photo_sources ps LEFT JOIN users u ON u.id = ps.owner_user_id LEFT JOIN albums a ON a.id = ps.album_id
	LEFT JOIN frames f ON f.id = ps.frame_id LEFT JOIN feeds fd ON fd.id = ps.feed_id`

// CreatePhotoSource inserts a source; its first run is due now.
func (tx *Tx) CreatePhotoSource(ctx context.Context, p PhotoSource) error {
	_, err := tx.Exec(ctx, `
		INSERT INTO photo_sources (id, provider, owner_user_id, frame_id, album_id, feed_id, name, url_enc, url_hint, server_url,
			api_key_enc, password_enc, upstream_album_id, upstream_key, upstream_host, resolved_host, history, history_count,
			on_upstream_delete, include_videos, notify_reactions, warnings, change_hint, upstream_count, next_sync_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24, $25)`,
		p.ID, p.Provider, p.OwnerUserID, p.FrameID, p.AlbumID, p.FeedID, p.Name, p.URLEnc, p.URLHint, p.ServerURL,
		p.APIKeyEnc, p.PasswordEnc, p.UpstreamAlbumID, p.UpstreamKey, p.UpstreamHost, p.ResolvedHost, p.History, p.HistoryCount,
		p.OnUpstreamDelete, p.IncludeVideos, p.NotifyReactions, nonNil(p.Warnings), p.ChangeHint, p.UpstreamCount, p.NextSyncAt)
	return err
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

// SavePhotoSource writes every mutable column of a source back.
func (tx *Tx) SavePhotoSource(ctx context.Context, p *PhotoSource) error {
	if len(p.AttentionPushed) == 0 {
		p.AttentionPushed = json.RawMessage(`{}`)
	}
	_, err := tx.Exec(ctx, `
		UPDATE photo_sources SET owner_user_id = $2, name = $3, url_enc = $4, url_hint = $5, server_url = $6, api_key_enc = $7,
			password_enc = $8, upstream_album_id = $9, upstream_key = $10, upstream_host = $11, resolved_host = $12,
			on_upstream_delete = $13, include_videos = $14, notify_reactions = $15, status = $16, warnings = $17, error_code = $18,
			error_detail = $19, failures = $20, error_since = $21, attention_pushed = $22, pending_removals = $23, change_hint = $24,
			full_listing_at = $25, upstream_count = $26, initial_done = $27, hold_since = $28, last_new_at = $29, last_run = $30,
			last_synced_at = $31, last_attempt_at = $32, next_sync_at = $33, last_manual_sync_at = $34, updated_at = now()
		WHERE id = $1`,
		p.ID, p.OwnerUserID, p.Name, p.URLEnc, p.URLHint, p.ServerURL, p.APIKeyEnc,
		p.PasswordEnc, p.UpstreamAlbumID, p.UpstreamKey, p.UpstreamHost, p.ResolvedHost,
		p.OnUpstreamDelete, p.IncludeVideos, p.NotifyReactions, p.Status, nonNil(p.Warnings), p.ErrorCode,
		p.ErrorDetail, p.Failures, p.ErrorSince, p.AttentionPushed, p.PendingRemovals, p.ChangeHint,
		p.FullListingAt, p.UpstreamCount, p.InitialDone, p.HoldSince, p.LastNewAt, p.LastRun,
		p.LastSyncedAt, p.LastAttemptAt, p.NextSyncAt, p.LastManualSyncAt)
	return err
}

// GetPhotoSource returns a source with its names.
func (s *Store) GetPhotoSource(ctx context.Context, q db.DBTX, id uuid.UUID) (*PhotoSourceView, error) {
	return one[PhotoSourceView](q.Query(ctx, photoSourceSelect+` WHERE ps.id = $1`, id))
}

// LockPhotoSource locks a source row.
func (tx *Tx) LockPhotoSource(ctx context.Context, id uuid.UUID) (*PhotoSource, error) {
	return one[PhotoSource](tx.Query(ctx, `SELECT `+photoSourceCols+` FROM photo_sources ps WHERE ps.id = $1 FOR UPDATE`, id))
}

// ListPhotoSources lists the sources of an album, a feed or an owner.
func (s *Store) ListPhotoSources(ctx context.Context, albumID, feedID, ownerID *uuid.UUID) ([]PhotoSourceView, error) {
	order := ` ORDER BY ps.created_at, ps.id`
	if ownerID != nil {
		order = ` ORDER BY ps.created_at DESC, ps.id DESC`
	}
	return many[PhotoSourceView](s.Pool.Query(ctx, photoSourceSelect+`
		WHERE ($1::uuid IS NULL OR ps.album_id = $1) AND ($2::uuid IS NULL OR ps.feed_id = $2) AND ($3::uuid IS NULL OR ps.owner_user_id = $3)`+order,
		albumID, feedID, ownerID))
}

// CountPhotoSources counts sources of a target (album or feed) and of an owner.
func (s *Store) CountPhotoSources(ctx context.Context, q db.DBTX, albumID, feedID *uuid.UUID, ownerID uuid.UUID) (target, owned int, err error) {
	err = q.QueryRow(ctx, `SELECT
		(SELECT count(*) FROM photo_sources WHERE ($1::uuid IS NOT NULL AND album_id = $1) OR ($2::uuid IS NOT NULL AND feed_id = $2))::int,
		(SELECT count(*) FROM photo_sources WHERE owner_user_id = $3)::int`, albumID, feedID, ownerID).Scan(&target, &owned)
	return
}

// SourceWithKey finds a source of the target with the same outside album.
func (s *Store) SourceWithKey(ctx context.Context, q db.DBTX, target uuid.UUID, key string, except *uuid.UUID) (*uuid.UUID, error) {
	var id uuid.UUID
	err := q.QueryRow(ctx, `SELECT id FROM photo_sources WHERE COALESCE(album_id, feed_id) = $1 AND upstream_key = $2
		AND ($3::uuid IS NULL OR id <> $3)`, target, key, except).Scan(&id)
	if db.NotFound(err) {
		return nil, nil
	}
	return &id, err
}

// DeletePhotoSource deletes a source (its items rows cascade).
func (tx *Tx) DeletePhotoSource(ctx context.Context, id uuid.UUID) error {
	_, err := tx.Exec(ctx, `DELETE FROM photo_sources WHERE id = $1`, id)
	return err
}

// SourcesOf lists the ids of the sources of an album, a feed or an owner.
func (s *Store) SourcesOf(ctx context.Context, q db.DBTX, albumID, feedID, ownerID *uuid.UUID) ([]PhotoSource, error) {
	return many[PhotoSource](q.Query(ctx, `SELECT `+photoSourceCols+` FROM photo_sources ps
		WHERE ($1::uuid IS NOT NULL AND ps.album_id = $1) OR ($2::uuid IS NOT NULL AND ps.feed_id = $2)
			OR ($3::uuid IS NOT NULL AND ps.owner_user_id = $3)`, albumID, feedID, ownerID))
}

// DuePhotoSource is a source whose next run has come.
type DuePhotoSource struct {
	ID   uuid.UUID `db:"id"`
	Host string    `db:"upstream_host"`
}

// DuePhotoSources lists sources to run: active and error ones on schedule,
// needs_attention ones on their daily probe (next_sync_at is far away for
// codes that are not probed). providers limits to enabled providers.
func (s *Store) DuePhotoSources(ctx context.Context, now time.Time, providers []string, limit int) ([]DuePhotoSource, error) {
	return many[DuePhotoSource](s.Pool.Query(ctx, `SELECT id, upstream_host FROM photo_sources
		WHERE status <> 'paused' AND next_sync_at <= $1 AND provider = ANY($2) AND run_started_at IS NULL
		ORDER BY next_sync_at LIMIT $3`, now, providers, limit))
}

// ClaimPhotoSourceRun marks a source running; false when a run is in
// progress already (another process, or a crash less than stale ago).
func (s *Store) ClaimPhotoSourceRun(ctx context.Context, id uuid.UUID, now time.Time, stale time.Duration) (bool, error) {
	tag, err := s.Pool.Exec(ctx, `UPDATE photo_sources SET run_started_at = $2, last_attempt_at = $2
		WHERE id = $1 AND (run_started_at IS NULL OR run_started_at < $3)`, id, now, now.Add(-stale))
	return tag.RowsAffected() == 1, err
}

// EndPhotoSourceRun clears the running mark.
func (s *Store) EndPhotoSourceRun(ctx context.Context, id uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `UPDATE photo_sources SET run_started_at = NULL WHERE id = $1`, id)
	return err
}

// ResetStaleRuns clears running marks a crashed process left (startup).
func (s *Store) ResetStaleRuns(ctx context.Context) error {
	_, err := s.Pool.Exec(ctx, `UPDATE photo_sources SET run_started_at = NULL WHERE run_started_at IS NOT NULL`)
	return err
}

// SourceCounts are per-state counts of a source's items.
type SourceCounts struct {
	SourceID       uuid.UUID `db:"source_id"`
	Imported       int       `db:"imported"`
	Duplicates     int       `db:"duplicates"`
	Pending        int       `db:"pending"`
	Skipped        int       `db:"skipped"`
	Failed         int       `db:"failed"`
	RemovedLocally int       `db:"removed_locally"`
	GoneUpstream   int       `db:"gone_upstream"`
}

// PhotoSourceCounts counts items per state for sources. Imported counts
// items still live (held feed items included).
func (s *Store) PhotoSourceCounts(ctx context.Context, ids []uuid.UUID) (map[uuid.UUID]SourceCounts, error) {
	rows, err := many[SourceCounts](s.Pool.Query(ctx, `SELECT source_id,
		count(*) FILTER (WHERE state = 'imported')::int AS imported,
		count(*) FILTER (WHERE state = 'duplicate')::int AS duplicates,
		count(*) FILTER (WHERE state = 'pending')::int AS pending,
		count(*) FILTER (WHERE state = 'skipped')::int AS skipped,
		count(*) FILTER (WHERE state = 'failed')::int AS failed,
		count(*) FILTER (WHERE state = 'removed_locally')::int AS removed_locally,
		count(*) FILTER (WHERE state = 'gone_upstream')::int AS gone_upstream
		FROM photo_source_items WHERE source_id = ANY($1) GROUP BY source_id`, ids))
	if err != nil {
		return nil, err
	}
	out := map[uuid.UUID]SourceCounts{}
	for _, r := range rows {
		out[r.SourceID] = r
	}
	return out, nil
}

// ---------------------------------------------------------------- items

// SourceItem is a photo_source_items row: what became of one upstream asset.
type SourceItem struct {
	SourceID           uuid.UUID  `db:"source_id"`
	UpstreamID         string     `db:"upstream_id"`
	State              string     `db:"state"`
	SkipReason         *string    `db:"skip_reason"`
	FailureCode        *string    `db:"failure_code"`
	Attempts           int        `db:"attempts"`
	ItemID             *uuid.UUID `db:"item_id"`
	SendID             *uuid.UUID `db:"send_id"`
	ReplacementMediaID *uuid.UUID `db:"replacement_media_id"`
	UpstreamChecksum   *string    `db:"upstream_checksum"`
	ContentSHA256      *string    `db:"content_sha256"`
	UpstreamCaption    *string    `db:"upstream_caption"`
	UpstreamTakenAt    *time.Time `db:"upstream_taken_at"`
	UpstreamAddedAt    *time.Time `db:"upstream_added_at"`
	UpstreamUpdated    *string    `db:"upstream_updated"`
	Kind               *string    `db:"kind"`
	Bytes              *int64     `db:"bytes"`
	FirstSeenAt        time.Time  `db:"first_seen_at"`
	ImportedAt         *time.Time `db:"imported_at"`
	GoneAt             *time.Time `db:"gone_at"`
	RetryAt            *time.Time `db:"retry_at"`
}

const sourceItemCols = `source_id, upstream_id, state, skip_reason, failure_code, attempts, item_id, send_id, replacement_media_id,
	upstream_checksum, content_sha256, upstream_caption, upstream_taken_at, upstream_added_at, upstream_updated, kind, bytes,
	first_seen_at, imported_at, gone_at, retry_at`

// SourceItems loads every item row of a source.
func (s *Store) SourceItems(ctx context.Context, q db.DBTX, sourceID uuid.UUID) ([]SourceItem, error) {
	return many[SourceItem](q.Query(ctx, `SELECT `+sourceItemCols+` FROM photo_source_items WHERE source_id = $1`, sourceID))
}

// PutSourceItem inserts or replaces an item row; a removed_locally row is
// only replaced by gone_upstream (the sync's own mirror removal).
func (s *Store) PutSourceItem(ctx context.Context, q db.DBTX, it *SourceItem) error {
	if it.FirstSeenAt.IsZero() {
		it.FirstSeenAt = time.Now()
	}
	_, err := q.Exec(ctx, `INSERT INTO photo_source_items (`+sourceItemCols+`)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21)
		ON CONFLICT (source_id, upstream_id) DO UPDATE SET state = EXCLUDED.state, skip_reason = EXCLUDED.skip_reason,
			failure_code = EXCLUDED.failure_code, attempts = EXCLUDED.attempts, item_id = EXCLUDED.item_id, send_id = EXCLUDED.send_id,
			replacement_media_id = EXCLUDED.replacement_media_id, upstream_checksum = EXCLUDED.upstream_checksum,
			content_sha256 = EXCLUDED.content_sha256, upstream_caption = EXCLUDED.upstream_caption,
			upstream_taken_at = EXCLUDED.upstream_taken_at, upstream_added_at = EXCLUDED.upstream_added_at,
			upstream_updated = EXCLUDED.upstream_updated, kind = EXCLUDED.kind, bytes = EXCLUDED.bytes,
			imported_at = EXCLUDED.imported_at, gone_at = EXCLUDED.gone_at, retry_at = EXCLUDED.retry_at
		-- Someone removed it on kinframe meanwhile: that tombstone stays (only the sync's own mirror removal ends it).
		WHERE photo_source_items.state <> 'removed_locally' OR EXCLUDED.state = 'gone_upstream'`,
		it.SourceID, it.UpstreamID, it.State, it.SkipReason, it.FailureCode, it.Attempts, it.ItemID, it.SendID, it.ReplacementMediaID,
		it.UpstreamChecksum, it.ContentSHA256, it.UpstreamCaption, it.UpstreamTakenAt, it.UpstreamAddedAt, it.UpstreamUpdated, it.Kind, it.Bytes,
		it.FirstSeenAt, it.ImportedAt, it.GoneAt, it.RetryAt)
	return err
}

// GetSourceItem returns one row.
func (s *Store) GetSourceItem(ctx context.Context, q db.DBTX, sourceID uuid.UUID, upstreamID string) (*SourceItem, error) {
	return one[SourceItem](q.Query(ctx, `SELECT `+sourceItemCols+` FROM photo_source_items WHERE source_id = $1 AND upstream_id = $2`, sourceID, upstreamID))
}

// markRemovedLocally tombstones source rows whose items someone removed on
// kinframe: album sources of the frame (purge, trash expiry, moderation
// reject, unsend) for placements, feed sources of the feed for post items.
// Mirror-removals by the sync itself overwrite the state afterwards.
func (tx *Tx) markRemovedLocally(ctx context.Context, where string, args ...any) error {
	_, err := tx.Exec(ctx, `UPDATE photo_source_items psi SET state = 'removed_locally' FROM photo_sources ps
		WHERE ps.id = psi.source_id AND psi.state IN ('imported', 'duplicate') AND (`+where+`)`, args...)
	return err
}

// ---------------------------------------------------------------- Google imports

// GoogleImport is a google_imports row.
type GoogleImport struct {
	ID              uuid.UUID   `db:"id"`
	UserID          uuid.UUID   `db:"user_id"`
	FrameID         *uuid.UUID  `db:"frame_id"`
	AlbumID         *uuid.UUID  `db:"album_id"`
	FeedID          *uuid.UUID  `db:"feed_id"`
	TargetKind      string      `db:"target_kind"`
	Status          string      `db:"status"`
	ReturnURI       string      `db:"return_uri"`
	StateHash       *string     `db:"state_hash"`
	AuthURLEnc      []byte      `db:"auth_url_enc"`
	PKCEVerifierEnc []byte      `db:"pkce_verifier_enc"`
	AccessTokenEnc  []byte      `db:"access_token_enc"`
	TokenExpiresAt  *time.Time  `db:"token_expires_at"`
	PickerSessionID *string     `db:"picker_session_id"`
	PickerURI       *string     `db:"picker_uri"`
	PickerExpiresAt *time.Time  `db:"picker_expires_at"`
	PollIntervalMS  *int        `db:"poll_interval_ms"`
	Picked          int         `db:"picked"`
	Imported        int         `db:"imported"`
	Duplicates      int         `db:"duplicates"`
	Skipped         int         `db:"skipped"`
	Failed          int         `db:"failed"`
	Bytes           int64       `db:"bytes"`
	DoneIDs         []string    `db:"done_ids"`
	SendIDs         []uuid.UUID `db:"send_ids"`
	ErrorCode       *string     `db:"error_code"`
	CreatedAt       time.Time   `db:"created_at"`
	UpdatedAt       time.Time   `db:"updated_at"`
	FinishedAt      *time.Time  `db:"finished_at"`
}

const googleImportCols = `id, user_id, frame_id, album_id, feed_id, target_kind, status, return_uri, state_hash, auth_url_enc,
	pkce_verifier_enc, access_token_enc, token_expires_at, picker_session_id, picker_uri, picker_expires_at, poll_interval_ms,
	picked, imported, duplicates, skipped, failed, bytes, done_ids, send_ids, error_code, created_at, updated_at, finished_at`

// GoogleImportView adds the target's names.
type GoogleImportView struct {
	GoogleImport
	AlbumName *string `db:"album_name"`
	FrameName *string `db:"frame_name"`
	FeedName  *string `db:"feed_name"`
}

const googleImportSelect = `SELECT g.id, g.user_id, g.frame_id, g.album_id, g.feed_id, g.target_kind, g.status, g.return_uri, g.state_hash,
	g.auth_url_enc, g.pkce_verifier_enc, g.access_token_enc, g.token_expires_at, g.picker_session_id, g.picker_uri, g.picker_expires_at,
	g.poll_interval_ms, g.picked, g.imported, g.duplicates, g.skipped, g.failed, g.bytes, g.done_ids, g.send_ids, g.error_code,
	g.created_at, g.updated_at, g.finished_at, a.name AS album_name, f.name AS frame_name, fd.name AS feed_name
	FROM google_imports g LEFT JOIN albums a ON a.id = g.album_id LEFT JOIN frames f ON f.id = g.frame_id LEFT JOIN feeds fd ON fd.id = g.feed_id`

// CreateGoogleImport inserts an import.
func (s *Store) CreateGoogleImport(ctx context.Context, g *GoogleImport) error {
	_, err := s.Pool.Exec(ctx, `INSERT INTO google_imports (id, user_id, frame_id, album_id, feed_id, target_kind, status, return_uri,
		state_hash, auth_url_enc, pkce_verifier_enc, access_token_enc, token_expires_at, picker_session_id, picker_uri, picker_expires_at,
		poll_interval_ms) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17)`,
		g.ID, g.UserID, g.FrameID, g.AlbumID, g.FeedID, g.TargetKind, g.Status, g.ReturnURI, g.StateHash, g.AuthURLEnc,
		g.PKCEVerifierEnc, g.AccessTokenEnc, g.TokenExpiresAt, g.PickerSessionID, g.PickerURI, g.PickerExpiresAt, g.PollIntervalMS)
	return err
}

// SaveGoogleImport writes an import's mutable columns back.
func (s *Store) SaveGoogleImport(ctx context.Context, g *GoogleImport) error {
	_, err := s.Pool.Exec(ctx, `UPDATE google_imports SET status = $2, state_hash = $3, auth_url_enc = $4, pkce_verifier_enc = $5,
		access_token_enc = $6, token_expires_at = $7, picker_session_id = $8, picker_uri = $9, picker_expires_at = $10,
		poll_interval_ms = $11, picked = $12, imported = $13, duplicates = $14, skipped = $15, failed = $16, bytes = $17,
		done_ids = $18, send_ids = $19, error_code = $20, finished_at = $21, updated_at = now() WHERE id = $1`,
		g.ID, g.Status, g.StateHash, g.AuthURLEnc, g.PKCEVerifierEnc, g.AccessTokenEnc, g.TokenExpiresAt, g.PickerSessionID,
		g.PickerURI, g.PickerExpiresAt, g.PollIntervalMS, g.Picked, g.Imported, g.Duplicates, g.Skipped, g.Failed, g.Bytes,
		nonNil(g.DoneIDs), nonNilIDs(g.SendIDs), g.ErrorCode, g.FinishedAt)
	return err
}

func nonNilIDs(s []uuid.UUID) []uuid.UUID {
	if s == nil {
		return []uuid.UUID{}
	}
	return s
}

// GetGoogleImport returns an import.
func (s *Store) GetGoogleImport(ctx context.Context, id uuid.UUID) (*GoogleImportView, error) {
	return one[GoogleImportView](s.Pool.Query(ctx, googleImportSelect+` WHERE g.id = $1`, id))
}

// GoogleImportStatus reads only the status (cancel checks between files).
func (s *Store) GoogleImportStatus(ctx context.Context, id uuid.UUID) (string, error) {
	var st string
	err := s.Pool.QueryRow(ctx, `SELECT status FROM google_imports WHERE id = $1`, id).Scan(&st)
	if db.NotFound(err) {
		return "", ErrNotFound
	}
	return st, err
}

// GoogleImportByState finds the import waiting for consent with this state
// hash and clears the hash (single use) in one step.
func (s *Store) GoogleImportByState(ctx context.Context, stateHash string) (*GoogleImport, error) {
	return one[GoogleImport](s.Pool.Query(ctx, `UPDATE google_imports SET state_hash = NULL, updated_at = now()
		WHERE state_hash = $1 RETURNING `+googleImportCols, stateHash))
}

// ListGoogleImports lists a user's imports since a time, newest first.
func (s *Store) ListGoogleImports(ctx context.Context, userID uuid.UUID, since time.Time) ([]GoogleImportView, error) {
	return many[GoogleImportView](s.Pool.Query(ctx, googleImportSelect+` WHERE g.user_id = $1 AND g.created_at >= $2
		ORDER BY g.created_at DESC, g.id DESC`, userID, since))
}

// ActiveGoogleImports lists imports the server still has to drive (picking, importing).
func (s *Store) ActiveGoogleImports(ctx context.Context) ([]uuid.UUID, error) {
	rows, err := s.Pool.Query(ctx, `SELECT id FROM google_imports WHERE status IN ('picking', 'importing') ORDER BY created_at`)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// ReusableGoogleToken returns the newest import of the user whose access
// token lives at least until minUntil (§7.3: skip consent).
func (s *Store) ReusableGoogleToken(ctx context.Context, userID uuid.UUID, minUntil time.Time) (*GoogleImport, error) {
	return one[GoogleImport](s.Pool.Query(ctx, `SELECT `+googleImportCols+` FROM google_imports
		WHERE user_id = $1 AND access_token_enc IS NOT NULL AND token_expires_at > $2 ORDER BY token_expires_at DESC LIMIT 1`, userID, minUntil))
}

// PruneGoogleImports deletes imports finished more than keep ago, clears
// tokens that expired or are older than maxToken, and fails imports that
// waited for consent longer than consent. Returns the timed-out imports.
func (s *Store) PruneGoogleImports(ctx context.Context, now time.Time, keep, maxToken, consent time.Duration) ([]uuid.UUID, error) {
	if _, err := s.Pool.Exec(ctx, `DELETE FROM google_imports WHERE finished_at IS NOT NULL AND finished_at < $1`, now.Add(-keep)); err != nil {
		return nil, err
	}
	if _, err := s.Pool.Exec(ctx, `UPDATE google_imports SET access_token_enc = NULL, token_expires_at = NULL
		WHERE access_token_enc IS NOT NULL AND (token_expires_at < $1 OR created_at < $2)`, now, now.Add(-maxToken)); err != nil {
		return nil, err
	}
	rows, err := s.Pool.Query(ctx, `UPDATE google_imports SET status = 'failed', error_code = 'auth_failed', state_hash = NULL,
		pkce_verifier_enc = NULL, auth_url_enc = NULL, finished_at = $1, updated_at = $1
		WHERE status = 'needs_auth' AND created_at < $2 RETURNING id`, now, now.Add(-consent))
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// LibraryManagerIDs lists the members who manage a frame's library (owner,
// admins, manage_library): the album managers of its albums.
func (s *Store) LibraryManagerIDs(ctx context.Context, frameID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := s.Pool.Query(ctx, `SELECT user_id FROM memberships WHERE frame_id = $1
		AND (role IN ('owner', 'admin') OR (permissions->>'manage_library')::boolean)`, frameID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// SetUpstreamCaption changes an item's caption from upstream unless a
// kinframe user edited it (caption_edited stays false). Reports a change.
func (tx *Tx) SetUpstreamCaption(ctx context.Context, itemID uuid.UUID, caption *string) (bool, error) {
	tag, err := tx.Exec(ctx, `UPDATE items SET caption = $2 WHERE id = $1 AND NOT caption_edited AND caption IS DISTINCT FROM $2`, itemID, caption)
	return tag.RowsAffected() > 0, err
}

// SetCapturedOverride sets the upstream capture time of an item.
func (tx *Tx) SetCapturedOverride(ctx context.Context, itemID uuid.UUID, at *time.Time) (bool, error) {
	tag, err := tx.Exec(ctx, `UPDATE items SET captured_override = $2 WHERE id = $1 AND captured_override IS DISTINCT FROM $2`, itemID, at)
	return tag.RowsAffected() > 0, err
}

// SwapItemMedia points an item at new media (an upstream file changed):
// same item, same placements.
func (tx *Tx) SwapItemMedia(ctx context.Context, itemID, mediaID uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE items SET media_id = $2, thumb256_key = NULL, thumb512_key = NULL WHERE id = $1`, itemID, mediaID)
	return err
}

// LivePlacementsOfItem lists (placement, frame) of an item's live placements, frame-ordered.
func (s *Store) LivePlacementsOfItem(ctx context.Context, q db.DBTX, itemID uuid.UUID) ([][2]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT id, frame_id FROM placements WHERE item_id = $1 AND removed_at IS NULL ORDER BY frame_id, id`, itemID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out [][2]uuid.UUID
	for rows.Next() {
		var p [2]uuid.UUID
		if err := rows.Scan(&p[0], &p[1]); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// DuplicateOnFrame finds a live placement on the frame whose media came
// from a file with this sha256 (or was declared to, original_sha256), or
// whose source row has this upstream checksum. Returns its placement and item.
func (s *Store) DuplicateOnFrame(ctx context.Context, q db.DBTX, frameID uuid.UUID, sha, checksum string) (placementID, itemID uuid.UUID, found bool, err error) {
	err = q.QueryRow(ctx, `SELECT p.id, p.item_id FROM placements p JOIN items i ON i.id = p.item_id JOIN media m ON m.id = i.media_id
		WHERE p.frame_id = $1 AND p.removed_at IS NULL
			AND (($2 <> '' AND (m.source_sha256 = $2 OR m.original_sha256 = $2))
				OR ($3 <> '' AND EXISTS (SELECT 1 FROM photo_source_items psi WHERE psi.item_id = p.item_id AND psi.upstream_checksum = $3)))
		ORDER BY p.received_at LIMIT 1`, frameID, sha, checksum).Scan(&placementID, &itemID)
	if db.NotFound(err) {
		return placementID, itemID, false, nil
	}
	return placementID, itemID, err == nil, err
}

// DuplicateInFeed finds a live post item of the feed with the same file
// (sha256) or upstream checksum.
func (s *Store) DuplicateInFeed(ctx context.Context, q db.DBTX, feedID uuid.UUID, sha, checksum string) (itemID uuid.UUID, found bool, err error) {
	err = q.QueryRow(ctx, `SELECT pi.item_id FROM feed_post_items pi JOIN feed_posts p ON p.id = pi.post_id
		JOIN items i ON i.id = pi.item_id JOIN media m ON m.id = i.media_id
		WHERE p.feed_id = $1 AND p.removed_at IS NULL AND pi.removed_at IS NULL
			AND (($2 <> '' AND (m.source_sha256 = $2 OR m.original_sha256 = $2))
				OR ($3 <> '' AND EXISTS (SELECT 1 FROM photo_source_items psi WHERE psi.item_id = pi.item_id AND psi.upstream_checksum = $3)))
		LIMIT 1`, feedID, sha, checksum).Scan(&itemID)
	if db.NotFound(err) {
		return itemID, false, nil
	}
	return itemID, err == nil, err
}

// LivePostsOfItem lists live posts of a feed holding the item.
func (s *Store) LivePostsOfItem(ctx context.Context, q db.DBTX, feedID, itemID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT p.id FROM feed_post_items pi JOIN feed_posts p ON p.id = pi.post_id
		WHERE p.feed_id = $1 AND pi.item_id = $2 AND pi.removed_at IS NULL AND p.removed_at IS NULL ORDER BY p.id`, feedID, itemID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// SourceSendItem is an item to put in a source send.
type SourceSendItem struct {
	ItemID     uuid.UUID
	MediaID    uuid.UUID
	MotionID   *uuid.UUID
	TakenAt    *time.Time
	ReceivedAt *time.Time // quiet history: when it was added upstream
}

// CreateSourceSend creates a send credited to a photo source or Google
// import (via) with its items in order.
func (tx *Tx) CreateSourceSend(ctx context.Context, id, sender uuid.UUID, via string, sourceID, importID *uuid.UUID, items []SourceSendItem) error {
	if _, err := tx.Exec(ctx, `INSERT INTO sends (id, sender_user_id, via, photo_source_id, google_import_id) VALUES ($1, $2, $3, $4, $5)`,
		id, sender, via, sourceID, importID); err != nil {
		return err
	}
	for i, it := range items {
		if err := tx.AddSendItem(ctx, id, it.ItemID, i); err != nil {
			return err
		}
	}
	return nil
}

// MediaStatus returns a media's status.
func (s *Store) MediaStatus(ctx context.Context, q db.DBTX, id uuid.UUID) (string, error) {
	var st string
	err := q.QueryRow(ctx, `SELECT status FROM media WHERE id = $1`, id).Scan(&st)
	if db.NotFound(err) {
		return "", ErrNotFound
	}
	return st, err
}

// NotifyReactions reports whether reaction notifications for an item go to
// its sender: false only when it came from a source with notify_reactions off.
func (s *Store) NotifyReactions(ctx context.Context, itemID uuid.UUID) (bool, error) {
	var on bool
	// Email: items sent by an address owner on behalf of a non-account sender carry the address (email-inbox.md §9.1).
	err := s.Pool.QueryRow(ctx, `SELECT COALESCE((SELECT ps.notify_reactions FROM items i JOIN photo_sources ps ON ps.id = i.via_source_id
		WHERE i.id = $1), (SELECT ea.notify_reactions FROM items i JOIN email_addresses ea ON ea.id = i.via_email_address_id
		WHERE i.id = $1), true)`, itemID).Scan(&on)
	return on, err
}
