// Package shop is the optional pre-order shop (docs/api/README.md §5.11): the
// operator's configuration (KINFRAME_SHOP_FILE), price and shipping rules,
// reservation references, signed cancel links and the emails sent for a
// reservation. A server without a shop file has no shop at all.
package shop

import (
	"bytes"
	"errors"
	"fmt"
	"maps"
	"net/mail"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"unicode/utf8"

	"gopkg.in/yaml.v3"

	"github.com/jeroentrappers/kinframe/server/internal/i18n"
)

// Config is the operator's shop configuration (YAML or JSON).
type Config struct {
	Enabled bool `yaml:"enabled"`
	// Brand is the consumer-facing name in shop texts and emails ("Get a {brand}").
	// Defaults to the server name.
	Brand string `yaml:"brand"`
	// ReferencePrefix starts every reservation reference ("KF" → "KF-7Q3M").
	ReferencePrefix string `yaml:"reference_prefix"`
	Currency        string `yaml:"currency"`
	// VATPercent is the VAT rate included in all prices (shown as "incl. 21% VAT").
	VATPercent float64   `yaml:"vat_percent"`
	Seller     Seller    `yaml:"seller"`
	Products   []Product `yaml:"products"`
	Shipping   Shipping  `yaml:"shipping"`
	// EstimatedDelivery is free text per language, e.g. {en: "early 2027", nl: "begin 2027"}.
	EstimatedDelivery Localized `yaml:"estimated_delivery"`
	// Notice is shown prominently at the top of the storefront, above the
	// products, per language ("we resell converted frames"). Plain text; a blank
	// line starts a new paragraph.
	Notice Localized `yaml:"notice"`
	// DIY titles the "buy it yourself and convert it" section that visitors in
	// a shipping country see when Elsewhere has links for their country. Nil =
	// the web app's own texts.
	DIY *DIY `yaml:"diy"`
	// Elsewhere links visitors to retailers selling a frame the convert tool
	// supports (affiliate links allowed): outside Shipping.Countries in the
	// "Outside {countries}?" section, and in a shipping country (an entry that
	// lists it) in the DIY section.
	Elsewhere []ElsewhereLink `yaml:"elsewhere"`
	// NotifyEmail receives a message for every new reservation and cancellation.
	NotifyEmail string `yaml:"notify_email"`
	// RetentionDays: reservations are deleted this many days after their last
	// change (cancelled ones after 30 days). Default 365.
	RetentionDays int `yaml:"retention_days"`
	// Domains are extra hostnames ("shop.example.org") that serve the web app
	// as a storefront: the brand's landing page and the shop, without the
	// family app. They proxy to the same server (docs/deploy.md).
	Domains []string `yaml:"domains"`
	// DefaultLocale is the storefront's language for visitors whose browser
	// prefers none of the shop languages (EmailLanguages). Default "en".
	DefaultLocale string `yaml:"default_locale"`
	// Payments turns reservations into paid orders: the customer pays online
	// (KINFRAME_MOLLIE_API_KEY) and the order is confirmed once paid. Nil =
	// reservations without payment.
	Payments *Payments `yaml:"payments"`
	// Stock turns on stock management (dispatch.go); nil = no stock, and the
	// shop shows EstimatedDelivery only.
	Stock *Stock `yaml:"stock"`
}

// Payments configures online payment of orders.
type Payments struct {
	// Provider is the payment provider; only "mollie".
	Provider string `yaml:"provider"`
	// Methods limits the payment methods (Mollie ids: bancontact, creditcard,
	// applepay, ideal, …); empty = every method enabled in the provider's dashboard.
	Methods []string `yaml:"methods"`
}

// Checkout reports whether orders are paid online.
func (c *Config) Checkout() bool { return c.Payments != nil }

type Seller struct {
	Name       string `yaml:"name"`
	Email      string `yaml:"email"`
	Legal      string `yaml:"legal"`
	PrivacyURL string `yaml:"privacy_url"`
	// TermsURL is the terms of sale (required with payments); empty = the
	// web app's own page /shop/terms, built from the seller details.
	TermsURL string `yaml:"terms_url"`
	// Address and VATNumber identify the seller on the terms of sale and in
	// order emails (required with payments).
	Address   string `yaml:"address"`
	VATNumber string `yaml:"vat_number"`
	Phone     string `yaml:"phone"`
}

type Product struct {
	ID         string `yaml:"id"`
	Name       string `yaml:"name"`
	Size       string `yaml:"size"`
	PriceCents int    `yaml:"price_cents"`
	MaxQty     int    `yaml:"max_qty"`
	// Image picks the web illustration: frame-10 (landscape 16:10) or frame-15 (16:9).
	Image string `yaml:"image"`
	// Specs are short bullets per language; "en" is required and the fallback.
	Specs map[string][]string `yaml:"specs"`
	// Photos are product photo file names in the shop's media directory
	// (KINFRAME_SHOP_MEDIA_DIR), the first one leading; served at
	// /v1/shop/media/{name}. Empty = the web illustration (Image).
	Photos []string `yaml:"photos"`
}

// ElsewhereLink is a retailer selling a compatible frame in some countries.
type ElsewhereLink struct {
	// Countries are ISO 3166-1 alpha-2 codes; "*" means every other country.
	Countries []string `yaml:"countries"`
	Retailer  string   `yaml:"retailer"`
	Product   string   `yaml:"product"`
	URL       string   `yaml:"url"`
	// Affiliate marks an affiliate link: the web app shows a disclosure.
	Affiliate bool `yaml:"affiliate"`
	// Secondary shows the link as a small text link ("Also at bol.com") below
	// the main ones instead of a button.
	Secondary bool `yaml:"secondary"`
}

// DIY is the title and text of the "buy it yourself and convert it" section;
// an empty map means the web app's default text.
type DIY struct {
	Title Localized `yaml:"title"`
	Body  Localized `yaml:"body"`
}

// hasPrimaryLink reports whether visitors from country (or "*") get a main,
// non-secondary Elsewhere link.
func (c *Config) hasPrimaryLink(country string) bool {
	return slices.ContainsFunc(c.Elsewhere, func(l ElsewhereLink) bool {
		return !l.Secondary && (slices.Contains(l.Countries, country) || (country != "*" && !c.ShipsTo(country) && slices.Contains(l.Countries, "*")))
	})
}

// DIYLinks are the Elsewhere links for visitors in a shipping country: the
// entries that list one, shown in the "buy it yourself and convert it" section.
func (c *Config) DIYLinks() []ElsewhereLink {
	var out []ElsewhereLink
	for _, l := range c.Elsewhere {
		if slices.ContainsFunc(l.Countries, c.ShipsTo) {
			out = append(out, l)
		}
	}
	return out
}

type Shipping struct {
	Countries []string `yaml:"countries"`
	FlatCents int      `yaml:"flat_cents"`
	// FreeFromCents: an item subtotal of at least this ships free; nil = never.
	FreeFromCents *int `yaml:"free_from_cents"`
}

// Localized maps a language code to a text; "en" is the fallback.
type Localized map[string]string

// Get returns the text for lang, else English.
func (l Localized) Get(lang string) string {
	if v := l[lang]; v != "" {
		return v
	}
	return l["en"]
}

// Paragraphs splits a plain text at blank lines, trimming each paragraph.
func Paragraphs(s string) []string {
	var out []string
	for _, p := range blankLineRe.Split(strings.ReplaceAll(s, "\r\n", "\n"), -1) {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

var blankLineRe = regexp.MustCompile(`\n[ \t]*\n`)

// SpecsFor returns the product's bullets for lang, else English.
func (p Product) SpecsFor(lang string) []string {
	if v := p.Specs[lang]; len(v) > 0 {
		return v
	}
	return p.Specs["en"]
}

// Product returns the product with id.
func (c *Config) Product(id string) (Product, bool) {
	for _, p := range c.Products {
		if p.ID == id {
			return p, true
		}
	}
	return Product{}, false
}

// ShipsTo reports whether country (ISO 3166-1 alpha-2) is served.
func (c *Config) ShipsTo(country string) bool { return slices.Contains(c.Shipping.Countries, country) }

// Images are the illustration keys the web app draws.
var Images = []string{"frame-10", "frame-15"}

var (
	idRe       = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,63}$`)
	photoRe    = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,99}\.(jpg|jpeg|png|webp)$`)
	countryRe  = regexp.MustCompile(`^[A-Z]{2}$`)
	currencyRe = regexp.MustCompile(`^[A-Z]{3}$`)
	prefixRe   = regexp.MustCompile(`^[A-Z]{1,4}$`)
	// A host name with at least two labels, no scheme, port or path.
	domainRe = regexp.MustCompile(`^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$`)
)

// Load reads and validates a shop file. It returns nil, nil when the file
// says enabled: false. serverName is the default brand.
func Load(path, serverName string) (*Config, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return Parse(b, serverName)
}

// Parse decodes and validates a shop configuration (YAML or JSON).
func Parse(b []byte, serverName string) (*Config, error) {
	var c Config
	dec := yaml.NewDecoder(bytes.NewReader(b))
	dec.KnownFields(true)
	if err := dec.Decode(&c); err != nil {
		return nil, fmt.Errorf("shop config: %w", err)
	}
	if !c.Enabled {
		return nil, nil
	}
	if strings.TrimSpace(c.Brand) == "" {
		c.Brand = serverName
	}
	if c.ReferencePrefix == "" {
		c.ReferencePrefix = "KF"
	}
	if c.Currency == "" {
		c.Currency = "EUR"
	}
	if c.RetentionDays == 0 {
		c.RetentionDays = 365
	}
	if c.DefaultLocale == "" {
		c.DefaultLocale = "en"
	}
	if c.Stock != nil && c.Stock.RestockDays == 0 {
		c.Stock.RestockDays = 3
	}
	for i, d := range c.Domains {
		c.Domains[i] = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(d)), ".")
	}
	if err := c.validate(); err != nil {
		return nil, fmt.Errorf("shop config: %w", err)
	}
	return &c, nil
}

func validEmail(s string) bool {
	a, err := mail.ParseAddress(s)
	return err == nil && a.Address == s
}

func (c *Config) validate() error {
	var errs []error
	bad := func(f string, a ...any) { errs = append(errs, fmt.Errorf(f, a...)) }
	if !prefixRe.MatchString(c.ReferencePrefix) {
		bad("reference_prefix must be 1-4 capital letters, got %q", c.ReferencePrefix)
	}
	if !currencyRe.MatchString(c.Currency) {
		bad("currency must be an ISO 4217 code like EUR, got %q", c.Currency)
	}
	if c.VATPercent < 0 || c.VATPercent >= 100 {
		bad("vat_percent must be between 0 and 100")
	}
	if strings.TrimSpace(c.Seller.Name) == "" {
		bad("seller.name is required")
	}
	if !validEmail(c.Seller.Email) {
		bad("seller.email must be an email address, got %q", c.Seller.Email)
	}
	if c.Seller.PrivacyURL != "" {
		if u, err := url.Parse(c.Seller.PrivacyURL); err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
			bad("seller.privacy_url must be an absolute http(s) URL")
		}
	}
	if c.Seller.TermsURL != "" {
		if u, err := url.Parse(c.Seller.TermsURL); err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
			bad("seller.terms_url must be an absolute http(s) URL")
		}
	}
	if p := c.Payments; p != nil {
		if p.Provider != "mollie" {
			bad("payments.provider must be mollie, got %q", p.Provider)
		}
		for _, m := range p.Methods {
			if !idRe.MatchString(m) {
				bad("payments.methods: %q is not a payment method id like bancontact", m)
			}
		}
		if strings.TrimSpace(c.Seller.Address) == "" || strings.TrimSpace(c.Seller.VATNumber) == "" {
			bad("seller.address and seller.vat_number are required with payments (terms of sale, order emails)")
		}
	}
	if !validEmail(c.NotifyEmail) {
		bad("notify_email must be an email address, got %q", c.NotifyEmail)
	}
	if c.RetentionDays < 30 {
		bad("retention_days must be at least 30")
	}
	if len(c.Products) == 0 {
		bad("at least one product is required")
	}
	seen := map[string]bool{}
	for i, p := range c.Products {
		where := fmt.Sprintf("products[%d]", i)
		if !idRe.MatchString(p.ID) {
			bad("%s.id must be lowercase letters, digits and dashes, got %q", where, p.ID)
		}
		if len(p.Photos) > 12 {
			bad("%s.photos: at most 12", where)
		}
		for _, f := range p.Photos {
			if !photoRe.MatchString(f) || strings.Contains(f, "..") {
				bad("%s.photos: %q must be a lowercase file name ending in .jpg, .png or .webp", where, f)
			}
		}
		if seen[p.ID] {
			bad("%s.id %q is used twice", where, p.ID)
		}
		seen[p.ID] = true
		if strings.TrimSpace(p.Name) == "" {
			bad("%s.name is required", where)
		}
		if p.PriceCents <= 0 {
			bad("%s.price_cents must be positive", where)
		}
		if p.MaxQty < 1 || p.MaxQty > 99 {
			bad("%s.max_qty must be between 1 and 99", where)
		}
		if p.Image != "" && !slices.Contains(Images, p.Image) {
			bad("%s.image must be one of %v", where, Images)
		}
		if len(p.Specs) > 0 && len(p.Specs["en"]) == 0 {
			bad("%s.specs needs an en list (the fallback)", where)
		}
	}
	if len(c.Shipping.Countries) == 0 {
		bad("shipping.countries is required")
	}
	for _, cc := range c.Shipping.Countries {
		if !countryRe.MatchString(cc) {
			bad("shipping.countries: %q is not an ISO 3166-1 alpha-2 code like BE", cc)
		}
	}
	if c.Shipping.FlatCents < 0 {
		bad("shipping.flat_cents must not be negative")
	}
	if f := c.Shipping.FreeFromCents; f != nil && *f < 0 {
		bad("shipping.free_from_cents must not be negative")
	}
	if c.Stock != nil && (c.Stock.RestockDays < 0 || c.Stock.RestockDays > 60) {
		bad("stock.restock_days must be between 1 and 60")
	}
	if len(c.EstimatedDelivery) > 0 && c.EstimatedDelivery["en"] == "" {
		bad("estimated_delivery needs an en text (the fallback)")
	}
	checkText := func(where string, l Localized, max int) {
		if len(l) == 0 {
			return
		}
		if strings.TrimSpace(l["en"]) == "" {
			bad("%s needs an en text (the fallback)", where)
		}
		for _, lang := range slices.Sorted(maps.Keys(l)) {
			if !slices.Contains(i18n.Languages, lang) {
				bad("%s: %q is not a language code like nl or zh-TW (docs/i18n.md)", where, lang)
			}
			if n := utf8.RuneCountInString(l[lang]); n > max {
				bad("%s.%s: at most %d characters, got %d", where, lang, max, n)
			}
			if strings.ContainsAny(l[lang], "<>") {
				bad("%s.%s: plain text only, no HTML", where, lang)
			}
		}
	}
	checkText("notice", c.Notice, 2000)
	if c.DIY != nil {
		checkText("diy.title", c.DIY.Title, 120)
		checkText("diy.body", c.DIY.Body, 2000)
	}
	for i, l := range c.Elsewhere {
		where := fmt.Sprintf("elsewhere[%d]", i)
		if len(l.Countries) == 0 {
			bad("%s.countries is required", where)
		}
		for _, cc := range l.Countries {
			if cc != "*" && !countryRe.MatchString(cc) {
				bad("%s.countries: %q is not an ISO 3166-1 alpha-2 code or *", where, cc)
			}
		}
		if strings.TrimSpace(l.Retailer) == "" || strings.TrimSpace(l.Product) == "" {
			bad("%s.retailer and product are required", where)
		}
		if u, err := url.Parse(l.URL); err != nil || u.Scheme != "https" || u.Host == "" {
			bad("%s.url must be an absolute https URL", where)
		}
		if l.Secondary && !slices.ContainsFunc(l.Countries, c.hasPrimaryLink) {
			bad("%s is secondary (a small \"also at\" link) but none of its countries has a main link", where)
		}
	}
	for i, d := range c.Domains {
		if len(d) > 253 || !domainRe.MatchString(d) {
			bad("domains[%d] must be a host name like shop.example.org (no scheme, port or path), got %q", i, d)
		}
		if slices.Index(c.Domains, d) != i {
			bad("domains: %q is listed twice", d)
		}
	}
	if !slices.Contains(EmailLanguages(), c.DefaultLocale) {
		bad("default_locale must be one of %v, got %q", EmailLanguages(), c.DefaultLocale)
	}
	return errors.Join(errs...)
}

// ---------------------------------------------------------------- totals

// Line is one product in a reservation, priced by the server.
type Line struct {
	ProductID string `json:"product_id"`
	Name      string `json:"name"`
	Quantity  int    `json:"quantity"`
	UnitCents int    `json:"unit_cents"`
	LineCents int    `json:"line_cents"`
}

// Quote is a priced order.
type Quote struct {
	Lines         []Line
	SubtotalCents int
	ShippingCents int
	TotalCents    int
}

// Want is a requested quantity of a product.
type Want struct {
	ProductID string
	Quantity  int
}

// ItemError says which requested item is wrong: Code is "enum" for an
// unknown product, "maximum"/"minimum" for a quantity out of range.
type ItemError struct {
	Index int
	Field string // "product_id" or "quantity"
	Code  string
}

// Price prices the wanted items with the configured prices and the shipping
// rule. Lines for the same product are merged, in first-seen order.
func (c *Config) Price(want []Want) (Quote, []ItemError) {
	var q Quote
	var errs []ItemError
	idx := map[string]int{}
	for i, w := range want {
		p, ok := c.Product(w.ProductID)
		if !ok {
			errs = append(errs, ItemError{Index: i, Field: "product_id", Code: "enum"})
			continue
		}
		if w.Quantity < 1 {
			errs = append(errs, ItemError{Index: i, Field: "quantity", Code: "minimum"})
			continue
		}
		if j, ok := idx[p.ID]; ok {
			q.Lines[j].Quantity += w.Quantity
		} else {
			idx[p.ID] = len(q.Lines)
			q.Lines = append(q.Lines, Line{ProductID: p.ID, Name: p.Name, Quantity: w.Quantity, UnitCents: p.PriceCents})
		}
		if q.Lines[idx[p.ID]].Quantity > p.MaxQty {
			errs = append(errs, ItemError{Index: i, Field: "quantity", Code: "maximum"})
		}
	}
	for i := range q.Lines {
		q.Lines[i].LineCents = q.Lines[i].Quantity * q.Lines[i].UnitCents
		q.SubtotalCents += q.Lines[i].LineCents
	}
	q.ShippingCents = c.ShippingFor(q.SubtotalCents)
	q.TotalCents = q.SubtotalCents + q.ShippingCents
	return q, errs
}

// ShippingFor is the shipping cost for an item subtotal.
func (c *Config) ShippingFor(subtotal int) int {
	if subtotal <= 0 {
		return 0
	}
	if f := c.Shipping.FreeFromCents; f != nil && subtotal >= *f {
		return 0
	}
	return c.Shipping.FlatCents
}

// Photo reports whether name is one of the products' photos (only those are
// served from the media directory).
func (c *Config) Photo(name string) bool {
	for _, p := range c.Products {
		if slices.Contains(p.Photos, name) {
			return true
		}
	}
	return false
}

// CheckMedia checks that every product photo is a readable file in dir.
func (c *Config) CheckMedia(dir string) error {
	var errs []error
	for _, p := range c.Products {
		for _, f := range p.Photos {
			if dir == "" {
				return errors.New("products have photos but KINFRAME_SHOP_MEDIA_DIR is not set")
			}
			if st, err := os.Stat(filepath.Join(dir, f)); err != nil || !st.Mode().IsRegular() {
				errs = append(errs, fmt.Errorf("photo %s of %s is not a file in %s", f, p.ID, dir))
			}
		}
	}
	return errors.Join(errs...)
}
