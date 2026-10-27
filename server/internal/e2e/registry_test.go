package e2e

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/registry"
)

const communityManifest = `{"id":"community.rain","name":"Rain","version":"1.0.0","api":1,"license":"MIT",
 "slots":["top-left","top-right"],"refresh":{"min_seconds":60,"default_seconds":300},
 "permissions":{"http":["https://api.example.org"]},
 "settings":[{"key":"show_dry","type":"boolean","default":false}]}`

// A signed registry with one community plugin, served the way the GitHub release serves it.
func fakeRegistry(t *testing.T) (url, publicKey string) {
	t.Helper()
	var zb bytes.Buffer
	zw := zip.NewWriter(&zb)
	for name, body := range map[string]string{"plugin.json": communityManifest, "main.js": "export default { render() { return null; } };"} {
		w, _ := zw.Create(name)
		w.Write([]byte(body))
	}
	zw.Close()
	bundle := zb.Bytes()
	sum := sha256.Sum256(bundle)
	var manifest map[string]any
	_ = json.Unmarshal([]byte(communityManifest), &manifest)
	ix := registry.Index{Format: 1, Sequence: 1, GeneratedAt: time.Now().UTC(), Plugins: []registry.Plugin{{ID: "community.rain", Versions: []registry.Version{{
		Version: "1.0.0", API: 1, File: "community.rain-1.0.0.zip", Size: int64(len(bundle)), SHA256: hex.EncodeToString(sum[:]),
		PublishedAt: time.Now().UTC(), Manifest: manifest, Locales: map[string]map[string]string{"nl": {"@name": "Regen"}},
	}}}}}
	raw, _ := json.Marshal(ix)
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	h := sha256.Sum256(raw)
	sig, _ := ecdsa.SignASN1(rand.Reader, key, h[:])
	files := map[string][]byte{"index.json": raw, "index.json.sig": []byte(base64.StdEncoding.EncodeToString(sig)), "community.rain-1.0.0.zip": bundle}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if b, ok := files[strings.TrimPrefix(r.URL.Path, "/")]; ok {
			w.Write(b)
			return
		}
		http.NotFound(w, r)
	}))
	t.Cleanup(srv.Close)
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	return srv.URL, base64.StdEncoding.EncodeToString(der)
}

// The community registry: mirrored and served to frames, offered in the apps' catalog, and checked like a built-in.
func TestPluginRegistry(t *testing.T) {
	url, pub := fakeRegistry(t)
	e := newEnv(t, func(c *config.Config) {
		c.PluginRegistryURL, c.PluginRegistryKey, c.PluginRegistryInterval = url, pub, time.Hour
	})
	if err := e.app.Registry.Sync(context.Background()); err != nil {
		t.Fatal(err)
	}
	owner := e.user("o@x.org", "O")
	d, frameID := e.claim(owner)

	// The frame gets the index as signed and can verify it itself.
	reg := e.get("/v1/device/plugins/registry", d.token).m(t)
	raw, _ := base64.StdEncoding.DecodeString(reg["index"].(string))
	key, _ := registry.ParsePublicKey(pub)
	if _, err := registry.Verify(key, raw, reg["signature"].(string)); err != nil || reg["sequence"] != float64(1) {
		t.Fatalf("registry: %v %v", err, reg)
	}
	b := e.do(req{method: "GET", path: "/v1/device/plugins/bundles/community.rain-1.0.0.zip", token: d.token})
	if b.StatusCode != 200 || b.Header.Get("ETag") == "" || len(b.body) == 0 {
		t.Fatalf("bundle: %d", b.StatusCode)
	}
	if r := e.do(req{method: "GET", path: "/v1/device/plugins/bundles/community.rain-9.9.9.zip", token: d.token}); r.StatusCode != 404 {
		t.Fatalf("unknown bundle: %d", r.StatusCode)
	}

	// The apps see it in the catalog once the frame reported its own (an app that knows about plugins).
	e.expect(req{method: "PUT", path: "/v1/device/plugins/catalog", token: d.token, body: catalogOf(t, "kinframe.homewizard-p1")}, 204)
	cat := e.get("/v1/frames/"+frameID+"/plugins", owner.token).m(t)["catalog"].([]any)
	var found map[string]any
	for _, c := range cat {
		if dig(c.(map[string]any), "manifest", "id") == "community.rain" {
			found = c.(map[string]any)
		}
	}
	if found == nil || found["builtin"] != false || dig(found, "locales", "nl", "@name") != "Regen" {
		t.Fatalf("catalog: %v", cat)
	}

	// An instance of it is checked against its manifest; a version the registry doesn't have is refused.
	path := "/v1/frames/" + frameID + "/settings"
	inst := func(version string, settings map[string]any) map[string]any {
		return map[string]any{"plugins": map[string]any{"instances": []any{map[string]any{
			"instance_id": "p-rain000001", "plugin_id": "community.rain", "version": version, "settings": settings}}}}
	}
	patch := func(v string, body map[string]any) *resp {
		return e.do(req{method: "PATCH", path: path, token: owner.token, body: body, header: map[string]string{"If-Match": v}})
	}
	if r := patch(`"1"`, inst("1.0.0", map[string]any{"show_dry": "yes"})); r.StatusCode != 422 {
		t.Fatalf("wrong type: %d %s", r.StatusCode, r.body)
	}
	if r := patch(`"1"`, inst("2.0.0", map[string]any{})); r.StatusCode != 422 || !strings.Contains(string(r.body), "not_on_frame") {
		t.Fatalf("unknown version: %d %s", r.StatusCode, r.body)
	}
	if r := patch(`"1"`, inst("1.0.0", map[string]any{"show_dry": true})); r.StatusCode != 200 {
		t.Fatalf("valid: %d %s", r.StatusCode, r.body)
	}
}
