package logtoboot

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeGlitchTipLogto adds application secrets and user search to fakeApps.
// Like Logto, it gives every new Traditional app a default secret.
type fakeGlitchTipLogto struct {
	apps          *fakeApps
	mu            sync.Mutex
	secrets       map[string][]appSecret
	secretCreates int
	users         []user
}

func (f *fakeGlitchTipLogto) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	rest, isApp := strings.CutPrefix(r.URL.Path, "/api/applications/")
	switch {
	case isApp && strings.HasSuffix(rest, "/secrets"):
		id := strings.TrimSuffix(rest, "/secrets")
		switch r.Method {
		case http.MethodGet:
			_ = json.NewEncoder(w).Encode(f.secrets[id])
		case http.MethodPost:
			var body struct {
				Name string `json:"name"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			for _, s := range f.secrets[id] {
				if s.Name == body.Name {
					http.Error(w, `{"code":"application.secret_name_exists"}`, http.StatusUnprocessableEntity)
					return
				}
			}
			s := appSecret{Name: body.Name, Value: "secret-" + body.Name, CreatedAt: time.Now().UnixMilli()}
			f.secrets[id] = append(f.secrets[id], s)
			f.secretCreates++
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(s)
		}
	case r.Method == http.MethodGet && r.URL.Path == "/api/users":
		var out []user
		if r.URL.Query().Get("page") == "1" {
			for _, u := range f.users {
				if strings.EqualFold(u.PrimaryEmail, r.URL.Query().Get("search.primaryEmail")) {
					out = append(out, u)
				}
			}
		}
		_ = json.NewEncoder(w).Encode(out)
	default:
		n := len(f.apps.apps)
		f.apps.ServeHTTP(w, r)
		if len(f.apps.apps) > n {
			a := f.apps.apps[n]
			if a.Type == "Traditional" {
				f.secrets[a.ID] = []appSecret{{Name: "Default secret", Value: "default-" + a.ID, CreatedAt: 1}}
			}
		}
	}
}

func TestEnsureGlitchTip(t *testing.T) {
	fake := &fakeGlitchTipLogto{
		apps: &fakeApps{apps: []application{
			{ID: "web", Name: webAppName, Type: "SPA"},
		}},
		secrets: map[string][]appSecret{},
		users:   []user{{ID: "sub-anna", PrimaryEmail: "Anna@example.org"}},
	}
	srv := httptest.NewServer(fake)
	defer srv.Close()
	gtURL, _ := url.Parse("https://errors.example.org")
	file := filepath.Join(t.TempDir(), "glitchtip-oidc.json")
	b := &Bootstrapper{
		cfg: &Config{
			GlitchTipURL:   gtURL,
			GlitchTipFile:  file,
			OperatorEmails: []string{"anna@example.org", "bob@example.org"},
		},
		log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		m:   &mgmt{hc: srv.Client(), base: srv.URL + "/api", token: "t"},
	}
	ctx := context.Background()
	const issuer = "https://auth.example.org/oidc"
	read := func() GlitchTipFile {
		t.Helper()
		st, err := os.Stat(file)
		if err != nil {
			t.Fatal(err)
		}
		if st.Mode().Perm() != 0o600 {
			t.Fatalf("mode %v, want 0600", st.Mode().Perm())
		}
		var f GlitchTipFile
		data, _ := os.ReadFile(file)
		if err := json.Unmarshal(data, &f); err != nil {
			t.Fatal(err)
		}
		return f
	}

	if err := b.ensureGlitchTip(ctx, issuer); err != nil {
		t.Fatal(err)
	}
	app := fake.apps.apps[len(fake.apps.apps)-1]
	if app.Name != glitchtipAppName || app.Type != "Traditional" {
		t.Fatalf("created %+v", app)
	}
	if got := app.OIDCClientMetadata; strings.Join(got.RedirectURIs, ",") != "https://errors.example.org/accounts/oidc/logto/login/callback/" ||
		strings.Join(got.PostLogoutRedirectURIs, ",") != "https://errors.example.org/" {
		t.Fatalf("client metadata %+v", got)
	}
	f := read()
	want := GlitchTipFile{
		Issuer: issuer, ProviderID: "logto", ClientID: app.ID, ClientSecret: "default-" + app.ID,
		RedirectURI: "https://errors.example.org/accounts/oidc/logto/login/callback/",
		Operators:   []GlitchTipOperator{{Email: "anna@example.org", Sub: "sub-anna"}},
	}
	if !jsonEqual(f, want) {
		t.Fatalf("file %+v, want %+v", f, want)
	}
	if fake.secretCreates != 0 || b.changes != 2 {
		t.Fatalf("first run: secret creates %d, changes %d", fake.secretCreates, b.changes)
	}

	// Second run: nothing to do, no new secret.
	if err := b.ensureGlitchTip(ctx, issuer); err != nil {
		t.Fatal(err)
	}
	if fake.apps.creates != 1 || fake.apps.patches != 0 || fake.secretCreates != 0 || b.changes != 2 {
		t.Fatalf("second run changed something: creates %d, patches %d, secrets %d, changes %d",
			fake.apps.creates, fake.apps.patches, fake.secretCreates, b.changes)
	}

	// A second, older secret added in the console does not replace the one
	// GlitchTip already has.
	fake.secrets[app.ID] = append([]appSecret{{Name: "older", Value: "older", CreatedAt: 0}}, fake.secrets[app.ID]...)
	if err := b.ensureGlitchTip(ctx, issuer); err != nil {
		t.Fatal(err)
	}
	if got := read().ClientSecret; got != "default-"+app.ID || b.changes != 2 {
		t.Fatalf("secret switched to %q (changes %d)", got, b.changes)
	}

	// Once every secret is expired (or about to), a new one is made, and the
	// operator who signed up meanwhile is added.
	soon := time.Now().Add(time.Hour).UnixMilli()
	for i := range fake.secrets[app.ID] {
		fake.secrets[app.ID][i].ExpiresAt = &soon
	}
	fake.users = append(fake.users, user{ID: "sub-bob", PrimaryEmail: "bob@example.org"})
	if err := b.ensureGlitchTip(ctx, issuer); err != nil {
		t.Fatal(err)
	}
	f = read()
	if fake.secretCreates != 1 || f.ClientSecret != "secret-glitchtip" || len(f.Operators) != 2 || f.Operators[1].Sub != "sub-bob" {
		t.Fatalf("after expiry: creates %d, file %+v", fake.secretCreates, f)
	}

	// Without KINFRAME_LOGTO_GLITCHTIP_URL nothing is touched.
	b.cfg.GlitchTipURL = nil
	before := b.changes
	if err := b.ensureGlitchTip(ctx, issuer); err != nil || b.changes != before {
		t.Fatalf("disabled run: err %v, changes %d→%d", err, before, b.changes)
	}
}

func TestEnsureAppSecretNamesNewSecretUniquely(t *testing.T) {
	past := time.Now().Add(-time.Hour).UnixMilli()
	fake := &fakeGlitchTipLogto{
		apps:    &fakeApps{},
		secrets: map[string][]appSecret{"gt": {{Name: glitchtipSecretName, Value: "old", ExpiresAt: &past}}},
	}
	srv := httptest.NewServer(fake)
	defer srv.Close()
	b := &Bootstrapper{
		log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		m:   &mgmt{hc: srv.Client(), base: srv.URL + "/api", token: "t"},
	}
	got, err := b.ensureAppSecret(context.Background(), "gt", "old")
	if err != nil {
		t.Fatal(err)
	}
	if got == "old" || !strings.HasPrefix(got, "secret-glitchtip-") || fake.secretCreates != 1 {
		t.Fatalf("got %q, creates %d", got, fake.secretCreates)
	}
}

func TestLoadConfigGlitchTip(t *testing.T) {
	env := map[string]string{
		"KINFRAME_PUBLIC_URL":           "https://kinframe.example.org",
		"KINFRAME_LOGTO_ENDPOINT":       "https://auth.example.org",
		"KINFRAME_LOGTO_ADMIN_ENDPOINT": "https://admin.example.org",
		"KINFRAME_LOGTO_ADMIN_USERNAME": "admin",
		"KINFRAME_LOGTO_ADMIN_PASSWORD": "pw",
		"KINFRAME_DATA_DIR":             "/data",
	}
	get := func(k string) string { return env[k] }
	c, err := loadConfig(get)
	if err != nil {
		t.Fatal(err)
	}
	if c.GlitchTipURL != nil || c.GlitchTipFile != "/data/glitchtip-oidc.json" {
		t.Fatalf("defaults: url %v, file %q", c.GlitchTipURL, c.GlitchTipFile)
	}
	env["KINFRAME_LOGTO_GLITCHTIP_URL"] = "https://errors.example.org/"
	env["KINFRAME_LOGTO_GLITCHTIP_FILE"] = "/run/gt.json"
	if c, err = loadConfig(get); err != nil {
		t.Fatal(err)
	}
	if originOf(c.GlitchTipURL) != "https://errors.example.org" || c.GlitchTipFile != "/run/gt.json" {
		t.Fatalf("url %v, file %q", c.GlitchTipURL, c.GlitchTipFile)
	}
	env["KINFRAME_LOGTO_GLITCHTIP_URL"] = "https://errors.example.org/glitchtip"
	if _, err := loadConfig(get); err == nil || !strings.Contains(err.Error(), "KINFRAME_LOGTO_GLITCHTIP_URL") {
		t.Fatalf("path accepted: %v", err)
	}
}
