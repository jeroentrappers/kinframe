//lint:file-ignore U1000 Helpers other areas call (remoteFrameCount: server-leaving; remoteItemCredits: items rendered outside store.ItemRow).

package api

// Families on different servers, server-federation's helpers that the other areas call
// (docs/spec/leaving-servers-work.md). The signatures are fixed. Post additions, removals (unsend, admin removal,
// taking away, keep-request restores), birthdays and comments reach remote subscribers through the database
// triggers of 00037_federation.sql and 00046_federation_media_ready.sql (federation_changes), so the feed post
// paths need no call.

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// federationDiscovery sets discovery `server_id`, `server_name`, `base_url`, `api_version`, `federation` and
// `remote_families`.
func (s *Server) federationDiscovery(d *gen.Discovery) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	d.ApiVersion = ptr("v1")
	d.BaseUrl = ptr(s.cfg.PublicURL)
	d.ServerName = ptr(s.cfg.FederationLabel())
	if id := s.federationServerID(ctx); id != "" {
		d.ServerId = ptr(id)
	}
	d.Federation = s.federationInfo(ctx)
	d.RemoteFamilies = ptr(d.Federation.Enabled)
}

// remoteFrameCount is the number of live remote subscriptions (frames on other servers) of a family; leaving uses
// it for TakeAwayResult.remote_frames.
func (s *Server) remoteFrameCount(ctx context.Context, q db.DBTX, feedID uuid.UUID) (int, error) {
	return s.st.CountLiveRemoteSubscribers(ctx, q, feedID)
}

// remoteFeedSubscriptions renders a family's remote subscribers as Subscriptions (with `remote` set) for
// listFeedSubscriptions; status filters like the local list (nil: all the caller may see).
func (s *Server) remoteFeedSubscriptions(ctx context.Context, feedID, callerID uuid.UUID, status *gen.SubscriptionStatus) ([]gen.Subscription, error) {
	v, err := s.st.GetFeedView(ctx, s.st.Q(), callerID, feedID)
	if err != nil || v.Role == "" {
		return nil, err
	}
	statuses := statusFilter(status, v.IsAdmin())
	if len(statuses) == 0 {
		return nil, nil
	}
	rows, err := s.st.ListRemoteSubscribers(ctx, feedID, statuses)
	if err != nil {
		return nil, err
	}
	out := make([]gen.Subscription, len(rows))
	for i := range rows {
		out[i] = s.remoteSubscriptionOut(&rows[i], v.IsAdmin())
	}
	return out, nil
}

// remoteSubscriptionAction applies approve, reject or delete ("approve", "reject", "delete"; "get" reads) to a
// remote subscriber when subscriptionID is one (handled=false otherwise, so the local code goes on). It checks the
// caller is the family's owner or admin and tells B.
func (s *Server) remoteSubscriptionAction(ctx context.Context, callerID, subscriptionID uuid.UUID, action string) (handled bool, sub *gen.Subscription, err error) {
	return s.remoteAdminAction(ctx, callerID, subscriptionID, action)
}

// remoteFrameActivity queues a frame's reaction ("reaction", reaction or nil to clear), quick reply ("reply") or
// "seen" on a placement for A, when the placement is a remote copy (no-op otherwise). Called by the device
// reaction, reply and ack handlers after they stored the activity locally.
func (s *Server) remoteFrameActivity(ctx context.Context, placementID uuid.UUID, kind string, reaction *gen.Reaction, reply *gen.ReplyPreset) {
	s.queueRemoteActivity(ctx, placementID, kind, reaction, reply)
}

// remoteItemCredits fills Item.remote_sender for copied items. Items rendered from store.ItemRow have it already
// (itemJSON, from the item query); this fills items built elsewhere by looking them up.
func (s *Server) remoteItemCredits(ctx context.Context, items []gen.Item) {
	var ids []uuid.UUID
	for _, it := range items {
		if it.RemoteSender == nil {
			ids = append(ids, it.Id)
		}
	}
	if len(ids) == 0 {
		return
	}
	type credit struct {
		ID   uuid.UUID `db:"id"`
		Name string    `db:"name"`
		Srv  string    `db:"server_name"`
		URL  string    `db:"server_url"`
	}
	rows, err := store.FederationMany[credit](s.st.Pool.Query(ctx, `SELECT i.id, rsd.display_name AS name,
		COALESCE(NULLIF(rsv.server_name, ''), rsv.host) AS server_name, rsv.base_url AS server_url
		FROM items i JOIN remote_senders rsd ON rsd.id = i.remote_sender_id JOIN remote_servers rsv ON rsv.id = rsd.server_id
		WHERE i.id = ANY($1)`, ids))
	if err != nil {
		return
	}
	by := map[uuid.UUID]credit{}
	for _, r := range rows {
		by[r.ID] = r
	}
	for i := range items {
		if c, ok := by[items[i].Id]; ok {
			items[i].RemoteSender = &gen.ItemRemoteSender{Name: c.Name, ServerName: c.Srv, ServerUrl: c.URL}
		}
	}
}

// remoteFrameBirthdays is the birthday people a frame gets from families on other servers (merged into the frame's
// `birthdays` change by the birthday code; the same person shows once).
func (s *Server) remoteFrameBirthdays(ctx context.Context, q db.DBTX, frameID uuid.UUID) ([]gen.FrameBirthday, error) {
	return s.remoteFrameBirthdaysOf(ctx, q, frameID)
}

// federationFeedEnded ends every remote subscription of a family (deleted: "feed_deleted"; allow_remote_frames turned
// off: "removed_by_feed") inside the caller's transaction; the other servers are told after commit (the loop sends
// the DELETE and the change feed says `removed`).
func (s *Server) federationFeedEnded(ctx context.Context, tx *store.Tx, feedID uuid.UUID, reason string) error {
	ids, err := s.endRemoteSubscribersWhere(ctx, tx, reason, `x.feed_id = $1`, feedID)
	if err == nil && len(ids) > 0 {
		s.wakeFederation()
	}
	return err
}

// federationFrameRemoved ends a frame's remote follows when the frame is removed (tells A after commit).
func (s *Server) federationFrameRemoved(ctx context.Context, tx *store.Tx, frameID uuid.UUID) error {
	tag, err := tx.Exec(ctx, `UPDATE remote_follows SET status = 'removed', removed_reason = 'unsubscribed', ended_at = now(),
		unsubscribe_pending = remote_subscription_id IS NOT NULL, next_pull_at = NULL
		WHERE frame_id = $1 AND status IN ('pending', 'active', 'error')`, frameID)
	if err == nil && tag.RowsAffected() > 0 {
		s.wakeFederation()
	}
	return err
}

// runFederationJob handles server-federation's worker jobs (kinds `federation.*`; handled=false for other kinds).
// The federation work runs in RunFederation; there are no jobs of its own yet.
func (s *Server) runFederationJob(ctx context.Context, job *store.Job) (bool, error) {
	_, _ = ctx, job
	return false, nil
}
