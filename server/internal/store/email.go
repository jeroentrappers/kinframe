package store

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Email to frame (docs/spec/email-inbox.md §16): inbox addresses, their
// senders, the inbound queue, held mail, the 30-day message log and reply
// recipients.

// ---------------------------------------------------------------- addresses

type EmailAddress struct {
	ID                uuid.UUID  `db:"id"`
	LocalPart         string     `db:"local_part"`
	Slug              string     `db:"slug"`
	Random            string     `db:"random"`
	Name              string     `db:"name"`
	FrameID           *uuid.UUID `db:"frame_id"`
	FeedID            *uuid.UUID `db:"feed_id"`
	OwnerUserID       *uuid.UUID `db:"owner_user_id"`
	CreatedByUserID   *uuid.UUID `db:"created_by_user_id"`
	Status            string     `db:"status"`
	AnyoneMaySend     bool       `db:"anyone_may_send"`
	SendConfirmations bool       `db:"send_confirmations"`
	OfferAppInvite    bool       `db:"offer_app_invite"`
	NotifyReactions   bool       `db:"notify_reactions"`
	LastReceivedAt    *time.Time `db:"last_received_at"`
	RegeneratedAt     *time.Time `db:"regenerated_at"`
	CreatedAt         time.Time  `db:"created_at"`
	UpdatedAt         time.Time  `db:"updated_at"`
}

// EmailAddressView is an address with its target's and owner's names and counts.
type EmailAddressView struct {
	EmailAddress
	FrameName      *string `db:"frame_name"`
	FeedName       *string `db:"feed_name"`
	OwnerName      *string `db:"owner_name"`
	OwnerAvatarKey *string `db:"owner_avatar_key"`
	Allowed        int     `db:"allowed_senders"`
	Blocked        int     `db:"blocked_senders"`
	Pending        int     `db:"pending_senders"`
	Held           int     `db:"held_messages"`
	Delivered30d   int     `db:"delivered_30d"`
}

const emailAddressCols = `a.id, a.local_part, a.slug, a.random, a.name, a.frame_id, a.feed_id, a.owner_user_id, a.created_by_user_id,
	a.status, a.anyone_may_send, a.send_confirmations, a.offer_app_invite, a.notify_reactions, a.last_received_at, a.regenerated_at,
	a.created_at, a.updated_at`

// Addresses of deleted frames or feeds are dead (the rows go with the hard delete).
const emailAddressLive = ` LEFT JOIN frames fr ON fr.id = a.frame_id LEFT JOIN feeds fd ON fd.id = a.feed_id
	WHERE ((a.frame_id IS NOT NULL AND fr.deleted_at IS NULL) OR (a.feed_id IS NOT NULL AND fd.deleted_at IS NULL))`

const emailAddressViewSelect = `SELECT ` + emailAddressCols + `, fr.name AS frame_name, fd.name AS feed_name,
	u.display_name AS owner_name, u.avatar_key AS owner_avatar_key,
	(SELECT count(*) FROM email_senders s WHERE s.address_id = a.id AND s.state = 'allowed')::int AS allowed_senders,
	(SELECT count(*) FROM email_senders s WHERE s.address_id = a.id AND s.state = 'blocked')::int AS blocked_senders,
	(SELECT count(*) FROM email_senders s WHERE s.address_id = a.id AND s.state = 'pending')::int AS pending_senders,
	(SELECT count(*) FROM email_held_messages h WHERE h.address_id = a.id)::int AS held_messages,
	(SELECT count(*) FROM email_messages m WHERE m.address_id = a.id AND m.outcome = 'delivered'
		AND m.received_at > now() - interval '30 days')::int AS delivered_30d
	FROM email_addresses a LEFT JOIN users u ON u.id = a.owner_user_id` + emailAddressLive

// LocalPartUsed reports whether a local part is live or retired.
func (s *Store) LocalPartUsed(ctx context.Context, q db.DBTX, lp string) (bool, error) {
	var used bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM email_addresses WHERE local_part = $1)
		OR EXISTS (SELECT 1 FROM email_retired_local_parts WHERE local_part = $1)`, lp).Scan(&used)
	return used, err
}

func (tx *Tx) CreateEmailAddress(ctx context.Context, a EmailAddress) error {
	_, err := tx.Exec(ctx, `INSERT INTO email_addresses (id, local_part, slug, random, name, frame_id, feed_id, owner_user_id,
		created_by_user_id, anyone_may_send, send_confirmations, offer_app_invite, notify_reactions)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)`,
		a.ID, a.LocalPart, a.Slug, a.Random, a.Name, a.FrameID, a.FeedID, a.OwnerUserID, a.CreatedByUserID,
		a.AnyoneMaySend, a.SendConfirmations, a.OfferAppInvite, a.NotifyReactions)
	return err
}

func (s *Store) GetEmailAddressView(ctx context.Context, q db.DBTX, id uuid.UUID) (*EmailAddressView, error) {
	return one[EmailAddressView](q.Query(ctx, emailAddressViewSelect+` AND a.id = $1`, id))
}

// ListEmailAddresses lists a frame's or a feed's addresses, oldest first.
func (s *Store) ListEmailAddresses(ctx context.Context, frameID, feedID *uuid.UUID) ([]EmailAddressView, error) {
	return many[EmailAddressView](s.Pool.Query(ctx, emailAddressViewSelect+`
		AND ($1::uuid IS NULL OR a.frame_id = $1) AND ($2::uuid IS NULL OR a.feed_id = $2) ORDER BY a.created_at, a.id`, frameID, feedID))
}

func (s *Store) CountEmailAddresses(ctx context.Context, q db.DBTX, frameID, feedID *uuid.UUID) (int, error) {
	var n int
	err := q.QueryRow(ctx, `SELECT count(*) FROM email_addresses WHERE ($1::uuid IS NULL OR frame_id = $1) AND ($2::uuid IS NULL OR feed_id = $2)`,
		frameID, feedID).Scan(&n)
	return n, err
}

// EmailAddressByLocalPart finds a live address by its local part (lower case).
func (s *Store) EmailAddressByLocalPart(ctx context.Context, q db.DBTX, lp string) (*EmailAddress, error) {
	return one[EmailAddress](q.Query(ctx, `SELECT `+emailAddressCols+` FROM email_addresses a`+emailAddressLive+` AND a.local_part = $1`, lp))
}

// LockEmailAddress returns a live address, locked for the transaction.
func (tx *Tx) LockEmailAddress(ctx context.Context, id uuid.UUID) (*EmailAddress, error) {
	if _, err := tx.Exec(ctx, `SELECT 1 FROM email_addresses WHERE id = $1 FOR UPDATE`, id); err != nil {
		return nil, err
	}
	return one[EmailAddress](tx.Query(ctx, `SELECT `+emailAddressCols+` FROM email_addresses a`+emailAddressLive+` AND a.id = $1`, id))
}

// SaveEmailAddress writes the mutable fields.
func (tx *Tx) SaveEmailAddress(ctx context.Context, a *EmailAddress) error {
	_, err := tx.Exec(ctx, `UPDATE email_addresses SET name = $2, owner_user_id = $3, status = $4, anyone_may_send = $5,
		send_confirmations = $6, offer_app_invite = $7, notify_reactions = $8, updated_at = now() WHERE id = $1`,
		a.ID, a.Name, a.OwnerUserID, a.Status, a.AnyoneMaySend, a.SendConfirmations, a.OfferAppInvite, a.NotifyReactions)
	return err
}

// ChangeLocalPart gives an address a new local part and retires the old one at once.
func (tx *Tx) ChangeLocalPart(ctx context.Context, a *EmailAddress, slug, random string, regenerated bool) error {
	if _, err := tx.Exec(ctx, `INSERT INTO email_retired_local_parts (local_part) VALUES ($1) ON CONFLICT DO NOTHING`, a.LocalPart); err != nil {
		return err
	}
	lp := slug + "-" + random
	if _, err := tx.Exec(ctx, `UPDATE email_addresses SET local_part = $2, slug = $3, random = $4, updated_at = now(),
		regenerated_at = CASE WHEN $5 THEN now() ELSE regenerated_at END WHERE id = $1`, a.ID, lp, slug, random, regenerated); err != nil {
		return err
	}
	a.LocalPart, a.Slug, a.Random = lp, slug, random
	return nil
}

// TouchEmailAddress records the last mail time.
func (s *Store) TouchEmailAddress(ctx context.Context, id uuid.UUID, at time.Time) error {
	_, err := s.Pool.Exec(ctx, `UPDATE email_addresses SET last_received_at = GREATEST(COALESCE(last_received_at, $2), $2) WHERE id = $1`, id, at)
	return err
}

// HeldMedia is a processed file of held mail without an item.
type HeldMedia struct {
	MediaID      uuid.UUID  `json:"media_id"`
	Kind         string     `json:"kind"`
	Width        *int       `json:"width,omitempty"`
	Height       *int       `json:"height,omitempty"`
	DurationMS   *int       `json:"duration_ms,omitempty"`
	Bytes        int64      `json:"bytes"`
	Filename     *string    `json:"filename,omitempty"`
	CapturedHint *time.Time `json:"captured_hint,omitempty"`
	SHA256       string     `json:"sha256"`
	ThumbKey     *string    `json:"thumb_key,omitempty"`
}

// MediaBlobs are the stored files of media rows that go away with them.
type MediaBlobs struct {
	Keys []string
}

// deleteHeldMedia deletes the given media rows (held mail never shares them)
// unless an item uses them, and returns their blob keys.
func (tx *Tx) deleteHeldMedia(ctx context.Context, hm []HeldMedia, out *MediaBlobs) error {
	if len(hm) == 0 {
		return nil
	}
	ids := make([]uuid.UUID, 0, len(hm))
	for _, m := range hm {
		ids = append(ids, m.MediaID)
		if m.ThumbKey != nil {
			out.Keys = append(out.Keys, *m.ThumbKey)
		}
	}
	rows, err := tx.Query(ctx, `SELECT key FROM renditions WHERE media_id = ANY($1)
		AND NOT EXISTS (SELECT 1 FROM items i WHERE i.media_id = renditions.media_id OR i.motion_media_id = renditions.media_id)
		UNION ALL SELECT k FROM media m, LATERAL (VALUES (m.master_key), (m.poster_key)) v(k) WHERE m.id = ANY($1) AND k IS NOT NULL
		AND NOT EXISTS (SELECT 1 FROM items i WHERE i.media_id = m.id OR i.motion_media_id = m.id)`, ids)
	if err != nil {
		return err
	}
	keys, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return err
	}
	out.Keys = append(out.Keys, keys...)
	_, err = tx.Exec(ctx, `DELETE FROM media m WHERE m.id = ANY($1)
		AND NOT EXISTS (SELECT 1 FROM items i WHERE i.media_id = m.id OR i.motion_media_id = m.id)`, ids)
	return err
}

// DeleteEmailAddress removes an address: its local part is retired, its held
// mail (and their media) and its email join links go with it.
func (tx *Tx) DeleteEmailAddress(ctx context.Context, a *EmailAddress) (*MediaBlobs, error) {
	out := &MediaBlobs{}
	held, err := many[HeldMessage](tx.Query(ctx, heldSelect+` WHERE h.address_id = $1`, a.ID))
	if err != nil {
		return nil, err
	}
	for _, h := range held {
		if err := tx.deleteHeldMedia(ctx, h.Media, out); err != nil {
			return nil, err
		}
	}
	if err := tx.revokeSenderInvites(ctx, `SELECT id::text FROM email_senders WHERE address_id = $1`, a.ID); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO email_retired_local_parts (local_part) VALUES ($1) ON CONFLICT DO NOTHING`, a.LocalPart); err != nil {
		return nil, err
	}
	_, err = tx.Exec(ctx, `DELETE FROM email_addresses WHERE id = $1`, a.ID)
	return out, err
}

// RetireTargetAddresses retires and deletes the addresses of a frame or feed
// that is being removed (their rows would otherwise go with the cascade,
// leaving their local parts free).
func (tx *Tx) RetireTargetAddresses(ctx context.Context, frameID, feedID *uuid.UUID) ([]EmailAddress, *MediaBlobs, error) {
	as, err := many[EmailAddress](tx.Query(ctx, `SELECT `+emailAddressCols+` FROM email_addresses a
		WHERE ($1::uuid IS NOT NULL AND a.frame_id = $1) OR ($2::uuid IS NOT NULL AND a.feed_id = $2)`, frameID, feedID))
	if err != nil {
		return nil, nil, err
	}
	out := &MediaBlobs{}
	for i := range as {
		b, err := tx.DeleteEmailAddress(ctx, &as[i])
		if err != nil {
			return nil, nil, err
		}
		out.Keys = append(out.Keys, b.Keys...)
	}
	return as, out, nil
}

// revokeSenderInvites revokes the live email join links of the senders the
// subquery selects (ids as text, $1 its argument).
func (tx *Tx) revokeSenderInvites(ctx context.Context, senders string, arg any) error {
	if _, err := tx.Exec(ctx, `UPDATE invites SET revoked_at = now() WHERE via = 'email' AND revoked_at IS NULL AND via_ref IN (`+senders+`)`, arg); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `UPDATE feed_invites SET revoked_at = now() WHERE via = 'email' AND revoked_at IS NULL AND via_ref IN (`+senders+`)`, arg)
	return err
}

// ---------------------------------------------------------------- managers and owners

// EmailFrameManagerIDs: owner, admins and members with change_settings (email-inbox.md §4.1).
func (s *Store) EmailFrameManagerIDs(ctx context.Context, q db.DBTX, frameID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT user_id FROM memberships WHERE frame_id = $1
		AND (role IN ('owner', 'admin') OR (permissions->>'change_settings')::boolean) ORDER BY user_id`, frameID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// FeedOwnerID returns the feed's owner.
func (s *Store) FeedOwnerID(ctx context.Context, q db.DBTX, feedID uuid.UUID) (uuid.UUID, error) {
	var id uuid.UUID
	err := q.QueryRow(ctx, `SELECT user_id FROM feed_members WHERE feed_id = $1 AND role = 'owner'`, feedID).Scan(&id)
	if db.NotFound(err) {
		return uuid.Nil, ErrNotFound
	}
	return id, err
}

// VerifiedAccountByEmail returns the live account whose verified email is
// email (compared case-insensitively), if exactly one has it.
func (s *Store) VerifiedAccountByEmail(ctx context.Context, q db.DBTX, email string) (*User, error) {
	return one[User](q.Query(ctx, `SELECT `+userCols+` FROM users WHERE email_verified AND deleted_at IS NULL AND lower(email) = lower($1)`, email))
}

// UsesApp reports whether the account has an app push endpoint or used an
// app recently (no app block in replies, §10.4).
func (s *Store) UsesApp(ctx context.Context, userID uuid.UUID) (bool, error) {
	var yes bool
	err := s.Pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM push_endpoints WHERE user_id = $1 AND kind IN ('unifiedpush', 'fcm', 'apns'))`,
		userID).Scan(&yes)
	return yes, err
}

// ---------------------------------------------------------------- senders

type EmailSender struct {
	ID                uuid.UUID  `db:"id"`
	AddressID         uuid.UUID  `db:"address_id"`
	Email             string     `db:"email"`
	DisplayName       *string    `db:"display_name"`
	State             string     `db:"state"`
	Origin            string     `db:"origin"`
	AddedByUserID     *uuid.UUID `db:"added_by_user_id"`
	AddedByName       *string    `db:"added_by_name"`
	InviteID          *uuid.UUID `db:"invite_id"`
	PendingNotifiedAt *time.Time `db:"pending_notified_at"`
	ExpiringNotified  bool       `db:"expiring_notified"`
	LastMessageAt     *time.Time `db:"last_message_at"`
	CreatedAt         time.Time  `db:"created_at"`
	UpdatedAt         time.Time  `db:"updated_at"`
}

const senderSelect = `SELECT s.id, s.address_id, s.email, s.display_name, s.state, s.origin, s.added_by_user_id,
	u.display_name AS added_by_name, s.invite_id, s.pending_notified_at, s.expiring_notified, s.last_message_at, s.created_at, s.updated_at
	FROM email_senders s LEFT JOIN users u ON u.id = s.added_by_user_id`

// ListEmailSenders lists an address's senders in the given states, by email.
func (s *Store) ListEmailSenders(ctx context.Context, q db.DBTX, addressID uuid.UUID, states ...string) ([]EmailSender, error) {
	return many[EmailSender](q.Query(ctx, senderSelect+` WHERE s.address_id = $1 AND s.state = ANY($2) ORDER BY s.email`, addressID, states))
}

func (s *Store) GetEmailSender(ctx context.Context, q db.DBTX, addressID, id uuid.UUID) (*EmailSender, error) {
	return one[EmailSender](q.Query(ctx, senderSelect+` WHERE s.address_id = $1 AND s.id = $2`, addressID, id))
}

func (s *Store) EmailSenderByEmail(ctx context.Context, q db.DBTX, addressID uuid.UUID, email string) (*EmailSender, error) {
	return one[EmailSender](q.Query(ctx, senderSelect+` WHERE s.address_id = $1 AND s.email = $2`, addressID, strings.ToLower(email)))
}

// LockEmailSender locks a sender row.
func (tx *Tx) LockEmailSender(ctx context.Context, addressID, id uuid.UUID) (*EmailSender, error) {
	if _, err := tx.Exec(ctx, `SELECT 1 FROM email_senders WHERE id = $1 FOR UPDATE`, id); err != nil {
		return nil, err
	}
	return one[EmailSender](tx.Query(ctx, senderSelect+` WHERE s.address_id = $1 AND s.id = $2`, addressID, id))
}

// CountListedSenders counts allowed and blocked senders.
func (s *Store) CountListedSenders(ctx context.Context, q db.DBTX, addressID uuid.UUID) (int, error) {
	var n int
	err := q.QueryRow(ctx, `SELECT count(*) FROM email_senders WHERE address_id = $1 AND state IN ('allowed', 'blocked')`, addressID).Scan(&n)
	return n, err
}

func (tx *Tx) CreateEmailSender(ctx context.Context, e *EmailSender) error {
	e.Email = strings.ToLower(e.Email)
	_, err := tx.Exec(ctx, `INSERT INTO email_senders (id, address_id, email, display_name, state, origin, added_by_user_id, last_message_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`, e.ID, e.AddressID, e.Email, e.DisplayName, e.State, e.Origin, e.AddedByUserID, e.LastMessageAt)
	return err
}

// SaveEmailSender writes the mutable fields.
func (tx *Tx) SaveEmailSender(ctx context.Context, e *EmailSender) error {
	_, err := tx.Exec(ctx, `UPDATE email_senders SET display_name = $2, state = $3, origin = $4, added_by_user_id = $5, invite_id = $6,
		pending_notified_at = $7, expiring_notified = $8, last_message_at = $9, updated_at = now() WHERE id = $1`,
		e.ID, e.DisplayName, e.State, e.Origin, e.AddedByUserID, e.InviteID, e.PendingNotifiedAt, e.ExpiringNotified, e.LastMessageAt)
	return err
}

// DeleteEmailSender removes a sender with their held mail and live join link.
func (tx *Tx) DeleteEmailSender(ctx context.Context, e *EmailSender) (*MediaBlobs, error) {
	out := &MediaBlobs{}
	held, err := many[HeldMessage](tx.Query(ctx, heldSelect+` WHERE h.sender_id = $1`, e.ID))
	if err != nil {
		return nil, err
	}
	for _, h := range held {
		if err := tx.deleteHeldMedia(ctx, h.Media, out); err != nil {
			return nil, err
		}
	}
	if err := tx.RevokeSenderInvite(ctx, e.ID); err != nil {
		return nil, err
	}
	_, err = tx.Exec(ctx, `DELETE FROM email_senders WHERE id = $1`, e.ID)
	return out, err
}

// RevokeSenderInvite revokes a sender's live email join link.
func (tx *Tx) RevokeSenderInvite(ctx context.Context, senderID uuid.UUID) error {
	return tx.revokeSenderInvites(ctx, `$1::text`, senderID.String())
}

// LiveSenderInvite returns the sender's usable join link token (frame or feed invite), if any.
func (s *Store) LiveSenderInvite(ctx context.Context, q db.DBTX, senderID uuid.UUID, feed bool) (string, time.Time, bool, error) {
	table := "invites"
	if feed {
		table = "feed_invites"
	}
	var tok string
	var exp time.Time
	err := q.QueryRow(ctx, `SELECT token, expires_at FROM `+table+` WHERE via = 'email' AND via_ref = $1 AND revoked_at IS NULL
		AND expires_at > now() AND (max_uses IS NULL OR uses < max_uses) ORDER BY expires_at DESC LIMIT 1`, senderID.String()).Scan(&tok, &exp)
	if db.NotFound(err) {
		return "", time.Time{}, false, nil
	}
	return tok, exp, err == nil, err
}

// ---------------------------------------------------------------- held mail

type HeldMessage struct {
	ID          uuid.UUID       `db:"id"`
	AddressID   uuid.UUID       `db:"address_id"`
	SenderID    uuid.UUID       `db:"sender_id"`
	Reason      string          `db:"reason"`
	MessageKey  string          `db:"message_key"`
	SenderEmail string          `db:"sender_email"`
	SenderName  *string         `db:"sender_name"`
	Subject     *string         `db:"subject"`
	Caption     *string         `db:"caption"`
	Auth        json.RawMessage `db:"auth"`
	Media       []HeldMedia     `db:"media"`
	Skipped     map[string]int  `db:"skipped"`
	ReceivedAt  time.Time       `db:"received_at"`
	ExpiresAt   time.Time       `db:"expires_at"`
}

const heldSelect = `SELECT h.id, h.address_id, h.sender_id, h.reason, h.message_key, h.sender_email, h.sender_name, h.subject, h.caption,
	h.auth, h.media, h.skipped, h.received_at, h.expires_at FROM email_held_messages h`

// CreateHeldMessage stores held mail; its media rows point back at it.
func (tx *Tx) CreateHeldMessage(ctx context.Context, h *HeldMessage) error {
	if _, err := tx.Exec(ctx, `INSERT INTO email_held_messages (id, address_id, sender_id, reason, message_key, sender_email, sender_name,
		subject, caption, auth, media, skipped, received_at, expires_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)`,
		h.ID, h.AddressID, h.SenderID, h.Reason, h.MessageKey, h.SenderEmail, h.SenderName, h.Subject, h.Caption, h.Auth, h.Media,
		h.Skipped, h.ReceivedAt, h.ExpiresAt); err != nil {
		return err
	}
	ids := make([]uuid.UUID, len(h.Media))
	for i, m := range h.Media {
		ids[i] = m.MediaID
	}
	_, err := tx.Exec(ctx, `UPDATE media SET held_message_id = $1 WHERE id = ANY($2)`, h.ID, ids)
	return err
}

// ListHeldMessages lists held mail of an address (or one sender), newest first.
func (s *Store) ListHeldMessages(ctx context.Context, q db.DBTX, addressID uuid.UUID, senderID *uuid.UUID) ([]HeldMessage, error) {
	return many[HeldMessage](q.Query(ctx, heldSelect+` WHERE h.address_id = $1 AND ($2::uuid IS NULL OR h.sender_id = $2)
		ORDER BY h.received_at DESC, h.id DESC`, addressID, senderID))
}

func (s *Store) GetHeldMessage(ctx context.Context, q db.DBTX, addressID, id uuid.UUID) (*HeldMessage, error) {
	return one[HeldMessage](q.Query(ctx, heldSelect+` WHERE h.address_id = $1 AND h.id = $2`, addressID, id))
}

// LockHeldMessage locks one held mail.
func (tx *Tx) LockHeldMessage(ctx context.Context, addressID, id uuid.UUID) (*HeldMessage, error) {
	return one[HeldMessage](tx.Query(ctx, heldSelect+` WHERE h.address_id = $1 AND h.id = $2 FOR UPDATE`, addressID, id))
}

// DeleteHeldMessage deletes held mail. With keepMedia its media become
// ordinary (delivered: items use them); otherwise they are deleted.
func (tx *Tx) DeleteHeldMessage(ctx context.Context, h *HeldMessage, keepMedia bool) (*MediaBlobs, error) {
	out := &MediaBlobs{}
	if keepMedia {
		for _, m := range h.Media {
			if m.ThumbKey != nil {
				out.Keys = append(out.Keys, *m.ThumbKey)
			}
		}
	} else if err := tx.deleteHeldMedia(ctx, h.Media, out); err != nil {
		return nil, err
	}
	_, err := tx.Exec(ctx, `DELETE FROM email_held_messages WHERE id = $1`, h.ID)
	return out, err
}

func (s *Store) CountHeldMessages(ctx context.Context, q db.DBTX, addressID uuid.UUID, senderID *uuid.UUID) (int, error) {
	var n int
	err := q.QueryRow(ctx, `SELECT count(*) FROM email_held_messages WHERE address_id = $1 AND ($2::uuid IS NULL OR sender_id = $2)`,
		addressID, senderID).Scan(&n)
	return n, err
}

// OldestHeldMessage returns the oldest held mail of an address (or one sender).
func (s *Store) OldestHeldMessage(ctx context.Context, q db.DBTX, addressID uuid.UUID, senderID *uuid.UUID) (*HeldMessage, error) {
	return one[HeldMessage](q.Query(ctx, heldSelect+` WHERE h.address_id = $1 AND ($2::uuid IS NULL OR h.sender_id = $2)
		ORDER BY h.received_at, h.id LIMIT 1`, addressID, senderID))
}

// HeldKeyExists: the message key is already held for the address.
func (s *Store) HeldKeyExists(ctx context.Context, q db.DBTX, addressID uuid.UUID, key string) (bool, error) {
	var yes bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM email_held_messages WHERE address_id = $1 AND message_key = $2)`, addressID, key).Scan(&yes)
	return yes, err
}

// ExpiringPending lists pending senders whose oldest held mail expires before
// t and who were not reminded yet.
func (s *Store) ExpiringPending(ctx context.Context, before time.Time) ([]EmailSender, error) {
	return many[EmailSender](s.Pool.Query(ctx, senderSelect+` WHERE s.state = 'pending' AND NOT s.expiring_notified
		AND EXISTS (SELECT 1 FROM email_held_messages h WHERE h.sender_id = s.id AND h.expires_at < $1)`, before))
}

// ---------------------------------------------------------------- message log

type EmailMessage struct {
	ID            uuid.UUID      `db:"id"`
	AddressID     uuid.UUID      `db:"address_id"`
	MessageKey    string         `db:"message_key"`
	SenderEmail   *string        `db:"sender_email"`
	SenderName    *string        `db:"sender_name"`
	Authenticated bool           `db:"authenticated"`
	Outcome       string         `db:"outcome"`
	Items         int            `db:"items"`
	Skipped       map[string]int `db:"skipped"`
	SendIDs       []uuid.UUID    `db:"send_ids"`
	ReplyKind     *string        `db:"reply_kind"`
	RepliedAt     *time.Time     `db:"replied_at"`
	ReceivedAt    time.Time      `db:"received_at"`
}

const emailMessageCols = `id, address_id, message_key, sender_email, sender_name, authenticated, outcome, items, skipped, send_ids,
	reply_kind, replied_at, received_at`

func (s *Store) InsertEmailMessage(ctx context.Context, q db.DBTX, m *EmailMessage) error {
	if m.Skipped == nil {
		m.Skipped = map[string]int{}
	}
	if m.SendIDs == nil {
		m.SendIDs = []uuid.UUID{}
	}
	_, err := q.Exec(ctx, `INSERT INTO email_messages (id, address_id, message_key, sender_email, sender_name, authenticated, outcome,
		items, skipped, send_ids, reply_kind, replied_at, received_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)`,
		m.ID, m.AddressID, m.MessageKey, m.SenderEmail, m.SenderName, m.Authenticated, m.Outcome, m.Items, m.Skipped, m.SendIDs,
		m.ReplyKind, m.RepliedAt, m.ReceivedAt)
	return err
}

// SetEmailReply records the reply sent for a logged message.
func (s *Store) SetEmailReply(ctx context.Context, id uuid.UUID, kind string, at time.Time) error {
	_, err := s.Pool.Exec(ctx, `UPDATE email_messages SET reply_kind = $2, replied_at = $3 WHERE id = $1`, id, kind, at)
	return err
}

// ListEmailMessages pages an address's log, newest first.
func (s *Store) ListEmailMessages(ctx context.Context, addressID uuid.UUID, cur string, limit int) ([]EmailMessage, string, error) {
	var c *cursor
	if cur != "" {
		var err error
		if c, err = decodeCursor(cur); err != nil {
			return nil, "", err
		}
	}
	var t *time.Time
	var id *uuid.UUID
	if c != nil {
		t, id = &c.T, &c.ID
	}
	ms, err := many[EmailMessage](s.Pool.Query(ctx, `SELECT `+emailMessageCols+` FROM email_messages WHERE address_id = $1
		AND received_at > now() - interval '30 days'
		AND ($2::timestamptz IS NULL OR (received_at, id) < ($2, $3)) ORDER BY received_at DESC, id DESC LIMIT $4`, addressID, t, id, limit+1))
	if err != nil {
		return nil, "", err
	}
	next := ""
	if len(ms) > limit {
		ms = ms[:limit]
		last := ms[len(ms)-1]
		next = encodeCursor(last.ReceivedAt, last.ID)
	}
	return ms, next, nil
}

// MessageKeySeen: the key was delivered or held for the address in the last 30 days.
func (s *Store) MessageKeySeen(ctx context.Context, q db.DBTX, addressID uuid.UUID, key string) (bool, error) {
	var yes bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM email_messages WHERE address_id = $1 AND message_key = $2
		AND outcome IN ('delivered', 'held') AND received_at > now() - interval '30 days')
		OR EXISTS (SELECT 1 FROM email_held_messages WHERE address_id = $1 AND message_key = $2)`, addressID, key).Scan(&yes)
	return yes, err
}

// CountEmailMessagesSince counts logged mail of an address since t.
func (s *Store) CountEmailMessagesSince(ctx context.Context, addressID uuid.UUID, since time.Time) (int, error) {
	var n int
	err := s.Pool.QueryRow(ctx, `SELECT count(*) FROM email_messages WHERE address_id = $1 AND received_at >= $2`, addressID, since).Scan(&n)
	return n, err
}

// RepliesSince counts replies of the given kinds (all when empty) to a
// sender (all senders when empty) on an address (all when nil) since t.
func (s *Store) RepliesSince(ctx context.Context, addressID *uuid.UUID, sender string, kinds []string, since time.Time) (int, error) {
	var n int
	err := s.Pool.QueryRow(ctx, `SELECT count(*) FROM email_messages WHERE replied_at >= $1
		AND ($2::uuid IS NULL OR address_id = $2) AND ($3 = '' OR sender_email = $3) AND (cardinality($4::text[]) = 0 OR reply_kind = ANY($4))`,
		since, addressID, sender, kinds).Scan(&n)
	return n, err
}

// UnconfirmedDeliveries returns delivered mail of a sender to an address
// since t that no reply confirmed yet (coalesced confirmations, §10.3).
func (s *Store) UnconfirmedDeliveries(ctx context.Context, addressID uuid.UUID, sender string, since time.Time) ([]EmailMessage, error) {
	return many[EmailMessage](s.Pool.Query(ctx, `SELECT `+emailMessageCols+` FROM email_messages WHERE address_id = $1 AND sender_email = $2
		AND outcome = 'delivered' AND reply_kind IS NULL AND authenticated AND received_at >= $3 ORDER BY received_at`, addressID, sender, since))
}

// MarkConfirmed records that a delivered reply covered these messages.
func (s *Store) MarkConfirmed(ctx context.Context, ids []uuid.UUID, at time.Time) error {
	_, err := s.Pool.Exec(ctx, `UPDATE email_messages SET reply_kind = 'delivered', replied_at = $2 WHERE id = ANY($1)`, ids, at)
	return err
}

// ---------------------------------------------------------------- reply recipients

type ReplyRecipient struct {
	Email          string     `db:"email"`
	AppIntroSentAt *time.Time `db:"app_intro_sent_at"`
	LastReplyAt    time.Time  `db:"last_reply_at"`
}

func (s *Store) GetReplyRecipient(ctx context.Context, email string) (*ReplyRecipient, error) {
	return one[ReplyRecipient](s.Pool.Query(ctx, `SELECT email, app_intro_sent_at, last_reply_at FROM email_reply_recipients WHERE email = $1`,
		strings.ToLower(email)))
}

// RecordReply notes a reply to a sender address (and whether it had the long app intro).
func (s *Store) RecordReply(ctx context.Context, email string, intro bool, at time.Time) error {
	var introAt *time.Time
	if intro {
		introAt = &at
	}
	_, err := s.Pool.Exec(ctx, `INSERT INTO email_reply_recipients (email, app_intro_sent_at, last_reply_at) VALUES ($1, $2, $3)
		ON CONFLICT (email) DO UPDATE SET last_reply_at = EXCLUDED.last_reply_at,
			app_intro_sent_at = COALESCE(email_reply_recipients.app_intro_sent_at, EXCLUDED.app_intro_sent_at)`,
		strings.ToLower(email), introAt, at)
	return err
}

// ---------------------------------------------------------------- inbound queue

type InboundMail struct {
	ID            uuid.UUID       `db:"id"`
	SpoolPath     string          `db:"spool_path"`
	Path          string          `db:"path"`
	EnvelopeFrom  *string         `db:"envelope_from"`
	EnvelopeTo    []string        `db:"envelope_to"`
	ClientIP      *string         `db:"client_ip"`
	Helo          *string         `db:"helo"`
	SizeBytes     int64           `db:"size_bytes"`
	Auth          json.RawMessage `db:"auth"`
	Attempts      int             `db:"attempts"`
	NextAttemptAt time.Time       `db:"next_attempt_at"`
	ReceivedAt    time.Time       `db:"received_at"`
}

const inboundCols = `id, spool_path, path, envelope_from, envelope_to, host(client_ip) AS client_ip, helo, size_bytes, auth, attempts,
	next_attempt_at, received_at`

func (s *Store) InsertInbound(ctx context.Context, m *InboundMail) error {
	var auth any
	if len(m.Auth) > 0 {
		auth = m.Auth
	}
	return s.Pool.QueryRow(ctx, `INSERT INTO email_inbound (id, spool_path, path, envelope_from, envelope_to, client_ip, helo, size_bytes, auth)
		VALUES ($1, $2, $3, $4, $5, $6::inet, $7, $8, $9) RETURNING received_at`,
		m.ID, m.SpoolPath, m.Path, m.EnvelopeFrom, m.EnvelopeTo, m.ClientIP, m.Helo, m.SizeBytes, auth).Scan(&m.ReceivedAt)
}

// ClaimInbound takes the oldest due queue row and counts an attempt; the
// lease (next_attempt_at) makes a crashed worker's row come back later.
func (s *Store) ClaimInbound(ctx context.Context, lease time.Duration) (*InboundMail, error) {
	return one[InboundMail](s.Pool.Query(ctx, `UPDATE email_inbound SET attempts = attempts + 1, next_attempt_at = now() + $1::interval
		WHERE id = (SELECT id FROM email_inbound WHERE next_attempt_at <= now() ORDER BY received_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
		RETURNING `+inboundCols, lease.String()))
}

// RetryInbound puts a queue row back for later (and stores computed auth results).
func (s *Store) RetryInbound(ctx context.Context, id uuid.UUID, at time.Time) error {
	_, err := s.Pool.Exec(ctx, `UPDATE email_inbound SET next_attempt_at = $2 WHERE id = $1`, id, at)
	return err
}

func (s *Store) DeleteInbound(ctx context.Context, id uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM email_inbound WHERE id = $1`, id)
	return err
}

// ---------------------------------------------------------------- retention

// EmailPruned is what one retention round removed.
type EmailPruned struct {
	Spools    []string    // queue rows older than 24 h: spool files to delete
	Held      int         // expired held mail
	Blobs     MediaBlobs  // their stored files
	Senders   []uuid.UUID // addresses whose pending senders went (no held mail left)
	Messages  int64
	Replies   int64
	Addresses []uuid.UUID // addresses whose counts changed
}

// PruneEmail applies the retention rules of §12.4 / §16.
func (s *Store) PruneEmail(ctx context.Context, now time.Time) (*EmailPruned, error) {
	out := &EmailPruned{}
	rows, err := s.Pool.Query(ctx, `DELETE FROM email_inbound WHERE received_at < $1 RETURNING spool_path`, now.Add(-24*time.Hour))
	if err != nil {
		return nil, err
	}
	if out.Spools, err = pgx.CollectRows(rows, pgx.RowTo[string]); err != nil {
		return nil, err
	}
	err = s.InTx(ctx, func(tx *Tx) error {
		held, err := many[HeldMessage](tx.Query(ctx, heldSelect+` WHERE h.expires_at <= $1 FOR UPDATE`, now))
		if err != nil {
			return err
		}
		seen := map[uuid.UUID]bool{}
		for i := range held {
			b, err := tx.DeleteHeldMessage(ctx, &held[i], false)
			if err != nil {
				return err
			}
			out.Blobs.Keys = append(out.Blobs.Keys, b.Keys...)
			if !seen[held[i].AddressID] {
				seen[held[i].AddressID] = true
				out.Addresses = append(out.Addresses, held[i].AddressID)
			}
		}
		out.Held = len(held)
		// Pending senders without held mail go back to unknown (§5.2).
		rows, err := tx.Query(ctx, `DELETE FROM email_senders s WHERE s.state = 'pending'
			AND NOT EXISTS (SELECT 1 FROM email_held_messages h WHERE h.sender_id = s.id) RETURNING s.address_id`)
		if err != nil {
			return err
		}
		ids, err := collectIDs(rows)
		if err != nil {
			return err
		}
		for _, id := range ids {
			if !seen[id] {
				seen[id] = true
				out.Addresses = append(out.Addresses, id)
			}
		}
		out.Senders = ids
		return nil
	})
	if err != nil {
		return nil, err
	}
	tag, err := s.Pool.Exec(ctx, `DELETE FROM email_messages WHERE received_at < $1`, now.Add(-30*24*time.Hour))
	if err != nil {
		return nil, err
	}
	out.Messages = tag.RowsAffected()
	tag, err = s.Pool.Exec(ctx, `DELETE FROM email_reply_recipients WHERE last_reply_at < $1`, now.AddDate(-2, 0, 0))
	if err != nil {
		return nil, err
	}
	out.Replies = tag.RowsAffected()
	return out, nil
}

// ---------------------------------------------------------------- sends

// CreateEmailSend inserts a send credited to email (sends.via = 'email').
func (tx *Tx) CreateEmailSend(ctx context.Context, id, sender, addressID uuid.UUID, label string, items []uuid.UUID) error {
	if _, err := tx.Exec(ctx, `INSERT INTO sends (id, sender_user_id, via, email_address_id, via_label) VALUES ($1, $2, 'email', $3, $4)`,
		id, sender, addressID, label); err != nil {
		return err
	}
	for i, it := range items {
		if err := tx.AddSendItem(ctx, id, it, i); err != nil {
			return err
		}
	}
	return nil
}

// MediaRow returns one media row.
func (s *Store) MediaRow(ctx context.Context, q db.DBTX, id uuid.UUID) (*Media, error) {
	return one[Media](q.Query(ctx, `SELECT `+mediaCols+` FROM media WHERE id = $1`, id))
}

// GetEmailSenderByID returns a sender of any address.
func (s *Store) GetEmailSenderByID(ctx context.Context, q db.DBTX, id uuid.UUID) (*EmailSender, error) {
	return one[EmailSender](q.Query(ctx, senderSelect+` WHERE s.id = $1`, id))
}

// PendingConfirmation is a sender whose delivered mail waits for a coalesced confirmation.
type PendingConfirmation struct {
	AddressID uuid.UUID `db:"address_id"`
	Sender    string    `db:"sender_email"`
}

// PendingConfirmations lists (address, sender) pairs with unconfirmed
// authenticated deliveries, quiet since before t, and no confirmation since t.
func (s *Store) PendingConfirmations(ctx context.Context, quietSince, since time.Time) ([]PendingConfirmation, error) {
	return many[PendingConfirmation](s.Pool.Query(ctx, `SELECT m.address_id, m.sender_email FROM email_messages m
		WHERE m.outcome = 'delivered' AND m.reply_kind IS NULL AND m.authenticated AND m.sender_email IS NOT NULL AND m.received_at >= $2
		GROUP BY m.address_id, m.sender_email HAVING max(m.received_at) < $1
		AND NOT EXISTS (SELECT 1 FROM email_messages r WHERE r.address_id = m.address_id AND r.sender_email = m.sender_email
			AND r.reply_kind = 'delivered' AND r.replied_at >= $1)`, quietSince, since))
}

// LatestSenderMessage is a sender's latest logged mail to an address.
func (s *Store) LatestSenderMessage(ctx context.Context, addressID uuid.UUID, sender string) (*EmailMessage, error) {
	return one[EmailMessage](s.Pool.Query(ctx, `SELECT `+emailMessageCols+` FROM email_messages WHERE address_id = $1 AND sender_email = $2
		ORDER BY received_at DESC LIMIT 1`, addressID, sender))
}

// PendingSenderRows lists the senders managers must decide about: pending
// ones and listed ones with held (unverified) mail, newest mail first.
func (s *Store) PendingSenderRows(ctx context.Context, addressID uuid.UUID) ([]EmailSender, error) {
	return many[EmailSender](s.Pool.Query(ctx, senderSelect+` WHERE s.address_id = $1
		AND (s.state = 'pending' OR EXISTS (SELECT 1 FROM email_held_messages h WHERE h.sender_id = s.id))
		ORDER BY (SELECT max(h.received_at) FROM email_held_messages h WHERE h.sender_id = s.id) DESC NULLS LAST, s.id`, addressID))
}

// SoftDeletedTargetAddresses lists addresses of frames or feeds that were deleted.
func (s *Store) SoftDeletedTargetAddresses(ctx context.Context) ([]EmailAddress, error) {
	return many[EmailAddress](s.Pool.Query(ctx, `SELECT `+emailAddressCols+` FROM email_addresses a
		LEFT JOIN frames fr ON fr.id = a.frame_id LEFT JOIN feeds fd ON fd.id = a.feed_id
		WHERE fr.deleted_at IS NOT NULL OR fd.deleted_at IS NOT NULL`))
}

// GetEmailAddress returns a live address.
func (s *Store) GetEmailAddress(ctx context.Context, q db.DBTX, id uuid.UUID) (*EmailAddress, error) {
	return one[EmailAddress](q.Query(ctx, `SELECT `+emailAddressCols+` FROM email_addresses a`+emailAddressLive+` AND a.id = $1`, id))
}
