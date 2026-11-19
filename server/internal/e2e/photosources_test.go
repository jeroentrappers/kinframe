package e2e

import (
	"context"
	"fmt"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
	"github.com/jeroentrappers/kinframe/server/internal/photosource/photosourcetest"
)

// Photo sources and Google Photos imports (docs/spec/photo-sources.md) over
// real HTTP, with fake Immich, iCloud and Google servers.

func withPhotoSources(c *config.Config) {
	c.PhotoSources, c.ICloudSources = true, true
	c.PhotoSourceSync = 30 * time.Minute
}

func withGoogle(c *config.Config) {
	withPhotoSources(c)
	c.GoogleClientID, c.GoogleClientSecret = "client-id", "client-secret"
}

func pic(seed int) []byte { return mediatest.JPEG(400+seed, 300, 0, "", false) }

// settle waits for running syncs (and Google imports) to finish.
func (e *env) settle() {
	e.app.API.PhotoScheduler().Wait()
	e.app.API.WaitGoogleImports()
}

// syncNow runs a source's sync in the test goroutine (bypassing the sync-now rate limit).
func (e *env) syncNow(id string) {
	e.t.Helper()
	e.settle()
	e.app.API.RunPhotoSource(context.Background(), uuid.MustParse(id))
	e.settle()
}

func (e *env) source(u *user, id string) map[string]any {
	e.t.Helper()
	return e.get("/v1/photo-sources/"+id, u.token).m(e.t)
}

func (e *env) album(u *user, frameID, name string) string {
	e.t.Helper()
	return e.expect(req{method: "POST", path: "/v1/frames/" + frameID + "/albums", token: u.token, body: map[string]any{"name": name}}, 201).m(e.t)["id"].(string)
}

func (e *env) posts(feed, token string) []map[string]any {
	e.t.Helper()
	var out []map[string]any
	for _, p := range e.get("/v1/feeds/"+feed+"/posts", token).m(e.t)["posts"].([]any) {
		out = append(out, p.(map[string]any))
	}
	return out
}

func count(src map[string]any, k string) int {
	v, _ := dig(src, "counts", k).(float64)
	return int(v)
}

func newImmich(t *testing.T, n int) *photosourcetest.Immich {
	f := photosourcetest.NewImmich()
	t.Cleanup(f.Close)
	t0 := time.Date(2026, 6, 1, 10, 0, 0, 0, time.UTC)
	for i := range n {
		f.AddAsset("garden", &photosourcetest.ImmichAsset{ID: fmt.Sprintf("a%d", i), Mime: "image/jpeg", Original: pic(i),
			TakenAt: t0.Add(time.Duration(i) * time.Hour), TimeZone: "Europe/Brussels", Caption: fmt.Sprintf("Photo %d", i)})
	}
	f.Lock(func() {
		if f.Albums["garden"] == nil {
			f.Albums["garden"] = &photosourcetest.ImmichAlbum{ID: "garden"}
		}
		f.Albums["garden"].Name = "Garden 2026"
		f.Links["k1"] = &photosourcetest.ImmichLink{AlbumID: "garden", AllowDownload: true, ShowMetadata: true}
		f.Keys["key-full"] = nil
	})
	return f
}

func TestPhotoSourceImmichAlbum(t *testing.T) {
	requireMedia(t)
	e := newEnv(t, withPhotoSources)
	anna, ben, carl := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carl@x.org", "Carl")
	d, frame := e.claim(anna)
	e.heartbeat(d, "feed_backfill")
	e.join(ben, d)
	e.setPerms(anna, frame, ben, map[string]any{"send": true, "see_library": true, "manage_library": false})
	garden := e.album(anna, frame, "Garden")
	f := newImmich(t, 3)

	disc := e.get("/.well-known/kinframe", "").m(t)
	if disc["photo_sources"] != true || len(disc["photo_source_providers"].([]any)) != 3 || disc["photo_sources_private_networks"] != true ||
		disc["google_photos_import"] != false || dig(disc, "limits", "max_photo_sources_per_target") != float64(5) {
		t.Fatalf("discovery: %v", disc)
	}

	// Test first: name, counts, no warnings.
	test := e.expect(req{method: "POST", path: "/v1/photo-sources:test", token: anna.token,
		body: map[string]any{"provider": "immich_link", "url": f.ShareURL("k1")}}, 200).m(t)
	if test["ok"] != true || test["album_name"] != "Garden 2026" || test["photos"] != float64(3) || len(test["warnings"].([]any)) != 0 {
		t.Fatalf("test: %v", test)
	}
	bad := e.expect(req{method: "POST", path: "/v1/photo-sources:test", token: anna.token,
		body: map[string]any{"provider": "immich_link", "url": f.ShareURL("nope")}}, 200).m(t)
	if bad["ok"] != false || bad["error_code"] != "link_not_found" {
		t.Fatalf("bad link test: %v", bad)
	}
	// Immich API key: list the albums.
	al := e.expect(req{method: "POST", path: "/v1/photo-sources:immich-albums", token: anna.token,
		body: map[string]any{"server_url": f.URL + "/api", "api_key": "key-full"}}, 200).m(t)
	if al["ok"] != true || al["server_version"] != "v1.132.3" || len(al["albums"].([]any)) != 1 {
		t.Fatalf("immich albums: %v", al)
	}

	// Permissions: Ben may see the library but not manage it.
	body := map[string]any{"provider": "immich_link", "url": f.ShareURL("k1"), "album_id": garden}
	if r := e.do(req{method: "POST", path: "/v1/photo-sources", token: ben.token, body: body}); r.StatusCode != 403 || r.code() != "missing_permission" {
		t.Fatalf("member creates: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/photo-sources", token: carl.token, body: body}); r.StatusCode != 404 {
		t.Fatalf("stranger creates: %d", r.StatusCode)
	}
	if r := e.do(req{method: "POST", path: "/v1/photo-sources", token: anna.token, body: map[string]any{"provider": "immich_link",
		"url": f.ShareURL("gone"), "album_id": garden}}); r.StatusCode != 422 || r.code() != "source_test_failed" || !strings.Contains(string(r.body), "link_not_found") {
		t.Fatalf("broken link: %d %s", r.StatusCode, r.body)
	}
	annaWS := e.dialWS("/v1/ws?access_token="+anna.token, "")
	src := e.expect(req{method: "POST", path: "/v1/photo-sources", token: anna.token, body: body}, 201).m(t)
	id := src["id"].(string)
	if src["running"] != true || src["history"] != "all" || src["on_upstream_delete"] != "mirror" || src["name"] != "Garden 2026" ||
		src["url_hint"] != f.URL+"/…" || src["is_owner"] != true || src["can_manage"] != true || src["best_effort"] != false {
		t.Fatalf("created: %v", src)
	}
	if strings.Contains(fmt.Sprint(src), "k1") {
		t.Fatalf("the link leaked: %v", src)
	}
	annaWS.awaitWhere("photo_source.updated", 10*time.Second, func(m map[string]any) bool { return dig(m, "source", "id") == id })
	if r := e.do(req{method: "POST", path: "/v1/photo-sources", token: anna.token, body: body}); r.StatusCode != 409 || r.code() != "duplicate_source" {
		t.Fatalf("duplicate: %d %s", r.StatusCode, r.body)
	}
	e.settle()
	src = e.source(anna, id)
	if src["status"] != "active" || count(src, "imported") != 3 || count(src, "pending") != 0 || dig(src, "counts", "upstream") != float64(3) || src["last_synced_at"] == nil {
		t.Fatalf("after first run: %v", src)
	}
	// The album shows the photos, quietly (history), credited via Immich.
	pls := e.library(anna, frame, "&album_id="+garden)
	if len(pls) != 3 {
		t.Fatalf("album placements: %d", len(pls))
	}
	for _, p := range pls {
		if p["backfill"] != true || dig(p, "item", "via", "service") != "immich" || dig(p, "item", "via", "label") != "Immich" ||
			dig(p, "item", "via", "source_id") != id || dig(p, "item", "sender_name") != "Anna" {
			t.Fatalf("placement: %v", p)
		}
	}
	if c := dig(pls[0], "item", "captured_at"); c == nil {
		t.Fatalf("capture time missing: %v", pls[0])
	}
	sends := e.get("/v1/sends", anna.token).m(t)["sends"].([]any)
	if len(sends) != 1 || dig(sends[0].(map[string]any), "via", "service") != "immich" {
		t.Fatalf("sends: %v", sends)
	}
	albums := e.list("/v1/frames/"+frame+"/albums", ben.token)
	if albums[0]["photo_source_count"] != float64(1) {
		t.Fatalf("album: %v", albums[0])
	}
	if r := e.do(req{method: "GET", path: "/v1/frames/" + frame + "/albums/" + garden + "/photo-sources", token: ben.token}); r.StatusCode != 403 {
		t.Fatalf("member lists sources: %d", r.StatusCode)
	}
	if r := e.do(req{method: "GET", path: "/v1/photo-sources/" + id, token: ben.token}); r.StatusCode != 404 {
		t.Fatalf("member gets source: %d", r.StatusCode)
	}
	if l := e.list("/v1/frames/"+frame+"/albums/"+garden+"/photo-sources", anna.token); len(l) != 1 {
		t.Fatalf("list: %v", l)
	}
	if l := e.list("/v1/photo-sources", anna.token); len(l) != 1 {
		t.Fatalf("my sources: %v", l)
	}

	// A new photo upstream is a new photo here (not quiet); a re-run imports nothing twice.
	f.AddAsset("garden", &photosourcetest.ImmichAsset{ID: "new", Mime: "image/jpeg", Original: pic(10), TakenAt: time.Now(), TimeZone: "UTC"})
	sync := e.expect(req{method: "POST", path: "/v1/photo-sources/" + id + "/sync", token: anna.token}, 202).m(t)
	if sync["running"] != true {
		t.Fatalf("sync now: %v", sync)
	}
	if r := e.do(req{method: "POST", path: "/v1/photo-sources/" + id + "/sync", token: anna.token}); r.StatusCode != 429 || r.Header.Get("Retry-After") == "" {
		t.Fatalf("sync again: %d", r.StatusCode)
	}
	e.settle()
	e.syncNow(id)
	pls = e.library(anna, frame, "&album_id="+garden)
	if len(pls) != 4 {
		t.Fatalf("after new photo: %d", len(pls))
	}
	fresh := 0
	for _, p := range pls {
		if p["backfill"] == false {
			fresh++
		}
	}
	if fresh != 1 {
		t.Fatalf("new photos not quiet: %d", fresh)
	}

	// Edits: an upstream caption follows, unless someone edited it here.
	f.Edit("a0", func(a *photosourcetest.ImmichAsset) { a.Caption = "Roses in June" })
	f.Edit("a1", func(a *photosourcetest.ImmichAsset) { a.Caption = "Tulips" })
	var a1Item string
	for _, p := range pls {
		if dig(p, "item", "caption") == "Photo 1" {
			a1Item = dig(p, "item", "id").(string)
		}
	}
	e.expect(req{method: "PATCH", path: "/v1/items/" + a1Item, token: anna.token, body: map[string]any{"caption": "Mine"}}, 200)
	e.syncNow(id)
	caps := map[string]bool{}
	for _, p := range e.library(anna, frame, "&album_id="+garden) {
		caps[fmt.Sprint(dig(p, "item", "caption"))] = true
	}
	if !caps["Roses in June"] || !caps["Mine"] || caps["Tulips"] {
		t.Fatalf("captions: %v", caps)
	}

	// Removing a synced photo here: never imported again.
	var purgeID string
	for _, p := range e.library(anna, frame, "&album_id="+garden) {
		if dig(p, "item", "caption") == "Photo 2" {
			purgeID = p["id"].(string)
		}
	}
	e.expect(req{method: "DELETE", path: "/v1/frames/" + frame + "/placements/" + purgeID, token: anna.token}, 204)
	e.syncNow(id)
	src = e.source(anna, id)
	if count(src, "removed_locally") != 1 || len(e.library(anna, frame, "&album_id="+garden)) != 3 {
		t.Fatalf("purged photo came back: %v", src["counts"])
	}

	// Mirror: a photo removed upstream goes here too.
	f.RemoveAsset("garden", "new")
	e.syncNow(id)
	if n := len(e.library(anna, frame, "&album_id="+garden)); n != 2 {
		t.Fatalf("mirror removal: %d", n)
	}
	if src = e.source(anna, id); count(src, "gone_upstream") != 1 || dig(src, "last_run", "removed") != float64(1) {
		t.Fatalf("after removal: %v", src)
	}

	// Pause and resume; repeating is a no-op.
	if p := e.expect(req{method: "PATCH", path: "/v1/photo-sources/" + id, token: anna.token, body: map[string]any{"paused": true}}, 200).m(t); p["status"] != "paused" || p["next_sync_at"] != nil {
		t.Fatalf("pause: %v", p)
	}
	e.expect(req{method: "PATCH", path: "/v1/photo-sources/" + id, token: anna.token, body: map[string]any{"paused": true}}, 200)
	if r := e.do(req{method: "POST", path: "/v1/photo-sources/" + id + "/sync", token: anna.token}); r.StatusCode != 409 || r.code() != "invalid_transition" {
		t.Fatalf("sync paused: %d %s", r.StatusCode, r.body)
	}
	if p := e.expect(req{method: "PATCH", path: "/v1/photo-sources/" + id, token: anna.token, body: map[string]any{"paused": false}}, 200).m(t); p["status"] != "active" {
		t.Fatalf("resume: %v", p)
	}
	e.settle()

	// Delete the album asking to remove the synced photos: they leave the frame, the source goes.
	removed := 0
	e.expect(req{method: "DELETE", path: "/v1/frames/" + frame + "/albums/" + garden + "?remove_source_items=true", token: anna.token}, 204)
	annaWS.awaitWhere("photo_source.removed", 5*time.Second, func(m map[string]any) bool { return m["source_id"] == id })
	for _, p := range e.library(anna, frame, "") {
		if dig(p, "item", "via") != nil {
			removed++
		}
	}
	if removed != 0 {
		t.Fatalf("source photos left after album deletion: %d", removed)
	}
	if r := e.do(req{method: "GET", path: "/v1/photo-sources/" + id, token: anna.token}); r.StatusCode != 404 {
		t.Fatalf("source after album deletion: %d", r.StatusCode)
	}
}

func TestPhotoSourceMassRemovalAndTakeOver(t *testing.T) {
	requireMedia(t)
	e := newEnv(t, withPhotoSources)
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	d, frame := e.claim(anna)
	e.join(ben, d)
	e.setPerms(anna, frame, ben, map[string]any{"send": true, "see_library": true, "manage_library": true})
	garden := e.album(anna, frame, "Garden")
	f := newImmich(t, 24)
	id := e.expect(req{method: "POST", path: "/v1/photo-sources", token: ben.token,
		body: map[string]any{"provider": "immich_link", "url": f.ShareURL("k1"), "album_id": garden}}, 201).m(t)["id"].(string)
	e.settle()
	if n := len(e.library(anna, frame, "&album_id="+garden)); n != 24 {
		t.Fatalf("imported %d", n)
	}
	// Anna (owner) sees Ben's source and may manage it, but not its reaction switch.
	if s := e.source(anna, id); s["can_manage"] != true || s["is_owner"] != false || dig(s, "owner", "display_name") != "Ben" {
		t.Fatalf("anna's view: %v", s)
	}
	if r := e.do(req{method: "PATCH", path: "/v1/photo-sources/" + id, token: anna.token, body: map[string]any{"notify_reactions": false}}); r.StatusCode != 403 {
		t.Fatalf("not the owner's switch: %d", r.StatusCode)
	}

	// The whole album disappears upstream: nothing is removed until confirmed.
	f.Lock(func() { f.Albums["garden"].AssetIDs = f.Albums["garden"].AssetIDs[:2] })
	e.syncNow(id)
	s := e.source(ben, id)
	if s["status"] != "needs_attention" || s["last_error_code"] != "mass_removal" || s["pending_removals"] != float64(22) || s["next_sync_at"] != nil {
		t.Fatalf("mass removal: %v", s)
	}
	if n := len(e.library(anna, frame, "&album_id="+garden)); n != 24 {
		t.Fatalf("removed without confirmation: %d", n)
	}
	e.expect(req{method: "POST", path: "/v1/photo-sources/" + id + "/sync", token: ben.token, body: map[string]any{"confirm_removals": true}}, 202)
	e.settle()
	if n := len(e.library(anna, frame, "&album_id="+garden)); n != 2 {
		t.Fatalf("after confirmation: %d", n)
	}
	if s = e.source(ben, id); s["status"] != "active" || s["pending_removals"] != float64(0) {
		t.Fatalf("after confirmation: %v", s)
	}

	// Ben loses manage_library: the next run stops with owner_lost_access; Anna takes over.
	e.setPerms(anna, frame, ben, map[string]any{"send": true, "see_library": true, "manage_library": false})
	e.syncNow(id)
	if s = e.source(anna, id); s["status"] != "needs_attention" || s["last_error_code"] != "owner_lost_access" {
		t.Fatalf("lost access: %v", s)
	}
	if r := e.do(req{method: "GET", path: "/v1/photo-sources/" + id, token: ben.token}); r.StatusCode != 200 {
		t.Fatalf("the owner still sees it: %d", r.StatusCode)
	}
	if r := e.do(req{method: "PATCH", path: "/v1/photo-sources/" + id, token: ben.token, body: map[string]any{"paused": true}}); r.StatusCode != 403 {
		t.Fatalf("owner without rights manages: %d", r.StatusCode)
	}
	to := e.expect(req{method: "PATCH", path: "/v1/photo-sources/" + id, token: anna.token, body: map[string]any{"take_over": true}}, 200).m(t)
	if to["is_owner"] != true || to["status"] != "active" || dig(to, "owner", "display_name") != "Anna" {
		t.Fatalf("take over: %v", to)
	}
	e.settle()
	// Delete keeping the photos: they stay, still credited.
	e.expect(req{method: "DELETE", path: "/v1/photo-sources/" + id + "?remove_items=false", token: anna.token}, 204)
	pls := e.library(anna, frame, "&album_id="+garden)
	if len(pls) != 2 || dig(pls[0], "item", "via", "service") != "immich" || dig(pls[0], "item", "via", "source_id") != nil {
		t.Fatalf("kept photos: %v", pls)
	}
}

func TestPhotoSourceFeedDebounce(t *testing.T) {
	requireMedia(t)
	e := newEnv(t, withPhotoSources)
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters kids"})
	e.joinFeed(anna, ben, feed)
	d, oma := e.claim(anna)
	e.heartbeat(d, "feed_backfill")
	e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/subscriptions", token: anna.token, body: map[string]any{"frame_id": oma, "history": "all"}}, 201)
	f := newImmich(t, 3)

	// Posters can't add or list sources.
	if r := e.do(req{method: "POST", path: "/v1/photo-sources", token: ben.token, body: map[string]any{"provider": "immich_link", "url": f.ShareURL("k1"), "feed_id": feed}}); r.StatusCode != 403 {
		t.Fatalf("poster creates: %d", r.StatusCode)
	}
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed + "/photo-sources", token: ben.token}); r.StatusCode != 403 {
		t.Fatalf("poster lists: %d", r.StatusCode)
	}
	t0 := time.Now().UTC().Truncate(time.Second)
	e.setClock(t0)
	src := e.expect(req{method: "POST", path: "/v1/photo-sources", token: anna.token,
		body: map[string]any{"provider": "immich_link", "url": f.ShareURL("k1"), "feed_id": feed, "history_count": 2}}, 201).m(t)
	id := src["id"].(string)
	if src["history"] != "last_n" || src["history_count"] != float64(2) || src["on_upstream_delete"] != "keep" {
		t.Fatalf("feed defaults: %v", src)
	}
	e.settle()
	posts := e.posts(feed, ben.token)
	if len(posts) != 1 || len(posts[0]["items"].([]any)) != 2 || dig(posts[0], "via", "label") != "Immich" {
		t.Fatalf("first post: %v", posts)
	}
	if s := e.source(anna, id); count(s, "imported") != 2 || count(s, "skipped") != 1 {
		t.Fatalf("history cut: %v", s["counts"])
	}
	eventually(t, 20*time.Second, "history placed quietly", func() bool {
		ps := e.library(anna, oma, "")
		return len(ps) == 2 && ps[0]["backfill"] == true
	})

	// New photos are held while the album keeps changing...
	add := func(n int) {
		f.AddAsset("garden", &photosourcetest.ImmichAsset{ID: fmt.Sprintf("n%d", n), Mime: "image/jpeg", Original: pic(100 + n), TakenAt: t0.Add(time.Duration(n) * time.Minute)})
	}
	add(1)
	add(2)
	e.syncNow(id)
	if p := e.posts(feed, ben.token); len(p) != 1 {
		t.Fatalf("posted before the album was quiet: %d posts", len(p))
	}
	s := e.source(anna, id)
	next, _ := time.Parse(time.RFC3339Nano, s["next_sync_at"].(string))
	if !next.Equal(t0.Add(30 * time.Minute)) {
		t.Fatalf("next run should be the flush time, got %v", next)
	}
	e.setClock(t0.Add(20 * time.Minute))
	add(3)
	e.syncNow(id)
	if p := e.posts(feed, ben.token); len(p) != 1 {
		t.Fatalf("posted while still changing: %d", len(p))
	}
	// ...until it has been quiet for 30 minutes: one post with all three.
	e.setClock(t0.Add(51 * time.Minute))
	e.syncNow(id)
	posts = e.posts(feed, ben.token)
	if len(posts) != 2 || len(posts[0]["items"].([]any)) != 3 || dig(posts[0], "via", "service") != "immich" {
		t.Fatalf("debounced post: %v", posts)
	}

	// A busy album: new photos every 20 minutes still post 3 hours after the first one was held.
	start := t0.Add(2 * time.Hour)
	for i := 0; i <= 9; i++ {
		e.setClock(start.Add(time.Duration(i*20) * time.Minute))
		add(10 + i)
		e.syncNow(id)
		want := 2
		if i == 9 { // 180 minutes after the first held photo
			want = 3
		}
		if p := e.posts(feed, ben.token); len(p) != want {
			t.Fatalf("step %d: %d posts, want %d", i, len(p), want)
		}
	}
	// Keep: a photo removed upstream stays in the feed.
	f.RemoveAsset("garden", "n1")
	e.setClock(start.Add(5 * time.Hour))
	e.syncNow(id)
	if s := e.source(anna, id); count(s, "gone_upstream") != 1 {
		t.Fatalf("keep: %v", s["counts"])
	}
	if p := e.posts(feed, ben.token); len(p) != 3 {
		t.Fatalf("keep removed a post: %d", len(p))
	}
	// Removing a source post item here: never posted again.
	post := e.posts(feed, anna.token)[0]
	item := post["items"].([]any)[0].(map[string]any)["id"].(string)
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/posts/" + post["id"].(string) + "/items/" + item, token: anna.token}, 204)
	e.syncNow(id)
	if s := e.source(anna, id); count(s, "removed_locally") != 1 {
		t.Fatalf("tombstone: %v", s["counts"])
	}
	// Deleting the feed deletes its sources.
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed, token: anna.token}, 204)
	if r := e.do(req{method: "GET", path: "/v1/photo-sources/" + id, token: anna.token}); r.StatusCode != 404 {
		t.Fatalf("source after feed deletion: %d", r.StatusCode)
	}
}

func TestPhotoSourceICloudAndAttention(t *testing.T) {
	requireMedia(t)
	e := newEnv(t, withPhotoSources, withUnifiedPush)
	anna := e.user("anna@x.org", "Anna")
	_, frame := e.claim(anna)
	album := e.album(anna, frame, "Oma & Opa")
	ic := photosourcetest.NewICloud()
	t.Cleanup(ic.Close)
	tok := "B0aGWZuqDGxyz"
	ic.Albums[tok] = &photosourcetest.ICloudAlbum{Name: "Oma & Opa", Ctag: "1", Photos: []*photosourcetest.ICloudPhoto{
		{GUID: "G1", Caption: "Beach", Created: time.Date(2026, 7, 1, 10, 0, 0, 0, time.UTC), BatchDate: time.Date(2026, 7, 3, 9, 0, 0, 0, time.UTC), File: pic(1)},
		{GUID: "G2", Created: time.Date(2026, 7, 1, 11, 0, 0, 0, time.UTC), BatchDate: time.Date(2026, 7, 3, 9, 0, 0, 0, time.UTC), File: pic(2)},
	}}
	_, icl, _ := e.app.API.PhotoProviders()
	icl.HostFormat, icl.Pinned, icl.Scheme = ic.Wrong.URL, []string{"127.0.0.1"}, "http"
	push := e.pushSink(t, anna)

	if r := e.do(req{method: "POST", path: "/v1/photo-sources:test", token: anna.token, body: map[string]any{"provider": "icloud_link",
		"url": "https://www.icloud.com/photos/#0abcdefGHIJ"}}); r.StatusCode != 200 || r.m(t)["error_code"] != "unsupported_link" {
		t.Fatalf("iCloud Link: %d %s", r.StatusCode, r.body)
	}
	src := e.expect(req{method: "POST", path: "/v1/photo-sources", token: anna.token,
		body: map[string]any{"provider": "icloud_link", "url": photosourcetest.Link(tok), "album_id": album}}, 201).m(t)
	id := src["id"].(string)
	if src["best_effort"] != true {
		t.Fatalf("best effort: %v", src)
	}
	e.settle()
	s := e.source(anna, id)
	if s["status"] != "active" || count(s, "imported") != 2 {
		t.Fatalf("icloud: %v", s)
	}
	pls := e.library(anna, frame, "&album_id="+album)
	if len(pls) != 2 || dig(pls[0], "item", "via", "label") != "iCloud" || !strings.HasPrefix(pls[0]["received_at"].(string), "2026-07-03") {
		t.Fatalf("icloud placements: %v", pls)
	}
	next, _ := time.Parse(time.RFC3339Nano, s["next_sync_at"].(string))
	if time.Until(next) < 59*time.Minute {
		t.Fatalf("iCloud syncs at most hourly: %v", next)
	}

	// Apple's answers stop making sense: needs attention, one push, nothing removed, a daily probe.
	ic.Lock(func() { ic.Mode = "nophotos" })
	e.syncNow(id)
	s = e.source(anna, id)
	if s["status"] != "needs_attention" || s["last_error_code"] != "format_changed" || s["next_sync_at"] == nil {
		t.Fatalf("format changed: %v", s)
	}
	m := push.next(t, "attention")
	if m.Key != "photo_source_attention" || m.IDs["source_id"] != id || m.IDs["album_id"] != album {
		t.Fatalf("attention push: %+v", m)
	}
	e.syncNow(id) // the probe fails again: no second push within a week
	push.none(t, 500*time.Millisecond)
	if n := len(e.library(anna, frame, "&album_id="+album)); n != 2 {
		t.Fatalf("errors removed photos: %d", n)
	}
	// The probe works again: back to active, silently.
	ic.Lock(func() { ic.Mode = "" })
	e.syncNow(id)
	if s = e.source(anna, id); s["status"] != "active" || s["last_error_code"] != nil {
		t.Fatalf("recovered: %v", s)
	}
	push.none(t, 300*time.Millisecond)

	// Transient errors: retried, `error` after 3 runs, a push only after 3 days.
	ic.Lock(func() { ic.Mode = "throttle" })
	t0 := time.Now().UTC()
	for i := range 3 {
		e.setClock(t0.Add(time.Duration(i) * time.Hour))
		e.syncNow(id)
	}
	if s = e.source(anna, id); s["status"] != "error" || s["last_error_code"] != "rate_limited_upstream" {
		t.Fatalf("error state: %v", s)
	}
	push.none(t, 300*time.Millisecond)
	e.setClock(t0.Add(73 * time.Hour))
	e.syncNow(id)
	if m := push.next(t, "3 days in error"); m.Key != "photo_source_attention" {
		t.Fatalf("error push: %+v", m)
	}
	e.setClock(t0.Add(80 * time.Hour))
	e.syncNow(id)
	push.none(t, 300*time.Millisecond)
}

func TestPhotoSourceLimitsAndGuard(t *testing.T) {
	e := newEnv(t, withPhotoSources, func(c *config.Config) { c.SourcesPrivateNetworks = "off" })
	anna := e.user("anna@x.org", "Anna")
	_, frame := e.claim(anna)
	album := e.album(anna, frame, "A")
	// A private address on a server that may not reach them: 422 on url.
	r := e.do(req{method: "POST", path: "/v1/photo-sources", token: anna.token,
		body: map[string]any{"provider": "immich_link", "url": "http://192.168.1.10:2283/share/abc", "album_id": album}})
	if r.StatusCode != 422 || r.code() != "validation_failed" || !strings.Contains(string(r.body), "private_address_blocked") {
		t.Fatalf("private address: %d %s", r.StatusCode, r.body)
	}
	if e.get("/.well-known/kinframe", "").m(t)["photo_sources_private_networks"] != false {
		t.Fatal("discovery private networks")
	}
	// Cloud metadata is never reachable.
	res := e.expect(req{method: "POST", path: "/v1/photo-sources:test", token: anna.token,
		body: map[string]any{"provider": "immich_api", "server_url": "http://169.254.169.254/latest", "api_key": "12345678"}}, 200).m(t)
	if res["ok"] != false || res["error_code"] != "private_address_blocked" {
		t.Fatalf("metadata service: %v", res)
	}
	// Operator limits.
	op := e.operator()
	l := e.get("/v1/operator/limits", op).m(t)
	l["max_photo_sources_per_target"] = 1
	if got := e.expect(req{method: "PUT", path: "/v1/operator/limits", token: op, body: l}, 200).m(t); got["max_photo_sources_per_target"] != float64(1) {
		t.Fatalf("limits: %v", got)
	}
	f := newImmich(t, 1)
	f.Lock(func() { f.Links["k2"] = &photosourcetest.ImmichLink{AssetIDs: []string{"a0"}, AllowDownload: true} })
	ok := e.do(req{method: "POST", path: "/v1/photo-sources", token: anna.token, body: map[string]any{"provider": "immich_link", "url": f.ShareURL("k1"), "album_id": album, "history": "none"}})
	if ok.StatusCode != 201 {
		t.Fatalf("create: %d %s", ok.StatusCode, ok.body)
	}
	r = e.do(req{method: "POST", path: "/v1/photo-sources", token: anna.token, body: map[string]any{"provider": "immich_link", "url": f.ShareURL("k2"), "album_id": album}})
	if r.StatusCode != 409 || r.code() != "limit_reached" {
		t.Fatalf("limit: %d %s", r.StatusCode, r.body)
	}
	e.settle()
	if s := e.source(anna, ok.m(t)["id"].(string)); count(s, "skipped") != 1 || count(s, "imported") != 0 {
		t.Fatalf("history none: %v", s["counts"])
	}
	// Turned off: 503.
	e2 := newEnv(t)
	u := e2.user("x@x.org", "X")
	if r := e2.do(req{method: "POST", path: "/v1/photo-sources:test", token: u.token, body: map[string]any{"provider": "immich_link", "url": f.ShareURL("k1")}}); r.StatusCode != 503 || r.code() != "feature_disabled" {
		t.Fatalf("disabled: %d %s", r.StatusCode, r.body)
	}
	if d := e2.get("/.well-known/kinframe", "").m(t); d["photo_sources"] != false || len(d["photo_source_providers"].([]any)) != 0 {
		t.Fatalf("disabled discovery: %v", d)
	}
}

// Calendar fetching goes through the same guard now.
func TestCalendarBehindGuard(t *testing.T) {
	e := newEnv(t, func(c *config.Config) {
		c.CalendarEnabled, c.CalendarSync, c.SourcesAllowLoopback, c.SourcesPrivateNetworks = true, 15*time.Minute, false, "on"
	})
	anna := e.user("anna@x.org", "Anna")
	_, frame := e.claim(anna)
	f := newImmich(t, 0) // any local server
	res := e.expect(req{method: "POST", path: "/v1/frames/" + frame + "/calendars:test", token: anna.token,
		body: map[string]any{"kind": "ics", "url": f.URL + "/cal.ics"}}, 200).m(t)
	if res["ok"] != false || !strings.Contains(fmt.Sprint(res["error"]), "not allowed") {
		t.Fatalf("calendar reached loopback: %v", res)
	}
}

func TestAlbumOnlyInAlbumMode(t *testing.T) {
	e := newEnv(t)
	anna := e.user("anna@x.org", "Anna")
	d, frame := e.claim(anna)
	a := e.expect(req{method: "POST", path: "/v1/frames/" + frame + "/albums", token: anna.token,
		body: map[string]any{"name": "Archive", "only_in_album_mode": true}}, 201)
	am := a.m(t)
	if am["only_in_album_mode"] != true || am["photo_source_count"] != float64(0) {
		t.Fatalf("create: %v", am)
	}
	ch := e.changes(d, 0)
	found := false
	for _, c := range changesOf(ch, "album.upsert") {
		if dig(c, "album", "id") == am["id"] && dig(c, "album", "only_in_album_mode") == true {
			found = true
		}
	}
	if !found {
		t.Fatalf("album.upsert without the flag: %v", ch)
	}
	head := ch["next_seq"].(float64)
	up := e.expect(req{method: "PATCH", path: "/v1/frames/" + frame + "/albums/" + am["id"].(string), token: anna.token,
		header: map[string]string{"If-Match": a.Header.Get("ETag")}, body: map[string]any{"only_in_album_mode": false}}, 200).m(t)
	if up["only_in_album_mode"] != false {
		t.Fatalf("update: %v", up)
	}
	cs := changesOf(e.changes(d, head), "album.upsert")
	if len(cs) != 1 || dig(cs[0], "album", "only_in_album_mode") != false {
		t.Fatalf("change after update: %v", cs)
	}
	if l := e.list("/v1/frames/"+frame+"/albums", anna.token); l[0]["only_in_album_mode"] != false {
		t.Fatalf("list: %v", l)
	}
}

func TestGoogleImport(t *testing.T) {
	requireMedia(t)
	e := newEnv(t, withGoogle)
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	d, frame := e.claim(anna)
	e.join(ben, d)
	album := e.album(anna, frame, "Garden")
	g := photosourcetest.NewGoogle()
	t.Cleanup(g.Close)
	_, _, gc := e.app.API.PhotoProviders()
	gc.AuthURL, gc.TokenURL, gc.APIBase, gc.Pinned = g.URL+"/auth", g.URL+"/token", g.URL+"/v1", []string{"127.0.0.1"}
	e.app.API.SetGooglePollMin(20 * time.Millisecond)
	if e.get("/.well-known/kinframe", "").m(t)["google_photos_import"] != true {
		t.Fatal("discovery")
	}
	ret := "app.kinframe:/google-import"
	start := func(u *user, body map[string]any, status int) *resp {
		return e.expect(req{method: "POST", path: "/v1/google-imports", token: u.token, body: body}, status)
	}
	if r := start(anna, map[string]any{"album_id": album, "return_uri": "https://evil.example/cb"}, 422); r.code() != "validation_failed" {
		t.Fatal(r.code())
	}
	if r := e.do(req{method: "POST", path: "/v1/google-imports", token: ben.token, body: map[string]any{"album_id": album, "return_uri": ret}}); r.StatusCode != 403 {
		t.Fatalf("member without manage_library: %d", r.StatusCode)
	}
	imp := start(anna, map[string]any{"album_id": album, "return_uri": ret}, 201).m(t)
	id := imp["id"].(string)
	if imp["status"] != "needs_auth" || imp["auth_url"] == nil || dig(imp, "target", "album_id") != album {
		t.Fatalf("start: %v", imp)
	}
	if r := e.do(req{method: "GET", path: "/v1/google-imports/" + id, token: ben.token}); r.StatusCode != 404 {
		t.Fatalf("someone else's import: %d", r.StatusCode)
	}
	cb, err := g.Consent(imp["auth_url"].(string))
	if err != nil {
		t.Fatal(err)
	}
	// A forged state gets the expired page, never a redirect.
	if r := e.do(req{method: "GET", path: "/v1/oauth/google/callback?state=forged&code=" + cb.Get("code")}); r.StatusCode != 400 || r.Header.Get("Location") != "" ||
		!strings.Contains(string(r.body), "expired") {
		t.Fatalf("forged state: %d %s", r.StatusCode, r.body)
	}
	r := e.do(req{method: "GET", path: "/v1/oauth/google/callback?" + cb.Encode()})
	if r.StatusCode != 302 {
		t.Fatalf("callback: %d %s", r.StatusCode, r.body)
	}
	loc, _ := url.Parse(r.Header.Get("Location"))
	if !strings.HasPrefix(r.Header.Get("Location"), ret+"?") || loc.Query().Get("import_id") != id || loc.Query().Get("status") != "picking" ||
		strings.Contains(r.Header.Get("Location"), "tok-") {
		t.Fatalf("redirect: %s", r.Header.Get("Location"))
	}
	// The state is single use.
	if r := e.do(req{method: "GET", path: "/v1/oauth/google/callback?" + cb.Encode()}); r.StatusCode != 400 {
		t.Fatalf("reused state: %d", r.StatusCode)
	}
	imp = e.get("/v1/google-imports/"+id, anna.token).m(t)
	if imp["status"] != "picking" || imp["picker_uri"] == nil || g.LastMaxItems != "500" {
		t.Fatalf("picking: %v (max %s)", imp, g.LastMaxItems)
	}
	annaWS := e.dialWS("/v1/ws?access_token="+anna.token, "")
	var sess string
	for s := range g.SessionIDs() {
		sess = s
	}
	g.Pick(sess,
		photosourcetest.GoogleItem{ID: "p1", Mime: "image/jpeg", File: pic(1), CreateTime: time.Date(2025, 5, 1, 12, 0, 0, 0, time.UTC)},
		photosourcetest.GoogleItem{ID: "p2", Mime: "image/jpeg", File: pic(2), CreateTime: time.Now()},
		photosourcetest.GoogleItem{ID: "raw", Mime: "image/x-adobe-dng", File: []byte("not an image"), Rendered: pic(3), CreateTime: time.Now()},
		photosourcetest.GoogleItem{ID: "dup", Mime: "image/jpeg", File: pic(1), CreateTime: time.Now()},
	)
	annaWS.awaitWhere("google_import.updated", 15*time.Second, func(m map[string]any) bool { return dig(m, "import", "status") == "done" })
	e.settle()
	imp = e.get("/v1/google-imports/"+id, anna.token).m(t)
	if dig(imp, "progress", "picked") != float64(4) || dig(imp, "progress", "imported") != float64(3) || dig(imp, "progress", "duplicates") != float64(1) ||
		len(imp["send_ids"].([]any)) != 1 || imp["finished_at"] == nil {
		t.Fatalf("done: %v", imp)
	}
	if !g.SessionIDs()[sess] {
		t.Fatal("picker session not deleted")
	}
	pls := e.library(anna, frame, "&album_id="+album)
	if len(pls) != 3 || dig(pls[0], "item", "via", "service") != "google_photos" || dig(pls[0], "item", "via", "source_id") != nil || pls[0]["backfill"] != false {
		t.Fatalf("imported: %v", pls)
	}
	if l := e.list("/v1/google-imports", anna.token); len(l) != 1 {
		t.Fatalf("my imports: %v", l)
	}

	// Within the hour the token is reused: no consent, straight to picking. Cancelling deletes session and token.
	imp2 := start(anna, map[string]any{"album_id": album, "return_uri": e.url + "/google-import/return"}, 201).m(t)
	if imp2["status"] != "picking" || imp2["auth_url"] != nil {
		t.Fatalf("token reuse: %v", imp2)
	}
	c := e.expect(req{method: "POST", path: "/v1/google-imports/" + imp2["id"].(string) + "/cancel", token: anna.token}, 200).m(t)
	if c["status"] != "cancelled" {
		t.Fatalf("cancel: %v", c)
	}
	e.expect(req{method: "POST", path: "/v1/google-imports/" + imp2["id"].(string) + "/cancel", token: anna.token}, 200)
	if r := e.do(req{method: "POST", path: "/v1/google-imports/" + id + "/cancel", token: anna.token}); r.StatusCode != 409 {
		t.Fatalf("cancel a finished import: %d", r.StatusCode)
	}
	var tokens int
	if err := e.app.Pool.QueryRow(context.Background(), `SELECT count(*) FROM google_imports WHERE id = $1 AND access_token_enc IS NOT NULL`, imp2["id"]).Scan(&tokens); err != nil || tokens != 0 {
		t.Fatalf("token kept after cancel: %d %v", tokens, err)
	}
	// Maintenance forgets tokens after they expire.
	e.setClock(time.Now().Add(2 * time.Hour))
	if err := e.app.API.PruneGoogleImports(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := e.app.Pool.QueryRow(context.Background(), `SELECT count(*) FROM google_imports WHERE access_token_enc IS NOT NULL`).Scan(&tokens); err != nil || tokens != 0 {
		t.Fatalf("tokens after expiry: %d %v", tokens, err)
	}
	e.setClock(time.Now())

	// Denied consent; a feed import by a poster makes a post.
	feed := e.createFeed(anna, map[string]any{"name": "Fam"})
	e.joinFeed(anna, ben, feed)
	imp3 := start(ben, map[string]any{"feed_id": feed, "return_uri": ret}, 201).m(t)
	cb3, _ := g.Consent(imp3["auth_url"].(string))
	r = e.do(req{method: "GET", path: "/v1/oauth/google/callback?state=" + url.QueryEscape(cb3.Get("state")) + "&error=access_denied"})
	if r.StatusCode != 302 || !strings.Contains(r.Header.Get("Location"), "status=failed") {
		t.Fatalf("denied: %d %s", r.StatusCode, r.Header.Get("Location"))
	}
	if g3 := e.get("/v1/google-imports/"+imp3["id"].(string), ben.token).m(t); g3["status"] != "failed" || g3["error_code"] != "consent_denied" {
		t.Fatalf("denied import: %v", g3)
	}
	imp4 := start(ben, map[string]any{"feed_id": feed, "return_uri": ret}, 201).m(t)
	cb4, _ := g.Consent(imp4["auth_url"].(string))
	if r := e.do(req{method: "GET", path: "/v1/oauth/google/callback?" + cb4.Encode()}); r.StatusCode != 302 {
		t.Fatalf("callback 4: %d", r.StatusCode)
	}
	for s, deleted := range g.SessionIDs() {
		if !deleted && s != sess {
			g.Pick(s, photosourcetest.GoogleItem{ID: "f1", Mime: "image/jpeg", File: pic(7), CreateTime: time.Now()})
		}
	}
	eventually(t, 15*time.Second, "feed import done", func() bool {
		return e.get("/v1/google-imports/"+imp4["id"].(string), ben.token).m(t)["status"] == "done"
	})
	posts := e.posts(feed, anna.token)
	if len(posts) != 1 || dig(posts[0], "via", "label") != "Google Photos" || dig(posts[0], "poster", "display_name") != "Ben" {
		t.Fatalf("feed import post: %v", posts)
	}
}

func TestPhotoSourceNotificationsAndFileEdits(t *testing.T) {
	requireMedia(t)
	e := newEnv(t, withPhotoSources, withUnifiedPush)
	anna := e.user("anna@x.org", "Anna")
	d, frame := e.claim(anna)
	e.heartbeat(d, "feed_backfill")
	album := e.album(anna, frame, "Garden")
	f := newImmich(t, 2)
	push := e.pushSink(t, anna)
	id := e.expect(req{method: "POST", path: "/v1/photo-sources", token: anna.token,
		body: map[string]any{"provider": "immich_link", "url": f.ShareURL("k1"), "album_id": album, "notify_reactions": false}}, 201).m(t)["id"].(string)
	e.settle()
	// The frame gets the photos with the credit.
	var pids []string
	eventually(t, 30*time.Second, "renditions", func() bool {
		ups := placementUpserts(e.changes(d, 0))
		pids = pids[:0]
		for pid, p := range ups {
			if dig(p, "item", "via", "service") == "immich" {
				pids = append(pids, pid)
			}
		}
		return len(pids) == 2
	})
	// Delivery of a source send pushes nothing; reactions are muted for this source.
	e.expect(req{method: "POST", path: "/v1/device/acks", token: d.token, body: map[string]any{"delivered": pids}}, 204)
	e.expect(req{method: "PUT", path: "/v1/device/placements/" + pids[0] + "/reaction", token: d.token, body: map[string]any{"reaction": "love"}}, 204)
	push.none(t, 700*time.Millisecond)
	e.expect(req{method: "PATCH", path: "/v1/photo-sources/" + id, token: anna.token, body: map[string]any{"notify_reactions": true}}, 200)
	e.settle()
	e.expect(req{method: "PUT", path: "/v1/device/placements/" + pids[0] + "/reaction", token: d.token, body: map[string]any{"reaction": "wow"}}, 204)
	if m := push.next(t, "reaction"); m.Key != "reaction" {
		t.Fatalf("reaction push: %+v", m)
	}

	// The file changes upstream: same item, new media, swapped once processed.
	itemOf := func(upstream string) (item, media string) {
		if err := e.app.Pool.QueryRow(context.Background(), `SELECT i.id::text, i.media_id::text FROM photo_source_items psi JOIN items i ON i.id = psi.item_id
			WHERE psi.source_id = $1 AND psi.upstream_id = $2`, id, upstream).Scan(&item, &media); err != nil {
			t.Fatal(err)
		}
		return
	}
	item0, media0 := itemOf("a0")
	f.Edit("a0", func(a *photosourcetest.ImmichAsset) { a.Original = pic(77); a.TakenAt = a.TakenAt.Add(-24 * time.Hour) })
	e.syncNow(id)
	eventually(t, 30*time.Second, "replacement processed and swapped", func() bool {
		e.syncNow(id)
		item, m := itemOf("a0")
		return item == item0 && m != media0
	})
	if s := e.source(anna, id); dig(s, "last_run", "updated") == nil || count(s, "imported") != 2 {
		t.Fatalf("after edit: %v", s)
	}
	for _, p := range e.library(anna, frame, "&album_id="+album) {
		if dig(p, "item", "id") == item0 && !strings.HasPrefix(fmt.Sprint(dig(p, "item", "captured_at")), "2026-05-31") {
			t.Fatalf("capture time not updated: %v", dig(p, "item", "captured_at"))
		}
	}
	// Unreadable credentials (the secrets key changed): needs attention.
	if _, err := e.app.Pool.Exec(context.Background(), `UPDATE photo_sources SET url_enc = '\x01deadbeef' WHERE id = $1`, id); err != nil {
		t.Fatal(err)
	}
	e.syncNow(id)
	if s := e.source(anna, id); s["status"] != "needs_attention" || s["last_error_code"] != "credentials_unreadable" {
		t.Fatalf("unreadable: %v", s)
	}
	if m := push.next(t, "attention"); m.Key != "photo_source_attention" {
		t.Fatalf("attention: %+v", m)
	}
}
