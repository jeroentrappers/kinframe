-- +goose Up

-- The carrier's tracking link for a shipped order; the shipped email links to it.
ALTER TABLE shop_reservations ADD COLUMN tracking_url text;

-- +goose Down
ALTER TABLE shop_reservations DROP COLUMN tracking_url;
