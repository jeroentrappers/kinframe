package auth

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

var master = []byte(strings.Repeat("k", 32))

func TestSecretHash(t *testing.T) {
	s := NewDeviceSecret()
	h := HashSecret(s)
	if !strings.HasPrefix(h, "$argon2id$") {
		t.Fatalf("hash format: %s", h)
	}
	for _, tt := range []struct {
		in   string
		want bool
	}{{s, true}, {s + "x", false}, {"", false}} {
		got, err := VerifySecret(tt.in, h)
		if err != nil || got != tt.want {
			t.Errorf("VerifySecret(%q) = %v, %v; want %v", tt.in, got, err, tt.want)
		}
	}
	if _, err := VerifySecret(s, "$bcrypt$x"); err == nil {
		t.Error("expected error for unknown format")
	}
}

func TestDeviceTokens(t *testing.T) {
	d := NewDeviceTokens(master, "http://test")
	id := uuid.New()
	tok, ttl, err := d.Issue(id)
	if err != nil || ttl != 3600 {
		t.Fatal(err, ttl)
	}
	got, err := d.Verify(tok)
	if err != nil || got != id {
		t.Fatalf("verify: %v %v", got, err)
	}
	d.now = func() time.Time { return time.Now().Add(2 * time.Hour) }
	if _, err := d.Verify(tok); !errors.Is(err, ErrExpired) {
		t.Fatalf("want expired, got %v", err)
	}
	other := NewDeviceTokens([]byte(strings.Repeat("z", 32)), "http://test")
	if _, err := other.Verify(tok); !errors.Is(err, ErrInvalid) {
		t.Fatalf("want invalid, got %v", err)
	}
	// A dev user token must never pass as a device token.
	dev := NewDevTokens(master, "http://test", "device")
	ut, _, _ := dev.Issue("a@b.c", "A", false)
	if _, err := d.Verify(ut); err == nil {
		t.Fatal("user token accepted as device token")
	}
}

func TestDevTokens(t *testing.T) {
	d := NewDevTokens(master, "http://x/dev", "http://x/v1")
	tok, _, err := d.Issue("Ann@Example.org", "Ann", true)
	if err != nil {
		t.Fatal(err)
	}
	c, err := MultiVerifier{"http://x/dev": d}.Verify(context.Background(), tok)
	if err != nil {
		t.Fatal(err)
	}
	if c.Subject != "dev|ann@example.org" || !c.HasScope(OperatorScope) || c.Name != "Ann" {
		t.Fatalf("claims: %+v", c)
	}
	if _, err := (MultiVerifier{}).Verify(context.Background(), tok); !errors.Is(err, ErrInvalid) {
		t.Fatal("unknown issuer accepted")
	}
}

func TestOIDCVerifier(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/oidc/.well-known/openid-configuration":
			_ = json.NewEncoder(w).Encode(map[string]string{"jwks_uri": srv.URL + "/oidc/jwks"})
		case "/oidc/jwks":
			b64 := func(i *big.Int) string { return base64.RawURLEncoding.EncodeToString(i.FillBytes(make([]byte, 48))) }
			_ = json.NewEncoder(w).Encode(map[string]any{"keys": []map[string]string{{
				"kty": "EC", "crv": "P-384", "kid": "k1", "alg": "ES384", "use": "sig",
				"x": b64(key.X), "y": b64(key.Y),
			}}})
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	issuer := srv.URL + "/oidc"
	v := &OIDCVerifier{Issuer: issuer, Audience: "https://api/v1"}

	sign := func(mod func(*tokenClaims)) string {
		c := tokenClaims{RegisteredClaims: jwt.RegisteredClaims{
			Issuer: issuer, Subject: "u1", Audience: jwt.ClaimStrings{"https://api/v1"},
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour)),
		}, Scope: "kinframe:operate other"}
		mod(&c)
		tk := jwt.NewWithClaims(jwt.SigningMethodES384, c)
		tk.Header["kid"] = "k1"
		s, err := tk.SignedString(key)
		if err != nil {
			t.Fatal(err)
		}
		return s
	}
	tests := []struct {
		name    string
		mod     func(*tokenClaims)
		wantErr error
	}{
		{"valid", func(*tokenClaims) {}, nil},
		{"wrong audience", func(c *tokenClaims) { c.Audience = jwt.ClaimStrings{"other"} }, ErrInvalid},
		{"wrong issuer", func(c *tokenClaims) { c.Issuer = "https://evil" }, ErrInvalid},
		{"expired", func(c *tokenClaims) { c.ExpiresAt = jwt.NewNumericDate(time.Now().Add(-2 * time.Minute)) }, ErrExpired},
		{"within leeway", func(c *tokenClaims) { c.ExpiresAt = jwt.NewNumericDate(time.Now().Add(-30 * time.Second)) }, nil},
		{"not yet valid", func(c *tokenClaims) { c.NotBefore = jwt.NewNumericDate(time.Now().Add(5 * time.Minute)) }, ErrInvalid},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c, err := v.Verify(context.Background(), sign(tt.mod))
			if tt.wantErr == nil {
				if err != nil {
					t.Fatal(err)
				}
				if c.Subject != "u1" || !c.HasScope(OperatorScope) {
					t.Fatalf("claims %+v", c)
				}
				return
			}
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("err = %v, want %v", err, tt.wantErr)
			}
		})
	}
}

func TestURLSigner(t *testing.T) {
	s := NewURLSigner(master)
	u := s.Sign("/media/x", time.Minute)
	if u != s.Sign("/media/x", time.Minute) {
		t.Error("signature not stable within window")
	}
	q := u[strings.Index(u, "?")+1:]
	vals := map[string]string{}
	for _, kv := range strings.Split(q, "&") {
		p := strings.SplitN(kv, "=", 2)
		vals[p[0]] = p[1]
	}
	if !s.Valid("/media/x", vals["exp"], vals["sig"]) {
		t.Error("valid signature rejected")
	}
	if s.Valid("/media/y", vals["exp"], vals["sig"]) {
		t.Error("signature valid for another path")
	}
	if s.Valid("/media/x", "1", vals["sig"]) {
		t.Error("expired signature accepted")
	}
}
