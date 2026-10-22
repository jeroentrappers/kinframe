package e2e

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/calendar/calendartest"
	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

func withCalendar() option {
	return func(c *config.Config) { c.CalendarEnabled, c.CalendarSync = true, 15*time.Minute }
}

// calendarChanges returns the frame's `calendar` changes after since.
func (e *env) calendarChanges(d *device, since int) []map[string]any {
	e.t.Helper()
	var out []map[string]any
	for _, c := range e.get("/v1/device/changes?since="+strconv.Itoa(since), d.token).m(e.t)["changes"].([]any) {
		if cm := c.(map[string]any); cm["type"] == "calendar" {
			out = append(out, cm)
		}
	}
	return out
}

// latestCalendar waits for a calendar change after since that satisfies ok.
func (e *env) latestCalendar(d *device, since int, what string, ok func(cal map[string]any) bool) map[string]any {
	e.t.Helper()
	var cal map[string]any
	eventually(e.t, 10*time.Second, what, func() bool {
		cs := e.calendarChanges(d, since)
		if len(cs) == 0 {
			return false
		}
		c, _ := cs[len(cs)-1]["calendar"].(map[string]any)
		if c == nil || !ok(c) {
			return false
		}
		cal = c
		return true
	})
	return cal
}

func eventTitles(cal map[string]any) []string {
	var out []string
	for _, ev := range cal["events"].([]any) {
		out = append(out, ev.(map[string]any)["title"].(string))
	}
	return out
}

func findEvent(cal map[string]any, title string) map[string]any {
	for _, ev := range cal["events"].([]any) {
		if m := ev.(map[string]any); m["title"] == title {
			return m
		}
	}
	return nil
}

func icsTime(t time.Time) string { return t.UTC().Format("20060102T150405Z") }

// familyICS has a single event tomorrow, a weekly series and an all-day event,
// all relative to now so the test does not depend on the date.
func familyICS(now time.Time) string {
	tomorrow := now.Add(24 * time.Hour).Truncate(time.Hour)
	lastWeek := now.Add(-7 * 24 * time.Hour).Truncate(time.Hour)
	day := now.AddDate(0, 0, 3).Format("20060102")
	end := now.AddDate(0, 0, 5).Format("20060102")
	return strings.Join([]string{
		"BEGIN:VCALENDAR", "VERSION:2.0", "X-WR-CALNAME:Family",
		"BEGIN:VEVENT", "UID:dinner@family", "DTSTART:" + icsTime(tomorrow), "DTEND:" + icsTime(tomorrow.Add(2*time.Hour)),
		"SUMMARY:Dinner at Oma's", "LOCATION:Gent", "END:VEVENT",
		"BEGIN:VEVENT", "UID:swim@family", "DTSTART:" + icsTime(lastWeek), "DURATION:PT1H",
		"RRULE:FREQ=WEEKLY;COUNT=4", "SUMMARY:Swimming", "END:VEVENT",
		"BEGIN:VEVENT", "UID:trip@family", "DTSTART;VALUE=DATE:" + day, "DTEND;VALUE=DATE:" + end,
		"SUMMARY:Ardennes trip", "END:VEVENT",
		"END:VCALENDAR", "",
	}, "\r\n")
}

func davObject(uid, title string, start time.Time) string {
	return strings.Join([]string{
		"BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "UID:" + uid, "DTSTART:" + icsTime(start),
		"DTEND:" + icsTime(start.Add(time.Hour)), "SUMMARY:" + title, "END:VEVENT", "END:VCALENDAR", "",
	}, "\r\n")
}

func TestCalendar(t *testing.T) {
	fake := calendartest.New()
	t.Cleanup(fake.Close)
	now := time.Now()
	fake.SetICS("family.ics", familyICS(now))
	tomorrow := now.Add(24 * time.Hour).Truncate(time.Hour)
	// The same dinner (same UID and start) is also in the CalDAV calendar: shown once.
	fake.PutEvent("family", "dinner", davObject("dinner@family", "Dinner at Oma's (CalDAV copy)", tomorrow))
	fake.PutEvent("family", "dentist", davObject("dentist@nextcloud", "Dentist", now.Add(48*time.Hour).Truncate(time.Hour)))

	e := newEnv(t, withCalendar())
	if dig(e.get("/.well-known/kinframe", "").m(t), "calendar") != true {
		t.Fatal("discovery does not announce calendars")
	}
	owner := e.user("cal@x.org", "Carla")
	d, frameID := e.claim(owner)
	base := "/v1/frames/" + frameID
	frameWS := e.dialWS("/v1/device/ws", d.token)
	frameWS.await("hello", 5*time.Second)
	head := func() int { return int(e.get("/v1/device/changes?since=0", d.token).m(t)["next_seq"].(float64)) }

	// Settings carry the calendar defaults.
	st := e.get(base+"/settings", owner.token).m(t)
	if dig(st, "calendar", "view") != "week" || dig(st, "calendar", "past_days") != float64(30) ||
		dig(st, "widgets", "calendar_sidebar") != false || dig(st, "widgets", "countdowns") != true {
		t.Fatalf("calendar settings defaults: %v", st)
	}
	// No calendar yet: a snapshot has none.
	if cs := e.calendarChanges(d, 0); len(cs) != 0 {
		t.Fatalf("calendar before any source: %v", cs)
	}

	// 1. Add an ICS link: the server syncs it and the frame receives the calendar change.
	since := head()
	ics := e.expect(req{method: "POST", path: base + "/calendars", token: owner.token, body: map[string]any{
		"kind": "ics", "url": fake.ICSURL("family.ics"), "name": "Family",
	}}, 201).m(t)
	if ics["status"] != "syncing" || ics["color"] != "coral" || ics["enabled"] != true || ics["kind"] != "ics" ||
		ics["url_hint"] != fake.URL+"/…/family.ics" {
		t.Fatalf("created ics source: %v", ics)
	}
	cal := e.latestCalendar(d, since, "calendar change with the ICS events", func(c map[string]any) bool { return len(c["events"].([]any)) >= 3 })
	frameWS.await("changes", 5*time.Second)
	if titles := eventTitles(cal); !contains(titles, "Dinner at Oma's") || !contains(titles, "Ardennes trip") || !contains(titles, "Swimming") {
		t.Fatalf("events: %v", titles)
	}
	trip := findEvent(cal, "Ardennes trip")
	if trip["all_day"] != true || trip["start_date"] != now.AddDate(0, 0, 3).Format("2006-01-02") || trip["end_date"] != now.AddDate(0, 0, 4).Format("2006-01-02") || trip["start_at"] != nil {
		t.Fatalf("all-day event: %v", trip)
	}
	dinner := findEvent(cal, "Dinner at Oma's")
	if dinner["start_at"] != tomorrow.UTC().Format(time.RFC3339) || dinner["location"] != "Gent" || dinner["source_id"] != ics["id"] {
		t.Fatalf("timed event: %v", dinner)
	}
	if dig(cal, "sources") == nil || len(cal["sources"].([]any)) != 1 || dig(cal["sources"].([]any)[0].(map[string]any), "color") != "coral" ||
		cal["timezone"] == "" || dig(cal, "window", "from") == nil {
		t.Fatalf("calendar header: %v", cal)
	}

	// An unchanged re-sync is a conditional request (304) and emits nothing.
	since = head()
	e.expect(req{method: "POST", path: base + "/calendars/" + ics["id"].(string) + "/sync", token: owner.token}, 202)
	eventually(t, 5*time.Second, "304 on re-sync", func() bool { return fake.NotModified.Load() >= 1 })
	time.Sleep(200 * time.Millisecond)
	if cs := e.calendarChanges(d, since); len(cs) != 0 {
		t.Fatalf("unchanged sync emitted a change: %v", cs)
	}

	// 2. CalDAV: test the account, pick a calendar, add it. Events merge, the duplicate dinner shows once.
	test := e.expect(req{method: "POST", path: base + "/calendars:test", token: owner.token, body: map[string]any{
		"kind": "caldav", "url": fake.URL, "username": calendartest.User, "password": calendartest.Password,
	}}, 200).m(t)
	cals, _ := test["calendars"].([]any)
	if test["ok"] != true || test["status"] != "ok" || len(cals) != 2 || dig(cals[0].(map[string]any), "name") != "Family" {
		t.Fatalf("caldav test: %v", test)
	}
	bad := e.expect(req{method: "POST", path: base + "/calendars:test", token: owner.token, body: map[string]any{
		"kind": "caldav", "url": fake.URL + "/dav/", "username": calendartest.User, "password": "wrong",
	}}, 200).m(t)
	if bad["ok"] != false || bad["status"] != "auth_failed" || bad["error"] == nil {
		t.Fatalf("caldav test with a wrong password: %v", bad)
	}
	icsTest := e.expect(req{method: "POST", path: base + "/calendars:test", token: owner.token, body: map[string]any{
		"kind": "ics", "url": fake.ICSURL("family.ics"),
	}}, 200).m(t)
	if icsTest["ok"] != true || icsTest["calendar_name"] != "Family" || icsTest["event_count"] == nil {
		t.Fatalf("ics test: %v", icsTest)
	}
	since = head()
	dav := e.expect(req{method: "POST", path: base + "/calendars", token: owner.token, body: map[string]any{
		"kind": "caldav", "url": fake.URL, "username": calendartest.User, "password": calendartest.Password,
		"calendar_url": dig(cals[0].(map[string]any), "url"), "name": "Nextcloud",
	}}, 201).m(t)
	if dav["color"] != "tangerine" || dav["username"] != calendartest.User || dav["has_password"] != true {
		t.Fatalf("created caldav source: %v", dav)
	}
	cal = e.latestCalendar(d, since, "merged calendar", func(c map[string]any) bool { return findEvent(c, "Dentist") != nil })
	titles := eventTitles(cal)
	if contains(titles, "Dinner at Oma's (CalDAV copy)") || !contains(titles, "Dinner at Oma's") || len(cal["sources"].([]any)) != 2 {
		t.Fatalf("merge/dedup: %v", titles)
	}
	if findEvent(cal, "Dentist")["source_id"] != dav["id"] {
		t.Fatal("CalDAV event lost its source")
	}

	// Listing shows status, never the secrets.
	list := e.get(base+"/calendars", owner.token)
	if strings.Contains(string(list.body), calendartest.Password) || strings.Contains(string(list.body), "/ics/family.ics") {
		t.Fatalf("secrets leaked: %s", list.body)
	}
	var srcs []map[string]any
	list.json(t, &srcs)
	if len(srcs) != 2 || srcs[0]["status"] != "ok" || srcs[1]["status"] != "ok" || srcs[0]["last_synced_at"] == nil || srcs[0]["event_count"] == float64(0) {
		t.Fatalf("sources: %v", srcs)
	}

	// A wrong password ends in auth_failed with a reason; fixing it recovers.
	e.expect(req{method: "PATCH", path: base + "/calendars/" + dav["id"].(string), token: owner.token, body: map[string]any{"password": "wrong"}}, 200)
	eventually(t, 10*time.Second, "auth_failed", func() bool {
		var ss []map[string]any
		e.get(base+"/calendars", owner.token).json(t, &ss)
		return ss[1]["status"] == "auth_failed" && ss[1]["last_error"] != nil
	})
	e.expect(req{method: "PATCH", path: base + "/calendars/" + dav["id"].(string), token: owner.token, body: map[string]any{"password": calendartest.Password, "color": "sky"}}, 200)
	eventually(t, 10*time.Second, "recovered", func() bool {
		var ss []map[string]any
		e.get(base+"/calendars", owner.token).json(t, &ss)
		return ss[1]["status"] == "ok" && ss[1]["color"] == "sky"
	})

	// 3. Send an event (with a photo) from a member: invitation card on the frame, accept, sender is told.
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: d.token}, 200).m(t)["code"]
	bob := e.user("bob@x.org", "Bob")
	e.expect(req{method: "POST", path: "/v1/frames/redeem", token: bob.token, body: map[string]any{"code": code}}, 200)
	if r := e.do(req{method: "GET", path: base + "/calendars", token: bob.token}); r.StatusCode != 403 {
		t.Fatalf("member without change_settings listed calendars: %d", r.StatusCode)
	}
	bobWS := e.dialWS("/v1/ws?access_token="+bob.token, "")
	start := now.AddDate(0, 0, 2).Truncate(time.Hour)
	inviteBody := map[string]any{
		"title": "  Pancake   party ", "start_at": start.UTC().Format(time.RFC3339), "end_at": start.Add(2 * time.Hour).UTC().Format(time.RFC3339),
		"location": "Bob's kitchen", "notes": "Bring syrup", "photo_placement": "background",
	}
	photo := false
	if mediaTools() {
		inviteBody["photo_upload_id"] = e.upload(bob, mediatest.JPEG(2000, 1500, 0, "", false), "party.jpg", "image/jpeg")
		photo = true
	}
	inv := e.expect(req{method: "POST", path: base + "/events", token: bob.token, body: inviteBody, header: map[string]string{"Idempotency-Key": "inv-1"}}, 201).m(t)
	if inv["status"] != "pending" || inv["title"] != "Pancake party" || inv["sender_name"] != "Bob" || inv["has_photo"] != photo {
		t.Fatalf("invitation: %v", inv)
	}
	again := e.expect(req{method: "POST", path: base + "/events", token: bob.token, body: inviteBody, header: map[string]string{"Idempotency-Key": "inv-1"}}, 201).m(t)
	if again["id"] != inv["id"] {
		t.Fatal("idempotency key ignored")
	}
	msg := frameWS.await("event_invitation", 5*time.Second)
	if dig(msg, "invitation", "id") != inv["id"] {
		t.Fatalf("frame ws: %v", msg)
	}
	var pending []map[string]any
	e.get("/v1/device/invitations", d.token).json(t, &pending)
	if len(pending) != 1 || pending[0]["location"] != "Bob's kitchen" {
		t.Fatalf("pending invitations: %v", pending)
	}
	invID := inv["id"].(string)
	if photo {
		p := e.get("/v1/device/invitations/"+invID+"/photo", d.token)
		if p.StatusCode != 200 || p.Header.Get("Content-Type") != "image/webp" || p.Header.Get("ETag") == "" {
			t.Fatalf("invitation photo: %d %v", p.StatusCode, p.Header)
		}
		if r := e.do(req{method: "GET", path: "/v1/device/invitations/" + invID + "/photo", token: d.token, header: map[string]string{"If-None-Match": p.Header.Get("ETag")}}); r.StatusCode != 304 {
			t.Fatalf("photo revalidation: %d", r.StatusCode)
		}
		if r := e.get(base+"/events/"+invID+"/photo", bob.token); r.StatusCode != 200 {
			t.Fatalf("sender photo: %d", r.StatusCode)
		}
	}
	// Bad input is rejected.
	if r := e.do(req{method: "POST", path: base + "/events", token: bob.token, body: map[string]any{"title": "x", "start_at": start.Format(time.RFC3339), "end_at": start.Add(-time.Hour).Format(time.RFC3339)}}); r.StatusCode != 422 {
		t.Fatalf("end before start: %d", r.StatusCode)
	}
	if r := e.do(req{method: "POST", path: base + "/events", token: bob.token, body: map[string]any{"title": "x", "all_day": true}}); r.StatusCode != 422 {
		t.Fatalf("all-day without date: %d", r.StatusCode)
	}
	since = head()
	e.expect(req{method: "POST", path: "/v1/device/invitations/" + invID, token: d.token, body: map[string]any{"decision": "accept"}}, 204)
	decided := bobWS.await("event_invitation.decided", 5*time.Second)
	if dig(decided, "invitation", "status") != "accepted" || decided["frame_id"] != frameID {
		t.Fatalf("decision event: %v", decided)
	}
	if r := e.do(req{method: "POST", path: "/v1/device/invitations/" + invID, token: d.token, body: map[string]any{"decision": "decline"}}); r.StatusCode != 409 || r.code() != "already_decided" {
		t.Fatalf("second decision: %d %s", r.StatusCode, r.body)
	}
	cal = e.latestCalendar(d, since, "received event", func(c map[string]any) bool { return findEvent(c, "Pancake party") != nil })
	party := findEvent(cal, "Pancake party")
	local := cal["sources"].([]any)[0].(map[string]any)
	if local["kind"] != "local" || local["name"] != "" || party["source_id"] != local["id"] || party["sender_name"] != "Bob" ||
		party["invitation_id"] != invID || party["has_photo"] != photo || party["notes"] != "Bring syrup" {
		t.Fatalf("received event: %v / %v", party, local)
	}
	var sent []map[string]any
	e.get(base+"/events", bob.token).json(t, &sent)
	if len(sent) != 1 || sent[0]["status"] != "accepted" || sent[0]["decided_at"] == nil {
		t.Fatalf("sender's list: %v", sent)
	}
	// A declined one, and a withdrawn one.
	inv2 := e.expect(req{method: "POST", path: base + "/events", token: bob.token, body: map[string]any{"title": "Birthday", "all_day": true, "start_date": now.AddDate(0, 0, 10).Format("2006-01-02")}}, 201).m(t)
	e.expect(req{method: "POST", path: "/v1/device/invitations/" + inv2["id"].(string), token: d.token, body: map[string]any{"decision": "decline"}}, 204)
	if dig(bobWS.await("event_invitation.decided", 5*time.Second), "invitation", "status") != "declined" {
		t.Fatal("decline not told")
	}
	inv3 := e.expect(req{method: "POST", path: base + "/events", token: bob.token, body: map[string]any{"title": "Oops", "all_day": true, "start_date": now.Format("2006-01-02")}}, 201).m(t)
	e.expect(req{method: "DELETE", path: base + "/events/" + inv3["id"].(string), token: bob.token}, 204)
	if r := e.do(req{method: "DELETE", path: base + "/events/" + inv3["id"].(string), token: bob.token}); r.StatusCode != 409 {
		t.Fatalf("withdraw twice: %d", r.StatusCode)
	}
	e.get("/v1/device/invitations", d.token).json(t, &pending)
	if len(pending) != 0 {
		t.Fatalf("still pending: %v", pending)
	}

	// 4. Countdowns: marked from the app, shown in the change; cleared from the frame.
	since = head()
	e.expect(req{method: "PUT", path: base + "/countdowns/" + party["id"].(string), token: owner.token}, 204)
	e.expect(req{method: "PUT", path: base + "/countdowns/" + party["id"].(string), token: owner.token}, 204)
	if r := e.do(req{method: "PUT", path: base + "/countdowns/nosuchevent123", token: owner.token}); r.StatusCode != 404 {
		t.Fatalf("countdown for an unknown event: %d", r.StatusCode)
	}
	cal = e.latestCalendar(d, since, "countdown", func(c map[string]any) bool { return len(c["countdowns"].([]any)) == 1 })
	cd := cal["countdowns"].([]any)[0].(map[string]any)
	if cd["event_id"] != party["id"] || cd["title"] != "Pancake party" || cd["color"] != "rose" || findEvent(cal, "Pancake party")["countdown"] != true {
		t.Fatalf("countdown: %v", cd)
	}
	since = head()
	e.expect(req{method: "DELETE", path: "/v1/device/countdowns/" + party["id"].(string), token: d.token}, 204)
	e.latestCalendar(d, since, "countdown cleared", func(c map[string]any) bool { return len(c["countdowns"].([]any)) == 0 })

	// 5. The merged preview for the web, with weather (none here).
	view := e.get(base+"/calendar", owner.token).m(t)
	if len(view["events"].([]any)) != len(cal["events"].([]any)) || view["weather"] != nil {
		t.Fatalf("preview: %v", view)
	}

	// 6. Settings: a longer window re-expands without fetching.
	requests := fake.ICSRequests.Load()
	since = head()
	path := base + "/settings"
	v := e.get(path, owner.token).Header.Get("ETag")
	e.expect(req{method: "PATCH", path: path, token: owner.token, body: map[string]any{"calendar": map[string]any{"future_days": 90}}, header: map[string]string{"If-Match": v}}, 200)
	cal = e.latestCalendar(d, since, "wider window", func(c map[string]any) bool {
		return dig(c, "window", "to") == now.AddDate(0, 0, 90).In(mustLoc(cal["timezone"].(string))).Format("2006-01-02")
	})
	if fake.ICSRequests.Load() != requests {
		t.Fatal("window change refetched the calendar")
	}

	// 7. Disabling hides a source's events; removing the last remote source keeps the received events.
	since = head()
	e.expect(req{method: "PATCH", path: base + "/calendars/" + dav["id"].(string), token: owner.token, body: map[string]any{"enabled": false}}, 200)
	cal = e.latestCalendar(d, since, "disabled source", func(c map[string]any) bool { return findEvent(c, "Dentist") == nil })
	e.expect(req{method: "DELETE", path: base + "/calendars/" + ics["id"].(string), token: owner.token}, 204)
	e.expect(req{method: "DELETE", path: base + "/calendars/" + dav["id"].(string), token: owner.token}, 204)
	cal = e.latestCalendar(d, since, "removed sources", func(c map[string]any) bool { return len(c["sources"].([]any)) == 1 })
	if titles := eventTitles(cal); len(titles) != 1 || titles[0] != "Pancake party" {
		t.Fatalf("after removal: %v", titles)
	}
	// Removing the received-events calendar clears it: calendar: null.
	since = head()
	e.expect(req{method: "DELETE", path: base + "/calendars/" + local["id"].(string), token: owner.token}, 204)
	eventually(t, 5*time.Second, "calendar: null", func() bool {
		cs := e.calendarChanges(d, since)
		return len(cs) == 1 && cs[0]["calendar"] == nil
	})
	if cs := e.calendarChanges(d, 0); len(cs) != 0 {
		t.Fatalf("snapshot after removing everything: %v", cs)
	}

	// Frame-side sync now is accepted.
	e.expect(req{method: "POST", path: "/v1/device/calendar/sync", token: d.token}, 202)
}

func TestCalendarDisabled(t *testing.T) {
	e := newEnv(t)
	if dig(e.get("/.well-known/kinframe", "").m(t), "calendar") != false {
		t.Fatal("discovery announces calendars")
	}
	u := e.user("off@x.org", "Off")
	_, frameID := e.claim(u)
	if r := e.do(req{method: "GET", path: "/v1/frames/" + frameID + "/calendars", token: u.token}); r.StatusCode != 503 || r.code() != "feature_disabled" {
		t.Fatalf("list when disabled: %d %s", r.StatusCode, r.body)
	}
}

func contains(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}

// mediaTools reports whether vips/ffmpeg are available (photo invitations need vips).
func mediaTools() bool {
	t := media.Tools{Vips: os.Getenv("KINFRAME_VIPS"), VipsHeader: os.Getenv("KINFRAME_VIPSHEADER"),
		FFmpeg: os.Getenv("KINFRAME_FFMPEG"), FFprobe: os.Getenv("KINFRAME_FFPROBE")}
	return len(t.Missing()) == 0
}

func mustLoc(name string) *time.Location {
	l, err := time.LoadLocation(name)
	if err != nil {
		panic(fmt.Sprint(err))
	}
	return l
}
