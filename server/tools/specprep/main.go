// Command specprep rewrites docs/api/openapi.yaml into a form oapi-codegen can
// digest, without changing the contract's meaning:
//
//   - references deeper than a component schema (e.g. #/components/schemas/Album/properties/schedule)
//     are inlined, because oapi-codegen only resolves refs to whole components;
//   - OpenAPI 3.1 type arrays such as [string, 'null'] become `type: string` plus `nullable: true`;
//   - oneOf [ {$ref}, {type: 'null'} ] becomes the nullable referenced type;
//   - the document is labelled openapi 3.0.3 to match.
//
// The output is only used as code-generator input; docs/api/openapi.yaml stays authoritative.
package main

import (
	"fmt"
	"os"
	"strings"

	"gopkg.in/yaml.v3"
)

func main() {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: specprep <in.yaml> <out.yaml>")
		os.Exit(2)
	}
	raw, err := os.ReadFile(os.Args[1])
	if err != nil {
		fail(err)
	}
	var doc map[string]any
	if err := yaml.Unmarshal(raw, &doc); err != nil {
		fail(err)
	}
	out := rewrite(doc, doc).(map[string]any)
	// The rewritten constructs are OpenAPI 3.0 (nullable, example), so label
	// the document accordingly for validators that switch on the version.
	out["openapi"] = "3.0.3"
	b, err := yaml.Marshal(out)
	if err != nil {
		fail(err)
	}
	if err := os.WriteFile(os.Args[2], b, 0o644); err != nil {
		fail(err)
	}
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "specprep:", err)
	os.Exit(1)
}

func rewrite(root map[string]any, v any) any {
	switch t := v.(type) {
	case map[string]any:
		if ref, ok := t["$ref"].(string); ok && strings.Count(ref, "/") > 3 {
			return rewrite(root, resolve(root, ref))
		}
		if one, ok := t["oneOf"].([]any); ok && len(one) == 2 {
			if nonNull, ok := nullableOneOf(one); ok {
				delete(t, "oneOf")
				t["allOf"] = []any{rewrite(root, nonNull)}
				t["nullable"] = true
			}
		}
		if types, ok := t["type"].([]any); ok {
			var kept []string
			nullable := false
			for _, x := range types {
				if s, _ := x.(string); s == "null" {
					nullable = true
				} else {
					kept = append(kept, fmt.Sprint(x))
				}
			}
			if len(kept) == 1 {
				t["type"] = kept[0]
			}
			if nullable {
				t["nullable"] = true
			}
		}
		delete(t, "const")
		if _, ok := t["contentMediaType"]; ok {
			delete(t, "contentMediaType")
			t["format"] = "binary"
		}
		if ex, ok := t["examples"]; ok {
			delete(t, "examples")
			if arr, ok := ex.([]any); ok && len(arr) > 0 {
				t["example"] = arr[0]
			}
		}
		for k, x := range t {
			t[k] = rewrite(root, x)
		}
		return t
	case []any:
		for i, x := range t {
			t[i] = rewrite(root, x)
		}
		return t
	default:
		return v
	}
}

func nullableOneOf(one []any) (any, bool) {
	isNull := func(x any) bool {
		m, ok := x.(map[string]any)
		return ok && len(m) == 1 && m["type"] == "null"
	}
	switch {
	case isNull(one[1]):
		return one[0], true
	case isNull(one[0]):
		return one[1], true
	}
	return nil, false
}

func resolve(root map[string]any, ref string) any {
	parts := strings.Split(strings.TrimPrefix(ref, "#/"), "/")
	var cur any = root
	for _, p := range parts {
		m, ok := cur.(map[string]any)
		if !ok {
			fail(fmt.Errorf("cannot resolve %s", ref))
		}
		cur = m[p]
	}
	// Deep copy so later in-place rewrites don't alias.
	b, _ := yaml.Marshal(cur)
	var cp any
	_ = yaml.Unmarshal(b, &cp)
	return cp
}
