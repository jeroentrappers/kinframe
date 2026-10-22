package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"net/url"
	"strconv"
	"time"
)

// URLSigner creates and checks short-lived signatures over a path.
type URLSigner struct{ key []byte }

func NewURLSigner(master []byte) *URLSigner { return &URLSigner{key: DeriveKey(master, "media-url")} }

func (s *URLSigner) mac(path string, exp int64) string {
	m := hmac.New(sha256.New, s.key)
	m.Write([]byte(path))
	m.Write([]byte{0})
	m.Write([]byte(strconv.FormatInt(exp, 10)))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

// Sign returns path with exp and sig query parameters. Expiry is rounded up to
// the next ttl boundary so repeated calls yield cache-friendly identical URLs.
func (s *URLSigner) Sign(path string, ttl time.Duration) string {
	step := int64(ttl.Seconds())
	exp := (time.Now().Unix()/step + 2) * step
	q := url.Values{"exp": {strconv.FormatInt(exp, 10)}, "sig": {s.mac(path, exp)}}
	return path + "?" + q.Encode()
}

// Valid checks a signature produced by Sign.
func (s *URLSigner) Valid(path, expStr, sig string) bool {
	exp, err := strconv.ParseInt(expStr, 10, 64)
	if err != nil || time.Now().Unix() > exp {
		return false
	}
	return hmac.Equal([]byte(s.mac(path, exp)), []byte(sig))
}
