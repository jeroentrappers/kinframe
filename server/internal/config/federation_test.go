package config

import (
	"strings"
	"testing"
)

// KINFRAME_FEDERATION_DEV_LOOPBACK lets two local servers federate; it is refused without dev auth.
func TestFederationDevLoopback(t *testing.T) {
	base := map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_SIGNING_KEY": strings.Repeat("ab", 32),
		"KINFRAME_DATA_DIR": t.TempDir()}
	get := func(extra map[string]string) func(string) string {
		return func(k string) string {
			if v, ok := extra[k]; ok {
				return v
			}
			return base[k]
		}
	}
	if c, err := load(get(nil)); err != nil || c.Federation.AllowLoopback {
		t.Fatalf("default: %v", err)
	}
	if c, err := load(get(map[string]string{"KINFRAME_FEDERATION_DEV_LOOPBACK": "1"})); err != nil || !c.Federation.AllowLoopback {
		t.Fatalf("with dev auth: %v", err)
	}
	if _, err := load(get(map[string]string{"KINFRAME_FEDERATION_DEV_LOOPBACK": "1", "KINFRAME_DEV_AUTH": "",
		"KINFRAME_OIDC_ISSUER": "https://id.example.org/oidc"})); err == nil || !strings.Contains(err.Error(), "KINFRAME_FEDERATION_DEV_LOOPBACK") {
		t.Fatalf("without dev auth: %v", err)
	}
	if _, err := load(get(map[string]string{"KINFRAME_FEDERATION_DEV_LOOPBACK": "maybe"})); err == nil {
		t.Fatal("a bad value was accepted")
	}
}
