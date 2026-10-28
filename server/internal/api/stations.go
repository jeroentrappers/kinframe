package api

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/settings"
	"github.com/jeroentrappers/kinframe/server/internal/stations"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// The weather-station plugin and its settings for uploads (plugins/kinframe.weather-station).
const (
	stationPlugin = "kinframe.weather-station"
	stationSource = "upload"
)

// stationUpload receives a Weather Underground upload (hand-written: the stations' own protocol, plain text). A
// station sends one every 16 s to a minute; only stations a frame registered are kept, and those are passed on to
// Weather Underground unless the frame turned that off. The answer is what Weather Underground answers.
func (s *Server) stationUpload(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	if ok, _ := s.stationIP.Allow(clientIP(r.Context())); !ok {
		w.WriteHeader(http.StatusTooManyRequests)
		_, _ = w.Write([]byte("too many uploads\n"))
		return
	}
	up, ok := stations.Parse(r.URL.Query())
	if !ok {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte("INVALIDPASSWORDID|Password and/or id are incorrect\n"))
		return
	}
	ctx := r.Context()
	mac := stations.MAC(s.stationKey, up.ID, up.Key)
	regs, err := s.st.StationsByMAC(ctx, s.st.Q(), mac)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	if len(regs) == 0 {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte("INVALIDPASSWORDID|Password and/or id are incorrect\n"))
		return
	}
	raw, _ := json.Marshal(up.Reading)
	if err := s.st.AddStationReading(ctx, mac, time.Now().UTC(), raw); err != nil {
		s.writeError(w, r, err)
		return
	}
	for _, reg := range regs {
		if reg.Forward {
			query := r.URL.RawQuery
			go func() {
				fctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
				defer cancel()
				s.stationForward.Forward(fctx, query)
			}()
			break
		}
	}
	_, _ = w.Write([]byte("success\n"))
}

// GetStationLatest: the latest reading for the frame's widget and Home Assistant.
func (s *Server) GetStationLatest(ctx context.Context, req gen.GetStationLatestRequestObject) (gen.GetStationLatestResponseObject, error) {
	if ok, retry := s.stationIP.Allow(clientIP(ctx)); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	mac := stations.MAC(s.stationKey, req.Params.XStationId, req.Params.XStationKey)
	regs, err := s.st.StationsByMAC(ctx, s.st.Q(), mac)
	if err != nil {
		return nil, err
	}
	if len(regs) == 0 {
		return nil, problem.New(problem.NotFound, "no readings from this station")
	}
	zone := time.UTC
	if f, err := s.st.GetFrame(ctx, s.st.Q(), regs[0].FrameID); err == nil {
		zone = frameZone(f)
	}
	now := time.Now().In(zone)
	midnight := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, zone)
	rows, err := s.st.StationReadings(ctx, mac, now.Add(-48*time.Hour))
	if err != nil {
		return nil, err
	}
	if len(rows) == 0 {
		return nil, problem.New(problem.NotFound, "no readings from this station")
	}
	var out struct {
		ReceivedAt time.Time       `json:"received_at"`
		Reading    json.RawMessage `json:"reading"`
		Today      struct {
			TempMin *float64 `json:"temp_min_c"`
			TempMax *float64 `json:"temp_max_c"`
			GustMax *float64 `json:"gust_max_ms"`
		} `json:"today"`
	}
	last := rows[len(rows)-1]
	out.ReceivedAt, out.Reading = last.At.UTC(), last.Reading
	for _, row := range rows {
		if row.At.Before(midnight) {
			continue
		}
		var rd stations.Reading
		if json.Unmarshal(row.Reading, &rd) != nil {
			continue
		}
		out.Today.TempMin = minPtr(out.Today.TempMin, rd.TempC, true)
		out.Today.TempMax = minPtr(out.Today.TempMax, rd.TempC, false)
		out.Today.GustMax = minPtr(out.Today.GustMax, rd.GustMs, false)
	}
	return Response{Status: 200, Body: out}, nil
}

// GetStationPublic: a station's public page, when its owner turned it on.
func (s *Server) GetStationPublic(ctx context.Context, req gen.GetStationPublicRequestObject) (gen.GetStationPublicResponseObject, error) {
	if ok, retry := s.stationPage.Allow(clientIP(ctx)); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	notFound := problem.New(problem.NotFound, "no readings from this station")
	regs, err := s.st.PublicStations(ctx, s.st.Q(), strings.TrimSpace(req.StationId))
	if err != nil {
		return nil, err
	}
	// Several frames may name the same station; the registration whose readings are newest wins.
	const span, step = 48 * time.Hour, 5 * time.Minute
	now := time.Now().UTC()
	start := now.Truncate(step).Add(step - span)
	var reg *store.WeatherStation
	var rows []store.StationReading
	for i := range regs {
		got, err := s.st.StationReadings(ctx, regs[i].KeyMAC, start)
		if err != nil {
			return nil, err
		}
		if len(got) > 0 && (len(rows) == 0 || got[len(got)-1].At.After(rows[len(rows)-1].At)) {
			reg, rows = &regs[i], got
		}
	}
	if reg == nil {
		return nil, notFound
	}
	zone := time.UTC
	if f, err := s.st.GetFrame(ctx, s.st.Q(), reg.FrameID); err == nil {
		zone = frameZone(f)
	}
	local := now.In(zone)
	midnight := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, zone)

	type extreme struct {
		v  *float64
		at *time.Time
	}
	pick := func(e *extreme, v *float64, at time.Time, min bool) {
		if v != nil && (e.v == nil || (min && *v < *e.v) || (!min && *v > *e.v)) {
			x, t := *v, at.UTC()
			e.v, e.at = &x, &t
		}
	}
	var lo, hi, gust, uv extreme
	var rain *float64
	timed := make([]stations.Timed, 0, len(rows))
	for _, row := range rows {
		var rd stations.Reading
		if json.Unmarshal(row.Reading, &rd) != nil {
			continue
		}
		timed = append(timed, stations.Timed{At: row.At, Reading: rd})
		if row.At.Before(midnight) {
			continue
		}
		pick(&lo, rd.TempC, row.At, true)
		pick(&hi, rd.TempC, row.At, false)
		pick(&gust, rd.GustMs, row.At, false)
		pick(&uv, rd.UVIndex, row.At, false)
		if rd.RainTodayMm != nil {
			rain = rd.RainTodayMm
		}
	}
	type today struct {
		TempMin   *float64   `json:"temp_min_c"`
		TempMinAt *time.Time `json:"temp_min_at"`
		TempMax   *float64   `json:"temp_max_c"`
		TempMaxAt *time.Time `json:"temp_max_at"`
		GustMax   *float64   `json:"gust_max_ms"`
		GustMaxAt *time.Time `json:"gust_max_at"`
		Rain      *float64   `json:"rain_mm"`
		UVMax     *float64   `json:"uv_max"`
	}
	last := rows[len(rows)-1]
	out := struct {
		StationID  string          `json:"station_id"`
		Name       string          `json:"name"`
		Timezone   string          `json:"timezone"`
		ReceivedAt time.Time       `json:"received_at"`
		Reading    json.RawMessage `json:"reading"`
		Today      today           `json:"today"`
		Series     stations.Series `json:"series"`
	}{
		StationID: reg.UploadID, Name: reg.PublicName, Timezone: zone.String(), ReceivedAt: last.At.UTC(), Reading: last.Reading,
		Today:  today{lo.v, lo.at, hi.v, hi.at, gust.v, gust.at, rain, uv.v},
		Series: stations.MakeSeries(timed, start, step, int(span/step)),
	}
	return Response{Status: 200, Body: out, Header: http.Header{"Cache-Control": {"public, max-age=30"}}}, nil
}

func minPtr(cur, v *float64, min bool) *float64 {
	if v == nil {
		return cur
	}
	if cur == nil || (min && *v < *cur) || (!min && *v > *cur) {
		x := *v
		return &x
	}
	return cur
}

func frameZone(f *store.Frame) *time.Location {
	var doc struct {
		Locale struct {
			Timezone string `json:"timezone"`
		} `json:"locale"`
	}
	if json.Unmarshal(f.Settings, &doc) == nil && doc.Locale.Timezone != "" {
		if z, err := time.LoadLocation(doc.Locale.Timezone); err == nil {
			return z
		}
	}
	return time.UTC
}

// syncStations registers the stations the frame's weather-station instances upload from (their station ID and key),
// after the settings changed.
func (s *Server) syncStations(ctx context.Context, tx *store.Tx, frameID uuid.UUID, doc map[string]any) error {
	instances, _ := settings.PluginInstances(doc)
	var regs []store.WeatherStation
	for _, in := range instances {
		if pid, _ := in["plugin_id"].(string); pid != stationPlugin {
			continue
		}
		values, _ := in["settings"].(map[string]any)
		// Left out while it has the manifest's default, "upload" (the forms don't store defaults).
		if src, _ := values["source"].(string); src != "" && src != stationSource {
			continue
		}
		id, _ := values["station_id"].(string)
		key, err := settings.OpenSecret(in, "station_key", s.pluginSealer)
		if err != nil || strings.TrimSpace(id) == "" || key == "" {
			continue
		}
		iid, _ := in["instance_id"].(string)
		forward := true
		if f, ok := values["forward_wunderground"].(bool); ok {
			forward = f
		}
		public, _ := values["public_page"].(bool)
		name, _ := values["public_name"].(string)
		regs = append(regs, store.WeatherStation{FrameID: frameID, InstanceID: iid, UploadID: strings.TrimSpace(id), KeyMAC: stations.MAC(s.stationKey, id, key),
			Forward: forward, Public: public, PublicName: strings.TrimSpace(name)})
	}
	return tx.SetFrameStations(ctx, frameID, regs)
}
