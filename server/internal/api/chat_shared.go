//lint:file-ignore U1000 Fixed signatures for the parallel family social work; their callers arrive with the areas (docs/spec/family-social-work.md).

package api

// Chats, server-chat's helpers that the other areas call (docs/spec/family-social-work.md). The signatures are
// fixed.

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// postSystemMessage posts a system message (kind `system`, README §5.19) into a conversation and sends
// `message.created`. Engagement uses it for `streak_milestone`, `monthly_recap` and `prompt` in family chats
// (familyConversationID), chats itself for `put_on_frame`, `member_joined` and `member_left`. System messages
// never push.
func (s *Server) postSystemMessage(ctx context.Context, conversationID uuid.UUID, msg gen.SystemMessage) (uuid.UUID, error) {
	var id uuid.UUID
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		var err error
		id, err = s.insertSystemMessage(ctx, tx, conversationID, msg)
		return err
	})
	if err != nil {
		return uuid.Nil, err
	}
	s.publishSystemMessages(ctx, conversationID, []uuid.UUID{id})
	return id, nil
}

// familyConversationID is the family chat of a feed (created by the database with the feed).
func (s *Server) familyConversationID(ctx context.Context, feedID uuid.UUID) (uuid.UUID, error) {
	var id uuid.UUID
	err := s.st.Q().QueryRow(ctx, `SELECT id FROM conversations WHERE kind = 'family' AND feed_id = $1`, feedID).Scan(&id)
	return id, err
}

// notifyBadge recomputes the app badge of these users and sends `badge.updated` (README §5.19) when it
// changed. Social calls it when thread unread state changes, chats when conversation unread state changes.
func (s *Server) notifyBadge(ctx context.Context, userIDs ...uuid.UUID) {
	ctx = context.WithoutCancel(ctx)
	seen := map[uuid.UUID]bool{}
	for _, id := range userIDs {
		if seen[id] {
			continue
		}
		seen[id] = true
		if !s.hub.UserConnected(id) {
			// Nothing to send now; the next getBadge or connection reads it fresh.
			s.chat.mu.Lock()
			delete(s.chat.badges, id)
			s.chat.mu.Unlock()
			continue
		}
		b, err := s.badge(ctx, id)
		if err != nil {
			s.log.Warn("badge", "user", id, "err", err)
			continue
		}
		s.chat.mu.Lock()
		last, known := s.chat.badges[id]
		s.chat.badges[id] = b
		s.chat.mu.Unlock()
		if known && last == b {
			continue
		}
		s.notify.User(id, notifyEvent("badge.updated", map[string]any{"badge": b}), nil)
	}
}

// chatDiscovery sets discovery `chats`, `voice_messages`, `link_previews` and the chat limits.
func (s *Server) chatDiscovery(d *gen.Discovery) {
	d.Chats = ptr(true)
	d.VoiceMessages = ptr(s.chat.voice)
	d.LinkPreviews = ptr(s.chat.previews != nil)
	d.Limits.MaxMessageChars = ptr(maxMessageChars)
	d.Limits.MaxMessageItems = ptr(maxMessageItems)
	d.Limits.MaxGroupMembers = ptr(maxGroupMembers)
	d.Limits.MaxVoiceSeconds = ptr(maxVoiceSeconds)
	d.Limits.MessageEditHours = ptr(messageEditHours)
	if d.Limits.MaxMentions == nil {
		d.Limits.MaxMentions = ptr(maxMentions)
	}
}

// runChatJob handles server-chat's worker jobs (handled=false for other kinds).
func (s *Server) runChatJob(ctx context.Context, job *store.Job) (bool, error) {
	switch job.Kind {
	case store.JobChatLinkPreview:
		return true, s.runLinkPreviewJob(ctx, job)
	}
	return false, nil
}

// RunChat is server-chat's background loop: old link previews and their images, operators not seen for a
// year, the badge cache.
func (s *Server) RunChat(ctx context.Context) {
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		keys, err := s.st.PruneLinkPreviews(ctx)
		if err != nil {
			s.log.Warn("prune link previews", "err", err)
		}
		for _, k := range keys {
			s.deleteBlob(ctx, k)
		}
		s.pruneOperators(ctx)
		s.chat.mu.Lock()
		for id := range s.chat.badges {
			if !s.hub.UserConnected(id) {
				delete(s.chat.badges, id)
			}
		}
		s.chat.mu.Unlock()
	}
}
