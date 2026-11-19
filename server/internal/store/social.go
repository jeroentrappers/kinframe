package store

import (
	"context"
	"encoding/json"
	"slices"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Family social (docs/spec/family-social.md, README §5.18): posts as the timeline sees them (feed posts and
// frame posts in one id space), threads, comments, people's reactions and push bundles. Tables: 00022_social.sql;
// frame posts follow their placements by the triggers of 00025_social_frame_posts.sql.

// ---------------------------------------------------------------- posts

// SocialPost is a feed post or a frame post with the viewer's relation to it and its thread.
type SocialPost struct {
	ID        uuid.UUID  `db:"id"`
	Kind      string     `db:"kind"` // "feed" or "frame"
	FeedID    *uuid.UUID `db:"feed_id"`
	CircleID  *uuid.UUID `db:"circle_id"`
	FrameID   *uuid.UUID `db:"frame_id"`
	SendID    *uuid.UUID `db:"send_id"`
	PosterID  *uuid.UUID `db:"poster_id"`
	CreatedAt time.Time  `db:"created_at"`
	// Gone: removed, its feed, circle or frame deleted.
	Gone       bool       `db:"gone"`
	FeedName   *string    `db:"feed_name"`
	CircleName *string    `db:"circle_name"`
	FrameName  *string    `db:"frame_name"`
	PromptID   *uuid.UUID `db:"prompt_id"`
	// The feed's settings (feed posts).
	MembersSeeReactions bool `db:"members_see_reactions"`
	MembersSeeReceipts  bool `db:"members_see_receipts"`
	// The viewer: feed role and mute, circle role (feed posts), frame role and see_library (frame posts).
	FeedRole   string `db:"feed_role"`
	FeedMute   string `db:"feed_mute"`
	CircleRole string `db:"circle_role"`
	FrameRole  string `db:"frame_role"`
	SeeLibrary bool   `db:"see_library"`
	// HasItems: a frame post still has a live placement on its frame (feed posts: always).
	HasItems bool `db:"has_items"`

	PosterName        string     `db:"poster_name"`
	PosterAvatarKey   *string    `db:"poster_avatar_key"`
	PosterDeleted     bool       `db:"poster_deleted"`
	Via               *string    `db:"via"`
	ViaSourceID       *uuid.UUID `db:"via_source_id"`
	ViaLabel          *string    `db:"via_label"`
	ViaEmailAddressID *uuid.UUID `db:"via_email_address_id"`
	ViaTelegramChatID *uuid.UUID `db:"via_telegram_chat_id"`

	// The thread (defaults when it has no row yet) and the viewer's read state and mute.
	ThreadState  string     `db:"thread_state"`
	CommentCount int        `db:"comment_count"`
	ReadUpTo     *uuid.UUID `db:"read_up_to"`
	Muted        bool       `db:"muted"`
}

// IsPoster reports whether userID made the post.
func (p *SocialPost) IsPoster(userID uuid.UUID) bool {
	return p.PosterID != nil && *p.PosterID == userID
}

// FeedAdmin: the viewer is the feed's owner or an admin (feed posts).
func (p *SocialPost) FeedAdmin() bool { return p.FeedRole == "owner" || p.FeedRole == "admin" }

// FrameManager: the viewer is the frame's owner or an admin (frame posts).
func (p *SocialPost) FrameManager() bool { return p.FrameRole == "owner" || p.FrameRole == "admin" }

// Visible reports whether the viewer may see the post (README §5.18): feed posts every member of the feed,
// circle posts the circle's members and the poster; frame posts the sender and the frame's members with
// see_library (owner and admins always).
func (p *SocialPost) Visible(viewer uuid.UUID) bool {
	if p.Gone {
		return false
	}
	if p.Kind == "feed" {
		return p.FeedRole != "" && (p.CircleID == nil || p.CircleRole != "" || p.IsPoster(viewer))
	}
	return p.HasItems && (p.IsPoster(viewer) || p.FrameManager() || (p.FrameRole != "" && p.SeeLibrary))
}

// Moderator: may close the thread and remove comments and the post. Whole-family posts: the feed's owner and
// admins; circle posts: the circle's admins; frame posts: the frame's owner and admins.
func (p *SocialPost) Moderator() bool {
	switch {
	case p.Kind == "frame":
		return p.FrameManager()
	case p.CircleID != nil:
		return p.CircleRole == "admin"
	}
	return p.FeedAdmin()
}

// SeesFrameReactions: frames' reactions in the reaction row and frame entries in the thread. Feed posts: the
// poster, owner/admins (and moderators), or everyone with members_see_reactions; frame posts: everyone who sees it.
func (p *SocialPost) SeesFrameReactions(viewer uuid.UUID) bool {
	return p.Kind == "frame" || p.MembersSeeReactions || p.IsPoster(viewer) || p.FeedAdmin() || p.Moderator()
}

// SeesReceipts: per-frame delivered and seen (TimelinePost.seen). Feed posts: the poster, owner/admins, or
// everyone with members_see_receipts; frame posts: everyone who sees it.
func (p *SocialPost) SeesReceipts(viewer uuid.UUID) bool {
	return p.Kind == "frame" || p.MembersSeeReceipts || p.IsPoster(viewer) || p.FeedAdmin()
}

// framePostItemsLive is the condition for the placements that make a frame post's items.
const framePostItemsLive = `pl.send_id = p.send_id AND pl.frame_id = p.frame_id AND pl.removed_at IS NULL AND pl.trashed_at IS NULL
	AND pl.origin = 'send' AND pl.shared_by_user_id IS NULL`

const socialPostSelect = `WITH posts AS (
	SELECT fp.id, 'feed'::text AS kind, fp.feed_id, fp.circle_id, NULL::uuid AS frame_id, fp.send_id, fp.poster_user_id AS poster_id,
		fp.created_at, (fp.removed_at IS NOT NULL OR fd.deleted_at IS NOT NULL OR c.deleted_at IS NOT NULL) AS gone,
		fd.name AS feed_name, c.name AS circle_name, NULL::text AS frame_name, fp.prompt_id,
		fd.members_see_reactions, fd.members_see_receipts,
		COALESCE(fm.role, '') AS feed_role, COALESCE(fm.mute, 'none') AS feed_mute, COALESCE(cm.role, '') AS circle_role,
		''::text AS frame_role, false AS see_library, true AS has_items
	FROM feed_posts fp JOIN feeds fd ON fd.id = fp.feed_id
	LEFT JOIN circles c ON c.id = fp.circle_id
	LEFT JOIN feed_members fm ON fm.feed_id = fp.feed_id AND fm.user_id = $1
	LEFT JOIN circle_members cm ON cm.circle_id = fp.circle_id AND cm.user_id = $1
	WHERE fp.id = ANY($2)
	UNION ALL
	SELECT p.id, 'frame', NULL, NULL, p.frame_id, p.send_id, p.sender_user_id, p.created_at,
		(p.removed_at IS NOT NULL OR f.deleted_at IS NOT NULL), NULL, NULL, f.name, NULL,
		false, false, '', 'none', '', COALESCE(m.role, ''), COALESCE((m.permissions->>'see_library')::boolean, false),
		EXISTS (SELECT 1 FROM placements pl WHERE ` + framePostItemsLive + `)
	FROM frame_posts p JOIN frames f ON f.id = p.frame_id
	LEFT JOIN memberships m ON m.frame_id = p.frame_id AND m.user_id = $1
	WHERE p.id = ANY($2))
SELECT x.id, x.kind, x.feed_id, x.circle_id, x.frame_id, x.send_id, x.poster_id, x.created_at, x.gone, x.feed_name, x.circle_name,
	x.frame_name, x.prompt_id, x.members_see_reactions, x.members_see_receipts, x.feed_role, x.feed_mute, x.circle_role,
	x.frame_role, x.see_library, x.has_items,
	CASE WHEN u.deleted_at IS NULL THEN COALESCE(u.display_name, '') ELSE '' END AS poster_name,
	CASE WHEN u.deleted_at IS NULL THEN u.avatar_key END AS poster_avatar_key, (u.id IS NULL OR u.deleted_at IS NOT NULL) AS poster_deleted,
	sd.via, sd.photo_source_id AS via_source_id, sd.via_label, sd.email_address_id AS via_email_address_id,
	sd.telegram_chat_id AS via_telegram_chat_id,
	COALESCE(t.state, 'open') AS thread_state, COALESCE(t.comment_count, 0) AS comment_count, r.read_up_to,
	COALESCE(r.muted, false) AS muted
FROM posts x LEFT JOIN users u ON u.id = x.poster_id LEFT JOIN sends sd ON sd.id = x.send_id
LEFT JOIN threads t ON t.post_id = x.id LEFT JOIN thread_reads r ON r.post_id = x.id AND r.user_id = $1
ORDER BY x.id DESC`

// SocialPosts loads posts (any state) as viewer sees them, newest first. Unknown ids are left out.
func (s *Store) SocialPosts(ctx context.Context, q db.DBTX, viewer uuid.UUID, ids []uuid.UUID) ([]SocialPost, error) {
	if len(ids) == 0 {
		return nil, nil
	}
	return many[SocialPost](q.Query(ctx, socialPostSelect, viewer, ids))
}

// SocialPost loads one post as viewer sees it.
func (s *Store) SocialPost(ctx context.Context, q db.DBTX, viewer, id uuid.UUID) (*SocialPost, error) {
	ps, err := s.SocialPosts(ctx, q, viewer, []uuid.UUID{id})
	if err != nil {
		return nil, err
	}
	if len(ps) == 0 {
		return nil, ErrNotFound
	}
	return &ps[0], nil
}

// TimelineFilter narrows the timeline (getTimeline's query parameters).
type TimelineFilter struct {
	FeedID, CircleID, PromptID, FrameID *uuid.UUID
	Before                              *uuid.UUID
	Limit                               int
}

// TimelineIDs lists the ids of the live posts viewer may see, newest first (README §5.18).
func (s *Store) TimelineIDs(ctx context.Context, viewer uuid.UUID, f TimelineFilter) ([]uuid.UUID, error) {
	rows, err := s.Pool.Query(ctx, `SELECT id FROM (
		SELECT fp.id FROM feed_posts fp JOIN feeds fd ON fd.id = fp.feed_id AND fd.deleted_at IS NULL
		JOIN feed_members fm ON fm.feed_id = fp.feed_id AND fm.user_id = $1
		WHERE fp.removed_at IS NULL AND $5::uuid IS NULL
			AND (fp.circle_id IS NULL OR fp.poster_user_id = $1
				OR EXISTS (SELECT 1 FROM circle_members cm WHERE cm.circle_id = fp.circle_id AND cm.user_id = $1))
			AND ($2::uuid IS NULL OR fp.feed_id = $2) AND ($3::uuid IS NULL OR fp.circle_id = $3)
			AND ($4::uuid IS NULL OR fp.prompt_id = $4) AND ($6::uuid IS NULL OR fp.id < $6)
		UNION ALL
		SELECT p.id FROM frame_posts p JOIN frames f ON f.id = p.frame_id AND f.deleted_at IS NULL
		WHERE p.removed_at IS NULL AND $2::uuid IS NULL AND $3::uuid IS NULL AND $4::uuid IS NULL
			AND ($5::uuid IS NULL OR p.frame_id = $5) AND ($6::uuid IS NULL OR p.id < $6)
			AND (p.sender_user_id = $1 OR EXISTS (SELECT 1 FROM memberships m WHERE m.frame_id = p.frame_id AND m.user_id = $1
				AND (m.role IN ('owner', 'admin') OR (m.permissions->>'see_library')::boolean)))
			AND EXISTS (SELECT 1 FROM placements pl WHERE `+framePostItemsLive+`)
		) x ORDER BY id DESC LIMIT $7`, viewer, f.FeedID, f.CircleID, f.PromptID, f.FrameID, f.Before, f.Limit)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// PostAudience lists everyone who may see a live post: the feed's members (circle posts: the circle's members
// and the poster, while a member of the feed), or a frame post's sender and the frame's members with
// see_library (owner and admins always).
func (s *Store) PostAudience(ctx context.Context, q db.DBTX, postID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `
		SELECT fm.user_id FROM feed_posts fp JOIN feeds fd ON fd.id = fp.feed_id AND fd.deleted_at IS NULL
		JOIN feed_members fm ON fm.feed_id = fp.feed_id
		WHERE fp.id = $1 AND fp.removed_at IS NULL AND (fp.circle_id IS NULL OR fm.user_id = fp.poster_user_id
			OR EXISTS (SELECT 1 FROM circle_members cm WHERE cm.circle_id = fp.circle_id AND cm.user_id = fm.user_id))
		UNION
		SELECT p.sender_user_id FROM frame_posts p WHERE p.id = $1 AND p.removed_at IS NULL AND p.sender_user_id IS NOT NULL
		UNION
		SELECT m.user_id FROM frame_posts p JOIN frames f ON f.id = p.frame_id AND f.deleted_at IS NULL
		JOIN memberships m ON m.frame_id = p.frame_id AND (m.role IN ('owner', 'admin') OR (m.permissions->>'see_library')::boolean)
		WHERE p.id = $1 AND p.removed_at IS NULL
		ORDER BY 1`, postID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// PostsOfSend lists the live feed posts and frame posts of a send.
func (s *Store) PostsOfSend(ctx context.Context, q db.DBTX, sendID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT id FROM feed_posts WHERE send_id = $1 AND removed_at IS NULL
		UNION ALL SELECT id FROM frame_posts WHERE send_id = $1 AND removed_at IS NULL ORDER BY 1`, sendID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// FramePostOfPlacement is the live frame post a direct placement belongs to (nil when none).
func (s *Store) FramePostOfPlacement(ctx context.Context, q db.DBTX, placementID uuid.UUID) (*uuid.UUID, error) {
	var id uuid.UUID
	err := q.QueryRow(ctx, `SELECT p.id FROM placements pl JOIN frame_posts p ON p.send_id = pl.send_id AND p.frame_id = pl.frame_id
		WHERE pl.id = $1 AND pl.origin = 'send' AND pl.shared_by_user_id IS NULL AND p.removed_at IS NULL`, placementID).Scan(&id)
	if db.NotFound(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &id, nil
}

// FramePostItemRow is a live item of a frame post with its placement on the frame.
type FramePostItemRow struct {
	PostID          uuid.UUID `db:"post_id"`
	Position        *int      `db:"position"`
	PlacementID     uuid.UUID `db:"placement_id"`
	PlacementStatus string    `db:"placement_status"`
	ItemRow
}

func (s *Store) FramePostItemRows(ctx context.Context, q db.DBTX, postIDs []uuid.UUID) ([]FramePostItemRow, error) {
	return many[FramePostItemRow](q.Query(ctx, `SELECT p.id AS post_id, si.position, pl.id AS placement_id, pl.status AS placement_status, `+itemRowCols+`
		FROM frame_posts p JOIN placements pl ON `+framePostItemsLive+`
		JOIN items i ON i.id = pl.item_id`+itemJoins+`
		LEFT JOIN send_items si ON si.send_id = p.send_id AND si.item_id = pl.item_id
		WHERE p.id = ANY($1) ORDER BY p.id, si.position NULLS LAST, pl.id`, postIDs))
}

// PostHasItem reports whether itemID is a live item of the post.
func (s *Store) PostHasItem(ctx context.Context, q db.DBTX, postID, itemID uuid.UUID) (bool, error) {
	var ok bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM feed_post_items pi WHERE pi.post_id = $1 AND pi.item_id = $2 AND pi.removed_at IS NULL)
		OR EXISTS (SELECT 1 FROM frame_posts p JOIN placements pl ON `+framePostItemsLive+` WHERE p.id = $1 AND pl.item_id = $2)`, postID, itemID).Scan(&ok)
	return ok, err
}

// DeletePostReactions removes people's reactions on a post, its items and comments (the post is gone).
func (tx *Tx) DeletePostReactions(ctx context.Context, postIDs []uuid.UUID) error {
	_, err := tx.Exec(ctx, `DELETE FROM reactions WHERE scope_id = ANY($1) AND target_kind IN ('post', 'post_item', 'comment')`, postIDs)
	return err
}

// FrameReactionRow is a frame's reaction on a placement of a post's item.
type FrameReactionRow struct {
	PostID    uuid.UUID `db:"post_id"`
	FrameID   uuid.UUID `db:"frame_id"`
	FrameName string    `db:"frame_name"`
	ItemID    uuid.UUID `db:"item_id"`
	Reaction  string    `db:"reaction"`
	At        time.Time `db:"at"`
}

// FrameReactions lists frames' current reactions on the items of posts, newest first: feed posts through
// their deliveries on subscribed frames, frame posts on their frame.
func (s *Store) FrameReactions(ctx context.Context, q db.DBTX, postIDs []uuid.UUID) ([]FrameReactionRow, error) {
	if len(postIDs) == 0 {
		return nil, nil
	}
	return many[FrameReactionRow](q.Query(ctx, `
		SELECT d.post_id, f.id AS frame_id, f.name AS frame_name, pl.item_id, pl.reaction, COALESCE(pl.reaction_at, pl.received_at) AS at
		FROM feed_deliveries d JOIN subscriptions s ON s.id = d.subscription_id AND s.status IN ('active', 'paused')
		JOIN frames f ON f.id = s.frame_id AND f.deleted_at IS NULL
		JOIN placements pl ON pl.id = d.placement_id AND pl.removed_at IS NULL
		JOIN feed_post_items pi ON pi.post_id = d.post_id AND pi.item_id = d.item_id AND pi.removed_at IS NULL
		WHERE d.post_id = ANY($1) AND d.state = 'placed' AND pl.reaction IS NOT NULL
		UNION ALL
		SELECT p.id, f.id, f.name, pl.item_id, pl.reaction, COALESCE(pl.reaction_at, pl.received_at)
		FROM frame_posts p JOIN frames f ON f.id = p.frame_id AND f.deleted_at IS NULL
		JOIN placements pl ON `+framePostItemsLive+`
		WHERE p.id = ANY($1) AND pl.reaction IS NOT NULL
		ORDER BY 6 DESC, 2, 4`, postIDs))
}

// ---------------------------------------------------------------- threads

// EnsureThread creates the post's thread row if it has none (threads are created lazily).
func (tx *Tx) EnsureThread(ctx context.Context, p *SocialPost) error {
	var feedPost, framePost *uuid.UUID
	if p.Kind == "feed" {
		feedPost = &p.ID
	} else {
		framePost = &p.ID
	}
	_, err := tx.Exec(ctx, `INSERT INTO threads (post_id, feed_post_id, frame_post_id, feed_id, circle_id, frame_id)
		VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT (post_id) DO NOTHING`, p.ID, feedPost, framePost, p.FeedID, p.CircleID, p.FrameID)
	return err
}

// LockThread takes the thread's row lock (after EnsureThread): comment writes on one thread are serialized.
func (tx *Tx) LockThread(ctx context.Context, postID uuid.UUID) (state string, err error) {
	err = tx.QueryRow(ctx, `SELECT state FROM threads WHERE post_id = $1 FOR UPDATE`, postID).Scan(&state)
	return state, err
}

// RecountThread recomputes comment_count and the last comment (live person comments and replies).
func (tx *Tx) RecountThread(ctx context.Context, postID uuid.UUID) error {
	const live = `FROM comments c WHERE c.post_id = $1 AND c.kind = 'person' AND c.deleted_at IS NULL AND c.removed_at IS NULL`
	_, err := tx.Exec(ctx, `UPDATE threads SET comment_count = (SELECT count(*) `+live+`),
		last_comment_id = (SELECT c.id `+live+` ORDER BY c.id DESC LIMIT 1),
		last_comment_at = (SELECT c.created_at `+live+` ORDER BY c.id DESC LIMIT 1)
		WHERE post_id = $1`, postID)
	return err
}

// SetThreadState opens or closes a thread.
func (tx *Tx) SetThreadState(ctx context.Context, postID uuid.UUID, state string, by uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE threads SET state = $2,
		closed_by = CASE WHEN $2 = 'closed' THEN $3::uuid END, closed_at = CASE WHEN $2 = 'closed' THEN now() END
		WHERE post_id = $1`, postID, state, by)
	return err
}

// MarkThreadRead moves the reader's position forward (never back). Reports whether it moved.
func (tx *Tx) MarkThreadRead(ctx context.Context, userID, postID, upTo uuid.UUID) (bool, error) {
	tag, err := tx.Exec(ctx, `INSERT INTO thread_reads (user_id, post_id, read_up_to, read_at) VALUES ($1, $2, $3, now())
		ON CONFLICT (user_id, post_id) DO UPDATE SET read_up_to = EXCLUDED.read_up_to, read_at = now()
		WHERE thread_reads.read_up_to IS NULL OR thread_reads.read_up_to < EXCLUDED.read_up_to`, userID, postID, upTo)
	return tag.RowsAffected() > 0, err
}

// SetThreadMute stores the reader's mute for a thread.
func (tx *Tx) SetThreadMute(ctx context.Context, userID, postID uuid.UUID, muted bool) error {
	_, err := tx.Exec(ctx, `INSERT INTO thread_reads (user_id, post_id, muted) VALUES ($1, $2, $3)
		ON CONFLICT (user_id, post_id) DO UPDATE SET muted = EXCLUDED.muted`, userID, postID, muted)
	return err
}

// ThreadMuted lists which of users muted the thread.
func (s *Store) ThreadMuted(ctx context.Context, q db.DBTX, postID uuid.UUID, users []uuid.UUID) (map[uuid.UUID]bool, error) {
	rows, err := q.Query(ctx, `SELECT user_id FROM thread_reads WHERE post_id = $1 AND user_id = ANY($2) AND muted`, postID, users)
	if err != nil {
		return nil, err
	}
	return idSet(rows)
}

// UnreadCounts is a viewer's unread state of one thread.
type UnreadCounts struct {
	PostID   uuid.UUID `db:"post_id"`
	Unread   int       `db:"unread"`
	Mentions int       `db:"mentions"`
}

// ThreadUnread counts, per post, the live person comments after the viewer's read position that are not their
// own and not by people they blocked; Mentions are those that mention the viewer or reply to them.
func (s *Store) ThreadUnread(ctx context.Context, q db.DBTX, viewer uuid.UUID, postIDs []uuid.UUID) (map[uuid.UUID]UnreadCounts, error) {
	rs, err := many[UnreadCounts](q.Query(ctx, `SELECT c.post_id, count(*)::int AS unread,
			(count(*) FILTER (WHERE c.quoted_user_id = $1
				OR EXISTS (SELECT 1 FROM comment_mentions cm WHERE cm.comment_id = c.id AND cm.user_id = $1)
				OR EXISTS (SELECT 1 FROM comments pc WHERE pc.id = c.parent_id AND pc.author_user_id = $1)))::int AS mentions
		FROM comments c LEFT JOIN thread_reads r ON r.post_id = c.post_id AND r.user_id = $1
		WHERE c.post_id = ANY($2) AND c.kind = 'person' AND c.deleted_at IS NULL AND c.removed_at IS NULL
			AND c.author_user_id IS DISTINCT FROM $1 AND (r.read_up_to IS NULL OR c.id > r.read_up_to)
			AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = $1 AND b.blocked_user_id = c.author_user_id)
		GROUP BY c.post_id`, viewer, postIDs))
	if err != nil {
		return nil, err
	}
	out := make(map[uuid.UUID]UnreadCounts, len(rs))
	for _, r := range rs {
		out[r.PostID] = r
	}
	return out, nil
}

// ---------------------------------------------------------------- comments

// MentionSpan is a mention inside a body (UTF-16 offsets, README §5.18).
type MentionSpan struct {
	UserID uuid.UUID `json:"user_id"`
	Start  int       `json:"start"`
	Length int       `json:"length"`
}

// Comment is a comment, reply or frame entry with its people.
type Comment struct {
	ID              uuid.UUID     `db:"id"`
	PostID          uuid.UUID     `db:"post_id"`
	ParentID        *uuid.UUID    `db:"parent_id"`
	Kind            string        `db:"kind"`
	AuthorID        *uuid.UUID    `db:"author_user_id"`
	AuthorName      string        `db:"author_name"`
	AuthorAvatarKey *string       `db:"author_avatar_key"`
	AuthorDeleted   bool          `db:"author_deleted"`
	QuotedID        *uuid.UUID    `db:"quoted_user_id"`
	QuotedName      string        `db:"quoted_name"`
	QuotedAvatarKey *string       `db:"quoted_avatar_key"`
	QuotedDeleted   bool          `db:"quoted_deleted"`
	FrameID         *uuid.UUID    `db:"frame_id"`
	FrameName       *string       `db:"frame_name"`
	PlacementID     *uuid.UUID    `db:"placement_id"`
	ItemID          *uuid.UUID    `db:"item_id"`
	FrameReaction   *string       `db:"frame_reaction"`
	FrameReply      *string       `db:"frame_reply"`
	Body            *string       `db:"body"`
	BodyEnc         []byte        `db:"body_enc"`
	Mentions        []MentionSpan `db:"mentions"`
	CreatedAt       time.Time     `db:"created_at"`
	EditedAt        *time.Time    `db:"edited_at"`
	DeletedAt       *time.Time    `db:"deleted_at"`
	RemovedAt       *time.Time    `db:"removed_at"`
	ReplyCount      int           `db:"reply_count"`
}

// Live: a person comment that is neither deleted nor removed.
func (c *Comment) Live() bool { return c.DeletedAt == nil && c.RemovedAt == nil }

const commentCols = `c.id, c.post_id, c.parent_id, c.kind, c.author_user_id,
	CASE WHEN au.deleted_at IS NULL THEN COALESCE(au.display_name, '') ELSE '' END AS author_name,
	CASE WHEN au.deleted_at IS NULL THEN au.avatar_key END AS author_avatar_key,
	(c.kind = 'person' AND (au.id IS NULL OR au.deleted_at IS NOT NULL)) AS author_deleted,
	c.quoted_user_id, CASE WHEN qu.deleted_at IS NULL THEN COALESCE(qu.display_name, '') ELSE '' END AS quoted_name,
	CASE WHEN qu.deleted_at IS NULL THEN qu.avatar_key END AS quoted_avatar_key,
	(c.quoted_user_id IS NOT NULL AND qu.deleted_at IS NOT NULL) AS quoted_deleted,
	c.frame_id, c.frame_name, c.placement_id, c.item_id, c.frame_reaction, c.frame_reply, c.body, c.body_enc, c.mentions,
	c.created_at, c.edited_at, c.deleted_at, c.removed_at,
	(SELECT count(*) FROM comments r WHERE r.parent_id = c.id AND r.deleted_at IS NULL AND r.removed_at IS NULL)::int AS reply_count`

const commentJoins = ` LEFT JOIN users au ON au.id = c.author_user_id LEFT JOIN users qu ON qu.id = c.quoted_user_id`

// GetComment returns a comment of the post (any state).
func (s *Store) GetComment(ctx context.Context, q db.DBTX, postID, id uuid.UUID) (*Comment, error) {
	return one[Comment](q.Query(ctx, `SELECT `+commentCols+` FROM comments c`+commentJoins+` WHERE c.id = $1 AND c.post_id = $2`, id, postID))
}

// CommentsByID loads comments of a post by id (any state), in thread order.
func (s *Store) CommentsByID(ctx context.Context, q db.DBTX, postID uuid.UUID, ids []uuid.UUID) ([]Comment, error) {
	return many[Comment](q.Query(ctx, `SELECT `+commentCols+` FROM comments c`+commentJoins+`
		WHERE c.post_id = $1 AND c.id = ANY($2) ORDER BY COALESCE(c.parent_id, c.id), c.id`, postID, ids))
}

// ListComments pages a thread as viewer sees it, flat in thread order (top comments oldest first, each followed
// by its replies): comments by people the viewer blocked are left out, removed ones except for their author,
// deleted ones except top comments that still have live replies (tombstones), frame entries unless frames is
// set. after is a comment id of the thread.
func (s *Store) ListComments(ctx context.Context, viewer, postID uuid.UUID, frames bool, after *uuid.UUID, limit int) ([]Comment, error) {
	var k1, k2 *uuid.UUID
	if after != nil {
		var parent *uuid.UUID
		err := s.Pool.QueryRow(ctx, `SELECT parent_id FROM comments WHERE id = $1 AND post_id = $2`, *after, postID).Scan(&parent)
		if err != nil && !db.NotFound(err) {
			return nil, err
		}
		if err == nil {
			k1, k2 = after, after
			if parent != nil {
				k1 = parent
			}
		}
	}
	return many[Comment](s.Pool.Query(ctx, `SELECT `+commentCols+` FROM comments c`+commentJoins+`
		WHERE c.post_id = $1 AND (c.kind = 'person' OR $3)
			AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = $2 AND b.blocked_user_id = c.author_user_id)
			AND (c.removed_at IS NULL OR c.author_user_id = $2)
			AND (c.deleted_at IS NULL OR (c.parent_id IS NULL AND c.removed_at IS NULL AND EXISTS (SELECT 1 FROM comments r
				WHERE r.parent_id = c.id AND r.deleted_at IS NULL AND r.removed_at IS NULL)))
			AND ($4::uuid IS NULL OR (COALESCE(c.parent_id, c.id), c.id) > ($4::uuid, $5::uuid))
		ORDER BY COALESCE(c.parent_id, c.id), c.id LIMIT $6`, postID, viewer, frames, k1, k2, limit))
}

// LatestComments returns, per post, the two newest live person comments the viewer sees (oldest first).
func (s *Store) LatestComments(ctx context.Context, q db.DBTX, viewer uuid.UUID, postIDs []uuid.UUID) (map[uuid.UUID][]Comment, error) {
	cs, err := many[Comment](q.Query(ctx, `SELECT x.* FROM unnest($2::uuid[]) AS p(id) CROSS JOIN LATERAL (
		SELECT `+commentCols+` FROM comments c`+commentJoins+`
		WHERE c.post_id = p.id AND c.kind = 'person' AND c.deleted_at IS NULL AND c.removed_at IS NULL
			AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = $1 AND b.blocked_user_id = c.author_user_id)
		ORDER BY c.id DESC LIMIT 2) x ORDER BY x.post_id, x.id`, viewer, postIDs))
	if err != nil {
		return nil, err
	}
	out := map[uuid.UUID][]Comment{}
	for _, c := range cs {
		out[c.PostID] = append(out[c.PostID], c)
	}
	return out, nil
}

// NewComment is a person comment or reply to insert.
type NewComment struct {
	ID       uuid.UUID
	PostID   uuid.UUID
	ParentID *uuid.UUID
	AuthorID uuid.UUID
	QuotedID *uuid.UUID
	Body     *string // plain text, or nil when sealed
	BodyEnc  []byte
	Mentions []MentionSpan
}

// CreateComment inserts a person comment and its mention rows.
func (tx *Tx) CreateComment(ctx context.Context, c NewComment) error {
	ms, err := mentionsJSON(c.Mentions)
	if err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO comments (id, post_id, parent_id, kind, author_user_id, quoted_user_id, body, body_enc, mentions)
		VALUES ($1, $2, $3, 'person', $4, $5, $6, $7, $8)`, c.ID, c.PostID, c.ParentID, c.AuthorID, c.QuotedID, c.Body, c.BodyEnc, ms); err != nil {
		return err
	}
	return tx.setCommentMentions(ctx, c.ID, c.Mentions)
}

// UpdateCommentBody replaces a comment's body and mentions (edited_at set).
func (tx *Tx) UpdateCommentBody(ctx context.Context, id uuid.UUID, body *string, bodyEnc []byte, mentions []MentionSpan) error {
	ms, err := mentionsJSON(mentions)
	if err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE comments SET body = $2, body_enc = $3, mentions = $4, edited_at = now() WHERE id = $1`,
		id, body, bodyEnc, ms); err != nil {
		return err
	}
	return tx.setCommentMentions(ctx, id, mentions)
}

func (tx *Tx) setCommentMentions(ctx context.Context, id uuid.UUID, mentions []MentionSpan) error {
	if _, err := tx.Exec(ctx, `DELETE FROM comment_mentions WHERE comment_id = $1`, id); err != nil {
		return err
	}
	users := make([]uuid.UUID, 0, len(mentions))
	for _, m := range mentions {
		users = append(users, m.UserID)
	}
	if len(users) == 0 {
		return nil
	}
	_, err := tx.Exec(ctx, `INSERT INTO comment_mentions (comment_id, user_id) SELECT $1, u FROM unnest($2::uuid[]) AS u ON CONFLICT DO NOTHING`, id, users)
	return err
}

func mentionsJSON(ms []MentionSpan) ([]byte, error) {
	if ms == nil {
		ms = []MentionSpan{}
	}
	return json.Marshal(ms)
}

// DeleteCommentByAuthor deletes a comment: the body and mentions go, the row stays (a tombstone while it is a top
// comment with live replies; otherwise it no longer shows).
func (tx *Tx) DeleteCommentByAuthor(ctx context.Context, id uuid.UUID) error {
	if _, err := tx.Exec(ctx, `UPDATE comments SET deleted_at = now(), body = NULL, body_enc = NULL, mentions = '[]' WHERE id = $1 AND deleted_at IS NULL`, id); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `DELETE FROM comment_mentions WHERE comment_id = $1`, id)
	return err
}

// RemoveComment hides a comment and its replies for everyone but their authors (a moderator's delete).
// Returns the ids removed now.
func (tx *Tx) RemoveComment(ctx context.Context, id, by uuid.UUID) ([]uuid.UUID, error) {
	rows, err := tx.Query(ctx, `UPDATE comments SET removed_at = now(), removed_by = $2
		WHERE (id = $1 OR parent_id = $1) AND removed_at IS NULL AND deleted_at IS NULL RETURNING id`, id, by)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// HasCommented reports whether userID wrote a person comment in the thread.
func (s *Store) HasCommented(ctx context.Context, q db.DBTX, postID, userID uuid.UUID) (bool, error) {
	var ok bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM comments WHERE post_id = $1 AND author_user_id = $2 AND kind = 'person')`, postID, userID).Scan(&ok)
	return ok, err
}

// Commenters lists the authors of live person comments in a thread made before the comment before.
func (s *Store) Commenters(ctx context.Context, q db.DBTX, postID, before uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT DISTINCT author_user_id FROM comments WHERE post_id = $1 AND kind = 'person' AND id < $2
		AND author_user_id IS NOT NULL AND deleted_at IS NULL AND removed_at IS NULL ORDER BY 1`, postID, before)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// LatestCommentFor is the newest live person comment of a thread not written by viewer nor by people they
// blocked (bundled pushes).
func (s *Store) LatestCommentFor(ctx context.Context, q db.DBTX, viewer, postID uuid.UUID) (*Comment, error) {
	return one[Comment](q.Query(ctx, `SELECT `+commentCols+` FROM comments c`+commentJoins+`
		WHERE c.post_id = $1 AND c.kind = 'person' AND c.deleted_at IS NULL AND c.removed_at IS NULL
			AND c.author_user_id IS DISTINCT FROM $2
			AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = $2 AND b.blocked_user_id = c.author_user_id)
		ORDER BY c.id DESC LIMIT 1`, postID, viewer))
}

// FrameEntry is a frame's line in a thread.
type FrameEntry struct {
	PostID      uuid.UUID
	FrameID     uuid.UUID
	FrameName   string
	PlacementID uuid.UUID
	ItemID      uuid.UUID
}

// SetFrameReactionEntry makes the thread's reaction entry of a frame show reaction (on entry's placement), or
// deletes it when reaction is nil. Returns the entry's id and whether it was created, changed or deleted.
func (tx *Tx) SetFrameReactionEntry(ctx context.Context, e FrameEntry, reaction *string) (id uuid.UUID, change string, err error) {
	var cur, curItem *string
	err = tx.QueryRow(ctx, `SELECT id, frame_reaction, item_id::text FROM comments WHERE post_id = $1 AND frame_id = $2 AND kind = 'frame'
		AND frame_reaction IS NOT NULL FOR UPDATE`, e.PostID, e.FrameID).Scan(&id, &cur, &curItem)
	switch {
	case db.NotFound(err):
		if reaction == nil {
			return uuid.Nil, "", nil
		}
		id = NewID()
		_, err = tx.Exec(ctx, `INSERT INTO comments (id, post_id, kind, frame_id, frame_name, placement_id, item_id, frame_reaction)
			VALUES ($1, $2, 'frame', $3, $4, $5, $6, $7)`, id, e.PostID, e.FrameID, e.FrameName, e.PlacementID, e.ItemID, *reaction)
		return id, "created", err
	case err != nil:
		return uuid.Nil, "", err
	case reaction == nil:
		_, err = tx.Exec(ctx, `DELETE FROM comments WHERE id = $1`, id)
		return id, "deleted", err
	case cur != nil && *cur == *reaction && curItem != nil && *curItem == e.ItemID.String():
		return id, "", nil
	}
	_, err = tx.Exec(ctx, `UPDATE comments SET frame_reaction = $2, item_id = $3, placement_id = $4, frame_name = $5 WHERE id = $1`,
		id, *reaction, e.ItemID, e.PlacementID, e.FrameName)
	return id, "updated", err
}

// AddFrameReplyEntry adds a frame's quick reply to a thread.
func (tx *Tx) AddFrameReplyEntry(ctx context.Context, e FrameEntry, preset string) (uuid.UUID, error) {
	id := NewID()
	_, err := tx.Exec(ctx, `INSERT INTO comments (id, post_id, kind, frame_id, frame_name, placement_id, item_id, frame_reply)
		VALUES ($1, $2, 'frame', $3, $4, $5, $6, $7)`, id, e.PostID, e.FrameID, e.FrameName, e.PlacementID, e.ItemID, preset)
	return id, err
}

// LatestFrameReaction is the frame's newest reaction on the post's items (nil when it has none).
func (s *Store) LatestFrameReaction(ctx context.Context, q db.DBTX, postID, frameID uuid.UUID) (*FrameReactionRow, error) {
	rs, err := s.FrameReactions(ctx, q, []uuid.UUID{postID})
	if err != nil {
		return nil, err
	}
	for i := range rs {
		if rs[i].FrameID == frameID {
			return &rs[i], nil
		}
	}
	return nil, nil
}

// PlacementOnFrame is the live placement of an item on a frame.
func (s *Store) PlacementOnFrame(ctx context.Context, q db.DBTX, frameID, itemID uuid.UUID) (uuid.UUID, error) {
	var id uuid.UUID
	err := q.QueryRow(ctx, `SELECT id FROM placements WHERE frame_id = $1 AND item_id = $2 AND removed_at IS NULL`, frameID, itemID).Scan(&id)
	if db.NotFound(err) {
		return uuid.Nil, ErrNotFound
	}
	return id, err
}

// ---------------------------------------------------------------- people's reactions

// PersonReactionRow is one person's reaction with the person.
type PersonReactionRow struct {
	TargetKind string    `db:"target_kind"`
	TargetID   uuid.UUID `db:"target_id"`
	UserID     uuid.UUID `db:"user_id"`
	Emoji      string    `db:"emoji"`
	CreatedAt  time.Time `db:"created_at"`
	Name       string    `db:"name"`
	AvatarKey  *string   `db:"avatar_key"`
	Deleted    bool      `db:"deleted"`
}

// PersonReactions lists people's reactions on targets of one kind within a scope, newest first.
func (s *Store) PersonReactions(ctx context.Context, q db.DBTX, kind string, scopeID uuid.UUID, targets []uuid.UUID) ([]PersonReactionRow, error) {
	return many[PersonReactionRow](q.Query(ctx, `SELECT r.target_kind, r.target_id, r.user_id, r.emoji, r.created_at,
		CASE WHEN u.deleted_at IS NULL THEN u.display_name ELSE '' END AS name,
		CASE WHEN u.deleted_at IS NULL THEN u.avatar_key END AS avatar_key, u.deleted_at IS NOT NULL AS deleted
		FROM reactions r JOIN users u ON u.id = r.user_id
		WHERE r.target_kind = $1 AND r.scope_id = $2 AND r.target_id = ANY($3) ORDER BY r.created_at DESC, r.user_id`, kind, scopeID, targets))
}

// SetPersonReaction stores (emoji non-nil) or removes a person's reaction. Returns the previous emoji.
func (tx *Tx) SetPersonReaction(ctx context.Context, kind string, targetID, scopeID, userID uuid.UUID, emoji *string) (*string, error) {
	var prev *string
	err := tx.QueryRow(ctx, `SELECT emoji FROM reactions WHERE target_kind = $1 AND target_id = $2 AND scope_id = $3 AND user_id = $4 FOR UPDATE`,
		kind, targetID, scopeID, userID).Scan(&prev)
	if err != nil && !db.NotFound(err) {
		return nil, err
	}
	if emoji == nil {
		_, err = tx.Exec(ctx, `DELETE FROM reactions WHERE target_kind = $1 AND target_id = $2 AND scope_id = $3 AND user_id = $4`, kind, targetID, scopeID, userID)
		return prev, err
	}
	_, err = tx.Exec(ctx, `INSERT INTO reactions (target_kind, target_id, scope_id, user_id, emoji) VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (target_kind, target_id, scope_id, user_id) DO UPDATE SET emoji = EXCLUDED.emoji, created_at = now()
		WHERE reactions.emoji <> EXCLUDED.emoji`, kind, targetID, scopeID, userID, *emoji)
	return prev, err
}

// ---------------------------------------------------------------- people

// WhoBlocked lists which of users blocked author.
func (s *Store) WhoBlocked(ctx context.Context, q db.DBTX, author uuid.UUID, users []uuid.UUID) (map[uuid.UUID]bool, error) {
	rows, err := q.Query(ctx, `SELECT user_id FROM blocks WHERE blocked_user_id = $1 AND user_id = ANY($2)`, author, users)
	if err != nil {
		return nil, err
	}
	return idSet(rows)
}

// Blocked reports whether a blocked b or b blocked a.
func (s *Store) Blocked(ctx context.Context, q db.DBTX, a, b uuid.UUID) (bool, error) {
	var ok bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM blocks WHERE (user_id = $1 AND blocked_user_id = $2) OR (user_id = $2 AND blocked_user_id = $1))`, a, b).Scan(&ok)
	return ok, err
}

// ContactsAmong lists which of users share a live feed or a frame with viewer.
func (s *Store) ContactsAmong(ctx context.Context, q db.DBTX, viewer uuid.UUID, users []uuid.UUID) (map[uuid.UUID]bool, error) {
	rows, err := q.Query(ctx, `SELECT o.user_id FROM feed_members me JOIN feeds f ON f.id = me.feed_id AND f.deleted_at IS NULL
		JOIN feed_members o ON o.feed_id = me.feed_id WHERE me.user_id = $1 AND o.user_id = ANY($2)
		UNION SELECT o.user_id FROM memberships me JOIN memberships o ON o.frame_id = me.frame_id WHERE me.user_id = $1 AND o.user_id = ANY($2)`,
		viewer, users)
	if err != nil {
		return nil, err
	}
	return idSet(rows)
}

// UserTimezone returns the user's IANA time zone ("" when unknown).
func (s *Store) UserTimezone(ctx context.Context, userID uuid.UUID) (string, error) {
	var tz *string
	err := s.Pool.QueryRow(ctx, `SELECT timezone FROM users WHERE id = $1`, userID).Scan(&tz)
	if err != nil || tz == nil {
		return "", err
	}
	return *tz, nil
}

func idSet(rows pgx.Rows) (map[uuid.UUID]bool, error) {
	ids, err := collectIDs(rows)
	if err != nil {
		return nil, err
	}
	out := make(map[uuid.UUID]bool, len(ids))
	for _, id := range ids {
		out[id] = true
	}
	return out, nil
}

// ---------------------------------------------------------------- push bundles

// maxBundleActors is how many distinct actors a windowed bundle remembers (newest first); counted keys
// remember up to maxCountedActors so a person who reacts again is not counted twice.
const (
	maxBundleActors  = 5
	maxCountedActors = 100
)

// PushBundle is a recipient's collapsing state for one push key (README §6.3).
type PushBundle struct {
	UserID       uuid.UUID   `db:"user_id"`
	Key          string      `db:"bundle_key"`
	PushedCount  int         `db:"pushed_count"`
	PendingCount int         `db:"pending_count"`
	ActorIDs     []uuid.UUID `db:"actor_ids"`
	WindowUntil  *time.Time  `db:"window_until"`
	LastEventAt  *time.Time  `db:"last_event_at"`
	LastSentAt   *time.Time  `db:"last_sent_at"`
}

const bundleCols = `user_id, bundle_key, pushed_count, pending_count, actor_ids, window_until, last_event_at, last_sent_at`

func (tx *Tx) lockBundle(ctx context.Context, userID uuid.UUID, key string) (*PushBundle, error) {
	if _, err := tx.Exec(ctx, `INSERT INTO push_bundles (user_id, bundle_key) VALUES ($1, $2) ON CONFLICT DO NOTHING`, userID, key); err != nil {
		return nil, err
	}
	return one[PushBundle](tx.Query(ctx, `SELECT `+bundleCols+` FROM push_bundles WHERE user_id = $1 AND bundle_key = $2 FOR UPDATE`, userID, key))
}

func withActor(actors []uuid.UUID, actor uuid.UUID) []uuid.UUID {
	out := []uuid.UUID{actor}
	for _, a := range actors {
		if a != actor && len(out) < maxBundleActors {
			out = append(out, a)
		}
	}
	return out
}

// BundleDecision says what to do with one event of a bundled key.
type BundleDecision struct {
	PushNow bool
	Actors  []uuid.UUID // the distinct actors the push covers, newest first
}

// BundleEvent records an event of a windowed key (comment:<post_id>): in a quiet key it is pushed at once and
// opens a window; within the window it is held (pending) until FlushBundles sends one push at its end. A
// window that ended with events still held (the flush is late) is pushed now, covering them.
func (s *Store) BundleEvent(ctx context.Context, userID uuid.UUID, key string, actor uuid.UUID, now time.Time, window time.Duration) (BundleDecision, error) {
	var d BundleDecision
	err := s.InTx(ctx, func(tx *Tx) error {
		b, err := tx.lockBundle(ctx, userID, key)
		if err != nil {
			return err
		}
		if b.WindowUntil != nil && now.Before(*b.WindowUntil) {
			d = BundleDecision{Actors: withActor(b.ActorIDs, actor)}
			_, err = tx.Exec(ctx, `UPDATE push_bundles SET pending_count = pending_count + 1, actor_ids = $3, last_event_at = $4
				WHERE user_id = $1 AND bundle_key = $2`, userID, key, d.Actors, now)
			return err
		}
		prev := []uuid.UUID{}
		if b.PendingCount > 0 {
			prev = b.ActorIDs
		}
		d = BundleDecision{PushNow: true, Actors: withActor(prev, actor)}
		_, err = tx.Exec(ctx, `UPDATE push_bundles SET pushed_count = pushed_count + 1, pending_count = 0, actor_ids = $3,
			window_until = $4, last_event_at = $5, last_sent_at = $5 WHERE user_id = $1 AND bundle_key = $2`,
			userID, key, []uuid.UUID{actor}, now.Add(window), now)
		return err
	})
	return d, err
}

// CountedDecision says whether a counted key (reaction:<kind>:<id>) pushes this actor.
type CountedDecision struct {
	PushNow  bool // one of the first `limit` distinct actors
	Overflow bool // a new actor beyond the limit: counted for the digest
}

// CountEvent records an actor on a counted key: the first limit distinct actors are pushed, later ones are
// counted (pending) and reported as overflow; an actor seen before is neither.
func (s *Store) CountEvent(ctx context.Context, userID uuid.UUID, key string, actor uuid.UUID, limit int, now time.Time) (CountedDecision, error) {
	var d CountedDecision
	err := s.InTx(ctx, func(tx *Tx) error {
		b, err := tx.lockBundle(ctx, userID, key)
		if err != nil {
			return err
		}
		if slices.Contains(b.ActorIDs, actor) {
			return nil
		}
		if b.PushedCount < limit {
			d.PushNow = true
			_, err = tx.Exec(ctx, `UPDATE push_bundles SET pushed_count = pushed_count + 1, actor_ids = $3, last_event_at = $4, last_sent_at = $4
				WHERE user_id = $1 AND bundle_key = $2`, userID, key, append(slices.Clone(b.ActorIDs), actor), now)
			return err
		}
		d.Overflow = true
		actors := b.ActorIDs
		if len(actors) < maxCountedActors {
			actors = append(slices.Clone(actors), actor)
		}
		_, err = tx.Exec(ctx, `UPDATE push_bundles SET pending_count = pending_count + 1, actor_ids = $3, last_event_at = $4
			WHERE user_id = $1 AND bundle_key = $2`, userID, key, actors, now)
		return err
	})
	return d, err
}

// AddToDigest counts one event for a daily key (reaction_digest), due at due unless it is due earlier already.
func (s *Store) AddToDigest(ctx context.Context, userID uuid.UUID, key string, due, now time.Time) error {
	_, err := s.Pool.Exec(ctx, `INSERT INTO push_bundles (user_id, bundle_key, pending_count, window_until, last_event_at)
		VALUES ($1, $2, 1, $3, $4)
		ON CONFLICT (user_id, bundle_key) DO UPDATE SET pending_count = push_bundles.pending_count + 1, last_event_at = $4,
			window_until = CASE WHEN push_bundles.pending_count = 0 OR push_bundles.window_until IS NULL THEN $3
				ELSE push_bundles.window_until END`, userID, key, due, now)
	return err
}

// FlushBundles claims the bundles with held events whose window ended at now (keys with the given prefixes or
// equal to them): their held events are cleared and counted as pushed. A windowed key's next window starts
// now (reopen), a daily key's has none until its next event.
func (s *Store) FlushBundles(ctx context.Context, now time.Time, keyPrefix string, reopen time.Duration, limit int) ([]PushBundle, error) {
	var out []PushBundle
	err := s.InTx(ctx, func(tx *Tx) error {
		bs, err := many[PushBundle](tx.Query(ctx, `SELECT `+bundleCols+` FROM push_bundles
			WHERE pending_count > 0 AND window_until <= $1 AND (bundle_key = $2 OR bundle_key LIKE $2 || ':%')
			ORDER BY window_until LIMIT $3 FOR UPDATE SKIP LOCKED`, now, keyPrefix, limit))
		if err != nil {
			return err
		}
		out = bs
		for _, b := range bs {
			var next *time.Time
			if reopen > 0 {
				t := now.Add(reopen)
				next = &t
			}
			if _, err := tx.Exec(ctx, `UPDATE push_bundles SET pending_count = 0, pushed_count = pushed_count + 1, last_sent_at = $3,
				window_until = $4, actor_ids = CASE WHEN $5 THEN '{}'::uuid[] ELSE actor_ids END
				WHERE user_id = $1 AND bundle_key = $2`, b.UserID, b.Key, now, next, reopen > 0); err != nil {
				return err
			}
		}
		return nil
	})
	return out, err
}

// PruneBundles deletes social push bundles without held events that saw nothing for 30 days.
func (s *Store) PruneBundles(ctx context.Context, now time.Time, keys []string) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM push_bundles WHERE pending_count = 0 AND COALESCE(last_event_at, last_sent_at, '-infinity') < $1
		AND (bundle_key = ANY($2) OR split_part(bundle_key, ':', 1) = ANY($2))`, now.Add(-30*24*time.Hour), keys)
	return err
}

// SetFeedMute stores a member's mute for a feed (FeedMute).
func (tx *Tx) SetFeedMute(ctx context.Context, feedID, userID uuid.UUID, mute string) error {
	_, err := tx.Exec(ctx, `UPDATE feed_members SET mute = $3 WHERE feed_id = $1 AND user_id = $2`, feedID, userID, mute)
	return err
}

// FeedMutes maps users to their mute of the feed (members only).
func (s *Store) FeedMutes(ctx context.Context, q db.DBTX, feedID uuid.UUID, users []uuid.UUID) (map[uuid.UUID]string, error) {
	rows, err := q.Query(ctx, `SELECT user_id, mute FROM feed_members WHERE feed_id = $1 AND user_id = ANY($2)`, feedID, users)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[uuid.UUID]string{}
	for rows.Next() {
		var id uuid.UUID
		var m string
		if err := rows.Scan(&id, &m); err != nil {
			return nil, err
		}
		out[id] = m
	}
	return out, rows.Err()
}
