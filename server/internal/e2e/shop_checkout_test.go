package e2e

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strings"
	"sync"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/shop"
	"github.com/jeroentrappers/kinframe/server/internal/shop/smtptest"
)

const testCheckoutShop = testShop + `
payments: { provider: mollie, methods: [bancontact, creditcard] }
`

// fakeMollie is the part of the Mollie Payments API the shop uses.
type fakeMollie struct {
	mu      sync.Mutex
	created []map[string]any
	status  string
	fail    bool
}

func (f *fakeMollie) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if r.Header.Get("Authorization") != "Bearer test_abc" || f.fail {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = io.WriteString(w, `{"title":"Unauthorized","detail":"bad key"}`)
		return
	}
	payment := func() map[string]any {
		p := map[string]any{"id": "tr_test1", "status": f.status, "metadata": map[string]string{"reference": "x"}, "_links": map[string]any{}}
		if f.status == "open" {
			p["_links"] = map[string]any{"checkout": map[string]string{"href": "https://pay.example/tr_test1"}}
		}
		return p
	}
	switch {
	case r.Method == "POST" && r.URL.Path == "/payments":
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		f.created = append(f.created, b)
		f.status = "open"
		_ = json.NewEncoder(w).Encode(payment())
	case r.Method == "GET" && r.URL.Path == "/payments/tr_test1":
		_ = json.NewEncoder(w).Encode(payment())
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

func (f *fakeMollie) set(status string) { f.mu.Lock(); f.status = status; f.mu.Unlock() }

func withCheckoutShop(t *testing.T, mail *smtptest.Server, mollie *httptest.Server) option {
	c, err := shop.Parse([]byte(strings.Replace(testCheckoutShop,
		`seller: { name: Example BV, email: shop@example.org, legal: "Example BV · VAT BE0123.456.789" }`,
		`seller: { name: Example BV, email: shop@example.org, legal: "Example BV · VAT BE0123.456.789", address: "Kerkstraat 1, 9000 Gent", vat_number: BE0123456789 }`, 1)),
		"kinframe test")
	if err != nil {
		t.Fatal(err)
	}
	return func(cfg *config.Config) {
		cfg.Shop = c
		cfg.MollieAPIKey = "test_abc"
		cfg.MollieBaseURL = mollie.URL
		cfg.SMTP = &shop.SMTP{Host: mail.Host, Port: mail.Port, From: "Fotokader <noreply@example.org>"}
	}
}

func TestShopCheckout(t *testing.T) {
	mail := smtptest.Start(t)
	fm := &fakeMollie{}
	ms := httptest.NewServer(fm)
	defer ms.Close()
	e := newEnv(t, withCheckoutShop(t, mail, ms))

	d := e.get("/.well-known/kinframe", "").m(t)
	if dig(d, "shop", "checkout") != true || dig(d, "shop", "seller", "vat_number") != "BE0123456789" {
		t.Fatalf("discovery: %v", d["shop"])
	}

	// An order needs the delivery address.
	r := e.do(req{method: "POST", path: "/v1/shop/reservations", body: reservation(map[string]any{"postal_code": ""})})
	if r.StatusCode != 422 || !slices.Equal(fields(r), []string{"city/minLength", "postal_code/minLength", "street/minLength"}) {
		t.Fatalf("address validation: %d %v", r.StatusCode, fields(r))
	}

	order := reservation(map[string]any{"street": "Veldstraat 10", "city": "Gent", "phone": "+32 470 00 00 00"})
	rc := e.expect(req{method: "POST", path: "/v1/shop/reservations", body: order}, 201).m(t)
	ref := rc["reference"].(string)
	if rc["checkout_url"] != "https://pay.example/tr_test1" || rc["payment_status"] != "open" || rc["total_cents"] != float64(8595) {
		t.Fatalf("receipt: %v", rc)
	}
	if len(fm.created) != 1 {
		t.Fatalf("payments created: %d", len(fm.created))
	}
	p := fm.created[0]
	amount := p["amount"].(map[string]any)
	if amount["value"] != "85.95" || amount["currency"] != "EUR" || p["locale"] != "nl_BE" || p["description"] != "Fotokader "+ref ||
		p["webhookUrl"] != nil || !slices.Equal(toStrings(p["method"]), []string{"bancontact", "creditcard"}) {
		t.Fatalf("payment request: %v", p)
	}
	redirect, _ := url.Parse(p["redirectUrl"].(string))
	token := redirect.Query().Get("token")
	if redirect.Path != "/shop/order" || token == "" {
		t.Fatalf("redirect: %v", p["redirectUrl"])
	}
	if n := len(mail.Mails()); n != 0 {
		t.Fatalf("mails before payment: %d", n)
	}

	// The order page sees the open payment and can send the customer back to it.
	st := e.expect(req{method: "POST", path: "/v1/shop/orders/status", body: map[string]any{"token": token}}, 200).m(t)
	if st["payment_status"] != "open" || st["checkout_url"] != "https://pay.example/tr_test1" || st["reference"] != ref {
		t.Fatalf("status open: %v", st)
	}
	if r := e.do(req{method: "POST", path: "/v1/shop/orders/status", body: map[string]any{"token": "aaaaaaaaaaaa.bbbb"}}); r.StatusCode != 404 {
		t.Fatalf("bad token: %d", r.StatusCode)
	}

	// Paid: the webhook only carries the id; the status is read back from Mollie. The mails go out once.
	fm.set("paid")
	webhook := func(id string) int {
		resp, err := http.PostForm(e.url+"/v1/shop/payments/webhook", url.Values{"id": {id}})
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	if c := webhook("tr_test1"); c != 200 {
		t.Fatalf("webhook: %d", c)
	}
	mails := mail.Wait(t, 2)
	var customer, operator smtptest.Mail
	for _, m := range mails {
		if m.To == "an@example.org" {
			customer = m
		} else {
			operator = m
		}
	}
	if !strings.Contains(customer.Subject, "bestelling") || !strings.Contains(customer.Text, "Veldstraat 10") ||
		!strings.Contains(customer.Text, "9000 Gent") || !strings.Contains(customer.Text, "herroepen") {
		t.Fatalf("customer mail: %s\n%s", customer.Subject, customer.Text)
	}
	if !strings.HasPrefix(operator.Subject, "Paid order "+ref) || !strings.Contains(operator.Text, "+32 470 00 00 00") {
		t.Fatalf("operator mail: %s\n%s", operator.Subject, operator.Text)
	}
	if c := webhook("tr_test1"); c != 200 {
		t.Fatalf("webhook again: %d", c)
	}
	if c := webhook("tr_unknown"); c != 200 {
		t.Fatalf("unknown payment: %d", c)
	}
	st = e.expect(req{method: "POST", path: "/v1/shop/orders/status", body: map[string]any{"token": token}}, 200).m(t)
	if st["payment_status"] != "paid" || st["checkout_url"] != nil {
		t.Fatalf("status paid: %v", st)
	}

	// The operator sees the address and payment, and marking it shipped mails the customer.
	op := e.expect(req{method: "POST", path: "/dev/token", body: map[string]any{"email": "op@x.org", "name": "Op", "operator": true}}, 200).m(t)["access_token"].(string)
	list := e.expect(req{method: "GET", path: "/v1/operator/reservations", token: op}, 200).m(t)["reservations"].([]any)
	o := list[0].(map[string]any)
	if o["street"] != "Veldstraat 10" || o["payment_status"] != "paid" || o["paid_at"] == nil {
		t.Fatalf("operator view: %v", o)
	}
	path := "/v1/operator/reservations/" + o["id"].(string)
	if r := e.do(req{method: "PATCH", path: path, token: op, body: map[string]any{"tracking_url": "javascript:alert(1)"}}); r.StatusCode != 422 ||
		!slices.Equal(fields(r), []string{"tracking_url/format"}) {
		t.Fatalf("bad tracking link: %d %v", r.StatusCode, fields(r))
	}
	track := "https://track.bpost.cloud/btr/web/#/search?itemCode=323212345678901234&lang=nl"
	sh := e.expect(req{method: "PATCH", path: path, token: op, body: map[string]any{"status": "shipped", "tracking_url": track}}, 200).m(t)
	if sh["tracking_url"] != track || sh["shipped_at"] == nil {
		t.Fatalf("shipped: %v", sh)
	}
	mails = mail.Wait(t, 3)
	if !strings.Contains(mails[2].Subject, "onderweg") || mails[2].To != "an@example.org" ||
		!strings.Contains(mails[2].Text, "Volg je pakje:\n"+track) || !strings.Contains(mails[2].HTML, "Volg mijn pakje") {
		t.Fatalf("shipped mail: %s to %s\n%s", mails[2].Subject, mails[2].To, mails[2].Text)
	}
	// The same link again sends nothing; a new link on a shipped order mails the customer once more.
	e.expect(req{method: "PATCH", path: path, token: op, body: map[string]any{"tracking_url": track, "internal_note": "bpost"}}, 200)
	track2 := "https://www.dhl.com/be-nl/home/tracking.html?tracking-id=JVGL0123456789"
	e.expect(req{method: "PATCH", path: path, token: op, body: map[string]any{"tracking_url": track2}}, 200)
	mails = mail.Wait(t, 4)
	if !strings.Contains(mails[3].Text, track2) || mails[3].To != "an@example.org" {
		t.Fatalf("tracking mail: %s\n%s", mails[3].Subject, mails[3].Text)
	}
	if len(mail.Mails()) != 4 {
		t.Fatalf("mails: %d", len(mail.Mails()))
	}
	if c := e.expect(req{method: "PATCH", path: path, token: op, body: map[string]any{"tracking_url": nil}}, 200).m(t); c["tracking_url"] != nil {
		t.Fatalf("cleared: %v", c["tracking_url"])
	}

	// A provider failure is reported, not hidden.
	fm.mu.Lock()
	fm.fail = true
	fm.mu.Unlock()
	if r := e.do(req{method: "POST", path: "/v1/shop/reservations", body: order}); r.code() != "upstream_unavailable" {
		t.Fatalf("provider down: %d %s", r.StatusCode, r.body)
	}
}

func toStrings(v any) []string {
	var out []string
	for _, x := range v.([]any) {
		out = append(out, x.(string))
	}
	return out
}
