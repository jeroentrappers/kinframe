package e2e

import (
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/config"
)

// TestFamilyAppVersions: discovery publishes the per-platform policy, and a
// family app below its platform's minimum build gets 426 on user endpoints
// (README §5.15) while the website, other platforms and the frame are unaffected.
func TestFamilyAppVersions(t *testing.T) {
	e := newEnv(t, func(c *config.Config) {
		c.AppVersions = map[string]config.AppVersionPolicy{
			"android": {MinBuild: 100, RecommendedBuild: 120, StoreURL: "https://play.google.com/store/apps/details?id=app.kinframe"},
			"ios":     {MinBuild: 50},
			"macos":   {RecommendedBuild: 9},
		}
		c.MinFrameAppVersion = 2
	})
	u := e.user("fam@x.org", "Fam")
	hdr := func(v string) map[string]string { return map[string]string{"X-Kinframe-App": v} }
	oldAndroid := hdr("kinframe-family-android/1.0.0 (99)")

	// Discovery carries the policy and is never blocked.
	d := e.expect(req{method: "GET", path: "/.well-known/kinframe", header: oldAndroid}, 200).m(t)
	av, _ := d["app_versions"].(map[string]any)
	android, _ := av["android"].(map[string]any)
	ios, _ := av["ios"].(map[string]any)
	macos, _ := av["macos"].(map[string]any)
	if android["minimum_build"] != float64(100) || android["recommended_build"] != float64(120) ||
		android["store_url"] != "https://play.google.com/store/apps/details?id=app.kinframe" ||
		ios["minimum_build"] != float64(50) || ios["recommended_build"] != nil || ios["store_url"] != nil ||
		macos["recommended_build"] != float64(9) || macos["minimum_build"] != nil {
		t.Fatalf("app_versions = %v", d["app_versions"])
	}

	gated := func(h map[string]string, want int) {
		t.Helper()
		r := e.do(req{method: "GET", path: "/v1/me", token: u.token, header: h})
		if r.StatusCode != want || (want == 426 && r.code() != "app_update_required") {
			t.Fatalf("%v: /v1/me %d %s, want %d", h, r.StatusCode, r.body, want)
		}
	}
	gated(oldAndroid, 426)
	gated(hdr("kinframe-family-android/1.1.0 (100)"), 200) // at the minimum
	gated(hdr("kinframe-family-android/1.2.0 (130)"), 200) // above
	gated(hdr("kinframe-family-ios/1.0.0 (49)"), 426)
	gated(hdr("kinframe-family-ios/1.0.0 (50)"), 200)
	gated(hdr("kinframe-family-macos/1.0.0 (1)"), 200) // no minimum for macOS
	gated(nil, 200)                                    // the website / apps from before the header
	gated(hdr("kinframe-family-android/garbled"), 200) // unparsable: not blocked
	gated(hdr("kinframe-frame/0.1.0 (1)"), 200)        // the frame header is not a family app

	// Refused before authentication: an outdated app with no token learns it must update.
	if r := e.do(req{method: "GET", path: "/v1/me", header: oldAndroid}); r.StatusCode != 426 {
		t.Fatalf("no token: %d", r.StatusCode)
	}
	// The user websocket is a user endpoint too.
	if r := e.do(req{method: "GET", path: "/v1/ws?access_token=" + u.token, header: oldAndroid}); r.StatusCode != 426 {
		t.Fatalf("ws: %d %s", r.StatusCode, r.body)
	}

	// Public, unauthenticated endpoints stay reachable.
	for _, p := range []struct {
		path string
	}{{"/healthz"}, {"/v1/oauth/google/callback?state=x&code=y"}, {"/v1/tv/nope"}, {"/v1/stations/latest"}, {"/"}} {
		if r := e.do(req{method: "GET", path: p.path, header: oldAndroid}); r.StatusCode == 426 {
			t.Errorf("%s: blocked", p.path)
		}
	}

	// The frame path keeps its own gate: a frame below KINFRAME_MIN_FRAME_APP_VERSION_CODE gets 426 on
	// ordinary calls whatever header it sends, and the family policy doesn't touch it.
	dev := e.enroll(1280, 800)
	e.expect(req{method: "POST", path: "/v1/device/heartbeat", token: dev.token, header: hdr("kinframe-frame/0.1.0 (1)"),
		body: map[string]any{"app_version": "0.2.0", "app_version_code": 5, "uptime_s": 5}}, 204)
	if r := e.do(req{method: "GET", path: "/v1/device/changes?since=0", token: dev.token, header: hdr("kinframe-frame/0.2.0 (5)")}); r.StatusCode == 426 {
		t.Fatalf("current frame blocked: %s", r.body)
	}
	e.expect(req{method: "POST", path: "/v1/device/heartbeat", token: dev.token,
		body: map[string]any{"app_version": "0.1.0", "app_version_code": 1, "uptime_s": 5}}, 204)
	if r := e.do(req{method: "GET", path: "/v1/device/changes?since=0", token: dev.token}); r.StatusCode != 426 {
		t.Fatalf("old frame: %d", r.StatusCode)
	}
}

// TestFamilyAppVersionsUnset: without a policy discovery has no app_versions and nothing is blocked.
func TestFamilyAppVersionsUnset(t *testing.T) {
	e := newEnv(t)
	u := e.user("fam2@x.org", "Fam")
	if d := e.get("/.well-known/kinframe", "").m(t); d["app_versions"] != nil {
		t.Fatalf("app_versions = %v", d["app_versions"])
	}
	e.expect(req{method: "GET", path: "/v1/me", token: u.token, header: map[string]string{"X-Kinframe-App": "kinframe-family-ios/0.0.1 (1)"}}, 200)
}
