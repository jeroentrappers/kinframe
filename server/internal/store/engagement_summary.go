package store

// On this day, the weekly "seen and loved" summary and the monthly family recap (README §5.20).

import (
	"context"
	"time"

	"github.com/google/uuid"
)

// MemoryPost is a feed post from an earlier year (On this day).
type MemoryPost struct {
	PostID    uuid.UUID `db:"post_id"`
	FeedID    uuid.UUID `db:"feed_id"`
	FeedName  string    `db:"feed_name"`
	CreatedAt time.Time `db:"created_at"`
	Year      int       `db:"year"`
}

// MemoryPosts returns the user's visible feed posts (member of the feed; circle posts only for the circle)
// made on month/one of days in zone in years before beforeYear, newest first.
func (s *Store) MemoryPosts(ctx context.Context, userID uuid.UUID, zone string, month int, days []int, beforeYear int) ([]MemoryPost, error) {
	return many[MemoryPost](s.Pool.Query(ctx, `
		SELECT p.id AS post_id, p.feed_id, f.name AS feed_name, p.created_at,
		       extract(year FROM p.created_at AT TIME ZONE $2)::int AS year
		FROM feed_posts p JOIN feeds f ON f.id = p.feed_id AND f.deleted_at IS NULL
		JOIN feed_members m ON m.feed_id = p.feed_id AND m.user_id = $1
		WHERE p.removed_at IS NULL
		  AND (p.circle_id IS NULL OR EXISTS (SELECT 1 FROM circle_members cm WHERE cm.circle_id = p.circle_id AND cm.user_id = $1))
		  AND extract(month FROM p.created_at AT TIME ZONE $2)::int = $3
		  AND extract(day FROM p.created_at AT TIME ZONE $2)::int = ANY($4)
		  AND extract(year FROM p.created_at AT TIME ZONE $2)::int < $5
		ORDER BY p.created_at DESC LIMIT 500`, userID, zone, month, days, beforeYear))
}

// SeenFrame is how often a user's photos were first seen on one frame in a window, and loved there.
type SeenFrame struct {
	FrameID   uuid.UUID `db:"frame_id"`
	FrameName string    `db:"frame_name"`
	Seen      int       `db:"seen"`
	Loves     int       `db:"loves"`
}

// SeenOnFrames counts placements of the user's personal sends first seen in [from, to), and frame loves
// given in that window, per frame.
func (s *Store) SeenOnFrames(ctx context.Context, userID uuid.UUID, from, to time.Time) ([]SeenFrame, error) {
	return many[SeenFrame](s.Pool.Query(ctx, `
		SELECT p.frame_id, f.name AS frame_name,
		       count(*) FILTER (WHERE p.seen_at >= $2 AND p.seen_at < $3)::int AS seen,
		       count(*) FILTER (WHERE p.reaction = 'love' AND p.reaction_at >= $2 AND p.reaction_at < $3)::int AS loves
		FROM placements p JOIN sends s ON s.id = p.send_id JOIN frames f ON f.id = p.frame_id AND f.deleted_at IS NULL
		WHERE s.sender_user_id = $1 AND `+PersonalSendSQL+`
		  AND ((p.seen_at >= $2 AND p.seen_at < $3) OR (p.reaction_at >= $2 AND p.reaction_at < $3))
		GROUP BY p.frame_id, f.name ORDER BY seen DESC, f.name`, userID, from, to))
}

// ownPostsSQL are the user's posts ($1): feed posts they made and frame posts of their sends.
const ownPostsSQL = `(SELECT id FROM feed_posts WHERE poster_user_id = $1 UNION SELECT id FROM frame_posts WHERE sender_user_id = $1)`

// PostReactionsAndComments counts others' reactions on the user's posts and their items, and others'
// person comments on the user's posts, made in [from, to).
func (s *Store) PostReactionsAndComments(ctx context.Context, userID uuid.UUID, from, to time.Time) (int, int, error) {
	var reactions, comments int
	err := s.Pool.QueryRow(ctx, `SELECT
		(SELECT count(*)::int FROM reactions r WHERE r.target_kind IN ('post', 'post_item') AND r.user_id <> $1
		   AND r.scope_id IN `+ownPostsSQL+` AND r.created_at >= $2 AND r.created_at < $3),
		(SELECT count(*)::int FROM comments c WHERE c.kind = 'person' AND c.author_user_id <> $1 AND c.deleted_at IS NULL
		   AND c.removed_at IS NULL AND c.post_id IN `+ownPostsSQL+` AND c.created_at >= $2 AND c.created_at < $3)`,
		userID, from, to).Scan(&reactions, &comments)
	return reactions, comments, err
}

// TopItem returns the user's item with the most reactions (frames' and people's) in [from, to).
func (s *Store) TopItem(ctx context.Context, userID uuid.UUID, from, to time.Time) (*uuid.UUID, error) {
	rows, err := many[idRow](s.Pool.Query(ctx, `
		SELECT x.item_id AS id FROM (
			SELECT p.item_id FROM placements p JOIN sends s ON s.id = p.send_id
			WHERE s.sender_user_id = $1 AND p.reaction IS NOT NULL AND p.reaction_at >= $2 AND p.reaction_at < $3
			UNION ALL
			SELECT r.target_id FROM reactions r JOIN items i ON i.id = r.target_id AND i.sender_user_id = $1
			WHERE r.target_kind = 'post_item' AND r.user_id <> $1 AND r.created_at >= $2 AND r.created_at < $3
		) x GROUP BY x.item_id ORDER BY count(*) DESC, x.item_id LIMIT 1`, userID, from, to))
	if err != nil || len(rows) == 0 {
		return nil, err
	}
	return &rows[0].ID, nil
}

// SeenSenders returns users whose personal sends' placements were first seen at or after since.
func (s *Store) SeenSenders(ctx context.Context, since time.Time) ([]uuid.UUID, error) {
	rows, err := many[idRow](s.Pool.Query(ctx, `SELECT DISTINCT s.sender_user_id AS id FROM placements p JOIN sends s ON s.id = p.send_id
		JOIN users u ON u.id = s.sender_user_id AND u.deleted_at IS NULL
		WHERE p.seen_at >= $1 AND `+PersonalSendSQL+` ORDER BY 1`, since))
	out := make([]uuid.UUID, len(rows))
	for i, r := range rows {
		out[i] = r.ID
	}
	return out, err
}

// RecapCounts is a family's month: photos and videos of personal posts and how many people shared them.
type RecapCounts struct {
	Photos  int `db:"photos"`
	Videos  int `db:"videos"`
	Sharers int `db:"sharers"`
}

// MonthCounts counts a family's personal posts' live items in [from, to).
func (s *Store) MonthCounts(ctx context.Context, feedID uuid.UUID, from, to time.Time) (*RecapCounts, error) {
	return one[RecapCounts](s.Pool.Query(ctx, `SELECT
		count(pi.item_id) FILTER (WHERE m.kind <> 'video')::int AS photos,
		count(pi.item_id) FILTER (WHERE m.kind = 'video')::int AS videos,
		count(DISTINCT p.poster_user_id)::int AS sharers
		FROM feed_posts p JOIN sends s ON s.id = p.send_id
		JOIN feed_post_items pi ON pi.post_id = p.id AND pi.removed_at IS NULL
		JOIN items i ON i.id = pi.item_id JOIN media m ON m.id = i.media_id
		WHERE p.feed_id = $1 AND p.removed_at IS NULL AND p.created_at >= $2 AND p.created_at < $3 AND `+PersonalSendSQL,
		feedID, from, to))
}

// MostLoved is a post with its reaction count and first item.
type MostLoved struct {
	PostID    uuid.UUID `db:"post_id"`
	ItemID    uuid.UUID `db:"item_id"`
	Reactions int       `db:"reactions"`
}

// MostLovedPost returns the family's post of [from, to) with the most reactions by people (post and items)
// and frames (on its placements).
func (s *Store) MostLovedPost(ctx context.Context, feedID uuid.UUID, from, to time.Time) (*MostLoved, error) {
	rows, err := many[MostLoved](s.Pool.Query(ctx, `
		SELECT p.id AS post_id,
		       (SELECT pi.item_id FROM feed_post_items pi WHERE pi.post_id = p.id AND pi.removed_at IS NULL ORDER BY pi.position LIMIT 1) AS item_id,
		       ((SELECT count(*) FROM reactions r WHERE r.scope_id = p.id AND r.target_kind IN ('post', 'post_item'))
		        + (SELECT count(*) FROM feed_deliveries d JOIN placements pl ON pl.id = d.placement_id
		           WHERE d.post_id = p.id AND pl.reaction IS NOT NULL))::int AS reactions
		FROM feed_posts p
		WHERE p.feed_id = $1 AND p.removed_at IS NULL AND p.created_at >= $2 AND p.created_at < $3
		  AND EXISTS (SELECT 1 FROM feed_post_items pi WHERE pi.post_id = p.id AND pi.removed_at IS NULL)
		ORDER BY reactions DESC, p.id DESC LIMIT 1`, feedID, from, to))
	if err != nil || len(rows) == 0 || rows[0].Reactions == 0 {
		return nil, err
	}
	return &rows[0], nil
}

// FreshFrame is a frame and on how many days of a month it got new photos.
type FreshFrame struct {
	FrameID   uuid.UUID `db:"frame_id"`
	FrameName string    `db:"frame_name"`
	Days      int       `db:"days"`
}

// FreshestFrames ranks the family's actively subscribed frames by days in [from, to) (in zone) with new
// non-backfill personal photos.
func (s *Store) FreshestFrames(ctx context.Context, feedID uuid.UUID, zone string, from, to time.Time, limit int) ([]FreshFrame, error) {
	return many[FreshFrame](s.Pool.Query(ctx, `
		SELECT f.id AS frame_id, f.name AS frame_name, count(DISTINCT (p.received_at AT TIME ZONE $2)::date)::int AS days
		FROM subscriptions su JOIN frames f ON f.id = su.frame_id AND f.deleted_at IS NULL
		JOIN placements p ON p.frame_id = f.id AND NOT p.backfill AND p.origin IN ('send', 'feed')
		JOIN sends s ON s.id = p.send_id
		WHERE su.feed_id = $1 AND su.status = 'active' AND p.received_at >= $3 AND p.received_at < $4 AND `+PersonalSendSQL+`
		GROUP BY f.id, f.name ORDER BY days DESC, f.name LIMIT $5`, feedID, zone, from, to, limit))
}

// NewMembers returns who joined the family in [from, to).
func (s *Store) NewMembers(ctx context.Context, feedID uuid.UUID, from, to time.Time) ([]uuid.UUID, error) {
	rows, err := many[idRow](s.Pool.Query(ctx, `SELECT user_id AS id FROM feed_members WHERE feed_id = $1 AND joined_at >= $2 AND joined_at < $3
		AND role <> 'owner' ORDER BY joined_at`, feedID, from, to))
	out := make([]uuid.UUID, len(rows))
	for i, r := range rows {
		out[i] = r.ID
	}
	return out, err
}
