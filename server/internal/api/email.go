package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/mailin"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/ratelimit"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Email to frame (docs/spec/email-inbox.md): inbox addresses for frames and
// feeds. Mail arrives through the built-in SMTP receiver or the signed
// webhook, is queued (email_inbound + a spool file) and processed by
// RunEmailInbox; see email_process.go, email_deliver.go and email_reply.go.

type emailInbox struct {
	cfg      config.EmailConfig
	spool    string
	verifier *mailin.Verifier

	resolverMu sync.RWMutex
	resolver   mailin.Resolver

	wake chan struct{}

	smtpMu   sync.Mutex
	smtpAddr string

	createUser *ratelimit.Limiter // creating addresses: 30/h per user
	regenAddr  *ratelimit.Limiter // regenerating: 10/h per address
	senderUser *ratelimit.Limiter // adding senders: 120/h per user
	webhook    *ratelimit.Limiter // the webhook: 600/min in total
	replyHour  *ratelimit.Limiter // replies: KINFRAME_EMAIL_MAX_REPLIES_PER_HOUR in total
	pendingDay *ratelimit.Limiter // pending-sender pushes: 10/day per address
}

func (s *Server) setupEmail() error {
	e := &emailInbox{
		cfg:        s.cfg.Email,
		spool:      filepath.Join(s.cfg.DataDir, "email-spool"),
		verifier:   &mailin.Verifier{Secrets: s.cfg.Email.WebhookSecrets},
		resolver:   mailin.NewResolver(s.cfg.Email.DNSServer),
		wake:       make(chan struct{}, 1),
		createUser: ratelimit.New(30, time.Hour),
		regenAddr:  ratelimit.New(10, time.Hour),
		senderUser: ratelimit.New(120, time.Hour),
		webhook:    ratelimit.New(600, time.Minute),
		replyHour:  ratelimit.New(max(1, s.cfg.Email.MaxRepliesPerHour), time.Hour),
		pendingDay: ratelimit.New(10, 24*time.Hour),
	}
	if e.cfg.HeldDays <= 0 {
		e.cfg.HeldDays = 14
	}
	if e.cfg.Enabled {
		if err := os.MkdirAll(e.spool, 0o750); err != nil {
			return fmt.Errorf("email spool: %w", err)
		}
	}
	s.em = e
	return nil
}

// emailOn: the feature is on (discovery email_inbox).
func (s *Server) emailOn() bool { return s.em != nil && s.em.cfg.Enabled && s.em.cfg.Domain != "" }

func (s *Server) emailDiscovery() (*bool, *string) {
	if !s.emailOn() {
		return ptr(false), nil
	}
	return ptr(true), ptr(s.em.cfg.Domain)
}

// SetEmailResolver replaces the DNS used for SPF/DKIM/DMARC (tests).
func (s *Server) SetEmailResolver(r mailin.Resolver) {
	s.em.resolverMu.Lock()
	s.em.resolver = r
	s.em.resolverMu.Unlock()
}

func (s *Server) emailResolver() mailin.Resolver {
	s.em.resolverMu.RLock()
	defer s.em.resolverMu.RUnlock()
	return s.em.resolver
}

// EmailSMTPAddr is where the SMTP receiver listens ("" while it doesn't).
func (s *Server) EmailSMTPAddr() string {
	s.em.smtpMu.Lock()
	defer s.em.smtpMu.Unlock()
	return s.em.smtpAddr
}

func (s *Server) wakeEmail() {
	select {
	case s.em.wake <- struct{}{}:
	default:
	}
}

func emailLimitsJSON(l config.Limits, out *gen.Limits) {
	out.MaxEmailAddressesPerTarget = ptr(l.MaxEmailAddressesPerTarget)
	out.MaxEmailSendersPerAddress = ptr(l.MaxEmailSendersPerAddress)
	out.MaxEmailMessageBytes = ptr(int(l.MaxEmailMessageBytes))
	out.MaxEmailMessagesPerAddressDay = ptr(l.MaxEmailMessagesPerAddressDay)
	out.MaxEmailHeldMessages = ptr(l.MaxEmailHeldMessages)
}

// setEmailLimits keeps the current email limits unless the body sets them.
func setEmailLimits(l *config.Limits, cur config.Limits, b *gen.Limits) {
	l.MaxEmailAddressesPerTarget, l.MaxEmailSendersPerAddress = cur.MaxEmailAddressesPerTarget, cur.MaxEmailSendersPerAddress
	l.MaxEmailMessageBytes, l.MaxEmailMessagesPerAddressDay = cur.MaxEmailMessageBytes, cur.MaxEmailMessagesPerAddressDay
	l.MaxEmailHeldMessages = cur.MaxEmailHeldMessages
	if b == nil {
		return
	}
	pos := func(dst *int, src *int) {
		if src != nil && *src > 0 {
			*dst = *src
		}
	}
	pos(&l.MaxEmailAddressesPerTarget, b.MaxEmailAddressesPerTarget)
	pos(&l.MaxEmailSendersPerAddress, b.MaxEmailSendersPerAddress)
	pos(&l.MaxEmailMessagesPerAddressDay, b.MaxEmailMessagesPerAddressDay)
	pos(&l.MaxEmailHeldMessages, b.MaxEmailHeldMessages)
	if b.MaxEmailMessageBytes != nil && *b.MaxEmailMessageBytes > 0 {
		l.MaxEmailMessageBytes = int64(*b.MaxEmailMessageBytes)
	}
}

// ---------------------------------------------------------------- the SMTP receiver's backend

type emailBackend struct{ s *Server }

func (b emailBackend) MaxMessageBytes(ctx context.Context) int64 {
	return b.s.Limits(ctx).MaxEmailMessageBytes
}

func (b emailBackend) CheckRecipient(ctx context.Context, local string) mailin.RcptStatus {
	return b.s.recipientStatus(ctx, local)
}

func (b emailBackend) Enqueue(ctx context.Context, env mailin.Envelope, path string, size int64, auth *mailin.AuthResult) (string, error) {
	raw, err := json.Marshal(auth)
	if err != nil {
		return "", err
	}
	m := &store.InboundMail{ID: store.NewID(), SpoolPath: path, Path: "smtp", EnvelopeTo: env.To, SizeBytes: size, Auth: raw}
	m.EnvelopeFrom = &env.From
	if env.ClientIP != nil {
		m.ClientIP = ptr(env.ClientIP.String())
	}
	if env.Helo != "" {
		m.Helo = &env.Helo
	}
	if err := b.s.st.InsertInbound(ctx, m); err != nil {
		return "", err
	}
	b.s.wakeEmail()
	return m.ID.String(), nil
}

// recipientStatus looks up a local part (lower case, +tag removed).
func (s *Server) recipientStatus(ctx context.Context, local string) mailin.RcptStatus {
	a, err := s.st.EmailAddressByLocalPart(ctx, s.st.Q(), local)
	if err != nil {
		return mailin.RcptUnknown
	}
	if a.Status == "paused" {
		return mailin.RcptPaused
	}
	return mailin.RcptActive
}

// RunEmailInbox runs the SMTP receiver (when configured) and processes the
// queue until ctx ends.
func (s *Server) RunEmailInbox(ctx context.Context) {
	if !s.emailOn() {
		return
	}
	log := s.log.With("component", "email")
	if s.em.cfg.SMTPListen != "" {
		if s.em.cfg.TLSCertFile == "" {
			log.Warn("email: no TLS certificate (KINFRAME_EMAIL_TLS_CERT_FILE): the SMTP receiver offers no STARTTLS")
		}
		srv, err := mailin.NewSMTPServer(mailin.SMTPConfig{
			Listen: s.em.cfg.SMTPListen, Hostname: s.em.cfg.SMTPHostname, Domain: s.em.cfg.Domain,
			CertFile: s.em.cfg.TLSCertFile, KeyFile: s.em.cfg.TLSKeyFile, RequireTLS: s.em.cfg.RequireTLS,
			ProxyProtocol: s.em.cfg.ProxyProtocol, MaxSessions: s.em.cfg.MaxSessions, Postmaster: s.em.cfg.Postmaster != "",
			SpoolDir: s.em.spool, Resolver: resolverFunc{s}, Log: log,
		}, emailBackend{s})
		if err != nil {
			log.Error("email: SMTP receiver", "err", err)
		} else if l, err := net.Listen("tcp", s.em.cfg.SMTPListen); err != nil {
			log.Error("email: SMTP receiver", "err", err)
		} else {
			s.em.smtpMu.Lock()
			s.em.smtpAddr = l.Addr().String()
			s.em.smtpMu.Unlock()
			log.Info("email: SMTP receiver listening", "addr", l.Addr().String(), "domain", s.em.cfg.Domain)
			go func() { _ = srv.Serve(l) }()
			defer srv.Close()
		}
	}
	tick := time.NewTicker(30 * time.Second)
	defer tick.Stop()
	lastMinute := time.Time{}
	for {
		for ctx.Err() == nil {
			done, err := s.processNextInbound(ctx)
			if err != nil && ctx.Err() == nil {
				log.Error("email: process", "err", err)
				break
			}
			if !done {
				break
			}
		}
		if time.Since(lastMinute) >= time.Minute {
			lastMinute = time.Now()
			s.emailMinutely(ctx)
		}
		select {
		case <-ctx.Done():
			return
		case <-s.em.wake:
		case <-tick.C:
		}
	}
}

// resolverFunc lets tests swap the resolver while the SMTP receiver runs.
type resolverFunc struct{ s *Server }

func (r resolverFunc) LookupTXT(ctx context.Context, n string) ([]string, error) {
	return r.s.emailResolver().LookupTXT(ctx, n)
}
func (r resolverFunc) LookupMX(ctx context.Context, n string) ([]*net.MX, error) {
	return r.s.emailResolver().LookupMX(ctx, n)
}
func (r resolverFunc) LookupIPAddr(ctx context.Context, h string) ([]net.IPAddr, error) {
	return r.s.emailResolver().LookupIPAddr(ctx, h)
}
func (r resolverFunc) LookupAddr(ctx context.Context, a string) ([]string, error) {
	return r.s.emailResolver().LookupAddr(ctx, a)
}

// ---------------------------------------------------------------- webhook (§6.3)

type inboundRecipientJSON struct {
	Address string `json:"address"`
	Outcome string `json:"outcome"`
}

func (s *Server) webhookGate(w http.ResponseWriter, r *http.Request) bool {
	if s.em == nil || len(s.em.cfg.WebhookSecrets) == 0 {
		problem.Write(w, r, problem.New(problem.NotFound, "the inbound email webhook is not set up on this server"))
		return false
	}
	if !s.emailOn() {
		problem.Write(w, r, problem.New(problem.FeatureDisabled, "email to frame is not turned on on this server"))
		return false
	}
	if ok, retry := s.em.webhook.Allow("all"); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		problem.Write(w, r, p)
		return false
	}
	return true
}

// recipientOutcome checks one webhook recipient: accepted, unknown_recipient or paused.
func (s *Server) recipientOutcome(ctx context.Context, addr string) (string, string) {
	local, domain, ok := mailin.LocalPart(addr)
	if !ok || domain != s.em.cfg.Domain {
		return strings.TrimSpace(addr), "unknown_recipient"
	}
	norm := local + "@" + domain
	if mailin.IsPostmaster(local) {
		if s.em.cfg.Postmaster != "" {
			return norm, "accepted"
		}
		return norm, "unknown_recipient"
	}
	switch s.recipientStatus(ctx, local) {
	case mailin.RcptActive:
		return norm, "accepted"
	case mailin.RcptPaused:
		return norm, "paused"
	}
	return norm, "unknown_recipient"
}

func (s *Server) receiveInboundEmail(w http.ResponseWriter, r *http.Request) {
	if !s.webhookGate(w, r) {
		return
	}
	ctx := r.Context()
	maxBytes := s.Limits(ctx).MaxEmailMessageBytes
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBytes+1))
	if err != nil {
		problem.Write(w, r, problem.New(problem.ValidationFailed, "malformed request body").WithStatus(http.StatusBadRequest))
		return
	}
	sr := mailin.SignedRequest{
		Timestamp: r.Header.Get("X-Kinframe-Timestamp"), EnvelopeTo: r.Header.Get("X-Kinframe-Envelope-To"),
		EnvelopeFrom: r.Header.Get("X-Kinframe-Envelope-From"), ClientIP: r.Header.Get("X-Kinframe-Client-Ip"),
		Helo: r.Header.Get("X-Kinframe-Helo"), BodySHA256: mailin.BodyHash(body),
	}
	if int64(len(body)) > maxBytes {
		problem.Write(w, r, problem.New(problem.UploadTooLarge, "the mail is larger than %d bytes", maxBytes))
		return
	}
	if err := s.em.verifier.Verify(sr, r.Header.Get("X-Kinframe-Signature")); err != nil {
		problem.Write(w, r, problem.New(problem.Unauthorized, "invalid webhook signature"))
		return
	}
	id := store.NewID()
	var rcpts []inboundRecipientJSON
	var accepted []string
	for _, a := range strings.Split(sr.EnvelopeTo, ",") {
		if strings.TrimSpace(a) == "" {
			continue
		}
		addr, outcome := s.recipientOutcome(ctx, a)
		rcpts = append(rcpts, inboundRecipientJSON{Address: addr, Outcome: outcome})
		if outcome == "accepted" {
			accepted = append(accepted, addr)
		}
	}
	if len(rcpts) == 0 {
		problem.Write(w, r, problem.Validation("X-Kinframe-Envelope-To", "required", "not a valid email address"))
		return
	}
	if len(accepted) > 0 {
		// Authentication now, so a DMARC reject is answered (and not queued); a temporary
		// DNS error leaves it to the worker, which retries.
		var auth *mailin.AuthResult
		res := s.authenticateRaw(ctx, body, sr.ClientIP, sr.Helo, sr.EnvelopeFrom)
		if !res.TempError {
			auth = &res
		}
		if auth != nil && auth.RejectDMARC {
			for i := range rcpts {
				if rcpts[i].Outcome == "accepted" {
					rcpts[i].Outcome = "rejected_dmarc"
				}
			}
			s.logRejectedDMARC(ctx, accepted, auth, body)
		} else if err := s.queueWebhookMail(ctx, id, body, sr, accepted, auth); err != nil {
			s.writeError(w, r, err)
			return
		}
	}
	Response{Status: http.StatusAccepted, Body: map[string]any{"id": id, "recipients": rcpts}}.write(w)
}

func (s *Server) queueWebhookMail(ctx context.Context, id uuid.UUID, body []byte, sr mailin.SignedRequest, to []string, auth *mailin.AuthResult) error {
	path := filepath.Join(s.em.spool, "webhook-"+id.String()+".eml")
	f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o640)
	if err != nil {
		return err
	}
	_, err = f.Write(body)
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		_ = os.Remove(path)
		return err
	}
	m := &store.InboundMail{ID: id, SpoolPath: path, Path: "webhook", EnvelopeTo: to, SizeBytes: int64(len(body)),
		EnvelopeFrom: &sr.EnvelopeFrom}
	if ip := net.ParseIP(strings.TrimSpace(sr.ClientIP)); ip != nil {
		m.ClientIP = ptr(ip.String())
	}
	if sr.Helo != "" {
		m.Helo = &sr.Helo
	}
	if auth != nil {
		if m.Auth, err = json.Marshal(auth); err != nil {
			return err
		}
	}
	if err := s.st.InsertInbound(ctx, m); err != nil {
		_ = os.Remove(path)
		return err
	}
	s.wakeEmail()
	return nil
}

func (s *Server) checkInboundEmailRecipients(w http.ResponseWriter, r *http.Request) {
	if !s.webhookGate(w, r) {
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 64<<10))
	if err != nil {
		problem.Write(w, r, problem.New(problem.ValidationFailed, "malformed request body").WithStatus(http.StatusBadRequest))
		return
	}
	sr := mailin.SignedRequest{Timestamp: r.Header.Get("X-Kinframe-Timestamp"), BodySHA256: mailin.BodyHash(body)}
	if err := s.em.verifier.Verify(sr, r.Header.Get("X-Kinframe-Signature")); err != nil {
		problem.Write(w, r, problem.New(problem.Unauthorized, "invalid webhook signature"))
		return
	}
	var req struct {
		Recipients []string `json:"recipients"`
	}
	if err := json.Unmarshal(body, &req); err != nil || len(req.Recipients) == 0 || len(req.Recipients) > 50 {
		problem.Write(w, r, problem.Validation("recipients", "required", "not a valid email address"))
		return
	}
	out := make([]inboundRecipientJSON, 0, len(req.Recipients))
	for _, a := range req.Recipients {
		_, outcome := s.recipientOutcome(r.Context(), a)
		out = append(out, inboundRecipientJSON{Address: a, Outcome: outcome})
	}
	ok(map[string]any{"recipients": out}).write(w)
}

// authenticateRaw runs §7 on an in-memory message (webhook), or reads the
// provider's verdict in the trust mode.
func (s *Server) authenticateRaw(ctx context.Context, body []byte, clientIP, helo, mailFrom string) mailin.AuthResult {
	rd := bytes.NewReader(body)
	if id := s.em.cfg.TrustAuthservID; id != "" {
		return mailin.TrustedResults(rd, id)
	}
	ip := net.ParseIP(strings.TrimSpace(clientIP))
	if mailin.PrivateIP(ip) {
		ip = nil
	}
	return mailin.Authenticate(ctx, s.emailResolver(), mailin.AuthInput{ClientIP: ip, Helo: helo, MailFrom: mailFrom}, rd)
}

// logRejectedDMARC records refused mail in the addresses' Recent mail.
func (s *Server) logRejectedDMARC(ctx context.Context, to []string, auth *mailin.AuthResult, body []byte) {
	key := mailin.BodyHash(body)
	for _, addr := range to {
		local, _, _ := mailin.LocalPart(addr)
		a, err := s.st.EmailAddressByLocalPart(ctx, s.st.Q(), local)
		if err != nil {
			continue
		}
		m := &store.EmailMessage{ID: store.NewID(), AddressID: a.ID, MessageKey: key, Outcome: "rejected_dmarc", ReceivedAt: s.now()}
		if auth.From != "" {
			m.SenderEmail = &auth.From
		}
		if err := s.st.InsertEmailMessage(ctx, s.st.Q(), m); err != nil {
			s.log.Warn("email: log message", "err", err)
		}
	}
}
