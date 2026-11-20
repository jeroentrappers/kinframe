package config

import (
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"slices"
	"strings"
)

// Remote help (docs/spec/remote-help.md §5.1): a temporary tailnet tunnel from a frame to the operator, through a
// self-hosted Headscale.
type RemoteHelpConfig struct {
	// Enabled is KINFRAME_REMOTE_HELP=on.
	Enabled bool
	// HeadscaleURL is the Headscale control URL (also the frame's --login-server), no trailing slash.
	HeadscaleURL string
	// HeadscaleAPIKey is sent as Authorization: Bearer.
	HeadscaleAPIKey string
	// HeadscaleUser is the Headscale user frames register under (looked up to its id).
	HeadscaleUser string
	// ADBKey is the operator's ADB public key (one line of adbkey.pub).
	ADBKey string
	// PolicyChecked is KINFRAME_REMOTE_HELP_POLICY_CHECKED=1: the operator confirms the tailnet ACL is in place.
	PolicyChecked bool
	// TailscaleVersion and the SHA-256s of its linux arm and arm64 tarballs.
	TailscaleVersion string
	TailscaleSHA256  map[string]string // "arm", "arm64"
}

// DefaultTailscaleVersion is the Tailscale release whose SHA-256s are built in.
const DefaultTailscaleVersion = "1.104.7"

var defaultTailscaleSHA256 = map[string]string{
	"arm":   "1c753fef45d497491081db37552b7a2a42c7932292743bf6936cb1a42b6bceb0",
	"arm64": "d6bed2720310b328b861f943d7b4016c5358a9bad60575eb51e8143b79068484",
}

var (
	sha256Re  = regexp.MustCompile(`^[0-9a-f]{64}$`)
	versionRe = regexp.MustCompile(`^[0-9]+\.[0-9]+\.[0-9]+$`)
)

// Ready reports whether remote help is on and fully configured (discovery `remote_help`).
func (r *RemoteHelpConfig) Ready() bool {
	return r.Enabled && r.PolicyChecked && r.HeadscaleURL != "" && r.HeadscaleAPIKey != "" && r.HeadscaleUser != "" && r.ADBKey != ""
}

// TailscaleURL is the download URL of the static Tailscale tarball for an architecture ("arm" or "arm64").
func (r *RemoteHelpConfig) TailscaleURL(arch string) string {
	return "https://pkgs.tailscale.com/stable/tailscale_" + r.TailscaleVersion + "_" + arch + ".tgz"
}

func loadRemoteHelp(c *Config, get func(string) string, env func(k, def string) string) error {
	r := &c.RemoteHelp
	var errs []error
	switch v := strings.ToLower(env("KINFRAME_REMOTE_HELP", "off")); v {
	case "1", "on", "true", "yes":
		r.Enabled = true
	case "0", "off", "false", "no", "":
	default:
		errs = append(errs, fmt.Errorf("KINFRAME_REMOTE_HELP must be on or off, got %q", v))
	}
	r.HeadscaleURL = strings.TrimRight(env("KINFRAME_HEADSCALE_URL", ""), "/")
	if r.HeadscaleURL != "" {
		if u, err := url.Parse(r.HeadscaleURL); err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
			errs = append(errs, errors.New("KINFRAME_HEADSCALE_URL must be an http(s) URL"))
		}
	}
	key, err := secretValue(get, "KINFRAME_HEADSCALE_API_KEY")
	if err != nil {
		errs = append(errs, err)
	}
	r.HeadscaleAPIKey = key
	r.HeadscaleUser = env("KINFRAME_HEADSCALE_USER", "")
	r.ADBKey = strings.TrimSpace(env("KINFRAME_REMOTE_HELP_ADB_KEY", ""))
	if strings.ContainsAny(r.ADBKey, "\r\n") {
		errs = append(errs, errors.New("KINFRAME_REMOTE_HELP_ADB_KEY must be one line of adbkey.pub"))
	}
	switch strings.ToLower(env("KINFRAME_REMOTE_HELP_POLICY_CHECKED", "")) {
	case "1", "on", "true", "yes":
		r.PolicyChecked = true
	}
	r.TailscaleVersion = env("KINFRAME_REMOTE_HELP_TAILSCALE_VERSION", DefaultTailscaleVersion)
	if !versionRe.MatchString(r.TailscaleVersion) {
		errs = append(errs, fmt.Errorf("KINFRAME_REMOTE_HELP_TAILSCALE_VERSION must look like 1.104.7, got %q", r.TailscaleVersion))
	}
	r.TailscaleSHA256 = map[string]string{}
	for arch, def := range defaultTailscaleSHA256 {
		k := "KINFRAME_REMOTE_HELP_TAILSCALE_SHA256_" + strings.ToUpper(arch)
		v := strings.ToLower(env(k, ""))
		if v == "" && r.TailscaleVersion == DefaultTailscaleVersion {
			v = def
		}
		if v != "" && !sha256Re.MatchString(v) {
			errs = append(errs, fmt.Errorf("%s must be 64 hex characters", k))
		}
		if v == "" && r.Enabled {
			errs = append(errs, fmt.Errorf("%s is required for Tailscale %s", k, r.TailscaleVersion))
		}
		r.TailscaleSHA256[arch] = v
	}
	if r.Enabled {
		var missing []string
		for k, v := range map[string]string{"KINFRAME_HEADSCALE_URL": r.HeadscaleURL, "KINFRAME_HEADSCALE_API_KEY": r.HeadscaleAPIKey,
			"KINFRAME_HEADSCALE_USER": r.HeadscaleUser, "KINFRAME_REMOTE_HELP_ADB_KEY": r.ADBKey} {
			if v == "" {
				missing = append(missing, k)
			}
		}
		if len(missing) > 0 {
			errs = append(errs, fmt.Errorf("KINFRAME_REMOTE_HELP=on needs %s", strings.Join(slices.Sorted(slices.Values(missing)), ", ")))
		}
	}
	return errors.Join(errs...)
}
