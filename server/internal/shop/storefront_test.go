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
	out := string(c.StorefrontIndex(index, "https://shop.example.org", "nl"))
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
	// Unknown languages fall back to English texts.
	if title, _ := c.StorefrontMeta("ja"); title != "Kader & Co: the photo frame for the whole family" {
		t.Errorf("fallback title %q", title)
	}
}
