package e2e

import (
	"errors"
	"fmt"
	"net/smtp"
	"net/textproto"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/i18n"
	"github.com/jeroentrappers/kinframe/server/internal/mailin"
	"github.com/jeroentrappers/kinframe/server/internal/mailin/mailintest"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
	"github.com/jeroentrappers/kinframe/server/internal/shop"
	"github.com/jeroentrappers/kinframe/server/internal/shop/smtptest"
)

// Email to frame (docs/spec/email-inbox.md): a real SMTP dialogue with the
// built-in receiver, the signed webhook, sender authentication against a fake
// DNS, holding and approving, replies and permissions.

const inboxDomain = "in.example.org"
const webhookSecret = "webhook-secret-0123456789"

type mailEnv struct {
	*env
	dns    *mailintest.DNS
	out    *smtptest.Server
	gmail  *mailintest.Domain // DKIM-signing domain with p=none
	strict *mailintest.Domain // p=reject
}

func newMailEnv(t *testing.T, opts ...option) *mailEnv {
	t.Helper()
	requireMedia(t)
	out := smtptest.Start(t)
	opts = append([]option{func(cfg *config.Config) {
		cfg.Email = config.EmailConfig{Enabled: true, Domain: inboxDomain, SMTPListen: "127.0.0.1:0", SMTPHostname: "mx." + inboxDomain,
			MaxSessions: 100, WebhookSecrets: []string{webhookSecret}, Replies: true, MaxRepliesPerHour: 500, HeldDays: 14}
		cfg.SMTP = &shop.SMTP{Host: out.Host, Port: out.Port, From: "kinframe <noreply@example.org>"}
		cfg.AppVersions = map[string]config.AppVersionPolicy{"ios": {StoreURL: "https://apps.apple.com/app/kinframe"}}
	}}, opts...)
	e := newEnv(t, opts...)
	dns := mailintest.NewDNS()
	e.app.API.SetEmailResolver(dns)
	eventually(t, 5*time.Second, "SMTP receiver", func() bool { return e.app.API.EmailSMTPAddr() != "" })
	return &mailEnv{env: e, dns: dns, out: out, gmail: mailintest.NewDomain(dns, "gmail.example", "none"),
		strict: mailintest.NewDomain(dns, "bank.example", "reject")}
}

// smtpSend runs one SMTP dialogue; it returns the server's error for the
// first refused step (nil when queued).
func (m *mailEnv) smtpSend(from string, to []string, raw []byte) error {
	c, err := smtp.Dial(m.app.API.EmailSMTPAddr())
	if err != nil {
		return err
	}
	defer c.Close()
	if err := c.Hello("mx.gmail.example"); err != nil {
		return err
	}
	if err := c.Mail(from); err != nil {
		return err
	}
	for _, r := range to {
		if err := c.Rcpt(r); err != nil {
			return err
		}
	}
	w, err := c.Data()
	if err != nil {
		return err
	}
	if _, err := w.Write(raw); err != nil {
		return err
	}
	if err := w.Close(); err != nil {
		return err
	}
	return c.Quit()
}

func smtpCode(err error) int {
	var te *textproto.Error
	if errors.As(err, &te) {
		return te.Code
	}
	return 0
}

func (m *mailEnv) photoMail(from, to, subject, msgID string, photos ...[]byte) []byte {
	ml := mailintest.Mail{From: from, To: to, Subject: subject, Text: "Hello!\n", MessageID: msgID}
	for i, p := range photos {
		ml.Parts = append(ml.Parts, mailintest.Part{ContentType: "image/jpeg", Filename: fmt.Sprintf("IMG_%d.jpg", i), Data: p})
	}
	return ml.Build()
}

func (m *mailEnv) createAddress(u *user, frame string, body map[string]any) map[string]any {
	m.t.Helper()
	return m.expect(req{method: "POST", path: "/v1/frames/" + frame + "/email-addresses", token: u.token, body: body}, 201).m(m.t)
}

// messages waits until the address's Recent mail has n entries and returns them, newest first.
func (m *mailEnv) messages(u *user, addr string, n int) []any {
	m.t.Helper()
	var items []any
	eventually(m.t, 20*time.Second, fmt.Sprintf("%d logged mails", n), func() bool {
		items = m.get("/v1/email-addresses/"+addr+"/messages", u.token).m(m.t)["items"].([]any)
		return len(items) >= n
	})
	return items
}

func (m *mailEnv) mailsTo(to string, n int) []smtptest.Mail {
	m.t.Helper()
	var got []smtptest.Mail
	eventually(m.t, 20*time.Second, fmt.Sprintf("%d replies to %s", n, to), func() bool {
		got = nil
		for _, ml := range m.out.Mails() {
			if ml.To == to {
				got = append(got, ml)
			}
		}
		return len(got) >= n
	})
	return got
}

func jpeg(seed int) []byte { return mediatest.JPEG(640+seed, 480, 0, "", false) }

func TestEmailInboxSMTP(t *testing.T) {
	m := newMailEnv(t)
	anna := m.user("anna@gmail.example", "Anna")
	_, frame := m.claim(anna)
	if d := m.get("/.well-known/kinframe", "").m(t); d["email_inbox"] != true || d["email_inbox_domain"] != inboxDomain {
		t.Fatalf("discovery: %v", d)
	}
	annaWS := m.dialWS("/v1/ws", anna.token)
	a := m.createAddress(anna, frame, map[string]any{"slug": "oma", "name": "Oma's frame"})
	addr, id := a["address"].(string), a["id"].(string)
	if !strings.HasPrefix(addr, "oma-") || !strings.HasSuffix(addr, "@"+inboxDomain) || a["is_owner"] != true || a["replies_available"] != true {
		t.Fatalf("address %v", a)
	}
	annaWS.await("email_address.updated", 5*time.Second)

	// The dialogue: no relaying, unknown addresses refused, guessing ends the session.
	if err := m.smtpSend("x@gmail.example", []string{"someone@gmail.example"}, nil); smtpCode(err) != 550 || !strings.Contains(err.Error(), "Relaying denied") {
		t.Fatalf("relay: %v", err)
	}
	if err := m.smtpSend("x@gmail.example", []string{"nobody-abcdef@" + inboxDomain}, nil); smtpCode(err) != 550 || !strings.Contains(err.Error(), "5.1.1") {
		t.Fatalf("unknown: %v", err)
	}
	c, _ := smtp.Dial(m.app.API.EmailSMTPAddr())
	_ = c.Hello("guesser.example")
	_ = c.Mail("x@guesser.example")
	var last error
	for i := 0; i < 5; i++ {
		last = c.Rcpt(fmt.Sprintf("guess%d-abcdef@%s", i, inboxDomain))
	}
	if smtpCode(last) != 421 {
		t.Fatalf("guessing: %v", last)
	}
	c.Close()
	// A DMARC p=reject failure is refused in the dialogue.
	spoof := m.gmail.Sign(m.photoMail("ceo@bank.example", addr, "Invoice", "<s1@x>", jpeg(1)))
	if err := m.smtpSend("x@gmail.example", []string{addr}, spoof); smtpCode(err) != 550 || !strings.Contains(err.Error(), "DMARC") {
		t.Fatalf("dmarc reject: %v", err)
	}

	// Anna mails from her verified account email: delivered as her, credited "Anna via email".
	raw := m.gmail.Sign(m.photoMail("Anna <anna@gmail.example>", addr, "Fwd: Re: pics from Sunday", "<a1@gmail.example>",
		jpeg(1), jpeg(2), mediatest.JPEG(120, 40, 0, "", false)))
	if err := m.smtpSend("anna@gmail.example", []string{addr}, raw); err != nil {
		t.Fatalf("send: %v", err)
	}
	msgs := m.messages(anna, id, 1)
	first := msgs[0].(map[string]any)
	if first["outcome"] != "delivered" || first["items"] != float64(2) || dig(first, "skipped", "too_small") != float64(1) || first["authenticated"] != true {
		t.Fatalf("logged %v", first)
	}
	var lib []map[string]any
	eventually(t, 30*time.Second, "mailed photos on the frame", func() bool { lib = m.library(anna, frame, ""); return len(lib) == 2 })
	for _, p := range lib {
		if dig(p, "item", "via", "service") != "email" || dig(p, "item", "via", "label") != "Anna" || dig(p, "item", "caption") != "pics from Sunday" ||
			dig(p, "item", "sender_name") != "Anna" {
			t.Fatalf("placement %v", p)
		}
	}
	sends := m.get("/v1/sends", anna.token).m(t)["sends"].([]any)
	if len(sends) != 1 || dig(sends[0].(map[string]any), "via", "address_id") != id {
		t.Fatalf("sends %v", sends)
	}
	reply := m.mailsTo("anna@gmail.example", 1)[0]
	// The frame's language (nl) is the reply's.
	if !strings.Contains(reply.Text, i18n.T("nl", "email.reply.delivered_many", map[string]string{"count": "2", "name": "Oma's frame"})) || reply.Header.Get("Auto-Submitted") != "auto-replied" ||
		reply.Header.Get("In-Reply-To") != "<a1@gmail.example>" || reply.Subject != "Re: pics from Sunday" || !strings.Contains(reply.Text, i18n.T("nl", "email.app.open", nil)) {
		t.Fatalf("reply %+v\n%s", reply.Header, reply.Text)
	}
	// The same mail again (a client retrying after a lost 250) is a duplicate.
	if err := m.smtpSend("anna@gmail.example", []string{addr}, raw); err != nil {
		t.Fatal(err)
	}
	if o := m.messages(anna, id, 2)[0].(map[string]any)["outcome"]; o != "duplicate" {
		t.Fatalf("duplicate: %v", o)
	}

	// Rita is unknown: her mail is held, she is told, Anna is notified.
	ritaMail := m.gmail.Sign(m.photoMail("Rita <rita@gmail.example>", addr, "Garden party", "<r1@gmail.example>", jpeg(3)))
	if err := m.smtpSend("rita@gmail.example", []string{"Oma-" + strings.TrimPrefix(strings.Split(addr, "@")[0], "oma-") + "+x@" + inboxDomain}, ritaMail); err != nil {
		t.Fatalf("rita: %v (case and +tag are ignored)", err)
	}
	pend := annaWS.await("email_sender.pending", 20*time.Second)
	if dig(pend, "pending", "email") != "rita@gmail.example" {
		t.Fatalf("pending event %v", pend)
	}
	if held := m.mailsTo("rita@gmail.example", 1)[0]; !strings.Contains(held.Text, i18n.T("nl", "email.reply.held", map[string]string{"name": "Oma's frame"})) ||
		!strings.Contains(held.Text, i18n.T("nl", "email.app.heading", nil)) ||
		strings.Contains(held.Text, "/i/") {
		t.Fatalf("held notice:\n%s", held.Text)
	}
	pending := m.list("/v1/email-addresses/"+id+"/pending-senders", anna.token)
	if len(pending) != 1 || pending[0]["authenticated"] != true || len(pending[0]["messages"].([]any)) != 1 {
		t.Fatalf("pending %v", pending)
	}
	heldID := pending[0]["messages"].([]any)[0].(map[string]any)["id"].(string)
	h := m.get("/v1/email-addresses/"+id+"/held-messages/"+heldID, anna.token).m(t)
	if h["reason"] != "unknown_sender" || h["caption"] != "Garden party" || len(h["media"].([]any)) != 1 || dig(h, "auth", "dkim") != "pass" {
		t.Fatalf("held %v", h)
	}
	eventually(t, 30*time.Second, "held preview", func() bool {
		h := m.get("/v1/email-addresses/"+id+"/held-messages/"+heldID, anna.token).m(t)
		return dig(h["media"].([]any)[0].(map[string]any), "thumb_url") != nil
	})
	// Approve: delivered (credited Rita, sent by Anna), and Rita gets a join link.
	sender := m.expect(req{method: "POST", path: "/v1/email-addresses/" + id + "/pending-senders/" + pending[0]["id"].(string) + "/approve",
		token: anna.token, body: map[string]any{}}, 200).m(t)
	if sender["state"] != "allowed" || sender["origin"] != "approved" {
		t.Fatalf("approved %v", sender)
	}
	if r := m.do(req{method: "POST", path: "/v1/email-addresses/" + id + "/pending-senders/" + pending[0]["id"].(string) + "/approve", token: anna.token}); r.StatusCode != 409 {
		t.Fatalf("approve twice: %d", r.StatusCode)
	}
	eventually(t, 30*time.Second, "approved photo on the frame", func() bool { lib = m.library(anna, frame, ""); return len(lib) == 3 })
	var rita map[string]any
	for _, p := range lib {
		if dig(p, "item", "via", "label") == "Rita" {
			rita = p
		}
	}
	if rita == nil || dig(rita, "item", "sender_name") != "Anna" {
		t.Fatalf("rita's photo %v", lib)
	}
	conf := m.mailsTo("rita@gmail.example", 2)[1]
	if !strings.Contains(conf.Text, i18n.T("nl", "email.reply.delivered_photo", map[string]string{"name": "Oma's frame"})) ||
		!strings.Contains(conf.Text, m.url+"/i/") || strings.Contains(conf.Text, i18n.T("nl", "email.app.heading", nil)) {
		t.Fatalf("confirmation:\n%s", conf.Text)
	}
	tok := conf.Text[strings.Index(conf.Text, "/i/")+3:]
	tok = strings.Fields(tok)[0]
	// The link always needs approval; the request says it came by email and matches.
	ritaUser := m.user("rita@gmail.example", "Rita")
	if r := m.expect(req{method: "POST", path: "/v1/invites/" + tok + "/accept", token: ritaUser.token}, 200).m(t); r["status"] != "pending" {
		t.Fatalf("accept %v", r)
	}
	jrs := m.list("/v1/frames/"+frame+"/join-requests", anna.token)
	if len(jrs) != 1 || jrs[0]["via"] != "email" || jrs[0]["via_matches"] != true || jrs[0]["via_name"] != "Rita · rita@gmail.example" {
		t.Fatalf("join requests %v", jrs)
	}
	if invs := m.list("/v1/frames/"+frame+"/invites", anna.token); len(invs) != 0 {
		t.Fatalf("channel invite listed: %v", invs)
	}

	// Unauthenticated mail claiming to be Anna: held as unverified, delivered by hand without a reply.
	forged := m.photoMail("Anna <anna@gmail.example>", addr, "Hi", "<f1@forged>", jpeg(4))
	if err := m.smtpSend("anna@gmail.example", []string{addr}, forged); err != nil {
		t.Fatal(err)
	}
	var unv map[string]any
	eventually(t, 20*time.Second, "unverified held mail", func() bool {
		for _, p := range m.list("/v1/email-addresses/"+id+"/pending-senders", anna.token) {
			for _, mm := range p["messages"].([]any) {
				if mm.(map[string]any)["reason"] == "unverified_sender" {
					unv = mm.(map[string]any)
				}
			}
		}
		return unv != nil
	})
	if unv["authenticated"] != false {
		t.Fatalf("unverified %v", unv)
	}
	before := len(m.mailsTo("anna@gmail.example", 1))
	d := m.expect(req{method: "POST", path: "/v1/email-addresses/" + id + "/held-messages/" + unv["id"].(string) + "/deliver", token: anna.token}, 200).m(t)
	if d["outcome"] != "delivered" || d["items"] != float64(1) {
		t.Fatalf("deliver one %v", d)
	}
	eventually(t, 30*time.Second, "hand-delivered photo", func() bool { return len(m.library(anna, frame, "")) == 4 })
	time.Sleep(300 * time.Millisecond)
	if n := len(m.mailsTo("anna@gmail.example", 1)); n != before {
		t.Fatalf("unverified mail was answered (%d replies)", n)
	}

	// No usable media: nothing held, a short explanation.
	links := mailintest.Mail{From: "rita@gmail.example", To: addr, Subject: "photos", Text: "https://drive.google.com/x\n", MessageID: "<r2@gmail.example>"}
	if err := m.smtpSend("rita@gmail.example", []string{addr}, m.gmail.Sign(links.Build())); err != nil {
		t.Fatal(err)
	}
	if nm := m.mailsTo("rita@gmail.example", 3)[2]; !strings.Contains(nm.Text, i18n.T("nl", "email.reply.no_media", nil)) {
		t.Fatalf("no media reply:\n%s", nm.Text)
	}

	// Pause, regenerate.
	m.expect(req{method: "PATCH", path: "/v1/email-addresses/" + id, token: anna.token, body: map[string]any{"paused": true}}, 200)
	if err := m.smtpSend("rita@gmail.example", []string{addr}, ritaMail); smtpCode(err) != 550 || !strings.Contains(err.Error(), "paused") {
		t.Fatalf("paused: %v", err)
	}
	m.expect(req{method: "PATCH", path: "/v1/email-addresses/" + id, token: anna.token, body: map[string]any{"paused": false}}, 200)
	regen := m.expect(req{method: "POST", path: "/v1/email-addresses/" + id + "/regenerate", token: anna.token}, 200).m(t)
	if regen["address"] == addr || regen["regenerated_at"] == nil || !strings.HasPrefix(regen["address"].(string), "oma-") {
		t.Fatalf("regenerated %v", regen)
	}
	if err := m.smtpSend("rita@gmail.example", []string{addr}, ritaMail); smtpCode(err) != 550 {
		t.Fatalf("old address after regenerate: %v", err)
	}
	if senders := m.list("/v1/email-addresses/"+id+"/senders", anna.token); len(senders) != 1 || senders[0]["email"] != "rita@gmail.example" {
		t.Fatalf("senders kept: %v", senders)
	}
}

// webhookPost signs and posts a raw message to the webhook.
func (m *mailEnv) webhookPost(secret, to, from, clientIP string, raw []byte) *resp {
	m.t.Helper()
	sr := mailin.SignedRequest{Timestamp: strconv.FormatInt(time.Now().Unix(), 10), EnvelopeTo: to, EnvelopeFrom: from, ClientIP: clientIP,
		BodySHA256: mailin.BodyHash(raw)}
	h := map[string]string{"Content-Type": "message/rfc822", "X-Kinframe-Timestamp": sr.Timestamp, "X-Kinframe-Signature": mailin.Sign(secret, sr),
		"X-Kinframe-Envelope-To": to, "X-Kinframe-Envelope-From": from}
	if clientIP != "" {
		h["X-Kinframe-Client-Ip"] = clientIP
	}
	return m.do(req{method: "POST", path: "/v1/inbound/email", raw: raw, header: h})
}

func TestEmailInboxWebhookFeedAndModeration(t *testing.T) {
	m := newMailEnv(t)
	ben := m.user("ben@gmail.example", "Ben")
	poster := m.user("piet@gmail.example", "Piet")
	carla := m.user("carla@example.net", "Carla")
	_, frame := m.claim(ben)
	feed := m.expect(req{method: "POST", path: "/v1/feeds", token: ben.token, body: map[string]any{"name": "Peeters kids"}}, 201).m(t)["id"].(string)
	m.joinFeed(ben, poster, feed)

	// Permissions: posters can't see feed addresses; outsiders don't see the address at all.
	if r := m.do(req{method: "GET", path: "/v1/feeds/" + feed + "/email-addresses", token: poster.token}); r.StatusCode != 403 || r.code() != "missing_permission" {
		t.Fatalf("poster: %d %s", r.StatusCode, r.body)
	}
	fa := m.expect(req{method: "POST", path: "/v1/feeds/" + feed + "/email-addresses", token: ben.token, body: map[string]any{}}, 201).m(t)
	if !strings.HasPrefix(fa["address"].(string), "peeters-") || fa["name"] != "Peeters kids" || dig(fa, "target", "kind") != "feed" {
		t.Fatalf("feed address %v", fa)
	}
	if r := m.do(req{method: "GET", path: "/v1/email-addresses/" + fa["id"].(string), token: carla.token}); r.StatusCode != 404 {
		t.Fatalf("outsider: %d", r.StatusCode)
	}

	// Webhook: bad signatures, replays and unknown recipients.
	raw := m.gmail.Sign(m.photoMail("Piet <piet@gmail.example>", fa["address"].(string), "Zomer", "<p1@gmail.example>", jpeg(1), jpeg(2)))
	if r := m.webhookPost("wrong", fa["address"].(string), "piet@gmail.example", "", raw); r.StatusCode != 401 {
		t.Fatalf("bad signature: %d", r.StatusCode)
	}
	res := m.webhookPost(webhookSecret, fa["address"].(string)+", nobody-abcdef@"+inboxDomain, "piet@gmail.example", "", raw)
	if res.StatusCode != 202 {
		t.Fatalf("webhook: %d %s", res.StatusCode, res.body)
	}
	rc := res.m(t)["recipients"].([]any)
	if rc[0].(map[string]any)["outcome"] != "accepted" || rc[1].(map[string]any)["outcome"] != "unknown_recipient" {
		t.Fatalf("outcomes %v", rc)
	}
	// A feed member's mail posts to the feed as them.
	var posts []any
	eventually(t, 30*time.Second, "feed post", func() bool {
		posts = m.get("/v1/feeds/"+feed+"/posts", ben.token).m(t)["posts"].([]any)
		return len(posts) == 1
	})
	p := posts[0].(map[string]any)
	if dig(p, "via", "service") != "email" || dig(p, "via", "label") != "Piet" || len(p["items"].([]any)) != 2 {
		t.Fatalf("post %v", p)
	}
	// The recipient pre-check, signed over the JSON body.
	body := []byte(`{"recipients":["` + fa["address"].(string) + `","x-abcdef@` + inboxDomain + `"]}`)
	sr := mailin.SignedRequest{Timestamp: strconv.FormatInt(time.Now().Unix(), 10), BodySHA256: mailin.BodyHash(body)}
	chk := m.expect(req{method: "POST", path: "/v1/inbound/email:check", raw: body, header: map[string]string{"Content-Type": "application/json",
		"X-Kinframe-Timestamp": sr.Timestamp, "X-Kinframe-Signature": mailin.Sign(webhookSecret, sr)}}, 200).m(t)["recipients"].([]any)
	if chk[0].(map[string]any)["outcome"] != "accepted" || chk[1].(map[string]any)["outcome"] != "unknown_recipient" {
		t.Fatalf("check %v", chk)
	}
	// DMARC p=reject: dropped, recorded.
	spoof := m.gmail.Sign(m.photoMail("ceo@bank.example", fa["address"].(string), "x", "<x@x>", jpeg(5)))
	if r := m.webhookPost(webhookSecret, fa["address"].(string), "x@gmail.example", "", spoof).m(t); dig(r["recipients"].([]any)[0].(map[string]any), "outcome") != "rejected_dmarc" {
		t.Fatalf("dmarc %v", r)
	}

	// Moderation: on a moderated frame mail the owner sends for an allowed non-account sender always waits.
	m.expect(req{method: "PATCH", path: "/v1/frames/" + frame, token: ben.token, body: map[string]any{"moderation": true}}, 200)
	a := m.createAddress(ben, frame, map[string]any{"slug": "oma"})
	m.expect(req{method: "POST", path: "/v1/email-addresses/" + a["id"].(string) + "/senders", token: ben.token,
		body: map[string]any{"email": "Opa@Gmail.example", "display_name": "Grandpa"}}, 201)
	if r := m.do(req{method: "POST", path: "/v1/email-addresses/" + a["id"].(string) + "/senders", token: ben.token,
		body: map[string]any{"email": "opa@gmail.example"}}); r.StatusCode != 409 || r.code() != "duplicate_sender" {
		t.Fatalf("duplicate sender: %d %s", r.StatusCode, r.body)
	}
	opa := m.gmail.Sign(m.photoMail("opa@gmail.example", a["address"].(string), "", "<o1@gmail.example>", jpeg(6)))
	if r := m.webhookPost(webhookSecret, a["address"].(string), "opa@gmail.example", "", opa); r.StatusCode != 202 {
		t.Fatalf("opa: %d", r.StatusCode)
	}
	var lib []map[string]any
	eventually(t, 30*time.Second, "grandpa's photo", func() bool { lib = m.library(ben, frame, ""); return len(lib) >= 1 })
	var got map[string]any
	for _, pl := range lib {
		if dig(pl, "item", "via", "label") == "Grandpa" {
			got = pl
		}
	}
	if got == nil || got["pending_moderation"] != true {
		t.Fatalf("moderation: %v", lib)
	}
	// Ben's own mail (an owner) skips the queue.
	own := m.gmail.Sign(m.photoMail("ben@gmail.example", a["address"].(string), "mine", "<b1@gmail.example>", jpeg(7)))
	m.webhookPost(webhookSecret, a["address"].(string), "ben@gmail.example", "", own)
	eventually(t, 30*time.Second, "ben's photo", func() bool {
		for _, pl := range m.library(ben, frame, "") {
			if dig(pl, "item", "via", "label") == "Ben" {
				return pl["pending_moderation"] == false
			}
		}
		return false
	})

	// Without a secret the webhook doesn't exist.
	off := newEnv(t)
	if r := off.do(req{method: "POST", path: "/v1/inbound/email", raw: raw}); r.StatusCode != 404 {
		t.Fatalf("webhook without secret: %d", r.StatusCode)
	}
	if d := off.get("/.well-known/kinframe", "").m(t); d["email_inbox"] != false {
		t.Fatalf("discovery off: %v", d["email_inbox"])
	}
}

func TestEmailInboxSendersAndRetention(t *testing.T) {
	m := newMailEnv(t, func(cfg *config.Config) { cfg.DefaultLimits.MaxEmailMessageBytes = 1 << 20 })
	anna := m.user("anna@gmail.example", "Anna")
	dirk := m.user("dirk@gmail.example", "Dirk")
	_, frame := m.claim(anna)
	// Dirk joins without change_settings: he may mail (send), not manage.
	inv := m.expect(req{method: "POST", path: "/v1/frames/" + frame + "/invites", token: anna.token, body: map[string]any{}}, 201).m(t)
	m.expect(req{method: "POST", path: "/v1/invites/" + inv["token"].(string) + "/accept", token: dirk.token}, 200)
	if jrs := m.list("/v1/frames/"+frame+"/join-requests", anna.token); len(jrs) == 1 {
		m.expect(req{method: "POST", path: "/v1/frames/" + frame + "/join-requests/" + jrs[0]["id"].(string) + "/approve", token: anna.token}, 204)
	}
	if r := m.do(req{method: "GET", path: "/v1/frames/" + frame + "/email-addresses", token: dirk.token}); r.StatusCode != 403 {
		t.Fatalf("member list: %d", r.StatusCode)
	}
	a := m.createAddress(anna, frame, map[string]any{"slug": "oma", "send_confirmations": false})
	id, addr := a["id"].(string), a["address"].(string)
	if r := m.do(req{method: "PATCH", path: "/v1/email-addresses/" + id, token: dirk.token, body: map[string]any{"paused": true}}); r.StatusCode != 404 {
		t.Fatalf("member patch: %d", r.StatusCode)
	}
	// Too big: refused while streaming (552), as is a declared SIZE.
	big := m.gmail.Sign(m.photoMail("dirk@gmail.example", addr, "big", "<big@gmail.example>", make([]byte, 2<<20)))
	if err := m.smtpSend("dirk@gmail.example", []string{addr}, big); smtpCode(err) != 552 {
		t.Fatalf("too big: %v", err)
	}
	// Members' emails are never listed, but they are allowed.
	raw := m.gmail.Sign(m.photoMail("dirk@gmail.example", addr, "From Dirk", "<d1@gmail.example>", jpeg(1)))
	if err := m.smtpSend("dirk@gmail.example", []string{addr}, raw); err != nil {
		t.Fatal(err)
	}
	if o := m.messages(anna, id, 1)[0].(map[string]any)["outcome"]; o != "delivered" {
		t.Fatalf("member mail: %v", o)
	}
	if s := m.list("/v1/email-addresses/"+id+"/senders", anna.token); len(s) != 0 {
		t.Fatalf("members listed: %v", s)
	}
	// send_confirmations off: no delivered reply.
	time.Sleep(300 * time.Millisecond)
	for _, ml := range m.out.Mails() {
		if ml.To == "dirk@gmail.example" {
			t.Fatalf("confirmation despite send_confirmations off: %s", ml.Text)
		}
	}
	// Blocked senders are dropped silently.
	m.expect(req{method: "POST", path: "/v1/email-addresses/" + id + "/senders", token: anna.token, body: map[string]any{"email": "spam@gmail.example", "blocked": true}}, 201)
	spam := m.gmail.Sign(m.photoMail("spam@gmail.example", addr, "buy", "<sp1@gmail.example>", jpeg(2)))
	if err := m.smtpSend("spam@gmail.example", []string{addr}, spam); err != nil {
		t.Fatal(err)
	}
	if o := m.messages(anna, id, 2)[0].(map[string]any)["outcome"]; o != "blocked" {
		t.Fatalf("blocked: %v", o)
	}
	// Reject with block: held mail goes, nobody tells the sender.
	rita := m.gmail.Sign(m.photoMail("rita@gmail.example", addr, "hi", "<r1@gmail.example>", jpeg(3)))
	if err := m.smtpSend("rita@gmail.example", []string{addr}, rita); err != nil {
		t.Fatal(err)
	}
	var pending []map[string]any
	eventually(t, 20*time.Second, "pending sender", func() bool {
		pending = m.list("/v1/email-addresses/"+id+"/pending-senders", anna.token)
		return len(pending) == 1
	})
	m.expect(req{method: "POST", path: "/v1/email-addresses/" + id + "/pending-senders/" + pending[0]["id"].(string) + "/reject",
		token: anna.token, body: map[string]any{"block": true}}, 204)
	if s := m.list("/v1/email-addresses/"+id+"/senders", anna.token); len(s) != 2 {
		t.Fatalf("blocked after reject: %v", s)
	}
	// Retention: expired held mail is deleted and its pending sender goes.
	bob := m.gmail.Sign(m.photoMail("bob@gmail.example", addr, "hi", "<bo1@gmail.example>", jpeg(4)))
	if err := m.smtpSend("bob@gmail.example", []string{addr}, bob); err != nil {
		t.Fatal(err)
	}
	eventually(t, 20*time.Second, "bob pending", func() bool { return len(m.list("/v1/email-addresses/"+id+"/pending-senders", anna.token)) == 1 })
	if _, err := m.app.Pool.Exec(t.Context(), `UPDATE email_held_messages SET expires_at = now() - interval '1 minute'`); err != nil {
		t.Fatal(err)
	}
	m.app.Maintain(t.Context())
	if p := m.list("/v1/email-addresses/"+id+"/pending-senders", anna.token); len(p) != 0 {
		t.Fatalf("expired held mail kept: %v", p)
	}
	// Delete: the address is gone and never comes back.
	m.expect(req{method: "DELETE", path: "/v1/email-addresses/" + id, token: anna.token}, 204)
	if err := m.smtpSend("bob@gmail.example", []string{addr}, bob); smtpCode(err) != 550 {
		t.Fatalf("deleted address: %v", err)
	}
}
