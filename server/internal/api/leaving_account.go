package api

// Account deletion with the photos (docs/spec/leaving.md §4.4, README §5.21 "Account deletion"): `take` takes
// everything away with the 30-day notice; `leave` hands each photo still in a family or on a frame over to that
// family's or frame's owner, credited "<name> (former member)", and logs the handover for the operator.

import (
	"context"
	"encoding/json"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

type accountDeletion struct {
	user *store.User
	take bool
	ta   *takeAway
}

// newAccountDeletion prepares deleteMe's photo choice (snapshotting who sees the posts that may go).
func (s *Server) newAccountDeletion(ctx context.Context, u *store.User, take bool) *accountDeletion {
	d := &accountDeletion{user: u, take: take}
	if take {
		d.ta = s.newTakeAway(u, "account")
		d.ta.spec.All = true
		s.snapshotTakeAway(ctx, d.ta)
	}
	return d
}

// accountDeletionPhotos applies the choice in deleteMe's transaction.
func (s *Server) accountDeletionPhotos(ctx context.Context, tx *store.Tx, d *accountDeletion) error {
	if d.take {
		return s.runTakeAway(ctx, tx, d.ta)
	}
	now := s.now()
	scopes, err := tx.HandOver(ctx, d.user.ID, d.user.DisplayName, now)
	if err != nil {
		return err
	}
	for _, sc := range scopes {
		kind := sc.Kind
		e := store.OwnershipLogEntry{Kind: "account_handover", ScopeKind: &kind, ScopeName: ptr(sc.Name), FromUserID: &d.user.ID,
			FromName: ptr(d.user.DisplayName), ToUserID: ptr(sc.OwnerID), ToName: ptr(sc.Owner), ItemCount: ptr(sc.Items), CreatedAt: now}
		id := sc.ScopeID
		if kind == "feed" {
			e.FeedID = &id
		} else {
			e.FrameID = &id
		}
		e.Details, _ = json.Marshal(map[string]any{"consent": "account_deletion", "photos": "leave"})
		if err := tx.LogOwnership(ctx, e); err != nil {
			return err
		}
	}
	return nil
}

// announceAccountDeletion tells the frames and families after commit (take: photos_taken_away and the events).
func (s *Server) announceAccountDeletion(ctx context.Context, d *accountDeletion) {
	if d.take && d.ta != nil {
		s.announceTakeAway(ctx, d.ta)
	}
}
