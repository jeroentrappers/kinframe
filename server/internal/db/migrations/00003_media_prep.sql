-- +goose Up

-- Client-side preparation (README §5.3 "Prepared uploads"): the tus upload
-- declares what the client produced; the worker verifies it and either stores
-- it as the master directly or falls back to the full transcode.
ALTER TABLE uploads ADD COLUMN prepared text;

ALTER TABLE media
    ADD COLUMN prepared             text,
    -- How the master was made: 'transcode' (full pipeline), 'prepared' (the
    -- client's master verified and stored as-is, metadata stripped again) or
    -- 'fallback' (a prepared upload failed verification and was transcoded).
    ADD COLUMN pipeline             text CHECK (pipeline IN ('transcode', 'prepared', 'fallback')),
    ADD COLUMN pipeline_note        text,
    -- A Live/Motion Photo clip: muted by default, no poster or thumbnails; the
    -- still of its item is the poster.
    ADD COLUMN motion               boolean NOT NULL DEFAULT false,
    ADD COLUMN declared_width       integer,
    ADD COLUMN declared_height      integer,
    ADD COLUMN declared_duration_ms integer;

-- A frame rendition that reuses the master's bytes (same key) instead of a transcode.
ALTER TABLE renditions ADD COLUMN reused_master boolean NOT NULL DEFAULT false;

-- Live items: the still is media_id (kind photo), the motion clip a second media (kind video).
ALTER TABLE items ADD COLUMN motion_media_id uuid REFERENCES media;
CREATE INDEX items_motion_media_idx ON items (motion_media_id) WHERE motion_media_id IS NOT NULL;

-- +goose Down
DROP INDEX items_motion_media_idx;
ALTER TABLE items DROP COLUMN motion_media_id;
ALTER TABLE renditions DROP COLUMN reused_master;
ALTER TABLE media DROP COLUMN prepared, DROP COLUMN pipeline, DROP COLUMN pipeline_note, DROP COLUMN motion,
    DROP COLUMN declared_width, DROP COLUMN declared_height, DROP COLUMN declared_duration_ms;
ALTER TABLE uploads DROP COLUMN prepared;
