package mailin

import (
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

// MaxCaption is the longest caption a mail gives its photos (§8.5).
const MaxCaption = 280

// markerRe matches one leading reply/forward marker or list tag: "Re:",
// "Fwd:", "Re[2]:", "AW:", "[family]".
var markerRe = regexp.MustCompile(`(?i)^\s*(?:\[[^\]]{0,60}\]\s*|(?:re|fwd?|tr|wg|aw|antw|doorst|rv|sv|vs|r|i|enc|odp)\s*(?:\[\d+\]|\(\d+\))?\s*[:：]\s*)`)

// fileNameRe: a subject that is only a file name or a camera's default label.
var fileNameRe = regexp.MustCompile(`(?i)^(?:(?:img|dsc|dscn|pxl|vid|mov|photo|foto|image|picture|bild|screenshot)[_ -]?[\d_ -]*(?:\.\w{2,5})?|[\w-]+\.(?:jpe?g|png|heic|heif|webp|gif|mp4|mov|avif))$`)

var phoneSubjects = map[string]bool{
	"photo": true, "photos": true, "foto": true, "fotos": true, "image": true, "images": true, "picture": true, "pictures": true,
	"bild": true, "bilder": true, "photo attached": true, "sent from my iphone": true, "sent from my ipad": true,
	"verzonden met mijn iphone": true, "envoyé de mon iphone": true, "von meinem iphone gesendet": true, "no subject": true,
	"(no subject)": true, "geen onderwerp": true, "(geen onderwerp)": true,
}

// CleanSubject strips reply and forward markers and list tags, repeatedly.
func CleanSubject(s string) string {
	s = cleanLine(s)
	for {
		loc := markerRe.FindStringIndex(s)
		if loc == nil || loc[1] == 0 {
			return strings.TrimSpace(s)
		}
		s = s[loc[1]:]
	}
}

// subjectCaption is the subject as a caption, or "" when it counts as no subject.
func subjectCaption(subject string) string {
	s := CleanSubject(subject)
	if s == "" || phoneSubjects[strings.ToLower(s)] || fileNameRe.MatchString(s) {
		return ""
	}
	return s
}

// quoteRe marks where quoted text or a signature starts.
var quoteStartRe = regexp.MustCompile(`(?i)^(?:>|-- ?$|_{5,}|-{5,}\s*(?:original|forwarded|oorspronkelijk|ursprüngliche|message d'origine)|` +
	`on .{0,200}wrote:?$|op .{0,200}schreef:?$|am .{0,200}schrieb:?$|le .{0,200}a écrit\s*:?$|el .{0,200}escribió:?$|il .{0,200}ha scritto:?$|` +
	`from:\s|van:\s|von:\s|de\s?:\s|begin forwarded message|doorgestuurd bericht|forwarded message)`)

var deviceSignatures = []string{
	"sent from my", "verzonden met mijn", "verstuurd vanaf mijn", "verzonden vanaf mijn", "envoyé de mon", "von meinem",
	"get outlook for", "download outlook for", "enviado desde mi", "inviato da", "skickat från min", "sendt fra min",
}

// Caption picks a mail's caption: the cleaned subject, else the first
// non-empty line of the text before any quote or signature; at most 280
// characters, control characters removed.
func Caption(subject, text string) string {
	if c := subjectCaption(subject); c != "" {
		return limitRunes(c, MaxCaption)
	}
	for _, line := range strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n") {
		t := strings.TrimSpace(line)
		if t == "" {
			continue
		}
		if quoteStartRe.MatchString(t) || line == "-- " {
			break
		}
		low := strings.ToLower(t)
		sig := false
		for _, d := range deviceSignatures {
			if strings.HasPrefix(low, d) {
				sig = true
				break
			}
		}
		if sig {
			break
		}
		return limitRunes(cleanLine(t), MaxCaption)
	}
	return ""
}

// cleanLine removes control characters and line breaks and folds spaces.
func cleanLine(s string) string {
	s = strings.Map(func(r rune) rune {
		if r == '\n' || r == '\r' || r == '\t' {
			return ' '
		}
		if unicode.IsControl(r) || r == utf8.RuneError {
			return -1
		}
		return r
	}, s)
	return strings.Join(strings.Fields(s), " ")
}

func limitRunes(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return strings.TrimSpace(string(r[:n]))
	}
	return s
}

// Label is the credit name of a mail (§9.1): the From display name ("Jan
// via Family List" keeps "Jan"), else the fallback, else the local part; at
// most 40 characters.
func Label(displayName, fallback, addr string) string {
	n := cleanLine(displayName)
	if i := strings.Index(strings.ToLower(n), " via "); i > 0 {
		n = strings.TrimSpace(n[:i])
	}
	n = strings.Trim(n, `"' `)
	if n == "" || strings.Contains(n, "@") {
		n = cleanLine(fallback)
	}
	if n == "" {
		n, _, _ = strings.Cut(addr, "@")
	}
	return limitRunes(n, 40)
}

// ReplySubject is "Re: <original>" with the original's markers stripped,
// line breaks removed, at most 100 characters.
func ReplySubject(original string) string {
	s := CleanSubject(original)
	if s == "" {
		return ""
	}
	return limitRunes("Re: "+s, 100)
}
