package store_test

import (
	"testing"
	"time"

	"github.com/google/uuid"
)

// The weekly summary without the people and memories someone left out.
func TestWeeklySummaryExcept(t *testing.T) {
	f := newFeedFx(t)
	lies, tom, ben := f.user("Lies"), f.user("Tom"), f.user("Ben")
	feed := f.feed(lies)
	item := f.item(lies)
	post := f.post(feed, lies, item)
	f.exec(`INSERT INTO reactions (target_kind, target_id, scope_id, user_id, emoji) VALUES
		('post', $1, $1, $2, 'love'), ('post_item', $3, $1, $2, 'love'), ('post_item', $3, $1, $4, 'wow')`, post, tom, item, ben)
	from, to := time.Now().Add(-time.Hour), time.Now().Add(time.Hour)
	r, _, err := f.st.PostReactionsAndCommentsExcept(f.ctx, lies, from, to, nil)
	if err != nil || r != 3 {
		t.Fatalf("all reactions: %d %v", r, err)
	}
	if r, _, _ := f.st.PostReactionsAndCommentsExcept(f.ctx, lies, from, to, []uuid.UUID{tom}); r != 1 {
		t.Fatalf("without tom: %d", r)
	}
	if id, err := f.st.TopItemExcept(f.ctx, lies, from, to, nil, nil); err != nil || id == nil || *id != item {
		t.Fatalf("top item: %v %v", id, err)
	}
	if id, _ := f.st.TopItemExcept(f.ctx, lies, from, to, []uuid.UUID{tom, ben}, nil); id != nil {
		t.Fatalf("top item without the people: %v", id)
	}
	if id, _ := f.st.TopItemExcept(f.ctx, lies, from, to, nil, []uuid.UUID{post}); id != nil {
		t.Fatalf("top item of a hidden memory: %v", id)
	}
}
