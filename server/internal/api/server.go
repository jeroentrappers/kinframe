// Package api implements the kinframe HTTP API (docs/api/openapi.yaml and
// docs/api/README.md): generated strict handlers plus hand-written tus,
// websocket and media endpoints.
package api

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/tus/tusd/v2/pkg/handler"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/blob"
	"github.com/jeroentrappers/kinframe/server/internal/calendar"
	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/ratelimit"
	"github.com/jeroentrappers/kinframe/server/internal/releases"
	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/weather"
	"github.com/jeroentrappers/kinframe/server/internal/ws"
)

// Server implements gen.StrictServerInterface and the hand-written routes.
type Server struct {
	cfg     *config.Config
	st      *store.Store
	hub     *ws.Hub
	notify  *notify.Notifier
	blob    blob.Store
	scratch string // local scratch directory (avatar processing)
	tools   media.Tools
	log     *slog.Logger
	users   auth.UserVerifier
	dev     *auth.DevTokens
	devices *auth.DeviceTokens
	signer  *auth.URLSigner

	tus       *handler.UnroutedHandler
	uploadDir string

	redeemUser       *ratelimit.Limiter
	redeemIP         *ratelimit.Limiter
	enrollIP         *ratelimit.Limiter
	tokenIP          *ratelimit.Limiter
	devTokenIP       *ratelimit.Limiter
	inviteUser       *ratelimit.Limiter
	geocodeUser      *ratelimit.Limiter
	calendarTestUser *ratelimit.Limiter
	tvUser           *ratelimit.Limiter
	tvIP             *ratelimit.Limiter

	limitsMu sync.Mutex
	limits   *config.Limits

	releases *releases.Mirror  // nil: self-update disabled (always 204)
	weather  *weather.Service  // nil: weather disabled
	calendar *calendar.Service // nil: calendars disabled
	sealer   *calendar.Sealer
}

// Deps are the collaborators a Server needs.
type Deps struct {
	Config   *config.Config
	Store    *store.Store
	Hub      *ws.Hub
	Notifier *notify.Notifier
	Blob     blob.Store
	Scratch  string // local scratch directory; defaults to the OS temp dir
	Tools    media.Tools
	Log      *slog.Logger
	Users    auth.UserVerifier
	Dev      *auth.DevTokens   // nil unless dev auth is enabled
	Releases *releases.Mirror  // frame app releases; nil disables self-update
	Weather  *weather.Service  // nil disables weather and /v1/geocode
	Calendar *calendar.Service // nil disables calendars
}

func New(d Deps) (*Server, error) {
	s := &Server{
		cfg:              d.Config,
		st:               d.Store,
		hub:              d.Hub,
		notify:           d.Notifier,
		blob:             d.Blob,
		scratch:          d.Scratch,
		tools:            d.Tools,
		log:              d.Log,
		users:            d.Users,
		dev:              d.Dev,
		releases:         d.Releases,
		weather:          d.Weather,
		calendar:         d.Calendar,
		devices:          auth.NewDeviceTokens(d.Config.SigningKey, d.Config.PublicURL),
		signer:           auth.NewURLSigner(d.Config.SigningKey),
		redeemUser:       ratelimit.New(10, time.Hour),
		redeemIP:         ratelimit.New(30, time.Hour),
		enrollIP:         ratelimit.New(20, time.Hour),
		tokenIP:          ratelimit.New(120, time.Hour),
		devTokenIP:       ratelimit.New(120, time.Hour),
		inviteUser:       ratelimit.New(60, time.Hour),
		geocodeUser:      ratelimit.New(60, time.Minute),
		calendarTestUser: ratelimit.New(30, time.Minute),
		tvUser:           ratelimit.New(60, time.Hour),
		tvIP:             ratelimit.New(600, time.Hour),
	}
	if d.Calendar != nil {
		s.sealer = d.Calendar.Sealer
	}
	if err := s.setupTus(); err != nil {
		return nil, err
	}
	return s, nil
}

var _ gen.StrictServerInterface = (*Server)(nil)

// Limits returns the operator-adjustable limits (DB value over config defaults).
func (s *Server) Limits(ctx context.Context) config.Limits {
	s.limitsMu.Lock()
	defer s.limitsMu.Unlock()
	if s.limits != nil {
		return *s.limits
	}
	l := s.cfg.DefaultLimits
	if ok, err := s.st.GetServerSetting(ctx, "limits", &l); err != nil {
		s.log.Warn("load limits", "err", err)
		return s.cfg.DefaultLimits
	} else if !ok {
		l = s.cfg.DefaultLimits
	}
	s.limits = &l
	return l
}

func (s *Server) setLimits(ctx context.Context, l config.Limits) error {
	if err := s.st.PutServerSetting(ctx, "limits", l); err != nil {
		return err
	}
	s.limitsMu.Lock()
	s.limits = &l
	s.limitsMu.Unlock()
	return nil
}

// Response is a generic strict-server response: it satisfies every generated
// response interface (see response.gen.go).
type Response struct {
	Status int
	Body   any
	Header http.Header
}

func (r Response) write(w http.ResponseWriter) error {
	for k, vs := range r.Header {
		for _, v := range vs {
			w.Header().Add(k, v)
		}
	}
	if r.Body == nil {
		w.WriteHeader(r.Status)
		return nil
	}
	if raw, ok := r.Body.(json.RawMessage); ok {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(r.Status)
		_, err := w.Write(raw)
		return err
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(r.Status)
	return json.NewEncoder(w).Encode(r.Body)
}

func ok(body any) Response       { return Response{Status: http.StatusOK, Body: body} }
func created(body any) Response  { return Response{Status: http.StatusCreated, Body: body} }
func accepted(body any) Response { return Response{Status: http.StatusAccepted, Body: body} }
func noContent() Response        { return Response{Status: http.StatusNoContent} }

// ---------------------------------------------------------------- request context

type ctxKey int

const (
	userKey ctxKey = iota
	deviceKey
	rawBodyKey
	ipKey
)

type principal struct {
	user   *store.User
	claims auth.UserClaims
}

func withUser(ctx context.Context, p *principal) context.Context {
	return context.WithValue(ctx, userKey, p)
}

func userFrom(ctx context.Context) *principal {
	p, _ := ctx.Value(userKey).(*principal)
	return p
}

// me returns the signed-in user; handlers behind user auth can rely on it.
func me(ctx context.Context) *store.User { return userFrom(ctx).user }

func isOperator(ctx context.Context) bool {
	p := userFrom(ctx)
	return p != nil && p.claims.HasScope(auth.OperatorScope)
}

func deviceFrom(ctx context.Context) *store.Device {
	d, _ := ctx.Value(deviceKey).(*store.Device)
	return d
}

func clientIP(ctx context.Context) string {
	ip, _ := ctx.Value(ipKey).(string)
	return ip
}

// rawField inspects the raw JSON body of the current request: present reports
// whether key exists at the top level, null whether its value is JSON null.
// Generated request structs cannot distinguish "absent" from "null".
func rawField(ctx context.Context, key string) (present, null bool) {
	raw, _ := ctx.Value(rawBodyKey).([]byte)
	var m map[string]json.RawMessage
	if json.Unmarshal(raw, &m) != nil {
		return false, false
	}
	v, ok := m[key]
	return ok, ok && string(v) == "null"
}

// ---------------------------------------------------------------- permission helpers

// membership returns the caller's membership, or 404 (not 403) so frame IDs don't leak.
func (s *Server) membership(ctx context.Context, frameID uuid.UUID) (*store.Membership, error) {
	m, err := s.st.GetMembership(ctx, s.st.Q(), frameID, me(ctx).ID)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "frame not found")
	}
	return m, err
}

func requirePerm(m *store.Membership, allowed bool, name string) error {
	if !allowed {
		return problem.New(problem.MissingPermission, "requires the %s permission", name)
	}
	return nil
}

func requireManager(m *store.Membership) error {
	if !m.IsManager() {
		return problem.New(problem.Forbidden, "owner or admin only")
	}
	return nil
}

func requireOwner(m *store.Membership) error {
	if m.Role != "owner" {
		return problem.New(problem.Forbidden, "owner only")
	}
	return nil
}

// deviceFrame returns the frame of the calling device, or 403 when unclaimed.
func (s *Server) deviceFrame(ctx context.Context) (*store.Frame, error) {
	d := deviceFrom(ctx)
	if d.FrameID == nil {
		return nil, problem.New(problem.Forbidden, "this frame has not been claimed yet")
	}
	f, err := s.st.GetFrame(ctx, s.st.Q(), *d.FrameID)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.DeviceRevoked, "")
	}
	return f, err
}

func notFound(err error, what string) error {
	if err != store.ErrNotFound {
		return err
	}
	// Literal details, so each one has its own translation (package i18n).
	switch what {
	case "album":
		return problem.New(problem.NotFound, "album not found")
	case "frame":
		return problem.New(problem.NotFound, "frame not found")
	case "invite":
		return problem.New(problem.NotFound, "invite not found")
	case "item":
		return problem.New(problem.NotFound, "item not found")
	case "join request":
		return problem.New(problem.NotFound, "join request not found")
	case "member":
		return problem.New(problem.NotFound, "member not found")
	case "placement":
		return problem.New(problem.NotFound, "placement not found")
	case "send":
		return problem.New(problem.NotFound, "send not found")
	}
	return problem.New(problem.NotFound, "")
}
