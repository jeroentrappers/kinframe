package config

import (
	"strings"
	"testing"
)

func TestLinkPreviewsConfig(t *testing.T) {
	base := func(v string) map[string]string {
		env := map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_SIGNING_KEY": strings.Repeat("ab", 32),
			"KINFRAME_DATA_DIR": t.TempDir()}
		if v != "" {
			env["KINFRAME_LINK_PREVIEWS"] = v
		}
		return env
	}
	for v, want := range map[string]bool{"": false, "0": false, "off": false, "1": true, "on": true, "TRUE": true} {
		env := base(v)
		c, err := load(func(k string) string { return env[k] })
		if err != nil || c.LinkPreviews != want {
			t.Errorf("KINFRAME_LINK_PREVIEWS=%q: %v %v, want %v", v, c != nil && c.LinkPreviews, err, want)
		}
	}
	env := base("sometimes")
	if _, err := load(func(k string) string { return env[k] }); err == nil || !strings.Contains(err.Error(), "KINFRAME_LINK_PREVIEWS") {
		t.Fatalf("bad value: %v", err)
	}
}
