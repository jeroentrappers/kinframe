package e2e

import "testing"

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
	// The account is gone: the same sign-in starts a new, empty account.
	again := e.get("/v1/me", ben.token).m(t)
	if again["id"] == ben.id {
		t.Fatalf("deleted account still signs in: %v", again)
	}
	var frames []map[string]any
	e.get("/v1/frames", ben.token).json(t, &frames)
	if len(frames) != 0 {
		t.Fatalf("new account sees frames: %v", frames)
	}
}
