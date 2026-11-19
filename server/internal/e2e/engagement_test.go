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

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
	"github.com/jeroentrappers/kinframe/server/internal/notify/pushtest"
	"github.com/jeroentrappers/kinframe/server/internal/shop"
	"github.com/jeroentrappers/kinframe/server/internal/shop/smtptest"
)

// Encouragement to share (docs/spec/family-social.md §9, README §5.20) over real HTTP, with the scheduler
// driven by a controlled clock.

var brussels, _ = time.LoadLocation("Europe/Brussels")

// local is a wall time in Brussels.
func local(s string) time.Time {
	t, err := time.ParseInLocation("2006-01-02 15:04", s, brussels)
	if err != nil {
		panic(err)
	}
	return t
}

func (e *env) sql(q string, args ...any) {
	e.t.Helper()
	if _, err := e.app.Pool.Exec(context.Background(), q, args...); err != nil {
		e.t.Fatalf("%s: %v", q, err)
	}
}

func (e *env) count(q string, args ...any) int {
	e.t.Helper()
	var n int
	if err := e.app.Pool.QueryRow(context.Background(), q, args...).Scan(&n); err != nil {
		e.t.Fatalf("%s: %v", q, err)
	}
	return n
}

// tick runs the engagement scheduler at now.
func (e *env) tick(now time.Time) {
	e.setClock(now)
	e.app.API.EngagementTick(context.Background(), now)
}

// sqlItem makes a ready photo item of u.
func (e *env) sqlItem(u *user) uuid.UUID {
	m, i := uuid.Must(uuid.NewV7()), uuid.Must(uuid.NewV7())
	e.sql(`INSERT INTO media (id, owner_user_id, kind, status, width, height) VALUES ($1, $2, 'photo', 'ready', 800, 600)`, m, u.id)
	e.sql(`INSERT INTO items (id, media_id, sender_user_id) VALUES ($1, $2, $3)`, i, m, u.id)
	return i
}

// sqlPlace puts one new photo of u on a frame at `at` (a personal send).
func (e *env) sqlPlace(u *user, frameID string, at time.Time) {
	e.t.Helper()
	send, item := uuid.Must(uuid.NewV7()), e.sqlItem(u)
	e.sql(`INSERT INTO sends (id, sender_user_id, created_at) VALUES ($1, $2, $3)`, send, u.id, at)
	e.sql(`INSERT INTO send_items (send_id, item_id, position) VALUES ($1, $2, 0)`, send, item)
	e.sql(`INSERT INTO placements (id, frame_id, item_id, send_id, status, received_at) VALUES ($1, $2, $3, $4, 'ready', $5)`,
		uuid.Must(uuid.NewV7()), frameID, item, send, at)
}

// sqlPost makes a personal feed post of u with n photos at `at`.
func (e *env) sqlPost(u *user, feedID string, at time.Time, n int) string {
	e.t.Helper()
	send, post := uuid.Must(uuid.NewV7()), uuid.Must(uuid.NewV7())
	e.sql(`INSERT INTO sends (id, sender_user_id, created_at) VALUES ($1, $2, $3)`, send, u.id, at)
	e.sql(`INSERT INTO feed_posts (id, feed_id, send_id, poster_user_id, created_at) VALUES ($1, $2, $3, $4, $5)`, post, feedID, send, u.id, at)
	for i := 0; i < n; i++ {
		item := e.sqlItem(u)
		e.sql(`INSERT INTO send_items (send_id, item_id, position) VALUES ($1, $2, $3)`, send, item, i)
		e.sql(`INSERT INTO feed_post_items (post_id, item_id, position) VALUES ($1, $2, $3)`, post, item, i)
	}
	return post.String()
}

func (e *env) subscribeSQL(feedID, frameID string) {
	e.sql(`INSERT INTO subscriptions (id, feed_id, frame_id, status, history, activated_at) VALUES ($1, $2, $3, 'active', 'none', now())`,
		uuid.Must(uuid.NewV7()), feedID, frameID)
}

func (e *env) nudges(u *user) map[string]int {
	e.t.Helper()
	rows, err := e.app.Pool.Query(context.Background(), `SELECT kind, count(*) FROM nudges WHERE user_id = $1 GROUP BY kind`, u.id)
	if err != nil {
		e.t.Fatal(err)
	}
	defer rows.Close()
	out := map[string]int{}
	for rows.Next() {
		var k string
		var n int
		_ = rows.Scan(&k, &n)
		out[k] = n
	}
	return out
}

type reminderPush struct {
	Type    string            `json:"type"`
	Key     string            `json:"key"`
	Title   string            `json:"title"`
	Body    string            `json:"body"`
	IDs     map[string]string `json:"ids"`
	Tag     string            `json:"tag"`
	Actions []string          `json:"actions"`
}

// webPushInbox registers a browser push endpoint for u and returns the pushes it receives.
func (e *env) webPushInbox(u *user, locale string) chan reminderPush {
	e.t.Helper()
	br := pushtest.NewBrowser()
	msgs := make(chan reminderPush, 20)
	svc := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		plain, err := br.Decrypt(body)
		if err != nil {
			e.t.Errorf("decrypt: %v", err)
		}
		var m reminderPush
		_ = json.Unmarshal(plain, &m)
		msgs <- m
		w.WriteHeader(http.StatusCreated)
	}))
	e.t.Cleanup(svc.Close)
	e.expect(req{method: "POST", path: "/v1/me/push-endpoints", token: u.token, body: map[string]any{
		"kind": "webpush", "endpoint": svc.URL + "/push/" + u.id, "locale": locale,
		"webpush_keys": map[string]any{"p256dh": br.P256dh(), "auth": br.AuthB64()},
	}}, 200)
	return msgs
}

func pushes(ch chan reminderPush, wait time.Duration) []reminderPush {
	var out []reminderPush
	deadline := time.After(wait)
	for {
		select {
		case m := <-ch:
			out = append(out, m)
		case <-deadline:
			return out
		}
	}
}

// reminderScene: Anna's family "Peeters" with her frame. On Sunday 22 November 2026 at 17:30 in Brussels her
// frame has had no new photos for 10 days, the family's streak (goal 3) is at risk and Oma's birthday is on
// Wednesday the 25th: three reminders want to go to Anna.
func reminderScene(t *testing.T, e *env) (*user, string, string) {
	anna := e.user("anna@x.org", "Anna")
	e.expect(req{method: "PATCH", path: "/v1/me", token: anna.token, body: map[string]any{"timezone": "Europe/Brussels"}}, 200)
	_, frame := e.claim(anna)
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.subscribeSQL(feed, frame)
	e.sqlPlace(anna, frame, local("2026-11-12 12:00"))
	e.sqlPost(anna, feed, local("2026-11-12 12:00"), 1) // she helped last week
	e.sql(`INSERT INTO feed_streak_weeks (feed_id, week_of, photos, goal, outcome) VALUES ($1, '2026-11-09', 4, 3, 'met')`, feed)
	e.sql(`INSERT INTO feed_streak_helpers (feed_id, week_of, user_id) VALUES ($1, '2026-11-09', $2)`, feed, anna.id)
	e.sql(`INSERT INTO feed_streaks (feed_id, current_weeks, best_weeks, week_of, freeze_granted_month) VALUES ($1, 5, 5, '2026-11-16', '2026-11-01')`, feed)
	e.sql(`INSERT INTO feed_birthdays (id, feed_id, status, display_name, name_key, month, day) VALUES ($1, $2, 'approved', 'Oma Peeters', 'oma peeters', 11, 25)`,
		uuid.Must(uuid.NewV7()), feed)
	return anna, frame, feed
}

// The budget, its order, quiet hours, snooze, the push actions and the outcome log (README §5.20).
func TestEngagementReminderBudget(t *testing.T) {
	e := newEnv(t, withWebPush(t))
	anna, frame, feed := reminderScene(t, e)
	inbox := e.webPushInbox(anna, "nl-BE")

	// Sunday 21:30 is in Anna's quiet hours: nothing goes out.
	e.tick(local("2026-11-22 21:30"))
	if n := e.nudges(anna); len(n) != 0 {
		t.Fatalf("reminders during quiet hours: %v", n)
	}
	// 17:30: birthday and streak go first; the quiet frame is over the budget of 2.
	e.tick(local("2026-11-22 17:30"))
	n := e.nudges(anna)
	if n["reminder_birthday"] != 1 || n["reminder_streak"] != 1 || n["reminder_quiet"] != 0 {
		t.Fatalf("nudges %v", n)
	}
	got := pushes(inbox, 3*time.Second)
	if len(got) != 2 {
		t.Fatalf("pushes %+v", got)
	}
	for _, p := range got {
		if len(p.Actions) != 2 || p.Actions[0] != "snooze_month" || p.Actions[1] != "fewer_reminders" || p.Tag == "" {
			t.Fatalf("push without actions or tag: %+v", p)
		}
		switch p.Key {
		case "reminder_birthday":
			if p.IDs["feed_id"] != feed || p.IDs["date"] != "2026-11-25" || !strings.Contains(p.Title, "Oma") {
				t.Fatalf("birthday push %+v", p)
			}
		case "reminder_streak":
			if p.IDs["week_of"] != "2026-11-16" || !strings.Contains(p.Title+p.Body, "Peeters") {
				t.Fatalf("streak push %+v", p)
			}
		default:
			t.Fatalf("unexpected push %+v", p)
		}
	}
	// Running again changes nothing (dedupe keys and the budget).
	e.tick(local("2026-11-22 17:45"))
	if m := e.nudges(anna); m["reminder_birthday"] != 1 || m["reminder_streak"] != 1 || m["reminder_quiet"] != 0 {
		t.Fatalf("second run %v", m)
	}
	// A week later the budget has room again: the frame is still quiet, but Anna snoozed reminders meanwhile.
	e.setClock(local("2026-11-23 09:00"))
	r := e.expect(req{method: "PATCH", path: "/v1/me/reminders", token: anna.token, body: map[string]any{"snooze_days": 30}}, 200).m(t)
	if r["snoozed_until"] == nil {
		t.Fatalf("snooze: %v", r)
	}
	e.tick(local("2026-11-30 10:00"))
	if m := e.nudges(anna); m["reminder_quiet"] != 0 {
		t.Fatalf("reminded while snoozed: %v", m)
	}
	e.expect(req{method: "PATCH", path: "/v1/me/reminders", token: anna.token, body: map[string]any{"snooze_days": 0}}, 200)
	e.tick(local("2026-11-30 10:15"))
	if m := e.nudges(anna); m["reminder_quiet"] != 1 {
		t.Fatalf("quiet frame: %v", m)
	}
	if p := pushes(inbox, 3*time.Second); len(p) != 1 || p[0].Key != "reminder_quiet" || p[0].IDs["frame_id"] != frame ||
		p[0].Title == "" || !strings.Contains(p[0].Body, "17") {
		t.Fatalf("quiet push %+v", p)
	}
	// Per frame once a week: the next day nothing more.
	e.tick(local("2026-12-01 10:15"))
	if m := e.nudges(anna); m["reminder_quiet"] != 1 {
		t.Fatalf("quiet frame twice: %v", m)
	}

	// Nobody answered: all four were ignored; after four in a row Anna drops to fewer, told once.
	e.tick(local("2026-12-02 12:00"))
	if c := e.count(`SELECT count(*) FROM nudges WHERE user_id = $1 AND outcome = 'ignored'`, anna.id); c != 3 {
		t.Fatalf("ignored %d", c)
	}
	prefs := e.get("/v1/me/reminders", anna.token).m(t)
	if prefs["level"] != "normal" {
		t.Fatalf("lowered after 3: %v", prefs)
	}
	e.sql(`INSERT INTO nudges (id, user_id, kind, dedupe_key, sent_at) VALUES ($1, $2, 'reminder_quiet', 'old', $3)`,
		uuid.Must(uuid.NewV7()), anna.id, local("2026-11-02 10:00"))
	e.tick(local("2026-12-02 12:15"))
	prefs = e.get("/v1/me/reminders", anna.token).m(t)
	if prefs["level"] != "fewer" || prefs["auto_reduced"] != true {
		t.Fatalf("not lowered after 4: %v", prefs)
	}
	prefs = e.expect(req{method: "PATCH", path: "/v1/me/reminders", token: anna.token, body: map[string]any{"auto_reduced_seen": true}}, 200).m(t)
	if prefs["level"] != "fewer" || prefs["auto_reduced"] != false {
		t.Fatalf("seen: %v", prefs)
	}
	// Validation.
	if r := e.do(req{method: "PATCH", path: "/v1/me/reminders", token: anna.token, body: map[string]any{"quiet_hours": map[string]any{"start": "25:00", "end": "08:00"}}}); r.StatusCode != 422 && r.StatusCode != 400 {
		t.Fatalf("bad quiet hours: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "PATCH", path: "/v1/me/reminders", token: anna.token, body: map[string]any{"email_digest": "daily"}}); r.StatusCode != 422 {
		t.Fatalf("digest without mail: %d %s", r.StatusCode, r.body)
	}
}

// With `fewer` only one reminder a week goes out and never a streak reminder; a share answers it.
func TestEngagementFewerReminders(t *testing.T) {
	e := newEnv(t)
	anna, frame, _ := reminderScene(t, e)
	e.expect(req{method: "PATCH", path: "/v1/me/reminders", token: anna.token, body: map[string]any{"level": "fewer"}}, 200)
	e.tick(local("2026-11-22 17:30"))
	if n := e.nudges(anna); n["reminder_birthday"] != 1 || len(n) != 1 {
		t.Fatalf("fewer: %v", n)
	}
	// Anna shares within 24 hours: the reminder counts as answered.
	e.setClock(local("2026-11-23 09:00"))
	e.sqlPlace(anna, frame, local("2026-11-23 09:00"))
	sendID := ""
	_ = e.app.Pool.QueryRow(context.Background(), `SELECT id::text FROM sends WHERE sender_user_id = $1 ORDER BY created_at DESC LIMIT 1`, anna.id).Scan(&sendID)
	e.app.API.AfterPersonalSend(context.Background(), uuid.MustParse(sendID))
	if c := e.count(`SELECT count(*) FROM nudges WHERE user_id = $1 AND outcome = 'shared'`, anna.id); c != 1 {
		t.Fatalf("shared outcome %d", c)
	}
	// Off: nothing at all.
	e.expect(req{method: "PATCH", path: "/v1/me/reminders", token: anna.token, body: map[string]any{"level": "off"}}, 200)
	e.tick(local("2026-12-06 17:30"))
	if n := e.nudges(anna); n["reminder_streak"] != 0 || n["reminder_quiet"] != 0 {
		t.Fatalf("off: %v", n)
	}
}

// Weeks close at Monday 00:00 in the family's zone: a missed week uses the freeze, the next restarts quietly;
// one freeze comes back on the 1st.
func TestEngagementStreakWeeks(t *testing.T) {
	e := newEnv(t)
	anna := e.user("anna@x.org", "Anna")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.setClock(local("2026-11-02 10:00")) // Monday
	en := e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/engagement", token: anna.token, body: map[string]any{"streak_goal": 2}}, 200).m(t)
	if en["timezone"] != "Europe/Brussels" || en["streak_goal"] != float64(2) || en["quiet_after_days"] != float64(7) {
		t.Fatalf("engagement %v", en)
	}
	e.tick(local("2026-11-02 10:00"))
	// Week of 2 November: met on Sunday 23:30 (local, after 22:30 UTC).
	e.sqlPost(anna, feed, local("2026-11-08 23:30"), 2)
	st := e.get("/v1/feeds/"+feed+"/streak", anna.token).m(t)
	if st["current_weeks"] != float64(1) || dig(st, "week", "met") != true || dig(st, "week", "photos") != float64(2) {
		t.Fatalf("met this week: %v", st)
	}
	f := e.get("/v1/feeds/"+feed, anna.token).m(t)
	if dig(f, "streak", "current_weeks") != float64(1) || dig(f, "freshness", "state") == nil {
		t.Fatalf("feed summary %v", f)
	}
	e.tick(local("2026-11-09 00:15"))
	// Week of 9 November: missed, the freeze is used.
	e.tick(local("2026-11-16 00:15"))
	st = e.get("/v1/feeds/"+feed+"/streak", anna.token).m(t)
	if st["current_weeks"] != float64(1) || st["freezes_left"] != float64(0) {
		t.Fatalf("frozen: %v", st)
	}
	// Week of 16 November: missed without a freeze: quietly back to 0.
	e.tick(local("2026-11-23 00:15"))
	st = e.get("/v1/feeds/"+feed+"/streak", anna.token).m(t)
	if st["current_weeks"] != float64(0) || st["best_weeks"] != float64(1) {
		t.Fatalf("missed: %v", st)
	}
	hist := st["history"].([]any)
	if len(hist) != 3 || hist[0].(map[string]any)["outcome"] != "missed" || hist[1].(map[string]any)["outcome"] != "frozen" || hist[2].(map[string]any)["outcome"] != "met" {
		t.Fatalf("history %v", hist)
	}
	// 1 December: a freeze again.
	e.tick(local("2026-12-01 00:15"))
	if st = e.get("/v1/feeds/"+feed+"/streak", anna.token).m(t); st["freezes_left"] != float64(1) {
		t.Fatalf("freeze on the 1st: %v", st)
	}
	// Turned off: hidden in the summary, still counted.
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/engagement", token: anna.token, body: map[string]any{"streak_enabled": false}}, 200)
	f = e.get("/v1/feeds/"+feed, anna.token).m(t)
	if v, ok := f["streak"]; !ok || v != nil {
		t.Fatalf("streak off: %v", f["streak"])
	}
	ben := e.user("ben@x.org", "Ben")
	if r := e.do(req{method: "PATCH", path: "/v1/feeds/" + feed + "/engagement", token: ben.token, body: map[string]any{"streak_goal": 4}}); r.StatusCode != 404 {
		t.Fatalf("non-member: %d", r.StatusCode)
	}
	if r := e.do(req{method: "PATCH", path: "/v1/feeds/" + feed + "/engagement", token: anna.token, body: map[string]any{"timezone": "Mars/Olympus"}}); r.StatusCode != 422 {
		t.Fatalf("bad zone: %d %s", r.StatusCode, r.body)
	}
}

// Weekly prompts: off by default, the rotation, an admin's own title, tagging and the frame's `prompts` change.
func TestEngagementPrompts(t *testing.T) {
	e := newEnv(t)
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	d, frame := e.claim(anna)
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	e.subscribeSQL(feed, frame)
	e.setClock(local("2026-11-19 10:00"))
	var list []map[string]any
	e.get("/v1/feeds/"+feed+"/prompts", anna.token).json(t, &list)
	if len(list) != 0 {
		t.Fatalf("prompts while off: %v", list)
	}
	lib := []map[string]any{}
	e.expect(req{method: "GET", path: "/v1/prompts", token: anna.token, header: map[string]string{"Accept-Language": "nl-BE"}}, 200).json(t, &lib)
	if len(lib) < 40 || lib[0]["key"] != "cooked" || lib[0]["title"] == "Something you cooked" {
		t.Fatalf("library %v", lib[:1])
	}
	if r := e.do(req{method: "PATCH", path: "/v1/feeds/" + feed + "/engagement", token: ben.token, body: map[string]any{"prompts_enabled": true}}); r.StatusCode != 403 {
		t.Fatalf("poster enables prompts: %d", r.StatusCode)
	}
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/engagement", token: anna.token, body: map[string]any{"prompts_enabled": true}}, 200)
	e.get("/v1/feeds/"+feed+"/prompts", anna.token).json(t, &list)
	if len(list) != 5 || list[4]["status"] != "current" || list[4]["week_of"] != "2026-11-16" || list[0]["status"] != "planned" {
		t.Fatalf("prompts %v", list)
	}
	current := list[4]
	keys := map[any]bool{}
	for _, p := range list {
		keys[p["library_key"]] = true
	}
	if len(keys) != 5 {
		t.Fatalf("repeated prompts: %v", list)
	}
	// The frame got the current prompt in its language (the frame was set up in Dutch).
	ch := e.changes(d, 0)
	ps := changesOf(ch, "prompts")
	if len(ps) != 1 || dig(ps[0], "prompts", "prompts") == nil {
		t.Fatalf("prompts change %v", ps)
	}
	fp := dig(ps[0], "prompts", "prompts").([]any)[0].(map[string]any)
	if fp["prompt_id"] != current["id"] || fp["feed_name"] != "Peeters" {
		t.Fatalf("frame prompt %v vs %v", fp, current)
	}
	// An admin writes this week's own title: same id, new title on the frame.
	r := e.expect(req{method: "PUT", path: "/v1/feeds/" + feed + "/prompts/2026-11-16", token: anna.token, body: map[string]any{"title": "Onze tuin"}}, 200).m(t)
	if r["id"] != current["id"] || r["source"] != "custom" || r["title"] != "Onze tuin" || dig(r, "set_by", "display_name") != "Anna" {
		t.Fatalf("custom prompt %v", r)
	}
	ch = e.changes(d, ch["next_seq"].(float64))
	if ps = changesOf(ch, "prompts"); len(ps) != 1 || dig(ps[0], "prompts", "prompts").([]any)[0].(map[string]any)["title"] != "Onze tuin" {
		t.Fatalf("prompts change after custom: %v", ps)
	}
	for _, bad := range []struct {
		week string
		body map[string]any
		code int
	}{
		{"2026-11-09", map[string]any{"skip": true}, 409},                    // past
		{"2026-11-17", map[string]any{"skip": true}, 422},                    // not a Monday
		{"2026-11-23", map[string]any{"skip": true, "title": "x"}, 422},      // two of three
		{"2026-11-23", map[string]any{"library_key": "no_such_prompt"}, 422}, // unknown
	} {
		if r := e.do(req{method: "PUT", path: "/v1/feeds/" + feed + "/prompts/" + bad.week, token: anna.token, body: bad.body}); r.StatusCode != bad.code {
			t.Fatalf("%v: %d %s", bad, r.StatusCode, r.body)
		}
	}
	e.expect(req{method: "PUT", path: "/v1/feeds/" + feed + "/prompts/2026-11-23", token: anna.token, body: map[string]any{"skip": true}}, 200)
	e.get("/v1/feeds/"+feed+"/prompts", anna.token).json(t, &list)
	if list[3]["status"] != "skipped" {
		t.Fatalf("skipped week %v", list[3])
	}
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/prompts/2026-11-23", token: anna.token}, 204)
	// Tagging: the poster only, this week's or last week's prompt.
	post := e.sqlPost(ben, feed, local("2026-11-19 09:00"), 1)
	if r := e.do(req{method: "PUT", path: "/v1/posts/" + post + "/prompt", token: anna.token, body: map[string]any{"prompt_id": current["id"]}}); r.StatusCode != 403 {
		t.Fatalf("not the poster: %d", r.StatusCode)
	}
	tp := e.expect(req{method: "PUT", path: "/v1/posts/" + post + "/prompt", token: ben.token, body: map[string]any{"prompt_id": current["id"]}}, 200).m(t)
	if dig(tp, "prompt", "title") != "Onze tuin" || tp["id"] != post {
		t.Fatalf("tagged %v", tp)
	}
	e.get("/v1/feeds/"+feed+"/prompts", anna.token).json(t, &list)
	if list[4]["answer_count"] != float64(1) {
		t.Fatalf("answers %v", list[4])
	}
	if r := e.do(req{method: "PUT", path: "/v1/posts/" + post + "/prompt", token: ben.token, body: map[string]any{"prompt_id": list[0]["id"]}}); r.StatusCode != 422 {
		t.Fatalf("future prompt: %d %s", r.StatusCode, r.body)
	}
	e.expect(req{method: "PUT", path: "/v1/posts/" + post + "/prompt", token: ben.token, body: map[string]any{"prompt_id": nil}}, 200)
	// Reset: back to the server's choice, same id.
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/prompts/2026-11-16", token: anna.token}, 204)
	e.get("/v1/feeds/"+feed+"/prompts", anna.token).json(t, &list)
	if list[4]["id"] != current["id"] || list[4]["source"] != "server" {
		t.Fatalf("reset %v", list[4])
	}
	// Turning prompts off empties the frame's prompts.
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/engagement", token: anna.token, body: map[string]any{"prompts_enabled": false}}, 200)
	ch = e.changes(d, ch["next_seq"].(float64))
	if ps = changesOf(ch, "prompts"); len(ps) == 0 || ps[len(ps)-1]["prompts"] != nil {
		t.Fatalf("prompts off: %v", ps)
	}
}

// "Ask for new photos" from the frame: once a day, pushed to the family, held through quiet hours, answered
// by the first photo that reaches the frame; the frame shows who answered.
func TestEngagementPhotoRequests(t *testing.T) {
	requireMedia(t)
	e := newEnv(t, withWebPush(t))
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	d, frame := e.claim(anna)
	e.join(ben, d)
	inbox := e.webPushInbox(ben, "en")
	disc := e.get("/.well-known/kinframe", "").m(t)
	if disc["engagement"] != true || disc["email_digest"] != false {
		t.Fatalf("discovery %v", disc)
	}
	set := e.get("/v1/frames/"+frame+"/settings", anna.token).m(t)
	if dig(set, "social", "ask_for_photos") != true || dig(set, "social", "show_comments") != false {
		t.Fatalf("social settings %v", set["social"])
	}
	fs := e.get("/v1/frames/"+frame, anna.token).m(t)
	if dig(fs, "freshness", "state") != "never" || fs["photo_request"] != nil {
		t.Fatalf("frame %v", fs)
	}
	// 23:00 in Brussels: Anna (no zone set: Brussels) and Ben are in their quiet hours.
	// (Two days ago: placements made now must come after the request.)
	day := time.Now().In(brussels).AddDate(0, 0, -2)
	at := func(d, h, m int) time.Time {
		return time.Date(day.Year(), day.Month(), day.Day()+d, h, m, 0, 0, brussels)
	}
	e.setClock(at(0, 23, 0))
	annaWS := e.dialWS("/v1/ws?access_token="+anna.token, "")
	r := e.expect(req{method: "POST", path: "/v1/device/photo-requests", token: d.token}, 201).m(t)
	if r["state"] != "open" || r["next_allowed_at"] == nil {
		t.Fatalf("request %v", r)
	}
	annaWS.await("photo_request.created", 5*time.Second)
	again := e.expect(req{method: "POST", path: "/v1/device/photo-requests", token: d.token}, 200).m(t)
	if again["id"] != r["id"] {
		t.Fatalf("second request %v", again)
	}
	if p := pushes(inbox, time.Second); len(p) != 0 {
		t.Fatalf("pushed in quiet hours: %+v", p)
	}
	// 09:15: the held push goes out.
	e.tick(at(1, 9, 15))
	if p := pushes(inbox, 3*time.Second); len(p) != 1 || p[0].Key != "frame_request" || p[0].IDs["request_id"] != r["id"] || p[0].Tag != "frame_request:"+frame || len(p[0].Actions) != 0 {
		t.Fatalf("request push %+v", p)
	}
	e.tick(at(1, 9, 30))
	if p := pushes(inbox, time.Second); len(p) != 0 {
		t.Fatalf("pushed twice %+v", p)
	}
	ch := e.changes(d, 0)
	if pr := changesOf(ch, "photo_request"); len(pr) != 1 || dig(pr[0], "photo_request", "state") != "open" {
		t.Fatalf("photo_request change %v", pr)
	}
	fs = e.get("/v1/frames/"+frame, ben.token).m(t)
	if dig(fs, "photo_request", "id") != r["id"] {
		t.Fatalf("frame request %v", fs["photo_request"])
	}
	// Ben answers with a photo.
	e.setClock(at(1, 10, 0))
	up := e.upload(ben, mediatest.JPEG(800, 600, 3, "", false), "b.jpg", "image/jpeg")
	e.sendOne(ben, []string{frame}, map[string]any{"upload_id": up})
	ev := annaWS.await("photo_request.updated", 5*time.Second)
	if dig(ev, "request", "state") != "answered" || dig(ev, "request", "answered_by", "display_name") != "Ben" {
		t.Fatalf("answered %v", ev)
	}
	ch = e.changes(d, ch["next_seq"].(float64))
	pr := changesOf(ch, "photo_request")
	if len(pr) != 1 || dig(pr[0], "photo_request", "answered_by_name") != "Ben" {
		t.Fatalf("answered change %v", pr)
	}
	fs = e.get("/v1/frames/"+frame, anna.token).m(t)
	if dig(fs, "freshness", "state") != "fresh" || dig(fs, "freshness", "last_shared_by", "display_name") != "Ben" || dig(fs, "photo_request", "state") != "answered" {
		t.Fatalf("frame after the answer %v", fs)
	}
	var list []map[string]any
	e.get("/v1/frames/"+frame+"/photo-requests", ben.token).json(t, &list)
	if len(list) != 1 || list[0]["state"] != "answered" {
		t.Fatalf("list %v", list)
	}
	e.get("/v1/photo-requests/"+r["id"].(string), ben.token)
	if res := e.do(req{method: "GET", path: "/v1/photo-requests/" + r["id"].(string), token: e.user("eve@x.org", "Eve").token}); res.StatusCode != 404 {
		t.Fatalf("stranger: %d", res.StatusCode)
	}
	// The owner turns the button off.
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frame + "/settings", token: anna.token,
		header: map[string]string{"If-Match": `"` + jsonNum(set["version"]) + `"`}, body: map[string]any{"social": map[string]any{"ask_for_photos": false}}}, 200)
	e.setClock(at(3, 10, 0))
	if res := e.do(req{method: "POST", path: "/v1/device/photo-requests", token: d.token}); res.StatusCode != 403 {
		t.Fatalf("button off: %d %s", res.StatusCode, res.body)
	}
}

func jsonNum(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}

// On this day, the weekly summary and the email digest.
func TestEngagementMemoriesSummaryDigest(t *testing.T) {
	out := smtptest.Start(t)
	e := newEnv(t, withWebPush(t), func(c *config.Config) {
		c.SMTP = &shop.SMTP{Host: out.Host, Port: out.Port, From: "kinframe <noreply@example.org>"}
	})
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	_, frame := e.claim(anna)
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	if disc := e.get("/.well-known/kinframe", "").m(t); disc["email_digest"] != true {
		t.Fatalf("discovery %v", disc)
	}
	// On this day: a post of 19 November 2025.
	e.sqlPost(ben, feed, local("2025-11-19 15:00"), 2)
	e.sqlPost(ben, feed, local("2025-11-18 15:00"), 1)
	e.setClock(local("2026-11-19 10:00"))
	otd := e.get("/v1/me/on-this-day", anna.token).m(t)
	groups := otd["groups"].([]any)
	if otd["date"] != "2026-11-19" || len(groups) != 1 || dig(groups[0].(map[string]any), "years_ago") != float64(1) || len(groups[0].(map[string]any)["items"].([]any)) != 2 {
		t.Fatalf("on this day %v", otd)
	}
	if g := e.get("/v1/me/on-this-day", e.user("eve@x.org", "Eve").token).m(t)["groups"].([]any); len(g) != 0 {
		t.Fatalf("a stranger sees %v", g)
	}

	// Weekly summary: Anna's photo was seen and loved on her frame this week.
	e.sqlPlace(anna, frame, local("2026-11-18 10:00"))
	e.sql(`UPDATE placements SET seen_at = $2, reaction = 'love', reaction_at = $2 WHERE frame_id = $1`, frame, local("2026-11-18 11:00"))
	inbox := e.webPushInbox(anna, "en")
	e.tick(local("2026-11-22 18:30"))
	if p := pushes(inbox, time.Second); len(p) != 0 {
		t.Fatalf("summary before 19:00: %+v", p)
	}
	e.tick(local("2026-11-22 19:15"))
	p := pushes(inbox, 3*time.Second)
	if len(p) != 1 || p[0].Key != "weekly_summary" || p[0].IDs["week_of"] != "2026-11-16" || !strings.Contains(p[0].Body, "1×") || !strings.Contains(p[0].Body, "❤ 1") {
		t.Fatalf("summary push %+v", p)
	}
	sum := e.get("/v1/me/weekly-summaries/2026-11-16", anna.token).m(t)
	if sum["seen_count"] != float64(1) || sum["love_count"] != float64(1) || sum["frame_count"] != float64(1) || sum["top_item"] == nil {
		t.Fatalf("summary %v", sum)
	}
	if r := e.do(req{method: "GET", path: "/v1/me/weekly-summaries/2026-11-09", token: anna.token}); r.StatusCode != 404 {
		t.Fatalf("no summary: %d", r.StatusCode)
	}
	if ms := e.get("/v1/me/milestones", anna.token); ms.StatusCode != 200 {
		t.Fatal("milestones")
	}

	// Email digest: daily at 08:00 local with Ben's unread message... here: a comment on Anna's post.
	e.setClock(local("2026-11-22 20:00"))
	e.expect(req{method: "PATCH", path: "/v1/me/reminders", token: anna.token, body: map[string]any{"email_digest": "daily"}}, 200)
	post := e.sqlPost(anna, feed, local("2026-11-22 20:00"), 1)
	e.sql(`INSERT INTO threads (post_id, feed_post_id, feed_id) VALUES ($1, $1, $2)`, post, feed)
	e.sql(`INSERT INTO comments (id, post_id, author_user_id, body, created_at) VALUES ($1, $2, $3, 'Look at her face 😂', $4)`,
		uuid.Must(uuid.NewV7()), post, ben.id, local("2026-11-22 21:00"))
	e.tick(local("2026-11-23 07:45"))
	if len(out.Mails()) != 0 {
		t.Fatal("digest before 08:00")
	}
	e.tick(local("2026-11-23 08:15"))
	mails := out.Wait(t, 1)
	m := mails[0]
	if m.To != "anna@x.org" || !strings.Contains(m.Text, "Ben") || !strings.Contains(m.Text, "Look at her face") || !strings.Contains(m.Text, "/v1/email-digest/stop?") {
		t.Fatalf("digest %+v", m)
	}
	e.tick(local("2026-11-23 08:30"))
	if len(out.Mails()) != 1 {
		t.Fatal("digest twice")
	}
	// The stop link: GET asks, POST stops.
	i := strings.Index(m.Text, e.url+"/v1/email-digest/stop?")
	link := strings.Fields(m.Text[i:])[0]
	if r := e.do(req{method: "GET", path: link}); r.StatusCode != 200 || !strings.Contains(string(r.body), "<form") {
		t.Fatalf("stop page %d", r.StatusCode)
	}
	if r := e.get("/v1/me/reminders", anna.token).m(t); r["email_digest"] != "daily" {
		t.Fatal("GET changed the setting")
	}
	q := link[strings.Index(link, "?")+1:]
	e.expect(req{method: "POST", path: "/v1/email-digest/stop", raw: []byte(q), header: map[string]string{"Content-Type": "application/x-www-form-urlencoded"}}, 200)
	if r := e.get("/v1/me/reminders", anna.token).m(t); r["email_digest"] != "off" {
		t.Fatalf("stop: %v", r)
	}
	if r := e.do(req{method: "POST", path: "/v1/email-digest/stop", raw: []byte("u=" + anna.id + "&t=forged"), header: map[string]string{"Content-Type": "application/x-www-form-urlencoded"}}); r.StatusCode != 404 {
		t.Fatalf("forged stop: %d", r.StatusCode)
	}
}

// "Show comments" on the frame: the three newest comments of a placement's family post, first names only, cut
// at 280 characters; dropped when turned off.
func TestEngagementCommentCards(t *testing.T) {
	e := newEnv(t)
	anna, ben := e.user("anna@x.org", "Anna Peeters"), e.user("ben@x.org", "Ben Janssens")
	d, frame := e.claim(anna)
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	sub := uuid.Must(uuid.NewV7())
	e.sql(`INSERT INTO subscriptions (id, feed_id, frame_id, status, history, activated_at) VALUES ($1, $2, $3, 'active', 'none', now())`, sub, feed, frame)
	post := e.sqlPost(ben, feed, time.Now().Add(-time.Hour), 1)
	var item uuid.UUID
	_ = e.app.Pool.QueryRow(context.Background(), `SELECT item_id FROM feed_post_items WHERE post_id = $1`, post).Scan(&item)
	placement := uuid.Must(uuid.NewV7())
	e.sql(`INSERT INTO placements (id, frame_id, item_id, status, origin, origin_feed_id, held_directly) VALUES ($1, $2, $3, 'ready', 'feed', $4, false)`, placement, frame, item, feed)
	e.sql(`INSERT INTO feed_deliveries (subscription_id, post_id, item_id, placement_id, state) VALUES ($1, $2, $3, $4, 'placed')`, sub, post, item, placement)
	e.sql(`INSERT INTO threads (post_id, feed_post_id, feed_id) VALUES ($1, $1, $2)`, post, feed)
	for _, body := range []string{"first", "second", "third", strings.Repeat("ä", 300)} {
		e.sql(`INSERT INTO comments (id, post_id, author_user_id, body) VALUES ($1, $2, $3, $4)`, uuid.Must(uuid.NewV7()), post, anna.id, body)
	}
	ch := e.changes(d, 0)
	if c := changesOf(ch, "comments"); len(c) != 0 {
		t.Fatalf("comments while off: %v", c)
	}
	set := e.get("/v1/frames/"+frame+"/settings", anna.token).m(t)
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frame + "/settings", token: anna.token,
		header: map[string]string{"If-Match": `"` + jsonNum(set["version"]) + `"`}, body: map[string]any{"social": map[string]any{"show_comments": true}}}, 200)
	ch = e.changes(d, ch["next_seq"].(float64))
	cs := changesOf(ch, "comments")
	if len(cs) != 1 || cs[0]["id"] != placement.String() {
		t.Fatalf("comments change %v", cs)
	}
	list := dig(cs[0], "comments", "comments").([]any)
	newest := list[0].(map[string]any)
	if len(list) != 3 || newest["author_name"] != "Anna" || len([]rune(newest["body"].(string))) != 280 || list[2].(map[string]any)["body"] != "second" {
		t.Fatalf("comments %v", list)
	}
	// A new comment reaches the frame through the scheduler; an unchanged one is not sent again.
	e.sql(`INSERT INTO comments (id, post_id, author_user_id, body) VALUES ($1, $2, $3, 'Mooi!')`, uuid.Must(uuid.NewV7()), post, ben.id)
	e.tick(time.Now())
	ch = e.changes(d, ch["next_seq"].(float64))
	if cs = changesOf(ch, "comments"); len(cs) != 1 || dig(cs[0], "comments", "comments").([]any)[0].(map[string]any)["author_name"] != "Ben" {
		t.Fatalf("new comment %v", cs)
	}
	e.tick(time.Now())
	ch = e.changes(d, ch["next_seq"].(float64))
	if cs = changesOf(ch, "comments"); len(cs) != 0 {
		t.Fatalf("sent again %v", cs)
	}
	// Turned off: comments null.
	set = e.get("/v1/frames/"+frame+"/settings", anna.token).m(t)
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frame + "/settings", token: anna.token,
		header: map[string]string{"If-Match": `"` + jsonNum(set["version"]) + `"`}, body: map[string]any{"social": map[string]any{"show_comments": false}}}, 200)
	ch = e.changes(d, ch["next_seq"].(float64))
	if cs = changesOf(ch, "comments"); len(cs) != 1 || cs[0]["comments"] != nil {
		t.Fatalf("off %v", cs)
	}
}
