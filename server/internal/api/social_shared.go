//lint:file-ignore U1000 Fixed signatures for the parallel family social work; their callers arrive with the areas (docs/spec/family-social-work.md).

package api

// Family social, server-social's helpers that the other areas call (docs/spec/family-social-work.md). The
// signatures are fixed; server-social fills in the bodies.

import (
	"context"

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
	_, _, _ = ctx, userID, postID
	return postAccess{}, nil
}

// socialDiscovery sets discovery `social` and the social limits (max_comment_chars, max_mentions,
// max_circles_per_feed) once the area is built.
func (s *Server) socialDiscovery(d *gen.Discovery) { _ = d }

// runSocialJob handles server-social's worker jobs (handled=false for other kinds).
func (s *Server) runSocialJob(ctx context.Context, job *store.Job) (bool, error) {
	_, _ = ctx, job
	return false, nil
}

// RunSocial is server-social's background loop (comment bundles, the daily reaction digest).
func (s *Server) RunSocial(ctx context.Context) { <-ctx.Done() }
