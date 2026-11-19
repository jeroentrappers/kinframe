package shop

import (
	"html"
	"regexp"
	"slices"
	"strings"

	"golang.org/x/text/language"
)

// Storefront domains (Config.Domains) serve the same web app as the main
// origin, but as the brand's shop: landing page and the /shop pages. The
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

// storefrontOrderNow replaces the "reserve now, pay later" sentence when
// orders are paid online.
var storefrontOrderNow = map[string][2]string{
	"en": {"Reserve now, pay later.", "Order online and pay securely."},
	"nl": {"Nu reserveren, later betalen.", "Bestel online en betaal veilig."},
	"fr": {"Réservez maintenant, payez plus tard.", "Commandez en ligne et payez en toute sécurité."},
	"de": {"Jetzt reservieren, später bezahlen.", "Online bestellen und sicher bezahlen."},
}

// StorefrontMeta is the storefront's page title and description in lang.
func (c *Config) StorefrontMeta(lang string) (title, description string) {
	l := emailLang(lang)
	tx := storefrontTexts[l]
	description = tx[1]
	if c.Checkout() {
		r := storefrontOrderNow[l]
		description = strings.Replace(description, r[0], r[1], 1)
	}
	return strings.ReplaceAll(tx[0], "{brand}", c.Brand), description
}

var (
	titleRe = regexp.MustCompile(`<title>[^<]*</title>`)
	descRe  = regexp.MustCompile(`<meta name="description" content="[^"]*"\s*/?>`)
	langRe  = regexp.MustCompile(`<html lang="[^"]*"`)
	bodyRe  = regexp.MustCompile(`<body[^>]*>`)
)

// StorefrontIndex rewrites the web app's index.html for a storefront host:
// the brand's title, description and language, plus Open Graph tags so a
// shared link previews as the brand rather than the software. origin is the
// storefront's own origin ("https://shop.example.org"), app the web app's
// (KINFRAME_PUBLIC_URL), where the convert tool lives.
func (c *Config) StorefrontIndex(index []byte, origin, app, lang string) []byte {
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
	if loc := bodyRe.FindStringIndex(s); loc != nil {
		s = s[:loc[1]] + c.storefrontNoscript(lang, title, desc, app) + s[loc[1]:]
	}
	return []byte(s)
}

// noscriptTexts label the storefront's plain HTML version.
var noscriptTexts = map[string]map[string]string{
	"en": {
		"products": "Our frames", "vat": "incl. {vat}% VAT", "order": "Order", "reserve": "Reserve",
		"shipping": "Delivery in {countries}. Shipping {flat}, free from {free}.", "shippingFlat": "Delivery in {countries}. Shipping {flat}.",
		"pay": "Payment: {methods}.", "seller": "Seller", "terms": "Terms of sale", "privacy": "Privacy",
		"js": "This shop works best with JavaScript turned on.", "creditcard": "credit card",
		"diyTitle": "Buy it yourself and convert it", "diyBody": "Prefer to do it yourself? Buy a compatible frame and turn it into a {brand} with our free convert tool.",
		"convert": "How to convert a frame", "alsoAt": "Also at {retailer}", "disclosure": "These are affiliate links: we may earn a small commission, at no extra cost to you.",
	},
	"nl": {
		"products": "Onze fotokaders", "vat": "incl. {vat}% btw", "order": "Bestellen", "reserve": "Reserveren",
		"shipping": "Levering in {countries}. Verzending {flat}, gratis vanaf {free}.", "shippingFlat": "Levering in {countries}. Verzending {flat}.",
		"pay": "Betalen: {methods}.", "seller": "Verkoper", "terms": "Verkoopsvoorwaarden", "privacy": "Privacy",
		"js": "Deze winkel werkt het best met JavaScript ingeschakeld.", "creditcard": "kredietkaart",
		"diyTitle": "Zelf kopen en omzetten", "diyBody": "Doe je het liever zelf? Koop een geschikt fotokader en maak er met onze gratis tool zelf een {brand} van.",
		"convert": "Zo zet je een kader om", "alsoAt": "Ook bij {retailer}", "disclosure": "Dit zijn affiliate links: we kunnen een kleine commissie krijgen, zonder extra kosten voor jou.",
	},
	"fr": {
		"products": "Nos cadres", "vat": "TVA {vat} % incl.", "order": "Commander", "reserve": "Réserver",
		"shipping": "Livraison en {countries}. Frais d’envoi {flat}, gratuits dès {free}.", "shippingFlat": "Livraison en {countries}. Frais d’envoi {flat}.",
		"pay": "Paiement : {methods}.", "seller": "Vendeur", "terms": "Conditions de vente", "privacy": "Vie privée",
		"js": "Cette boutique fonctionne mieux avec JavaScript activé.", "creditcard": "carte de crédit",
		"diyTitle": "Achetez-le et convertissez-le vous-même", "diyBody": "Vous préférez le faire vous-même ? Achetez un cadre compatible et transformez-le en {brand} avec notre outil gratuit.",
		"convert": "Comment convertir un cadre", "alsoAt": "Aussi chez {retailer}", "disclosure": "Ce sont des liens affiliés : nous pouvons toucher une petite commission, sans frais supplémentaires pour vous.",
	},
	"de": {
		"products": "Unsere Fotorahmen", "vat": "inkl. {vat} % MwSt.", "order": "Bestellen", "reserve": "Reservieren",
		"shipping": "Lieferung nach {countries}. Versand {flat}, kostenlos ab {free}.", "shippingFlat": "Lieferung nach {countries}. Versand {flat}.",
		"pay": "Zahlung: {methods}.", "seller": "Verkäufer", "terms": "AGB", "privacy": "Datenschutz",
		"js": "Dieser Shop funktioniert am besten mit aktiviertem JavaScript.", "creditcard": "Kreditkarte",
		"diyTitle": "Selbst kaufen und umbauen", "diyBody": "Lieber selbst machen? Kauf einen kompatiblen Rahmen und mach ihn mit unserem kostenlosen Tool selbst zu einem {brand}.",
		"convert": "So wandelst du einen Rahmen um", "alsoAt": "Auch bei {retailer}", "disclosure": "Das sind Affiliate-Links: Wir erhalten eventuell eine kleine Provision, ohne Mehrkosten für dich.",
	},
}

// noscriptDIY is the "buy it yourself and convert it" section: the Elsewhere
// links for the shipping countries (the page can't tell the visitor's country).
func (c *Config) noscriptDIY(b *strings.Builder, l string, tx map[string]string, app string) {
	links := c.DIYLinks()
	if len(links) == 0 {
		return
	}
	e := html.EscapeString
	title, body := tx["diyTitle"], fill(tx["diyBody"], map[string]string{"brand": c.Brand})
	if c.DIY != nil {
		if t := c.DIY.Title.Get(l); t != "" {
			title = t
		}
		if t := c.DIY.Body.Get(l); t != "" {
			body = t
		}
	}
	rel := func(x ElsewhereLink) string {
		if x.Affiliate {
			return "sponsored noopener"
		}
		return "noopener"
	}
	b.WriteString("<section><h2>" + e(title) + "</h2>")
	for _, p := range Paragraphs(body) {
		b.WriteString("<p>" + e(p) + "</p>")
	}
	b.WriteString("<ul>")
	affiliate := false
	for _, x := range links {
		if !x.Secondary {
			b.WriteString(`<li><a href="` + e(x.URL) + `" rel="` + rel(x) + `">` + e(x.Product) + " · " + e(x.Retailer) + "</a></li>")
			affiliate = affiliate || x.Affiliate
		}
	}
	b.WriteString("</ul>")
	for _, x := range links {
		if x.Secondary {
			b.WriteString(`<p><small><a href="` + e(x.URL) + `" rel="` + rel(x) + `">` + e(fill(tx["alsoAt"], map[string]string{"retailer": x.Retailer})) + "</a></small></p>")
			affiliate = affiliate || x.Affiliate
		}
	}
	b.WriteString(`<p><a href="` + e(strings.TrimRight(app, "/")+"/convert") + `">` + e(tx["convert"]) + "</a></p>")
	if affiliate {
		b.WriteString("<p><small>" + e(tx["disclosure"]) + "</small></p>")
	}
	b.WriteString("</section>")
}

// methodNames are the payment methods' display names (creditcard is translated).
var methodNames = map[string]string{
	"bancontact": "Bancontact", "applepay": "Apple Pay", "googlepay": "Google Pay", "ideal": "iDEAL",
	"paypal": "PayPal", "kbc": "KBC/CBC", "belfius": "Belfius", "banktransfer": "SEPA", "klarna": "Klarna",
}

// storefrontNoscript is the shop as plain HTML for visitors and checkers that
// don't run the web app: products and prices, shipping, payment and the
// seller's details with the terms and privacy links.
func (c *Config) storefrontNoscript(lang, title, desc, app string) string {
	l := emailLang(lang)
	tx := noscriptTexts[l]
	e := html.EscapeString
	money := func(cents int) string { return Money(cents, c.Currency, l) }
	var b strings.Builder
	b.WriteString(`<noscript><main style="max-width:40rem;margin:2rem auto;padding:0 1rem;font-family:system-ui,sans-serif;line-height:1.5">`)
	b.WriteString("<h1>" + e(title) + "</h1><p>" + e(desc) + "</p>")
	if n := c.Notice.Get(l); n != "" {
		b.WriteString(`<div role="note" style="border:2px solid #2f6fdd;border-radius:8px;padding:0 1rem;margin:1rem 0;background:#eef4ff;color:#0b1f44">`)
		for _, p := range Paragraphs(n) {
			b.WriteString("<p>" + e(p) + "</p>")
		}
		b.WriteString("</div>")
	}
	c.noscriptDIY(&b, l, tx, app)
	b.WriteString("<h2>" + e(tx["products"]) + "</h2>")
	action := tx["reserve"]
	if c.Checkout() {
		action = tx["order"]
	}
	for _, p := range c.Products {
		b.WriteString("<section><h3>" + e(p.Name) + "</h3>")
		if len(p.Photos) > 0 {
			b.WriteString(`<img src="/v1/shop/media/` + e(p.Photos[0]) + `" alt="` + e(p.Name) + `" width="320" style="max-width:100%;height:auto">`)
		}
		b.WriteString("<p><strong>" + e(money(p.PriceCents)) + "</strong> " + e(fill(tx["vat"], map[string]string{"vat": c.vat()})) + "</p><ul>")
		for _, spec := range p.SpecsFor(l) {
			b.WriteString("<li>" + e(spec) + "</li>")
		}
		b.WriteString(`</ul><p><a href="/shop">` + e(action) + "</a></p></section>")
	}
	countries := make([]string, len(c.Shipping.Countries))
	for i, cc := range c.Shipping.Countries {
		countries[i] = CountryName(cc, l)
	}
	args := map[string]string{"countries": strings.Join(countries, ", "), "flat": money(c.Shipping.FlatCents)}
	ship := tx["shippingFlat"]
	if c.Shipping.FreeFromCents != nil {
		args["free"] = money(*c.Shipping.FreeFromCents)
		ship = tx["shipping"]
	}
	b.WriteString("<p>" + e(fill(ship, args)))
	if c.Payments != nil && len(c.Payments.Methods) > 0 {
		names := make([]string, 0, len(c.Payments.Methods))
		for _, m := range c.Payments.Methods {
			switch n, ok := methodNames[m]; {
			case m == "creditcard":
				names = append(names, tx["creditcard"])
			case ok:
				names = append(names, n)
			default:
				names = append(names, m)
			}
		}
		b.WriteString(" " + e(fill(tx["pay"], map[string]string{"methods": strings.Join(names, ", ")})))
	}
	b.WriteString("</p>")
	s := c.Seller
	b.WriteString("<h2>" + e(tx["seller"]) + "</h2><p>" + e(s.Name))
	if s.Legal != "" {
		b.WriteString("<br>" + e(s.Legal))
	} else {
		if s.Address != "" {
			b.WriteString("<br>" + e(s.Address))
		}
		if s.VATNumber != "" {
			b.WriteString("<br>" + e(s.VATNumber))
		}
	}
	if s.Email != "" {
		b.WriteString(`<br><a href="mailto:` + e(s.Email) + `">` + e(s.Email) + "</a>")
	}
	if s.Phone != "" {
		b.WriteString(`<br><a href="tel:` + e(strings.ReplaceAll(s.Phone, " ", "")) + `">` + e(s.Phone) + "</a>")
	}
	terms := s.TermsURL
	if terms == "" {
		terms = "/shop/terms"
	}
	privacy := s.PrivacyURL
	if privacy == "" {
		privacy = "/privacy"
	}
	b.WriteString(`</p><p><a href="` + e(terms) + `">` + e(tx["terms"]) + `</a> · <a href="` + e(privacy) + `">` + e(tx["privacy"]) + "</a></p>")
	b.WriteString("<p><small>" + e(tx["js"]) + "</small></p></main></noscript>")
	return b.String()
}
