package blob_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/blob"
	"github.com/jeroentrappers/kinframe/server/internal/blob/blobtest"
)

func TestMain(m *testing.M) {
	code := m.Run()
	blobtest.Stop()
	os.Exit(code)
}

func TestKeys(t *testing.T) {
	l := blob.Local{Root: t.TempDir()}
	for _, bad := range []string{"", "/etc/passwd", "../x", "a/../../x", "a/./b", "a//b", "a/", `a\b`} {
		if _, err := l.Path(bad); err == nil {
			t.Errorf("key %q accepted", bad)
		}
	}
	if _, err := l.Path("media/x/master.webp"); err != nil {
		t.Error(err)
	}
}

func TestLocalPutFileMoves(t *testing.T) {
	ctx := context.Background()
	l := blob.Local{Root: t.TempDir()}
	src := filepath.Join(t.TempDir(), "f")
	_ = os.WriteFile(src, []byte("hi"), 0o644)
	if err := l.PutFile(ctx, "media/x/master.webp", src, blob.PutOptions{}); err != nil {
		t.Fatal(err)
	}
	p, _ := l.Path("media/x/master.webp")
	if b, _ := os.ReadFile(p); string(b) != "hi" {
		t.Fatal("content")
	}
	// Old versions kept scratch files in <root>/tmp and wrote "<key>.tmp"; List skips both.
	_ = os.MkdirAll(filepath.Join(l.Root, "tmp", "job-1"), 0o755)
	_ = os.WriteFile(filepath.Join(l.Root, "tmp", "job-1", "x"), []byte("x"), 0o644)
	_ = os.WriteFile(p+".tmp", []byte("x"), 0o644)
	var keys []string
	_ = l.List(ctx, "", func(i blob.Info) error { keys = append(keys, i.Key); return nil })
	if strings.Join(keys, ",") != "media/x/master.webp" {
		t.Fatalf("list: %v", keys)
	}
}

func TestFS(t *testing.T) { conformance(t, blob.Local{Root: t.TempDir()}) }

func TestS3(t *testing.T) {
	s := blobtest.New(t)
	conformance(t, s)
}

func TestS3Prefix(t *testing.T) {
	cfg := blobtest.Config(t)
	ctx := context.Background()
	cfg.Prefix = "tenant-a"
	a, err := blob.NewS3(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	cfg.Prefix = "tenant-b/"
	b, err := blob.NewS3(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := a.Put(ctx, "k/one", strings.NewReader("a"), 1, blob.PutOptions{}); err != nil {
		t.Fatal(err)
	}
	if _, err := b.Stat(ctx, "k/one"); !errors.Is(err, blob.ErrNotFound) {
		t.Fatalf("prefixes leak: %v", err)
	}
	var keys []string
	_ = a.List(ctx, "", func(i blob.Info) error { keys = append(keys, i.Key); return nil })
	if strings.Join(keys, ",") != "k/one" {
		t.Fatalf("list with prefix: %v", keys)
	}
}

func TestS3MissingBucket(t *testing.T) {
	cfg := blobtest.Config(t)
	cfg.CreateBucket = false
	if _, err := blob.NewS3(context.Background(), cfg); err == nil || !strings.Contains(err.Error(), "does not exist") {
		t.Fatalf("missing bucket: %v", err)
	}
}

func TestS3Presign(t *testing.T) {
	s := blobtest.New(t)
	ctx := context.Background()
	if err := s.Put(ctx, "a/b.webp", strings.NewReader("hello"), 5, blob.PutOptions{}); err != nil {
		t.Fatal(err)
	}
	u, err := s.PresignGet(ctx, "a/b.webp", time.Minute, "image/webp")
	if err != nil {
		t.Fatal(err)
	}
	resp, err := http.Get(u)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 || string(b) != "hello" || resp.Header.Get("Content-Type") != "image/webp" {
		t.Fatalf("presigned GET: %d %q %s", resp.StatusCode, b, resp.Header.Get("Content-Type"))
	}
	if _, err := (blob.Local{Root: t.TempDir()}).PresignGet(ctx, "a", time.Minute, ""); !errors.Is(err, blob.ErrUnsupported) {
		t.Fatal("fs presign should be unsupported")
	}
}

// conformance checks the behaviour every Store must have.
func conformance(t *testing.T, s blob.Store) {
	ctx := context.Background()
	data := bytes.Repeat([]byte("0123456789abcdef"), 4096) // 64 KiB
	sum := sha256.Sum256(data)
	digest := hex.EncodeToString(sum[:])

	t.Run("missing", func(t *testing.T) {
		if _, err := s.Stat(ctx, "nope/x.webp"); !errors.Is(err, blob.ErrNotFound) {
			t.Fatalf("stat: %v", err)
		}
		if _, _, err := s.Open(ctx, "nope/x.webp"); !errors.Is(err, blob.ErrNotFound) {
			t.Fatalf("open: %v", err)
		}
		if err := s.Delete(ctx, "nope/x.webp"); err != nil {
			t.Fatalf("delete missing: %v", err)
		}
		if _, err := blob.LocalFile(ctx, s, "nope/x.webp", t.TempDir()); !errors.Is(err, blob.ErrNotFound) {
			t.Fatalf("local file: %v", err)
		}
	})

	t.Run("bad keys", func(t *testing.T) {
		if err := s.Put(ctx, "../x", strings.NewReader("x"), 1, blob.PutOptions{}); err == nil {
			t.Fatal("traversal accepted")
		}
	})

	t.Run("put and read", func(t *testing.T) {
		if err := s.Put(ctx, "media/m1/master.webp", bytes.NewReader(data), int64(len(data)), blob.PutOptions{SHA256: digest}); err != nil {
			t.Fatal(err)
		}
		info, err := s.Stat(ctx, "media/m1/master.webp")
		if err != nil {
			t.Fatal(err)
		}
		if info.Size != int64(len(data)) || info.ETag == "" || !strings.HasPrefix(info.ETag, `"`) || info.ModTime.IsZero() {
			t.Fatalf("info: %+v", info)
		}
		if s.Backend() == "s3" && (info.SHA256 != digest || info.ContentType != "image/webp") {
			t.Fatalf("s3 metadata: %+v", info)
		}
		got, n, err := blob.Digest(ctx, s, "media/m1/master.webp")
		if err != nil || got != digest || n != int64(len(data)) {
			t.Fatalf("digest: %s %d %v", got, n, err)
		}
		p, err := blob.LocalFile(ctx, s, "media/m1/master.webp", t.TempDir())
		if err != nil {
			t.Fatal(err)
		}
		if b, _ := os.ReadFile(p); !bytes.Equal(b, data) {
			t.Fatal("local file content")
		}
	})

	t.Run("seek", func(t *testing.T) {
		obj, _, err := s.Open(ctx, "media/m1/master.webp")
		if err != nil {
			t.Fatal(err)
		}
		defer obj.Close()
		if n, err := obj.Seek(0, io.SeekEnd); err != nil || n != int64(len(data)) {
			t.Fatalf("seek end: %d %v", n, err)
		}
		if _, err := obj.Seek(1000, io.SeekStart); err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, 10)
		if _, err := io.ReadFull(obj, buf); err != nil || !bytes.Equal(buf, data[1000:1010]) {
			t.Fatalf("read at 1000: %q %v", buf, err)
		}
		if _, err := obj.Seek(-6, io.SeekEnd); err != nil {
			t.Fatal(err)
		}
		rest, err := io.ReadAll(obj)
		if err != nil || !bytes.Equal(rest, data[len(data)-6:]) {
			t.Fatalf("tail: %q %v", rest, err)
		}
	})

	t.Run("http range etag", func(t *testing.T) {
		h := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			obj, info, err := s.Open(r.Context(), "media/m1/master.webp")
			if err != nil {
				http.Error(w, err.Error(), 404)
				return
			}
			defer obj.Close()
			w.Header().Set("ETag", info.ETag)
			http.ServeContent(w, r, "", info.ModTime, obj)
		})
		srv := httptest.NewServer(h)
		defer srv.Close()
		get := func(hdr map[string]string) (*http.Response, []byte) {
			req, _ := http.NewRequest("GET", srv.URL, nil)
			for k, v := range hdr {
				req.Header.Set(k, v)
			}
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			defer resp.Body.Close()
			b, _ := io.ReadAll(resp.Body)
			return resp, b
		}
		full, body := get(nil)
		if full.StatusCode != 200 || !bytes.Equal(body, data) {
			t.Fatalf("full: %d %d bytes", full.StatusCode, len(body))
		}
		part, body := get(map[string]string{"Range": "bytes=100-199"})
		if part.StatusCode != 206 || !bytes.Equal(body, data[100:200]) || part.Header.Get("Content-Range") != "bytes 100-199/65536" {
			t.Fatalf("range: %d %q", part.StatusCode, part.Header.Get("Content-Range"))
		}
		tail, body := get(map[string]string{"Range": "bytes=65000-"})
		if tail.StatusCode != 206 || !bytes.Equal(body, data[65000:]) {
			t.Fatalf("open range: %d %d", tail.StatusCode, len(body))
		}
		multi, body := get(map[string]string{"Range": "bytes=0-1,10-11"})
		if multi.StatusCode != 206 || !strings.HasPrefix(multi.Header.Get("Content-Type"), "multipart/byteranges") || !bytes.Contains(body, []byte("ab")) {
			t.Fatalf("multi range: %d %s", multi.StatusCode, multi.Header.Get("Content-Type"))
		}
		nm, _ := get(map[string]string{"If-None-Match": full.Header.Get("ETag")})
		if nm.StatusCode != 304 {
			t.Fatalf("if-none-match: %d", nm.StatusCode)
		}
		bad, _ := get(map[string]string{"Range": "bytes=70000-"})
		if bad.StatusCode != http.StatusRequestedRangeNotSatisfiable {
			t.Fatalf("unsatisfiable: %d", bad.StatusCode)
		}
	})

	t.Run("overwrite", func(t *testing.T) {
		before, _ := s.Stat(ctx, "media/m1/master.webp")
		if err := s.Put(ctx, "media/m1/master.webp", strings.NewReader("short"), 5, blob.PutOptions{}); err != nil {
			t.Fatal(err)
		}
		after, err := s.Stat(ctx, "media/m1/master.webp")
		if err != nil || after.Size != 5 || after.ETag == before.ETag {
			t.Fatalf("overwrite: %+v %v", after, err)
		}
		got, _, _ := blob.Digest(ctx, s, "media/m1/master.webp")
		if want := sha256.Sum256([]byte("short")); got != hex.EncodeToString(want[:]) {
			t.Fatal("overwrite content")
		}
	})

	t.Run("put file and size check", func(t *testing.T) {
		src := filepath.Join(t.TempDir(), "r.mp4")
		_ = os.WriteFile(src, data[:1234], 0o644)
		if err := s.PutFile(ctx, "media/m1/frame_1280x800.mp4", src, blob.PutOptions{}); err != nil {
			t.Fatal(err)
		}
		info, err := s.Stat(ctx, "media/m1/frame_1280x800.mp4")
		if err != nil || info.Size != 1234 {
			t.Fatalf("put file: %+v %v", info, err)
		}
		if err := s.Put(ctx, "media/m1/short", strings.NewReader("abc"), 10, blob.PutOptions{}); err == nil {
			t.Fatal("short reader accepted")
		}
		if _, err := s.Stat(ctx, "media/m1/short"); !errors.Is(err, blob.ErrNotFound) {
			t.Fatalf("failed put left an object: %v", err)
		}
	})

	t.Run("list and delete", func(t *testing.T) {
		for _, k := range []string{"items/i1/thumb_256-a.webp", "items/i1/thumb_512-a.webp", "items/i2/thumb_256-b.webp", "avatars/u/x.webp"} {
			if err := s.Put(ctx, k, strings.NewReader(k), int64(len(k)), blob.PutOptions{}); err != nil {
				t.Fatal(err)
			}
		}
		list := func(prefix string) string {
			var keys []string
			if err := s.List(ctx, prefix, func(i blob.Info) error {
				if i.Size <= 0 || i.ETag == "" {
					t.Errorf("list info %+v", i)
				}
				keys = append(keys, i.Key)
				return nil
			}); err != nil {
				t.Fatal(err)
			}
			return strings.Join(keys, ",")
		}
		if got := list("items/i1/"); got != "items/i1/thumb_256-a.webp,items/i1/thumb_512-a.webp" {
			t.Fatalf("list items/i1/: %s", got)
		}
		if got := list("items/"); got != "items/i1/thumb_256-a.webp,items/i1/thumb_512-a.webp,items/i2/thumb_256-b.webp" {
			t.Fatalf("list items/: %s", got)
		}
		if got := list(""); got != "avatars/u/x.webp,items/i1/thumb_256-a.webp,items/i1/thumb_512-a.webp,items/i2/thumb_256-b.webp,media/m1/frame_1280x800.mp4,media/m1/master.webp" {
			t.Fatalf("list all: %s", got)
		}
		stop := errors.New("stop")
		n := 0
		if err := s.List(ctx, "", func(blob.Info) error { n++; return stop }); !errors.Is(err, stop) || n != 1 {
			t.Fatalf("stop walk: %v %d", err, n)
		}
		if err := s.Delete(ctx, "items/i1/thumb_256-a.webp"); err != nil {
			t.Fatal(err)
		}
		if _, err := s.Stat(ctx, "items/i1/thumb_256-a.webp"); !errors.Is(err, blob.ErrNotFound) {
			t.Fatalf("deleted key still there: %v", err)
		}
		if got := list("items/i1/"); got != "items/i1/thumb_512-a.webp" {
			t.Fatalf("after delete: %s", got)
		}
	})
}
