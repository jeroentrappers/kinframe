package shop

import (
	"fmt"
	"html"
	"net/mail"
	"strconv"
	"strings"

	"golang.org/x/text/language"
	"golang.org/x/text/language/display"
)

// Reservation is what the emails need to know about a reservation.
type Reservation struct {
	Reference  string
	Name       string
	Email      string
	Country    string
	PostalCode string
	Note       string
	Lang       string // a supported language (package i18n); emails fall back to English
	Quote      Quote
	CancelURL  string
}

// emailTexts are the customer email texts. English is the fallback for
// languages without their own texts.
var emailTexts = map[string]map[string]string{
	"en": {
		"subject":   "Your {brand} reservation {reference}",
		"greeting":  "Hi {name},",
		"intro":     "Thank you for reserving a {brand}! This is what we noted down for you:",
		"reference": "Reservation number",
		"shipping":  "Shipping to {country}",
		"free":      "free",
		"total":     "Total (incl. {vat}% VAT)",
		"nopay":     "You don’t pay anything now and there is no obligation. As soon as the frames are in stock, we’ll email you the final price, the delivery date and a payment link. Only then do you decide.",
		"delivery":  "Estimated delivery: {delivery}.",
		"note":      "Your note",
		"cancel":    "Changed your mind? You can cancel your reservation with this link:",
		"cancelBtn": "Cancel my reservation",
		"questions": "Questions? Just reply to this email.",
		"signoff":   "Kind regards,",
		"privacy":   "We only use your details to contact you about this reservation.",
	},
	"nl": {
		"subject":   "Je reservatie voor een {brand}: {reference}",
		"greeting":  "Dag {name},",
		"intro":     "Bedankt voor je reservatie van een {brand}! Dit hebben we voor je genoteerd:",
		"reference": "Reservatienummer",
		"shipping":  "Verzending naar {country}",
		"free":      "gratis",
		"total":     "Totaal (incl. {vat}% btw)",
		"nopay":     "Je betaalt nu niets en zit nergens aan vast. Zodra de fotokaders op voorraad zijn, mailen we je de definitieve prijs, de leverdatum en een betaallink. Pas dan beslis je.",
		"delivery":  "Verwachte levering: {delivery}.",
		"note":      "Je opmerking",
		"cancel":    "Toch van gedachten veranderd? Met deze link annuleer je je reservatie:",
		"cancelBtn": "Mijn reservatie annuleren",
		"questions": "Vragen? Antwoord gewoon op deze e-mail.",
		"signoff":   "Vriendelijke groeten,",
		"privacy":   "We gebruiken je gegevens alleen om je over deze reservatie te contacteren.",
	},
	"fr": {
		"subject":   "Votre réservation {brand} : {reference}",
		"greeting":  "Bonjour {name},",
		"intro":     "Merci d’avoir réservé un {brand} ! Voici ce que nous avons noté pour vous :",
		"reference": "Numéro de réservation",
		"shipping":  "Livraison ({country})",
		"free":      "gratuite",
		"total":     "Total (TVA {vat} % comprise)",
		"nopay":     "Vous ne payez rien maintenant et vous n’êtes engagé à rien. Dès que les cadres seront en stock, nous vous enverrons par e-mail le prix définitif, la date de livraison et un lien de paiement. C’est seulement à ce moment-là que vous décidez.",
		"delivery":  "Livraison estimée : {delivery}.",
		"note":      "Votre remarque",
		"cancel":    "Vous avez changé d’avis ? Ce lien annule votre réservation :",
		"cancelBtn": "Annuler ma réservation",
		"questions": "Des questions ? Répondez simplement à cet e-mail.",
		"signoff":   "Bien cordialement,",
		"privacy":   "Nous utilisons vos données uniquement pour vous contacter au sujet de cette réservation.",
	},
	"de": {
		"subject":   "Ihre {brand}-Reservierung {reference}",
		"greeting":  "Hallo {name},",
		"intro":     "Vielen Dank für Ihre Reservierung eines {brand}! Das haben wir für Sie notiert:",
		"reference": "Reservierungsnummer",
		"shipping":  "Versand ({country})",
		"free":      "kostenlos",
		"total":     "Gesamt (inkl. {vat} % MwSt.)",
		"nopay":     "Sie zahlen jetzt nichts und gehen keine Verpflichtung ein. Sobald die Bilderrahmen auf Lager sind, schicken wir Ihnen per E-Mail den endgültigen Preis, das Lieferdatum und einen Zahlungslink. Erst dann entscheiden Sie.",
		"delivery":  "Voraussichtliche Lieferung: {delivery}.",
		"note":      "Ihre Anmerkung",
		"cancel":    "Doch anders entschieden? Mit diesem Link stornieren Sie Ihre Reservierung:",
		"cancelBtn": "Meine Reservierung stornieren",
		"questions": "Fragen? Antworten Sie einfach auf diese E-Mail.",
		"signoff":   "Freundliche Grüße",
		"privacy":   "Wir verwenden Ihre Daten nur, um Sie zu dieser Reservierung zu kontaktieren.",
	},
}

// EmailLanguages are the languages with their own email texts.
func EmailLanguages() []string { return []string{"en", "nl", "fr", "de"} }

func emailLang(lang string) string {
	if _, ok := emailTexts[lang]; ok {
		return lang
	}
	return "en"
}

func fill(s string, args map[string]string) string {
	for k, v := range args {
		s = strings.ReplaceAll(s, "{"+k+"}", v)
	}
	return s
}

// CountryName is the country's name in lang ("België", "Pays-Bas").
func CountryName(code, lang string) string {
	r, err := language.ParseRegion(code)
	if err != nil {
		return code
	}
	tag, err := language.Parse(lang)
	if err != nil {
		tag = language.English
	}
	if n := display.Regions(tag).Name(r); n != "" {
		return n
	}
	return code
}

func (c *Config) vat() string { return strconv.FormatFloat(c.VATPercent, 'f', -1, 64) }

// CustomerEmail is the confirmation sent to the customer in their language.
func (c *Config) CustomerEmail(r Reservation) Message {
	lang := emailLang(r.Lang)
	tx := emailTexts[lang]
	T := func(k string, args map[string]string) string { return fill(tx[k], args) }
	money := func(cents int) string { return Money(cents, c.Currency, lang) }
	brand := c.Brand
	ship := money(r.Quote.ShippingCents)
	if r.Quote.ShippingCents == 0 {
		ship = tx["free"]
	}
	shipLabel := T("shipping", map[string]string{"country": CountryName(r.Country, lang)})
	totalLabel := T("total", map[string]string{"vat": c.vat()})
	delivery := ""
	if d := c.EstimatedDelivery.Get(lang); d != "" {
		delivery = T("delivery", map[string]string{"delivery": d})
	}

	var t strings.Builder
	t.WriteString(T("greeting", map[string]string{"name": r.Name}) + "\n\n")
	t.WriteString(T("intro", map[string]string{"brand": brand}) + "\n\n")
	t.WriteString(tx["reference"] + ": " + r.Reference + "\n\n")
	for _, l := range r.Quote.Lines {
		fmt.Fprintf(&t, "  %d × %s  %s\n", l.Quantity, l.Name, money(l.LineCents))
	}
	fmt.Fprintf(&t, "  %s  %s\n", shipLabel, ship)
	fmt.Fprintf(&t, "  %s  %s\n\n", totalLabel, money(r.Quote.TotalCents))
	if r.Note != "" {
		t.WriteString(tx["note"] + ": " + r.Note + "\n\n")
	}
	t.WriteString(tx["nopay"] + "\n")
	if delivery != "" {
		t.WriteString(delivery + "\n")
	}
	t.WriteString("\n" + tx["cancel"] + "\n" + r.CancelURL + "\n\n")
	t.WriteString(tx["questions"] + "\n\n" + tx["signoff"] + "\n" + c.Seller.Name + "\n\n")
	t.WriteString("-- \n" + tx["privacy"] + "\n")
	if c.Seller.Legal != "" {
		t.WriteString(c.Seller.Legal + "\n")
	}

	e := html.EscapeString
	var h strings.Builder
	h.WriteString(`<!doctype html><html lang="` + lang + `"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>` +
		e(T("subject", map[string]string{"brand": brand, "reference": r.Reference})) + `</title></head>` +
		`<body style="margin:0;padding:24px 12px;background:#f6f1ea;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#2b2420;line-height:1.5">` +
		`<div style="max-width:560px;margin:0 auto;background:#fffdf9;border-radius:16px;padding:28px;border:1px solid #eadfd2">`)
	fmt.Fprintf(&h, `<p style="margin:0 0 12px">%s</p>`, e(T("greeting", map[string]string{"name": r.Name})))
	fmt.Fprintf(&h, `<p style="margin:0 0 16px">%s</p>`, e(T("intro", map[string]string{"brand": brand})))
	fmt.Fprintf(&h, `<p style="margin:0 0 16px">%s: <strong style="font-size:1.15em;letter-spacing:.04em">%s</strong></p>`, e(tx["reference"]), e(r.Reference))
	h.WriteString(`<table role="presentation" style="width:100%;border-collapse:collapse;margin:0 0 16px">`)
	row := func(label, value string, strong bool) {
		style := "padding:6px 0;border-bottom:1px solid #eadfd2"
		if strong {
			style += ";font-weight:700"
		}
		fmt.Fprintf(&h, `<tr><td style="%s">%s</td><td style="%s;text-align:right;white-space:nowrap">%s</td></tr>`, style, e(label), style, e(value))
	}
	for _, l := range r.Quote.Lines {
		row(fmt.Sprintf("%d × %s", l.Quantity, l.Name), money(l.LineCents), false)
	}
	row(shipLabel, ship, false)
	row(totalLabel, money(r.Quote.TotalCents), true)
	h.WriteString(`</table>`)
	if r.Note != "" {
		fmt.Fprintf(&h, `<p style="margin:0 0 16px;color:#6b5f57">%s: %s</p>`, e(tx["note"]), e(r.Note))
	}
	fmt.Fprintf(&h, `<p style="margin:0 0 12px;padding:12px 14px;background:#eef4ec;border-radius:10px">%s`, e(tx["nopay"]))
	if delivery != "" {
		fmt.Fprintf(&h, `<br><strong>%s</strong>`, e(delivery))
	}
	h.WriteString(`</p>`)
	fmt.Fprintf(&h, `<p style="margin:16px 0 6px">%s</p><p style="margin:0 0 16px"><a href="%s" style="color:#b4532a">%s</a></p>`,
		e(tx["cancel"]), e(r.CancelURL), e(tx["cancelBtn"]))
	fmt.Fprintf(&h, `<p style="margin:0 0 16px">%s</p><p style="margin:0">%s<br>%s</p>`, e(tx["questions"]), e(tx["signoff"]), e(c.Seller.Name))
	fmt.Fprintf(&h, `<p style="margin:24px 0 0;font-size:12px;color:#8a7d73">%s`, e(tx["privacy"]))
	if c.Seller.Legal != "" {
		fmt.Fprintf(&h, `<br>%s`, e(c.Seller.Legal))
	}
	h.WriteString(`</p></div></body></html>`)

	return Message{
		To:      (&mail.Address{Name: r.Name, Address: r.Email}).String(),
		ReplyTo: (&mail.Address{Name: c.Seller.Name, Address: c.Seller.Email}).String(),
		Subject: T("subject", map[string]string{"brand": brand, "reference": r.Reference}),
		Text:    t.String(),
		HTML:    h.String(),
	}
}

// OperatorEmail tells the operator about a new or cancelled reservation
// (English; it goes to the operator's own inbox). adminURL is the web
// page listing reservations.
func (c *Config) OperatorEmail(r Reservation, cancelled bool, adminURL string) Message {
	money := func(cents int) string { return Money(cents, c.Currency, "en") }
	var items []string
	for _, l := range r.Quote.Lines {
		items = append(items, fmt.Sprintf("%d × %s", l.Quantity, l.Name))
	}
	subject := fmt.Sprintf("New reservation %s: %s (%s)", r.Reference, strings.Join(items, ", "), r.Country)
	lead := "A new reservation came in."
	if cancelled {
		subject = fmt.Sprintf("Reservation %s cancelled by the customer", r.Reference)
		lead = "The customer cancelled this reservation with the link in their email."
	}
	var t strings.Builder
	t.WriteString(lead + "\n\n")
	fmt.Fprintf(&t, "Reference: %s\nName: %s\nEmail: %s\nCountry: %s\n", r.Reference, r.Name, r.Email, r.Country)
	if r.PostalCode != "" {
		fmt.Fprintf(&t, "Postal code: %s\n", r.PostalCode)
	}
	fmt.Fprintf(&t, "Language: %s\n\n", r.Lang)
	for _, l := range r.Quote.Lines {
		fmt.Fprintf(&t, "  %d × %s  %s\n", l.Quantity, l.Name, money(l.LineCents))
	}
	fmt.Fprintf(&t, "  Shipping  %s\n  Total  %s\n\n", money(r.Quote.ShippingCents), money(r.Quote.TotalCents))
	if r.Note != "" {
		fmt.Fprintf(&t, "Note: %s\n\n", r.Note)
	}
	fmt.Fprintf(&t, "All reservations: %s\n", adminURL)

	e := html.EscapeString
	htm := `<!doctype html><html lang="en"><head><meta charset="utf-8"></head><body style="font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#2b2420;line-height:1.5">` +
		`<pre style="font:inherit;white-space:pre-wrap">` + e(t.String()) + `</pre>` +
		`<p><a href="` + e(adminURL) + `">Open the reservations</a></p></body></html>`
	return Message{
		To:      c.NotifyEmail,
		ReplyTo: (&mail.Address{Name: r.Name, Address: r.Email}).String(),
		Subject: subject,
		Text:    t.String(),
		HTML:    htm,
	}
}
