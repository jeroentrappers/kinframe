// Package notify routes user events (README §6.2) to open websockets, falling
// back to push notifications (README §6.3) when the user has none.
package notify

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/i18n"
	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/ws"
)

// Event is a user websocket message: {"type": ..., ...}.
type Event map[string]any

// Push describes the notification shown when the user is not connected.
// Payloads carry only IDs plus a localized title and body.
type Push struct {
	Key  string            // message key, e.g. "reaction"
	Args map[string]string // substitutions for the localized text
	IDs  map[string]string // ids clients use to fetch details
}

// Sender delivers one push payload to one endpoint. Gone reports the endpoint
// is permanently invalid and should be deleted.
type Sender interface {
	Send(ctx context.Context, ep store.PushEndpoint, payload []byte) (gone bool, err error)
}

type Notifier struct {
	Hub    *ws.Hub
	Store  *store.Store
	Sender Sender // nil disables push
	Log    *slog.Logger
}

// User sends an event to a user; if they have no open websocket and push is
// given, it is delivered as a push notification in the background.
func (n *Notifier) User(userID uuid.UUID, ev Event, push *Push) {
	if n.Hub.SendToUser(userID, ev) || push == nil || n.Sender == nil {
		return
	}
	go n.push(userID, ev, push)
}

// Users sends the same event to several users.
func (n *Notifier) Users(userIDs []uuid.UUID, ev Event, push *Push) {
	for _, u := range userIDs {
		n.User(u, ev, push)
	}
}

func (n *Notifier) push(userID uuid.UUID, ev Event, p *Push) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	eps, err := n.Store.ListPushEndpoints(ctx, userID)
	if err != nil {
		n.Log.Warn("push: list endpoints", "err", err)
		return
	}
	for _, ep := range eps {
		locale := ""
		if ep.Locale != nil {
			locale = *ep.Locale
		}
		title, body := Text(locale, p.Key, p.Args)
		payload, _ := json.Marshal(map[string]any{"type": ev["type"], "key": p.Key, "title": title, "body": body, "ids": p.IDs})
		gone, err := n.Sender.Send(ctx, ep, payload)
		if gone {
			_ = n.Store.DeletePushEndpoint(ctx, userID, ep.ID)
		}
		if err != nil {
			n.Log.Info("push: delivery failed", "kind", ep.Kind, "err", err)
		}
	}
}

// Text returns a localized title and body for a push (texts in package i18n
// as "push.<key>.title" / ".body"; quick replies as "push.preset.<id>").
// locale is any BCP 47 tag; unsupported languages get English.
func Text(locale, key string, args map[string]string) (string, string) {
	lang := i18n.Pick(locale)
	a := make(map[string]string, len(args))
	for k, v := range args {
		a[k] = v
	}
	if p, ok := a["preset"]; ok {
		a["preset"] = i18n.T(lang, "push.preset."+p, nil)
	}
	return i18n.T(lang, "push."+key+".title", a), i18n.T(lang, "push."+key+".body", a)
}

// HTTPSender delivers UnifiedPush (a plain POST of the payload to the
// distributor endpoint). Other kinds are ignored; see MultiSender.
type HTTPSender struct {
	Client *http.Client
}

func (s HTTPSender) Send(ctx context.Context, ep store.PushEndpoint, payload []byte) (bool, error) {
	if ep.Kind != "unifiedpush" {
		return false, nil
	}
	client := s.Client
	if client == nil {
		client = &http.Client{Timeout: 15 * time.Second}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, ep.Endpoint, bytes.NewReader(payload))
	if err != nil {
		return false, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("TTL", "86400")
	resp, err := client.Do(req)
	if err != nil {
		return false, err
	}
	resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone {
		return true, nil
	}
	if resp.StatusCode >= 300 {
		return false, &statusError{resp.StatusCode}
	}
	return false, nil
}

type statusError struct{ code int }

func (e *statusError) Error() string { return "push endpoint returned " + http.StatusText(e.code) }
