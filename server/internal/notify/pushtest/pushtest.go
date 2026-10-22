// Package pushtest fakes the browser side of WebPush for tests: a
// subscription key pair and RFC 8291 / RFC 8188 decryption.
package pushtest

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
)

// Browser is a fake push subscription (the user agent's keys).
type Browser struct {
	Key  *ecdh.PrivateKey
	Auth []byte
}

func NewBrowser() *Browser {
	k, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		panic(err)
	}
	auth := make([]byte, 16)
	_, _ = rand.Read(auth)
	return &Browser{Key: k, Auth: auth}
}

// P256dh and AuthB64 are the subscription keys as PushSubscription.toJSON() gives them.
func (b *Browser) P256dh() string {
	return base64.RawURLEncoding.EncodeToString(b.Key.PublicKey().Bytes())
}
func (b *Browser) AuthB64() string { return base64.RawURLEncoding.EncodeToString(b.Auth) }

// Decrypt reverses the aes128gcm content coding of a single-record push message.
func (b *Browser) Decrypt(body []byte) ([]byte, error) {
	if len(body) < 21 {
		return nil, errors.New("short body")
	}
	salt, rs, idlen := body[:16], binary.BigEndian.Uint32(body[16:20]), int(body[20])
	if rs < 18 || idlen != 65 || len(body) < 21+idlen {
		return nil, errors.New("bad header")
	}
	asPub, err := ecdh.P256().NewPublicKey(body[21 : 21+idlen])
	if err != nil {
		return nil, err
	}
	secret, err := b.Key.ECDH(asPub)
	if err != nil {
		return nil, err
	}
	info := append(append([]byte("WebPush: info\x00"), b.Key.PublicKey().Bytes()...), asPub.Bytes()...)
	prkKey, err := hkdf.Extract(sha256.New, secret, b.Auth)
	if err != nil {
		return nil, err
	}
	ikm, err := hkdf.Expand(sha256.New, prkKey, string(info), 32)
	if err != nil {
		return nil, err
	}
	prk, err := hkdf.Extract(sha256.New, ikm, salt)
	if err != nil {
		return nil, err
	}
	cek, err := hkdf.Expand(sha256.New, prk, "Content-Encoding: aes128gcm\x00", 16)
	if err != nil {
		return nil, err
	}
	nonce, err := hkdf.Expand(sha256.New, prk, "Content-Encoding: nonce\x00", 12)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(cek)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	plain, err := gcm.Open(nil, nonce, body[21+idlen:], nil)
	if err != nil {
		return nil, err
	}
	plain = bytes.TrimRight(plain, "\x00")
	if len(plain) == 0 || plain[len(plain)-1] != 2 {
		return nil, errors.New("missing last-record delimiter")
	}
	return plain[:len(plain)-1], nil
}
