package e2e

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/config"
)

// Discovery publishes the web app's crash reporting DSN only when the operator set one.
func TestDiscoverySentryDSN(t *testing.T) {
	off := newEnv(t)
	if d := off.get("/.well-known/kinframe", "").m(t); d["sentry_dsn"] != nil {
		t.Fatalf("sentry_dsn without KINFRAME_SENTRY_WEB_DSN: %v", d["sentry_dsn"])
	}
	const dsn = "https://0123abcd@errors.example.org/2"
	on := newEnv(t, func(c *config.Config) { c.SentryWebDSN = dsn })
	if d := on.get("/.well-known/kinframe", "").m(t); d["sentry_dsn"] != dsn {
		t.Fatalf("sentry_dsn = %v", d["sentry_dsn"])
	}
}

// The web build's source maps go to the crash reporting server, never to browsers.
func TestSourceMapsNotServed(t *testing.T) {
	web := t.TempDir()
	dir := filepath.Join(web, "_app", "immutable", "chunks")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	for name, body := range map[string]string{"index.html": "<html></html>", "_app/immutable/chunks/app.js": "x()", "_app/immutable/chunks/app.js.map": "{}"} {
		if err := os.WriteFile(filepath.Join(web, name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	e := newEnv(t, func(c *config.Config) { c.WebDir = web })
	e.get("/_app/immutable/chunks/app.js", "")
	e.expect(req{method: "GET", path: "/_app/immutable/chunks/app.js.map"}, 404)
}
