-- +goose Up

-- Families on different servers (docs/spec/multi-server.md, README §5.22). Owned by server-federation
-- (docs/spec/leaving-servers-work.md). "A" is the server with the family, "B" the server with the frame; one server is
-- both. Subscription ids on the wire are always A's ids (remote_subscribers.id).

-- ---------------------------------------------------------------- identity
-- One row: this server's stable id (discovery `server_id`).
CREATE TABLE server_identity (
    singleton  boolean     PRIMARY KEY DEFAULT true CHECK (singleton),
    server_id  text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Ed25519 signing keys. The private key is sealed with internal/secrets when KINFRAME_SECRETS_KEY is set (else
-- stored as is, like other credentials). One current key; a rotated key stays published until expires_at (7 days).
CREATE TABLE federation_keys (
    key_id          text PRIMARY KEY,
    public_key      bytea       NOT NULL,                           -- 32 bytes
    private_key_enc bytea       NOT NULL,
    is_current      boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    retired_at      timestamptz,
    expires_at      timestamptz
);
CREATE UNIQUE INDEX federation_keys_one_current ON federation_keys (is_current) WHERE is_current;

-- ---------------------------------------------------------------- other servers
-- What this server knows about another one: its well-known document (refreshed daily and on a signature failure).
CREATE TABLE remote_servers (
    id              uuid PRIMARY KEY,
    base_url        text        NOT NULL UNIQUE,                    -- normalized: https://host[:port], no trailing slash
    host            text        NOT NULL,
    server_id       text,
    server_name     text,
    document        jsonb,                                          -- the last good /.well-known/kinframe
    keys            jsonb       NOT NULL DEFAULT '[]',              -- [{key_id, public_key, expires_at}]
    fetched_at      timestamptz,
    last_contact_at timestamptz,
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX remote_servers_host_idx ON remote_servers (host);

-- Blocks: instance-wide (feed_id null, by the operator) or for one family (by its owner/admins).
CREATE TABLE blocked_servers (
    id          uuid PRIMARY KEY,
    host        text        NOT NULL,
    feed_id     uuid REFERENCES feeds ON DELETE CASCADE,
    server_name text,
    reason      text,
    created_by  uuid REFERENCES users ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX blocked_servers_instance_once ON blocked_servers (host) WHERE feed_id IS NULL;
CREATE UNIQUE INDEX blocked_servers_feed_once ON blocked_servers (feed_id, host) WHERE feed_id IS NOT NULL;

-- ---------------------------------------------------------------- A: the family's side
ALTER TABLE feeds ADD COLUMN allow_remote_frames boolean NOT NULL DEFAULT false;
ALTER TABLE feed_invites ADD COLUMN allow_remote boolean NOT NULL DEFAULT false;

-- A frame on another server following a family here. Its id is the subscription id both servers use.
CREATE TABLE remote_subscribers (
    id                uuid PRIMARY KEY,
    feed_id           uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    server_id         uuid        NOT NULL REFERENCES remote_servers ON DELETE CASCADE,
    remote_frame_id   uuid        NOT NULL,                         -- B's frame id, as B told it
    follow_id         uuid,                                         -- B's remote_follows id, as B told it
    frame_name        text        NOT NULL,
    requested_by_name text,
    status            text        NOT NULL CHECK (status IN ('pending', 'active', 'rejected', 'removed')),
    history           text        NOT NULL CHECK (history IN ('none', 'last_n', 'all')),
    history_count     integer CHECK (history_count BETWEEN 1 AND 1000),
    birthdays         boolean     NOT NULL DEFAULT true,
    show_comments     boolean     NOT NULL DEFAULT false,
    via_invite_id     uuid REFERENCES feed_invites ON DELETE SET NULL,
    decided_by        uuid REFERENCES users ON DELETE SET NULL,
    removed_reason    text CHECK (removed_reason IN ('unsubscribed', 'removed_by_feed', 'feed_deleted', 'server_blocked', 'withdrawn', 'rejected')),
    activated_at      timestamptz,                                  -- the history boundary, never moved
    ended_at          timestamptz,
    last_pull_at      timestamptz,
    last_ping_at      timestamptz,
    pinged_seq        bigint      NOT NULL DEFAULT 0,               -- federation_changes.seq covered by the last ping
    created_at        timestamptz NOT NULL DEFAULT now(),
    CHECK ((history = 'last_n') = (history_count IS NOT NULL))
);
CREATE UNIQUE INDEX remote_subscribers_live_once ON remote_subscribers (feed_id, server_id, remote_frame_id)
    WHERE status IN ('pending', 'active');
CREATE INDEX remote_subscribers_server_idx ON remote_subscribers (server_id);

-- The family's change log for remote subscribers, written by the triggers below (never by Go code for posts,
-- items, birthdays and comments; server-federation writes `subscription` rows itself). Rows carry ids only: the
-- changes endpoint renders the current state when B pulls. Kept 30 days; an older cursor gets `reset`.
CREATE TABLE federation_changes (
    seq        bigserial PRIMARY KEY,
    feed_id    uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    kind       text        NOT NULL CHECK (kind IN ('post.upsert', 'post.remove', 'items.remove', 'birthdays', 'comments', 'subscription')),
    post_id    uuid,
    item_ids   uuid[],
    subscriber_id uuid REFERENCES remote_subscribers ON DELETE CASCADE, -- `subscription` rows: only for that subscriber
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX federation_changes_feed_idx ON federation_changes (feed_id, seq);

-- Activity from remote frames, back on A: reactions and "seen" per item (receipts and reaction rows), and frame
-- entries in threads as `comments` rows of kind 'frame' with frame_id null and remote_subscriber_id set.
CREATE TABLE remote_frame_reactions (
    subscriber_id uuid        NOT NULL REFERENCES remote_subscribers ON DELETE CASCADE,
    post_id       uuid        NOT NULL,
    item_id       uuid        NOT NULL,
    reaction      text        NOT NULL CHECK (reaction IN ('love', 'laugh', 'wow', 'sad')),
    at            timestamptz NOT NULL,
    PRIMARY KEY (subscriber_id, post_id, item_id)
);
CREATE INDEX remote_frame_reactions_post_idx ON remote_frame_reactions (post_id);
CREATE TABLE remote_frame_seen (
    subscriber_id uuid        NOT NULL REFERENCES remote_subscribers ON DELETE CASCADE,
    post_id       uuid        NOT NULL,
    item_id       uuid        NOT NULL,
    seen_at       timestamptz NOT NULL,
    PRIMARY KEY (subscriber_id, post_id, item_id)
);
CREATE INDEX remote_frame_seen_post_idx ON remote_frame_seen (post_id);
CREATE TABLE remote_activity_seen (                                 -- idempotency of FederationActivity events
    subscriber_id uuid        NOT NULL REFERENCES remote_subscribers ON DELETE CASCADE,
    event_id      uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (subscriber_id, event_id)
);
ALTER TABLE comments
    ADD COLUMN remote_subscriber_id uuid REFERENCES remote_subscribers ON DELETE SET NULL,
    ADD COLUMN frame_server_name    text;                          -- snapshot: "Oma's frame (kinframe.appmire.be)"

-- ---------------------------------------------------------------- B: the frame's side
-- A frame here following a family on another server.
CREATE TABLE remote_follows (
    id                     uuid PRIMARY KEY,
    frame_id               uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    server_id              uuid        NOT NULL REFERENCES remote_servers ON DELETE CASCADE,
    remote_subscription_id uuid,                                    -- A's id; null until A recorded it
    invite_url             text        NOT NULL,
    feed_name              text        NOT NULL,
    cover_url              text,
    status                 text        NOT NULL CHECK (status IN ('pending', 'active', 'rejected', 'removed', 'error')),
    history                text        NOT NULL CHECK (history IN ('none', 'last_n', 'all')),
    history_count          integer CHECK (history_count BETWEEN 1 AND 1000),
    birthdays              boolean     NOT NULL DEFAULT true,
    birthday_people        jsonb       NOT NULL DEFAULT '[]',       -- the last `birthdays` change ([FederationBirthday])
    on_end                 text        NOT NULL DEFAULT 'remove' CHECK (on_end IN ('remove', 'keep')),
    requested_by           uuid REFERENCES users ON DELETE SET NULL,
    pull_cursor            text,                                    -- stored only after a page was applied
    next_pull_at           timestamptz,
    last_pull_at           timestamptz,
    last_error             text,
    error_since            timestamptz,
    removed_reason         text CHECK (removed_reason IN ('unsubscribed', 'removed_by_feed', 'feed_deleted', 'server_blocked', 'withdrawn', 'rejected')),
    unsubscribe_pending    boolean     NOT NULL DEFAULT false,      -- tell A again until it answered
    activated_at           timestamptz,
    ended_at               timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    CHECK ((history = 'last_n') = (history_count IS NOT NULL))
);
CREATE UNIQUE INDEX remote_follows_remote_once ON remote_follows (server_id, remote_subscription_id)
    WHERE remote_subscription_id IS NOT NULL;
CREATE INDEX remote_follows_frame_idx ON remote_follows (frame_id);
CREATE INDEX remote_follows_due_idx ON remote_follows (next_pull_at) WHERE status IN ('pending', 'active', 'error');

-- The poster on A, as shown on B ("Lies"): the owner of copied items.
CREATE TABLE remote_senders (
    id           uuid PRIMARY KEY,
    server_id    uuid        NOT NULL REFERENCES remote_servers ON DELETE CASCADE,
    poster_key   text        NOT NULL,                              -- FederationPost.poster_key
    display_name text        NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (server_id, poster_key)
);

-- Copied items: one local item per remote item and follow (downloaded once, renditions as for any item).
CREATE TABLE remote_items (
    item_id        uuid PRIMARY KEY REFERENCES items ON DELETE CASCADE,
    follow_id      uuid        NOT NULL REFERENCES remote_follows ON DELETE CASCADE,
    remote_post_id uuid        NOT NULL,
    remote_item_id uuid        NOT NULL,
    sender_id      uuid REFERENCES remote_senders ON DELETE SET NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (follow_id, remote_item_id)
);
CREATE INDEX remote_items_post_idx ON remote_items (follow_id, remote_post_id);
ALTER TABLE items ADD COLUMN remote_sender_id uuid REFERENCES remote_senders ON DELETE SET NULL;
-- Remote placements: origin 'feed', origin_feed_id null, origin_remote_follow_id set (Placement.origin_remote).
ALTER TABLE placements ADD COLUMN origin_remote_follow_id uuid REFERENCES remote_follows ON DELETE SET NULL;
CREATE INDEX placements_remote_follow_idx ON placements (origin_remote_follow_id, frame_id) WHERE origin_remote_follow_id IS NOT NULL;

-- B's queue of activity to send to A (reactions, replies, seen, frame name/show comments), sent in batches.
CREATE TABLE remote_activity_outbox (
    id         uuid PRIMARY KEY,
    follow_id  uuid        NOT NULL REFERENCES remote_follows ON DELETE CASCADE,
    event      jsonb       NOT NULL,                                -- one FederationActivity event, or {"frame": {...}}
    created_at timestamptz NOT NULL DEFAULT now(),
    attempts   integer     NOT NULL DEFAULT 0
);
CREATE INDEX remote_activity_outbox_follow_idx ON remote_activity_outbox (follow_id, created_at);

-- ---------------------------------------------------------------- triggers on A
-- Only families with a live remote subscriber get change rows; circle posts never do.
-- +goose StatementBegin
CREATE FUNCTION federation_feed_has_remote(p_feed uuid) RETURNS boolean AS $$
    SELECT EXISTS (SELECT 1 FROM remote_subscribers WHERE feed_id = p_feed AND status IN ('pending', 'active'));
$$ LANGUAGE sql STABLE;
-- +goose StatementEnd

-- +goose StatementBegin
CREATE FUNCTION federation_post_change() RETURNS trigger AS $$
BEGIN
    IF NEW.circle_id IS NOT NULL OR NOT federation_feed_has_remote(NEW.feed_id) THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.removed_at IS NULL THEN
            INSERT INTO federation_changes (feed_id, kind, post_id) VALUES (NEW.feed_id, 'post.upsert', NEW.id);
        END IF;
    ELSIF OLD.removed_at IS NULL AND NEW.removed_at IS NOT NULL THEN
        INSERT INTO federation_changes (feed_id, kind, post_id) VALUES (NEW.feed_id, 'post.remove', NEW.id);
    ELSIF OLD.removed_at IS NOT NULL AND NEW.removed_at IS NULL THEN
        INSERT INTO federation_changes (feed_id, kind, post_id) VALUES (NEW.feed_id, 'post.upsert', NEW.id);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER feed_posts_federation AFTER INSERT OR UPDATE OF removed_at ON feed_posts
    FOR EACH ROW EXECUTE FUNCTION federation_post_change();

-- +goose StatementBegin
CREATE FUNCTION federation_post_item_change() RETURNS trigger AS $$
DECLARE
    v_feed   uuid;
    v_circle uuid;
BEGIN
    SELECT feed_id, circle_id INTO v_feed, v_circle FROM feed_posts WHERE id = NEW.post_id;
    IF NOT FOUND OR v_circle IS NOT NULL OR NOT federation_feed_has_remote(v_feed) THEN
        RETURN NEW;
    END IF;
    IF OLD.removed_at IS NULL AND NEW.removed_at IS NOT NULL THEN
        INSERT INTO federation_changes (feed_id, kind, post_id, item_ids) VALUES (v_feed, 'items.remove', NEW.post_id, ARRAY[NEW.item_id]);
    ELSIF OLD.removed_at IS NOT NULL AND NEW.removed_at IS NULL THEN
        INSERT INTO federation_changes (feed_id, kind, post_id) VALUES (v_feed, 'post.upsert', NEW.post_id);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER feed_post_items_federation AFTER UPDATE OF removed_at ON feed_post_items
    FOR EACH ROW EXECUTE FUNCTION federation_post_item_change();

-- +goose StatementBegin
CREATE FUNCTION federation_birthday_change() RETURNS trigger AS $$
DECLARE
    v_feed uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.feed_id ELSE NEW.feed_id END;
BEGIN
    IF federation_feed_has_remote(v_feed) THEN
        INSERT INTO federation_changes (feed_id, kind) VALUES (v_feed, 'birthdays');
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER feed_birthdays_federation AFTER INSERT OR UPDATE OR DELETE ON feed_birthdays
    FOR EACH ROW EXECUTE FUNCTION federation_birthday_change();

-- +goose StatementBegin
CREATE FUNCTION federation_comment_change() RETURNS trigger AS $$
DECLARE
    v_feed   uuid;
    v_circle uuid;
BEGIN
    IF NEW.kind <> 'person' THEN
        RETURN NEW;
    END IF;
    SELECT feed_id, circle_id INTO v_feed, v_circle FROM threads WHERE post_id = NEW.post_id;
    IF NOT FOUND OR v_feed IS NULL OR v_circle IS NOT NULL THEN
        RETURN NEW;
    END IF;
    IF EXISTS (SELECT 1 FROM remote_subscribers WHERE feed_id = v_feed AND status = 'active' AND show_comments) THEN
        INSERT INTO federation_changes (feed_id, kind, post_id) VALUES (v_feed, 'comments', NEW.post_id);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER comments_federation AFTER INSERT OR UPDATE OF body, body_enc, deleted_at, removed_at ON comments
    FOR EACH ROW EXECUTE FUNCTION federation_comment_change();

-- +goose Down
DROP TRIGGER comments_federation ON comments;
DROP FUNCTION federation_comment_change();
DROP TRIGGER feed_birthdays_federation ON feed_birthdays;
DROP FUNCTION federation_birthday_change();
DROP TRIGGER feed_post_items_federation ON feed_post_items;
DROP FUNCTION federation_post_item_change();
DROP TRIGGER feed_posts_federation ON feed_posts;
DROP FUNCTION federation_post_change();
DROP FUNCTION federation_feed_has_remote(uuid);
DROP TABLE remote_activity_outbox;
DROP INDEX placements_remote_follow_idx;
ALTER TABLE placements DROP COLUMN origin_remote_follow_id;
ALTER TABLE items DROP COLUMN remote_sender_id;
DROP TABLE remote_items, remote_senders, remote_follows;
ALTER TABLE comments DROP COLUMN frame_server_name, DROP COLUMN remote_subscriber_id;
DROP TABLE remote_activity_seen, remote_frame_seen, remote_frame_reactions, federation_changes, remote_subscribers;
ALTER TABLE feed_invites DROP COLUMN allow_remote;
ALTER TABLE feeds DROP COLUMN allow_remote_frames;
DROP TABLE blocked_servers, remote_servers, federation_keys, server_identity;
