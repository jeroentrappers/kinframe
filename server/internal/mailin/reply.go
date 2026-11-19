package mailin

import (
	"html"
	"strconv"
	"strings"

	"github.com/jeroentrappers/kinframe/server/internal/i18n"
)

// Reply kinds (EmailReplyKind).
const (
	ReplyDelivered = "delivered"
	ReplyHeld      = "held"
	ReplyNoMedia   = "no_media"
	ReplyTooLarge  = "too_large"
	ReplyEncrypted = "encrypted"
)

// AppBlock is how much of the app promotion a reply carries (§10.4).
type AppBlock int

const (
	AppNone   AppBlock = iota // the sender uses the app already
	AppFooter                 // one line
	AppLong                   // the full block, once per sender address
)

// AppLinks are where the app is (store links only when configured).
type AppLinks struct {
	IOS, Android string
	Web          string // the web app
	Landing      string // the server's page, when no store link is configured
}

// ReplyInput is everything a reply says; nothing of the mail but its subject and counts.
type ReplyInput struct {
	Kind       string
	Lang       string
	Name       string // the address's label ("Oma's frame")
	Server     string // the server's name
	Subject    string // the original subject
	Photos     int    // delivered photos
	Videos     int    // delivered videos
	Mails      int    // emails a coalesced confirmation covers (>1: "from your last N emails")
	Skipped    map[string]int
	MaxMinutes int
	App        AppBlock
	Links      AppLinks
	JoinURL    string // "Join <name> in the app" (allowed, authenticated non-members)
	OpenURL    string // "Open kinframe" (matched members)
}

// ReplyMessage is a rendered reply.
type ReplyMessage struct {
	Subject, Text, HTML string
}

// RenderReply renders a reply in the input's language.
func RenderReply(in ReplyInput) ReplyMessage {
	l := i18n.Pick(in.Lang)
	t := func(key string, args map[string]string) string { return i18n.T(l, key, args) }
	name := cleanLine(in.Name)
	var lines []string
	switch in.Kind {
	case ReplyDelivered:
		n := in.Photos + in.Videos
		switch {
		case in.Mails > 1:
			lines = append(lines, t("email.reply.delivered_batch", map[string]string{"count": strconv.Itoa(n), "mails": strconv.Itoa(in.Mails), "name": name}))
		case n == 1 && in.Videos == 1:
			lines = append(lines, t("email.reply.delivered_video", map[string]string{"name": name}))
		case n == 1:
			lines = append(lines, t("email.reply.delivered_photo", map[string]string{"name": name}))
		default:
			lines = append(lines, t("email.reply.delivered_many", map[string]string{"count": strconv.Itoa(n), "name": name}))
		}
		lines = append(lines, skippedLines(t, in.Skipped, in.MaxMinutes)...)
	case ReplyHeld:
		lines = append(lines, t("email.reply.held", map[string]string{"name": name}))
	case ReplyNoMedia:
		lines = append(lines, t("email.reply.no_media", nil))
	case ReplyTooLarge:
		lines = append(lines, t("email.reply.too_large", map[string]string{"minutes": strconv.Itoa(in.MaxMinutes)}))
	case ReplyEncrypted:
		lines = append(lines, t("email.reply.encrypted", nil))
	}
	subject := ReplySubject(in.Subject)
	if subject == "" {
		subject = limitRunes("Re: "+t("email.reply.subject", map[string]string{"name": name}), 100)
	}

	var text, htm strings.Builder
	for _, ln := range lines {
		text.WriteString(ln + "\n")
	}
	htm.WriteString(`<div style="font-family:sans-serif;font-size:15px;line-height:1.5;color:#222">`)
	htm.WriteString("<p>" + html.EscapeString(lines[0]) + "</p>")
	if len(lines) > 1 {
		htm.WriteString("<p>")
		for i, ln := range lines[1:] {
			if i > 0 {
				htm.WriteString("<br>")
			}
			htm.WriteString(html.EscapeString(ln))
		}
		htm.WriteString("</p>")
	}

	links := in.Links.list(t)
	switch in.App {
	case AppLong:
		text.WriteString("\n" + t("email.app.heading", nil) + "\n" + t("email.app.benefits", nil) + "\n")
		htm.WriteString(`<div style="margin:24px 0;padding:16px;border:1px solid #ddd;border-radius:8px;background:#f7f7f5">`)
		htm.WriteString(`<p style="margin:0 0 8px;font-weight:bold">` + html.EscapeString(t("email.app.heading", nil)) + "</p>")
		htm.WriteString(`<p style="margin:0 0 12px">` + html.EscapeString(t("email.app.benefits", nil)) + "</p>")
		if len(links) > 0 {
			text.WriteString(t("email.app.get", nil) + "\n")
			htm.WriteString(`<p style="margin:0 0 12px">`)
			for i, lk := range links {
				text.WriteString("- " + lk[0] + ": " + lk[1] + "\n")
				if i > 0 {
					htm.WriteString(" · ")
				}
				htm.WriteString(`<a href="` + html.EscapeString(lk[1]) + `">` + html.EscapeString(lk[0]) + "</a>")
			}
			htm.WriteString("</p>")
		}
		if b := in.button(t, name); b[1] != "" {
			text.WriteString(b[0] + ": " + b[1] + "\n")
			htm.WriteString(`<p style="margin:0"><a href="` + html.EscapeString(b[1]) + `" style="display:inline-block;padding:10px 16px;` +
				`border-radius:6px;background:#2f6f4f;color:#fff;text-decoration:none">` + html.EscapeString(b[0]) + "</a></p>")
		}
		htm.WriteString("</div>")
	case AppFooter:
		link := in.JoinURL
		if link == "" && len(links) > 0 {
			link = links[0][1]
		}
		if link == "" {
			link = in.Links.Web
		}
		if link != "" {
			ln := t("email.app.footer", map[string]string{"link": link})
			text.WriteString("\n" + ln + "\n")
			pre, _, _ := strings.Cut(t("email.app.footer", map[string]string{"link": "\x00"}), "\x00")
			htm.WriteString(`<p style="color:#555">` + html.EscapeString(pre) + `<a href="` + html.EscapeString(link) + `">` + html.EscapeString(link) + "</a></p>")
		}
	case AppNone:
		if in.OpenURL != "" {
			b := in.button(t, name)
			text.WriteString("\n" + b[0] + ": " + b[1] + "\n")
			htm.WriteString(`<p><a href="` + html.EscapeString(b[1]) + `">` + html.EscapeString(b[0]) + "</a></p>")
		}
	}
	auto := t("email.reply.automatic", map[string]string{"server": cleanLine(in.Server)})
	text.WriteString("\n-- \n" + auto + "\n")
	htm.WriteString(`<p style="color:#888;font-size:12px">` + html.EscapeString(auto) + "</p></div>")
	return ReplyMessage{Subject: subject, Text: text.String(), HTML: htm.String()}
}

// button is the join (or open) button: label and link.
func (in ReplyInput) button(t func(string, map[string]string) string, name string) [2]string {
	switch {
	case in.JoinURL != "":
		return [2]string{t("email.app.join", map[string]string{"name": name}), in.JoinURL}
	case in.OpenURL != "":
		return [2]string{t("email.app.open", nil), in.OpenURL}
	}
	return [2]string{}
}

func (l AppLinks) list(t func(string, map[string]string) string) [][2]string {
	var out [][2]string
	if l.IOS != "" {
		out = append(out, [2]string{t("email.app.ios", nil), l.IOS})
	}
	if l.Android != "" {
		out = append(out, [2]string{t("email.app.android", nil), l.Android})
	}
	if l.IOS == "" && l.Android == "" && l.Landing != "" {
		out = append(out, [2]string{t("email.app.download", nil), l.Landing})
	}
	if l.Web != "" {
		out = append(out, [2]string{t("email.app.web", nil), l.Web})
	}
	return out
}

func skippedLines(t func(string, map[string]string) string, sk map[string]int, maxMin int) []string {
	var out []string
	if n := sk["too_long"]; n > 0 {
		out = append(out, t("email.reply.skipped_too_long", map[string]string{"count": strconv.Itoa(n), "minutes": strconv.Itoa(maxMin)}))
	}
	if n := sk["too_large"]; n > 0 {
		out = append(out, t("email.reply.skipped_too_large", map[string]string{"count": strconv.Itoa(n)}))
	}
	if n := sk["duplicate"]; n > 0 {
		out = append(out, t("email.reply.skipped_duplicate", map[string]string{"count": strconv.Itoa(n)}))
	}
	// Tiny images (signatures, logos) are not worth mentioning.
	if n := sk["unsupported"] + sk["over_limit"]; n > 0 {
		out = append(out, t("email.reply.skipped_other", map[string]string{"count": strconv.Itoa(n)}))
	}
	return out
}

// HashAddress is the log form of a sender address (§12.4): never the address itself.
func HashAddress(addr string) string {
	return BodyHash([]byte(strings.ToLower(addr)))[:16]
}
