-- +goose Up

-- What runs a frame: the Android frame app, or a web browser enrolled as a "virtual
-- frame" from the TV page (docs/spec/kinframe-additions.md, TV modes). Browser frames use
-- the same device protocol; the server skips what only applies to the Android app
-- (self-update, the minimum app version).
ALTER TABLE devices ADD COLUMN kind text NOT NULL DEFAULT 'android' CHECK (kind IN ('android', 'browser'));

-- +goose Down
ALTER TABLE devices DROP COLUMN kind;
