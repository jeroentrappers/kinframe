package calendar

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/rand/v2"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Service syncs calendar sources and keeps every frame's `calendar` change current.
type Service struct {
	Store   *store.Store
	Sealer  *Sealer
	Fetcher *Fetcher
	Log     *slog.Logger
	// Interval between syncs of a source (15 min) plus up to Jitter (3 min);
	// Tick is the scheduling interval (30 s); MaxBackoff caps retries (2 h).
	Interval, Jitter, Tick, MaxBackoff time.Duration
	// Parallel is how many sources sync at once (4).
	Parallel int
	Now      func() time.Time

	rebuildMu sync.Mutex // one rebuild at a time: feed writes stay ordered
	ctxMu     sync.Mutex
	ctx       context.Context
	kicks     sync.WaitGroup
	running   sync.Map // source id -> struct{}: a sync in progress
}

func (s *Service) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func (s *Service) defaults() {
	if s.Interval <= 0 {
		s.Interval = 15 * time.Minute
	}
	if s.Jitter < 0 {
		s.Jitter = 0
	}
	if s.Tick <= 0 {
		s.Tick = 30 * time.Second
	}
	if s.MaxBackoff <= 0 {
		s.MaxBackoff = 2 * time.Hour
	}
	if s.Parallel <= 0 {
		s.Parallel = 4
	}
}

// Backoff is the retry delay after n consecutive failures: 1 min doubling, at most max.
func Backoff(n int, max time.Duration) time.Duration {
	d := time.Minute
	for i := 1; i < n && d < max; i++ {
		d *= 2
	}
	return min(d, max)
}

// Run syncs due sources and moves windows every Tick until ctx is done.
func (s *Service) Run(ctx context.Context) {
	s.defaults()
	s.ctxMu.Lock()
	s.ctx = ctx
	s.ctxMu.Unlock()
	defer s.kicks.Wait()
	t := time.NewTicker(s.Tick)
	defer t.Stop()
	prune := time.Time{}
	for {
		s.Tick1(ctx)
		if time.Since(prune) > time.Hour {
			if err := s.Store.PruneCountdowns(ctx); err != nil && ctx.Err() == nil {
				s.Log.Error("prune countdowns", "err", err)
			}
			prune = time.Now()
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// Tick1 runs one scheduling round: due syncs, then frames whose window moved.
func (s *Service) Tick1(ctx context.Context) {
	s.defaults()
	if err := s.SyncDue(ctx); err != nil && ctx.Err() == nil {
		s.Log.Error("calendar sync", "err", err)
	}
	if err := s.RefreshWindows(ctx); err != nil && ctx.Err() == nil {
		s.Log.Error("calendar windows", "err", err)
	}
}

func (s *Service) background(fn func(ctx context.Context)) {
	s.ctxMu.Lock()
	ctx := s.ctx
	if ctx == nil {
		ctx = context.Background()
	}
	if ctx.Err() != nil {
		s.ctxMu.Unlock()
		return
	}
	s.kicks.Add(1)
	s.ctxMu.Unlock()
	go func() {
		defer s.kicks.Done()
		fn(ctx)
	}()
}

// Wait blocks until background kicks have finished (tests).
func (s *Service) Wait() { s.kicks.Wait() }

// KickSource syncs one source right away in the background.
func (s *Service) KickSource(id uuid.UUID) {
	s.background(func(ctx context.Context) {
		src, err := s.Store.GetCalendarSource(ctx, id)
		if err != nil {
			return
		}
		s.syncOne(ctx, src)
		s.rebuildLogged(ctx, src.FrameID)
	})
}

// KickFrame re-syncs every enabled source of a frame (sync now) in the background.
func (s *Service) KickFrame(frameID uuid.UUID) {
	s.background(func(ctx context.Context) {
		srcs, err := s.Store.CalendarSourcesFull(ctx, frameID)
		if err != nil {
			return
		}
		s.syncAll(ctx, srcs)
		s.rebuildLogged(ctx, frameID)
	})
}

// RebuildLater re-renders a frame's calendar in the background (settings changed).
func (s *Service) RebuildLater(frameID uuid.UUID) {
	s.background(func(ctx context.Context) { s.rebuildLogged(ctx, frameID) })
}

func (s *Service) rebuildLogged(ctx context.Context, frameID uuid.UUID) {
	if err := s.Rebuild(ctx, frameID); err != nil && ctx.Err() == nil {
		s.Log.Error("calendar rebuild", "frame", frameID, "err", err)
	}
}

// SyncDue syncs every source whose time has come and rebuilds their frames.
func (s *Service) SyncDue(ctx context.Context) error {
	s.defaults()
	srcs, err := s.Store.DueCalendarSources(ctx, s.now(), 200)
	if err != nil || len(srcs) == 0 {
		return err
	}
	s.syncAll(ctx, srcs)
	frames := map[uuid.UUID]bool{}
	var errs []error
	for _, src := range srcs {
		if !frames[src.FrameID] {
			frames[src.FrameID] = true
			errs = append(errs, s.Rebuild(ctx, src.FrameID))
		}
	}
	return errors.Join(errs...)
}

func (s *Service) syncAll(ctx context.Context, srcs []store.CalendarSource) {
	s.defaults()
	sem := make(chan struct{}, s.Parallel)
	var wg sync.WaitGroup
	for i := range srcs {
		src := &srcs[i]
		if src.Kind == "local" || !src.Enabled {
			continue
		}
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			s.syncOne(ctx, src)
		}()
	}
	wg.Wait()
}

// syncOne fetches, parses and expands one source and records the outcome.
func (s *Service) syncOne(ctx context.Context, src *store.CalendarSource) {
	if src.Kind == "local" || !src.Enabled {
		return
	}
	if _, busy := s.running.LoadOrStore(src.ID, struct{}{}); busy {
		return
	}
	defer s.running.Delete(src.ID)
	s.defaults()
	if err := s.Store.CalendarSyncing(ctx, src.ID); err != nil {
		return
	}
	f, err := s.Store.GetFrame(ctx, s.Store.Q(), src.FrameID)
	if err != nil {
		return
	}
	opts := Options(f.Settings)
	w := opts.Window(s.now())
	res, err := s.fetch(ctx, src, w)
	if err == nil {
		var occ []Occurrence
		occ, err = s.expand(res.raw, src.ID, opts, w)
		if err == nil {
			events, _ := json.Marshal(occ)
			var jitter time.Duration
			if s.Jitter > 0 {
				jitter = rand.N(s.Jitter)
			}
			err = s.Store.CalendarSynced(ctx, src.ID, gz(res.raw), res.etag, res.lastMod, s.now().Add(s.Interval+jitter), w.Key(), events, len(occ))
			if err == nil {
				return
			}
		}
	}
	if ctx.Err() != nil {
		return
	}
	status := "failed"
	if IsAuth(err) {
		status = "auth_failed"
	}
	failures := src.Failures + 1
	next := s.now().Add(Backoff(failures, s.MaxBackoff))
	s.Log.Info("calendar sync failed", "source", src.ID, "frame", src.FrameID, "failures", failures, "retry_at", next, "err", err)
	if ferr := s.Store.CalendarSyncFailed(ctx, src.ID, status, short(err.Error()), failures, next); ferr != nil {
		s.Log.Error("calendar sync failed: record", "err", ferr)
	}
}

func short(s string) string {
	if len(s) > 300 {
		return s[:297] + "…"
	}
	return s
}

type fetched struct {
	raw           []byte
	etag, lastMod *string
}

func strp(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

// Credentials returns a source's decrypted URL, username, password and collection URL.
func (s *Service) Credentials(src *store.CalendarSource) (u, user, pass, collection string, err error) {
	if u, err = s.Sealer.Open(src.URLEnc); err != nil {
		return
	}
	if pass, err = s.Sealer.Open(src.PasswordEnc); err != nil {
		return
	}
	if collection, err = s.Sealer.Open(src.CollectionURLEnc); err != nil {
		return
	}
	return u, deref(src.Username), pass, collection, nil
}

func (s *Service) fetch(ctx context.Context, src *store.CalendarSource, w Window) (*fetched, error) {
	u, user, pass, collection, err := s.Credentials(src)
	if err != nil {
		return nil, &SyncError{Auth: true, Msg: err.Error()}
	}
	switch src.Kind {
	case "ics":
		etag, lastMod := deref(src.ETag), deref(src.LastModified)
		if len(src.Raw) == 0 {
			etag, lastMod = "", ""
		}
		r, err := s.Fetcher.FetchICS(ctx, u, etag, lastMod, user, pass)
		if err != nil {
			return nil, err
		}
		if r.NotModified {
			raw, err := gunzip(src.Raw)
			if err != nil {
				return nil, failf("cached calendar unreadable")
			}
			return &fetched{raw: raw, etag: src.ETag, lastMod: src.LastModified}, nil
		}
		return &fetched{raw: r.Raw, etag: strp(r.ETag), lastMod: strp(r.LastModified)}, nil
	case "caldav":
		if collection == "" {
			cals, selected, err := s.Fetcher.CalDAVCalendars(ctx, u, user, pass)
			if err != nil {
				return nil, err
			}
			collection = selected
			if collection == "" {
				collection = cals[0].URL
			}
		}
		from := w.startInstant().Add(-24 * time.Hour)
		to := w.endInstant().Add(24 * time.Hour)
		raw, err := s.Fetcher.FetchCalDAV(ctx, collection, user, pass, from, to)
		if err != nil {
			return nil, err
		}
		return &fetched{raw: raw}, nil
	}
	return nil, failf("unknown calendar kind %q", src.Kind)
}

func (s *Service) expand(raw []byte, sourceID uuid.UUID, opts FrameOptions, w Window) ([]Occurrence, error) {
	p, err := Parse(raw, opts.Loc)
	if err != nil {
		if errors.Is(err, ErrNotCalendar) {
			return nil, failf("this link does not return a calendar (ICS)")
		}
		return nil, failf("the calendar could not be read: %v", err)
	}
	occ := p.Expand(sourceID.String(), w)
	if occ == nil {
		occ = []Occurrence{}
	}
	return occ, nil
}

func gz(b []byte) []byte {
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	_, _ = zw.Write(b)
	_ = zw.Close()
	return buf.Bytes()
}

func gunzip(b []byte) ([]byte, error) {
	zr, err := gzip.NewReader(bytes.NewReader(b))
	if err != nil {
		return nil, err
	}
	return io.ReadAll(zr)
}

// RefreshWindows rebuilds frames whose window moved (a new day, changed settings).
func (s *Service) RefreshWindows(ctx context.Context) error {
	frames, err := s.Store.CalendarFrames(ctx)
	if err != nil {
		return err
	}
	var errs []error
	now := s.now()
	for _, f := range frames {
		key := Options(f.Settings).Window(now).Key()
		if f.WindowKey != nil && *f.WindowKey == key {
			continue
		}
		errs = append(errs, s.Rebuild(ctx, f.FrameID))
	}
	return errors.Join(errs...)
}

// Rebuild merges a frame's sources and emits a `calendar` change when what the
// frame shows changed. It never touches the network.
func (s *Service) Rebuild(ctx context.Context, frameID uuid.UUID) error {
	s.rebuildMu.Lock()
	defer s.rebuildMu.Unlock()
	payload, changed, err := s.Render(ctx, frameID, true)
	if err != nil {
		return err
	}
	prev, err := s.Store.GetFrameCalendar(ctx, s.Store.Q(), frameID)
	if err != nil {
		return err
	}
	if payload == nil {
		if prev == nil || prev.Payload == nil || string(prev.Payload) == "null" {
			return nil
		}
		return s.Store.InTx(ctx, func(tx *store.Tx) error { return tx.SetFrameCalendar(ctx, frameID, "none", "", nil) })
	}
	digest := Digest(payload)
	f, err := s.Store.GetFrame(ctx, s.Store.Q(), frameID)
	if err != nil {
		return err
	}
	key := Options(f.Settings).Window(s.now()).Key()
	if prev != nil && prev.Digest == digest && prev.WindowKey == key && len(changed) == 0 {
		return nil
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	return s.Store.InTx(ctx, func(tx *store.Tx) error {
		for _, m := range changed {
			if err := s.Store.PutCountdown(ctx, tx, frameID, markRow(m)); err != nil {
				return err
			}
		}
		if prev != nil && prev.Digest == digest {
			// Same content (e.g. only countdown snapshots changed): record the window, no change.
			_, err := tx.Exec(ctx, `UPDATE frame_calendar SET window_key = $2 WHERE frame_id = $1`, frameID, key)
			return err
		}
		return tx.SetFrameCalendar(ctx, frameID, digest, key, raw)
	})
}

func markRow(m Mark) store.Countdown {
	c := store.Countdown{EventID: m.EventID, SourceID: m.SourceID, Title: m.Title, AllDay: m.AllDay, StartAt: m.StartAt, Color: m.Color}
	if m.StartDate != "" {
		if d, err := time.Parse(dateLayout, m.StartDate); err == nil {
			c.StartDate = &d
		}
	}
	return c
}

func rowMark(c store.Countdown) Mark {
	m := Mark{EventID: c.EventID, SourceID: c.SourceID, Title: c.Title, AllDay: c.AllDay, StartAt: c.StartAt, Color: c.Color}
	if c.StartDate != nil {
		m.StartDate = c.StartDate.Format(dateLayout)
	}
	return m
}

// Render computes a frame's current payload (nil when it has no calendar).
// With persist, re-expansions for a moved window are saved.
func (s *Service) Render(ctx context.Context, frameID uuid.UUID, persist bool) (*Payload, []Mark, error) {
	f, err := s.Store.GetFrame(ctx, s.Store.Q(), frameID)
	if err != nil {
		return nil, nil, err
	}
	opts := Options(f.Settings)
	now := s.now()
	w := opts.Window(now)
	srcs, err := s.Store.CalendarSourcesFull(ctx, frameID)
	if err != nil {
		return nil, nil, err
	}
	rows, err := s.Store.ListCountdowns(ctx, frameID)
	if err != nil {
		return nil, nil, err
	}
	if len(srcs) == 0 && len(rows) == 0 {
		return nil, nil, nil
	}
	var merge []MergeSource
	for i := range srcs {
		src := &srcs[i]
		info := SourceInfo{ID: src.ID, Kind: src.Kind, Name: src.Name, Color: src.Color, Status: src.Status, LastSyncedAt: src.LastSyncedAt}
		if info.Status == "syncing" && src.LastSyncedAt != nil {
			info.Status = "ok" // a sync in progress changes nothing on screen
		}
		if !src.Enabled {
			continue
		}
		var events []Event
		switch src.Kind {
		case "local":
			events, err = s.localEvents(ctx, src, w)
			if err != nil {
				return nil, nil, err
			}
		default:
			occ, err := s.sourceOccurrences(ctx, src, opts, w, persist)
			if err != nil {
				s.Log.Warn("calendar expand", "source", src.ID, "err", err)
			}
			for _, o := range occ {
				events = append(events, FromOccurrence(o, src.ID))
			}
		}
		merge = append(merge, MergeSource{Info: info, Events: events})
	}
	marks := make([]Mark, len(rows))
	for i, r := range rows {
		marks[i] = rowMark(r)
	}
	p, changed := Merge(merge, marks, opts, now)
	return p, changed, nil
}

// sourceOccurrences returns the stored expansion, re-expanding the cached
// data when the window moved.
func (s *Service) sourceOccurrences(ctx context.Context, src *store.CalendarSource, opts FrameOptions, w Window, persist bool) ([]Occurrence, error) {
	var occ []Occurrence
	if src.WindowKey != nil && *src.WindowKey == w.Key() {
		if len(src.Events) > 0 {
			err := json.Unmarshal(src.Events, &occ)
			return occ, err
		}
		return nil, nil
	}
	if len(src.Raw) == 0 {
		return nil, nil
	}
	raw, err := gunzip(src.Raw)
	if err != nil {
		return nil, err
	}
	occ, err = s.expand(raw, src.ID, opts, w)
	if err != nil {
		return nil, err
	}
	if persist {
		b, _ := json.Marshal(occ)
		if err := s.Store.CalendarExpanded(ctx, src.ID, w.Key(), b, len(occ)); err != nil {
			return occ, err
		}
	}
	return occ, nil
}

// localEvents turns accepted invitations into events.
func (s *Service) localEvents(ctx context.Context, src *store.CalendarSource, w Window) ([]Event, error) {
	invs, err := s.Store.AcceptedInvitations(ctx, src.FrameID)
	if err != nil {
		return nil, err
	}
	var out []Event
	for _, inv := range invs {
		if e, ok := InvitationEvent(&inv, src.ID, w); ok {
			out = append(out, e)
		}
	}
	return out, nil
}

// InvitationEvent renders an accepted invitation as an event if it overlaps w.
func InvitationEvent(inv *store.Invitation, sourceID uuid.UUID, w Window) (Event, bool) {
	id := inv.ID
	e := Event{
		ID: eventID(sourceID.String(), "invitation:"+inv.ID.String(), ""), SourceID: sourceID, Title: inv.Title,
		AllDay: inv.AllDay, Location: deref(inv.Location), SenderName: inv.SenderName, InvitationID: &id,
		HasPhoto: inv.PhotoKey != nil, uid: "invitation:" + inv.ID.String(),
	}
	if len([]rune(deref(inv.Notes))) > maxNotes {
		e.Notes = string([]rune(*inv.Notes)[:maxNotes-1]) + "…"
	} else {
		e.Notes = deref(inv.Notes)
	}
	if e.HasPhoto {
		e.PhotoPlacement = inv.PhotoPlacement
	}
	if inv.AllDay {
		if inv.StartDate == nil {
			return e, false
		}
		start := *inv.StartDate
		end := start
		if inv.EndDate != nil && inv.EndDate.After(start) {
			end = *inv.EndDate
		}
		if end.Before(w.From) || start.After(w.To) {
			return e, false
		}
		e.StartDate, e.EndDate = start.Format(dateLayout), end.Format(dateLayout)
		return e, true
	}
	if inv.StartAt == nil || inv.EndAt == nil {
		return e, false
	}
	st, en := inv.StartAt.UTC(), inv.EndAt.UTC()
	if !st.Before(w.endInstant()) || !en.After(w.startInstant()) {
		return e, false
	}
	e.StartAt, e.EndAt = &st, &en
	return e, true
}

// ---------------------------------------------------------------- testing a connection

// TestResult is the outcome of trying a calendar without saving it.
type TestResult struct {
	OK           bool
	Auth         bool
	Error        string
	CalendarName string
	EventCount   *int
	Calendars    []CalDAVCalendar
}

// Test fetches and parses a calendar, counting events in the next 30 days.
func (s *Service) Test(ctx context.Context, kind, rawURL, user, pass, collection string, loc *time.Location) TestResult {
	w := NewWindow(s.now(), loc, 0, 30)
	fail := func(err error) TestResult {
		return TestResult{Auth: IsAuth(err), Error: err.Error()}
	}
	var raw []byte
	var cals []CalDAVCalendar
	switch kind {
	case "ics":
		r, err := s.Fetcher.FetchICS(ctx, rawURL, "", "", user, pass)
		if err != nil {
			return fail(err)
		}
		raw = r.Raw
	case "caldav":
		if collection == "" {
			var selected string
			var err error
			cals, selected, err = s.Fetcher.CalDAVCalendars(ctx, rawURL, user, pass)
			if err != nil {
				return fail(err)
			}
			collection = selected
			if collection == "" {
				collection = cals[0].URL
			}
		}
		var err error
		raw, err = s.Fetcher.FetchCalDAV(ctx, collection, user, pass, w.startInstant(), w.endInstant())
		if err != nil {
			return fail(err)
		}
	default:
		return TestResult{Error: fmt.Sprintf("unknown kind %q", kind)}
	}
	p, err := Parse(raw, loc)
	if err != nil {
		if errors.Is(err, ErrNotCalendar) {
			return TestResult{Error: "this link does not return a calendar (ICS)", Calendars: cals}
		}
		return TestResult{Error: "the calendar could not be read", Calendars: cals}
	}
	n := len(p.Expand("test", w))
	name := p.Name
	for _, c := range cals {
		if c.URL == collection && name == "" {
			name = c.Name
		}
	}
	return TestResult{OK: true, CalendarName: name, EventCount: &n, Calendars: cals}
}

// DefaultName names a source after its URL's host when the calendar has no name.
func DefaultName(rawURL string) string {
	u, err := url.Parse(rawURL)
	if err != nil || u.Host == "" {
		return "Calendar"
	}
	h := strings.TrimPrefix(u.Hostname(), "www.")
	switch {
	case strings.Contains(h, "google."):
		return "Google Calendar"
	case strings.Contains(h, "icloud."):
		return "iCloud"
	case strings.Contains(h, "outlook.") || strings.Contains(h, "office365.") || strings.Contains(h, "live."):
		return "Outlook"
	}
	return h
}
