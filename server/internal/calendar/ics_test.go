package calendar

import (
	"os"
	"sort"
	"strings"
	"testing"
	"time"
)

var brussels = mustLoc("Europe/Brussels")

func mustLoc(name string) *time.Location {
	l, err := time.LoadLocation(name)
	if err != nil {
		panic(err)
	}
	return l
}

// testWindow is ±30 days around 2026-10-22 in Brussels.
func testWindow() Window {
	return NewWindow(time.Date(2026, 10, 22, 12, 0, 0, 0, brussels), brussels, 30, 30)
}

func expandFixture(t *testing.T, name string) []Occurrence {
	t.Helper()
	raw, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	p, err := Parse(raw, brussels)
	if err != nil {
		t.Fatalf("parse %s: %v", name, err)
	}
	occ := p.Expand("src", testWindow())
	sort.Slice(occ, func(i, j int) bool { return occ[i].SortTime(brussels).Before(occ[j].SortTime(brussels)) })
	return occ
}

func starts(occ []Occurrence, uid string) []string {
	var out []string
	for _, o := range occ {
		if o.UID != uid {
			continue
		}
		if o.AllDay {
			out = append(out, o.StartDate+".."+o.EndDate)
		} else {
			out = append(out, o.StartAt.UTC().Format("01-02T15:04Z")+"+"+o.EndAt.Sub(*o.StartAt).String())
		}
	}
	return out
}

func eq(t *testing.T, what string, got, want []string) {
	t.Helper()
	if strings.Join(got, " ") != strings.Join(want, " ") {
		t.Errorf("%s:\n got  %v\n want %v", what, got, want)
	}
}

func TestWindow(t *testing.T) {
	w := testWindow()
	if w.From.Format(dateLayout) != "2026-09-22" || w.To.Format(dateLayout) != "2026-11-21" {
		t.Fatalf("window %s", w.Key())
	}
	// Late evening UTC is already the next day in Brussels.
	w2 := NewWindow(time.Date(2026, 10, 22, 23, 30, 0, 0, time.UTC), brussels, 0, 7)
	if w2.From.Format(dateLayout) != "2026-10-23" {
		t.Fatalf("window should follow the frame's zone, got %s", w2.Key())
	}
}

func TestRecurrence(t *testing.T) {
	occ := expandFixture(t, "recurring.ics")
	// Weekly on Monday 18:00 Brussels: across the DST change (Oct 25) the UTC
	// time moves from 16:00 to 17:00. Oct 12 is an EXDATE, Oct 19 moved to
	// Tuesday 17:00 (RECURRENCE-ID override), Nov 2 cancelled by an override
	// whose RECURRENCE-ID is in UTC. Sep 21 is before the window.
	eq(t, "swim", starts(occ, "swim@example.com"), []string{
		"09-28T16:00Z+1h0m0s", "10-05T16:00Z+1h0m0s", "10-20T15:00Z+1h0m0s",
		"10-26T17:00Z+1h0m0s", "11-09T17:00Z+1h0m0s", "11-16T17:00Z+1h0m0s",
	})
	// COUNT=5 from Oct 19, minus the EXDATE, plus two RDATEs.
	eq(t, "standup", starts(occ, "standup@example.com"), []string{
		"10-19T07:00Z+15m0s", "10-20T07:00Z+15m0s", "10-22T07:00Z+15m0s", "10-23T07:00Z+15m0s",
		"10-31T07:00Z+15m0s", "11-01T07:00Z+15m0s",
	})
	if s := starts(occ, "gone@example.com"); len(s) != 0 {
		t.Errorf("cancelled event shown: %v", s)
	}
	ids := map[string]bool{}
	for _, o := range occ {
		if ids[o.ID] {
			t.Errorf("duplicate id %s", o.ID)
		}
		ids[o.ID] = true
		if o.UID == "swim@example.com" {
			if !o.Recurring {
				t.Error("series occurrence not marked recurring")
			}
			if o.Title != "Swimming, Lotte" && o.Title != "Swimming (moved)" {
				t.Errorf("title %q", o.Title)
			}
		}
	}
	// Ids are stable across parses.
	again := expandFixture(t, "recurring.ics")
	if again[0].ID != occ[0].ID {
		t.Error("ids not stable")
	}
}

func TestAllDay(t *testing.T) {
	occ := expandFixture(t, "allday.ics")
	eq(t, "birthday", starts(occ, "bday-oma@example.com"), []string{"2026-11-04..2026-11-04"})
	eq(t, "holiday", starts(occ, "holiday@example.com"), []string{"2026-10-29..2026-11-01"})
	eq(t, "single", starts(occ, "single@example.com"), []string{"2026-10-22..2026-10-22"})
	eq(t, "old", starts(occ, "old@example.com"), nil)
	for _, o := range occ {
		if !o.AllDay || o.StartAt != nil {
			t.Errorf("%s should be all-day without times", o.UID)
		}
	}
}

func TestTimeZones(t *testing.T) {
	occ := expandFixture(t, "timezones.ics")
	eq(t, "windows", starts(occ, "windows@example.com"), []string{"10-22T07:00Z+1h0m0s"})
	eq(t, "custom winter", starts(occ, "custom@example.com"), []string{"10-26T08:00Z+30m0s"})
	eq(t, "custom summer", starts(occ, "custom-summer@example.com"), []string{"10-23T07:00Z+30m0s"})
	eq(t, "mozilla", starts(occ, "mozilla@example.com"), []string{"10-24T16:00Z+1h0m0s"})
	eq(t, "floating", starts(occ, "floating@example.com"), []string{"10-25T07:00Z+30m0s"})
	eq(t, "multi-day", starts(occ, "trip@example.com"), []string{"10-27T19:00Z+14h0m0s"})
	eq(t, "weekly across DST", starts(occ, "weekly-dst@example.com"), []string{
		"10-19T06:30Z+30m0s", "10-26T07:30Z+30m0s", "11-02T07:30Z+30m0s",
	})
	for _, o := range occ {
		if o.UID == "windows@example.com" && o.Notes != "Agenda:\nBudget & planning" {
			t.Errorf("html notes not cleaned: %q", o.Notes)
		}
	}
}

func TestParseName(t *testing.T) {
	raw, _ := os.ReadFile("testdata/allday.ics")
	p, err := Parse(raw, brussels)
	if err != nil || p.Name != "Birthdays" {
		t.Fatalf("name %q err %v", p.Name, err)
	}
	if _, err := Parse([]byte("<html>not a calendar</html>"), brussels); err != ErrNotCalendar {
		t.Fatalf("want ErrNotCalendar, got %v", err)
	}
}

func TestMultipleCalendarsInOneDocument(t *testing.T) {
	a, _ := os.ReadFile("testdata/allday.ics")
	b, _ := os.ReadFile("testdata/recurring.ics")
	p, err := Parse(append(append(a, '\n'), b...), brussels)
	if err != nil {
		t.Fatal(err)
	}
	if n := len(p.Expand("x", testWindow())); n != 3+12 {
		t.Fatalf("got %d occurrences", n)
	}
}

func TestInfiniteDailySeriesIsCapped(t *testing.T) {
	raw := "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nDTSTART:19000101T090000Z\r\nDURATION:PT1M\r\nRRULE:FREQ=MINUTELY\r\nSUMMARY:spam\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
	p, err := Parse([]byte(raw), brussels)
	if err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	occ := p.Expand("x", testWindow())
	if len(occ) > maxOccurrencesPerSeries || time.Since(start) > 5*time.Second {
		t.Fatalf("not capped: %d in %v", len(occ), time.Since(start))
	}
}
