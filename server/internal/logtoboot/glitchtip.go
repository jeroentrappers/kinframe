package logtoboot

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"slices"
	"time"
)

// GlitchTip (docs/spec/crash-reporting.md §1) signs operators in with Logto
// through django-allauth's OpenID Connect provider. The glitchtip Ansible role
// reads GlitchTipFile and registers the matching allauth SocialApp.
const (
	glitchtipAppName    = "GlitchTip"
	glitchtipProviderID = "logto" // allauth SocialApp.provider_id; part of the callback path
	glitchtipSecretName = "glitchtip"
	// allauth's OIDC callback (SOCIALACCOUNT_OPENID_CONNECT_URL_PREFIX "oidc",
	// mounted under /accounts/ by GlitchTip).
	glitchtipCallbackPath = "/accounts/oidc/" + glitchtipProviderID + "/login/callback/"
	// A secret that expires sooner than this is not handed out.
	secretMinLifetime = 24 * time.Hour
)

// GlitchTipFile is what the bootstrap writes for the glitchtip role. It holds
// the client secret: mode 0600.
type GlitchTipFile struct {
	Issuer       string              `json:"issuer"`
	ProviderID   string              `json:"provider_id"`
	ClientID     string              `json:"client_id"`
	ClientSecret string              `json:"client_secret"`
	RedirectURI  string              `json:"redirect_uri"`
	Operators    []GlitchTipOperator `json:"operators"`
}

// GlitchTipOperator is an operator email with its Logto user ID (the OIDC sub).
type GlitchTipOperator struct {
	Email string `json:"email"`
	Sub   string `json:"sub"`
}

type appSecret struct {
	Name      string `json:"name"`
	Value     string `json:"value"`
	CreatedAt int64  `json:"createdAt"`
	ExpiresAt *int64 `json:"expiresAt"` // epoch ms; nil = never
}

func glitchtipClientMetadata(origin string) oidcClientMetadata {
	return oidcClientMetadata{
		RedirectURIs:           []string{origin + glitchtipCallbackPath},
		PostLogoutRedirectURIs: []string{origin + "/"},
	}
}

// ensureGlitchTip registers GlitchTip as a Traditional web app with a client
// secret and writes its settings, with the operators' Logto user IDs, to
// GlitchTipFile. Without KINFRAME_LOGTO_GLITCHTIP_URL it does nothing (an
// existing app and file are left alone).
func (b *Bootstrapper) ensureGlitchTip(ctx context.Context, issuer string) error {
	if b.cfg.GlitchTipURL == nil {
		return nil
	}
	origin := originOf(b.cfg.GlitchTipURL)
	meta := glitchtipClientMetadata(origin)
	id, err := b.ensureApp(ctx, glitchtipAppName, "Traditional", "GlitchTip crash reports (kinframe operators only)", meta, nil)
	if err != nil {
		return err
	}

	// Keep handing out the secret the file already has while Logto still
	// accepts it, so GlitchTip is not reconfigured on every run.
	var prev GlitchTipFile
	if data, err := os.ReadFile(b.cfg.GlitchTipFile); err == nil {
		_ = json.Unmarshal(data, &prev)
	}
	keep := ""
	if prev.ClientID == id {
		keep = prev.ClientSecret
	}
	secret, err := b.ensureAppSecret(ctx, id, keep)
	if err != nil {
		return err
	}

	operators := []GlitchTipOperator{}
	for _, email := range b.cfg.OperatorEmails {
		u, err := b.m.findUserByEmail(ctx, email)
		if err != nil {
			return err
		}
		if u == nil {
			b.log.Info("operator has no Logto account yet, so cannot sign in to GlitchTip; re-run bootstrap after they sign up", "email", email)
			continue
		}
		operators = append(operators, GlitchTipOperator{Email: email, Sub: u.ID})
	}

	data, err := json.MarshalIndent(GlitchTipFile{
		Issuer:       issuer,
		ProviderID:   glitchtipProviderID,
		ClientID:     id,
		ClientSecret: secret,
		RedirectURI:  meta.RedirectURIs[0],
		Operators:    operators,
	}, "", "  ")
	if err != nil {
		return err
	}
	changed, err := writeFileAtomic(b.cfg.GlitchTipFile, append(data, '\n'), 0o600)
	if err != nil {
		return fmt.Errorf("write %s: %w", b.cfg.GlitchTipFile, err)
	}
	if changed {
		b.changed("wrote GlitchTip sign-in settings", "file", b.cfg.GlitchTipFile, "client_id", id, "operators", len(operators))
	}
	return nil
}

// ensureAppSecret returns a client secret of the application that stays valid
// for at least secretMinLifetime: keep if Logto still has it, else the oldest
// such secret (Logto creates one when it creates a Traditional app), else a
// new one. It never prints or logs the value.
func (b *Bootstrapper) ensureAppSecret(ctx context.Context, appID, keep string) (string, error) {
	var secrets []appSecret
	if err := b.m.do(ctx, http.MethodGet, "/applications/"+url.PathEscape(appID)+"/secrets", nil, &secrets); err != nil {
		return "", err
	}
	minExpiry := time.Now().Add(secretMinLifetime).UnixMilli()
	var valid []appSecret
	for _, s := range secrets {
		if s.Value != "" && (s.ExpiresAt == nil || *s.ExpiresAt > minExpiry) {
			valid = append(valid, s)
		}
	}
	if keep != "" && slices.ContainsFunc(valid, func(s appSecret) bool { return s.Value == keep }) {
		return keep, nil
	}
	if len(valid) > 0 {
		// Prefer secrets that never expire, then the oldest: a stable choice.
		slices.SortStableFunc(valid, func(x, y appSecret) int {
			if (x.ExpiresAt == nil) != (y.ExpiresAt == nil) {
				if x.ExpiresAt == nil {
					return -1
				}
				return 1
			}
			switch {
			case x.CreatedAt < y.CreatedAt:
				return -1
			case x.CreatedAt > y.CreatedAt:
				return 1
			}
			return 0
		})
		return valid[0].Value, nil
	}

	name := glitchtipSecretName
	if slices.ContainsFunc(secrets, func(s appSecret) bool { return s.Name == name }) {
		name += "-" + time.Now().UTC().Format("20060102-150405")
	}
	var created appSecret
	if err := b.m.do(ctx, http.MethodPost, "/applications/"+url.PathEscape(appID)+"/secrets", map[string]any{"name": name}, &created); err != nil {
		return "", err
	}
	if created.Value == "" {
		return "", errors.New("logto returned no secret value")
	}
	b.changed("created application secret", "name", glitchtipAppName, "secret", name)
	return created.Value, nil
}
