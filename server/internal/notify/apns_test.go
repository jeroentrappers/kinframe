package notify

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

func TestAPNsSender(t *testing.T) {
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalPKCS8PrivateKey(k)
	p8 := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})

	var got map[string]any
	handler := func(env string) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			// The provider token must verify with the key's public half.
			tok := strings.TrimPrefix(r.Header.Get("authorization"), "bearer ")
			parts := strings.Split(tok, ".")
			sig, _ := base64.RawURLEncoding.DecodeString(parts[2])
			sum := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
			if len(sig) != 64 || !ecdsa.Verify(&k.PublicKey, sum[:], new(big.Int).SetBytes(sig[:32]), new(big.Int).SetBytes(sig[32:])) {
				t.Errorf("%s: bad provider token signature", env)
			}
			if r.Header.Get("apns-topic") != "app.kinframe.family" || r.Header.Get("apns-push-type") != "alert" {
				t.Errorf("%s: headers %v", env, r.Header)
			}
			device := strings.TrimPrefix(r.URL.Path, "/3/device/")
			switch {
			case device == "sandboxtoken" && env == "production", device == "nowhere":
				w.WriteHeader(http.StatusBadRequest)
				_, _ = w.Write([]byte(`{"reason":"BadDeviceToken"}`))
			case device == "gone":
				w.WriteHeader(http.StatusGone)
				_, _ = w.Write([]byte(`{"reason":"Unregistered"}`))
			default:
				_ = json.NewDecoder(r.Body).Decode(&got)
				got["_env"] = env
			}
		}
	}
	prod := httptest.NewServer(handler("production"))
	defer prod.Close()
	sandbox := httptest.NewServer(handler("sandbox"))
	defer sandbox.Close()

	s, err := NewAPNsSender(p8, "KEY1234567", "TEAM123456", "app.kinframe.family")
	if err != nil {
		t.Fatal(err)
	}
	s.Hosts = []string{prod.URL, sandbox.URL}
	payload := []byte(`{"type":"reaction","key":"reaction","title":"Grandma","body":"loved your photo","ids":{"frame_id":"f1"}}`)
	send := func(device string) bool {
		gone, err := s.Send(context.Background(), store.PushEndpoint{Kind: "apns", Endpoint: device}, payload)
		if err != nil {
			t.Fatalf("%s: %v", device, err)
		}
		return gone
	}

	if send("prodtoken") || got["_env"] != "production" {
		t.Fatalf("production token: %v", got)
	}
	alert := got["aps"].(map[string]any)["alert"].(map[string]any)
	if alert["title"] != "Grandma" || alert["body"] != "loved your photo" || got["ids"].(map[string]any)["frame_id"] != "f1" || got["type"] != "reaction" {
		t.Errorf("body: %v", got)
	}
	if send("sandboxtoken") || got["_env"] != "sandbox" {
		t.Errorf("sandbox token not retried on the sandbox: %v", got)
	}
	if !send("gone") {
		t.Error("unregistered token not reported gone")
	}
	if !send("nowhere") {
		t.Error("token unknown to both environments not reported gone")
	}
}
