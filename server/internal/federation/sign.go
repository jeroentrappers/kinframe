// Package federation is the server-to-server link of families on different servers (docs/spec/multi-server.md,
// API README §5.22): HTTP Message Signatures (RFC 9421) with Ed25519 keys, Content-Digest (RFC 9530), the
// /.well-known/kinframe document and the HTTP client that reaches other servers through the network guard.
package federation

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Header names of the kinframe profile.
const (
	HeaderServer         = "Kinframe-Server"
	HeaderSignature      = "Signature"
	HeaderSignatureInput = "Signature-Input"
	HeaderContentDigest  = "Content-Digest"
	Label                = "sig1"
	Alg                  = "ed25519"
)

// Components are what every kinframe federation request signs, in this order (README §5.22).
var Components = []string{"@method", "@path", "@query", "content-digest", "date", "kinframe-server"}

// MaxSkew is how far `created` and `Date` may be from the receiver's clock.
const MaxSkew = 5 * time.Minute

// Verification failures. All of them answer 401 unauthorized; ErrUnknownKey makes the receiver refetch the
// caller's well-known document once.
var (
	ErrMissing    = errors.New("federation: signature headers missing")
	ErrMalformed  = errors.New("federation: malformed signature headers")
	ErrComponents = errors.New("federation: the signature does not cover the required components")
	ErrDigest     = errors.New("federation: content digest does not match the body")
	ErrSkew       = errors.New("federation: request time outside the allowed window")
	ErrUnknownKey = errors.New("federation: unknown key")
	ErrBadSig     = errors.New("federation: signature does not verify")
	ErrReplay     = errors.New("federation: signature already used")
)

// ContentDigest is the RFC 9530 Content-Digest field value of body with sha-256.
func ContentDigest(body []byte) string {
	sum := sha256.Sum256(body)
	return "sha-256=:" + base64.StdEncoding.EncodeToString(sum[:]) + ":"
}

// checkDigest verifies a Content-Digest field value against body; it must have a sha-256 member.
func checkDigest(field string, body []byte) error {
	want := sha256.Sum256(body)
	for _, member := range splitTopLevel(field, ',') {
		k, v, ok := strings.Cut(strings.TrimSpace(member), "=")
		if !ok || strings.TrimSpace(k) != "sha-256" {
			continue
		}
		b, err := byteSequence(strings.TrimSpace(v))
		if err != nil {
			return ErrDigest
		}
		if !bytes.Equal(b, want[:]) {
			return ErrDigest
		}
		return nil
	}
	return ErrDigest
}

// Params are the signature parameters (RFC 9421 §2.3) in the order they are serialized.
type Params struct {
	Created int64
	KeyID   string
	Alg     string
	Nonce   string
	// Order lists the parameter names as they appeared (verification) or should appear (signing);
	// empty means created, keyid, alg, nonce (those that are set).
	Order []string
}

func (p Params) serialize() string {
	order := p.Order
	if len(order) == 0 {
		order = []string{"created", "keyid", "alg", "nonce"}
	}
	var b strings.Builder
	for _, name := range order {
		switch name {
		case "created":
			if p.Created != 0 {
				fmt.Fprintf(&b, ";created=%d", p.Created)
			}
		case "keyid":
			if p.KeyID != "" {
				fmt.Fprintf(&b, ";keyid=%s", quote(p.KeyID))
			}
		case "alg":
			if p.Alg != "" {
				fmt.Fprintf(&b, ";alg=%s", quote(p.Alg))
			}
		case "nonce":
			if p.Nonce != "" {
				fmt.Fprintf(&b, ";nonce=%s", quote(p.Nonce))
			}
		}
	}
	return b.String()
}

func quote(s string) string {
	return `"` + strings.NewReplacer(`\`, `\\`, `"`, `\"`).Replace(s) + `"`
}

// SignatureParams is the `@signature-params` value: the inner list of components and the parameters.
func SignatureParams(components []string, p Params) string {
	q := make([]string, len(components))
	for i, c := range components {
		q[i] = quote(c)
	}
	return "(" + strings.Join(q, " ") + ")" + p.serialize()
}

// SignatureBase builds the RFC 9421 §2.5 signature base for a request. Derived components supported:
// @method, @path, @query, @authority, @target-uri, @scheme; any other name is a header field.
func SignatureBase(r *http.Request, components []string, p Params) ([]byte, error) {
	var b strings.Builder
	seen := map[string]bool{}
	for _, c := range components {
		if seen[c] {
			return nil, ErrMalformed
		}
		seen[c] = true
		v, err := componentValue(r, c)
		if err != nil {
			return nil, err
		}
		fmt.Fprintf(&b, "%s: %s\n", quote(c), v)
	}
	fmt.Fprintf(&b, "%s: %s", quote("@signature-params"), SignatureParams(components, p))
	return []byte(b.String()), nil
}

func componentValue(r *http.Request, c string) (string, error) {
	switch c {
	case "@method":
		return strings.ToUpper(r.Method), nil
	case "@path":
		p := r.URL.EscapedPath()
		if p == "" {
			p = "/"
		}
		return p, nil
	case "@query":
		return "?" + r.URL.RawQuery, nil
	case "@authority":
		h := r.Host
		if h == "" {
			h = r.URL.Host
		}
		return strings.ToLower(h), nil
	case "@scheme":
		return strings.ToLower(r.URL.Scheme), nil
	case "@target-uri":
		return r.URL.String(), nil
	}
	if strings.HasPrefix(c, "@") {
		return "", ErrComponents
	}
	vals := r.Header.Values(c)
	if len(vals) == 0 {
		return "", fmt.Errorf("%w: header %s is missing", ErrComponents, c)
	}
	trimmed := make([]string, len(vals))
	for i := range vals {
		trimmed[i] = strings.TrimSpace(vals[i])
	}
	return strings.Join(trimmed, ", "), nil
}

// SignRaw signs a request with arbitrary components (RFC 9421 test vectors) and sets Signature-Input and
// Signature with label.
func SignRaw(r *http.Request, label string, components []string, p Params, key ed25519.PrivateKey) error {
	base, err := SignatureBase(r, components, p)
	if err != nil {
		return err
	}
	sig := ed25519.Sign(key, base)
	r.Header.Set(HeaderSignatureInput, label+"="+SignatureParams(components, p))
	r.Header.Set(HeaderSignature, label+"=:"+base64.StdEncoding.EncodeToString(sig)+":")
	return nil
}

// Sign signs a kinframe federation request: it sets Content-Digest (of body), Date, Kinframe-Server and the
// signature over Components with created, keyid, alg and a fresh nonce.
func Sign(r *http.Request, body []byte, key *Key, serverURL string, now time.Time) error {
	r.Header.Set(HeaderContentDigest, ContentDigest(body))
	r.Header.Set("Date", now.UTC().Format(http.TimeFormat))
	r.Header.Set(HeaderServer, serverURL)
	return SignRaw(r, Label, Components, Params{Created: now.Unix(), KeyID: key.ID, Alg: Alg, Nonce: RandomNonce()}, key.Private)
}

// Signed is a parsed Signature-Input and Signature pair.
type Signed struct {
	Label      string
	Components []string
	Params     Params
	Signature  []byte
}

// ParseSignature reads the signature with label (or the first one when label is "") from the request.
func ParseSignature(r *http.Request, label string) (*Signed, error) {
	input, sig := r.Header.Get(HeaderSignatureInput), r.Header.Get(HeaderSignature)
	if input == "" || sig == "" {
		return nil, ErrMissing
	}
	inputs := map[string]string{}
	var first string
	for _, m := range splitTopLevel(input, ',') {
		k, v, ok := strings.Cut(strings.TrimSpace(m), "=")
		if !ok {
			return nil, ErrMalformed
		}
		if first == "" {
			first = k
		}
		inputs[k] = v
	}
	if label == "" {
		label = first
	}
	in, ok := inputs[label]
	if !ok {
		return nil, ErrMissing
	}
	var sigValue string
	for _, m := range splitTopLevel(sig, ',') {
		k, v, ok := strings.Cut(strings.TrimSpace(m), "=")
		if ok && k == label {
			sigValue = v
		}
	}
	if sigValue == "" {
		return nil, ErrMissing
	}
	raw, err := byteSequence(sigValue)
	if err != nil {
		return nil, ErrMalformed
	}
	out := &Signed{Label: label, Signature: raw}
	// Inner list: ("a" "b" ...)
	if !strings.HasPrefix(in, "(") {
		return nil, ErrMalformed
	}
	end := strings.Index(in, ")")
	if end < 0 {
		return nil, ErrMalformed
	}
	for _, item := range strings.Fields(in[1:end]) {
		s, err := unquote(item)
		if err != nil {
			return nil, ErrMalformed
		}
		out.Components = append(out.Components, s)
	}
	for _, param := range splitTopLevel(in[end+1:], ';') {
		param = strings.TrimSpace(param)
		if param == "" {
			continue
		}
		k, v, ok := strings.Cut(param, "=")
		if !ok {
			return nil, ErrMalformed
		}
		out.Params.Order = append(out.Params.Order, k)
		switch k {
		case "created":
			n, err := strconv.ParseInt(v, 10, 64)
			if err != nil {
				return nil, ErrMalformed
			}
			out.Params.Created = n
		case "keyid", "alg", "nonce":
			s, err := unquote(v)
			if err != nil {
				return nil, ErrMalformed
			}
			switch k {
			case "keyid":
				out.Params.KeyID = s
			case "alg":
				out.Params.Alg = s
			default:
				out.Params.Nonce = s
			}
		default:
			return nil, ErrMalformed // expires, tag, …: not part of the kinframe profile
		}
	}
	return out, nil
}

// VerifyRaw checks a parsed signature over the request with pub (RFC 9421 test vectors).
func VerifyRaw(r *http.Request, s *Signed, pub ed25519.PublicKey) error {
	base, err := SignatureBase(r, s.Components, s.Params)
	if err != nil {
		return err
	}
	if len(pub) != ed25519.PublicKeySize || !ed25519.Verify(pub, base, s.Signature) {
		return ErrBadSig
	}
	return nil
}

// KeyLookup returns the caller's public key for keyid (ErrUnknownKey when it has no such key).
type KeyLookup func(serverURL, keyID string) (ed25519.PublicKey, error)

// Verifier checks kinframe federation requests and remembers signatures to refuse replays.
type Verifier struct {
	Now func() time.Time

	mu   sync.Mutex
	seen map[string]time.Time
}

// Verify checks the kinframe profile on a request whose body was read into body: Content-Digest, Date and
// created within MaxSkew, the required components, an Ed25519 signature by the key lookup returns, and no
// replay. It returns the caller's normalized base URL (Kinframe-Server) and the key id.
func (v *Verifier) Verify(r *http.Request, body []byte, lookup KeyLookup) (serverURL, keyID string, err error) {
	now := time.Now()
	if v.Now != nil {
		now = v.Now()
	}
	claimed := r.Header.Get(HeaderServer)
	if claimed == "" || r.Header.Get(HeaderContentDigest) == "" || r.Header.Get("Date") == "" {
		return "", "", ErrMissing
	}
	serverURL, _, err = NormalizeBaseURL(claimed)
	if err != nil {
		return "", "", ErrMalformed
	}
	if err := checkDigest(r.Header.Get(HeaderContentDigest), body); err != nil {
		return "", "", err
	}
	date, err := http.ParseTime(r.Header.Get("Date"))
	if err != nil {
		return "", "", ErrMalformed
	}
	if d := now.Sub(date); d > MaxSkew || d < -MaxSkew {
		return "", "", ErrSkew
	}
	s, err := ParseSignature(r, Label)
	if err == ErrMissing {
		s, err = ParseSignature(r, "")
	}
	if err != nil {
		return "", "", err
	}
	if !sameSet(s.Components, Components) {
		return "", "", ErrComponents
	}
	if s.Params.Alg != Alg || s.Params.KeyID == "" || s.Params.Created == 0 {
		return "", "", ErrMalformed
	}
	if d := now.Sub(time.Unix(s.Params.Created, 0)); d > MaxSkew || d < -MaxSkew {
		return "", "", ErrSkew
	}
	pub, err := lookup(serverURL, s.Params.KeyID)
	if err != nil {
		return "", "", err
	}
	if err := VerifyRaw(r, s, pub); err != nil {
		return "", "", err
	}
	if !v.remember(s.Params.KeyID+" "+base64.StdEncoding.EncodeToString(s.Signature), now) {
		return "", "", ErrReplay
	}
	return serverURL, s.Params.KeyID, nil
}

// remember records a signature; false when it was seen within the replay window (twice MaxSkew).
func (v *Verifier) remember(key string, now time.Time) bool {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.seen == nil {
		v.seen = map[string]time.Time{}
	}
	if len(v.seen) > 1000 {
		for k, t := range v.seen {
			if now.Sub(t) > 2*MaxSkew {
				delete(v.seen, k)
			}
		}
	}
	if t, ok := v.seen[key]; ok && now.Sub(t) <= 2*MaxSkew {
		return false
	}
	v.seen[key] = now
	return true
}

func sameSet(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	m := map[string]int{}
	for _, x := range a {
		m[x]++
	}
	for _, x := range b {
		if m[x] == 0 {
			return false
		}
		m[x]--
	}
	return true
}

// splitTopLevel splits a structured field on sep outside of strings, byte sequences and inner lists.
func splitTopLevel(s string, sep byte) []string {
	var out []string
	depth, start := 0, 0
	inStr, inBytes := false, false
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case inStr:
			if c == '\\' {
				i++
			} else if c == '"' {
				inStr = false
			}
		case inBytes:
			if c == ':' {
				inBytes = false
			}
		case c == '"':
			inStr = true
		case c == ':':
			inBytes = true
		case c == '(':
			depth++
		case c == ')':
			depth--
		case c == sep && depth == 0:
			out = append(out, s[start:i])
			start = i + 1
		}
	}
	return append(out, s[start:])
}

func byteSequence(v string) ([]byte, error) {
	v = strings.TrimSpace(v)
	if len(v) < 2 || v[0] != ':' || v[len(v)-1] != ':' {
		return nil, ErrMalformed
	}
	return base64.StdEncoding.DecodeString(v[1 : len(v)-1])
}

func unquote(v string) (string, error) {
	v = strings.TrimSpace(v)
	if len(v) < 2 || v[0] != '"' || v[len(v)-1] != '"' {
		return "", ErrMalformed
	}
	inner := v[1 : len(v)-1]
	var b strings.Builder
	for i := 0; i < len(inner); i++ {
		if inner[i] == '\\' && i+1 < len(inner) {
			i++
		}
		b.WriteByte(inner[i])
	}
	return b.String(), nil
}

// NormalizeBaseURL turns a server URL into its base URL (scheme://host[:port], lower case, no default port, no
// path) and returns the host (host[:port]) too. Only http and https are allowed.
func NormalizeBaseURL(raw string) (base, host string, err error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Host == "" || u.User != nil {
		return "", "", fmt.Errorf("not a server URL: %q", raw)
	}
	scheme := strings.ToLower(u.Scheme)
	if scheme != "https" && scheme != "http" {
		return "", "", fmt.Errorf("not an http(s) URL: %q", raw)
	}
	h := strings.TrimSuffix(strings.ToLower(u.Hostname()), ".")
	port := u.Port()
	if (scheme == "https" && port == "443") || (scheme == "http" && port == "80") {
		port = ""
	}
	host = h
	if strings.Contains(h, ":") {
		host = "[" + h + "]"
	}
	if port != "" {
		host += ":" + port
	}
	return scheme + "://" + host, host, nil
}
