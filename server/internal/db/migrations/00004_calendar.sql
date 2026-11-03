-- +goose Up

-- Calendar sources of a frame (README §5.8). URL, collection URL and password
-- are AES-GCM sealed (calendar.Sealer). `raw` is the last fetched ICS data
-- (gzip), so the window can move without a refetch; `events` is its expansion
-- for `window_key`.
CREATE TABLE calendar_sources (
    id                 uuid PRIMARY KEY,
    frame_id           uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    kind               text        NOT NULL CHECK (kind IN ('ics', 'caldav', 'local')),
    name               text        NOT NULL DEFAULT '',
    color              text        NOT NULL,
    enabled            boolean     NOT NULL DEFAULT true,
    position           integer     NOT NULL DEFAULT 0,
    url_enc            bytea,
    url_hint           text,
    username           text,
    password_enc       bytea,
    collection_url_enc bytea,
    etag               text,
    last_modified      text,
    raw                bytea,
    status             text        NOT NULL DEFAULT 'syncing',
    last_error         text,
    last_synced_at     timestamptz,
    last_attempt_at    timestamptz,
    failures           integer     NOT NULL DEFAULT 0,
    next_sync_at       timestamptz NOT NULL DEFAULT now(),
    window_key         text,
    events             jsonb,
    event_count        integer     NOT NULL DEFAULT 0,
    created_by         uuid REFERENCES users ON DELETE SET NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX calendar_sources_frame_idx ON calendar_sources (frame_id, position);
CREATE UNIQUE INDEX calendar_sources_local_idx ON calendar_sources (frame_id) WHERE kind = 'local';
CREATE INDEX calendar_sources_due_idx ON calendar_sources (next_sync_at) WHERE kind <> 'local' AND enabled;

-- Events sent to a frame. Accepted ones (until cleared by removing the local
-- calendar) are the local calendar's events.
CREATE TABLE event_invitations (
    id              uuid PRIMARY KEY,
    frame_id        uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    sender_id       uuid REFERENCES users ON DELETE SET NULL,
    sender_name     text        NOT NULL,
    status          text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'accepted', 'declined', 'withdrawn')),
    title           text        NOT NULL,
    all_day         boolean     NOT NULL DEFAULT false,
    start_at        timestamptz,
    end_at          timestamptz,
    start_date      date,
    end_date        date,
    location        text,
    notes           text,
    photo_key       text,
    photo_sha256    text,
    photo_placement text        NOT NULL DEFAULT 'top',
    created_at      timestamptz NOT NULL DEFAULT now(),
    decided_at      timestamptz,
    cleared_at      timestamptz
);
CREATE INDEX event_invitations_frame_idx ON event_invitations (frame_id, created_at DESC);

-- Countdowns marked per frame, with a snapshot of the event so they keep
-- showing when the event is outside the window.
CREATE TABLE calendar_countdowns (
    frame_id   uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    event_id   text        NOT NULL,
    source_id  uuid,
    title      text        NOT NULL,
    all_day    boolean     NOT NULL,
    start_at   timestamptz,
    start_date date,
    color      text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (frame_id, event_id)
);

-- What each frame was last sent (the `calendar` change renders from this).
CREATE TABLE frame_calendar (
    frame_id   uuid PRIMARY KEY REFERENCES frames ON DELETE CASCADE,
    digest     text        NOT NULL,
    window_key text        NOT NULL,
    payload    jsonb,
    emitted_at timestamptz NOT NULL DEFAULT now()
);

-- +goose Down
DROP TABLE frame_calendar, calendar_countdowns, event_invitations, calendar_sources;
