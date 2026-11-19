package store

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// A's side of families on different servers: what the changes endpoint renders and the frame entries of remote
// frames in threads (README §5.22).

// HistoryLimit is how many items from before the boundary a history option wants (as for local subscriptions).
func HistoryLimit(sub *Subscription, maxBackfill int) int { return historyLimit(sub, maxBackfill) }

// FederationPostRow is a family post as remote frames get it.
type FederationPostRow struct {
	ID          uuid.UUID  `db:"id"`
	FeedID      uuid.UUID  `db:"feed_id"`
	PosterID    *uuid.UUID `db:"poster_user_id"`
	PosterName  *string    `db:"poster_name"`
	CreatedAt   time.Time  `db:"created_at"`
	Removed     bool       `db:"removed"`
	InCircle    bool       `db:"in_circle"`
	FeedDeleted bool       `db:"feed_deleted"`
}

// FederationPosts loads posts by id (any state; the caller decides what is shareable).
func (s *Store) FederationPosts(ctx context.Context, ids []uuid.UUID) ([]FederationPostRow, error) {
	return many[FederationPostRow](s.Pool.Query(ctx, `SELECT p.id, p.feed_id, p.poster_user_id, u.display_name AS poster_name, p.created_at,
		p.removed_at IS NOT NULL AS removed, p.circle_id IS NOT NULL AS in_circle, f.deleted_at IS NOT NULL AS feed_deleted
		FROM feed_posts p JOIN feeds f ON f.id = p.feed_id LEFT JOIN users u ON u.id = p.poster_user_id WHERE p.id = ANY($1)`, ids))
}

// FederationItemRow is a live item of a post with its master.
type FederationItemRow struct {
	PostID       uuid.UUID  `db:"post_id"`
	ItemID       uuid.UUID  `db:"item_id"`
	Position     int        `db:"position"`
	Caption      *string    `db:"caption"`
	FocalX       *float64   `db:"focal_x"`
	FocalY       *float64   `db:"focal_y"`
	Fit          *string    `db:"fit"`
	CapturedAt   *time.Time `db:"captured_at"`
	Kind         string     `db:"kind"`
	Status       string     `db:"status"`
	Width        *int       `db:"width"`
	Height       *int       `db:"height"`
	DurationMS   *int       `db:"duration_ms"`
	HasAudio     *bool      `db:"has_audio"`
	MasterKey    *string    `db:"master_key"`
	MasterSHA256 *string    `db:"master_sha256"`
	MasterBytes  *int64     `db:"master_bytes"`
	MasterMime   *string    `db:"master_mime"`
	MotionKey    *string    `db:"motion_key"`
	MotionStatus *string    `db:"motion_status"`
}

// FederationPostItems lists the live items of posts, in post order.
func (s *Store) FederationPostItems(ctx context.Context, postIDs []uuid.UUID) ([]FederationItemRow, error) {
	return many[FederationItemRow](s.Pool.Query(ctx, `SELECT pi.post_id, pi.item_id, pi.position, i.caption, i.focal_x, i.focal_y, i.fit,
		COALESCE(i.captured_override, m.captured_at, i.captured_hint) AS captured_at, m.kind, m.status, m.width, m.height, m.duration_ms,
		m.has_audio, m.master_key, m.master_sha256, m.master_bytes, m.master_mime, mm.master_key AS motion_key, mm.status AS motion_status
		FROM feed_post_items pi JOIN items i ON i.id = pi.item_id JOIN media m ON m.id = i.media_id LEFT JOIN media mm ON mm.id = i.motion_media_id
		WHERE pi.post_id = ANY($1) AND pi.removed_at IS NULL ORDER BY pi.post_id, pi.position`, postIDs))
}

// FederationBackfillPosts lists the shareable posts (whole-family, live, with a live item) created at or after
// from, after the (afterAt, afterID) position, oldest first.
func (s *Store) FederationBackfillPosts(ctx context.Context, feedID uuid.UUID, from, afterAt time.Time, afterID uuid.UUID, limit int) ([]uuid.UUID, error) {
	rows, err := s.Pool.Query(ctx, `SELECT p.id FROM feed_posts p WHERE p.feed_id = $1 AND p.removed_at IS NULL AND p.circle_id IS NULL
		AND p.created_at >= $2 AND (p.created_at, p.id) > ($3, $4)
		AND EXISTS (SELECT 1 FROM feed_post_items pi WHERE pi.post_id = p.id AND pi.removed_at IS NULL)
		ORDER BY p.created_at, p.id LIMIT $5`, feedID, from, afterAt, afterID, limit)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// FederationHistoryStart is the post time from which a subscription's history starts: the post of the n-th newest
// shareable item before boundary (the epoch when there are fewer, boundary itself for n = 0).
func (s *Store) FederationHistoryStart(ctx context.Context, feedID uuid.UUID, boundary time.Time, n int) (time.Time, error) {
	if n <= 0 {
		return boundary, nil
	}
	var t time.Time
	err := s.Pool.QueryRow(ctx, `SELECT p.created_at FROM feed_post_items pi JOIN feed_posts p ON p.id = pi.post_id
		WHERE p.feed_id = $1 AND p.removed_at IS NULL AND pi.removed_at IS NULL AND p.circle_id IS NULL AND p.created_at < $2
		ORDER BY p.created_at DESC, p.id DESC, pi.position DESC OFFSET $3 LIMIT 1`, feedID, boundary, n-1).Scan(&t)
	if db.NotFound(err) {
		return time.Unix(0, 0).UTC(), nil
	}
	return t, err
}

// FederationBirthdayRow is an approved birthday person of a family.
type FederationBirthdayRow struct {
	ID    uuid.UUID `db:"id"`
	Name  string    `db:"display_name"`
	Month int       `db:"month"`
	Day   int       `db:"day"`
	Year  *int      `db:"year"`
}

// FederationBirthdays lists a family's approved birthday people (the year only with show_age).
func (s *Store) FederationBirthdays(ctx context.Context, feedID uuid.UUID) ([]FederationBirthdayRow, error) {
	return many[FederationBirthdayRow](s.Pool.Query(ctx, `SELECT id, display_name, month::int AS month, day::int AS day,
		CASE WHEN show_age THEN birth_year::int END AS year FROM feed_birthdays WHERE feed_id = $1 AND status = 'approved'
		ORDER BY month, day, display_name`, feedID))
}

// NewestComments lists the n newest live top-level person comments of a post's thread.
func (s *Store) NewestComments(ctx context.Context, postID uuid.UUID, n int) ([]Comment, error) {
	return many[Comment](s.Pool.Query(ctx, `SELECT `+commentCols+` FROM comments c`+commentJoins+`
		WHERE c.post_id = $1 AND c.kind = 'person' AND c.parent_id IS NULL AND c.deleted_at IS NULL AND c.removed_at IS NULL
		ORDER BY c.id DESC LIMIT $2`, postID, n))
}

// FederationPostOK reports whether item is a live item of a live whole-family post of feed.
func (s *Store) FederationPostOK(ctx context.Context, q db.DBTX, feedID, postID, itemID uuid.UUID) (bool, error) {
	var ok bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM feed_posts p JOIN feed_post_items pi ON pi.post_id = p.id AND pi.item_id = $3
		AND pi.removed_at IS NULL WHERE p.id = $2 AND p.feed_id = $1 AND p.removed_at IS NULL AND p.circle_id IS NULL)`, feedID, postID, itemID).Scan(&ok)
	return ok, err
}

// RemoteEntry is a remote frame's line in a thread.
type RemoteEntry struct {
	PostID       uuid.UUID
	SubscriberID uuid.UUID
	FrameName    string
	ServerName   string
	ItemID       uuid.UUID
}

// SetRemoteReactionEntry makes the thread's reaction entry of a remote frame show reaction (nil deletes it).
func (tx *Tx) SetRemoteReactionEntry(ctx context.Context, e RemoteEntry, reaction *string) (id uuid.UUID, change string, err error) {
	var cur *string
	err = tx.QueryRow(ctx, `SELECT id, frame_reaction FROM comments WHERE post_id = $1 AND remote_subscriber_id = $2 AND kind = 'frame'
		AND frame_reaction IS NOT NULL FOR UPDATE`, e.PostID, e.SubscriberID).Scan(&id, &cur)
	switch {
	case db.NotFound(err):
		if reaction == nil {
			return uuid.Nil, "", nil
		}
		id = NewID()
		_, err = tx.Exec(ctx, `INSERT INTO comments (id, post_id, kind, frame_name, frame_server_name, remote_subscriber_id, item_id, frame_reaction)
			VALUES ($1, $2, 'frame', $3, $4, $5, $6, $7)`, id, e.PostID, e.FrameName, e.ServerName, e.SubscriberID, e.ItemID, *reaction)
		return id, "created", err
	case err != nil:
		return uuid.Nil, "", err
	case reaction == nil:
		_, err = tx.Exec(ctx, `DELETE FROM comments WHERE id = $1`, id)
		return id, "deleted", err
	case cur != nil && *cur == *reaction:
		return id, "", nil
	}
	_, err = tx.Exec(ctx, `UPDATE comments SET frame_reaction = $2, item_id = $3, frame_name = $4, frame_server_name = $5 WHERE id = $1`,
		id, *reaction, e.ItemID, e.FrameName, e.ServerName)
	return id, "updated", err
}

// AddRemoteReplyEntry adds a remote frame's quick reply to a thread.
func (tx *Tx) AddRemoteReplyEntry(ctx context.Context, e RemoteEntry, preset string) (uuid.UUID, error) {
	id := NewID()
	_, err := tx.Exec(ctx, `INSERT INTO comments (id, post_id, kind, frame_name, frame_server_name, remote_subscriber_id, item_id, frame_reply)
		VALUES ($1, $2, 'frame', $3, $4, $5, $6, $7)`, id, e.PostID, e.FrameName, e.ServerName, e.SubscriberID, e.ItemID, preset)
	return id, err
}

// LatestRemoteReaction is a remote frame's newest reaction on a post's items.
func (s *Store) LatestRemoteReaction(ctx context.Context, q db.DBTX, subscriberID, postID uuid.UUID) (*string, uuid.UUID, error) {
	var r string
	var item uuid.UUID
	err := q.QueryRow(ctx, `SELECT reaction, item_id FROM remote_frame_reactions WHERE subscriber_id = $1 AND post_id = $2
		ORDER BY at DESC LIMIT 1`, subscriberID, postID).Scan(&r, &item)
	if db.NotFound(err) {
		return nil, uuid.Nil, nil
	}
	if err != nil {
		return nil, uuid.Nil, err
	}
	return &r, item, nil
}

// RemoteReceiptRow is one live item of a post on a remote frame.
type RemoteReceiptRow struct {
	PostID       uuid.UUID  `db:"post_id"`
	ItemID       uuid.UUID  `db:"item_id"`
	SubscriberID uuid.UUID  `db:"subscriber_id"`
	FrameName    string     `db:"frame_name"`
	ServerName   string     `db:"server_name"`
	Pulled       bool       `db:"pulled"`
	SeenAt       *time.Time `db:"seen_at"`
	Reaction     *string    `db:"reaction"`
	ReactionAt   *time.Time `db:"reaction_at"`
}

// RemoteReceipts lists the items of posts on the active remote frames of their families: pulled when the frame's
// server pulled after the post was made, seen and reactions as the frame reported.
func (s *Store) RemoteReceipts(ctx context.Context, postIDs []uuid.UUID) ([]RemoteReceiptRow, error) {
	return many[RemoteReceiptRow](s.Pool.Query(ctx, `SELECT p.id AS post_id, pi.item_id, x.id AS subscriber_id, x.frame_name,
		COALESCE(NULLIF(rs.server_name, ''), rs.host) AS server_name,
		COALESCE(x.last_pull_at > p.created_at, false) AS pulled, sn.seen_at, r.reaction, r.at AS reaction_at
		FROM feed_posts p JOIN feed_post_items pi ON pi.post_id = p.id AND pi.removed_at IS NULL
		JOIN remote_subscribers x ON x.feed_id = p.feed_id AND x.status = 'active'
		JOIN remote_servers rs ON rs.id = x.server_id
		LEFT JOIN remote_frame_seen sn ON sn.subscriber_id = x.id AND sn.post_id = p.id AND sn.item_id = pi.item_id
		LEFT JOIN remote_frame_reactions r ON r.subscriber_id = x.id AND r.post_id = p.id AND r.item_id = pi.item_id
		WHERE p.id = ANY($1) AND p.removed_at IS NULL AND p.circle_id IS NULL
		ORDER BY p.id, x.id, pi.position`, postIDs))
}
