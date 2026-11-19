package store

// Link previews (README §5.19): one row per normalized URL, shared by every message that links it.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// JobChatLinkPreview fetches a link preview ({preview_id}); run by the API's chat jobs.
const JobChatLinkPreview = "chat.link_preview"

type LinkPreview struct {
	ID          uuid.UUID  `db:"id"`
	URL         string     `db:"url"`
	Status      string     `db:"status"`
	ErrorCode   *string    `db:"error_code"`
	Title       *string    `db:"title"`
	Description *string    `db:"description"`
	SiteName    *string    `db:"site_name"`
	ImageKey    *string    `db:"image_key"`
	ImageWidth  *int       `db:"image_width"`
	ImageHeight *int       `db:"image_height"`
	FetchedAt   *time.Time `db:"fetched_at"`
	ExpiresAt   *time.Time `db:"expires_at"`
	CreatedAt   time.Time  `db:"created_at"`
}

const linkPreviewCols = `id, url, status, error_code, title, description, site_name, image_key, image_width, image_height,
	fetched_at, expires_at, created_at`

// Fresh: fetched (ok or failed) and not expired.
func (p *LinkPreview) Fresh(now time.Time) bool {
	return p.Status != "pending" && p.ExpiresAt != nil && p.ExpiresAt.After(now)
}

func urlHash(u string) string {
	h := sha256.Sum256([]byte(u))
	return hex.EncodeToString(h[:])
}

// EnsureLinkPreview returns the row of a normalized URL, creating a pending one. stale reports a row that must
// be (re)fetched: new, or expired (it is then set back to pending, keeping what it had until the fetch is done).
func (s *Store) EnsureLinkPreview(ctx context.Context, normalized string) (p *LinkPreview, stale bool, err error) {
	h := urlHash(normalized)
	p, err = one[LinkPreview](s.Pool.Query(ctx, `INSERT INTO link_previews (id, url, url_hash) VALUES ($1, $2, $3)
		ON CONFLICT (url_hash) DO NOTHING RETURNING `+linkPreviewCols, NewID(), normalized, h))
	if err == nil {
		return p, true, nil // new: fetch it
	}
	if err != ErrNotFound {
		return nil, false, err
	}
	p, err = one[LinkPreview](s.Pool.Query(ctx, `SELECT `+linkPreviewCols+` FROM link_previews WHERE url_hash = $1`, h))
	if err != nil {
		return nil, false, err
	}
	if p.Status == "pending" {
		// Being fetched; a fetch that never finished (lost job) is retried after 10 minutes.
		return p, time.Since(p.CreatedAt) > 10*time.Minute && (p.FetchedAt == nil || (p.ExpiresAt != nil && time.Since(*p.ExpiresAt) > 10*time.Minute)), nil
	}
	if p.Fresh(time.Now()) {
		return p, false, nil
	}
	// Expired: claim the refresh once (concurrent senders see it pending already).
	tag, err := s.Pool.Exec(ctx, `UPDATE link_previews SET status = 'pending' WHERE id = $1 AND status <> 'pending'`, p.ID)
	if err != nil {
		return nil, false, err
	}
	p.Status = "pending"
	return p, tag.RowsAffected() == 1, nil
}

func (s *Store) GetLinkPreview(ctx context.Context, q db.DBTX, id uuid.UUID) (*LinkPreview, error) {
	return one[LinkPreview](q.Query(ctx, `SELECT `+linkPreviewCols+` FROM link_previews WHERE id = $1`, id))
}

func (s *Store) LinkPreviewsByID(ctx context.Context, q db.DBTX, ids []uuid.UUID) (map[uuid.UUID]LinkPreview, error) {
	rows, err := many[LinkPreview](q.Query(ctx, `SELECT `+linkPreviewCols+` FROM link_previews WHERE id = ANY($1)`, ids))
	if err != nil {
		return nil, err
	}
	out := make(map[uuid.UUID]LinkPreview, len(rows))
	for _, r := range rows {
		out[r.ID] = r
	}
	return out, nil
}

// LinkPreviewResult is what a fetch found.
type LinkPreviewResult struct {
	OK          bool
	ErrorCode   string
	Title       *string
	Description *string
	SiteName    *string
	ImageKey    *string
	ImageWidth  *int
	ImageHeight *int
	TTL         time.Duration
}

// FinishLinkPreview stores a fetch result and returns the image key it replaced (to delete).
func (s *Store) FinishLinkPreview(ctx context.Context, id uuid.UUID, r LinkPreviewResult) (*string, error) {
	status, code := "ok", (*string)(nil)
	if !r.OK {
		status, code = "failed", &r.ErrorCode
	}
	var old *string
	err := s.Pool.QueryRow(ctx, `UPDATE link_previews p SET status = $2, error_code = $3, title = $4, description = $5, site_name = $6,
			image_key = $7, image_width = $8, image_height = $9, fetched_at = now(), expires_at = now() + make_interval(secs => $10)
		FROM (SELECT image_key FROM link_previews WHERE id = $1 FOR UPDATE) o WHERE p.id = $1 RETURNING o.image_key`,
		id, status, code, r.Title, r.Description, r.SiteName, r.ImageKey, r.ImageWidth, r.ImageHeight, r.TTL.Seconds()).Scan(&old)
	if db.NotFound(err) {
		return nil, nil
	}
	if old != nil && r.ImageKey != nil && *old == *r.ImageKey {
		old = nil
	}
	return old, err
}

// MessagesWithPreview lists live messages that show a link preview.
func (s *Store) MessagesWithPreview(ctx context.Context, previewID uuid.UUID) ([]uuid.UUID, error) {
	return uuids(s.Pool.Query(ctx, `SELECT id FROM messages WHERE link_preview_id = $1 AND deleted_at IS NULL ORDER BY id DESC LIMIT 500`, previewID))
}

// PruneLinkPreviews deletes previews expired for a month that no message shows, returning their image keys.
func (s *Store) PruneLinkPreviews(ctx context.Context) ([]string, error) {
	rows, err := s.Pool.Query(ctx, `DELETE FROM link_previews p WHERE p.expires_at < now() - interval '30 days'
		AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.link_preview_id = p.id) RETURNING p.image_key`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var keys []string
	for rows.Next() {
		var k *string
		if err := rows.Scan(&k); err != nil {
			return nil, err
		}
		if k != nil {
			keys = append(keys, *k)
		}
	}
	return keys, rows.Err()
}
