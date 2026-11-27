package e2e

import (
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

// Framing: zoom and position per orientation (docs/spec/framing.md).
func TestFraming(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	u := e.user("fr@x.org", "Fien")
	d, frameID := e.claim(u)

	if dig(e.get("/.well-known/kinframe", "").m(t), "framing") != true {
		t.Fatal("discovery does not announce framing")
	}

	img := mediatest.JPEG(1600, 1200, 1, "", false)
	up := e.upload(u, img, "f.jpg", "image/jpeg")
	// Out of range: 422, nothing sent.
	e.expect(req{method: "POST", path: "/v1/sends", token: u.token, body: map[string]any{"frame_ids": []string{frameID},
		"items": []map[string]any{{"upload_id": up, "framing": map[string]any{"landscape": map[string]any{"x": 0.5, "y": 0.5, "zoom": 5}}}}}}, 422)

	// Rounded to 3 decimals; without focal_point the focal point is the landscape view's centre (§2.1).
	sendID := e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up,
		"framing": map[string]any{"landscape": map[string]any{"x": 0.33333, "y": 0.25, "zoom": 2}}})
	item := e.waitSend(u, sendID)["items"].([]any)[0].(map[string]any)
	if dig(item, "framing", "landscape", "x") != 0.333 || dig(item, "framing", "landscape", "zoom") != float64(2) ||
		dig(item, "framing", "portrait") != nil {
		t.Fatalf("framing: %v", item["framing"])
	}
	if dig(item, "focal_point", "x") != 0.333 || dig(item, "focal_point", "y") != 0.25 {
		t.Fatalf("focal point not derived: %v", item["focal_point"])
	}
	itemID := item["id"].(string)

	// The frame gets it with the placement.
	ch := e.get("/v1/device/changes?since=0", d.token).m(t)
	ups := changesOf(ch, "placement.upsert")
	if len(ups) != 1 || dig(ups[0], "placement", "item", "framing", "landscape", "y") != 0.25 {
		t.Fatalf("framing not on the frame: %v", ch)
	}
	pid := dig(ups[0], "placement", "id").(string)

	// Change it later: a portrait view only, with an explicit focal point that stays.
	ed := e.expect(req{method: "PATCH", path: "/v1/items/" + itemID, token: u.token, body: map[string]any{
		"focal_point": map[string]any{"x": 0.1, "y": 0.9},
		"framing":     map[string]any{"portrait": map[string]any{"x": 0.6, "y": 0.4, "zoom": 1.5}}}}, 200).m(t)
	if dig(ed, "framing", "landscape") != nil || dig(ed, "framing", "portrait", "zoom") != 1.5 || dig(ed, "focal_point", "x") != 0.1 {
		t.Fatalf("framing edit: %v", ed)
	}
	// Leaving framing out keeps it; null clears it.
	ed = e.expect(req{method: "PATCH", path: "/v1/items/" + itemID, token: u.token, body: map[string]any{"caption": "hi"}}, 200).m(t)
	if dig(ed, "framing", "portrait", "x") != 0.6 {
		t.Fatalf("framing lost on a caption edit: %v", ed)
	}
	e.expect(req{method: "PATCH", path: "/v1/items/" + itemID, token: u.token, body: map[string]any{"framing": map[string]any{"portrait": map[string]any{"x": 2, "y": 0, "zoom": 1}}}}, 422)
	ed = e.expect(req{method: "PATCH", path: "/v1/items/" + itemID, token: u.token, body: map[string]any{"framing": nil}}, 200).m(t)
	if ed["framing"] != nil {
		t.Fatalf("framing not cleared: %v", ed)
	}

	// The frame owner's override, at the frame.
	pl := e.expect(req{method: "PATCH", path: "/v1/device/placements/" + pid, token: d.token, body: map[string]any{
		"framing": map[string]any{"landscape": map[string]any{"x": 0.7, "y": 0.5, "zoom": 3}}}}, 200).m(t)
	if dig(pl, "framing_override", "landscape", "zoom") != float64(3) {
		t.Fatalf("override: %v", pl)
	}
	pl = e.expect(req{method: "PATCH", path: "/v1/device/placements/" + pid, token: d.token, body: map[string]any{"hidden": false}}, 200).m(t)
	if dig(pl, "framing_override", "landscape", "x") != 0.7 {
		t.Fatalf("override lost: %v", pl)
	}
	pl = e.expect(req{method: "PATCH", path: "/v1/device/placements/" + pid, token: d.token, body: map[string]any{"framing": nil}}, 200).m(t)
	if pl["framing_override"] != nil {
		t.Fatalf("override not cleared: %v", pl)
	}
}
