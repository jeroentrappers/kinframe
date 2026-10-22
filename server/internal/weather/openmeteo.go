// Package weather fetches weather from Open-Meteo (https://open-meteo.com, data
// CC BY 4.0, no API key), caches it per rounded location and feeds it to frames
// as `weather` changes (docs/api/README.md §5.4). Frames never call Open-Meteo
// themselves. It also proxies place search for the frame settings
// (GET /v1/geocode).
package weather

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Client talks to the Open-Meteo forecast and geocoding APIs.
type Client struct {
	ForecastURL  string // e.g. https://api.open-meteo.com
	GeocodingURL string // e.g. https://geocoding-api.open-meteo.com
	UserAgent    string
	HTTP         *http.Client
}

func (c *Client) get(ctx context.Context, u string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	if c.UserAgent != "" {
		req.Header.Set("User-Agent", c.UserAgent)
	}
	req.Header.Set("Accept", "application/json")
	hc := c.HTTP
	if hc == nil {
		hc = &http.Client{Timeout: 15 * time.Second}
	}
	res, err := hc.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	body, err := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	if err != nil {
		return err
	}
	if res.StatusCode != http.StatusOK {
		var e struct {
			Reason string `json:"reason"`
		}
		_ = json.Unmarshal(body, &e)
		return fmt.Errorf("open-meteo: HTTP %d %s", res.StatusCode, e.Reason)
	}
	return json.Unmarshal(body, out)
}

// ---------------------------------------------------------------- forecast

// Payload is the `weather` change body (openapi.yaml Weather), without the
// per-frame location, which is added when rendering.
type Payload struct {
	FetchedAt        time.Time `json:"fetched_at"`
	Timezone         string    `json:"timezone,omitempty"`
	UTCOffsetSeconds int       `json:"utc_offset_seconds"`
	Units            Units     `json:"units"`
	Current          Current   `json:"current"`
	Hourly           []Hour    `json:"hourly"`
	Daily            []Day     `json:"daily"`
}

type Units struct {
	Temperature   string `json:"temperature"`
	WindSpeed     string `json:"wind_speed"`
	Precipitation string `json:"precipitation"`
}

var metric = Units{Temperature: "c", WindSpeed: "km/h", Precipitation: "mm"}

type Current struct {
	Time        *time.Time `json:"time,omitempty"`
	TempC       float64    `json:"temp_c"`
	ApparentC   *float64   `json:"apparent_c"`
	Code        int        `json:"code"`
	IsDay       bool       `json:"is_day"`
	WindKmh     *float64   `json:"wind_kmh"`
	WindDirDeg  *int       `json:"wind_dir_deg"`
	HumidityPct *int       `json:"humidity_pct"`
	PrecipMM    *float64   `json:"precip_mm"`
}

type Hour struct {
	Time       time.Time `json:"time"`
	TempC      float64   `json:"temp_c"`
	Code       int       `json:"code"`
	IsDay      bool      `json:"is_day"`
	PrecipProb *int      `json:"precip_prob"`
}

type Day struct {
	Date       string     `json:"date"`
	MinC       float64    `json:"min_c"`
	MaxC       float64    `json:"max_c"`
	Code       int        `json:"code"`
	PrecipMM   *float64   `json:"precip_mm"`
	PrecipProb *int       `json:"precip_prob"`
	Sunrise    *time.Time `json:"sunrise"`
	Sunset     *time.Time `json:"sunset"`
}

// forecastResponse is the subset of Open-Meteo's /v1/forecast we request.
// Every value may be null.
type forecastResponse struct {
	UTCOffsetSeconds int    `json:"utc_offset_seconds"`
	Timezone         string `json:"timezone"`
	Current          struct {
		Time        string   `json:"time"`
		Temperature *float64 `json:"temperature_2m"`
		Apparent    *float64 `json:"apparent_temperature"`
		Humidity    *float64 `json:"relative_humidity_2m"`
		IsDay       *float64 `json:"is_day"`
		Precip      *float64 `json:"precipitation"`
		Code        *float64 `json:"weather_code"`
		WindSpeed   *float64 `json:"wind_speed_10m"`
		WindDir     *float64 `json:"wind_direction_10m"`
	} `json:"current"`
	Hourly struct {
		Time        []string   `json:"time"`
		Temperature []*float64 `json:"temperature_2m"`
		Code        []*float64 `json:"weather_code"`
		PrecipProb  []*float64 `json:"precipitation_probability"`
		IsDay       []*float64 `json:"is_day"`
	} `json:"hourly"`
	Daily struct {
		Time       []string   `json:"time"`
		Code       []*float64 `json:"weather_code"`
		Max        []*float64 `json:"temperature_2m_max"`
		Min        []*float64 `json:"temperature_2m_min"`
		PrecipSum  []*float64 `json:"precipitation_sum"`
		PrecipProb []*float64 `json:"precipitation_probability_max"`
		Sunrise    []string   `json:"sunrise"`
		Sunset     []string   `json:"sunset"`
	} `json:"daily"`
}

// Forecast fetches current conditions, hourly and 7-day forecast (metric).
func (c *Client) Forecast(ctx context.Context, lat, lon float64, now time.Time) (*Payload, error) {
	q := url.Values{}
	q.Set("latitude", strconv.FormatFloat(lat, 'f', 4, 64))
	q.Set("longitude", strconv.FormatFloat(lon, 'f', 4, 64))
	q.Set("current", "temperature_2m,apparent_temperature,relative_humidity_2m,is_day,precipitation,weather_code,wind_speed_10m,wind_direction_10m")
	q.Set("hourly", "temperature_2m,weather_code,precipitation_probability,is_day")
	q.Set("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,sunrise,sunset")
	q.Set("timezone", "auto")
	q.Set("forecast_days", "7")
	var r forecastResponse
	if err := c.get(ctx, strings.TrimRight(c.ForecastURL, "/")+"/v1/forecast?"+q.Encode(), &r); err != nil {
		return nil, err
	}
	return normalize(&r, now)
}

const localLayout = "2006-01-02T15:04"

func round1(v float64) float64 { return math.Round(v*10) / 10 }

func f64(p *float64) *float64 {
	if p == nil {
		return nil
	}
	v := round1(*p)
	return &v
}

func intp(p *float64) *int {
	if p == nil {
		return nil
	}
	v := int(math.Round(*p))
	return &v
}

func at[T any](s []T, i int) (T, bool) {
	var zero T
	if i < len(s) {
		return s[i], true
	}
	return zero, false
}

func normalize(r *forecastResponse, now time.Time) (*Payload, error) {
	zone := time.FixedZone(r.Timezone, r.UTCOffsetSeconds)
	parse := func(s string) (*time.Time, bool) {
		t, err := time.ParseInLocation(localLayout, s, zone)
		if err != nil {
			return nil, false
		}
		return &t, true
	}
	if r.Current.Temperature == nil || r.Current.Code == nil {
		return nil, errors.New("open-meteo: current conditions missing")
	}
	p := &Payload{
		FetchedAt: now.UTC().Truncate(time.Second), Timezone: r.Timezone, UTCOffsetSeconds: r.UTCOffsetSeconds, Units: metric,
		Current: Current{
			TempC: round1(*r.Current.Temperature), ApparentC: f64(r.Current.Apparent), Code: int(*r.Current.Code),
			IsDay: r.Current.IsDay != nil && *r.Current.IsDay >= 1, WindKmh: f64(r.Current.WindSpeed),
			WindDirDeg: intp(r.Current.WindDir), HumidityPct: intp(r.Current.Humidity), PrecipMM: f64(r.Current.Precip),
		},
		Hourly: []Hour{}, Daily: []Day{},
	}
	start := now.In(zone).Truncate(time.Hour)
	if t, ok := parse(r.Current.Time); ok {
		p.Current.Time = t
		start = t.Truncate(time.Hour)
	}
	for i, ts := range r.Hourly.Time {
		t, ok := parse(ts)
		if !ok || t.Before(start) {
			continue
		}
		temp, _ := at(r.Hourly.Temperature, i)
		code, _ := at(r.Hourly.Code, i)
		if temp == nil || code == nil {
			continue
		}
		h := Hour{Time: *t, TempC: round1(*temp), Code: int(*code)}
		if d, _ := at(r.Hourly.IsDay, i); d != nil {
			h.IsDay = *d >= 1
		}
		pp, _ := at(r.Hourly.PrecipProb, i)
		h.PrecipProb = intp(pp)
		p.Hourly = append(p.Hourly, h)
		if len(p.Hourly) == 24 {
			break
		}
	}
	for i, date := range r.Daily.Time {
		lo, _ := at(r.Daily.Min, i)
		hi, _ := at(r.Daily.Max, i)
		code, _ := at(r.Daily.Code, i)
		if lo == nil || hi == nil || code == nil {
			continue
		}
		d := Day{Date: date, MinC: round1(*lo), MaxC: round1(*hi), Code: int(*code)}
		ps, _ := at(r.Daily.PrecipSum, i)
		d.PrecipMM = f64(ps)
		pp, _ := at(r.Daily.PrecipProb, i)
		d.PrecipProb = intp(pp)
		if s, ok := at(r.Daily.Sunrise, i); ok {
			d.Sunrise, _ = parse(s)
		}
		if s, ok := at(r.Daily.Sunset, i); ok {
			d.Sunset, _ = parse(s)
		}
		p.Daily = append(p.Daily, d)
		if len(p.Daily) == 7 {
			break
		}
	}
	return p, nil
}

// ---------------------------------------------------------------- geocoding

// Place is one geocoding result (openapi.yaml Place).
type Place struct {
	Name        string  `json:"name"`
	Admin1      *string `json:"admin1,omitempty"`
	Country     *string `json:"country,omitempty"`
	CountryCode *string `json:"country_code,omitempty"`
	Lat         float64 `json:"lat"`
	Lon         float64 `json:"lon"`
	Timezone    *string `json:"timezone,omitempty"`
}

// Search looks up places by name.
func (c *Client) Search(ctx context.Context, q, lang string, count int) ([]Place, error) {
	v := url.Values{}
	v.Set("name", q)
	v.Set("count", strconv.Itoa(count))
	v.Set("language", lang)
	v.Set("format", "json")
	var r struct {
		Results []struct {
			Name        string  `json:"name"`
			Latitude    float64 `json:"latitude"`
			Longitude   float64 `json:"longitude"`
			CountryCode string  `json:"country_code"`
			Country     string  `json:"country"`
			Admin1      string  `json:"admin1"`
			Timezone    string  `json:"timezone"`
		} `json:"results"`
	}
	if err := c.get(ctx, strings.TrimRight(c.GeocodingURL, "/")+"/v1/search?"+v.Encode(), &r); err != nil {
		return nil, err
	}
	opt := func(s string) *string {
		if s == "" {
			return nil
		}
		return &s
	}
	out := make([]Place, 0, len(r.Results))
	for _, x := range r.Results {
		out = append(out, Place{
			Name: x.Name, Admin1: opt(x.Admin1), Country: opt(x.Country), CountryCode: opt(strings.ToUpper(x.CountryCode)),
			Lat: x.Latitude, Lon: x.Longitude, Timezone: opt(x.Timezone),
		})
	}
	return out, nil
}
