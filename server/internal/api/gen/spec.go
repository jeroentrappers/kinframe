package gen

import _ "embed"

// PreparedSpec is docs/api/openapi.yaml after tools/specprep, used for request validation.
//
//go:embed openapi.prepared.yaml
var PreparedSpec []byte
