package api

// Chat messages (README §5.19): rendering, sending (text, photos and videos, voice, shared posts, link
// previews), edits, deletes, "Put on a frame", read state and typing.

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"strings"
	"time"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/linkpreview"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// ---------------------------------------------------------------- rendering

// msgData is everything needed to render a set of messages for any viewer.
type msgData struct {
	msgs      map[uuid.UUID]store.Message
	items     map[uuid.UUID][]store.ItemRow
	voices    map[uuid.UUID]store.VoiceRow // by item id
	quotes    map[uuid.UUID]store.Message  // reply targets
	quoteItem map[uuid.UUID][]store.ItemRow
	previews  map[uuid.UUID]store.LinkPreview
	reactions map[uuid.UUID][]store.MessageReaction
	activity  map[uuid.UUID][]store.FrameActivity
	cards     map[[2]uuid.UUID]*gen.PostCard // (viewer, post)
}

func (s *Server) loadMessages(ctx context.Context, msgs []store.Message) (*msgData, error) {
	d := &msgData{msgs: map[uuid.UUID]store.Message{}, cards: map[[2]uuid.UUID]*gen.PostCard{}}
	var ids, quoteIDs, voiceIDs, previewIDs []uuid.UUID
	for _, m := range msgs {
		d.msgs[m.ID] = m
		ids = append(ids, m.ID)
		if m.ReplyToID != nil {
			quoteIDs = append(quoteIDs, *m.ReplyToID)
		}
		if m.VoiceItemID != nil {
			voiceIDs = append(voiceIDs, *m.VoiceItemID)
		}
		if m.LinkPreviewID != nil {
			previewIDs = append(previewIDs, *m.LinkPreviewID)
		}
	}
	q := s.st.Q()
	var err error
	if d.items, err = s.st.MessageItems(ctx, q, ids); err != nil {
		return nil, err
	}
	if d.voices, err = s.st.VoiceItems(ctx, q, voiceIDs); err != nil {
		return nil, err
	}
	if d.quotes, err = s.st.MessagesByID(ctx, q, quoteIDs); err != nil {
		return nil, err
	}
	if d.quoteItem, err = s.st.MessageItems(ctx, q, quoteIDs); err != nil {
		return nil, err
	}
	if d.previews, err = s.st.LinkPreviewsByID(ctx, q, previewIDs); err != nil {
		return nil, err
	}
	if d.reactions, err = s.st.MessageReactions(ctx, q, ids); err != nil {
		return nil, err
	}
	if d.activity, err = s.st.MessageFrameActivity(ctx, q, ids); err != nil {
		return nil, err
	}
	return d, nil
}

// msgViewer is who a message is rendered for.
type msgViewer struct {
	ID          uuid.UUID
	Blocked     map[uuid.UUID]bool // people the viewer blocked
	VisibleFrom *time.Time
}

func (s *Server) chatViewer(ctx context.Context, userID uuid.UUID, visibleFrom *time.Time) (msgViewer, error) {
	b, err := s.st.BlockedByUser(ctx, s.st.Q(), userID)
	return msgViewer{ID: userID, Blocked: b, VisibleFrom: visibleFrom}, err
}

func emptyReactions() gen.ReactionSummary {
	return gen.ReactionSummary{Counts: []struct {
		Count int                `json:"count"`
		Emoji gen.PersonReaction `json:"emoji"`
	}{}, People: []struct {
		Emoji  gen.PersonReaction `json:"emoji"`
		Person gen.PersonRef      `json:"person"`
	}{}, Frames: []gen.FrameReactionRef{}}
}

// reactionSummary: people only (frames react to placements, shown as frame_activity); people the viewer
// blocked are counted but not listed.
func (s *Server) reactionSummary(rs []store.MessageReaction, v msgViewer) gen.ReactionSummary {
	out := emptyReactions()
	counts := map[string]int{}
	for _, r := range rs {
		counts[r.Emoji]++
		out.Total++
		if r.UserID == v.ID {
			out.Mine = ptr(gen.PersonReaction(r.Emoji))
		}
		if !v.Blocked[r.UserID] && len(out.People) < 3 {
			out.People = append(out.People, struct {
				Emoji  gen.PersonReaction `json:"emoji"`
				Person gen.PersonRef      `json:"person"`
			}{gen.PersonReaction(r.Emoji), s.personRef(r.UserID, r.DisplayName, r.AvatarKey, r.Deleted)})
		}
	}
	for e, n := range counts {
		out.Counts = append(out.Counts, struct {
			Count int                `json:"count"`
			Emoji gen.PersonReaction `json:"emoji"`
		}{n, gen.PersonReaction(e)})
	}
	sort.Slice(out.Counts, func(i, j int) bool {
		if out.Counts[i].Count != out.Counts[j].Count {
			return out.Counts[i].Count > out.Counts[j].Count
		}
		return out.Counts[i].Emoji < out.Counts[j].Emoji
	})
	return out
}

func (s *Server) authorRef(m *store.Message) *gen.PersonRef {
	if m.AuthorUserID == nil {
		return nil
	}
	p := s.personRef(*m.AuthorUserID, deref(m.AuthorName), m.AuthorAvatarKey, m.AuthorDeleted)
	return &p
}

func parseMentions(raw json.RawMessage) []gen.Mention {
	out := []gen.Mention{}
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &out)
	}
	return out
}

func excerpt(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "…"
}

// messageView renders one message of d for a viewer.
func (s *Server) messageView(ctx context.Context, d *msgData, id uuid.UUID, v msgViewer) gen.Message {
	m := d.msgs[id]
	out := gen.Message{
		Id: m.ID, ConversationId: m.ConversationID, Kind: gen.MessageKind(m.Kind), CreatedAt: m.CreatedAt, EditedAt: m.EditedAt,
		Items: []gen.Item{}, Reactions: emptyReactions(), Author: s.authorRef(&m), Mentions: &[]gen.Mention{},
	}
	if m.AuthorUserID != nil && *m.AuthorUserID == v.ID {
		out.ClientId = m.ClientID
	}
	if m.SystemType != nil {
		sm := gen.SystemMessage{}
		if len(m.SystemData) > 0 {
			_ = json.Unmarshal(m.SystemData, &sm)
		}
		sm.Type = gen.SystemMessageType(*m.SystemType)
		out.System = &sm
	}
	if m.DeletedAt != nil {
		out.Deleted = true
		out.RemovedByAdmin = ptr(m.RemovedBy != nil)
		return out
	}
	out.RemovedByAdmin = ptr(false)
	out.Body = s.openBody(m.Body, m.BodyEnc)
	ms := parseMentions(m.Mentions)
	out.Mentions = &ms
	for i := range d.items[m.ID] {
		out.Items = append(out.Items, s.itemJSON(&d.items[m.ID][i]))
	}
	if m.VoiceItemID != nil {
		if vr, ok := d.voices[*m.VoiceItemID]; ok {
			vc := gen.VoiceClip{ItemId: vr.ItemID, Status: gen.VoiceClipStatus(vr.Status), DurationMs: vr.DurationMS, Waveform: &[]int{}}
			if vr.Status == "ready" {
				wf := make([]int, len(vr.Waveform))
				for i, x := range vr.Waveform {
					wf[i] = int(x)
				}
				vc.Waveform = &wf
			}
			out.Voice = &vc
		}
	}
	if m.ReplyToID != nil {
		out.ReplyTo = s.quoteView(d, *m.ReplyToID, v)
	}
	if m.SharedPostID != nil {
		out.SharedPost = s.postCard(ctx, d, v.ID, *m.SharedPostID)
	}
	if m.LinkPreviewID != nil {
		if p, ok := d.previews[*m.LinkPreviewID]; ok {
			out.LinkPreview = s.linkPreviewJSON(&p, m.Body, m.BodyEnc)
		}
	}
	out.Reactions = s.reactionSummary(d.reactions[m.ID], v)
	if acts := d.activity[m.ID]; len(acts) > 0 {
		fa := make([]struct {
			FrameId   gen.Id             `json:"frame_id"`
			FrameName string             `json:"frame_name"`
			ItemId    gen.Id             `json:"item_id"`
			Reaction  *gen.Reaction      `json:"reaction,omitempty"`
			Replies   *[]gen.ReplyPreset `json:"replies,omitempty"`
		}, len(acts))
		for i, a := range acts {
			fa[i].FrameId, fa[i].FrameName, fa[i].ItemId = a.FrameID, a.FrameName, a.ItemID
			if a.Reaction != nil {
				fa[i].Reaction = ptr(gen.Reaction(*a.Reaction))
			}
			rp := make([]gen.ReplyPreset, len(a.Replies))
			for j, r := range a.Replies {
				rp[j] = gen.ReplyPreset(r)
			}
			fa[i].Replies = &rp
		}
		out.FrameActivity = &fa
	}
	return out
}

func (s *Server) quoteView(d *msgData, id uuid.UUID, v msgViewer) *gen.MessageQuote {
	q, ok := d.msgs[id]
	if !ok {
		q, ok = d.quotes[id]
	}
	out := &gen.MessageQuote{Id: id, Deleted: true}
	if !ok || q.DeletedAt != nil || (v.VisibleFrom != nil && q.CreatedAt.Before(*v.VisibleFrom)) ||
		(q.AuthorUserID != nil && v.Blocked[*q.AuthorUserID]) {
		return out
	}
	out.Deleted = false
	out.Author = s.authorRef(&q)
	if b := s.openBody(q.Body, q.BodyEnc); b != nil {
		out.Excerpt = ptr(excerpt(*b, 200))
	}
	out.Voice = ptr(q.VoiceItemID != nil)
	items := d.quoteItem[id]
	if len(items) == 0 {
		items = d.items[id]
	}
	if len(items) > 0 {
		it := s.itemJSON(&items[0])
		out.MediaKind = &it.Kind
		out.ThumbUrl = it.ThumbUrl
	}
	return out
}

// postCard is a shared post as the viewer may see it (README §5.19 "Shared posts").
func (s *Server) postCard(ctx context.Context, d *msgData, viewer, postID uuid.UUID) *gen.PostCard {
	key := [2]uuid.UUID{viewer, postID}
	if c, ok := d.cards[key]; ok {
		return c
	}
	c := &gen.PostCard{PostId: postID, Available: false}
	d.cards[key] = c
	a, err := s.chatPostAccess(ctx, viewer, postID)
	if err != nil {
		s.log.Warn("post access", "post", postID, "err", err)
		return c
	}
	if !a.Visible {
		return c
	}
	type cardRow struct {
		Kind       string     `db:"kind"`
		FeedName   *string    `db:"feed_name"`
		FrameName  *string    `db:"frame_name"`
		PosterID   *uuid.UUID `db:"poster_id"`
		PosterName *string    `db:"poster_name"`
		PosterAv   *string    `db:"poster_avatar"`
		PosterGone bool       `db:"poster_gone"`
		CreatedAt  time.Time  `db:"created_at"`
	}
	var r cardRow
	err = s.st.Q().QueryRow(ctx, `
		SELECT 'feed', f.name, NULL::text, p.poster_user_id, u.display_name, u.avatar_key, coalesce(u.deleted_at IS NOT NULL, false), p.created_at
		FROM feed_posts p JOIN feeds f ON f.id = p.feed_id LEFT JOIN users u ON u.id = p.poster_user_id
		WHERE p.id = $1 AND p.removed_at IS NULL
		UNION ALL
		SELECT 'frame', NULL::text, fr.name, p.sender_user_id, u.display_name, u.avatar_key, coalesce(u.deleted_at IS NOT NULL, false), p.created_at
		FROM frame_posts p JOIN frames fr ON fr.id = p.frame_id LEFT JOIN users u ON u.id = p.sender_user_id
		WHERE p.id = $1 AND p.removed_at IS NULL`, postID).Scan(&r.Kind, &r.FeedName, &r.FrameName, &r.PosterID, &r.PosterName, &r.PosterAv, &r.PosterGone, &r.CreatedAt)
	if err != nil {
		if !db.NotFound(err) {
			s.log.Warn("post card", "post", postID, "err", err)
		}
		return c
	}
	itemIDs, err := uuidsOf(s.st.Q().Query(ctx, `
		SELECT pi.item_id FROM feed_post_items pi WHERE pi.post_id = $1 AND pi.removed_at IS NULL
		UNION ALL
		SELECT si.item_id FROM frame_posts fp JOIN send_items si ON si.send_id = fp.send_id
		WHERE fp.id = $1 AND EXISTS (SELECT 1 FROM placements pl WHERE pl.send_id = fp.send_id AND pl.frame_id = fp.frame_id
			AND pl.item_id = si.item_id AND pl.removed_at IS NULL)`, postID))
	if err != nil {
		s.log.Warn("post card items", "post", postID, "err", err)
		return c
	}
	c.Available = true
	c.Kind = ptr(gen.PostKind(r.Kind))
	c.FeedName, c.FrameName, c.CreatedAt = r.FeedName, r.FrameName, &r.CreatedAt
	if r.PosterID != nil {
		c.Poster = ptr(s.personRef(*r.PosterID, deref(r.PosterName), r.PosterAv, r.PosterGone))
	}
	c.ItemCount = ptr(len(itemIDs))
	thumbs := []string{}
	for i, id := range itemIDs {
		row, err := s.st.GetItemRow(ctx, s.st.Q(), id)
		if err != nil {
			continue
		}
		if i == 0 && row.Caption != nil {
			c.Caption = row.Caption
		}
		if len(thumbs) < 4 && row.Thumb256Key != nil {
			thumbs = append(thumbs, s.signedURL(*row.Thumb256Key, thumbURLTTL))
		}
	}
	c.ThumbUrls = &thumbs
	return c
}

func uuidsOf(rows interface {
	Next() bool
	Scan(...any) error
	Err() error
	Close()
}, err error) ([]uuid.UUID, error) {
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// renderMessages renders stored messages (in order) for the viewer.
func (s *Server) renderMessages(ctx context.Context, msgs []store.Message, v msgViewer) ([]gen.Message, error) {
	out := make([]gen.Message, len(msgs))
	if len(msgs) == 0 {
		return out, nil
	}
	d, err := s.loadMessages(ctx, msgs)
	if err != nil {
		return nil, err
	}
	for i, m := range msgs {
		out[i] = s.messageView(ctx, d, m.ID, v)
	}
	return out, nil
}

// lastMessages renders each conversation's latest message the viewer may see.
func (s *Server) lastMessages(ctx context.Context, viewer uuid.UUID, convIDs []uuid.UUID) (map[uuid.UUID]gen.Message, error) {
	ids, err := uuidsOf(s.st.Q().Query(ctx, `
		SELECT lm.id FROM conversation_members cm
		CROSS JOIN LATERAL (SELECT m.id FROM messages m WHERE m.conversation_id = cm.conversation_id
			AND (cm.visible_from IS NULL OR m.created_at >= cm.visible_from)
			AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = $1 AND b.blocked_user_id = m.author_user_id)
			ORDER BY m.id DESC LIMIT 1) lm
		WHERE cm.user_id = $1 AND cm.conversation_id = ANY($2)`, viewer, convIDs))
	if err != nil {
		return nil, err
	}
	out := map[uuid.UUID]gen.Message{}
	if len(ids) == 0 {
		return out, nil
	}
	byID, err := s.st.MessagesByID(ctx, s.st.Q(), ids)
	if err != nil {
		return nil, err
	}
	msgs := make([]store.Message, 0, len(byID))
	for _, m := range byID {
		msgs = append(msgs, m)
	}
	v, err := s.chatViewer(ctx, viewer, nil)
	if err != nil {
		return nil, err
	}
	rendered, err := s.renderMessages(ctx, msgs, v)
	if err != nil {
		return nil, err
	}
	for _, m := range rendered {
		out[m.ConversationId] = m
	}
	return out, nil
}

// messageFor renders one message for the caller, 404 when they may not see it.
func (s *Server) messageFor(ctx context.Context, cv *store.ConversationView, id uuid.UUID) (*gen.Message, *store.Message, error) {
	m, err := s.st.GetMessage(ctx, s.st.Q(), cv.ID, id)
	if err == store.ErrNotFound {
		return nil, nil, problem.New(problem.NotFound, "message not found")
	}
	if err != nil {
		return nil, nil, err
	}
	v, err := s.chatViewer(ctx, me(ctx).ID, cv.VisibleFrom)
	if err != nil {
		return nil, nil, err
	}
	if !visibleTo(m, v) {
		return nil, nil, problem.New(problem.NotFound, "message not found")
	}
	out, err := s.renderMessages(ctx, []store.Message{*m}, v)
	if err != nil {
		return nil, nil, err
	}
	return &out[0], m, nil
}

func visibleTo(m *store.Message, v msgViewer) bool {
	if v.VisibleFrom != nil && m.CreatedAt.Before(*v.VisibleFrom) {
		return false
	}
	return m.AuthorUserID == nil || !v.Blocked[*m.AuthorUserID]
}

// ---------------------------------------------------------------- validation

// cleanBody trims a body and shifts mention offsets by what was cut at the start; nil when empty.
func cleanBody(body *string, mentions []gen.Mention) (*string, []gen.Mention) {
	if body == nil {
		return nil, nil
	}
	t := strings.TrimSpace(*body)
	if t == "" {
		return nil, nil
	}
	lead := len(utf16.Encode([]rune((*body)[:strings.Index(*body, t)])))
	out := make([]gen.Mention, len(mentions))
	for i, m := range mentions {
		out[i] = gen.Mention{UserId: m.UserId, Start: m.Start - lead, Length: m.Length}
	}
	return &t, out
}

// checkMentions: spans inside the body (UTF-16 units), not overlapping, at most maxMentions, of members.
func checkMentions(body string, mentions []gen.Mention, member func(uuid.UUID) bool) ([]uuid.UUID, error) {
	if len(mentions) > maxMentions {
		return nil, problem.Validation("mentions", "maxItems", "")
	}
	n := len(utf16.Encode([]rune(body)))
	sorted := append([]gen.Mention(nil), mentions...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].Start < sorted[j].Start })
	end := 0
	seen := map[uuid.UUID]bool{}
	var users []uuid.UUID
	for _, m := range sorted {
		if m.Start < 0 || m.Length < 1 || m.Start+m.Length > n || m.Start < end {
			return nil, problem.Validation("mentions", "span", "")
		}
		end = m.Start + m.Length
		if !member(m.UserId) {
			return nil, problem.Validation("mentions", "not_member", "")
		}
		if !seen[m.UserId] {
			seen[m.UserId] = true
			users = append(users, m.UserId)
		}
	}
	return users, nil
}

func (s *Server) memberSet(ctx context.Context, convID uuid.UUID) (map[uuid.UUID]store.ConversationMember, error) {
	ms, err := s.st.ConversationMembers(ctx, s.st.Q(), []uuid.UUID{convID})
	if err != nil {
		return nil, err
	}
	out := map[uuid.UUID]store.ConversationMember{}
	for _, m := range ms[convID] {
		out[m.UserID] = m
	}
	return out, nil
}

// canSend: the conversation is not archived and (direct) the other side can still be messaged.
func (s *Server) canSend(ctx context.Context, v *store.ConversationView, members map[uuid.UUID]store.ConversationMember) error {
	if v.ArchivedAt != nil {
		return problem.New(problem.InvalidTransition, "this chat is read-only")
	}
	if v.Kind == "direct" {
		list := make([]store.ConversationMember, 0, len(members))
		for _, m := range members {
			list = append(list, m)
		}
		ro, err := s.readOnly(ctx, me(ctx).ID, &v.Conversation, list)
		if err != nil {
			return err
		}
		if ro {
			return problem.New(problem.CannotMessage, "")
		}
	}
	return nil
}

// ---------------------------------------------------------------- read

func (s *Server) ListMessages(ctx context.Context, req gen.ListMessagesRequestObject) (gen.ListMessagesResponseObject, error) {
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	p := req.Params
	if p.Before != nil && p.After != nil {
		return nil, problem.Validation("before", "exclusive", "")
	}
	q := store.MessagePageQuery{ConversationID: v.ID, Viewer: me(ctx).ID, VisibleFrom: v.VisibleFrom, Limit: pageLimit(p.Limit)}
	if p.Before != nil {
		id, err := uuid.Parse(string(*p.Before))
		if err != nil {
			return nil, problem.Validation("before", "format", "invalid cursor")
		}
		q.Before = &id
	}
	if p.After != nil {
		id := *p.After
		q.After = &id
	}
	msgs, before, after, err := s.st.MessagePage(ctx, q)
	if err != nil {
		return nil, err
	}
	viewer, err := s.chatViewer(ctx, me(ctx).ID, v.VisibleFrom)
	if err != nil {
		return nil, err
	}
	out, err := s.renderMessages(ctx, msgs, viewer)
	if err != nil {
		return nil, err
	}
	return ok(gen.MessagePage{Messages: out, HasMoreBefore: before, HasMoreAfter: after}), nil
}

func (s *Server) GetMessage(ctx context.Context, req gen.GetMessageRequestObject) (gen.GetMessageResponseObject, error) {
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	m, _, err := s.messageFor(ctx, v, req.MessageId)
	if err != nil {
		return nil, err
	}
	return ok(m), nil
}

// ---------------------------------------------------------------- send

type messageRef struct {
	MessageID uuid.UUID `json:"message_id"`
}

// voiceUpload is a checked voice recording upload.
type voiceUpload struct {
	upload *store.Upload
	path   string
	mime   string
	durMS  int
}

func (s *Server) prepareVoice(ctx context.Context, uploadID string) (*voiceUpload, error) {
	if !s.chat.voice {
		return nil, problem.New(problem.FeatureDisabled, "voice messages are not available on this server")
	}
	up, err := s.st.GetUpload(ctx, s.st.Q(), uploadID)
	if err != nil || up.UserID != me(ctx).ID || up.CompletedAt == nil || up.ConsumedAt != nil {
		return nil, problem.New(problem.UploadNotFound, "upload not found, incomplete or already used").WithField("voice.upload_id", "upload_not_found")
	}
	path := s.uploadPath(up.ID)
	st, err := os.Stat(path)
	if err != nil {
		return nil, problem.New(problem.UploadNotFound, "upload not found, incomplete or already used").WithField("voice.upload_id", "upload_not_found")
	}
	if st.Size() > media.MaxVoiceBytes {
		return nil, problem.New(problem.UploadTooLarge, "voice messages are limited to 20 MB").WithField("voice.upload_id", "upload_too_large")
	}
	mime, err := media.SniffAudio(path)
	if err != nil {
		return nil, problem.New(problem.UnsupportedMedia, "this file type is not supported").WithField("voice.upload_id", "unsupported_media")
	}
	info, err := s.tools.ProbeAudio(ctx, path)
	if err != nil {
		return nil, problem.New(problem.UnsupportedMedia, "this file type is not supported").WithField("voice.upload_id", "unsupported_media")
	}
	if info.DurationMS < media.MinVoiceMS {
		return nil, problem.New(problem.ValidationFailed, "a voice message is at least 1 second long").WithField("voice.upload_id", "too_short")
	}
	if info.DurationMS > maxVoiceSeconds*1000 {
		return nil, problem.New(problem.AudioTooLong, "voice messages can be at most %d seconds", maxVoiceSeconds).WithField("voice.upload_id", "audio_too_long")
	}
	return &voiceUpload{upload: up, path: path, mime: mime, durMS: info.DurationMS}, nil
}

// prepareChatItems checks photo and video uploads like a send's.
func (s *Server) prepareChatItems(ctx context.Context, items []gen.MessageItemCreate) ([]preparedItem, error) {
	raw, err := json.Marshal(items)
	if err != nil {
		return nil, err
	}
	var body gen.CreateSendJSONRequestBody
	if err := json.Unmarshal(raw, &body.Items); err != nil {
		return nil, err
	}
	return s.prepareItems(ctx, &body)
}

// createChatItems makes the items of a message (one media per new upload, reusing the caller's media for
// duplicates), shared with the conversation.
func (s *Server) createChatItems(ctx context.Context, tx *store.Tx, convID uuid.UUID, items []preparedItem) ([]uuid.UUID, error) {
	u := me(ctx)
	ids := make([]uuid.UUID, len(items))
	for i, p := range items {
		itemID := store.NewID()
		ids[i] = itemID
		var mediaID uuid.UUID
		if p.reuse != nil {
			mediaID = p.reuse.MediaID
		} else {
			consumed, err := tx.ConsumeUpload(ctx, p.upload.ID, u.ID)
			if err != nil {
				return nil, err
			}
			if !consumed {
				return nil, problem.New(problem.UploadNotFound, "upload already used").WithField(fmt.Sprintf("items.%d.upload_id", i), "upload_not_found")
			}
			mediaID = store.NewID()
			path, mime := p.path, p.mime
			md := store.Media{
				ID: mediaID, OwnerUserID: &u.ID, Kind: string(p.kind), SourcePath: &path, SourceMime: &mime, OriginalSHA256: preparedOriginal(p.upload),
				TrimStartMS: p.trimS, TrimEndMS: p.trimE, IncludeAudio: p.audio, DurationMS: p.durMS,
				Prepared: p.upload.Prepared, DeclaredWidth: p.decl.Width, DeclaredHeight: p.decl.Height,
			}
			if p.kind == media.Video {
				md.DeclaredDurationMS = p.decl.DurationMS
			}
			if err := tx.CreateMedia(ctx, md); err != nil {
				return nil, err
			}
			if err := tx.Enqueue(ctx, store.JobProcessMedia, map[string]any{"media_id": mediaID}); err != nil {
				return nil, err
			}
		}
		motionID, err := s.createMotion(ctx, tx, i, p)
		if err != nil {
			return nil, err
		}
		if err := tx.CreateItem(ctx, store.Item{ID: itemID, MediaID: mediaID, SenderUserID: &u.ID, Caption: p.caption,
			FocalX: p.focalX, FocalY: p.focalY, Fit: p.fit, CapturedHint: p.hint, MotionMediaID: motionID}); err != nil {
			return nil, err
		}
		if p.reuse != nil {
			if err := tx.Enqueue(ctx, store.JobThumbs, map[string]any{"item_id": itemID}); err != nil {
				return nil, err
			}
		}
	}
	if len(ids) > 0 {
		if err := tx.SetItemConversation(ctx, ids, &convID); err != nil {
			return nil, err
		}
	}
	return ids, nil
}

// linkPreviewFor returns the preview row for the body's first link (nil when previews are off or there is
// none), and whether it must be fetched.
func (s *Server) linkPreviewFor(ctx context.Context, body *string) (*store.LinkPreview, bool, error) {
	if s.chat.previews == nil || body == nil {
		return nil, false, nil
	}
	link := linkpreview.FirstURL(*body)
	if link == "" {
		return nil, false, nil
	}
	norm, err := linkpreview.Normalize(link)
	if err != nil {
		return nil, false, nil
	}
	return s.st.EnsureLinkPreview(ctx, norm)
}

func (s *Server) SendMessage(ctx context.Context, req gen.SendMessageRequestObject) (gen.SendMessageResponseObject, error) {
	u := me(ctx)
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev messageRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "message", key, &prev); err != nil {
			return nil, err
		} else if found {
			return s.sentMessage(ctx, v, prev.MessageID)
		}
	}
	b := req.Body
	if b.ClientId != nil && *b.ClientId != "" {
		if id, err := s.st.MessageByClientID(ctx, s.st.Q(), v.ID, u.ID, *b.ClientId); err == nil {
			return s.sentMessage(ctx, v, id)
		} else if err != store.ErrNotFound {
			return nil, err
		}
	}
	members, err := s.memberSet(ctx, v.ID)
	if err != nil {
		return nil, err
	}
	if err := s.canSend(ctx, v, members); err != nil {
		return nil, err
	}
	var mentionsIn []gen.Mention
	if b.Mentions != nil {
		mentionsIn = *b.Mentions
	}
	body, mentions := cleanBody(b.Body, mentionsIn)
	var items []gen.MessageItemCreate
	if b.Items != nil {
		items = *b.Items
	}
	if body == nil && len(items) == 0 && b.Voice == nil && b.SharedPostId == nil {
		return nil, problem.Validation("body", "required", "a message needs text, photos, a voice message or a post")
	}
	if body != nil && utf8.RuneCountInString(*body) > maxMessageChars {
		return nil, problem.Validation("body", "maxLength", "")
	}
	var mentionIDs []uuid.UUID
	if len(mentions) > 0 {
		if body == nil {
			return nil, problem.Validation("mentions", "span", "")
		}
		if mentionIDs, err = checkMentions(*body, mentions, func(id uuid.UUID) bool { _, ok := members[id]; return ok }); err != nil {
			return nil, err
		}
	}
	viewer, err := s.chatViewer(ctx, u.ID, v.VisibleFrom)
	if err != nil {
		return nil, err
	}
	if b.ReplyToId != nil {
		q, err := s.st.GetMessage(ctx, s.st.Q(), v.ID, *b.ReplyToId)
		if err != nil || !visibleTo(q, viewer) {
			return nil, problem.Validation("reply_to_id", "not_found", "")
		}
	}
	if b.SharedPostId != nil {
		a, err := s.chatPostAccess(ctx, u.ID, *b.SharedPostId)
		if err != nil {
			return nil, err
		}
		if !a.Visible {
			return nil, problem.Validation("shared_post_id", "not_found", "")
		}
	}
	if len(items) > maxMessageItems {
		return nil, problem.New(problem.TooManyItems, "at most %d items per send", maxMessageItems)
	}
	var voice *voiceUpload
	if b.Voice != nil {
		if len(items) > 0 {
			return nil, problem.Validation("voice", "with_items", "voice messages can't have photos or videos")
		}
		if voice, err = s.prepareVoice(ctx, b.Voice.UploadId); err != nil {
			return nil, err
		}
	}
	prepared, err := s.prepareChatItems(ctx, items)
	if err != nil {
		return nil, err
	}
	var preview *store.LinkPreview
	fetch := false
	if b.LinkPreview == nil || *b.LinkPreview {
		if preview, fetch, err = s.linkPreviewFor(ctx, body); err != nil {
			return nil, err
		}
	}
	msgID := store.NewID()
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		itemIDs, err := s.createChatItems(ctx, tx, v.ID, prepared)
		if err != nil {
			return err
		}
		var voiceItem *uuid.UUID
		if voice != nil {
			if voiceItem, err = s.createVoiceItem(ctx, tx, v.ID, voice); err != nil {
				return err
			}
		}
		raw, _ := json.Marshal(mentions)
		if len(mentions) == 0 {
			raw = []byte(`[]`)
		}
		plain, enc := s.sealBody(body)
		nm := store.NewMessage{ID: msgID, ConversationID: v.ID, Kind: "person", AuthorUserID: &u.ID, ClientID: b.ClientId,
			Body: plain, BodyEnc: enc, Mentions: raw, MentionIDs: mentionIDs, ReplyToID: b.ReplyToId, SharedPostID: b.SharedPostId,
			VoiceItemID: voiceItem}
		if b.ClientId != nil && *b.ClientId == "" {
			nm.ClientID = nil
		}
		if preview != nil {
			nm.LinkPreviewID = &preview.ID
		}
		if err := tx.CreateMessage(ctx, nm); err != nil {
			return err
		}
		for i, id := range itemIDs {
			if err := tx.AddMessageItem(ctx, msgID, id, i); err != nil {
				return err
			}
		}
		if fetch {
			if err := tx.Enqueue(ctx, store.JobChatLinkPreview, map[string]any{"preview_id": preview.ID}); err != nil {
				return err
			}
		}
		// The author has read their own message.
		if _, err := tx.Exec(ctx, `UPDATE conversation_members SET last_read_message_id = $3, last_read_at = now()
			WHERE conversation_id = $1 AND user_id = $2`, v.ID, u.ID, msgID); err != nil {
			return err
		}
		return saveIdem(ctx, tx, u.ID, "message", key, messageRef{msgID})
	})
	if db.IsUniqueViolation(err, "messages_client_id_unique") && b.ClientId != nil {
		if id, err := s.st.MessageByClientID(ctx, s.st.Q(), v.ID, u.ID, *b.ClientId); err == nil {
			return s.sentMessage(ctx, v, id)
		}
	}
	if db.IsUniqueViolation(err, "idempotency_keys_pkey") {
		var prev messageRef
		if found, _ := store.IdempotentResult(ctx, s.st.Q(), u.ID, "message", key, &prev); found {
			return s.sentMessage(ctx, v, prev.MessageID)
		}
	}
	if err != nil {
		return nil, err
	}
	s.publishMessage(ctx, v.ID, msgID, true)
	return s.sentMessage(ctx, v, msgID)
}

func (s *Server) sentMessage(ctx context.Context, v *store.ConversationView, id uuid.UUID) (gen.SendMessageResponseObject, error) {
	m, _, err := s.messageFor(ctx, v, id)
	if err != nil {
		return nil, err
	}
	return created(m), nil
}

func (s *Server) createVoiceItem(ctx context.Context, tx *store.Tx, convID uuid.UUID, vu *voiceUpload) (*uuid.UUID, error) {
	u := me(ctx)
	consumed, err := tx.ConsumeUpload(ctx, vu.upload.ID, u.ID)
	if err != nil {
		return nil, err
	}
	if !consumed {
		return nil, problem.New(problem.UploadNotFound, "upload already used").WithField("voice.upload_id", "upload_not_found")
	}
	mediaID, itemID := store.NewID(), store.NewID()
	path, mime := vu.path, vu.mime
	if err := tx.CreateMedia(ctx, store.Media{ID: mediaID, OwnerUserID: &u.ID, Kind: string(media.Audio), SourcePath: &path,
		SourceMime: &mime, IncludeAudio: true, DurationMS: &vu.durMS}); err != nil {
		return nil, err
	}
	if err := tx.Enqueue(ctx, store.JobProcessMedia, map[string]any{"media_id": mediaID}); err != nil {
		return nil, err
	}
	if err := tx.CreateItem(ctx, store.Item{ID: itemID, MediaID: mediaID, SenderUserID: &u.ID}); err != nil {
		return nil, err
	}
	if err := tx.SetItemConversation(ctx, []uuid.UUID{itemID}, &convID); err != nil {
		return nil, err
	}
	return &itemID, nil
}

// ---------------------------------------------------------------- edit and delete

func (s *Server) UpdateMessage(ctx context.Context, req gen.UpdateMessageRequestObject) (gen.UpdateMessageResponseObject, error) {
	u := me(ctx)
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	_, m, err := s.messageFor(ctx, v, req.MessageId)
	if err != nil {
		return nil, err
	}
	if m.Kind != "person" {
		return nil, problem.New(problem.InvalidTransition, "system messages can't be changed")
	}
	if m.AuthorUserID == nil || *m.AuthorUserID != u.ID {
		return nil, problem.New(problem.Forbidden, "only the author can edit a message")
	}
	if m.DeletedAt != nil {
		return nil, problem.New(problem.InvalidTransition, "this message was deleted")
	}
	if time.Since(m.CreatedAt) > messageEditHours*time.Hour {
		return nil, problem.New(problem.InvalidTransition, "messages can be edited for %d hours", messageEditHours)
	}
	if v.ArchivedAt != nil {
		return nil, problem.New(problem.InvalidTransition, "this chat is read-only")
	}
	var mentionsIn []gen.Mention
	if req.Body.Mentions != nil {
		mentionsIn = *req.Body.Mentions
	}
	body, mentions := cleanBody(&req.Body.Body, mentionsIn)
	if body == nil {
		return nil, problem.Validation("body", "minLength", "")
	}
	if utf8.RuneCountInString(*body) > maxMessageChars {
		return nil, problem.Validation("body", "maxLength", "")
	}
	members, err := s.memberSet(ctx, v.ID)
	if err != nil {
		return nil, err
	}
	mentionIDs, err := checkMentions(*body, mentions, func(id uuid.UUID) bool { _, ok := members[id]; return ok })
	if err != nil {
		return nil, err
	}
	before := map[uuid.UUID]bool{}
	for _, x := range parseMentions(m.Mentions) {
		before[x.UserId] = true
	}
	var newly []uuid.UUID
	for _, id := range mentionIDs {
		if !before[id] {
			newly = append(newly, id)
		}
	}
	// The preview follows the first link: kept when it is the same, fetched when it changed.
	previewID := m.LinkPreviewID
	fetch := false
	if s.chat.previews != nil {
		var p *store.LinkPreview
		if p, fetch, err = s.linkPreviewFor(ctx, body); err != nil {
			return nil, err
		}
		if p == nil {
			previewID = nil
		} else {
			previewID = &p.ID
		}
	}
	raw, _ := json.Marshal(mentions)
	if len(mentions) == 0 {
		raw = []byte(`[]`)
	}
	plain, enc := s.sealBody(body)
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.EditMessage(ctx, m.ID, plain, enc, raw, mentionIDs, previewID); err != nil {
			return err
		}
		if fetch && previewID != nil {
			return tx.Enqueue(ctx, store.JobChatLinkPreview, map[string]any{"preview_id": *previewID})
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	s.publishMessage(ctx, v.ID, m.ID, false)
	s.pushMentions(ctx, v.ID, m.ID, newly)
	out, _, err := s.messageFor(ctx, v, m.ID)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

func (s *Server) DeleteMessage(ctx context.Context, req gen.DeleteMessageRequestObject) (gen.DeleteMessageResponseObject, error) {
	u := me(ctx)
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	_, m, err := s.messageFor(ctx, v, req.MessageId)
	if err != nil {
		return nil, err
	}
	if m.Kind != "person" {
		return nil, problem.New(problem.InvalidTransition, "system messages can't be changed")
	}
	var removedBy *uuid.UUID
	if m.AuthorUserID == nil || *m.AuthorUserID != u.ID {
		mod, err := s.isChatModerator(ctx, u.ID, &v.Conversation, v.Role)
		if err != nil {
			return nil, err
		}
		if !mod {
			return nil, problem.New(problem.Forbidden, "only the author or a moderator can delete this message")
		}
		removedBy = &u.ID
	}
	if err := s.removeMessage(ctx, m, removedBy); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// removeMessage tombstones a message (moderators: removedBy) and tells the members.
func (s *Server) removeMessage(ctx context.Context, m *store.Message, removedBy *uuid.UUID) error {
	var changed bool
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		var err error
		changed, err = tx.DeleteMessage(ctx, m.ID, removedBy)
		if err != nil || !changed {
			return err
		}
		if removedBy != nil {
			return tx.ResolveOpenReportsOn(ctx, "message", m.ID, *removedBy)
		}
		return nil
	})
	if err != nil || !changed {
		return err
	}
	s.publishMessage(ctx, m.ConversationID, m.ID, false)
	s.notifyConversationBadges(ctx, m.ConversationID)
	return nil
}

// ---------------------------------------------------------------- put on a frame

type sendRefMsg struct {
	SendID uuid.UUID `json:"send_id"`
}

func (s *Server) PutMessageOnFrame(ctx context.Context, req gen.PutMessageOnFrameRequestObject) (gen.PutMessageOnFrameResponseObject, error) {
	u := me(ctx)
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev sendRefMsg
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "message_send", key, &prev); err != nil {
			return nil, err
		} else if found {
			return s.putOnFrameResult(ctx, prev.SendID)
		}
	}
	_, m, err := s.messageFor(ctx, v, req.MessageId)
	if err != nil {
		return nil, err
	}
	if m.DeletedAt != nil {
		return nil, problem.New(problem.InvalidTransition, "this message was deleted")
	}
	rows, err := s.st.MessageItems(ctx, s.st.Q(), []uuid.UUID{m.ID})
	if err != nil {
		return nil, err
	}
	all := rows[m.ID]
	b := req.Body
	var chosen []store.ItemRow
	if b.ItemIds != nil && len(*b.ItemIds) > 0 {
		byID := map[uuid.UUID]store.ItemRow{}
		for _, r := range all {
			byID[r.ItemID] = r
		}
		seen := map[uuid.UUID]bool{}
		for _, id := range *b.ItemIds {
			r, found := byID[id]
			if !found {
				return nil, problem.Validation("item_ids", "not_in_message", "")
			}
			if !seen[id] {
				seen[id] = true
				chosen = append(chosen, r)
			}
		}
	} else {
		chosen = all
	}
	var live []store.ItemRow
	for _, r := range chosen {
		if r.MediaStatus != "failed" {
			live = append(live, r)
		}
	}
	if len(live) == 0 {
		return nil, problem.New(problem.InvalidTransition, "this message has no photos or videos")
	}
	var feedIDs, circleIDs []uuid.UUID
	if b.FeedIds != nil {
		feedIDs = *b.FeedIds
	}
	if b.CircleIds != nil {
		circleIDs = *b.CircleIds
	}
	if len(b.FrameIds) == 0 && len(feedIDs) == 0 {
		return nil, problem.Validation("frame_ids", "minItems", "choose at least one frame or feed")
	}
	own := m.AuthorUserID != nil && *m.AuthorUserID == u.ID
	if !own && len(feedIDs) > 0 {
		return nil, problem.New(problem.Forbidden, "photos from other people's messages can't be posted to families")
	}
	frames, members, err := s.sendTargets(ctx, b.FrameIds)
	if err != nil {
		return nil, err
	}
	feeds, err := s.feedTargets(ctx, feedIDs)
	if err != nil {
		return nil, err
	}
	for _, fid := range feeds {
		fv, err := s.feedView(ctx, s.st.Q(), fid)
		if err != nil {
			return nil, err
		}
		if fv.Role == "member" {
			return nil, problem.New(problem.MissingPermission, "members can't post photos to this family")
		}
	}
	circles, err := s.checkCircles(ctx, circleIDs, feeds)
	if err != nil {
		return nil, err
	}
	sendID := store.NewID()
	var frameNames, feedNames []string
	var sysID uuid.UUID
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.CreateSend(ctx, sendID, u.ID); err != nil {
			return err
		}
		items := make([]placeItem, len(live))
		itemIDs := make([]uuid.UUID, len(live))
		for i, r := range live {
			items[i] = placeItem{ItemID: r.ItemID, MediaID: r.MediaID, MotionMediaID: r.MotionMediaID, SendID: &sendID}
			itemIDs[i] = r.ItemID
			if own {
				if err := tx.AddSendItem(ctx, sendID, r.ItemID, i); err != nil {
					return err
				}
			}
		}
		// Other people's photos are placed like shares: credited to their sender, "shared by" the caller.
		var sharedBy *uuid.UUID
		if !own {
			sharedBy = &u.ID
		}
		created, _, err := s.placeOnFrames(ctx, tx, items, frames, members, sharedBy, false)
		if err != nil {
			return err
		}
		if err := ensureRenditionsFor(ctx, tx, created); err != nil {
			return err
		}
		if err := tx.TouchLastSent(ctx, frames, u.ID); err != nil {
			return err
		}
		if own {
			// A send by a person to frames is a frame post per frame (README §5.18; the table is the family
			// social area's, the row is the same one createSend makes).
			if _, err := tx.Exec(ctx, `INSERT INTO frame_posts (id, send_id, frame_id, sender_user_id)
				SELECT kinframe_uuidv7(clock_timestamp()), $1, f, $2 FROM unnest($3::uuid[]) AS f
				WHERE EXISTS (SELECT 1 FROM placements p WHERE p.send_id = $1 AND p.frame_id = f AND p.removed_at IS NULL)
				ON CONFLICT (send_id, frame_id) DO NOTHING`, sendID, u.ID, frames); err != nil {
				return err
			}
			if err := createPosts(ctx, s.st, tx, sendID, u.ID, feeds, itemIDs); err != nil {
				return err
			}
			for feedID, circleID := range circles {
				if _, err := tx.Exec(ctx, `UPDATE feed_posts SET circle_id = $3 WHERE feed_id = $1 AND send_id = $2 AND removed_at IS NULL`,
					feedID, sendID, circleID); err != nil {
					return err
				}
			}
		}
		if err := tx.LinkMessageSend(ctx, m.ID, sendID, u.ID); err != nil {
			return err
		}
		if frameNames, err = namesOf(ctx, tx, `SELECT name FROM frames WHERE id = ANY($1) ORDER BY name`, frames); err != nil {
			return err
		}
		if feedNames, err = namesOf(ctx, tx, `SELECT name FROM feeds WHERE id = ANY($1) ORDER BY name`, feeds); err != nil {
			return err
		}
		if sysID, err = s.insertSystemMessage(ctx, tx, v.ID, gen.SystemMessage{Type: gen.SystemMessageTypePutOnFrame,
			Person: s.personRefOf(ctx, u.ID), SendId: &sendID, SourceMessageId: &m.ID, ItemCount: ptr(len(live)),
			FrameNames: &frameNames, FeedNames: &feedNames}); err != nil {
			return err
		}
		return saveIdem(ctx, tx, u.ID, "message_send", key, sendRefMsg{sendID})
	})
	if db.IsUniqueViolation(err, "idempotency_keys_pkey") {
		var prev sendRefMsg
		if found, _ := store.IdempotentResult(ctx, s.st.Q(), u.ID, "message_send", key, &prev); found {
			return s.putOnFrameResult(ctx, prev.SendID)
		}
	}
	if err != nil {
		return nil, err
	}
	s.publishSystemMessages(ctx, v.ID, []uuid.UUID{sysID})
	s.afterPersonalSend(ctx, sendID)
	return s.putOnFrameResult(ctx, sendID)
}

// putOnFrameResult is the send with every item it placed (other people's items are placements of the send
// without being its items: they stay out of the caller's sent items).
func (s *Server) putOnFrameResult(ctx context.Context, sendID uuid.UUID) (gen.PutMessageOnFrameResponseObject, error) {
	sd, err := s.renderSend(ctx, sendID)
	if err != nil {
		return nil, err
	}
	have := map[uuid.UUID]bool{}
	for _, it := range sd.Items {
		have[it.Id] = true
	}
	for _, f := range sd.Frames {
		for _, p := range f.Placements {
			if have[p.ItemID] {
				continue
			}
			have[p.ItemID] = true
			if row, err := s.st.GetItemRow(ctx, s.st.Q(), p.ItemID); err == nil {
				sd.Items = append(sd.Items, s.itemJSON(row))
			}
		}
	}
	return accepted(sd), nil
}

func namesOf(ctx context.Context, q db.DBTX, sql string, ids []uuid.UUID) ([]string, error) {
	out := []string{}
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := q.Query(ctx, sql, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var n string
		if err := rows.Scan(&n); err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}

// checkCircles validates circle audiences as for sends: at most one per feed, its feed among the targets, the
// caller in it (README §5.18). Returns feed -> circle.
func (s *Server) checkCircles(ctx context.Context, circleIDs, feeds []uuid.UUID) (map[uuid.UUID]uuid.UUID, error) {
	out := map[uuid.UUID]uuid.UUID{}
	if len(circleIDs) == 0 {
		return out, nil
	}
	inFeeds := map[uuid.UUID]bool{}
	for _, f := range feeds {
		inFeeds[f] = true
	}
	for _, cid := range circleIDs {
		var feedID uuid.UUID
		err := s.st.Q().QueryRow(ctx, `SELECT c.feed_id FROM circles c JOIN circle_members m ON m.circle_id = c.id AND m.user_id = $2
			WHERE c.id = $1 AND c.deleted_at IS NULL`, cid, me(ctx).ID).Scan(&feedID)
		if db.NotFound(err) {
			return nil, problem.Validation("circle_ids", "not_member", "")
		}
		if err != nil {
			return nil, err
		}
		if !inFeeds[feedID] {
			return nil, problem.Validation("circle_ids", "feed", "")
		}
		if _, dup := out[feedID]; dup {
			return nil, problem.Validation("circle_ids", "one_per_feed", "")
		}
		out[feedID] = cid
	}
	return out, nil
}

// ---------------------------------------------------------------- read state and typing

func (s *Server) MarkConversationRead(ctx context.Context, req gen.MarkConversationReadRequestObject) (gen.MarkConversationReadResponseObject, error) {
	u := me(ctx)
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	if _, err := s.st.GetMessage(ctx, s.st.Q(), v.ID, req.Body.MessageId); err != nil {
		return nil, notFoundMessage(err)
	}
	moved, err := s.st.MarkRead(ctx, v.ID, u.ID, req.Body.MessageId)
	if err != nil {
		return nil, err
	}
	if moved {
		s.publishRead(ctx, v, u, req.Body.MessageId)
		s.notifyBadge(ctx, u.ID)
	}
	return noContent(), nil
}

func notFoundMessage(err error) error {
	if err == store.ErrNotFound {
		return problem.New(problem.NotFound, "message not found")
	}
	return err
}

func (s *Server) SendTyping(ctx context.Context, req gen.SendTypingRequestObject) (gen.SendTypingResponseObject, error) {
	u := me(ctx)
	v, err := s.conversation(ctx, s.st.Q(), req.ConversationId)
	if err != nil {
		return nil, err
	}
	if v.ArchivedAt != nil {
		return noContent(), nil
	}
	if allowed, _ := s.chat.typing.Allow(u.ID.String() + ":" + v.ID.String()); !allowed {
		return noContent(), nil // more than one every 3 s: ignored
	}
	activity := "typing"
	if req.Body != nil && req.Body.Activity != nil {
		activity = string(*req.Body.Activity)
	}
	members, err := s.memberSet(ctx, v.ID)
	if err != nil {
		return nil, err
	}
	var to []uuid.UUID
	for id := range members {
		if id != u.ID && s.hub.UserConnected(id) {
			to = append(to, id)
		}
	}
	if len(to) == 0 {
		return noContent(), nil
	}
	blocked, err := s.st.BlockedEither(ctx, s.st.Q(), u.ID, to)
	if err != nil {
		return nil, err
	}
	ev := notifyEvent("typing", map[string]any{"conversation_id": v.ID, "user_id": u.ID, "display_name": u.DisplayName,
		"activity": activity, "expires_at": time.Now().Add(typingTTL).UTC()})
	for _, id := range to {
		if !blocked[id] {
			s.notify.User(id, ev, nil)
		}
	}
	return noContent(), nil
}
