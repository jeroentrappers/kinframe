-- +goose Up

-- Telegram bridge (docs/spec/telegram.md §16): the bot's state, the intake queue, codes, chat bindings, linked
-- accounts, contributors, contributions and open batches.

-- The bot's own state (one row).
CREATE TABLE telegram_bot (
    id                       boolean PRIMARY KEY DEFAULT true CHECK (id),
    bot_id                   bigint      NOT NULL,
    username                 text        NOT NULL,
    can_read_all_group_msgs  boolean     NOT NULL,
    privacy_off_since        timestamptz,                -- first seen with privacy mode off (§6)
    mode                     text        NOT NULL CHECK (mode IN ('webhook', 'polling')),
    poll_offset              bigint      NOT NULL DEFAULT 0,
    webhook_hash             text,                       -- sha256 of the registered url, secret and updates
    api_base                 text,                       -- the Bot API last used (a local server needs one logOut, §9.3)
    commands_hash            text,                       -- the command menus last registered
    updated_at               timestamptz NOT NULL DEFAULT now()
);

-- Intake and dedupe (§8.1). Payload cleared once processed; rows deleted after 48 h.
CREATE TABLE telegram_updates (
    update_id        bigint      PRIMARY KEY,
    chat_id          bigint,
    payload          jsonb,                                  -- reduced update; null after processing
    state            text        NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'done', 'failed')),
    attempts         integer     NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    received_at      timestamptz NOT NULL DEFAULT now(),
    processed_at     timestamptz
);
CREATE INDEX telegram_updates_pending_idx ON telegram_updates (chat_id, update_id) WHERE state = 'pending';
CREATE INDEX telegram_updates_received_idx ON telegram_updates (received_at);

CREATE TABLE telegram_codes (
    id              uuid PRIMARY KEY,
    code_hash       text        NOT NULL UNIQUE,         -- sha256
    purpose         text        NOT NULL CHECK (purpose IN ('connect', 'link', 'join')),
    chat_kind       text CHECK (chat_kind IN ('group', 'private')),
    link_account    boolean     NOT NULL DEFAULT false,
    created_by      uuid REFERENCES users ON DELETE CASCADE,          -- null for join codes
    frame_id        uuid REFERENCES frames ON DELETE CASCADE,
    feed_id         uuid REFERENCES feeds  ON DELETE CASCADE,
    for_tg_user_id  bigint,                                           -- join codes: the intended Telegram user
    for_chat_id     uuid,                                             -- join codes: the binding (no FK: survives it)
    expires_at      timestamptz NOT NULL,
    used_at         timestamptz,
    revoked_at      timestamptz,
    result_chat_id  uuid,                                             -- the binding it created
    error_code      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (purpose <> 'connect' OR ((frame_id IS NULL) <> (feed_id IS NULL)))
);
CREATE INDEX telegram_codes_creator_idx ON telegram_codes (created_by) WHERE used_at IS NULL AND revoked_at IS NULL;

-- A binding (TelegramChat).
CREATE TABLE telegram_chats (
    id                 uuid PRIMARY KEY,
    tg_chat_id         bigint      NOT NULL,
    previous_chat_id   bigint,                            -- before a supergroup migration
    bot_id             bigint      NOT NULL,
    chat_kind          text        NOT NULL CHECK (chat_kind IN ('group', 'private')),
    is_forum           boolean     NOT NULL DEFAULT false,
    title              text        NOT NULL,              -- group title / private: the user's display name
    private_tg_user_id bigint,                            -- private chats: the one contributor
    owner_user_id      uuid REFERENCES users ON DELETE SET NULL,
    frame_id           uuid REFERENCES frames ON DELETE SET NULL,  -- set null → target_deleted handled in code
    feed_id            uuid REFERENCES feeds  ON DELETE SET NULL,
    contributors       text        NOT NULL DEFAULT 'everyone' CHECK (contributors IN ('everyone', 'linked_only')),
    frame_replies      boolean     NOT NULL DEFAULT false,
    notify_reactions   boolean     NOT NULL DEFAULT false,
    topic_id           bigint,                            -- forum: message_thread_id, null = all
    topics             jsonb       NOT NULL DEFAULT '{}', -- thread id -> name, as seen
    status             text        NOT NULL DEFAULT 'active'
                       CHECK (status IN ('active', 'needs_attention', 'paused', 'disconnected')),
    error_code         text,
    warnings           text[]      NOT NULL DEFAULT '{}',
    ended_reason       text,
    ended_by_user_id   uuid REFERENCES users ON DELETE SET NULL,
    bot_status         text        NOT NULL DEFAULT 'member' CHECK (bot_status IN ('member', 'administrator')),
    bot_joined_at      timestamptz NOT NULL DEFAULT now(),
    attention_pushed   jsonb       NOT NULL DEFAULT '{}',
    welcome_sent_at    timestamptz,
    ready_sent_at      timestamptz,                       -- "Ready ✓" once privacy mode stopped mattering (§6)
    spoiler_notice_at  timestamptz,                       -- the first spoiler reply (§8.5)
    last_promo_at      timestamptz,
    last_delivery_at   timestamptz,
    counts             jsonb       NOT NULL DEFAULT '{}', -- sent, duplicates, skipped, failed, contributors
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    ended_at           timestamptz,
    farewell_due       boolean     NOT NULL DEFAULT false, -- target deleted: the bot still says goodbye and leaves
    CHECK (status = 'disconnected' OR (frame_id IS NULL) <> (feed_id IS NULL))
);
CREATE UNIQUE INDEX telegram_chats_live_idx ON telegram_chats (tg_chat_id) WHERE status <> 'disconnected';
CREATE INDEX telegram_chats_previous_idx ON telegram_chats (previous_chat_id) WHERE previous_chat_id IS NOT NULL;
CREATE INDEX telegram_chats_frame_idx ON telegram_chats (frame_id) WHERE frame_id IS NOT NULL;
CREATE INDEX telegram_chats_feed_idx ON telegram_chats (feed_id) WHERE feed_id IS NOT NULL;
CREATE INDEX telegram_chats_owner_idx ON telegram_chats (owner_user_id);

-- Groups the bot was added to without a code: it explains once and leaves after 10 minutes (§8.2).
CREATE TABLE telegram_strays (
    tg_chat_id  bigint PRIMARY KEY,
    notice_at   timestamptz,                              -- when to explain (a code usually follows at once)
    leave_at    timestamptz NOT NULL
);

CREATE TABLE telegram_links (
    tg_user_id     bigint      PRIMARY KEY,
    user_id        uuid        NOT NULL UNIQUE REFERENCES users ON DELETE CASCADE,
    display_name   text        NOT NULL,
    username       text,
    language_code  text,
    can_message    boolean     NOT NULL DEFAULT true,
    linked_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE telegram_contributors (
    id             uuid PRIMARY KEY,                      -- opaque id for the API
    chat_id        uuid        NOT NULL REFERENCES telegram_chats ON DELETE CASCADE,
    tg_user_id     bigint      NOT NULL,                  -- or the sender_chat id for anonymous/channel senders
    display_name   text        NOT NULL,
    language_code  text,
    blocked        boolean     NOT NULL DEFAULT false,
    promo_sent_at  timestamptz,
    sent_count     integer     NOT NULL DEFAULT 0,
    first_seen_at  timestamptz NOT NULL DEFAULT now(),
    last_seen_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (chat_id, tg_user_id)
);

-- One row per accepted or refused media message (§5.4); deleted after 90 days.
CREATE TABLE telegram_contributions (
    chat_id          uuid        NOT NULL REFERENCES telegram_chats ON DELETE CASCADE,
    message_id       bigint      NOT NULL,
    tg_chat_id       bigint      NOT NULL,                -- at the time (migrations change it)
    thread_id        bigint,
    media_group_id   text,
    contributor_id   uuid REFERENCES telegram_contributors ON DELETE SET NULL,
    sender_user_id   uuid REFERENCES users ON DELETE SET NULL,  -- the kinframe sender (linked user or owner)
    file_unique_id   text,
    state            text        NOT NULL CHECK (state IN ('collecting', 'sent', 'duplicate', 'skipped', 'failed')),
    skip_reason      text,
    failure_code     text,
    attempts         integer     NOT NULL DEFAULT 0,
    retry_at         timestamptz,                         -- a failed download is tried again then
    media            jsonb,                               -- what to download (while collecting without an item)
    caption          text,                                -- the message's own caption
    message_date     timestamptz,
    batch_id         uuid,
    item_id          uuid REFERENCES items ON DELETE SET NULL,
    send_id          uuid REFERENCES sends ON DELETE SET NULL,
    caption_source   boolean     NOT NULL DEFAULT false,  -- this message's caption set the album's captions
    reaction_shown   text,                                -- emoji currently set by the bot
    replied_frames   uuid[]      NOT NULL DEFAULT '{}',   -- frames whose quick reply was posted (§10.2)
    closed           boolean     NOT NULL DEFAULT false,  -- no more reactions (supergroup migration)
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, message_id)
);
CREATE INDEX telegram_contributions_item_idx ON telegram_contributions (item_id) WHERE item_id IS NOT NULL;
CREATE INDEX telegram_contributions_file_idx ON telegram_contributions (file_unique_id);
CREATE INDEX telegram_contributions_batch_idx ON telegram_contributions (batch_id) WHERE batch_id IS NOT NULL;
CREATE INDEX telegram_contributions_retry_idx ON telegram_contributions (retry_at) WHERE retry_at IS NOT NULL;
CREATE INDEX telegram_contributions_created_idx ON telegram_contributions (created_at);

-- Open batches (§8.4).
CREATE TABLE telegram_batches (
    id              uuid PRIMARY KEY,
    chat_id         uuid        NOT NULL REFERENCES telegram_chats ON DELETE CASCADE,
    contributor_id  uuid        NOT NULL REFERENCES telegram_contributors ON DELETE CASCADE,
    sender_user_id  uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    outside         boolean     NOT NULL,                 -- sent by the owner on the contributor's behalf, always moderated
    label           text        NOT NULL,                 -- the credit (ItemVia.label)
    opened_at       timestamptz NOT NULL DEFAULT now(),
    last_at         timestamptz NOT NULL DEFAULT now(),
    album_at        timestamptz,                          -- the latest album message it holds
    closes_at       timestamptz NOT NULL,
    items           integer     NOT NULL DEFAULT 0
);
CREATE INDEX telegram_batches_due_idx ON telegram_batches (closes_at);

-- Credit: telegram joins the photo services and email. sends.via_label / items.via_label (the person's name)
-- come from 00020_email_inbox.sql and are reused.
ALTER TABLE sends DROP CONSTRAINT sends_via_check;
ALTER TABLE sends ADD CONSTRAINT sends_via_check CHECK (via IN ('immich', 'icloud', 'google_photos', 'email', 'telegram')),
    ADD COLUMN telegram_chat_id uuid REFERENCES telegram_chats ON DELETE SET NULL,
    ADD COLUMN telegram_outside boolean NOT NULL DEFAULT false; -- sent by the chat's owner: no delivered push
ALTER TABLE items DROP CONSTRAINT items_via_check;
ALTER TABLE items ADD CONSTRAINT items_via_check CHECK (via IN ('immich', 'icloud', 'google_photos', 'email', 'telegram')),
    ADD COLUMN via_telegram_chat_id uuid REFERENCES telegram_chats ON DELETE SET NULL;

-- Join links from the app promotion (§10.3): the email inbox's generic channel columns (00020) gain 'telegram'.
-- invites.via_ref / feed_invites.via_ref = the telegram_contributors id; join requests: via_name
-- ("Ben · Telegram, Peeters family"), via_ref (the telegram_contributors id), via_matches null.
ALTER TABLE invites DROP CONSTRAINT invites_via_check;
ALTER TABLE invites ADD CONSTRAINT invites_via_check CHECK (via IN ('email', 'telegram'));
ALTER TABLE feed_invites DROP CONSTRAINT feed_invites_via_check;
ALTER TABLE feed_invites ADD CONSTRAINT feed_invites_via_check CHECK (via IN ('email', 'telegram'));
ALTER TABLE join_requests DROP CONSTRAINT join_requests_via_check;
ALTER TABLE join_requests ADD CONSTRAINT join_requests_via_check CHECK (via IN ('frame_code', 'invite', 'email', 'telegram'));
ALTER TABLE feed_join_requests DROP CONSTRAINT feed_join_requests_via_check;
ALTER TABLE feed_join_requests ADD CONSTRAINT feed_join_requests_via_check CHECK (via IN ('invite', 'email', 'telegram'));
ALTER TABLE telegram_chats ADD COLUMN offer_app_invite boolean NOT NULL DEFAULT true;

-- Frames and feeds are soft-deleted: their chats end with target_deleted; the bot says goodbye later (§12).
-- +goose StatementBegin
CREATE FUNCTION telegram_target_deleted() RETURNS trigger AS $$
BEGIN
    IF NEW.deleted_at IS NOT NULL AND OLD.deleted_at IS NULL THEN
        IF TG_TABLE_NAME = 'frames' THEN
            UPDATE telegram_chats SET status = 'disconnected', ended_reason = 'target_deleted', ended_at = now(),
                updated_at = now(), farewell_due = true
            WHERE frame_id = NEW.id AND status <> 'disconnected';
        ELSE
            UPDATE telegram_chats SET status = 'disconnected', ended_reason = 'target_deleted', ended_at = now(),
                updated_at = now(), farewell_due = true
            WHERE feed_id = NEW.id AND status <> 'disconnected';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER frames_telegram_deleted AFTER UPDATE OF deleted_at ON frames
    FOR EACH ROW EXECUTE FUNCTION telegram_target_deleted();
CREATE TRIGGER feeds_telegram_deleted AFTER UPDATE OF deleted_at ON feeds
    FOR EACH ROW EXECUTE FUNCTION telegram_target_deleted();

-- +goose Down
DROP TRIGGER feeds_telegram_deleted ON feeds;
DROP TRIGGER frames_telegram_deleted ON frames;
DROP FUNCTION telegram_target_deleted();
DELETE FROM feed_join_requests WHERE via = 'telegram';
ALTER TABLE feed_join_requests DROP CONSTRAINT feed_join_requests_via_check;
ALTER TABLE feed_join_requests ADD CONSTRAINT feed_join_requests_via_check CHECK (via IN ('invite', 'email'));
DELETE FROM join_requests WHERE via = 'telegram';
ALTER TABLE join_requests DROP CONSTRAINT join_requests_via_check;
ALTER TABLE join_requests ADD CONSTRAINT join_requests_via_check CHECK (via IN ('frame_code', 'invite', 'email'));
UPDATE feed_invites SET via = NULL WHERE via = 'telegram';
ALTER TABLE feed_invites DROP CONSTRAINT feed_invites_via_check;
ALTER TABLE feed_invites ADD CONSTRAINT feed_invites_via_check CHECK (via IN ('email'));
UPDATE invites SET via = NULL WHERE via = 'telegram';
ALTER TABLE invites DROP CONSTRAINT invites_via_check;
ALTER TABLE invites ADD CONSTRAINT invites_via_check CHECK (via IN ('email'));
UPDATE items SET via = NULL WHERE via = 'telegram';
ALTER TABLE items DROP CONSTRAINT items_via_check;
ALTER TABLE items ADD CONSTRAINT items_via_check CHECK (via IN ('immich', 'icloud', 'google_photos', 'email'));
ALTER TABLE items DROP COLUMN via_telegram_chat_id;
UPDATE sends SET via = NULL WHERE via = 'telegram';
ALTER TABLE sends DROP CONSTRAINT sends_via_check;
ALTER TABLE sends ADD CONSTRAINT sends_via_check CHECK (via IN ('immich', 'icloud', 'google_photos', 'email'));
ALTER TABLE sends DROP COLUMN telegram_chat_id, DROP COLUMN telegram_outside;
DROP TABLE telegram_batches, telegram_contributions, telegram_contributors, telegram_links, telegram_strays, telegram_chats,
    telegram_codes, telegram_updates, telegram_bot;
