// Package settings implements the versioned frame settings document: defaults
// (product spec §6), RFC 7396 merge patches and per-field conflict detection.
package settings

import (
	"encoding/json"
	"sort"
	"strings"

	"github.com/google/uuid"
)

// Doc is a settings document without its version (the version is stored
// separately and added when serialising).
type Doc = map[string]any

// Defaults returns the default settings for a new frame.
func Defaults(language, timezone string) Doc {
	if language == "" {
		language = "en"
	}
	if timezone == "" {
		timezone = "Europe/Brussels"
	}
	return Doc{
		"slideshow": map[string]any{
			"interval_s": 30, "transition": "crossfade", "transition_ms": 800, "order": "received",
			"reverse": false, "caption_date": "received", "fill": true, "background": "blur",
			"pair_portraits": true, "collage": false, "collage_max": 3, "album_id": nil, "on_this_day": false,
			"live_photos": "on",
		},
		"widgets": map[string]any{
			"caption": true, "avatar": true, "clock": true, "date": true, "weather": false, "clock_24h": true,
		},
		"video": map[string]any{
			"autoplay": true, "playback": "once_hold", "volume": 60, "auto_mute_min": nil,
		},
		"display": map[string]any{
			"brightness": 204, "brightness_mode": "manual", "brightness_schedule": []any{},
			"sleep_schedules": []any{map[string]any{
				"id": uuid.Must(uuid.NewV7()).String(), "sleep_at": "23:00", "wake_at": "07:00",
				"days":    []any{"mon", "tue", "wed", "thu", "fri", "sat", "sun"},
				"enabled": true, "mode": "off",
			}},
			"motion_timeout_min": 30, "text_scale": 1.0,
		},
		"locale": map[string]any{
			"language": language, "timezone": timezone, "temperature_unit": "c", "first_day_of_week": "mon",
		},
		"location":      nil,
		"notifications": map[string]any{"sound": true, "volume": 40},
		"kiosk":         map[string]any{"allow_remove_members_on_frame": true, "owner_pin_set": false},
		"updates":       map[string]any{"channel": "stable"},
	}
}

// readOnly paths are ignored in patches.
var readOnly = []string{"version", "kiosk.owner_pin_set"}

// Sanitize removes read-only fields from a patch.
func Sanitize(patch map[string]any) {
	for _, p := range readOnly {
		parts := strings.Split(p, ".")
		m := patch
		for i, k := range parts {
			if i == len(parts)-1 {
				delete(m, k)
				break
			}
			next, ok := m[k].(map[string]any)
			if !ok {
				break
			}
			m = next
		}
	}
}

// MergePatch applies an RFC 7396 merge patch to target (which is modified and returned).
func MergePatch(target map[string]any, patch map[string]any) map[string]any {
	if target == nil {
		target = map[string]any{}
	}
	for k, v := range patch {
		if v == nil {
			delete(target, k)
			continue
		}
		if pm, ok := v.(map[string]any); ok {
			tm, _ := target[k].(map[string]any)
			target[k] = MergePatch(tm, pm)
			continue
		}
		target[k] = v
	}
	return target
}

// LeafPaths lists the dotted paths a patch changes. Objects are descended into;
// anything else (including arrays and null) is a leaf.
func LeafPaths(patch map[string]any) []string {
	var out []string
	var walk func(prefix string, m map[string]any)
	walk = func(prefix string, m map[string]any) {
		for k, v := range m {
			p := k
			if prefix != "" {
				p = prefix + "." + k
			}
			if sub, ok := v.(map[string]any); ok && len(sub) > 0 {
				walk(p, sub)
				continue
			}
			out = append(out, p)
		}
	}
	walk("", patch)
	sort.Strings(out)
	return out
}

// overlaps reports whether a and b address the same field or one contains the other.
func overlaps(a, b string) bool {
	return a == b || strings.HasPrefix(a, b+".") || strings.HasPrefix(b, a+".")
}

// Conflicts reports whether a patch based on baseVersion touches any field
// changed after baseVersion. fieldVersions maps leaf path -> version that last
// changed it.
func Conflicts(paths []string, baseVersion int, fieldVersions map[string]int) bool {
	for _, p := range paths {
		for q, v := range fieldVersions {
			if v > baseVersion && overlaps(p, q) {
				return true
			}
		}
	}
	return false
}

// Clone deep-copies a document via JSON.
func Clone(d Doc) Doc {
	b, _ := json.Marshal(d)
	var out Doc
	_ = json.Unmarshal(b, &out)
	return out
}
