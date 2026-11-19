package notify

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// APNsSender delivers alerts to the iOS app straight through Apple Push
// Notification service, with token-based authentication: a .p8 key (ES256)
// from the Apple Developer account. It never expires; the signed provider
// token is refreshed every 50 minutes.
//
// Device tokens don't say which environment they belong to: App Store and
// TestFlight builds get production tokens, debug builds sandbox tokens. A token
// production rejects as BadDeviceToken is retried on the sandbox.
type APNsSender struct {
	KeyID    string
	TeamID   string
	Topic    string // the app's bundle identifier
	Key      *ecdsa.PrivateKey
	Hosts    []string // default production, then sandbox
	Client   *http.Client
	Interval time.Duration // token refresh, default 50 min

	mu     sync.Mutex
	token  string
	issued time.Time
}

const (
	apnsProduction = "https://api.push.apple.com"
	apnsSandbox    = "https://api.sandbox.push.apple.com"
)

// NewAPNsSender parses a .p8 key (PKCS#8 PEM).
func NewAPNsSender(p8 []byte, keyID, teamID, topic string) (*APNsSender, error) {
	if keyID == "" || teamID == "" || topic == "" {
		return nil, errors.New("apns: key ID, team ID and topic are required")
	}
	block, _ := pem.Decode(p8)
	if block == nil {
		return nil, errors.New("apns: no PEM private key")
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("apns: %w", err)
	}
	ek, ok := k.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("apns: key is not an EC key")
	}
	return &APNsSender{KeyID: keyID, TeamID: teamID, Topic: topic, Key: ek}, nil
}

func (s *APNsSender) providerToken() (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	interval := s.Interval
	if interval == 0 {
		interval = 50 * time.Minute
	}
	if s.token != "" && time.Since(s.issued) < interval {
		return s.token, nil
	}
	now := time.Now()
	enc := func(v any) string { b, _ := json.Marshal(v); return base64.RawURLEncoding.EncodeToString(b) }
	unsigned := enc(map[string]string{"alg": "ES256", "kid": s.KeyID}) + "." + enc(map[string]any{"iss": s.TeamID, "iat": now.Unix()})
	sum := sha256.Sum256([]byte(unsigned))
	r, sv, err := ecdsa.Sign(rand.Reader, s.Key, sum[:])
	if err != nil {
		return "", err
	}
	sig := make([]byte, 64) // JWS ES256: r || s, 32 bytes each
	r.FillBytes(sig[:32])
	sv.FillBytes(sig[32:])
	s.token, s.issued = unsigned+"."+base64.RawURLEncoding.EncodeToString(sig), now
	return s.token, nil
}

func (s *APNsSender) resetToken() {
	s.mu.Lock()
	s.token = ""
	s.mu.Unlock()
}

// apnsBody turns the push payload into an alert. The ids and type ride along
// as custom keys so the app can open the right screen.
func apnsBody(payload []byte) ([]byte, error) {
	var p struct {
		Type    any               `json:"type"`
		Key     string            `json:"key"`
		Title   string            `json:"title"`
		Body    string            `json:"body"`
		IDs     map[string]string `json:"ids"`
		Tag     string            `json:"tag"`
		Actions []string          `json:"actions"`
		Badge   *int              `json:"badge"`
	}
	if err := json.Unmarshal(payload, &p); err != nil {
		return nil, err
	}
	aps := map[string]any{
		"alert":           map[string]string{"title": p.Title, "body": p.Body},
		"sound":           "default",
		"thread-id":       p.Key,
		"mutable-content": 1,
	}
	m := map[string]any{"aps": aps, "key": p.Key, "ids": p.IDs}
	// Group in Notification Center by conversation or post, not by kind: the tag when there is one, else the
	// conversation, else the post.
	if c := p.IDs["conversation_id"]; c != "" {
		aps["thread-id"] = "conversation:" + c
	} else if po := p.IDs["post_id"]; po != "" {
		aps["thread-id"] = "post:" + po
	}
	if p.Tag != "" {
		aps["thread-id"] = p.Tag
		m["tag"] = p.Tag
	}
	if p.Badge != nil {
		aps["badge"] = *p.Badge
	}
	// Chat messages and mentions in chats get the category with the reply field.
	if (p.Key == "message" || p.Key == "mention") && p.IDs["conversation_id"] != "" && p.IDs["message_id"] != "" {
		aps["category"] = APNsMessageCategory
	}
	if len(p.Actions) > 0 {
		// The app registers the category with the "Snooze 1 month" and "Fewer reminders" buttons (README §6.3).
		aps["category"] = APNsReminderCategory
		m["actions"] = p.Actions
	}
	if p.Type != nil {
		m["type"] = fmt.Sprint(p.Type)
	}
	return json.Marshal(m)
}

// APNsReminderCategory is the notification category of pushes with actions (reminders).
const APNsReminderCategory = "kinframe.reminder"

// APNsMessageCategory is the category of chat message and chat mention pushes (the app adds a reply field).
const APNsMessageCategory = "kinframe.message"

// apnsCollapseID is the payload's tag as apns-collapse-id (at most 64 bytes; longer tags are hashed).
func apnsCollapseID(payload []byte) string {
	var p struct {
		Tag string `json:"tag"`
	}
	if json.Unmarshal(payload, &p) != nil || p.Tag == "" {
		return ""
	}
	if len(p.Tag) <= 64 {
		return p.Tag
	}
	sum := sha256.Sum256([]byte(p.Tag))
	return fmt.Sprintf("%x", sum[:16])
}

type apnsError struct {
	status int
	reason string
}

func (e *apnsError) Error() string { return fmt.Sprintf("apns: %d %s", e.status, e.reason) }

func (s *APNsSender) post(ctx context.Context, host, device string, body []byte, collapse string) error {
	tok, err := s.providerToken()
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, host+"/3/device/"+device, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("authorization", "bearer "+tok)
	req.Header.Set("apns-topic", s.Topic)
	req.Header.Set("apns-push-type", "alert")
	req.Header.Set("apns-priority", "10")
	req.Header.Set("apns-expiration", fmt.Sprint(time.Now().Add(24*time.Hour).Unix()))
	if collapse != "" {
		req.Header.Set("apns-collapse-id", collapse)
	}
	client := s.Client
	if client == nil {
		client = &http.Client{Timeout: 15 * time.Second} // HTTP/2 over TLS, as APNs requires
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		return nil
	}
	var e struct {
		Reason string `json:"reason"`
	}
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
	_ = json.Unmarshal(b, &e)
	if e.Reason == "ExpiredProviderToken" || e.Reason == "InvalidProviderToken" {
		s.resetToken()
	}
	return &apnsError{resp.StatusCode, e.Reason}
}

func (s *APNsSender) Send(ctx context.Context, ep store.PushEndpoint, payload []byte) (bool, error) {
	body, err := apnsBody(payload)
	if err != nil {
		return false, err
	}
	hosts := s.Hosts
	if len(hosts) == 0 {
		hosts = []string{apnsProduction, apnsSandbox}
	}
	var last error
	for _, h := range hosts {
		err := s.post(ctx, h, ep.Endpoint, body, apnsCollapseID(payload))
		if err == nil {
			return false, nil
		}
		last = err
		var ae *apnsError
		if !errors.As(err, &ae) {
			return false, err
		}
		switch {
		case ae.status == http.StatusGone || ae.reason == "Unregistered":
			return true, nil
		case ae.reason == "BadDeviceToken" || ae.reason == "DeviceTokenNotForTopic":
			continue // maybe a token of the other environment
		default:
			return false, err
		}
	}
	var ae *apnsError
	if errors.As(last, &ae) && (ae.reason == "BadDeviceToken" || ae.reason == "DeviceTokenNotForTopic") {
		return true, nil // no environment knows it
	}
	return false, last
}
