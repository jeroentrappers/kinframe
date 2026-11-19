//lint:file-ignore U1000 Fixed signatures for the parallel family social work; their callers arrive with the areas (docs/spec/family-social-work.md).

package api

// Family social, server-social's helpers that the other areas call (docs/spec/family-social-work.md). The
// signatures are fixed.

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// postAccess is what a user may do with a post (feed post or frame post, README §5.18).
type postAccess struct {
	Visible    bool       // may see the post, its thread and reactions
	CanComment bool       // visible and the thread is open
	Moderator  bool       // may close the thread and remove comments (README §5.18 "Moderators")
	Kind       string     // "feed" or "frame"
	FeedID     *uuid.UUID // feed posts
	CircleID   *uuid.UUID // feed posts with a circle audience
	FrameID    *uuid.UUID // frame posts
	PosterID   uuid.UUID
}

// postAccessFor answers whether userID may see postID and what they may do; an unknown or removed post is
// not visible. Used by chats (shared posts, reports) and engagement (On this day, prompts).
func (s *Server) postAccessFor(ctx context.Context, userID, postID uuid.UUID) (postAccess, error) {
	p, err := s.st.SocialPost(ctx, s.st.Q(), userID, postID)
	if err == store.ErrNotFound {
		return postAccess{}, nil
	}
	if err != nil {
		return postAccess{}, err
	}
	a := postAccess{Kind: p.Kind, FeedID: p.FeedID, CircleID: p.CircleID, FrameID: p.FrameID}
	if p.PosterID != nil {
		a.PosterID = *p.PosterID
	}
	if !p.Visible(userID) {
		return a, nil
	}
	a.Visible = true
	a.CanComment = p.ThreadState == "open"
	a.Moderator = p.Moderator()
	return a, nil
}

// socialDiscovery sets discovery `social` and the social limits (max_comment_chars, max_mentions,
// max_circles_per_feed).
func (s *Server) socialDiscovery(d *gen.Discovery) {
	d.Social = ptr(true)
	d.Limits.MaxCommentChars = ptr(maxCommentChars)
	d.Limits.MaxMentions = ptr(maxMentions)
	d.Limits.MaxCirclesPerFeed = ptr(maxCirclesPerFeed)
}

// runSocialJob handles server-social's worker jobs (handled=false for other kinds). Social needs no jobs: its
// timed work (comment bundles, the reaction digest) runs in RunSocial.
func (s *Server) runSocialJob(ctx context.Context, job *store.Job) (bool, error) {
	_, _ = ctx, job
	return false, nil
}

// RunSocial is server-social's background loop (comment bundles, the daily reaction digest).
func (s *Server) RunSocial(ctx context.Context) {
	t := time.NewTicker(socialTickEvery)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		if n, err := s.SocialTick(ctx, s.now()); err != nil && ctx.Err() == nil {
			s.log.Error("social pushes", "err", err)
		} else if n > 0 {
			s.log.Debug("sent social pushes", "count", n)
		}
	}
}
