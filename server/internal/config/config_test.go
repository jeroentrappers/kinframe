package config

import (
	"strings"
	"testing"
)

func TestLoad(t *testing.T) {
	key := strings.Repeat("ab", 32)
	tests := []struct {
		name    string
		env     map[string]string
		wantErr string
	}{
		{"dev auth ok", map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_SIGNING_KEY": key}, ""},
		{"missing db", map[string]string{"KINFRAME_DEV_AUTH": "1", "KINFRAME_SIGNING_KEY": key}, "KINFRAME_DATABASE_URL"},
		{"dev auth refused in production", map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_ENV": "production", "KINFRAME_OIDC_ISSUER": "https://a/oidc", "KINFRAME_SIGNING_KEY": key}, "must not be enabled"},
		{"production needs key", map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_ENV": "production", "KINFRAME_OIDC_ISSUER": "https://a/oidc"}, "KINFRAME_SIGNING_KEY is required"},
		{"short key", map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_SIGNING_KEY": "abcd"}, "at least 32 bytes"},
		{"no auth at all", map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_SIGNING_KEY": key}, "KINFRAME_OIDC_ISSUER"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tt.env["KINFRAME_DATA_DIR"] = t.TempDir()
			c, err := load(func(k string) string { return tt.env[k] })
			if tt.wantErr == "" {
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				if c.OIDCAudience != "http://localhost:8110/v1" {
					t.Errorf("audience = %q", c.OIDCAudience)
				}
				return
			}
			if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
				t.Fatalf("error = %v, want containing %q", err, tt.wantErr)
			}
		})
	}
}

func TestDevKeyPersists(t *testing.T) {
	dir := t.TempDir()
	env := map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_DATA_DIR": dir}
	a, err := load(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	b, err := load(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	if string(a.SigningKey) != string(b.SigningKey) || len(a.SigningKey) != 32 {
		t.Fatal("dev key not persisted")
	}
}
