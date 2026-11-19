package notify

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// FCMSender delivers Firebase Cloud Messaging data messages (HTTP v1 API) to
// the family app's store builds. It signs in with a Google service account
// (the JSON key Firebase exports) and caches the access token.
type FCMSender struct {
	ProjectID   string
	ClientEmail string
	PrivateKey  *rsa.PrivateKey
	TokenURL    string // default https://oauth2.googleapis.com/token
	SendBase    string // default https://fcm.googleapis.com
	Client      *http.Client

	mu      sync.Mutex
	token   string
	expires time.Time
}

// NewFCMSender reads a service account JSON key.
func NewFCMSender(serviceAccountJSON []byte) (*FCMSender, error) {
	var sa struct {
		Type        string `json:"type"`
		ProjectID   string `json:"project_id"`
		ClientEmail string `json:"client_email"`
		PrivateKey  string `json:"private_key"`
		TokenURI    string `json:"token_uri"`
	}
	if err := json.Unmarshal(serviceAccountJSON, &sa); err != nil {
		return nil, fmt.Errorf("fcm credentials: %w", err)
	}
	if sa.Type != "service_account" || sa.ProjectID == "" || sa.ClientEmail == "" || sa.PrivateKey == "" {
		return nil, errors.New("fcm credentials: not a service account key")
	}
	block, _ := pem.Decode([]byte(sa.PrivateKey))
	if block == nil {
		return nil, errors.New("fcm credentials: no PEM private key")
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("fcm credentials: %w", err)
	}
	rk, ok := k.(*rsa.PrivateKey)
	if !ok {
		return nil, errors.New("fcm credentials: private key is not RSA")
	}
	return &FCMSender{ProjectID: sa.ProjectID, ClientEmail: sa.ClientEmail, PrivateKey: rk, TokenURL: sa.TokenURI}, nil
}

func (s *FCMSender) client() *http.Client {
	if s.Client != nil {
		return s.Client
	}
	return &http.Client{Timeout: 15 * time.Second}
}

// accessToken returns a cached OAuth token, fetching a new one with a signed
// JWT assertion (RFC 7523) when it is about to expire.
func (s *FCMSender) accessToken(ctx context.Context) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.token != "" && time.Until(s.expires) > time.Minute {
		return s.token, nil
	}
	tokenURL := s.TokenURL
	if tokenURL == "" {
		tokenURL = "https://oauth2.googleapis.com/token"
	}
	now := time.Now()
	enc := func(v any) string { b, _ := json.Marshal(v); return base64.RawURLEncoding.EncodeToString(b) }
	unsigned := enc(map[string]string{"alg": "RS256", "typ": "JWT"}) + "." + enc(map[string]any{
		"iss": s.ClientEmail, "scope": "https://www.googleapis.com/auth/firebase.messaging",
		"aud": tokenURL, "iat": now.Unix(), "exp": now.Add(time.Hour).Unix(),
	})
	sum := sha256.Sum256([]byte(unsigned))
	sig, err := rsa.SignPKCS1v15(rand.Reader, s.PrivateKey, crypto.SHA256, sum[:])
	if err != nil {
		return "", err
	}
	form := url.Values{"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"}, "assertion": {unsigned + "." + base64.RawURLEncoding.EncodeToString(sig)}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, tokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := s.client().Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var t struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return "", fmt.Errorf("fcm token: %s: %s", resp.Status, b)
	}
	if err := json.NewDecoder(resp.Body).Decode(&t); err != nil || t.AccessToken == "" {
		return "", fmt.Errorf("fcm token: bad response")
	}
	s.token, s.expires = t.AccessToken, now.Add(time.Duration(t.ExpiresIn)*time.Second)
	return s.token, nil
}

// fcmData flattens the push payload into FCM's string-only data map: type,
// key, title, body and every id ("frame_id", "send_id", ...).
func fcmData(payload []byte) (map[string]string, error) {
	var p struct {
		Type    any               `json:"type"`
		Key     string            `json:"key"`
		Title   string            `json:"title"`
		Body    string            `json:"body"`
		IDs     map[string]string `json:"ids"`
		Tag     string            `json:"tag"`
		Actions []string          `json:"actions"`
		Badge   *int              `json:"badge"`
		Server  string            `json:"server"`
	}
	if err := json.Unmarshal(payload, &p); err != nil {
		return nil, err
	}
	d := map[string]string{"key": p.Key, "title": p.Title, "body": p.Body}
	if p.Server != "" {
		d["server"] = p.Server // README §5.22: the account a tap opens
	}
	if p.Type != nil {
		d["type"] = fmt.Sprint(p.Type)
	}
	if p.Tag != "" {
		d["tag"] = p.Tag
	}
	if len(p.Actions) > 0 {
		d["actions"] = strings.Join(p.Actions, ",") // README §6.3: the reminder buttons
	}
	if p.Badge != nil {
		d["badge"] = strconv.Itoa(*p.Badge)
	}
	for k, v := range p.IDs {
		if _, taken := d[k]; !taken {
			d[k] = v
		}
	}
	return d, nil
}

func (s *FCMSender) Send(ctx context.Context, ep store.PushEndpoint, payload []byte) (bool, error) {
	data, err := fcmData(payload)
	if err != nil {
		return false, err
	}
	tok, err := s.accessToken(ctx)
	if err != nil {
		return false, err
	}
	android := map[string]any{"priority": "high", "ttl": "86400s"}
	if t := data["tag"]; t != "" {
		android["collapse_key"] = t
	}
	body, _ := json.Marshal(map[string]any{"message": map[string]any{
		"token": ep.Endpoint, "data": data, "android": android,
	}})
	base := s.SendBase
	if base == "" {
		base = "https://fcm.googleapis.com"
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, base+"/v1/projects/"+url.PathEscape(s.ProjectID)+"/messages:send", bytes.NewReader(body))
	if err != nil {
		return false, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := s.client().Do(req)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		return false, nil
	}
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
	// The token is gone for good when FCM says UNREGISTERED (404) or that the token itself is invalid.
	if resp.StatusCode == http.StatusNotFound || bytes.Contains(b, []byte("UNREGISTERED")) ||
		(resp.StatusCode == http.StatusBadRequest && bytes.Contains(b, []byte("registration token"))) {
		return true, nil
	}
	if resp.StatusCode == http.StatusUnauthorized {
		s.mu.Lock()
		s.token = ""
		s.mu.Unlock()
	}
	return false, fmt.Errorf("fcm: %s: %s", resp.Status, bytes.TrimSpace(b[:min(len(b), 300)]))
}
