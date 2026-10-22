package settings

import (
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"regexp"
)

// Owner PIN (kiosk.owner_pin): the server never stores the PIN, only a
// PBKDF2-HMAC-SHA256 hash the frame verifies offline (FrameSettings.kiosk.owner_pin_hash).

const (
	PinAlg        = "pbkdf2-sha256"
	PinIterations = 20000 // ~0.5 s on the 1 GHz Cortex-A7 reference frame
	pinSaltBytes  = 16
	pinKeyBytes   = 32
)

var pinRe = regexp.MustCompile(`^[0-9]{4,6}$`)

// ErrBadPIN is returned for anything but 4–6 ASCII digits.
var ErrBadPIN = errors.New("the PIN must be 4 to 6 digits")

// ValidPIN reports whether pin is 4–6 digits.
func ValidPIN(pin string) bool { return pinRe.MatchString(pin) }

// HashPIN returns the PinHash document for pin with a fresh random salt.
func HashPIN(pin string) (map[string]any, error) {
	if !ValidPIN(pin) {
		return nil, ErrBadPIN
	}
	salt := make([]byte, pinSaltBytes)
	if _, err := rand.Read(salt); err != nil {
		return nil, err
	}
	return hashWith(pin, salt, PinIterations)
}

func hashWith(pin string, salt []byte, iterations int) (map[string]any, error) {
	key, err := pbkdf2.Key(sha256.New, pin, salt, iterations, pinKeyBytes)
	if err != nil {
		return nil, err
	}
	return map[string]any{
		"alg":        PinAlg,
		"iterations": iterations,
		"salt":       base64.StdEncoding.EncodeToString(salt),
		"hash":       base64.StdEncoding.EncodeToString(key),
	}, nil
}

// VerifyPIN checks pin against a PinHash document (as decoded from JSON).
func VerifyPIN(doc map[string]any, pin string) bool {
	if doc == nil || doc["alg"] != PinAlg {
		return false
	}
	it, ok := doc["iterations"].(float64)
	if !ok {
		if i, ok2 := doc["iterations"].(int); ok2 {
			it = float64(i)
		} else {
			return false
		}
	}
	salt, err1 := base64.StdEncoding.DecodeString(str(doc["salt"]))
	want, err2 := base64.StdEncoding.DecodeString(str(doc["hash"]))
	if err1 != nil || err2 != nil || it < 1 {
		return false
	}
	got, err := pbkdf2.Key(sha256.New, pin, salt, int(it), len(want))
	return err == nil && subtle.ConstantTimeCompare(got, want) == 1
}

func str(v any) string {
	s, _ := v.(string)
	return s
}

// PinActor says who is patching settings, for the owner PIN rules.
type PinActor int

const (
	ActorDevice PinActor = iota // the frame itself: may set owner_pin_hash, never owner_pin
	ActorOwner                  // the frame's owner: may set owner_pin
	ActorOther                  // admins and members with change_settings: neither
)

// ErrPinOwnerOnly is returned when someone other than the owner sends owner_pin.
var ErrPinOwnerOnly = errors.New("only the frame's owner can set or remove its PIN")

// ApplyPinRules rewrites the kiosk part of a merge patch: a user's `owner_pin`
// becomes `owner_pin_hash` (or its removal), and fields the actor may not write
// are dropped. Call before Sanitize.
func ApplyPinRules(patch map[string]any, actor PinActor) error {
	kiosk, ok := patch["kiosk"].(map[string]any)
	if !ok {
		return nil
	}
	pin, hasPin := kiosk["owner_pin"]
	delete(kiosk, "owner_pin")
	if actor != ActorDevice {
		delete(kiosk, "owner_pin_hash")
	}
	if !hasPin || actor == ActorDevice {
		if len(kiosk) == 0 {
			delete(patch, "kiosk")
		}
		return nil
	}
	if actor != ActorOwner {
		return ErrPinOwnerOnly
	}
	if pin == nil {
		kiosk["owner_pin_hash"] = nil
		return nil
	}
	s, ok := pin.(string)
	if !ok {
		return ErrBadPIN
	}
	h, err := HashPIN(s)
	if err != nil {
		return err
	}
	kiosk["owner_pin_hash"] = h
	return nil
}

// DerivePinSet sets kiosk.owner_pin_set from kiosk.owner_pin_hash in a full document.
func DerivePinSet(doc map[string]any) {
	kiosk, ok := doc["kiosk"].(map[string]any)
	if !ok {
		kiosk = map[string]any{}
		doc["kiosk"] = kiosk
	}
	h, _ := kiosk["owner_pin_hash"].(map[string]any)
	kiosk["owner_pin_set"] = h != nil
}

// StripPinHash removes the hash from a document shown to people (only the frame needs it).
func StripPinHash(doc map[string]any) {
	if kiosk, ok := doc["kiosk"].(map[string]any); ok {
		delete(kiosk, "owner_pin_hash")
	}
}
