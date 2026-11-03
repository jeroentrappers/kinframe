package calendar

import (
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	ical "github.com/emersion/go-ical"
	"github.com/teambition/rrule-go"
)

// Times inside this package are mostly "wall times": the local date and clock
// reading of an event, stored in a time.Time in UTC so that arithmetic and
// recurrence expansion never cross a DST change. A zone turns wall times into
// instants and back.
type zone interface {
	instant(wall time.Time) time.Time
	wall(t time.Time) time.Time
}

// toWall re-encodes t's clock reading (in its own location) as a UTC wall time.
func toWall(t time.Time) time.Time {
	return time.Date(t.Year(), t.Month(), t.Day(), t.Hour(), t.Minute(), t.Second(), 0, time.UTC)
}

// locZone is an IANA (or UTC/fixed) location.
type locZone struct{ loc *time.Location }

func (z locZone) instant(w time.Time) time.Time {
	return time.Date(w.Year(), w.Month(), w.Day(), w.Hour(), w.Minute(), w.Second(), 0, z.loc)
}
func (z locZone) wall(t time.Time) time.Time { return toWall(t.In(z.loc)) }

var utcZone = locZone{time.UTC}

// observance is one STANDARD or DAYLIGHT block of a VTIMEZONE.
type observance struct {
	start      time.Time // wall time, in the offset before the change
	from, to   int       // offsets in seconds
	rule       *rrule.RRule
	rdates     []time.Time
	isStandard bool
}

// vtzZone evaluates an embedded VTIMEZONE (used when the TZID is not a known
// IANA or Windows name).
type vtzZone struct {
	obs []observance

	mu    sync.Mutex
	cache map[int][]onset // per year
}

type onset struct {
	at     time.Time // instant (UTC)
	offset int
}

func (z *vtzZone) onsets(year int) []onset {
	z.mu.Lock()
	defer z.mu.Unlock()
	if o, ok := z.cache[year]; ok {
		return o
	}
	from := time.Date(year-1, 1, 1, 0, 0, 0, 0, time.UTC)
	to := time.Date(year+1, 12, 31, 23, 59, 59, 0, time.UTC)
	var out []onset
	for _, ob := range z.obs {
		add := func(w time.Time) {
			if w.Before(from) || w.After(to) {
				return
			}
			out = append(out, onset{at: w.Add(-time.Duration(ob.from) * time.Second), offset: ob.to})
		}
		if ob.rule != nil {
			for _, w := range ob.rule.Between(from, to, true) {
				add(w)
			}
		} else {
			add(ob.start)
		}
		for _, w := range ob.rdates {
			add(w)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].at.Before(out[j].at) })
	if z.cache == nil {
		z.cache = map[int][]onset{}
	}
	z.cache[year] = out
	return out
}

// offsetAt returns the UTC offset in effect at instant t.
func (z *vtzZone) offsetAt(t time.Time) int {
	t = t.UTC()
	best, found := 0, false
	for _, o := range z.onsets(t.Year()) {
		if !o.at.After(t) {
			best, found = o.offset, true
		}
	}
	if found {
		return best
	}
	// Before every onset: the earliest observance's "from" offset, preferring standard time.
	for _, ob := range z.obs {
		if ob.isStandard {
			return ob.to
		}
	}
	if len(z.obs) > 0 {
		return z.obs[0].from
	}
	return 0
}

func (z *vtzZone) instant(w time.Time) time.Time {
	// Try each known offset; the one that is consistent wins. In a gap or
	// overlap, the first consistent (or, failing that, standard) choice is used.
	seen := map[int]bool{}
	var offsets []int
	for _, ob := range z.obs {
		for _, o := range []int{ob.to, ob.from} {
			if !seen[o] {
				seen[o] = true
				offsets = append(offsets, o)
			}
		}
	}
	for _, o := range offsets {
		t := w.Add(-time.Duration(o) * time.Second)
		if z.offsetAt(t) == o {
			return t
		}
	}
	return w.Add(-time.Duration(z.offsetAt(w)) * time.Second)
}

func (z *vtzZone) wall(t time.Time) time.Time {
	return t.UTC().Add(time.Duration(z.offsetAt(t)) * time.Second)
}

// parseOffset parses "+0100", "-0530" or "+013000" into seconds.
func parseOffset(s string) (int, bool) {
	s = strings.TrimSpace(s)
	if len(s) != 5 && len(s) != 7 {
		return 0, false
	}
	sign := 1
	switch s[0] {
	case '+':
	case '-':
		sign = -1
	default:
		return 0, false
	}
	h, err1 := strconv.Atoi(s[1:3])
	m, err2 := strconv.Atoi(s[3:5])
	sec := 0
	var err3 error
	if len(s) == 7 {
		sec, err3 = strconv.Atoi(s[5:7])
	}
	if err1 != nil || err2 != nil || err3 != nil {
		return 0, false
	}
	return sign * (h*3600 + m*60 + sec), true
}

func parseVTimezone(c *ical.Component) (string, *vtzZone) {
	tzid := ""
	if p := c.Props.Get(ical.PropTimezoneID); p != nil {
		tzid = p.Value
	}
	z := &vtzZone{}
	for _, child := range c.Children {
		if child.Name != "STANDARD" && child.Name != "DAYLIGHT" {
			continue
		}
		var ob observance
		ob.isStandard = child.Name == "STANDARD"
		var ok1, ok2 bool
		if p := child.Props.Get("TZOFFSETFROM"); p != nil {
			ob.from, ok1 = parseOffset(p.Value)
		}
		if p := child.Props.Get("TZOFFSETTO"); p != nil {
			ob.to, ok2 = parseOffset(p.Value)
		}
		if !ok1 || !ok2 {
			continue
		}
		p := child.Props.Get(ical.PropDateTimeStart)
		if p == nil {
			continue
		}
		start, _, _, err := parseWallValue(p.Value)
		if err != nil {
			continue
		}
		ob.start = start
		if rp := child.Props.Get(ical.PropRecurrenceRule); rp != nil {
			if opt, err := rrule.StrToROption(rp.Value); err == nil {
				opt.Dtstart = start
				// Exchange writes DTSTART:16010101T…; rrule-go yields nothing for
				// such early years. Yearly transition rules don't depend on the
				// start year, so start them in 1970 instead.
				if opt.Freq == rrule.YEARLY && start.Year() < 1970 {
					opt.Dtstart = time.Date(1970, start.Month(), start.Day(), start.Hour(), start.Minute(), start.Second(), 0, time.UTC)
				}
				if !opt.Until.IsZero() {
					// UNTIL in UTC: good enough to compare against wall times here.
					opt.Until = opt.Until.UTC()
				}
				if r, err := rrule.NewRRule(*opt); err == nil {
					ob.rule = r
				}
			}
		}
		for _, rp := range child.Props.Values(ical.PropRecurrenceDates) {
			for _, v := range strings.Split(rp.Value, ",") {
				if w, _, _, err := parseWallValue(v); err == nil {
					ob.rdates = append(ob.rdates, w)
				}
			}
		}
		z.obs = append(z.obs, ob)
	}
	if len(z.obs) == 0 {
		return tzid, nil
	}
	return tzid, z
}

// zoneSet resolves TZIDs for one calendar object.
type zoneSet struct {
	floating zone
	vtz      map[string]*vtzZone
}

var locCache sync.Map // name -> *time.Location (or nil)

func loadLocation(name string) *time.Location {
	if v, ok := locCache.Load(name); ok {
		loc, _ := v.(*time.Location)
		return loc
	}
	loc, err := time.LoadLocation(name)
	if err != nil || name == "" || name == "Local" {
		loc = nil
	}
	locCache.Store(name, loc)
	return loc
}

// ianaFor maps a TZID to an IANA location: IANA names directly, Windows names
// through a table, and prefixed names such as "/mozilla.org/20050126_1/Europe/Brussels"
// or "/freeassociation.sourceforge.net/Europe/Brussels" by their longest IANA suffix.
func ianaFor(tzid string) *time.Location {
	tzid = strings.Trim(strings.TrimSpace(tzid), `"`)
	if tzid == "" {
		return nil
	}
	if strings.EqualFold(tzid, "UTC") || strings.EqualFold(tzid, "GMT") || tzid == "Z" || strings.EqualFold(tzid, "Etc/UTC") {
		return time.UTC
	}
	if loc := loadLocation(tzid); loc != nil {
		return loc
	}
	if name, ok := windowsZones[strings.ToLower(tzid)]; ok {
		return loadLocation(name)
	}
	parts := strings.Split(strings.Trim(tzid, "/"), "/")
	for i := 1; i < len(parts); i++ {
		cand := strings.Join(parts[i:], "/")
		if !strings.Contains(cand, "/") {
			break
		}
		if loc := loadLocation(cand); loc != nil {
			return loc
		}
	}
	return nil
}

func (zs *zoneSet) resolve(tzid string) zone {
	if tzid == "" {
		return zs.floating
	}
	if loc := ianaFor(tzid); loc != nil {
		return locZone{loc}
	}
	if z, ok := zs.vtz[tzid]; ok && z != nil {
		return z
	}
	return zs.floating
}

// windowsZones maps common Windows time zone names (as sent by Outlook and
// Exchange) to IANA names. Keys are lower case.
var windowsZones = map[string]string{
	"dateline standard time":          "Etc/GMT+12",
	"hawaiian standard time":          "Pacific/Honolulu",
	"alaskan standard time":           "America/Anchorage",
	"pacific standard time":           "America/Los_Angeles",
	"pacific standard time (mexico)":  "America/Tijuana",
	"us mountain standard time":       "America/Phoenix",
	"mountain standard time":          "America/Denver",
	"central america standard time":   "America/Guatemala",
	"central standard time":           "America/Chicago",
	"central standard time (mexico)":  "America/Mexico_City",
	"canada central standard time":    "America/Regina",
	"sa pacific standard time":        "America/Bogota",
	"eastern standard time":           "America/New_York",
	"us eastern standard time":        "America/Indianapolis",
	"venezuela standard time":         "America/Caracas",
	"atlantic standard time":          "America/Halifax",
	"sa western standard time":        "America/La_Paz",
	"pacific sa standard time":        "America/Santiago",
	"newfoundland standard time":      "America/St_Johns",
	"e. south america standard time":  "America/Sao_Paulo",
	"argentina standard time":         "America/Buenos_Aires",
	"greenland standard time":         "America/Godthab",
	"azores standard time":            "Atlantic/Azores",
	"cape verde standard time":        "Atlantic/Cape_Verde",
	"utc":                             "UTC",
	"gmt standard time":               "Europe/London",
	"greenwich standard time":         "Atlantic/Reykjavik",
	"morocco standard time":           "Africa/Casablanca",
	"w. europe standard time":         "Europe/Berlin",
	"central europe standard time":    "Europe/Budapest",
	"romance standard time":           "Europe/Paris",
	"central european standard time":  "Europe/Warsaw",
	"w. central africa standard time": "Africa/Lagos",
	"gtb standard time":               "Europe/Bucharest",
	"e. europe standard time":         "Europe/Chisinau",
	"fle standard time":               "Europe/Kiev",
	"israel standard time":            "Asia/Jerusalem",
	"egypt standard time":             "Africa/Cairo",
	"south africa standard time":      "Africa/Johannesburg",
	"turkey standard time":            "Europe/Istanbul",
	"arabic standard time":            "Asia/Baghdad",
	"arab standard time":              "Asia/Riyadh",
	"russian standard time":           "Europe/Moscow",
	"e. africa standard time":         "Africa/Nairobi",
	"iran standard time":              "Asia/Tehran",
	"arabian standard time":           "Asia/Dubai",
	"afghanistan standard time":       "Asia/Kabul",
	"pakistan standard time":          "Asia/Karachi",
	"india standard time":             "Asia/Calcutta",
	"sri lanka standard time":         "Asia/Colombo",
	"nepal standard time":             "Asia/Katmandu",
	"bangladesh standard time":        "Asia/Dhaka",
	"myanmar standard time":           "Asia/Rangoon",
	"se asia standard time":           "Asia/Bangkok",
	"china standard time":             "Asia/Shanghai",
	"singapore standard time":         "Asia/Singapore",
	"taipei standard time":            "Asia/Taipei",
	"w. australia standard time":      "Australia/Perth",
	"tokyo standard time":             "Asia/Tokyo",
	"korea standard time":             "Asia/Seoul",
	"cen. australia standard time":    "Australia/Adelaide",
	"aus central standard time":       "Australia/Darwin",
	"e. australia standard time":      "Australia/Brisbane",
	"aus eastern standard time":       "Australia/Sydney",
	"tasmania standard time":          "Australia/Hobart",
	"new zealand standard time":       "Pacific/Auckland",
	"tonga standard time":             "Pacific/Tongatapu",
}
