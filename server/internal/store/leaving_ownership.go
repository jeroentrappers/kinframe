package store

// Ownership by the operator and the account-deletion handover (docs/spec/leaving.md §4.4, §4.6, README §5.21):
// the ownership log, the operator's search, and handing a deleted account's photos to the families' and frames'
// owners.

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// OwnershipLogEntry is an ownership_log row.
type OwnershipLogEntry struct {
	ID              uuid.UUID       `db:"id"`
	Kind            string          `db:"kind"`
	ScopeKind       *string         `db:"scope_kind"`
	FrameID         *uuid.UUID      `db:"frame_id"`
	FeedID          *uuid.UUID      `db:"feed_id"`
	ScopeName       *string         `db:"scope_name"`
	FromUserID      *uuid.UUID      `db:"from_user_id"`
	FromName        *string         `db:"from_name"`
	ToUserID        *uuid.UUID      `db:"to_user_id"`
	ToName          *string         `db:"to_name"`
	OperatorUserID  *uuid.UUID      `db:"operator_user_id"`
	OperatorName    *string         `db:"operator_name"`
	Reason          *string         `db:"reason"`
	RemovedPrevious *bool           `db:"removed_previous"`
	ItemCount       *int            `db:"item_count"`
	Details         json.RawMessage `db:"details"`
	CreatedAt       time.Time       `db:"created_at"`
}

const ownershipCols = `id, kind, scope_kind, frame_id, feed_id, scope_name, from_user_id, from_name, to_user_id, to_name,
	operator_user_id, operator_name, reason, removed_previous, item_count, details, created_at`

// LogOwnership adds an entry to the ownership log.
func (tx *Tx) LogOwnership(ctx context.Context, e OwnershipLogEntry) error {
	if e.ID == uuid.Nil {
		e.ID = NewID()
	}
	if len(e.Details) == 0 {
		e.Details = json.RawMessage(`{}`)
	}
	_, err := tx.Exec(ctx, `INSERT INTO ownership_log (`+ownershipCols+`)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, COALESCE($17, now()))`,
		e.ID, e.Kind, e.ScopeKind, e.FrameID, e.FeedID, e.ScopeName, e.FromUserID, e.FromName, e.ToUserID, e.ToName,
		e.OperatorUserID, e.OperatorName, e.Reason, e.RemovedPrevious, e.ItemCount, e.Details, nullTime(e.CreatedAt))
	return err
}

func nullTime(t time.Time) *time.Time {
	if t.IsZero() {
		return nil
	}
	return &t
}

// ListOwnershipLog pages the log newest first, after the cursor (created_at, id) when given.
func (s *Store) ListOwnershipLog(ctx context.Context, afterAt *time.Time, afterID *uuid.UUID, limit int) ([]OwnershipLogEntry, error) {
	return many[OwnershipLogEntry](s.Pool.Query(ctx, `SELECT `+ownershipCols+` FROM ownership_log
		WHERE $1::timestamptz IS NULL OR (created_at, id) < ($1, $2)
		ORDER BY created_at DESC, id DESC LIMIT $3`, afterAt, afterID, limit))
}

// PruneOwnershipLog deletes entries older than the retention (2 years).
func (s *Store) PruneOwnershipLog(ctx context.Context, before time.Time) (int64, error) {
	tag, err := s.Pool.Exec(ctx, `DELETE FROM ownership_log WHERE created_at < $1`, before)
	return tag.RowsAffected(), err
}

// OperatorScope is a frame or family found by the operator's search.
type OperatorScope struct {
	ID        uuid.UUID `db:"id"`
	Name      string    `db:"name"`
	CreatedAt time.Time `db:"created_at"`
}

// OperatorScopeMember is a member of a found frame or family.
type OperatorScopeMember struct {
	ScopeID     uuid.UUID `db:"scope_id"`
	UserID      uuid.UUID `db:"user_id"`
	DisplayName string    `db:"display_name"`
	Email       *string   `db:"email"`
	Role        string    `db:"role"`
}

// SearchOwnership finds live frames and families by id, name, or a member's email or display name.
func (s *Store) SearchOwnership(ctx context.Context, q string, limit int) (frames, feeds []OperatorScope, err error) {
	var id *uuid.UUID
	if u, perr := uuid.Parse(q); perr == nil {
		id = &u
	}
	like := "%" + escapeLike(q) + "%"
	frames, err = many[OperatorScope](s.Pool.Query(ctx, `SELECT f.id, f.name, f.created_at FROM frames f WHERE f.deleted_at IS NULL AND (
			f.id = $1 OR f.name ILIKE $2 OR EXISTS (SELECT 1 FROM memberships m JOIN users u ON u.id = m.user_id
				WHERE m.frame_id = f.id AND (u.email ILIKE $2 OR u.display_name ILIKE $2)))
		ORDER BY lower(f.name), f.id LIMIT $3`, id, like, limit))
	if err != nil {
		return nil, nil, err
	}
	feeds, err = many[OperatorScope](s.Pool.Query(ctx, `SELECT f.id, f.name, f.created_at FROM feeds f WHERE f.deleted_at IS NULL AND (
			f.id = $1 OR f.name ILIKE $2 OR EXISTS (SELECT 1 FROM feed_members m JOIN users u ON u.id = m.user_id
				WHERE m.feed_id = f.id AND (u.email ILIKE $2 OR u.display_name ILIKE $2)))
		ORDER BY lower(f.name), f.id LIMIT $3`, id, like, limit))
	return frames, feeds, err
}

func escapeLike(s string) string {
	out := make([]rune, 0, len(s))
	for _, r := range s {
		if r == '%' || r == '_' || r == '\\' {
			out = append(out, '\\')
		}
		out = append(out, r)
	}
	return string(out)
}

// OperatorMembers lists the members of frames (feed=false) or families (feed=true), owner first.
func (s *Store) OperatorMembers(ctx context.Context, ids []uuid.UUID, feed bool) ([]OperatorScopeMember, error) {
	if feed {
		return many[OperatorScopeMember](s.Pool.Query(ctx, `SELECT m.feed_id AS scope_id, m.user_id, u.display_name, u.email, m.role
			FROM feed_members m JOIN users u ON u.id = m.user_id WHERE m.feed_id = ANY($1)
			ORDER BY m.feed_id, CASE m.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END, m.joined_at`, ids))
	}
	return many[OperatorScopeMember](s.Pool.Query(ctx, `SELECT m.frame_id AS scope_id, m.user_id, u.display_name, u.email, m.role
		FROM memberships m JOIN users u ON u.id = m.user_id WHERE m.frame_id = ANY($1)
		ORDER BY m.frame_id, CASE m.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END, m.joined_at`, ids))
}

// HandoverScope is one family or frame that received a deleted account's photos.
type HandoverScope struct {
	Kind    string    `db:"kind"` // feed or frame
	ScopeID uuid.UUID `db:"scope_id"`
	Name    string    `db:"name"`
	OwnerID uuid.UUID `db:"owner_id"`
	Owner   string    `db:"owner_name"`
	Items   int       `db:"items"`
}

// HandOver gives a deleting account's photos to the families and frames they are still in (README §5.21 "Account
// deletion", `leave`): every item with a live post goes to the owner of the family it was first posted in (by post
// time); items only on frames go to the owner of the frame they first reached. The credit is frozen as
// former_sender_name. Items in neither stay with the account (its history). Returns the scopes per receiver.
func (tx *Tx) HandOver(ctx context.Context, userID uuid.UUID, name string, now time.Time) ([]HandoverScope, error) {
	return many[HandoverScope](tx.Query(ctx, `
		WITH mine AS (SELECT i.id FROM items i WHERE i.sender_user_id = $1),
		by_feed AS (
			SELECT DISTINCT ON (pi.item_id) pi.item_id, p.feed_id, o.user_id AS owner_id
			FROM feed_post_items pi JOIN feed_posts p ON p.id = pi.post_id AND p.removed_at IS NULL
			JOIN feeds fd ON fd.id = p.feed_id AND fd.deleted_at IS NULL
			JOIN feed_members o ON o.feed_id = p.feed_id AND o.role = 'owner'
			WHERE pi.item_id IN (SELECT id FROM mine) AND pi.removed_at IS NULL AND o.user_id <> $1
			ORDER BY pi.item_id, p.created_at, p.id
		),
		by_frame AS (
			SELECT DISTINCT ON (pl.item_id) pl.item_id, pl.frame_id, o.user_id AS owner_id
			FROM placements pl JOIN frames f ON f.id = pl.frame_id AND f.deleted_at IS NULL
			JOIN memberships o ON o.frame_id = pl.frame_id AND o.role = 'owner'
			WHERE pl.item_id IN (SELECT id FROM mine) AND pl.removed_at IS NULL AND o.user_id <> $1
			  AND pl.item_id NOT IN (SELECT item_id FROM by_feed)
			ORDER BY pl.item_id, pl.received_at, pl.id
		),
		target AS (
			SELECT item_id, 'feed' AS kind, feed_id AS scope_id, owner_id FROM by_feed
			UNION ALL SELECT item_id, 'frame', frame_id, owner_id FROM by_frame
		),
		moved AS (
			UPDATE items i SET sender_user_id = t.owner_id, former_sender_name = $2, handed_over_at = $3
			FROM target t WHERE i.id = t.item_id RETURNING t.kind, t.scope_id, t.owner_id
		)
		SELECT m.kind, m.scope_id, COALESCE(fd.name, fr.name, '') AS name, m.owner_id, u.display_name AS owner_name, count(*)::int AS items
		FROM moved m JOIN users u ON u.id = m.owner_id
		LEFT JOIN feeds fd ON m.kind = 'feed' AND fd.id = m.scope_id LEFT JOIN frames fr ON m.kind = 'frame' AND fr.id = m.scope_id
		GROUP BY m.kind, m.scope_id, fd.name, fr.name, m.owner_id, u.display_name
		ORDER BY m.kind, name, m.scope_id`, userID, name, now))
}
