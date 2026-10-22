package notify

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	webpush "github.com/SherClockHolmes/webpush-go"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// WebPushSender delivers browser push messages: the payload is encrypted for
// the subscription (RFC 8291, aes128gcm) and the request carries a VAPID
// authorization (RFC 8292) signed with the server's key pair.
type WebPushSender struct {
	PublicKey  string // base64url, uncompressed P-256
	PrivateKey string // base64url
	Subject    string // mailto: or https: contact
	Client     *http.Client
	TTL        time.Duration // how long push services keep undelivered messages (default 24 h)
}

// ErrNoKeys is returned for webpush endpoints registered without p256dh/auth.
var ErrNoKeys = errors.New("webpush endpoint has no encryption keys")

func (s WebPushSender) Send(ctx context.Context, ep store.PushEndpoint, payload []byte) (bool, error) {
	if ep.P256dh == nil || ep.Auth == nil || *ep.P256dh == "" || *ep.Auth == "" {
		return true, ErrNoKeys // unusable forever: drop it
	}
	client := s.Client
	if client == nil {
		client = &http.Client{Timeout: 15 * time.Second}
	}
	ttl := s.TTL
	if ttl == 0 {
		ttl = 24 * time.Hour
	}
	sub := &webpush.Subscription{Endpoint: ep.Endpoint, Keys: webpush.Keys{P256dh: *ep.P256dh, Auth: *ep.Auth}}
	resp, err := webpush.SendNotificationWithContext(ctx, payload, sub, &webpush.Options{
		HTTPClient:      client,
		Subscriber:      strings.TrimPrefix(s.Subject, "mailto:"), // the library adds mailto: to non-https subjects
		VAPIDPublicKey:  s.PublicKey,
		VAPIDPrivateKey: s.PrivateKey,
		TTL:             int(ttl.Seconds()),
		Urgency:         webpush.UrgencyNormal,
	})
	if err != nil {
		return false, err
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	resp.Body.Close()
	return gone(resp.StatusCode)
}

// gone maps a push service status: 404/410 mean the subscription expired or
// was revoked and must be deleted.
func gone(status int) (bool, error) {
	if status == http.StatusNotFound || status == http.StatusGone {
		return true, nil
	}
	if status >= 300 {
		return false, &statusError{status}
	}
	return false, nil
}

// MultiSender routes each endpoint to the sender for its kind. Kinds without a
// sender (fcm, apns until store credentials exist) are skipped.
type MultiSender map[string]Sender

func (m MultiSender) Send(ctx context.Context, ep store.PushEndpoint, payload []byte) (bool, error) {
	s, ok := m[ep.Kind]
	if !ok || s == nil {
		return false, nil
	}
	return s.Send(ctx, ep, payload)
}
