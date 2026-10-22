-- +goose Up

-- Weather fetched from Open-Meteo, shared by every frame whose location rounds
-- to the same key ("51.05,3.72"). Payloads are always metric.
CREATE TABLE weather_cache (
    key           text PRIMARY KEY,
    lat           double precision NOT NULL,
    lon           double precision NOT NULL,
    payload       jsonb,
    fetched_at    timestamptz,
    next_fetch_at timestamptz      NOT NULL DEFAULT now(),
    failures      integer          NOT NULL DEFAULT 0,
    last_error    text
);

-- What each frame was last sent (the `weather` change renders from this).
CREATE TABLE frame_weather (
    frame_id   uuid PRIMARY KEY REFERENCES frames ON DELETE CASCADE,
    cache_key  text        NOT NULL,
    digest     text        NOT NULL,
    emitted_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX frame_weather_key_idx ON frame_weather (cache_key);

-- +goose Down
DROP TABLE frame_weather, weather_cache;
