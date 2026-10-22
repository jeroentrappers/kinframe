package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

type Album struct {
	ID               uuid.UUID       `db:"id"`
	FrameID          uuid.UUID       `db:"frame_id"`
	Name             string          `db:"name"`
	Version          int             `db:"version"`
	CoverPlacementID *uuid.UUID      `db:"cover_placement_id"`
	Schedule         json.RawMessage `db:"schedule"`
	CreatedAt        time.Time       `db:"created_at"`
	PlacementIDs     []uuid.UUID     `db:"placement_ids"`
}

const albumSelect = `SELECT a.id, a.frame_id, a.name, a.version, a.cover_placement_id, a.schedule, a.created_at,
	COALESCE((SELECT array_agg(ap.placement_id ORDER BY ap.position) FROM album_placements ap
		JOIN placements p ON p.id = ap.placement_id AND p.removed_at IS NULL WHERE ap.album_id = a.id), '{}') AS placement_ids
	FROM albums a`

func (s *Store) ListAlbums(ctx context.Context, frameID uuid.UUID) ([]Album, error) {
	return many[Album](s.Pool.Query(ctx, albumSelect+` WHERE a.frame_id = $1 ORDER BY a.name, a.id`, frameID))
}

func (s *Store) GetAlbum(ctx context.Context, q db.DBTX, frameID, id uuid.UUID) (*Album, error) {
	return one[Album](q.Query(ctx, albumSelect+` WHERE a.frame_id = $1 AND a.id = $2`, frameID, id))
}

func (s *Store) AlbumsByIDs(ctx context.Context, ids []uuid.UUID) (map[uuid.UUID]Album, error) {
	as, err := many[Album](s.Pool.Query(ctx, albumSelect+` WHERE a.id = ANY($1)`, ids))
	if err != nil {
		return nil, err
	}
	out := map[uuid.UUID]Album{}
	for _, a := range as {
		out[a.ID] = a
	}
	return out, nil
}

// AlbumWrite is a full or partial album write; nil fields are left unchanged.
type AlbumWrite struct {
	Name             *string
	PlacementIDs     *[]uuid.UUID
	CoverPlacementID *uuid.UUID
	Schedule         *json.RawMessage // JSON null clears
}

func (tx *Tx) CreateAlbum(ctx context.Context, frameID uuid.UUID, w AlbumWrite) (uuid.UUID, error) {
	id := NewID()
	var sched any
	if w.Schedule != nil && string(*w.Schedule) != "null" {
		sched = *w.Schedule
	}
	if _, err := tx.Exec(ctx, `INSERT INTO albums (id, frame_id, name, cover_placement_id, schedule) VALUES ($1, $2, $3, $4, $5)`,
		id, frameID, *w.Name, w.CoverPlacementID, sched); err != nil {
		return uuid.Nil, err
	}
	if w.PlacementIDs != nil {
		if err := tx.setAlbumPlacements(ctx, frameID, id, *w.PlacementIDs); err != nil {
			return uuid.Nil, err
		}
	}
	return id, tx.FeedPut(ctx, frameID, FeedAlbum, id, false)
}

// UpdateAlbum applies w if the album is at version; ok is false on a version mismatch.
func (tx *Tx) UpdateAlbum(ctx context.Context, frameID, id uuid.UUID, version int, w AlbumWrite) (bool, error) {
	var sched any
	setSched := w.Schedule != nil
	if setSched && string(*w.Schedule) != "null" {
		sched = *w.Schedule
	}
	tag, err := tx.Exec(ctx, `
		UPDATE albums SET name = COALESCE($4, name), cover_placement_id = COALESCE($5, cover_placement_id),
			schedule = CASE WHEN $6 THEN $7::jsonb ELSE schedule END, version = version + 1
		WHERE frame_id = $1 AND id = $2 AND version = $3`, frameID, id, version, w.Name, w.CoverPlacementID, setSched, sched)
	if err != nil || tag.RowsAffected() == 0 {
		return false, err
	}
	if w.PlacementIDs != nil {
		if _, err := tx.Exec(ctx, `DELETE FROM album_placements WHERE album_id = $1`, id); err != nil {
			return false, err
		}
		if err := tx.setAlbumPlacements(ctx, frameID, id, *w.PlacementIDs); err != nil {
			return false, err
		}
	}
	return true, tx.FeedPut(ctx, frameID, FeedAlbum, id, false)
}

// setAlbumPlacements inserts placements in order, ignoring ones not on the frame.
func (tx *Tx) setAlbumPlacements(ctx context.Context, frameID, albumID uuid.UUID, ids []uuid.UUID) error {
	_, err := tx.Exec(ctx, `
		INSERT INTO album_placements (album_id, placement_id, position)
		SELECT $1, x.id, x.ord FROM unnest($2::uuid[]) WITH ORDINALITY AS x(id, ord)
		JOIN placements p ON p.id = x.id AND p.frame_id = $3 AND p.removed_at IS NULL
		ON CONFLICT DO NOTHING`, albumID, ids, frameID)
	return err
}

// AddToAlbum appends placements; RemoveFromAlbum removes them.
func (tx *Tx) AddToAlbum(ctx context.Context, frameID, albumID uuid.UUID, ids []uuid.UUID) error {
	_, err := tx.Exec(ctx, `
		INSERT INTO album_placements (album_id, placement_id, position)
		SELECT $1, x.id, COALESCE((SELECT max(position) FROM album_placements WHERE album_id = $1), 0) + x.ord
		FROM unnest($2::uuid[]) WITH ORDINALITY AS x(id, ord)
		JOIN placements p ON p.id = x.id AND p.frame_id = $3 AND p.removed_at IS NULL
		ON CONFLICT DO NOTHING`, albumID, ids, frameID)
	if err != nil {
		return err
	}
	return tx.bumpAlbum(ctx, frameID, albumID)
}

func (tx *Tx) RemoveFromAlbum(ctx context.Context, frameID, albumID uuid.UUID, ids []uuid.UUID) error {
	if _, err := tx.Exec(ctx, `DELETE FROM album_placements WHERE album_id = $1 AND placement_id = ANY($2)`, albumID, ids); err != nil {
		return err
	}
	return tx.bumpAlbum(ctx, frameID, albumID)
}

func (tx *Tx) bumpAlbum(ctx context.Context, frameID, albumID uuid.UUID) error {
	if _, err := tx.Exec(ctx, `UPDATE albums SET version = version + 1 WHERE id = $1`, albumID); err != nil {
		return err
	}
	return tx.FeedPut(ctx, frameID, FeedAlbum, albumID, false)
}

func (tx *Tx) DeleteAlbum(ctx context.Context, frameID, id uuid.UUID) (bool, error) {
	tag, err := tx.Exec(ctx, `DELETE FROM albums WHERE frame_id = $1 AND id = $2`, frameID, id)
	if err != nil || tag.RowsAffected() == 0 {
		return false, err
	}
	return true, tx.FeedPut(ctx, frameID, FeedAlbum, id, true)
}
