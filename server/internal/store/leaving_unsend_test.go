package store_test

import (
	"testing"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Unsending a send whose photos wait in a trash after a taking purges them at once; no keep request brings them
// back.
func TestTakeAwayUnsend(t *testing.T) {
	f := newFeedFx(t)
	lies, tom := f.user("Lies"), f.user("Tom")
	feed := f.feed(lies)
	oma := f.frame(lies, `{}`)
	f.subscribe(feed, oma, "none", nil)
	item, send, post := f.item(tom), store.NewID(), store.NewID()
	f.tx(func(tx *store.Tx) error {
		if err := tx.CreateSend(f.ctx, send, tom); err != nil {
			return err
		}
		if err := tx.AddSendItem(f.ctx, send, item, 0); err != nil {
			return err
		}
		if err := tx.AddFeedMember(f.ctx, feed, tom, "poster"); err != nil {
			return err
		}
		if err := tx.CreateFeedPost(f.ctx, post, feed, &send, tom, []uuid.UUID{item}); err != nil {
			return err
		}
		return tx.Enqueue(f.ctx, store.JobFeedFanout, store.PostJob{PostID: post})
	})
	f.drain()
	out := f.takeAway(store.TakeAwaySpec{UserID: tom, UserName: "Tom", Scope: "feed", FeedID: &feed})
	var purged []store.RestoredFrame
	f.tx(func(tx *store.Tx) error {
		var err error
		purged, err = tx.PurgeTakenAwayOfSend(f.ctx, send)
		return err
	})
	if len(purged) != 1 || purged[0].FrameID != oma {
		t.Fatalf("purged: %+v", purged)
	}
	f.tx(func(tx *store.Tx) error {
		posts, frames, err := tx.RestoreInFeed(f.ctx, out.Taking.ID, feed, []uuid.UUID{item})
		if len(posts) != 0 || len(frames) != 0 {
			t.Errorf("restored after unsend: %v %+v", posts, frames)
		}
		return err
	})
	if g, _ := f.st.FeedTakenAwayGroups(f.ctx, feed); len(g) != 0 {
		t.Fatalf("family groups after unsend: %+v", g)
	}
}
