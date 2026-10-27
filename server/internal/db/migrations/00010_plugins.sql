-- +goose Up

-- What a frame reports about its plugins (docs/plugins.md §10): the catalog of plugins it can run (their manifests and
-- translations), and the status of its instances with what it discovered for the apps' forms (Home Assistant entities,
-- mDNS services). The instances themselves live in frames.settings (plugins.instances).
ALTER TABLE frames
    ADD COLUMN plugin_catalog jsonb,
    ADD COLUMN plugin_catalog_at timestamptz,
    ADD COLUMN plugin_status jsonb,
    ADD COLUMN plugin_status_at timestamptz;

-- +goose Down
ALTER TABLE frames
    DROP COLUMN plugin_catalog,
    DROP COLUMN plugin_catalog_at,
    DROP COLUMN plugin_status,
    DROP COLUMN plugin_status_at;
