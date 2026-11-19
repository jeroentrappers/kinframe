package photosource

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/netguard"
)

// ICloud reads iCloud shared albums through the endpoints their public
// website uses (best effort, §6.5): POST …/sharedstreams/webstream for the
// metadata and …/webasseturls for short-lived download URLs. There is no
// official API; anything unexpected is format_changed, never "empty".
type ICloud struct {
	Guard *netguard.Guard
	// HostFormat builds the partition host's base URL from a partition
	// number (tests point it at an httptest server). Default
	// "https://p%02d-sharedstreams.icloud.com".
	HostFormat string
	// Pinned are the domains iCloud requests may go to (default icloud.com,
	// icloud-content.com).
	Pinned []string
	// Scheme of redirected partition hosts (default https; tests use http).
	Scheme string
}

func (ic *ICloud) pinned() []string {
	if len(ic.Pinned) > 0 {
		return ic.Pinned
	}
	return []string{"icloud.com", "icloud-content.com"}
}

func (ic *ICloud) scheme() string {
	if ic.Scheme != "" {
		return ic.Scheme
	}
	return "https"
}

const base62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

// Partition is the server partition encoded in a shared album token: one
// base62 digit after a leading 'A', else two.
func Partition(token string) int {
	val := func(s string) int {
		n := 0
		for _, r := range s {
			n = n*62 + strings.IndexRune(base62, r)
		}
		return n
	}
	if len(token) < 3 {
		return 0
	}
	if token[0] == 'A' {
		return val(token[1:2])
	}
	return val(token[1:3])
}

func (ic *ICloud) baseFor(token, resolved string) string {
	if resolved != "" {
		if strings.Contains(resolved, "://") {
			return strings.TrimRight(resolved, "/")
		}
		return ic.scheme() + "://" + resolved
	}
	f := ic.HostFormat
	if f == "" {
		f = "https://p%02d-sharedstreams.icloud.com"
	}
	if !strings.Contains(f, "%") {
		return strings.TrimRight(f, "/")
	}
	return fmt.Sprintf(f, Partition(token))
}

type icloudSession struct {
	ic    *ICloud
	http  *http.Client
	token string
	base  string // partition base URL
	mu    sync.Mutex
	urls  map[string]icloudURL // derivative checksum -> download URL
}

type icloudURL struct {
	url     string
	expires time.Time
}

// post calls one endpoint, following the 330 partition redirect once.
func (s *icloudSession) post(ctx context.Context, endpoint string, body any, max int64) ([]byte, error) {
	b, _ := json.Marshal(body)
	for attempt := 0; attempt < 2; attempt++ {
		cctx, cancel := context.WithTimeout(ctx, MetadataTimeout)
		req, err := http.NewRequestWithContext(cctx, http.MethodPost, s.base+"/"+s.token+"/sharedstreams/"+endpoint, bytes.NewReader(b))
		if err != nil {
			cancel()
			return nil, errf(CodeFormatChanged, "invalid album address")
		}
		req.Header.Set("Content-Type", "text/plain")
		req.Header.Set("Accept", "application/json")
		req.Header.Set("Origin", "https://www.icloud.com")
		req.Header.Set("User-Agent", UserAgent)
		resp, err := s.http.Do(req)
		if err != nil {
			cancel()
			return nil, AsError(err)
		}
		data, rerr := readCapped(resp.Body, max)
		resp.Body.Close()
		cancel()
		if errors.Is(rerr, errTooBig) {
			return nil, errf(CodeTooLarge, "the album is too big to read in one go")
		}
		if rerr != nil {
			return nil, AsError(rerr)
		}
		switch {
		case resp.StatusCode == 330:
			var r struct {
				Host string `json:"X-Apple-MMe-Host"`
			}
			if json.Unmarshal(data, &r) != nil || r.Host == "" || attempt > 0 {
				return nil, errf(CodeFormatChanged, "iCloud redirected without a usable host")
			}
			host := strings.ToLower(strings.TrimSpace(r.Host))
			if strings.ContainsAny(host, "/?#@") || !netguard.Pinned(strings.Split(host, ":")[0], s.ic.pinned()) {
				return nil, errf(CodeFormatChanged, "iCloud redirected to an unexpected host")
			}
			s.base = s.ic.scheme() + "://" + host
			continue
		case resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone ||
			resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden:
			return nil, &Error{Code: CodeLinkNotFound, Detail: fmt.Sprintf("the album is not shared publicly any more (%d)", resp.StatusCode), Status: resp.StatusCode}
		case resp.StatusCode < 200 || resp.StatusCode > 299:
			return nil, statusError(resp.StatusCode)
		}
		// An error body instead of an album (Public Website turned off, token revoked).
		var e struct {
			ErrorCode *string `json:"errorCode"`
			Error     *string `json:"error"`
		}
		if json.Unmarshal(data, &e) == nil && (e.ErrorCode != nil || e.Error != nil) {
			return nil, errf(CodeLinkNotFound, "iCloud does not share this album publicly")
		}
		return data, nil
	}
	return nil, errf(CodeFormatChanged, "iCloud redirected twice")
}

// flexInt reads iCloud's numbers, which come as strings or numbers.
type flexInt int64

func (f *flexInt) UnmarshalJSON(b []byte) error {
	s := strings.Trim(string(b), `"`)
	if s == "" || s == "null" {
		*f = 0
		return nil
	}
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		fl, ferr := strconv.ParseFloat(s, 64)
		if ferr != nil {
			return err
		}
		n = int64(fl)
	}
	*f = flexInt(n)
	return nil
}

type icloudDerivative struct {
	Checksum string  `json:"checksum"`
	FileSize flexInt `json:"fileSize"`
	Width    flexInt `json:"width"`
	Height   flexInt `json:"height"`
}

type icloudPhoto struct {
	PhotoGUID        string                      `json:"photoGuid"`
	BatchGUID        string                      `json:"batchGuid"`
	DateCreated      string                      `json:"dateCreated"`
	BatchDateCreated string                      `json:"batchDateCreated"`
	Caption          string                      `json:"caption"`
	Contributor      string                      `json:"contributorFullName"`
	Width            flexInt                     `json:"width"`
	Height           flexInt                     `json:"height"`
	MediaAssetType   string                      `json:"mediaAssetType"`
	VideoDuration    flexInt                     `json:"videoDuration"`
	Derivatives      map[string]icloudDerivative `json:"derivatives"`
}

type icloudStream struct {
	StreamName    *string        `json:"streamName"`
	UserFirstName string         `json:"userFirstName"`
	UserLastName  string         `json:"userLastName"`
	StreamCtag    string         `json:"streamCtag"`
	ItemsReturned flexInt        `json:"itemsReturned"`
	Photos        *[]icloudPhoto `json:"photos"`
}

// pick chooses the derivative to download: the largest video rendition for
// videos (not the poster frame), the largest still for photos.
func (p icloudPhoto) pick() (string, icloudDerivative, bool) {
	video := strings.EqualFold(p.MediaAssetType, "video")
	var bestKey string
	var best icloudDerivative
	var bestScore int64 = -1
	for k, d := range p.Derivatives {
		if d.Checksum == "" || strings.EqualFold(k, "PosterFrame") {
			continue
		}
		isVideoKey := strings.HasSuffix(strings.ToLower(k), "p") && len(k) > 1 && k[0] >= '0' && k[0] <= '9'
		if video != isVideoKey {
			continue // stills of a video, or a Live Photo's motion rendition (stills only, §6.5)
		}
		score := int64(d.Width) * int64(d.Height)
		if score == 0 {
			score = int64(d.FileSize)
		}
		if score == 0 {
			if n, err := strconv.Atoi(strings.TrimSuffix(strings.ToLower(k), "p")); err == nil {
				score = int64(n)
			}
		}
		if score > bestScore {
			bestKey, best, bestScore = k, d, score
		}
	}
	return bestKey, best, bestScore >= 0
}

// List implements Provider.
func (ic *ICloud) List(ctx context.Context, c Conn) (*Listing, error) {
	pl, err := ParseLink(ICloudLink, c.URL)
	if err != nil {
		return nil, err
	}
	s := &icloudSession{ic: ic, http: ic.Guard.Client(netguard.Options{Pinned: ic.pinned(), MaxRedirects: -1}), token: pl.Key,
		base: ic.baseFor(pl.Key, c.ResolvedHost), urls: map[string]icloudURL{}}
	start := s.base
	data, err := s.post(ctx, "webstream", map[string]any{"streamCtag": nil}, MaxMetadataBytes)
	if err != nil {
		return nil, err
	}
	var st icloudStream
	if err := json.Unmarshal(data, &st); err != nil {
		return nil, errf(CodeFormatChanged, "iCloud answered in a way we don't understand")
	}
	if st.Photos == nil {
		return nil, errf(CodeFormatChanged, "iCloud's answer has no photo list")
	}
	name := "iCloud"
	if st.StreamName != nil && strings.TrimSpace(*st.StreamName) != "" {
		name = strings.TrimSpace(*st.StreamName)
	}
	out := &Listing{Name: name, Hint: st.StreamCtag, sess: s, DownloadsAllow: true}
	if s.base != start {
		out.ResolvedHost = strings.TrimPrefix(strings.TrimPrefix(s.base, "https://"), "http://")
	}
	for _, p := range *st.Photos {
		if p.PhotoGUID == "" {
			return nil, errf(CodeFormatChanged, "an iCloud item has no id")
		}
		_, d, ok := p.pick()
		if !ok {
			return nil, errf(CodeFormatChanged, "an iCloud item has no usable sizes")
		}
		a := Asset{ID: p.PhotoGUID, Kind: "photo", Caption: strings.TrimSpace(p.Caption), Checksum: d.Checksum,
			Size: int64(d.FileSize), Width: int(d.Width), Height: int(d.Height), derivative: d.Checksum,
			Mime: "image/jpeg", Updated: d.Checksum}
		if strings.EqualFold(p.MediaAssetType, "video") {
			a.Kind, a.Mime = "video", "video/mp4"
			a.DurationMS = int(p.VideoDuration) * 1000
		}
		a.TakenAt = parseTime(p.DateCreated)
		a.AddedAt = parseTime(p.BatchDateCreated)
		out.Assets = append(out.Assets, a)
	}
	if len(out.Assets) == 0 && int64(st.ItemsReturned) > 0 {
		return nil, errf(CodeFormatChanged, "iCloud said it returned %d items but listed none", st.ItemsReturned)
	}
	return out, nil
}

type icloudAssetURLs struct {
	Items map[string]struct {
		URLExpiry   string `json:"url_expiry"`
		URLLocation string `json:"url_location"`
		URLPath     string `json:"url_path"`
	} `json:"items"`
	Locations map[string]struct {
		Scheme string   `json:"scheme"`
		Hosts  []string `json:"hosts"`
	} `json:"locations"`
}

// prefetch asks for download URLs, 25 photo guids at a time.
func (s *icloudSession) prefetch(ctx context.Context, assets []Asset) error {
	for i := 0; i < len(assets); i += 25 {
		chunk := assets[i:min(i+25, len(assets))]
		guids := make([]string, len(chunk))
		for j, a := range chunk {
			guids[j] = a.ID
		}
		data, err := s.post(ctx, "webasseturls", map[string]any{"photoGuids": guids}, 16<<20)
		if err != nil {
			return err
		}
		var r icloudAssetURLs
		if err := json.Unmarshal(data, &r); err != nil || r.Items == nil {
			return errf(CodeFormatChanged, "iCloud's download list could not be read")
		}
		s.mu.Lock()
		for checksum, it := range r.Items {
			loc, ok := r.Locations[it.URLLocation]
			host := it.URLLocation
			scheme := "https"
			if ok {
				if len(loc.Hosts) > 0 {
					host = loc.Hosts[0]
				}
				if loc.Scheme != "" {
					scheme = loc.Scheme
				}
			}
			if host == "" || it.URLPath == "" || !strings.HasPrefix(it.URLPath, "/") {
				continue
			}
			exp := time.Now().Add(time.Hour)
			if t := parseTime(it.URLExpiry); t != nil {
				exp = *t
			}
			s.urls[checksum] = icloudURL{url: scheme + "://" + host + it.URLPath, expires: exp}
		}
		s.mu.Unlock()
	}
	return nil
}

func (s *icloudSession) download(ctx context.Context, a Asset, variant, dst string, max int64) (*Download, error) {
	s.mu.Lock()
	u, ok := s.urls[a.derivative]
	s.mu.Unlock()
	if !ok || time.Until(u.expires) < time.Minute {
		if err := s.prefetch(ctx, []Asset{a}); err != nil {
			return nil, err
		}
		s.mu.Lock()
		u, ok = s.urls[a.derivative]
		s.mu.Unlock()
		if !ok {
			return nil, errf(CodeFormatChanged, "iCloud gave no download address for an item")
		}
	}
	pu, err := url.Parse(u.url)
	if err != nil || !netguard.Pinned(pu.Hostname(), s.ic.pinned()) {
		return nil, errf(CodeFormatChanged, "iCloud pointed to an unexpected host")
	}
	ctx, cancel := context.WithTimeout(ctx, DownloadTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.url, nil)
	if err != nil {
		return nil, errf(CodeFormatChanged, "invalid download address")
	}
	req.Header.Set("User-Agent", UserAgent)
	resp, err := s.http.Do(req)
	if err != nil {
		return nil, AsError(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		if resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusForbidden || resp.StatusCode == http.StatusGone {
			return nil, &Error{Code: CodeFormatChanged, Detail: fmt.Sprintf("the file is not available (%d)", resp.StatusCode), Status: resp.StatusCode}
		}
		return nil, statusError(resp.StatusCode)
	}
	if resp.ContentLength > max {
		return nil, errf(CodeTooLarge, "the file is larger than %d MB", max>>20)
	}
	d, err := saveCapped(resp.Body, dst, max)
	if d != nil {
		d.Variant = "derivative"
	}
	return d, err
}
