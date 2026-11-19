package e2e

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

// Birthdays (docs/spec/birthdays.md, README §5.13) over real HTTP.

func (e *env) addBirthday(u *user, feed string, body map[string]any, status int) map[string]any {
	e.t.Helper()
	r := e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/birthdays", token: u.token, body: body}, status)
	if status >= 300 {
		return map[string]any{"code": r.code(), "detail": r.m(e.t)["detail"], "errors": r.m(e.t)["errors"]}
	}
	return r.m(e.t)
}

func (e *env) birthdays(u *user, feed string) map[string]map[string]any {
	e.t.Helper()
	out := map[string]map[string]any{}
	for _, b := range e.list("/v1/feeds/"+feed+"/birthdays", u.token) {
		out[b["display_name"].(string)] = b
	}
	return out
}

func (e *env) patchBirthday(u *user, feed, id string, body map[string]any, status int) *resp {
	e.t.Helper()
	return e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/birthdays/" + id, token: u.token, body: body}, status)
}

func errField(r *resp) string {
	var p struct {
		Errors []struct {
			Field string `json:"field"`
		} `json:"errors"`
	}
	_ = json.Unmarshal(r.body, &p)
	if len(p.Errors) == 0 {
		return ""
	}
	return p.Errors[0].Field
}

// lastBirthdays returns the last `birthdays` change after since (present
// reports whether there was one) and the new head.
func (e *env) lastBirthdays(d *device, since float64) (payload map[string]any, present bool, next float64) {
	e.t.Helper()
	ch := e.changes(d, since)
	for _, c := range ch["changes"].([]any) {
		m := c.(map[string]any)
		if m["type"] == "birthdays" {
			present = true
			payload, _ = m["birthdays"].(map[string]any)
		}
	}
	return payload, present, ch["next_seq"].(float64)
}

func people(p map[string]any) []map[string]any {
	var out []map[string]any
	if p == nil {
		return nil
	}
	for _, x := range p["people"].([]any) {
		out = append(out, x.(map[string]any))
	}
	return out
}

func (e *env) setClock(t time.Time) { e.app.API.SetClock(func() time.Time { return t }) }

// The permission matrix of birthdays.md §4.1, suggestions, year visibility and validation.
func TestBirthdayPermissions(t *testing.T) {
	e := newEnv(t, func(c *config.Config) { c.DefaultLimits.MaxBirthdaysPerFeed = 6 })
	anna, ben, carl, dave, eve := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carl@x.org", "Carl"),
		e.user("dave@x.org", "Dave"), e.user("eve@x.org", "Eve")
	disc := e.get("/.well-known/kinframe", "").m(t)
	if disc["birthdays"] != true || dig(disc, "limits", "max_birthdays_per_feed") != float64(6) {
		t.Fatalf("discovery: %v", disc)
	}
	feed := e.createFeed(anna, map[string]any{"name": "Peeters kids"})
	for _, u := range []*user{ben, carl, dave} {
		e.joinFeed(anna, u, feed)
	}
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/members/" + dave.id, token: anna.token, body: map[string]any{"role": "admin"}}, 200)

	// Owner adds Oma: approved at once; the year is hidden from posters while show_age is off.
	oma := e.addBirthday(anna, feed, map[string]any{"display_name": "Oma", "day": 12, "month": 3, "birth_year": 1946}, 201)
	if oma["status"] != "approved" || oma["birth_year"] != float64(1946) || oma["suggested"] != false || oma["can_edit"] != true ||
		oma["created_by_id"] != anna.id || oma["created_by_name"] != "Anna" || oma["show_age"] != false {
		t.Fatalf("oma: %v", oma)
	}
	omaID := oma["id"].(string)
	if b := e.birthdays(ben, feed)["Oma"]; b == nil || b["birth_year"] != nil || b["turns"] != nil || b["can_edit"] != false {
		t.Fatalf("poster view: %v", b)
	}
	if b := e.birthdays(dave, feed)["Oma"]; b["birth_year"] != float64(1946) || b["turns"] == nil {
		t.Fatalf("admin view: %v", b)
	}
	// Outsiders see nothing.
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed + "/birthdays", token: eve.token}); r.StatusCode != 404 {
		t.Fatalf("outsider: %d", r.StatusCode)
	}
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed + "/birthdays/" + omaID, token: eve.token}); r.StatusCode != 404 {
		t.Fatalf("outsider get: %d", r.StatusCode)
	}
	// show_age on: everyone sees the year.
	e.patchBirthday(anna, feed, omaID, map[string]any{"show_age": true}, 200)
	if b := e.birthdays(ben, feed)["Oma"]; b["birth_year"] != float64(1946) {
		t.Fatalf("show_age on: %v", b)
	}
	e.patchBirthday(anna, feed, omaID, map[string]any{"show_age": false}, 200)
	// A poster may not edit or remove what they don't own.
	if r := e.patchBirthday(ben, feed, omaID, map[string]any{"display_name": "Omi"}, 403); r.code() != "forbidden" {
		t.Fatal(r.code())
	}
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/birthdays/" + omaID, token: ben.token}, 403)
	if r := e.addBirthday(ben, feed, map[string]any{"display_name": "oma ", "day": 12, "month": 3}, 409); r["code"] != "duplicate_birthday" || r["detail"] != omaID {
		t.Fatalf("duplicate: %v", r)
	}

	// --- A poster's suggestion.
	annaWS := e.dialWS("/v1/ws?access_token="+anna.token, "")
	benWS := e.dialWS("/v1/ws?access_token="+ben.token, "")
	lotte := e.addBirthday(ben, feed, map[string]any{"display_name": "Lote", "day": 2, "month": 6, "birth_year": 2025}, 201)
	lotteID := lotte["id"].(string)
	if lotte["status"] != "pending" || lotte["suggested"] != true || lotte["birth_year"] != float64(2025) || lotte["can_edit"] != true || lotte["can_decide"] != false {
		t.Fatalf("suggestion: %v", lotte)
	}
	if ev := annaWS.await("birthday.suggested", 5*time.Second); dig(ev, "birthday", "id") != lotteID || dig(ev, "birthday", "can_decide") != true {
		t.Fatalf("suggested event: %v", ev)
	}
	if _, seen := e.birthdays(carl, feed)["Lote"]; seen {
		t.Fatal("other posters see suggestions")
	}
	if r := e.do(req{method: "GET", path: "/v1/feeds/" + feed + "/birthdays/" + lotteID, token: carl.token}); r.StatusCode != 404 {
		t.Fatalf("other poster get suggestion: %d", r.StatusCode)
	}
	if f := e.get("/v1/feeds/"+feed, anna.token).m(t); f["pending_birthday_count"] != float64(1) || f["birthday_count"] != float64(1) {
		t.Fatalf("admin feed counts: %v", f)
	}
	if f := e.get("/v1/feeds/"+feed, ben.token).m(t); f["pending_birthday_count"] != float64(0) || f["birthday_notifications"] != true {
		t.Fatalf("poster feed counts: %v", f)
	}
	// Second identical suggestion while the first is pending.
	if r := e.addBirthday(carl, feed, map[string]any{"display_name": "LOTE", "day": 2, "month": 6}, 409); r["detail"] != lotteID {
		t.Fatalf("duplicate of a pending one: %v", r)
	}
	// The suggester edits their suggestion; the admin fixes the spelling, then approves.
	e.patchBirthday(ben, feed, lotteID, map[string]any{"show_age": true}, 200)
	e.patchBirthday(ben, feed, lotteID, map[string]any{"show_age": false}, 200)
	e.patchBirthday(carl, feed, lotteID, map[string]any{"show_age": false}, 404)
	e.patchBirthday(dave, feed, lotteID, map[string]any{"display_name": "Lotte"}, 200)
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/birthdays/" + lotteID + "/approve", token: ben.token}); r.StatusCode != 403 {
		t.Fatalf("poster approves: %d", r.StatusCode)
	}
	ap := e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/birthdays/" + lotteID + "/approve", token: anna.token}, 200).m(t)
	if ap["status"] != "approved" || ap["display_name"] != "Lotte" || ap["decided_by_name"] != "Anna" {
		t.Fatalf("approved: %v", ap)
	}
	if ev := benWS.await("birthday.decided", 5*time.Second); dig(ev, "birthday", "status") != "approved" {
		t.Fatalf("decided event: %v", ev)
	}
	e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/birthdays/" + lotteID + "/approve", token: anna.token}, 200)
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/birthdays/" + lotteID + "/reject", token: anna.token}); r.StatusCode != 409 || r.code() != "already_decided" {
		t.Fatalf("reject approved: %d %s", r.StatusCode, r.body)
	}
	// Now approved, the suggester (no posters_can_add_birthdays) can no longer edit it.
	e.patchBirthday(ben, feed, lotteID, map[string]any{"display_name": "Lottie"}, 403)
	// Ben added it: he still sees its year although show_age is off; Carl does not.
	if e.birthdays(ben, feed)["Lotte"]["birth_year"] != float64(2025) || e.birthdays(carl, feed)["Lotte"]["birth_year"] != nil {
		t.Fatal("added-by-me year visibility")
	}

	// Rejection: visible to the suggester and admins; a new suggestion is allowed at once.
	cousin := e.addBirthday(carl, feed, map[string]any{"display_name": "Cousin Tom", "day": 1, "month": 1}, 201)["id"].(string)
	rj := e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/birthdays/" + cousin + "/reject", token: dave.token}, 200).m(t)
	if rj["status"] != "rejected" {
		t.Fatalf("reject: %v", rj)
	}
	if r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/birthdays/" + cousin + "/approve", token: anna.token}); r.StatusCode != 409 || r.code() != "already_decided" {
		t.Fatalf("approve rejected: %d", r.StatusCode)
	}
	if b := e.birthdays(carl, feed)["Cousin Tom"]; b == nil || b["status"] != "rejected" {
		t.Fatalf("suggester sees the rejection: %v", b)
	}
	if l := e.list("/v1/feeds/"+feed+"/birthdays?status=rejected", ben.token); len(l) != 0 {
		t.Fatalf("others see rejections: %v", l)
	}
	cousin2 := e.addBirthday(carl, feed, map[string]any{"display_name": "Cousin Tom", "day": 1, "month": 1}, 201)["id"].(string)
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/birthdays/" + cousin2, token: carl.token}, 204) // withdraw

	// --- Own entry ("Add my birthday"): the linked member controls the privacy fields.
	own := e.addBirthday(ben, feed, map[string]any{"display_name": "Ben", "day": 5, "month": 9, "birth_year": 1990, "user_id": ben.id}, 201)
	ownID := own["id"].(string)
	if own["user_id"] != ben.id || own["linked_member_name"] != "Ben" {
		t.Fatalf("own: %v", own)
	}
	if r := e.addBirthday(carl, feed, map[string]any{"display_name": "Benny", "day": 6, "month": 9, "user_id": ben.id}, 422); r["code"] != "validation_failed" {
		t.Fatalf("second link: %v", r)
	}
	if r := e.addBirthday(anna, feed, map[string]any{"display_name": "Eve", "day": 6, "month": 9, "user_id": eve.id}, 422); r["code"] != "validation_failed" {
		t.Fatalf("link to non-member: %v", r)
	}
	e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/birthdays/" + ownID + "/approve", token: anna.token}, 200)
	if b := e.birthdays(carl, feed)["Ben"]; b["birth_year"] != nil {
		t.Fatal("year leaked to another poster")
	}
	e.patchBirthday(ben, feed, ownID, map[string]any{"show_age": true}, 200)
	e.patchBirthday(ben, feed, ownID, map[string]any{"birth_year": nil}, 200)
	if b := e.get("/v1/feeds/"+feed+"/birthdays/"+ownID, anna.token).m(t); b["birth_year"] != nil || b["show_age"] != true {
		t.Fatalf("own edits: %v", b)
	}
	if r := e.patchBirthday(ben, feed, ownID, map[string]any{"day": 6}, 403); r.code() != "forbidden" {
		t.Fatal(r.code())
	}
	// Fields sent unchanged are not edits.
	e.patchBirthday(ben, feed, ownID, map[string]any{"display_name": "Ben", "day": 5, "month": 9, "show_age": false}, 200)
	e.patchBirthday(ben, feed, ownID, map[string]any{"user_id": carl.id}, 403)
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/birthdays/" + ownID, token: ben.token}, 204)

	// --- posters_can_add_birthdays: only owner/admins change it; posters then add, edit and remove their own.
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed, token: ben.token, body: map[string]any{"posters_can_add_birthdays": true}}, 403)
	if f := e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed, token: dave.token, body: map[string]any{"posters_can_add_birthdays": true}}, 200).m(t); f["posters_can_add_birthdays"] != true {
		t.Fatalf("setting: %v", f)
	}
	opa := e.addBirthday(carl, feed, map[string]any{"display_name": "Opa", "day": 3, "month": 4}, 201)
	if opa["status"] != "approved" || opa["suggested"] != false {
		t.Fatalf("direct add: %v", opa)
	}
	e.patchBirthday(carl, feed, opa["id"].(string), map[string]any{"display_name": "Opa Jan", "user_id": dave.id}, 200)
	e.patchBirthday(ben, feed, opa["id"].(string), map[string]any{"display_name": "x"}, 403)
	e.patchBirthday(carl, feed, omaID, map[string]any{"display_name": "x"}, 403)
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/birthdays/" + opa["id"].(string), token: carl.token}, 204)

	// --- Validation.
	for _, c := range []struct {
		body  map[string]any
		field string
	}{
		{map[string]any{"display_name": "A", "day": 30, "month": 2}, "day"},
		{map[string]any{"display_name": "A", "day": 31, "month": 4}, "day"},
		{map[string]any{"display_name": "A", "day": 29, "month": 2, "birth_year": 1999}, "day"},
		{map[string]any{"display_name": "A", "day": 1, "month": 1, "birth_year": 1899}, "birth_year"},
		{map[string]any{"display_name": "A", "day": 1, "month": 1, "birth_year": time.Now().Year() + 1}, "birth_year"},
		{map[string]any{"display_name": "  ", "day": 1, "month": 1}, "display_name"},
	} {
		r := e.do(req{method: "POST", path: "/v1/feeds/" + feed + "/birthdays", token: anna.token, body: c.body})
		if r.StatusCode != 422 || errField(r) != c.field {
			t.Errorf("%v: %d %s", c.body, r.StatusCode, r.body)
		}
	}
	e.addBirthday(anna, feed, map[string]any{"display_name": "Leap", "day": 29, "month": 2, "birth_year": 2000}, 201)

	// --- Limit: pending and approved count, rejected ones do not (6: Oma, Lotte, rejected Tom, Leap, + 2).
	e.addBirthday(anna, feed, map[string]any{"display_name": "Five", "day": 5, "month": 5}, 201)
	e.addBirthday(ben, feed, map[string]any{"display_name": "Six", "day": 6, "month": 6}, 201)
	e.addBirthday(anna, feed, map[string]any{"display_name": "Seven", "day": 7, "month": 7}, 201)
	if r := e.addBirthday(anna, feed, map[string]any{"display_name": "Eight", "day": 8, "month": 8}, 409); r["code"] != "limit_reached" {
		t.Fatalf("limit: %v", r)
	}

	// --- Preferences and the user's zone.
	if f := e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/preferences", token: ben.token, body: map[string]any{"birthday_notifications": false}}, 200).m(t); f["birthday_notifications"] != false {
		t.Fatalf("preferences: %v", f)
	}
	if f := e.get("/v1/feeds/"+feed, anna.token).m(t); f["birthday_notifications"] != true {
		t.Fatal("preference is per member")
	}
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/preferences", token: eve.token, body: map[string]any{"birthday_notifications": false}}, 404)
	if r := e.do(req{method: "PATCH", path: "/v1/me", token: ben.token, body: map[string]any{"timezone": "Mars/Olympus"}}); r.StatusCode != 422 || errField(r) != "timezone" {
		t.Fatalf("bad zone: %d %s", r.StatusCode, r.body)
	}
	if u := e.expect(req{method: "PATCH", path: "/v1/me", token: ben.token, body: map[string]any{"timezone": "America/Toronto"}}, 200).m(t); u["timezone"] != "America/Toronto" {
		t.Fatalf("zone: %v", u)
	}
}

// The frame's `birthdays` change: emission paths, cross-feed merge, the year
// rule, subscriptions and greetings with their date window and limits.
func TestBirthdayFramesAndGreetings(t *testing.T) {
	e := newEnv(t, withUnifiedPush)
	anna, ben, carla := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carla@x.org", "Carla")
	pushes := e.pushSink(t, ben)
	dK, kitchen := e.claim(carla) // Carla's frame: she is no feed member
	sideA := e.createFeed(anna, map[string]any{"name": "Peeters"})
	sideB := e.createFeed(anna, map[string]any{"name": "Janssens"})
	e.joinFeed(anna, ben, sideA)
	e.joinFeed(anna, ben, sideB)
	e.joinFeed(anna, carla, sideA)
	e.joinFeed(anna, carla, sideB)
	e.setClock(time.Date(2027, 3, 12, 9, 0, 0, 0, time.UTC))

	omaA := e.addBirthday(anna, sideA, map[string]any{"display_name": "Oma", "day": 12, "month": 3, "birth_year": 1946, "show_age": true}, 201)["id"].(string)
	// Carla subscribes her frame to side A; side B is requested with birthdays off.
	subA := e.expect(req{method: "POST", path: "/v1/feeds/" + sideA + "/subscriptions", token: carla.token, body: map[string]any{"frame_id": kitchen}}, 201).m(t)
	if subA["status"] != "pending" || subA["birthdays"] != true || subA["birthday_count"] != float64(1) || dig(subA, "frame", "birthdays_supported") != false {
		t.Fatalf("subscription: %v", subA)
	}
	if p, present, _ := e.lastBirthdays(dK, 0); present || p != nil {
		t.Fatal("pending subscriptions contribute nothing")
	}
	e.expect(req{method: "POST", path: "/v1/subscriptions/" + subA["id"].(string) + "/approve", token: anna.token}, 200)
	p, present, head := e.lastBirthdays(dK, 0)
	ps := people(p)
	if !present || len(ps) != 1 || ps[0]["id"] != omaA || ps[0]["name"] != "Oma" || ps[0]["birth_year"] != float64(1946) || ps[0]["month"] != float64(3) {
		t.Fatalf("after approval: %v", p)
	}
	subB := e.expect(req{method: "POST", path: "/v1/feeds/" + sideB + "/subscriptions", token: anna.token,
		body: map[string]any{"frame_id": kitchen}}, 404) // Anna does not manage Carla's frame
	_ = subB
	sb := e.expect(req{method: "POST", path: "/v1/feeds/" + sideB + "/subscriptions", token: carla.token,
		body: map[string]any{"frame_id": kitchen, "birthdays": false}}, 201).m(t)
	e.expect(req{method: "POST", path: "/v1/subscriptions/" + sb["id"].(string) + "/approve", token: anna.token}, 200)
	if _, present, _ := e.lastBirthdays(dK, head); present {
		t.Fatal("a subscription with birthdays off changed the list")
	}

	// The other side of the family lists Oma too (no year, with show_age off): merged, year kept.
	omaB := e.addBirthday(anna, sideB, map[string]any{"display_name": "oma", "day": 12, "month": 3}, 201)["id"].(string)
	if _, present, _ := e.lastBirthdays(dK, head); present {
		t.Fatal("side B's people reached a frame with birthdays off")
	}
	e.expect(req{method: "PATCH", path: "/v1/subscriptions/" + sb["id"].(string), token: carla.token, body: map[string]any{"birthdays": true}}, 200)
	p, present, head = e.lastBirthdays(dK, head)
	ps = people(p)
	if !present || len(ps) != 1 || len(ps[0]["birthday_ids"].([]any)) != 2 || len(ps[0]["feed_ids"].([]any)) != 2 || ps[0]["id"] != omaA || ps[0]["birth_year"] != float64(1946) {
		t.Fatalf("merged: %v", p)
	}
	// A conflicting year shows no age.
	e.patchBirthday(anna, sideB, omaB, map[string]any{"birth_year": 1947, "show_age": true}, 200)
	p, _, head = e.lastBirthdays(dK, head)
	if ps = people(p); len(ps) != 1 || ps[0]["birth_year"] != nil {
		t.Fatalf("conflicting years: %v", p)
	}
	// Agreeing years show the age again; a year with show_age off hides it everywhere.
	e.patchBirthday(anna, sideB, omaB, map[string]any{"birth_year": 1946}, 200)
	p, _, head = e.lastBirthdays(dK, head)
	if ps = people(p); len(ps) != 1 || ps[0]["birth_year"] != float64(1946) {
		t.Fatalf("agreeing years: %v", p)
	}
	e.patchBirthday(anna, sideB, omaB, map[string]any{"show_age": false}, 200)
	p, _, head = e.lastBirthdays(dK, head)
	if ps = people(p); ps[0]["birth_year"] != nil {
		t.Fatalf("show_age off on one side: %v", p)
	}
	// An edit that changes nothing the frame shows emits nothing.
	e.patchBirthday(anna, sideB, omaB, map[string]any{"birth_year": 1945}, 200)
	if _, present, _ := e.lastBirthdays(dK, head); present {
		t.Fatal("a hidden year change was emitted")
	}
	// Pausing side A leaves side B's entry, now the primary.
	e.expect(req{method: "POST", path: "/v1/subscriptions/" + subA["id"].(string) + "/pause", token: carla.token}, 200)
	p, _, head = e.lastBirthdays(dK, head)
	if ps = people(p); len(ps) != 1 || ps[0]["id"] != omaB || ps[0]["name"] != "oma" || len(ps[0]["birthday_ids"].([]any)) != 1 {
		t.Fatalf("paused side A: %v", p)
	}
	e.expect(req{method: "POST", path: "/v1/subscriptions/" + subA["id"].(string) + "/resume", token: carla.token}, 200)
	p, _, head = e.lastBirthdays(dK, head)
	if ps = people(p); len(ps[0]["birthday_ids"].([]any)) != 2 {
		t.Fatalf("resumed: %v", p)
	}
	// Frame side: the count, and birthdays_supported once the frame app says so.
	e.heartbeat(dK, "feed_backfill", "birthdays")
	if s := e.get("/v1/subscriptions/"+subA["id"].(string), carla.token).m(t); dig(s, "frame", "birthdays_supported") != true || s["birthday_count"] != float64(1) {
		t.Fatalf("supported: %v", s)
	}

	// --- Greetings.
	annaWS := e.dialWS("/v1/ws?access_token="+anna.token, "")
	greet := func(d *device, id, date string, status int) *resp {
		t.Helper()
		return e.expect(req{method: "PUT", path: "/v1/device/birthdays/" + id + "/greetings/" + date, token: d.token}, status)
	}
	if r := greet(dK, omaA, "2027-03-11", 422); errField(r) != "date" {
		t.Fatalf("not the birthday: %s", r.body)
	}
	greet(dK, omaB, "2027-03-12", 204) // any merged id
	evs := map[string]bool{}
	for range 2 {
		ev := annaWS.await("birthday.greeting", 5*time.Second)
		evs[ev["feed_id"].(string)] = true
		if dig(ev, "greeting", "frame_name") == nil || dig(ev, "greeting", "date") != "2027-03-12" || dig(ev, "greeting", "birthday_name") == nil {
			t.Fatalf("greeting event: %v", ev)
		}
	}
	if !evs[sideA] || !evs[sideB] {
		t.Fatalf("one greeting per feed: %v", evs)
	}
	// Ben (no websocket, member of both feeds) gets one push.
	pm := pushes.next(t, "greeting")
	if pm.Key != "birthday_greeting" || pm.IDs["date"] != "2027-03-12" || pm.IDs["birthday_id"] == "" || !strings.Contains(pm.Body, "happy birthday") {
		t.Fatalf("greeting push: %+v", pm)
	}
	tag := pm.IDs["birthday_id"]
	if pm.Tag != "birthday:"+tag+":2027-03-12" {
		t.Fatalf("greeting push tag: %q", pm.Tag)
	}
	greet(dK, omaA, "2027-03-12", 204) // idempotent
	pushes.none(t, time.Second)
	gs := e.get("/v1/feeds/"+sideA+"/birthday-greetings", ben.token).m(t)["greetings"].([]any)
	if len(gs) != 1 || dig(gs[0].(map[string]any), "birthday_id") != omaA {
		t.Fatalf("greetings: %v", gs)
	}
	if b := e.get("/v1/feeds/"+sideA+"/birthdays/"+omaA, anna.token).m(t); b["greetings_today"] != float64(1) {
		t.Fatalf("greetings_today: %v", b)
	}
	// A second frame greets: the push counts both and keeps the tag.
	dO, opaFrame := e.claim(anna)
	e.expect(req{method: "POST", path: "/v1/feeds/" + sideA + "/subscriptions", token: anna.token, body: map[string]any{"frame_id": opaFrame}}, 201)
	greet(dO, omaA, "2027-03-12", 204)
	pm = pushes.next(t, "second greeting")
	if pm.IDs["birthday_id"] != tag || pm.Tag != "birthday:"+tag+":2027-03-12" || !strings.Contains(pm.Body, "one other frame") {
		t.Fatalf("second push: %+v", pm)
	}
	if n := len(e.get("/v1/feeds/"+sideA+"/birthday-greetings?birthday_id="+omaA+"&date=2027-03-12&limit=1", ben.token).m(t)["greetings"].([]any)); n != 1 {
		t.Fatalf("filtered page: %d", n)
	}
	page := e.get("/v1/feeds/"+sideA+"/birthday-greetings?limit=1", ben.token).m(t)
	if page["next_cursor"] == nil {
		t.Fatalf("paging: %v", page)
	}
	if e.get("/v1/feeds/"+sideA+"/birthday-greetings?cursor="+page["next_cursor"].(string), ben.token).m(t)["next_cursor"] != nil {
		t.Fatal("second page")
	}

	// Date window: server UTC date −2 … +1.
	lotte := e.addBirthday(anna, sideA, map[string]any{"display_name": "Lotte", "day": 10, "month": 3}, 201)["id"].(string)
	e.setClock(time.Date(2027, 3, 13, 1, 0, 0, 0, time.UTC))
	if r := greet(dO, lotte, "2027-03-10", 422); errField(r) != "date" {
		t.Fatalf("3 days late: %s", r.body)
	}
	e.setClock(time.Date(2027, 3, 11, 23, 0, 0, 0, time.UTC))
	greet(dO, lotte, "2027-03-10", 204) // 1 day late
	tim := e.addBirthday(anna, sideA, map[string]any{"display_name": "Tim", "day": 13, "month": 3}, 201)["id"].(string)
	greet(dO, tim, "2027-03-12", 422) // not his day
	greet(dO, tim, "2027-03-13", 422) // 2 days ahead
	e.setClock(time.Date(2027, 3, 12, 0, 30, 0, 0, time.UTC))
	greet(dO, tim, "2027-03-13", 204) // 1 day ahead (a zone east of UTC)

	// Leap day: 29 February counts on 28 February in non-leap years.
	e.setClock(time.Date(2027, 2, 28, 12, 0, 0, 0, time.UTC))
	leap := e.addBirthday(anna, sideA, map[string]any{"display_name": "Leap", "day": 29, "month": 2}, 201)["id"].(string)
	greet(dO, leap, "2027-03-01", 422)
	greet(dO, leap, "2027-02-28", 204)
	// The year of birth is not a birthday.
	baby := e.addBirthday(anna, sideA, map[string]any{"display_name": "Baby", "day": 28, "month": 2, "birth_year": 2027}, 201)["id"].(string)
	greet(dO, baby, "2027-02-28", 422)

	// Rate limit: 50 greetings a day per frame.
	if _, err := e.app.Store.Pool.Exec(context.Background(), `INSERT INTO birthday_greetings (id, birthday_id, feed_id, frame_id, local_date, frame_name, created_at)
		SELECT gen_random_uuid(), $1, $2, $3, DATE '2027-01-01' + n, 'x', TIMESTAMPTZ '2027-02-28 01:00:00+00' + n * interval '1 second'
		FROM generate_series(1, 50) n`, lotte, sideA, opaFrame); err != nil {
		t.Fatal(err)
	}
	ann := e.addBirthday(anna, sideA, map[string]any{"display_name": "Ann", "day": 28, "month": 2}, 201)["id"].(string)
	if r := greet(dO, ann, "2027-02-28", 429); r.code() != "rate_limited" {
		t.Fatal(r.code())
	}
	greet(dK, ann, "2027-02-28", 204) // another frame is not limited

	// --- Removal paths reach the frame.
	e.setClock(time.Date(2027, 3, 12, 9, 0, 0, 0, time.UTC))
	_, _, head = e.lastBirthdays(dK, head)
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + sideA + "/birthdays/" + omaA, token: anna.token}, 204)
	p, _, head = e.lastBirthdays(dK, head)
	for _, x := range people(p) {
		if x["name"] == "Oma" && len(x["birthday_ids"].([]any)) != 1 {
			t.Fatalf("after removing side A's Oma: %v", p)
		}
	}
	greet(dK, omaA, "2027-03-12", 404)
	// Unsubscribing side B and deleting side A's feed empties the list: birthdays null, omitted from snapshots.
	e.expect(req{method: "DELETE", path: "/v1/subscriptions/" + sb["id"].(string), token: carla.token}, 204)
	greet(dK, omaB, "2027-03-12", 404)
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + sideA, token: anna.token}, 204)
	p, present, _ = e.lastBirthdays(dK, head)
	if !present || p != nil {
		t.Fatalf("emptied list: present %v %v", present, p)
	}
	if _, present, _ := e.lastBirthdays(dK, 0); present {
		t.Fatal("an empty list is in the snapshot")
	}
}

// Photos (avatar pipeline), avatar fallback and a linked member leaving.
func TestBirthdayPhotos(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	dK, kitchen := e.claim(anna)
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/subscriptions", token: anna.token, body: map[string]any{"frame_id": kitchen}}, 201)
	jpeg := mediatest.JPEG(800, 600, 0, "2024:07:01 18:30:00", false)
	e.expect(req{method: "PUT", path: "/v1/me/avatar", token: ben.token, raw: jpeg, header: map[string]string{"Content-Type": "image/jpeg"}}, 200)
	entry := e.addBirthday(ben, feed, map[string]any{"display_name": "Ben", "day": 5, "month": 9, "user_id": ben.id}, 201)["id"].(string)
	e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/birthdays/" + entry + "/approve", token: anna.token}, 200)
	p, _, head := e.lastBirthdays(dK, 0)
	avatar, _ := people(p)[0]["photo_url"].(string)
	if !strings.Contains(avatar, "/avatars/"+ben.id+"/") {
		t.Fatalf("avatar fallback: %v", p)
	}
	// The linked member uploads a photo for their own entry.
	r := e.expect(req{method: "PUT", path: "/v1/feeds/" + feed + "/birthdays/" + entry + "/photo", token: ben.token, raw: jpeg,
		header: map[string]string{"Content-Type": "image/jpeg"}}, 200).m(t)
	photo, _ := r["photo_url"].(string)
	if r["has_own_photo"] != true || !strings.Contains(photo, "/avatars/b/") {
		t.Fatalf("photo: %v", r)
	}
	p, _, head = e.lastBirthdays(dK, head)
	if people(p)[0]["photo_url"] != photo {
		t.Fatalf("frame photo: %v", p)
	}
	if res, err := http.Get(photo); err != nil || res.StatusCode != 200 || res.Header.Get("Content-Type") != "image/webp" {
		t.Fatalf("public photo: %v %v", res, err)
	}
	e.expect(req{method: "PUT", path: "/v1/feeds/" + feed + "/birthdays/" + entry + "/photo", token: ben.token, raw: []byte("x"),
		header: map[string]string{"Content-Type": "text/plain"}}, 415)
	// Removing the photo falls back to the avatar; the old file is gone.
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/birthdays/" + entry + "/photo", token: ben.token}, 200)
	p, _, head = e.lastBirthdays(dK, head)
	if people(p)[0]["photo_url"] != avatar {
		t.Fatalf("back to avatar: %v", p)
	}
	if res, err := http.Get(photo); err != nil || res.StatusCode != 404 {
		t.Fatalf("old photo still served: %v", res.StatusCode)
	}
	// A new avatar re-emits the entry.
	e.expect(req{method: "PUT", path: "/v1/me/avatar", token: ben.token, raw: mediatest.JPEG(640, 640, 0, "2024:07:01 18:30:00", false),
		header: map[string]string{"Content-Type": "image/jpeg"}}, 200)
	p, present, head := e.lastBirthdays(dK, head)
	if !present || people(p)[0]["photo_url"] == avatar {
		t.Fatalf("avatar change: %v", p)
	}
	// Ben leaves: the entry stays, unlinked, without photo.
	e.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/leave", token: ben.token, body: map[string]any{}}, 204)
	p, present, _ = e.lastBirthdays(dK, head)
	if !present || len(people(p)) != 1 || people(p)[0]["photo_url"] != nil {
		t.Fatalf("after leaving: %v", p)
	}
	if b := e.get("/v1/feeds/"+feed+"/birthdays/"+entry, anna.token).m(t); b["user_id"] != nil {
		t.Fatalf("still linked: %v", b)
	}
}

// The morning notification: 08:00 in the member's zone, fallbacks, catch-up
// until 20:00, one per member per day merged across feeds, never their own entry.
func TestBirthdayMorning(t *testing.T) {
	e := newEnv(t, withUnifiedPush)
	ctx := context.Background()
	anna, ben, carl, dave, eve, fay := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carl@x.org", "Carl"),
		e.user("dave@x.org", "Dave"), e.user("eve@x.org", "Eve"), e.user("fay@x.org", "Fay")
	davePush := e.pushSink(t, dave)
	e.setClock(time.Date(2027, 3, 1, 12, 0, 0, 0, time.UTC))
	sideA := e.createFeed(anna, map[string]any{"name": "Peeters"})
	sideB := e.createFeed(dave, map[string]any{"name": "Janssens"}) // no frames: Dave's fallback is Brussels
	for _, u := range []*user{ben, carl, eve, fay} {
		e.joinFeed(anna, u, sideA)
	}
	e.joinFeed(dave, anna, sideB)
	e.joinFeed(dave, ben, sideB)
	// Side A's frame is in Europe/Amsterdam (from enrollment).
	_, frame := e.claim(anna)
	e.expect(req{method: "POST", path: "/v1/feeds/" + sideA + "/subscriptions", token: anna.token, body: map[string]any{"frame_id": frame}}, 201)
	e.expect(req{method: "PATCH", path: "/v1/me", token: carl.token, body: map[string]any{"timezone": "America/Toronto"}}, 200)
	e.expect(req{method: "PATCH", path: "/v1/me", token: eve.token, body: map[string]any{"timezone": "Asia/Tokyo"}}, 200)
	e.expect(req{method: "PATCH", path: "/v1/me", token: anna.token, body: map[string]any{"timezone": "Europe/Brussels"}}, 200)
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + sideA + "/preferences", token: fay.token, body: map[string]any{"birthday_notifications": false}}, 200)

	e.addBirthday(anna, sideA, map[string]any{"display_name": "Oma", "day": 12, "month": 3, "birth_year": 1946, "show_age": true}, 201)
	e.addBirthday(dave, sideB, map[string]any{"display_name": "Oma", "day": 12, "month": 3}, 201)
	// Anna's own entry on the same day: everybody else hears about it, she does not.
	e.addBirthday(anna, sideA, map[string]any{"display_name": "Anna", "day": 12, "month": 3, "user_id": anna.id}, 201)
	e.addBirthday(dave, sideB, map[string]any{"display_name": "Leap", "day": 29, "month": 2}, 201)

	annaWS := e.dialWS("/v1/ws?access_token="+anna.token, "")
	benWS := e.dialWS("/v1/ws?access_token="+ben.token, "")
	run := func(at string) int {
		t.Helper()
		now, err := time.Parse(time.RFC3339, at)
		if err != nil {
			t.Fatal(err)
		}
		n, err := e.app.API.BirthdayMornings(ctx, now)
		if err != nil {
			t.Fatal(err)
		}
		return n
	}
	// 06:30 UTC = 07:30 in Brussels/Amsterdam: nobody there yet; Eve (Tokyo, 15:30) catches up.
	if n := run("2027-03-12T06:30:00Z"); n != 1 {
		t.Fatalf("before 08:00 in Europe: %d", n)
	}
	// 07:05 UTC = 08:05 in Brussels: Anna (zone), Ben (no zone: his frames' Amsterdam), Dave (no zone, no frames:
	// Brussels). Fay has notifications off; Carl (Toronto, 02:05) is asleep.
	if n := run("2027-03-12T07:05:00Z"); n != 3 {
		t.Fatalf("08:05 Brussels: %d", n)
	}
	ev := benWS.await("birthday.today", 5*time.Second)
	if ev["date"] != "2027-03-12" || len(ev["birthdays"].([]any)) != 3 { // Oma (two feeds, merged) and Anna
		t.Fatalf("ben's notice: %v", ev)
	}
	ev = annaWS.await("birthday.today", 5*time.Second)
	if bs := ev["birthdays"].([]any); len(bs) != 2 { // Oma from both feeds, not her own entry
		t.Fatalf("anna's notice: %v", ev)
	}
	pm := davePush.next(t, "morning")
	// Dave is only in side B (Oma without a year): no age.
	if pm.Key != "birthday_today" || pm.IDs["date"] != "2027-03-12" || pm.Body != "Today is Oma's birthday 🎈" {
		t.Fatalf("dave's push: %+v", pm)
	}
	// Once per member and day.
	if n := run("2027-03-12T07:10:00Z"); n != 0 {
		t.Fatalf("again: %d", n)
	}
	// Carl in Toronto: 08:05 EST is 13:05 UTC.
	if n := run("2027-03-12T12:55:00Z"); n != 0 {
		t.Fatalf("07:55 Toronto: %d", n)
	}
	if n := run("2027-03-12T13:05:00Z"); n != 1 {
		t.Fatalf("08:05 Toronto: %d", n)
	}
	// Leap person on 28 February of a non-leap year; Eve's (Tokyo) catch-up ends at 20:00 local.
	e.joinFeed(dave, eve, sideB)
	// 11:30 UTC: Dave, Anna and Ben (side B) are at 12:30 in Europe; Eve in Tokyo at 20:30 is past the catch-up.
	if n := run("2027-02-28T11:30:00Z"); n != 3 {
		t.Fatalf("28 February: %d", n)
	}
	if n := run("2027-02-28T23:30:00Z"); n != 0 { // Tokyo 08:30 on 1 March: no birthday then
		t.Fatalf("1 March Tokyo: %d", n)
	}
	if n := run("2028-02-28T09:00:00Z"); n != 0 { // leap year: 29 February is the day
		t.Fatalf("28 February of a leap year: %d", n)
	}
	if n := run("2028-02-29T09:00:00Z"); n != 4 { // Eve (Tokyo 18:00) too
		t.Fatalf("29 February of a leap year: %d", n)
	}
	var eveFeb int
	_ = e.app.Store.Pool.QueryRow(ctx, `SELECT count(*) FROM birthday_notices WHERE user_id = $1 AND local_date = '2027-02-28'`, eve.id).Scan(&eveFeb)
	if eveFeb != 0 {
		t.Fatal("Eve was notified after 20:00")
	}
	if n := run("2028-02-29T09:30:00Z"); n != 0 {
		t.Fatalf("29 February of a leap year: %d", n)
	}
}

// ---------------------------------------------------------------- push sink

func withUnifiedPush(c *config.Config) { c.PushEnabled = true }

type pushSink struct {
	msgs chan pushMsg
}

// pushSink registers a UnifiedPush endpoint for u that collects pushes.
func (e *env) pushSink(t *testing.T, u *user) *pushSink {
	ps := &pushSink{msgs: make(chan pushMsg, 20)}
	svc := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		var m pushMsg
		_ = json.Unmarshal(b, &m)
		ps.msgs <- m
		w.WriteHeader(http.StatusCreated)
	}))
	t.Cleanup(svc.Close)
	e.expect(req{method: "POST", path: "/v1/me/push-endpoints", token: u.token,
		body: map[string]any{"kind": "unifiedpush", "endpoint": svc.URL, "locale": "en"}}, 200)
	return ps
}

func (p *pushSink) next(t *testing.T, what string) pushMsg {
	t.Helper()
	select {
	case m := <-p.msgs:
		return m
	case <-time.After(10 * time.Second):
		t.Fatalf("no push for %s", what)
	}
	return pushMsg{}
}

func (p *pushSink) none(t *testing.T, d time.Duration) {
	t.Helper()
	select {
	case m := <-p.msgs:
		t.Fatalf("unexpected push: %+v", m)
	case <-time.After(d):
	}
}
