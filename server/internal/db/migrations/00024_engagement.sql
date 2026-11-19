-- +goose Up

-- Family social, part 3 (docs/spec/family-social.md §9, README §5.20): freshness, "Ask for new photos",
-- reminder preferences and the nudge log (budget), family engagement settings and streaks, weekly prompts,
-- weekly summaries, monthly recaps, personal milestones, and what frames were last sent of comments and prompts.

-- ---------------------------------------------------------------- freshness

-- Kept up to date when placements are created (not for backfills): "personal" = a send by a person
-- (sends.via null, 'email' or 'telegram'; feed posts, direct sends, chats put on a frame), "source" = photo
-- sources and Google imports (count half). Frame imports, shares, birthdays and calendars never count.
CREATE TABLE frame_freshness (
    frame_id              uuid PRIMARY KEY REFERENCES frames ON DELETE CASCADE,
    last_personal_at      timestamptz,
    last_personal_user_id uuid REFERENCES users ON DELETE SET NULL,
    last_source_at        timestamptz,
    updated_at            timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE feed_freshness (
    feed_id               uuid PRIMARY KEY REFERENCES feeds ON DELETE CASCADE,
    last_personal_at      timestamptz,
    last_personal_user_id uuid REFERENCES users ON DELETE SET NULL,
    last_source_at        timestamptz,
    updated_at            timestamptz NOT NULL DEFAULT now()
);

INSERT INTO frame_freshness (frame_id, last_personal_at, last_source_at)
SELECT p.frame_id,
       max(p.received_at) FILTER (WHERE s.via IS NULL OR s.via IN ('email', 'telegram')),
       max(p.received_at) FILTER (WHERE s.via IN ('immich', 'icloud', 'google_photos'))
FROM placements p JOIN sends s ON s.id = p.send_id
WHERE NOT p.backfill AND p.origin IN ('send', 'feed')
GROUP BY p.frame_id;
UPDATE frame_freshness ff SET last_personal_user_id = (
    SELECT s.sender_user_id FROM placements p JOIN sends s ON s.id = p.send_id
    WHERE p.frame_id = ff.frame_id AND p.received_at = ff.last_personal_at LIMIT 1)
WHERE ff.last_personal_at IS NOT NULL;

INSERT INTO feed_freshness (feed_id, last_personal_at, last_source_at)
SELECT fp.feed_id,
       max(fp.created_at) FILTER (WHERE s.via IS NULL OR s.via IN ('email', 'telegram')),
       max(fp.created_at) FILTER (WHERE s.via IN ('immich', 'icloud', 'google_photos'))
FROM feed_posts fp JOIN sends s ON s.id = fp.send_id
WHERE fp.removed_at IS NULL
GROUP BY fp.feed_id;
UPDATE feed_freshness ff SET last_personal_user_id = (
    SELECT fp.poster_user_id FROM feed_posts fp
    WHERE fp.feed_id = ff.feed_id AND fp.created_at = ff.last_personal_at LIMIT 1)
WHERE ff.last_personal_at IS NOT NULL;

-- ---------------------------------------------------------------- "Ask for new photos"

CREATE TABLE photo_requests (
    id                  uuid PRIMARY KEY,
    frame_id            uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    state               text        NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'answered', 'expired')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    expires_at          timestamptz NOT NULL,               -- created_at + 7 days
    answered_at         timestamptz,
    answered_by_user_id uuid REFERENCES users ON DELETE SET NULL,
    answered_send_id    uuid REFERENCES sends ON DELETE SET NULL,
    notified_user_ids   uuid[]      NOT NULL DEFAULT '{}',  -- who got the frame_request push
    CHECK ((state = 'answered') = (answered_at IS NOT NULL))
);
CREATE UNIQUE INDEX photo_requests_one_open ON photo_requests (frame_id) WHERE state = 'open';
CREATE INDEX photo_requests_frame_idx ON photo_requests (frame_id, created_at DESC);
CREATE INDEX photo_requests_expiry_idx ON photo_requests (expires_at) WHERE state = 'open';

-- ---------------------------------------------------------------- reminders

-- Absent row = defaults. level_auto: the server lowered the level after 4 unanswered reminders in a row;
-- auto_reduced_seen_at: the app showed it.
CREATE TABLE reminder_preferences (
    user_id              uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,
    level                text        NOT NULL DEFAULT 'normal' CHECK (level IN ('normal', 'fewer', 'off')),
    level_auto           boolean     NOT NULL DEFAULT false,
    auto_reduced_seen_at timestamptz,
    frame_requests       boolean     NOT NULL DEFAULT true,
    weekly_summary       boolean     NOT NULL DEFAULT true,
    birthday_reminders   boolean     NOT NULL DEFAULT true,
    snoozed_until        timestamptz,
    quiet_start          time        NOT NULL DEFAULT '21:00',
    quiet_end            time        NOT NULL DEFAULT '09:00',
    unanswered_in_row    integer     NOT NULL DEFAULT 0,
    email_digest         text        NOT NULL DEFAULT 'off' CHECK (email_digest IN ('off', 'daily', 'weekly')),
    email_digest_sent_at timestamptz,                     -- the last digest's cut-off
    updated_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reminder_preferences_digest_idx ON reminder_preferences (email_digest) WHERE email_digest <> 'off';

-- Every reminder and request push sent (the budget, rotation and the "did a share follow" outcome). A share by
-- the user within 24 h of sent_at sets outcome 'shared'; otherwise the scheduler sets 'ignored'.
-- dedupe_key makes a reminder at most once: 'reminder_birthday:<birthday_id>:<date>',
-- 'reminder_streak:<feed_id>:<week_of>', 'reminder_quiet:<frame_id>:<date>', 'weekly_summary:<week_of>',
-- 'frame_request:<request_id>'. counts_in_budget is false for frame requests (they come from a person).
CREATE TABLE nudges (
    id               uuid PRIMARY KEY,
    user_id          uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    kind             text        NOT NULL CHECK (kind IN ('reminder_quiet', 'reminder_streak', 'reminder_birthday',
                                                          'weekly_summary', 'frame_request')),
    frame_id         uuid REFERENCES frames ON DELETE SET NULL,
    feed_id          uuid REFERENCES feeds ON DELETE SET NULL,
    subject_id       uuid,                                -- birthday id, request id
    dedupe_key       text        NOT NULL,
    counts_in_budget boolean     NOT NULL DEFAULT true,
    sent_at          timestamptz NOT NULL DEFAULT now(),
    outcome          text        NOT NULL DEFAULT 'pending' CHECK (outcome IN ('pending', 'shared', 'ignored')),
    outcome_at       timestamptz,
    UNIQUE (user_id, dedupe_key)
);
CREATE INDEX nudges_user_sent_idx ON nudges (user_id, sent_at DESC);
CREATE INDEX nudges_frame_idx ON nudges (frame_id, kind, sent_at DESC) WHERE frame_id IS NOT NULL;
CREATE INDEX nudges_feed_idx ON nudges (feed_id, kind, sent_at DESC) WHERE feed_id IS NOT NULL;
CREATE INDEX nudges_pending_idx ON nudges (sent_at) WHERE outcome = 'pending';

-- ---------------------------------------------------------------- family settings, streaks, prompts

-- One row per feed (created by the trigger below, backfilled here). timezone null = derived (README §5.20).
CREATE TABLE feed_engagement (
    feed_id          uuid PRIMARY KEY REFERENCES feeds ON DELETE CASCADE,
    streak_enabled   boolean     NOT NULL DEFAULT true,
    streak_goal      smallint    NOT NULL DEFAULT 3 CHECK (streak_goal BETWEEN 1 AND 20),
    prompts_enabled  boolean     NOT NULL DEFAULT false,
    quiet_after_days smallint    NOT NULL DEFAULT 7 CHECK (quiet_after_days BETWEEN 3 AND 30),
    monthly_recap    boolean     NOT NULL DEFAULT true,
    timezone         text,
    updated_at       timestamptz NOT NULL DEFAULT now()
);
INSERT INTO feed_engagement (feed_id) SELECT id FROM feeds;
-- +goose StatementBegin
CREATE FUNCTION engagement_feed_created() RETURNS trigger AS $$
BEGIN
    INSERT INTO feed_engagement (feed_id) VALUES (NEW.id) ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER feeds_engagement_created AFTER INSERT ON feeds
    FOR EACH ROW EXECUTE FUNCTION engagement_feed_created();

-- The running streak. week_of: the Monday of the open week (feed time zone). Freezes: one granted on the 1st of
-- every month (freeze_granted_month), at most 2 held, used automatically on a missed week.
CREATE TABLE feed_streaks (
    feed_id              uuid PRIMARY KEY REFERENCES feeds ON DELETE CASCADE,
    current_weeks        integer     NOT NULL DEFAULT 0,
    best_weeks           integer     NOT NULL DEFAULT 0,
    freezes_left         smallint    NOT NULL DEFAULT 1 CHECK (freezes_left BETWEEN 0 AND 2),
    freeze_granted_month date,
    week_of              date        NOT NULL CHECK (extract(isodow FROM week_of) = 1),
    last_milestone_weeks integer     NOT NULL DEFAULT 0,  -- 4, 12, 26, 52, 104, … already celebrated
    updated_at           timestamptz NOT NULL DEFAULT now()
);

-- One row per feed and week once the week has a post or was closed: photos counted (personal posts' live items),
-- the goal that applied, and the outcome when the week closed ('open' while it runs).
CREATE TABLE feed_streak_weeks (
    feed_id uuid     NOT NULL REFERENCES feeds ON DELETE CASCADE,
    week_of date     NOT NULL CHECK (extract(isodow FROM week_of) = 1),
    photos  integer  NOT NULL DEFAULT 0,
    goal    smallint NOT NULL,
    outcome text     NOT NULL DEFAULT 'open' CHECK (outcome IN ('open', 'met', 'frozen', 'missed')),
    PRIMARY KEY (feed_id, week_of)
);

-- Who posted in a week ("who helped": avatars, no counts).
CREATE TABLE feed_streak_helpers (
    feed_id uuid NOT NULL,
    week_of date NOT NULL,
    user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
    PRIMARY KEY (feed_id, week_of, user_id),
    FOREIGN KEY (feed_id, week_of) REFERENCES feed_streak_weeks ON DELETE CASCADE
);

-- A week's prompt when an admin chose one, wrote one or skipped the week, or once the server's choice became
-- current (rows for the server's choice are created when the week starts, so `id` is stable for tagging).
CREATE TABLE feed_prompts (
    id           uuid PRIMARY KEY,
    feed_id      uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    week_of      date        NOT NULL CHECK (extract(isodow FROM week_of) = 1),
    source       text        NOT NULL CHECK (source IN ('server', 'custom', 'skipped')),
    library_key  text,                                    -- server prompts: the i18n key 'prompt.<key>'
    title        text CHECK (title IS NULL OR char_length(title) BETWEEN 1 AND 80),   -- custom prompts
    set_by       uuid REFERENCES users ON DELETE SET NULL,
    announced_at timestamptz,                             -- the `prompt` system message in the family chat
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (feed_id, week_of),
    CHECK (source <> 'server' OR (library_key IS NOT NULL AND title IS NULL)),
    CHECK (source <> 'custom' OR (title IS NOT NULL AND library_key IS NULL)),
    CHECK (source <> 'skipped' OR (title IS NULL AND library_key IS NULL))
);

ALTER TABLE feed_posts ADD COLUMN prompt_id uuid REFERENCES feed_prompts ON DELETE SET NULL;
CREATE INDEX feed_posts_prompt_idx ON feed_posts (prompt_id) WHERE prompt_id IS NOT NULL AND removed_at IS NULL;

-- ---------------------------------------------------------------- summaries, recaps, milestones

-- The weekly "seen and loved" summary per user and week (Monday, user's time zone); kept 12 weeks.
CREATE TABLE weekly_summaries (
    user_id    uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    week_of    date        NOT NULL CHECK (extract(isodow FROM week_of) = 1),
    data       jsonb       NOT NULL,                      -- WeeklySummary without week_of
    created_at timestamptz NOT NULL DEFAULT now(),
    pushed_at  timestamptz,
    PRIMARY KEY (user_id, week_of)
);

CREATE TABLE feed_recaps (
    feed_id    uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    month      date        NOT NULL CHECK (extract(day FROM month) = 1),   -- first day of the month
    data       jsonb       NOT NULL,                      -- MonthlyRecap without feed_id and month
    message_id uuid REFERENCES messages ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (feed_id, month)
);

CREATE TABLE user_milestones (
    user_id     uuid        NOT NULL REFERENCES users ON DELETE CASCADE,
    key         text        NOT NULL CHECK (key IN ('first_photo', 'photos_10', 'photos_100', 'photos_1000',
                                                    'first_video', 'year_of_sharing', 'loves_100')),
    achieved_at timestamptz NOT NULL DEFAULT now(),
    shown       boolean     NOT NULL DEFAULT false,
    seen_at     timestamptz,
    PRIMARY KEY (user_id, key)
);

-- ---------------------------------------------------------------- what frames were last sent

-- Like frame_birthdays: a digest of what the frame last got, so unchanged content is not emitted again. The
-- change itself is rendered from current state when the frame reads its change feed.
CREATE TABLE frame_comment_cards (
    placement_id uuid PRIMARY KEY REFERENCES placements ON DELETE CASCADE,
    frame_id     uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    digest       text        NOT NULL,
    emitted_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX frame_comment_cards_frame_idx ON frame_comment_cards (frame_id);

CREATE TABLE frame_prompt_cards (
    frame_id   uuid PRIMARY KEY REFERENCES frames ON DELETE CASCADE,
    digest     text        NOT NULL,
    emitted_at timestamptz NOT NULL DEFAULT now()
);

-- +goose Down
DROP TABLE frame_prompt_cards, frame_comment_cards, user_milestones, feed_recaps, weekly_summaries;
DROP INDEX feed_posts_prompt_idx;
ALTER TABLE feed_posts DROP COLUMN prompt_id;
DROP TABLE feed_prompts, feed_streak_helpers, feed_streak_weeks, feed_streaks;
DROP TRIGGER feeds_engagement_created ON feeds;
DROP FUNCTION engagement_feed_created();
DROP TABLE feed_engagement, nudges, reminder_preferences, photo_requests, feed_freshness, frame_freshness;
