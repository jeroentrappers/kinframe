package e2e

import (
	"context"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/jeroentrappers/kinframe/server/internal/config"
)

func TestAuthAndProblems(t *testing.T) {
	e := newEnv(t)
	tests := []struct {
		name   string
		r      req
		status int
		code   string
	}{
		{"no token", req{method: "GET", path: "/v1/me"}, 401, "unauthorized"},
		{"garbage token", req{method: "GET", path: "/v1/me", token: "abc.def.ghi"}, 401, "unauthorized"},
		{"device token on user API", req{method: "GET", path: "/v1/me", token: e.enroll(1280, 800).token}, 401, "unauthorized"},
		{"no device token", req{method: "GET", path: "/v1/device/state"}, 401, "unauthorized"},
		{"unknown endpoint", req{method: "GET", path: "/v1/nope", token: e.user("a@x.org", "A").token}, 404, "not_found"},
		{"wrong device secret", req{method: "POST", path: "/v1/devices/token", body: map[string]any{
			"device_id": "01a16300-0000-7000-8000-000000000000", "device_secret": "x"}}, 401, "unauthorized"},
		{"invalid enroll body", req{method: "POST", path: "/v1/devices/enroll", body: map[string]any{"screen": "big"}}, 422, "validation_failed"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := e.do(tt.r)
			if r.StatusCode != tt.status || r.code() != tt.code {
				t.Fatalf("got %d %s, want %d %s: %s", r.StatusCode, r.code(), tt.status, tt.code, r.body)
			}
			if ct := r.Header.Get("Content-Type"); ct != "application/problem+json" {
				t.Fatalf("content type %q", ct)
			}
		})
	}

	u := e.user("b@x.org", "Bea")
	r := e.do(req{method: "PATCH", path: "/v1/me", token: u.token, body: map[string]any{"display_name": ""}})
	if r.StatusCode != 422 || r.code() != "validation_failed" || !strings.Contains(string(r.body), "display_name") {
		t.Fatalf("empty display name: %d %s", r.StatusCode, r.body)
	}
	me := e.expect(req{method: "PATCH", path: "/v1/me", token: u.token, body: map[string]any{"display_name": "Beatrix", "locale": "nl-BE"}}, 200).m(t)
	if me["display_name"] != "Beatrix" || me["locale"] != "nl-BE" || me["is_operator"] != false {
		t.Fatalf("me: %v", me)
	}

	// Operator endpoints need the kinframe:operate scope.
	if r := e.do(req{method: "GET", path: "/v1/operator/stats", token: u.token}); r.StatusCode != 403 {
		t.Fatalf("operator without scope: %d", r.StatusCode)
	}
	op := e.expect(req{method: "POST", path: "/dev/token", body: map[string]any{"email": "op@x.org", "name": "Op", "operator": true}}, 200).m(t)["access_token"].(string)
	stats := e.get("/v1/operator/stats", op).m(t)
	if stats["users"].(float64) < 2 {
		t.Fatalf("stats: %v", stats)
	}
	lim := e.expect(req{method: "PUT", path: "/v1/operator/limits", token: op, body: map[string]any{
		"max_items_per_send": 2, "max_video_seconds": 60, "max_upload_bytes": 10 << 20}}, 200).m(t)
	if lim["max_items_per_send"] != float64(2) || lim["trash_retention_days"] != float64(30) {
		t.Fatalf("limits: %v", lim)
	}
	if d := e.get("/.well-known/kinframe", "").m(t); dig(d, "limits", "max_items_per_send") != float64(2) {
		t.Fatalf("discovery does not reflect new limits: %v", d)
	}
	// Uploads beyond the limit are refused at creation.
	big := e.do(req{method: "POST", path: "/v1/uploads", token: u.token, header: map[string]string{
		"Tus-Resumable": "1.0.0", "Upload-Length": "20000000", "Upload-Metadata": "filename eA=="}})
	if big.StatusCode != 413 || big.code() != "upload_too_large" {
		t.Fatalf("oversized upload: %d %s", big.StatusCode, big.body)
	}
}

func TestDevAuthRefusedInProduction(t *testing.T) {
	env := map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_ENV": "production",
		"KINFRAME_OIDC_ISSUER": "https://auth/oidc", "KINFRAME_SIGNING_KEY": strings.Repeat("ab", 32)}
	for k, v := range env {
		t.Setenv(k, v)
	}
	if _, err := config.Load(); err == nil || !strings.Contains(err.Error(), "KINFRAME_DEV_AUTH") {
		t.Fatalf("expected refusal, got %v", err)
	}
}

func TestCodesAndJoining(t *testing.T) {
	e := newEnv(t)
	owner := e.user("owner@x.org", "Olga")
	d, frameID := e.claim(owner)

	// A claim invalidates the code, so the frame gets a new one for adding friends.
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: d.token}, 200).m(t)
	if code["claimed"] != true {
		t.Fatalf("code: %v", code)
	}

	// Wrong and malformed codes.
	bob := e.user("bob@x.org", "Bob")
	if r := e.do(req{method: "POST", path: "/v1/frames/redeem", token: bob.token, body: map[string]any{"code": "00000000"}}); r.StatusCode != 404 || r.code() != "code_invalid" {
		t.Fatalf("wrong code: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/frames/redeem", token: bob.token, body: map[string]any{"code": "123"}}); r.StatusCode != 422 {
		t.Fatalf("malformed code: %d %s", r.StatusCode, r.body)
	}

	// Joining an owned frame (approval on the frame off): member with default permissions.
	res := e.expect(req{method: "POST", path: "/v1/frames/redeem", token: bob.token, body: map[string]any{"code": code["code"]}}, 200).m(t)
	if res["status"] != "joined" || dig(res, "frame", "role") != "member" || dig(res, "frame", "permissions", "send") != true || dig(res, "frame", "permissions", "invite") != false {
		t.Fatalf("join: %v", res)
	}
	if again := e.expect(req{method: "POST", path: "/v1/frames/redeem", token: bob.token, body: map[string]any{"code": code["code"]}}, 200).m(t); again["status"] != "already_member" {
		t.Fatalf("rejoin: %v", again)
	}

	// Approval on the frame: pending until the frame decides.
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frameID, token: owner.token, body: map[string]any{"approve_joins_on_frame": true, "name": "Oma's frame", "location": "Living room"}}, 200)
	frameWS := e.dialWS("/v1/device/ws", d.token)
	frameWS.await("hello", 5*time.Second)
	cleo := e.user("cleo@x.org", "Cleo")
	cleoWS := e.dialWS("/v1/ws?access_token="+cleo.token, "")
	pend := e.expect(req{method: "POST", path: "/v1/frames/redeem", token: cleo.token, body: map[string]any{"code": code["code"]}}, 200).m(t)
	if pend["status"] != "pending" || pend["join_request_id"] == nil {
		t.Fatalf("pending: %v", pend)
	}
	jr := frameWS.await("join_request", 5*time.Second)
	if dig(jr, "request", "user", "display_name") != "Cleo" || dig(jr, "request", "via") != "frame_code" {
		t.Fatalf("join_request: %v", jr)
	}
	if r := e.do(req{method: "GET", path: "/v1/frames/" + frameID, token: cleo.token}); r.StatusCode != 404 {
		t.Fatalf("pending user can see frame: %d", r.StatusCode)
	}
	e.expect(req{method: "POST", path: "/v1/device/join-requests/" + pend["join_request_id"].(string), token: d.token, body: map[string]any{"decision": "approve"}}, 204)
	cleoWS.await("membership.updated", 5*time.Second)
	if f := e.get("/v1/frames/"+frameID, cleo.token).m(t); f["role"] != "member" || f["location"] != "Living room" || f["member_count"] != float64(3) {
		t.Fatalf("after approval: %v", f)
	}

	// Rate limit: 10 redemptions per user per hour.
	limited := false
	for i := 0; i < 12; i++ {
		r := e.do(req{method: "POST", path: "/v1/frames/redeem", token: bob.token, body: map[string]any{"code": "99999999"}})
		if r.StatusCode == 429 {
			limited = r.code() == "rate_limited" && r.Header.Get("Retry-After") != ""
			break
		}
	}
	if !limited {
		t.Fatal("redeem was not rate limited")
	}
}

func TestMembersAndOwnership(t *testing.T) {
	e := newEnv(t)
	owner := e.user("o@x.org", "Owen")
	d, frameID := e.claim(owner)
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: d.token}, 200).m(t)["code"]
	ann := e.user("ann@x.org", "Ann")
	ben := e.user("ben@x.org", "Ben")
	for _, u := range []*user{ann, ben} {
		e.expect(req{method: "POST", path: "/v1/frames/redeem", token: u.token, body: map[string]any{"code": code}}, 200)
	}
	base := "/v1/frames/" + frameID

	if r := e.do(req{method: "POST", path: base + "/leave", token: owner.token}); r.StatusCode != 409 || r.code() != "owner_cannot_leave" {
		t.Fatalf("owner leave: %d %s", r.StatusCode, r.body)
	}
	// Members can't manage members.
	if r := e.do(req{method: "PATCH", path: base + "/members/" + ben.id, token: ann.token, body: map[string]any{"role": "admin"}}); r.StatusCode != 403 {
		t.Fatalf("member promoting: %d", r.StatusCode)
	}
	m := e.expect(req{method: "PATCH", path: base + "/members/" + ann.id, token: owner.token, body: map[string]any{"role": "admin"}}, 200).m(t)
	if m["role"] != "admin" || dig(m, "permissions", "change_settings") != true {
		t.Fatalf("promote: %v", m)
	}
	e.expect(req{method: "PATCH", path: base + "/members/" + ben.id, token: ann.token, body: map[string]any{"role": "admin", "permissions": map[string]any{"invite": true}}}, 200)
	if r := e.do(req{method: "PATCH", path: base + "/members/" + ben.id, token: ann.token, body: map[string]any{"role": "member"}}); r.StatusCode != 403 {
		t.Fatalf("admin demoting admin: %d", r.StatusCode)
	}
	if r := e.do(req{method: "DELETE", path: base + "/members/" + owner.id, token: ann.token}); r.StatusCode != 403 {
		t.Fatalf("admin removing owner: %d", r.StatusCode)
	}

	e.expect(req{method: "POST", path: base + "/transfer", token: owner.token, body: map[string]any{"user_id": ann.id}}, 204)
	members := e.get(base+"/members", ben.token)
	var list []map[string]any
	members.json(t, &list)
	roles := map[string]string{}
	for _, mm := range list {
		roles[mm["display_name"].(string)] = mm["role"].(string)
	}
	if roles["Ann"] != "owner" || roles["Owen"] != "admin" || list[0]["display_name"] != "Ann" {
		t.Fatalf("roles after transfer: %v", roles)
	}
	// The frame sees the new owner name in its feed.
	ch := e.get("/v1/device/changes?since=0", d.token).m(t)
	found := false
	for _, c := range ch["changes"].([]any) {
		if c.(map[string]any)["type"] == "frame" && dig(c.(map[string]any), "frame", "owner_name") == "Ann" {
			found = true
		}
	}
	if !found {
		t.Fatalf("frame change with new owner missing: %v", ch)
	}

	// Removal from the frame itself, unless the owner disallowed it.
	e.expect(req{method: "DELETE", path: "/v1/device/members/" + ben.id, token: d.token}, 204)
	if r := e.do(req{method: "GET", path: base, token: ben.token}); r.StatusCode != 404 {
		t.Fatalf("removed member still sees frame: %d", r.StatusCode)
	}
	if r := e.do(req{method: "DELETE", path: "/v1/device/members/" + ann.id, token: d.token}); r.StatusCode != 403 {
		t.Fatalf("frame removing owner: %d", r.StatusCode)
	}
	since := e.get("/v1/device/changes?since=0", d.token).m(t)["next_seq"].(float64)
	e.expect(req{method: "POST", path: base + "/leave", token: owner.token}, 204)
	ch = e.get("/v1/device/changes?since="+ftoa(since), d.token).m(t)
	if c := ch["changes"].([]any); len(c) != 1 || c[0].(map[string]any)["type"] != "member.remove" || c[0].(map[string]any)["id"] != owner.id {
		t.Fatalf("member.remove: %v", ch)
	}
}

func TestInvites(t *testing.T) {
	e := newEnv(t)
	owner := e.user("o@x.org", "Owner")
	_, frameID := e.claim(owner)
	base := "/v1/frames/" + frameID
	inv := e.expect(req{method: "POST", path: base + "/invites", token: owner.token, header: map[string]string{"Idempotency-Key": "k1"},
		body: map[string]any{"max_uses": 1, "note": "for Dirk"}}, 201).m(t)
	tok := inv["token"].(string)
	if inv["url"] != e.url+"/i/"+tok || len(tok) < 22 {
		t.Fatalf("invite: %v", inv)
	}
	if again := e.expect(req{method: "POST", path: base + "/invites", token: owner.token, header: map[string]string{"Idempotency-Key": "k1"}, body: map[string]any{}}, 201).m(t); again["id"] != inv["id"] {
		t.Fatal("idempotent invite created twice")
	}

	dirk := e.user("dirk@x.org", "Dirk")
	if r := e.do(req{method: "GET", path: "/v1/invites/" + strings.Repeat("x", 30), token: dirk.token}); r.StatusCode != 404 || r.code() != "invite_invalid" {
		t.Fatalf("unknown invite: %d %s", r.StatusCode, r.body)
	}
	pv := e.get("/v1/invites/"+tok, dirk.token).m(t)
	if pv["invited_by_name"] != "Owner" || pv["requires_approval"] != true || pv["already_member"] != false {
		t.Fatalf("preview: %v", pv)
	}
	ownerWS := e.dialWS("/v1/ws?access_token="+owner.token, "")
	acc := e.expect(req{method: "POST", path: "/v1/invites/" + tok + "/accept", token: dirk.token}, 200).m(t)
	if acc["status"] != "pending" {
		t.Fatalf("accept: %v", acc)
	}
	ownerWS.await("join_request", 5*time.Second)
	var jrs []map[string]any
	e.get(base+"/join-requests", owner.token).json(t, &jrs)
	if len(jrs) != 1 || jrs[0]["via"] != "invite" || jrs[0]["note"] != "for Dirk" || jrs[0]["invited_by_name"] != "Owner" {
		t.Fatalf("join requests: %v", jrs)
	}
	e.expect(req{method: "POST", path: base + "/join-requests/" + jrs[0]["id"].(string) + "/approve", token: owner.token}, 204)
	if f := e.get(base, dirk.token).m(t); f["role"] != "member" {
		t.Fatalf("dirk: %v", f)
	}
	// Single use: now used up.
	eve := e.user("eve@x.org", "Eve")
	if r := e.do(req{method: "GET", path: "/v1/invites/" + tok, token: eve.token}); r.StatusCode != 410 || r.code() != "invite_expired" {
		t.Fatalf("used invite: %d %s", r.StatusCode, r.body)
	}
	// Members without the invite permission can't create invites; revoked invites stop working.
	if r := e.do(req{method: "POST", path: base + "/invites", token: dirk.token, body: map[string]any{}}); r.StatusCode != 403 || r.code() != "missing_permission" {
		t.Fatalf("member invite: %d %s", r.StatusCode, r.body)
	}
	e.expect(req{method: "PATCH", path: base, token: owner.token, body: map[string]any{"invite_requires_approval": false}}, 200)
	inv2 := e.expect(req{method: "POST", path: base + "/invites", token: owner.token, body: map[string]any{}}, 201).m(t)
	if res := e.expect(req{method: "POST", path: "/v1/invites/" + inv2["token"].(string) + "/accept", token: eve.token}, 200).m(t); res["status"] != "joined" {
		t.Fatalf("auto-accept: %v", res)
	}
	e.expect(req{method: "DELETE", path: base + "/invites/" + inv2["id"].(string), token: owner.token}, 204)
	if r := e.do(req{method: "GET", path: "/v1/invites/" + inv2["token"].(string), token: dirk.token}); r.StatusCode != 410 {
		t.Fatalf("revoked invite: %d", r.StatusCode)
	}
}

func TestSettings(t *testing.T) {
	e := newEnv(t)
	owner := e.user("o@x.org", "O")
	d, frameID := e.claim(owner)
	path := "/v1/frames/" + frameID + "/settings"
	s := e.get(path, owner.token).m(t)
	if s["version"] != float64(1) || dig(s, "slideshow", "interval_s") != float64(30) || dig(s, "locale", "language") != "nl" ||
		dig(s, "display", "brightness") != float64(204) {
		t.Fatalf("defaults: %v", s)
	}
	patch := func(tok, ifMatch string, body map[string]any) *resp {
		return e.do(req{method: "PATCH", path: path, token: tok, body: body, header: map[string]string{"If-Match": ifMatch}})
	}
	r := patch(owner.token, `"1"`, map[string]any{"slideshow": map[string]any{"interval_s": 10}, "location": map[string]any{"name": "Gent", "lat": 51.05, "lon": 3.72}})
	if r.StatusCode != 200 || dig(r.m(t), "slideshow", "interval_s") != float64(10) || dig(r.m(t), "slideshow", "transition") != "crossfade" || r.m(t)["version"] != float64(2) {
		t.Fatalf("patch: %d %s", r.StatusCode, r.body)
	}
	// Stale version, same field: conflict.
	if r := patch(owner.token, `"1"`, map[string]any{"slideshow": map[string]any{"interval_s": 20}}); r.StatusCode != 412 || r.code() != "version_conflict" {
		t.Fatalf("stale same-field: %d %s", r.StatusCode, r.body)
	}
	// Stale version, other field: merged.
	if r := patch(owner.token, `"1"`, map[string]any{"widgets": map[string]any{"clock_24h": false}}); r.StatusCode != 200 || dig(r.m(t), "slideshow", "interval_s") != float64(10) {
		t.Fatalf("stale other-field: %d %s", r.StatusCode, r.body)
	}
	if r := patch(owner.token, `"3"`, map[string]any{"slideshow": map[string]any{"interval_s": 1}}); r.StatusCode != 422 {
		t.Fatalf("out of range: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "PATCH", path: path, token: owner.token, body: map[string]any{}}); r.StatusCode != 400 {
		t.Fatalf("missing If-Match: %d %s", r.StatusCode, r.body)
	}
	// RFC 7396: null removes; unknown fields are preserved.
	r = patch(owner.token, `"3"`, map[string]any{"location": nil, "experimental": map[string]any{"x": 1}})
	if r.StatusCode != 200 || r.m(t)["location"] != nil || dig(r.m(t), "experimental", "x") != float64(1) {
		t.Fatalf("null/unknown: %d %s", r.StatusCode, r.body)
	}
	// The frame changes settings locally with the same semantics, and sees them in its feed.
	dr := e.do(req{method: "PATCH", path: "/v1/device/settings", token: d.token, raw: []byte(`{"display":{"brightness":100}}`),
		header: map[string]string{"If-Match": `"4"`, "Content-Type": "application/merge-patch+json"}})
	if dr.StatusCode != 200 || dig(dr.m(t), "display", "brightness") != float64(100) {
		t.Fatalf("device patch: %d %s", dr.StatusCode, dr.body)
	}
	ch := e.get("/v1/device/changes?since=0", d.token).m(t)
	for _, c := range ch["changes"].([]any) {
		cm := c.(map[string]any)
		if cm["type"] == "settings" && dig(cm, "settings", "version") != float64(5) {
			t.Fatalf("feed settings: %v", cm)
		}
	}
	// Members without change_settings can't read or write settings.
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: d.token}, 200).m(t)["code"]
	mem := e.user("m@x.org", "M")
	e.expect(req{method: "POST", path: "/v1/frames/redeem", token: mem.token, body: map[string]any{"code": code}}, 200)
	if r := e.do(req{method: "GET", path: path, token: mem.token}); r.StatusCode != 403 || r.code() != "missing_permission" {
		t.Fatalf("member settings: %d", r.StatusCode)
	}
}

func TestCommandsAndRevocation(t *testing.T) {
	e := newEnv(t)
	owner := e.user("o@x.org", "O")
	d, frameID := e.claim(owner)
	cmd := func(typ string) *resp {
		return e.do(req{method: "POST", path: "/v1/frames/" + frameID + "/commands", token: owner.token, body: map[string]any{"type": typ}})
	}
	if r := cmd("identify"); r.StatusCode != 409 || r.code() != "frame_offline" {
		t.Fatalf("offline: %d %s", r.StatusCode, r.body)
	}
	ws := e.dialWS("/v1/device/ws", d.token)
	ws.await("hello", 5*time.Second)
	done := make(chan *resp)
	go func() { done <- cmd("sleep") }()
	c := ws.await("command", 5*time.Second)
	if c["command_type"] != "sleep" {
		t.Fatalf("command: %v", c)
	}
	ws.send(map[string]any{"type": "command_result", "id": c["id"], "ok": true})
	if r := <-done; r.StatusCode != 200 || r.m(t)["ok"] != true {
		t.Fatalf("command result: %d %s", r.StatusCode, r.body)
	}
	// No answer: 504 after the (test) timeout of 2 s.
	if r := cmd("next"); r.StatusCode != 504 || r.code() != "command_timeout" {
		t.Fatalf("timeout: %d %s", r.StatusCode, r.body)
	}
	health := e.get("/v1/frames/"+frameID+"/health", owner.token).m(t)
	if health["online"] != true {
		t.Fatalf("health: %v", health)
	}
	e.expect(req{method: "POST", path: "/v1/device/heartbeat", token: d.token, body: map[string]any{"app_version": "0.1.1", "uptime_s": 60, "wifi_rssi_dbm": -60, "last_shutdown_clean": false}}, 204)
	health = e.get("/v1/frames/"+frameID+"/health", owner.token).m(t)
	if dig(health, "last_heartbeat", "wifi_rssi_dbm") != float64(-60) || health["last_unclean_shutdown_at"] == nil {
		t.Fatalf("health after heartbeat: %v", health)
	}

	// Removing the frame revokes the device: websocket closes with 4401, tokens stop working.
	e.expect(req{method: "DELETE", path: "/v1/frames/" + frameID, token: owner.token}, 204)
	ws.await("revoked", 5*time.Second)
	<-ws.done
	if websocket.CloseStatus(ws.err) != 4401 {
		t.Fatalf("close status %v", ws.err)
	}
	if r := e.do(req{method: "GET", path: "/v1/device/state", token: d.token}); r.StatusCode != 401 || r.code() != "device_revoked" {
		t.Fatalf("revoked device: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/devices/token", body: map[string]any{"device_id": d.id, "device_secret": d.secret}}); r.StatusCode != 401 || r.code() != "device_revoked" {
		t.Fatalf("revoked token exchange: %d %s", r.StatusCode, r.body)
	}
	var frames []any
	e.get("/v1/frames", owner.token).json(t, &frames)
	if len(frames) != 0 {
		t.Fatalf("frame still listed")
	}
}

func TestFeedReset(t *testing.T) {
	e := newEnv(t)
	owner := e.user("o@x.org", "O")
	d, frameID := e.claim(owner)
	ch := e.get("/v1/device/changes?since=0", d.token).m(t)
	head := ch["next_seq"].(float64)
	if head < 3 {
		t.Fatalf("expected initial changes, got %v", ch)
	}
	// Ahead of the server (e.g. restored from another server): reset.
	r := e.get("/v1/device/changes?since=999", d.token).m(t)
	if c := r["changes"].([]any); len(c) != 1 || c[0].(map[string]any)["type"] != "reset" || r["next_seq"] != float64(0) {
		t.Fatalf("reset ahead: %v", r)
	}
	// Behind the retention window: reset.
	if _, err := e.app.Pool.Exec(context.Background(), `UPDATE frames SET feed_floor_seq = $2 WHERE id = $1`, frameID, int(head)); err != nil {
		t.Fatal(err)
	}
	r = e.get("/v1/device/changes?since=1", d.token).m(t)
	if c := r["changes"].([]any); len(c) != 1 || c[0].(map[string]any)["type"] != "reset" {
		t.Fatalf("reset behind: %v", r)
	}
	// Paging with limit.
	p := e.get("/v1/device/changes?since=0&limit=1", d.token).m(t)
	if len(p["changes"].([]any)) != 1 || p["has_more"] != true {
		t.Fatalf("paging: %v", p)
	}
	// Albums are versioned and reach the frame.
	base := "/v1/frames/" + frameID + "/albums"
	a := e.expect(req{method: "POST", path: base, token: owner.token, body: map[string]any{"name": "Kerst", "schedule": map[string]any{"from_month_day": "12-01", "to_month_day": "12-31"}}}, 201).m(t)
	if a["version"] != float64(1) || dig(a, "schedule", "from_month_day") != "12-01" {
		t.Fatalf("album: %v", a)
	}
	if r := e.do(req{method: "PATCH", path: base + "/" + a["id"].(string), token: owner.token, body: map[string]any{"name": "X"}, header: map[string]string{"If-Match": `"7"`}}); r.StatusCode != 412 {
		t.Fatalf("album stale: %d", r.StatusCode)
	}
	up := e.expect(req{method: "PATCH", path: base + "/" + a["id"].(string), token: owner.token, body: map[string]any{"name": "Christmas", "schedule": nil}, header: map[string]string{"If-Match": `"1"`}}, 200).m(t)
	if up["name"] != "Christmas" || up["version"] != float64(2) || up["schedule"] != nil {
		t.Fatalf("album update: %v", up)
	}
	next := e.get("/v1/device/changes?since="+ftoa(head), d.token).m(t)
	if c := next["changes"].([]any); len(c) != 1 || c[0].(map[string]any)["type"] != "album.upsert" {
		t.Fatalf("album change: %v", next)
	}
	e.expect(req{method: "DELETE", path: base + "/" + a["id"].(string), token: owner.token}, 204)
	next = e.get("/v1/device/changes?since="+ftoa(head), d.token).m(t)
	if c := next["changes"].([]any); len(c) != 1 || c[0].(map[string]any)["type"] != "album.remove" {
		t.Fatalf("album remove: %v", next)
	}
}

func TestSendValidation(t *testing.T) {
	e := newEnv(t)
	owner := e.user("o@x.org", "O")
	_, frameID := e.claim(owner)
	other := e.user("x@x.org", "X")
	_, otherFrame := e.claim(other)

	txt := e.upload(owner, []byte(strings.Repeat("not a photo\n", 100)), "notes.txt", "text/plain")
	tests := []struct {
		name   string
		body   map[string]any
		status int
		code   string
	}{
		{"unknown upload", map[string]any{"frame_ids": []string{frameID}, "items": []map[string]any{{"upload_id": "nope"}}}, 422, "upload_not_found"},
		{"unsupported type", map[string]any{"frame_ids": []string{frameID}, "items": []map[string]any{{"upload_id": txt}}}, 422, "unsupported_media"},
		{"not my frame", map[string]any{"frame_ids": []string{otherFrame}, "items": []map[string]any{{"upload_id": txt}}}, 404, "not_found"},
		{"caption too long", map[string]any{"frame_ids": []string{frameID}, "items": []map[string]any{{"upload_id": txt, "caption": strings.Repeat("a", 281)}}}, 422, "validation_failed"},
		{"no frames", map[string]any{"frame_ids": []string{}, "items": []map[string]any{{"upload_id": txt}}}, 422, "validation_failed"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := e.do(req{method: "POST", path: "/v1/sends", token: owner.token, body: tt.body})
			if r.StatusCode != tt.status || r.code() != tt.code {
				t.Fatalf("got %d %s: %s", r.StatusCode, r.code(), r.body)
			}
		})
	}
	// All-or-nothing: nothing was created, and the upload is still usable.
	sends := e.get("/v1/sends", owner.token).m(t)
	if len(sends["sends"].([]any)) != 0 {
		t.Fatalf("sends were created: %v", sends)
	}
	// Uploads belong to their uploader.
	if r := e.do(req{method: "HEAD", path: "/v1/uploads/" + txt, token: other.token, header: map[string]string{"Tus-Resumable": "1.0.0"}}); r.StatusCode != 404 {
		t.Fatalf("foreign upload HEAD: %d", r.StatusCode)
	}
	h := e.expect(req{method: "HEAD", path: "/v1/uploads/" + txt, token: owner.token, header: map[string]string{"Tus-Resumable": "1.0.0"}}, 200)
	if h.Header.Get("Upload-Offset") != "1200" || h.Header.Get("Upload-Expires") == "" {
		t.Fatalf("HEAD headers: %v", h.Header)
	}
	opt := e.do(req{method: "OPTIONS", path: "/v1/uploads", token: owner.token})
	if !strings.Contains(opt.Header.Get("Tus-Extension"), "expiration") || !strings.Contains(opt.Header.Get("Tus-Extension"), "termination") {
		t.Fatalf("Tus-Extension: %q", opt.Header.Get("Tus-Extension"))
	}
	e.expect(req{method: "DELETE", path: "/v1/uploads/" + txt, token: owner.token, header: map[string]string{"Tus-Resumable": "1.0.0"}}, 204)
}

func TestSPA(t *testing.T) {
	web := t.TempDir()
	_ = os.WriteFile(filepath.Join(web, "index.html"), []byte("<!doctype html><title>kinframe</title>"), 0o644)
	_ = os.MkdirAll(filepath.Join(web, "_app", "immutable"), 0o755)
	_ = os.WriteFile(filepath.Join(web, "_app", "immutable", "app.js"), []byte("console.log(1)"), 0o644)
	e := newEnv(t, func(c *config.Config) { c.WebDir = web })
	for _, p := range []string{"/", "/f/12345678", "/i/abcdefghijklmnopqrstuvwxyz", "/frames/x/settings"} {
		r := e.get(p, "")
		if !strings.Contains(string(r.body), "<title>kinframe</title>") {
			t.Fatalf("%s: %s", p, r.body)
		}
	}
	js := e.get("/_app/immutable/app.js", "")
	if string(js.body) != "console.log(1)" || !strings.Contains(js.Header.Get("Cache-Control"), "immutable") {
		t.Fatalf("asset: %s %v", js.body, js.Header)
	}
	if r := e.do(req{method: "GET", path: "/v1/unknown"}); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("API paths must not fall back to the SPA: %d", r.StatusCode)
	}
}

func TestReplaceDevice(t *testing.T) {
	e := newEnv(t)
	owner := e.user("o@x.org", "O")
	old, frameID := e.claim(owner)
	oldWS := e.dialWS("/v1/device/ws", old.token)
	oldWS.await("hello", 5*time.Second)
	fresh := e.enroll(1920, 1200)
	newWS := e.dialWS("/v1/device/ws", fresh.token)
	newWS.await("hello", 5*time.Second)
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: fresh.token}, 200).m(t)["code"]
	f := e.expect(req{method: "POST", path: "/v1/frames/" + frameID + "/replace-device", token: owner.token, body: map[string]any{"code": code}}, 200).m(t)
	if dig(f, "screen", "width") != float64(1920) {
		t.Fatalf("frame after replace: %v", f)
	}
	oldWS.await("revoked", 5*time.Second)
	newWS.await("claimed", 5*time.Second)
	st := e.get("/v1/device/state", fresh.token).m(t)
	if st["claimed"] != true || st["frame_id"] != frameID {
		t.Fatalf("new device state: %v", st)
	}
	if ch := e.get("/v1/device/changes?since=0", fresh.token).m(t); len(changesOf(ch, "settings")) != 1 {
		t.Fatalf("new device snapshot: %v", ch)
	}
	if r := e.do(req{method: "GET", path: "/v1/device/state", token: old.token}); r.code() != "device_revoked" {
		t.Fatalf("old device: %s", r.body)
	}
}

func TestAppleAppSiteAssociation(t *testing.T) {
	e := newEnv(t, func(c *config.Config) { c.AppleAppIDs = []string{"TEAM.app.kinframe.family"} })
	r := e.do(req{method: "GET", path: "/.well-known/apple-app-site-association"})
	if r.StatusCode != 200 || r.Header.Get("Content-Type") != "application/json" {
		t.Fatalf("aasa: %d %q %s", r.StatusCode, r.Header.Get("Content-Type"), r.body)
	}
	if ids := dig(r.m(t), "applinks", "details"); ids == nil {
		t.Fatalf("aasa body: %s", r.body)
	}

	// Without app ids the file is not served (the SPA fallback answers 404 without a web dir).
	off := newEnv(t)
	if r := off.do(req{method: "GET", path: "/.well-known/apple-app-site-association"}); r.StatusCode != 404 {
		t.Fatalf("aasa disabled: %d %s", r.StatusCode, r.body)
	}
}
