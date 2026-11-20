package e2e

// Remote help (docs/spec/remote-help.md): the operator starts a session, the frame gets the key on its change
// feed and reports on, members see and stop it, the frame stops it, the worker ends what is due. Headscale is a
// fake httptest server.

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
)

type fakeHeadscale struct {
	*httptest.Server
	mu    sync.Mutex
	calls []string // "METHOD path?query body"
	keys  int
	host  string // the frame's node name, once the test knows it
}

func newFakeHeadscale(t *testing.T) *fakeHeadscale {
	f := &fakeHeadscale{}
	f.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer hs-api-key" {
			w.WriteHeader(401)
			_, _ = io.WriteString(w, `{"code":16,"message":"Unauthorized"}`)
			return
		}
		b, _ := io.ReadAll(r.Body)
		f.mu.Lock()
		line := r.Method + " " + r.URL.Path
		if r.URL.RawQuery != "" {
			line += "?" + r.URL.RawQuery
		}
		if len(b) > 0 {
			line += " " + string(b)
		}
		f.calls = append(f.calls, line)
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == "GET" && r.URL.Path == "/api/v1/user":
			_, _ = io.WriteString(w, `{"users":[{"id":"7","name":"kinframe-frames"}]}`)
		case r.Method == "POST" && r.URL.Path == "/api/v1/preauthkey":
			f.keys++
			fmt.Fprintf(w, `{"preAuthKey":{"user":{"id":"7","name":"kinframe-frames"},"id":"%d","key":"hskey-auth-%d","reusable":false,"ephemeral":true,"used":false,"aclTags":["tag:kinframe-frame"]}}`, 40+f.keys, f.keys)
		case r.Method == "POST" && r.URL.Path == "/api/v1/preauthkey/expire":
			_, _ = io.WriteString(w, `{}`)
		case r.Method == "GET" && r.URL.Path == "/api/v1/node":
			_, _ = io.WriteString(w, `{"nodes":[{"id":"1","name":"laptop","givenName":"laptop"},{"id":"9","name":"`+f.host+`","givenName":"`+f.host+`","ipAddresses":["100.64.0.9"]}]}`)
		case r.Method == "DELETE" && strings.HasPrefix(r.URL.Path, "/api/v1/node/"):
			_, _ = io.WriteString(w, `{}`)
		default:
			w.WriteHeader(404)
		}
		f.mu.Unlock()
	}))
	t.Cleanup(f.Close)
	return f
}

func (f *fakeHeadscale) take() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := f.calls
	f.calls = nil
	return out
}

func hsCalled(calls []string, prefix string) bool {
	for _, c := range calls {
		if strings.HasPrefix(c, prefix) {
			return true
		}
	}
	return false
}

func withRemoteHelp(url string) option {
	return func(c *config.Config) {
		c.RemoteHelp = config.RemoteHelpConfig{Enabled: true, HeadscaleURL: url, HeadscaleAPIKey: "hs-api-key", HeadscaleUser: "kinframe-frames",
			ADBKey: "QAAAAadbkey operator@laptop", PolicyChecked: true, TailscaleVersion: "1.104.7",
			TailscaleSHA256: map[string]string{"arm": strings.Repeat("a", 64), "arm64": strings.Repeat("b", 64)}}
	}
}

func TestRemoteHelp(t *testing.T) {
	hs := newFakeHeadscale(t)
	e := newEnv(t, withRemoteHelp(hs.URL))
	ben, anna := e.user("ben@x.org", "Ben"), e.user("anna@x.org", "Anna")
	d, frame := e.claim(ben)
	e.join(anna, d)
	op := e.operator()
	start := func(token string, hours int) *resp {
		return e.do(req{method: "POST", path: "/v1/operator/frames/" + frame + "/remote-help", token: token, body: map[string]any{"hours": hours, "reason": "Ben asked by mail: no new photos"}})
	}
	heartbeat := func(extra map[string]any) {
		body := map[string]any{"app_version": "0.1.1", "uptime_s": 60, "features": []string{"feed_backfill", "remote_help"}}
		for k, v := range extra {
			body[k] = v
		}
		e.expect(req{method: "POST", path: "/v1/device/heartbeat", token: d.token, body: body}, 204)
	}

	if dig(e.get("/.well-known/kinframe", "").m(t), "remote_help") != true || e.get("/v1/me", op).m(t)["remote_help"] != true {
		t.Fatal("discovery or me without remote_help")
	}
	if _, ok := e.get("/v1/me", ben.token).m(t)["remote_help"]; ok {
		t.Fatal("remote_help in a non-operator's me")
	}
	if r := start(ben.token, 8); r.StatusCode != 403 {
		t.Fatalf("non-operator start: %d", r.StatusCode)
	}
	found := e.get("/v1/operator/ownership/search?q=ben@x", op).m(t)["frames"].([]any)
	if len(found) != 1 || dig(found[0].(map[string]any), "remote_help_supported") != false {
		t.Fatalf("search before the feature: %v", found)
	}
	if r := start(op, 8); r.StatusCode != 409 || r.code() != "remote_help_unsupported" {
		t.Fatalf("frame without the feature: %d %s", r.StatusCode, r.body)
	}
	heartbeat(nil)
	found = e.get("/v1/operator/ownership/search?q=ben@x", op).m(t)["frames"].([]any)
	if dig(found[0].(map[string]any), "remote_help_supported") != true {
		t.Fatalf("search after the feature: %v", found)
	}
	if r := start(op, 3); r.StatusCode != 422 {
		t.Fatalf("3 hours: %d", r.StatusCode)
	}
	base := e.get("/v1/device/changes?since=0", d.token).m(t)
	if len(changesOf(base, "remote_help")) != 0 {
		t.Fatalf("remote_help in a snapshot without a session: %v", base)
	}
	benWS := e.dialWS("/v1/ws?access_token="+ben.token, "")
	hs.take()

	// Start.
	before := time.Now()
	r := start(op, 8)
	if r.StatusCode != 201 {
		t.Fatalf("start: %d %s", r.StatusCode, r.body)
	}
	s := r.m(t)
	host := s["hostname"].(string)
	hs.mu.Lock()
	hs.host = host
	hs.mu.Unlock()
	if s["state"] != "pending" || !strings.HasPrefix(host, "kinframe-") || s["adb_command"] != "adb connect "+host+":5555" ||
		s["started_by_name"] != "Op" || s["frame_name"] == nil {
		t.Fatalf("session %v", s)
	}
	if ends, _ := time.Parse(time.RFC3339, s["ends_at"].(string)); ends.Sub(before) < 8*time.Hour-time.Minute || ends.Sub(before) > 8*time.Hour+time.Minute {
		t.Fatalf("ends_at %v", s["ends_at"])
	}
	calls := hs.take()
	if !hsCalled(calls, "GET /api/v1/user?name=kinframe-frames") {
		t.Fatalf("user lookup: %v", calls)
	}
	var create map[string]any
	for _, c := range calls {
		if strings.HasPrefix(c, "POST /api/v1/preauthkey ") {
			_ = json.Unmarshal([]byte(strings.TrimPrefix(c, "POST /api/v1/preauthkey ")), &create)
		}
	}
	if create["user"] != "7" || create["ephemeral"] != true || create["reusable"] != false || fmt.Sprint(create["aclTags"]) != "[tag:kinframe-frame]" {
		t.Fatalf("create key: %v", calls)
	}
	ev := benWS.await("remote_help.updated", 5*time.Second)
	if dig(ev, "session", "state") != "pending" || dig(ev, "session", "tailnet_ip") != nil {
		t.Fatalf("event %v", ev)
	}
	if r := start(op, 1); r.StatusCode != 409 || r.code() != "remote_help_active" {
		t.Fatalf("second start: %d %s", r.StatusCode, r.body)
	}

	// The frame gets the key.
	ch := e.get(fmt.Sprintf("/v1/device/changes?since=%v", base["next_seq"]), d.token).m(t)
	rc := changesOf(ch, "remote_help")
	if len(rc) != 1 || rc[0]["id"] != frame || dig(rc[0], "remote_help", "auth_key") != "hskey-auth-1" ||
		dig(rc[0], "remote_help", "login_server") != hs.URL || dig(rc[0], "remote_help", "state") != "active" ||
		dig(rc[0], "remote_help", "hostname") != host || dig(rc[0], "remote_help", "adb_key") != "QAAAAadbkey operator@laptop" ||
		dig(rc[0], "remote_help", "session_id") != s["id"] || dig(rc[0], "remote_help", "tailscale", "arm64", "sha256") != strings.Repeat("b", 64) ||
		dig(rc[0], "remote_help", "tailscale", "arm", "url") != "https://pkgs.tailscale.com/stable/tailscale_1.104.7_arm.tgz" {
		t.Fatalf("remote_help change %v", rc)
	}
	if dig(snap0(e, d), "remote_help", "auth_key") != "hskey-auth-1" {
		t.Fatal("snapshot before on without the key")
	}

	// Members see it.
	m := e.get("/v1/frames/"+frame+"/remote-help", anna.token).m(t)
	if m["id"] != s["id"] || m["reason"] != "Ben asked by mail: no new photos" {
		t.Fatalf("member view %v", m)
	}
	if _, ok := m["tailnet_ip"]; ok {
		if m["tailnet_ip"] != nil {
			t.Fatalf("member sees the ip %v", m)
		}
	}
	if f := e.get("/v1/frames/"+frame, anna.token).m(t); dig(f, "remote_help", "reason") != "Ben asked by mail: no new photos" {
		t.Fatalf("frame %v", f["remote_help"])
	}
	if fs := e.get("/v1/frames", ben.token).body; !strings.Contains(string(fs), `"remote_help":{`) {
		t.Fatalf("frame list without remote_help: %s", fs)
	}

	// The frame reports on: the key leaves the change, the IP is stored.
	heartbeat(map[string]any{"remote_help": map[string]any{"session_id": s["id"], "state": "starting"}})
	heartbeat(map[string]any{"remote_help": map[string]any{"session_id": s["id"], "state": "on", "ip": "100.64.0.9"}})
	ch2 := e.get(fmt.Sprintf("/v1/device/changes?since=%v", ch["next_seq"]), d.token).m(t)
	rc = changesOf(ch2, "remote_help")
	if len(rc) != 1 || dig(rc[0], "remote_help", "session_id") != s["id"] {
		t.Fatalf("change after on %v", ch2)
	}
	if _, ok := rc[0]["remote_help"].(map[string]any)["auth_key"]; ok {
		t.Fatalf("auth_key after on: %v", rc[0])
	}
	list := e.get("/v1/operator/remote-help", op).m(t)["sessions"].([]any)
	if len(list) != 1 || dig(list[0].(map[string]any), "state") != "on" || dig(list[0].(map[string]any), "tailnet_ip") != "100.64.0.9" {
		t.Fatalf("operator list %v", list)
	}
	benWS.awaitWhere("remote_help.updated", 5*time.Second, func(m map[string]any) bool { return dig(m, "session", "state") == "on" })
	// A reset frame (snapshot) still gets the session, without the used key.
	if snap := snap0(e, d); dig(snap, "remote_help", "session_id") != s["id"] || dig(snap, "remote_help", "auth_key") != nil {
		t.Fatalf("snapshot after on %v", snap)
	}

	// Only owner/admins stop it.
	if r := e.do(req{method: "DELETE", path: "/v1/frames/" + frame + "/remote-help", token: anna.token}); r.StatusCode != 403 {
		t.Fatalf("member stop: %d", r.StatusCode)
	}
	annaWS := e.dialWS("/v1/ws?access_token="+anna.token, "")
	hs.take()
	e.expect(req{method: "DELETE", path: "/v1/frames/" + frame + "/remote-help", token: ben.token}, 204)
	calls = hs.take()
	if !hsCalled(calls, "GET /api/v1/node?user=kinframe-frames") || !hsCalled(calls, "DELETE /api/v1/node/9") || hsCalled(calls, "DELETE /api/v1/node/1") || hsCalled(calls, "POST /api/v1/preauthkey/expire") {
		t.Fatalf("headscale after stop: %v", calls)
	}
	ev = annaWS.awaitWhere("remote_help.updated", 5*time.Second, func(m map[string]any) bool { return dig(m, "session", "state") == "ended" })
	if dig(ev, "session", "ended_by") != "owner" || dig(ev, "session", "ended_by_name") != "Ben" {
		t.Fatalf("ended event %v", ev)
	}
	ch3 := e.get(fmt.Sprintf("/v1/device/changes?since=%v", ch2["next_seq"]), d.token).m(t)
	if rc = changesOf(ch3, "remote_help"); len(rc) != 1 || rc[0]["remote_help"] != nil {
		t.Fatalf("change after stop %v", ch3)
	}
	if r := e.do(req{method: "GET", path: "/v1/frames/" + frame + "/remote-help", token: anna.token}); r.StatusCode != 204 {
		t.Fatalf("member get after stop: %d", r.StatusCode)
	}
	if snap := changesOf(e.get("/v1/device/changes?since=0", d.token).m(t), "remote_help"); len(snap) != 0 {
		t.Fatalf("snapshot after stop %v", snap)
	}
	if r := e.do(req{method: "DELETE", path: "/v1/frames/" + frame + "/remote-help", token: ben.token}); r.StatusCode != 404 {
		t.Fatalf("stop twice: %d", r.StatusCode)
	}

	// The frame stops one it never joined: the unused key is expired.
	s2 := start(op, 1).m(t)
	hs.take()
	e.expect(req{method: "DELETE", path: "/v1/device/remote-help", token: d.token}, 204)
	calls = hs.take()
	if !hsCalled(calls, `POST /api/v1/preauthkey/expire {"id":"42"}`) || !hsCalled(calls, "DELETE /api/v1/node/9") {
		t.Fatalf("headscale after the frame's stop: %v", calls)
	}
	e.expect(req{method: "DELETE", path: "/v1/device/remote-help", token: d.token}, 204) // idempotent

	// A heartbeat saying off ends it too (the frame's DELETE never got through).
	s2b := start(op, 1).m(t)
	heartbeat(map[string]any{"remote_help": map[string]any{"session_id": s2b["id"], "state": "off"}})
	if r := e.do(req{method: "GET", path: "/v1/frames/" + frame + "/remote-help", token: anna.token}); r.StatusCode != 204 {
		t.Fatalf("after off: %d", r.StatusCode)
	}

	// A failed join ends it.
	s3 := start(op, 1).m(t)
	heartbeat(map[string]any{"remote_help": map[string]any{"session_id": s3["id"], "state": "failed", "error": "no DNS fallback candidates remain"}})
	if r := e.do(req{method: "GET", path: "/v1/frames/" + frame + "/remote-help", token: anna.token}); r.StatusCode != 204 {
		t.Fatalf("after failed: %d", r.StatusCode)
	}

	// The worker: the pending timeout, then the end time.
	ctx := context.Background()
	s4 := start(op, 1).m(t)
	if err := e.app.API.RemoteHelpTick(ctx, time.Now().Add(20*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if r := e.do(req{method: "GET", path: "/v1/frames/" + frame + "/remote-help", token: anna.token}); r.StatusCode != 200 {
		t.Fatalf("ended too early: %d", r.StatusCode)
	}
	if err := e.app.API.RemoteHelpTick(ctx, time.Now().Add(31*time.Minute)); err != nil {
		t.Fatal(err)
	}
	s5 := start(op, 8).m(t)
	heartbeat(map[string]any{"remote_help": map[string]any{"session_id": s5["id"], "state": "on", "ip": "100.64.0.10"}})
	if err := e.app.API.RemoteHelpTick(ctx, time.Now().Add(7*time.Hour)); err != nil {
		t.Fatal(err)
	}
	if r := e.do(req{method: "GET", path: "/v1/frames/" + frame + "/remote-help", token: anna.token}); r.StatusCode != 200 {
		t.Fatalf("an on session ended before its time: %d", r.StatusCode)
	}
	if err := e.app.API.RemoteHelpTick(ctx, time.Now().Add(8*time.Hour+time.Minute)); err != nil {
		t.Fatal(err)
	}

	// The log, newest first, paged.
	by := map[string]string{}
	var cursor string
	for page := 0; page < 10; page++ {
		path := "/v1/operator/remote-help?limit=2"
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		p := e.get(path, op).m(t)
		for _, x := range p["sessions"].([]any) {
			sx := x.(map[string]any)
			by[sx["id"].(string)] = fmt.Sprint(sx["ended_by"])
			if sx["state"] != "ended" {
				t.Fatalf("still active: %v", sx)
			}
		}
		if p["next_cursor"] == nil {
			break
		}
		cursor = p["next_cursor"].(string)
	}
	want := map[string]string{s["id"].(string): "owner", s2["id"].(string): "frame", s2b["id"].(string): "frame", s3["id"].(string): "failed", s4["id"].(string): "failed", s5["id"].(string): "expired"}
	for id, w := range want {
		if by[id] != w {
			t.Errorf("session %s ended_by %q, want %q (log %v)", id, by[id], w, by)
		}
	}
	if len(by) != 6 {
		t.Fatalf("log has %d sessions", len(by))
	}

	// Unpairing the frame ends a session.
	start(op, 1)
	e.expect(req{method: "DELETE", path: "/v1/frames/" + frame, token: ben.token}, 204)
	if err := e.app.API.RemoteHelpTick(ctx, time.Now()); err != nil {
		t.Fatal(err)
	}
	first := e.get("/v1/operator/remote-help?limit=1", op).m(t)["sessions"].([]any)[0].(map[string]any)
	if first["ended_by"] != "unpaired" {
		t.Fatalf("after unpairing: %v", first)
	}
}

// Without the configuration the endpoints answer 503 and discovery says nothing.
func TestRemoteHelpDisabled(t *testing.T) {
	e := newEnv(t)
	ben := e.user("ben@x.org", "Ben")
	_, frame := e.claim(ben)
	op := e.operator()
	if dig(e.get("/.well-known/kinframe", "").m(t), "remote_help") != false || e.get("/v1/me", op).m(t)["remote_help"] != false {
		t.Fatal("remote_help on without configuration")
	}
	r := e.do(req{method: "POST", path: "/v1/operator/frames/" + frame + "/remote-help", token: op, body: map[string]any{"hours": 8, "reason": "x"}})
	if r.StatusCode != 503 || r.code() != "feature_disabled" {
		t.Fatalf("start: %d %s", r.StatusCode, r.body)
	}
}

func snap0(e *env, d *device) map[string]any {
	e.t.Helper()
	c := changesOf(e.get("/v1/device/changes?since=0", d.token).m(e.t), "remote_help")
	if len(c) != 1 {
		e.t.Fatalf("snapshot %v", c)
	}
	return c[0]
}
