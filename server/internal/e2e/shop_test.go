package e2e

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/shop"
	"github.com/jeroentrappers/kinframe/server/internal/shop/smtptest"
)

const testShop = `
enabled: true
brand: Fotokader
seller: { name: Example BV, email: shop@example.org, legal: "Example BV · VAT BE0123.456.789" }
vat_percent: 21
notify_email: orders@example.org
estimated_delivery: { en: early 2027, nl: begin 2027, fr: début 2027 }
products:
  - { id: frame-10, name: 'Fotokader 10.1"', size: '10.1"', price_cents: 7900, max_qty: 5, image: frame-10,
      specs: { en: [1280×800 IPS touchscreen, Wi-Fi], nl: [1280×800 IPS-touchscreen, wifi] } }
  - { id: frame-15, name: 'Fotokader 15.6"', size: '15.6"', price_cents: 15900, max_qty: 5, image: frame-15 }
shipping: { countries: [BE, NL], flat_cents: 695, free_from_cents: 10000 }
`

func withShop(t *testing.T, mail *smtptest.Server) option {
	c, err := shop.Parse([]byte(testShop), "kinframe test")
	if err != nil {
		t.Fatal(err)
	}
	return func(cfg *config.Config) {
		cfg.Shop = c
		if mail != nil {
			cfg.SMTP = &shop.SMTP{Host: mail.Host, Port: mail.Port, From: "Fotokader <noreply@example.org>"}
		}
	}
}

func reservation(over map[string]any) map[string]any {
	b := map[string]any{"name": "An Peeters", "email": "an@example.org", "country": "BE", "postal_code": "9000",
		"items": []map[string]any{{"product_id": "frame-10", "quantity": 1}}, "locale": "nl", "consent": true}
	for k, v := range over {
		b[k] = v
	}
	return b
}

func fields(r *resp) []string {
	var p struct {
		Errors []struct{ Field, Code string } `json:"errors"`
	}
	_ = json.Unmarshal(r.body, &p)
	var out []string
	for _, e := range p.Errors {
		out = append(out, e.Field+"/"+e.Code)
	}
	slices.Sort(out)
	return out
}

// TestShopDisabled: the open-source default has no shop at all.
func TestShopDisabled(t *testing.T) {
	e := newEnv(t)
	if d := e.get("/.well-known/kinframe", "").m(t); d["shop"] != nil {
		t.Fatalf("discovery has a shop: %v", d["shop"])
	}
	if r := e.do(req{method: "POST", path: "/v1/shop/reservations", body: reservation(nil)}); r.StatusCode != 404 || r.code() != "not_found" {
		t.Fatalf("reservation without shop: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/shop/reservations/cancel", body: map[string]any{"token": "aaaaaaaaaaaa.bbbb"}}); r.StatusCode != 404 {
		t.Fatalf("cancel without shop: %d %s", r.StatusCode, r.body)
	}
	op := e.expect(req{method: "POST", path: "/dev/token", body: map[string]any{"email": "op@x.org", "name": "Op", "operator": true}}, 200).m(t)["access_token"].(string)
	for _, p := range []string{"/v1/operator/reservations", "/v1/operator/reservations.csv"} {
		if r := e.do(req{method: "GET", path: p, token: op}); r.StatusCode != 404 {
			t.Fatalf("%s without shop: %d %s", p, r.StatusCode, r.body)
		}
	}
}

func TestShopReservations(t *testing.T) {
	mail := smtptest.Start(t)
	e := newEnv(t, withShop(t, mail))

	// Discovery publishes what the web needs.
	d := e.get("/.well-known/kinframe", "").m(t)
	if dig(d, "shop", "brand") != "Fotokader" || dig(d, "shop", "currency") != "EUR" || dig(d, "shop", "vat_percent") != float64(21) ||
		dig(d, "shop", "shipping", "free_from_cents") != float64(10000) || dig(d, "shop", "seller", "name") != "Example BV" ||
		dig(d, "shop", "estimated_delivery", "nl") != "begin 2027" {
		t.Fatalf("discovery shop: %v", d["shop"])
	}
	products := dig(d, "shop", "products").([]any)
	if len(products) != 2 || products[0].(map[string]any)["price_cents"] != float64(7900) {
		t.Fatalf("products: %v", products)
	}
	if strings.Contains(string(e.get("/.well-known/kinframe", "").body), "orders@example.org") {
		t.Fatal("the notify address must not be public")
	}

	// Validation: every problem at once, as field errors.
	r := e.do(req{method: "POST", path: "/v1/shop/reservations", header: map[string]string{"Accept-Language": "nl"}, body: reservation(map[string]any{
		"email": "not-an-email", "country": "DE", "consent": false,
		"items": []map[string]any{{"product_id": "frame-10", "quantity": 6}, {"product_id": "toaster", "quantity": 1}},
	})})
	want := []string{"consent/required", "country/enum", "email/format", "items.0.quantity/maximum", "items.1.product_id/enum"}
	if r.StatusCode != 422 || r.code() != "validation_failed" || !slices.Equal(fields(r), want) {
		t.Fatalf("validation: %d %v\n%s", r.StatusCode, fields(r), r.body)
	}
	if !strings.Contains(string(r.body), "sommige gegevens van de reservatie zijn niet geldig") {
		t.Fatalf("detail not localized: %s", r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/shop/reservations", body: reservation(map[string]any{"items": []any{}})}); r.StatusCode != 422 ||
		!slices.Equal(fields(r), []string{"items/minItems"}) {
		t.Fatalf("no items: %d %s", r.StatusCode, r.body)
	}

	// A small frame pays shipping; prices from the client are ignored.
	rc := e.expect(req{method: "POST", path: "/v1/shop/reservations", body: reservation(map[string]any{
		"items": []map[string]any{{"product_id": "frame-10", "quantity": 1, "price_cents": 1}}, "note": "Graag voor Kerstmis",
	})}, 201).m(t)
	ref := rc["reference"].(string)
	if !regexp.MustCompile(`^KF-[2-9A-Z]{4}$`).MatchString(ref) || rc["subtotal_cents"] != float64(7900) || rc["shipping_cents"] != float64(695) ||
		rc["total_cents"] != float64(8595) || rc["status"] != "new" {
		t.Fatalf("receipt: %v", rc)
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
	if customer.Subject != "Je reservatie voor een Fotokader: "+ref || !strings.Contains(customer.Text, "Verzending naar België") ||
		!strings.Contains(customer.Text, "Je betaalt nu niets") {
		t.Fatalf("customer mail %q:\n%s", customer.Subject, customer.Text)
	}
	if operator.To != "orders@example.org" || !strings.Contains(operator.Subject, ref) || !strings.Contains(operator.Text, e.url+"/settings/reservations") {
		t.Fatalf("operator mail %s %q:\n%s", operator.To, operator.Subject, operator.Text)
	}
	link := regexp.MustCompile(`https?://\S+/shop/cancel\?token=(\S+)`).FindStringSubmatch(customer.Text)
	if link == nil || !strings.HasPrefix(link[0], e.url+"/shop/cancel?token=") {
		t.Fatalf("no cancel link in:\n%s", customer.Text)
	}
	token := link[1]

	// Two small frames (merged lines) ship free; a formula-looking name is defused in the CSV.
	rc2 := e.expect(req{method: "POST", path: "/v1/shop/reservations", body: reservation(map[string]any{
		"name": "=HYPERLINK(1)", "email": "piet@example.nl", "country": "NL", "locale": "fr",
		"items": []map[string]any{{"product_id": "frame-10", "quantity": 1}, {"product_id": "frame-10", "quantity": 1}},
	})}, 201).m(t)
	if rc2["subtotal_cents"] != float64(15800) || rc2["shipping_cents"] != float64(0) || len(rc2["items"].([]any)) != 1 {
		t.Fatalf("free shipping: %v", rc2)
	}
	mails = mail.Wait(t, 4)

	// Honeypot: looks fine, stores and sends nothing.
	hp := e.expect(req{method: "POST", path: "/v1/shop/reservations", body: reservation(map[string]any{"website": "http://spam.example"})}, 201).m(t)
	if hp["reference"] == "" {
		t.Fatalf("honeypot receipt: %v", hp)
	}

	// Operator endpoints need the operator scope.
	u := e.user("shopper@x.org", "Shopper")
	for _, r := range []req{
		{method: "GET", path: "/v1/operator/reservations", token: u.token},
		{method: "GET", path: "/v1/operator/reservations.csv", token: u.token},
		{method: "PATCH", path: "/v1/operator/reservations/01a16300-0000-7000-8000-000000000000", token: u.token, body: map[string]any{"status": "contacted"}},
	} {
		if res := e.do(r); res.StatusCode != 403 {
			t.Fatalf("%s %s as a normal user: %d", r.method, r.path, res.StatusCode)
		}
		r.token = ""
		if res := e.do(r); res.StatusCode != 401 {
			t.Fatalf("%s %s without a token: %d", r.method, r.path, res.StatusCode)
		}
	}
	op := e.expect(req{method: "POST", path: "/dev/token", body: map[string]any{"email": "op@x.org", "name": "Op", "operator": true}}, 200).m(t)["access_token"].(string)
	list := e.get("/v1/operator/reservations", op).m(t)["reservations"].([]any)
	if len(list) != 2 {
		t.Fatalf("list (honeypot must not be stored): %v", list)
	}
	first := list[1].(map[string]any)
	if first["reference"] != ref || first["note"] != "Graag voor Kerstmis" || first["postal_code"] != "9000" || first["locale"] != "nl" {
		t.Fatalf("listed reservation: %v", first)
	}
	id := first["id"].(string)
	upd := e.expect(req{method: "PATCH", path: "/v1/operator/reservations/" + id, token: op,
		body: map[string]any{"status": "contacted", "internal_note": "called on Monday"}}, 200).m(t)
	if upd["status"] != "contacted" || upd["internal_note"] != "called on Monday" {
		t.Fatalf("update: %v", upd)
	}
	if got := e.get("/v1/operator/reservations?status=contacted", op).m(t)["reservations"].([]any); len(got) != 1 {
		t.Fatalf("filter: %v", got)
	}
	if r := e.do(req{method: "GET", path: "/v1/operator/reservations?status=lost", token: op}); r.StatusCode != 400 {
		t.Fatalf("bad filter: %d", r.StatusCode)
	}
	upd = e.expect(req{method: "PATCH", path: "/v1/operator/reservations/" + id, token: op, body: map[string]any{"internal_note": nil}}, 200).m(t)
	if upd["internal_note"] != nil || upd["status"] != "contacted" {
		t.Fatalf("clear note: %v", upd)
	}
	if r := e.do(req{method: "PATCH", path: "/v1/operator/reservations/01a16300-0000-7000-8000-000000000000", token: op, body: map[string]any{"status": "new"}}); r.StatusCode != 404 {
		t.Fatalf("unknown reservation: %d", r.StatusCode)
	}

	csv := e.get("/v1/operator/reservations.csv", op)
	body := string(csv.body)
	if !strings.HasPrefix(csv.Header.Get("Content-Type"), "text/csv") || !strings.HasPrefix(body, "\ufeffreference,status,created_at,") ||
		!strings.Contains(body, ref+",contacted,") || !strings.Contains(body, "'=HYPERLINK(1)") || !strings.Contains(body, "85.95") ||
		strings.Count(body, "\n") != 3 {
		t.Fatalf("csv:\n%s", body)
	}

	// The customer cancels with the link; again is fine; a forged token is not.
	if r := e.do(req{method: "POST", path: "/v1/shop/reservations/cancel", body: map[string]any{"token": token[:len(token)-2] + "xx"}}); r.StatusCode != 404 {
		t.Fatalf("forged token: %d %s", r.StatusCode, r.body)
	}
	for range 2 {
		c := e.expect(req{method: "POST", path: "/v1/shop/reservations/cancel", body: map[string]any{"token": token}}, 200).m(t)
		if c["reference"] != ref || c["status"] != "cancelled" {
			t.Fatalf("cancel: %v", c)
		}
	}
	mails = mail.Wait(t, 5)
	time.Sleep(200 * time.Millisecond)
	if n := len(mail.Mails()); n != 5 || !strings.Contains(mails[4].Subject, "cancelled") {
		t.Fatalf("expected exactly one cancellation mail, have %d: %q", n, mails[len(mails)-1].Subject)
	}
	if got := e.get("/v1/operator/reservations?status=cancelled", op).m(t)["reservations"].([]any); len(got) != 1 || got[0].(map[string]any)["cancelled_at"] == nil {
		t.Fatalf("cancelled: %v", got)
	}

	// Retention: cancelled reservations go after 30 days, others after retention_days.
	ctx := context.Background()
	if _, err := e.app.Pool.Exec(ctx, `UPDATE shop_reservations SET cancelled_at = now() - interval '31 days' WHERE status = 'cancelled'`); err != nil {
		t.Fatal(err)
	}
	e.app.Maintain(ctx)
	if got := e.get("/v1/operator/reservations", op).m(t)["reservations"].([]any); len(got) != 1 {
		t.Fatalf("after purging cancelled: %d", len(got))
	}
	if _, err := e.app.Pool.Exec(ctx, `UPDATE shop_reservations SET updated_at = now() - interval '366 days'`); err != nil {
		t.Fatal(err)
	}
	e.app.Maintain(ctx)
	if got := e.get("/v1/operator/reservations", op).m(t)["reservations"].([]any); len(got) != 0 {
		t.Fatalf("after retention: %d", len(got))
	}
}

// TestShopWithoutSMTP: reservations are stored even when mail isn't set up.
func TestShopWithoutSMTP(t *testing.T) {
	e := newEnv(t, withShop(t, nil))
	e.expect(req{method: "POST", path: "/v1/shop/reservations", body: reservation(nil)}, 201)
	op := e.expect(req{method: "POST", path: "/dev/token", body: map[string]any{"email": "op@x.org", "name": "Op", "operator": true}}, 200).m(t)["access_token"].(string)
	if got := e.get("/v1/operator/reservations", op).m(t)["reservations"].([]any); len(got) != 1 {
		t.Fatalf("stored: %v", got)
	}
}

// TestShopStorefront: a storefront domain proxies to the same server. Customer
// links in mails point back to the storefront the reservation came from;
// everything else (discovery, operator links) stays on the public URL.
func TestShopStorefront(t *testing.T) {
	mail := smtptest.Start(t)
	sf, err := shop.Parse([]byte(testShop+"domains: [shop.example.org]\ndefault_locale: nl\n"), "kinframe test")
	if err != nil {
		t.Fatal(err)
	}
	web := t.TempDir()
	_ = os.WriteFile(filepath.Join(web, "index.html"), []byte(`<!doctype html>
<html lang="en"><head><meta name="description" content="Send photos and videos to the family's photo frames." />
<title>kinframe</title></head><body></body></html>`), 0o644)
	_ = os.WriteFile(filepath.Join(web, "favicon.svg"), []byte("<svg/>"), 0o644)
	e := newEnv(t, withShop(t, mail), func(c *config.Config) { c.Shop = sf; c.WebDir = web })
	storefront := map[string]string{"Host": "shop.example.org"}

	// Discovery is the same on every host: `web` is still the app's origin.
	for _, h := range []map[string]string{nil, storefront} {
		d := e.expect(req{method: "GET", path: "/.well-known/kinframe", header: h}, 200).m(t)
		if d["web"] != e.url || dig(d, "shop", "default_locale") != "nl" || !slices.Equal(dig(d, "shop", "domains").([]any), []any{"shop.example.org"}) {
			t.Fatalf("discovery on %v: web %v shop %v", h, d["web"], d["shop"])
		}
	}

	cancelLink := func(m smtptest.Mail) string {
		t.Helper()
		l := regexp.MustCompile(`https?://\S+/shop/cancel\?token=\S+`).FindString(m.Text)
		if l == "" {
			t.Fatalf("no cancel link in:\n%s", m.Text)
		}
		return l
	}
	reserve := func(header map[string]string, email string) (customer, operator smtptest.Mail) {
		t.Helper()
		n := len(mail.Mails())
		e.expect(req{method: "POST", path: "/v1/shop/reservations", header: header, body: reservation(map[string]any{"email": email})}, 201)
		for _, m := range mail.Wait(t, n+2)[n:] {
			if m.To == email {
				customer = m
			} else {
				operator = m
			}
		}
		return customer, operator
	}

	// Reserved on the storefront: the cancel link goes back there (in the HTML part too).
	customer, operator := reserve(map[string]string{"Host": "Shop.Example.org"}, "an@example.org")
	link := cancelLink(customer)
	if !strings.HasPrefix(link, "http://shop.example.org/shop/cancel?token=") || !strings.Contains(customer.HTML, `href="http://shop.example.org/shop/cancel?token=`) {
		t.Fatalf("storefront cancel link %s\n%s", link, customer.HTML)
	}
	if !strings.Contains(operator.Text, e.url+"/settings/reservations") {
		t.Fatalf("operator link must stay on the app:\n%s", operator.Text)
	}
	// On the app's own host, and on hosts that aren't storefronts, nothing changes.
	for i, h := range []map[string]string{nil, {"Host": "other.example.org"}, {"X-Forwarded-Host": "shop.example.org"}} {
		customer, _ := reserve(h, fmt.Sprintf("p%d@example.org", i))
		if l := cancelLink(customer); !strings.HasPrefix(l, e.url+"/shop/cancel?token=") {
			t.Fatalf("host %v: cancel link %s", h, l)
		}
	}
	// The storefront's link works like any other.
	token := link[strings.Index(link, "token=")+len("token="):]
	if c := e.expect(req{method: "POST", path: "/v1/shop/reservations/cancel", header: storefront, body: map[string]any{"token": token}}, 200).m(t); c["status"] != "cancelled" {
		t.Fatalf("cancel on the storefront: %v", c)
	}

	// The web app on a storefront host carries the brand's title and link preview, in the visitor's language.
	page := func(path string, header map[string]string) string {
		t.Helper()
		r := e.expect(req{method: "GET", path: path, header: header}, 200)
		return string(r.body)
	}
	fr := page("/", map[string]string{"Host": "shop.example.org", "Accept-Language": "fr-BE,fr;q=0.9"})
	for _, want := range []string{`<html lang="fr">`, "<title>Fotokader : le cadre photo de toute la famille</title>",
		`<meta property="og:url" content="http://shop.example.org/" />`, `<meta property="og:site_name" content="Fotokader" />`} {
		if !strings.Contains(fr, want) {
			t.Fatalf("storefront page lacks %s:\n%s", want, fr)
		}
	}
	if nl := page("/shop", map[string]string{"Host": "shop.example.org", "Accept-Language": "es"}); !strings.Contains(nl, "het fotokader voor de hele familie") {
		t.Fatalf("default_locale page:\n%s", nl)
	}
	for _, h := range []map[string]string{nil, {"Host": "other.example.org"}} {
		if p := page("/", h); !strings.Contains(p, "<title>kinframe</title>") || strings.Contains(p, "og:") {
			t.Fatalf("app page on %v changed:\n%s", h, p)
		}
	}
	if svg := page("/favicon.svg", storefront); svg != "<svg/>" {
		t.Fatalf("static file on the storefront: %s", svg)
	}
}

// TestShopStorefrontBehindProxy: with KINFRAME_TRUST_PROXY, X-Forwarded-Host names the storefront.
func TestShopStorefrontBehindProxy(t *testing.T) {
	mail := smtptest.Start(t)
	sf, err := shop.Parse([]byte(testShop+"domains: [shop.example.org]\n"), "kinframe test")
	if err != nil {
		t.Fatal(err)
	}
	e := newEnv(t, withShop(t, mail), func(c *config.Config) { c.Shop = sf; c.TrustProxy = true })
	e.expect(req{method: "POST", path: "/v1/shop/reservations", header: map[string]string{"X-Forwarded-Host": "shop.example.org"},
		body: reservation(nil)}, 201)
	for _, m := range mail.Wait(t, 2) {
		if m.To == "an@example.org" && !strings.Contains(m.Text, "http://shop.example.org/shop/cancel?token=") {
			t.Fatalf("cancel link behind a proxy:\n%s", m.Text)
		}
	}
}

func TestShopRateLimit(t *testing.T) {
	e := newEnv(t, withShop(t, nil))
	for i := range 10 {
		if r := e.do(req{method: "POST", path: "/v1/shop/reservations", body: reservation(map[string]any{"consent": false})}); r.StatusCode != 422 {
			t.Fatalf("request %d: %d", i, r.StatusCode)
		}
	}
	r := e.do(req{method: "POST", path: "/v1/shop/reservations", body: reservation(nil)})
	if r.StatusCode != 429 || r.code() != "rate_limited" || r.Header.Get("Retry-After") == "" {
		t.Fatalf("11th request: %d %s", r.StatusCode, r.body)
	}
}
