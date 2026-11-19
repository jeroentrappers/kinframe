package e2e

import (
	"testing"
	"time"
)

func TestDeleteAccount(t *testing.T) {
	e := newEnv(t)
	ann := e.user("ann@example.org", "Ann")
	ben := e.user("ben@example.org", "Ben")
	d, frameID := e.claim(ann)
	e.join(ben, d)

	// An owner has to hand over or remove the frame first.
	if r := e.do(req{method: "DELETE", path: "/v1/me?remove_items=false", token: ann.token}); r.StatusCode != 409 || r.code() != "owner_cannot_leave" {
		t.Fatalf("owner delete: %d %s", r.StatusCode, r.body)
	}

	e.expect(req{method: "DELETE", path: "/v1/me?remove_items=true", token: ben.token}, 204)
	var list []map[string]any
	e.get("/v1/frames/"+frameID+"/members", ann.token).json(t, &list)
	if len(list) != 1 || list[0]["display_name"] != "Ann" {
		t.Fatalf("members after delete: %v", list)
	}
	// A token from before the deletion is refused rather than starting a new account (the share extension or
	// another tab may still hold one).
	if r := e.do(req{method: "GET", path: "/v1/me", token: ben.token}); r.StatusCode != 401 || r.code() != "unauthorized" {
		t.Fatalf("old token after delete: %d %s", r.StatusCode, r.body)
	}
	// Signing in again afterwards starts a new, empty account.
	time.Sleep(time.Second) // token times have second precision
	again := e.user("ben@example.org", "Ben")
	if again.id == ben.id {
		t.Fatalf("deleted account came back: %v", again.id)
	}
	var frames []map[string]any
	e.get("/v1/frames", again.token).json(t, &frames)
	if len(frames) != 0 {
		t.Fatalf("new account sees frames: %v", frames)
	}
}
