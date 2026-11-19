package federation

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"time"
)

// KeyLifetime is how long a signing key is used before it is rotated; RotatedKeyValidity how long a rotated key
// stays published and accepted (README §5.22: 7 days).
const (
	KeyLifetime        = 180 * 24 * time.Hour
	RotatedKeyValidity = 7 * 24 * time.Hour
)

// Key is an Ed25519 signing key with its id.
type Key struct {
	ID      string
	Public  ed25519.PublicKey
	Private ed25519.PrivateKey
}

// NewKey generates a key pair.
func NewKey() (*Key, error) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	return &Key{ID: KeyID(pub), Public: pub, Private: priv}, nil
}

// KeyFromSeed rebuilds a key from its 32-byte seed.
func KeyFromSeed(seed []byte) (*Key, error) {
	if len(seed) != ed25519.SeedSize {
		return nil, errors.New("federation: an Ed25519 seed is 32 bytes")
	}
	priv := ed25519.NewKeyFromSeed(seed)
	pub := priv.Public().(ed25519.PublicKey)
	return &Key{ID: KeyID(pub), Public: pub, Private: priv}, nil
}

// KeyID derives a key's id from its public key: "k" and 16 base64url characters of its SHA-256.
func KeyID(pub ed25519.PublicKey) string {
	sum := sha256.Sum256(pub)
	return "k" + base64.RawURLEncoding.EncodeToString(sum[:12])
}

// EncodePublic is a public key as published: base64url without padding.
func EncodePublic(pub ed25519.PublicKey) string { return base64.RawURLEncoding.EncodeToString(pub) }

// DecodePublic reads a published public key (base64url, padding optional; standard base64 accepted).
func DecodePublic(s string) (ed25519.PublicKey, error) {
	for _, enc := range []*base64.Encoding{base64.RawURLEncoding, base64.URLEncoding, base64.StdEncoding, base64.RawStdEncoding} {
		if b, err := enc.DecodeString(s); err == nil && len(b) == ed25519.PublicKeySize {
			return ed25519.PublicKey(b), nil
		}
	}
	return nil, errors.New("federation: not an Ed25519 public key")
}

// RandomNonce is a signature nonce (16 random bytes, base64url).
func RandomNonce() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}
