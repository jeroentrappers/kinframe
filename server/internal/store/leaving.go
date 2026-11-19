package store

// Leaving a family (docs/spec/leaving.md, README §5.21): takings. A taking takes a person's own photos away from a
// family (their live posts, and the posts' placements on every subscribed frame), from frames (their direct
// placements), or from everything (account deletion). Taken-away placements are removed (removed_at, so every
// other path treats them as gone and the change feed renders placement.remove) and wait in the frame's trash for
// 30 days (taken_away_*); a keep request can bring them back, the purge ends the wait.

import (
	"context"
	"sort"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Taking is a takings row with whether its person's account is gone.
type Taking struct {
	ID              uuid.UUID  `db:"id"`
	UserID          *uuid.UUID `db:"user_id"`
	UserName        string     `db:"user_name"`
	Scope           string     `db:"scope"`
	FeedID          *uuid.UUID `db:"feed_id"`
	FrameID         *uuid.UUID `db:"frame_id"`
	ItemCount       int        `db:"item_count"`
	CommentsDeleted int        `db:"comments_deleted"`
	CreatedAt       time.Time  `db:"created_at"`
	Until           time.Time  `db:"until"`
	PurgedAt        *time.Time `db:"purged_at"`
	// UserDeleted: the account was deleted (or purged); nobody can be asked to keep.
	UserDeleted   bool    `db:"user_deleted"`
	UserAvatarKey *string `db:"user_avatar_key"`
}

const takingSelect = `SELECT t.id, t.user_id, t.user_name, t.scope, t.feed_id, t.frame_id, t.item_count, t.comments_deleted,
	t.created_at, t.until, t.purged_at, COALESCE(u.deleted_at IS NOT NULL, true) AS user_deleted, u.avatar_key AS user_avatar_key
	FROM takings t LEFT JOIN users u ON u.id = t.user_id`

// GetTaking returns a taking.
func (s *Store) GetTaking(ctx context.Context, q db.DBTX, id uuid.UUID) (*Taking, error) {
	return one[Taking](q.Query(ctx, takingSelect+` WHERE t.id = $1`, id))
}

// TakeAwaySpec says what one taking covers. Only the person's own items: their live posts (poster) and their
// placements (items they sent, or placements they shared) that no family delivery holds.
type TakeAwaySpec struct {
	UserID   uuid.UUID
	UserName string
	Scope    string     // feed, frame or account
	FeedID   *uuid.UUID // scope feed: the family whose posts go
	// FrameIDs: direct placements on these frames go (scope frame: the frame; scope feed: frames left with the family).
	FrameIDs []uuid.UUID
	// All: account deletion, every family and every frame.
	All        bool
	Now, Until time.Time
}

// TakenFrame is a frame that lost placements to a taking.
type TakenFrame struct {
	FrameID      uuid.UUID
	Name         string
	PlacementIDs []uuid.UUID
	Items        int
}

// TakenFeed is a family that lost posts to a taking.
type TakenFeed struct {
	FeedID  uuid.UUID
	Name    string
	PostIDs []uuid.UUID
	Items   int
}

// TakeAwayOutcome is what a taking did; Taking is nil when nothing was live.
type TakeAwayOutcome struct {
	Taking *Taking
	Items  int
	Frames []TakenFrame
	Feeds  []TakenFeed
}

type postItemPair struct {
	PostID uuid.UUID `db:"post_id"`
	ItemID uuid.UUID `db:"item_id"`
	FeedID uuid.UUID `db:"feed_id"`
}

type cutDelivery struct {
	SubscriptionID uuid.UUID `db:"subscription_id"`
	PostID         uuid.UUID `db:"post_id"`
	ItemID         uuid.UUID `db:"item_id"`
	PlacementID    uuid.UUID `db:"placement_id"`
	FrameID        uuid.UUID `db:"frame_id"`
}

// directPlacementSQL: live placements of the person ($1) on live frames, their own (sent or shared by them) and held
// by no family delivery. $2 = every frame, $3 = these frames.
const directPlacementSQL = `SELECT p.id FROM placements p JOIN items i ON i.id = p.item_id
	JOIN frames f ON f.id = p.frame_id AND f.deleted_at IS NULL
	WHERE p.removed_at IS NULL AND (i.sender_user_id = $1 OR p.shared_by_user_id = $1)
	  AND ($2 OR p.frame_id = ANY($3))
	  AND p.origin_remote_follow_id IS NULL
	  AND NOT EXISTS (SELECT 1 FROM feed_deliveries d WHERE d.placement_id = p.id AND d.state = 'placed')`

// TakeAway runs one taking (README §5.21 "Taking away"). Idempotent: what is already taken away stays out of it.
func (tx *Tx) TakeAway(ctx context.Context, sp TakeAwaySpec) (*TakeAwayOutcome, error) {
	out := &TakeAwayOutcome{}
	frames := sp.FrameIDs
	if frames == nil {
		frames = []uuid.UUID{}
	}
	// The person's live post items in scope.
	var pairs []postItemPair
	if sp.FeedID != nil || sp.All {
		var err error
		pairs, err = many[postItemPair](tx.Query(ctx, `SELECT pi.post_id, pi.item_id, p.feed_id FROM feed_post_items pi
			JOIN feed_posts p ON p.id = pi.post_id JOIN feeds fd ON fd.id = p.feed_id AND fd.deleted_at IS NULL
			WHERE p.poster_user_id = $1 AND p.removed_at IS NULL AND pi.removed_at IS NULL AND ($2::uuid IS NULL OR p.feed_id = $2)
			ORDER BY p.id, pi.position`, sp.UserID, sp.FeedID))
		if err != nil {
			return nil, err
		}
	}
	postIDs := uniqueIDs(pairs, func(p postItemPair) uuid.UUID { return p.PostID })
	// The frames involved, locked in a stable order (as FeedPutMany) before anything changes.
	lockIDs, err := uuids(tx.Query(ctx, `
		SELECT DISTINCT s.frame_id FROM feed_deliveries d JOIN subscriptions s ON s.id = d.subscription_id
		WHERE d.post_id = ANY($4) AND d.state = 'placed' AND d.placement_id IS NOT NULL
		UNION
		SELECT DISTINCT p.frame_id FROM placements p JOIN items i ON i.id = p.item_id
		WHERE p.removed_at IS NULL AND (i.sender_user_id = $1 OR p.shared_by_user_id = $1) AND ($2 OR p.frame_id = ANY($3))`,
		sp.UserID, sp.All, frames, postIDs))
	if err != nil {
		return nil, err
	}
	if err := tx.lockFrames(ctx, lockIDs); err != nil {
		return nil, err
	}
	direct, err := uuids(tx.Query(ctx, directPlacementSQL, sp.UserID, sp.All, frames))
	if err != nil {
		return nil, err
	}
	if len(pairs) == 0 && len(direct) == 0 {
		return out, nil
	}
	t := &Taking{ID: NewID(), UserID: &sp.UserID, UserName: sp.UserName, Scope: sp.Scope, CreatedAt: sp.Now, Until: sp.Until}
	switch sp.Scope {
	case "feed":
		t.FeedID = sp.FeedID
	case "frame":
		if len(sp.FrameIDs) == 1 {
			t.FrameID = &sp.FrameIDs[0]
		}
	}
	if _, err := tx.Exec(ctx, `INSERT INTO takings (id, user_id, user_name, scope, feed_id, frame_id, created_at, until)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`, t.ID, t.UserID, t.UserName, t.Scope, t.FeedID, t.FrameID, t.CreatedAt, t.Until); err != nil {
		return nil, err
	}
	var cut []cutDelivery
	if len(postIDs) > 0 {
		// The family deliveries of those post items are cut (kept aside for a restore).
		cut, err = many[cutDelivery](tx.Query(ctx, `
			WITH gone AS (
				DELETE FROM feed_deliveries d USING feed_post_items pi
				WHERE pi.post_id = d.post_id AND pi.item_id = d.item_id AND pi.removed_at IS NULL
				  AND d.post_id = ANY($2) AND d.state = 'placed' AND d.placement_id IS NOT NULL
				RETURNING d.subscription_id, d.post_id, d.item_id, d.placement_id
			), kept AS (
				INSERT INTO taking_deliveries (taking_id, subscription_id, post_id, item_id, placement_id)
				SELECT $1, subscription_id, post_id, item_id, placement_id FROM gone RETURNING placement_id
			)
			SELECT g.subscription_id, g.post_id, g.item_id, g.placement_id, s.frame_id FROM gone g
			JOIN subscriptions s ON s.id = g.subscription_id ORDER BY s.frame_id, g.placement_id`, t.ID, postIDs))
		if err != nil {
			return nil, err
		}
		if _, err := tx.Exec(ctx, `UPDATE feed_post_items SET removed_at = $3, taking_id = $2
			WHERE post_id = ANY($1) AND removed_at IS NULL`, postIDs, t.ID, sp.Now); err != nil {
			return nil, err
		}
		if _, err := tx.Exec(ctx, `UPDATE feed_posts SET removed_at = $3, removed_by = $4, taking_id = $2
			WHERE id = ANY($1) AND removed_at IS NULL`, postIDs, t.ID, sp.Now, sp.UserID); err != nil {
			return nil, err
		}
	}
	// Placements whose only holds were the cut deliveries go too; others (held directly or by another family)
	// stay and are re-emitted (their feed_ids changed).
	cutPlacements := uniqueIDs(cut, func(c cutDelivery) uuid.UUID { return c.PlacementID })
	orphans, err := uuids(tx.Query(ctx, `SELECT p.id FROM placements p WHERE p.id = ANY($1) AND p.removed_at IS NULL
		AND NOT p.held_directly AND NOT EXISTS (SELECT 1 FROM feed_deliveries d WHERE d.placement_id = p.id AND d.state = 'placed')`,
		cutPlacements))
	if err != nil {
		return nil, err
	}
	// After the cut, more of the person's placements on the scoped frames may be held by nothing but them.
	if len(cut) > 0 && (sp.All || len(frames) > 0) {
		if direct, err = uuids(tx.Query(ctx, directPlacementSQL, sp.UserID, sp.All, frames)); err != nil {
			return nil, err
		}
	}
	take := map[uuid.UUID]bool{}
	for _, id := range orphans {
		take[id] = true
	}
	for _, id := range direct {
		take[id] = true
	}
	var stays []uuid.UUID
	for _, id := range cutPlacements {
		if !take[id] {
			stays = append(stays, id)
		}
	}
	if err := tx.recomputeSuspended(ctx, stays); err != nil {
		return nil, err
	}
	stayRows, err := many[framePlacement](tx.Query(ctx, `SELECT id, frame_id, item_id FROM placements WHERE id = ANY($1) AND removed_at IS NULL ORDER BY frame_id`, stays))
	if err != nil {
		return nil, err
	}
	for frameID, ids := range byFrame(stayRows) {
		if err := tx.FeedPutEntities(ctx, frameID, FeedPlacement, ids, false); err != nil {
			return nil, err
		}
	}
	takeIDs := make([]uuid.UUID, 0, len(take))
	for id := range take {
		takeIDs = append(takeIDs, id)
	}
	taken, err := many[framePlacement](tx.Query(ctx, `UPDATE placements SET removed_at = $2, taken_away_at = $2, taken_away_until = $3,
		taken_away_by = $4, taking_id = $5 WHERE id = ANY($1) AND removed_at IS NULL RETURNING id, frame_id, item_id`,
		takeIDs, sp.Now, sp.Until, sp.UserID, t.ID))
	if err != nil {
		return nil, err
	}
	sort.Slice(taken, func(i, j int) bool {
		if taken[i].FrameID != taken[j].FrameID {
			return taken[i].FrameID.String() < taken[j].FrameID.String()
		}
		return taken[i].ID.String() < taken[j].ID.String()
	})
	perFrame := byFrame(taken)
	frameOrder := make([]uuid.UUID, 0, len(perFrame))
	for id := range perFrame {
		frameOrder = append(frameOrder, id)
	}
	sort.Slice(frameOrder, func(i, j int) bool { return frameOrder[i].String() < frameOrder[j].String() })
	for _, frameID := range frameOrder {
		if err := tx.FeedPutEntities(ctx, frameID, FeedPlacement, perFrame[frameID], true); err != nil {
			return nil, err
		}
	}
	// The items the taking covers (keep requests pick among them).
	items := map[uuid.UUID]bool{}
	for _, p := range pairs {
		items[p.ItemID] = true
	}
	for _, p := range taken {
		items[p.ItemID] = true
	}
	itemIDs := make([]uuid.UUID, 0, len(items))
	for id := range items {
		itemIDs = append(itemIDs, id)
	}
	if _, err := tx.Exec(ctx, `INSERT INTO taking_items (taking_id, item_id) SELECT $1, unnest($2::uuid[]) ON CONFLICT DO NOTHING`, t.ID, itemIDs); err != nil {
		return nil, err
	}
	t.ItemCount = len(itemIDs)
	if _, err := tx.Exec(ctx, `UPDATE takings SET item_count = $2 WHERE id = $1`, t.ID, t.ItemCount); err != nil {
		return nil, err
	}
	out.Taking, out.Items = t, len(itemIDs)
	names, err := tx.frameNames(ctx, frameOrder)
	if err != nil {
		return nil, err
	}
	for _, frameID := range frameOrder {
		ids := perFrame[frameID]
		n := map[uuid.UUID]bool{}
		for _, p := range taken {
			if p.FrameID == frameID {
				n[p.ItemID] = true
			}
		}
		out.Frames = append(out.Frames, TakenFrame{FrameID: frameID, Name: names[frameID], PlacementIDs: ids, Items: len(n)})
	}
	feeds := map[uuid.UUID]*TakenFeed{}
	var feedOrder []uuid.UUID
	for _, p := range pairs {
		f := feeds[p.FeedID]
		if f == nil {
			f = &TakenFeed{FeedID: p.FeedID}
			feeds[p.FeedID] = f
			feedOrder = append(feedOrder, p.FeedID)
		}
		if len(f.PostIDs) == 0 || f.PostIDs[len(f.PostIDs)-1] != p.PostID {
			f.PostIDs = append(f.PostIDs, p.PostID)
		}
		f.Items++
	}
	for _, id := range feedOrder {
		var name string
		if err := tx.QueryRow(ctx, `SELECT name FROM feeds WHERE id = $1`, id).Scan(&name); err != nil {
			return nil, err
		}
		feeds[id].Name = name
		out.Feeds = append(out.Feeds, *feeds[id])
	}
	return out, nil
}

// SetTakingComments records how many comments the taking deleted.
func (tx *Tx) SetTakingComments(ctx context.Context, takingID uuid.UUID, n int) error {
	_, err := tx.Exec(ctx, `UPDATE takings SET comments_deleted = $2 WHERE id = $1`, takingID, n)
	return err
}

type framePlacement struct {
	ID      uuid.UUID `db:"id"`
	FrameID uuid.UUID `db:"frame_id"`
	ItemID  uuid.UUID `db:"item_id"`
}

func byFrame(rows []framePlacement) map[uuid.UUID][]uuid.UUID {
	out := map[uuid.UUID][]uuid.UUID{}
	for _, r := range rows {
		out[r.FrameID] = append(out[r.FrameID], r.ID)
	}
	return out
}

func uniqueIDs[T any](xs []T, key func(T) uuid.UUID) []uuid.UUID {
	seen := map[uuid.UUID]bool{}
	out := []uuid.UUID{}
	for _, x := range xs {
		k := key(x)
		if !seen[k] {
			seen[k] = true
			out = append(out, k)
		}
	}
	return out
}

// lockFrames takes the frame row locks in id order (the order FeedPutMany uses).
func (tx *Tx) lockFrames(ctx context.Context, ids []uuid.UUID) error {
	if len(ids) == 0 {
		return nil
	}
	_, err := tx.Exec(ctx, `SELECT id FROM frames WHERE id = ANY($1) ORDER BY id FOR UPDATE`, ids)
	return err
}

func (tx *Tx) frameNames(ctx context.Context, ids []uuid.UUID) (map[uuid.UUID]string, error) {
	rows, err := many[NamedID](tx.Query(ctx, `SELECT id, name FROM frames WHERE id = ANY($1)`, ids))
	if err != nil {
		return nil, err
	}
	out := map[uuid.UUID]string{}
	for _, r := range rows {
		out[r.ID] = r.Name
	}
	return out, nil
}

// ---------------------------------------------------------------- restores (keep requests)

// RestoredFrame is a frame whose placements came back.
type RestoredFrame struct {
	FrameID      uuid.UUID
	PlacementIDs []uuid.UUID
}

// RestoreOnFrame brings back the taking's placements of itemIDs on a frame (a frame's keep request): they are on
// the frame again as before, held directly (the family post may stay away), emitted as history (backfill), and
// their frame posts come back. Items the frame holds again in the meantime are skipped.
func (tx *Tx) RestoreOnFrame(ctx context.Context, takingID, frameID uuid.UUID, itemIDs []uuid.UUID) ([]uuid.UUID, error) {
	if _, err := tx.LockFrame(ctx, frameID); err != nil {
		if err == ErrNotFound {
			return nil, nil
		}
		return nil, err
	}
	ids, err := uuids(tx.Query(ctx, `UPDATE placements p SET removed_at = NULL, taken_away_at = NULL, taken_away_until = NULL,
			taken_away_by = NULL, backfill = true, held_directly = true, feed_suspended = false
		WHERE p.frame_id = $1 AND p.taking_id = $2 AND p.taken_away_at IS NOT NULL AND p.item_id = ANY($3)
		  AND NOT EXISTS (SELECT 1 FROM placements q WHERE q.frame_id = p.frame_id AND q.item_id = p.item_id AND q.removed_at IS NULL)
		RETURNING p.id`, frameID, takingID, itemIDs))
	if err != nil {
		return nil, err
	}
	if err := tx.reviveFramePosts(ctx, ids); err != nil {
		return nil, err
	}
	return ids, tx.FeedPutEntities(ctx, frameID, FeedPlacement, ids, false)
}

// reviveFramePosts brings back the frame posts of restored direct send placements (00025's trigger only revives
// on insert).
func (tx *Tx) reviveFramePosts(ctx context.Context, placementIDs []uuid.UUID) error {
	if len(placementIDs) == 0 {
		return nil
	}
	_, err := tx.Exec(ctx, `UPDATE frame_posts fp SET removed_at = NULL WHERE fp.removed_at IS NOT NULL
		AND (fp.send_id, fp.frame_id) IN (SELECT p.send_id, p.frame_id FROM placements p
			WHERE p.id = ANY($1) AND p.origin = 'send' AND p.shared_by_user_id IS NULL AND p.send_id IS NOT NULL)`, placementIDs)
	return err
}

// RestoreInFeed brings back the taking's post items of itemIDs in a family (a family's keep request): the posts
// are in the timeline again, and their deliveries on frames whose subscriptions are still live come back with
// their placements (emitted as history). Posts whose send has been posted to the family again since are skipped.
func (tx *Tx) RestoreInFeed(ctx context.Context, takingID, feedID uuid.UUID, itemIDs []uuid.UUID) ([]uuid.UUID, []RestoredFrame, error) {
	posts, err := uuids(tx.Query(ctx, `SELECT DISTINCT p.id FROM feed_posts p JOIN feed_post_items pi ON pi.post_id = p.id
		WHERE p.feed_id = $1 AND pi.taking_id = $2 AND pi.removed_at IS NOT NULL AND pi.item_id = ANY($3)
		  AND (p.removed_at IS NULL OR p.taking_id = $2)
		  AND NOT EXISTS (SELECT 1 FROM feed_posts q WHERE q.feed_id = p.feed_id AND q.send_id = p.send_id
			AND q.removed_at IS NULL AND q.id <> p.id)
		ORDER BY p.id`, feedID, takingID, itemIDs))
	if err != nil || len(posts) == 0 {
		return nil, nil, err
	}
	type restoredItem struct {
		PostID uuid.UUID `db:"post_id"`
		ItemID uuid.UUID `db:"item_id"`
	}
	restored, err := many[restoredItem](tx.Query(ctx, `UPDATE feed_post_items SET removed_at = NULL
		WHERE post_id = ANY($1) AND taking_id = $2 AND removed_at IS NOT NULL AND item_id = ANY($3) RETURNING post_id, item_id`,
		posts, takingID, itemIDs))
	if err != nil {
		return nil, nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE feed_posts SET removed_at = NULL, removed_by = NULL WHERE id = ANY($1) AND taking_id = $2
		AND removed_at IS NOT NULL`, posts, takingID); err != nil {
		return nil, nil, err
	}
	// The deliveries to put back, on frames whose subscriptions are still live.
	tds, err := many[cutDelivery](tx.Query(ctx, `SELECT td.subscription_id, td.post_id, td.item_id, td.placement_id, s.frame_id
		FROM taking_deliveries td JOIN subscriptions s ON s.id = td.subscription_id AND s.status IN ('active', 'paused')
		JOIN frames f ON f.id = s.frame_id AND f.deleted_at IS NULL
		WHERE td.taking_id = $1 AND td.post_id = ANY($2) AND td.item_id = ANY($3) AND td.placement_id IS NOT NULL
		ORDER BY s.frame_id, td.placement_id`, takingID, posts, itemIDs))
	if err != nil {
		return nil, nil, err
	}
	frameIDs := uniqueIDs(tds, func(c cutDelivery) uuid.UUID { return c.FrameID })
	if err := tx.lockFrames(ctx, frameIDs); err != nil {
		return nil, nil, err
	}
	back := map[uuid.UUID][]uuid.UUID{} // frame -> placements emitted again
	var touched []uuid.UUID
	for _, d := range tds {
		var pid *uuid.UUID
		var takenHere bool
		err := tx.QueryRow(ctx, `SELECT id, COALESCE(taken_away_at IS NOT NULL AND taking_id = $3, false) FROM placements
			WHERE id = $1 AND frame_id = $2 AND (removed_at IS NULL OR (taken_away_at IS NOT NULL AND taking_id = $3)) FOR UPDATE`,
			d.PlacementID, d.FrameID, takingID).Scan(&pid, &takenHere)
		if db.NotFound(err) {
			// Purged early or removed since: the frame holds another live copy, or none.
			err = tx.QueryRow(ctx, `SELECT id FROM placements WHERE frame_id = $1 AND item_id = $2 AND removed_at IS NULL FOR UPDATE`,
				d.FrameID, d.ItemID).Scan(&pid)
			if db.NotFound(err) {
				continue
			}
		}
		if err != nil {
			return nil, nil, err
		}
		if takenHere {
			var live bool
			if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM placements WHERE frame_id = $1 AND item_id = $2 AND removed_at IS NULL)`,
				d.FrameID, d.ItemID).Scan(&live); err != nil {
				return nil, nil, err
			}
			if live {
				continue
			}
			if _, err := tx.Exec(ctx, `UPDATE placements SET removed_at = NULL, taken_away_at = NULL, taken_away_until = NULL,
				taken_away_by = NULL, backfill = true WHERE id = $1`, *pid); err != nil {
				return nil, nil, err
			}
		}
		if _, err := tx.Exec(ctx, `INSERT INTO feed_deliveries (subscription_id, post_id, item_id, placement_id, state)
			VALUES ($1, $2, $3, $4, 'placed') ON CONFLICT DO NOTHING`, d.SubscriptionID, d.PostID, d.ItemID, *pid); err != nil {
			return nil, nil, err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM taking_deliveries WHERE taking_id = $1 AND subscription_id = $2 AND post_id = $3 AND item_id = $4`,
			takingID, d.SubscriptionID, d.PostID, d.ItemID); err != nil {
			return nil, nil, err
		}
		touched = append(touched, *pid)
		back[d.FrameID] = appendID(back[d.FrameID], *pid)
	}
	if err := tx.recomputeSuspended(ctx, touched); err != nil {
		return nil, nil, err
	}
	var frames []RestoredFrame
	for _, frameID := range frameIDs {
		ids := back[frameID]
		if len(ids) == 0 {
			continue
		}
		if err := tx.FeedPutEntities(ctx, frameID, FeedPlacement, ids, false); err != nil {
			return nil, nil, err
		}
		frames = append(frames, RestoredFrame{FrameID: frameID, PlacementIDs: ids})
	}
	out := uniqueIDs(restored, func(r restoredItem) uuid.UUID { return r.PostID })
	return out, frames, nil
}

func appendID(xs []uuid.UUID, id uuid.UUID) []uuid.UUID {
	for _, x := range xs {
		if x == id {
			return xs
		}
	}
	return append(xs, id)
}

// ---------------------------------------------------------------- the purge

// DueTakings lists takings whose 30 days are over and that were not purged yet.
func (s *Store) DueTakings(ctx context.Context, now time.Time, limit int) ([]Taking, error) {
	return many[Taking](s.Pool.Query(ctx, takingSelect+` WHERE t.purged_at IS NULL AND t.until <= $1 ORDER BY t.until LIMIT $2`, now, limit))
}

// PurgeTaking ends a taking's wait: placements still taken away leave the frames' trash for good (they stay
// removed; the media lifecycle follows), the kept-aside deliveries go, and posts still taken away lose their
// people's reactions. Returns the purged placements per frame.
func (tx *Tx) PurgeTaking(ctx context.Context, takingID uuid.UUID, now time.Time) ([]RestoredFrame, error) {
	rows, err := many[framePlacement](tx.Query(ctx, `UPDATE placements SET taken_away_at = NULL, taken_away_until = NULL
		WHERE taking_id = $1 AND taken_away_at IS NOT NULL RETURNING id, frame_id, item_id`, takingID))
	if err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM taking_deliveries WHERE taking_id = $1`, takingID); err != nil {
		return nil, err
	}
	posts, err := uuids(tx.Query(ctx, `SELECT id FROM feed_posts WHERE taking_id = $1 AND removed_at IS NOT NULL`, takingID))
	if err != nil {
		return nil, err
	}
	if len(posts) > 0 {
		if err := tx.DeletePostReactions(ctx, posts); err != nil {
			return nil, err
		}
	}
	if _, err := tx.Exec(ctx, `UPDATE takings SET purged_at = $2 WHERE id = $1`, takingID, now); err != nil {
		return nil, err
	}
	var out []RestoredFrame
	per := byFrame(rows)
	for frameID, ids := range per {
		out = append(out, RestoredFrame{FrameID: frameID, PlacementIDs: ids})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].FrameID.String() < out[j].FrameID.String() })
	return out, nil
}

// PurgeTakenAwayPlacement deletes one taken-away placement early (a frame manager's purge from the trash): it never
// comes back, also not with a family keep request. Returns the taking, or nil when the placement isn't taken away.
func (tx *Tx) PurgeTakenAwayPlacement(ctx context.Context, frameID, placementID uuid.UUID) (*uuid.UUID, error) {
	var taking *uuid.UUID
	err := tx.QueryRow(ctx, `UPDATE placements SET taken_away_at = NULL, taken_away_until = NULL
		WHERE id = $1 AND frame_id = $2 AND taken_away_at IS NOT NULL RETURNING taking_id`, placementID, frameID).Scan(&taking)
	if db.NotFound(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	// The family's deliveries become tombstones, so a later restore or backfill never places it here again.
	if _, err := tx.Exec(ctx, `INSERT INTO feed_deliveries (subscription_id, post_id, item_id, state)
		SELECT subscription_id, post_id, item_id, 'declined' FROM taking_deliveries WHERE placement_id = $1
		ON CONFLICT DO NOTHING`, placementID); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM taking_deliveries WHERE placement_id = $1`, placementID); err != nil {
		return nil, err
	}
	return taking, nil
}

// ---------------------------------------------------------------- reads

// TakenAwayPlacement is what Placement.taken_away shows.
type TakenAwayPlacement struct {
	PlacementID   uuid.UUID  `db:"placement_id"`
	TakingID      uuid.UUID  `db:"taking_id"`
	ByUserID      *uuid.UUID `db:"by_user_id"`
	ByName        string     `db:"by_name"`
	At            time.Time  `db:"at"`
	Until         time.Time  `db:"until"`
	RequestStatus *string    `db:"request_status"`
}

// TakenAwayOf loads the taken-away state of placements of a frame.
func (s *Store) TakenAwayOf(ctx context.Context, frameID uuid.UUID, placementIDs []uuid.UUID) (map[uuid.UUID]TakenAwayPlacement, error) {
	rows, err := many[TakenAwayPlacement](s.Pool.Query(ctx, `SELECT p.id AS placement_id, p.taking_id,
			CASE WHEN u.deleted_at IS NULL THEN t.user_id END AS by_user_id, t.user_name AS by_name,
			p.taken_away_at AS at, p.taken_away_until AS until,
			(SELECT k.status FROM keep_requests k WHERE k.taking_id = t.id AND k.frame_id = p.frame_id) AS request_status
		FROM placements p JOIN takings t ON t.id = p.taking_id LEFT JOIN users u ON u.id = t.user_id
		WHERE p.frame_id = $1 AND p.id = ANY($2) AND p.taken_away_at IS NOT NULL`, frameID, placementIDs))
	if err != nil {
		return nil, err
	}
	out := make(map[uuid.UUID]TakenAwayPlacement, len(rows))
	for _, r := range rows {
		out[r.PlacementID] = r
	}
	return out, nil
}

// CountTakenAway counts a frame's placements waiting in its trash after a taking.
func (s *Store) CountTakenAway(ctx context.Context, frameID uuid.UUID) (int, error) {
	var n int
	err := s.Pool.QueryRow(ctx, `SELECT count(*) FROM placements WHERE frame_id = $1 AND taken_away_at IS NOT NULL`, frameID).Scan(&n)
	return n, err
}

// TakenAwayGroup is one running taking in a scope (frame or family) with how much of it is still away there.
type TakenAwayGroup struct {
	Taking
	Count int `db:"count"`
}

// FrameTakenAwayGroups lists the takings still waiting in a frame's trash, newest first.
func (s *Store) FrameTakenAwayGroups(ctx context.Context, frameID uuid.UUID) ([]TakenAwayGroup, error) {
	return many[TakenAwayGroup](s.Pool.Query(ctx, `SELECT x.*, c.count FROM (`+takingSelect+`) x
		JOIN (SELECT taking_id, count(*)::int AS count FROM placements WHERE frame_id = $1 AND taken_away_at IS NOT NULL GROUP BY taking_id) c
		ON c.taking_id = x.id WHERE x.purged_at IS NULL ORDER BY x.created_at DESC, x.id DESC`, frameID))
}

// FeedTakenAwayGroups lists the takings whose posts are still away from a family, newest first (counted in items).
func (s *Store) FeedTakenAwayGroups(ctx context.Context, feedID uuid.UUID) ([]TakenAwayGroup, error) {
	return many[TakenAwayGroup](s.Pool.Query(ctx, `SELECT x.*, c.count FROM (`+takingSelect+`) x
		JOIN (SELECT pi.taking_id, count(DISTINCT pi.item_id)::int AS count FROM feed_post_items pi
			JOIN feed_posts p ON p.id = pi.post_id AND p.feed_id = $1
			WHERE pi.taking_id IS NOT NULL AND pi.removed_at IS NOT NULL GROUP BY pi.taking_id) c
		ON c.taking_id = x.id WHERE x.purged_at IS NULL ORDER BY x.created_at DESC, x.id DESC`, feedID))
}

// TakenItemsOnFrame lists the items of a taking still taken away from a frame (oldest placement first).
func (s *Store) TakenItemsOnFrame(ctx context.Context, q db.DBTX, takingID, frameID uuid.UUID) ([]uuid.UUID, error) {
	return uuids(q.Query(ctx, `SELECT item_id FROM placements WHERE taking_id = $1 AND frame_id = $2 AND taken_away_at IS NOT NULL
		ORDER BY received_at, id`, takingID, frameID))
}

// TakenItemsInFeed lists the items of a taking still taken away from a family.
func (s *Store) TakenItemsInFeed(ctx context.Context, q db.DBTX, takingID, feedID uuid.UUID) ([]uuid.UUID, error) {
	return uuids(q.Query(ctx, `SELECT pi.item_id FROM feed_post_items pi JOIN feed_posts p ON p.id = pi.post_id AND p.feed_id = $2
		WHERE pi.taking_id = $1 AND pi.removed_at IS NOT NULL GROUP BY pi.item_id ORDER BY min(p.created_at), min(pi.position), pi.item_id`,
		takingID, feedID))
}

// ThumbKeys returns the 256 px thumbnail keys of items, in the given order (missing ones skipped).
func (s *Store) ThumbKeys(ctx context.Context, itemIDs []uuid.UUID) ([]string, error) {
	type row struct {
		ID  uuid.UUID `db:"id"`
		Key *string   `db:"thumb256_key"`
	}
	rows, err := many[row](s.Pool.Query(ctx, `SELECT id, thumb256_key FROM items WHERE id = ANY($1)`, itemIDs))
	if err != nil {
		return nil, err
	}
	keys := map[uuid.UUID]string{}
	for _, r := range rows {
		if r.Key != nil {
			keys[r.ID] = *r.Key
		}
	}
	out := []string{}
	for _, id := range itemIDs {
		if k, ok := keys[id]; ok {
			out = append(out, k)
		}
	}
	return out, nil
}

// LeaveCounts is what the leave sheet counts for a person in a scope.
type LeaveCounts struct {
	Photos           int `db:"photos"`
	Comments         int `db:"comments"`
	AlreadyTakenAway int `db:"already_taken_away"`
}

// FeedLeaveCounts counts a person's live items and comments in a family and what is still taken away from it.
func (s *Store) FeedLeaveCounts(ctx context.Context, feedID, userID uuid.UUID) (*LeaveCounts, error) {
	return one[LeaveCounts](s.Pool.Query(ctx, `SELECT
		(SELECT count(DISTINCT pi.item_id) FROM feed_post_items pi JOIN feed_posts p ON p.id = pi.post_id
			WHERE p.feed_id = $1 AND p.poster_user_id = $2 AND p.removed_at IS NULL AND pi.removed_at IS NULL)::int AS photos,
		(SELECT count(*) FROM comments c JOIN feed_posts p ON p.id = c.post_id
			WHERE p.feed_id = $1 AND c.author_user_id = $2 AND c.kind = 'person' AND c.deleted_at IS NULL)::int AS comments,
		(SELECT count(DISTINCT pi.item_id) FROM feed_post_items pi JOIN feed_posts p ON p.id = pi.post_id
			JOIN takings t ON t.id = pi.taking_id AND t.purged_at IS NULL
			WHERE p.feed_id = $1 AND p.poster_user_id = $2 AND pi.removed_at IS NOT NULL)::int AS already_taken_away`, feedID, userID))
}

// FrameLeaveCounts counts a person's direct placements on a frame and what is still taken away from it.
func (s *Store) FrameLeaveCounts(ctx context.Context, q db.DBTX, frameID, userID uuid.UUID) (*LeaveCounts, error) {
	return one[LeaveCounts](q.Query(ctx, `SELECT (SELECT count(*) FROM (`+directPlacementSQL+`) d)::int AS photos, 0 AS comments,
		(SELECT count(*) FROM placements p WHERE p.frame_id = $3[1] AND p.taken_away_at IS NOT NULL AND p.taken_away_by = $1)::int AS already_taken_away`,
		userID, false, []uuid.UUID{frameID}))
}

// LeaveFrame is a frame of the person that subscribes to a family (the "Leave this frame too" checkboxes).
type LeaveFrame struct {
	FrameID   uuid.UUID `db:"frame_id"`
	FrameName string    `db:"frame_name"`
	Role      string    `db:"role"`
	Active    bool      `db:"active"`
}

// FeedLeaveFrames lists frames the person is a member of that have a live subscription to the family.
func (s *Store) FeedLeaveFrames(ctx context.Context, q db.DBTX, feedID, userID uuid.UUID) ([]LeaveFrame, error) {
	return many[LeaveFrame](q.Query(ctx, `SELECT f.id AS frame_id, f.name AS frame_name, m.role, bool_or(s.status = 'active') AS active
		FROM subscriptions s JOIN frames f ON f.id = s.frame_id AND f.deleted_at IS NULL
		JOIN memberships m ON m.frame_id = f.id AND m.user_id = $2
		WHERE s.feed_id = $1 AND s.status IN ('pending', 'active', 'paused')
		GROUP BY f.id, f.name, m.role ORDER BY lower(f.name), f.id`, feedID, userID))
}

// FormerPlace is a family or frame the person no longer belongs to but still has live photos in.
type FormerPlace struct {
	ID     uuid.UUID `db:"id"`
	Name   string    `db:"name"`
	Photos int       `db:"photos"`
}

// FormerFeeds lists live families the person is not a member of where their posts are still live.
func (s *Store) FormerFeeds(ctx context.Context, userID uuid.UUID) ([]FormerPlace, error) {
	return many[FormerPlace](s.Pool.Query(ctx, `SELECT fd.id, fd.name, count(DISTINCT pi.item_id)::int AS photos
		FROM feed_posts p JOIN feeds fd ON fd.id = p.feed_id AND fd.deleted_at IS NULL
		JOIN feed_post_items pi ON pi.post_id = p.id AND pi.removed_at IS NULL
		WHERE p.poster_user_id = $1 AND p.removed_at IS NULL
		  AND NOT EXISTS (SELECT 1 FROM feed_members m WHERE m.feed_id = fd.id AND m.user_id = $1)
		GROUP BY fd.id, fd.name ORDER BY lower(fd.name), fd.id`, userID))
}

// FormerFrames lists live frames the person is not a member of where their direct placements are still live.
func (s *Store) FormerFrames(ctx context.Context, userID uuid.UUID) ([]FormerPlace, error) {
	return many[FormerPlace](s.Pool.Query(ctx, `SELECT f.id, f.name, count(*)::int AS photos
		FROM (`+directPlacementSQL+`) d JOIN placements p ON p.id = d.id JOIN frames f ON f.id = p.frame_id
		WHERE NOT EXISTS (SELECT 1 FROM memberships m WHERE m.frame_id = f.id AND m.user_id = $1)
		GROUP BY f.id, f.name ORDER BY lower(f.name), f.id`, userID, true, []uuid.UUID{}))
}

// FrameExists reports whether a frame is live.
func (s *Store) FrameExists(ctx context.Context, q db.DBTX, frameID uuid.UUID) (string, bool, error) {
	var name string
	err := q.QueryRow(ctx, `SELECT name FROM frames WHERE id = $1 AND deleted_at IS NULL`, frameID).Scan(&name)
	if db.NotFound(err) {
		return "", false, nil
	}
	return name, err == nil, err
}

// FeedExists reports whether a family is live.
func (s *Store) FeedExists(ctx context.Context, q db.DBTX, feedID uuid.UUID) (string, bool, error) {
	var name string
	err := q.QueryRow(ctx, `SELECT name FROM feeds WHERE id = $1 AND deleted_at IS NULL`, feedID).Scan(&name)
	if db.NotFound(err) {
		return "", false, nil
	}
	return name, err == nil, err
}

// CommentsOfUserInFeed lists a person's live person comments on a family's posts (post, comment), oldest first.
func (s *Store) CommentsOfUserInFeed(ctx context.Context, q db.DBTX, feedID, userID uuid.UUID) ([]PostComment, error) {
	return many[PostComment](q.Query(ctx, `SELECT c.post_id, c.id AS comment_id FROM comments c JOIN feed_posts p ON p.id = c.post_id
		WHERE p.feed_id = $1 AND c.author_user_id = $2 AND c.kind = 'person' AND c.deleted_at IS NULL ORDER BY c.post_id, c.id`, feedID, userID))
}

// PostComment is a comment with its post.
type PostComment struct {
	PostID    uuid.UUID `db:"post_id"`
	CommentID uuid.UUID `db:"comment_id"`
}

// FramePostsOfPlacements lists the live frame posts the placements belong to (send and frame).
func (s *Store) FramePostsOfPlacements(ctx context.Context, q db.DBTX, placementIDs []uuid.UUID) ([]uuid.UUID, error) {
	return uuids(q.Query(ctx, `SELECT DISTINCT fp.id FROM frame_posts fp JOIN placements p ON p.send_id = fp.send_id AND p.frame_id = fp.frame_id
		WHERE p.id = ANY($1) AND fp.removed_at IS NULL ORDER BY fp.id`, placementIDs))
}

// SharesLiveScope reports whether two people are both members of a live family or a live frame.
func (s *Store) SharesLiveScope(ctx context.Context, q db.DBTX, a, b uuid.UUID) (bool, error) {
	var ok bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM feed_members x JOIN feed_members y ON y.feed_id = x.feed_id AND y.user_id = $2
			JOIN feeds f ON f.id = x.feed_id AND f.deleted_at IS NULL WHERE x.user_id = $1)
		OR EXISTS (SELECT 1 FROM memberships x JOIN memberships y ON y.frame_id = x.frame_id AND y.user_id = $2
			JOIN frames f ON f.id = x.frame_id AND f.deleted_at IS NULL WHERE x.user_id = $1)`, a, b).Scan(&ok)
	return ok, err
}
