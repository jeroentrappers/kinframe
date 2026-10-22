package e2e

import (
	"strconv"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/weather/weathertest"
)

func withWeather(f *weathertest.Fake) option {
	return func(c *config.Config) {
		c.WeatherURL, c.GeocodingURL, c.WeatherRefresh = f.URL, f.URL, 30*time.Minute
	}
}

// weatherChanges returns the frame's `weather` changes after since.
func (e *env) weatherChanges(d *device, since int) []map[string]any {
	e.t.Helper()
	var out []map[string]any
	for _, c := range e.get("/v1/device/changes?since="+strconv.Itoa(since), d.token).m(e.t)["changes"].([]any) {
		if cm := c.(map[string]any); cm["type"] == "weather" {
			out = append(out, cm)
		}
	}
	return out
}

func TestWeather(t *testing.T) {
	fake := weathertest.New()
	t.Cleanup(fake.Close)
	e := newEnv(t, withWeather(fake))
	if dig(e.get("/.well-known/kinframe", "").m(t), "weather") != true {
		t.Fatal("discovery does not announce weather")
	}
	owner := e.user("w@x.org", "W")
	d1, f1 := e.claim(owner)
	d2, f2 := e.claim(owner)
	ws := e.dialWS("/v1/device/ws", d1.token)
	ws.await("hello", 5*time.Second)
	patch := func(frameID string, body map[string]any) {
		t.Helper()
		path := "/v1/frames/" + frameID + "/settings"
		v := e.get(path, owner.token).Header.Get("ETag")
		e.expect(req{method: "PATCH", path: path, token: owner.token, body: body, header: map[string]string{"If-Match": v}}, 200)
	}
	head := func(d *device) int {
		return int(e.get("/v1/device/changes?since=0", d.token).m(t)["next_seq"].(float64))
	}

	// Setting the location alone does nothing while the widget is off.
	patch(f1, map[string]any{"location": map[string]any{"name": "Gent", "lat": 51.054, "lon": 3.7174}})
	time.Sleep(300 * time.Millisecond)
	if n := fake.Forecasts.Load(); n != 0 {
		t.Fatalf("fetched with the widget off: %d", n)
	}
	// Turning it on fetches right away and the frame gets a weather change (and a websocket hint).
	since := head(d1)
	patch(f1, map[string]any{"widgets": map[string]any{"weather": true}})
	var w map[string]any
	eventually(t, 5*time.Second, "weather change", func() bool {
		cs := e.weatherChanges(d1, since)
		if len(cs) == 0 || cs[len(cs)-1]["weather"] == nil {
			return false
		}
		w = cs[len(cs)-1]["weather"].(map[string]any)
		return true
	})
	ws.await("changes", 5*time.Second)
	if dig(w, "location", "name") != "Gent" || dig(w, "location", "lat") != 51.054 || dig(w, "current", "temp_c") != 12.3 ||
		dig(w, "current", "code") != float64(3) || dig(w, "units", "temperature") != "c" ||
		len(w["hourly"].([]any)) != 24 || len(w["daily"].([]any)) != 7 || w["fetched_at"] == nil {
		t.Fatalf("weather payload: %v", w)
	}

	// A second frame in the same town shares the cached fetch.
	patch(f2, map[string]any{"location": map[string]any{"name": "Ghent", "lat": 51.051, "lon": 3.721}, "widgets": map[string]any{"weather": true}})
	eventually(t, 5*time.Second, "weather on frame 2", func() bool {
		cs := e.weatherChanges(d2, 0)
		return len(cs) == 1 && dig(cs[0], "weather", "location", "name") == "Ghent"
	})
	if n := fake.Forecasts.Load(); n != 1 {
		t.Fatalf("upstream requests: %d, want 1 (shared cache)", n)
	}

	// Renaming the place re-sends; moving far away fetches the new location.
	since = head(d1)
	patch(f1, map[string]any{"location": map[string]any{"name": "Brugge", "lat": 51.2093, "lon": 3.2247}})
	eventually(t, 5*time.Second, "weather for the new location", func() bool {
		cs := e.weatherChanges(d1, since)
		return len(cs) > 0 && dig(cs[len(cs)-1], "weather", "location", "name") == "Brugge"
	})
	if n := fake.Forecasts.Load(); n != 2 {
		t.Fatalf("upstream requests: %d, want 2", n)
	}

	// Turning weather off sends weather: null; a snapshot then has no weather at all.
	since = head(d1)
	patch(f1, map[string]any{"widgets": map[string]any{"weather": false}})
	eventually(t, 5*time.Second, "weather: null", func() bool {
		cs := e.weatherChanges(d1, since)
		return len(cs) == 1 && cs[0]["weather"] == nil
	})
	if cs := e.weatherChanges(d1, 0); len(cs) != 0 {
		t.Fatalf("snapshot of a frame without weather: %v", cs)
	}
	if cs := e.weatherChanges(d2, 0); len(cs) != 1 || cs[0]["weather"] == nil {
		t.Fatalf("snapshot of frame 2: %v", cs)
	}

	// Place search.
	var places []map[string]any
	e.get("/v1/geocode?q=Gent&lang=nl", owner.token).json(t, &places)
	if len(places) != 2 || places[0]["name"] != "Gent" || places[0]["country"] != "België" || places[0]["country_code"] != "BE" ||
		places[0]["admin1"] != "Flanders" || places[0]["lat"] != 51.05 || places[0]["timezone"] != "Europe/Brussels" {
		t.Fatalf("geocode: %v", places)
	}
	if r := e.do(req{method: "GET", path: "/v1/geocode?q=Gent&lang=nl"}); r.StatusCode != 401 {
		t.Fatalf("anonymous geocode: %d", r.StatusCode)
	}
	if r := e.do(req{method: "GET", path: "/v1/geocode", token: owner.token}); r.StatusCode != 400 {
		t.Fatalf("missing q: %d", r.StatusCode)
	}
	fake.SetFail(true)
	if r := e.do(req{method: "GET", path: "/v1/geocode?q=Antwerpen", token: owner.token}); r.StatusCode != 502 || r.code() != "upstream_unavailable" {
		t.Fatalf("upstream failure: %d %s", r.StatusCode, r.body)
	}
	limited := false
	for i := 0; i < 70 && !limited; i++ {
		limited = e.do(req{method: "GET", path: "/v1/geocode?q=Gent&lang=nl", token: owner.token}).StatusCode == 429
	}
	if !limited {
		t.Fatal("geocode is not rate-limited")
	}
	if fake.Searches.Load() != 2 {
		t.Fatalf("searches: %d, want 2 (cached)", fake.Searches.Load())
	}
}

func TestWeatherDisabled(t *testing.T) {
	e := newEnv(t)
	if dig(e.get("/.well-known/kinframe", "").m(t), "weather") != false {
		t.Fatal("discovery announces weather")
	}
	u := e.user("n@x.org", "N")
	if r := e.do(req{method: "GET", path: "/v1/geocode?q=Gent", token: u.token}); r.StatusCode != 503 || r.code() != "feature_disabled" {
		t.Fatalf("geocode when disabled: %d %s", r.StatusCode, r.body)
	}
}
