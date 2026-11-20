// Package crash reports panics and errors to a Sentry-compatible server (GlitchTip, docs/spec/crash-reporting.md).
//
// It is off unless KINFRAME_SENTRY_DSN is set: then Init starts the SDK and every helper here reports; otherwise
// they do nothing. Reports never carry personal data (spec §3): no user, IP address, cookies, headers, query
// strings or bodies, and paths have their ids and tokens replaced. scrub enforces that in BeforeSend, so a later
// SDK default can't widen it.
package crash

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync/atomic"
	"time"

	"github.com/getsentry/sentry-go"
)

// Options configure Init.
type Options struct {
	DSN         string
	Environment string
	// Release is the full release name, e.g. kinframe-server@0.3.0+1a2b3c4 (see Release).
	Release string
	// ServerName replaces the host name the SDK would report (a container id).
	ServerName string
}

// flushTimeout bounds how long a crash or shutdown waits for reports to go out.
const flushTimeout = 3 * time.Second

var enabled atomic.Bool

// Release builds the release name `kinframe-server@<version>[+<revision>]` (revision shortened to 7 characters).
func Release(version, revision string) string {
	r := "kinframe-server@" + version
	if revision = strings.TrimSpace(revision); revision != "" {
		r += "+" + revision[:min(7, len(revision))]
	}
	return r
}

// Init starts the SDK when o.DSN is set. The returned function flushes pending reports and stops reporting; call
// it on shutdown. With an empty DSN Init does nothing and returns a no-op.
func Init(o Options) (func(), error) {
	if o.DSN == "" {
		return func() {}, nil
	}
	err := sentry.Init(sentry.ClientOptions{
		Dsn:              o.DSN,
		Environment:      o.Environment,
		Release:          o.Release,
		ServerName:       o.ServerName,
		AttachStacktrace: true,
		SampleRate:       1,
		EnableTracing:    false,
		TracesSampleRate: 0,
		MaxBreadcrumbs:   20,
		// No user, cookies, headers, bodies or query strings (SendDefaultPII off, and stricter); scrub has the last word.
		DataCollection: &sentry.DataCollection{
			UserInfo:    sentry.Set(false),
			Cookies:     &sentry.KeyValueCollectionBehavior{Mode: sentry.CollectionOff},
			HTTPHeaders: &sentry.HeaderCollectionConfig{Request: &sentry.KeyValueCollectionBehavior{Mode: sentry.CollectionOff}, Response: &sentry.KeyValueCollectionBehavior{Mode: sentry.CollectionOff}},
			HTTPBodies:  []sentry.BodyType{},
			QueryParams: &sentry.KeyValueCollectionBehavior{Mode: sentry.CollectionOff},
		},
		BeforeSend:            scrub,
		BeforeBreadcrumb:      func(b *sentry.Breadcrumb, _ *sentry.BreadcrumbHint) *sentry.Breadcrumb { scrubBreadcrumb(b); return b },
		BeforeSendTransaction: func(*sentry.Event, *sentry.EventHint) *sentry.Event { return nil },
	})
	if err != nil {
		return func() {}, fmt.Errorf("crash reporting: %w", err)
	}
	enabled.Store(true)
	return func() {
		sentry.Flush(flushTimeout)
		enabled.Store(false)
		sentry.CurrentHub().BindClient(nil)
	}, nil
}

// Enabled reports whether Init started the SDK.
func Enabled() bool { return enabled.Load() }

// HTTPPanic reports a panic recovered while serving r (the request becomes method, host and scrubbed path only).
func HTTPPanic(r *http.Request, v any) {
	if !Enabled() {
		return
	}
	hub := sentry.CurrentHub().Clone()
	hub.ConfigureScope(func(s *sentry.Scope) {
		s.SetTag("component", "http")
		s.SetTag("http.method", r.Method)
		s.SetTag("http.route", RedactPath(r.URL.Path))
		s.AddEventProcessor(func(e *sentry.Event, _ *sentry.EventHint) *sentry.Event {
			e.Request = &sentry.Request{Method: r.Method, URL: requestURL(r)}
			return e
		})
	})
	hub.RecoverWithContext(context.Background(), v)
}

// Report reports a panic that the caller recovered and handles itself (e.g. a parser run in its own goroutine).
func Report(component string, v any) {
	if !Enabled() {
		return
	}
	hub := sentry.CurrentHub().Clone()
	hub.ConfigureScope(func(s *sentry.Scope) { s.SetTag("component", component) })
	hub.RecoverWithContext(context.Background(), v)
}

// Recover is deferred at the top of a background goroutine: a panic is reported, flushed and then re-raised, so
// the process still crashes (and is restarted) as it did before.
//
//	go func() { defer crash.Recover("worker"); w.Run(ctx) }()
func Recover(component string) {
	v := recover()
	if v == nil {
		return
	}
	if Enabled() {
		Report(component, v)
		sentry.Flush(flushTimeout)
	}
	panic(v)
}

// requestURL is scheme://host/path with ids redacted, no query string or fragment.
func requestURL(r *http.Request) string {
	scheme := "http"
	if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
		scheme = "https"
	}
	return scheme + "://" + r.Host + RedactPath(r.URL.Path)
}

var (
	uuidRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
	// routeSegRe matches the path segments routes are made of ("frames", "feed-invites", ".well-known",
	// "email:check", "reservations.csv", "updateweatherstation.php"): lower-case words. Ids, invite and TV tokens,
	// signed media keys and file names have digits, capitals, other characters or another extension and become "*".
	routeSegRe = regexp.MustCompile(`^(v[0-9]{1,2}|\.?[a-z][a-z_:-]{0,39}|[a-z][a-z_-]{0,39}\.(csv|php))$`)
)

// RedactPath keeps a path's route words and replaces every other segment (ids, tokens, file names) with "*":
// /v1/invites/Xy7…/accept → /v1/invites/*/accept. Everything after /signed/ is a media key and goes entirely.
func RedactPath(p string) string {
	segs := strings.Split(p, "/")
	key := false
	for i, s := range segs {
		if s == "" {
			continue
		}
		if !key && routeSegRe.MatchString(s) && !uuidRe.MatchString(s) {
			key = s == "signed"
			continue
		}
		segs[i] = "*"
	}
	return strings.Join(segs, "/")
}

// redactURL keeps scheme, host and the redacted path of a URL; it drops user info, query and fragment.
func redactURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	if u.Scheme == "" || u.Host == "" {
		return RedactPath(u.Path)
	}
	return u.Scheme + "://" + u.Host + RedactPath(u.Path)
}

// scrub is the BeforeSend hook: whatever the SDK or a caller attached, the event leaves without personal data.
func scrub(e *sentry.Event, _ *sentry.EventHint) *sentry.Event {
	e.User = sentry.User{}
	if e.Request != nil {
		e.Request = &sentry.Request{Method: e.Request.Method, URL: redactURL(e.Request.URL)}
	}
	for _, b := range e.Breadcrumbs {
		scrubBreadcrumb(b)
	}
	e.Attachments = nil
	return e
}

// scrubBreadcrumb keeps an HTTP breadcrumb's method, redacted URL and status; other data is dropped.
func scrubBreadcrumb(b *sentry.Breadcrumb) {
	if b == nil || len(b.Data) == 0 {
		return
	}
	keep := map[string]any{}
	for k, v := range b.Data {
		switch k {
		case "method", "status_code":
			keep[k] = v
		case "url":
			if s, ok := v.(string); ok {
				keep[k] = redactURL(s)
			}
		}
	}
	b.Data = keep
}
