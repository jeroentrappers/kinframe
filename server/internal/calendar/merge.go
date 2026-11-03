package calendar

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"
)

// FrameOptions are the calendar-related frame settings.
type FrameOptions struct {
	TZ           string
	Loc          *time.Location
	Past, Future int
}

// Options reads the frame settings document (defaults for anything missing).
func Options(settings json.RawMessage) FrameOptions {
	var s struct {
		Locale struct {
			Timezone string `json:"timezone"`
		} `json:"locale"`
		Calendar struct {
			PastDays   *int `json:"past_days"`
			FutureDays *int `json:"future_days"`
		} `json:"calendar"`
	}
	_ = json.Unmarshal(settings, &s)
	o := FrameOptions{TZ: s.Locale.Timezone, Past: 30, Future: 30}
	if s.Calendar.PastDays != nil {
		o.Past = min(max(*s.Calendar.PastDays, 1), 365)
	}
	if s.Calendar.FutureDays != nil {
		o.Future = min(max(*s.Calendar.FutureDays, 7), 365)
	}
	o.Loc = loadLocation(o.TZ)
	if o.Loc == nil {
		o.TZ, o.Loc = "UTC", time.UTC
	}
	return o
}

// Window returns the frame's window at now.
func (o FrameOptions) Window(now time.Time) Window { return NewWindow(now, o.Loc, o.Past, o.Future) }

// Event is one event in the `calendar` change (schema CalendarEvent).
type Event struct {
	ID             string     `json:"id"`
	SourceID       uuid.UUID  `json:"source_id"`
	Title          string     `json:"title"`
	AllDay         bool       `json:"all_day"`
	StartAt        *time.Time `json:"start_at,omitempty"`
	EndAt          *time.Time `json:"end_at,omitempty"`
	StartDate      string     `json:"start_date,omitempty"`
	EndDate        string     `json:"end_date,omitempty"`
	Location       string     `json:"location,omitempty"`
	Notes          string     `json:"notes,omitempty"`
	Recurring      bool       `json:"recurring"`
	Countdown      bool       `json:"countdown"`
	SenderName     string     `json:"sender_name,omitempty"`
	InvitationID   *uuid.UUID `json:"invitation_id,omitempty"`
	HasPhoto       bool       `json:"has_photo,omitempty"`
	PhotoPlacement string     `json:"photo_placement,omitempty"`

	uid string
}

// FromOccurrence turns an expanded occurrence into an event of source id.
func FromOccurrence(o Occurrence, source uuid.UUID) Event {
	return Event{
		ID: o.ID, SourceID: source, Title: o.Title, AllDay: o.AllDay, StartAt: o.StartAt, EndAt: o.EndAt,
		StartDate: o.StartDate, EndDate: o.EndDate, Location: o.Location, Notes: o.Notes, Recurring: o.Recurring, uid: o.UID,
	}
}

// WithUID sets the UID used for de-duplication across sources.
func (e Event) WithUID(uid string) Event { e.uid = uid; return e }

func (e *Event) dedupKey() string {
	if e.AllDay {
		return e.uid + "|" + e.StartDate
	}
	return e.uid + "|" + e.StartAt.UTC().Format(time.RFC3339)
}

func (e *Event) start(loc *time.Location) time.Time {
	if e.AllDay {
		d, _ := time.ParseInLocation(dateLayout, e.StartDate, loc)
		return d
	}
	return *e.StartAt
}

// SourceInfo describes a source in the change (schema CalendarSourceInfo).
type SourceInfo struct {
	ID           uuid.UUID  `json:"id"`
	Kind         string     `json:"kind"`
	Name         string     `json:"name"`
	Color        string     `json:"color"`
	Status       string     `json:"status"`
	LastSyncedAt *time.Time `json:"last_synced_at"`
}

// Mark is a countdown: an event id plus a snapshot of the event.
type Mark struct {
	EventID   string     `json:"event_id"`
	SourceID  *uuid.UUID `json:"source_id"`
	Title     string     `json:"title"`
	AllDay    bool       `json:"all_day"`
	StartAt   *time.Time `json:"start_at,omitempty"`
	StartDate string     `json:"start_date,omitempty"`
	Color     string     `json:"color"`
}

// Payload is the `calendar` change (schema Calendar).
type Payload struct {
	GeneratedAt time.Time `json:"generated_at"`
	Timezone    string    `json:"timezone"`
	Window      struct {
		From string `json:"from"`
		To   string `json:"to"`
	} `json:"window"`
	Sources    []SourceInfo `json:"sources"`
	Events     []Event      `json:"events"`
	Countdowns []Mark       `json:"countdowns"`
}

// MergeSource is one source with its events for the window.
type MergeSource struct {
	Info   SourceInfo
	Events []Event
}

// Merge combines the sources (in display order) into one payload. Events in
// several sources (same UID and start) are kept once, from the first source.
// It returns the countdown marks whose snapshot changed, to be saved.
func Merge(srcs []MergeSource, marks []Mark, opts FrameOptions, now time.Time) (*Payload, []Mark) {
	w := opts.Window(now)
	p := &Payload{GeneratedAt: now.UTC().Truncate(time.Second), Timezone: opts.TZ, Sources: []SourceInfo{}, Events: []Event{}, Countdowns: []Mark{}}
	p.Window.From, p.Window.To = w.From.Format(dateLayout), w.To.Format(dateLayout)
	marked := map[string]int{}
	for i, m := range marks {
		marked[m.EventID] = i
	}
	colors := map[uuid.UUID]string{}
	seen := map[string]bool{}
	for _, s := range srcs {
		p.Sources = append(p.Sources, s.Info)
		colors[s.Info.ID] = s.Info.Color
		for _, e := range s.Events {
			if k := e.dedupKey(); e.uid != "" {
				if seen[k] {
					continue
				}
				seen[k] = true
			}
			p.Events = append(p.Events, e)
		}
	}
	sort.SliceStable(p.Events, func(i, j int) bool {
		a, b := &p.Events[i], &p.Events[j]
		sa, sb := a.start(opts.Loc), b.start(opts.Loc)
		if !sa.Equal(sb) {
			return sa.Before(sb)
		}
		if a.AllDay != b.AllDay {
			return a.AllDay
		}
		return strings.ToLower(a.Title) < strings.ToLower(b.Title)
	})
	var changed []Mark
	for i := range p.Events {
		e := &p.Events[i]
		idx, ok := marked[e.ID]
		if !ok {
			continue
		}
		e.Countdown = true
		src := e.SourceID
		snap := Mark{EventID: e.ID, SourceID: &src, Title: e.Title, AllDay: e.AllDay, StartAt: e.StartAt, StartDate: e.StartDate, Color: colors[src]}
		if !sameMark(marks[idx], snap) {
			marks[idx] = snap
			changed = append(changed, snap)
		}
	}
	today := now.In(opts.Loc).Format(dateLayout)
	for _, m := range marks {
		if m.AllDay && m.StartDate < today || !m.AllDay && (m.StartAt == nil || !m.StartAt.After(now)) {
			continue
		}
		p.Countdowns = append(p.Countdowns, m)
	}
	sort.SliceStable(p.Countdowns, func(i, j int) bool {
		return markStart(p.Countdowns[i], opts.Loc).Before(markStart(p.Countdowns[j], opts.Loc))
	})
	return p, changed
}

func markStart(m Mark, loc *time.Location) time.Time {
	if m.AllDay {
		d, _ := time.ParseInLocation(dateLayout, m.StartDate, loc)
		return d
	}
	if m.StartAt == nil {
		return time.Time{}
	}
	return *m.StartAt
}

func sameMark(a, b Mark) bool {
	ja, _ := json.Marshal(a)
	jb, _ := json.Marshal(b)
	return string(ja) == string(jb)
}

// Digest summarises a payload, ignoring generated_at and sync timestamps that
// don't change what the frame shows.
func Digest(p *Payload) string {
	c := *p
	c.GeneratedAt = time.Time{}
	c.Sources = make([]SourceInfo, len(p.Sources))
	for i, s := range p.Sources {
		s.LastSyncedAt = nil
		c.Sources[i] = s
	}
	b, _ := json.Marshal(c)
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:12])
}
