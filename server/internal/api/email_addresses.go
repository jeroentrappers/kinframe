package api

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"math/big"
	"net/mail"
	"strings"
	"time"
	"unicode"

	"github.com/google/uuid"
	"golang.org/x/text/runes"
	"golang.org/x/text/transform"
	"golang.org/x/text/unicode/norm"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Inbox address management (tag email-inbox): addresses, senders, pending
// senders, held mail and recent mail. Permissions are in §4.

// ---------------------------------------------------------------- JSON

type emailOwnerJSON struct {
	UserID      uuid.UUID `json:"user_id"`
	DisplayName string    `json:"display_name"`
	AvatarURL   *string   `json:"avatar_url,omitempty"`
}

type emailTargetJSON struct {
	Kind      string     `json:"kind"`
	FrameID   *uuid.UUID `json:"frame_id,omitempty"`
	FrameName *string    `json:"frame_name,omitempty"`
	FeedID    *uuid.UUID `json:"feed_id,omitempty"`
	FeedName  *string    `json:"feed_name,omitempty"`
}

type emailCountsJSON struct {
	Allowed     int `json:"allowed_senders"`
	Blocked     int `json:"blocked_senders"`
	Pending     int `json:"pending_senders"`
	Held        int `json:"held_messages"`
	Delivered30 int `json:"delivered_30d"`
}

type emailAddressJSON struct {
	ID                uuid.UUID       `json:"id"`
	Address           string          `json:"address"`
	Slug              string          `json:"slug"`
	Name              string          `json:"name"`
	Target            emailTargetJSON `json:"target"`
	Owner             *emailOwnerJSON `json:"owner,omitempty"`
	Status            string          `json:"status"`
	AnyoneMaySend     bool            `json:"anyone_may_send"`
	SendConfirmations bool            `json:"send_confirmations"`
	OfferAppInvite    bool            `json:"offer_app_invite"`
	NotifyReactions   bool            `json:"notify_reactions"`
	RepliesAvailable  bool            `json:"replies_available"`
	Counts            emailCountsJSON `json:"counts"`
	LastReceivedAt    *time.Time      `json:"last_received_at,omitempty"`
	RegeneratedAt     *time.Time      `json:"regenerated_at,omitempty"`
	CanManage         bool            `json:"can_manage"`
	IsOwner           bool            `json:"is_owner"`
	CreatedAt         time.Time       `json:"created_at"`
}

func (s *Server) emailAddressJSON(v *store.EmailAddressView, viewer uuid.UUID) emailAddressJSON {
	out := emailAddressJSON{ID: v.ID, Address: v.LocalPart + "@" + s.em.cfg.Domain, Slug: v.Slug, Name: v.Name, Status: v.Status,
		AnyoneMaySend: v.AnyoneMaySend, SendConfirmations: v.SendConfirmations, OfferAppInvite: v.OfferAppInvite,
		NotifyReactions: v.NotifyReactions, RepliesAvailable: s.repliesAvailable(), LastReceivedAt: v.LastReceivedAt,
		RegeneratedAt: v.RegeneratedAt, CanManage: true, CreatedAt: v.CreatedAt,
		Counts: emailCountsJSON{Allowed: v.Allowed, Blocked: v.Blocked, Pending: v.Pending, Held: v.Held, Delivered30: v.Delivered30d}}
	if v.FrameID != nil {
		out.Target = emailTargetJSON{Kind: "frame", FrameID: v.FrameID, FrameName: v.FrameName}
	} else {
		out.Target = emailTargetJSON{Kind: "feed", FeedID: v.FeedID, FeedName: v.FeedName}
	}
	if v.OwnerUserID != nil {
		out.Owner = &emailOwnerJSON{UserID: *v.OwnerUserID, DisplayName: deref(v.OwnerName), AvatarURL: s.avatarURL(v.OwnerAvatarKey)}
		out.IsOwner = *v.OwnerUserID == viewer
	}
	return out
}

type emailSenderJSON struct {
	ID            uuid.UUID  `json:"id"`
	Email         string     `json:"email"`
	DisplayName   *string    `json:"display_name,omitempty"`
	State         string     `json:"state"`
	Origin        string     `json:"origin"`
	AddedByName   *string    `json:"added_by_name,omitempty"`
	LastMessageAt *time.Time `json:"last_message_at,omitempty"`
	CreatedAt     time.Time  `json:"created_at"`
}

func emailSenderOut(e *store.EmailSender) emailSenderJSON {
	return emailSenderJSON{ID: e.ID, Email: e.Email, DisplayName: e.DisplayName, State: e.State, Origin: e.Origin,
		AddedByName: e.AddedByName, LastMessageAt: e.LastMessageAt, CreatedAt: e.CreatedAt}
}

type heldSummaryJSON struct {
	ID            uuid.UUID `json:"id"`
	Reason        string    `json:"reason"`
	Subject       *string   `json:"subject,omitempty"`
	ReceivedAt    time.Time `json:"received_at"`
	ExpiresAt     time.Time `json:"expires_at"`
	MediaCount    int       `json:"media_count"`
	Authenticated bool      `json:"authenticated"`
	ThumbURLs     []string  `json:"thumb_urls"`
}

type pendingSenderJSON struct {
	ID            uuid.UUID         `json:"id"`
	Email         string            `json:"email"`
	DisplayName   *string           `json:"display_name,omitempty"`
	Authenticated bool              `json:"authenticated"`
	FirstHeldAt   time.Time         `json:"first_held_at"`
	LastHeldAt    time.Time         `json:"last_held_at"`
	Messages      []heldSummaryJSON `json:"messages"`
}

func heldAuthenticated(h *store.HeldMessage) bool {
	var a struct {
		Authenticated bool `json:"authenticated"`
	}
	_ = json.Unmarshal(h.Auth, &a)
	return a.Authenticated
}

func (s *Server) pendingJSON(ctx context.Context, a *store.EmailAddress, row *store.EmailSender) pendingSenderJSON {
	out := pendingSenderJSON{ID: row.ID, Email: row.Email, DisplayName: row.DisplayName, Messages: []heldSummaryJSON{},
		FirstHeldAt: row.CreatedAt, LastHeldAt: row.CreatedAt}
	held, _ := s.st.ListHeldMessages(ctx, s.st.Q(), a.ID, &row.ID)
	for i := range held {
		h := &held[i]
		sum := heldSummaryJSON{ID: h.ID, Reason: h.Reason, Subject: h.Subject, ReceivedAt: h.ReceivedAt, ExpiresAt: h.ExpiresAt,
			MediaCount: len(h.Media), Authenticated: heldAuthenticated(h), ThumbURLs: []string{}}
		for _, m := range h.Media {
			if len(sum.ThumbURLs) == 4 {
				break
			}
			if u := s.heldPreviewURL(ctx, m); u != nil {
				sum.ThumbURLs = append(sum.ThumbURLs, *u)
			}
		}
		out.Messages = append(out.Messages, sum)
		if i == 0 {
			out.LastHeldAt, out.Authenticated = h.ReceivedAt, sum.Authenticated
		}
		out.FirstHeldAt = h.ReceivedAt
	}
	return out
}

type heldMediaJSON struct {
	Kind       string  `json:"kind"`
	Width      *int    `json:"width,omitempty"`
	Height     *int    `json:"height,omitempty"`
	DurationMS *int    `json:"duration_ms,omitempty"`
	Bytes      *int64  `json:"bytes,omitempty"`
	Filename   *string `json:"filename,omitempty"`
	ThumbURL   *string `json:"thumb_url,omitempty"`
}

type heldMessageJSON struct {
	ID          uuid.UUID       `json:"id"`
	AddressID   uuid.UUID       `json:"address_id"`
	SenderID    uuid.UUID       `json:"sender_id"`
	SenderEmail string          `json:"sender_email"`
	SenderName  *string         `json:"sender_name,omitempty"`
	Reason      string          `json:"reason"`
	Subject     *string         `json:"subject,omitempty"`
	Caption     *string         `json:"caption,omitempty"`
	ReceivedAt  time.Time       `json:"received_at"`
	ExpiresAt   time.Time       `json:"expires_at"`
	Auth        json.RawMessage `json:"auth"`
	Media       []heldMediaJSON `json:"media"`
	Skipped     map[string]int  `json:"skipped"`
}

// authJSON keeps only the contract's EmailAuthResult fields.
func authJSON(raw json.RawMessage) json.RawMessage {
	var m map[string]any
	if json.Unmarshal(raw, &m) != nil {
		return json.RawMessage(`{"authenticated":false}`)
	}
	out := map[string]any{}
	for _, k := range []string{"authenticated", "from_domain", "spf", "dkim", "dkim_domain", "dmarc", "dmarc_policy", "aligned"} {
		if v, ok := m[k]; ok && v != nil {
			out[k] = v
		}
	}
	if _, ok := out["authenticated"]; !ok {
		out["authenticated"] = false
	}
	b, _ := json.Marshal(out)
	return b
}

func (s *Server) heldJSON(ctx context.Context, h *store.HeldMessage) heldMessageJSON {
	out := heldMessageJSON{ID: h.ID, AddressID: h.AddressID, SenderID: h.SenderID, SenderEmail: h.SenderEmail, SenderName: h.SenderName,
		Reason: h.Reason, Subject: h.Subject, Caption: h.Caption, ReceivedAt: h.ReceivedAt, ExpiresAt: h.ExpiresAt,
		Auth: authJSON(h.Auth), Media: []heldMediaJSON{}, Skipped: h.Skipped}
	if out.Skipped == nil {
		out.Skipped = map[string]int{}
	}
	for _, m := range h.Media {
		b := m.Bytes
		out.Media = append(out.Media, heldMediaJSON{Kind: m.Kind, Width: m.Width, Height: m.Height, DurationMS: m.DurationMS, Bytes: &b,
			Filename: m.Filename, ThumbURL: s.heldPreviewURL(ctx, m)})
	}
	return out
}

type emailMessageJSON struct {
	ID            uuid.UUID      `json:"id"`
	ReceivedAt    time.Time      `json:"received_at"`
	SenderEmail   *string        `json:"sender_email,omitempty"`
	SenderName    *string        `json:"sender_name,omitempty"`
	Authenticated bool           `json:"authenticated"`
	Outcome       string         `json:"outcome"`
	Items         int            `json:"items"`
	Skipped       map[string]int `json:"skipped"`
	SendIDs       []uuid.UUID    `json:"send_ids"`
	Reply         *string        `json:"reply,omitempty"`
}

func emailMessageOut(m *store.EmailMessage) emailMessageJSON {
	out := emailMessageJSON{ID: m.ID, ReceivedAt: m.ReceivedAt, SenderEmail: m.SenderEmail, SenderName: m.SenderName,
		Authenticated: m.Authenticated, Outcome: m.Outcome, Items: m.Items, Skipped: m.Skipped, SendIDs: m.SendIDs, Reply: m.ReplyKind}
	if out.Skipped == nil {
		out.Skipped = map[string]int{}
	}
	if out.SendIDs == nil {
		out.SendIDs = []uuid.UUID{}
	}
	return out
}

// ---------------------------------------------------------------- permissions

func (s *Server) requireEmail() error {
	if !s.emailOn() {
		return problem.New(problem.FeatureDisabled, "email to frame is not turned on on this server")
	}
	return nil
}

// canManageEmail: frame managers (owner, admin, change_settings) or feed owner/admins.
func (s *Server) canManageEmail(ctx context.Context, q db.DBTX, userID uuid.UUID, a *store.EmailAddress) bool {
	switch {
	case a.FrameID != nil:
		m, err := s.st.GetMembership(ctx, q, *a.FrameID, userID)
		return err == nil && m.Effective().ChangeSettings
	case a.FeedID != nil:
		v, err := s.st.GetFeedView(ctx, q, userID, *a.FeedID)
		return err == nil && v.IsAdmin()
	}
	return false
}

// managedAddress loads an address the caller manages; others get 404.
func (s *Server) managedAddress(ctx context.Context, id uuid.UUID) (*store.EmailAddressView, error) {
	if s.em == nil {
		return nil, problem.New(problem.NotFound, "email address not found")
	}
	v, err := s.st.GetEmailAddressView(ctx, s.st.Q(), id)
	if err == store.ErrNotFound || err == nil && !s.canManageEmail(ctx, s.st.Q(), me(ctx).ID, &v.EmailAddress) {
		return nil, problem.New(problem.NotFound, "email address not found")
	}
	return v, err
}

func (s *Server) frameEmailManager(ctx context.Context, frameID uuid.UUID) (*store.Frame, error) {
	m, err := s.membership(ctx, frameID)
	if err != nil {
		return nil, err
	}
	if !m.Effective().ChangeSettings {
		return nil, problem.New(problem.MissingPermission, "only frame managers can see and change email addresses")
	}
	return s.st.GetFrame(ctx, s.st.Q(), frameID)
}

func (s *Server) feedEmailManager(ctx context.Context, feedID uuid.UUID) (*store.FeedView, error) {
	v, err := s.feedView(ctx, s.st.Q(), feedID)
	if err != nil {
		return nil, err
	}
	if !v.IsAdmin() {
		return nil, problem.New(problem.MissingPermission, "only the feed's owner and admins can see and change email addresses")
	}
	return v, nil
}

// emailAudience: the people who manage an address's target.
func (s *Server) emailAudience(ctx context.Context, a *store.EmailAddress) []uuid.UUID {
	var ids []uuid.UUID
	switch {
	case a.FrameID != nil:
		ids, _ = s.st.EmailFrameManagerIDs(ctx, s.st.Q(), *a.FrameID)
	case a.FeedID != nil:
		ids, _ = s.st.FeedAdminIDs(ctx, s.st.Q(), *a.FeedID)
	}
	return ids
}

func emailPushIDs(a *store.EmailAddress, senderID uuid.UUID) map[string]string {
	ids := map[string]string{"address_id": a.ID.String(), "sender_id": senderID.String()}
	if a.FrameID != nil {
		ids["frame_id"] = a.FrameID.String()
	} else if a.FeedID != nil {
		ids["feed_id"] = a.FeedID.String()
	}
	return ids
}

// ---------------------------------------------------------------- events (§11)

func (s *Server) publishEmailAddress(ctx context.Context, id uuid.UUID) {
	v, err := s.st.GetEmailAddressView(ctx, s.st.Q(), id)
	if err != nil {
		return
	}
	for _, uid := range s.emailAudience(ctx, &v.EmailAddress) {
		s.notify.User(uid, notify.Event{"type": "email_address.updated", "address": s.emailAddressJSON(v, uid)}, nil)
	}
}

func (s *Server) publishEmailRemoved(a *store.EmailAddress, audience []uuid.UUID) {
	ev := notify.Event{"type": "email_address.removed", "address_id": a.ID}
	if a.FrameID != nil {
		ev["frame_id"] = *a.FrameID
	}
	if a.FeedID != nil {
		ev["feed_id"] = *a.FeedID
	}
	s.notify.Users(audience, ev, nil)
}

func (s *Server) publishSenderDecided(ctx context.Context, a *store.EmailAddress, senderID uuid.UUID, decision string) {
	s.notify.Users(s.emailAudience(ctx, a), notify.Event{"type": "email_sender.decided", "address_id": a.ID, "sender_id": senderID,
		"decision": decision}, nil)
	s.publishEmailAddress(ctx, a.ID)
}

// announcePending tells the managers that someone waits: a push once per
// pending sender per 7 days (at most 10 per address a day), the websocket always.
func (s *Server) announcePending(ctx context.Context, a *store.EmailAddress, row *store.EmailSender, count int) {
	if row == nil {
		return
	}
	var push *notify.Push
	now := s.now()
	if row.State == "pending" && (row.PendingNotifiedAt == nil || now.Sub(*row.PendingNotifiedAt) >= 7*24*time.Hour) {
		if ok, _ := s.em.pendingDay.Allow(a.ID.String()); ok {
			name := labelOf(deref(row.DisplayName), "", row.Email)
			if row.DisplayName != nil {
				name += " (" + row.Email + ")"
			}
			push = &notify.Push{Key: "email_pending_sender", IDs: emailPushIDs(a, row.ID),
				Args: map[string]string{"sender": name, "count": itoa(count), "name": a.Name}}
			row.PendingNotifiedAt = &now
			_ = s.st.InTx(ctx, func(tx *store.Tx) error { return tx.SaveEmailSender(ctx, row) })
		}
	}
	s.notify.Users(s.emailAudience(ctx, a), notify.Event{"type": "email_sender.pending", "address_id": a.ID, "pending": s.pendingJSON(ctx, a, row)}, push)
}

func itoa(n int) string { return big.NewInt(int64(n)).String() }

// ---------------------------------------------------------------- addresses

const randomAlphabet = "23456789abcdefghjkmnpqrstuvwxyz"

func randomPart() string {
	b := make([]byte, 6)
	for i := range b {
		n, err := rand.Int(rand.Reader, big.NewInt(int64(len(randomAlphabet))))
		if err != nil {
			panic(err)
		}
		b[i] = randomAlphabet[n.Int64()]
	}
	return string(b)
}

// slugFrom makes the friendly part from a name: its first word, lower case,
// accents removed ("Oma's frame" -> "oma").
func slugFrom(name string) string {
	t := transform.Chain(norm.NFD, runes.Remove(runes.In(unicode.Mn)), norm.NFC)
	plain, _, _ := transform.String(t, name)
	var words []string
	var cur strings.Builder
	for _, r := range strings.ToLower(plain) {
		if r >= 'a' && r <= 'z' || r >= '0' && r <= '9' {
			cur.WriteRune(r)
			continue
		}
		if cur.Len() > 0 {
			words = append(words, cur.String())
			cur.Reset()
		}
	}
	if cur.Len() > 0 {
		words = append(words, cur.String())
	}
	if len(words) == 0 {
		return "photos"
	}
	w := words[0]
	if len(w) > 24 {
		w = w[:24]
	}
	return w
}

func validSlug(s string) bool {
	if len(s) < 1 || len(s) > 24 || s[0] == '-' || s[len(s)-1] == '-' {
		return false
	}
	for _, r := range s {
		if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '-') {
			return false
		}
	}
	return true
}

type emailAddressRef struct {
	ID uuid.UUID `json:"id"`
}

func (s *Server) ListFrameEmailAddresses(ctx context.Context, req gen.ListFrameEmailAddressesRequestObject) (gen.ListFrameEmailAddressesResponseObject, error) {
	if _, err := s.frameEmailManager(ctx, req.FrameId); err != nil {
		return nil, err
	}
	return s.listEmailAddresses(ctx, &req.FrameId, nil)
}

func (s *Server) ListFeedEmailAddresses(ctx context.Context, req gen.ListFeedEmailAddressesRequestObject) (gen.ListFeedEmailAddressesResponseObject, error) {
	if _, err := s.feedEmailManager(ctx, req.FeedId); err != nil {
		return nil, err
	}
	return s.listEmailAddresses(ctx, nil, &req.FeedId)
}

func (s *Server) listEmailAddresses(ctx context.Context, frameID, feedID *uuid.UUID) (Response, error) {
	vs, err := s.st.ListEmailAddresses(ctx, frameID, feedID)
	if err != nil {
		return Response{}, err
	}
	out := make([]emailAddressJSON, len(vs))
	for i := range vs {
		out[i] = s.emailAddressJSON(&vs[i], me(ctx).ID)
	}
	return ok(out), nil
}

func (s *Server) CreateFrameEmailAddress(ctx context.Context, req gen.CreateFrameEmailAddressRequestObject) (gen.CreateFrameEmailAddressResponseObject, error) {
	f, err := s.frameEmailManager(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	return s.createEmailAddress(ctx, &req.FrameId, nil, f.Name, req.Params.IdempotencyKey, req.Body)
}

func (s *Server) CreateFeedEmailAddress(ctx context.Context, req gen.CreateFeedEmailAddressRequestObject) (gen.CreateFeedEmailAddressResponseObject, error) {
	v, err := s.feedEmailManager(ctx, req.FeedId)
	if err != nil {
		return nil, err
	}
	return s.createEmailAddress(ctx, nil, &req.FeedId, v.Name, req.Params.IdempotencyKey, req.Body)
}

func (s *Server) createEmailAddress(ctx context.Context, frameID, feedID *uuid.UUID, targetName string, idem *string, b *gen.EmailAddressCreate) (Response, error) {
	if err := s.requireEmail(); err != nil {
		return Response{}, err
	}
	u := me(ctx)
	key := ""
	if idem != nil {
		key = *idem
		var prev emailAddressRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "email_address", key, &prev); err != nil {
			return Response{}, err
		} else if found {
			v, err := s.st.GetEmailAddressView(ctx, s.st.Q(), prev.ID)
			if err != nil {
				return Response{}, notFoundEmail(err)
			}
			return created(s.emailAddressJSON(v, u.ID)), nil
		}
	}
	if ok, retry := s.em.createUser.Allow(u.ID.String()); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return Response{}, p
	}
	if b == nil {
		b = &gen.EmailAddressCreate{}
	}
	slug := slugFrom(targetName)
	if b.Slug != nil {
		slug = *b.Slug
	}
	if !validSlug(slug) {
		return Response{}, problem.Validation("slug", "pattern", "the friendly part must be lower-case letters, digits and hyphens")
	}
	name := strings.TrimSpace(targetName)
	if b.Name != nil && strings.TrimSpace(*b.Name) != "" {
		name = strings.TrimSpace(*b.Name)
	}
	if r := []rune(name); len(r) > 60 {
		name = string(r[:60])
	}
	a := store.EmailAddress{ID: store.NewID(), Slug: slug, Name: name, FrameID: frameID, FeedID: feedID, OwnerUserID: &u.ID,
		CreatedByUserID: &u.ID, SendConfirmations: true, OfferAppInvite: true, NotifyReactions: true}
	setb := func(dst *bool, src *bool) {
		if src != nil {
			*dst = *src
		}
	}
	setb(&a.AnyoneMaySend, b.AnyoneMaySend)
	setb(&a.SendConfirmations, b.SendConfirmations)
	setb(&a.OfferAppInvite, b.OfferAppInvite)
	setb(&a.NotifyReactions, b.NotifyReactions)
	limit := s.Limits(ctx).MaxEmailAddressesPerTarget
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		if frameID != nil {
			if _, err := tx.LockFrame(ctx, *frameID); err != nil {
				return notFound(err, "frame")
			}
		} else if _, err := tx.Exec(ctx, `SELECT 1 FROM feeds WHERE id = $1 FOR UPDATE`, *feedID); err != nil {
			return err
		}
		n, err := s.st.CountEmailAddresses(ctx, tx, frameID, feedID)
		if err != nil {
			return err
		}
		if n >= limit {
			return problem.New(problem.LimitReached, "a frame or feed can have at most %d email addresses", limit)
		}
		for try := 0; ; try++ {
			a.Random = randomPart()
			a.LocalPart = a.Slug + "-" + a.Random
			used, err := s.st.LocalPartUsed(ctx, tx, a.LocalPart)
			if err != nil {
				return err
			}
			if !used {
				break
			}
			if try > 20 {
				return problem.Validation("slug", "unavailable", "this address can't be issued again; choose another friendly part")
			}
		}
		if err := tx.CreateEmailAddress(ctx, a); err != nil {
			return err
		}
		if key != "" {
			return tx.SaveIdempotent(ctx, u.ID, "email_address", key, emailAddressRef{a.ID})
		}
		return nil
	})
	if err != nil {
		return Response{}, err
	}
	v, err := s.st.GetEmailAddressView(ctx, s.st.Q(), a.ID)
	if err != nil {
		return Response{}, err
	}
	s.publishEmailAddress(ctx, a.ID)
	return created(s.emailAddressJSON(v, u.ID)), nil
}

func notFoundEmail(err error) error {
	if err == store.ErrNotFound {
		return problem.New(problem.NotFound, "email address not found")
	}
	return err
}

func (s *Server) GetEmailAddress(ctx context.Context, req gen.GetEmailAddressRequestObject) (gen.GetEmailAddressResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	return ok(s.emailAddressJSON(v, me(ctx).ID)), nil
}

func (s *Server) UpdateEmailAddress(ctx context.Context, req gen.UpdateEmailAddressRequestObject) (gen.UpdateEmailAddressResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	b := req.Body
	if b == nil || (b.Name == nil && b.Slug == nil && b.Paused == nil && b.AnyoneMaySend == nil && b.SendConfirmations == nil &&
		b.OfferAppInvite == nil && b.NotifyReactions == nil && b.TakeOver == nil) {
		return nil, problem.New(problem.ValidationFailed, "choose at least one field to change")
	}
	u := me(ctx)
	if b.NotifyReactions != nil && *b.NotifyReactions != v.NotifyReactions && (v.OwnerUserID == nil || *v.OwnerUserID != u.ID) &&
		(b.TakeOver == nil || !*b.TakeOver) {
		return nil, problem.New(problem.Forbidden, "only the address owner can change notify_reactions")
	}
	if b.Slug != nil && !validSlug(*b.Slug) {
		return nil, problem.Validation("slug", "pattern", "the friendly part must be lower-case letters, digits and hyphens")
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		a, err := tx.LockEmailAddress(ctx, v.ID)
		if err != nil {
			return notFoundEmail(err)
		}
		if b.Name != nil && strings.TrimSpace(*b.Name) != "" {
			a.Name = strings.TrimSpace(*b.Name)
		}
		if b.Paused != nil {
			a.Status = map[bool]string{true: "paused", false: "active"}[*b.Paused]
		}
		if b.AnyoneMaySend != nil {
			a.AnyoneMaySend = *b.AnyoneMaySend
		}
		if b.SendConfirmations != nil {
			a.SendConfirmations = *b.SendConfirmations
		}
		if b.OfferAppInvite != nil {
			a.OfferAppInvite = *b.OfferAppInvite
		}
		if b.TakeOver != nil && *b.TakeOver {
			a.OwnerUserID = &u.ID
		}
		if b.NotifyReactions != nil {
			a.NotifyReactions = *b.NotifyReactions
		}
		if err := tx.SaveEmailAddress(ctx, a); err != nil {
			return err
		}
		if b.Slug != nil && *b.Slug != a.Slug {
			lp := *b.Slug + "-" + a.Random
			used, err := s.st.LocalPartUsed(ctx, tx, lp)
			if err != nil {
				return err
			}
			if used {
				return problem.Validation("slug", "unavailable", "this address can't be issued again; choose another friendly part")
			}
			return tx.ChangeLocalPart(ctx, a, *b.Slug, a.Random, false)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	v, err = s.st.GetEmailAddressView(ctx, s.st.Q(), v.ID)
	if err != nil {
		return nil, err
	}
	s.publishEmailAddress(ctx, v.ID)
	return ok(s.emailAddressJSON(v, u.ID)), nil
}

func (s *Server) DeleteEmailAddress(ctx context.Context, req gen.DeleteEmailAddressRequestObject) (gen.DeleteEmailAddressResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	audience := s.emailAudience(ctx, &v.EmailAddress)
	var blobs *store.MediaBlobs
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		a, err := tx.LockEmailAddress(ctx, v.ID)
		if err != nil {
			return notFoundEmail(err)
		}
		blobs, err = tx.DeleteEmailAddress(ctx, a)
		return err
	})
	if err != nil {
		return nil, err
	}
	s.deleteBlobs(ctx, blobs.Keys)
	s.publishEmailRemoved(&v.EmailAddress, audience)
	return noContent(), nil
}

func (s *Server) RegenerateEmailAddress(ctx context.Context, req gen.RegenerateEmailAddressRequestObject) (gen.RegenerateEmailAddressResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	if err := s.requireEmail(); err != nil {
		return nil, err
	}
	if ok, retry := s.em.regenAddr.Allow(v.ID.String()); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		a, err := tx.LockEmailAddress(ctx, v.ID)
		if err != nil {
			return notFoundEmail(err)
		}
		for try := 0; try < 20; try++ {
			r := randomPart()
			used, err := s.st.LocalPartUsed(ctx, tx, a.Slug+"-"+r)
			if err != nil {
				return err
			}
			if !used {
				return tx.ChangeLocalPart(ctx, a, a.Slug, r, true)
			}
		}
		return problem.Validation("slug", "unavailable", "this address can't be issued again; choose another friendly part")
	})
	if err != nil {
		return nil, err
	}
	v, err = s.st.GetEmailAddressView(ctx, s.st.Q(), v.ID)
	if err != nil {
		return nil, err
	}
	s.publishEmailAddress(ctx, v.ID)
	return ok(s.emailAddressJSON(v, me(ctx).ID)), nil
}

// ---------------------------------------------------------------- senders

func notFoundSender(err error) error {
	if err == store.ErrNotFound {
		return problem.New(problem.NotFound, "sender not found")
	}
	return err
}

func (s *Server) ListEmailSenders(ctx context.Context, req gen.ListEmailSendersRequestObject) (gen.ListEmailSendersResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	rows, err := s.st.ListEmailSenders(ctx, s.st.Q(), v.ID, "allowed", "blocked")
	if err != nil {
		return nil, err
	}
	out := make([]emailSenderJSON, len(rows))
	for i := range rows {
		out[i] = emailSenderOut(&rows[i])
	}
	return ok(out), nil
}

// normEmail validates and lower-cases a bare email address.
func normEmail(s string) (string, bool) {
	a, err := mail.ParseAddress(strings.TrimSpace(s))
	if err != nil || a.Address == "" || !strings.Contains(a.Address, "@") || len(a.Address) > 254 {
		return "", false
	}
	return strings.ToLower(a.Address), true
}

func (s *Server) AddEmailSender(ctx context.Context, req gen.AddEmailSenderRequestObject) (gen.AddEmailSenderResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	u := me(ctx)
	if ok, retry := s.em.senderUser.Allow(u.ID.String()); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	email, valid := normEmail(string(req.Body.Email))
	if !valid {
		return nil, problem.Validation("email", "format", "not a valid email address")
	}
	blocked := req.Body.Blocked != nil && *req.Body.Blocked
	var name *string
	if req.Body.DisplayName != nil && strings.TrimSpace(*req.Body.DisplayName) != "" {
		name = ptr(labelOf(*req.Body.DisplayName, "", email))
	}
	existing, err := s.st.EmailSenderByEmail(ctx, s.st.Q(), v.ID, email)
	switch {
	case err == nil && existing.State == "pending":
		// Adding a pending address decides it.
		if blocked {
			if err := s.rejectSender(ctx, &v.EmailAddress, existing.ID, true); err != nil {
				return nil, err
			}
		} else if err := s.approveSender(ctx, &v.EmailAddress, existing.ID, name, true); err != nil {
			return nil, err
		}
		row, err := s.st.GetEmailSender(ctx, s.st.Q(), v.ID, existing.ID)
		if err != nil {
			return nil, err
		}
		return created(emailSenderOut(row)), nil
	case err == nil:
		return nil, problem.New(problem.DuplicateSender, "%s", existing.ID.String())
	case err != store.ErrNotFound:
		return nil, err
	}
	limit := s.Limits(ctx).MaxEmailSendersPerAddress
	row := &store.EmailSender{ID: store.NewID(), AddressID: v.ID, Email: email, DisplayName: name, State: "allowed", Origin: "manual", AddedByUserID: &u.ID}
	if blocked {
		row.State = "blocked"
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockEmailAddress(ctx, v.ID); err != nil {
			return notFoundEmail(err)
		}
		n, err := s.st.CountListedSenders(ctx, tx, v.ID)
		if err != nil {
			return err
		}
		if n >= limit {
			return problem.New(problem.LimitReached, "an email address can have at most %d senders", limit)
		}
		return tx.CreateEmailSender(ctx, row)
	})
	if db.IsUniqueViolation(err, "") {
		if ex, e2 := s.st.EmailSenderByEmail(ctx, s.st.Q(), v.ID, email); e2 == nil {
			return nil, problem.New(problem.DuplicateSender, "%s", ex.ID.String())
		}
	}
	if err != nil {
		return nil, err
	}
	out, err := s.st.GetEmailSender(ctx, s.st.Q(), v.ID, row.ID)
	if err != nil {
		return nil, err
	}
	s.publishSenderDecided(ctx, &v.EmailAddress, row.ID, map[bool]string{true: "blocked", false: "added"}[blocked])
	return created(emailSenderOut(out)), nil
}

func (s *Server) UpdateEmailSender(ctx context.Context, req gen.UpdateEmailSenderRequestObject) (gen.UpdateEmailSenderResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	decision := ""
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		row, err := tx.LockEmailSender(ctx, v.ID, req.SenderId)
		if err != nil {
			return notFoundSender(err)
		}
		if row.State == "pending" {
			return problem.New(problem.InvalidTransition, "a pending sender is approved or rejected, not changed")
		}
		if present, null := rawField(ctx, "display_name"); present {
			if null || req.Body.DisplayName == nil || strings.TrimSpace(*req.Body.DisplayName) == "" {
				row.DisplayName = nil
			} else {
				row.DisplayName = ptr(labelOf(*req.Body.DisplayName, "", row.Email))
			}
		}
		if b := req.Body.Blocked; b != nil {
			switch {
			case *b && row.State != "blocked":
				row.State, decision = "blocked", "blocked"
				if err := tx.RevokeSenderInvite(ctx, row.ID); err != nil {
					return err
				}
			case !*b && row.State == "blocked":
				row.State, decision = "allowed", "unblocked"
			}
		}
		return tx.SaveEmailSender(ctx, row)
	})
	if err != nil {
		return nil, err
	}
	row, err := s.st.GetEmailSender(ctx, s.st.Q(), v.ID, req.SenderId)
	if err != nil {
		return nil, err
	}
	if decision != "" {
		s.publishSenderDecided(ctx, &v.EmailAddress, row.ID, decision)
	}
	return ok(emailSenderOut(row)), nil
}

func (s *Server) RemoveEmailSender(ctx context.Context, req gen.RemoveEmailSenderRequestObject) (gen.RemoveEmailSenderResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	var blobs *store.MediaBlobs
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		row, err := tx.LockEmailSender(ctx, v.ID, req.SenderId)
		if err != nil {
			return notFoundSender(err)
		}
		if row.State == "pending" {
			return problem.New(problem.InvalidTransition, "a pending sender is approved or rejected, not changed")
		}
		blobs, err = tx.DeleteEmailSender(ctx, row)
		return err
	})
	if err != nil {
		return nil, err
	}
	s.deleteBlobs(ctx, blobs.Keys)
	s.publishSenderDecided(ctx, &v.EmailAddress, req.SenderId, "removed")
	return noContent(), nil
}

// ---------------------------------------------------------------- pending senders

func (s *Server) ListEmailPendingSenders(ctx context.Context, req gen.ListEmailPendingSendersRequestObject) (gen.ListEmailPendingSendersResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	rows, err := s.st.PendingSenderRows(ctx, v.ID)
	if err != nil {
		return nil, err
	}
	out := []pendingSenderJSON{}
	for i := range rows {
		p := s.pendingJSON(ctx, &v.EmailAddress, &rows[i])
		if len(p.Messages) == 0 && rows[i].State != "pending" {
			continue
		}
		out = append(out, p)
	}
	return ok(out), nil
}

// approveSender allows a pending sender and (with deliver) delivers their
// authenticated held mail; they get one confirmation.
func (s *Server) approveSender(ctx context.Context, a *store.EmailAddress, senderID uuid.UUID, name *string, deliver bool) error {
	u := me(ctx)
	var row *store.EmailSender
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		var err error
		row, err = tx.LockEmailSender(ctx, a.ID, senderID)
		if err != nil {
			return notFoundSender(err)
		}
		if row.State != "pending" {
			return problem.New(problem.AlreadyDecided, "")
		}
		row.State, row.Origin, row.AddedByUserID = "allowed", "approved", &u.ID
		if name != nil {
			row.DisplayName = name
		}
		return tx.SaveEmailSender(ctx, row)
	})
	if err != nil {
		return err
	}
	var last *emailDecision
	if deliver {
		held, err := s.st.ListHeldMessages(ctx, s.st.Q(), a.ID, &row.ID)
		if err != nil {
			return err
		}
		// Oldest first, so the frame gets them in the order they were mailed.
		for i := len(held) - 1; i >= 0; i-- {
			h := &held[i]
			if h.Reason != "unknown_sender" || !heldAuthenticated(h) {
				continue // unverified mail stays held
			}
			d, err := s.deliverHeld(ctx, a, h, row)
			if err != nil {
				return err
			}
			last = d
		}
	}
	s.publishSenderDecided(ctx, a, row.ID, "approved")
	if last != nil && !last.noReply && a.SendConfirmations {
		s.confirm(ctx, a, last, row.Email, "", "")
	}
	return nil
}

// rejectSender deletes a pending sender's held mail (silently); with block
// their later mail is dropped.
func (s *Server) rejectSender(ctx context.Context, a *store.EmailAddress, senderID uuid.UUID, block bool) error {
	var blobs store.MediaBlobs
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		row, err := tx.LockEmailSender(ctx, a.ID, senderID)
		if err != nil {
			return notFoundSender(err)
		}
		if row.State != "pending" {
			return problem.New(problem.AlreadyDecided, "")
		}
		if block {
			held, err := s.st.ListHeldMessages(ctx, tx, a.ID, &row.ID)
			if err != nil {
				return err
			}
			for i := range held {
				b, err := tx.DeleteHeldMessage(ctx, &held[i], false)
				if err != nil {
					return err
				}
				blobs.Keys = append(blobs.Keys, b.Keys...)
			}
			row.State = "blocked"
			row.AddedByUserID = &me(ctx).ID
			return tx.SaveEmailSender(ctx, row)
		}
		b, err := tx.DeleteEmailSender(ctx, row)
		if err != nil {
			return err
		}
		blobs.Keys = append(blobs.Keys, b.Keys...)
		return nil
	})
	if err != nil {
		return err
	}
	s.deleteBlobs(ctx, blobs.Keys)
	s.publishSenderDecided(ctx, a, senderID, map[bool]string{true: "blocked", false: "rejected"}[block])
	return nil
}

func (s *Server) ApproveEmailSender(ctx context.Context, req gen.ApproveEmailSenderRequestObject) (gen.ApproveEmailSenderResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	deliver := true
	var name *string
	if b := req.Body; b != nil {
		if b.Deliver != nil {
			deliver = *b.Deliver
		}
		if b.DisplayName != nil && strings.TrimSpace(*b.DisplayName) != "" {
			name = ptr(labelOf(*b.DisplayName, "", ""))
		}
	}
	if err := s.approveSender(ctx, &v.EmailAddress, req.SenderId, name, deliver); err != nil {
		return nil, err
	}
	row, err := s.st.GetEmailSender(ctx, s.st.Q(), v.ID, req.SenderId)
	if err != nil {
		return nil, err
	}
	return ok(emailSenderOut(row)), nil
}

func (s *Server) RejectEmailSender(ctx context.Context, req gen.RejectEmailSenderRequestObject) (gen.RejectEmailSenderResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	block := req.Body != nil && req.Body.Block != nil && *req.Body.Block
	if err := s.rejectSender(ctx, &v.EmailAddress, req.SenderId, block); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// ---------------------------------------------------------------- held mail

func notFoundHeld(err error) error {
	if err == store.ErrNotFound {
		return problem.New(problem.NotFound, "held message not found")
	}
	return err
}

func (s *Server) GetEmailHeldMessage(ctx context.Context, req gen.GetEmailHeldMessageRequestObject) (gen.GetEmailHeldMessageResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	h, err := s.st.GetHeldMessage(ctx, s.st.Q(), v.ID, req.MessageId)
	if err != nil {
		return nil, notFoundHeld(err)
	}
	return ok(s.heldJSON(ctx, h)), nil
}

// dropEmptyPending removes a pending sender whose held mail is all gone (§5.2).
func (s *Server) dropEmptyPending(ctx context.Context, a *store.EmailAddress, senderID uuid.UUID) {
	_ = s.st.InTx(ctx, func(tx *store.Tx) error {
		row, err := tx.LockEmailSender(ctx, a.ID, senderID)
		if err != nil || row.State != "pending" {
			return nil
		}
		if n, err := s.st.CountHeldMessages(ctx, tx, a.ID, &senderID); err != nil || n > 0 {
			return err
		}
		_, err = tx.DeleteEmailSender(ctx, row)
		return err
	})
}

func (s *Server) DeleteEmailHeldMessage(ctx context.Context, req gen.DeleteEmailHeldMessageRequestObject) (gen.DeleteEmailHeldMessageResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	var blobs *store.MediaBlobs
	var senderID uuid.UUID
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		h, err := tx.LockHeldMessage(ctx, v.ID, req.MessageId)
		if err != nil {
			return notFoundHeld(err)
		}
		senderID = h.SenderID
		blobs, err = tx.DeleteHeldMessage(ctx, h, false)
		return err
	})
	if err != nil {
		return nil, err
	}
	s.deleteBlobs(ctx, blobs.Keys)
	s.dropEmptyPending(ctx, &v.EmailAddress, senderID)
	s.publishEmailAddress(ctx, v.ID)
	return noContent(), nil
}

func (s *Server) DeliverEmailHeldMessage(ctx context.Context, req gen.DeliverEmailHeldMessageRequestObject) (gen.DeliverEmailHeldMessageResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	h, err := s.st.GetHeldMessage(ctx, s.st.Q(), v.ID, req.MessageId)
	if err != nil {
		return nil, notFoundHeld(err)
	}
	row, _ := s.st.GetEmailSender(ctx, s.st.Q(), v.ID, h.SenderID)
	if row != nil && row.State != "allowed" {
		row = nil // delivering one mail allows nobody (§5.3)
	}
	// One manager at a time: whoever locks it first delivers it.
	if err := s.st.InTx(ctx, func(tx *store.Tx) error {
		_, err := tx.LockHeldMessage(ctx, v.ID, req.MessageId)
		return notFoundHeld(err)
	}); err != nil {
		return nil, err
	}
	a := v.EmailAddress
	d, err := s.deliverHeld(ctx, &a, h, row)
	if err != nil {
		return nil, err
	}
	s.dropEmptyPending(ctx, &a, h.SenderID)
	s.publishEmailAddress(ctx, a.ID)
	if d.items > 0 && !d.noReply && a.SendConfirmations {
		s.confirm(ctx, &a, d, h.SenderEmail, deref(h.Subject), "")
	}
	m := d.msg
	if fresh, err := s.st.LatestSenderMessage(ctx, a.ID, h.SenderEmail); err == nil && fresh.ID == m.ID {
		m = fresh
	}
	return ok(emailMessageOut(m)), nil
}

// ---------------------------------------------------------------- recent mail

func (s *Server) ListEmailMessages(ctx context.Context, req gen.ListEmailMessagesRequestObject) (gen.ListEmailMessagesResponseObject, error) {
	v, err := s.managedAddress(ctx, req.AddressId)
	if err != nil {
		return nil, err
	}
	limit := pageLimit(req.Params.Limit)
	cur := ""
	if req.Params.Cursor != nil {
		cur = *req.Params.Cursor
	}
	ms, next, err := s.st.ListEmailMessages(ctx, v.ID, cur, limit)
	if err != nil {
		return nil, problem.Validation("cursor", "invalid", "malformed request body")
	}
	items := make([]emailMessageJSON, len(ms))
	for i := range ms {
		items[i] = emailMessageOut(&ms[i])
	}
	out := map[string]any{"items": items}
	if next != "" {
		out["next_cursor"] = next
	}
	return ok(out), nil
}
