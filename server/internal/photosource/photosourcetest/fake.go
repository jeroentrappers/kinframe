// Package photosourcetest has fake Immich, iCloud and Google Photos servers
// for tests. The JSON shapes follow the official Immich API, Google's Picker
// API reference and, for iCloud (no official docs), the answers its public
// shared-album page receives, written down by hand.
package photosourcetest

import (
	"crypto/sha1"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// ---------------------------------------------------------------- Immich

// ImmichAsset is a photo or video in the fake Immich.
type ImmichAsset struct {
	ID          string
	Video       bool
	Mime        string
	Original    []byte
	Preview     []byte // thumbnail?size=preview (and fullsize)
	Caption     string
	TakenAt     time.Time
	TimeZone    string
	LiveVideoID string
	Trashed     bool
	Offline     bool
	DurationMS  int
	UpdatedAt   time.Time
}

// ImmichLink is a shared link.
type ImmichLink struct {
	AlbumID       string   // ALBUM links
	AssetIDs      []string // INDIVIDUAL links
	Password      string
	AllowDownload bool
	ShowMetadata  bool
	ExpiresAt     *time.Time
}

// Immich is a fake Immich server (API under /api).
type Immich struct {
	*httptest.Server

	mu      sync.Mutex
	Assets  map[string]*ImmichAsset
	Albums  map[string]*ImmichAlbum
	Links   map[string]*ImmichLink
	Keys    map[string][]string // API key -> permissions (empty: all)
	Version [3]int
	// Fail makes every API call answer this status (0: off).
	Fail int
	// NoAssetsInAlbum makes GET /albums/{id} omit assets (forces the metadata search).
	NoAssetsInAlbum bool

	Requests  atomic.Int64
	Downloads atomic.Int64
}

// ImmichAlbum is an album.
type ImmichAlbum struct {
	ID        string
	Name      string
	OwnerID   string
	OwnerName string
	Shared    bool // shared with the API key's user by someone else
	AssetIDs  []string
	UpdatedAt time.Time
}

// NewImmich starts a fake Immich server.
func NewImmich() *Immich {
	f := &Immich{Assets: map[string]*ImmichAsset{}, Albums: map[string]*ImmichAlbum{}, Links: map[string]*ImmichLink{},
		Keys: map[string][]string{}, Version: [3]int{1, 132, 3}}
	f.Server = httptest.NewServer(http.HandlerFunc(f.serve))
	return f
}

// AddAsset adds an asset to an album (creating the album).
func (f *Immich) AddAsset(albumID string, a *ImmichAsset) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if a.UpdatedAt.IsZero() {
		a.UpdatedAt = time.Now().UTC()
	}
	f.Assets[a.ID] = a
	al := f.Albums[albumID]
	if al == nil {
		al = &ImmichAlbum{ID: albumID, Name: albumID}
		f.Albums[albumID] = al
	}
	al.AssetIDs = append(al.AssetIDs, a.ID)
	al.UpdatedAt = time.Now().UTC()
}

// RemoveAsset takes an asset out of an album.
func (f *Immich) RemoveAsset(albumID, assetID string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	al := f.Albums[albumID]
	for i, id := range al.AssetIDs {
		if id == assetID {
			al.AssetIDs = append(al.AssetIDs[:i], al.AssetIDs[i+1:]...)
			break
		}
	}
	al.UpdatedAt = time.Now().UTC()
}

// Edit changes an asset under the lock.
func (f *Immich) Edit(assetID string, fn func(a *ImmichAsset)) {
	f.mu.Lock()
	defer f.mu.Unlock()
	fn(f.Assets[assetID])
	f.Assets[assetID].UpdatedAt = time.Now().UTC()
	for _, al := range f.Albums {
		al.UpdatedAt = time.Now().UTC()
	}
}

// Lock runs fn under the fake's lock (to change links, keys and albums).
func (f *Immich) Lock(fn func()) {
	f.mu.Lock()
	defer f.mu.Unlock()
	fn()
}

// ShareURL is the web link of a shared link key.
func (f *Immich) ShareURL(key string) string { return f.URL + "/share/" + key }

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func immichError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]any{"message": msg, "error": http.StatusText(status), "statusCode": status})
}

func (f *Immich) assetJSON(a *ImmichAsset, metadata bool) map[string]any {
	sum := sha1.Sum(a.Original)
	typ := "IMAGE"
	if a.Video {
		typ = "VIDEO"
	}
	m := map[string]any{
		"id": a.ID, "type": typ, "originalMimeType": a.Mime, "originalFileName": a.ID + ".jpg",
		"fileCreatedAt": a.TakenAt.UTC().Format(time.RFC3339Nano), "localDateTime": a.TakenAt.UTC().Format(time.RFC3339Nano),
		"checksum": base64.StdEncoding.EncodeToString(sum[:]), "isTrashed": a.Trashed, "isOffline": a.Offline,
		"updatedAt": a.UpdatedAt.Format(time.RFC3339Nano), "duration": a.DurationMS, "visibility": "timeline",
	}
	if a.LiveVideoID != "" {
		m["livePhotoVideoId"] = a.LiveVideoID
	} else {
		m["livePhotoVideoId"] = nil
	}
	if metadata {
		exif := map[string]any{"fileSizeInByte": len(a.Original), "dateTimeOriginal": a.TakenAt.UTC().Format(time.RFC3339Nano)}
		if a.TimeZone != "" {
			exif["timeZone"] = a.TimeZone
		}
		if a.Caption != "" {
			exif["description"] = a.Caption
		}
		m["exifInfo"] = exif
	}
	return m
}

func (f *Immich) albumJSON(al *ImmichAlbum, withAssets, metadata bool) map[string]any {
	m := map[string]any{"id": al.ID, "albumName": al.Name, "updatedAt": al.UpdatedAt.Format(time.RFC3339Nano),
		"assetCount": len(al.AssetIDs), "shared": al.Shared, "ownerId": al.OwnerID,
		"owner": map[string]any{"id": al.OwnerID, "name": al.OwnerName}}
	if withAssets {
		list := []map[string]any{}
		for _, id := range al.AssetIDs {
			list = append(list, f.assetJSON(f.Assets[id], metadata))
		}
		m["assets"] = list
	}
	return m
}

// access is what a request may see.
type access struct {
	link  *ImmichLink
	perms []string // API key permissions; nil for links
	key   bool
}

func (a access) has(p string) bool {
	if !a.key || len(a.perms) == 0 {
		return true
	}
	for _, x := range a.perms {
		if x == p || x == "all" {
			return true
		}
	}
	return false
}

func (f *Immich) auth(w http.ResponseWriter, r *http.Request) (access, bool) {
	if k := r.Header.Get("x-api-key"); k != "" {
		perms, ok := f.Keys[k]
		if !ok {
			immichError(w, 401, "Invalid API key")
			return access{}, false
		}
		return access{perms: perms, key: true}, true
	}
	key := r.URL.Query().Get("key")
	l := f.Links[key]
	if l == nil {
		immichError(w, 401, "Invalid share key")
		return access{}, false
	}
	if l.ExpiresAt != nil && l.ExpiresAt.Before(time.Now()) {
		immichError(w, 401, "Expired shared link")
		return access{}, false
	}
	if l.Password != "" {
		c, err := r.Cookie("immich_shared_link_token")
		if err != nil || c.Value != "ok-"+key {
			immichError(w, 401, "Password required")
			return access{}, false
		}
	}
	return access{link: l}, true
}

func (f *Immich) serve(w http.ResponseWriter, r *http.Request) {
	f.Requests.Add(1)
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.Fail != 0 {
		immichError(w, f.Fail, "failure")
		return
	}
	p := strings.TrimPrefix(r.URL.Path, "/api")
	switch {
	case p == "/server/version":
		writeJSON(w, 200, map[string]any{"major": f.Version[0], "minor": f.Version[1], "patch": f.Version[2]})
	case p == "/shared-links/login" && r.Method == http.MethodPost:
		key := r.URL.Query().Get("key")
		l := f.Links[key]
		var body struct{ Password string }
		_ = json.NewDecoder(r.Body).Decode(&body)
		if l == nil {
			immichError(w, 401, "Invalid share key")
			return
		}
		if body.Password != l.Password {
			immichError(w, 401, "Invalid password")
			return
		}
		http.SetCookie(w, &http.Cookie{Name: "immich_shared_link_token", Value: "ok-" + key, Path: "/"})
		writeJSON(w, 201, f.linkJSON(l, key))
	case p == "/shared-links/me":
		a, ok := f.auth(w, r)
		if !ok {
			return
		}
		writeJSON(w, 200, f.linkJSON(a.link, r.URL.Query().Get("key")))
	case p == "/albums" && r.Method == http.MethodGet:
		a, ok := f.auth(w, r)
		if !ok {
			return
		}
		if !a.has("album.read") {
			immichError(w, 403, "Missing required permission: album.read")
			return
		}
		shared := r.URL.Query().Get("shared") == "true"
		list := []map[string]any{}
		for _, al := range f.Albums {
			if al.Shared == shared {
				list = append(list, f.albumJSON(al, false, true))
			}
		}
		writeJSON(w, 200, list)
	case strings.HasPrefix(p, "/albums/"):
		a, ok := f.auth(w, r)
		if !ok {
			return
		}
		id := strings.TrimPrefix(p, "/albums/")
		al := f.Albums[id]
		if al == nil || (a.link != nil && a.link.AlbumID != id) {
			immichError(w, 400, "Not found or no album.read access")
			return
		}
		if !a.has("album.read") {
			immichError(w, 403, "Missing required permission: album.read")
			return
		}
		metadata := a.link == nil || a.link.ShowMetadata
		withAssets := r.URL.Query().Get("withoutAssets") != "true" && !f.NoAssetsInAlbum
		writeJSON(w, 200, f.albumJSON(al, withAssets, metadata))
	case p == "/search/metadata" && r.Method == http.MethodPost:
		a, ok := f.auth(w, r)
		if !ok || a.link != nil {
			return
		}
		var body struct {
			AlbumIDs []string `json:"albumIds"`
			Page     int      `json:"page"`
			Size     int      `json:"size"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		size := max(body.Size, 1)
		page := max(body.Page, 1)
		var ids []string
		if len(body.AlbumIDs) > 0 && f.Albums[body.AlbumIDs[0]] != nil {
			ids = f.Albums[body.AlbumIDs[0]].AssetIDs
		}
		from, to := (page-1)*size, min(page*size, len(ids))
		items := []map[string]any{}
		for i := from; i < to; i++ {
			items = append(items, f.assetJSON(f.Assets[ids[i]], true))
		}
		var next any
		if to < len(ids) {
			next = strconv.Itoa(page + 1)
		}
		writeJSON(w, 200, map[string]any{"albums": map[string]any{}, "assets": map[string]any{"items": items, "nextPage": next, "total": len(ids)}})
	case strings.HasPrefix(p, "/assets/"):
		a, ok := f.auth(w, r)
		if !ok {
			return
		}
		parts := strings.Split(strings.TrimPrefix(p, "/assets/"), "/")
		as := f.Assets[parts[0]]
		if as == nil || len(parts) < 2 {
			immichError(w, 400, "Not found")
			return
		}
		if a.link != nil && !f.linkHas(a.link, parts[0]) {
			immichError(w, 400, "Not found")
			return
		}
		switch parts[1] {
		case "original":
			if a.link != nil && !a.link.AllowDownload {
				immichError(w, 403, "Download not allowed")
				return
			}
			if !a.has("asset.download") {
				immichError(w, 403, "Missing required permission: asset.download")
				return
			}
			f.Downloads.Add(1)
			w.Header().Set("Content-Type", as.Mime)
			_, _ = w.Write(as.Original)
		case "thumbnail", "video":
			if !a.has("asset.view") {
				immichError(w, 403, "Missing required permission: asset.view")
				return
			}
			b := as.Preview
			if b == nil {
				b = as.Original
			}
			f.Downloads.Add(1)
			_, _ = w.Write(b)
		default:
			immichError(w, 404, "Not found")
		}
	default:
		immichError(w, 404, "Not found")
	}
}

func (f *Immich) linkHas(l *ImmichLink, assetID string) bool {
	if l.AlbumID != "" {
		al := f.Albums[l.AlbumID]
		for _, id := range al.AssetIDs {
			if id == assetID {
				return true
			}
		}
		// The motion clip of a live photo in the album.
		for _, id := range al.AssetIDs {
			if f.Assets[id].LiveVideoID == assetID {
				return true
			}
		}
		return false
	}
	for _, id := range l.AssetIDs {
		if id == assetID {
			return true
		}
	}
	return false
}

func (f *Immich) linkJSON(l *ImmichLink, key string) map[string]any {
	m := map[string]any{"id": "link-" + key, "key": key, "allowDownload": l.AllowDownload, "showMetadata": l.ShowMetadata,
		"password": nil, "slug": nil, "allowUpload": false, "description": nil, "createdAt": time.Now().UTC().Format(time.RFC3339)}
	if l.ExpiresAt != nil {
		m["expiresAt"] = l.ExpiresAt.Format(time.RFC3339)
	} else {
		m["expiresAt"] = nil
	}
	if l.AlbumID != "" {
		m["type"] = "ALBUM"
		al := f.Albums[l.AlbumID]
		if al != nil {
			m["album"] = f.albumJSON(al, false, l.ShowMetadata)
		}
		m["assets"] = []any{}
	} else {
		m["type"] = "INDIVIDUAL"
		list := []map[string]any{}
		for _, id := range l.AssetIDs {
			list = append(list, f.assetJSON(f.Assets[id], l.ShowMetadata))
		}
		m["assets"] = list
	}
	return m
}

// ---------------------------------------------------------------- iCloud

// ICloudPhoto is an item of a fake shared album.
type ICloudPhoto struct {
	GUID      string
	Video     bool
	Caption   string
	Created   time.Time
	BatchDate time.Time
	File      []byte // the largest derivative
}

// ICloud is a fake iCloud shared streams host. Albums live on Home; a request
// for an album on Wrong answers 330 pointing at Home (the partition redirect).
type ICloud struct {
	Home  *httptest.Server
	Wrong *httptest.Server

	mu     sync.Mutex
	Albums map[string]*ICloudAlbum
	// Mode changes the answers: "" normal, "garbage" (unparsable), "nophotos"
	// (no photos list), "gone" (404), "errorbody" (an error object), "throttle" (503).
	Mode string

	Webstream atomic.Int64
	AssetURLs atomic.Int64
	Redirects atomic.Int64
}

// ICloudAlbum is a shared album.
type ICloudAlbum struct {
	Name   string
	Ctag   string
	Photos []*ICloudPhoto
}

// NewICloud starts the fake.
func NewICloud() *ICloud {
	f := &ICloud{Albums: map[string]*ICloudAlbum{}}
	f.Home = httptest.NewServer(http.HandlerFunc(f.serveHome))
	f.Wrong = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.Redirects.Add(1)
		writeJSON(w, 330, map[string]any{"X-Apple-MMe-Host": strings.TrimPrefix(f.Home.URL, "http://"), "X-Apple-MMe-Scope": "token"})
	}))
	return f
}

// Close stops both servers.
func (f *ICloud) Close() { f.Home.Close(); f.Wrong.Close() }

// Lock runs fn under the fake's lock.
func (f *ICloud) Lock(fn func()) {
	f.mu.Lock()
	defer f.mu.Unlock()
	fn()
}

// Link is the public website link of a token.
func Link(token string) string { return "https://www.icloud.com/sharedalbum/#" + token }

func checksum(b []byte) string {
	s := sha256.Sum256(b)
	return "c" + fmt.Sprintf("%x", s[:8])
}

func (f *ICloud) serveHome(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if strings.HasPrefix(r.URL.Path, "/file/") {
		sum := strings.TrimPrefix(r.URL.Path, "/file/")
		for _, al := range f.Albums {
			for _, p := range al.Photos {
				if checksum(p.File) == sum {
					_, _ = w.Write(p.File)
					return
				}
			}
		}
		http.NotFound(w, r)
		return
	}
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	if len(parts) != 3 || parts[1] != "sharedstreams" || r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	al := f.Albums[parts[0]]
	switch f.Mode {
	case "gone":
		http.NotFound(w, r)
		return
	case "throttle":
		w.WriteHeader(503)
		return
	case "errorbody":
		writeJSON(w, 200, map[string]any{"errorCode": "NOT_FOUND"})
		return
	}
	if al == nil {
		http.NotFound(w, r)
		return
	}
	switch parts[2] {
	case "webstream":
		f.Webstream.Add(1)
		switch f.Mode {
		case "garbage":
			_, _ = io.WriteString(w, "<html>maintenance</html>")
			return
		case "nophotos":
			writeJSON(w, 200, map[string]any{"streamName": al.Name, "streamCtag": al.Ctag})
			return
		}
		photos := []map[string]any{}
		for _, p := range al.Photos {
			sum := checksum(p.File)
			derivs := map[string]any{
				"342":  map[string]any{"checksum": sum + "small", "fileSize": "1200", "width": "342", "height": "256"},
				"2049": map[string]any{"checksum": sum, "fileSize": strconv.Itoa(len(p.File)), "width": "2048", "height": "1536"},
			}
			item := map[string]any{"photoGuid": p.GUID, "batchGuid": "batch-1", "dateCreated": p.Created.UTC().Format(time.RFC3339),
				"batchDateCreated": p.BatchDate.UTC().Format(time.RFC3339), "caption": p.Caption,
				"contributorFullName": "Oma Peeters", "width": "2048", "height": "1536"}
			if p.Video {
				item["mediaAssetType"] = "video"
				item["videoDuration"] = "7"
				derivs = map[string]any{
					"PosterFrame": map[string]any{"checksum": sum + "poster", "fileSize": "4000", "width": "1280", "height": "720"},
					"720p":        map[string]any{"checksum": sum, "fileSize": strconv.Itoa(len(p.File)), "width": "1280", "height": "720"},
					"360p":        map[string]any{"checksum": sum + "360", "fileSize": "100", "width": "640", "height": "360"},
				}
			}
			item["derivatives"] = derivs
			photos = append(photos, item)
		}
		writeJSON(w, 200, map[string]any{"streamName": al.Name, "userFirstName": "Oma", "userLastName": "Peeters",
			"streamCtag": al.Ctag, "itemsReturned": strconv.Itoa(len(photos)), "photos": photos, "locations": map[string]any{}})
	case "webasseturls":
		f.AssetURLs.Add(1)
		var body struct {
			PhotoGUIDs []string `json:"photoGuids"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		host := strings.TrimPrefix(f.Home.URL, "http://")
		items := map[string]any{}
		for _, g := range body.PhotoGUIDs {
			for _, p := range al.Photos {
				if p.GUID == g {
					sum := checksum(p.File)
					items[sum] = map[string]any{"url_expiry": time.Now().Add(2 * time.Hour).UTC().Format(time.RFC3339),
						"url_location": host, "url_path": "/file/" + sum}
				}
			}
		}
		writeJSON(w, 200, map[string]any{"items": items, "locations": map[string]any{host: map[string]any{"scheme": "http", "hosts": []string{host}}}})
	default:
		http.NotFound(w, r)
	}
}

// ---------------------------------------------------------------- Google

// GoogleItem is a picked media item.
type GoogleItem struct {
	ID         string
	Video      bool
	Mime       string
	File       []byte // =d / =dv
	Rendered   []byte // =w2560-h2560 (RAW and other unreadable files)
	CreateTime time.Time
	Status     string // video processingStatus; "" = READY
}

type googleSession struct {
	id      string
	items   []GoogleItem
	set     bool
	deleted bool
	expire  time.Time
	token   string
}

// Google is a fake Google OAuth token endpoint and Picker API.
type Google struct {
	*httptest.Server

	mu           sync.Mutex
	codes        map[string]string // code -> PKCE challenge
	tokens       map[string]bool   // valid access tokens
	sessions     map[string]*googleSession
	n            int
	PollEvery    string // pollInterval, default "0.05s"
	Expire       time.Duration
	TokenCalls   atomic.Int64
	Deleted      atomic.Int64
	Downloads    atomic.Int64
	LastMaxItems string
}

// NewGoogle starts the fake.
func NewGoogle() *Google {
	f := &Google{codes: map[string]string{}, tokens: map[string]bool{}, sessions: map[string]*googleSession{}, PollEvery: "0.05s", Expire: 30 * time.Minute}
	f.Server = httptest.NewServer(http.HandlerFunc(f.serve))
	return f
}

// Consent simulates Google's consent screen for an auth URL: it checks the
// parameters and returns the callback query (code and state) the browser
// would bring back.
func (f *Google) Consent(authURL string) (url.Values, error) {
	u, err := url.Parse(authURL)
	if err != nil {
		return nil, err
	}
	q := u.Query()
	if q.Get("access_type") != "online" || q.Get("code_challenge_method") != "S256" || q.Get("code_challenge") == "" ||
		q.Get("scope") != "https://www.googleapis.com/auth/photospicker.mediaitems.readonly" || q.Get("state") == "" {
		return nil, fmt.Errorf("bad auth url %s", authURL)
	}
	f.mu.Lock()
	f.n++
	code := fmt.Sprintf("code-%d", f.n)
	f.codes[code] = q.Get("code_challenge")
	f.mu.Unlock()
	return url.Values{"code": {code}, "state": {q.Get("state")}, "scope": {q.Get("scope")}}, nil
}

// Pick finishes picking in the session with these items.
func (f *Google) Pick(sessionID string, items ...GoogleItem) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if s := f.sessions[sessionID]; s != nil {
		s.items, s.set = items, true
	}
}

// SessionIDs lists sessions (and whether they were deleted).
func (f *Google) SessionIDs() map[string]bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := map[string]bool{}
	for id, s := range f.sessions {
		out[id] = s.deleted
	}
	return out
}

// ExpireSession makes a session expired.
func (f *Google) ExpireSession(id string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if s := f.sessions[id]; s != nil {
		s.expire = time.Now().Add(-time.Minute)
	}
}

func googleErr(w http.ResponseWriter, code int, status string) {
	writeJSON(w, code, map[string]any{"error": map[string]any{"code": code, "status": status, "message": status}})
}

func (f *Google) serve(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if r.URL.Path == "/token" {
		f.TokenCalls.Add(1)
		_ = r.ParseForm()
		ch, ok := f.codes[r.PostForm.Get("code")]
		sum := sha256.Sum256([]byte(r.PostForm.Get("code_verifier")))
		if !ok || base64.RawURLEncoding.EncodeToString(sum[:]) != ch || r.PostForm.Get("grant_type") != "authorization_code" ||
			r.PostForm.Get("client_secret") == "" {
			writeJSON(w, 400, map[string]any{"error": "invalid_grant"})
			return
		}
		delete(f.codes, r.PostForm.Get("code"))
		f.n++
		tok := fmt.Sprintf("tok-%d", f.n)
		f.tokens[tok] = true
		writeJSON(w, 200, map[string]any{"access_token": tok, "expires_in": 3599, "token_type": "Bearer",
			"scope": "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"})
		return
	}
	tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !f.tokens[tok] {
		googleErr(w, 401, "UNAUTHENTICATED")
		return
	}
	switch {
	case r.URL.Path == "/v1/sessions" && r.Method == http.MethodPost:
		var body struct {
			PickingConfig struct {
				MaxItemCount string `json:"maxItemCount"`
			} `json:"pickingConfig"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		f.LastMaxItems = body.PickingConfig.MaxItemCount
		f.n++
		s := &googleSession{id: fmt.Sprintf("sess-%d", f.n), expire: time.Now().Add(f.Expire), token: tok}
		f.sessions[s.id] = s
		writeJSON(w, 200, f.sessionJSON(s))
	case strings.HasPrefix(r.URL.Path, "/v1/sessions/"):
		s := f.sessions[strings.TrimPrefix(r.URL.Path, "/v1/sessions/")]
		if s == nil || s.deleted {
			googleErr(w, 404, "NOT_FOUND")
			return
		}
		if r.Method == http.MethodDelete {
			s.deleted = true
			f.Deleted.Add(1)
			writeJSON(w, 200, map[string]any{})
			return
		}
		if time.Now().After(s.expire) {
			googleErr(w, 403, "PERMISSION_DENIED")
			return
		}
		writeJSON(w, 200, f.sessionJSON(s))
	case r.URL.Path == "/v1/mediaItems":
		s := f.sessions[r.URL.Query().Get("sessionId")]
		if s == nil || s.deleted {
			googleErr(w, 404, "NOT_FOUND")
			return
		}
		if !s.set {
			googleErr(w, 400, "FAILED_PRECONDITION")
			return
		}
		start, _ := strconv.Atoi(r.URL.Query().Get("pageToken"))
		size, _ := strconv.Atoi(r.URL.Query().Get("pageSize"))
		size = min(max(size, 1), 100)
		end := min(start+size, len(s.items))
		list := []map[string]any{}
		for _, it := range s.items[start:end] {
			typ, status := "PHOTO", it.Status
			meta := map[string]any{"width": 1600, "height": 1200}
			if it.Video {
				typ = "VIDEO"
				if status == "" {
					status = "READY"
				}
				meta["videoMetadata"] = map[string]any{"fps": 30, "processingStatus": status}
			} else {
				meta["photoMetadata"] = map[string]any{}
			}
			list = append(list, map[string]any{"id": it.ID, "createTime": it.CreateTime.UTC().Format(time.RFC3339), "type": typ,
				"mediaFile": map[string]any{"baseUrl": f.URL + "/files/" + it.ID, "mimeType": it.Mime, "filename": it.ID,
					"mediaFileMetadata": meta}})
		}
		out := map[string]any{"mediaItems": list}
		if end < len(s.items) {
			out["nextPageToken"] = strconv.Itoa(end)
		}
		writeJSON(w, 200, out)
	case strings.HasPrefix(r.URL.Path, "/files/"):
		name := strings.TrimPrefix(r.URL.Path, "/files/")
		id, suffix, _ := strings.Cut(name, "=")
		for _, s := range f.sessions {
			for _, it := range s.items {
				if it.ID != id {
					continue
				}
				f.Downloads.Add(1)
				switch {
				case suffix == "d" && !it.Video, suffix == "dv" && it.Video:
					_, _ = w.Write(it.File)
				case strings.HasPrefix(suffix, "w") && it.Rendered != nil:
					_, _ = w.Write(it.Rendered)
				default:
					googleErr(w, 400, "INVALID_ARGUMENT")
				}
				return
			}
		}
		googleErr(w, 404, "NOT_FOUND")
	default:
		googleErr(w, 404, "NOT_FOUND")
	}
}

func (f *Google) sessionJSON(s *googleSession) map[string]any {
	m := map[string]any{"id": s.id, "pickerUri": "https://photos.google.com/picker/" + s.id,
		"expireTime": s.expire.UTC().Format(time.RFC3339Nano), "mediaItemsSet": s.set,
		"pickingConfig": map[string]any{"maxItemCount": f.LastMaxItems}}
	if !s.set {
		m["pollingConfig"] = map[string]any{"pollInterval": f.PollEvery, "timeoutIn": "1800s"}
	}
	return m
}
