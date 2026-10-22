package e2e

import (
	"bytes"
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

	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

// uploadPrepared is upload with the tus `prepared` metadata key.
func (e *env) uploadPrepared(u *user, data []byte, filename, mime, prepared string) string {
	e.t.Helper()
	b64 := func(s string) string { return base64.StdEncoding.EncodeToString([]byte(s)) }
	meta := fmt.Sprintf("filename %s,filetype %s", b64(filename), b64(mime))
	if prepared != "" {
		meta += ",prepared " + b64(prepared)
	}
	res := e.expect(req{method: "POST", path: "/v1/uploads", token: u.token, header: map[string]string{
		"Tus-Resumable": "1.0.0", "Upload-Length": fmt.Sprint(len(data)), "Upload-Metadata": meta,
	}}, 201)
	loc := res.Header.Get("Location")
	e.expect(req{method: "PATCH", path: loc, token: u.token, raw: data, header: map[string]string{
		"Tus-Resumable": "1.0.0", "Upload-Offset": "0", "Content-Type": "application/offset+octet-stream",
	}}, 204)
	return strings.TrimPrefix(loc, e.url+"/v1/uploads/")
}

type mediaRow struct {
	pipeline, note, mime, sha string
	motion                    bool
}

// mediaOf reads how an item's media (or its motion clip) was made.
func (e *env) mediaOf(itemID string, motion bool) mediaRow {
	e.t.Helper()
	col := "media_id"
	if motion {
		col = "motion_media_id"
	}
	var r mediaRow
	var note *string
	err := e.app.Pool.QueryRow(context.Background(), `SELECT COALESCE(m.pipeline, ''), m.pipeline_note, COALESCE(m.master_mime, ''),
		COALESCE(m.master_sha256, ''), m.motion FROM items i JOIN media m ON m.id = i.`+col+` WHERE i.id = $1`, itemID).
		Scan(&r.pipeline, &note, &r.mime, &r.sha, &r.motion)
	if err != nil {
		e.t.Fatal(err)
	}
	if note != nil {
		r.note = *note
	}
	return r
}

func (e *env) reusedRenditions(itemID string, motion bool) int {
	e.t.Helper()
	col := "media_id"
	if motion {
		col = "motion_media_id"
	}
	var n int
	if err := e.app.Pool.QueryRow(context.Background(), `SELECT count(*) FROM renditions r JOIN items i ON r.media_id = i.`+col+`
		WHERE i.id = $1 AND r.reused_master`, itemID).Scan(&n); err != nil {
		e.t.Fatal(err)
	}
	return n
}

// itemMedia follows the signed redirect of /v1/items/{id}/media/{variant}.
func (e *env) itemMedia(u *user, itemID, variant string) []byte {
	e.t.Helper()
	r := e.expect(req{method: "GET", path: "/v1/items/" + itemID + "/media/" + variant, token: u.token}, 302)
	return e.get(r.Header.Get("Location"), "").body
}

func sha(b []byte) string { s := sha256.Sum256(b); return hex.EncodeToString(s[:]) }

func vipsBin(name string) string {
	if b := os.Getenv("KINFRAME_" + strings.ToUpper(name)); b != "" {
		return b
	}
	return name
}

// webpMaster makes what a client prepares: a metadata-free WebP q90.
func webpMaster(t *testing.T, w, h int) []byte {
	t.Helper()
	dir := t.TempDir()
	src := filepath.Join(dir, "in.jpg")
	if err := os.WriteFile(src, mediatest.JPEG(w, h, 0, "", false), 0o644); err != nil {
		t.Fatal(err)
	}
	dst := filepath.Join(dir, "out.webp")
	if b, err := exec.Command(vipsBin("vips"), "copy", src, dst+"[Q=90,strip]").CombinedOutput(); err != nil {
		t.Fatalf("vips: %v %s", err, b)
	}
	b, err := os.ReadFile(dst)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func clip(t *testing.T, name string, w, h, fps int, seconds float64, audio bool, extra ...string) []byte {
	t.Helper()
	b, err := mediatest.Clip(ffmpegBin(), t.TempDir(), name, w, h, fps, seconds, audio, extra...)
	if err != nil {
		t.Skipf("cannot build clip: %v", err)
	}
	return b
}

func itemOf(s map[string]any) map[string]any { return s["items"].([]any)[0].(map[string]any) }

func TestPreparedPhotos(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	u := e.user("p@x.org", "Pia")
	d, frameID := e.claim(u)

	// A conforming prepared WebP is kept byte for byte: no re-encode.
	wp := webpMaster(t, 2400, 1600)
	up := e.uploadPrepared(u, wp, "IMG_1.webp", "image/webp", media.PreparedPhotoV1)
	s := e.waitSend(u, e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up,
		"width": 2400, "height": 1600, "captured_at": "2020-05-06T07:08:09Z"}))
	it := itemOf(s)
	if it["status"] != "ready" || it["width"] != float64(2400) || it["captured_at"] != "2020-05-06T07:08:09Z" {
		t.Fatalf("prepared photo item: %v", it)
	}
	if m := e.mediaOf(it["id"].(string), false); m.pipeline != "prepared" || m.sha != sha(wp) || m.mime != "image/webp" {
		t.Fatalf("fast path not taken or bytes changed: %+v (upload %s)", m, sha(wp))
	}
	// Frames still get their per-frame rendition and thumbnails.
	ch := e.get("/v1/device/changes?since=0", d.token).m(t)
	ups := changesOf(ch, "placement.upsert")
	// Short edge = the frame's long edge (1280): 1920×1280.
	if m := dig(ups[0], "placement", "media").(map[string]any); m["width"] != float64(1920) || m["mime"] != "image/webp" {
		t.Fatalf("frame rendition: %v", m)
	}
	if dig(ups[0], "placement", "item", "thumb_url") == nil {
		t.Fatal("no thumbnail")
	}

	// A "prepared" JPEG that still carries GPS: accepted without re-encode, but
	// the server strips the metadata anyway (privacy is never delegated).
	gps := mediatest.JPEG(1600, 1200, 1, "2018:03:04 05:06:07", true)
	up = e.uploadPrepared(u, gps, "gps.jpg", "image/jpeg", media.PreparedPhotoV1)
	s = e.waitSend(u, e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up}))
	it = itemOf(s)
	m := e.mediaOf(it["id"].(string), false)
	if m.pipeline != "prepared" || m.mime != "image/jpeg" || m.sha == sha(gps) {
		t.Fatalf("gps jpeg: %+v", m)
	}
	if it["captured_at"] != "2018-03-04T05:06:07Z" {
		t.Fatalf("capture time from the original's EXIF: %v", it["captured_at"])
	}
	master := e.itemMedia(u, it["id"].(string), "master")
	if bytes.Contains(master, []byte("Exif\x00\x00")) || len(master) == 0 || !bytes.HasPrefix(master, []byte{0xFF, 0xD8}) {
		t.Fatalf("master still has EXIF/GPS (%d bytes)", len(master))
	}

	// Non-conforming prepared uploads fall back to the full pipeline.
	for _, tc := range []struct {
		name string
		data []byte
		mime string
		want string
	}{
		{"orientation", mediatest.JPEG(1200, 900, 6, "", false), "image/jpeg", "orientation"},
		{"oversized", webpMaster(t, 3200, 1800), "image/webp", "exceeds"},
	} {
		up := e.uploadPrepared(u, tc.data, tc.name, tc.mime, media.PreparedPhotoV1)
		it := itemOf(e.waitSend(u, e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up})))
		m := e.mediaOf(it["id"].(string), false)
		if it["status"] != "ready" || m.pipeline != "fallback" || !strings.Contains(m.note, tc.want) || m.mime != "image/webp" {
			t.Fatalf("%s: %+v %v", tc.name, m, it)
		}
		if tc.name == "orientation" && (it["width"] != float64(900) || it["height"] != float64(1200)) {
			t.Fatalf("fallback did not autorotate: %v", it)
		}
	}

	// Unprepared uploads are unchanged.
	up = e.upload(u, mediatest.JPEG(800, 600, 0, "", false), "plain.jpg", "image/jpeg")
	it = itemOf(e.waitSend(u, e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up})))
	if m := e.mediaOf(it["id"].(string), false); m.pipeline != "transcode" {
		t.Fatalf("unprepared: %+v", m)
	}

	st := e.get("/v1/operator/stats", e.operator()).m(t)
	if dig(st, "media_pipeline", "prepared") != float64(2) || dig(st, "media_pipeline", "fallback") != float64(2) ||
		dig(st, "media_pipeline", "transcoded") != float64(1) {
		t.Fatalf("stats: %v", st["media_pipeline"])
	}
}

func TestPreparedVideos(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	u := e.user("pv@x.org", "Piet")
	d, frameID := e.claim(u)

	// Conforming: remuxed (metadata gone), and the master doubles as the frame rendition.
	good := clip(t, "good.mp4", 1280, 720, 30, 4, true, append(mediatest.PreparedH264,
		"-metadata", "location=+50.8503+004.3517/", "-metadata", "creation_time=2022-08-09T10:11:12Z")...)
	up := e.uploadPrepared(u, good, "VID_1.mp4", "video/mp4", media.PreparedVideoV1)
	s := e.waitSend(u, e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up, "duration_ms": 4000, "width": 1280, "height": 720}))
	it := itemOf(s)
	m := e.mediaOf(it["id"].(string), false)
	if it["status"] != "ready" || m.pipeline != "prepared" || it["has_audio"] != true {
		t.Fatalf("prepared video: %+v %v", m, it)
	}
	if e.reusedRenditions(it["id"].(string), false) != 1 {
		t.Fatal("frame rendition was transcoded although the master fits the frame")
	}
	ups := changesOf(e.get("/v1/device/changes?since=0", d.token).m(t), "placement.upsert")
	med := dig(ups[0], "placement", "media").(map[string]any)
	if med["sha256"] != m.sha || med["poster_url"] == nil {
		t.Fatalf("frame media %v, master %s", med, m.sha)
	}
	body := e.get(med["url"].(string), d.token).body
	if sha(body) != m.sha || bytes.Contains(body, []byte("+50.8503")) {
		t.Fatal("frame got different bytes or metadata survived")
	}

	// Non-conforming prepared videos fall back to the transcode.
	for _, tc := range []struct {
		name string
		data []byte
		want string
	}{
		{"hevc", clip(t, "h.mp4", 640, 360, 30, 3, true, "-c:v", "libx265", "-tag:v", "hvc1", "-x265-params", "log-level=error"), "codec"},
		{"4k", clip(t, "k.mp4", 3840, 2160, 30, 3, false, mediatest.PreparedH264...), "1080p"},
	} {
		up := e.uploadPrepared(u, tc.data, tc.name+".mp4", "video/mp4", media.PreparedVideoV1)
		it := itemOf(e.waitSend(u, e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up})))
		m := e.mediaOf(it["id"].(string), false)
		if it["status"] != "ready" || m.pipeline != "fallback" || !strings.Contains(m.note, tc.want) {
			t.Fatalf("%s: %+v %v", tc.name, m, it)
		}
	}

	// A trim on a prepared video can't be stream-copied: transcoded instead.
	up = e.uploadPrepared(u, good, "trim.mp4", "video/mp4", media.PreparedVideoV1)
	it = itemOf(e.waitSend(u, e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up, "trim": map[string]any{"start_ms": 0, "end_ms": 3000}})))
	if m := e.mediaOf(it["id"].(string), false); m.pipeline != "fallback" || m.note != "trim requested" {
		t.Fatalf("trimmed prepared: %+v", m)
	}
}

func TestLivePhotos(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	u := e.user("lp@x.org", "Lotte")
	d, frameID := e.claim(u)

	// iPhone style: a still plus its MOV, sent as a pair. The clip is muted by default.
	still := mediatest.JPEG(1600, 1200, 1, "2024:07:08 09:10:11", false)
	mov := clip(t, "IMG_0001.MOV", 1440, 1080, 30, 2.5, true, "-c:v", "libx265", "-tag:v", "hvc1", "-x265-params", "log-level=error")
	stillUp := e.upload(u, still, "IMG_0001.HEIC.jpg", "image/jpeg")
	movUp := e.upload(u, mov, "IMG_0001.MOV", "video/quicktime")
	s := e.waitSend(u, e.sendOne(u, []string{frameID}, map[string]any{"upload_id": stillUp, "live_video_upload_id": movUp, "caption": "live"}))
	it := itemOf(s)
	if it["kind"] != "live" || it["width"] != float64(1600) {
		t.Fatalf("live item: %v", it)
	}
	itemID := it["id"].(string)
	var motion map[string]any
	eventually(t, 60e9, "motion ready", func() bool {
		motion, _ = itemOf(e.get("/v1/sends/"+s["id"].(string), u.token).m(t))["motion"].(map[string]any)
		return motion != nil && motion["status"] == "ready"
	})
	if motion["has_audio"] != false || motion["duration_ms"].(float64) < 2000 || motion["width"] != float64(1440) {
		t.Fatalf("motion: %v", motion)
	}
	if m := e.mediaOf(itemID, true); !m.motion || m.mime != "video/mp4" {
		t.Fatalf("motion media: %+v", m)
	}
	// The frame gets the still as `media` (old frames: a photo) plus `motion`.
	var pl map[string]any
	eventually(t, 60e9, "motion in feed", func() bool {
		ups := changesOf(e.get("/v1/device/changes?since=0", d.token).m(t), "placement.upsert")
		pl = ups[len(ups)-1]["placement"].(map[string]any)
		return pl["motion"] != nil
	})
	if dig(pl, "media", "mime") != "image/webp" || dig(pl, "item", "kind") != "live" || dig(pl, "motion", "has_audio") != false {
		t.Fatalf("live placement: %v", pl)
	}
	mo := pl["motion"].(map[string]any)
	clipBytes := e.get(mo["url"].(string), d.token).body
	if k, mime := media.SniffBytes(clipBytes); k != media.Video || mime != "video/mp4" || sha(clipBytes) != mo["sha256"] {
		t.Fatalf("motion download %s %s", k, mime)
	}
	if b := e.itemMedia(u, itemID, "motion"); len(b) == 0 {
		t.Fatal("item motion empty")
	}
	if l := e.get("/v1/frames/"+frameID+"/placements?kind=live", u.token).m(t); len(l["placements"].([]any)) != 1 {
		t.Fatalf("kind=live filter: %v", l)
	}
	if l := e.get("/v1/frames/"+frameID+"/placements?kind=photo", u.token).m(t); len(l["placements"].([]any)) != 1 {
		t.Fatal("kind=photo should include live photos")
	}

	// Android Motion Photo: one JPEG with an embedded MP4; the server splits it.
	mp4 := clip(t, "mv.mp4", 1280, 720, 30, 1.5, false, mediatest.PreparedH264...)
	mp := mediatest.MotionPhoto(mediatest.JPEG(1200, 900, 1, "", false), mp4, mediatest.MotionPhotoV1)
	up := e.uploadPrepared(u, mp, "PXL_MP.jpg", "image/jpeg", media.PreparedPhotoV1)
	s = e.waitSend(u, e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up}))
	it = itemOf(s)
	if it["kind"] != "live" {
		t.Fatalf("motion photo not live: %v", it)
	}
	still2 := e.mediaOf(it["id"].(string), false)
	if still2.pipeline != "prepared" {
		t.Fatalf("motion photo still: %+v", still2)
	}
	eventually(t, 60e9, "split clip processed", func() bool {
		return e.mediaOf(it["id"].(string), true).sha != ""
	})
	// The split clip conforms and fits the frame: served from the master.
	if e.reusedRenditions(it["id"].(string), true) != 1 {
		t.Fatal("motion rendition was transcoded")
	}
	master := e.itemMedia(u, it["id"].(string), "master")
	if bytes.Contains(master, []byte("ftyp")) || bytes.Contains(master, []byte("MotionPhoto")) {
		t.Fatal("still master kept the embedded video or its XMP")
	}

	// live: false sends a Motion Photo as a still only.
	up = e.upload(u, mp, "PXL_MP2.jpg", "image/jpeg")
	it = itemOf(e.waitSend(u, e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up, "live": false})))
	if it["kind"] != "photo" || it["motion"] != nil {
		t.Fatalf("still only: %v", it)
	}

	// Validation: the clip must be a video, and live:false conflicts with a clip.
	a := e.upload(u, still, "a.jpg", "image/jpeg")
	b := e.upload(u, still, "b.jpg", "image/jpeg")
	r := e.do(req{method: "POST", path: "/v1/sends", token: u.token, body: map[string]any{"frame_ids": []string{frameID},
		"items": []map[string]any{{"upload_id": a, "live_video_upload_id": b}}}})
	if r.StatusCode != 422 || !strings.Contains(string(r.body), "items.0.live_video_upload_id") {
		t.Fatalf("photo as motion: %d %s", r.StatusCode, r.body)
	}
	c := e.upload(u, mov, "c.mov", "video/quicktime")
	r = e.do(req{method: "POST", path: "/v1/sends", token: u.token, body: map[string]any{"frame_ids": []string{frameID},
		"items": []map[string]any{{"upload_id": a, "live_video_upload_id": c, "live": false}}}})
	if r.StatusCode != 422 {
		t.Fatalf("live false with clip: %d %s", r.StatusCode, r.body)
	}
}
