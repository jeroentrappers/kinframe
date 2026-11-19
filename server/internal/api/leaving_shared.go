//lint:file-ignore U1000 Fixed signatures for the parallel leaving and federation work; their callers arrive with the areas (docs/spec/leaving-servers-work.md).

package api

// Leaving a family, server-leaving's helpers that the other areas call (docs/spec/leaving-servers-work.md). The
// signatures are fixed; the bodies are stubs until server-leaving fills them.

import (
	"context"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// accountDeletionTakesPhotos reads deleteMe's choice (README §5.21 "Account deletion"): the body's `photos` wins,
// else the older `remove_items` (true = take, false = leave), else the default `take`.
func accountDeletionTakesPhotos(req gen.DeleteMeRequestObject) bool {
	if req.Body != nil && req.Body.Photos != nil {
		return *req.Body.Photos == gen.AccountDeletionPhotosTake
	}
	if req.Params.RemoveItems != nil {
		return *req.Params.RemoveItems
	}
	return true
}

// memoryFilter is a user's private memories filter (README §5.21 "Memories").
type memoryFilter struct {
	HiddenPeople map[uuid.UUID]bool // posters left out
	HiddenPosts  map[uuid.UUID]bool // single memories never shown again
}

// Hides reports whether a post by posterID (nil: unknown or deleted) must stay out of this user's memories.
func (f memoryFilter) Hides(posterID *uuid.UUID, postID uuid.UUID) bool {
	if f.HiddenPosts[postID] {
		return true
	}
	return posterID != nil && f.HiddenPeople[*posterID]
}

// memoryFilterFor loads userID's memories filter. Engagement applies it to On this day, the monthly recap as
// rendered for that user, the "Send something to Oma's frame" suggestions and the weekly summary (never to frames).
func (s *Server) memoryFilterFor(ctx context.Context, q db.DBTX, userID uuid.UUID) (memoryFilter, error) {
	_, _, _ = ctx, q, userID
	return memoryFilter{}, nil
}

// shareLiveScope reports whether two people still share a live family or frame. Chats make a direct chat
// read-only (`read_only_reason: no_longer_contacts`, sending `403 cannot_message`) when they don't (README §5.21
// "Contact that stops"). Until server-leaving fills it, it answers true (no change in behaviour).
func (s *Server) shareLiveScope(ctx context.Context, q db.DBTX, a, b uuid.UUID) (bool, error) {
	_, _, _, _ = ctx, q, a, b
	return true, nil
}

// placementTakenAway fills Placement.taken_away for placements rendered for a frame's managers (listPlacements with
// `visibility: taken_away`, updatePlacement). Library code calls it after rendering a page.
func (s *Server) placementTakenAway(ctx context.Context, frameID uuid.UUID, placements []gen.Placement) {
	_, _, _ = ctx, frameID, placements
}

// leavingDiscovery sets discovery `leaving` once the area is complete.
func (s *Server) leavingDiscovery(d *gen.Discovery) {
	_ = d
}

// runLeavingJob handles server-leaving's worker jobs (kinds `leaving.*`; handled=false for other kinds).
func (s *Server) runLeavingJob(ctx context.Context, job *store.Job) (bool, error) {
	_, _ = ctx, job
	return false, nil
}

// RunLeaving is server-leaving's background loop: the 30-day purge of taken-away placements and posts, keep
// request expiry (`keep_request.updated`), and the ownership log's retention.
func (s *Server) RunLeaving(ctx context.Context) {
	<-ctx.Done()
}
