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
		if src, _ := values["source"].(string); src != stationSource {
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
		regs = append(regs, store.WeatherStation{FrameID: frameID, InstanceID: iid, UploadID: strings.TrimSpace(id), KeyMAC: stations.MAC(s.stationKey, id, key), Forward: forward})
	}
	return tx.SetFrameStations(ctx, frameID, regs)
}
