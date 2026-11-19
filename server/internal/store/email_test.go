package store_test

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Email to frame rows (docs/spec/email-inbox.md §16): addresses and retired
// local parts, senders and held mail, the message log, retention and the
// verified account emails.

func (f *feedFx) emailAddress(frame, owner uuid.UUID, slug, random string) *store.EmailAddress {
	f.t.Helper()
	a := &store.EmailAddress{ID: store.NewID(), Slug: slug, Random: random, LocalPart: slug + "-" + random, Name: "Oma's frame",
		FrameID: &frame, OwnerUserID: &owner, CreatedByUserID: &owner, SendConfirmations: true, OfferAppInvite: true, NotifyReactions: true}
	f.tx(func(tx *store.Tx) error { return tx.CreateEmailAddress(f.ctx, *a) })
	return a
}

func TestEmailAddressLocalParts(t *testing.T) {
	f := newFeedFx(t)
	anna := f.user("Anna")
	frame := f.frame(anna, `{}`)
	a := f.emailAddress(frame, anna, "oma", "k7q2m9")
	got, err := f.st.EmailAddressByLocalPart(f.ctx, f.st.Q(), "oma-k7q2m9")
	if err != nil || got.ID != a.ID {
		t.Fatalf("lookup: %v %v", got, err)
	}
	// The random part's alphabet is enforced (no 0, 1, i, l, o).
	bad := store.EmailAddress{ID: store.NewID(), Slug: "oma", Random: "k7q2m0", LocalPart: "oma-k7q2m0", Name: "x", FrameID: &frame}
	if err := f.st.InTx(f.ctx, func(tx *store.Tx) error { return tx.CreateEmailAddress(f.ctx, bad) }); err == nil {
		t.Fatal("random part with 0 accepted")
	}
	// Regenerate: the old local part is retired at once and never issued again.
	f.tx(func(tx *store.Tx) error {
		cur, err := tx.LockEmailAddress(f.ctx, a.ID)
		if err != nil {
			return err
		}
		return tx.ChangeLocalPart(f.ctx, cur, "oma", "p3wd8h", true)
	})
	if _, err := f.st.EmailAddressByLocalPart(f.ctx, f.st.Q(), "oma-k7q2m9"); err != store.ErrNotFound {
		t.Fatalf("old local part still works: %v", err)
	}
	if used, _ := f.st.LocalPartUsed(f.ctx, f.st.Q(), "oma-k7q2m9"); !used {
		t.Fatal("old local part not retired")
	}
	v, err := f.st.GetEmailAddressView(f.ctx, f.st.Q(), a.ID)
	if err != nil || v.LocalPart != "oma-p3wd8h" || v.RegeneratedAt == nil || *v.FrameName != "frame" {
		t.Fatalf("view: %+v %v", v, err)
	}
	// Delete retires the current one too.
	var addr *store.EmailAddress
	f.tx(func(tx *store.Tx) error {
		var err error
		addr, err = tx.LockEmailAddress(f.ctx, a.ID)
		if err != nil {
			return err
		}
		_, err = tx.DeleteEmailAddress(f.ctx, addr)
		return err
	})
	if used, _ := f.st.LocalPartUsed(f.ctx, f.st.Q(), "oma-p3wd8h"); !used {
		t.Fatal("deleted local part not retired")
	}
	// A deleted frame's address is dead.
	b := f.emailAddress(frame, anna, "opa", "abcdef")
	f.exec(`UPDATE frames SET deleted_at = now() WHERE id = $1`, frame)
	if _, err := f.st.EmailAddressByLocalPart(f.ctx, f.st.Q(), b.LocalPart); err != store.ErrNotFound {
		t.Fatalf("address of a deleted frame: %v", err)
	}
	gone, err := f.st.SoftDeletedTargetAddresses(f.ctx)
	if err != nil || len(gone) != 1 || gone[0].ID != b.ID {
		t.Fatalf("soft-deleted targets: %v %v", gone, err)
	}
}

func TestEmailSendersHeldAndRetention(t *testing.T) {
	f := newFeedFx(t)
	anna := f.user("Anna")
	frame := f.frame(anna, `{}`)
	a := f.emailAddress(frame, anna, "oma", "k7q2m9")
	rita := &store.EmailSender{ID: store.NewID(), AddressID: a.ID, Email: "Rita@Gmail.com", State: "pending", Origin: "mail"}
	f.tx(func(tx *store.Tx) error { return tx.CreateEmailSender(f.ctx, rita) })
	if got, err := f.st.EmailSenderByEmail(f.ctx, f.st.Q(), a.ID, "rita@gmail.com"); err != nil || got.ID != rita.ID {
		t.Fatalf("sender by email (lower case): %v", err)
	}
	// Held mail with a processed media row that points back at it.
	mediaID := store.NewID()
	f.exec(`INSERT INTO media (id, owner_user_id, kind, status, master_key) VALUES ($1, $2, 'photo', 'ready', 'held/master.webp')`, mediaID, anna)
	h := &store.HeldMessage{ID: store.NewID(), AddressID: a.ID, SenderID: rita.ID, Reason: "unknown_sender", MessageKey: "k1",
		SenderEmail: "rita@gmail.com", Auth: json.RawMessage(`{"authenticated":true}`),
		Media: []store.HeldMedia{{MediaID: mediaID, Kind: "photo", Bytes: 1234, SHA256: "abc", ThumbKey: ptrStr("held/thumb.webp")}},
		Skipped: map[string]int{"too_small": 2}, ReceivedAt: time.Now(), ExpiresAt: time.Now().Add(14 * 24 * time.Hour)}
	f.tx(func(tx *store.Tx) error { return tx.CreateHeldMessage(f.ctx, h) })
	if seen, _ := f.st.MessageKeySeen(f.ctx, f.st.Q(), a.ID, "k1"); !seen {
		t.Fatal("held key not seen")
	}
	v, _ := f.st.GetEmailAddressView(f.ctx, f.st.Q(), a.ID)
	if v.Pending != 1 || v.Held != 1 || v.Allowed != 0 {
		t.Fatalf("counts %+v", v)
	}
	rows, err := f.st.PendingSenderRows(f.ctx, a.ID)
	if err != nil || len(rows) != 1 {
		t.Fatalf("pending rows %v %v", rows, err)
	}
	// Expiry: the held mail goes, its media and files with it, and the pending sender is back to unknown.
	f.exec(`UPDATE email_held_messages SET expires_at = now() - interval '1 minute'`)
	res, err := f.st.PruneEmail(f.ctx, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if res.Held != 1 || len(res.Senders) != 1 || len(res.Blobs.Keys) != 2 {
		t.Fatalf("prune %+v", res)
	}
	var n int
	_ = f.st.Pool.QueryRow(f.ctx, `SELECT count(*) FROM media WHERE id = $1`, mediaID).Scan(&n)
	if n != 0 {
		t.Fatal("held media not deleted")
	}
	if _, err := f.st.EmailSenderByEmail(f.ctx, f.st.Q(), a.ID, "rita@gmail.com"); err != store.ErrNotFound {
		t.Fatalf("pending sender kept: %v", err)
	}
	// Message log: 30 days, coalesced confirmations, reply counts.
	sender := "jan@example.org"
	old := &store.EmailMessage{ID: store.NewID(), AddressID: a.ID, MessageKey: "old", SenderEmail: &sender, Authenticated: true,
		Outcome: "delivered", Items: 1, ReceivedAt: time.Now().Add(-31 * 24 * time.Hour)}
	m1 := &store.EmailMessage{ID: store.NewID(), AddressID: a.ID, MessageKey: "m1", SenderEmail: &sender, Authenticated: true,
		Outcome: "delivered", Items: 4, ReceivedAt: time.Now().Add(-20 * time.Minute)}
	for _, m := range []*store.EmailMessage{old, m1} {
		if err := f.st.InsertEmailMessage(f.ctx, f.st.Q(), m); err != nil {
			t.Fatal(err)
		}
	}
	pcs, err := f.st.PendingConfirmations(f.ctx, time.Now().Add(-10*time.Minute), time.Now().Add(-24*time.Hour))
	if err != nil || len(pcs) != 1 || pcs[0].Sender != sender {
		t.Fatalf("pending confirmations %v %v", pcs, err)
	}
	if err := f.st.MarkConfirmed(f.ctx, []uuid.UUID{m1.ID}, time.Now()); err != nil {
		t.Fatal(err)
	}
	if n, _ := f.st.RepliesSince(f.ctx, &a.ID, sender, []string{"delivered"}, time.Now().Add(-time.Minute)); n != 1 {
		t.Fatalf("replies since: %d", n)
	}
	ms, _, err := f.st.ListEmailMessages(f.ctx, a.ID, "", 10)
	if err != nil || len(ms) != 1 || ms[0].ID != m1.ID {
		t.Fatalf("recent mail: %v %v", ms, err)
	}
	res, err = f.st.PruneEmail(f.ctx, time.Now())
	if err != nil || res.Messages != 1 {
		t.Fatalf("prune messages %+v %v", res, err)
	}
	// Reply recipients: the app intro is kept once set.
	if err := f.st.RecordReply(f.ctx, "Jan@example.org", true, time.Now()); err != nil {
		t.Fatal(err)
	}
	if err := f.st.RecordReply(f.ctx, "jan@example.org", false, time.Now()); err != nil {
		t.Fatal(err)
	}
	rr, err := f.st.GetReplyRecipient(f.ctx, "jan@example.org")
	if err != nil || rr.AppIntroSentAt == nil {
		t.Fatalf("reply recipient %+v %v", rr, err)
	}
	// Queue: claimed once, leased, deleted.
	in := &store.InboundMail{ID: store.NewID(), SpoolPath: "/x", Path: "webhook", EnvelopeTo: []string{"oma-k7q2m9@in.example.org"}, SizeBytes: 10, ClientIP: ptrStr("203.0.113.5")}
	if err := f.st.InsertInbound(f.ctx, in); err != nil {
		t.Fatal(err)
	}
	got, err := f.st.ClaimInbound(f.ctx, time.Minute)
	if err != nil || got.ID != in.ID || got.Attempts != 1 || *got.ClientIP != "203.0.113.5" {
		t.Fatalf("claim %+v %v", got, err)
	}
	if _, err := f.st.ClaimInbound(f.ctx, time.Minute); err != store.ErrNotFound {
		t.Fatalf("leased row claimed again: %v", err)
	}
}

func TestVerifiedEmails(t *testing.T) {
	f := newFeedFx(t)
	now := time.Now()
	jan, err := f.st.UpsertUserBySub(f.ctx, "sub-jan", "jan@example.org", "Jan", now, true)
	if err != nil || !jan.EmailVerified {
		t.Fatalf("jan: %+v %v", jan, err)
	}
	if u, err := f.st.VerifiedAccountByEmail(f.ctx, f.st.Q(), "JAN@example.org"); err != nil || u.ID != jan.ID {
		t.Fatalf("match: %v", err)
	}
	// A changed primary email follows the token.
	jan, _ = f.st.UpsertUserBySub(f.ctx, "sub-jan", "jan.peeters@telenet.be", "Jan", now, true)
	if *jan.Email != "jan.peeters@telenet.be" || !jan.EmailVerified {
		t.Fatalf("refresh: %+v", jan)
	}
	// Unverified tokens never verify.
	piet, _ := f.st.UpsertUserBySub(f.ctx, "sub-piet", "piet@example.org", "Piet", now, false)
	if piet.EmailVerified {
		t.Fatal("unverified email marked verified")
	}
	// An email two accounts claim is matched to neither.
	twin, err := f.st.UpsertUserBySub(f.ctx, "sub-twin", "Jan.Peeters@telenet.be", "Twin", now, true)
	if err != nil || twin.EmailVerified {
		t.Fatalf("twin: %+v %v", twin, err)
	}
	if _, err := f.st.VerifiedAccountByEmail(f.ctx, f.st.Q(), "jan.peeters@telenet.be"); err != store.ErrNotFound {
		t.Fatalf("duplicate email matched: %v", err)
	}
	jan, _ = f.st.UpsertUserBySub(f.ctx, "sub-jan", "jan.peeters@telenet.be", "Jan", now, true)
	if jan.EmailVerified {
		t.Fatal("duplicate email verified again")
	}
}
