package settings

import (
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"testing"
)

func parse(t *testing.T, s string) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal([]byte(s), &m); err != nil {
		t.Fatal(err)
	}
	return m
}

// Cases from RFC 7396 appendix A (object-valued ones).
func TestMergePatchRFC(t *testing.T) {
	tests := []struct{ target, patch, want string }{
		{`{"a":"b"}`, `{"a":"c"}`, `{"a":"c"}`},
		{`{"a":"b"}`, `{"b":"c"}`, `{"a":"b","b":"c"}`},
		{`{"a":"b"}`, `{"a":null}`, `{}`},
		{`{"a":"b","b":"c"}`, `{"a":null}`, `{"b":"c"}`},
		{`{"a":["b"]}`, `{"a":"c"}`, `{"a":"c"}`},
		{`{"a":"c"}`, `{"a":["b"]}`, `{"a":["b"]}`},
		{`{"a":{"b":"c"}}`, `{"a":{"b":"d","c":null}}`, `{"a":{"b":"d"}}`},
		{`{"a":[{"b":"c"}]}`, `{"a":[1]}`, `{"a":[1]}`},
		{`{"e":null}`, `{"a":1}`, `{"e":null,"a":1}`},
		{`{}`, `{"a":{"bb":{"ccc":null}}}`, `{"a":{"bb":{}}}`},
	}
	for _, tt := range tests {
		got := MergePatch(parse(t, tt.target), parse(t, tt.patch))
		if !reflect.DeepEqual(got, parse(t, tt.want)) {
			t.Errorf("merge(%s, %s) = %v, want %s", tt.target, tt.patch, got, tt.want)
		}
	}
}

func TestLeafPathsAndConflicts(t *testing.T) {
	p := parse(t, `{"slideshow":{"interval_s":10,"order":"shuffle"},"location":null,"display":{"sleep_schedules":[]}}`)
	got := LeafPaths(p)
	want := []string{"display.sleep_schedules", "location", "slideshow.interval_s", "slideshow.order"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("paths = %v", got)
	}
	fv := map[string]int{"slideshow.interval_s": 5, "widgets.clock": 7, "location": 3}
	tests := []struct {
		name  string
		paths []string
		base  int
		want  bool
	}{
		{"up to date", []string{"slideshow.interval_s"}, 7, false},
		{"same field changed later", []string{"slideshow.interval_s"}, 4, true},
		{"other field changed later", []string{"slideshow.order"}, 4, false},
		{"parent replaced", []string{"widgets"}, 6, true},
		{"child of changed leaf", []string{"location.name"}, 2, true},
	}
	for _, tt := range tests {
		if got := Conflicts(tt.paths, tt.base, fv); got != tt.want {
			t.Errorf("%s: got %v", tt.name, got)
		}
	}
}

func TestSanitize(t *testing.T) {
	p := parse(t, `{"version":3,"kiosk":{"owner_pin_set":true,"allow_remove_members_on_frame":false}}`)
	Sanitize(p)
	if !reflect.DeepEqual(p, parse(t, `{"kiosk":{"allow_remove_members_on_frame":false}}`)) {
		t.Fatalf("got %v", p)
	}
}

func TestDefaults(t *testing.T) {
	d := Defaults("nl", "")
	if d["locale"].(map[string]any)["language"] != "nl" || d["locale"].(map[string]any)["timezone"] != "Europe/Brussels" {
		t.Fatal("locale defaults")
	}
	if d["slideshow"].(map[string]any)["interval_s"] != 30 {
		t.Fatal("interval default")
	}
}

type fakeSealer struct{ broken bool }

func (f fakeSealer) Seal(s string) []byte { return []byte("sealed:" + s) }
func (f fakeSealer) Open(b []byte) (string, error) {
	if f.broken {
		return "", errors.New("key changed")
	}
	return strings.TrimPrefix(string(b), "sealed:"), nil
}

// A secret sealed with a key the server no longer has counts as unset for people and is left out for the frame.
func TestPluginSecretsAfterAKeyChange(t *testing.T) {
	current := map[string]any{}
	patch := map[string]any{"plugins": map[string]any{"instances": []any{
		map[string]any{"instance_id": "a", "secrets": map[string]any{"token": "t1", "other": ""}},
	}}}
	ApplyPluginSecrets(patch, current, fakeSealer{})
	doc := Clone(MergePatch(current, patch))
	RenderPluginSecrets(doc, fakeSealer{}, true)
	in := doc["plugins"].(map[string]any)["instances"].([]any)[0].(map[string]any)
	if in["secrets"].(map[string]any)["token"] != "t1" || len(in["secrets_set"].([]any)) != 1 || in[sealedKey] != nil {
		t.Fatalf("device: %v", in)
	}
	doc = Clone(MergePatch(map[string]any{}, patch))
	RenderPluginSecrets(doc, fakeSealer{broken: true}, false)
	in = doc["plugins"].(map[string]any)["instances"].([]any)[0].(map[string]any)
	if len(in["secrets_set"].([]any)) != 0 || in["secrets"] != nil {
		t.Fatalf("people after key change: %v", in)
	}
}
