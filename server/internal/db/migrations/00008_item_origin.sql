-- +goose Up

-- Items imported on a frame from another photo-frame app's leftover data
-- (docs/spec/frameo-import.md). origin names the app ('frameo'), origin_sender_name
-- is the sender's display name as that app knew it (shown as "Anna · via Frameo"),
-- and origin_key identifies the source file so a re-run never imports it twice.
ALTER TABLE items ADD COLUMN origin text CHECK (origin IN ('frameo'));
ALTER TABLE items ADD COLUMN origin_sender_name text;
ALTER TABLE items ADD COLUMN origin_key text;
CREATE UNIQUE INDEX items_origin_key_unique ON items (sender_frame_id, origin, origin_key)
    WHERE origin_key IS NOT NULL;

-- +goose Down
DROP INDEX items_origin_key_unique;
ALTER TABLE items DROP COLUMN origin_key;
ALTER TABLE items DROP COLUMN origin_sender_name;
ALTER TABLE items DROP COLUMN origin;
