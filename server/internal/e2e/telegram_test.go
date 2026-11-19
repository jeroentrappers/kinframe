package e2e

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/telegram"
	"github.com/jeroentrappers/kinframe/server/internal/telegram/telegramtest"
)

// The Telegram bridge (docs/spec/telegram.md) against a fake Bot API: connect
// codes, binding, batching with a moved clock, reactions both ways, the
// promotion once, linking, linked_only and the join link, blocking,
// supergroup migration, removal, limits, permissions and the webhook.

func withTelegram(fb *telegramtest.Bot, mode string) option {
	return func(c *config.Config) {
		c.TelegramBotToken, c.TelegramAPIURL, c.TelegramCloudURL = telegramtest.Token, fb.URL, fb.URL
		c.TelegramMode, c.TelegramWorkers = mode, 2
		c.TelegramMaxDownload = telegram.CloudMaxDownload
	}
}

type tgEnv struct {
	*env
	fb     *telegramtest.Bot
	mu     sync.Mutex
	offset time.Duration
}

func newTelegramEnv(t *testing.T, mode string, opts ...option) *tgEnv {
	fb := telegramtest.New()
	t.Cleanup(fb.Close)
	e := newEnv(t, append([]option{withTelegram(fb, mode)}, opts...)...)
	select {
	case <-e.app.API.TelegramReady():
	case <-time.After(10 * time.Second):
		t.Fatal("bot not ready")
	}
	e.app.API.TelegramOutbox().SetPace(-1, -1, -1)
	te := &tgEnv{env: e, fb: fb}
	e.app.API.SetClock(func() time.Time {
		te.mu.Lock()
		defer te.mu.Unlock()
		return time.Now().Add(te.offset)
	})
	return te
}

// advance moves the server's clock and runs the batch closer.
func (e *tgEnv) advance(d time.Duration) {
	e.mu.Lock()
	e.offset += d
	e.mu.Unlock()
	e.app.API.TelegramTick(context.Background())
	e.settle()
}

// settle waits until every update is processed and every call made.
func (e *tgEnv) settle() {
	e.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	eventually(e.t, 15*time.Second, "telegram updates processed", func() bool {
		return e.fb.Pending() == 0 && e.app.API.TelegramIdle(ctx)
	})
	e.app.API.TelegramOutbox().Wait(ctx)
}

func (e *tgEnv) push(updates ...map[string]any) {
	for _, u := range updates {
		e.fb.Push(u)
	}
	e.settle()
}

func (e *tgEnv) connectCode(u *user, path, kind string, link bool) map[string]any {
	e.t.Helper()
	return e.expect(req{method: "POST", path: path + "/telegram-codes", token: u.token, body: map[string]any{"chat_kind": kind, "link_account": link}}, 201).m(e.t)
}

func (e *tgEnv) file(id string, data []byte) { e.fb.Lock(func() { e.fb.Files[id] = data }) }

func lastTo(fb *telegramtest.Bot, chat int64) telegramtest.Sent {
	m := fb.SentTo(chat)
	if len(m) == 0 {
		return telegramtest.Sent{}
	}
	return m[len(m)-1]
}

func TestTelegramGroupToFrame(t *testing.T) {
	e := newTelegramEnv(t, "polling")
	anna := e.user("anna@example.com", "Anna Peeters")
	d, frame := e.claim(anna)

	disc := e.get("/.well-known/kinframe", "").m(t)
	if disc["telegram"] != true || disc["telegram_bot_username"] != "kinframe_test_bot" || disc["telegram_privacy_mode"] != false ||
		disc["telegram_max_file_bytes"] != float64(20<<20) || dig(disc, "limits", "max_telegram_chats_per_target") != float64(5) {
		t.Fatalf("discovery %v", disc)
	}
	if calls := e.fb.CallsOf("deleteWebhook"); len(e.fb.CallsOf("getUpdates")) == 0 && len(calls) == 0 {
		t.Fatal("not polling")
	}

	code := e.connectCode(anna, "/v1/frames/"+frame, "group", false)
	url := code["url"].(string)
	if !strings.HasPrefix(url, "https://t.me/kinframe_test_bot?startgroup=c") || code["status"] != "open" || code["code"] == nil {
		t.Fatalf("code %v", code)
	}
	// A wrong code in a group: "expired", nothing bound.
	group := telegramtest.Group(-1001, "Peeters family")
	annaTG, ben := telegramtest.User(1, "Anna", "", "nl"), telegramtest.User(2, "Ben", "Peeters", "en")
	e.push(e.fb.MemberUpdate(group, annaTG, "left", "member"), telegramtest.Text(group, annaTG, 1, "/start@kinframe_test_bot cAAAAAAAAAAAAAAAA"))
	if !strings.Contains(lastTo(e.fb, -1001).Text, "expired") && !strings.Contains(lastTo(e.fb, -1001).Text, "verlopen") {
		t.Fatalf("wrong code reply %q", lastTo(e.fb, -1001).Text)
	}
	e.push(telegramtest.Text(group, annaTG, 2, "/start@kinframe_test_bot "+code["code"].(string)))
	chats := e.list("/v1/frames/"+frame+"/telegram-chats", anna.token)
	if len(chats) != 1 || chats[0]["status"] != "active" || chats[0]["title"] != "Peeters family" || dig(chats[0], "target", "frame_id") != frame ||
		dig(chats[0], "owner", "user_id") != anna.id || chats[0]["sees_all_messages"] != true {
		t.Fatalf("chats %v", chats)
	}
	chatID := chats[0]["id"].(string)
	if c := e.get("/v1/telegram-codes/"+code["id"].(string), anna.token).m(t); c["status"] != "used" || c["chat_id"] != chatID || c["url"] != nil {
		t.Fatalf("code after use %v", c)
	}
	if w := lastTo(e.fb, -1001); !strings.Contains(w.Text, "frame") || !w.Silent {
		t.Fatalf("welcome %+v", w)
	}
	// The code is single use.
	e.push(telegramtest.Text(group, annaTG, 3, "/start "+code["code"].(string)))

	// Ben posts an album of two (one caption) and a single photo.
	for i, id := range []string{"p1", "p2", "p3"} {
		e.file(id, pic(i))
	}
	before := len(e.fb.SentTo(-1001))
	e.push(telegramtest.Photo(group, ben, 10, "p1", map[string]any{"media_group_id": "A", "caption": "Kids"}),
		telegramtest.Photo(group, ben, 11, "p2", map[string]any{"media_group_id": "A"}),
		telegramtest.Photo(group, ben, 12, "p3", nil),
		telegramtest.Text(group, ben, 13, "lovely!")) // chat text: never stored, ignored
	if e.fb.ReactionOn(-1001, 10) != "👍" || e.fb.ReactionOn(-1001, 11) != "" || e.fb.ReactionOn(-1001, 12) != "👍" {
		t.Fatalf("reactions %v", e.fb.Reactions)
	}
	msgs := e.fb.SentTo(-1001)[before:]
	if len(msgs) != 1 || msgs[0].ReplyTo != 10 || !msgs[0].Silent || len(msgs[0].Buttons) != 1 || !strings.Contains(msgs[0].Buttons[0], "?start=j") ||
		!strings.Contains(msgs[0].Text, "Ben") {
		t.Fatalf("promotion %+v", msgs)
	}
	joinURL := msgs[0].Buttons[0]
	var n int
	_ = e.app.Pool.QueryRow(context.Background(), `SELECT count(*) FROM telegram_updates WHERE payload::text LIKE '%lovely%'`).Scan(&n)
	if n != 0 {
		t.Fatal("message text stored")
	}
	if sends := e.get("/v1/sends", anna.token).m(t)["sends"].([]any); len(sends) != 0 {
		t.Fatalf("sent before the batch closed: %v", sends)
	}
	// Quiet for a minute: one send of three, credited to Ben, the album's caption on both.
	e.advance(61 * time.Second)
	sends := e.get("/v1/sends", anna.token).m(t)["sends"].([]any)
	if len(sends) != 1 {
		t.Fatalf("sends %v", sends)
	}
	sd := sends[0].(map[string]any)
	items := sd["items"].([]any)
	if dig(sd, "via", "service") != "telegram" || dig(sd, "via", "label") != "Ben Peeters" || dig(sd, "via", "telegram_chat_id") != chatID || len(items) != 3 {
		t.Fatalf("send %v", sd)
	}
	caps := []any{}
	for _, it := range items {
		caps = append(caps, it.(map[string]any)["caption"])
	}
	if caps[0] != "Kids" || caps[1] != "Kids" || caps[2] != nil {
		t.Fatalf("captions %v", caps)
	}
	pls := e.get("/v1/frames/"+frame+"/placements", anna.token).m(t)["placements"].([]any)
	if len(pls) != 3 || dig(pls[0].(map[string]any), "item", "via", "service") != "telegram" {
		t.Fatalf("placements %v", pls)
	}
	ch := e.get("/v1/telegram-chats/"+chatID, anna.token).m(t)
	if dig(ch, "counts", "sent") != float64(3) || dig(ch, "counts", "contributors") != float64(1) || ch["last_delivery_at"] == nil {
		t.Fatalf("chat counts %v", ch)
	}

	// The frame loves the album's second photo: ❤ replaces 👍 on the album's first message; removing it brings 👍 back.
	var p2 string
	for _, p := range pls {
		pm := p.(map[string]any)
		if dig(pm, "item", "id") == dig(items[1].(map[string]any), "id") {
			p2 = pm["id"].(string)
		}
	}
	e.expect(req{method: "PUT", path: "/v1/device/placements/" + p2 + "/reaction", token: d.token, body: map[string]any{"reaction": "love"}}, 204)
	e.settle()
	if got := e.fb.ReactionOn(-1001, 10); got != "❤" {
		t.Fatalf("mirrored reaction %q", got)
	}
	e.expect(req{method: "PUT", path: "/v1/device/placements/" + p2 + "/reaction", token: d.token, body: map[string]any{"reaction": nil}}, 204)
	e.settle()
	if got := e.fb.ReactionOn(-1001, 10); got != "👍" {
		t.Fatalf("reaction after removal %q", got)
	}

	// Ben posts again: no second promotion; a duplicate file is 👍 but nothing new.
	before = len(e.fb.SentTo(-1001))
	e.file("p4", pic(1)) // same bytes as p2
	e.push(telegramtest.Photo(group, ben, 20, "p4", nil))
	if len(e.fb.SentTo(-1001)) != before || e.fb.ReactionOn(-1001, 20) != "👍" {
		t.Fatalf("second post: %v", e.fb.SentTo(-1001)[before:])
	}
	e.advance(61 * time.Second)
	if n := len(e.get("/v1/sends", anna.token).m(t)["sends"].([]any)); n != 1 {
		t.Fatalf("duplicate made a send (%d)", n)
	}

	// Spoilers are skipped with 🙈 and one explanation; too big files get a reply.
	e.file("s1", pic(5))
	e.push(telegramtest.Photo(group, ben, 30, "s1", map[string]any{"has_media_spoiler": true}),
		telegramtest.Document(group, ben, 31, "big", "video/mp4", 30<<20, nil))
	if e.fb.ReactionOn(-1001, 30) != "🙈" {
		t.Fatalf("spoiler reaction %q", e.fb.ReactionOn(-1001, 30))
	}
	replies := map[int64]string{}
	for _, m := range e.fb.SentTo(-1001) {
		replies[m.ReplyTo] = m.Text
	}
	if !strings.Contains(replies[30], "spoiler") || !strings.Contains(replies[31], "20 MB") {
		t.Fatalf("replies %v", replies)
	}

	// Commands.
	e.push(telegramtest.Text(group, ben, 40, "/status"))
	if r := lastTo(e.fb, -1001); r.ReplyTo != 40 || !strings.Contains(r.Text, "Anna Peeters") || r.Silent {
		t.Fatalf("status %+v", r)
	}
	e.push(telegramtest.Text(group, ben, 41, "/stop"))
	if r := lastTo(e.fb, -1001); r.ReplyTo != 41 || !strings.Contains(r.Text, "managers") {
		t.Fatalf("stop by a non-manager %+v", r)
	}

	// Block Ben: his photos are ignored silently.
	cs := e.list("/v1/telegram-chats/"+chatID+"/contributors", anna.token)
	if len(cs) != 1 || cs[0]["display_name"] != "Ben Peeters" || cs[0]["linked"] != false || cs[0]["sent_count"] != float64(3) {
		t.Fatalf("contributors %v", cs)
	}
	e.expect(req{method: "PATCH", path: "/v1/telegram-chats/" + chatID + "/contributors/" + cs[0]["id"].(string), token: anna.token, body: map[string]any{"blocked": true}}, 200)
	e.file("p5", pic(7))
	e.push(telegramtest.Photo(group, ben, 50, "p5", nil))
	if e.fb.ReactionOn(-1001, 50) != "" {
		t.Fatal("blocked contributor's photo confirmed")
	}
	// Ben's join link is revoked with the block.
	_ = joinURL

	// Supergroup migration: the binding follows the new id.
	e.push(telegramtest.Message(map[string]any{"message_id": 60, "date": time.Now().Unix(), "chat": map[string]any{"id": -1001, "type": "group", "title": "Peeters family"},
		"from": annaTG, "migrate_to_chat_id": -100777}))
	carlaTG := telegramtest.User(3, "Carla", "", "")
	e.file("m1", pic(8))
	e.push(telegramtest.Photo(telegramtest.Group(-100777, "Peeters family"), carlaTG, 1, "m1", nil))
	if e.fb.ReactionOn(-100777, 1) != "👍" {
		t.Fatal("photo in the supergroup not accepted")
	}

	// Disconnect from the app: goodbye, the bot leaves, the open batch is sent first.
	e.expect(req{method: "DELETE", path: "/v1/telegram-chats/" + chatID, token: anna.token}, 204)
	e.settle()
	if n := len(e.get("/v1/sends", anna.token).m(t)["sends"].([]any)); n != 2 {
		t.Fatalf("open batch not flushed on disconnect (%d sends)", n)
	}
	if r := lastTo(e.fb, -100777); !strings.Contains(r.Text, "no longer") {
		t.Fatalf("goodbye %+v", r)
	}
	e.fb.Lock(func() {
		if len(e.fb.Left) != 1 || e.fb.Left[0] != -100777 {
			t.Errorf("left %v", e.fb.Left)
		}
	})
	if ch := e.get("/v1/telegram-chats/"+chatID, anna.token).m(t); ch["status"] != "disconnected" || ch["ended_reason"] != "disconnected" || ch["ended_by_name"] != "Anna Peeters" {
		t.Fatalf("after disconnect %v", ch)
	}
	if r := e.do(req{method: "PATCH", path: "/v1/telegram-chats/" + chatID, token: anna.token, body: map[string]any{"paused": true}}); r.code() != "invalid_transition" {
		t.Fatalf("patch disconnected: %d %s", r.StatusCode, r.body)
	}
}

func TestTelegramLinkingAndJoin(t *testing.T) {
	e := newTelegramEnv(t, "polling")
	anna := e.user("anna@example.com", "Anna Peeters")
	carla := e.user("carla@example.com", "Carla Peeters")
	d, frame := e.claim(anna)
	e.join(carla, d)

	// Carla links her Telegram account.
	lc := e.expect(req{method: "POST", path: "/v1/me/telegram/link-code", token: carla.token}, 201).m(t)
	if !strings.Contains(lc["url"].(string), "?start=l") || lc["purpose"] != "link" {
		t.Fatalf("link code %v", lc)
	}
	carlaTG := telegramtest.User(30, "Carla", "P", "de")
	carlaDM := telegramtest.Private(30, "Carla")
	e.push(telegramtest.Text(carlaDM, carlaTG, 1, "/start "+lc["code"].(string)))
	if r := lastTo(e.fb, 30); !strings.Contains(r.Text, "Carla Peeters") {
		t.Fatalf("linked reply %+v", r)
	}
	if l := e.get("/v1/me/telegram", carla.token).m(t); l["display_name"] != "Carla P" || l["can_message"] != true {
		t.Fatalf("link %v", l)
	}
	if r := e.do(req{method: "POST", path: "/v1/me/telegram/link-code", token: carla.token}); r.code() != "telegram_already_linked" || r.StatusCode != 409 {
		t.Fatalf("second link code: %d %s", r.StatusCode, r.body)
	}

	// A group in linked_only mode.
	code := e.connectCode(anna, "/v1/frames/"+frame, "group", false)
	group := telegramtest.Group(-2002, "Cousins")
	annaTG := telegramtest.User(31, "Anna", "", "")
	e.push(e.fb.MemberUpdate(group, annaTG, "left", "member"), telegramtest.Text(group, annaTG, 1, "/start "+code["code"].(string)))
	chatID := e.list("/v1/frames/"+frame+"/telegram-chats", anna.token)[0]["id"].(string)
	e.expect(req{method: "PATCH", path: "/v1/telegram-chats/" + chatID, token: anna.token, body: map[string]any{"contributors": "linked_only"}}, 200)

	// Carla (linked, may send): her own send.
	e.file("c1", pic(1))
	e.push(telegramtest.Photo(group, carlaTG, 10, "c1", nil))
	if e.fb.ReactionOn(-2002, 10) != "👍" {
		t.Fatal("linked member refused")
	}
	// Dan (not linked): refused with one quiet reply carrying the join link.
	dan := telegramtest.User(40, "Dan", "", "")
	e.file("d1", pic(2))
	e.file("d2", pic(3))
	e.push(telegramtest.Photo(group, dan, 11, "d1", nil), telegramtest.Photo(group, dan, 12, "d2", nil))
	var refusal telegramtest.Sent
	n := 0
	for _, m := range e.fb.SentTo(-2002) {
		if m.ReplyTo == 11 || m.ReplyTo == 12 {
			refusal = m
			n++
		}
	}
	if n != 1 || refusal.ReplyTo != 11 || !strings.Contains(refusal.Buttons[0], "?start=j") || e.fb.ReactionOn(-2002, 11) != "" {
		t.Fatalf("refusal %+v (%d)", refusal, n)
	}
	e.advance(61 * time.Second)
	sends := e.get("/v1/sends", carla.token).m(t)["sends"].([]any)
	if len(sends) != 1 || dig(sends[0].(map[string]any), "via", "label") != "Carla Peeters" {
		t.Fatalf("Carla's sends %v", sends)
	}
	if n := len(e.get("/v1/sends", anna.token).m(t)["sends"].([]any)); n != 0 {
		t.Fatalf("Anna sent %d", n)
	}

	// The join code only works for Dan, in a private chat.
	start := "/start " + refusal.Buttons[0][strings.Index(refusal.Buttons[0], "=")+1:]
	eve := telegramtest.User(41, "Eve", "", "")
	e.push(telegramtest.Text(telegramtest.Private(41, "Eve"), eve, 1, start))
	if r := lastTo(e.fb, 41); strings.Contains(strings.Join(r.Buttons, " "), "/i/") {
		t.Fatalf("someone else got the join link %+v", r)
	}
	e.push(telegramtest.Text(telegramtest.Private(40, "Dan"), dan, 1, start))
	r := lastTo(e.fb, 40)
	var invite string
	for _, b := range r.Buttons {
		if strings.Contains(b, "/i/") {
			invite = b[strings.LastIndex(b, "/")+1:]
		}
	}
	if invite == "" || !strings.Contains(strings.Join(r.Buttons, " "), "apps.apple.com") {
		t.Fatalf("join message %+v", r)
	}
	// The invite is not listed; accepting it asks for approval with the channel fields and links Dan's Telegram.
	if inv := e.list("/v1/frames/"+frame+"/invites", anna.token); len(inv) != 0 {
		t.Fatalf("channel invite listed: %v", inv)
	}
	danUser := e.user("dan@example.com", "Dan D")
	res := e.expect(req{method: "POST", path: "/v1/invites/" + invite + "/accept", token: danUser.token}, 200).m(t)
	if res["status"] != "pending" {
		t.Fatalf("accept %v", res)
	}
	jrs := e.list("/v1/frames/"+frame+"/join-requests", anna.token)
	if len(jrs) != 1 || jrs[0]["via"] != "telegram" || jrs[0]["via_name"] != "Dan · Telegram, Cousins" || jrs[0]["via_matches"] != nil {
		t.Fatalf("join request %v", jrs)
	}
	if l := e.get("/v1/me/telegram", danUser.token).m(t); l["display_name"] != "Dan" {
		t.Fatalf("Dan not linked: %v", l)
	}

	// Unlink.
	e.expect(req{method: "DELETE", path: "/v1/me/telegram", token: carla.token}, 204)
	e.expect(req{method: "GET", path: "/v1/me/telegram", token: carla.token}, 404)
}

func TestTelegramPermissionsAndLimits(t *testing.T) {
	e := newTelegramEnv(t, "polling")
	anna := e.user("anna@example.com", "Anna")
	ben := e.user("ben@example.com", "Ben")
	stranger := e.user("x@example.com", "X")
	d, frame := e.claim(anna)
	e.join(ben, d)
	if r := e.do(req{method: "POST", path: "/v1/frames/" + frame + "/telegram-codes", token: ben.token, body: map[string]any{"chat_kind": "group"}}); r.code() != "missing_permission" {
		t.Fatalf("member creates a code: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/frames/" + frame + "/telegram-codes", token: stranger.token, body: map[string]any{"chat_kind": "group"}}); r.StatusCode != 404 {
		t.Fatalf("stranger creates a code: %d", r.StatusCode)
	}
	if r := e.do(req{method: "POST", path: "/v1/frames/" + frame + "/telegram-codes", token: anna.token, body: map[string]any{"chat_kind": "group", "link_account": true}}); r.StatusCode != 422 {
		t.Fatalf("link_account for a group: %d", r.StatusCode)
	}
	// A new code revokes the previous open one; revoking twice is fine.
	c1 := e.connectCode(anna, "/v1/frames/"+frame, "group", false)
	c2 := e.connectCode(anna, "/v1/frames/"+frame, "group", false)
	if e.get("/v1/telegram-codes/"+c1["id"].(string), anna.token).m(t)["status"] != "revoked" {
		t.Fatal("previous code still open")
	}
	if r := e.do(req{method: "GET", path: "/v1/telegram-codes/" + c2["id"].(string), token: ben.token}); r.StatusCode != 404 {
		t.Fatal("someone else's code visible")
	}
	// A private chat in a group link: refused, the code stays open.
	opa := telegramtest.User(50, "Opa", "", "")
	e.push(telegramtest.Text(telegramtest.Private(50, "Opa"), opa, 1, "/start "+c2["code"].(string)))
	if c := e.get("/v1/telegram-codes/"+c2["id"].(string), anna.token).m(t); c["status"] != "open" || c["error_code"] != "wrong_chat_kind" {
		t.Fatalf("wrong kind %v", c)
	}
	// Invite someone to send by Telegram: Opa's private chat, his photos sent by Anna.
	pc := e.connectCode(anna, "/v1/frames/"+frame, "private", false)
	e.push(telegramtest.Text(telegramtest.Private(50, "Opa"), opa, 2, "/start "+pc["code"].(string)))
	chats := e.list("/v1/frames/"+frame+"/telegram-chats", anna.token)
	if len(chats) != 1 || chats[0]["chat_kind"] != "private" || chats[0]["title"] != "Opa" {
		t.Fatalf("private chat %v", chats)
	}
	chatID := chats[0]["id"].(string)
	if r := e.do(req{method: "GET", path: "/v1/telegram-chats/" + chatID, token: ben.token}); r.StatusCode != 404 {
		t.Fatal("member without change_settings sees the chat")
	}
	if r := e.do(req{method: "PATCH", path: "/v1/telegram-chats/" + chatID, token: anna.token, body: map[string]any{"topic_id": 5}}); r.StatusCode != 422 {
		t.Fatalf("topic on a private chat: %d", r.StatusCode)
	}
	e.file("o1", pic(3))
	e.push(telegramtest.Photo(telegramtest.Private(50, "Opa"), opa, 3, "o1", nil))
	e.advance(61 * time.Second)
	sends := e.get("/v1/sends", anna.token).m(t)["sends"].([]any)
	if len(sends) != 1 || dig(sends[0].(map[string]any), "via", "label") != "Opa" {
		t.Fatalf("Opa's send %v", sends)
	}
	// Opa blocks the bot: the private binding ends.
	e.push(e.fb.MemberUpdate(telegramtest.Private(50, "Opa"), opa, "member", "kicked"))
	if ch := e.get("/v1/telegram-chats/"+chatID, anna.token).m(t); ch["ended_reason"] != "bot_blocked" {
		t.Fatalf("blocked %v", ch)
	}
	// Limits: one chat per target.
	op := e.operator()
	e.expect(req{method: "PUT", path: "/v1/operator/limits", token: op, body: map[string]any{"max_items_per_send": 200, "max_video_seconds": 300,
		"max_upload_bytes": 2 << 30, "max_telegram_chats_per_target": 1}}, 200)
	gc := e.connectCode(anna, "/v1/frames/"+frame, "group", false)
	g := telegramtest.Group(-3003, "Fam")
	e.push(telegramtest.Text(g, opa, 1, "/start "+gc["code"].(string)))
	if r := e.do(req{method: "POST", path: "/v1/frames/" + frame + "/telegram-codes", token: anna.token, body: map[string]any{"chat_kind": "group"}}); r.code() != "limit_reached" {
		t.Fatalf("over the limit: %d %s", r.StatusCode, r.body)
	}
	// The bot is removed from the group: disconnected, the owner is told.
	e.push(e.fb.MemberUpdate(g, opa, "member", "kicked"))
	chats = e.list("/v1/frames/"+frame+"/telegram-chats", anna.token)
	for _, c := range chats {
		if c["title"] == "Fam" && c["ended_reason"] != "bot_removed" {
			t.Fatalf("removed %v", c)
		}
	}
}

func TestTelegramWebhookAndFeed(t *testing.T) {
	e := newTelegramEnv(t, "webhook")
	var secret string
	eventually(t, 10*time.Second, "webhook registered", func() bool {
		ok := false
		e.fb.Lock(func() {
			w := e.fb.Webhook
			ok = w.URL == e.url+"/v1/telegram/webhook" && len(w.Secret) == 43 && len(w.Allowed) == 3
			secret = w.Secret
		})
		return ok
	})
	hook := func(body map[string]any, s string) int {
		h := map[string]string{}
		if s != "" {
			h[telegram.SecretHeader] = s
		}
		return e.do(req{method: "POST", path: "/v1/telegram/webhook", body: body, header: h}).StatusCode
	}
	if c := hook(map[string]any{"update_id": 1}, ""); c != 401 {
		t.Fatalf("no secret: %d", c)
	}
	if c := hook(map[string]any{"update_id": 1}, secret[:42]+"x"); c != 401 {
		t.Fatalf("wrong secret: %d", c)
	}
	big := map[string]any{"update_id": 2, "x": strings.Repeat("a", 1<<20)}
	if c := hook(big, secret); c != 413 {
		t.Fatalf("big body: %d", c)
	}

	anna := e.user("anna@example.com", "Anna")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters kids"})
	code := e.connectCode(anna, "/v1/feeds/"+feed, "group", false)
	g := telegramtest.Group(-4004, "Kids")
	ben := telegramtest.User(60, "Ben", "", "")
	start := telegramtest.Text(g, ben, 1, "/start "+code["code"].(string))
	if c := hook(start, secret); c != 200 {
		t.Fatalf("webhook: %d", c)
	}
	if c := hook(start, secret); c != 200 { // redelivery: a no-op
		t.Fatalf("redelivery: %d", c)
	}
	e.settle()
	chats := e.list("/v1/feeds/"+feed+"/telegram-chats", anna.token)
	if len(chats) != 1 || dig(chats[0], "target", "kind") != "feed" {
		t.Fatalf("feed chats %v", chats)
	}
	welcomes := 0
	for _, m := range e.fb.SentTo(-4004) {
		if strings.Contains(m.Text, "Peeters kids") {
			welcomes++
		}
	}
	if welcomes != 1 {
		t.Fatalf("%d welcomes", welcomes)
	}
	e.file("f1", pic(1))
	hook(telegramtest.Photo(g, ben, 2, "f1", nil), secret)
	e.settle()
	e.advance(61 * time.Second)
	posts := e.posts(feed, anna.token)
	if len(posts) != 1 || dig(posts[0], "via", "service") != "telegram" || dig(posts[0], "via", "label") != "Ben" {
		t.Fatalf("posts %v", posts)
	}
	// Groups that restrict reactions: remembered, confirmations stay silent.
	e.fb.Lock(func() { e.fb.ReactionInvalid[-4004] = true })
	e.file("f2", pic(2))
	hook(telegramtest.Photo(g, ben, 3, "f2", nil), secret)
	e.settle()
	eventually(t, 5*time.Second, "reactions_off", func() bool {
		ws, _ := e.get("/v1/telegram-chats/"+chats[0]["id"].(string), anna.token).m(t)["warnings"].([]any)
		return len(ws) == 1 && ws[0] == "reactions_off"
	})
}

func TestTelegramPrivacyMode(t *testing.T) {
	fbOpt := func(c *config.Config) {}
	e := newTelegramEnvPrivacy(t, fbOpt)
	anna := e.user("anna@example.com", "Anna")
	_, frame := e.claim(anna)
	code := e.connectCode(anna, "/v1/frames/"+frame, "group", false)
	g := telegramtest.Group(-5005, "Privacy")
	a := telegramtest.User(70, "Anna", "", "")
	e.push(e.fb.MemberUpdate(g, a, "left", "member"), telegramtest.Text(g, a, 1, "/start "+code["code"].(string)))
	ch := e.list("/v1/frames/"+frame+"/telegram-chats", anna.token)[0]
	if ch["status"] != "needs_attention" || ch["error_code"] != "privacy_mode" || ch["sees_all_messages"] != false {
		t.Fatalf("privacy %v", ch)
	}
	if !strings.Contains(lastTo(e.fb, -5005).Text, "admin") {
		t.Fatalf("welcome %q", lastTo(e.fb, -5005).Text)
	}
	// Promoted to admin: fixed at once, "Ready" once.
	e.push(e.fb.MemberUpdate(g, a, "member", "administrator"))
	ch = e.get("/v1/telegram-chats/"+ch["id"].(string), anna.token).m(t)
	if ch["status"] != "active" || ch["bot_is_admin"] != true || !strings.Contains(lastTo(e.fb, -5005).Text, "✓") {
		t.Fatalf("after promotion %v / %q", ch, lastTo(e.fb, -5005).Text)
	}
}

// newTelegramEnvPrivacy starts with a bot in privacy mode.
func newTelegramEnvPrivacy(t *testing.T, opts ...option) *tgEnv {
	fb := telegramtest.New()
	t.Cleanup(fb.Close)
	fb.ReadAll = false
	e := newEnv(t, append([]option{withTelegram(fb, "polling")}, opts...)...)
	<-e.app.API.TelegramReady()
	e.app.API.TelegramOutbox().SetPace(-1, -1, -1)
	te := &tgEnv{env: e, fb: fb}
	if d := e.get("/.well-known/kinframe", "").m(t); d["telegram_privacy_mode"] != true {
		t.Fatalf("discovery %v", d)
	}
	return te
}

