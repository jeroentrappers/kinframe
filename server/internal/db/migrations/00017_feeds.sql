-- +goose Up

-- Feeds (docs/spec/feeds.md, tables and algorithm in docs/spec/feeds-data-model.md): private family channels
-- that frames subscribe to. A post is one send in one feed; its items are the send's items (no copies), and
-- each active subscription gets ordinary placements (origin 'feed').

CREATE TABLE feeds (
    id                          uuid PRIMARY KEY,
    name                        text        NOT NULL,
    cover_item_id               uuid REFERENCES items ON DELETE SET NULL,
    auto_approve_subscriptions  boolean     NOT NULL DEFAULT false,
    invite_requires_approval    boolean     NOT NULL DEFAULT true,
    members_see_reactions       boolean     NOT NULL DEFAULT false,
    posters_can_invite          boolean     NOT NULL DEFAULT false,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    deleted_at                  timestamptz            -- soft delete while the removal job runs
);

CREATE TABLE feed_members (
    feed_id        uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    user_id        uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    role           text        NOT NULL CHECK (role IN ('owner', 'admin', 'poster')),
    joined_at      timestamptz NOT NULL DEFAULT now(),
    last_posted_at timestamptz,
    PRIMARY KEY (feed_id, user_id)
);
CREATE UNIQUE INDEX feed_members_one_owner ON feed_members (feed_id) WHERE role = 'owner';
CREATE INDEX feed_members_user_idx ON feed_members (user_id);

-- Like invites, plus a purpose and a short code (8 Crockford base32 characters, never all digits,
-- stored upper case without the hyphen).
CREATE TABLE feed_invites (
    id         uuid PRIMARY KEY,
    feed_id    uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    purpose    text        NOT NULL CHECK (purpose IN ('join', 'subscribe')),
    token      text        NOT NULL UNIQUE,
    code       text        NOT NULL UNIQUE,
    created_by uuid REFERENCES users ON DELETE SET NULL,
    max_uses   integer,
    uses       integer     NOT NULL DEFAULT 0,
    note       text,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX feed_invites_feed_idx ON feed_invites (feed_id);

CREATE TABLE feed_join_requests (
    id         uuid PRIMARY KEY,
    feed_id    uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    invite_id  uuid REFERENCES feed_invites ON DELETE SET NULL,
    invited_by uuid REFERENCES users ON DELETE SET NULL,
    note       text,
    status     text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'denied')),
    created_at timestamptz NOT NULL DEFAULT now(),
    decided_at timestamptz
);
CREATE UNIQUE INDEX feed_join_requests_one_pending ON feed_join_requests (feed_id, user_id) WHERE status = 'pending';

CREATE TABLE subscriptions (
    id             uuid PRIMARY KEY,
    feed_id        uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    frame_id       uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    status         text        NOT NULL CHECK (status IN ('pending', 'active', 'paused', 'rejected', 'removed')),
    history        text        NOT NULL CHECK (history IN ('none', 'last_n', 'all')),
    history_count  integer CHECK (history_count BETWEEN 1 AND 1000),   -- last_n only
    requested_by   uuid REFERENCES users ON DELETE SET NULL,
    via_invite_id  uuid REFERENCES feed_invites ON DELETE SET NULL,
    via_invite     boolean     NOT NULL DEFAULT false,               -- survives the invite row
    decided_by     uuid REFERENCES users ON DELETE SET NULL,
    removed_reason text CHECK (removed_reason IN ('unsubscribed', 'removed_by_feed', 'frame_removed', 'feed_deleted', 'withdrawn')),
    activated_at   timestamptz,            -- first activation: the history boundary, never moved
    paused_at      timestamptz,
    ended_at       timestamptz,            -- rejected or removed; rows are deleted 30 days later
    backfill_state text        NOT NULL DEFAULT 'idle' CHECK (backfill_state IN ('idle', 'queued', 'running')),
    backfill_placed integer    NOT NULL DEFAULT 0,
    backfill_total  integer    NOT NULL DEFAULT 0,
    reconciled_version integer NOT NULL DEFAULT 0,   -- bumped by every change that needs a reconcile
    created_at     timestamptz NOT NULL DEFAULT now(),
    CHECK ((history = 'last_n') = (history_count IS NOT NULL))
);
CREATE UNIQUE INDEX subscriptions_one_live ON subscriptions (feed_id, frame_id)
    WHERE status IN ('pending', 'active', 'paused');
CREATE INDEX subscriptions_frame_idx ON subscriptions (frame_id) WHERE status IN ('pending', 'active', 'paused');
CREATE INDEX subscriptions_feed_active_idx ON subscriptions (feed_id) WHERE status = 'active';
CREATE INDEX subscriptions_feed_idx ON subscriptions (feed_id, created_at);

-- A post is one send in one feed.
CREATE TABLE feed_posts (
    id             uuid PRIMARY KEY,             -- UUIDv7: also the feed's time order
    feed_id        uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    send_id        uuid REFERENCES sends ON DELETE SET NULL,
    poster_user_id uuid REFERENCES users ON DELETE SET NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    removed_at     timestamptz,
    removed_by     uuid REFERENCES users ON DELETE SET NULL
);
CREATE INDEX feed_posts_feed_idx ON feed_posts (feed_id, id DESC) WHERE removed_at IS NULL;
CREATE INDEX feed_posts_poster_idx ON feed_posts (poster_user_id, feed_id);
CREATE INDEX feed_posts_send_idx ON feed_posts (send_id);
CREATE UNIQUE INDEX feed_posts_send_unique ON feed_posts (feed_id, send_id) WHERE removed_at IS NULL;

CREATE TABLE feed_post_items (
    post_id    uuid    NOT NULL REFERENCES feed_posts ON DELETE CASCADE,
    item_id    uuid    NOT NULL REFERENCES items ON DELETE CASCADE,
    position   integer NOT NULL,
    removed_at timestamptz,
    PRIMARY KEY (post_id, item_id)
);
CREATE INDEX feed_post_items_item_idx ON feed_post_items (item_id);

-- Which subscription delivered which post item, and through which placement. 'placed' rows hold the
-- placement; 'declined' rows are tombstones (the frame rejected or purged it) that stop backfills from
-- placing it again.
CREATE TABLE feed_deliveries (
    subscription_id uuid NOT NULL REFERENCES subscriptions ON DELETE CASCADE,
    post_id         uuid NOT NULL,
    item_id         uuid NOT NULL,
    placement_id    uuid REFERENCES placements ON DELETE SET NULL,
    state           text NOT NULL CHECK (state IN ('placed', 'declined')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (subscription_id, post_id, item_id),
    FOREIGN KEY (post_id, item_id) REFERENCES feed_post_items ON DELETE CASCADE
);
CREATE INDEX feed_deliveries_placement_idx ON feed_deliveries (placement_id) WHERE placement_id IS NOT NULL;
CREATE INDEX feed_deliveries_post_idx ON feed_deliveries (post_id, item_id);

-- Placement origin and holds.
ALTER TABLE placements
    ADD COLUMN origin text NOT NULL DEFAULT 'send' CHECK (origin IN ('send', 'share', 'feed', 'import')),
    ADD COLUMN origin_feed_id uuid REFERENCES feeds ON DELETE SET NULL,
    ADD COLUMN held_directly boolean NOT NULL DEFAULT true,   -- a send, forward, share or import holds it
    ADD COLUMN feed_suspended boolean NOT NULL DEFAULT false, -- only feed holds, none of them active (paused)
    ADD COLUMN backfill boolean NOT NULL DEFAULT false;       -- placed as history: frames don't treat it as new
CREATE INDEX placements_origin_feed_idx ON placements (origin_feed_id, frame_id) WHERE origin_feed_id IS NOT NULL;
CREATE INDEX placements_backfill_idx ON placements (frame_id) WHERE backfill AND removed_at IS NULL;

-- Capabilities the frame app reported in its latest heartbeat (Heartbeat.features). Backfilled feed
-- placements are withheld from frames whose app does not list 'feed_backfill'.
ALTER TABLE devices ADD COLUMN features text[] NOT NULL DEFAULT '{}';

UPDATE placements p SET origin = CASE
    WHEN p.shared_by_user_id IS NOT NULL THEN 'share'
    WHEN EXISTS (SELECT 1 FROM items i WHERE i.id = p.item_id AND i.sender_frame_id IS NOT NULL) THEN 'import'
    ELSE 'send' END;

-- +goose Down
ALTER TABLE devices DROP COLUMN features;
DROP INDEX placements_backfill_idx;
DROP INDEX placements_origin_feed_idx;
ALTER TABLE placements DROP COLUMN backfill, DROP COLUMN feed_suspended, DROP COLUMN held_directly,
    DROP COLUMN origin_feed_id, DROP COLUMN origin;
DROP TABLE feed_deliveries, feed_post_items, feed_posts, subscriptions, feed_join_requests, feed_invites,
    feed_members, feeds;
