package logtoboot

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

// rewriteTransport sends requests addressed to Logto's configured (public)
// endpoints to the address this process can actually reach, the way a reverse
// proxy would: Host and X-Forwarded-* carry the public origin. Logto needs
// that (with TRUST_PROXY_HEADER=1) to pick the right tenant, issuer and
// cookie settings, so every URL the bootstrap builds or receives in a redirect
// stays in public terms.
type rewriteTransport struct {
	base   http.RoundTripper
	routes map[string]*url.URL // public scheme://host → internal base URL
}

func newRewriteTransport(base http.RoundTripper, pairs ...[2]*url.URL) *rewriteTransport {
	t := &rewriteTransport{base: base, routes: map[string]*url.URL{}}
	for _, p := range pairs {
		pub, in := p[0], p[1]
		if pub == nil || in == nil || originOf(pub) == originOf(in) {
			continue
		}
		t.routes[originOf(pub)] = in
	}
	return t
}

func originOf(u *url.URL) string { return u.Scheme + "://" + u.Host }

func (t *rewriteTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	in, ok := t.routes[originOf(req.URL)]
	if !ok {
		return t.base.RoundTrip(req)
	}
	r := req.Clone(req.Context())
	r.Host = req.URL.Host
	r.URL.Scheme = in.Scheme
	r.URL.Host = in.Host
	r.Header.Set("X-Forwarded-Proto", req.URL.Scheme)
	r.Header.Set("X-Forwarded-Host", req.URL.Host)
	return t.base.RoundTrip(r)
}

// apiError is a non-2xx answer from Logto.
type apiError struct {
	Method string
	URL    string
	Status int
	Code   string // Logto error code, e.g. "entity.not_exists"
	Body   string
}

func (e *apiError) Error() string {
	return fmt.Sprintf("%s %s: %d %s", e.Method, e.URL, e.Status, e.Body)
}

func isStatus(err error, status int) bool {
	var ae *apiError
	return errors.As(err, &ae) && ae.Status == status
}

// doJSON sends in (if non-nil) as JSON and decodes a 2xx response into out
// (if non-nil). The bearer token, if any, is added by the caller via hdr.
func doJSON(ctx context.Context, hc *http.Client, method, u string, hdr http.Header, in, out any) error {
	var body io.Reader
	if in != nil {
		b, err := json.Marshal(in)
		if err != nil {
			return err
		}
		body = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, u, body)
	if err != nil {
		return err
	}
	for k, v := range hdr {
		req.Header[k] = v
	}
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	resp, err := hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		ae := &apiError{Method: method, URL: redactURL(u), Status: resp.StatusCode, Body: strings.TrimSpace(truncate(string(raw), 500))}
		var e struct {
			Code string `json:"code"`
		}
		if json.Unmarshal(raw, &e) == nil {
			ae.Code = e.Code
		}
		return ae
	}
	if out != nil && len(raw) > 0 {
		if err := json.Unmarshal(raw, out); err != nil {
			return fmt.Errorf("%s %s: decode: %w", method, redactURL(u), err)
		}
	}
	return nil
}

func redactURL(u string) string {
	if i := strings.IndexByte(u, '?'); i >= 0 {
		return u[:i]
	}
	return u
}

func truncate(s string, n int) string {
	if len(s) > n {
		return s[:n] + "…"
	}
	return s
}

// tokenResponse is an OAuth 2.0 token endpoint answer.
type tokenResponse struct {
	AccessToken string `json:"access_token"`
	ExpiresIn   int    `json:"expires_in"`
	Scope       string `json:"scope"`
}

func postToken(ctx context.Context, hc *http.Client, tokenURL string, form url.Values, basicUser, basicPass string) (*tokenResponse, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, tokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	if basicUser != "" {
		req.SetBasicAuth(url.QueryEscape(basicUser), url.QueryEscape(basicPass))
	}
	resp, err := hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		// The body is an OAuth error object; it never echoes the secret.
		return nil, fmt.Errorf("token endpoint: %d %s", resp.StatusCode, truncate(strings.TrimSpace(string(raw)), 300))
	}
	var t tokenResponse
	if err := json.Unmarshal(raw, &t); err != nil || t.AccessToken == "" {
		return nil, fmt.Errorf("token endpoint: no access_token in response")
	}
	return &t, nil
}
