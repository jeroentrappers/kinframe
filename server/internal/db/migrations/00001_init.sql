-- +goose Up

-- People. Rows are soft-deleted (sub cleared) so that items they sent can keep
-- their sender after the account is gone.
CREATE TABLE users (
    id                   uuid PRIMARY KEY,
    sub                  text UNIQUE,
    email                text,
    display_name         text        NOT NULL,
    avatar_key           text,
    share_location_names boolean     NOT NULL DEFAULT false,
    locale               text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    deleted_at           timestamptz
);

CREATE TABLE frames (
    id                       uuid PRIMARY KEY,
    name                     text        NOT NULL,
    location                 text,
    device_id                uuid,
    default_permissions      jsonb       NOT NULL,
    approve_joins_on_frame   boolean     NOT NULL DEFAULT false,
    invite_requires_approval boolean     NOT NULL DEFAULT true,
    moderation               boolean     NOT NULL DEFAULT false,
    settings                 jsonb       NOT NULL,
    settings_version         integer     NOT NULL DEFAULT 1,
    -- leaf path ("slideshow.interval_s") -> settings_version that last changed it
    settings_field_versions  jsonb       NOT NULL DEFAULT '{}',
    -- change feed head; also the per-frame lock that serialises feed writes
    latest_seq               bigint      NOT NULL DEFAULT 0,
    -- devices with 0 < since < feed_floor_seq must reset
    feed_floor_seq           bigint      NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL DEFAULT now(),
    deleted_at               timestamptz
);

CREATE TABLE devices (
    id                       uuid PRIMARY KEY,
    secret_hash              text        NOT NULL,
    frame_id                 uuid REFERENCES frames,
    hardware                 jsonb       NOT NULL,
    screen_w                 integer     NOT NULL,
    screen_h                 integer     NOT NULL,
    density_dpi              integer,
    orientation              text,
    decode_w                 integer     NOT NULL,
    decode_h                 integer     NOT NULL,
    app_version              text        NOT NULL,
    app_version_code         integer     NOT NULL,
    app_channel              text        NOT NULL DEFAULT 'stable',
    locale                   text,
    timezone                 text,
    created_at               timestamptz NOT NULL DEFAULT now(),
    revoked_at               timestamptz,
    last_seen_at             timestamptz,
    last_heartbeat           jsonb,
    last_heartbeat_at        timestamptz,
    last_unclean_shutdown_at timestamptz
);
CREATE INDEX devices_frame_idx ON devices (frame_id);

ALTER TABLE frames ADD CONSTRAINT frames_device_fk FOREIGN KEY (device_id) REFERENCES devices;

-- One active code per device; codes are unique among all stored (not yet purged) codes.
CREATE TABLE device_codes (
    device_id  uuid PRIMARY KEY REFERENCES devices ON DELETE CASCADE,
    code       text        NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE memberships (
    frame_id     uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    role         text        NOT NULL CHECK (role IN ('owner', 'admin', 'member')),
    permissions  jsonb       NOT NULL,
    joined_at    timestamptz NOT NULL DEFAULT now(),
    last_sent_at timestamptz,
    PRIMARY KEY (frame_id, user_id)
);
CREATE UNIQUE INDEX memberships_one_owner ON memberships (frame_id) WHERE role = 'owner';
CREATE INDEX memberships_user_idx ON memberships (user_id);

CREATE TABLE invites (
    id         uuid PRIMARY KEY,
    frame_id   uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    token      text        NOT NULL UNIQUE,
    created_by uuid REFERENCES users ON DELETE SET NULL,
    max_uses   integer,
    uses       integer     NOT NULL DEFAULT 0,
    note       text,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX invites_frame_idx ON invites (frame_id);

CREATE TABLE join_requests (
    id         uuid PRIMARY KEY,
    frame_id   uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    via        text        NOT NULL CHECK (via IN ('frame_code', 'invite')),
    invite_id  uuid REFERENCES invites ON DELETE SET NULL,
    invited_by uuid REFERENCES users ON DELETE SET NULL,
    note       text,
    status     text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'denied')),
    created_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz
);
CREATE UNIQUE INDEX join_requests_one_pending ON join_requests (frame_id, user_id) WHERE status = 'pending';

-- tus uploads (the bytes live in the tusd file store).
CREATE TABLE uploads (
    id           text PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    filename     text,
    filetype     text,
    sha256       text,
    size         bigint      NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    consumed_at  timestamptz
);

-- The stored media: one master rendition plus derived frame renditions.
-- Several items (sends) can share one media row when the uploader is deduplicated.
CREATE TABLE media (
    id            uuid PRIMARY KEY,
    owner_user_id uuid REFERENCES users ON DELETE SET NULL,
    source_sha256 text,
    kind          text        NOT NULL CHECK (kind IN ('photo', 'video')),
    status        text        NOT NULL DEFAULT 'processing' CHECK (status IN ('processing', 'ready', 'failed')),
    failure_code  text,
    source_path   text,
    source_mime   text,
    trim_start_ms integer,
    trim_end_ms   integer,
    include_audio boolean     NOT NULL DEFAULT true,
    width         integer,
    height        integer,
    duration_ms   integer,
    has_audio     boolean,
    captured_at   timestamptz,
    master_key    text,
    master_sha256 text,
    master_bytes  bigint,
    master_mime   text,
    poster_key    text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX media_dedupe_idx ON media (owner_user_id, source_sha256);

CREATE TABLE renditions (
    media_id   uuid        NOT NULL REFERENCES media ON DELETE CASCADE,
    variant    text        NOT NULL,
    key        text        NOT NULL,
    sha256     text        NOT NULL,
    bytes      bigint      NOT NULL,
    mime       text        NOT NULL,
    width      integer     NOT NULL,
    height     integer     NOT NULL,
    poster_key text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (media_id, variant)
);

CREATE TABLE items (
    id              uuid PRIMARY KEY,
    media_id        uuid        NOT NULL REFERENCES media,
    sender_user_id  uuid REFERENCES users ON DELETE SET NULL,
    sender_frame_id uuid REFERENCES frames ON DELETE SET NULL,
    caption         text,
    caption_edited  boolean     NOT NULL DEFAULT false,
    focal_x         double precision,
    focal_y         double precision,
    fit             text CHECK (fit IN ('fill', 'fit')),
    captured_hint   timestamptz,
    thumb256_key    text,
    thumb512_key    text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX items_media_idx ON items (media_id);
CREATE INDEX items_sender_idx ON items (sender_user_id);

CREATE TABLE sends (
    id             uuid PRIMARY KEY,
    sender_user_id uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sends_sender_idx ON sends (sender_user_id, id DESC);

CREATE TABLE send_items (
    send_id  uuid    NOT NULL REFERENCES sends ON DELETE CASCADE,
    item_id  uuid    NOT NULL REFERENCES items ON DELETE CASCADE,
    position integer NOT NULL,
    PRIMARY KEY (send_id, item_id)
);

CREATE TABLE placements (
    id                 uuid PRIMARY KEY,
    frame_id           uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    item_id            uuid        NOT NULL REFERENCES items ON DELETE CASCADE,
    send_id            uuid REFERENCES sends ON DELETE SET NULL,
    status             text        NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'ready', 'delivered', 'seen', 'failed')),
    hidden             boolean     NOT NULL DEFAULT false,
    trashed_at         timestamptz,
    pending_moderation boolean     NOT NULL DEFAULT false,
    fit_override       text,
    focal_override_x   double precision,
    focal_override_y   double precision,
    rotation           integer     NOT NULL DEFAULT 0,
    reaction           text,
    reaction_at        timestamptz,
    delivered_at       timestamptz,
    seen_at            timestamptz,
    received_at        timestamptz NOT NULL DEFAULT now(),
    removed_at         timestamptz
);
CREATE UNIQUE INDEX placements_live_unique ON placements (frame_id, item_id) WHERE removed_at IS NULL;
CREATE INDEX placements_item_idx ON placements (item_id);
CREATE INDEX placements_send_idx ON placements (send_id);
CREATE INDEX placements_trash_idx ON placements (trashed_at) WHERE trashed_at IS NOT NULL AND removed_at IS NULL;

CREATE TABLE placement_replies (
    id           uuid PRIMARY KEY,
    placement_id uuid        NOT NULL REFERENCES placements ON DELETE CASCADE,
    preset       text        NOT NULL,
    at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX placement_replies_idx ON placement_replies (placement_id);

CREATE TABLE albums (
    id                 uuid PRIMARY KEY,
    frame_id           uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    name               text        NOT NULL,
    version            integer     NOT NULL DEFAULT 1,
    cover_placement_id uuid,
    schedule           jsonb,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE album_placements (
    album_id     uuid    NOT NULL REFERENCES albums ON DELETE CASCADE,
    placement_id uuid    NOT NULL REFERENCES placements ON DELETE CASCADE,
    position     integer NOT NULL,
    PRIMARY KEY (album_id, placement_id)
);

-- Compacted change feed: the latest change per entity. Superseded entries are
-- overwritten, so reading "everything after since" yields exactly the entities
-- that changed, and since=0 is a full snapshot.
CREATE TABLE feed (
    frame_id   uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    kind       text        NOT NULL,
    entity_id  uuid        NOT NULL,
    seq        bigint      NOT NULL,
    removed    boolean     NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (frame_id, kind, entity_id)
);
CREATE INDEX feed_seq_idx ON feed (frame_id, seq);

CREATE TABLE jobs (
    id           bigserial PRIMARY KEY,
    kind         text        NOT NULL,
    payload      jsonb       NOT NULL,
    run_after    timestamptz NOT NULL DEFAULT now(),
    locked_until timestamptz,
    attempts     integer     NOT NULL DEFAULT 0,
    last_error   text,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX jobs_ready_idx ON jobs (run_after, id);

CREATE TABLE idempotency_keys (
    user_id    uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    scope      text        NOT NULL,
    key        text        NOT NULL,
    response   jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, scope, key)
);

CREATE TABLE push_endpoints (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    kind        text        NOT NULL,
    endpoint    text        NOT NULL,
    p256dh      text,
    auth        text,
    device_name text,
    locale      text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, kind, endpoint)
);

CREATE TABLE server_settings (
    key   text PRIMARY KEY,
    value jsonb NOT NULL
);

-- +goose Down
DROP TABLE server_settings, push_endpoints, idempotency_keys, jobs, feed, album_placements, albums,
    placement_replies, placements, send_items, sends, items, renditions, media, uploads, join_requests,
    invites, memberships, device_codes;
ALTER TABLE frames DROP CONSTRAINT frames_device_fk;
DROP TABLE devices, frames, users;
