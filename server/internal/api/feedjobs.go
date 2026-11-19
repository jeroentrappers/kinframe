package api

import (
	"context"
	"encoding/json"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// ---------------------------------------------------------------- background jobs

// RunJob runs the feed jobs (worker.Worker.Jobs): fanout of new posts,
// reconcile (history backfill and trim) and sweeps after removals. Long work
// is done in batches; a job re-enqueues itself until it is done.
func (s *Server) RunJob(ctx context.Context, job *store.Job) (bool, error) {
	for _, run := range []func(context.Context, *store.Job) (bool, error){s.runSocialJob, s.runChatJob, s.runEngagementJob} {
		if handled, err := run(ctx, job); handled {
			return true, err
		}
	}
	switch job.Kind {
	case store.JobFeedFanout:
		var p store.PostJob
		if err := json.Unmarshal(job.Payload, &p); err != nil {
			return true, err
		}
		if _, err := s.st.FeedFanout(ctx, p.PostID); err != nil {
			return true, err
		}
		s.publishFeedPost(ctx, p.PostID)
		return true, nil
	case store.JobFeedReconcile:
		var p store.ReconcileJob
		if err := json.Unmarshal(job.Payload, &p); err != nil {
			return true, err
		}
		res, err := s.st.FeedReconcile(ctx, p, s.Limits(ctx).MaxFeedBackfillItems)
		if err != nil {
			return true, err
		}
		if res.More {
			return true, s.st.InTx(ctx, func(tx *store.Tx) error { return tx.Enqueue(ctx, store.JobFeedReconcile, p) })
		}
		if res.Done && res.Sub != nil {
			s.publishSubscription(ctx, res.Sub.ID, nil)
		}
		return true, nil
	case store.JobFeedSweep:
		var p store.SweepJob
		if err := json.Unmarshal(job.Payload, &p); err != nil {
			return true, err
		}
		res, err := s.st.FeedSweep(ctx, p.SubscriptionID)
		if err != nil {
			return true, err
		}
		if res.More {
			return true, s.st.InTx(ctx, func(tx *store.Tx) error { return tx.Enqueue(ctx, store.JobFeedSweep, p) })
		}
		if res.Sub != nil {
			// A deleted feed goes for good once nothing of it is left on frames.
			if _, err := s.st.FinishFeedDelete(ctx, res.Sub.FeedID); err != nil {
				return true, err
			}
		}
		return true, nil
	}
	return false, nil
}

// ---------------------------------------------------------------- notifications

// publishFeedPost sends feed_post.updated (receipt summary) to the poster and
// the feed's owner and admins.
func (s *Server) publishFeedPost(ctx context.Context, postID uuid.UUID) {
	var feedID uuid.UUID
	var poster *uuid.UUID
	err := s.st.Pool.QueryRow(ctx, `SELECT p.feed_id, p.poster_user_id FROM feed_posts p JOIN feeds f ON f.id = p.feed_id AND f.deleted_at IS NULL
		WHERE p.id = $1 AND p.removed_at IS NULL`, postID).Scan(&feedID, &poster)
	if err != nil {
		return
	}
	rs, err := s.receiptsOf(ctx, []uuid.UUID{postID})
	if err != nil {
		s.log.Warn("feed post receipts", "post", postID, "err", err)
		return
	}
	sum := feedReceiptSummaryJSON{}
	if pr := rs[postID]; pr != nil {
		sum = pr.summary
	}
	to, err := s.st.FeedAdminIDs(ctx, s.st.Q(), feedID)
	if err != nil {
		return
	}
	if poster != nil {
		to = appendUnique(to, *poster)
	}
	s.notify.Users(to, notify.Event{"type": "feed_post.updated", "feed_id": feedID, "post_id": postID, "summary": sum}, nil)
	if poster != nil {
		// The poster's sent history (Send.feeds[]) changed too.
		var sendID *uuid.UUID
		if s.st.Pool.QueryRow(ctx, `SELECT send_id FROM feed_posts WHERE id = $1`, postID).Scan(&sendID) == nil && sendID != nil {
			s.publishSendUpdates(ctx, map[uuid.UUID]uuid.UUID{*sendID: *poster}, "")
		}
	}
}

// publishFeedPostsOf publishes feed_post.updated for the posts holding these placements.
func (s *Server) publishFeedPostsOf(ctx context.Context, placementIDs []uuid.UUID) {
	if len(placementIDs) == 0 {
		return
	}
	pp, err := s.st.PostsOfPlacements(ctx, s.st.Q(), placementIDs)
	if err != nil {
		s.log.Warn("posts of placements", "err", err)
		return
	}
	done := map[uuid.UUID]bool{}
	for _, p := range pp {
		if !done[p.PostID] {
			done[p.PostID] = true
			s.publishFeedPost(ctx, p.PostID)
		}
	}
}

// placementPushIDs are the ids a push about a placement carries, with the
// feed and post when it came through a feed (README §6.3).
func (s *Server) placementPushIDs(ctx context.Context, p *store.PlacementRow, f *store.Frame) map[string]string {
	ids := placementIDs(p, f)
	pp, err := s.st.PostsOfPlacements(ctx, s.st.Q(), []uuid.UUID{p.Placement.ID})
	if err != nil || len(pp) == 0 {
		return ids
	}
	pick := pp[0]
	for _, x := range pp {
		if p.OriginFeedID != nil && x.FeedID == *p.OriginFeedID {
			pick = x
			break
		}
	}
	ids["feed_id"], ids["post_id"] = pick.FeedID.String(), pick.PostID.String()
	return ids
}

// addFeedIDs copies feed_id and post_id from push ids into a websocket event.
func addFeedIDs(ev notify.Event, ids map[string]string) {
	if f, ok := ids["feed_id"]; ok {
		ev["feed_id"], ev["post_id"] = f, ids["post_id"]
	}
}

func appendUnique(ids []uuid.UUID, more ...uuid.UUID) []uuid.UUID {
	for _, m := range more {
		found := false
		for _, id := range ids {
			if id == m {
				found = true
				break
			}
		}
		if !found {
			ids = append(ids, m)
		}
	}
	return ids
}

// subscriptionAudience: the requester, the feed's owner and admins and the frame's managers.
func (s *Server) subscriptionAudience(ctx context.Context, sv *store.SubscriptionView) []uuid.UUID {
	var to []uuid.UUID
	if sv.RequestedBy != nil {
		to = append(to, *sv.RequestedBy)
	}
	if ids, err := s.st.FeedAdminIDs(ctx, s.st.Q(), sv.FeedID); err == nil {
		to = appendUnique(to, ids...)
	}
	if ids, err := s.st.FrameManagerIDs(ctx, sv.FrameID); err == nil {
		to = appendUnique(to, ids...)
	}
	return to
}

// publishSubscription sends subscription.updated, rendered for each recipient
// (field rules depend on who looks), with optional pushes per recipient.
func (s *Server) publishSubscription(ctx context.Context, subID uuid.UUID, pushes map[uuid.UUID]*notify.Push) {
	sv, err := s.st.GetSubscriptionView(ctx, s.st.Q(), subID)
	if err != nil {
		return
	}
	to := s.subscriptionAudience(ctx, sv)
	for id := range pushes {
		to = appendUnique(to, id)
	}
	for _, uid := range to {
		v := s.viewerOf(ctx, uid, sv)
		if !v.canSee(&sv.Subscription) && pushes[uid] == nil {
			continue
		}
		s.notify.User(uid, notify.Event{"type": "subscription.updated", "feed_id": sv.FeedID, "frame_id": sv.FrameID,
			"subscription": s.subscriptionOut(ctx, sv, v)}, pushes[uid])
	}
}

// announceSubscriptionRequest tells the feed's owner and admins that a frame asks to subscribe.
func (s *Server) announceSubscriptionRequest(ctx context.Context, subID uuid.UUID) {
	sv, err := s.st.GetSubscriptionView(ctx, s.st.Q(), subID)
	if err != nil {
		return
	}
	admins, err := s.st.FeedAdminIDs(ctx, s.st.Q(), sv.FeedID)
	if err != nil {
		return
	}
	name := ""
	if sv.RequestedByName != nil {
		name = *sv.RequestedByName
	}
	for _, uid := range admins {
		v := s.viewerOf(ctx, uid, sv)
		s.notify.User(uid, notify.Event{"type": "subscription.request", "feed_id": sv.FeedID, "subscription": s.subscriptionOut(ctx, sv, v)},
			&notify.Push{Key: "subscription_request", Args: map[string]string{"name": name, "feed": sv.FeedName, "frame": sv.FrameName},
				IDs: map[string]string{"feed_id": sv.FeedID.String(), "frame_id": sv.FrameID.String(), "subscription_id": sv.ID.String()}})
	}
}

// notifySubscriptionRemoved tells a frame's managers that a subscription ended
// from the feed side (feed deleted), pushing its owner and admins. The row may
// already be gone, so the event carries what is known.
func (s *Server) notifySubscriptionRemoved(ctx context.Context, sub *store.Subscription, feedName string) {
	frameName := ""
	if f, err := s.st.GetFrame(ctx, s.st.Q(), sub.FrameID); err == nil {
		frameName = f.Name
	}
	owners, _ := s.st.MemberIDs(ctx, sub.FrameID, true)
	managers, _ := s.st.FrameManagerIDs(ctx, sub.FrameID)
	ev := notify.Event{"type": "subscription.updated", "feed_id": sub.FeedID, "frame_id": sub.FrameID, "subscription": map[string]any{
		"id": sub.ID, "status": "removed", "removed_reason": "feed_deleted", "history": sub.History,
		"feed": map[string]any{"id": sub.FeedID, "name": feedName}, "frame": map[string]any{"id": sub.FrameID, "name": frameName},
		"created_at": sub.CreatedAt,
	}}
	push := &notify.Push{Key: "subscription_removed", Args: map[string]string{"feed": feedName, "frame": frameName},
		IDs: map[string]string{"feed_id": sub.FeedID.String(), "frame_id": sub.FrameID.String(), "subscription_id": sub.ID.String()}}
	for _, uid := range appendUnique(owners, managers...) {
		var p *notify.Push
		for _, o := range owners {
			if o == uid {
				p = push
			}
		}
		s.notify.User(uid, ev, p)
	}
}

// notifyFeedMembership tells a member their feed role changed, they were
// added (approved) or removed.
func (s *Server) notifyFeedMembership(ctx context.Context, feedID uuid.UUID, feedName string, userID uuid.UUID) {
	s.notify.User(userID, notify.Event{"type": "feed.membership_updated", "feed_id": feedID},
		&notify.Push{Key: "feed_membership", Args: map[string]string{"feed": feedName}, IDs: map[string]string{"feed_id": feedID.String()}})
}

// announceFeedJoinRequest tells the feed's owner and admins someone asks to join.
func (s *Server) announceFeedJoinRequest(ctx context.Context, v *store.FeedView, jr *store.JoinRequest) {
	admins, err := s.st.FeedAdminIDs(ctx, s.st.Q(), v.ID)
	if err != nil {
		return
	}
	s.notify.Users(admins, notify.Event{"type": "feed.join_request", "feed_id": v.ID, "request": s.joinRequestJSON(jr)},
		&notify.Push{Key: "feed_join_request", Args: map[string]string{"name": jr.UserName, "feed": v.Name},
			IDs: map[string]string{"feed_id": v.ID.String(), "request_id": jr.ID.String()}})
}
