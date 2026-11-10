package auth

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// LogtoAccounts deletes sign-in accounts in Logto through its Management API,
// with the machine-to-machine app that bootstrap-logto creates. Deleting a
// Logto user also removes its social identities (Apple, Google, ...).
type LogtoAccounts struct {
	// Endpoint is Logto's public ENDPOINT (the issuer without /oidc); URL is
	// where this process reaches it, e.g. http://logto:3001. Requests to URL
	// carry the endpoint's host in Host and X-Forwarded-*, the way a reverse
	// proxy would: Logto picks the tenant and issuer from it.
	Endpoint string
	URL      string
	ID       string // KINFRAME_LOGTO_M2M_ID
	Secret   string // KINFRAME_LOGTO_M2M_SECRET
	HTTP     *http.Client

	mu      sync.Mutex
	token   string
	expires time.Time
}

const (
	logtoManagementResource = "https://default.logto.app/api"
	logtoManagementScope    = "all"
)

// Delete removes the Logto user behind a token subject. A user that is
// already gone counts as deleted.
func (l *LogtoAccounts) Delete(ctx context.Context, subject string) error {
	tok, err := l.accessToken(ctx)
	if err != nil {
		return err
	}
	req, err := l.request(ctx, http.MethodDelete, "/api/users/"+url.PathEscape(subject), nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := l.HTTP.Do(req)
	if err != nil {
		return fmt.Errorf("logto: delete user: %w", err)
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case http.StatusOK, http.StatusNoContent, http.StatusNotFound:
		return nil
	default:
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 300))
		return fmt.Errorf("logto: delete user: %d %s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
}

// accessToken returns a cached Management API token, fetching a new one a
// minute before the old one expires.
func (l *LogtoAccounts) accessToken(ctx context.Context) (string, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.token != "" && time.Now().Before(l.expires) {
		return l.token, nil
	}
	form := url.Values{
		"grant_type": {"client_credentials"},
		"resource":   {logtoManagementResource},
		"scope":      {logtoManagementScope},
	}
	req, err := l.request(ctx, http.MethodPost, "/oidc/token", strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.SetBasicAuth(url.QueryEscape(l.ID), url.QueryEscape(l.Secret))
	resp, err := l.HTTP.Do(req)
	if err != nil {
		return "", fmt.Errorf("logto: token: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		// The body is an OAuth error object; it never echoes the secret.
		return "", fmt.Errorf("logto: token: %d %.300s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	var t struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(raw, &t); err != nil || t.AccessToken == "" {
		return "", fmt.Errorf("logto: token: no access_token in response")
	}
	l.token = t.AccessToken
	l.expires = time.Now().Add(time.Duration(t.ExpiresIn)*time.Second - time.Minute)
	return l.token, nil
}

func (l *LogtoAccounts) request(ctx context.Context, method, path string, body io.Reader) (*http.Request, error) {
	base := l.URL
	if base == "" {
		base = l.Endpoint
	}
	req, err := http.NewRequestWithContext(ctx, method, base+path, body)
	if err != nil {
		return nil, err
	}
	if pub, err := url.Parse(l.Endpoint); err == nil && pub.Host != "" && base != l.Endpoint {
		req.Host = pub.Host
		req.Header.Set("X-Forwarded-Proto", pub.Scheme)
		req.Header.Set("X-Forwarded-Host", pub.Host)
	}
	return req, nil
}
