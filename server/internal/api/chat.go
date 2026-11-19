package api

// Chats (docs/spec/family-social.md, README §5.19): conversations, their members and preferences. Messages are
// in messages.go, events and pushes in chat_events.go, blocks and reports in safety.go, link previews in
// linkpreview.go. Family and circle chats and their members are kept by the database (migration 00023); code
// here only writes direct and group members and every member's own state.

import (
	"context"
	"net/http"
	"os/exec"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/linkpreview"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/ratelimit"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Chat limits (discovery `limits`, README §5.19). Not operator-adjustable.
const (
	maxMessageChars   = 4000
	maxMessageItems   = 30
	maxGroupMembers   = 100
	maxVoiceSeconds   = 300
	messageEditHours  = 24
	maxMentions       = 20
	maxGroupNameChars = 60
	receiptsMaxGroup  = 10 // read receipts in groups of at most this many people
	typingTTL         = 6 * time.Second
)

// chatState is the chat area's part of the Server.
type chatState struct {
	voice    bool                 // ffmpeg and ffprobe are there: voice messages
	previews *linkpreview.Fetcher // nil: link previews off
	typing   *ratelimit.Limiter   // one typing event per person and conversation every 3 s
	preview  *ratelimit.Limiter   // createLinkPreview fetches per person

	mu        sync.Mutex
	badges    map[uuid.UUID]gen.Badge // last badge sent per user (badge.updated only on change)
	operators map[uuid.UUID]time.Time // operators noted recently (chat_operators)

	// postAccess replaces postAccessFor in tests (SetPostAccessForTest).
	postAccess func(ctx context.Context, userID, postID uuid.UUID) (postAccess, error)
}

// setupChat prepares chats: voice messages need ffmpeg, link previews the operator's opt-in.
func (s *Server) setupChat() error {
	c := &chatState{typing: ratelimit.New(1, 3*time.Second), preview: ratelimit.New(120, time.Hour), badges: map[uuid.UUID]gen.Badge{}, operators: map[uuid.UUID]time.Time{}}
	_, errF := exec.LookPath(s.tools.FFmpeg)
	if s.tools.FFmpeg == "" {
		_, errF = exec.LookPath("ffmpeg")
	}
	_, errP := exec.LookPath(s.tools.FFprobe)
	if s.tools.FFprobe == "" {
		_, errP = exec.LookPath("ffprobe")
	}
	c.voice = errF == nil && errP == nil
	if s.cfg.LinkPreviews {
		g, err := linkpreview.Guard(s.cfg.SourcesAllowLoopback, nil)
		if err != nil {
			return err
		}
		c.previews = linkpreview.NewFetcher(g)
	}
	s.chat = c
	return nil
}

// SetPostAccessForTest replaces the post visibility check chats use (shared posts, reports) until the
// family social area provides postAccessFor; nil restores it.
func (s *Server) SetPostAccessForTest(f func(ctx context.Context, userID, postID uuid.UUID) (visible bool, feedID *uuid.UUID, poster uuid.UUID, err error)) {
	if f == nil {
		s.chat.postAccess = nil
		return
	}
	s.chat.postAccess = func(ctx context.Context, userID, postID uuid.UUID) (postAccess, error) {
		visible, feedID, poster, err := f(ctx, userID, postID)
		a := postAccess{Visible: visible, CanComment: visible, FeedID: feedID, PosterID: poster, Kind: "frame"}
		if feedID != nil {
			a.Kind = "feed"
		}
		return a, err
	}
}

func (s *Server) chatPostAccess(ctx context.Context, userID, postID uuid.UUID) (postAccess, error) {
	if s.chat.postAccess != nil {
		return s.chat.postAccess(ctx, userID, postID)
	}
	return s.postAccessFor(ctx, userID, postID)
}

// ---------------------------------------------------------------- bodies

// sealBody stores text as body, or sealed as body_enc when the server encrypts bodies at rest.
func (s *Server) sealBody(text *string) (*string, []byte) {
	if text == nil {
		return nil, nil
	}
	if s.bodies != nil {
		return nil, s.bodies.Seal(*text)
	}
	return text, nil
}

// openBody reads a stored body (either form).
func (s *Server) openBody(body *string, enc []byte) *string {
	if body != nil {
		return body
	}
	if len(enc) == 0 || s.bodies == nil {
		return nil
	}
	t, err := s.bodies.Open(enc)
	if err != nil {
		s.log.Warn("open message body", "err", err)
		return nil
	}
	return &t
}

// ---------------------------------------------------------------- people

func (s *Server) personRef(id uuid.UUID, name string, avatarKey *string, deleted bool) gen.PersonRef {
	if deleted {
		return gen.PersonRef{UserId: id, DisplayName: "", FormerMember: ptr(true)}
	}
	return gen.PersonRef{UserId: id, DisplayName: name, AvatarUrl: s.avatarURL(avatarKey)}
}

func directKey(a, b uuid.UUID) string {
	if b.String() < a.String() {
		a, b = b, a
	}
	return a.String() + ":" + b.String()
}

// canMessage: other is a contact of userID and has not blocked them (README §5.19). One answer for both, so
// nobody learns about blocks.
func (s *Server) canMessage(ctx context.Context, q db.DBTX, userID, other uuid.UUID) error {
	if other == userID {
		return problem.New(problem.CannotMessage, "")
	}
	okContact, err := s.st.AreContacts(ctx, q, userID, other)
	if err != nil {
		return err
	}
	blocked, err := s.st.HasBlocked(ctx, q, other, userID)
	if err != nil {
		return err
	}
	if !okContact || blocked {
		return problem.New(problem.CannotMessage, "")
	}
	return nil
}

// ---------------------------------------------------------------- access

// conversation is the caller's view of a conversation they are in; 404 for everyone else.
func (s *Server) conversation(ctx context.Context, q db.DBTX, convID uuid.UUID) (*store.ConversationView, error) {
	v, err := s.st.ConversationOf(ctx, q, me(ctx).ID, convID)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "conversation not found")
	}
	return v, err
}

func requireGroup(c *store.Conversation) error {
	if c.Kind != "group" {
		return problem.New(problem.InvalidTransition, "only group chats can do this")
	}
	return nil
}

func requireGroupAdmin(v *store.ConversationView) error {
	if err := requireGroup(&v.Conversation); err != nil {
		return err
	}
	if v.Role != "admin" {
		return problem.New(problem.Forbidden, "group admins only")
	}
	return nil
}

// isChatModerator: group admins; for family chats the feed's owner and admins; for circle chats also the
// circle's admins (README §5.19).
func (s *Server) isChatModerator(ctx context.Context, userID uuid.UUID, c *store.Conversation, role string) (bool, error) {
	switch c.Kind {
	case "group":
		return role == "admin", nil
	case "family", "circle":
		var ok bool
		err := s.st.Q().QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM feed_members WHERE feed_id = $1 AND user_id = $2 AND role IN ('owner', 'admin'))
			OR ($3::uuid IS NOT NULL AND EXISTS (SELECT 1 FROM circle_members WHERE circle_id = $3 AND user_id = $2 AND role = 'admin'))`,
			c.FeedID, userID, c.CircleID).Scan(&ok)
		return ok, err
	}
	return false, nil
}

// readOnly: the family or circle was deleted, or (direct) the other side is gone or blocked the caller.
func (s *Server) readOnly(ctx context.Context, userID uuid.UUID, c *store.Conversation, members []store.ConversationMember) (bool, error) {
	if c.ArchivedAt != nil {
		return true, nil
	}
	if c.Kind != "direct" {
		return false, nil
	}
	for _, m := range members {
		if m.UserID == userID {
			continue
		}
		if m.Deleted {
			return true, nil
		}
		blocked, err := s.st.HasBlocked(ctx, s.st.Q(), m.UserID, userID)
		if err != nil || blocked {
			return blocked, err
		}
		// Leaving (README §5.21): two people who no longer share a family or frame can't message each other.
		share, err := s.shareLiveScope(ctx, s.st.Q(), userID, m.UserID)
		return !share, err
	}
	return true, nil // the other side left (account purged)
}

// ---------------------------------------------------------------- JSON

func receiptsShown(kind string, memberCount int) bool {
	return kind == "direct" || (kind == "group" && memberCount <= receiptsMaxGroup)
}

// conversationsJSON renders conversations for the viewer.
func (s *Server) conversationsJSON(ctx context.Context, viewer *store.User, views []store.ConversationView) ([]gen.Conversation, error) {
	out := make([]gen.Conversation, len(views))
	if len(views) == 0 {
		return out, nil
	}
	ids := make([]uuid.UUID, len(views))
	for i, v := range views {
		ids[i] = v.ID
	}
	members, err := s.st.ConversationMembers(ctx, s.st.Q(), ids)
	if err != nil {
		return nil, err
	}
	last, err := s.lastMessages(ctx, viewer.ID, ids)
	if err != nil {
		return nil, err
	}
	now := time.Now()
	for i := range views {
		v := &views[i]
		ms := members[v.ID]
		c := gen.Conversation{
			Id: v.ID, Kind: gen.ConversationKind(v.Kind), FeedId: v.FeedID, CircleId: v.CircleID,
			MemberCount: len(ms), UnreadCount: v.UnreadCount, UnreadMentions: ptr(v.UnreadMentions), ReadUpTo: v.LastReadMessageID,
			LastActivityAt: v.CreatedAt, Muted: v.Muted(now), Archived: v.Archived || v.ArchivedAt != nil, CreatedAt: ptr(v.CreatedAt),
			MyRole: ptr(gen.ConversationRole(v.Role)),
		}
		if v.LastMessageAt != nil {
			c.LastActivityAt = *v.LastMessageAt
		}
		if v.MutedUntil != nil && v.MutedUntil.After(now) {
			c.MutedUntil = v.MutedUntil
		}
		switch v.Kind {
		case "group":
			c.Name = deref(v.Name)
			c.PhotoUrl = s.avatarURL(v.PhotoKey)
		case "family":
			c.Name = deref(v.FeedName)
			c.PhotoUrl = s.feedCoverURL(v.CoverKey)
		case "circle":
			c.Name = deref(v.CircleName)
		case "direct":
			for _, m := range ms {
				if m.UserID != viewer.ID {
					p := s.personRef(m.UserID, m.DisplayName, m.AvatarKey, m.Deleted)
					c.Other, c.Name, c.PhotoUrl = &p, p.DisplayName, p.AvatarUrl
				}
			}
		}
		ro, err := s.readOnly(ctx, viewer.ID, &v.Conversation, ms)
		if err != nil {
			return nil, err
		}
		c.ReadOnly = ptr(ro)
		if ro {
			c.ReadOnlyReason = s.readOnlyReason(ctx, viewer.ID, &v.Conversation, ms) // leaving
		}
		receipts := receiptsShown(v.Kind, len(ms)) && viewer.ShowReadReceipts
		c.ReadReceipts = ptr(receipts)
		list := make([]gen.ConversationMember, len(ms))
		for j, m := range ms {
			list[j] = gen.ConversationMember{Person: s.personRef(m.UserID, m.DisplayName, m.AvatarKey, m.Deleted),
				Role: gen.ConversationRole(m.Role), JoinedAt: ptr(m.JoinedAt)}
			if receipts && m.ShowReadReceipts {
				list[j].LastReadMessageId = m.LastReadMessageID
			}
		}
		c.Members = &list
		if lm, ok := last[v.ID]; ok {
			c.LastMessage = &lm
		}
		out[i] = c
	}
	return out, nil
}

func (s *Server) conversationJSON(ctx context.Context, viewer *store.User, convID uuid.UUID) (*gen.Conversation, error) {
	v, err := s.st.ConversationOf(ctx, s.st.Q(), viewer.ID, convID)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "conversation not found")
	}
	if err != nil {
		return nil, err
	}
	out, err := s.conversationsJSON(ctx, viewer, []store.ConversationView{*v})
	if err != nil {
		return nil, err
	}
	return &out[0], nil
}

// ---------------------------------------------------------------- conversations

func (s *Server) ListConversations(ctx context.Context, req gen.ListConversationsRequestObject) (gen.ListConversationsResponseObject, error) {
	s.noteOperator(ctx)
	archived := req.Params.Archived != nil && *req.Params.Archived
	views, err := s.st.ConversationsOf(ctx, me(ctx).ID, archived)
	if err != nil {
		return nil, err
	}
	out, err := s.conversationsJSON(ctx, me(ctx), views)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

type conversationRef struct {
	ConversationID uuid.UUID `json:"conversation_id"`
	Existing       bool      `json:"existing"`
}

func (s *Server) CreateConversation(ctx context.Context, req gen.CreateConversationRequestObject) (gen.CreateConversationResponseObject, error) {
	u := me(ctx)
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev conversationRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "conversation", key, &prev); err != nil {
			return nil, err
		} else if found {
			return s.conversationCreated(ctx, prev)
		}
	}
	b := req.Body
	others := []uuid.UUID{}
	seen := map[uuid.UUID]bool{u.ID: true}
	for _, id := range b.MemberIds {
		if !seen[id] {
			seen[id] = true
			others = append(others, id)
		}
	}
	var ref conversationRef
	switch b.Kind {
	case gen.ConversationCreateKindDirect:
		if len(others) != 1 {
			return nil, problem.Validation("member_ids", "count", "a direct chat is with exactly one other person")
		}
		if err := s.canMessage(ctx, s.st.Q(), u.ID, others[0]); err != nil {
			return nil, err
		}
		dk := directKey(u.ID, others[0])
		err := s.st.InTx(ctx, func(tx *store.Tx) error {
			// One per pair: serialize on the pair's key.
			if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtext($1))`, dk); err != nil {
				return err
			}
			if id, err := s.st.DirectConversation(ctx, tx, dk); err == nil {
				ref = conversationRef{ConversationID: id, Existing: true}
				return nil
			} else if err != store.ErrNotFound {
				return err
			}
			ref = conversationRef{ConversationID: store.NewID()}
			if err := tx.CreateConversation(ctx, ref.ConversationID, "direct", &dk, nil, u.ID); err != nil {
				return err
			}
			for _, m := range []uuid.UUID{u.ID, others[0]} {
				if _, err := tx.AddConversationMember(ctx, ref.ConversationID, m, "member", &u.ID, false); err != nil {
					return err
				}
			}
			return saveIdem(ctx, tx, u.ID, "conversation", key, ref)
		})
		if err != nil {
			return nil, err
		}
	default:
		name := ""
		if b.Name != nil {
			name = strings.TrimSpace(*b.Name)
		}
		if name == "" || utf8.RuneCountInString(name) > maxGroupNameChars {
			return nil, problem.Validation("name", "length", "a group needs a name")
		}
		if len(others) < 2 || len(others)+1 > maxGroupMembers {
			return nil, problem.Validation("member_ids", "count", "a group has 3 to 100 people")
		}
		for _, o := range others {
			if err := s.canMessage(ctx, s.st.Q(), u.ID, o); err != nil {
				return nil, err
			}
		}
		ref = conversationRef{ConversationID: store.NewID()}
		err := s.st.InTx(ctx, func(tx *store.Tx) error {
			if err := tx.CreateConversation(ctx, ref.ConversationID, "group", nil, &name, u.ID); err != nil {
				return err
			}
			if _, err := tx.AddConversationMember(ctx, ref.ConversationID, u.ID, "admin", &u.ID, true); err != nil {
				return err
			}
			for _, o := range others {
				if _, err := tx.AddConversationMember(ctx, ref.ConversationID, o, "member", &u.ID, true); err != nil {
					return err
				}
			}
			return saveIdem(ctx, tx, u.ID, "conversation", key, ref)
		})
		if err != nil {
			return nil, err
		}
		s.publishConversation(ctx, ref.ConversationID, others...)
		for _, o := range others {
			s.pushConversationAdded(ctx, ref.ConversationID, o)
		}
	}
	return s.conversationCreated(ctx, ref)
}

func saveIdem(ctx context.Context, tx *store.Tx, userID uuid.UUID, scope, key string, v any) error {
	if key == "" {
		return nil
	}
	return tx.SaveIdempotent(ctx, userID, scope, key, v)
}

func (s *Server) conversationCreated(ctx context.Context, ref conversationRef) (gen.CreateConversationResponseObject, error) {
	c, err := s.conversationJSON(ctx, me(ctx), ref.ConversationID)
	if err != nil {
		return nil, err
	}
	if ref.Existing {
		return ok(c), nil
	}
	return created(c), nil
}

func (s *Server) GetConversation(ctx context.Context, req gen.GetConversationRequestObject) (gen.GetConversationResponseObject, error) {
	c, err := s.conversationJSON(ctx, me(ctx), req.ConversationId)
	if err != nil {
		return nil, err
	}
	return ok(c), nil
}

func (s *Server) UpdateConversation(ctx context.Context, req gen.UpdateConversationRequestObject) (gen.UpdateConversationResponseObject, error) {
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	if err := requireGroupAdmin(v); err != nil {
		return nil, err
	}
	if req.Body.Name != nil {
		name := strings.TrimSpace(*req.Body.Name)
		if name == "" || utf8.RuneCountInString(name) > maxGroupNameChars {
			return nil, problem.Validation("name", "length", "a group needs a name")
		}
		if err := s.st.InTx(ctx, func(tx *store.Tx) error { return tx.RenameConversation(ctx, v.ID, name) }); err != nil {
			return nil, err
		}
		s.publishConversation(ctx, v.ID)
	}
	c, err := s.conversationJSON(ctx, me(ctx), v.ID)
	if err != nil {
		return nil, err
	}
	return ok(c), nil
}

// putConversationPhoto is hand-written (binary body, like putAvatar; excluded in gen/cfg.yaml) and mounted in
// router.go. The photo is kept like an avatar (public, unguessable key).
func (s *Server) putConversationPhoto(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	convID, err := uuid.Parse(r.PathValue("conversationId"))
	if err != nil {
		problem.Write(w, r, problem.New(problem.NotFound, "conversation not found"))
		return
	}
	v, err := s.conversation(ctx, s.st.Q(), convID)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	if err := requireGroupAdmin(v); err != nil {
		s.writeError(w, r, err)
		return
	}
	key, err := s.storeSquarePhoto(ctx, r, "avatars/"+convID.String()+"/")
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	old, err := s.st.SetConversationPhoto(ctx, convID, &key)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	if old != nil {
		s.deleteBlob(ctx, *old)
	}
	s.publishConversation(ctx, convID)
	c, err := s.conversationJSON(ctx, me(ctx), convID)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, c)
}

func (s *Server) DeleteConversationPhoto(ctx context.Context, req gen.DeleteConversationPhotoRequestObject) (gen.DeleteConversationPhotoResponseObject, error) {
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	if err := requireGroupAdmin(v); err != nil {
		return nil, err
	}
	old, err := s.st.SetConversationPhoto(ctx, v.ID, nil)
	if err != nil {
		return nil, err
	}
	if old != nil {
		s.deleteBlob(ctx, *old)
		s.publishConversation(ctx, v.ID)
	}
	return noContent(), nil
}

func (s *Server) AddConversationMembers(ctx context.Context, req gen.AddConversationMembersRequestObject) (gen.AddConversationMembersResponseObject, error) {
	u := me(ctx)
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	if err := requireGroup(&v.Conversation); err != nil {
		return nil, err
	}
	for _, id := range req.Body.UserIds {
		if err := s.canMessage(ctx, s.st.Q(), u.ID, id); err != nil {
			return nil, err
		}
	}
	var added, sys []uuid.UUID
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		added, sys = nil, nil
		if err := tx.LockConversation(ctx, v.ID); err != nil {
			return err
		}
		var n int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM conversation_members WHERE conversation_id = $1`, v.ID).Scan(&n); err != nil {
			return err
		}
		for _, id := range req.Body.UserIds {
			ok, err := tx.AddConversationMember(ctx, v.ID, id, "member", &u.ID, true)
			if err != nil {
				return err
			}
			if ok {
				added = append(added, id)
				n++
			}
		}
		if n > maxGroupMembers {
			return problem.New(problem.LimitReached, "a group has 3 to 100 people")
		}
		for _, id := range added {
			mid, err := s.insertSystemMessage(ctx, tx, v.ID, gen.SystemMessage{Type: gen.SystemMessageTypeMemberJoined, Person: s.personRefOf(ctx, id)})
			if err != nil {
				return err
			}
			sys = append(sys, mid)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	if len(added) > 0 {
		s.publishConversation(ctx, v.ID)
		s.publishSystemMessages(ctx, v.ID, sys)
		for _, id := range added {
			s.pushConversationAdded(ctx, v.ID, id)
		}
	}
	c, err := s.conversationJSON(ctx, u, v.ID)
	if err != nil {
		return nil, err
	}
	return ok(c), nil
}

func (s *Server) UpdateConversationMember(ctx context.Context, req gen.UpdateConversationMemberRequestObject) (gen.UpdateConversationMemberResponseObject, error) {
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	if err := requireGroupAdmin(v); err != nil {
		return nil, err
	}
	role := string(req.Body.Role)
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.LockConversation(ctx, v.ID); err != nil {
			return err
		}
		found, err := tx.SetConversationRole(ctx, v.ID, req.UserId, role)
		if err != nil {
			return err
		}
		if !found {
			return problem.New(problem.NotFound, "member not found")
		}
		if n, err := s.st.CountConversationAdmins(ctx, tx, v.ID); err != nil {
			return err
		} else if n == 0 {
			return problem.New(problem.InvalidTransition, "a group needs an admin")
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	s.publishConversation(ctx, v.ID)
	c, err := s.conversationJSON(ctx, me(ctx), v.ID)
	if err != nil {
		return nil, err
	}
	return ok(c), nil
}

func (s *Server) RemoveConversationMember(ctx context.Context, req gen.RemoveConversationMemberRequestObject) (gen.RemoveConversationMemberResponseObject, error) {
	u := me(ctx)
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	if v.Kind != "group" {
		return nil, problem.New(problem.InvalidTransition, "this chat can't be left")
	}
	if req.UserId != u.ID && v.Role != "admin" {
		return nil, problem.New(problem.Forbidden, "group admins only")
	}
	var sys uuid.UUID
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.LockConversation(ctx, v.ID); err != nil {
			return err
		}
		found, _, err := tx.RemoveConversationMember(ctx, v.ID, req.UserId)
		if err != nil {
			return err
		}
		if !found {
			return problem.New(problem.NotFound, "member not found")
		}
		sys, err = s.insertSystemMessage(ctx, tx, v.ID, gen.SystemMessage{Type: gen.SystemMessageTypeMemberLeft, Person: s.personRefOf(ctx, req.UserId)})
		return err
	})
	if err != nil {
		return nil, err
	}
	s.notify.User(req.UserId, notifyEvent("conversation.removed", map[string]any{"conversation_id": v.ID}), nil)
	s.publishConversation(ctx, v.ID)
	s.publishSystemMessages(ctx, v.ID, []uuid.UUID{sys})
	s.notifyBadge(ctx, req.UserId)
	return noContent(), nil
}

func (s *Server) UpdateConversationPreferences(ctx context.Context, req gen.UpdateConversationPreferencesRequestObject) (gen.UpdateConversationPreferencesResponseObject, error) {
	u := me(ctx)
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	var p store.MemberPrefs
	if m := req.Body.Mute; m != nil {
		p.SetMute = true
		now := time.Now()
		switch *m {
		case gen.MuteForN1h:
			p.MutedUntil = ptr(now.Add(time.Hour))
		case gen.MuteForN8h:
			p.MutedUntil = ptr(now.Add(8 * time.Hour))
		case gen.MuteForN1w:
			p.MutedUntil = ptr(now.Add(7 * 24 * time.Hour))
		case gen.MuteForAlways:
			p.MuteAlways = true
		}
	}
	if req.Body.Archived != nil {
		p.SetArchived, p.Archived = true, *req.Body.Archived
	}
	if err := s.st.SetConversationPrefs(ctx, v.ID, u.ID, p); err != nil {
		return nil, err
	}
	c, err := s.conversationJSON(ctx, u, v.ID)
	if err != nil {
		return nil, err
	}
	s.notify.User(u.ID, notifyEvent("conversation.updated", map[string]any{"conversation": c}), nil)
	s.notifyBadge(ctx, u.ID)
	return ok(c), nil
}

// ---------------------------------------------------------------- contacts and people

func (s *Server) ListContacts(ctx context.Context, _ gen.ListContactsRequestObject) (gen.ListContactsResponseObject, error) {
	u := me(ctx)
	cs, err := s.st.Contacts(ctx, u.ID)
	if err != nil {
		return nil, err
	}
	out := make([]gen.Person, 0, len(cs))
	for _, c := range cs {
		p, err := s.personJSON(ctx, u.ID, c.UserID, s.personRef(c.UserID, c.DisplayName, c.AvatarKey, false), false)
		if err != nil {
			return nil, err
		}
		out = append(out, *p)
	}
	return ok(out), nil
}

func (s *Server) GetPerson(ctx context.Context, req gen.GetPersonRequestObject) (gen.GetPersonResponseObject, error) {
	u := me(ctx)
	if req.UserId == u.ID {
		p, err := s.personJSON(ctx, u.ID, u.ID, s.personRef(u.ID, u.DisplayName, u.AvatarKey, false), true)
		if err != nil {
			return nil, err
		}
		return ok(p), nil
	}
	isContact, err := s.st.AreContacts(ctx, s.st.Q(), u.ID, req.UserId)
	if err != nil {
		return nil, err
	}
	if !isContact {
		return nil, problem.New(problem.NotFound, "member not found")
	}
	other, err := s.st.GetUser(ctx, s.st.Q(), req.UserId)
	if err != nil {
		return nil, notFound(err, "member")
	}
	p, err := s.personJSON(ctx, u.ID, other.ID, s.personRef(other.ID, other.DisplayName, other.AvatarKey, false), false)
	if err != nil {
		return nil, err
	}
	return ok(p), nil
}

// personJSON is a contact's profile for the viewer; own: every milestone, else only the shown ones.
func (s *Server) personJSON(ctx context.Context, viewer, userID uuid.UUID, ref gen.PersonRef, own bool) (*gen.Person, error) {
	p := gen.Person{Person: ref, Feeds: []struct {
		FeedId   gen.Id `json:"feed_id"`
		FeedName string `json:"feed_name"`
	}{}, Frames: []struct {
		FrameId   gen.Id `json:"frame_id"`
		FrameName string `json:"frame_name"`
	}{}}
	feeds, err := s.st.SharedFeeds(ctx, viewer, userID)
	if err != nil {
		return nil, err
	}
	for _, f := range feeds {
		p.Feeds = append(p.Feeds, struct {
			FeedId   gen.Id `json:"feed_id"`
			FeedName string `json:"feed_name"`
		}{f.ID, f.Name})
	}
	frames, err := s.st.SharedFrames(ctx, viewer, userID)
	if err != nil {
		return nil, err
	}
	for _, f := range frames {
		p.Frames = append(p.Frames, struct {
			FrameId   gen.Id `json:"frame_id"`
			FrameName string `json:"frame_name"`
		}{f.ID, f.Name})
	}
	if viewer != userID {
		blocked, err := s.st.HasBlocked(ctx, s.st.Q(), viewer, userID)
		if err != nil {
			return nil, err
		}
		p.Blocked = blocked
		if id, err := s.st.DirectConversation(ctx, s.st.Q(), directKey(viewer, userID)); err == nil {
			p.DirectConversationId = &id
		} else if err != store.ErrNotFound {
			return nil, err
		}
	}
	// Milestones are engagement's table, read only here.
	rows, err := s.st.Q().Query(ctx, `SELECT key FROM user_milestones WHERE user_id = $1 AND ($2 OR shown) ORDER BY achieved_at`, userID, own)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ms := []gen.MilestoneKey{}
	for rows.Next() {
		var k string
		if err := rows.Scan(&k); err != nil {
			return nil, err
		}
		ms = append(ms, gen.MilestoneKey(k))
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	p.Milestones = &ms
	return &p, nil
}

func (s *Server) personRefOf(ctx context.Context, userID uuid.UUID) *gen.PersonRef {
	u, err := s.st.GetUser(ctx, s.st.Q(), userID)
	if err != nil {
		return &gen.PersonRef{UserId: userID, FormerMember: ptr(true)}
	}
	p := s.personRef(u.ID, u.DisplayName, u.AvatarKey, u.DeletedAt != nil)
	return &p
}

// ---------------------------------------------------------------- the badge

func (s *Server) badge(ctx context.Context, userID uuid.UUID) (gen.Badge, error) {
	convs, threads, err := s.st.BadgeCounts(ctx, userID)
	if err != nil {
		return gen.Badge{}, err
	}
	return gen.Badge{Conversations: convs, Threads: threads, Total: convs + threads}, nil
}

func (s *Server) GetBadge(ctx context.Context, _ gen.GetBadgeRequestObject) (gen.GetBadgeResponseObject, error) {
	s.noteOperator(ctx)
	b, err := s.badge(ctx, me(ctx).ID)
	if err != nil {
		return nil, err
	}
	s.chat.mu.Lock()
	s.chat.badges[me(ctx).ID] = b
	s.chat.mu.Unlock()
	return ok(b), nil
}

// noteOperator remembers operators (reports outside families go to them), at most once an hour per process.
func (s *Server) noteOperator(ctx context.Context) {
	if !isOperator(ctx) {
		return
	}
	id := me(ctx).ID
	s.chat.mu.Lock()
	last, seen := s.chat.operators[id]
	if !seen || time.Since(last) > time.Hour {
		s.chat.operators[id] = time.Now()
	}
	s.chat.mu.Unlock()
	if !seen || time.Since(last) > time.Hour {
		if err := s.st.NoteOperator(ctx, id); err != nil {
			s.log.Warn("note operator", "err", err)
		}
	}
}

// ---------------------------------------------------------------- families

// feedChats fills each feed's family chat (FeedSummary.conversation_id).
func (s *Server) feedChats(ctx context.Context, feeds []feedSummaryJSON) {
	ids := make([]uuid.UUID, len(feeds))
	for i := range feeds {
		ids[i] = feeds[i].ID
	}
	convs, err := s.st.FamilyConversations(ctx, ids)
	if err != nil {
		s.log.Warn("family chats", "err", err)
		return
	}
	for i := range feeds {
		if c, found := convs[feeds[i].ID]; found {
			feeds[i].ConversationID = &c
		}
	}
}

func (s *Server) feedChat(ctx context.Context, f *feedSummaryJSON) {
	one := []feedSummaryJSON{*f}
	s.feedChats(ctx, one)
	f.ConversationID = one[0].ConversationID
}
