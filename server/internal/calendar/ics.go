// Package calendar syncs ICS and CalDAV calendars for frames on the server
// (docs/api/README.md §5.8): fetching, parsing, recurrence expansion, merging
// into the frame's `calendar` change, and encryption of stored credentials.
package calendar

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"html"
	"io"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	ical "github.com/emersion/go-ical"
	"github.com/teambition/rrule-go"
)

// Limits that keep a hostile or huge calendar from hurting the server or the frame.
const (
	maxOccurrencesPerSeries = 1000
	maxEventsPerSource      = 5000
	maxIterations           = 200000 // rrule steps per series (a daily rule since 1900 is ~46k)
	maxTitle                = 200
	maxLocation             = 200
	maxNotes                = 500
)

// Occurrence is one expanded event instance of a source. Timed events carry
// StartAt/EndAt (UTC); all-day events StartDate/EndDate (inclusive, YYYY-MM-DD).
type Occurrence struct {
	ID        string     `json:"id"`
	UID       string     `json:"uid"`
	Title     string     `json:"title"`
	AllDay    bool       `json:"all_day"`
	StartAt   *time.Time `json:"start_at,omitempty"`
	EndAt     *time.Time `json:"end_at,omitempty"`
	StartDate string     `json:"start_date,omitempty"`
	EndDate   string     `json:"end_date,omitempty"`
	Location  string     `json:"location,omitempty"`
	Notes     string     `json:"notes,omitempty"`
	Recurring bool       `json:"recurring,omitempty"`
}

// DedupKey identifies the same event in different sources: UID plus start.
func (o *Occurrence) DedupKey() string {
	if o.AllDay {
		return o.UID + "|" + o.StartDate
	}
	return o.UID + "|" + o.StartAt.UTC().Format(time.RFC3339)
}

// SortTime is the occurrence's start as an instant (all-day: midnight in loc).
func (o *Occurrence) SortTime(loc *time.Location) time.Time {
	if o.AllDay {
		d, _ := time.ParseInLocation(dateLayout, o.StartDate, loc)
		return d
	}
	return *o.StartAt
}

const dateLayout = "2006-01-02"

// Window is the date range a frame shows, inclusive, in the frame's zone.
type Window struct {
	From, To time.Time // dates (midnight UTC)
	Loc      *time.Location
}

// NewWindow returns the window around today (in loc).
func NewWindow(now time.Time, loc *time.Location, pastDays, futureDays int) Window {
	n := now.In(loc)
	today := time.Date(n.Year(), n.Month(), n.Day(), 0, 0, 0, 0, time.UTC)
	return Window{From: today.AddDate(0, 0, -pastDays), To: today.AddDate(0, 0, futureDays), Loc: loc}
}

// Key identifies the window, so expansions can be reused until it moves.
func (w Window) Key() string {
	return w.From.Format(dateLayout) + "/" + w.To.Format(dateLayout) + "/" + w.Loc.String()
}

func (w Window) startInstant() time.Time {
	return time.Date(w.From.Year(), w.From.Month(), w.From.Day(), 0, 0, 0, 0, w.Loc)
}

func (w Window) endInstant() time.Time { // exclusive
	e := w.To.AddDate(0, 0, 1)
	return time.Date(e.Year(), e.Month(), e.Day(), 0, 0, 0, 0, w.Loc)
}

// Parsed is a parsed ICS document (one or more VCALENDARs).
type Parsed struct {
	Name   string // X-WR-CALNAME of the first calendar that has one
	events []*vevent
}

// Events reports how many VEVENTs (masters and overrides) were found.
func (p *Parsed) Events() int { return len(p.events) }

type vevent struct {
	uid                    string
	title, location, notes string
	cancelled              bool
	allDay                 bool
	start                  time.Time // wall
	zone                   zone
	dur                    time.Duration // timed: exact length
	days                   int           // all-day: number of days (>= 1)
	rules                  []rrule.ROption
	rdates                 []time.Time // wall, in the event's zone
	exdates                map[time.Time]bool
	exdays                 map[string]bool // date-only EXDATEs on timed series
	recurrenceID           *time.Time      // wall, in the master's zone (fixed up later)
	recurrenceIDRaw        recurrenceRef
}

type recurrenceRef struct {
	wall time.Time
	zone zone
	date bool
}

// ErrNotCalendar is returned for documents that hold no VCALENDAR at all.
var ErrNotCalendar = errors.New("not an iCalendar document")

// Parse reads ICS data. floating is the zone for times without TZID or "Z"
// (the frame's time zone).
func Parse(raw []byte, floating *time.Location) (*Parsed, error) {
	raw = bytes.TrimPrefix(raw, []byte("\xef\xbb\xbf"))
	if !bytes.Contains(bytes.ToUpper(raw[:min(len(raw), 4096)]), []byte("BEGIN:VCALENDAR")) {
		return nil, ErrNotCalendar
	}
	// Drop blank lines (some servers put them between components); folded
	// continuation lines start with a space or tab and are kept.
	var clean bytes.Buffer
	for _, line := range bytes.Split(raw, []byte("\n")) {
		if len(bytes.TrimRight(line, "\r")) == 0 {
			continue
		}
		clean.Write(bytes.TrimRight(line, "\r"))
		clean.WriteString("\r\n")
	}
	if floating == nil {
		floating = time.UTC
	}
	p := &Parsed{}
	dec := ical.NewDecoder(&clean)
	n := 0
	for {
		cal, err := dec.Decode()
		if err == io.EOF {
			break
		}
		if err != nil {
			if n > 0 {
				break // keep what was readable
			}
			return nil, fmt.Errorf("parse calendar: %w", err)
		}
		n++
		if p.Name == "" {
			if v := cal.Props.Get("X-WR-CALNAME"); v != nil {
				p.Name = strings.TrimSpace(unescapeText(v.Value))
			}
		}
		zs := &zoneSet{floating: locZone{floating}, vtz: map[string]*vtzZone{}}
		for _, c := range cal.Children {
			if c.Name == ical.CompTimezone {
				if id, z := parseVTimezone(c); id != "" && z != nil {
					zs.vtz[id] = z
				}
			}
		}
		for _, c := range cal.Children {
			if c.Name != ical.CompEvent {
				continue
			}
			if ev := parseEvent(c, zs); ev != nil {
				p.events = append(p.events, ev)
			}
		}
	}
	if n == 0 {
		return nil, ErrNotCalendar
	}
	return p, nil
}

var (
	wallLayout = "20060102T150405"
	tagRe      = regexp.MustCompile(`(?is)<(?:!--.*?--|/?(?:html|head|body|meta|title|style|p|br|div|span|a|b|i|u|em|strong|ul|ol|li|font|table|tbody|tr|td|th|hr|h[1-6]|img|o:p)\b[^>]*)>`)
	spaceRe    = regexp.MustCompile(`[ \t]+`)
	blankRe    = regexp.MustCompile(`\n{3,}`)
)

// parseWallValue parses a DATE or DATE-TIME value. utc reports a trailing "Z".
func parseWallValue(v string) (wall time.Time, isDate, utc bool, err error) {
	v = strings.TrimSpace(v)
	switch {
	case len(v) == 8:
		t, err := time.Parse("20060102", v)
		return t, true, false, err
	case len(v) == 16 && (v[15] == 'Z' || v[15] == 'z'):
		t, err := time.Parse(wallLayout, v[:15])
		return t, false, true, err
	case len(v) >= 15:
		t, err := time.Parse(wallLayout, v[:15])
		return t, false, false, err
	}
	return time.Time{}, false, false, fmt.Errorf("bad date %q", v)
}

// propTime reads a date/date-time property: its wall time and zone.
func (zs *zoneSet) propTime(p *ical.Prop) (wall time.Time, isDate bool, z zone, err error) {
	return zs.valueTime(p.Value, p.Params.Get(ical.ParamTimezoneID), p.Params.Get(ical.ParamValue))
}

func (zs *zoneSet) valueTime(v, tzid, valueType string) (time.Time, bool, zone, error) {
	w, isDate, utc, err := parseWallValue(v)
	if err != nil {
		return w, false, nil, err
	}
	if strings.EqualFold(valueType, "DATE") && !isDate {
		w = time.Date(w.Year(), w.Month(), w.Day(), 0, 0, 0, 0, time.UTC)
		isDate = true
	}
	switch {
	case isDate:
		return w, true, zs.floating, nil
	case utc:
		return w, false, utcZone, nil
	default:
		return w, false, zs.resolve(tzid), nil
	}
}

func unescapeText(s string) string {
	r := strings.NewReplacer(`\n`, "\n", `\N`, "\n", `\,`, ",", `\;`, ";", `\\`, `\`)
	return r.Replace(s)
}

func cleanText(s string, limit int, multiline bool) string {
	s = unescapeText(s)
	if multiline && tagRe.MatchString(s) {
		s = strings.NewReplacer("<br>", "\n", "<br/>", "\n", "<br />", "\n", "</p>", "\n").Replace(s)
		s = tagRe.ReplaceAllString(s, "")
		s = html.UnescapeString(s)
	}
	s = strings.ReplaceAll(s, "\r", "")
	if !multiline {
		s = strings.Join(strings.Fields(s), " ")
	} else {
		lines := strings.Split(s, "\n")
		for i, l := range lines {
			lines[i] = strings.TrimSpace(spaceRe.ReplaceAllString(l, " "))
		}
		s = blankRe.ReplaceAllString(strings.Join(lines, "\n"), "\n\n")
		s = strings.TrimSpace(s)
	}
	if !utf8.ValidString(s) {
		s = strings.ToValidUTF8(s, "")
	}
	if utf8.RuneCountInString(s) > limit {
		r := []rune(s)
		s = strings.TrimSpace(string(r[:limit-1])) + "…"
	}
	return s
}

func parseEvent(c *ical.Component, zs *zoneSet) *vevent {
	dtstart := c.Props.Get(ical.PropDateTimeStart)
	if dtstart == nil {
		return nil
	}
	start, isDate, z, err := zs.propTime(dtstart)
	if err != nil {
		return nil
	}
	ev := &vevent{start: start, allDay: isDate, zone: z, exdates: map[time.Time]bool{}, exdays: map[string]bool{}}
	if p := c.Props.Get(ical.PropUID); p != nil {
		ev.uid = strings.TrimSpace(p.Value)
	}
	if p := c.Props.Get(ical.PropSummary); p != nil {
		ev.title = cleanText(p.Value, maxTitle, false)
	}
	if p := c.Props.Get(ical.PropLocation); p != nil {
		ev.location = cleanText(p.Value, maxLocation, false)
	}
	if p := c.Props.Get(ical.PropDescription); p != nil {
		ev.notes = cleanText(p.Value, maxNotes, true)
	}
	if p := c.Props.Get(ical.PropStatus); p != nil && strings.EqualFold(strings.TrimSpace(p.Value), "CANCELLED") {
		ev.cancelled = true
	}
	if ev.uid == "" {
		sum := sha256.Sum256([]byte(ev.title + "\x00" + dtstart.Value))
		ev.uid = "nouid-" + base64.RawURLEncoding.EncodeToString(sum[:9])
	}

	// End: DTEND, else DURATION, else the default (one day for dates, zero for times).
	if ev.allDay {
		ev.days = 1
		if p := c.Props.Get(ical.PropDateTimeEnd); p != nil {
			if end, _, _, err := zs.propTime(p); err == nil {
				end = time.Date(end.Year(), end.Month(), end.Day(), 0, 0, 0, 0, time.UTC)
				if d := int(end.Sub(start).Hours() / 24); d > 1 {
					ev.days = d
				}
			}
		} else if p := c.Props.Get(ical.PropDuration); p != nil {
			if d, err := p.Duration(); err == nil && d > 0 {
				ev.days = max(1, int((d+24*time.Hour-1)/(24*time.Hour)))
			}
		}
	} else {
		if p := c.Props.Get(ical.PropDateTimeEnd); p != nil {
			if end, endIsDate, ez, err := zs.propTime(p); err == nil {
				if endIsDate {
					ez = z
				}
				if d := ez.instant(end).Sub(z.instant(start)); d > 0 {
					ev.dur = d
				}
			}
		} else if p := c.Props.Get(ical.PropDuration); p != nil {
			if d, err := p.Duration(); err == nil && d > 0 {
				ev.dur = d
			}
		}
	}

	for _, p := range c.Props.Values(ical.PropRecurrenceRule) {
		opt, err := rrule.StrToROption(p.Value)
		if err != nil {
			continue
		}
		if !opt.Until.IsZero() {
			// rrule-go parses UNTIL as UTC (or floating as UTC). Convert a UTC
			// UNTIL to the series' wall clock; a date UNTIL covers the whole day.
			raw := strings.ToUpper(p.Value)
			if i := strings.Index(raw, "UNTIL="); i >= 0 {
				u := raw[i+6:]
				if j := strings.IndexByte(u, ';'); j >= 0 {
					u = u[:j]
				}
				switch {
				case len(u) == 8:
					opt.Until = opt.Until.Add(24*time.Hour - time.Second)
				case strings.HasSuffix(u, "Z") && !ev.allDay:
					opt.Until = z.wall(opt.Until)
				}
			}
		}
		ev.rules = append(ev.rules, *opt)
	}
	for _, p := range c.Props.Values(ical.PropRecurrenceDates) {
		for _, v := range strings.Split(p.Value, ",") {
			if i := strings.IndexByte(v, '/'); i >= 0 { // PERIOD: start/end or start/duration
				v = v[:i]
			}
			w, d, wz, err := zs.valueTime(v, p.Params.Get(ical.ParamTimezoneID), p.Params.Get(ical.ParamValue))
			if err != nil {
				continue
			}
			ev.rdates = append(ev.rdates, convertWall(w, d, wz, ev))
		}
	}
	for _, p := range c.Props.Values(ical.PropExceptionDates) {
		for _, v := range strings.Split(p.Value, ",") {
			w, d, wz, err := zs.valueTime(v, p.Params.Get(ical.ParamTimezoneID), p.Params.Get(ical.ParamValue))
			if err != nil {
				continue
			}
			if d && !ev.allDay {
				ev.exdays[w.Format(dateLayout)] = true
				continue
			}
			ev.exdates[convertWall(w, d, wz, ev)] = true
		}
	}
	if p := c.Props.Get(ical.PropRecurrenceID); p != nil {
		if w, d, rz, err := zs.propTime(p); err == nil {
			ev.recurrenceIDRaw = recurrenceRef{wall: w, zone: rz, date: d}
			ev.recurrenceID = &w
		}
	}
	return ev
}

// convertWall expresses a wall time given in zone wz in the event's own zone.
func convertWall(w time.Time, isDate bool, wz zone, ev *vevent) time.Time {
	if isDate || ev.allDay || wz == nil || wz == ev.zone {
		if ev.allDay {
			return time.Date(w.Year(), w.Month(), w.Day(), 0, 0, 0, 0, time.UTC)
		}
		return w
	}
	return ev.zone.wall(wz.instant(w))
}

// Expand returns the occurrences that overlap the window. sourceKey makes
// event ids unique per source.
func (p *Parsed) Expand(sourceKey string, w Window) []Occurrence {
	masters := map[string]*vevent{}
	overrides := map[string][]*vevent{}
	var order []string
	for _, ev := range p.events {
		if _, seen := masters[ev.uid]; !seen {
			if _, seen2 := overrides[ev.uid]; !seen2 {
				order = append(order, ev.uid)
			}
		}
		if ev.recurrenceID != nil {
			overrides[ev.uid] = append(overrides[ev.uid], ev)
		} else if m, ok := masters[ev.uid]; !ok || m.cancelled {
			masters[ev.uid] = ev
		}
	}
	var out []Occurrence
	for _, uid := range order {
		m := masters[uid]
		ovs := overrides[uid]
		if m != nil && m.cancelled && len(m.rules) == 0 && len(m.rdates) == 0 {
			continue
		}
		replaced := map[time.Time]bool{}
		for _, o := range ovs {
			key := o.recurrenceIDRaw.wall
			if m != nil {
				key = convertWall(o.recurrenceIDRaw.wall, o.recurrenceIDRaw.date, o.recurrenceIDRaw.zone, m)
			}
			o.recurrenceID = &key
			replaced[key] = true
		}
		if m != nil && !m.cancelled {
			recurring := len(m.rules) > 0 || len(m.rdates) > 0
			for _, start := range m.occurrences(w) {
				if replaced[start] || m.exdates[start] || m.exdays[start.Format(dateLayout)] {
					continue
				}
				key := ""
				if recurring {
					key = start.Format(wallLayout)
				}
				if o, ok := m.emit(sourceKey, key, start, w, recurring); ok {
					out = append(out, o)
				}
			}
		}
		for _, o := range ovs {
			if o.cancelled {
				continue
			}
			if occ, ok := o.emit(sourceKey, o.recurrenceID.Format(wallLayout), o.start, w, true); ok {
				out = append(out, occ)
			}
		}
		if len(out) >= maxEventsPerSource {
			out = out[:maxEventsPerSource]
			break
		}
	}
	return out
}

// occurrences lists the series' start wall times that may touch the window.
func (ev *vevent) occurrences(w Window) []time.Time {
	if len(ev.rules) == 0 && len(ev.rdates) == 0 {
		return []time.Time{ev.start}
	}
	span := ev.dur
	if ev.allDay {
		span = time.Duration(ev.days) * 24 * time.Hour
	}
	// Widen by the event's length and a day each side for zone offsets.
	lo := w.From.Add(-span - 48*time.Hour)
	hi := w.To.Add(72 * time.Hour)
	seen := map[time.Time]bool{}
	var out []time.Time
	add := func(t time.Time) {
		if t.Before(lo) || t.After(hi) || seen[t] || len(out) >= maxOccurrencesPerSeries {
			return
		}
		seen[t] = true
		out = append(out, t)
	}
	add(ev.start) // DTSTART is always the first instance
	for _, opt := range ev.rules {
		opt.Dtstart = ev.start
		r, err := rrule.NewRRule(opt)
		if err != nil {
			continue
		}
		next := r.Iterator()
		for i := 0; i < maxIterations; i++ {
			t, ok := next()
			if !ok || t.After(hi) {
				break
			}
			add(t)
		}
	}
	for _, t := range ev.rdates {
		add(t)
	}
	return out
}

func eventID(sourceKey, uid, key string) string {
	sum := sha256.Sum256([]byte(sourceKey + "\x00" + uid + "\x00" + key))
	return base64.RawURLEncoding.EncodeToString(sum[:16])
}

// emit builds an occurrence starting at wall time start if it overlaps w.
func (ev *vevent) emit(sourceKey, key string, start time.Time, w Window, recurring bool) (Occurrence, bool) {
	o := Occurrence{
		ID: eventID(sourceKey, ev.uid, key), UID: ev.uid, Title: ev.title, AllDay: ev.allDay,
		Location: ev.location, Notes: ev.notes, Recurring: recurring,
	}
	if ev.allDay {
		end := start.AddDate(0, 0, ev.days-1)
		if end.Before(w.From) || start.After(w.To) {
			return o, false
		}
		o.StartDate, o.EndDate = start.Format(dateLayout), end.Format(dateLayout)
		return o, true
	}
	s := ev.zone.instant(start).UTC()
	e := s.Add(ev.dur)
	ws, we := w.startInstant(), w.endInstant()
	if !s.Before(we) || e.Before(ws) || (ev.dur > 0 && e.Equal(ws)) {
		return o, false
	}
	o.StartAt, o.EndAt = &s, &e
	return o, true
}
