package api

import (
	"context"
	"encoding/json"
	"strings"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/weather"
)

// frameWeather renders a frame's `weather` change payload, or nil when the
// frame should show no weather (turned off, no location, nothing fetched yet).
func (s *Server) frameWeather(ctx context.Context, f *store.Frame) (json.RawMessage, error) {
	if s.weather == nil {
		return nil, nil
	}
	loc, ok := weather.Wanted(f.Settings)
	if !ok {
		return nil, nil
	}
	raw, err := s.st.FrameWeatherPayload(ctx, s.st.Q(), f.ID)
	if err != nil || raw == nil {
		return nil, err
	}
	return weather.Render(raw, loc), nil
}

func (s *Server) Geocode(ctx context.Context, req gen.GeocodeRequestObject) (gen.GeocodeResponseObject, error) {
	if s.weather == nil {
		return nil, problem.New(problem.FeatureDisabled, "weather is turned off on this server")
	}
	if ok, retry := s.geocodeUser.Allow(me(ctx).ID.String()); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	q := strings.TrimSpace(req.Params.Q)
	if len([]rune(q)) < 2 {
		return ok([]weather.Place{}), nil
	}
	lang, count := "en", 8
	if req.Params.Lang != nil {
		lang = *req.Params.Lang
	}
	if req.Params.Count != nil {
		count = *req.Params.Count
	}
	places, err := s.weather.Geocode(ctx, q, lang, count)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		s.log.Warn("geocoding failed", "err", err)
		return nil, problem.New(problem.UpstreamUnavailable, "place search is not available right now")
	}
	return ok(places), nil
}
