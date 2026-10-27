package e2e

import (
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
)

// A personal weather station uploading in the Weather Underground format (docs/plugins.md §9.4): only registered
// stations are kept, their uploads are passed on unchanged, and the frame and Home Assistant read them back.
func TestWeatherStationUploads(t *testing.T) {
	var mu sync.Mutex
	var forwarded []string
	wu := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		forwarded = append(forwarded, r.URL.RawQuery)
		mu.Unlock()
		w.Write([]byte("success\n"))
	}))
	defer wu.Close()
	e := newEnv(t, func(c *config.Config) { c.StationForwardURL = wu.URL + "/weatherstation/updateweatherstation.php" })
	owner := e.user("o@x.org", "O")
	d, frameID := e.claim(owner)
	e.expect(req{method: "PUT", path: "/v1/device/plugins/catalog", token: d.token, body: catalogOf(t, "kinframe.weather-station")}, 204)

	upload := func(id, key string) (int, string) {
		q := url.Values{"ID": {id}, "PASSWORD": {key}, "action": {"updateraw"}, "dateutc": {"now"},
			"tempf": {"50.0"}, "humidity": {"81"}, "dewptf": {"44.4"}, "windspeedmph": {"5.6"}, "windgustmph": {"8.9"},
			"winddir": {"225"}, "baromin": {"29.92"}, "rainin": {"0.02"}, "dailyrainin": {"0.15"}, "solarradiation": {"55.3"},
			"UV": {"0"}, "softwaretype": {"EasyWeatherPro_V5.1.1"}}
		res, err := http.Get(e.url + "/weatherstation/updateweatherstation.php?" + q.Encode())
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return res.StatusCode, string(b)
	}
	latest := func(id, key string) *resp {
		return e.do(req{method: "GET", path: "/v1/stations/latest", header: map[string]string{"X-Station-Id": id, "X-Station-Key": key}})
	}

	// Not registered yet: refused as Weather Underground refuses a wrong key.
	if code, body := upload("IAFFLI3", "k3y"); code != 401 || !strings.HasPrefix(body, "INVALIDPASSWORDID") {
		t.Fatalf("unregistered: %d %q", code, body)
	}
	// The frame's weather-station instance registers it (ID in the settings, key as a secret).
	settings := map[string]any{"plugins": map[string]any{"instances": []any{map[string]any{
		"instance_id": "builtin-weather-station", "plugin_id": "kinframe.weather-station", "enabled": true,
		"settings": map[string]any{"source": "upload", "station_id": "IAFFLI3"}, "secrets": map[string]any{"station_key": "k3y"}}}}}
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frameID + "/settings", token: owner.token, body: settings, header: map[string]string{"If-Match": `"1"`}}, 200)

	if code, body := upload("IAFFLI3", "k3y"); code != 200 || body != "success\n" {
		t.Fatalf("upload: %d %q", code, body)
	}
	if code, _ := upload("IAFFLI3", "wrong"); code != 401 {
		t.Fatalf("wrong key: %d", code)
	}
	r := latest("IAFFLI3", "k3y")
	if r.StatusCode != 200 {
		t.Fatalf("latest: %d %s", r.StatusCode, r.body)
	}
	m := r.m(t)
	rd := m["reading"].(map[string]any)
	if rd["temp_c"] != 10.0 || rd["humidity_pct"] != 81.0 || rd["wind_ms"] != 2.5 || rd["gust_ms"] != 4.0 || rd["pressure_hpa"] != 1013.2 ||
		rd["rain_today_mm"] != 3.8 || rd["rain_rate_mm_h"] != 0.5 || rd["wind_dir_deg"] != 225.0 {
		t.Fatalf("reading: %v", rd)
	}
	if dig(m, "today", "temp_max_c") != 10.0 {
		t.Fatalf("today: %v", m["today"])
	}
	if r := latest("IAFFLI3", "wrong"); r.StatusCode != 404 {
		t.Fatalf("wrong key reads: %d", r.StatusCode)
	}
	// Passed on, unchanged (the key included: it is Weather Underground's).
	deadline := time.Now().Add(3 * time.Second)
	for {
		mu.Lock()
		n := len(forwarded)
		mu.Unlock()
		if n > 0 || time.Now().After(deadline) {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	mu.Lock()
	if len(forwarded) != 1 || !strings.Contains(forwarded[0], "PASSWORD=k3y") || !strings.Contains(forwarded[0], "tempf=50.0") {
		t.Fatalf("forwarded: %v", forwarded)
	}
	mu.Unlock()

	// Turned off: no longer passed on; removed: no longer accepted.
	off := settings["plugins"].(map[string]any)["instances"].([]any)[0].(map[string]any)
	off["settings"].(map[string]any)["forward_wunderground"] = false
	delete(off, "secrets")
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frameID + "/settings", token: owner.token, body: settings, header: map[string]string{"If-Match": `"2"`}}, 200)
	upload("IAFFLI3", "k3y")
	time.Sleep(200 * time.Millisecond)
	mu.Lock()
	if len(forwarded) != 1 {
		t.Fatalf("forwarded while off: %d", len(forwarded))
	}
	mu.Unlock()
	e.expect(req{method: "PATCH", path: "/v1/frames/" + frameID + "/settings", token: owner.token,
		body: map[string]any{"plugins": map[string]any{"instances": []any{}}}, header: map[string]string{"If-Match": `"3"`}}, 200)
	if code, _ := upload("IAFFLI3", "k3y"); code != 401 {
		t.Fatalf("after removal: %d", code)
	}
}
