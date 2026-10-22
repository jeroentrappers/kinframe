package calendar

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"
)

func ts(s string) *time.Time {
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		panic(err)
	}
	return &t
}

func TestOptions(t *testing.T) {
	o := Options(json.RawMessage(`{"locale":{"timezone":"Europe/Brussels"},"calendar":{"past_days":3,"future_days":1000}}`))
	if o.TZ != "Europe/Brussels" || o.Past != 3 || o.Future != 365 {
		t.Fatalf("%+v", o)
	}
	o = Options(json.RawMessage(`{"locale":{"timezone":"Mars/Olympus"}}`))
	if o.TZ != "UTC" || o.Past != 30 || o.Future != 30 {
		t.Fatalf("bad zone should fall back to UTC: %+v", o)
	}
}

func TestMerge(t *testing.T) {
	opts := FrameOptions{TZ: "Europe/Brussels", Loc: brussels, Past: 30, Future: 30}
	now := time.Date(2026, 10, 22, 10, 0, 0, 0, time.UTC)
	a, b := uuid.New(), uuid.New()
	shared := Occurrence{ID: "id-a-shared", UID: "same@x", Title: "Shared dinner", StartAt: ts("2026-10-23T17:00:00Z"), EndAt: ts("2026-10-23T19:00:00Z")}
	src := []MergeSource{
		{Info: SourceInfo{ID: a, Kind: "ics", Name: "Family", Color: "coral", Status: "ok"}, Events: []Event{
			FromOccurrence(shared, a),
			FromOccurrence(Occurrence{ID: "id-late", UID: "late@x", Title: "Late", StartAt: ts("2026-10-24T20:00:00Z"), EndAt: ts("2026-10-24T21:00:00Z")}, a),
			FromOccurrence(Occurrence{ID: "id-trip", UID: "trip@x", Title: "Trip", AllDay: true, StartDate: "2026-10-24", EndDate: "2026-10-26"}, a),
		}},
		{Info: SourceInfo{ID: b, Kind: "caldav", Name: "Work", Color: "sky", Status: "ok"}, Events: []Event{
			FromOccurrence(Occurrence{ID: "id-b-shared", UID: "same@x", Title: "Shared dinner (copy)", StartAt: ts("2026-10-23T17:00:00Z"), EndAt: ts("2026-10-23T19:00:00Z")}, b),
			FromOccurrence(Occurrence{ID: "id-early", UID: "early@x", Title: "Early", StartAt: ts("2026-10-23T06:00:00Z"), EndAt: ts("2026-10-23T07:00:00Z")}, b),
			// Midnight start on the trip's first day: the all-day event sorts first.
			FromOccurrence(Occurrence{ID: "id-midnight", UID: "mid@x", Title: "Midnight", StartAt: ts("2026-10-23T22:00:00Z"), EndAt: ts("2026-10-23T23:00:00Z")}, b),
		}},
	}
	marks := []Mark{
		{EventID: "id-late", Title: "Old title", Color: "coral"},
		{EventID: "gone-past", Title: "Last year", AllDay: true, StartDate: "2025-12-24", Color: "rose"},
		{EventID: "far-future", Title: "Wedding", AllDay: true, StartDate: "2027-06-12", Color: "plum"},
	}
	p, changed := Merge(src, marks, opts, now)
	var titles []string
	for _, e := range p.Events {
		titles = append(titles, e.Title)
	}
	eq(t, "order and dedup", titles, []string{"Early", "Shared dinner", "Trip", "Midnight", "Late"})
	if p.Events[1].SourceID != a {
		t.Error("the duplicate should be kept from the first source")
	}
	if !p.Events[4].Countdown || p.Events[0].Countdown {
		t.Error("countdown flag")
	}
	if len(changed) != 1 || changed[0].Title != "Late" || changed[0].Color != "coral" || changed[0].StartAt == nil {
		t.Fatalf("snapshot refresh: %+v", changed)
	}
	if len(p.Countdowns) != 2 || p.Countdowns[0].EventID != "id-late" || p.Countdowns[1].Title != "Wedding" {
		t.Fatalf("countdowns (past dropped, nearest first): %+v", p.Countdowns)
	}
	if p.Window.From != "2026-09-22" || p.Window.To != "2026-11-21" || p.Timezone != "Europe/Brussels" || len(p.Sources) != 2 {
		t.Fatalf("payload header: %+v", p)
	}

	// The digest ignores generation time and sync timestamps, not content.
	p2, _ := Merge(src, marks, opts, now.Add(time.Minute))
	p2.Sources[0].LastSyncedAt = &now
	if Digest(p) != Digest(p2) {
		t.Error("digest changed without visible changes")
	}
	src[1].Info.Color = "mint"
	p3, _ := Merge(src, marks, opts, now)
	if Digest(p) == Digest(p3) {
		t.Error("colour change not in digest")
	}

	// Empty frames still produce a valid payload.
	e, _ := Merge(nil, nil, opts, now)
	b2, _ := json.Marshal(e)
	if string(b2) == "" || e.Events == nil || e.Sources == nil || e.Countdowns == nil {
		t.Fatalf("empty payload: %s", b2)
	}
}

func TestNextColor(t *testing.T) {
	if c := NextColor(nil); c != "coral" {
		t.Fatal(c)
	}
	if c := NextColor([]string{"coral", "amber"}); c != "tangerine" {
		t.Fatal(c)
	}
	if c := NextColor(append(append([]string{}, Colors...), "coral")); c != "tangerine" {
		t.Fatalf("second round: %s", c)
	}
	if !ValidColor("sage") || ValidColor("red") {
		t.Fatal("ValidColor")
	}
}
