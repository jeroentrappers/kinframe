package e2e

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/config"
)

// Chats (docs/spec/family-social.md, README §5.19) over real HTTP.

func (e *env) conversation(u *user, id string) map[string]any {
	e.t.Helper()
	return e.get("/v1/conversations/"+id, u.token).m(e.t)
}

func (e *env) direct(a, b *user, status int) string {
	e.t.Helper()
	r := e.expect(req{method: "POST", path: "/v1/conversations", token: a.token, body: map[string]any{"kind": "direct", "member_ids": []string{b.id}}}, status)
	if status != 200 && status != 201 {
		return r.code()
	}
	return r.m(e.t)["id"].(string)
}

func (e *env) group(a *user, name string, others ...*user) string {
	e.t.Helper()
	ids := []string{}
	for _, o := range others {
		ids = append(ids, o.id)
	}
	return e.expect(req{method: "POST", path: "/v1/conversations", token: a.token,
		body: map[string]any{"kind": "group", "name": name, "member_ids": ids}}, 201).m(e.t)["id"].(string)
}

func (e *env) say(u *user, conv, text string) map[string]any {
	e.t.Helper()
	return e.expect(req{method: "POST", path: "/v1/conversations/" + conv + "/messages", token: u.token,
		body: map[string]any{"body": text}}, 201).m(e.t)
}

func (e *env) messages(u *user, conv, query string) []map[string]any {
	e.t.Helper()
	var out []map[string]any
	for _, m := range e.get("/v1/conversations/"+conv+"/messages"+query, u.token).m(e.t)["messages"].([]any) {
		out = append(out, m.(map[string]any))
	}
	return out
}

func bodies(ms []map[string]any) []string {
	out := []string{}
	for _, m := range ms {
		if b, ok := m["body"].(string); ok {
			out = append(out, b)
		} else if s, ok := m["system"].(map[string]any); ok {
			out = append(out, "<"+s["type"].(string)+">")
		} else {
			out = append(out, "<deleted>")
		}
	}
	return out
}

func memberOf(conv map[string]any, userID string) map[string]any {
	for _, m := range conv["members"].([]any) {
		mm := m.(map[string]any)
		if dig(mm, "person", "user_id") == userID {
			return mm
		}
	}
	return nil
}

func (e *env) sql(q string, args ...any) {
	e.t.Helper()
	if _, err := e.app.Pool.Exec(context.Background(), q, args...); err != nil {
		e.t.Fatalf("%s: %v", q, err)
	}
}

func (e *env) scalar(q string, args ...any) any {
	e.t.Helper()
	var v any
	if err := e.app.Pool.QueryRow(context.Background(), q, args...).Scan(&v); err != nil {
		e.t.Fatalf("%s: %v", q, err)
	}
	return v
}

// Contacts: only people who share a family or a frame; direct chats once per pair; blocks look like "not a
// contact".
func TestChatWhoCanMessageWhom(t *testing.T) {
	e := newEnv(t)
	anna, ben, carl, dora := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carl@x.org", "Carl"), e.user("dora@x.org", "Dora")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	d, _ := e.claim(anna)
	e.join(dora, d) // Dora shares only a frame with Anna

	disc := e.get("/.well-known/kinframe", "").m(t)
	if disc["chats"] != true || dig(disc, "limits", "max_message_chars") != float64(4000) || dig(disc, "limits", "max_group_members") != float64(100) {
		t.Fatalf("discovery: chats %v limits %v", disc["chats"], disc["limits"])
	}

	var names []string
	for _, p := range e.list("/v1/contacts", anna.token) {
		names = append(names, dig(p, "person", "display_name").(string))
	}
	if strings.Join(names, ",") != "Ben,Dora" {
		t.Fatalf("Anna's contacts: %v", names)
	}
	if n := len(e.list("/v1/contacts", carl.token)); n != 0 {
		t.Fatalf("Carl has %d contacts", n)
	}
	// Ben and Dora share nothing.
	if code := e.direct(ben, dora, 403); code != "cannot_message" {
		t.Fatalf("Ben -> Dora: %s", code)
	}
	if code := e.direct(anna, carl, 403); code != "cannot_message" {
		t.Fatalf("Anna -> Carl: %s", code)
	}
	if r := e.do(req{method: "GET", path: "/v1/people/" + carl.id, token: anna.token}); r.StatusCode != 404 {
		t.Fatalf("stranger's profile: %d", r.StatusCode)
	}
	person := e.get("/v1/people/"+ben.id, anna.token).m(t)
	if len(person["feeds"].([]any)) != 1 || person["blocked"] != false || person["direct_conversation_id"] != nil {
		t.Fatalf("Ben's profile for Anna: %v", person)
	}

	dm := e.direct(anna, ben, 201)
	if again := e.direct(anna, ben, 200); again != dm {
		t.Fatalf("second direct chat %s, want %s", again, dm)
	}
	if other := e.direct(ben, anna, 200); other != dm {
		t.Fatalf("Ben's side %s, want %s", other, dm)
	}
	if e.get("/v1/people/"+ben.id, anna.token).m(t)["direct_conversation_id"] != dm {
		t.Fatal("profile lacks the direct chat")
	}
	c := e.conversation(anna, dm)
	if c["kind"] != "direct" || c["name"] != "Ben" || dig(c, "other", "user_id") != ben.id || c["read_only"] != false {
		t.Fatalf("direct chat for Anna: %v", c)
	}
	if r := e.do(req{method: "GET", path: "/v1/conversations/" + dm, token: carl.token}); r.StatusCode != 404 {
		t.Fatalf("outsider reads the chat: %d", r.StatusCode)
	}
	e.say(anna, dm, "Hello Ben")
	e.say(ben, dm, "Hi Anna")

	// Ben blocks Anna: she can't reach him, and can't tell why.
	e.expect(req{method: "PUT", path: "/v1/blocks/" + anna.id, token: ben.token}, 204)
	e.expect(req{method: "PUT", path: "/v1/blocks/" + anna.id, token: ben.token}, 204) // idempotent
	if bl := e.list("/v1/blocks", ben.token); len(bl) != 1 || dig(bl[0], "person", "user_id") != anna.id {
		t.Fatalf("blocks: %v", bl)
	}
	r := e.do(req{method: "POST", path: "/v1/conversations/" + dm + "/messages", token: anna.token, body: map[string]any{"body": "Ben?"}})
	if r.StatusCode != 403 || r.code() != "cannot_message" {
		t.Fatalf("message to a blocker: %d %s", r.StatusCode, r.body)
	}
	if c := e.conversation(anna, dm); c["read_only"] != true {
		t.Fatalf("blocked direct chat not read-only: %v", c["read_only"])
	}
	if code := e.direct(anna, ben, 403); code != "cannot_message" {
		t.Fatalf("new direct chat to a blocker: %s", code)
	}
	// Ben no longer sees Anna's messages; Anna sees both.
	if got := bodies(e.messages(ben, dm, "")); strings.Join(got, "|") != "Hi Anna" {
		t.Fatalf("Ben sees %v", got)
	}
	if got := bodies(e.messages(anna, dm, "")); strings.Join(got, "|") != "Hello Ben|Hi Anna" {
		t.Fatalf("Anna sees %v", got)
	}
	// Anna can't add Ben to a group either.
	if r := e.do(req{method: "POST", path: "/v1/conversations", token: anna.token, body: map[string]any{"kind": "group", "name": "X", "member_ids": []string{ben.id, dora.id}}}); r.StatusCode != 403 || r.code() != "cannot_message" {
		t.Fatalf("group with a blocker: %d %s", r.StatusCode, r.body)
	}
	e.expect(req{method: "DELETE", path: "/v1/blocks/" + anna.id, token: ben.token}, 204)
	e.say(anna, dm, "Back again")
	if got := bodies(e.messages(ben, dm, "")); strings.Join(got, "|") != "Hello Ben|Hi Anna|Back again" {
		t.Fatalf("after unblock Ben sees %v", got)
	}
	if code := e.do(req{method: "PUT", path: "/v1/blocks/" + ben.id, token: ben.token}).code(); code != "validation_failed" {
		t.Fatalf("self block: %s", code)
	}
}

// Groups: admins rename and remove, anyone adds their own contacts and leaves, the last admin leaving promotes
// the longest-standing member, and new members only see messages from when they were added.
func TestChatGroups(t *testing.T) {
	e := newEnv(t)
	anna, ben, carl, dora := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carl@x.org", "Carl"), e.user("dora@x.org", "Dora")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	e.joinFeed(anna, carl, feed)
	dBen, _ := e.claim(ben)
	e.join(dora, dBen) // Dora is Ben's contact only

	if r := e.do(req{method: "POST", path: "/v1/conversations", token: anna.token, body: map[string]any{"kind": "group", "name": "Solo", "member_ids": []string{ben.id}}}); r.StatusCode != 422 {
		t.Fatalf("group of two: %d", r.StatusCode)
	}
	if r := e.do(req{method: "POST", path: "/v1/conversations", token: anna.token, body: map[string]any{"kind": "group", "name": "  ", "member_ids": []string{ben.id, carl.id}}}); r.StatusCode != 422 {
		t.Fatalf("group without a name: %d", r.StatusCode)
	}
	ws := e.dialWS("/v1/ws?access_token="+ben.token, "")
	g := e.group(anna, "Cousins weekend", ben, carl)
	ev := ws.await("conversation.updated", 5*time.Second)
	if dig(ev, "conversation", "id") != g || dig(ev, "conversation", "name") != "Cousins weekend" {
		t.Fatalf("conversation.updated: %v", ev)
	}
	c := e.conversation(anna, g)
	if c["my_role"] != "admin" || c["member_count"] != float64(3) || c["read_receipts"] != true {
		t.Fatalf("group for Anna: %v", c)
	}
	e.say(anna, g, "before Dora")

	// Members can't rename or remove; admins can.
	if r := e.do(req{method: "PATCH", path: "/v1/conversations/" + g, token: ben.token, body: map[string]any{"name": "Mine"}}); r.StatusCode != 403 {
		t.Fatalf("member renames: %d", r.StatusCode)
	}
	if r := e.do(req{method: "DELETE", path: "/v1/conversations/" + g + "/members/" + carl.id, token: ben.token}); r.StatusCode != 403 {
		t.Fatalf("member removes someone: %d", r.StatusCode)
	}
	if c := e.expect(req{method: "PATCH", path: "/v1/conversations/" + g, token: anna.token, body: map[string]any{"name": "Cousins"}}, 200).m(t); c["name"] != "Cousins" {
		t.Fatalf("rename: %v", c["name"])
	}
	// Anyone adds their own contacts, not other people's.
	if r := e.do(req{method: "POST", path: "/v1/conversations/" + g + "/members", token: anna.token, body: map[string]any{"user_ids": []string{dora.id}}}); r.StatusCode != 403 || r.code() != "cannot_message" {
		t.Fatalf("Anna adds a stranger: %d %s", r.StatusCode, r.body)
	}
	c = e.expect(req{method: "POST", path: "/v1/conversations/" + g + "/members", token: ben.token, body: map[string]any{"user_ids": []string{dora.id}}}, 200).m(t)
	if c["member_count"] != float64(4) {
		t.Fatalf("after adding Dora: %v", c["member_count"])
	}
	e.say(ben, g, "welcome Dora")
	if got := bodies(e.messages(dora, g, "")); strings.Join(got, "|") != "<member_joined>|welcome Dora" {
		t.Fatalf("Dora sees %v", got)
	}
	if got := bodies(e.messages(anna, g, "")); strings.Join(got, "|") != "before Dora|<member_joined>|welcome Dora" {
		t.Fatalf("Anna sees %v", got)
	}

	// Roles: the group always keeps an admin.
	if r := e.do(req{method: "PATCH", path: "/v1/conversations/" + g + "/members/" + anna.id, token: anna.token, body: map[string]any{"role": "member"}}); r.StatusCode != 409 {
		t.Fatalf("demote the last admin: %d", r.StatusCode)
	}
	e.expect(req{method: "PATCH", path: "/v1/conversations/" + g + "/members/" + carl.id, token: anna.token, body: map[string]any{"role": "admin"}}, 200)
	// Admins remove people; the removed get conversation.removed.
	e.expect(req{method: "DELETE", path: "/v1/conversations/" + g + "/members/" + ben.id, token: carl.token}, 204)
	ws.await("conversation.removed", 5*time.Second)
	if r := e.do(req{method: "GET", path: "/v1/conversations/" + g, token: ben.token}); r.StatusCode != 404 {
		t.Fatalf("removed member still reads: %d", r.StatusCode)
	}
	// The admins leave; the longest-standing member (Dora, the only one left) becomes admin.
	e.expect(req{method: "DELETE", path: "/v1/conversations/" + g + "/members/" + anna.id, token: anna.token}, 204)
	e.expect(req{method: "DELETE", path: "/v1/conversations/" + g + "/members/" + carl.id, token: carl.token}, 204)
	c = e.conversation(dora, g)
	if c["my_role"] != "admin" || c["member_count"] != float64(1) {
		t.Fatalf("Dora after everyone left: role %v count %v", c["my_role"], c["member_count"])
	}
	got := bodies(e.messages(dora, g, ""))
	if strings.Count(strings.Join(got, "|"), "<member_left>") != 3 {
		t.Fatalf("member_left messages: %v", got)
	}

	// Direct chats can't be left; group operations on them are refused.
	dm := e.direct(anna, ben, 201)
	if r := e.do(req{method: "DELETE", path: "/v1/conversations/" + dm + "/members/" + anna.id, token: anna.token}); r.StatusCode != 409 {
		t.Fatalf("leave a direct chat: %d", r.StatusCode)
	}
	if r := e.do(req{method: "PATCH", path: "/v1/conversations/" + dm, token: anna.token, body: map[string]any{"name": "x"}}); r.StatusCode != 409 {
		t.Fatalf("rename a direct chat: %d", r.StatusCode)
	}
}

// Family chats follow the feed's members through the database triggers.
func TestChatFamily(t *testing.T) {
	e := newEnv(t)
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	fam := e.get("/v1/feeds/"+feed, anna.token).m(t)["conversation_id"].(string)
	if mine := e.list("/v1/feeds", anna.token); len(mine) != 1 || mine[0]["conversation_id"] != fam {
		t.Fatalf("my feeds: %v", mine)
	}
	if r := e.do(req{method: "GET", path: "/v1/conversations/" + fam, token: ben.token}); r.StatusCode != 404 {
		t.Fatalf("non-member reads the family chat: %d", r.StatusCode)
	}
	e.joinFeed(anna, ben, feed)
	c := e.conversation(ben, fam)
	if c["kind"] != "family" || c["name"] != "Peeters" || c["member_count"] != float64(2) || c["read_receipts"] != false || c["feed_id"] != feed {
		t.Fatalf("family chat: %v", c)
	}
	e.say(anna, fam, "Dinner on Sunday?")
	e.say(ben, fam, "Yes!")
	list := e.list("/v1/conversations", anna.token)
	if len(list) != 1 || list[0]["id"] != fam || dig(list[0], "last_message", "body") != "Yes!" || list[0]["unread_count"] != float64(1) {
		t.Fatalf("Anna's chats: %v", list)
	}
	// It can't be left, renamed or given a photo on its own.
	if r := e.do(req{method: "DELETE", path: "/v1/conversations/" + fam + "/members/" + ben.id, token: ben.token}); r.StatusCode != 409 {
		t.Fatalf("leave the family chat: %d", r.StatusCode)
	}
	if r := e.do(req{method: "PATCH", path: "/v1/conversations/" + fam, token: anna.token, body: map[string]any{"name": "x"}}); r.StatusCode != 409 {
		t.Fatalf("rename the family chat: %d", r.StatusCode)
	}
	// Feed owners and admins moderate the family chat.
	m := e.say(ben, fam, "rude")
	e.expect(req{method: "DELETE", path: "/v1/conversations/" + fam + "/messages/" + m["id"].(string), token: anna.token}, 204)
	got := e.get("/v1/conversations/"+fam+"/messages/"+m["id"].(string), ben.token).m(t)
	if got["deleted"] != true || got["removed_by_admin"] != true || got["body"] != nil {
		t.Fatalf("moderated message: %v", got)
	}
	// Leaving the family leaves the chat.
	e.expect(req{method: "DELETE", path: "/v1/feeds/" + feed + "/members/" + ben.id, token: anna.token}, 204)
	if r := e.do(req{method: "GET", path: "/v1/conversations/" + fam, token: ben.token}); r.StatusCode != 404 {
		t.Fatalf("former member reads the family chat: %d", r.StatusCode)
	}
	// A deleted feed archives its chat (read-only, listed only as archived) until the feed's removal job
	// deletes it with the feed.
	e.sql(`UPDATE feeds SET deleted_at = now() WHERE id = $1`, feed)
	if n := len(e.list("/v1/conversations", anna.token)); n != 0 {
		t.Fatalf("archived family chat listed: %d", n)
	}
	arch := e.list("/v1/conversations?archived=true", anna.token)
	if len(arch) != 1 || arch[0]["read_only"] != true || arch[0]["archived"] != true {
		t.Fatalf("archived chats: %v", arch)
	}
	r := e.do(req{method: "POST", path: "/v1/conversations/" + fam + "/messages", token: anna.token, body: map[string]any{"body": "hello?"}})
	if r.StatusCode != 409 {
		t.Fatalf("message in an archived chat: %d %s", r.StatusCode, r.body)
	}
}

// Messages: edits within 24 hours by the author, deletes as tombstones, replies, mentions, paging and
// client_id retries.
func TestChatMessages(t *testing.T) {
	e := newEnv(t)
	anna, ben, carl := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carl@x.org", "Carl")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	e.joinFeed(anna, carl, feed)
	g := e.group(anna, "Trio", ben, carl)

	if r := e.do(req{method: "POST", path: "/v1/conversations/" + g + "/messages", token: anna.token, body: map[string]any{}}); r.StatusCode != 422 {
		t.Fatalf("empty message: %d", r.StatusCode)
	}
	if r := e.do(req{method: "POST", path: "/v1/conversations/" + g + "/messages", token: anna.token, body: map[string]any{"body": "   "}}); r.StatusCode != 422 {
		t.Fatalf("blank message: %d", r.StatusCode)
	}
	first := e.expect(req{method: "POST", path: "/v1/conversations/" + g + "/messages", token: anna.token,
		body: map[string]any{"body": "Hi all", "client_id": "c-1"}}, 201).m(t)
	retry := e.expect(req{method: "POST", path: "/v1/conversations/" + g + "/messages", token: anna.token,
		body: map[string]any{"body": "Hi all", "client_id": "c-1"}}, 201).m(t)
	if retry["id"] != first["id"] || first["client_id"] != "c-1" {
		t.Fatalf("client_id retry: %v vs %v", retry["id"], first["id"])
	}
	if bm := e.get("/v1/conversations/"+g+"/messages/"+first["id"].(string), ben.token).m(t); bm["client_id"] != nil {
		t.Fatalf("client_id shown to others: %v", bm["client_id"])
	}

	// Mentions: UTF-16 offsets into the trimmed body, members only.
	body := "  👋 @Ben look"
	mention := map[string]any{"user_id": ben.id, "start": 5, "length": 4} // "@Ben" after "  👋 " (2 + 2 + 1 units)
	m := e.expect(req{method: "POST", path: "/v1/conversations/" + g + "/messages", token: carl.token,
		body: map[string]any{"body": body, "mentions": []any{mention}}}, 201).m(t)
	if m["body"] != "👋 @Ben look" || dig(m["mentions"].([]any)[0].(map[string]any), "start") != float64(3) {
		t.Fatalf("mention offsets: %v %v", m["body"], m["mentions"])
	}
	if r := e.do(req{method: "POST", path: "/v1/conversations/" + g + "/messages", token: carl.token,
		body: map[string]any{"body": "hi", "mentions": []any{map[string]any{"user_id": ben.id, "start": 1, "length": 5}}}}); r.StatusCode != 422 {
		t.Fatalf("mention outside the body: %d", r.StatusCode)
	}
	stranger := e.user("zed@x.org", "Zed")
	if r := e.do(req{method: "POST", path: "/v1/conversations/" + g + "/messages", token: carl.token,
		body: map[string]any{"body": "@Zed", "mentions": []any{map[string]any{"user_id": stranger.id, "start": 0, "length": 4}}}}); r.StatusCode != 422 {
		t.Fatalf("mention of a non-member: %d", r.StatusCode)
	}
	bc := e.conversation(ben, g)
	if bc["unread_count"] != float64(2) || bc["unread_mentions"] != float64(1) {
		t.Fatalf("Ben's unread: %v / %v", bc["unread_count"], bc["unread_mentions"])
	}
	if b := e.get("/v1/me/badge", ben.token).m(t); b["conversations"] != float64(1) || b["total"] != float64(1) {
		t.Fatalf("Ben's badge: %v", b)
	}

	// Replies quote the message.
	reply := e.expect(req{method: "POST", path: "/v1/conversations/" + g + "/messages", token: ben.token,
		body: map[string]any{"body": "Hi Anna", "reply_to_id": first["id"]}}, 201).m(t)
	if dig(reply, "reply_to", "id") != first["id"] || dig(reply, "reply_to", "excerpt") != "Hi all" || dig(reply, "reply_to", "deleted") != false {
		t.Fatalf("reply: %v", reply["reply_to"])
	}

	// Edits: the author only, within 24 hours.
	mid := first["id"].(string)
	if r := e.do(req{method: "PATCH", path: "/v1/conversations/" + g + "/messages/" + mid, token: ben.token, body: map[string]any{"body": "hacked"}}); r.StatusCode != 403 {
		t.Fatalf("edit by someone else: %d", r.StatusCode)
	}
	ed := e.expect(req{method: "PATCH", path: "/v1/conversations/" + g + "/messages/" + mid, token: anna.token, body: map[string]any{"body": "Hi everyone"}}, 200).m(t)
	if ed["body"] != "Hi everyone" || ed["edited_at"] == nil {
		t.Fatalf("edited: %v", ed)
	}
	e.sql(`UPDATE messages SET created_at = now() - interval '25 hours' WHERE id = $1`, mid)
	e.sql(`UPDATE conversation_members SET visible_from = now() - interval '2 days' WHERE conversation_id = $1`, g)
	if r := e.do(req{method: "PATCH", path: "/v1/conversations/" + g + "/messages/" + mid, token: anna.token, body: map[string]any{"body": "late"}}); r.StatusCode != 409 {
		t.Fatalf("edit after 24 h: %d", r.StatusCode)
	}

	// Deletes: the author any time (tombstone); others not, unless they moderate (group admins).
	if r := e.do(req{method: "DELETE", path: "/v1/conversations/" + g + "/messages/" + reply["id"].(string), token: carl.token}); r.StatusCode != 403 {
		t.Fatalf("delete by a member: %d", r.StatusCode)
	}
	e.expect(req{method: "DELETE", path: "/v1/conversations/" + g + "/messages/" + mid, token: anna.token}, 204)
	tomb := e.get("/v1/conversations/"+g+"/messages/"+mid, carl.token).m(t)
	if tomb["deleted"] != true || tomb["removed_by_admin"] != false || tomb["body"] != nil {
		t.Fatalf("tombstone: %v", tomb)
	}
	if q := e.get("/v1/conversations/"+g+"/messages/"+reply["id"].(string), carl.token).m(t); dig(q, "reply_to", "deleted") != true {
		t.Fatalf("quote of a deleted message: %v", q["reply_to"])
	}
	e.expect(req{method: "DELETE", path: "/v1/conversations/" + g + "/messages/" + reply["id"].(string), token: anna.token}, 204)
	if rm := e.get("/v1/conversations/"+g+"/messages/"+reply["id"].(string), carl.token).m(t); rm["removed_by_admin"] != true {
		t.Fatalf("admin removal: %v", rm)
	}

	// Paging: newest page, then before and after cursors; oldest first within pages.
	for i := 0; i < 7; i++ {
		e.say(ben, g, fmt.Sprintf("n%d", i))
	}
	page := e.get("/v1/conversations/"+g+"/messages?limit=3", anna.token).m(t)
	ms := page["messages"].([]any)
	if len(ms) != 3 || page["has_more_before"] != true || page["has_more_after"] != false || ms[2].(map[string]any)["body"] != "n6" {
		t.Fatalf("newest page: %v", page)
	}
	oldest := ms[0].(map[string]any)["id"].(string)
	prev := e.get("/v1/conversations/"+g+"/messages?limit=3&before="+oldest, anna.token).m(t)
	if p := prev["messages"].([]any); len(p) != 3 || p[2].(map[string]any)["body"] != "n3" || prev["has_more_after"] != true {
		t.Fatalf("before page: %v", prev)
	}
	next := e.get("/v1/conversations/"+g+"/messages?limit=2&after="+oldest, anna.token).m(t)
	if p := next["messages"].([]any); len(p) != 2 || p[0].(map[string]any)["body"] != "n5" || next["has_more_after"] != false || next["has_more_before"] != true {
		t.Fatalf("after page: %v", next)
	}
	if r := e.do(req{method: "GET", path: "/v1/conversations/" + g + "/messages?before=" + oldest + "&after=" + oldest, token: anna.token}); r.StatusCode != 422 {
		t.Fatalf("both cursors: %d", r.StatusCode)
	}

	// Reading: never backwards; the badge follows.
	last := ms[2].(map[string]any)["id"].(string)
	e.expect(req{method: "PUT", path: "/v1/conversations/" + g + "/read", token: ben.token, body: map[string]any{"message_id": last}}, 204)
	e.expect(req{method: "PUT", path: "/v1/conversations/" + g + "/read", token: ben.token, body: map[string]any{"message_id": oldest}}, 204)
	if c := e.conversation(ben, g); c["read_up_to"] != last || c["unread_count"] != float64(0) {
		t.Fatalf("read position: %v unread %v", c["read_up_to"], c["unread_count"])
	}
	if b := e.get("/v1/me/badge", ben.token).m(t); b["total"] != float64(0) {
		t.Fatalf("badge after reading: %v", b)
	}
	// Muting keeps unread counts but takes the chat off the badge.
	e.say(anna, g, "muted?")
	e.expect(req{method: "PATCH", path: "/v1/conversations/" + g + "/preferences", token: ben.token, body: map[string]any{"mute": "always"}}, 200)
	c := e.conversation(ben, g)
	if c["muted"] != true || c["muted_until"] != nil || c["unread_count"] != float64(1) {
		t.Fatalf("muted: %v %v %v", c["muted"], c["muted_until"], c["unread_count"])
	}
	if b := e.get("/v1/me/badge", ben.token).m(t); b["total"] != float64(0) {
		t.Fatalf("badge of a muted chat: %v", b)
	}
	if c := e.expect(req{method: "PATCH", path: "/v1/conversations/" + g + "/preferences", token: ben.token, body: map[string]any{"mute": "8h"}}, 200).m(t); c["muted_until"] == nil {
		t.Fatal("mute 8h has no end")
	}
	e.expect(req{method: "PATCH", path: "/v1/conversations/" + g + "/preferences", token: ben.token, body: map[string]any{"mute": "off", "archived": true}}, 200)
	listed := func() bool {
		for _, c := range e.list("/v1/conversations", ben.token) {
			if c["id"] == g {
				return true
			}
		}
		return false
	}
	if listed() {
		t.Fatal("archived chat listed")
	}
	if arch := e.list("/v1/conversations?archived=true", ben.token); len(arch) != 1 || arch[0]["id"] != g || arch[0]["read_only"] != false {
		t.Fatalf("archived list: %v", arch)
	}
	e.say(anna, g, "un-archive")
	if !listed() {
		t.Fatal("a new message doesn't un-archive")
	}
}

// Read receipts: direct chats and groups of at most 10 people, only between people who show them.
func TestChatReadReceipts(t *testing.T) {
	e := newEnv(t)
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	dm := e.direct(anna, ben, 201)
	m := e.say(anna, dm, "seen?")
	wsAnna := e.dialWS("/v1/ws?access_token="+anna.token, "")
	e.expect(req{method: "PUT", path: "/v1/conversations/" + dm + "/read", token: ben.token, body: map[string]any{"message_id": m["id"]}}, 204)
	ev := wsAnna.await("read.updated", 5*time.Second)
	if ev["user_id"] != ben.id || ev["last_read_message_id"] != m["id"] {
		t.Fatalf("read.updated: %v", ev)
	}
	if mb := memberOf(e.conversation(anna, dm), ben.id); mb["last_read_message_id"] != m["id"] {
		t.Fatalf("Ben's position for Anna: %v", mb)
	}
	// Ben turns read receipts off: Anna no longer sees his position, and he sees nobody's.
	if u := e.expect(req{method: "PATCH", path: "/v1/me", token: ben.token, body: map[string]any{"show_read_receipts": false}}, 200).m(t); u["show_read_receipts"] != false {
		t.Fatalf("me: %v", u)
	}
	if mb := memberOf(e.conversation(anna, dm), ben.id); mb["last_read_message_id"] != nil {
		t.Fatalf("hidden position shown: %v", mb)
	}
	e.expect(req{method: "PUT", path: "/v1/conversations/" + dm + "/read", token: anna.token, body: map[string]any{"message_id": m["id"]}}, 204)
	bc := e.conversation(ben, dm)
	if bc["read_receipts"] != false || memberOf(bc, anna.id)["last_read_message_id"] != nil {
		t.Fatalf("Ben sees receipts: %v", bc)
	}
	if e.get("/v1/me", ben.token).m(t)["show_read_receipts"] != false {
		t.Fatal("getMe lacks show_read_receipts")
	}

	// Groups over 10 people never show them.
	var others []*user
	for i := 0; i < 10; i++ {
		u := e.user(fmt.Sprintf("p%d@x.org", i), fmt.Sprintf("P%d", i))
		e.joinFeed(anna, u, feed)
		others = append(others, u)
	}
	big := e.group(anna, "Eleven", others...)
	if c := e.conversation(anna, big); c["read_receipts"] != false || c["member_count"] != float64(11) {
		t.Fatalf("big group: %v %v", c["read_receipts"], c["member_count"])
	}
	small := e.group(anna, "Three", others[0], others[1])
	if c := e.conversation(anna, small); c["read_receipts"] != true {
		t.Fatalf("small group: %v", c["read_receipts"])
	}
}

// Typing goes to the other connected members, at most every 3 s, not to people who blocked the typer.
func TestChatTyping(t *testing.T) {
	e := newEnv(t)
	anna, ben, carl := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carl@x.org", "Carl")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	e.joinFeed(anna, carl, feed)
	g := e.group(anna, "Trio", ben, carl)
	e.expect(req{method: "PUT", path: "/v1/blocks/" + anna.id, token: carl.token}, 204)
	wsBen := e.dialWS("/v1/ws?access_token="+ben.token, "")
	wsCarl := e.dialWS("/v1/ws?access_token="+carl.token, "")
	e.expect(req{method: "POST", path: "/v1/conversations/" + g + "/typing", token: anna.token, body: map[string]any{"activity": "recording"}}, 204)
	ev := wsBen.await("typing", 5*time.Second)
	if ev["user_id"] != anna.id || ev["activity"] != "recording" || ev["display_name"] != "Anna" || ev["expires_at"] == nil {
		t.Fatalf("typing: %v", ev)
	}
	e.expect(req{method: "POST", path: "/v1/conversations/" + g + "/typing", token: anna.token}, 204) // within 3 s: dropped
	e.say(ben, g, "marker")
	for {
		m := wsCarl.await("message.created", 5*time.Second)
		if dig(m, "message", "body") == "marker" {
			break
		}
	}
	select {
	case m := <-wsCarl.msgs:
		if m["type"] == "typing" {
			t.Fatalf("Carl got Anna's typing: %v", m)
		}
	default:
	}
	// Ben's websocket got no second typing event before his own message.
	for {
		select {
		case m := <-wsBen.msgs:
			if m["type"] == "typing" {
				t.Fatalf("second typing event within 3 s: %v", m)
			}
			continue
		default:
		}
		break
	}
}

// Message bodies and report excerpts are sealed at rest with KINFRAME_SECRETS_KEY, and plain without.
func TestChatEncryptionAtRest(t *testing.T) {
	for _, sealed := range []bool{false, true} {
		t.Run(fmt.Sprint("sealed=", sealed), func(t *testing.T) {
			var opts []option
			if sealed {
				opts = append(opts, func(c *config.Config) { c.SecretsKey = []byte(strings.Repeat("k", 32)) })
			}
			e := newEnv(t, opts...)
			anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
			feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
			e.joinFeed(anna, ben, feed)
			dm := e.direct(anna, ben, 201)
			m := e.say(anna, dm, "a secret recipe")
			plain := e.scalar(`SELECT body FROM messages WHERE id = $1`, m["id"])
			enc := e.scalar(`SELECT body_enc IS NOT NULL FROM messages WHERE id = $1`, m["id"])
			if sealed && (plain != nil || enc != true) {
				t.Fatalf("sealed: body %v, body_enc %v", plain, enc)
			}
			if !sealed && (plain != "a secret recipe" || enc != false) {
				t.Fatalf("plain: body %v, body_enc %v", plain, enc)
			}
			if got := e.get("/v1/conversations/"+dm+"/messages/"+m["id"].(string), ben.token).m(t)["body"]; got != "a secret recipe" {
				t.Fatalf("read back: %v", got)
			}
			ed := e.expect(req{method: "PATCH", path: "/v1/conversations/" + dm + "/messages/" + m["id"].(string), token: anna.token,
				body: map[string]any{"body": "an edited recipe"}}, 200).m(t)
			if ed["body"] != "an edited recipe" {
				t.Fatalf("edit read back: %v", ed["body"])
			}
			if sealed && e.scalar(`SELECT body IS NULL AND body_enc IS NOT NULL FROM messages WHERE id = $1`, m["id"]) != true {
				t.Fatal("edit stored in plain text")
			}
			e.expect(req{method: "POST", path: "/v1/reports", token: ben.token,
				body: map[string]any{"target_kind": "message", "target_id": m["id"], "reason": "unkind"}}, 201)
			exPlain := e.scalar(`SELECT excerpt FROM reports WHERE target_id = $1`, m["id"])
			if sealed != (exPlain == nil) {
				t.Fatalf("report excerpt sealed=%v: %v", sealed, exPlain)
			}
			op := e.operator()
			rs := e.list("/v1/operator/reports", op)
			if len(rs) != 1 || rs[0]["excerpt"] != "an edited recipe" {
				t.Fatalf("operator reports: %v", rs)
			}
		})
	}
}

// Reports: family content goes to the feed's admins, the rest to operators; resolve once, remove acts as a
// moderator delete.
func TestChatReports(t *testing.T) {
	e := newEnv(t, withUnifiedPush)
	anna, ben, carl := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carl@x.org", "Carl")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	e.joinFeed(anna, carl, feed)
	fam := e.get("/v1/feeds/"+feed, anna.token).m(t)["conversation_id"].(string)
	pushes := e.pushSink(t, anna)
	bad := e.say(carl, fam, "you are all wrong")
	pushes.next(t, "family chat message")

	r := e.expect(req{method: "POST", path: "/v1/reports", token: ben.token,
		body: map[string]any{"target_kind": "message", "target_id": bad["id"], "reason": "unkind", "note": "not nice"}}, 201).m(t)
	if r["feed_id"] != feed || r["mine"] != true || r["excerpt"] != nil || r["note"] != nil || r["author"] != nil {
		t.Fatalf("reporter's view: %v", r)
	}
	p := pushes.next(t, "report push")
	if p.Key != "report" || p.IDs["report_id"] != r["id"] || !strings.Contains(p.Body, "Peeters") {
		t.Fatalf("report push: %+v", p)
	}
	again := e.expect(req{method: "POST", path: "/v1/reports", token: ben.token,
		body: map[string]any{"target_kind": "message", "target_id": bad["id"], "reason": "spam"}}, 201).m(t)
	if again["id"] != r["id"] {
		t.Fatal("a second open report on the same target")
	}
	if rr := e.do(req{method: "GET", path: "/v1/feeds/" + feed + "/reports", token: ben.token}); rr.StatusCode != 403 {
		t.Fatalf("poster lists reports: %d", rr.StatusCode)
	}
	list := e.list("/v1/feeds/"+feed+"/reports", anna.token)
	if len(list) != 1 || list[0]["excerpt"] != "you are all wrong" || dig(list[0], "author", "user_id") != carl.id || list[0]["note"] != "not nice" || list[0]["mine"] != false {
		t.Fatalf("handler's view: %v", list)
	}
	// Not the operator's.
	if n := len(e.list("/v1/operator/reports", e.operator())); n != 0 {
		t.Fatalf("operator sees family reports: %d", n)
	}
	if rr := e.do(req{method: "GET", path: "/v1/operator/reports", token: anna.token}); rr.StatusCode != 403 {
		t.Fatalf("non-operator lists operator reports: %d", rr.StatusCode)
	}
	res := e.expect(req{method: "POST", path: "/v1/reports/" + r["id"].(string) + "/resolve", token: anna.token, body: map[string]any{"action": "remove"}}, 200).m(t)
	if res["status"] != "removed" || res["resolved_at"] == nil {
		t.Fatalf("resolved: %v", res)
	}
	if rr := e.do(req{method: "POST", path: "/v1/reports/" + r["id"].(string) + "/resolve", token: anna.token, body: map[string]any{"action": "dismiss"}}); rr.StatusCode != 409 || rr.code() != "already_decided" {
		t.Fatalf("second resolve: %d %s", rr.StatusCode, rr.body)
	}
	if m := e.get("/v1/conversations/"+fam+"/messages/"+bad["id"].(string), ben.token).m(t); m["deleted"] != true || m["removed_by_admin"] != true {
		t.Fatalf("reported message after remove: %v", m)
	}

	// Direct chats go to the operator, who must have signed in once to be known.
	op := e.operator()
	e.get("/v1/me/badge", op)
	dm := e.direct(ben, carl, 201)
	spam := e.say(carl, dm, "buy now")
	opr := e.expect(req{method: "POST", path: "/v1/reports", token: ben.token,
		body: map[string]any{"target_kind": "message", "target_id": spam["id"], "reason": "spam"}}, 201).m(t)
	if opr["feed_id"] != nil {
		t.Fatalf("direct chat report has a feed: %v", opr)
	}
	rs := e.list("/v1/operator/reports", op)
	if len(rs) != 1 || rs[0]["excerpt"] != "buy now" {
		t.Fatalf("operator reports: %v", rs)
	}
	if rr := e.do(req{method: "POST", path: "/v1/reports/" + opr["id"].(string) + "/resolve", token: anna.token, body: map[string]any{"action": "dismiss"}}); rr.StatusCode != 404 {
		t.Fatalf("family admin resolves an operator report: %d", rr.StatusCode)
	}
	e.expect(req{method: "POST", path: "/v1/reports/" + opr["id"].(string) + "/resolve", token: op, body: map[string]any{"action": "dismiss"}}, 200)
	if m := e.get("/v1/conversations/"+dm+"/messages/"+spam["id"].(string), ben.token).m(t); m["deleted"] != false {
		t.Fatal("dismiss removed the message")
	}
	if n := len(e.list("/v1/operator/reports?status=resolved", op)); n != 1 {
		t.Fatalf("resolved operator reports: %d", n)
	}
	// Only what the reporter can see.
	stranger := e.user("zed@x.org", "Zed")
	if rr := e.do(req{method: "POST", path: "/v1/reports", token: stranger.token,
		body: map[string]any{"target_kind": "message", "target_id": spam["id"], "reason": "spam"}}); rr.StatusCode != 404 {
		t.Fatalf("report of an unseen message: %d", rr.StatusCode)
	}
}

// Pushes for messages: per conversation tag, mention instead of message, muted chats silent, blocked authors
// don't reach the blocker.
func TestChatPushes(t *testing.T) {
	e := newEnv(t, withUnifiedPush)
	anna, ben, carl := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carl@x.org", "Carl")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	e.joinFeed(anna, carl, feed)
	pushes := e.pushSink(t, ben)
	g := e.group(anna, "Trio", ben, carl)
	p := pushes.next(t, "added to a group")
	if p.Key != "conversation_added" || p.IDs["conversation_id"] != g || !strings.Contains(p.Body, "Anna") {
		t.Fatalf("conversation_added: %+v", p)
	}
	e.say(anna, g, "Look at this")
	p = pushes.next(t, "message")
	if p.Key != "message" || p.Tag != "conversation:"+g || p.Title != "Trio" || p.Body != "Anna: Look at this" || p.IDs["conversation_id"] != g {
		t.Fatalf("message push: %+v", p)
	}
	e.expect(req{method: "POST", path: "/v1/conversations/" + g + "/messages", token: carl.token,
		body: map[string]any{"body": "@Ben hi", "mentions": []any{map[string]any{"user_id": ben.id, "start": 0, "length": 4}}}}, 201)
	p = pushes.next(t, "mention")
	if p.Key != "mention" || p.Tag != "" || !strings.Contains(p.Body, "Carl") {
		t.Fatalf("mention push: %+v", p)
	}
	e.expect(req{method: "PUT", path: "/v1/blocks/" + carl.id, token: ben.token}, 204)
	e.say(carl, g, "you blocked me")
	e.expect(req{method: "PATCH", path: "/v1/conversations/" + g + "/preferences", token: ben.token, body: map[string]any{"mute": "1h"}}, 200)
	e.say(anna, g, "muted now")
	pushes.none(t, 700*time.Millisecond)
	// The family's chat mute mutes the family chat.
	fam := e.get("/v1/feeds/"+feed, anna.token).m(t)["conversation_id"].(string)
	e.sql(`UPDATE feed_members SET mute = 'chat' WHERE feed_id = $1 AND user_id = $2`, feed, ben.id)
	e.say(anna, fam, "family muted")
	pushes.none(t, 700*time.Millisecond)
	e.sql(`UPDATE feed_members SET mute = 'none' WHERE feed_id = $1 AND user_id = $2`, feed, ben.id)
	e.say(anna, fam, "family again")
	if p = pushes.next(t, "family chat"); p.Title != "Peeters" {
		t.Fatalf("family chat push: %+v", p)
	}
}

// Shared posts: only posts the sender may see; members who can't see it get an unavailable card.
func TestChatSharedPosts(t *testing.T) {
	e := newEnv(t)
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	other := e.createFeed(anna, map[string]any{"name": "Janssens"})
	e.joinFeed(anna, ben, feed)
	dm := e.direct(anna, ben, 201)
	post := e.scalar(`INSERT INTO feed_posts (id, feed_id, poster_user_id) VALUES (kinframe_uuidv7(now()), $1, $2) RETURNING id::text`, other, anna.id).(string)
	if r := e.do(req{method: "POST", path: "/v1/conversations/" + dm + "/messages", token: anna.token, body: map[string]any{"shared_post_id": post}}); r.StatusCode != 422 {
		t.Fatalf("share without access (until social's postAccessFor): %d", r.StatusCode)
	}
	// With visibility from feed membership (what postAccessFor answers for whole-family posts).
	e.app.API.SetPostAccessForTest(func(ctx context.Context, userID, postID uuid.UUID) (bool, *uuid.UUID, uuid.UUID, error) {
		var feedID, poster uuid.UUID
		var member bool
		err := e.app.Pool.QueryRow(ctx, `SELECT p.feed_id, p.poster_user_id, EXISTS (SELECT 1 FROM feed_members m WHERE m.feed_id = p.feed_id AND m.user_id = $2)
			FROM feed_posts p WHERE p.id = $1 AND p.removed_at IS NULL`, postID, userID).Scan(&feedID, &poster, &member)
		if err != nil {
			return false, nil, uuid.Nil, nil
		}
		return member, &feedID, poster, nil
	})
	t.Cleanup(func() { e.app.API.SetPostAccessForTest(nil) })
	m := e.expect(req{method: "POST", path: "/v1/conversations/" + dm + "/messages", token: anna.token, body: map[string]any{"shared_post_id": post, "body": "look"}}, 201).m(t)
	if dig(m, "shared_post", "available") != true || dig(m, "shared_post", "feed_name") != "Janssens" || dig(m, "shared_post", "kind") != "feed" {
		t.Fatalf("Anna's card: %v", m["shared_post"])
	}
	bm := e.get("/v1/conversations/"+dm+"/messages/"+m["id"].(string), ben.token).m(t)
	if card := bm["shared_post"].(map[string]any); card["available"] != false || card["feed_name"] != nil {
		t.Fatalf("Ben's card: %v", card)
	}
}
