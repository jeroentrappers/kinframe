package e2e

import (
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
