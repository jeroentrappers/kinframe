package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestVAPIDKeys(t *testing.T) {
	dir := t.TempDir()
	env := map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_DATA_DIR": dir}
	a, err := load(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	if len(a.VAPIDPublicKey) != 87 || a.VAPIDPrivateKey == "" || a.VAPIDSubject != "mailto:kinframe@localhost" {
		t.Fatalf("generated keys: %q %q %q", a.VAPIDPublicKey, a.VAPIDPrivateKey, a.VAPIDSubject)
	}
	st, err := os.Stat(filepath.Join(dir, "vapid.json"))
	if err != nil || st.Mode().Perm() != 0o600 {
		t.Fatalf("vapid.json: %v %v", st, err)
	}
	b, err := load(func(k string) string { return env[k] })
	if err != nil || b.VAPIDPublicKey != a.VAPIDPublicKey || b.VAPIDPrivateKey != a.VAPIDPrivateKey {
		t.Fatalf("keys not persisted: %v", err)
	}

	// Explicit keys win; mismatched and half-configured pairs are refused.
	other := t.TempDir()
	env2 := map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_DATA_DIR": other,
		"KINFRAME_VAPID_PUBLIC_KEY": a.VAPIDPublicKey, "KINFRAME_VAPID_PRIVATE_KEY": a.VAPIDPrivateKey,
		"KINFRAME_VAPID_SUBJECT": "https://kinframe.example"}
	c, err := load(func(k string) string { return env2[k] })
	if err != nil || c.VAPIDPublicKey != a.VAPIDPublicKey || c.VAPIDSubject != "https://kinframe.example" {
		t.Fatalf("explicit keys: %v", err)
	}
	if _, err := os.Stat(filepath.Join(other, "vapid.json")); !os.IsNotExist(err) {
		t.Fatal("vapid.json written although keys were configured")
	}
	fresh, _, err := vapidKeys(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	env2["KINFRAME_VAPID_PUBLIC_KEY"] = fresh
	if _, err := load(func(k string) string { return env2[k] }); err == nil || !strings.Contains(err.Error(), "does not belong") {
		t.Fatalf("mismatched pair: %v", err)
	}
	delete(env2, "KINFRAME_VAPID_PUBLIC_KEY")
	if _, err := load(func(k string) string { return env2[k] }); err == nil || !strings.Contains(err.Error(), "both") {
		t.Fatalf("half pair: %v", err)
	}
	env2["KINFRAME_PUSH_DISABLED"] = "1"
	if c, err := load(func(k string) string { return env2[k] }); err != nil || c.VAPIDPublicKey != "" {
		t.Fatalf("push disabled: %v", err)
	}
	env3 := map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_DATA_DIR": dir, "KINFRAME_VAPID_SUBJECT": "admin@x"}
	if _, err := load(func(k string) string { return env3[k] }); err == nil || !strings.Contains(err.Error(), "SUBJECT") {
		t.Fatalf("bad subject: %v", err)
	}
}
