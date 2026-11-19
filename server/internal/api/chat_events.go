package api

// Chat events and pushes (README §6.2, §6.3): conversation.updated, conversation.removed, message.created,
// message.updated, read.updated, typing, badge.updated; push keys message, mention (messages) and
// conversation_added. Texts are in i18n locales/chat as push.chat_*.

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/blob"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

func notifyEvent(typ string, fields map[string]any) notify.Event {
	ev := notify.Event{"type": typ}
	for k, v := range fields {
		ev[k] = v
	}
	return ev
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// conversationTitle is the conversation's name as a member sees it (push titles).
func conversationTitle(c *store.Conversation, members []store.ConversationMember, viewer uuid.UUID) string {
	switch c.Kind {
	case "group":
		return deref(c.Name)
	case "family":
		return deref(c.FeedName)
	case "circle":
		return deref(c.CircleName)
	}
	for _, m := range members {
		if m.UserID != viewer && !m.Deleted {
			return m.DisplayName
		}
	}
	return ""
}

// ---------------------------------------------------------------- conversations

// publishConversation sends conversation.updated to every member (and to extra people, e.g. just added).
func (s *Server) publishConversation(ctx context.Context, convID uuid.UUID, extra ...uuid.UUID) {
	ms, err := s.st.ConversationMembers(ctx, s.st.Q(), []uuid.UUID{convID})
	if err != nil {
		s.log.Warn("conversation members", "err", err)
		return
	}
	seen := map[uuid.UUID]bool{}
	ids := []uuid.UUID{}
	for _, m := range ms[convID] {
		if !seen[m.UserID] {
			seen[m.UserID] = true
			ids = append(ids, m.UserID)
		}
	}
	for _, id := range extra {
		if !seen[id] {
			seen[id] = true
			ids = append(ids, id)
		}
	}
	for _, id := range ids {
		if !s.hub.UserConnected(id) {
			continue
		}
		u, err := s.st.GetUser(ctx, s.st.Q(), id)
		if err != nil {
			continue
		}
		c, err := s.conversationJSON(ctx, u, convID)
		if err != nil {
			continue // not a member (any more)
		}
		s.notify.User(id, notifyEvent("conversation.updated", map[string]any{"conversation": c}), nil)
	}
}

// pushConversationAdded tells someone they were added to a group (push conversation_added when offline).
func (s *Server) pushConversationAdded(ctx context.Context, convID, userID uuid.UUID) {
	c, err := s.st.GetConversation(ctx, s.st.Q(), convID)
	if err != nil {
		return
	}
	by := ""
	if p := s.personRefOf(ctx, me(ctx).ID); p != nil {
		by = p.DisplayName
	}
	push := &notify.Push{Key: "conversation_added", Text: "chat_conversation_added",
		Args: map[string]string{"conversation": deref(c.Name), "name": by},
		IDs:  map[string]string{"conversation_id": convID.String()}}
	if s.hub.UserConnected(userID) {
		return // conversation.updated already went to the open connection
	}
	s.notify.User(userID, notifyEvent("conversation.updated", map[string]any{"conversation_id": convID}), push)
}

// ---------------------------------------------------------------- messages

// publishMessage sends message.created (isNew) or message.updated to the members who may see the message,
// not to people who blocked its author; new person messages also push and change badges.
func (s *Server) publishMessage(ctx context.Context, convID, msgID uuid.UUID, isNew bool) {
	ctx = context.WithoutCancel(ctx)
	conv, err := s.st.GetConversation(ctx, s.st.Q(), convID)
	if err != nil {
		return
	}
	msgs, err := s.st.MessagesByID(ctx, s.st.Q(), []uuid.UUID{msgID})
	if err != nil || len(msgs) == 0 {
		return
	}
	m := msgs[msgID]
	ms, err := s.st.ConversationMembers(ctx, s.st.Q(), []uuid.UUID{convID})
	if err != nil {
		return
	}
	members := ms[convID]
	ids := make([]uuid.UUID, 0, len(members))
	for _, mb := range members {
		ids = append(ids, mb.UserID)
	}
	var blockedAuthor, either map[uuid.UUID]bool
	if m.AuthorUserID != nil {
		if blockedAuthor, err = s.st.BlockedBy(ctx, s.st.Q(), *m.AuthorUserID, ids); err != nil {
			return
		}
		if either, err = s.st.BlockedEither(ctx, s.st.Q(), *m.AuthorUserID, ids); err != nil {
			return
		}
	}
	d, err := s.loadMessages(ctx, []store.Message{m})
	if err != nil {
		s.log.Warn("load message", "err", err)
		return
	}
	mentioned := map[uuid.UUID]bool{}
	for _, x := range parseMentions(m.Mentions) {
		mentioned[x.UserId] = true
	}
	typ := "message.updated"
	if isNew {
		typ = "message.created"
	}
	var badges []uuid.UUID
	for _, mb := range members {
		if blockedAuthor[mb.UserID] || (mb.VisibleFrom != nil && m.CreatedAt.Before(*mb.VisibleFrom)) {
			continue
		}
		v, err := s.chatViewer(ctx, mb.UserID, mb.VisibleFrom)
		if err != nil {
			continue
		}
		ev := notifyEvent(typ, map[string]any{"conversation_id": convID, "message": s.messageView(ctx, d, msgID, v)})
		self := m.AuthorUserID != nil && *m.AuthorUserID == mb.UserID
		var push *notify.Push
		if isNew && m.Kind == "person" && !self && !mb.Muted(conv.Kind, s.now()) {
			push = s.messagePush(conv, members, &m, mb.UserID, mentioned[mb.UserID] && !either[mb.UserID])
		}
		s.notify.User(mb.UserID, ev, push)
		if isNew && !self {
			badges = append(badges, mb.UserID)
		}
	}
	if len(badges) > 0 {
		s.notifyBadge(ctx, badges...)
	}
}

// messagePush is the push for a new message: mention when the recipient is mentioned, else message ("Anna:
// Look at this", "Anna sent 3 photos", "Anna sent a voice message", "Anna shared a post").
func (s *Server) messagePush(conv *store.Conversation, members []store.ConversationMember, m *store.Message, to uuid.UUID, mention bool) *notify.Push {
	name := ""
	if m.AuthorName != nil && !m.AuthorDeleted {
		name = *m.AuthorName
	}
	args := map[string]string{"conversation": conversationTitle(conv, members, to), "name": name}
	ids := map[string]string{"conversation_id": conv.ID.String(), "message_id": m.ID.String()}
	text := ""
	if b := s.openBody(m.Body, m.BodyEnc); b != nil {
		text = excerpt(strings.Join(strings.Fields(*b), " "), 120)
	}
	args["text"] = text
	if mention {
		return &notify.Push{Key: "mention", Text: "chat_mention", Args: args, IDs: ids}
	}
	p := &notify.Push{Key: "message", Args: args, IDs: ids, Tag: "conversation:" + conv.ID.String()}
	n := 0
	var count int
	_ = s.st.Q().QueryRow(context.Background(), `SELECT count(*) FROM message_items WHERE message_id = $1`, m.ID).Scan(&count)
	n = count
	switch {
	case text != "":
		p.Text = "chat_message"
	case m.VoiceItemID != nil:
		p.Text = "chat_message_voice"
	case n == 1:
		p.Text = "chat_message_photo"
	case n > 1:
		p.Text = "chat_message_photos"
		args["count"] = strconv.Itoa(n)
	case m.SharedPostID != nil:
		p.Text = "chat_message_post"
	default:
		p.Text = "chat_message"
	}
	return p
}

// pushMentions notifies people newly mentioned in an edited message (not muted, no block either way).
func (s *Server) pushMentions(ctx context.Context, convID, msgID uuid.UUID, users []uuid.UUID) {
	if len(users) == 0 {
		return
	}
	conv, err := s.st.GetConversation(ctx, s.st.Q(), convID)
	if err != nil {
		return
	}
	msgs, err := s.st.MessagesByID(ctx, s.st.Q(), []uuid.UUID{msgID})
	if err != nil || len(msgs) == 0 {
		return
	}
	m := msgs[msgID]
	ms, err := s.st.ConversationMembers(ctx, s.st.Q(), []uuid.UUID{convID})
	if err != nil {
		return
	}
	either, err := s.st.BlockedEither(ctx, s.st.Q(), *m.AuthorUserID, users)
	if err != nil {
		return
	}
	want := map[uuid.UUID]bool{}
	for _, u := range users {
		want[u] = true
	}
	for _, mb := range ms[convID] {
		if !want[mb.UserID] || either[mb.UserID] || mb.Muted(conv.Kind, s.now()) || mb.UserID == *m.AuthorUserID {
			continue
		}
		if s.hub.UserConnected(mb.UserID) {
			continue // message.updated already showed it
		}
		push := s.messagePush(conv, ms[convID], &m, mb.UserID, true)
		s.notify.User(mb.UserID, notifyEvent("message.updated", map[string]any{"conversation_id": convID, "message_id": msgID}), push)
	}
}

// publishRead sends read.updated: to the reader's own connections always, to other members when read receipts
// are shown in the conversation and both show them, and neither blocked the other.
func (s *Server) publishRead(ctx context.Context, v *store.ConversationView, reader *store.User, msgID uuid.UUID) {
	ev := notifyEvent("read.updated", map[string]any{"conversation_id": v.ID, "user_id": reader.ID, "last_read_message_id": msgID})
	s.notify.User(reader.ID, ev, nil)
	ms, err := s.st.ConversationMembers(ctx, s.st.Q(), []uuid.UUID{v.ID})
	if err != nil || !receiptsShown(v.Kind, len(ms[v.ID])) || !reader.ShowReadReceipts {
		return
	}
	ids := []uuid.UUID{}
	for _, m := range ms[v.ID] {
		if m.UserID != reader.ID && m.ShowReadReceipts {
			ids = append(ids, m.UserID)
		}
	}
	either, err := s.st.BlockedEither(ctx, s.st.Q(), reader.ID, ids)
	if err != nil {
		return
	}
	for _, id := range ids {
		if !either[id] {
			s.notify.User(id, ev, nil)
		}
	}
}

// notifyConversationBadges recomputes the badges of every member.
func (s *Server) notifyConversationBadges(ctx context.Context, convID uuid.UUID) {
	ms, err := s.st.ConversationMembers(ctx, s.st.Q(), []uuid.UUID{convID})
	if err != nil {
		return
	}
	ids := make([]uuid.UUID, 0, len(ms[convID]))
	for _, m := range ms[convID] {
		ids = append(ids, m.UserID)
	}
	s.notifyBadge(ctx, ids...)
}

// ---------------------------------------------------------------- system messages

// insertSystemMessage stores a system message in the caller's transaction; publish it after commit with
// publishSystemMessages.
func (s *Server) insertSystemMessage(ctx context.Context, tx *store.Tx, convID uuid.UUID, sm gen.SystemMessage) (uuid.UUID, error) {
	typ := string(sm.Type)
	data := sm
	data.Type = ""
	raw, err := json.Marshal(data)
	if err != nil {
		return uuid.Nil, err
	}
	id := store.NewID()
	return id, tx.CreateMessage(ctx, store.NewMessage{ID: id, ConversationID: convID, Kind: "system", SystemType: &typ, SystemData: raw})
}

// publishSystemMessages sends message.created for system messages (they never push).
func (s *Server) publishSystemMessages(ctx context.Context, convID uuid.UUID, ids []uuid.UUID) {
	for _, id := range ids {
		s.publishMessage(ctx, convID, id, true)
	}
}

// ---------------------------------------------------------------- media and frames

// MediaReady implements worker.MediaReadyEvents: chat photos, videos and voice messages that finished
// processing update their messages.
func (s *Server) MediaReady(mediaID uuid.UUID) { s.chatMediaChanged(mediaID) }

// chatMediaChanged sends message.updated for the messages using a media (ready or failed).
func (s *Server) chatMediaChanged(mediaID uuid.UUID) {
	ctx := context.Background()
	ids, err := s.st.MessagesOfMedia(ctx, mediaID)
	if err != nil {
		s.log.Warn("messages of media", "err", err)
		return
	}
	s.publishMessagesUpdated(ctx, ids)
}

// chatFrameActivity: a frame reacted or replied to a placement of an item: messages holding the item show it
// in frame_activity.
func (s *Server) chatFrameActivity(ctx context.Context, itemID uuid.UUID) {
	ids, err := s.st.MessagesOfItem(ctx, itemID)
	if err != nil {
		s.log.Warn("messages of item", "err", err)
		return
	}
	s.publishMessagesUpdated(ctx, ids)
}

func (s *Server) publishMessagesUpdated(ctx context.Context, ids []uuid.UUID) {
	if len(ids) == 0 {
		return
	}
	msgs, err := s.st.MessagesByID(ctx, s.st.Q(), ids)
	if err != nil {
		return
	}
	for _, m := range msgs {
		s.publishMessage(ctx, m.ConversationID, m.ID, false)
	}
}

// ---------------------------------------------------------------- group photos

// storeSquarePhoto reads an uploaded photo (JPEG, PNG or WebP, at most 20 MB), crops it to a 256x256 WebP
// like an avatar and stores it under prefix + a random name.
func (s *Server) storeSquarePhoto(ctx context.Context, r *http.Request, prefix string) (string, error) {
	ct := strings.ToLower(strings.TrimSpace(strings.Split(r.Header.Get("Content-Type"), ";")[0]))
	if ct != "image/jpeg" && ct != "image/png" && ct != "image/webp" {
		return "", problem.New(problem.UnsupportedMedia, "photos must be JPEG, PNG or WebP").WithStatus(http.StatusUnsupportedMediaType)
	}
	tmp, err := s.tempDir()
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(tmp)
	src := filepath.Join(tmp, "in")
	f, err := os.Create(src)
	if err != nil {
		return "", err
	}
	n, err := io.Copy(f, io.LimitReader(r.Body, 20<<20+1))
	f.Close()
	if err != nil {
		return "", err
	}
	if n > 20<<20 {
		return "", problem.New(problem.UploadTooLarge, "photos are limited to 20 MB")
	}
	if kind, _, err := media.Sniff(src); err != nil || kind != media.Photo {
		return "", problem.New(problem.UnsupportedMedia, "")
	}
	norm, out := filepath.Join(tmp, "norm.webp"), filepath.Join(tmp, "photo.webp")
	size, _, err := s.tools.PhotoMaster(ctx, src, norm)
	if err != nil {
		return "", problem.New(problem.CorruptMedia, "")
	}
	if err := s.tools.Thumb(ctx, norm, size, 256, 0.5, 0.5, out); err != nil {
		return "", problem.New(problem.CorruptMedia, "")
	}
	key := prefix + auth.RandomToken(12) + ".webp"
	if err := s.blob.PutFile(ctx, key, out, blob.PutOptions{ContentType: "image/webp"}); err != nil {
		return "", err
	}
	return key, nil
}
