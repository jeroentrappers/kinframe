package api

import (
	"context"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Family social websocket events and pushes (README §5.18, §6.2, §6.3). Events go to everyone who sees the
// post (rendered for each receiver); pushes follow the rules of §5.18 "Pushes": comment (bundled per post for
// 10 minutes), comment_reply, mention, reaction_person (the first three people per target) and the daily
// reaction_digest at 19:00 local time.

const (
	commentWindow      = 10 * time.Minute // comment bundles
	reactionPushLimit  = 3                // people per target pushed before the digest takes over
	reactionDigestHour = 19               // local hour of the reaction digest
	socialTickEvery    = 30 * time.Second
	pushTextRunes      = 120 // comment text in a push body
)

// emojiChars are the reactions as shown in push texts.
var emojiChars = map[string]string{"love": "❤️", "laugh": "😂", "wow": "😮", "sad": "😢", "thumbs_up": "👍", "pray": "🙏"}

// pushText shortens a comment for a push body.
func pushText(s string) string {
	s = strings.Join(strings.Fields(s), " ")
	if utf8.RuneCountInString(s) <= pushTextRunes {
		return s
	}
	r := []rune(s)
	return strings.TrimSpace(string(r[:pushTextRunes-1])) + "…"
}

// userNames maps users to their display names (deleted accounts: "").
func (s *Server) userNames(ctx context.Context, ids []uuid.UUID) map[uuid.UUID]string {
	out := map[uuid.UUID]string{}
	if len(ids) == 0 {
		return out
	}
	rows, err := s.st.Pool.Query(ctx, `SELECT id, CASE WHEN deleted_at IS NULL THEN display_name ELSE '' END FROM users WHERE id = ANY($1)`, ids)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var id uuid.UUID
		var n string
		if rows.Scan(&id, &n) == nil {
			out[id] = n
		}
	}
	return out
}

// postIDs are the ids a push about a post carries.
func postPushIDs(p *store.SocialPost) map[string]string {
	ids := map[string]string{"post_id": p.ID.String()}
	if p.FeedID != nil {
		ids["feed_id"] = p.FeedID.String()
	}
	if p.FrameID != nil {
		ids["frame_id"] = p.FrameID.String()
	}
	return ids
}

// postMutedFor reports whether a push about the post is muted for its receiver (p loaded as them): a muted
// thread mutes everything, the family mute `all` too, `posts` all but mentions.
func postMutedFor(p *store.SocialPost, mention bool) bool {
	return p.Muted || p.FeedMute == "all" || (p.FeedMute == "posts" && !mention)
}

// ---------------------------------------------------------------- events

// publishPostUpdated sends post.updated with each receiver's ThreadSummary to everyone connected who sees it.
func (s *Server) publishPostUpdated(ctx context.Context, postID uuid.UUID) {
	aud, err := s.st.PostAudience(ctx, s.st.Q(), postID)
	if err != nil {
		s.log.Warn("post audience", "post", postID, "err", err)
		return
	}
	for _, r := range aud {
		if !s.hub.UserConnected(r) {
			continue
		}
		p, err := s.st.SocialPost(ctx, s.st.Q(), r, postID)
		if err != nil || !p.Visible(r) {
			continue
		}
		v, err := s.viewer(ctx, r, nil)
		if err != nil {
			continue
		}
		ts, err := s.threadSummaries(ctx, v, []store.SocialPost{*p})
		if err != nil {
			s.log.Warn("thread summary", "post", postID, "err", err)
			return
		}
		s.notify.User(r, notify.Event{"type": "post.updated", "post_id": postID, "feed_id": p.FeedID, "frame_id": p.FrameID,
			"thread": ts[postID]}, nil)
	}
}

// publishComment sends comment.created or comment.updated (typ) for a comment to everyone who sees it, with the
// planned pushes. People who blocked the author get nothing; frame entries go only to people who see frames'
// reactions; a comment removed by a moderator is comment.deleted for everyone but its author.
func (s *Server) publishComment(ctx context.Context, postID, commentID uuid.UUID, typ string, pushes map[uuid.UUID]*notify.Push) {
	c, err := s.st.GetComment(ctx, s.st.Q(), postID, commentID)
	if err != nil {
		return
	}
	aud, err := s.st.PostAudience(ctx, s.st.Q(), postID)
	if err != nil {
		s.log.Warn("post audience", "post", postID, "err", err)
		return
	}
	var blockedAuthor map[uuid.UUID]bool
	if c.AuthorID != nil {
		if blockedAuthor, err = s.st.WhoBlocked(ctx, s.st.Q(), *c.AuthorID, aud); err != nil {
			return
		}
	}
	reactions, err := s.st.PersonReactionsOf(ctx, s.st.Q(), "comment", []uuid.UUID{c.ID})
	if err != nil {
		return
	}
	for _, r := range aud {
		push := pushes[r]
		if blockedAuthor[r] || (push == nil && !s.hub.UserConnected(r)) {
			continue
		}
		p, err := s.st.SocialPost(ctx, s.st.Q(), r, postID)
		if err != nil || !p.Visible(r) || (c.Kind == "frame" && !p.SeesFrameReactions(r)) {
			continue
		}
		author := c.AuthorID != nil && *c.AuthorID == r
		if c.RemovedAt != nil && !author {
			s.notify.User(r, notify.Event{"type": "comment.deleted", "post_id": postID, "comment_id": c.ID}, nil)
			continue
		}
		people := []uuid.UUID{}
		for _, x := range reactions {
			people = append(people, x.UserID)
		}
		v, err := s.viewer(ctx, r, people)
		if err != nil {
			continue
		}
		s.notify.User(r, notify.Event{"type": typ, "post_id": postID, "comment": s.commentOut(v, p, c, reactions)}, push)
	}
}

// publishCommentDeleted sends comment.deleted to everyone who sees the post, except keep (who get the
// comment again: authors of removed comments see them with removed_by_admin).
func (s *Server) publishCommentDeleted(ctx context.Context, postID uuid.UUID, ids []uuid.UUID, keep map[uuid.UUID]bool) {
	aud, err := s.st.PostAudience(ctx, s.st.Q(), postID)
	if err != nil {
		return
	}
	for _, r := range aud {
		if keep[r] || !s.hub.UserConnected(r) {
			continue
		}
		for _, id := range ids {
			s.notify.User(r, notify.Event{"type": "comment.deleted", "post_id": postID, "comment_id": id}, nil)
		}
	}
}

// postAudienceSnapshot remembers who saw posts before a removal, so they can be told after it.
type postAudienceSnapshot struct {
	posts map[uuid.UUID]*store.SocialPost // as seen by anyone (feed and frame ids)
	users map[uuid.UUID][]uuid.UUID
}

// postAudienceBefore snapshots the audience of a post (before it may be removed).
func (s *Server) postAudienceBefore(ctx context.Context, postIDs ...uuid.UUID) *postAudienceSnapshot {
	snap := &postAudienceSnapshot{posts: map[uuid.UUID]*store.SocialPost{}, users: map[uuid.UUID][]uuid.UUID{}}
	for _, id := range postIDs {
		aud, err := s.st.PostAudience(ctx, s.st.Q(), id)
		if err != nil || len(aud) == 0 {
			continue
		}
		p, err := s.st.SocialPost(ctx, s.st.Q(), aud[0], id)
		if err != nil {
			continue
		}
		snap.posts[id], snap.users[id] = p, aud
	}
	return snap
}

// sendPostsAudience snapshots the audience of every live post of a send.
func (s *Server) sendPostsAudience(ctx context.Context, sendID uuid.UUID) *postAudienceSnapshot {
	ids, err := s.st.PostsOfSend(ctx, s.st.Q(), sendID)
	if err != nil {
		s.log.Warn("posts of send", "send", sendID, "err", err)
	}
	return s.postAudienceBefore(ctx, ids...)
}

// publishPostGone sends post.removed for the snapshotted posts that are gone now (removed, or no live item
// left), and drops their people's reactions.
func (s *Server) publishPostGone(ctx context.Context, snap *postAudienceSnapshot) {
	if snap == nil {
		return
	}
	var gone []uuid.UUID
	for id, before := range snap.posts {
		users := snap.users[id]
		p, err := s.st.SocialPost(ctx, s.st.Q(), users[0], id)
		if err != nil || (!p.Gone && p.HasItems) {
			continue
		}
		gone = append(gone, id)
		s.notify.Users(users, notify.Event{"type": "post.removed", "post_id": id, "feed_id": before.FeedID, "frame_id": before.FrameID}, nil)
	}
	if len(gone) > 0 {
		if err := s.st.InTx(ctx, func(tx *store.Tx) error { return tx.DeletePostReactions(ctx, gone) }); err != nil {
			s.log.Warn("delete post reactions", "err", err)
		}
	}
}

// ---------------------------------------------------------------- comment pushes

// commentPushes plans the pushes for a new comment (or, with onlyMentions, an edit that mentions new people):
// per receiver the first that applies of mention, comment_reply (the top comment's author and the quoted
// person) and comment (the poster and earlier commenters, bundled). Never to the author, to people who blocked
// the author, or for muted threads (README §5.18).
func (s *Server) commentPushes(ctx context.Context, postID uuid.UUID, c *store.Comment, text string, mentioned []uuid.UUID, onlyMentions bool) map[uuid.UUID]*notify.Push {
	out := map[uuid.UUID]*notify.Push{}
	if c.AuthorID == nil {
		return out
	}
	author := *c.AuthorID
	key := map[uuid.UUID]string{}
	var order []uuid.UUID
	add := func(u uuid.UUID, k string) {
		if u == author {
			return
		}
		if _, ok := key[u]; !ok {
			key[u] = k
			order = append(order, u)
		}
	}
	for _, u := range mentioned {
		add(u, "mention")
	}
	if !onlyMentions {
		if c.ParentID != nil {
			if parent, err := s.st.GetComment(ctx, s.st.Q(), postID, *c.ParentID); err == nil && parent.AuthorID != nil {
				add(*parent.AuthorID, "comment_reply")
			}
			if c.QuotedID != nil {
				add(*c.QuotedID, "comment_reply")
			}
		}
		if p, err := s.st.SocialPost(ctx, s.st.Q(), author, postID); err == nil && p.PosterID != nil {
			add(*p.PosterID, "comment")
		}
		earlier, err := s.st.Commenters(ctx, s.st.Q(), postID, c.ID)
		if err != nil {
			s.log.Warn("commenters", "post", postID, "err", err)
		}
		for _, u := range earlier {
			add(u, "comment")
		}
	}
	if len(order) == 0 {
		return out
	}
	blockedAuthor, err := s.st.WhoBlocked(ctx, s.st.Q(), author, order)
	if err != nil {
		return out
	}
	authorBlocked, err := s.st.BlockedByUser(ctx, s.st.Q(), author)
	if err != nil {
		return out
	}
	names := s.userNames(ctx, []uuid.UUID{author})
	now := s.now()
	for _, r := range order {
		k := key[r]
		if blockedAuthor[r] || (k == "mention" && authorBlocked[r]) {
			continue
		}
		p, err := s.st.SocialPost(ctx, s.st.Q(), r, postID)
		if err != nil || !p.Visible(r) || postMutedFor(p, k == "mention") {
			continue
		}
		ids := postPushIDs(p)
		ids["comment_id"] = c.ID.String()
		args := map[string]string{"name": names[author], "text": text}
		var badge *int
		if k == "mention" || k == "comment_reply" {
			if b, err := s.badge(ctx, r); err == nil {
				badge = &b.Total
			}
		}
		switch k {
		case "mention":
			out[r] = &notify.Push{Key: "mention", Text: "mention_comment", Args: args, IDs: ids, Badge: badge}
		case "comment_reply":
			ids["parent_id"] = c.ParentID.String()
			out[r] = &notify.Push{Key: "comment_reply", Args: args, IDs: ids, Tag: "comment_reply:" + c.ParentID.String(), Badge: badge}
		case "comment":
			if s.hub.UserConnected(r) {
				continue // in the app: the event is enough, and the bundle stays quiet
			}
			d, err := s.st.BundleEvent(ctx, r, "comment:"+postID.String(), author, now, commentWindow)
			if err != nil {
				s.log.Warn("comment bundle", "err", err)
				continue
			}
			if d.PushNow {
				out[r] = s.commentBundlePush(ctx, p, r, d.Actors, c.ID, names[author], text)
			}
		}
	}
	return out
}

// commentBundlePush is the comment push for receiver r covering actors (newest first; the first wrote the
// comment shown): "Ben commented on your photo: …", "Ben and Anna commented: …", "Ben and 3 others commented: …".
func (s *Server) commentBundlePush(ctx context.Context, p *store.SocialPost, r uuid.UUID, actors []uuid.UUID, commentID uuid.UUID, name, text string) *notify.Push {
	ids := postPushIDs(p)
	ids["comment_id"] = commentID.String()
	args := map[string]string{"name": name, "text": text}
	textKey := "comment_also"
	if p.IsPoster(r) {
		textKey = "comment"
	}
	switch {
	case len(actors) == 2:
		textKey = "comment_two"
		args["other"] = s.userNames(ctx, actors[1:2])[actors[1]]
	case len(actors) > 2:
		textKey = "comment_many"
		args["count"] = strconv.Itoa(len(actors) - 1)
	}
	return &notify.Push{Key: "comment", Text: textKey, Args: args, IDs: ids, Tag: "comment:" + p.ID.String()}
}

// ---------------------------------------------------------------- the background tick

// SocialTick sends what is due at now: comment bundles whose 10-minute window ended and the 19:00 reaction
// digests. Returns how many pushes it sent.
func (s *Server) SocialTick(ctx context.Context, now time.Time) (int, error) {
	sent := 0
	for {
		bs, err := s.st.FlushBundles(ctx, now, "comment", commentWindow, 200)
		if err != nil {
			return sent, err
		}
		for _, b := range bs {
			if s.flushCommentBundle(ctx, b) {
				sent++
			}
		}
		if len(bs) < 200 {
			break
		}
	}
	for {
		bs, err := s.st.FlushBundles(ctx, now, "reaction_digest", 0, 200)
		if err != nil {
			return sent, err
		}
		for _, b := range bs {
			text := "reaction_digest"
			if b.PendingCount == 1 {
				text = "reaction_digest_one"
			}
			s.notify.PushOnly(b.UserID, "reaction_digest", &notify.Push{Key: "reaction_digest", Text: text,
				Args: map[string]string{"count": strconv.Itoa(b.PendingCount)}, IDs: map[string]string{}, Tag: "reaction_digest"})
			sent++
		}
		if len(bs) < 200 {
			break
		}
	}
	if now.Minute() == 0 && now.Second() < int(socialTickEvery/time.Second) {
		if err := s.st.PruneBundles(ctx, now, []string{"comment", "comment_reply", "reaction", "reaction_digest"}); err != nil {
			s.log.Warn("prune push bundles", "err", err)
		}
	}
	return sent, nil
}

// flushCommentBundle sends the held comments of one bundle as one push, unless the receiver is in the app,
// can no longer see the post or muted it.
func (s *Server) flushCommentBundle(ctx context.Context, b store.PushBundle) bool {
	postID, err := uuid.Parse(strings.TrimPrefix(b.Key, "comment:"))
	if err != nil || s.hub.UserConnected(b.UserID) {
		return false
	}
	p, err := s.st.SocialPost(ctx, s.st.Q(), b.UserID, postID)
	if err != nil || !p.Visible(b.UserID) || postMutedFor(p, false) {
		return false
	}
	c, err := s.st.LatestCommentFor(ctx, s.st.Q(), b.UserID, postID)
	if err != nil || c.AuthorID == nil {
		return false
	}
	blocked, err := s.st.BlockedByUser(ctx, s.st.Q(), b.UserID)
	if err != nil {
		return false
	}
	actors := []uuid.UUID{*c.AuthorID}
	for _, a := range b.ActorIDs {
		if a != *c.AuthorID && a != b.UserID && !blocked[a] {
			actors = append(actors, a)
		}
	}
	text := ""
	if t := s.openCommentBody(c); t != nil {
		text = pushText(*t)
	}
	s.notify.PushOnly(b.UserID, "comment.created", s.commentBundlePush(ctx, p, b.UserID, actors, c.ID, c.AuthorName, text))
	return true
}

// nextDigest is the next 19:00 in the user's time zone after now.
func (s *Server) nextDigest(ctx context.Context, userID uuid.UUID, now time.Time) time.Time {
	tz, _ := s.st.UserTimezone(ctx, userID)
	local := now.In(loadZone(tz))
	due := time.Date(local.Year(), local.Month(), local.Day(), reactionDigestHour, 0, 0, 0, local.Location())
	if !local.Before(due) {
		due = due.AddDate(0, 0, 1)
	}
	return due
}
