package store

// Chats (docs/spec/family-social.md, README §5.19): contacts, conversations and their members, messages, read
// state and the badge. Family and circle conversations and their members are written by the database triggers
// of migration 00023 only; this file writes direct and group members, and every member's own state (read
// position, mute, archive) in any conversation.

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// ---------------------------------------------------------------- contacts

// contactsCTE lists the people $1 shares a live feed (any role) or a live frame with.
const contactsCTE = `WITH contacts AS (
		SELECT fm2.user_id FROM feed_members fm1
		JOIN feed_members fm2 ON fm2.feed_id = fm1.feed_id AND fm2.user_id <> fm1.user_id
		JOIN feeds f ON f.id = fm1.feed_id AND f.deleted_at IS NULL
		WHERE fm1.user_id = $1
		UNION
		SELECT m2.user_id FROM memberships m1
		JOIN memberships m2 ON m2.frame_id = m1.frame_id AND m2.user_id <> m1.user_id
		JOIN frames fr ON fr.id = m1.frame_id AND fr.deleted_at IS NULL
		WHERE m1.user_id = $1
	)`

// Contact is a person the user shares a family or a frame with.
type Contact struct {
	UserID      uuid.UUID `db:"id"`
	DisplayName string    `db:"display_name"`
	AvatarKey   *string   `db:"avatar_key"`
}

// Contacts lists the user's contacts with live accounts, by display name.
func (s *Store) Contacts(ctx context.Context, userID uuid.UUID) ([]Contact, error) {
	return many[Contact](s.Pool.Query(ctx, contactsCTE+`
		SELECT u.id, u.display_name, u.avatar_key FROM contacts c JOIN users u ON u.id = c.user_id
		WHERE u.deleted_at IS NULL ORDER BY lower(u.display_name), u.id`, userID))
}

// AreContacts reports whether a and b share a live feed or frame and b's account is live.
func (s *Store) AreContacts(ctx context.Context, q db.DBTX, a, b uuid.UUID) (bool, error) {
	var ok bool
	err := q.QueryRow(ctx, contactsCTE+`
		SELECT EXISTS (SELECT 1 FROM contacts c JOIN users u ON u.id = c.user_id AND u.deleted_at IS NULL WHERE c.user_id = $2)`,
		a, b).Scan(&ok)
	return ok, err
}

// NonContacts returns those of others who are not contacts of userID (or whose account is gone).
func (s *Store) NonContacts(ctx context.Context, q db.DBTX, userID uuid.UUID, others []uuid.UUID) ([]uuid.UUID, error) {
	return uuids(q.Query(ctx, contactsCTE+`
		SELECT o FROM unnest($2::uuid[]) AS o
		WHERE NOT EXISTS (SELECT 1 FROM contacts c JOIN users u ON u.id = c.user_id AND u.deleted_at IS NULL WHERE c.user_id = o)`,
		userID, others))
}

// NamedID is an id with a name (shared feeds and frames of two people).
type NamedID struct {
	ID   uuid.UUID `db:"id"`
	Name string    `db:"name"`
}

// SharedFeeds lists the live feeds a and b are both members of.
func (s *Store) SharedFeeds(ctx context.Context, a, b uuid.UUID) ([]NamedID, error) {
	return many[NamedID](s.Pool.Query(ctx, `SELECT f.id, f.name FROM feeds f
		JOIN feed_members x ON x.feed_id = f.id AND x.user_id = $1
		JOIN feed_members y ON y.feed_id = f.id AND y.user_id = $2
		WHERE f.deleted_at IS NULL ORDER BY lower(f.name), f.id`, a, b))
}

// SharedFrames lists the live frames a and b are both members of.
func (s *Store) SharedFrames(ctx context.Context, a, b uuid.UUID) ([]NamedID, error) {
	return many[NamedID](s.Pool.Query(ctx, `SELECT f.id, f.name FROM frames f
		JOIN memberships x ON x.frame_id = f.id AND x.user_id = $1
		JOIN memberships y ON y.frame_id = f.id AND y.user_id = $2
		WHERE f.deleted_at IS NULL ORDER BY lower(f.name), f.id`, a, b))
}

// ---------------------------------------------------------------- blocks

// HasBlocked reports whether blocker blocked blocked.
func (s *Store) HasBlocked(ctx context.Context, q db.DBTX, blocker, blocked uuid.UUID) (bool, error) {
	var ok bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM blocks WHERE user_id = $1 AND blocked_user_id = $2)`, blocker, blocked).Scan(&ok)
	return ok, err
}

// BlockedBy returns those of users who blocked author.
func (s *Store) BlockedBy(ctx context.Context, q db.DBTX, author uuid.UUID, users []uuid.UUID) (map[uuid.UUID]bool, error) {
	ids, err := uuids(q.Query(ctx, `SELECT user_id FROM blocks WHERE blocked_user_id = $1 AND user_id = ANY($2)`, author, users))
	return set(ids), err
}

// BlockedEither returns those of users with a block in either direction with person.
func (s *Store) BlockedEither(ctx context.Context, q db.DBTX, person uuid.UUID, users []uuid.UUID) (map[uuid.UUID]bool, error) {
	ids, err := uuids(q.Query(ctx, `
		SELECT user_id FROM blocks WHERE blocked_user_id = $1 AND user_id = ANY($2)
		UNION SELECT blocked_user_id FROM blocks WHERE user_id = $1 AND blocked_user_id = ANY($2)`, person, users))
	return set(ids), err
}

// BlockedByUser lists everyone userID blocked.
func (s *Store) BlockedByUser(ctx context.Context, q db.DBTX, userID uuid.UUID) (map[uuid.UUID]bool, error) {
	ids, err := uuids(q.Query(ctx, `SELECT blocked_user_id FROM blocks WHERE user_id = $1`, userID))
	return set(ids), err
}

// BlockRow is one of a user's blocks.
type BlockRow struct {
	UserID      uuid.UUID `db:"blocked_user_id"`
	DisplayName string    `db:"display_name"`
	AvatarKey   *string   `db:"avatar_key"`
	Deleted     bool      `db:"deleted"`
	CreatedAt   time.Time `db:"created_at"`
}

func (s *Store) ListBlocks(ctx context.Context, userID uuid.UUID) ([]BlockRow, error) {
	return many[BlockRow](s.Pool.Query(ctx, `SELECT b.blocked_user_id, u.display_name, u.avatar_key, u.deleted_at IS NOT NULL AS deleted, b.created_at
		FROM blocks b JOIN users u ON u.id = b.blocked_user_id WHERE b.user_id = $1 ORDER BY b.created_at DESC`, userID))
}

// Block is idempotent.
func (s *Store) Block(ctx context.Context, userID, blocked uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `INSERT INTO blocks (user_id, blocked_user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`, userID, blocked)
	return err
}

func (s *Store) Unblock(ctx context.Context, userID, blocked uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM blocks WHERE user_id = $1 AND blocked_user_id = $2`, userID, blocked)
	return err
}

// ---------------------------------------------------------------- conversations

// Conversation is a chat with what its kind takes from its family or circle.
type Conversation struct {
	ID            uuid.UUID  `db:"id"`
	Kind          string     `db:"kind"`
	FeedID        *uuid.UUID `db:"feed_id"`
	CircleID      *uuid.UUID `db:"circle_id"`
	Name          *string    `db:"name"`
	PhotoKey      *string    `db:"photo_key"`
	CreatedBy     *uuid.UUID `db:"created_by"`
	CreatedAt     time.Time  `db:"created_at"`
	LastMessageID *uuid.UUID `db:"last_message_id"`
	LastMessageAt *time.Time `db:"last_message_at"`
	ArchivedAt    *time.Time `db:"archived_at"`
	FeedName      *string    `db:"feed_name"`
	CircleName    *string    `db:"circle_name"`
	CoverKey      *string    `db:"cover_key"` // family: the feed's cover thumbnail
	MemberCount   int        `db:"member_count"`
}

const conversationCols = `c.id, c.kind, c.feed_id, c.circle_id, c.name, c.photo_key, c.created_by, c.created_at, c.last_message_id,
	c.last_message_at, c.archived_at, f.name AS feed_name, ci.name AS circle_name,
	(SELECT coalesce(i.thumb512_key, i.thumb256_key) FROM items i WHERE i.id = f.cover_item_id) AS cover_key,
	(SELECT count(*)::int FROM conversation_members x WHERE x.conversation_id = c.id) AS member_count`

const conversationJoins = ` LEFT JOIN feeds f ON f.id = c.feed_id LEFT JOIN circles ci ON ci.id = c.circle_id`

// ConversationView is a conversation as one member sees it.
type ConversationView struct {
	Conversation
	Role              string     `db:"role"`
	JoinedAt          time.Time  `db:"joined_at"`
	VisibleFrom       *time.Time `db:"visible_from"`
	LastReadMessageID *uuid.UUID `db:"last_read_message_id"`
	MutedUntil        *time.Time `db:"muted_until"`  // null with MutedAlways
	MutedAlways       bool       `db:"muted_always"` // muted_until = infinity
	Archived          bool       `db:"member_archived"`
	FeedMute          string     `db:"feed_mute"` // family and circle chats: the member's family mute
	UnreadCount       int        `db:"unread_count"`
	UnreadMentions    int        `db:"unread_mentions"`
}

// Muted: muted for a while, always, or through the family's chat mute.
func (v *ConversationView) Muted(now time.Time) bool {
	if v.MutedAlways || (v.MutedUntil != nil && v.MutedUntil.After(now)) {
		return true
	}
	return (v.Kind == "family" || v.Kind == "circle") && (v.FeedMute == "chat" || v.FeedMute == "all")
}

// unreadSQL counts person messages after the member's read position (else since they joined), not their own, not
// deleted, not from people they blocked. $1 is the member. extra narrows further (mentions).
func unreadSQL(extra string) string {
	return `(SELECT count(*)::int FROM messages m WHERE m.conversation_id = c.id AND m.kind = 'person' AND m.deleted_at IS NULL
		AND m.author_user_id IS DISTINCT FROM $1
		AND (cm.last_read_message_id IS NULL OR m.id > cm.last_read_message_id)
		AND (cm.last_read_message_id IS NOT NULL OR m.created_at >= cm.joined_at)
		AND (cm.visible_from IS NULL OR m.created_at >= cm.visible_from)
		AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = $1 AND b.blocked_user_id = m.author_user_id)` + extra + `)`
}

var conversationViewSelect = `SELECT ` + conversationCols + `, cm.role, cm.joined_at, cm.visible_from, cm.last_read_message_id,
	CASE WHEN cm.muted_until = 'infinity' THEN NULL ELSE cm.muted_until END AS muted_until,
	coalesce(cm.muted_until = 'infinity', false) AS muted_always, cm.archived AS member_archived,
	coalesce((SELECT fm.mute FROM feed_members fm WHERE fm.feed_id = c.feed_id AND fm.user_id = $1), 'none') AS feed_mute,
	` + unreadSQL("") + ` AS unread_count,
	` + unreadSQL(` AND EXISTS (SELECT 1 FROM message_mentions mm WHERE mm.message_id = m.id AND mm.user_id = $1)`) + ` AS unread_mentions
	FROM conversation_members cm JOIN conversations c ON c.id = cm.conversation_id` + conversationJoins + `
	WHERE cm.user_id = $1`

// ConversationsOf lists the member's conversations, latest activity first. Without archived, conversations the
// member archived and archived families and circles are left out.
func (s *Store) ConversationsOf(ctx context.Context, userID uuid.UUID, archived bool) ([]ConversationView, error) {
	filter := ` AND NOT cm.archived AND c.archived_at IS NULL`
	if archived {
		filter = ` AND (cm.archived OR c.archived_at IS NOT NULL)`
	}
	return many[ConversationView](s.Pool.Query(ctx, conversationViewSelect+filter+`
		ORDER BY coalesce(c.last_message_at, c.created_at) DESC, c.id DESC`, userID))
}

// ConversationOf is one conversation as a member sees it; ErrNotFound for non-members.
func (s *Store) ConversationOf(ctx context.Context, q db.DBTX, userID, convID uuid.UUID) (*ConversationView, error) {
	return one[ConversationView](q.Query(ctx, conversationViewSelect+` AND c.id = $2`, userID, convID))
}

// GetConversation reads a conversation without a member's view.
func (s *Store) GetConversation(ctx context.Context, q db.DBTX, id uuid.UUID) (*Conversation, error) {
	return one[Conversation](q.Query(ctx, `SELECT `+conversationCols+` FROM conversations c`+conversationJoins+` WHERE c.id = $1`, id))
}

// LockConversation locks a direct or group conversation's row (member changes).
func (tx *Tx) LockConversation(ctx context.Context, id uuid.UUID) error {
	_, err := tx.Exec(ctx, `SELECT 1 FROM conversations WHERE id = $1 FOR UPDATE`, id)
	return err
}

// DirectConversation finds the direct conversation of a pair.
func (s *Store) DirectConversation(ctx context.Context, q db.DBTX, key string) (uuid.UUID, error) {
	var id uuid.UUID
	err := q.QueryRow(ctx, `SELECT id FROM conversations WHERE direct_key = $1`, key).Scan(&id)
	if db.NotFound(err) {
		return uuid.Nil, ErrNotFound
	}
	return id, err
}

// CreateConversation creates a direct or group conversation.
func (tx *Tx) CreateConversation(ctx context.Context, id uuid.UUID, kind string, directKey, name *string, createdBy uuid.UUID) error {
	_, err := tx.Exec(ctx, `INSERT INTO conversations (id, kind, direct_key, name, created_by) VALUES ($1, $2, $3, $4, $5)`,
		id, kind, directKey, name, createdBy)
	return err
}

// AddConversationMember adds a direct or group member (no-op when already a member); fromNow limits the
// history they see to messages from now on (groups). Reports whether a row was added.
func (tx *Tx) AddConversationMember(ctx context.Context, convID, userID uuid.UUID, role string, addedBy *uuid.UUID, fromNow bool) (bool, error) {
	tag, err := tx.Exec(ctx, `INSERT INTO conversation_members (conversation_id, user_id, role, added_by, joined_at, visible_from)
		VALUES ($1, $2, $3, $4, clock_timestamp(), CASE WHEN $5 THEN clock_timestamp() END) ON CONFLICT DO NOTHING`, convID, userID, role, addedBy, fromNow)
	return tag.RowsAffected() == 1, err
}

// RemoveConversationMember removes a group member; when no admin is left the longest-standing member becomes
// admin. Reports whether the row existed and who was promoted.
func (tx *Tx) RemoveConversationMember(ctx context.Context, convID, userID uuid.UUID) (bool, *uuid.UUID, error) {
	tag, err := tx.Exec(ctx, `DELETE FROM conversation_members WHERE conversation_id = $1 AND user_id = $2`, convID, userID)
	if err != nil || tag.RowsAffected() == 0 {
		return false, nil, err
	}
	p, err := tx.ensureAdmin(ctx, convID)
	return true, p, err
}

func (tx *Tx) ensureAdmin(ctx context.Context, convID uuid.UUID) (*uuid.UUID, error) {
	var promoted uuid.UUID
	err := tx.QueryRow(ctx, `UPDATE conversation_members SET role = 'admin'
		WHERE conversation_id = $1 AND NOT EXISTS (SELECT 1 FROM conversation_members WHERE conversation_id = $1 AND role = 'admin')
		AND user_id = (SELECT user_id FROM conversation_members WHERE conversation_id = $1 ORDER BY joined_at, user_id LIMIT 1)
		RETURNING user_id`, convID).Scan(&promoted)
	if db.NotFound(err) {
		return nil, nil
	}
	return &promoted, err
}

// SetConversationRole changes a group member's role; false when they aren't a member.
func (tx *Tx) SetConversationRole(ctx context.Context, convID, userID uuid.UUID, role string) (bool, error) {
	tag, err := tx.Exec(ctx, `UPDATE conversation_members SET role = $3 WHERE conversation_id = $1 AND user_id = $2`, convID, userID, role)
	return tag.RowsAffected() == 1, err
}

// CountConversationAdmins counts a group's admins.
func (s *Store) CountConversationAdmins(ctx context.Context, q db.DBTX, convID uuid.UUID) (int, error) {
	var n int
	err := q.QueryRow(ctx, `SELECT count(*) FROM conversation_members WHERE conversation_id = $1 AND role = 'admin'`, convID).Scan(&n)
	return n, err
}

// RenameConversation sets a group's name.
func (tx *Tx) RenameConversation(ctx context.Context, convID uuid.UUID, name string) error {
	_, err := tx.Exec(ctx, `UPDATE conversations SET name = $2, updated_at = now() WHERE id = $1`, convID, name)
	return err
}

// SetConversationPhoto sets or clears a group's photo and returns the old key.
func (s *Store) SetConversationPhoto(ctx context.Context, convID uuid.UUID, key *string) (*string, error) {
	var old *string
	err := s.Pool.QueryRow(ctx, `UPDATE conversations c SET photo_key = $2, updated_at = now()
		FROM (SELECT photo_key FROM conversations WHERE id = $1 FOR UPDATE) o WHERE c.id = $1 RETURNING o.photo_key`, convID, key).Scan(&old)
	return old, err
}

// MemberPrefs is a member's own mute and archive change.
type MemberPrefs struct {
	SetMute     bool
	MutedUntil  *time.Time // nil with SetMute: off
	MuteAlways  bool
	SetArchived bool
	Archived    bool
}

func (s *Store) SetConversationPrefs(ctx context.Context, convID, userID uuid.UUID, p MemberPrefs) error {
	_, err := s.Pool.Exec(ctx, `UPDATE conversation_members SET
		muted_until = CASE WHEN NOT $3 THEN muted_until WHEN $5 THEN 'infinity'::timestamptz ELSE $4 END,
		archived = CASE WHEN $6 THEN $7 ELSE archived END
		WHERE conversation_id = $1 AND user_id = $2`, convID, userID, p.SetMute, p.MutedUntil, p.MuteAlways, p.SetArchived, p.Archived)
	return err
}

// ConversationMember is a member as listed in a conversation.
type ConversationMember struct {
	ConversationID    uuid.UUID  `db:"conversation_id"`
	UserID            uuid.UUID  `db:"user_id"`
	Role              string     `db:"role"`
	JoinedAt          time.Time  `db:"joined_at"`
	VisibleFrom       *time.Time `db:"visible_from"`
	LastReadMessageID *uuid.UUID `db:"last_read_message_id"`
	MutedUntil        *time.Time `db:"muted_until"`
	MutedAlways       bool       `db:"muted_always"`
	DisplayName       string     `db:"display_name"`
	AvatarKey         *string    `db:"avatar_key"`
	Deleted           bool       `db:"deleted"`
	ShowReadReceipts  bool       `db:"show_read_receipts"`
	FeedMute          string     `db:"feed_mute"`
}

// Muted: as ConversationView.Muted, for kind.
func (m *ConversationMember) Muted(kind string, now time.Time) bool {
	if m.MutedAlways || (m.MutedUntil != nil && m.MutedUntil.After(now)) {
		return true
	}
	return (kind == "family" || kind == "circle") && (m.FeedMute == "chat" || m.FeedMute == "all")
}

// ConversationMembers lists the members of conversations, longest-standing first.
func (s *Store) ConversationMembers(ctx context.Context, q db.DBTX, convIDs []uuid.UUID) (map[uuid.UUID][]ConversationMember, error) {
	rows, err := many[ConversationMember](q.Query(ctx, `
		SELECT cm.conversation_id, cm.user_id, cm.role, cm.joined_at, cm.visible_from, cm.last_read_message_id,
			CASE WHEN cm.muted_until = 'infinity' THEN NULL ELSE cm.muted_until END AS muted_until,
			coalesce(cm.muted_until = 'infinity', false) AS muted_always,
			u.display_name, u.avatar_key, u.deleted_at IS NOT NULL AS deleted, u.show_read_receipts,
			coalesce((SELECT fm.mute FROM feed_members fm WHERE fm.feed_id = c.feed_id AND fm.user_id = cm.user_id), 'none') AS feed_mute
		FROM conversation_members cm JOIN users u ON u.id = cm.user_id JOIN conversations c ON c.id = cm.conversation_id
		WHERE cm.conversation_id = ANY($1) ORDER BY cm.joined_at, cm.user_id`, convIDs))
	if err != nil {
		return nil, err
	}
	out := map[uuid.UUID][]ConversationMember{}
	for _, r := range rows {
		out[r.ConversationID] = append(out[r.ConversationID], r)
	}
	return out, nil
}

// MarkRead moves the member's read position forward to messageID (never back); reports whether it moved.
func (s *Store) MarkRead(ctx context.Context, convID, userID, messageID uuid.UUID) (bool, error) {
	tag, err := s.Pool.Exec(ctx, `UPDATE conversation_members SET last_read_message_id = $3, last_read_at = now()
		WHERE conversation_id = $1 AND user_id = $2 AND (last_read_message_id IS NULL OR last_read_message_id < $3)`,
		convID, userID, messageID)
	return tag.RowsAffected() == 1, err
}

// ---------------------------------------------------------------- messages

// Message is a stored message with its author.
type Message struct {
	ID              uuid.UUID       `db:"id"`
	ConversationID  uuid.UUID       `db:"conversation_id"`
	Kind            string          `db:"kind"`
	AuthorUserID    *uuid.UUID      `db:"author_user_id"`
	ClientID        *string         `db:"client_id"`
	Body            *string         `db:"body"`
	BodyEnc         []byte          `db:"body_enc"`
	Mentions        json.RawMessage `db:"mentions"`
	ReplyToID       *uuid.UUID      `db:"reply_to_id"`
	SharedPostID    *uuid.UUID      `db:"shared_post_id"`
	VoiceItemID     *uuid.UUID      `db:"voice_item_id"`
	LinkPreviewID   *uuid.UUID      `db:"link_preview_id"`
	SystemType      *string         `db:"system_type"`
	SystemData      []byte          `db:"system_data"`
	CreatedAt       time.Time       `db:"created_at"`
	EditedAt        *time.Time      `db:"edited_at"`
	DeletedAt       *time.Time      `db:"deleted_at"`
	RemovedBy       *uuid.UUID      `db:"removed_by"`
	AuthorName      *string         `db:"author_name"`
	AuthorAvatarKey *string         `db:"author_avatar_key"`
	AuthorDeleted   bool            `db:"author_deleted"`
}

const messageSelect = `SELECT m.id, m.conversation_id, m.kind, m.author_user_id, m.client_id, m.body, m.body_enc, m.mentions,
	m.reply_to_id, m.shared_post_id, m.voice_item_id, m.link_preview_id, m.system_type, m.system_data, m.created_at,
	m.edited_at, m.deleted_at, m.removed_by, u.display_name AS author_name, u.avatar_key AS author_avatar_key,
	coalesce(u.deleted_at IS NOT NULL, false) AS author_deleted
	FROM messages m LEFT JOIN users u ON u.id = m.author_user_id`

// MessagesByID reads messages by id (any conversation).
func (s *Store) MessagesByID(ctx context.Context, q db.DBTX, ids []uuid.UUID) (map[uuid.UUID]Message, error) {
	rows, err := many[Message](q.Query(ctx, messageSelect+` WHERE m.id = ANY($1)`, ids))
	if err != nil {
		return nil, err
	}
	out := make(map[uuid.UUID]Message, len(rows))
	for _, r := range rows {
		out[r.ID] = r
	}
	return out, nil
}

// GetMessage reads one message of a conversation.
func (s *Store) GetMessage(ctx context.Context, q db.DBTX, convID, id uuid.UUID) (*Message, error) {
	return one[Message](q.Query(ctx, messageSelect+` WHERE m.id = $1 AND m.conversation_id = $2`, id, convID))
}

// MessagePageQuery selects a page of a conversation for a viewer.
type MessagePageQuery struct {
	ConversationID uuid.UUID
	Viewer         uuid.UUID
	VisibleFrom    *time.Time
	Before, After  *uuid.UUID
	Limit          int
}

// MessagePage returns up to q.Limit messages oldest first, and whether there are more before and after them.
// Messages before the viewer's visible_from and from people they blocked are left out.
func (s *Store) MessagePage(ctx context.Context, p MessagePageQuery) ([]Message, bool, bool, error) {
	filter := ` WHERE m.conversation_id = $1 AND ($2::timestamptz IS NULL OR m.created_at >= $2)
		AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = $3 AND b.blocked_user_id = m.author_user_id)`
	var rows []Message
	var err error
	switch {
	case p.After != nil:
		rows, err = many[Message](s.Pool.Query(ctx, messageSelect+filter+` AND m.id > $4 ORDER BY m.id LIMIT $5`,
			p.ConversationID, p.VisibleFrom, p.Viewer, *p.After, p.Limit+1))
	case p.Before != nil:
		rows, err = many[Message](s.Pool.Query(ctx, messageSelect+filter+` AND m.id < $4 ORDER BY m.id DESC LIMIT $5`,
			p.ConversationID, p.VisibleFrom, p.Viewer, *p.Before, p.Limit+1))
	default:
		rows, err = many[Message](s.Pool.Query(ctx, messageSelect+filter+` ORDER BY m.id DESC LIMIT $4`,
			p.ConversationID, p.VisibleFrom, p.Viewer, p.Limit+1))
	}
	if err != nil {
		return nil, false, false, err
	}
	more := len(rows) > p.Limit
	if more {
		rows = rows[:p.Limit]
	}
	if p.After == nil {
		for i, j := 0, len(rows)-1; i < j; i, j = i+1, j-1 {
			rows[i], rows[j] = rows[j], rows[i]
		}
	}
	if len(rows) == 0 {
		return rows, p.After != nil, p.Before != nil, nil
	}
	// The other side: is there anything visible beyond the page?
	exists := func(cmp string, id uuid.UUID) (bool, error) {
		var ok bool
		err := s.Pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM messages m`+filter+` AND m.id `+cmp+` $4)`,
			p.ConversationID, p.VisibleFrom, p.Viewer, id).Scan(&ok)
		return ok, err
	}
	var before, after bool
	if p.After != nil {
		after = more
		before = true
	} else {
		before = more
		if p.Before != nil {
			if after, err = exists(">", rows[len(rows)-1].ID); err != nil {
				return nil, false, false, err
			}
		}
	}
	if p.After != nil {
		if before, err = exists("<", rows[0].ID); err != nil {
			return nil, false, false, err
		}
	}
	return rows, before, after, nil
}

// NewMessage is a message to store.
type NewMessage struct {
	ID             uuid.UUID
	ConversationID uuid.UUID
	Kind           string
	AuthorUserID   *uuid.UUID
	ClientID       *string
	Body           *string
	BodyEnc        []byte
	Mentions       json.RawMessage
	MentionIDs     []uuid.UUID
	ReplyToID      *uuid.UUID
	SharedPostID   *uuid.UUID
	VoiceItemID    *uuid.UUID
	LinkPreviewID  *uuid.UUID
	SystemType     *string
	SystemData     []byte
}

// CreateMessage stores a message, its mentions, bumps the conversation's activity and un-archives it for every
// member.
func (tx *Tx) CreateMessage(ctx context.Context, m NewMessage) error {
	mentions := m.Mentions
	if len(mentions) == 0 {
		mentions = json.RawMessage(`[]`)
	}
	if _, err := tx.Exec(ctx, `INSERT INTO messages (id, conversation_id, kind, author_user_id, client_id, body, body_enc, mentions,
			reply_to_id, shared_post_id, voice_item_id, link_preview_id, system_type, system_data, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, clock_timestamp())`,
		m.ID, m.ConversationID, m.Kind, m.AuthorUserID, m.ClientID, m.Body, m.BodyEnc, []byte(mentions), m.ReplyToID,
		m.SharedPostID, m.VoiceItemID, m.LinkPreviewID, m.SystemType, m.SystemData); err != nil {
		return err
	}
	if err := tx.setMentions(ctx, m.ID, m.MentionIDs); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE conversations SET last_message_id = $2, last_message_at = clock_timestamp(), updated_at = now() WHERE id = $1`,
		m.ConversationID, m.ID); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `UPDATE conversation_members SET archived = false WHERE conversation_id = $1 AND archived`, m.ConversationID)
	return err
}

func (tx *Tx) setMentions(ctx context.Context, messageID uuid.UUID, users []uuid.UUID) error {
	if _, err := tx.Exec(ctx, `DELETE FROM message_mentions WHERE message_id = $1`, messageID); err != nil {
		return err
	}
	if len(users) == 0 {
		return nil
	}
	_, err := tx.Exec(ctx, `INSERT INTO message_mentions (message_id, user_id) SELECT $1, u FROM unnest($2::uuid[]) AS u ON CONFLICT DO NOTHING`,
		messageID, users)
	return err
}

// MessageByClientID finds an author's earlier message with the same client id.
func (s *Store) MessageByClientID(ctx context.Context, q db.DBTX, convID, author uuid.UUID, clientID string) (uuid.UUID, error) {
	var id uuid.UUID
	err := q.QueryRow(ctx, `SELECT id FROM messages WHERE conversation_id = $1 AND author_user_id = $2 AND client_id = $3`,
		convID, author, clientID).Scan(&id)
	if db.NotFound(err) {
		return uuid.Nil, ErrNotFound
	}
	return id, err
}

// AddMessageItem links a photo or video to a message.
func (tx *Tx) AddMessageItem(ctx context.Context, messageID, itemID uuid.UUID, pos int) error {
	_, err := tx.Exec(ctx, `INSERT INTO message_items (message_id, item_id, position) VALUES ($1, $2, $3)`, messageID, itemID, pos)
	return err
}

// SetItemConversation marks items as uploaded in a chat (their media is served to its members).
func (tx *Tx) SetItemConversation(ctx context.Context, itemIDs []uuid.UUID, convID *uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE items SET conversation_id = $2 WHERE id = ANY($1)`, itemIDs, convID)
	return err
}

// EditMessage replaces a message's text and mentions.
func (tx *Tx) EditMessage(ctx context.Context, id uuid.UUID, body *string, bodyEnc []byte, mentions json.RawMessage, mentionIDs []uuid.UUID, previewID *uuid.UUID) error {
	if len(mentions) == 0 {
		mentions = json.RawMessage(`[]`)
	}
	if _, err := tx.Exec(ctx, `UPDATE messages SET body = $2, body_enc = $3, mentions = $4, link_preview_id = $5, edited_at = now() WHERE id = $1`,
		id, body, bodyEnc, []byte(mentions), previewID); err != nil {
		return err
	}
	return tx.setMentions(ctx, id, mentionIDs)
}

// DeleteMessage turns a message into a tombstone: text, mentions, preview, voice, items and reactions go;
// items that were never put on a frame are no longer shared with the conversation. removedBy marks a
// moderator's removal. Reports false when it already was deleted.
func (tx *Tx) DeleteMessage(ctx context.Context, id uuid.UUID, removedBy *uuid.UUID) (bool, error) {
	var voice *uuid.UUID
	var conv uuid.UUID
	err := tx.QueryRow(ctx, `UPDATE messages m SET body = NULL, body_enc = NULL, mentions = '[]', link_preview_id = NULL,
			voice_item_id = NULL, shared_post_id = NULL, deleted_at = now(), removed_by = $2
		FROM (SELECT voice_item_id FROM messages WHERE id = $1 FOR UPDATE) o
		WHERE m.id = $1 AND m.deleted_at IS NULL RETURNING o.voice_item_id, m.conversation_id`, id, removedBy).Scan(&voice, &conv)
	if db.NotFound(err) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	items, err := uuids(tx.Query(ctx, `DELETE FROM message_items WHERE message_id = $1 RETURNING item_id`, id))
	if err != nil {
		return false, err
	}
	if voice != nil {
		items = append(items, *voice)
	}
	if _, err := tx.Exec(ctx, `UPDATE items SET conversation_id = NULL WHERE id = ANY($1)
		AND NOT EXISTS (SELECT 1 FROM message_items mi WHERE mi.item_id = items.id)`, items); err != nil {
		return false, err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM message_mentions WHERE message_id = $1`, id); err != nil {
		return false, err
	}
	_, err = tx.Exec(ctx, `DELETE FROM reactions WHERE target_kind = 'message' AND target_id = $1 AND scope_id = $2`, id, conv)
	return true, err
}

// MessageItemRow is a photo or video of a message.
type MessageItemRow struct {
	MessageID uuid.UUID `db:"message_id"`
	Position  int       `db:"position"`
	ItemRow
}

// MessageItems lists the photos and videos of messages, in order.
func (s *Store) MessageItems(ctx context.Context, q db.DBTX, messageIDs []uuid.UUID) (map[uuid.UUID][]ItemRow, error) {
	rows, err := many[MessageItemRow](q.Query(ctx, `SELECT mi.message_id, mi.position, `+itemRowCols+`
		FROM message_items mi JOIN items i ON i.id = mi.item_id`+itemJoins+`
		WHERE mi.message_id = ANY($1) ORDER BY mi.message_id, mi.position`, messageIDs))
	if err != nil {
		return nil, err
	}
	out := map[uuid.UUID][]ItemRow{}
	for _, r := range rows {
		out[r.MessageID] = append(out[r.MessageID], r.ItemRow)
	}
	return out, nil
}

// VoiceRow is a voice message's recording.
type VoiceRow struct {
	ItemID     uuid.UUID `db:"item_id"`
	Status     string    `db:"status"`
	DurationMS *int      `db:"duration_ms"`
	Waveform   []int16   `db:"waveform"`
}

// VoiceItems reads recordings by item id.
func (s *Store) VoiceItems(ctx context.Context, q db.DBTX, itemIDs []uuid.UUID) (map[uuid.UUID]VoiceRow, error) {
	rows, err := many[VoiceRow](q.Query(ctx, `SELECT i.id AS item_id, m.status, m.duration_ms, m.waveform
		FROM items i JOIN media m ON m.id = i.media_id WHERE i.id = ANY($1)`, itemIDs))
	if err != nil {
		return nil, err
	}
	out := make(map[uuid.UUID]VoiceRow, len(rows))
	for _, r := range rows {
		out[r.ItemID] = r
	}
	return out, nil
}

// SetMediaWaveform stores an audio media's 64-value waveform (audio has no width or height).
func (tx *Tx) SetMediaWaveform(ctx context.Context, mediaID uuid.UUID, waveform []int16) error {
	_, err := tx.Exec(ctx, `UPDATE media SET waveform = $2, width = NULL, height = NULL WHERE id = $1`, mediaID, waveform)
	return err
}

// MessagesOfMedia lists the messages (and their conversations) whose photos, videos or recording use a media.
func (s *Store) MessagesOfMedia(ctx context.Context, mediaID uuid.UUID) ([]uuid.UUID, error) {
	return uuids(s.Pool.Query(ctx, `
		SELECT mi.message_id FROM message_items mi JOIN items i ON i.id = mi.item_id WHERE i.media_id = $1 OR i.motion_media_id = $1
		UNION SELECT m.id FROM messages m JOIN items i ON i.id = m.voice_item_id WHERE i.media_id = $1`, mediaID))
}

// MessagesOfItem lists the messages that hold an item.
func (s *Store) MessagesOfItem(ctx context.Context, itemID uuid.UUID) ([]uuid.UUID, error) {
	return uuids(s.Pool.Query(ctx, `SELECT message_id FROM message_items WHERE item_id = $1`, itemID))
}

// FrameActivity is a frame's reaction or replies on a placement of a message's item.
type FrameActivity struct {
	MessageID uuid.UUID `db:"message_id"`
	FrameID   uuid.UUID `db:"frame_id"`
	FrameName string    `db:"frame_name"`
	ItemID    uuid.UUID `db:"item_id"`
	Reaction  *string   `db:"reaction"`
	Replies   []string  `db:"replies"`
}

// MessageFrameActivity lists frames' reactions and quick replies on live placements of messages' items.
func (s *Store) MessageFrameActivity(ctx context.Context, q db.DBTX, messageIDs []uuid.UUID) (map[uuid.UUID][]FrameActivity, error) {
	rows, err := many[FrameActivity](q.Query(ctx, `
		SELECT x.message_id, x.frame_id, x.frame_name, x.item_id, x.reaction, x.replies FROM (
			SELECT mi.message_id, p.frame_id, f.name AS frame_name, p.item_id, p.reaction, p.received_at, p.id,
				coalesce((SELECT array_agg(r.preset ORDER BY r.at) FROM placement_replies r WHERE r.placement_id = p.id), '{}') AS replies
			FROM message_items mi JOIN placements p ON p.item_id = mi.item_id AND p.removed_at IS NULL
			JOIN frames f ON f.id = p.frame_id AND f.deleted_at IS NULL
			WHERE mi.message_id = ANY($1)) x
		WHERE x.reaction IS NOT NULL OR cardinality(x.replies) > 0
		ORDER BY x.message_id, x.received_at, x.id`, messageIDs))
	if err != nil {
		return nil, err
	}
	out := map[uuid.UUID][]FrameActivity{}
	for _, r := range rows {
		out[r.MessageID] = append(out[r.MessageID], r)
	}
	return out, nil
}

// LinkMessageSend records a "put on a frame" send of a message.
func (tx *Tx) LinkMessageSend(ctx context.Context, messageID, sendID, by uuid.UUID) error {
	_, err := tx.Exec(ctx, `INSERT INTO message_sends (message_id, send_id, created_by) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`,
		messageID, sendID, by)
	return err
}

// MessageReaction is a person's reaction on a message.
type MessageReaction struct {
	MessageID   uuid.UUID `db:"target_id"`
	UserID      uuid.UUID `db:"user_id"`
	Emoji       string    `db:"emoji"`
	CreatedAt   time.Time `db:"created_at"`
	DisplayName string    `db:"display_name"`
	AvatarKey   *string   `db:"avatar_key"`
	Deleted     bool      `db:"deleted"`
}

// MessageReactions lists people's reactions on messages, newest first.
func (s *Store) MessageReactions(ctx context.Context, q db.DBTX, messageIDs []uuid.UUID) (map[uuid.UUID][]MessageReaction, error) {
	rows, err := many[MessageReaction](q.Query(ctx, `SELECT r.target_id, r.user_id, r.emoji, r.created_at, u.display_name, u.avatar_key,
			u.deleted_at IS NOT NULL AS deleted
		FROM reactions r JOIN users u ON u.id = r.user_id
		WHERE r.target_kind = 'message' AND r.target_id = ANY($1) ORDER BY r.created_at DESC, r.user_id`, messageIDs))
	if err != nil {
		return nil, err
	}
	out := map[uuid.UUID][]MessageReaction{}
	for _, r := range rows {
		out[r.MessageID] = append(out[r.MessageID], r)
	}
	return out, nil
}

// ---------------------------------------------------------------- the badge

// BadgeCounts: conversations with unread messages that aren't muted, and threads with unread mentions of or
// replies to the user that aren't muted (README §5.19).
func (s *Store) BadgeCounts(ctx context.Context, userID uuid.UUID) (int, int, error) {
	var convs, threads int
	err := s.Pool.QueryRow(ctx, `
		SELECT count(*)::int FROM conversation_members cm JOIN conversations c ON c.id = cm.conversation_id
		WHERE cm.user_id = $1 AND c.archived_at IS NULL
		AND (cm.muted_until IS NULL OR cm.muted_until <= now())
		AND NOT (c.kind IN ('family', 'circle') AND coalesce((SELECT fm.mute FROM feed_members fm WHERE fm.feed_id = c.feed_id AND fm.user_id = $1), 'none') IN ('chat', 'all'))
		AND EXISTS (SELECT 1 FROM messages m WHERE m.conversation_id = c.id AND m.kind = 'person' AND m.deleted_at IS NULL
			AND m.author_user_id IS DISTINCT FROM $1
			AND (cm.last_read_message_id IS NULL OR m.id > cm.last_read_message_id)
			AND (cm.last_read_message_id IS NOT NULL OR m.created_at >= cm.joined_at)
			AND (cm.visible_from IS NULL OR m.created_at >= cm.visible_from)
			AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = $1 AND b.blocked_user_id = m.author_user_id))`,
		userID).Scan(&convs)
	if err != nil {
		return 0, 0, err
	}
	// Threads: person comments by others after the read position that mention the user or answer their
	// comments, in threads of posts the user still sees (feed membership, the circle, or the frame post's
	// sender and see_library members), not muted (thread mute, or the whole family muted).
	err = s.Pool.QueryRow(ctx, `
		SELECT count(DISTINCT t.post_id)::int FROM comments co
		JOIN threads t ON t.post_id = co.post_id
		LEFT JOIN thread_reads tr ON tr.post_id = t.post_id AND tr.user_id = $1
		WHERE co.kind = 'person' AND co.deleted_at IS NULL AND co.removed_at IS NULL
		AND co.author_user_id IS DISTINCT FROM $1
		AND (tr.read_up_to IS NULL OR co.id > tr.read_up_to)
		AND NOT coalesce(tr.muted, false)
		AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = $1 AND b.blocked_user_id = co.author_user_id)
		AND (EXISTS (SELECT 1 FROM comment_mentions cmn WHERE cmn.comment_id = co.id AND cmn.user_id = $1)
			OR co.quoted_user_id = $1
			OR EXISTS (SELECT 1 FROM comments pc WHERE pc.id = co.parent_id AND pc.author_user_id = $1))
		AND (
			(t.feed_id IS NOT NULL AND EXISTS (SELECT 1 FROM feed_members fm JOIN feeds f ON f.id = fm.feed_id AND f.deleted_at IS NULL
				WHERE fm.feed_id = t.feed_id AND fm.user_id = $1 AND fm.mute <> 'all')
			 AND EXISTS (SELECT 1 FROM feed_posts fp WHERE fp.id = t.feed_post_id AND fp.removed_at IS NULL
				AND (fp.circle_id IS NULL OR fp.poster_user_id = $1
					OR EXISTS (SELECT 1 FROM circle_members cim WHERE cim.circle_id = fp.circle_id AND cim.user_id = $1))))
			OR (t.frame_id IS NOT NULL AND EXISTS (SELECT 1 FROM frame_posts fr JOIN frames f ON f.id = fr.frame_id AND f.deleted_at IS NULL
				WHERE fr.id = t.frame_post_id AND fr.removed_at IS NULL
				AND (fr.sender_user_id = $1 OR EXISTS (SELECT 1 FROM memberships ms WHERE ms.frame_id = fr.frame_id AND ms.user_id = $1
					AND (ms.role IN ('owner', 'admin') OR (ms.permissions->>'see_library')::boolean))))))`,
		userID).Scan(&threads)
	return convs, threads, err
}

// ---------------------------------------------------------------- helpers

func uuids(rows pgx.Rows, err error) ([]uuid.UUID, error) {
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
}

func set(ids []uuid.UUID) map[uuid.UUID]bool {
	out := make(map[uuid.UUID]bool, len(ids))
	for _, id := range ids {
		out[id] = true
	}
	return out
}

// ---------------------------------------------------------------- items and users

// ChatCanSeeItem: the item is a photo, video or recording of a live message the user may see (a member of its
// conversation, the message not before their visible_from, its author not blocked by them).
func (s *Store) ChatCanSeeItem(ctx context.Context, userID, itemID uuid.UUID) (bool, error) {
	var ok bool
	err := s.Pool.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM items i
			JOIN messages m ON m.conversation_id = i.conversation_id AND m.deleted_at IS NULL
				AND (m.voice_item_id = i.id OR EXISTS (SELECT 1 FROM message_items mi WHERE mi.message_id = m.id AND mi.item_id = i.id))
			JOIN conversation_members cm ON cm.conversation_id = m.conversation_id AND cm.user_id = $1
			WHERE i.id = $2 AND (cm.visible_from IS NULL OR m.created_at >= cm.visible_from)
			AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = $1 AND b.blocked_user_id = m.author_user_id))`,
		userID, itemID).Scan(&ok)
	return ok, err
}

// SetShowReadReceipts sets the user's read receipts choice.
func (s *Store) SetShowReadReceipts(ctx context.Context, userID uuid.UUID, show bool) (*User, error) {
	return one[User](s.Pool.Query(ctx, `UPDATE users SET show_read_receipts = $2 WHERE id = $1 RETURNING `+userCols, userID, show))
}

// FamilyConversations maps feeds to their family chats.
func (s *Store) FamilyConversations(ctx context.Context, feedIDs []uuid.UUID) (map[uuid.UUID]uuid.UUID, error) {
	rows, err := s.Pool.Query(ctx, `SELECT feed_id, id FROM conversations WHERE kind = 'family' AND feed_id = ANY($1)`, feedIDs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[uuid.UUID]uuid.UUID{}
	for rows.Next() {
		var f, c uuid.UUID
		if err := rows.Scan(&f, &c); err != nil {
			return nil, err
		}
		out[f] = c
	}
	return out, rows.Err()
}
