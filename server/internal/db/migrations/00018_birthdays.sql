-- +goose Up

-- Birthdays (docs/spec/birthdays.md §12): a feed keeps a list of birthday people; frames with an active
-- subscription that has `birthdays` on get the merged list as a `birthdays` change (entity = frame id).

ALTER TABLE feeds ADD COLUMN posters_can_add_birthdays boolean NOT NULL DEFAULT false;
ALTER TABLE feed_members ADD COLUMN birthday_notifications boolean NOT NULL DEFAULT true;
ALTER TABLE subscriptions ADD COLUMN birthdays boolean NOT NULL DEFAULT true;
ALTER TABLE users ADD COLUMN timezone text;                  -- IANA name, set by the apps (PATCH /v1/me)

CREATE TABLE feed_birthdays (
    id            uuid PRIMARY KEY,
    feed_id       uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    status        text        NOT NULL CHECK (status IN ('pending', 'approved', 'rejected')),
    display_name  text        NOT NULL,
    name_key      text        NOT NULL,                      -- NFKC, case-folded, no diacritics, collapsed spaces
    month         smallint    NOT NULL CHECK (month BETWEEN 1 AND 12),
    day           smallint    NOT NULL CHECK (day BETWEEN 1 AND 31),
    birth_year    smallint             CHECK (birth_year >= 1900),
    show_age      boolean     NOT NULL DEFAULT false,
    user_id       uuid REFERENCES users ON DELETE SET NULL,  -- linked feed member; cleared when they leave
    photo_key     text,                                      -- avatar-style blob key; null = none (avatar fallback)
    created_by    uuid REFERENCES users ON DELETE SET NULL,
    suggested     boolean     NOT NULL DEFAULT false,        -- created as a suggestion
    decided_by    uuid REFERENCES users ON DELETE SET NULL,
    decided_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    -- A real day of the month (29 February allowed); the store validates first and answers 422.
    CHECK (day <= CASE month WHEN 2 THEN 29 WHEN 4 THEN 30 WHEN 6 THEN 30 WHEN 9 THEN 30 WHEN 11 THEN 30 ELSE 31 END),
    CHECK (birth_year IS NULL OR month <> 2 OR day <> 29
           OR (birth_year % 4 = 0 AND (birth_year % 100 <> 0 OR birth_year % 400 = 0)))
);
CREATE UNIQUE INDEX feed_birthdays_unique ON feed_birthdays (feed_id, name_key, month, day)
    WHERE status IN ('pending', 'approved');
CREATE UNIQUE INDEX feed_birthdays_one_per_user ON feed_birthdays (feed_id, user_id)
    WHERE user_id IS NOT NULL AND status IN ('pending', 'approved');
CREATE INDEX feed_birthdays_feed_idx ON feed_birthdays (feed_id, status);
CREATE INDEX feed_birthdays_day_idx ON feed_birthdays (month, day) WHERE status = 'approved';
CREATE INDEX feed_birthdays_user_idx ON feed_birthdays (user_id) WHERE user_id IS NOT NULL;

-- One per (person, frame, occurrence date): the rate limit is the key.
CREATE TABLE birthday_greetings (
    id             uuid PRIMARY KEY,
    birthday_id    uuid        NOT NULL REFERENCES feed_birthdays ON DELETE CASCADE,
    feed_id        uuid        NOT NULL REFERENCES feeds ON DELETE CASCADE,
    frame_id       uuid        NOT NULL REFERENCES frames ON DELETE CASCADE,
    local_date     date        NOT NULL,
    frame_name     text        NOT NULL,                     -- snapshot for the activity list
    frame_location text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (birthday_id, frame_id, local_date)
);
CREATE INDEX birthday_greetings_feed_idx ON birthday_greetings (feed_id, created_at DESC);
CREATE INDEX birthday_greetings_frame_idx ON birthday_greetings (frame_id, created_at);

-- The morning notice: at most one per member and local date.
CREATE TABLE birthday_notices (
    user_id    uuid NOT NULL REFERENCES users ON DELETE CASCADE,
    local_date date NOT NULL,
    sent_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, local_date)
);

-- What each frame was last sent: a digest of its merged list, so unchanged content is not emitted again.
-- The change itself is rendered from current state at read time; emitted_at is its generated_at.
CREATE TABLE frame_birthdays (
    frame_id   uuid PRIMARY KEY REFERENCES frames ON DELETE CASCADE,
    digest     text        NOT NULL,
    emitted_at timestamptz NOT NULL DEFAULT now()
);

-- +goose Down
DROP TABLE frame_birthdays, birthday_notices, birthday_greetings, feed_birthdays;
ALTER TABLE users DROP COLUMN timezone;
ALTER TABLE subscriptions DROP COLUMN birthdays;
ALTER TABLE feed_members DROP COLUMN birthday_notifications;
ALTER TABLE feeds DROP COLUMN posters_can_add_birthdays;
