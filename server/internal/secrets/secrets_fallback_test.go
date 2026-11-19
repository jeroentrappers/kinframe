package secrets

import (
	"bytes"
	"testing"
)

// Configuring KINFRAME_SECRETS_KEY later must keep secrets sealed with the signing-key-derived key readable.
func TestSecretsKeyAddedLater(t *testing.T) {
	signing := bytes.Repeat([]byte{1}, 32)
	secretsKey := bytes.Repeat([]byte{2}, 32)
	before, err := New(nil, signing, "s v1", "from signing v1")
	if err != nil {
		t.Fatal(err)
	}
	old := before.Seal("calendar password")
	after, err := New(secretsKey, signing, "s v1", "from signing v1")
	if err != nil {
		t.Fatal(err)
	}
	if got, err := after.Open(old); err != nil || got != "calendar password" {
		t.Fatalf("old secret: %q, %v", got, err)
	}
	fresh := after.Seal("new password")
	if _, err := before.Open(fresh); err == nil {
		t.Fatal("new secrets must use the secrets key, not the signing key")
	}
	if got, err := after.Open(fresh); err != nil || got != "new password" {
		t.Fatalf("new secret: %q, %v", got, err)
	}
	// Another kind of secret (other info strings) still can't be opened.
	other, _ := New(secretsKey, signing, "other v1", "other from signing v1")
	if _, err := other.Open(old); err == nil {
		t.Fatal("keys of different kinds must not open each other's secrets")
	}
	// Without a signing fallback (bodies, federation keys) there is no fallback.
	bodies, _ := New(secretsKey, nil, "b v1", "")
	if bodies.fallback != nil {
		t.Fatal("unexpected fallback")
	}
}
