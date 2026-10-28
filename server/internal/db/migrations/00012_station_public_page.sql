-- +goose Up

-- A station's public page (docs/plugins.md §9.4): only when the owner turned it on in the plugin's settings, under
-- the title they chose. Nothing about the frame or its place is shown there.
ALTER TABLE weather_stations
    ADD COLUMN public      boolean NOT NULL DEFAULT false,
    ADD COLUMN public_name text    NOT NULL DEFAULT '';
CREATE INDEX weather_stations_public ON weather_stations (upper(upload_id)) WHERE public;

-- +goose Down
DROP INDEX weather_stations_public;
ALTER TABLE weather_stations DROP COLUMN public_name, DROP COLUMN public;
