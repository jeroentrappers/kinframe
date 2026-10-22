package weather

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/testdb"
	"github.com/jeroentrappers/kinframe/server/internal/weather/weathertest"
)

func TestMain(m *testing.M) {
	code := m.Run()
	testdb.Stop()
	os.Exit(code)
}

func client(f *weathertest.Fake) *Client {
	return &Client{ForecastURL: f.URL, GeocodingURL: f.URL, UserAgent: "kinframe-test"}
}

func TestForecastNormalization(t *testing.T) {
	f := weathertest.New()
	t.Cleanup(f.Close)
	now := time.Date(2026, 10, 22, 12, 40, 0, 0, time.UTC) // 14:40 local (+02:00)
	f.Now = func() time.Time { return now }
	p, err := client(f).Forecast(context.Background(), 51.054, 3.7174, now)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"latitude=51.0540", "timezone=auto", "forecast_days=7", "current=temperature_2m", "precipitation_probability_max"} {
		if !strings.Contains(f.LastPath, want) {
			t.Errorf("request %q lacks %q", f.LastPath, want)
		}
	}
	if p.FetchedAt != now || p.UTCOffsetSeconds != 7200 || p.Timezone != "Europe/Brussels" || p.Units != metric {
		t.Fatalf("header: %+v", p)
	}
	c := p.Current
	if c.TempC != 12.3 || *c.ApparentC != 10.3 || c.Code != 3 || !c.IsDay || *c.WindKmh != 14.8 || *c.WindDirDeg != 238 || *c.HumidityPct != 81 || *c.PrecipMM != 0 {
		t.Fatalf("current: %+v", c)
	}
	if got := c.Time.Format(time.RFC3339); got != "2026-10-22T14:30:00+02:00" {
		t.Fatalf("current time %s", got)
	}
	if len(p.Hourly) != 24 {
		t.Fatalf("hourly: %d", len(p.Hourly))
	}
	if got := p.Hourly[0].Time.Format(time.RFC3339); got != "2026-10-22T14:00:00+02:00" {
		t.Fatalf("hourly starts at %s, want the current hour", got)
	}
	if got := p.Hourly[23].Time.Format(time.RFC3339); got != "2026-10-23T13:00:00+02:00" {
		t.Fatalf("hourly ends at %s", got)
	}
	if h := p.Hourly[0]; h.TempC != 17 || !h.IsDay || h.PrecipProb == nil {
		t.Fatalf("hour 0: %+v", h)
	}
	if h := p.Hourly[6]; h.IsDay { // 20:00
		t.Fatalf("20:00 should be night: %+v", h)
	}
	if len(p.Daily) != 7 || p.Daily[0].Date != "2026-10-22" || p.Daily[0].MinC != 5 || p.Daily[0].MaxC != 14 || p.Daily[3].Code != 95 {
		t.Fatalf("daily: %+v", p.Daily)
	}
	if got := p.Daily[0].Sunrise.Format(time.RFC3339); got != "2026-10-22T07:58:00+02:00" {
		t.Fatalf("sunrise %s", got)
	}
	if p.Daily[6].PrecipProb != nil || *p.Daily[2].PrecipProb != 30 || *p.Daily[1].PrecipMM != 1.3 {
		t.Fatalf("daily precip: %+v %+v", p.Daily[6], p.Daily[2])
	}
	// Serialized field names follow openapi.yaml Weather.
	b, _ := json.Marshal(p)
	for _, k := range []string{`"temp_c"`, `"apparent_c"`, `"wind_kmh"`, `"wind_dir_deg"`, `"humidity_pct"`, `"precip_prob"`, `"min_c"`, `"max_c"`, `"sunrise"`, `"utc_offset_seconds"`, `"fetched_at"`} {
		if !strings.Contains(string(b), k) {
			t.Errorf("payload lacks %s", k)
		}
	}
}

func TestForecastErrors(t *testing.T) {
	f := weathertest.New()
	t.Cleanup(f.Close)
	f.SetFail(true)
	if _, err := client(f).Forecast(context.Background(), 1, 2, time.Now()); err == nil || !strings.Contains(err.Error(), "boom") {
		t.Fatalf("err %v", err)
	}
	if _, err := normalize(&forecastResponse{}, time.Now()); err == nil {
		t.Fatal("missing current accepted")
	}
}

func TestKey(t *testing.T) {
	for _, c := range []struct {
		lat, lon float64
		want     string
	}{{51.054, 3.7174, "51.05,3.72"}, {51.0549, 3.7151, "51.05,3.72"}, {-0.001, 0.004, "0.00,0.00"}, {-33.8688, 151.2093, "-33.87,151.21"}} {
		if got := Key(c.lat, c.lon); got != c.want {
			t.Errorf("Key(%v,%v) = %s, want %s", c.lat, c.lon, got, c.want)
		}
	}
}

func TestWantedAndRender(t *testing.T) {
	on := json.RawMessage(`{"widgets":{"weather":true},"location":{"name":"Gent","lat":51.05,"lon":3.72}}`)
	loc, ok := Wanted(on)
	if !ok || *loc.Name != "Gent" || loc.Lat != 51.05 {
		t.Fatalf("wanted: %v %+v", ok, loc)
	}
	for _, s := range []string{
		`{"widgets":{"weather":false},"location":{"name":"Gent","lat":51.05,"lon":3.72}}`,
		`{"widgets":{"weather":true},"location":null}`,
		`{"widgets":{"weather":true},"location":{"name":"Gent"}}`,
	} {
		if _, ok := Wanted(json.RawMessage(s)); ok {
			t.Errorf("Wanted(%s) = true", s)
		}
	}
	out := Render(json.RawMessage(`{"current":{"temp_c":1}}`), loc)
	var m map[string]any
	_ = json.Unmarshal(out, &m)
	if m["location"].(map[string]any)["name"] != "Gent" || m["current"] == nil {
		t.Fatalf("render: %s", out)
	}
}

func TestDigest(t *testing.T) {
	now := time.Date(2026, 10, 22, 12, 40, 0, 0, time.UTC)
	r := func(temp float64, code int, at time.Time) *Payload {
		raw, _ := json.Marshal(weathertest.Forecast(at, temp, code))
		var fr forecastResponse
		_ = json.Unmarshal(raw, &fr)
		p, err := normalize(&fr, at)
		if err != nil {
			t.Fatal(err)
		}
		return p
	}
	base := Digest(r(12.3, 3, now), "Gent")
	if Digest(r(12.4, 3, now.Add(time.Minute)), "Gent") != base {
		t.Error("0.1° noise and a new fetched_at should not be a meaningful change")
	}
	if Digest(r(13.4, 3, now), "Gent") == base {
		t.Error("1° change not detected")
	}
	if Digest(r(12.3, 61, now), "Gent") == base {
		t.Error("code change not detected")
	}
	if Digest(r(12.3, 3, now), "Ghent") == base {
		t.Error("location name change not detected")
	}
	if Digest(r(12.3, 3, now.Add(time.Hour)), "Gent") == base {
		t.Error("hourly window moving on not detected")
	}
}

func TestBackoff(t *testing.T) {
	max := 30 * time.Minute
	want := []time.Duration{time.Minute, 2 * time.Minute, 4 * time.Minute, 8 * time.Minute, 16 * time.Minute, max, max}
	for i, w := range want {
		if got := Backoff(i+1, max); got != w {
			t.Errorf("Backoff(%d) = %v, want %v", i+1, got, w)
		}
	}
}

func TestGeocode(t *testing.T) {
	f := weathertest.New()
	t.Cleanup(f.Close)
	s := &Service{Client: client(f), Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	ctx := context.Background()
	places, err := s.Geocode(ctx, "Gent", "nl", 8)
	if err != nil {
		t.Fatal(err)
	}
	if len(places) != 2 || places[0].Name != "Gent" || *places[0].Country != "België" || *places[0].CountryCode != "BE" ||
		places[0].Lat != 51.05 || *places[0].Admin1 != "Flanders" || *places[0].Timezone != "Europe/Brussels" ||
		places[1].Admin1 != nil || *places[1].CountryCode != "BE" {
		t.Fatalf("places: %+v", places)
	}
	if !strings.Contains(f.LastPath, "language=nl") || !strings.Contains(f.LastPath, "count=8") {
		t.Fatalf("request %s", f.LastPath)
	}
	// Cached, case- and space-insensitively.
	if _, err := s.Geocode(ctx, "  gent ", "NL", 8); err != nil || f.Searches.Load() != 1 {
		t.Fatalf("not cached: %v %d", err, f.Searches.Load())
	}
	if p, err := s.Geocode(ctx, "nowhere", "en", 8); err != nil || len(p) != 0 || p == nil {
		t.Fatalf("no results: %v %v", p, err)
	}
	f.SetFail(true)
	if _, err := s.Geocode(ctx, "Brugge", "en", 8); err == nil {
		t.Fatal("upstream failure not reported")
	}
}

// TestSync drives the refresh loop against a real database.
func TestSync(t *testing.T) {
	pool, _ := testdb.New(t)
	st := store.New(pool)
	ctx := context.Background()
	var fed []uuid.UUID
	st.OnFeed = func(f uuid.UUID, _ int64) { fed = append(fed, f) }
	settings := func(lat float64, on bool) json.RawMessage {
		b, _ := json.Marshal(map[string]any{"widgets": map[string]any{"weather": on}, "location": map[string]any{"name": "Gent", "lat": lat, "lon": 3.72}})
		return b
	}
	var a, b, c uuid.UUID
	if err := st.InTx(ctx, func(tx *store.Tx) error {
		for i, s := range []json.RawMessage{settings(51.051, true), settings(51.049, true), settings(51.05, false)} {
			fr, err := tx.CreateFrame(ctx, store.Frame{ID: store.NewID(), Name: "f", Settings: s, DefaultPermissions: store.DefaultMemberPermissions})
			if err != nil {
				return err
			}
			*[]*uuid.UUID{&a, &b, &c}[i] = fr.ID
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	f := weathertest.New()
	t.Cleanup(f.Close)
	now := time.Now().Truncate(time.Second)
	clock := func() time.Time { return now }
	f.Now = clock
	s := &Service{Store: st, Client: client(f), Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Now: clock}
	if err := s.Sync(ctx, nil); err != nil {
		t.Fatal(err)
	}
	if f.Forecasts.Load() != 1 || len(fed) != 2 {
		t.Fatalf("first sync: %d fetches, feed for %v", f.Forecasts.Load(), fed)
	}
	for _, id := range []uuid.UUID{a, b} {
		if raw, err := st.FrameWeatherPayload(ctx, st.Q(), id); err != nil || raw == nil {
			t.Fatalf("payload for %s: %v", id, err)
		}
	}
	if raw, _ := st.FrameWeatherPayload(ctx, st.Q(), c); raw != nil {
		t.Fatal("weather for a frame with the widget off")
	}
	// Not due yet: no fetch, no changes.
	fed = nil
	now = now.Add(10 * time.Minute)
	if err := s.Sync(ctx, nil); err != nil || f.Forecasts.Load() != 1 || len(fed) != 0 {
		t.Fatalf("early sync: %v %d %v", err, f.Forecasts.Load(), fed)
	}
	// Due, but nothing meaningful changed (same hour, 0.1°): fetch, no change.
	now = now.Add(21 * time.Minute)
	f.Set(12.4, 3)
	if err := s.Sync(ctx, nil); err != nil || f.Forecasts.Load() != 2 {
		t.Fatalf("due sync: %v %d", err, f.Forecasts.Load())
	}
	sameHour := now.Add(-31*time.Minute).Truncate(time.Hour) == now.Truncate(time.Hour)
	if sameHour && len(fed) != 0 {
		t.Fatalf("unmeaningful change emitted: %v", fed)
	}
	// Failures back off without dropping the last good data.
	fed = nil
	f.SetFail(true)
	now = now.Add(40 * time.Minute)
	if err := s.Sync(ctx, nil); err != nil {
		t.Fatal(err)
	}
	e, _ := st.GetWeatherCache(ctx, Key(51.05, 3.72))
	if e.Failures != 1 || e.Payload == nil || e.NextFetchAt.Sub(now) != time.Minute || len(fed) != 0 {
		t.Fatalf("after failure: %+v %v", e, fed)
	}
	now = now.Add(30 * time.Second)
	_ = s.Sync(ctx, nil)
	if f.Forecasts.Load() != 3 {
		t.Fatalf("retried during back-off: %d", f.Forecasts.Load())
	}
	// Recovery with a real change notifies both frames.
	f.SetFail(false)
	f.Set(18, 61)
	now = now.Add(time.Minute)
	if err := s.Sync(ctx, nil); err != nil || len(fed) != 2 {
		t.Fatalf("recovery: %v %v", err, fed)
	}
	// Turning the widget off clears the frame's weather with one change.
	fed = nil
	if _, err := pool.Exec(ctx, `UPDATE frames SET settings = $2 WHERE id = $1`, a, settings(51.051, false)); err != nil {
		t.Fatal(err)
	}
	if err := s.Sync(ctx, &a); err != nil || len(fed) != 1 || fed[0] != a {
		t.Fatalf("clear: %v %v", err, fed)
	}
	if raw, _ := st.FrameWeatherPayload(ctx, st.Q(), a); raw != nil {
		t.Fatal("cleared frame still has weather")
	}
}
