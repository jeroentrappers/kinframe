// Package releases mirrors frame app releases (APKs) so frames update from
// their own server (docs/api/README.md §5.7, product spec 4.10).
//
// Two sources feed the catalog:
//   - GitHub Releases of a repository: assets named
//     kinframe-frame-<version name>-<version code>.apk plus a matching .apk.sha256
//     (sha256sum format). Prereleases are the beta channel. APKs are downloaded
//     into the cache directory, verified and pruned to the newest few.
//   - A drop-in directory: any *.apk in it is served as is (self-hosters).
//
// Metadata (package, version code, minSdk) always comes from the APK's own
// binary manifest, never from names alone; the asset name must agree with it.
package releases

import (
	"cmp"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

// FramePackage is the only package name the mirror accepts.
const FramePackage = "app.kinframe.frame"

// Release is one servable frame APK.
type Release struct {
	VersionName string    `json:"version_name"`
	VersionCode int       `json:"version_code"`
	MinSDK      int       `json:"min_sdk"`
	Package     string    `json:"package"`
	SHA256      string    `json:"sha256"`
	Size        int64     `json:"size"`
	Prerelease  bool      `json:"prerelease"`
	Notes       string    `json:"release_notes,omitempty"`
	PublishedAt time.Time `json:"published_at"`
	Source      string    `json:"source"` // "github" or "dir"
	Tag         string    `json:"tag,omitempty"`
	Path        string    `json:"-"`
}

// Mirror keeps the catalog. The zero value is not usable; fill the exported
// fields and call Load before serving.
type Mirror struct {
	CacheDir string        // where GitHub downloads live (<data dir>/frame-releases)
	Repo     string        // owner/name; empty disables GitHub
	APIBase  string        // default https://api.github.com
	Token    string        // optional GitHub token (higher rate limit)
	DropDir  string        // optional drop-in directory
	Interval time.Duration // poll interval, default 30 min
	Keep     int           // GitHub versions kept, default 3 (plus the newest stable)
	HTTP     *http.Client
	Log      *slog.Logger

	mu        sync.RWMutex
	cached    []Release // GitHub, verified, in CacheDir
	dropped   []Release // drop-in directory
	etag      string
	notBefore time.Time // rate-limit back-off
	dirSeen   map[string]dirEntry
}

type dirEntry struct {
	size  int64
	mtime time.Time
	rel   *Release // nil = rejected
}

var assetRE = regexp.MustCompile(`^kinframe-frame-([0-9A-Za-z.+-]+)-([0-9]+)\.apk$`)

func (m *Mirror) log() *slog.Logger {
	if m.Log == nil {
		return slog.Default()
	}
	return m.Log
}

func (m *Mirror) client() *http.Client {
	if m.HTTP == nil {
		return &http.Client{Timeout: 10 * time.Minute}
	}
	return m.HTTP
}

// Load reads previously mirrored releases from CacheDir (so frames can update
// while GitHub is unreachable) and scans the drop-in directory.
func (m *Mirror) Load() error {
	if m.CacheDir != "" {
		if err := os.MkdirAll(m.CacheDir, 0o750); err != nil {
			return err
		}
		metas, _ := filepath.Glob(filepath.Join(m.CacheDir, "*.apk.json"))
		var list []Release
		for _, p := range metas {
			var r Release
			b, err := os.ReadFile(p)
			if err == nil {
				err = json.Unmarshal(b, &r)
			}
			apk := strings.TrimSuffix(p, ".json")
			if st, serr := os.Stat(apk); err != nil || serr != nil || st.Size() != r.Size {
				m.log().Warn("dropping incomplete mirrored release", "file", filepath.Base(apk))
				_ = os.Remove(p)
				_ = os.Remove(apk)
				continue
			}
			r.Path = apk
			list = append(list, r)
		}
		m.mu.Lock()
		m.cached = list
		m.mu.Unlock()
		// Leftover partial downloads.
		parts, _ := filepath.Glob(filepath.Join(m.CacheDir, "*.part"))
		for _, p := range parts {
			_ = os.Remove(p)
		}
	}
	m.scanDropDir()
	return nil
}

// Run polls until ctx ends: once at start, then every Interval.
func (m *Mirror) Run(ctx context.Context) {
	interval := cmp.Or(m.Interval, 30*time.Minute)
	for {
		if err := m.Sync(ctx); err != nil && ctx.Err() == nil {
			m.log().Warn("frame release mirror", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(interval):
		}
	}
}

// Sync runs one round: rescan the drop-in directory and poll GitHub.
func (m *Mirror) Sync(ctx context.Context) error {
	m.scanDropDir()
	if m.Repo == "" {
		return nil
	}
	return m.syncGitHub(ctx)
}

// ---------------------------------------------------------------- catalog

// All returns every servable release, newest first. GitHub wins over the
// drop-in directory for the same version code.
func (m *Mirror) All() []Release {
	m.mu.RLock()
	defer m.mu.RUnlock()
	seen := map[int]bool{}
	var out []Release
	for _, l := range [][]Release{m.cached, m.dropped} {
		for _, r := range l {
			if !seen[r.VersionCode] {
				seen[r.VersionCode] = true
				out = append(out, r)
			}
		}
	}
	slices.SortFunc(out, func(a, b Release) int { return b.VersionCode - a.VersionCode })
	return out
}

// Latest returns the newest release for channel ("stable" or "beta") that is
// newer than current, not in skip, and installable on androidSDK (0 = unknown,
// no check). Beta sees stable releases too.
func (m *Mirror) Latest(channel string, current, androidSDK int, skip map[int]bool) *Release {
	for _, r := range m.All() {
		if r.VersionCode <= current {
			return nil
		}
		if skip[r.VersionCode] || (r.Prerelease && channel != "beta") || (androidSDK > 0 && androidSDK < r.MinSDK) {
			continue
		}
		return &r
	}
	return nil
}

// Get returns the release with that version code.
func (m *Mirror) Get(code int) *Release {
	for _, r := range m.All() {
		if r.VersionCode == code {
			return &r
		}
	}
	return nil
}

// ---------------------------------------------------------------- drop-in directory

func (m *Mirror) scanDropDir() {
	if m.DropDir == "" {
		return
	}
	entries, err := os.ReadDir(m.DropDir)
	if err != nil {
		m.log().Warn("frame releases directory", "err", err)
		return
	}
	if m.dirSeen == nil {
		m.dirSeen = map[string]dirEntry{}
	}
	var list []Release
	present := map[string]bool{}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".apk") {
			continue
		}
		p := filepath.Join(m.DropDir, e.Name())
		st, err := os.Stat(p)
		if err != nil {
			continue
		}
		present[p] = true
		if prev, ok := m.dirSeen[p]; ok && prev.size == st.Size() && prev.mtime.Equal(st.ModTime()) {
			if prev.rel != nil {
				list = append(list, *prev.rel)
			}
			continue
		}
		rel, err := inspect(p, "", 0)
		if err != nil {
			m.log().Warn("ignoring APK in frame releases directory", "file", e.Name(), "err", err)
			m.dirSeen[p] = dirEntry{size: st.Size(), mtime: st.ModTime()}
			continue
		}
		rel.Source = "dir"
		rel.PublishedAt = st.ModTime().UTC()
		rel.Prerelease = strings.Contains(rel.VersionName, "-")
		// Optional sidecar <name>.apk.json: {"prerelease": bool, "release_notes": "..."}.
		if b, err := os.ReadFile(p + ".json"); err == nil {
			var side struct {
				Prerelease *bool  `json:"prerelease"`
				Notes      string `json:"release_notes"`
			}
			if json.Unmarshal(b, &side) == nil {
				if side.Prerelease != nil {
					rel.Prerelease = *side.Prerelease
				}
				rel.Notes = side.Notes
			}
		}
		m.dirSeen[p] = dirEntry{size: st.Size(), mtime: st.ModTime(), rel: rel}
		list = append(list, *rel)
		m.log().Info("frame release from directory", "version", rel.VersionName, "code", rel.VersionCode, "beta", rel.Prerelease)
	}
	for p := range m.dirSeen {
		if !present[p] {
			delete(m.dirSeen, p)
		}
	}
	m.mu.Lock()
	m.dropped = list
	m.mu.Unlock()
}

// inspect hashes an APK and reads its manifest. wantSHA (hex) and wantCode are
// checked when non-empty/non-zero.
func inspect(path, wantSHA string, wantCode int) (*Release, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	h := sha256.New()
	n, err := io.Copy(h, f)
	f.Close()
	if err != nil {
		return nil, err
	}
	sum := hex.EncodeToString(h.Sum(nil))
	if wantSHA != "" && !strings.EqualFold(sum, wantSHA) {
		return nil, fmt.Errorf("sha256 mismatch: got %s, want %s", sum, wantSHA)
	}
	man, err := ReadAPKManifest(path)
	if err != nil {
		return nil, err
	}
	if man.Package != FramePackage {
		return nil, fmt.Errorf("package is %q, not %s", man.Package, FramePackage)
	}
	if man.VersionCode <= 0 {
		return nil, errors.New("APK has no versionCode")
	}
	if wantCode != 0 && man.VersionCode != wantCode {
		return nil, fmt.Errorf("versionCode in APK is %d, asset name says %d", man.VersionCode, wantCode)
	}
	return &Release{
		VersionName: man.VersionName, VersionCode: man.VersionCode, MinSDK: man.MinSDK, Package: man.Package,
		SHA256: sum, Size: n, Path: path,
	}, nil
}

// ---------------------------------------------------------------- GitHub

type ghRelease struct {
	TagName     string    `json:"tag_name"`
	Body        string    `json:"body"`
	Draft       bool      `json:"draft"`
	Prerelease  bool      `json:"prerelease"`
	PublishedAt time.Time `json:"published_at"`
	Assets      []ghAsset `json:"assets"`
}

type ghAsset struct {
	Name string `json:"name"`
	URL  string `json:"browser_download_url"`
	Size int64  `json:"size"`
}

// candidate is a GitHub release with a usable APK asset.
type candidate struct {
	rel    ghRelease
	apk    ghAsset
	sha    ghAsset
	name   string
	code   int
	stable bool
}

func (m *Mirror) syncGitHub(ctx context.Context) error {
	m.mu.RLock()
	wait, etag := m.notBefore, m.etag
	m.mu.RUnlock()
	if time.Now().Before(wait) {
		return nil
	}
	base := strings.TrimRight(cmp.Or(m.APIBase, "https://api.github.com"), "/")
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/repos/"+m.Repo+"/releases?per_page=30", nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
	req.Header.Set("User-Agent", "kinframe-server")
	if etag != "" {
		req.Header.Set("If-None-Match", etag)
	}
	if m.Token != "" {
		req.Header.Set("Authorization", "Bearer "+m.Token)
	}
	resp, err := m.client().Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	switch {
	case resp.StatusCode == http.StatusNotModified:
		return nil
	case resp.StatusCode == http.StatusForbidden || resp.StatusCode == http.StatusTooManyRequests:
		until := rateLimitUntil(resp.Header)
		m.mu.Lock()
		m.notBefore = until
		m.mu.Unlock()
		return fmt.Errorf("GitHub rate limit (%d), next try after %s", resp.StatusCode, until.Format(time.RFC3339))
	case resp.StatusCode != http.StatusOK:
		return fmt.Errorf("GitHub releases: HTTP %d", resp.StatusCode)
	}
	var list []ghRelease
	if err := json.NewDecoder(io.LimitReader(resp.Body, 8<<20)).Decode(&list); err != nil {
		return fmt.Errorf("GitHub releases: %w", err)
	}

	var cands []candidate
	for _, r := range list {
		if r.Draft {
			continue
		}
		for _, a := range r.Assets {
			sm := assetRE.FindStringSubmatch(a.Name)
			if sm == nil {
				continue
			}
			code, err := strconv.Atoi(sm[2])
			if err != nil || code <= 0 {
				continue
			}
			i := slices.IndexFunc(r.Assets, func(s ghAsset) bool { return s.Name == a.Name+".sha256" })
			if i < 0 {
				m.log().Warn("frame release without .sha256 asset ignored", "tag", r.TagName, "asset", a.Name)
				continue
			}
			cands = append(cands, candidate{rel: r, apk: a, sha: r.Assets[i], name: sm[1], code: code, stable: !r.Prerelease})
		}
	}
	slices.SortFunc(cands, func(a, b candidate) int { return b.code - a.code })
	keep := keepSet(cands, cmp.Or(m.Keep, 3))

	var errs []error
	have := map[int]Release{}
	m.mu.RLock()
	for _, r := range m.cached {
		have[r.VersionCode] = r
	}
	m.mu.RUnlock()
	var next []Release
	for _, c := range cands {
		if !keep[c.code] {
			continue
		}
		wantSHA, err := m.fetchSHA(ctx, c.sha)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", c.sha.Name, err))
			if old, ok := have[c.code]; ok {
				next = append(next, old)
			}
			continue
		}
		if r, ok := have[c.code]; ok && r.SHA256 == wantSHA {
			// Metadata from GitHub may change (notes, prerelease flag); the APK didn't.
			r.Notes, r.Prerelease, r.PublishedAt, r.Tag = c.rel.Body, c.rel.Prerelease, c.rel.PublishedAt, c.rel.TagName
			if err := writeMeta(r); err != nil {
				errs = append(errs, err)
			}
			next = append(next, r)
			continue
		}
		r, err := m.download(ctx, c, wantSHA)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", c.apk.Name, err))
			// Keep serving a previously verified copy, if any.
			if old, ok := have[c.code]; ok {
				next = append(next, old)
			}
			continue
		}
		m.log().Info("mirrored frame release", "version", r.VersionName, "code", r.VersionCode, "beta", r.Prerelease)
		next = append(next, *r)
	}
	// Anything no longer listed (or no longer kept) is removed: deleting a GitHub release withdraws it.
	inNext := map[string]bool{}
	for _, r := range next {
		inNext[r.Path] = true
	}
	for _, r := range have {
		if !inNext[r.Path] {
			_ = os.Remove(r.Path)
			_ = os.Remove(r.Path + ".json")
			m.log().Info("removed mirrored frame release", "version", r.VersionName, "code", r.VersionCode)
		}
	}
	m.mu.Lock()
	m.cached = next
	if len(errs) == 0 {
		// Only remember the ETag once everything it describes is mirrored, so failures are retried.
		m.etag = resp.Header.Get("ETag")
	} else {
		m.etag = ""
	}
	m.mu.Unlock()
	return errors.Join(errs...)
}

// keepSet is the newest n version codes plus the newest stable one (so the
// stable channel always has its latest release even after many betas).
func keepSet(sorted []candidate, n int) map[int]bool {
	keep := map[int]bool{}
	stable := false
	for _, c := range sorted {
		if len(keep) < n {
			keep[c.code] = true
			stable = stable || c.stable
		} else if !stable && c.stable {
			keep[c.code] = true
			stable = true
		}
	}
	return keep
}

func rateLimitUntil(h http.Header) time.Time {
	if s, err := strconv.Atoi(h.Get("Retry-After")); err == nil && s > 0 {
		return time.Now().Add(time.Duration(s) * time.Second)
	}
	if h.Get("X-RateLimit-Remaining") == "0" {
		if t, err := strconv.ParseInt(h.Get("X-RateLimit-Reset"), 10, 64); err == nil {
			return time.Unix(t, 0)
		}
	}
	return time.Now().Add(time.Hour)
}

var hexRE = regexp.MustCompile(`^[0-9a-fA-F]{64}$`)

func (m *Mirror) get(ctx context.Context, url string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/octet-stream")
	req.Header.Set("User-Agent", "kinframe-server")
	resp, err := m.client().Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return resp, nil
}

func (m *Mirror) fetchSHA(ctx context.Context, a ghAsset) (string, error) {
	resp, err := m.get(ctx, a.URL)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err != nil {
		return "", err
	}
	f := strings.Fields(string(b))
	if len(f) == 0 || !hexRE.MatchString(f[0]) {
		return "", errors.New("not a sha256sum file")
	}
	return strings.ToLower(f[0]), nil
}

func (m *Mirror) download(ctx context.Context, c candidate, wantSHA string) (*Release, error) {
	resp, err := m.get(ctx, c.apk.URL)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	final := filepath.Join(m.CacheDir, c.apk.Name)
	tmp := final + ".part"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o640)
	if err != nil {
		return nil, err
	}
	const maxAPK = 512 << 20
	n, err := io.Copy(f, io.LimitReader(resp.Body, maxAPK+1))
	if err == nil && n > maxAPK {
		err = errors.New("APK larger than 512 MiB")
	}
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		_ = os.Remove(tmp)
		return nil, err
	}
	r, err := inspect(tmp, wantSHA, c.code)
	if err != nil {
		_ = os.Remove(tmp)
		return nil, err
	}
	if err := os.Rename(tmp, final); err != nil {
		_ = os.Remove(tmp)
		return nil, err
	}
	r.Path = final
	r.Source = "github"
	r.Prerelease = c.rel.Prerelease
	r.Notes = c.rel.Body
	r.PublishedAt = c.rel.PublishedAt
	r.Tag = c.rel.TagName
	if r.VersionName == "" {
		r.VersionName = c.name
	}
	if err := writeMeta(*r); err != nil {
		return nil, err
	}
	return r, nil
}

func writeMeta(r Release) error {
	b, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		return err
	}
	tmp := r.Path + ".json.tmp"
	if err := os.WriteFile(tmp, b, 0o640); err != nil {
		return err
	}
	return os.Rename(tmp, r.Path+".json")
}
