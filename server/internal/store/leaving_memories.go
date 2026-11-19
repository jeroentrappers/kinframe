package store

// Memories that leave people out (docs/spec/leaving.md §4.7, README §5.21): a person's private lists of people and
// posts never shown in their memories (On this day, the monthly recap as rendered for them, the weekly summary).

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// MemoryHidden returns the people and posts userID left out of their memories.
func (s *Store) MemoryHidden(ctx context.Context, q db.DBTX, userID uuid.UUID) (people, posts []uuid.UUID, err error) {
	if people, err = uuids(q.Query(ctx, `SELECT hidden_user_id FROM memory_hidden_people WHERE user_id = $1 ORDER BY created_at, hidden_user_id`, userID)); err != nil {
		return nil, nil, err
	}
	posts, err = uuids(q.Query(ctx, `SELECT post_id FROM memory_hidden_posts WHERE user_id = $1 ORDER BY created_at, post_id`, userID))
	return people, posts, err
}

// SetMemoryHiddenPeople replaces the people userID leaves out. Only people they share or shared something with are
// kept (a family, a frame, a chat, posts in their families or photos on their frames); others are dropped.
func (tx *Tx) SetMemoryHiddenPeople(ctx context.Context, userID uuid.UUID, ids []uuid.UUID) error {
	ids = idsOrEmpty(ids)
	if _, err := tx.Exec(ctx, `DELETE FROM memory_hidden_people WHERE user_id = $1 AND NOT (hidden_user_id = ANY($2))`, userID, ids); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `INSERT INTO memory_hidden_people (user_id, hidden_user_id)
		SELECT $1, o FROM unnest($2::uuid[]) AS o
		WHERE o <> $1 AND EXISTS (SELECT 1 FROM users u WHERE u.id = o) AND (
			EXISTS (SELECT 1 FROM feed_members x JOIN feed_members y ON y.feed_id = x.feed_id AND y.user_id = o WHERE x.user_id = $1)
			OR EXISTS (SELECT 1 FROM memberships x JOIN memberships y ON y.frame_id = x.frame_id AND y.user_id = o WHERE x.user_id = $1)
			OR EXISTS (SELECT 1 FROM conversation_members x JOIN conversation_members y ON y.conversation_id = x.conversation_id AND y.user_id = o
				WHERE x.user_id = $1)
			OR EXISTS (SELECT 1 FROM feed_posts p JOIN feed_members m ON m.feed_id = p.feed_id AND m.user_id = $1 WHERE p.poster_user_id = o)
			OR EXISTS (SELECT 1 FROM placements p JOIN items i ON i.id = p.item_id JOIN memberships m ON m.frame_id = p.frame_id AND m.user_id = $1
				WHERE i.sender_user_id = o))
		ON CONFLICT DO NOTHING`, userID, ids)
	return err
}

// SetMemoryHiddenPosts replaces the posts userID never wants to see as a memory again; ids that are no post are
// dropped.
func (tx *Tx) SetMemoryHiddenPosts(ctx context.Context, userID uuid.UUID, ids []uuid.UUID) error {
	ids = idsOrEmpty(ids)
	if _, err := tx.Exec(ctx, `DELETE FROM memory_hidden_posts WHERE user_id = $1 AND NOT (post_id = ANY($2))`, userID, ids); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `INSERT INTO memory_hidden_posts (user_id, post_id)
		SELECT $1, o FROM unnest($2::uuid[]) AS o
		WHERE EXISTS (SELECT 1 FROM feed_posts p WHERE p.id = o) OR EXISTS (SELECT 1 FROM frame_posts p WHERE p.id = o)
		ON CONFLICT DO NOTHING`, userID, ids)
	return err
}

// PostPosters maps feed and frame posts to their poster (posts without one are absent).
func (s *Store) PostPosters(ctx context.Context, postIDs []uuid.UUID) (map[uuid.UUID]uuid.UUID, error) {
	type row struct {
		ID     uuid.UUID `db:"id"`
		Poster uuid.UUID `db:"poster"`
	}
	rows, err := many[row](s.Pool.Query(ctx, `SELECT id, poster_user_id AS poster FROM feed_posts WHERE id = ANY($1) AND poster_user_id IS NOT NULL
		UNION ALL SELECT id, sender_user_id FROM frame_posts WHERE id = ANY($1) AND sender_user_id IS NOT NULL`, postIDs))
	if err != nil {
		return nil, err
	}
	out := make(map[uuid.UUID]uuid.UUID, len(rows))
	for _, r := range rows {
		out[r.ID] = r.Poster
	}
	return out, nil
}

// LovedPost is a recap candidate: a post with its poster, first item and reactions.
type LovedPost struct {
	MostLoved
	PosterID *uuid.UUID `db:"poster_user_id"`
}

// MostLovedPosts ranks a family's posts of [from, to) like MostLovedPost, up to limit (only posts with reactions).
func (s *Store) MostLovedPosts(ctx context.Context, feedID uuid.UUID, from, to time.Time, limit int) ([]LovedPost, error) {
	return many[LovedPost](s.Pool.Query(ctx, `SELECT * FROM (
		SELECT p.id AS post_id, p.poster_user_id,
		       (SELECT pi.item_id FROM feed_post_items pi WHERE pi.post_id = p.id AND pi.removed_at IS NULL ORDER BY pi.position LIMIT 1) AS item_id,
		       ((SELECT count(*) FROM reactions r WHERE r.scope_id = p.id AND r.target_kind IN ('post', 'post_item'))
		        + (SELECT count(*) FROM feed_deliveries d JOIN placements pl ON pl.id = d.placement_id
		           WHERE d.post_id = p.id AND pl.reaction IS NOT NULL))::int AS reactions
		FROM feed_posts p
		WHERE p.feed_id = $1 AND p.removed_at IS NULL AND p.created_at >= $2 AND p.created_at < $3
		  AND EXISTS (SELECT 1 FROM feed_post_items pi WHERE pi.post_id = p.id AND pi.removed_at IS NULL)) x
		WHERE x.reactions > 0 ORDER BY x.reactions DESC, x.post_id DESC LIMIT $4`, feedID, from, to, limit))
}

// PostReactionsAndCommentsExcept is PostReactionsAndComments without reactions and comments by the given people.
func (s *Store) PostReactionsAndCommentsExcept(ctx context.Context, userID uuid.UUID, from, to time.Time, except []uuid.UUID) (int, int, error) {
	var reactions, comments int
	err := s.Pool.QueryRow(ctx, `SELECT
		(SELECT count(*)::int FROM reactions r WHERE r.target_kind IN ('post', 'post_item') AND r.user_id <> $1 AND NOT (r.user_id = ANY($4))
		   AND r.scope_id IN `+ownPostsSQL+` AND r.created_at >= $2 AND r.created_at < $3),
		(SELECT count(*)::int FROM comments c WHERE c.kind = 'person' AND c.author_user_id <> $1 AND NOT (c.author_user_id = ANY($4))
		   AND c.deleted_at IS NULL AND c.removed_at IS NULL AND c.post_id IN `+ownPostsSQL+` AND c.created_at >= $2 AND c.created_at < $3)`,
		userID, from, to, idsOrEmpty(except)).Scan(&reactions, &comments)
	return reactions, comments, err
}

// TopItemExcept is TopItem without people's reactions by the given people and without items of the given posts.
func (s *Store) TopItemExcept(ctx context.Context, userID uuid.UUID, from, to time.Time, exceptPeople, exceptPosts []uuid.UUID) (*uuid.UUID, error) {
	rows, err := many[idRow](s.Pool.Query(ctx, `
		SELECT x.item_id AS id FROM (
			SELECT p.item_id FROM placements p JOIN sends s ON s.id = p.send_id
			WHERE s.sender_user_id = $1 AND p.reaction IS NOT NULL AND p.reaction_at >= $2 AND p.reaction_at < $3
			UNION ALL
			SELECT r.target_id FROM reactions r JOIN items i ON i.id = r.target_id AND i.sender_user_id = $1
			WHERE r.target_kind = 'post_item' AND r.user_id <> $1 AND NOT (r.user_id = ANY($4)) AND r.created_at >= $2 AND r.created_at < $3
		) x
		WHERE NOT EXISTS (SELECT 1 FROM feed_post_items pi WHERE pi.item_id = x.item_id AND pi.post_id = ANY($5))
		  AND NOT EXISTS (SELECT 1 FROM frame_posts fp JOIN send_items si ON si.send_id = fp.send_id WHERE si.item_id = x.item_id AND fp.id = ANY($5))
		GROUP BY x.item_id ORDER BY count(*) DESC, x.item_id LIMIT 1`, userID, from, to, idsOrEmpty(exceptPeople), idsOrEmpty(exceptPosts)))
	if err != nil || len(rows) == 0 {
		return nil, err
	}
	return &rows[0].ID, nil
}

// idsOrEmpty: a nil slice is NULL in SQL, and x = ANY(NULL) is never true or false.
func idsOrEmpty(ids []uuid.UUID) []uuid.UUID {
	if ids == nil {
		return []uuid.UUID{}
	}
	return ids
}
