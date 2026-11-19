package store_test

import (
	"testing"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Family social in the store (README §5.18): circle posts reach only the frames their circle reaches when they
// are placed (fanout and backfill), and frame posts follow their placements (migration 00025).

func (f *feedFx) circle(feed, creator uuid.UUID, members, frames []uuid.UUID) uuid.UUID {
	f.t.Helper()
	id := store.NewID()
	f.tx(func(tx *store.Tx) error {
		return tx.CreateCircle(f.ctx, store.Circle{ID: id, FeedID: feed, Name: "Kids " + id.String()[:8]}, creator, members, frames)
	})
	return id
}

func (f *feedFx) circlePost(feed, circle, poster uuid.UUID, items ...uuid.UUID) uuid.UUID {
	f.t.Helper()
	id := f.post(feed, poster, items...)
	f.exec(`UPDATE feed_posts SET circle_id = $2 WHERE id = $1`, id, circle) // before its fanout runs (drain)
	return id
}

func TestSocialCircleReach(t *testing.T) {
	f := newFeedFx(t)
	anna, ben, carl := f.user("Anna"), f.user("Ben"), f.user("Carl")
	feed := f.feed(anna)
	f.tx(func(tx *store.Tx) error {
		if err := tx.AddFeedMember(f.ctx, feed, ben, "poster"); err != nil {
			return err
		}
		return tx.AddFeedMember(f.ctx, feed, carl, "poster")
	})
	frA, frB, frC := f.frame(ben, `{}`), f.frame(carl, `{}`), f.frame(anna, `{}`)
	// The circle: Ben (manages frame A); frame C is one of its extra frames; Carl (frame B) is not in it.
	circle := f.circle(feed, ben, nil, []uuid.UUID{frC})

	old := f.item(ben)
	f.circlePost(feed, circle, ben, old)
	whole := f.item(carl)
	f.post(feed, carl, whole)
	f.drain()
	f.subscribe(feed, frA, "all", nil)
	subB := f.subscribe(feed, frB, "all", nil)
	f.subscribe(feed, frC, "all", nil)
	f.drain()
	for _, c := range []struct {
		frame       uuid.UUID
		old, family bool
	}{{frA, true, true}, {frB, false, true}, {frC, true, true}} {
		l := f.live(c.frame)
		if _, ok := l[old]; ok != c.old {
			t.Fatalf("backfill: circle post on %v: %v", c.frame, ok)
		}
		if _, ok := l[whole]; ok != c.family {
			t.Fatalf("backfill: whole-family post on %v: %v", c.frame, ok)
		}
	}
	if n := f.count(`SELECT backfill_total FROM subscriptions WHERE id = $1`, subB); n != 1 {
		t.Fatalf("frame B's backfill counts the circle post: %d", n)
	}

	// New circle posts: the fanout skips frames the circle doesn't reach.
	fresh := f.item(ben)
	f.circlePost(feed, circle, ben, fresh)
	f.drain()
	if _, ok := f.live(frA)[fresh]; !ok {
		t.Fatal("fanout: frame A")
	}
	if _, ok := f.live(frC)[fresh]; !ok {
		t.Fatal("fanout: frame C (circle_frames)")
	}
	if _, ok := f.live(frB)[fresh]; ok {
		t.Fatal("fanout: frame B got a circle post")
	}

	// Carl joins the circle: nothing moves until frame B backfills again, which then places the circle's posts.
	f.tx(func(tx *store.Tx) error {
		_, err := tx.AddCircleMembers(f.ctx, circle, feed, []uuid.UUID{carl}, ben)
		return err
	})
	f.drain()
	if _, ok := f.live(frB)[fresh]; ok {
		t.Fatal("a member change moved a placement")
	}
	f.with(subB, func(tx *store.Tx, sub *store.Subscription) error {
		return tx.SetSubscriptionHistory(f.ctx, sub, "all", nil)
	})
	f.drain()
	if l := f.live(frB); len(l) != 3 {
		t.Fatalf("frame B after its backfill: %v", l)
	}
	// Carl leaves again: the next backfill never trims what is there.
	f.tx(func(tx *store.Tx) error {
		_, err := tx.RemoveCircleMember(f.ctx, circle, carl)
		return err
	})
	f.with(subB, func(tx *store.Tx, sub *store.Subscription) error {
		return tx.SetSubscriptionHistory(f.ctx, sub, "all", nil)
	})
	f.drain()
	if l := f.live(frB); len(l) != 3 {
		t.Fatalf("frame B after the circle changed: %v", l)
	}
	// The circle's chat was made by the database and follows its members.
	if n := f.count(`SELECT count(*) FROM conversation_members cm JOIN conversations c ON c.id = cm.conversation_id
		WHERE c.kind = 'circle' AND c.circle_id = $1`, circle); n != 1 {
		t.Fatalf("circle chat members: %d", n)
	}
}

func TestSocialFramePostTriggers(t *testing.T) {
	f := newFeedFx(t)
	anna, ben := f.user("Anna"), f.user("Ben")
	frame := f.frame(anna, `{}`)
	send := store.NewID()
	i1, i2 := f.item(ben), f.item(ben)
	place := func(sendID, item uuid.UUID, sharedBy *uuid.UUID) uuid.UUID {
		id := store.NewID()
		f.tx(func(tx *store.Tx) error {
			return tx.CreatePlacement(f.ctx, store.Placement{ID: id, FrameID: frame, ItemID: item, SendID: &sendID, Status: "pending", SharedByUserID: sharedBy})
		})
		return id
	}
	live := func(sendID uuid.UUID) int {
		return f.count(`SELECT count(*) FROM frame_posts WHERE send_id = $1 AND removed_at IS NULL`, sendID)
	}
	f.tx(func(tx *store.Tx) error { return tx.CreateSend(f.ctx, send, ben) })
	p1, p2 := place(send, i1, nil), place(send, i2, nil)
	if live(send) != 1 || f.count(`SELECT count(*) FROM frame_posts WHERE send_id = $1 AND sender_user_id = $2`, send, ben) != 1 {
		t.Fatal("one frame post per send and frame")
	}
	remove := func(id uuid.UUID) {
		f.tx(func(tx *store.Tx) error { _, err := tx.RemovePlacements(f.ctx, `p.id = $1`, id); return err })
	}
	remove(p1)
	if live(send) != 1 {
		t.Fatal("removed with a placement left")
	}
	remove(p2)
	if live(send) != 0 {
		t.Fatal("frame post stays without placements")
	}
	place(send, i1, nil) // placed again (a forward to the same frame)
	if live(send) != 1 || f.count(`SELECT count(*) FROM frame_posts WHERE send_id = $1`, send) != 1 {
		t.Fatal("frame post not revived")
	}

	// Photo-source sends and shares make none.
	src := store.NewID()
	f.tx(func(tx *store.Tx) error { return tx.CreateSend(f.ctx, src, ben) })
	f.exec(`UPDATE sends SET via = 'google_photos' WHERE id = $1`, src)
	place(src, f.item(ben), nil)
	if live(src) != 0 {
		t.Fatal("frame post for a Google import")
	}
	other := store.NewID()
	f.tx(func(tx *store.Tx) error { return tx.CreateSend(f.ctx, other, ben) })
	place(other, f.item(ben), &anna)
	if live(other) != 0 {
		t.Fatal("frame post for a share")
	}
}
