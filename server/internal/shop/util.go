package shop

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"strconv"
	"strings"

	"github.com/google/uuid"
)

// ---------------------------------------------------------------- money

var symbols = map[string]string{"EUR": "€", "GBP": "£", "USD": "$"}

// symbolFirst lists languages that write the currency before the amount
// ("€ 79,00" in Dutch, "€79.00" in English); the rest write "79,00 €".
var symbolFirst = map[string]string{"en": "", "nl": " ", "ja": "", "ko": "", "zh-CN": "", "zh-TW": "", "th": "", "he": " ", "hi": ""}

// decimalPoint lists languages that use a decimal point; the rest use a comma.
var decimalPoint = map[string]bool{"en": true, "ja": true, "ko": true, "zh-CN": true, "zh-TW": true, "th": true, "he": true, "hi": true}

// Money formats cents for people, e.g. "€79.00" (en), "€ 79,00" (nl), "79,00 €" (fr).
func Money(cents int, currency, lang string) string {
	neg := cents < 0
	if neg {
		cents = -cents
	}
	whole, frac := strconv.Itoa(cents/100), cents%100
	sep, group := ",", "."
	if decimalPoint[lang] {
		sep, group = ".", ","
	}
	if lang == "fr" || lang == "sv" || lang == "nb" || lang == "fi" || lang == "cs" || lang == "sk" || lang == "pl" {
		group = " "
	}
	var b strings.Builder
	for i, r := range whole {
		if i > 0 && (len(whole)-i)%3 == 0 {
			b.WriteString(group)
		}
		b.WriteRune(r)
	}
	num := b.String() + sep + strconv.Itoa(frac/10) + strconv.Itoa(frac%10)
	if neg {
		num = "-" + num
	}
	sym := symbols[currency]
	if sym == "" {
		return currency + " " + num
	}
	if gap, ok := symbolFirst[lang]; ok {
		return sym + strings.ReplaceAll(gap, " ", " ") + num
	}
	return num + " " + sym
}

// ---------------------------------------------------------------- references

// refAlphabet avoids look-alikes (0/O, 1/I/L, U/V) so references read well over the phone.
const refAlphabet = "23456789ABCDEFGHJKMNPQRSTWXYZ"

// NewReference returns a short human reference like "KF-7Q3M".
func NewReference(prefix string) string {
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	out := []byte(prefix + "-")
	for _, x := range b {
		out = append(out, refAlphabet[int(x)%len(refAlphabet)])
	}
	return string(out)
}

// ---------------------------------------------------------------- cancel links

// ErrBadToken is returned for a cancel token that was not issued by this server.
var ErrBadToken = errors.New("invalid cancel token")

// Tokens signs reservation IDs for the cancel link in the confirmation email.
// They do not expire: the reservation's deletion ends them.
type Tokens struct{ key []byte }

func NewTokens(key []byte) *Tokens { return &Tokens{key: key} }

func (t *Tokens) mac(id uuid.UUID) []byte {
	m := hmac.New(sha256.New, t.key)
	m.Write([]byte("shop-cancel:"))
	m.Write(id[:])
	return m.Sum(nil)[:18]
}

// Sign returns a URL-safe token for the reservation.
func (t *Tokens) Sign(id uuid.UUID) string {
	return base64.RawURLEncoding.EncodeToString(id[:]) + "." + base64.RawURLEncoding.EncodeToString(t.mac(id))
}

// Verify returns the reservation ID of a token.
func (t *Tokens) Verify(token string) (uuid.UUID, error) {
	a, b, ok := strings.Cut(token, ".")
	if !ok {
		return uuid.Nil, ErrBadToken
	}
	raw, err := base64.RawURLEncoding.DecodeString(a)
	if err != nil || len(raw) != 16 {
		return uuid.Nil, ErrBadToken
	}
	sig, err := base64.RawURLEncoding.DecodeString(b)
	if err != nil {
		return uuid.Nil, ErrBadToken
	}
	id := uuid.UUID(raw)
	if !hmac.Equal(sig, t.mac(id)) {
		return uuid.Nil, ErrBadToken
	}
	return id, nil
}
