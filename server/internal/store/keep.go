package store

// Ask to keep (docs/spec/leaving.md §4.2, README §5.21): a frame's or family's managers ask the person who took
// photos away to let them stay. One request per scope and taking; the answer is final.

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// KeepRequest is a keep_requests row with the names it shows.
type KeepRequest struct {
	ID               uuid.UUID  `db:"id"`
	TakingID         uuid.UUID  `db:"taking_id"`
	FrameID          *uuid.UUID `db:"frame_id"`
	FrameName        *string    `db:"frame_name"`
	FeedID           *uuid.UUID `db:"feed_id"`
	FeedName         *string    `db:"feed_name"`
	AskedBy          *uuid.UUID `db:"asked_by"`
	AskedByName      string     `db:"asked_by_name"`
	AskedByAvatarKey *string    `db:"asked_by_avatar_key"`
	AskedByDeleted   bool       `db:"asked_by_deleted"`
	Note             *string    `db:"note"`
	AllItems         bool       `db:"all_items"`
	Status           string     `db:"status"`
	CreatedAt        time.Time  `db:"created_at"`
	ExpiresAt        time.Time  `db:"expires_at"`
	AnsweredAt       *time.Time `db:"answered_at"`
	ItemCount        int        `db:"item_count"`
	AllowedCount     int        `db:"allowed_count"`
	// The person asked: who took the photos away.
	PersonID        *uuid.UUID `db:"person_id"`
	PersonName      string     `db:"person_name"`
	PersonAvatarKey *string    `db:"person_avatar_key"`
	PersonDeleted   bool       `db:"person_deleted"`
}

const keepSelect = `SELECT k.id, k.taking_id, k.frame_id, fr.name AS frame_name, k.feed_id, fd.name AS feed_name,
	k.asked_by, COALESCE(au.display_name, k.asked_by_name) AS asked_by_name, au.avatar_key AS asked_by_avatar_key,
	COALESCE(au.deleted_at IS NOT NULL, k.asked_by IS NULL) AS asked_by_deleted,
	k.note, k.all_items, k.status, k.created_at, k.expires_at, k.answered_at,
	(SELECT count(*) FROM keep_request_items ki WHERE ki.request_id = k.id)::int AS item_count,
	(SELECT count(*) FROM keep_request_items ki WHERE ki.request_id = k.id AND ki.allowed)::int AS allowed_count,
	t.user_id AS person_id, t.user_name AS person_name, pu.avatar_key AS person_avatar_key,
	COALESCE(pu.deleted_at IS NOT NULL, true) AS person_deleted
	FROM keep_requests k JOIN takings t ON t.id = k.taking_id
	LEFT JOIN frames fr ON fr.id = k.frame_id LEFT JOIN feeds fd ON fd.id = k.feed_id
	LEFT JOIN users au ON au.id = k.asked_by LEFT JOIN users pu ON pu.id = t.user_id`

// ErrKeepAsked: the scope already asked for this taking (keep_requests_frame_once / keep_requests_feed_once).
var ErrKeepAsked = errors.New("keep request exists")

// NewKeepRequest is what CreateKeepRequest stores.
type NewKeepRequest struct {
	ID          uuid.UUID
	TakingID    uuid.UUID
	FrameID     *uuid.UUID
	FeedID      *uuid.UUID
	AskedBy     uuid.UUID
	AskedByName string
	Note        *string
	AllItems    bool
	ItemIDs     []uuid.UUID
	CreatedAt   time.Time
	ExpiresAt   time.Time
}

// CreateKeepRequest inserts a request with its items; ErrKeepAsked when the scope already asked.
func (tx *Tx) CreateKeepRequest(ctx context.Context, k NewKeepRequest) error {
	_, err := tx.Exec(ctx, `INSERT INTO keep_requests (id, taking_id, frame_id, feed_id, asked_by, asked_by_name, note, all_items,
		created_at, expires_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
		k.ID, k.TakingID, k.FrameID, k.FeedID, k.AskedBy, k.AskedByName, k.Note, k.AllItems, k.CreatedAt, k.ExpiresAt)
	if db.IsUniqueViolation(err, "keep_requests_frame_once") || db.IsUniqueViolation(err, "keep_requests_feed_once") {
		return ErrKeepAsked
	}
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `INSERT INTO keep_request_items (request_id, item_id) SELECT $1, unnest($2::uuid[]) ON CONFLICT DO NOTHING`, k.ID, k.ItemIDs)
	return err
}

// GetKeepRequest returns a request.
func (s *Store) GetKeepRequest(ctx context.Context, q db.DBTX, id uuid.UUID) (*KeepRequest, error) {
	return one[KeepRequest](q.Query(ctx, keepSelect+` WHERE k.id = $1`, id))
}

// LockKeepRequest returns a request with a row lock.
func (tx *Tx) LockKeepRequest(ctx context.Context, id uuid.UUID) (*KeepRequest, error) {
	if _, err := tx.Exec(ctx, `SELECT 1 FROM keep_requests WHERE id = $1 FOR UPDATE`, id); err != nil {
		return nil, err
	}
	return one[KeepRequest](tx.Query(ctx, keepSelect+` WHERE k.id = $1`, id))
}

// KeepRequestOf returns the request of a scope for a taking, if any.
func (s *Store) KeepRequestOf(ctx context.Context, q db.DBTX, takingID uuid.UUID, frameID, feedID *uuid.UUID) (*KeepRequest, error) {
	return one[KeepRequest](q.Query(ctx, keepSelect+` WHERE k.taking_id = $1 AND (k.frame_id = $2 OR k.feed_id = $3)`, takingID, frameID, feedID))
}

// KeepRequestFilter selects requests: sent to a person, or made for a frame or a family.
type KeepRequestFilter struct {
	PersonID *uuid.UUID
	FrameID  *uuid.UUID
	FeedID   *uuid.UUID
	OnlyOpen bool
	Since    time.Time // created at or after (scope lists: 60 days)
}

// ListKeepRequests lists requests newest first.
func (s *Store) ListKeepRequests(ctx context.Context, f KeepRequestFilter) ([]KeepRequest, error) {
	return many[KeepRequest](s.Pool.Query(ctx, keepSelect+` WHERE ($1::uuid IS NULL OR t.user_id = $1)
		AND ($2::uuid IS NULL OR k.frame_id = $2) AND ($3::uuid IS NULL OR k.feed_id = $3)
		AND (NOT $4 OR k.status = 'open') AND k.created_at >= $5
		ORDER BY k.created_at DESC, k.id DESC LIMIT 200`, f.PersonID, f.FrameID, f.FeedID, f.OnlyOpen, f.Since))
}

// KeepRequestItem is an item asked for, with the answer.
type KeepRequestItem struct {
	ItemRow
	Allowed *bool `db:"allowed"`
}

// KeepRequestItems lists the items of a request, with their answer.
func (s *Store) KeepRequestItems(ctx context.Context, q db.DBTX, id uuid.UUID) ([]KeepRequestItem, error) {
	return many[KeepRequestItem](q.Query(ctx, `SELECT `+itemRowCols+`, ki.allowed FROM keep_request_items ki
		JOIN items i ON i.id = ki.item_id`+itemJoins+` WHERE ki.request_id = $1 ORDER BY i.created_at, i.id`, id))
}

// KeepRequestItemIDs lists the item ids of a request.
func (s *Store) KeepRequestItemIDs(ctx context.Context, q db.DBTX, id uuid.UUID) ([]uuid.UUID, error) {
	return uuids(q.Query(ctx, `SELECT item_id FROM keep_request_items WHERE request_id = $1 ORDER BY item_id`, id))
}

// AnswerKeepRequest records the answer: allowed items true, the rest false, and the status.
func (tx *Tx) AnswerKeepRequest(ctx context.Context, id uuid.UUID, allowed []uuid.UUID, status string, now time.Time) error {
	if _, err := tx.Exec(ctx, `UPDATE keep_request_items SET allowed = (item_id = ANY($2)) WHERE request_id = $1`, id, allowed); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `UPDATE keep_requests SET status = $2, answered_at = $3 WHERE id = $1`, id, status, now)
	return err
}

// ExpireKeepRequests marks open requests whose 30 days are over as expired and returns their ids.
func (s *Store) ExpireKeepRequests(ctx context.Context, now time.Time) ([]uuid.UUID, error) {
	return uuids(s.Pool.Query(ctx, `UPDATE keep_requests SET status = 'expired' WHERE status = 'open' AND expires_at <= $1 RETURNING id`, now))
}
