-- +goose Up

-- Family social, part 1 (docs/spec/family-social.md, README §5.18): the `member` role and the new feed settings,
-- frame posts (direct sends as timeline posts), threads, comments and mentions, people's reactions, thread read
-- state, circles, and push bundling. Rules for every table are in docs/spec/family-social-work.md.

-- A UUIDv7 for a given time, for backfills (Go code makes its own ids with store.NewID). Kept for later
-- migrations; same layout as github.com/google/uuid NewV7 (48-bit unix ms, version 7, variant 10).
-- +goose StatementBegin
CREATE FUNCTION kinframe_uuidv7(ts timestamptz) RETURNS uuid AS $$
    SELECT encode(
        set_bit(set_bit(
            overlay(uuid_send(gen_random_uuid())
                    PLACING substring(int8send(floor(extract(epoch FROM ts) * 1000)::bigint) FROM 3)
                    FROM 1 FOR 6),
            52, 1), 53, 1),
        'hex')::uuid;
$$ LANGUAGE sql VOLATILE;
-- +goose StatementEnd

-- ---------------------------------------------------------------- roles and feed settings

-- `member`: reads, comments, reacts and chats, but does not post photos.
ALTER TABLE feed_members DROP CONSTRAINT feed_members_role_check;
ALTER TABLE feed_members ADD CONSTRAINT feed_members_role_check
    CHECK (role IN ('owner', 'admin', 'poster', 'member'));

-- members_see_reactions now means frames' reactions in the reaction row (default on for new feeds);
-- members_see_receipts is the per-frame receipts. Existing feeds keep what members saw: today's one flag
-- covered both.
ALTER TABLE feeds
    ADD COLUMN members_see_receipts       boolean NOT NULL DEFAULT false,
    ADD COLUMN new_members_can_post       boolean NOT NULL DEFAULT true,   -- joiners: poster, else member
    ADD COLUMN members_can_create_circles boolean NOT NULL DEFAULT true;
UPDATE feeds SET members_see_receipts = members_see_reactions;
ALTER TABLE feeds ALTER COLUMN members_see_reactions SET DEFAULT true;

-- The member's own mute for this family (FeedMute).
ALTER TABLE feed_members
    ADD COLUMN mute text NOT NULL DEFAULT 'none' CHECK (mute IN ('none', 'posts', 'chat', 'all'));

-- ---------------------------------------------------------------- circles

-- Circles are soft-deleted (deleted_at) by code after their posts were removed; a hard delete only happens
-- through the feed's own purge (ON DELETE CASCADE from feeds).
CREATE TABLE circles (
    id         uuid PRIMARY KEY,
    feed_id    uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    name       text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 40),
    created_by uuid REFERENCES users ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    UNIQUE (id, feed_id)                              -- target of circle_members' composite key
);
CREATE UNIQUE INDEX circles_name_unique ON circles (feed_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX circles_feed_idx ON circles (feed_id) WHERE deleted_at IS NULL;

-- Circle members are always feed members: leaving or being removed from the feed removes the row (cascade).
CREATE TABLE circle_members (
    circle_id uuid        NOT NULL,
    feed_id   uuid        NOT NULL,
    user_id   uuid        NOT NULL,
    role      text        NOT NULL DEFAULT 'member' CHECK (role IN ('admin', 'member')),
    added_by  uuid REFERENCES users ON DELETE SET NULL,
    added_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (circle_id, user_id),
    FOREIGN KEY (circle_id, feed_id) REFERENCES circles (id, feed_id) ON DELETE CASCADE,
    FOREIGN KEY (feed_id, user_id) REFERENCES feed_members (feed_id, user_id) ON DELETE CASCADE
);
CREATE INDEX circle_members_user_idx ON circle_members (user_id);
CREATE INDEX circle_members_feed_user_idx ON circle_members (feed_id, user_id);

-- Extra frames a circle's posts reach (Circle.frame_ids), besides subscribed frames with a manager in the circle.
CREATE TABLE circle_frames (
    circle_id uuid        NOT NULL REFERENCES circles ON DELETE CASCADE,
    frame_id  uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    added_by  uuid REFERENCES users ON DELETE SET NULL,
    added_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (circle_id, frame_id)
);
CREATE INDEX circle_frames_frame_idx ON circle_frames (frame_id);

-- A post's audience: null = the whole family.
ALTER TABLE feed_posts ADD COLUMN circle_id uuid REFERENCES circles ON DELETE CASCADE;
CREATE INDEX feed_posts_circle_idx ON feed_posts (circle_id, id DESC) WHERE circle_id IS NOT NULL AND removed_at IS NULL;

-- ---------------------------------------------------------------- frame posts

-- A direct send to one frame, as a timeline post with a thread: one per send and frame (a forward to another
-- frame adds one). Visible to the sender and the frame's members with see_library (owner/admin included).
-- Made for sends by people (sends.via null, 'email' or 'telegram'), not for photo-source syncs or Google
-- imports, shares (placements:share) or frame imports. removed_at is set when its last placement on that
-- frame is removed.
CREATE TABLE frame_posts (
    id             uuid PRIMARY KEY,                  -- UUIDv7: timeline order, shares the id space of feed_posts
    send_id        uuid        NOT NULL REFERENCES sends ON DELETE CASCADE,
    frame_id       uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    sender_user_id uuid REFERENCES users ON DELETE SET NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    removed_at     timestamptz,
    UNIQUE (send_id, frame_id)
);
CREATE INDEX frame_posts_frame_idx ON frame_posts (frame_id, id DESC) WHERE removed_at IS NULL;
CREATE INDEX frame_posts_sender_idx ON frame_posts (sender_user_id, id DESC) WHERE removed_at IS NULL;

INSERT INTO frame_posts (id, send_id, frame_id, sender_user_id, created_at)
SELECT kinframe_uuidv7(x.first_at), x.send_id, x.frame_id, x.sender_user_id, x.first_at
FROM (
    SELECT p.send_id, p.frame_id, s.sender_user_id, min(p.received_at) AS first_at
    FROM placements p
    JOIN sends s ON s.id = p.send_id
    WHERE p.removed_at IS NULL AND p.origin = 'send' AND p.shared_by_user_id IS NULL
      AND (s.via IS NULL OR s.via IN ('email', 'telegram'))
    GROUP BY p.send_id, p.frame_id, s.sender_user_id
) x;

-- ---------------------------------------------------------------- threads and comments

-- One per post (feed post or frame post), created lazily by the first comment, reaction, frame entry, read
-- mark, mute or state change: a post without a row has an open, empty thread. post_id is the post's id.
CREATE TABLE threads (
    post_id         uuid PRIMARY KEY,
    feed_post_id    uuid UNIQUE REFERENCES feed_posts ON DELETE CASCADE,
    frame_post_id   uuid UNIQUE REFERENCES frame_posts ON DELETE CASCADE,
    feed_id         uuid REFERENCES feeds ON DELETE CASCADE,       -- feed posts: denormalized for visibility
    circle_id       uuid REFERENCES circles ON DELETE CASCADE,     -- feed posts with a circle audience
    frame_id        uuid REFERENCES frames ON DELETE CASCADE,      -- frame posts
    state           text        NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'closed')),
    closed_by       uuid REFERENCES users ON DELETE SET NULL,
    closed_at       timestamptz,
    comment_count   integer     NOT NULL DEFAULT 0,  -- live person comments and replies (no tombstones, not removed)
    last_comment_id uuid,
    last_comment_at timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK ((feed_post_id IS NULL) <> (frame_post_id IS NULL)),
    CHECK (post_id = coalesce(feed_post_id, frame_post_id)),
    CHECK ((feed_post_id IS NULL) = (feed_id IS NULL)),
    CHECK ((frame_post_id IS NULL) = (frame_id IS NULL))
);
CREATE INDEX threads_feed_idx ON threads (feed_id, last_comment_at DESC) WHERE feed_id IS NOT NULL;
CREATE INDEX threads_frame_idx ON threads (frame_id, last_comment_at DESC) WHERE frame_id IS NOT NULL;

-- Comments, replies (one level: parent_id is always a top-level comment) and frame entries.
-- Bodies: plain `body`, or `body_enc` (secrets.Sealer, info "kinframe message bodies v1") when the server
-- has KINFRAME_SECRETS_KEY; never both. Author deleted = users.deleted_at set ("Former member").
CREATE TABLE comments (
    id             uuid PRIMARY KEY,                -- UUIDv7: thread order
    post_id        uuid        NOT NULL REFERENCES threads ON DELETE CASCADE,
    parent_id      uuid REFERENCES comments ON DELETE CASCADE,
    kind           text        NOT NULL DEFAULT 'person' CHECK (kind IN ('person', 'frame')),
    author_user_id uuid REFERENCES users ON DELETE SET NULL,      -- person comments
    quoted_user_id uuid REFERENCES users ON DELETE SET NULL,      -- a reply to a reply quotes that person
    frame_id       uuid REFERENCES frames ON DELETE SET NULL,     -- frame entries
    frame_name     text,                                          -- snapshot when the frame made the entry
    placement_id   uuid REFERENCES placements ON DELETE SET NULL,
    item_id        uuid REFERENCES items ON DELETE SET NULL,
    frame_reaction text CHECK (frame_reaction IN ('love', 'laugh', 'wow', 'sad')),
    frame_reply    text CHECK (frame_reply IN ('beautiful', 'call_me', 'miss_you', 'thanks')),
    body           text,
    body_enc       bytea,
    mentions       jsonb       NOT NULL DEFAULT '[]',              -- [{user_id, start, length}], UTF-16 offsets
    created_at     timestamptz NOT NULL DEFAULT now(),
    edited_at      timestamptz,
    deleted_at     timestamptz,                                   -- by the author (tombstone if it has replies)
    removed_at     timestamptz,                                   -- by a moderator (hidden, also its replies)
    removed_by     uuid REFERENCES users ON DELETE SET NULL,
    CHECK (body IS NULL OR body_enc IS NULL),
    CHECK (kind <> 'person' OR (frame_id IS NULL AND frame_reaction IS NULL AND frame_reply IS NULL)),
    CHECK (kind <> 'frame' OR (parent_id IS NULL AND body IS NULL AND body_enc IS NULL
                               AND (frame_reaction IS NULL) <> (frame_reply IS NULL)))
);
CREATE INDEX comments_post_idx ON comments (post_id, id);
CREATE INDEX comments_parent_idx ON comments (parent_id, id) WHERE parent_id IS NOT NULL;
CREATE INDEX comments_author_idx ON comments (author_user_id, id DESC) WHERE author_user_id IS NOT NULL;
-- One "Oma's frame loved this" entry per frame and post (later reactions update the reaction row only).
CREATE UNIQUE INDEX comments_one_frame_reaction ON comments (post_id, frame_id)
    WHERE kind = 'frame' AND frame_reaction IS NOT NULL AND frame_id IS NOT NULL;

-- Who a comment mentions (from comments.mentions), for mention pushes and the badge.
CREATE TABLE comment_mentions (
    comment_id uuid NOT NULL REFERENCES comments ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users ON DELETE CASCADE,
    PRIMARY KEY (comment_id, user_id)
);
CREATE INDEX comment_mentions_user_idx ON comment_mentions (user_id);

-- Per person and thread: read position (for unread counts and the badge) and mute.
CREATE TABLE thread_reads (
    user_id    uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    post_id    uuid        NOT NULL REFERENCES threads ON DELETE CASCADE,
    read_up_to uuid,                                  -- a comment id; null = nothing read
    read_at    timestamptz,
    muted      boolean     NOT NULL DEFAULT false,
    PRIMARY KEY (user_id, post_id)
);
CREATE INDEX thread_reads_post_idx ON thread_reads (post_id);

-- ---------------------------------------------------------------- reactions by people

-- One per person and target; the latest wins. scope_id groups targets for visibility and clean-up: the post
-- id for post, post_item (target = item id) and comment targets, the conversation id for message targets.
-- No foreign keys (polymorphic): code deletes reactions with their scope (post removal, conversation
-- deletion) and ignores dangling ones. Frames' reactions stay on placements (README §5.5).
CREATE TABLE reactions (
    target_kind text        NOT NULL CHECK (target_kind IN ('post', 'post_item', 'comment', 'message')),
    target_id   uuid        NOT NULL,
    scope_id    uuid        NOT NULL,
    user_id     uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    emoji       text        NOT NULL CHECK (emoji IN ('love', 'laugh', 'wow', 'sad', 'thumbs_up', 'pray')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (target_kind, target_id, scope_id, user_id),
    CHECK (target_kind <> 'post' OR target_id = scope_id)
);
CREATE INDEX reactions_scope_idx ON reactions (scope_id, target_kind);
CREATE INDEX reactions_user_idx ON reactions (user_id, created_at DESC);

-- ---------------------------------------------------------------- push bundling

-- Collapsing and bundling of social and chat pushes (README §6.3). bundle_key: 'comment:<post_id>' (10-minute
-- bundle), 'comment_reply:<comment_id>', 'reaction:<target_kind>:<target_id>' (first 3 pushed, then counted for
-- the daily digest), 'reaction_digest' (the daily line), 'conversation:<conversation_id>'. Rows older than
-- 30 days without pending work may be deleted.
CREATE TABLE push_bundles (
    user_id       uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    bundle_key    text        NOT NULL,
    pushed_count  integer     NOT NULL DEFAULT 0,     -- pushes sent for this key
    pending_count integer     NOT NULL DEFAULT 0,     -- events waiting for the window or the digest
    actor_ids     uuid[]      NOT NULL DEFAULT '{}',  -- latest distinct actors, newest first, at most 5
    window_until  timestamptz,                        -- bundle window end: send what is pending then
    last_event_at timestamptz,
    last_sent_at  timestamptz,
    PRIMARY KEY (user_id, bundle_key)
);
CREATE INDEX push_bundles_due_idx ON push_bundles (window_until) WHERE pending_count > 0;

-- +goose Down
DROP TABLE push_bundles, reactions, thread_reads, comment_mentions, comments, threads, frame_posts;
DROP INDEX feed_posts_circle_idx;
ALTER TABLE feed_posts DROP COLUMN circle_id;
DROP TABLE circle_frames, circle_members, circles;
ALTER TABLE feed_members DROP COLUMN mute;
ALTER TABLE feeds ALTER COLUMN members_see_reactions SET DEFAULT false;
ALTER TABLE feeds DROP COLUMN members_can_create_circles, DROP COLUMN new_members_can_post,
    DROP COLUMN members_see_receipts;
UPDATE feed_members SET role = 'poster' WHERE role = 'member';
ALTER TABLE feed_members DROP CONSTRAINT feed_members_role_check;
ALTER TABLE feed_members ADD CONSTRAINT feed_members_role_check CHECK (role IN ('owner', 'admin', 'poster'));
DROP FUNCTION kinframe_uuidv7(timestamptz);
