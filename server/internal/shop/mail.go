package shop

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"fmt"
	"mime"
	"mime/quotedprintable"
	"net"
	"net/mail"
	"net/smtp"
	"strconv"
	"strings"
	"time"
)

// SMTP is the outgoing mail server (KINFRAME_SMTP_*, the same settings the
// Logto bootstrap uses for sign-in codes).
type SMTP struct {
	Host     string
	Port     int
	User     string
	Password string
	From     string // "kinframe <noreply@example.org>" or a bare address
}

// Message is a plain email with a text and an HTML part.
type Message struct {
	To      string
	ReplyTo string
	Subject string
	Text    string
	HTML    string
}

// Mailer sends messages.
type Mailer interface {
	Send(ctx context.Context, m Message) error
}

// SMTPMailer sends through an SMTP server. Port 465 uses implicit TLS, 587
// requires STARTTLS; other ports (local relays, test sinks) use STARTTLS only
// when offered, like the Logto connector the bootstrap configures.
type SMTPMailer struct{ Cfg SMTP }

func (s *SMTPMailer) Send(ctx context.Context, m Message) error {
	from, err := mail.ParseAddress(s.Cfg.From)
	if err != nil {
		return fmt.Errorf("KINFRAME_SMTP_FROM: %w", err)
	}
	raw, err := Build(s.Cfg.From, m, time.Now())
	if err != nil {
		return err
	}
	addr := net.JoinHostPort(s.Cfg.Host, strconv.Itoa(s.Cfg.Port))
	d := net.Dialer{Timeout: 15 * time.Second}
	var conn net.Conn
	if s.Cfg.Port == 465 {
		conn, err = tls.DialWithDialer(&d, "tcp", addr, &tls.Config{ServerName: s.Cfg.Host})
	} else {
		conn, err = d.DialContext(ctx, "tcp", addr)
	}
	if err != nil {
		return err
	}
	if dl, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(dl)
	} else {
		_ = conn.SetDeadline(time.Now().Add(time.Minute))
	}
	c, err := smtp.NewClient(conn, s.Cfg.Host)
	if err != nil {
		conn.Close()
		return err
	}
	defer c.Close()
	if s.Cfg.Port != 465 {
		if ok, _ := c.Extension("STARTTLS"); ok {
			if err := c.StartTLS(&tls.Config{ServerName: s.Cfg.Host}); err != nil {
				return err
			}
		} else if s.Cfg.Port == 587 {
			return fmt.Errorf("smtp %s: STARTTLS not offered", addr)
		}
	}
	if s.Cfg.User != "" {
		if err := c.Auth(smtp.PlainAuth("", s.Cfg.User, s.Cfg.Password, s.Cfg.Host)); err != nil {
			return err
		}
	}
	if err := c.Mail(from.Address); err != nil {
		return err
	}
	to, err := mail.ParseAddress(m.To)
	if err != nil {
		return err
	}
	if err := c.Rcpt(to.Address); err != nil {
		return err
	}
	w, err := c.Data()
	if err != nil {
		return err
	}
	if _, err := w.Write(raw); err != nil {
		return err
	}
	if err := w.Close(); err != nil {
		return err
	}
	return c.Quit()
}

// Build renders m as a MIME message (multipart/alternative, UTF-8,
// quoted-printable).
func Build(from string, m Message, now time.Time) ([]byte, error) {
	fa, err := mail.ParseAddress(from)
	if err != nil {
		return nil, err
	}
	for _, s := range []string{m.To, m.ReplyTo, m.Subject} {
		if strings.ContainsAny(s, "\r\n") {
			return nil, fmt.Errorf("header contains a line break")
		}
	}
	rnd := make([]byte, 12)
	_, _ = rand.Read(rnd)
	boundary := "kf-" + hex.EncodeToString(rnd)
	domain := fa.Address[strings.LastIndexByte(fa.Address, '@')+1:]
	var b bytes.Buffer
	h := func(k, v string) { b.WriteString(k + ": " + v + "\r\n") }
	h("From", fa.String())
	h("To", m.To)
	if m.ReplyTo != "" {
		h("Reply-To", m.ReplyTo)
	}
	h("Subject", mime.QEncoding.Encode("utf-8", m.Subject))
	h("Date", now.Format(time.RFC1123Z))
	h("Message-ID", "<"+hex.EncodeToString(rnd)+"@"+domain+">")
	h("MIME-Version", "1.0")
	h("Auto-Submitted", "auto-generated")
	h("Content-Type", `multipart/alternative; boundary="`+boundary+`"`)
	b.WriteString("\r\n")
	part := func(ct, body string) error {
		b.WriteString("--" + boundary + "\r\n")
		b.WriteString("Content-Type: " + ct + "; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n")
		w := quotedprintable.NewWriter(&b)
		if _, err := w.Write([]byte(strings.ReplaceAll(body, "\n", "\r\n"))); err != nil {
			return err
		}
		if err := w.Close(); err != nil {
			return err
		}
		b.WriteString("\r\n")
		return nil
	}
	if err := part("text/plain", m.Text); err != nil {
		return nil, err
	}
	if err := part("text/html", m.HTML); err != nil {
		return nil, err
	}
	b.WriteString("--" + boundary + "--\r\n")
	return b.Bytes(), nil
}
