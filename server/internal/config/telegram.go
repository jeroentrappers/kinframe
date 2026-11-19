package config

import (
	"errors"
	"fmt"
	"net/url"
	"strings"
)

// Telegram bridge settings (docs/spec/telegram.md §14, server/README.md).
func loadTelegram(c *Config, get func(string) string, env func(k, def string) string, intv func(k string, def int) int) error {
	var errs []error
	tok, err := secretValue(get, "KINFRAME_TELEGRAM_BOT_TOKEN")
	if err != nil {
		errs = append(errs, err)
	}
	c.TelegramBotToken = tok
	c.TelegramMode = strings.ToLower(env("KINFRAME_TELEGRAM_MODE", "auto"))
	switch c.TelegramMode {
	case "auto", "webhook", "polling", "off":
	default:
		errs = append(errs, fmt.Errorf("KINFRAME_TELEGRAM_MODE must be auto, webhook, polling or off, got %q", c.TelegramMode))
	}
	if tok != "" && !strings.Contains(tok, ":") {
		errs = append(errs, errors.New("KINFRAME_TELEGRAM_BOT_TOKEN doesn't look like a bot token (<id>:<secret> from BotFather)"))
	}
	c.TelegramWebhookURL = strings.TrimRight(env("KINFRAME_TELEGRAM_WEBHOOK_URL", ""), "/")
	if c.TelegramWebhookURL != "" {
		if u, err := url.Parse(c.TelegramWebhookURL); err != nil || u.Scheme != "https" || u.Host == "" {
			errs = append(errs, errors.New("KINFRAME_TELEGRAM_WEBHOOK_URL must be an https:// URL"))
		}
	}
	c.TelegramAPIURL = strings.TrimRight(env("KINFRAME_TELEGRAM_API_URL", "https://api.telegram.org"), "/")
	if u, err := url.Parse(c.TelegramAPIURL); err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
		errs = append(errs, errors.New("KINFRAME_TELEGRAM_API_URL must be an http(s) URL"))
	}
	c.TelegramLocalFilesDir = env("KINFRAME_TELEGRAM_LOCAL_FILES_DIR", "")
	def := 20
	if c.TelegramAPIURL != "https://api.telegram.org" {
		def = 2000
	}
	mb := intv("KINFRAME_TELEGRAM_MAX_DOWNLOAD_MB", def)
	if mb < 1 || mb > 2000 {
		errs = append(errs, errors.New("KINFRAME_TELEGRAM_MAX_DOWNLOAD_MB must be between 1 and 2000"))
	}
	c.TelegramMaxDownload = int64(mb) << 20
	c.TelegramWorkers = intv("KINFRAME_TELEGRAM_WORKERS", 4)
	if c.TelegramWorkers < 1 {
		errs = append(errs, errors.New("KINFRAME_TELEGRAM_WORKERS must be at least 1"))
	}
	switch strings.ToLower(env("KINFRAME_TELEGRAM_WEBHOOK_IP_CHECK", "off")) {
	case "1", "on", "true", "yes":
		c.TelegramWebhookIPCheck = true
	}
	return errors.Join(errs...)
}

// TelegramEnabled reports whether the server runs a Telegram bot.
func (c *Config) TelegramEnabled() bool { return c.TelegramBotToken != "" && c.TelegramMode != "off" }
