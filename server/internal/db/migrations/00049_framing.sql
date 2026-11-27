-- +goose Up
-- Framing: zoom and position per screen orientation (docs/spec/framing.md). The sender's on the item, a frame's own on
-- its placement. {"landscape": {"x":..,"y":..,"zoom":..}, "portrait": {...}}; NULL = the focal point at 1×.
ALTER TABLE items ADD COLUMN framing jsonb;
ALTER TABLE placements ADD COLUMN framing_override jsonb;

-- +goose Down
ALTER TABLE placements DROP COLUMN framing_override;
ALTER TABLE items DROP COLUMN framing;
