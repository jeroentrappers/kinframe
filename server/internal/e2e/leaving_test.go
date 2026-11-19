package e2e

import (
	"context"
	"testing"
	"time"
)

// Leaving a family (docs/spec/leaving.md, README §5.21) over real HTTP: the leave choices and their defaults for
// older clients, taking away with the 30-day notice, asking to keep (permissions, blocks, partial answers,
// expiry), the purge with a controlled clock, admin removal, account deletion (take and leave), the memories
// filter, read-only direct chats and operator ownership transfers.

// subscribeFeed subscribes a frame (managed by u) to a family and approves it as admin when needed.
func (e *env) subscribeFeed(u, admin *user, feed, frame string) string {
	e.t.Helper()
	s := e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/subscriptions", token: u.token,
		body: map[string]any{"frame_id": frame, "history": "none"}}, 201).m(e.t)
	if s["status"] == "pending" {
		e.expect(req{method: "POST", path: "/v1/subscriptions/" + s["id"].(string) + "/approve", token: admin.token}, 200)
	}
	return s["id"].(string)
}

// libraryOf lists a frame's library with a query (visibility included).
func (e *env) libraryOf(u *user, frame, query string) (placements []map[string]any, counts map[string]any) {
	e.t.Helper()
	r := e.get("/v1/frames/"+frame+"/placements?"+query, u.token).m(e.t)
	for _, p := range r["placements"].([]any) {
		placements = append(placements, p.(map[string]any))
	}
	counts, _ = r["counts"].(map[string]any)
	return placements, counts
}

// allChanges pages a frame's change feed from since to its head: the head and every change.
func (e *env) allChanges(d *device, since float64) (float64, map[string]any) {
	e.t.Helper()
	all := []any{}
	for i := 0; i < 100; i++ {
		ch := e.changes(d, since)
		all = append(all, ch["changes"].([]any)...)
		since = ch["next_seq"].(float64)
		if ch["has_more"] != true {
			break
		}
	}
	return since, map[string]any{"changes": all}
}

func itemIDs(ps []map[string]any) map[string]bool {
	out := map[string]bool{}
	for _, p := range ps {
		out[dig(p, "item", "id").(string)] = true
	}
	return out
}

// awaitReady waits until n of u's placements (by sender) are on the frame and no longer pending.
func (e *env) awaitReady(viewer *user, frame string, n int) []map[string]any {
	e.t.Helper()
	var out []map[string]any
	eventually(e.t, 60*time.Second, "placements ready", func() bool {
		ps, _ := e.libraryOf(viewer, frame, "visibility=all")
		if len(ps) != n {
			return false
		}
		for _, p := range ps {
			if p["status"] == "pending" {
				return false
			}
		}
		out = ps
		return true
	})
	return out
}

func TestLeavingTakeAwayAndKeep(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	lies, tom, carla, ben := e.user("lies@x.org", "Lies"), e.user("tom@x.org", "Tom"), e.user("carla@x.org", "Carla"), e.user("ben@x.org", "Ben")
	disc := e.get("/.well-known/kinframe", "").m(t)
	if disc["leaving"] != true {
		t.Fatalf("discovery leaving: %v", disc["leaving"])
	}
	feed := e.createFeed(lies, map[string]any{"name": "Janssens"})
	e.joinFeed(lies, tom, feed)
	e.joinFeed(lies, ben, feed)
	dOma, oma := e.claimSized(lies, 1280, 800)
	_, kitchen := e.claimSized(lies, 1024, 600)
	e.heartbeat(dOma, "feed_backfill")
	e.join(tom, dOma)
	e.join(carla, dOma)
	e.expect(req{method: "PATCH", path: "/v1/frames/" + oma + "/members/" + carla.id, token: lies.token, body: map[string]any{"role": "admin"}}, 200)
	e.subscribeFeed(lies, lies, feed, oma)
	e.subscribeFeed(lies, lies, feed, kitchen)

	// Tom posts two photos to the family and one to Oma's frame directly; Ben posts one.
	tomSend := e.post(tom, []string{}, []string{feed}, e.photo(tom, 1), e.photo(tom, 2))
	tomPost := e.feedPostOf(tom, tomSend, feed)
	e.post(tom, []string{oma}, []string{}, e.photo(tom, 3))
	benSend := e.post(ben, []string{}, []string{feed}, e.photo(ben, 4))
	e.awaitReady(lies, oma, 4)
	e.awaitReady(lies, kitchen, 3)
	c := e.comment(tom, e.feedPostOf(ben, benSend, feed), map[string]any{"body": "Lovely"}, 201)

	// The leave sheet.
	pv := e.get("/v1/me/leave-preview?feed_id="+feed, tom.token).m(t)
	if pv["scope"] != "feed" || pv["member"] != true || pv["owner"] != false || pv["photos"] != float64(2) || pv["comments"] != float64(1) {
		t.Fatalf("preview: %v", pv)
	}
	frames := pv["frames"].([]any)
	if len(frames) != 1 || dig(frames[0].(map[string]any), "frame_id") != oma || dig(frames[0].(map[string]any), "suggested") != true ||
		dig(frames[0].(map[string]any), "direct_photos") != float64(1) {
		t.Fatalf("preview frames: %v", frames)
	}
	if r := e.do(req{method: "GET", path: "/v1/me/leave-preview?feed_id=" + feed + "&frame_id=" + oma, token: tom.token}); r.StatusCode != 422 {
		t.Fatalf("both scopes: %d", r.StatusCode)
	}
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/leave", token: tom.token,
		body: map[string]any{"photos": "take", "leave_frame_ids": []string{kitchen}}}); r.StatusCode != 422 {
		t.Fatalf("leave a frame he isn't in: %d %s", r.StatusCode, r.body)
	}

	liesWS := e.dialWS("/v1/ws?access_token="+lies.token, "")
	before, _ := e.allChanges(dOma, 0)
	// Tom leaves, takes his photos and comments, and leaves Oma's frame too.
	res := e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/leave", token: tom.token,
		body: map[string]any{"photos": "take", "comments": "delete", "leave_frame_ids": []string{oma}}}, 200).m(t)
	if res["photos_taken_away"] != float64(3) || res["comments_deleted"] != float64(1) || res["taking_id"] == nil || res["until"] == nil ||
		len(res["left_frame_ids"].([]any)) != 1 || res["remote_frames"] != float64(0) {
		t.Fatalf("leave result: %v", res)
	}
	taking := res["taking_id"].(string)
	got := map[string]float64{}
	for _, f := range res["frames"].([]any) {
		got[dig(f.(map[string]any), "frame_id").(string)] = dig(f.(map[string]any), "photos").(float64)
	}
	if got[oma] != 3 || got[kitchen] != 2 {
		t.Fatalf("frames that lost photos: %v", got)
	}
	ev := liesWS.awaitWhere("placement.updated", 5*time.Second, func(m map[string]any) bool { return m["frame_id"] == oma })
	if ev["reason"] != "taken_away" || ev["taking_id"] != taking || len(ev["placement_ids"].([]any)) != 3 {
		t.Fatalf("placement.updated: %v", ev)
	}
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed, token: tom.token}); r.StatusCode != 404 {
		t.Fatalf("tom still in the family: %d", r.StatusCode)
	}
	if r := e.do(req{method: "GET", path: "/v1/frames/" + oma, token: tom.token}); r.StatusCode != 404 {
		t.Fatalf("tom still on Oma's frame: %d", r.StatusCode)
	}
	for _, p := range e.timeline(lies, "") {
		if p["id"] == tomPost {
			t.Fatal("Tom's post is still in the timeline")
		}
	}
	if cs := e.comments(lies, e.feedPostOf(ben, benSend, feed)); len(cs) != 0 && cs[0]["id"] == c["id"] && cs[0]["deleted"] != true {
		t.Fatalf("comment not deleted: %v", cs)
	}
	// The library: Tom's photos are out of every view but the trash's "Taken away".
	if ps, counts := e.libraryOf(lies, oma, "visibility=all"); len(ps) != 1 || counts["taken_away"] != float64(3) {
		t.Fatalf("oma library: %d %v", len(ps), counts)
	}
	away, _ := e.libraryOf(lies, oma, "visibility=taken_away&taking_id="+taking)
	if len(away) != 3 || dig(away[0], "taken_away", "by_name") != "Tom" || dig(away[0], "taken_away", "taking_id") != taking {
		t.Fatalf("taken away list: %v", away)
	}
	// The frame gets placement.remove for each.
	_, ch := e.allChanges(dOma, before)
	if rm := removals(ch); len(rm) != 3 {
		t.Fatalf("frame removals: %v", rm)
	}

	// The frame's managers see one group; Carla can't ask (Tom blocked her), Lies can.
	e.block(tom, carla)
	g := e.get("/v1/frames/"+oma+"/taken-away", carla.token).m(t)["groups"].([]any)
	if len(g) != 1 || dig(g[0].(map[string]any), "count") != float64(3) || dig(g[0].(map[string]any), "can_ask") != false ||
		dig(g[0].(map[string]any), "cannot_ask_reason") != "blocked" || dig(g[0].(map[string]any), "by", "display_name") != "Tom" {
		t.Fatalf("carla's groups: %v", g)
	}
	if r := e.do(req{method: "POST", path: "/v1/keep-requests", token: carla.token, body: map[string]any{"taking_id": taking, "frame_id": oma}}); r.StatusCode != 403 || r.code() != "forbidden" {
		t.Fatalf("blocked asker: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "GET", path: "/v1/frames/" + oma + "/taken-away", token: ben.token}); r.StatusCode != 404 {
		t.Fatalf("non-member groups: %d", r.StatusCode)
	}
	g = e.get("/v1/frames/"+oma+"/taken-away", lies.token).m(t)["groups"].([]any)
	if dig(g[0].(map[string]any), "can_ask") != true || len(dig(g[0].(map[string]any), "preview_thumb_urls").([]any)) != 3 {
		t.Fatalf("lies's groups: %v", g)
	}
	pick := []string{dig(away[0], "item", "id").(string), dig(away[1], "item", "id").(string)}
	if r := e.do(req{method: "POST", path: "/v1/keep-requests", token: lies.token,
		body: map[string]any{"taking_id": taking, "frame_id": oma, "item_ids": []string{"01900000-0000-7000-8000-000000000001"}}}); r.StatusCode != 422 {
		t.Fatalf("unknown item: %d", r.StatusCode)
	}
	tomWS := e.dialWS("/v1/ws?access_token="+tom.token, "")
	kr := e.expect(req{method: "POST", path: "/v1/keep-requests", token: lies.token,
		body: map[string]any{"taking_id": taking, "frame_id": oma, "item_ids": pick, "note": " The ones with the children "}}, 201).m(t)
	if kr["status"] != "open" || kr["item_count"] != float64(2) || kr["note"] != "The ones with the children" || dig(kr, "person", "display_name") != "Tom" ||
		kr["frame_name"] == nil || kr["scope"] != "frame" {
		t.Fatalf("keep request: %v", kr)
	}
	tomWS.awaitWhere("keep_request.updated", 5*time.Second, func(m map[string]any) bool { return dig(m, "request", "id") == kr["id"] })
	if r := e.do(req{method: "POST", path: "/v1/keep-requests", token: lies.token, body: map[string]any{"taking_id": taking, "frame_id": oma}}); r.StatusCode != 409 || r.code() != "limit_reached" {
		t.Fatalf("second request: %d %s", r.StatusCode, r.body)
	}
	mine := e.get("/v1/me/keep-requests", tom.token).m(t)["requests"].([]any)
	if len(mine) != 1 || dig(mine[0].(map[string]any), "id") != kr["id"] {
		t.Fatalf("tom's requests: %v", mine)
	}
	full := e.get("/v1/keep-requests/"+kr["id"].(string), tom.token).m(t)
	if len(full["items"].([]any)) != 2 {
		t.Fatalf("request items: %v", full)
	}
	if r := e.do(req{method: "GET", path: "/v1/keep-requests/" + kr["id"].(string), token: ben.token}); r.StatusCode != 404 {
		t.Fatalf("stranger reads request: %d", r.StatusCode)
	}
	if r := e.do(req{method: "POST", path: "/v1/keep-requests/" + kr["id"].(string) + "/answer", token: lies.token, body: map[string]any{"allow": "all"}}); r.StatusCode != 403 {
		t.Fatalf("asker answers: %d", r.StatusCode)
	}
	if r := e.do(req{method: "POST", path: "/v1/keep-requests/" + kr["id"].(string) + "/answer", token: tom.token,
		body: map[string]any{"allow": "some", "item_ids": []string{dig(away[2], "item", "id").(string)}}}); r.StatusCode != 422 {
		t.Fatalf("answer with an item not asked for: %d", r.StatusCode)
	}
	seq, _ := e.allChanges(dOma, 0)
	ans := e.expect(req{method: "POST", path: "/v1/keep-requests/" + kr["id"].(string) + "/answer", token: tom.token,
		body: map[string]any{"allow": "some", "item_ids": pick[:1]}}, 200).m(t)
	if ans["status"] != "partly_allowed" || ans["allowed_count"] != float64(1) || ans["answered_at"] == nil {
		t.Fatalf("answer: %v", ans)
	}
	liesWS.awaitWhere("placement.updated", 5*time.Second, func(m map[string]any) bool { return m["reason"] == "restored" })
	liesWS.awaitWhere("keep_request.updated", 5*time.Second, func(m map[string]any) bool { return dig(m, "request", "status") == "partly_allowed" })
	if r := e.do(req{method: "POST", path: "/v1/keep-requests/" + kr["id"].(string) + "/answer", token: tom.token, body: map[string]any{"allow": "all"}}); r.StatusCode != 409 || r.code() != "already_decided" {
		t.Fatalf("second answer: %d %s", r.StatusCode, r.body)
	}
	if ps, _ := e.libraryOf(lies, oma, "visibility=all"); !itemIDs(ps)[pick[0]] || len(ps) != 2 {
		t.Fatalf("restored photo not back: %v", itemIDs(ps))
	}
	_, after := e.allChanges(dOma, seq)
	up := placementUpserts(after)
	back := false
	for _, p := range up {
		if dig(p, "item", "id") == pick[0] && p["backfill"] == true {
			back = true
		}
	}
	if !back {
		t.Fatalf("restored placement not emitted as history: %v", up)
	}

	// The family side: Lies asks for the family's photos; Tom declines.
	fg := e.get("/v1/feeds/"+feed+"/taken-away", lies.token).m(t)["groups"].([]any)
	if len(fg) != 1 || dig(fg[0].(map[string]any), "count") != float64(2) || dig(fg[0].(map[string]any), "can_ask") != true {
		t.Fatalf("family groups: %v", fg)
	}
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed + "/taken-away", token: ben.token}); r.StatusCode != 403 {
		t.Fatalf("poster reads family groups: %d", r.StatusCode)
	}
	fkr := e.expect(req{method: "POST", path: "/v1/keep-requests", token: lies.token, body: map[string]any{"taking_id": taking, "feed_id": feed}}, 201).m(t)
	e.expect(req{method: "POST", path: "/v1/keep-requests/" + fkr["id"].(string) + "/answer", token: tom.token, body: map[string]any{"allow": "all"}}, 200)
	found := false
	for _, p := range e.timeline(lies, "") {
		found = found || p["id"] == tomPost
	}
	if !found {
		t.Fatal("family post not back after the keep request")
	}
	if ps, _ := e.libraryOf(lies, kitchen, "visibility=all"); len(ps) != 3 {
		t.Fatalf("kitchen after the family restore: %d", len(ps))
	}
	if l := e.get("/v1/feeds/"+feed+"/keep-requests", lies.token).m(t)["requests"].([]any); len(l) != 1 || dig(l[0].(map[string]any), "status") != "allowed" {
		t.Fatalf("family requests: %v", l)
	}
	if l := e.get("/v1/frames/"+oma+"/keep-requests", lies.token).m(t)["requests"].([]any); len(l) != 1 {
		t.Fatalf("frame requests: %v", l)
	}

	// After 30 days what nobody kept is purged.
	e.app.API.SetClock(func() time.Time { return time.Now().Add(31 * 24 * time.Hour) })
	defer e.app.API.SetClock(nil)
	if err := e.app.API.LeavingTick(context.Background(), time.Now().Add(31*24*time.Hour)); err != nil {
		t.Fatal(err)
	}
	liesWS.awaitWhere("placement.updated", 5*time.Second, func(m map[string]any) bool { return m["reason"] == "purged" })
	if away, counts := e.libraryOf(lies, oma, "visibility=taken_away"); len(away) != 0 || counts["taken_away"] != float64(0) {
		t.Fatalf("after purge: %d %v", len(away), counts)
	}
	if g := e.get("/v1/frames/"+oma+"/taken-away", lies.token).m(t)["groups"].([]any); len(g) != 0 {
		t.Fatalf("groups after purge: %v", g)
	}
}

// Old clients get "keep"; admins removing someone never takes photos away; the removed person can take them
// away later from the place they left.
func TestLeavingDefaultsAndRemoval(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	lies, tom, ben := e.user("lies@x.org", "Lies"), e.user("tom@x.org", "Tom"), e.user("ben@x.org", "Ben")
	feed := e.createFeed(lies, map[string]any{"name": "Janssens"})
	e.joinFeed(lies, tom, feed)
	e.joinFeed(lies, ben, feed)
	dOma, oma := e.claimSized(lies, 1280, 800)
	e.join(ben, dOma)
	e.subscribeFeed(lies, lies, feed, oma)
	e.post(ben, []string{oma}, []string{}, e.photo(ben, 1))
	e.post(tom, []string{}, []string{feed}, e.photo(tom, 2))
	// Sent both directly and to the family: taking away from the frame leaves it (the family holds it).
	e.post(ben, []string{oma}, []string{feed}, e.photo(ben, 5))
	e.awaitReady(lies, oma, 3)

	// Ben's app is old: leave without a body keeps the photos, with 204.
	e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/leave", token: ben.token}, 204)
	e.expect(req{method: "POST", path: "/v1/frames/" + oma + "/leave", token: ben.token, body: map[string]any{}}, 204)
	if ps, _ := e.libraryOf(lies, oma, "visibility=all"); len(ps) != 3 {
		t.Fatalf("old client leave took photos: %d", len(ps))
	}
	// An admin removes Tom: his photos stay.
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/members/" + tom.id, token: lies.token}, 204)
	if ps, _ := e.libraryOf(lies, oma, "visibility=all"); len(ps) != 3 {
		t.Fatalf("removal took photos: %d", len(ps))
	}
	// Both still see where their photos are.
	fp := e.get("/v1/me/former-places", tom.token).m(t)
	if fs := fp["feeds"].([]any); len(fs) != 1 || dig(fs[0].(map[string]any), "feed_id") != feed || dig(fs[0].(map[string]any), "photos") != float64(1) {
		t.Fatalf("tom's former places: %v", fp)
	}
	fp = e.get("/v1/me/former-places", ben.token).m(t)
	if fr := fp["frames"].([]any); len(fr) != 1 || dig(fr[0].(map[string]any), "frame_id") != oma {
		t.Fatalf("ben's former frames: %v", fp)
	}
	if pv := e.get("/v1/me/leave-preview?feed_id="+feed, tom.token).m(t); pv["member"] != false || pv["photos"] != float64(1) {
		t.Fatalf("former member preview: %v", pv)
	}
	// Tom takes his photos away from the family he was removed from; Ben from the frame he left.
	r := e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/take-away", token: tom.token, body: map[string]any{},
		header: map[string]string{"Idempotency-Key": "take-1"}}, 200).m(t)
	if r["photos_taken_away"] != float64(1) {
		t.Fatalf("take away: %v", r)
	}
	again := e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/take-away", token: tom.token, body: map[string]any{},
		header: map[string]string{"Idempotency-Key": "take-1"}}, 200).m(t)
	if again["taking_id"] != r["taking_id"] {
		t.Fatalf("idempotent replay: %v", again)
	}
	if x := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/take-away", token: tom.token, body: map[string]any{}}); x.StatusCode != 404 {
		t.Fatalf("nothing left to take: %d", x.StatusCode)
	}
	if x := e.expect(req{method: "POST", path: "/v1/frames/" + oma + "/take-away", token: ben.token}, 200).m(t); x["photos_taken_away"] != float64(1) {
		t.Fatalf("frame take away: %v", x)
	}
	// Ben's photo that the family holds stays (a direct placement is all he took).
	if ps, _ := e.libraryOf(lies, oma, "visibility=all"); len(ps) != 1 {
		t.Fatalf("library after take-aways: %d", len(ps))
	}
	if fp := e.get("/v1/me/former-places", tom.token).m(t); len(fp["feeds"].([]any)) != 0 {
		t.Fatalf("former places after taking: %v", fp)
	}
	// A purge in the trash by a manager deletes early.
	away, _ := e.libraryOf(lies, oma, "visibility=taken_away")
	if len(away) != 2 {
		t.Fatalf("taken away: %d", len(away))
	}
	e.expect(req{method: "DELETE", path: "/v1/frames/" + oma + "/placements/" + away[0]["id"].(string), token: lies.token}, 204)
	away, _ = e.libraryOf(lies, oma, "visibility=taken_away")
	if len(away) != 1 {
		t.Fatalf("after early purge: %d", len(away))
	}
	// A request nobody answers expires with the 30 days; then it can't be answered or asked again.
	taking := dig(away[0], "taken_away", "taking_id").(string)
	kr := e.expect(req{method: "POST", path: "/v1/keep-requests", token: lies.token, body: map[string]any{"taking_id": taking, "frame_id": oma}}, 201).m(t)
	later := time.Now().Add(30*24*time.Hour + time.Minute)
	e.app.API.SetClock(func() time.Time { return later })
	defer e.app.API.SetClock(nil)
	owner := tom
	if dig(away[0], "taken_away", "by_user_id") == ben.id {
		owner = ben
	}
	if r := e.do(req{method: "POST", path: "/v1/keep-requests/" + kr["id"].(string) + "/answer", token: owner.token, body: map[string]any{"allow": "all"}}); r.StatusCode != 409 || r.code() != "invalid_transition" {
		t.Fatalf("answer after 30 days: %d %s", r.StatusCode, r.body)
	}
	if err := e.app.API.LeavingTick(context.Background(), later); err != nil {
		t.Fatal(err)
	}
	if got := e.get("/v1/keep-requests/"+kr["id"].(string), lies.token).m(t); got["status"] != "expired" {
		t.Fatalf("not expired: %v", got["status"])
	}
	if l := e.get("/v1/me/keep-requests", owner.token).m(t)["requests"].([]any); len(l) != 0 {
		t.Fatalf("expired request still open: %v", l)
	}
	if l := e.get("/v1/me/keep-requests?status=all", owner.token).m(t)["requests"].([]any); len(l) != 1 {
		t.Fatalf("all requests: %v", l)
	}
	if away, _ := e.libraryOf(lies, oma, "visibility=taken_away"); len(away) != 0 {
		t.Fatalf("not purged: %d", len(away))
	}
}

// The monthly recap as rendered for someone who left a person out: the next most-loved post takes its place.
func TestLeavingRecap(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	lies, tom, ben := e.user("lies@x.org", "Lies"), e.user("tom@x.org", "Tom"), e.user("ben@x.org", "Ben")
	feed := e.createFeed(lies, map[string]any{"name": "Janssens"})
	e.joinFeed(lies, tom, feed)
	e.joinFeed(lies, ben, feed)
	tomPost := e.feedPostOf(tom, e.post(tom, []string{}, []string{feed}, e.photo(tom, 1)), feed)
	benPost := e.feedPostOf(ben, e.post(ben, []string{}, []string{feed}, e.photo(ben, 2)), feed)
	now := time.Now().UTC()
	month := time.Date(now.Year(), now.Month()-1, 1, 0, 0, 0, 0, time.UTC)
	at := month.AddDate(0, 0, 10).Add(12 * time.Hour)
	e.sql(`UPDATE feed_posts SET created_at = $2 WHERE id = ANY($1)`, []string{tomPost, benPost}, at)
	e.sql(`INSERT INTO reactions (target_kind, target_id, scope_id, user_id, emoji) VALUES
		('post', $1, $1, $3, 'love'), ('post', $1, $1, $4, 'love'), ('post', $2, $2, $3, 'love')`, tomPost, benPost, lies.id, ben.id)
	e.sql(`INSERT INTO feed_recaps (feed_id, month, data) VALUES ($1::uuid, $2, jsonb_build_object('feed_id', $5::text, 'month', $3::text,
		'photos_shared', 2, 'videos_shared', 0, 'sharer_count', 2,
		'most_loved', jsonb_build_object('post_id', $4::text, 'reaction_count', 2, 'item', jsonb_build_object('id', $4::text))))`,
		feed, month, month.Format("2006-01"), tomPost, feed)
	path := "/v1/feeds/" + feed + "/recaps/" + month.Format("2006-01")
	if rc := e.get(path, lies.token).m(t); dig(rc, "most_loved", "post_id") != tomPost {
		t.Fatalf("recap before: %v", rc)
	}
	e.expect(req{method: "PUT", path: "/v1/me/memories", token: lies.token, body: map[string]any{"hidden_people": []string{tom.id}}}, 200)
	rc := e.get(path, lies.token).m(t)
	if dig(rc, "most_loved", "post_id") != benPost || dig(rc, "most_loved", "reaction_count") != float64(1) || rc["photos_shared"] != float64(2) {
		t.Fatalf("recap for lies: %v", rc)
	}
	if rc := e.get(path, ben.token).m(t); dig(rc, "most_loved", "post_id") != tomPost {
		t.Fatalf("recap for ben changed: %v", rc)
	}
	e.expect(req{method: "PUT", path: "/v1/me/memories", token: lies.token, body: map[string]any{"hidden_memories": []string{benPost}}}, 200)
	if rc := e.get(path, lies.token).m(t); rc["most_loved"] != nil {
		t.Fatalf("recap with everything hidden: %v", rc)
	}
}

// Account deletion: take (the default) takes everything away with notice and nobody can be asked; leave hands
// the photos over with the frozen credit and a log entry.
func TestLeavingAccountDeletion(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	lies, tom, ben := e.user("lies@x.org", "Lies"), e.user("tom@x.org", "Tom"), e.user("ben@x.org", "Ben")
	feed := e.createFeed(lies, map[string]any{"name": "Janssens"})
	e.joinFeed(lies, tom, feed)
	e.joinFeed(lies, ben, feed)
	dOma, oma := e.claimSized(lies, 1280, 800)
	e.join(ben, dOma)
	e.subscribeFeed(lies, lies, feed, oma)
	e.post(tom, []string{}, []string{feed}, e.photo(tom, 1))
	e.post(ben, []string{}, []string{feed}, e.photo(ben, 2))
	e.post(ben, []string{oma}, []string{}, e.photo(ben, 3))
	e.awaitReady(lies, oma, 3)

	e.expect(req{method: "DELETE", path: "/v1/me", token: tom.token}, 204)
	g := e.get("/v1/frames/"+oma+"/taken-away", lies.token).m(t)["groups"].([]any)
	if len(g) != 1 || dig(g[0].(map[string]any), "by") != nil || dig(g[0].(map[string]any), "by_name") != "Tom" ||
		dig(g[0].(map[string]any), "cannot_ask_reason") != "account_deleted" {
		t.Fatalf("groups after take: %v", g)
	}
	taking := dig(g[0].(map[string]any), "taking_id").(string)
	if r := e.do(req{method: "POST", path: "/v1/keep-requests", token: lies.token, body: map[string]any{"taking_id": taking, "frame_id": oma}}); r.StatusCode != 409 {
		t.Fatalf("ask a deleted account: %d", r.StatusCode)
	}

	e.expect(req{method: "DELETE", path: "/v1/me", token: ben.token, body: map[string]any{"photos": "leave"}}, 204)
	ps, _ := e.libraryOf(lies, oma, "visibility=all")
	if len(ps) != 2 {
		t.Fatalf("after leave: %d", len(ps))
	}
	for _, p := range ps {
		if dig(p, "item", "former_sender_name") != "Ben" || dig(p, "item", "sender_name") != "Ben" || dig(p, "item", "sender_id") != lies.id {
			t.Fatalf("handed over item: %v", p["item"])
		}
	}
	op := e.operator()
	// One entry per family or frame that received photos: Ben's family post and his direct send.
	log := e.get("/v1/operator/ownership-log", op).m(t)["entries"].([]any)
	kinds := map[string]float64{}
	for _, l := range log {
		m := l.(map[string]any)
		if m["kind"] != "account_handover" || m["from_name"] != "Ben" || m["to_name"] != "Lies" {
			t.Fatalf("handover log entry: %v", m)
		}
		kinds[m["scope_kind"].(string)] += m["item_count"].(float64)
	}
	if len(log) != 2 || kinds["feed"] != 1 || kinds["frame"] != 1 {
		t.Fatalf("handover log: %v", log)
	}
}

// Memories leave people and single posts out of On this day and the recap, privately.
func TestLeavingMemories(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	lies, tom := e.user("lies@x.org", "Lies"), e.user("tom@x.org", "Tom")
	feed := e.createFeed(lies, map[string]any{"name": "Janssens"})
	e.joinFeed(lies, tom, feed)
	send := e.post(tom, []string{}, []string{feed}, e.photo(tom, 1))
	post := e.feedPostOf(tom, send, feed)
	e.sql(`UPDATE feed_posts SET created_at = now() - interval '1 year' WHERE id = $1`, post)
	eventually(t, 60*time.Second, "memory", func() bool {
		return len(e.get("/v1/me/on-this-day", lies.token).m(t)["groups"].([]any)) == 1
	})
	if r := e.do(req{method: "PUT", path: "/v1/me/memories", token: lies.token, body: map[string]any{"hidden_people": []string{tom.id, "01900000-0000-7000-8000-000000000001"}}}); r.StatusCode != 200 {
		t.Fatalf("put memories: %d %s", r.StatusCode, r.body)
	}
	m := e.get("/v1/me/memories", lies.token).m(t)
	if hp := m["hidden_people"].([]any); len(hp) != 1 || dig(hp[0].(map[string]any), "user_id") != tom.id || len(m["hidden_memories"].([]any)) != 0 {
		t.Fatalf("memories: %v", m)
	}
	if g := e.get("/v1/me/on-this-day", lies.token).m(t)["groups"].([]any); len(g) != 0 {
		t.Fatalf("hidden person still in memories: %v", g)
	}
	// Tom's own memories are unchanged; nobody is told.
	if g := e.get("/v1/me/on-this-day", tom.token).m(t)["groups"].([]any); len(g) != 1 {
		t.Fatalf("tom's memories: %v", g)
	}
	// One memory instead of a person.
	e.expect(req{method: "PUT", path: "/v1/me/memories", token: lies.token, body: map[string]any{"hidden_people": []string{}, "hidden_memories": []string{post}}}, 200)
	if g := e.get("/v1/me/on-this-day", lies.token).m(t)["groups"].([]any); len(g) != 0 {
		t.Fatalf("hidden memory shown: %v", g)
	}
	e.expect(req{method: "PUT", path: "/v1/me/memories", token: lies.token, body: map[string]any{"hidden_memories": []string{}}}, 200)
	if g := e.get("/v1/me/on-this-day", lies.token).m(t)["groups"].([]any); len(g) != 1 {
		t.Fatalf("memory not back: %v", g)
	}
}

// A direct chat becomes read-only when two people no longer share a family or frame, and writable again.
func TestLeavingReadOnlyChat(t *testing.T) {
	e := newEnv(t)
	lies, tom := e.user("lies@x.org", "Lies"), e.user("tom@x.org", "Tom")
	feed := e.createFeed(lies, map[string]any{"name": "Janssens"})
	e.joinFeed(lies, tom, feed)
	conv := e.direct(lies, tom, 201)
	e.say(tom, conv, "Hi")
	e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/leave", token: tom.token, body: map[string]any{"photos": "keep"}}, 200)
	c := e.conversation(lies, conv)
	if c["read_only"] != true || c["read_only_reason"] != "no_longer_contacts" {
		t.Fatalf("conversation after leaving: %v %v", c["read_only"], c["read_only_reason"])
	}
	if r := e.do(req{method: "POST", path: "/v1/conversations/" + conv + "/messages", token: tom.token, body: map[string]any{"body": "Still there?"}}); r.StatusCode != 403 || r.code() != "cannot_message" {
		t.Fatalf("send after leaving: %d %s", r.StatusCode, r.body)
	}
	if ms := e.messages(lies, conv, ""); len(ms) == 0 {
		t.Fatal("history gone")
	}
	e.joinFeed(lies, tom, feed)
	if c := e.conversation(tom, conv); c["read_only"] != false {
		t.Fatalf("writable again: %v", c["read_only"])
	}
	e.say(tom, conv, "Back")
}

// The operator moves ownership of a frame and a family, with the log; members only.
func TestLeavingOperatorTransfer(t *testing.T) {
	e := newEnv(t)
	lies, tom, ben := e.user("lies@x.org", "Lies"), e.user("tom@x.org", "Tom"), e.user("ben@x.org", "Ben")
	d, frame := e.claim(tom)
	e.join(lies, d)
	feed := e.createFeed(tom, map[string]any{"name": "Janssens"})
	e.joinFeed(tom, lies, feed)
	op := e.operator()
	if r := e.do(req{method: "GET", path: "/v1/operator/ownership/search?q=lies", token: lies.token}); r.StatusCode != 403 {
		t.Fatalf("non-operator search: %d", r.StatusCode)
	}
	found := e.get("/v1/operator/ownership/search?q=lies@x", op).m(t)
	if len(found["frames"].([]any)) != 1 || len(found["feeds"].([]any)) != 1 || len(dig(found["frames"].([]any)[0].(map[string]any), "members").([]any)) != 2 {
		t.Fatalf("search: %v", found)
	}
	if r := e.do(req{method: "POST", path: "/v1/operator/frames/" + frame + "/owner", token: op, body: map[string]any{"user_id": ben.id, "reason": "court order"}}); r.StatusCode != 422 {
		t.Fatalf("non-member new owner: %d", r.StatusCode)
	}
	tomWS := e.dialWS("/v1/ws?access_token="+tom.token, "")
	e.expect(req{method: "POST", path: "/v1/operator/frames/" + frame + "/owner", token: op, body: map[string]any{"user_id": lies.id, "reason": "court order"}}, 204)
	tomWS.await("membership.updated", 5*time.Second)
	if f := e.get("/v1/frames/"+frame, lies.token).m(t); f["role"] != "owner" {
		t.Fatalf("lies role: %v", f["role"])
	}
	if f := e.get("/v1/frames/"+frame, tom.token).m(t); f["role"] != "admin" {
		t.Fatalf("tom role: %v", f["role"])
	}
	e.expect(req{method: "POST", path: "/v1/operator/feeds/" + feed + "/owner", token: op, body: map[string]any{"user_id": lies.id, "reason": "agreed", "remove_previous": true}}, 204)
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed, token: tom.token}); r.StatusCode != 404 {
		t.Fatalf("previous owner still in family: %d", r.StatusCode)
	}
	log := e.get("/v1/operator/ownership-log?limit=1", op).m(t)
	entries := log["entries"].([]any)
	if len(entries) != 1 || dig(entries[0].(map[string]any), "scope_kind") != "feed" || dig(entries[0].(map[string]any), "removed_previous") != true ||
		dig(entries[0].(map[string]any), "reason") != "agreed" || log["next_cursor"] == nil {
		t.Fatalf("log page 1: %v", log)
	}
	next := e.get("/v1/operator/ownership-log?limit=1&cursor="+log["next_cursor"].(string), op).m(t)["entries"].([]any)
	if len(next) != 1 || dig(next[0].(map[string]any), "scope_kind") != "frame" || dig(next[0].(map[string]any), "from_name") != "Tom" ||
		dig(next[0].(map[string]any), "operator_name") != "Op" {
		t.Fatalf("log page 2: %v", next)
	}
}
