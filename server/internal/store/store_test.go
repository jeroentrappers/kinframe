package store_test

import (
	"context"
	"encoding/json"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/testdb"
)

func TestMain(m *testing.M) {
	code := m.Run()
	testdb.Stop()
	os.Exit(code)
}

func newFrame(t *testing.T, st *store.Store) uuid.UUID {
	t.Helper()
	id := store.NewID()
	err := st.InTx(context.Background(), func(tx *store.Tx) error {
		_, err := tx.CreateFrame(context.Background(), store.Frame{ID: id, Name: "f", Settings: json.RawMessage(`{}`)})
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	return id
}

// Concurrent writers must produce gap-free, unique seqs, and the compacted
// feed keeps only the latest entry per entity.
func TestFeedConcurrency(t *testing.T) {
	pool, _ := testdb.New(t)
	st := store.New(pool)
	ctx := context.Background()
	frame := newFrame(t, st)
	entities := []uuid.UUID{store.NewID(), store.NewID(), store.NewID()}
	var wg sync.WaitGroup
	for i := 0; i < 30; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			err := st.InTx(ctx, func(tx *store.Tx) error {
				return tx.FeedPut(ctx, frame, store.FeedPlacement, entities[i%3], i%7 == 0)
			})
			if err != nil {
				t.Error(err)
			}
		}(i)
	}
	wg.Wait()
	entries, err := st.FeedSince(ctx, frame, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 3 {
		t.Fatalf("compaction: %d entries", len(entries))
	}
	f, _ := st.GetFrame(ctx, st.Q(), frame)
	if f.LatestSeq != 30 || entries[2].Seq != 30 {
		t.Fatalf("latest seq %d, last entry %d", f.LatestSeq, entries[2].Seq)
	}
	// Pruning old removals raises the floor.
	if _, err := pool.Exec(ctx, `UPDATE feed SET removed = true, updated_at = now() - interval '40 days' WHERE entity_id = $1`, entities[0]); err != nil {
		t.Fatal(err)
	}
	if _, err := st.PruneFeed(ctx, 30*24*time.Hour); err != nil {
		t.Fatal(err)
	}
	f, _ = st.GetFrame(ctx, st.Q(), frame)
	if f.FeedFloorSeq == 0 {
		t.Fatal("floor not raised")
	}
}

func TestJobQueueSkipLocked(t *testing.T) {
	pool, _ := testdb.New(t)
	st := store.New(pool)
	ctx := context.Background()
	for i := 0; i < 5; i++ {
		if err := st.InTx(ctx, func(tx *store.Tx) error { return tx.Enqueue(ctx, store.JobThumbs, map[string]int{"n": i}) }); err != nil {
			t.Fatal(err)
		}
	}
	var mu sync.Mutex
	seen := map[int64]bool{}
	var wg sync.WaitGroup
	for w := 0; w < 5; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			j, err := st.ClaimJob(ctx, time.Minute)
			if err != nil || j == nil {
				t.Errorf("claim: %v %v", j, err)
				return
			}
			mu.Lock()
			defer mu.Unlock()
			if seen[j.ID] {
				t.Errorf("job %d claimed twice", j.ID)
			}
			seen[j.ID] = true
		}()
	}
	wg.Wait()
	if j, _ := st.ClaimJob(ctx, time.Minute); j != nil {
		t.Fatal("leased job claimed again")
	}
	for id := range seen {
		if err := st.RetryJob(ctx, id, 0, context.Canceled); err != nil {
			t.Fatal(err)
		}
		break
	}
	if j, _ := st.ClaimJob(ctx, time.Minute); j == nil || j.Attempts != 2 {
		t.Fatalf("retried job: %+v", j)
	}
	if n, _ := st.QueueLength(ctx); n != 5 {
		t.Fatalf("queue length %d", n)
	}
}
