package api

import (
	"context"
	"sort"
	"strings"
	"unicode"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Threads and comments (README §5.18 "Threads and comments").

// ---------------------------------------------------------------- validation

// commentBody trims a body and checks its length; shift is how many UTF-16 units were trimmed at the start
// (mentions are offsets into what the client sent).
func commentBody(raw string) (body string, shift int, err error) {
	left := strings.TrimLeftFunc(raw, unicode.IsSpace)
	shift = utf16Len(raw) - utf16Len(left)
	body = strings.TrimRightFunc(left, unicode.IsSpace)
	if body == "" {
		return "", 0, problem.Validation("body", "minLength", "a comment needs text")
	}
	if utf8.RuneCountInString(body) > maxCommentChars {
		return "", 0, problem.New(problem.ValidationFailed, "comments can be at most %d characters", maxCommentChars).WithField("body", "maxLength")
	}
	return body, shift, nil
}

func utf16Len(s string) int { return len(utf16.Encode([]rune(s))) }

// commentMentions validates mention spans against the (trimmed) body: inside it, not overlapping, at most
// maxCommentMentions, and only people who may see the post.
func (s *Server) commentMentions(ctx context.Context, postID uuid.UUID, body string, shift int, in *[]gen.Mention) ([]store.MentionSpan, error) {
	if in == nil || len(*in) == 0 {
		return nil, nil
	}
	if len(*in) > maxCommentMentions {
		return nil, problem.New(problem.ValidationFailed, "at most %d mentions per comment", maxCommentMentions).WithField("mentions", "maxItems")
	}
	n := utf16Len(body)
	out := make([]store.MentionSpan, 0, len(*in))
	for _, m := range *in {
		out = append(out, store.MentionSpan{UserID: m.UserId, Start: m.Start - shift, Length: m.Length})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Start < out[j].Start })
	end := 0
	for _, m := range out {
		if m.Start < end || m.Length < 1 || m.Start+m.Length > n {
			return nil, problem.Validation("mentions", "span", "a mention must lie inside the text and must not overlap another one")
		}
		end = m.Start + m.Length
	}
	seen := map[uuid.UUID]bool{}
	for _, m := range out {
		if seen[m.UserID] {
			continue
		}
		seen[m.UserID] = true
		p, err := s.st.SocialPost(ctx, s.st.Q(), m.UserID, postID)
		if err == store.ErrNotFound || (err == nil && !p.Visible(m.UserID)) {
			return nil, problem.Validation("mentions", "not_visible", "only people who can see the post can be mentioned")
		}
		if err != nil {
			return nil, err
		}
	}
	return out, nil
}

func mentionedUsers(ms []store.MentionSpan) []uuid.UUID {
	seen := map[uuid.UUID]bool{}
	var out []uuid.UUID
	for _, m := range ms {
		if !seen[m.UserID] {
			seen[m.UserID] = true
			out = append(out, m.UserID)
		}
	}
	return out
}

// threadComment loads a comment of a visible post; 404 when unknown, removed or deleted.
func (s *Server) threadComment(ctx context.Context, postID, id uuid.UUID) (*store.Comment, error) {
	c, err := s.st.GetComment(ctx, s.st.Q(), postID, id)
	if err == store.ErrNotFound || (err == nil && c.Kind == "person" && !c.Live()) {
		return nil, problem.New(problem.NotFound, "comment not found")
	}
	return c, err
}

// renderComment renders one comment for the caller.
func (s *Server) renderComment(ctx context.Context, p *store.SocialPost, id uuid.UUID) (gen.Comment, error) {
	c, err := s.st.GetComment(ctx, s.st.Q(), p.ID, id)
	if err != nil {
		return gen.Comment{}, err
	}
	v, err := s.viewer(ctx, me(ctx).ID, nil)
	if err != nil {
		return gen.Comment{}, err
	}
	out, err := s.commentsOut(ctx, v, p, []store.Comment{*c})
	if err != nil {
		return gen.Comment{}, err
	}
	return out[0], nil
}

// ---------------------------------------------------------------- handlers

func (s *Server) ListComments(ctx context.Context, req gen.ListCommentsRequestObject) (gen.ListCommentsResponseObject, error) {
	u := me(ctx)
	p, err := s.visiblePost(ctx, u.ID, req.PostId)
	if err != nil {
		return nil, err
	}
	limit := 100
	if req.Params.Limit != nil {
		limit = max(1, min(*req.Params.Limit, 200))
	}
	cs, err := s.st.ListComments(ctx, u.ID, p.ID, p.SeesFrameReactions(u.ID), req.Params.After, limit+1)
	if err != nil {
		return nil, err
	}
	more := len(cs) > limit
	if more {
		cs = cs[:limit]
	}
	var people []uuid.UUID
	for _, c := range cs {
		if c.AuthorID != nil {
			people = append(people, *c.AuthorID)
		}
	}
	v, err := s.viewer(ctx, u.ID, people)
	if err != nil {
		return nil, err
	}
	out, err := s.commentsOut(ctx, v, p, cs)
	if err != nil {
		return nil, err
	}
	return ok(gen.CommentPage{Comments: out, HasMore: more}), nil
}

type commentRef struct {
	CommentID uuid.UUID `json:"comment_id"`
}

func (s *Server) CreateComment(ctx context.Context, req gen.CreateCommentRequestObject) (gen.CreateCommentResponseObject, error) {
	u := me(ctx)
	p, err := s.visiblePost(ctx, u.ID, req.PostId)
	if err != nil {
		return nil, err
	}
	scope := "comment:" + p.ID.String()
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev commentRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, scope, key, &prev); err != nil {
			return nil, err
		} else if found {
			out, err := s.renderComment(ctx, p, prev.CommentID)
			if err != nil {
				return nil, err
			}
			return created(out), nil
		}
	}
	b := req.Body
	body, shift, err := commentBody(b.Body)
	if err != nil {
		return nil, err
	}
	mentions, err := s.commentMentions(ctx, p.ID, body, shift, b.Mentions)
	if err != nil {
		return nil, err
	}
	if b.ParentId != nil {
		parent, err := s.st.GetComment(ctx, s.st.Q(), p.ID, *b.ParentId)
		if err != nil && err != store.ErrNotFound {
			return nil, err
		}
		if err == store.ErrNotFound || parent.Kind != "person" || parent.ParentID != nil || !parent.Live() {
			return nil, problem.Validation("parent_id", "not_top_level", "a reply must be made to a top-level comment of this post")
		}
	}
	if b.QuotedUserId != nil {
		did, err := s.st.HasCommented(ctx, s.st.Q(), p.ID, *b.QuotedUserId)
		if err != nil {
			return nil, err
		}
		if b.ParentId == nil || !did {
			return nil, problem.Validation("quoted_user_id", "not_commenter", "the person you reply to must have commented on this post")
		}
	}
	plain, sealed := s.sealCommentBody(body)
	nc := store.NewComment{ID: store.NewID(), PostID: p.ID, ParentID: b.ParentId, AuthorID: u.ID, QuotedID: b.QuotedUserId,
		Body: plain, BodyEnc: sealed, Mentions: mentions}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.EnsureThread(ctx, p); err != nil {
			return err
		}
		state, err := tx.LockThread(ctx, p.ID)
		if err != nil {
			return err
		}
		if state == "closed" {
			return problem.New(problem.CommentsClosed, "")
		}
		if err := tx.CreateComment(ctx, nc); err != nil {
			return err
		}
		if err := tx.RecountThread(ctx, p.ID); err != nil {
			return err
		}
		if key != "" {
			return tx.SaveIdempotent(ctx, u.ID, scope, key, commentRef{nc.ID})
		}
		return nil
	})
	if db.IsUniqueViolation(err, "idempotency_keys_pkey") {
		var prev commentRef
		if found, _ := store.IdempotentResult(ctx, s.st.Q(), u.ID, scope, key, &prev); found {
			out, err := s.renderComment(ctx, p, prev.CommentID)
			if err != nil {
				return nil, err
			}
			return created(out), nil
		}
	}
	if err != nil {
		return nil, err
	}
	c, err := s.st.GetComment(ctx, s.st.Q(), p.ID, nc.ID)
	if err != nil {
		return nil, err
	}
	mentioned := mentionedUsers(mentions)
	pushes := s.commentPushes(ctx, p.ID, c, pushText(body), mentioned, false)
	s.publishComment(ctx, p.ID, c.ID, "comment.created", pushes)
	s.publishPostUpdated(ctx, p.ID)
	badge := mentioned
	if c.ParentID != nil {
		if parent, err := s.st.GetComment(ctx, s.st.Q(), p.ID, *c.ParentID); err == nil && parent.AuthorID != nil {
			badge = appendUnique(badge, *parent.AuthorID)
		}
	}
	if c.QuotedID != nil {
		badge = appendUnique(badge, *c.QuotedID)
	}
	s.notifyBadge(ctx, badge...)
	out, err := s.renderComment(ctx, p, c.ID)
	if err != nil {
		return nil, err
	}
	return created(out), nil
}

func (s *Server) UpdateComment(ctx context.Context, req gen.UpdateCommentRequestObject) (gen.UpdateCommentResponseObject, error) {
	u := me(ctx)
	p, err := s.visiblePost(ctx, u.ID, req.PostId)
	if err != nil {
		return nil, err
	}
	c, err := s.threadComment(ctx, p.ID, req.CommentId)
	if err != nil {
		return nil, err
	}
	if c.Kind == "frame" {
		return nil, problem.New(problem.Forbidden, "frame entries can't be changed")
	}
	if c.AuthorID == nil || *c.AuthorID != u.ID {
		return nil, problem.New(problem.Forbidden, "only the author can edit a comment")
	}
	body, shift, err := commentBody(req.Body.Body)
	if err != nil {
		return nil, err
	}
	mentions, err := s.commentMentions(ctx, p.ID, body, shift, req.Body.Mentions)
	if err != nil {
		return nil, err
	}
	before := map[uuid.UUID]bool{}
	for _, m := range c.Mentions {
		before[m.UserID] = true
	}
	var fresh []uuid.UUID
	for _, id := range mentionedUsers(mentions) {
		if !before[id] {
			fresh = append(fresh, id)
		}
	}
	plain, sealed := s.sealCommentBody(body)
	if err := s.st.InTx(ctx, func(tx *store.Tx) error { return tx.UpdateCommentBody(ctx, c.ID, plain, sealed, mentions) }); err != nil {
		return nil, err
	}
	c, err = s.st.GetComment(ctx, s.st.Q(), p.ID, c.ID)
	if err != nil {
		return nil, err
	}
	pushes := s.commentPushes(ctx, p.ID, c, pushText(body), fresh, true)
	s.publishComment(ctx, p.ID, c.ID, "comment.updated", pushes)
	s.publishPostUpdated(ctx, p.ID)
	s.notifyBadge(ctx, fresh...)
	out, err := s.renderComment(ctx, p, c.ID)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

func (s *Server) DeleteComment(ctx context.Context, req gen.DeleteCommentRequestObject) (gen.DeleteCommentResponseObject, error) {
	u := me(ctx)
	p, err := s.visiblePost(ctx, u.ID, req.PostId)
	if err != nil {
		return nil, err
	}
	c, err := s.threadComment(ctx, p.ID, req.CommentId)
	if err != nil {
		return nil, err
	}
	if c.Kind == "frame" {
		return nil, problem.New(problem.Forbidden, "frame entries can't be changed")
	}
	own := c.AuthorID != nil && *c.AuthorID == u.ID
	if !own && !p.Moderator() {
		return nil, problem.New(problem.Forbidden, "only the author or a moderator can delete this comment")
	}
	var removed []uuid.UUID
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockThread(ctx, p.ID); err != nil {
			return err
		}
		if own {
			if err := tx.DeleteCommentByAuthor(ctx, c.ID); err != nil {
				return err
			}
		} else if removed, err = tx.RemoveComment(ctx, c.ID, u.ID); err != nil {
			return err
		}
		return tx.RecountThread(ctx, p.ID)
	})
	if err != nil {
		return nil, err
	}
	affected := mentionedUsers(c.Mentions)
	if own {
		after, err := s.st.GetComment(ctx, s.st.Q(), p.ID, c.ID)
		if err != nil {
			return nil, err
		}
		if after.ParentID == nil && after.ReplyCount > 0 {
			s.publishComment(ctx, p.ID, c.ID, "comment.updated", nil) // a tombstone
		} else {
			gone := []uuid.UUID{c.ID}
			// The last live reply of a deleted top comment takes its tombstone with it.
			if c.ParentID != nil {
				if parent, err := s.st.GetComment(ctx, s.st.Q(), p.ID, *c.ParentID); err == nil && parent.DeletedAt != nil && parent.ReplyCount == 0 {
					gone = append(gone, parent.ID)
				}
			}
			s.publishCommentDeleted(ctx, p.ID, gone, nil)
		}
	} else {
		for _, id := range removed {
			s.publishComment(ctx, p.ID, id, "comment.updated", nil) // deleted for everyone, removed_by_admin for the author
		}
	}
	s.publishPostUpdated(ctx, p.ID)
	s.notifyBadge(ctx, affected...)
	return noContent(), nil
}

func (s *Server) SetThreadState(ctx context.Context, req gen.SetThreadStateRequestObject) (gen.SetThreadStateResponseObject, error) {
	u := me(ctx)
	p, err := s.visiblePost(ctx, u.ID, req.PostId)
	if err != nil {
		return nil, err
	}
	if !p.IsPoster(u.ID) && !p.Moderator() {
		return nil, problem.New(problem.Forbidden, "only the poster or a moderator can turn comments on or off")
	}
	state := string(req.Body.State)
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.EnsureThread(ctx, p); err != nil {
			return err
		}
		if _, err := tx.LockThread(ctx, p.ID); err != nil {
			return err
		}
		return tx.SetThreadState(ctx, p.ID, state, u.ID)
	})
	if err != nil {
		return nil, err
	}
	s.publishPostUpdated(ctx, p.ID)
	if p, err = s.st.SocialPost(ctx, s.st.Q(), u.ID, p.ID); err != nil {
		return nil, err
	}
	v, err := s.viewer(ctx, u.ID, nil)
	if err != nil {
		return nil, err
	}
	ts, err := s.threadSummaries(ctx, v, []store.SocialPost{*p})
	if err != nil {
		return nil, err
	}
	return ok(ts[p.ID]), nil
}

func (s *Server) MarkThreadRead(ctx context.Context, req gen.MarkThreadReadRequestObject) (gen.MarkThreadReadResponseObject, error) {
	u := me(ctx)
	p, err := s.visiblePost(ctx, u.ID, req.PostId)
	if err != nil {
		return nil, err
	}
	if _, err := s.st.GetComment(ctx, s.st.Q(), p.ID, req.Body.UpTo); err == store.ErrNotFound {
		return nil, problem.Validation("up_to", "not_in_thread", "up_to must be a comment of this post")
	} else if err != nil {
		return nil, err
	}
	var moved bool
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.EnsureThread(ctx, p); err != nil {
			return err
		}
		var err error
		moved, err = tx.MarkThreadRead(ctx, u.ID, p.ID, req.Body.UpTo)
		return err
	})
	if err != nil {
		return nil, err
	}
	if moved {
		s.notify.User(u.ID, notify.Event{"type": "thread.read", "post_id": p.ID, "read_up_to": req.Body.UpTo}, nil)
		s.notifyBadge(ctx, u.ID)
	}
	return noContent(), nil
}

func (s *Server) SetThreadMute(ctx context.Context, req gen.SetThreadMuteRequestObject) (gen.SetThreadMuteResponseObject, error) {
	u := me(ctx)
	p, err := s.visiblePost(ctx, u.ID, req.PostId)
	if err != nil {
		return nil, err
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.EnsureThread(ctx, p); err != nil {
			return err
		}
		return tx.SetThreadMute(ctx, u.ID, p.ID, req.Body.Muted)
	})
	if err != nil {
		return nil, err
	}
	return noContent(), nil
}
