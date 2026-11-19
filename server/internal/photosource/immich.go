package photosource

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/netguard"
)

// Immich reads Immich albums through shared links (immich_link) or an API
// key (immich_api), with Immich's public REST API (the calls Immich's own
// shared-album page makes; https://api.immich.app).
type Immich struct {
	Guard *netguard.Guard
}

type immichSession struct {
	http    *http.Client
	base    string // …/api
	apiKey  string
	key     string // share key
	slug    string // share slug
	token   string // shared-link token from a password login (older servers)
	allowDl bool
}

func (s *immichSession) url(path string, q url.Values) string {
	if q == nil {
		q = url.Values{}
	}
	if s.key != "" {
		q.Set("key", s.key)
	}
	if s.slug != "" {
		q.Set("slug", s.slug)
	}
	if s.token != "" {
		q.Set("token", s.token)
	}
	u := s.base + path
	if len(q) > 0 {
		u += "?" + q.Encode()
	}
	return u
}

type immichErrBody struct {
	Message json.RawMessage `json:"message"`
	Error   string          `json:"error"`
}

func (b immichErrBody) text() string {
	var s string
	if json.Unmarshal(b.Message, &s) == nil {
		return s
	}
	var list []string
	if json.Unmarshal(b.Message, &list) == nil {
		return strings.Join(list, "; ")
	}
	return b.Error
}

// do runs one request; non-2xx answers return the status and Immich's message.
func (s *immichSession) do(ctx context.Context, method, path string, q url.Values, body any, max int64) ([]byte, int, string, error) {
	ctx, cancel := context.WithTimeout(ctx, MetadataTimeout)
	defer cancel()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, s.url(path, q), rd)
	if err != nil {
		return nil, 0, "", errf(CodeUnreachable, "invalid address")
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", UserAgent)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if s.apiKey != "" {
		req.Header.Set("x-api-key", s.apiKey)
	}
	resp, err := s.http.Do(req)
	if err != nil {
		return nil, 0, "", AsError(err)
	}
	defer resp.Body.Close()
	data, err := readCapped(resp.Body, max)
	if errors.Is(err, errTooBig) {
		return nil, resp.StatusCode, "", errf(CodeTooLarge, "the album is too big to read in one go")
	}
	if err != nil {
		return nil, resp.StatusCode, "", AsError(err)
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		var eb immichErrBody
		_ = json.Unmarshal(data, &eb)
		return data, resp.StatusCode, eb.text(), nil
	}
	return data, resp.StatusCode, "", nil
}

func (im *Immich) client(jar bool) *http.Client {
	o := netguard.Options{}
	if jar {
		o.Jar, _ = cookiejar.New(nil)
	}
	return im.Guard.Client(o)
}

// ---------------------------------------------------------------- JSON

type immichExif struct {
	DateTimeOriginal *string `json:"dateTimeOriginal"`
	TimeZone         *string `json:"timeZone"`
	Description      *string `json:"description"`
	FileSizeInByte   *int64  `json:"fileSizeInByte"`
	ExifImageWidth   *int    `json:"exifImageWidth"`
	ExifImageHeight  *int    `json:"exifImageHeight"`
}

type immichAsset struct {
	ID               string          `json:"id"`
	Type             string          `json:"type"`
	OriginalMimeType string          `json:"originalMimeType"`
	OriginalFileName string          `json:"originalFileName"`
	FileCreatedAt    string          `json:"fileCreatedAt"`
	LocalDateTime    string          `json:"localDateTime"`
	Checksum         string          `json:"checksum"`
	LivePhotoVideoID *string         `json:"livePhotoVideoId"`
	IsTrashed        bool            `json:"isTrashed"`
	IsOffline        bool            `json:"isOffline"`
	UpdatedAt        string          `json:"updatedAt"`
	Duration         json.RawMessage `json:"duration"`
	ExifInfo         *immichExif     `json:"exifInfo"`
	Width            *int            `json:"width"`
	Height           *int            `json:"height"`
}

type immichAlbum struct {
	ID         string         `json:"id"`
	AlbumName  string         `json:"albumName"`
	UpdatedAt  string         `json:"updatedAt"`
	AssetCount *int           `json:"assetCount"`
	Assets     *[]immichAsset `json:"assets"`
	Shared     bool           `json:"shared"`
	OwnerID    string         `json:"ownerId"`
	Owner      *struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	} `json:"owner"`
}

type immichSharedLink struct {
	Type          string        `json:"type"`
	AllowDownload *bool         `json:"allowDownload"`
	ShowMetadata  *bool         `json:"showMetadata"`
	ShowExif      *bool         `json:"showExif"` // older servers
	ExpiresAt     *string       `json:"expiresAt"`
	Token         string        `json:"token"`
	Album         *immichAlbum  `json:"album"`
	Assets        []immichAsset `json:"assets"`
}

func parseTime(s string) *time.Time {
	if s == "" {
		return nil
	}
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04:05.000Z07:00", "2006-01-02T15:04:05"} {
		if t, err := time.Parse(layout, s); err == nil {
			t = t.UTC()
			return &t
		}
	}
	return nil
}

// parseDuration reads Immich durations: milliseconds (newer servers) or
// "H:MM:SS.mmm" (older ones).
func parseDuration(raw json.RawMessage) int {
	var ms float64
	if json.Unmarshal(raw, &ms) == nil {
		return int(ms)
	}
	var s string
	if json.Unmarshal(raw, &s) != nil || s == "" {
		return 0
	}
	parts := strings.Split(s, ":")
	if len(parts) != 3 {
		return 0
	}
	h, _ := strconv.Atoi(parts[0])
	m, _ := strconv.Atoi(parts[1])
	sec, _ := strconv.ParseFloat(parts[2], 64)
	return int((float64(h*3600+m*60) + sec) * 1000)
}

func (a immichAsset) toAsset(metadata bool) (Asset, bool) {
	out := Asset{ID: a.ID, Checksum: a.Checksum, Updated: a.UpdatedAt, Mime: a.OriginalMimeType, Offline: a.IsOffline,
		DurationMS: parseDuration(a.Duration)}
	switch strings.ToUpper(a.Type) {
	case "IMAGE":
		out.Kind = "photo"
		if a.LivePhotoVideoID != nil && *a.LivePhotoVideoID != "" {
			out.Kind, out.MotionID = "live", *a.LivePhotoVideoID
		}
	case "VIDEO":
		out.Kind = "video"
	default:
		return out, false // audio and other files are not photos
	}
	if a.Width != nil && a.Height != nil {
		out.Width, out.Height = *a.Width, *a.Height
	}
	if e := a.ExifInfo; e != nil && metadata {
		if e.Description != nil {
			out.Caption = strings.TrimSpace(*e.Description)
		}
		if e.FileSizeInByte != nil {
			out.Size = *e.FileSizeInByte
		}
		if e.DateTimeOriginal != nil && e.TimeZone != nil && *e.TimeZone != "" {
			out.TakenAt = parseTime(*e.DateTimeOriginal)
		}
	}
	switch {
	case out.TakenAt != nil:
	case metadata && a.LocalDateTime != "":
		// localDateTime is the wall-clock time written as UTC: the rule for EXIF without an offset.
		out.TakenAt = parseTime(a.LocalDateTime)
	default:
		out.TakenAt = parseTime(a.FileCreatedAt)
	}
	return out, true
}

func assetsOf(list []immichAsset, metadata bool) []Asset {
	out := make([]Asset, 0, len(list))
	for _, a := range list {
		if a.IsTrashed {
			continue // trashed counts as gone
		}
		if as, ok := a.toAsset(metadata); ok {
			out = append(out, as)
		}
	}
	return out
}

func hint(a *immichAlbum) string {
	n := 0
	if a.AssetCount != nil {
		n = *a.AssetCount
	}
	return a.UpdatedAt + "|" + strconv.Itoa(n)
}

// ---------------------------------------------------------------- listing

// List implements Provider for immich_link and immich_api.
func (im *Immich) List(ctx context.Context, c Conn) (*Listing, error) {
	if c.Provider == ImmichAPI {
		return im.listAPI(ctx, c)
	}
	return im.listLink(ctx, c)
}

func (im *Immich) openLink(ctx context.Context, c Conn) (*immichSession, *immichSharedLink, error) {
	pl, err := ParseLink(ImmichLink, c.URL)
	if err != nil {
		return nil, nil, err
	}
	s := &immichSession{http: im.client(true), base: pl.APIBase, key: pl.Key, slug: pl.Slug}
	if c.Password != "" {
		data, status, msg, err := s.do(ctx, http.MethodPost, "/shared-links/login", nil, map[string]string{"password": c.Password}, 1<<20)
		switch {
		case err != nil:
			return nil, nil, err
		case status == http.StatusNotFound || status == http.StatusMethodNotAllowed:
			// Older servers: the password goes on /shared-links/me and the answer carries a token.
			q := url.Values{"password": {c.Password}}
			data, status, msg, err = s.do(ctx, http.MethodGet, "/shared-links/me", q, nil, MaxMetadataBytes)
			if err != nil {
				return nil, nil, err
			}
			if status >= 300 {
				return nil, nil, linkStatusError(status, msg, true)
			}
			var l immichSharedLink
			if json.Unmarshal(data, &l) == nil && l.Token != "" {
				s.token = l.Token
			}
		case status >= 300:
			return nil, nil, linkStatusError(status, msg, true)
		default:
			var l immichSharedLink
			if json.Unmarshal(data, &l) == nil && l.Token != "" {
				s.token = l.Token
			}
		}
	}
	data, status, msg, err := s.do(ctx, http.MethodGet, "/shared-links/me", nil, nil, MaxMetadataBytes)
	if err != nil {
		return nil, nil, err
	}
	if status >= 300 {
		return nil, nil, linkStatusError(status, msg, c.Password != "")
	}
	var l immichSharedLink
	if err := json.Unmarshal(data, &l); err != nil || (l.Album == nil && l.Assets == nil && l.Type == "") {
		return nil, nil, errf(CodeFormatChanged, "Immich answered in an unexpected shape (shared link)")
	}
	if l.ExpiresAt != nil {
		if t := parseTime(*l.ExpiresAt); t != nil && t.Before(time.Now()) {
			return nil, nil, errf(CodeLinkExpired, "the link expired on %s", t.Format("2 January 2006"))
		}
	}
	s.allowDl = l.AllowDownload == nil || *l.AllowDownload
	return s, &l, nil
}

// linkStatusError maps the answer to a shared-link call.
func linkStatusError(status int, msg string, hadPassword bool) error {
	m := strings.ToLower(msg)
	switch {
	case strings.Contains(m, "expired"):
		return &Error{Code: CodeLinkExpired, Detail: "the link has expired", Status: status}
	case status == http.StatusUnauthorized && strings.Contains(m, "password") && !hadPassword:
		return &Error{Code: CodePasswordRequired, Detail: "the link needs a password", Status: status}
	case status == http.StatusUnauthorized && (strings.Contains(m, "password") || hadPassword) && !strings.Contains(m, "key"):
		return &Error{Code: CodeAuthFailed, Detail: "Immich refused the link password", Status: status}
	case status == http.StatusUnauthorized || status == http.StatusForbidden || status == http.StatusNotFound ||
		status == http.StatusBadRequest || status == http.StatusGone:
		return &Error{Code: CodeLinkNotFound, Detail: fmt.Sprintf("the link no longer works (%d)", status), Status: status}
	}
	return statusError(status)
}

func (im *Immich) listLink(ctx context.Context, c Conn) (*Listing, error) {
	s, l, err := im.openLink(ctx, c)
	if err != nil {
		return nil, err
	}
	metadata := true
	if l.ShowMetadata != nil {
		metadata = *l.ShowMetadata
	} else if l.ShowExif != nil {
		metadata = *l.ShowExif
	}
	out := &Listing{sess: s, DownloadsAllow: s.allowDl}
	if !s.allowDl {
		out.Warnings = append(out.Warnings, WarnDownloadsDisabled)
	}
	if !metadata {
		out.Warnings = append(out.Warnings, WarnMetadataHidden)
	}
	if strings.EqualFold(l.Type, "INDIVIDUAL") || l.Album == nil {
		out.Name = "Immich"
		out.Assets = assetsOf(l.Assets, metadata)
		ids := make([]string, len(out.Assets))
		for i, a := range out.Assets {
			ids[i] = a.ID
		}
		sort.Strings(ids)
		out.Hint = strconv.Itoa(len(ids)) + "|" + strings.Join(ids, ",")
		return out, nil
	}
	data, status, msg, err := s.do(ctx, http.MethodGet, "/albums/"+url.PathEscape(l.Album.ID), nil, nil, MaxMetadataBytes)
	if err != nil {
		return nil, err
	}
	if status >= 300 {
		if status == http.StatusNotFound || status == http.StatusBadRequest {
			return nil, &Error{Code: CodeAlbumNotFound, Detail: "the album no longer exists in Immich", Status: status}
		}
		return nil, linkStatusError(status, msg, c.Password != "")
	}
	var a immichAlbum
	if err := json.Unmarshal(data, &a); err != nil || a.ID == "" {
		return nil, errf(CodeFormatChanged, "Immich answered in an unexpected shape (album)")
	}
	if a.Assets == nil || (len(*a.Assets) == 0 && a.AssetCount != nil && *a.AssetCount > 0) {
		return nil, errf(CodeFormatChanged, "the album has %d photos but Immich listed none", deref(a.AssetCount))
	}
	out.Name, out.AlbumID, out.Hint = a.AlbumName, a.ID, hint(&a)
	out.Assets = assetsOf(*a.Assets, metadata)
	return out, nil
}

func deref(p *int) int {
	if p == nil {
		return 0
	}
	return *p
}

func (im *Immich) apiSession(c Conn) (*immichSession, error) {
	base, _, _, err := ImmichServer(c.URL)
	if err != nil {
		return nil, err
	}
	return &immichSession{http: im.client(false), base: base, apiKey: c.APIKey, allowDl: true}, nil
}

// apiStatusError maps answers to API-key calls.
func apiStatusError(status int, msg string, what string) error {
	switch status {
	case http.StatusUnauthorized:
		return &Error{Code: CodeAuthFailed, Detail: "Immich refused the API key", Status: status}
	case http.StatusForbidden:
		d := "the API key is missing a permission"
		if msg != "" {
			d = "the API key is missing a permission: " + shortMsg(msg)
		}
		return &Error{Code: CodePermissionMissing, Detail: d, Status: status}
	case http.StatusNotFound, http.StatusBadRequest:
		if what == "album" {
			return &Error{Code: CodeAlbumNotFound, Detail: "the album no longer exists in Immich", Status: status}
		}
		return &Error{Code: CodeFormatChanged, Detail: "this does not look like an Immich server", Status: status}
	}
	return statusError(status)
}

func shortMsg(s string) string {
	if len(s) > 120 {
		return s[:117] + "…"
	}
	return s
}

// Version returns the server version ("v1.132.3"), "" when unknown.
func (s *immichSession) version(ctx context.Context) string {
	data, status, _, err := s.do(ctx, http.MethodGet, "/server/version", nil, nil, 1<<16)
	if err != nil || status >= 300 {
		data, status, _, err = s.do(ctx, http.MethodGet, "/server-info/version", nil, nil, 1<<16)
		if err != nil || status >= 300 {
			return ""
		}
	}
	var v struct{ Major, Minor, Patch int }
	if json.Unmarshal(data, &v) != nil || (v.Major == 0 && v.Minor == 0) {
		return ""
	}
	return fmt.Sprintf("v%d.%d.%d", v.Major, v.Minor, v.Patch)
}

func (im *Immich) listAPI(ctx context.Context, c Conn) (*Listing, error) {
	s, err := im.apiSession(c)
	if err != nil {
		return nil, err
	}
	if c.AlbumID == "" {
		return nil, errf(CodeAlbumNotFound, "no album chosen")
	}
	ver := s.version(ctx)
	data, status, msg, err := s.do(ctx, http.MethodGet, "/albums/"+url.PathEscape(c.AlbumID), nil, nil, MaxMetadataBytes)
	var a immichAlbum
	paged := false
	var pe *Error
	switch {
	case errors.As(err, &pe) && pe.Code == CodeTooLarge:
		// Too big to read at once: the album without assets, then the metadata search page by page.
		data, status, msg, err = s.do(ctx, http.MethodGet, "/albums/"+url.PathEscape(c.AlbumID), url.Values{"withoutAssets": {"true"}}, nil, 1<<20)
		paged = true
	}
	if err != nil {
		return nil, err
	}
	if status >= 300 {
		return nil, apiStatusError(status, msg, "album")
	}
	if err := json.Unmarshal(data, &a); err != nil || a.ID == "" {
		return nil, errf(CodeFormatChanged, "Immich answered in an unexpected shape (album)")
	}
	out := &Listing{Name: a.AlbumName, AlbumID: a.ID, Hint: hint(&a), ServerVersion: ver, sess: s, DownloadsAllow: true}
	if !paged && a.Assets != nil && (len(*a.Assets) > 0 || deref(a.AssetCount) == 0) {
		out.Assets = assetsOf(*a.Assets, true)
		return out, nil
	}
	assets, err := s.searchAlbum(ctx, a.ID)
	if err != nil {
		return nil, err
	}
	out.Assets = assets
	return out, nil
}

// searchAlbum pages through Immich's metadata search filtered by album.
func (s *immichSession) searchAlbum(ctx context.Context, albumID string) ([]Asset, error) {
	var out []Asset
	page := 1
	cursor := ""
	for range 1000 {
		body := map[string]any{"albumIds": []string{albumID}, "filter": map[string]any{"albumIds": []string{albumID}},
			"withExif": true, "size": 1000, "page": page}
		if cursor != "" {
			body["cursor"] = cursor
		}
		data, status, msg, err := s.do(ctx, http.MethodPost, "/search/metadata", nil, body, MaxMetadataBytes)
		if err != nil {
			return nil, err
		}
		if status >= 300 {
			if status == http.StatusNotFound || status == http.StatusBadRequest {
				return nil, errf(CodeTooLarge, "the album is too big to read in one go and this Immich can't page it")
			}
			return nil, apiStatusError(status, msg, "search")
		}
		var r struct {
			Assets *struct {
				Items      []immichAsset `json:"items"`
				NextPage   *string       `json:"nextPage"`
				NextCursor *string       `json:"nextCursor"`
			} `json:"assets"`
		}
		if err := json.Unmarshal(data, &r); err != nil || r.Assets == nil {
			return nil, errf(CodeFormatChanged, "Immich answered in an unexpected shape (search)")
		}
		out = append(out, assetsOf(r.Assets.Items, true)...)
		switch {
		case r.Assets.NextCursor != nil && *r.Assets.NextCursor != "":
			cursor = *r.Assets.NextCursor
		case r.Assets.NextPage != nil && *r.Assets.NextPage != "":
			n, err := strconv.Atoi(*r.Assets.NextPage)
			if err != nil || n <= page {
				return nil, errf(CodeFormatChanged, "Immich answered in an unexpected shape (search page)")
			}
			page = n
		default:
			return out, nil
		}
	}
	return nil, errf(CodeTooLarge, "the album is too big")
}

// ImmichAlbumInfo is an album an API key can read.
type ImmichAlbumInfo struct {
	ID         string
	Name       string
	AssetCount int
	UpdatedAt  *time.Time
	OwnerName  string
	Shared     bool // shared with the key's account by someone else
}

// Albums lists the albums an API key can read (own first, then shared, by
// name) and the server version.
func (im *Immich) Albums(ctx context.Context, serverURL, apiKey string) ([]ImmichAlbumInfo, string, error) {
	s, err := im.apiSession(Conn{Provider: ImmichAPI, URL: serverURL, APIKey: apiKey})
	if err != nil {
		return nil, "", err
	}
	ver := s.version(ctx)
	get := func(q url.Values) ([]immichAlbum, error) {
		data, status, msg, err := s.do(ctx, http.MethodGet, "/albums", q, nil, MaxMetadataBytes)
		if err != nil {
			return nil, err
		}
		if status >= 300 {
			return nil, apiStatusError(status, msg, "albums")
		}
		var list []immichAlbum
		if err := json.Unmarshal(data, &list); err != nil {
			return nil, errf(CodeFormatChanged, "this does not look like an Immich server")
		}
		return list, nil
	}
	own, err := get(nil)
	if err != nil {
		return nil, ver, err
	}
	shared, err := get(url.Values{"shared": {"true"}})
	if err != nil {
		return nil, ver, err
	}
	seen := map[string]bool{}
	var out []ImmichAlbumInfo
	add := func(a immichAlbum, isShared bool) {
		if seen[a.ID] || a.ID == "" {
			return
		}
		seen[a.ID] = true
		info := ImmichAlbumInfo{ID: a.ID, Name: a.AlbumName, AssetCount: deref(a.AssetCount), UpdatedAt: parseTime(a.UpdatedAt), Shared: isShared}
		if a.Owner != nil {
			info.OwnerName = a.Owner.Name
		}
		out = append(out, info)
	}
	for _, a := range own {
		add(a, false)
	}
	nOwn := len(out)
	for _, a := range shared {
		add(a, true)
	}
	byName := func(xs []ImmichAlbumInfo) {
		sort.SliceStable(xs, func(i, j int) bool { return strings.ToLower(xs[i].Name) < strings.ToLower(xs[j].Name) })
	}
	byName(out[:nOwn])
	byName(out[nOwn:])
	return out, ver, nil
}

// ---------------------------------------------------------------- downloads

func (s *immichSession) prefetch(context.Context, []Asset) error { return nil }

func (s *immichSession) fetch(ctx context.Context, path string, q url.Values, dst string, max int64) (*Download, error) {
	ctx, cancel := context.WithTimeout(ctx, DownloadTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.url(path, q), nil)
	if err != nil {
		return nil, errf(CodeUnreachable, "invalid address")
	}
	req.Header.Set("User-Agent", UserAgent)
	if s.apiKey != "" {
		req.Header.Set("x-api-key", s.apiKey)
	}
	resp, err := s.http.Do(req)
	if err != nil {
		return nil, AsError(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		var eb immichErrBody
		b, _ := readCapped(resp.Body, 1<<16)
		_ = json.Unmarshal(b, &eb)
		switch resp.StatusCode {
		case http.StatusUnauthorized:
			if s.apiKey != "" {
				return nil, &Error{Code: CodeAuthFailed, Detail: "Immich refused the API key", Status: resp.StatusCode}
			}
			return nil, linkStatusError(resp.StatusCode, eb.text(), false)
		case http.StatusForbidden:
			if s.apiKey != "" {
				return nil, apiStatusError(resp.StatusCode, eb.text(), "asset")
			}
			return nil, &Error{Code: CodeFormatChanged, Detail: "the download was refused", Status: resp.StatusCode}
		case http.StatusNotFound, http.StatusBadRequest:
			return nil, &Error{Code: CodeFormatChanged, Detail: fmt.Sprintf("this file is not available (%d)", resp.StatusCode), Status: resp.StatusCode}
		}
		return nil, statusError(resp.StatusCode)
	}
	if resp.ContentLength > max {
		return nil, errf(CodeTooLarge, "the file is larger than %d MB", max>>20)
	}
	return saveCapped(resp.Body, dst, max)
}

// download picks the file of §6.6: the original when readable and allowed,
// else Immich's fullsize or preview rendering; videos the original, else the
// playback encoding; "motion" the live photo's clip.
func (s *immichSession) download(ctx context.Context, a Asset, variant, dst string, max int64) (*Download, error) {
	id := url.PathEscape(a.ID)
	try := func(v string) (*Download, error) {
		var d *Download
		var err error
		switch v {
		case "original":
			d, err = s.fetch(ctx, "/assets/"+id+"/original", nil, dst, max)
		case "fullsize", "preview":
			d, err = s.fetch(ctx, "/assets/"+id+"/thumbnail", url.Values{"size": {v}}, dst, max)
		case "playback":
			d, err = s.fetch(ctx, "/assets/"+id+"/video/playback", nil, dst, max)
		case "motion":
			if a.MotionID == "" {
				return nil, errf(CodeFormatChanged, "no motion clip")
			}
			d, err = s.fetch(ctx, "/assets/"+url.PathEscape(a.MotionID)+"/original", nil, dst, max)
		default:
			return nil, errf(CodeFormatChanged, "unknown variant %q", v)
		}
		if d != nil {
			d.Variant = v
		}
		return d, err
	}
	if variant != "" {
		return try(variant)
	}
	var chain []string
	tooBig := a.Size > 0 && a.Size > max
	switch {
	case a.Kind == "video" && s.allowDl && !tooBig:
		chain = []string{"original", "playback"}
	case a.Kind == "video":
		chain = []string{"playback"}
	case s.allowDl && !tooBig && (a.Mime == "" || Readable(a.Mime)):
		chain = []string{"original", "fullsize", "preview"}
	case s.allowDl:
		chain = []string{"fullsize", "preview"} // RAW, TIFF, PSD, too big: Immich's rendering
	default:
		chain = []string{"preview"}
	}
	var last error
	for _, v := range chain {
		d, err := try(v)
		if err == nil {
			return d, nil
		}
		last = err
		pe := AsError(err)
		if pe.Code != CodeTooLarge && pe.Code != CodeFormatChanged {
			return nil, err // a network or auth failure: not a reason to fall back
		}
	}
	return nil, last
}
