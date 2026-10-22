package releases

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// fakeGitHub serves /repos/o/r/releases and asset downloads.
type fakeGitHub struct {
	t        *testing.T
	srv      *httptest.Server
	mu       sync.Mutex
	releases []map[string]any
	files    map[string][]byte
	etag     string
	listHits atomic.Int32
	apkHits  atomic.Int32
	limited  bool
}

func newFakeGitHub(t *testing.T) *fakeGitHub {
	f := &fakeGitHub{t: t, files: map[string][]byte{}}
	f.srv = httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeGitHub) serve(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if r.URL.Path == "/repos/o/r/releases" {
		f.listHits.Add(1)
		if f.limited {
			w.Header().Set("X-RateLimit-Remaining", "0")
			w.Header().Set("X-RateLimit-Reset", strconv.FormatInt(time.Now().Add(time.Hour).Unix(), 10))
			w.WriteHeader(http.StatusForbidden)
			return
		}
		b, _ := json.Marshal(f.releases)
		etag := fmt.Sprintf(`"%x"`, sha256.Sum256(b))
		if r.Header.Get("If-None-Match") == etag {
			w.WriteHeader(http.StatusNotModified)
			return
		}
		w.Header().Set("ETag", etag)
		_, _ = w.Write(b)
		return
	}
	b, ok := f.files[r.URL.Path]
	if !ok {
		http.NotFound(w, r)
		return
	}
	if filepath.Ext(r.URL.Path) == ".apk" {
		f.apkHits.Add(1)
	}
	_, _ = w.Write(b)
}

// add publishes a release with an APK built for (name, code). Options can corrupt it.
func (f *fakeGitHub) add(tag, name string, code int, prerelease bool, opts ...string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	pkg := FramePackage
	assetCode := code
	for _, o := range opts {
		switch o {
		case "wrong-package":
			pkg = "com.example.evil"
		case "wrong-code":
			assetCode = code + 1
		}
	}
	apk := writeTestAPK(f.t, "", pkg, name, code, 23)
	asset := fmt.Sprintf("kinframe-frame-%s-%d.apk", name, assetCode)
	sum := sha256.Sum256(apk)
	shaLine := hex.EncodeToString(sum[:]) + "  " + asset + "\n"
	for _, o := range opts {
		if o == "bad-sha" {
			shaLine = hex.EncodeToString(make([]byte, 32)) + "  " + asset + "\n"
		}
	}
	f.files["/dl/"+tag+"/"+asset] = apk
	f.files["/dl/"+tag+"/"+asset+".sha256"] = []byte(shaLine)
	assets := []map[string]any{
		{"name": asset, "browser_download_url": f.srv.URL + "/dl/" + tag + "/" + asset, "size": len(apk)},
	}
	for _, o := range opts {
		if o == "no-sha" {
			goto done
		}
	}
	assets = append(assets, map[string]any{"name": asset + ".sha256", "browser_download_url": f.srv.URL + "/dl/" + tag + "/" + asset + ".sha256", "size": len(shaLine)})
done:
	assets = append(assets, map[string]any{"name": "source.zip", "browser_download_url": f.srv.URL + "/dl/x", "size": 1})
	f.releases = append([]map[string]any{{
		"tag_name": tag, "body": "Notes for " + name, "draft": false, "prerelease": prerelease,
		"published_at": time.Date(2026, 10, 1, 12, 0, code%60, 0, time.UTC).Format(time.RFC3339), "assets": assets,
	}}, f.releases...)
}

func (f *fakeGitHub) remove(tag string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for i, r := range f.releases {
		if r["tag_name"] == tag {
			f.releases = append(f.releases[:i], f.releases[i+1:]...)
			return
		}
	}
}

func newMirror(t *testing.T, gh *fakeGitHub, dir string) *Mirror {
	m := &Mirror{CacheDir: dir, Repo: "o/r", APIBase: gh.srv.URL, HTTP: gh.srv.Client(),
		Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	if err := m.Load(); err != nil {
		t.Fatal(err)
	}
	return m
}

func codes(rs []Release) []int {
	var out []int
	for _, r := range rs {
		out = append(out, r.VersionCode)
	}
	return out
}

func TestMirrorChannelsAndPruning(t *testing.T) {
	gh := newFakeGitHub(t)
	gh.add("v0.1.0", "0.1.0", 10099, false)
	gh.add("v0.2.0-beta.1", "0.2.0-beta.1", 20001, true)
	gh.add("v0.2.0", "0.2.0", 20099, false)
	gh.add("v0.3.0-beta.1", "0.3.0-beta.1", 30001, true)
	gh.add("v0.3.0-beta.2", "0.3.0-beta.2", 30002, true)
	gh.add("v0.3.0-beta.3", "0.3.0-beta.3", 30003, true)
	dir := t.TempDir()
	m := newMirror(t, gh, dir)
	ctx := context.Background()
	if err := m.Sync(ctx); err != nil {
		t.Fatal(err)
	}
	// Newest three plus the newest stable.
	if got := fmt.Sprint(codes(m.All())); got != "[30003 30002 30001 20099]" {
		t.Fatalf("kept %s", got)
	}
	if r := m.Latest("stable", 10099, 23, nil); r == nil || r.VersionCode != 20099 || r.Prerelease || r.Notes != "Notes for 0.2.0" {
		t.Fatalf("stable latest = %+v", r)
	}
	if r := m.Latest("beta", 10099, 23, nil); r == nil || r.VersionCode != 30003 || !r.Prerelease {
		t.Fatalf("beta latest = %+v", r)
	}
	if r := m.Latest("beta", 10099, 23, map[int]bool{30003: true}); r == nil || r.VersionCode != 30002 {
		t.Fatalf("beta with skip = %+v", r)
	}
	if r := m.Latest("stable", 20099, 23, nil); r != nil {
		t.Fatalf("up to date but got %+v", r)
	}
	if r := m.Latest("stable", 10099, 21, nil); r != nil {
		t.Fatalf("API 21 is below minSdk 23 but got %+v", r)
	}
	files, _ := filepath.Glob(filepath.Join(dir, "*.apk"))
	if len(files) != 4 {
		t.Fatalf("cache has %d APKs: %v", len(files), files)
	}
	r := m.Get(20099)
	b, _ := os.ReadFile(r.Path)
	if s := sha256.Sum256(b); hex.EncodeToString(s[:]) != r.SHA256 || int64(len(b)) != r.Size {
		t.Fatal("cached file does not match its metadata")
	}

	// Unchanged listing: ETag → 304, nothing downloaded again.
	before := gh.apkHits.Load()
	if err := m.Sync(ctx); err != nil {
		t.Fatal(err)
	}
	if gh.apkHits.Load() != before {
		t.Fatal("re-downloaded unchanged releases")
	}

	// A new stable release; a withdrawn (deleted) beta disappears.
	gh.add("v0.3.0", "0.3.0", 30099, false)
	gh.remove("v0.3.0-beta.3")
	if err := m.Sync(ctx); err != nil {
		t.Fatal(err)
	}
	if got := fmt.Sprint(codes(m.All())); got != "[30099 30002 30001]" {
		t.Fatalf("after new release: %s", got)
	}
	if files, _ := filepath.Glob(filepath.Join(dir, "*.apk")); len(files) != 3 {
		t.Fatalf("pruning left %d APKs", len(files))
	}

	// A restart serves the cache without GitHub.
	m2 := &Mirror{CacheDir: dir, Log: m.Log}
	if err := m2.Load(); err != nil {
		t.Fatal(err)
	}
	if got := fmt.Sprint(codes(m2.All())); got != "[30099 30002 30001]" {
		t.Fatalf("reloaded %s", got)
	}
}

func TestMirrorRejectsBadAssets(t *testing.T) {
	gh := newFakeGitHub(t)
	gh.add("v0.1.0", "0.1.0", 10099, false)
	gh.add("v0.2.0", "0.2.0", 20099, false, "bad-sha")
	gh.add("v0.3.0", "0.3.0", 30099, false, "wrong-package")
	gh.add("v0.4.0", "0.4.0", 40099, false, "wrong-code")
	gh.add("v0.5.0", "0.5.0", 50099, false, "no-sha")
	m := newMirror(t, gh, t.TempDir())
	m.Keep = 10
	err := m.Sync(context.Background())
	if err == nil {
		t.Fatal("expected errors for the bad releases")
	}
	t.Log(err)
	if got := fmt.Sprint(codes(m.All())); got != "[10099]" {
		t.Fatalf("served %s", got)
	}
	// Failures clear the ETag so the next round retries.
	if m.etag != "" {
		t.Fatal("etag kept despite failures")
	}
	if left, _ := filepath.Glob(filepath.Join(m.CacheDir, "*.part")); len(left) != 0 {
		t.Fatalf("partial files left: %v", left)
	}
}

func TestMirrorRateLimit(t *testing.T) {
	gh := newFakeGitHub(t)
	gh.limited = true
	m := newMirror(t, gh, t.TempDir())
	if err := m.Sync(context.Background()); err == nil {
		t.Fatal("expected rate limit error")
	}
	if err := m.Sync(context.Background()); err != nil {
		t.Fatal(err)
	}
	if n := gh.listHits.Load(); n != 1 {
		t.Fatalf("polled %d times while rate limited", n)
	}
}

func TestDropDirectory(t *testing.T) {
	drop := t.TempDir()
	writeTestAPK(t, filepath.Join(drop, "frame.apk"), FramePackage, "0.4.0", 40099, 23)
	writeTestAPK(t, filepath.Join(drop, "beta.apk"), FramePackage, "0.5.0-beta.1", 50001, 23)
	writeTestAPK(t, filepath.Join(drop, "evil.apk"), "com.example.evil", "9.9.9", 99999, 23)
	_ = os.WriteFile(filepath.Join(drop, "frame.apk.json"), []byte(`{"release_notes":"Hello"}`), 0o644)
	m := &Mirror{DropDir: drop, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	if err := m.Load(); err != nil {
		t.Fatal(err)
	}
	if got := fmt.Sprint(codes(m.All())); got != "[50001 40099]" {
		t.Fatalf("served %s", got)
	}
	if r := m.Latest("stable", 1, 0, nil); r == nil || r.VersionCode != 40099 || r.Notes != "Hello" || r.Source != "dir" {
		t.Fatalf("stable = %+v", r)
	}
	if r := m.Latest("beta", 1, 0, nil); r == nil || r.VersionCode != 50001 {
		t.Fatalf("beta = %+v", r)
	}
	_ = os.Remove(filepath.Join(drop, "beta.apk"))
	if err := m.Sync(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got := fmt.Sprint(codes(m.All())); got != "[40099]" {
		t.Fatalf("after removal %s", got)
	}
}
