package store

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"

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
	CreatedAt     time.Time   `db:"created_at"`
	UpdatedAt     time.Time   `db:"updated_at"`
}

const reservationSelect = `SELECT id, reference, status, name, email, country, postal_code, note, locale, items, currency,
	subtotal_cents, shipping_cents, total_cents, consent_at, internal_note, cancelled_at, created_at, updated_at
	FROM shop_reservations`

// ErrReferenceTaken means the generated reference already exists; pick another.
var ErrReferenceTaken = errors.New("reservation reference taken")

func (s *Store) CreateReservation(ctx context.Context, r Reservation) (*Reservation, error) {
	_, err := s.Pool.Exec(ctx, `INSERT INTO shop_reservations (id, reference, name, email, country, postal_code, note, locale,
		items, currency, subtotal_cents, shipping_cents, total_cents, consent_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, now())`,
		r.ID, r.Reference, r.Name, r.Email, r.Country, r.PostalCode, r.Note, r.Locale, r.Items, r.Currency,
		r.SubtotalCents, r.ShippingCents, r.TotalCents)
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

// ListReservations returns reservations newest first; status filters unless empty.
func (s *Store) ListReservations(ctx context.Context, status string) ([]Reservation, error) {
	return many[Reservation](s.Pool.Query(ctx, reservationSelect+`
		WHERE ($1 = '' OR status = $1) ORDER BY created_at DESC, id DESC LIMIT 5000`, status))
}

// ReservationPatch changes the status and/or the internal note. ClearNote
// sets the note to NULL.
type ReservationPatch struct {
	Status       *string
	InternalNote *string
	ClearNote    bool
}

func (s *Store) UpdateReservation(ctx context.Context, id uuid.UUID, p ReservationPatch) (*Reservation, error) {
	tag, err := s.Pool.Exec(ctx, `UPDATE shop_reservations SET
		status = coalesce($2, status),
		cancelled_at = CASE WHEN $2::text IS NULL OR $2 = status THEN cancelled_at WHEN $2 = 'cancelled' THEN now() ELSE NULL END,
		internal_note = CASE WHEN $4 THEN NULL ELSE coalesce($3, internal_note) END,
		updated_at = now()
		WHERE id = $1`, id, p.Status, p.InternalNote, p.ClearNote)
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
// cancelled ones 30 days after the cancellation.
func (s *Store) PurgeReservations(ctx context.Context, retentionDays int) (int64, error) {
	tag, err := s.Pool.Exec(ctx, `DELETE FROM shop_reservations
		WHERE updated_at < now() - make_interval(days => $1)
		OR (status = 'cancelled' AND coalesce(cancelled_at, updated_at) < now() - interval '30 days')`, retentionDays)
	return tag.RowsAffected(), err
}
