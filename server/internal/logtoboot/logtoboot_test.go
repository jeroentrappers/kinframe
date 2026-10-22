package logtoboot

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
)

func TestLoadConfig(t *testing.T) {
	base := map[string]string{
		"KINFRAME_PUBLIC_URL":           "https://kinframe.example.org/",
		"KINFRAME_LOGTO_ENDPOINT":       "https://auth.example.org",
		"KINFRAME_LOGTO_URL":            "http://logto:3001",
		"KINFRAME_LOGTO_ADMIN_ENDPOINT": "http://localhost:8112",
		"KINFRAME_LOGTO_ADMIN_URL":      "http://logto:3002",
		"KINFRAME_LOGTO_ADMIN_USERNAME": "admin",
		"KINFRAME_LOGTO_ADMIN_PASSWORD": "pw",
		"KINFRAME_SMTP_HOST":            "smtp.example.org",
		"KINFRAME_SMTP_FROM":            "kinframe <noreply@example.org>",
		"KINFRAME_OPERATOR_EMAILS":      "Anna@Example.org, bob@example.org",
		"KINFRAME_DATA_DIR":             "/data",
	}
	with := func(kv ...string) func(string) string {
		m := map[string]string{}
		for k, v := range base {
			m[k] = v
		}
		for i := 0; i < len(kv); i += 2 {
			m[kv[i]] = kv[i+1]
		}
		return func(k string) string { return m[k] }
	}

	c, err := loadConfig(with())
	if err != nil {
		t.Fatal(err)
	}
	if got := c.APIResource(); got != "https://kinframe.example.org/v1" {
		t.Errorf("APIResource = %q", got)
	}
	if got := c.Origin(); got != "https://kinframe.example.org" {
		t.Errorf("Origin = %q", got)
	}
	if c.SMTP.Port != 587 || c.OutputFile != "/data/logto.json" {
		t.Errorf("defaults: port %d, file %q", c.SMTP.Port, c.OutputFile)
	}
	if strings.Join(c.OperatorEmails, ",") != "anna@example.org,bob@example.org" {
		t.Errorf("operator emails = %v", c.OperatorEmails)
	}

	for name, tc := range map[string]struct {
		get  func(string) string
		want string
	}{
		"no credentials":     {with("KINFRAME_LOGTO_ADMIN_USERNAME", "", "KINFRAME_LOGTO_ADMIN_PASSWORD", ""), "KINFRAME_LOGTO_M2M_ID/SECRET"},
		"half m2m":           {with("KINFRAME_LOGTO_M2M_ID", "x"), "set both KINFRAME_LOGTO_M2M_ID"},
		"admin w/o endpoint": {with("KINFRAME_LOGTO_ADMIN_ENDPOINT", "", "KINFRAME_LOGTO_ADMIN_URL", ""), "KINFRAME_LOGTO_ADMIN_ENDPOINT is required"},
		"endpoint with path": {with("KINFRAME_LOGTO_ENDPOINT", "https://auth.example.org/oidc"), "must not have a path"},
		"bad email":          {with("KINFRAME_OPERATOR_EMAILS", "nope"), "invalid address"},
		"smtp without from":  {with("KINFRAME_SMTP_FROM", ""), "KINFRAME_SMTP_FROM is required"},
		"bad port":           {with("KINFRAME_SMTP_PORT", "smtp"), "KINFRAME_SMTP_PORT"},
	} {
		t.Run(name, func(t *testing.T) {
			_, err := loadConfig(tc.get)
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want %q", err, tc.want)
			}
		})
	}

	// M2M credentials alone are enough; no admin endpoint needed.
	c, err = loadConfig(with("KINFRAME_LOGTO_ADMIN_USERNAME", "", "KINFRAME_LOGTO_ADMIN_PASSWORD", "",
		"KINFRAME_LOGTO_ADMIN_ENDPOINT", "", "KINFRAME_LOGTO_ADMIN_URL", "", "KINFRAME_LOGTO_M2M_ID", "id", "KINFRAME_LOGTO_M2M_SECRET", "s"))
	if err != nil {
		t.Fatal(err)
	}
	if c.M2MID != "id" {
		t.Fatal("m2m not loaded")
	}
}

func TestSubsetEqual(t *testing.T) {
	have := map[string]any{
		"signUp":      map[string]any{"identifiers": []any{"email"}, "password": false, "verify": true},
		"color":       map[string]any{"primaryColor": "#5D34F2"},
		"passkey":     map[string]any{"enabled": true},
		"port":        float64(587),
		"emptyRemote": []any{},
	}
	cases := []struct {
		want any
		eq   bool
	}{
		{map[string]any{"signUp": map[string]any{"identifiers": []string{"email"}, "verify": true}}, true},
		{map[string]any{"signUp": map[string]any{"identifiers": []string{"email"}, "secondaryIdentifiers": []any{}}}, true},
		{map[string]any{"signUp": map[string]any{"identifiers": []string{"email", "phone"}}}, false},
		{map[string]any{"port": 587}, true},
		{map[string]any{"port": 465}, false},
		{map[string]any{"missing": "x"}, false},
		{map[string]any{"passkey": map[string]any{"enabled": false}}, false},
	}
	for i, c := range cases {
		if got := subsetEqual(c.want, have); got != c.eq {
			t.Errorf("case %d: subsetEqual = %v, want %v", i, got, c.eq)
		}
	}
}

func TestRewriteTransport(t *testing.T) {
	var gotHost, gotProto, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotHost, gotProto, gotPath = r.Host, r.Header.Get("X-Forwarded-Proto"), r.URL.Path
	}))
	defer srv.Close()
	pub, _ := url.Parse("https://auth.example.org")
	in, _ := url.Parse(srv.URL)
	hc := &http.Client{Transport: newRewriteTransport(http.DefaultTransport, [2]*url.URL{pub, in})}
	resp, err := hc.Get("https://auth.example.org/oidc/jwks")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if gotHost != "auth.example.org" || gotProto != "https" || gotPath != "/oidc/jwks" {
		t.Fatalf("host %q proto %q path %q", gotHost, gotProto, gotPath)
	}
	// Unmapped origins pass through untouched.
	resp, err = hc.Get(srv.URL + "/x")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if gotProto != "" || gotPath != "/x" {
		t.Fatalf("passthrough rewrote the request: proto %q", gotProto)
	}
}

func TestWriteOutputIdempotent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sub", "logto.json")
	b := &Bootstrapper{cfg: &Config{OutputFile: path}, log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	lf := config.LogtoFile{Issuer: "https://auth.example.org/oidc", WebClientID: "w", MobileClientID: "m", DesktopClientID: "d", APIResource: "https://k.example.org/v1"}
	if err := b.writeOutput(lf); err != nil {
		t.Fatal(err)
	}
	st1, _ := os.Stat(path)
	time.Sleep(10 * time.Millisecond)
	if err := b.writeOutput(lf); err != nil {
		t.Fatal(err)
	}
	st2, _ := os.Stat(path)
	if b.changes != 1 || !st1.ModTime().Equal(st2.ModTime()) {
		t.Fatalf("second write was not a no-op (changes=%d)", b.changes)
	}
	got, err := config.ReadLogtoFile(path)
	if err != nil || *got != lf {
		t.Fatalf("read back %+v, %v", got, err)
	}
}

func TestSMTPConfigNeverLeaksIntoTemplates(t *testing.T) {
	cfg := smtpConfig(&SMTPConfig{Host: "h", Port: 465, User: "u", Password: "p", From: "f@example.org"})
	if cfg["secure"] != true || cfg["requireTLS"] != false {
		t.Errorf("port 465 should use implicit TLS: %v", cfg)
	}
	usages := map[string]bool{}
	for _, tpl := range emailTemplates() {
		usages[tpl.UsageType] = true
		if !strings.Contains(tpl.Content, "{{code}}") {
			t.Errorf("%s template has no code", tpl.UsageType)
		}
	}
	for _, u := range []string{"Register", "SignIn", "ForgotPassword", "Generic"} {
		if !usages[u] {
			t.Errorf("missing required template %s", u)
		}
	}
	if a := smtpConfig(&SMTPConfig{Host: "h", Port: 25, From: "f"})["auth"].(map[string]any); len(a) != 0 {
		t.Errorf("no user should mean no auth, got %v", a)
	}
}
