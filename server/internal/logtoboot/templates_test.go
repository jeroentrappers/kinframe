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

	"github.com/jeroentrappers/kinframe/server/internal/i18n"
)

func TestEmailTextsComplete(t *testing.T) {
	all, err := loadEmailTexts()
	if err != nil {
		t.Fatal(err)
	}
	if err := validateEmailTexts(all); err != nil {
		t.Fatal(err)
	}
	for _, l := range i18n.Languages {
		if _, ok := all[l]; !ok {
			t.Errorf("no emails/%s.json", l)
		}
	}
	for l := range all {
		if !strings.Contains(strings.Join(i18n.Languages, " ")+" ", l+" ") {
			t.Errorf("emails/%s.json is not a supported language", l)
		}
	}
}

func TestEnglishConnectorTemplates(t *testing.T) {
	for _, tpl := range emailTemplates() {
		if tpl.UsageType == "SignIn" {
			want := "Hi,\n\nUse this code to sign in to kinframe:\n\n    {{code}}\n\nThe code expires in 10 minutes. If you didn't ask for it, you can ignore this email.\n\n— kinframe"
			if tpl.Content != want || tpl.Subject != "Your kinframe sign-in code: {{code}}" {
				t.Errorf("SignIn template changed:\n%q", tpl.Content)
			}
		}
	}
}

// fakeTemplateStore mimics Logto's /api/email-templates (GET list, PUT upsert).
func fakeTemplateStore(t *testing.T) (*httptest.Server, *int) {
	var mu sync.Mutex
	store := map[string]localizedEmailTemplate{}
	puts := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		if r.URL.Path != "/api/email-templates" {
			http.NotFound(w, r)
			return
		}
		switch r.Method {
		case http.MethodGet:
			out := []localizedEmailTemplate{}
			for _, v := range store {
				out = append(out, v)
			}
			_ = json.NewEncoder(w).Encode(out)
		case http.MethodPut:
			puts++
			var body struct {
				Templates []localizedEmailTemplate `json:"templates"`
			}
			b, _ := io.ReadAll(r.Body)
			if err := json.Unmarshal(b, &body); err != nil {
				t.Errorf("bad body: %v", err)
			}
			for _, tpl := range body.Templates {
				store[tpl.LanguageTag+"/"+tpl.TemplateType] = tpl
			}
			_ = json.NewEncoder(w).Encode(body.Templates)
		}
	}))
	t.Cleanup(srv.Close)
	return srv, &puts
}

func TestEnsureEmailTemplatesIdempotent(t *testing.T) {
	srv, puts := fakeTemplateStore(t)
	b := &Bootstrapper{log: slog.New(slog.NewTextHandler(io.Discard, nil)), m: &mgmt{hc: srv.Client(), base: srv.URL + "/api", token: "t"}}
	if err := b.ensureEmailTemplates(context.Background()); err != nil {
		t.Fatal(err)
	}
	if *puts != 1 || b.changes != 1 {
		t.Fatalf("first run: puts=%d changes=%d", *puts, b.changes)
	}
	if err := b.ensureEmailTemplates(context.Background()); err != nil {
		t.Fatal(err)
	}
	if *puts != 1 || b.changes != 1 {
		t.Fatalf("second run was not a no-op: puts=%d changes=%d", *puts, b.changes)
	}
	var nl, ptBR bool
	for _, tpl := range localizedEmailTemplates() {
		nl = nl || (tpl.LanguageTag == "nl" && tpl.TemplateType == "SignIn" && strings.Contains(tpl.Details.Subject, "aanmeldcode"))
		ptBR = ptBR || tpl.LanguageTag == "pt-BR"
		if !strings.Contains(tpl.Details.Content, "{{code}}") {
			t.Errorf("%s/%s has no code", tpl.LanguageTag, tpl.TemplateType)
		}
	}
	if !nl {
		t.Error("no Dutch sign-in template")
	}
	if len(i18n.Languages) > 2 && !ptBR {
		t.Error("Portuguese should also be stored as pt-BR")
	}
}

func TestEnsureEmailTemplatesWithoutStore(t *testing.T) {
	srv := httptest.NewServer(http.NotFoundHandler())
	defer srv.Close()
	b := &Bootstrapper{log: slog.New(slog.NewTextHandler(io.Discard, nil)), m: &mgmt{hc: srv.Client(), base: srv.URL + "/api", token: "t"}}
	if err := b.ensureEmailTemplates(context.Background()); err != nil {
		t.Fatalf("an older Logto should only warn: %v", err)
	}
}
