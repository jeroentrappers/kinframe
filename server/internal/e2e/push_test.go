package e2e

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
	"github.com/jeroentrappers/kinframe/server/internal/notify/pushtest"
)

func withWebPush(t *testing.T) option {
	k, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return func(c *config.Config) {
		c.PushEnabled = true
		c.VAPIDPublicKey = base64.RawURLEncoding.EncodeToString(k.PublicKey().Bytes())
		c.VAPIDPrivateKey = base64.RawURLEncoding.EncodeToString(k.Bytes())
		c.VAPIDSubject = "mailto:ops@example.org"
	}
}

type pushMsg struct {
	Type  string            `json:"type"`
	Key   string            `json:"key"`
	Title string            `json:"title"`
	Body  string            `json:"body"`
	IDs   map[string]string `json:"ids"`
}

// TestWebPush: a sender without an open websocket gets encrypted browser
// pushes for delivery and reactions, in their language; expired
// subscriptions are removed.
func TestWebPush(t *testing.T) {
	requireMedia(t)
	e := newEnv(t, withWebPush(t))

	disc := e.get("/.well-known/kinframe", "").m(t)
	if k, _ := disc["webpush_public_key"].(string); len(k) != 87 {
		t.Fatalf("discovery webpush_public_key: %v", disc["webpush_public_key"])
	}

	br := pushtest.NewBrowser()
	msgs := make(chan pushMsg, 10)
	var status atomic.Int32
	status.Store(http.StatusCreated)
	svc := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		if r.Header.Get("Authorization") == "" || r.Header.Get("Content-Encoding") != "aes128gcm" {
			t.Errorf("push request headers: %v", r.Header)
		}
		plain, err := br.Decrypt(body)
		if err != nil {
			t.Errorf("decrypt: %v", err)
		}
		var m pushMsg
		_ = json.Unmarshal(plain, &m)
		msgs <- m
		w.WriteHeader(int(status.Load()))
	}))
	defer svc.Close()
	next := func(what string) pushMsg {
		t.Helper()
		select {
		case m := <-msgs:
			return m
		case <-time.After(10 * time.Second):
			t.Fatalf("no push for %s", what)
			return pushMsg{}
		}
	}

	u := e.user("pia@x.org", "Pia")
	e.expect(req{method: "POST", path: "/v1/me/push-endpoints", token: u.token, body: map[string]any{
		"kind": "webpush", "endpoint": svc.URL + "/push/1", "locale": "nl-BE", "device_name": "Firefox on Linux",
		"webpush_keys": map[string]any{"p256dh": br.P256dh(), "auth": br.AuthB64()},
	}}, 200)
	d, frameID := e.claim(u)
	up := e.upload(u, mediatest.JPEG(800, 600, 1, "", false), "a.jpg", "image/jpeg")
	sendID := e.sendOne(u, []string{frameID}, map[string]any{"upload_id": up})
	s := e.waitSend(u, sendID)
	pid := s["frames"].([]any)[0].(map[string]any)["placements"].([]any)[0].(map[string]any)["placement_id"].(string)

	// Delivery: one push once everything of the send reached the frame.
	e.expect(req{method: "POST", path: "/v1/device/acks", token: d.token, body: map[string]any{"delivered": []string{pid}}}, 204)
	m := next("delivered")
	if m.Key != "delivered" || m.Title != "Afgeleverd" || m.IDs["send_id"] != sendID || m.IDs["frame_id"] != frameID {
		t.Fatalf("delivered push: %+v", m)
	}
	// Seen after delivered does not push "delivered" again.
	e.expect(req{method: "POST", path: "/v1/device/acks", token: d.token, body: map[string]any{
		"seen": []map[string]any{{"placement_id": pid, "at": time.Now().UTC().Format(time.RFC3339)}}}}, 204)

	// Reaction: localized, with ids to open the send.
	e.expect(req{method: "PUT", path: "/v1/device/placements/" + pid + "/reaction", token: d.token, body: map[string]any{"reaction": "love"}}, 204)
	m = next("reaction")
	if m.Key != "reaction" || m.Type != "reaction" || m.Title != "Nieuwe reactie" || m.IDs["send_id"] != sendID || m.IDs["placement_id"] != pid {
		t.Fatalf("reaction push: %+v", m)
	}

	// With the web app open (websocket), events go there instead of push.
	ws := e.dialWS("/v1/ws?access_token="+u.token, "")
	e.expect(req{method: "PUT", path: "/v1/device/placements/" + pid + "/reaction", token: d.token, body: map[string]any{"reaction": "wow"}}, 204)
	ws.await("reaction", 5*time.Second)
	select {
	case m := <-msgs:
		t.Fatalf("pushed although connected: %+v", m)
	case <-time.After(300 * time.Millisecond):
	}
	ws.c.CloseNow()
	eventually(t, 5*time.Second, "websocket closed", func() bool { return !e.app.Hub.UserConnected(uuid.MustParse(u.id)) })

	// The push service says the subscription is gone: it is deleted.
	status.Store(http.StatusGone)
	e.expect(req{method: "POST", path: "/v1/device/placements/" + pid + "/replies", token: d.token, body: map[string]any{"preset": "beautiful"}}, 204)
	if m = next("reply"); m.Key != "reply" || m.Title != "Nieuw antwoord" {
		t.Fatalf("reply push: %+v", m)
	}
	eventually(t, 5*time.Second, "expired endpoint removed", func() bool {
		var eps []any
		e.get("/v1/me/push-endpoints", u.token).json(t, &eps)
		return len(eps) == 0
	})
}
