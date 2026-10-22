-- +goose Up

-- Short-lived, revocable share tokens for showing a frame's library on a TV
-- (the /tv page and Cast senders). The token is the credential.
CREATE TABLE tv_links (
    id               uuid PRIMARY KEY,
    frame_id         uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    token            text        NOT NULL UNIQUE,
    created_by       uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    purpose          text        NOT NULL DEFAULT 'tv',
    album_id         uuid        REFERENCES albums ON DELETE CASCADE,
    kind             text,
    placement_ids    uuid[],
    interval_seconds integer     NOT NULL DEFAULT 10,
    expires_at       timestamptz NOT NULL,
    revoked_at       timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX tv_links_frame_idx ON tv_links (frame_id);

-- +goose Down
DROP TABLE tv_links;
