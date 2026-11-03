package e2e

import (
	"context"
	"io/fs"
	"path/filepath"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

// claimSized enrolls a device with the given screen and has u claim it.
func (e *env) claimSized(u *user, w, h int) (*device, string) {
	e.t.Helper()
	d := e.enroll(w, h)
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: d.token}, 200).m(e.t)["code"].(string)
	res := e.expect(req{method: "POST", path: "/v1/frames/redeem", token: u.token, body: map[string]any{"code": code}}, 200).m(e.t)
	return d, res["frame"].(map[string]any)["id"].(string)
}

// join has u join the frame of device d as a member.
func (e *env) join(u *user, d *device) {
	e.t.Helper()
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: d.token}, 200).m(e.t)["code"].(string)
	if r := e.expect(req{method: "POST", path: "/v1/frames/redeem", token: u.token, body: map[string]any{"code": code}}, 200).m(e.t); r["status"] != "joined" {
		e.t.Fatalf("join: %v", r)
	}
}

func (e *env) setPerms(owner *user, frameID string, member *user, perms map[string]any) {
	e.t.Helper()
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frameID + "/members/" + member.id, token: owner.token,
		body: map[string]any{"permissions": perms}}, 200)
}

// blobCount counts stored objects (masters, thumbnails, renditions).
func (e *env) blobCount() int {
	e.t.Helper()
	n := 0
	root := filepath.Join(e.app.Cfg.DataDir, "media")
	err := filepath.WalkDir(root, func(_ string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !d.IsDir() {
			n++
		}
		return nil
	})
	if err != nil {
		e.t.Fatal(err)
	}
	return n
}

func (e *env) rowCount(table string) int {
	e.t.Helper()
	var n int
	if err := e.app.Pool.QueryRow(context.Background(), `SELECT count(*) FROM `+table).Scan(&n); err != nil {
		e.t.Fatal(err)
	}
	return n
}

func (e *env) share(u *user, from string, placements, frames []string, key string, status int) map[string]any {
	e.t.Helper()
	r := req{method: "POST", path: "/v1/frames/" + from + "/placements:share", token: u.token,
		body: map[string]any{"placement_ids": placements, "frame_ids": frames}}
	if key != "" {
		r.header = map[string]string{"Idempotency-Key": key}
	}
	res := e.expect(r, status)
	if status != 200 {
		return map[string]any{"code": res.code()}
	}
	return res.m(e.t)
}

func placementUpserts(ch map[string]any) map[string]map[string]any {
	out := map[string]map[string]any{}
	for _, c := range changesOf(ch, "placement.upsert") {
		p := c["placement"].(map[string]any)
		out[p["id"].(string)] = p
	}
	return out
}

// TestSharePlacements shares a photo from one frame's library to others:
// permissions, no copied media, duplicate skips, moderation, the target's
// change feed, notifications, removal by the sharer and the unsend cascade.
func TestSharePlacements(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	anna := e.user("anna@x.org", "Anna")
	ben := e.user("ben@x.org", "Ben")
	carl := e.user("carl@x.org", "Carl")
	dave := e.user("dave@x.org", "Dave")

	// Anna's frame A (1280x800); Ben owns B (same size) and C (1024x600); Dave owns D (moderated).
	dA, frameA := e.claimSized(anna, 1280, 800)
	dB, frameB := e.claimSized(ben, 1280, 800)
	dC, frameC := e.claimSized(ben, 1024, 600)
	dD, frameD := e.claimSized(dave, 1280, 800)
	e.join(ben, dA)
	e.join(carl, dA)
	e.join(ben, dD)
	e.join(carl, dD)
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frameD, token: dave.token, body: map[string]any{"moderation": true}}, 200)

	img := mediatest.JPEG(3000, 2000, 0, "2024:07:01 18:30:00", false)
	sendID := e.sendOne(anna, []string{frameA}, map[string]any{"upload_id": e.upload(anna, img, "a.jpg", "image/jpeg"), "caption": "Zee"})
	e.waitSend(anna, sendID)
	lib := e.get("/v1/frames/"+frameA+"/placements", anna.token).m(t)
	src := lib["placements"].([]any)[0].(map[string]any)
	srcID := src["id"].(string)
	itemID := dig(src, "item", "id").(string)

	blobs0, media0, items0 := e.blobCount(), e.rowCount("media"), e.rowCount("items")

	// --- permissions
	e.setPerms(anna, frameA, ben, map[string]any{"see_library": false})
	if r := e.share(ben, frameA, []string{srcID}, []string{frameB}, "", 404); r["code"] != "not_found" {
		t.Fatalf("no see_library: %v", r)
	}
	e.setPerms(anna, frameA, ben, map[string]any{"see_library": true})
	// Ben may not send to D yet.
	e.setPerms(dave, frameD, ben, map[string]any{"send": false})
	if r := e.share(ben, frameA, []string{srcID}, []string{frameB, frameD}, "", 403); r["code"] != "missing_permission" {
		t.Fatalf("no send on target: %v", r)
	}
	e.setPerms(dave, frameD, ben, map[string]any{"send": true})
	// Anna is no member of B.
	if r := e.share(anna, frameA, []string{srcID}, []string{frameB}, "", 404); r["code"] != "not_found" {
		t.Fatalf("non-member target: %v", r)
	}
	// Unknown or foreign placements fail the whole request.
	if r := e.share(ben, frameB, []string{srcID}, []string{frameC}, "", 404); r["code"] != "not_found" {
		t.Fatalf("placement of another frame: %v", r)
	}
	// Nothing was created by the failed attempts.
	if n := e.rowCount("placements"); n != 1 {
		t.Fatalf("placements after refusals: %d", n)
	}

	// --- share to B and C (idempotent)
	userWS := e.dialWS("/v1/ws?access_token="+anna.token, "")
	benWS := e.dialWS("/v1/ws?access_token="+ben.token, "")
	res := e.share(ben, frameA, []string{srcID}, []string{frameB, frameC}, "share-1", 200)
	pls := res["placements"].([]any)
	if len(pls) != 2 || len(res["skipped"].([]any)) != 0 {
		t.Fatalf("share: %v", res)
	}
	created := map[string]string{} // frame -> placement
	for _, p := range pls {
		pm := p.(map[string]any)
		if dig(pm, "item", "id") != itemID || dig(pm, "item", "sender_name") != "Anna" || pm["shared_by_name"] != "Ben" || pm["shared_by_user_id"] != ben.id {
			t.Fatalf("shared placement: %v", pm)
		}
		created[pm["frame_id"].(string)] = pm["id"].(string)
	}
	again := e.share(ben, frameA, []string{srcID}, []string{frameB, frameC}, "share-1", 200)
	if len(again["placements"].([]any)) != 2 || again["placements"].([]any)[0].(map[string]any)["id"] != pls[0].(map[string]any)["id"] {
		t.Fatalf("idempotent retry: %v", again)
	}

	// Already there: skipped and reported; a dry run reports the same without writing.
	dup := e.share(ben, frameA, []string{srcID}, []string{frameA, frameB}, "", 200)
	if len(dup["placements"].([]any)) != 0 || len(dup["skipped"].([]any)) != 2 {
		t.Fatalf("duplicate share: %v", dup)
	}
	for _, s := range dup["skipped"].([]any) {
		sm := s.(map[string]any)
		if sm["reason"] != "already_on_frame" || sm["placement_id"] != srcID {
			t.Fatalf("skip: %v", sm)
		}
		if sm["frame_id"] == frameB && sm["existing_placement_id"] != created[frameB] {
			t.Fatalf("skip existing id: %v", sm)
		}
	}
	dry := e.expect(req{method: "POST", path: "/v1/frames/" + frameA + "/placements:share", token: ben.token,
		body: map[string]any{"placement_ids": []string{srcID}, "frame_ids": []string{frameB, frameD}, "dry_run": true}}, 200).m(t)
	if len(dry["placements"].([]any)) != 0 || len(dry["skipped"].([]any)) != 1 || dig(dry["skipped"].([]any)[0].(map[string]any), "frame_id") != frameB {
		t.Fatalf("dry run: %v", dry)
	}

	// --- storage: same item and media; only C's new screen size got a rendition.
	s := e.waitSend(anna, sendID)
	if len(s["frames"].([]any)) != 3 {
		t.Fatalf("history should list A, B and C: %v", s)
	}
	sharedNames := 0
	for _, f := range s["frames"].([]any) {
		for _, p := range f.(map[string]any)["placements"].([]any) {
			if p.(map[string]any)["shared_by_name"] == "Ben" {
				sharedNames++
			}
		}
	}
	if sharedNames != 2 {
		t.Fatalf("history shared_by_name: %v", s)
	}
	if m, i := e.rowCount("media"), e.rowCount("items"); m != media0 || i != items0 {
		t.Fatalf("share copied media or items: media %d->%d items %d->%d", media0, m, items0, i)
	}
	if b := e.blobCount(); b != blobs0+1 {
		t.Fatalf("blobs %d -> %d, want exactly one new rendition (C's screen size)", blobs0, b)
	}

	// --- B's change feed has the placement with media, the same rendition as A's.
	chA := placementUpserts(e.get("/v1/device/changes?since=0", dA.token).m(t))
	chB := placementUpserts(e.get("/v1/device/changes?since=0", dB.token).m(t))
	pB := chB[created[frameB]]
	if pB == nil || pB["shared_by_name"] != "Ben" || dig(pB, "item", "sender_name") != "Anna" || dig(pB, "item", "caption") != "Zee" {
		t.Fatalf("B feed: %v", chB)
	}
	mediaB := pB["media"].(map[string]any)
	if mediaB["sha256"] != dig(chA[srcID], "media", "sha256") {
		t.Fatalf("B should reuse A's rendition: %v vs %v", mediaB, chA[srcID]["media"])
	}
	e.get(mediaB["url"].(string), dB.token)
	if pC := placementUpserts(e.get("/v1/device/changes?since=0", dC.token).m(t))[created[frameC]]; dig(pC, "media", "width") == dig(chA[srcID], "media", "width") {
		t.Fatalf("C needs its own size: %v", pC)
	}

	// --- delivery: the sharer hears "delivered", the original sender sees it in history.
	e.expect(req{method: "POST", path: "/v1/device/acks", token: dB.token, body: map[string]any{"delivered": []string{created[frameB]}}}, 204)
	if ev := benWS.await("share.delivered", 5*time.Second); ev["frame_id"] != frameB {
		t.Fatalf("share.delivered: %v", ev)
	}
	userWS.awaitWhere("send.updated", 5*time.Second, func(m map[string]any) bool { return dig(m, "send", "id") == sendID })
	// Reactions on the shared copy go to the original sender.
	e.expect(req{method: "PUT", path: "/v1/device/placements/" + created[frameB] + "/reaction", token: dB.token, body: map[string]any{"reaction": "love"}}, 204)
	if rx := userWS.await("reaction", 5*time.Second); rx["placement_id"] != created[frameB] {
		t.Fatalf("reaction to original sender: %v", rx)
	}

	// --- moderation on D: pending until Dave approves, invisible to the frame meanwhile.
	md := e.share(ben, frameA, []string{srcID}, []string{frameD}, "", 200)
	pD := md["placements"].([]any)[0].(map[string]any)
	if pD["pending_moderation"] != true {
		t.Fatalf("moderated share: %v", pD)
	}
	if _, ok := placementUpserts(e.get("/v1/device/changes?since=0", dD.token).m(t))[pD["id"].(string)]; ok {
		t.Fatal("pending placement reached the frame")
	}
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frameD + "/placements/" + pD["id"].(string), token: dave.token, body: map[string]any{"approved": true}}, 200)
	eventually(t, 30*time.Second, "approved share on D", func() bool {
		p := placementUpserts(e.get("/v1/device/changes?since=0", dD.token).m(t))[pD["id"].(string)]
		return p != nil && p["media"] != nil
	})

	// --- the sender's own placement: Carl (no see_library on A) shares what he sent.
	e.setPerms(anna, frameA, carl, map[string]any{"see_library": false})
	carlSend := e.sendOne(carl, []string{frameA}, map[string]any{"upload_id": e.upload(carl, mediatest.JPEG(800, 600, 0, "", false), "c.jpg", "image/jpeg")})
	e.waitSend(carl, carlSend)
	carlLib := e.get("/v1/frames/"+frameA+"/placements", carl.token).m(t)["placements"].([]any)
	if len(carlLib) != 1 {
		t.Fatalf("carl sees only his own: %v", carlLib)
	}
	carlPID := carlLib[0].(map[string]any)["id"].(string)
	if r := e.share(carl, frameA, []string{srcID}, []string{frameD}, "", 404); r["code"] != "not_found" {
		t.Fatalf("carl sharing anna's photo: %v", r)
	}
	e.share(carl, frameA, []string{carlPID}, []string{frameD}, "", 200)

	// --- removal: the sharer can take back what he shared (undo), not other placements.
	e.setPerms(dave, frameD, ben, map[string]any{"manage_library": false})
	dLib := e.get("/v1/frames/"+frameD+"/placements?visibility=all", dave.token).m(t)["placements"].([]any)
	var carlOnD string
	for _, p := range dLib {
		if p.(map[string]any)["shared_by_user_id"] == nil {
			continue
		}
		if dig(p.(map[string]any), "item", "sender_name") == "Carl" {
			carlOnD = p.(map[string]any)["id"].(string)
		}
	}
	bulk := e.expect(req{method: "POST", path: "/v1/frames/" + frameD + "/placements:bulk", token: ben.token,
		body: map[string]any{"placement_ids": []string{pD["id"].(string), carlOnD}, "action": "purge"}}, 200).m(t)
	if len(bulk["ok"].([]any)) != 1 || bulk["ok"].([]any)[0] != pD["id"] || len(bulk["failed"].([]any)) != 1 {
		t.Fatalf("sharer purge: %v", bulk)
	}

	// --- unsend by the original sender removes the shared copies too.
	sinceB := e.get("/v1/device/changes?since=0", dB.token).m(t)["next_seq"].(float64)
	e.expect(req{method: "DELETE", path: "/v1/sends/" + sendID, token: anna.token}, 204)
	if rm := changesOf(e.get("/v1/device/changes?since="+ftoa(sinceB), dB.token).m(t), "placement.remove"); len(rm) != 1 || rm[0]["id"] != created[frameB] {
		t.Fatalf("unsend cascade on B: %v", rm)
	}
	var live int
	if err := e.app.Pool.QueryRow(context.Background(), `SELECT count(*) FROM placements WHERE item_id = $1 AND removed_at IS NULL`, itemID).Scan(&live); err != nil || live != 0 {
		t.Fatalf("live placements after unsend: %d %v", live, err)
	}
}
