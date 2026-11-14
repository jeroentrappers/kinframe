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
	// Order: a paid order (shops with payments) rather than a reservation.
	Order  bool
	Street string
	City   string
	Phone  string
	// TrackingURL is the carrier's tracking link of a shipped order.
	TrackingURL string
}

// address is the delivery address on one line per part.
func (r Reservation) address(lang string) []string {
	var out []string
	if r.Street != "" {
		out = append(out, r.Street)
	}
	if pc := strings.TrimSpace(r.PostalCode + " " + r.City); pc != "" {
		out = append(out, pc)
	}
	return append(out, CountryName(r.Country, lang))
}

// emailTexts are the customer email texts. English is the fallback for
// languages without their own texts.
var emailTexts = map[string]map[string]string{
	"en": {
		"subject":     "Your {brand} reservation {reference}",
		"greeting":    "Hi {name},",
		"intro":       "Thank you for reserving a {brand}! This is what we noted down for you:",
		"reference":   "Reservation number",
		"shipping":    "Shipping to {country}",
		"free":        "free",
		"total":       "Total (incl. {vat}% VAT)",
		"nopay":       "You don’t pay anything now and there is no obligation. As soon as the frames are in stock, we’ll email you the final price, the delivery date and a payment link. Only then do you decide.",
		"delivery":    "Estimated delivery: {delivery}.",
		"note":        "Your note",
		"cancel":      "Changed your mind? You can cancel your reservation with this link:",
		"cancelBtn":   "Cancel my reservation",
		"questions":   "Questions? Just reply to this email.",
		"signoff":     "Kind regards,",
		"privacy":     "We only use your details to contact you about this reservation.",
		"o.subject":   "Your {brand} order {reference}",
		"o.intro":     "Thank you for your order! This is what you bought:",
		"o.reference": "Order number",
		"o.nopay":     "We received your payment. We’ll email you as soon as your order is on its way.",
		"o.address":   "Delivery address",
		"o.cancel":    "Changed your mind? You may withdraw from your purchase within 14 days after delivery, without giving a reason. Use this link or reply to this email, and we’ll refund you within 14 days:",
		"o.cancelBtn": "Withdraw from my order",
		"o.privacy":   "We only use your details to deliver and handle this order.",
		"s.subject":   "Your {brand} order {reference} is on its way",
		"s.intro":     "Good news: your order is on its way to you.",
		"s.track":     "Track your parcel:",
		"s.trackBtn":  "Track my parcel",
		"s.withdraw":  "You may withdraw from your purchase within 14 days after delivery, without giving a reason. Use this link or reply to this email:",
	},
	"nl": {
		"subject":     "Je reservatie voor een {brand}: {reference}",
		"greeting":    "Dag {name},",
		"intro":       "Bedankt voor je reservatie van een {brand}! Dit hebben we voor je genoteerd:",
		"reference":   "Reservatienummer",
		"shipping":    "Verzending naar {country}",
		"free":        "gratis",
		"total":       "Totaal (incl. {vat}% btw)",
		"nopay":       "Je betaalt nu niets en zit nergens aan vast. Zodra de fotokaders op voorraad zijn, mailen we je de definitieve prijs, de leverdatum en een betaallink. Pas dan beslis je.",
		"delivery":    "Verwachte levering: {delivery}.",
		"note":        "Je opmerking",
		"cancel":      "Toch van gedachten veranderd? Met deze link annuleer je je reservatie:",
		"cancelBtn":   "Mijn reservatie annuleren",
		"questions":   "Vragen? Antwoord gewoon op deze e-mail.",
		"signoff":     "Vriendelijke groeten,",
		"privacy":     "We gebruiken je gegevens alleen om je over deze reservatie te contacteren.",
		"o.subject":   "Je bestelling van een {brand}: {reference}",
		"o.intro":     "Bedankt voor je bestelling! Dit heb je gekocht:",
		"o.reference": "Bestelnummer",
		"o.nopay":     "We hebben je betaling ontvangen. We mailen je zodra je bestelling onderweg is.",
		"o.address":   "Leveradres",
		"o.cancel":    "Toch van gedachten veranderd? Je kunt je aankoop binnen 14 dagen na de levering herroepen, zonder opgave van reden. Gebruik deze link of antwoord op deze e-mail, dan betalen we je binnen 14 dagen terug:",
		"o.cancelBtn": "Mijn bestelling herroepen",
		"o.privacy":   "We gebruiken je gegevens alleen om deze bestelling te leveren en af te handelen.",
		"s.subject":   "Je bestelling {reference} is onderweg",
		"s.intro":     "Goed nieuws: je bestelling is onderweg naar je.",
		"s.track":     "Volg je pakje:",
		"s.trackBtn":  "Volg mijn pakje",
		"s.withdraw":  "Je kunt je aankoop binnen 14 dagen na de levering herroepen, zonder opgave van reden. Gebruik deze link of antwoord op deze e-mail:",
	},
	"fr": {
		"subject":     "Votre réservation {brand} : {reference}",
		"greeting":    "Bonjour {name},",
		"intro":       "Merci d’avoir réservé un {brand} ! Voici ce que nous avons noté pour vous :",
		"reference":   "Numéro de réservation",
		"shipping":    "Livraison ({country})",
		"free":        "gratuite",
		"total":       "Total (TVA {vat} % comprise)",
		"nopay":       "Vous ne payez rien maintenant et vous n’êtes engagé à rien. Dès que les cadres seront en stock, nous vous enverrons par e-mail le prix définitif, la date de livraison et un lien de paiement. C’est seulement à ce moment-là que vous décidez.",
		"delivery":    "Livraison estimée : {delivery}.",
		"note":        "Votre remarque",
		"cancel":      "Vous avez changé d’avis ? Ce lien annule votre réservation :",
		"cancelBtn":   "Annuler ma réservation",
		"questions":   "Des questions ? Répondez simplement à cet e-mail.",
		"signoff":     "Bien cordialement,",
		"privacy":     "Nous utilisons vos données uniquement pour vous contacter au sujet de cette réservation.",
		"o.subject":   "Votre commande {brand} : {reference}",
		"o.intro":     "Merci pour votre commande ! Voici ce que vous avez acheté :",
		"o.reference": "Numéro de commande",
		"o.nopay":     "Nous avons bien reçu votre paiement. Nous vous écrirons dès que votre commande sera en route.",
		"o.address":   "Adresse de livraison",
		"o.cancel":    "Vous avez changé d’avis ? Vous pouvez vous rétracter dans les 14 jours suivant la livraison, sans donner de motif. Utilisez ce lien ou répondez à cet e-mail, et nous vous rembourserons dans les 14 jours :",
		"o.cancelBtn": "Me rétracter de ma commande",
		"o.privacy":   "Nous utilisons vos données uniquement pour livrer et traiter cette commande.",
		"s.subject":   "Votre commande {reference} est en route",
		"s.intro":     "Bonne nouvelle : votre commande est en route.",
		"s.track":     "Suivez votre colis :",
		"s.trackBtn":  "Suivre mon colis",
		"s.withdraw":  "Vous pouvez vous rétracter dans les 14 jours suivant la livraison, sans donner de motif. Utilisez ce lien ou répondez à cet e-mail :",
	},
	"de": {
		"subject":     "Deine {brand}-Reservierung {reference}",
		"greeting":    "Hallo {name},",
		"intro":       "Danke für deine Reservierung! Das haben wir für dich notiert ({brand}):",
		"reference":   "Reservierungsnummer",
		"shipping":    "Versand ({country})",
		"free":        "kostenlos",
		"total":       "Gesamt (inkl. {vat} % MwSt.)",
		"nopay":       "Du zahlst jetzt nichts und gehst keine Verpflichtung ein. Sobald die Rahmen auf Lager sind, schicken wir dir per E-Mail den endgültigen Preis, das Lieferdatum und einen Zahlungslink. Erst dann entscheidest du.",
		"delivery":    "Voraussichtliche Lieferung: {delivery}.",
		"note":        "Deine Anmerkung",
		"cancel":      "Doch anders entschieden? Mit diesem Link stornierst du deine Reservierung:",
		"cancelBtn":   "Meine Reservierung stornieren",
		"questions":   "Fragen? Antworte einfach auf diese E-Mail.",
		"signoff":     "Viele Grüße",
		"privacy":     "Wir verwenden deine Daten nur, um dich zu dieser Reservierung zu kontaktieren.",
		"o.subject":   "Deine {brand}-Bestellung {reference}",
		"o.intro":     "Danke für deine Bestellung! Das hast du gekauft:",
		"o.reference": "Bestellnummer",
		"o.nopay":     "Wir haben deine Zahlung erhalten. Wir schreiben dir, sobald deine Bestellung unterwegs ist.",
		"o.address":   "Lieferadresse",
		"o.cancel":    "Doch anders entschieden? Du kannst deinen Kauf innerhalb von 14 Tagen nach der Lieferung ohne Angabe von Gründen widerrufen. Nutze diesen Link oder antworte auf diese E-Mail, dann erstatten wir dir den Betrag innerhalb von 14 Tagen:",
		"o.cancelBtn": "Meine Bestellung widerrufen",
		"o.privacy":   "Wir verwenden deine Daten nur, um diese Bestellung zu liefern und abzuwickeln.",
		"s.subject":   "Deine Bestellung {reference} ist unterwegs",
		"s.intro":     "Gute Nachricht: Deine Bestellung ist auf dem Weg zu dir.",
		"s.track":     "Verfolge deine Sendung:",
		"s.trackBtn":  "Sendung verfolgen",
		"s.withdraw":  "Du kannst deinen Kauf innerhalb von 14 Tagen nach der Lieferung ohne Angabe von Gründen widerrufen. Nutze diesen Link oder antworte auf diese E-Mail:",
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
	tx := map[string]string{}
	for k, v := range emailTexts[lang] {
		tx[k] = v
	}
	if r.Order {
		for _, k := range []string{"subject", "intro", "reference", "nopay", "cancel", "cancelBtn", "privacy"} {
			tx[k] = tx["o."+k]
		}
	}
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
	if r.Order {
		t.WriteString(tx["o.address"] + ":\n  " + r.Name + "\n  " + strings.Join(r.address(lang), "\n  ") + "\n\n")
	}
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
	if r.Order {
		parts := []string{e(r.Name)}
		for _, a := range r.address(lang) {
			parts = append(parts, e(a))
		}
		fmt.Fprintf(&h, `<p style="margin:0 0 16px"><strong>%s</strong><br>%s</p>`, e(tx["o.address"]), strings.Join(parts, "<br>"))
	}
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

// ShippedEmail tells the customer their paid order is on its way.
func (c *Config) ShippedEmail(r Reservation) Message {
	lang := emailLang(r.Lang)
	tx := emailTexts[lang]
	args := map[string]string{"brand": c.Brand, "reference": r.Reference, "name": r.Name}
	subject := fill(tx["s.subject"], args)
	addr := append([]string{r.Name}, r.address(lang)...)

	var t strings.Builder
	t.WriteString(fill(tx["greeting"], args) + "\n\n" + tx["s.intro"] + "\n\n")
	t.WriteString(tx["o.reference"] + ": " + r.Reference + "\n\n")
	if r.TrackingURL != "" {
		t.WriteString(tx["s.track"] + "\n" + r.TrackingURL + "\n\n")
	}
	t.WriteString(tx["o.address"] + ":\n  " + strings.Join(addr, "\n  ") + "\n\n")
	t.WriteString(tx["s.withdraw"] + "\n" + r.CancelURL + "\n\n")
	t.WriteString(tx["questions"] + "\n\n" + tx["signoff"] + "\n" + c.Seller.Name + "\n")
	if c.Seller.Legal != "" {
		t.WriteString("\n-- \n" + c.Seller.Legal + "\n")
	}

	e := html.EscapeString
	var esc []string
	for _, a := range addr {
		esc = append(esc, e(a))
	}
	var h strings.Builder
	h.WriteString(`<!doctype html><html lang="` + lang + `"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>` + e(subject) + `</title></head>` +
		`<body style="margin:0;padding:24px 12px;background:#f6f1ea;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#2b2420;line-height:1.5">` +
		`<div style="max-width:560px;margin:0 auto;background:#fffdf9;border-radius:16px;padding:28px;border:1px solid #eadfd2">`)
	fmt.Fprintf(&h, `<p style="margin:0 0 12px">%s</p><p style="margin:0 0 16px">%s</p>`, e(fill(tx["greeting"], args)), e(tx["s.intro"]))
	fmt.Fprintf(&h, `<p style="margin:0 0 16px">%s: <strong>%s</strong></p>`, e(tx["o.reference"]), e(r.Reference))
	if r.TrackingURL != "" {
		fmt.Fprintf(&h, `<p style="margin:0 0 20px"><a href="%s" style="display:inline-block;background:#b4532a;color:#fff;text-decoration:none;padding:10px 18px;border-radius:10px;font-weight:600">%s</a></p>`,
			e(r.TrackingURL), e(tx["s.trackBtn"]))
	}
	fmt.Fprintf(&h, `<p style="margin:0 0 16px"><strong>%s</strong><br>%s</p>`, e(tx["o.address"]), strings.Join(esc, "<br>"))
	fmt.Fprintf(&h, `<p style="margin:0 0 6px">%s</p><p style="margin:0 0 16px"><a href="%s" style="color:#b4532a">%s</a></p>`,
		e(tx["s.withdraw"]), e(r.CancelURL), e(tx["o.cancelBtn"]))
	fmt.Fprintf(&h, `<p style="margin:0 0 16px">%s</p><p style="margin:0">%s<br>%s</p>`, e(tx["questions"]), e(tx["signoff"]), e(c.Seller.Name))
	if c.Seller.Legal != "" {
		fmt.Fprintf(&h, `<p style="margin:24px 0 0;font-size:12px;color:#8a7d73">%s</p>`, e(c.Seller.Legal))
	}
	h.WriteString(`</div></body></html>`)
	return Message{
		To:      (&mail.Address{Name: r.Name, Address: r.Email}).String(),
		ReplyTo: (&mail.Address{Name: c.Seller.Name, Address: c.Seller.Email}).String(),
		Subject: subject, Text: t.String(), HTML: h.String(),
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
	switch {
	case cancelled && r.Order:
		subject = fmt.Sprintf("Order %s withdrawn by the customer", r.Reference)
		lead = "The customer withdrew from this paid order with the link in their email. Refund it in the payment provider's dashboard (within 14 days) once the frame is back, or right away if it hasn't shipped."
	case cancelled:
		subject = fmt.Sprintf("Reservation %s cancelled by the customer", r.Reference)
		lead = "The customer cancelled this reservation with the link in their email."
	case r.Order:
		subject = fmt.Sprintf("Paid order %s: %s (%s)", r.Reference, strings.Join(items, ", "), r.Country)
		lead = "A new order was paid. Ship it to the address below and set it to shipped."
	}
	var t strings.Builder
	t.WriteString(lead + "\n\n")
	fmt.Fprintf(&t, "Reference: %s\nName: %s\nEmail: %s\nCountry: %s\n", r.Reference, r.Name, r.Email, r.Country)
	if r.Order {
		fmt.Fprintf(&t, "Address: %s\n", strings.Join(r.address("en"), ", "))
		if r.Phone != "" {
			fmt.Fprintf(&t, "Phone: %s\n", r.Phone)
		}
	} else if r.PostalCode != "" {
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
