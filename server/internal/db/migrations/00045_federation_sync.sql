-- +goose Up

-- Families on different servers: bookkeeping of the sync between servers (server-federation,
-- docs/spec/leaving-servers-work.md). A = the family's server, B = the frame's server.

-- A: an ended subscription is told to B with a signed DELETE, retried until B answered.
ALTER TABLE remote_subscribers ADD COLUMN notify_end boolean NOT NULL DEFAULT false;

-- B: a `reset` restarts from a full snapshot; copies not seen again since resync_since are dropped once B caught up.
ALTER TABLE remote_follows ADD COLUMN resync_since timestamptz;
ALTER TABLE remote_items ADD COLUMN seen_at timestamptz NOT NULL DEFAULT now();

-- B: the outbox retries with back-off.
ALTER TABLE remote_activity_outbox ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();

-- B: the newest comments of a remote post (`comments` changes; only with *show comments*), replaced as a whole.
CREATE TABLE remote_post_comments (
    follow_id      uuid        NOT NULL REFERENCES remote_follows ON DELETE CASCADE,
    remote_post_id uuid        NOT NULL,
    comments       jsonb       NOT NULL DEFAULT '[]',               -- [FederationComment]
    updated_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (follow_id, remote_post_id)
);

-- A: the change log is kept 30 days.
CREATE INDEX federation_changes_created_idx ON federation_changes (created_at);

-- +goose Down
DROP INDEX federation_changes_created_idx;
DROP TABLE remote_post_comments;
ALTER TABLE remote_activity_outbox DROP COLUMN next_attempt_at;
ALTER TABLE remote_items DROP COLUMN seen_at;
ALTER TABLE remote_follows DROP COLUMN resync_since;
ALTER TABLE remote_subscribers DROP COLUMN notify_end;
