// Package crashtest is a stand-in Sentry/GlitchTip server for tests: it accepts envelopes and keeps their events.
package crashtest

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// Ingest records the events posted to it.
type Ingest struct {
	srv    *httptest.Server
	mu     sync.Mutex
	events []map[string]any
	raw    [][]byte
}

// New starts an ingest server; it stops when the test ends.
func New(t testing.TB) *Ingest {
	in := &Ingest{}
	in.srv = httptest.NewServer(http.HandlerFunc(in.serve))
	t.Cleanup(in.srv.Close)
	return in
}

// DSN points an SDK at this server (project 1).
func (in *Ingest) DSN() string {
	return strings.Replace(in.srv.URL, "http://", "http://public@", 1) + "/1"
}

func (in *Ingest) serve(w http.ResponseWriter, r *http.Request) {
	var body io.Reader = r.Body
	if r.Header.Get("Content-Encoding") == "gzip" {
		gz, err := gzip.NewReader(r.Body)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		body = gz
	}
	b, _ := io.ReadAll(body)
	in.mu.Lock()
	in.raw = append(in.raw, b)
	in.events = append(in.events, Events(b)...)
	in.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{}`))
}

// Events parses the error events out of an envelope (header line, then item header and payload pairs).
func Events(envelope []byte) []map[string]any {
	var out []map[string]any
	sc := bufio.NewScanner(bytes.NewReader(envelope))
	sc.Buffer(make([]byte, 1<<20), 16<<20)
	sc.Scan() // envelope header
	for sc.Scan() {
		var item struct {
			Type string `json:"type"`
		}
		if json.Unmarshal(sc.Bytes(), &item) != nil {
			continue
		}
		if !sc.Scan() {
			break
		}
		if item.Type != "event" {
			continue
		}
		var ev map[string]any
		if json.Unmarshal(sc.Bytes(), &ev) == nil {
			out = append(out, ev)
		}
	}
	return out
}

// Wait returns the events once there are at least n, or fails the test after a few seconds.
func (in *Ingest) Wait(t testing.TB, n int) []map[string]any {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		in.mu.Lock()
		got := append([]map[string]any(nil), in.events...)
		in.mu.Unlock()
		if len(got) >= n {
			return got
		}
		if time.Now().After(deadline) {
			t.Fatalf("got %d crash reports, want %d", len(got), n)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// Raw is every envelope body received, for asserting on what went over the wire.
func (in *Ingest) Raw() string {
	in.mu.Lock()
	defer in.mu.Unlock()
	return string(bytes.Join(in.raw, []byte("\n")))
}
