package photosource_test

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/netguard"
	ps "github.com/jeroentrappers/kinframe/server/internal/photosource"
	"github.com/jeroentrappers/kinframe/server/internal/photosource/photosourcetest"
)

func guard(t *testing.T) *netguard.Guard {
	t.Helper()
	g, err := netguard.New(netguard.Config{PrivateNetworks: netguard.ModeOn, AllowLoopback: true})
	if err != nil {
		t.Fatal(err)
	}
	return g
}

func code(err error) string {
	if err == nil {
		return ""
	}
	return ps.AsError(err).Code
}

func TestParseLink(t *testing.T) {
	for _, tc := range []struct {
		provider, url string
		want          string // "" ok, else error code
		key, base     string
	}{
		{ps.ImmichLink, "https://photos.example.org/share/abc123", "", "abc123", "https://photos.example.org/api"},
		{ps.ImmichLink, "https://example.org/immich/share/K-ey_1/", "", "K-ey_1", "https://example.org/immich/api"},
		{ps.ImmichLink, "http://192.168.1.10:2283/s/garden", "", "", "http://192.168.1.10:2283/api"},
		{ps.ImmichLink, "https://photos.example.org/albums/xyz", ps.CodeUnsupportedLink, "", ""},
		{ps.ImmichLink, "https://user:pw@photos.example.org/share/abc", ps.CodeUnsupportedLink, "", ""},
		{ps.ICloudLink, "https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz", "", "B0aGWZuqDGxyz", ""},
		{ps.ICloudLink, "https://icloud.com/sharedalbum/nl-nl/#B0aGWZuqDGxyz", "", "B0aGWZuqDGxyz", ""},
		{ps.ICloudLink, "https://www.icloud.com/photos/#0abcDEF", ps.CodeUnsupportedLink, "", ""},
		{ps.ICloudLink, "https://www.icloud.com/sharedalbum/", ps.CodeUnsupportedLink, "", ""},
		{ps.ICloudLink, "https://evil.example/sharedalbum/#B0aGWZuqDGxyz", ps.CodeUnsupportedLink, "", ""},
	} {
		pl, err := ps.ParseLink(tc.provider, tc.url)
		if got := code(err); got != tc.want {
			t.Errorf("%s: code %q, want %q", tc.url, got, tc.want)
			continue
		}
		if err == nil && (pl.Key != tc.key || pl.APIBase != tc.base) {
			t.Errorf("%s: %+v", tc.url, pl)
		}
	}
	if base, display, _, _ := ps.ImmichServer("https://photos.peeters.be/api/"); base != "https://photos.peeters.be/api" || display != "https://photos.peeters.be" {
		t.Errorf("server url: %s %s", base, display)
	}
	if h := ps.URLHint("https://photos.example.org/share/secret"); h != "https://photos.example.org/…" {
		t.Errorf("hint %q", h)
	}
}

func TestPartition(t *testing.T) {
	// 'A' + one base62 digit, else two base62 digits after the first character.
	for tok, want := range map[string]int{"A5xyz": 5, "AZxyz": 35, "B0aGWZuq": 36, "B12xyz": 64} {
		if got := ps.Partition(tok); got != want {
			t.Errorf("%s: %d, want %d", tok, got, want)
		}
	}
}

func immichFake() *photosourcetest.Immich {
	f := photosourcetest.NewImmich()
	t0 := time.Date(2026, 6, 1, 12, 0, 0, 0, time.UTC)
	f.AddAsset("garden", &photosourcetest.ImmichAsset{ID: "a1", Mime: "image/jpeg", Original: []byte("ORIGINAL-1"), Preview: []byte("PREVIEW-1"),
		Caption: "Roses", TakenAt: t0, TimeZone: "Europe/Brussels"})
	f.AddAsset("garden", &photosourcetest.ImmichAsset{ID: "a2", Mime: "image/x-adobe-dng", Original: []byte("RAW"), Preview: []byte("PREVIEW-2"), TakenAt: t0.Add(time.Hour)})
	f.AddAsset("garden", &photosourcetest.ImmichAsset{ID: "v1", Video: true, Mime: "video/mp4", Original: []byte("VIDEO"), TakenAt: t0.Add(2 * time.Hour), DurationMS: 12000})
	f.AddAsset("garden", &photosourcetest.ImmichAsset{ID: "gone", Mime: "image/jpeg", Original: []byte("X"), Trashed: true, TakenAt: t0})
	f.AddAsset("garden", &photosourcetest.ImmichAsset{ID: "l1", Mime: "image/heic", Original: []byte("HEIC"), LiveVideoID: "l1-motion", TakenAt: t0.Add(3 * time.Hour)})
	f.Lock(func() {
		f.Assets["l1-motion"] = &photosourcetest.ImmichAsset{ID: "l1-motion", Video: true, Mime: "video/quicktime", Original: []byte("MOTION")}
		f.Albums["garden"].Name = "Garden 2026"
		f.Links["open"] = &photosourcetest.ImmichLink{AlbumID: "garden", AllowDownload: true, ShowMetadata: true}
		f.Links["locked"] = &photosourcetest.ImmichLink{AlbumID: "garden", AllowDownload: true, ShowMetadata: true, Password: "s3cret"}
		f.Links["nodl"] = &photosourcetest.ImmichLink{AlbumID: "garden", AllowDownload: false, ShowMetadata: false}
		past := time.Now().Add(-time.Hour)
		f.Links["old"] = &photosourcetest.ImmichLink{AlbumID: "garden", ExpiresAt: &past}
		f.Keys["full"] = nil
		f.Keys["nodl"] = []string{"album.read", "asset.read", "asset.view"}
	})
	return f
}

func download(t *testing.T, l *ps.Listing, a ps.Asset, variant string) (string, string) {
	t.Helper()
	dst := filepath.Join(t.TempDir(), "f")
	d, err := l.Download(context.Background(), a, variant, dst, 1<<20)
	if err != nil {
		t.Fatalf("download %s: %v", a.ID, err)
	}
	b, _ := os.ReadFile(d.Path)
	return string(b), d.Variant
}

func byID(l *ps.Listing) map[string]ps.Asset {
	m := map[string]ps.Asset{}
	for _, a := range l.Assets {
		m[a.ID] = a
	}
	return m
}

func TestImmichLink(t *testing.T) {
	f := immichFake()
	defer f.Close()
	im := &ps.Immich{Guard: guard(t)}
	ctx := context.Background()
	l, err := im.List(ctx, ps.Conn{Provider: ps.ImmichLink, URL: f.ShareURL("open")})
	if err != nil {
		t.Fatal(err)
	}
	as := byID(l)
	if l.Name != "Garden 2026" || l.AlbumID != "garden" || len(l.Assets) != 4 || len(l.Warnings) != 0 {
		t.Fatalf("listing: %+v", l)
	}
	if _, ok := as["gone"]; ok {
		t.Error("trashed asset listed")
	}
	if a := as["a1"]; a.Caption != "Roses" || a.Kind != "photo" || a.TakenAt == nil || a.Checksum == "" {
		t.Errorf("a1: %+v", a)
	}
	if a := as["l1"]; a.Kind != "live" || a.MotionID != "l1-motion" {
		t.Errorf("live: %+v", a)
	}
	if a := as["v1"]; a.Kind != "video" || a.DurationMS != 12000 {
		t.Errorf("video: %+v", a)
	}
	if b, v := download(t, l, as["a1"], ""); b != "ORIGINAL-1" || v != "original" {
		t.Errorf("a1 download: %s %s", b, v)
	}
	if b, v := download(t, l, as["a2"], ""); b != "PREVIEW-2" || v != "fullsize" {
		t.Errorf("RAW: Immich's rendering expected, got %s %s", b, v)
	}
	if b, _ := download(t, l, as["l1"], "motion"); b != "MOTION" {
		t.Errorf("motion: %s", b)
	}

	// Password-protected link: login first (cookie), wrong password, no password.
	if _, err := im.List(ctx, ps.Conn{Provider: ps.ImmichLink, URL: f.ShareURL("locked")}); code(err) != ps.CodePasswordRequired {
		t.Errorf("no password: %v", err)
	}
	if _, err := im.List(ctx, ps.Conn{Provider: ps.ImmichLink, URL: f.ShareURL("locked"), Password: "nope"}); code(err) != ps.CodeAuthFailed {
		t.Errorf("wrong password: %v", err)
	}
	ll, err := im.List(ctx, ps.Conn{Provider: ps.ImmichLink, URL: f.ShareURL("locked"), Password: "s3cret"})
	if err != nil {
		t.Fatalf("password: %v", err)
	}
	if b, _ := download(t, ll, byID(ll)["a1"], ""); b != "ORIGINAL-1" {
		t.Errorf("download behind password: %s", b)
	}

	// No downloads, no metadata: previews and warnings, file dates.
	nl, err := im.List(ctx, ps.Conn{Provider: ps.ImmichLink, URL: f.ShareURL("nodl")})
	if err != nil {
		t.Fatal(err)
	}
	if len(nl.Warnings) != 2 || nl.Warnings[0] != ps.WarnDownloadsDisabled || nl.Warnings[1] != ps.WarnMetadataHidden {
		t.Errorf("warnings %v", nl.Warnings)
	}
	if a := byID(nl)["a1"]; a.Caption != "" || a.TakenAt == nil {
		t.Errorf("hidden metadata: %+v", a)
	}
	if b, v := download(t, nl, byID(nl)["a1"], ""); b != "PREVIEW-1" || v != "preview" {
		t.Errorf("no-download link: %s %s", b, v)
	}

	if _, err := im.List(ctx, ps.Conn{Provider: ps.ImmichLink, URL: f.ShareURL("old")}); code(err) != ps.CodeLinkExpired {
		t.Errorf("expired: %v", err)
	}
	if _, err := im.List(ctx, ps.Conn{Provider: ps.ImmichLink, URL: f.ShareURL("deleted")}); code(err) != ps.CodeLinkNotFound {
		t.Errorf("deleted: %v", err)
	}
	f.Lock(func() { f.Fail = 502 })
	if _, err := im.List(ctx, ps.Conn{Provider: ps.ImmichLink, URL: f.ShareURL("open")}); code(err) != ps.CodeUpstreamError {
		t.Errorf("502: %v", err)
	}
	f.Lock(func() { f.Fail = 0 })
	f.Close()
	if _, err := im.List(ctx, ps.Conn{Provider: ps.ImmichLink, URL: f.ShareURL("open")}); code(err) != ps.CodeUnreachable {
		t.Errorf("down: %v", err)
	}
}

func TestImmichAPI(t *testing.T) {
	f := immichFake()
	defer f.Close()
	f.Lock(func() {
		f.Albums["xmas"] = &photosourcetest.ImmichAlbum{ID: "xmas", Name: "Christmas", Shared: true, OwnerName: "Ben"}
		f.Albums["garden"].OwnerName = "Anna"
	})
	im := &ps.Immich{Guard: guard(t)}
	ctx := context.Background()
	albums, ver, err := im.Albums(ctx, f.URL+"/api", "full")
	if err != nil {
		t.Fatal(err)
	}
	if ver != "v1.132.3" || len(albums) != 2 || albums[0].ID != "garden" || albums[0].Shared || !albums[1].Shared || albums[1].OwnerName != "Ben" {
		t.Errorf("albums %s %+v", ver, albums)
	}
	if _, _, err := im.Albums(ctx, f.URL, "wrong"); code(err) != ps.CodeAuthFailed {
		t.Errorf("wrong key: %v", err)
	}
	l, err := im.List(ctx, ps.Conn{Provider: ps.ImmichAPI, URL: f.URL, APIKey: "full", AlbumID: "garden"})
	if err != nil || len(l.Assets) != 4 || l.ServerVersion != "v1.132.3" {
		t.Fatalf("list: %v %+v", err, l)
	}
	if _, err := im.List(ctx, ps.Conn{Provider: ps.ImmichAPI, URL: f.URL, APIKey: "full", AlbumID: "nope"}); code(err) != ps.CodeAlbumNotFound {
		t.Errorf("unknown album: %v", err)
	}
	// A key without asset.download: the original is refused with permission_missing.
	nl, err := im.List(ctx, ps.Conn{Provider: ps.ImmichAPI, URL: f.URL, APIKey: "nodl", AlbumID: "garden"})
	if err != nil {
		t.Fatal(err)
	}
	_, err = nl.Download(ctx, byID(nl)["a1"], "", filepath.Join(t.TempDir(), "x"), 1<<20)
	if pe := ps.AsError(err); pe == nil || pe.Code != ps.CodePermissionMissing || !bytes.Contains([]byte(pe.Detail), []byte("asset.download")) {
		t.Errorf("missing permission: %v", err)
	}
	// Albums too big for one answer: the metadata search pages through them.
	f.Lock(func() { f.NoAssetsInAlbum = true })
	for i := range 2500 {
		f.AddAsset("big", &photosourcetest.ImmichAsset{ID: "b" + uuid.NewString()[:8] + string(rune('a'+i%26)), Mime: "image/jpeg", Original: []byte{byte(i)}, TakenAt: time.Now()})
	}
	bl, err := im.List(ctx, ps.Conn{Provider: ps.ImmichAPI, URL: f.URL, APIKey: "full", AlbumID: "big"})
	if err != nil || len(bl.Assets) != 2500 {
		t.Fatalf("paged: %v %d", err, len(bl.Assets))
	}
}

func icloudFake() (*photosourcetest.ICloud, *ps.ICloud, string) {
	f := photosourcetest.NewICloud()
	tok := "B0aGWZuqDGxyz"
	f.Albums[tok] = &photosourcetest.ICloudAlbum{Name: "Oma & Opa", Ctag: "FT;1", Photos: []*photosourcetest.ICloudPhoto{
		{GUID: "G1", Caption: "Beach", Created: time.Date(2026, 7, 1, 10, 0, 0, 0, time.UTC), BatchDate: time.Date(2026, 7, 2, 9, 0, 0, 0, time.UTC), File: []byte("JPEG-1")},
		{GUID: "G2", Video: true, Created: time.Date(2026, 7, 1, 11, 0, 0, 0, time.UTC), File: []byte("MP4-2")},
	}}
	return f, nil, tok
}

func TestICloud(t *testing.T) {
	f, _, tok := icloudFake()
	defer f.Close()
	ic := &ps.ICloud{Guard: guard(t), HostFormat: f.Wrong.URL, Pinned: []string{"127.0.0.1"}, Scheme: "http"}
	ctx := context.Background()
	l, err := ic.List(ctx, ps.Conn{Provider: ps.ICloudLink, URL: photosourcetest.Link(tok)})
	if err != nil {
		t.Fatal(err)
	}
	if f.Redirects.Load() != 1 || l.ResolvedHost == "" || l.Name != "Oma & Opa" || l.Hint != "FT;1" || len(l.Assets) != 2 {
		t.Fatalf("listing: redirects %d %+v", f.Redirects.Load(), l)
	}
	as := byID(l)
	if a := as["G1"]; a.Kind != "photo" || a.Caption != "Beach" || a.AddedAt == nil || a.TakenAt == nil || !a.TakenAt.Equal(time.Date(2026, 7, 1, 10, 0, 0, 0, time.UTC)) {
		t.Errorf("photo %+v", a)
	}
	if a := as["G2"]; a.Kind != "video" || a.DurationMS != 7000 {
		t.Errorf("video %+v", a)
	}
	if err := l.Prefetch(ctx, l.Assets); err != nil {
		t.Fatal(err)
	}
	if b, _ := download(t, l, as["G1"], ""); b != "JPEG-1" {
		t.Errorf("photo: %s", b)
	}
	if b, _ := download(t, l, as["G2"], ""); b != "MP4-2" {
		t.Errorf("video: largest video derivative expected, got %s", b)
	}
	if f.AssetURLs.Load() != 1 {
		t.Errorf("asset url calls %d, want 1 (batched)", f.AssetURLs.Load())
	}
	// The remembered partition host skips the redirect.
	if _, err := ic.List(ctx, ps.Conn{Provider: ps.ICloudLink, URL: photosourcetest.Link(tok), ResolvedHost: l.ResolvedHost}); err != nil || f.Redirects.Load() != 1 {
		t.Errorf("resolved host: %v, redirects %d", err, f.Redirects.Load())
	}
	conn := ps.Conn{Provider: ps.ICloudLink, URL: photosourcetest.Link(tok), ResolvedHost: l.ResolvedHost}
	for mode, want := range map[string]string{
		"garbage": ps.CodeFormatChanged, "nophotos": ps.CodeFormatChanged, "gone": ps.CodeLinkNotFound,
		"errorbody": ps.CodeLinkNotFound, "throttle": ps.CodeRateLimited,
	} {
		f.Lock(func() { f.Mode = mode })
		if _, err := ic.List(ctx, conn); code(err) != want {
			t.Errorf("%s: %v, want %s", mode, err, want)
		}
	}
	f.Lock(func() { f.Mode = "" })
	// A redirect off iCloud's domains is refused.
	pinned := &ps.ICloud{Guard: guard(t), HostFormat: f.Wrong.URL, Pinned: []string{"icloud.com"}, Scheme: "http"}
	if _, err := pinned.List(ctx, ps.Conn{Provider: ps.ICloudLink, URL: photosourcetest.Link(tok)}); code(err) != ps.CodeFormatChanged {
		t.Errorf("pinned: %v", err)
	}
}

func TestGooglePicker(t *testing.T) {
	f := photosourcetest.NewGoogle()
	defer f.Close()
	g := &ps.Google{Guard: guard(t), ClientID: "cid", ClientSecret: "secret", RedirectURI: "https://k.example/v1/oauth/google/callback",
		AuthURL: f.URL + "/auth", TokenURL: f.URL + "/token", APIBase: f.URL + "/v1", Pinned: []string{"127.0.0.1"}}
	ctx := context.Background()
	verifier, state := ps.RandomToken(), ps.RandomToken()
	cb, err := f.Consent(g.Consent(state, verifier))
	if err != nil {
		t.Fatal(err)
	}
	if cb.Get("state") != state {
		t.Fatal("state not carried")
	}
	if _, err := g.Exchange(ctx, cb.Get("code"), "wrong-verifier"); err == nil {
		t.Fatal("PKCE not checked")
	}
	cb, _ = f.Consent(g.Consent(state, verifier))
	tok, err := g.Exchange(ctx, cb.Get("code"), verifier)
	if err != nil || time.Until(tok.ExpiresAt) < 50*time.Minute {
		t.Fatalf("exchange: %v %+v", err, tok)
	}
	s, err := g.CreateSession(ctx, tok.AccessToken, 500)
	if err != nil || s.PollInterval != 50*time.Millisecond || s.ExpireTime == nil || f.LastMaxItems != "500" {
		t.Fatalf("session: %v %+v max=%s", err, s, f.LastMaxItems)
	}
	if _, _, err := g.ListItems(ctx, tok.AccessToken, s.ID, ""); err == nil {
		t.Error("listing before picking should fail (FAILED_PRECONDITION)")
	}
	items := make([]photosourcetest.GoogleItem, 150)
	for i := range items {
		items[i] = photosourcetest.GoogleItem{ID: uuid.NewString(), Mime: "image/jpeg", File: []byte{byte(i)}, CreateTime: time.Now()}
	}
	items[3] = photosourcetest.GoogleItem{ID: "vid", Video: true, Mime: "video/mp4", File: []byte("V"), Status: "PROCESSING"}
	f.Pick(s.ID, items...)
	s2, err := g.GetSession(ctx, tok.AccessToken, s.ID)
	if err != nil || !s2.MediaItemsSet {
		t.Fatalf("poll: %v %+v", err, s2)
	}
	page1, next, err := g.ListItems(ctx, tok.AccessToken, s.ID, "")
	if err != nil || len(page1) != 100 || next == "" || page1[3].VideoStatus != "PROCESSING" || page1[3].Type != "VIDEO" {
		t.Fatalf("page 1: %v %d %q", err, len(page1), next)
	}
	page2, next, err := g.ListItems(ctx, tok.AccessToken, s.ID, next)
	if err != nil || len(page2) != 50 || next != "" {
		t.Fatalf("page 2: %v %d %q", err, len(page2), next)
	}
	d, err := g.DownloadItem(ctx, tok.AccessToken, page1[0].BaseURL, "=d", filepath.Join(t.TempDir(), "p"), 1<<20)
	if err != nil || d.Bytes != 1 {
		t.Fatalf("download: %v", err)
	}
	if _, err := g.DownloadItem(ctx, "stolen", page1[0].BaseURL, "=d", filepath.Join(t.TempDir(), "p"), 1<<20); err == nil {
		t.Error("download without the bearer token should fail")
	}
	if err := g.DeleteSession(ctx, tok.AccessToken, s.ID); err != nil || !f.SessionIDs()[s.ID] {
		t.Fatalf("delete: %v", err)
	}
	var ge *ps.GoogleError
	if _, err := g.GetSession(ctx, tok.AccessToken, s.ID); err == nil || !asGoogle(err, &ge) || ge.Status != 404 {
		t.Errorf("deleted session: %v", err)
	}
	if d := ps.ParseGoogleDuration("3.5s"); d != 3500*time.Millisecond {
		t.Errorf("duration %v", d)
	}
}

func asGoogle(err error, out **ps.GoogleError) bool {
	g, ok := err.(*ps.GoogleError)
	*out = g
	return ok
}

func TestRules(t *testing.T) {
	if ps.Backoff(1) != time.Minute || ps.Backoff(3) != 4*time.Minute || ps.Backoff(30) != 6*time.Hour {
		t.Error("backoff")
	}
	if ps.Interval(ps.ICloudLink, 30*time.Minute) != time.Hour || ps.Interval(ps.ImmichLink, 30*time.Minute) != 30*time.Minute {
		t.Error("interval")
	}
	if ps.MassRemoval(20, 30) || !ps.MassRemoval(21, 40) || ps.MassRemoval(21, 42) || ps.MassRemoval(5, 6) {
		t.Error("mass removal")
	}
	t0 := time.Date(2026, 1, 1, 18, 0, 0, 0, time.UTC)
	if got := ps.FlushAt(t0, t0.Add(10*time.Minute)); !got.Equal(t0.Add(40 * time.Minute)) {
		t.Errorf("quiet flush %v", got)
	}
	if got := ps.FlushAt(t0, t0.Add(170*time.Minute)); !got.Equal(t0.Add(3 * time.Hour)) {
		t.Errorf("capped flush %v", got)
	}
}

// One run per upstream host at a time, at most Parallel overall.
func TestSchedulerLimits(t *testing.T) {
	var mu sync.Mutex
	perHost := map[string]int{}
	maxHost, active, maxActive := 0, 0, 0
	hostOf := map[uuid.UUID]string{}
	var due []ps.Due
	for i := range 8 {
		id := uuid.New()
		h := []string{"a", "b", "c", "d", "e"}[i%5]
		hostOf[id] = h
		due = append(due, ps.Due{ID: id, Host: h})
	}
	var runs atomic.Int64
	s := &ps.Scheduler{Parallel: 3,
		Due: func(context.Context, int) ([]ps.Due, error) { return due, nil },
		Run: func(ctx context.Context, id uuid.UUID) {
			mu.Lock()
			perHost[hostOf[id]]++
			active++
			maxHost = max(maxHost, perHost[hostOf[id]])
			maxActive = max(maxActive, active)
			mu.Unlock()
			time.Sleep(20 * time.Millisecond)
			mu.Lock()
			perHost[hostOf[id]]--
			active--
			mu.Unlock()
			runs.Add(1)
		}}
	for _, d := range due {
		s.Kick(d.ID, d.Host)
	}
	s.Kick(due[0].ID, due[0].Host) // already running or queued: ignored
	s.Wait()
	if runs.Load() != 8 || maxHost != 1 || maxActive > 3 {
		t.Errorf("runs %d, max per host %d, max active %d", runs.Load(), maxHost, maxActive)
	}
}
