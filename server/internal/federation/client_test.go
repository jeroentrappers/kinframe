package federation

import (
	"context"
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/netguard"
)

type fakeResolver map[string][]netip.Addr

func (f fakeResolver) LookupNetIP(_ context.Context, _, host string) ([]netip.Addr, error) {
	if a, ok := f[host]; ok {
		return a, nil
	}
	return nil, errors.New("no such host")
}

// federationGuard is the guard the server builds for other servers: public addresses only.
func federationGuard(t *testing.T, loopback bool, r netguard.Resolver) *http.Client {
	t.Helper()
	g, err := netguard.New(netguard.Config{PrivateNetworks: netguard.ModeOff, AllowLoopback: loopback, Resolver: r})
	if err != nil {
		t.Fatal(err)
	}
	return g.Client(netguard.Options{Timeout: 5 * time.Second, MaxRedirects: -1})
}

func wellKnown(base string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/.well-known/kinframe" {
			http.NotFound(w, r)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"server_id": "s1", "server_name": "A", "base_url": base,
			"federation": map[string]any{"enabled": true, "key_id": "k1", "public_key": "x"}})
	}
}

// Other servers are reached at public addresses only (README §5.22): loopback, private and link-local
// (metadata) addresses are refused, also behind a host name, and redirects are not followed.
func TestClientRefusesInternalAddresses(t *testing.T) {
	srv := httptest.NewServer(nil)
	defer srv.Close()
	srv.Config.Handler = wellKnown(srv.URL)
	key, _ := NewKey()
	c := &Client{HTTP: federationGuard(t, false, fakeResolver{
		"internal.example": {netip.MustParseAddr("10.0.0.7")},
		"meta.example":     {netip.MustParseAddr("169.254.169.254")},
		"mixed.example":    {netip.MustParseAddr("93.184.216.34"), netip.MustParseAddr("127.0.0.1")},
		"cgnat.example":    {netip.MustParseAddr("100.64.1.2")},
	}), Self: "https://b.example", Key: func(context.Context) (*Key, error) { return key, nil }}
	ctx := context.Background()
	for _, base := range []string{srv.URL, "https://internal.example", "https://meta.example", "https://mixed.example", "https://cgnat.example",
		"http://[::1]:1", "https://[fd00::1]"} {
		_, err := c.FetchDocument(ctx, base)
		if _, blocked := netguard.Blocked(err); !blocked {
			t.Errorf("%s: %v, want a blocked address", base, err)
		}
		if _, err := c.Do(ctx, http.MethodPost, base+"/federation/v1/ping", map[string]any{"subscription_ids": []string{}}, nil); err == nil {
			t.Errorf("%s: signed call went through", base)
		}
	}
	// Plain http to a public address is refused too (only https).
	pub := &Client{HTTP: federationGuard(t, false, fakeResolver{"public.example": {netip.MustParseAddr("93.184.216.34")}})}
	if _, err := pub.FetchDocument(ctx, "http://public.example"); err == nil {
		t.Error("plain http to a public address went through")
	} else if b, ok := netguard.Blocked(err); !ok || b.Reason != netguard.ReasonScheme {
		t.Errorf("plain http: %v", err)
	}
}

func TestFetchDocument(t *testing.T) {
	srv := httptest.NewServer(nil)
	defer srv.Close()
	srv.Config.Handler = wellKnown(srv.URL)
	c := &Client{HTTP: federationGuard(t, true, nil)}
	d, err := c.FetchDocument(context.Background(), srv.URL)
	if err != nil || d.ServerID != "s1" || !d.Enabled() || len(d.Keys()) != 1 {
		t.Fatalf("document: %+v %v", d, err)
	}
	// A document that names another base URL is not that server's.
	other := httptest.NewServer(wellKnown("https://elsewhere.example"))
	defer other.Close()
	if _, err := c.FetchDocument(context.Background(), other.URL); !errors.Is(err, ErrNotKinframe) {
		t.Fatalf("foreign document: %v", err)
	}
	// Redirects are not followed (a redirect to an internal address can't sneak through).
	redir := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "http://169.254.169.254/latest/meta-data", http.StatusFound)
	}))
	defer redir.Close()
	if _, err := c.FetchDocument(context.Background(), redir.URL); err == nil || !strings.Contains(err.Error(), "302") {
		t.Fatalf("redirect: %v", err)
	}
}

// A signed call from the client verifies on the receiving side; the answer's problem document is surfaced.
func TestClientSignsRequests(t *testing.T) {
	key, _ := NewKey()
	v := &Verifier{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		_, _, err := v.Verify(r, body, func(_, id string) (ed25519.PublicKey, error) {
			if id == key.ID {
				return key.Public, nil
			}
			return nil, ErrUnknownKey
		})
		if err != nil {
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"code":"unauthorized","detail":"` + err.Error() + `"}`))
			return
		}
		w.WriteHeader(http.StatusAccepted)
	}))
	defer srv.Close()
	c := &Client{HTTP: federationGuard(t, true, nil), Self: "https://b.example", Key: func(context.Context) (*Key, error) { return key, nil }}
	if st, err := c.Do(context.Background(), http.MethodPost, srv.URL+"/federation/v1/ping?x=1", map[string]any{"subscription_ids": []string{"a"}}, nil); err != nil || st != 202 {
		t.Fatalf("signed call: %d %v", st, err)
	}
	other, _ := NewKey()
	c.Key = func(context.Context) (*Key, error) { return other, nil }
	_, err := c.Do(context.Background(), http.MethodGet, srv.URL+"/federation/v1/subscriptions/s/changes", nil, nil)
	if re, ok := AsRemote(err); !ok || re.Status != 401 || re.Code != "unauthorized" {
		t.Fatalf("unknown key: %v", err)
	}
}
