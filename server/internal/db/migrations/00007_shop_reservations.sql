-- +goose Up

-- Pre-order reservations for the optional shop (README §5.10). Prices are the
-- server's own at the time of the reservation (items: [{product_id, name,
-- quantity, unit_cents, line_cents}]). No IP addresses or payment data are kept.
-- Rows are deleted by the maintenance loop after the shop's retention period.
CREATE TABLE shop_reservations (
    id             uuid PRIMARY KEY,
    reference      text NOT NULL UNIQUE,
    status         text NOT NULL DEFAULT 'new' CHECK (status IN ('new', 'contacted', 'confirmed', 'cancelled')),
    name           text NOT NULL,
    email          text NOT NULL,
    country        text NOT NULL,
    postal_code    text,
    note           text,
    locale         text NOT NULL,
    items          jsonb NOT NULL,
    currency       text NOT NULL,
    subtotal_cents integer NOT NULL,
    shipping_cents integer NOT NULL,
    total_cents    integer NOT NULL,
    consent_at     timestamptz NOT NULL,
    internal_note  text,
    cancelled_at   timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX shop_reservations_status_idx ON shop_reservations (status, created_at DESC);

-- +goose Down
DROP TABLE shop_reservations;
