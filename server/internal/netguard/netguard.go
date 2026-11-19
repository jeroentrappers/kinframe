// Package netguard is the HTTP client for URLs that people enter (Immich
// servers, shared-album links, calendar feeds): it keeps those requests out of
// the server's own network (SSRF, docs/spec/photo-sources.md §12.3).
//
// Every address is checked when it is dialed, after DNS resolution, and the
// connection goes to exactly the address that was checked, so DNS rebinding
// cannot swap in a private address after a check. Redirects are followed at
// most five times and every hop is checked the same way.
package netguard

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

// Mode is KINFRAME_SOURCES_PRIVATE_NETWORKS.
const (
	ModeAuto = "auto" // allowed when the server itself is on a private network
	ModeOn   = "on"
	ModeOff  = "off"
)

// Config configures a Guard.
type Config struct {
	// PrivateNetworks is auto, on or off (default auto).
	PrivateNetworks string
	// AllowedHosts are host names, IP addresses or CIDRs that may use private
	// addresses (and plain http) whatever PrivateNetworks says.
	AllowedHosts []string
	// CAFile holds extra trusted CA certificates (PEM).
	CAFile string
	// PublicURL is the server's own URL; auto mode looks at its host.
	PublicURL string
	// AllowLoopback lets tests reach httptest servers on 127.0.0.1. Never set
	// in production: loopback and link-local are always blocked otherwise.
	AllowLoopback bool
	// Resolver resolves host names (default net.DefaultResolver).
	Resolver Resolver
	// DialTimeout is the connect timeout (default 10 s).
	DialTimeout time.Duration
}

// Resolver looks up host names (net.Resolver satisfies it).
type Resolver interface {
	LookupNetIP(ctx context.Context, network, host string) ([]netip.Addr, error)
}

// Guard checks addresses and builds guarded HTTP clients.
type Guard struct {
	cfg      Config
	hosts    map[string]bool
	prefixes []netip.Prefix
	roots    *x509.CertPool

	autoOnce sync.Once
	autoPriv bool
}

// Reasons a request was blocked.
const (
	ReasonLoopback = "loopback" // loopback, link-local, multicast, unspecified: never allowed
	ReasonPrivate  = "private"  // a private network address while private networks are off
	ReasonScheme   = "scheme"   // plain http to a public address, or not http(s) at all
	ReasonPinned   = "pinned"   // a provider's host outside its allowed domains
	ReasonRedirect = "redirect" // too many redirects
)

// BlockedError is a request the guard refused.
type BlockedError struct {
	Host   string
	Reason string
}

func (e *BlockedError) Error() string {
	switch e.Reason {
	case ReasonPrivate:
		return fmt.Sprintf("%s is on a private network, which this server may not reach", e.Host)
	case ReasonLoopback:
		return fmt.Sprintf("%s is a local or link-local address, which is never allowed", e.Host)
	case ReasonScheme:
		return fmt.Sprintf("%s: only https:// is allowed for public addresses", e.Host)
	case ReasonPinned:
		return fmt.Sprintf("%s is not an allowed host for this service", e.Host)
	case ReasonRedirect:
		return "too many redirects"
	}
	return "blocked: " + e.Host
}

// Blocked returns the BlockedError in err's chain, if any.
func Blocked(err error) (*BlockedError, bool) {
	var b *BlockedError
	if errors.As(err, &b) {
		return b, true
	}
	return nil, false
}

// New builds a guard.
func New(cfg Config) (*Guard, error) {
	if cfg.PrivateNetworks == "" {
		cfg.PrivateNetworks = ModeAuto
	}
	switch cfg.PrivateNetworks {
	case ModeAuto, ModeOn, ModeOff:
	default:
		return nil, fmt.Errorf("private networks must be auto, on or off, not %q", cfg.PrivateNetworks)
	}
	if cfg.Resolver == nil {
		cfg.Resolver = net.DefaultResolver
	}
	if cfg.DialTimeout <= 0 {
		cfg.DialTimeout = 10 * time.Second
	}
	g := &Guard{cfg: cfg, hosts: map[string]bool{}}
	for _, h := range cfg.AllowedHosts {
		h = strings.TrimSpace(strings.ToLower(h))
		if h == "" {
			continue
		}
		if p, err := netip.ParsePrefix(h); err == nil {
			g.prefixes = append(g.prefixes, p.Masked())
		} else if a, err := netip.ParseAddr(h); err == nil {
			g.prefixes = append(g.prefixes, netip.PrefixFrom(a.Unmap(), a.Unmap().BitLen()))
		} else {
			g.hosts[strings.TrimSuffix(h, ".")] = true
		}
	}
	if cfg.CAFile != "" {
		pem, err := os.ReadFile(cfg.CAFile)
		if err != nil {
			return nil, fmt.Errorf("CA file: %w", err)
		}
		pool, err := x509.SystemCertPool()
		if err != nil || pool == nil {
			pool = x509.NewCertPool()
		}
		if !pool.AppendCertsFromPEM(pem) {
			return nil, errors.New("CA file: no PEM certificates found")
		}
		g.roots = pool
	}
	return g, nil
}

var cgnat = netip.MustParsePrefix("100.64.0.0/10")

// alwaysBlocked: loopback, unspecified, link-local (cloud metadata), multicast, broadcast.
func alwaysBlocked(a netip.Addr) bool {
	a = a.Unmap()
	return a.IsLoopback() || a.IsUnspecified() || a.IsLinkLocalUnicast() || a.IsLinkLocalMulticast() ||
		a.IsInterfaceLocalMulticast() || a.IsMulticast() || a == netip.AddrFrom4([4]byte{255, 255, 255, 255}) ||
		(a.Is4() && a.As4()[0] == 0)
}

// IsPrivate reports RFC 1918, fc00::/7 and CGNAT (100.64.0.0/10) addresses.
func IsPrivate(a netip.Addr) bool {
	a = a.Unmap()
	return a.IsPrivate() || cgnat.Contains(a)
}

// PrivateAllowed reports whether private network addresses are allowed
// (discovery photo_sources_private_networks).
func (g *Guard) PrivateAllowed() bool {
	switch g.cfg.PrivateNetworks {
	case ModeOn:
		return true
	case ModeOff:
		return false
	}
	g.autoOnce.Do(func() { g.autoPriv = g.serverIsPrivate() })
	return g.autoPriv
}

// serverIsPrivate: the host of the public URL is localhost or resolves only
// to private or loopback addresses (a kinframe on the home network).
func (g *Guard) serverIsPrivate() bool {
	u, err := url.Parse(g.cfg.PublicURL)
	if err != nil || u.Hostname() == "" {
		return false
	}
	h := strings.ToLower(u.Hostname())
	if h == "localhost" || strings.HasSuffix(h, ".localhost") {
		return true
	}
	if a, err := netip.ParseAddr(h); err == nil {
		return IsPrivate(a) || a.Unmap().IsLoopback()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	addrs, err := g.cfg.Resolver.LookupNetIP(ctx, "ip", h)
	if err != nil || len(addrs) == 0 {
		return false
	}
	for _, a := range addrs {
		if !IsPrivate(a) && !a.Unmap().IsLoopback() {
			return false
		}
	}
	return true
}

func (g *Guard) hostAllowed(host string, a netip.Addr) bool {
	if g.hosts[strings.TrimSuffix(strings.ToLower(host), ".")] {
		return true
	}
	a = a.Unmap()
	for _, p := range g.prefixes {
		if p.Contains(a) {
			return true
		}
	}
	return false
}

// checkAddr decides whether host (as requested) may be reached at a.
// plain is a plain-http request.
func (g *Guard) checkAddr(host string, a netip.Addr, plain bool) error {
	a = a.Unmap()
	if alwaysBlocked(a) {
		if g.cfg.AllowLoopback && a.IsLoopback() {
			return nil
		}
		return &BlockedError{Host: host, Reason: ReasonLoopback}
	}
	allowed := g.hostAllowed(host, a)
	if IsPrivate(a) {
		if !allowed && !g.PrivateAllowed() {
			return &BlockedError{Host: host, Reason: ReasonPrivate}
		}
		return nil
	}
	if plain && !allowed {
		return &BlockedError{Host: host, Reason: ReasonScheme}
	}
	return nil
}

// Check resolves a URL's host and applies the same rules as a request would,
// without connecting (for validating what people enter).
func (g *Guard) Check(ctx context.Context, rawURL string) error {
	u, err := url.Parse(rawURL)
	if err != nil || u.Hostname() == "" {
		return &BlockedError{Host: rawURL, Reason: ReasonScheme}
	}
	if u.Scheme != "https" && u.Scheme != "http" {
		return &BlockedError{Host: u.Hostname(), Reason: ReasonScheme}
	}
	addrs, err := g.resolve(ctx, u.Hostname())
	if err != nil {
		return err
	}
	for _, a := range addrs {
		if err := g.checkAddr(u.Hostname(), a, u.Scheme == "http"); err != nil {
			return err
		}
	}
	return nil
}

func (g *Guard) resolve(ctx context.Context, host string) ([]netip.Addr, error) {
	if a, err := netip.ParseAddr(strings.Trim(host, "[]")); err == nil {
		return []netip.Addr{a}, nil
	}
	return g.cfg.Resolver.LookupNetIP(ctx, "ip", host)
}

type plainKey struct{}

// dial resolves the host, checks every address and connects to a checked one.
// Any address that is never allowed fails the dial: a name that resolves to a
// public and a loopback address is not trusted at all.
func (g *Guard) dial(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	addrs, err := g.resolve(ctx, host)
	if err != nil {
		return nil, err
	}
	plain, _ := ctx.Value(plainKey{}).(bool)
	var ok []netip.Addr
	var first error
	for _, a := range addrs {
		if err := g.checkAddr(host, a, plain); err != nil {
			if b, _ := Blocked(err); b != nil && b.Reason == ReasonLoopback {
				return nil, err
			}
			if first == nil {
				first = err
			}
			continue
		}
		ok = append(ok, a)
	}
	if len(ok) == 0 {
		if first == nil {
			first = &net.DNSError{Err: "no addresses", Name: host, IsNotFound: true}
		}
		return nil, first
	}
	d := net.Dialer{Timeout: g.cfg.DialTimeout}
	var last error
	for _, a := range ok {
		c, err := d.DialContext(ctx, network, net.JoinHostPort(a.Unmap().String(), port))
		if err == nil {
			return c, nil
		}
		last = err
	}
	return nil, last
}

// Options configure one client.
type Options struct {
	// Timeout of a whole request including the body (0: none; use contexts).
	Timeout time.Duration
	// Pinned, when set, allows only these hosts and their subdomains (e.g.
	// icloud.com): fixed providers never follow a link elsewhere.
	Pinned []string
	// Jar keeps cookies (Immich link logins).
	Jar http.CookieJar
	// MaxRedirects defaults to 5; negative disables following redirects.
	MaxRedirects int
}

// Pinned reports whether host is one of suffixes or a subdomain of one.
func Pinned(host string, suffixes []string) bool {
	host = strings.TrimSuffix(strings.ToLower(host), ".")
	for _, s := range suffixes {
		s = strings.TrimPrefix(strings.ToLower(s), ".")
		if host == s || strings.HasSuffix(host, "."+s) {
			return true
		}
	}
	return false
}

type guardedTransport struct {
	base   *http.Transport
	pinned []string
}

func (t *guardedTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if req.URL.Scheme != "https" && req.URL.Scheme != "http" {
		return nil, &BlockedError{Host: req.URL.Host, Reason: ReasonScheme}
	}
	if len(t.pinned) > 0 && !Pinned(req.URL.Hostname(), t.pinned) {
		return nil, &BlockedError{Host: req.URL.Hostname(), Reason: ReasonPinned}
	}
	if req.URL.Scheme == "http" {
		req = req.WithContext(context.WithValue(req.Context(), plainKey{}, true))
	}
	return t.base.RoundTrip(req)
}

// Client returns an HTTP client whose every connection is guarded. TLS is
// always verified (system roots plus the CA file).
func (g *Guard) Client(o Options) *http.Client {
	tr := &http.Transport{
		Proxy:                 nil, // a proxy would bypass the address checks
		DialContext:           g.dial,
		TLSClientConfig:       &tls.Config{RootCAs: g.roots, MinVersion: tls.VersionTLS12},
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 60 * time.Second,
		IdleConnTimeout:       60 * time.Second,
		MaxIdleConnsPerHost:   4,
		ForceAttemptHTTP2:     true,
	}
	max := o.MaxRedirects
	if max == 0 {
		max = 5
	}
	c := &http.Client{Transport: &guardedTransport{base: tr, pinned: o.Pinned}, Timeout: o.Timeout, Jar: o.Jar}
	c.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		if max < 0 {
			return http.ErrUseLastResponse
		}
		if len(via) > max {
			return &BlockedError{Host: req.URL.Hostname(), Reason: ReasonRedirect}
		}
		return nil
	}
	return c
}
