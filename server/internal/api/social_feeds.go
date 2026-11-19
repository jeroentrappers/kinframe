package api

import (
	"context"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Family social hooks in the feed operations (README §5.18): the new feed settings, the `member` role, the
// caller's family mute, circle visibility of feed posts and circle audiences of sends.

// socialFeedCreate applies createFeed's family social settings.
func socialFeedCreate(f *store.Feed, b *gen.CreateFeedJSONRequestBody) {
	if b.MembersSeeReceipts != nil {
		f.MembersSeeReceipts = *b.MembersSeeReceipts
	}
	if b.NewMembersCanPost != nil {
		f.NewMembersCanPost = *b.NewMembersCanPost
	}
	if b.MembersCanCreateCircles != nil {
		f.MembersCanCreateCircles = *b.MembersCanCreateCircles
	}
}

// joinRole is the role of people joining through an invite or an approved join request.
func joinRole(f *store.Feed) string {
	if f.NewMembersCanPost {
		return "poster"
	}
	return "member"
}

// setFeedMute stores the caller's family mute (updateFeedPreferences).
func (s *Server) setFeedMute(ctx context.Context, feedID uuid.UUID, mute *gen.FeedMute) error {
	if mute == nil {
		return nil
	}
	return s.st.InTx(ctx, func(tx *store.Tx) error { return tx.SetFeedMute(ctx, feedID, me(ctx).ID, string(*mute)) })
}

// seesCirclePost: whole-family posts are for every member; circle posts for the circle and the poster.
func (s *Server) seesCirclePost(ctx context.Context, p *store.FeedPost) bool {
	if p.CircleID == nil || (p.PosterUserID != nil && *p.PosterUserID == me(ctx).ID) {
		return true
	}
	role, err := s.st.CircleRole(ctx, s.st.Q(), *p.CircleID, me(ctx).ID)
	return err == nil && role != ""
}

// moderatesFeedPost: the feed's owner and admins for whole-family posts, the circle's admins for circle posts.
func (s *Server) moderatesFeedPost(ctx context.Context, v *store.FeedView, p *store.FeedPost) bool {
	if p.CircleID == nil {
		return v.IsAdmin()
	}
	role, err := s.st.CircleRole(ctx, s.st.Q(), *p.CircleID, me(ctx).ID)
	return err == nil && role == "admin"
}

// feedPostThreads fills FeedPost.thread (listFeedPosts, getFeedPost).
func (s *Server) feedPostThreads(ctx context.Context, out []feedPostJSON) error {
	if len(out) == 0 {
		return nil
	}
	u := me(ctx)
	ids := make([]uuid.UUID, len(out))
	for i := range out {
		ids[i] = out[i].ID
	}
	posts, err := s.st.SocialPosts(ctx, s.st.Q(), u.ID, ids)
	if err != nil {
		return err
	}
	v, err := s.viewer(ctx, u.ID, nil)
	if err != nil {
		return err
	}
	ts, err := s.threadSummaries(ctx, v, posts)
	if err != nil {
		return err
	}
	for i := range out {
		if t, ok := ts[out[i].ID]; ok {
			out[i].Thread = &t
		}
	}
	return nil
}

// sendCircles validates SendCreate.circle_ids / forwardSend's: at most one circle per feed, its feed among the
// send's feeds, the caller in it (README §5.18). Returns feed -> circle.
func (s *Server) sendCircles(ctx context.Context, feeds []uuid.UUID, circleIDs *[]uuid.UUID) (map[uuid.UUID]uuid.UUID, error) {
	out := map[uuid.UUID]uuid.UUID{}
	if circleIDs == nil {
		return out, nil
	}
	inSend := map[uuid.UUID]bool{}
	for _, f := range feeds {
		inSend[f] = true
	}
	for _, id := range *circleIDs {
		c, err := s.st.GetCircleByID(ctx, s.st.Q(), id)
		if err == store.ErrNotFound || (err == nil && !inSend[c.FeedID]) {
			return nil, problem.Validation("circle_ids", "not_in_feed", "a circle must belong to a family you post to")
		}
		if err != nil {
			return nil, err
		}
		if prev, dup := out[c.FeedID]; dup && prev != id {
			return nil, problem.Validation("circle_ids", "one_per_feed", "at most one circle per family")
		}
		role, err := s.st.CircleRole(ctx, s.st.Q(), id, me(ctx).ID)
		if err != nil {
			return nil, err
		}
		if role == "" {
			return nil, problem.Validation("circle_ids", "not_member", "you can only share with circles you are in")
		}
		out[c.FeedID] = id
	}
	return out, nil
}

// applySendCircles makes the send's new posts in those feeds circle posts (before their fanout runs).
func applySendCircles(ctx context.Context, tx *store.Tx, sendID uuid.UUID, circles map[uuid.UUID]uuid.UUID) error {
	for feed, circle := range circles {
		if err := tx.SetPostCircle(ctx, sendID, feed, circle); err != nil {
			return err
		}
	}
	return nil
}
