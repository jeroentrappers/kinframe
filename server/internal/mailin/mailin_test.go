package mailin_test

import (
	"bytes"
	"context"
	"encoding/base64"
	"net"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/mailin"
	"github.com/jeroentrappers/kinframe/server/internal/mailin/mailintest"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

func mail(from string) mailintest.Mail {
	return mailintest.Mail{From: from, To: "oma-k7q2m9@in.example.org", Subject: "Fwd: Re: pics from Sunday", Text: "Hi!\n",
		MessageID: "<m1@example.org>", Parts: []mailintest.Part{{ContentType: "image/jpeg", Filename: "a.jpg", Data: mediatest.JPEG(640, 480, 0, "", false)}}}
}

func auth(t *testing.T, dns *mailintest.DNS, in mailin.AuthInput, raw []byte) mailin.AuthResult {
	t.Helper()
	return mailin.Authenticate(context.Background(), dns, in, bytes.NewReader(raw))
}

func TestAuthentication(t *testing.T) {
	dns := mailintest.NewDNS()
	gmail := mailintest.NewDomain(dns, "gmail.example", "none")
	strict := mailintest.NewDomain(dns, "bank.example", "reject")
	_ = mailintest.NewDomain(dns, "nodmarc.example", "")

	// DKIM aligned with the From domain: authenticated, whatever the policy.
	r := auth(t, dns, mailin.AuthInput{}, gmail.Sign(mail("Rita <Rita@Gmail.Example>").Build()))
	if !r.Authenticated || r.DKIM != "pass" || *r.Aligned != "dkim" || r.From != "rita@gmail.example" || r.FromName != "Rita" {
		t.Fatalf("aligned dkim: %+v", r)
	}
	// A subdomain signature aligns relaxed with the organizational domain.
	sub := mailintest.NewDomain(dns, "mail.gmail.example", "")
	if r := auth(t, dns, mailin.AuthInput{}, sub.Sign(mail("rita@gmail.example").Build())); !r.Authenticated {
		t.Fatalf("relaxed alignment: %+v", r)
	}
	// Signed by another domain: not aligned, not authenticated.
	r = auth(t, dns, mailin.AuthInput{}, gmail.Sign(mail("ceo@bank.example").Build()))
	if r.Authenticated || !r.RejectDMARC || r.DMARC != "fail" || *r.DMARCPolicy != "reject" {
		t.Fatalf("spoofed p=reject: %+v", r)
	}
	_ = strict
	// No signature, no SPF, no DMARC record: unauthenticated (alignment still required).
	r = auth(t, dns, mailin.AuthInput{}, mail("x@nodmarc.example").Build())
	if r.Authenticated || r.RejectDMARC || r.DMARCPolicy != nil {
		t.Fatalf("no record: %+v", r)
	}
	// A tampered body breaks the signature.
	signed := gmail.Sign(mail("rita@gmail.example").Build())
	tampered := bytes.Replace(signed, []byte("Hi!"), []byte("Yo!"), 1)
	if r := auth(t, dns, mailin.AuthInput{}, tampered); r.Authenticated || r.DKIM != "fail" {
		t.Fatalf("tampered: %+v", r)
	}
	// SPF: the client IP is allowed by the MAIL FROM domain, which matches From.
	dns.Set("spf.example", "v=spf1 ip4:203.0.113.0/24 -all")
	r = auth(t, dns, mailin.AuthInput{ClientIP: net.ParseIP("203.0.113.7"), Helo: "mx.spf.example", MailFrom: "bounce@spf.example"},
		mail("jan@spf.example").Build())
	if !r.Authenticated || r.SPF != "pass" || *r.Aligned != "spf" {
		t.Fatalf("spf: %+v", r)
	}
	r = auth(t, dns, mailin.AuthInput{ClientIP: net.ParseIP("198.51.100.1"), MailFrom: "bounce@spf.example"}, mail("jan@spf.example").Build())
	if r.Authenticated || r.SPF != "fail" {
		t.Fatalf("spf fail: %+v", r)
	}
	// Two From addresses: never authenticated.
	two := gmail.Sign(mail("a@gmail.example, b@gmail.example").Build())
	if r := auth(t, dns, mailin.AuthInput{}, two); r.Authenticated || !r.FromInvalid {
		t.Fatalf("two from: %+v", r)
	}
	// A DNS timeout on the DKIM key decides the outcome: temperror.
	tmp := mailintest.NewDomain(dns, "flaky.example", "quarantine")
	dns.SetTemp("s1._domainkey.flaky.example", true)
	if r := auth(t, dns, mailin.AuthInput{}, tmp.Sign(mail("x@flaky.example").Build())); r.Authenticated || !r.TempError {
		t.Fatalf("temperror: %+v", r)
	}
	// Forged Authentication-Results headers are ignored...
	forged := append([]byte("Authentication-Results: mx.google.com; dmarc=pass header.from=nodmarc.example\r\n"), mail("x@nodmarc.example").Build()...)
	if r := auth(t, dns, mailin.AuthInput{}, forged); r.Authenticated {
		t.Fatal("forged Authentication-Results trusted")
	}
	// ...except the provider's own in the webhook trust mode (topmost with the configured id).
	trusted := append([]byte("Authentication-Results: provider.example; dkim=pass header.d=nodmarc.example; dmarc=pass header.from=nodmarc.example\r\n"+
		"Authentication-Results: attacker.example; dmarc=fail header.from=nodmarc.example\r\n"), mail("x@nodmarc.example").Build()...)
	if r := mailin.TrustedResults(bytes.NewReader(trusted), "provider.example"); !r.Authenticated || r.DKIM != "pass" {
		t.Fatalf("trusted: %+v", r)
	}
	if r := mailin.TrustedResults(bytes.NewReader(trusted), "other.example"); r.Authenticated {
		t.Fatal("untrusted authserv-id used")
	}
}

func TestCaptions(t *testing.T) {
	for subject, want := range map[string]string{
		"Fwd: Re: pics from Sunday":  "pics from Sunday",
		"RE[2]: AW: Tuinfeest":       "Tuinfeest",
		"[family] Fw: Zomer":         "Zomer",
		"IMG_1234.JPG":               "",
		"Photo":                      "",
		"Sent from my iPhone":        "",
		"  Re:  ":                    "",
		"Antw: Doorst: Verjaardag 🎂": "Verjaardag 🎂",
	} {
		if got := mailin.Caption(subject, ""); got != want {
			t.Errorf("Caption(%q) = %q, want %q", subject, got, want)
		}
	}
	body := "Look at these!\n\nSent from my iPhone\n\nOn Sun, Jan wrote:\n> old"
	if got := mailin.Caption("IMG_1.jpg", body); got != "Look at these!" {
		t.Errorf("body caption %q", got)
	}
	if got := mailin.Caption("", "Verzonden met mijn iPhone"); got != "" {
		t.Errorf("signature as caption: %q", got)
	}
	if got := mailin.Caption(strings.Repeat("x", 400), ""); len([]rune(got)) != 280 {
		t.Errorf("caption length %d", len(got))
	}
	if got := mailin.Label("Jan via Family List", "", "list@example.org"); got != "Jan" {
		t.Errorf("label %q", got)
	}
	if got := mailin.Label("", "", "jan.peeters@telenet.be"); got != "jan.peeters" {
		t.Errorf("label fallback %q", got)
	}
	if got := mailin.ReplySubject("Re: Fwd: Hello\r\nBcc: x@y"); strings.ContainsAny(got, "\r\n") || got != "Re: Hello Bcc: x@y" {
		t.Errorf("reply subject %q", got)
	}
}

func parse(t *testing.T, raw []byte) *mailin.Parsed {
	t.Helper()
	p, err := mailin.Parse(bytes.NewReader(raw), mailin.Limits{ScratchDir: t.TempDir(), MaxFileBytes: 1 << 20, MaxDecoded: 4 << 20})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.Cleanup)
	return p
}

func TestParseMIME(t *testing.T) {
	jpg := mediatest.JPEG(640, 480, 0, "", false)
	m := mail("Rita <rita@gmail.example>")
	m.Parts = append(m.Parts,
		mailintest.Part{ContentType: "image/png", Filename: "logo.png", Inline: true, Data: jpg},
		mailintest.Part{ContentType: "application/zip", Filename: "photos.zip", Data: []byte("PK\x03\x04")},
		mailintest.Part{ContentType: "application/octet-stream", Filename: "IMG_1.HEIC", Data: []byte("not really")},
		mailintest.Part{ContentType: "image/jpeg", Filename: "big.jpg", Data: bytes.Repeat([]byte{1}, 2<<20)},
	)
	p := parse(t, m.Build())
	if !p.FromOK || p.From != "rita@gmail.example" || p.Subject != "Fwd: Re: pics from Sunday" || p.MessageID != "<m1@example.org>" {
		t.Fatalf("headers %+v", p)
	}
	if len(p.Candidates) != 4 || !p.Candidates[3].TooLarge || p.Unsupported != 1 || p.Text != "Hi!\r\n" {
		t.Fatalf("candidates %+v unsupported %d text %q", p.Candidates, p.Unsupported, p.Text)
	}
	if b, _ := os.ReadFile(p.Candidates[0].Path); !bytes.Equal(b, jpg) {
		t.Fatal("decoded attachment differs")
	}

	// Forwarded as an attachment: its photos count, its headers don't.
	inner := mail("someone@else.example").Build()
	fwd := "From: jan@gmail.example\r\nSubject: fwd\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=b1\r\n\r\n" +
		"--b1\r\nContent-Type: text/plain\r\n\r\nsee below\r\n--b1\r\nContent-Type: message/rfc822\r\n\r\n" + string(inner) + "\r\n--b1--\r\n"
	p = parse(t, []byte(fwd))
	if len(p.Candidates) != 1 || p.From != "jan@gmail.example" || p.Text != "see below" {
		t.Fatalf("forwarded: %d %q %q", len(p.Candidates), p.From, p.Text)
	}

	// multipart/alternative: the branch with images wins; HTML is only text.
	alt := "From: a@b.example\r\nMIME-Version: 1.0\r\nContent-Type: multipart/alternative; boundary=a1\r\n\r\n" +
		"--a1\r\nContent-Type: text/plain\r\n\r\n\r\n--a1\r\nContent-Type: multipart/related; boundary=r1\r\n\r\n" +
		"--r1\r\nContent-Type: text/html\r\n\r\n<html><head><style>x{}</style></head><body><p>Garden <b>party</b></p><img src=\"cid:i1\"><img src=\"https://tracker.example/p.gif\"></body></html>\r\n" +
		"--r1\r\nContent-Type: image/jpeg\r\nContent-ID: <i1>\r\nContent-Transfer-Encoding: base64\r\n\r\n" + b64(jpg) + "\r\n--r1--\r\n--a1--\r\n"
	p = parse(t, []byte(alt))
	if len(p.Candidates) != 1 || p.HTMLText != "Garden party" {
		t.Fatalf("alternative: %d %q", len(p.Candidates), p.HTMLText)
	}

	// Encrypted mail can't be read.
	enc := "From: a@b.example\r\nMIME-Version: 1.0\r\nContent-Type: application/pkcs7-mime; smime-type=enveloped-data; name=smime.p7m\r\n\r\nMIIB...\r\n"
	if p := parse(t, []byte(enc)); !p.Encrypted {
		t.Fatal("encrypted not recognised")
	}
	pgp := "From: a@b.example\r\nMIME-Version: 1.0\r\nContent-Type: multipart/encrypted; protocol=\"application/pgp-encrypted\"; boundary=e1\r\n\r\n--e1\r\n\r\nx\r\n--e1--\r\n"
	if p := parse(t, []byte(pgp)); !p.Encrypted {
		t.Fatal("pgp not recognised")
	}

	// Deep trees stop at the depth limit; unknown transfer encodings are not decoded.
	var deep strings.Builder
	deep.WriteString("From: a@b.example\r\nMIME-Version: 1.0\r\n")
	for i := 0; i < 12; i++ {
		deep.WriteString("Content-Type: multipart/mixed; boundary=d" + itoa(i) + "\r\n\r\n--d" + itoa(i) + "\r\n")
	}
	deep.WriteString("Content-Type: image/jpeg\r\nContent-Transfer-Encoding: base64\r\n\r\n" + b64(jpg) + "\r\n")
	if p := parse(t, []byte(deep.String())); len(p.Candidates) != 0 || !p.Truncated {
		t.Fatalf("deep tree: %d candidates, truncated %v", len(p.Candidates), p.Truncated)
	}
	odd := "From: a@b.example\r\nMIME-Version: 1.0\r\nContent-Type: image/jpeg\r\nContent-Transfer-Encoding: x-uuencode\r\n\r\nbegin 644 a\r\n"
	if p := parse(t, []byte(odd)); len(p.Candidates) != 0 || p.Unsupported != 1 {
		t.Fatalf("unknown encoding: %+v", p)
	}

	// Robots: auto-replies, bounces, list mail without media.
	ooo := mail("a@b.example")
	ooo.Headers = map[string]string{"Auto-Submitted": "auto-replied"}
	if p := parse(t, ooo.Build()); !p.Automatic("a@b.example", false) {
		t.Fatal("auto-reply not ignored")
	}
	if p := parse(t, mail("MAILER-DAEMON@b.example").Build()); !p.Automatic("", true) {
		t.Fatal("bounce not ignored")
	}
	if p := parse(t, mail("jan@b.example").Build()); p.Automatic("jan@b.example", false) {
		t.Fatal("ordinary mail ignored")
	}
}

func b64(b []byte) string {
	enc := base64.StdEncoding.EncodeToString(b)
	var sb strings.Builder
	for len(enc) > 76 {
		sb.WriteString(enc[:76] + "\r\n")
		enc = enc[76:]
	}
	sb.WriteString(enc)
	return sb.String()
}

func itoa(i int) string { return strconv.Itoa(i) }

func TestWebhookSignature(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	v := &mailin.Verifier{Secrets: mailin.ParseSecrets("new-secret, old-secret"), Now: func() time.Time { return now }}
	r := mailin.SignedRequest{Timestamp: "1800000000", EnvelopeTo: "oma-k7q2m9@in.example.org", BodySHA256: mailin.BodyHash([]byte("x"))}
	if err := v.Verify(r, mailin.Sign("old-secret", r)); err != nil {
		t.Fatalf("old secret: %v", err)
	}
	if err := v.Verify(r, mailin.Sign("old-secret", r)); err != mailin.ErrReplayed {
		t.Fatalf("replay: %v", err)
	}
	r2 := r
	r2.EnvelopeTo = "other@in.example.org"
	if err := v.Verify(r2, mailin.Sign("new-secret", r)); err != mailin.ErrBadSignature {
		t.Fatalf("changed envelope: %v", err)
	}
	r3 := r
	r3.Timestamp = "1799999000"
	if err := v.Verify(r3, mailin.Sign("new-secret", r3)); err != mailin.ErrStale {
		t.Fatalf("stale: %v", err)
	}
	if err := v.Verify(r2, mailin.Sign("wrong", r2)); err != mailin.ErrBadSignature {
		t.Fatalf("wrong secret: %v", err)
	}
}

func TestReplyRendering(t *testing.T) {
	in := mailin.ReplyInput{Kind: mailin.ReplyDelivered, Lang: "en", Name: "Oma's frame", Server: "kinframe", Subject: "Fwd: pics",
		Photos: 3, Videos: 1, Skipped: map[string]int{"too_long": 1, "duplicate": 2, "too_small": 4}, MaxMinutes: 5, App: mailin.AppLong,
		Links: mailin.AppLinks{IOS: "https://apps.apple.com/app/x", Web: "https://kinframe.example"}, JoinURL: "https://kinframe.example/i/tok"}
	m := mailin.RenderReply(in)
	for _, want := range []string{"Your 4 photos and videos are on Oma's frame.", "1 videos were longer than 5 minutes", "2 of them were already there",
		"Next time, try the kinframe app", "App Store: https://apps.apple.com/app/x", "Join Oma's frame in the app: https://kinframe.example/i/tok"} {
		if !strings.Contains(m.Text, want) {
			t.Errorf("text lacks %q:\n%s", want, m.Text)
		}
	}
	if strings.Contains(m.Text, "Google Play") || m.Subject != "Re: pics" || !strings.Contains(m.HTML, "Oma&#39;s frame") {
		t.Errorf("reply %+v", m)
	}
	in.App, in.JoinURL = mailin.AppFooter, ""
	if m := mailin.RenderReply(in); !strings.Contains(m.Text, "Sending is easier in the kinframe app: https://apps.apple.com/app/x") || strings.Contains(m.Text, "Next time") {
		t.Errorf("footer:\n%s", m.Text)
	}
	in.Lang, in.Kind = "nl", mailin.ReplyHeld
	if m := mailin.RenderReply(in); strings.Contains(m.Text, "waiting for the family") {
		t.Errorf("not translated:\n%s", m.Text)
	}
}
