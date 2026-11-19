package store_test

import (
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Leaving a family (docs/spec/leaving.md §4): takings, restores through keep requests, the purge, the account
// deletion handover and the memories filter lists, driven through the store.

func (f *feedFx) direct(frame, item uuid.UUID) uuid.UUID {
	f.t.Helper()
	id := store.NewID()
	f.tx(func(tx *store.Tx) error {
		return tx.CreatePlacement(f.ctx, store.Placement{ID: id, FrameID: frame, ItemID: item, Status: "delivered", Origin: "send"})
	})
	return id
}

func (f *feedFx) takeAway(sp store.TakeAwaySpec) *store.TakeAwayOutcome {
	f.t.Helper()
	if sp.Now.IsZero() {
		sp.Now = time.Now().UTC().Truncate(time.Microsecond)
	}
	if sp.Until.IsZero() {
		sp.Until = sp.Now.Add(30 * 24 * time.Hour)
	}
	var out *store.TakeAwayOutcome
	f.tx(func(tx *store.Tx) error {
		var err error
		out, err = tx.TakeAway(f.ctx, sp)
		return err
	})
	return out
}

func (f *feedFx) feedHead(frame uuid.UUID) (int64, map[uuid.UUID]bool) {
	f.t.Helper()
	rows, err := f.st.Pool.Query(f.ctx, `SELECT entity_id, removed FROM feed WHERE frame_id = $1 AND kind = 'placement'`, frame)
	if err != nil {
		f.t.Fatal(err)
	}
	defer rows.Close()
	out := map[uuid.UUID]bool{}
	for rows.Next() {
		var id uuid.UUID
		var removed bool
		if err := rows.Scan(&id, &removed); err != nil {
			f.t.Fatal(err)
		}
		out[id] = removed
	}
	var seq int64
	_ = f.st.Pool.QueryRow(f.ctx, `SELECT latest_seq FROM frames WHERE id = $1`, frame).Scan(&seq)
	return seq, out
}

// Taking a family away: the posts leave, their placements are taken away (removed, in the trash for 30 days) unless
// something else holds them, the deliveries are kept aside; taking again takes nothing; direct placements on
// frames not left stay.
func TestTakeAwayFamily(t *testing.T) {
	f := newFeedFx(t)
	lies, tom := f.user("Lies"), f.user("Tom")
	feed := f.feed(lies)
	oma, kitchen := f.frame(lies, `{}`), f.frame(lies, `{}`)
	f.subscribe(feed, oma, "none", nil)
	f.subscribe(feed, kitchen, "none", nil)
	i1, i2, i3 := f.item(tom), f.item(tom), f.item(lies)
	post := f.post(feed, tom, i1, i2)
	liesPost := f.post(feed, lies, i3)
	f.drain()
	// Tom also sent i2 to Oma's frame directly: that placement is held twice.
	f.tx(func(tx *store.Tx) error {
		_, err := tx.Exec(f.ctx, `UPDATE placements SET held_directly = true WHERE frame_id = $1 AND item_id = $2`, oma, i2)
		return err
	})
	if n := len(f.live(oma)); n != 3 {
		t.Fatalf("oma before: %d", n)
	}

	out := f.takeAway(store.TakeAwaySpec{UserID: tom, UserName: "Tom", Scope: "feed", FeedID: &feed})
	if out.Taking == nil || out.Items != 2 || len(out.Feeds) != 1 || out.Feeds[0].Items != 2 || out.Feeds[0].PostIDs[0] != post {
		t.Fatalf("outcome: %+v", out)
	}
	// Oma keeps i2 (held directly) and Lies's photo; the kitchen keeps only Lies's photo.
	if l := f.live(oma); len(l) != 2 || l[i2].ID == uuid.Nil || l[i3].ID == uuid.Nil || len(l[i2].FeedIDs) != 0 {
		t.Fatalf("oma after: %+v", l)
	}
	if l := f.live(kitchen); len(l) != 1 || l[i3].ID == uuid.Nil {
		t.Fatalf("kitchen after: %+v", l)
	}
	frames := map[uuid.UUID]int{}
	for _, fr := range out.Frames {
		frames[fr.FrameID] = fr.Items
	}
	if frames[oma] != 1 || frames[kitchen] != 2 {
		t.Fatalf("frames: %v", frames)
	}
	if n := f.count(`SELECT count(*) FROM placements WHERE taking_id = $1 AND removed_at IS NOT NULL AND taken_away_at IS NOT NULL
		AND taken_away_until > now() + interval '29 days' AND taken_away_by = $2`, out.Taking.ID, tom); n != 3 {
		t.Fatalf("taken away placements: %d", n)
	}
	if n := f.count(`SELECT count(*) FROM feed_posts WHERE id = $1 AND removed_at IS NOT NULL AND removed_by = $2 AND taking_id = $3`, post, tom, out.Taking.ID); n != 1 {
		t.Fatal("post not taken away")
	}
	if n := f.count(`SELECT count(*) FROM feed_posts WHERE id = $1 AND removed_at IS NULL`, liesPost); n != 1 {
		t.Fatal("someone else's post went")
	}
	if n := f.count(`SELECT count(*) FROM taking_deliveries WHERE taking_id = $1`, out.Taking.ID); n != 4 {
		t.Fatalf("kept deliveries: %d", n)
	}
	if n := f.count(`SELECT count(*) FROM feed_deliveries WHERE post_id = $1`, post); n != 0 {
		t.Fatalf("deliveries left: %d", n)
	}
	if n := f.count(`SELECT count(*) FROM taking_items WHERE taking_id = $1`, out.Taking.ID); n != 2 {
		t.Fatalf("taking items: %d", n)
	}
	// The change feed removes the taken placements; the one that stayed is re-emitted.
	_, entries := f.feedHead(kitchen)
	removed := 0
	for _, r := range entries {
		if r {
			removed++
		}
	}
	if removed != 2 {
		t.Fatalf("kitchen feed removals: %v", entries)
	}
	// Idempotent: nothing left to take.
	if again := f.takeAway(store.TakeAwaySpec{UserID: tom, UserName: "Tom", Scope: "feed", FeedID: &feed}); again.Taking != nil {
		t.Fatalf("second taking: %+v", again.Taking)
	}
	// The frame groups and the family groups.
	g, err := f.st.FrameTakenAwayGroups(f.ctx, kitchen)
	if err != nil || len(g) != 1 || g[0].Count != 2 || g[0].UserName != "Tom" || g[0].UserDeleted {
		t.Fatalf("frame groups: %+v %v", g, err)
	}
	fg, err := f.st.FeedTakenAwayGroups(f.ctx, feed)
	if err != nil || len(fg) != 1 || fg[0].Count != 2 {
		t.Fatalf("feed groups: %+v %v", fg, err)
	}
	// Taking Oma's frame too now takes the direct placement of i2.
	out2 := f.takeAway(store.TakeAwaySpec{UserID: tom, UserName: "Tom", Scope: "frame", FrameIDs: []uuid.UUID{oma}})
	if out2.Taking == nil || out2.Items != 1 || len(f.live(oma)) != 1 {
		t.Fatalf("frame taking: %+v, oma %v", out2, f.live(oma))
	}
}

// A keep request on the family side brings posts and their placements back (as history); on the frame side the
// placements come back held directly while the post stays away.
func TestTakeAwayRestore(t *testing.T) {
	f := newFeedFx(t)
	lies, tom := f.user("Lies"), f.user("Tom")
	feed := f.feed(lies)
	oma, kitchen := f.frame(lies, `{}`), f.frame(lies, `{}`)
	subOma := f.subscribe(feed, oma, "none", nil)
	f.subscribe(feed, kitchen, "none", nil)
	i1, i2 := f.item(tom), f.item(tom)
	post := f.post(feed, tom, i1, i2)
	f.drain()
	out := f.takeAway(store.TakeAwaySpec{UserID: tom, UserName: "Tom", Scope: "feed", FeedID: &feed})
	tk := out.Taking.ID

	// Oma's frame keeps i1.
	var ids []uuid.UUID
	f.tx(func(tx *store.Tx) error {
		var err error
		ids, err = tx.RestoreOnFrame(f.ctx, tk, oma, []uuid.UUID{i1})
		return err
	})
	if len(ids) != 1 {
		t.Fatalf("restored on frame: %v", ids)
	}
	if l := f.live(oma); len(l) != 1 || !l[i1].Held || !l[i1].Backfill || l[i1].Suspended {
		t.Fatalf("oma after frame restore: %+v", l)
	}
	if n := f.count(`SELECT count(*) FROM feed_posts WHERE id = $1 AND removed_at IS NULL`, post); n != 0 {
		t.Fatal("post came back with a frame request")
	}
	// The family keeps i2: the post is back with i2 only, on both frames, delivered again.
	var posts []uuid.UUID
	var frames []store.RestoredFrame
	f.tx(func(tx *store.Tx) error {
		var err error
		posts, frames, err = tx.RestoreInFeed(f.ctx, tk, feed, []uuid.UUID{i2})
		return err
	})
	if len(posts) != 1 || posts[0] != post || len(frames) != 2 {
		t.Fatalf("restored in feed: %v %+v", posts, frames)
	}
	if n := f.count(`SELECT count(*) FROM feed_post_items WHERE post_id = $1 AND removed_at IS NULL`, post); n != 1 {
		t.Fatalf("live items: %d", n)
	}
	for _, fr := range []uuid.UUID{oma, kitchen} {
		l := f.live(fr)
		if l[i2].ID == uuid.Nil || !l[i2].Backfill || len(l[i2].FeedIDs) != 1 {
			t.Fatalf("frame %v after feed restore: %+v", fr, l)
		}
	}
	if n := f.count(`SELECT count(*) FROM feed_deliveries WHERE subscription_id = $1 AND post_id = $2 AND item_id = $3 AND state = 'placed'`, subOma, post, i2); n != 1 {
		t.Fatal("delivery not back")
	}
	// The kitchen's i1 is still taken away: the purge ends it, and the purge is final.
	var purged []store.RestoredFrame
	f.tx(func(tx *store.Tx) error {
		var err error
		purged, err = tx.PurgeTaking(f.ctx, tk, time.Now())
		return err
	})
	if len(purged) != 1 || purged[0].FrameID != kitchen || len(purged[0].PlacementIDs) != 1 {
		t.Fatalf("purged: %+v", purged)
	}
	if n := f.count(`SELECT count(*) FROM placements WHERE taking_id = $1 AND taken_away_at IS NOT NULL`, tk); n != 0 {
		t.Fatalf("still taken away: %d", n)
	}
	if n := f.count(`SELECT count(*) FROM taking_deliveries WHERE taking_id = $1`, tk); n != 0 {
		t.Fatalf("kept deliveries after purge: %d", n)
	}
	if n := f.count(`SELECT count(*) FROM takings WHERE id = $1 AND purged_at IS NOT NULL`, tk); n != 1 {
		t.Fatal("taking not marked purged")
	}
	due, err := f.st.DueTakings(f.ctx, time.Now().Add(60*24*time.Hour), 10)
	if err != nil || len(due) != 0 {
		t.Fatalf("due after purge: %v %v", due, err)
	}
}

// A frame manager purging one taken-away placement early: it never comes back, also not with a family restore.
func TestTakeAwayEarlyPurge(t *testing.T) {
	f := newFeedFx(t)
	lies, tom := f.user("Lies"), f.user("Tom")
	feed := f.feed(lies)
	oma := f.frame(lies, `{}`)
	f.subscribe(feed, oma, "none", nil)
	i1 := f.item(tom)
	f.post(feed, tom, i1)
	f.drain()
	out := f.takeAway(store.TakeAwaySpec{UserID: tom, UserName: "Tom", Scope: "feed", FeedID: &feed})
	pid := out.Frames[0].PlacementIDs[0]
	f.tx(func(tx *store.Tx) error {
		tk, err := tx.PurgeTakenAwayPlacement(f.ctx, oma, pid)
		if err == nil && (tk == nil || *tk != out.Taking.ID) {
			t.Errorf("purge taking: %v", tk)
		}
		return err
	})
	f.tx(func(tx *store.Tx) error {
		_, frames, err := tx.RestoreInFeed(f.ctx, out.Taking.ID, feed, []uuid.UUID{i1})
		if len(frames) != 0 {
			t.Errorf("came back after the purge: %+v", frames)
		}
		return err
	})
	if l := f.live(oma); len(l) != 0 {
		t.Fatalf("oma: %+v", l)
	}
	if n := f.count(`SELECT count(*) FROM feed_deliveries WHERE item_id = $1 AND state = 'declined'`, i1); n != 1 {
		t.Fatalf("tombstone: %d", n)
	}
}

// Account deletion with "leave": items go to the first family's owner, items only on frames to the frame's owner;
// the credit is frozen.
func TestHandOver(t *testing.T) {
	f := newFeedFx(t)
	lies, tom, oma := f.user("Lies"), f.user("Tom"), f.user("Oma")
	feed := f.feed(lies)
	omaFrame := f.frame(oma, `{}`)
	f.subscribe(feed, omaFrame, "none", nil)
	i1, i2, i3 := f.item(tom), f.item(tom), f.item(tom)
	f.post(feed, tom, i1)
	f.drain()
	f.direct(omaFrame, i2)
	var scopes []store.HandoverScope
	f.tx(func(tx *store.Tx) error {
		var err error
		scopes, err = tx.HandOver(f.ctx, tom, "Tom", time.Now())
		return err
	})
	if len(scopes) != 2 || scopes[0].Kind != "feed" || scopes[0].OwnerID != lies || scopes[0].Items != 1 ||
		scopes[1].Kind != "frame" || scopes[1].OwnerID != oma || scopes[1].Items != 1 {
		t.Fatalf("scopes: %+v", scopes)
	}
	for item, owner := range map[uuid.UUID]uuid.UUID{i1: lies, i2: oma, i3: tom} {
		var sender uuid.UUID
		var former *string
		if err := f.st.Pool.QueryRow(f.ctx, `SELECT sender_user_id, former_sender_name FROM items WHERE id = $1`, item).Scan(&sender, &former); err != nil {
			t.Fatal(err)
		}
		if sender != owner || (owner != tom) != (former != nil && *former == "Tom") {
			t.Fatalf("item %v: sender %v former %v", item, sender, former)
		}
	}
	row, err := f.st.GetItemRow(f.ctx, f.st.Pool, i1)
	if err != nil || row.SenderName != "Tom" || row.FormerSenderName == nil || *row.SenderUserID != lies {
		t.Fatalf("item row: %+v %v", row, err)
	}
}

// The memories lists keep only people the person shares or shared something with, and only posts.
func TestMemoryHidden(t *testing.T) {
	f := newFeedFx(t)
	lies, tom, stranger := f.user("Lies"), f.user("Tom"), f.user("Stranger")
	feed := f.feed(lies)
	post := f.post(feed, tom, f.item(tom))
	f.tx(func(tx *store.Tx) error {
		if err := tx.SetMemoryHiddenPeople(f.ctx, lies, []uuid.UUID{tom, stranger, lies}); err != nil {
			return err
		}
		return tx.SetMemoryHiddenPosts(f.ctx, lies, []uuid.UUID{post, store.NewID()})
	})
	people, posts, err := f.st.MemoryHidden(f.ctx, f.st.Pool, lies)
	if err != nil || len(people) != 1 || people[0] != tom || len(posts) != 1 || posts[0] != post {
		t.Fatalf("hidden: %v %v %v", people, posts, err)
	}
	// Tom left the family: he stays hideable (his posts are there); an empty list clears.
	f.exec(`DELETE FROM feed_members WHERE feed_id = $1 AND user_id = $2`, feed, tom)
	f.tx(func(tx *store.Tx) error { return tx.SetMemoryHiddenPeople(f.ctx, lies, []uuid.UUID{tom}) })
	if people, _, _ := f.st.MemoryHidden(f.ctx, f.st.Pool, lies); len(people) != 1 {
		t.Fatalf("after leaving: %v", people)
	}
	f.tx(func(tx *store.Tx) error { return tx.SetMemoryHiddenPeople(f.ctx, lies, nil) })
	if people, _, _ := f.st.MemoryHidden(f.ctx, f.st.Pool, lies); len(people) != 0 {
		t.Fatalf("after clearing: %v", people)
	}
	if ok, err := f.st.SharesLiveScope(f.ctx, f.st.Pool, lies, tom); err != nil || ok {
		t.Fatalf("shares after leaving: %v %v", ok, err)
	}
}
