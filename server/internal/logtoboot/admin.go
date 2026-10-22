package logtoboot

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
)

// Fixed identifiers in every Logto OSS installation.
const (
	adminConsoleClientID = "admin-console"
	// Management API of the default tenant, as seen from the admin tenant.
	managementAPIResource = "https://default.logto.app/api"
	managementAPIScope    = "all"
	// Machine-to-machine role seeded in the default tenant.
	managementAccessRoleName = "Logto Management API access"
)

// adminSession signs in to Logto's admin tenant as the console admin (or
// creates that account on a fresh instance) and returns a Management API
// access token, using the same OIDC + Experience API flow the admin console
// itself uses. Nothing touches Logto's database directly.
//
// Logto OSS makes the first account registered through the admin console the
// administrator and then closes registration (SignInMode SignIn), so creating
// it here, before the console is reachable, removes the "first visitor
// becomes admin" window.
type adminSession struct {
	hc       *http.Client // own cookie jar, no automatic redirects
	endpoint string       // Logto ADMIN_ENDPOINT (public terms)
	log      func(msg string, args ...any)
}

func newAdminSession(transport http.RoundTripper, endpoint string, log func(string, ...any)) *adminSession {
	jar, _ := cookiejar.New(nil)
	return &adminSession{
		hc: &http.Client{
			Transport: transport,
			Jar:       jar,
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
		endpoint: endpoint,
		log:      log,
	}
}

// token returns a Management API token for the default tenant. created
// reports whether the admin account was registered by this call.
func (s *adminSession) token(ctx context.Context, username, password string) (token string, created bool, err error) {
	var sie struct {
		SignInMode string `json:"signInMode"`
	}
	if err := doJSON(ctx, s.hc, http.MethodGet, s.endpoint+"/api/.well-known/sign-in-exp", nil, nil, &sie); err != nil {
		return "", false, fmt.Errorf("admin tenant sign-in experience: %w", err)
	}
	register := sie.SignInMode == "Register"

	verifier, challenge := pkce()
	state := randString(16)
	redirectURI := s.endpoint + "/console/callback"
	q := url.Values{
		"client_id":             {adminConsoleClientID},
		"redirect_uri":          {redirectURI},
		"response_type":         {"code"},
		"scope":                 {"openid " + managementAPIScope},
		"resource":              {managementAPIResource},
		"state":                 {state},
		"code_challenge":        {challenge},
		"code_challenge_method": {"S256"},
	}
	if register {
		q.Set("first_screen", "register")
	}
	loc, err := s.get(ctx, s.endpoint+"/oidc/auth?"+q.Encode())
	if err != nil {
		return "", false, fmt.Errorf("start admin sign-in: %w", err)
	}
	if loc == "" || strings.HasPrefix(loc, redirectURI) {
		return "", false, fmt.Errorf("start admin sign-in: unexpected redirect %q", redactURL(loc))
	}

	api := s.endpoint + "/api/experience"
	var redirect struct {
		RedirectTo string `json:"redirectTo"`
	}
	var ver struct {
		VerificationID string `json:"verificationId"`
	}
	ident := map[string]any{"type": "username", "value": username}
	if register {
		s.log("creating the Logto console admin account", "username", username)
		if err := doJSON(ctx, s.hc, http.MethodPut, api, nil, map[string]any{"interactionEvent": "Register"}, nil); err != nil {
			return "", false, err
		}
		if err := doJSON(ctx, s.hc, http.MethodPost, api+"/verification/new-password-identity", nil,
			map[string]any{"identifier": ident, "password": password}, &ver); err != nil {
			return "", false, fmt.Errorf("register admin: %w", err)
		}
	} else {
		if err := doJSON(ctx, s.hc, http.MethodPut, api, nil, map[string]any{"interactionEvent": "SignIn"}, nil); err != nil {
			return "", false, err
		}
		if err := doJSON(ctx, s.hc, http.MethodPost, api+"/verification/password", nil,
			map[string]any{"identifier": ident, "password": password}, &ver); err != nil {
			return "", false, fmt.Errorf("admin sign-in (check KINFRAME_LOGTO_ADMIN_USERNAME/PASSWORD): %w", err)
		}
	}
	if err := doJSON(ctx, s.hc, http.MethodPost, api+"/identification", nil, map[string]any{"verificationId": ver.VerificationID}, nil); err != nil {
		return "", false, fmt.Errorf("admin identification: %w", err)
	}
	if err := doJSON(ctx, s.hc, http.MethodPost, api+"/submit", nil, nil, &redirect); err != nil {
		return "", false, fmt.Errorf("admin submit: %w", err)
	}

	// Follow the OIDC redirects (auth → consent → auth) to the callback.
	next := redirect.RedirectTo
	var code string
	for range 8 {
		if strings.HasPrefix(next, redirectURI) {
			cb, err := url.Parse(next)
			if err != nil {
				return "", false, err
			}
			if e := cb.Query().Get("error"); e != "" {
				return "", false, fmt.Errorf("admin sign-in: %s: %s", e, cb.Query().Get("error_description"))
			}
			if cb.Query().Get("state") != state {
				return "", false, errors.New("admin sign-in: state mismatch")
			}
			code = cb.Query().Get("code")
			break
		}
		if next, err = s.get(ctx, s.abs(next)); err != nil {
			return "", false, fmt.Errorf("admin sign-in: %w", err)
		}
		if next == "" {
			return "", false, errors.New("admin sign-in: redirect chain ended before the callback")
		}
	}
	if code == "" {
		return "", false, errors.New("admin sign-in: no authorization code")
	}

	tok, err := postToken(ctx, s.hc, s.endpoint+"/oidc/token", url.Values{
		"grant_type":    {"authorization_code"},
		"code":          {code},
		"redirect_uri":  {redirectURI},
		"client_id":     {adminConsoleClientID},
		"code_verifier": {verifier},
		"resource":      {managementAPIResource},
	}, "", "")
	if err != nil {
		return "", false, fmt.Errorf("admin token: %w", err)
	}
	return tok.AccessToken, register, nil
}

// get issues a GET and returns the Location of a redirect ("" otherwise).
func (s *adminSession) get(ctx context.Context, u string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return "", err
	}
	resp, err := s.hc.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	switch {
	case resp.StatusCode >= 300 && resp.StatusCode < 400:
		return resp.Header.Get("Location"), nil
	case resp.StatusCode >= 400:
		return "", fmt.Errorf("GET %s: %d %s", redactURL(u), resp.StatusCode, truncate(strings.TrimSpace(string(body)), 300))
	}
	return "", nil
}

func (s *adminSession) abs(loc string) string {
	if strings.HasPrefix(loc, "/") {
		return s.endpoint + loc
	}
	return loc
}

func pkce() (verifier, challenge string) {
	verifier = randString(32)
	sum := sha256.Sum256([]byte(verifier))
	return verifier, base64.RawURLEncoding.EncodeToString(sum[:])
}

func randString(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}
