package api

import (
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync/atomic"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
)

// Family app version policy (docs/api/README.md §5.15). The Android, iOS and
// Mac apps send `X-Kinframe-App: kinframe-family-<platform>/<name> (<build>)`;
// one below its platform's minimum build gets 426 app_update_required on user
// endpoints. Requests without that header (the website, older apps, the
// frame's `kinframe-frame/…`) are never blocked here.

// familyAppHeaderRE matches `kinframe-family-ios/1.4.0 (412)`.
var familyAppHeaderRE = regexp.MustCompile(`^kinframe-family-([a-z]+)/[^\s()]+\s+\((\d+)\)$`)

// familyApp parses the X-Kinframe-App header of a family app. ok is false for
// any other value (missing, the frame app, an unknown platform, malformed).
func familyApp(header string) (platform string, build int, ok bool) {
	m := familyAppHeaderRE.FindStringSubmatch(strings.TrimSpace(header))
	if m == nil {
		return "", 0, false
	}
	known := false
	for _, p := range config.AppPlatforms {
		known = known || p == m[1]
	}
	n, err := strconv.Atoi(m[2])
	if !known || err != nil || n <= 0 {
		return "", 0, false
	}
	return m[1], n, true
}

// familyVersionExempt lists paths an outdated family app may still call: what
// it needs to show the update screen. User endpoints are the only ones gated,
// so this is a second line for paths that also live under /v1/.
func familyVersionExempt(path string) bool {
	return path == "/v1/discovery" || path == "/healthz" || strings.HasPrefix(path, "/.well-known/") ||
		strings.HasPrefix(path, "/v1/oauth/")
}

// familyAppTooOld reports whether r comes from a family app below its
// platform's minimum build, and counts and logs the refusal (no user data).
func (s *Server) familyAppTooOld(r *http.Request) bool {
	if len(s.cfg.AppVersions) == 0 || familyVersionExempt(r.URL.Path) {
		return false
	}
	platform, build, ok := familyApp(r.Header.Get("X-Kinframe-App"))
	if !ok {
		return false
	}
	minBuild := s.cfg.AppVersions[platform].MinBuild
	if minBuild <= 0 || build >= minBuild {
		return false
	}
	n := familyAppRefused.Add(1)
	s.log.Debug("family app below minimum build", "platform", platform, "build", build, "minimum_build", minBuild, "refused_total", n)
	return true
}

// familyAppRefused counts 426 answers to outdated family apps since start.
var familyAppRefused atomic.Int64

func writeAppUpdateRequired(w http.ResponseWriter, r *http.Request) {
	problem.Write(w, r, problem.New(problem.AppUpdateRequired, ""))
}

// appVersionsJSON is Discovery.app_versions; nil when no platform is set.
func appVersionsJSON(policies map[string]config.AppVersionPolicy) *struct {
	Android *gen.AppVersionPolicy `json:"android,omitempty"`
	Ios     *gen.AppVersionPolicy `json:"ios,omitempty"`
	Macos   *gen.AppVersionPolicy `json:"macos,omitempty"`
} {
	one := func(p string) *gen.AppVersionPolicy {
		v, ok := policies[p]
		if !ok {
			return nil
		}
		out := &gen.AppVersionPolicy{}
		if v.MinBuild > 0 {
			out.MinimumBuild = ptr(v.MinBuild)
		}
		if v.RecommendedBuild > 0 {
			out.RecommendedBuild = ptr(v.RecommendedBuild)
		}
		if v.StoreURL != "" {
			out.StoreUrl = ptr(v.StoreURL)
		}
		if *out == (gen.AppVersionPolicy{}) {
			return nil
		}
		return out
	}
	android, ios, macos := one("android"), one("ios"), one("macos")
	if android == nil && ios == nil && macos == nil {
		return nil
	}
	return &struct {
		Android *gen.AppVersionPolicy `json:"android,omitempty"`
		Ios     *gen.AppVersionPolicy `json:"ios,omitempty"`
		Macos   *gen.AppVersionPolicy `json:"macos,omitempty"`
	}{android, ios, macos}
}
