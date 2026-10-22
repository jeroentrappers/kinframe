package e2e

import (
	"context"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/blob"
	"github.com/jeroentrappers/kinframe/server/internal/blob/blobtest"
	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

// withS3 runs the server on a fresh bucket of the test S3 server.
func withS3(t *testing.T, redirect string) option {
	s3 := blobtest.Config(t)
	return func(c *config.Config) {
		c.Blob = config.BlobConfig{Backend: config.BlobS3, S3: s3, MediaRedirect: redirect}
	}
}

// Only processed media may reach the bucket: masters, posters, frame
// renditions (and their posters), thumbnails and avatars. Never originals.
var allowedKey = regexp.MustCompile(`^(media/[0-9a-f-]{36}/(master\.(webp|mp4)|poster\.webp|frame_[0-9x_v]+(\.webp|\.mp4|-poster\.webp))` +
	`|items/[0-9a-f-]{36}/thumb_(256|512)-[0-9a-f-]{8}\.webp|avatars/[0-9a-f-]{36}/[A-Za-z0-9_-]+\.webp)$`)

func checkBucket(t *testing.T, e *env, wantPrefixes ...string) {
	t.Helper()
	s, err := e.app.Cfg.Blob.Open(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	var keys []string
	if err := s.List(context.Background(), "", func(i blob.Info) error { keys = append(keys, i.Key); return nil }); err != nil {
		t.Fatal(err)
	}
	for _, k := range keys {
		if !allowedKey.MatchString(k) {
			t.Errorf("unexpected object in bucket: %s", k)
		}
	}
	for _, p := range wantPrefixes {
		found := false
		for _, k := range keys {
			found = found || strings.Contains(k, p)
		}
		if !found {
			t.Errorf("no %q object in bucket: %v", p, keys)
		}
	}
	// Nothing processed lands on local disk, and every job cleaned its scratch.
	if _, err := os.Stat(filepath.Join(e.app.Cfg.DataDir, "media")); !os.IsNotExist(err) {
		t.Errorf("local media dir exists with the s3 backend: %v", err)
	}
	if left, _ := os.ReadDir(filepath.Join(e.app.Cfg.DataDir, "tmp")); len(left) > 0 {
		t.Errorf("scratch not cleaned: %d entries", len(left))
	}
}

func TestSendLoopS3(t *testing.T) {
	requireMedia(t)
	e := newEnv(t, withS3(t, ""))
	sendLoop(t, e)
	checkBucket(t, e, "/master.webp", "/frame_", "/thumb_256-", "/thumb_512-")
}

func TestVideoSendS3(t *testing.T) {
	requireMedia(t)
	e := newEnv(t, withS3(t, ""))
	videoSend(t, e)
	checkBucket(t, e, "/master.mp4", "/poster.webp", "-poster.webp")
}

func TestPresignedRedirect(t *testing.T) {
	requireMedia(t)
	e := newEnv(t, withS3(t, config.MediaRedirectPresigned))
	u := e.user("p@x.org", "Pia")
	d, frameID := e.claim(u)
	up := e.upload(u, mediatest.JPEG(1600, 1200, 0, "", false), "a.jpg", "image/jpeg")
	sendID := e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up})
	s := e.waitSend(u, sendID)
	thumb := s["items"].([]any)[0].(map[string]any)["thumb_url"].(string)

	noFollow := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := noFollow.Get(thumb)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	loc := resp.Header.Get("Location")
	if resp.StatusCode != http.StatusFound || !strings.Contains(loc, "X-Amz-Signature=") {
		t.Fatalf("signed media: %d %q", resp.StatusCode, loc)
	}
	// Following it yields the image straight from the bucket.
	th, err := http.Get(loc)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(th.Body)
	th.Body.Close()
	if k, _ := media.SniffBytes(body); th.StatusCode != 200 || k != media.Photo || th.Header.Get("Content-Type") != "image/webp" {
		t.Fatalf("presigned thumb: %d %s", th.StatusCode, th.Header.Get("Content-Type"))
	}

	// Frames are still served by the server itself, with the sha256 ETag.
	ch := e.get("/v1/device/changes?since=0", d.token).m(t)
	m := dig(changesOf(ch, "placement.upsert")[0], "placement", "media").(map[string]any)
	dl := e.get(m["url"].(string), d.token)
	if dl.StatusCode != 200 || dl.Header.Get("ETag") != `"`+m["sha256"].(string)+`"` {
		t.Fatalf("frame download: %d %s", dl.StatusCode, dl.Header.Get("ETag"))
	}
	e.expect(req{method: "GET", path: m["url"].(string), token: d.token, header: map[string]string{"If-None-Match": dl.Header.Get("ETag")}}, http.StatusNotModified)
	e.expect(req{method: "GET", path: m["url"].(string), token: d.token, header: map[string]string{"Range": "bytes=10-19"}}, http.StatusPartialContent)
}
