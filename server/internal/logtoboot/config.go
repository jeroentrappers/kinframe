// Package logtoboot configures a fresh Logto OSS instance for kinframe through
// its Management API (`kinframe-server bootstrap-logto`). Every step is an
// "ensure": it compares the desired state with what Logto has and only writes
// differences, so re-running the command is a no-op.
package logtoboot

import (
	"errors"
	"fmt"
	"net/mail"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Config is read from the environment; every variable is documented in
// server/README.md.
type Config struct {
	PublicURL *url.URL // kinframe's external base URL (KINFRAME_PUBLIC_URL)

	// Endpoint is Logto's ENDPOINT as Logto itself is configured; URL is where
	// this process actually reaches it (e.g. http://logto:3001 inside compose).
	Endpoint *url.URL
	URL      *url.URL
	// AdminEndpoint is Logto's ADMIN_ENDPOINT; AdminURL is how to reach it.
	AdminEndpoint *url.URL
	AdminURL      *url.URL

	AdminUsername string
	AdminPassword string
	M2MID         string
	M2MSecret     string

	OperatorEmails []string

	SMTP *SMTPConfig // nil when KINFRAME_SMTP_HOST is unset

	OutputFile string
}

type SMTPConfig struct {
	Host     string
	Port     int
	User     string
	Password string
	From     string
}

// APIResource is the Logto API resource indicator (and token audience).
func (c *Config) APIResource() string { return strings.TrimRight(c.PublicURL.String(), "/") + "/v1" }

// Origin is the web app's origin (scheme://host[:port]).
func (c *Config) Origin() string { return c.PublicURL.Scheme + "://" + c.PublicURL.Host }

// LoadConfig reads the bootstrap configuration from the environment.
func LoadConfig() (*Config, error) { return loadConfig(os.Getenv) }

func loadConfig(get func(string) string) (*Config, error) {
	env := func(k string) string { return strings.TrimSpace(get(k)) }
	var errs []error
	parseURL := func(k, v string, required bool) *url.URL {
		if v == "" {
			if required {
				errs = append(errs, fmt.Errorf("%s is required", k))
			}
			return nil
		}
		u, err := url.Parse(strings.TrimRight(v, "/"))
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			errs = append(errs, fmt.Errorf("%s: want an absolute http(s) URL, got %q", k, v))
			return nil
		}
		if u.Path != "" || u.RawQuery != "" {
			errs = append(errs, fmt.Errorf("%s: must not have a path or query, got %q", k, v))
			return nil
		}
		return u
	}

	c := &Config{
		AdminUsername: env("KINFRAME_LOGTO_ADMIN_USERNAME"),
		AdminPassword: get("KINFRAME_LOGTO_ADMIN_PASSWORD"),
		M2MID:         env("KINFRAME_LOGTO_M2M_ID"),
		M2MSecret:     env("KINFRAME_LOGTO_M2M_SECRET"),
	}
	c.PublicURL = parseURL("KINFRAME_PUBLIC_URL", env("KINFRAME_PUBLIC_URL"), true)
	c.Endpoint = parseURL("KINFRAME_LOGTO_ENDPOINT", env("KINFRAME_LOGTO_ENDPOINT"), true)
	c.URL = parseURL("KINFRAME_LOGTO_URL", env("KINFRAME_LOGTO_URL"), false)
	if c.URL == nil {
		c.URL = c.Endpoint
	}
	c.AdminEndpoint = parseURL("KINFRAME_LOGTO_ADMIN_ENDPOINT", env("KINFRAME_LOGTO_ADMIN_ENDPOINT"), false)
	c.AdminURL = parseURL("KINFRAME_LOGTO_ADMIN_URL", env("KINFRAME_LOGTO_ADMIN_URL"), false)
	if c.AdminURL == nil {
		c.AdminURL = c.AdminEndpoint
	}

	hasM2M := c.M2MID != "" || c.M2MSecret != ""
	hasAdmin := c.AdminUsername != "" || c.AdminPassword != ""
	switch {
	case hasM2M && (c.M2MID == "" || c.M2MSecret == ""):
		errs = append(errs, errors.New("set both KINFRAME_LOGTO_M2M_ID and KINFRAME_LOGTO_M2M_SECRET"))
	case hasAdmin && (c.AdminUsername == "" || c.AdminPassword == ""):
		errs = append(errs, errors.New("set both KINFRAME_LOGTO_ADMIN_USERNAME and KINFRAME_LOGTO_ADMIN_PASSWORD"))
	case !hasM2M && !hasAdmin:
		errs = append(errs, errors.New("set KINFRAME_LOGTO_ADMIN_USERNAME/PASSWORD (first run) or KINFRAME_LOGTO_M2M_ID/SECRET"))
	}
	if !hasM2M && hasAdmin && c.AdminEndpoint == nil {
		errs = append(errs, errors.New("KINFRAME_LOGTO_ADMIN_ENDPOINT is required to sign in as the console admin"))
	}

	for _, e := range strings.FieldsFunc(env("KINFRAME_OPERATOR_EMAILS"), func(r rune) bool { return r == ',' || r == ' ' || r == ';' }) {
		a, err := mail.ParseAddress(e)
		if err != nil || a.Address != e {
			errs = append(errs, fmt.Errorf("KINFRAME_OPERATOR_EMAILS: invalid address %q", e))
			continue
		}
		c.OperatorEmails = append(c.OperatorEmails, strings.ToLower(e))
	}

	if host := env("KINFRAME_SMTP_HOST"); host != "" {
		s := &SMTPConfig{
			Host:     host,
			User:     env("KINFRAME_SMTP_USER"),
			Password: get("KINFRAME_SMTP_PASSWORD"),
			From:     env("KINFRAME_SMTP_FROM"),
			Port:     587,
		}
		if p := env("KINFRAME_SMTP_PORT"); p != "" {
			n, err := strconv.Atoi(p)
			if err != nil || n <= 0 || n > 65535 {
				errs = append(errs, fmt.Errorf("KINFRAME_SMTP_PORT: invalid port %q", p))
			}
			s.Port = n
		}
		if s.From == "" {
			errs = append(errs, errors.New("KINFRAME_SMTP_FROM is required with KINFRAME_SMTP_HOST"))
		}
		c.SMTP = s
	}

	c.OutputFile = env("KINFRAME_LOGTO_CONFIG_FILE")
	if c.OutputFile == "" {
		dir := env("KINFRAME_DATA_DIR")
		if dir == "" {
			dir = "./data"
		}
		c.OutputFile = filepath.Join(dir, "logto.json")
	}

	if len(errs) > 0 {
		return nil, errors.Join(errs...)
	}
	return c, nil
}
