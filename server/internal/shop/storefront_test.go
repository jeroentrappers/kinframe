package shop

import (
	"strings"
	"testing"
)

func TestStorefrontDomainsConfig(t *testing.T) {
	c := sampleConfig(t)
	if len(c.Domains) != 0 || c.DefaultLocale != "en" || c.IsStorefront("shop.example.org") {
		t.Fatalf("defaults: domains %v locale %q", c.Domains, c.DefaultLocale)
	}

	c, err := Parse([]byte(sample+"domains: [Shop.Example.org., frames.example.net]\ndefault_locale: nl\n"), "kinframe")
	if err != nil {
		t.Fatal(err)
	}
	if c.Domains[0] != "shop.example.org" || c.DefaultLocale != "nl" {
		t.Fatalf("normalized: %v %q", c.Domains, c.DefaultLocale)
	}
	for host, want := range map[string]bool{
		"shop.example.org": true, "SHOP.example.org:443": true, "shop.example.org.": true, "frames.example.net": true,
		"kinframe.example.org": false, "example.org": false, "": false, "evil.shop.example.org": false, "[::1]:8080": false,
	} {
		if got := c.IsStorefront(host); got != want {
			t.Errorf("IsStorefront(%q) = %v", host, got)
		}
	}
	var none *Config
	if none.IsStorefront("shop.example.org") {
		t.Error("no shop, no storefront")
	}

	for _, bad := range []string{
		"domains: [https://shop.example.org]", "domains: [shop.example.org:8443]", "domains: [shop.example.org/x]",
		"domains: [localhost]", "domains: [shop.example.org, SHOP.example.org]", "domains: ['-x.example.org']",
		"default_locale: es",
	} {
		if _, err := Parse([]byte(sample+bad+"\n"), "kinframe"); err == nil {
			t.Errorf("accepted %s", bad)
		}
	}
}

func TestStorefrontLang(t *testing.T) {
	c, err := Parse([]byte(sample+"domains: [shop.example.org]\ndefault_locale: nl\n"), "kinframe")
	if err != nil {
		t.Fatal(err)
	}
	for accept, want := range map[string]string{
		"": "nl", "fr-BE,fr;q=0.9": "fr", "es-ES, de;q=0.5": "de", "en-US": "en", "ja": "nl", "nl-NL": "nl", "garbage;;": "nl",
	} {
		if got := c.StorefrontLang(accept); got != want {
			t.Errorf("StorefrontLang(%q) = %q, want %q", accept, got, want)
		}
	}
}

func TestStorefrontIndex(t *testing.T) {
	c, err := Parse([]byte(sample+"brand: Kader & Co\ndomains: [shop.example.org]\n"), "kinframe")
	if err != nil {
		t.Fatal(err)
	}
	index := []byte(`<!doctype html>
<html lang="en">
	<head>
		<meta name="description" content="Send photos and videos to the family's photo frames." />
		<title>kinframe</title>
	</head>
	<body></body>
</html>`)
	out := string(c.StorefrontIndex(index, "https://shop.example.org", "https://app.example.org", "nl"))
	for _, want := range []string{
		`<html lang="nl">`,
		`<title>Kader &amp; Co: het fotokader voor de hele familie</title>`,
		`<meta name="description" content="Stuur foto’s vanaf je smartphone`,
		`<meta property="og:site_name" content="Kader &amp; Co" />`,
		`<meta property="og:title" content="Kader &amp; Co: het fotokader voor de hele familie" />`,
		`<meta property="og:url" content="https://shop.example.org/" />`,
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %s in\n%s", want, out)
		}
	}
	if strings.Contains(out, "kinframe") || strings.Index(out, "og:title") > strings.Index(out, "</head>") {
		t.Errorf("storefront index:\n%s", out)
	}
	// The shop as plain HTML for visitors without JavaScript.
	nos := out[strings.Index(out, "<noscript>"):]
	for _, want := range []string{
		"<noscript><main", "<h1>Kader &amp; Co: het fotokader", "Reserveren", "incl. 21% btw", "/shop/terms", "Verkoper",
	} {
		if !strings.Contains(nos, want) {
			t.Errorf("noscript missing %s in\n%s", want, nos)
		}
	}
	if strings.Index(out, "<noscript>") < strings.Index(out, "<body>") {
		t.Errorf("noscript outside body:\n%s", out)
	}
	// With online payment: order, not reserve, and the payment methods.
	paid, err := Parse([]byte(strings.Replace(sample, "  legal: Example BV · VAT BE0123.456.789\n",
		"  legal: Example BV · VAT BE0123.456.789\n  address: Kerkstraat 1, 9000 Gent\n  vat_number: BE0123456789\n", 1)+"domains: [shop.example.org]\npayments: { provider: mollie, methods: [bancontact, creditcard, applepay] }\n"), "kinframe")
	if err != nil {
		t.Fatal(err)
	}
	if _, desc := paid.StorefrontMeta("nl"); !strings.HasSuffix(desc, "Bestel online en betaal veilig.") {
		t.Errorf("checkout description %q", desc)
	}
	po := string(paid.StorefrontIndex(index, "https://shop.example.org", "https://app.example.org", "nl"))
	for _, want := range []string{"Bestellen", "Betalen: Bancontact, kredietkaart, Apple Pay."} {
		if !strings.Contains(po, want) {
			t.Errorf("checkout noscript missing %s", want)
		}
	}
	// Unknown languages fall back to English texts.
	if title, _ := c.StorefrontMeta("ja"); title != "Kader & Co: the photo frame for the whole family" {
		t.Errorf("fallback title %q", title)
	}
}

// The plain HTML storefront says what the web app says: the notice at the top
// and the DIY links for the shipping countries, before the products.
func TestStorefrontNoscriptNoticeDIY(t *testing.T) {
	c, err := Parse([]byte(diySource("domains: [shop.example.org]\n")), "kinframe")
	if err != nil {
		t.Fatal(err)
	}
	index := []byte(`<!doctype html><html lang="en"><head><title>kinframe</title></head><body></body></html>`)
	out := string(c.StorefrontIndex(index, "https://shop.example.org", "https://app.example.org/", "fr"))
	nos := out[strings.Index(out, "<noscript>"):]
	want := []string{
		`<div role="note"`, "<p>We resell Denver frames, converted to kinframe.</p><p>Prefer to do it yourself? See below.</p></div>",
		"<h2>Buy it yourself and convert it</h2><p>Buy a Denver frame and convert it with our free tool.</p>",
		`<a href="https://www.amazon.com.be/dp/B000000001" rel="sponsored noopener">Denver PFF-1024 · Amazon</a>`,
		`<small><a href="https://www.bol.com/be/p/1" rel="noopener">Aussi chez bol.com</a></small>`,
		`<a href="https://app.example.org/convert">Comment convertir un cadre</a>`,
		"Ce sont des liens affiliés",
		"<h2>Nos cadres</h2>",
	}
	last := -1
	for _, w := range want {
		i := strings.Index(nos, w)
		if i < 0 {
			t.Fatalf("noscript missing %s in\n%s", w, nos)
		}
		if i < last {
			t.Errorf("%s is out of order in\n%s", w, nos)
		}
		last = i
	}
	// The "*" link is for countries the shop doesn't ship to: not in the DIY section.
	if strings.Contains(nos, "shop.example.org/frame-x") {
		t.Errorf("other-country link in the DIY section:\n%s", nos)
	}
	// Dutch: the localized notice and title; the body falls back to English.
	nl := string(c.StorefrontIndex(index, "https://shop.example.org", "https://app.example.org", "nl"))
	for _, w := range []string{"<p>Wij verkopen omgezette Denver-kaders.</p>", "<h2>Zelf kopen en omzetten</h2>", "Ook bij bol.com", "Zo zet je een kader om"} {
		if !strings.Contains(nl, w) {
			t.Errorf("nl noscript missing %s", w)
		}
	}
	// Without a notice or DIY links: neither, as before.
	plain := string(sampleConfig(t).StorefrontIndex(index, "https://shop.example.org", "https://app.example.org", "en"))
	if strings.Contains(plain, `role="note"`) || strings.Contains(plain, "convert") {
		t.Errorf("plain noscript has notice or DIY:\n%s", plain)
	}
	// Default DIY texts when the shop file has no diy block.
	d, err := Parse([]byte(strings.Replace(diySource(""), sampleDIY, "", 1)), "kinframe")
	if err != nil {
		t.Fatal(err)
	}
	def := string(d.StorefrontIndex(index, "https://shop.example.org", "https://app.example.org", "en"))
	if !strings.Contains(def, "<h2>Buy it yourself and convert it</h2><p>Prefer to do it yourself? Buy a compatible frame and turn it into a kinframe with our free convert tool.</p>") {
		t.Errorf("default DIY texts:\n%s", def)
	}
}
