package api

// Shared by the temporary *_stubs.go files (docs/spec/family-social-work.md). Whoever removes the last stub file
// removes this one too.

import (
	"net/http"

	"github.com/jeroentrappers/kinframe/server/internal/problem"
)

// notImplemented is the answer of an operation that is in the contract but not built yet.
func notImplemented() error {
	return problem.New(problem.FeatureDisabled, "%s", "not implemented yet").WithStatus(http.StatusNotImplemented)
}

// writeNotImplemented is notImplemented for hand-written handlers.
func writeNotImplemented(w http.ResponseWriter, r *http.Request) {
	problem.Write(w, r, problem.New(problem.FeatureDisabled, "%s", "not implemented yet").WithStatus(http.StatusNotImplemented))
}
