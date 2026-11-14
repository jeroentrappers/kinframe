-- +goose Up

-- Paid orders (shop payments): the delivery address, the payment at the provider and a "shipped" status.
-- Reservations without payment keep these columns empty.
ALTER TABLE shop_reservations
    ADD COLUMN street         text,
    ADD COLUMN city           text,
    ADD COLUMN phone          text,
    ADD COLUMN payment_id     text UNIQUE,
    ADD COLUMN payment_status text CHECK (payment_status IN ('open', 'pending', 'authorized', 'paid', 'failed', 'canceled', 'expired')),
    ADD COLUMN paid_at        timestamptz,
    ADD COLUMN shipped_at     timestamptz;

ALTER TABLE shop_reservations DROP CONSTRAINT shop_reservations_status_check;
ALTER TABLE shop_reservations ADD CONSTRAINT shop_reservations_status_check
    CHECK (status IN ('new', 'contacted', 'confirmed', 'shipped', 'cancelled'));

-- +goose Down
DELETE FROM shop_reservations WHERE status = 'shipped';
ALTER TABLE shop_reservations DROP CONSTRAINT shop_reservations_status_check;
ALTER TABLE shop_reservations ADD CONSTRAINT shop_reservations_status_check
    CHECK (status IN ('new', 'contacted', 'confirmed', 'cancelled'));
ALTER TABLE shop_reservations
    DROP COLUMN street, DROP COLUMN city, DROP COLUMN phone, DROP COLUMN payment_id,
    DROP COLUMN payment_status, DROP COLUMN paid_at, DROP COLUMN shipped_at;
