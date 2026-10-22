package e2e

import (
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

// TestSendLoop is the v0.1 loop end to end: enroll a frame, claim it with its
// code, upload a photo with tus, send it, let the pipeline render it, sync the
// frame from the change feed, download and verify the rendition, ack it, react,
// and check the sender sees delivery and the reaction.
func TestSendLoop(t *testing.T) {
	requireMedia(t)
	sendLoop(t, newEnv(t))
}

func sendLoop(t *testing.T, e *env) {

	// Discovery works without auth.
	disc := e.get("/.well-known/kinframe", "").m(t)
	if disc["api"] != e.url+"/v1" || disc["dev_auth"] != true || dig(disc, "limits", "max_items_per_send") != float64(200) {
		t.Fatalf("discovery: %v", disc)
	}

	// 1. A fresh frame enrolls and opens its websocket.
	d := e.enroll(1280, 800)
	frameWS := e.dialWS("/v1/device/ws", d.token)
	hello := frameWS.await("hello", 5*time.Second)
	if hello["latest_seq"] != float64(0) || hello["server_time"] == nil {
		t.Fatalf("hello: %v", hello)
	}
	state := e.get("/v1/device/state", d.token).m(t)
	if state["claimed"] != false {
		t.Fatalf("state: %v", state)
	}

	// 2. It shows a code; the same code comes back on re-request.
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: d.token}, 200).m(t)
	again := e.expect(req{method: "POST", path: "/v1/device/code", token: d.token}, 200).m(t)
	if code["code"] != again["code"] || len(code["code"].(string)) != 8 || code["join_url"] != e.url+"/f/"+code["code"].(string) {
		t.Fatalf("codes: %v %v", code, again)
	}

	// 3. A dev user claims it.
	oma := e.user("anna@example.org", "Anna")
	userWS := e.dialWS("/v1/ws?access_token="+oma.token, "")
	red := e.expect(req{method: "POST", path: "/v1/frames/redeem", token: oma.token, body: map[string]any{"code": code["code"]}}, 200).m(t)
	if red["status"] != "claimed" || dig(red, "frame", "role") != "owner" {
		t.Fatalf("redeem: %v", red)
	}
	frameID := dig(red, "frame", "id").(string)
	if claimed := frameWS.await("claimed", 5*time.Second); claimed["owner_name"] != "Anna" {
		t.Fatalf("claimed: %v", claimed)
	}
	if dig(red, "frame", "online") != true {
		t.Errorf("frame should be online while its websocket is open")
	}
	// The code was used for a claim, so it no longer works.
	if r := e.do(req{method: "POST", path: "/v1/frames/redeem", token: oma.token, body: map[string]any{"code": code["code"]}}); r.StatusCode != 404 || r.code() != "code_invalid" {
		t.Fatalf("reused claim code: %d %s", r.StatusCode, r.body)
	}

	// 4. Upload a 3000x2000 JPEG over tus (with EXIF capture time and GPS).
	jpeg := mediatest.JPEG(3000, 2000, 0, "2024:07:01 18:30:00", true)
	uploadID := e.upload(oma, jpeg, "IMG_0001.jpg", "image/jpeg")

	// 5. Send it.
	send := e.expect(req{method: "POST", path: "/v1/sends", token: oma.token, header: map[string]string{"Idempotency-Key": "send-1"},
		body: map[string]any{"frame_ids": []string{frameID}, "items": []map[string]any{{"upload_id": uploadID, "caption": "Zomer! ☀️"}}}}, 202).m(t)
	sendID := send["id"].(string)
	items := send["items"].([]any)
	if len(items) != 1 || dig(items[0].(map[string]any), "status") != "processing" {
		t.Fatalf("send: %v", send)
	}
	// Retrying with the same Idempotency-Key returns the same send.
	retry := e.expect(req{method: "POST", path: "/v1/sends", token: oma.token, header: map[string]string{"Idempotency-Key": "send-1"},
		body: map[string]any{"frame_ids": []string{frameID}, "items": []map[string]any{{"upload_id": uploadID}}}}, 202).m(t)
	if retry["id"] != sendID {
		t.Fatalf("idempotent retry created a new send")
	}

	// 6. The frame gets a "changes" hint once processing is done.
	eventually(t, 60*time.Second, "send processed", func() bool {
		s := e.get("/v1/sends/"+sendID, oma.token).m(t)
		return dig(s, "summary", "pending") == float64(1) && dig(s, "items").([]any)[0].(map[string]any)["status"] == "ready"
	})
	frameWS.await("changes", 5*time.Second)

	// 7. The frame syncs from 0.
	ch := e.get("/v1/device/changes?since=0", d.token).m(t)
	if ch["has_more"] != false {
		t.Fatalf("has_more: %v", ch)
	}
	var placement map[string]any
	types := map[string]int{}
	for _, c := range ch["changes"].([]any) {
		cm := c.(map[string]any)
		types[cm["type"].(string)]++
		if cm["type"] == "placement.upsert" {
			placement = cm["placement"].(map[string]any)
		}
	}
	if types["settings"] != 1 || types["frame"] != 1 || types["member.upsert"] != 1 || types["placement.upsert"] != 1 {
		t.Fatalf("snapshot types: %v", types)
	}
	if dig(placement, "item", "caption") != "Zomer! ☀️" || dig(placement, "item", "sender_name") != "Anna" ||
		dig(placement, "item", "captured_at") != "2024-07-01T18:30:00Z" {
		t.Fatalf("placement: %v", placement)
	}
	mediaInfo := placement["media"].(map[string]any)
	// 3000x2000 master is 2560x1707; the 1280x800 frame needs a 1920x1280 rendition.
	if mediaInfo["mime"] != "image/webp" || mediaInfo["width"] != float64(1920) || mediaInfo["height"] != float64(1280) {
		t.Fatalf("media: %v", mediaInfo)
	}
	next := ch["next_seq"].(float64)
	if empty := e.get("/v1/device/changes?since="+ftoa(next), d.token).m(t); len(empty["changes"].([]any)) != 0 {
		t.Fatalf("expected no further changes: %v", empty)
	}

	// 8. Download the rendition and check its sha256; also Range and 304.
	dl := e.get(mediaInfo["url"].(string), d.token)
	sum := sha256.Sum256(dl.body)
	if hex.EncodeToString(sum[:]) != mediaInfo["sha256"] || float64(len(dl.body)) != mediaInfo["bytes"] {
		t.Fatalf("rendition checksum mismatch")
	}
	if k, _ := media.SniffBytes(dl.body); k != media.Photo {
		t.Fatalf("rendition is not an image")
	}
	part := e.expect(req{method: "GET", path: mediaInfo["url"].(string), token: d.token, header: map[string]string{"Range": "bytes=0-99"}}, http.StatusPartialContent)
	if len(part.body) != 100 {
		t.Fatalf("range returned %d bytes", len(part.body))
	}
	e.expect(req{method: "GET", path: mediaInfo["url"].(string), token: d.token, header: map[string]string{"If-None-Match": dl.Header.Get("ETag")}}, http.StatusNotModified)

	// 9. Ack delivered, then seen.
	pid := placement["id"].(string)
	e.expect(req{method: "POST", path: "/v1/device/acks", token: d.token, body: map[string]any{"delivered": []string{pid}}}, 204)
	userWS.awaitWhere("send.updated", 5*time.Second, func(m map[string]any) bool {
		return dig(m, "send", "summary", "delivered") == float64(1)
	})
	e.expect(req{method: "POST", path: "/v1/device/acks", token: d.token, body: map[string]any{
		"seen": []map[string]any{{"placement_id": pid, "at": time.Now().UTC().Format(time.RFC3339)}}}}, 204)

	// 10. React from the frame.
	e.expect(req{method: "PUT", path: "/v1/device/placements/" + pid + "/reaction", token: d.token, body: map[string]any{"reaction": "love"}}, 204)
	rx := userWS.await("reaction", 5*time.Second)
	if rx["reaction"] != "love" || rx["placement_id"] != pid || rx["frame_id"] != frameID {
		t.Fatalf("reaction event: %v", rx)
	}

	// 11. The sender sees it all in GET /v1/sends/{id}.
	final := e.get("/v1/sends/"+sendID, oma.token).m(t)
	frames := final["frames"].([]any)
	pl := frames[0].(map[string]any)["placements"].([]any)[0].(map[string]any)
	if pl["status"] != "seen" || pl["reaction"] != "love" || dig(final, "summary", "seen") != float64(1) {
		t.Fatalf("final send: %v", final)
	}

	// The thumbnail URL works without auth headers (signed).
	thumb := dig(final, "items").([]any)[0].(map[string]any)["thumb_url"].(string)
	if !strings.HasPrefix(thumb, e.url+"/v1/media/signed/") {
		t.Fatalf("thumb url %q", thumb)
	}
	th := e.get(thumb, "")
	if k, _ := media.SniffBytes(th.body); k != media.Photo {
		t.Fatalf("thumb not an image")
	}
	// Item media redirects to a signed URL.
	loc := e.expect(req{method: "GET", path: "/v1/items/" + pl["item_id"].(string) + "/media/master", token: oma.token}, 302).Header.Get("Location")
	e.get(loc, "")
}

func ftoa(f float64) string { return strconv.FormatInt(int64(f), 10) }
