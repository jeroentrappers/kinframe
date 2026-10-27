package plugins

import (
	"encoding/json"
	"testing"
)

const catalogJSON = `{"plugins": [{"manifest": {"id": "kinframe.ha-energy", "slots": ["top-left"], "settings": [
	{"key": "base_url", "type": "url", "required": true},
	{"key": "token", "type": "secret", "required": true},
	{"key": "solar", "type": "entity", "multiple": true},
	{"key": "grid", "type": "entity"},
	{"key": "max_w", "type": "number", "required": true, "default": 4000},
	{"key": "flip", "type": "boolean"}
]}}, {"manifest": "not an object"}]}`

func check(t *testing.T, in map[string]any) []Problem {
	t.Helper()
	c, err := ParseCatalog([]byte(catalogJSON))
	if err != nil || len(c) != 1 {
		t.Fatalf("catalog: %v %v", c, err)
	}
	var round map[string]any
	b, _ := json.Marshal(in)
	_ = json.Unmarshal(b, &round)
	return Check([]map[string]any{round}, c)
}

func TestTypes(t *testing.T) {
	ok := map[string]any{"instance_id": "a", "plugin_id": "kinframe.ha-energy", "slot": "top-left",
		"settings":       map[string]any{"base_url": "http://ha.local:8123", "solar": []string{"sensor.a", "sensor.b"}, "flip": true, "max_w": 5000},
		"secrets_sealed": map[string]any{"token": "x", "gone": "y"}}
	if p := check(t, ok); len(p) != 0 {
		t.Fatalf("valid: %v", p)
	}
	for name, settings := range map[string]map[string]any{
		"list for a single entity": {"grid": []string{"a"}},
		"number as text":           {"max_w": "5000"},
		"boolean as text":          {"flip": "yes"},
	} {
		in := map[string]any{"instance_id": "a", "plugin_id": "kinframe.ha-energy", "enabled": false, "settings": settings}
		if p := check(t, in); len(p) != 1 || p[0].Reason != "type" {
			t.Fatalf("%s: %v", name, p)
		}
	}
	for _, u := range []string{"192.168.1.50", "p1.local:8080", "https://ha.example.org"} {
		in := map[string]any{"instance_id": "a", "plugin_id": "kinframe.ha-energy", "enabled": false, "settings": map[string]any{"base_url": u}}
		if p := check(t, in); len(p) != 0 {
			t.Fatalf("url %s: %v", u, p)
		}
	}
	for _, u := range []string{"ftp://x", "http://", "two words"} {
		in := map[string]any{"instance_id": "a", "plugin_id": "kinframe.ha-energy", "enabled": false, "settings": map[string]any{"base_url": u}}
		if p := check(t, in); len(p) != 1 {
			t.Fatalf("url %s: %v", u, p)
		}
	}
	if p := check(t, map[string]any{"instance_id": "a", "plugin_id": "kinframe.ha-energy", "slot": "top-right", "enabled": false}); len(p) != 1 || p[0].Path != "plugins.instances.0.slot" {
		t.Fatalf("slot: %v", p)
	}
}

// A required setting with a default is satisfied; secrets the manifest dropped go.
func TestRequiredAndStaleSecrets(t *testing.T) {
	c, _ := ParseCatalog([]byte(catalogJSON))
	in := map[string]any{"instance_id": "a", "plugin_id": "kinframe.ha-energy",
		"settings": map[string]any{"base_url": "http://ha.local:8123"}, "secrets_sealed": map[string]any{"token": "x", "old_key": "y"}}
	if p := Check([]map[string]any{in}, c); len(p) != 0 {
		t.Fatalf("problems: %v", p)
	}
	if s := in["secrets_sealed"].(map[string]any); len(s) != 1 || s["token"] == nil {
		t.Fatalf("sealed: %v", s)
	}
	in = map[string]any{"instance_id": "a", "plugin_id": "kinframe.ha-energy", "settings": map[string]any{"base_url": " "}}
	if p := Check([]map[string]any{in}, c); len(p) != 2 {
		t.Fatalf("blank url and no token: %v", p)
	}
}
