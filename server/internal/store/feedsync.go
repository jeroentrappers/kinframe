package store

import (
	"context"
	"errors"
	"sort"
	"strconv"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Feed materialization (docs/spec/feeds-data-model.md §2). Every write that
// places on a frame runs in one transaction per frame with the frame row
// locked first (LockFrame), which also serializes the change feed; then the
// subscription row. Multi-frame work loops over frames in frame order.
//
// The one primitive is place (attach a post item to the frame through a
// subscription, creating a placement only when the frame has none of that
// item); unplace is its inverse and removes the placement only when nothing
// else holds it. Delivery rows are keyed (subscription, post, item), so both
// are idempotent and jobs can rerun safely.

// FeedBatch is how many post items one reconcile or sweep transaction handles.
const FeedBatch = 200

// wantedSQL lists the post items a subscription wants: every live item posted
// at or after the boundary ($2, the first activation), plus the newest $3
// live items from before it (history). $1 is the feed.
const wantedSQL = `
	SELECT pi.post_id, pi.item_id, p.created_at FROM feed_post_items pi JOIN feed_posts p ON p.id = pi.post_id
	WHERE p.feed_id = $1 AND p.removed_at IS NULL AND pi.removed_at IS NULL AND p.created_at >= $2
	UNION ALL
	(SELECT pi.post_id, pi.item_id, p.created_at FROM feed_post_items pi JOIN feed_posts p ON p.id = pi.post_id
	WHERE p.feed_id = $1 AND p.removed_at IS NULL AND pi.removed_at IS NULL AND p.created_at < $2
	ORDER BY p.created_at DESC, p.id DESC, pi.position DESC LIMIT $3)`

// circleReachSQL limits post items to those a frame may get (README §5.18): whole-family posts, and circle
// posts when the frame has a manager (owner, admin or change_settings) in the circle or is one of its
// circle_frames. p is the feed post; the frame id is the parameter given as %s. Applied when placing (fanout and
// backfill) only, so later changes to a circle never move existing placements.
func circleReachSQL(frameParam string) string {
	return `(p.circle_id IS NULL OR EXISTS (SELECT 1 FROM circle_frames cf WHERE cf.circle_id = p.circle_id AND cf.frame_id = ` + frameParam + `)
		OR EXISTS (SELECT 1 FROM circle_members cm JOIN memberships ms ON ms.user_id = cm.user_id AND ms.frame_id = ` + frameParam + `
			WHERE cm.circle_id = p.circle_id AND (ms.role IN ('owner', 'admin') OR (ms.permissions->>'change_settings')::boolean)))`
}

// historyLimit is how many items from before the boundary the subscription's
// history option wants (all is capped at maxBackfill).
func historyLimit(sub *Subscription, maxBackfill int) int {
	switch sub.History {
	case "last_n":
		if sub.HistoryCount != nil {
			return min(*sub.HistoryCount, maxBackfill)
		}
	case "all":
		return maxBackfill
	}
	return 0
}

// postItemRef is a post item with what placing it needs.
type postItemRef struct {
	PostID        uuid.UUID  `db:"post_id"`
	ItemID        uuid.UUID  `db:"item_id"`
	PostCreatedAt time.Time  `db:"post_created_at"`
	SendID        *uuid.UUID `db:"send_id"`
	PosterID      *uuid.UUID `db:"poster_user_id"`
	MediaID       uuid.UUID  `db:"media_id"`
	MotionMediaID *uuid.UUID `db:"motion_media_id"`
}

const postItemRefCols = `pi.post_id, pi.item_id, p.created_at AS post_created_at, p.send_id, p.poster_user_id, i.media_id, i.motion_media_id`

// placeEffects collects what a batch of place/unplace calls must do after:
// change-feed bumps (existing placements whose holds changed) and rendition
// checks (media that got new placements).
type placeEffects struct {
	bump       []uuid.UUID
	media      map[uuid.UUID]bool
	moderators map[uuid.UUID]bool // poster -> owner/admin of the frame (cache)
}

func newEffects() *placeEffects {
	return &placeEffects{media: map[uuid.UUID]bool{}, moderators: map[uuid.UUID]bool{}}
}

func (tx *Tx) flushEffects(ctx context.Context, frameID uuid.UUID, e *placeEffects) error {
	if err := tx.FeedPutEntities(ctx, frameID, FeedPlacement, e.bump, false); err != nil {
		return err
	}
	ids := make([]uuid.UUID, 0, len(e.media))
	for id := range e.media {
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i].String() < ids[j].String() })
	for _, id := range ids {
		if err := tx.Enqueue(ctx, JobEnsureRenditions, map[string]any{"media_id": id}); err != nil {
			return err
		}
	}
	e.bump, e.media = nil, map[uuid.UUID]bool{}
	return nil
}

// place attaches one post item to the frame through sub (feeds-data-model.md
// §2.1). The frame must be locked and sub active. quiet places history:
// backfill = true and received_at = the post time.
func (tx *Tx) place(ctx context.Context, f *Frame, sub *Subscription, r postItemRef, quiet bool, e *placeEffects) error {
	var exists bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM feed_deliveries WHERE subscription_id = $1 AND post_id = $2 AND item_id = $3)`,
		sub.ID, r.PostID, r.ItemID).Scan(&exists); err != nil {
		return err
	}
	if exists {
		return nil // idempotent
	}
	// The frame removed this item (purge, moderation): never again through a feed.
	var declined bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM feed_deliveries d JOIN subscriptions s ON s.id = d.subscription_id
		WHERE s.frame_id = $1 AND d.item_id = $2 AND d.state = 'declined')`, f.ID, r.ItemID).Scan(&declined); err != nil {
		return err
	}
	if declined {
		_, err := tx.Exec(ctx, `INSERT INTO feed_deliveries (subscription_id, post_id, item_id, state) VALUES ($1, $2, $3, 'declined')`,
			sub.ID, r.PostID, r.ItemID)
		return err
	}
	var pid uuid.UUID
	var suspended bool
	var status string
	err := tx.QueryRow(ctx, `SELECT id, feed_suspended, status FROM placements WHERE frame_id = $1 AND item_id = $2 AND removed_at IS NULL FOR UPDATE`,
		f.ID, r.ItemID).Scan(&pid, &suspended, &status)
	switch {
	case err == nil:
		// The frame holds the item already (directly or through another feed): share it.
		if _, err := tx.Exec(ctx, `INSERT INTO feed_deliveries (subscription_id, post_id, item_id, placement_id, state) VALUES ($1, $2, $3, $4, 'placed')`,
			sub.ID, r.PostID, r.ItemID, pid); err != nil {
			return err
		}
		if suspended {
			if _, err := tx.Exec(ctx, `UPDATE placements SET feed_suspended = false WHERE id = $1`, pid); err != nil {
				return err
			}
		}
		if status != "pending" {
			e.bump = append(e.bump, pid) // feed_ids changed (pending ones enter the feed when ready)
		}
		return nil
	case !db.NotFound(err):
		return err
	}
	moderated := false
	if f.Moderation {
		manager := false
		if r.PosterID != nil {
			var ok bool
			if manager, ok = e.moderators[*r.PosterID]; !ok {
				var role string
				err := tx.QueryRow(ctx, `SELECT role FROM memberships WHERE frame_id = $1 AND user_id = $2`, f.ID, *r.PosterID).Scan(&role)
				if err != nil && !db.NotFound(err) {
					return err
				}
				manager = role == "owner" || role == "admin"
				e.moderators[*r.PosterID] = manager
			}
		}
		moderated = !manager
	}
	p := Placement{ID: NewID(), FrameID: f.ID, ItemID: r.ItemID, SendID: r.SendID, Status: "pending", PendingModeration: moderated,
		Origin: "feed", OriginFeedID: &sub.FeedID, Backfill: quiet}
	if quiet {
		p.ReceivedAt = r.PostCreatedAt
	}
	if err := tx.CreatePlacement(ctx, p); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO feed_deliveries (subscription_id, post_id, item_id, placement_id, state) VALUES ($1, $2, $3, $4, 'placed')`,
		sub.ID, r.PostID, r.ItemID, p.ID); err != nil {
		return err
	}
	// The placement enters the change feed when its rendition is ready.
	e.media[r.MediaID] = true
	if r.MotionMediaID != nil {
		e.media[*r.MotionMediaID] = true
	}
	return nil
}

// unplace detaches one post item from the frame (§2.2): the delivery row goes,
// and the placement too unless it is held directly or by another feed. The
// frame must be locked.
func (tx *Tx) unplace(ctx context.Context, subID, postID, itemID uuid.UUID, e *placeEffects) error {
	var pid *uuid.UUID
	err := tx.QueryRow(ctx, `DELETE FROM feed_deliveries WHERE subscription_id = $1 AND post_id = $2 AND item_id = $3 RETURNING placement_id`,
		subID, postID, itemID).Scan(&pid)
	if db.NotFound(err) || (err == nil && pid == nil) {
		return nil
	}
	if err != nil {
		return err
	}
	var held, removed bool
	err = tx.QueryRow(ctx, `SELECT held_directly, removed_at IS NOT NULL FROM placements WHERE id = $1 FOR UPDATE`, *pid).Scan(&held, &removed)
	if db.NotFound(err) || (err == nil && removed) {
		return nil
	}
	if err != nil {
		return err
	}
	var others bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM feed_deliveries WHERE placement_id = $1 AND state = 'placed')`, *pid).Scan(&others); err != nil {
		return err
	}
	if held || others {
		if err := tx.recomputeSuspended(ctx, []uuid.UUID{*pid}); err != nil {
			return err
		}
		e.bump = append(e.bump, *pid)
		return nil
	}
	_, err = tx.RemovePlacements(ctx, `p.id = $1`, *pid)
	return err
}

// recomputeSuspended sets feed_suspended: not held directly and no active
// subscription holds it (all its feeds are paused or ending).
func (tx *Tx) recomputeSuspended(ctx context.Context, ids []uuid.UUID) error {
	if len(ids) == 0 {
		return nil
	}
	_, err := tx.Exec(ctx, `UPDATE placements p SET feed_suspended = (NOT p.held_directly AND NOT EXISTS (
			SELECT 1 FROM feed_deliveries d JOIN subscriptions s ON s.id = d.subscription_id
			WHERE d.placement_id = p.id AND d.state = 'placed' AND s.status = 'active'))
		WHERE p.id = ANY($1) AND p.removed_at IS NULL`, ids)
	return err
}

// subscriptionPlacements lists the live placements a subscription holds.
func (tx *Tx) subscriptionPlacements(ctx context.Context, subID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := tx.Query(ctx, `SELECT d.placement_id FROM feed_deliveries d JOIN placements p ON p.id = d.placement_id AND p.removed_at IS NULL
		WHERE d.subscription_id = $1 AND d.state = 'placed' ORDER BY d.placement_id`, subID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// ---------------------------------------------------------------- state transitions (frame locked by the caller)

// queueReconcile bumps the subscription's version and enqueues a reconcile
// that places (quietly) or trims history against it.
func (tx *Tx) queueReconcile(ctx context.Context, subID uuid.UUID) error {
	var version int
	var start time.Time
	if err := tx.QueryRow(ctx, `UPDATE subscriptions SET reconciled_version = reconciled_version + 1, backfill_state = 'queued',
		backfill_placed = 0, backfill_total = 0 WHERE id = $1 RETURNING reconciled_version, now()`, subID).Scan(&version, &start); err != nil {
		return err
	}
	return tx.Enqueue(ctx, JobFeedReconcile, ReconcileJob{SubscriptionID: subID, Version: version, Start: start})
}

// ReconcileJob is the payload of JobFeedReconcile.
type ReconcileJob struct {
	SubscriptionID uuid.UUID `json:"subscription_id"`
	Version        int       `json:"version"`
	// Start: posts made before it are placed quietly (history, or made while paused).
	Start time.Time `json:"start"`
}

// SweepJob is the payload of JobFeedSweep.
type SweepJob struct {
	SubscriptionID uuid.UUID `json:"subscription_id"`
}

// PostJob is the payload of JobFeedFanout.
type PostJob struct {
	PostID uuid.UUID `json:"post_id"`
}

// ActivateSubscription moves a pending subscription to active (approve,
// auto-approve, admin request, subscribe invite): the history boundary is set
// once and the backfill starts.
func (tx *Tx) ActivateSubscription(ctx context.Context, subID uuid.UUID, by *uuid.UUID) error {
	var frameID uuid.UUID
	if err := tx.QueryRow(ctx, `UPDATE subscriptions SET status = 'active', activated_at = COALESCE(activated_at, now()),
		decided_by = COALESCE($2, decided_by), paused_at = NULL WHERE id = $1 RETURNING frame_id`, subID, by).Scan(&frameID); err != nil {
		return err
	}
	if err := tx.SyncFrameBirthdays(ctx, frameID); err != nil {
		return err
	}
	return tx.queueReconcile(ctx, subID)
}

// PauseSubscription: active -> paused. Placements only this feed held leave
// the frame (rendered as placement.remove) but stay on the server.
func (tx *Tx) PauseSubscription(ctx context.Context, sub *Subscription) error {
	if _, err := tx.Exec(ctx, `UPDATE subscriptions SET status = 'paused', paused_at = now() WHERE id = $1`, sub.ID); err != nil {
		return err
	}
	if err := tx.SyncFrameBirthdays(ctx, sub.FrameID); err != nil {
		return err
	}
	return tx.refreshHolds(ctx, sub)
}

// ResumeSubscription: paused -> active. The feed's placements come back, and a
// reconcile quietly places what was posted meanwhile.
func (tx *Tx) ResumeSubscription(ctx context.Context, sub *Subscription) error {
	if _, err := tx.Exec(ctx, `UPDATE subscriptions SET status = 'active', paused_at = NULL WHERE id = $1`, sub.ID); err != nil {
		return err
	}
	if err := tx.SyncFrameBirthdays(ctx, sub.FrameID); err != nil {
		return err
	}
	if err := tx.refreshHolds(ctx, sub); err != nil {
		return err
	}
	return tx.queueReconcile(ctx, sub.ID)
}

// refreshHolds recomputes feed_suspended for the subscription's placements and
// re-emits them (their feed_ids changed, and some appear or disappear).
func (tx *Tx) refreshHolds(ctx context.Context, sub *Subscription) error {
	ids, err := tx.subscriptionPlacements(ctx, sub.ID)
	if err != nil {
		return err
	}
	if err := tx.recomputeSuspended(ctx, ids); err != nil {
		return err
	}
	return tx.FeedPutEntities(ctx, sub.FrameID, FeedPlacement, ids, false)
}

// SetSubscriptionHistory changes the history option; an active subscription
// backfills or trims (a paused one does so on resume).
func (tx *Tx) SetSubscriptionHistory(ctx context.Context, sub *Subscription, history string, count *int) error {
	if _, err := tx.Exec(ctx, `UPDATE subscriptions SET history = $2, history_count = $3 WHERE id = $1`, sub.ID, history, count); err != nil {
		return err
	}
	if sub.Status == "active" {
		return tx.queueReconcile(ctx, sub.ID)
	}
	return nil
}

// EndSubscription moves a live subscription to rejected or removed. Its
// placements leave the frame at once (suspended) and a sweep removes those
// only it held; slideshow.feed_id resets when it pointed at the feed.
func (tx *Tx) EndSubscription(ctx context.Context, sub *Subscription, status, reason string, by *uuid.UUID) error {
	var r *string
	if reason != "" {
		r = &reason
	}
	if _, err := tx.Exec(ctx, `UPDATE subscriptions SET status = $2, removed_reason = $3, ended_at = now(), paused_at = NULL,
		decided_by = CASE WHEN $2 = 'rejected' THEN $4 ELSE decided_by END, backfill_state = 'idle' WHERE id = $1`,
		sub.ID, status, r, by); err != nil {
		return err
	}
	if err := tx.SyncFrameBirthdays(ctx, sub.FrameID); err != nil {
		return err
	}
	if err := tx.refreshHolds(ctx, sub); err != nil {
		return err
	}
	if err := tx.ResetSlideshowFeed(ctx, sub.FrameID, sub.FeedID); err != nil {
		return err
	}
	return tx.Enqueue(ctx, JobFeedSweep, SweepJob{SubscriptionID: sub.ID})
}

// EndFrameSubscriptions ends every live subscription of a frame that is being
// removed (its placements go with the frame). Returns the ended ones.
func (tx *Tx) EndFrameSubscriptions(ctx context.Context, frameID uuid.UUID) ([]Subscription, error) {
	subs, err := many[Subscription](tx.Query(ctx, `UPDATE subscriptions s SET status = 'removed', removed_reason = 'frame_removed',
		ended_at = now(), paused_at = NULL, backfill_state = 'idle'
		WHERE s.frame_id = $1 AND s.status IN ('pending', 'active', 'paused') RETURNING `+subCols, frameID))
	if err != nil {
		return nil, err
	}
	_, err = tx.Exec(ctx, `DELETE FROM feed_deliveries WHERE subscription_id IN (SELECT id FROM subscriptions WHERE frame_id = $1)`, frameID)
	return subs, err
}

// HoldDirectly marks an existing placement as held directly (a direct
// forward or share reached a frame that had it only through a feed), so
// unsubscribing no longer removes it. Reports whether anything changed.
func (tx *Tx) HoldDirectly(ctx context.Context, frameID, placementID uuid.UUID) (bool, error) {
	tag, err := tx.Exec(ctx, `UPDATE placements SET held_directly = true, feed_suspended = false
		WHERE id = $1 AND frame_id = $2 AND NOT held_directly AND removed_at IS NULL`, placementID, frameID)
	if err != nil || tag.RowsAffected() == 0 {
		return false, err
	}
	return true, tx.FeedPut(ctx, frameID, FeedPlacement, placementID, false)
}

// BumpFeedPlacements re-emits the live placements a feed originated, on every
// frame (a rename changes their credit). Frames are visited in order.
func (tx *Tx) BumpFeedPlacements(ctx context.Context, feedID uuid.UUID) error {
	rows, err := tx.Query(ctx, `SELECT frame_id, id FROM placements WHERE origin_feed_id = $1 AND removed_at IS NULL ORDER BY frame_id, id`, feedID)
	if err != nil {
		return err
	}
	byFrame := map[uuid.UUID][]uuid.UUID{}
	var frames []uuid.UUID
	for rows.Next() {
		var f, p uuid.UUID
		if err := rows.Scan(&f, &p); err != nil {
			rows.Close()
			return err
		}
		if _, ok := byFrame[f]; !ok {
			frames = append(frames, f)
		}
		byFrame[f] = append(byFrame[f], p)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, f := range frames {
		if err := tx.FeedPutEntities(ctx, f, FeedPlacement, byFrame[f], false); err != nil {
			return err
		}
	}
	return nil
}

// ReleaseBackfill re-emits a frame's backfilled placements: the frame app now
// understands `backfill` (they were withheld from older apps).
func (tx *Tx) ReleaseBackfill(ctx context.Context, frameID uuid.UUID) (int, error) {
	rows, err := tx.Query(ctx, `SELECT id FROM placements WHERE frame_id = $1 AND backfill AND removed_at IS NULL ORDER BY id`, frameID)
	if err != nil {
		return 0, err
	}
	ids, err := collectIDs(rows)
	if err != nil {
		return 0, err
	}
	return len(ids), tx.FeedPutEntities(ctx, frameID, FeedPlacement, ids, false)
}

// ---------------------------------------------------------------- jobs

// errSkip ends a job transaction without effect.
var errSkip = errors.New("skip")

// FeedFanout places a new post on every active subscription of its feed, one
// transaction per frame. Returns the subscriptions it touched.
func (s *Store) FeedFanout(ctx context.Context, postID uuid.UUID) ([]uuid.UUID, error) {
	var feedID uuid.UUID
	var circleID *uuid.UUID
	var live, quiet bool
	var postedAt time.Time
	err := s.Pool.QueryRow(ctx, `SELECT p.feed_id, p.removed_at IS NULL AND f.deleted_at IS NULL, p.created_at, p.quiet, p.circle_id
		FROM feed_posts p JOIN feeds f ON f.id = p.feed_id WHERE p.id = $1`, postID).Scan(&feedID, &live, &postedAt, &quiet, &circleID)
	if db.NotFound(err) || (err == nil && !live) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	subs, err := many[Subscription](s.Pool.Query(ctx, `SELECT `+subCols+` FROM subscriptions s WHERE s.feed_id = $1 AND s.status = 'active' ORDER BY s.frame_id`, feedID))
	if err != nil {
		return nil, err
	}
	var touched []uuid.UUID
	for _, sub0 := range subs {
		err := s.InTx(ctx, func(tx *Tx) error {
			f, err := tx.LockFrame(ctx, sub0.FrameID)
			if err == ErrNotFound {
				return errSkip
			}
			if err != nil {
				return err
			}
			sub, err := tx.LockSubscription(ctx, sub0.ID)
			if err != nil || sub.Status != "active" {
				if err == ErrNotFound || err == nil {
					return errSkip
				}
				return err
			}
			// A post from before the subscription's first activation is history:
			// its reconcile decides (quietly, per the history option).
			if sub.ActivatedAt == nil || postedAt.Before(*sub.ActivatedAt) {
				return errSkip
			}
			// A circle post goes only to the frames the circle reaches now (README §5.18).
			if circleID != nil {
				var reached bool
				if err := tx.QueryRow(ctx, `SELECT `+circleReachSQL("$2")+` FROM feed_posts p WHERE p.id = $1`, postID, f.ID).Scan(&reached); err != nil {
					return err
				}
				if !reached {
					return errSkip
				}
			}
			refs, err := many[postItemRef](tx.Query(ctx, `SELECT `+postItemRefCols+` FROM feed_post_items pi
				JOIN feed_posts p ON p.id = pi.post_id JOIN items i ON i.id = pi.item_id
				WHERE pi.post_id = $1 AND pi.removed_at IS NULL AND p.removed_at IS NULL ORDER BY pi.position`, postID))
			if err != nil {
				return err
			}
			e := newEffects()
			for _, r := range refs {
				// A photo source's first-run posts are history: placed quietly (photo-sources.md §9.3).
				if err := tx.place(ctx, f, sub, r, quiet, e); err != nil {
					return err
				}
			}
			return tx.flushEffects(ctx, f.ID, e)
		})
		if errors.Is(err, errSkip) {
			continue
		}
		if err != nil {
			return touched, err
		}
		touched = append(touched, sub0.ID)
	}
	return touched, nil
}

// ReconcileResult reports one reconcile batch.
type ReconcileResult struct {
	More bool // another batch is needed (the caller re-enqueues the job)
	Done bool // the subscription is reconciled (backfill idle)
	Sub  *Subscription
}

// FeedReconcile runs one batch of reconcile(sub) (§2.3): place the wanted post
// items that have no delivery row yet, newest first (quietly when posted
// before job.Start), and unplace history items no longer wanted. Declined
// rows are left alone. Stops when the subscription's version moved on.
func (s *Store) FeedReconcile(ctx context.Context, job ReconcileJob, maxBackfill int) (ReconcileResult, error) {
	var res ReconcileResult
	sub0, err := s.GetSubscription(ctx, s.Pool, job.SubscriptionID)
	if err == ErrNotFound {
		return res, nil
	}
	if err != nil {
		return res, err
	}
	err = s.InTx(ctx, func(tx *Tx) error {
		res = ReconcileResult{}
		f, err := tx.LockFrame(ctx, sub0.FrameID)
		if err == ErrNotFound {
			return errSkip
		}
		if err != nil {
			return err
		}
		sub, err := tx.LockSubscription(ctx, sub0.ID)
		if err != nil {
			if err == ErrNotFound {
				return errSkip
			}
			return err
		}
		if sub.ReconciledVersion != job.Version {
			return errSkip // a newer reconcile took over
		}
		if sub.Status != "active" || sub.ActivatedAt == nil {
			_, err := tx.Exec(ctx, `UPDATE subscriptions SET backfill_state = 'idle' WHERE id = $1`, sub.ID)
			return err
		}
		limit := historyLimit(sub, maxBackfill)
		missingSQL := `WITH wanted AS (` + wantedSQL + `)
			SELECT ` + postItemRefCols + ` FROM wanted w
			JOIN feed_post_items pi ON pi.post_id = w.post_id AND pi.item_id = w.item_id
			JOIN feed_posts p ON p.id = w.post_id JOIN items i ON i.id = w.item_id
			WHERE NOT EXISTS (SELECT 1 FROM feed_deliveries d WHERE d.subscription_id = $4 AND d.post_id = w.post_id AND d.item_id = w.item_id)
			AND ` + circleReachSQL("$5") + `
			ORDER BY w.created_at DESC, w.post_id DESC, pi.position`
		trimSQL := `WITH wanted AS (` + wantedSQL + `)
			SELECT d.post_id, d.item_id FROM feed_deliveries d WHERE d.subscription_id = $4 AND d.state = 'placed'
			AND NOT EXISTS (SELECT 1 FROM wanted w WHERE w.post_id = d.post_id AND w.item_id = d.item_id)
			ORDER BY d.post_id, d.item_id`
		args := []any{sub.FeedID, *sub.ActivatedAt, limit, sub.ID, f.ID}
		trimArgs := args[:4] // the trim never looks at circles: existing placements stay
		if sub.BackfillState == "queued" {
			var total int
			if err := tx.QueryRow(ctx, `SELECT (SELECT count(*) FROM (`+missingSQL+`) m) + (SELECT count(*) FROM (`+trimSQL+`) t)`, args...).Scan(&total); err != nil {
				return err
			}
			if _, err := tx.Exec(ctx, `UPDATE subscriptions SET backfill_state = 'running', backfill_placed = 0, backfill_total = $2 WHERE id = $1`, sub.ID, total); err != nil {
				return err
			}
		}
		missing, err := many[postItemRef](tx.Query(ctx, missingSQL+` LIMIT `+itoa(FeedBatch), args...))
		if err != nil {
			return err
		}
		type key struct{ Post, Item uuid.UUID }
		var trim []key
		if left := FeedBatch - len(missing); left > 0 {
			rows, err := tx.Query(ctx, trimSQL+` LIMIT `+itoa(left), trimArgs...)
			if err != nil {
				return err
			}
			for rows.Next() {
				var k key
				if err := rows.Scan(&k.Post, &k.Item); err != nil {
					rows.Close()
					return err
				}
				trim = append(trim, k)
			}
			rows.Close()
			if err := rows.Err(); err != nil {
				return err
			}
		}
		e := newEffects()
		for _, r := range missing {
			if err := tx.place(ctx, f, sub, r, r.PostCreatedAt.Before(job.Start), e); err != nil {
				return err
			}
		}
		for _, k := range trim {
			if err := tx.unplace(ctx, sub.ID, k.Post, k.Item, e); err != nil {
				return err
			}
		}
		if err := tx.flushEffects(ctx, f.ID, e); err != nil {
			return err
		}
		n := len(missing) + len(trim)
		res.More = n >= FeedBatch
		res.Done = !res.More
		state := "running"
		if res.Done {
			state = "idle"
		}
		sub, err = one[Subscription](tx.Query(ctx, `UPDATE subscriptions s SET backfill_placed = LEAST(backfill_placed + $2, GREATEST(backfill_total, backfill_placed + $2)),
			backfill_state = $3 WHERE s.id = $1 RETURNING `+subCols, sub.ID, n, state))
		res.Sub = sub
		return err
	})
	if errors.Is(err, errSkip) {
		return ReconcileResult{}, nil
	}
	return res, err
}

// SweepResult reports one sweep batch.
type SweepResult struct {
	More bool
	Sub  *Subscription
}

// FeedSweep unplaces one batch of what a subscription must no longer hold:
// everything once it ended, else the items of removed posts. Delivery rows
// (tombstones too) are deleted.
func (s *Store) FeedSweep(ctx context.Context, subID uuid.UUID) (SweepResult, error) {
	var res SweepResult
	sub0, err := s.GetSubscription(ctx, s.Pool, subID)
	if err == ErrNotFound {
		return res, nil
	}
	if err != nil {
		return res, err
	}
	res.Sub = sub0
	err = s.InTx(ctx, func(tx *Tx) error {
		f, err := tx.LockFrame(ctx, sub0.FrameID)
		if err == ErrNotFound {
			// The frame is gone, and its placements with it.
			_, err := tx.Exec(ctx, `DELETE FROM feed_deliveries WHERE subscription_id = $1`, subID)
			return err
		}
		if err != nil {
			return err
		}
		sub, err := tx.LockSubscription(ctx, subID)
		if err != nil {
			return err
		}
		ended := !sub.Live()
		rows, err := tx.Query(ctx, `SELECT d.post_id, d.item_id FROM feed_deliveries d
			JOIN feed_post_items pi ON pi.post_id = d.post_id AND pi.item_id = d.item_id JOIN feed_posts p ON p.id = d.post_id
			WHERE d.subscription_id = $1 AND ($2 OR pi.removed_at IS NOT NULL OR p.removed_at IS NOT NULL)
			ORDER BY d.post_id, d.item_id LIMIT `+itoa(FeedBatch*2), subID, ended)
		if err != nil {
			return err
		}
		type key struct{ Post, Item uuid.UUID }
		var keys []key
		for rows.Next() {
			var k key
			if err := rows.Scan(&k.Post, &k.Item); err != nil {
				rows.Close()
				return err
			}
			keys = append(keys, k)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		e := newEffects()
		for _, k := range keys {
			if err := tx.unplace(ctx, subID, k.Post, k.Item, e); err != nil {
				return err
			}
		}
		res.More = len(keys) >= FeedBatch*2
		return tx.flushEffects(ctx, f.ID, e)
	})
	return res, err
}

func itoa(n int) string { return strconv.Itoa(n) }
