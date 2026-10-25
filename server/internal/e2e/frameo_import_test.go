package e2e

import (
	"context"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

// webp converts a JPEG fixture to WebP with vips, like the files a Frameo frame keeps.
func webp(t *testing.T, jpg []byte) []byte {
	t.Helper()
	dir := t.TempDir()
	in, out := filepath.Join(dir, "in.jpg"), filepath.Join(dir, "out.webp")
	if err := os.WriteFile(in, jpg, 0o600); err != nil {
		t.Fatal(err)
	}
	if b, err := exec.Command("vips", "copy", in, out).CombinedOutput(); err != nil {
		t.Fatalf("vips: %v %s", err, b)
	}
	b, err := os.ReadFile(out)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// TestFrameoImport: a converted frame uploads the photos its old app received
// (docs/spec/frameo-import.md). Imports keep the original sender, times,
// caption, fit and focal point, and are idempotent per source key.
func TestFrameoImport(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	owner := e.user("frameo-owner@x.org", "Fien")
	d, frameID := e.claim(owner)
	hdr := func(name string) map[string]string {
		return map[string]string{"Content-Type": "application/octet-stream", "X-Filename": name, "X-Captured-At": "2023-08-01T10:00:00Z"}
	}
	imp := func(key string, extra url.Values) string {
		q := url.Values{"source": {"frameo"}, "source_key": {key}}
		for k, v := range extra {
			q[k] = v
		}
		return "/v1/device/imports?" + q.Encode()
	}
	photo := webp(t, mediatest.JPEG(1707, 1280, 0, "", false))
	first := e.expect(req{method: "POST", token: d.token, raw: photo, header: hdr("1700000000000.webp"), path: imp("1700000000000.webp", url.Values{
		"sender_name": {"Anna"}, "received_at": {"2023-11-14T22:13:20Z"}, "caption": {"Zee"},
		"fit": {"fill"}, "focal_x": {"0.25"}, "focal_y": {"0.75"},
	})}, 202).m(t)
	if dig(first, "item", "imported_from") != "frameo" || dig(first, "item", "imported_sender_name") != "Anna" {
		t.Fatalf("origin: %v", first["item"])
	}
	// The frame stays the sender, like any import on the frame.
	if dig(first, "item", "sender_name") != "Fotolijst van Fien" {
		t.Fatalf("sender: %v", dig(first, "item", "sender_name"))
	}
	if dig(first, "item", "caption") != "Zee" || dig(first, "item", "fit") != "fill" ||
		dig(first, "item", "focal_point", "x") != 0.25 || dig(first, "item", "focal_point", "y") != 0.75 {
		t.Fatalf("item: %v", first["item"])
	}
	if first["received_at"] != "2023-11-14T22:13:20Z" || first["hidden"] != false {
		t.Fatalf("placement: %v", first)
	}

	// A hidden photo, a received time in the future (clamped) and no sender.
	hidden := e.expect(req{method: "POST", token: d.token, raw: webp(t, mediatest.JPEG(1280, 1707, 0, "", false)), header: hdr("1700000005000.webp"),
		path: imp("1700000005000.webp", url.Values{"hidden": {"true"}, "fit": {"fit"}, "rotation": {"90"}, "received_at": {"2999-01-01T00:00:00Z"}})}, 202).m(t)
	if hidden["hidden"] != true || hidden["rotation"] != float64(90) || dig(hidden, "item", "fit") != "fit" || dig(hidden, "item", "imported_sender_name") != nil {
		t.Fatalf("hidden import: %v", hidden)
	}
	if ra, _ := time.Parse(time.RFC3339, hidden["received_at"].(string)); ra.After(time.Now().Add(time.Minute)) {
		t.Fatalf("future received_at kept: %v", hidden["received_at"])
	}

	// The frame owns nothing; the owner owns the media.
	var owned int
	if err := e.app.Pool.QueryRow(context.Background(), `SELECT count(*) FROM media m JOIN items i ON i.media_id = m.id
		WHERE i.origin = 'frameo' AND m.owner_user_id = (SELECT user_id FROM memberships WHERE frame_id = $1 AND role = 'owner')`, frameID).Scan(&owned); err != nil {
		t.Fatal(err)
	}
	if owned != 2 {
		t.Fatalf("owner owns %d imported media, want 2", owned)
	}

	// Resume: the frame asks which keys the server has.
	known := e.expect(req{method: "POST", path: "/v1/device/imports/check", token: d.token, body: map[string]any{
		"source": "frameo", "source_keys": []string{"1700000000000.webp", "1700000005000.webp", "1700000009000.mp4"},
	}}, 200).m(t)
	if k, _ := known["known_source_keys"].([]any); len(k) != 2 || len(known["known"].([]any)) != 0 {
		t.Fatalf("check: %v", known)
	}

	// The same key again, even with other bytes: the existing placement, nothing new.
	again := e.expect(req{method: "POST", token: d.token, raw: webp(t, mediatest.JPEG(900, 700, 0, "", false)), header: hdr("1700000000000.webp"),
		path: imp("1700000000000.webp", url.Values{"sender_name": {"Anna"}})}, 200).m(t)
	if again["id"] != first["id"] {
		t.Fatalf("re-import created %v, want %v", again["id"], first["id"])
	}

	// Deleted in kinframe: a re-run must not bring it back.
	e.expect(req{method: "DELETE", path: "/v1/frames/" + frameID + "/placements/" + first["id"].(string), token: owner.token}, 204)
	gone := e.expect(req{method: "POST", token: d.token, raw: photo, header: hdr("1700000000000.webp"), path: imp("1700000000000.webp", nil)}, 200).m(t)
	if gone["id"] != first["id"] || gone["status"] != "removed" {
		t.Fatalf("re-import after delete: %v", gone)
	}
	lib := e.get("/v1/frames/"+frameID+"/placements?visibility=all", owner.token).m(t)
	if n := len(lib["placements"].([]any)); n != 1 {
		t.Fatalf("library has %d placements, want 1 (the hidden one)", n)
	}

	// Without `source` the other-app parameters are ignored: a plain import.
	plain := e.expect(req{method: "POST", token: d.token, raw: mediatest.JPEG(640, 480, 0, "", false),
		header: map[string]string{"Content-Type": "application/octet-stream", "X-Filename": "x.jpg"},
		path:   "/v1/device/imports?source_key=x&sender_name=Bob"}, 202).m(t)
	if dig(plain, "item", "imported_from") != nil || dig(plain, "item", "imported_sender_name") != nil {
		t.Fatalf("plain import has an origin: %v", plain["item"])
	}
	if r := e.do(req{method: "POST", token: d.token, raw: photo, header: hdr("y.webp"), path: "/v1/device/imports?source=other"}); r.StatusCode != 400 && r.StatusCode != 422 {
		t.Fatalf("unknown source: %d", r.StatusCode)
	}

	// Processing keeps the origin and the original received time in the frame's feed.
	eventually(t, 30*time.Second, "imports processed", func() bool {
		ch := e.get("/v1/device/changes?since=0", d.token).m(t)
		for _, u := range changesOf(ch, "placement.upsert") {
			if dig(u, "placement", "id") == hidden["id"] {
				return dig(u, "placement", "media") != nil && dig(u, "placement", "item", "imported_from") == "frameo"
			}
		}
		return false
	})
}
