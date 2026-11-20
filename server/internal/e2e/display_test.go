package e2e

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
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

// TestDisplayRendition: new photos get an uncropped display rendition next to
// the square thumbnails (display_url, the display media variant).
func TestDisplayRendition(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	u := e.user("d@x.org", "Dora")
	_, frameID := e.claim(u)
	sizes := []struct{ w, h int }{{3000, 2000}, {1000, 3000}, {800, 600}}
	// 3000x2000: master 2560x1707, display 1600x1067; 1000x3000: master 853x2560, display 533x1600; small: as is.
	want := []media.Size{{W: 1600, H: 1067}, {W: 533, H: 1600}, {W: 800, H: 600}}
	var ids []string
	for i, s := range sizes {
		up := e.upload(u, mediatest.JPEG(s.w, s.h, 0, "", false), "p.jpg", "image/jpeg")
		sendID := e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up})
		it := e.waitSend(u, sendID)["items"].([]any)[0].(map[string]any)
		e.checkDisplay(it, want[i])
		ids = append(ids, it["id"].(string))
	}
	loc := e.expect(req{method: "GET", path: "/v1/items/" + ids[0] + "/media/display", token: u.token}, 302).Header.Get("Location")
	if got := imageSize(t, e.get(loc, "").body); got != want[0] {
		t.Fatalf("display variant %v", got)
	}
}
