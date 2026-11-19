package api

// Encouragement to share (docs/spec/family-social.md §9, README §5.20): shared helpers of the engagement area
// (freshness formula, time zones and weeks, quiet hours, the reminder budget, people and languages).

import (
	"context"
	"math"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/i18n"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

const (
	defaultQuietAfter = 7
	freshnessDays     = 14.0
	// Reminder budget (README §5.20): budgeted pushes per person in any 7 days.
	budgetNormal = 2
	budgetFewer  = 1
	// After this many ignored reminders in a row the level drops to fewer.
	ignoredLimit = 4
)

// reminderActions are the buttons on every reminder push (README §6.3).
var reminderActions = []string{"snooze_month", "fewer_reminders"}

// ---------------------------------------------------------------- freshness

// freshnessOf computes a Freshness (README §5.20): level = max(L(personal), 0.5 × L(source)) with
// L(t) = clamp(1 − days(t)/14, 0, 1); days_quiet whole days since the last personal photo; state never, fresh
// (days_quiet 0), recent (below quietAfter) or quiet.
func freshnessOf(now time.Time, personal, source *time.Time, quietAfter int) gen.Freshness {
	if quietAfter <= 0 {
		quietAfter = defaultQuietAfter
	}
	l := func(t *time.Time) float64 {
		if t == nil {
			return 0
		}
		d := now.Sub(*t).Hours() / 24
		return math.Max(0, math.Min(1, 1-d/freshnessDays))
	}
	level := math.Max(l(personal), 0.5*l(source))
	out := gen.Freshness{Level: float32(math.Round(level*1000) / 1000)}
	switch {
	case personal == nil && source == nil:
		out.State = "never"
	case personal == nil:
		out.State = "quiet"
	default:
		days := int(math.Floor(math.Max(0, now.Sub(*personal).Hours()/24)))
		out.DaysQuiet, out.LastSharedAt = &days, personal
		switch {
		case days == 0:
			out.State = "fresh"
		case days < quietAfter:
			out.State = "recent"
		default:
			out.State = "quiet"
		}
	}
	return out
}

// ---------------------------------------------------------------- zones and weeks

// feedZone is a family's time zone (README §5.20): set by an admin, else the most common zone of its
// subscribed frames, else the owner's, else Europe/Brussels.
func feedZone(fe *store.FeedEngagement) *time.Location {
	return loadZone(deref(fe.Timezone), deref(fe.FrameZone), deref(fe.OwnerZone), defaultZone)
}

func feedZoneName(fe *store.FeedEngagement) string { return feedZone(fe).String() }

// userZone is a person's time zone (User.timezone, else Europe/Brussels).
func userZone(tz *string) *time.Location { return loadZone(deref(tz), defaultZone) }

// mondayOf is the Monday (a date at UTC midnight) of the week t falls in, in loc.
func mondayOf(t time.Time, loc *time.Location) time.Time {
	d := dateOf(t.In(loc))
	off := (int(d.Weekday()) + 6) % 7 // Monday 0 … Sunday 6
	return d.AddDate(0, 0, -off)
}

// weekBounds are the instants a week (its Monday as a date) starts and ends in loc.
func weekBounds(monday time.Time, loc *time.Location) (time.Time, time.Time) {
	start := time.Date(monday.Year(), monday.Month(), monday.Day(), 0, 0, 0, 0, loc)
	end := time.Date(monday.Year(), monday.Month(), monday.Day()+7, 0, 0, 0, 0, loc)
	return start, end
}

// monthOf is the first day (a date) of t's month in loc.
func monthOf(t time.Time, loc *time.Location) time.Time {
	l := t.In(loc)
	return time.Date(l.Year(), l.Month(), 1, 0, 0, 0, 0, time.UTC)
}

func isMonday(d time.Time) bool { return d.Weekday() == time.Monday }

// ---------------------------------------------------------------- quiet hours and budget

// parseClock reads "HH:MM" as minutes after midnight.
func parseClock(s string) (int, bool) {
	if len(s) != 5 || s[2] != ':' {
		return 0, false
	}
	h, m := int(s[0]-'0')*10+int(s[1]-'0'), int(s[3]-'0')*10+int(s[4]-'0')
	if s[0] < '0' || s[0] > '9' || s[1] < '0' || s[1] > '9' || s[3] < '0' || s[3] > '9' || s[4] < '0' || s[4] > '9' || h > 23 || m > 59 {
		return 0, false
	}
	return h*60 + m, true
}

// inQuietHours reports whether the local time falls in [start, end) (wrapping over midnight when start > end;
// start == end means no quiet hours).
func inQuietHours(local time.Time, start, end string) bool {
	s, ok1 := parseClock(start)
	e, ok2 := parseClock(end)
	if !ok1 || !ok2 || s == e {
		return false
	}
	m := local.Hour()*60 + local.Minute()
	if s < e {
		return m >= s && m < e
	}
	return m >= s || m < e
}

// budgetFor is how many budgeted reminders the preferences allow in 7 days (0 when off or snoozed).
func budgetFor(p *store.ReminderPrefs, now time.Time) int {
	if p.SnoozedUntil != nil && p.SnoozedUntil.After(now) {
		return 0
	}
	switch p.Level {
	case "off":
		return 0
	case "fewer":
		return budgetFewer
	}
	return budgetNormal
}

// reminderAllowed decides whether a budgeted reminder of kind may go to the person now: level, snooze, the
// kind's own switch, quiet hours and the 7-day budget (used = budgeted reminders in the last 7 days).
func reminderAllowed(p *store.ReminderPrefs, kind string, now time.Time, used int) bool {
	if p.DeletedAt != nil {
		return false
	}
	budget := budgetFor(p, now)
	if budget == 0 || used >= budget {
		return false
	}
	switch kind {
	case "reminder_streak":
		if p.Level == "fewer" {
			return false
		}
	case "reminder_birthday":
		if !p.BirthdayReminders {
			return false
		}
	case "weekly_summary":
		if !p.WeeklySummary {
			return false
		}
	}
	return !quietNow(p, now)
}

// quietNow reports whether the person is in their quiet hours at now.
func quietNow(p *store.ReminderPrefs, now time.Time) bool {
	return inQuietHours(now.In(userZone(p.Timezone)), p.QuietStart, p.QuietEnd)
}

// ---------------------------------------------------------------- people and languages

// engPersonRef renders a PersonRef ("Former member" handling is the clients').
func (s *Server) engPersonRef(p store.Person) gen.PersonRef {
	out := gen.PersonRef{UserId: p.UserID, DisplayName: p.DisplayName, AvatarUrl: s.avatarURL(p.AvatarKey)}
	if p.DeletedAt != nil {
		out.FormerMember, out.AvatarUrl = ptr(true), nil
	}
	return out
}

// engPersonRefs renders these users in order, skipping unknown ones.
func (s *Server) engPersonRefs(ctx context.Context, ids []uuid.UUID) []gen.PersonRef {
	out := []gen.PersonRef{}
	ps, err := s.st.People(ctx, s.st.Q(), ids)
	if err != nil {
		return out
	}
	for _, id := range ids {
		if p, ok := ps[id]; ok {
			out = append(out, s.engPersonRef(p))
		}
	}
	return out
}

type acceptLanguageKey struct{}

// withAcceptLanguage keeps the request's Accept-Language for handlers that answer in the caller's language.
func withAcceptLanguage(ctx context.Context, r *http.Request) context.Context {
	return context.WithValue(ctx, acceptLanguageKey{}, r.Header.Get("Accept-Language"))
}

// engLang is the caller's language: Accept-Language, else their account's locale, else English.
func engLang(ctx context.Context) string {
	al, _ := ctx.Value(acceptLanguageKey{}).(string)
	var loc string
	if p := userFrom(ctx); p != nil && p.user != nil && p.user.Locale != nil {
		loc = *p.user.Locale
	}
	return i18n.Pick(al, loc)
}

// firstName is the part of a display name frames show ("New photos from Ben").
func engFirstName(name string) string {
	if f := i18n.FirstName(name); f != "" {
		return f
	}
	return strings.TrimSpace(name)
}

// engID scans a single id column.
type engID struct {
	ID uuid.UUID `db:"id"`
}

// engRows collects rows into structs by column name.
func engRows[T any](rows pgx.Rows, err error) ([]T, error) {
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByName[T])
}

// engUnique drops duplicates, keeping order.
func engUnique(ids []uuid.UUID) []uuid.UUID {
	seen := map[uuid.UUID]bool{}
	out := ids[:0:0]
	for _, id := range ids {
		if !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	return out
}
