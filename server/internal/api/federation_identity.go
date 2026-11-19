package api

import (
	"context"
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/federation"
	"github.com/jeroentrappers/kinframe/server/internal/netguard"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/secrets"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Families on different servers (docs/spec/multi-server.md, README §5.22): this server's identity and keys, the
// well-known document, the cache of other servers and the verification of signed requests.

type federationState struct {
	client   *federation.Client
	verifier federation.Verifier
	sealer   *secrets.Sealer // nil: the private key is stored as is (no KINFRAME_SECRETS_KEY)

	mu       sync.Mutex
	serverID string
	current  *federation.Key
	keys     []store.FederationKey
	loadedAt time.Time

	kickMu sync.Mutex
	kicked map[uuid.UUID]bool // follows to pull at once (pings, new subscriptions)
	wake   chan struct{}
}

// setupFederation builds the federation client. Other servers are reached through a guard of their own:
// public addresses only, whatever the photo sources allow (README §5.22).
func (s *Server) setupFederation() error {
	g, err := netguard.New(netguard.Config{PrivateNetworks: netguard.ModeOff, CAFile: s.cfg.SourcesCAFile, PublicURL: s.cfg.PublicURL,
		AllowLoopback: s.cfg.Federation.AllowLoopback})
	if err != nil {
		return fmt.Errorf("federation guard: %w", err)
	}
	st := &federationState{kicked: map[uuid.UUID]bool{}, wake: make(chan struct{}, 1)}
	st.verifier.Now = s.now
	if len(s.cfg.SecretsKey) > 0 {
		if st.sealer, err = secrets.New(s.cfg.SecretsKey, nil, "kinframe federation keys v1", ""); err != nil {
			return err
		}
	}
	st.client = &federation.Client{
		HTTP: g.Client(netguard.Options{Timeout: 2 * time.Minute, MaxRedirects: -1}),
		Self: s.cfg.PublicURL,
		Key:  s.federationKey,
		Now:  s.now,
	}
	s.fed = st
	return nil
}

func (s *Server) federationOn() bool { return s.cfg.Federation.Enabled }

// federationServerID is discovery `server_id` (made at first use).
func (s *Server) federationServerID(ctx context.Context) string {
	s.fed.mu.Lock()
	id := s.fed.serverID
	s.fed.mu.Unlock()
	if id != "" {
		return id
	}
	id, err := s.st.FederationServerID(ctx)
	if err != nil {
		s.log.Warn("federation: server id", "err", err)
		return ""
	}
	s.fed.mu.Lock()
	s.fed.serverID = id
	s.fed.mu.Unlock()
	return id
}

// federationKeys returns the current signing key and every valid key, making the first key when there is none.
func (s *Server) federationKeys(ctx context.Context) (*federation.Key, []store.FederationKey, error) {
	s.fed.mu.Lock()
	defer s.fed.mu.Unlock()
	if s.fed.current != nil && time.Since(s.fed.loadedAt) < time.Minute {
		return s.fed.current, s.fed.keys, nil
	}
	for attempt := 0; attempt < 3; attempt++ {
		keys, err := s.st.FederationKeys(ctx)
		if err != nil {
			return nil, nil, err
		}
		if len(keys) > 0 && keys[0].IsCurrent {
			k, err := s.openFederationKey(&keys[0])
			if err != nil {
				return nil, nil, err
			}
			s.fed.current, s.fed.keys, s.fed.loadedAt = k, keys, time.Now()
			return k, keys, nil
		}
		if err := s.addFederationKey(ctx); err != nil {
			return nil, nil, err
		}
	}
	return nil, nil, errors.New("federation: no signing key")
}

// federationKey is the current signing key (federation.Client.Key).
func (s *Server) federationKey(ctx context.Context) (*federation.Key, error) {
	k, _, err := s.federationKeys(ctx)
	return k, err
}

func (s *Server) addFederationKey(ctx context.Context) error {
	k, err := federation.NewKey()
	if err != nil {
		return err
	}
	seed := k.Private.Seed()
	enc := seed
	if s.fed.sealer != nil {
		enc = s.fed.sealer.Seal(string(seed))
	}
	return s.st.AddFederationKey(ctx, store.FederationKey{KeyID: k.ID, PublicKey: k.Public, PrivateKeyEnc: enc}, federation.RotatedKeyValidity)
}

// rotateFederationKey replaces the current key; the old one stays published and accepted for 7 days.
func (s *Server) rotateFederationKey(ctx context.Context) error {
	s.fed.mu.Lock()
	defer s.fed.mu.Unlock()
	if err := s.addFederationKey(ctx); err != nil {
		return err
	}
	s.fed.current = nil
	return nil
}

func (s *Server) openFederationKey(k *store.FederationKey) (*federation.Key, error) {
	seed := k.PrivateKeyEnc
	if len(seed) != ed25519.SeedSize {
		if s.fed.sealer == nil {
			return nil, errors.New("federation: the signing key is sealed but KINFRAME_SECRETS_KEY is not set")
		}
		plain, err := s.fed.sealer.Open(seed)
		if err != nil {
			return nil, fmt.Errorf("federation: signing key: %w", err)
		}
		seed = []byte(plain)
	}
	key, err := federation.KeyFromSeed(seed)
	if err != nil {
		return nil, err
	}
	if key.ID != k.KeyID {
		return nil, errors.New("federation: signing key does not match its id")
	}
	return key, nil
}

// federationInfo is the `federation` object of the well-known document.
func (s *Server) federationInfo(ctx context.Context) *gen.FederationInfo {
	if !s.federationOn() {
		return &gen.FederationInfo{Enabled: false}
	}
	cur, keys, err := s.federationKeys(ctx)
	if err != nil {
		s.log.Warn("federation: keys", "err", err)
		return &gen.FederationInfo{Enabled: false}
	}
	info := &gen.FederationInfo{Enabled: true, KeyId: ptr(cur.ID), PublicKey: ptr(federation.EncodePublic(cur.Public))}
	list := make([]struct {
		ExpiresAt *time.Time `json:"expires_at,omitempty"`
		KeyId     string     `json:"key_id"`
		PublicKey string     `json:"public_key"`
	}, 0, len(keys))
	for _, k := range keys {
		list = append(list, struct {
			ExpiresAt *time.Time `json:"expires_at,omitempty"`
			KeyId     string     `json:"key_id"`
			PublicKey string     `json:"public_key"`
		}{ExpiresAt: k.ExpiresAt, KeyId: k.KeyID, PublicKey: federation.EncodePublic(k.PublicKey)})
	}
	info.Keys = &list
	info.Endpoints = &struct {
		Ping          *string `json:"ping,omitempty"`
		Subscriptions *string `json:"subscriptions,omitempty"`
	}{Ping: ptr(s.cfg.PublicURL + "/federation/v1/ping"), Subscriptions: ptr(s.cfg.PublicURL + "/federation/v1/subscriptions")}
	return info
}

// ---------------------------------------------------------------- trust

// hostAllowed applies KINFRAME_FEDERATION_ALLOW (empty: every host).
func (s *Server) hostAllowed(host string) bool {
	if len(s.cfg.Federation.Allow) == 0 {
		return true
	}
	host = strings.ToLower(host)
	name := host
	if i := strings.LastIndex(host, ":"); i > 0 && !strings.HasSuffix(host, "]") {
		name = host[:i]
	}
	for _, a := range s.cfg.Federation.Allow {
		if a == host || a == name {
			return true
		}
	}
	return false
}

// federationRefusal says why this server won't talk to host (nil: it will). feedID adds the family's blocks.
func (s *Server) federationRefusal(ctx context.Context, host string, feedID *uuid.UUID) error {
	if !s.federationOn() {
		return problem.New(problem.Forbidden, "federation is turned off on this server")
	}
	if !s.hostAllowed(host) {
		return problem.New(problem.Forbidden, "this server does not federate with that server")
	}
	blocked, err := s.st.ServerBlocked(ctx, s.st.Q(), host, feedID)
	if err != nil {
		return err
	}
	if blocked {
		return problem.New(problem.Forbidden, "that server is blocked")
	}
	return nil
}

// ---------------------------------------------------------------- other servers

// documentMaxAge: other servers' documents are refreshed daily (and on a signature failure).
const documentMaxAge = 24 * time.Hour

// remoteServer returns what this server knows about base, fetching its well-known document when it has none, it
// is older than a day, or refresh is set. A failed refresh keeps the last good document.
func (s *Server) remoteServer(ctx context.Context, base string, refresh bool) (*store.RemoteServer, error) {
	base, host, err := federation.NormalizeBaseURL(base)
	if err != nil {
		return nil, err
	}
	rs, err := s.st.EnsureRemoteServer(ctx, base, host)
	if err != nil {
		return nil, err
	}
	if !refresh && rs.Document != nil && rs.FetchedAt != nil && s.now().Sub(*rs.FetchedAt) < documentMaxAge {
		return rs, nil
	}
	doc, err := s.fed.client.FetchDocument(ctx, base)
	if err != nil {
		s.st.RemoteServerFailed(ctx, rs.ID, err.Error())
		if rs.Document != nil {
			return rs, nil
		}
		return nil, err
	}
	raw, _ := json.Marshal(doc)
	keys, _ := json.Marshal(doc.Keys())
	name := doc.ServerName
	if name == "" {
		name = host
	}
	if err := s.st.SaveRemoteDocument(ctx, rs.ID, doc.ServerID, name, raw, keys); err != nil {
		return nil, err
	}
	return s.st.RemoteServerByID(ctx, s.st.Q(), rs.ID)
}

// remoteDocument decodes a server's cached document.
func remoteDocument(rs *store.RemoteServer) *federation.Document {
	var d federation.Document
	if rs.Document == nil || json.Unmarshal(rs.Document, &d) != nil {
		return nil
	}
	return &d
}

// remoteKey finds keyID among a server's published keys (not past its expiry).
func (s *Server) remoteKey(rs *store.RemoteServer, keyID string) ed25519.PublicKey {
	var keys []federation.PublishedKey
	if json.Unmarshal(rs.Keys, &keys) != nil {
		return nil
	}
	for _, k := range keys {
		if k.KeyID != keyID || (k.ExpiresAt != nil && k.ExpiresAt.Before(s.now())) {
			continue
		}
		if pub, err := federation.DecodePublic(k.PublicKey); err == nil {
			return pub
		}
	}
	return nil
}

// ---------------------------------------------------------------- incoming requests

const maxFederationBody = 2 << 20

// verifiedServer checks a signed federation request (README §5.22 "Signatures") and returns the caller and the
// raw body. On failure it has written the problem and returns nil.
func (s *Server) verifiedServer(w http.ResponseWriter, r *http.Request) (*store.RemoteServer, []byte) {
	ctx := r.Context()
	if !s.federationOn() {
		problem.Write(w, r, problem.New(problem.Forbidden, "federation is turned off on this server"))
		return nil, nil
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxFederationBody+1))
	if err != nil || len(body) > maxFederationBody {
		problem.Write(w, r, problem.New(problem.ValidationFailed, "body too large").WithStatus(http.StatusRequestEntityTooLarge))
		return nil, nil
	}
	var caller *store.RemoteServer
	lookup := func(serverURL, keyID string) (ed25519.PublicKey, error) {
		_, host, err := federation.NormalizeBaseURL(serverURL)
		if err != nil {
			return nil, federation.ErrMalformed
		}
		if err := s.federationRefusal(ctx, host, nil); err != nil {
			return nil, err
		}
		rs, err := s.remoteServer(ctx, serverURL, false)
		if err != nil {
			return nil, federation.ErrUnknownKey
		}
		if pub := s.remoteKey(rs, keyID); pub != nil {
			caller = rs
			return pub, nil
		}
		// Unknown key: refetch the document once (a rotation), unless it was fetched just now.
		if rs.FetchedAt != nil && s.now().Sub(*rs.FetchedAt) < 10*time.Second {
			return nil, federation.ErrUnknownKey
		}
		if rs, err = s.remoteServer(ctx, serverURL, true); err != nil {
			return nil, federation.ErrUnknownKey
		}
		if pub := s.remoteKey(rs, keyID); pub != nil {
			caller = rs
			return pub, nil
		}
		return nil, federation.ErrUnknownKey
	}
	if _, _, err := s.fed.verifier.Verify(r, body, lookup); err != nil {
		var p *problem.Problem
		if errors.As(err, &p) {
			problem.Write(w, r, p)
			return nil, nil
		}
		s.log.Info("federation: refused request", "path", r.URL.Path, "server", r.Header.Get(federation.HeaderServer), "err", err)
		problem.Write(w, r, problem.New(problem.Unauthorized, "the request is not signed correctly"))
		return nil, nil
	}
	s.st.TouchRemoteServer(ctx, caller.ID)
	return caller, body
}

func writeFederationJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// remoteServerJSON renders another server.
func remoteServerJSON(base, host string, serverID, name *string) gen.RemoteServer {
	out := gen.RemoteServer{ServerUrl: base, Host: ptr(host), ServerId: serverID, ServerName: host}
	if name != nil && *name != "" {
		out.ServerName = *name
	}
	return out
}

// remoteLabel is "Oma's frame (kinframe.appmire.be)".
func remoteLabel(frame, server string) string { return frame + " (" + server + ")" }
