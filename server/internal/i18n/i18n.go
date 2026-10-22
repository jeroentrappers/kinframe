// Package i18n holds the server's message catalogs (locales/<lang>.json) and
// picks a supported language for a request, user or frame.
//
// Catalogs have three sections:
//   - "messages": keyed texts with {name} placeholders (frame names, push
//     notifications, problem titles as "problem.<code>");
//   - "details": problem details keyed by their English printf format, so a
//     call site like problem.New(code, "album changed since version %d", v)
//     needs no key of its own; translations keep the same verbs in order.
//
// English is the fallback for anything missing.
package i18n

import (
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"path"
	"strings"
	"sync"
	"unicode/utf8"

	"golang.org/x/text/language"
)

// Languages are the supported UI languages as stored in settings
// (locale.language) and used by every client. Keep in sync with the frame app
// (Locales.kt) and the web app (src/lib/i18n/locales.ts).
var Languages = []string{
	"en", "nl", "ar", "bg", "cs", "da", "de", "el", "es", "fi", "fr", "he", "hi", "hr", "hu", "id", "it", "ja",
	"ko", "lt", "lv", "nb", "pl", "pt", "ro", "ru", "sk", "sl", "sr", "sv", "th", "tr", "uk", "vi", "zh-CN", "zh-TW",
}

// Default is the fallback language.
const Default = "en"

//go:embed locales/*.json
var files embed.FS

type catalog struct {
	Messages map[string]string `json:"messages"`
	Details  map[string]string `json:"details"`
}

var (
	loadOnce sync.Once
	catalogs map[string]*catalog
	loadErr  error
	matcher  language.Matcher
)

func load() {
	catalogs = map[string]*catalog{}
	entries, err := fs.Glob(files, "locales/*.json")
	if err != nil {
		loadErr = err
		return
	}
	for _, e := range entries {
		b, err := files.ReadFile(e)
		if err != nil {
			loadErr = err
			return
		}
		var c catalog
		if err := json.Unmarshal(b, &c); err != nil {
			loadErr = fmt.Errorf("%s: %w", e, err)
			return
		}
		catalogs[strings.TrimSuffix(path.Base(e), ".json")] = &c
	}
	tags := make([]language.Tag, len(Languages))
	for i, l := range Languages {
		tags[i] = language.MustParse(l)
	}
	matcher = language.NewMatcher(tags)
}

func cat(lang string) *catalog {
	loadOnce.Do(load)
	if loadErr != nil {
		panic(loadErr) // embedded files: a broken catalog is a build error, caught by tests
	}
	return catalogs[lang]
}

// Normalize maps any BCP 47 tag or Accept-Language value ("nl-BE", "iw",
// "zh-Hant-HK", "no", "de-CH;q=0.8, en") to a supported language, or "" when
// nothing reasonable matches.
func Normalize(tags string) string {
	tags = strings.TrimSpace(tags)
	if tags == "" {
		return ""
	}
	cat(Default)
	var want []language.Tag
	if strings.ContainsAny(tags, ",;") {
		parsed, _, err := language.ParseAcceptLanguage(tags)
		if err != nil {
			return ""
		}
		want = parsed
	} else {
		t, err := language.Parse(strings.ReplaceAll(tags, "_", "-"))
		if err != nil {
			return ""
		}
		want = []language.Tag{t}
	}
	if len(want) == 0 {
		return ""
	}
	_, idx, conf := matcher.Match(want...)
	if conf == language.No {
		return ""
	}
	return Languages[idx]
}

// Pick returns the first candidate that normalizes to a supported language,
// or Default.
func Pick(candidates ...string) string {
	for _, c := range candidates {
		if l := Normalize(c); l != "" {
			return l
		}
	}
	return Default
}

// T returns the message for key in lang with {placeholders} replaced.
func T(lang, key string, args map[string]string) string {
	msg := ""
	if c := cat(lang); c != nil {
		msg = c.Messages[key]
	}
	if msg == "" {
		msg = cat(Default).Messages[key]
	}
	if msg == "" {
		return key
	}
	if len(args) == 0 {
		return msg
	}
	repl := make([]string, 0, 2*len(args))
	for k, v := range args {
		repl = append(repl, "{"+k+"}", v)
	}
	return strings.NewReplacer(repl...).Replace(msg)
}

// Has reports whether the English catalog defines key.
func Has(key string) bool { _, ok := cat(Default).Messages[key]; return ok }

// Detail translates an English problem-detail format string; unknown formats
// are returned unchanged.
func Detail(lang, format string) string {
	if c := cat(lang); c != nil {
		if d := c.Details[format]; d != "" {
			return d
		}
	}
	return format
}

// FirstName is the part of a display name used in friendly texts.
func FirstName(display string) string {
	f := strings.Fields(display)
	if len(f) == 0 {
		return ""
	}
	return f[0]
}

// DefaultFrameName names a freshly claimed frame after its owner, in lang,
// e.g. "Jeroen's frame" / "Fotolijst van Jeroen". At most 40 characters.
func DefaultFrameName(lang, ownerDisplayName string) string {
	name := FirstName(ownerDisplayName)
	if name == "" {
		return truncate(T(lang, "frame.default_name_anon", nil), 40)
	}
	full := T(lang, "frame.default_name", map[string]string{"name": name})
	if utf8.RuneCountInString(full) <= 40 {
		return full
	}
	// Shorten the name, not the sentence, so the result stays grammatical.
	over := utf8.RuneCountInString(full) - 40
	r := []rune(name)
	if over+1 < len(r) {
		return T(lang, "frame.default_name", map[string]string{"name": string(r[:len(r)-over-1]) + "…"})
	}
	return truncate(T(lang, "frame.default_name_anon", nil), 40)
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n])
	}
	return s
}
