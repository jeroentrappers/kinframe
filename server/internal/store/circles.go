package store

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Circles: smaller groups inside a family (README §5.18). Their chat is created and kept in step by the
// database (00023_chat.sql triggers); deleting a circle is a soft delete after its posts were removed.

type Circle struct {
	ID        uuid.UUID  `db:"id"`
	FeedID    uuid.UUID  `db:"feed_id"`
	Name      string     `db:"name"`
	CreatedBy *uuid.UUID `db:"created_by"`
	CreatedAt time.Time  `db:"created_at"`
	DeletedAt *time.Time `db:"deleted_at"`
}

const circleCols = `c.id, c.feed_id, c.name, c.created_by, c.created_at, c.deleted_at`

// GetCircle returns a live circle of the feed.
func (s *Store) GetCircle(ctx context.Context, q db.DBTX, feedID, id uuid.UUID) (*Circle, error) {
	return one[Circle](q.Query(ctx, `SELECT `+circleCols+` FROM circles c WHERE c.id = $1 AND c.feed_id = $2 AND c.deleted_at IS NULL`, id, feedID))
}

// GetCircleByID returns a live circle of any feed.
func (s *Store) GetCircleByID(ctx context.Context, q db.DBTX, id uuid.UUID) (*Circle, error) {
	return one[Circle](q.Query(ctx, `SELECT `+circleCols+` FROM circles c WHERE c.id = $1 AND c.deleted_at IS NULL`, id))
}

// LockCircle returns a live circle of the feed with a row lock.
func (tx *Tx) LockCircle(ctx context.Context, feedID, id uuid.UUID) (*Circle, error) {
	return one[Circle](tx.Query(ctx, `SELECT `+circleCols+` FROM circles c WHERE c.id = $1 AND c.feed_id = $2 AND c.deleted_at IS NULL FOR UPDATE`, id, feedID))
}

// ListCircles lists a feed's live circles by name: all of them, or only those viewer is in.
func (s *Store) ListCircles(ctx context.Context, q db.DBTX, feedID, viewer uuid.UUID, all bool) ([]Circle, error) {
	return many[Circle](q.Query(ctx, `SELECT `+circleCols+` FROM circles c WHERE c.feed_id = $1 AND c.deleted_at IS NULL
		AND ($3 OR EXISTS (SELECT 1 FROM circle_members cm WHERE cm.circle_id = c.id AND cm.user_id = $2))
		ORDER BY lower(c.name), c.id`, feedID, viewer, all))
}

// CountCircles counts a feed's live circles.
func (s *Store) CountCircles(ctx context.Context, q db.DBTX, feedID uuid.UUID) (int, error) {
	var n int
	err := q.QueryRow(ctx, `SELECT count(*) FROM circles WHERE feed_id = $1 AND deleted_at IS NULL`, feedID).Scan(&n)
	return n, err
}

// CircleNameTaken reports whether another live circle of the feed has this name (ignoring case).
func (s *Store) CircleNameTaken(ctx context.Context, q db.DBTX, feedID uuid.UUID, name string, except *uuid.UUID) (bool, error) {
	var ok bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM circles WHERE feed_id = $1 AND deleted_at IS NULL AND lower(name) = lower($2)
		AND ($3::uuid IS NULL OR id <> $3))`, feedID, name, except).Scan(&ok)
	return ok, err
}

// CircleMember is a member of a circle with the person.
type CircleMember struct {
	CircleID    uuid.UUID `db:"circle_id"`
	UserID      uuid.UUID `db:"user_id"`
	Role        string    `db:"role"`
	AddedAt     time.Time `db:"added_at"`
	DisplayName string    `db:"display_name"`
	AvatarKey   *string   `db:"avatar_key"`
}

// CircleMembers lists the members of circles: admins first, then by name.
func (s *Store) CircleMembers(ctx context.Context, q db.DBTX, circleIDs []uuid.UUID) ([]CircleMember, error) {
	return many[CircleMember](q.Query(ctx, `SELECT cm.circle_id, cm.user_id, cm.role, cm.added_at, u.display_name, u.avatar_key
		FROM circle_members cm JOIN users u ON u.id = cm.user_id WHERE cm.circle_id = ANY($1)
		ORDER BY cm.circle_id, CASE cm.role WHEN 'admin' THEN 0 ELSE 1 END, lower(u.display_name), cm.user_id`, circleIDs))
}

// CircleMemberIDs lists the members of a circle.
func (s *Store) CircleMemberIDs(ctx context.Context, q db.DBTX, circleID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT user_id FROM circle_members WHERE circle_id = $1 ORDER BY user_id`, circleID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// CircleRole is userID's role in the circle ("" when not a member).
func (s *Store) CircleRole(ctx context.Context, q db.DBTX, circleID, userID uuid.UUID) (string, error) {
	var role string
	err := q.QueryRow(ctx, `SELECT role FROM circle_members WHERE circle_id = $1 AND user_id = $2`, circleID, userID).Scan(&role)
	if db.NotFound(err) {
		return "", nil
	}
	return role, err
}

// CircleFrame is a frame a circle's posts reach.
type CircleFrame struct {
	CircleID  uuid.UUID `db:"circle_id"`
	FrameID   uuid.UUID `db:"frame_id"`
	FrameName string    `db:"frame_name"`
}

// CircleFrameIDs lists the extra frames of circles (circle_frames).
func (s *Store) CircleFrameIDs(ctx context.Context, q db.DBTX, circleIDs []uuid.UUID) ([]CircleFrame, error) {
	return many[CircleFrame](q.Query(ctx, `SELECT cf.circle_id, cf.frame_id, f.name AS frame_name FROM circle_frames cf
		JOIN frames f ON f.id = cf.frame_id WHERE cf.circle_id = ANY($1) ORDER BY cf.circle_id, cf.added_at, cf.frame_id`, circleIDs))
}

// FramesReached lists, per circle, the feed's actively subscribed frames its posts reach now: a manager (owner,
// admin or change_settings) is in the circle, or the frame is one of its circle_frames.
func (s *Store) FramesReached(ctx context.Context, q db.DBTX, circleIDs []uuid.UUID) ([]CircleFrame, error) {
	return many[CircleFrame](q.Query(ctx, `SELECT c.id AS circle_id, f.id AS frame_id, f.name AS frame_name
		FROM circles c JOIN subscriptions s ON s.feed_id = c.feed_id AND s.status = 'active'
		JOIN frames f ON f.id = s.frame_id AND f.deleted_at IS NULL
		WHERE c.id = ANY($1) AND (EXISTS (SELECT 1 FROM circle_frames cf WHERE cf.circle_id = c.id AND cf.frame_id = f.id)
			OR EXISTS (SELECT 1 FROM circle_members cm JOIN memberships ms ON ms.user_id = cm.user_id AND ms.frame_id = f.id
				WHERE cm.circle_id = c.id AND (ms.role IN ('owner', 'admin') OR (ms.permissions->>'change_settings')::boolean)))
		ORDER BY c.id, lower(f.name), f.id`, circleIDs))
}

// ActiveSubscribedFrames lists which of frames have an active subscription to the feed.
func (s *Store) ActiveSubscribedFrames(ctx context.Context, q db.DBTX, feedID uuid.UUID, frames []uuid.UUID) (map[uuid.UUID]bool, error) {
	rows, err := q.Query(ctx, `SELECT frame_id FROM subscriptions WHERE feed_id = $1 AND status = 'active' AND frame_id = ANY($2)`, feedID, frames)
	if err != nil {
		return nil, err
	}
	return idSet(rows)
}

// FeedMembersAmong lists which of users are members of the feed.
func (s *Store) FeedMembersAmong(ctx context.Context, q db.DBTX, feedID uuid.UUID, users []uuid.UUID) (map[uuid.UUID]bool, error) {
	rows, err := q.Query(ctx, `SELECT user_id FROM feed_members WHERE feed_id = $1 AND user_id = ANY($2)`, feedID, users)
	if err != nil {
		return nil, err
	}
	return idSet(rows)
}

// CircleConversations maps circles to their chats (kind circle).
func (s *Store) CircleConversations(ctx context.Context, q db.DBTX, circleIDs []uuid.UUID) (map[uuid.UUID]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT circle_id, id FROM conversations WHERE kind = 'circle' AND circle_id = ANY($1)`, circleIDs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[uuid.UUID]uuid.UUID{}
	for rows.Next() {
		var c, id uuid.UUID
		if err := rows.Scan(&c, &id); err != nil {
			return nil, err
		}
		out[c] = id
	}
	return out, rows.Err()
}

// CreateCircle inserts a circle with creator as its admin and the others as members (feed members), and its
// extra frames. The database creates its chat.
func (tx *Tx) CreateCircle(ctx context.Context, c Circle, creator uuid.UUID, members, frames []uuid.UUID) error {
	if _, err := tx.Exec(ctx, `INSERT INTO circles (id, feed_id, name, created_by) VALUES ($1, $2, $3, $4)`, c.ID, c.FeedID, c.Name, creator); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO circle_members (circle_id, feed_id, user_id, role, added_by) VALUES ($1, $2, $3, 'admin', $3)`,
		c.ID, c.FeedID, creator); err != nil {
		return err
	}
	if _, err := tx.AddCircleMembers(ctx, c.ID, c.FeedID, members, creator); err != nil {
		return err
	}
	return tx.SetCircleFrames(ctx, c.ID, frames, creator)
}

// AddCircleMembers adds feed members as circle members (present ones are left alone). Returns who was added.
func (tx *Tx) AddCircleMembers(ctx context.Context, circleID, feedID uuid.UUID, users []uuid.UUID, by uuid.UUID) ([]uuid.UUID, error) {
	if len(users) == 0 {
		return nil, nil
	}
	rows, err := tx.Query(ctx, `INSERT INTO circle_members (circle_id, feed_id, user_id, added_by)
		SELECT $1, $2, u, $4 FROM unnest($3::uuid[]) AS u ON CONFLICT DO NOTHING RETURNING user_id`, circleID, feedID, users, by)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// SetCircleFrames replaces a circle's extra frames.
func (tx *Tx) SetCircleFrames(ctx context.Context, circleID uuid.UUID, frames []uuid.UUID, by uuid.UUID) error {
	if frames == nil {
		frames = []uuid.UUID{}
	}
	if _, err := tx.Exec(ctx, `DELETE FROM circle_frames WHERE circle_id = $1 AND NOT (frame_id = ANY($2))`, circleID, frames); err != nil {
		return err
	}
	if len(frames) == 0 {
		return nil
	}
	_, err := tx.Exec(ctx, `INSERT INTO circle_frames (circle_id, frame_id, added_by) SELECT $1, f, $3 FROM unnest($2::uuid[]) AS f
		ON CONFLICT DO NOTHING`, circleID, frames, by)
	return err
}

// RenameCircle renames a circle.
func (tx *Tx) RenameCircle(ctx context.Context, id uuid.UUID, name string) error {
	_, err := tx.Exec(ctx, `UPDATE circles SET name = $2, updated_at = now() WHERE id = $1`, id, name)
	return err
}

// TouchCircle bumps updated_at.
func (tx *Tx) TouchCircle(ctx context.Context, id uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE circles SET updated_at = now() WHERE id = $1`, id)
	return err
}

// SetCircleMemberRole changes a member's role. Reports whether they are a member.
func (tx *Tx) SetCircleMemberRole(ctx context.Context, circleID, userID uuid.UUID, role string) (bool, error) {
	tag, err := tx.Exec(ctx, `UPDATE circle_members SET role = $3 WHERE circle_id = $1 AND user_id = $2`, circleID, userID, role)
	return tag.RowsAffected() > 0, err
}

// RemoveCircleMember removes a member (the database removes them from the circle's chat). Reports whether
// they were a member.
func (tx *Tx) RemoveCircleMember(ctx context.Context, circleID, userID uuid.UUID) (bool, error) {
	tag, err := tx.Exec(ctx, `DELETE FROM circle_members WHERE circle_id = $1 AND user_id = $2`, circleID, userID)
	return tag.RowsAffected() > 0, err
}

// MarkCircleDeleted soft-deletes a circle (its chat is archived by the database).
func (tx *Tx) MarkCircleDeleted(ctx context.Context, id uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE circles SET deleted_at = now(), updated_at = now() WHERE id = $1 AND deleted_at IS NULL`, id)
	return err
}

// LiveCirclePosts lists the live posts shared with a circle.
func (s *Store) LiveCirclePosts(ctx context.Context, q db.DBTX, circleID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT id FROM feed_posts WHERE circle_id = $1 AND removed_at IS NULL ORDER BY id`, circleID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// SetPostCircle shares the send's new post in the feed with a circle (made in this transaction).
func (tx *Tx) SetPostCircle(ctx context.Context, sendID, feedID, circleID uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE feed_posts SET circle_id = $3 WHERE send_id = $1 AND feed_id = $2 AND removed_at IS NULL
		AND circle_id IS NULL AND created_at = now()`, sendID, feedID, circleID)
	return err
}
