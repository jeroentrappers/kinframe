package e2e

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

// TestTvLinks: share tokens for the TV page and Cast senders.
func TestTvLinks(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	owner := e.user("tv-owner@x.org", "Olga")
	d, frameID := e.claim(owner)
	var placements []string
	for i, caption := range []string{"first", "second", "third"} {
		up := e.upload(owner, mediatest.JPEG(640+i, 480, 1, "", false), caption+".jpg", "image/jpeg")
		e.waitSend(owner, e.sendOne(owner, []string{frameID}, map[string]any{"upload_id": up, "caption": caption}))
	}
	lib := e.get("/v1/frames/"+frameID+"/placements?sort=received_asc", owner.token).m(t)
	for _, p := range lib["placements"].([]any) {
		placements = append(placements, p.(map[string]any)["id"].(string))
	}

	// The whole library; the token is all a TV needs.
	link := e.expect(req{method: "POST", path: "/v1/frames/" + frameID + "/tv-links", token: owner.token,
		body: map[string]any{"interval_seconds": 15, "expires_in_hours": 2}}, 201).m(t)
	if !strings.HasPrefix(link["url"].(string), e.url+"/tv/"+frameID+"?t=") || link["interval_seconds"] != float64(15) {
		t.Fatalf("link: %v", link)
	}
	token := link["token"].(string)
	show := e.get("/v1/tv/"+token, "").m(t)
	slides := show["slides"].([]any)
	if len(slides) != 3 || show["frame_name"] == "" || show["interval_seconds"] != float64(15) {
		t.Fatalf("slideshow: %v", show)
	}
	first := slides[0].(map[string]any)
	if first["caption"] != "third" || first["sender_name"] != "Olga" || first["mime"] != "image/webp" {
		t.Fatalf("slide: %v", first)
	}
	// Slide URLs are absolute, signed and fetchable without headers (what a Chromecast does).
	u := first["url"].(string)
	if !strings.HasPrefix(u, e.url+"/v1/media/signed/") {
		t.Fatalf("slide url %q", u)
	}
	img := e.get(u, "")
	if k, mime := media.SniffBytes(img.body); k != media.Photo || mime != "image/webp" {
		t.Fatalf("slide media %s", mime)
	}

	// A selection keeps its order; hidden items drop out.
	sel := e.expect(req{method: "POST", path: "/v1/frames/" + frameID + "/tv-links", token: owner.token,
		body: map[string]any{"placement_ids": []string{placements[0], placements[2], placements[1]}, "purpose": "cast"}}, 201).m(t)
	if sel["placement_count"] != float64(3) || sel["purpose"] != "cast" {
		t.Fatalf("selection link: %v", sel)
	}
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frameID + "/placements/" + placements[2], token: owner.token, body: map[string]any{"hidden": true}}, 200)
	ss := e.get("/v1/tv/"+sel["token"].(string), "").m(t)["slides"].([]any)
	if len(ss) != 2 || ss[0].(map[string]any)["placement_id"] != placements[0] || ss[1].(map[string]any)["placement_id"] != placements[1] {
		t.Fatalf("selection slides: %v", ss)
	}

	// A member without see_library may only share their own placements.
	member := e.user("tv-member@x.org", "Mo")
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: d.token}, 200).m(t)["code"].(string)
	e.expect(req{method: "POST", path: "/v1/frames/redeem", token: member.token, body: map[string]any{"code": code}}, 200)
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frameID + "/members/" + member.id, token: owner.token,
		body: map[string]any{"permissions": map[string]any{"see_library": false}}}, 200)
	if r := e.do(req{method: "POST", path: "/v1/frames/" + frameID + "/tv-links", token: member.token, body: map[string]any{}}); r.StatusCode != 403 || r.code() != "missing_permission" {
		t.Fatalf("member library link: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/frames/" + frameID + "/tv-links", token: member.token,
		body: map[string]any{"placement_ids": []string{placements[0]}}}); r.StatusCode != 403 {
		t.Fatalf("member foreign selection: %d %s", r.StatusCode, r.body)
	}
	// Members see only their own links; the owner sees all of them.
	var mine []map[string]any
	if e.get("/v1/frames/"+frameID+"/tv-links", member.token).json(t, &mine); len(mine) != 0 {
		t.Fatalf("member links: %v", mine)
	}
	var all []map[string]any
	e.get("/v1/frames/"+frameID+"/tv-links", owner.token).json(t, &all)
	if len(all) != 2 {
		t.Fatalf("owner links: %v", all)
	}
	if r := e.do(req{method: "DELETE", path: "/v1/frames/" + frameID + "/tv-links/" + link["id"].(string), token: member.token}); r.StatusCode != 403 {
		t.Fatalf("member revoke: %d", r.StatusCode)
	}

	// Revoked and expired links stop working; bad tokens look the same as revoked ones.
	e.expect(req{method: "DELETE", path: "/v1/frames/" + frameID + "/tv-links/" + link["id"].(string), token: owner.token}, 204)
	if r := e.do(req{method: "GET", path: "/v1/tv/" + token}); r.StatusCode != 404 || r.code() != "not_found" {
		t.Fatalf("revoked: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "GET", path: "/v1/tv/" + url.PathEscape(strings.Repeat("x", 32))}); r.StatusCode != 404 {
		t.Fatalf("unknown token: %d", r.StatusCode)
	}
	if _, err := e.app.Pool.Exec(context.Background(), `UPDATE tv_links SET expires_at = now() - interval '1 minute' WHERE id = $1`, sel["id"]); err != nil {
		t.Fatal(err)
	}
	if r := e.do(req{method: "GET", path: "/v1/tv/" + sel["token"].(string)}); r.StatusCode != 410 || r.code() != "link_expired" {
		t.Fatalf("expired: %d %s", r.StatusCode, r.body)
	}
	var left []map[string]any
	e.get("/v1/frames/"+frameID+"/tv-links", owner.token).json(t, &left)
	if len(left) != 0 {
		t.Fatalf("active links after revoke/expiry: %v", left)
	}
	// Validation: interval bounds come from the contract.
	if r := e.do(req{method: "POST", path: "/v1/frames/" + frameID + "/tv-links", token: owner.token, body: map[string]any{"interval_seconds": 1}}); r.StatusCode != 400 && r.StatusCode != 422 {
		t.Fatalf("interval validation: %d", r.StatusCode)
	}
}

// TestImportDedupe: the frame can ask which files it already has, and a
// re-import (or a restore from its own export) returns the existing placement.
func TestImportDedupe(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	u := e.user("dedupe@x.org", "Dirk")
	d, _ := e.claim(u)
	photo := mediatest.JPEG(800, 600, 0, "", false)
	sum := sha256.Sum256(photo)
	sha := hex.EncodeToString(sum[:])
	other := strings.Repeat("ab", 32)

	check := func() []any {
		return e.expect(req{method: "POST", path: "/v1/device/imports/check", token: d.token,
			body: map[string]any{"sha256": []string{sha, other}}}, 200).m(t)["known"].([]any)
	}
	if k := check(); len(k) != 0 {
		t.Fatalf("known before import: %v", k)
	}
	hdr := map[string]string{"Content-Type": "application/octet-stream", "X-Filename": "2024-05-01_Oma_beach.jpg"}
	imp := e.expect(req{method: "POST", path: "/v1/device/imports?caption=" + url.QueryEscape("Strand – zomer"), token: d.token, raw: photo, header: hdr}, 202).m(t)
	if dig(imp, "item", "caption") != "Strand – zomer" {
		t.Fatalf("caption: %v", imp)
	}
	// Known right away (before processing finishes), so a quick second pass skips it.
	if k := check(); len(k) != 1 || k[0] != sha {
		t.Fatalf("known after import: %v", k)
	}
	again := e.expect(req{method: "POST", path: "/v1/device/imports", token: d.token, raw: photo, header: hdr}, 200).m(t)
	if again["id"] != imp["id"] {
		t.Fatalf("duplicate import created %v, want %v", again["id"], imp["id"])
	}

	// After processing, the frame rendition's hash is known too: that is what a
	// kinframe export on a stick contains.
	var rendition string
	eventually(t, 30*time.Second, "import processed", func() bool {
		ch := e.get("/v1/device/changes?since=0", d.token).m(t)
		ups := changesOf(ch, "placement.upsert")
		if len(ups) != 1 {
			return false
		}
		s, _ := dig(ups[0], "placement", "media", "sha256").(string)
		rendition = s
		return s != ""
	})
	k := e.expect(req{method: "POST", path: "/v1/device/imports/check", token: d.token,
		body: map[string]any{"sha256": []string{rendition}}}, 200).m(t)["known"].([]any)
	if len(k) != 1 {
		t.Fatalf("rendition hash not known: %v", k)
	}
	if r := e.do(req{method: "POST", path: "/v1/device/imports/check", token: d.token, body: map[string]any{"sha256": []string{"nothex"}}}); r.StatusCode != 400 && r.StatusCode != 422 {
		t.Fatalf("bad hash: %d", r.StatusCode)
	}
}
