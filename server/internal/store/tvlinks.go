package store

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// TvLink is a revocable share token for showing a frame's library on a TV.
type TvLink struct {
	ID              uuid.UUID   `db:"id"`
	FrameID         uuid.UUID   `db:"frame_id"`
	Token           string      `db:"token"`
	CreatedBy       uuid.UUID   `db:"created_by"`
	CreatedByName   *string     `db:"created_by_name"`
	Purpose         string      `db:"purpose"`
	AlbumID         *uuid.UUID  `db:"album_id"`
	Kind            *string     `db:"kind"`
	PlacementIDs    []uuid.UUID `db:"placement_ids"`
	IntervalSeconds int         `db:"interval_seconds"`
	ExpiresAt       time.Time   `db:"expires_at"`
	RevokedAt       *time.Time  `db:"revoked_at"`
	CreatedAt       time.Time   `db:"created_at"`
}

const tvLinkSelect = `SELECT t.id, t.frame_id, t.token, t.created_by, u.display_name AS created_by_name, t.purpose, t.album_id,
	t.kind, t.placement_ids, t.interval_seconds, t.expires_at, t.revoked_at, t.created_at
	FROM tv_links t LEFT JOIN users u ON u.id = t.created_by`

func (s *Store) CreateTvLink(ctx context.Context, q db.DBTX, l TvLink) (*TvLink, error) {
	if _, err := q.Exec(ctx, `INSERT INTO tv_links (id, frame_id, token, created_by, purpose, album_id, kind, placement_ids,
		interval_seconds, expires_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
		l.ID, l.FrameID, l.Token, l.CreatedBy, l.Purpose, l.AlbumID, l.Kind, l.PlacementIDs, l.IntervalSeconds, l.ExpiresAt); err != nil {
		return nil, err
	}
	return one[TvLink](q.Query(ctx, tvLinkSelect+` WHERE t.id = $1`, l.ID))
}

// ListActiveTvLinks lists unexpired, unrevoked links; createdBy filters unless nil.
func (s *Store) ListActiveTvLinks(ctx context.Context, frameID uuid.UUID, createdBy *uuid.UUID) ([]TvLink, error) {
	return many[TvLink](s.Pool.Query(ctx, tvLinkSelect+`
		WHERE t.frame_id = $1 AND t.revoked_at IS NULL AND t.expires_at > now() AND ($2::uuid IS NULL OR t.created_by = $2)
		ORDER BY t.id DESC`, frameID, createdBy))
}

func (s *Store) GetTvLink(ctx context.Context, frameID, id uuid.UUID) (*TvLink, error) {
	return one[TvLink](s.Pool.Query(ctx, tvLinkSelect+` WHERE t.frame_id = $1 AND t.id = $2`, frameID, id))
}

func (s *Store) GetTvLinkByToken(ctx context.Context, token string) (*TvLink, error) {
	return one[TvLink](s.Pool.Query(ctx, tvLinkSelect+` WHERE t.token = $1`, token))
}

func (s *Store) RevokeTvLink(ctx context.Context, frameID, id uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `UPDATE tv_links SET revoked_at = now() WHERE frame_id = $1 AND id = $2 AND revoked_at IS NULL`, frameID, id)
	return err
}

// VisiblePlacementsByIDs returns the visible (not hidden, trashed, removed or
// awaiting moderation) placements among ids on the frame, in the order of ids.
// onlySender restricts them to that user's own items.
func (s *Store) VisiblePlacementsByIDs(ctx context.Context, frameID uuid.UUID, ids []uuid.UUID, onlySender *uuid.UUID) ([]PlacementRow, error) {
	rows, err := many[PlacementRow](s.Pool.Query(ctx, placementRowSelect+`
		WHERE p.frame_id = $1 AND p.id = ANY($2) AND p.removed_at IS NULL AND NOT p.hidden AND p.trashed_at IS NULL
		AND NOT p.pending_moderation AND ($3::uuid IS NULL OR i.sender_user_id = $3)`, frameID, ids, onlySender))
	if err != nil {
		return nil, err
	}
	byID := make(map[uuid.UUID]PlacementRow, len(rows))
	for _, r := range rows {
		byID[r.Placement.ID] = r
	}
	out := make([]PlacementRow, 0, len(rows))
	for _, id := range ids {
		if r, ok := byID[id]; ok {
			out = append(out, r)
			delete(byID, id)
		}
	}
	return out, nil
}

// CountOwnPlacements counts live placements among ids on the frame whose item
// the user sent.
func (s *Store) CountOwnPlacements(ctx context.Context, frameID uuid.UUID, ids []uuid.UUID, userID uuid.UUID) (int, error) {
	var n int
	err := s.Pool.QueryRow(ctx, `SELECT count(DISTINCT p.id) FROM placements p JOIN items i ON i.id = p.item_id
		WHERE p.frame_id = $1 AND p.id = ANY($2) AND p.removed_at IS NULL AND i.sender_user_id = $3`, frameID, ids, userID).Scan(&n)
	return n, err
}

// ---------------------------------------------------------------- import dedupe

// KnownFileHashes returns the subset of hashes for which the frame has a live
// placement whose media came from (source), or was rendered to (master or a
// rendition), a file with that sha256.
func (s *Store) KnownFileHashes(ctx context.Context, frameID uuid.UUID, hashes []string) ([]string, error) {
	if len(hashes) == 0 {
		return []string{}, nil
	}
	rows, err := s.Pool.Query(ctx, `
		SELECT h FROM unnest($2::text[]) AS h WHERE EXISTS (
			SELECT 1 FROM placements p JOIN items i ON i.id = p.item_id JOIN media m ON m.id = i.media_id
			WHERE p.frame_id = $1 AND p.removed_at IS NULL AND m.status <> 'failed'
			AND (m.source_sha256 = h OR m.master_sha256 = h
				OR EXISTS (SELECT 1 FROM renditions r WHERE r.media_id = m.id AND r.sha256 = h)))`, frameID, hashes)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var h string
		if err := rows.Scan(&h); err != nil {
			return nil, err
		}
		out = append(out, h)
	}
	return out, rows.Err()
}

// PlacementOfFile returns a live placement on the frame for a file hash (see
// KnownFileHashes), or ErrNotFound.
func (s *Store) PlacementOfFile(ctx context.Context, frameID uuid.UUID, sha string) (*PlacementRow, error) {
	return one[PlacementRow](s.Pool.Query(ctx, placementRowSelect+`
		WHERE p.frame_id = $1 AND p.removed_at IS NULL AND m.status <> 'failed'
		AND (m.source_sha256 = $2 OR m.master_sha256 = $2
			OR EXISTS (SELECT 1 FROM renditions r WHERE r.media_id = m.id AND r.sha256 = $2))
		ORDER BY p.received_at DESC LIMIT 1`, frameID, sha))
}

// PlacementOfImport returns the newest placement on the frame of the item it
// imported under this origin key, removed ones included (a re-run of an import
// must not bring back what was deleted), or ErrNotFound.
func (s *Store) PlacementOfImport(ctx context.Context, frameID uuid.UUID, origin, key string) (*PlacementRow, error) {
	return one[PlacementRow](s.Pool.Query(ctx, placementRowSelect+`
		WHERE p.frame_id = $1 AND i.sender_frame_id = $1 AND i.origin = $2 AND i.origin_key = $3
		ORDER BY p.removed_at IS NULL DESC, p.received_at DESC LIMIT 1`, frameID, origin, key))
}

// KnownImportKeys returns the subset of origin keys the frame has imported
// (whatever happened to the items since).
func (s *Store) KnownImportKeys(ctx context.Context, frameID uuid.UUID, origin string, keys []string) ([]string, error) {
	out := []string{}
	if len(keys) == 0 {
		return out, nil
	}
	rows, err := s.Pool.Query(ctx, `
		SELECT k FROM unnest($3::text[]) AS k WHERE EXISTS (
			SELECT 1 FROM items i WHERE i.sender_frame_id = $1 AND i.origin = $2 AND i.origin_key = k)`, frameID, origin, keys)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var k string
		if err := rows.Scan(&k); err != nil {
			return nil, err
		}
		out = append(out, k)
	}
	return out, rows.Err()
}
