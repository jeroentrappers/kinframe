package shop

import (
	"context"
	"regexp"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/shop/smtptest"
)

const sample = `
enabled: true
seller:
  name: Example BV
  email: shop@example.org
  legal: Example BV · VAT BE0123.456.789
vat_percent: 21
notify_email: orders@example.org
estimated_delivery: { en: early 2027, nl: begin 2027 }
products:
  - id: frame-10
    name: Frame 10.1"
    size: 10.1"
    price_cents: 7900
    max_qty: 5
    image: frame-10
    specs:
      en: [1280×800 IPS touchscreen, Wi-Fi]
      nl: [1280×800 IPS-touchscreen, wifi]
  - id: frame-15
    name: Frame 15.6"
    price_cents: 15900
    max_qty: 5
    image: frame-15
shipping:
  countries: [BE, NL]
  flat_cents: 695
  free_from_cents: 10000
elsewhere:
  - { countries: [DE, FR], retailer: Amazon, product: Frame X, url: "https://www.amazon.de/dp/B000000000", affiliate: true }
  - { countries: ["*"], retailer: Shop, product: Frame X, url: "https://shop.example.org/frame-x" }
`

func sampleConfig(t *testing.T) *Config {
	t.Helper()
	c, err := Parse([]byte(sample), "kinframe")
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestParseDefaultsAndJSON(t *testing.T) {
	c := sampleConfig(t)
	if c.Brand != "kinframe" || c.ReferencePrefix != "KF" || c.Currency != "EUR" || c.RetentionDays != 365 {
		t.Fatalf("defaults: %+v", c)
	}
	if got := c.Products[0].SpecsFor("fr"); got[0] != "1280×800 IPS touchscreen" {
		t.Fatalf("specs fallback: %v", got)
	}
	if len(c.Elsewhere) != 2 || c.Elsewhere[0].Countries[1] != "FR" || c.Elsewhere[1].Countries[0] != "*" || !c.Elsewhere[0].Affiliate || c.Elsewhere[1].Affiliate {
		t.Fatalf("elsewhere: %+v", c.Elsewhere)
	}
	if c.EstimatedDelivery.Get("nl") != "begin 2027" || c.EstimatedDelivery.Get("fr") != "early 2027" {
		t.Fatalf("delivery: %v", c.EstimatedDelivery)
	}
	j := `{"enabled": true, "brand": "Fotokader", "seller": {"name": "S", "email": "s@x.org"}, "notify_email": "n@x.org",
		"products": [{"id": "a", "name": "A", "price_cents": 100, "max_qty": 2}],
		"shipping": {"countries": ["BE"], "flat_cents": 0, "free_from_cents": null}}`
	c2, err := Parse([]byte(j), "kinframe")
	if err != nil {
		t.Fatal(err)
	}
	if c2.Brand != "Fotokader" || c2.Shipping.FreeFromCents != nil {
		t.Fatalf("json: %+v", c2)
	}
}

func TestParseDisabled(t *testing.T) {
	c, err := Parse([]byte("enabled: false\n"), "kinframe")
	if c != nil || err != nil {
		t.Fatalf("disabled: %v %v", c, err)
	}
}

func TestParseErrors(t *testing.T) {
	for name, tc := range map[string]struct{ from, to, want string }{
		"unknown field":      {"vat_percent: 21", "vat_percent: 21\nvat: 3", "field vat not found"},
		"bad seller mail":    {"email: shop@example.org", "email: nope", "seller.email"},
		"bad country":        {"[BE, NL]", "[BE, Netherlands]", "shipping.countries"},
		"bad price":          {"price_cents: 7900", "price_cents: 0", "price_cents"},
		"bad image":          {"image: frame-10", "image: photo.jpg", "image"},
		"duplicate id":       {"id: frame-15", "id: frame-10", "used twice"},
		"no notify":          {"notify_email: orders@example.org", "notify_email: ''", "notify_email"},
		"elsewhere country":  {"[DE, FR]", "[DE, France]", "elsewhere[0].countries"},
		"elsewhere http":     {"https://www.amazon.de", "http://www.amazon.de", "elsewhere[0].url"},
		"elsewhere retailer": {"retailer: Amazon", "retailer: ''", "elsewhere[0].retailer"},
		"bad max qty":        {"max_qty: 5\n    image: frame-15", "max_qty: 0\n    image: frame-15", "max_qty"},
	} {
		t.Run(name, func(t *testing.T) {
			src := strings.Replace(sample, tc.from, tc.to, 1)
			if src == sample {
				t.Fatal("replacement did not apply")
			}
			_, err := Parse([]byte(src), "kinframe")
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want %q", err, tc.want)
			}
		})
	}
}

func TestPrice(t *testing.T) {
	c := sampleConfig(t)
	for name, tc := range map[string]struct {
		want                     []Want
		sub, ship, total, nLines int
	}{
		"one small pays shipping": {[]Want{{"frame-10", 1}}, 7900, 695, 8595, 1},
		"two small ship free":     {[]Want{{"frame-10", 2}}, 15800, 0, 15800, 1},
		"one large ships free":    {[]Want{{"frame-15", 1}}, 15900, 0, 15900, 1},
		"mixed":                   {[]Want{{"frame-10", 1}, {"frame-15", 2}}, 39700, 0, 39700, 2},
		"same product merges":     {[]Want{{"frame-10", 1}, {"frame-10", 1}}, 15800, 0, 15800, 1},
	} {
		t.Run(name, func(t *testing.T) {
			q, errs := c.Price(tc.want)
			if len(errs) > 0 {
				t.Fatal(errs)
			}
			if q.SubtotalCents != tc.sub || q.ShippingCents != tc.ship || q.TotalCents != tc.total || len(q.Lines) != tc.nLines {
				t.Fatalf("quote %+v", q)
			}
		})
	}
	_, errs := c.Price([]Want{{"frame-10", 3}, {"nope", 1}, {"frame-10", 3}})
	if len(errs) != 2 || errs[0] != (ItemError{1, "product_id", "enum"}) || errs[1] != (ItemError{2, "quantity", "maximum"}) {
		t.Fatalf("errors: %+v", errs)
	}
	if c.ShippingFor(0) != 0 || c.ShippingFor(9999) != 695 || c.ShippingFor(10000) != 0 {
		t.Fatal("shipping rule")
	}
}

func TestMoney(t *testing.T) {
	for _, tc := range []struct {
		cents     int
		cur, lang string
		want      string
	}{
		{7900, "EUR", "en", "€79.00"},
		{695, "EUR", "nl", "€\u00a06,95"},
		{15900, "EUR", "fr", "159,00\u00a0€"},
		{123456, "EUR", "de", "1.234,56\u00a0€"},
		{100, "CHF", "en", "CHF\u00a01.00"},
	} {
		if got := Money(tc.cents, tc.cur, tc.lang); got != tc.want {
			t.Errorf("Money(%d, %s, %s) = %q, want %q", tc.cents, tc.cur, tc.lang, got, tc.want)
		}
	}
}

func TestReferenceAndTokens(t *testing.T) {
	if r := NewReference("KF"); !regexp.MustCompile(`^KF-[2-9A-HJKMNP-TW-Z]{4}$`).MatchString(r) {
		t.Fatalf("reference %q", r)
	}
	tok := NewTokens([]byte("0123456789abcdef0123456789abcdef"))
	id := uuid.Must(uuid.NewV7())
	s := tok.Sign(id)
	if got, err := tok.Verify(s); err != nil || got != id {
		t.Fatalf("verify: %v %v", got, err)
	}
	other := NewTokens([]byte("another key, another key, another"))
	for _, bad := range []string{"", "abc", s + "x", strings.Replace(s, ".", ".A", 1), other.Sign(id)} {
		if _, err := tok.Verify(bad); err == nil {
			t.Errorf("token %q verified", bad)
		}
	}
}

func TestEmailsAndSMTP(t *testing.T) {
	c := sampleConfig(t)
	c.Brand = "Fotokader"
	q, _ := c.Price([]Want{{"frame-10", 1}})
	r := Reservation{Reference: "KF-7Q3M", Name: "Ann Peeters", Email: "ann@example.org", Country: "BE", Lang: "nl",
		Quote: q, Note: "<b>graag wit</b>", CancelURL: "https://k.example/shop/cancel?token=abc.def"}
	srv := smtptest.Start(t)
	m := &SMTPMailer{Cfg: SMTP{Host: srv.Host, Port: srv.Port, From: "Fotokader <noreply@example.org>"}}
	if err := m.Send(context.Background(), c.CustomerEmail(r)); err != nil {
		t.Fatal(err)
	}
	r.Lang = "it" // no Italian email texts: English
	if err := m.Send(context.Background(), c.OperatorEmail(r, false, "https://k.example/settings/reservations")); err != nil {
		t.Fatal(err)
	}
	if err := m.Send(context.Background(), c.CustomerEmail(r)); err != nil {
		t.Fatal(err)
	}
	got := srv.Wait(t, 3)
	nl, op, en := got[0], got[1], got[2]
	if nl.To != "ann@example.org" || nl.Subject != "Je reservatie voor een Fotokader: KF-7Q3M" {
		t.Fatalf("nl mail: %s %q", nl.To, nl.Subject)
	}
	for _, want := range []string{"Dag Ann Peeters", "Verzending naar België", "€\u00a06,95", "Totaal (incl. 21% btw)", "begin 2027",
		"https://k.example/shop/cancel?token=abc.def", "Example BV · VAT BE0123.456.789"} {
		if !strings.Contains(nl.Text, want) {
			t.Errorf("nl text lacks %q:\n%s", want, nl.Text)
		}
	}
	if !strings.Contains(nl.HTML, "&lt;b&gt;graag wit&lt;/b&gt;") || strings.Contains(nl.HTML, "<b>graag") {
		t.Errorf("html not escaped:\n%s", nl.HTML)
	}
	if strings.Contains(nl.HTML, "<img") {
		t.Error("no images or tracking pixels in emails")
	}
	if nl.Header.Get("Reply-To") == "" || !strings.Contains(nl.Header.Get("Reply-To"), "shop@example.org") {
		t.Errorf("reply-to %q", nl.Header.Get("Reply-To"))
	}
	if op.To != "orders@example.org" || !strings.Contains(op.Subject, "KF-7Q3M") || !strings.Contains(op.Text, "ann@example.org") {
		t.Fatalf("operator mail: %s %q\n%s", op.To, op.Subject, op.Text)
	}
	if en.Subject != "Your Fotokader reservation KF-7Q3M" || !strings.Contains(en.Text, "Estimated delivery: early 2027.") {
		t.Fatalf("fallback mail: %q\n%s", en.Subject, en.Text)
	}
}

func TestCountryName(t *testing.T) {
	if CountryName("NL", "fr") != "Pays-Bas" || CountryName("BE", "nl") != "België" || CountryName("BE", "en") != "Belgium" {
		t.Fatal(CountryName("NL", "fr"), CountryName("BE", "nl"))
	}
}

// The resale notice, the DIY section and secondary ("also at") links.
const sampleDIY = `
notice:
  en: "We resell Denver frames, converted to kinframe.\n\nPrefer to do it yourself? See below."
  nl: Wij verkopen omgezette Denver-kaders.
diy:
  title: { en: Buy it yourself and convert it, nl: Zelf kopen en omzetten }
  body: { en: Buy a Denver frame and convert it with our free tool. }
`

func diySource(extra string) string {
	return strings.Replace(sample, "elsewhere:\n", "elsewhere:\n"+
		`  - { countries: [BE], retailer: Amazon, product: Denver PFF-1024, url: "https://www.amazon.com.be/dp/B000000001", affiliate: true }`+"\n"+
		`  - { countries: [BE, DE], retailer: bol.com, product: Denver PFF-1024, url: "https://www.bol.com/be/p/1", secondary: true }`+"\n", 1) + sampleDIY + extra
}

func TestParseNoticeDIYSecondary(t *testing.T) {
	c, err := Parse([]byte(diySource("")), "kinframe")
	if err != nil {
		t.Fatal(err)
	}
	if got := Paragraphs(c.Notice.Get("fr")); len(got) != 2 || got[0] != "We resell Denver frames, converted to kinframe." || got[1] != "Prefer to do it yourself? See below." {
		t.Fatalf("notice paragraphs: %q", got)
	}
	if c.Notice.Get("nl") != "Wij verkopen omgezette Denver-kaders." || c.DIY.Title.Get("nl") != "Zelf kopen en omzetten" || c.DIY.Body.Get("nl") != "Buy a Denver frame and convert it with our free tool." {
		t.Fatalf("diy: %+v notice %v", c.DIY, c.Notice)
	}
	if !c.Elsewhere[1].Secondary || c.Elsewhere[0].Secondary {
		t.Fatalf("secondary: %+v", c.Elsewhere)
	}
	// Entries that list a shipping country (BE, NL) are the DIY links; "*" is for other countries only.
	diy := c.DIYLinks()
	if len(diy) != 2 || diy[0].Retailer != "Amazon" || diy[1].Retailer != "bol.com" {
		t.Fatalf("diy links: %+v", diy)
	}
	// Without the new fields nothing changes.
	if old := sampleConfig(t); len(old.Notice) != 0 || old.DIY != nil || len(old.DIYLinks()) != 0 {
		t.Fatalf("old config: %+v", old)
	}
	if got := Paragraphs("a\r\n \r\nb\nc\n\n\n"); len(got) != 2 || got[1] != "b\nc" {
		t.Fatalf("paragraphs: %q", got)
	}
}

func TestParseNoticeDIYErrors(t *testing.T) {
	for name, tc := range map[string]struct{ from, to, extra, want string }{
		"notice without en":   {"  en: \"We resell", "  de: \"We resell", "", "notice needs an en text"},
		"notice bad language": {"  nl: Wij", "  xx: Wij", "", `notice: "xx" is not a language code`},
		"notice html":         {"converted to kinframe.", "converted to <b>kinframe</b>.", "", "notice.en: plain text only"},
		"notice too long":     {"Wij verkopen omgezette Denver-kaders.", strings.Repeat("x", 2001), "", "notice.nl: at most 2000 characters"},
		"diy title no en":     {"title: { en: Buy it yourself and convert it, nl:", "title: { fr: Achetez, nl:", "", "diy.title needs an en text"},
		"diy body language":   {"body: { en: Buy", "body: { nl-BE: Koop, en: Buy", "", `diy.body: "nl-BE" is not a language code`},
		"diy unknown field":   {"", "", "  subtitle: { en: x }\n", "field subtitle not found"},
		// NL ships, so "*" doesn't cover it: an "also at" link needs a main link for one of its countries.
		"secondary alone": {"countries: [BE, DE], retailer: bol.com", "countries: [NL], retailer: bol.com", "", "elsewhere[1] is secondary"},
	} {
		t.Run(name, func(t *testing.T) {
			src := diySource(tc.extra)
			if tc.from != "" {
				next := strings.Replace(src, tc.from, tc.to, 1)
				if next == src {
					t.Fatal("replacement did not apply")
				}
				src = next
			}
			_, err := Parse([]byte(src), "kinframe")
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want %q", err, tc.want)
			}
		})
	}
}
