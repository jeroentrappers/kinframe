package auth

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestLogtoAccountsDelete(t *testing.T) {
	var tokens int
	var deleted []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "auth.example.org" || r.Header.Get("X-Forwarded-Proto") != "https" || r.Header.Get("X-Forwarded-Host") != "auth.example.org" {
			http.Error(w, "wrong tenant", http.StatusNotFound)
			return
		}
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/oidc/token":
			id, secret, _ := r.BasicAuth()
			_ = r.ParseForm()
			if id != "m2m" || secret != "s3cret" || r.Form.Get("resource") != "https://default.logto.app/api" || r.Form.Get("scope") != "all" {
				http.Error(w, `{"error":"invalid_client"}`, http.StatusUnauthorized)
				return
			}
			tokens++
			_, _ = w.Write([]byte(`{"access_token":"mgmt-token","expires_in":3600}`))
		case r.Method == http.MethodDelete && strings.HasPrefix(r.URL.Path, "/api/users/"):
			if r.Header.Get("Authorization") != "Bearer mgmt-token" {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			id := strings.TrimPrefix(r.URL.Path, "/api/users/")
			switch id {
			case "gone":
				w.WriteHeader(http.StatusNotFound)
			case "broken":
				w.WriteHeader(http.StatusInternalServerError)
			default:
				deleted = append(deleted, id)
				w.WriteHeader(http.StatusNoContent)
			}
		default:
			w.WriteHeader(http.StatusTeapot)
		}
	}))
	defer srv.Close()

	l := &LogtoAccounts{Endpoint: "https://auth.example.org", URL: srv.URL, ID: "m2m", Secret: "s3cret", HTTP: srv.Client()}
	ctx := context.Background()
	if err := l.Delete(ctx, "u1"); err != nil {
		t.Fatal(err)
	}
	if err := l.Delete(ctx, "gone"); err != nil {
		t.Fatalf("already deleted user: %v", err)
	}
	if err := l.Delete(ctx, "broken"); err == nil {
		t.Fatal("want an error for a 500")
	}
	if len(deleted) != 1 || deleted[0] != "u1" || tokens != 1 {
		t.Fatalf("deleted %v with %d tokens, want [u1] with 1 (cached)", deleted, tokens)
	}

	bad := &LogtoAccounts{Endpoint: "https://auth.example.org", URL: srv.URL, ID: "m2m", Secret: "wrong", HTTP: srv.Client()}
	if err := bad.Delete(ctx, "u2"); err == nil || strings.Contains(err.Error(), "wrong") {
		t.Fatalf("bad credentials: %v", err)
	}
}
