package logtoboot

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// fakeApps is a minimal in-memory stand-in for Logto's /api/applications.
type fakeApps struct {
	mu      sync.Mutex
	apps    []application
	creates int
	patches int
}

func (f *fakeApps) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/api/applications":
		if r.URL.Query().Get("page") != "1" {
			_ = json.NewEncoder(w).Encode([]application{})
			return
		}
		_ = json.NewEncoder(w).Encode(f.apps)
	case r.Method == http.MethodPost && r.URL.Path == "/api/applications":
		var a application
		_ = json.NewDecoder(r.Body).Decode(&a)
		a.ID = "app" + string(rune('a'+len(f.apps)))
		f.apps = append(f.apps, a)
		f.creates++
		_ = json.NewEncoder(w).Encode(a)
	case r.Method == http.MethodPatch && strings.HasPrefix(r.URL.Path, "/api/applications/"):
		id := strings.TrimPrefix(r.URL.Path, "/api/applications/")
		var patch struct {
			OIDCClientMetadata *oidcClientMetadata `json:"oidcClientMetadata"`
		}
		_ = json.NewDecoder(r.Body).Decode(&patch)
		for i := range f.apps {
			if f.apps[i].ID == id && patch.OIDCClientMetadata != nil {
				f.apps[i].OIDCClientMetadata = *patch.OIDCClientMetadata
			}
		}
		f.patches++
		_ = json.NewEncoder(w).Encode(map[string]any{})
	default:
		http.NotFound(w, r)
	}
}

func TestEnsureDesktopAppIdempotent(t *testing.T) {
	fake := &fakeApps{apps: []application{
		// The mobile app is a Native app too; it must not be mistaken for the desktop one.
		{ID: "mobile", Name: nativeAppName, Type: "Native", OIDCClientMetadata: oidcClientMetadata{RedirectURIs: []string{"app.kinframe:/callback"}}},
	}}
	srv := httptest.NewServer(fake)
	defer srv.Close()
	b := &Bootstrapper{
		log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		m:   &mgmt{hc: srv.Client(), base: srv.URL + "/api", token: "t"},
	}
	ctx := context.Background()

	id, err := b.ensureDesktopApp(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if id == "mobile" || fake.creates != 1 || b.changes != 1 {
		t.Fatalf("first run: id %q, creates %d, changes %d", id, fake.creates, b.changes)
	}
	created := fake.apps[len(fake.apps)-1]
	if created.Name != desktopAppName || created.Type != "Native" {
		t.Fatalf("created %+v", created)
	}
	if got := created.OIDCClientMetadata.RedirectURIs; len(got) != 1 || got[0] != "http://127.0.0.1/callback" {
		t.Fatalf("redirect URIs %v", got)
	}

	// Second run: nothing to do.
	id2, err := b.ensureDesktopApp(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if id2 != id || fake.creates != 1 || fake.patches != 0 || b.changes != 1 {
		t.Fatalf("second run changed something: id %q, creates %d, patches %d", id2, fake.creates, fake.patches)
	}

	// Drift (someone edited the redirects in the console) is repaired.
	fake.apps[len(fake.apps)-1].OIDCClientMetadata.RedirectURIs = []string{"http://localhost:8080/callback"}
	if _, err := b.ensureDesktopApp(ctx); err != nil {
		t.Fatal(err)
	}
	if fake.patches != 1 || fake.apps[len(fake.apps)-1].OIDCClientMetadata.RedirectURIs[0] != "http://127.0.0.1/callback" {
		t.Fatalf("drift not repaired: %+v", fake.apps[len(fake.apps)-1].OIDCClientMetadata)
	}
}
