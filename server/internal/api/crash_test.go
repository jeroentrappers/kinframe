package api

import (
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/getsentry/sentry-go"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/crash"
	"github.com/jeroentrappers/kinframe/server/internal/crash/crashtest"
)

// A handler panic answers 500 problem+json and is reported once, with the request reduced to method, host and
// redacted path (docs/spec/crash-reporting.md §3).
func TestRecovererReportsPanic(t *testing.T) {
	in := crashtest.New(t)
	stop, err := crash.Init(crash.Options{DSN: in.DSN(), Environment: "production", Release: crash.Release("0.9.0", "")})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(stop)

	s := &Server{cfg: &config.Config{TrustProxy: true}, log: slog.New(slog.DiscardHandler)}
	h := s.recoverer(s.realIP(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		panic(errors.New("assignment to entry in nil map"))
	})))
	r := httptest.NewRequest("PATCH", "http://kinframe.test/v1/frames/0b9e4f3c-6f0e-4c4e-9a59-8e3d4a1f2b7c?name=Oma%27s+frame",
		strings.NewReader(`{"name":"Oma's frame"}`))
	r.Header.Set("Authorization", "Bearer secret-access-token")
	r.Header.Set("Cookie", "logto=session")
	r.Header.Set("X-Forwarded-For", "198.51.100.23")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusInternalServerError || !strings.Contains(w.Header().Get("Content-Type"), "problem+json") {
		t.Fatalf("status %d %q", w.Code, w.Header().Get("Content-Type"))
	}
	sentry.Flush(3 * time.Second)
	evs := in.Wait(t, 1)
	if len(evs) != 1 {
		t.Fatalf("%d reports, want 1", len(evs))
	}
	ev := evs[0]
	if ev["release"] != "kinframe-server@0.9.0" || ev["level"] != "fatal" {
		t.Errorf("release %v level %v", ev["release"], ev["level"])
	}
	req := ev["request"].(map[string]any)
	if len(req) != 2 || req["method"] != "PATCH" || req["url"] != "http://kinframe.test/v1/frames/*" {
		t.Errorf("request = %v", req)
	}
	exc := ev["exception"].([]any)
	if v := exc[len(exc)-1].(map[string]any)["value"]; !strings.Contains(v.(string), "nil map") {
		t.Errorf("exception = %v", v)
	}
	raw := in.Raw()
	for _, leak := range []string{"Oma", "secret-access-token", "logto=session", "198.51.100.23", "0b9e4f3c"} {
		if strings.Contains(raw, leak) {
			t.Errorf("report contains %q", leak)
		}
	}
}
