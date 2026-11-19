-- +goose Up

-- Encouragement to share (README §5.20): keep frame_freshness and feed_freshness up to date wherever placements
-- and posts are made (sends, forwards, feed fanout, email, Telegram, photo sources, chats put on a frame), and
-- remember which posts' comments changed so frames with "Show comments" get a new `comments` change.

-- Credit: the send's person, except outside contributions (Telegram sent by the chat's owner on someone's behalf,
-- mail delivered on behalf of an unknown sender), which count as personal photos but credit nobody.
-- +goose StatementBegin
CREATE FUNCTION engagement_placement_freshness() RETURNS trigger AS $$
DECLARE
    v_via     text;
    v_sender  uuid;
    v_outside boolean;
BEGIN
    SELECT s.via, s.sender_user_id, s.telegram_outside INTO v_via, v_sender, v_outside FROM sends s WHERE s.id = NEW.send_id;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;
    IF v_via IS NULL OR v_via IN ('email', 'telegram') THEN
        IF v_outside OR EXISTS (SELECT 1 FROM items i WHERE i.id = NEW.item_id AND i.via_email_address_id IS NOT NULL) THEN
            v_sender := NULL;
        END IF;
        INSERT INTO frame_freshness AS ff (frame_id, last_personal_at, last_personal_user_id, updated_at)
        VALUES (NEW.frame_id, NEW.received_at, v_sender, now())
        ON CONFLICT (frame_id) DO UPDATE SET
            last_personal_user_id = CASE WHEN ff.last_personal_at IS NULL OR EXCLUDED.last_personal_at >= ff.last_personal_at
                                         THEN EXCLUDED.last_personal_user_id ELSE ff.last_personal_user_id END,
            last_personal_at = GREATEST(ff.last_personal_at, EXCLUDED.last_personal_at),
            updated_at = now();
    ELSIF v_via IN ('immich', 'icloud', 'google_photos') THEN
        INSERT INTO frame_freshness AS ff (frame_id, last_source_at, updated_at)
        VALUES (NEW.frame_id, NEW.received_at, now())
        ON CONFLICT (frame_id) DO UPDATE SET
            last_source_at = GREATEST(ff.last_source_at, EXCLUDED.last_source_at), updated_at = now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER placements_engagement_freshness AFTER INSERT ON placements
    FOR EACH ROW WHEN (NOT NEW.backfill AND NEW.origin IN ('send', 'feed') AND NEW.send_id IS NOT NULL)
    EXECUTE FUNCTION engagement_placement_freshness();

-- +goose StatementBegin
CREATE FUNCTION engagement_post_freshness() RETURNS trigger AS $$
DECLARE
    v_via     text;
    v_outside boolean;
    v_poster  uuid := NEW.poster_user_id;
BEGIN
    SELECT s.via, s.telegram_outside INTO v_via, v_outside FROM sends s WHERE s.id = NEW.send_id;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;
    IF v_via IS NULL OR v_via IN ('email', 'telegram') THEN
        IF v_outside OR EXISTS (SELECT 1 FROM send_items si JOIN items i ON i.id = si.item_id
                                WHERE si.send_id = NEW.send_id AND i.via_email_address_id IS NOT NULL) THEN
            v_poster := NULL;
        END IF;
        INSERT INTO feed_freshness AS ff (feed_id, last_personal_at, last_personal_user_id, updated_at)
        VALUES (NEW.feed_id, NEW.created_at, v_poster, now())
        ON CONFLICT (feed_id) DO UPDATE SET
            last_personal_user_id = CASE WHEN ff.last_personal_at IS NULL OR EXCLUDED.last_personal_at >= ff.last_personal_at
                                         THEN EXCLUDED.last_personal_user_id ELSE ff.last_personal_user_id END,
            last_personal_at = GREATEST(ff.last_personal_at, EXCLUDED.last_personal_at),
            updated_at = now();
    ELSIF v_via IN ('immich', 'icloud', 'google_photos') THEN
        INSERT INTO feed_freshness AS ff (feed_id, last_source_at, updated_at)
        VALUES (NEW.feed_id, NEW.created_at, now())
        ON CONFLICT (feed_id) DO UPDATE SET
            last_source_at = GREATEST(ff.last_source_at, EXCLUDED.last_source_at), updated_at = now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER feed_posts_engagement_freshness AFTER INSERT ON feed_posts
    FOR EACH ROW WHEN (NEW.send_id IS NOT NULL)
    EXECUTE FUNCTION engagement_post_freshness();

-- Posts whose person comments changed since frames were last synced (`comments` change, README §5.20). Rows
-- are consumed by the engagement loop within seconds.
CREATE TABLE engagement_comment_changes (
    post_id    uuid PRIMARY KEY,
    changed_at timestamptz NOT NULL DEFAULT now()
);

-- +goose StatementBegin
CREATE FUNCTION engagement_comment_changed() RETURNS trigger AS $$
BEGIN
    INSERT INTO engagement_comment_changes (post_id, changed_at)
    VALUES (CASE WHEN TG_OP = 'DELETE' THEN OLD.post_id ELSE NEW.post_id END, now())
    ON CONFLICT (post_id) DO UPDATE SET changed_at = now();
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER comments_engagement_changed AFTER INSERT OR UPDATE OR DELETE ON comments
    FOR EACH ROW EXECUTE FUNCTION engagement_comment_changed();

-- +goose Down
DROP TRIGGER comments_engagement_changed ON comments;
DROP FUNCTION engagement_comment_changed();
DROP TABLE engagement_comment_changes;
DROP TRIGGER feed_posts_engagement_freshness ON feed_posts;
DROP FUNCTION engagement_post_freshness();
DROP TRIGGER placements_engagement_freshness ON placements;
DROP FUNCTION engagement_placement_freshness();
