package api

import (
	"context"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// The family's settings for frames on other servers (README §5.22, decision D): `allow_remote_frames` (default
// off) and `allow_remote` on subscribe invites.

// federationFeedSettings stores allow_remote_frames (nil: unchanged) in the caller's transaction; turning it off
// ends every remote subscription of the family (`removed_by_feed`).
func (s *Server) federationFeedSettings(ctx context.Context, tx *store.Tx, feedID uuid.UUID, allow *bool) error {
	if allow == nil {
		return nil
	}
	if _, err := tx.Exec(ctx, `UPDATE feeds SET allow_remote_frames = $2 WHERE id = $1`, feedID, *allow); err != nil {
		return err
	}
	if !*allow {
		return s.federationFeedEnded(ctx, tx, feedID, "removed_by_feed")
	}
	return nil
}

// federationInviteSettings sets allow_remote on a new invite: subscribe invites of families that allow frames on
// other servers only.
func (s *Server) federationInviteSettings(ctx context.Context, tx *store.Tx, inv *store.FeedInvite, v *store.FeedView, allow *bool) (*store.FeedInvite, error) {
	if allow == nil || !*allow {
		return inv, nil
	}
	if inv.Purpose != "subscribe" {
		return nil, problem.Validation("allow_remote", "subscribe_only", "only subscribe invites can be used by frames on other servers")
	}
	if !v.AllowRemoteFrames {
		return nil, problem.Validation("allow_remote", "not_allowed", "the family does not allow frames on other servers")
	}
	if _, err := tx.Exec(ctx, `UPDATE feed_invites SET allow_remote = true WHERE id = $1`, inv.ID); err != nil {
		return nil, err
	}
	inv.AllowRemote = true
	return inv, nil
}
