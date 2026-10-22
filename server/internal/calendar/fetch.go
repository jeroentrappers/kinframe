package calendar

import (
	"bytes"
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"path"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/emersion/go-webdav"
	"github.com/emersion/go-webdav/caldav"
)

// SyncError is a failed fetch: Auth means credentials were rejected.
type SyncError struct {
	Auth bool
	Msg  string
}

func (e *SyncError) Error() string { return e.Msg }

func failf(format string, a ...any) error { return &SyncError{Msg: fmt.Sprintf(format, a...)} }
func authf(format string, a ...any) error {
	return &SyncError{Auth: true, Msg: fmt.Sprintf(format, a...)}
}

// IsAuth reports whether err is an authentication failure.
func IsAuth(err error) bool {
	var se *SyncError
	return errors.As(err, &se) && se.Auth
}

// NormalizeURL checks a user-entered calendar URL: webcal(s):// becomes
// https://, only http(s) with a host is accepted.
func NormalizeURL(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	lower := strings.ToLower(raw)
	switch {
	case strings.HasPrefix(lower, "webcals://"):
		raw = "https://" + raw[len("webcals://"):]
	case strings.HasPrefix(lower, "webcal://"):
		raw = "https://" + raw[len("webcal://"):]
	}
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
		return "", errors.New("must be an https://, http:// or webcal:// link")
	}
	if u.User != nil {
		return "", errors.New("put the username and password in their own fields, not in the link")
	}
	u.Fragment = ""
	return u.String(), nil
}

// RedactURL keeps the scheme, host and last path segment, e.g.
// https://calendar.google.com/…/basic.ics, so secret tokens are not shown.
func RedactURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return ""
	}
	out := u.Scheme + "://" + u.Host
	p := strings.Trim(u.Path, "/")
	if p == "" {
		return out
	}
	segs := strings.Split(p, "/")
	last := segs[len(segs)-1]
	if len([]rune(last)) > 24 {
		last = string([]rune(last)[:20]) + "…"
	}
	if len(segs) > 1 || u.RawQuery != "" {
		return out + "/…/" + last
	}
	return out + "/" + last
}

// Fetcher downloads calendars.
type Fetcher struct {
	HTTP      *http.Client
	MaxBytes  int64 // per response (default 10 MiB)
	UserAgent string
}

func (f *Fetcher) client() *http.Client {
	if f.HTTP != nil {
		return f.HTTP
	}
	return &http.Client{Timeout: 30 * time.Second}
}

func (f *Fetcher) maxBytes() int64 {
	if f.MaxBytes > 0 {
		return f.MaxBytes
	}
	return 10 << 20
}

// FetchResult is a fetched ICS document; NotModified means the cached copy is current.
type FetchResult struct {
	Raw          []byte
	NotModified  bool
	ETag         string
	LastModified string
}

func netError(err error) error {
	var dnsErr *net.DNSError
	switch {
	case errors.As(err, &dnsErr):
		return failf("server not found (%s)", dnsErr.Name)
	case errors.Is(err, context.DeadlineExceeded) || strings.Contains(err.Error(), "Client.Timeout"):
		return failf("the server did not answer in time")
	}
	var uerr *url.Error
	if errors.As(err, &uerr) {
		return failf("could not connect: %v", uerr.Err)
	}
	return failf("could not connect: %v", err)
}

func statusError(code int) error {
	switch {
	case code == http.StatusUnauthorized || code == http.StatusForbidden:
		return authf("the server refused access (%d): check the link or the password", code)
	case code == http.StatusNotFound || code == http.StatusGone:
		return failf("calendar not found (%d): the link may have been reset", code)
	default:
		return failf("the server answered %d %s", code, http.StatusText(code))
	}
}

// FetchICS downloads an ICS link with conditional request headers.
func (f *Fetcher) FetchICS(ctx context.Context, rawURL, etag, lastModified, username, password string) (*FetchResult, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, failf("invalid link")
	}
	req.Header.Set("Accept", "text/calendar, application/calendar+json;q=0.1, */*;q=0.5")
	if f.UserAgent != "" {
		req.Header.Set("User-Agent", f.UserAgent)
	}
	if etag != "" {
		req.Header.Set("If-None-Match", etag)
	}
	if lastModified != "" {
		req.Header.Set("If-Modified-Since", lastModified)
	}
	if username != "" || password != "" {
		req.SetBasicAuth(username, password)
	}
	resp, err := f.client().Do(req)
	if err != nil {
		return nil, netError(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotModified {
		return &FetchResult{NotModified: true, ETag: etag, LastModified: lastModified}, nil
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, statusError(resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, f.maxBytes()+1))
	if err != nil {
		return nil, netError(err)
	}
	if int64(len(body)) > f.maxBytes() {
		return nil, failf("the calendar is larger than %d MB", f.maxBytes()>>20)
	}
	return &FetchResult{Raw: body, ETag: resp.Header.Get("ETag"), LastModified: resp.Header.Get("Last-Modified")}, nil
}

// ---------------------------------------------------------------- CalDAV

// CalDAVCalendar is a calendar found on a CalDAV account.
type CalDAVCalendar struct {
	URL   string
	Name  string
	Color *string
}

// statusClient wraps the HTTP client: basic auth, a size limit, and it
// remembers whether any response was 401/403 so errors can be classified.
type statusClient struct {
	c          *http.Client
	user, pass string
	ua         string
	mu         sync.Mutex
	denied     int
}

func (s *statusClient) Do(req *http.Request) (*http.Response, error) {
	if s.user != "" || s.pass != "" {
		req.SetBasicAuth(s.user, s.pass)
	}
	if s.ua != "" {
		req.Header.Set("User-Agent", s.ua)
	}
	resp, err := s.c.Do(req)
	if err == nil && (resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden) {
		s.mu.Lock()
		s.denied = resp.StatusCode
		s.mu.Unlock()
	}
	return resp, err
}

func (s *statusClient) classify(err error) error {
	if err == nil {
		return nil
	}
	var se *SyncError
	if errors.As(err, &se) {
		return err
	}
	s.mu.Lock()
	denied := s.denied
	s.mu.Unlock()
	if denied != 0 {
		return statusError(denied)
	}
	var uerr *url.Error
	if errors.As(err, &uerr) {
		return netError(err)
	}
	return failf("CalDAV: %v", err)
}

func (f *Fetcher) davClient(username, password string) *statusClient {
	return &statusClient{c: f.client(), user: username, pass: password, ua: f.UserAgent}
}

// wellKnown resolves /.well-known/caldav for a URL without a path.
func (f *Fetcher) wellKnown(ctx context.Context, sc *statusClient, base *url.URL) *url.URL {
	if p := strings.Trim(base.Path, "/"); p != "" {
		return base
	}
	wk := *base
	wk.Path = "/.well-known/caldav"
	noRedirect := *sc.c
	noRedirect.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	req, err := http.NewRequestWithContext(ctx, "PROPFIND", wk.String(), nil)
	if err != nil {
		return base
	}
	req.Header.Set("Depth", "0")
	if sc.user != "" || sc.pass != "" {
		req.SetBasicAuth(sc.user, sc.pass)
	}
	resp, err := noRedirect.Do(req)
	if err != nil {
		return base
	}
	resp.Body.Close()
	if resp.StatusCode >= 300 && resp.StatusCode < 400 {
		if loc, err := resp.Request.URL.Parse(resp.Header.Get("Location")); err == nil {
			return loc
		}
	}
	if resp.StatusCode == 207 {
		return &wk
	}
	return base
}

// CalDAVCalendars discovers the calendars (holding events) of an account.
// selected is the calendar the URL itself points to, if any.
func (f *Fetcher) CalDAVCalendars(ctx context.Context, rawURL, username, password string) (cals []CalDAVCalendar, selected string, err error) {
	base, err := url.Parse(rawURL)
	if err != nil {
		return nil, "", failf("invalid link")
	}
	sc := f.davClient(username, password)
	endpoint := f.wellKnown(ctx, sc, base)
	client, err := caldav.NewClient(sc, endpoint.String())
	if err != nil {
		return nil, "", failf("invalid link")
	}
	principal, err := client.FindCurrentUserPrincipal(ctx)
	if err != nil {
		// Not every server answers this on every URL: maybe the URL is a calendar.
		if cerr := sc.classify(err); IsAuth(cerr) {
			return nil, "", cerr
		}
		if _, qerr := f.FetchCalDAV(ctx, rawURL, username, password, time.Now(), time.Now().Add(24*time.Hour)); qerr == nil {
			return []CalDAVCalendar{{URL: rawURL, Name: lastSegment(base.Path)}}, rawURL, nil
		}
		return nil, "", sc.classify(err)
	}
	home, err := client.FindCalendarHomeSet(ctx, principal)
	if err != nil {
		return nil, "", sc.classify(err)
	}
	found, err := client.FindCalendars(ctx, home)
	if err != nil {
		return nil, "", sc.classify(err)
	}
	for _, c := range found {
		if len(c.SupportedComponentSet) > 0 && !containsFold(c.SupportedComponentSet, "VEVENT") {
			continue
		}
		u, err := endpoint.Parse(c.Path)
		if err != nil {
			continue
		}
		name := strings.TrimSpace(c.Name)
		if name == "" {
			name = lastSegment(c.Path)
		}
		cals = append(cals, CalDAVCalendar{URL: u.String(), Name: name})
		if strings.TrimRight(u.Path, "/") == strings.TrimRight(base.Path, "/") {
			selected = u.String()
		}
	}
	sort.SliceStable(cals, func(i, j int) bool { return strings.ToLower(cals[i].Name) < strings.ToLower(cals[j].Name) })
	if len(cals) == 0 {
		return nil, "", failf("no calendars with events found on this account")
	}
	return cals, selected, nil
}

func containsFold(list []string, s string) bool {
	for _, v := range list {
		if strings.EqualFold(v, s) {
			return true
		}
	}
	return false
}

func lastSegment(p string) string {
	p = strings.Trim(p, "/")
	if p == "" {
		return "Calendar"
	}
	s, err := url.PathUnescape(path.Base(p))
	if err != nil {
		return path.Base(p)
	}
	return s
}

type multistatus struct {
	Responses []struct {
		Href      string `xml:"DAV: href"`
		Propstats []struct {
			Status string `xml:"DAV: status"`
			Prop   struct {
				Data string `xml:"urn:ietf:params:xml:ns:caldav calendar-data"`
			} `xml:"DAV: prop"`
		} `xml:"DAV: propstat"`
	} `xml:"DAV: response"`
}

// FetchCalDAV runs a calendar-query REPORT for events overlapping [from, to)
// and returns the calendar objects concatenated as one ICS document.
func (f *Fetcher) FetchCalDAV(ctx context.Context, collectionURL, username, password string, from, to time.Time) ([]byte, error) {
	body := fmt.Sprintf(`<?xml version="1.0" encoding="utf-8"?>
<C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:prop><D:getetag/><C:calendar-data/></D:prop>
  <C:filter><C:comp-filter name="VCALENDAR"><C:comp-filter name="VEVENT">
    <C:time-range start="%s" end="%s"/>
  </C:comp-filter></C:comp-filter></C:filter>
</C:calendar-query>`, from.UTC().Format("20060102T150405Z"), to.UTC().Format("20060102T150405Z"))
	req, err := http.NewRequestWithContext(ctx, "REPORT", collectionURL, strings.NewReader(body))
	if err != nil {
		return nil, failf("invalid link")
	}
	req.Header.Set("Content-Type", "application/xml; charset=utf-8")
	req.Header.Set("Depth", "1")
	sc := f.davClient(username, password)
	resp, err := sc.Do(req)
	if err != nil {
		return nil, netError(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusMultiStatus {
		return nil, statusError(resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, f.maxBytes()*2+1))
	if err != nil {
		return nil, netError(err)
	}
	if int64(len(data)) > f.maxBytes()*2 {
		return nil, failf("the calendar is larger than %d MB", f.maxBytes()>>20)
	}
	var ms multistatus
	if err := xml.Unmarshal(data, &ms); err != nil {
		return nil, failf("the server's answer is not valid CalDAV")
	}
	var out bytes.Buffer
	for _, r := range ms.Responses {
		for _, ps := range r.Propstats {
			if ps.Status != "" && !strings.Contains(ps.Status, " 200") {
				continue
			}
			d := strings.TrimSpace(ps.Prop.Data)
			if d == "" {
				continue
			}
			out.WriteString(d)
			out.WriteString("\r\n")
		}
	}
	if int64(out.Len()) > f.maxBytes() {
		return nil, failf("the calendar is larger than %d MB", f.maxBytes()>>20)
	}
	if out.Len() == 0 {
		// An empty calendar is fine.
		out.WriteString("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n")
	}
	return out.Bytes(), nil
}

var _ webdav.HTTPClient = (*statusClient)(nil)
