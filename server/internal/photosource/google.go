package photosource

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/netguard"
)

// GoogleScope is the only scope kinframe asks for: the photos the person picks.
const GoogleScope = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"

// Google is the Google Photos Picker API with server-side OAuth
// (https://developers.google.com/photos/picker). No refresh token is ever
// requested (access_type=online).
type Google struct {
	Guard        *netguard.Guard
	ClientID     string
	ClientSecret string
	RedirectURI  string // <public URL>/v1/oauth/google/callback

	// Endpoints (tests point them at fakes).
	AuthURL  string // https://accounts.google.com/o/oauth2/v2/auth
	TokenURL string // https://oauth2.googleapis.com/token
	APIBase  string // https://photospicker.googleapis.com/v1
	// Pinned are the hosts API calls and downloads may go to.
	Pinned []string
}

func (g *Google) authURL() string {
	if g.AuthURL != "" {
		return g.AuthURL
	}
	return "https://accounts.google.com/o/oauth2/v2/auth"
}

func (g *Google) tokenURL() string {
	if g.TokenURL != "" {
		return g.TokenURL
	}
	return "https://oauth2.googleapis.com/token"
}

func (g *Google) apiBase() string {
	if g.APIBase != "" {
		return strings.TrimRight(g.APIBase, "/")
	}
	return "https://photospicker.googleapis.com/v1"
}

func (g *Google) client() *http.Client {
	pins := g.Pinned
	if len(pins) == 0 {
		pins = []string{"googleapis.com", "googleusercontent.com", "google.com"}
	}
	return g.Guard.Client(netguard.Options{Pinned: pins})
}

// Configured reports whether an OAuth client is set.
func (g *Google) Configured() bool { return g != nil && g.ClientID != "" && g.ClientSecret != "" }

// RandomToken is a URL-safe random value (OAuth state, PKCE verifier).
func RandomToken() string {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

// Consent builds the authorization URL: PKCE (S256), the given state,
// access_type=online (no refresh token).
func (g *Google) Consent(state, verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	q := url.Values{
		"client_id":             {g.ClientID},
		"redirect_uri":          {g.RedirectURI},
		"response_type":         {"code"},
		"scope":                 {GoogleScope},
		"access_type":           {"online"},
		"state":                 {state},
		"code_challenge":        {base64.RawURLEncoding.EncodeToString(sum[:])},
		"code_challenge_method": {"S256"},
		"prompt":                {"select_account"},
	}
	return g.authURL() + "?" + q.Encode()
}

// GoogleError is a failed Google call.
type GoogleError struct {
	Status int
	Reason string // Google's error status (PERMISSION_DENIED, FAILED_PRECONDITION, …) or OAuth error
}

func (e *GoogleError) Error() string { return fmt.Sprintf("google: %d %s", e.Status, e.Reason) }

// Auth reports an authorization failure (expired or revoked token).
func (e *GoogleError) Auth() bool { return e.Status == http.StatusUnauthorized }

func googleErr(status int, body []byte) error {
	var r struct {
		Error any `json:"error"`
	}
	reason := ""
	if json.Unmarshal(body, &r) == nil {
		switch v := r.Error.(type) {
		case string:
			reason = v
		case map[string]any:
			if s, ok := v["status"].(string); ok {
				reason = s
			}
		}
	}
	return &GoogleError{Status: status, Reason: reason}
}

// Token is an OAuth access token.
type Token struct {
	AccessToken string
	ExpiresAt   time.Time
}

// Exchange trades the authorization code (with the PKCE verifier) for an
// access token.
func (g *Google) Exchange(ctx context.Context, code, verifier string) (*Token, error) {
	form := url.Values{
		"code": {code}, "client_id": {g.ClientID}, "client_secret": {g.ClientSecret},
		"redirect_uri": {g.RedirectURI}, "grant_type": {"authorization_code"}, "code_verifier": {verifier},
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, g.tokenURL(), strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	resp, err := g.client().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := readCapped(resp.Body, 1<<20)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, googleErr(resp.StatusCode, body)
	}
	var t struct {
		AccessToken string  `json:"access_token"`
		ExpiresIn   float64 `json:"expires_in"`
		Scope       string  `json:"scope"`
	}
	if err := json.Unmarshal(body, &t); err != nil || t.AccessToken == "" {
		return nil, errors.New("google: token response without access_token")
	}
	if t.ExpiresIn <= 0 {
		t.ExpiresIn = 3600
	}
	return &Token{AccessToken: t.AccessToken, ExpiresAt: time.Now().Add(time.Duration(t.ExpiresIn) * time.Second)}, nil
}

func (g *Google) call(ctx context.Context, method, path, token string, body any, out any) error {
	var rd *bytes.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	} else {
		rd = bytes.NewReader(nil)
	}
	ctx, cancel := context.WithTimeout(ctx, MetadataTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, method, g.apiBase()+path, rd)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := g.client().Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, err := readCapped(resp.Body, 16<<20)
	if err != nil {
		return err
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return googleErr(resp.StatusCode, data)
	}
	if out != nil && len(data) > 0 {
		if err := json.Unmarshal(data, out); err != nil {
			return fmt.Errorf("google: unexpected answer: %w", err)
		}
	}
	return nil
}

// PickingSession is a Picker session.
type PickingSession struct {
	ID            string
	PickerURI     string
	PollInterval  time.Duration
	Timeout       time.Duration // timeoutIn: stop polling after this
	ExpireTime    *time.Time
	MediaItemsSet bool
}

type sessionJSON struct {
	ID            string `json:"id"`
	PickerURI     string `json:"pickerUri"`
	PollingConfig *struct {
		PollInterval string `json:"pollInterval"`
		TimeoutIn    string `json:"timeoutIn"`
	} `json:"pollingConfig"`
	ExpireTime    string `json:"expireTime"`
	MediaItemsSet bool   `json:"mediaItemsSet"`
}

// ParseGoogleDuration reads protobuf Duration strings ("3.5s").
func ParseGoogleDuration(s string) time.Duration {
	s = strings.TrimSpace(s)
	if !strings.HasSuffix(s, "s") {
		return 0
	}
	f, err := strconv.ParseFloat(strings.TrimSuffix(s, "s"), 64)
	if err != nil || f < 0 {
		return 0
	}
	return time.Duration(f * float64(time.Second))
}

func (j sessionJSON) session() *PickingSession {
	out := &PickingSession{ID: j.ID, PickerURI: j.PickerURI, MediaItemsSet: j.MediaItemsSet, ExpireTime: parseTime(j.ExpireTime)}
	if j.PollingConfig != nil {
		out.PollInterval = ParseGoogleDuration(j.PollingConfig.PollInterval)
		out.Timeout = ParseGoogleDuration(j.PollingConfig.TimeoutIn)
	}
	return out
}

// CreateSession starts a picker session for at most maxItems items
// (pickingConfig.maxItemCount is an int64 string; Google caps it at 2000).
func (g *Google) CreateSession(ctx context.Context, token string, maxItems int) (*PickingSession, error) {
	var j sessionJSON
	body := map[string]any{"pickingConfig": map[string]any{"maxItemCount": strconv.Itoa(maxItems)}}
	if err := g.call(ctx, http.MethodPost, "/sessions", token, body, &j); err != nil {
		return nil, err
	}
	if j.ID == "" || j.PickerURI == "" {
		return nil, errors.New("google: session without id or pickerUri")
	}
	return j.session(), nil
}

// GetSession polls a session.
func (g *Google) GetSession(ctx context.Context, token, id string) (*PickingSession, error) {
	var j sessionJSON
	if err := g.call(ctx, http.MethodGet, "/sessions/"+url.PathEscape(id), token, nil, &j); err != nil {
		return nil, err
	}
	return j.session(), nil
}

// DeleteSession deletes a session (best practice once the bytes are fetched).
func (g *Google) DeleteSession(ctx context.Context, token, id string) error {
	return g.call(ctx, http.MethodDelete, "/sessions/"+url.PathEscape(id), token, nil, nil)
}

// PickedItem is one picked media item.
type PickedItem struct {
	ID         string
	CreateTime *time.Time
	Type       string // PHOTO, VIDEO
	BaseURL    string
	MimeType   string
	Filename   string
	// VideoStatus is videoMetadata.processingStatus (READY when downloadable).
	VideoStatus string
}

// ListItems returns one page of the session's picked items (pages of 100).
func (g *Google) ListItems(ctx context.Context, token, sessionID, pageToken string) ([]PickedItem, string, error) {
	q := url.Values{"sessionId": {sessionID}, "pageSize": {"100"}}
	if pageToken != "" {
		q.Set("pageToken", pageToken)
	}
	var r struct {
		MediaItems []struct {
			ID         string `json:"id"`
			CreateTime string `json:"createTime"`
			Type       string `json:"type"`
			MediaFile  struct {
				BaseURL           string `json:"baseUrl"`
				MimeType          string `json:"mimeType"`
				Filename          string `json:"filename"`
				MediaFileMetadata struct {
					VideoMetadata *struct {
						ProcessingStatus string `json:"processingStatus"`
					} `json:"videoMetadata"`
				} `json:"mediaFileMetadata"`
			} `json:"mediaFile"`
		} `json:"mediaItems"`
		NextPageToken string `json:"nextPageToken"`
	}
	if err := g.call(ctx, http.MethodGet, "/mediaItems?"+q.Encode(), token, nil, &r); err != nil {
		return nil, "", err
	}
	out := make([]PickedItem, 0, len(r.MediaItems))
	for _, m := range r.MediaItems {
		it := PickedItem{ID: m.ID, CreateTime: parseTime(m.CreateTime), Type: m.Type, BaseURL: m.MediaFile.BaseURL,
			MimeType: m.MediaFile.MimeType, Filename: m.MediaFile.Filename}
		if m.MediaFile.MediaFileMetadata.VideoMetadata != nil {
			it.VideoStatus = m.MediaFile.MediaFileMetadata.VideoMetadata.ProcessingStatus
		}
		if it.Type == "" && strings.HasPrefix(it.MimeType, "video/") {
			it.Type = "VIDEO"
		}
		out = append(out, it)
	}
	return out, r.NextPageToken, nil
}

// DownloadItem fetches baseUrl with a suffix (=d photos with metadata but no
// location, =dv videos, =w2560-h2560 a rendered JPEG) and the bearer token.
func (g *Google) DownloadItem(ctx context.Context, token, baseURL, suffix, dst string, max int64) (*Download, error) {
	ctx, cancel := context.WithTimeout(ctx, DownloadTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, baseURL+suffix, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("User-Agent", UserAgent)
	resp, err := g.client().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := readCapped(resp.Body, 1<<16)
		return nil, googleErr(resp.StatusCode, b)
	}
	if resp.ContentLength > max {
		return nil, errf(CodeTooLarge, "the file is larger than %d MB", max>>20)
	}
	d, err := saveCapped(resp.Body, dst, max)
	if d != nil {
		d.Variant = strings.TrimPrefix(suffix, "=")
	}
	return d, err
}
