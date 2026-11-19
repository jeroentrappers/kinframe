package config

import (
	"errors"
	"fmt"
	"net"
	"net/mail"
	"os"
	"strconv"
	"strings"
)

// EmailConfig is email to frame (docs/spec/email-inbox.md §12.1).
type EmailConfig struct {
	// Enabled: KINFRAME_EMAIL_INBOX=on, with a domain and at least one way in.
	Enabled bool
	Domain  string
	// SMTP receiver; empty Listen: none.
	SMTPListen    string
	SMTPHostname  string
	TLSCertFile   string
	TLSKeyFile    string
	RequireTLS    bool
	ProxyProtocol bool
	MaxSessions   int
	// Webhook; no secrets: the endpoint answers 404. Two values rotate (new, old).
	WebhookSecrets    []string
	TrustAuthservID   string
	DNSServer         string
	Replies           bool
	MaxRepliesPerHour int
	Postmaster        string
	HeldDays          int
}

// Email limit defaults (Limits).
const (
	DefaultMaxEmailAddressesPerTarget    = 5
	DefaultMaxEmailSendersPerAddress     = 200
	DefaultMaxEmailMessageBytes          = 50 << 20
	DefaultMaxEmailMessagesPerAddressDay = 200
	DefaultMaxEmailHeldMessages          = 100
)

func (l *Limits) emailDefaults() {
	if l.MaxEmailAddressesPerTarget <= 0 {
		l.MaxEmailAddressesPerTarget = DefaultMaxEmailAddressesPerTarget
	}
	if l.MaxEmailSendersPerAddress <= 0 {
		l.MaxEmailSendersPerAddress = DefaultMaxEmailSendersPerAddress
	}
	if l.MaxEmailMessageBytes <= 0 {
		l.MaxEmailMessageBytes = DefaultMaxEmailMessageBytes
	}
	if l.MaxEmailMessagesPerAddressDay <= 0 {
		l.MaxEmailMessagesPerAddressDay = DefaultMaxEmailMessagesPerAddressDay
	}
	if l.MaxEmailHeldMessages <= 0 {
		l.MaxEmailHeldMessages = DefaultMaxEmailHeldMessages
	}
}

// loadEmail reads the KINFRAME_EMAIL_* variables and the email limits.
func loadEmail(get func(string) string, c *Config) []error {
	env := func(k, def string) string {
		if v := strings.TrimSpace(get(k)); v != "" {
			return v
		}
		return def
	}
	var errs []error
	intv := func(k string, def int) int {
		v := env(k, "")
		if v == "" {
			return def
		}
		n, err := strconv.Atoi(v)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", k, err))
		}
		return n
	}
	on := func(k, def string) bool {
		switch strings.ToLower(env(k, def)) {
		case "1", "true", "yes", "on":
			return true
		}
		return false
	}
	l := &c.DefaultLimits
	l.MaxEmailAddressesPerTarget = intv("KINFRAME_MAX_EMAIL_ADDRESSES_PER_TARGET", DefaultMaxEmailAddressesPerTarget)
	l.MaxEmailSendersPerAddress = intv("KINFRAME_MAX_EMAIL_SENDERS_PER_ADDRESS", DefaultMaxEmailSendersPerAddress)
	l.MaxEmailMessageBytes = int64(intv("KINFRAME_MAX_EMAIL_MESSAGE_MB", DefaultMaxEmailMessageBytes>>20)) << 20
	l.MaxEmailMessagesPerAddressDay = intv("KINFRAME_MAX_EMAIL_MESSAGES_PER_ADDRESS_DAY", DefaultMaxEmailMessagesPerAddressDay)
	l.MaxEmailHeldMessages = intv("KINFRAME_MAX_EMAIL_HELD_MESSAGES", DefaultMaxEmailHeldMessages)

	e := &c.Email
	e.Enabled = on("KINFRAME_EMAIL_INBOX", "off")
	e.Domain = strings.ToLower(strings.TrimSuffix(env("KINFRAME_EMAIL_INBOX_DOMAIN", ""), "."))
	e.SMTPListen = env("KINFRAME_EMAIL_SMTP_LISTEN", "")
	e.SMTPHostname = env("KINFRAME_EMAIL_SMTP_HOSTNAME", e.Domain)
	e.TLSCertFile = env("KINFRAME_EMAIL_TLS_CERT_FILE", "")
	e.TLSKeyFile = env("KINFRAME_EMAIL_TLS_KEY_FILE", "")
	e.RequireTLS = on("KINFRAME_EMAIL_REQUIRE_TLS", "off")
	e.ProxyProtocol = on("KINFRAME_EMAIL_PROXY_PROTOCOL", "off")
	e.MaxSessions = intv("KINFRAME_EMAIL_SMTP_MAX_SESSIONS", 100)
	secret, err := secretValue(get, "KINFRAME_EMAIL_WEBHOOK_SECRET")
	if err != nil {
		errs = append(errs, err)
	}
	for _, s := range strings.Split(secret, ",") {
		if s = strings.TrimSpace(s); s != "" {
			e.WebhookSecrets = append(e.WebhookSecrets, s)
		}
	}
	e.TrustAuthservID = env("KINFRAME_EMAIL_WEBHOOK_TRUST_AUTHSERV_ID", "")
	e.DNSServer = env("KINFRAME_EMAIL_DNS_SERVER", "")
	e.Replies = on("KINFRAME_EMAIL_REPLIES", "on")
	e.MaxRepliesPerHour = intv("KINFRAME_EMAIL_MAX_REPLIES_PER_HOUR", 500)
	e.Postmaster = env("KINFRAME_EMAIL_POSTMASTER", "")
	e.HeldDays = intv("KINFRAME_EMAIL_HELD_DAYS", 14)

	if !e.Enabled {
		return errs
	}
	if e.Domain == "" || !strings.Contains(e.Domain, ".") {
		errs = append(errs, errors.New("KINFRAME_EMAIL_INBOX=on needs KINFRAME_EMAIL_INBOX_DOMAIN (e.g. in.kinframe.example.org)"))
	}
	if e.SMTPListen == "" && len(e.WebhookSecrets) == 0 {
		errs = append(errs, errors.New("KINFRAME_EMAIL_INBOX=on needs KINFRAME_EMAIL_SMTP_LISTEN or KINFRAME_EMAIL_WEBHOOK_SECRET"))
	}
	if e.SMTPListen != "" {
		if _, _, err := net.SplitHostPort(e.SMTPListen); err != nil {
			errs = append(errs, fmt.Errorf("KINFRAME_EMAIL_SMTP_LISTEN: %w", err))
		}
	}
	if (e.TLSCertFile == "") != (e.TLSKeyFile == "") {
		errs = append(errs, errors.New("set both KINFRAME_EMAIL_TLS_CERT_FILE and KINFRAME_EMAIL_TLS_KEY_FILE, or neither"))
	}
	for _, f := range []string{e.TLSCertFile, e.TLSKeyFile} {
		if f != "" {
			if _, err := os.Stat(f); err != nil {
				errs = append(errs, fmt.Errorf("KINFRAME_EMAIL_TLS_*: %w", err))
			}
		}
	}
	if e.RequireTLS && e.TLSCertFile == "" {
		errs = append(errs, errors.New("KINFRAME_EMAIL_REQUIRE_TLS needs KINFRAME_EMAIL_TLS_CERT_FILE and _KEY_FILE"))
	}
	if e.MaxSessions < 1 {
		errs = append(errs, errors.New("KINFRAME_EMAIL_SMTP_MAX_SESSIONS must be at least 1"))
	}
	if e.HeldDays < 1 || e.HeldDays > 14 {
		errs = append(errs, errors.New("KINFRAME_EMAIL_HELD_DAYS must be between 1 and 14"))
	}
	if e.Postmaster != "" {
		if a, err := mail.ParseAddress(e.Postmaster); err != nil || a.Address == "" {
			errs = append(errs, errors.New("KINFRAME_EMAIL_POSTMASTER must be an email address"))
		} else if strings.HasSuffix(strings.ToLower(a.Address), "@"+e.Domain) {
			errs = append(errs, errors.New("KINFRAME_EMAIL_POSTMASTER must not be on the inbox domain"))
		}
	}
	if c.SMTP != nil && e.Domain != "" {
		if a, err := mail.ParseAddress(c.SMTP.From); err == nil && strings.HasSuffix(strings.ToLower(a.Address), "@"+e.Domain) {
			errs = append(errs, errors.New("KINFRAME_SMTP_FROM must not be on the inbox domain (replies come from the server's own sender)"))
		}
	}
	return errs
}
