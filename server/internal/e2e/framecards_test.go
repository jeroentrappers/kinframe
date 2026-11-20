package e2e

import (
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

// Frame cards (README §5.5, "Frame cards") and FeedSummary.my_post_count: what the simple apps' Frames and
// Family tabs need (docs/spec/simple-app.md §3, §3.3).

// framePlacement puts a photo of u on the frame with this status, received at `at`, and returns the placement id.
func (e *env) framePlacement(u *user, frameID, status string, at time.Time) string {
	e.t.Helper()
	item, id := e.sqlItem(u), uuid.Must(uuid.NewV7())
	e.sql(`UPDATE items SET thumb512_key = $2, display_key = $3 WHERE id = $1`, item, "thumbs/"+item.String(), "display/"+item.String())
	e.sql(`INSERT INTO placements (id, frame_id, item_id, status, received_at) VALUES ($1, $2, $3, $4, $5)`, id, frameID, item, status, at)
	return id.String()
}

// frameCard is the caller's FrameSummary of the frame in GET /v1/frames.
func (e *env) frameCard(u *user, frameID string) map[string]any {
	e.t.Helper()
	for _, f := range e.list("/v1/frames", u.token) {
		if f["id"] == frameID {
			return f
		}
	}
	e.t.Fatalf("frame %s not in the list", frameID)
	return nil
}

func TestFrameCards(t *testing.T) {
	e := newEnv(t)
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	d, frame := e.claim(anna)
	e.join(ben, d) // default permissions: send only, no see_library

	// Nothing on the frame yet: no photo, no reactions.
	c := e.frameCard(anna, frame)
	if c["now_showing"] != nil || dig(c, "reactions_today", "count") != float64(0) || dig(c, "reactions_today", "item_count") != float64(0) {
		t.Fatalf("empty frame card: %v", c)
	}

	// The newest visible placement on the frame: not the one still on its way (ready) nor a hidden one.
	now := time.Now().UTC()
	p1 := e.framePlacement(anna, frame, "delivered", now.Add(-3*time.Hour))
	p2 := e.framePlacement(anna, frame, "seen", now.Add(-2*time.Hour))
	e.framePlacement(anna, frame, "ready", now.Add(-time.Hour))
	p4 := e.framePlacement(anna, frame, "delivered", now.Add(-30*time.Minute))
	e.sql(`UPDATE placements SET hidden = true WHERE id = $1`, p4)
	c = e.frameCard(anna, frame)
	ns, _ := c["now_showing"].(map[string]any)
	if ns == nil || ns["placement_id"] != p2 || ns["source"] != "last_placed" || ns["item_id"] == nil {
		t.Fatalf("now showing: %v", c["now_showing"])
	}
	if since, _ := time.Parse(time.RFC3339Nano, ns["since"].(string)); since.Sub(now.Add(-2*time.Hour)).Abs() > time.Second {
		t.Fatalf("since %v", ns["since"])
	}
	for _, k := range []string{"thumb_url", "display_url"} {
		if u, _ := ns[k].(string); !strings.HasPrefix(u, e.url+"/v1/media/signed/") {
			t.Fatalf("%s %q", k, ns[k])
		}
	}

	// Reactions and quick replies from the frame today: a changed reaction counts once, a removed one not at all.
	react := func(pid string, r any) {
		e.expect(req{method: "PUT", path: "/v1/device/placements/" + pid + "/reaction", token: d.token, body: map[string]any{"reaction": r}}, 204)
	}
	react(p1, "love")
	react(p1, "wow")
	e.expect(req{method: "POST", path: "/v1/device/placements/" + p1 + "/replies", token: d.token, body: map[string]any{"preset": "call_me"}}, 204)
	react(p2, "love")
	today := func(u *user, count, items float64) {
		t.Helper()
		for _, c := range []map[string]any{e.frameCard(u, frame), e.get("/v1/frames/"+frame, u.token).m(t)} {
			if dig(c, "reactions_today", "count") != count || dig(c, "reactions_today", "item_count") != items {
				t.Fatalf("reactions today, want %v on %v items: %v", count, items, c["reactions_today"])
			}
		}
	}
	today(anna, 3, 2)
	react(p2, nil)
	today(anna, 2, 1)
	react(p2, "love")
	today(anna, 3, 2)
	if f := e.get("/v1/frames/"+frame, anna.token).m(t); dig(f, "now_showing", "placement_id") != p2 {
		t.Fatalf("getFrame now showing: %v", f["now_showing"])
	}

	// A member who can't see the library gets neither; with see_library they do.
	for _, c := range []map[string]any{e.frameCard(ben, frame), e.get("/v1/frames/"+frame, ben.token).m(t)} {
		if _, has := c["now_showing"]; has {
			t.Fatalf("member without see_library: %v", c)
		}
		if _, has := c["reactions_today"]; has {
			t.Fatalf("member without see_library: %v", c)
		}
	}
	e.setPerms(anna, frame, ben, map[string]any{"see_library": true})
	today(ben, 3, 2)

	// "Today" is the frame's local day. At 23:00 in New York (03:00 UTC the next day) a reaction from 01:00 New York
	// time counts, one from the evening before doesn't; in UTC neither does.
	e.setClock(time.Date(2026, 3, 10, 3, 0, 0, 0, time.UTC))
	defer e.app.API.SetClock(nil)
	e.sql(`UPDATE placements SET reaction_at = '2026-03-09T05:00:00Z' WHERE id = $1`, p1)
	e.sql(`UPDATE placements SET reaction_at = '2026-03-09T03:00:00Z' WHERE id = $1`, p2)
	e.sql(`UPDATE placement_replies SET at = '2026-03-09T03:30:00Z'`)
	e.sql(`UPDATE frames SET settings = jsonb_set(settings, '{locale,timezone}', '"America/New_York"') WHERE id = $1`, frame)
	today(anna, 1, 1)
	e.sql(`UPDATE frames SET settings = jsonb_set(settings, '{locale,timezone}', '"UTC"') WHERE id = $1`, frame)
	today(anna, 0, 0)
	e.app.API.SetClock(nil)

	// While the frame is connected, the slide it reports on screen; a slide it may no longer show (hidden) or a
	// disconnect falls back to the newest placement.
	ws := e.dialWS("/v1/device/ws", d.token)
	ws.await("hello", 5*time.Second)
	ws.send(nowShowing([]string{p1}, p2, "on", 5*time.Second))
	eventually(t, 5*time.Second, "now showing from the screen", func() bool {
		return dig(e.frameCard(anna, frame), "now_showing", "source") == "screen"
	})
	ns = e.frameCard(anna, frame)["now_showing"].(map[string]any)
	if ns["placement_id"] != p1 {
		t.Fatalf("screen now showing: %v", ns)
	}
	if since, _ := time.Parse(time.RFC3339Nano, ns["since"].(string)); time.Since(since) < 4*time.Second || time.Since(since) > time.Minute {
		t.Fatalf("screen since %v", ns["since"])
	}
	ws.send(nowShowing([]string{p4}, "", "on", 0))
	eventually(t, 5*time.Second, "hidden slide ignored", func() bool {
		ns := e.frameCard(anna, frame)["now_showing"].(map[string]any)
		return ns["source"] == "last_placed" && ns["placement_id"] == p2
	})
	ws.send(nowShowing([]string{p1}, "", "on", 0))
	eventually(t, 5*time.Second, "screen again", func() bool {
		return dig(e.frameCard(anna, frame), "now_showing", "source") == "screen"
	})
	_ = ws.c.CloseNow()
	eventually(t, 5*time.Second, "offline falls back", func() bool {
		return dig(e.frameCard(anna, frame), "now_showing", "source") == "last_placed"
	})
}

// FeedSummary.my_post_count: the caller's live posts per family, in the list and on the family.
func TestFeedMyPostCount(t *testing.T) {
	e := newEnv(t)
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	trappers := e.createFeed(anna, map[string]any{"name": "Trappers"})
	oma := e.createFeed(anna, map[string]any{"name": "Opa & Oma"})
	e.joinFeed(anna, ben, trappers)
	now := time.Now().UTC()
	e.sqlPost(anna, trappers, now.Add(-time.Hour), 2)
	removed := e.sqlPost(anna, trappers, now.Add(-30*time.Minute), 1)
	e.sqlPost(anna, trappers, now, 1)
	e.sqlPost(ben, trappers, now, 1)
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + trappers + "/posts/" + removed, token: anna.token}, 204)

	counts := func(u *user) map[string]any {
		out := map[string]any{}
		for _, f := range e.list("/v1/feeds", u.token) {
			out[f["id"].(string)] = f["my_post_count"]
		}
		return out
	}
	if c := counts(anna); c[trappers] != float64(2) || c[oma] != float64(0) {
		t.Fatalf("anna's post counts: %v", c)
	}
	if c := counts(ben); c[trappers] != float64(1) || len(c) != 1 {
		t.Fatalf("ben's post counts: %v", c)
	}
	if f := e.get("/v1/feeds/"+trappers, anna.token).m(t); f["my_post_count"] != float64(2) || f["post_count"] != float64(3) {
		t.Fatalf("getFeed: %v", f)
	}
}
