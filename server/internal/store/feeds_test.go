package store_test

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/testdb"
)

// Feed materialization (docs/spec/feeds-data-model.md §2), driven through the
// store and its jobs without HTTP or media processing.

type feedFx struct {
	t   *testing.T
	st  *store.Store
	ctx context.Context
}

func newFeedFx(t *testing.T) *feedFx {
	pool, _ := testdb.New(t)
	return &feedFx{t: t, st: store.New(pool), ctx: context.Background()}
}

func (f *feedFx) exec(sql string, args ...any) {
	f.t.Helper()
	if _, err := f.st.Pool.Exec(f.ctx, sql, args...); err != nil {
		f.t.Fatalf("%s: %v", sql, err)
	}
}

func (f *feedFx) tx(fn func(tx *store.Tx) error) {
	f.t.Helper()
	if err := f.st.InTx(f.ctx, fn); err != nil {
		f.t.Fatal(err)
	}
}

func (f *feedFx) user(name string) uuid.UUID {
	id := store.NewID()
	f.exec(`INSERT INTO users (id, display_name) VALUES ($1, $2)`, id, name)
	return id
}

func (f *feedFx) frame(owner uuid.UUID, settings string) uuid.UUID {
	f.t.Helper()
	id := store.NewID()
	f.tx(func(tx *store.Tx) error {
		if _, err := tx.CreateFrame(f.ctx, store.Frame{ID: id, Name: "frame", Settings: json.RawMessage(settings), DefaultPermissions: store.DefaultMemberPermissions}); err != nil {
			return err
		}
		_, err := tx.AddMember(f.ctx, id, owner, "owner", store.AllPermissions)
		return err
	})
	return id
}

func (f *feedFx) item(sender uuid.UUID) uuid.UUID {
	m, i := store.NewID(), store.NewID()
	f.exec(`INSERT INTO media (id, owner_user_id, kind, status) VALUES ($1, $2, 'photo', 'ready')`, m, sender)
	f.exec(`INSERT INTO items (id, media_id, sender_user_id) VALUES ($1, $2, $3)`, i, m, sender)
	return i
}

func (f *feedFx) feed(owner uuid.UUID) uuid.UUID {
	id := store.NewID()
	f.tx(func(tx *store.Tx) error { return tx.CreateFeed(f.ctx, store.Feed{ID: id, Name: "Peeters kids"}, owner) })
	return id
}

func (f *feedFx) post(feed, poster uuid.UUID, items ...uuid.UUID) uuid.UUID {
	id := store.NewID()
	f.tx(func(tx *store.Tx) error {
		if err := tx.AddFeedMember(f.ctx, feed, poster, "poster"); err != nil {
			return err
		}
		if err := tx.CreateFeedPost(f.ctx, id, feed, nil, poster, items); err != nil {
			return err
		}
		return tx.Enqueue(f.ctx, store.JobFeedFanout, store.PostJob{PostID: id})
	})
	return id
}

func (f *feedFx) subscribe(feed, frame uuid.UUID, history string, count *int) uuid.UUID {
	id := store.NewID()
	f.tx(func(tx *store.Tx) error {
		if _, err := tx.LockFrame(f.ctx, frame); err != nil {
			return err
		}
		if err := tx.CreateSubscription(f.ctx, store.Subscription{ID: id, FeedID: feed, FrameID: frame, History: history, HistoryCount: count}); err != nil {
			return err
		}
		return tx.ActivateSubscription(f.ctx, id, nil)
	})
	return id
}

// with runs fn on the subscription with its frame and row locked.
func (f *feedFx) with(subID uuid.UUID, fn func(tx *store.Tx, sub *store.Subscription) error) {
	f.t.Helper()
	sub0, err := f.st.GetSubscription(f.ctx, f.st.Pool, subID)
	if err != nil {
		f.t.Fatal(err)
	}
	f.tx(func(tx *store.Tx) error {
		if _, err := tx.LockFrame(f.ctx, sub0.FrameID); err != nil {
			return err
		}
		sub, err := tx.LockSubscription(f.ctx, subID)
		if err != nil {
			return err
		}
		return fn(tx, sub)
	})
}

// drain runs queued jobs until none are left (media jobs are dropped).
func (f *feedFx) drain() {
	f.t.Helper()
	for i := 0; i < 1000; i++ {
		job, err := f.st.ClaimJob(f.ctx, time.Minute)
		if err != nil {
			f.t.Fatal(err)
		}
		if job == nil {
			return
		}
		again := false
		switch job.Kind {
		case store.JobFeedFanout:
			var p store.PostJob
			_ = json.Unmarshal(job.Payload, &p)
			if _, err := f.st.FeedFanout(f.ctx, p.PostID); err != nil {
				f.t.Fatal(err)
			}
		case store.JobFeedReconcile:
			var p store.ReconcileJob
			_ = json.Unmarshal(job.Payload, &p)
			res, err := f.st.FeedReconcile(f.ctx, p, 5000)
			if err != nil {
				f.t.Fatal(err)
			}
			again = res.More
		case store.JobFeedSweep:
			var p store.SweepJob
			_ = json.Unmarshal(job.Payload, &p)
			res, err := f.st.FeedSweep(f.ctx, p.SubscriptionID)
			if err != nil {
				f.t.Fatal(err)
			}
			again = res.More
		}
		if err := f.st.CompleteJob(f.ctx, job.ID); err != nil {
			f.t.Fatal(err)
		}
		if again {
			f.tx(func(tx *store.Tx) error { return tx.Enqueue(f.ctx, job.Kind, json.RawMessage(job.Payload)) })
		}
	}
	f.t.Fatal("jobs never drained")
}

type livePl struct {
	ID            uuid.UUID
	Origin        string
	Held          bool
	Suspended     bool
	Backfill      bool
	Moderated     bool
	ReceivedAt    time.Time
	FeedIDs       []uuid.UUID
	OriginFeedID  *uuid.UUID
	ItemCreatedAt time.Time
}

// live maps item -> live placement on the frame.
func (f *feedFx) live(frame uuid.UUID) map[uuid.UUID]livePl {
	f.t.Helper()
	rows, err := f.st.Pool.Query(f.ctx, `SELECT p.item_id, p.id, p.origin, p.held_directly, p.feed_suspended, p.backfill, p.pending_moderation,
		p.received_at, p.origin_feed_id,
		COALESCE((SELECT array_agg(DISTINCT s.feed_id) FROM feed_deliveries d JOIN subscriptions s ON s.id = d.subscription_id AND s.status = 'active'
			WHERE d.placement_id = p.id AND d.state = 'placed'), '{}')
		FROM placements p WHERE p.frame_id = $1 AND p.removed_at IS NULL`, frame)
	if err != nil {
		f.t.Fatal(err)
	}
	defer rows.Close()
	out := map[uuid.UUID]livePl{}
	for rows.Next() {
		var item uuid.UUID
		var p livePl
		if err := rows.Scan(&item, &p.ID, &p.Origin, &p.Held, &p.Suspended, &p.Backfill, &p.Moderated, &p.ReceivedAt, &p.OriginFeedID, &p.FeedIDs); err != nil {
			f.t.Fatal(err)
		}
		out[item] = p
	}
	return out
}

func (f *feedFx) count(sql string, args ...any) int {
	f.t.Helper()
	var n int
	if err := f.st.Pool.QueryRow(f.ctx, sql, args...).Scan(&n); err != nil {
		f.t.Fatal(err)
	}
	return n
}

func (f *feedFx) unsend(post uuid.UUID, items []uuid.UUID) {
	f.t.Helper()
	f.tx(func(tx *store.Tx) error {
		_, subs, err := tx.RemovePostItems(f.ctx, post, items, nil)
		if err != nil {
			return err
		}
		for _, s := range subs {
			if err := tx.Enqueue(f.ctx, store.JobFeedSweep, store.SweepJob{SubscriptionID: s}); err != nil {
				return err
			}
		}
		return nil
	})
}

func ptrInt(n int) *int { return &n }

// A new post reaches every active subscription once; pending ones get nothing;
// rerunning the fanout changes nothing.
func TestFeedFanout(t *testing.T) {
	f := newFeedFx(t)
	anna, ben := f.user("Anna"), f.user("Ben")
	feed := f.feed(anna)
	frameA, frameB := f.frame(anna, `{}`), f.frame(ben, `{}`)
	f.subscribe(feed, frameA, "none", nil)
	f.tx(func(tx *store.Tx) error {
		return tx.CreateSubscription(f.ctx, store.Subscription{ID: store.NewID(), FeedID: feed, FrameID: frameB, History: "none"})
	})
	f.drain()
	x, y := f.item(ben), f.item(ben)
	post := f.post(feed, ben, x, y)
	f.drain()
	a := f.live(frameA)
	if len(a) != 2 || len(f.live(frameB)) != 0 {
		t.Fatalf("A %v, B %v", a, f.live(frameB))
	}
	for _, p := range a {
		if p.Origin != "feed" || p.Held || p.Backfill || p.Suspended || p.OriginFeedID == nil || *p.OriginFeedID != feed || len(p.FeedIDs) != 1 {
			t.Fatalf("feed placement %+v", p)
		}
	}
	f.tx(func(tx *store.Tx) error { return tx.Enqueue(f.ctx, store.JobFeedFanout, store.PostJob{PostID: post}) })
	f.drain()
	if n := f.count(`SELECT count(*) FROM placements WHERE frame_id = $1`, frameA); n != 2 {
		t.Fatalf("rerun created placements: %d", n)
	}
	if n := f.count(`SELECT count(*) FROM feed_deliveries`); n != 2 {
		t.Fatalf("deliveries %d", n)
	}
}

// One live placement per item and frame: direct and feed paths share it, and
// it leaves only when no path holds it (feeds.md §6.2).
func TestFeedSharedPlacements(t *testing.T) {
	f := newFeedFx(t)
	anna := f.user("Anna")
	feed1, feed2 := f.feed(anna), f.feed(anna)
	frame := f.frame(anna, `{}`)
	sub1 := f.subscribe(feed1, frame, "none", nil)
	sub2 := f.subscribe(feed2, frame, "none", nil)
	f.drain()

	// Direct first (as in a send that also targets the frame), then the feed.
	direct := f.item(anna)
	f.tx(func(tx *store.Tx) error {
		return tx.CreatePlacement(f.ctx, store.Placement{ID: store.NewID(), FrameID: frame, ItemID: direct, Status: "pending"})
	})
	p1 := f.post(feed1, anna, direct)
	// One item through two feeds.
	both := f.item(anna)
	q1 := f.post(feed1, anna, both)
	f.post(feed2, anna, both)
	f.drain()
	live := f.live(frame)
	if len(live) != 2 {
		t.Fatalf("live %v", live)
	}
	if p := live[direct]; p.Origin != "send" || !p.Held || len(p.FeedIDs) != 1 {
		t.Fatalf("direct placement %+v", p)
	}
	if p := live[both]; p.Origin != "feed" || p.Held || len(p.FeedIDs) != 2 {
		t.Fatalf("two-feed placement %+v", p)
	}

	// Unsending the feed post leaves the directly held placement.
	f.unsend(p1, nil)
	f.drain()
	if p, ok := f.live(frame)[direct]; !ok || len(p.FeedIDs) != 0 {
		t.Fatalf("direct placement after unsend: %+v %v", p, ok)
	}
	// Unsending from feed 1 keeps the placement feed 2 holds.
	f.unsend(q1, nil)
	f.drain()
	if p, ok := f.live(frame)[both]; !ok || len(p.FeedIDs) != 1 || p.FeedIDs[0] != feed2 {
		t.Fatalf("after feed 1 unsend: %+v %v", p, ok)
	}
	// Unsubscribing feed 1 changes nothing more; unsubscribing feed 2 removes it.
	f.with(sub1, func(tx *store.Tx, sub *store.Subscription) error {
		return tx.EndSubscription(f.ctx, sub, "removed", "unsubscribed", nil)
	})
	f.drain()
	if _, ok := f.live(frame)[both]; !ok {
		t.Fatal("removed by the other feed's unsubscribe")
	}
	f.with(sub2, func(tx *store.Tx, sub *store.Subscription) error {
		return tx.EndSubscription(f.ctx, sub, "removed", "unsubscribed", nil)
	})
	f.drain()
	live = f.live(frame)
	if _, ok := live[both]; ok || len(live) != 1 {
		t.Fatalf("after both unsubscribed: %v", live)
	}
	if n := f.count(`SELECT count(*) FROM feed_deliveries`); n != 0 {
		t.Fatalf("delivery rows left: %d", n)
	}

	// A direct forward onto a feed-only placement makes it held directly.
	feed3 := f.feed(anna)
	sub3 := f.subscribe(feed3, frame, "none", nil)
	z := f.item(anna)
	f.post(feed3, anna, z)
	f.drain()
	pz := f.live(frame)[z]
	f.tx(func(tx *store.Tx) error {
		changed, err := tx.HoldDirectly(f.ctx, frame, pz.ID)
		if err == nil && !changed {
			t.Error("HoldDirectly changed nothing")
		}
		return err
	})
	f.with(sub3, func(tx *store.Tx, sub *store.Subscription) error {
		return tx.EndSubscription(f.ctx, sub, "removed", "unsubscribed", nil)
	})
	f.drain()
	if p, ok := f.live(frame)[z]; !ok || !p.Held || p.Origin != "feed" {
		t.Fatalf("held directly after unsubscribe: %+v %v", p, ok)
	}
}

// History: last_n and all backfill quietly in post order, raising and lowering
// the option backfills or trims, and posts after activation are never trimmed.
func TestFeedHistory(t *testing.T) {
	f := newFeedFx(t)
	anna := f.user("Anna")
	feed := f.feed(anna)
	frame := f.frame(anna, `{}`)
	var old []uuid.UUID
	for i := 0; i < 5; i++ {
		it := f.item(anna)
		old = append(old, it)
		f.post(feed, anna, it)
	}
	f.drain() // no subscriptions yet
	sub := f.subscribe(feed, frame, "last_n", ptrInt(2))
	f.drain()
	live := f.live(frame)
	if len(live) != 2 {
		t.Fatalf("last_n=2 placed %d", len(live))
	}
	for _, it := range old[3:] {
		p, ok := live[it]
		if !ok || !p.Backfill {
			t.Fatalf("newest history item %v: %+v", it, p)
		}
	}
	var postAt time.Time
	if err := f.st.Pool.QueryRow(f.ctx, `SELECT p.created_at FROM feed_posts p JOIN feed_post_items i ON i.post_id = p.id WHERE i.item_id = $1`, old[4]).Scan(&postAt); err != nil {
		t.Fatal(err)
	}
	if !live[old[4]].ReceivedAt.Equal(postAt) {
		t.Fatalf("received_at %v, want post time %v", live[old[4]].ReceivedAt, postAt)
	}
	s, _ := f.st.GetSubscription(f.ctx, f.st.Pool, sub)
	if s.BackfillState != "idle" || s.BackfillPlaced != 2 || s.BackfillTotal != 2 {
		t.Fatalf("progress %+v", s)
	}

	// A post after activation: new, not quiet.
	fresh := f.item(anna)
	f.post(feed, anna, fresh)
	f.drain()
	if p := f.live(frame)[fresh]; p.Backfill {
		t.Fatalf("new post placed as backfill: %+v", p)
	}

	f.with(sub, func(tx *store.Tx, s *store.Subscription) error {
		return tx.SetSubscriptionHistory(f.ctx, s, "all", nil)
	})
	f.drain()
	if n := len(f.live(frame)); n != 6 {
		t.Fatalf("all: %d placements", n)
	}
	f.with(sub, func(tx *store.Tx, s *store.Subscription) error {
		return tx.SetSubscriptionHistory(f.ctx, s, "none", nil)
	})
	f.drain()
	live = f.live(frame)
	if _, ok := live[fresh]; len(live) != 1 || !ok {
		t.Fatalf("none keeps only posts after activation: %v", live)
	}

	// The cap on all: newest first.
	frame2 := f.frame(anna, `{}`)
	sub2 := f.subscribe(feed, frame2, "all", nil)
	_ = sub2
	job, _ := f.st.ClaimJob(f.ctx, time.Minute)
	var p store.ReconcileJob
	_ = json.Unmarshal(job.Payload, &p)
	if _, err := f.st.FeedReconcile(f.ctx, p, 3); err != nil {
		t.Fatal(err)
	}
	_ = f.st.CompleteJob(f.ctx, job.ID)
	live = f.live(frame2)
	if len(live) != 3 {
		t.Fatalf("capped all: %d", len(live))
	}
	for _, it := range append(old[3:], fresh) {
		if _, ok := live[it]; !ok {
			t.Fatalf("capped all misses %v", it)
		}
	}
}

// Pause hides the feed's placements (not directly held ones) and places
// nothing; resume restores them and quietly adds what was posted meanwhile.
func TestFeedPauseResume(t *testing.T) {
	f := newFeedFx(t)
	anna := f.user("Anna")
	feed := f.feed(anna)
	frame := f.frame(anna, `{}`)
	sub := f.subscribe(feed, frame, "none", nil)
	f.drain()
	a, direct := f.item(anna), f.item(anna)
	f.tx(func(tx *store.Tx) error {
		return tx.CreatePlacement(f.ctx, store.Placement{ID: store.NewID(), FrameID: frame, ItemID: direct, Status: "ready"})
	})
	f.post(feed, anna, a, direct)
	f.drain()
	var seq0 int64
	_ = f.st.Pool.QueryRow(f.ctx, `SELECT latest_seq FROM frames WHERE id = $1`, frame).Scan(&seq0)

	f.with(sub, func(tx *store.Tx, s *store.Subscription) error { return tx.PauseSubscription(f.ctx, s) })
	live := f.live(frame)
	if !live[a].Suspended || live[direct].Suspended || len(live[a].FeedIDs) != 0 {
		t.Fatalf("paused: %+v", live)
	}
	entries, _ := f.st.FeedSince(f.ctx, frame, seq0, 100)
	bumped := map[uuid.UUID]bool{}
	for _, e := range entries {
		bumped[e.EntityID] = true
	}
	if !bumped[live[a].ID] || !bumped[live[direct].ID] {
		t.Fatalf("pause did not re-emit both placements: %v", entries)
	}
	rows, _, err := f.st.ListPlacements(f.ctx, store.PlacementFilter{FrameID: frame, Visibility: "all", Limit: 50})
	if err != nil || len(rows) != 1 {
		t.Fatalf("library while paused: %d %v", len(rows), err)
	}
	during := f.item(anna)
	f.post(feed, anna, during)
	f.drain()
	if _, ok := f.live(frame)[during]; ok {
		t.Fatal("posted while paused reached the frame")
	}

	f.with(sub, func(tx *store.Tx, s *store.Subscription) error { return tx.ResumeSubscription(f.ctx, s) })
	f.drain()
	live = f.live(frame)
	if live[a].Suspended || len(live[a].FeedIDs) != 1 {
		t.Fatalf("resumed: %+v", live[a])
	}
	if p, ok := live[during]; !ok || !p.Backfill {
		t.Fatalf("post made while paused: %+v %v", p, ok)
	}
}

// A frame that purges (or whose moderation rejects) a feed placement never
// gets that item again from a backfill, also not through another post.
func TestFeedTombstones(t *testing.T) {
	f := newFeedFx(t)
	anna := f.user("Anna")
	feed := f.feed(anna)
	frame := f.frame(anna, `{}`)
	x, y := f.item(anna), f.item(anna)
	f.post(feed, anna, x, y)
	f.drain()
	sub := f.subscribe(feed, frame, "all", nil)
	f.drain()
	px := f.live(frame)[x]
	f.tx(func(tx *store.Tx) error {
		_, err := tx.RemovePlacements(f.ctx, `p.id = $1`, px.ID)
		return err
	})
	if n := f.count(`SELECT count(*) FROM feed_deliveries WHERE state = 'declined' AND placement_id IS NULL AND item_id = $1`, x); n != 1 {
		t.Fatalf("tombstones %d", n)
	}
	f.post(feed, anna, x) // the same item in another post
	f.with(sub, func(tx *store.Tx, s *store.Subscription) error {
		return tx.SetSubscriptionHistory(f.ctx, s, "last_n", ptrInt(10))
	})
	f.drain()
	live := f.live(frame)
	if _, ok := live[x]; ok || len(live) != 1 {
		t.Fatalf("purged item came back: %v", live)
	}
}

// Moderation applies to feed posts unless the poster manages the frame;
// unsending one item removes only that item; the post goes with its last item.
func TestFeedModerationAndUnsend(t *testing.T) {
	f := newFeedFx(t)
	anna, ben := f.user("Anna"), f.user("Ben")
	feed := f.feed(anna)
	frame := f.frame(anna, `{}`)
	f.exec(`UPDATE frames SET moderation = true WHERE id = $1`, frame)
	f.subscribe(feed, frame, "none", nil)
	f.drain()
	x, y := f.item(ben), f.item(anna)
	post := f.post(feed, ben, x)
	f.post(feed, anna, y)
	f.drain()
	live := f.live(frame)
	if !live[x].Moderated || live[y].Moderated {
		t.Fatalf("moderation: %+v", live)
	}
	z := f.item(ben)
	f.tx(func(tx *store.Tx) error {
		_, err := tx.Exec(f.ctx, `INSERT INTO feed_post_items (post_id, item_id, position) VALUES ($1, $2, 1)`, post, z)
		return err
	})
	f.tx(func(tx *store.Tx) error { return tx.Enqueue(f.ctx, store.JobFeedFanout, store.PostJob{PostID: post}) })
	f.drain()
	f.unsend(post, []uuid.UUID{x})
	f.drain()
	live = f.live(frame)
	if _, ok := live[x]; ok {
		t.Fatal("unsent item still on the frame")
	}
	if _, ok := live[z]; !ok {
		t.Fatal("other item of the post removed")
	}
	if n := f.count(`SELECT count(*) FROM feed_posts WHERE id = $1 AND removed_at IS NULL`, post); n != 1 {
		t.Fatal("post removed with items left")
	}
	f.unsend(post, []uuid.UUID{z})
	f.drain()
	if n := f.count(`SELECT count(*) FROM feed_posts WHERE id = $1 AND removed_at IS NOT NULL`, post); n != 1 {
		t.Fatal("post not removed with its last item")
	}
}

// Ending a subscription resets slideshow.feed_id; deleting a feed removes its
// placements everywhere and finally the feed row.
func TestFeedEndAndDelete(t *testing.T) {
	f := newFeedFx(t)
	anna := f.user("Anna")
	feed := f.feed(anna)
	frame := f.frame(anna, `{"slideshow":{"feed_id":"`+uuid.Nil.String()+`"}}`)
	f.exec(`UPDATE frames SET settings = jsonb_set(settings, '{slideshow,feed_id}', to_jsonb($2::text)) WHERE id = $1`, frame, feed.String())
	sub := f.subscribe(feed, frame, "none", nil)
	f.drain()
	x := f.item(anna)
	f.post(feed, anna, x)
	f.drain()
	if len(f.live(frame)) != 1 {
		t.Fatal("not placed")
	}
	f.tx(func(tx *store.Tx) error {
		if err := tx.MarkFeedDeleted(f.ctx, feed); err != nil {
			return err
		}
		if _, err := tx.Exec(f.ctx, `UPDATE feed_post_items SET removed_at = now() WHERE post_id IN (SELECT id FROM feed_posts WHERE feed_id = $1)`, feed); err != nil {
			return err
		}
		return nil
	})
	f.with(sub, func(tx *store.Tx, s *store.Subscription) error {
		return tx.EndSubscription(f.ctx, s, "removed", "feed_deleted", nil)
	})
	var settings map[string]map[string]any
	var raw []byte
	_ = f.st.Pool.QueryRow(f.ctx, `SELECT settings FROM frames WHERE id = $1`, frame).Scan(&raw)
	_ = json.Unmarshal(raw, &settings)
	if v, ok := settings["slideshow"]["feed_id"]; ok && v != nil {
		t.Fatalf("slideshow.feed_id not reset: %v", settings)
	}
	f.drain()
	if len(f.live(frame)) != 0 {
		t.Fatal("feed placement survived the feed")
	}
	if done, err := f.st.FinishFeedDelete(f.ctx, feed); err != nil || !done {
		t.Fatalf("finish delete: %v %v", done, err)
	}
	if n := f.count(`SELECT count(*) FROM feeds`); n != 0 {
		t.Fatalf("feeds left %d", n)
	}
}
