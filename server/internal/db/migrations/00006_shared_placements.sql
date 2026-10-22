-- +goose Up

-- A placement shared from another frame's library (POST /v1/frames/{id}/placements:share)
-- points at the same item, so no media is copied. shared_by_user_id is the member
-- who shared it; NULL for placements made by the item's sender (sends, forwards).
ALTER TABLE placements ADD COLUMN shared_by_user_id uuid REFERENCES users ON DELETE SET NULL;
CREATE INDEX placements_shared_by_idx ON placements (shared_by_user_id, frame_id) WHERE shared_by_user_id IS NOT NULL;

-- +goose Down
DROP INDEX placements_shared_by_idx;
ALTER TABLE placements DROP COLUMN shared_by_user_id;
