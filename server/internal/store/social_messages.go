package store

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Chat messages as targets of people's reactions (README §5.18). The chat tables belong to server-chat; this
// file only reads them.

// MessageTarget is a chat message as a reaction target, with the viewer's membership.
type MessageTarget struct {
	ID             uuid.UUID  `db:"id"`
	ConversationID uuid.UUID  `db:"conversation_id"`
	ConvKind       string     `db:"conv_kind"`
	FeedID         *uuid.UUID `db:"feed_id"`
	Archived       bool       `db:"archived"`
	Kind           string     `db:"kind"`
	AuthorID       *uuid.UUID `db:"author_user_id"`
	Deleted        bool       `db:"deleted"`
	CreatedAt      time.Time  `db:"created_at"`
	Member         bool       `db:"member"`
	VisibleFrom    *time.Time `db:"visible_from"`
}

// Visible: the viewer is a member and the message is from their part of the history.
func (m *MessageTarget) Visible() bool {
	return m.Member && (m.VisibleFrom == nil || !m.CreatedAt.Before(*m.VisibleFrom))
}

// GetMessageTarget loads a message as viewer sees it.
func (s *Store) GetMessageTarget(ctx context.Context, q db.DBTX, viewer, id uuid.UUID) (*MessageTarget, error) {
	return one[MessageTarget](q.Query(ctx, `SELECT m.id, m.conversation_id, c.kind AS conv_kind, c.feed_id, c.archived_at IS NOT NULL AS archived,
		m.kind, m.author_user_id, m.deleted_at IS NOT NULL AS deleted, m.created_at, cm.user_id IS NOT NULL AS member, cm.visible_from
		FROM messages m JOIN conversations c ON c.id = m.conversation_id
		LEFT JOIN conversation_members cm ON cm.conversation_id = m.conversation_id AND cm.user_id = $1
		WHERE m.id = $2`, viewer, id))
}

// MessageAudience lists the conversation's members who see the message (groups: joined before it).
func (s *Store) MessageAudience(ctx context.Context, q db.DBTX, m *MessageTarget) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT user_id FROM conversation_members WHERE conversation_id = $1
		AND (visible_from IS NULL OR visible_from <= $2) ORDER BY user_id`, m.ConversationID, m.CreatedAt)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// ConversationMuted reports whether userID muted the conversation at now.
func (s *Store) ConversationMuted(ctx context.Context, q db.DBTX, conversationID, userID uuid.UUID, now time.Time) (bool, error) {
	var muted bool
	err := q.QueryRow(ctx, `SELECT COALESCE(muted_until > $3, false) FROM conversation_members WHERE conversation_id = $1 AND user_id = $2`,
		conversationID, userID, now).Scan(&muted)
	if db.NotFound(err) {
		return false, nil
	}
	return muted, err
}
