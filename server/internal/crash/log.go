package crash

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/getsentry/sentry-go"
)

// logEvery is how often the same error log message is reported at most (per process).
const logEvery = time.Hour

// LogHandler wraps h so that records at error level are also reported, at most once an hour per message.
// The report carries only the message (a constant in the code), the component, the method and redacted path of a
// failed request and the Go type of the error, never attribute values, which can hold names, addresses or file
// names. Records with a "panic" attribute are skipped: the panic is reported on its own, with its stack.
func LogHandler(h slog.Handler) slog.Handler {
	return &logHandler{Handler: h, seen: &seenMessages{last: map[string]time.Time{}}}
}

type seenMessages struct {
	mu   sync.Mutex
	last map[string]time.Time
}

// first reports whether msg was not reported within logEvery, and marks it reported.
func (s *seenMessages) first(msg string, now time.Time) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if t, ok := s.last[msg]; ok && now.Sub(t) < logEvery {
		return false
	}
	if len(s.last) > 1000 {
		clear(s.last)
	}
	s.last[msg] = now
	return true
}

type logHandler struct {
	slog.Handler
	seen      *seenMessages
	component string
}

func (h *logHandler) Enabled(ctx context.Context, l slog.Level) bool {
	return (l >= slog.LevelError && Enabled()) || h.Handler.Enabled(ctx, l)
}

func (h *logHandler) Handle(ctx context.Context, r slog.Record) error {
	if r.Level >= slog.LevelError && Enabled() {
		h.report(r)
	}
	if !h.Handler.Enabled(ctx, r.Level) {
		return nil
	}
	return h.Handler.Handle(ctx, r)
}

func (h *logHandler) report(r slog.Record) {
	tags := map[string]string{}
	if h.component != "" {
		tags["component"] = h.component
	}
	skip := false
	r.Attrs(func(a slog.Attr) bool {
		switch a.Key {
		case "panic":
			skip = true
			return false
		case "component":
			tags["component"] = a.Value.String()
		case "method":
			tags["http.method"] = a.Value.String()
		case "path":
			tags["http.route"] = RedactPath(a.Value.String())
		case "err", "error":
			if err, ok := a.Value.Any().(error); ok {
				tags["error.type"] = fmt.Sprintf("%T", err)
			}
		}
		return true
	})
	if skip || !h.seen.first(r.Message, r.Time) {
		return
	}
	hub := sentry.CurrentHub().Clone()
	hub.WithScope(func(s *sentry.Scope) {
		s.SetLevel(sentry.LevelError)
		s.SetTags(tags)
		s.SetTag("source", "log")
		hub.CaptureMessage(r.Message)
	})
}

func (h *logHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	c := *h
	c.Handler = h.Handler.WithAttrs(attrs)
	for _, a := range attrs {
		if a.Key == "component" {
			c.component = a.Value.String()
		}
	}
	return &c
}

func (h *logHandler) WithGroup(name string) slog.Handler {
	c := *h
	c.Handler = h.Handler.WithGroup(name)
	return &c
}
