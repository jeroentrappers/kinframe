package federation

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Document is the part of another server's /.well-known/kinframe that federation reads.
type Document struct {
	ServerID   string `json:"server_id"`
	ServerName string `json:"server_name"`
	BaseURL    string `json:"base_url"`
	APIVersion string `json:"api_version"`
	Federation *struct {
		Enabled   bool   `json:"enabled"`
		KeyID     string `json:"key_id"`
		PublicKey string `json:"public_key"`
		Keys      []struct {
			KeyID     string     `json:"key_id"`
			PublicKey string     `json:"public_key"`
			ExpiresAt *time.Time `json:"expires_at"`
		} `json:"keys"`
		Endpoints struct {
			Subscriptions string `json:"subscriptions"`
			Ping          string `json:"ping"`
		} `json:"endpoints"`
	} `json:"federation"`
}

// PublishedKey is one key of a document.
type PublishedKey struct {
	KeyID     string     `json:"key_id"`
	PublicKey string     `json:"public_key"`
	ExpiresAt *time.Time `json:"expires_at,omitempty"`
}

// Keys lists every key the document publishes (the current one first, deduplicated).
func (d *Document) Keys() []PublishedKey {
	if d.Federation == nil {
		return nil
	}
	var out []PublishedKey
	seen := map[string]bool{}
	if d.Federation.KeyID != "" && d.Federation.PublicKey != "" {
		out = append(out, PublishedKey{KeyID: d.Federation.KeyID, PublicKey: d.Federation.PublicKey})
		seen[d.Federation.KeyID] = true
	}
	for _, k := range d.Federation.Keys {
		if k.KeyID == "" || seen[k.KeyID] {
			continue
		}
		seen[k.KeyID] = true
		out = append(out, PublishedKey{KeyID: k.KeyID, PublicKey: k.PublicKey, ExpiresAt: k.ExpiresAt})
	}
	return out
}

// Enabled reports whether the other server says federation is on.
func (d *Document) Enabled() bool { return d.Federation != nil && d.Federation.Enabled }

// Client calls other kinframe servers. HTTP must be a guarded client (internal/netguard): public addresses only.
type Client struct {
	HTTP *http.Client
	// Self is this server's base URL (the Kinframe-Server header).
	Self string
	// Key returns the current signing key.
	Key func(ctx context.Context) (*Key, error)
	Now func() time.Time
}

// maxDocument and maxResponse bound what is read from another server.
const (
	maxDocument = 256 << 10
	maxResponse = 16 << 20
)

// ErrNotKinframe means the URL did not serve a kinframe well-known document.
var ErrNotKinframe = errors.New("federation: not a kinframe server")

// FetchDocument reads base's /.well-known/kinframe (unsigned) and checks it describes base.
func (c *Client) FetchDocument(ctx context.Context, base string) (*Document, error) {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/.well-known/kinframe", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	res, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%w: well-known answered %d", ErrNotKinframe, res.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(res.Body, maxDocument))
	if err != nil {
		return nil, err
	}
	var d Document
	if err := json.Unmarshal(b, &d); err != nil {
		return nil, fmt.Errorf("%w: %v", ErrNotKinframe, err)
	}
	if d.ServerID == "" || d.BaseURL == "" {
		return nil, ErrNotKinframe
	}
	got, _, err := NormalizeBaseURL(d.BaseURL)
	if err != nil || got != base {
		return nil, fmt.Errorf("%w: it calls itself %q", ErrNotKinframe, d.BaseURL)
	}
	return &d, nil
}

// RemoteError is a problem document (or a bare status) another server answered with.
type RemoteError struct {
	Status int
	Code   string
	Detail string
}

func (e *RemoteError) Error() string {
	return fmt.Sprintf("remote server answered %d %s %s", e.Status, e.Code, e.Detail)
}

// AsRemote returns the RemoteError in err's chain.
func AsRemote(err error) (*RemoteError, bool) {
	var r *RemoteError
	ok := errors.As(err, &r)
	return r, ok
}

// Do sends a signed request to url (on another server) with body marshalled as JSON (nil: empty body) and
// decodes a 2xx JSON answer into out (when not nil). Non-2xx answers are a *RemoteError.
func (c *Client) Do(ctx context.Context, method, url string, body, out any) (int, error) {
	var raw []byte
	if body != nil {
		var err error
		if raw, err = json.Marshal(body); err != nil {
			return 0, err
		}
	}
	key, err := c.Key(ctx)
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, method, url, bytes.NewReader(raw))
	if err != nil {
		return 0, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	now := time.Now()
	if c.Now != nil {
		now = c.Now()
	}
	if err := Sign(req, raw, key, c.Self, now); err != nil {
		return 0, err
	}
	res, err := c.HTTP.Do(req)
	if err != nil {
		return 0, err
	}
	defer res.Body.Close()
	b, err := io.ReadAll(io.LimitReader(res.Body, maxResponse))
	if err != nil {
		return res.StatusCode, err
	}
	if res.StatusCode < 200 || res.StatusCode > 299 {
		re := &RemoteError{Status: res.StatusCode}
		var p struct {
			Code   string `json:"code"`
			Detail string `json:"detail"`
		}
		if json.Unmarshal(b, &p) == nil {
			re.Code, re.Detail = p.Code, p.Detail
		}
		return res.StatusCode, re
	}
	if out != nil && len(bytes.TrimSpace(b)) > 0 {
		if err := json.Unmarshal(b, out); err != nil {
			return res.StatusCode, fmt.Errorf("federation: decode answer of %s: %w", url, err)
		}
	}
	return res.StatusCode, nil
}

// Download fetches url (a signed master URL on another server) into w, refusing more than max bytes.
// It returns the bytes written and the Content-Type.
func (c *Client) Download(ctx context.Context, url string, w io.Writer, max int64) (int64, string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return 0, "", err
	}
	res, err := c.HTTP.Do(req)
	if err != nil {
		return 0, "", err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return 0, "", &RemoteError{Status: res.StatusCode}
	}
	if res.ContentLength > max {
		return 0, "", ErrTooLarge
	}
	n, err := io.Copy(w, io.LimitReader(res.Body, max+1))
	if err != nil {
		return n, "", err
	}
	if n > max {
		return n, "", ErrTooLarge
	}
	return n, strings.TrimSpace(strings.Split(res.Header.Get("Content-Type"), ";")[0]), nil
}

// ErrTooLarge is a download over its size limit.
var ErrTooLarge = errors.New("federation: download too large")
