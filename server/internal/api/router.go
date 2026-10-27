package api

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers"
	"github.com/getkin/kin-openapi/routers/gorillamux"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Handler builds the complete HTTP handler.
func (s *Server) Handler() (http.Handler, error) {
	strict := gen.NewStrictHandlerWithOptions(s, nil, gen.StrictHTTPServerOptions{
		RequestErrorHandlerFunc: func(w http.ResponseWriter, r *http.Request, err error) {
			problem.Write(w, r, problem.New(problem.ValidationFailed, "malformed request body").WithStatus(http.StatusBadRequest))
		},
		ResponseErrorHandlerFunc: s.writeError,
	})
	inner := http.NewServeMux()
	gen.HandlerWithOptions(strict, gen.StdHTTPServerOptions{
		BaseRouter: inner,
		ErrorHandlerFunc: func(w http.ResponseWriter, r *http.Request, err error) {
			problem.Write(w, r, problem.New(problem.ValidationFailed, "%s", err.Error()).WithStatus(http.StatusBadRequest))
		},
	})
	validator, err := newValidator()
	if err != nil {
		return nil, err
	}
	api := validator.middleware(withProblemNotFound(inner))

	mux := http.NewServeMux()
	mux.Handle("GET /.well-known/kinframe", api)
	mux.Handle("/v1/", s.authenticate(api))

	user := s.userAuth
	mux.Handle("/v1/uploads", user(s.tusRoutes()))
	mux.Handle("/v1/uploads/", user(s.tusRoutes()))
	mux.Handle("PUT /v1/me/avatar", user(http.HandlerFunc(s.putAvatar)))
	mux.Handle("GET /v1/items/{itemId}/media/{variant}", user(http.HandlerFunc(s.getItemMedia)))
	mux.Handle("GET /v1/device/media/{placementId}", s.deviceAuth(http.HandlerFunc(s.deviceGetMedia)))
	mux.Handle("GET /v1/device/ws", s.deviceAuth(http.HandlerFunc(s.deviceWS)))
	mux.Handle("GET /v1/device/update", s.deviceAuth(http.HandlerFunc(s.deviceCheckUpdate)))
	mux.Handle("GET /v1/device/update/apk/{versionCode}", s.deviceAuth(http.HandlerFunc(s.deviceGetUpdateApk)))
	mux.Handle("GET /v1/device/plugins/bundles/{file}", s.deviceAuth(http.HandlerFunc(s.deviceGetPluginBundle)))
	mux.Handle("GET /v1/frame-app/latest", validator.middleware(http.HandlerFunc(s.frameAppLatest)))
	mux.HandleFunc("GET /v1/frame-app/apk/{versionCode}", s.deviceGetUpdateApk)
	mux.Handle("GET /v1/device/invitations/{invitationId}/photo", s.deviceAuth(http.HandlerFunc(s.deviceGetInvitationPhoto)))
	mux.Handle("GET /v1/frames/{frameId}/events/{invitationId}/photo", user(http.HandlerFunc(s.getEventInvitationPhoto)))
	mux.Handle("GET /v1/operator/reservations.csv", user(http.HandlerFunc(s.exportReservations)))
	mux.HandleFunc("GET /v1/ws", s.userWS)
	mux.HandleFunc("GET /v1/tv/{token}/mirror", s.tvMirrorWS)
	mux.HandleFunc("GET /v1/media/signed/{key...}", s.signedMedia)
	mux.HandleFunc("GET /avatars/{user}/{file}", s.avatarFile)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		if err := s.st.Pool.Ping(ctx); err != nil {
			http.Error(w, "database unavailable", http.StatusServiceUnavailable)
			return
		}
		_, _ = w.Write([]byte("ok\n"))
	})
	if s.dev != nil {
		mux.HandleFunc("POST /dev/token", s.devToken)
	}
	// Weather stations upload in the Weather Underground format (plain HTTP, the stations' own protocol).
	mux.HandleFunc("GET /weatherstation/updateweatherstation.php", s.stationUpload)
	mux.Handle("/", s.spa())
	return s.recoverer(s.realIP(s.logRequests(mux))), nil
}

func (s *Server) writeError(w http.ResponseWriter, r *http.Request, err error) {
	p := problem.From(err)
	if p.Status == http.StatusInternalServerError {
		s.log.Error("request failed", "method", r.Method, "path", r.URL.Path, "err", err)
	}
	problem.Write(w, r, p)
}

// withProblemNotFound turns the mux's plain-text 404/405 into problem+json.
func withProblemNotFound(mux *http.ServeMux) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if _, pattern := mux.Handler(r); pattern == "" {
			problem.Write(w, r, problem.New(problem.NotFound, "no such endpoint"))
			return
		}
		mux.ServeHTTP(w, r)
	})
}

// ---------------------------------------------------------------- authentication

func (s *Server) authenticate(next http.Handler) http.Handler {
	user, device := s.userAuth(next), s.deviceAuth(next)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, "/v1/devices/"), tvPublicPath(r), shopPublicPath(r), r.URL.Path == "/v1/stations/latest":
			next.ServeHTTP(w, r)
		case strings.HasPrefix(r.URL.Path, "/v1/device/"):
			device.ServeHTTP(w, r)
		default:
			user.ServeHTTP(w, r)
		}
	})
}

func bearer(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if len(h) > 7 && strings.EqualFold(h[:7], "bearer ") {
		return strings.TrimSpace(h[7:])
	}
	return ""
}

func authProblem(err error) *problem.Problem {
	if errors.Is(err, auth.ErrExpired) {
		return problem.New(problem.TokenExpired, "")
	}
	return problem.New(problem.Unauthorized, "invalid access token")
}

// resolveUser validates a token and loads (or creates) the user.
func (s *Server) resolveUser(ctx context.Context, token string) (*principal, *problem.Problem) {
	if token == "" {
		return nil, problem.New(problem.Unauthorized, "missing access token")
	}
	claims, err := s.users.Verify(ctx, token)
	if err != nil {
		if !errors.Is(err, auth.ErrInvalid) && !errors.Is(err, auth.ErrExpired) {
			s.log.Warn("token verification unavailable", "err", err)
		}
		return nil, authProblem(err)
	}
	u, err := s.st.UpsertUserBySub(ctx, claims.Subject, claims.Email, claims.Name)
	if err != nil {
		p := problem.Internal(err)
		return nil, p
	}
	return &principal{user: u, claims: claims}, nil
}

func (s *Server) userAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p, prob := s.resolveUser(r.Context(), bearer(r))
		if prob != nil {
			s.writeError(w, r, prob)
			return
		}
		next.ServeHTTP(w, r.WithContext(withUser(r.Context(), p)))
	})
}

// deviceVersionExempt lists device calls allowed even when the app is too old,
// so an outdated frame can still report health, fetch its state and update itself.
var deviceVersionExempt = map[string]bool{"/v1/device/heartbeat": true, "/v1/device/state": true, "/v1/device/update": true}

func versionExempt(path string) bool {
	return deviceVersionExempt[path] || strings.HasPrefix(path, "/v1/device/update/apk/")
}

// deviceQueryTokenPath reports whether a device call may carry its token in the
// query string (`access_token`): a browser frame cannot set headers on a
// websocket or on the <img>/<video> elements that load its media.
func deviceQueryTokenPath(r *http.Request) bool {
	return r.Method == http.MethodGet && (r.URL.Path == "/v1/device/ws" || strings.HasPrefix(r.URL.Path, "/v1/device/media/"))
}

func (s *Server) deviceAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := bearer(r)
		if token == "" && deviceQueryTokenPath(r) {
			token = r.URL.Query().Get("access_token")
		}
		if token == "" {
			problem.Write(w, r, problem.New(problem.Unauthorized, "missing device token"))
			return
		}
		id, err := s.devices.Verify(token)
		if err != nil {
			problem.Write(w, r, authProblem(err))
			return
		}
		d, err := s.st.GetDevice(r.Context(), s.st.Q(), id)
		if err == store.ErrNotFound || (err == nil && d.RevokedAt != nil) {
			problem.Write(w, r, problem.New(problem.DeviceRevoked, ""))
			return
		}
		if err != nil {
			s.writeError(w, r, err)
			return
		}
		if s.cfg.MinFrameAppVersion > 0 && !d.IsBrowser() && d.AppVersionCode < s.cfg.MinFrameAppVersion && !versionExempt(r.URL.Path) {
			problem.Write(w, r, problem.New(problem.AppUpdateRequired, ""))
			return
		}
		_ = s.st.TouchDevice(r.Context(), d.ID)
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), deviceKey, d)))
	})
}

// ---------------------------------------------------------------- request validation

type validator struct{ router routers.Router }

func newValidator() (*validator, error) {
	loader := openapi3.NewLoader()
	doc, err := loader.LoadFromData(gen.PreparedSpec)
	if err != nil {
		return nil, fmt.Errorf("load spec: %w", err)
	}
	doc.Servers = nil // match any host
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		return nil, fmt.Errorf("spec router: %w", err)
	}
	return &validator{router: router}, nil
}

func isJSON(ct string) bool {
	mt, _, _ := mime.ParseMediaType(ct)
	return mt == "application/json" || strings.HasSuffix(mt, "+json")
}

const maxJSONBody = 2 << 20

func (v *validator) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		route, params, err := v.router.FindRoute(r)
		if err != nil {
			next.ServeHTTP(w, r) // unknown routes are answered by the mux
			return
		}
		ct := r.Header.Get("Content-Type")
		jsonBody := isJSON(ct)
		if jsonBody && r.Body != nil {
			raw, err := io.ReadAll(io.LimitReader(r.Body, maxJSONBody+1))
			if err != nil {
				problem.Write(w, r, problem.New(problem.ValidationFailed, "could not read body").WithStatus(http.StatusBadRequest))
				return
			}
			if len(raw) > maxJSONBody {
				problem.Write(w, r, problem.New(problem.ValidationFailed, "body too large").WithStatus(http.StatusRequestEntityTooLarge))
				return
			}
			r.Body = io.NopCloser(bytes.NewReader(raw))
			r = r.WithContext(context.WithValue(r.Context(), rawBodyKey, raw))
			// Merge-patch endpoints also accept plain application/json.
			if mt, _, _ := mime.ParseMediaType(ct); mt == "application/json" && route.Operation.RequestBody != nil &&
				route.Operation.RequestBody.Value.Content.Get("application/json") == nil &&
				route.Operation.RequestBody.Value.Content.Get("application/merge-patch+json") != nil {
				r.Header.Set("Content-Type", "application/merge-patch+json")
			}
		}
		in := &openapi3filter.RequestValidationInput{
			Request: r, PathParams: params, Route: route,
			Options: &openapi3filter.Options{
				AuthenticationFunc: openapi3filter.NoopAuthenticationFunc,
				// Binary bodies (imports) are streamed, never buffered for validation.
				ExcludeRequestBody: !jsonBody && route.Operation.RequestBody != nil && route.Operation.RequestBody.Value.Content.Get("application/json") == nil && ct != "",
			},
		}
		if err := openapi3filter.ValidateRequest(r.Context(), in); err != nil {
			problem.Write(w, r, validationProblem(err))
			return
		}
		next.ServeHTTP(w, r)
	})
}

func validationProblem(err error) *problem.Problem {
	var re *openapi3filter.RequestError
	if !errors.As(err, &re) {
		return problem.New(problem.ValidationFailed, "%s", err.Error())
	}
	field, code := "", "invalid"
	if re.Parameter != nil {
		field = re.Parameter.Name
	} else if re.RequestBody != nil {
		field = "body"
	}
	var se *openapi3.SchemaError
	if errors.As(re.Err, &se) {
		if ptr := se.JSONPointer(); len(ptr) > 0 {
			field = strings.Join(ptr, ".")
		}
		code = se.SchemaField
	}
	// Malformed requests (bad parameters, unparsable bodies) are 400;
	// well-formed bodies with invalid fields are 422.
	status := http.StatusUnprocessableEntity
	var pe *openapi3filter.ParseError
	if re.Parameter != nil || errors.As(re.Err, &pe) || (re.RequestBody != nil && se == nil) {
		status = http.StatusBadRequest
	}
	reason := re.Reason
	if se != nil {
		reason = se.Reason
	}
	if reason == "" {
		reason = re.Error()
	}
	return problem.New(problem.ValidationFailed, "%s", reason).WithStatus(status).WithField(field, code)
}

// ---------------------------------------------------------------- plumbing middleware

type statusWriter struct {
	http.ResponseWriter
	status int
	bytes  int64
}

func (w *statusWriter) WriteHeader(code int) {
	if w.status == 0 {
		w.status = code
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	n, err := w.ResponseWriter.Write(b)
	w.bytes += int64(n)
	return n, err
}

func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *statusWriter) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func (w *statusWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	if h, ok := w.ResponseWriter.(http.Hijacker); ok {
		if w.status == 0 {
			w.status = http.StatusSwitchingProtocols
		}
		return h.Hijack()
	}
	return nil, nil, errors.New("hijack not supported")
}

func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w}
		next.ServeHTTP(sw, r)
		s.log.Debug("http", "method", r.Method, "path", r.URL.Path, "status", sw.status, "bytes", sw.bytes,
			"took", time.Since(start).Round(time.Microsecond), "ip", clientIP(r.Context()))
	})
}

func (s *Server) recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				if v == http.ErrAbortHandler {
					panic(v)
				}
				s.log.Error("panic", "path", r.URL.Path, "panic", v)
				problem.Write(w, r, problem.New(problem.ServerError, ""))
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func (s *Server) realIP(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			ip = r.RemoteAddr
		}
		if s.cfg.TrustProxy {
			if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
				first, _, _ := strings.Cut(xff, ",")
				ip = strings.TrimSpace(first)
			} else if xr := r.Header.Get("X-Real-IP"); xr != "" {
				ip = strings.TrimSpace(xr)
			}
		}
		host := r.Host
		if s.cfg.TrustProxy {
			if xh := r.Header.Get("X-Forwarded-Host"); xh != "" {
				first, _, _ := strings.Cut(xh, ",")
				host = strings.TrimSpace(first)
			}
		}
		ctx := context.WithValue(r.Context(), ipKey, ip)
		next.ServeHTTP(w, r.WithContext(context.WithValue(ctx, hostKey, host)))
	})
}

// ---------------------------------------------------------------- web SPA

// spa serves the built web app; unknown non-API paths (including /f/{code}
// and /i/{token}) get index.html so the client router can handle them.
func (s *Server) spa() http.Handler {
	if s.cfg.WebDir == "" {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/" {
				_, _ = w.Write([]byte("kinframe server " + s.cfg.Version + "\n"))
				return
			}
			problem.Write(w, r, problem.New(problem.NotFound, ""))
		})
	}
	root := http.Dir(s.cfg.WebDir)
	files := http.FileServer(root)
	index := filepath.Join(s.cfg.WebDir, "index.html")
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			problem.Write(w, r, problem.New(problem.NotFound, ""))
			return
		}
		p := filepath.Clean("/" + r.URL.Path)
		if p != "/" {
			if f, err := root.Open(p); err == nil {
				st, err := f.Stat()
				f.Close()
				if err == nil && !st.IsDir() {
					if strings.HasPrefix(p, "/_app/immutable/") {
						w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
					}
					if strings.HasPrefix(p, "/fonts/") {
						// Logto's sign-in page (another origin) loads the
						// display font from here; browsers need CORS for fonts.
						w.Header().Set("Access-Control-Allow-Origin", "*")
						w.Header().Set("Cache-Control", "public, max-age=604800")
					}
					files.ServeHTTP(w, r)
					return
				}
			}
		}
		w.Header().Set("Cache-Control", "no-cache")
		if _, err := os.Stat(index); err != nil {
			problem.Write(w, r, problem.New(problem.NotFound, "web app not built"))
			return
		}
		if host := requestHost(r.Context()); s.cfg.Shop.IsStorefront(host) {
			// A storefront domain: the brand's title, description and link preview.
			b, err := os.ReadFile(index)
			if err != nil {
				s.writeError(w, r, err)
				return
			}
			lang := s.cfg.Shop.StorefrontLang(r.Header.Get("Accept-Language"))
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Header().Set("Vary", "Accept-Language")
			_, _ = w.Write(s.cfg.Shop.StorefrontIndex(b, s.shopOrigin(r.Context()), lang))
			return
		}
		http.ServeFile(w, r, index)
	})
}
