package calendar_test

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/calendar"
	"github.com/jeroentrappers/kinframe/server/internal/calendar/calendartest"
)

func TestNormalizeAndRedact(t *testing.T) {
	for in, want := range map[string]string{
		"webcal://p52-caldav.icloud.com/published/2/abc":                             "https://p52-caldav.icloud.com/published/2/abc",
		" WEBCALS://example.org/x.ics ":                                              "https://example.org/x.ics",
		"https://calendar.google.com/calendar/ical/a%40b/private-123/basic.ics#frag": "https://calendar.google.com/calendar/ical/a%40b/private-123/basic.ics",
	} {
		got, err := calendar.NormalizeURL(in)
		if err != nil || got != want {
			t.Errorf("NormalizeURL(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	for _, bad := range []string{"ftp://x/y", "calendar.google.com/x", "https://user:pw@x.org/a.ics", "https://"} {
		if _, err := calendar.NormalizeURL(bad); err == nil {
			t.Errorf("NormalizeURL(%q) accepted", bad)
		}
	}
	for in, want := range map[string]string{
		"https://calendar.google.com/calendar/ical/a%40b/private-123/basic.ics":                         "https://calendar.google.com/…/basic.ics",
		"https://outlook.office365.com/owa/calendar/abcdef0123456789abcdef0123456789/reachcalendar.ics": "https://outlook.office365.com/…/reachcalendar.ics",
		"https://example.org/cal.ics":       "https://example.org/cal.ics",
		"https://example.org/feed?token=s3": "https://example.org/…/feed",
		"https://example.org":               "https://example.org",
	} {
		if got := calendar.RedactURL(in); got != want {
			t.Errorf("RedactURL(%q) = %q, want %q", in, got, want)
		}
	}
}

func fixture(t *testing.T, name string) string {
	b, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestFetchICS(t *testing.T) {
	fake := calendartest.New()
	t.Cleanup(fake.Close)
	fake.SetICS("family.ics", fixture(t, "recurring.ics"))
	f := &calendar.Fetcher{UserAgent: "kinframe-test"}
	ctx := context.Background()

	r, err := f.FetchICS(ctx, fake.ICSURL("family.ics"), "", "", "", "")
	if err != nil || r.NotModified || r.ETag == "" || !strings.Contains(string(r.Raw), "BEGIN:VCALENDAR") {
		t.Fatalf("first fetch: %+v %v", r, err)
	}
	// Conditional request: 304 keeps the cached copy.
	r2, err := f.FetchICS(ctx, fake.ICSURL("family.ics"), r.ETag, r.LastModified, "", "")
	if err != nil || !r2.NotModified || fake.NotModified.Load() != 1 {
		t.Fatalf("conditional fetch: %+v %v", r2, err)
	}

	// Errors are classified.
	if _, err := f.FetchICS(ctx, fake.ICSURL("missing.ics"), "", "", "", ""); err == nil || calendar.IsAuth(err) || !strings.Contains(err.Error(), "404") {
		t.Fatalf("404: %v", err)
	}
	fake.SetICS("private.ics", fixture(t, "allday.ics"))
	if _, err := f.FetchICS(ctx, fake.ICSURL("private.ics"), "", "", "", ""); !calendar.IsAuth(err) {
		t.Fatalf("403 should be an auth failure: %v", err)
	}
	if _, err := f.FetchICS(ctx, fake.ICSURL("private.ics"), "", "", calendartest.User, calendartest.Password); err != nil {
		t.Fatalf("basic auth ICS: %v", err)
	}
	fake.SetFail(true)
	if _, err := f.FetchICS(ctx, fake.ICSURL("family.ics"), "", "", "", ""); err == nil || !strings.Contains(err.Error(), "500") {
		t.Fatalf("500: %v", err)
	}
	fake.SetFail(false)
	if _, err := f.FetchICS(ctx, "http://127.0.0.1:1/x.ics", "", "", "", ""); err == nil || !strings.Contains(err.Error(), "connect") {
		t.Fatalf("connection refused: %v", err)
	}
	small := &calendar.Fetcher{MaxBytes: 100}
	if _, err := small.FetchICS(ctx, fake.ICSURL("family.ics"), "", "", "", ""); err == nil || !strings.Contains(err.Error(), "larger") {
		t.Fatalf("size limit: %v", err)
	}
}

const davEvent = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTIMEZONE\r\nTZID:Europe/Brussels\r\nEND:VTIMEZONE\r\n" +
	"BEGIN:VEVENT\r\nUID:dentist@nextcloud\r\nDTSTART;TZID=Europe/Brussels:20261024T100000\r\n" +
	"DTEND;TZID=Europe/Brussels:20261024T110000\r\nSUMMARY:Dentist <Mila>\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

func TestCalDAV(t *testing.T) {
	fake := calendartest.New()
	t.Cleanup(fake.Close)
	fake.PutEvent("family", "dentist", davEvent)
	f := &calendar.Fetcher{}
	ctx := context.Background()

	// The server root: /.well-known/caldav → principal → home set → calendars.
	cals, selected, err := f.CalDAVCalendars(ctx, fake.URL, calendartest.User, calendartest.Password)
	if err != nil {
		t.Fatal(err)
	}
	if len(cals) != 2 || cals[0].Name != "Family" || cals[1].Name != "Work & school" || selected != "" ||
		cals[0].URL != fake.CalendarURL("family") {
		t.Fatalf("calendars %+v selected %q (tasks-only calendars must be skipped)", cals, selected)
	}
	// A calendar URL selects that calendar.
	_, selected, err = f.CalDAVCalendars(ctx, fake.CalendarURL("work"), calendartest.User, calendartest.Password)
	if err != nil || selected != fake.CalendarURL("work") {
		t.Fatalf("selected %q, %v", selected, err)
	}
	// A wrong password is an auth failure.
	if _, _, err := f.CalDAVCalendars(ctx, fake.URL+"/dav/", calendartest.User, "wrong"); !calendar.IsAuth(err) {
		t.Fatalf("wrong password: %v", err)
	}

	from := time.Date(2026, 9, 22, 0, 0, 0, 0, time.UTC)
	raw, err := f.FetchCalDAV(ctx, fake.CalendarURL("family"), calendartest.User, calendartest.Password, from, from.AddDate(0, 2, 0))
	if err != nil {
		t.Fatal(err)
	}
	if tr, _ := fake.LastTimeRange.Load().(string); !strings.Contains(tr, `start="20260922T000000Z"`) {
		t.Fatalf("time range not sent: %q", tr)
	}
	loc, _ := time.LoadLocation("Europe/Brussels")
	p, err := calendar.Parse(raw, loc)
	if err != nil {
		t.Fatal(err)
	}
	occ := p.Expand("s", calendar.NewWindow(time.Date(2026, 10, 22, 12, 0, 0, 0, loc), loc, 30, 30))
	if len(occ) != 1 || occ[0].Title != "Dentist <Mila>" || occ[0].StartAt.UTC().Hour() != 8 {
		t.Fatalf("caldav events: %+v", occ)
	}
	// An empty calendar is a valid, empty document.
	raw, err = f.FetchCalDAV(ctx, fake.CalendarURL("work"), calendartest.User, calendartest.Password, from, from.AddDate(0, 2, 0))
	if err != nil {
		t.Fatal(err)
	}
	if p, err := calendar.Parse(raw, loc); err != nil || p.Events() != 0 {
		t.Fatalf("empty calendar: %v", err)
	}
	if _, err := f.FetchCalDAV(ctx, fake.CalendarURL("family"), calendartest.User, "nope", from, from); !calendar.IsAuth(err) {
		t.Fatalf("report with a wrong password: %v", err)
	}
}

func TestSealer(t *testing.T) {
	a, err := calendar.NewSealer(nil, []byte(strings.Repeat("k", 32)))
	if err != nil {
		t.Fatal(err)
	}
	box := a.Seal("https://calendar.google.com/secret")
	if strings.Contains(string(box), "secret") {
		t.Fatal("not encrypted")
	}
	if got, err := a.Open(box); err != nil || got != "https://calendar.google.com/secret" {
		t.Fatalf("open: %q %v", got, err)
	}
	if a.Seal("") != nil {
		t.Fatal("empty should seal to nil")
	}
	if got, err := a.Open(nil); err != nil || got != "" {
		t.Fatal("nil should open to empty")
	}
	// A dedicated secrets key differs from the derived one.
	b, _ := calendar.NewSealer([]byte(strings.Repeat("z", 32)), []byte(strings.Repeat("k", 32)))
	if _, err := b.Open(box); err != calendar.ErrUnseal {
		t.Fatalf("other key: %v", err)
	}
	if _, err := calendar.NewSealer(nil, []byte("short")); err == nil {
		t.Fatal("short key accepted")
	}
}
