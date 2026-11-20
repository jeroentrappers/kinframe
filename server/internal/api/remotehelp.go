package api

// Remote help (docs/spec/remote-help.md, README §5.23): the server operator switches on a temporary tailnet tunnel
// from a frame to their Headscale, so they can use ADB on it. Only the operator starts a session; the frame's
// members see it and owner/admins stop it; the frame itself can stop it.

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/headscale"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/secrets"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

const (
	// featureRemoteHelp (Heartbeat.features): the frame can join the tailnet (GitHub build, rooted).
	featureRemoteHelp = "remote_help"
	// remoteHelpPending is how long a session waits for the frame to report `on` before it ends as failed. The first
	// session downloads Tailscale (≈37 MB), which took 7 minutes on a frame's slow Wi-Fi.
	remoteHelpPending = 30 * time.Minute
	// remoteHelpRetention is how long ended sessions stay in the log.
	remoteHelpRetention = 2 * 365 * 24 * time.Hour
	// remoteHelpTick is how often RunRemoteHelp looks for sessions to end.
	remoteHelpTick = 30 * time.Second
	// remoteHelpTag is the ACL tag frames' pre-auth keys carry (docs/spec/remote-help.md §5.3).
	remoteHelpTag = "tag:kinframe-frame"
)

// remoteHelp is the server's remote help state; hs is nil when Headscale is not configured.
type remoteHelp struct {
	hs     *headscale.Client
	sealer *secrets.Sealer

	mu     sync.Mutex
	userID headscale.ID // the Headscale user's id, looked up once
}

func (s *Server) setupRemoteHelp() error {
	sealer, err := secrets.New(s.cfg.SecretsKey, s.cfg.SigningKey, "kinframe remote help keys v1", "kinframe remote help keys from signing key v1")
	if err != nil {
		return err
	}
	s.rh = &remoteHelp{sealer: sealer}
	if c := s.cfg.RemoteHelp; c.HeadscaleURL != "" && c.HeadscaleAPIKey != "" {
		s.rh.hs = headscale.New(c.HeadscaleURL, c.HeadscaleAPIKey)
	}
	return nil
}

// remoteHelpReady: remote help is on and fully configured (discovery `remote_help`).
func (s *Server) remoteHelpReady() bool {
	return s.rh != nil && s.rh.hs != nil && s.cfg.RemoteHelp.Ready()
}

func (s *Server) requireRemoteHelp() error {
	if !s.remoteHelpReady() {
		return problem.New(problem.FeatureDisabled, "remote help is not configured on this server")
	}
	return nil
}

func (s *Server) headscaleUserID(ctx context.Context) (headscale.ID, error) {
	s.rh.mu.Lock()
	defer s.rh.mu.Unlock()
	if s.rh.userID != 0 {
		return s.rh.userID, nil
	}
	u, err := s.rh.hs.UserByName(ctx, s.cfg.RemoteHelp.HeadscaleUser)
	if err != nil {
		return 0, err
	}
	s.rh.userID = u.ID
	return u.ID, nil
}

// ---------------------------------------------------------------- rendering

var hostnameUnsafe = regexp.MustCompile(`[^a-z0-9-]+`)

// remoteHelpHostname is `kinframe-<device serial or short device id>`, lowercase [a-z0-9-], at most 63. The serial
// comes from enrolment (`hardware.serial`) or, for frames enrolled before, from the latest heartbeat's `serial`.
func remoteHelpHostname(d *store.Device) string {
	var hw, hb struct {
		Serial string `json:"serial"`
	}
	_ = json.Unmarshal(d.Hardware, &hw)
	_ = json.Unmarshal(d.LastHeartbeat, &hb)
	serial := hw.Serial
	if serial == "" || strings.EqualFold(serial, "unknown") {
		serial = hb.Serial
	}
	id := strings.Trim(hostnameUnsafe.ReplaceAllString(strings.ToLower(serial), "-"), "-")
	if id == "" || strings.EqualFold(id, "unknown") {
		hex := strings.ReplaceAll(d.ID.String(), "-", "")
		id = hex[len(hex)-8:] // the random end of the UUIDv7
	}
	h := "kinframe-" + id
	if len(h) > 63 {
		h = strings.TrimRight(h[:63], "-")
	}
	return h
}

func adbCommand(hostname string) string { return "adb connect " + hostname + ":5555" }

// remoteHelpJSON renders a session; full adds what only the operator sees (tailnet IP, error).
func remoteHelpJSON(r *store.RemoteHelpSession, full bool) gen.RemoteHelpSession {
	out := gen.RemoteHelpSession{
		Id: r.ID, FrameId: r.FrameID, FrameName: r.FrameName, State: gen.RemoteHelpSessionState(r.State), Reason: r.Reason,
		StartedByName: r.StartedByName, StartedAt: r.StartedAt, EndsAt: r.EndsAt, EndedAt: r.EndedAt, EndedByName: r.EndedByName,
		Hostname: r.Hostname, AdbCommand: adbCommand(r.Hostname),
	}
	if r.EndedBy != nil {
		out.EndedBy = ptr(gen.RemoteHelpEndedBy(*r.EndedBy))
	}
	if full {
		out.TailnetIp, out.Error = r.TailnetIP, r.Error
	}
	return out
}

// remoteHelpChange renders the `remote_help` change payload: the active session, or nil.
func (s *Server) remoteHelpChange(ctx context.Context, f *store.Frame) (map[string]any, error) {
	r, err := s.st.ActiveRemoteHelp(ctx, s.st.Q(), f.ID)
	if err == store.ErrNotFound {
		return nil, nil
	} else if err != nil {
		return nil, err
	}
	c := s.cfg.RemoteHelp
	ts := map[string]any{"version": c.TailscaleVersion}
	for _, arch := range []string{"arm", "arm64"} {
		ts[arch] = map[string]any{"url": c.TailscaleURL(arch), "sha256": c.TailscaleSHA256[arch]}
	}
	out := map[string]any{
		"session_id": r.ID, "state": "active", "ends_at": r.EndsAt.UTC(), "login_server": c.HeadscaleURL,
		"hostname": r.Hostname, "adb_key": c.ADBKey, "tailscale": ts,
	}
	if len(r.AuthKeyEnc) > 0 {
		key, err := s.rh.sealer.Open(r.AuthKeyEnc)
		if err != nil {
			s.log.Warn("remote help: open auth key", "session", r.ID, "err", err)
		} else {
			out["auth_key"] = key
		}
	}
	return out, nil
}

// fillFrameRemoteHelp fills FrameSummary.remote_help for these frames (listMyFrames, getFrame).
func (s *Server) fillFrameRemoteHelp(ctx context.Context, frames []gen.FrameSummary) {
	if len(frames) == 0 {
		return
	}
	ids := make([]uuid.UUID, len(frames))
	for i, f := range frames {
		ids[i] = f.Id
	}
	active, err := s.st.ActiveRemoteHelpOf(ctx, s.st.Q(), ids)
	if err != nil {
		s.log.Warn("frame remote help", "err", err)
		return
	}
	for i := range frames {
		if r, ok := active[frames[i].Id]; ok {
			frames[i].RemoteHelp = &gen.FrameRemoteHelpSummary{EndsAt: r.EndsAt, Reason: r.Reason}
		}
	}
}

// ---------------------------------------------------------------- operator

func (s *Server) OperatorStartRemoteHelp(ctx context.Context, req gen.OperatorStartRemoteHelpRequestObject) (gen.OperatorStartRemoteHelpResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	if err := s.requireRemoteHelp(); err != nil {
		return nil, err
	}
	b := req.Body
	hours := int(b.Hours)
	if hours != 1 && hours != 8 && hours != 24 {
		return nil, problem.Validation("hours", "enum", "hours must be 1, 8 or 24")
	}
	reason := strings.TrimSpace(b.Reason)
	if reason == "" || utf8.RuneCountInString(reason) > 500 {
		return nil, problem.Validation("reason", "length", "give a reason of at most 500 characters")
	}
	f, err := s.st.GetFrame(ctx, s.st.Q(), req.FrameId)
	if err != nil || f.DeletedAt != nil {
		return nil, notFound(store.ErrNotFound, "frame")
	}
	if f.DeviceID == nil {
		return nil, problem.New(problem.RemoteHelpUnsupported, "")
	}
	d, err := s.st.GetDevice(ctx, s.st.Q(), *f.DeviceID)
	if err != nil {
		return nil, err
	}
	if d.RevokedAt != nil || !d.HasFeature(featureRemoteHelp) {
		return nil, problem.New(problem.RemoteHelpUnsupported, "")
	}
	if _, err := s.st.ActiveRemoteHelp(ctx, s.st.Q(), f.ID); err == nil {
		return nil, problem.New(problem.RemoteHelpActive, "")
	} else if err != store.ErrNotFound {
		return nil, err
	}
	now := s.now().UTC().Truncate(time.Second)
	ends := now.Add(time.Duration(hours) * time.Hour)
	op := me(ctx)
	uid, err := s.headscaleUserID(ctx)
	if err != nil {
		s.log.Error("remote help: headscale user", "user", s.cfg.RemoteHelp.HeadscaleUser, "err", err)
		return nil, problem.New(problem.UpstreamUnavailable, "Headscale could not make a key for the frame")
	}
	key, err := s.rh.hs.CreatePreAuthKey(ctx, headscale.CreateKey{User: uid, Ephemeral: true, Expiration: ends, ACLTags: []string{remoteHelpTag}})
	if err != nil {
		s.log.Error("remote help: create pre-auth key", "frame", f.ID, "err", err)
		return nil, problem.New(problem.UpstreamUnavailable, "Headscale could not make a key for the frame")
	}
	sess := store.RemoteHelpSession{
		ID: store.NewID(), FrameID: f.ID, DeviceID: f.DeviceID, FrameName: f.Name, Reason: reason, StartedBy: &op.ID,
		StartedByName: op.DisplayName, StartedAt: now, EndsAt: ends, Hostname: remoteHelpHostname(d),
		AuthKeyEnc: s.rh.sealer.Seal(key.Key), AuthKeyID: ptr(key.ID.String()),
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFrame(ctx, f.ID); err != nil {
			return notFound(err, "frame")
		}
		if err := tx.CreateRemoteHelp(ctx, sess); err != nil {
			if db.IsUniqueViolation(err, "remote_help_sessions_active") {
				return problem.New(problem.RemoteHelpActive, "")
			}
			return err
		}
		return tx.FeedPut(ctx, f.ID, store.FeedRemoteHelp, f.ID, false)
	})
	if err != nil {
		s.expireKey(context.WithoutCancel(ctx), key.ID.String())
		return nil, err
	}
	r, err := s.st.GetRemoteHelp(ctx, s.st.Q(), sess.ID)
	if err != nil {
		return nil, err
	}
	s.announceRemoteHelp(ctx, r, "remote_help_started", nil)
	return created(remoteHelpJSON(r, true)), nil
}

func (s *Server) OperatorStopRemoteHelp(ctx context.Context, req gen.OperatorStopRemoteHelpRequestObject) (gen.OperatorStopRemoteHelpResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	u := me(ctx)
	ended, err := s.endFrameRemoteHelp(ctx, req.FrameId, store.RemoteHelpEnd{By: "operator", ByUser: &u.ID, ByName: &u.DisplayName})
	if err != nil {
		return nil, err
	}
	if !ended {
		return nil, problem.New(problem.NotFound, "no remote help session is active on this frame")
	}
	return noContent(), nil
}

func (s *Server) OperatorListRemoteHelp(ctx context.Context, req gen.OperatorListRemoteHelpRequestObject) (gen.OperatorListRemoteHelpResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	limit := pageLimit(req.Params.Limit)
	var after *store.RemoteHelpCursor
	if req.Params.Cursor != nil && *req.Params.Cursor != "" {
		var c store.RemoteHelpCursor
		b, err := base64.RawURLEncoding.DecodeString(*req.Params.Cursor)
		if err != nil || json.Unmarshal(b, &c) != nil {
			return nil, problem.Validation("cursor", "format", "invalid cursor")
		}
		after = &c
	}
	rows, err := s.st.ListRemoteHelp(ctx, after, limit+1)
	if err != nil {
		return nil, err
	}
	out := gen.RemoteHelpPage{Sessions: []gen.RemoteHelpSession{}}
	if len(rows) > limit {
		rows = rows[:limit]
		last := rows[len(rows)-1]
		b, _ := json.Marshal(store.RemoteHelpCursor{Ended: !last.Active(), At: last.StartedAt, ID: last.ID})
		out.NextCursor = ptr(base64.RawURLEncoding.EncodeToString(b))
	}
	for i := range rows {
		out.Sessions = append(out.Sessions, remoteHelpJSON(&rows[i], true))
	}
	return ok(out), nil
}

// remoteHelpSupported fills OperatorScope.remote_help_supported for found frames.
func (s *Server) remoteHelpSupported(ctx context.Context, frames []gen.OperatorScope) error {
	if len(frames) == 0 {
		return nil
	}
	ids := make([]uuid.UUID, len(frames))
	for i, f := range frames {
		ids[i] = f.Id
	}
	has, err := s.st.FramesWithFeature(ctx, ids, featureRemoteHelp)
	if err != nil {
		return err
	}
	for i := range frames {
		frames[i].RemoteHelpSupported = ptr(has[frames[i].Id])
	}
	return nil
}

// ---------------------------------------------------------------- members

func (s *Server) GetFrameRemoteHelp(ctx context.Context, req gen.GetFrameRemoteHelpRequestObject) (gen.GetFrameRemoteHelpResponseObject, error) {
	if _, err := s.membership(ctx, req.FrameId); err != nil {
		return nil, err
	}
	r, err := s.st.ActiveRemoteHelp(ctx, s.st.Q(), req.FrameId)
	if err == store.ErrNotFound {
		return noContent(), nil
	} else if err != nil {
		return nil, err
	}
	return ok(remoteHelpJSON(r, false)), nil
}

func (s *Server) StopFrameRemoteHelp(ctx context.Context, req gen.StopFrameRemoteHelpRequestObject) (gen.StopFrameRemoteHelpResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requireManager(m); err != nil {
		return nil, err
	}
	u := me(ctx)
	ended, err := s.endFrameRemoteHelp(ctx, req.FrameId, store.RemoteHelpEnd{By: "owner", ByUser: &u.ID, ByName: &u.DisplayName})
	if err != nil {
		return nil, err
	}
	if !ended {
		return nil, problem.New(problem.NotFound, "no remote help session is active on this frame")
	}
	return noContent(), nil
}

// ---------------------------------------------------------------- the frame

func (s *Server) DeviceStopRemoteHelp(ctx context.Context, _ gen.DeviceStopRemoteHelpRequestObject) (gen.DeviceStopRemoteHelpResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	if _, err := s.endFrameRemoteHelp(ctx, f.ID, store.RemoteHelpEnd{By: "frame"}); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// remoteHelpHeartbeat applies Heartbeat.remote_help: `on` (first time: the key is forgotten, the IP stored, the
// operator told), `failed` (the session ends, failed), `off` (the frame cleaned up: it ends as the frame's Stop).
// `starting` and other sessions change nothing.
func (s *Server) remoteHelpHeartbeat(ctx context.Context, frameID uuid.UUID, hb *gen.HeartbeatRemoteHelp) {
	r, err := s.st.ActiveRemoteHelp(ctx, s.st.Q(), frameID)
	if err != nil {
		if err != store.ErrNotFound {
			s.log.Warn("remote help heartbeat", "frame", frameID, "err", err)
		}
		return
	}
	if r.ID != hb.SessionId {
		return
	}
	var ip *string
	if hb.Ip != nil && *hb.Ip != "" && len(*hb.Ip) <= 64 {
		ip = hb.Ip
	}
	switch hb.State {
	case gen.HeartbeatRemoteHelpStateOn:
		if r.State == "on" {
			if ip != nil && (r.TailnetIP == nil || *r.TailnetIP != *ip) {
				if err := s.st.InTx(ctx, func(tx *store.Tx) error { return tx.SetRemoteHelpIP(ctx, r.ID, *ip) }); err != nil {
					s.log.Warn("remote help ip", "err", err)
				} else if r, err = s.st.GetRemoteHelp(ctx, s.st.Q(), r.ID); err == nil {
					s.announceRemoteHelp(ctx, r, "", nil)
				}
			}
			return
		}
		err := s.st.InTx(ctx, func(tx *store.Tx) error {
			if err := tx.RemoteHelpOn(ctx, r.ID, ip, s.now()); err != nil {
				return err
			}
			return tx.FeedPut(ctx, frameID, store.FeedRemoteHelp, frameID, false) // the change without auth_key
		})
		if err != nil {
			s.log.Warn("remote help on", "session", r.ID, "err", err)
			return
		}
		if r, err = s.st.GetRemoteHelp(ctx, s.st.Q(), r.ID); err == nil {
			s.announceRemoteHelp(ctx, r, "remote_help_on", nil)
		}
	case gen.HeartbeatRemoteHelpStateOff:
		// The frame cleaned up while the server still has the session (a Stop whose DELETE never got through).
		if _, err := s.endRemoteHelp(ctx, r, store.RemoteHelpEnd{By: "frame", KeyUsed: r.State == "on"}); err != nil {
			s.log.Warn("remote help off", "session", r.ID, "err", err)
		}
	case gen.HeartbeatRemoteHelpStateFailed:
		msg := "the frame could not join the tailnet"
		if hb.Error != nil && strings.TrimSpace(*hb.Error) != "" {
			msg = truncateRunes(strings.TrimSpace(*hb.Error), 500)
		}
		if _, err := s.endRemoteHelp(ctx, r, store.RemoteHelpEnd{By: "failed", Error: &msg, KeyUsed: true}); err != nil {
			s.log.Warn("remote help failed", "session", r.ID, "err", err)
		}
	}
}

func truncateRunes(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n])
}

// ---------------------------------------------------------------- ending

// endFrameRemoteHelp ends the frame's active session; false when there is none.
func (s *Server) endFrameRemoteHelp(ctx context.Context, frameID uuid.UUID, e store.RemoteHelpEnd) (bool, error) {
	r, err := s.st.ActiveRemoteHelp(ctx, s.st.Q(), frameID)
	if err == store.ErrNotFound {
		return false, nil
	} else if err != nil {
		return false, err
	}
	return s.endRemoteHelp(ctx, r, e)
}

// endRemoteHelp ends a session: it is logged as ended, the frame gets `remote_help: null`, Headscale forgets the
// node and the unused key (errors are logged, not fatal), and members and the operator are told.
func (s *Server) endRemoteHelp(ctx context.Context, r *store.RemoteHelpSession, e store.RemoteHelpEnd) (bool, error) {
	if e.EndedAt.IsZero() {
		e.EndedAt = s.now()
	}
	var ended bool
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		var err error
		if ended, err = tx.EndRemoteHelp(ctx, r.ID, e); err != nil || !ended {
			return err
		}
		var alive bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM frames WHERE id = $1)`, r.FrameID).Scan(&alive); err != nil {
			return err
		}
		if !alive {
			return nil
		}
		return tx.FeedPut(ctx, r.FrameID, store.FeedRemoteHelp, r.FrameID, false)
	})
	if err != nil || !ended {
		return ended, err
	}
	s.cleanUpHeadscale(context.WithoutCancel(ctx), r, e.KeyUsed)
	if done, err := s.st.GetRemoteHelp(ctx, s.st.Q(), r.ID); err == nil {
		s.announceRemoteHelp(ctx, done, "remote_help_ended", e.ByUser)
	}
	return true, nil
}

// cleanUpHeadscale deletes the frame's node and expires the key when the frame never used it.
func (s *Server) cleanUpHeadscale(ctx context.Context, r *store.RemoteHelpSession, used bool) {
	if s.rh == nil || s.rh.hs == nil {
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	var errs []string
	if r.AuthKeyID != nil && !r.KeyUsed && !used && r.State == "pending" {
		if err := s.expireKey(ctx, *r.AuthKeyID); err != nil {
			errs = append(errs, "expire key: "+err.Error())
		}
	}
	if _, err := s.rh.hs.DeleteNodesNamed(ctx, s.cfg.RemoteHelp.HeadscaleUser, r.Hostname); err != nil {
		s.log.Warn("remote help: delete node", "session", r.ID, "hostname", r.Hostname, "err", err)
		errs = append(errs, "delete node: "+err.Error())
	}
	if len(errs) > 0 {
		if err := s.st.SetRemoteHelpError(ctx, r.ID, truncateRunes("Headscale clean-up: "+strings.Join(errs, "; "), 500)); err != nil {
			s.log.Warn("remote help error", "err", err)
		}
	}
}

func (s *Server) expireKey(ctx context.Context, id string) error {
	if s.rh == nil || s.rh.hs == nil {
		return nil
	}
	var n uint64
	if _, err := fmt.Sscan(id, &n); err != nil {
		return err
	}
	err := s.rh.hs.ExpirePreAuthKey(ctx, headscale.ID(n))
	if err != nil {
		s.log.Warn("remote help: expire pre-auth key", "key", id, "err", err)
	}
	return err
}

// ---------------------------------------------------------------- telling people

// announceRemoteHelp sends remote_help.updated to the frame's members (member view) and the operator who started
// it (full view), with the push `key` (empty: none): remote_help_started to members, remote_help_on to the
// operator, remote_help_ended to both; never to `except` (whoever ended it).
func (s *Server) announceRemoteHelp(ctx context.Context, r *store.RemoteHelpSession, key string, except *uuid.UUID) {
	members, err := s.st.MemberIDs(ctx, r.FrameID, false)
	if err != nil {
		s.log.Warn("remote help members", "frame", r.FrameID, "err", err)
	}
	var op uuid.UUID
	if r.StartedBy != nil {
		op = *r.StartedBy
	}
	recipients := appendUnique(members, op)
	if op == uuid.Nil {
		recipients = members
	}
	if e := r.EndedByUser; e != nil && *e != op {
		recipients = appendUnique(recipients, *e) // an operator who stopped someone else's session sees it end
	}
	tzs := s.userTimezones(ctx, recipients, r)
	for _, u := range recipients {
		full := u == op || (r.EndedByUser != nil && u == *r.EndedByUser && r.EndedBy != nil && *r.EndedBy == "operator")
		ev := notify.Event{"type": "remote_help.updated", "frame_id": r.FrameID, "session": remoteHelpJSON(r, full)}
		var push *notify.Push
		switch {
		case key == "" || (except != nil && u == *except):
		case key == "remote_help_started" && u != op:
			push = s.remoteHelpPush(r, key, tzs[u])
		case key == "remote_help_on" && u == op:
			push = s.remoteHelpPush(r, key, tzs[u])
		case key == "remote_help_ended":
			push = s.remoteHelpPush(r, key, tzs[u])
		}
		s.notify.User(u, ev, push)
	}
}

func (s *Server) remoteHelpPush(r *store.RemoteHelpSession, key string, loc *time.Location) *notify.Push {
	return &notify.Push{Key: key, Args: map[string]string{"frame": r.FrameName, "until": r.EndsAt.In(loc).Format("15:04")},
		IDs: map[string]string{"frame_id": r.FrameID.String(), "session_id": r.ID.String()}, Tag: "remote_help:" + r.FrameID.String()}
}

// userTimezones are the recipients' time zones for "until 18:00": their own, else the frame's, else UTC.
func (s *Server) userTimezones(ctx context.Context, users []uuid.UUID, r *store.RemoteHelpSession) map[uuid.UUID]*time.Location {
	def := time.UTC
	if r.DeviceID != nil {
		if d, err := s.st.GetDevice(ctx, s.st.Q(), *r.DeviceID); err == nil && d.Timezone != nil {
			if l, err := time.LoadLocation(*d.Timezone); err == nil {
				def = l
			}
		}
	}
	out := make(map[uuid.UUID]*time.Location, len(users))
	zones, err := s.st.UserTimezones(ctx, users)
	if err != nil {
		s.log.Warn("remote help time zones", "err", err)
	}
	for _, u := range users {
		out[u] = def
		if z, ok := zones[u]; ok {
			if l, err := time.LoadLocation(z); err == nil {
				out[u] = l
			}
		}
	}
	return out
}

// ---------------------------------------------------------------- background

// RunRemoteHelp ends sessions at their end time, pending ones the frame never took up, and those of unpaired frames.
func (s *Server) RunRemoteHelp(ctx context.Context) {
	t := time.NewTicker(remoteHelpTick)
	defer t.Stop()
	last := time.Time{}
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		now := s.now()
		if err := s.RemoteHelpTick(ctx, now); err != nil && ctx.Err() == nil {
			s.log.Error("remote help tick", "err", err)
		}
		if now.Sub(last) > 24*time.Hour {
			last = now
			if _, err := s.st.PruneRemoteHelp(ctx, now.Add(-remoteHelpRetention)); err != nil && ctx.Err() == nil {
				s.log.Warn("remote help prune", "err", err)
			}
		}
	}
}

// RemoteHelpTick runs one round of RunRemoteHelp at now (tests call it with their clock).
func (s *Server) RemoteHelpTick(ctx context.Context, now time.Time) error {
	expired, failed, unpaired, err := s.st.RemoteHelpDue(ctx, now, remoteHelpPending)
	if err != nil {
		return err
	}
	end := func(rows []store.RemoteHelpSession, by string, msg *string) {
		for i := range rows {
			if _, err := s.endRemoteHelp(ctx, &rows[i], store.RemoteHelpEnd{By: by, Error: msg, EndedAt: now}); err != nil {
				s.log.Warn("remote help end", "session", rows[i].ID, "by", by, "err", err)
			}
		}
	}
	end(unpaired, "unpaired", nil)
	end(expired, "expired", nil)
	end(failed, "failed", ptr("the frame did not report within 30 minutes"))
	return nil
}
