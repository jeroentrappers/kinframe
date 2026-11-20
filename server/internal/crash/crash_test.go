package crash_test

import (
	"errors"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/getsentry/sentry-go"

	"github.com/jeroentrappers/kinframe/server/internal/crash"
	"github.com/jeroentrappers/kinframe/server/internal/crash/crashtest"
)

const crashFlush = 3 * time.Second

func start(t *testing.T) *crashtest.Ingest {
	t.Helper()
	in := crashtest.New(t)
	stop, err := crash.Init(crash.Options{DSN: in.DSN(), Environment: "production", Release: crash.Release("1.2.3", "abcdef0123456789"), ServerName: "test"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(stop)
	return in
}

func TestOffWithoutDSN(t *testing.T) {
	stop, err := crash.Init(crash.Options{})
	if err != nil {
		t.Fatal(err)
	}
	defer stop()
	if crash.Enabled() {
		t.Fatal("enabled without a DSN")
	}
	crash.HTTPPanic(httptest.NewRequest("GET", "/x", nil), "boom") // no-op, no panic
}

func TestRelease(t *testing.T) {
	if got := crash.Release("0.3.0", ""); got != "kinframe-server@0.3.0" {
		t.Errorf("got %q", got)
	}
	if got := crash.Release("main", "1a2b3c4d5e6f"); got != "kinframe-server@main+1a2b3c4" {
		t.Errorf("got %q", got)
	}
}

func TestRedactPath(t *testing.T) {
	for in, want := range map[string]string{
		"/v1/frames/0b9e4f3c-6f0e-4c4e-9a59-8e3d4a1f2b7c/items": "/v1/frames/*/items",
		"/v1/invites/Xy7Qp0aZ_k3/accept":                        "/v1/invites/*/accept",
		"/v1/media/signed/items/ab/photo.jpg":                   "/v1/media/signed/*/*/*",
		"/v1/media/signed/ab12/IMG_2041.HEIC":                   "/v1/media/signed/*/*",
		"/v1/operator/reservations.csv":                         "/v1/operator/reservations.csv",
		"/v1/inbound/email:check":                               "/v1/inbound/email:check",
		"/weatherstation/updateweatherstation.php":              "/weatherstation/updateweatherstation.php",
		"/.well-known/kinframe":                                 "/.well-known/kinframe",
		"/v1/device/update/apk/1042":                            "/v1/device/update/apk/*",
		"/":                                                     "/",
		"/avatars/9f1c2d3e-0000-4000-8000-000000000001/a.webp":  "/avatars/*/*",
	} {
		if got := crash.RedactPath(in); got != want {
			t.Errorf("RedactPath(%q) = %q, want %q", in, got, want)
		}
	}
}

// A panic in a request is reported with the release, the stack and only method, host and redacted path of the
// request: no user, IP, cookies, headers, query string or body.
func TestHTTPPanicIsScrubbed(t *testing.T) {
	in := start(t)
	r := httptest.NewRequest("POST", "https://kinframe.example/v1/invites/SeCrEtToKeN123/accept?token=hunter2&name=alice.jpg", strings.NewReader(`{"email":"alice@example.com"}`))
	r.Header.Set("Authorization", "Bearer eyJsecret")
	r.Header.Set("Cookie", "session=abc")
	r.Header.Set("X-Forwarded-For", "203.0.113.7")
	r.RemoteAddr = "203.0.113.7:4711"
	// Even data set on the scope on purpose is removed by BeforeSend.
	sentry.ConfigureScope(func(s *sentry.Scope) {
		s.SetUser(sentry.User{ID: "user-1", Email: "alice@example.com", IPAddress: "203.0.113.7"})
	})
	t.Cleanup(func() { sentry.ConfigureScope(func(s *sentry.Scope) { s.SetUser(sentry.User{}) }) })
	crash.HTTPPanic(r, errors.New("nil map write in accept"))
	sentry.Flush(crashFlush)
	ev := in.Wait(t, 1)[0]

	if ev["release"] != "kinframe-server@1.2.3+abcdef0" || ev["environment"] != "production" || ev["server_name"] != "test" {
		t.Errorf("release/env/server = %v %v %v", ev["release"], ev["environment"], ev["server_name"])
	}
	req, _ := ev["request"].(map[string]any)
	if req["method"] != "POST" || req["url"] != "https://kinframe.example/v1/invites/*/accept" {
		t.Errorf("request = %v", req)
	}
	for _, k := range []string{"headers", "cookies", "query_string", "data", "env"} {
		if _, ok := req[k]; ok {
			t.Errorf("request has %s: %v", k, req[k])
		}
	}
	if u, ok := ev["user"]; ok && len(u.(map[string]any)) > 0 {
		t.Errorf("user = %v", u)
	}
	exc, _ := ev["exception"].([]any)
	if len(exc) == 0 {
		t.Fatalf("no exception: %v", ev["exception"])
	}
	last := exc[len(exc)-1].(map[string]any)
	if last["value"] != "nil map write in accept" {
		t.Errorf("exception = %v", last)
	}
	if st, _ := last["stacktrace"].(map[string]any); st == nil || len(st["frames"].([]any)) == 0 {
		t.Error("no stack trace")
	}
	raw := in.Raw()
	for _, leak := range []string{"hunter2", "alice", "eyJsecret", "session=abc", "203.0.113.7", "SeCrEtToKeN123", "user-1"} {
		if strings.Contains(raw, leak) {
			t.Errorf("envelope contains %q", leak)
		}
	}
}

func TestRecoverReportsAndRepanics(t *testing.T) {
	in := start(t)
	func() {
		defer func() {
			if v := recover(); v != "worker exploded" {
				t.Errorf("re-panicked with %v", v)
			}
		}()
		defer crash.Recover("worker")
		panic("worker exploded")
	}()
	ev := in.Wait(t, 1)[0]
	if tags, _ := ev["tags"].(map[string]any); tags["component"] != "worker" {
		t.Errorf("tags = %v", ev["tags"])
	}
}

// Error logs are reported once per message per hour, without attribute values; panics logged elsewhere are not
// reported twice.
func TestLogHandler(t *testing.T) {
	in := start(t)
	log := slog.New(crash.LogHandler(slog.DiscardHandler)).With("component", "calendar")
	log.Error("calendar sync", "url", "https://alice:pw@cal.example/secret.ics", "err", errors.New("401 for alice@example.com"))
	log.Error("calendar sync", "err", errors.New("again"))
	log.Error("panic", "panic", "x")
	log.Warn("just a warning")
	sentry.Flush(crashFlush)
	evs := in.Wait(t, 1)
	if len(evs) != 1 {
		t.Fatalf("%d events, want 1", len(evs))
	}
	ev := evs[0]
	if ev["message"] != "calendar sync" || ev["level"] != "error" {
		t.Errorf("event = %v %v", ev["message"], ev["level"])
	}
	tags := ev["tags"].(map[string]any)
	if tags["component"] != "calendar" || tags["error.type"] != "*errors.errorString" {
		t.Errorf("tags = %v", tags)
	}
	raw := in.Raw()
	for _, leak := range []string{"alice", "secret.ics", "401 for"} {
		if strings.Contains(raw, leak) {
			t.Errorf("envelope contains %q", leak)
		}
	}
}
