package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/crash"
	"github.com/jeroentrappers/kinframe/server/internal/federation"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// The federation loop (RunFederation): pings to other servers after new changes (debounced), pulls of follows
// (on a ping and every 15 minutes), the activity outbox, ends to tell, document refreshes, key rotation and the
// change log's retention.

func (s *Server) pingDelay() time.Duration {
	if d := s.cfg.Federation.PingDelay; d > 0 {
		return d
	}
	return 10 * time.Second
}

func (s *Server) pollInterval() time.Duration {
	if d := s.cfg.Federation.PollInterval; d > 0 {
		return d
	}
	return 15 * time.Minute
}

func (s *Server) federationTick() time.Duration {
	if d := s.cfg.Federation.Tick; d > 0 {
		return d
	}
	return 2 * time.Second
}

// serverBackoff keeps a server that failed from being called every tick.
type serverBackoff struct {
	mu    sync.Mutex
	until map[string]time.Time
	fails map[string]int
}

func (b *serverBackoff) ready(key string, now time.Time) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return now.After(b.until[key])
}

func (b *serverBackoff) result(key string, err error, now time.Time) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.until == nil {
		b.until, b.fails = map[string]time.Time{}, map[string]int{}
	}
	if err == nil {
		delete(b.until, key)
		delete(b.fails, key)
		return
	}
	b.fails[key]++
	wait := time.Duration(1<<min(b.fails[key], 10)) * 5 * time.Second // 10 s … 85 min
	b.until[key] = now.Add(min(wait, time.Hour))
}

// RunFederation is server-federation's background loop.
func (s *Server) RunFederation(ctx context.Context) {
	if !s.federationOn() {
		<-ctx.Done()
		return
	}
	var back serverBackoff
	tick := time.NewTicker(s.federationTick())
	defer tick.Stop()
	var lastHousekeeping time.Time
	for {
		s.federationStep(ctx, &back)
		if s.now().Sub(lastHousekeeping) > time.Hour {
			lastHousekeeping = s.now()
			s.federationHousekeeping(ctx)
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		case <-s.fed.wake:
		}
	}
}

func (s *Server) federationStep(ctx context.Context, back *serverBackoff) {
	if ctx.Err() != nil {
		return
	}
	now := s.now()
	s.sendPings(ctx, back, now)
	s.sendEnds(ctx, back, now)
	s.sendUnsubscribes(ctx, back, now)
	s.runPulls(ctx)
	s.sendActivity(ctx, back, now)
}

// sendPings tells B servers about new changes: debounced (the newest change settled for the ping delay, or the
// oldest waiting six times that), one ping per server, never with data.
func (s *Server) sendPings(ctx context.Context, back *serverBackoff, now time.Time) {
	due, err := s.st.DuePings(ctx, s.pingDelay(), 6*s.pingDelay())
	if err != nil {
		s.log.Warn("federation: due pings", "err", err)
		return
	}
	byServer := map[string][]store.PingDue{}
	var order []string
	for _, d := range due {
		if _, ok := byServer[d.ServerURL]; !ok {
			order = append(order, d.ServerURL)
		}
		byServer[d.ServerURL] = append(byServer[d.ServerURL], d)
	}
	for _, base := range order {
		list := byServer[base]
		if !back.ready("ping "+base, now) || s.federationRefusal(ctx, list[0].Host, nil) != nil {
			continue
		}
		for len(list) > 0 {
			n := min(len(list), 500)
			chunk := list[:n]
			list = list[n:]
			ids := make([]uuid.UUID, len(chunk))
			for i, d := range chunk {
				ids[i] = d.ID
			}
			_, err := s.fed.client.Do(ctx, http.MethodPost, base+"/federation/v1/ping", gen.FederationPing{SubscriptionIds: ids}, nil)
			back.result("ping "+base, err, now)
			if err != nil {
				s.log.Info("federation: ping failed", "server", base, "err", err)
				break
			}
			if err := s.st.MarkPinged(ctx, chunk); err != nil {
				s.log.Warn("federation: mark pinged", "err", err)
			}
		}
	}
}

// sendEnds tells B servers about subscriptions A ended (signed DELETE with the reason), until they answered.
func (s *Server) sendEnds(ctx context.Context, back *serverBackoff, now time.Time) {
	subs, err := store.FederationMany[store.RemoteSubscriber](s.st.Pool.Query(ctx, `SELECT x.id, x.feed_id, x.server_id, x.remote_frame_id,
		x.follow_id, x.frame_name, x.requested_by_name, x.status, x.history, x.history_count, x.birthdays, x.show_comments, x.via_invite_id,
		x.decided_by, x.removed_reason, x.activated_at, x.ended_at, x.last_pull_at, x.last_ping_at, x.pinged_seq, x.created_at, x.notify_end,
		rs.base_url AS server_url, rs.host AS server_host, rs.server_name, rs.server_id AS remote_server_key, f.name AS feed_name
		FROM remote_subscribers x JOIN remote_servers rs ON rs.id = x.server_id JOIN feeds f ON f.id = x.feed_id
		WHERE x.notify_end ORDER BY x.ended_at LIMIT 100`))
	if err != nil {
		s.log.Warn("federation: ends", "err", err)
		return
	}
	for _, x := range subs {
		key := "end " + x.ServerURL
		if !back.ready(key, now) {
			continue
		}
		target := x.ServerURL + "/federation/v1/subscriptions/" + x.ID.String()
		if x.RemovedReason != nil {
			target += "?reason=" + url.QueryEscape(*x.RemovedReason)
		}
		_, err := s.fed.client.Do(ctx, http.MethodDelete, target, nil, nil)
		if re, ok := federation.AsRemote(err); ok && re.Status == http.StatusNotFound {
			err = nil
		}
		back.result(key, err, now)
		// A server that is blocked or not allowed any more is not called; the change feed says it anyway.
		if err == nil || (x.EndedAt != nil && now.Sub(*x.EndedAt) > 7*24*time.Hour) || s.federationRefusal(ctx, x.ServerHost, nil) != nil && err != nil {
			_, _ = s.st.Pool.Exec(ctx, `UPDATE remote_subscribers SET notify_end = false WHERE id = $1`, x.ID)
		}
	}
}

// sendUnsubscribes tells A servers about follows B ended, retried while A is down.
func (s *Server) sendUnsubscribes(ctx context.Context, back *serverBackoff, now time.Time) {
	follows, err := s.st.RemoteFollowsWhere(ctx, s.st.Q(), `f.unsubscribe_pending AND f.remote_subscription_id IS NOT NULL`)
	if err != nil {
		s.log.Warn("federation: unsubscribes", "err", err)
		return
	}
	for _, f := range follows {
		key := "unsubscribe " + f.ServerURL
		if !back.ready(key, now) {
			continue
		}
		_, err := s.fed.client.Do(ctx, http.MethodDelete, f.ServerURL+"/federation/v1/subscriptions/"+f.RemoteSubscriptionID.String(), nil, nil)
		if re, ok := federation.AsRemote(err); ok && (re.Status == http.StatusNotFound || re.Status == http.StatusForbidden) {
			err = nil // A doesn't know it any more, or won't talk to us: nothing left to tell
		}
		back.result(key, err, now)
		if err == nil || (f.EndedAt != nil && now.Sub(*f.EndedAt) > 7*24*time.Hour) {
			_, _ = s.st.Pool.Exec(ctx, `UPDATE remote_follows SET unsubscribe_pending = false WHERE id = $1`, f.ID)
		}
	}
}

// runPulls pulls the follows that were pinged or are due.
func (s *Server) runPulls(ctx context.Context) {
	s.fed.kickMu.Lock()
	kicked := s.fed.kicked
	s.fed.kicked = map[uuid.UUID]bool{}
	s.fed.kickMu.Unlock()
	due, err := s.st.RemoteFollowsWhere(ctx, s.st.Q(), `f.status IN ('pending', 'active', 'error') AND f.remote_subscription_id IS NOT NULL
		AND (f.next_pull_at IS NULL OR f.next_pull_at <= now())`)
	if err != nil {
		s.log.Warn("federation: due pulls", "err", err)
		return
	}
	for _, f := range due {
		kicked[f.ID] = true
	}
	var wg sync.WaitGroup
	sem := make(chan struct{}, 4)
	for id := range kicked {
		if ctx.Err() != nil {
			break
		}
		wg.Add(1)
		sem <- struct{}{}
		go func(id uuid.UUID) {
			defer crash.Recover("federation")
			defer wg.Done()
			defer func() { <-sem }()
			s.pullFollow(ctx, id)
		}(id)
	}
	wg.Wait()
}

// sendActivity sends the outbox (the frames' reactions, replies and "seen" on copies) to A in batches.
func (s *Server) sendActivity(ctx context.Context, back *serverBackoff, now time.Time) {
	type row struct {
		ID       uuid.UUID       `db:"id"`
		FollowID uuid.UUID       `db:"follow_id"`
		Event    json.RawMessage `db:"event"`
		Attempts int             `db:"attempts"`
	}
	rows, err := store.FederationMany[row](s.st.Pool.Query(ctx, `SELECT o.id, o.follow_id, o.event, o.attempts FROM remote_activity_outbox o
		WHERE o.next_attempt_at <= now() ORDER BY o.follow_id, o.created_at LIMIT 1000`))
	if err != nil {
		s.log.Warn("federation: outbox", "err", err)
		return
	}
	byFollow := map[uuid.UUID][]row{}
	var order []uuid.UUID
	for _, r := range rows {
		if _, ok := byFollow[r.FollowID]; !ok {
			order = append(order, r.FollowID)
		}
		byFollow[r.FollowID] = append(byFollow[r.FollowID], r)
	}
	for _, fid := range order {
		f, err := s.st.RemoteFollowByID(ctx, s.st.Q(), fid)
		if err != nil || f.RemoteSubscriptionID == nil || f.Status != "active" {
			_, _ = s.st.Pool.Exec(ctx, `DELETE FROM remote_activity_outbox WHERE follow_id = $1`, fid)
			continue
		}
		key := "activity " + f.ServerURL
		if !back.ready(key, now) {
			continue
		}
		list := byFollow[fid]
		for len(list) > 0 {
			n := min(len(list), 200)
			chunk := list[:n]
			list = list[n:]
			events := make([]json.RawMessage, n)
			ids := make([]uuid.UUID, n)
			for i, r := range chunk {
				events[i], ids[i] = r.Event, r.ID
			}
			body := map[string]any{"events": events}
			if fr, err := s.st.GetFrame(ctx, s.st.Q(), f.FrameID); err == nil {
				body["frame"] = map[string]any{"name": fr.Name, "show_comments": frameSocialSetting(fr, "show_comments", false)}
			}
			_, err := s.fed.client.Do(ctx, http.MethodPost, f.ServerURL+"/federation/v1/subscriptions/"+f.RemoteSubscriptionID.String()+"/activity", body, nil)
			back.result(key, err, now)
			if err == nil {
				_, _ = s.st.Pool.Exec(ctx, `DELETE FROM remote_activity_outbox WHERE id = ANY($1)`, ids)
				continue
			}
			s.log.Info("federation: activity failed", "server", f.ServerURL, "err", err)
			// Retried with back-off for a week, then dropped.
			_, _ = s.st.Pool.Exec(ctx, `UPDATE remote_activity_outbox SET attempts = attempts + 1,
				next_attempt_at = now() + make_interval(secs => LEAST(3600, 10 * power(2, LEAST(attempts, 9))))
				WHERE id = ANY($1)`, ids)
			_, _ = s.st.Pool.Exec(ctx, `DELETE FROM remote_activity_outbox WHERE id = ANY($1) AND created_at < now() - interval '7 days'`, ids)
			break
		}
	}
}

// sendFrameInfo tells A the frame's current name and *show comments* (also with no events), so renames and the
// comment setting reach the family's server even when the frame does not react.
func (s *Server) sendFrameInfo(ctx context.Context) {
	follows, err := s.st.RemoteFollowsWhere(ctx, s.st.Q(), `f.status = 'active' AND f.remote_subscription_id IS NOT NULL`)
	if err != nil {
		return
	}
	for _, f := range follows {
		fr, err := s.st.GetFrame(ctx, s.st.Q(), f.FrameID)
		if err != nil || s.federationRefusal(ctx, f.ServerHost, nil) != nil {
			continue
		}
		body := map[string]any{"frame": map[string]any{"name": fr.Name, "show_comments": frameSocialSetting(fr, "show_comments", false)}}
		if _, err := s.fed.client.Do(ctx, http.MethodPost, f.ServerURL+"/federation/v1/subscriptions/"+f.RemoteSubscriptionID.String()+"/activity", body, nil); err != nil {
			s.log.Info("federation: frame info", "server", f.ServerURL, "err", err)
		}
	}
}

// federationHousekeeping (hourly): key rotation, expired keys, the change log's retention, old idempotency rows,
// daily document refreshes of the servers in use.
func (s *Server) federationHousekeeping(ctx context.Context) {
	if cur, keys, err := s.federationKeys(ctx); err == nil && cur != nil && len(keys) > 0 && s.now().Sub(keys[0].CreatedAt) > federation.KeyLifetime {
		if err := s.rotateFederationKey(ctx); err != nil {
			s.log.Warn("federation: rotate key", "err", err)
		}
	}
	if err := s.st.DeleteExpiredFederationKeys(ctx); err != nil {
		s.log.Warn("federation: expired keys", "err", err)
	}
	if err := s.st.PruneFederationChanges(ctx, changeRetention); err != nil {
		s.log.Warn("federation: prune changes", "err", err)
	}
	_, _ = s.st.Pool.Exec(ctx, `DELETE FROM remote_activity_seen WHERE created_at < now() - interval '30 days'`)
	s.sendFrameInfo(ctx)
	rows, err := s.st.Pool.Query(ctx, `SELECT r.base_url FROM remote_servers r WHERE (r.fetched_at IS NULL OR r.fetched_at < now() - interval '1 day')
		AND (EXISTS (SELECT 1 FROM remote_subscribers x WHERE x.server_id = r.id AND x.status IN ('pending', 'active'))
			OR EXISTS (SELECT 1 FROM remote_follows f WHERE f.server_id = r.id AND f.status IN ('pending', 'active', 'error')))
		LIMIT 50`)
	if err != nil {
		return
	}
	var bases []string
	for rows.Next() {
		var b string
		if rows.Scan(&b) == nil {
			bases = append(bases, b)
		}
	}
	rows.Close()
	for _, b := range bases {
		_, _ = s.remoteServer(ctx, b, true)
	}
}
