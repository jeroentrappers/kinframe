-- +goose Up

-- Family social, part 2 (docs/spec/family-social.md, README §5.19): conversations and their members, messages
-- with items, mentions and "put on a frame" links, voice messages, link previews, blocks and reports.

-- ---------------------------------------------------------------- conversations

CREATE TABLE conversations (
    id              uuid PRIMARY KEY,
    kind            text        NOT NULL CHECK (kind IN ('direct', 'group', 'family', 'circle')),
    feed_id         uuid REFERENCES feeds ON DELETE CASCADE,      -- family, circle (the circle's feed)
    circle_id       uuid REFERENCES circles ON DELETE CASCADE,    -- circle
    direct_key      text UNIQUE,                                  -- direct: '<lower user id>:<higher user id>'
    name            text CHECK (name IS NULL OR char_length(name) BETWEEN 1 AND 60),   -- group only
    photo_key       text,                                         -- group photo (avatar-style blob key)
    created_by      uuid REFERENCES users ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    last_message_id uuid,
    last_message_at timestamptz,
    archived_at     timestamptz,                                  -- family/circle deleted: read-only, hidden
    CHECK (kind <> 'family' OR (feed_id IS NOT NULL AND circle_id IS NULL)),
    CHECK (kind <> 'circle' OR (feed_id IS NOT NULL AND circle_id IS NOT NULL)),
    CHECK (kind NOT IN ('direct', 'group') OR (feed_id IS NULL AND circle_id IS NULL)),
    CHECK ((kind = 'direct') = (direct_key IS NOT NULL)),
    CHECK (kind = 'group' OR name IS NULL)
);
CREATE UNIQUE INDEX conversations_one_family ON conversations (feed_id) WHERE kind = 'family';
CREATE UNIQUE INDEX conversations_one_circle ON conversations (circle_id) WHERE kind = 'circle';

-- Family and circle members are maintained by the triggers below (never by Go code): always the feed's
-- members and the circle's members. Direct and group members are written by code. Leaving deletes the row.
CREATE TABLE conversation_members (
    conversation_id      uuid        NOT NULL REFERENCES conversations ON DELETE CASCADE,
    user_id              uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    role                 text        NOT NULL DEFAULT 'member' CHECK (role IN ('admin', 'member')),
    added_by             uuid REFERENCES users ON DELETE SET NULL,
    joined_at            timestamptz NOT NULL DEFAULT now(),
    visible_from         timestamptz,              -- groups: messages from here on (joined_at); null = all history
    last_read_message_id uuid,
    last_read_at         timestamptz,
    muted_until          timestamptz,              -- 'infinity' = always; null or past = not muted
    archived             boolean     NOT NULL DEFAULT false,   -- the member's own archive; a new message clears it
    PRIMARY KEY (conversation_id, user_id)
);
CREATE INDEX conversation_members_user_idx ON conversation_members (user_id);

-- Read receipts: users choose whether they show and see "Seen" (README §5.19).
ALTER TABLE users ADD COLUMN show_read_receipts boolean NOT NULL DEFAULT true;

-- ---------------------------------------------------------------- link previews

-- Fetched by the server (netguard: public addresses only) when the operator turned link previews on. One row
-- per normalized URL, shared by every message that links it; refreshed after expires_at.
CREATE TABLE link_previews (
    id           uuid PRIMARY KEY,
    url          text        NOT NULL,                -- normalized (scheme and host lower case, no fragment)
    url_hash     text        NOT NULL UNIQUE,         -- hex sha256 of url
    status       text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'ok', 'failed')),
    error_code   text,                                -- failed: blocked_address, timeout, too_large, not_html, http_<status>
    title        text,
    description  text,
    site_name    text,
    image_key    text,                                -- WebP, at most 512 px, served by the server
    image_width  integer,
    image_height integer,
    fetched_at   timestamptz,
    expires_at   timestamptz,                         -- ok: 7 days, failed: 1 day
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX link_previews_expiry_idx ON link_previews (expires_at);

-- ---------------------------------------------------------------- messages

-- Bodies as for comments: `body`, or `body_enc` with KINFRAME_SECRETS_KEY (secrets.Sealer, info
-- "kinframe message bodies v1"). A deleted message keeps its row as a tombstone; deleting clears body,
-- body_enc, mentions, link preview, voice, its items and reactions.
CREATE TABLE messages (
    id              uuid PRIMARY KEY,                 -- UUIDv7: conversation order
    conversation_id uuid        NOT NULL REFERENCES conversations ON DELETE CASCADE,
    kind            text        NOT NULL DEFAULT 'person' CHECK (kind IN ('person', 'system')),
    author_user_id  uuid REFERENCES users ON DELETE SET NULL,     -- person messages
    client_id       text CHECK (client_id IS NULL OR char_length(client_id) <= 64),
    body            text,
    body_enc        bytea,
    mentions        jsonb       NOT NULL DEFAULT '[]',
    reply_to_id     uuid REFERENCES messages ON DELETE SET NULL,
    shared_post_id  uuid,                             -- feed_posts.id or frame_posts.id (no FK: either table)
    voice_item_id   uuid,                             -- a voice message: an item whose media has kind 'audio' (FK below)
    link_preview_id uuid REFERENCES link_previews ON DELETE SET NULL,
    system_type     text CHECK (system_type IN ('put_on_frame', 'streak_milestone', 'monthly_recap', 'prompt',
                                                'member_joined', 'member_left')),
    system_data     jsonb,                            -- the SystemMessage fields besides `type`
    created_at      timestamptz NOT NULL DEFAULT now(),
    edited_at       timestamptz,
    deleted_at      timestamptz,
    removed_by      uuid REFERENCES users ON DELETE SET NULL,     -- set when a moderator removed it
    CHECK (body IS NULL OR body_enc IS NULL),
    CHECK ((kind = 'system') = (system_type IS NOT NULL)),
    CHECK (kind = 'person' OR (author_user_id IS NULL AND body IS NULL AND body_enc IS NULL))
);
CREATE INDEX messages_conversation_idx ON messages (conversation_id, id);
CREATE INDEX messages_author_idx ON messages (author_user_id, id DESC) WHERE author_user_id IS NOT NULL;
CREATE UNIQUE INDEX messages_client_id_unique ON messages (conversation_id, author_user_id, client_id)
    WHERE client_id IS NOT NULL;
CREATE INDEX messages_shared_post_idx ON messages (shared_post_id) WHERE shared_post_id IS NOT NULL;

ALTER TABLE conversations ADD CONSTRAINT conversations_last_message_fk
    FOREIGN KEY (last_message_id) REFERENCES messages ON DELETE SET NULL;
CREATE INDEX conversations_last_message_idx ON conversations (last_message_at DESC);

ALTER TABLE messages ADD CONSTRAINT messages_voice_item_fk FOREIGN KEY (voice_item_id) REFERENCES items ON DELETE SET NULL;

-- Photos and videos of a message: ordinary items (one master, renditions on demand). Never the voice item.
CREATE TABLE message_items (
    message_id uuid    NOT NULL REFERENCES messages ON DELETE CASCADE,
    item_id    uuid    NOT NULL REFERENCES items ON DELETE CASCADE,
    position   integer NOT NULL,
    PRIMARY KEY (message_id, item_id)
);
CREATE INDEX message_items_item_idx ON message_items (item_id);

-- An item uploaded in a chat: who may see its media besides its sender (conversation members), and kept out
-- of sent history and libraries until it is put on a frame.
ALTER TABLE items ADD COLUMN conversation_id uuid REFERENCES conversations ON DELETE SET NULL;
CREATE INDEX items_conversation_idx ON items (conversation_id) WHERE conversation_id IS NOT NULL;

CREATE TABLE message_mentions (
    message_id uuid NOT NULL REFERENCES messages ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users ON DELETE CASCADE,
    PRIMARY KEY (message_id, user_id)
);
CREATE INDEX message_mentions_user_idx ON message_mentions (user_id);

-- "Put on a frame": the sends made from a message, so frame reactions and replies show under it.
CREATE TABLE message_sends (
    message_id uuid        NOT NULL REFERENCES messages ON DELETE CASCADE,
    send_id    uuid        NOT NULL REFERENCES sends ON DELETE CASCADE,
    created_by uuid REFERENCES users ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, send_id)
);
CREATE INDEX message_sends_send_idx ON message_sends (send_id);

-- Voice messages: media of kind 'audio' (AAC-LC in MP4, README §5.19) with a 64-value waveform; the API never
-- shows them as items (MediaKind has no audio), only as Message.voice.
ALTER TABLE media DROP CONSTRAINT media_kind_check;
ALTER TABLE media ADD CONSTRAINT media_kind_check CHECK (kind IN ('photo', 'video', 'audio'));
ALTER TABLE media ADD COLUMN waveform smallint[] CHECK (waveform IS NULL OR cardinality(waveform) = 64);

-- ---------------------------------------------------------------- blocks and reports

CREATE TABLE blocks (
    user_id         uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    blocked_user_id uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, blocked_user_id),
    CHECK (user_id <> blocked_user_id)
);
CREATE INDEX blocks_blocked_idx ON blocks (blocked_user_id);

-- feed_id set: the feed's owner/admins handle it (feed posts, their comments, family and circle chats);
-- null: the operator. The excerpt is a copy at report time (sealed like bodies when encryption is on).
CREATE TABLE reports (
    id               uuid PRIMARY KEY,
    reporter_user_id uuid REFERENCES users ON DELETE SET NULL,
    target_kind      text        NOT NULL CHECK (target_kind IN ('comment', 'message', 'post')),
    target_id        uuid        NOT NULL,
    target_user_id   uuid REFERENCES users ON DELETE SET NULL,      -- the content's author
    post_id          uuid,                                          -- comments and posts
    conversation_id  uuid REFERENCES conversations ON DELETE SET NULL,
    feed_id          uuid REFERENCES feeds ON DELETE CASCADE,
    reason           text        NOT NULL CHECK (reason IN ('unkind', 'spam', 'inappropriate', 'other')),
    note             text CHECK (note IS NULL OR char_length(note) <= 500),
    excerpt          text,
    excerpt_enc      bytea,
    status           text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'removed', 'dismissed')),
    resolved_by      uuid REFERENCES users ON DELETE SET NULL,
    resolved_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (excerpt IS NULL OR excerpt_enc IS NULL)
);
CREATE UNIQUE INDEX reports_one_open ON reports (reporter_user_id, target_kind, target_id) WHERE status = 'open';
CREATE INDEX reports_feed_idx ON reports (feed_id, status, created_at DESC) WHERE feed_id IS NOT NULL;
CREATE INDEX reports_operator_idx ON reports (status, created_at DESC) WHERE feed_id IS NULL;

-- ---------------------------------------------------------------- family and circle chats

-- Every feed has exactly one family conversation and every circle one circle conversation, created with it;
-- their members mirror feed_members and circle_members. Soft-deleting the feed or circle archives them.
-- These triggers are the only writers of family/circle conversations and their members.
-- +goose StatementBegin
CREATE FUNCTION chat_feed_conversation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO conversations (id, kind, feed_id, created_at, updated_at)
        VALUES (kinframe_uuidv7(clock_timestamp()), 'family', NEW.id, now(), now())
        ON CONFLICT DO NOTHING;
    ELSIF NEW.deleted_at IS NOT NULL AND OLD.deleted_at IS NULL THEN
        UPDATE conversations SET archived_at = now(), updated_at = now()
        WHERE feed_id = NEW.id AND archived_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER feeds_chat_conversation AFTER INSERT OR UPDATE OF deleted_at ON feeds
    FOR EACH ROW EXECUTE FUNCTION chat_feed_conversation();

-- +goose StatementBegin
CREATE FUNCTION chat_circle_conversation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO conversations (id, kind, feed_id, circle_id, created_by, created_at, updated_at)
        VALUES (kinframe_uuidv7(clock_timestamp()), 'circle', NEW.feed_id, NEW.id, NEW.created_by, now(), now())
        ON CONFLICT DO NOTHING;
    ELSIF NEW.deleted_at IS NOT NULL AND OLD.deleted_at IS NULL THEN
        UPDATE conversations SET archived_at = now(), updated_at = now()
        WHERE circle_id = NEW.id AND archived_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER circles_chat_conversation AFTER INSERT OR UPDATE OF deleted_at ON circles
    FOR EACH ROW EXECUTE FUNCTION chat_circle_conversation();

-- +goose StatementBegin
CREATE FUNCTION chat_feed_member_sync() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO conversation_members (conversation_id, user_id, joined_at)
        SELECT c.id, NEW.user_id, now() FROM conversations c WHERE c.kind = 'family' AND c.feed_id = NEW.feed_id
        ON CONFLICT DO NOTHING;
        RETURN NEW;
    END IF;
    DELETE FROM conversation_members cm USING conversations c
    WHERE cm.conversation_id = c.id AND c.kind = 'family' AND c.feed_id = OLD.feed_id AND cm.user_id = OLD.user_id;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER feed_members_chat_sync AFTER INSERT OR DELETE ON feed_members
    FOR EACH ROW EXECUTE FUNCTION chat_feed_member_sync();

-- +goose StatementBegin
CREATE FUNCTION chat_circle_member_sync() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO conversation_members (conversation_id, user_id, added_by, joined_at)
        SELECT c.id, NEW.user_id, NEW.added_by, now() FROM conversations c
        WHERE c.kind = 'circle' AND c.circle_id = NEW.circle_id
        ON CONFLICT DO NOTHING;
        RETURN NEW;
    END IF;
    DELETE FROM conversation_members cm USING conversations c
    WHERE cm.conversation_id = c.id AND c.kind = 'circle' AND c.circle_id = OLD.circle_id AND cm.user_id = OLD.user_id;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER circle_members_chat_sync AFTER INSERT OR DELETE ON circle_members
    FOR EACH ROW EXECUTE FUNCTION chat_circle_member_sync();

-- Backfill: one family conversation per existing feed (deleted ones archived), with the feed's members.
INSERT INTO conversations (id, kind, feed_id, created_at, updated_at, archived_at)
SELECT kinframe_uuidv7(f.created_at), 'family', f.id, f.created_at, now(), f.deleted_at
FROM feeds f;
INSERT INTO conversation_members (conversation_id, user_id, joined_at)
SELECT c.id, fm.user_id, fm.joined_at
FROM feed_members fm JOIN conversations c ON c.kind = 'family' AND c.feed_id = fm.feed_id;

-- +goose Down
DROP TRIGGER circle_members_chat_sync ON circle_members;
DROP TRIGGER feed_members_chat_sync ON feed_members;
DROP TRIGGER circles_chat_conversation ON circles;
DROP TRIGGER feeds_chat_conversation ON feeds;
DROP FUNCTION chat_circle_member_sync(), chat_feed_member_sync(), chat_circle_conversation(), chat_feed_conversation();
DROP TABLE reports, blocks;
ALTER TABLE media DROP COLUMN waveform;
DELETE FROM items WHERE media_id IN (SELECT id FROM media WHERE kind = 'audio');
DELETE FROM media WHERE kind = 'audio';
ALTER TABLE media DROP CONSTRAINT media_kind_check;
ALTER TABLE media ADD CONSTRAINT media_kind_check CHECK (kind IN ('photo', 'video'));
DROP TABLE message_sends, message_mentions;
DROP INDEX items_conversation_idx;
ALTER TABLE items DROP COLUMN conversation_id;
DROP TABLE message_items;
ALTER TABLE conversations DROP CONSTRAINT conversations_last_message_fk;
DROP TABLE messages, link_previews;
ALTER TABLE users DROP COLUMN show_read_receipts;
DROP TABLE conversation_members, conversations;
