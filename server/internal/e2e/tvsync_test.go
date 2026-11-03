package e2e

import (
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

// nowShowing is the frame's `now_showing` websocket message (README §6.1).
func nowShowing(ids []string, next, screen string, startedAgo time.Duration) map[string]any {
	now := time.Now().UTC()
	m := map[string]any{
		"type": "now_showing", "placement_ids": ids, "transition": "crossfade", "transition_ms": 800,
		"direction": 1, "ken_burns": true, "hold_ms": 30000, "screen": screen,
		"started_at": now.Add(-startedAgo).Format(time.RFC3339Nano), "sent_at": now.Format(time.RFC3339Nano),
	}
	if len(ids) > 0 {
		m["placement_id"] = ids[0]
	}
	if next != "" {
		m["next_placement_id"] = next
	}
	return m
}

// TestTvMirror: a mirror TV link follows what the frame reports, live, within the link's permissions.
func TestTvMirror(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	owner := e.user("mirror-owner@x.org", "Olga")
	d, frameID := e.claim(owner)
	for i, caption := range []string{"first", "second"} {
		up := e.upload(owner, mediatest.JPEG(640+i, 480, 1, "", false), caption+".jpg", "image/jpeg")
		e.waitSend(owner, e.sendOne(owner, []string{frameID}, map[string]any{"upload_id": up, "caption": caption}))
	}
	lib := e.get("/v1/frames/"+frameID+"/placements?sort=received_asc", owner.token).m(t)
	var p []string
	for _, x := range lib["placements"].([]any) {
		p = append(p, x.(map[string]any)["id"].(string))
	}

	// A mirror link has no scope of its own and points the TV page at mirror mode.
	if r := e.do(req{method: "POST", path: "/v1/frames/" + frameID + "/tv-links", token: owner.token,
		body: map[string]any{"purpose": "mirror", "placement_ids": []string{p[0]}}}); r.StatusCode != 422 {
		t.Fatalf("scoped mirror link: %d %s", r.StatusCode, r.body)
	}
	link := e.expect(req{method: "POST", path: "/v1/frames/" + frameID + "/tv-links", token: owner.token,
		body: map[string]any{"purpose": "mirror"}}, 201).m(t)
	if link["purpose"] != "mirror" || !strings.HasSuffix(link["url"].(string), "&mode=mirror") {
		t.Fatalf("mirror link: %v", link)
	}
	token := link["token"].(string)

	// Only someone who may see the whole library can create one.
	member := e.user("mirror-member@x.org", "Mo")
	e.join(member, d)
	e.setPerms(owner, frameID, member, map[string]any{"see_library": false})
	if r := e.do(req{method: "POST", path: "/v1/frames/" + frameID + "/tv-links", token: member.token,
		body: map[string]any{"purpose": "mirror"}}); r.StatusCode != 403 || r.code() != "missing_permission" {
		t.Fatalf("member mirror link: %d %s", r.StatusCode, r.body)
	}
	// Ordinary links are not mirror links.
	tvLink := e.expect(req{method: "POST", path: "/v1/frames/" + frameID + "/tv-links", token: owner.token, body: map[string]any{}}, 201).m(t)
	if r := e.do(req{method: "GET", path: "/v1/tv/" + tvLink["token"].(string) + "/mirror"}); r.StatusCode != 404 {
		t.Fatalf("tv link on mirror endpoint: %d", r.StatusCode)
	}

	// The frame is online but has not reported yet.
	frame := e.dialWS("/v1/device/ws", d.token)
	frame.await("hello", 5*time.Second)
	tv := e.dialWS("/v1/tv/"+token+"/mirror", "")
	first := tv.await("mirror", 5*time.Second)
	if first["online"] != true || first["showing"] != nil || first["frame_name"] == "" || first["expires_at"] == nil {
		t.Fatalf("first mirror state: %v", first)
	}
	if s, _ := first["settings"].(map[string]any); s == nil || s["interval_s"] == nil || s["transition"] == nil {
		t.Fatalf("mirror settings: %v", first["settings"])
	}

	// A slide change fans out with signed media, the upcoming item, and times in server time.
	frame.send(nowShowing([]string{p[1]}, p[0], "on", 2*time.Second))
	st := tv.awaitWhere("mirror", 5*time.Second, func(m map[string]any) bool { return m["showing"] != nil })
	sh := st["showing"].(map[string]any)
	slides := sh["slides"].([]any)
	if sh["placement_id"] != p[1] || len(slides) != 1 || sh["ken_burns"] != true || sh["transition"] != "crossfade" {
		t.Fatalf("showing: %v", sh)
	}
	slide := slides[0].(map[string]any)
	if slide["caption"] != "second" || !strings.HasPrefix(slide["url"].(string), e.url+"/v1/media/signed/") {
		t.Fatalf("slide: %v", slide)
	}
	e.get(slide["url"].(string), "")
	// A TV tuning in later gets the current slide right away.
	late := e.dialWS("/v1/tv/"+token+"/mirror", "")
	if m := late.await("mirror", 5*time.Second); m["showing"] == nil {
		t.Fatalf("late viewer: %v", m)
	}
	_ = late.c.CloseNow()
	if n, _ := st["next"].(map[string]any); n == nil || n["placement_id"] != p[0] {
		t.Fatalf("next: %v", st["next"])
	}
	started, _ := time.Parse(time.RFC3339Nano, sh["started_at"].(string))
	server, _ := time.Parse(time.RFC3339Nano, st["server_time"].(string))
	if age := server.Sub(started); age < 1500*time.Millisecond || age > 10*time.Second {
		t.Fatalf("started %v before server_time", age)
	}

	// Sleeping, and an item the frame may not show any more (hidden meanwhile).
	frame.send(nowShowing([]string{p[1]}, "", "sleeping", 0))
	tv.awaitWhere("mirror", 5*time.Second, func(m map[string]any) bool { return m["screen"] == "sleeping" })
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frameID + "/placements/" + p[0], token: owner.token, body: map[string]any{"hidden": true}}, 200)
	frame.send(nowShowing([]string{p[0]}, "", "on", 0))
	tv.awaitWhere("mirror", 5*time.Second, func(m map[string]any) bool { return m["screen"] == "on" && m["showing"] == nil })

	// A frame going offline shows as offline.
	_ = frame.c.CloseNow()
	tv.awaitWhere("mirror", 5*time.Second, func(m map[string]any) bool { return m["online"] == false })

	// Viewers per frame are capped.
	for i := 0; i < 7; i++ {
		e.dialWS("/v1/tv/"+token+"/mirror", "").await("mirror", 5*time.Second)
	}
	if r := e.do(req{method: "GET", path: "/v1/tv/" + token + "/mirror"}); r.StatusCode != 429 {
		t.Fatalf("ninth viewer: %d %s", r.StatusCode, r.body)
	}

	// Revoking ends the mirror right away.
	e.expect(req{method: "DELETE", path: "/v1/frames/" + frameID + "/tv-links/" + link["id"].(string), token: owner.token}, 204)
	if m := tv.await("ended", 5*time.Second); m["reason"] != "revoked" {
		t.Fatalf("ended: %v", m)
	}
	if r := e.do(req{method: "GET", path: "/v1/tv/" + token + "/mirror"}); r.StatusCode != 404 {
		t.Fatalf("revoked mirror: %d", r.StatusCode)
	}
}

// enrollBrowser enrolls a web browser as a virtual frame (device kind "browser").
func (e *env) enrollBrowser() *device {
	e.t.Helper()
	res := e.expect(req{method: "POST", path: "/v1/devices/enroll", body: map[string]any{
		"kind":     "browser",
		"hardware": map[string]any{"brand": "web", "model": "Chrome on Linux"},
		"screen":   map[string]any{"width": 1920, "height": 1080, "orientation": "landscape"},
		"app":      map[string]any{"version": "0.1.0-web", "version_code": 0},
		"locale":   "en", "timezone": "Europe/Brussels",
	}}, 201).m(e.t)
	d := &device{id: res["device_id"].(string), secret: res["device_secret"].(string)}
	tok := e.expect(req{method: "POST", path: "/v1/devices/token", body: map[string]any{"device_id": d.id, "device_secret": d.secret}}, 200).m(e.t)
	d.token = tok["access_token"].(string)
	return d
}

// TestBrowserFrame: a browser enrolls, is claimed like a frame, syncs with its token in the query
// where it cannot set headers, never self-updates, and is removed like any frame.
func TestBrowserFrame(t *testing.T) {
	requireMedia(t)
	e := newEnv(t, func(c *config.Config) { c.MinFrameAppVersion = 2 })
	owner := e.user("browser-owner@x.org", "Bea")
	b := e.enrollBrowser()
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: b.token}, 200).m(t)["code"].(string)
	res := e.expect(req{method: "POST", path: "/v1/frames/redeem", token: owner.token, body: map[string]any{"code": code}}, 200).m(t)
	if res["status"] != "claimed" {
		t.Fatalf("redeem: %v", res)
	}
	frameID := res["frame"].(map[string]any)["id"].(string)

	var frames []map[string]any
	e.get("/v1/frames", owner.token).json(t, &frames)
	if len(frames) != 1 || frames[0]["device_kind"] != "browser" || frames[0]["needs_update"] != false {
		t.Fatalf("frames: %v", frames)
	}
	// An Android frame below the minimum version is told to update; a browser frame never is.
	a := e.enroll(1280, 800)
	if r := e.do(req{method: "GET", path: "/v1/device/changes?since=0", token: a.token}); r.StatusCode != 426 {
		t.Fatalf("old android frame: %d", r.StatusCode)
	}
	e.get("/v1/device/changes?since=0", b.token)
	if r := e.do(req{method: "GET", path: "/v1/device/update", token: b.token}); r.StatusCode != 204 {
		t.Fatalf("browser update check: %d %s", r.StatusCode, r.body)
	}

	// The websocket and media accept the token in the query string.
	ws := e.dialWS("/v1/device/ws?access_token="+b.token, "")
	ws.await("hello", 5*time.Second)
	up := e.upload(owner, mediatest.JPEG(800, 600, 1, "", false), "beach.jpg", "image/jpeg")
	e.waitSend(owner, e.sendOne(owner, []string{frameID}, map[string]any{"upload_id": up, "caption": "beach"}))
	ws.await("changes", 5*time.Second)
	ch := e.get("/v1/device/changes?since=0", b.token).m(t)["changes"].([]any)
	var mediaURL string
	for _, c := range ch {
		if c.(map[string]any)["type"] == "placement.upsert" {
			mediaURL = dig(c.(map[string]any), "placement", "media", "url").(string)
		}
	}
	if mediaURL == "" {
		t.Fatalf("no placement media in %v", ch)
	}
	if r := e.do(req{method: "GET", path: mediaURL}); r.StatusCode != 401 {
		t.Fatalf("media without token: %d", r.StatusCode)
	}
	if r := e.get(mediaURL+"?access_token="+b.token, ""); len(r.body) == 0 {
		t.Fatal("empty media")
	}
	// Only media and the websocket take a query token.
	if r := e.do(req{method: "GET", path: "/v1/device/changes?since=0&access_token=" + b.token}); r.StatusCode != 401 {
		t.Fatalf("changes with query token: %d", r.StatusCode)
	}

	// Removing the frame revokes the browser like any frame.
	e.expect(req{method: "DELETE", path: "/v1/frames/" + frameID, token: owner.token}, 204)
	ws.await("revoked", 5*time.Second)
	if r := e.do(req{method: "GET", path: "/v1/device/state", token: b.token}); r.StatusCode != 401 || r.code() != "device_revoked" {
		t.Fatalf("after removal: %d %s", r.StatusCode, r.body)
	}
}
