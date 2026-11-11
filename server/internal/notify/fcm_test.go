package notify

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

func testServiceAccount(t *testing.T, tokenURL string) []byte {
	t.Helper()
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	der, _ := x509.MarshalPKCS8PrivateKey(k)
	b, _ := json.Marshal(map[string]string{
		"type": "service_account", "project_id": "kf-test", "client_email": "push@kf-test.iam.gserviceaccount.com",
		"private_key": string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})), "token_uri": tokenURL,
	})
	return b
}

func TestFCMSender(t *testing.T) {
	tokens := 0
	var got map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/token":
			b, _ := io.ReadAll(r.Body)
			f, _ := url.ParseQuery(string(b))
			if f.Get("grant_type") != "urn:ietf:params:oauth:grant-type:jwt-bearer" || strings.Count(f.Get("assertion"), ".") != 2 {
				t.Errorf("bad token request %v", f)
			}
			tokens++
			_, _ = w.Write([]byte(`{"access_token":"at","expires_in":3600}`))
		case "/v1/projects/kf-test/messages:send":
			if r.Header.Get("Authorization") != "Bearer at" {
				t.Errorf("auth %q", r.Header.Get("Authorization"))
			}
			_ = json.NewDecoder(r.Body).Decode(&got)
			if got["message"].(map[string]any)["token"] == "dead" {
				w.WriteHeader(http.StatusNotFound)
				_, _ = w.Write([]byte(`{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}`))
				return
			}
			_, _ = w.Write([]byte(`{"name":"projects/kf-test/messages/1"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	s, err := NewFCMSender(testServiceAccount(t, srv.URL+"/token"))
	if err != nil {
		t.Fatal(err)
	}
	s.SendBase = srv.URL
	payload := []byte(`{"type":"reaction","key":"reaction","title":"Grandma","body":"loved your photo","ids":{"frame_id":"f1","send_id":"s1"}}`)
	for i := 0; i < 2; i++ {
		gone, err := s.Send(context.Background(), store.PushEndpoint{Kind: "fcm", Endpoint: "tok"}, payload)
		if gone || err != nil {
			t.Fatalf("send: gone=%v err=%v", gone, err)
		}
	}
	if tokens != 1 {
		t.Errorf("token fetched %d times, want 1 (cached)", tokens)
	}
	data := got["message"].(map[string]any)["data"].(map[string]any)
	for k, v := range map[string]string{"type": "reaction", "title": "Grandma", "body": "loved your photo", "frame_id": "f1", "send_id": "s1"} {
		if data[k] != v {
			t.Errorf("data[%s] = %v, want %s", k, data[k], v)
		}
	}
	gone, _ := s.Send(context.Background(), store.PushEndpoint{Kind: "fcm", Endpoint: "dead"}, payload)
	if !gone {
		t.Error("unregistered token not reported gone")
	}
}

func TestNewFCMSenderRejectsOtherKeys(t *testing.T) {
	if _, err := NewFCMSender([]byte(`{"type":"authorized_user"}`)); err == nil {
		t.Error("accepted a non service account key")
	}
}
