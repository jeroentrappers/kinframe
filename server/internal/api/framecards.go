package api

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Frame cards (README §5.5, "Frame cards"): FrameSummary.now_showing and .reactions_today for the frame list
// and page, for callers who can see the library. One query for the whole list.

// startOfDay is midnight of t's day in loc.
func startOfDay(t time.Time, loc *time.Location) time.Time {
	l := t.In(loc)
	return time.Date(l.Year(), l.Month(), l.Day(), 0, 0, 0, 0, loc)
}

// fillFrameCards fills now_showing and reactions_today of frames[i] (built from views[i]).
func (s *Server) fillFrameCards(ctx context.Context, views []store.FrameView, frames []gen.FrameSummary) {
	now := s.now()
	var qs []store.FrameCardQuery
	screen := map[uuid.UUID]mirrorReport{}
	for i := range frames {
		if p := frames[i].Permissions.SeeLibrary; p == nil || !*p {
			continue
		}
		q := store.FrameCardQuery{FrameID: frames[i].Id, DayStart: startOfDay(now, frameZone(&views[i].Frame))}
		if frames[i].Online {
			if r, ok := s.mirrorReported(frames[i].Id); ok {
				q.Reported, screen[frames[i].Id] = r.placementID, r
			}
		}
		qs = append(qs, q)
	}
	if len(qs) == 0 {
		return
	}
	cards, err := s.st.FrameCards(ctx, s.st.Q(), qs)
	if err != nil {
		s.log.Warn("frame cards", "err", err)
		return
	}
	for i := range frames {
		c, ok := cards[frames[i].Id]
		if !ok {
			continue
		}
		frames[i].ReactionsToday = &gen.FrameReactionsToday{Count: c.ReactionCount, ItemCount: c.ReactionItemCount}
		if c.PlacementID == nil {
			continue
		}
		ns := &gen.FrameNowShowing{PlacementId: *c.PlacementID, ItemId: *c.ItemID, Source: gen.FrameNowShowingSourceLastPlaced,
			Since: c.ReceivedAt.UTC()}
		if c.Reported {
			ns.Source, ns.Since = gen.FrameNowShowingSourceScreen, screen[frames[i].Id].since
		}
		if c.ThumbKey != nil {
			ns.ThumbUrl = ptr(s.signedURL(*c.ThumbKey, thumbURLTTL))
		}
		if c.DisplayKey != nil {
			ns.DisplayUrl = ptr(s.signedURL(*c.DisplayKey, thumbURLTTL))
		}
		frames[i].NowShowing = ns
	}
}
