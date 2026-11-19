package telegram

import (
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

// SecretHeader carries the webhook secret on Telegram's requests.
const SecretHeader = "X-Telegram-Bot-Api-Secret-Token"

// WebhookSecret derives the webhook secret from the server's key (§7):
// HKDF-SHA256 with info "kinframe telegram webhook v1", 32 bytes as 43
// base64url characters (Telegram allows A-Z a-z 0-9 _ -). Every process of
// the server computes the same value, so a restart doesn't re-register.
func WebhookSecret(key []byte) (string, error) {
	if len(key) < 32 {
		return "", errors.New("telegram: secrets key must be at least 32 bytes")
	}
	k, err := hkdf.Key(sha256.New, key, nil, "kinframe telegram webhook v1", 32)
	if err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(k), nil
}

// CheckSecret compares the header value with the secret in constant time.
func CheckSecret(got, want string) bool {
	if want == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

// Code purposes and their one-letter prefixes (§5.1).
const (
	PurposeConnectGroup   = 'c'
	PurposeConnectPrivate = 'p'
	PurposeLink           = 'l'
	PurposeJoin           = 'j'
)

const codeAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

// NewCode returns a code: the purpose prefix and 16 random characters from
// [A-Za-z0-9], within Telegram's start parameter rules.
func NewCode(prefix byte) string {
	b := make([]byte, 17)
	b[0] = prefix
	r := make([]byte, 16)
	_, _ = rand.Read(r)
	for i := range r {
		// 62 symbols: reject bytes that would bias the distribution.
		for r[i] >= 248 {
			_, _ = rand.Read(r[i : i+1])
		}
		b[i+1] = codeAlphabet[int(r[i])%len(codeAlphabet)]
	}
	return string(b)
}

var codeRe = regexp.MustCompile(`^[cplj][A-Za-z0-9]{16}$`)

// ValidCode reports whether s looks like a code (a wrong guess still costs a token).
func ValidCode(s string) bool { return codeRe.MatchString(s) }

// HashCode is how codes are stored (sha256, hex).
func HashCode(code string) string {
	h := sha256.Sum256([]byte(code))
	return hex.EncodeToString(h[:])
}

// Caption limits a caption to kinframe's 280 characters, cut at a word with
// "…" (§8.5). Empty captions are "".
func Caption(s string) string {
	s = strings.TrimSpace(s)
	const max = 280
	if utf8.RuneCountInString(s) <= max {
		return s
	}
	r := []rune(s)[:max-1]
	cut := len(r)
	for i := len(r) - 1; i > max/2; i-- {
		if unicode.IsSpace(r[i]) {
			cut = i
			break
		}
	}
	return strings.TrimRightFunc(string(r[:cut]), unicode.IsSpace) + "…"
}

// Emoji the bot sets (Telegram's fixed set for bots).
const (
	EmojiConfirm = "👍"
	EmojiSpoiler = "🙈"
)

// ReactionEmoji maps a frame reaction to the emoji mirrored on the message (§10.2).
func ReactionEmoji(reaction string) string {
	switch reaction {
	case "love":
		return "❤"
	case "laugh":
		return "😁"
	case "wow":
		return "🤩"
	case "sad":
		return "😢"
	}
	return EmojiConfirm
}

// Language maps a Telegram language_code to the tags the server's catalog
// understands (§11): pt-br → pt, nn/no → nb, iw → he, in → id. The result
// still goes through i18n.Pick.
func Language(code string) string {
	c := strings.ToLower(strings.TrimSpace(code))
	switch {
	case c == "":
		return ""
	case strings.HasPrefix(c, "pt"):
		return "pt"
	case c == "nn", c == "no", strings.HasPrefix(c, "nn-"), strings.HasPrefix(c, "no-"):
		return "nb"
	case c == "iw":
		return "he"
	case c == "in":
		return "id"
	case c == "zh-hans", c == "zh-cn", c == "zh-sg", c == "zh":
		return "zh-CN"
	case c == "zh-hant", c == "zh-tw", c == "zh-hk":
		return "zh-TW"
	}
	return c
}
