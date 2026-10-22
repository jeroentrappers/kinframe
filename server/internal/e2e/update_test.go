package e2e

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/releases"
	"github.com/jeroentrappers/kinframe/server/internal/releases/releasestest"
)

func TestFrameSelfUpdate(t *testing.T) {
	drop := t.TempDir()
	stable := releasestest.APK(releases.FramePackage, "0.2.0", 20099, 23)
	beta := releasestest.APK(releases.FramePackage, "0.3.0-beta.1", 30001, 23)
	newer := releasestest.APK(releases.FramePackage, "0.4.0", 40099, 26) // needs Android 8
	for name, b := range map[string][]byte{"a.apk": stable, "b.apk": beta, "c.apk": newer} {
		if err := os.WriteFile(filepath.Join(drop, name), b, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	e := newEnv(t, func(c *config.Config) {
		c.FrameReleasesDir = drop
		c.MinFrameAppVersion = 2
	})
	owner := e.user("o@x.org", "O")
	// Enrolled as version code 1, below the minimum: report 2 (heartbeat is exempt) to claim, then go back to 1.
	d := e.enroll(1280, 800)
	beat := func(code int) {
		e.expect(req{method: "POST", path: "/v1/device/heartbeat", token: d.token, body: map[string]any{
			"app_version": "0.1.0", "app_version_code": code, "uptime_s": 5}}, 204)
	}
	beat(2)
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: d.token}, 200).m(t)["code"].(string)
	frameID := e.expect(req{method: "POST", path: "/v1/frames/redeem", token: owner.token, body: map[string]any{"code": code}}, 200).
		m(t)["frame"].(map[string]any)["id"].(string)
	beat(1)
	app := map[string]string{"X-Kinframe-App": "kinframe-frame/0.1.0 (1)"}

	// The frame is below the minimum version: ordinary calls get 426, the update endpoints don't.
	if r := e.do(req{method: "GET", path: "/v1/device/changes?since=0", token: d.token, header: app}); r.StatusCode != 426 || r.code() != "app_update_required" {
		t.Fatalf("changes: %d %s", r.StatusCode, r.body)
	}
	u := e.expect(req{method: "GET", path: "/v1/device/update", token: d.token, header: app}, 200).m(t)
	sum := sha256.Sum256(stable)
	if u["version_code"] != float64(20099) || u["version_name"] != "0.2.0" || u["sha256"] != hex.EncodeToString(sum[:]) ||
		u["size"] != float64(len(stable)) || u["min_sdk"] != float64(23) || u["prerelease"] != false ||
		u["url"] != e.url+"/v1/device/update/apk/20099" {
		t.Fatalf("stable update: %v", u)
	}

	// Download with resume (Range) and conditional requests (ETag).
	full := e.expect(req{method: "GET", path: u["url"].(string), token: d.token, header: app}, 200)
	if string(full.body) != string(stable) || full.Header.Get("ETag") != `"`+u["sha256"].(string)+`"` {
		t.Fatal("APK body or ETag wrong")
	}
	part := e.expect(req{method: "GET", path: u["url"].(string), token: d.token, header: map[string]string{
		"Range": "bytes=100-", "If-Range": full.Header.Get("ETag")}}, 206)
	if string(part.body) != string(stable[100:]) {
		t.Fatal("range body wrong")
	}
	e.expect(req{method: "GET", path: u["url"].(string), token: d.token, header: map[string]string{"If-None-Match": full.Header.Get("ETag")}}, 304)
	e.expect(req{method: "GET", path: "/v1/device/update/apk/12345", token: d.token}, 404)
	e.expect(req{method: "GET", path: "/v1/device/update/apk/20099"}, 401)

	// Beta via query, and via the frame setting.
	if u := e.expect(req{method: "GET", path: "/v1/device/update?channel=beta", token: d.token, header: app}, 200).m(t); u["version_code"] != float64(30001) || u["prerelease"] != true {
		t.Fatalf("beta update: %v", u)
	}
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frameID + "/settings", token: owner.token,
		body: map[string]any{"updates": map[string]any{"channel": "beta"}}, header: map[string]string{"If-Match": `"1"`}}, 200)
	if u := e.expect(req{method: "GET", path: "/v1/device/update", token: d.token, header: app}, 200).m(t); u["version_code"] != float64(30001) {
		t.Fatalf("beta from settings: %v", u)
	}
	// Skipped (rolled back) versions fall back to the next newest.
	if u := e.expect(req{method: "GET", path: "/v1/device/update?skip=30001,77", token: d.token, header: app}, 200).m(t); u["version_code"] != float64(20099) {
		t.Fatalf("skip: %v", u)
	}
	// Up to date (0.4.0 needs API 26, the frame is API 23).
	e.expect(req{method: "GET", path: "/v1/device/update?channel=stable", token: d.token, header: map[string]string{"X-Kinframe-App": "kinframe-frame/0.2.0 (20099)"}}, 204)
	e.expect(req{method: "GET", path: "/v1/device/update?channel=nightly", token: d.token, header: app}, 400)
	e.expect(req{method: "GET", path: "/v1/device/update?skip=abc", token: d.token, header: app}, 400)

	// Update state shows up in the owner's health view.
	e.expect(req{method: "POST", path: "/v1/device/heartbeat", token: d.token, header: app, body: map[string]any{
		"app_version": "0.1.0", "app_version_code": 1, "uptime_s": 5, "update_state": "rolled_back", "last_update_error": "crash_loop 30001"}}, 204)
	h := e.get("/v1/frames/"+frameID+"/health", owner.token).m(t)
	if dig(h, "last_heartbeat", "update_state") != "rolled_back" || dig(h, "last_heartbeat", "last_update_error") != "crash_loop 30001" {
		t.Fatalf("health: %v", h)
	}
	e.expect(req{method: "POST", path: "/v1/device/heartbeat", token: d.token, body: map[string]any{
		"app_version": "0.1.0", "uptime_s": 5, "update_state": "exploded"}}, 422)
}

func TestSelfUpdateDisabled(t *testing.T) {
	e := newEnv(t)
	d := e.enroll(1280, 800)
	e.expect(req{method: "GET", path: "/v1/device/update", token: d.token}, 204)
	e.expect(req{method: "GET", path: "/v1/device/update/apk/1", token: d.token}, 404)
}
