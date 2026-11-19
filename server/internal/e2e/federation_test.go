package e2e

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
)

// Families on different servers (docs/spec/multi-server.md, README §5.22): two kinframe servers in one test
// process, each with its own database and base URL. A has the family "Janssens", B has Oma's frame.

func federated(label string) option {
	return func(c *config.Config) {
		c.Federation = config.FederationConfig{Enabled: true, Label: label, AllowLoopback: true,
			PingDelay: 200 * time.Millisecond, PollInterval: time.Hour, Tick: 100 * time.Millisecond}
	}
}

// remotePlacements lists the copies on a frame of B (visible placements with origin_remote).
func (e *env) remotePlacements(u *user, frameID string) []map[string]any {
	e.t.Helper()
	var out []map[string]any
	for _, p := range e.library(u, frameID, "") {
		if p["origin_remote"] != nil {
			out = append(out, p)
		}
	}
	return out
}

func captions(ps []map[string]any) []string {
	var out []string
	for _, p := range ps {
		c, _ := dig(p, "item", "caption").(string)
		out = append(out, c)
	}
	return out
}

func (e *env) sendCaptioned(u *user, feed, caption string, seed int, extra map[string]any) string {
	e.t.Helper()
	body := map[string]any{"frame_ids": []string{}, "feed_ids": []string{feed},
		"items": []map[string]any{{"upload_id": e.photo(u, seed), "caption": caption}}}
	for k, v := range extra {
		body[k] = v
	}
	sd := e.expect(req{method: "POST", path: "/v1/sends", token: u.token, body: body}, 202).m(e.t)
	return e.feedPostOf(u, sd["id"].(string), feed)
}

func TestFederationTwoServers(t *testing.T) {
	requireMedia(t)
	a := newEnv(t, federated("Janssens"))
	b := newEnv(t, federated("Appmire"))
	hostA, hostB := strings.TrimPrefix(a.url, "http://"), strings.TrimPrefix(b.url, "http://")

	// --- identity: discovery and the well-known document
	da, db := a.get("/.well-known/kinframe", "").m(t), b.get("/.well-known/kinframe", "").m(t)
	if da["server_id"] == nil || da["server_id"] == db["server_id"] || da["server_name"] != "Janssens" || da["base_url"] != a.url ||
		da["api_version"] != "v1" || dig(da, "federation", "enabled") != true || dig(da, "federation", "key_id") == nil ||
		dig(da, "federation", "endpoints", "ping") != a.url+"/federation/v1/ping" || da["remote_families"] != true {
		t.Fatalf("discovery A: %v", da)
	}
	if again := a.get("/.well-known/kinframe", "").m(t); again["server_id"] != da["server_id"] {
		t.Fatalf("server id not stable: %v vs %v", again["server_id"], da["server_id"])
	}

	// --- signatures are required
	if r := a.do(req{method: "POST", path: "/federation/v1/subscriptions", body: map[string]any{"invite_token": strings.Repeat("x", 24),
		"frame": map[string]any{"id": "01900000-0000-7000-8000-000000000001", "name": "x"}}}); r.StatusCode != 401 || r.code() != "unauthorized" {
		t.Fatalf("unsigned subscribe: %d %s", r.StatusCode, r.body)
	}
	if r := a.do(req{method: "POST", path: "/federation/v1/ping", body: map[string]any{"subscription_ids": []string{}},
		header: map[string]string{"Kinframe-Server": b.url, "Signature-Input": `sig1=("@method");created=1;keyid="k";alg="ed25519"`, "Signature": "sig1=:AAAA:",
			"Content-Digest": "sha-256=:AAAA:", "Date": time.Now().UTC().Format(http.TimeFormat)}}); r.StatusCode != 401 {
		t.Fatalf("forged ping: %d %s", r.StatusCode, r.body)
	}

	// --- A: the family, a history post, a circle and its post
	anna := a.user("anna@janssens.be", "Anna Janssens")
	lies := a.user("lies@janssens.be", "Lies Janssens")
	feed := a.createFeed(anna, map[string]any{"name": "Janssens", "allow_remote_frames": true})
	if f := a.get("/v1/feeds/"+feed, anna.token).m(t); f["allow_remote_frames"] != true {
		t.Fatalf("allow_remote_frames: %v", f)
	}
	a.joinFeed(anna, lies, feed)
	old := a.sendCaptioned(lies, feed, "before", 1, nil)
	circle := a.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/circles", token: anna.token,
		body: map[string]any{"name": "Grown-ups", "member_ids": []string{lies.id}}}, 201).m(t)["id"].(string)
	// Invites: allow_remote only on subscribe invites of families that allow it.
	if r := a.do(req{method: "POST", path: "/v1/feeds/" + feed + "/invites", token: anna.token, body: map[string]any{"purpose": "join", "allow_remote": true}}); r.StatusCode != 422 {
		t.Fatalf("join invite with allow_remote: %d %s", r.StatusCode, r.body)
	}
	plain := a.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/invites", token: anna.token, body: map[string]any{"purpose": "subscribe"}}, 201).m(t)
	inv := a.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/invites", token: anna.token,
		body: map[string]any{"purpose": "subscribe", "allow_remote": true}}, 201).m(t)
	if inv["allow_remote"] != true || plain["allow_remote"] != false {
		t.Fatalf("invites: %v / %v", inv, plain)
	}

	// --- B: Tom and Oma's frame
	tom := b.user("tom@appmire.be", "Tom")
	dev, frame := b.claim(tom)
	b.expect(req{method: "PATCH", path: "/v1/frames/" + frame, token: tom.token, body: map[string]any{"name": "Oma's frame"}}, 200)
	b.heartbeat(dev, "feed_backfill", "birthdays")

	if r := b.do(req{method: "POST", path: "/v1/remote-invites:preview", token: tom.token, body: map[string]any{"invite_url": plain["url"]}}); r.StatusCode != 403 {
		t.Fatalf("preview of an invite without allow_remote: %d %s", r.StatusCode, r.body)
	}
	if r := b.do(req{method: "POST", path: "/v1/remote-invites:preview", token: tom.token, body: map[string]any{"invite_url": b.url + "/fi/" + strings.Repeat("a", 24)}}); r.StatusCode != 422 {
		t.Fatalf("preview of an invite of this server: %d %s", r.StatusCode, r.body)
	}
	pv := b.expect(req{method: "POST", path: "/v1/remote-invites:preview", token: tom.token, body: map[string]any{"invite_url": inv["url"]}}, 200).m(t)
	if pv["feed_name"] != "Janssens" || pv["requires_approval"] != true || dig(pv, "server", "server_name") != "Janssens" ||
		dig(pv, "server", "server_url") != a.url || pv["this_server_name"] != "Appmire" || pv["this_server_host"] != hostB {
		t.Fatalf("preview: %v", pv)
	}

	// --- subscribe from B: pending until Anna approves on A
	annaWS := a.dialWS("/v1/ws?access_token="+anna.token, "")
	rsub := b.expect(req{method: "POST", path: "/v1/frames/" + frame + "/remote-subscriptions", token: tom.token,
		body: map[string]any{"invite_url": inv["url"], "history": "all"}}, 201).m(t)
	if rsub["status"] != "pending" || rsub["feed_name"] != "Janssens" || dig(rsub, "server", "host") != hostA {
		t.Fatalf("remote subscription: %v", rsub)
	}
	follow := rsub["id"].(string)
	if r := b.do(req{method: "POST", path: "/v1/frames/" + frame + "/remote-subscriptions", token: tom.token,
		body: map[string]any{"invite_url": inv["url"]}}); r.StatusCode != 409 || r.code() != "already_subscribed" {
		t.Fatalf("second follow: %d %s", r.StatusCode, r.body)
	}
	reqEv := annaWS.await("subscription.request", 5*time.Second)
	if dig(reqEv, "subscription", "remote", "server_name") != "Appmire" || dig(reqEv, "subscription", "frame", "name") != "Oma's frame" {
		t.Fatalf("request event: %v", reqEv)
	}
	subs := a.list("/v1/feeds/"+feed+"/subscriptions", anna.token)
	if len(subs) != 1 || subs[0]["status"] != "pending" || dig(subs[0], "remote", "server_url") != b.url || dig(subs[0], "remote", "requested_by_name") != "Tom" {
		t.Fatalf("A's subscriptions: %v", subs)
	}
	sid := subs[0]["id"].(string)
	if r := a.do(req{method: "POST", path: "/v1/subscriptions/" + sid + "/approve", token: lies.token}); r.StatusCode != 403 {
		t.Fatalf("poster approves: %d %s", r.StatusCode, r.body)
	}
	if ap := a.expect(req{method: "POST", path: "/v1/subscriptions/" + sid + "/approve", token: anna.token}, 200).m(t); ap["status"] != "active" {
		t.Fatalf("approve: %v", ap)
	}

	// --- backfill: the history post arrives on B (ping after the approval), credited "Lies · Janssens"
	eventually(t, 30*time.Second, "backfill on B", func() bool {
		ps := b.remotePlacements(tom, frame)
		return len(ps) == 1 && ps[0]["status"] != "pending"
	})
	cp := b.remotePlacements(tom, frame)[0]
	if dig(cp, "item", "caption") != "before" || dig(cp, "item", "sender_name") != "Lies Janssens" || dig(cp, "item", "remote_sender", "server_name") != "Janssens" ||
		cp["origin"] != "feed" || cp["origin_feed_id"] != nil || cp["origin_feed_name"] != "Janssens" || dig(cp, "origin_remote", "remote_subscription_id") != follow ||
		cp["backfill"] != true {
		t.Fatalf("copy: %v", cp)
	}
	if l := b.list("/v1/frames/"+frame+"/remote-subscriptions", tom.token); len(l) != 1 || l[0]["status"] != "active" {
		t.Fatalf("B's follows: %v", l)
	}
	// The frame sees a feed placement: origin_feed_name, no origin_remote.
	ch := b.changes(dev, 0)
	var seen map[string]any
	for _, p := range upsertsByID(ch) {
		if p["id"] == cp["id"] {
			seen = p
		}
	}
	if seen == nil || seen["origin_feed_name"] != "Janssens" || seen["origin_remote"] != nil || dig(seen, "item", "sender_name") != "Lies Janssens" {
		t.Fatalf("frame change feed: %v", ch)
	}

	// --- birthdays: the family's people reach the frame (the year only with show_age)
	a.addBirthday(anna, feed, map[string]any{"display_name": "Oma Lies", "day": 14, "month": 3, "birth_year": 1950}, 201)
	eventually(t, 20*time.Second, "birthdays on B's frame", func() bool {
		p, _, _ := b.lastBirthdays(dev, 0)
		ps := people(p)
		return len(ps) == 1 && ps[0]["name"] == "Oma Lies" && ps[0]["month"] == float64(3) && ps[0]["day"] == float64(14) && ps[0]["birth_year"] == nil
	})

	// --- a new post appears on B through the ping; a circle post never does
	a.sendCaptioned(lies, feed, "secret", 3, map[string]any{"circle_ids": []string{circle}})
	fresh := a.sendCaptioned(lies, feed, "fresh", 2, nil)
	eventually(t, 30*time.Second, "new post on B", func() bool {
		return len(b.remotePlacements(tom, frame)) == 2
	})
	time.Sleep(time.Second) // the circle post had every chance to arrive
	for _, c := range captions(b.remotePlacements(tom, frame)) {
		if c == "secret" {
			t.Fatal("a circle post reached the other server")
		}
	}
	var freshPlacement string
	for _, p := range b.remotePlacements(tom, frame) {
		if dig(p, "item", "caption") == "fresh" {
			freshPlacement = p["id"].(string)
			if p["backfill"] != false {
				t.Fatalf("new post placed as history: %v", p)
			}
		}
	}

	// --- a frame reaction on B shows in A's thread as "Oma's frame (Appmire)" and in the reaction row
	eventually(t, 20*time.Second, "copy ready on the frame", func() bool {
		for _, p := range upsertsByID(b.changes(dev, 0)) {
			if p["id"] == freshPlacement {
				return true
			}
		}
		return false
	})
	b.expect(req{method: "PUT", path: "/v1/device/placements/" + freshPlacement + "/reaction", token: dev.token, body: map[string]any{"reaction": "love"}}, 204)
	b.expect(req{method: "POST", path: "/v1/device/placements/" + freshPlacement + "/replies", token: dev.token, body: map[string]any{"preset": "thanks"}}, 204)
	eventually(t, 20*time.Second, "frame entries on A", func() bool {
		n := 0
		for _, c := range a.comments(anna, fresh) {
			if c["kind"] == "frame" && dig(c, "frame", "server_name") == "Appmire" && dig(c, "frame", "frame_name") == "Oma's frame" &&
				dig(c, "frame", "frame_id") == sid {
				n++
			}
		}
		return n == 2
	})
	post := a.get("/v1/posts/"+fresh, anna.token).m(t)
	if !strings.Contains(string(mustJSON(t, post)), `"server_name":"Appmire"`) {
		t.Fatalf("reaction row without the remote frame: %v", post)
	}

	// --- a removal on A disappears on B
	a.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/posts/" + old, token: anna.token}, 204)
	eventually(t, 20*time.Second, "removal on B", func() bool {
		cs := captions(b.remotePlacements(tom, frame))
		return len(cs) == 1 && cs[0] == "fresh"
	})

	// --- unsubscribe from B: A shows it removed
	b.expect(req{method: "DELETE", path: "/v1/frames/" + frame + "/remote-subscriptions/" + follow, token: tom.token}, 204)
	if n := len(b.remotePlacements(tom, frame)); n != 0 {
		t.Fatalf("copies after unsubscribing (on_end remove): %d", n)
	}
	eventually(t, 20*time.Second, "unsubscribe on A", func() bool {
		s := a.list("/v1/feeds/"+feed+"/subscriptions", anna.token)
		return len(s) == 1 && s[0]["status"] == "removed" && s[0]["removed_reason"] == "unsubscribed"
	})

	// --- subscribe again; Anna removes it on A: B's follow ends (removed_by_feed), copies go
	a.expect(req{method: "PATCH", path: "/v1/feeds/" + feed, token: anna.token, body: map[string]any{"auto_approve_subscriptions": true}}, 200)
	again := b.expect(req{method: "POST", path: "/v1/frames/" + frame + "/remote-subscriptions", token: tom.token,
		body: map[string]any{"invite_url": inv["url"], "history": "all"}}, 201).m(t)
	if again["status"] != "active" {
		t.Fatalf("auto-approved follow: %v", again)
	}
	eventually(t, 30*time.Second, "copies of the second follow", func() bool { return len(b.remotePlacements(tom, frame)) == 1 })

	// --- comment summaries reach a frame that shows comments (first names only)
	set := b.get("/v1/frames/"+frame+"/settings", tom.token).m(t)
	b.expect(req{method: "PATCH", path: "/v1/frames/" + frame + "/settings", token: tom.token,
		header: map[string]string{"If-Match": `"` + jsonNum(set["version"]) + `"`}, body: map[string]any{"social": map[string]any{"show_comments": true}}}, 200)
	copyID := b.remotePlacements(tom, frame)[0]["id"].(string)
	b.expect(req{method: "PUT", path: "/v1/device/placements/" + copyID + "/reaction", token: dev.token, body: map[string]any{"reaction": "wow"}}, 204)
	eventually(t, 20*time.Second, "show_comments on A", func() bool {
		for _, s := range a.list("/v1/feeds/"+feed+"/subscriptions", anna.token) {
			if s["status"] == "active" && dig(s, "remote", "show_comments") == true {
				return true
			}
		}
		return false
	})
	a.comment(anna, fresh, map[string]any{"body": "So lovely!"}, 201)
	eventually(t, 20*time.Second, "comment card on B's frame", func() bool {
		for _, c := range changesOf(b.changes(dev, 0), "comments") {
			if c["id"] == copyID {
				list, _ := dig(c, "comments", "comments").([]any)
				return len(list) == 1 && list[0].(map[string]any)["author_name"] == "Anna" && list[0].(map[string]any)["body"] == "So lovely!"
			}
		}
		return false
	})
	var sid2 string
	for _, s := range a.list("/v1/feeds/"+feed+"/subscriptions", anna.token) {
		if s["status"] == "active" {
			sid2 = s["id"].(string)
		}
	}
	a.expect(req{method: "DELETE", path: "/v1/subscriptions/" + sid2, token: anna.token}, 204)
	eventually(t, 20*time.Second, "removal by the family on B", func() bool {
		f := b.get("/v1/frames/"+frame+"/remote-subscriptions", tom.token)
		var l []map[string]any
		f.json(t, &l)
		for _, x := range l {
			if x["id"] == again["id"] {
				return x["status"] == "removed" && x["removed_reason"] == "removed_by_feed" && len(b.remotePlacements(tom, frame)) == 0
			}
		}
		return false
	})

	// --- a blocked server is refused
	bl := a.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/blocked-servers", token: anna.token, body: map[string]any{"server_url": b.url, "reason": "test"}}, 201).m(t)
	if bl["host"] != hostB || bl["server_name"] != "Appmire" {
		t.Fatalf("block: %v", bl)
	}
	if r := b.do(req{method: "POST", path: "/v1/remote-invites:preview", token: tom.token, body: map[string]any{"invite_url": inv["url"]}}); r.StatusCode != 403 || r.code() != "forbidden" {
		t.Fatalf("preview from a blocked server: %d %s", r.StatusCode, r.body)
	}
	a.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/blocked-servers/" + bl["id"].(string), token: anna.token}, 204)
	b.expect(req{method: "POST", path: "/v1/remote-invites:preview", token: tom.token, body: map[string]any{"invite_url": inv["url"]}}, 200)

	// --- federation off: a third server refuses everything, and others refuse its invites
	c := newEnv(t, func(cf *config.Config) { cf.Federation = config.FederationConfig{Enabled: false, AllowLoopback: true} })
	dc := c.get("/.well-known/kinframe", "").m(t)
	if dig(dc, "federation", "enabled") != false || dc["remote_families"] != false || dc["server_id"] == nil {
		t.Fatalf("discovery with federation off: %v", dc)
	}
	carol := c.user("carol@c.org", "Carol")
	if r := c.do(req{method: "POST", path: "/v1/remote-invites:preview", token: carol.token, body: map[string]any{"invite_url": inv["url"]}}); r.StatusCode != 503 || r.code() != "feature_disabled" {
		t.Fatalf("preview with federation off: %d %s", r.StatusCode, r.body)
	}
	if r := c.do(req{method: "POST", path: "/federation/v1/ping", raw: []byte(`{"subscription_ids":[]}`)}); r.StatusCode != 403 {
		t.Fatalf("federation call to a server with federation off: %d %s", r.StatusCode, r.body)
	}
	if r := b.do(req{method: "POST", path: "/v1/remote-invites:preview", token: tom.token, body: map[string]any{"invite_url": c.url + "/fi/" + strings.Repeat("a", 24)}}); r.StatusCode != 403 {
		t.Fatalf("preview of an invite on a server with federation off: %d %s", r.StatusCode, r.body)
	}

	// --- operator lists
	if r := a.do(req{method: "GET", path: "/v1/operator/remote-servers", token: anna.token}); r.StatusCode != 403 {
		t.Fatalf("operator list without the scope: %d", r.StatusCode)
	}
}

func mustJSON(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
