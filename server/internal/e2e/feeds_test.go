package e2e

import (
	"fmt"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

// Feeds (docs/spec/feeds.md, README §5.12) over real HTTP.

func (e *env) createFeed(u *user, body map[string]any) string {
	e.t.Helper()
	return e.expect(req{method: "POST", path: "/v1/feeds", token: u.token, body: body}, 201).m(e.t)["id"].(string)
}

func (e *env) feedInvite(u *user, feedID, purpose string, status int) map[string]any {
	e.t.Helper()
	r := e.expect(req{method: "POST", path: "/v1/feeds/" + feedID + "/invites", token: u.token, body: map[string]any{"purpose": purpose}}, status)
	if status != 201 {
		return map[string]any{"code": r.code()}
	}
	return r.m(e.t)
}

// joinFeed makes u a poster through a join invite of an owner/admin (no approval).
func (e *env) joinFeed(admin, u *user, feedID string) {
	e.t.Helper()
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feedID, token: admin.token, body: map[string]any{"invite_requires_approval": false}}, 200)
	inv := e.feedInvite(admin, feedID, "join", 201)
	r := e.expect(req{method: "POST", path: "/v1/feed-invites/" + inv["token"].(string) + "/accept", token: u.token}, 200).m(e.t)
	if r["status"] != "joined" {
		e.t.Fatalf("join feed: %v", r)
	}
}

func (e *env) changes(d *device, since float64) map[string]any {
	e.t.Helper()
	return e.get(fmt.Sprintf("/v1/device/changes?since=%d", int64(since)), d.token).m(e.t)
}

func (e *env) heartbeat(d *device, features ...string) {
	e.t.Helper()
	body := map[string]any{"app_version": "0.7.0", "app_version_code": 70099, "uptime_s": 60}
	if features != nil {
		body["features"] = features
	}
	e.expect(req{method: "POST", path: "/v1/device/heartbeat", token: d.token, body: body}, 204)
}

func (e *env) library(u *user, frameID, query string) []map[string]any {
	e.t.Helper()
	var out []map[string]any
	for _, p := range e.get("/v1/frames/"+frameID+"/placements?visibility=all"+query, u.token).m(e.t)["placements"].([]any) {
		out = append(out, p.(map[string]any))
	}
	return out
}

func (e *env) post(u *user, frames, feeds []string, uploads ...string) string {
	e.t.Helper()
	items := []map[string]any{}
	for _, up := range uploads {
		items = append(items, map[string]any{"upload_id": up})
	}
	return e.expect(req{method: "POST", path: "/v1/sends", token: u.token,
		body: map[string]any{"frame_ids": frames, "feed_ids": feeds, "items": items}}, 202).m(e.t)["id"].(string)
}

func (e *env) photo(u *user, seed int) string {
	e.t.Helper()
	return e.upload(u, mediatest.JPEG(1600+seed, 1200, 0, "2024:07:01 18:30:00", false), fmt.Sprintf("p%d.jpg", seed), "image/jpeg")
}

var codeRe = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{8}$`)

// Membership, roles, invites, codes and limits: the permission matrix of feeds.md §4.1.
func TestFeedMembersAndInvites(t *testing.T) {
	e := newEnv(t, func(c *config.Config) { c.DefaultLimits.MaxFeedsPerUser = 2 })
	anna, ben, carl, dave := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carl@x.org", "Carl"), e.user("dave@x.org", "Dave")

	disc := e.get("/.well-known/kinframe", "").m(t)
	if disc["feeds"] != true || dig(disc, "limits", "max_feeds_per_user") != float64(2) || dig(disc, "limits", "max_subscriptions_per_feed") != float64(100) {
		t.Fatalf("discovery: %v", disc)
	}
	feed := e.createFeed(anna, map[string]any{"name": "Peeters kids"})
	f := e.get("/v1/feeds/"+feed, anna.token).m(t)
	if f["role"] != "owner" || f["owner_name"] != "Anna" || f["invite_requires_approval"] != true || f["posters_can_invite"] != false || f["member_count"] != float64(1) {
		t.Fatalf("feed: %v", f)
	}
	// Feeds are never discoverable.
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed, token: ben.token}); r.StatusCode != 404 || r.code() != "not_found" {
		t.Fatalf("non-member: %d %s", r.StatusCode, r.body)
	}

	// --- join invite with approval, typed as a code
	inv := e.feedInvite(anna, feed, "join", 201)
	code := inv["code"].(string)
	if !codeRe.MatchString(code) || strings.Trim(code, "0123456789") == "" || inv["url"] != e.url+"/fi/"+inv["token"].(string) {
		t.Fatalf("invite: %v", inv)
	}
	typed := strings.ToLower(code[:4] + "-" + code[4:])
	pv := e.expect(req{method: "POST", path: "/v1/feed-invites:lookup", token: ben.token, body: map[string]any{"code": typed}}, 200).m(t)
	if pv["token"] != inv["token"] || pv["feed_name"] != "Peeters kids" || pv["requires_approval"] != true || pv["already_member"] != false || pv["invited_by_name"] != "Anna" {
		t.Fatalf("lookup: %v", pv)
	}
	if r := e.do(req{method: "POST", path: "/v1/feed-invites:lookup", token: ben.token, body: map[string]any{"code": "ZZZZ-ZZZZ"}}); r.StatusCode != 404 || r.code() != "invite_invalid" {
		t.Fatalf("unknown code: %d %s", r.StatusCode, r.body)
	}
	annaWS := e.dialWS("/v1/ws?access_token="+anna.token, "")
	acc := e.expect(req{method: "POST", path: "/v1/feed-invites/" + inv["token"].(string) + "/accept", token: ben.token}, 200).m(t)
	if acc["status"] != "pending" || acc["join_request_id"] == nil {
		t.Fatalf("accept: %v", acc)
	}
	annaWS.await("feed.join_request", 5*time.Second)
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed + "/join-requests", token: ben.token}); r.StatusCode != 404 {
		t.Fatalf("pending requester lists requests: %d", r.StatusCode)
	}
	if jrs := e.list("/v1/feeds/"+feed+"/join-requests", anna.token); len(jrs) != 1 || dig(jrs[0], "user", "display_name") != "Ben" {
		t.Fatalf("join requests: %v", jrs)
	}
	reqID := acc["join_request_id"].(string)
	e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/join-requests/" + reqID + "/approve", token: anna.token}, 204)
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/join-requests/" + reqID + "/deny", token: anna.token}); r.StatusCode != 409 || r.code() != "already_decided" {
		t.Fatalf("decide twice: %d %s", r.StatusCode, r.body)
	}
	if m := e.get("/v1/feeds/"+feed, ben.token).m(t); m["role"] != "poster" || m["pending_join_request_count"] != float64(0) {
		t.Fatalf("ben: %v", m)
	}

	// --- who may invite
	if r := e.feedInvite(ben, feed, "join", 403); r["code"] != "forbidden" {
		t.Fatalf("poster invite: %v", r)
	}
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed + "/invites", token: ben.token}); r.StatusCode != 403 {
		t.Fatalf("poster lists invites: %d", r.StatusCode)
	}
	if r := e.do(req{method: "PATCH", path: "/v1/feeds/" + feed, token: ben.token, body: map[string]any{"name": "Mine"}}); r.StatusCode != 403 {
		t.Fatalf("poster renames: %d", r.StatusCode)
	}
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed, token: anna.token, body: map[string]any{"posters_can_invite": true, "invite_requires_approval": false}}, 200)
	benInv := e.feedInvite(ben, feed, "join", 201)
	if r := e.feedInvite(ben, feed, "subscribe", 403); r["code"] != "forbidden" {
		t.Fatalf("poster subscribe invite: %v", r)
	}
	if l := e.list("/v1/feeds/"+feed+"/invites", ben.token); len(l) != 1 || l[0]["id"] != benInv["id"] {
		t.Fatalf("poster's invites: %v", l)
	}
	if l := e.list("/v1/feeds/"+feed+"/invites", anna.token); len(l) != 2 {
		t.Fatalf("admin's invites: %v", l)
	}
	if r := e.expect(req{method: "POST", path: "/v1/feed-invites/" + benInv["token"].(string) + "/accept", token: carl.token}, 200).m(t); r["status"] != "joined" || dig(r, "feed", "role") != "poster" {
		t.Fatalf("carl joins: %v", r)
	}
	if r := e.expect(req{method: "POST", path: "/v1/feed-invites/" + benInv["token"].(string) + "/accept", token: carl.token}, 200).m(t); r["status"] != "already_member" {
		t.Fatalf("join twice: %v", r)
	}
	// Ben revokes his own invite; Carl (a poster) can't revoke Anna's.
	if r := e.do(req{method: "DELETE", path: "/v1/feeds/" + feed + "/invites/" + inv["id"].(string), token: carl.token}); r.StatusCode != 403 {
		t.Fatalf("poster revokes another's invite: %d", r.StatusCode)
	}
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/invites/" + benInv["id"].(string), token: ben.token}, 204)
	if r := e.do(req{method: "GET", path: "/v1/feed-invites/" + benInv["token"].(string), token: dave.token}); r.StatusCode != 410 || r.code() != "invite_expired" {
		t.Fatalf("revoked invite: %d %s", r.StatusCode, r.body)
	}

	// --- roles: owner changes anyone, admins only posters
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/members/" + ben.id, token: anna.token, body: map[string]any{"role": "admin"}}, 200)
	if r := e.do(req{method: "PATCH", path: "/v1/feeds/" + feed + "/members/" + anna.id, token: ben.token, body: map[string]any{"role": "poster"}}); r.StatusCode != 403 {
		t.Fatalf("admin demotes owner: %d", r.StatusCode)
	}
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/members/" + carl.id, token: ben.token, body: map[string]any{"role": "admin"}}, 200)
	if r := e.do(req{method: "DELETE", path: "/v1/feeds/" + feed + "/members/" + carl.id, token: ben.token}); r.StatusCode != 403 {
		t.Fatalf("admin removes admin: %d", r.StatusCode)
	}
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/members/" + carl.id, token: anna.token}, 204)
	if l := e.list("/v1/feeds/"+feed+"/members", ben.token); len(l) != 2 || l[0]["role"] != "owner" {
		t.Fatalf("members: %v", l)
	}
	// --- leave and transfer
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/leave", token: anna.token}); r.StatusCode != 409 || r.code() != "owner_cannot_leave" {
		t.Fatalf("owner leaves: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/transfer", token: anna.token, body: map[string]any{"user_id": dave.id}}); r.StatusCode != 422 {
		t.Fatalf("transfer to non-member: %d", r.StatusCode)
	}
	e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/transfer", token: anna.token, body: map[string]any{"user_id": ben.id}}, 204)
	if m := e.get("/v1/feeds/"+feed, anna.token).m(t); m["role"] != "admin" || m["owner_name"] != "Ben" {
		t.Fatalf("after transfer: %v", m)
	}
	if r := e.do(req{method: "DELETE", path: "/v1/feeds/" + feed, token: anna.token}); r.StatusCode != 403 {
		t.Fatalf("admin deletes feed: %d", r.StatusCode)
	}
	e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/leave", token: anna.token}, 204)
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed, token: anna.token}); r.StatusCode != 404 {
		t.Fatalf("after leaving: %d", r.StatusCode)
	}
	if l := e.list("/v1/feeds", anna.token); len(l) != 0 {
		t.Fatalf("anna's feeds: %v", l)
	}
	// An owner can't delete their account.
	if r := e.do(req{method: "DELETE", path: "/v1/me?remove_items=false", token: ben.token}); r.StatusCode != 409 {
		t.Fatalf("feed owner deletes account: %d %s", r.StatusCode, r.body)
	}

	// --- limits
	e.createFeed(dave, map[string]any{"name": "One"})
	e.createFeed(dave, map[string]any{"name": "Two"})
	if r := e.do(req{method: "POST", path: "/v1/feeds", token: dave.token, body: map[string]any{"name": "Three"}}); r.StatusCode != 409 || r.code() != "limit_reached" {
		t.Fatalf("feed limit: %d %s", r.StatusCode, r.body)
	}
}

func (e *env) list(path, token string) []map[string]any {
	e.t.Helper()
	var raw []map[string]any
	e.get(path, token).json(e.t, &raw)
	return raw
}

func upsertsByID(ch map[string]any) map[string]map[string]any { return placementUpserts(ch) }

func removals(ch map[string]any) map[string]bool {
	out := map[string]bool{}
	for _, c := range changesOf(ch, "placement.remove") {
		out[c["id"].(string)] = true
	}
	return out
}

// Subscriptions from both sides, posting (direct and via a feed at once),
// the frame's change feed, receipts and their visibility, reactions, rename,
// pause/resume, feed mode and unsubscribe, unsend (feeds.md §3-§7).
func TestFeedPostsAndSubscriptions(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	anna, ben, carla, dave := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carla@x.org", "Carla"), e.user("dave@x.org", "Dave")
	// members_see_reactions defaults to true since docs/spec/family-social.md; this test checks a feed where
	// other members see neither receipts nor frames' reactions.
	feed := e.createFeed(anna, map[string]any{"name": "Peeters kids", "members_see_reactions": false})
	e.joinFeed(anna, ben, feed)
	e.joinFeed(anna, dave, feed)
	dO, oma := e.claimSized(ben, 1280, 800)
	dK, kitchen := e.claimSized(carla, 1024, 600)
	e.heartbeat(dK, "feed_backfill")
	e.heartbeat(dO, "feed_backfill")

	// --- Ben (poster, manages Oma's frame) asks: active at once, no approval (feeds.md §4.2).
	annaWS := e.dialWS("/v1/ws?access_token="+anna.token, "")
	benWS := e.dialWS("/v1/ws?access_token="+ben.token, "")
	sub := e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/subscriptions", token: ben.token,
		body: map[string]any{"frame_id": oma, "history": "none"}}, 201).m(t)
	if sub["status"] != "active" || sub["activated_at"] == nil || sub["can_manage"] != true || sub["can_decide"] != false ||
		dig(sub, "feed", "member_count") != float64(3) {
		t.Fatalf("request: %v", sub)
	}
	subO := sub["id"].(string)
	annaWS.awaitWhere("subscription.updated", 5*time.Second, func(m map[string]any) bool {
		return dig(m, "subscription", "id") == subO && dig(m, "subscription", "status") == "active" && dig(m, "subscription", "requested_by_name") == "Ben"
	})
	if r := e.do(req{method: "POST", path: "/v1/subscriptions/" + subO + "/approve", token: ben.token}); r.StatusCode != 403 {
		t.Fatalf("poster approves: %d", r.StatusCode)
	}
	// Dave (another poster) sees the active subscription, without who asked.
	if l := e.list("/v1/feeds/"+feed+"/subscriptions", dave.token); len(l) != 1 || l[0]["status"] != "active" || l[0]["requested_by_name"] != nil {
		t.Fatalf("poster's view: %v", l)
	}
	e.expect(req{method: "POST", path: "/v1/subscriptions/" + subO + "/approve", token: anna.token}, 200) // already active: unchanged
	if r := e.do(req{method: "POST", path: "/v1/subscriptions/" + subO + "/reject", token: anna.token}); r.StatusCode != 409 || r.code() != "already_decided" {
		t.Fatalf("reject active: %d %s", r.StatusCode, r.body)
	}
	benWS.awaitWhere("subscription.updated", 5*time.Second, func(m map[string]any) bool { return dig(m, "subscription", "status") == "active" })
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/subscriptions", token: ben.token, body: map[string]any{"frame_id": oma}}); r.StatusCode != 409 || r.code() != "already_subscribed" {
		t.Fatalf("second subscription: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/subscriptions/" + subO + "/resume", token: ben.token}); r.StatusCode != 200 {
		t.Fatalf("resume active (unchanged): %d", r.StatusCode)
	}
	// Dave is no member of the kitchen frame; Ben is no member of the feed side's admin.
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/subscriptions", token: dave.token, body: map[string]any{"frame_id": kitchen}}); r.StatusCode != 404 {
		t.Fatalf("subscribe a foreign frame: %d", r.StatusCode)
	}

	// --- Carla (not in the feed) subscribes the kitchen frame with Anna's subscribe invite: active at once.
	inv := e.feedInvite(anna, feed, "subscribe", 201)
	pv := e.get("/v1/feed-invites/"+inv["token"].(string), carla.token).m(t)
	if pv["purpose"] != "subscribe" || len(pv["frames"].([]any)) != 1 || dig(pv["frames"].([]any)[0].(map[string]any), "frame_id") != kitchen {
		t.Fatalf("subscribe preview: %v", pv)
	}
	if r := e.do(req{method: "POST", path: "/v1/feed-invites/" + inv["token"].(string) + "/accept", token: carla.token}); r.StatusCode != 422 {
		t.Fatalf("subscribe invite without frames: %d", r.StatusCode)
	}
	acc := e.expect(req{method: "POST", path: "/v1/feed-invites/" + inv["token"].(string) + "/accept", token: carla.token,
		body: map[string]any{"frame_ids": []string{kitchen}}}, 200).m(t)
	subs := acc["subscriptions"].([]any)
	if acc["status"] != "subscribed" || len(subs) != 1 || dig(subs[0].(map[string]any), "status") != "active" || dig(subs[0].(map[string]any), "via_invite") != true {
		t.Fatalf("subscribe invite: %v", acc)
	}
	subK := subs[0].(map[string]any)["id"].(string)
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed, token: carla.token}); r.StatusCode != 404 {
		t.Fatalf("subscribing joined the feed: %d", r.StatusCode)
	}
	if l := e.list("/v1/frames/"+kitchen+"/subscriptions", carla.token); len(l) != 1 || dig(l[0], "feed", "name") != "Peeters kids" || l[0]["can_manage"] != true {
		t.Fatalf("frame subscriptions: %v", l)
	}
	if l := e.list("/v1/feeds/"+feed+"/subscriptions", dave.token); len(l) != 2 || l[0]["requested_by_name"] != nil {
		t.Fatalf("poster's view of subscriptions: %v", l)
	}

	// --- Ben posts to the feed and to Oma's frame directly in one send.
	k0 := e.changes(dK, 0)
	sendID := e.post(ben, []string{oma}, []string{feed}, e.photo(ben, 1))
	var omaPl, kitPl map[string]any
	eventually(t, 60*time.Second, "feed placements ready", func() bool {
		o, k := e.library(ben, oma, ""), e.library(carla, kitchen, "")
		if len(o) != 1 || len(k) != 1 || o[0]["status"] == "pending" || k[0]["status"] == "pending" {
			return false
		}
		omaPl, kitPl = o[0], k[0]
		return true
	})
	if omaPl["origin"] != "send" || len(omaPl["feed_ids"].([]any)) != 1 {
		t.Fatalf("direct+feed placement: %v", omaPl)
	}
	if kitPl["origin"] != "feed" || kitPl["origin_feed_id"] != feed || kitPl["origin_feed_name"] != "Peeters kids" || kitPl["backfill"] != false {
		t.Fatalf("feed placement: %v", kitPl)
	}
	if n := len(e.library(carla, kitchen, "&feed_id="+feed)); n != 1 {
		t.Fatalf("feed_id filter: %d", n)
	}
	kpid := kitPl["id"].(string)
	k1 := e.changes(dK, k0["next_seq"].(float64))
	if up := upsertsByID(k1)[kpid]; up == nil || up["origin"] != "feed" || up["origin_feed_name"] != "Peeters kids" || dig(up, "media", "url") == nil {
		t.Fatalf("kitchen change feed: %v", k1)
	}
	sd := e.get("/v1/sends/"+sendID, ben.token).m(t)
	if len(sd["frames"].([]any)) != 1 || len(sd["feeds"].([]any)) != 1 || dig(sd["feeds"].([]any)[0].(map[string]any), "summary", "frames") != float64(2) {
		t.Fatalf("send with feeds: %v", sd)
	}
	postID := dig(sd["feeds"].([]any)[0].(map[string]any), "post_id").(string)

	// --- receipts and their visibility
	e.expect(req{method: "POST", path: "/v1/device/acks", token: dK.token, body: map[string]any{"delivered": []string{kpid}}}, 204)
	benWS.awaitWhere("feed_post.updated", 5*time.Second, func(m map[string]any) bool { return dig(m, "summary", "delivered") == float64(1) })
	e.expect(req{method: "PUT", path: "/v1/device/placements/" + kpid + "/reaction", token: dK.token, body: map[string]any{"reaction": "love"}}, 204)
	re := benWS.await("reaction", 5*time.Second)
	if re["feed_id"] != feed || re["post_id"] != postID {
		t.Fatalf("reaction event: %v", re)
	}
	posts := e.get("/v1/feeds/"+feed+"/posts", ben.token).m(t)["posts"].([]any)
	p0 := posts[0].(map[string]any)
	if len(posts) != 1 || dig(p0, "receipts", "frames") != float64(2) || dig(p0, "receipts", "delivered") != float64(1) || p0["send_id"] != sendID ||
		len(p0["reactions"].([]any)) != 1 || dig(p0["reactions"].([]any)[0].(map[string]any), "frame_id") != kitchen {
		t.Fatalf("own post: %v", p0)
	}
	dp := e.get("/v1/feeds/"+feed+"/posts", dave.token).m(t)["posts"].([]any)[0].(map[string]any)
	if dp["receipts"] != nil || dp["reactions"] != nil || dp["send_id"] != nil || len(dp["items"].([]any)) != 1 {
		t.Fatalf("other poster sees receipts: %v", dp)
	}
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed + "/posts/" + postID + "/receipts", token: dave.token}); r.StatusCode != 403 {
		t.Fatalf("receipts for others: %d", r.StatusCode)
	}
	rc := e.get("/v1/feeds/"+feed+"/posts/"+postID+"/receipts", anna.token).m(t)
	if len(rc["frames"].([]any)) != 2 {
		t.Fatalf("admin receipts: %v", rc)
	}
	// Since docs/spec/family-social.md: members_see_reactions shows frames' reactions, members_see_receipts the receipts.
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed, token: anna.token, body: map[string]any{"members_see_reactions": true}}, 200)
	if dp := e.get("/v1/feeds/"+feed+"/posts/"+postID, dave.token).m(t); dp["receipts"] != nil || dp["reactions"] == nil {
		t.Fatalf("members_see_reactions: %v", dp)
	}
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed, token: anna.token, body: map[string]any{"members_see_receipts": true}}, 200)
	if dp := e.get("/v1/feeds/"+feed+"/posts/"+postID, dave.token).m(t); dp["receipts"] == nil {
		t.Fatalf("members_see_receipts: %v", dp)
	}
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed + "/posts/" + postID + "/receipts", token: dave.token}); r.StatusCode != 200 {
		t.Fatalf("receipts with members_see_receipts: %d", r.StatusCode)
	}
	// Feed members may see the post's items.
	itemID := dig(kitPl, "item", "id").(string)
	if r := e.do(req{method: "GET", path: "/v1/items/" + itemID + "/media/thumb_256", token: dave.token}); r.StatusCode != 302 {
		t.Fatalf("feed member thumbnail: %d", r.StatusCode)
	}

	// --- rename re-emits the credit
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed, token: anna.token, body: map[string]any{"name": "Peeters", "cover_item_id": itemID}}, 200)
	k2 := e.changes(dK, k1["next_seq"].(float64))
	if up := upsertsByID(k2)[kpid]; up == nil || up["origin_feed_name"] != "Peeters" {
		t.Fatalf("rename: %v", k2)
	}
	if r := e.do(req{method: "PATCH", path: "/v1/feeds/" + feed, token: anna.token, body: map[string]any{"cover_item_id": anna.id}}); r.StatusCode != 422 {
		t.Fatalf("foreign cover: %d", r.StatusCode)
	}

	// --- pause and resume (frame side)
	if r := e.do(req{method: "POST", path: "/v1/subscriptions/" + subK + "/pause", token: anna.token}); r.StatusCode != 403 {
		t.Fatalf("feed admin pauses: %d", r.StatusCode)
	}
	e.expect(req{method: "POST", path: "/v1/subscriptions/" + subK + "/pause", token: carla.token}, 200)
	k3 := e.changes(dK, k2["next_seq"].(float64))
	if !removals(k3)[kpid] {
		t.Fatalf("pause: %v", k3)
	}
	if n := len(e.library(carla, kitchen, "")); n != 0 {
		t.Fatalf("library while paused: %d", n)
	}
	if s := e.get("/v1/feeds/"+feed+"/posts/"+postID, ben.token).m(t); dig(s, "receipts", "paused") != float64(1) || dig(s, "receipts", "frames") != float64(1) {
		t.Fatalf("paused receipts: %v", s)
	}
	if r := e.do(req{method: "POST", path: "/v1/subscriptions/" + subK + "/approve", token: anna.token}); r.StatusCode != 200 {
		t.Fatalf("approve paused (unchanged): %d", r.StatusCode)
	}
	e.expect(req{method: "POST", path: "/v1/subscriptions/" + subK + "/resume", token: carla.token}, 200)
	k4 := e.changes(dK, k3["next_seq"].(float64))
	if up := upsertsByID(k4)[kpid]; up == nil {
		t.Fatalf("resume: %v", k4)
	}

	// --- feed mode and unsubscribe
	path := "/v1/frames/" + kitchen + "/settings"
	st := e.get(path, carla.token).m(t)
	ver := fmt.Sprintf(`"%v"`, st["version"])
	st = e.expect(req{method: "PATCH", path: path, token: carla.token, header: map[string]string{"If-Match": ver},
		body: map[string]any{"slideshow": map[string]any{"album_id": "x"}}}, 200).m(t)
	ver = fmt.Sprintf(`"%v"`, st["version"])
	st = e.expect(req{method: "PATCH", path: path, token: carla.token, header: map[string]string{"If-Match": ver},
		body: map[string]any{"slideshow": map[string]any{"feed_id": feed}}}, 200).m(t)
	if dig(st, "slideshow", "feed_id") != feed || dig(st, "slideshow", "album_id") != nil {
		t.Fatalf("feed mode clears album mode: %v", st)
	}
	annaWS2 := e.dialWS("/v1/ws?access_token="+anna.token, "")
	e.expect(req{method: "DELETE", path: "/v1/subscriptions/" + subK, token: carla.token}, 204)
	annaWS2.awaitWhere("subscription.updated", 5*time.Second, func(m map[string]any) bool { return dig(m, "subscription", "removed_reason") == "unsubscribed" })
	if st := e.get(path, carla.token).m(t); dig(st, "slideshow", "feed_id") != nil {
		t.Fatalf("feed_id after unsubscribe: %v", st)
	}
	eventually(t, 10*time.Second, "kitchen placement removed", func() bool { return len(e.library(carla, kitchen, "")) == 0 })
	if l := e.list("/v1/frames/"+kitchen+"/subscriptions?status=removed", carla.token); len(l) != 1 {
		t.Fatalf("removed subscription listed: %v", l)
	}

	// --- unsend from the feed: Oma's placement stays (held directly)
	if r := e.do(req{method: "DELETE", path: "/v1/feeds/" + feed + "/posts/" + postID, token: dave.token}); r.StatusCode != 403 {
		t.Fatalf("other poster removes post: %d", r.StatusCode)
	}
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/posts/" + postID, token: ben.token}, 204)
	if l := e.get("/v1/feeds/"+feed+"/posts", ben.token).m(t)["posts"].([]any); len(l) != 0 {
		t.Fatalf("posts after unsend: %v", l)
	}
	time.Sleep(300 * time.Millisecond)
	if o := e.library(ben, oma, ""); len(o) != 1 || len(o[0]["feed_ids"].([]any)) != 0 {
		t.Fatalf("direct placement after unsend: %v", o)
	}
	if sd := e.get("/v1/sends/"+sendID, ben.token).m(t); dig(sd["feeds"].([]any)[0].(map[string]any), "removed") != true {
		t.Fatalf("send feeds after unsend: %v", sd)
	}

	// --- validation of recipients
	up := e.photo(ben, 2)
	if r := e.do(req{method: "POST", path: "/v1/sends", token: carla.token, body: map[string]any{"frame_ids": []string{}, "feed_ids": []string{feed},
		"items": []map[string]any{{"upload_id": up}}}}); r.StatusCode != 404 || r.code() != "not_found" {
		t.Fatalf("post to a feed you're not in: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/sends", token: ben.token, body: map[string]any{"frame_ids": []string{}, "feed_ids": []string{},
		"items": []map[string]any{{"upload_id": up}}}}); r.StatusCode != 422 || r.code() != "validation_failed" {
		t.Fatalf("no recipients: %d %s", r.StatusCode, r.body)
	}
	// Forward an own send to the feed (frame_ids may be empty).
	fw := e.expect(req{method: "POST", path: "/v1/sends/" + sendID + "/forward", token: ben.token, body: map[string]any{"frame_ids": []string{}, "feed_ids": []string{feed}}}, 202).m(t)
	if len(fw["feeds"].([]any)) != 2 {
		t.Fatalf("forward to feed: %v", fw)
	}
	// Removing Oma's frame ends its subscription.
	e.expect(req{method: "DELETE", path: "/v1/frames/" + oma, token: ben.token}, 204)
	removed := 0
	for _, l := range e.list("/v1/feeds/"+feed+"/subscriptions?status=removed", anna.token) {
		if l["removed_reason"] == "frame_removed" && dig(l, "frame", "id") == oma {
			removed++
		}
	}
	if removed != 1 {
		t.Fatal("feed admins don't see the removed frame")
	}
	s, _ := e.get("/v1/feeds/"+feed, anna.token).m(t)["subscription_count"].(float64)
	if s != 0 {
		t.Fatalf("subscriptions after frame removal: %v", s)
	}
}

// Backfilled placements are withheld from frames whose heartbeat doesn't list
// feed_backfill, and emitted once it does; new posts reach every frame.
// Member removal with posts and feed deletion clear the feed off the frames.
func TestFeedBackfillAndRemovals(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters kids"})
	e.joinFeed(anna, ben, feed)
	s1 := e.post(anna, []string{}, []string{feed}, e.photo(anna, 1), e.photo(anna, 2))
	eventually(t, 60*time.Second, "items processed", func() bool {
		for _, it := range e.get("/v1/sends/"+s1, anna.token).m(t)["items"].([]any) {
			if it.(map[string]any)["status"] != "ready" {
				return false
			}
		}
		return true
	})
	dOld, oldFrame := e.claimSized(anna, 1280, 800) // never reports features
	dNew, newFrame := e.claimSized(anna, 1280, 800)
	e.heartbeat(dNew, "feed_backfill")
	for _, f := range []string{oldFrame, newFrame} {
		e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/subscriptions", token: anna.token,
			body: map[string]any{"frame_id": f, "history": "all"}}, 201)
	}
	ready := func(frame string, n int) []map[string]any {
		var lib []map[string]any
		eventually(t, 60*time.Second, "backfill on "+frame, func() bool {
			lib = e.library(anna, frame, "")
			if len(lib) != n {
				return false
			}
			for _, p := range lib {
				if p["status"] == "pending" {
					return false
				}
			}
			return true
		})
		return lib
	}
	lib := ready(oldFrame, 2)
	ready(newFrame, 2)
	for _, p := range lib {
		if p["backfill"] != true {
			t.Fatalf("history placement: %v", p)
		}
	}
	if up := upsertsByID(e.changes(dNew, 0)); len(up) != 2 {
		t.Fatalf("new frame gets the backfill: %v", up)
	}
	old0 := e.changes(dOld, 0)
	if up := upsertsByID(old0); len(up) != 0 {
		t.Fatalf("old frame got backfill: %v", up)
	}
	// New posts are not withheld.
	e.post(ben, []string{}, []string{feed}, e.photo(ben, 3))
	ready(oldFrame, 3)
	old1 := e.changes(dOld, old0["next_seq"].(float64))
	var benPl string
	for id, p := range upsertsByID(old1) {
		if p["backfill"] == false {
			benPl = id
		}
	}
	if len(upsertsByID(old1)) != 1 || benPl == "" {
		t.Fatalf("new post on the old frame: %v", old1)
	}
	// Only history placements count below: Ben's new placement may show up again when a later update of it (media
	// or freshness bookkeeping) lands after old1 was read.
	backfillOnly := func(up map[string]map[string]any) map[string]map[string]any {
		out := map[string]map[string]any{}
		for id, p := range up {
			if p["backfill"] == true {
				out[id] = p
			}
		}
		return out
	}
	e.heartbeat(dOld) // still no feature
	if up := backfillOnly(upsertsByID(e.changes(dOld, old1["next_seq"].(float64)))); len(up) != 0 {
		t.Fatalf("released without the feature: %v", up)
	}
	e.heartbeat(dOld, "feed_backfill")
	old2 := e.changes(dOld, old1["next_seq"].(float64))
	if up := backfillOnly(upsertsByID(old2)); len(up) != 2 {
		t.Fatalf("released backfill: %v", old2)
	}
	for id, p := range upsertsByID(old2) {
		if p["backfill"] != true && id != benPl {
			t.Fatalf("released placement: %v", p)
		}
	}

	// Ben is removed with his posts: his photo leaves both frames.
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/members/" + ben.id + "?remove_posts=true", token: anna.token}, 204)
	eventually(t, 10*time.Second, "ben's post removed", func() bool {
		return len(e.library(anna, oldFrame, "")) == 2 && len(e.library(anna, newFrame, "")) == 2
	})
	if !removals(e.changes(dOld, old2["next_seq"].(float64)))[benPl] {
		t.Fatal("no placement.remove for ben's photo")
	}

	// A purged history photo is not placed again by a history change.
	purged := lib[0]["id"].(string)
	e.expect(req{method: "DELETE", path: "/v1/frames/" + oldFrame + "/placements/" + purged, token: anna.token}, 204)
	subs := e.list("/v1/frames/"+oldFrame+"/subscriptions", anna.token)
	sid := subs[0]["id"].(string)
	e.expect(req{method: "PATCH", path: "/v1/subscriptions/" + sid, token: anna.token, body: map[string]any{"history": "last_n", "history_count": 5}}, 200)
	if r := e.do(req{method: "PATCH", path: "/v1/subscriptions/" + sid, token: anna.token, body: map[string]any{"history": "last_n"}}); r.StatusCode != 422 {
		t.Fatalf("last_n without count: %d", r.StatusCode)
	}
	eventually(t, 10*time.Second, "reconciled", func() bool {
		s := e.get("/v1/subscriptions/"+sid, anna.token).m(t)
		return dig(s, "backfill", "state") == "idle" && s["history"] == "last_n"
	})
	if n := len(e.library(anna, oldFrame, "")); n != 1 {
		t.Fatalf("purged photo came back: %d", n)
	}

	// Deleting the feed removes its placements from every frame.
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed, token: anna.token}, 204)
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed, token: anna.token}); r.StatusCode != 404 {
		t.Fatalf("deleted feed: %d", r.StatusCode)
	}
	eventually(t, 10*time.Second, "feed placements removed", func() bool {
		return len(e.library(anna, oldFrame, "")) == 0 && len(e.library(anna, newFrame, "")) == 0
	})
	eventually(t, 10*time.Second, "feed row deleted", func() bool { return e.rowCount("feeds") == 0 })
	// The items stay in the poster's sent history.
	if sd := e.get("/v1/sends/"+s1, anna.token).m(t); len(sd["items"].([]any)) != 2 {
		t.Fatalf("sent history after feed delete: %v", sd)
	}
}

// Any feed member who manages the frame (owner, admin or change_settings)
// gets an active subscription at once, with the history option the simple
// apps send (last_n 50); everyone else is refused (feeds.md §4.1, §4.2).
func TestFeedSubscriptionByFrameManager(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	anna, ben, carla, dave, eve := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carla@x.org", "Carla"),
		e.user("dave@x.org", "Dave"), e.user("eve@x.org", "Eve")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters kids"})
	for _, u := range []*user{ben, carla, dave} {
		e.joinFeed(anna, u, feed)
	}
	s1 := e.post(anna, []string{}, []string{feed}, e.photo(anna, 1), e.photo(anna, 2), e.photo(anna, 3))
	eventually(t, 60*time.Second, "items processed", func() bool {
		for _, it := range e.get("/v1/sends/"+s1, anna.token).m(t)["items"].([]any) {
			if it.(map[string]any)["status"] != "ready" {
				return false
			}
		}
		return true
	})
	subscribe := func(u *user, frame string, status int) map[string]any {
		t.Helper()
		r := e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/subscriptions", token: u.token,
			body: map[string]any{"frame_id": frame, "history": "last_n", "history_count": 50}}, status)
		if status != 201 {
			return map[string]any{"code": r.code()}
		}
		return r.m(t)
	}
	backfilled := func(u *user, frame string, n int) {
		t.Helper()
		eventually(t, 60*time.Second, "backfill on "+frame, func() bool {
			lib := e.library(u, frame, "")
			if len(lib) != n {
				return false
			}
			for _, p := range lib {
				if p["status"] == "pending" || p["backfill"] != true || p["origin"] != "feed" {
					return false
				}
			}
			return true
		})
	}

	// Ben, a poster, owns his frame.
	dB, benFrame := e.claimSized(ben, 1280, 800)
	e.heartbeat(dB, "feed_backfill")
	s := subscribe(ben, benFrame, 201)
	if s["status"] != "active" || s["activated_at"] == nil || s["history"] != "last_n" || s["history_count"] != float64(50) || s["can_decide"] != false {
		t.Fatalf("poster owning the frame: %v", s)
	}
	backfilled(ben, benFrame, 3)

	// Carla, a poster, is admin of Eve's frame; Dave, a poster, has only change_settings on it.
	dE, eveFrame := e.claimSized(eve, 1280, 800)
	e.heartbeat(dE, "feed_backfill")
	e.join(carla, dE)
	e.join(dave, dE)
	e.expect(req{method: "PATCH", path: "/v1/frames/" + eveFrame + "/members/" + carla.id, token: eve.token, body: map[string]any{"role": "admin"}}, 200)
	if r := subscribe(dave, eveFrame, 403); r["code"] != "missing_permission" {
		t.Fatalf("poster without change_settings: %v", r)
	}
	if s := subscribe(carla, eveFrame, 201); s["status"] != "active" {
		t.Fatalf("poster who is frame admin: %v", s)
	} else {
		e.expect(req{method: "DELETE", path: "/v1/subscriptions/" + s["id"].(string), token: carla.token}, 204)
	}
	e.setPerms(eve, eveFrame, dave, map[string]any{"change_settings": true})
	if s := subscribe(dave, eveFrame, 201); s["status"] != "active" {
		t.Fatalf("poster with change_settings: %v", s)
	}
	backfilled(eve, eveFrame, 3)

	// Eve manages her frame but is no member of the feed: not found, as for any outsider.
	_, eveOther := e.claim(eve)
	if r := subscribe(eve, eveOther, 404); r["code"] != "not_found" {
		t.Fatalf("outsider: %v", r)
	}
	// Nothing waited for approval.
	for _, l := range e.list("/v1/feeds/"+feed+"/subscriptions?status=pending", anna.token) {
		t.Fatalf("pending subscription: %v", l)
	}
}

// max_subscriptions_per_feed holds for requests and subscribe invites; frame
// members without change_settings can't subscribe the frame.
func TestFeedSubscriptionLimits(t *testing.T) {
	e := newEnv(t, func(c *config.Config) { c.DefaultLimits.MaxSubscriptionsPerFeed = 1 })
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters kids"})
	e.joinFeed(anna, ben, feed)
	dA, a := e.claim(anna)
	_, b := e.claim(anna)
	e.join(ben, dA)
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/subscriptions", token: ben.token, body: map[string]any{"frame_id": a}}); r.StatusCode != 403 || r.code() != "missing_permission" {
		t.Fatalf("member without change_settings: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/subscriptions", token: anna.token, body: map[string]any{"frame_id": a, "history": "last_n"}}); r.StatusCode != 422 {
		t.Fatalf("last_n without count: %d", r.StatusCode)
	}
	s := e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/subscriptions", token: anna.token, body: map[string]any{"frame_id": a, "history": "last_n", "history_count": 50}}, 201).m(t)
	if s["status"] != "active" || s["history_count"] != float64(50) {
		t.Fatalf("admin request: %v", s)
	}
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/subscriptions", token: anna.token, body: map[string]any{"frame_id": b}}); r.StatusCode != 409 || r.code() != "limit_reached" {
		t.Fatalf("limit: %d %s", r.StatusCode, r.body)
	}
	inv := e.feedInvite(anna, feed, "subscribe", 201)
	if r := e.do(req{method: "POST", path: "/v1/feed-invites/" + inv["token"].(string) + "/accept", token: anna.token, body: map[string]any{"frame_ids": []string{b}}}); r.StatusCode != 409 || r.code() != "limit_reached" {
		t.Fatalf("limit via invite: %d %s", r.StatusCode, r.body)
	}
	// An existing subscription is returned as it is.
	r := e.expect(req{method: "POST", path: "/v1/feed-invites/" + inv["token"].(string) + "/accept", token: anna.token, body: map[string]any{"frame_ids": []string{a}}}, 200).m(t)
	if subs := r["subscriptions"].([]any); len(subs) != 1 || dig(subs[0].(map[string]any), "id") != s["id"] {
		t.Fatalf("existing via invite: %v", r)
	}
	// The frame side unsubscribes; an ended subscription can't be paused.
	e.expect(req{method: "DELETE", path: "/v1/subscriptions/" + s["id"].(string), token: anna.token}, 204)
	if g := e.get("/v1/subscriptions/"+s["id"].(string), anna.token).m(t); g["status"] != "removed" || g["removed_reason"] != "unsubscribed" {
		t.Fatalf("after unsubscribe: %v", g)
	}
	if r := e.do(req{method: "POST", path: "/v1/subscriptions/" + s["id"].(string) + "/pause", token: anna.token}); r.StatusCode != 409 || r.code() != "invalid_transition" {
		t.Fatalf("pause removed: %d %s", r.StatusCode, r.body)
	}
}
