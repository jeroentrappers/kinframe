package settings

import (
	"crypto/pbkdf2"
	"crypto/sha256"
	"encoding/hex"
	"testing"
)

// RFC 7914 §11 PBKDF2-HMAC-SHA256 vector; the frame's Kotlin implementation is tested against the same one.
func TestPBKDF2Vector(t *testing.T) {
	k, err := pbkdf2.Key(sha256.New, "passwd", []byte("salt"), 1, 64)
	if err != nil {
		t.Fatal(err)
	}
	want := "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"
	if hex.EncodeToString(k) != want {
		t.Fatalf("got %x", k)
	}
}

func TestHashAndVerify(t *testing.T) {
	h, err := HashPIN("4821")
	if err != nil {
		t.Fatal(err)
	}
	if !VerifyPIN(h, "4821") || VerifyPIN(h, "4820") || VerifyPIN(h, "") {
		t.Fatal("verify")
	}
	h2, _ := HashPIN("4821")
	if h["salt"] == h2["salt"] || h["hash"] == h2["hash"] {
		t.Fatal("salt must be random")
	}
	for _, bad := range []string{"123", "1234567", "12a4", " 1234", "１２３４"} {
		if _, err := HashPIN(bad); err == nil {
			t.Fatalf("accepted %q", bad)
		}
	}
}

func TestApplyPinRules(t *testing.T) {
	p := map[string]any{"kiosk": map[string]any{"owner_pin": "123456", "owner_pin_hash": "x"}}
	if err := ApplyPinRules(p, ActorOwner); err != nil {
		t.Fatal(err)
	}
	k := p["kiosk"].(map[string]any)
	if _, ok := k["owner_pin"]; ok || !VerifyPIN(k["owner_pin_hash"].(map[string]any), "123456") {
		t.Fatalf("owner set: %v", p)
	}
	p = map[string]any{"kiosk": map[string]any{"owner_pin": nil}}
	if err := ApplyPinRules(p, ActorOwner); err != nil || p["kiosk"].(map[string]any)["owner_pin_hash"] != nil {
		t.Fatalf("owner clear: %v %v", p, err)
	}
	if v, ok := p["kiosk"].(map[string]any)["owner_pin_hash"]; !ok || v != nil {
		t.Fatal("clear must be an explicit null in the merge patch")
	}
	if err := ApplyPinRules(map[string]any{"kiosk": map[string]any{"owner_pin": "1234"}}, ActorOther); err != ErrPinOwnerOnly {
		t.Fatalf("admin: %v", err)
	}
	p = map[string]any{"kiosk": map[string]any{"owner_pin_hash": nil, "allow_remove_members_on_frame": false}}
	if err := ApplyPinRules(p, ActorOther); err != nil || len(p["kiosk"].(map[string]any)) != 1 {
		t.Fatalf("other hash: %v", p)
	}
	p = map[string]any{"kiosk": map[string]any{"owner_pin": "1234", "owner_pin_hash": map[string]any{"alg": PinAlg}}}
	if err := ApplyPinRules(p, ActorDevice); err != nil {
		t.Fatal(err)
	}
	if _, ok := p["kiosk"].(map[string]any)["owner_pin"]; ok || p["kiosk"].(map[string]any)["owner_pin_hash"] == nil {
		t.Fatalf("device: %v", p)
	}
	if err := ApplyPinRules(map[string]any{"kiosk": map[string]any{"owner_pin": 1234}}, ActorOwner); err != ErrBadPIN {
		t.Fatalf("number: %v", err)
	}
}

func TestDerivePinSetAndStrip(t *testing.T) {
	d := map[string]any{"kiosk": map[string]any{"owner_pin_hash": map[string]any{"alg": PinAlg}}}
	DerivePinSet(d)
	StripPinHash(d)
	k := d["kiosk"].(map[string]any)
	if k["owner_pin_set"] != true || k["owner_pin_hash"] != nil {
		t.Fatalf("%v", d)
	}
	d = map[string]any{}
	DerivePinSet(d)
	if d["kiosk"].(map[string]any)["owner_pin_set"] != false {
		t.Fatalf("%v", d)
	}
}
