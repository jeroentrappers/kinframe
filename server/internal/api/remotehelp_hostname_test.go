package api

import (
	"encoding/json"
	"testing"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

func TestRemoteHelpHostname(t *testing.T) {
	id := uuid.MustParse("01a16412-baaa-70b7-9d5e-aadbc1dcfaa9")
	for _, c := range []struct {
		name         string
		hardware, hb string
		want         string
	}{
		{"device id", `{}`, ``, "kinframe-c1dcfaa9"},
		{"enrolment serial", `{"serial":"ABC_123"}`, `{"serial":"999"}`, "kinframe-abc-123"},
		{"heartbeat serial", `{"model":"Frame"}`, `{"serial":"36339301"}`, "kinframe-36339301"},
		{"unknown", `{"serial":"unknown"}`, `{"serial":"unknown"}`, "kinframe-c1dcfaa9"},
	} {
		d := &store.Device{ID: id, Hardware: json.RawMessage(c.hardware)}
		if c.hb != "" {
			d.LastHeartbeat = json.RawMessage(c.hb)
		}
		if got := remoteHelpHostname(d); got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
}
