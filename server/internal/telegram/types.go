// Package telegram is the server side of the Telegram bridge (docs/spec/telegram.md):
// the Bot API client, the webhook secret, update filtering (only what the
// server needs is ever stored), long polling with a single consumer, per-chat
// ordered dispatch, batching rules and the rate-limited outbound queue. It
// knows nothing about frames or feeds; package api wires it to them.
package telegram

// Bot API types: only the fields the server reads (core.telegram.org/bots/api).

// Update is one incoming update.
type Update struct {
	UpdateID      int64              `json:"update_id"`
	Message       *Message           `json:"message,omitempty"`
	EditedMessage *Message           `json:"edited_message,omitempty"`
	MyChatMember  *ChatMemberUpdated `json:"my_chat_member,omitempty"`
}

// User is a Telegram user or bot.
type User struct {
	ID           int64  `json:"id"`
	IsBot        bool   `json:"is_bot"`
	FirstName    string `json:"first_name"`
	LastName     string `json:"last_name,omitempty"`
	Username     string `json:"username,omitempty"`
	LanguageCode string `json:"language_code,omitempty"`
}

// Name is the display name (first and last name).
func (u *User) Name() string {
	if u == nil {
		return ""
	}
	if u.LastName == "" {
		return u.FirstName
	}
	if u.FirstName == "" {
		return u.LastName
	}
	return u.FirstName + " " + u.LastName
}

// Chat is a chat.
type Chat struct {
	ID        int64  `json:"id"`
	Type      string `json:"type"` // private, group, supergroup, channel
	Title     string `json:"title,omitempty"`
	Username  string `json:"username,omitempty"`
	FirstName string `json:"first_name,omitempty"`
	LastName  string `json:"last_name,omitempty"`
	IsForum   bool   `json:"is_forum,omitempty"`
}

// Message is a message (only the fields the server uses).
type Message struct {
	MessageID          int64           `json:"message_id"`
	MessageThreadID    int64           `json:"message_thread_id,omitempty"`
	IsTopicMessage     bool            `json:"is_topic_message,omitempty"`
	From               *User           `json:"from,omitempty"`
	SenderChat         *Chat           `json:"sender_chat,omitempty"`
	Date               int64           `json:"date"`
	Chat               Chat            `json:"chat"`
	ForwardOrigin      *MessageOrigin  `json:"forward_origin,omitempty"`
	IsAutomaticForward bool            `json:"is_automatic_forward,omitempty"`
	ReplyToMessage     *Message        `json:"reply_to_message,omitempty"`
	AuthorSignature    string          `json:"author_signature,omitempty"`
	Text               string          `json:"text,omitempty"`
	Entities           []MessageEntity `json:"entities,omitempty"`
	Caption            string          `json:"caption,omitempty"`
	MediaGroupID       string          `json:"media_group_id,omitempty"`
	HasMediaSpoiler    bool            `json:"has_media_spoiler,omitempty"`
	Photo              []PhotoSize     `json:"photo,omitempty"`
	Document           *Document       `json:"document,omitempty"`
	Video              *Video          `json:"video,omitempty"`
	Animation          *Video          `json:"animation,omitempty"`
	VideoNote          *struct{}       `json:"video_note,omitempty"`
	Voice              *struct{}       `json:"voice,omitempty"`
	Audio              *struct{}       `json:"audio,omitempty"`
	Sticker            *struct{}       `json:"sticker,omitempty"`
	NewChatTitle       string          `json:"new_chat_title,omitempty"`
	LeftChatMember     *User           `json:"left_chat_member,omitempty"`
	GroupChatCreated   bool            `json:"group_chat_created,omitempty"`
	SupergroupCreated  bool            `json:"supergroup_chat_created,omitempty"`
	MigrateToChatID    int64           `json:"migrate_to_chat_id,omitempty"`
	MigrateFromChatID  int64           `json:"migrate_from_chat_id,omitempty"`
	ForumTopicCreated  *ForumTopic     `json:"forum_topic_created,omitempty"`
	ForumTopicEdited   *ForumTopic     `json:"forum_topic_edited,omitempty"`
}

// MessageOrigin describes where a forwarded message came from.
type MessageOrigin struct {
	Type string `json:"type"` // user, hidden_user, chat, channel
	Date int64  `json:"date"`
}

// MessageEntity marks a command, a link, bold text…
type MessageEntity struct {
	Type   string `json:"type"`
	Offset int    `json:"offset"`
	Length int    `json:"length"`
}

// ForumTopic is a topic created or renamed in a forum group.
type ForumTopic struct {
	Name string `json:"name"`
}

// PhotoSize is one size of a photo.
type PhotoSize struct {
	FileID       string `json:"file_id"`
	FileUniqueID string `json:"file_unique_id"`
	Width        int    `json:"width"`
	Height       int    `json:"height"`
	FileSize     int64  `json:"file_size,omitempty"`
}

// Document is a file sent as a file.
type Document struct {
	FileID       string `json:"file_id"`
	FileUniqueID string `json:"file_unique_id"`
	FileName     string `json:"file_name,omitempty"`
	MimeType     string `json:"mime_type,omitempty"`
	FileSize     int64  `json:"file_size,omitempty"`
}

// Video is a video or an animation (GIFs arrive as silent MP4).
type Video struct {
	FileID       string `json:"file_id"`
	FileUniqueID string `json:"file_unique_id"`
	Width        int    `json:"width"`
	Height       int    `json:"height"`
	Duration     int    `json:"duration"`
	FileName     string `json:"file_name,omitempty"`
	MimeType     string `json:"mime_type,omitempty"`
	FileSize     int64  `json:"file_size,omitempty"`
}

// ChatMemberUpdated reports a change of the bot's own membership.
type ChatMemberUpdated struct {
	Chat          Chat       `json:"chat"`
	From          User       `json:"from"`
	Date          int64      `json:"date"`
	OldChatMember ChatMember `json:"old_chat_member"`
	NewChatMember ChatMember `json:"new_chat_member"`
}

// ChatMember is a member's status in a chat.
type ChatMember struct {
	Status string `json:"status"` // creator, administrator, member, restricted, left, kicked
	User   User   `json:"user"`
}

// Me is the getMe result.
type Me struct {
	User
	CanJoinGroups           bool `json:"can_join_groups"`
	CanReadAllGroupMessages bool `json:"can_read_all_group_messages"`
}

// File is the getFile result.
type File struct {
	FileID       string `json:"file_id"`
	FileUniqueID string `json:"file_unique_id"`
	FileSize     int64  `json:"file_size,omitempty"`
	FilePath     string `json:"file_path,omitempty"`
}

// WebhookInfo is the getWebhookInfo result.
type WebhookInfo struct {
	URL                string   `json:"url"`
	PendingUpdateCount int      `json:"pending_update_count"`
	MaxConnections     int      `json:"max_connections,omitempty"`
	AllowedUpdates     []string `json:"allowed_updates,omitempty"`
	LastErrorMessage   string   `json:"last_error_message,omitempty"`
}

// InlineButton is a URL button under a message.
type InlineButton struct {
	Text string `json:"text"`
	URL  string `json:"url"`
}

// BotCommand is a command shown in Telegram's menu.
type BotCommand struct {
	Command     string `json:"command"`
	Description string `json:"description"`
}

// AllowedUpdates are the update kinds the server asks for (§7).
var AllowedUpdates = []string{"message", "edited_message", "my_chat_member"}
