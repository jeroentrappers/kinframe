// Package smtptest is a tiny in-process SMTP sink for tests: it accepts every
// message (no TLS, no auth) and keeps it in memory.
package smtptest

import (
	"bufio"
	"io"
	"mime"
	"mime/multipart"
	"net"
	"net/mail"
	"strings"
	"sync"
	"testing"
	"time"
)

// Mail is a received message.
type Mail struct {
	From, To string
	Raw      string
	Subject  string
	Header   mail.Header
	Text     string
	HTML     string
}

type Server struct {
	Host string
	Port int
	l    net.Listener
	mu   sync.Mutex
	got  []Mail
}

// Start listens on a random loopback port until the test ends.
func Start(t testing.TB) *Server {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	a := l.Addr().(*net.TCPAddr)
	s := &Server{Host: "127.0.0.1", Port: a.Port, l: l}
	go s.serve()
	t.Cleanup(func() { l.Close() })
	return s
}

func (s *Server) serve() {
	for {
		c, err := s.l.Accept()
		if err != nil {
			return
		}
		go s.handle(c)
	}
}

func (s *Server) handle(c net.Conn) {
	defer c.Close()
	r := bufio.NewReader(c)
	w := func(line string) { _, _ = c.Write([]byte(line + "\r\n")) }
	w("220 smtptest ready")
	var m Mail
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return
		}
		cmd := strings.ToUpper(strings.TrimSpace(line))
		switch {
		case strings.HasPrefix(cmd, "EHLO"), strings.HasPrefix(cmd, "HELO"):
			w("250-smtptest")
			w("250 8BITMIME")
		case strings.HasPrefix(cmd, "MAIL FROM:"):
			m = Mail{From: strings.Trim(strings.TrimSpace(line)[10:], "<> ")}
			w("250 ok")
		case strings.HasPrefix(cmd, "RCPT TO:"):
			m.To = strings.Trim(strings.TrimSpace(line)[8:], "<> ")
			w("250 ok")
		case cmd == "DATA":
			w("354 go ahead")
			var b strings.Builder
			for {
				l, err := r.ReadString('\n')
				if err != nil {
					return
				}
				if l == ".\r\n" || l == ".\n" {
					break
				}
				b.WriteString(strings.TrimPrefix(l, "."))
			}
			m.Raw = b.String()
			parse(&m)
			s.mu.Lock()
			s.got = append(s.got, m)
			s.mu.Unlock()
			w("250 queued")
		case cmd == "QUIT":
			w("221 bye")
			return
		default:
			w("250 ok")
		}
	}
}

func parse(m *Mail) {
	msg, err := mail.ReadMessage(strings.NewReader(m.Raw))
	if err != nil {
		return
	}
	m.Header = msg.Header
	dec := new(mime.WordDecoder)
	m.Subject, _ = dec.DecodeHeader(msg.Header.Get("Subject"))
	_, params, err := mime.ParseMediaType(msg.Header.Get("Content-Type"))
	if err != nil {
		return
	}
	mr := multipart.NewReader(msg.Body, params["boundary"])
	for {
		p, err := mr.NextPart()
		if err != nil {
			return
		}
		body, _ := io.ReadAll(p) // multipart decodes quoted-printable itself
		text := strings.ReplaceAll(string(body), "\r\n", "\n")
		if strings.HasPrefix(p.Header.Get("Content-Type"), "text/html") {
			m.HTML = text
		} else {
			m.Text = text
		}
	}
}

// Mails returns the messages received so far.
func (s *Server) Mails() []Mail {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Mail(nil), s.got...)
}

// Wait waits until n messages arrived (or fails the test after 5 s).
func (s *Server) Wait(t testing.TB, n int) []Mail {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		ms := s.Mails()
		if len(ms) >= n {
			return ms
		}
		if time.Now().After(deadline) {
			t.Fatalf("got %d mails, want %d", len(ms), n)
		}
		time.Sleep(20 * time.Millisecond)
	}
}
