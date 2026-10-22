// Package calendartest is a fake calendar server for tests: ICS links with
// ETag support, and a small CalDAV server (principal → home set → calendars
// → calendar-query REPORT) with basic auth.
package calendartest

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"html"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
)

// CalDAV account used by the fake.
const (
	User     = "alice"
	Password = "app-pass-123"
)

// Fake serves /ics/<name> and a CalDAV tree under /dav/.
type Fake struct {
	*httptest.Server

	mu   sync.Mutex
	ics  map[string]string            // name -> document
	cals map[string]map[string]string // calendar -> object name -> VCALENDAR
	fail bool

	ICSRequests   atomic.Int64
	NotModified   atomic.Int64
	Reports       atomic.Int64
	LastTimeRange atomic.Value // string "start end" of the last REPORT
}

// New starts a fake with two CalDAV calendars, "family" and "work", both empty.
func New() *Fake {
	f := &Fake{ics: map[string]string{}, cals: map[string]map[string]string{"family": {}, "work": {}}}
	f.Server = httptest.NewServer(http.HandlerFunc(f.serve))
	return f
}

// SetICS sets the document served at /ics/<name>.
func (f *Fake) SetICS(name, doc string) {
	f.mu.Lock()
	f.ics[name] = doc
	f.mu.Unlock()
}

// ICSURL is the link for an ICS document.
func (f *Fake) ICSURL(name string) string { return f.URL + "/ics/" + name }

// PutEvent stores a calendar object (a full VCALENDAR) in a CalDAV calendar.
func (f *Fake) PutEvent(calendar, name, doc string) {
	f.mu.Lock()
	f.cals[calendar][name] = doc
	f.mu.Unlock()
}

// CalendarURL is a CalDAV calendar's collection URL.
func (f *Fake) CalendarURL(calendar string) string {
	return f.URL + "/dav/calendars/alice/" + calendar + "/"
}

// SetFail makes every request answer 500.
func (f *Fake) SetFail(v bool) {
	f.mu.Lock()
	f.fail = v
	f.mu.Unlock()
}

func etagOf(s string) string {
	sum := sha256.Sum256([]byte(s))
	return `"` + hex.EncodeToString(sum[:8]) + `"`
}

func (f *Fake) serve(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	fail := f.fail
	f.mu.Unlock()
	if fail {
		http.Error(w, "boom", http.StatusInternalServerError)
		return
	}
	switch {
	case strings.HasPrefix(r.URL.Path, "/ics/"):
		f.serveICS(w, r)
	case r.URL.Path == "/.well-known/caldav":
		http.Redirect(w, r, "/dav/", http.StatusMovedPermanently)
	case r.URL.Path == "/dav" || strings.HasPrefix(r.URL.Path, "/dav/"):
		u, p, ok := r.BasicAuth()
		if !ok || u != User || p != Password {
			w.Header().Set("WWW-Authenticate", `Basic realm="fake"`)
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		f.serveDAV(w, r)
	default:
		http.NotFound(w, r)
	}
}

func (f *Fake) serveICS(w http.ResponseWriter, r *http.Request) {
	f.ICSRequests.Add(1)
	name := strings.TrimPrefix(r.URL.Path, "/ics/")
	f.mu.Lock()
	doc, ok := f.ics[name]
	f.mu.Unlock()
	if !ok {
		http.NotFound(w, r)
		return
	}
	if name == "private.ics" {
		if u, p, ok := r.BasicAuth(); !ok || u != User || p != Password {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
	}
	tag := etagOf(doc)
	if r.Header.Get("If-None-Match") == tag {
		f.NotModified.Add(1)
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("ETag", tag)
	w.Header().Set("Content-Type", "text/calendar; charset=utf-8")
	_, _ = io.WriteString(w, doc)
}

const msHead = `<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">`

func (f *Fake) serveDAV(w http.ResponseWriter, r *http.Request) {
	p := r.URL.Path
	if !strings.HasSuffix(p, "/") && !strings.HasSuffix(p, ".ics") {
		p += "/" // collections, with or without the trailing slash
	}
	w.Header().Set("Content-Type", "application/xml; charset=utf-8")
	switch r.Method {
	case "PROPFIND":
		_, _ = io.ReadAll(r.Body)
		var b strings.Builder
		b.WriteString(msHead)
		switch {
		case p == "/dav/" || p == "/dav/principals/alice/":
			fmt.Fprintf(&b, `<d:response><d:href>%s</d:href><d:propstat><d:prop>
<d:current-user-principal><d:href>/dav/principals/alice/</d:href></d:current-user-principal>
<c:calendar-home-set><d:href>/dav/calendars/alice/</d:href></c:calendar-home-set>
<d:resourcetype><d:collection/></d:resourcetype>
</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`, p)
		case p == "/dav/calendars/alice/":
			fmt.Fprintf(&b, `<d:response><d:href>/dav/calendars/alice/</d:href><d:propstat><d:prop>
<d:current-user-principal><d:href>/dav/principals/alice/</d:href></d:current-user-principal>
<d:resourcetype><d:collection/></d:resourcetype><d:displayname>alice</d:displayname>
</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`)
			if r.Header.Get("Depth") == "1" {
				for _, c := range []struct{ id, name, comp string }{{"family", "Family", "VEVENT"}, {"work", "Work &amp; school", "VEVENT"}, {"tasks", "Tasks", "VTODO"}} {
					fmt.Fprintf(&b, `<d:response><d:href>/dav/calendars/alice/%s/</d:href><d:propstat><d:prop>
<d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>%s</d:displayname>
<c:calendar-description></c:calendar-description><c:max-resource-size>10000000</c:max-resource-size>
<c:supported-calendar-component-set><c:comp name="%s"/></c:supported-calendar-component-set>
</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`, c.id, c.name, c.comp)
				}
			}
		case strings.HasPrefix(p, "/dav/calendars/alice/"):
			fmt.Fprintf(&b, `<d:response><d:href>%s</d:href><d:propstat><d:prop>
<d:current-user-principal><d:href>/dav/principals/alice/</d:href></d:current-user-principal>
<d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`, p)
		default:
			http.NotFound(w, r)
			return
		}
		b.WriteString(`</d:multistatus>`)
		w.WriteHeader(http.StatusMultiStatus)
		_, _ = io.WriteString(w, b.String())
	case "REPORT":
		f.Reports.Add(1)
		body, _ := io.ReadAll(r.Body)
		if i := strings.Index(string(body), "time-range"); i >= 0 {
			f.LastTimeRange.Store(string(body[i:min(len(body), i+80)]))
		}
		cal := strings.Trim(strings.TrimPrefix(p, "/dav/calendars/alice/"), "/")
		f.mu.Lock()
		objs, ok := f.cals[cal]
		var b strings.Builder
		b.WriteString(msHead)
		for name, doc := range objs {
			fmt.Fprintf(&b, `<d:response><d:href>/dav/calendars/alice/%s/%s.ics</d:href><d:propstat><d:prop>
<d:getetag>%s</d:getetag><c:calendar-data>%s</c:calendar-data>
</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`, cal, name, html.EscapeString(etagOf(doc)), html.EscapeString(doc))
		}
		f.mu.Unlock()
		if !ok {
			http.NotFound(w, r)
			return
		}
		b.WriteString(`</d:multistatus>`)
		w.WriteHeader(http.StatusMultiStatus)
		_, _ = io.WriteString(w, b.String())
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}
