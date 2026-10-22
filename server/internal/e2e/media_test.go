package e2e

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

func ffmpegBin() string {
	if b := os.Getenv("KINFRAME_FFMPEG"); b != "" {
		return b
	}
	return "ffmpeg"
}

func makeClip(t *testing.T, w, h, seconds int, codec string) []byte {
	t.Helper()
	out := filepath.Join(t.TempDir(), "clip.mov")
	vc := []string{"-c:v", "libx264", "-preset", "ultrafast"}
	if codec == "hevc" {
		vc = []string{"-c:v", "libx265", "-tag:v", "hvc1", "-x265-params", "log-level=error"}
	}
	args := append([]string{"-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", fmt.Sprintf("testsrc2=size=%dx%d:rate=30:duration=%d", w, h, seconds),
		"-f", "lavfi", "-i", fmt.Sprintf("sine=frequency=440:duration=%d", seconds),
		"-shortest", "-pix_fmt", "yuv420p", "-c:a", "aac"}, vc...)
	args = append(args, out)
	if b, err := exec.Command(ffmpegBin(), args...).CombinedOutput(); err != nil {
		t.Skipf("cannot build %s clip: %v %s", codec, err, b)
	}
	b, err := os.ReadFile(out)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// waitSend polls a send until no item is processing and no placement waits for a rendition.
func (e *env) waitSend(u *user, sendID string) map[string]any {
	e.t.Helper()
	var s map[string]any
	eventually(e.t, 90*time.Second, "send "+sendID+" processed", func() bool {
		s = e.get("/v1/sends/"+sendID, u.token).m(e.t)
		for _, it := range s["items"].([]any) {
			if it.(map[string]any)["status"] == "processing" {
				return false
			}
		}
		return allReady(s)
	})
	return s
}

func allReady(s map[string]any) bool {
	for _, f := range s["frames"].([]any) {
		for _, p := range f.(map[string]any)["placements"].([]any) {
			if st := p.(map[string]any)["status"]; st == "pending" {
				return false
			}
		}
	}
	return true
}

func (e *env) sendOne(u *user, frames []string, item map[string]any) string {
	e.t.Helper()
	return e.expect(req{method: "POST", path: "/v1/sends", token: u.token,
		body: map[string]any{"frame_ids": frames, "items": []map[string]any{item}}}, 202).m(e.t)["id"].(string)
}

func changesOf(ch map[string]any, typ string) []map[string]any {
	var out []map[string]any
	for _, c := range ch["changes"].([]any) {
		if c.(map[string]any)["type"] == typ {
			out = append(out, c.(map[string]any))
		}
	}
	return out
}

func TestVideoSend(t *testing.T) {
	requireMedia(t)
	videoSend(t, newEnv(t))
}

func videoSend(t *testing.T, e *env) {
	u := e.user("v@x.org", "Vera")
	d, frameID := e.claim(u)
	clip := makeClip(t, 1280, 720, 6, "hevc")
	up := e.upload(u, clip, "IMG_1234.MOV", "video/quicktime")
	sendID := e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up, "trim": map[string]any{"start_ms": 1000, "end_ms": 4000}, "include_audio": false})
	s := e.waitSend(u, sendID)
	it := s["items"].([]any)[0].(map[string]any)
	if it["kind"] != "video" || it["status"] != "ready" || it["has_audio"] != false {
		t.Fatalf("video item: %v", it)
	}
	if d := it["duration_ms"].(float64); d < 2800 || d > 3300 {
		t.Fatalf("trimmed duration %v", d)
	}
	ch := e.get("/v1/device/changes?since=0", d.token).m(t)
	ups := changesOf(ch, "placement.upsert")
	if len(ups) != 1 {
		t.Fatalf("changes: %v", ch)
	}
	m := dig(ups[0], "placement", "media").(map[string]any)
	if m["mime"] != "video/mp4" || m["poster_url"] == nil {
		t.Fatalf("video media: %v", m)
	}
	body := e.get(m["url"].(string), d.token).body
	sum := sha256.Sum256(body)
	if hex.EncodeToString(sum[:]) != m["sha256"] {
		t.Fatal("video checksum")
	}
	if k, mime := media.SniffBytes(body); k != media.Video || mime != "video/mp4" {
		t.Fatalf("rendition type %s %s", k, mime)
	}
	poster := e.get(m["poster_url"].(string), d.token).body
	if k, _ := media.SniffBytes(poster); k != media.Photo {
		t.Fatal("poster")
	}

	// Trimmed below the 3 s minimum: rejected up front.
	short := e.upload(u, clip, "short.mov", "video/quicktime")
	r := e.do(req{method: "POST", path: "/v1/sends", token: u.token, body: map[string]any{"frame_ids": []string{frameID},
		"items": []map[string]any{{"upload_id": short, "trim": map[string]any{"start_ms": 1000, "end_ms": 2500}}}}})
	if r.StatusCode != 422 || r.code() != "validation_failed" || !strings.Contains(string(r.body), "items.0.trim") {
		t.Fatalf("short trim: %d %s", r.StatusCode, r.body)
	}

	// Too long after trim: rejected up front.
	e.expect(req{method: "PUT", path: "/v1/operator/limits", token: e.operator(), body: map[string]any{
		"max_items_per_send": 200, "max_video_seconds": 2, "max_upload_bytes": 1 << 30}}, 200)
	up2 := e.upload(u, clip, "b.mov", "video/quicktime")
	r = e.do(req{method: "POST", path: "/v1/sends", token: u.token, body: map[string]any{"frame_ids": []string{frameID}, "items": []map[string]any{{"upload_id": up2}}}})
	if r.StatusCode != 422 || r.code() != "video_too_long" {
		t.Fatalf("too long: %d %s", r.StatusCode, r.body)
	}
}

func (e *env) operator() string {
	return e.expect(req{method: "POST", path: "/dev/token", body: map[string]any{"email": "operator@x.org", "name": "Op", "operator": true}}, 200).m(e.t)["access_token"].(string)
}

func TestLibraryLifecycle(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	u := e.user("l@x.org", "Lien")
	d, frameID := e.claim(u)
	d2, frame2 := e.claim(u)
	img := mediatest.JPEG(1600, 1200, 6, "2020:01:02 03:04:05", false)
	up := e.upload(u, img, "a.jpg", "image/jpeg")
	sendID := e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up, "caption": "one", "fit": "fit"})
	s := e.waitSend(u, sendID)
	item := s["items"].([]any)[0].(map[string]any)
	if item["width"] != float64(1200) || item["height"] != float64(1600) {
		t.Fatalf("EXIF orientation not applied: %v", item)
	}

	// Duplicate detection: the same bytes yield Kinframe-Existing-Item at tus creation,
	// and sending that item id reuses the media without processing.
	sum := sha256.Sum256(img)
	meta := "filename " + base64.StdEncoding.EncodeToString([]byte("a.jpg")) + ",sha256 " + base64.StdEncoding.EncodeToString([]byte(hex.EncodeToString(sum[:])))
	cr := e.expect(req{method: "POST", path: "/v1/uploads", token: u.token, header: map[string]string{"Tus-Resumable": "1.0.0", "Upload-Length": fmt.Sprint(len(img)), "Upload-Metadata": meta}}, 201)
	if cr.Header.Get("Kinframe-Existing-Item") != item["id"] {
		t.Fatalf("dedupe header %q, want %v", cr.Header.Get("Kinframe-Existing-Item"), item["id"])
	}
	dupSend := e.sendOne(u, []string{frame2}, map[string]any{"upload_id": item["id"], "caption": "again"})
	ds := e.waitSend(u, dupSend)
	if dig(ds, "summary", "pending") != float64(1) {
		t.Fatalf("dedupe send: %v", ds)
	}

	// Forward the first send to the second frame too (placed once per frame).
	e.expect(req{method: "POST", path: "/v1/sends/" + sendID + "/forward", token: u.token, body: map[string]any{"frame_ids": []string{frame2}}}, 202)
	fw := e.waitSend(u, sendID)
	if len(fw["frames"].([]any)) != 2 {
		t.Fatalf("forward: %v", fw)
	}

	// Library listing, caption edit, hide, trash and the feed.
	lib := e.get("/v1/frames/"+frameID+"/placements", u.token).m(t)
	pls := lib["placements"].([]any)
	if len(pls) != 1 || dig(lib, "counts", "photos") != float64(1) {
		t.Fatalf("library: %v", lib)
	}
	pid := pls[0].(map[string]any)["id"].(string)
	since := e.get("/v1/device/changes?since=0", d.token).m(t)["next_seq"].(float64)
	ed := e.expect(req{method: "PATCH", path: "/v1/items/" + item["id"].(string), token: u.token, body: map[string]any{"caption": nil, "focal_point": map[string]any{"x": 0.2, "y": 0.3}}}, 200).m(t)
	if ed["caption"] != nil || ed["caption_edited"] != true {
		t.Fatalf("item edit: %v", ed)
	}
	ch := e.get("/v1/device/changes?since="+ftoa(since), d.token).m(t)
	if ups := changesOf(ch, "placement.upsert"); len(ups) != 1 || dig(ups[0], "placement", "item", "caption_edited") != true {
		t.Fatalf("edit not in feed: %v", ch)
	}
	e.expect(req{method: "PATCH", path: "/v1/device/placements/" + pid, token: d.token, body: map[string]any{"hidden": true, "rotation": 90}}, 200)
	if l := e.get("/v1/frames/"+frameID+"/placements?visibility=hidden", u.token).m(t); len(l["placements"].([]any)) != 1 {
		t.Fatalf("hidden list: %v", l)
	}
	bulk := e.expect(req{method: "POST", path: "/v1/frames/" + frameID + "/placements:bulk", token: u.token,
		body: map[string]any{"placement_ids": []string{pid, "01a16300-0000-7000-8000-000000000000"}, "action": "trash"}}, 200).m(t)
	if len(bulk["ok"].([]any)) != 1 || len(bulk["failed"].([]any)) != 1 {
		t.Fatalf("bulk: %v", bulk)
	}
	// Trash retention elapses: the janitor purges it and the frame gets placement.remove.
	if _, err := e.app.Pool.Exec(context.Background(), `UPDATE placements SET trashed_at = now() - interval '31 days' WHERE id = $1`, pid); err != nil {
		t.Fatal(err)
	}
	e.app.Maintain(context.Background())
	ch = e.get("/v1/device/changes?since="+ftoa(since), d.token).m(t)
	if rm := changesOf(ch, "placement.remove"); len(rm) != 1 || rm[0]["id"] != pid {
		t.Fatalf("purge not in feed: %v", ch)
	}

	// Unsend removes the dedupe send's placement from frame 2.
	since2 := e.get("/v1/device/changes?since=0", d2.token).m(t)["next_seq"].(float64)
	e.expect(req{method: "DELETE", path: "/v1/sends/" + dupSend, token: u.token}, 204)
	ch = e.get("/v1/device/changes?since="+ftoa(since2), d2.token).m(t)
	if rm := changesOf(ch, "placement.remove"); len(rm) != 1 {
		t.Fatalf("unsend not in feed: %v", ch)
	}
	if st := e.get("/v1/sends/"+dupSend, u.token).m(t); dig(st, "frames").([]any)[0].(map[string]any)["placements"].([]any)[0].(map[string]any)["status"] != "removed" {
		t.Fatalf("unsent status: %v", st)
	}
}

func TestImportsAndFailures(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	u := e.user("i@x.org", "Ingrid")
	d, frameID := e.claim(u)

	// A frame imports a photo from its SD card; the frame is the sender.
	png := mediatest.JPEG(800, 600, 0, "", false)
	imp := e.expect(req{method: "POST", path: "/v1/device/imports", token: d.token, raw: png,
		header: map[string]string{"Content-Type": "application/octet-stream", "X-Filename": "DSC0001.JPG"}}, 202).m(t)
	// The harness enrols frames in Dutch, so the default name is Dutch too.
	if dig(imp, "item", "sender_name") != "Fotolijst van Ingrid" {
		t.Fatalf("import: %v", imp)
	}
	eventually(t, 30*time.Second, "import processed", func() bool {
		ch := e.get("/v1/device/changes?since=0", d.token).m(t)
		ups := changesOf(ch, "placement.upsert")
		return len(ups) == 1 && dig(ups[0], "placement", "media") != nil
	})

	// A corrupt JPEG passes sniffing but fails processing: item failed, nothing reaches the frame.
	bad := append([]byte{}, png[:300]...)
	up := e.upload(u, bad, "broken.jpg", "image/jpeg")
	sendID := e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up})
	var s map[string]any
	eventually(t, 30*time.Second, "failure", func() bool {
		s = e.get("/v1/sends/"+sendID, u.token).m(t)
		return dig(s, "summary", "failed") == float64(1)
	})
	if it := s["items"].([]any)[0].(map[string]any); it["status"] != "failed" || it["failure_code"] != "corrupt_media" {
		t.Fatalf("failed item: %v", it)
	}

	// Avatars are cropped to 256x256 WebP and shown to frames.
	avatarResp := e.expect(req{method: "PUT", path: "/v1/me/avatar", token: u.token, raw: mediatest.JPEG(900, 400, 0, "", false),
		header: map[string]string{"Content-Type": "image/jpeg"}}, 200).m(t)
	av := e.get(avatarResp["avatar_url"].(string), "")
	if k, mime := media.SniffBytes(av.body); k != media.Photo || mime != "image/webp" {
		t.Fatalf("avatar type %s", mime)
	}
	if !strings.HasPrefix(avatarResp["avatar_url"].(string), e.url+"/avatars/") {
		t.Fatalf("avatar url %v", avatarResp["avatar_url"])
	}
	e.expect(req{method: "DELETE", path: "/v1/me/avatar", token: u.token}, 204)
}

// TestOnThisDay: ?on_this_day=MM-DD lists placements captured on that day in earlier years.
func TestOnThisDay(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	u := e.user("o@x.org", "Odette")
	_, frameID := e.claim(u)
	now := time.Now().UTC()
	dates := map[string]string{
		"three years ago": now.AddDate(-3, 0, 0).Format("2006:01:02") + " 10:00:00",
		"this year":       now.Format("2006:01:02") + " 00:30:00",
		"other day":       now.AddDate(-2, 0, 3).Format("2006:01:02") + " 10:00:00",
	}
	sends := map[string]string{}
	for caption, d := range dates {
		up := e.upload(u, mediatest.JPEG(640, 480, 1, d, false), caption+".jpg", "image/jpeg")
		sends[caption] = e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up, "caption": caption})
	}
	for _, id := range sends {
		e.waitSend(u, id)
	}
	lib := e.get("/v1/frames/"+frameID+"/placements?on_this_day="+now.Format("01-02"), u.token).m(t)
	pls := lib["placements"].([]any)
	if len(pls) != 1 || dig(pls[0].(map[string]any), "item", "caption") != "three years ago" {
		t.Fatalf("on this day: %v", pls)
	}
	for _, bad := range []string{"13-01", "1-2", "02-30"} {
		if r := e.do(req{method: "GET", path: "/v1/frames/" + frameID + "/placements?on_this_day=" + bad, token: u.token}); r.StatusCode != 400 {
			t.Errorf("%s: %d %s", bad, r.StatusCode, r.body)
		}
	}
	if r := e.do(req{method: "GET", path: "/v1/frames/" + frameID + "/placements?on_this_day=02-29", token: u.token}); r.StatusCode != 200 {
		t.Errorf("leap day: %d", r.StatusCode)
	}
}
