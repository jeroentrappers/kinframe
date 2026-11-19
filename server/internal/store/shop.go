package store

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/shop"
)

// Reservation is a shop pre-order (README §5.11).
type Reservation struct {
	ID            uuid.UUID   `db:"id"`
	Reference     string      `db:"reference"`
	Status        string      `db:"status"`
	Name          string      `db:"name"`
	Email         string      `db:"email"`
	Country       string      `db:"country"`
	PostalCode    *string     `db:"postal_code"`
	Note          *string     `db:"note"`
	Locale        string      `db:"locale"`
	Items         []shop.Line `db:"items"`
	Currency      string      `db:"currency"`
	SubtotalCents int         `db:"subtotal_cents"`
	ShippingCents int         `db:"shipping_cents"`
	TotalCents    int         `db:"total_cents"`
	ConsentAt     time.Time   `db:"consent_at"`
	InternalNote  *string     `db:"internal_note"`
	CancelledAt   *time.Time  `db:"cancelled_at"`
	Street        *string     `db:"street"`
	City          *string     `db:"city"`
	Phone         *string     `db:"phone"`
	PaymentID     *string     `db:"payment_id"`
	PaymentStatus *string     `db:"payment_status"`
	PaidAt        *time.Time  `db:"paid_at"`
	ShippedAt     *time.Time  `db:"shipped_at"`
	TrackingURL   *string     `db:"tracking_url"`
	ShipsOn       *time.Time  `db:"ships_on"`
	CreatedAt     time.Time   `db:"created_at"`
	UpdatedAt     time.Time   `db:"updated_at"`
}

const reservationSelect = `SELECT id, reference, status, name, email, country, postal_code, note, locale, items, currency,
	subtotal_cents, shipping_cents, total_cents, consent_at, internal_note, cancelled_at, created_at, updated_at,
	street, city, phone, payment_id, payment_status, paid_at, shipped_at, tracking_url, ships_on
	FROM shop_reservations`

// ErrReferenceTaken means the generated reference already exists; pick another.
var ErrReferenceTaken = errors.New("reservation reference taken")

func (s *Store) CreateReservation(ctx context.Context, r Reservation) (*Reservation, error) {
	_, err := s.Pool.Exec(ctx, `INSERT INTO shop_reservations (id, reference, name, email, country, postal_code, note, locale,
		items, currency, subtotal_cents, shipping_cents, total_cents, consent_at, street, city, phone, payment_status, ships_on)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, now(), $14, $15, $16, $17, $18)`,
		r.ID, r.Reference, r.Name, r.Email, r.Country, r.PostalCode, r.Note, r.Locale, r.Items, r.Currency,
		r.SubtotalCents, r.ShippingCents, r.TotalCents, r.Street, r.City, r.Phone, r.PaymentStatus, r.ShipsOn)
	if db.IsUniqueViolation(err, "shop_reservations_reference_key") {
		return nil, ErrReferenceTaken
	}
	if err != nil {
		return nil, err
	}
	return s.GetReservation(ctx, r.ID)
}

func (s *Store) GetReservation(ctx context.Context, id uuid.UUID) (*Reservation, error) {
	return one[Reservation](s.Pool.Query(ctx, reservationSelect+` WHERE id = $1`, id))
}

// GetReservationByPayment finds the order paid with a provider payment.
func (s *Store) GetReservationByPayment(ctx context.Context, paymentID string) (*Reservation, error) {
	return one[Reservation](s.Pool.Query(ctx, reservationSelect+` WHERE payment_id = $1`, paymentID))
}

// SetPayment records the provider payment created for an order.
func (s *Store) SetPayment(ctx context.Context, id uuid.UUID, paymentID, status string) error {
	_, err := s.Pool.Exec(ctx, `UPDATE shop_reservations SET payment_id = $2, payment_status = $3, updated_at = now() WHERE id = $1`,
		id, paymentID, status)
	return err
}

// SetPaymentStatus stores the provider's payment status and reports whether
// the order just became paid (exactly once per order, for the emails).
func (s *Store) SetPaymentStatus(ctx context.Context, id uuid.UUID, status string) (*Reservation, bool, error) {
	var nowPaid bool
	err := s.Pool.QueryRow(ctx, `WITH prev AS (SELECT paid_at, payment_status FROM shop_reservations WHERE id = $1 FOR UPDATE)
		UPDATE shop_reservations r SET payment_status = $2,
			paid_at = CASE WHEN $2 = 'paid' THEN coalesce(r.paid_at, now()) ELSE r.paid_at END,
			updated_at = CASE WHEN prev.payment_status IS DISTINCT FROM $2 THEN now() ELSE r.updated_at END
		FROM prev WHERE r.id = $1
		RETURNING prev.paid_at IS NULL AND $2 = 'paid'`, id, status).Scan(&nowPaid)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, false, ErrNotFound
	}
	if err != nil {
		return nil, false, err
	}
	r, err := s.GetReservation(ctx, id)
	return r, nowPaid, err
}

// ListReservations returns reservations newest first; status filters unless empty.
func (s *Store) ListReservations(ctx context.Context, status string) ([]Reservation, error) {
	return many[Reservation](s.Pool.Query(ctx, reservationSelect+`
		WHERE ($1 = '' OR status = $1) ORDER BY created_at DESC, id DESC LIMIT 5000`, status))
}

// ReservationPatch changes the status, the internal note and/or the tracking
// link. ClearNote and ClearTracking set those to NULL.
type ReservationPatch struct {
	Status        *string
	InternalNote  *string
	ClearNote     bool
	TrackingURL   *string
	ClearTracking bool
}

func (s *Store) UpdateReservation(ctx context.Context, id uuid.UUID, p ReservationPatch) (*Reservation, error) {
	tag, err := s.Pool.Exec(ctx, `UPDATE shop_reservations SET
		status = coalesce($2, status),
		cancelled_at = CASE WHEN $2::text IS NULL OR $2 = status THEN cancelled_at WHEN $2 = 'cancelled' THEN now() ELSE NULL END,
		shipped_at = CASE WHEN $2::text IS NULL OR $2 = status THEN shipped_at WHEN $2 = 'shipped' THEN now() ELSE shipped_at END,
		internal_note = CASE WHEN $4 THEN NULL ELSE coalesce($3, internal_note) END,
		tracking_url = CASE WHEN $6 THEN NULL ELSE coalesce($5, tracking_url) END,
		updated_at = now()
		WHERE id = $1`, id, p.Status, p.InternalNote, p.ClearNote, p.TrackingURL, p.ClearTracking)
	if err != nil {
		return nil, err
	}
	if tag.RowsAffected() == 0 {
		return nil, ErrNotFound
	}
	return s.GetReservation(ctx, id)
}

// CancelReservation cancels a reservation (idempotent) and reports whether
// it changed.
func (s *Store) CancelReservation(ctx context.Context, id uuid.UUID) (*Reservation, bool, error) {
	tag, err := s.Pool.Exec(ctx, `UPDATE shop_reservations SET status = 'cancelled', cancelled_at = now(), updated_at = now()
		WHERE id = $1 AND status <> 'cancelled'`, id)
	if err != nil {
		return nil, false, err
	}
	r, err := s.GetReservation(ctx, id)
	return r, tag.RowsAffected() > 0, err
}

// PurgeReservations deletes reservations unchanged for retentionDays, and
// cancelled ones and abandoned checkouts 30 days after the last change. Paid
// orders are bookkeeping records: they are kept for 7 years.
func (s *Store) PurgeReservations(ctx context.Context, retentionDays int) (int64, error) {
	tag, err := s.Pool.Exec(ctx, `DELETE FROM shop_reservations
		WHERE (paid_at IS NULL AND (updated_at < now() - make_interval(days => $1)
			OR (status = 'cancelled' AND coalesce(cancelled_at, updated_at) < now() - interval '30 days')
			OR (payment_status IN ('open', 'failed', 'canceled', 'expired') AND updated_at < now() - interval '30 days')))
		OR paid_at < now() - interval '7 years'`, retentionDays)
	return tag.RowsAffected(), err
}

// ShopStock is the count of frames ready to ship per product id; products
// without a row have none.
func (s *Store) ShopStock(ctx context.Context) (map[string]int, error) {
	rows, err := s.Pool.Query(ctx, `SELECT product_id, quantity FROM shop_stock`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int{}
	for rows.Next() {
		var id string
		var n int
		if err := rows.Scan(&id, &n); err != nil {
			return nil, err
		}
		out[id] = n
	}
	return out, rows.Err()
}

// SetShopStock sets a product's stock count.
func (s *Store) SetShopStock(ctx context.Context, productID string, quantity int) error {
	_, err := s.Pool.Exec(ctx, `INSERT INTO shop_stock (product_id, quantity) VALUES ($1, $2)
		ON CONFLICT (product_id) DO UPDATE SET quantity = $2, updated_at = now()`, productID, quantity)
	return err
}

// TakeShopStock lowers the stock by a paid order's lines (never below 0).
func (s *Store) TakeShopStock(ctx context.Context, lines []shop.Line) error {
	for _, l := range lines {
		if _, err := s.Pool.Exec(ctx, `UPDATE shop_stock SET quantity = greatest(quantity - $2, 0), updated_at = now()
			WHERE product_id = $1`, l.ProductID, l.Quantity); err != nil {
			return err
		}
	}
	return nil
}
