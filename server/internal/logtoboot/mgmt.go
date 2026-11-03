package logtoboot

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// mgmt is a minimal Logto Management API client.
type mgmt struct {
	hc    *http.Client
	base  string // <Logto ENDPOINT>/api
	token string
}

func (m *mgmt) do(ctx context.Context, method, path string, in, out any) error {
	h := http.Header{"Authorization": {"Bearer " + m.token}}
	return doJSON(ctx, m.hc, method, m.base+path, h, in, out)
}

// list fetches every page of a list endpoint.
func list[T any](ctx context.Context, m *mgmt, path string) ([]T, error) {
	const size = 100
	sep := "?"
	if strings.Contains(path, "?") {
		sep = "&"
	}
	var all []T
	for page := 1; ; page++ {
		var batch []T
		if err := m.do(ctx, http.MethodGet, path+sep+"page="+strconv.Itoa(page)+"&page_size="+strconv.Itoa(size), nil, &batch); err != nil {
			return nil, err
		}
		all = append(all, batch...)
		if len(batch) < size {
			return all, nil
		}
	}
}

type resource struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	Indicator      string `json:"indicator"`
	AccessTokenTTL int    `json:"accessTokenTtl"`
}

type scope struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
}

type role struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Type        string `json:"type"`
	Description string `json:"description"`
}

type oidcClientMetadata struct {
	RedirectURIs           []string `json:"redirectUris"`
	PostLogoutRedirectURIs []string `json:"postLogoutRedirectUris"`
}

type application struct {
	ID                   string             `json:"id"`
	Name                 string             `json:"name"`
	Type                 string             `json:"type"`
	Description          string             `json:"description"`
	OIDCClientMetadata   oidcClientMetadata `json:"oidcClientMetadata"`
	CustomClientMetadata map[string]any     `json:"customClientMetadata"`
}

type connector struct {
	ID          string         `json:"id"`
	ConnectorID string         `json:"connectorId"`
	Target      string         `json:"target"`
	Type        string         `json:"type"`
	Config      map[string]any `json:"config"`
}

type user struct {
	ID           string `json:"id"`
	PrimaryEmail string `json:"primaryEmail"`
}

// findUserByEmail returns nil when no user has that primary email.
func (m *mgmt) findUserByEmail(ctx context.Context, email string) (*user, error) {
	q := url.Values{"search.primaryEmail": {email}, "mode.primaryEmail": {"exact"}}
	users, err := list[user](ctx, m, "/users?"+q.Encode())
	if err != nil {
		return nil, err
	}
	for i := range users {
		if strings.EqualFold(users[i].PrimaryEmail, email) {
			return &users[i], nil
		}
	}
	return nil, nil
}

// jsonEqual compares two values by their JSON encoding (map keys sorted),
// which is how Logto stores and returns them.
func jsonEqual(a, b any) bool {
	na, err1 := normalize(a)
	nb, err2 := normalize(b)
	if err1 != nil || err2 != nil {
		return false
	}
	ja, _ := json.Marshal(na)
	jb, _ := json.Marshal(nb)
	return string(ja) == string(jb)
}

func normalize(v any) (any, error) {
	b, err := json.Marshal(v)
	if err != nil {
		return nil, err
	}
	var out any
	err = json.Unmarshal(b, &out)
	return out, err
}

// subsetEqual reports whether every key in want has an equal value in have
// (recursively for objects). Logto fills in defaults for fields we don't
// manage; those must not count as drift.
func subsetEqual(want, have any) bool {
	nw, err1 := normalize(want)
	nh, err2 := normalize(have)
	if err1 != nil || err2 != nil {
		return false
	}
	return subset(nw, nh)
}

func subset(want, have any) bool {
	wm, ok := want.(map[string]any)
	if !ok {
		return jsonEqual(want, have)
	}
	hm, ok := have.(map[string]any)
	if !ok {
		return false
	}
	for k, wv := range wm {
		hv, ok := hm[k]
		if !ok {
			if isEmpty(wv) {
				continue // absent and empty are the same to Logto
			}
			return false
		}
		if !subset(wv, hv) {
			return false
		}
	}
	return true
}

// isEmpty: nil, [] and {} all mean "nothing" to Logto, which may drop them.
func isEmpty(v any) bool {
	switch x := v.(type) {
	case nil:
		return true
	case []any:
		return len(x) == 0
	case map[string]any:
		return len(x) == 0
	}
	return false
}
