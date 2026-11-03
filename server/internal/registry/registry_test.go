package registry

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// A registry served over HTTP the way GitHub serves the release's assets.
type fakeRegistry struct {
	mu    sync.Mutex
	files map[string][]byte
	key   *ecdsa.PrivateKey
}

func newFake(t *testing.T) (*fakeRegistry, *httptest.Server) {
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	f := &fakeRegistry{files: map[string][]byte{}, key: k}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		b, ok := f.files[strings.TrimPrefix(r.URL.Path, "/")]
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Write(b)
	}))
	t.Cleanup(srv.Close)
	return f, srv
}

func (f *fakeRegistry) publicKey(t *testing.T) *ecdsa.PublicKey {
	der, _ := x509.MarshalPKIXPublicKey(&f.key.PublicKey)
	k, err := ParsePublicKey(base64.StdEncoding.EncodeToString(der))
	if err != nil {
		t.Fatal(err)
	}
	return k
}

// publish signs an index with the given bundles (file → content) and revocations.
func (f *fakeRegistry) publish(seq int64, bundles map[string][]byte, revoked []Revoked, tamper func(map[string][]byte)) {
	f.mu.Lock()
	defer f.mu.Unlock()
	ix := Index{Format: 1, Sequence: seq, GeneratedAt: time.Now().UTC(), Revoked: revoked}
	for file, b := range bundles {
		sum := sha256.Sum256(b)
		ver := strings.TrimSuffix(strings.TrimPrefix(file, "community.x-"), ".zip")
		ix.Plugins = append(ix.Plugins, Plugin{ID: "community.x", Versions: []Version{{
			Version: ver, API: 1, File: file, Size: int64(len(b)), SHA256: hex.EncodeToString(sum[:]),
			Manifest: map[string]any{"id": "community.x", "version": ver},
		}}})
		f.files[file] = b
	}
	raw, _ := json.Marshal(ix)
	h := sha256.Sum256(raw)
	sig, _ := ecdsa.SignASN1(rand.Reader, f.key, h[:])
	f.files["index.json"] = raw
	f.files["index.json.sig"] = []byte(base64.StdEncoding.EncodeToString(sig))
	if tamper != nil {
		tamper(f.files)
	}
}

func mirror(t *testing.T, url string, key *ecdsa.PublicKey, dir string) *Mirror {
	return &Mirror{BaseURL: url, Key: key, Dir: dir, HTTP: http.DefaultClient, Interval: time.Hour, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
}

func TestMirrorVerifiesAndKeepsBundles(t *testing.T) {
	f, srv := newFake(t)
	dir := t.TempDir()
	m := mirror(t, srv.URL, f.publicKey(t), dir)
	ctx := context.Background()
	f.publish(1, map[string][]byte{"community.x-1.0.0.zip": []byte("zip one")}, nil, nil)
	if err := m.Sync(ctx); err != nil {
		t.Fatal(err)
	}
	raw, sig, ix := m.Signed()
	if ix.Sequence != 1 || len(raw) == 0 || sig == "" {
		t.Fatalf("signed: %d %q", ix.Sequence, sig)
	}
	if p := m.BundlePath("community.x-1.0.0.zip"); p == "" {
		t.Fatal("bundle not served")
	} else if b, _ := os.ReadFile(p); string(b) != "zip one" {
		t.Fatalf("bundle content %q", b)
	}
	if m.BundlePath("../index.json") != "" || m.BundlePath("community.y-1.0.0.zip") != "" {
		t.Fatal("serves files the index doesn't list")
	}
	if l := ix.Latest(); len(l) != 1 || l[0].Version != "1.0.0" {
		t.Fatalf("latest: %+v", l)
	}

	// A restart loads the cache (verified again).
	again := mirror(t, srv.URL, f.publicKey(t), dir)
	if err := again.Load(); err != nil {
		t.Fatal(err)
	}
	if _, _, ix := again.Signed(); ix == nil || ix.Sequence != 1 {
		t.Fatal("cache not loaded")
	}

	// An older index, even validly signed, is refused (no rollback).
	f.publish(2, map[string][]byte{"community.x-1.0.1.zip": []byte("zip two")}, nil, nil)
	if err := m.Sync(ctx); err != nil {
		t.Fatal(err)
	}
	f.publish(1, map[string][]byte{"community.x-1.0.0.zip": []byte("zip one")}, nil, nil)
	if err := m.Sync(ctx); err == nil || !strings.Contains(err.Error(), "older") {
		t.Fatalf("rollback: %v", err)
	}
	if _, _, ix := m.Signed(); ix.Sequence != 2 {
		t.Fatalf("sequence %d", ix.Sequence)
	}
}

func TestMirrorRefusesWhatDoesntMatchTheSignature(t *testing.T) {
	f, srv := newFake(t)
	m := mirror(t, srv.URL, f.publicKey(t), t.TempDir())
	ctx := context.Background()
	// The index changed after signing.
	f.publish(1, map[string][]byte{"community.x-1.0.0.zip": []byte("a")}, nil, func(files map[string][]byte) {
		files["index.json"] = []byte(strings.Replace(string(files["index.json"]), `"sequence":1`, `"sequence":9`, 1))
	})
	if err := m.Sync(ctx); err == nil || !strings.Contains(err.Error(), "signature") {
		t.Fatalf("tampered index: %v", err)
	}
	// A bundle swapped after signing.
	f.publish(1, map[string][]byte{"community.x-1.0.0.zip": []byte("a")}, nil, func(files map[string][]byte) {
		files["community.x-1.0.0.zip"] = []byte("b")
	})
	if err := m.Sync(ctx); err == nil || !strings.Contains(err.Error(), "does not match") {
		t.Fatalf("tampered bundle: %v", err)
	}
	if raw, _, _ := m.Signed(); raw != nil {
		t.Fatal("nothing should be current")
	}
	// Another key's signature.
	other, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	f.key, f.files = other, map[string][]byte{}
	f.publish(1, map[string][]byte{"community.x-1.0.0.zip": []byte("a")}, nil, nil)
	if err := m.Sync(ctx); err == nil {
		t.Fatal("another key's index accepted")
	}
}

func TestRevokedVersionsAreNotServed(t *testing.T) {
	f, srv := newFake(t)
	dir := t.TempDir()
	m := mirror(t, srv.URL, f.publicKey(t), dir)
	f.publish(1, map[string][]byte{"community.x-1.0.0.zip": []byte("a")}, []Revoked{{ID: "community.x", Version: "1.0.0", Reason: "bad"}}, nil)
	if err := m.Sync(context.Background()); err != nil {
		t.Fatal(err)
	}
	_, _, ix := m.Signed()
	if m.BundlePath("community.x-1.0.0.zip") != "" || len(ix.Latest()) != 0 {
		t.Fatal("revoked version served")
	}
	if _, err := os.Stat(filepath.Join(dir, "bundles", "community.x-1.0.0.zip")); err == nil {
		t.Fatal("revoked bundle downloaded")
	}
}

func TestParsePublicKeyRejectsOtherCurves(t *testing.T) {
	k, _ := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(&k.PublicKey)
	if _, err := ParsePublicKey(base64.StdEncoding.EncodeToString(der)); err == nil {
		t.Fatal("P-384 accepted")
	}
	// The production key parses.
	if _, err := ParsePublicKey("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEt0Z/3gbK9h78oVewOrMVTasFYG4af/w1D3eXmNJk2gCHgw8rEsrammSJXnMu6VXjp0rkz87UXXVOzaAep6fB+Q=="); err != nil {
		t.Fatal(err)
	}
}
