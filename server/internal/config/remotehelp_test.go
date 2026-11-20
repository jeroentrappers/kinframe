package config

import (
	"strings"
	"testing"
)

func TestRemoteHelpConfig(t *testing.T) {
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
	c, err := load(get(nil))
	if err != nil || c.RemoteHelp.Enabled || c.RemoteHelp.Ready() {
		t.Fatalf("default: %v %+v", err, c.RemoteHelp)
	}
	on := map[string]string{"KINFRAME_REMOTE_HELP": "on", "KINFRAME_HEADSCALE_URL": "https://hs.example.org/", "KINFRAME_HEADSCALE_API_KEY": "k",
		"KINFRAME_HEADSCALE_USER": "kinframe-frames", "KINFRAME_REMOTE_HELP_ADB_KEY": "QAAAA= user@host"}
	c, err = load(get(on))
	if err != nil || !c.RemoteHelp.Enabled || c.RemoteHelp.Ready() || c.RemoteHelp.HeadscaleURL != "https://hs.example.org" {
		t.Fatalf("on without the policy: %v %+v", err, c.RemoteHelp)
	}
	if c.RemoteHelp.TailscaleSHA256["arm64"] != defaultTailscaleSHA256["arm64"] ||
		c.RemoteHelp.TailscaleURL("arm") != "https://pkgs.tailscale.com/stable/tailscale_1.104.7_arm.tgz" {
		t.Fatalf("tailscale %+v", c.RemoteHelp)
	}
	on["KINFRAME_REMOTE_HELP_POLICY_CHECKED"] = "1"
	if c, err = load(get(on)); err != nil || !c.RemoteHelp.Ready() {
		t.Fatalf("ready: %v", err)
	}
	on["KINFRAME_REMOTE_HELP_TAILSCALE_VERSION"] = "1.106.0"
	if _, err = load(get(on)); err == nil || !strings.Contains(err.Error(), "SHA256_ARM") {
		t.Fatalf("another version without hashes: %v", err)
	}
	delete(on, "KINFRAME_REMOTE_HELP_TAILSCALE_VERSION")
	delete(on, "KINFRAME_HEADSCALE_USER")
	if _, err = load(get(on)); err == nil || !strings.Contains(err.Error(), "KINFRAME_HEADSCALE_USER") {
		t.Fatalf("missing user: %v", err)
	}
}
