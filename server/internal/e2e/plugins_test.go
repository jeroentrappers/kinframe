package e2e

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Copies of the repository's manifests (plugins/<id>/plugin.json), as the frame app reports them; the server's tests
// build without the rest of the repository.
func catalogOf(t *testing.T, ids ...string) map[string]any {
	t.Helper()
	var plugins []any
	for _, id := range ids {
		raw, err := os.ReadFile(filepath.Join("testdata", "plugins", id+".json"))
		if err != nil {
			t.Fatal(err)
		}
		var m map[string]any
		if err := json.Unmarshal(raw, &m); err != nil {
			t.Fatal(err)
		}
		plugins = append(plugins, map[string]any{"manifest": m, "builtin": true,
			"locales": map[string]any{"nl": map[string]any{"@name": "Energie"}}})
	}
	return map[string]any{"plugins": plugins}
}

// Plugin instances live in the settings; secrets are written by people, read back only by the frame, kept per
// instance when the array is sent again without them, and checked against the catalog the frame reported.
func TestPluginSettings(t *testing.T) {
	e := newEnv(t)
	owner := e.user("o@x.org", "O")
	d, frameID := e.claim(owner)
	path := "/v1/frames/" + frameID + "/settings"
	patch := func(v string, body map[string]any) *resp {
		return e.do(req{method: "PATCH", path: path, token: owner.token, body: body, header: map[string]string{"If-Match": v}})
	}
	instances := func(list ...map[string]any) map[string]any {
		arr := make([]any, len(list))
		for i, x := range list {
			arr[i] = x
		}
		return map[string]any{"plugins": map[string]any{"layout": map[string]any{"mode": "overlay"}, "instances": arr}}
	}
	energy := func(extra map[string]any) map[string]any {
		in := map[string]any{"instance_id": "energy-1", "plugin_id": "kinframe.ha-energy", "slot": "top-left", "enabled": true,
			"settings": map[string]any{"base_url": "http://192.168.1.157:8123", "grid": "sensor.grid_power"}}
		for k, v := range extra {
			in[k] = v
		}
		return in
	}

	// Before the frame reported a catalog, nothing can be checked but the shape.
	if r := patch(`"1"`, instances(map[string]any{"instance_id": "Bad id", "plugin_id": "x.y"})); r.StatusCode != 422 {
		t.Fatalf("bad id: %d %s", r.StatusCode, r.body)
	}
	e.expect(req{method: "PUT", path: "/v1/device/plugins/catalog", token: d.token, body: catalogOf(t, "kinframe.ha-energy", "kinframe.homewizard-p1")}, 204)
	cat := e.get("/v1/frames/"+frameID+"/plugins", owner.token).m(t)
	if list, _ := cat["catalog"].([]any); len(list) != 2 || cat["catalog_at"] == nil || dig(list[0].(map[string]any), "locales", "nl", "@name") != "Energie" {
		t.Fatalf("catalog: %v", cat)
	}

	// Enabled without its required token: refused, naming the field.
	r := patch(`"1"`, instances(energy(nil)))
	if r.StatusCode != 422 || !strings.Contains(string(r.body), "plugins.instances.0.secrets.token") {
		t.Fatalf("missing token: %d %s", r.StatusCode, r.body)
	}
	// A secret in the plain settings, an unknown setting, a wrong choice, a plugin the frame doesn't have.
	for name, body := range map[string]map[string]any{
		"secret in settings": instances(energy(map[string]any{"enabled": false, "settings": map[string]any{"token": "x"}})),
		"unknown setting":    instances(energy(map[string]any{"enabled": false, "settings": map[string]any{"colour": "red"}})),
		"bad choice":         instances(energy(map[string]any{"enabled": false, "settings": map[string]any{"style": "pie"}})),
		"bad url":            instances(energy(map[string]any{"enabled": false, "settings": map[string]any{"base_url": "ftp://x"}})),
		"not on frame":       instances(map[string]any{"instance_id": "a", "plugin_id": "community.nope", "enabled": false}),
		"duplicate":          instances(energy(map[string]any{"enabled": false}), energy(map[string]any{"enabled": false})),
	} {
		if r := patch(`"1"`, body); r.StatusCode != 422 {
			t.Fatalf("%s: %d %s", name, r.StatusCode, r.body)
		}
	}

	// With the token: saved; people see only that it is set.
	r = patch(`"1"`, instances(energy(map[string]any{"secrets": map[string]any{"token": "eyJ-secret-token"}})))
	if r.StatusCode != 200 || strings.Contains(string(r.body), "eyJ-secret-token") {
		t.Fatalf("save: %d %s", r.StatusCode, r.body)
	}
	in := dig(r.m(t), "plugins", "instances").([]any)[0].(map[string]any)
	if set, _ := in["secrets_set"].([]any); len(set) != 1 || set[0] != "token" || in["secrets"] != nil || in["secrets_sealed"] != nil {
		t.Fatalf("people's view: %v", in)
	}
	if s := e.get(path, owner.token); strings.Contains(string(s.body), "eyJ-secret-token") || strings.Contains(string(s.body), "secrets_sealed") {
		t.Fatalf("GET leaks: %s", s.body)
	}
	// The frame gets it in full.
	feed := changesOf(e.get("/v1/device/changes?since=0", d.token).m(t), "settings")
	din := dig(feed[0], "settings", "plugins", "instances").([]any)[0].(map[string]any)
	if dig(din, "secrets", "token") != "eyJ-secret-token" {
		t.Fatalf("device view: %v", din)
	}

	// Sending the instances again (as a form does, from what people see) keeps the token; moving the widget too.
	r = patch(`"2"`, instances(energy(map[string]any{"slot": "top-right", "secrets_set": []any{"token"}})))
	if r.StatusCode != 200 {
		t.Fatalf("resave: %d %s", r.StatusCode, r.body)
	}
	feed = changesOf(e.get("/v1/device/changes?since=0", d.token).m(t), "settings")
	din = dig(feed[0], "settings", "plugins", "instances").([]any)[0].(map[string]any)
	if dig(din, "secrets", "token") != "eyJ-secret-token" || din["slot"] != "top-right" {
		t.Fatalf("kept: %v", din)
	}
	// A new instance id doesn't inherit another's secrets; removing the token disables nothing silently: refused.
	if r := patch(`"3"`, instances(energy(map[string]any{"instance_id": "energy-2"}))); r.StatusCode != 422 {
		t.Fatalf("new id: %d %s", r.StatusCode, r.body)
	}
	if r := patch(`"3"`, instances(energy(map[string]any{"secrets": map[string]any{"token": nil}}))); r.StatusCode != 422 {
		t.Fatalf("remove token while enabled: %d %s", r.StatusCode, r.body)
	}
	r = patch(`"3"`, instances(energy(map[string]any{"enabled": false, "secrets": map[string]any{"token": nil}})))
	in = dig(r.m(t), "plugins", "instances").([]any)[0].(map[string]any)
	if r.StatusCode != 200 || len(in["secrets_set"].([]any)) != 0 {
		t.Fatalf("removed: %d %s", r.StatusCode, r.body)
	}

	// The frame's own change (moved on the frame): same rules, it may send the secrets it has.
	raw, _ := json.Marshal(instances(energy(map[string]any{"secrets": map[string]any{"token": "from-frame"}})))
	dr := e.do(req{method: "PATCH", path: "/v1/device/settings", token: d.token, raw: raw,
		header: map[string]string{"If-Match": `"4"`, "Content-Type": "application/merge-patch+json"}})
	if dr.StatusCode != 200 || dig(dr.m(t), "plugins", "instances").([]any)[0].(map[string]any)["secrets"].(map[string]any)["token"] != "from-frame" {
		t.Fatalf("device patch: %d %s", dr.StatusCode, dr.body)
	}

	// Status and discovery, for the forms.
	e.expect(req{method: "PUT", path: "/v1/device/plugins/status", token: d.token, body: map[string]any{
		"instances": []any{map[string]any{"instance_id": "energy-1", "running": true, "error": nil,
			"entities": []any{map[string]any{"entity_id": "sensor.grid_power", "name": "Grid power", "unit": "W", "device_class": "power"}}}},
		"services": map[string]any{"_home-assistant._tcp": []any{map[string]any{"name": "Home", "url": "http://192.168.1.157:8123"}}},
	}}, 204)
	cat = e.get("/v1/frames/"+frameID+"/plugins", owner.token).m(t)
	st := cat["instances"].([]any)[0].(map[string]any)
	if st["running"] != true || st["entities"].([]any)[0].(map[string]any)["unit"] != "W" || cat["status_at"] == nil ||
		cat["services"].(map[string]any)["_home-assistant._tcp"].([]any)[0].(map[string]any)["name"] != "Home" {
		t.Fatalf("status: %v", cat)
	}

	// Members without change_settings see neither.
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: d.token}, 200).m(t)["code"]
	bob := e.user("bob@x.org", "Bob")
	e.expect(req{method: "POST", path: "/v1/frames/redeem", token: bob.token, body: map[string]any{"code": code}}, 200)
	if r := e.do(req{method: "GET", path: "/v1/frames/" + frameID + "/plugins", token: bob.token}); r.StatusCode != 403 {
		t.Fatalf("member: %d", r.StatusCode)
	}
}
