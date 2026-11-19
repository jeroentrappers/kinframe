package api

// The email digest (README §5.20 "Email digest"): unread comments in followed threads and unread messages,
// daily at 08:00 or weekly on Monday 08:00 local time, through the server's own mailer with the app footer
// and a one-click "stop these emails" link.

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"html"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/i18n"
	"github.com/jeroentrappers/kinframe/server/internal/shop"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

const (
	digestHour  = 8
	digestLines = 10 // per section
	digestText  = 200
)

// digestAvailable: the server can send mail.
func (s *Server) digestAvailable() bool { return s.mailer != nil }

// digestBoundary is the latest digest time at or before now: today 08:00 (daily) or this week's Monday 08:00
// (weekly), in loc.
func digestBoundary(now time.Time, loc *time.Location, weekly bool) time.Time {
	l := now.In(loc)
	day := time.Date(l.Year(), l.Month(), l.Day(), digestHour, 0, 0, 0, loc)
	if weekly {
		m := mondayOf(now, loc)
		day = time.Date(m.Year(), m.Month(), m.Day(), digestHour, 0, 0, 0, loc)
	}
	if day.After(now) {
		if weekly {
			day = day.AddDate(0, 0, -7)
		} else {
			day = day.AddDate(0, 0, -1)
		}
	}
	return day
}

// digestDue reports whether a digest is due: the boundary passed since the last one.
func digestDue(now time.Time, loc *time.Location, weekly bool, last *time.Time) bool {
	b := digestBoundary(now, loc, weekly)
	return last == nil || last.Before(b)
}

type digestLine struct {
	Author  string  `db:"author"`
	Where   *string `db:"place"`
	Body    *string `db:"body"`
	BodyEnc []byte  `db:"body_enc"`
	Media   bool    `db:"media"`
	Voice   bool    `db:"voice"`
}

// digestComments are others' comments since cutoff in threads the user follows (their posts, threads they
// commented in, comments mentioning them), unread and not muted, in posts they may see.
const digestCommentsSQL = `
	SELECT u.display_name AS author, coalesce(fd.name, fr.name) AS place, c.body, c.body_enc, false AS media, false AS voice
	FROM comments c
	JOIN threads t ON t.post_id = c.post_id
	JOIN users u ON u.id = c.author_user_id AND u.deleted_at IS NULL
	LEFT JOIN feeds fd ON fd.id = t.feed_id
	LEFT JOIN frames fr ON fr.id = t.frame_id
	LEFT JOIN thread_reads tr ON tr.post_id = c.post_id AND tr.user_id = $1
	WHERE c.kind = 'person' AND c.author_user_id <> $1 AND c.deleted_at IS NULL AND c.removed_at IS NULL AND c.created_at > $2
	  AND NOT coalesce(tr.muted, false) AND (tr.read_up_to IS NULL OR c.id > tr.read_up_to)
	  AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = $1 AND b.blocked_user_id = c.author_user_id)
	  AND (
	    (t.feed_id IS NOT NULL AND fd.deleted_at IS NULL
	     AND EXISTS (SELECT 1 FROM feed_members m WHERE m.feed_id = t.feed_id AND m.user_id = $1)
	     AND (t.circle_id IS NULL OR EXISTS (SELECT 1 FROM circle_members cm WHERE cm.circle_id = t.circle_id AND cm.user_id = $1)))
	    OR (t.frame_id IS NOT NULL AND (EXISTS (SELECT 1 FROM frame_posts fp WHERE fp.id = t.post_id AND fp.sender_user_id = $1)
	        OR EXISTS (SELECT 1 FROM memberships ms WHERE ms.frame_id = t.frame_id AND ms.user_id = $1
	                   AND (ms.role IN ('owner', 'admin') OR coalesce((ms.permissions->>'see_library')::boolean, false)))))
	  )
	  AND (
	    EXISTS (SELECT 1 FROM feed_posts p WHERE p.id = c.post_id AND p.poster_user_id = $1)
	    OR EXISTS (SELECT 1 FROM frame_posts p WHERE p.id = c.post_id AND p.sender_user_id = $1)
	    OR EXISTS (SELECT 1 FROM comments mine WHERE mine.post_id = c.post_id AND mine.author_user_id = $1)
	    OR EXISTS (SELECT 1 FROM comment_mentions cm WHERE cm.comment_id = c.id AND cm.user_id = $1)
	  )
	ORDER BY c.id`

// digestMessagesSQL are others' unread messages since cutoff in the user's conversations that are not muted.
const digestMessagesSQL = `
	SELECT u.display_name AS author, coalesce(cv.name, ci.name, fd.name, u.display_name) AS place, m.body, m.body_enc,
	       EXISTS (SELECT 1 FROM message_items mi WHERE mi.message_id = m.id) AS media, m.voice_item_id IS NOT NULL AS voice
	FROM conversation_members me
	JOIN conversations cv ON cv.id = me.conversation_id AND cv.archived_at IS NULL
	JOIN messages m ON m.conversation_id = cv.id
	JOIN users u ON u.id = m.author_user_id AND u.deleted_at IS NULL
	LEFT JOIN feeds fd ON fd.id = cv.feed_id
	LEFT JOIN circles ci ON ci.id = cv.circle_id
	WHERE me.user_id = $1 AND (me.muted_until IS NULL OR me.muted_until < now())
	  AND m.kind = 'person' AND m.author_user_id <> $1 AND m.deleted_at IS NULL AND m.created_at > $2
	  AND (me.visible_from IS NULL OR m.created_at >= me.visible_from)
	  AND (me.last_read_message_id IS NULL OR m.id > me.last_read_message_id)
	  AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.user_id = $1 AND b.blocked_user_id = m.author_user_id)
	ORDER BY m.id`

func (s *Server) digestLines(ctx context.Context, query, lineKey string, userID uuid.UUID, since time.Time, lang string) ([]string, int, error) {
	rows, err := engRows[digestLine](s.st.Q().Query(ctx, query, userID, since))
	if err != nil {
		return nil, 0, err
	}
	var out []string
	for _, r := range rows {
		if len(out) == digestLines {
			break
		}
		text := deref(r.Body)
		if len(r.BodyEnc) > 0 && s.bodies != nil {
			if b, err := s.bodies.Open(r.BodyEnc); err == nil {
				text = b
			}
		}
		text = strings.Join(strings.Fields(text), " ")
		switch {
		case text == "" && r.Voice:
			text = i18n.T(lang, "email.digest.voice", nil)
		case text == "" && r.Media:
			text = i18n.T(lang, "email.digest.photos", nil)
		case text == "":
			continue
		}
		out = append(out, i18n.T(lang, lineKey, map[string]string{
			"name": engFirstName(r.Author), "where": deref(r.Where), "text": cutRunes(text, digestText)}))
	}
	return out, len(rows), nil
}

// digestToken signs the stop link for a user.
func (s *Server) digestToken(userID uuid.UUID) string {
	m := hmac.New(sha256.New, s.cfg.SigningKey)
	m.Write([]byte("kinframe email digest stop:" + userID.String()))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

func (s *Server) digestStopURL(userID uuid.UUID) string {
	return s.cfg.PublicURL + "/v1/email-digest/stop?" + url.Values{"u": {userID.String()}, "t": {s.digestToken(userID)}}.Encode()
}

// sendDigests mails the digests that are due.
func (s *Server) sendDigests(ctx context.Context, now time.Time) {
	if !s.digestAvailable() {
		return
	}
	rows, err := engRows[engID](s.st.Q().Query(ctx, `SELECT user_id AS id FROM reminder_preferences WHERE email_digest <> 'off' ORDER BY user_id`))
	if err != nil {
		return
	}
	for _, r := range rows {
		p, err := s.st.GetReminderPrefs(ctx, s.st.Q(), r.ID)
		if err != nil || p.DeletedAt != nil || p.EmailDigest == "off" {
			continue
		}
		loc := userZone(p.Timezone)
		weekly := p.EmailDigest == "weekly"
		if !digestDue(now, loc, weekly, p.EmailDigestSentAt) {
			continue
		}
		if err := s.sendDigest(ctx, p, weekly, now); err != nil {
			s.log.Warn("email digest", "user", p.UserID, "err", err)
		}
	}
}

func (s *Server) sendDigest(ctx context.Context, p *store.ReminderPrefs, weekly bool, now time.Time) error {
	since := now.Add(-7 * 24 * time.Hour)
	if !weekly {
		since = now.Add(-24 * time.Hour)
	}
	if p.EmailDigestSentAt != nil {
		since = *p.EmailDigestSentAt
	}
	lang := i18n.Pick(deref(p.Locale))
	comments, nc, err := s.digestLines(ctx, digestCommentsSQL, "email.digest.comment_line", p.UserID, since, lang)
	if err != nil {
		return err
	}
	messages, nm, err := s.digestLines(ctx, digestMessagesSQL, "email.digest.message_line", p.UserID, since, lang)
	if err != nil {
		return err
	}
	mark := func() error {
		return s.st.UpdateReminderPrefs(ctx, s.st.Q(), p.UserID, store.ReminderPrefsPatch{DigestSentAt: &now})
	}
	if len(comments)+len(messages) == 0 || p.Email == nil || *p.Email == "" {
		return mark() // skipped when empty
	}
	msg := s.renderDigest(lang, weekly, p.UserID, comments, nc, messages, nm)
	msg.To = *p.Email
	sctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	if err := s.mailer.Send(sctx, msg); err != nil {
		return err
	}
	return mark()
}

func (s *Server) renderDigest(lang string, weekly bool, userID uuid.UUID, comments []string, nc int, messages []string, nm int) shop.Message {
	t := func(k string, a map[string]string) string { return i18n.T(lang, k, a) }
	server := map[string]string{"server": s.cfg.ServerName}
	subject, why := t("email.digest.subject_daily", server), t("email.digest.why_daily", server)
	if weekly {
		subject, why = t("email.digest.subject_weekly", server), t("email.digest.why_weekly", server)
	}
	var text, htm strings.Builder
	htm.WriteString(`<div style="font-family:sans-serif;font-size:15px;line-height:1.5;color:#222">`)
	intro := t("email.digest.intro", nil)
	text.WriteString(intro + "\n")
	htm.WriteString("<p>" + html.EscapeString(intro) + "</p>")
	section := func(head string, lines []string, total int) {
		if len(lines) == 0 {
			return
		}
		text.WriteString("\n" + head + "\n")
		htm.WriteString(`<h3 style="margin:16px 0 4px;font-size:16px">` + html.EscapeString(head) + "</h3><ul style=\"margin:0;padding-left:20px\">")
		for _, ln := range lines {
			text.WriteString("- " + ln + "\n")
			htm.WriteString("<li>" + html.EscapeString(ln) + "</li>")
		}
		if total > len(lines) {
			more := t("email.digest.more", map[string]string{"count": strconv.Itoa(total - len(lines))})
			text.WriteString("  " + more + "\n")
			htm.WriteString(`<li style="list-style:none;color:#555">` + html.EscapeString(more) + "</li>")
		}
		htm.WriteString("</ul>")
	}
	section(t("email.digest.comments", nil), comments, nc)
	section(t("email.digest.messages", nil), messages, nm)
	open := t("email.digest.open", server)
	web := s.cfg.PublicURL
	text.WriteString("\n" + open + ": " + web + "\n")
	htm.WriteString(`<p style="margin:20px 0"><a href="` + html.EscapeString(web) + `" style="display:inline-block;padding:10px 16px;border-radius:6px;` +
		`background:#2f6f4f;color:#fff;text-decoration:none">` + html.EscapeString(open) + "</a></p>")
	// The app promotion footer of every outgoing mail (email-inbox.md §10.4).
	link := web
	if ios := s.cfg.AppVersions["ios"].StoreURL; ios != "" {
		link = ios
	} else if android := s.cfg.AppVersions["android"].StoreURL; android != "" {
		link = android
	}
	footer := t("email.app.footer", map[string]string{"link": link})
	text.WriteString(footer + "\n")
	pre, _, _ := strings.Cut(t("email.app.footer", map[string]string{"link": "\x00"}), "\x00")
	htm.WriteString(`<p style="color:#555">` + html.EscapeString(pre) + `<a href="` + html.EscapeString(link) + `">` + html.EscapeString(link) + "</a></p>")
	stop := s.digestStopURL(userID)
	stopLabel := t("email.digest.stop", nil)
	text.WriteString("\n-- \n" + why + "\n" + stopLabel + ": " + stop + "\n")
	htm.WriteString(`<p style="color:#888;font-size:12px">` + html.EscapeString(why) + ` <a href="` + html.EscapeString(stop) + `" style="color:#888">` +
		html.EscapeString(stopLabel) + "</a></p></div>")
	return shop.Message{Subject: subject, Text: text.String(), HTML: htm.String()}
}

// digestStop serves the "stop these emails" link: GET shows a confirmation with one button (mail scanners
// follow links, so GET changes nothing), POST turns the digest off.
func (s *Server) digestStop(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	if r.Method == http.MethodPost {
		_ = r.ParseForm()
		q = r.Form
	}
	lang := i18n.Pick(r.Header.Get("Accept-Language"))
	page := func(status int, title, body, button string) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.WriteHeader(status)
		var b strings.Builder
		b.WriteString(`<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">`)
		b.WriteString("<title>" + html.EscapeString(title) + `</title></head><body style="font-family:sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem;line-height:1.5">`)
		b.WriteString("<h1 style=\"font-size:1.4rem\">" + html.EscapeString(title) + "</h1><p>" + html.EscapeString(body) + "</p>")
		if button != "" {
			b.WriteString(`<form method="post"><input type="hidden" name="u" value="` + html.EscapeString(q.Get("u")) + `">` +
				`<input type="hidden" name="t" value="` + html.EscapeString(q.Get("t")) + `">` +
				`<button type="submit" style="padding:10px 16px;font-size:1rem">` + html.EscapeString(button) + "</button></form>")
		}
		b.WriteString("</body></html>")
		_, _ = w.Write([]byte(b.String()))
	}
	uid, err := uuid.Parse(q.Get("u"))
	if err != nil || subtle.ConstantTimeCompare([]byte(q.Get("t")), []byte(s.digestToken(uid))) != 1 {
		page(http.StatusNotFound, i18n.T(lang, "email.digest.stop_title", nil), i18n.T(lang, "email.digest.stop_invalid", nil), "")
		return
	}
	if p, err := s.st.GetReminderPrefs(r.Context(), s.st.Q(), uid); err == nil && p.Locale != nil {
		lang = i18n.Pick(*p.Locale, r.Header.Get("Accept-Language"))
	}
	title := i18n.T(lang, "email.digest.stop_title", nil)
	if r.Method != http.MethodPost {
		page(http.StatusOK, title, i18n.T(lang, "email.digest.stop_text", map[string]string{"server": s.cfg.ServerName}), i18n.T(lang, "email.digest.stop", nil))
		return
	}
	off := "off"
	if err := s.st.UpdateReminderPrefs(r.Context(), s.st.Q(), uid, store.ReminderPrefsPatch{EmailDigest: &off}); err != nil {
		http.Error(w, "try again later", http.StatusInternalServerError)
		return
	}
	page(http.StatusOK, title, i18n.T(lang, "email.digest.stopped", nil), "")
}
