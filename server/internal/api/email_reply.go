package api

import (
	"context"
	"os"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/i18n"
	"github.com/jeroentrappers/kinframe/server/internal/mailin"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/shop"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Replies to senders (§10): only to authenticated From addresses, through the
// server's own mailer, rate limited, with the app block (§10.4).

func labelOf(name, fallback, addr string) string { return mailin.Label(name, fallback, addr) }

func (s *Server) repliesAvailable() bool { return s.mailer != nil && s.em != nil && s.em.cfg.Replies }

// replyAllowed applies the per-address and server-wide caps (§10.3).
func (s *Server) replyAllowed(ctx context.Context, a *store.EmailAddress) bool {
	if !s.repliesAvailable() {
		return false
	}
	if n, err := s.st.RepliesSince(ctx, &a.ID, "", nil, s.now().Add(-24*time.Hour)); err != nil || n >= 100 {
		return false
	}
	ok, _ := s.em.replyHour.Allow("all")
	return ok
}

// sendReply renders and sends one reply; it records it on the logged
// messages ids and the reply recipient. Reports whether it went out.
func (s *Server) sendReply(ctx context.Context, a *store.EmailAddress, d *emailDecision, to string, in mailin.ReplyInput,
	inReplyTo string, ids []uuid.UUID) bool {
	if to == "" || !s.replyAllowed(ctx, a) {
		return false
	}
	in.Lang = s.emailLang(ctx, a, d.account)
	in.Name = a.Name
	in.Server = s.cfg.ServerName
	in.MaxMinutes = max(1, s.Limits(ctx).MaxVideoSeconds/60)
	in.Links = mailin.AppLinks{IOS: s.cfg.AppVersions["ios"].StoreURL, Android: s.cfg.AppVersions["android"].StoreURL,
		Web: s.cfg.PublicURL, Landing: s.cfg.PublicURL}
	long := true
	if rec, err := s.st.GetReplyRecipient(ctx, to); err == nil && rec.AppIntroSentAt != nil {
		long = false
	}
	in.App = mailin.AppFooter
	if long {
		in.App = mailin.AppLong
	}
	switch {
	case d.account != nil:
		if uses, err := s.st.UsesApp(ctx, d.account.ID); err == nil && uses {
			in.App = mailin.AppNone
		} else {
			in.OpenURL = s.cfg.PublicURL
		}
	case in.Kind != mailin.ReplyHeld && d.row != nil && d.row.State == "allowed" && a.OfferAppInvite && !d.noReply:
		in.JoinURL = s.emailJoinLink(ctx, a, d.row)
	}
	m := mailin.RenderReply(in)
	msg := shop.Message{To: to, Subject: m.Subject, Text: m.Text, HTML: m.HTML, AutoReply: true}
	if id := strings.TrimSpace(inReplyTo); id != "" && !strings.ContainsAny(id, "\r\n") {
		if !strings.HasPrefix(id, "<") {
			id = "<" + id + ">"
		}
		msg.InReplyTo = id
	}
	sctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	if err := s.mailer.Send(sctx, msg); err != nil {
		s.log.Warn("email: reply failed", "address", a.ID, "to_domain", mailin.Domain(to), "err", err)
		return false
	}
	now := s.now()
	if in.Kind == mailin.ReplyDelivered {
		_ = s.st.MarkConfirmed(ctx, ids, now)
	} else {
		for _, id := range ids {
			_ = s.st.SetEmailReply(ctx, id, in.Kind, now)
		}
	}
	_ = s.st.RecordReply(ctx, to, in.App == mailin.AppLong, now)
	return true
}

// replyDelivered confirms deliveries: at most one per sender and address per
// 10 minutes; mail in between is counted into the next one (§10.3).
func (s *Server) replyDelivered(ctx context.Context, a *store.EmailAddress, d *emailDecision, to, subject, msgID string) {
	if !a.SendConfirmations || to == "" || d.noReply {
		return
	}
	now := s.now()
	if n, err := s.st.RepliesSince(ctx, &a.ID, to, []string{mailin.ReplyDelivered}, now.Add(-10*time.Minute)); err != nil || n > 0 {
		return
	}
	s.confirm(ctx, a, d, to, subject, msgID)
}

// confirm sends one confirmation for every unconfirmed delivery of the sender.
func (s *Server) confirm(ctx context.Context, a *store.EmailAddress, d *emailDecision, to, subject, msgID string) {
	pending, err := s.st.UnconfirmedDeliveries(ctx, a.ID, to, s.now().Add(-24*time.Hour))
	if err != nil || len(pending) == 0 {
		return
	}
	in := mailin.ReplyInput{Kind: mailin.ReplyDelivered, Subject: subject, Skipped: map[string]int{}}
	ids := make([]uuid.UUID, len(pending))
	items := 0
	for i, m := range pending {
		ids[i] = m.ID
		items += m.Items
		for k, v := range m.Skipped {
			in.Skipped[k] += v
		}
	}
	if len(pending) == 1 && d != nil && d.items == items && d.items > 0 {
		in.Photos, in.Videos = d.photos, d.videos
	} else {
		in.Photos, in.Mails = items, len(pending)
	}
	if d == nil {
		d = &emailDecision{a: a, skipped: skipCounts{}}
		if row, err := s.st.EmailSenderByEmail(ctx, s.st.Q(), a.ID, to); err == nil {
			d.row = row
		}
		d.account = s.matchedAccount(ctx, to, a)
	}
	s.sendReply(ctx, a, d, to, in, msgID, ids)
}

// replyHeld tells a new pending sender that their mail waits (once per pending period).
func (s *Server) replyHeld(ctx context.Context, a *store.EmailAddress, d *emailDecision, to, subject, msgID string) {
	var ids []uuid.UUID
	if d.msg != nil {
		ids = []uuid.UUID{d.msg.ID}
	}
	s.sendReply(ctx, a, d, to, mailin.ReplyInput{Kind: mailin.ReplyHeld, Subject: subject}, msgID, ids)
}

// replyFailure explains why nothing came through: at most 3 a day per sender and address.
func (s *Server) replyFailure(ctx context.Context, a *store.EmailAddress, d *emailDecision, kind, to, subject, msgID string) {
	if d.row != nil && d.row.State == "blocked" {
		return
	}
	n, err := s.st.RepliesSince(ctx, &a.ID, to, []string{mailin.ReplyNoMedia, mailin.ReplyTooLarge, mailin.ReplyEncrypted}, s.now().Add(-24*time.Hour))
	if err != nil || n >= 3 {
		return
	}
	var ids []uuid.UUID
	if d.msg != nil {
		ids = []uuid.UUID{d.msg.ID}
	}
	if d.account == nil {
		d.account = s.matchedAccount(ctx, to, a)
	}
	s.sendReply(ctx, a, d, to, mailin.ReplyInput{Kind: kind, Subject: subject, Skipped: map[string]int(d.skipped)}, msgID, ids)
}

// ---------------------------------------------------------------- join links (§10.4)

// emailJoinLink returns the sender's live join link, creating one (single
// use, 30 days, by the owner, always needing approval) when needed.
func (s *Server) emailJoinLink(ctx context.Context, a *store.EmailAddress, row *store.EmailSender) string {
	feed := a.FeedID != nil
	base := s.cfg.PublicURL + "/i/"
	if feed {
		base = s.cfg.PublicURL + "/fi/"
	}
	if tok, _, ok, err := s.st.LiveSenderInvite(ctx, s.st.Q(), row.ID, feed); err == nil && ok {
		return base + tok
	}
	if a.OwnerUserID == nil {
		return ""
	}
	lang := i18n.Default
	if u, err := s.st.GetUser(ctx, s.st.Q(), *a.OwnerUserID); err == nil && u.Locale != nil {
		lang = i18n.Pick(*u.Locale)
	}
	who := row.Email
	if row.DisplayName != nil && *row.DisplayName != "" {
		who = *row.DisplayName + " (" + row.Email + ")"
	}
	note := i18n.T(lang, "email.invite.note", map[string]string{"sender": who})
	if r := []rune(note); len(r) > 200 {
		note = string(r[:200])
	}
	exp := s.now().Add(30 * 24 * time.Hour).UTC().Truncate(time.Second)
	id := store.NewID()
	token := auth.RandomToken(24)
	var err error
	for try := 0; try < 5; try++ {
		if feed {
			_, err = s.st.CreateFeedInvite(ctx, s.st.Q(), store.FeedInvite{ID: id, FeedID: *a.FeedID, Purpose: "join", Token: token,
				Code: newFeedCode(), CreatedBy: a.OwnerUserID, MaxUses: ptr(1), Note: &note, ExpiresAt: exp, Via: ptr("email"), ViaRef: ptr(row.ID.String())})
		} else {
			_, err = s.st.CreateInvite(ctx, s.st.Q(), store.Invite{ID: id, FrameID: *a.FrameID, Token: token, CreatedBy: a.OwnerUserID,
				MaxUses: ptr(1), Note: &note, ExpiresAt: exp, Via: ptr("email"), ViaRef: ptr(row.ID.String())})
		}
		if !db.IsUniqueViolation(err, "") {
			break
		}
	}
	if err != nil {
		s.log.Warn("email: join link", "err", err)
		return ""
	}
	row.InviteID = &id
	_ = s.st.InTx(ctx, func(tx *store.Tx) error { return tx.SaveEmailSender(ctx, row) })
	return base + token
}

// emailChannelJoin fills a join request made from an email join link: who it
// was mailed to, and whether the account's verified email is that address.
func emailChannelJoin(s *Server, ctx context.Context, tx *store.Tx, viaRef string, u *store.User) (store.JoinChannel, error) {
	var ch store.JoinChannel
	id, err := uuid.Parse(viaRef)
	if err != nil {
		return ch, nil
	}
	row, err := s.st.GetEmailSenderByID(ctx, tx, id)
	if err == store.ErrNotFound {
		return ch, nil
	}
	if err != nil {
		return ch, err
	}
	name := labelOf(deref(row.DisplayName), "", row.Email)
	vn := name + " · " + row.Email
	ch.Name, ch.Ref = &vn, &row.Email
	m := u.EmailVerified && u.Email != nil && strings.EqualFold(*u.Email, row.Email)
	ch.Matches = &m
	return ch, nil
}

// ---------------------------------------------------------------- periodic work

// emailMinutely sends coalesced confirmations, expiring-held reminders and
// held previews.
func (s *Server) emailMinutely(ctx context.Context) {
	now := s.now()
	if s.repliesAvailable() {
		if pcs, err := s.st.PendingConfirmations(ctx, now.Add(-10*time.Minute), now.Add(-24*time.Hour)); err == nil {
			for _, pc := range pcs {
				a, err := s.st.GetEmailAddress(ctx, s.st.Q(), pc.AddressID)
				if err != nil || !a.SendConfirmations {
					continue
				}
				subject, msgID := "", ""
				s.confirm(ctx, a, nil, pc.Sender, subject, msgID)
			}
		}
	}
	if rows, err := s.st.ExpiringPending(ctx, now.Add(3*24*time.Hour)); err == nil {
		for i := range rows {
			r := &rows[i]
			a, err := s.st.GetEmailAddress(ctx, s.st.Q(), r.AddressID)
			if err != nil {
				continue
			}
			ids := emailPushIDs(a, r.ID)
			push := &notify.Push{Key: "email_pending_sender", Text: "email_pending_expiring", IDs: ids,
				Args: map[string]string{"sender": labelOf(deref(r.DisplayName), "", r.Email), "name": a.Name}}
			s.notify.Users(s.emailAudience(ctx, a), notify.Event{"type": "email_sender.pending", "address_id": a.ID, "pending": s.pendingJSON(ctx, a, r)}, push)
			r.ExpiringNotified = true
			_ = s.st.InTx(ctx, func(tx *store.Tx) error { return tx.SaveEmailSender(ctx, r) })
		}
	}
	s.fillHeldThumbs(ctx)
}

// PruneEmail applies the retention rules (§12.4) and retires the addresses of
// deleted frames and feeds.
func (s *Server) PruneEmail(ctx context.Context) error {
	if s.em == nil {
		return nil
	}
	if gone, err := s.st.SoftDeletedTargetAddresses(ctx); err == nil {
		for i := range gone {
			a := &gone[i]
			audience := s.emailAudience(ctx, a)
			var blobs *store.MediaBlobs
			if err := s.st.InTx(ctx, func(tx *store.Tx) error {
				var err error
				blobs, err = tx.DeleteEmailAddress(ctx, a)
				return err
			}); err != nil {
				return err
			}
			s.deleteBlobs(ctx, blobs.Keys)
			s.publishEmailRemoved(a, audience)
		}
	}
	res, err := s.st.PruneEmail(ctx, s.now())
	if err != nil {
		return err
	}
	for _, p := range res.Spools {
		_ = os.Remove(p)
	}
	if len(res.Spools) > 0 {
		s.log.Warn("email: queued messages older than 24 hours dropped as failed", "count", len(res.Spools))
	}
	s.deleteBlobs(ctx, res.Blobs.Keys)
	for _, id := range res.Addresses {
		s.publishEmailAddress(ctx, id)
	}
	return nil
}
