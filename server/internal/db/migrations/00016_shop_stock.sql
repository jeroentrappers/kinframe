-- +goose Up

-- Frames ready to ship per product (shops with stock management). Paid orders lower the count.
CREATE TABLE shop_stock (
    product_id text PRIMARY KEY,
    quantity   integer NOT NULL CHECK (quantity >= 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- The dispatch day promised when the order was placed.
ALTER TABLE shop_reservations ADD COLUMN ships_on date;

-- +goose Down
ALTER TABLE shop_reservations DROP COLUMN ships_on;
DROP TABLE shop_stock;
