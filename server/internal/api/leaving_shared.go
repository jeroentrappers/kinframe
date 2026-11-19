package api

// Leaving a family, server-leaving's helpers that the other areas call (docs/spec/leaving-servers-work.md). The
// signatures are fixed.

import (
	"context"
	"time"

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

// empty reports whether the filter hides nothing.
func (f memoryFilter) empty() bool { return len(f.HiddenPeople) == 0 && len(f.HiddenPosts) == 0 }

// memoryFilterFor loads userID's memories filter. Engagement applies it to On this day, the monthly recap as
// rendered for that user, the "Send something to Oma's frame" suggestions and the weekly summary (never to frames).
func (s *Server) memoryFilterFor(ctx context.Context, q db.DBTX, userID uuid.UUID) (memoryFilter, error) {
	f := memoryFilter{HiddenPeople: map[uuid.UUID]bool{}, HiddenPosts: map[uuid.UUID]bool{}}
	people, posts, err := s.st.MemoryHidden(ctx, q, userID)
	if err != nil {
		return f, err
	}
	for _, id := range people {
		f.HiddenPeople[id] = true
	}
	for _, id := range posts {
		f.HiddenPosts[id] = true
	}
	return f, nil
}

// shareLiveScope reports whether two people still share a live family or frame. Chats make a direct chat
// read-only (`read_only_reason: no_longer_contacts`, sending `403 cannot_message`) when they don't (README §5.21
// "Contact that stops").
func (s *Server) shareLiveScope(ctx context.Context, q db.DBTX, a, b uuid.UUID) (bool, error) {
	return s.st.SharesLiveScope(ctx, q, a, b)
}

// placementTakenAway fills Placement.taken_away for placements rendered for a frame's managers (listPlacements with
// `visibility: taken_away`, updatePlacement). Library code calls it after rendering a page.
func (s *Server) placementTakenAway(ctx context.Context, frameID uuid.UUID, placements []gen.Placement) {
	s.placementTakenAwayFill(ctx, frameID, placements)
}

// leavingDiscovery sets discovery `leaving` once the area is complete.
func (s *Server) leavingDiscovery(d *gen.Discovery) {
	d.Leaving = ptr(true)
}

// runLeavingJob handles server-leaving's worker jobs (kinds `leaving.*`; handled=false for other kinds). Leaving
// needs no jobs: takings run in the request, the purge and expiry in RunLeaving.
func (s *Server) runLeavingJob(ctx context.Context, job *store.Job) (bool, error) {
	_, _ = ctx, job
	return false, nil
}

// leavingTickEvery is how often RunLeaving looks for takings and keep requests whose 30 days are over.
const leavingTickEvery = time.Minute

// ownershipLogRetention is how long the ownership log keeps entries.
const ownershipLogRetention = 2 * 365 * 24 * time.Hour

// RunLeaving is server-leaving's background loop: the 30-day purge of taken-away placements and posts, keep
// request expiry (`keep_request.updated`), and the ownership log's retention.
func (s *Server) RunLeaving(ctx context.Context) {
	t := time.NewTicker(leavingTickEvery)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		if err := s.LeavingTick(ctx, s.now()); err != nil && ctx.Err() == nil {
			s.log.Error("leaving purge", "err", err)
		}
	}
}

// LeavingTick runs one round of RunLeaving at now (tests call it with their clock): open keep requests whose 30
// days are over expire, takings whose 30 days are over are purged (placement.updated `purged`), and ownership log
// entries older than 2 years go.
func (s *Server) LeavingTick(ctx context.Context, now time.Time) error {
	expired, err := s.st.ExpireKeepRequests(ctx, now)
	if err != nil {
		return err
	}
	for _, id := range expired {
		s.publishKeepRequest(ctx, id, nil)
	}
	for {
		due, err := s.st.DueTakings(ctx, now, 100)
		if err != nil {
			return err
		}
		if len(due) == 0 {
			break
		}
		for _, t := range due {
			var frames []store.RestoredFrame
			if err := s.st.InTx(ctx, func(tx *store.Tx) error {
				var err error
				frames, err = tx.PurgeTaking(ctx, t.ID, now)
				return err
			}); err != nil {
				return err
			}
			var extra []uuid.UUID
			if t.UserID != nil && !t.UserDeleted {
				extra = []uuid.UUID{*t.UserID}
			}
			for _, f := range frames {
				s.publishPlacementsUpdated(ctx, f.FrameID, f.PlacementIDs, "purged", &t.ID, extra)
			}
		}
		if len(due) < 100 {
			break
		}
	}
	if _, err := s.st.PruneOwnershipLog(ctx, now.Add(-ownershipLogRetention)); err != nil {
		return err
	}
	return nil
}
