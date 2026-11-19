package store_test

import (
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// send inserts a send (via: nil = the apps) with one placement of item on frame, received at `at`.
func (f *feedFx) placeSent(frame, sender, item uuid.UUID, via *string, at time.Time, backfill bool) uuid.UUID {
	f.t.Helper()
	send := store.NewID()
	f.exec(`INSERT INTO sends (id, sender_user_id, via) VALUES ($1, $2, $3)`, send, sender, via)
	f.exec(`INSERT INTO send_items (send_id, item_id, position) VALUES ($1, $2, 0)`, send, item)
	f.exec(`INSERT INTO placements (id, frame_id, item_id, send_id, received_at, backfill) VALUES ($1, $2, $3, $4, $5, $6)`,
		store.NewID(), frame, item, send, at, backfill)
	return send
}

func (f *feedFx) freshness(frame uuid.UUID) store.FreshnessRow {
	f.t.Helper()
	rows, err := f.st.FrameFreshness(f.ctx, f.st.Q(), []uuid.UUID{frame})
	if err != nil {
		f.t.Fatal(err)
	}
	return rows[frame]
}

// Freshness follows placements and posts wherever they are made (00035): people count, photo sources count
// as sources, backfills never, outside contributions credit nobody, and an older placement never moves it back.
func TestEngagementFreshnessTriggers(t *testing.T) {
	f := newFeedFx(t)
	anna, ben := f.user("Anna"), f.user("Ben")
	frame := f.frame(anna, `{}`)
	if r := f.freshness(frame); r.LastPersonalAt != nil || r.LastSourceAt != nil {
		t.Fatalf("fresh frame: %+v", r)
	}
	t0 := time.Date(2026, 11, 10, 12, 0, 0, 0, time.UTC)
	immich := "immich"
	f.placeSent(frame, anna, f.item(anna), &immich, t0, false)
	r := f.freshness(frame)
	if r.LastPersonalAt != nil || r.LastSourceAt == nil || !r.LastSourceAt.Equal(t0) {
		t.Fatalf("after a source photo: %+v", r)
	}
	f.placeSent(frame, ben, f.item(ben), nil, t0.Add(time.Hour), false)
	f.placeSent(frame, anna, f.item(anna), nil, t0.Add(48*time.Hour), true) // history: no
	f.placeSent(frame, anna, f.item(anna), nil, t0.Add(-time.Hour), false)  // older: no change
	r = f.freshness(frame)
	if r.LastPersonalAt == nil || !r.LastPersonalAt.Equal(t0.Add(time.Hour)) || r.UserID == nil || *r.UserID != ben {
		t.Fatalf("after Ben: %+v", r)
	}
	if r.QuietAfter != nil {
		t.Fatalf("no family, no threshold: %v", *r.QuietAfter)
	}
	// Mail delivered on behalf of an unknown sender: personal, nobody credited.
	email := "email"
	item := f.item(anna)
	addr := f.emailAddress(frame, anna, "oma", "k7q2m9")
	f.exec(`UPDATE items SET via = 'email', via_email_address_id = $2 WHERE id = $1`, item, addr.ID)
	f.placeSent(frame, anna, item, &email, t0.Add(2*time.Hour), false)
	r = f.freshness(frame)
	if !r.LastPersonalAt.Equal(t0.Add(2*time.Hour)) || r.UserID != nil {
		t.Fatalf("outside contribution: %+v", r)
	}
	// Feeds: their posts, and the frame's threshold is its families' smallest quiet_after_days.
	feed := f.feed(anna)
	f.exec(`UPDATE feed_engagement SET quiet_after_days = 4 WHERE feed_id = $1`, feed)
	f.subscribe(feed, frame, "none", nil)
	if r := f.freshness(frame); r.QuietAfter == nil || *r.QuietAfter != 4 {
		t.Fatalf("threshold: %+v", r)
	}
	send := store.NewID()
	f.exec(`INSERT INTO sends (id, sender_user_id) VALUES ($1, $2)`, send, ben)
	f.tx(func(tx *store.Tx) error {
		if err := tx.AddFeedMember(f.ctx, feed, ben, "poster"); err != nil {
			return err
		}
		return tx.CreateFeedPost(f.ctx, store.NewID(), feed, &send, ben, []uuid.UUID{f.item(ben)})
	})
	fr, err := f.st.FeedFreshness(f.ctx, f.st.Q(), []uuid.UUID{feed})
	if err != nil {
		t.Fatal(err)
	}
	if got := fr[feed]; got.LastPersonalAt == nil || got.UserID == nil || *got.UserID != ben || got.QuietAfter == nil || *got.QuietAfter != 4 {
		t.Fatalf("feed freshness: %+v", got)
	}
}

// The nudge log: dedupe keys, the 7-day budget, outcomes and the automatic lowering after 4 ignored.
func TestEngagementNudges(t *testing.T) {
	f := newFeedFx(t)
	anna := f.user("Anna")
	now := time.Date(2026, 11, 19, 12, 0, 0, 0, time.UTC)
	nudge := func(kind, key string, budget bool, at time.Time) bool {
		ok, err := f.st.InsertNudge(f.ctx, store.Nudge{ID: store.NewID(), UserID: anna, Kind: kind, DedupeKey: key, CountsInBudget: budget, SentAt: at})
		if err != nil {
			t.Fatal(err)
		}
		return ok
	}
	if !nudge("reminder_quiet", "q:1", true, now.Add(-8*24*time.Hour)) || nudge("reminder_quiet", "q:1", true, now) {
		t.Fatal("dedupe")
	}
	nudge("frame_request", "fr:1", false, now.Add(-time.Hour))
	nudge("reminder_streak", "s:1", true, now.Add(-2*24*time.Hour))
	if n, _ := f.st.BudgetUsed(f.ctx, anna, now.Add(-7*24*time.Hour)); n != 1 {
		t.Fatalf("budget used %d, want 1 (old ones and frame requests don't count)", n)
	}
	// A share within 24 hours answers the frame request; the streak reminder of 2 days ago stays pending.
	if n, _ := f.st.NudgesShared(f.ctx, anna, now); n != 1 {
		t.Fatalf("shared %d", n)
	}
	ign, err := f.st.CloseIgnoredNudges(f.ctx, now.Add(-24*time.Hour), now, []string{"reminder_quiet", "reminder_streak", "reminder_birthday"})
	if err != nil || len(ign) != 1 || ign[0].Count != 2 {
		t.Fatalf("ignored %v %v", ign, err)
	}
	if lowered, err := f.st.AddUnanswered(f.ctx, anna, 2, 4); err != nil || lowered {
		t.Fatalf("lowered after 2: %v %v", lowered, err)
	}
	if lowered, _ := f.st.AddUnanswered(f.ctx, anna, 2, 4); !lowered {
		t.Fatal("not lowered after 4")
	}
	p, _ := f.st.GetReminderPrefs(f.ctx, f.st.Q(), anna)
	if p.Level != "fewer" || !p.LevelAuto || p.AutoReducedSeenAt != nil || p.UnansweredInRow != 4 {
		t.Fatalf("prefs %+v", p)
	}
	if lowered, _ := f.st.AddUnanswered(f.ctx, anna, 1, 4); lowered {
		t.Fatal("lowered twice")
	}
	// A share resets the counter.
	nudge("reminder_quiet", "q:2", true, now)
	f.st.NudgesShared(f.ctx, anna, now.Add(time.Hour))
	if p, _ = f.st.GetReminderPrefs(f.ctx, f.st.Q(), anna); p.UnansweredInRow != 0 {
		t.Fatalf("counter %d", p.UnansweredInRow)
	}
	// Someone already on fewer by choice is not "lowered".
	ben := f.user("Ben")
	fewer := "fewer"
	if err := f.st.UpdateReminderPrefs(f.ctx, f.st.Q(), ben, store.ReminderPrefsPatch{Level: &fewer}); err != nil {
		t.Fatal(err)
	}
	if lowered, _ := f.st.AddUnanswered(f.ctx, ben, 5, 4); lowered {
		t.Fatal("manual fewer reported as lowered")
	}
}

// A request is answered once, only by a placement made after it, and expires after 7 days.
func TestEngagementPhotoRequests(t *testing.T) {
	f := newFeedFx(t)
	anna, ben := f.user("Anna"), f.user("Ben")
	frame := f.frame(anna, `{}`)
	t0 := time.Now().UTC().Truncate(time.Second)
	before := f.placeSent(frame, ben, f.item(ben), nil, t0.Add(-time.Minute), false)
	id := store.NewID()
	f.tx(func(tx *store.Tx) error { return tx.CreatePhotoRequest(f.ctx, id, frame, t0) })
	if got, _ := f.st.AnswerPhotoRequests(f.ctx, before, &ben, t0); len(got) != 0 {
		t.Fatal("answered by an earlier placement")
	}
	after := f.placeSent(frame, ben, f.item(ben), nil, t0.Add(time.Minute), false)
	got, err := f.st.AnswerPhotoRequests(f.ctx, after, &ben, t0.Add(time.Minute))
	if err != nil || len(got) != 1 || got[0].State != "answered" || *got[0].AnsweredBy != ben || *got[0].AnsweredSendID != after {
		t.Fatalf("answered: %+v %v", got, err)
	}
	if again, _ := f.st.AnswerPhotoRequests(f.ctx, after, &ben, t0.Add(2*time.Minute)); len(again) != 0 {
		t.Fatal("answered twice")
	}
	// A new request replaces an old open one; requests expire.
	id2, id3 := store.NewID(), store.NewID()
	f.tx(func(tx *store.Tx) error { return tx.CreatePhotoRequest(f.ctx, id2, frame, t0.Add(-8*24*time.Hour)) })
	f.tx(func(tx *store.Tx) error { return tx.CreatePhotoRequest(f.ctx, id3, frame, t0) })
	if r, _ := f.st.GetPhotoRequest(f.ctx, f.st.Q(), id2); r.State != "expired" {
		t.Fatalf("old request %s", r.State)
	}
	exp, err := f.st.ExpirePhotoRequests(f.ctx, t0.Add(8*24*time.Hour))
	if err != nil || len(exp) != 1 || exp[0].ID != id3 {
		t.Fatalf("expired %v %v", exp, err)
	}
	cands, err := f.st.PhotoRequestCandidates(f.ctx, f.st.Q(), frame, t0.Add(-24*time.Hour))
	if err != nil || len(cands) != 1 || cands[0].UserID != anna || cands[0].Sent {
		t.Fatalf("candidates %+v %v (Ben is no member)", cands, err)
	}
}

// A week counts live items of personal posts made in it, and who posted (README §5.20).
func TestEngagementWeekCount(t *testing.T) {
	f := newFeedFx(t)
	anna, ben := f.user("Anna"), f.user("Ben")
	feed := f.feed(anna)
	from := time.Date(2026, 11, 16, 0, 0, 0, 0, time.UTC)
	post := func(by uuid.UUID, via *string, at time.Time, items int) uuid.UUID {
		send := store.NewID()
		f.exec(`INSERT INTO sends (id, sender_user_id, via) VALUES ($1, $2, $3)`, send, by, via)
		var ids []uuid.UUID
		for i := 0; i < items; i++ {
			ids = append(ids, f.item(by))
		}
		id := store.NewID()
		f.tx(func(tx *store.Tx) error {
			if err := tx.AddFeedMember(f.ctx, feed, by, "poster"); err != nil {
				return err
			}
			return tx.CreateFeedPost(f.ctx, id, feed, &send, by, ids)
		})
		f.exec(`UPDATE feed_posts SET created_at = $2 WHERE id = $1`, id, at)
		return id
	}
	immich := "immich"
	post(anna, nil, from.Add(time.Hour), 2)
	p2 := post(ben, nil, from.Add(48*time.Hour), 2)
	post(ben, &immich, from.Add(50*time.Hour), 5) // a photo source: no
	post(anna, nil, from.Add(7*24*time.Hour), 3)  // next week
	post(anna, nil, from.Add(-time.Second), 3)    // last week
	f.exec(`UPDATE feed_post_items SET removed_at = now() WHERE post_id = $1 AND position = 0`, p2)
	c, err := f.st.CountWeek(f.ctx, f.st.Q(), feed, from, from.Add(7*24*time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if c.Photos != 3 || len(c.Helpers) != 2 {
		t.Fatalf("week: %+v", c)
	}
}
