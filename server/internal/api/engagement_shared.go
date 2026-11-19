//lint:file-ignore U1000 Fixed signatures for the parallel family social work; their callers arrive with the areas (docs/spec/family-social-work.md).

package api

// Encouragement to share, server-engagement's helpers that the other areas call
// (docs/spec/family-social-work.md). The signatures are fixed; server-engagement fills in the bodies.

import (
	"context"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// afterPersonalSend is called once a send by a person has made its placements and posts (createSend,
// forwardSend, putMessageOnFrame, email and Telegram sends; not photo sources, Google imports, shares or
// backfills): freshness, photo request answers, streak counts, nudge outcomes and milestones follow from it.
func (s *Server) afterPersonalSend(ctx context.Context, sendID uuid.UUID) { _, _ = ctx, sendID }

// frameFreshness fills FrameSummary.freshness and .photo_request for these frames (listFrames, getFrame).
func (s *Server) frameFreshness(ctx context.Context, frames []gen.FrameSummary) { _, _ = ctx, frames }

// feedFreshness fills FeedSummary.freshness and .streak for these feeds (listMyFeeds, getFeed).
func (s *Server) feedFreshness(ctx context.Context, feeds []gen.FeedSummary) { _, _ = ctx, feeds }

// engagementDiscovery sets discovery `engagement`, `email_digest` and limits once built.
func (s *Server) engagementDiscovery(d *gen.Discovery) { _ = d }

// runEngagementJob handles server-engagement's worker jobs (handled=false for other kinds).
func (s *Server) runEngagementJob(ctx context.Context, job *store.Job) (bool, error) {
	_, _ = ctx, job
	return false, nil
}

// RunEngagement is the reminder and summary scheduler (every 15 minutes, README §5.20).
func (s *Server) RunEngagement(ctx context.Context) { <-ctx.Done() }
