package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/federation"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/netguard"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// B's side of families on different servers (README §5.22): frames here follow families on other servers. B
// pulls the family's changes (on a ping and every 15 minutes), copies the masters once, makes the renditions as for
// any item and places them on the frame as feed placements of a remote sender; the frame's reactions, replies and
// "seen" go back through the outbox.

// ---------------------------------------------------------------- invite links

// parseRemoteInvite splits a subscribe invite URL (https://host/fi/<token>) into the server's base URL, host and token.
func (s *Server) parseRemoteInvite(raw string) (base, host, token string, err error) {
	u, perr := url.Parse(strings.TrimSpace(raw))
	if perr == nil {
		base, host, perr = federation.NormalizeBaseURL(raw)
	}
	if perr != nil || u == nil {
		return "", "", "", problem.Validation("invite_url", "invite", "not a kinframe family invite link")
	}
	path := strings.Trim(u.EscapedPath(), "/")
	token, ok := strings.CutPrefix(path, "fi/")
	if !ok || len(token) < 22 || len(token) > 64 || strings.ContainsAny(token, "/?#") {
		return "", "", "", problem.Validation("invite_url", "invite", "not a kinframe family invite link")
	}
	if host == s.cfg.FederationHost() {
		return "", "", "", problem.Validation("invite_url", "same_server", "this family is on this server: use the invite here")
	}
	return base, host, token, nil
}

// remoteProblem turns a failed call to another server into this server's answer.
func remoteProblem(err error) error {
	if re, ok := federation.AsRemote(err); ok {
		switch re.Status {
		case http.StatusNotFound:
			if re.Code == string(problem.InviteInvalid) {
				return problem.New(problem.InviteInvalid, "")
			}
			return problem.New(problem.NotFound, "subscription not found")
		case http.StatusGone:
			return problem.New(problem.InviteExpired, "")
		case http.StatusForbidden:
			return problem.New(problem.Forbidden, "the other server refused the request")
		case http.StatusConflict:
			if re.Code == string(problem.LimitReached) {
				return problem.New(problem.LimitReached, "")
			}
			return problem.New(problem.AlreadySubscribed, "")
		}
		return problem.New(problem.UpstreamUnavailable, "the other server refused the request")
	}
	if errors.Is(err, federation.ErrNotKinframe) {
		return problem.Validation("invite_url", "invite", "not a kinframe family invite link")
	}
	if _, ok := netguard.Blocked(err); ok { // a private or local address
		return problem.New(problem.Forbidden, "this server does not federate with that server")
	}
	return problem.New(problem.UpstreamUnavailable, "the other server could not be reached")
}

// subscribeAt asks A about an invite (preview) or to record a subscription.
func (s *Server) subscribeAt(ctx context.Context, base string, req gen.FederationSubscribeRequest) (*store.RemoteServer, *gen.FederationSubscription, error) {
	rs, err := s.remoteServer(ctx, base, false)
	if err != nil {
		return nil, nil, remoteProblem(err)
	}
	if d := remoteDocument(rs); d == nil || !d.Enabled() {
		return nil, nil, problem.New(problem.Forbidden, "the other server refused the request")
	}
	var out gen.FederationSubscription
	if _, err := s.fed.client.Do(ctx, http.MethodPost, base+"/federation/v1/subscriptions", req, &out); err != nil {
		s.st.RemoteServerFailed(ctx, rs.ID, err.Error())
		return nil, nil, remoteProblem(err)
	}
	s.st.TouchRemoteServer(ctx, rs.ID)
	return rs, &out, nil
}

// ---------------------------------------------------------------- operations (tag remote)

func (s *Server) PreviewRemoteInvite(ctx context.Context, request gen.PreviewRemoteInviteRequestObject) (gen.PreviewRemoteInviteResponseObject, error) {
	if !s.federationOn() {
		return nil, problem.New(problem.FeatureDisabled, "federation is turned off on this server")
	}
	base, host, token, err := s.parseRemoteInvite(request.Body.InviteUrl)
	if err != nil {
		return nil, err
	}
	if err := s.federationRefusal(ctx, host, nil); err != nil {
		return nil, err
	}
	req := gen.FederationSubscribeRequest{InviteToken: token, Preview: ptr(true)}
	req.Frame.Name = "preview"
	rs, res, err := s.subscribeAt(ctx, base, req)
	if err != nil {
		return nil, err
	}
	out := gen.RemoteInvitePreview{Server: remoteServerJSON(rs.BaseURL, rs.Host, rs.ServerID, rs.ServerName), FeedName: res.Feed.Name,
		CoverUrl: res.Feed.CoverUrl, FrameCount: res.Feed.FrameCount, RequiresApproval: res.RequiresApproval,
		ThisServerName: s.cfg.FederationLabel(), ThisServerHost: ptr(s.cfg.FederationHost())}
	return ok(out), nil
}

// remoteFrameManager checks the caller manages the frame (owner, admin or change_settings).
func (s *Server) remoteFrameManager(ctx context.Context, frameID uuid.UUID) (*store.Frame, bool, error) {
	m, err := s.membership(ctx, frameID)
	if err != nil {
		return nil, false, err
	}
	f, err := s.st.GetFrame(ctx, s.st.Q(), frameID)
	if err != nil {
		return nil, false, notFound(err, "frame")
	}
	return f, m.Effective().ChangeSettings, nil
}

func (s *Server) CreateRemoteSubscription(ctx context.Context, request gen.CreateRemoteSubscriptionRequestObject) (gen.CreateRemoteSubscriptionResponseObject, error) {
	if !s.federationOn() {
		return nil, problem.New(problem.FeatureDisabled, "federation is turned off on this server")
	}
	f, manager, err := s.remoteFrameManager(ctx, request.FrameId)
	if err != nil {
		return nil, err
	}
	if !manager {
		return nil, problem.New(problem.MissingPermission, "requires the %s permission", "change_settings")
	}
	b := request.Body
	base, host, token, err := s.parseRemoteInvite(b.InviteUrl)
	if err != nil {
		return nil, err
	}
	if err := s.federationRefusal(ctx, host, nil); err != nil {
		return nil, err
	}
	history, count, err := subscriptionHistory(b.History, b.HistoryCount)
	if err != nil {
		return nil, err
	}
	onEnd := "remove"
	if b.OnEnd != nil {
		onEnd = string(*b.OnEnd)
	}
	birthdays := b.Birthdays == nil || *b.Birthdays
	u := me(ctx)
	followID := store.NewID()
	req := gen.FederationSubscribeRequest{InviteToken: token, FollowId: &followID, Birthdays: ptr(birthdays), RequestedByName: ptr(u.DisplayName)}
	req.Frame.Id, req.Frame.Name, req.Frame.ShowComments = f.ID, f.Name, ptr(frameSocialSetting(f, "show_comments", false))
	h := gen.SubscriptionHistory(history)
	req.History, req.HistoryCount = &h, count
	rs, res, err := s.subscribeAt(ctx, base, req)
	if err != nil {
		return nil, err
	}
	if res.SubscriptionId == nil {
		return nil, problem.New(problem.UpstreamUnavailable, "the other server refused the request")
	}
	status := string(res.Status)
	if status != "active" && status != "pending" {
		return nil, problem.New(problem.Forbidden, "the other server refused the request")
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFrame(ctx, f.ID); err != nil {
			return notFound(err, "frame")
		}
		live, err := s.st.RemoteFollowsWhere(ctx, tx, `f.server_id = $1 AND f.remote_subscription_id = $2 AND f.status IN ('pending', 'active', 'error')`,
			rs.ID, *res.SubscriptionId)
		if err != nil {
			return err
		}
		if len(live) > 0 {
			return problem.New(problem.AlreadySubscribed, "")
		}
		// An ended follow of the same subscription id (A answered with a live one again) gives way.
		if _, err := tx.Exec(ctx, `UPDATE remote_follows SET remote_subscription_id = NULL WHERE server_id = $1 AND remote_subscription_id = $2`,
			rs.ID, *res.SubscriptionId); err != nil {
			return err
		}
		_, err = tx.Exec(ctx, `INSERT INTO remote_follows (id, frame_id, server_id, remote_subscription_id, invite_url, feed_name, cover_url, status,
			history, history_count, birthdays, on_end, requested_by, activated_at, next_pull_at)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, CASE WHEN $8 = 'active' THEN now() END, now())`,
			followID, f.ID, rs.ID, *res.SubscriptionId, b.InviteUrl, res.Feed.Name, res.Feed.CoverUrl, status, history, count, birthdays, onEnd, u.ID)
		return err
	})
	if db.IsUniqueViolation(err, "remote_follows_remote_once") {
		return nil, problem.New(problem.AlreadySubscribed, "")
	}
	if err != nil {
		return nil, err
	}
	s.kickPull(followID)
	s.publishRemoteFollow(ctx, followID)
	fl, err := s.st.RemoteFollowByID(ctx, s.st.Q(), followID)
	if err != nil {
		return nil, err
	}
	return created(s.remoteFollowJSON(fl)), nil
}

func (s *Server) ListRemoteSubscriptions(ctx context.Context, request gen.ListRemoteSubscriptionsRequestObject) (gen.ListRemoteSubscriptionsResponseObject, error) {
	_, manager, err := s.remoteFrameManager(ctx, request.FrameId)
	if err != nil {
		return nil, err
	}
	where := `f.frame_id = $1 AND (f.ended_at IS NULL OR f.ended_at > now() - interval '30 days')`
	if !manager {
		where += ` AND f.status = 'active'`
	}
	rows, err := s.st.RemoteFollowsWhere(ctx, s.st.Q(), where, request.FrameId)
	if err != nil {
		return nil, err
	}
	out := make([]gen.RemoteSubscription, len(rows))
	for i := range rows {
		out[i] = s.remoteFollowJSON(&rows[i])
	}
	return ok(out), nil
}

// frameFollow loads a follow of the frame for a frame manager.
func (s *Server) frameFollow(ctx context.Context, frameID, id uuid.UUID) (*store.RemoteFollow, error) {
	_, manager, err := s.remoteFrameManager(ctx, frameID)
	if err != nil {
		return nil, err
	}
	fl, err := s.st.RemoteFollowByID(ctx, s.st.Q(), id)
	if err != nil || fl.FrameID != frameID {
		return nil, problem.New(problem.NotFound, "remote subscription not found")
	}
	if !manager {
		return nil, problem.New(problem.MissingPermission, "requires the %s permission", "change_settings")
	}
	return fl, nil
}

func (s *Server) UpdateRemoteSubscription(ctx context.Context, request gen.UpdateRemoteSubscriptionRequestObject) (gen.UpdateRemoteSubscriptionResponseObject, error) {
	fl, err := s.frameFollow(ctx, request.FrameId, request.RemoteSubscriptionId)
	if err != nil {
		return nil, err
	}
	var onEnd *string
	if request.Body.OnEnd != nil {
		onEnd = ptr(string(*request.Body.OnEnd))
	}
	birthdays := request.Body.Birthdays
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFrame(ctx, fl.FrameID); err != nil {
			return notFound(err, "frame")
		}
		if _, err := tx.Exec(ctx, `UPDATE remote_follows SET on_end = COALESCE($2, on_end), birthdays = COALESCE($3, birthdays) WHERE id = $1`,
			fl.ID, onEnd, birthdays); err != nil {
			return err
		}
		if birthdays != nil && *birthdays != fl.Birthdays {
			return emitFrameBirthdays(ctx, tx, fl.FrameID)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	s.publishRemoteFollow(ctx, fl.ID)
	if fl, err = s.st.RemoteFollowByID(ctx, s.st.Q(), fl.ID); err != nil {
		return nil, err
	}
	return ok(s.remoteFollowJSON(fl)), nil
}

func (s *Server) DeleteRemoteSubscription(ctx context.Context, request gen.DeleteRemoteSubscriptionRequestObject) (gen.DeleteRemoteSubscriptionResponseObject, error) {
	fl, err := s.frameFollow(ctx, request.FrameId, request.RemoteSubscriptionId)
	if err != nil {
		return nil, err
	}
	if !fl.Live() {
		return noContent(), nil
	}
	reason := "unsubscribed"
	if fl.Status == "pending" {
		reason = "withdrawn"
	}
	var onEnd *string
	if request.Params.Placements != nil {
		onEnd = ptr(string(*request.Params.Placements))
	}
	if err := s.endFollowAs(ctx, fl.ID, reason, onEnd, true); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// remoteFollowJSON renders a follow (RemoteSubscription).
func (s *Server) remoteFollowJSON(f *store.RemoteFollow) gen.RemoteSubscription {
	out := gen.RemoteSubscription{Id: f.ID, FrameId: f.FrameID, Status: gen.RemoteSubscriptionStatus(f.Status), FeedName: f.FeedName,
		CoverUrl: f.CoverURL, Server: remoteServerJSON(f.ServerURL, f.ServerHost, f.RemoteServerKey, f.ServerName),
		History: gen.SubscriptionHistory(f.History), HistoryCount: f.HistoryCount, Birthdays: ptr(f.Birthdays), OnEnd: gen.RemoteOnEnd(f.OnEnd),
		ItemCount: ptr(f.ItemCount), RequestedByName: f.RequestedByName, CreatedAt: f.CreatedAt, ActivatedAt: f.ActivatedAt, EndedAt: f.EndedAt,
		LastSyncAt: f.LastPullAt, LastError: f.LastError}
	if f.RemovedReason != nil {
		out.RemovedReason = ptr(gen.RemoteEndReason(*f.RemovedReason))
	}
	state := gen.RemoteSubscriptionBackfillState("idle")
	if f.Status == "active" && f.PullCursor == nil {
		state = "running"
	}
	out.Backfill = &struct {
		Placed *int                                 `json:"placed,omitempty"`
		State  *gen.RemoteSubscriptionBackfillState `json:"state,omitempty"`
		Total  *int                                 `json:"total,omitempty"`
	}{Placed: ptr(f.ItemCount), State: &state}
	return out
}

// publishRemoteFollow sends remote_subscription.updated to the frame's managers.
func (s *Server) publishRemoteFollow(ctx context.Context, id uuid.UUID) {
	fl, err := s.st.RemoteFollowByID(ctx, s.st.Q(), id)
	if err != nil {
		return
	}
	managers, err := s.st.FrameManagerIDs(ctx, fl.FrameID)
	if err != nil {
		return
	}
	s.notify.Users(managers, notify.Event{"type": "remote_subscription.updated", "frame_id": fl.FrameID, "remote_subscription": s.remoteFollowJSON(fl)}, nil)
}

// ---------------------------------------------------------------- ending a follow

// endFollow ends a follow with the follow's on_end choice; tellA has the loop send A the signed DELETE.
func (s *Server) endFollow(ctx context.Context, id uuid.UUID, reason string, onEnd *string, tellA bool) {
	if err := s.endFollowAs(ctx, id, reason, onEnd, tellA); err != nil {
		s.log.Warn("federation: end follow", "follow", id, "err", err)
	}
}

func (s *Server) endFollowAs(ctx context.Context, id uuid.UUID, reason string, onEnd *string, tellA bool) error {
	fl, err := s.st.RemoteFollowByID(ctx, s.st.Q(), id)
	if err != nil {
		return err
	}
	ended := false
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		ended = false
		if _, err := tx.LockFrame(ctx, fl.FrameID); err != nil && err != store.ErrNotFound {
			return err
		}
		cur, err := tx.LockRemoteFollow(ctx, id)
		if err != nil {
			return err
		}
		if !cur.Live() {
			return nil
		}
		ended = true
		status := "removed"
		if reason == "rejected" {
			status = "rejected"
		}
		if _, err := tx.Exec(ctx, `UPDATE remote_follows SET status = $2, removed_reason = $3, ended_at = now(), birthday_people = '[]',
			unsubscribe_pending = $4, next_pull_at = NULL WHERE id = $1`, id, status, reason, tellA && cur.RemoteSubscriptionID != nil); err != nil {
			return err
		}
		choice := cur.OnEnd
		if onEnd != nil {
			choice = *onEnd
		}
		if choice == "remove" {
			if err := s.removeCopiesTx(ctx, tx, cur.FrameID, `ri.follow_id = $1`, id); err != nil {
				return err
			}
		}
		if len(cur.BirthdayPeople) > 2 {
			return emitFrameBirthdays(ctx, tx, cur.FrameID)
		}
		return nil
	})
	if err != nil || !ended {
		return err
	}
	s.purgeRemoteItems(ctx)
	s.publishRemoteFollow(ctx, id)
	s.wakeFederation()
	return nil
}

// ---------------------------------------------------------------- ping (B)

// federationPing is POST /federation/v1/ping (federationPing): A has new changes; pull those follows soon.
func (s *Server) federationPing(w http.ResponseWriter, r *http.Request) {
	caller, body := s.verifiedServer(w, r)
	if caller == nil {
		return
	}
	var req gen.FederationPing
	if err := json.Unmarshal(body, &req); err != nil || len(req.SubscriptionIds) > 500 {
		problem.Write(w, r, problem.New(problem.ValidationFailed, "malformed request body").WithStatus(http.StatusBadRequest))
		return
	}
	if len(req.SubscriptionIds) > 0 {
		follows, err := s.st.RemoteFollowsWhere(r.Context(), s.st.Q(), `f.server_id = $1 AND f.remote_subscription_id = ANY($2)
			AND f.status IN ('pending', 'active', 'error')`, caller.ID, req.SubscriptionIds)
		if err == nil {
			for _, f := range follows {
				s.kickPull(f.ID)
			}
		}
	}
	w.WriteHeader(http.StatusAccepted)
}

func (s *Server) kickPull(id uuid.UUID) {
	s.fed.kickMu.Lock()
	s.fed.kicked[id] = true
	s.fed.kickMu.Unlock()
	s.wakeFederation()
}

func (s *Server) wakeFederation() {
	select {
	case s.fed.wake <- struct{}{}:
	default:
	}
}

// ---------------------------------------------------------------- pulling

var pullLocks sync.Map // follow id -> *sync.Mutex: one pull per follow at a time

// pullFollow pulls a follow's changes until A has no more, applying each page before storing its cursor.
func (s *Server) pullFollow(ctx context.Context, id uuid.UUID) {
	mu, _ := pullLocks.LoadOrStore(id, &sync.Mutex{})
	mu.(*sync.Mutex).Lock()
	defer mu.(*sync.Mutex).Unlock()
	for page := 0; page < 100; page++ {
		fl, err := s.st.RemoteFollowByID(ctx, s.st.Q(), id)
		if err != nil || !fl.Live() || fl.RemoteSubscriptionID == nil {
			return
		}
		if err := s.federationRefusal(ctx, fl.ServerHost, nil); err != nil {
			s.pullFailed(ctx, fl, err)
			return
		}
		target := fl.ServerURL + "/federation/v1/subscriptions/" + fl.RemoteSubscriptionID.String() + "/changes?limit=100"
		if fl.PullCursor != nil && *fl.PullCursor != "" {
			target += "&cursor=" + url.QueryEscape(*fl.PullCursor)
		}
		var res gen.FederationChangePage
		if _, err := s.fed.client.Do(ctx, http.MethodGet, target, nil, &res); err != nil {
			if re, ok := federation.AsRemote(err); ok && re.Status == http.StatusUnauthorized {
				// Our key may be new to A, or A's document stale here: refresh A's document for next time.
				_, _ = s.remoteServer(ctx, fl.ServerURL, true)
			}
			s.pullFailed(ctx, fl, err)
			return
		}
		s.st.TouchRemoteServer(ctx, fl.ServerID)
		more, err := s.applyPage(ctx, fl, &res)
		if err != nil {
			s.pullFailed(ctx, fl, err)
			return
		}
		if !more {
			return
		}
	}
}

func (s *Server) pullFailed(ctx context.Context, fl *store.RemoteFollow, err error) {
	s.log.Info("federation: pull failed", "follow", fl.ID, "server", fl.ServerURL, "err", err)
	msg := err.Error()
	if len(msg) > 300 {
		msg = msg[:300]
	}
	// Back off: 1, 2, 4 … minutes up to the poll interval; `error` after 3 days.
	wait := time.Minute
	if fl.ErrorSince != nil {
		wait = min(max(s.now().Sub(*fl.ErrorSince)/4, time.Minute), s.pollInterval())
	}
	_, _ = s.st.Pool.Exec(ctx, `UPDATE remote_follows SET last_error = $2, error_since = COALESCE(error_since, now()),
		next_pull_at = now() + make_interval(secs => $3),
		status = CASE WHEN status <> 'pending' AND COALESCE(error_since, now()) < now() - interval '3 days' THEN 'error' ELSE status END
		WHERE id = $1`, fl.ID, msg, wait.Seconds())
}

// applyPage applies a change page; it reports whether A has more.
func (s *Server) applyPage(ctx context.Context, fl *store.RemoteFollow, page *gen.FederationChangePage) (bool, error) {
	if page.Reset != nil && *page.Reset {
		// A restarts from a full snapshot: what it doesn't bring again goes once B caught up.
		_, err := s.st.Pool.Exec(ctx, `UPDATE remote_follows SET resync_since = now(), pull_cursor = NULL WHERE id = $1`, fl.ID)
		return true, err
	}
	switch page.Status {
	case "pending":
		_, err := s.st.Pool.Exec(ctx, `UPDATE remote_follows SET last_pull_at = now(), last_error = NULL, error_since = NULL,
			next_pull_at = now() + make_interval(secs => $2) WHERE id = $1`, fl.ID, s.pollInterval().Seconds())
		return false, err
	case "rejected", "removed":
		reason := "removed_by_feed"
		if page.Status == "rejected" {
			reason = "rejected"
		}
		for _, c := range page.Changes {
			if c.Type == "subscription" && c.Reason != nil {
				reason = string(*c.Reason)
			}
		}
		s.endFollow(ctx, fl.ID, reason, nil, false)
		return false, nil
	}
	changed := fl.Status != "active"
	if changed {
		if _, err := s.st.Pool.Exec(ctx, `UPDATE remote_follows SET status = 'active', activated_at = COALESCE(activated_at, now()),
			error_since = NULL, last_error = NULL WHERE id = $1`, fl.ID); err != nil {
			return false, err
		}
	}
	if page.FeedName != nil && *page.FeedName != "" && *page.FeedName != fl.FeedName {
		if err := s.renameFollow(ctx, fl, *page.FeedName); err != nil {
			return false, err
		}
	}
	var commentPosts []uuid.UUID
	for _, c := range page.Changes {
		var err error
		switch c.Type {
		case "post.upsert":
			if c.Post != nil {
				err = s.copyRemotePost(ctx, fl, c.Post)
			}
		case "post.remove":
			if c.PostId != nil {
				err = s.removeCopies(ctx, fl.FrameID, `ri.follow_id = $1 AND ri.remote_post_id = $2`, fl.ID, *c.PostId)
			}
		case "items.remove":
			if c.ItemIds != nil {
				err = s.removeCopies(ctx, fl.FrameID, `ri.follow_id = $1 AND ri.remote_item_id = ANY($2)`, fl.ID, *c.ItemIds)
			}
		case "birthdays":
			err = s.setFollowBirthdays(ctx, fl, c.Birthdays)
		case "comments":
			if c.PostId != nil {
				cs := []gen.FederationComment{}
				if c.Comments != nil {
					cs = *c.Comments
				}
				raw, _ := json.Marshal(cs)
				_, err = s.st.Pool.Exec(ctx, `INSERT INTO remote_post_comments (follow_id, remote_post_id, comments) VALUES ($1, $2, $3)
					ON CONFLICT (follow_id, remote_post_id) DO UPDATE SET comments = EXCLUDED.comments, updated_at = now()`, fl.ID, *c.PostId, raw)
				commentPosts = append(commentPosts, *c.PostId)
			}
		case "subscription":
			if c.Status != nil && (*c.Status == "removed" || *c.Status == "rejected") {
				reason := "removed_by_feed"
				if c.Reason != nil {
					reason = string(*c.Reason)
				}
				s.endFollow(ctx, fl.ID, reason, nil, false)
				return false, nil
			}
		}
		if err != nil {
			return false, err
		}
	}
	if len(commentPosts) > 0 {
		s.syncRemoteCommentCards(ctx, fl, commentPosts)
	}
	more := page.More
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		var resync *time.Time
		if err := tx.QueryRow(ctx, `SELECT resync_since FROM remote_follows WHERE id = $1 FOR UPDATE`, fl.ID).Scan(&resync); err != nil {
			return err
		}
		if !more && resync != nil {
			// The snapshot after a reset is complete: drop the copies it didn't bring.
			if err := s.removeCopiesTx(ctx, tx, fl.FrameID, `ri.follow_id = $1 AND ri.seen_at < $2`, fl.ID, *resync); err != nil {
				return err
			}
			resync = nil
		}
		_, err := tx.Exec(ctx, `UPDATE remote_follows SET pull_cursor = $2, last_pull_at = now(), last_error = NULL, error_since = NULL,
			resync_since = $3, next_pull_at = now() + make_interval(secs => $4) WHERE id = $1`, fl.ID, page.Cursor, resync, s.pollInterval().Seconds())
		return err
	})
	if err != nil {
		return false, err
	}
	s.purgeRemoteItems(ctx)
	if changed || !more {
		s.publishRemoteFollow(ctx, fl.ID)
	}
	return more, nil
}

// renameFollow takes A's new family name and re-emits the copies (frames show "Lies · Janssens").
func (s *Server) renameFollow(ctx context.Context, fl *store.RemoteFollow, name string) error {
	return s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFrame(ctx, fl.FrameID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE remote_follows SET feed_name = $2 WHERE id = $1`, fl.ID, name); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, `SELECT id FROM placements WHERE origin_remote_follow_id = $1 AND frame_id = $2 AND removed_at IS NULL`, fl.ID, fl.FrameID)
		if err != nil {
			return err
		}
		var ids []uuid.UUID
		for rows.Next() {
			var id uuid.UUID
			if err := rows.Scan(&id); err != nil {
				rows.Close()
				return err
			}
			ids = append(ids, id)
		}
		rows.Close()
		return tx.FeedPutEntities(ctx, fl.FrameID, store.FeedPlacement, ids, false)
	})
}

// ---------------------------------------------------------------- copies

// copyRemotePost copies a post's new items (each master once, verified) and places them on the frame.
func (s *Server) copyRemotePost(ctx context.Context, fl *store.RemoteFollow, p *gen.FederationPost) error {
	if len(p.Items) == 0 {
		return nil
	}
	ids := make([]uuid.UUID, len(p.Items))
	for i, it := range p.Items {
		ids[i] = it.Id
	}
	have, err := s.st.RemoteItemsWhere(ctx, s.st.Q(), `ri.follow_id = $1 AND ri.remote_item_id = ANY($2)`, fl.ID, ids)
	if err != nil {
		return err
	}
	known := map[uuid.UUID]bool{}
	for _, h := range have {
		known[h.RemoteItemID] = true
	}
	if len(have) > 0 {
		if _, err := s.st.Pool.Exec(ctx, `UPDATE remote_items SET seen_at = now() WHERE follow_id = $1 AND remote_item_id = ANY($2)`, fl.ID, ids); err != nil {
			return err
		}
	}
	limits := s.Limits(ctx)
	for i := range p.Items {
		it := &p.Items[i]
		if known[it.Id] {
			continue
		}
		main, err := s.downloadMaster(ctx, fl, it.MasterUrl, int64(it.Bytes), it.Sha256, limits.MaxUploadBytes)
		if err != nil {
			s.log.Info("federation: copy skipped", "follow", fl.ID, "item", it.Id, "err", err)
			if errors.Is(err, errSkipCopy) {
				continue
			}
			return err
		}
		var motion *fetched
		if it.MotionUrl != nil {
			if m, err := s.downloadMaster(ctx, fl, *it.MotionUrl, 0, nil, limits.MaxUploadBytes); err == nil && m.kind == media.Video {
				motion = m
			} else if m != nil {
				_ = os.Remove(m.path)
			}
		}
		if err := s.placeCopy(ctx, fl, p, it, main, motion); err != nil {
			_ = os.Remove(main.path)
			if motion != nil {
				_ = os.Remove(motion.path)
			}
			return err
		}
	}
	return nil
}

var errSkipCopy = errors.New("federation: item not copied")

// downloadMaster fetches a master from A (same server only), checks its size, type and hash.
func (s *Server) downloadMaster(ctx context.Context, fl *store.RemoteFollow, raw string, size int64, sha *string, max int64) (*fetched, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return nil, errSkipCopy
	}
	if base, _, err := federation.NormalizeBaseURL(raw); err != nil || base != fl.ServerURL || u.Scheme == "" {
		return nil, errSkipCopy // masters come from the family's server only
	}
	if size > max {
		return nil, errSkipCopy
	}
	path := s.scratchPath()
	out, err := os.Create(path)
	if err != nil {
		return nil, err
	}
	h := sha256.New()
	n, _, err := s.fed.client.Download(ctx, raw, io.MultiWriter(out, h), max)
	cerr := out.Close()
	if err == nil {
		err = cerr
	}
	if err != nil {
		_ = os.Remove(path)
		if errors.Is(err, federation.ErrTooLarge) {
			return nil, errSkipCopy
		}
		if re, ok := federation.AsRemote(err); ok && re.Status == http.StatusNotFound {
			return nil, errSkipCopy
		}
		return nil, err
	}
	sum := hex.EncodeToString(h.Sum(nil))
	if (size > 0 && n != size) || (sha != nil && *sha != "" && !strings.EqualFold(*sha, sum)) {
		_ = os.Remove(path)
		return nil, errSkipCopy
	}
	kind, mime, err := media.Sniff(path)
	if err != nil || (kind != media.Photo && kind != media.Video) {
		_ = os.Remove(path)
		return nil, errSkipCopy
	}
	f := &fetched{path: path, kind: kind, mime: mime, sha: sum, size: n}
	if kind == media.Video {
		info, err := s.tools.ProbeVideo(ctx, path)
		if err != nil {
			_ = os.Remove(path)
			return nil, errSkipCopy
		}
		f.dur = info.DurationMS
	}
	return f, nil
}

// placeCopy makes the item (owned by the remote sender) and its placement on the frame.
func (s *Server) placeCopy(ctx context.Context, fl *store.RemoteFollow, p *gen.FederationPost, it *gen.FederationItem, main, motion *fetched) error {
	return s.st.InTx(ctx, func(tx *store.Tx) error {
		f, err := tx.LockFrame(ctx, fl.FrameID)
		if err != nil {
			return err
		}
		var live bool
		if err := tx.QueryRow(ctx, `SELECT status IN ('pending', 'active', 'error') FROM remote_follows WHERE id = $1 FOR UPDATE`, fl.ID).Scan(&live); err != nil || !live {
			return err
		}
		var exists bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM remote_items WHERE follow_id = $1 AND remote_item_id = $2)`, fl.ID, it.Id).Scan(&exists); err != nil {
			return err
		}
		if exists {
			_ = os.Remove(main.path)
			return nil
		}
		name := strings.TrimSpace(p.PosterName)
		if name == "" {
			name = fl.FeedName
		}
		key := p.PosterKey
		if key == "" {
			key = "-"
		}
		sender, err := tx.RemoteSenderFor(ctx, fl.ServerID, key, name)
		if err != nil {
			return err
		}
		mediaID := store.NewID()
		path, mime := main.path, main.mime
		md := store.Media{ID: mediaID, Kind: string(main.kind), SourcePath: &path, SourceMime: &mime, IncludeAudio: true, SourceSHA256: &main.sha}
		if main.kind == media.Video {
			md.DurationMS = &main.dur
		}
		if err := tx.CreateMedia(ctx, md); err != nil {
			return err
		}
		if err := tx.Enqueue(ctx, store.JobProcessMedia, map[string]any{"media_id": mediaID}); err != nil {
			return err
		}
		item := store.Item{ID: store.NewID(), MediaID: mediaID, Caption: it.Caption, CapturedOverride: it.CapturedAt}
		if it.Fit != nil {
			item.Fit = ptr(string(*it.Fit))
		}
		if it.FocalPoint != nil {
			x, y := float64(it.FocalPoint.X), float64(it.FocalPoint.Y)
			item.FocalX, item.FocalY = &x, &y
		}
		if motion != nil {
			mid := store.NewID()
			mpath, mmime := motion.path, motion.mime
			if err := tx.CreateMedia(ctx, store.Media{ID: mid, Kind: string(media.Video), SourcePath: &mpath, SourceMime: &mmime, Motion: true,
				SourceSHA256: &motion.sha}); err != nil {
				return err
			}
			if err := tx.Enqueue(ctx, store.JobProcessMedia, map[string]any{"media_id": mid}); err != nil {
				return err
			}
			item.MotionMediaID = &mid
		}
		if err := tx.CreateItem(ctx, item); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE items SET remote_sender_id = $2 WHERE id = $1`, item.ID, sender); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO remote_items (item_id, follow_id, remote_post_id, remote_item_id, sender_id) VALUES ($1, $2, $3, $4, $5)`,
			item.ID, fl.ID, p.Id, it.Id, sender); err != nil {
			return err
		}
		quiet := p.Backfill != nil && *p.Backfill
		pl := store.Placement{ID: store.NewID(), FrameID: f.ID, ItemID: item.ID, Status: "pending", PendingModeration: f.Moderation,
			Origin: "feed", Backfill: quiet}
		if quiet {
			pl.ReceivedAt = p.CreatedAt
		}
		if err := tx.CreatePlacement(ctx, pl); err != nil {
			return err
		}
		_, err = tx.Exec(ctx, `UPDATE placements SET origin_remote_follow_id = $2 WHERE id = $1`, pl.ID, fl.ID)
		return err
	})
}

// removeCopies removes copies (where on ri, the remote_items row) from the frame.
func (s *Server) removeCopies(ctx context.Context, frameID uuid.UUID, where string, args ...any) error {
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFrame(ctx, frameID); err != nil && err != store.ErrNotFound {
			return err
		}
		return s.removeCopiesTx(ctx, tx, frameID, where, args...)
	})
	if err == nil {
		s.purgeRemoteItems(ctx)
	}
	return err
}

func (s *Server) removeCopiesTx(ctx context.Context, tx *store.Tx, frameID uuid.UUID, where string, args ...any) error {
	rows, err := s.st.RemoteItemsWhere(ctx, tx, where, args...)
	if err != nil || len(rows) == 0 {
		return err
	}
	items := make([]uuid.UUID, len(rows))
	for i, r := range rows {
		items[i] = r.ItemID
	}
	if _, err := tx.RemovePlacements(ctx, `p.frame_id = $1 AND p.item_id = ANY($2)`, frameID, items); err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `DELETE FROM remote_items WHERE item_id = ANY($1)`, items)
	return err
}

// purgeRemoteItems deletes copied items that nothing holds any more (no remote_items row, no live placement), with
// their media and files.
func (s *Server) purgeRemoteItems(ctx context.Context) {
	type gone struct {
		ItemID  uuid.UUID `db:"id"`
		MediaID uuid.UUID `db:"media_id"`
	}
	rows, err := store.FederationMany[gone](s.st.Pool.Query(ctx, `SELECT i.id, i.media_id FROM items i
		WHERE i.remote_sender_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM remote_items ri WHERE ri.item_id = i.id)
		AND NOT EXISTS (SELECT 1 FROM placements p WHERE p.item_id = i.id AND p.removed_at IS NULL) LIMIT 200`))
	if err != nil {
		s.log.Warn("federation: purge copies", "err", err)
		return
	}
	for _, g := range rows {
		var keys []string
		err := s.st.InTx(ctx, func(tx *store.Tx) error {
			keys = nil
			var motion *uuid.UUID
			var t256, t512 *string
			if err := tx.QueryRow(ctx, `SELECT motion_media_id, thumb256_key, thumb512_key FROM items WHERE id = $1 FOR UPDATE`, g.ItemID).Scan(&motion, &t256, &t512); err != nil {
				return err
			}
			for _, k := range []*string{t256, t512} {
				if k != nil {
					keys = append(keys, *k)
				}
			}
			if _, err := tx.Exec(ctx, `DELETE FROM placements WHERE item_id = $1`, g.ItemID); err != nil {
				return err
			}
			if _, err := tx.Exec(ctx, `DELETE FROM items WHERE id = $1`, g.ItemID); err != nil {
				return err
			}
			for _, mid := range []*uuid.UUID{&g.MediaID, motion} {
				if mid == nil {
					continue
				}
				var used bool
				if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM items WHERE media_id = $1 OR motion_media_id = $1)`, *mid).Scan(&used); err != nil || used {
					return err
				}
				krows, err := tx.Query(ctx, `SELECT key FROM renditions WHERE media_id = $1 UNION ALL SELECT poster_key FROM renditions WHERE media_id = $1 AND poster_key IS NOT NULL
					UNION ALL SELECT master_key FROM media WHERE id = $1 AND master_key IS NOT NULL UNION ALL SELECT poster_key FROM media WHERE id = $1 AND poster_key IS NOT NULL`, *mid)
				if err != nil {
					return err
				}
				for krows.Next() {
					var k string
					if krows.Scan(&k) == nil {
						keys = append(keys, k)
					}
				}
				krows.Close()
				if _, err := tx.Exec(ctx, `DELETE FROM media WHERE id = $1`, *mid); err != nil {
					return err
				}
			}
			return nil
		})
		if err != nil {
			s.log.Info("federation: purge copy", "item", g.ItemID, "err", err)
			continue
		}
		for _, k := range keys {
			s.deleteBlob(ctx, k)
		}
	}
}

// ---------------------------------------------------------------- birthdays and comment cards on B

func (s *Server) setFollowBirthdays(ctx context.Context, fl *store.RemoteFollow, people *[]gen.FederationBirthday) error {
	list := []gen.FederationBirthday{}
	if people != nil {
		list = *people
	}
	raw, _ := json.Marshal(list)
	return s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFrame(ctx, fl.FrameID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE remote_follows SET birthday_people = $2 WHERE id = $1`, fl.ID, raw); err != nil {
			return err
		}
		return emitFrameBirthdays(ctx, tx, fl.FrameID)
	})
}

// emitFrameBirthdays re-emits the frame's `birthdays` change (the frame locked by the caller).
func emitFrameBirthdays(ctx context.Context, tx *store.Tx, frameID uuid.UUID) error {
	if _, err := tx.Exec(ctx, `INSERT INTO frame_birthdays (frame_id, digest, emitted_at) VALUES ($1, '', now())
		ON CONFLICT (frame_id) DO UPDATE SET emitted_at = now()`, frameID); err != nil {
		return err
	}
	return tx.FeedPut(ctx, frameID, store.FeedBirthdays, frameID, false)
}

// remoteFrameBirthdaysOf is remoteFrameBirthdays' body: the people of the frame's active follows with birthdays on.
func (s *Server) remoteFrameBirthdaysOf(ctx context.Context, q db.DBTX, frameID uuid.UUID) ([]gen.FrameBirthday, error) {
	follows, err := s.st.RemoteFollowsWhere(ctx, q, `f.frame_id = $1 AND f.status = 'active' AND f.birthdays`, frameID)
	if err != nil {
		return nil, err
	}
	var out []gen.FrameBirthday
	for _, f := range follows {
		var people []gen.FederationBirthday
		if json.Unmarshal(f.BirthdayPeople, &people) != nil {
			continue
		}
		for _, p := range people {
			out = append(out, gen.FrameBirthday{Id: p.Id, Name: p.Name, Month: p.Month, Day: p.Day, BirthYear: p.Year,
				BirthdayIds: []gen.Id{p.Id}, FeedIds: []gen.Id{}})
		}
	}
	return out, nil
}

// mergeRemoteBirthdays adds the people of families on other servers to the frame's list: the same person (name,
// month and day) shows once; the list stays sorted by month and day.
func mergeRemoteBirthdays(people []map[string]any, remote []gen.FrameBirthday) []map[string]any {
	if len(remote) == 0 {
		return people
	}
	key := func(name string, month, day int) string {
		return strings.ToLower(strings.TrimSpace(name)) + "|" + string(rune('a'+month)) + "|" + string(rune('a'+day))
	}
	seen := map[string]bool{}
	for _, p := range people {
		name, _ := p["name"].(string)
		month, _ := p["month"].(int)
		day, _ := p["day"].(int)
		seen[key(name, month, day)] = true
	}
	for _, r := range remote {
		k := key(r.Name, r.Month, r.Day)
		if seen[k] {
			continue
		}
		seen[k] = true
		p := map[string]any{"id": r.Id, "name": r.Name, "day": r.Day, "month": r.Month, "feed_ids": r.FeedIds, "birthday_ids": r.BirthdayIds}
		if r.BirthYear != nil {
			p["birth_year"] = *r.BirthYear
		}
		people = append(people, p)
	}
	sort.SliceStable(people, func(i, j int) bool {
		mi, _ := people[i]["month"].(int)
		mj, _ := people[j]["month"].(int)
		if mi != mj {
			return mi < mj
		}
		di, _ := people[i]["day"].(int)
		dj, _ := people[j]["day"].(int)
		return di < dj
	})
	return people
}

// syncRemoteCommentCards refreshes the frame's comment cards of the copies of these posts.
func (s *Server) syncRemoteCommentCards(ctx context.Context, fl *store.RemoteFollow, posts []uuid.UUID) {
	rows, err := s.st.Pool.Query(ctx, `SELECT p.id FROM placements p JOIN remote_items ri ON ri.item_id = p.item_id AND ri.follow_id = $1
		WHERE p.frame_id = $2 AND p.removed_at IS NULL AND ri.remote_post_id = ANY($3)`, fl.ID, fl.FrameID, posts)
	if err != nil {
		return
	}
	var ids []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if rows.Scan(&id) == nil {
			ids = append(ids, id)
		}
	}
	rows.Close()
	if len(ids) > 0 {
		if err := s.syncCommentCards(ctx, fl.FrameID, ids); err != nil {
			s.log.Warn("federation: comment cards", "frame", fl.FrameID, "err", err)
		}
	}
}

// remotePlacementComments is the `comments` card of a copy: the comments A sent for its post (handled=false for
// other placements).
func (s *Server) remotePlacementComments(ctx context.Context, q db.DBTX, f *store.Frame, placementID uuid.UUID) (*gen.PlacementComments, bool, error) {
	var raw []byte
	err := q.QueryRow(ctx, `SELECT c.comments FROM placements p JOIN remote_items ri ON ri.item_id = p.item_id AND ri.follow_id = p.origin_remote_follow_id
		JOIN remote_post_comments c ON c.follow_id = ri.follow_id AND c.remote_post_id = ri.remote_post_id
		WHERE p.id = $1 AND p.frame_id = $2 AND p.removed_at IS NULL`, placementID, f.ID).Scan(&raw)
	if db.NotFound(err) {
		var remote bool
		if err := q.QueryRow(ctx, `SELECT origin_remote_follow_id IS NOT NULL FROM placements WHERE id = $1`, placementID).Scan(&remote); err != nil || !remote {
			return nil, false, nil
		}
		return nil, true, nil
	}
	if err != nil {
		return nil, true, err
	}
	var cs []gen.FederationComment
	if json.Unmarshal(raw, &cs) != nil || len(cs) == 0 {
		return nil, true, nil
	}
	out := gen.PlacementComments{PlacementId: placementID}
	for i, c := range cs {
		if c.Text == nil || strings.TrimSpace(*c.Text) == "" {
			continue
		}
		// A stable id per position and post: the card is replaced as a whole.
		id := uuid.NewSHA1(placementID, []byte{byte(i)})
		out.Comments = append(out.Comments, struct {
			AuthorName string    `json:"author_name"`
			Body       string    `json:"body"`
			CreatedAt  time.Time `json:"created_at"`
			Id         gen.Id    `json:"id"`
		}{AuthorName: c.FirstName, Body: cutRunes(*c.Text, frameCommentRune), CreatedAt: c.At, Id: id})
	}
	if len(out.Comments) == 0 {
		return nil, true, nil
	}
	return &out, true, nil
}

// ---------------------------------------------------------------- activity out (B -> A)

// queueRemoteActivity is remoteFrameActivity's body.
func (s *Server) queueRemoteActivity(ctx context.Context, placementID uuid.UUID, kind string, reaction *gen.Reaction, reply *gen.ReplyPreset) {
	var follow, post, item uuid.UUID
	err := s.st.Q().QueryRow(ctx, `SELECT ri.follow_id, ri.remote_post_id, ri.remote_item_id FROM placements p
		JOIN remote_items ri ON ri.item_id = p.item_id AND ri.follow_id = p.origin_remote_follow_id
		JOIN remote_follows f ON f.id = ri.follow_id AND f.status = 'active'
		WHERE p.id = $1`, placementID).Scan(&follow, &post, &item)
	if err != nil {
		return // not a copy (or its follow ended)
	}
	ev := map[string]any{"id": store.NewID(), "kind": kind, "post_id": post, "item_id": item, "at": s.now().UTC()}
	switch kind {
	case "reaction":
		ev["reaction"] = reaction
	case "reply":
		if reply == nil {
			return
		}
		ev["reply"] = *reply
	}
	raw, _ := json.Marshal(ev)
	if _, err := s.st.Pool.Exec(ctx, `INSERT INTO remote_activity_outbox (id, follow_id, event) VALUES ($1, $2, $3)`, store.NewID(), follow, raw); err != nil {
		s.log.Warn("federation: queue activity", "err", err)
		return
	}
	s.wakeFederation()
}
