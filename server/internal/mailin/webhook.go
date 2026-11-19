package mailin

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Webhook signatures (§6.3): HMAC-SHA256 with the shared secret over
//
//	"v1" LF timestamp LF envelope_to LF envelope_from LF client_ip LF helo LF hex(sha256(body))
//
// sent as X-Kinframe-Signature: v1=<hex>.

// SignedRequest is the signed part of a webhook request.
type SignedRequest struct {
	Timestamp    string
	EnvelopeTo   string
	EnvelopeFrom string
	ClientIP     string
	Helo         string
	BodySHA256   string // hex
}

func (r SignedRequest) canonical() string {
	return strings.Join([]string{"v1", r.Timestamp, r.EnvelopeTo, r.EnvelopeFrom, r.ClientIP, r.Helo, r.BodySHA256}, "\n")
}

// Sign returns the X-Kinframe-Signature value for r (deliver-email, tests).
func Sign(secret string, r SignedRequest) string {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(r.canonical()))
	return "v1=" + hex.EncodeToString(m.Sum(nil))
}

// BodyHash is hex(sha256(body)).
func BodyHash(body []byte) string {
	s := sha256.Sum256(body)
	return hex.EncodeToString(s[:])
}

// Errors of Verifier.Verify; all answer 401.
var (
	ErrBadSignature = errors.New("bad signature")
	ErrStale        = errors.New("timestamp too far off")
	ErrReplayed     = errors.New("signature replayed")
)

// Verifier checks signatures against one or two secrets (new, old) and keeps
// a replay cache of the last 10 minutes.
type Verifier struct {
	Secrets []string
	Skew    time.Duration // default 5 minutes
	Now     func() time.Time

	mu   sync.Mutex
	seen map[string]time.Time
}

// ParseSecrets splits "new,old".
func ParseSecrets(v string) []string {
	var out []string
	for _, s := range strings.Split(v, ",") {
		if s = strings.TrimSpace(s); s != "" {
			out = append(out, s)
		}
	}
	return out
}

// Verify checks a signature header value for r.
func (v *Verifier) Verify(r SignedRequest, signature string) error {
	now := time.Now()
	if v.Now != nil {
		now = v.Now()
	}
	skew := v.Skew
	if skew == 0 {
		skew = 5 * time.Minute
	}
	ts, err := strconv.ParseInt(r.Timestamp, 10, 64)
	if err != nil {
		return ErrBadSignature
	}
	if d := now.Sub(time.Unix(ts, 0)); d > skew || d < -skew {
		return ErrStale
	}
	got, ok := strings.CutPrefix(strings.TrimSpace(signature), "v1=")
	if !ok {
		return ErrBadSignature
	}
	gb, err := hex.DecodeString(got)
	if err != nil {
		return ErrBadSignature
	}
	match := false
	for _, s := range v.Secrets {
		want, _ := hex.DecodeString(strings.TrimPrefix(Sign(s, r), "v1="))
		if hmac.Equal(gb, want) {
			match = true
		}
	}
	if !match {
		return ErrBadSignature
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.seen == nil {
		v.seen = map[string]time.Time{}
	}
	for k, t := range v.seen {
		if now.Sub(t) > 10*time.Minute {
			delete(v.seen, k)
		}
	}
	if _, dup := v.seen[got]; dup {
		return ErrReplayed
	}
	v.seen[got] = now
	return nil
}
