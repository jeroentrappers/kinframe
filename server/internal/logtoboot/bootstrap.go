package logtoboot

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/config"
)

// Names kinframe gives its objects in Logto. Re-runs find them by these names
// (and the API resource by its indicator).
const (
	resourceName      = "kinframe API"
	resourceTokenTTL  = 3600 // seconds
	operatorRoleName  = "kinframe-operator"
	webAppName        = "kinframe web"
	nativeAppName     = "kinframe app"
	desktopAppName    = "kinframe desktop"
	bootstrapAppName  = "kinframe bootstrap"
	mobileScheme      = "app.kinframe"
	nameProfileField  = "name"
	readyTimeout      = 3 * time.Minute
	readyPollInterval = 2 * time.Second
)

// Bootstrapper holds the state of one run.
type Bootstrapper struct {
	cfg     *Config
	log     *slog.Logger
	out     io.Writer // receives the one-time M2M credentials
	hc      *http.Client
	m       *mgmt
	changes int
}

// Run configures Logto for kinframe. It is idempotent.
func Run(ctx context.Context, cfg *Config, log *slog.Logger, out io.Writer) error {
	transport := newRewriteTransport(http.DefaultTransport,
		[2]*url.URL{cfg.Endpoint, cfg.URL},
		[2]*url.URL{cfg.AdminEndpoint, cfg.AdminURL})
	b := &Bootstrapper{
		cfg: cfg,
		log: log,
		out: out,
		hc:  &http.Client{Transport: transport, Timeout: 30 * time.Second},
	}
	return b.run(ctx, transport)
}

func (b *Bootstrapper) changed(msg string, args ...any) {
	b.changes++
	b.log.Info(msg, args...)
}

func (b *Bootstrapper) run(ctx context.Context, transport http.RoundTripper) error {
	endpoint := originOf(b.cfg.Endpoint)
	if err := b.waitReady(ctx, endpoint+"/api/status"); err != nil {
		return err
	}

	// Management API access: the M2M app if configured, else the console admin.
	var token string
	usingAdmin := b.cfg.M2MID == ""
	if usingAdmin {
		admin := originOf(b.cfg.AdminEndpoint)
		if err := b.waitReady(ctx, admin+"/api/status"); err != nil {
			return err
		}
		s := newAdminSession(transport, admin, b.log.Info)
		t, created, err := s.token(ctx, b.cfg.AdminUsername, b.cfg.AdminPassword)
		if err != nil {
			return err
		}
		if created {
			b.changed("created the Logto console admin account; console registration is now closed", "username", b.cfg.AdminUsername)
		}
		token = t
	} else {
		t, err := postToken(ctx, b.hc, endpoint+"/oidc/token", url.Values{
			"grant_type": {"client_credentials"},
			"resource":   {managementAPIResource},
			"scope":      {managementAPIScope},
		}, b.cfg.M2MID, b.cfg.M2MSecret)
		if err != nil {
			return fmt.Errorf("management API token for KINFRAME_LOGTO_M2M_ID: %w", err)
		}
		token = t.AccessToken
	}
	b.m = &mgmt{hc: b.hc, base: endpoint + "/api", token: token}

	issuer, err := b.issuer(ctx, endpoint)
	if err != nil {
		return err
	}
	if usingAdmin {
		if err := b.ensureBootstrapApp(ctx); err != nil {
			return fmt.Errorf("bootstrap M2M app: %w", err)
		}
	}

	scopeID, err := b.ensureResource(ctx)
	if err != nil {
		return fmt.Errorf("API resource: %w", err)
	}
	if err := b.ensureOperatorRole(ctx, scopeID); err != nil {
		return fmt.Errorf("operator role: %w", err)
	}
	origin := b.cfg.Origin()
	webID, err := b.ensureApp(ctx, webAppName, "SPA", "kinframe web app (SPA)",
		oidcClientMetadata{
			RedirectURIs:           []string{origin + "/callback"},
			PostLogoutRedirectURIs: []string{origin + "/"},
		},
		map[string]any{"corsAllowedOrigins": []string{origin}})
	if err != nil {
		return fmt.Errorf("web app: %w", err)
	}
	mobileID, err := b.ensureApp(ctx, nativeAppName, "Native", "kinframe family app (Android and iOS)",
		oidcClientMetadata{
			RedirectURIs:           []string{mobileScheme + ":/callback", origin + "/app/callback"},
			PostLogoutRedirectURIs: []string{mobileScheme + ":/logout", origin + "/app/logout"},
		}, nil)
	if err != nil {
		return fmt.Errorf("mobile app: %w", err)
	}
	desktopID, err := b.ensureDesktopApp(ctx)
	if err != nil {
		return fmt.Errorf("desktop app: %w", err)
	}
	if err := b.ensureEmailConnector(ctx); err != nil {
		return fmt.Errorf("email connector: %w", err)
	}
	if err := b.ensureEmailTemplates(ctx); err != nil {
		// Not fatal: the connector's English templates still work.
		b.log.Warn("could not store localized verification emails; they stay in English", "err", err)
	}
	if err := b.ensureNameProfileField(ctx); err != nil {
		return fmt.Errorf("name profile field: %w", err)
	}
	if err := b.ensureSignInExperience(ctx); err != nil {
		return fmt.Errorf("sign-in experience: %w", err)
	}
	if err := b.ensureTokenClaims(ctx); err != nil {
		return fmt.Errorf("access token claims: %w", err)
	}

	if err := b.writeOutput(config.LogtoFile{
		Issuer:          issuer,
		WebClientID:     webID,
		MobileClientID:  mobileID,
		DesktopClientID: desktopID,
		APIResource:     b.cfg.APIResource(),
	}); err != nil {
		return fmt.Errorf("write %s: %w", b.cfg.OutputFile, err)
	}
	if b.changes == 0 {
		b.log.Info("Logto already configured; nothing changed")
	} else {
		b.log.Info("Logto configured", "changes", b.changes)
	}
	return nil
}

func (b *Bootstrapper) waitReady(ctx context.Context, statusURL string) error {
	deadline := time.Now().Add(readyTimeout)
	for {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, statusURL, nil)
		resp, err := b.hc.Do(req)
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode < 300 {
				return nil
			}
			err = fmt.Errorf("status %d", resp.StatusCode)
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("logto not ready at %s: %w", redactURL(statusURL), err)
		}
		b.log.Info("waiting for Logto", "url", statusURL, "err", err)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(readyPollInterval):
		}
	}
}

func (b *Bootstrapper) issuer(ctx context.Context, endpoint string) (string, error) {
	var doc struct {
		Issuer string `json:"issuer"`
	}
	if err := doJSON(ctx, b.hc, http.MethodGet, endpoint+"/oidc/.well-known/openid-configuration", nil, nil, &doc); err != nil {
		return "", fmt.Errorf("oidc discovery: %w", err)
	}
	if doc.Issuer == "" {
		return "", errors.New("oidc discovery: no issuer")
	}
	return doc.Issuer, nil
}

// ensureBootstrapApp creates the machine-to-machine app later runs use instead
// of the admin password, and prints its credentials once.
func (b *Bootstrapper) ensureBootstrapApp(ctx context.Context) error {
	apps, err := list[application](ctx, b.m, "/applications")
	if err != nil {
		return err
	}
	var app *application
	for i := range apps {
		if apps[i].Name == bootstrapAppName && apps[i].Type == "MachineToMachine" {
			app = &apps[i]
			break
		}
	}
	roles, err := list[role](ctx, b.m, "/roles?type=MachineToMachine")
	if err != nil {
		return err
	}
	var roleID string
	for _, r := range roles {
		if r.Name == managementAccessRoleName {
			roleID = r.ID
		}
	}
	if roleID == "" {
		return fmt.Errorf("role %q not found in Logto", managementAccessRoleName)
	}

	if app == nil {
		var created application
		if err := b.m.do(ctx, http.MethodPost, "/applications", map[string]any{
			"name":        bootstrapAppName,
			"type":        "MachineToMachine",
			"description": "Used by kinframe-server bootstrap-logto to manage this Logto instance.",
		}, &created); err != nil {
			return err
		}
		if err := b.m.do(ctx, http.MethodPost, "/applications/"+created.ID+"/roles", map[string]any{"roleIds": []string{roleID}}, nil); err != nil {
			return err
		}
		var secret struct {
			Value string `json:"value"`
		}
		if err := b.m.do(ctx, http.MethodPost, "/applications/"+created.ID+"/secrets", map[string]any{"name": "kinframe-bootstrap"}, &secret); err != nil {
			return err
		}
		if secret.Value == "" {
			return errors.New("logto returned no secret for the bootstrap app")
		}
		b.changed("created machine-to-machine app with Management API access", "name", bootstrapAppName, "id", created.ID)
		fmt.Fprintf(b.out, `
# kinframe bootstrap-logto: Management API credentials (shown only once).
# Store them with your secrets and set them for later bootstrap runs;
# KINFRAME_LOGTO_ADMIN_PASSWORD is then no longer needed by the bootstrap.
KINFRAME_LOGTO_M2M_ID=%s
KINFRAME_LOGTO_M2M_SECRET=%s

`, created.ID, secret.Value)
		return nil
	}

	assigned, err := list[role](ctx, b.m, "/applications/"+app.ID+"/roles")
	if err != nil {
		return err
	}
	if !slices.ContainsFunc(assigned, func(r role) bool { return r.ID == roleID }) {
		if err := b.m.do(ctx, http.MethodPost, "/applications/"+app.ID+"/roles", map[string]any{"roleIds": []string{roleID}}, nil); err != nil {
			return err
		}
		b.changed("gave the bootstrap app Management API access", "id", app.ID)
	}
	b.log.Info("bootstrap M2M app exists; its secret was printed when it was created (delete the app in the console and re-run to get a new one)", "id", app.ID)
	return nil
}

// ensureResource returns the ID of the kinframe:operate scope.
func (b *Bootstrapper) ensureResource(ctx context.Context) (string, error) {
	indicator := b.cfg.APIResource()
	resources, err := list[resource](ctx, b.m, "/resources")
	if err != nil {
		return "", err
	}
	var res *resource
	for i := range resources {
		if resources[i].Indicator == indicator {
			res = &resources[i]
		}
	}
	if res == nil {
		res = new(resource)
		if err := b.m.do(ctx, http.MethodPost, "/resources", map[string]any{
			"name": resourceName, "indicator": indicator, "accessTokenTtl": resourceTokenTTL,
		}, res); err != nil {
			return "", err
		}
		b.changed("created API resource", "indicator", indicator)
	} else if res.Name != resourceName || res.AccessTokenTTL != resourceTokenTTL {
		if err := b.m.do(ctx, http.MethodPatch, "/resources/"+res.ID, map[string]any{
			"name": resourceName, "accessTokenTtl": resourceTokenTTL,
		}, nil); err != nil {
			return "", err
		}
		b.changed("updated API resource", "indicator", indicator)
	}

	scopes, err := list[scope](ctx, b.m, "/resources/"+res.ID+"/scopes")
	if err != nil {
		return "", err
	}
	for _, s := range scopes {
		if s.Name == auth.OperatorScope {
			return s.ID, nil
		}
	}
	var s scope
	if err := b.m.do(ctx, http.MethodPost, "/resources/"+res.ID+"/scopes", map[string]any{
		"name": auth.OperatorScope, "description": "Operate this kinframe server (limits, users, frames).",
	}, &s); err != nil {
		return "", err
	}
	b.changed("created scope", "scope", auth.OperatorScope)
	return s.ID, nil
}

func (b *Bootstrapper) ensureOperatorRole(ctx context.Context, scopeID string) error {
	roles, err := list[role](ctx, b.m, "/roles?type=User")
	if err != nil {
		return err
	}
	var roleID string
	for _, r := range roles {
		if r.Name == operatorRoleName {
			roleID = r.ID
		}
	}
	if roleID == "" {
		var r role
		if err := b.m.do(ctx, http.MethodPost, "/roles", map[string]any{
			"name":        operatorRoleName,
			"description": "kinframe server operators",
			"type":        "User",
			"scopeIds":    []string{scopeID},
		}, &r); err != nil {
			return err
		}
		roleID = r.ID
		b.changed("created role", "role", operatorRoleName)
	} else {
		scopes, err := list[scope](ctx, b.m, "/roles/"+roleID+"/scopes")
		if err != nil {
			return err
		}
		if !slices.ContainsFunc(scopes, func(s scope) bool { return s.ID == scopeID }) {
			if err := b.m.do(ctx, http.MethodPost, "/roles/"+roleID+"/scopes", map[string]any{"scopeIds": []string{scopeID}}, nil); err != nil {
				return err
			}
			b.changed("added scope to role", "role", operatorRoleName, "scope", auth.OperatorScope)
		}
	}

	for _, email := range b.cfg.OperatorEmails {
		u, err := b.m.findUserByEmail(ctx, email)
		if err != nil {
			return err
		}
		if u == nil {
			b.log.Info("operator has no Logto account yet; re-run bootstrap after they sign up", "email", email)
			continue
		}
		have, err := list[role](ctx, b.m, "/users/"+u.ID+"/roles")
		if err != nil {
			return err
		}
		if slices.ContainsFunc(have, func(r role) bool { return r.ID == roleID }) {
			continue
		}
		if err := b.m.do(ctx, http.MethodPost, "/users/"+u.ID+"/roles", map[string]any{"roleIds": []string{roleID}}, nil); err != nil {
			return err
		}
		b.changed("assigned operator role", "email", email)
	}
	return nil
}

// desktopRedirect is the loopback redirect of the desktop client (RFC 8252
// §7.3). Logto (oidc-provider) ignores the port of loopback redirect URIs for
// Native applications, so this one entry covers the random port the desktop
// app listens on: http://127.0.0.1:53127/callback matches. Post-logout
// redirects get no such port leniency, so the desktop app signs out by
// revoking its refresh token instead of a browser round trip.
var desktopClientMetadata = oidcClientMetadata{
	RedirectURIs:           []string{"http://127.0.0.1/callback"},
	PostLogoutRedirectURIs: []string{},
}

// ensureDesktopApp registers the Native app the Windows, macOS and Linux
// client signs in with. It is separate from the mobile app so operators can
// see and revoke desktop sessions on their own.
func (b *Bootstrapper) ensureDesktopApp(ctx context.Context) (string, error) {
	return b.ensureApp(ctx, desktopAppName, "Native", "kinframe desktop app (Windows, macOS and Linux)", desktopClientMetadata, nil)
}

// ensureApp returns the client ID of the named application.
func (b *Bootstrapper) ensureApp(ctx context.Context, name, typ, description string, meta oidcClientMetadata, custom map[string]any) (string, error) {
	apps, err := list[application](ctx, b.m, "/applications")
	if err != nil {
		return "", err
	}
	var app *application
	for i := range apps {
		if apps[i].Name == name && apps[i].Type == typ {
			if app != nil {
				b.log.Warn("several Logto applications have the same name; using the first", "name", name)
				break
			}
			app = &apps[i]
		}
	}
	if app == nil {
		body := map[string]any{"name": name, "type": typ, "description": description, "oidcClientMetadata": meta}
		if custom != nil {
			body["customClientMetadata"] = custom
		}
		var created application
		if err := b.m.do(ctx, http.MethodPost, "/applications", body, &created); err != nil {
			return "", err
		}
		b.changed("created application", "name", name, "type", typ, "client_id", created.ID)
		return created.ID, nil
	}

	patch := map[string]any{}
	if !jsonEqual(meta, app.OIDCClientMetadata) {
		patch["oidcClientMetadata"] = meta
	}
	if custom != nil && !subsetEqual(custom, app.CustomClientMetadata) {
		merged := map[string]any{}
		for k, v := range app.CustomClientMetadata {
			merged[k] = v
		}
		for k, v := range custom {
			merged[k] = v
		}
		patch["customClientMetadata"] = merged
	}
	if len(patch) > 0 {
		if err := b.m.do(ctx, http.MethodPatch, "/applications/"+app.ID, patch, nil); err != nil {
			return "", err
		}
		b.changed("updated application", "name", name, "client_id", app.ID)
	}
	return app.ID, nil
}

func (b *Bootstrapper) ensureEmailConnector(ctx context.Context) error {
	connectors, err := list[connector](ctx, b.m, "/connectors")
	if err != nil {
		return err
	}
	var smtp, other *connector
	for i := range connectors {
		switch {
		case connectors[i].ConnectorID == smtpConnectorID:
			smtp = &connectors[i]
		case connectors[i].Type == "Email":
			other = &connectors[i]
		}
	}
	s := b.cfg.SMTP
	if s == nil {
		if smtp != nil || other != nil {
			b.log.Info("KINFRAME_SMTP_HOST not set; keeping the existing email connector")
			return nil
		}
		return errors.New("no email connector in Logto: set KINFRAME_SMTP_HOST, KINFRAME_SMTP_PORT, KINFRAME_SMTP_USER, KINFRAME_SMTP_PASSWORD and KINFRAME_SMTP_FROM (sign-in uses one-time email codes)")
	}
	if other != nil {
		b.log.Warn("another email connector is configured in Logto; leaving it and not adding SMTP", "connector", other.ConnectorID)
		return nil
	}

	want := smtpConfig(s)
	redact := func(err error) error {
		if err == nil || s.Password == "" {
			return err
		}
		return errors.New(strings.ReplaceAll(err.Error(), s.Password, "[redacted]"))
	}
	if smtp == nil {
		if err := b.m.do(ctx, http.MethodPost, "/connectors", map[string]any{
			"connectorId": smtpConnectorID, "config": want,
		}, nil); err != nil {
			return redact(err)
		}
		b.changed("created SMTP email connector", "host", s.Host, "port", s.Port, "from", s.From)
		return nil
	}
	if subsetEqual(want, smtp.Config) {
		return nil
	}
	if err := b.m.do(ctx, http.MethodPatch, "/connectors/"+smtp.ID, map[string]any{"config": want}, nil); err != nil {
		return redact(err)
	}
	b.changed("updated SMTP email connector", "host", s.Host, "port", s.Port, "from", s.From)
	return nil
}

// smtpConfig is the connector-smtp configuration. Port 465 uses implicit TLS,
// 587 requires STARTTLS; other ports (local relays, test sinks) use STARTTLS
// only when offered.
func smtpConfig(s *SMTPConfig) map[string]any {
	authCfg := map[string]any{}
	if s.User != "" {
		authCfg = map[string]any{"type": "login", "user": s.User, "pass": s.Password}
	}
	return map[string]any{
		"host":       s.Host,
		"port":       s.Port,
		"auth":       authCfg,
		"fromEmail":  s.From,
		"secure":     s.Port == 465,
		"requireTLS": s.Port == 587,
		"templates":  emailTemplates(),
	}
}

// ensureNameProfileField asks new users for their name at sign-up (Logto's
// built-in `name` profile field), so tokens can carry it.
func (b *Bootstrapper) ensureNameProfileField(ctx context.Context) error {
	want := map[string]any{
		"name":        nameProfileField,
		"type":        "Text",
		"label":       "Your name",
		"description": "Shown to your family on kinframe.",
		"required":    true,
		"config":      map[string]any{"maxLength": 40},
	}
	var have map[string]any
	err := b.m.do(ctx, http.MethodGet, "/custom-profile-fields/"+nameProfileField, nil, &have)
	switch {
	case isStatus(err, http.StatusNotFound):
		if err := b.m.do(ctx, http.MethodPost, "/custom-profile-fields", want, nil); err != nil {
			return err
		}
		b.changed("created sign-up profile field", "field", nameProfileField)
		return nil
	case err != nil:
		return err
	}
	if subsetEqual(want, have) {
		return nil
	}
	put := maps(want)
	delete(put, "name")
	if err := b.m.do(ctx, http.MethodPut, "/custom-profile-fields/"+nameProfileField, put, nil); err != nil {
		return err
	}
	b.changed("updated sign-up profile field", "field", nameProfileField)
	return nil
}

func maps(m map[string]any) map[string]any {
	out := make(map[string]any, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}

// signInExperience: passwordless email codes for sign-up and sign-in,
// passkeys offered, the name asked at sign-up, language from the browser.
func signInExperience() map[string]any {
	return map[string]any{
		"signInMode": "SignInAndRegister",
		"signUp": map[string]any{
			"identifiers":          []string{"email"},
			"password":             false,
			"verify":               true,
			"secondaryIdentifiers": []any{},
		},
		"signIn": map[string]any{
			"methods": []any{map[string]any{
				"identifier": "email", "password": false, "verificationCode": true, "isPasswordPrimary": false,
			}},
		},
		"languageInfo":        map[string]any{"autoDetect": true, "fallbackLanguage": "en"},
		"passkeySignIn":       map[string]any{"enabled": true, "showPasskeyButton": true, "allowAutofill": true},
		"signUpProfileFields": []any{map[string]any{"name": nameProfileField}},
	}
}

func (b *Bootstrapper) ensureSignInExperience(ctx context.Context) error {
	var have map[string]any
	if err := b.m.do(ctx, http.MethodGet, "/sign-in-exp", nil, &have); err != nil {
		return err
	}
	want := signInExperience()
	if subsetEqual(want, have) {
		return nil
	}
	err := b.m.do(ctx, http.MethodPatch, "/sign-in-exp", want, nil)
	var ae *apiError
	if errors.As(err, &ae) && ae.Status < 500 {
		// Passkey sign-in is newer than the rest; don't fail the whole
		// setup if this Logto build refuses it.
		delete(want, "passkeySignIn")
		if err2 := b.m.do(ctx, http.MethodPatch, "/sign-in-exp", want, nil); err2 == nil {
			b.log.Warn("Logto refused passkey sign-in; configured email codes only", "err", err)
			err = nil
		}
	}
	if err != nil {
		return err
	}
	b.changed("updated sign-in experience (email one-time codes, passkeys, name at sign-up)")
	return nil
}

// tokenClaimsScript is a Logto custom JWT script (runs inside Logto). It adds
// the user's name and email to access tokens for the kinframe API only.
const tokenClaimsScript = `const getCustomJwtClaims = async ({ token, context, environmentVariables }) => {
  const resource = environmentVariables.KINFRAME_API_RESOURCE;
  const aud = Array.isArray(token.aud) ? token.aud : [token.aud];
  if (!resource || !aud.includes(resource)) {
    return {};
  }
  const user = (context && context.user) || {};
  const claims = {};
  if (user.name) {
    claims.name = user.name;
  }
  if (user.primaryEmail) {
    claims.email = user.primaryEmail;
  }
  return claims;
};
`

func (b *Bootstrapper) ensureTokenClaims(ctx context.Context) error {
	want := map[string]any{
		"script":               tokenClaimsScript,
		"environmentVariables": map[string]string{"KINFRAME_API_RESOURCE": b.cfg.APIResource()},
	}
	var have map[string]any
	err := b.m.do(ctx, http.MethodGet, "/configs/jwt-customizer/access-token", nil, &have)
	if err != nil && !isStatus(err, http.StatusNotFound) {
		return err
	}
	if err == nil && subsetEqual(want, have) {
		return nil
	}
	if err := b.m.do(ctx, http.MethodPut, "/configs/jwt-customizer/access-token", want, nil); err != nil {
		return err
	}
	b.changed("configured custom access token claims (name, email)")
	return nil
}

func (b *Bootstrapper) writeOutput(lf config.LogtoFile) error {
	data, err := json.MarshalIndent(lf, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	path := b.cfg.OutputFile
	if old, err := os.ReadFile(path); err == nil && bytes.Equal(old, data) {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".logto-*.json")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(0o644); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp.Name(), path); err != nil {
		return err
	}
	b.changed("wrote Logto client settings for discovery", "file", path)
	return nil
}
