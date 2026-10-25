package api

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/ws"
)

// TV mirror (README §6.4): a TV follows what the physical frame shows, live.
// Frames report `now_showing` over their device websocket; the latest report per
// frame is kept in memory (it is only meaningful while the frame is connected)
// and fanned out to the TV pages subscribed with a `mirror` TV link.

const (
	// maxMirrorViewers caps TV pages per frame.
	maxMirrorViewers = 8
	// mirrorMinGap coalesces bursts of frame reports (swipes) into one render.
	mirrorMinGap = 250 * time.Millisecond
	// mirrorRecheck is how often a viewer's link and its creator's permissions are re-checked.
	mirrorRecheck = time.Minute
	// mirrorResend refreshes the signed media URLs of an idle viewer well before they expire.
	mirrorResend = 40 * time.Minute
	// mirrorEndedCode closes a viewer whose link stopped working.
	mirrorEndedCode websocket.StatusCode = 4401
)

type mirrorViewer struct {
	v     *ws.Viewer
	token string

	mu        sync.Mutex
	expiresAt time.Time
	lastSent  time.Time
}

type mirrorFrame struct {
	report   *gen.NowShowing
	recvAt   time.Time // server time the report arrived
	viewers  map[*mirrorViewer]struct{}
	timer    *time.Timer
	lastFan  time.Time
	rendered []byte // last fan-out body without expires_at, for new viewers
}

type mirrorHub struct {
	mu     sync.Mutex
	frames map[uuid.UUID]*mirrorFrame
}

func (h *mirrorHub) frame(id uuid.UUID) *mirrorFrame {
	if h.frames == nil {
		h.frames = map[uuid.UUID]*mirrorFrame{}
	}
	f := h.frames[id]
	if f == nil {
		f = &mirrorFrame{viewers: map[*mirrorViewer]struct{}{}}
		h.frames[id] = f
	}
	return f
}

// gc drops a frame's entry once it has neither viewers nor a live report (the
// report of a connected frame is kept, so a TV that tunes in gets it at once).
func (h *mirrorHub) gc(id uuid.UUID, f *mirrorFrame) {
	if len(f.viewers) == 0 && f.report == nil && f.timer == nil {
		delete(h.frames, id)
	}
}

// NowShowing is the hub callback for a frame's `now_showing` message.
func (s *Server) NowShowing(frameID uuid.UUID, raw json.RawMessage) {
	var n gen.NowShowing
	if err := json.Unmarshal(raw, &n); err != nil || n.SentAt.IsZero() {
		return
	}
	if n.PlacementIds != nil && len(*n.PlacementIds) > 6 {
		ids := (*n.PlacementIds)[:6]
		n.PlacementIds = &ids
	}
	h := &s.mirror
	h.mu.Lock()
	f := h.frame(frameID)
	f.report, f.recvAt = &n, time.Now()
	h.mu.Unlock()
	s.mirrorFanOut(frameID)
}

// mirrorOnline records a frame connecting or disconnecting (DeviceConnection).
func (s *Server) mirrorOnline(frameID uuid.UUID, online bool) {
	h := &s.mirror
	h.mu.Lock()
	f := h.frames[frameID]
	if f == nil {
		h.mu.Unlock()
		return
	}
	if !online {
		// A reconnecting frame reports afresh; until then there is nothing to show.
		f.report = nil
	}
	h.mu.Unlock()
	s.mirrorFanOut(frameID)
}

// mirrorFanOut renders the frame's state for its viewers, at most every mirrorMinGap.
func (s *Server) mirrorFanOut(frameID uuid.UUID) {
	h := &s.mirror
	h.mu.Lock()
	defer h.mu.Unlock()
	f := h.frames[frameID]
	if f == nil {
		return
	}
	if len(f.viewers) == 0 {
		f.rendered = nil
		h.gc(frameID, f)
		return
	}
	if f.timer != nil {
		return
	}
	wait := mirrorMinGap - time.Since(f.lastFan)
	if wait < 0 {
		wait = 0
	}
	f.timer = time.AfterFunc(wait, func() {
		h.mu.Lock()
		f.timer = nil
		f.lastFan = time.Now()
		report, recvAt := f.report, f.recvAt
		h.mu.Unlock()
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		state, err := s.renderMirror(ctx, frameID, report, recvAt)
		if err != nil {
			s.log.Warn("render mirror state", "frame", frameID, "err", err)
			return
		}
		h.mu.Lock()
		viewers := make([]*mirrorViewer, 0, len(f.viewers))
		for v := range f.viewers {
			viewers = append(viewers, v)
		}
		h.mu.Unlock()
		for _, v := range viewers {
			v.send(state)
		}
	})
}

func (v *mirrorViewer) send(state *gen.MirrorState) {
	st := *state
	v.mu.Lock()
	st.ExpiresAt = v.expiresAt
	v.lastSent = time.Now()
	v.mu.Unlock()
	b, err := json.Marshal(st)
	if err != nil {
		return
	}
	v.v.Send(b)
}

// mirrorSettingsDoc is the part of the frame settings a mirroring TV needs.
type mirrorSettingsDoc struct {
	Slideshow struct {
		IntervalS    *int    `json:"interval_s"`
		Transition   *string `json:"transition"`
		TransitionMs *int    `json:"transition_ms"`
		Fill         *bool   `json:"fill"`
		Background   *string `json:"background"`
		CaptionDate  *string `json:"caption_date"`
	} `json:"slideshow"`
	Widgets struct {
		Caption  *bool `json:"caption"`
		Clock    *bool `json:"clock"`
		Date     *bool `json:"date"`
		Clock24h *bool `json:"clock_24h"`
	} `json:"widgets"`
	Locale struct {
		Timezone *string `json:"timezone"`
	} `json:"locale"`
}

func or[T any](p *T, def T) T {
	if p == nil {
		return def
	}
	return *p
}

func mirrorSettings(f *store.Frame) *gen.MirrorSettings {
	var d mirrorSettingsDoc
	_ = json.Unmarshal(f.Settings, &d)
	ss, w := d.Slideshow, d.Widgets
	out := &gen.MirrorSettings{
		IntervalS:    or(ss.IntervalS, 30),
		Transition:   gen.MirrorSettingsTransition(or(ss.Transition, "crossfade")),
		TransitionMs: or(ss.TransitionMs, 800),
		Fill:         or(ss.Fill, true),
		Background:   gen.MirrorSettingsBackground(or(ss.Background, "blur")),
		CaptionDate:  ptr(gen.MirrorSettingsCaptionDate(or(ss.CaptionDate, "received"))),
		Caption:      ptr(or(w.Caption, true)),
		Clock:        ptr(or(w.Clock, true)),
		Date:         ptr(or(w.Date, true)),
		Clock24h:     ptr(or(w.Clock24h, true)),
		Timezone:     d.Locale.Timezone,
	}
	return out
}

// renderMirror builds the state every viewer of the frame gets (ExpiresAt is per viewer).
// Times reported by the frame are translated to server time: the server only trusts
// differences between the frame's own timestamps.
func (s *Server) renderMirror(ctx context.Context, frameID uuid.UUID, n *gen.NowShowing, recvAt time.Time) (*gen.MirrorState, error) {
	f, err := s.st.GetFrame(ctx, s.st.Q(), frameID)
	if err != nil {
		return nil, err
	}
	now := time.Now().UTC()
	st := &gen.MirrorState{
		Type: "mirror", ServerTime: now, FrameId: f.ID, FrameName: f.Name, Online: s.hub.FrameOnline(f.ID),
		Screen: "unknown", Settings: mirrorSettings(f),
	}
	if !st.Online || n == nil {
		return st, nil
	}
	st.Screen = gen.MirrorStateScreen(n.Screen)
	toServer := func(t time.Time) time.Time { return recvAt.Add(t.Sub(n.SentAt)).UTC() }

	var ids []uuid.UUID
	if n.PlacementIds != nil {
		ids = append(ids, *n.PlacementIds...)
	}
	if n.PlacementId != nil && (len(ids) == 0 || ids[0] != *n.PlacementId) {
		ids = append([]uuid.UUID{*n.PlacementId}, ids...)
	}
	all := ids
	if n.NextPlacementId != nil {
		all = append(append([]uuid.UUID{}, ids...), *n.NextPlacementId)
	}
	if len(all) == 0 {
		return st, nil
	}
	// The frame only plays visible placements, but a report may race a hide or delete.
	rows, err := s.st.VisiblePlacementsByIDs(ctx, frameID, dedupeIDs(all), nil)
	if err != nil {
		return nil, err
	}
	byID := make(map[uuid.UUID]gen.TvSlide, len(rows))
	for i := range rows {
		if sl, ok := s.tvSlide(&rows[i]); ok {
			byID[rows[i].Placement.ID] = sl
		}
	}
	if n.NextPlacementId != nil {
		if sl, ok := byID[*n.NextPlacementId]; ok {
			st.Next = &sl
		}
	}
	var slides []gen.TvSlide
	for _, id := range ids {
		if sl, ok := byID[id]; ok {
			slides = append(slides, sl)
		}
	}
	if len(slides) == 0 {
		return st, nil
	}
	started := recvAt.UTC()
	if n.StartedAt != nil {
		started = toServer(*n.StartedAt)
	}
	sh := &gen.MirrorShowing{
		PlacementId: slides[0].PlacementId, Slides: slides, StartedAt: started,
		Transition: "crossfade", TransitionMs: or(n.TransitionMs, 800),
		HoldMs: n.HoldMs, KenBurns: n.KenBurns,
	}
	if n.Transition != nil {
		sh.Transition = gen.MirrorShowingTransition(*n.Transition)
	}
	if n.Direction != nil {
		sh.Direction = ptr(int(*n.Direction))
	}
	if n.Video != nil {
		sh.Video = &struct {
			At         time.Time `json:"at"`
			Live       *bool     `json:"live,omitempty"`
			Playing    bool      `json:"playing"`
			PositionMs int       `json:"position_ms"`
		}{At: recvAt.UTC(), Live: n.Video.Live, Playing: n.Video.Playing, PositionMs: n.Video.PositionMs}
	}
	st.Showing = sh
	return st, nil
}

// tvLinkForMirror resolves a token to a live mirror link whose creator may still
// see the whole library, or the problem to answer with.
func (s *Server) tvLinkForMirror(ctx context.Context, token string) (*store.TvLink, error) {
	l, err := s.liveTvLink(ctx, token)
	if err != nil {
		return nil, err
	}
	if l.Purpose != "mirror" {
		return nil, problem.New(problem.NotFound, "this TV link is not valid")
	}
	m, err := s.st.GetMembership(ctx, s.st.Q(), l.FrameID, l.CreatedBy)
	if err == store.ErrNotFound || (err == nil && !m.Effective().SeeLibrary) {
		return nil, problem.New(problem.NotFound, "this TV link is not valid")
	}
	if err != nil {
		return nil, err
	}
	return l, nil
}

// tvMirrorWS is GET /v1/tv/{token}/mirror: the TV page's websocket for a mirror link.
func (s *Server) tvMirrorWS(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	if ok, retry := s.tvIP.Allow(clientIP(ctx)); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		problem.Write(w, r, p)
		return
	}
	token := r.PathValue("token")
	l, err := s.tvLinkForMirror(ctx, token)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	frameID := l.FrameID
	h := &s.mirror
	h.mu.Lock()
	full := h.frames[frameID] != nil && len(h.frames[frameID].viewers) >= maxMirrorViewers
	h.mu.Unlock()
	if full {
		problem.Write(w, r, problem.New(problem.RateLimited, "too many TVs are mirroring this frame"))
		return
	}
	var mv *mirrorViewer
	ws.ServeViewer(w, r, func(v *ws.Viewer) {
		mv = &mirrorViewer{v: v, token: token, expiresAt: l.ExpiresAt}
		h.mu.Lock()
		f := h.frame(frameID)
		f.viewers[mv] = struct{}{}
		report, recvAt := f.report, f.recvAt
		h.mu.Unlock()
		// The first state right away, rendered for this viewer.
		rctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if st, err := s.renderMirror(rctx, frameID, report, recvAt); err == nil {
			mv.send(st)
		}
		go s.watchMirrorViewer(frameID, mv)
	})
	if mv == nil {
		return
	}
	h.mu.Lock()
	if f := h.frames[frameID]; f != nil {
		delete(f.viewers, mv)
		h.gc(frameID, f)
	}
	h.mu.Unlock()
}

// watchMirrorViewer ends a viewer when its link stops working, and refreshes its
// signed URLs while the frame shows the same thing for a long time.
func (s *Server) watchMirrorViewer(frameID uuid.UUID, mv *mirrorViewer) {
	t := time.NewTicker(mirrorRecheck)
	defer t.Stop()
	for {
		select {
		case <-mv.v.Done():
			return
		case <-t.C:
		}
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		l, err := s.tvLinkForMirror(ctx, mv.token)
		if err != nil {
			cancel()
			if p, ok := err.(*problem.Problem); ok {
				reason := "revoked"
				if p.Code == problem.LinkExpired {
					reason = "expired"
				}
				mv.v.Send(marshalJSON(map[string]string{"type": "ended", "reason": reason}))
				mv.v.Close(mirrorEndedCode, "TV link ended")
				return
			}
			continue // a database blip: try again next time
		}
		mv.mu.Lock()
		mv.expiresAt = l.ExpiresAt
		stale := time.Since(mv.lastSent) > mirrorResend
		mv.mu.Unlock()
		if stale {
			s.mirror.mu.Lock()
			f := s.mirror.frames[frameID]
			var report *gen.NowShowing
			var recvAt time.Time
			if f != nil {
				report, recvAt = f.report, f.recvAt
			}
			s.mirror.mu.Unlock()
			if st, err := s.renderMirror(ctx, frameID, report, recvAt); err == nil {
				mv.send(st)
			}
		}
		cancel()
	}
}

// mirrorEndLink closes the viewers of a link that was just revoked.
func (s *Server) mirrorEndLink(frameID uuid.UUID, token string) {
	h := &s.mirror
	h.mu.Lock()
	var ended []*mirrorViewer
	if f := h.frames[frameID]; f != nil {
		for v := range f.viewers {
			if v.token == token {
				ended = append(ended, v)
			}
		}
	}
	h.mu.Unlock()
	for _, v := range ended {
		v.v.Send(marshalJSON(map[string]string{"type": "ended", "reason": "revoked"}))
		v.v.Close(mirrorEndedCode, "TV link ended")
	}
}

func marshalJSON(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}
