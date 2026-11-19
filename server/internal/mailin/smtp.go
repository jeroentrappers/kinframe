package mailin

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/big"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/emersion/go-smtp"
	proxyproto "github.com/pires/go-proxyproto"

	"github.com/jeroentrappers/kinframe/server/internal/ratelimit"
)

// RcptStatus is a recipient lookup's answer at RCPT time.
type RcptStatus int

const (
	RcptUnknown RcptStatus = iota // or retired: 550 5.1.1
	RcptActive
	RcptPaused // 550 5.2.1
)

// Envelope is an accepted message's SMTP envelope.
type Envelope struct {
	From     string // MAIL FROM; "" for the null sender
	To       []string
	ClientIP net.IP
	Helo     string
}

// Backend is what the receiver needs from the server: recipient lookups and
// the queue. Enqueue must have committed the message (queue row, file) when
// it returns nil: the receiver answers 250 only then.
type Backend interface {
	CheckRecipient(ctx context.Context, localPart string) RcptStatus
	Enqueue(ctx context.Context, env Envelope, spoolPath string, size int64, auth *AuthResult) (string, error)
	MaxMessageBytes(ctx context.Context) int64
}

// SMTPConfig configures the receiver (§6.2, §12.1).
type SMTPConfig struct {
	Listen        string
	Hostname      string // greeting and EHLO name
	Domain        string // the inbox domain
	CertFile      string
	KeyFile       string
	RequireTLS    bool
	ProxyProtocol bool
	MaxSessions   int
	// Postmaster: postmaster@ and abuse@ are accepted (forwarded by the backend).
	Postmaster bool
	SpoolDir   string
	Resolver   Resolver
	Log        *slog.Logger
	// Timeouts; zero means the spec's values (5 min per command, 30 min per session).
	CommandTimeout time.Duration
	SessionTimeout time.Duration
}

// LocalPart normalises a recipient's local part: lower case, "+tag" removed.
func LocalPart(addr string) (local, domain string, ok bool) {
	addr = strings.Trim(strings.TrimSpace(addr), "<>")
	at := strings.LastIndexByte(addr, '@')
	if at <= 0 || at == len(addr)-1 {
		return "", "", false
	}
	local = strings.ToLower(addr[:at])
	if i := strings.IndexByte(local, '+'); i > 0 {
		local = local[:i]
	}
	return local, strings.ToLower(strings.TrimSuffix(addr[at+1:], ".")), true
}

// IsPostmaster: the two mailboxes RFC 5321 expects (§6.5).
func IsPostmaster(local string) bool { return local == "postmaster" || local == "abuse" }

// SMTPServer is the built-in receiver: no AUTH, no relaying, ever.
type SMTPServer struct {
	cfg     SMTPConfig
	backend Backend
	srv     *smtp.Server
	certs   *certReloader
	limits  *connLimits
	warned  atomic.Bool
}

func NewSMTPServer(cfg SMTPConfig, b Backend) (*SMTPServer, error) {
	if cfg.Hostname == "" {
		cfg.Hostname = cfg.Domain
	}
	if cfg.MaxSessions <= 0 {
		cfg.MaxSessions = 100
	}
	if cfg.CommandTimeout <= 0 {
		cfg.CommandTimeout = 5 * time.Minute
	}
	if cfg.SessionTimeout <= 0 {
		cfg.SessionTimeout = 30 * time.Minute
	}
	if cfg.Resolver == nil {
		cfg.Resolver = net.DefaultResolver
	}
	if cfg.Log == nil {
		cfg.Log = slog.Default()
	}
	s := &SMTPServer{cfg: cfg, backend: b, limits: newConnLimits(cfg.MaxSessions)}
	srv := smtp.NewServer(s)
	srv.Domain = cfg.Hostname
	srv.MaxRecipients = 20
	srv.MaxMessageBytes = b.MaxMessageBytes(context.Background())
	srv.ReadTimeout = cfg.CommandTimeout
	srv.WriteTimeout = cfg.CommandTimeout
	srv.EnableSMTPUTF8 = true
	srv.ErrorLog = slogLogger{cfg.Log}
	var getCert func(*tls.ClientHelloInfo) (*tls.Certificate, error)
	if cfg.CertFile != "" && cfg.KeyFile != "" {
		cr, err := newCertReloader(cfg.CertFile, cfg.KeyFile)
		if err != nil {
			return nil, err
		}
		s.certs = cr
		getCert = cr.get
	} else {
		// No certificate configured: offer STARTTLS with a self-signed one. Sending servers use
		// opportunistic TLS and accept it, so mail is still encrypted on the way in.
		c, err := selfSignedCert(cfg.Hostname)
		if err != nil {
			return nil, err
		}
		getCert = func(*tls.ClientHelloInfo) (*tls.Certificate, error) { return c, nil }
	}
	{
		srv.TLSConfig = &tls.Config{
			MinVersion:     tls.VersionTLS12,
			GetCertificate: getCert,
			GetConfigForClient: func(h *tls.ClientHelloInfo) (*tls.Config, error) {
				_ = h.Conn.SetDeadline(time.Now().Add(10 * time.Second)) // handshake timeout
				return nil, nil
			},
		}
	}
	s.srv = srv
	return s, nil
}

// Serve accepts connections on l until Close.
func (s *SMTPServer) Serve(l net.Listener) error {
	if s.cfg.ProxyProtocol {
		l = &proxyproto.Listener{Listener: l, ReadHeaderTimeout: 10 * time.Second,
			ConnPolicy: func(proxyproto.ConnPolicyOptions) (proxyproto.Policy, error) { return proxyproto.REQUIRE, nil }}
	}
	return s.srv.Serve(&limitListener{Listener: l, s: s})
}

// Close stops the receiver.
func (s *SMTPServer) Close() error { return s.srv.Close() }

// NewSession implements smtp.Backend (called at EHLO/HELO).
func (s *SMTPServer) NewSession(c *smtp.Conn) (smtp.Session, error) {
	lc, _ := underlying(c.Conn())
	var ip net.IP
	if a, ok := c.Conn().RemoteAddr().(*net.TCPAddr); ok {
		ip = a.IP
	}
	_, isTLS := c.TLSConnectionState()
	return &session{s: s, conn: lc, ip: ip, helo: c.Hostname(), tls: isTLS}, nil
}

type session struct {
	s       *SMTPServer
	conn    *limitConn
	ip      net.IP
	helo    string
	tls     bool
	from    string
	hasFrom bool
	to      []string
}

func smtpErr(code int, enh smtp.EnhancedCode, msg string) *smtp.SMTPError {
	return &smtp.SMTPError{Code: code, EnhancedCode: enh, Message: msg}
}

func (ss *session) Reset() { ss.from, ss.hasFrom, ss.to = "", false, nil }

func (ss *session) Logout() error { return nil }

func (ss *session) Mail(from string, opts *smtp.MailOptions) error {
	if ss.s.cfg.RequireTLS && !ss.tls {
		return smtpErr(530, smtp.EnhancedCode{5, 7, 0}, "Must issue a STARTTLS command first")
	}
	if max := ss.s.backend.MaxMessageBytes(context.Background()); opts != nil && max > 0 && opts.Size > max {
		return smtpErr(552, smtp.EnhancedCode{5, 3, 4}, "Message too big")
	}
	if ok, _ := ss.s.limits.mails.Allow(ipKey(ss.ip)); !ok {
		return smtpErr(421, smtp.EnhancedCode{4, 7, 0}, "Too many messages from your address, try again later")
	}
	ss.from, ss.hasFrom = from, true
	return nil
}

func (ss *session) Rcpt(to string, _ *smtp.RcptOptions) error {
	local, domain, ok := LocalPart(to)
	if !ok {
		return smtpErr(501, smtp.EnhancedCode{5, 1, 3}, "Bad recipient address syntax")
	}
	if domain != strings.ToLower(ss.s.cfg.Domain) {
		return ss.refuse(smtpErr(550, smtp.EnhancedCode{5, 7, 1}, "Relaying denied"))
	}
	if IsPostmaster(local) && ss.s.cfg.Postmaster {
		ss.to = append(ss.to, local+"@"+domain)
		return nil
	}
	switch ss.s.backend.CheckRecipient(context.Background(), local) {
	case RcptActive:
		ss.to = append(ss.to, local+"@"+domain)
		return nil
	case RcptPaused:
		return ss.refuse(smtpErr(550, smtp.EnhancedCode{5, 2, 1}, "This address is paused"))
	}
	return ss.refuse(smtpErr(550, smtp.EnhancedCode{5, 1, 1}, "No such address here"))
}

// refuse counts a refused recipient: 5 in a connection, or 20 an hour from
// an address, end it (address guessing).
func (ss *session) refuse(e *smtp.SMTPError) error {
	n := 0
	if ss.conn != nil {
		n = int(ss.conn.refused.Add(1))
	}
	key := ipKey(ss.ip)
	if ok, _ := ss.s.limits.refused.Allow(key); !ok {
		ss.s.limits.block(key, time.Hour)
		n = 5
	}
	if n >= 5 {
		if ss.conn != nil {
			ss.conn.dying.Store(true)
		}
		return smtpErr(421, smtp.EnhancedCode{4, 7, 0}, "Too many unknown recipients, closing connection")
	}
	return e
}

func (ss *session) Data(r io.Reader) error {
	if !ss.hasFrom || len(ss.to) == 0 {
		return smtpErr(503, smtp.EnhancedCode{5, 5, 1}, "Missing MAIL or RCPT")
	}
	ctx := context.Background()
	max := ss.s.backend.MaxMessageBytes(ctx)
	f, err := os.CreateTemp(ss.s.cfg.SpoolDir, "smtp-*.eml")
	if err != nil {
		return smtpErr(451, smtp.EnhancedCode{4, 3, 0}, "Temporary local problem, try again later")
	}
	path := f.Name()
	keep := false
	defer func() {
		if !keep {
			_ = os.Remove(path)
		}
	}()
	n, err := io.Copy(f, io.LimitReader(r, max+1))
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if errors.Is(err, smtp.ErrDataTooLarge) || n > max {
		return smtpErr(552, smtp.EnhancedCode{5, 3, 4}, "Message too big")
	}
	if err != nil {
		return smtpErr(451, smtp.EnhancedCode{4, 3, 0}, "Temporary local problem, try again later")
	}
	rf, err := os.Open(path)
	if err != nil {
		return smtpErr(451, smtp.EnhancedCode{4, 3, 0}, "Temporary local problem, try again later")
	}
	defer rf.Close()
	if !headerFits(rf) {
		return smtpErr(552, smtp.EnhancedCode{5, 3, 4}, "Message headers too big")
	}
	ip := ss.ip
	if PrivateIP(ip) {
		if ss.s.warned.CompareAndSwap(false, true) {
			ss.s.cfg.Log.Warn("email: SMTP client address is private, so SPF is skipped (DKIM still works); see docs/spec/email-inbox.md §14.2",
				"client", ip.String())
		}
		ip = nil
	}
	auth := Authenticate(ctx, ss.s.cfg.Resolver, AuthInput{ClientIP: ip, Helo: ss.helo, MailFrom: ss.from}, rf)
	switch {
	case auth.RejectDMARC:
		return smtpErr(550, smtp.EnhancedCode{5, 7, 1}, "Rejected by the sender domain's DMARC policy")
	case auth.TempError:
		return smtpErr(451, smtp.EnhancedCode{4, 7, 0}, "Temporary authentication failure, try again later")
	}
	id, err := ss.s.backend.Enqueue(ctx, Envelope{From: ss.from, To: ss.to, ClientIP: ss.ip, Helo: ss.helo}, path, n, &auth)
	if err != nil {
		ss.s.cfg.Log.Error("email: queue message", "err", err)
		return smtpErr(451, smtp.EnhancedCode{4, 3, 0}, "Temporary local problem, try again later")
	}
	keep = true
	return smtpErr(250, smtp.EnhancedCode{2, 0, 0}, "Queued as "+id)
}

// headerFits reports whether the header block ends within MaxHeaderBytes.
func headerFits(f *os.File) bool {
	buf := make([]byte, MaxHeaderBytes+4)
	n, _ := io.ReadFull(f, buf)
	_, _ = f.Seek(0, io.SeekStart)
	if n <= MaxHeaderBytes {
		return true
	}
	end := headerEnd(buf[:n])
	return end >= 0 && end <= MaxHeaderBytes
}

// ---------------------------------------------------------------- connection limits (§12.2)

type connLimits struct {
	mu       sync.Mutex
	max      int
	sessions int
	perIP    map[string]int
	blocked  map[string]time.Time
	conns    *ratelimit.Limiter // 30 connections a minute per address
	mails    *ratelimit.Limiter // 200 mails an hour per address
	refused  *ratelimit.Limiter // 20 refused recipients an hour per address
}

func newConnLimits(max int) *connLimits {
	return &connLimits{max: max, perIP: map[string]int{}, blocked: map[string]time.Time{},
		conns: ratelimit.New(30, time.Minute), mails: ratelimit.New(200, time.Hour), refused: ratelimit.New(20, time.Hour)}
}

func (l *connLimits) block(key string, d time.Duration) {
	l.mu.Lock()
	l.blocked[key] = time.Now().Add(d)
	l.mu.Unlock()
}

// admit reserves a session slot for key, or says why not.
func (l *connLimits) admit(key string) string {
	l.mu.Lock()
	defer l.mu.Unlock()
	if until, ok := l.blocked[key]; ok {
		if time.Now().Before(until) {
			return "Too many unknown recipients from your address, try again later"
		}
		delete(l.blocked, key)
	}
	if l.sessions >= l.max {
		return "Too busy, try again later"
	}
	if l.perIP[key] >= 5 {
		return "Too many connections from your address"
	}
	if ok, _ := l.conns.Allow(key); !ok {
		return "Too many connections from your address, try again later"
	}
	l.sessions++
	l.perIP[key]++
	return ""
}

func (l *connLimits) release(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.sessions--
	if l.perIP[key]--; l.perIP[key] <= 0 {
		delete(l.perIP, key)
	}
}

// ipKey groups IPv6 addresses per /64.
func ipKey(ip net.IP) string {
	if ip == nil {
		return ""
	}
	if v4 := ip.To4(); v4 != nil {
		return v4.String()
	}
	return ip.Mask(net.CIDRMask(64, 128)).String() + "/64"
}

type limitListener struct {
	net.Listener
	s *SMTPServer
}

func (l *limitListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	return &limitConn{Conn: c, s: l.s, end: time.Now().Add(l.s.cfg.SessionTimeout)}, nil
}

// limitConn admits the connection on its first write (the greeting, after a
// PROXY header was read), caps deadlines at the session timeout and ends the
// session after a 421.
type limitConn struct {
	net.Conn
	s        *SMTPServer
	end      time.Time
	once     sync.Once
	admitted bool
	key      string
	refused  atomic.Int32
	dying    atomic.Bool
	closed   atomic.Bool
}

func underlying(c net.Conn) (*limitConn, bool) {
	for {
		switch v := c.(type) {
		case *limitConn:
			return v, true
		case *tls.Conn:
			c = v.NetConn()
		default:
			return nil, false
		}
	}
}

func (c *limitConn) Write(b []byte) (int, error) {
	refusal := ""
	c.once.Do(func() {
		var ip net.IP
		if a, ok := c.Conn.RemoteAddr().(*net.TCPAddr); ok {
			ip = a.IP
		}
		c.key = ipKey(ip)
		if refusal = c.s.limits.admit(c.key); refusal == "" {
			c.admitted = true
		}
	})
	if refusal != "" {
		_, _ = c.Conn.Write([]byte("421 4.7.0 " + refusal + "\r\n"))
		c.dying.Store(true)
		_ = c.Conn.Close()
		return 0, net.ErrClosed
	}
	n, err := c.Conn.Write(b)
	if c.dying.Load() && bytes.HasPrefix(b, []byte("421")) {
		_ = c.Close()
	}
	return n, err
}

func (c *limitConn) Read(b []byte) (int, error) {
	if c.dying.Load() {
		return 0, io.EOF
	}
	return c.Conn.Read(b)
}

func (c *limitConn) SetReadDeadline(t time.Time) error {
	if t.IsZero() || t.After(c.end) {
		t = c.end
	}
	return c.Conn.SetReadDeadline(t)
}

func (c *limitConn) SetDeadline(t time.Time) error {
	if t.IsZero() || t.After(c.end) {
		t = c.end
	}
	return c.Conn.SetDeadline(t)
}

func (c *limitConn) Close() error {
	if c.closed.CompareAndSwap(false, true) && c.admitted {
		c.s.limits.release(c.key)
	}
	return c.Conn.Close()
}

// ---------------------------------------------------------------- TLS certificate, re-read on change

type certReloader struct {
	cert, key string
	mu        sync.Mutex
	mod       time.Time
	checked   time.Time
	cur       *tls.Certificate
}

func newCertReloader(cert, key string) (*certReloader, error) {
	r := &certReloader{cert: cert, key: key}
	if err := r.load(); err != nil {
		return nil, fmt.Errorf("email TLS certificate: %w", err)
	}
	return r, nil
}

func (r *certReloader) load() error {
	st, err := os.Stat(r.cert)
	if err != nil {
		return err
	}
	c, err := tls.LoadX509KeyPair(r.cert, r.key)
	if err != nil {
		return err
	}
	r.cur, r.mod = &c, st.ModTime()
	return nil
}

func (r *certReloader) get(*tls.ClientHelloInfo) (*tls.Certificate, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if time.Since(r.checked) > time.Minute {
		r.checked = time.Now()
		if st, err := os.Stat(r.cert); err == nil && !st.ModTime().Equal(r.mod) {
			_ = r.load() // keep the old certificate if the new pair doesn't load (yet)
		}
	}
	return r.cur, nil
}

type slogLogger struct{ l *slog.Logger }

func (s slogLogger) Printf(format string, v ...any) { s.l.Debug("smtp: " + fmt.Sprintf(format, v...)) }
func (s slogLogger) Println(v ...any)               { s.l.Debug("smtp: " + fmt.Sprintln(v...)) }

// selfSignedCert makes a throwaway ECDSA certificate for host, valid for a year.
func selfSignedCert(host string) (*tls.Certificate, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	if err != nil {
		return nil, err
	}
	now := time.Now()
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: host},
		DNSNames:     []string{host},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.AddDate(1, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, fmt.Errorf("self-signed email certificate: %w", err)
	}
	return &tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, nil
}
