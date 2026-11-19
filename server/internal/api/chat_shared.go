//lint:file-ignore U1000 Fixed signatures for the parallel family social work; their callers arrive with the areas (docs/spec/family-social-work.md).

package api

// Chats, server-chat's helpers that the other areas call (docs/spec/family-social-work.md). The signatures are
// fixed; server-chat fills in the bodies.

import (
	"context"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// postSystemMessage posts a system message (kind `system`, README §5.19) into a conversation and sends
// `message.created`. Engagement uses it for `streak_milestone`, `monthly_recap` and `prompt` in family chats
// (familyConversationID), chats itself for `put_on_frame`, `member_joined` and `member_left`. System messages
// never push.
func (s *Server) postSystemMessage(ctx context.Context, conversationID uuid.UUID, msg gen.SystemMessage) (uuid.UUID, error) {
	_, _, _ = ctx, conversationID, msg
	return uuid.Nil, nil
}

// familyConversationID is the family chat of a feed (created by the database with the feed).
func (s *Server) familyConversationID(ctx context.Context, feedID uuid.UUID) (uuid.UUID, error) {
	var id uuid.UUID
	err := s.st.Q().QueryRow(ctx, `SELECT id FROM conversations WHERE kind = 'family' AND feed_id = $1`, feedID).Scan(&id)
	return id, err
}

// notifyBadge recomputes the app badge of these users and sends `badge.updated` (README §5.19). Social calls it
// when thread unread state changes, chats when conversation unread state changes.
func (s *Server) notifyBadge(ctx context.Context, userIDs ...uuid.UUID) { _, _ = ctx, userIDs }

// chatDiscovery sets discovery `chats`, `voice_messages`, `link_previews` and the chat limits once built.
func (s *Server) chatDiscovery(d *gen.Discovery) { _ = d }

// runChatJob handles server-chat's worker jobs (handled=false for other kinds).
func (s *Server) runChatJob(ctx context.Context, job *store.Job) (bool, error) {
	_, _ = ctx, job
	return false, nil
}

// RunChat is server-chat's background loop (message push bundles, link preview refresh).
func (s *Server) RunChat(ctx context.Context) { <-ctx.Done() }
