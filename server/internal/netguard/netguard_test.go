package netguard

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"testing"
)

// fakeResolver answers from a table; each answer may change per call (rebinding).
type fakeResolver struct {
	mu      sync.Mutex
	answers map[string][][]netip.Addr
	calls   map[string]int
}

func (r *fakeResolver) LookupNetIP(_ context.Context, _, host string) ([]netip.Addr, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.calls == nil {
		r.calls = map[string]int{}
	}
	list := r.answers[host]
	if len(list) == 0 {
		return nil, &dnsErr{}
	}
	i := min(r.calls[host], len(list)-1)
	r.calls[host]++
	return list[i], nil
}

type dnsErr struct{}

func (dnsErr) Error() string { return "no such host" }

func addrs(ss ...string) []netip.Addr {
	out := make([]netip.Addr, len(ss))
	for i, s := range ss {
		out[i] = netip.MustParseAddr(s)
	}
	return out
}

func mustGuard(t *testing.T, cfg Config) *Guard {
	t.Helper()
	g, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	return g
}

func reason(err error) string {
	if b, ok := Blocked(err); ok {
		return b.Reason
	}
	return ""
}

func TestAddressRules(t *testing.T) {
	ctx := context.Background()
	res := &fakeResolver{answers: map[string][][]netip.Addr{
		"public.example":   {addrs("93.184.216.34")},
		"lan.example":      {addrs("192.168.1.20")},
		"tailnet.example":  {addrs("100.101.102.103")},
		"meta.example":     {addrs("169.254.169.254")},
		"loop.example":     {addrs("127.0.0.1")},
		"v6loop.example":   {addrs("::1")},
		"mapped.example":   {addrs("::ffff:127.0.0.1")},
		"ula.example":      {addrs("fd00::1")},
		"mixed.example":    {addrs("93.184.216.34", "127.0.0.1")},
		"immich.lan":       {addrs("192.168.1.30")},
		"unspecified.test": {addrs("0.0.0.0")},
	}}
	off := mustGuard(t, Config{PrivateNetworks: ModeOff, Resolver: res, AllowedHosts: []string{"immich.lan", "10.9.0.0/16"}})
	on := mustGuard(t, Config{PrivateNetworks: ModeOn, Resolver: res})
	for _, tc := range []struct {
		g    *Guard
		url  string
		want string
	}{
		{off, "https://public.example/x", ""},
		{off, "http://public.example/x", ReasonScheme},
		{off, "https://lan.example", ReasonPrivate},
		{off, "https://tailnet.example", ReasonPrivate},
		{off, "https://ula.example", ReasonPrivate},
		{off, "https://meta.example/latest/meta-data", ReasonLoopback},
		{off, "https://loop.example", ReasonLoopback},
		{off, "https://v6loop.example", ReasonLoopback},
		{off, "https://mapped.example", ReasonLoopback},
		{off, "https://unspecified.test", ReasonLoopback},
		{off, "https://127.0.0.1:2283", ReasonLoopback},
		{off, "https://[fe80::1]", ReasonLoopback},
		{off, "https://mixed.example", ReasonLoopback},
		{off, "http://immich.lan:2283", ""}, // allowed host: private and plain http are fine
		{off, "http://10.9.1.2:2283", ""},   // allowed CIDR
		{off, "ftp://public.example", ReasonScheme},
		{on, "http://lan.example:2283", ""},
		{on, "https://tailnet.example", ""},
		{on, "http://public.example", ReasonScheme},
		{on, "https://meta.example", ReasonLoopback},
		{on, "https://loop.example", ReasonLoopback},
	} {
		if got := reason(tc.g.Check(ctx, tc.url)); got != tc.want {
			t.Errorf("%s (%s): got %q, want %q", tc.url, tc.g.cfg.PrivateNetworks, got, tc.want)
		}
	}
}

func TestAutoMode(t *testing.T) {
	res := &fakeResolver{answers: map[string][][]netip.Addr{
		"home.lan":            {addrs("192.168.1.5")},
		"kinframe.appmire.be": {addrs("49.12.1.1")},
	}}
	for url, want := range map[string]bool{
		"http://localhost:8110":       true,
		"http://192.168.1.5":          true,
		"https://home.lan":            true,
		"https://kinframe.appmire.be": false,
		"https://unknown.example":     false,
	} {
		g := mustGuard(t, Config{PublicURL: url, Resolver: res})
		if got := g.PrivateAllowed(); got != want {
			t.Errorf("%s: private allowed %v, want %v", url, got, want)
		}
	}
}

func TestLoopbackBlockedAtDial(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "secret") }))
	defer srv.Close()
	g := mustGuard(t, Config{PrivateNetworks: ModeOn, AllowedHosts: []string{"127.0.0.1"}})
	_, err := g.Client(Options{}).Get(srv.URL)
	if reason(err) != ReasonLoopback {
		t.Fatalf("loopback reachable: %v", err)
	}
	// The test hook opens it (httptest servers only).
	g = mustGuard(t, Config{PrivateNetworks: ModeOn, AllowLoopback: true})
	resp, err := g.Client(Options{}).Get(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
}

// A name that resolves to a public address for the check and to loopback when
// the request is made must not reach loopback: the dial checks again and
// connects only to the address it checked.
func TestDNSRebinding(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "internal") }))
	defer srv.Close()
	port := srv.URL[strings.LastIndex(srv.URL, ":")+1:]
	res := &fakeResolver{answers: map[string][][]netip.Addr{"rebind.example": {addrs("93.184.216.34"), addrs("127.0.0.1")}}}
	g := mustGuard(t, Config{PrivateNetworks: ModeOff, Resolver: res})
	if err := g.Check(context.Background(), "https://rebind.example:"+port); err != nil {
		t.Fatalf("first answer is public: %v", err)
	}
	_, err := g.Client(Options{}).Get("https://rebind.example:" + port)
	if reason(err) != ReasonLoopback {
		t.Fatalf("rebinding reached loopback: %v", err)
	}
}

func TestRedirectsAreChecked(t *testing.T) {
	internal := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "internal") }))
	defer internal.Close()
	var hops int
	public := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/to-internal":
			http.Redirect(w, r, "http://internal.example:"+internal.URL[strings.LastIndex(internal.URL, ":")+1:]+"/", http.StatusFound)
		case "/loop":
			hops++
			http.Redirect(w, r, "/loop", http.StatusFound)
		case "/pinned":
			http.Redirect(w, r, "http://evil.example/", http.StatusFound)
		default:
			_, _ = io.WriteString(w, "ok")
		}
	}))
	defer public.Close()
	res := &fakeResolver{answers: map[string][][]netip.Addr{"internal.example": {addrs("10.0.0.5")}}}
	// Loopback stands in for "public" here; the redirect target is private and private networks are off.
	g := mustGuard(t, Config{PrivateNetworks: ModeOff, AllowLoopback: true, Resolver: res, AllowedHosts: []string{"127.0.0.1"}})
	c := g.Client(Options{})
	if _, err := c.Get(public.URL + "/to-internal"); reason(err) != ReasonPrivate {
		t.Fatalf("redirect to a private address: %v", err)
	}
	if _, err := c.Get(public.URL + "/loop"); reason(err) != ReasonRedirect {
		t.Fatalf("redirect loop: %v", err)
	}
	if hops != 6 {
		t.Errorf("followed %d hops, want 6 (5 redirects allowed)", hops)
	}
	pc := g.Client(Options{Pinned: []string{"127.0.0.1"}})
	if _, err := pc.Get(public.URL + "/pinned"); reason(err) != ReasonPinned {
		t.Fatalf("pinned redirect: %v", err)
	}
	if resp, err := pc.Get(public.URL + "/ok"); err != nil {
		t.Fatal(err)
	} else {
		resp.Body.Close()
	}
}

func TestPinned(t *testing.T) {
	for host, want := range map[string]bool{
		"p123-sharedstreams.icloud.com": true, "icloud.com": true, "cvws.icloud-content.com": true,
		"icloud.com.evil.example": false, "evilicloud.com": false,
	} {
		if got := Pinned(host, []string{"icloud.com", "icloud-content.com"}); got != want {
			t.Errorf("%s: %v", host, got)
		}
	}
}

func TestTLSAlwaysVerified(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer srv.Close()
	g := mustGuard(t, Config{PrivateNetworks: ModeOn, AllowLoopback: true})
	_, err := g.Client(Options{}).Get(srv.URL)
	if err == nil || !strings.Contains(err.Error(), "certificate") {
		t.Fatalf("self-signed certificate accepted: %v", err)
	}
}
