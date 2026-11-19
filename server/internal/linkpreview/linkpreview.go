// Package linkpreview fetches link previews for chat messages (README §5.19 "Link previews"): the title,
// description, site name and image of a web page, from Open Graph and Twitter card tags, <title> and the
// description meta tag.
//
// Requests go through a netguard client, so only public addresses are reached (checked on every connection,
// redirects included, at most five). Pages are read up to MaxHTML bytes and images up to MaxImage bytes; the
// whole fetch has a time limit. The server re-encodes the image itself, so clients never load remote content.
package linkpreview

import (
	"context"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/net/html"
	"golang.org/x/net/html/charset"

	"github.com/jeroentrappers/kinframe/server/internal/netguard"
)

// UserAgent is sent with every request.
const UserAgent = "kinframe-linkpreview/1"

// Limits (README §5.19).
const (
	DefaultTimeout  = 5 * time.Second
	DefaultMaxHTML  = 1 << 20
	DefaultMaxImage = 5 << 20
	MaxURLLength    = 2048
	maxTitle        = 300
	maxDescription  = 500
	maxSiteName     = 100
)

// Error codes stored with a failed preview (link_previews.error_code).
const (
	ErrBlockedAddress = "blocked_address"
	ErrTimeout        = "timeout"
	ErrTooLarge       = "too_large"
	ErrNotHTML        = "not_html"
	ErrFetchFailed    = "fetch_failed"
)

// Error is a failed fetch with its code (ErrBlockedAddress ..., or http_<status>).
type Error struct {
	Code string
	Err  error
}

func (e *Error) Error() string {
	if e.Err != nil {
		return e.Code + ": " + e.Err.Error()
	}
	return e.Code
}

func (e *Error) Unwrap() error { return e.Err }

// Code returns the error code of a fetch error.
func Code(err error) string {
	var e *Error
	if errors.As(err, &e) {
		return e.Code
	}
	return ErrFetchFailed
}

// Page is what a page says about itself.
type Page struct {
	URL         string // the final URL after redirects
	Title       string
	Description string
	SiteName    string
	ImageURL    string // absolute; empty when the page names none
}

// Fetcher fetches pages and images.
type Fetcher struct {
	HTTP     *http.Client // a netguard client (NewFetcher)
	Timeout  time.Duration
	MaxHTML  int64
	MaxImage int64
}

// NewFetcher builds a fetcher whose requests may only reach public addresses (plain http included: previews
// carry no credentials).
func NewFetcher(guard *netguard.Guard) *Fetcher {
	return &Fetcher{
		HTTP:    guard.Client(netguard.Options{MaxRedirects: 5, AllowPlainHTTP: true}),
		Timeout: DefaultTimeout, MaxHTML: DefaultMaxHTML, MaxImage: DefaultMaxImage,
	}
}

// Guard builds the guard link previews use: public addresses only, whatever the photo source settings allow.
// allowLoopback is for tests only.
func Guard(allowLoopback bool, resolver netguard.Resolver) (*netguard.Guard, error) {
	return netguard.New(netguard.Config{PrivateNetworks: netguard.ModeOff, AllowLoopback: allowLoopback, Resolver: resolver})
}

// Normalize checks a link and returns its normalized form (scheme and host lower case, no fragment, no user
// info, default ports dropped), the cache key of its preview.
func Normalize(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" || len(raw) > MaxURLLength {
		return "", errors.New("not a link")
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "", err
	}
	u.Scheme = strings.ToLower(u.Scheme)
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", errors.New("only http and https links")
	}
	if u.User != nil || u.Opaque != "" {
		return "", errors.New("not a web link")
	}
	host := strings.TrimSuffix(strings.ToLower(u.Hostname()), ".")
	if host == "" {
		return "", errors.New("no host")
	}
	port := u.Port()
	if (u.Scheme == "http" && port == "80") || (u.Scheme == "https" && port == "443") {
		port = ""
	}
	if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	u.Host = host
	if port != "" {
		u.Host += ":" + port
	}
	u.Fragment, u.RawFragment = "", ""
	if u.Path == "" {
		u.Path = "/"
	}
	return u.String(), nil
}

var urlRe = regexp.MustCompile(`(?i)\bhttps?://[^\s<>"'` + "`" + `]+`)

// FirstURL returns the first http(s) link in a text, without trailing punctuation, or "".
func FirstURL(text string) string {
	m := urlRe.FindString(text)
	if m == "" {
		return ""
	}
	// Trailing punctuation belongs to the sentence; an unbalanced closing bracket too.
	for {
		trimmed := strings.TrimRight(m, ".,;:!?")
		if strings.HasSuffix(trimmed, ")") && strings.Count(trimmed, "(") < strings.Count(trimmed, ")") {
			trimmed = trimmed[:len(trimmed)-1]
		}
		if trimmed == m {
			break
		}
		m = trimmed
	}
	return m
}

func (f *Fetcher) timeout() time.Duration {
	if f.Timeout > 0 {
		return f.Timeout
	}
	return DefaultTimeout
}

// classify turns a transport error into a fetch error.
func classify(ctx context.Context, err error) error {
	if _, ok := netguard.Blocked(err); ok {
		return &Error{Code: ErrBlockedAddress, Err: err}
	}
	var ne net.Error
	if errors.Is(err, context.DeadlineExceeded) || ctx.Err() != nil || (errors.As(err, &ne) && ne.Timeout()) {
		return &Error{Code: ErrTimeout, Err: err}
	}
	return &Error{Code: ErrFetchFailed, Err: err}
}

func (f *Fetcher) get(ctx context.Context, rawURL, accept string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, &Error{Code: ErrFetchFailed, Err: err}
	}
	req.Header.Set("User-Agent", UserAgent)
	req.Header.Set("Accept", accept)
	resp, err := f.HTTP.Do(req)
	if err != nil {
		return nil, classify(ctx, err)
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		resp.Body.Close()
		return nil, &Error{Code: fmt.Sprintf("http_%d", resp.StatusCode)}
	}
	return resp, nil
}

// readLimited reads at most max bytes; more is ErrTooLarge unless truncate.
func readLimited(ctx context.Context, r io.Reader, max int64, truncate bool) ([]byte, error) {
	b, err := io.ReadAll(io.LimitReader(r, max+1))
	if err != nil {
		return nil, classify(ctx, err)
	}
	if int64(len(b)) > max {
		if truncate {
			return b[:max], nil
		}
		return nil, &Error{Code: ErrTooLarge}
	}
	return b, nil
}

// Fetch reads a page's preview. The page is read up to MaxHTML bytes (the head is what matters; a longer page
// is cut, not refused).
func (f *Fetcher) Fetch(ctx context.Context, rawURL string) (*Page, error) {
	ctx, cancel := context.WithTimeout(ctx, f.timeout())
	defer cancel()
	resp, err := f.get(ctx, rawURL, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	ct := resp.Header.Get("Content-Type")
	mt, _, _ := mime.ParseMediaType(ct)
	if mt != "" && mt != "text/html" && mt != "application/xhtml+xml" {
		return nil, &Error{Code: ErrNotHTML}
	}
	max := f.MaxHTML
	if max <= 0 {
		max = DefaultMaxHTML
	}
	body, err := readLimited(ctx, resp.Body, max, true)
	if err != nil {
		return nil, err
	}
	page := Parse(body, ct, resp.Request.URL)
	page.URL = resp.Request.URL.String()
	return page, nil
}

// FetchImage reads an image (at most MaxImage bytes) for the server to re-encode.
func (f *Fetcher) FetchImage(ctx context.Context, rawURL string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, f.timeout())
	defer cancel()
	resp, err := f.get(ctx, rawURL, "image/*")
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if mt, _, _ := mime.ParseMediaType(resp.Header.Get("Content-Type")); mt != "" && !strings.HasPrefix(mt, "image/") {
		return nil, &Error{Code: ErrNotHTML}
	}
	max := f.MaxImage
	if max <= 0 {
		max = DefaultMaxImage
	}
	if resp.ContentLength > max {
		return nil, &Error{Code: ErrTooLarge}
	}
	return readLimited(ctx, resp.Body, max, false)
}

// Parse reads the preview fields from an HTML document (contentType picks the charset; base resolves a
// relative image URL).
func Parse(body []byte, contentType string, base *url.URL) *Page {
	r, err := charset.NewReader(strings.NewReader(string(body)), contentType)
	if err != nil {
		r = strings.NewReader(string(body))
	}
	z := html.NewTokenizer(r)
	meta := map[string]string{}
	var title strings.Builder
	inTitle, haveTitle := false, false
loop:
	for {
		switch z.Next() {
		case html.ErrorToken:
			break loop
		case html.StartTagToken, html.SelfClosingTagToken:
			name, hasAttr := z.TagName()
			switch string(name) {
			case "meta":
				var key, content string
				for hasAttr {
					var k, v []byte
					k, v, hasAttr = z.TagAttr()
					switch strings.ToLower(string(k)) {
					case "property", "name":
						if key == "" || strings.HasPrefix(strings.ToLower(string(v)), "og:") {
							key = strings.ToLower(strings.TrimSpace(string(v)))
						}
					case "content":
						content = string(v)
					}
				}
				if key != "" && content != "" {
					if _, dup := meta[key]; !dup {
						meta[key] = content
					}
				}
			case "title":
				if !haveTitle {
					inTitle = true
				}
			case "body":
				// Everything a preview needs is in the head; stop at the body unless nothing was found yet.
				if len(meta) > 0 || haveTitle {
					break loop
				}
			}
		case html.EndTagToken:
			if name, _ := z.TagName(); string(name) == "title" && inTitle {
				inTitle, haveTitle = false, true
			}
		case html.TextToken:
			if inTitle {
				title.Write(z.Text())
			}
		}
	}
	first := func(keys ...string) string {
		for _, k := range keys {
			if v := clean(meta[k]); v != "" {
				return v
			}
		}
		return ""
	}
	p := &Page{
		Title:       first("og:title", "twitter:title"),
		Description: first("og:description", "twitter:description", "description"),
		SiteName:    first("og:site_name", "application-name"),
	}
	if p.Title == "" {
		p.Title = clean(title.String())
	}
	if img := first("og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src"); img != "" && base != nil {
		if u, err := base.Parse(img); err == nil && (u.Scheme == "https" || u.Scheme == "http") {
			p.ImageURL = u.String()
		}
	}
	if p.SiteName == "" && base != nil {
		p.SiteName = strings.TrimPrefix(base.Hostname(), "www.")
	}
	p.Title = cut(p.Title, maxTitle)
	p.Description = cut(p.Description, maxDescription)
	p.SiteName = cut(p.SiteName, maxSiteName)
	return p
}

// clean collapses white space and drops control characters.
func clean(s string) string {
	s = strings.Map(func(r rune) rune {
		if r == utf8.RuneError || (r < 0x20 && r != '\n' && r != '\t') || r == 0x7f {
			return -1
		}
		return r
	}, html.UnescapeString(s))
	return strings.Join(strings.Fields(s), " ")
}

// cut shortens s to at most n characters, with an ellipsis.
func cut(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return strings.TrimSpace(string(r[:n-1])) + "…"
}
