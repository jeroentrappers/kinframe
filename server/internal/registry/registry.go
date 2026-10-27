// Package registry mirrors the signed community plugin registry
// (docs/plugins.md §7): the index and every bundle it lists, checked against
// the registry's public key, so frames fetch plugin code only from their own
// server and verify it themselves.
package registry

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Limits from §7.1.
const (
	MaxBundle = 512 << 10
	MaxIndex  = 8 << 20
	MaxSig    = 1 << 10
)

// Index is the signed registry index (format 1).
type Index struct {
	Format      int       `json:"format"`
	Sequence    int64     `json:"sequence"`
	GeneratedAt time.Time `json:"generated_at"`
	Plugins     []Plugin  `json:"plugins"`
	Revoked     []Revoked `json:"revoked"`
}

type Plugin struct {
	ID       string    `json:"id"`
	Versions []Version `json:"versions"`
}

type Version struct {
	Version     string                       `json:"version"`
	API         int                          `json:"api"`
	File        string                       `json:"file"`
	Size        int64                        `json:"size"`
	SHA256      string                       `json:"sha256"`
	PublishedAt time.Time                    `json:"published_at"`
	Manifest    map[string]any               `json:"manifest"`
	Locales     map[string]map[string]string `json:"locales,omitempty"`
}

type Revoked struct {
	ID      string `json:"id"`
	Version string `json:"version"`
	Reason  string `json:"reason,omitempty"`
}

// Revoked reports whether version v of plugin id is withdrawn.
func (ix *Index) IsRevoked(id, v string) bool {
	for _, r := range ix.Revoked {
		if r.ID == id && r.Version == v {
			return true
		}
	}
	return false
}

var (
	fileRe   = regexp.MustCompile(`^[a-z0-9][a-z0-9.-]{0,80}-\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?\.zip$`)
	sha256Re = regexp.MustCompile(`^[0-9a-f]{64}$`)
)

// ParsePublicKey reads a base64 SubjectPublicKeyInfo of an ECDSA P-256 key.
func ParsePublicKey(b64 string) (*ecdsa.PublicKey, error) {
	der, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil {
		return nil, err
	}
	k, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, err
	}
	ec, ok := k.(*ecdsa.PublicKey)
	if !ok || ec.Curve.Params().Name != "P-256" {
		return nil, errors.New("registry key must be ECDSA P-256")
	}
	return ec, nil
}

// Verify checks sig (base64 DER ECDSA over SHA-256 of raw) and parses the index.
func Verify(key *ecdsa.PublicKey, raw []byte, sigB64 string) (*Index, error) {
	sig, err := base64.StdEncoding.DecodeString(strings.TrimSpace(sigB64))
	if err != nil {
		return nil, fmt.Errorf("signature: %w", err)
	}
	sum := sha256.Sum256(raw)
	if !ecdsa.VerifyASN1(key, sum[:], sig) {
		return nil, errors.New("registry index signature does not verify")
	}
	var ix Index
	if err := json.Unmarshal(raw, &ix); err != nil {
		return nil, fmt.Errorf("index: %w", err)
	}
	if ix.Format != 1 {
		return nil, fmt.Errorf("index format %d", ix.Format)
	}
	for _, p := range ix.Plugins {
		for _, v := range p.Versions {
			if !fileRe.MatchString(v.File) || !sha256Re.MatchString(v.SHA256) || v.Size <= 0 || v.Size > MaxBundle {
				return nil, fmt.Errorf("index: bad entry %s %s", p.ID, v.Version)
			}
		}
	}
	return &ix, nil
}

// Mirror keeps the newest verified index and its bundles in Dir.
type Mirror struct {
	BaseURL  string // where index.json, index.json.sig and the bundles are (a GitHub release download URL)
	Key      *ecdsa.PublicKey
	Dir      string // <data dir>/plugin-registry
	HTTP     *http.Client
	Interval time.Duration
	Log      *slog.Logger

	mu    sync.RWMutex
	raw   []byte
	sig   string
	index *Index
}

// Load reads the cached index (verified again); a missing cache is fine.
func (m *Mirror) Load() error {
	raw, err := os.ReadFile(filepath.Join(m.Dir, "index.json"))
	if errors.Is(err, os.ErrNotExist) {
		return nil
	} else if err != nil {
		return err
	}
	sig, err := os.ReadFile(filepath.Join(m.Dir, "index.json.sig"))
	if err != nil {
		return err
	}
	ix, err := Verify(m.Key, raw, string(sig))
	if err != nil {
		return err
	}
	m.mu.Lock()
	m.raw, m.sig, m.index = raw, strings.TrimSpace(string(sig)), ix
	m.mu.Unlock()
	return nil
}

// Run syncs now and then every Interval until ctx ends.
func (m *Mirror) Run(ctx context.Context) {
	for {
		if err := m.Sync(ctx); err != nil && ctx.Err() == nil {
			m.Log.Warn("plugin registry sync failed", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(m.Interval):
		}
	}
}

// Sync fetches the index; when it verifies and is newer, downloads the bundles it
// lists that aren't here yet, then makes it current.
func (m *Mirror) Sync(ctx context.Context) error {
	raw, err := m.get(ctx, "index.json", MaxIndex)
	if err != nil {
		return err
	}
	sig, err := m.get(ctx, "index.json.sig", MaxSig)
	if err != nil {
		return err
	}
	ix, err := Verify(m.Key, raw, string(sig))
	if err != nil {
		return err
	}
	m.mu.RLock()
	cur := m.index
	m.mu.RUnlock()
	if cur != nil && ix.Sequence <= cur.Sequence {
		if ix.Sequence < cur.Sequence {
			return fmt.Errorf("registry index sequence %d is older than %d", ix.Sequence, cur.Sequence)
		}
		return nil
	}
	if err := os.MkdirAll(filepath.Join(m.Dir, "bundles"), 0o755); err != nil {
		return err
	}
	for _, p := range ix.Plugins {
		for _, v := range p.Versions {
			if ix.IsRevoked(p.ID, v.Version) {
				continue
			}
			if err := m.fetchBundle(ctx, v); err != nil {
				return fmt.Errorf("%s %s: %w", p.ID, v.Version, err)
			}
		}
	}
	if err := writeAtomic(filepath.Join(m.Dir, "index.json.sig"), []byte(strings.TrimSpace(string(sig)))); err != nil {
		return err
	}
	if err := writeAtomic(filepath.Join(m.Dir, "index.json"), raw); err != nil {
		return err
	}
	m.mu.Lock()
	m.raw, m.sig, m.index = raw, strings.TrimSpace(string(sig)), ix
	m.mu.Unlock()
	m.Log.Info("plugin registry updated", "sequence", ix.Sequence, "plugins", len(ix.Plugins))
	return nil
}

func (m *Mirror) fetchBundle(ctx context.Context, v Version) error {
	path := filepath.Join(m.Dir, "bundles", v.File)
	if b, err := os.ReadFile(path); err == nil && checksum(b) == v.SHA256 {
		return nil
	}
	b, err := m.get(ctx, v.File, MaxBundle)
	if err != nil {
		return err
	}
	if int64(len(b)) != v.Size || checksum(b) != v.SHA256 {
		return errors.New("bundle does not match the signed index")
	}
	return writeAtomic(path, b)
}

func (m *Mirror) get(ctx context.Context, name string, max int64) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(m.BaseURL, "/")+"/"+name, nil)
	if err != nil {
		return nil, err
	}
	res, err := m.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GET %s: HTTP %d", name, res.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(res.Body, max+1))
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > max {
		return nil, fmt.Errorf("GET %s: larger than %d bytes", name, max)
	}
	return b, nil
}

// Signed returns the current index exactly as signed, with its signature; nil before the first sync.
func (m *Mirror) Signed() ([]byte, string, *Index) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.raw, m.sig, m.index
}

// BundlePath returns the file of a bundle the current index lists (and doesn't revoke), or "".
func (m *Mirror) BundlePath(file string) string {
	m.mu.RLock()
	ix := m.index
	m.mu.RUnlock()
	if ix == nil || !fileRe.MatchString(file) {
		return ""
	}
	for _, p := range ix.Plugins {
		for _, v := range p.Versions {
			if v.File == file && !ix.IsRevoked(p.ID, v.Version) {
				return filepath.Join(m.Dir, "bundles", file)
			}
		}
	}
	return ""
}

// Latest is each plugin's newest version for plugin API 1 that isn't revoked.
func (ix *Index) Latest() []Version {
	var out []Version
	for _, p := range ix.Plugins {
		for i := len(p.Versions) - 1; i >= 0; i-- {
			v := p.Versions[i]
			if v.API == 1 && !ix.IsRevoked(p.ID, v.Version) {
				out = append(out, v)
				break
			}
		}
	}
	return out
}

// Find returns version v of plugin id (revoked or not), or nil.
func (ix *Index) Find(id, v string) *Version {
	for _, p := range ix.Plugins {
		if p.ID != id {
			continue
		}
		for i := range p.Versions {
			if p.Versions[i].Version == v {
				return &p.Versions[i]
			}
		}
	}
	return nil
}

func checksum(b []byte) string {
	s := sha256.Sum256(b)
	return hex.EncodeToString(s[:])
}

func writeAtomic(path string, b []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return err
	}
	if _, err := io.Copy(tmp, bytes.NewReader(b)); err != nil {
		tmp.Close()
		os.Remove(tmp.Name())
		return err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmp.Name())
		return err
	}
	return os.Rename(tmp.Name(), path)
}
