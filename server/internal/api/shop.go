package api

import (
	"context"
	"encoding/csv"
	"fmt"
	"net/http"
	"net/mail"
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
	return r.Method == http.MethodPost && (r.URL.Path == "/v1/shop/reservations" || r.URL.Path == "/v1/shop/reservations/cancel")
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
		out.Products[i] = gp
	}
	if len(c.EstimatedDelivery) > 0 {
		d := gen.LocalizedText(c.EstimatedDelivery)
		out.EstimatedDelivery = &d
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
	}
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
	if len(p.Errors) > 0 {
		return nil, p
	}

	lang := i18n.Pick(deref(b.Locale))
	r := store.Reservation{
		ID: store.NewID(), Name: name, Email: email, Country: country, PostalCode: trimmedOrNil(b.PostalCode), Note: trimmedOrNil(b.Note),
		Locale: lang, Items: q.Lines, Currency: c.Currency,
		SubtotalCents: q.SubtotalCents, ShippingCents: q.ShippingCents, TotalCents: q.TotalCents,
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
	s.log.Info("shop reservation", "reference", saved.Reference, "total_cents", saved.TotalCents, "country", saved.Country)

	mr := s.mailReservation(saved)
	s.sendMail("reservation "+saved.Reference, c.CustomerEmail(mr), c.OperatorEmail(mr, false, s.cfg.PublicURL+"/settings/reservations"))

	return created(gen.ShopReservationReceipt{
		Reference: saved.Reference, Status: gen.ShopReservationStatus(saved.Status), Email: saved.Email, Items: linesJSON(saved.Items),
		SubtotalCents: saved.SubtotalCents, ShippingCents: saved.ShippingCents, TotalCents: saved.TotalCents, Currency: saved.Currency,
	}), nil
}

func (s *Server) mailReservation(r *store.Reservation) shop.Reservation {
	return shop.Reservation{
		Reference: r.Reference, Name: r.Name, Email: r.Email, Country: r.Country, PostalCode: deref(r.PostalCode),
		Note: deref(r.Note), Lang: r.Locale,
		Quote:     shop.Quote{Lines: r.Items, SubtotalCents: r.SubtotalCents, ShippingCents: r.ShippingCents, TotalCents: r.TotalCents},
		CancelURL: s.cfg.PublicURL + "/shop/cancel?token=" + s.shopTokens.Sign(r.ID),
	}
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
	if changed {
		s.log.Info("shop reservation cancelled by the customer", "reference", r.Reference)
		s.sendMail("cancellation "+r.Reference, c.OperatorEmail(s.mailReservation(r), true, s.cfg.PublicURL+"/settings/reservations"))
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
	r, err := s.st.UpdateReservation(ctx, req.ReservationId, p)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "")
	}
	if err != nil {
		return nil, err
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
	head = append(head, "items", "subtotal", "shipping", "total", "currency", "note", "internal_note", "cancelled_at")
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
			res.Currency, deref(res.Note), deref(res.InternalNote), cancelled)
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
