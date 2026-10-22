// Package auth validates user access tokens (Logto or dev mode), issues and
// validates device tokens, hashes device secrets and signs media URLs.
package auth

import (
	"crypto/hmac"
	"crypto/sha256"
)

// DeriveKey derives a purpose-specific key from the server signing key, so one
// configured secret never signs two kinds of things with the same key.
func DeriveKey(master []byte, purpose string) []byte {
	m := hmac.New(sha256.New, master)
	m.Write([]byte("kinframe/" + purpose))
	return m.Sum(nil)
}
