-- +goose Up

-- Family social (README §5.18): frame posts follow their placements. A direct placement of a send by a person
-- (sends.via null, 'email' or 'telegram'; origin 'send', not shared from another frame) makes the frame post of
-- that send and frame, whichever code path placed it (createSend, forwardSend, putMessageOnFrame, email,
-- Telegram); removing the last such placement on that frame removes the post, and placing again brings it back.
-- Placements of photo sources, Google imports, shares, feeds and frame imports never make frame posts.

-- +goose StatementBegin
CREATE FUNCTION social_frame_post_placed() RETURNS trigger AS $$
BEGIN
    IF NEW.origin <> 'send' OR NEW.shared_by_user_id IS NOT NULL OR NEW.send_id IS NULL OR NEW.removed_at IS NOT NULL THEN
        RETURN NEW;
    END IF;
    INSERT INTO frame_posts (id, send_id, frame_id, sender_user_id, created_at)
    SELECT kinframe_uuidv7(clock_timestamp()), s.id, NEW.frame_id, s.sender_user_id, now()
    FROM sends s WHERE s.id = NEW.send_id AND (s.via IS NULL OR s.via IN ('email', 'telegram'))
    ON CONFLICT (send_id, frame_id) DO UPDATE SET removed_at = NULL WHERE frame_posts.removed_at IS NOT NULL;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER placements_social_frame_post AFTER INSERT ON placements
    FOR EACH ROW EXECUTE FUNCTION social_frame_post_placed();

-- +goose StatementBegin
CREATE FUNCTION social_frame_post_unplaced() RETURNS trigger AS $$
BEGIN
    IF NEW.send_id IS NULL OR NEW.removed_at IS NULL OR OLD.removed_at IS NOT NULL THEN
        RETURN NEW;
    END IF;
    UPDATE frame_posts fp SET removed_at = now()
    WHERE fp.send_id = NEW.send_id AND fp.frame_id = NEW.frame_id AND fp.removed_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM placements p
                      WHERE p.send_id = NEW.send_id AND p.frame_id = NEW.frame_id AND p.removed_at IS NULL
                        AND p.origin = 'send' AND p.shared_by_user_id IS NULL);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- +goose StatementEnd
CREATE TRIGGER placements_social_frame_post_removed AFTER UPDATE OF removed_at ON placements
    FOR EACH ROW EXECUTE FUNCTION social_frame_post_unplaced();

-- Frame posts whose placements were all removed after the 00022 backfill ran (none in practice; for safety).
UPDATE frame_posts fp SET removed_at = now()
WHERE fp.removed_at IS NULL AND NOT EXISTS (
    SELECT 1 FROM placements p WHERE p.send_id = fp.send_id AND p.frame_id = fp.frame_id AND p.removed_at IS NULL
      AND p.origin = 'send' AND p.shared_by_user_id IS NULL);

-- +goose Down
DROP TRIGGER placements_social_frame_post_removed ON placements;
DROP TRIGGER placements_social_frame_post ON placements;
DROP FUNCTION social_frame_post_unplaced(), social_frame_post_placed();
