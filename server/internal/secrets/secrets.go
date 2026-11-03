// Package secrets encrypts stored credentials (calendar links and passwords,
// plugin tokens) at rest with AES-256-GCM.
package secrets

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"errors"
)

// Sealer encrypts and decrypts one kind of secret.
type Sealer struct{ aead cipher.AEAD }

const sealVersion = 1

// ErrUnseal means a stored secret can't be decrypted (the key changed).
var ErrUnseal = errors.New("stored credentials can't be decrypted; enter them again")

// New derives the key with HKDF-SHA256 from secretsKey (KINFRAME_SECRETS_KEY)
// with info secretsInfo when set, else from signingKey with signingInfo. Each
// kind of secret uses its own info strings, so it gets its own key.
func New(secretsKey, signingKey []byte, secretsInfo, signingInfo string) (*Sealer, error) {
	ikm, info := secretsKey, secretsInfo
	if len(ikm) == 0 {
		ikm, info = signingKey, signingInfo
	}
	if len(ikm) < 32 {
		return nil, errors.New("secrets key must be at least 32 bytes")
	}
	key, err := hkdf.Key(sha256.New, ikm, nil, info, 32)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &Sealer{aead: aead}, nil
}

// Seal encrypts s; an empty string seals to nil.
func (x *Sealer) Seal(s string) []byte {
	if s == "" {
		return nil
	}
	nonce := make([]byte, x.aead.NonceSize(), 1+x.aead.NonceSize()+len(s)+x.aead.Overhead())
	_, _ = rand.Read(nonce)
	out := append([]byte{sealVersion}, nonce...)
	return x.aead.Seal(out, nonce, []byte(s), []byte{sealVersion})
}

// Open decrypts a sealed value; nil opens to "".
func (x *Sealer) Open(b []byte) (string, error) {
	if len(b) == 0 {
		return "", nil
	}
	n := x.aead.NonceSize()
	if len(b) < 1+n || b[0] != sealVersion {
		return "", ErrUnseal
	}
	plain, err := x.aead.Open(nil, b[1:1+n], b[1+n:], []byte{sealVersion})
	if err != nil {
		return "", ErrUnseal
	}
	return string(plain), nil
}
