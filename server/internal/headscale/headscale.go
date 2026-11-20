// Package headscale is a small client for the Headscale REST API (v0.26 and later, checked against v0.29), as
// used by remote help (docs/spec/remote-help.md §5.2): users, pre-auth keys and nodes.
//
// Headscale's REST API is grpc-gateway over its protobuf service: JSON field names are lowerCamelCase and 64-bit
// ids are strings. Requests are authorized with an API key (`headscale apikeys create`) as a Bearer token.
package headscale

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Client calls one Headscale server.
type Client struct {
	BaseURL string // e.g. https://headscale.example.org, no trailing slash
	APIKey  string
	HTTP    *http.Client // nil: a client with a 15-second timeout
}

// New returns a client for baseURL with the API key.
func New(baseURL, apiKey string) *Client {
	return &Client{BaseURL: strings.TrimRight(baseURL, "/"), APIKey: apiKey}
}

// Error is a non-2xx answer from Headscale.
type Error struct {
	Status  int
	Message string
}

func (e *Error) Error() string {
	if e.Message == "" {
		return fmt.Sprintf("headscale: HTTP %d", e.Status)
	}
	return fmt.Sprintf("headscale: HTTP %d: %s", e.Status, e.Message)
}

// IsNotFound reports whether err is a 404 from Headscale.
func IsNotFound(err error) bool {
	var e *Error
	return errors.As(err, &e) && e.Status == http.StatusNotFound
}

// ID is a Headscale uint64 id, encoded as a JSON string (protojson) but accepted as a number too.
type ID uint64

func (id ID) String() string { return strconv.FormatUint(uint64(id), 10) }

func (id ID) MarshalJSON() ([]byte, error) { return json.Marshal(id.String()) }

func (id *ID) UnmarshalJSON(b []byte) error {
	s := strings.Trim(string(b), `"`)
	if s == "" || s == "null" {
		*id = 0
		return nil
	}
	v, err := strconv.ParseUint(s, 10, 64)
	if err != nil {
		return fmt.Errorf("headscale id %s: %w", b, err)
	}
	*id = ID(v)
	return nil
}

// User is a Headscale user.
type User struct {
	ID          ID     `json:"id"`
	Name        string `json:"name"`
	DisplayName string `json:"displayName,omitempty"`
}

// PreAuthKey is a Headscale pre-auth key.
type PreAuthKey struct {
	ID         ID         `json:"id"`
	Key        string     `json:"key"`
	Reusable   bool       `json:"reusable"`
	Ephemeral  bool       `json:"ephemeral"`
	Used       bool       `json:"used"`
	Expiration *time.Time `json:"expiration,omitempty"`
	ACLTags    []string   `json:"aclTags,omitempty"`
	User       *User      `json:"user,omitempty"`
}

// Node is a machine on the tailnet.
type Node struct {
	ID          ID       `json:"id"`
	Name        string   `json:"name"`
	GivenName   string   `json:"givenName"`
	IPAddresses []string `json:"ipAddresses"`
	Online      bool     `json:"online"`
	User        *User    `json:"user,omitempty"`
}

// CreateKey describes a new pre-auth key.
type CreateKey struct {
	User       ID
	Reusable   bool
	Ephemeral  bool
	Expiration time.Time
	ACLTags    []string
}

// UserByName looks a user up by name (GET /api/v1/user?name=). ErrNoUser when there is none.
func (c *Client) UserByName(ctx context.Context, name string) (*User, error) {
	var out struct {
		Users []User `json:"users"`
	}
	if err := c.do(ctx, http.MethodGet, "/api/v1/user?name="+url.QueryEscape(name), nil, &out); err != nil {
		return nil, err
	}
	for i := range out.Users {
		if out.Users[i].Name == name {
			return &out.Users[i], nil
		}
	}
	return nil, ErrNoUser
}

// ErrNoUser means the Headscale user does not exist.
var ErrNoUser = errors.New("headscale: user not found")

// CreatePreAuthKey makes a pre-auth key (POST /api/v1/preauthkey).
func (c *Client) CreatePreAuthKey(ctx context.Context, k CreateKey) (*PreAuthKey, error) {
	body := map[string]any{
		"user": k.User, "reusable": k.Reusable, "ephemeral": k.Ephemeral,
		"expiration": k.Expiration.UTC().Format(time.RFC3339), "aclTags": k.ACLTags,
	}
	if k.ACLTags == nil {
		body["aclTags"] = []string{}
	}
	var out struct {
		PreAuthKey *PreAuthKey `json:"preAuthKey"`
	}
	if err := c.do(ctx, http.MethodPost, "/api/v1/preauthkey", body, &out); err != nil {
		return nil, err
	}
	if out.PreAuthKey == nil || out.PreAuthKey.Key == "" {
		return nil, errors.New("headscale: no pre-auth key in the answer")
	}
	return out.PreAuthKey, nil
}

// ExpirePreAuthKey expires a pre-auth key by id (POST /api/v1/preauthkey/expire, v0.28+).
func (c *Client) ExpirePreAuthKey(ctx context.Context, id ID) error {
	return c.do(ctx, http.MethodPost, "/api/v1/preauthkey/expire", map[string]any{"id": id}, nil)
}

// ListNodes lists the nodes of a user (GET /api/v1/node?user=<name>).
func (c *Client) ListNodes(ctx context.Context, user string) ([]Node, error) {
	var out struct {
		Nodes []Node `json:"nodes"`
	}
	if err := c.do(ctx, http.MethodGet, "/api/v1/node?user="+url.QueryEscape(user), nil, &out); err != nil {
		return nil, err
	}
	return out.Nodes, nil
}

// DeleteNode removes a node from the tailnet (DELETE /api/v1/node/{id}).
func (c *Client) DeleteNode(ctx context.Context, id ID) error {
	return c.do(ctx, http.MethodDelete, "/api/v1/node/"+id.String(), nil, nil)
}

// DeleteNodesNamed deletes the user's nodes whose given name or name is hostname and returns how many went.
func (c *Client) DeleteNodesNamed(ctx context.Context, user, hostname string) (int, error) {
	nodes, err := c.ListNodes(ctx, user)
	if err != nil {
		return 0, err
	}
	n := 0
	for _, nd := range nodes {
		if !strings.EqualFold(nd.GivenName, hostname) && !strings.EqualFold(nd.Name, hostname) {
			continue
		}
		if err := c.DeleteNode(ctx, nd.ID); err != nil && !IsNotFound(err) {
			return n, err
		}
		n++
	}
	return n, nil
}

func (c *Client) do(ctx context.Context, method, path string, body, out any) error {
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.BaseURL+path, rd)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.APIKey)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	hc := c.HTTP
	if hc == nil {
		hc = &http.Client{Timeout: 15 * time.Second}
	}
	res, err := hc.Do(req)
	if err != nil {
		return fmt.Errorf("headscale: %w", err)
	}
	defer res.Body.Close()
	b, err := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	if err != nil {
		return fmt.Errorf("headscale: %w", err)
	}
	if res.StatusCode < 200 || res.StatusCode > 299 {
		var e struct {
			Message string `json:"message"`
		}
		_ = json.Unmarshal(b, &e)
		msg := e.Message
		if msg == "" {
			msg = strings.TrimSpace(string(b))
			if len(msg) > 200 {
				msg = msg[:200]
			}
		}
		return &Error{Status: res.StatusCode, Message: msg}
	}
	if out == nil || len(bytes.TrimSpace(b)) == 0 {
		return nil
	}
	if err := json.Unmarshal(b, out); err != nil {
		return fmt.Errorf("headscale: decode %s: %w", path, err)
	}
	return nil
}
