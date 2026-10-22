package config

import (
	"bytes"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// VAPIDFile is the generated WebPush key pair stored in the data dir.
type VAPIDFile struct {
	PublicKey  string `json:"public_key"`
	PrivateKey string `json:"private_key"`
}

// vapidKeys loads <dataDir>/vapid.json, generating it (mode 0600) on first start.
// The keys must stay stable: browser subscriptions are bound to the public key.
func vapidKeys(dataDir string) (string, string, error) {
	path := filepath.Join(dataDir, "vapid.json")
	b, err := os.ReadFile(path)
	if err == nil {
		var f VAPIDFile
		if err := json.Unmarshal(b, &f); err != nil {
			return "", "", fmt.Errorf("%s: %w", path, err)
		}
		if err := checkVAPID(f.PublicKey, f.PrivateKey); err != nil {
			return "", "", fmt.Errorf("%s: %w", path, err)
		}
		return f.PublicKey, f.PrivateKey, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return "", "", err
	}
	key, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		return "", "", err
	}
	f := VAPIDFile{
		PublicKey:  base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes()),
		PrivateKey: base64.RawURLEncoding.EncodeToString(key.Bytes()),
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return "", "", err
	}
	out, _ := json.MarshalIndent(f, "", "  ")
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, out, 0o600); err != nil {
		return "", "", err
	}
	if err := os.Rename(tmp, path); err != nil {
		return "", "", err
	}
	return f.PublicKey, f.PrivateKey, nil
}

// checkVAPID verifies that priv is a P-256 scalar whose public key is pub.
func checkVAPID(pub, priv string) error {
	d, err := decodeB64(priv)
	if err != nil {
		return errors.New("private key must be base64url")
	}
	key, err := ecdh.P256().NewPrivateKey(d)
	if err != nil {
		return fmt.Errorf("private key: %w", err)
	}
	p, err := decodeB64(pub)
	if err != nil {
		return errors.New("public key must be base64url")
	}
	if !bytes.Equal(key.PublicKey().Bytes(), p) {
		return errors.New("public key does not belong to the private key")
	}
	return nil
}

func decodeB64(s string) ([]byte, error) {
	if b, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(s, "=")); err == nil {
		return b, nil
	}
	return base64.StdEncoding.DecodeString(s)
}
