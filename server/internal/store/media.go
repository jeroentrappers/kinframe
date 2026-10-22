package store

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// ---------------------------------------------------------------- uploads

type Upload struct {
	ID          string     `db:"id"`
	UserID      uuid.UUID  `db:"user_id"`
	Filename    *string    `db:"filename"`
	Filetype    *string    `db:"filetype"`
	SHA256      *string    `db:"sha256"`
	Size        int64      `db:"size"`
	CreatedAt   time.Time  `db:"created_at"`
	CompletedAt *time.Time `db:"completed_at"`
	ConsumedAt  *time.Time `db:"consumed_at"`
	// Prepared is the client's preparation profile (media.PreparedPhotoV1, ...).
	Prepared *string `db:"prepared"`
}

const uploadCols = `id, user_id, filename, filetype, sha256, size, created_at, completed_at, consumed_at, prepared`

func (s *Store) CreateUpload(ctx context.Context, u Upload) error {
	_, err := s.Pool.Exec(ctx, `INSERT INTO uploads (id, user_id, filename, filetype, sha256, size, prepared) VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		u.ID, u.UserID, u.Filename, u.Filetype, u.SHA256, u.Size, u.Prepared)
	return err
}

func (s *Store) CompleteUpload(ctx context.Context, id string) error {
	_, err := s.Pool.Exec(ctx, `UPDATE uploads SET completed_at = now() WHERE id = $1`, id)
	return err
}

func (s *Store) GetUpload(ctx context.Context, q db.DBTX, id string) (*Upload, error) {
	return one[Upload](q.Query(ctx, `SELECT `+uploadCols+` FROM uploads WHERE id = $1`, id))
}

// ConsumeUpload marks an upload as referenced by a send; false if it was already used.
func (tx *Tx) ConsumeUpload(ctx context.Context, id string, userID uuid.UUID) (bool, error) {
	tag, err := tx.Exec(ctx, `UPDATE uploads SET consumed_at = now() WHERE id = $1 AND user_id = $2 AND consumed_at IS NULL AND completed_at IS NOT NULL`, id, userID)
	return tag.RowsAffected() == 1, err
}

// ExpiredUploads lists uploads not referenced by a send within maxAge.
func (s *Store) ExpiredUploads(ctx context.Context, maxAge time.Duration) ([]string, error) {
	rows, err := s.Pool.Query(ctx, `SELECT id FROM uploads WHERE consumed_at IS NULL AND created_at < now() - make_interval(secs => $1)`, maxAge.Seconds())
	if err != nil {
		return nil, err
	}
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

func (s *Store) DeleteUpload(ctx context.Context, id string) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM uploads WHERE id = $1`, id)
	return err
}

// FindDuplicateItem returns the newest item of the user whose media came from
// a file with this sha256 and can be reused as-is (not failed, untrimmed, with audio).
func (s *Store) FindDuplicateItem(ctx context.Context, userID uuid.UUID, sha string) (*uuid.UUID, error) {
	var id uuid.UUID
	err := s.Pool.QueryRow(ctx, `
		SELECT i.id FROM items i JOIN media m ON m.id = i.media_id
		WHERE m.owner_user_id = $1 AND m.source_sha256 = $2 AND m.status <> 'failed'
			AND m.trim_start_ms IS NULL AND m.trim_end_ms IS NULL AND m.include_audio
		ORDER BY i.created_at DESC LIMIT 1`, userID, sha).Scan(&id)
	if db.NotFound(err) {
		return nil, nil
	}
	return &id, err
}

// ---------------------------------------------------------------- media

type Media struct {
	ID           uuid.UUID  `db:"id"`
	OwnerUserID  *uuid.UUID `db:"owner_user_id"`
	SourceSHA256 *string    `db:"source_sha256"`
	Kind         string     `db:"kind"`
	Status       string     `db:"status"`
	FailureCode  *string    `db:"failure_code"`
	SourcePath   *string    `db:"source_path"`
	SourceMime   *string    `db:"source_mime"`
	TrimStartMS  *int       `db:"trim_start_ms"`
	TrimEndMS    *int       `db:"trim_end_ms"`
	IncludeAudio bool       `db:"include_audio"`
	Width        *int       `db:"width"`
	Height       *int       `db:"height"`
	DurationMS   *int       `db:"duration_ms"`
	HasAudio     *bool      `db:"has_audio"`
	CapturedAt   *time.Time `db:"captured_at"`
	MasterKey    *string    `db:"master_key"`
	MasterSHA256 *string    `db:"master_sha256"`
	MasterBytes  *int64     `db:"master_bytes"`
	MasterMime   *string    `db:"master_mime"`
	PosterKey    *string    `db:"poster_key"`
	CreatedAt    time.Time  `db:"created_at"`
	UpdatedAt    time.Time  `db:"updated_at"`
	// Prepared is the upload's declared preparation profile, if any.
	Prepared     *string `db:"prepared"`
	Pipeline     *string `db:"pipeline"`
	PipelineNote *string `db:"pipeline_note"`
	// Motion marks the clip of a Live/Motion Photo (no poster, no thumbnails).
	Motion             bool `db:"motion"`
	DeclaredWidth      *int `db:"declared_width"`
	DeclaredHeight     *int `db:"declared_height"`
	DeclaredDurationMS *int `db:"declared_duration_ms"`
}

const mediaCols = `id, owner_user_id, source_sha256, kind, status, failure_code, source_path, source_mime, trim_start_ms, trim_end_ms,
	include_audio, width, height, duration_ms, has_audio, captured_at, master_key, master_sha256, master_bytes, master_mime,
	poster_key, created_at, updated_at, prepared, pipeline, pipeline_note, motion, declared_width, declared_height, declared_duration_ms`

func (tx *Tx) CreateMedia(ctx context.Context, m Media) error {
	_, err := tx.Exec(ctx, `
		INSERT INTO media (id, owner_user_id, kind, source_path, source_mime, trim_start_ms, trim_end_ms, include_audio, duration_ms,
			prepared, motion, declared_width, declared_height, declared_duration_ms, source_sha256)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)`,
		m.ID, m.OwnerUserID, m.Kind, m.SourcePath, m.SourceMime, m.TrimStartMS, m.TrimEndMS, m.IncludeAudio, m.DurationMS,
		m.Prepared, m.Motion, m.DeclaredWidth, m.DeclaredHeight, m.DeclaredDurationMS, m.SourceSHA256)
	return err
}

func (s *Store) GetMedia(ctx context.Context, q db.DBTX, id uuid.UUID) (*Media, error) {
	return one[Media](q.Query(ctx, `SELECT `+mediaCols+` FROM media WHERE id = $1`, id))
}

// MediaMaster records a finished master rendition.
type MediaMaster struct {
	SourceSHA256 string
	Width        int
	Height       int
	DurationMS   *int
	HasAudio     *bool
	CapturedAt   *time.Time
	Key          string
	SHA256       string
	Bytes        int64
	Mime         string
	PosterKey    *string
	// Pipeline is how the master was made: transcode, prepared or fallback.
	Pipeline     string
	PipelineNote *string
}

func (tx *Tx) SetMediaReady(ctx context.Context, id uuid.UUID, m MediaMaster) error {
	_, err := tx.Exec(ctx, `
		UPDATE media SET status = 'ready', source_sha256 = $2, width = $3, height = $4, duration_ms = $5, has_audio = $6,
			captured_at = $7, master_key = $8, master_sha256 = $9, master_bytes = $10, master_mime = $11, poster_key = $12,
			pipeline = $13, pipeline_note = $14, source_path = NULL, updated_at = now()
		WHERE id = $1`, id, m.SourceSHA256, m.Width, m.Height, m.DurationMS, m.HasAudio, m.CapturedAt, m.Key, m.SHA256, m.Bytes, m.Mime, m.PosterKey,
		m.Pipeline, m.PipelineNote)
	return err
}

func (tx *Tx) SetMediaFailed(ctx context.Context, id uuid.UUID, code string) error {
	if _, err := tx.Exec(ctx, `UPDATE media SET status = 'failed', failure_code = $2, source_path = NULL, updated_at = now() WHERE id = $1`, id, code); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `UPDATE placements SET status = 'failed' WHERE item_id IN (SELECT id FROM items WHERE media_id = $1) AND removed_at IS NULL`, id)
	return err
}

type Rendition struct {
	MediaID   uuid.UUID `db:"media_id"`
	Variant   string    `db:"variant"`
	Key       string    `db:"key"`
	SHA256    string    `db:"sha256"`
	Bytes     int64     `db:"bytes"`
	Mime      string    `db:"mime"`
	Width     int       `db:"width"`
	Height    int       `db:"height"`
	PosterKey *string   `db:"poster_key"`
	// ReusedMaster: Key is the master's key (no transcode). Blob clean-up must
	// not delete such a key while the media exists.
	ReusedMaster bool `db:"reused_master"`
}

const renditionCols = `media_id, variant, key, sha256, bytes, mime, width, height, poster_key, reused_master`

func (s *Store) PutRendition(ctx context.Context, r Rendition) error {
	_, err := s.Pool.Exec(ctx, `
		INSERT INTO renditions (media_id, variant, key, sha256, bytes, mime, width, height, poster_key, reused_master)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
		ON CONFLICT (media_id, variant) DO UPDATE SET key = EXCLUDED.key, sha256 = EXCLUDED.sha256, bytes = EXCLUDED.bytes,
			mime = EXCLUDED.mime, width = EXCLUDED.width, height = EXCLUDED.height, poster_key = EXCLUDED.poster_key,
			reused_master = EXCLUDED.reused_master`,
		r.MediaID, r.Variant, r.Key, r.SHA256, r.Bytes, r.Mime, r.Width, r.Height, r.PosterKey, r.ReusedMaster)
	return err
}

func (s *Store) GetRendition(ctx context.Context, mediaID uuid.UUID, variant string) (*Rendition, error) {
	return one[Rendition](s.Pool.Query(ctx, `SELECT `+renditionCols+` FROM renditions WHERE media_id = $1 AND variant = $2`, mediaID, variant))
}

// RenditionsFor loads renditions for several media in one variant-agnostic query.
func (s *Store) RenditionsFor(ctx context.Context, mediaIDs []uuid.UUID) (map[uuid.UUID][]Rendition, error) {
	rs, err := many[Rendition](s.Pool.Query(ctx, `SELECT `+renditionCols+` FROM renditions WHERE media_id = ANY($1)`, mediaIDs))
	if err != nil {
		return nil, err
	}
	out := map[uuid.UUID][]Rendition{}
	for _, r := range rs {
		out[r.MediaID] = append(out[r.MediaID], r)
	}
	return out, nil
}

// MediaTarget is a frame whose current device needs a rendition of a media.
type MediaTarget struct {
	FrameID uuid.UUID `db:"frame_id"`
	ScreenW int       `db:"screen_w"`
	ScreenH int       `db:"screen_h"`
	DecodeW int       `db:"decode_w"`
	DecodeH int       `db:"decode_h"`
}

// MediaTargets lists frames with live placements of the media, with their device geometry.
func (s *Store) MediaTargets(ctx context.Context, mediaID uuid.UUID) ([]MediaTarget, error) {
	return many[MediaTarget](s.Pool.Query(ctx, `
		SELECT DISTINCT f.id AS frame_id, d.screen_w, d.screen_h, d.decode_w, d.decode_h
		FROM placements p JOIN items i ON i.id = p.item_id JOIN frames f ON f.id = p.frame_id AND f.deleted_at IS NULL
		JOIN devices d ON d.id = f.device_id
		WHERE (i.media_id = $1 OR i.motion_media_id = $1) AND p.removed_at IS NULL`, mediaID))
}

// StorageBytes sums stored masters and renditions (renditions that reuse the
// master's bytes are not counted twice).
func (s *Store) StorageBytes(ctx context.Context) (int64, error) {
	var n int64
	err := s.Pool.QueryRow(ctx, `SELECT COALESCE((SELECT sum(master_bytes) FROM media), 0) + COALESCE((SELECT sum(bytes) FROM renditions WHERE NOT reused_master), 0)`).Scan(&n)
	return n, err
}

// PipelineStats counts how masters and video renditions were made.
type PipelineStats struct {
	Transcoded       int `json:"transcoded"`
	Prepared         int `json:"prepared"`
	Fallback         int `json:"fallback"`
	ReusedRenditions int `json:"reused_renditions"`
}

func (s *Store) PipelineStats(ctx context.Context) (PipelineStats, error) {
	var p PipelineStats
	err := s.Pool.QueryRow(ctx, `SELECT
		count(*) FILTER (WHERE pipeline = 'transcode')::int, count(*) FILTER (WHERE pipeline = 'prepared')::int,
		count(*) FILTER (WHERE pipeline = 'fallback')::int, (SELECT count(*) FROM renditions WHERE reused_master)::int
		FROM media`).Scan(&p.Transcoded, &p.Prepared, &p.Fallback, &p.ReusedRenditions)
	return p, err
}

// ---------------------------------------------------------------- items

type Item struct {
	ID            uuid.UUID  `db:"id"`
	MediaID       uuid.UUID  `db:"media_id"`
	SenderUserID  *uuid.UUID `db:"sender_user_id"`
	SenderFrameID *uuid.UUID `db:"sender_frame_id"`
	Caption       *string    `db:"caption"`
	FocalX        *float64   `db:"focal_x"`
	FocalY        *float64   `db:"focal_y"`
	Fit           *string    `db:"fit"`
	CapturedHint  *time.Time `db:"captured_hint"`
	// MotionMediaID is the clip of a Live/Motion Photo.
	MotionMediaID *uuid.UUID `db:"motion_media_id"`
}

func (tx *Tx) CreateItem(ctx context.Context, it Item) error {
	_, err := tx.Exec(ctx, `
		INSERT INTO items (id, media_id, sender_user_id, sender_frame_id, caption, focal_x, focal_y, fit, captured_hint, motion_media_id)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
		it.ID, it.MediaID, it.SenderUserID, it.SenderFrameID, it.Caption, it.FocalX, it.FocalY, it.Fit, it.CapturedHint, it.MotionMediaID)
	return err
}

// ItemRow is an item joined with its media and sender, as the API shows it.
type ItemRow struct {
	ItemID          uuid.UUID  `db:"item_id"`
	MediaID         uuid.UUID  `db:"media_id"`
	SenderUserID    *uuid.UUID `db:"sender_user_id"`
	SenderFrameID   *uuid.UUID `db:"sender_frame_id"`
	SenderName      string     `db:"sender_name"`
	SenderAvatarKey *string    `db:"sender_avatar_key"`
	Caption         *string    `db:"caption"`
	CaptionEdited   bool       `db:"caption_edited"`
	FocalX          *float64   `db:"focal_x"`
	FocalY          *float64   `db:"focal_y"`
	Fit             *string    `db:"fit"`
	Thumb256Key     *string    `db:"thumb256_key"`
	Thumb512Key     *string    `db:"thumb512_key"`
	ItemCreatedAt   time.Time  `db:"item_created_at"`
	CapturedHint    *time.Time `db:"captured_hint"`
	Kind            string     `db:"kind"`
	MediaStatus     string     `db:"media_status"`
	FailureCode     *string    `db:"failure_code"`
	Width           *int       `db:"width"`
	Height          *int       `db:"height"`
	DurationMS      *int       `db:"duration_ms"`
	HasAudio        *bool      `db:"has_audio"`
	CapturedAt      *time.Time `db:"captured_at"`
	MasterKey       *string    `db:"master_key"`
	MasterMime      *string    `db:"master_mime"`
	PosterKey       *string    `db:"poster_key"`
	// The Live/Motion Photo clip, if any.
	MotionMediaID    *uuid.UUID `db:"motion_media_id"`
	MotionStatus     *string    `db:"motion_status"`
	MotionWidth      *int       `db:"motion_width"`
	MotionHeight     *int       `db:"motion_height"`
	MotionDurationMS *int       `db:"motion_duration_ms"`
	MotionHasAudio   *bool      `db:"motion_has_audio"`
	MotionMasterKey  *string    `db:"motion_master_key"`
}

// IsLive reports whether the item is a Live/Motion Photo whose clip has not failed.
func (r *ItemRow) IsLive() bool {
	return r.MotionMediaID != nil && r.MotionStatus != nil && *r.MotionStatus != "failed"
}

const itemRowCols = `i.id AS item_id, i.media_id, i.sender_user_id, i.sender_frame_id,
	COALESCE(su.display_name, sf.name, '') AS sender_name, su.avatar_key AS sender_avatar_key,
	i.caption, i.caption_edited, i.focal_x, i.focal_y, i.fit, i.thumb256_key, i.thumb512_key,
	i.created_at AS item_created_at, i.captured_hint,
	m.kind, m.status AS media_status, m.failure_code, m.width, m.height, m.duration_ms, m.has_audio, m.captured_at,
	m.master_key, m.master_mime, m.poster_key, i.motion_media_id, mm.status AS motion_status, mm.width AS motion_width,
	mm.height AS motion_height, mm.duration_ms AS motion_duration_ms, mm.has_audio AS motion_has_audio,
	mm.master_key AS motion_master_key`

const itemJoins = ` JOIN media m ON m.id = i.media_id LEFT JOIN media mm ON mm.id = i.motion_media_id
	LEFT JOIN users su ON su.id = i.sender_user_id LEFT JOIN frames sf ON sf.id = i.sender_frame_id`

func (s *Store) GetItemRow(ctx context.Context, q db.DBTX, id uuid.UUID) (*ItemRow, error) {
	return one[ItemRow](q.Query(ctx, `SELECT `+itemRowCols+` FROM items i`+itemJoins+` WHERE i.id = $1`, id))
}

func (s *Store) ItemRowsOfMedia(ctx context.Context, mediaID uuid.UUID) ([]ItemRow, error) {
	return many[ItemRow](s.Pool.Query(ctx, `SELECT `+itemRowCols+` FROM items i`+itemJoins+` WHERE i.media_id = $1`, mediaID))
}

type ItemPatch struct {
	Caption      *string
	ClearCaption bool
	FocalX       *float64
	FocalY       *float64
	Fit          *string
}

func (tx *Tx) UpdateItem(ctx context.Context, id uuid.UUID, p ItemPatch) error {
	captionChanged := p.Caption != nil || p.ClearCaption
	_, err := tx.Exec(ctx, `
		UPDATE items SET caption = CASE WHEN $2 THEN NULL WHEN $3::text IS NOT NULL THEN $3 ELSE caption END,
			caption_edited = caption_edited OR $4,
			focal_x = COALESCE($5, focal_x), focal_y = COALESCE($6, focal_y), fit = COALESCE($7, fit)
		WHERE id = $1`, id, p.ClearCaption, p.Caption, captionChanged, p.FocalX, p.FocalY, p.Fit)
	return err
}

func (s *Store) SetItemThumbs(ctx context.Context, id uuid.UUID, k256, k512 string) error {
	_, err := s.Pool.Exec(ctx, `UPDATE items SET thumb256_key = $2, thumb512_key = $3 WHERE id = $1`, id, k256, k512)
	return err
}

// UserCanSeeItem: the sender, a member who shared a live placement of it, or a
// member of a frame with a live placement of the item who may see the library
// (or is a manager).
func (s *Store) UserCanSeeItem(ctx context.Context, userID, itemID uuid.UUID) (bool, error) {
	var ok bool
	err := s.Pool.QueryRow(ctx, `
		SELECT EXISTS (SELECT 1 FROM items WHERE id = $2 AND sender_user_id = $1)
		OR EXISTS (SELECT 1 FROM placements WHERE item_id = $2 AND shared_by_user_id = $1 AND removed_at IS NULL)
		OR EXISTS (
			SELECT 1 FROM placements p JOIN memberships m ON m.frame_id = p.frame_id AND m.user_id = $1
			JOIN frames f ON f.id = p.frame_id AND f.deleted_at IS NULL
			WHERE p.item_id = $2 AND p.removed_at IS NULL
			AND (m.role IN ('owner','admin') OR (m.permissions->>'see_library')::boolean))`, userID, itemID).Scan(&ok)
	return ok, err
}

// ItemFrames lists frames holding live placements of an item.
func (s *Store) ItemFrames(ctx context.Context, q db.DBTX, itemID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT DISTINCT frame_id FROM placements WHERE item_id = $1 AND removed_at IS NULL`, itemID)
	if err != nil {
		return nil, err
	}
	var out []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}
