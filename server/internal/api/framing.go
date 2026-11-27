package api

import (
	"encoding/json"
	"math"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
)

// Framing: zoom and position per screen orientation (docs/spec/framing.md).

const maxFramingZoom = 4

// framingJSON validates and rounds a framing for storage. A framing without views is nil (none).
func framingJSON(f *gen.Framing) (json.RawMessage, error) {
	raw, msg := framingNorm(f)
	if msg != "" {
		return nil, problem.Validation("framing", "range", msg)
	}
	return raw, nil
}

// framingNorm is framingJSON with the problem as text ("" when valid).
func framingNorm(f *gen.Framing) (json.RawMessage, string) {
	if f == nil {
		return nil, ""
	}
	out := gen.Framing{}
	for _, v := range []struct {
		in  *gen.FramingView
		out **gen.FramingView
	}{{f.Landscape, &out.Landscape}, {f.Portrait, &out.Portrait}} {
		if v.in == nil {
			continue
		}
		x, y, z := float64(v.in.X), float64(v.in.Y), float64(v.in.Zoom)
		if !(x >= 0 && x <= 1 && y >= 0 && y <= 1) {
			return nil, "x and y must be between 0 and 1"
		}
		if !(z >= 1 && z <= maxFramingZoom) {
			return nil, "zoom must be between 1 and 4"
		}
		*v.out = &gen.FramingView{X: round3(x), Y: round3(y), Zoom: round3(z)}
	}
	if out.Landscape == nil && out.Portrait == nil {
		return nil, ""
	}
	raw, _ := json.Marshal(out)
	return raw, ""
}

// framingOut is a stored framing as the API shows it; nil for none or unreadable JSON.
func framingOut(raw json.RawMessage) *gen.Framing {
	if len(raw) == 0 || string(raw) == "null" {
		return nil
	}
	var f gen.Framing
	if json.Unmarshal(raw, &f) != nil || (f.Landscape == nil && f.Portrait == nil) {
		return nil
	}
	return &f
}

// framingCentre is the focal point a framing implies (§2.1): the landscape view's centre, else the portrait one's.
func framingCentre(raw json.RawMessage) (x, y float64, ok bool) {
	f := framingOut(raw)
	if f == nil {
		return 0, 0, false
	}
	v := f.Landscape
	if v == nil {
		v = f.Portrait
	}
	return float64(v.X), float64(v.Y), true
}

func round3(v float64) float32 { return float32(math.Round(v*1000) / 1000) }
