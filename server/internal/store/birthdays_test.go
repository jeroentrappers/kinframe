package store_test

import (
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Birthdays (docs/spec/birthdays.md): name keys, dates, the merge rules of §6.5
// and the digest-gated `birthdays` change.

func TestNameKey(t *testing.T) {
	for in, want := range map[string]string{
		"Zoë  ":         "zoe",
		"  OMA ":        "oma",
		"Oma   van  Ee": "oma van ee",
		"Ｏｍａ":           "oma", // NFKC: full-width letters
		"Ångström":      "angstrom",
		"STRASSE":       "strasse",
		"Straße":        "strasse", // case folding
	} {
		if got := store.NameKey(in); got != want {
			t.Errorf("NameKey(%q) = %q, want %q", in, got, want)
		}
	}
}

func date(s string) time.Time {
	d, err := time.Parse("2006-01-02", s)
	if err != nil {
		panic(err)
	}
	return d
}

func TestBirthdayDates(t *testing.T) {
	// 29 February falls on 28 February in non-leap years.
	if !store.OccursOn(2, 29, date("2027-02-28")) || store.OccursOn(2, 29, date("2028-02-28")) || !store.OccursOn(2, 29, date("2028-02-29")) {
		t.Fatal("leap day occurrence")
	}
	if store.OccursOn(2, 28, date("2028-02-29")) || !store.OccursOn(3, 12, date("2026-03-12")) {
		t.Fatal("plain occurrence")
	}
	y := 2026
	for _, c := range []struct {
		m, d  int
		year  *int
		today string
		want  string
	}{
		{3, 12, nil, "2026-03-12", "2026-03-12"}, // today counts
		{3, 12, nil, "2026-03-13", "2027-03-12"}, // passed: next year
		{2, 29, nil, "2026-11-19", "2027-02-28"}, // non-leap
		{2, 29, nil, "2027-03-01", "2028-02-29"}, // leap
		{6, 2, &y, "2026-01-05", "2027-06-02"},   // the date of birth is not a birthday
		{12, 31, &y, "2026-11-19", "2027-12-31"}, // born later this year: first birthday next year
		{1, 1, nil, "2026-12-31", "2027-01-01"},
	} {
		if got := store.NextOccurrence(c.m, c.d, c.year, date(c.today)).Format("2006-01-02"); got != c.want {
			t.Errorf("NextOccurrence(%d-%d, %v, %s) = %s, want %s", c.m, c.d, c.year, c.today, got, c.want)
		}
	}
	m, d := store.MonthDaysAround(date("2027-02-27"), date("2027-02-28"))
	if len(m) != 3 || m[2] != 2 || d[2] != 29 {
		t.Fatalf("MonthDaysAround non-leap: %v %v", m, d)
	}
	if m, _ := store.MonthDaysAround(date("2028-02-28")); len(m) != 1 {
		t.Fatalf("MonthDaysAround leap year: %v", m)
	}
}

func entry(feed uuid.UUID, name string, m, d int, year *int, showAge bool, photo *string, activated time.Time) store.FrameBirthdayEntry {
	return store.FrameBirthdayEntry{ID: store.NewID(), FeedID: feed, DisplayName: name, NameKey: store.NameKey(name), Month: m, Day: d,
		BirthYear: year, ShowAge: showAge, PhotoKey: photo, ActivatedAt: activated, CreatedAt: activated}
}

func ip(n int) *int { return &n }

func TestMergeBirthdays(t *testing.T) {
	feedA, feedB, feedC := store.NewID(), store.NewID(), store.NewID()
	t0 := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	photo := "avatars/b/x.webp"

	// The same person in two feeds; the later-activated one has the photo: it is the primary.
	a := entry(feedA, "Oma", 3, 12, ip(1946), true, nil, t0)
	b := entry(feedB, "oma ", 3, 12, nil, false, &photo, t0.Add(time.Hour))
	c := entry(feedC, "Lotte", 6, 2, nil, false, nil, t0)
	gs := store.MergeBirthdays([]store.FrameBirthdayEntry{b, c, a})
	if len(gs) != 2 || gs[0].Name != "oma " || gs[0].ID != b.ID || gs[1].Name != "Lotte" {
		t.Fatalf("groups: %+v", gs)
	}
	g := gs[0]
	if len(g.FeedIDs) != 2 || g.FeedIDs[0] != feedA || len(g.BirthdayIDs) != 2 || g.BirthdayIDs[0] != a.ID {
		t.Fatalf("merged ids: %+v", g)
	}
	// Only one entry has a year and it shows the age: the year is sent (an entry without a year merges).
	if g.BirthYear == nil || *g.BirthYear != 1946 || g.PhotoKey == nil || *g.PhotoKey != photo {
		t.Fatalf("year/photo: %+v", g)
	}
	// Without photos the first-activated entry is the primary (ties: the oldest entry).
	b.PhotoKey = nil
	if gs := store.MergeBirthdays([]store.FrameBirthdayEntry{b, a}); gs[0].ID != a.ID || gs[0].Name != "Oma" {
		t.Fatalf("primary without photos: %+v", gs[0])
	}
	// The linked member's avatar counts as a photo.
	avatar := "avatars/u/a.webp"
	b.AvatarKey = &avatar
	if gs := store.MergeBirthdays([]store.FrameBirthdayEntry{a, b}); gs[0].ID != b.ID || *gs[0].PhotoKey != avatar {
		t.Fatalf("avatar fallback: %+v", gs[0])
	}

	// Year rule: most restrictive wins.
	for _, c := range []struct {
		name string
		es   []store.FrameBirthdayEntry
		want *int
	}{
		{"one with show_age", []store.FrameBirthdayEntry{entry(feedA, "X", 1, 1, ip(1950), true, nil, t0)}, ip(1950)},
		{"show_age off", []store.FrameBirthdayEntry{entry(feedA, "X", 1, 1, ip(1950), false, nil, t0)}, nil},
		{"no year at all", []store.FrameBirthdayEntry{entry(feedA, "X", 1, 1, nil, true, nil, t0)}, nil},
		{"one hides it", []store.FrameBirthdayEntry{entry(feedA, "X", 1, 1, ip(1950), true, nil, t0), entry(feedB, "X", 1, 1, ip(1950), false, nil, t0)}, nil},
		{"years disagree", []store.FrameBirthdayEntry{entry(feedA, "X", 1, 1, ip(1950), true, nil, t0), entry(feedB, "X", 1, 1, ip(1951), true, nil, t0)}, nil},
		{"years agree", []store.FrameBirthdayEntry{entry(feedA, "X", 1, 1, ip(1950), true, nil, t0), entry(feedB, "X", 1, 1, ip(1950), true, nil, t0)}, ip(1950)},
		{"yearless show_age off merges", []store.FrameBirthdayEntry{entry(feedA, "X", 1, 1, ip(1950), true, nil, t0), entry(feedB, "X", 1, 1, nil, false, nil, t0)}, ip(1950)},
	} {
		got := store.MergeBirthdays(c.es)
		if len(got) != 1 || (got[0].BirthYear == nil) != (c.want == nil) || (c.want != nil && *got[0].BirthYear != *c.want) {
			t.Errorf("%s: birth_year %v, want %v", c.name, got[0].BirthYear, c.want)
		}
	}
	// Different dates never merge (28 and 29 February are different people), and sorting is by month and day.
	gs = store.MergeBirthdays([]store.FrameBirthdayEntry{entry(feedA, "X", 2, 29, nil, false, nil, t0), entry(feedB, "X", 2, 28, nil, false, nil, t0),
		entry(feedA, "Y", 1, 30, nil, false, nil, t0)})
	if len(gs) != 3 || gs[0].Name != "Y" || gs[1].Day != 28 || gs[2].Day != 29 {
		t.Fatalf("dates: %+v", gs)
	}
}

// The frame's `birthdays` entry is bumped only when the merged content changes.
func TestSyncFrameBirthdays(t *testing.T) {
	f := newFeedFx(t)
	owner := f.user("Anna")
	frame := f.frame(owner, `{}`)
	feed := store.NewID()
	f.tx(func(tx *store.Tx) error { return tx.CreateFeed(f.ctx, store.Feed{ID: feed, Name: "Peeters"}, owner) })
	sub := store.NewID()
	f.tx(func(tx *store.Tx) error {
		if _, err := tx.LockFrame(f.ctx, frame); err != nil {
			return err
		}
		if err := tx.CreateSubscription(f.ctx, store.Subscription{ID: sub, FeedID: feed, FrameID: frame, History: "none", Birthdays: true}); err != nil {
			return err
		}
		return tx.ActivateSubscription(f.ctx, sub, &owner)
	})
	seq := func() int64 {
		var n int64
		_ = f.st.Pool.QueryRow(f.ctx, `SELECT COALESCE(max(seq), 0) FROM feed WHERE frame_id = $1 AND kind = 'birthdays'`, frame).Scan(&n)
		return n
	}
	if seq() != 0 {
		t.Fatal("an empty list is not emitted")
	}
	oma := &store.Birthday{ID: store.NewID(), FeedID: feed, Status: "approved", DisplayName: "Oma", Month: 3, Day: 12, CreatedBy: &owner}
	sync := func() {
		f.tx(func(tx *store.Tx) error { return tx.SyncBirthdayFeeds(f.ctx, []uuid.UUID{feed}) })
	}
	f.tx(func(tx *store.Tx) error { return tx.InsertBirthday(f.ctx, oma) })
	sync()
	s1 := seq()
	if s1 == 0 {
		t.Fatal("no birthdays change after adding Oma")
	}
	sync()
	if seq() != s1 {
		t.Fatal("unchanged content was emitted again")
	}
	// A pending suggestion is not on frames.
	f.tx(func(tx *store.Tx) error {
		return tx.InsertBirthday(f.ctx, &store.Birthday{ID: store.NewID(), FeedID: feed, Status: "pending", DisplayName: "Lotte", Month: 6, Day: 2})
	})
	sync()
	if seq() != s1 {
		t.Fatal("a pending entry changed the frame's list")
	}
	groups, err := f.st.FrameBirthdays(f.ctx, f.st.Q(), frame)
	if err != nil || len(groups) != 1 || groups[0].ID != oma.ID {
		t.Fatalf("frame birthdays: %+v %v", groups, err)
	}
	// Turning birthdays off on the subscription empties the list (a change, rendered as null).
	f.tx(func(tx *store.Tx) error {
		if _, err := tx.LockFrame(f.ctx, frame); err != nil {
			return err
		}
		s, err := tx.LockSubscription(f.ctx, sub)
		if err != nil {
			return err
		}
		return tx.SetSubscriptionBirthdays(f.ctx, s, false)
	})
	if seq() == s1 {
		t.Fatal("birthdays off did not emit")
	}
	if groups, _ := f.st.FrameBirthdays(f.ctx, f.st.Q(), frame); len(groups) != 0 {
		t.Fatalf("still listed: %+v", groups)
	}
	// Unlinking a member re-syncs: linked avatar used as photo.
	ben := f.user("Ben")
	f.exec(`UPDATE users SET avatar_key = 'avatars/ben/a.webp' WHERE id = $1`, ben)
	f.tx(func(tx *store.Tx) error { return tx.AddFeedMember(f.ctx, feed, ben, "poster") })
	f.tx(func(tx *store.Tx) error {
		if _, err := tx.LockFrame(f.ctx, frame); err != nil {
			return err
		}
		s, _ := tx.LockSubscription(f.ctx, sub)
		return tx.SetSubscriptionBirthdays(f.ctx, s, true)
	})
	f.exec(`UPDATE feed_birthdays SET user_id = $1 WHERE id = $2`, ben, oma.ID)
	sync()
	if g, _ := f.st.FrameBirthdays(f.ctx, f.st.Q(), frame); len(g) != 1 || g[0].PhotoKey == nil {
		t.Fatalf("avatar fallback: %+v", g)
	}
	s2 := seq()
	var un []store.UnlinkedBirthday
	f.tx(func(tx *store.Tx) error {
		var err error
		un, err = tx.UnlinkBirthdays(f.ctx, &feed, ben)
		return err
	})
	if len(un) != 1 || seq() == s2 {
		t.Fatalf("unlink: %v, seq %d -> %d", un, s2, seq())
	}
	if g, _ := f.st.FrameBirthdays(f.ctx, f.st.Q(), frame); len(g) != 1 || g[0].PhotoKey != nil {
		t.Fatalf("photo after unlink: %+v", g)
	}
}

// The database backs the validation up: impossible dates and leap years.
func TestBirthdayConstraints(t *testing.T) {
	f := newFeedFx(t)
	owner := f.user("Anna")
	feed := store.NewID()
	f.tx(func(tx *store.Tx) error { return tx.CreateFeed(f.ctx, store.Feed{ID: feed, Name: "F"}, owner) })
	for _, c := range []struct {
		m, d int
		y    *int
		ok   bool
	}{{2, 29, nil, true}, {2, 29, ip(2000), true}, {2, 29, ip(1900), false}, {2, 30, nil, false}, {4, 31, nil, false}, {12, 31, ip(1899), false}} {
		err := f.st.InTx(f.ctx, func(tx *store.Tx) error {
			return tx.InsertBirthday(f.ctx, &store.Birthday{ID: store.NewID(), FeedID: feed, Status: "approved", DisplayName: store.NewID().String(),
				Month: c.m, Day: c.d, BirthYear: c.y})
		})
		if (err == nil) != c.ok {
			t.Errorf("%d-%d year %v: err %v", c.m, c.d, c.y, err)
		}
	}
	// Uniqueness ignores rejected rows.
	ins := func(status string) error {
		return f.st.InTx(f.ctx, func(tx *store.Tx) error {
			return tx.InsertBirthday(f.ctx, &store.Birthday{ID: store.NewID(), FeedID: feed, Status: status, DisplayName: "Zoë", Month: 5, Day: 5})
		})
	}
	if ins("rejected") != nil || ins("pending") != nil || ins("rejected") != nil {
		t.Fatal("rejected rows must not block")
	}
	if ins("approved") == nil {
		t.Fatal("duplicate live entry accepted")
	}
}
