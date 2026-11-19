package store_test

import (
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Photo source rows (docs/spec/photo-sources.md §15): tombstones when people
// remove synced photos, and the duplicate key per target.

func (f *feedFx) albumSource(frame, owner uuid.UUID) (album, source uuid.UUID) {
	f.t.Helper()
	f.tx(func(tx *store.Tx) error {
		var err error
		album, err = tx.CreateAlbum(f.ctx, frame, store.AlbumWrite{Name: ptrStr("Garden")})
		if err != nil {
			return err
		}
		source = store.NewID()
		return tx.CreatePhotoSource(f.ctx, store.PhotoSource{ID: source, Provider: "immich_link", OwnerUserID: &owner, FrameID: &frame,
			AlbumID: &album, Name: "Garden", UpstreamKey: "k1", UpstreamHost: "photos.example.org", History: "all",
			OnUpstreamDelete: "mirror", IncludeVideos: true, NotifyReactions: true, NextSyncAt: time.Now()})
	})
	return
}

func ptrStr(s string) *string { return &s }

func (f *feedFx) state(source uuid.UUID, upstream string) string {
	f.t.Helper()
	r, err := f.st.GetSourceItem(f.ctx, f.st.Q(), source, upstream)
	if err != nil {
		f.t.Fatal(err)
	}
	return r.State
}

func TestPhotoSourceTombstones(t *testing.T) {
	f := newFeedFx(t)
	anna := f.user("Anna")
	frame := f.frame(anna, `{}`)
	album, src := f.albumSource(frame, anna)
	var pls []uuid.UUID
	for _, up := range []string{"a", "b", "c"} {
		item := f.item(anna)
		pid := store.NewID()
		pls = append(pls, pid)
		f.tx(func(tx *store.Tx) error {
			if err := tx.CreatePlacement(f.ctx, store.Placement{ID: pid, FrameID: frame, ItemID: item, Status: "pending"}); err != nil {
				return err
			}
			if err := tx.AddToAlbum(f.ctx, frame, album, []uuid.UUID{pid}); err != nil {
				return err
			}
			return f.st.PutSourceItem(f.ctx, tx, &store.SourceItem{SourceID: src, UpstreamID: up, State: "imported", ItemID: &item})
		})
	}
	// Purged on the frame, taken out of the album: removed_locally.
	f.tx(func(tx *store.Tx) error { _, err := tx.RemovePlacements(f.ctx, `p.id = $1`, pls[0]); return err })
	f.tx(func(tx *store.Tx) error { return tx.RemoveFromAlbum(f.ctx, frame, album, []uuid.UUID{pls[1]}) })
	if f.state(src, "a") != "removed_locally" || f.state(src, "b") != "removed_locally" || f.state(src, "c") != "imported" {
		t.Fatalf("states: %s %s %s", f.state(src, "a"), f.state(src, "b"), f.state(src, "c"))
	}
	// A sync writing its stale in-memory row never resurrects a tombstone...
	if err := f.st.PutSourceItem(f.ctx, f.st.Q(), &store.SourceItem{SourceID: src, UpstreamID: "a", State: "imported"}); err != nil {
		t.Fatal(err)
	}
	if f.state(src, "a") != "removed_locally" {
		t.Fatal("tombstone overwritten")
	}
	// ...except its own mirror removal.
	if err := f.st.PutSourceItem(f.ctx, f.st.Q(), &store.SourceItem{SourceID: src, UpstreamID: "a", State: "gone_upstream"}); err != nil {
		t.Fatal(err)
	}
	if f.state(src, "a") != "gone_upstream" {
		t.Fatal("mirror removal not recorded")
	}
	counts, err := f.st.PhotoSourceCounts(f.ctx, []uuid.UUID{src})
	if err != nil {
		t.Fatal(err)
	}
	if c := counts[src]; c.Imported != 1 || c.RemovedLocally != 1 || c.GoneUpstream != 1 {
		t.Fatalf("counts %+v", c)
	}
	// The album reports its sources; the same outside album twice is refused by the index.
	a, err := f.st.GetAlbum(f.ctx, f.st.Q(), frame, album)
	if err != nil || a.PhotoSourceCount != 1 {
		t.Fatalf("album: %+v %v", a, err)
	}
	err = f.st.InTx(f.ctx, func(tx *store.Tx) error {
		return tx.CreatePhotoSource(f.ctx, store.PhotoSource{ID: store.NewID(), Provider: "immich_link", OwnerUserID: &anna, FrameID: &frame,
			AlbumID: &album, Name: "x", UpstreamKey: "k1", UpstreamHost: "h", History: "all", OnUpstreamDelete: "mirror", NextSyncAt: time.Now()})
	})
	if err == nil {
		t.Fatal("duplicate source accepted")
	}
	if dup, err := f.st.SourceWithKey(f.ctx, f.st.Q(), album, "k1", nil); err != nil || dup == nil || *dup != src {
		t.Fatalf("SourceWithKey: %v %v", dup, err)
	}
	// Claiming a run is exclusive until it ends (or goes stale).
	now := time.Now()
	if ok, _ := f.st.ClaimPhotoSourceRun(f.ctx, src, now, time.Hour); !ok {
		t.Fatal("first claim")
	}
	if ok, _ := f.st.ClaimPhotoSourceRun(f.ctx, src, now, time.Hour); ok {
		t.Fatal("second claim while running")
	}
	if ok, _ := f.st.ClaimPhotoSourceRun(f.ctx, src, now.Add(2*time.Hour), time.Hour); !ok {
		t.Fatal("stale claim")
	}
	if due, err := f.st.DuePhotoSources(f.ctx, now.Add(time.Hour), []string{"immich_link"}, 10); err != nil || len(due) != 0 {
		t.Fatalf("running source due: %v %v", due, err)
	}
	if err := f.st.EndPhotoSourceRun(f.ctx, src); err != nil {
		t.Fatal(err)
	}
	if due, err := f.st.DuePhotoSources(f.ctx, now.Add(time.Hour), []string{"immich_link"}, 10); err != nil || len(due) != 1 || due[0].Host != "photos.example.org" {
		t.Fatalf("due: %v %v", due, err)
	}
	if due, _ := f.st.DuePhotoSources(f.ctx, now.Add(time.Hour), []string{"icloud_link"}, 10); len(due) != 0 {
		t.Fatal("disabled provider due")
	}
}

func TestFeedPostItemTombstone(t *testing.T) {
	f := newFeedFx(t)
	anna := f.user("Anna")
	feed := f.feed(anna)
	item := f.item(anna)
	post := f.post(feed, anna, item)
	src := store.NewID()
	f.tx(func(tx *store.Tx) error {
		if err := tx.CreatePhotoSource(f.ctx, store.PhotoSource{ID: src, Provider: "icloud_link", OwnerUserID: &anna, FeedID: &feed, Name: "x",
			UpstreamKey: "k", UpstreamHost: "icloud.com", History: "none", OnUpstreamDelete: "keep", NextSyncAt: time.Now()}); err != nil {
			return err
		}
		return f.st.PutSourceItem(f.ctx, tx, &store.SourceItem{SourceID: src, UpstreamID: "g", State: "imported", ItemID: &item})
	})
	f.unsend(post, []uuid.UUID{item})
	if f.state(src, "g") != "removed_locally" {
		t.Fatalf("state %s", f.state(src, "g"))
	}
}
