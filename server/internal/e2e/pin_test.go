package e2e

import (
	"encoding/json"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/settings"
)

// The owner PIN: only the owner sets it (as a PIN), people never see the hash, the frame gets the hash and may
// replace it when the PIN is changed at the frame.
func TestOwnerPin(t *testing.T) {
	e := newEnv(t)
	owner := e.user("o@x.org", "O")
	d, frameID := e.claim(owner)
	path := "/v1/frames/" + frameID + "/settings"
	patch := func(tok, version string, body map[string]any) *resp {
		return e.do(req{method: "PATCH", path: path, token: tok, body: body, header: map[string]string{"If-Match": version}})
	}
	devicePatch := func(version string, body map[string]any) *resp {
		raw, _ := json.Marshal(body)
		return e.do(req{method: "PATCH", path: "/v1/device/settings", token: d.token, raw: raw,
			header: map[string]string{"If-Match": version, "Content-Type": "application/merge-patch+json"}})
	}
	if s := e.get(path, owner.token).m(t); dig(s, "kiosk", "owner_pin_set") != false {
		t.Fatalf("default pin: %v", s)
	}
	if r := patch(owner.token, `"1"`, map[string]any{"kiosk": map[string]any{"owner_pin": "12a4"}}); r.StatusCode != 422 {
		t.Fatalf("bad pin: %d %s", r.StatusCode, r.body)
	}
	r := patch(owner.token, `"1"`, map[string]any{"kiosk": map[string]any{"owner_pin": "4821"}})
	if r.StatusCode != 200 || dig(r.m(t), "kiosk", "owner_pin_set") != true || dig(r.m(t), "kiosk", "owner_pin_hash") != nil ||
		dig(r.m(t), "kiosk", "owner_pin") != nil {
		t.Fatalf("set pin: %d %s", r.StatusCode, r.body)
	}
	if s := e.get(path, owner.token).m(t); dig(s, "kiosk", "owner_pin_hash") != nil || dig(s, "kiosk", "owner_pin_set") != true {
		t.Fatalf("user view: %v", s)
	}
	// The frame sees a hash that verifies the PIN.
	feed := changesOf(e.get("/v1/device/changes?since=0", d.token).m(t), "settings")
	h, _ := dig(feed[0], "settings", "kiosk", "owner_pin_hash").(map[string]any)
	if h == nil || !settings.VerifyPIN(h, "4821") || settings.VerifyPIN(h, "4822") || h["alg"] != "pbkdf2-sha256" {
		t.Fatalf("device hash: %v", feed[0])
	}
	// Admins can change settings but not the PIN, and a hash sent by a person is ignored.
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: d.token}, 200).m(t)["code"]
	ann := e.user("ann@x.org", "Ann")
	e.expect(req{method: "POST", path: "/v1/frames/redeem", token: ann.token, body: map[string]any{"code": code}}, 200)
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frameID + "/members/" + ann.id, token: owner.token, body: map[string]any{"role": "admin"}}, 200)
	if r := patch(ann.token, `"2"`, map[string]any{"kiosk": map[string]any{"owner_pin": nil}}); r.StatusCode != 403 {
		t.Fatalf("admin pin: %d %s", r.StatusCode, r.body)
	}
	if r := patch(ann.token, `"2"`, map[string]any{"kiosk": map[string]any{"owner_pin_hash": nil}}); r.StatusCode != 200 || dig(r.m(t), "kiosk", "owner_pin_set") != true {
		t.Fatalf("admin hash: %d %s", r.StatusCode, r.body)
	}
	// The frame changes the PIN locally: it sends a new hash; plain PINs from the device are ignored.
	h2, _ := settings.HashPIN("777777")
	dr := devicePatch(`"2"`, map[string]any{"kiosk": map[string]any{"owner_pin_hash": h2, "owner_pin": "1111"}})
	hd, _ := dig(dr.m(t), "kiosk", "owner_pin_hash").(map[string]any)
	if dr.StatusCode != 200 || !settings.VerifyPIN(hd, "777777") || dig(dr.m(t), "kiosk", "owner_pin_set") != true {
		t.Fatalf("device pin: %d %s", dr.StatusCode, dr.body)
	}
	if r := devicePatch(`"3"`, map[string]any{"kiosk": map[string]any{"owner_pin_hash": map[string]any{"alg": "md5"}}}); r.StatusCode != 422 {
		t.Fatalf("bad hash: %d %s", r.StatusCode, r.body)
	}
	// The owner removes it remotely ("forgot PIN").
	r = patch(owner.token, `"3"`, map[string]any{"kiosk": map[string]any{"owner_pin": nil}})
	if r.StatusCode != 200 || dig(r.m(t), "kiosk", "owner_pin_set") != false {
		t.Fatalf("clear pin: %d %s", r.StatusCode, r.body)
	}
	feed = changesOf(e.get("/v1/device/changes?since=0", d.token).m(t), "settings")
	if dig(feed[0], "settings", "kiosk", "owner_pin_hash") != nil {
		t.Fatalf("hash after clear: %v", feed[0])
	}
}
