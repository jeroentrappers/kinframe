package logtoboot

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"slices"
	"strings"
)

// SocialProvider is one social sign-in provider configured from the
// environment. It becomes a Logto social connector with a fixed instance ID,
// so its callback URL is predictable: <Logto ENDPOINT>/callback/<ID>.
type SocialProvider struct {
	Name      string         // human name for logs: "Google"
	ID        string         // Logto connector instance ID (and callback path segment)
	FactoryID string         // Logto connector factory, e.g. "google-universal"
	Target    string         // Logto connector target, used in socialSignInConnectorTargets
	Config    map[string]any // connector config
	secrets   []string       // values that must never appear in logs or errors
}

// socialSpec describes how one provider's env vars map to its connector.
type socialSpec struct {
	name, envPrefix, id, factoryID, target string
}

// socialSpecs, in the order the buttons appear on the sign-in page. Apple
// first: App Store guideline 4.8 wants it offered at least as prominently as
// the others.
var socialSpecs = []socialSpec{
	{"Apple", "KINFRAME_SOCIAL_APPLE", "apple", "apple-universal", "apple"},
	{"Google", "KINFRAME_SOCIAL_GOOGLE", "google", "google-universal", "google"},
	{"Microsoft", "KINFRAME_SOCIAL_MICROSOFT", "microsoft", "azuread-universal", "azuread"},
	{"Facebook", "KINFRAME_SOCIAL_FACEBOOK", "facebook", "facebook-universal", "facebook"},
	{"GitHub", "KINFRAME_SOCIAL_GITHUB", "github", "github-universal", "github"},
}

// Scopes: identity, email and profile only.
const (
	googleScope   = "openid profile email"
	appleScope    = "name email"
	facebookScope = "email,public_profile"
	githubScope   = "read:user user:email"
	msCloud       = "https://login.microsoftonline.com"
	msDefaultTen  = "common" // personal Microsoft accounts and work/school accounts
)

// Microsoft tenant: common, organizations, consumers, a tenant GUID or a
// verified domain name.
var msTenantRE = regexp.MustCompile(`^(common|organizations|consumers|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+)$`)

// loadSocial reads the KINFRAME_SOCIAL_* variables. A provider is on when its
// client ID is set; a half-configured provider is an error.
func loadSocial(env, raw func(string) string) (providers []SocialProvider, warnings []string, errs []error) {
	for _, s := range socialSpecs {
		id := env(s.envPrefix + "_CLIENT_ID")
		secret := strings.TrimSpace(raw(s.envPrefix + "_CLIENT_SECRET"))
		p := SocialProvider{Name: s.name, ID: s.id, FactoryID: s.factoryID, Target: s.target}
		switch s.id {
		case "apple":
			// Logto's Apple connector uses the hybrid `code id_token` flow and
			// only verifies the ID token against Apple's keys: it needs the
			// Services ID and nothing else (no team ID, key ID or .p8 key).
			for _, k := range []string{"_CLIENT_SECRET", "_TEAM_ID", "_KEY_ID", "_PRIVATE_KEY"} {
				if strings.TrimSpace(raw(s.envPrefix+k)) != "" {
					warnings = append(warnings, s.envPrefix+k+" is ignored: Logto's Apple connector only needs the Services ID ("+s.envPrefix+"_CLIENT_ID)")
				}
			}
			if id == "" {
				continue
			}
			p.Config = map[string]any{"clientId": id, "scope": appleScope}
		default:
			if id == "" && secret == "" {
				continue // off (a lone _TENANT is fine: deployments template it with its default)
			}
			if id == "" || secret == "" {
				errs = append(errs, fmt.Errorf("set both %s_CLIENT_ID and %s_CLIENT_SECRET", s.envPrefix, s.envPrefix))
				continue
			}
			p.secrets = []string{secret}
			switch s.id {
			case "google":
				p.Config = map[string]any{"clientId": id, "clientSecret": secret, "scope": googleScope,
					"prompts": []string{"select_account"}}
			case "microsoft":
				tenant := env(s.envPrefix + "_TENANT")
				if tenant == "" {
					tenant = msDefaultTen
				}
				if !msTenantRE.MatchString(tenant) {
					errs = append(errs, fmt.Errorf("%s_TENANT: want common, organizations, consumers, a tenant ID or a domain, got %q", s.envPrefix, tenant))
					continue
				}
				// Microsoft Graph's `mail` is not verified (any directory admin
				// can set it), so it must not be trusted for account linking:
				// Microsoft users confirm their email with a code instead.
				p.Config = map[string]any{"clientId": id, "clientSecret": secret, "cloudInstance": msCloud,
					"tenantId": tenant, "prompts": []string{"select_account"}, "disableEmailSync": true}
			case "facebook":
				p.Config = map[string]any{"clientId": id, "clientSecret": secret, "scope": facebookScope}
			case "github":
				p.Config = map[string]any{"clientId": id, "clientSecret": secret, "scope": githubScope}
			}
		}
		providers = append(providers, p)
	}
	return providers, warnings, errs
}

func (p *SocialProvider) redact(err error) error {
	if err == nil {
		return nil
	}
	msg := err.Error()
	for _, s := range p.secrets {
		if s != "" {
			msg = strings.ReplaceAll(msg, s, "[redacted]")
		}
	}
	return errors.New(msg)
}

// knownSocial finds the spec for a connector instance kinframe manages.
func knownSocial(id string) (socialSpec, bool) {
	for _, s := range socialSpecs {
		if s.id == id {
			return s, true
		}
	}
	return socialSpec{}, false
}

// ensureSocialConnectors creates or updates a connector per configured
// provider and returns the sign-in targets in display order: kinframe's
// providers (configured, or still present in Logto when not pruned), then any
// other social connectors already on the sign-in page.
func (b *Bootstrapper) ensureSocialConnectors(ctx context.Context, current []string) ([]string, error) {
	connectors, err := list[connector](ctx, b.m, "/connectors")
	if err != nil {
		return nil, err
	}
	byID := map[string]*connector{}
	byFactory := map[string]*connector{}
	for i := range connectors {
		c := &connectors[i]
		byID[c.ID] = c
		if _, ok := byFactory[c.ConnectorID]; !ok {
			byFactory[c.ConnectorID] = c
		}
	}

	present := map[string]bool{} // targets of kinframe-managed providers that stay on
	for i := range b.cfg.Social {
		p := &b.cfg.Social[i]
		have := byID[p.ID]
		if have == nil {
			if other := byFactory[p.FactoryID]; other != nil {
				// Added by hand in the console: adopt it rather than fail on
				// Logto's one-connector-per-provider rule.
				have = other
				b.log.Warn("using an existing connector created outside bootstrap; its callback URL differs from the documented one",
					"provider", p.Name, "callback", originOf(b.cfg.Endpoint)+"/callback/"+have.ID)
			}
		}
		if have == nil {
			if err := b.m.do(ctx, http.MethodPost, "/connectors", map[string]any{
				"id": p.ID, "connectorId": p.FactoryID, "config": p.Config,
			}, nil); err != nil {
				return nil, fmt.Errorf("%s connector: %w", p.Name, p.redact(err))
			}
			b.changed("created social connector", "provider", p.Name, "callback", originOf(b.cfg.Endpoint)+"/callback/"+p.ID)
		} else if !subsetEqual(p.Config, have.Config) {
			if err := b.m.do(ctx, http.MethodPatch, "/connectors/"+have.ID, map[string]any{"config": p.Config}, nil); err != nil {
				return nil, fmt.Errorf("%s connector: %w", p.Name, p.redact(err))
			}
			b.changed("updated social connector", "provider", p.Name)
		}
		present[p.Target] = true
	}

	// Connectors kinframe created earlier whose env vars are gone.
	for _, s := range socialSpecs {
		if present[s.target] {
			continue
		}
		c := byID[s.id]
		if c == nil {
			continue
		}
		if !b.cfg.SocialRemoveUnconfigured {
			b.log.Info("social provider not configured; leaving its existing connector as it is (set KINFRAME_SOCIAL_REMOVE_UNCONFIGURED=true to remove it)", "provider", s.name)
			present[s.target] = true
			continue
		}
		if err := b.m.do(ctx, http.MethodDelete, "/connectors/"+c.ID, nil, nil); err != nil {
			return nil, fmt.Errorf("remove %s connector: %w", s.name, err)
		}
		b.changed("removed social connector (provider no longer configured)", "provider", s.name)
	}

	var targets []string
	for _, s := range socialSpecs {
		if present[s.target] {
			targets = append(targets, s.target)
		}
	}
	// Keep social buttons someone added by hand for other providers.
	managed := func(t string) bool {
		return slices.ContainsFunc(socialSpecs, func(s socialSpec) bool { return s.target == t })
	}
	exists := func(t string) bool {
		return slices.ContainsFunc(connectors, func(c connector) bool { return c.Target == t && c.Type == "Social" })
	}
	for _, t := range current {
		if !managed(t) && exists(t) && !slices.Contains(targets, t) {
			targets = append(targets, t)
		}
	}
	return targets, nil
}
