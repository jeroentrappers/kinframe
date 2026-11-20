package headscale

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

type call struct {
	method, path, query, auth string
	body                      map[string]any
}

func fake(t *testing.T, handle func(c call, w http.ResponseWriter)) (*Client, *[]call) {
	t.Helper()
	var calls []call
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c := call{method: r.Method, path: r.URL.Path, query: r.URL.RawQuery, auth: r.Header.Get("Authorization")}
		if b, _ := io.ReadAll(r.Body); len(b) > 0 {
			if err := json.Unmarshal(b, &c.body); err != nil {
				t.Errorf("body %s: %v", b, err)
			}
		}
		calls = append(calls, c)
		w.Header().Set("Content-Type", "application/json")
		handle(c, w)
	}))
	t.Cleanup(srv.Close)
	return New(srv.URL+"/", "hskey"), &calls
}

func TestUserByName(t *testing.T) {
	c, calls := fake(t, func(c call, w http.ResponseWriter) {
		_, _ = io.WriteString(w, `{"users":[{"id":"7","name":"kinframe-frames","createdAt":"2026-01-01T00:00:00Z"}]}`)
	})
	u, err := c.UserByName(context.Background(), "kinframe-frames")
	if err != nil || u.ID != 7 {
		t.Fatalf("user %v %v", u, err)
	}
	got := (*calls)[0]
	if got.method != "GET" || got.path != "/api/v1/user" || got.query != "name=kinframe-frames" || got.auth != "Bearer hskey" {
		t.Fatalf("call %+v", got)
	}
	if _, err := c.UserByName(context.Background(), "other"); err != ErrNoUser {
		t.Fatalf("other user: %v", err)
	}
}

func TestCreateAndExpirePreAuthKey(t *testing.T) {
	c, calls := fake(t, func(c call, w http.ResponseWriter) {
		switch c.path {
		case "/api/v1/preauthkey":
			_, _ = io.WriteString(w, `{"preAuthKey":{"user":{"id":"7","name":"kinframe-frames"},"id":"42","key":"hskey-auth-abc","reusable":false,
				"ephemeral":true,"used":false,"expiration":"2026-11-20T18:00:00Z","createdAt":"2026-11-20T10:00:00Z","aclTags":["tag:kinframe-frame"]}}`)
		case "/api/v1/preauthkey/expire":
			_, _ = io.WriteString(w, `{}`)
		}
	})
	exp := time.Date(2026, 11, 20, 18, 0, 0, 0, time.UTC)
	k, err := c.CreatePreAuthKey(context.Background(), CreateKey{User: 7, Ephemeral: true, Expiration: exp, ACLTags: []string{"tag:kinframe-frame"}})
	if err != nil || k.ID != 42 || k.Key != "hskey-auth-abc" || !k.Ephemeral {
		t.Fatalf("key %+v %v", k, err)
	}
	b := (*calls)[0].body
	if b["user"] != "7" || b["reusable"] != false || b["ephemeral"] != true || b["expiration"] != "2026-11-20T18:00:00Z" {
		t.Fatalf("create body %v", b)
	}
	if tags, _ := b["aclTags"].([]any); len(tags) != 1 || tags[0] != "tag:kinframe-frame" {
		t.Fatalf("tags %v", b["aclTags"])
	}
	if err := c.ExpirePreAuthKey(context.Background(), k.ID); err != nil {
		t.Fatal(err)
	}
	if e := (*calls)[1]; e.method != "POST" || e.body["id"] != "42" {
		t.Fatalf("expire %+v", e)
	}
}

func TestDeleteNodesNamed(t *testing.T) {
	c, calls := fake(t, func(c call, w http.ResponseWriter) {
		switch {
		case c.method == "GET" && c.path == "/api/v1/node":
			_, _ = io.WriteString(w, `{"nodes":[{"id":"1","name":"kinframe-36339301","givenName":"kinframe-36339301","ipAddresses":["100.64.0.9"]},
				{"id":"2","name":"laptop","givenName":"laptop"},{"id":"3","name":"x","givenName":"kinframe-36339301"}]}`)
		case c.method == "DELETE" && c.path == "/api/v1/node/3":
			w.WriteHeader(404)
			_, _ = io.WriteString(w, `{"code":5,"message":"node not found"}`)
		case c.method == "DELETE":
			_, _ = io.WriteString(w, `{}`)
		}
	})
	n, err := c.DeleteNodesNamed(context.Background(), "kinframe-frames", "kinframe-36339301")
	if err != nil || n != 2 {
		t.Fatalf("deleted %d %v", n, err)
	}
	if (*calls)[0].query != "user=kinframe-frames" {
		t.Fatalf("list query %q", (*calls)[0].query)
	}
	var paths []string
	for _, c := range (*calls)[1:] {
		paths = append(paths, c.method+" "+c.path)
	}
	if strings.Join(paths, ",") != "DELETE /api/v1/node/1,DELETE /api/v1/node/3" {
		t.Fatalf("deletes %v", paths)
	}
}

func TestErrors(t *testing.T) {
	c, _ := fake(t, func(c call, w http.ResponseWriter) {
		w.WriteHeader(401)
		_, _ = io.WriteString(w, `{"code":16,"message":"Unauthorized"}`)
	})
	_, err := c.ListNodes(context.Background(), "u")
	var he *Error
	if err == nil || !strings.Contains(err.Error(), "Unauthorized") || !asError(err, &he) || he.Status != 401 {
		t.Fatalf("err %v", err)
	}
	if IsNotFound(err) {
		t.Fatal("401 is not 404")
	}
}

func asError(err error, out **Error) bool {
	e, ok := err.(*Error)
	*out = e
	return ok
}

func TestIDJSON(t *testing.T) {
	var v struct{ A, B ID }
	if err := json.Unmarshal([]byte(`{"A":"12","B":13}`), &v); err != nil || v.A != 12 || v.B != 13 {
		t.Fatalf("%v %v", v, err)
	}
}
