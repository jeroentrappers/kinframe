package api

// Encouragement to share, server-engagement's helpers that the other areas call
// (docs/spec/family-social-work.md). The signatures are fixed.

import (
	"context"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// afterPersonalSend is called once a send by a person has made its placements and posts (createSend,
// forwardSend, putMessageOnFrame, email and Telegram sends; not photo sources, Google imports, shares or
// backfills): freshness, photo request answers, streak counts, nudge outcomes and milestones follow from it.
// It is idempotent: calling it twice for the same send changes nothing more.
func (s *Server) afterPersonalSend(ctx context.Context, sendID uuid.UUID) {
	s.engagementAfterSend(ctx, sendID)
}

// frameFreshness fills FrameSummary.freshness and .photo_request for these frames (listFrames, getFrame).
func (s *Server) frameFreshness(ctx context.Context, frames []gen.FrameSummary) {
	s.fillFrameFreshness(ctx, frames)
}

// feedFreshness fills FeedSummary.freshness and .streak for these feeds (listMyFeeds, getFeed).
func (s *Server) feedFreshness(ctx context.Context, feeds []gen.FeedSummary) {
	s.fillFeedFreshness(ctx, feeds)
}

// engagementDiscovery sets discovery `engagement` and `email_digest` (outgoing mail configured).
func (s *Server) engagementDiscovery(d *gen.Discovery) {
	d.Engagement = ptr(true)
	d.EmailDigest = ptr(s.digestAvailable())
}

// runEngagementJob handles server-engagement's worker jobs (handled=false for other kinds).
func (s *Server) runEngagementJob(ctx context.Context, job *store.Job) (bool, error) {
	if job.Kind != jobEngagementTick {
		return false, nil
	}
	s.EngagementTick(ctx, s.now())
	return true, nil
}

// RunEngagement is the reminder and summary scheduler (every 15 minutes, README §5.20), with a short loop for
// comment cards and frame requests held through quiet hours.
func (s *Server) RunEngagement(ctx context.Context) { s.runEngagementLoop(ctx) }
