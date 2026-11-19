package config

import (
	"fmt"
	"net/url"
	"strings"
	"time"
)

// Families on different servers (docs/spec/multi-server.md, API README §5.22).
//
// KINFRAME_FEDERATION is on (default) or off; KINFRAME_FEDERATION_ALLOW, when set, limits federation to those
// hosts (comma-separated); KINFRAME_SERVER_LABEL is discovery `server_name` (default the public host name).
type FederationConfig struct {
	Enabled bool
	// Allow lists the only hosts this server federates with (lower case, host[:port]); empty: any host.
	Allow []string
	// Label is discovery `server_name`.
	Label string

	// Tests only (no environment variables): reach 127.0.0.1 over plain http and use short intervals.
	AllowLoopback bool
	PingDelay     time.Duration // debounce of outgoing pings (default 10 s)
	PollInterval  time.Duration // pull every follow at least this often (default 15 min)
	Tick          time.Duration // the background loop's step (default 2 s)
}

func loadFederation(c *Config, env func(k, def string) string) error {
	switch v := strings.ToLower(env("KINFRAME_FEDERATION", "on")); v {
	case "1", "on", "true", "yes":
		c.Federation.Enabled = true
	case "0", "off", "false", "no":
		c.Federation.Enabled = false
	default:
		return fmt.Errorf("KINFRAME_FEDERATION must be on or off, got %q", v)
	}
	for _, h := range strings.Split(env("KINFRAME_FEDERATION_ALLOW", ""), ",") {
		h = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(h)), ".")
		if h == "" {
			continue
		}
		if strings.Contains(h, "://") {
			u, err := url.Parse(h)
			if err != nil || u.Host == "" {
				return fmt.Errorf("KINFRAME_FEDERATION_ALLOW: %q is not a host name", h)
			}
			h = u.Host
		}
		c.Federation.Allow = append(c.Federation.Allow, h)
	}
	c.Federation.Label = env("KINFRAME_SERVER_LABEL", "")
	return nil
}

// FederationLabel is discovery `server_name`: KINFRAME_SERVER_LABEL, else the host of the public URL.
func (c *Config) FederationLabel() string {
	if c.Federation.Label != "" {
		return c.Federation.Label
	}
	if u, err := url.Parse(c.PublicURL); err == nil && u.Hostname() != "" {
		return u.Hostname()
	}
	return c.ServerName
}

// FederationHost is this server's host as other servers see it (host[:port] of the public URL, lower case).
func (c *Config) FederationHost() string {
	if u, err := url.Parse(c.PublicURL); err == nil && u.Host != "" {
		return strings.ToLower(u.Host)
	}
	return ""
}
