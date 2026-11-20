package e2e

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
	"github.com/jeroentrappers/kinframe/server/internal/worker"
)

// imageSize decodes the pixel size of an image body.
func imageSize(t *testing.T, body []byte) media.Size {
	t.Helper()
	p := filepath.Join(t.TempDir(), "img")
	if err := os.WriteFile(p, body, 0o644); err != nil {
		t.Fatal(err)
	}
	tools := media.Tools{Vips: os.Getenv("KINFRAME_VIPS"), VipsHeader: os.Getenv("KINFRAME_VIPSHEADER")}
	h, err := tools.ImageHeader(context.Background(), p)
	if err != nil {
		t.Fatal(err)
	}
	return h.Size
}

// checkDisplay fetches an item's display_url (signed, no auth) and checks it
// is an uncropped WebP of the given size.
func (e *env) checkDisplay(it map[string]any, want media.Size) {
	e.t.Helper()
	u, _ := it["display_url"].(string)
	if !strings.HasPrefix(u, e.url+"/v1/media/signed/") {
		e.t.Fatalf("display url %q in %v", u, it)
	}
	body := e.get(u, "").body
	if _, mime := media.SniffBytes(body); mime != "image/webp" {
		e.t.Fatalf("display rendition is %s", mime)
	}
	if got := imageSize(e.t, body); got != want {
		e.t.Fatalf("display rendition %v, want %v", got, want)
	}
}

func (e *env) itemKeys(itemID string) (t256, t512, display *string) {
	e.t.Helper()
	if err := e.app.Pool.QueryRow(context.Background(), `SELECT thumb256_key, thumb512_key, display_key FROM items WHERE id = $1`, itemID).
		Scan(&t256, &t512, &display); err != nil {
		e.t.Fatal(err)
	}
	return
}

func (e *env) jobsOf(kind string) int {
	e.t.Helper()
	var n int
	if err := e.app.Pool.QueryRow(context.Background(), `SELECT count(*) FROM jobs WHERE kind = $1`, kind).Scan(&n); err != nil {
		e.t.Fatal(err)
	}
	return n
}

// TestDisplayRendition: new photos get an uncropped display rendition next to
// the square thumbnails (display_url, the display media variant); the backfill
// gives it to items from before, in batches, without touching their thumbnails,
// and does nothing on a second run.
func TestDisplayRendition(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	ctx := context.Background()
	u := e.user("d@x.org", "Dora")
	_, frameID := e.claim(u)
	sizes := []struct{ w, h int }{{3000, 2000}, {1000, 3000}, {800, 600}}
	// 3000x2000: master 2560x1707, display 1600x1067; 1000x3000: master 853x2560, display 533x1600; small: as is.
	want := []media.Size{{W: 1600, H: 1067}, {W: 533, H: 1600}, {W: 800, H: 600}}
	var ids, sends []string
	for i, s := range sizes {
		up := e.upload(u, mediatest.JPEG(s.w, s.h, 0, "", false), "p.jpg", "image/jpeg")
		sendID := e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up})
		it := e.waitSend(u, sendID)["items"].([]any)[0].(map[string]any)
		e.checkDisplay(it, want[i])
		ids, sends = append(ids, it["id"].(string)), append(sends, sendID)
	}
	item := func(i int) map[string]any {
		return e.get("/v1/sends/"+sends[i], u.token).m(t)["items"].([]any)[0].(map[string]any)
	}
	loc := e.expect(req{method: "GET", path: "/v1/items/" + ids[0] + "/media/display", token: u.token}, 302).Header.Get("Location")
	if got := imageSize(t, e.get(loc, "").body); got != want[0] {
		t.Fatalf("display variant %v", got)
	}

	// Items from before display renditions: no display_url, the variant is not there.
	type keys struct{ t256, t512 string }
	before := map[string]keys{}
	for _, id := range ids {
		t256, t512, _ := e.itemKeys(id)
		before[id] = keys{*t256, *t512}
	}
	if _, err := e.app.Pool.Exec(ctx, `UPDATE items SET display_key = NULL`); err != nil {
		t.Fatal(err)
	}
	it := item(0)
	if _, ok := it["display_url"]; ok || it["thumb_url"] == nil {
		t.Fatalf("item without display rendition: %v", it)
	}
	if r := e.do(req{method: "GET", path: "/v1/items/" + ids[0] + "/media/display", token: u.token}); r.StatusCode != 404 {
		t.Fatalf("display variant before backfill: %d", r.StatusCode)
	}

	// The backfill, one item per batch: queued once, then run to the end.
	if ok, err := worker.EnqueueDisplayBackfill(ctx, e.app.Store, 1, 10*time.Millisecond); err != nil || !ok {
		t.Fatalf("enqueue: %v %v", ok, err)
	}
	if ok, err := worker.EnqueueDisplayBackfill(ctx, e.app.Store, 1, 10*time.Millisecond); err != nil || ok {
		t.Fatalf("second enqueue while queued: %v %v", ok, err)
	}
	e.app.Worker.Wake()
	eventually(t, 60*time.Second, "display backfill", func() bool {
		n, err := e.app.Store.CountItemsMissingDisplay(ctx)
		return err == nil && n == 0 && e.jobsOf("display_backfill") == 0
	})
	displays := map[string]string{}
	for i, id := range ids {
		t256, t512, d := e.itemKeys(id)
		if d == nil || *t256 != before[id].t256 || *t512 != before[id].t512 {
			t.Fatalf("after backfill %s: %v %v %v (thumbs were %v)", id, *t256, *t512, d, before[id])
		}
		displays[id] = *d
		e.checkDisplay(item(i), want[i])
	}

	// A second run finds nothing to do.
	if ok, err := worker.EnqueueDisplayBackfill(ctx, e.app.Store, 0, 0); err != nil || !ok {
		t.Fatalf("re-enqueue: %v %v", ok, err)
	}
	e.app.Worker.Wake()
	eventually(t, 30*time.Second, "second backfill", func() bool { return e.jobsOf("display_backfill") == 0 })
	for _, id := range ids {
		if _, _, d := e.itemKeys(id); d == nil || *d != displays[id] {
			t.Fatalf("second backfill changed %s: %v", id, d)
		}
	}
}
