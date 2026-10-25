// Package app wires the server together: database, store, realtime hub,
// API, media worker and periodic maintenance.
package app

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/jeroentrappers/kinframe/server/internal/api"
	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/calendar"
	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/releases"
	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/weather"
	"github.com/jeroentrappers/kinframe/server/internal/worker"
	"github.com/jeroentrappers/kinframe/server/internal/ws"
)

type App struct {
	Cfg     *config.Config
	Log     *slog.Logger
	Pool    *pgxpool.Pool
	Store   *store.Store
	Hub     *ws.Hub
	API     *api.Server
	Worker  *worker.Worker
	Handler http.Handler
	// Releases mirrors frame app releases; nil when neither GitHub nor a directory is configured.
	Releases *releases.Mirror
	// Weather fetches Open-Meteo weather for frames; nil when disabled.
	Weather *weather.Service
	// Calendar syncs frames' calendars; nil when disabled.
	Calendar *calendar.Service
}

// New connects to the database, applies migrations and builds the app.
func New(ctx context.Context, cfg *config.Config, log *slog.Logger) (*App, error) {
	pool, err := db.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return nil, err
	}
	if err := db.Migrate(ctx, pool); err != nil {
		pool.Close()
		return nil, fmt.Errorf("migrate: %w", err)
	}
	a, err := NewWithPool(cfg, log, pool)
	if err != nil {
		pool.Close()
	}
	return a, err
}

// NewWithPool builds the app on an existing, migrated pool.
func NewWithPool(cfg *config.Config, log *slog.Logger, pool *pgxpool.Pool) (*App, error) {
	st := store.New(pool)
	hub := ws.NewHub(log)
	tools := media.Tools{Vips: cfg.VipsBin, VipsHeader: cfg.VipsHdr, FFmpeg: cfg.FFmpegBin, FFprobe: cfg.FFprobe}
	if missing := tools.Missing(); len(missing) > 0 {
		log.Warn("media tools not found; uploads cannot be processed", "missing", missing)
	}
	cfg.Blob.Backend = cmp.Or(cfg.Blob.Backend, config.BlobFS)
	cfg.Blob.FSRoot = cmp.Or(cfg.Blob.FSRoot, filepath.Join(cfg.DataDir, "media"))
	openCtx, cancelOpen := context.WithTimeout(context.Background(), 30*time.Second)
	store, err := cfg.Blob.Open(openCtx)
	cancelOpen()
	if err != nil {
		return nil, fmt.Errorf("blob store (%s): %w", cfg.Blob.Backend, err)
	}
	// Scratch space for the media pipeline and avatar processing. Next to
	// the fs store (same volume), so finished files are renamed into place.
	scratch := filepath.Join(cfg.DataDir, "tmp")
	if err := os.MkdirAll(scratch, 0o750); err != nil {
		return nil, err
	}
	log.Info("blob store", "backend", store.Backend(), "media_redirect", cmp.Or(cfg.Blob.MediaRedirect, "off"))

	verifiers := auth.MultiVerifier{}
	if cfg.OIDCIssuer != "" {
		verifiers[cfg.OIDCIssuer] = &auth.OIDCVerifier{Issuer: cfg.OIDCIssuer, Audience: cfg.OIDCAudience, JWKSURL: cfg.OIDCJWKSURL}
	}
	var dev *auth.DevTokens
	if cfg.DevAuth {
		dev = auth.NewDevTokens(cfg.SigningKey, cfg.PublicURL+"/dev", cfg.OIDCAudience)
		verifiers[dev.Issuer] = dev
		log.Warn("DEV AUTH ENABLED: anyone can sign in as anyone via POST /dev/token")
	}

	n := &notify.Notifier{Hub: hub, Store: st, Log: log}
	if cfg.PushEnabled {
		senders := notify.MultiSender{"unifiedpush": notify.HTTPSender{}}
		if cfg.VAPIDPublicKey != "" {
			senders["webpush"] = notify.WebPushSender{PublicKey: cfg.VAPIDPublicKey, PrivateKey: cfg.VAPIDPrivateKey, Subject: cfg.VAPIDSubject}
		}
		n.Sender = senders
	}
	var mirror *releases.Mirror
	if cfg.FrameReleasesRepo != "" || cfg.FrameReleasesDir != "" {
		mirror = &releases.Mirror{
			CacheDir: filepath.Join(cfg.DataDir, "frame-releases"), Repo: cfg.FrameReleasesRepo,
			APIBase: cfg.FrameReleasesAPI, Token: cfg.FrameReleasesToken, DropDir: cfg.FrameReleasesDir,
			Interval: cfg.FrameReleasesInterval, Log: log.With("component", "releases"),
		}
		if err := mirror.Load(); err != nil {
			return nil, fmt.Errorf("frame releases: %w", err)
		}
	}
	var wx *weather.Service
	if cfg.WeatherURL != "" {
		wx = &weather.Service{
			Store: st, Log: log.With("component", "weather"), Refresh: cfg.WeatherRefresh, Jitter: 5 * time.Minute,
			Client: &weather.Client{
				ForecastURL: cfg.WeatherURL, GeocodingURL: cfg.GeocodingURL,
				UserAgent: "kinframe/" + cfg.Version + " (+https://github.com/jeroentrappers/kinframe)",
				HTTP:      &http.Client{Timeout: 15 * time.Second},
			},
		}
	}
	var cal *calendar.Service
	if cfg.CalendarEnabled {
		sealer, err := calendar.NewSealer(cfg.SecretsKey, cfg.SigningKey)
		if err != nil {
			return nil, fmt.Errorf("calendar secrets: %w", err)
		}
		cal = &calendar.Service{
			Store: st, Sealer: sealer, Log: log.With("component", "calendar"),
			Interval: cfg.CalendarSync, Jitter: 3 * time.Minute,
			Fetcher: &calendar.Fetcher{
				UserAgent: "kinframe/" + cfg.Version + " (+https://github.com/jeroentrappers/kinframe)",
				HTTP:      &http.Client{Timeout: 30 * time.Second},
			},
		}
	}
	srv, err := api.New(api.Deps{Config: cfg, Store: st, Hub: hub, Notifier: n, Blob: store, Scratch: scratch, Tools: tools, Log: log, Users: verifiers, Dev: dev, Releases: mirror, Weather: wx, Calendar: cal})
	if err != nil {
		return nil, err
	}
	h, err := srv.Handler()
	if err != nil {
		return nil, err
	}
	w := &worker.Worker{
		Store: st, Blob: store, Scratch: scratch, Tools: tools, Log: log.With("component", "worker"), Events: srv, Workers: cfg.Workers,
		MaxVideoMS:   func(ctx context.Context) int { return srv.Limits(ctx).MaxVideoSeconds * 1000 },
		RemoveSource: srv.RemoveUpload,
	}
	st.OnFeed = srv.FeedChanged
	st.OnJob = w.Wake
	hub.OnDevice = srv.DeviceConnection
	hub.OnNowShowing = srv.NowShowing
	return &App{Cfg: cfg, Log: log, Pool: pool, Store: st, Hub: hub, API: srv, Worker: w, Handler: h, Releases: mirror, Weather: wx, Calendar: cal}, nil
}

// RunBackground starts the media workers and the maintenance loop.
func (a *App) RunBackground(ctx context.Context) *sync.WaitGroup {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); a.Worker.Run(ctx) }()
	go func() { defer wg.Done(); a.maintain(ctx) }()
	if a.Releases != nil {
		wg.Add(1)
		go func() { defer wg.Done(); a.Releases.Run(ctx) }()
	}
	if a.Weather != nil {
		wg.Add(1)
		go func() { defer wg.Done(); a.Weather.Run(ctx) }()
	}
	if a.Calendar != nil {
		wg.Add(1)
		go func() { defer wg.Done(); a.Calendar.Run(ctx) }()
	}
	return &wg
}

// Serve runs the HTTP server on l until ctx is cancelled, then shuts down gracefully.
func (a *App) Serve(ctx context.Context, l net.Listener) error {
	srv := &http.Server{
		Handler:           a.Handler,
		ReadHeaderTimeout: 15 * time.Second,
		IdleTimeout:       120 * time.Second,
		BaseContext:       func(net.Listener) context.Context { return ctx },
	}
	bg, cancelBg := context.WithCancel(context.Background())
	wg := a.RunBackground(bg)
	errc := make(chan error, 1)
	go func() { errc <- srv.Serve(l) }()
	a.Log.Info("kinframe server listening", "addr", l.Addr().String(), "public_url", a.Cfg.PublicURL, "env", a.Cfg.Env)
	select {
	case err := <-errc:
		cancelBg()
		wg.Wait()
		return err
	case <-ctx.Done():
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	err := srv.Shutdown(shutdown)
	cancelBg()
	wg.Wait()
	a.Pool.Close()
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

// Maintain runs one round of periodic housekeeping.
func (a *App) Maintain(ctx context.Context) {
	// Errors caused by shutdown are not worth logging.
	log := slog.New(&quietOnCancel{Handler: a.Log.Handler(), ctx: ctx})
	limits := a.API.Limits(ctx)
	if n, err := a.Store.PurgeExpiredTrash(ctx, limits.TrashRetentionDays); err != nil {
		log.Error("purge trash", "err", err)
	} else if n > 0 {
		log.Info("purged trashed placements", "count", n)
	}
	if _, err := a.Store.PruneFeed(ctx, a.Cfg.FeedRetention); err != nil {
		log.Error("prune change feed", "err", err)
	}
	if ids, err := a.Store.ExpiredUploads(ctx, a.Cfg.UploadExpiry); err != nil {
		log.Error("expired uploads", "err", err)
	} else {
		for _, id := range ids {
			a.API.RemoveExpiredUpload(filepath.Join(a.Cfg.DataDir, "uploads", id))
			if err := a.Store.DeleteUpload(ctx, id); err != nil {
				log.Error("delete upload", "err", err)
			}
		}
	}
	if a.Cfg.Shop != nil {
		if n, err := a.Store.PurgeReservations(ctx, a.Cfg.Shop.RetentionDays); err != nil {
			log.Error("purge shop reservations", "err", err)
		} else if n > 0 {
			log.Info("deleted old shop reservations", "count", n)
		}
	}
	if err := a.Store.PruneIdempotency(ctx); err != nil {
		log.Error("prune idempotency keys", "err", err)
	}
	// Scratch directories left behind by a crash (normal runs remove their own).
	if n := pruneScratch(filepath.Join(a.Cfg.DataDir, "tmp"), 6*time.Hour); n > 0 {
		log.Info("removed stale scratch files", "count", n)
	}
}

func (a *App) maintain(ctx context.Context) {
	t := time.NewTicker(a.Cfg.JanitorInterval)
	defer t.Stop()
	for {
		a.Maintain(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

type quietOnCancel struct {
	slog.Handler
	ctx context.Context
}

func (q *quietOnCancel) Enabled(c context.Context, l slog.Level) bool {
	return q.ctx.Err() == nil && q.Handler.Enabled(c, l)
}

// pruneScratch removes entries of dir older than age and returns how many.
func pruneScratch(dir string, age time.Duration) int {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0
	}
	n := 0
	for _, e := range entries {
		info, err := e.Info()
		if err != nil || time.Since(info.ModTime()) < age {
			continue
		}
		if os.RemoveAll(filepath.Join(dir, e.Name())) == nil {
			n++
		}
	}
	return n
}
