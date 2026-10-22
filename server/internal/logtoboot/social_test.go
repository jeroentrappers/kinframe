package logtoboot

import (
	"errors"
	"net/url"
	"strings"
	"testing"
)

func envOf(kv map[string]string) func(string) string { return func(k string) string { return kv[k] } }

func TestLoadSocial(t *testing.T) {
	all := map[string]string{
		"KINFRAME_SOCIAL_GITHUB_CLIENT_ID":        "gh-id",
		"KINFRAME_SOCIAL_GITHUB_CLIENT_SECRET":    "gh-secret",
		"KINFRAME_SOCIAL_GOOGLE_CLIENT_ID":        "g-id",
		"KINFRAME_SOCIAL_GOOGLE_CLIENT_SECRET":    " g-secret ",
		"KINFRAME_SOCIAL_MICROSOFT_CLIENT_ID":     "ms-id",
		"KINFRAME_SOCIAL_MICROSOFT_CLIENT_SECRET": "ms-secret",
		"KINFRAME_SOCIAL_FACEBOOK_CLIENT_ID":      "fb-id",
		"KINFRAME_SOCIAL_FACEBOOK_CLIENT_SECRET":  "fb-secret",
		"KINFRAME_SOCIAL_APPLE_CLIENT_ID":         "app.kinframe.signin",
	}
	ps, warns, errs := loadSocial(envOf(all), envOf(all))
	if len(errs) != 0 || len(warns) != 0 {
		t.Fatalf("errs %v warns %v", errs, warns)
	}
	var ids []string
	for _, p := range ps {
		ids = append(ids, p.ID+"="+p.FactoryID)
	}
	if got := strings.Join(ids, ","); got != "apple=apple-universal,google=google-universal,microsoft=azuread-universal,facebook=facebook-universal,github=github-universal" {
		t.Fatalf("order/ids: %s", got)
	}
	byID := map[string]SocialProvider{}
	for _, p := range ps {
		byID[p.ID] = p
	}
	if c := byID["apple"].Config; len(c) != 2 || c["clientId"] != "app.kinframe.signin" || c["scope"] != "name email" {
		t.Errorf("apple config %v", c)
	}
	if c := byID["google"].Config; c["clientSecret"] != "g-secret" || c["scope"] != "openid profile email" {
		t.Errorf("google config %v", c)
	}
	if c := byID["microsoft"].Config; c["tenantId"] != "common" || c["disableEmailSync"] != true || c["cloudInstance"] != "https://login.microsoftonline.com" {
		t.Errorf("microsoft config %v", c)
	}
	if c := byID["facebook"].Config; c["scope"] != "email,public_profile" {
		t.Errorf("facebook config %v", c)
	}
	if c := byID["github"].Config; c["scope"] != "read:user user:email" {
		t.Errorf("github config %v", c)
	}
	gh := byID["github"]
	if err := gh.redact(errors.New("bad gh-secret here")); strings.Contains(err.Error(), "gh-secret") {
		t.Errorf("secret not redacted: %v", err)
	}

	// Nothing set (Ansible still templates the tenant default): no providers, no errors.
	off := map[string]string{"KINFRAME_SOCIAL_MICROSOFT_TENANT": "common", "KINFRAME_SOCIAL_GOOGLE_CLIENT_ID": ""}
	if ps, _, errs := loadSocial(envOf(off), envOf(off)); len(ps) != 0 || len(errs) != 0 {
		t.Fatalf("empty env: %v %v", ps, errs)
	}

	for name, tc := range map[string]struct {
		kv   map[string]string
		want string
	}{
		"id without secret": {map[string]string{"KINFRAME_SOCIAL_GOOGLE_CLIENT_ID": "x"}, "set both KINFRAME_SOCIAL_GOOGLE_CLIENT_ID and KINFRAME_SOCIAL_GOOGLE_CLIENT_SECRET"},
		"secret without id": {map[string]string{"KINFRAME_SOCIAL_GITHUB_CLIENT_SECRET": "x"}, "set both KINFRAME_SOCIAL_GITHUB_CLIENT_ID"},
		"bad tenant": {map[string]string{"KINFRAME_SOCIAL_MICROSOFT_CLIENT_ID": "x", "KINFRAME_SOCIAL_MICROSOFT_CLIENT_SECRET": "y",
			"KINFRAME_SOCIAL_MICROSOFT_TENANT": "not a tenant"}, "KINFRAME_SOCIAL_MICROSOFT_TENANT"},
	} {
		t.Run(name, func(t *testing.T) {
			_, _, errs := loadSocial(envOf(tc.kv), envOf(tc.kv))
			if err := errors.Join(errs...); err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want %q", err, tc.want)
			}
		})
	}

	for _, tenant := range []string{"organizations", "consumers", "contoso.onmicrosoft.com", "72f988bf-86f1-41af-91ab-2d7cd011db47"} {
		kv := map[string]string{"KINFRAME_SOCIAL_MICROSOFT_CLIENT_ID": "x", "KINFRAME_SOCIAL_MICROSOFT_CLIENT_SECRET": "y", "KINFRAME_SOCIAL_MICROSOFT_TENANT": tenant}
		if ps, _, errs := loadSocial(envOf(kv), envOf(kv)); len(errs) != 0 || ps[0].Config["tenantId"] != tenant {
			t.Errorf("tenant %q: %v", tenant, errs)
		}
	}

	// Apple key material is not used by Logto's connector: warn, don't fail, never echo it.
	kv := map[string]string{"KINFRAME_SOCIAL_APPLE_CLIENT_ID": "s", "KINFRAME_SOCIAL_APPLE_TEAM_ID": "T", "KINFRAME_SOCIAL_APPLE_PRIVATE_KEY": "-----BEGIN PRIVATE KEY-----"}
	ps, warns, errs = loadSocial(envOf(kv), envOf(kv))
	if len(errs) != 0 || len(ps) != 1 || len(warns) != 2 {
		t.Fatalf("apple extras: %v %v %v", ps, warns, errs)
	}
	if strings.Contains(strings.Join(warns, " "), "BEGIN PRIVATE") {
		t.Fatal("warning leaks the key")
	}
}

func TestLoadConfigSocialAndBranding(t *testing.T) {
	m := map[string]string{
		"KINFRAME_PUBLIC_URL":                 "https://kinframe.example.org",
		"KINFRAME_LOGTO_ENDPOINT":             "https://auth.example.org",
		"KINFRAME_LOGTO_M2M_ID":               "id",
		"KINFRAME_LOGTO_M2M_SECRET":           "s",
		"KINFRAME_SOCIAL_GITHUB_CLIENT_ID":    "a",
		"KINFRAME_SOCIAL_REMOVE_UNCONFIGURED": "yes",
		"KINFRAME_PRIVACY_URL":                "https://kinframe.example.org/privacy",
	}
	get := envOf(m)
	if _, err := loadConfig(get); err == nil || !strings.Contains(err.Error(), "KINFRAME_SOCIAL_GITHUB_CLIENT_SECRET") {
		t.Fatalf("half provider accepted: %v", err)
	}
	m["KINFRAME_SOCIAL_GITHUB_CLIENT_SECRET"] = "b"
	c, err := loadConfig(get)
	if err != nil {
		t.Fatal(err)
	}
	if len(c.Social) != 1 || !c.SocialRemoveUnconfigured || !c.SocialLinkByEmail || !c.Branding || c.PrivacyURL == "" || c.TermsURL != "" {
		t.Fatalf("config %+v", c)
	}
	m["KINFRAME_SOCIAL_LINK_BY_EMAIL"] = "maybe"
	if _, err := loadConfig(get); err == nil || !strings.Contains(err.Error(), "KINFRAME_SOCIAL_LINK_BY_EMAIL") {
		t.Fatalf("bad bool accepted: %v", err)
	}
	m["KINFRAME_SOCIAL_LINK_BY_EMAIL"] = "false"
	m["KINFRAME_TERMS_URL"] = "ftp://x"
	if _, err := loadConfig(get); err == nil || !strings.Contains(err.Error(), "KINFRAME_TERMS_URL") {
		t.Fatalf("bad terms URL accepted: %v", err)
	}
}

func TestSignInExperience(t *testing.T) {
	pub, _ := url.Parse("https://kinframe.example.org")
	b := &Bootstrapper{cfg: &Config{PublicURL: pub, Branding: true, SocialLinkByEmail: true}}
	exp := b.signInExperience([]string{"apple", "google"})
	if !jsonEqual(exp["socialSignInConnectorTargets"], []string{"apple", "google"}) {
		t.Errorf("targets %v", exp["socialSignInConnectorTargets"])
	}
	if !jsonEqual(exp["socialSignIn"], map[string]any{"automaticAccountLinking": true, "skipRequiredIdentifiers": false}) {
		t.Errorf("socialSignIn %v", exp["socialSignIn"])
	}
	css := exp["customCss"].(string)
	if strings.Contains(css, "{{ORIGIN}}") || !strings.Contains(css, "https://kinframe.example.org/fonts/") {
		t.Errorf("CSS origin not filled in")
	}
	if exp["termsOfUseUrl"] != nil || exp["privacyPolicyUrl"] != nil {
		t.Errorf("terms/privacy should be off by default")
	}
	br := exp["branding"].(map[string]any)
	if br["logoUrl"] != "https://kinframe.example.org/logo-light.svg" || br["darkLogoUrl"] != "https://kinframe.example.org/logo-dark.svg" {
		t.Errorf("branding %v", br)
	}

	// No providers: an empty list (clears the buttons), never null; branding off leaves the look alone.
	b.cfg.Branding = false
	exp = b.signInExperience(nil)
	if ts, ok := exp["socialSignInConnectorTargets"].([]string); !ok || ts == nil {
		t.Errorf("targets must be an empty list: %#v", exp["socialSignInConnectorTargets"])
	}
	if _, ok := exp["customCss"]; ok {
		t.Error("branding off must leave the look alone")
	}
}

func TestSubsetEqualEmptyObject(t *testing.T) {
	// Logto drops an empty SMTP "auth" object; that must not count as drift.
	if !subsetEqual(map[string]any{"auth": map[string]any{}, "host": "h"}, map[string]any{"host": "h"}) {
		t.Fatal("empty object vs absent should be equal")
	}
}
