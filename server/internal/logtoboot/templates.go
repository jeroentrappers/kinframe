package logtoboot

import (
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"path"
	"slices"
	"strings"
)

// SMTP connector ID shipped with Logto OSS (packages/connectors/connector-smtp).
const smtpConnectorID = "simple-mail-transfer-protocol"

type emailTemplate struct {
	UsageType   string `json:"usageType"`
	ContentType string `json:"contentType"`
	Subject     string `json:"subject"`
	Content     string `json:"content"`
}

// emails/<lang>.json hold the verification-code e-mails per language (same
// language codes as the apps; see docs/i18n.md).
//
//go:embed emails/*.json
var emailFiles embed.FS

type emailTexts struct {
	Footer    string `json:"footer"`
	Signature string `json:"signature"`
	Templates map[string]struct {
		Subject string `json:"subject"`
		Intro   string `json:"intro"`
	} `json:"templates"`
}

// templateTypes are Logto's verification-code template types. The connector
// requires Register, SignIn, ForgotPassword and Generic.
var templateTypes = []string{
	"SignIn", "Register", "ForgotPassword", "Generic", "BindNewIdentifier", "UserPermissionValidation",
	"MfaVerification", "BindMfa",
}

func loadEmailTexts() (map[string]emailTexts, error) {
	out := map[string]emailTexts{}
	names, err := fs.Glob(emailFiles, "emails/*.json")
	if err != nil {
		return nil, err
	}
	for _, n := range names {
		b, err := emailFiles.ReadFile(n)
		if err != nil {
			return nil, err
		}
		var t emailTexts
		if err := json.Unmarshal(b, &t); err != nil {
			return nil, fmt.Errorf("%s: %w", n, err)
		}
		out[strings.TrimSuffix(path.Base(n), ".json")] = t
	}
	return out, nil
}

func mustEmailTexts() map[string]emailTexts {
	t, err := loadEmailTexts()
	if err != nil {
		panic(err) // embedded at build time; covered by tests
	}
	return t
}

// render builds one plain-text e-mail. Logto substitutes {{code}}; codes
// expire after 10 minutes (Logto's default).
func (t emailTexts) render(usage string) (subject, content string) {
	tpl := t.Templates[usage]
	return tpl.Subject, tpl.Intro + "\n\n    {{code}}\n\n" + t.Footer + "\n\n" + t.Signature
}

// emailTemplates are the connector's default (English) templates, used when
// Logto finds no template for the user's language.
func emailTemplates() []emailTemplate {
	en := mustEmailTexts()["en"]
	out := make([]emailTemplate, 0, len(templateTypes))
	for _, u := range templateTypes {
		subject, content := en.render(u)
		out = append(out, emailTemplate{UsageType: u, ContentType: "text/plain", Subject: subject, Content: content})
	}
	return out
}

// logtoLanguageTags maps our language codes to the tags Logto may resolve for
// them (its built-in language library uses some regional tags).
func logtoLanguageTags(lang string) []string {
	switch lang {
	case "pt":
		return []string{"pt", "pt-BR", "pt-PT"}
	case "pl":
		return []string{"pl", "pl-PL"}
	case "zh-TW":
		return []string{"zh-TW", "zh-HK"}
	case "uk":
		return []string{"uk", "uk-UA"}
	case "nb":
		return []string{"nb", "no"}
	}
	return []string{lang}
}

// localizedEmailTemplate is one entry of Logto's email template store
// (Management API /api/email-templates, Logto 1.27+).
type localizedEmailTemplate struct {
	LanguageTag  string               `json:"languageTag"`
	TemplateType string               `json:"templateType"`
	Details      localizedEmailDetail `json:"details"`
}

type localizedEmailDetail struct {
	Subject     string `json:"subject"`
	Content     string `json:"content"`
	ContentType string `json:"contentType"`
}

// localizedEmailTemplates are the per-language templates for every language
// except English (which the connector's defaults cover), sorted for stable
// comparisons.
func localizedEmailTemplates() []localizedEmailTemplate {
	var out []localizedEmailTemplate
	for lang, t := range mustEmailTexts() {
		if lang == "en" {
			continue
		}
		for _, tag := range logtoLanguageTags(lang) {
			for _, u := range templateTypes {
				subject, content := t.render(u)
				out = append(out, localizedEmailTemplate{
					LanguageTag: tag, TemplateType: u,
					Details: localizedEmailDetail{Subject: subject, Content: content, ContentType: "text/plain"},
				})
			}
		}
	}
	slices.SortFunc(out, func(a, b localizedEmailTemplate) int {
		return strings.Compare(a.LanguageTag+"/"+a.TemplateType, b.LanguageTag+"/"+b.TemplateType)
	})
	return out
}

// ensureEmailTemplates stores the translated e-mails in Logto's template
// store, so a sign-in in German gets a German code e-mail. Logto picks the
// language from ui_locales (the web app sends its language) or the sign-in
// page's language, and falls back to the connector's English templates.
// Idempotent: only templates that differ are written. Logto versions without
// the template store keep sending English.
func (b *Bootstrapper) ensureEmailTemplates(ctx context.Context) error {
	var have []localizedEmailTemplate
	if err := b.m.do(ctx, http.MethodGet, "/email-templates", nil, &have); err != nil {
		if isStatus(err, http.StatusNotFound) {
			b.log.Warn("this Logto version has no email template store; verification emails stay in English")
			return nil
		}
		return err
	}
	type key struct{ tag, typ string }
	existing := make(map[key]localizedEmailDetail, len(have))
	for _, t := range have {
		existing[key{t.LanguageTag, t.TemplateType}] = t.Details
	}
	var put []localizedEmailTemplate
	for _, t := range localizedEmailTemplates() {
		if d, ok := existing[key{t.LanguageTag, t.TemplateType}]; !ok || d != t.Details {
			put = append(put, t)
		}
	}
	if len(put) == 0 {
		return nil
	}
	if err := b.m.do(ctx, http.MethodPut, "/email-templates", map[string]any{"templates": put}, nil); err != nil {
		return err
	}
	b.changed("stored localized verification emails", "templates", len(put))
	return nil
}

var errNoEmailTexts = errors.New("no email texts")

// validateEmailTexts checks every language has every template with {{code}}
// in the subject (used by tests).
func validateEmailTexts(all map[string]emailTexts) error {
	if _, ok := all["en"]; !ok {
		return errNoEmailTexts
	}
	for lang, t := range all {
		if t.Footer == "" || t.Signature == "" {
			return fmt.Errorf("%s: footer or signature missing", lang)
		}
		for _, u := range templateTypes {
			tpl, ok := t.Templates[u]
			if !ok || tpl.Subject == "" || tpl.Intro == "" {
				return fmt.Errorf("%s: template %s missing", lang, u)
			}
			if !strings.Contains(tpl.Subject, "{{code}}") || strings.Contains(tpl.Intro, "{{code}}") {
				return fmt.Errorf("%s: template %s: {{code}} belongs in the subject (and is added after the intro)", lang, u)
			}
		}
		for u := range t.Templates {
			if !slices.Contains(templateTypes, u) {
				return fmt.Errorf("%s: unknown template type %s", lang, u)
			}
		}
	}
	return nil
}
