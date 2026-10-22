package shop

import (
	"html"
	"regexp"
	"slices"
	"strings"

	"golang.org/x/text/language"
)

// Storefront domains (Config.Domains) serve the same web app as the main
// origin, but as the brand's shop: landing page, /shop and /shop/cancel. The
// API, sign-in and the family app stay on KINFRAME_PUBLIC_URL.

// HostName strips a port from a Host header value and lowercases it.
func HostName(host string) string {
	host = strings.ToLower(strings.TrimSpace(host))
	if strings.HasPrefix(host, "[") { // IPv6 literal
		if i := strings.IndexByte(host, ']'); i > 0 {
			return host[:i+1]
		}
		return host
	}
	if i := strings.LastIndexByte(host, ':'); i >= 0 {
		host = host[:i]
	}
	return strings.TrimSuffix(host, ".")
}

// IsStorefront reports whether host (a Host header, port allowed) is one of
// the configured storefront domains.
func (c *Config) IsStorefront(host string) bool {
	if c == nil || host == "" {
		return false
	}
	return slices.Contains(c.Domains, HostName(host))
}

// StorefrontLang is the storefront's language for an Accept-Language value:
// the first preferred language the shop speaks (EmailLanguages), else
// DefaultLocale. The web app makes the same choice from navigator.languages.
func (c *Config) StorefrontLang(acceptLanguage string) string {
	tags, _, _ := language.ParseAcceptLanguage(acceptLanguage)
	for _, t := range tags {
		base, _ := t.Base()
		if slices.Contains(EmailLanguages(), base.String()) {
			return base.String()
		}
	}
	return c.DefaultLocale
}

// storefrontTexts are the page title and description that link previews
// (which don't run the web app) show for a storefront. Same languages as the
// emails; the web app has the same texts in every language.
var storefrontTexts = map[string][2]string{
	"en": {"{brand}: the photo frame for the whole family",
		"Send photos from your phone and they appear on the frame within seconds. Easy for grandma and grandpa, no subscription. Reserve now, pay later."},
	"nl": {"{brand}: het fotokader voor de hele familie",
		"Stuur foto’s vanaf je smartphone en binnen enkele seconden staan ze op het kader. Eenvoudig voor oma en opa, zonder abonnement. Nu reserveren, later betalen."},
	"fr": {"{brand} : le cadre photo de toute la famille",
		"Envoyez des photos depuis votre smartphone : elles s’affichent sur le cadre en quelques secondes. Simple pour papy et mamie, sans abonnement. Réservez maintenant, payez plus tard."},
	"de": {"{brand}: der Fotorahmen für die ganze Familie",
		"Schick Fotos vom Handy, und Sekunden später sind sie auf dem Rahmen. Einfach für Oma und Opa, ohne Abo. Jetzt reservieren, später bezahlen."},
}

// StorefrontMeta is the storefront's page title and description in lang.
func (c *Config) StorefrontMeta(lang string) (title, description string) {
	tx := storefrontTexts[emailLang(lang)]
	return strings.ReplaceAll(tx[0], "{brand}", c.Brand), tx[1]
}

var (
	titleRe = regexp.MustCompile(`<title>[^<]*</title>`)
	descRe  = regexp.MustCompile(`<meta name="description" content="[^"]*"\s*/?>`)
	langRe  = regexp.MustCompile(`<html lang="[^"]*"`)
)

// StorefrontIndex rewrites the web app's index.html for a storefront host:
// the brand's title, description and language, plus Open Graph tags so a
// shared link previews as the brand rather than the software. origin is the
// storefront's own origin ("https://shop.example.org").
func (c *Config) StorefrontIndex(index []byte, origin, lang string) []byte {
	title, desc := c.StorefrontMeta(lang)
	e := html.EscapeString
	s := string(index)
	s = langRe.ReplaceAllLiteralString(s, `<html lang="`+e(lang)+`"`)
	s = titleRe.ReplaceAllLiteralString(s, "<title>"+e(title)+"</title>")
	s = descRe.ReplaceAllLiteralString(s, `<meta name="description" content="`+e(desc)+`" />`)
	og := `<meta property="og:type" content="website" />` +
		`<meta property="og:site_name" content="` + e(c.Brand) + `" />` +
		`<meta property="og:title" content="` + e(title) + `" />` +
		`<meta property="og:description" content="` + e(desc) + `" />` +
		`<meta property="og:url" content="` + e(origin) + `/" />` +
		`<meta name="twitter:card" content="summary" />`
	if i := strings.Index(s, "</head>"); i >= 0 {
		s = s[:i] + og + s[i:]
	}
	return []byte(s)
}
