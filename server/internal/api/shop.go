package api

import (
	"context"
	"encoding/csv"
	"fmt"
	"net"
	"net/http"
	"net/mail"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	openapi_types "github.com/oapi-codegen/runtime/types"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/i18n"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/shop"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// The pre-order shop (README §5.11): public reservations without payment,
// confirmation emails with a signed cancel link, and operator endpoints.
// Everything here answers 404 on servers without a shop configuration.

func (s *Server) shopOrNotFound() (*shop.Config, error) {
	if s.cfg.Shop == nil {
		return nil, problem.New(problem.NotFound, "")
	}
	return s.cfg.Shop, nil
}

// shopPublicPath reports whether a request is one of the public shop calls.
func shopPublicPath(r *http.Request) bool {
	return r.Method == http.MethodPost && (r.URL.Path == "/v1/shop/reservations" || r.URL.Path == "/v1/shop/reservations/cancel" ||
		r.URL.Path == "/v1/shop/orders/status")
}

func shopJSON(c *shop.Config) *gen.Shop {
	out := &gen.Shop{
		Brand: c.Brand, Currency: c.Currency, VatPercent: float32(c.VATPercent),
		Products: make([]gen.ShopProduct, len(c.Products)),
		Shipping: gen.ShopShipping{Countries: c.Shipping.Countries, FlatCents: c.Shipping.FlatCents, FreeFromCents: c.Shipping.FreeFromCents},
	}
	for i, p := range c.Products {
		gp := gen.ShopProduct{Id: p.ID, Name: p.Name, PriceCents: p.PriceCents, MaxQty: p.MaxQty}
		if p.Size != "" {
			gp.Size = ptr(p.Size)
		}
		if p.Image != "" {
			gp.Image = ptr(p.Image)
		}
		if len(p.Specs) > 0 {
			specs := map[string][]string(p.Specs)
			gp.Specs = &specs
		}
		if len(p.Photos) > 0 {
			photos := make([]string, len(p.Photos))
			for j, f := range p.Photos {
				photos[j] = "/v1/shop/media/" + f
			}
			gp.Photos = &photos
		}
		out.Products[i] = gp
	}
	if len(c.EstimatedDelivery) > 0 {
		d := gen.LocalizedText(c.EstimatedDelivery)
		out.EstimatedDelivery = &d
	}
	if len(c.Elsewhere) > 0 {
		links := make([]gen.ShopElsewhereLink, len(c.Elsewhere))
		for i, l := range c.Elsewhere {
			links[i] = gen.ShopElsewhereLink{Countries: l.Countries, Retailer: l.Retailer, Product: l.Product, Url: l.URL, Affiliate: ptr(l.Affiliate)}
		}
		out.Elsewhere = &links
	}
	if len(c.Domains) > 0 {
		out.Domains = &c.Domains
	}
	out.DefaultLocale = ptr(c.DefaultLocale)
	if c.Checkout() {
		out.Checkout = ptr(true)
	}
	for _, f := range []struct {
		v   string
		dst **string
	}{{c.Seller.TermsURL, &out.Seller.TermsUrl}, {c.Seller.Address, &out.Seller.Address}, {c.Seller.VATNumber, &out.Seller.VatNumber}, {c.Seller.Phone, &out.Seller.Phone}} {
		if f.v != "" {
			*f.dst = ptr(f.v)
		}
	}
	out.Seller.Name = c.Seller.Name
	out.Seller.Email = openapi_types.Email(c.Seller.Email)
	if c.Seller.Legal != "" {
		out.Seller.Legal = ptr(c.Seller.Legal)
	}
	if c.Seller.PrivacyURL != "" {
		out.Seller.PrivacyUrl = ptr(c.Seller.PrivacyURL)
	}
	return out
}

func linesJSON(ls []shop.Line) []gen.ShopLine {
	out := make([]gen.ShopLine, len(ls))
	for i, l := range ls {
		out[i] = gen.ShopLine{ProductId: l.ProductID, Name: l.Name, Quantity: l.Quantity, UnitCents: l.UnitCents, LineCents: l.LineCents}
	}
	return out
}

func reservationJSON(r *store.Reservation) gen.ShopReservation {
	return gen.ShopReservation{
		Id: r.ID, Reference: r.Reference, Status: gen.ShopReservationStatus(r.Status), Name: r.Name, Email: r.Email,
		Country: r.Country, PostalCode: r.PostalCode, Note: r.Note, Locale: r.Locale, Items: linesJSON(r.Items),
		SubtotalCents: r.SubtotalCents, ShippingCents: r.ShippingCents, TotalCents: r.TotalCents, Currency: r.Currency,
		InternalNote: r.InternalNote, CreatedAt: r.CreatedAt, UpdatedAt: r.UpdatedAt, CancelledAt: r.CancelledAt,
		Street: r.Street, City: r.City, Phone: r.Phone, PaymentStatus: paymentStatusJSON(r.PaymentStatus), PaidAt: r.PaidAt, ShippedAt: r.ShippedAt,
		TrackingUrl: r.TrackingURL, ShipsOn: dateJSON(r.ShipsOn),
	}
}

func paymentStatusJSON(s *string) *gen.ShopPaymentStatus {
	if s == nil {
		return nil
	}
	return ptr(gen.ShopPaymentStatus(*s))
}

func receiptJSON(r *store.Reservation, checkoutURL string) gen.ShopReservationReceipt {
	out := gen.ShopReservationReceipt{
		Reference: r.Reference, Status: gen.ShopReservationStatus(r.Status), Email: r.Email, Items: linesJSON(r.Items),
		SubtotalCents: r.SubtotalCents, ShippingCents: r.ShippingCents, TotalCents: r.TotalCents, Currency: r.Currency,
		PaymentStatus: paymentStatusJSON(r.PaymentStatus), ShipsOn: dateJSON(r.ShipsOn),
	}
	if checkoutURL != "" {
		out.CheckoutUrl = ptr(checkoutURL)
	}
	return out
}

// validTrackingURL accepts an absolute http(s) link, as carriers give them.
func validTrackingURL(s string) bool {
	u, err := url.Parse(s)
	return err == nil && (u.Scheme == "https" || u.Scheme == "http") && u.Host != "" && !strings.ContainsAny(s, " \t\r\n")
}

func validCustomerEmail(s string) bool {
	a, err := mail.ParseAddress(s)
	if err != nil || a.Address != s {
		return false
	}
	at := strings.LastIndexByte(s, '@')
	return at > 0 && strings.Contains(s[at+1:], ".") && !strings.HasSuffix(s, ".")
}

// trimmedOrNil is the trimmed text, nil when empty.
func trimmedOrNil(p *string) *string {
	if v := trimmed(p); v != "" {
		return &v
	}
	return nil
}

func (s *Server) CreateShopReservation(ctx context.Context, req gen.CreateShopReservationRequestObject) (gen.CreateShopReservationResponseObject, error) {
	c, err := s.shopOrNotFound()
	if err != nil {
		return nil, err
	}
	if ok, retry := s.shopIP.Allow(clientIP(ctx)); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	b := req.Body
	if b.Website != nil && strings.TrimSpace(*b.Website) != "" {
		// Honeypot: a bot filled the hidden field. Look successful, keep nothing.
		s.log.Info("shop reservation dropped by the honeypot", "ip", clientIP(ctx))
		return created(gen.ShopReservationReceipt{Reference: shop.NewReference(c.ReferencePrefix), Status: gen.New,
			Email: b.Email, Items: []gen.ShopLine{}, Currency: c.Currency}), nil
	}

	email := strings.TrimSpace(b.Email)
	name := strings.TrimSpace(b.Name)
	country := strings.ToUpper(strings.TrimSpace(b.Country))
	p := problem.New(problem.ValidationFailed, "some reservation details are not valid")
	if name == "" {
		p.WithField("name", "minLength")
	}
	if !validCustomerEmail(email) {
		p.WithField("email", "format")
	}
	if !c.ShipsTo(country) {
		p.WithField("country", "enum")
	}
	if len(b.Items) == 0 {
		p.WithField("items", "minItems")
	}
	want := make([]shop.Want, len(b.Items))
	for i, it := range b.Items {
		want[i] = shop.Want{ProductID: it.ProductId, Quantity: it.Quantity}
	}
	q, itemErrs := c.Price(want)
	for _, e := range itemErrs {
		p.WithField(fmt.Sprintf("items.%d.%s", e.Index, e.Field), e.Code)
	}
	if len(b.Items) > 0 && len(itemErrs) == 0 && q.SubtotalCents == 0 {
		p.WithField("items", "minItems")
	}
	if !b.Consent {
		p.WithField("consent", "required")
	}
	if c.Checkout() {
		for _, f := range []struct {
			name string
			v    *string
		}{{"street", b.Street}, {"postal_code", b.PostalCode}, {"city", b.City}} {
			if trimmed(f.v) == "" {
				p.WithField(f.name, "minLength")
			}
		}
	}
	if len(p.Errors) > 0 {
		return nil, p
	}

	lang := i18n.Pick(deref(b.Locale))
	r := store.Reservation{
		ID: store.NewID(), Name: name, Email: email, Country: country, PostalCode: trimmedOrNil(b.PostalCode), Note: trimmedOrNil(b.Note),
		Locale: lang, Items: q.Lines, Currency: c.Currency,
		SubtotalCents: q.SubtotalCents, ShippingCents: q.ShippingCents, TotalCents: q.TotalCents,
	}
	if c.Checkout() {
		r.Street, r.City, r.Phone = trimmedOrNil(b.Street), trimmedOrNil(b.City), trimmedOrNil(b.Phone)
		r.PaymentStatus = ptr("open")
	}
	if c.Stock != nil {
		stock, err := s.stockNow(ctx)
		if err != nil {
			return nil, err
		}
		r.ShipsOn = ptr(c.ShipsOn(time.Now(), shop.InStock(q.Lines, stock)))
	}
	var saved *store.Reservation
	for range 8 {
		r.Reference = shop.NewReference(c.ReferencePrefix)
		saved, err = s.st.CreateReservation(ctx, r)
		if err != store.ErrReferenceTaken {
			break
		}
	}
	if err != nil {
		return nil, err
	}
	if c.Checkout() {
		return s.startCheckout(ctx, c, saved, lang)
	}
	s.log.Info("shop reservation", "reference", saved.Reference, "total_cents", saved.TotalCents, "country", saved.Country)

	mr := s.mailReservation(saved, s.shopOrigin(ctx))
	s.sendMail("reservation "+saved.Reference, c.CustomerEmail(mr), c.OperatorEmail(mr, false, s.cfg.PublicURL+"/settings/reservations"))

	return created(receiptJSON(saved, "")), nil
}

// shopOrigin is where customer links point: the storefront domain the request
// came in on, else the app's public URL.
func (s *Server) shopOrigin(ctx context.Context) string {
	host := requestHost(ctx)
	if !s.cfg.Shop.IsStorefront(host) {
		return s.cfg.PublicURL
	}
	scheme, _, _ := strings.Cut(s.cfg.PublicURL, "://")
	return scheme + "://" + strings.TrimSuffix(strings.ToLower(host), ".")
}

// storefrontOrigin is where emails sent outside a customer request link to:
// the first storefront domain, else the app's public URL.
func (s *Server) storefrontOrigin() string {
	if d := s.cfg.Shop.Domains; len(d) > 0 {
		scheme, _, _ := strings.Cut(s.cfg.PublicURL, "://")
		return scheme + "://" + d[0]
	}
	return s.cfg.PublicURL
}

func (s *Server) mailReservation(r *store.Reservation, origin string) shop.Reservation {
	return shop.Reservation{
		Reference: r.Reference, Name: r.Name, Email: r.Email, Country: r.Country, PostalCode: deref(r.PostalCode),
		Note: deref(r.Note), Lang: r.Locale,
		Quote:     shop.Quote{Lines: r.Items, SubtotalCents: r.SubtotalCents, ShippingCents: r.ShippingCents, TotalCents: r.TotalCents},
		CancelURL: origin + "/shop/cancel?token=" + s.shopTokens.Sign(r.ID),
		Order:     r.PaymentStatus != nil, Street: deref(r.Street), City: deref(r.City), Phone: deref(r.Phone),
		TrackingURL: deref(r.TrackingURL), ShipsOn: r.ShipsOn,
	}
}

// ---------------------------------------------------------------- payments

// startCheckout creates the payment for a new order and answers with the
// provider's payment page. The emails go out once the payment is paid.
func (s *Server) startCheckout(ctx context.Context, c *shop.Config, r *store.Reservation, lang string) (gen.CreateShopReservationResponseObject, error) {
	origin := s.shopOrigin(ctx)
	pay, err := s.payments.Create(ctx, shop.PaymentRequest{
		AmountCents: r.TotalCents, Currency: r.Currency, Description: c.Brand + " " + r.Reference, Reference: r.Reference,
		RedirectURL: origin + "/shop/order?token=" + s.orderTokens.Sign(r.ID),
		WebhookURL:  s.paymentWebhookURL(), Locale: lang, Methods: c.Payments.Methods,
	})
	if err != nil {
		s.log.Error("shop payment", "reference", r.Reference, "err", err)
		_, _, _ = s.st.SetPaymentStatus(ctx, r.ID, "failed")
		return nil, problem.New(problem.UpstreamUnavailable, "the payment provider is not available; please try again")
	}
	if err := s.st.SetPayment(ctx, r.ID, pay.ID, pay.Status); err != nil {
		return nil, err
	}
	r.PaymentStatus = ptr(pay.Status)
	s.log.Info("shop order", "reference", r.Reference, "payment", pay.ID, "total_cents", r.TotalCents, "country", r.Country)
	return created(receiptJSON(r, pay.CheckoutURL)), nil
}

// paymentWebhookURL is where the provider reports payment changes; empty when
// the public URL can't be reached from the internet (local development).
func (s *Server) paymentWebhookURL() string {
	u, err := url.Parse(s.cfg.PublicURL)
	if err != nil || u.Scheme != "https" {
		return ""
	}
	h := u.Hostname()
	if h == "localhost" || strings.HasSuffix(h, ".localhost") || strings.HasSuffix(h, ".test") || net.ParseIP(h) != nil {
		return ""
	}
	return strings.TrimSuffix(s.cfg.PublicURL, "/") + "/v1/shop/payments/webhook"
}

// syncPayment reads the payment from the provider and stores its status; the
// first time it is paid, the order emails go out.
func (s *Server) syncPayment(ctx context.Context, r *store.Reservation, origin string) (*store.Reservation, *shop.Payment, error) {
	pay, err := s.payments.Get(ctx, *r.PaymentID)
	if err != nil {
		return r, nil, err
	}
	updated, nowPaid, err := s.st.SetPaymentStatus(ctx, r.ID, pay.Status)
	if err != nil {
		return r, pay, err
	}
	if nowPaid {
		s.log.Info("shop order paid", "reference", updated.Reference, "payment", pay.ID, "total_cents", updated.TotalCents)
		if s.cfg.Shop.Stock != nil {
			if err := s.st.TakeShopStock(ctx, updated.Items); err != nil {
				s.log.Error("shop stock", "reference", updated.Reference, "err", err)
			}
			s.shopStock.forget()
		}
		mr := s.mailReservation(updated, origin)
		s.sendMail("order "+updated.Reference, s.cfg.Shop.CustomerEmail(mr), s.cfg.Shop.OperatorEmail(mr, false, s.cfg.PublicURL+"/settings/reservations"))
	}
	return updated, pay, nil
}

// shopPaymentWebhook is POST /v1/shop/payments/webhook (hand-written: the
// provider posts a form with the payment id). The status itself is always
// read back from the provider, so a forged call can't mark an order paid.
func (s *Server) shopPaymentWebhook(w http.ResponseWriter, r *http.Request) {
	c := s.cfg.Shop
	if c == nil || !c.Checkout() || s.payments == nil {
		s.writeError(w, r, problem.New(problem.NotFound, ""))
		return
	}
	id := strings.TrimSpace(r.PostFormValue("id"))
	if id == "" || len(id) > 64 {
		w.WriteHeader(http.StatusOK)
		return
	}
	res, err := s.st.GetReservationByPayment(r.Context(), id)
	if err == store.ErrNotFound {
		w.WriteHeader(http.StatusOK) // unknown payment: nothing to do (and nothing to reveal)
		return
	}
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	if _, _, err := s.syncPayment(r.Context(), res, s.storefrontOrigin()); err != nil {
		s.log.Error("shop payment webhook", "payment", id, "err", err)
		http.Error(w, "payment provider unavailable", http.StatusBadGateway) // the provider retries
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (s *Server) GetShopOrderStatus(ctx context.Context, req gen.GetShopOrderStatusRequestObject) (gen.GetShopOrderStatusResponseObject, error) {
	c, err := s.shopOrNotFound()
	if err != nil {
		return nil, err
	}
	if ok, retry := s.shopOrderIP.Allow(clientIP(ctx)); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	id, err := s.orderTokens.Verify(req.Body.Token)
	if err != nil {
		return nil, problem.New(problem.NotFound, "")
	}
	r, err := s.st.GetReservation(ctx, id)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "")
	}
	if err != nil {
		return nil, err
	}
	checkout := ""
	if c.Checkout() && s.payments != nil && r.PaymentID != nil && r.PaymentStatus != nil {
		switch *r.PaymentStatus {
		case "open", "pending", "authorized":
			updated, pay, err := s.syncPayment(ctx, r, s.shopOrigin(ctx))
			if err != nil {
				s.log.Warn("shop payment status", "reference", r.Reference, "err", err)
			}
			r = updated
			if pay != nil && pay.Status == "open" {
				checkout = pay.CheckoutURL
			}
		}
	}
	return ok(receiptJSON(r, checkout)), nil
}

// sendMail sends in the background: the reservation is stored either way,
// and a slow or broken mail server must not fail the request.
func (s *Server) sendMail(what string, msgs ...shop.Message) {
	if s.mailer == nil {
		s.log.Warn("SMTP is not configured (KINFRAME_SMTP_HOST); no email sent", "for", what)
		return
	}
	go func() {
		for _, m := range msgs {
			ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
			if err := s.mailer.Send(ctx, m); err != nil {
				s.log.Error("send email", "for", what, "subject", m.Subject, "err", err)
			}
			cancel()
		}
	}()
}

func (s *Server) CancelShopReservation(ctx context.Context, req gen.CancelShopReservationRequestObject) (gen.CancelShopReservationResponseObject, error) {
	c, err := s.shopOrNotFound()
	if err != nil {
		return nil, err
	}
	if ok, retry := s.shopCancelIP.Allow(clientIP(ctx)); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	id, err := s.shopTokens.Verify(req.Body.Token)
	if err != nil {
		return nil, problem.New(problem.NotFound, "")
	}
	r, changed, err := s.st.CancelReservation(ctx, id)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "")
	}
	if err != nil {
		return nil, err
	}
	// An unpaid checkout that is cancelled needs nothing from the operator.
	if changed && (r.PaymentStatus == nil || r.PaidAt != nil) {
		s.log.Info("shop reservation cancelled by the customer", "reference", r.Reference)
		s.sendMail("cancellation "+r.Reference, c.OperatorEmail(s.mailReservation(r, s.shopOrigin(ctx)), true, s.cfg.PublicURL+"/settings/reservations"))
	}
	return ok(map[string]any{"reference": r.Reference, "status": r.Status}), nil
}

// ---------------------------------------------------------------- operator

func (s *Server) OperatorListReservations(ctx context.Context, req gen.OperatorListReservationsRequestObject) (gen.OperatorListReservationsResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	if _, err := s.shopOrNotFound(); err != nil {
		return nil, err
	}
	status := ""
	if req.Params.Status != nil {
		status = string(*req.Params.Status)
	}
	rs, err := s.st.ListReservations(ctx, status)
	if err != nil {
		return nil, err
	}
	out := make([]gen.ShopReservation, len(rs))
	for i := range rs {
		out[i] = reservationJSON(&rs[i])
	}
	return ok(map[string]any{"reservations": out}), nil
}

func (s *Server) OperatorUpdateReservation(ctx context.Context, req gen.OperatorUpdateReservationRequestObject) (gen.OperatorUpdateReservationResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	if _, err := s.shopOrNotFound(); err != nil {
		return nil, err
	}
	b := req.Body
	p := store.ReservationPatch{}
	if b.Status != nil {
		p.Status = ptr(string(*b.Status))
	}
	if present, null := rawField(ctx, "internal_note"); present {
		if v := trimmedOrNil(b.InternalNote); v == nil || null {
			p.ClearNote = true
		} else {
			p.InternalNote = v
		}
	}
	if present, null := rawField(ctx, "tracking_url"); present {
		if v := trimmedOrNil(b.TrackingUrl); v == nil || null {
			p.ClearTracking = true
		} else if !validTrackingURL(*v) {
			return nil, problem.New(problem.ValidationFailed, "some reservation details are not valid").WithField("tracking_url", "format")
		} else {
			p.TrackingURL = v
		}
	}
	before, err := s.st.GetReservation(ctx, req.ReservationId)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "")
	}
	if err != nil {
		return nil, err
	}
	r, err := s.st.UpdateReservation(ctx, req.ReservationId, p)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "")
	}
	if err != nil {
		return nil, err
	}
	// The customer hears when a paid order ships, and again when its tracking link is added or changed afterwards.
	shipped := r.Status == "shipped" && before.Status != "shipped"
	tracking := r.Status == "shipped" && before.Status == "shipped" && deref(r.TrackingURL) != "" && deref(r.TrackingURL) != deref(before.TrackingURL)
	if (shipped || tracking) && r.PaidAt != nil {
		s.sendMail("shipped "+r.Reference, s.cfg.Shop.ShippedEmail(s.mailReservation(r, s.storefrontOrigin())))
	}
	return ok(reservationJSON(r)), nil
}

// exportReservations is GET /v1/operator/reservations.csv (hand-written: CSV).
func (s *Server) exportReservations(w http.ResponseWriter, r *http.Request) {
	if err := requireOperator(r.Context()); err != nil {
		s.writeError(w, r, err)
		return
	}
	c, err := s.shopOrNotFound()
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	status := r.URL.Query().Get("status")
	if status != "" && !gen.ShopReservationStatus(status).Valid() {
		s.writeError(w, r, problem.New(problem.ValidationFailed, "%s", "status").WithStatus(http.StatusBadRequest).WithField("status", "enum"))
		return
	}
	rs, err := s.st.ListReservations(r.Context(), status)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="reservations-%s.csv"`, time.Now().UTC().Format("2006-01-02")))
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write([]byte("\xef\xbb\xbf")) // BOM: spreadsheet apps then read UTF-8
	cw := csv.NewWriter(w)
	head := []string{"reference", "status", "created_at", "name", "email", "country", "postal_code", "locale"}
	for _, p := range c.Products {
		head = append(head, "qty "+p.ID)
	}
	head = append(head, "items", "subtotal", "shipping", "total", "currency", "note", "internal_note", "cancelled_at", "tracking_url")
	_ = cw.Write(head)
	money := func(cents int) string { return fmt.Sprintf("%d.%02d", cents/100, cents%100) }
	for _, res := range rs {
		qty := map[string]int{}
		var items []string
		for _, l := range res.Items {
			qty[l.ProductID] += l.Quantity
			items = append(items, fmt.Sprintf("%d x %s", l.Quantity, l.Name))
		}
		row := []string{res.Reference, res.Status, res.CreatedAt.UTC().Format(time.RFC3339), res.Name, res.Email, res.Country,
			deref(res.PostalCode), res.Locale}
		for _, p := range c.Products {
			row = append(row, fmt.Sprint(qty[p.ID]))
		}
		cancelled := ""
		if res.CancelledAt != nil {
			cancelled = res.CancelledAt.UTC().Format(time.RFC3339)
		}
		row = append(row, strings.Join(items, "; "), money(res.SubtotalCents), money(res.ShippingCents), money(res.TotalCents),
			res.Currency, deref(res.Note), deref(res.InternalNote), cancelled, deref(res.TrackingURL))
		for i := range row {
			row[i] = csvSafe(row[i])
		}
		_ = cw.Write(row)
	}
	cw.Flush()
}

// csvSafe defuses spreadsheet formulas in customer-supplied cells.
func csvSafe(v string) string {
	if v != "" && strings.ContainsRune("=+-@\t\r", rune(v[0])) {
		return "'" + v
	}
	return v
}

// shopMedia is GET /v1/shop/media/{name} (hand-written: a file): a product
// photo from KINFRAME_SHOP_MEDIA_DIR. Only photos named in the shop config are
// served.
func (s *Server) shopMedia(w http.ResponseWriter, r *http.Request) {
	c := s.cfg.Shop
	name := r.PathValue("name")
	if c == nil || s.cfg.ShopMediaDir == "" || !c.Photo(name) {
		s.writeError(w, r, problem.New(problem.NotFound, ""))
		return
	}
	f, err := os.Open(filepath.Join(s.cfg.ShopMediaDir, name))
	if err != nil {
		s.writeError(w, r, problem.New(problem.NotFound, ""))
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=86400")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, name, st.ModTime(), f)
}
