package linkpreview

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"strings"
	"testing"
	"time"
)

// fakeResolver answers fixed addresses for test host names and the system resolver for the rest.
type fakeResolver map[string]string

func (f fakeResolver) LookupNetIP(ctx context.Context, network, host string) ([]netip.Addr, error) {
	if a, ok := f[host]; ok {
		return []netip.Addr{netip.MustParseAddr(a)}, nil
	}
	return net.DefaultResolver.LookupNetIP(ctx, network, host)
}

func fetcher(t *testing.T, loopback bool, r fakeResolver) *Fetcher {
	t.Helper()
	g, err := Guard(loopback, r)
	if err != nil {
		t.Fatal(err)
	}
	f := NewFetcher(g)
	f.Timeout = 2 * time.Second
	return f
}

func TestNormalize(t *testing.T) {
	for in, want := range map[string]string{
		"HTTPS://Example.ORG":                 "https://example.org/",
		"https://example.org:443/a?b=1#frag":  "https://example.org/a?b=1",
		"http://example.org:80/x":             "http://example.org/x",
		"http://example.org:8080/x":           "http://example.org:8080/x",
		"https://[2001:DB8::1]/":              "https://[2001:db8::1]/",
		"  https://example.org./path  ":       "https://example.org/path",
		"https://example.org/%E2%9C%93?q=a b": "https://example.org/%E2%9C%93?q=a b",
	} {
		got, err := Normalize(in)
		if err != nil || got != want {
			t.Errorf("Normalize(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	for _, bad := range []string{"", "ftp://example.org/", "javascript:alert(1)", "https://user:pw@example.org/", "https:///nohost",
		"mailto:a@b.c", "https://" + strings.Repeat("a", 2050) + ".org/"} {
		if got, err := Normalize(bad); err == nil {
			t.Errorf("Normalize(%q) = %q, want an error", bad, got)
		}
	}
}

func TestFirstURL(t *testing.T) {
	for in, want := range map[string]string{
		"Look: https://example.org/a.":                    "https://example.org/a",
		"(see https://en.wikipedia.org/wiki/Go_(game))":   "https://en.wikipedia.org/wiki/Go_(game)",
		"(see https://example.org/x)":                     "https://example.org/x",
		"first http://a.example/ then https://b.example/": "http://a.example/",
		"no links here, just example.org":                 "",
		`<a href="https://example.org/q?x=1">`:            "https://example.org/q?x=1",
		"Wow!https://example.org/!!":                      "https://example.org/",
	} {
		if got := FirstURL(in); got != want {
			t.Errorf("FirstURL(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestParse(t *testing.T) {
	base, _ := url.Parse("https://www.example.org/posts/1")
	p := Parse([]byte(`<!doctype html><html><head>
		<title>  The   page  </title>
		<meta name="twitter:title" content="Twitter title">
		<meta property="og:title" content="OG &amp; title">
		<meta name="description" content="Plain description">
		<meta property="og:image" content="../img/cover.jpg">
		</head><body><meta property="og:description" content="in the body"></body></html>`), "text/html", base)
	if p.Title != "OG & title" || p.Description != "Plain description" || p.SiteName != "example.org" ||
		p.ImageURL != "https://www.example.org/img/cover.jpg" {
		t.Fatalf("parsed: %+v", p)
	}
	// Fallbacks: <title>, twitter cards; an ISO-8859-1 page; long texts are cut.
	p = Parse([]byte("<html><head><title>Caf\xe9</title><meta name=\"twitter:description\" content=\""+strings.Repeat("x", 600)+"\">"+
		"<meta name=\"twitter:image\" content=\"javascript:alert(1)\"></head></html>"), "text/html; charset=iso-8859-1", base)
	if p.Title != "Café" || len([]rune(p.Description)) != 500 || !strings.HasSuffix(p.Description, "…") || p.ImageURL != "" {
		t.Fatalf("fallbacks: %+v", p)
	}
}

func TestFetch(t *testing.T) {
	big := strings.Repeat("<p>filler</p>", 200000)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/page":
			w.Header().Set("Content-Type", "text/html")
			fmt.Fprint(w, `<html><head><meta property="og:title" content="Hello"><meta property="og:image" content="/i.png"></head></html>`)
		case "/huge":
			w.Header().Set("Content-Type", "text/html")
			fmt.Fprint(w, `<html><head><title>Big page</title></head><body>`+big+`</body></html>`)
		case "/pdf":
			w.Header().Set("Content-Type", "application/pdf")
		case "/slow":
			time.Sleep(time.Second)
			fmt.Fprint(w, "<title>late</title>")
		case "/bigimage":
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write(make([]byte, 6<<20))
		case "/loop":
			http.Redirect(w, r, "/loop", http.StatusFound)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	f := fetcher(t, true, nil)
	ctx := context.Background()
	p, err := f.Fetch(ctx, srv.URL+"/page")
	if err != nil || p.Title != "Hello" || p.ImageURL != srv.URL+"/i.png" {
		t.Fatalf("page: %+v %v", p, err)
	}
	// Longer pages are read up to the limit, not refused.
	if p, err := f.Fetch(ctx, srv.URL+"/huge"); err != nil || p.Title != "Big page" {
		t.Fatalf("huge page: %+v %v", p, err)
	}
	for path, code := range map[string]string{"/pdf": ErrNotHTML, "/missing": "http_404", "/loop": ErrBlockedAddress} {
		if _, err := f.Fetch(ctx, srv.URL+path); Code(err) != code {
			t.Errorf("%s: %v, want %s", path, err, code)
		}
	}
	if _, err := f.FetchImage(ctx, srv.URL+"/bigimage"); Code(err) != ErrTooLarge {
		t.Errorf("big image: %v", err)
	}
	f.Timeout = 200 * time.Millisecond
	if _, err := f.Fetch(ctx, srv.URL+"/slow"); Code(err) != ErrTimeout {
		t.Errorf("slow page: %v", err)
	}
}

// SSRF: loopback, private networks and link-local (cloud metadata) are never fetched, also not behind a
// redirect or a host name that resolves to them.
func TestFetchOnlyPublicAddresses(t *testing.T) {
	inner := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		fmt.Fprint(w, "<title>internal</title>")
	}))
	defer inner.Close()
	_, port, _ := net.SplitHostPort(strings.TrimPrefix(inner.URL, "http://"))
	ctx := context.Background()
	strict := fetcher(t, false, fakeResolver{"intranet.test": "10.0.0.5", "metadata.test": "169.254.169.254", "cgnat.test": "100.64.1.1", "v6local.test": "::1"})
	for _, u := range []string{
		inner.URL,                                 // 127.0.0.1
		"http://localhost:" + port + "/",          // a name for loopback
		"http://intranet.test:" + port + "/",      // RFC 1918
		"http://metadata.test/latest/meta-data/",  // link-local
		"http://cgnat.test/",                      // CGNAT
		"http://v6local.test:" + port + "/",       // IPv6 loopback
		"http://[::ffff:127.0.0.1]:" + port + "/", // IPv4-mapped loopback
		"http://0.0.0.0:" + port + "/",
	} {
		if _, err := strict.Fetch(ctx, u); Code(err) != ErrBlockedAddress {
			t.Errorf("%s: %v, want blocked", u, err)
		}
	}
	// A public-looking start that redirects inside is blocked at the redirect (loopback allowed here only to
	// reach the test server at all).
	redir := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "http://intranet.test:"+port+"/", http.StatusFound)
	}))
	defer redir.Close()
	lenient := fetcher(t, true, fakeResolver{"intranet.test": "10.0.0.5"})
	if _, err := lenient.Fetch(ctx, redir.URL); Code(err) != ErrBlockedAddress {
		t.Errorf("redirect to a private address: %v", err)
	}
	if _, err := lenient.FetchImage(ctx, "http://intranet.test:"+port+"/i.png"); Code(err) != ErrBlockedAddress {
		t.Errorf("image on a private address: %v", err)
	}
}
