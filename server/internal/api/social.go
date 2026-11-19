package api

import (
	"context"
	"net/http"
	"sort"
	"strings"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/i18n"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Family social: the home timeline, posts and their threads as the apps show them (docs/spec/family-social.md,
// README §5.18). Comments are in social_comments.go, people's reactions in social_reactions.go, frame entries in
// social_frames.go, pushes and the background loop in social_push.go, the feed hooks in social_feeds.go and
// circles in circles.go.

// Limits of family social (discovery `limits`).
const (
	maxCommentChars    = 2000
	maxCommentMentions = 20
	maxCirclesPerFeed  = 20
)

// ---------------------------------------------------------------- access

// visiblePost loads a post as the viewer sees it; 404 when it is unknown, gone or not theirs to see.
func (s *Server) visiblePost(ctx context.Context, viewer, postID uuid.UUID) (*store.SocialPost, error) {
	p, err := s.st.SocialPost(ctx, s.st.Q(), viewer, postID)
	if err == store.ErrNotFound || (err == nil && !p.Visible(viewer)) {
		return nil, problem.New(problem.NotFound, "post not found")
	}
	return p, err
}

// ---------------------------------------------------------------- people

// personRef renders a person next to a comment, reaction or post; deleted accounts are "Former member".
func (s *Server) socialPersonRef(id *uuid.UUID, name string, avatarKey *string, deleted bool) gen.PersonRef {
	out := gen.PersonRef{DisplayName: name, AvatarUrl: s.avatarURL(avatarKey)}
	if id != nil {
		out.UserId = *id
	}
	if deleted || id == nil {
		out.DisplayName, out.AvatarUrl, out.FormerMember = "", nil, ptr(true)
	}
	return out
}

// ---------------------------------------------------------------- bodies

// sealBody stores a comment body sealed when the server has an at-rest key (README §5.18 "Storage").
func (s *Server) sealCommentBody(text string) (*string, []byte) {
	if s.bodies != nil {
		return nil, s.bodies.Seal(text)
	}
	return &text, nil
}

// openBody returns a comment's body, sealed or plain; nil for tombstones and frame entries.
func (s *Server) openCommentBody(c *store.Comment) *string {
	if c.BodyEnc != nil {
		if s.bodies == nil {
			s.log.Warn("sealed comment body but no secrets key", "comment", c.ID)
			return nil
		}
		t, err := s.bodies.Open(c.BodyEnc)
		if err != nil {
			s.log.Warn("open comment body", "comment", c.ID, "err", err)
			return nil
		}
		return &t
	}
	return c.Body
}

// ---------------------------------------------------------------- reaction summaries

// emojiOrder breaks ties between equally frequent reactions.
var emojiOrder = map[string]int{"love": 0, "laugh": 1, "wow": 2, "sad": 3, "thumbs_up": 4, "pray": 5}

// viewerCtx is what rendering for one viewer needs besides the posts.
type viewerCtx struct {
	id       uuid.UUID
	blocked  map[uuid.UUID]bool // people the viewer blocked
	contacts map[uuid.UUID]bool // people sharing a family or frame with the viewer
	lang     string
}

func (s *Server) viewer(ctx context.Context, id uuid.UUID, people []uuid.UUID) (*viewerCtx, error) {
	v := &viewerCtx{id: id, contacts: map[uuid.UUID]bool{}}
	var err error
	if v.blocked, err = s.st.BlockedByUser(ctx, s.st.Q(), id); err != nil {
		return nil, err
	}
	if len(people) > 0 {
		if v.contacts, err = s.st.ContactsAmong(ctx, s.st.Q(), id, people); err != nil {
			return nil, err
		}
	}
	if u, err := s.st.GetUser(ctx, s.st.Q(), id); err == nil {
		v.lang = i18n.Pick(deref(u.Locale))
	} else {
		v.lang = i18n.Default
	}
	return v, nil
}

// reactionSummary builds the reaction row of one target for the viewer: people (newest first) and frames
// (newest first, at most one per frame and item as given) together.
func (s *Server) socialReactionSummary(v *viewerCtx, people []store.PersonReactionRow, frames []gen.FrameReactionRef) gen.ReactionSummary {
	out := gen.ReactionSummary{People: []struct {
		Emoji  gen.PersonReaction `json:"emoji"`
		Person gen.PersonRef      `json:"person"`
	}{}, Frames: []gen.FrameReactionRef{}, Counts: []struct {
		Count int                `json:"count"`
		Emoji gen.PersonReaction `json:"emoji"`
	}{}}
	counts := map[string]int{}
	for _, r := range people {
		counts[r.Emoji]++
		if r.UserID == v.id {
			e := gen.PersonReaction(r.Emoji)
			out.Mine = &e
		}
	}
	for _, f := range frames {
		counts[string(f.Reaction)]++
	}
	out.Total = len(people) + len(frames)
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
		return emojiOrder[string(out.Counts[i].Emoji)] < emojiOrder[string(out.Counts[j].Emoji)]
	})
	shown := make([]store.PersonReactionRow, 0, len(people))
	for _, r := range people {
		if !v.blocked[r.UserID] {
			shown = append(shown, r)
		}
	}
	sort.SliceStable(shown, func(i, j int) bool { return v.contacts[shown[i].UserID] && !v.contacts[shown[j].UserID] })
	for _, r := range shown {
		if len(out.People) == 3 {
			break
		}
		id := r.UserID
		out.People = append(out.People, struct {
			Emoji  gen.PersonReaction `json:"emoji"`
			Person gen.PersonRef      `json:"person"`
		}{gen.PersonReaction(r.Emoji), s.socialPersonRef(&id, r.Name, r.AvatarKey, r.Deleted)})
	}
	if len(frames) > 3 {
		frames = frames[:3]
	}
	out.Frames = append(out.Frames, frames...)
	return out
}

// frameRefs turns frames' reactions on posts into reaction-row entries: per post the newest reaction of each
// frame (item nil), or per post and item (item set).
func frameRefs(rows []store.FrameReactionRow, postID uuid.UUID, item *uuid.UUID) []gen.FrameReactionRef {
	out := []gen.FrameReactionRef{}
	seen := map[uuid.UUID]bool{}
	for _, r := range rows { // newest first
		if r.PostID != postID || (item != nil && r.ItemID != *item) || seen[r.FrameID] {
			continue
		}
		seen[r.FrameID] = true
		itemID, at := r.ItemID, r.At
		out = append(out, gen.FrameReactionRef{FrameId: r.FrameID, FrameName: r.FrameName, Reaction: gen.Reaction(r.Reaction), ItemId: &itemID, At: &at})
	}
	return out
}

// ---------------------------------------------------------------- comments

// commentOut renders a comment for the viewer of the post.
func (s *Server) commentOut(v *viewerCtx, p *store.SocialPost, c *store.Comment, reactions []store.PersonReactionRow) gen.Comment {
	out := gen.Comment{Id: c.ID, PostId: c.PostID, Kind: gen.CommentKind(c.Kind), ParentId: c.ParentID, CreatedAt: c.CreatedAt,
		EditedAt: c.EditedAt, CanEdit: ptr(false), CanDelete: ptr(false), Deleted: ptr(false), RemovedByAdmin: ptr(false)}
	if c.Kind == "frame" {
		f := &struct {
			FrameId    gen.Id           `json:"frame_id"`
			FrameName  string           `json:"frame_name"`
			ItemId     *gen.Id          `json:"item_id,omitempty"`
			Reaction   *gen.Reaction    `json:"reaction,omitempty"`
			Reply      *gen.ReplyPreset `json:"reply,omitempty"`
			ServerName *string          `json:"server_name,omitempty"` // frames on other servers (README §5.22)
		}{ItemId: c.ItemID}
		if c.FrameID != nil {
			f.FrameId = *c.FrameID
		}
		if c.FrameName != nil {
			f.FrameName = *c.FrameName
		}
		if c.FrameReaction != nil {
			f.Reaction = ptr(gen.Reaction(*c.FrameReaction))
		}
		if c.FrameReply != nil {
			f.Reply = ptr(gen.ReplyPreset(*c.FrameReply))
		}
		out.Frame = f
		return out
	}
	author := s.socialPersonRef(c.AuthorID, c.AuthorName, c.AuthorAvatarKey, c.AuthorDeleted)
	out.Author = &author
	if c.QuotedID != nil {
		q := s.socialPersonRef(c.QuotedID, c.QuotedName, c.QuotedAvatarKey, c.QuotedDeleted)
		out.Quoted = &q
	}
	mine := c.AuthorID != nil && *c.AuthorID == v.id
	switch {
	case c.DeletedAt != nil:
		out.Deleted = ptr(true)
		out.Mentions = &[]gen.Mention{}
	default:
		out.Body = s.openCommentBody(c)
		ms := make([]gen.Mention, len(c.Mentions))
		for i, m := range c.Mentions {
			ms[i] = gen.Mention{UserId: m.UserID, Start: m.Start, Length: m.Length}
		}
		out.Mentions = &ms
		if c.RemovedAt != nil {
			out.RemovedByAdmin = ptr(true)
		}
	}
	if c.ParentID == nil {
		out.ReplyCount = ptr(c.ReplyCount)
	}
	live := c.Live()
	out.CanEdit = ptr(live && mine)
	out.CanDelete = ptr(live && (mine || p.Moderator()))
	sum := s.socialReactionSummary(v, reactions, nil)
	out.Reactions = &sum
	return out
}

// commentsOut renders comments of one post with their reaction rows.
func (s *Server) commentsOut(ctx context.Context, v *viewerCtx, p *store.SocialPost, cs []store.Comment) ([]gen.Comment, error) {
	ids := make([]uuid.UUID, 0, len(cs))
	for _, c := range cs {
		if c.Kind == "person" {
			ids = append(ids, c.ID)
		}
	}
	rs, err := s.st.PersonReactionsOf(ctx, s.st.Q(), "comment", ids)
	if err != nil {
		return nil, err
	}
	by := map[uuid.UUID][]store.PersonReactionRow{}
	for _, r := range rs {
		by[r.TargetID] = append(by[r.TargetID], r)
	}
	out := make([]gen.Comment, len(cs))
	for i := range cs {
		out[i] = s.commentOut(v, p, &cs[i], by[cs[i].ID])
	}
	return out, nil
}

// ---------------------------------------------------------------- thread summaries

// threadSummaries renders the ThreadSummary of each post for the viewer.
func (s *Server) threadSummaries(ctx context.Context, v *viewerCtx, posts []store.SocialPost) (map[uuid.UUID]gen.ThreadSummary, error) {
	out := map[uuid.UUID]gen.ThreadSummary{}
	if len(posts) == 0 {
		return out, nil
	}
	ids := make([]uuid.UUID, len(posts))
	var framesFor []uuid.UUID
	for i := range posts {
		ids[i] = posts[i].ID
		if posts[i].SeesFrameReactions(v.id) {
			framesFor = append(framesFor, posts[i].ID)
		}
	}
	latest, err := s.st.LatestComments(ctx, s.st.Q(), v.id, ids)
	if err != nil {
		return nil, err
	}
	unread, err := s.st.ThreadUnread(ctx, s.st.Q(), v.id, ids)
	if err != nil {
		return nil, err
	}
	people, err := s.st.PersonReactionsOf(ctx, s.st.Q(), "post", ids)
	if err != nil {
		return nil, err
	}
	frames, err := s.st.FrameReactions(ctx, s.st.Q(), framesFor)
	if err != nil {
		return nil, err
	}
	byPost := map[uuid.UUID][]store.PersonReactionRow{}
	var reactors []uuid.UUID
	for _, r := range people {
		byPost[r.TargetID] = append(byPost[r.TargetID], r)
		reactors = append(reactors, r.UserID)
	}
	if len(reactors) > 0 {
		if v.contacts, err = s.st.ContactsAmong(ctx, s.st.Q(), v.id, reactors); err != nil {
			return nil, err
		}
	}
	for i := range posts {
		p := &posts[i]
		cs, err := s.commentsOut(ctx, v, p, latest[p.ID])
		if err != nil {
			return nil, err
		}
		u := unread[p.ID]
		out[p.ID] = gen.ThreadSummary{
			PostId: p.ID, State: gen.ThreadState(p.ThreadState), CommentCount: p.CommentCount, LatestComments: cs,
			UnreadCount: ptr(u.Unread), UnreadMentions: ptr(u.Mentions), ReadUpTo: p.ReadUpTo, Muted: ptr(p.Muted),
			Reactions:  s.socialReactionSummary(v, byPost[p.ID], frameRefs(frames, p.ID, nil)),
			CanComment: ptr(p.ThreadState == "open"), CanModerate: ptr(p.Moderator()),
		}
	}
	return out, nil
}

// ---------------------------------------------------------------- timeline posts

// timelinePosts renders posts (visible to the viewer) in the given order.
func (s *Server) timelinePosts(ctx context.Context, v *viewerCtx, posts []store.SocialPost) ([]gen.TimelinePost, error) {
	out := make([]gen.TimelinePost, len(posts))
	if len(posts) == 0 {
		return out, nil
	}
	idx := map[uuid.UUID]int{}
	var feedPosts, framePosts, receipts, prompts []uuid.UUID
	for i := range posts {
		p := &posts[i]
		idx[p.ID] = i
		t := gen.TimelinePost{Id: p.ID, Kind: gen.PostKind(p.Kind), FeedId: p.FeedID, FeedName: p.FeedName, CircleId: p.CircleID,
			CircleName: p.CircleName, FrameId: p.FrameID, FrameName: p.FrameName, CreatedAt: p.CreatedAt, Items: []gen.Item{},
			Poster: s.socialPersonRef(p.PosterID, p.PosterName, p.PosterAvatarKey, p.PosterDeleted),
			Via:    tgCredit(viaCredit(viaJSON(p.Via, p.ViaSourceID), p.ViaLabel, p.ViaEmailAddressID), p.ViaTelegramChatID)}
		if p.IsPoster(v.id) {
			t.SendId = p.SendID
		}
		if p.Kind == "feed" {
			feedPosts = append(feedPosts, p.ID)
			t.CanRemove = ptr(p.IsPoster(v.id) || p.Moderator())
			if p.SeesReceipts(v.id) {
				receipts = append(receipts, p.ID)
			}
			if p.PromptID != nil {
				prompts = append(prompts, *p.PromptID)
			}
		} else {
			framePosts = append(framePosts, p.ID)
			t.CanRemove = ptr(p.IsPoster(v.id))
		}
		out[i] = t
	}
	fitems, err := s.st.FeedPostItemRows(ctx, s.st.Q(), feedPosts)
	if err != nil {
		return nil, err
	}
	for i := range fitems {
		k := idx[fitems[i].PostID]
		out[k].Items = append(out[k].Items, s.itemJSON(&fitems[i].ItemRow))
	}
	pitems, err := s.st.FramePostItemRows(ctx, s.st.Q(), framePosts)
	if err != nil {
		return nil, err
	}
	seen := map[uuid.UUID]bool{}
	for i := range pitems {
		r := &pitems[i]
		k := idx[r.PostID]
		out[k].Items = append(out[k].Items, s.itemJSON(&r.ItemRow))
		if _, ok := seen[r.PostID]; !ok {
			seen[r.PostID] = true
		}
		seen[r.PostID] = seen[r.PostID] && r.PlacementStatus == "seen"
	}
	for id, all := range seen {
		n := 0
		if all {
			n = 1
		}
		out[idx[id]].Seen = &gen.PostSeen{Frames: 1, Seen: n}
	}
	rs, err := s.receiptsOf(ctx, receipts)
	if err != nil {
		return nil, err
	}
	for _, id := range receipts {
		ps := gen.PostSeen{}
		if pr := rs[id]; pr != nil {
			ps = gen.PostSeen{Frames: pr.summary.Frames, Seen: pr.summary.Seen}
		}
		out[idx[id]].Seen = &ps
	}
	titles, err := s.st.PromptTitles(ctx, s.st.Q(), prompts)
	if err != nil {
		return nil, err
	}
	for i := range posts {
		if posts[i].PromptID == nil {
			continue
		}
		pt, ok := titles[*posts[i].PromptID]
		if !ok || pt.Source == "skipped" {
			continue
		}
		title := deref(pt.Title)
		if pt.LibraryKey != nil {
			title = i18n.T(v.lang, "prompt."+*pt.LibraryKey, nil)
		}
		out[i].Prompt = &struct {
			Id    gen.Id `json:"id"`
			Title string `json:"title"`
		}{pt.ID, title}
	}
	threads, err := s.threadSummaries(ctx, v, posts)
	if err != nil {
		return nil, err
	}
	for i := range posts {
		out[i].Thread = threads[posts[i].ID]
	}
	return out, nil
}

// ---------------------------------------------------------------- handlers

func (s *Server) GetTimeline(ctx context.Context, req gen.GetTimelineRequestObject) (gen.GetTimelineResponseObject, error) {
	u := me(ctx)
	p := req.Params
	limit := 20
	if p.Limit != nil {
		limit = max(1, min(*p.Limit, 50))
	}
	f := store.TimelineFilter{FeedID: p.FeedId, CircleID: p.CircleId, PromptID: p.PromptId, FrameID: p.FrameId, Limit: limit + 1}
	if p.Before != nil && strings.TrimSpace(*p.Before) != "" {
		id, err := uuid.Parse(*p.Before)
		if err != nil {
			return nil, problem.Validation("before", "format", "invalid cursor").WithStatus(http.StatusBadRequest)
		}
		f.Before = &id
	}
	ids, err := s.st.TimelineIDs(ctx, u.ID, f)
	if err != nil {
		return nil, err
	}
	var next *string
	if len(ids) > limit {
		ids = ids[:limit]
		c := ids[limit-1].String()
		next = &c
	}
	posts, err := s.st.SocialPosts(ctx, s.st.Q(), u.ID, ids)
	if err != nil {
		return nil, err
	}
	visible := posts[:0]
	for _, sp := range posts {
		if sp.Visible(u.ID) {
			visible = append(visible, sp)
		}
	}
	v, err := s.viewer(ctx, u.ID, nil)
	if err != nil {
		return nil, err
	}
	out, err := s.timelinePosts(ctx, v, visible)
	if err != nil {
		return nil, err
	}
	return ok(gen.TimelinePage{Posts: out, NextBefore: next}), nil
}

func (s *Server) GetPost(ctx context.Context, req gen.GetPostRequestObject) (gen.GetPostResponseObject, error) {
	u := me(ctx)
	p, err := s.visiblePost(ctx, u.ID, req.PostId)
	if err != nil {
		return nil, err
	}
	out, err := s.renderPost(ctx, u.ID, p)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

// renderPost renders one visible post for the viewer.
func (s *Server) renderPost(ctx context.Context, viewer uuid.UUID, p *store.SocialPost) (gen.TimelinePost, error) {
	v, err := s.viewer(ctx, viewer, nil)
	if err != nil {
		return gen.TimelinePost{}, err
	}
	out, err := s.timelinePosts(ctx, v, []store.SocialPost{*p})
	if err != nil {
		return gen.TimelinePost{}, err
	}
	return out[0], nil
}
