-- +goose Up

-- Photo sources (docs/spec/photo-sources.md §15): an outside album (Immich, iCloud) synced into a kinframe album or
-- feed, and one-time Google Photos imports. Secrets (*_enc) are sealed with internal/secrets.

CREATE TABLE photo_sources (
    id                  uuid PRIMARY KEY,
    provider            text        NOT NULL CHECK (provider IN ('immich_link', 'immich_api', 'icloud_link')),
    owner_user_id       uuid REFERENCES users ON DELETE SET NULL,   -- null: owner gone (owner_lost_access)
    frame_id            uuid REFERENCES frames ON DELETE CASCADE,  -- album targets: the album's frame
    album_id            uuid REFERENCES albums ON DELETE CASCADE,
    feed_id             uuid REFERENCES feeds  ON DELETE CASCADE,
    name                text        NOT NULL,                       -- the outside album's name (or the given one)
    url_enc             bytea,                                      -- link, or Immich server URL (sealed)
    url_hint            text,
    server_url          text,                                       -- immich_api: the server URL without secrets (shown to managers)
    api_key_enc         bytea,
    password_enc        bytea,
    upstream_album_id   text,                                       -- Immich album id; iCloud: null
    upstream_key        text        NOT NULL,                       -- sha256(provider|host|album id or link key): duplicate check
    upstream_host       text        NOT NULL,                       -- for per-host concurrency and hints
    resolved_host       text,                                       -- iCloud partition host learned from a redirect
    history             text        NOT NULL CHECK (history IN ('none', 'last_n', 'all')),
    history_count       integer CHECK (history_count BETWEEN 1 AND 10000),
    on_upstream_delete  text        NOT NULL CHECK (on_upstream_delete IN ('mirror', 'keep')),
    include_videos      boolean     NOT NULL DEFAULT true,
    notify_reactions    boolean     NOT NULL DEFAULT true,
    status              text        NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active', 'paused', 'error', 'needs_attention')),
    warnings            text[]      NOT NULL DEFAULT '{}',
    error_code          text,
    error_detail        text,
    failures            integer     NOT NULL DEFAULT 0,
    error_since         timestamptz,
    attention_pushed    jsonb       NOT NULL DEFAULT '{}',          -- error code -> last push time (7-day rule)
    pending_removals    integer     NOT NULL DEFAULT 0,
    change_hint         text,                                       -- Immich updatedAt+assetCount / iCloud streamCtag
    full_listing_at     timestamptz,
    upstream_count      integer,                                    -- assets at the last listing
    initial_done        boolean     NOT NULL DEFAULT false,
    hold_since          timestamptz,                                -- feed targets: first item waiting for its post (debounce)
    last_new_at         timestamptz,                                -- feed targets: last time new upstream items appeared
    run_started_at      timestamptz,                                -- non-null while running
    last_run            jsonb,                                      -- {started_at, finished_at, added, updated, removed, skipped, failed, bytes}
    last_synced_at      timestamptz,
    last_attempt_at     timestamptz,
    next_sync_at        timestamptz NOT NULL DEFAULT now(),
    last_manual_sync_at timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CHECK ((album_id IS NOT NULL AND frame_id IS NOT NULL AND feed_id IS NULL)
        OR (feed_id IS NOT NULL AND album_id IS NULL AND frame_id IS NULL)),
    CHECK ((history = 'last_n') = (history_count IS NOT NULL))
);
CREATE UNIQUE INDEX photo_sources_upstream_unique ON photo_sources (COALESCE(album_id, feed_id), upstream_key);
CREATE INDEX photo_sources_album_idx ON photo_sources (album_id) WHERE album_id IS NOT NULL;
CREATE INDEX photo_sources_feed_idx ON photo_sources (feed_id) WHERE feed_id IS NOT NULL;
CREATE INDEX photo_sources_owner_idx ON photo_sources (owner_user_id);
CREATE INDEX photo_sources_due_idx ON photo_sources (next_sync_at) WHERE status <> 'paused';

CREATE TABLE photo_source_items (
    source_id            uuid        NOT NULL REFERENCES photo_sources ON DELETE CASCADE,
    upstream_id          text        NOT NULL,                      -- Immich asset id / iCloud photoGuid
    state                text        NOT NULL CHECK (state IN ('pending', 'imported', 'duplicate', 'skipped',
                                                               'failed', 'removed_locally', 'gone_upstream')),
    skip_reason          text CHECK (skip_reason IN ('video_off', 'too_long', 'too_large', 'unsupported', 'over_limit', 'history')),
    failure_code         text,
    attempts             integer     NOT NULL DEFAULT 0,
    item_id              uuid REFERENCES items ON DELETE SET NULL,
    send_id              uuid REFERENCES sends ON DELETE SET NULL, -- null on an imported row of a feed source: waiting for its post
    replacement_media_id uuid REFERENCES media ON DELETE SET NULL, -- the file changed upstream: swapped in once processed
    upstream_checksum    text,                                      -- Immich SHA-1 / iCloud derivative checksum
    content_sha256       text,
    upstream_caption     text,
    upstream_taken_at    timestamptz,
    upstream_added_at    timestamptz,                               -- iCloud batchDateCreated (quiet history order)
    upstream_updated     text,                                      -- provider's updatedAt, for edit detection
    kind                 text CHECK (kind IN ('photo', 'video', 'live')),
    bytes                bigint,
    first_seen_at        timestamptz NOT NULL DEFAULT now(),
    imported_at          timestamptz,
    gone_at              timestamptz,
    retry_at             timestamptz,                               -- failed rows: next retry (daily, at most 7 days)
    PRIMARY KEY (source_id, upstream_id)
);
CREATE INDEX photo_source_items_item_idx ON photo_source_items (item_id) WHERE item_id IS NOT NULL;
CREATE INDEX photo_source_items_pending_idx ON photo_source_items (source_id) WHERE state = 'pending';
CREATE INDEX photo_source_items_checksum_idx ON photo_source_items (upstream_checksum) WHERE upstream_checksum IS NOT NULL;

CREATE TABLE google_imports (
    id                 uuid PRIMARY KEY,
    user_id            uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    frame_id           uuid REFERENCES frames ON DELETE CASCADE,
    album_id           uuid REFERENCES albums ON DELETE SET NULL,  -- null after deletion: target_gone
    feed_id            uuid REFERENCES feeds  ON DELETE SET NULL,
    target_kind        text        NOT NULL CHECK (target_kind IN ('album', 'feed')),
    status             text        NOT NULL CHECK (status IN ('needs_auth', 'picking', 'importing', 'done',
                                                              'failed', 'cancelled', 'expired')),
    return_uri         text        NOT NULL,
    state_hash         text UNIQUE,                                 -- sha256 of the OAuth state; cleared after use
    auth_url_enc       bytea,                                       -- the consent URL (carries the state), shown to the importer
    pkce_verifier_enc  bytea,
    access_token_enc   bytea,
    token_expires_at   timestamptz,
    picker_session_id  text,
    picker_uri         text,
    picker_expires_at  timestamptz,
    poll_interval_ms   integer,
    picked             integer     NOT NULL DEFAULT 0,
    imported           integer     NOT NULL DEFAULT 0,
    duplicates         integer     NOT NULL DEFAULT 0,
    skipped            integer     NOT NULL DEFAULT 0,
    failed             integer     NOT NULL DEFAULT 0,
    bytes              bigint      NOT NULL DEFAULT 0,
    done_ids           text[]      NOT NULL DEFAULT '{}',           -- Google media item ids already handled (resume)
    send_ids           uuid[]      NOT NULL DEFAULT '{}',
    error_code         text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    finished_at        timestamptz
);
CREATE INDEX google_imports_user_idx ON google_imports (user_id, created_at DESC);
CREATE INDEX google_imports_active_idx ON google_imports (status) WHERE status IN ('needs_auth', 'picking', 'importing');

-- Credit and the source send.
ALTER TABLE sends
    ADD COLUMN via text CHECK (via IN ('immich', 'icloud', 'google_photos')),
    ADD COLUMN photo_source_id uuid REFERENCES photo_sources ON DELETE SET NULL,
    ADD COLUMN google_import_id uuid REFERENCES google_imports ON DELETE SET NULL;
ALTER TABLE items
    ADD COLUMN via text CHECK (via IN ('immich', 'icloud', 'google_photos')),
    ADD COLUMN via_source_id uuid REFERENCES photo_sources ON DELETE SET NULL,
    ADD COLUMN captured_override timestamptz;                       -- upstream capture time, wins over EXIF
ALTER TABLE media ADD COLUMN original_sha256 text;                 -- client-declared hash of a prepared upload's original (§8.1)
CREATE INDEX media_original_sha_idx ON media (original_sha256) WHERE original_sha256 IS NOT NULL;
CREATE INDEX media_source_sha_idx ON media (source_sha256) WHERE source_sha256 IS NOT NULL;
-- Albums whose photos play only when the frame plays the album (§9.1).
ALTER TABLE albums ADD COLUMN only_in_album_mode boolean NOT NULL DEFAULT false;
-- A source's first-run posts are feed history: the fanout places them quietly (backfill).
ALTER TABLE feed_posts ADD COLUMN quiet boolean NOT NULL DEFAULT false;

-- +goose Down
ALTER TABLE albums DROP COLUMN only_in_album_mode;
ALTER TABLE feed_posts DROP COLUMN quiet;
DROP INDEX IF EXISTS media_source_sha_idx;
DROP INDEX IF EXISTS media_original_sha_idx;
ALTER TABLE media DROP COLUMN original_sha256;
ALTER TABLE items DROP COLUMN via, DROP COLUMN via_source_id, DROP COLUMN captured_override;
ALTER TABLE sends DROP COLUMN via, DROP COLUMN photo_source_id, DROP COLUMN google_import_id;
DROP TABLE google_imports, photo_source_items, photo_sources;
