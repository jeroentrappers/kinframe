package api

import (
	"context"
	"fmt"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Channel invites (docs/spec/email-inbox.md §10.4): invites the server makes for an outside channel (email now;
// other channels register their own resolver). They are not listed, are single use and always need a manager's
// approval; the join request carries via, via_name, via_ref and via_matches.

// channelJoinResolver turns a channel invite's via_ref into the join request's channel fields for the user who
// redeemed it.
type channelJoinResolver func(s *Server, ctx context.Context, tx *store.Tx, viaRef string, u *store.User) (store.JoinChannel, error)

// channelJoins maps invites.via to its resolver. Each channel registers itself (init in its own file).
var channelJoins = map[string]channelJoinResolver{}

func init() { channelJoins["email"] = emailChannelJoin }

// markChannelJoin fills a fresh join request's channel fields.
func (s *Server) markChannelJoin(ctx context.Context, tx *store.Tx, jr *store.JoinRequest, via, viaRef *string, u *store.User, feed bool) (*store.JoinRequest, error) {
	resolve := channelJoins[*via]
	if resolve == nil {
		return nil, fmt.Errorf("channel invite: unknown via %q", *via)
	}
	ref := ""
	if viaRef != nil {
		ref = *viaRef
	}
	ch, err := resolve(s, ctx, tx, ref, u)
	if err != nil {
		return nil, err
	}
	ch.Via = *via
	if feed {
		return tx.SetFeedJoinRequestChannel(ctx, jr.ID, ch)
	}
	return tx.SetJoinRequestChannel(ctx, jr.ID, ch)
}
