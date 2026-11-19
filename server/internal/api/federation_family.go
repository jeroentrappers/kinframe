package api

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// A's side of families on different servers (README §5.22): frames on other servers subscribe to a family here,
// pull its changes and send their reactions, replies and "seen" back. Only whole-family posts ever leave.

// changeRetention: an older cursor gets `reset` (federation_changes are kept 30 days).
const changeRetention = 30 * 24 * time.Hour

// ---------------------------------------------------------------- subscribe

// federationSubscribe is POST /federation/v1/subscriptions (operationId federationSubscribe).
func (s *Server) federationSubscribe(w http.ResponseWriter, r *http.Request) {
	caller, body := s.verifiedServer(w, r)
	if caller == nil {
		return
	}
	var req gen.FederationSubscribeRequest
	if err := json.Unmarshal(body, &req); err != nil || req.InviteToken == "" {
		problem.Write(w, r, problem.New(problem.ValidationFailed, "malformed request body").WithStatus(http.StatusBadRequest))
		return
	}
	out, status, err := s.remoteSubscribe(r.Context(), caller, &req)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	writeFederationJSON(w, status, out)
}

func (s *Server) remoteSubscribe(ctx context.Context, caller *store.RemoteServer, req *gen.FederationSubscribeRequest) (*gen.FederationSubscription, int, error) {
	inv, err := s.st.GetFeedInviteByToken(ctx, s.st.Q(), req.InviteToken)
	if err == store.ErrNotFound {
		return nil, 0, problem.New(problem.InviteInvalid, "")
	}
	if err != nil {
		return nil, 0, err
	}
	feed, err := s.st.GetFeed(ctx, s.st.Q(), inv.FeedID)
	if err == store.ErrNotFound || (err == nil && feed.DeletedAt != nil) {
		return nil, 0, problem.New(problem.InviteInvalid, "")
	}
	if err != nil {
		return nil, 0, err
	}
	if !inv.Usable(s.now()) {
		return nil, 0, problem.New(problem.InviteExpired, "")
	}
	if inv.Purpose != "subscribe" || !inv.AllowRemote {
		return nil, 0, problem.New(problem.Forbidden, "this invite is not for frames on other servers")
	}
	if !feed.AllowRemoteFrames {
		return nil, 0, problem.New(problem.Forbidden, "the family does not allow frames on other servers")
	}
	if err := s.federationRefusal(ctx, caller.Host, &feed.ID); err != nil {
		return nil, 0, err
	}
	local, err := s.st.CountLiveSubscriptions(ctx, s.st.Q(), feed.ID)
	if err != nil {
		return nil, 0, err
	}
	remote, err := s.st.CountLiveRemoteSubscribers(ctx, s.st.Q(), feed.ID)
	if err != nil {
		return nil, 0, err
	}
	// Approval: a family that auto-approves subscriptions takes remote frames at once; otherwise its admins see
	// which server would receive copies before anything is shared (multi-server.md §3.4).
	requiresApproval := !feed.AutoApproveSubscriptions
	out := &gen.FederationSubscription{RequiresApproval: ptr(requiresApproval), ServerName: ptr(s.cfg.FederationLabel())}
	out.Feed.Name, out.Feed.FrameCount = feed.Name, ptr(local+remote)
	if feed.CoverItemID != nil {
		var key *string
		_ = s.st.Q().QueryRow(ctx, `SELECT thumb512_key FROM items WHERE id = $1`, *feed.CoverItemID).Scan(&key)
		out.Feed.CoverUrl = s.feedCoverURL(key)
	}
	if req.Preview != nil && *req.Preview {
		out.Status = "preview"
		return out, http.StatusOK, nil
	}
	name := strings.TrimSpace(req.Frame.Name)
	if name == "" || utf8.RuneCountInString(name) > 80 || req.Frame.Id == uuid.Nil {
		return nil, 0, problem.Validation("frame", "invalid", "the frame needs an id and a name")
	}
	history, count, err := subscriptionHistory(req.History, req.HistoryCount)
	if err != nil {
		return nil, 0, err
	}
	var requester *string
	if req.RequestedByName != nil {
		if n := strings.TrimSpace(*req.RequestedByName); n != "" && utf8.RuneCountInString(n) <= 80 {
			requester = &n
		}
	}
	limit := s.Limits(ctx).MaxSubscriptionsPerFeed
	status := "active"
	if requiresApproval {
		status = "pending"
	}
	var sub *store.RemoteSubscriber
	existing := false
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		existing = false
		if err := tx.LockFeedInvite(ctx, inv.ID); err != nil {
			return err
		}
		cur, err := s.st.GetFeedInviteByToken(ctx, tx, req.InviteToken)
		if err != nil {
			return err
		}
		if !cur.Usable(s.now()) {
			return problem.New(problem.InviteExpired, "")
		}
		if sub, err = s.st.LiveRemoteSubscriber(ctx, tx, feed.ID, caller.ID, req.Frame.Id); err == nil {
			existing = true
			return nil
		} else if err != store.ErrNotFound {
			return err
		}
		n, err := s.st.CountLiveSubscriptions(ctx, tx, feed.ID)
		if err != nil {
			return err
		}
		m, err := s.st.CountLiveRemoteSubscribers(ctx, tx, feed.ID)
		if err != nil {
			return err
		}
		if n+m >= limit {
			return problem.New(problem.LimitReached, "a feed can be shown on at most %d frames", limit)
		}
		row := store.RemoteSubscriber{ID: store.NewID(), FeedID: feed.ID, ServerID: caller.ID, RemoteFrameID: req.Frame.Id, FollowID: req.FollowId,
			FrameName: name, RequestedByName: requester, Status: status, History: history, HistoryCount: count,
			Birthdays: req.Birthdays == nil || *req.Birthdays, ShowComments: req.Frame.ShowComments != nil && *req.Frame.ShowComments,
			ViaInviteID: &inv.ID}
		if status == "active" {
			row.DecidedBy = inv.CreatedBy
		}
		if err := tx.CreateRemoteSubscriber(ctx, row); err != nil {
			return err
		}
		if err := tx.UseFeedInvite(ctx, inv.ID); err != nil {
			return err
		}
		sub, err = tx.LockRemoteSubscriber(ctx, row.ID)
		return err
	})
	if db.IsUniqueViolation(err, "remote_subscribers_live_once") {
		sub, err = s.st.LiveRemoteSubscriber(ctx, s.st.Q(), feed.ID, caller.ID, req.Frame.Id)
		existing = err == nil
	}
	if err != nil {
		return nil, 0, err
	}
	out.Status = gen.FederationSubscriptionStatus(sub.Status)
	out.SubscriptionId = &sub.ID
	if existing {
		return out, http.StatusOK, nil
	}
	if sub.Status == "pending" {
		s.announceRemoteRequest(ctx, sub)
	} else {
		s.publishRemoteSubscriber(ctx, sub.ID, nil)
	}
	return out, http.StatusCreated, nil
}

// ---------------------------------------------------------------- A's admins

// remoteSubscriptionOut renders a remote subscriber as a Subscription (with `remote`) for a viewer of the family.
func (s *Server) remoteSubscriptionOut(x *store.RemoteSubscriber, feedAdmin bool) gen.Subscription {
	out := gen.Subscription{Id: x.ID, Status: gen.SubscriptionStatus(x.Status), History: gen.SubscriptionHistory(x.History),
		HistoryCount: x.HistoryCount, CreatedAt: x.CreatedAt, ActivatedAt: x.ActivatedAt, EndedAt: x.EndedAt,
		Birthdays: ptr(x.Birthdays), ViaInvite: ptr(x.ViaInviteID != nil), CanManage: ptr(false), CanDecide: ptr(feedAdmin && x.Live())}
	if x.RemovedReason != nil {
		out.RemovedReason = ptr(gen.SubscriptionRemovedReason(*x.RemovedReason))
	}
	out.Feed.Id, out.Feed.Name = x.FeedID, x.FeedName
	out.Frame.Id, out.Frame.Name = x.RemoteFrameID, x.FrameName
	out.Remote = &gen.SubscriptionRemote{ServerId: x.RemoteServerKey, ServerName: x.ServerLabel(), ServerUrl: x.ServerURL, Host: ptr(x.ServerHost),
		ShowComments: ptr(x.ShowComments)}
	if feedAdmin {
		out.RequestedByName = x.RequestedByName
		out.Remote.RequestedByName = x.RequestedByName
	}
	return out
}

// publishRemoteSubscriber sends subscription.updated for a remote subscriber to the family's owner and admins (and
// the pushes given).
func (s *Server) publishRemoteSubscriber(ctx context.Context, id uuid.UUID, pushes map[uuid.UUID]*notify.Push) {
	x, err := s.st.RemoteSubscriberByID(ctx, s.st.Q(), id)
	if err != nil {
		return
	}
	admins, err := s.st.FeedAdminIDs(ctx, s.st.Q(), x.FeedID)
	if err != nil {
		return
	}
	ev := notify.Event{"type": "subscription.updated", "feed_id": x.FeedID, "frame_id": x.RemoteFrameID, "subscription": s.remoteSubscriptionOut(x, true)}
	for _, uid := range admins {
		s.notify.User(uid, ev, pushes[uid])
	}
}

// announceRemoteRequest tells the family's owner and admins that a frame on another server asks to follow:
// "Tom wants to show Janssens on Oma's frame (kinframe.appmire.be)".
func (s *Server) announceRemoteRequest(ctx context.Context, x *store.RemoteSubscriber) {
	admins, err := s.st.FeedAdminIDs(ctx, s.st.Q(), x.FeedID)
	if err != nil {
		return
	}
	name := deref(x.RequestedByName)
	frame := remoteLabel(x.FrameName, x.ServerLabel())
	for _, uid := range admins {
		s.notify.User(uid, notify.Event{"type": "subscription.request", "feed_id": x.FeedID, "subscription": s.remoteSubscriptionOut(x, true)},
			&notify.Push{Key: "subscription_request", Args: map[string]string{"name": name, "feed": x.FeedName, "frame": frame},
				IDs: map[string]string{"feed_id": x.FeedID.String(), "subscription_id": x.ID.String()}})
	}
}

// remoteAdminAction is remoteSubscriptionAction's body (federation_shared.go).
func (s *Server) remoteAdminAction(ctx context.Context, callerID, id uuid.UUID, action string) (bool, *gen.Subscription, error) {
	x, err := s.st.RemoteSubscriberByID(ctx, s.st.Q(), id)
	if err == store.ErrNotFound {
		return false, nil, nil
	}
	if err != nil {
		return true, nil, err
	}
	v, err := s.st.GetFeedView(ctx, s.st.Q(), callerID, x.FeedID)
	if err == store.ErrNotFound || (err == nil && v.Role == "") {
		return true, nil, problem.New(problem.NotFound, "subscription not found")
	}
	if err != nil {
		return true, nil, err
	}
	if action == "get" {
		if !v.IsAdmin() && x.Status != "active" {
			return true, nil, problem.New(problem.NotFound, "subscription not found")
		}
		out := s.remoteSubscriptionOut(x, v.IsAdmin())
		return true, &out, nil
	}
	if !v.IsAdmin() {
		return true, nil, problem.New(problem.Forbidden, "owner or admin only")
	}
	changed := false
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		changed = false
		cur, err := tx.LockRemoteSubscriber(ctx, id)
		if err != nil {
			return err
		}
		switch action {
		case "approve":
			switch cur.Status {
			case "active":
				return nil
			case "pending":
				changed = true
				return tx.ActivateRemoteSubscriber(ctx, cur, &callerID)
			}
			return problem.New(problem.AlreadyDecided, "")
		case "reject":
			switch cur.Status {
			case "rejected":
				return nil
			case "pending":
				changed = true
				return tx.EndRemoteSubscriber(ctx, cur, "rejected", "rejected", &callerID, true)
			}
			return problem.New(problem.AlreadyDecided, "")
		case "delete":
			if !cur.Live() {
				return nil
			}
			changed = true
			return tx.EndRemoteSubscriber(ctx, cur, "removed", "removed_by_feed", &callerID, true)
		}
		return problem.New(problem.InvalidTransition, "")
	})
	if err != nil {
		return true, nil, err
	}
	if changed {
		s.publishRemoteSubscriber(ctx, id, nil)
		s.wakeFederation()
	}
	if x, err = s.st.RemoteSubscriberByID(ctx, s.st.Q(), id); err != nil {
		return true, nil, err
	}
	out := s.remoteSubscriptionOut(x, true)
	return true, &out, nil
}

// endRemoteSubscribersWhere ends live remote subscribers (in the caller's transaction) and has the loop tell B.
func (s *Server) endRemoteSubscribersWhere(ctx context.Context, tx *store.Tx, reason string, where string, args ...any) ([]uuid.UUID, error) {
	subs, err := s.st.LiveRemoteSubscribersWhere(ctx, tx, where, args...)
	if err != nil {
		return nil, err
	}
	var ids []uuid.UUID
	for i := range subs {
		status := "removed"
		if subs[i].Status == "pending" && reason == "rejected" {
			status = "rejected"
		}
		if err := tx.EndRemoteSubscriber(ctx, &subs[i], status, reason, nil, true); err != nil {
			return nil, err
		}
		ids = append(ids, subs[i].ID)
	}
	return ids, nil
}

// ---------------------------------------------------------------- the change feed

// fedCursor is the opaque cursor of federationChanges: the backfill position (history start, last post) or the
// live position (change seq), and when it was issued (older than the log's retention: reset).
type fedCursor struct {
	Live    bool
	Issued  time.Time
	Base    int64
	From    time.Time
	AfterAt time.Time
	AfterID uuid.UUID
}

func (c fedCursor) encode() string {
	b := make([]byte, 0, 50)
	b = append(b, 1)
	if c.Live {
		b = append(b, 'l')
	} else {
		b = append(b, 'b')
	}
	b = binary.BigEndian.AppendUint64(b, uint64(c.Issued.Unix()))
	b = binary.BigEndian.AppendUint64(b, uint64(c.Base))
	b = binary.BigEndian.AppendUint64(b, uint64(c.From.UnixMicro()))
	b = binary.BigEndian.AppendUint64(b, uint64(c.AfterAt.UnixMicro()))
	b = append(b, c.AfterID[:]...)
	return base64.RawURLEncoding.EncodeToString(b)
}

func decodeFedCursor(s string) (fedCursor, bool) {
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil || len(b) != 50 || b[0] != 1 || (b[1] != 'l' && b[1] != 'b') {
		return fedCursor{}, false
	}
	c := fedCursor{Live: b[1] == 'l'}
	c.Issued = time.Unix(int64(binary.BigEndian.Uint64(b[2:10])), 0)
	c.Base = int64(binary.BigEndian.Uint64(b[10:18]))
	c.From = time.UnixMicro(int64(binary.BigEndian.Uint64(b[18:26]))).UTC()
	c.AfterAt = time.UnixMicro(int64(binary.BigEndian.Uint64(b[26:34]))).UTC()
	copy(c.AfterID[:], b[34:50])
	return c, true
}

// subscriberOfCaller loads the subscription in the path, which must belong to the calling server (else 404).
func (s *Server) subscriberOfCaller(w http.ResponseWriter, r *http.Request, caller *store.RemoteServer) *store.RemoteSubscriber {
	id, err := uuid.Parse(r.PathValue("subscriptionId"))
	if err == nil {
		if x, err := s.st.RemoteSubscriberByID(r.Context(), s.st.Q(), id); err == nil && x.ServerID == caller.ID {
			return x
		}
	}
	problem.Write(w, r, problem.New(problem.NotFound, "subscription not found"))
	return nil
}

// federationChanges is GET /federation/v1/subscriptions/{subscriptionId}/changes (federationChanges).
func (s *Server) federationChanges(w http.ResponseWriter, r *http.Request) {
	caller, _ := s.verifiedServer(w, r)
	if caller == nil {
		return
	}
	x := s.subscriberOfCaller(w, r, caller)
	if x == nil {
		return
	}
	limit := 100
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 1 && n <= 200 {
			limit = n
		}
	}
	page, err := s.changePage(r.Context(), x, r.URL.Query().Get("cursor"), limit)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	_, _ = s.st.Pool.Exec(r.Context(), `UPDATE remote_subscribers SET last_pull_at = now() WHERE id = $1`, x.ID)
	writeFederationJSON(w, http.StatusOK, page)
}

func (s *Server) changePage(ctx context.Context, x *store.RemoteSubscriber, raw string, limit int) (*gen.FederationChangePage, error) {
	now := s.now()
	page := &gen.FederationChangePage{Changes: []gen.FederationChange{}, Status: gen.FederationChangePageStatus(x.Status), FeedName: ptr(x.FeedName)}
	switch x.Status {
	case "pending":
		page.Cursor = raw
		return page, nil
	case "rejected", "removed":
		ch := gen.FederationChange{Type: "subscription", Status: ptr(gen.FederationChangeStatus(x.Status))}
		if x.RemovedReason != nil {
			ch.Reason = ptr(gen.RemoteEndReason(*x.RemovedReason))
		}
		page.Changes, page.Cursor = append(page.Changes, ch), raw
		return page, nil
	}
	var cur fedCursor
	start := raw == ""
	if !start {
		var ok bool
		if cur, ok = decodeFedCursor(raw); !ok || now.Sub(cur.Issued) > changeRetention {
			// Too old (or not ours): restart from a full snapshot; B drops what the snapshot doesn't bring.
			page.Reset, page.More, page.Cursor = ptr(true), true, ""
			return page, nil
		}
	}
	if start {
		head, err := s.st.FederationHeadSeq(ctx, x.FeedID)
		if err != nil {
			return nil, err
		}
		boundary := now
		if x.ActivatedAt != nil {
			boundary = *x.ActivatedAt
		}
		sub := store.Subscription{History: x.History, HistoryCount: x.HistoryCount}
		from, err := s.st.FederationHistoryStart(ctx, x.FeedID, boundary, store.HistoryLimit(&sub, s.Limits(ctx).MaxFeedBackfillItems))
		if err != nil {
			return nil, err
		}
		cur = fedCursor{Base: head, From: from, AfterAt: time.Unix(0, 0).UTC()}
		page.Changes = append(page.Changes, gen.FederationChange{Type: "subscription", Status: ptr(gen.FederationChangeStatus("active"))})
		if x.Birthdays {
			b, err := s.federationBirthdays(ctx, x.FeedID)
			if err != nil {
				return nil, err
			}
			page.Changes = append(page.Changes, gen.FederationChange{Type: "birthdays", Birthdays: &b})
		}
	}
	cur.Issued = now
	if !cur.Live {
		ids, err := s.st.FederationBackfillPosts(ctx, x.FeedID, cur.From, cur.AfterAt, cur.AfterID, limit)
		if err != nil {
			return nil, err
		}
		posts, err := s.federationPosts(ctx, x, ids)
		if err != nil {
			return nil, err
		}
		for _, id := range ids {
			p := posts[id]
			if p == nil {
				continue
			}
			p.Backfill = ptr(x.ActivatedAt != nil && p.CreatedAt.Before(*x.ActivatedAt))
			page.Changes = append(page.Changes, gen.FederationChange{Type: "post.upsert", Post: p})
			if x.ShowComments {
				if cs := s.federationComments(ctx, id); len(cs) > 0 {
					page.Changes = append(page.Changes, gen.FederationChange{Type: "comments", PostId: ptr(id), Comments: &cs})
				}
			}
			cur.AfterAt, cur.AfterID = p.CreatedAt, id
		}
		if len(ids) < limit {
			cur.Live = true // the backfill is done: live changes from the head at the start
		}
		// More until the first live page: B knows its snapshot is complete when `more` is false.
		page.More, page.Cursor = true, cur.encode()
		return page, nil
	}
	rows, err := s.st.FederationChangesAfter(ctx, x.FeedID, x.ID, cur.Base, limit)
	if err != nil {
		return nil, err
	}
	var upserts []uuid.UUID
	for _, c := range rows {
		if c.Kind == "post.upsert" && c.PostID != nil {
			upserts = append(upserts, *c.PostID)
		}
	}
	posts, err := s.federationPosts(ctx, x, upserts)
	if err != nil {
		return nil, err
	}
	var birthdays *[]gen.FederationBirthday
	for _, c := range rows {
		cur.Base = c.Seq
		switch c.Kind {
		case "post.upsert":
			if p := posts[*c.PostID]; p != nil {
				p.Backfill = ptr(x.ActivatedAt != nil && p.CreatedAt.Before(*x.ActivatedAt))
				page.Changes = append(page.Changes, gen.FederationChange{Type: "post.upsert", Post: p})
			} else {
				page.Changes = append(page.Changes, gen.FederationChange{Type: "post.remove", PostId: c.PostID})
			}
		case "post.remove":
			page.Changes = append(page.Changes, gen.FederationChange{Type: "post.remove", PostId: c.PostID})
		case "items.remove":
			ids := c.ItemIDs
			page.Changes = append(page.Changes, gen.FederationChange{Type: "items.remove", PostId: c.PostID, ItemIds: &ids})
		case "birthdays":
			if !x.Birthdays {
				continue
			}
			if birthdays == nil {
				b, err := s.federationBirthdays(ctx, x.FeedID)
				if err != nil {
					return nil, err
				}
				birthdays = &b
			}
			page.Changes = append(page.Changes, gen.FederationChange{Type: "birthdays", Birthdays: birthdays})
		case "comments":
			if !x.ShowComments || c.PostID == nil {
				continue
			}
			cs := s.federationComments(ctx, *c.PostID)
			page.Changes = append(page.Changes, gen.FederationChange{Type: "comments", PostId: c.PostID, Comments: &cs})
		case "subscription":
			page.Changes = append(page.Changes, gen.FederationChange{Type: "subscription", Status: ptr(gen.FederationChangeStatus(x.Status))})
		}
	}
	page.More, page.Cursor = len(rows) == limit, cur.encode()
	return page, nil
}

// federationPosts renders shareable posts (gone, circle and other families' posts are left out: post.remove).
func (s *Server) federationPosts(ctx context.Context, x *store.RemoteSubscriber, ids []uuid.UUID) (map[uuid.UUID]*gen.FederationPost, error) {
	out := map[uuid.UUID]*gen.FederationPost{}
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := s.st.FederationPosts(ctx, ids)
	if err != nil {
		return nil, err
	}
	var keep []uuid.UUID
	for _, p := range rows {
		if p.FeedID != x.FeedID || p.Removed || p.InCircle || p.FeedDeleted {
			continue
		}
		key := ""
		if p.PosterID != nil {
			key = s.posterKey(x.ID, *p.PosterID)
		}
		out[p.ID] = &gen.FederationPost{Id: p.ID, PosterKey: key, PosterName: deref(p.PosterName), CreatedAt: p.CreatedAt,
			Items: []gen.FederationItem{}}
		keep = append(keep, p.ID)
	}
	items, err := s.st.FederationPostItems(ctx, keep)
	if err != nil {
		return nil, err
	}
	expires := s.now().Add(time.Hour)
	for _, it := range items {
		p := out[it.PostID]
		if p == nil || it.Status != "ready" || it.MasterKey == nil || it.MasterBytes == nil || it.MasterMime == nil {
			continue // not ready yet: the post is logged again when it is (00046)
		}
		fi := gen.FederationItem{Id: it.ItemID, Kind: gen.MediaKind(it.Kind), Caption: it.Caption, CapturedAt: it.CapturedAt,
			Width: it.Width, Height: it.Height, DurationMs: it.DurationMS, HasAudio: it.HasAudio, Bytes: int(*it.MasterBytes),
			Sha256: it.MasterSHA256, Mime: *it.MasterMime, MasterUrl: s.signedURL(*it.MasterKey, time.Hour), MasterUrlExpiresAt: expires}
		if it.FocalX != nil && it.FocalY != nil {
			fi.FocalPoint = &gen.FocalPoint{X: float32(*it.FocalX), Y: float32(*it.FocalY)}
		}
		if it.Fit != nil {
			fi.Fit = ptr(gen.Fit(*it.Fit))
		}
		if it.MotionKey != nil && it.MotionStatus != nil && *it.MotionStatus == "ready" {
			fi.Kind = gen.MediaKindLive
			fi.MotionUrl = ptr(s.signedURL(*it.MotionKey, time.Hour))
		}
		p.Items = append(p.Items, fi)
	}
	return out, nil
}

// posterKey is the opaque, stable key of a poster within one remote subscription (never the user id).
func (s *Server) posterKey(subscriberID, userID uuid.UUID) string {
	m := hmac.New(sha256.New, auth.DeriveKey(s.cfg.SigningKey, "federation-poster"))
	m.Write(subscriberID[:])
	m.Write(userID[:])
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil)[:16])
}

func (s *Server) federationBirthdays(ctx context.Context, feedID uuid.UUID) ([]gen.FederationBirthday, error) {
	rows, err := s.st.FederationBirthdays(ctx, feedID)
	if err != nil {
		return nil, err
	}
	out := make([]gen.FederationBirthday, len(rows))
	for i, b := range rows {
		out[i] = gen.FederationBirthday{Id: b.ID, Name: b.Name, Month: b.Month, Day: b.Day, Year: b.Year}
	}
	return out, nil
}

// federationComments is a post's comment card for remote frames: the three newest, first names, 200 characters.
func (s *Server) federationComments(ctx context.Context, postID uuid.UUID) []gen.FederationComment {
	cs, err := s.st.NewestComments(ctx, postID, 3)
	if err != nil {
		s.log.Warn("federation: comments", "post", postID, "err", err)
		return []gen.FederationComment{}
	}
	out := make([]gen.FederationComment, 0, len(cs))
	for i := range cs {
		c := &cs[i]
		if c.AuthorDeleted {
			continue
		}
		first := strings.Fields(c.AuthorName)
		fc := gen.FederationComment{At: c.CreatedAt}
		if len(first) > 0 {
			fc.FirstName = first[0]
		}
		if body := s.openCommentBody(c); body != nil {
			t := *body
			if utf8.RuneCountInString(t) > 200 {
				t = string([]rune(t)[:199]) + "…"
			}
			fc.Text = &t
		}
		out = append(out, fc)
	}
	return out
}

// ---------------------------------------------------------------- activity back

// federationActivity is POST /federation/v1/subscriptions/{subscriptionId}/activity (federationActivity).
func (s *Server) federationActivity(w http.ResponseWriter, r *http.Request) {
	caller, body := s.verifiedServer(w, r)
	if caller == nil {
		return
	}
	x := s.subscriberOfCaller(w, r, caller)
	if x == nil {
		return
	}
	var req gen.FederationActivity
	if err := json.Unmarshal(body, &req); err != nil || (req.Events != nil && len(*req.Events) > 200) {
		problem.Write(w, r, problem.New(problem.ValidationFailed, "malformed request body").WithStatus(http.StatusBadRequest))
		return
	}
	ctx := r.Context()
	if req.Frame != nil {
		name := x.FrameName
		if req.Frame.Name != nil {
			if n := strings.TrimSpace(*req.Frame.Name); n != "" && utf8.RuneCountInString(n) <= 80 {
				name = n
			}
		}
		show := x.ShowComments
		if req.Frame.ShowComments != nil {
			show = *req.Frame.ShowComments
		}
		if name != x.FrameName || show != x.ShowComments {
			if _, err := s.st.Pool.Exec(ctx, `UPDATE remote_subscribers SET frame_name = $2, show_comments = $3 WHERE id = $1`, x.ID, name, show); err != nil {
				s.writeError(w, r, err)
				return
			}
			x.FrameName, x.ShowComments = name, show
		}
	}
	if x.Status == "active" && req.Events != nil {
		for _, ev := range *req.Events {
			if err := s.remoteActivityEvent(ctx, x, ev.Id, string(ev.Kind), ev.PostId, ev.ItemId, ev.Reaction, ev.Reply, ev.At); err != nil {
				s.writeError(w, r, err)
				return
			}
		}
	}
	w.WriteHeader(http.StatusAccepted)
}

// remoteActivityEvent applies one event once (idempotent by id); events for posts that are gone are ignored.
func (s *Server) remoteActivityEvent(ctx context.Context, x *store.RemoteSubscriber, eventID uuid.UUID, kind string, postID, itemID uuid.UUID,
	reaction *gen.Reaction, reply *gen.ReplyPreset, at time.Time) error {
	if at.After(s.now().Add(5*time.Minute)) || at.IsZero() {
		at = s.now()
	}
	label := remoteLabel(x.FrameName, x.ServerLabel())
	var entry uuid.UUID
	var change string
	applied := false
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		applied, entry, change = false, uuid.Nil, ""
		tag, err := tx.Exec(ctx, `INSERT INTO remote_activity_seen (subscriber_id, event_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`, x.ID, eventID)
		if err != nil || tag.RowsAffected() == 0 {
			return err
		}
		ok, err := s.st.FederationPostOK(ctx, tx, x.FeedID, postID, itemID)
		if err != nil || !ok {
			return err
		}
		applied = true
		switch kind {
		case "seen":
			_, err = tx.Exec(ctx, `INSERT INTO remote_frame_seen (subscriber_id, post_id, item_id, seen_at) VALUES ($1, $2, $3, $4)
				ON CONFLICT (subscriber_id, post_id, item_id) DO NOTHING`, x.ID, postID, itemID, at)
			return err
		case "reaction":
			if reaction == nil {
				_, err = tx.Exec(ctx, `DELETE FROM remote_frame_reactions WHERE subscriber_id = $1 AND post_id = $2 AND item_id = $3`, x.ID, postID, itemID)
			} else {
				_, err = tx.Exec(ctx, `INSERT INTO remote_frame_reactions (subscriber_id, post_id, item_id, reaction, at) VALUES ($1, $2, $3, $4, $5)
					ON CONFLICT (subscriber_id, post_id, item_id) DO UPDATE SET reaction = EXCLUDED.reaction, at = EXCLUDED.at`,
					x.ID, postID, itemID, string(*reaction), at)
			}
			if err != nil {
				return err
			}
			if err := s.ensureThreadOf(ctx, tx, postID); err != nil {
				return err
			}
			latest, latestItem, err := s.st.LatestRemoteReaction(ctx, tx, x.ID, postID)
			if err != nil {
				return err
			}
			entry, change, err = tx.SetRemoteReactionEntry(ctx, store.RemoteEntry{PostID: postID, SubscriberID: x.ID, FrameName: x.FrameName,
				ServerName: x.ServerLabel(), ItemID: latestItem}, latest)
			return err
		case "reply":
			if reply == nil {
				return nil
			}
			if err := s.ensureThreadOf(ctx, tx, postID); err != nil {
				return err
			}
			entry, err = tx.AddRemoteReplyEntry(ctx, store.RemoteEntry{PostID: postID, SubscriberID: x.ID, FrameName: x.FrameName,
				ServerName: x.ServerLabel(), ItemID: itemID}, string(*reply))
			change = "created"
			return err
		}
		return nil
	})
	if err != nil || !applied {
		return err
	}
	switch change {
	case "created":
		s.publishComment(ctx, postID, entry, "comment.created", nil)
	case "updated":
		s.publishComment(ctx, postID, entry, "comment.updated", nil)
	case "deleted":
		s.publishCommentDeleted(ctx, postID, []uuid.UUID{entry}, nil)
	}
	if kind == "seen" {
		s.publishFeedPost(ctx, postID) // receipts
		return nil
	}
	s.publishPostUpdated(ctx, postID) // the reaction row
	s.publishFeedPost(ctx, postID)
	// The poster hears it as from a local frame.
	var sender *uuid.UUID
	_ = s.st.Q().QueryRow(ctx, `SELECT sender_user_id FROM items WHERE id = $1`, itemID).Scan(&sender)
	if sender == nil || !s.reactionsOn(ctx, itemID) {
		return nil
	}
	ids := map[string]string{"feed_id": x.FeedID.String(), "post_id": postID.String(), "item_id": itemID.String()}
	switch {
	case kind == "reaction" && reaction != nil:
		s.notify.User(*sender, notify.Event{"type": "reaction", "item_id": itemID, "feed_id": x.FeedID, "post_id": postID,
			"reaction": string(*reaction), "by_frame_name": label},
			&notify.Push{Key: "reaction", Args: map[string]string{"frame": label}, IDs: ids})
	case kind == "reply" && reply != nil:
		s.notify.User(*sender, notify.Event{"type": "reply", "item_id": itemID, "feed_id": x.FeedID, "post_id": postID, "preset": string(*reply)},
			&notify.Push{Key: "reply", Args: map[string]string{"frame": label, "preset": string(*reply)}, IDs: ids})
	}
	return nil
}

// ensureThreadOf creates the thread row of a family post (threads are created lazily).
func (s *Server) ensureThreadOf(ctx context.Context, tx *store.Tx, postID uuid.UUID) error {
	p, err := s.st.SocialPost(ctx, tx, uuid.Nil, postID)
	if err != nil {
		return err
	}
	if err := tx.EnsureThread(ctx, p); err != nil {
		return err
	}
	_, err = tx.LockThread(ctx, postID)
	return err
}

// ---------------------------------------------------------------- unsubscribe (either side)

// federationUnsubscribe is DELETE /federation/v1/subscriptions/{subscriptionId} (federationUnsubscribe): B ends
// its follow of a family here, or A ends B's follow of a family there.
func (s *Server) federationUnsubscribe(w http.ResponseWriter, r *http.Request) {
	caller, _ := s.verifiedServer(w, r)
	if caller == nil {
		return
	}
	ctx := r.Context()
	id, err := uuid.Parse(r.PathValue("subscriptionId"))
	if err != nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	reason := r.URL.Query().Get("reason")
	switch reason {
	case "", "unsubscribed", "removed_by_feed", "feed_deleted", "server_blocked", "withdrawn", "rejected":
	default:
		reason = ""
	}
	// A family here, a frame there.
	if x, err := s.st.RemoteSubscriberByID(ctx, s.st.Q(), id); err == nil && x.ServerID == caller.ID {
		ended := false
		err := s.st.InTx(ctx, func(tx *store.Tx) error {
			cur, err := tx.LockRemoteSubscriber(ctx, id)
			if err != nil || !cur.Live() {
				return err
			}
			why := "unsubscribed"
			if cur.Status == "pending" {
				why = "withdrawn"
			}
			ended = true
			return tx.EndRemoteSubscriber(ctx, cur, "removed", why, nil, false)
		})
		if err != nil {
			s.writeError(w, r, err)
			return
		}
		if ended {
			s.publishRemoteSubscriber(ctx, id, nil)
		}
		w.WriteHeader(http.StatusNoContent)
		return
	}
	// A frame here, a family there.
	follows, err := s.st.RemoteFollowsWhere(ctx, s.st.Q(), `f.server_id = $1 AND f.remote_subscription_id = $2`, caller.ID, id)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	if reason == "" {
		reason = "removed_by_feed"
	}
	for i := range follows {
		if follows[i].Live() {
			s.endFollow(ctx, follows[i].ID, reason, nil, false)
		}
	}
	w.WriteHeader(http.StatusNoContent)
}
