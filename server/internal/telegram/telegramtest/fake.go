// Package telegramtest is an httptest fake of Telegram's Bot API for the
// methods the server uses: getMe, getWebhookInfo, setWebhook, deleteWebhook,
// logOut, getUpdates (long polling), getFile, file downloads,
// setMessageReaction, sendMessage, leaveChat and setMyCommands.
package telegramtest

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"time"
)

// Token is the bot token the fake accepts.
const Token = "123456:TEST-token_abc"

// Call is one recorded API call.
type Call struct {
	Method string
	Params map[string]any
}

// Sent is a message the bot sent.
type Sent struct {
	ChatID   int64
	Text     string
	ReplyTo  int64
	ThreadID int64
	Silent   bool
	Buttons  []string // button URLs
	ButtonTx []string // button texts
}

// Reaction is a reaction the bot set.
type Reaction struct {
	ChatID, MessageID int64
	Emoji             string
}

// Bot is the fake.
type Bot struct {
	*httptest.Server

	mu sync.Mutex
	// Configuration (set before or during a test, under Lock).
	BotID           int64
	Username        string
	ReadAll         bool              // can_read_all_group_messages (privacy mode off)
	Files           map[string][]byte // file_id -> content
	FileSizes       map[string]int64  // file_id -> reported size (default: len(content))
	FailFile        map[string]int    // file_id -> HTTP status for the next downloads
	ReactionInvalid map[int64]bool    // chat_id -> setMessageReaction answers REACTION_INVALID
	CannotPost      map[int64]bool    // chat_id -> sendMessage answers 403
	Migrated        map[int64]int64   // chat_id -> supergroup id (400 with migrate_to_chat_id)
	RateLimitNext   int               // the next N calls answer 429 retry_after 1

	// State.
	Calls     []Call
	Messages  []Sent
	Reactions []Reaction
	Left      []int64
	Webhook   struct {
		URL, Secret string
		Allowed     []string
	}
	updates   []json.RawMessage
	nextMsgID int64
	cond      *sync.Cond
}

// New starts a fake bot.
func New() *Bot {
	b := &Bot{BotID: 777000111, Username: "kinframe_test_bot", ReadAll: true, Files: map[string][]byte{}, FileSizes: map[string]int64{},
		FailFile: map[string]int{}, ReactionInvalid: map[int64]bool{}, CannotPost: map[int64]bool{}, Migrated: map[int64]int64{}, nextMsgID: 9000}
	b.cond = sync.NewCond(&b.mu)
	b.Server = httptest.NewServer(http.HandlerFunc(b.serve))
	return b
}

// Lock runs fn with the fake locked (to change its configuration).
func (b *Bot) Lock(fn func()) {
	b.mu.Lock()
	defer b.mu.Unlock()
	fn()
}

// Push queues an update for getUpdates.
func (b *Bot) Push(update any) {
	raw, _ := json.Marshal(update)
	b.mu.Lock()
	b.updates = append(b.updates, raw)
	b.cond.Broadcast()
	b.mu.Unlock()
}

// Pending is the number of updates not yet fetched by getUpdates.
func (b *Bot) Pending() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.updates)
}

// CallsOf returns the recorded calls of one method.
func (b *Bot) CallsOf(method string) []Call {
	b.mu.Lock()
	defer b.mu.Unlock()
	var out []Call
	for _, c := range b.Calls {
		if c.Method == method {
			out = append(out, c)
		}
	}
	return out
}

// SentTo returns the messages sent to a chat.
func (b *Bot) SentTo(chat int64) []Sent {
	b.mu.Lock()
	defer b.mu.Unlock()
	var out []Sent
	for _, m := range b.Messages {
		if m.ChatID == chat {
			out = append(out, m)
		}
	}
	return out
}

// ReactionOn returns the latest reaction the bot set on a message ("" if none).
func (b *Bot) ReactionOn(chat, msg int64) string {
	b.mu.Lock()
	defer b.mu.Unlock()
	r := ""
	for _, x := range b.Reactions {
		if x.ChatID == chat && x.MessageID == msg {
			r = x.Emoji
		}
	}
	return r
}

func (b *Bot) serve(w http.ResponseWriter, r *http.Request) {
	if strings.HasPrefix(r.URL.Path, "/file/bot"+Token+"/") {
		b.serveFile(w, strings.TrimPrefix(r.URL.Path, "/file/bot"+Token+"/"))
		return
	}
	prefix := "/bot" + Token + "/"
	if !strings.HasPrefix(r.URL.Path, prefix) {
		writeErr(w, 401, "Unauthorized", nil)
		return
	}
	method := strings.TrimPrefix(r.URL.Path, prefix)
	params := map[string]any{}
	body, _ := io.ReadAll(r.Body)
	if len(body) > 0 {
		_ = json.Unmarshal(body, &params)
	}
	b.mu.Lock()
	b.Calls = append(b.Calls, Call{Method: method, Params: params})
	if b.RateLimitNext > 0 && method != "getUpdates" {
		b.RateLimitNext--
		b.mu.Unlock()
		writeErr(w, 429, "Too Many Requests: retry after 1", map[string]any{"retry_after": 1})
		return
	}
	b.mu.Unlock()
	chat := int64Of(params["chat_id"])
	switch method {
	case "getMe":
		b.mu.Lock()
		res := map[string]any{"id": b.BotID, "is_bot": true, "first_name": "Kinframe", "username": b.Username,
			"can_join_groups": true, "can_read_all_group_messages": b.ReadAll}
		b.mu.Unlock()
		writeOK(w, res)
	case "getWebhookInfo":
		b.mu.Lock()
		res := map[string]any{"url": b.Webhook.URL, "pending_update_count": len(b.updates), "allowed_updates": b.Webhook.Allowed}
		b.mu.Unlock()
		writeOK(w, res)
	case "setWebhook":
		b.mu.Lock()
		b.Webhook.URL, _ = params["url"].(string)
		b.Webhook.Secret, _ = params["secret_token"].(string)
		b.Webhook.Allowed = nil
		if a, ok := params["allowed_updates"].([]any); ok {
			for _, x := range a {
				s, _ := x.(string)
				b.Webhook.Allowed = append(b.Webhook.Allowed, s)
			}
		}
		b.mu.Unlock()
		writeOK(w, true)
	case "deleteWebhook":
		b.mu.Lock()
		b.Webhook.URL, b.Webhook.Secret, b.Webhook.Allowed = "", "", nil
		b.mu.Unlock()
		writeOK(w, true)
	case "logOut", "setMyCommands":
		writeOK(w, true)
	case "getUpdates":
		b.getUpdates(w, r, params)
	case "getFile":
		id, _ := params["file_id"].(string)
		b.mu.Lock()
		data, ok := b.Files[id]
		size, sized := b.FileSizes[id]
		b.mu.Unlock()
		if !ok {
			writeErr(w, 400, "Bad Request: invalid file_id", nil)
			return
		}
		if !sized {
			size = int64(len(data))
		}
		if size > 20<<20 {
			writeErr(w, 400, "Bad Request: file is too big", nil)
			return
		}
		writeOK(w, map[string]any{"file_id": id, "file_unique_id": "u" + id, "file_size": size, "file_path": "files/" + id})
	case "setMessageReaction":
		b.mu.Lock()
		invalid := b.ReactionInvalid[chat]
		b.mu.Unlock()
		if invalid {
			writeErr(w, 400, "Bad Request: REACTION_INVALID", nil)
			return
		}
		emoji := ""
		if rs, ok := params["reaction"].([]any); ok && len(rs) > 0 {
			if m, ok := rs[0].(map[string]any); ok {
				emoji, _ = m["emoji"].(string)
			}
		}
		b.mu.Lock()
		b.Reactions = append(b.Reactions, Reaction{ChatID: chat, MessageID: int64Of(params["message_id"]), Emoji: emoji})
		b.mu.Unlock()
		writeOK(w, true)
	case "sendMessage":
		b.mu.Lock()
		if to, ok := b.Migrated[chat]; ok {
			b.mu.Unlock()
			writeErr(w, 400, "Bad Request: group chat was upgraded to a supergroup chat", map[string]any{"migrate_to_chat_id": to})
			return
		}
		if b.CannotPost[chat] {
			b.mu.Unlock()
			writeErr(w, 403, "Forbidden: bot is not a member of the group chat or not enough rights to send text messages", nil)
			return
		}
		m := Sent{ChatID: chat, ThreadID: int64Of(params["message_thread_id"])}
		m.Text, _ = params["text"].(string)
		m.Silent, _ = params["disable_notification"].(bool)
		if rp, ok := params["reply_parameters"].(map[string]any); ok {
			m.ReplyTo = int64Of(rp["message_id"])
		}
		if rm, ok := params["reply_markup"].(map[string]any); ok {
			rows, _ := rm["inline_keyboard"].([]any)
			for _, row := range rows {
				btns, _ := row.([]any)
				for _, bt := range btns {
					if bm, ok := bt.(map[string]any); ok {
						u, _ := bm["url"].(string)
						t, _ := bm["text"].(string)
						m.Buttons = append(m.Buttons, u)
						m.ButtonTx = append(m.ButtonTx, t)
					}
				}
			}
		}
		b.Messages = append(b.Messages, m)
		b.nextMsgID++
		id := b.nextMsgID
		b.mu.Unlock()
		writeOK(w, map[string]any{"message_id": id, "date": time.Now().Unix(), "chat": map[string]any{"id": chat}})
	case "leaveChat":
		b.mu.Lock()
		b.Left = append(b.Left, chat)
		b.mu.Unlock()
		writeOK(w, true)
	default:
		writeErr(w, 404, "Not Found: method not found", nil)
	}
}

func (b *Bot) getUpdates(w http.ResponseWriter, r *http.Request, params map[string]any) {
	offset := int64Of(params["offset"])
	timeout := time.Duration(int64Of(params["timeout"])) * time.Second
	deadline := time.Now().Add(timeout)
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.Webhook.URL != "" {
		writeErr(w, 409, "Conflict: can't use getUpdates method while webhook is active; use deleteWebhook to delete the webhook first", nil)
		return
	}
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		select {
		case <-stop:
		case <-r.Context().Done():
		case <-time.After(timeout):
		}
		b.mu.Lock()
		b.cond.Broadcast()
		b.mu.Unlock()
	}()
	for {
		// Updates below the offset are confirmed: drop them.
		var keep []json.RawMessage
		var out []json.RawMessage
		for _, u := range b.updates {
			var head struct {
				UpdateID int64 `json:"update_id"`
			}
			_ = json.Unmarshal(u, &head)
			if head.UpdateID < offset {
				continue
			}
			keep = append(keep, u)
			out = append(out, u)
		}
		b.updates = keep
		if len(out) > 0 || time.Now().After(deadline) || r.Context().Err() != nil {
			if out == nil {
				out = []json.RawMessage{}
			}
			writeOK(w, out)
			return
		}
		b.cond.Wait()
	}
}

func (b *Bot) serveFile(w http.ResponseWriter, path string) {
	id := strings.TrimPrefix(path, "files/")
	b.mu.Lock()
	data, ok := b.Files[id]
	status := b.FailFile[id]
	if status != 0 {
		delete(b.FailFile, id)
	}
	b.mu.Unlock()
	if status != 0 {
		http.Error(w, "fail", status)
		return
	}
	if !ok {
		http.NotFound(w, nil)
		return
	}
	w.Header().Set("Content-Length", fmt.Sprint(len(data)))
	_, _ = w.Write(data)
}

func writeOK(w http.ResponseWriter, result any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "result": result})
}

func writeErr(w http.ResponseWriter, code int, desc string, params map[string]any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	body := map[string]any{"ok": false, "error_code": code, "description": desc}
	if params != nil {
		body["parameters"] = params
	}
	_ = json.NewEncoder(w).Encode(body)
}

func int64Of(v any) int64 {
	switch x := v.(type) {
	case float64:
		return int64(x)
	case json.Number:
		n, _ := x.Int64()
		return n
	case string:
		var n int64
		_, _ = fmt.Sscan(x, &n)
		return n
	}
	return 0
}

// ---------------------------------------------------------------- update builders

var updateSeq struct {
	sync.Mutex
	n int64
}

// NextUpdateID returns a fresh, increasing update id.
func NextUpdateID() int64 {
	updateSeq.Lock()
	defer updateSeq.Unlock()
	updateSeq.n++
	return 500000 + updateSeq.n
}

// User builds a Telegram user.
func User(id int64, first, last, lang string) map[string]any {
	u := map[string]any{"id": id, "is_bot": false, "first_name": first}
	if last != "" {
		u["last_name"] = last
	}
	if lang != "" {
		u["language_code"] = lang
	}
	return u
}

// Group builds a group chat.
func Group(id int64, title string) map[string]any {
	return map[string]any{"id": id, "type": "supergroup", "title": title}
}

// Private builds a private chat with a user.
func Private(id int64, first string) map[string]any {
	return map[string]any{"id": id, "type": "private", "first_name": first}
}

// Message wraps a message in an update.
func Message(msg map[string]any) map[string]any {
	return map[string]any{"update_id": NextUpdateID(), "message": msg}
}

// Text is a text message (commands: "/start <code>").
func Text(chat, from map[string]any, msgID int64, text string) map[string]any {
	m := map[string]any{"message_id": msgID, "date": time.Now().Unix(), "chat": chat, "from": from, "text": text}
	if strings.HasPrefix(text, "/") {
		word, _, _ := strings.Cut(text, " ")
		m["entities"] = []any{map[string]any{"type": "bot_command", "offset": 0, "length": len([]rune(word))}}
	}
	return Message(m)
}

// Photo is a photo message whose largest size is fileID (sizes as Telegram sends them).
func Photo(chat, from map[string]any, msgID int64, fileID string, extra map[string]any) map[string]any {
	m := map[string]any{"message_id": msgID, "date": time.Now().Unix(), "chat": chat, "from": from,
		"photo": []any{
			map[string]any{"file_id": fileID + "-s", "file_unique_id": "us" + fileID, "width": 90, "height": 60, "file_size": 1000},
			map[string]any{"file_id": fileID, "file_unique_id": "u" + fileID, "width": 1280, "height": 853, "file_size": 200000},
		}}
	for k, v := range extra {
		m[k] = v
	}
	return Message(m)
}

// Document is a file sent as a file.
func Document(chat, from map[string]any, msgID int64, fileID, mime string, size int64, extra map[string]any) map[string]any {
	m := map[string]any{"message_id": msgID, "date": time.Now().Unix(), "chat": chat, "from": from,
		"document": map[string]any{"file_id": fileID, "file_unique_id": "u" + fileID, "mime_type": mime, "file_size": size, "file_name": "f"}}
	for k, v := range extra {
		m[k] = v
	}
	return Message(m)
}

// MemberUpdate is a my_chat_member update for the bot.
func (b *Bot) MemberUpdate(chat, from map[string]any, oldStatus, newStatus string) map[string]any {
	b.mu.Lock()
	bot := map[string]any{"id": b.BotID, "is_bot": true, "first_name": "Kinframe", "username": b.Username}
	b.mu.Unlock()
	return map[string]any{"update_id": NextUpdateID(), "my_chat_member": map[string]any{
		"chat": chat, "from": from, "date": time.Now().Unix(),
		"old_chat_member": map[string]any{"status": oldStatus, "user": bot},
		"new_chat_member": map[string]any{"status": newStatus, "user": bot},
	}}
}
