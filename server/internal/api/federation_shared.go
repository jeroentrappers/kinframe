//lint:file-ignore U1000 Fixed signatures for the parallel leaving and federation work; their callers arrive with the areas (docs/spec/leaving-servers-work.md).

package api

// Families on different servers, server-federation's helpers that the other areas call
// (docs/spec/leaving-servers-work.md). The signatures are fixed; the bodies are stubs until server-federation fills
// them. Post additions, removals (unsend, admin removal, taking away, keep-request restores), birthdays and comments
// reach remote subscribers through the database triggers of 00037_federation.sql (federation_changes), so the
// feed post paths need no call.

import (
	"context"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// federationDiscovery sets discovery `server_id`, `server_name`, `base_url`, `api_version`, `federation` and
// `remote_families`. The stub only says federation is off.
func (s *Server) federationDiscovery(d *gen.Discovery) {
	d.ApiVersion = ptr("v1")
	d.Federation = &gen.FederationInfo{Enabled: false}
	d.RemoteFamilies = ptr(false)
}

// remoteFrameCount is the number of live remote subscriptions (frames on other servers) of a family; leaving uses
// it for TakeAwayResult.remote_frames.
func (s *Server) remoteFrameCount(ctx context.Context, q db.DBTX, feedID uuid.UUID) (int, error) {
	_, _, _ = ctx, q, feedID
	return 0, nil
}

// remoteFeedSubscriptions renders a family's remote subscribers as Subscriptions (with `remote` set) for
// listFeedSubscriptions; status filters like the local list (nil: all the caller may see).
func (s *Server) remoteFeedSubscriptions(ctx context.Context, feedID, callerID uuid.UUID, status *gen.SubscriptionStatus) ([]gen.Subscription, error) {
	_, _, _, _ = ctx, feedID, callerID, status
	return nil, nil
}

// remoteSubscriptionAction applies approve, reject or delete ("approve", "reject", "delete") to a remote subscriber
// when subscriptionID is one (handled=false otherwise, so the local code goes on). It checks the caller is the
// family's owner or admin and tells B.
func (s *Server) remoteSubscriptionAction(ctx context.Context, callerID, subscriptionID uuid.UUID, action string) (handled bool, sub *gen.Subscription, err error) {
	_, _, _, _ = ctx, callerID, subscriptionID, action
	return false, nil, nil
}

// remoteFrameActivity queues a frame's reaction ("reaction", reaction or nil to clear), quick reply ("reply") or
// "seen" on a placement for A, when the placement is a remote copy (no-op otherwise). Called by the device
// reaction, reply and ack handlers after they stored the activity locally.
func (s *Server) remoteFrameActivity(ctx context.Context, placementID uuid.UUID, kind string, reaction *gen.Reaction, reply *gen.ReplyPreset) {
	_, _, _, _, _ = ctx, placementID, kind, reaction, reply
}

// remoteItemCredits fills Item.remote_sender for copied items (library, sends, timeline: wherever items render).
func (s *Server) remoteItemCredits(ctx context.Context, items []gen.Item) {
	_, _ = ctx, items
}

// remoteFrameBirthdays is the birthday people a frame gets from families on other servers (merged into the frame's
// `birthdays` change by the birthday code; the same person shows once).
func (s *Server) remoteFrameBirthdays(ctx context.Context, q db.DBTX, frameID uuid.UUID) ([]gen.FrameBirthday, error) {
	_, _, _ = ctx, q, frameID
	return nil, nil
}

// federationFeedEnded ends every remote subscription of a family (deleted: "feed_deleted"; allow_remote_frames turned
// off: "removed_by_feed") inside the caller's transaction; the other servers are told after commit.
func (s *Server) federationFeedEnded(ctx context.Context, tx *store.Tx, feedID uuid.UUID, reason string) error {
	_, _, _, _ = ctx, tx, feedID, reason
	return nil
}

// federationFrameRemoved ends a frame's remote follows when the frame is removed (tells A after commit).
func (s *Server) federationFrameRemoved(ctx context.Context, tx *store.Tx, frameID uuid.UUID) error {
	_, _, _ = ctx, tx, frameID
	return nil
}

// runFederationJob handles server-federation's worker jobs (kinds `federation.*`; handled=false for other kinds).
func (s *Server) runFederationJob(ctx context.Context, job *store.Job) (bool, error) {
	_, _ = ctx, job
	return false, nil
}

// RunFederation is server-federation's background loop: pings for new federation_changes (debounced 10 s), pulls
// of remote follows (on ping and every 15 minutes), downloads, the activity outbox, unsubscribe retries, well-known
// refreshes and key rotation.
func (s *Server) RunFederation(ctx context.Context) {
	<-ctx.Done()
}
