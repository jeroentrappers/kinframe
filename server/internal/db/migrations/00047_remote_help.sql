-- +goose Up

-- Remote help (docs/spec/remote-help.md): a temporary tailnet tunnel from a frame to the server operator, started by
-- the operator only. One active (pending or on) session per frame; ended sessions are the log, kept 2 years.
CREATE TABLE remote_help_sessions (
    id              uuid PRIMARY KEY,
    frame_id        uuid NOT NULL REFERENCES frames(id) ON DELETE CASCADE,
    device_id       uuid REFERENCES devices(id) ON DELETE SET NULL, -- the frame's device at start (unpaired: it changed)
    frame_name      text NOT NULL,
    state           text NOT NULL CHECK (state IN ('pending', 'on', 'ended')),
    reason          text NOT NULL,
    started_by      uuid REFERENCES users(id) ON DELETE SET NULL,
    started_by_name text NOT NULL,
    started_at      timestamptz NOT NULL,
    ends_at         timestamptz NOT NULL,
    on_at           timestamptz,
    ended_at        timestamptz,
    ended_by        text CHECK (ended_by IN ('operator', 'owner', 'frame', 'expired', 'unpaired', 'failed')),
    ended_by_user   uuid REFERENCES users(id) ON DELETE SET NULL,
    ended_by_name   text,
    hostname        text NOT NULL,
    tailnet_ip      text,
    -- The Headscale pre-auth key, sealed; cleared once the frame reported `on` or `failed`, or the session ended.
    auth_key_enc    bytea,
    auth_key_id     text,  -- Headscale's id of the key (to expire it)
    key_used        boolean NOT NULL DEFAULT false,
    error           text,
    CHECK ((state = 'ended') = (ended_at IS NOT NULL))
);
CREATE UNIQUE INDEX remote_help_sessions_active ON remote_help_sessions (frame_id) WHERE state <> 'ended';
CREATE INDEX remote_help_sessions_due ON remote_help_sessions (ends_at) WHERE state <> 'ended';
CREATE INDEX remote_help_sessions_log ON remote_help_sessions (started_at DESC, id DESC);

-- +goose Down
DROP TABLE remote_help_sessions;
