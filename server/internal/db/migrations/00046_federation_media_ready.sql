-- +goose Up

-- Families on different servers (server-federation): a post reaches remote frames with the items whose media is
-- ready. When an item's media (or its motion clip) becomes ready later, the post is logged again (`post.upsert`)
-- so other servers copy the rest. Only families with a live remote subscriber; circle posts never.
-- +goose StatementBegin
CREATE FUNCTION federation_media_ready() RETURNS trigger AS $$
BEGIN
    IF NEW.status = 'ready' AND OLD.status IS DISTINCT FROM 'ready' THEN
        INSERT INTO federation_changes (feed_id, kind, post_id)
        SELECT DISTINCT p.feed_id, 'post.upsert', p.id
        FROM items i
        JOIN feed_post_items pi ON pi.item_id = i.id AND pi.removed_at IS NULL
        JOIN feed_posts p ON p.id = pi.post_id AND p.removed_at IS NULL AND p.circle_id IS NULL
        WHERE (i.media_id = NEW.id OR i.motion_media_id = NEW.id) AND federation_feed_has_remote(p.feed_id);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER media_federation AFTER UPDATE OF status ON media
    FOR EACH ROW EXECUTE FUNCTION federation_media_ready();

-- +goose Down
DROP TRIGGER media_federation ON media;
DROP FUNCTION federation_media_ready();
