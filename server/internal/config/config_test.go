package config

import (
	"os"
	"path/filepath"
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

func TestLogtoConfigFile(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "logto.json"), []byte(`{"issuer":"https://auth.example.org/oidc/","web_client_id":"web","mobile_client_id":"mob","api_resource":"https://k.example.org/v1"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	env := map[string]string{
		"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_ENV": "production", "KINFRAME_DATA_DIR": dir,
		"KINFRAME_SIGNING_KEY": strings.Repeat("ab", 32), "KINFRAME_PUBLIC_URL": "https://k.example.org",
	}
	c, err := load(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	if c.OIDCIssuer != "https://auth.example.org/oidc" || c.WebClientID != "web" || c.MobileClientID != "mob" || c.OIDCAudience != "https://k.example.org/v1" {
		t.Fatalf("file not applied: %+v", c)
	}

	// Explicit env vars win over the file.
	env["KINFRAME_OIDC_WEB_CLIENT_ID"] = "override"
	env["KINFRAME_OIDC_AUDIENCE"] = "https://other/v1"
	c, err = load(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	if c.WebClientID != "override" || c.MobileClientID != "mob" || c.OIDCAudience != "https://other/v1" {
		t.Fatalf("env should win: %+v", c)
	}

	// A corrupt file is an error, not silently ignored.
	if err := os.WriteFile(filepath.Join(dir, "logto.json"), []byte("{"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := load(func(k string) string { return env[k] }); err == nil || !strings.Contains(err.Error(), "KINFRAME_LOGTO_CONFIG_FILE") {
		t.Fatalf("err = %v", err)
	}
}

func TestWeatherConfig(t *testing.T) {
	base := map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_SIGNING_KEY": strings.Repeat("ab", 32)}
	get := func(extra map[string]string) func(string) string {
		return func(k string) string {
			if v, ok := extra[k]; ok {
				return v
			}
			return base[k]
		}
	}
	base["KINFRAME_DATA_DIR"] = t.TempDir()
	c, err := load(get(nil))
	if err != nil || c.WeatherURL != "https://api.open-meteo.com" || c.GeocodingURL != "https://geocoding-api.open-meteo.com" || c.WeatherRefresh.Minutes() != 30 {
		t.Fatalf("defaults: %v %+v", err, c)
	}
	if c, err := load(get(map[string]string{"KINFRAME_WEATHER": "off"})); err != nil || c.WeatherURL != "" || c.GeocodingURL != "" {
		t.Fatalf("off: %v %q", err, c.WeatherURL)
	}
	if _, err := load(get(map[string]string{"KINFRAME_WEATHER_REFRESH_MINUTES": "5"})); err == nil {
		t.Fatal("5 minute refresh accepted")
	}
}

func TestAppleAppIDs(t *testing.T) {
	base := map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_SIGNING_KEY": strings.Repeat("ab", 32), "KINFRAME_DATA_DIR": t.TempDir()}
	load1 := func(v string) []string {
		t.Helper()
		env := map[string]string{"KINFRAME_APPLE_APP_IDS": v}
		c, err := load(func(k string) string {
			if x, ok := env[k]; ok {
				return x
			}
			return base[k]
		})
		if err != nil {
			t.Fatal(err)
		}
		return c.AppleAppIDs
	}
	if got := load1(""); len(got) != 1 || got[0] != DefaultAppleAppID {
		t.Fatalf("default = %v", got)
	}
	if got := load1(" A.b.c , ,D.e.f "); len(got) != 2 || got[0] != "A.b.c" || got[1] != "D.e.f" {
		t.Fatalf("list = %v", got)
	}
	if got := load1("off"); got != nil {
		t.Fatalf("off = %v", got)
	}
}
