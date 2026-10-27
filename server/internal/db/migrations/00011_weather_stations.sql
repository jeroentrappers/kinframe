-- +goose Up

-- Personal weather stations that upload to kinframe (docs/plugins.md §9.4): registered by a frame's weather-station
-- plugin instance (its station ID and key), so only stations someone set up are accepted. A station is known by an
-- HMAC of its ID and key: the key itself is only in the frame's sealed plugin secrets.
CREATE TABLE weather_stations (
    frame_id    uuid    NOT NULL REFERENCES frames (id) ON DELETE CASCADE,
    instance_id text    NOT NULL,
    upload_id   text    NOT NULL,
    key_mac     bytea   NOT NULL,
    forward     boolean NOT NULL DEFAULT true,
    PRIMARY KEY (frame_id, instance_id)
);
CREATE INDEX weather_stations_key_mac ON weather_stations (key_mac);

-- What the stations sent, normalised to metric; kept for 48 hours.
CREATE TABLE station_readings (
    key_mac bytea       NOT NULL,
    at      timestamptz NOT NULL,
    reading jsonb       NOT NULL,
    PRIMARY KEY (key_mac, at)
);

-- +goose Down
DROP TABLE station_readings;
DROP TABLE weather_stations;
