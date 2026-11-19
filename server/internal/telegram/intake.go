package telegram

import (
	"encoding/json"
	"strings"
	"unicode/utf16"
)

// Commands the bot answers (§11).
var Commands = []string{"start", "help", "status", "stop", "unlink"}

// Person is a sender as stored: id, display name, language; never the
// phone number. The username is kept only for linking (§13.1).
type Person struct {
	ID       int64  `json:"id"`
	Name     string `json:"name"`
	Username string `json:"username,omitempty"`
	Lang     string `json:"lang,omitempty"`
	IsBot    bool   `json:"is_bot,omitempty"`
}

// ChatRef is a sender chat (anonymous admin, channel).
type ChatRef struct {
	ID    int64  `json:"id"`
	Title string `json:"title"`
	Type  string `json:"type"`
}

// Media kinds.
const (
	MediaPhoto     = "photo"
	MediaImage     = "image" // a document with an image/* type: the original file
	MediaVideo     = "video" // a video, or a document with a video/* type
	MediaAnimation = "animation"
)

// Media is the one file of a message the server would take (§8.5).
type Media struct {
	Kind         string `json:"kind"`
	FileID       string `json:"file_id"`
	FileUniqueID string `json:"file_unique_id"`
	Size         int64  `json:"size,omitempty"` // 0: unknown
	Mime         string `json:"mime,omitempty"`
	Duration     int    `json:"duration,omitempty"` // seconds, videos (0: unknown)
	Width        int    `json:"width,omitempty"`
	Height       int    `json:"height,omitempty"`
}

// MemberChange is a my_chat_member update: the bot's own status changed.
type MemberChange struct {
	Old string `json:"old"`
	New string `json:"new"`
	By  Person `json:"by"`
}

// Update kinds.
const (
	KindMessage = "message"
	KindEdited  = "edited_message"
	KindMember  = "my_chat_member"
)

// Reduced is an update reduced to the fields the server uses (§8.1): ids,
// sender, chat, thread, media, caption, album id, spoiler, forward date.
// Message text is never kept, only a command addressed to the bot and its
// argument.
type Reduced struct {
	UpdateID int64  `json:"update_id"`
	Kind     string `json:"kind"`

	ChatID    int64  `json:"chat_id"`
	ChatType  string `json:"chat_type"` // private, group, supergroup
	ChatTitle string `json:"chat_title,omitempty"`
	IsForum   bool   `json:"is_forum,omitempty"`
	ThreadID  int64  `json:"thread_id,omitempty"` // forum topic (message_thread_id of a topic message)
	MessageID int64  `json:"message_id,omitempty"`
	Date      int64  `json:"date,omitempty"`

	From            *Person  `json:"from,omitempty"`
	SenderChat      *ChatRef `json:"sender_chat,omitempty"`
	AuthorSignature string   `json:"author_signature,omitempty"`
	AutoForward     bool     `json:"auto_forward,omitempty"`
	ReplyToBot      bool     `json:"reply_to_bot,omitempty"`

	Command    string `json:"command,omitempty"`
	CommandArg string `json:"command_arg,omitempty"`

	Media        *Media `json:"media,omitempty"`
	Caption      string `json:"caption,omitempty"`
	MediaGroupID string `json:"media_group_id,omitempty"`
	Spoiler      bool   `json:"spoiler,omitempty"`
	ForwardDate  int64  `json:"forward_date,omitempty"` // forwarded: the original message's date

	MigrateTo   int64  `json:"migrate_to,omitempty"`
	MigrateFrom int64  `json:"migrate_from,omitempty"`
	NewTitle    string `json:"new_title,omitempty"`
	TopicName   string `json:"topic_name,omitempty"`
	BotLeft     bool   `json:"bot_left,omitempty"`
	Created     bool   `json:"created,omitempty"` // group_chat_created / supergroup_chat_created

	Member *MemberChange `json:"member,omitempty"`
}

// IsGroup reports a basic group or supergroup.
func (r *Reduced) IsGroup() bool { return r.ChatType == "group" || r.ChatType == "supergroup" }

// Reduce filters an update (§8.1 step 2). It returns nil for updates the
// server drops unstored (text, stickers, voice, polls, other bots' commands,
// members joining…). botID and botUsername identify this bot.
func Reduce(raw []byte, botID int64, botUsername string) (*Reduced, int64, error) {
	var u Update
	if err := json.Unmarshal(raw, &u); err != nil {
		return nil, 0, err
	}
	return ReduceUpdate(&u, botID, botUsername), u.UpdateID, nil
}

// ReduceUpdate is Reduce for a decoded update.
func ReduceUpdate(u *Update, botID int64, botUsername string) *Reduced {
	switch {
	case u.MyChatMember != nil:
		m := u.MyChatMember
		if m.NewChatMember.User.ID != 0 && botID != 0 && m.NewChatMember.User.ID != botID {
			return nil
		}
		r := &Reduced{UpdateID: u.UpdateID, Kind: KindMember, ChatID: m.Chat.ID, ChatType: m.Chat.Type, ChatTitle: chatTitle(&m.Chat),
			IsForum: m.Chat.IsForum, Date: m.Date,
			Member: &MemberChange{Old: m.OldChatMember.Status, New: m.NewChatMember.Status, By: person(&m.From)}}
		if m.Chat.Type == "channel" {
			return nil
		}
		return r
	case u.Message != nil:
		return reduceMessage(u.UpdateID, KindMessage, u.Message, botID, botUsername)
	case u.EditedMessage != nil:
		r := reduceMessage(u.UpdateID, KindEdited, u.EditedMessage, botID, botUsername)
		if r != nil && r.Media == nil {
			return nil // only caption edits of media messages matter (§8.3)
		}
		if r != nil {
			r.Command, r.CommandArg = "", ""
		}
		return r
	}
	return nil
}

func person(u *User) Person {
	return Person{ID: u.ID, Name: u.Name(), Username: u.Username, Lang: u.LanguageCode, IsBot: u.IsBot}
}

func chatTitle(c *Chat) string {
	if c.Title != "" {
		return c.Title
	}
	n := strings.TrimSpace(c.FirstName + " " + c.LastName)
	return n
}

func reduceMessage(updateID int64, kind string, m *Message, botID int64, botUsername string) *Reduced {
	if m.Chat.Type == "channel" {
		return nil // channel posts aren't requested (§12)
	}
	r := &Reduced{UpdateID: updateID, Kind: kind, ChatID: m.Chat.ID, ChatType: m.Chat.Type, ChatTitle: chatTitle(&m.Chat),
		IsForum: m.Chat.IsForum, MessageID: m.MessageID, Date: m.Date}
	if m.IsTopicMessage {
		r.ThreadID = m.MessageThreadID
	}
	if m.From != nil {
		p := person(m.From)
		r.From = &p
	}
	if m.SenderChat != nil {
		r.SenderChat = &ChatRef{ID: m.SenderChat.ID, Title: chatTitle(m.SenderChat), Type: m.SenderChat.Type}
		r.AuthorSignature = m.AuthorSignature
	}
	r.AutoForward = m.IsAutomaticForward
	if m.ReplyToMessage != nil && m.ReplyToMessage.From != nil && botID != 0 && m.ReplyToMessage.From.ID == botID {
		r.ReplyToBot = true
	}
	keep := false
	if md := PickMedia(m); md != nil {
		r.Media = md
		r.Caption = m.Caption
		r.MediaGroupID = m.MediaGroupID
		r.Spoiler = m.HasMediaSpoiler
		if m.ForwardOrigin != nil {
			r.ForwardDate = m.ForwardOrigin.Date
		}
		keep = true
	}
	if cmd, arg, ok := ParseCommand(m.Text, m.Entities, botUsername); ok && r.Media == nil {
		r.Command, r.CommandArg = cmd, arg
		keep = true
	}
	switch {
	case m.MigrateToChatID != 0:
		r.MigrateTo, keep = m.MigrateToChatID, true
	case m.MigrateFromChatID != 0:
		r.MigrateFrom, keep = m.MigrateFromChatID, true
	case m.NewChatTitle != "":
		r.NewTitle, keep = m.NewChatTitle, true
	case m.ForumTopicCreated != nil:
		r.TopicName, keep = m.ForumTopicCreated.Name, true
		r.ThreadID = m.MessageThreadID
	case m.ForumTopicEdited != nil && m.ForumTopicEdited.Name != "":
		r.TopicName, keep = m.ForumTopicEdited.Name, true
		r.ThreadID = m.MessageThreadID
	case m.LeftChatMember != nil && botID != 0 && m.LeftChatMember.ID == botID:
		r.BotLeft, keep = true, true
	case m.GroupChatCreated || m.SupergroupCreated:
		r.Created, keep = true, true
	}
	if !keep {
		return nil
	}
	return r
}

// PickMedia chooses the file of a message (§8.5): the largest photo size, a
// document of an image or video type, a video, an animation. Round videos,
// voice, audio, stickers and other documents give nil.
func PickMedia(m *Message) *Media {
	switch {
	case len(m.Photo) > 0:
		best := m.Photo[0]
		for _, p := range m.Photo[1:] {
			if p.Width*p.Height > best.Width*best.Height || (p.Width*p.Height == best.Width*best.Height && p.FileSize > best.FileSize) {
				best = p
			}
		}
		return &Media{Kind: MediaPhoto, FileID: best.FileID, FileUniqueID: best.FileUniqueID, Size: best.FileSize, Mime: "image/jpeg",
			Width: best.Width, Height: best.Height}
	case m.Video != nil:
		v := m.Video
		return &Media{Kind: MediaVideo, FileID: v.FileID, FileUniqueID: v.FileUniqueID, Size: v.FileSize, Mime: v.MimeType, Duration: v.Duration,
			Width: v.Width, Height: v.Height}
	case m.Animation != nil:
		v := m.Animation
		return &Media{Kind: MediaAnimation, FileID: v.FileID, FileUniqueID: v.FileUniqueID, Size: v.FileSize, Mime: v.MimeType, Duration: v.Duration,
			Width: v.Width, Height: v.Height}
	case m.Document != nil:
		d := m.Document
		mt := strings.ToLower(d.MimeType)
		switch {
		case strings.HasPrefix(mt, "image/"):
			return &Media{Kind: MediaImage, FileID: d.FileID, FileUniqueID: d.FileUniqueID, Size: d.FileSize, Mime: mt}
		case strings.HasPrefix(mt, "video/"):
			return &Media{Kind: MediaVideo, FileID: d.FileID, FileUniqueID: d.FileUniqueID, Size: d.FileSize, Mime: mt}
		}
	}
	return nil
}

// ParseCommand finds a bot command at the start of a message: "/cmd",
// "/cmd@<bot>" (other bots' commands are not ours), with the rest of the text
// as its argument. Only Commands count.
func ParseCommand(text string, entities []MessageEntity, botUsername string) (cmd, arg string, ok bool) {
	if !strings.HasPrefix(text, "/") {
		return "", "", false
	}
	length := 0
	for _, e := range entities {
		if e.Type == "bot_command" && e.Offset == 0 {
			length = e.Length
		}
	}
	var word string
	if length > 0 {
		// Entity offsets count UTF-16 code units.
		units := utf16.Encode([]rune(text))
		if length > len(units) {
			return "", "", false
		}
		word = string(utf16.Decode(units[:length]))
	} else {
		word, _, _ = strings.Cut(text, " ")
	}
	rest := strings.TrimSpace(strings.TrimPrefix(text, word))
	word = strings.TrimPrefix(word, "/")
	name, at, addressed := strings.Cut(word, "@")
	if addressed && !strings.EqualFold(at, botUsername) {
		return "", "", false
	}
	name = strings.ToLower(name)
	for _, c := range Commands {
		if c == name {
			if f := strings.Fields(rest); len(f) > 0 {
				rest = f[0]
			}
			return name, rest, true
		}
	}
	return "", "", false
}
