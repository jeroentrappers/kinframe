package telegram

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// CloudAPI is Telegram's own Bot API.
const CloudAPI = "https://api.telegram.org"

// CloudMaxDownload is the cloud Bot API's download limit (§9.3).
const CloudMaxDownload = 20 << 20

// Client calls the Bot API. The token is part of every URL: errors are
// redacted and URLs are never logged (§13.1).
type Client struct {
	BaseURL string // default CloudAPI
	Token   string
	// CloudURL is Telegram's cloud Bot API (default CloudAPI); BaseURL differs
	// from it for a local Bot API server.
	CloudURL string
	// LocalFilesDir is where a local Bot API server's --local directory is
	// mounted (getFile then returns absolute paths on that server).
	LocalFilesDir string
	HTTP          *http.Client // default: connect timeout 10 s, no overall timeout (per call contexts)
}

func (c *Client) base() string {
	if c.BaseURL == "" {
		return CloudAPI
	}
	return strings.TrimRight(c.BaseURL, "/")
}

// Local reports whether the client talks to a local Bot API server.
func (c *Client) Local() bool { return c.base() != c.cloud() }

func (c *Client) cloud() string {
	if c.CloudURL == "" {
		return CloudAPI
	}
	return strings.TrimRight(c.CloudURL, "/")
}

// Cloud returns a client for the cloud Bot API with the same token.
func (c *Client) Cloud() *Client { return &Client{BaseURL: c.cloud(), Token: c.Token, HTTP: c.HTTP} }

var defaultHTTP = &http.Client{Transport: &http.Transport{
	Proxy:                 http.ProxyFromEnvironment,
	DialContext:           (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
	TLSHandshakeTimeout:   10 * time.Second,
	ResponseHeaderTimeout: 90 * time.Second,
	MaxIdleConnsPerHost:   8,
	IdleConnTimeout:       90 * time.Second,
}}

func (c *Client) http() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return defaultHTTP
}

// Error is a Bot API error answer.
type Error struct {
	Code        int    // HTTP-like error_code (400, 403, 429…); 0 for transport errors
	Description string // Telegram's description, e.g. "Bad Request: chat not found"
	RetryAfter  time.Duration
	// MigrateTo is set when a basic group became a supergroup (§12).
	MigrateTo int64
	transport bool
}

func (e *Error) Error() string {
	if e.transport {
		return "telegram: " + e.Description
	}
	return fmt.Sprintf("telegram: %d %s", e.Code, e.Description)
}

// Temporary reports a failure worth retrying (network, 5xx, 429).
func (e *Error) Temporary() bool { return e.transport || e.Code >= 500 || e.Code == 429 }

// Has reports whether the description contains s (case-insensitive).
func (e *Error) Has(s string) bool {
	return strings.Contains(strings.ToLower(e.Description), strings.ToLower(s))
}

// ChatGone reports an answer meaning the bot can no longer reach the chat.
func (e *Error) ChatGone() bool {
	return e.Code == 400 && e.Has("chat not found") || e.Code == 403 && (e.Has("kicked") || e.Has("not a member") || e.Has("chat was deleted"))
}

// Blocked reports a private chat whose user blocked the bot.
func (e *Error) Blocked() bool { return e.Code == 403 && e.Has("blocked by the user") }

// AsError returns err as *Error (nil when it isn't one).
func AsError(err error) *Error {
	var e *Error
	if errors.As(err, &e) {
		return e
	}
	return nil
}

func (c *Client) redact(err error) error {
	if err == nil || c.Token == "" {
		return err
	}
	msg := strings.ReplaceAll(err.Error(), c.Token, "<token>")
	return &Error{Description: msg, transport: true}
}

type envelope struct {
	OK          bool            `json:"ok"`
	Result      json.RawMessage `json:"result"`
	ErrorCode   int             `json:"error_code"`
	Description string          `json:"description"`
	Parameters  *struct {
		RetryAfter      int   `json:"retry_after"`
		MigrateToChatID int64 `json:"migrate_to_chat_id"`
	} `json:"parameters"`
}

// Call invokes method with params (JSON) and decodes the result into out.
func (c *Client) Call(ctx context.Context, method string, params any, out any) error {
	var body io.Reader = http.NoBody
	if params != nil {
		b, err := json.Marshal(params)
		if err != nil {
			return err
		}
		body = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base()+"/bot"+c.Token+"/"+method, body)
	if err != nil {
		return c.redact(err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http().Do(req)
	if err != nil {
		return c.redact(err)
	}
	defer resp.Body.Close()
	var env envelope
	if err := json.NewDecoder(io.LimitReader(resp.Body, 8<<20)).Decode(&env); err != nil {
		return &Error{Code: resp.StatusCode, Description: "unreadable answer to " + method, transport: resp.StatusCode >= 500}
	}
	if !env.OK {
		e := &Error{Code: env.ErrorCode, Description: env.Description}
		if e.Code == 0 {
			e.Code = resp.StatusCode
		}
		if env.Parameters != nil {
			e.RetryAfter = time.Duration(env.Parameters.RetryAfter) * time.Second
			e.MigrateTo = env.Parameters.MigrateToChatID
		}
		return e
	}
	if out != nil {
		if err := json.Unmarshal(env.Result, out); err != nil {
			return fmt.Errorf("telegram: decode %s: %w", method, err)
		}
	}
	return nil
}

// GetMe returns the bot.
func (c *Client) GetMe(ctx context.Context) (*Me, error) {
	var m Me
	return &m, c.Call(ctx, "getMe", nil, &m)
}

// GetWebhookInfo returns the current webhook registration.
func (c *Client) GetWebhookInfo(ctx context.Context) (*WebhookInfo, error) {
	var w WebhookInfo
	return &w, c.Call(ctx, "getWebhookInfo", nil, &w)
}

// SetWebhook registers url with the secret token (§7).
func (c *Client) SetWebhook(ctx context.Context, url, secret string) error {
	return c.Call(ctx, "setWebhook", map[string]any{
		"url": url, "secret_token": secret, "allowed_updates": AllowedUpdates, "max_connections": 10, "drop_pending_updates": false,
	}, nil)
}

// DeleteWebhook removes the webhook (before long polling).
func (c *Client) DeleteWebhook(ctx context.Context) error {
	return c.Call(ctx, "deleteWebhook", map[string]any{"drop_pending_updates": false}, nil)
}

// LogOut logs the bot out of the cloud Bot API (before using a local server).
func (c *Client) LogOut(ctx context.Context) error { return c.Call(ctx, "logOut", nil, nil) }

// GetUpdates long-polls for updates after offset.
func (c *Client) GetUpdates(ctx context.Context, offset int64, timeout int) ([]json.RawMessage, error) {
	var out []json.RawMessage
	err := c.Call(ctx, "getUpdates", map[string]any{"offset": offset, "timeout": timeout, "allowed_updates": AllowedUpdates}, &out)
	return out, err
}

// GetFile resolves a file id to a download path.
func (c *Client) GetFile(ctx context.Context, fileID string) (*File, error) {
	var f File
	return &f, c.Call(ctx, "getFile", map[string]any{"file_id": fileID}, &f)
}

// SendOptions are the optional parts of a message.
type SendOptions struct {
	ReplyTo  int64 // reply to this message (in the same chat)
	ThreadID int64 // forum topic
	Silent   bool  // disable_notification
	Buttons  [][]InlineButton
}

// SendMessage sends a plain-text message and returns its id.
func (c *Client) SendMessage(ctx context.Context, chatID int64, text string, o SendOptions) (int64, error) {
	p := map[string]any{"chat_id": chatID, "text": text, "link_preview_options": map[string]any{"is_disabled": true}}
	if o.Silent {
		p["disable_notification"] = true
	}
	if o.ThreadID != 0 {
		p["message_thread_id"] = o.ThreadID
	}
	if o.ReplyTo != 0 {
		p["reply_parameters"] = map[string]any{"message_id": o.ReplyTo, "allow_sending_without_reply": true}
	}
	if len(o.Buttons) > 0 {
		p["reply_markup"] = map[string]any{"inline_keyboard": o.Buttons}
	}
	var m struct {
		MessageID int64 `json:"message_id"`
	}
	err := c.Call(ctx, "sendMessage", p, &m)
	return m.MessageID, err
}

// SetMessageReaction sets the bot's one reaction on a message ("" clears it).
func (c *Client) SetMessageReaction(ctx context.Context, chatID, messageID int64, emoji string) error {
	reaction := []map[string]string{}
	if emoji != "" {
		reaction = append(reaction, map[string]string{"type": "emoji", "emoji": emoji})
	}
	return c.Call(ctx, "setMessageReaction", map[string]any{"chat_id": chatID, "message_id": messageID, "reaction": reaction}, nil)
}

// LeaveChat makes the bot leave a group.
func (c *Client) LeaveChat(ctx context.Context, chatID int64) error {
	return c.Call(ctx, "leaveChat", map[string]any{"chat_id": chatID}, nil)
}

// SetMyCommands registers the command menu for a scope ("all_private_chats",
// "all_group_chats") and language ("" for the default).
func (c *Client) SetMyCommands(ctx context.Context, cmds []BotCommand, scope, lang string) error {
	p := map[string]any{"commands": cmds, "scope": map[string]string{"type": scope}}
	if lang != "" {
		p["language_code"] = lang
	}
	return c.Call(ctx, "setMyCommands", p, nil)
}

// Downloaded is a file saved to disk.
type Downloaded struct {
	Path   string
	Bytes  int64
	SHA256 string
}

// ErrTooLarge means the file exceeded the cap given to Download.
var ErrTooLarge = errors.New("telegram: file too large")

// Download fetches a file found with GetFile into dst, at most max bytes,
// within 10 minutes. With a local Bot API server's absolute paths the file
// is read from LocalFilesDir and deleted there.
func (c *Client) Download(ctx context.Context, f *File, dst string, max int64) (*Downloaded, error) {
	if f.FilePath == "" {
		return nil, &Error{Code: 400, Description: "file has no path"}
	}
	ctx, cancel := context.WithTimeout(ctx, 10*time.Minute)
	defer cancel()
	var src io.ReadCloser
	local := ""
	if filepath.IsAbs(f.FilePath) {
		p, err := c.localPath(f.FilePath)
		if err != nil {
			return nil, err
		}
		fh, err := os.Open(p)
		if err != nil {
			return nil, &Error{Description: "local file unreadable", transport: true}
		}
		src, local = fh, p
	} else {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base()+"/file/bot"+c.Token+"/"+f.FilePath, nil)
		if err != nil {
			return nil, c.redact(err)
		}
		resp, err := c.http().Do(req)
		if err != nil {
			return nil, c.redact(err)
		}
		if resp.StatusCode != http.StatusOK {
			resp.Body.Close()
			return nil, &Error{Code: resp.StatusCode, Description: "file download failed: " + strconv.Itoa(resp.StatusCode)}
		}
		if resp.ContentLength > max {
			resp.Body.Close()
			return nil, ErrTooLarge
		}
		src = resp.Body
	}
	defer src.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o640)
	if err != nil {
		return nil, err
	}
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(out, h), io.LimitReader(src, max+1))
	if cerr := out.Close(); err == nil {
		err = cerr
	}
	if err == nil && n > max {
		err = ErrTooLarge
	}
	if err != nil {
		_ = os.Remove(dst)
		if errors.Is(err, ErrTooLarge) {
			return nil, err
		}
		return nil, c.redact(err)
	}
	if local != "" {
		_ = os.Remove(local)
	}
	return &Downloaded{Path: dst, Bytes: n, SHA256: hex.EncodeToString(h.Sum(nil))}, nil
}

// localPath maps an absolute path on the local Bot API server to the shared
// volume: the server keeps files under <dir>/<token>/<kind>/<file>.
func (c *Client) localPath(p string) (string, error) {
	if c.LocalFilesDir == "" {
		return "", &Error{Code: 400, Description: "local Bot API file path but KINFRAME_TELEGRAM_LOCAL_FILES_DIR is not set"}
	}
	clean := filepath.Clean(p)
	root := filepath.Clean(c.LocalFilesDir)
	if strings.HasPrefix(clean, root+string(filepath.Separator)) {
		return clean, nil
	}
	sep := string(filepath.Separator)
	if i := strings.Index(clean, sep+c.Token+sep); i >= 0 {
		rel := clean[i+1:]
		if strings.Contains(rel, "..") {
			return "", &Error{Code: 400, Description: "bad local file path"}
		}
		return filepath.Join(root, rel), nil
	}
	return "", &Error{Code: 400, Description: "local file outside KINFRAME_TELEGRAM_LOCAL_FILES_DIR"}
}
