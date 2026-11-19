package shop

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Payment is a payment at the provider.
type Payment struct {
	ID          string
	Status      string // open, pending, authorized, paid, failed, canceled, expired
	CheckoutURL string // where the customer pays; empty once the payment is final
	Reference   string // metadata.reference: the order it belongs to
}

// PaymentRequest is a new payment for an order.
type PaymentRequest struct {
	AmountCents int
	Currency    string
	Description string
	Reference   string
	RedirectURL string
	WebhookURL  string // empty: no webhook (local development); the status is then read on the order page
	Locale      string // a supported language; mapped to the provider's locale
	Methods     []string
}

// PaymentProvider creates and reads payments.
type PaymentProvider interface {
	Create(ctx context.Context, r PaymentRequest) (*Payment, error)
	Get(ctx context.Context, id string) (*Payment, error)
}

// Mollie is the Mollie Payments API (https://docs.mollie.com/reference/payments-api).
type Mollie struct {
	APIKey  string
	BaseURL string // default https://api.mollie.com/v2
	HTTP    *http.Client
}

// ErrPayment is a payment provider failure.
var ErrPayment = errors.New("payment provider error")

// TestMode reports whether the key is a Mollie test key (no real money).
func (m *Mollie) TestMode() bool { return strings.HasPrefix(m.APIKey, "test_") }

type mollieAmount struct {
	Currency string `json:"currency"`
	Value    string `json:"value"`
}

type molliePayment struct {
	ID       string            `json:"id"`
	Status   string            `json:"status"`
	Metadata map[string]string `json:"metadata"`
	Links    struct {
		Checkout *struct {
			Href string `json:"href"`
		} `json:"checkout"`
	} `json:"_links"`
}

func (p molliePayment) payment() *Payment {
	out := &Payment{ID: p.ID, Status: p.Status, Reference: p.Metadata["reference"]}
	if p.Links.Checkout != nil {
		out.CheckoutURL = p.Links.Checkout.Href
	}
	return out
}

// mollieLocales maps the shop languages to Mollie's checkout locales.
var mollieLocales = map[string]string{"en": "en_US", "nl": "nl_BE", "fr": "fr_BE", "de": "de_DE"}

// Amount formats cents as a decimal string with two decimals ("99.00").
func Amount(cents int) string { return fmt.Sprintf("%d.%02d", cents/100, cents%100) }

func (m *Mollie) Create(ctx context.Context, r PaymentRequest) (*Payment, error) {
	body := map[string]any{
		"amount":      mollieAmount{Currency: r.Currency, Value: Amount(r.AmountCents)},
		"description": r.Description,
		"redirectUrl": r.RedirectURL,
		"metadata":    map[string]string{"reference": r.Reference},
	}
	if r.WebhookURL != "" {
		body["webhookUrl"] = r.WebhookURL
	}
	if l := mollieLocales[r.Locale]; l != "" {
		body["locale"] = l
	}
	if len(r.Methods) > 0 {
		body["method"] = r.Methods
	}
	var p molliePayment
	if err := m.call(ctx, http.MethodPost, "/payments", body, &p); err != nil {
		return nil, err
	}
	return p.payment(), nil
}

func (m *Mollie) Get(ctx context.Context, id string) (*Payment, error) {
	var p molliePayment
	if err := m.call(ctx, http.MethodGet, "/payments/"+id, nil, &p); err != nil {
		return nil, err
	}
	return p.payment(), nil
}

func (m *Mollie) call(ctx context.Context, method, path string, body, out any) error {
	base := m.BaseURL
	if base == "" {
		base = "https://api.mollie.com/v2"
	}
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, base+path, rd)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+m.APIKey)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	hc := m.HTTP
	if hc == nil {
		hc = &http.Client{Timeout: 20 * time.Second}
	}
	resp, err := hc.Do(req)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrPayment, err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 300 {
		var e struct {
			Title  string `json:"title"`
			Detail string `json:"detail"`
			Field  string `json:"field"`
		}
		_ = json.Unmarshal(b, &e)
		return fmt.Errorf("%w: %s %s: %d %s %s %s", ErrPayment, method, path, resp.StatusCode, e.Title, e.Detail, e.Field)
	}
	return json.Unmarshal(b, out)
}
