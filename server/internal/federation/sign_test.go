package federation

import (
	"bytes"
	"crypto/ed25519"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// RFC 9421 Appendix B.1.4 (test-key-ed25519).
const rfcPrivate = `-----BEGIN PRIVATE KEY-----
MC4CAQAwBQYDK2VwBCIEIJ+DYvh6SEqVTm50DFtMDoQikTmiCqirVv9mWG9qfSnF
-----END PRIVATE KEY-----`

const rfcPublic = `-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEAJrQLj5P/89iXES9+vFgrIy29clF9CC/oPPsw3c5D0bs=
-----END PUBLIC KEY-----`

func rfcKeys(t *testing.T) (ed25519.PrivateKey, ed25519.PublicKey) {
	t.Helper()
	b, _ := pem.Decode([]byte(rfcPrivate))
	k, err := x509.ParsePKCS8PrivateKey(b.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	pb, _ := pem.Decode([]byte(rfcPublic))
	p, err := x509.ParsePKIXPublicKey(pb.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	return k.(ed25519.PrivateKey), p.(ed25519.PublicKey)
}

// rfcRequest is the test request of RFC 9421 Appendix B.2.
func rfcRequest() *http.Request {
	r, _ := http.NewRequest("POST", "https://example.com/foo?param=Value&Pet=dog", strings.NewReader(`{"hello": "world"}`))
	r.Host = "example.com"
	r.Header.Set("Date", "Tue, 20 Apr 2021 02:07:55 GMT")
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Content-Digest", "sha-512=:WZDPaVn/7XgHaAy8pmojAkGWoRx2UFChF41A2svX+TaPm+AbwAgBWnrIiYllu7BNNyealdVLvRwEmTHWXvJwew==:")
	r.Header.Set("Content-Length", "18")
	return r
}

// RFC 9421 §B.2.6: signing a request with Ed25519 (deterministic, so the signature must match exactly).
func TestRFC9421Ed25519Vector(t *testing.T) {
	priv, pub := rfcKeys(t)
	components := []string{"date", "@method", "@path", "@authority", "content-type", "content-length"}
	params := Params{Created: 1618884473, KeyID: "test-key-ed25519", Order: []string{"created", "keyid"}}
	r := rfcRequest()
	base, err := SignatureBase(r, components, params)
	if err != nil {
		t.Fatal(err)
	}
	wantBase := `"date": Tue, 20 Apr 2021 02:07:55 GMT
"@method": POST
"@path": /foo
"@authority": example.com
"content-type": application/json
"content-length": 18
"@signature-params": ("date" "@method" "@path" "@authority" "content-type" "content-length");created=1618884473;keyid="test-key-ed25519"`
	if string(base) != wantBase {
		t.Fatalf("signature base:\n%s\nwant:\n%s", base, wantBase)
	}
	if err := SignRaw(r, "sig-b26", components, params, priv); err != nil {
		t.Fatal(err)
	}
	if got, want := r.Header.Get("Signature-Input"), `sig-b26=("date" "@method" "@path" "@authority" "content-type" "content-length");created=1618884473;keyid="test-key-ed25519"`; got != want {
		t.Fatalf("Signature-Input %s", got)
	}
	if got, want := r.Header.Get("Signature"), "sig-b26=:wqcAqbmYJ2ji2glfAMaRy4gruYYnx2nEFN2HN6jrnDnQCK1u02Gb04v9EDgwUPiu4A0w6vuQv5lIp5WPpBKRCw==:"; got != want {
		t.Fatalf("Signature %s, want %s", got, want)
	}
	// And the published signature verifies after parsing.
	s, err := ParseSignature(r, "sig-b26")
	if err != nil {
		t.Fatal(err)
	}
	if err := VerifyRaw(r, s, pub); err != nil {
		t.Fatal(err)
	}
	s.Signature[0] ^= 1
	if err := VerifyRaw(r, s, pub); !errors.Is(err, ErrBadSig) {
		t.Fatalf("tampered signature: %v", err)
	}
}

// RFC 9530 §B.1's example digest, and the empty body.
func TestContentDigest(t *testing.T) {
	if got := ContentDigest([]byte(`{"hello": "world"}`)); got != "sha-256=:X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=:" {
		t.Fatalf("digest %s", got)
	}
	if got := ContentDigest(nil); got != "sha-256=:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=:" {
		t.Fatalf("empty digest %s", got)
	}
	if err := checkDigest("sha-512=:AAAA:, sha-256=:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=:", nil); err != nil {
		t.Fatal(err)
	}
	if err := checkDigest("sha-512=:AAAA:", nil); !errors.Is(err, ErrDigest) {
		t.Fatalf("no sha-256 member: %v", err)
	}
}

type fixture struct {
	key, other *Key
	now        time.Time
	v          *Verifier
}

func newFixture(t *testing.T) *fixture {
	k, err := NewKey()
	if err != nil {
		t.Fatal(err)
	}
	o, _ := NewKey()
	now := time.Date(2026, 11, 19, 10, 0, 0, 0, time.UTC)
	return &fixture{key: k, other: o, now: now, v: &Verifier{Now: func() time.Time { return now }}}
}

func (f *fixture) lookup(serverURL, keyID string) (ed25519.PublicKey, error) {
	if serverURL != "https://b.example" {
		return nil, ErrUnknownKey
	}
	switch keyID {
	case f.key.ID:
		return f.key.Public, nil
	case f.other.ID:
		return f.other.Public, nil
	}
	return nil, ErrUnknownKey
}

func (f *fixture) request(t *testing.T, method, target string, body []byte, at time.Time, key *Key) *http.Request {
	t.Helper()
	r := httptest.NewRequest(method, target, bytes.NewReader(body))
	if err := Sign(r, body, key, "https://B.example:443/", at); err != nil {
		t.Fatal(err)
	}
	return r
}

func TestSignAndVerify(t *testing.T) {
	f := newFixture(t)
	body := []byte(`{"subscription_ids":["0192"]}`)
	r := f.request(t, "POST", "https://a.example/federation/v1/ping", body, f.now, f.key)
	if !strings.Contains(r.Header.Get("Signature-Input"), `("@method" "@path" "@query" "content-digest" "date" "kinframe-server");created=`) ||
		!strings.Contains(r.Header.Get("Signature-Input"), `;alg="ed25519";nonce="`) {
		t.Fatalf("Signature-Input %s", r.Header.Get("Signature-Input"))
	}
	server, keyID, err := f.v.Verify(r, body, f.lookup)
	if err != nil || server != "https://b.example" || keyID != f.key.ID {
		t.Fatalf("verify: %q %q %v", server, keyID, err)
	}
	// The same signature again is a replay.
	if _, _, err := f.v.Verify(r, body, f.lookup); !errors.Is(err, ErrReplay) {
		t.Fatalf("replay: %v", err)
	}
	// A new signature of the same request is fine (fresh nonce).
	r2 := f.request(t, "POST", "https://a.example/federation/v1/ping", body, f.now, f.key)
	if _, _, err := f.v.Verify(r2, body, f.lookup); err != nil {
		t.Fatalf("second request: %v", err)
	}
}

func TestVerifyRefusesTampering(t *testing.T) {
	f := newFixture(t)
	body := []byte(`{"events":[]}`)
	sign := func() *http.Request {
		return f.request(t, "GET", "https://a.example/federation/v1/subscriptions/s1/changes?cursor=abc&limit=10", body, f.now, f.key)
	}
	cases := []struct {
		name   string
		mutate func(r *http.Request) []byte
		want   error
	}{
		{"body", func(r *http.Request) []byte { return []byte(`{"events":[1]}`) }, ErrDigest},
		{"digest and body", func(r *http.Request) []byte {
			b := []byte(`{"events":[1]}`)
			r.Header.Set(HeaderContentDigest, ContentDigest(b))
			return b
		}, ErrBadSig},
		{"query", func(r *http.Request) []byte { r.URL.RawQuery = "cursor=xyz&limit=10"; return body }, ErrBadSig},
		{"no query", func(r *http.Request) []byte { r.URL.RawQuery = ""; return body }, ErrBadSig},
		{"path", func(r *http.Request) []byte { r.URL.Path = "/federation/v1/subscriptions/s2/changes"; return body }, ErrBadSig},
		{"method", func(r *http.Request) []byte { r.Method = "POST"; return body }, ErrBadSig},
		{"server header", func(r *http.Request) []byte { r.Header.Set(HeaderServer, "https://c.example"); return body }, ErrUnknownKey},
		{"date", func(r *http.Request) []byte {
			r.Header.Set("Date", f.now.Add(time.Second).Format(http.TimeFormat))
			return body
		}, ErrBadSig},
		{"missing signature", func(r *http.Request) []byte { r.Header.Del(HeaderSignature); return body }, ErrMissing},
		{"missing digest", func(r *http.Request) []byte { r.Header.Del(HeaderContentDigest); return body }, ErrMissing},
		{"fewer components", func(r *http.Request) []byte {
			in := r.Header.Get(HeaderSignatureInput)
			r.Header.Set(HeaderSignatureInput, strings.Replace(in, ` "@query"`, "", 1))
			return body
		}, ErrComponents},
		{"other algorithm", func(r *http.Request) []byte {
			in := r.Header.Get(HeaderSignatureInput)
			r.Header.Set(HeaderSignatureInput, strings.Replace(in, `alg="ed25519"`, `alg="hmac-sha256"`, 1))
			return body
		}, ErrMalformed},
		{"garbage", func(r *http.Request) []byte { r.Header.Set(HeaderSignature, "sig1=nope"); return body }, ErrMalformed},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := sign()
			b := c.mutate(r)
			if _, _, err := f.v.Verify(r, b, f.lookup); !errors.Is(err, c.want) {
				t.Fatalf("got %v, want %v", err, c.want)
			}
		})
	}
}

func TestVerifyWrongKey(t *testing.T) {
	f := newFixture(t)
	body := []byte(`{}`)
	r := f.request(t, "POST", "https://a.example/federation/v1/subscriptions", body, f.now, f.key)
	// The keyid names f.key, but the receiver has another public key for it.
	wrong := func(string, string) (ed25519.PublicKey, error) { return f.other.Public, nil }
	if _, _, err := f.v.Verify(r, body, wrong); !errors.Is(err, ErrBadSig) {
		t.Fatalf("wrong key: %v", err)
	}
	// Signed by a key the caller doesn't publish.
	stranger, _ := NewKey()
	r = f.request(t, "POST", "https://a.example/federation/v1/subscriptions", body, f.now, stranger)
	if _, _, err := f.v.Verify(r, body, f.lookup); !errors.Is(err, ErrUnknownKey) {
		t.Fatalf("unknown key: %v", err)
	}
}

func TestVerifyClockSkew(t *testing.T) {
	f := newFixture(t)
	body := []byte(`{}`)
	for _, d := range []time.Duration{-MaxSkew - time.Second, MaxSkew + time.Second} {
		r := f.request(t, "POST", "https://a.example/federation/v1/ping", body, f.now.Add(d), f.key)
		if _, _, err := f.v.Verify(r, body, f.lookup); !errors.Is(err, ErrSkew) {
			t.Fatalf("skew %v: %v", d, err)
		}
	}
	for _, d := range []time.Duration{-MaxSkew + time.Second, MaxSkew - time.Second} {
		r := f.request(t, "POST", "https://a.example/federation/v1/ping", body, f.now.Add(d), f.key)
		if _, _, err := f.v.Verify(r, body, f.lookup); err != nil {
			t.Fatalf("within skew %v: %v", d, err)
		}
	}
}

// A rotated key keeps verifying while the receiver still lists it (published for 7 days).
func TestVerifyRotatedKey(t *testing.T) {
	f := newFixture(t)
	body := []byte(`{}`)
	r := f.request(t, "POST", "https://a.example/federation/v1/ping", body, f.now, f.other) // the old key
	if _, _, err := f.v.Verify(r, body, f.lookup); err != nil {
		t.Fatalf("rotated key: %v", err)
	}
	gone := func(_, keyID string) (ed25519.PublicKey, error) {
		if keyID == f.key.ID {
			return f.key.Public, nil
		}
		return nil, ErrUnknownKey
	}
	r = f.request(t, "POST", "https://a.example/federation/v1/ping", body, f.now, f.other)
	if _, _, err := f.v.Verify(r, body, gone); !errors.Is(err, ErrUnknownKey) {
		t.Fatalf("expired key: %v", err)
	}
}

func TestKeys(t *testing.T) {
	k, _ := NewKey()
	k2, err := KeyFromSeed(k.Private.Seed())
	if err != nil || k2.ID != k.ID || !k2.Public.Equal(k.Public) {
		t.Fatalf("seed round trip: %v", err)
	}
	pub, err := DecodePublic(EncodePublic(k.Public))
	if err != nil || !pub.Equal(k.Public) {
		t.Fatalf("public round trip: %v", err)
	}
	if _, err := DecodePublic(base64.RawURLEncoding.EncodeToString([]byte("short"))); err == nil {
		t.Fatal("short key accepted")
	}
	if len(k.ID) != 17 || k.ID[0] != 'k' {
		t.Fatalf("key id %q", k.ID)
	}
}

func TestNormalizeBaseURL(t *testing.T) {
	for in, want := range map[string]string{
		"https://Kinframe.Janssens.be/":          "https://kinframe.janssens.be",
		"https://kinframe.janssens.be:443/fi/x":  "https://kinframe.janssens.be",
		"https://kinframe.janssens.be:8443":      "https://kinframe.janssens.be:8443",
		"http://127.0.0.1:8110":                  "http://127.0.0.1:8110",
		"https://[2001:db8::1]:8443/":            "https://[2001:db8::1]:8443",
		"https://kinframe.janssens.be.?x=1#frag": "https://kinframe.janssens.be",
	} {
		got, _, err := NormalizeBaseURL(in)
		if err != nil || got != want {
			t.Errorf("%q: %q %v, want %q", in, got, err, want)
		}
	}
	for _, bad := range []string{"ftp://x.org", "kinframe.janssens.be", "https://user:pw@x.org", "javascript:alert(1)", ""} {
		if _, _, err := NormalizeBaseURL(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}
