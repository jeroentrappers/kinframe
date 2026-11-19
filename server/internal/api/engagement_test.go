package api

import (
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

func at(s string) time.Time {
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		panic(err)
	}
	return t
}

func tp(s string) *time.Time { t := at(s); return &t }

// The freshness ring (README §5.20): level = max(L(personal), 0.5 × L(source)), L linear over 14 days.
func TestFreshnessFormula(t *testing.T) {
	now := at("2026-11-19T12:00:00Z")
	tests := []struct {
		name             string
		personal, source *time.Time
		quiet            int
		state            string
		level            float32
		days             *int
	}{
		{"never", nil, nil, 7, "never", 0, nil},
		{"shared now", tp("2026-11-19T12:00:00Z"), nil, 7, "fresh", 1, ptr(0)},
		{"23 hours ago is still fresh", tp("2026-11-18T13:00:00Z"), nil, 7, "fresh", float32(1 - 23.0/24/14), ptr(0)},
		{"7 days: half and quiet at the threshold", tp("2026-11-12T12:00:00Z"), nil, 7, "quiet", 0.5, ptr(7)},
		{"6 days is recent", tp("2026-11-13T12:00:00Z"), nil, 7, "recent", float32(1 - 6.0/14), ptr(6)},
		{"a family's own threshold", tp("2026-11-13T12:00:00Z"), nil, 5, "quiet", float32(1 - 6.0/14), ptr(6)},
		{"14 days and more: empty ring", tp("2026-11-01T12:00:00Z"), nil, 7, "quiet", 0, ptr(18)},
		{"sources count half", tp("2026-11-01T12:00:00Z"), tp("2026-11-19T12:00:00Z"), 7, "quiet", 0.5, ptr(18)},
		{"a person beats a source", tp("2026-11-19T12:00:00Z"), tp("2026-11-19T12:00:00Z"), 7, "fresh", 1, ptr(0)},
		{"only sources: quiet without days", nil, tp("2026-11-12T12:00:00Z"), 7, "quiet", 0.25, nil},
	}
	for _, tc := range tests {
		f := freshnessOf(now, tc.personal, tc.source, tc.quiet)
		if string(f.State) != tc.state {
			t.Errorf("%s: state %s, want %s", tc.name, f.State, tc.state)
		}
		if d := f.Level - tc.level; d > 0.001 || d < -0.001 {
			t.Errorf("%s: level %v, want %v", tc.name, f.Level, tc.level)
		}
		if (f.DaysQuiet == nil) != (tc.days == nil) || (f.DaysQuiet != nil && *f.DaysQuiet != *tc.days) {
			t.Errorf("%s: days_quiet %v, want %v", tc.name, f.DaysQuiet, tc.days)
		}
	}
}

func TestQuietHours(t *testing.T) {
	loc, _ := time.LoadLocation("Europe/Brussels")
	local := func(h, m int) time.Time { return time.Date(2026, 11, 19, h, m, 0, 0, loc) }
	tests := []struct {
		start, end string
		h, m       int
		quiet      bool
	}{
		{"21:00", "09:00", 20, 59, false},
		{"21:00", "09:00", 21, 0, true},
		{"21:00", "09:00", 23, 30, true},
		{"21:00", "09:00", 0, 0, true},
		{"21:00", "09:00", 8, 59, true},
		{"21:00", "09:00", 9, 0, false},
		{"13:00", "14:00", 13, 30, true}, // not over midnight
		{"13:00", "14:00", 14, 0, false},
		{"10:00", "10:00", 10, 0, false}, // equal: none
		{"bad", "09:00", 3, 0, false},
	}
	for _, tc := range tests {
		if got := inQuietHours(local(tc.h, tc.m), tc.start, tc.end); got != tc.quiet {
			t.Errorf("%s–%s at %02d:%02d: quiet %v, want %v", tc.start, tc.end, tc.h, tc.m, got, tc.quiet)
		}
	}
	// In the person's own zone: 21:30 UTC is 22:30 in Brussels but 16:30 in New York.
	now := at("2026-11-19T21:30:00Z")
	bxl, ny := "Europe/Brussels", "America/New_York"
	if !quietNow(&store.ReminderPrefs{Timezone: &bxl, QuietStart: "21:00", QuietEnd: "09:00"}, now) {
		t.Error("Brussels should be quiet")
	}
	if quietNow(&store.ReminderPrefs{Timezone: &ny, QuietStart: "21:00", QuietEnd: "09:00"}, now) {
		t.Error("New York should not be quiet")
	}
	if _, ok := parseClock("24:00"); ok {
		t.Error("24:00 accepted")
	}
}

// The budget (README §5.20): 2 a week normally, 1 with fewer (and no streak reminders), none when off or
// snoozed; each kind's own switch.
func TestReminderBudget(t *testing.T) {
	noon := at("2026-11-19T11:00:00Z") // 12:00 in Brussels, outside quiet hours
	p := func(level string) *store.ReminderPrefs {
		return &store.ReminderPrefs{Level: level, BirthdayReminders: true, WeeklySummary: true, FrameRequests: true,
			QuietStart: "21:00", QuietEnd: "09:00"}
	}
	tests := []struct {
		name  string
		prefs *store.ReminderPrefs
		kind  string
		used  int
		ok    bool
	}{
		{"normal, none used", p("normal"), "reminder_quiet", 0, true},
		{"normal, one used", p("normal"), "reminder_quiet", 1, true},
		{"normal, two used", p("normal"), "reminder_quiet", 2, false},
		{"fewer, none used", p("fewer"), "reminder_quiet", 0, true},
		{"fewer, one used", p("fewer"), "reminder_birthday", 1, false},
		{"fewer: no streak reminders", p("fewer"), "reminder_streak", 0, false},
		{"normal: streak", p("normal"), "reminder_streak", 0, true},
		{"off", p("off"), "reminder_quiet", 0, false},
		{"birthdays off", func() *store.ReminderPrefs { x := p("normal"); x.BirthdayReminders = false; return x }(), "reminder_birthday", 0, false},
		{"summary off", func() *store.ReminderPrefs { x := p("normal"); x.WeeklySummary = false; return x }(), "weekly_summary", 0, false},
		{"snoozed", func() *store.ReminderPrefs { x := p("normal"); x.SnoozedUntil = tp("2026-12-01T00:00:00Z"); return x }(), "reminder_quiet", 0, false},
		{"snooze over", func() *store.ReminderPrefs { x := p("normal"); x.SnoozedUntil = tp("2026-11-01T00:00:00Z"); return x }(), "reminder_quiet", 0, true},
		{"deleted account", func() *store.ReminderPrefs { x := p("normal"); x.DeletedAt = tp("2026-11-01T00:00:00Z"); return x }(), "reminder_quiet", 0, false},
	}
	for _, tc := range tests {
		if got := reminderAllowed(tc.prefs, tc.kind, noon, tc.used); got != tc.ok {
			t.Errorf("%s: allowed %v, want %v", tc.name, got, tc.ok)
		}
	}
	night := at("2026-11-19T22:00:00Z") // 23:00 in Brussels (no zone: the default)
	if reminderAllowed(p("normal"), "reminder_quiet", night, 0) {
		t.Error("sent during quiet hours")
	}
}

// Weeks run Monday 00:00 to Sunday 24:00 in the family's zone, also across daylight saving time.
func TestWeeksInZone(t *testing.T) {
	bxl, _ := time.LoadLocation("Europe/Brussels")
	akl, _ := time.LoadLocation("Pacific/Auckland")
	tests := []struct {
		at     string
		loc    *time.Location
		monday string
	}{
		{"2026-11-22T22:59:00Z", bxl, "2026-11-16"}, // Sunday 23:59 in Brussels
		{"2026-11-22T23:00:00Z", bxl, "2026-11-23"}, // Monday 00:00 in Brussels
		{"2026-11-22T11:00:00Z", akl, "2026-11-23"}, // already Monday in Auckland
		{"2026-03-29T12:00:00Z", bxl, "2026-03-23"}, // the Sunday the clocks go forward
	}
	for _, tc := range tests {
		if got := mondayOf(at(tc.at), tc.loc).Format(dateLayout); got != tc.monday {
			t.Errorf("%s in %s: Monday %s, want %s", tc.at, tc.loc, got, tc.monday)
		}
	}
	// The week of the DST change is 167 hours long.
	from, to := weekBounds(time.Date(2026, 3, 23, 0, 0, 0, 0, time.UTC), bxl)
	if to.Sub(from) != 167*time.Hour || from.Format(time.RFC3339) != "2026-03-23T00:00:00+01:00" {
		t.Errorf("DST week %s – %s", from, to)
	}
}

// Closing weeks (README §5.20): met adds a week, a missed week uses a freeze, without one the streak quietly
// restarts; milestones at 4, 12, 26, 52 and every 52 after, once.
func TestStreakWeeks(t *testing.T) {
	st := streakState{Freezes: 1}
	var outcomes []string
	var milestones []int
	for _, met := range []bool{true, true, true, true, false, true, false, false, true} {
		o, m := closeStreakWeek(&st, met)
		outcomes = append(outcomes, o)
		if m > 0 {
			milestones = append(milestones, m)
		}
	}
	want := []string{"met", "met", "met", "met", "frozen", "met", "missed", "missed", "met"}
	for i := range want {
		if outcomes[i] != want[i] {
			t.Fatalf("outcomes %v, want %v", outcomes, want)
		}
	}
	if st.Current != 1 || st.Best != 5 || st.Freezes != 0 || len(milestones) != 1 || milestones[0] != 4 {
		t.Fatalf("state %+v, milestones %v", st, milestones)
	}
	// A restarted streak celebrates 4 weeks again.
	st = streakState{Current: 3, Best: 10, LastMilestone: 0}
	if _, m := closeStreakWeek(&st, true); m != 4 {
		t.Fatalf("milestone after restart: %d", m)
	}
	for n, want := range map[int]bool{4: true, 12: true, 26: true, 52: true, 104: true, 156: true, 5: false, 53: false, 78: false} {
		if isStreakMilestone(n) != want {
			t.Errorf("milestone %d: %v", n, !want)
		}
	}
	// One freeze a month, at most two held.
	st = streakState{Freezes: 1}
	nov := time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)
	granted := &nov
	grantFreeze(&st, &granted, nov)
	if st.Freezes != 1 {
		t.Fatalf("granted twice in a month: %d", st.Freezes)
	}
	grantFreeze(&st, &granted, nov.AddDate(0, 1, 0))
	grantFreeze(&st, &granted, nov.AddDate(0, 2, 0))
	if st.Freezes != 2 || !granted.Equal(nov.AddDate(0, 2, 0)) {
		t.Fatalf("freezes %d granted %s", st.Freezes, granted)
	}
	if shownWeeks(5, true) != 6 || shownWeeks(5, false) != 5 {
		t.Fatal("shown weeks")
	}
}

// The rotation never repeats a key within 52 weeks while unused keys are left, and differs between families.
func TestPromptRotation(t *testing.T) {
	if len(promptLibrary) < 40 {
		t.Fatalf("only %d prompts", len(promptLibrary))
	}
	seen := map[string]bool{}
	for _, k := range promptLibrary {
		if seen[k] {
			t.Fatalf("duplicate key %s", k)
		}
		seen[k] = true
	}
	feed := uuid.New()
	used := map[time.Time]string{}
	monday := time.Date(2026, 1, 5, 0, 0, 0, 0, time.UTC)
	var keys []string
	for i := 0; i < 80; i++ {
		w := monday.AddDate(0, 0, 7*i)
		k := rotationKey(feed, w, used)
		used[w] = k
		keys = append(keys, k)
	}
	last := map[string]int{}
	for i, k := range keys {
		if j, ok := last[k]; ok && i-j < len(promptLibrary) {
			t.Fatalf("%s repeated after %d weeks", k, i-j)
		}
		last[k] = i
	}
	// An admin's choice counts as used.
	w := monday.AddDate(0, 0, 7*80)
	next := rotationKey(feed, w, nil)
	if rotationKey(feed, w, map[time.Time]string{w.AddDate(0, 0, -7): next}) == next {
		t.Fatal("a key used last week came again")
	}
	other := 0
	for i := 0; i < 20; i++ {
		if rotationKey(uuid.New(), monday, nil) != rotationKey(feed, monday, nil) {
			other++
		}
	}
	if other == 0 {
		t.Fatal("every family gets the same prompt")
	}
	if promptID(feed, monday) != promptID(feed, monday) || promptID(feed, monday) == promptID(feed, monday.AddDate(0, 0, 7)) {
		t.Fatal("prompt ids are not stable per week")
	}
}

func TestSummaryAndDigestTimes(t *testing.T) {
	bxl, _ := time.LoadLocation("Europe/Brussels")
	l := func(s string) time.Time { t, _ := time.ParseInLocation("2006-01-02 15:04", s, bxl); return t }
	if _, due := summaryWeek(l("2026-11-22 18:59")); due {
		t.Error("summary before Sunday 19:00")
	}
	if w, due := summaryWeek(l("2026-11-22 19:00")); !due || w.Format(dateLayout) != "2026-11-16" {
		t.Errorf("Sunday 19:00: %v %s", due, w)
	}
	if w, due := summaryWeek(l("2026-11-23 08:00")); !due || w.Format(dateLayout) != "2026-11-16" {
		t.Errorf("Monday morning (quiet hours ended): %v %s", due, w)
	}
	if _, due := summaryWeek(l("2026-11-23 12:00")); due {
		t.Error("summary after Monday noon")
	}
	// Daily at 08:00, weekly on Monday 08:00, local.
	now := l("2026-11-19 08:30")
	if !digestDue(now, bxl, false, ptr(l("2026-11-18 08:05"))) || digestDue(now, bxl, false, ptr(l("2026-11-19 08:01"))) {
		t.Error("daily digest")
	}
	if digestDue(l("2026-11-19 07:59"), bxl, false, ptr(l("2026-11-18 08:05"))) {
		t.Error("daily digest before 08:00")
	}
	if digestDue(now, bxl, true, ptr(l("2026-11-16 08:10"))) || !digestDue(l("2026-11-23 08:00"), bxl, true, ptr(l("2026-11-16 08:10"))) {
		t.Error("weekly digest")
	}
	if !digestDue(now, bxl, true, nil) {
		t.Error("first digest")
	}
}

func TestMemoriesAndMilestones(t *testing.T) {
	if d := memoryDays(time.Date(2027, 2, 28, 0, 0, 0, 0, time.UTC)); len(d) != 2 || d[1] != 29 {
		t.Errorf("28 February: %v", d)
	}
	if d := memoryDays(time.Date(2027, 3, 1, 0, 0, 0, 0, time.UTC)); len(d) != 1 {
		t.Errorf("1 March: %v", d)
	}
	now := at("2026-11-19T12:00:00Z")
	got := milestonesFor(&store.SharingStats{Items: 120, Videos: 0, FirstSend: tp("2025-11-01T00:00:00Z"), LastSend: tp("2026-11-10T00:00:00Z"), Loves: 99}, now)
	want := []string{"first_photo", "photos_10", "photos_100", "year_of_sharing"}
	if len(got) != len(want) {
		t.Fatalf("milestones %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("milestones %v, want %v", got, want)
		}
	}
	// A year since the first send, but nothing in the last 30 days: not yet.
	got = milestonesFor(&store.SharingStats{Items: 1, FirstSend: tp("2025-01-01T00:00:00Z"), LastSend: tp("2026-09-01T00:00:00Z")}, now)
	if len(got) != 1 {
		t.Fatalf("inactive sharer: %v", got)
	}
	if c := cutRunes("😂 "+string(make([]rune, 0))+repeat("a", 300), 280); len([]rune(c)) != 280 || []rune(c)[279] != '…' {
		t.Fatalf("cut: %d", len([]rune(c)))
	}
}

func repeat(s string, n int) string {
	out := ""
	for i := 0; i < n; i++ {
		out += s
	}
	return out
}
