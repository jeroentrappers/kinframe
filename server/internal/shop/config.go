// Package shop is the optional pre-order shop (docs/api/README.md §5.10): the
// operator's configuration (KINFRAME_SHOP_FILE), price and shipping rules,
// reservation references, signed cancel links and the emails sent for a
// reservation. A server without a shop file has no shop at all.
package shop

import (
	"bytes"
	"errors"
	"fmt"
	"net/mail"
	"net/url"
	"os"
	"regexp"
	"slices"
	"strings"

	"gopkg.in/yaml.v3"
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
	// NotifyEmail receives a message for every new reservation and cancellation.
	NotifyEmail string `yaml:"notify_email"`
	// RetentionDays: reservations are deleted this many days after their last
	// change (cancelled ones after 30 days). Default 365.
	RetentionDays int `yaml:"retention_days"`
}

type Seller struct {
	Name       string `yaml:"name"`
	Email      string `yaml:"email"`
	Legal      string `yaml:"legal"`
	PrivacyURL string `yaml:"privacy_url"`
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
	countryRe  = regexp.MustCompile(`^[A-Z]{2}$`)
	currencyRe = regexp.MustCompile(`^[A-Z]{3}$`)
	prefixRe   = regexp.MustCompile(`^[A-Z]{1,4}$`)
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
	if len(c.EstimatedDelivery) > 0 && c.EstimatedDelivery["en"] == "" {
		bad("estimated_delivery needs an en text (the fallback)")
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
