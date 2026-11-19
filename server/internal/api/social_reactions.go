package api

import (
	"context"
	"strconv"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// People's reactions on posts, post items, comments and chat messages (README §5.18 "Reactions by people").

// reactionTarget is a resolved, visible reaction target.
type reactionTarget struct {
	kind   string
	id     uuid.UUID
	scope  uuid.UUID         // the post, or the conversation for messages
	post   *store.SocialPost // post, post_item, comment
	msg    *store.MessageTarget
	item   *uuid.UUID // post_item
	author *uuid.UUID // who gets reaction_person
}

// resolveReactionTarget finds the target as viewer sees it (404 when they may not), and whether people may
// react to it (422 for frame entries, system messages and tombstones).
func (s *Server) resolveReactionTarget(ctx context.Context, viewer uuid.UUID, kind string, id uuid.UUID, postID *uuid.UUID, forSet bool) (*reactionTarget, error) {
	t := &reactionTarget{kind: kind, id: id}
	cannot := func() error { return problem.Validation("targetId", "not_reactable", "you can't react to this") }
	switch kind {
	case "post":
		p, err := s.visiblePost(ctx, viewer, id)
		if err != nil {
			return nil, err
		}
		t.post, t.scope, t.author = p, p.ID, p.PosterID
	case "post_item":
		if postID == nil {
			return nil, problem.Validation("post_id", "required", "post_id is required to react to a photo of a post")
		}
		p, err := s.visiblePost(ctx, viewer, *postID)
		if err != nil {
			return nil, err
		}
		has, err := s.st.PostHasItem(ctx, s.st.Q(), p.ID, id)
		if err != nil {
			return nil, err
		}
		if !has {
			return nil, problem.Validation("post_id", "not_in_post", "this photo is not part of the post")
		}
		it, err := s.st.GetItemRow(ctx, s.st.Q(), id)
		if err != nil {
			return nil, err
		}
		t.post, t.scope, t.item, t.author = p, p.ID, &id, it.SenderUserID
	case "comment":
		var pid uuid.UUID
		err := s.st.Pool.QueryRow(ctx, `SELECT post_id FROM comments WHERE id = $1`, id).Scan(&pid)
		if db.NotFound(err) {
			return nil, problem.New(problem.NotFound, "comment not found")
		}
		if err != nil {
			return nil, err
		}
		p, err := s.visiblePost(ctx, viewer, pid)
		if err != nil {
			return nil, err
		}
		c, err := s.st.GetComment(ctx, s.st.Q(), pid, id)
		if err != nil {
			return nil, err
		}
		blocked := false
		if c.AuthorID != nil {
			if blocked, err = s.blocks(ctx, viewer, *c.AuthorID); err != nil {
				return nil, err
			}
		}
		if c.RemovedAt != nil || blocked || (c.Kind == "frame" && !p.SeesFrameReactions(viewer)) {
			return nil, problem.New(problem.NotFound, "comment not found")
		}
		if c.Kind == "frame" || c.DeletedAt != nil {
			return nil, cannot()
		}
		t.post, t.scope, t.author = p, pid, c.AuthorID
	case "message":
		m, err := s.st.GetMessageTarget(ctx, s.st.Q(), viewer, id)
		if err == store.ErrNotFound || (err == nil && !m.Visible()) {
			return nil, problem.New(problem.NotFound, "")
		}
		if err != nil {
			return nil, err
		}
		if m.AuthorID != nil {
			if blocked, err := s.blocks(ctx, viewer, *m.AuthorID); err != nil {
				return nil, err
			} else if blocked {
				return nil, problem.New(problem.NotFound, "")
			}
		}
		if m.Kind != "person" || m.Deleted || (forSet && m.Archived) {
			return nil, cannot()
		}
		t.msg, t.scope, t.author = m, m.ConversationID, m.AuthorID
	default:
		return nil, problem.Validation("targetKind", "enum", "you can't react to this")
	}
	return t, nil
}

// blocks reports whether viewer blocked other.
func (s *Server) blocks(ctx context.Context, viewer, other uuid.UUID) (bool, error) {
	b, err := s.st.BlockedByUser(ctx, s.st.Q(), viewer)
	return b[other], err
}

// targetReactions loads people's reactions on the target and, for posts and post items the viewer may see
// them on, frames' reactions.
func (s *Server) targetReactions(ctx context.Context, viewer uuid.UUID, t *reactionTarget, post *store.SocialPost) ([]store.PersonReactionRow, []gen.FrameReactionRef, error) {
	people, err := s.st.PersonReactions(ctx, s.st.Q(), t.kind, t.scope, []uuid.UUID{t.id})
	if err != nil {
		return nil, nil, err
	}
	frames := []gen.FrameReactionRef{}
	if (t.kind == "post" || t.kind == "post_item") && post != nil && post.SeesFrameReactions(viewer) {
		rows, err := s.st.FrameReactions(ctx, s.st.Q(), []uuid.UUID{post.ID})
		if err != nil {
			return nil, nil, err
		}
		frames = frameRefs(rows, post.ID, t.item)
	}
	return people, frames, nil
}

func reactors(rows []store.PersonReactionRow) []uuid.UUID {
	out := make([]uuid.UUID, len(rows))
	for i, r := range rows {
		out[i] = r.UserID
	}
	return out
}

func (s *Server) ListReactions(ctx context.Context, req gen.ListReactionsRequestObject) (gen.ListReactionsResponseObject, error) {
	u := me(ctx)
	t, err := s.resolveReactionTarget(ctx, u.ID, string(req.TargetKind), req.TargetId, req.Params.PostId, false)
	if err != nil {
		return nil, err
	}
	people, _, err := s.targetReactions(ctx, u.ID, t, t.post)
	if err != nil {
		return nil, err
	}
	v, err := s.viewer(ctx, u.ID, reactors(people))
	if err != nil {
		return nil, err
	}
	out := gen.ReactionList{Frames: []gen.FrameReactionRef{}, People: []struct {
		At     time.Time          `json:"at"`
		Emoji  gen.PersonReaction `json:"emoji"`
		Person gen.PersonRef      `json:"person"`
	}{}}
	for _, r := range people {
		if v.blocked[r.UserID] {
			continue
		}
		id := r.UserID
		out.People = append(out.People, struct {
			At     time.Time          `json:"at"`
			Emoji  gen.PersonReaction `json:"emoji"`
			Person gen.PersonRef      `json:"person"`
		}{r.CreatedAt, gen.PersonReaction(r.Emoji), s.socialPersonRef(&id, r.Name, r.AvatarKey, r.Deleted)})
	}
	if (t.kind == "post" || t.kind == "post_item") && t.post.SeesFrameReactions(u.ID) {
		rows, err := s.st.FrameReactions(ctx, s.st.Q(), []uuid.UUID{t.post.ID})
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			if r.PostID != t.post.ID || (t.item != nil && r.ItemID != *t.item) {
				continue
			}
			item, at := r.ItemID, r.At
			out.Frames = append(out.Frames, gen.FrameReactionRef{FrameId: r.FrameID, FrameName: r.FrameName, Reaction: gen.Reaction(r.Reaction), ItemId: &item, At: &at})
		}
	}
	return ok(out), nil
}

func (s *Server) SetReaction(ctx context.Context, req gen.SetReactionRequestObject) (gen.SetReactionResponseObject, error) {
	u := me(ctx)
	t, err := s.resolveReactionTarget(ctx, u.ID, string(req.TargetKind), req.TargetId, req.Params.PostId, true)
	if err != nil {
		return nil, err
	}
	var emoji *string
	if req.Body.Emoji != nil {
		e := string(*req.Body.Emoji)
		emoji = &e
	}
	var prev *string
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if t.post != nil {
			if err := tx.EnsureThread(ctx, t.post); err != nil {
				return err
			}
		}
		var err error
		prev, err = tx.SetPersonReaction(ctx, t.kind, t.id, t.scope, u.ID, emoji)
		return err
	})
	if err != nil {
		return nil, err
	}
	changed := (prev == nil) != (emoji == nil) || (prev != nil && emoji != nil && *prev != *emoji)
	var push *notify.Push
	if emoji != nil && prev == nil {
		push = s.reactionPush(ctx, t, u.ID, *emoji)
	}
	if changed {
		s.publishReaction(ctx, t, push)
		if t.kind == "post" {
			s.publishPostUpdated(ctx, t.post.ID)
		}
	}
	people, frames, err := s.targetReactions(ctx, u.ID, t, t.post)
	if err != nil {
		return nil, err
	}
	v, err := s.viewer(ctx, u.ID, reactors(people))
	if err != nil {
		return nil, err
	}
	return ok(s.socialReactionSummary(v, people, frames)), nil
}

// publishReaction sends reaction.updated (the summary for each receiver) to everyone who sees the target, with
// the author's push.
func (s *Server) publishReaction(ctx context.Context, t *reactionTarget, push *notify.Push) {
	var aud []uuid.UUID
	var err error
	if t.msg != nil {
		aud, err = s.st.MessageAudience(ctx, s.st.Q(), t.msg)
	} else {
		aud, err = s.st.PostAudience(ctx, s.st.Q(), t.post.ID)
	}
	if err != nil {
		s.log.Warn("reaction audience", "err", err)
		return
	}
	for _, r := range aud {
		var p *store.SocialPost
		pr := (*notify.Push)(nil)
		if t.author != nil && r == *t.author {
			pr = push
		}
		if pr == nil && !s.hub.UserConnected(r) {
			continue
		}
		if t.post != nil {
			if p, err = s.st.SocialPost(ctx, s.st.Q(), r, t.post.ID); err != nil || !p.Visible(r) {
				continue
			}
		}
		people, frames, err := s.targetReactions(ctx, r, t, p)
		if err != nil {
			return
		}
		v, err := s.viewer(ctx, r, reactors(people))
		if err != nil {
			return
		}
		ev := notify.Event{"type": "reaction.updated", "target_kind": t.kind, "target_id": t.id, "summary": s.socialReactionSummary(v, people, frames)}
		if t.post != nil {
			ev["post_id"] = t.post.ID
		}
		if t.msg != nil {
			ev["conversation_id"] = t.msg.ConversationID
		}
		s.notify.User(r, ev, pr)
	}
}

// reactionPush is the reaction_person push to the target's author for a new reaction by actor, or nil: never
// for own reactions, for authors who blocked the actor or can't see the target, or when muted; only for the
// first reactionPushLimit people per target (later ones go into the 19:00 digest).
func (s *Server) reactionPush(ctx context.Context, t *reactionTarget, actor uuid.UUID, emoji string) *notify.Push {
	if t.author == nil || *t.author == actor {
		return nil
	}
	author := *t.author
	if b, err := s.blocks(ctx, author, actor); err != nil || b {
		return nil
	}
	ids := map[string]string{"target_kind": t.kind, "target_id": t.id.String()}
	text := "reaction_photo"
	if t.post != nil {
		p, err := s.st.SocialPost(ctx, s.st.Q(), author, t.post.ID)
		if err != nil || !p.Visible(author) || postMutedFor(p, false) {
			return nil
		}
		ids["post_id"] = p.ID.String()
		if t.kind == "comment" {
			text = "reaction_comment"
		}
	} else {
		text = "reaction_message"
		ids["conversation_id"] = t.msg.ConversationID.String()
		m, err := s.st.GetMessageTarget(ctx, s.st.Q(), author, t.msg.ID)
		if err != nil || !m.Visible() {
			return nil
		}
		muted, err := s.st.ConversationMuted(ctx, s.st.Q(), m.ConversationID, author, s.now())
		if err != nil || muted {
			return nil
		}
		if m.FeedID != nil && (m.ConvKind == "family" || m.ConvKind == "circle") {
			mutes, err := s.st.FeedMutes(ctx, s.st.Q(), *m.FeedID, []uuid.UUID{author})
			if err != nil || mutes[author] == "chat" || mutes[author] == "all" {
				return nil
			}
		}
	}
	now := s.now()
	key := "reaction:" + t.kind + ":" + t.id.String()
	d, err := s.st.CountEvent(ctx, author, key, actor, reactionPushLimit, now)
	if err != nil {
		s.log.Warn("reaction bundle", "err", err)
		return nil
	}
	if d.Overflow {
		if err := s.st.AddToDigest(ctx, author, "reaction_digest", s.nextDigest(ctx, author, now), now); err != nil {
			s.log.Warn("reaction digest", "err", err)
		}
	}
	if !d.PushNow {
		return nil
	}
	people, err := s.st.PersonReactions(ctx, s.st.Q(), t.kind, t.scope, []uuid.UUID{t.id})
	if err != nil {
		return nil
	}
	n := 0
	for _, r := range people {
		if r.UserID != author {
			n++
		}
	}
	args := map[string]string{"name": s.userNames(ctx, []uuid.UUID{actor})[actor], "emoji": emojiChars[emoji]}
	switch {
	case n == 2:
		text += "_two"
	case n > 2:
		text += "_many"
		args["count"] = strconv.Itoa(n - 1)
	}
	return &notify.Push{Key: "reaction_person", Text: text, Args: args, IDs: ids, Tag: key}
}
