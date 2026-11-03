package api

import (
	"context"
	"net/http"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
)

// deviceWS is GET /v1/device/ws (README §6.1).
func (s *Server) deviceWS(w http.ResponseWriter, r *http.Request) {
	d := deviceFrom(r.Context())
	var latest int64
	if d.FrameID != nil {
		if f, err := s.st.GetFrame(r.Context(), s.st.Q(), *d.FrameID); err == nil {
			latest = f.LatestSeq
		}
	}
	hello := map[string]any{"type": "hello", "server_time": time.Now().UTC().Format(time.RFC3339), "latest_seq": latest}
	s.hub.ServeDevice(w, r, d.ID, d.FrameID, hello)
}

// userWS is GET /v1/ws?access_token=… (README §6.2): browsers cannot set
// headers on websockets, so the token travels in the query string.
func (s *Server) userWS(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("access_token")
	if token == "" {
		token = bearer(r)
	}
	p, prob := s.resolveUser(r.Context(), token)
	if prob != nil {
		problem.Write(w, r, prob)
		return
	}
	expires := p.claims.ExpiresAt
	if expires.IsZero() {
		expires = time.Now().Add(time.Hour)
	}
	s.hub.ServeUser(w, r, p.user.ID, expires)
}

// DeviceConnection is the hub callback for device connect/disconnect: it
// records last-seen and tells the frame's members.
func (s *Server) DeviceConnection(deviceID uuid.UUID, frameID *uuid.UUID, online bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := s.st.SetDeviceSeen(ctx, deviceID); err != nil {
		s.log.Warn("record device seen", "err", err)
	}
	if frameID == nil {
		return
	}
	s.mirrorOnline(*frameID, online)
	members, err := s.st.MemberIDs(ctx, *frameID, false)
	if err != nil {
		s.log.Warn("frame members", "err", err)
		return
	}
	ev := notify.Event{"type": "frame.status", "frame_id": *frameID, "online": online, "last_seen_at": time.Now().UTC().Truncate(time.Second)}
	s.notify.Users(members, ev, nil)
}

// FeedChanged is the store callback after a frame's feed advanced: hint the frame.
func (s *Server) FeedChanged(frameID uuid.UUID, seq int64) {
	s.hub.SendToFrame(frameID, map[string]any{"type": "changes", "latest_seq": seq})
}
