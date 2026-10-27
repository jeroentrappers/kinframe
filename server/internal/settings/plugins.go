package settings

import (
	"encoding/base64"
	"sort"
)

// Plugin secrets (FrameSettings.plugins.instances[].secrets, docs/plugins.md
// §10): people write them, only the frame reads them back. The stored document
// keeps them sealed per instance under sealedKey; renders replace that with
// `secrets_set` (people) or the opened `secrets` (the frame).

const sealedKey = "secrets_sealed"

// Sealer encrypts and decrypts one secret value.
type Sealer interface {
	Seal(string) []byte
	Open([]byte) (string, error)
}

// PluginInstances returns the instances of a settings document or patch, and
// whether the document has an `instances` array at all.
func PluginInstances(doc map[string]any) ([]map[string]any, bool) {
	p, _ := doc["plugins"].(map[string]any)
	if p == nil {
		return nil, false
	}
	arr, ok := p["instances"].([]any)
	if !ok {
		return nil, false
	}
	out := make([]map[string]any, 0, len(arr))
	for _, e := range arr {
		if m, ok := e.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out, true
}

// ApplyPluginSecrets rewrites the instances of a merge patch for storage: each
// instance keeps the sealed secrets its stored namesake (same instance_id in
// current) has, with the patch's `secrets` applied (a string sets, null or ""
// removes), and loses `secrets` and `secrets_set`. A patch without instances is
// left alone.
func ApplyPluginSecrets(patch, current map[string]any, s Sealer) {
	next, ok := PluginInstances(patch)
	if !ok {
		return
	}
	old := map[string]map[string]any{}
	if cur, ok := PluginInstances(current); ok {
		for _, in := range cur {
			if id, _ := in["instance_id"].(string); id != "" {
				sealed, _ := in[sealedKey].(map[string]any)
				old[id] = sealed
			}
		}
	}
	for _, in := range next {
		id, _ := in["instance_id"].(string)
		sealed := map[string]any{}
		for k, v := range old[id] {
			sealed[k] = v
		}
		if w, ok := in["secrets"].(map[string]any); ok {
			for k, v := range w {
				if str, _ := v.(string); str != "" {
					sealed[k] = base64.StdEncoding.EncodeToString(s.Seal(str))
				} else {
					delete(sealed, k)
				}
			}
		}
		delete(in, "secrets")
		delete(in, "secrets_set")
		delete(in, sealedKey)
		if len(sealed) > 0 {
			in[sealedKey] = sealed
		}
	}
}

// SecretKeys lists the secret settings an instance has a value for.
func SecretKeys(instance map[string]any) []string {
	sealed, _ := instance[sealedKey].(map[string]any)
	keys := make([]string, 0, len(sealed))
	for k := range sealed {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// RenderPluginSecrets prepares a full document for a reader: every instance
// gets `secrets_set`, and the frame (forDevice) also `secrets` with the opened
// values. A value that can't be opened (the key changed) counts as unset, so the
// form asks for it again. The sealed values never leave the server.
func RenderPluginSecrets(doc map[string]any, s Sealer, forDevice bool) {
	ins, _ := PluginInstances(doc)
	for _, in := range ins {
		keys := SecretKeys(in)
		sealed, _ := in[sealedKey].(map[string]any)
		delete(in, sealedKey)
		set := make([]any, 0, len(keys))
		open := map[string]any{}
		for _, k := range keys {
			b64, _ := sealed[k].(string)
			box, err := base64.StdEncoding.DecodeString(b64)
			if err != nil {
				continue
			}
			v, err := s.Open(box)
			if err != nil {
				continue
			}
			set = append(set, k)
			open[k] = v
		}
		in["secrets_set"] = set
		if forDevice {
			in["secrets"] = open
		}
	}
}
