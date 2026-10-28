// Package stations receives uploads from personal weather stations in the
// Weather Underground format (docs/plugins.md §9.4): the station sends a GET
// with its readings in imperial units, the server keeps them in metric for the
// frames and Home Assistant, and passes the upload on to Weather Underground
// unchanged so the owner's dashboard there keeps working.
package stations

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"log/slog"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Reading is one upload in metric units; absent values are nil.
type Reading struct {
	StationTime  *time.Time `json:"station_time,omitempty"`
	TempC        *float64   `json:"temp_c,omitempty"`
	HumidityPct  *float64   `json:"humidity_pct,omitempty"`
	DewPointC    *float64   `json:"dew_point_c,omitempty"`
	FeelsLikeC   *float64   `json:"feels_like_c,omitempty"`
	PressureHpa  *float64   `json:"pressure_hpa,omitempty"`
	WindMs       *float64   `json:"wind_ms,omitempty"`
	WindDirDeg   *float64   `json:"wind_dir_deg,omitempty"`
	GustMs       *float64   `json:"gust_ms,omitempty"`
	RainRateMmH  *float64   `json:"rain_rate_mm_h,omitempty"`
	RainTodayMm  *float64   `json:"rain_today_mm,omitempty"`
	SolarWm2     *float64   `json:"solar_wm2,omitempty"`
	UVIndex      *float64   `json:"uv_index,omitempty"`
	IndoorTempC  *float64   `json:"indoor_temp_c,omitempty"`
	IndoorHumPct *float64   `json:"indoor_humidity_pct,omitempty"`
}

// Upload is a parsed Weather Underground upload.
type Upload struct {
	ID, Key string
	Reading Reading
}

// Parse reads a Weather Underground upload's query (ID, PASSWORD, tempf, …).
// Values outside what a station can measure are dropped, as are the station's
// "no value" markers (-9999).
func Parse(q url.Values) (Upload, bool) {
	u := Upload{ID: strings.TrimSpace(q.Get("ID")), Key: q.Get("PASSWORD")}
	if u.ID == "" || u.Key == "" || len(u.ID) > 64 || len(u.Key) > 128 {
		return u, false
	}
	num := func(k string, lo, hi float64) *float64 {
		s := strings.TrimSpace(q.Get(k))
		if s == "" {
			return nil
		}
		v, err := strconv.ParseFloat(s, 64)
		if err != nil || math.IsNaN(v) || math.IsInf(v, 0) || v <= -9999 || v < lo || v > hi {
			return nil
		}
		return &v
	}
	conv := func(v *float64, f func(float64) float64, decimals int) *float64 {
		if v == nil {
			return nil
		}
		p := math.Pow(10, float64(decimals))
		r := math.Round(f(*v)*p) / p
		return &r
	}
	fToC := func(f float64) float64 { return (f - 32) * 5 / 9 }
	mphToMs := func(m float64) float64 { return m * 0.44704 }
	inToMm := func(i float64) float64 { return i * 25.4 }
	r := &u.Reading
	r.TempC = conv(num("tempf", -130, 160), fToC, 1)
	r.HumidityPct = conv(num("humidity", 0, 100), func(x float64) float64 { return x }, 0)
	r.DewPointC = conv(num("dewptf", -130, 160), fToC, 1)
	if v := num("heatindexf", -130, 180); v != nil {
		r.FeelsLikeC = conv(v, fToC, 1)
	} else {
		r.FeelsLikeC = conv(num("windchillf", -130, 160), fToC, 1)
	}
	r.PressureHpa = conv(num("baromin", 25, 33), func(x float64) float64 { return x * 33.8639 }, 1)
	r.WindMs = conv(num("windspeedmph", 0, 270), mphToMs, 1)
	r.WindDirDeg = conv(num("winddir", 0, 360), func(x float64) float64 { return x }, 0)
	r.GustMs = conv(num("windgustmph", 0, 340), mphToMs, 1)
	r.RainRateMmH = conv(num("rainin", 0, 80), inToMm, 1)
	r.RainTodayMm = conv(num("dailyrainin", 0, 80), inToMm, 1)
	r.SolarWm2 = conv(num("solarradiation", 0, 2000), func(x float64) float64 { return x }, 0)
	r.UVIndex = conv(num("UV", 0, 25), func(x float64) float64 { return x }, 1)
	r.IndoorTempC = conv(num("indoortempf", -40, 160), fToC, 1)
	r.IndoorHumPct = conv(num("indoorhumidity", 0, 100), func(x float64) float64 { return x }, 0)
	if s := q.Get("dateutc"); s != "" && s != "now" {
		if t, err := time.Parse("2006-01-02 15:04:05", s); err == nil {
			r.StationTime = &t
		}
	}
	return u, true
}

// MAC identifies a station by its ID and key without keeping the key: an HMAC
// with the server's own key.
func MAC(serverKey []byte, id, key string) []byte {
	m := hmac.New(sha256.New, serverKey)
	m.Write([]byte(strings.ToUpper(strings.TrimSpace(id))))
	m.Write([]byte{0})
	m.Write([]byte(key))
	return m.Sum(nil)
}

// Forwarder passes uploads on to Weather Underground.
type Forwarder struct {
	URL  string // e.g. http://rtupdate.wunderground.com/weatherstation/updateweatherstation.php
	HTTP *http.Client
	Log  *slog.Logger
}

// Forward sends the upload's query unchanged; errors are logged (without the query: it holds the key).
func (f *Forwarder) Forward(ctx context.Context, rawQuery string) {
	if f == nil || f.URL == "" {
		return
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, f.URL+"?"+rawQuery, nil)
	if err != nil {
		return
	}
	res, err := f.HTTP.Do(req)
	if err != nil {
		f.Log.Info("station upload not passed on", "err", redact(err.Error(), rawQuery))
		return
	}
	res.Body.Close()
	if res.StatusCode != http.StatusOK {
		f.Log.Info("station upload not passed on", "status", res.StatusCode)
	}
}

func redact(s, query string) string {
	if query == "" {
		return s
	}
	return strings.ReplaceAll(s, query, "…")
}

// Series is a station's readings in fixed steps for charts (the public page): one value per step, null where
// nothing arrived. Most values are the step's mean; gusts, rain rate and UV its maximum, today's rain its last
// value, and the wind direction the mean of the step's directions weighted by wind speed.
type Series struct {
	Start       time.Time  `json:"start"`
	StepS       int        `json:"step_s"`
	TempC       []*float64 `json:"temp_c"`
	DewPointC   []*float64 `json:"dew_point_c"`
	FeelsLikeC  []*float64 `json:"feels_like_c"`
	HumidityPct []*float64 `json:"humidity_pct"`
	PressureHpa []*float64 `json:"pressure_hpa"`
	WindMs      []*float64 `json:"wind_ms"`
	GustMs      []*float64 `json:"gust_ms"`
	WindDirDeg  []*float64 `json:"wind_dir_deg"`
	RainRateMmH []*float64 `json:"rain_rate_mm_h"`
	RainTodayMm []*float64 `json:"rain_today_mm"`
	SolarWm2    []*float64 `json:"solar_wm2"`
	UVIndex     []*float64 `json:"uv_index"`
}

// Timed is a reading and when it arrived.
type Timed struct {
	At      time.Time
	Reading Reading
}

// MakeSeries puts readings between start and start+n*step into n steps.
func MakeSeries(rows []Timed, start time.Time, step time.Duration, n int) Series {
	type acc struct {
		sum, max, last float64
		count          int
	}
	type bucket struct {
		mean                 [7]acc // temp, dew, feels, humidity, pressure, wind, solar
		gust, rate, uv, rain acc
		dirX, dirY           float64
		dirs                 int
	}
	b := make([]bucket, n)
	add := func(a *acc, v *float64) {
		if v == nil {
			return
		}
		if a.count == 0 || *v > a.max {
			a.max = *v
		}
		a.sum += *v
		a.last = *v
		a.count++
	}
	for _, row := range rows {
		if row.At.Before(start) {
			continue
		}
		i := int(row.At.Sub(start) / step)
		if i >= n {
			continue
		}
		r, k := row.Reading, &b[i]
		for j, v := range []*float64{r.TempC, r.DewPointC, r.FeelsLikeC, r.HumidityPct, r.PressureHpa, r.WindMs, r.SolarWm2} {
			add(&k.mean[j], v)
		}
		add(&k.gust, r.GustMs)
		add(&k.rate, r.RainRateMmH)
		add(&k.uv, r.UVIndex)
		add(&k.rain, r.RainTodayMm)
		if r.WindDirDeg != nil {
			w := 0.1 // calm readings still count, a little
			if r.WindMs != nil && *r.WindMs > w {
				w = *r.WindMs
			}
			rad := *r.WindDirDeg * math.Pi / 180
			k.dirX += w * math.Sin(rad)
			k.dirY += w * math.Cos(rad)
			k.dirs++
		}
	}
	round := func(v float64, decimals int) *float64 {
		p := math.Pow(10, float64(decimals))
		x := math.Round(v*p) / p
		return &x
	}
	col := func(f func(*bucket) *float64) []*float64 {
		out := make([]*float64, n)
		for i := range b {
			out[i] = f(&b[i])
		}
		return out
	}
	mean := func(j, decimals int) []*float64 {
		return col(func(k *bucket) *float64 {
			if a := k.mean[j]; a.count > 0 {
				return round(a.sum/float64(a.count), decimals)
			}
			return nil
		})
	}
	pick := func(a func(*bucket) acc, last bool) []*float64 {
		return col(func(k *bucket) *float64 {
			x := a(k)
			if x.count == 0 {
				return nil
			}
			if last {
				return round(x.last, 1)
			}
			return round(x.max, 1)
		})
	}
	return Series{
		Start: start.UTC(), StepS: int(step / time.Second),
		TempC: mean(0, 1), DewPointC: mean(1, 1), FeelsLikeC: mean(2, 1), HumidityPct: mean(3, 0), PressureHpa: mean(4, 1),
		WindMs: mean(5, 1), SolarWm2: mean(6, 0),
		GustMs:      pick(func(k *bucket) acc { return k.gust }, false),
		RainRateMmH: pick(func(k *bucket) acc { return k.rate }, false),
		UVIndex:     pick(func(k *bucket) acc { return k.uv }, false),
		RainTodayMm: pick(func(k *bucket) acc { return k.rain }, true),
		WindDirDeg: col(func(k *bucket) *float64 {
			if k.dirs == 0 {
				return nil
			}
			d := math.Mod(math.Atan2(k.dirX, k.dirY)*180/math.Pi+360, 360)
			return round(d, 0)
		}),
	}
}
