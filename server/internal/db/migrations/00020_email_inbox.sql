-- +goose Up

-- Email to frame (docs/spec/email-inbox.md §16): an address per frame or feed, its allowed/pending/blocked senders,
-- the inbound queue, held mail, a 30-day message log and the reply recipients (app intro, server-wide).
CREATE TABLE email_addresses (
    id                  uuid PRIMARY KEY,
    local_part          text        NOT NULL UNIQUE,              -- '<slug>-<random>', lower case
    slug                text        NOT NULL CHECK (slug ~ '^[a-z0-9]([a-z0-9-]{0,22}[a-z0-9])?$'),
    random              text        NOT NULL CHECK (random ~ '^[23456789abcdefghjkmnpqrstuvwxyz]{6,16}$'),
    name                text        NOT NULL,                     -- label: "Oma's frame"
    frame_id            uuid REFERENCES frames ON DELETE CASCADE,
    feed_id             uuid REFERENCES feeds  ON DELETE CASCADE,
    owner_user_id       uuid REFERENCES users ON DELETE SET NULL, -- null only between owner loss and fallback
    created_by_user_id  uuid REFERENCES users ON DELETE SET NULL,
    status              text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'paused')),
    anyone_may_send     boolean     NOT NULL DEFAULT false,
    send_confirmations  boolean     NOT NULL DEFAULT true,
    offer_app_invite    boolean     NOT NULL DEFAULT true,
    notify_reactions    boolean     NOT NULL DEFAULT true,
    last_received_at    timestamptz,
    regenerated_at      timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CHECK ((frame_id IS NOT NULL) <> (feed_id IS NOT NULL))
);
CREATE INDEX email_addresses_frame_idx ON email_addresses (frame_id) WHERE frame_id IS NOT NULL;
CREATE INDEX email_addresses_feed_idx ON email_addresses (feed_id) WHERE feed_id IS NOT NULL;

CREATE TABLE email_retired_local_parts (
    local_part  text PRIMARY KEY,
    retired_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE email_senders (
    id                 uuid PRIMARY KEY,
    address_id         uuid        NOT NULL REFERENCES email_addresses ON DELETE CASCADE,
    email              text        NOT NULL,                      -- lower case
    display_name       text,
    state              text        NOT NULL CHECK (state IN ('allowed', 'blocked', 'pending')),
    origin             text        NOT NULL CHECK (origin IN ('manual', 'approved', 'mail')),
    added_by_user_id   uuid REFERENCES users ON DELETE SET NULL,
    invite_id          uuid,                                      -- live email join link (frame or feed invite)
    pending_notified_at timestamptz,
    expiring_notified  boolean     NOT NULL DEFAULT false,
    last_message_at    timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (address_id, email)
);

CREATE TABLE email_inbound (                                      -- the queue; rows live minutes
    id              uuid PRIMARY KEY,
    spool_path      text        NOT NULL,
    path            text        NOT NULL CHECK (path IN ('smtp', 'webhook')),
    envelope_from   text,
    envelope_to     text[]      NOT NULL,
    client_ip       inet,
    helo            text,
    size_bytes      bigint      NOT NULL,
    auth            jsonb,                                        -- §7 results (null: webhook, checked by the worker)
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    received_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE email_held_messages (
    id              uuid PRIMARY KEY,
    address_id      uuid        NOT NULL REFERENCES email_addresses ON DELETE CASCADE,
    sender_id       uuid        NOT NULL REFERENCES email_senders ON DELETE CASCADE,
    reason          text        NOT NULL CHECK (reason IN ('unknown_sender', 'unverified_sender')),
    message_key     text        NOT NULL,
    sender_email    text        NOT NULL,                         -- as claimed
    sender_name     text,
    subject         text,
    caption         text,
    auth            jsonb       NOT NULL,
    media           jsonb       NOT NULL,                         -- [{media_id, kind, width, height, duration_ms, bytes, filename, captured_hint}]
    skipped         jsonb       NOT NULL DEFAULT '{}',
    received_at     timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    UNIQUE (address_id, message_key)
);
CREATE INDEX email_held_expiry_idx ON email_held_messages (expires_at);

CREATE TABLE email_messages (                                     -- 30-day log: dedupe, rate limits, Recent mail
    id              uuid PRIMARY KEY,
    address_id      uuid        NOT NULL REFERENCES email_addresses ON DELETE CASCADE,
    message_key     text        NOT NULL,
    sender_email    text,
    sender_name     text,
    authenticated   boolean     NOT NULL,
    outcome         text        NOT NULL CHECK (outcome IN ('delivered', 'held', 'no_media', 'too_large', 'encrypted',
                                    'duplicate', 'blocked', 'rejected_dmarc', 'ignored', 'rate_limited', 'failed')),
    items           integer     NOT NULL DEFAULT 0,
    skipped         jsonb       NOT NULL DEFAULT '{}',
    send_ids        uuid[]      NOT NULL DEFAULT '{}',
    reply_kind      text CHECK (reply_kind IN ('delivered', 'held', 'no_media', 'too_large', 'encrypted')),
    replied_at      timestamptz,
    received_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX email_messages_address_idx ON email_messages (address_id, received_at DESC);
CREATE INDEX email_messages_key_idx ON email_messages (address_id, message_key);
CREATE INDEX email_messages_sender_idx ON email_messages (address_id, sender_email, received_at DESC);

CREATE TABLE email_reply_recipients (                             -- per sender address, server-wide (§10.4)
    email             text PRIMARY KEY,
    app_intro_sent_at timestamptz,
    last_reply_at     timestamptz NOT NULL
);

-- Credit: email joins the photo services.
ALTER TABLE sends DROP CONSTRAINT sends_via_check;
ALTER TABLE sends ADD CONSTRAINT sends_via_check CHECK (via IN ('immich', 'icloud', 'google_photos', 'email')),
    ADD COLUMN email_address_id uuid REFERENCES email_addresses ON DELETE SET NULL,
    ADD COLUMN via_label text;
ALTER TABLE items DROP CONSTRAINT items_via_check;
ALTER TABLE items ADD CONSTRAINT items_via_check CHECK (via IN ('immich', 'icloud', 'google_photos', 'email')),
    ADD COLUMN via_label text,                                    -- email: the sender's name
    ADD COLUMN via_email_address_id uuid REFERENCES email_addresses ON DELETE SET NULL;

-- Held media: processed masters without items (deleted with the held message).
ALTER TABLE media ADD COLUMN held_message_id uuid REFERENCES email_held_messages ON DELETE SET NULL;

-- Verified account emails (§8.2).
ALTER TABLE users ADD COLUMN email_verified boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX users_email_verified_unique ON users (lower(email))
    WHERE email_verified AND deleted_at IS NULL;                  -- a duplicate stays unverified, matched to nobody

-- Join links from replies (§10.4); generic per outside channel (email now, telegram next).
ALTER TABLE invites
    ADD COLUMN via text CHECK (via IN ('email')),                 -- set: a channel invite (not listed, always needs approval)
    ADD COLUMN via_ref text;                                      -- email: the email_senders id
ALTER TABLE feed_invites
    ADD COLUMN via text CHECK (via IN ('email')),
    ADD COLUMN via_ref text;
ALTER TABLE join_requests
    ADD COLUMN via_name text,                                     -- shown to managers: "Jan · jan@…"
    ADD COLUMN via_ref text,                                      -- email: the address the link was mailed to
    ADD COLUMN via_matches boolean;
ALTER TABLE feed_join_requests
    ADD COLUMN via_name text,
    ADD COLUMN via_ref text,
    ADD COLUMN via_matches boolean;
ALTER TABLE join_requests DROP CONSTRAINT join_requests_via_check;
ALTER TABLE join_requests ADD CONSTRAINT join_requests_via_check CHECK (via IN ('frame_code', 'invite', 'email'));
ALTER TABLE feed_join_requests ADD COLUMN via text NOT NULL DEFAULT 'invite' CHECK (via IN ('invite', 'email'));

-- +goose Down
ALTER TABLE feed_join_requests DROP COLUMN via, DROP COLUMN via_name, DROP COLUMN via_ref, DROP COLUMN via_matches;
DELETE FROM join_requests WHERE via = 'email';
ALTER TABLE join_requests DROP CONSTRAINT join_requests_via_check;
ALTER TABLE join_requests ADD CONSTRAINT join_requests_via_check CHECK (via IN ('frame_code', 'invite'));
ALTER TABLE join_requests DROP COLUMN via_name, DROP COLUMN via_ref, DROP COLUMN via_matches;
ALTER TABLE feed_invites DROP COLUMN via, DROP COLUMN via_ref;
ALTER TABLE invites DROP COLUMN via, DROP COLUMN via_ref;
DROP INDEX IF EXISTS users_email_verified_unique;
ALTER TABLE users DROP COLUMN email_verified;
ALTER TABLE media DROP COLUMN held_message_id;
UPDATE items SET via = NULL WHERE via = 'email';
ALTER TABLE items DROP CONSTRAINT items_via_check;
ALTER TABLE items ADD CONSTRAINT items_via_check CHECK (via IN ('immich', 'icloud', 'google_photos'));
ALTER TABLE items DROP COLUMN via_label, DROP COLUMN via_email_address_id;
UPDATE sends SET via = NULL WHERE via = 'email';
ALTER TABLE sends DROP CONSTRAINT sends_via_check;
ALTER TABLE sends ADD CONSTRAINT sends_via_check CHECK (via IN ('immich', 'icloud', 'google_photos'));
ALTER TABLE sends DROP COLUMN email_address_id, DROP COLUMN via_label;
DROP TABLE email_reply_recipients, email_messages, email_held_messages, email_inbound, email_senders,
    email_retired_local_parts, email_addresses;
