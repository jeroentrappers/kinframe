package notify

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/base64"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/golang-jwt/jwt/v5"

	"github.com/jeroentrappers/kinframe/server/internal/notify/pushtest"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

func endpointFor(b *pushtest.Browser, url string) store.PushEndpoint {
	p, a := b.P256dh(), b.AuthB64()
	return store.PushEndpoint{Kind: "webpush", Endpoint: url, P256dh: &p, Auth: &a}
}

func vapidPair(t *testing.T) (string, string) {
	k, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return base64.RawURLEncoding.EncodeToString(k.PublicKey().Bytes()), base64.RawURLEncoding.EncodeToString(k.Bytes())
}

func TestWebPushSend(t *testing.T) {
	pub, priv := vapidPair(t)
	var got struct {
		body []byte
		hdr  http.Header
	}
	status := http.StatusCreated
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got.body, _ = io.ReadAll(r.Body)
		got.hdr = r.Header.Clone()
		w.WriteHeader(status)
	}))
	defer srv.Close()

	br := pushtest.NewBrowser()
	s := WebPushSender{PublicKey: pub, PrivateKey: priv, Subject: "mailto:ops@example.org"}
	payload := []byte(`{"type":"reaction","title":"New reaction","body":"Oma reacted to your photo"}`)
	gone, err := s.Send(context.Background(), endpointFor(br, srv.URL+"/push/abc"), payload)
	if err != nil || gone {
		t.Fatalf("send: gone=%v err=%v", gone, err)
	}
	if plain, err := br.Decrypt(got.body); err != nil || !bytes.Equal(plain, payload) {
		t.Fatalf("payload %q %v", plain, err)
	}
	if got.hdr.Get("Content-Encoding") != "aes128gcm" || got.hdr.Get("TTL") != "86400" {
		t.Errorf("headers: %v", got.hdr)
	}

	// RFC 8292: "vapid t=<JWT>, k=<public key>", JWT signed by that key, aud = push service origin.
	authz := got.hdr.Get("Authorization")
	tok, k, ok := strings.Cut(strings.TrimPrefix(authz, "vapid t="), ", k=")
	if !ok || k != pub {
		t.Fatalf("authorization %q", authz)
	}
	raw, _ := base64.RawURLEncoding.DecodeString(pub)
	x, y := elliptic.Unmarshal(elliptic.P256(), raw) //nolint:staticcheck // test helper for a raw point
	key := &ecdsa.PublicKey{Curve: elliptic.P256(), X: new(big.Int).Set(x), Y: new(big.Int).Set(y)}
	claims := jwt.MapClaims{}
	if _, err := jwt.ParseWithClaims(tok, claims, func(*jwt.Token) (any, error) { return key, nil }, jwt.WithValidMethods([]string{"ES256"})); err != nil {
		t.Fatalf("vapid jwt: %v", err)
	}
	if claims["aud"] != srv.URL || claims["sub"] != "mailto:ops@example.org" {
		t.Errorf("claims %v", claims)
	}

	// Expired subscriptions are reported as gone.
	for _, st := range []int{http.StatusGone, http.StatusNotFound} {
		status = st
		if gone, err := s.Send(context.Background(), endpointFor(br, srv.URL), payload); !gone || err != nil {
			t.Errorf("%d: gone=%v err=%v", st, gone, err)
		}
	}
	status = http.StatusTooManyRequests
	if gone, err := s.Send(context.Background(), endpointFor(br, srv.URL), payload); gone || err == nil {
		t.Errorf("429: gone=%v err=%v", gone, err)
	}
	// An endpoint without keys can never be delivered to.
	if gone, err := s.Send(context.Background(), store.PushEndpoint{Kind: "webpush", Endpoint: srv.URL}, payload); !gone || err != ErrNoKeys {
		t.Errorf("no keys: gone=%v err=%v", gone, err)
	}
}

func TestMultiSender(t *testing.T) {
	calls := map[string]int{}
	rec := func(kind string) Sender { return senderFunc(func() { calls[kind]++ }) }
	m := MultiSender{"webpush": rec("webpush"), "unifiedpush": rec("unifiedpush")}
	for _, k := range []string{"webpush", "unifiedpush", "fcm", "apns"} {
		if gone, err := m.Send(context.Background(), store.PushEndpoint{Kind: k}, nil); gone || err != nil {
			t.Errorf("%s: %v %v", k, gone, err)
		}
	}
	if calls["webpush"] != 1 || calls["unifiedpush"] != 1 || len(calls) != 2 {
		t.Errorf("calls %v", calls)
	}
}

type senderFunc func()

func (f senderFunc) Send(context.Context, store.PushEndpoint, []byte) (bool, error) {
	f()
	return false, nil
}

func TestTextDoesNotMutateArgs(t *testing.T) {
	args := map[string]string{"frame": "Oma", "preset": "beautiful"}
	if _, b := Text("nl", "reply", args); b != "Oma: Prachtig!" {
		t.Errorf("nl: %q", b)
	}
	if _, b := Text("en", "reply", args); b != "Oma: Beautiful!" {
		t.Errorf("en after nl: %q", b)
	}
}
