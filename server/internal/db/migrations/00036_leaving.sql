-- +goose Up

-- Leaving a family (docs/spec/leaving.md, README §5.21): taking photos away with a 30-day notice, asking to keep,
-- the account deletion handover, operator ownership transfers and the private memories filter. Owned by
-- server-leaving (docs/spec/leaving-servers-work.md).

-- One taking: a person took their own photos away from a family, a frame, or everything (account deletion).
CREATE TABLE takings (
    id               uuid PRIMARY KEY,
    user_id          uuid REFERENCES users ON DELETE SET NULL,      -- null once the account is gone
    user_name        text        NOT NULL,                          -- frozen for "Taken away by Tom"
    scope            text        NOT NULL CHECK (scope IN ('feed', 'frame', 'account')),
    feed_id          uuid REFERENCES feeds ON DELETE CASCADE,       -- scope feed
    frame_id         uuid REFERENCES frames ON DELETE CASCADE,      -- scope frame
    item_count       integer     NOT NULL DEFAULT 0,
    comments_deleted integer     NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    until            timestamptz NOT NULL,                          -- created_at + 30 days: the purge
    purged_at        timestamptz,                                   -- the purge ran
    CHECK ((scope = 'feed') = (feed_id IS NOT NULL)),
    CHECK ((scope = 'frame') = (frame_id IS NOT NULL))
);
CREATE INDEX takings_user_idx ON takings (user_id, created_at DESC);
CREATE INDEX takings_due_idx ON takings (until) WHERE purged_at IS NULL;

-- The items a taking covers (the leaver's own items in scope), so keep requests can pick among them.
CREATE TABLE taking_items (
    taking_id uuid NOT NULL REFERENCES takings ON DELETE CASCADE,
    item_id   uuid NOT NULL REFERENCES items ON DELETE CASCADE,
    PRIMARY KEY (taking_id, item_id)
);
CREATE INDEX taking_items_item_idx ON taking_items (item_id);

-- A taken-away placement has removed_at set (so every existing path treats it as gone and the change feed renders
-- placement.remove) and taken_away_at/until/by/taking_id set (so the frame's trash lists it under "Taken away by
-- Tom"). Allowed by a keep request: removed_at and taken_away_* are cleared and the placement is emitted again.
-- The purge clears taken_away_at (taking_id stays) and the media lifecycle follows.
ALTER TABLE placements
    ADD COLUMN taken_away_at    timestamptz,
    ADD COLUMN taken_away_until timestamptz,
    ADD COLUMN taken_away_by    uuid REFERENCES users ON DELETE SET NULL,
    ADD COLUMN taking_id        uuid REFERENCES takings ON DELETE SET NULL;
CREATE INDEX placements_taken_away_idx ON placements (frame_id, taking_id) WHERE taken_away_at IS NOT NULL;

-- Family posts and post items the same way: removed_at + removed_by (the leaver) + taking_id; a keep request on the
-- family side clears them again.
ALTER TABLE feed_posts ADD COLUMN taking_id uuid REFERENCES takings ON DELETE SET NULL;
ALTER TABLE feed_post_items ADD COLUMN taking_id uuid REFERENCES takings ON DELETE SET NULL;
CREATE INDEX feed_posts_taking_idx ON feed_posts (taking_id) WHERE taking_id IS NOT NULL;

-- Ask to keep: one request per scope (a frame or a family) and taking.
CREATE TABLE keep_requests (
    id            uuid PRIMARY KEY,
    taking_id     uuid        NOT NULL REFERENCES takings ON DELETE CASCADE,
    frame_id      uuid REFERENCES frames ON DELETE CASCADE,
    feed_id       uuid REFERENCES feeds ON DELETE CASCADE,
    asked_by      uuid REFERENCES users ON DELETE SET NULL,
    asked_by_name text        NOT NULL,
    note          text,
    all_items     boolean     NOT NULL DEFAULT true,               -- no selection: every item of the taking in scope
    status        text        NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'allowed', 'partly_allowed', 'declined', 'expired')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,                            -- the taking's until
    answered_at   timestamptz,
    CHECK ((frame_id IS NULL) <> (feed_id IS NULL))
);
CREATE UNIQUE INDEX keep_requests_frame_once ON keep_requests (taking_id, frame_id) WHERE frame_id IS NOT NULL;
CREATE UNIQUE INDEX keep_requests_feed_once ON keep_requests (taking_id, feed_id) WHERE feed_id IS NOT NULL;
CREATE INDEX keep_requests_open_idx ON keep_requests (expires_at) WHERE status = 'open';

CREATE TABLE keep_request_items (
    request_id uuid    NOT NULL REFERENCES keep_requests ON DELETE CASCADE,
    item_id    uuid    NOT NULL REFERENCES items ON DELETE CASCADE,
    allowed    boolean,                                            -- null until answered
    PRIMARY KEY (request_id, item_id)
);

-- Account deletion with "Leave my photos to the families": the item's sender becomes the family's (or frame's)
-- owner and the credit is frozen as "<former_sender_name> (former member)".
ALTER TABLE items
    ADD COLUMN former_sender_name text,
    ADD COLUMN handed_over_at     timestamptz;

-- The operator's log: account-deletion handovers and operator ownership transfers (kept 2 years).
CREATE TABLE ownership_log (
    id               uuid PRIMARY KEY,
    kind             text        NOT NULL CHECK (kind IN ('operator_transfer', 'account_handover')),
    scope_kind       text CHECK (scope_kind IN ('frame', 'feed')),
    frame_id         uuid REFERENCES frames ON DELETE SET NULL,
    feed_id          uuid REFERENCES feeds ON DELETE SET NULL,
    scope_name       text,
    from_user_id     uuid,                                          -- no FK: the account may be deleted
    from_name        text,
    to_user_id       uuid REFERENCES users ON DELETE SET NULL,
    to_name          text,
    operator_user_id uuid REFERENCES users ON DELETE SET NULL,
    operator_name    text,
    reason           text,
    removed_previous boolean,
    item_count       integer,
    details          jsonb       NOT NULL DEFAULT '{}',            -- handovers: per scope counts
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ownership_log_created_idx ON ownership_log (created_at DESC, id DESC);

-- Memories that leave people out (private: only the owner reads them).
CREATE TABLE memory_hidden_people (
    user_id        uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    hidden_user_id uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, hidden_user_id)
);
CREATE TABLE memory_hidden_posts (
    user_id    uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    post_id    uuid        NOT NULL,                               -- a feed post or frame post id (one id space)
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, post_id)
);

-- +goose Down
DROP TABLE memory_hidden_posts, memory_hidden_people, ownership_log;
ALTER TABLE items DROP COLUMN handed_over_at, DROP COLUMN former_sender_name;
DROP TABLE keep_request_items, keep_requests;
DROP INDEX feed_posts_taking_idx;
ALTER TABLE feed_post_items DROP COLUMN taking_id;
ALTER TABLE feed_posts DROP COLUMN taking_id;
DROP INDEX placements_taken_away_idx;
ALTER TABLE placements DROP COLUMN taking_id, DROP COLUMN taken_away_by, DROP COLUMN taken_away_until,
    DROP COLUMN taken_away_at;
DROP TABLE taking_items, takings;
