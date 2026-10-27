// Package plugins checks a frame's plugin instances (FrameSettings.plugins)
// against the catalog the frame reported: the manifests of the plugins it can
// run (docs/plugins.md §3.1, §10). The frame checks everything again before it
// runs anything; this is so the apps get a clear error instead of a widget
// that never shows.
package plugins

import (
	"encoding/json"
	"fmt"
	"net/url"
	"slices"
	"strings"
)

// Setting is the part of a manifest setting the server checks.
type Setting struct {
	Key      string   `json:"key"`
	Type     string   `json:"type"`
	Required bool     `json:"required"`
	Default  any      `json:"default"`
	Options  []string `json:"options"`
	Multiple bool     `json:"multiple"`
}

// Manifest is the part of a plugin.json the server checks.
type Manifest struct {
	ID       string    `json:"id"`
	Version  string    `json:"version"`
	Slots    []string  `json:"slots"`
	Settings []Setting `json:"settings"`
}

func (m *Manifest) setting(key string) *Setting {
	for i := range m.Settings {
		if m.Settings[i].Key == key {
			return &m.Settings[i]
		}
	}
	return nil
}

// Catalog maps plugin id to manifest.
type Catalog map[string]*Manifest

// ParseCatalog reads the stored catalog (PluginCatalog: {plugins: [{manifest}]}).
// Entries without a usable manifest are skipped.
func ParseCatalog(raw []byte) (Catalog, error) {
	var c struct {
		Plugins []struct {
			Manifest json.RawMessage `json:"manifest"`
		} `json:"plugins"`
	}
	if err := json.Unmarshal(raw, &c); err != nil {
		return nil, err
	}
	out := Catalog{}
	for _, p := range c.Plugins {
		var m Manifest
		if json.Unmarshal(p.Manifest, &m) != nil || m.ID == "" {
			continue
		}
		if len(m.Slots) == 0 {
			m.Slots = []string{"top-left", "top-right"}
		}
		out[m.ID] = &m
	}
	return out, nil
}

// Problem is one invalid field: Path is dotted from the settings root
// (`plugins.instances.0.settings.grid`).
type Problem struct {
	Path, Reason string
}

// Check validates instances (as stored: secrets sealed under `secrets_sealed`)
// against the catalog. Secrets the manifest no longer has are dropped from
// instances. A nil catalog (a frame app that doesn't report one) checks only
// that instance ids are unique.
func Check(instances []map[string]any, catalog Catalog) []Problem {
	var out []Problem
	seen := map[string]bool{}
	for i, in := range instances {
		at := fmt.Sprintf("plugins.instances.%d", i)
		id, _ := in["instance_id"].(string)
		if seen[id] {
			out = append(out, Problem{at + ".instance_id", "duplicate"})
		}
		seen[id] = true
		if catalog == nil {
			continue
		}
		pid, _ := in["plugin_id"].(string)
		m := catalog[pid]
		if m == nil {
			out = append(out, Problem{at + ".plugin_id", "not_on_frame"})
			continue
		}
		if slot, ok := in["slot"].(string); ok && !slices.Contains(m.Slots, slot) {
			out = append(out, Problem{at + ".slot", "enum"})
		}
		values, _ := in["settings"].(map[string]any)
		for k, v := range values {
			s := m.setting(k)
			switch {
			case s == nil:
				out = append(out, Problem{at + ".settings." + k, "unknown"})
			case s.Type == "secret":
				out = append(out, Problem{at + ".settings." + k, "secret"})
			case v != nil && !fits(s, v):
				out = append(out, Problem{at + ".settings." + k, "type"})
			}
		}
		if sealed, _ := in["secrets_sealed"].(map[string]any); sealed != nil {
			for k := range sealed {
				if s := m.setting(k); s == nil || s.Type != "secret" {
					delete(sealed, k)
				}
			}
			if len(sealed) == 0 {
				delete(in, "secrets_sealed")
			}
		}
		if enabled, ok := in["enabled"].(bool); ok && !enabled {
			continue
		}
		sealed, _ := in["secrets_sealed"].(map[string]any)
		for _, s := range m.Settings {
			if !s.Required || s.Default != nil {
				continue
			}
			if s.Type == "secret" {
				if _, ok := sealed[s.Key]; !ok {
					out = append(out, Problem{at + ".secrets." + s.Key, "required"})
				}
			} else if empty(values[s.Key]) {
				out = append(out, Problem{at + ".settings." + s.Key, "required"})
			}
		}
	}
	return out
}

func empty(v any) bool {
	switch x := v.(type) {
	case nil:
		return true
	case string:
		return strings.TrimSpace(x) == ""
	case []any:
		return len(x) == 0
	}
	return false
}

func fits(s *Setting, v any) bool {
	str, isStr := v.(string)
	switch s.Type {
	case "number":
		_, ok := v.(float64)
		return ok
	case "boolean":
		_, ok := v.(bool)
		return ok
	case "choice":
		return isStr && slices.Contains(s.Options, str)
	case "entity":
		if isStr {
			return true
		}
		arr, ok := v.([]any)
		if !ok || !s.Multiple {
			return false
		}
		for _, e := range arr {
			if _, ok := e.(string); !ok {
				return false
			}
		}
		return true
	case "url":
		if !isStr {
			return false
		}
		if strings.TrimSpace(str) == "" {
			return true
		}
		u, err := url.Parse(str)
		return err == nil && (u.Scheme == "http" || u.Scheme == "https") && u.Host != ""
	}
	return isStr
}
