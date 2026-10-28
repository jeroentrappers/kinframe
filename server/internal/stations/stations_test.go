package stations

import (
	"bytes"
	"net/url"
	"testing"
	"time"
)

// An upload as the VEVOR YT60234 sends it (EasyWeatherPro), with the console's "no value" markers.
func TestParse(t *testing.T) {
	q, _ := url.ParseQuery("ID=IAFFLI3&PASSWORD=k&action=updateraw&realtime=1&rtfreq=16&dateutc=2026-10-27%2010%3A15%3A02" +
		"&tempf=50.0&humidity=81&dewptf=44.4&windchillf=50.0&windspeedmph=5.6&windgustmph=8.9&winddir=225&baromin=29.92" +
		"&rainin=0.02&dailyrainin=0.15&solarradiation=55.3&UV=0&indoortempf=-9999&indoorhumidity=-9999")
	u, ok := Parse(q)
	if !ok || u.ID != "IAFFLI3" || u.Key != "k" {
		t.Fatalf("parse: %+v %v", u, ok)
	}
	r := u.Reading
	check := func(name string, got *float64, want float64) {
		t.Helper()
		if got == nil || *got != want {
			t.Fatalf("%s: %v, want %v", name, got, want)
		}
	}
	check("temp", r.TempC, 10)
	check("dew point", r.DewPointC, 6.9)
	check("feels like (wind chill)", r.FeelsLikeC, 10)
	check("pressure", r.PressureHpa, 1013.2)
	check("wind", r.WindMs, 2.5)
	check("gust", r.GustMs, 4)
	check("rain rate", r.RainRateMmH, 0.5)
	check("rain today", r.RainTodayMm, 3.8)
	check("solar", r.SolarWm2, 55)
	check("uv", r.UVIndex, 0)
	if r.IndoorTempC != nil || r.IndoorHumPct != nil {
		t.Fatal("the console's -9999 marker must read as absent")
	}
	if r.StationTime == nil || r.StationTime.Format("15:04:05") != "10:15:02" {
		t.Fatalf("station time: %v", r.StationTime)
	}
	// Impossible values are dropped, missing credentials refused.
	q.Set("tempf", "400")
	if u, _ := Parse(q); u.Reading.TempC != nil {
		t.Fatal("400 °F kept")
	}
	q.Del("PASSWORD")
	if _, ok := Parse(q); ok {
		t.Fatal("no key accepted")
	}
}

func TestMACNamesStationAndKeyTogether(t *testing.T) {
	k := []byte("server key")
	if !bytes.Equal(MAC(k, "iaffli3 ", "x"), MAC(k, "IAFFLI3", "x")) {
		t.Fatal("IDs differ only in case and spaces")
	}
	if bytes.Equal(MAC(k, "IAFFLI3", "x"), MAC(k, "IAFFLI3", "y")) || bytes.Equal(MAC(k, "A", "BC"), MAC(k, "AB", "C")) {
		t.Fatal("different stations share a MAC")
	}
}

func TestMakeSeries(t *testing.T) {
	f := func(v float64) *float64 { return &v }
	start := time.Date(2026, 10, 28, 9, 0, 0, 0, time.UTC)
	at := func(min int) time.Time { return start.Add(time.Duration(min) * time.Minute) }
	rows := []Timed{
		{at(0), Reading{TempC: f(10), GustMs: f(3), WindMs: f(2), WindDirDeg: f(350), RainTodayMm: f(1.2), UVIndex: f(1)}},
		{at(2), Reading{TempC: f(11), GustMs: f(5), WindMs: f(2), WindDirDeg: f(10), RainTodayMm: f(1.4), UVIndex: f(2)}},
		// Nothing in the second step; the third has one reading, and one falls outside.
		{at(11), Reading{TempC: f(12.25), HumidityPct: f(80.4)}},
		{at(15), Reading{TempC: f(99)}},
		{at(-1), Reading{TempC: f(-99)}},
	}
	s := MakeSeries(rows, start, 5*time.Minute, 3)
	val := func(p *float64) any {
		if p == nil {
			return nil
		}
		return *p
	}
	got := []any{val(s.TempC[0]), val(s.GustMs[0]), val(s.RainTodayMm[0]), val(s.UVIndex[0]), val(s.WindDirDeg[0]),
		val(s.TempC[1]), val(s.TempC[2]), val(s.HumidityPct[2]), val(s.GustMs[2]), s.StepS, len(s.TempC)}
	want := []any{10.5, 5.0, 1.4, 2.0, 0.0, nil, 12.3, 80.0, nil, 300, 3}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("series: got %v, want %v", got, want)
		}
	}
}
