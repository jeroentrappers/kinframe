-- +goose Up

-- The display rendition of an item (README §5.3): the uncropped image apps show large (feed posts), WebP q82,
-- long edge ≤1600 px, made from the photo master or the video poster next to the square thumbnails. Null until
-- the thumbs job made it; existing items get it from the display backfill (`kinframe-server backfill-display`).
ALTER TABLE items ADD COLUMN display_key text;

-- +goose Down
ALTER TABLE items DROP COLUMN display_key;
