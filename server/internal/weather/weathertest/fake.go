// Package weathertest is a fake Open-Meteo (forecast + geocoding) for tests.
package weathertest

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"time"
)

// Fake serves /v1/forecast and /v1/search. Forecasts are generated around Now
// (default time.Now) in a UTC+2 zone, like Open-Meteo answers for Belgium in summer.
type Fake struct {
	*httptest.Server
	Forecasts atomic.Int64
	Searches  atomic.Int64

	mu       sync.Mutex
	Now      func() time.Time
	TempC    float64 // current temperature
	Code     int     // current WMO code
	Fail     bool    // answer 500
	LastPath string
}

// New starts a fake; close it with t.Cleanup(f.Close).
func New() *Fake {
	f := &Fake{TempC: 12.3, Code: 3}
	f.Server = httptest.NewServer(http.HandlerFunc(f.serve))
	return f
}

// Set changes the current conditions returned from now on.
func (f *Fake) Set(temp float64, code int) {
	f.mu.Lock()
	f.TempC, f.Code = temp, code
	f.mu.Unlock()
}

// SetFail makes every request fail (or succeed again).
func (f *Fake) SetFail(fail bool) {
	f.mu.Lock()
	f.Fail = fail
	f.mu.Unlock()
}

const offset = 7200

func (f *Fake) serve(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	fail, temp, code := f.Fail, f.TempC, f.Code
	now := time.Now()
	if f.Now != nil {
		now = f.Now()
	}
	f.LastPath = r.URL.String()
	f.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	switch r.URL.Path {
	case "/v1/forecast":
		f.Forecasts.Add(1)
		if fail {
			w.WriteHeader(500)
			_, _ = w.Write([]byte(`{"error":true,"reason":"boom"}`))
			return
		}
		_ = json.NewEncoder(w).Encode(Forecast(now, temp, code))
	case "/v1/search":
		f.Searches.Add(1)
		if fail {
			w.WriteHeader(500)
			return
		}
		if r.URL.Query().Get("name") == "nowhere" {
			_, _ = w.Write([]byte(`{"generationtime_ms":0.1}`))
			return
		}
		lang := r.URL.Query().Get("language")
		country := "Belgium"
		if lang == "nl" {
			country = "België"
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"results": []map[string]any{
			{"id": 2797656, "name": "Gent", "latitude": 51.05, "longitude": 3.71667, "country_code": "BE",
				"country": country, "admin1": "Flanders", "timezone": "Europe/Brussels"},
			{"id": 1, "name": "Gentbrugge", "latitude": 51.04, "longitude": 3.75, "country_code": "be", "country": country},
		}})
	default:
		w.WriteHeader(404)
	}
}

// Forecast builds an Open-Meteo style /v1/forecast response around now.
func Forecast(now time.Time, temp float64, code int) map[string]any {
	zone := time.FixedZone("Europe/Brussels", offset)
	local := now.In(zone)
	cur := local.Truncate(15 * time.Minute)
	dayStart := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, zone)
	const layout = "2006-01-02T15:04"
	var ht []string
	var htemp, hcode, hprob, hday []any
	for i := 0; i < 7*24; i++ {
		t := dayStart.Add(time.Duration(i) * time.Hour)
		ht = append(ht, t.Format(layout))
		htemp = append(htemp, 10+float64(i%24)/2)
		hcode = append(hcode, []int{0, 2, 3, 61}[i%4])
		hprob = append(hprob, (i*7)%100)
		day := 0
		if h := t.Hour(); h >= 7 && h < 19 {
			day = 1
		}
		hday = append(hday, day)
	}
	var dt, sr, ss []string
	var dcode, dmax, dmin, dsum, dprob []any
	for i := 0; i < 7; i++ {
		d := dayStart.AddDate(0, 0, i)
		dt = append(dt, d.Format("2006-01-02"))
		sr = append(sr, d.Add(7*time.Hour+58*time.Minute).Format(layout))
		ss = append(ss, d.Add(18*time.Hour+31*time.Minute).Format(layout))
		dcode = append(dcode, []int{3, 61, 0, 95, 71, 45, 80}[i])
		dmax = append(dmax, 14+float64(i))
		dmin = append(dmin, 5+float64(i)/2)
		dsum = append(dsum, float64(i)*1.25)
		dprob = append(dprob, i*15)
	}
	// One gap, as Open-Meteo sends null for unavailable values.
	dprob[6] = nil
	return map[string]any{
		"latitude": 51.06, "longitude": 3.72, "generationtime_ms": 0.2,
		"utc_offset_seconds": offset, "timezone": "Europe/Brussels", "timezone_abbreviation": "CEST",
		"current": map[string]any{
			"time": cur.Format(layout), "interval": 900, "temperature_2m": temp, "apparent_temperature": temp - 2.04,
			"relative_humidity_2m": 81, "is_day": 1, "precipitation": 0.0, "weather_code": code,
			"wind_speed_10m": 14.76, "wind_direction_10m": 238,
		},
		"hourly": map[string]any{"time": ht, "temperature_2m": htemp, "weather_code": hcode,
			"precipitation_probability": hprob, "is_day": hday},
		"daily": map[string]any{"time": dt, "weather_code": dcode, "temperature_2m_max": dmax, "temperature_2m_min": dmin,
			"precipitation_sum": dsum, "precipitation_probability_max": dprob, "sunrise": sr, "sunset": ss},
	}
}
