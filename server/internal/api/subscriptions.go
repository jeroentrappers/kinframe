package api

import (
	"context"
	"sort"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Subscriptions: a frame subscribing to a feed (feeds.md §4.2, §5.1; README §5.12).

// ---------------------------------------------------------------- JSON

type subFeedJSON struct {
	ID          uuid.UUID `json:"id"`
	Name        string    `json:"name"`
	CoverURL    *string   `json:"cover_url,omitempty"`
	MemberCount *int      `json:"member_count,omitempty"`
	AdminNames  []string  `json:"admin_names,omitempty"`
}

type subFrameJSON struct {
	ID         uuid.UUID  `json:"id"`
	Name       string     `json:"name"`
	Location   *string    `json:"location,omitempty"`
	Online     bool       `json:"online"`
	LastSeenAt *time.Time `json:"last_seen_at,omitempty"`
	// The frame app shows birthdays (its latest heartbeat lists `birthdays`).
	BirthdaysSupported bool `json:"birthdays_supported"`
}

type subBackfillJSON struct {
	State  string `json:"state"`
	Placed int    `json:"placed"`
	Total  int    `json:"total"`
}

type subEstimateJSON struct {
	Items int   `json:"items"`
	Bytes int64 `json:"bytes"`
}

type subscriptionJSON struct {
	ID              uuid.UUID        `json:"id"`
	Status          string           `json:"status"`
	History         string           `json:"history"`
	HistoryCount    *int             `json:"history_count"`
	Feed            subFeedJSON      `json:"feed"`
	Frame           subFrameJSON     `json:"frame"`
	RequestedByName *string          `json:"requested_by_name,omitempty"`
	ViaInvite       bool             `json:"via_invite"`
	Birthdays       bool             `json:"birthdays"`
	BirthdayCount   int              `json:"birthday_count"`
	CreatedAt       time.Time        `json:"created_at"`
	ActivatedAt     *time.Time       `json:"activated_at,omitempty"`
	PausedAt        *time.Time       `json:"paused_at,omitempty"`
	EndedAt         *time.Time       `json:"ended_at,omitempty"`
	RemovedReason   *string          `json:"removed_reason,omitempty"`
	Backfill        subBackfillJSON  `json:"backfill"`
	Estimate        *subEstimateJSON `json:"estimate,omitempty"`
	CanManage       bool             `json:"can_manage"`
	CanDecide       bool             `json:"can_decide"`
}

// subViewer is what one person is on both sides of a subscription.
type subViewer struct {
	userID      uuid.UUID
	feedRole    string // "" when not a feed member
	frameMember bool
	frameMgr    bool // owner, admin or change_settings on the frame
}

func (v subViewer) feedAdmin() bool { return v.feedRole == "owner" || v.feedRole == "admin" }

// canSee: frame managers and feed owners/admins see every status; other
// members of either side only active and paused ones; the requester their own.
func (v subViewer) canSee(sub *store.Subscription) bool {
	if v.frameMgr || v.feedAdmin() || (sub.RequestedBy != nil && *sub.RequestedBy == v.userID) {
		return true
	}
	return (v.frameMember || v.feedRole != "") && (sub.Status == "active" || sub.Status == "paused")
}

func (s *Server) viewerOf(ctx context.Context, userID uuid.UUID, sv *store.SubscriptionView) subViewer {
	v := subViewer{userID: userID}
	if m, err := s.st.GetFeedMember(ctx, s.st.Q(), sv.FeedID, userID); err == nil {
		v.feedRole = m.Role
	}
	if m, err := s.st.GetMembership(ctx, s.st.Q(), sv.FrameID, userID); err == nil {
		v.frameMember, v.frameMgr = true, m.Effective().ChangeSettings
	}
	return v
}

func (s *Server) subscriptionOut(ctx context.Context, sv *store.SubscriptionView, v subViewer) subscriptionJSON {
	out := subscriptionJSON{
		ID: sv.ID, Status: sv.Status, History: sv.History, HistoryCount: sv.HistoryCount,
		Feed:      subFeedJSON{ID: sv.FeedID, Name: sv.FeedName, CoverURL: s.feedCoverURL(sv.FeedCoverKey)},
		Frame:     subFrameJSON{ID: sv.FrameID, Name: sv.FrameName, Location: sv.FrameLocation, Online: s.hub.FrameOnline(sv.FrameID), LastSeenAt: sv.LastSeenAt},
		ViaInvite: sv.ViaInvite, Birthdays: sv.Birthdays, BirthdayCount: sv.BirthdayCount, CreatedAt: sv.CreatedAt, ActivatedAt: sv.ActivatedAt, PausedAt: sv.PausedAt, EndedAt: sv.EndedAt,
		RemovedReason: sv.RemovedReason,
		Backfill:      subBackfillJSON{State: sv.BackfillState, Placed: sv.BackfillPlaced, Total: sv.BackfillTotal},
		CanManage:     v.frameMgr && sv.Live(), CanDecide: v.feedAdmin() && sv.Live(),
	}
	for _, f := range sv.FrameFeatures {
		if f == featureBirthdays {
			out.Frame.BirthdaysSupported = true
		}
	}
	if v.frameMgr || v.feedAdmin() {
		out.RequestedByName = sv.RequestedByName
	}
	// The frame side sees whom it subscribes to (feeds.md §9).
	if v.frameMgr || v.feedRole != "" {
		out.Feed.MemberCount = ptr(sv.FeedMemberCount)
		if names, err := s.st.FeedAdminNames(ctx, s.st.Q(), sv.FeedID); err == nil {
			out.Feed.AdminNames = names
		}
	}
	if sv.Live() {
		if est, err := s.st.EstimateSubscription(ctx, &sv.Subscription, s.Limits(ctx).MaxFeedBackfillItems); err == nil {
			out.Estimate = &subEstimateJSON{Items: est.Items, Bytes: est.Bytes}
		} else {
			s.log.Warn("subscription estimate", "subscription", sv.ID, "err", err)
		}
	}
	return out
}

// subscriptionHistory validates a history option (history_count only with last_n).
func subscriptionHistory(h *gen.SubscriptionHistory, count *int) (string, *int, error) {
	history := "none"
	if h != nil {
		history = string(*h)
	}
	if history != "last_n" {
		return history, nil, nil
	}
	if count == nil {
		return "", nil, problem.Validation("history_count", "required", "history_count is required with last_n")
	}
	return history, count, nil
}

func statusFilter(st *gen.SubscriptionStatus, all bool) []string {
	visible := []string{"active", "paused"}
	if all {
		visible = []string{"pending", "active", "paused", "rejected", "removed"}
	}
	if st == nil {
		return visible
	}
	for _, v := range visible {
		if v == string(*st) {
			return []string{v}
		}
	}
	return []string{}
}

func sortIDs(ids []uuid.UUID) {
	sort.Slice(ids, func(i, j int) bool { return ids[i].String() < ids[j].String() })
}

// ---------------------------------------------------------------- lists and reads

func (s *Server) ListFeedSubscriptions(ctx context.Context, req gen.ListFeedSubscriptionsRequestObject) (gen.ListFeedSubscriptionsResponseObject, error) {
	fv, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	out := []any{}
	statuses := statusFilter(req.Params.Status, fv.IsAdmin())
	if len(statuses) == 0 {
		return ok(out), nil
	}
	subs, err := s.st.ListFeedSubscriptions(ctx, req.FeedId, statuses)
	if err != nil {
		return nil, err
	}
	for i := range subs {
		v := s.viewerOf(ctx, me(ctx).ID, &subs[i])
		out = append(out, s.subscriptionOut(ctx, &subs[i], v))
	}
	// Frames on other servers (README §5.22).
	remote, err := s.remoteFeedSubscriptions(ctx, req.FeedId, me(ctx).ID, req.Params.Status)
	if err != nil {
		return nil, err
	}
	for _, r := range remote {
		out = append(out, r)
	}
	return ok(out), nil
}

func (s *Server) ListFrameSubscriptions(ctx context.Context, req gen.ListFrameSubscriptionsRequestObject) (gen.ListFrameSubscriptionsResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	out := []subscriptionJSON{}
	statuses := statusFilter(req.Params.Status, m.Effective().ChangeSettings)
	if len(statuses) == 0 {
		return ok(out), nil
	}
	subs, err := s.st.ListFrameSubscriptions(ctx, req.FrameId, statuses)
	if err != nil {
		return nil, err
	}
	for i := range subs {
		v := s.viewerOf(ctx, me(ctx).ID, &subs[i])
		out = append(out, s.subscriptionOut(ctx, &subs[i], v))
	}
	return ok(out), nil
}

// visibleSubscription loads a subscription the caller may see (else 404).
func (s *Server) visibleSubscription(ctx context.Context, id uuid.UUID) (*store.SubscriptionView, subViewer, error) {
	sv, err := s.st.GetSubscriptionView(ctx, s.st.Q(), id)
	if err != nil {
		return nil, subViewer{}, notFound(err, "subscription")
	}
	v := s.viewerOf(ctx, me(ctx).ID, sv)
	if !v.canSee(&sv.Subscription) {
		return nil, subViewer{}, problem.New(problem.NotFound, "subscription not found")
	}
	return sv, v, nil
}

func (s *Server) renderSubscription(ctx context.Context, id uuid.UUID) (subscriptionJSON, error) {
	sv, err := s.st.GetSubscriptionView(ctx, s.st.Q(), id)
	if err != nil {
		return subscriptionJSON{}, notFound(err, "subscription")
	}
	return s.subscriptionOut(ctx, sv, s.viewerOf(ctx, me(ctx).ID, sv)), nil
}

func (s *Server) GetSubscription(ctx context.Context, req gen.GetSubscriptionRequestObject) (gen.GetSubscriptionResponseObject, error) {
	if handled, sub, err := s.remoteSubscriptionAction(ctx, me(ctx).ID, req.SubscriptionId, "get"); handled { // a frame on another server
		if err != nil {
			return nil, err
		}
		return ok(sub), nil
	}
	sv, v, err := s.visibleSubscription(ctx, req.SubscriptionId)
	if err != nil {
		return nil, err
	}
	return ok(s.subscriptionOut(ctx, sv, v)), nil
}

// ---------------------------------------------------------------- request

type subRef struct {
	SubscriptionID uuid.UUID `json:"subscription_id"`
}

func (s *Server) RequestSubscription(ctx context.Context, req gen.RequestSubscriptionRequestObject) (gen.RequestSubscriptionResponseObject, error) {
	u := me(ctx)
	fv, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev subRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "subscription", key, &prev); err != nil {
			return nil, err
		} else if found {
			out, err := s.renderSubscription(ctx, prev.SubscriptionID)
			if err != nil {
				return nil, err
			}
			return created(out), nil
		}
	}
	b := req.Body
	if _, err := s.managedFrames(ctx, []uuid.UUID{b.FrameId}); err != nil {
		return nil, err
	}
	history, count, err := subscriptionHistory(b.History, b.HistoryCount)
	if err != nil {
		return nil, err
	}
	active := fv.IsAdmin() || fv.AutoApproveSubscriptions
	limit := s.Limits(ctx).MaxSubscriptionsPerFeed
	id := store.NewID()
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFrame(ctx, b.FrameId); err != nil {
			return notFound(err, "frame")
		}
		if sub, err := s.st.LiveSubscription(ctx, tx, req.FeedId, b.FrameId); err == nil {
			return problem.New(problem.AlreadySubscribed, "%s", sub.ID.String())
		} else if err != store.ErrNotFound {
			return err
		}
		n, err := s.st.CountLiveSubscriptions(ctx, tx, req.FeedId)
		if err != nil {
			return err
		}
		if n >= limit {
			return problem.New(problem.LimitReached, "a feed can be shown on at most %d frames", limit)
		}
		if err := tx.CreateSubscription(ctx, store.Subscription{ID: id, FeedID: req.FeedId, FrameID: b.FrameId, History: history,
			HistoryCount: count, RequestedBy: &u.ID, Birthdays: b.Birthdays == nil || *b.Birthdays}); err != nil {
			return err
		}
		if active {
			var by *uuid.UUID
			if fv.IsAdmin() {
				by = &u.ID
			}
			if err := tx.ActivateSubscription(ctx, id, by); err != nil {
				return err
			}
		}
		if key != "" {
			return tx.SaveIdempotent(ctx, u.ID, "subscription", key, subRef{id})
		}
		return nil
	})
	if db.IsUniqueViolation(err, "idempotency_keys_pkey") {
		var prev subRef
		if found, _ := store.IdempotentResult(ctx, s.st.Q(), u.ID, "subscription", key, &prev); found {
			id, err, active = prev.SubscriptionID, nil, false
		}
	}
	if db.IsUniqueViolation(err, "subscriptions_one_live") {
		return nil, problem.New(problem.AlreadySubscribed, "")
	}
	if err != nil {
		return nil, err
	}
	if active {
		s.publishSubscription(ctx, id, nil)
	} else {
		s.announceSubscriptionRequest(ctx, id)
	}
	out, err := s.renderSubscription(ctx, id)
	if err != nil {
		return nil, err
	}
	return created(out), nil
}

// ---------------------------------------------------------------- transitions

// transition runs fn with the subscription's frame and row locked (in that
// order, as everything that places on the frame).
func (s *Server) transition(ctx context.Context, sv *store.SubscriptionView, fn func(tx *store.Tx, sub *store.Subscription) error) error {
	return s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFrame(ctx, sv.FrameID); err != nil {
			return notFound(err, "frame")
		}
		sub, err := tx.LockSubscription(ctx, sv.ID)
		if err != nil {
			return notFound(err, "subscription")
		}
		return fn(tx, sub)
	})
}

// errUnchanged marks a repeated transition: the subscription is returned as it is.
var errUnchanged = problem.New(problem.InvalidTransition, "")

func (s *Server) subscriptionResult(ctx context.Context, id uuid.UUID, err error, pushes map[uuid.UUID]*notify.Push) (Response, error) {
	if err != nil && err != errUnchanged {
		return Response{}, err
	}
	if err == nil {
		s.publishSubscription(ctx, id, pushes)
	}
	out, rerr := s.renderSubscription(ctx, id)
	if rerr != nil {
		return Response{}, rerr
	}
	return ok(out), nil
}

func requireFrameSide(v subViewer) error {
	if !v.frameMgr {
		return problem.New(problem.MissingPermission, "requires the %s permission", "change_settings")
	}
	return nil
}

func requireFeedSide(v subViewer) error {
	if !v.feedAdmin() {
		return problem.New(problem.Forbidden, "owner or admin only")
	}
	return nil
}

func (s *Server) ApproveSubscription(ctx context.Context, req gen.ApproveSubscriptionRequestObject) (gen.ApproveSubscriptionResponseObject, error) {
	if handled, sub, err := s.remoteSubscriptionAction(ctx, me(ctx).ID, req.SubscriptionId, "approve"); handled { // a frame on another server
		if err != nil {
			return nil, err
		}
		return ok(sub), nil
	}
	sv, v, err := s.visibleSubscription(ctx, req.SubscriptionId)
	if err != nil {
		return nil, err
	}
	if err := requireFeedSide(v); err != nil {
		return nil, err
	}
	err = s.transition(ctx, sv, func(tx *store.Tx, sub *store.Subscription) error {
		switch sub.Status {
		case "active", "paused":
			return errUnchanged
		case "pending":
			return tx.ActivateSubscription(ctx, sub.ID, &me(ctx).ID)
		}
		return problem.New(problem.AlreadyDecided, "")
	})
	return s.subscriptionResult(ctx, sv.ID, err, s.decisionPush(sv, true))
}

func (s *Server) RejectSubscription(ctx context.Context, req gen.RejectSubscriptionRequestObject) (gen.RejectSubscriptionResponseObject, error) {
	if handled, sub, err := s.remoteSubscriptionAction(ctx, me(ctx).ID, req.SubscriptionId, "reject"); handled { // a frame on another server
		if err != nil {
			return nil, err
		}
		return ok(sub), nil
	}
	sv, v, err := s.visibleSubscription(ctx, req.SubscriptionId)
	if err != nil {
		return nil, err
	}
	if err := requireFeedSide(v); err != nil {
		return nil, err
	}
	err = s.transition(ctx, sv, func(tx *store.Tx, sub *store.Subscription) error {
		switch sub.Status {
		case "rejected":
			return errUnchanged
		case "pending":
			return tx.EndSubscription(ctx, sub, "rejected", "", &me(ctx).ID)
		}
		return problem.New(problem.AlreadyDecided, "")
	})
	return s.subscriptionResult(ctx, sv.ID, err, s.decisionPush(sv, false))
}

func (s *Server) PauseSubscription(ctx context.Context, req gen.PauseSubscriptionRequestObject) (gen.PauseSubscriptionResponseObject, error) {
	sv, v, err := s.visibleSubscription(ctx, req.SubscriptionId)
	if err != nil {
		return nil, err
	}
	if err := requireFrameSide(v); err != nil {
		return nil, err
	}
	err = s.transition(ctx, sv, func(tx *store.Tx, sub *store.Subscription) error {
		switch sub.Status {
		case "paused":
			return errUnchanged
		case "active":
			return tx.PauseSubscription(ctx, sub)
		}
		return problem.New(problem.InvalidTransition, "")
	})
	return s.subscriptionResult(ctx, sv.ID, err, nil)
}

func (s *Server) ResumeSubscription(ctx context.Context, req gen.ResumeSubscriptionRequestObject) (gen.ResumeSubscriptionResponseObject, error) {
	sv, v, err := s.visibleSubscription(ctx, req.SubscriptionId)
	if err != nil {
		return nil, err
	}
	if err := requireFrameSide(v); err != nil {
		return nil, err
	}
	err = s.transition(ctx, sv, func(tx *store.Tx, sub *store.Subscription) error {
		switch sub.Status {
		case "active":
			return errUnchanged
		case "paused":
			return tx.ResumeSubscription(ctx, sub)
		}
		return problem.New(problem.InvalidTransition, "")
	})
	return s.subscriptionResult(ctx, sv.ID, err, nil)
}

func (s *Server) UpdateSubscription(ctx context.Context, req gen.UpdateSubscriptionRequestObject) (gen.UpdateSubscriptionResponseObject, error) {
	sv, v, err := s.visibleSubscription(ctx, req.SubscriptionId)
	if err != nil {
		return nil, err
	}
	if err := requireFrameSide(v); err != nil {
		return nil, err
	}
	// Omitting history keeps it (and starts no backfill or trim).
	var history string
	var count *int
	if req.Body.History != nil {
		if history, count, err = subscriptionHistory(req.Body.History, req.Body.HistoryCount); err != nil {
			return nil, err
		}
	}
	birthdays := req.Body.Birthdays
	err = s.transition(ctx, sv, func(tx *store.Tx, sub *store.Subscription) error {
		if !sub.Live() {
			return problem.New(problem.InvalidTransition, "")
		}
		changed := false
		if history != "" && (sub.History != history || !eqIntPtr(sub.HistoryCount, count)) {
			if err := tx.SetSubscriptionHistory(ctx, sub, history, count); err != nil {
				return err
			}
			changed = true
		}
		if birthdays != nil && *birthdays != sub.Birthdays {
			if err := tx.SetSubscriptionBirthdays(ctx, sub, *birthdays); err != nil {
				return err
			}
			changed = true
		}
		if !changed {
			return errUnchanged
		}
		return nil
	})
	return s.subscriptionResult(ctx, sv.ID, err, nil)
}

func eqIntPtr(a, b *int) bool {
	if a == nil || b == nil {
		return a == b
	}
	return *a == *b
}

func (s *Server) RemoveSubscription(ctx context.Context, req gen.RemoveSubscriptionRequestObject) (gen.RemoveSubscriptionResponseObject, error) {
	if handled, _, err := s.remoteSubscriptionAction(ctx, me(ctx).ID, req.SubscriptionId, "delete"); handled { // a frame on another server
		if err != nil {
			return nil, err
		}
		return noContent(), nil
	}
	sv, v, err := s.visibleSubscription(ctx, req.SubscriptionId)
	if err != nil {
		return nil, err
	}
	requester := sv.RequestedBy != nil && *sv.RequestedBy == me(ctx).ID
	var reason string
	switch {
	case v.frameMgr && sv.Status == "pending", requester && sv.Status == "pending":
		reason = "withdrawn"
	case v.frameMgr:
		reason = "unsubscribed"
	case v.feedAdmin():
		reason = "removed_by_feed"
	default:
		return nil, problem.New(problem.MissingPermission, "requires the %s permission", "change_settings")
	}
	var wasShown bool
	err = s.transition(ctx, sv, func(tx *store.Tx, sub *store.Subscription) error {
		if !sub.Live() {
			return errUnchanged
		}
		wasShown = sub.Status == "active" || sub.Status == "paused"
		return tx.EndSubscription(ctx, sub, "removed", reason, &me(ctx).ID)
	})
	if err == errUnchanged {
		return noContent(), nil
	}
	if err != nil {
		return nil, err
	}
	var pushes map[uuid.UUID]*notify.Push
	if reason == "removed_by_feed" && wasShown {
		// The frame's owner and admins hear that a feed admin took the feed off their frame.
		pushes = map[uuid.UUID]*notify.Push{}
		owners, _ := s.st.MemberIDs(ctx, sv.FrameID, true)
		for _, id := range owners {
			pushes[id] = &notify.Push{Key: "subscription_removed", Args: map[string]string{"feed": sv.FeedName, "frame": sv.FrameName},
				IDs: map[string]string{"feed_id": sv.FeedID.String(), "frame_id": sv.FrameID.String(), "subscription_id": sv.ID.String()}}
		}
	}
	s.publishSubscription(ctx, sv.ID, pushes)
	return noContent(), nil
}

// decisionPush tells the requester that a feed admin approved or rejected their request.
func (s *Server) decisionPush(sv *store.SubscriptionView, approved bool) map[uuid.UUID]*notify.Push {
	if sv.RequestedBy == nil {
		return nil
	}
	text := "subscription_rejected"
	if approved {
		text = "subscription_approved"
	}
	return map[uuid.UUID]*notify.Push{*sv.RequestedBy: {Key: "subscription_decided", Text: text,
		Args: map[string]string{"feed": sv.FeedName, "frame": sv.FrameName},
		IDs:  map[string]string{"feed_id": sv.FeedID.String(), "frame_id": sv.FrameID.String(), "subscription_id": sv.ID.String()}}}
}
