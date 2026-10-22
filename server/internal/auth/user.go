package auth

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/MicahParks/keyfunc/v3"
	"github.com/golang-jwt/jwt/v5"
)

// OperatorScope grants operator endpoints.
const OperatorScope = "kinframe:operate"

// UserClaims is what the API needs from a person's access token.
type UserClaims struct {
	Subject   string
	Email     string
	Name      string
	Scopes    []string
	ExpiresAt time.Time
}

func (c UserClaims) HasScope(s string) bool {
	for _, x := range c.Scopes {
		if x == s {
			return true
		}
	}
	return false
}

// UserVerifier validates a bearer token from a person.
type UserVerifier interface {
	Verify(ctx context.Context, token string) (UserClaims, error)
}

type tokenClaims struct {
	jwt.RegisteredClaims
	Scope string `json:"scope,omitempty"`
	Email string `json:"email,omitempty"`
	Name  string `json:"name,omitempty"`
}

func (c tokenClaims) user() UserClaims {
	u := UserClaims{Subject: c.Subject, Email: c.Email, Name: c.Name, Scopes: strings.Fields(c.Scope)}
	if c.ExpiresAt != nil {
		u.ExpiresAt = c.ExpiresAt.Time
	}
	return u
}

func mapJWTError(err error) error {
	if errors.Is(err, jwt.ErrTokenExpired) {
		return ErrExpired
	}
	return fmt.Errorf("%w: %v", ErrInvalid, err)
}

// OIDCVerifier validates Logto access tokens against the issuer's JWKS.
// The JWKS is located lazily so the server can start before Logto is up.
type OIDCVerifier struct {
	Issuer   string
	Audience string
	JWKSURL  string
	Client   *http.Client

	mu sync.Mutex
	kf keyfunc.Keyfunc
}

func (v *OIDCVerifier) keyfunc(ctx context.Context) (keyfunc.Keyfunc, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.kf != nil {
		return v.kf, nil
	}
	url := v.JWKSURL
	if url == "" {
		var err error
		if url, err = v.discoverJWKS(ctx); err != nil {
			return nil, err
		}
	}
	// Background refresh lives for the process lifetime.
	kf, err := keyfunc.NewDefaultCtx(context.Background(), []string{url})
	if err != nil {
		return nil, err
	}
	v.kf = kf
	return kf, nil
}

func (v *OIDCVerifier) discoverJWKS(ctx context.Context) (string, error) {
	client := v.Client
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, v.Issuer+"/.well-known/openid-configuration", nil)
	if err != nil {
		return "", err
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("oidc discovery: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("oidc discovery: status %d", resp.StatusCode)
	}
	var doc struct {
		JWKSURI string `json:"jwks_uri"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&doc); err != nil || doc.JWKSURI == "" {
		return "", fmt.Errorf("oidc discovery: no jwks_uri")
	}
	return doc.JWKSURI, nil
}

func (v *OIDCVerifier) Verify(ctx context.Context, token string) (UserClaims, error) {
	kf, err := v.keyfunc(ctx)
	if err != nil {
		return UserClaims{}, err
	}
	var c tokenClaims
	_, err = jwt.ParseWithClaims(token, &c, kf.Keyfunc,
		jwt.WithValidMethods([]string{"ES256", "ES384", "ES512", "RS256", "RS384", "RS512", "PS256", "EdDSA"}),
		jwt.WithIssuer(v.Issuer),
		jwt.WithAudience(v.Audience),
		jwt.WithExpirationRequired(),
		jwt.WithLeeway(60*time.Second),
	)
	if err != nil {
		return UserClaims{}, mapJWTError(err)
	}
	if c.Subject == "" {
		return UserClaims{}, ErrInvalid
	}
	return c.user(), nil
}

// DevTokens signs and verifies user tokens in dev auth mode, so clients can
// be developed without a Logto instance. Never enabled in production.
type DevTokens struct {
	key      []byte
	Issuer   string
	Audience string
	TTL      time.Duration
}

func NewDevTokens(master []byte, issuer, audience string) *DevTokens {
	return &DevTokens{key: DeriveKey(master, "dev-user-token"), Issuer: issuer, Audience: audience, TTL: 12 * time.Hour}
}

// Issue signs a token for a dev user identified by email.
func (d *DevTokens) Issue(email, name string, operator bool) (string, int, error) {
	now := time.Now()
	scope := ""
	if operator {
		scope = OperatorScope
	}
	c := tokenClaims{
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    d.Issuer,
			Subject:   "dev|" + strings.ToLower(email),
			Audience:  jwt.ClaimStrings{d.Audience},
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(d.TTL)),
		},
		Scope: scope,
		Email: email,
		Name:  name,
	}
	s, err := jwt.NewWithClaims(jwt.SigningMethodHS256, c).SignedString(d.key)
	return s, int(d.TTL.Seconds()), err
}

func (d *DevTokens) Verify(_ context.Context, token string) (UserClaims, error) {
	var c tokenClaims
	_, err := jwt.ParseWithClaims(token, &c, func(*jwt.Token) (any, error) { return d.key, nil },
		jwt.WithValidMethods([]string{"HS256"}),
		jwt.WithIssuer(d.Issuer),
		jwt.WithAudience(d.Audience),
		jwt.WithExpirationRequired(),
		jwt.WithLeeway(60*time.Second),
	)
	if err != nil {
		return UserClaims{}, mapJWTError(err)
	}
	return c.user(), nil
}

// MultiVerifier dispatches on the (unverified) issuer claim.
type MultiVerifier map[string]UserVerifier

func (m MultiVerifier) Verify(ctx context.Context, token string) (UserClaims, error) {
	var c jwt.RegisteredClaims
	if _, _, err := jwt.NewParser().ParseUnverified(token, &c); err != nil {
		return UserClaims{}, ErrInvalid
	}
	v, ok := m[c.Issuer]
	if !ok {
		return UserClaims{}, fmt.Errorf("%w: unknown issuer", ErrInvalid)
	}
	return v.Verify(ctx, token)
}
