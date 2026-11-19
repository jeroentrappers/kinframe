package config

import (
	"fmt"
	"strings"
)

// Chats (README §5.19). KINFRAME_LINK_PREVIEWS=1 lets the server fetch previews of links in chat messages
// (public addresses only); off by default.
func loadChat(c *Config, env func(k, def string) string) error {
	switch v := strings.ToLower(env("KINFRAME_LINK_PREVIEWS", "off")); v {
	case "1", "on", "true", "yes":
		c.LinkPreviews = true
	case "0", "off", "false", "no":
		c.LinkPreviews = false
	default:
		return fmt.Errorf("KINFRAME_LINK_PREVIEWS must be on or off, got %q", v)
	}
	return nil
}
