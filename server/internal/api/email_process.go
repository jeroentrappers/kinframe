package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/crash"
	"github.com/jeroentrappers/kinframe/server/internal/i18n"
	"github.com/jeroentrappers/kinframe/server/internal/mailin"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/shop"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Processing of one queued mail (§8): authentication (webhook mail), the MIME
// walk, media extraction, then per recipient address the sender decision and
// delivery, holding or dropping, and the replies.

const (
	minPhotoLongSide = 300         // smaller images are signatures, logos, pixels (§8.4 step 3)
	maxPhotoPixels   = 100_000_000 // decompression bombs
	parseBudget      = 60 * time.Second
	inboundLease     = 15 * time.Minute
	authRetries      = 3
)

// mailFile is a usable photo or video of a mail: a scratch file, or the media
// of held mail.
type mailFile struct {
	path     string     // scratch file (new mail)
	mediaID  *uuid.UUID // held mail: the processed media
	kind     media.Kind
	mime     string
	sha      string
	size     int64
	width    int
	height   int
	durMS    int
	filename string
}

type skipCounts map[string]int

func (k skipCounts) add(o skipCounts) {
	for r, n := range o {
		k[r] += n
	}
}

// mailJob is one mail being processed.
type mailJob struct {
	row       *store.InboundMail
	auth      mailin.AuthResult
	parsed    *mailin.Parsed
	files     []*mailFile
	skipped   skipCounts
	key       string
	caption   *string
	hint      *time.Time
	automatic bool
	now       time.Time
	log       *slog.Logger
}

func (s *Server) processNextInbound(ctx context.Context) (bool, error) {
	row, err := s.st.ClaimInbound(ctx, inboundLease)
	if err == store.ErrNotFound {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	s.processInbound(ctx, row)
	return true, nil
}

func (s *Server) processInbound(ctx context.Context, row *store.InboundMail) {
	log := s.log.With("component", "email", "message", row.ID, "path", row.Path)
	finish := func() {
		_ = os.Remove(row.SpoolPath)
		if err := s.st.DeleteInbound(ctx, row.ID); err != nil {
			log.Error("email: delete queue row", "err", err)
		}
	}
	f, err := os.Open(row.SpoolPath)
	if err != nil {
		log.Error("email: spool file missing, dropped", "err", err)
		finish()
		return
	}
	defer f.Close()
	j := &mailJob{row: row, now: s.now(), log: log, skipped: skipCounts{}}
	if len(row.Auth) > 0 && string(row.Auth) != "null" {
		_ = json.Unmarshal(row.Auth, &j.auth)
	} else {
		j.auth = s.authenticateFile(ctx, f, row)
		if j.auth.TempError && row.Attempts < authRetries {
			if err := s.st.RetryInbound(ctx, row.ID, j.now.Add(10*time.Minute)); err != nil {
				log.Error("email: retry", "err", err)
			}
			return
		}
		j.auth.TempError = false
	}
	parsed, err := s.parseWithBudget(ctx, f)
	if err != nil {
		log.Warn("email: message could not be processed", "err", err)
		s.logForAll(ctx, j, "failed")
		finish()
		return
	}
	defer parsed.Cleanup()
	j.parsed = parsed
	if parsed.FromOK {
		j.auth.From, j.auth.FromName = parsed.From, parsed.FromName
	} else {
		j.auth.Authenticated = false
	}
	nullSender := row.EnvelopeFrom != nil && *row.EnvelopeFrom == "" || row.EnvelopeFrom != nil && *row.EnvelopeFrom == "<>"
	j.automatic = parsed.Automatic(deref(row.EnvelopeFrom), nullSender)
	limits := s.Limits(ctx)
	s.extract(ctx, j, limits)
	j.key = messageKey(parsed, j.files)
	if c := mailin.Caption(parsed.Subject, captionText(parsed)); c != "" {
		j.caption = &c
	}
	if d := parsed.Date; d != nil && d.After(j.now.AddDate(-20, 0, 0)) && d.Before(j.now.Add(24*time.Hour)) {
		t := d.UTC()
		j.hint = &t
	} else {
		t := j.now.UTC()
		j.hint = &t
	}
	log.Info("email: received", "from_domain", mailin.Domain(j.auth.From), "from_hash", mailin.HashAddress(j.auth.From),
		"authenticated", j.auth.Authenticated, "spf", j.auth.SPF, "dkim", j.auth.DKIM, "dmarc", j.auth.DMARC,
		"bytes", row.SizeBytes, "files", len(j.files), "recipients", len(row.EnvelopeTo))
	s.route(ctx, j)
	finish()
}

func captionText(p *mailin.Parsed) string {
	if p.Text != "" {
		return p.Text
	}
	return p.HTMLText
}

func (s *Server) authenticateFile(ctx context.Context, f *os.File, row *store.InboundMail) mailin.AuthResult {
	if id := s.em.cfg.TrustAuthservID; id != "" && row.Path == "webhook" {
		return mailin.TrustedResults(f, id)
	}
	var ip net.IP
	if row.ClientIP != nil {
		ip = net.ParseIP(*row.ClientIP)
	}
	if mailin.PrivateIP(ip) {
		ip = nil
	}
	return mailin.Authenticate(ctx, s.emailResolver(), mailin.AuthInput{ClientIP: ip, Helo: deref(row.Helo), MailFrom: deref(row.EnvelopeFrom)}, f)
}

// parseWithBudget walks the message in its own goroutine with a time budget
// and panics recovered (§12.3).
func (s *Server) parseWithBudget(ctx context.Context, f *os.File) (*mailin.Parsed, error) {
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}
	limits := s.Limits(ctx)
	type result struct {
		p   *mailin.Parsed
		err error
	}
	ch := make(chan result, 1)
	abandoned := make(chan struct{})
	go func() {
		defer func() {
			if r := recover(); r != nil {
				s.log.Error("email: parser panic", "panic", r, "stack", string(debug.Stack()))
				crash.Report("email", r)
				ch <- result{err: fmt.Errorf("parser panic: %v", r)}
			}
		}()
		p, err := mailin.Parse(f, mailin.Limits{MaxDecoded: limits.MaxEmailMessageBytes, MaxFileBytes: limits.MaxUploadBytes,
			ScratchDir: s.scratchDir(), ScratchPrefix: "email-"})
		select {
		case <-abandoned:
			if p != nil {
				p.Cleanup()
			}
		case ch <- result{p, err}:
		}
	}()
	select {
	case r := <-ch:
		return r.p, r.err
	case <-time.After(parseBudget):
		close(abandoned)
		return nil, mailin.ErrTimeBudget
	}
}

func (s *Server) scratchDir() string {
	if s.scratch != "" {
		return s.scratch
	}
	return os.TempDir()
}

// extract checks every candidate like an upload (§8.4 steps 1-4) and drops
// duplicates within the mail.
func (s *Server) extract(ctx context.Context, j *mailJob, limits configLimits) {
	seen := map[string]bool{}
	for _, c := range j.parsed.Candidates {
		if c.TooLarge {
			j.skipped["too_large"]++
			continue
		}
		kind, mime, err := media.Sniff(c.Path)
		if err != nil {
			j.skipped["unsupported"]++
			continue
		}
		if c.Size > limits.MaxUploadBytes {
			j.skipped["too_large"]++
			continue
		}
		f := &mailFile{path: c.Path, kind: kind, mime: mime, sha: c.SHA256, size: c.Size, filename: c.Filename}
		switch kind {
		case media.Photo:
			info, err := s.tools.ImageHeader(ctx, c.Path)
			if err != nil {
				j.skipped["unsupported"]++
				continue
			}
			f.width, f.height = info.Size.W, info.Size.H
			if info.Size.Long() < minPhotoLongSide {
				j.skipped["too_small"]++
				continue
			}
			if int64(info.Size.W)*int64(info.Size.H) > maxPhotoPixels {
				j.skipped["too_large"]++
				continue
			}
		case media.Video:
			info, err := s.tools.ProbeVideo(ctx, c.Path)
			if err != nil {
				j.skipped["unsupported"]++
				continue
			}
			if info.DurationMS > limits.MaxVideoSeconds*1000 {
				j.skipped["too_long"]++
				continue
			}
			f.width, f.height, f.durMS = info.Size.W, info.Size.H, info.DurationMS
		default:
			j.skipped["unsupported"]++
			continue
		}
		if seen[c.SHA256] {
			j.skipped["duplicate"]++
			continue
		}
		seen[c.SHA256] = true
		j.files = append(j.files, f)
	}
	j.skipped["unsupported"] += j.parsed.Unsupported
	if j.skipped["unsupported"] == 0 {
		delete(j.skipped, "unsupported")
	}
	// Beyond 5 × max_items_per_send nothing more is taken (§8.4 step 6).
	if cap := 5 * limits.MaxItemsPerSend; len(j.files) > cap {
		j.skipped["over_limit"] += len(j.files) - cap
		j.files = j.files[:cap]
	}
}

// messageKey is the idempotency key of §8.6 step 1.
func messageKey(p *mailin.Parsed, files []*mailFile) string {
	h := sha256.New()
	if id := strings.ToLower(strings.Trim(strings.TrimSpace(p.MessageID), "<>")); id != "" {
		h.Write([]byte("mid\n" + id))
	} else {
		date := ""
		if p.Date != nil {
			date = p.Date.UTC().Format(time.RFC3339)
		}
		h.Write([]byte("hdr\n" + date + "\n" + p.From + "\n" + p.Subject))
		for _, f := range files {
			h.Write([]byte("\n" + f.sha))
		}
	}
	return hex.EncodeToString(h.Sum(nil))
}

// ---------------------------------------------------------------- routing per recipient

// emailDecision is what happens to a mail for one address (§8.2).
type emailDecision struct {
	a        *store.EmailAddress
	action   string // deliver, hold
	by       uuid.UUID
	account  *store.User        // matched account
	row      *store.EmailSender // the sender's row (allow-list, pending, ...)
	label    string
	onBehalf bool // sent by the owner for a non-account sender
	reason   string
	noReply  bool // unauthenticated: never answered
	msg      *store.EmailMessage
	skipped  skipCounts
	items    int
	photos   int
	videos   int
}

func (s *Server) route(ctx context.Context, j *mailJob) {
	limits := s.Limits(ctx)
	var deliver []*emailDecision
	seen := map[uuid.UUID]bool{}
	for _, to := range j.row.EnvelopeTo {
		local, domain, ok := mailin.LocalPart(to)
		if !ok || domain != s.em.cfg.Domain {
			continue
		}
		if mailin.IsPostmaster(local) {
			s.forwardPostmaster(ctx, j)
			continue
		}
		a, err := s.st.EmailAddressByLocalPart(ctx, s.st.Q(), local)
		if err != nil || a.Status != "active" {
			j.log.Info("email: recipient gone or paused, dropped")
			continue
		}
		if seen[a.ID] {
			continue
		}
		seen[a.ID] = true
		d := s.decide(ctx, j, a, limits)
		if d != nil && d.action == "deliver" {
			deliver = append(deliver, d)
		}
	}
	// §8.6 step 2: one send per (account, credit) across the mail's addresses.
	groups := map[string][]*emailDecision{}
	var order []string
	for _, d := range deliver {
		k := fmt.Sprintf("%s|%s|%v", d.by, d.label, d.onBehalf)
		if _, ok := groups[k]; !ok {
			order = append(order, k)
		}
		groups[k] = append(groups[k], d)
	}
	for _, k := range order {
		g := groups[k]
		if err := s.deliverGroup(ctx, g, j.files, j.caption, j.hint, limits); err != nil {
			j.log.Error("email: deliver", "err", err)
			for _, d := range g {
				s.logMessage(ctx, j, d.a, d, "failed")
			}
			continue
		}
		for _, d := range g {
			d.skipped.add(j.skipped)
			if d.items == 0 {
				// Everything was already there.
				s.logMessage(ctx, j, d.a, d, "duplicate")
				continue
			}
			s.logMessage(ctx, j, d.a, d, "delivered")
			_ = s.st.TouchEmailAddress(ctx, d.a.ID, j.now)
			if d.row != nil {
				d.row.LastMessageAt = &j.now
				_ = s.st.InTx(ctx, func(tx *store.Tx) error { return tx.SaveEmailSender(ctx, d.row) })
			}
			s.publishEmailAddress(ctx, d.a.ID)
			if !d.noReply {
				s.replyDelivered(ctx, d.a, d, j.auth.From, j.parsed.Subject, j.parsed.MessageID)
			}
		}
	}
}

// decide runs §8.1 steps 2-3 and §8.2 for one address. Drops, duplicates and
// holds are finished here; deliveries are returned for grouping.
func (s *Server) decide(ctx context.Context, j *mailJob, a *store.EmailAddress, limits configLimits) *emailDecision {
	d := &emailDecision{a: a, skipped: skipCounts{}}
	if n, err := s.st.CountEmailMessagesSince(ctx, a.ID, j.now.Add(-24*time.Hour)); err == nil && n >= limits.MaxEmailMessagesPerAddressDay {
		j.log.Info("email: address over its daily limit, dropped", "address", a.ID)
		s.logMessage(ctx, j, a, d, "rate_limited")
		return nil
	}
	if j.automatic {
		s.logMessage(ctx, j, a, d, "ignored")
		return nil
	}
	if seen, err := s.st.MessageKeySeen(ctx, s.st.Q(), a.ID, j.key); err == nil && seen {
		s.logMessage(ctx, j, a, d, "duplicate")
		return nil
	}
	if err := s.ensureEmailOwner(ctx, a); err != nil || a.OwnerUserID == nil {
		j.log.Error("email: address has no owner", "address", a.ID, "err", err)
		s.logMessage(ctx, j, a, d, "failed")
		return nil
	}
	from := j.auth.From
	claimedName := j.auth.FromName
	if from != "" {
		if row, err := s.st.EmailSenderByEmail(ctx, s.st.Q(), a.ID, from); err == nil {
			d.row = row
		}
	}
	switch {
	case !j.auth.Authenticated:
		d.noReply = true
		if a.AnyoneMaySend {
			d.action, d.by, d.onBehalf = "deliver", *a.OwnerUserID, true
			d.label = mailin.Label(claimedName, "", from)
		} else {
			d.action, d.reason = "hold", "unverified_sender"
		}
	case d.row != nil && d.row.State == "blocked":
		s.logMessage(ctx, j, a, d, "blocked")
		return nil
	default:
		if u := s.matchedAccount(ctx, from, a); u != nil {
			d.action, d.by, d.account = "deliver", u.ID, u
			d.label = mailin.Label(u.DisplayName, "", from)
		} else if d.row != nil && d.row.State == "allowed" || a.AnyoneMaySend {
			d.action, d.by, d.onBehalf = "deliver", *a.OwnerUserID, true
			d.label = mailin.Label(claimedName, deref(rowName(d.row)), from)
		} else {
			d.action, d.reason = "hold", "unknown_sender"
		}
	}
	if len(j.files) == 0 {
		outcome, kind := "no_media", mailin.ReplyNoMedia
		switch {
		case j.parsed.Encrypted:
			outcome, kind = "encrypted", mailin.ReplyEncrypted
		case j.skipped["too_large"]+j.skipped["too_long"] > 0 && j.skipped["too_small"] == 0:
			outcome, kind = "too_large", mailin.ReplyTooLarge
		}
		d.skipped.add(j.skipped)
		s.logMessage(ctx, j, a, d, outcome)
		if j.auth.Authenticated {
			s.replyFailure(ctx, a, d, kind, from, j.parsed.Subject, j.parsed.MessageID)
		}
		return nil
	}
	if d.action == "hold" {
		if err := s.holdMail(ctx, j, d, limits); err != nil {
			j.log.Error("email: hold", "err", err)
			s.logMessage(ctx, j, a, d, "failed")
		}
		return nil
	}
	return d
}

func rowName(r *store.EmailSender) *string {
	if r == nil {
		return nil
	}
	return r.DisplayName
}

// matchedAccount is the account whose verified email is from, while it may
// send to the address's target (§8.2, §4.3).
func (s *Server) matchedAccount(ctx context.Context, from string, a *store.EmailAddress) *store.User {
	if from == "" {
		return nil
	}
	u, err := s.st.VerifiedAccountByEmail(ctx, s.st.Q(), from)
	if err != nil || !s.canSendToTarget(ctx, u.ID, a) {
		return nil
	}
	return u
}

// canSendToTarget: a frame member with send, or any feed member.
func (s *Server) canSendToTarget(ctx context.Context, userID uuid.UUID, a *store.EmailAddress) bool {
	switch {
	case a.FrameID != nil:
		m, err := s.st.GetMembership(ctx, s.st.Q(), *a.FrameID, userID)
		return err == nil && m.Effective().Send
	case a.FeedID != nil:
		_, err := s.st.GetFeedMember(ctx, s.st.Q(), *a.FeedID, userID)
		return err == nil
	}
	return false
}

// ensureEmailOwner passes ownership to the frame's or feed's owner when the
// owner lost their rights (§4.3).
func (s *Server) ensureEmailOwner(ctx context.Context, a *store.EmailAddress) error {
	if a.OwnerUserID != nil && s.canSendToTarget(ctx, *a.OwnerUserID, a) {
		return nil
	}
	var owner uuid.UUID
	var err error
	if a.FrameID != nil {
		owner, err = s.st.FrameOwnerID(ctx, s.st.Q(), *a.FrameID)
	} else {
		owner, err = s.st.FeedOwnerID(ctx, s.st.Q(), *a.FeedID)
	}
	if err != nil {
		return err
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		cur, err := tx.LockEmailAddress(ctx, a.ID)
		if err != nil {
			return err
		}
		cur.OwnerUserID = &owner
		return tx.SaveEmailAddress(ctx, cur)
	})
	if err != nil {
		return err
	}
	a.OwnerUserID = &owner
	s.publishEmailAddress(ctx, a.ID)
	return nil
}

// logMessage records the outcome of a mail for an address (Recent mail).
func (s *Server) logMessage(ctx context.Context, j *mailJob, a *store.EmailAddress, d *emailDecision, outcome string) {
	m := &store.EmailMessage{ID: store.NewID(), AddressID: a.ID, MessageKey: j.key, Authenticated: j.auth.Authenticated,
		Outcome: outcome, ReceivedAt: j.now, Skipped: map[string]int(d.skipped)}
	if j.auth.From != "" {
		m.SenderEmail = &j.auth.From
	}
	if n := strings.TrimSpace(j.auth.FromName); n != "" {
		n = mailin.Label(n, "", j.auth.From)
		m.SenderName = &n
	} else if d.label != "" {
		m.SenderName = &d.label
	}
	m.Items = d.items
	if d.msg != nil {
		m.SendIDs = d.msg.SendIDs
	}
	if err := s.st.InsertEmailMessage(ctx, s.st.Q(), m); err != nil {
		j.log.Warn("email: log message", "err", err)
		return
	}
	d.msg = m
	if outcome != "delivered" && outcome != "held" {
		s.publishEmailAddress(ctx, a.ID)
	}
}

// logForAll records an outcome for every recipient address (parse failures).
func (s *Server) logForAll(ctx context.Context, j *mailJob, outcome string) {
	if j.key == "" {
		j.key = hex.EncodeToString(func() []byte { h := sha256.Sum256([]byte(j.row.ID.String())); return h[:] }())
	}
	for _, to := range j.row.EnvelopeTo {
		local, _, _ := mailin.LocalPart(to)
		if a, err := s.st.EmailAddressByLocalPart(ctx, s.st.Q(), local); err == nil {
			s.logMessage(ctx, j, a, &emailDecision{skipped: skipCounts{}}, outcome)
		}
	}
}

// forwardPostmaster sends mail to postmaster@/abuse@ on to the configured
// address, attached to a new mail from the server's own address (§6.5).
func (s *Server) forwardPostmaster(ctx context.Context, j *mailJob) {
	if s.em.cfg.Postmaster == "" || s.mailer == nil {
		return
	}
	raw, err := os.ReadFile(j.row.SpoolPath)
	if err != nil {
		return
	}
	subj := "Mail for postmaster@" + s.em.cfg.Domain
	text := fmt.Sprintf("A message for postmaster@ or abuse@%s is attached (from %s, authenticated: %v).\n",
		s.em.cfg.Domain, strings.ReplaceAll(j.auth.From, "\n", ""), j.auth.Authenticated)
	msg := shop.Message{To: s.em.cfg.Postmaster, Subject: subj, Text: text, HTML: "<p>" + strings.TrimSpace(text) + "</p>",
		Attachment: &shop.Attachment{Name: "message.eml", ContentType: "message/rfc822", Data: bytes.ReplaceAll(raw, []byte("\r\n"), []byte("\n"))}}
	sctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	if err := s.mailer.Send(sctx, msg); err != nil {
		j.log.Warn("email: postmaster forward failed", "err", err)
	}
}

// emailLang picks a reply's language: the matched account's, else the
// frame's locale.language (frame address) or the owner's (feed address).
func (s *Server) emailLang(ctx context.Context, a *store.EmailAddress, account *store.User) string {
	if account != nil && account.Locale != nil {
		return i18n.Pick(*account.Locale)
	}
	if a.FrameID != nil {
		if f, err := s.st.GetFrame(ctx, s.st.Q(), *a.FrameID); err == nil {
			var st struct {
				Locale struct {
					Language string `json:"language"`
				} `json:"locale"`
			}
			if json.Unmarshal(f.Settings, &st) == nil && st.Locale.Language != "" {
				return i18n.Pick(st.Locale.Language)
			}
		}
	}
	if a.OwnerUserID != nil {
		if u, err := s.st.GetUser(ctx, s.st.Q(), *a.OwnerUserID); err == nil && u.Locale != nil {
			return i18n.Pick(*u.Locale)
		}
	}
	return i18n.Default
}

// copyScratch gives a scratch file a second name for another media row
// (the pipeline consumes its source file).
func (s *Server) copyScratch(path string) (string, error) {
	dst := filepath.Join(s.scratchDir(), "email-"+store.NewID().String())
	if err := os.Link(path, dst); err == nil {
		return dst, nil
	}
	in, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return "", err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		_ = os.Remove(dst)
		return "", err
	}
	return dst, out.Close()
}
