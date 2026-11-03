package weather

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"math/rand/v2"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Location is a frame's settings.location.
type Location struct {
	Name *string `json:"name"`
	Lat  float64 `json:"lat"`
	Lon  float64 `json:"lon"`
}

// Wanted returns the frame's location when its settings ask for weather
// (location with numeric lat/lon and the weather widget or the calendar sidebar on).
func Wanted(settings json.RawMessage) (Location, bool) {
	var s struct {
		Location *struct {
			Name *string  `json:"name"`
			Lat  *float64 `json:"lat"`
			Lon  *float64 `json:"lon"`
		} `json:"location"`
		Widgets struct {
			Weather         bool `json:"weather"`
			CalendarSidebar bool `json:"calendar_sidebar"`
		} `json:"widgets"`
	}
	if json.Unmarshal(settings, &s) != nil || s.Location == nil || s.Location.Lat == nil || s.Location.Lon == nil || (!s.Widgets.Weather && !s.Widgets.CalendarSidebar) {
		return Location{}, false
	}
	return Location{Name: s.Location.Name, Lat: *s.Location.Lat, Lon: *s.Location.Lon}, true
}

// Key is the cache key: lat/lon rounded to 2 decimals (about 1 km), so frames
// in the same town share one upstream request.
func Key(lat, lon float64) string {
	r := func(v float64) float64 {
		v = math.Round(v*100) / 100
		if v == 0 {
			return 0 // no "-0.00"
		}
		return v
	}
	return fmt.Sprintf("%.2f,%.2f", r(lat), r(lon))
}

// Render adds the frame's location to a cached payload.
func Render(payload json.RawMessage, loc Location) json.RawMessage {
	var m map[string]any
	if json.Unmarshal(payload, &m) != nil || m == nil {
		return nil
	}
	m["location"] = loc
	b, _ := json.Marshal(m)
	return b
}

// Digest summarises what a frame would show, rounded so that noise (0.1° steps,
// 1 % humidity) does not cause a new change. Hourly window start and daily dates
// are included, so the strip still moves forward every hour.
func Digest(p *Payload, locationName string) string {
	half := func(v float64) float64 { return math.Round(v*2) / 2 }
	opt := func(p *float64, f func(float64) float64) any {
		if p == nil {
			return nil
		}
		return f(*p)
	}
	opti := func(p *int, step int) any {
		if p == nil {
			return nil
		}
		return (*p + step/2) / step * step
	}
	var b strings.Builder
	enc := json.NewEncoder(&b)
	_ = enc.Encode([]any{locationName, half(p.Current.TempC), opt(p.Current.ApparentC, half), p.Current.Code, p.Current.IsDay,
		opti(p.Current.HumidityPct, 5), opt(p.Current.WindKmh, math.Round)})
	for _, h := range p.Hourly {
		_ = enc.Encode([]any{h.Time.Unix(), half(h.TempC), h.Code, h.IsDay, opti(h.PrecipProb, 10)})
	}
	for _, d := range p.Daily {
		_ = enc.Encode([]any{d.Date, half(d.MinC), half(d.MaxC), d.Code, opti(d.PrecipProb, 10)})
	}
	sum := sha256.Sum256([]byte(b.String()))
	return hex.EncodeToString(sum[:12])
}

// Backoff is the retry delay after n consecutive failures: 1 min doubling, at
// most max.
func Backoff(n int, max time.Duration) time.Duration {
	d := time.Minute
	for i := 1; i < n && d < max; i++ {
		d *= 2
	}
	return min(d, max)
}

// Service refreshes weather for every frame that wants it.
type Service struct {
	Store  *store.Store
	Client *Client
	Log    *slog.Logger
	// Refresh is how long a fetch stays fresh (30 min); Jitter is added to
	// spread requests (up to 5 min); Tick is the scheduling interval (1 min).
	Refresh, Jitter, Tick time.Duration
	// Reemit re-sends unchanged weather after this long so fetched_at stays
	// recent on the frame, which hides data older than 6 h (default 2 h).
	Reemit time.Duration
	Now    func() time.Time

	mu    sync.Mutex // one sync at a time: no duplicate fetches
	ctxMu sync.Mutex
	ctx   context.Context
	kicks sync.WaitGroup

	geoMu    sync.Mutex
	geoCache map[string]geoEntry
}

type geoEntry struct {
	places []Place
	at     time.Time
}

const (
	geoTTL     = 24 * time.Hour
	geoMaxSize = 2000
)

func (s *Service) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func (s *Service) defaults() {
	if s.Refresh <= 0 {
		s.Refresh = 30 * time.Minute
	}
	if s.Jitter < 0 {
		s.Jitter = 0
	}
	if s.Tick <= 0 {
		s.Tick = time.Minute
	}
	if s.Reemit <= 0 {
		s.Reemit = 2 * time.Hour
	}
}

// Run syncs every Tick until ctx is done.
func (s *Service) Run(ctx context.Context) {
	s.mu.Lock()
	s.defaults()
	s.mu.Unlock()
	s.ctxMu.Lock()
	s.ctx = ctx
	s.ctxMu.Unlock()
	defer s.kicks.Wait()
	t := time.NewTicker(s.Tick)
	defer t.Stop()
	prune := time.Time{}
	for {
		if err := s.Sync(ctx, nil); err != nil && ctx.Err() == nil {
			s.Log.Error("weather sync", "err", err)
		}
		if time.Since(prune) > time.Hour {
			if err := s.Store.PruneWeatherCache(ctx); err != nil && ctx.Err() == nil {
				s.Log.Error("prune weather cache", "err", err)
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

// Kick syncs one frame right away in the background (settings changed).
func (s *Service) Kick(frameID uuid.UUID) {
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
		if err := s.Sync(ctx, &frameID); err != nil && ctx.Err() == nil {
			s.Log.Error("weather sync", "frame", frameID, "err", err)
		}
	}()
}

// Sync fetches due locations and updates frames' weather; only limits it to one frame.
func (s *Service) Sync(ctx context.Context, only *uuid.UUID) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.defaults()
	frames, err := s.Store.WeatherFrames(ctx, only)
	if err != nil {
		return err
	}
	type want struct {
		f   store.WeatherFrame
		loc Location
	}
	byKey := map[string][]want{}
	var keys []string
	var errs []error
	for _, f := range frames {
		loc, ok := Wanted(f.Settings)
		if !ok {
			if f.CacheKey != nil {
				errs = append(errs, s.Store.InTx(ctx, func(tx *store.Tx) error { return tx.ClearFrameWeather(ctx, f.FrameID) }))
			}
			continue
		}
		k := Key(loc.Lat, loc.Lon)
		if _, seen := byKey[k]; !seen {
			keys = append(keys, k)
		}
		byKey[k] = append(byKey[k], want{f, loc})
	}
	for _, k := range keys {
		ws := byKey[k]
		entry, err := s.ensure(ctx, k, ws[0].loc)
		if err != nil {
			errs = append(errs, err)
		}
		if entry == nil || entry.Payload == nil || entry.FetchedAt == nil {
			continue
		}
		var p Payload
		if err := json.Unmarshal(entry.Payload, &p); err != nil {
			errs = append(errs, err)
			continue
		}
		for _, w := range ws {
			name := ""
			if w.loc.Name != nil {
				name = *w.loc.Name
			}
			d := Digest(&p, name)
			f := w.f
			changed := f.CacheKey == nil || *f.CacheKey != k || f.Digest == nil || *f.Digest != d
			refresh := f.EmittedAt != nil && s.now().Sub(*f.EmittedAt) >= s.Reemit && entry.FetchedAt.After(*f.EmittedAt)
			if !changed && !refresh {
				continue
			}
			errs = append(errs, s.Store.InTx(ctx, func(tx *store.Tx) error { return tx.SetFrameWeather(ctx, f.FrameID, k, d) }))
		}
	}
	return errors.Join(errs...)
}

// ensure returns the cache entry for key, fetching it first when it is due.
// On a failed fetch the previous entry (if any) is returned with the error.
func (s *Service) ensure(ctx context.Context, key string, loc Location) (*store.WeatherCache, error) {
	entry, err := s.Store.GetWeatherCache(ctx, key)
	if err != nil && err != store.ErrNotFound {
		return nil, err
	}
	now := s.now()
	if entry != nil && now.Before(entry.NextFetchAt) {
		return entry, nil
	}
	p, ferr := s.Client.Forecast(ctx, loc.Lat, loc.Lon, now)
	if ferr != nil {
		if ctx.Err() != nil {
			return entry, ctx.Err()
		}
		failures := 1
		if entry != nil {
			failures = entry.Failures + 1
		}
		next := now.Add(Backoff(failures, s.Refresh))
		s.Log.Warn("weather fetch failed", "key", key, "failures", failures, "retry_at", next, "err", ferr)
		if err := s.Store.WeatherFailed(ctx, key, loc.Lat, loc.Lon, failures, next, ferr.Error()); err != nil {
			return entry, err
		}
		return entry, nil
	}
	raw, err := json.Marshal(p)
	if err != nil {
		return entry, err
	}
	var jitter time.Duration
	if s.Jitter > 0 {
		jitter = rand.N(s.Jitter)
	}
	if err := s.Store.PutWeather(ctx, key, loc.Lat, loc.Lon, raw, p.FetchedAt, now.Add(s.Refresh+jitter)); err != nil {
		return entry, err
	}
	return s.Store.GetWeatherCache(ctx, key)
}

// ---------------------------------------------------------------- geocoding

// Geocode searches places, cached for a day per (query, language, count).
func (s *Service) Geocode(ctx context.Context, q, lang string, count int) ([]Place, error) {
	q = strings.Join(strings.Fields(q), " ")
	lang = strings.ToLower(lang)
	key := strings.ToLower(q) + "\x00" + lang + "\x00" + fmt.Sprint(count)
	now := s.now()
	s.geoMu.Lock()
	if e, ok := s.geoCache[key]; ok && now.Sub(e.at) < geoTTL {
		s.geoMu.Unlock()
		return e.places, nil
	}
	s.geoMu.Unlock()
	places, err := s.Client.Search(ctx, q, lang, count)
	if err != nil {
		return nil, err
	}
	s.geoMu.Lock()
	defer s.geoMu.Unlock()
	if s.geoCache == nil {
		s.geoCache = map[string]geoEntry{}
	}
	if len(s.geoCache) >= geoMaxSize {
		// Drop expired entries; if still full, drop an arbitrary half.
		for k, e := range s.geoCache {
			if now.Sub(e.at) >= geoTTL {
				delete(s.geoCache, k)
			}
		}
		for k := range s.geoCache {
			if len(s.geoCache) < geoMaxSize/2 {
				break
			}
			delete(s.geoCache, k)
		}
	}
	s.geoCache[key] = geoEntry{places: places, at: now}
	return places, nil
}
