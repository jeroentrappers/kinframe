package e2e

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/config"
)

// Family social (docs/spec/family-social.md, README §5.18): timeline, threads, reactions, circles, frame posts
// and entries, pushes, over real HTTP.

func (e *env) timeline(u *user, query string) []map[string]any {
	e.t.Helper()
	var out []map[string]any
	for _, p := range e.get("/v1/timeline"+query, u.token).m(e.t)["posts"].([]any) {
		out = append(out, p.(map[string]any))
	}
	return out
}

func (e *env) comments(u *user, post string) []map[string]any {
	e.t.Helper()
	var out []map[string]any
	for _, c := range e.get("/v1/posts/"+post+"/comments", u.token).m(e.t)["comments"].([]any) {
		out = append(out, c.(map[string]any))
	}
	return out
}

func (e *env) comment(u *user, post string, body map[string]any, status int) map[string]any {
	e.t.Helper()
	r := e.expect(req{method: "POST", path: "/v1/posts/" + post + "/comments", token: u.token, body: body}, status)
	if status != 201 {
		return map[string]any{"code": r.code(), "errors": r.m(e.t)["errors"]}
	}
	return r.m(e.t)
}

// feedPostOf waits for the send's post in the feed and returns its id.
func (e *env) feedPostOf(u *user, sendID, feedID string) string {
	e.t.Helper()
	for _, f := range e.get("/v1/sends/"+sendID, u.token).m(e.t)["feeds"].([]any) {
		if f.(map[string]any)["feed_id"] == feedID {
			return f.(map[string]any)["post_id"].(string)
		}
	}
	e.t.Fatalf("send %s has no post in %s", sendID, feedID)
	return ""
}

func ids(ms []map[string]any) []string {
	var out []string
	for _, m := range ms {
		out = append(out, m["id"].(string))
	}
	return out
}

func has(xs []string, x string) bool {
	for _, y := range xs {
		if y == x {
			return true
		}
	}
	return false
}

// block records that blocker blocked blocked (the blocks API belongs to the chats area).
func (e *env) block(blocker, blocked *user) {
	e.t.Helper()
	if _, err := e.app.Pool.Exec(context.Background(), `INSERT INTO blocks (user_id, blocked_user_id) VALUES ($1, $2)`,
		uuid.MustParse(blocker.id), uuid.MustParse(blocked.id)); err != nil {
		e.t.Fatal(err)
	}
}

// Roles, the timeline, comments and replies with their rules, mentions, read state, edits, deletes by authors
// and moderators, closing threads, people's reactions and blocks.
func TestSocialThreads(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	anna, ben, carla, dave, eve := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carla@x.org", "Carla"),
		e.user("dave@x.org", "Dave"), e.user("eve@x.org", "Eve")

	disc := e.get("/.well-known/kinframe", "").m(t)
	if disc["social"] != true || dig(disc, "limits", "max_comment_chars") != float64(2000) || dig(disc, "limits", "max_mentions") != float64(20) ||
		dig(disc, "limits", "max_circles_per_feed") != float64(20) {
		t.Fatalf("discovery: %v", disc)
	}
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	f := e.get("/v1/feeds/"+feed, anna.token).m(t)
	if f["members_see_reactions"] != true || f["members_see_receipts"] != false || f["new_members_can_post"] != true ||
		f["members_can_create_circles"] != true || f["mute"] != "none" || f["circle_count"] != float64(0) {
		t.Fatalf("feed defaults: %v", f)
	}
	e.joinFeed(anna, ben, feed)
	e.joinFeed(anna, carla, feed)
	// Joiners become members (read, comment, react; no photos) when new_members_can_post is off.
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed, token: anna.token, body: map[string]any{"new_members_can_post": false, "posters_can_invite": true}}, 200)
	e.joinFeed(anna, dave, feed)
	if m := e.get("/v1/feeds/"+feed, dave.token).m(t); m["role"] != "member" {
		t.Fatalf("dave: %v", m)
	}
	if r := e.do(req{method: "POST", path: "/v1/sends", token: dave.token, body: map[string]any{"frame_ids": []string{}, "feed_ids": []string{feed},
		"items": []map[string]any{{"upload_id": e.photo(dave, 9)}}}}); r.StatusCode != 403 || r.code() != "missing_permission" {
		t.Fatalf("member posts: %d %s", r.StatusCode, r.body)
	}
	if r := e.feedInvite(dave, feed, "join", 403); r["code"] != "forbidden" {
		t.Fatalf("member invites: %v", r)
	}
	// Roles: owner makes Carla a member and back.
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/members/" + carla.id, token: anna.token, body: map[string]any{"role": "member"}}, 200)
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/members/" + carla.id, token: anna.token, body: map[string]any{"role": "poster"}}, 200)

	send := e.post(ben, []string{}, []string{feed}, e.photo(ben, 1))
	post := e.feedPostOf(ben, send, feed)
	for _, u := range []*user{anna, ben, carla, dave} {
		tl := e.timeline(u, "")
		if len(tl) != 1 || tl[0]["id"] != post || tl[0]["kind"] != "feed" || tl[0]["feed_name"] != "Peeters" || dig(tl[0], "poster", "display_name") != "Ben" {
			t.Fatalf("%s timeline: %v", u.name, tl)
		}
		if (u == ben) != (tl[0]["send_id"] != nil) || (u == ben || u == anna) != (tl[0]["can_remove"] == true) {
			t.Fatalf("%s: send_id/can_remove: %v", u.name, tl[0])
		}
	}
	// Seen: the poster and admins (members_see_receipts off).
	if e.timeline(ben, "")[0]["seen"] == nil || e.timeline(dave, "")[0]["seen"] != nil {
		t.Fatal("seen visibility")
	}
	if tl := e.timeline(eve, ""); len(tl) != 0 {
		t.Fatalf("stranger timeline: %v", tl)
	}
	for _, path := range []string{"/v1/posts/" + post, "/v1/posts/" + post + "/comments", "/v1/reactions/post/" + post} {
		if r := e.do(req{method: "GET", path: path, token: eve.token}); r.StatusCode != 404 {
			t.Fatalf("stranger %s: %d", path, r.StatusCode)
		}
	}
	if r := e.comment(eve, post, map[string]any{"body": "hi"}, 404); r["code"] != "not_found" {
		t.Fatalf("stranger comments: %v", r)
	}

	// --- validation
	for _, c := range []struct {
		body  map[string]any
		field string
	}{
		{map[string]any{"body": "   "}, "body"},
		{map[string]any{"body": strings.Repeat("é", 2001)}, "body"},
		{map[string]any{"body": "hi @Anna", "mentions": []map[string]any{{"user_id": anna.id, "start": 3, "length": 9}}}, "mentions"},
		{map[string]any{"body": "hi @Eve", "mentions": []map[string]any{{"user_id": eve.id, "start": 3, "length": 4}}}, "mentions"},
		{map[string]any{"body": "x", "quoted_user_id": anna.id}, "quoted_user_id"},
		{map[string]any{"body": "x", "parent_id": post}, "parent_id"},
	} {
		r := e.comment(carla, post, c.body, 422)
		errs, _ := r["errors"].([]any)
		if r["code"] != "validation_failed" || len(errs) == 0 || errs[0].(map[string]any)["field"] != c.field {
			t.Fatalf("%v: %v", c.body, r)
		}
	}

	// --- comments, replies, mentions (UTF-16 offsets, after trimming)
	annaWS := e.dialWS("/v1/ws?access_token="+anna.token, "")
	c1 := e.comment(carla, post, map[string]any{"body": "  😂 @Anna look", "mentions": []map[string]any{{"user_id": anna.id, "start": 5, "length": 5}}}, 201)
	if c1["body"] != "😂 @Anna look" || dig(c1, "author", "display_name") != "Carla" || c1["can_edit"] != true || c1["can_delete"] != true {
		t.Fatalf("comment: %v", c1)
	}
	if ms := c1["mentions"].([]any); len(ms) != 1 || dig(ms[0].(map[string]any), "start") != float64(3) {
		t.Fatalf("mentions shifted by the trim: %v", ms)
	}
	ev := annaWS.awaitWhere("comment.created", 5*time.Second, func(m map[string]any) bool { return dig(m, "comment", "id") == c1["id"] })
	if dig(ev, "comment", "can_delete") != true || dig(ev, "comment", "can_edit") != false {
		t.Fatalf("event for the admin: %v", ev)
	}
	annaWS.awaitWhere("post.updated", 5*time.Second, func(m map[string]any) bool { return dig(m, "thread", "comment_count") == float64(1) })
	c2 := e.comment(dave, post, map[string]any{"body": "Ha!", "parent_id": c1["id"]}, 201)
	c3 := e.comment(ben, post, map[string]any{"body": "@Dave yes", "parent_id": c1["id"], "quoted_user_id": dave.id}, 201)
	c4 := e.comment(anna, post, map[string]any{"body": "Lovely"}, 201)
	if r := e.comment(anna, post, map[string]any{"body": "x", "parent_id": c2["id"]}, 422); r["code"] != "validation_failed" {
		t.Fatalf("reply to a reply: %v", r)
	}
	if got := ids(e.comments(anna, post)); strings.Join(got, ",") != strings.Join([]string{c1["id"].(string), c2["id"].(string), c3["id"].(string), c4["id"].(string)}, ",") {
		t.Fatalf("thread order: %v", got)
	}
	page := e.get("/v1/posts/"+post+"/comments?limit=2", anna.token).m(t)
	if page["has_more"] != true || len(page["comments"].([]any)) != 2 {
		t.Fatalf("page: %v", page)
	}
	page = e.get("/v1/posts/"+post+"/comments?after="+c2["id"].(string), anna.token).m(t)
	if page["has_more"] != false || len(page["comments"].([]any)) != 2 || dig(page["comments"].([]any)[0].(map[string]any), "id") != c3["id"] {
		t.Fatalf("after: %v", page)
	}
	if top := e.comments(anna, post)[0]; top["reply_count"] != float64(2) {
		t.Fatalf("reply_count: %v", top)
	}
	// Unread: Anna never read the thread: everyone else's comments; the mention counts.
	th := e.get("/v1/posts/"+post, anna.token).m(t)["thread"].(map[string]any)
	if th["comment_count"] != float64(4) || th["unread_count"] != float64(3) || th["unread_mentions"] != float64(1) || len(th["latest_comments"].([]any)) != 2 ||
		dig(th["latest_comments"].([]any)[1].(map[string]any), "id") != c4["id"] || th["can_moderate"] != true {
		t.Fatalf("anna's thread: %v", th)
	}
	// Dave: Ben's reply quotes him (a reply to him).
	if th := e.get("/v1/posts/"+post, dave.token).m(t)["thread"].(map[string]any); th["unread_mentions"] != float64(1) || th["can_moderate"] != false {
		t.Fatalf("dave's thread: %v", th)
	}
	e.expect(req{method: "PUT", path: "/v1/posts/" + post + "/read", token: anna.token, body: map[string]any{"up_to": c3["id"]}}, 204)
	annaWS.awaitWhere("thread.read", 5*time.Second, func(m map[string]any) bool { return m["read_up_to"] == c3["id"] })
	e.expect(req{method: "PUT", path: "/v1/posts/" + post + "/read", token: anna.token, body: map[string]any{"up_to": c1["id"]}}, 204) // never back
	if th := e.get("/v1/posts/"+post, anna.token).m(t)["thread"].(map[string]any); th["unread_count"] != float64(0) || th["read_up_to"] != c3["id"] {
		t.Fatalf("after read: %v", th)
	}
	if r := e.do(req{method: "PUT", path: "/v1/posts/" + post + "/read", token: anna.token, body: map[string]any{"up_to": post}}); r.StatusCode != 422 {
		t.Fatalf("read up to a foreign id: %d", r.StatusCode)
	}

	// --- edits
	ed := e.expect(req{method: "PATCH", path: "/v1/posts/" + post + "/comments/" + c4["id"].(string), token: anna.token, body: map[string]any{"body": "Lovely!"}}, 200).m(t)
	if ed["body"] != "Lovely!" || ed["edited_at"] == nil {
		t.Fatalf("edit: %v", ed)
	}
	if r := e.do(req{method: "PATCH", path: "/v1/posts/" + post + "/comments/" + c1["id"].(string), token: anna.token, body: map[string]any{"body": "mine"}}); r.StatusCode != 403 {
		t.Fatalf("edit someone else's: %d", r.StatusCode)
	}

	// --- people's reactions
	sum := e.expect(req{method: "PUT", path: "/v1/reactions/post/" + post, token: carla.token, body: map[string]any{"emoji": "love"}}, 200).m(t)
	if sum["total"] != float64(1) || sum["mine"] != "love" {
		t.Fatalf("react: %v", sum)
	}
	e.expect(req{method: "PUT", path: "/v1/reactions/post/" + post, token: dave.token, body: map[string]any{"emoji": "laugh"}}, 200)
	e.expect(req{method: "PUT", path: "/v1/reactions/post/" + post, token: dave.token, body: map[string]any{"emoji": "love"}}, 200) // the latest wins
	sum = e.expect(req{method: "PUT", path: "/v1/reactions/comment/" + c1["id"].(string), token: ben.token, body: map[string]any{"emoji": "thumbs_up"}}, 200).m(t)
	if sum["total"] != float64(1) {
		t.Fatalf("comment reaction: %v", sum)
	}
	itemID := dig(e.timeline(anna, "")[0]["items"].([]any)[0].(map[string]any), "id").(string)
	if r := e.do(req{method: "PUT", path: "/v1/reactions/post_item/" + itemID, token: anna.token, body: map[string]any{"emoji": "wow"}}); r.StatusCode != 422 {
		t.Fatalf("post_item without post_id: %d", r.StatusCode)
	}
	e.expect(req{method: "PUT", path: "/v1/reactions/post_item/" + itemID + "?post_id=" + post, token: anna.token, body: map[string]any{"emoji": "wow"}}, 200)
	th = e.get("/v1/posts/"+post, anna.token).m(t)["thread"].(map[string]any)
	if dig(th, "reactions", "total") != float64(2) || dig(th["reactions"].(map[string]any)["counts"].([]any)[0].(map[string]any), "count") != float64(2) {
		t.Fatalf("post reaction row: %v", th["reactions"])
	}
	rl := e.get("/v1/reactions/post/"+post, ben.token).m(t)
	if len(rl["people"].([]any)) != 2 {
		t.Fatalf("list reactions: %v", rl)
	}
	e.expect(req{method: "PUT", path: "/v1/reactions/post/" + post, token: carla.token, body: map[string]any{"emoji": nil}}, 200)

	// --- blocks: Carla blocked Dave: his comments and his reaction's name are left out for her.
	e.block(carla, dave)
	if got := ids(e.comments(carla, post)); has(got, c2["id"].(string)) || len(got) != 3 {
		t.Fatalf("blocked author's comment: %v", got)
	}
	if rl := e.get("/v1/reactions/post/"+post, carla.token).m(t); len(rl["people"].([]any)) != 0 {
		t.Fatalf("blocked reactor listed: %v", rl)
	}
	if th := e.get("/v1/posts/"+post, carla.token).m(t)["thread"].(map[string]any); dig(th, "reactions", "total") != float64(1) {
		t.Fatalf("blocked reactor still counted: %v", th["reactions"])
	}

	// --- closing the thread: the poster or a moderator
	if r := e.do(req{method: "PUT", path: "/v1/posts/" + post + "/thread", token: dave.token, body: map[string]any{"state": "closed"}}); r.StatusCode != 403 {
		t.Fatalf("member closes: %d", r.StatusCode)
	}
	ts := e.expect(req{method: "PUT", path: "/v1/posts/" + post + "/thread", token: ben.token, body: map[string]any{"state": "closed"}}, 200).m(t)
	if ts["state"] != "closed" || ts["can_comment"] != false {
		t.Fatalf("closed: %v", ts)
	}
	if r := e.comment(carla, post, map[string]any{"body": "late"}, 409); r["code"] != "comments_closed" {
		t.Fatalf("closed thread: %v", r)
	}
	e.expect(req{method: "PUT", path: "/v1/reactions/comment/" + c4["id"].(string), token: carla.token, body: map[string]any{"emoji": "love"}}, 200) // still allowed
	e.expect(req{method: "PUT", path: "/v1/posts/" + post + "/thread", token: anna.token, body: map[string]any{"state": "open"}}, 200)

	// --- deletes: the author's top comment with replies stays as a tombstone
	if r := e.do(req{method: "DELETE", path: "/v1/posts/" + post + "/comments/" + c1["id"].(string), token: dave.token}); r.StatusCode != 403 {
		t.Fatalf("member deletes someone else's: %d", r.StatusCode)
	}
	e.expect(req{method: "DELETE", path: "/v1/posts/" + post + "/comments/" + c1["id"].(string), token: carla.token}, 204)
	cs := e.comments(anna, post)
	if cs[0]["id"] != c1["id"] || cs[0]["deleted"] != true || cs[0]["body"] != nil {
		t.Fatalf("tombstone: %v", cs[0])
	}
	if r := e.do(req{method: "PUT", path: "/v1/reactions/comment/" + c1["id"].(string), token: anna.token, body: map[string]any{"emoji": "love"}}); r.StatusCode != 422 {
		t.Fatalf("react to a tombstone: %d", r.StatusCode)
	}
	// A moderator removes Ben's reply: gone for everyone but Ben, who sees removed_by_admin.
	e.expect(req{method: "DELETE", path: "/v1/posts/" + post + "/comments/" + c3["id"].(string), token: anna.token}, 204)
	if has(ids(e.comments(anna, post)), c3["id"].(string)) {
		t.Fatal("removed comment still shown")
	}
	var mine map[string]any
	for _, c := range e.comments(ben, post) {
		if c["id"] == c3["id"] {
			mine = c
		}
	}
	if mine == nil || mine["removed_by_admin"] != true || mine["can_delete"] != false {
		t.Fatalf("author's removed comment: %v", mine)
	}
	// Dave deletes the last live reply: the tombstone goes with it.
	e.expect(req{method: "DELETE", path: "/v1/posts/" + post + "/comments/" + c2["id"].(string), token: dave.token}, 204)
	if got := ids(e.comments(anna, post)); len(got) != 1 || got[0] != c4["id"] {
		t.Fatalf("after deletes: %v", got)
	}
	if th := e.get("/v1/posts/"+post, anna.token).m(t)["thread"].(map[string]any); th["comment_count"] != float64(1) {
		t.Fatalf("comment_count: %v", th)
	}

	// --- feed mute and thread mute are stored
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/preferences", token: dave.token, body: map[string]any{"mute": "posts"}}, 200)
	if m := e.get("/v1/feeds/"+feed, dave.token).m(t); m["mute"] != "posts" {
		t.Fatalf("mute: %v", m)
	}
	e.expect(req{method: "PUT", path: "/v1/posts/" + post + "/mute", token: dave.token, body: map[string]any{"muted": true}}, 204)
	if th := e.get("/v1/posts/"+post, dave.token).m(t)["thread"].(map[string]any); th["muted"] != true {
		t.Fatalf("thread mute: %v", th)
	}

	// --- removing the post: post.removed, and it leaves the timeline
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/posts/" + post, token: anna.token}, 204)
	annaWS.awaitWhere("post.removed", 5*time.Second, func(m map[string]any) bool { return m["post_id"] == post })
	if tl := e.timeline(dave, ""); len(tl) != 0 {
		t.Fatalf("removed post on the timeline: %v", tl)
	}
}

// Circles: who creates and manages them, names and limits, posts shared with a circle (visibility, media,
// frames they reach) and deleting a circle.
func TestSocialCircles(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	anna, ben, carla, dave, eve := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carla@x.org", "Carla"),
		e.user("dave@x.org", "Dave"), e.user("eve@x.org", "Eve")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters", "auto_approve_subscriptions": true})
	for _, u := range []*user{ben, carla, dave} {
		e.joinFeed(anna, u, feed)
	}
	dO, oma := e.claimSized(ben, 1280, 800)
	dK, kitchen := e.claimSized(carla, 1024, 600)
	e.heartbeat(dO, "feed_backfill")
	e.heartbeat(dK, "feed_backfill")
	for _, x := range []struct {
		u     *user
		frame string
	}{{ben, oma}, {carla, kitchen}} {
		if s := e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/subscriptions", token: x.u.token,
			body: map[string]any{"frame_id": x.frame, "history": "all"}}, 201).m(t); s["status"] != "active" {
			t.Fatalf("subscription: %v", s)
		}
	}

	// Dave (a poster) creates a circle with Ben: he is its admin; its chat exists.
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/circles", token: dave.token, body: map[string]any{"name": "Kids", "member_ids": []string{eve.id}}}); r.StatusCode != 422 {
		t.Fatalf("circle with a stranger: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/circles", token: dave.token, body: map[string]any{"name": "Kids", "member_ids": []string{}, "frame_ids": []string{kitchen + "x"}}}); r.StatusCode != 400 && r.StatusCode != 422 {
		t.Fatalf("bad frame id: %d", r.StatusCode)
	}
	c := e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/circles", token: dave.token, body: map[string]any{"name": "Kids", "member_ids": []string{ben.id}}}, 201).m(t)
	circle := c["id"].(string)
	if c["my_role"] != "admin" || c["member_count"] != float64(2) || c["conversation_id"] == nil {
		t.Fatalf("circle: %v", c)
	}
	if fr := c["frames_reached"].([]any); len(fr) != 1 || dig(fr[0].(map[string]any), "frame_id") != oma {
		t.Fatalf("frames reached (Ben manages Oma's frame): %v", fr)
	}
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/circles", token: ben.token, body: map[string]any{"name": "kids", "member_ids": []string{}}}); r.StatusCode != 422 || !strings.Contains(string(r.body), "duplicate") {
		t.Fatalf("duplicate name: %d %s", r.StatusCode, r.body)
	}
	// Carla is not in it: she doesn't list it; Anna (owner) does, without its chat.
	if l := e.list("/v1/feeds/"+feed+"/circles", carla.token); len(l) != 0 {
		t.Fatalf("carla's circles: %v", l)
	}
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed + "/circles/" + circle, token: carla.token}); r.StatusCode != 404 {
		t.Fatalf("carla gets it: %d", r.StatusCode)
	}
	if l := e.list("/v1/feeds/"+feed+"/circles", anna.token); len(l) != 1 || l[0]["my_role"] != nil || l[0]["conversation_id"] != nil {
		t.Fatalf("owner's view: %v", l)
	}
	if r := e.do(req{method: "PATCH", path: "/v1/feeds/" + feed + "/circles/" + circle, token: ben.token, body: map[string]any{"name": "Mine"}}); r.StatusCode != 403 {
		t.Fatalf("circle member renames: %d", r.StatusCode)
	}
	if r := e.do(req{method: "PATCH", path: "/v1/feeds/" + feed + "/circles/" + circle, token: dave.token, body: map[string]any{"frame_ids": []string{uuid.NewString()}}}); r.StatusCode != 422 {
		t.Fatalf("unsubscribed frame: %d", r.StatusCode)
	}
	// Only owner/admins create circles once members_can_create_circles is off.
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed, token: anna.token, body: map[string]any{"members_can_create_circles": false}}, 200)
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/circles", token: carla.token, body: map[string]any{"name": "Other", "member_ids": []string{}}}); r.StatusCode != 403 {
		t.Fatalf("poster creates with the setting off: %d", r.StatusCode)
	}

	// --- sharing with the circle
	up := e.photo(dave, 2)
	if r := e.do(req{method: "POST", path: "/v1/sends", token: carla.token, body: map[string]any{"frame_ids": []string{}, "feed_ids": []string{feed},
		"circle_ids": []string{circle}, "items": []map[string]any{{"upload_id": e.photo(carla, 3)}}}}); r.StatusCode != 422 {
		t.Fatalf("share with a circle you're not in: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/sends", token: dave.token, body: map[string]any{"frame_ids": []string{}, "feed_ids": []string{},
		"circle_ids": []string{circle}, "items": []map[string]any{{"upload_id": up}}}}); r.StatusCode != 422 {
		t.Fatalf("circle without its feed: %d %s", r.StatusCode, r.body)
	}
	sd := e.expect(req{method: "POST", path: "/v1/sends", token: dave.token, body: map[string]any{"frame_ids": []string{}, "feed_ids": []string{feed},
		"circle_ids": []string{circle}, "items": []map[string]any{{"upload_id": up}}}}, 202).m(t)
	post := e.feedPostOf(dave, sd["id"].(string), feed)
	tl := e.timeline(ben, "")
	if len(tl) != 1 || tl[0]["circle_id"] != circle || tl[0]["circle_name"] != "Kids" {
		t.Fatalf("circle member's timeline: %v", tl)
	}
	if l := e.timeline(dave, "?circle_id="+circle); len(l) != 1 {
		t.Fatalf("circle filter: %v", l)
	}
	item := dig(tl[0]["items"].([]any)[0].(map[string]any), "id").(string)
	for _, u := range []*user{anna, carla} { // the owner too: circles' posts are for the circle
		if l := e.timeline(u, ""); len(l) != 0 {
			t.Fatalf("%s sees the circle post: %v", u.name, l)
		}
		if r := e.do(req{method: "GET", path: "/v1/posts/" + post, token: u.token}); r.StatusCode != 404 {
			t.Fatalf("%s gets it: %d", u.name, r.StatusCode)
		}
		if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed + "/posts/" + post, token: u.token}); r.StatusCode != 404 {
			t.Fatalf("%s gets the feed post: %d", u.name, r.StatusCode)
		}
		if l := e.posts(feed, u.token); len(l) != 0 {
			t.Fatalf("%s lists it: %v", u.name, l)
		}
		if r := e.do(req{method: "GET", path: "/v1/items/" + item + "/media/thumb_256", token: u.token}); r.StatusCode != 404 {
			t.Fatalf("%s gets its media: %d", u.name, r.StatusCode)
		}
	}
	if fp := e.get("/v1/feeds/"+feed+"/posts/"+post, ben.token).m(t); fp["circle_id"] != circle || fp["thread"] == nil {
		t.Fatalf("feed post: %v", fp)
	}
	eventually(t, 60*time.Second, "circle member gets the media", func() bool {
		return e.do(req{method: "GET", path: "/v1/items/" + item + "/media/thumb_256", token: ben.token}).StatusCode == 302
	})
	// Frames: Oma's (Ben manages it) gets the photo, the kitchen frame (Carla) doesn't.
	eventually(t, 60*time.Second, "circle post on Oma's frame", func() bool { return len(e.library(ben, oma, "")) == 1 })
	if l := e.library(carla, kitchen, ""); len(l) != 0 {
		t.Fatalf("kitchen frame got a circle post: %v", l)
	}
	// Anna can't comment on what she can't see; Ben can; Dave (circle admin) moderates.
	e.comment(anna, post, map[string]any{"body": "hi"}, 404)
	c1 := e.comment(ben, post, map[string]any{"body": "Nice"}, 201)
	if th := e.get("/v1/posts/"+post, dave.token).m(t)["thread"].(map[string]any); th["can_moderate"] != true {
		t.Fatalf("circle admin moderates: %v", th)
	}
	if r := e.do(req{method: "DELETE", path: "/v1/feeds/" + feed + "/posts/" + post, token: anna.token}); r.StatusCode != 404 {
		t.Fatalf("feed owner removes a circle post she can't see: %d", r.StatusCode)
	}
	_ = c1

	// --- members: add Carla; remove Ben (also by himself)
	cc := e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/circles/" + circle + "/members", token: anna.token,
		body: map[string]any{"user_ids": []string{carla.id, ben.id}}}, 200).m(t)
	if cc["member_count"] != float64(3) {
		t.Fatalf("add members: %v", cc)
	}
	if fr := cc["frames_reached"].([]any); len(fr) != 2 {
		t.Fatalf("frames reached with Carla: %v", fr)
	}
	// Later member changes don't move existing placements.
	if l := e.library(carla, kitchen, ""); len(l) != 0 {
		t.Fatalf("existing circle post moved to the kitchen frame: %v", l)
	}
	if l := e.timeline(carla, ""); len(l) != 1 {
		t.Fatalf("carla now sees the circle's post: %v", l)
	}
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/circles/" + circle + "/members/" + carla.id, token: dave.token, body: map[string]any{"role": "admin"}}, 200)
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/circles/" + circle + "/members/" + ben.id, token: ben.token}, 204)
	if l := e.timeline(ben, ""); len(l) != 0 {
		t.Fatalf("ben left the circle but sees its post: %v", l)
	}

	// --- deleting the circle removes its posts everywhere and archives its chat
	daveWS := e.dialWS("/v1/ws?access_token="+dave.token, "")
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/circles/" + circle, token: carla.token}, 204)
	daveWS.awaitWhere("circle.removed", 5*time.Second, func(m map[string]any) bool { return m["circle_id"] == circle })
	if l := e.timeline(dave, ""); len(l) != 0 {
		t.Fatalf("deleted circle's post: %v", l)
	}
	eventually(t, 60*time.Second, "circle post leaves Oma's frame", func() bool { return len(e.library(ben, oma, "")) == 0 })
	var archived bool
	if err := e.app.Pool.QueryRow(context.Background(), `SELECT archived_at IS NOT NULL FROM conversations WHERE circle_id = $1`, uuid.MustParse(circle)).Scan(&archived); err != nil || !archived {
		t.Fatalf("circle chat archived: %v %v", archived, err)
	}
	if m := e.get("/v1/feeds/"+feed, anna.token).m(t); m["circle_count"] != float64(0) {
		t.Fatalf("circle_count: %v", m)
	}

	// --- the limit
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed, token: anna.token, body: map[string]any{"members_can_create_circles": true}}, 200)
	for i := 0; i < 20; i++ {
		e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/circles", token: anna.token, body: map[string]any{"name": "C" + strings.Repeat("x", i), "member_ids": []string{}}}, 201)
	}
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/circles", token: anna.token, body: map[string]any{"name": "One too many", "member_ids": []string{}}}); r.StatusCode != 409 || r.code() != "limit_reached" {
		t.Fatalf("circle limit: %d %s", r.StatusCode, r.body)
	}
}

// Frame posts: direct sends are posts for the sender and the frame's members with see_library; the frame's
// reactions and quick replies become frame entries; unsending removes the post.
func TestSocialFramePosts(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	anna, ben, carla := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carla@x.org", "Carla")
	d, frame := e.claim(anna)
	e.join(ben, d)
	e.join(carla, d)
	e.setPerms(anna, frame, carla, map[string]any{"see_library": false})
	sendID := e.sendOne(ben, []string{frame}, map[string]any{"upload_id": e.photo(ben, 1)})
	s := e.waitSend(ben, sendID)
	pid := s["frames"].([]any)[0].(map[string]any)["placements"].([]any)[0].(map[string]any)["placement_id"].(string)

	tl := e.timeline(ben, "")
	if len(tl) != 1 || tl[0]["kind"] != "frame" || tl[0]["frame_id"] != frame || len(tl[0]["items"].([]any)) != 1 || tl[0]["can_remove"] != true {
		t.Fatalf("sender's timeline: %v", tl)
	}
	post := tl[0]["id"].(string)
	if l := e.timeline(anna, "?frame_id="+frame); len(l) != 1 || l[0]["id"] != post || dig(l[0], "thread", "can_moderate") != true {
		t.Fatalf("frame owner's timeline: %v", l)
	}
	if l := e.timeline(anna, "?feed_id="+uuid.NewString()); len(l) != 0 {
		t.Fatalf("feed filter keeps frame posts out: %v", l)
	}
	if l := e.timeline(carla, ""); len(l) != 0 {
		t.Fatalf("member without see_library: %v", l)
	}
	e.comment(carla, post, map[string]any{"body": "hi"}, 404)

	benWS := e.dialWS("/v1/ws?access_token="+ben.token, "")
	e.comment(anna, post, map[string]any{"body": "Thanks Ben"}, 201)
	e.expect(req{method: "PUT", path: "/v1/device/placements/" + pid + "/reaction", token: d.token, body: map[string]any{"reaction": "love"}}, 204)
	ev := benWS.awaitWhere("comment.created", 5*time.Second, func(m map[string]any) bool { return dig(m, "comment", "kind") == "frame" })
	if dig(ev, "comment", "frame", "reaction") != "love" || dig(ev, "comment", "frame", "frame_id") != frame {
		t.Fatalf("frame entry: %v", ev)
	}
	e.expect(req{method: "PUT", path: "/v1/device/placements/" + pid + "/reaction", token: d.token, body: map[string]any{"reaction": "wow"}}, 204)
	e.expect(req{method: "POST", path: "/v1/device/placements/" + pid + "/replies", token: d.token, body: map[string]any{"preset": "call_me"}}, 204)
	cs := e.comments(ben, post)
	if len(cs) != 3 || dig(cs[1], "frame", "reaction") != "wow" || dig(cs[2], "frame", "reply") != "call_me" || cs[1]["can_delete"] != false {
		t.Fatalf("thread with frame entries: %v", cs)
	}
	th := e.get("/v1/posts/"+post, ben.token).m(t)["thread"].(map[string]any)
	if th["comment_count"] != float64(1) || len(dig(th, "reactions", "frames").([]any)) != 1 || th["unread_count"] != float64(1) {
		t.Fatalf("frame entries don't count: %v", th)
	}
	if r := e.do(req{method: "DELETE", path: "/v1/posts/" + post + "/comments/" + cs[1]["id"].(string), token: anna.token}); r.StatusCode != 403 {
		t.Fatalf("delete a frame entry: %d", r.StatusCode)
	}
	if r := e.do(req{method: "PUT", path: "/v1/reactions/comment/" + cs[1]["id"].(string), token: anna.token, body: map[string]any{"emoji": "love"}}); r.StatusCode != 422 {
		t.Fatalf("react to a frame entry: %d", r.StatusCode)
	}
	e.expect(req{method: "PUT", path: "/v1/device/placements/" + pid + "/reaction", token: d.token, body: map[string]any{"reaction": nil}}, 204)
	if cs := e.comments(ben, post); len(cs) != 2 {
		t.Fatalf("reaction entry stays after the reaction went: %v", cs)
	}

	e.expect(req{method: "DELETE", path: "/v1/sends/" + sendID, token: ben.token}, 204)
	benWS.awaitWhere("post.removed", 5*time.Second, func(m map[string]any) bool { return m["post_id"] == post })
	if l := e.timeline(ben, ""); len(l) != 0 {
		t.Fatalf("unsent frame post: %v", l)
	}
	if r := e.do(req{method: "GET", path: "/v1/posts/" + post, token: anna.token}); r.StatusCode != 404 {
		t.Fatalf("unsent post: %d", r.StatusCode)
	}
}

// Pushes: mention at once, comment bundles (10 minutes, one push at the end of the window), muted threads,
// reaction_person for the first three people and the 19:00 digest.
func TestSocialPushes(t *testing.T) {
	requireMedia(t)
	e := newEnv(t, withUnifiedPush)
	anna, ben, carla, dave, fred := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carla@x.org", "Carla"),
		e.user("dave@x.org", "Dave"), e.user("fred@x.org", "Fred")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	for _, u := range []*user{ben, carla, dave, fred} {
		e.joinFeed(anna, u, feed)
	}
	e.expect(req{method: "PATCH", path: "/v1/me", token: anna.token, body: map[string]any{"timezone": "UTC"}}, 200)
	sink := e.pushSink(t, anna)
	t0 := time.Date(2026, 11, 19, 10, 0, 0, 0, time.UTC)
	e.setClock(t0)
	ctx := context.Background()

	send := e.post(anna, []string{}, []string{feed}, e.photo(anna, 1))
	post := e.feedPostOf(anna, send, feed)

	// The first comment in a quiet thread is pushed at once.
	e.comment(carla, post, map[string]any{"body": "Look at her face 😂"}, 201)
	m := sink.next(t, "first comment")
	if m.Key != "comment" || m.Tag != "comment:"+post || m.Body != "Carla commented on your photo: Look at her face 😂" || m.IDs["post_id"] != post || m.IDs["feed_id"] != feed {
		t.Fatalf("comment push: %+v", m)
	}
	// Two more within 10 minutes are held...
	e.setClock(t0.Add(3 * time.Minute))
	e.comment(dave, post, map[string]any{"body": "So cute"}, 201)
	e.comment(ben, post, map[string]any{"body": "Haha"}, 201)
	// ...but a mention is pushed at once.
	e.comment(dave, post, map[string]any{"body": "@Anna call me", "mentions": []map[string]any{{"user_id": anna.id, "start": 0, "length": 5}}}, 201)
	if m := sink.next(t, "mention"); m.Key != "mention" || m.Body != "Dave mentioned you: @Anna call me" || m.Tag != "" {
		t.Fatalf("mention push: %+v", m)
	}
	sink.none(t, 300*time.Millisecond)
	if n, err := e.app.API.SocialTick(ctx, t0.Add(9*time.Minute)); err != nil || n != 0 {
		t.Fatalf("tick inside the window: %d %v", n, err)
	}
	// At the end of the window: one push for the held ones.
	if n, err := e.app.API.SocialTick(ctx, t0.Add(10*time.Minute+time.Second)); err != nil || n != 1 {
		t.Fatalf("tick after the window: %d %v", n, err)
	}
	m = sink.next(t, "bundle")
	if m.Key != "comment" || m.Tag != "comment:"+post || !strings.Contains(m.Body, " and 2 others commented: ") {
		t.Fatalf("bundled push: %+v", m)
	}
	// A muted thread pushes nothing.
	e.expect(req{method: "PUT", path: "/v1/posts/" + post + "/mute", token: anna.token, body: map[string]any{"muted": true}}, 204)
	e.setClock(t0.Add(time.Hour))
	e.comment(fred, post, map[string]any{"body": "Late but lovely @Anna", "mentions": []map[string]any{{"user_id": anna.id, "start": 16, "length": 5}}}, 201)
	sink.none(t, 300*time.Millisecond)
	e.expect(req{method: "PUT", path: "/v1/posts/" + post + "/mute", token: anna.token, body: map[string]any{"muted": false}}, 204)
	// The family mute `posts` keeps mentions, `all` doesn't.
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/preferences", token: anna.token, body: map[string]any{"mute": "posts"}}, 200)
	e.setClock(t0.Add(2 * time.Hour))
	e.comment(fred, post, map[string]any{"body": "again"}, 201)
	sink.none(t, 300*time.Millisecond)
	e.comment(fred, post, map[string]any{"body": "@Anna!", "mentions": []map[string]any{{"user_id": anna.id, "start": 0, "length": 5}}}, 201)
	if m := sink.next(t, "mention with posts muted"); m.Key != "mention" {
		t.Fatalf("mention push: %+v", m)
	}
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/preferences", token: anna.token, body: map[string]any{"mute": "none"}}, 200)

	// Replies notify the top comment's author.
	top := e.comment(anna, post, map[string]any{"body": "Thanks all"}, 201)
	e.comment(ben, post, map[string]any{"body": "Anytime", "parent_id": top["id"]}, 201)
	if m := sink.next(t, "reply"); m.Key != "comment_reply" || m.Tag != "comment_reply:"+top["id"].(string) || m.IDs["parent_id"] != top["id"] {
		t.Fatalf("reply push: %+v", m)
	}
	// Blocking the author stops pushes.
	e.block(anna, ben)
	e.comment(ben, post, map[string]any{"body": "again", "parent_id": top["id"]}, 201)
	sink.none(t, 300*time.Millisecond)

	// --- reactions: the first three people are pushed, the fourth waits for the 19:00 digest.
	post2 := e.feedPostOf(anna, e.post(anna, []string{}, []string{feed}, e.photo(anna, 2)), feed)
	var bodies []string
	for i, u := range []*user{carla, dave, fred} {
		e.expect(req{method: "PUT", path: "/v1/reactions/post/" + post2, token: u.token, body: map[string]any{"emoji": "love"}}, 200)
		m := sink.next(t, "reaction")
		if m.Key != "reaction_person" || m.Tag != "reaction:post:"+post2 || m.IDs["target_id"] != post2 {
			t.Fatalf("reaction push %d: %+v", i, m)
		}
		bodies = append(bodies, m.Body)
	}
	if bodies[0] != "Carla reacted ❤️ to your photo" || bodies[2] != "Fred and 2 others reacted ❤️ to your photo" {
		t.Fatalf("reaction texts: %v", bodies)
	}
	// Changing a reaction never pushes; the fourth person goes into the digest.
	e.expect(req{method: "PUT", path: "/v1/reactions/post/" + post2, token: carla.token, body: map[string]any{"emoji": "wow"}}, 200)
	if _, err := e.app.Pool.Exec(ctx, `DELETE FROM blocks WHERE user_id = $1`, uuid.MustParse(anna.id)); err != nil {
		t.Fatal(err)
	}
	e.expect(req{method: "PUT", path: "/v1/reactions/post/" + post2, token: ben.token, body: map[string]any{"emoji": "pray"}}, 200)
	sink.none(t, 300*time.Millisecond)
	// Others' comment bundles are due by now (they have no push endpoints); Anna has none held.
	if _, err := e.app.API.SocialTick(ctx, time.Date(2026, 11, 19, 18, 58, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	sink.none(t, 300*time.Millisecond)
	if n, _ := e.app.API.SocialTick(ctx, time.Date(2026, 11, 19, 18, 59, 0, 0, time.UTC)); n != 0 {
		t.Fatalf("digest before 19:00: %d", n)
	}
	if n, _ := e.app.API.SocialTick(ctx, time.Date(2026, 11, 19, 19, 0, 30, 0, time.UTC)); n != 1 {
		t.Fatalf("digest at 19:00: %d", n)
	}
	if m := sink.next(t, "digest"); m.Key != "reaction_digest" || m.Tag != "reaction_digest" || m.Body != "One more reaction to your photos, comments and messages today" {
		t.Fatalf("digest: %+v", m)
	}
	if n, _ := e.app.API.SocialTick(ctx, time.Date(2026, 11, 19, 19, 5, 0, 0, time.UTC)); n != 0 {
		t.Fatalf("digest twice: %d", n)
	}
}

// Comment bodies are sealed at rest when the server has a secrets key, plain otherwise; the API reads both.
func TestSocialBodiesAtRest(t *testing.T) {
	requireMedia(t)
	for _, sealed := range []bool{false, true} {
		var opts []option
		if sealed {
			opts = append(opts, func(c *config.Config) { c.SecretsKey = []byte(strings.Repeat("k", 32)) })
		}
		e := newEnv(t, opts...)
		anna := e.user("anna@x.org", "Anna")
		feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
		post := e.feedPostOf(anna, e.post(anna, []string{}, []string{feed}, e.photo(anna, 1)), feed)
		c := e.comment(anna, post, map[string]any{"body": "secret family text"}, 201)
		var body *string
		var enc []byte
		if err := e.app.Pool.QueryRow(context.Background(), `SELECT body, body_enc FROM comments WHERE id = $1`, uuid.MustParse(c["id"].(string))).Scan(&body, &enc); err != nil {
			t.Fatal(err)
		}
		if sealed != (body == nil) || sealed != (enc != nil) || strings.Contains(string(enc), "secret") {
			t.Fatalf("sealed=%v: body %v enc %d bytes", sealed, body, len(enc))
		}
		if got := e.comments(anna, post); got[0]["body"] != "secret family text" {
			t.Fatalf("sealed=%v: read back %v", sealed, got)
		}
		ed := e.expect(req{method: "PATCH", path: "/v1/posts/" + post + "/comments/" + c["id"].(string), token: anna.token, body: map[string]any{"body": "still secret"}}, 200).m(t)
		if ed["body"] != "still secret" {
			t.Fatalf("edit: %v", ed)
		}
	}
}
