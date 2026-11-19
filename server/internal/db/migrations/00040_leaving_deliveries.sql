-- +goose Up

-- Leaving a family (docs/spec/leaving.md, README §5.21), owned by server-leaving: the feed deliveries a taking cut.
-- Taking a family post away deletes its feed_deliveries rows (so sweeps, reconciles and receipts treat the items as
-- gone) and keeps a copy here, so a keep request on the family side can put the post back on exactly the frames
-- that had it. The rows go when the taking is purged (30 days) or the delivery is restored.
CREATE TABLE taking_deliveries (
    taking_id       uuid NOT NULL REFERENCES takings ON DELETE CASCADE,
    subscription_id uuid NOT NULL REFERENCES subscriptions ON DELETE CASCADE,
    post_id         uuid NOT NULL,
    item_id         uuid NOT NULL,
    placement_id    uuid REFERENCES placements ON DELETE CASCADE,
    PRIMARY KEY (taking_id, subscription_id, post_id, item_id),
    FOREIGN KEY (post_id, item_id) REFERENCES feed_post_items ON DELETE CASCADE
);
CREATE INDEX taking_deliveries_post_idx ON taking_deliveries (post_id, item_id);
CREATE INDEX taking_deliveries_placement_idx ON taking_deliveries (placement_id) WHERE placement_id IS NOT NULL;

-- +goose Down
DROP TABLE taking_deliveries;
