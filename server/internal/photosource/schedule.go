package photosource

import (
	"context"
	"log/slog"
	"math/rand/v2"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/crash"
)

// Scheduling rules (§6.1).
const (
	MinInterval    = 10 * time.Minute
	ICloudInterval = time.Hour // unofficial endpoints: never more often
	Jitter         = 5 * time.Minute
	CatchUp        = 2 * time.Minute // next run while a backlog remains
	MaxBackoff     = 6 * time.Hour
	ProbeInterval  = 24 * time.Hour // needs_attention probes
	RunBudget      = 15 * time.Minute
	ManualEvery    = 2 * time.Minute // sync now, per source

	// Feed debounce (decision 2): new items wait for their post until the
	// upstream album has been quiet for FeedQuiet, or FeedMaxHold after the
	// first held item.
	FeedQuiet   = 30 * time.Minute
	FeedMaxHold = 3 * time.Hour

	// Errors become `error` after ErrorRuns failures in a row or ErrorAfter.
	ErrorRuns  = 3
	ErrorAfter = 6 * time.Hour
	// An `error` source pushes its owner after ErrorPushAfter (§10).
	ErrorPushAfter = 72 * time.Hour
	// At most one attention push per source and code per AttentionEvery.
	AttentionEvery = 7 * 24 * time.Hour

	// Mass-removal guard: more than MassFraction of the live items and more than MassMin.
	MassMin = 20
)

// Interval is the regular interval of a provider (iCloud at least an hour).
func Interval(provider string, base time.Duration) time.Duration {
	if base < MinInterval {
		base = MinInterval
	}
	if provider == ICloudLink && base < ICloudInterval {
		base = ICloudInterval
	}
	return base
}

// NextRegular is the next scheduled run after a successful one, with jitter.
func NextRegular(provider string, base time.Duration, now time.Time) time.Time {
	return now.Add(Interval(provider, base) + rand.N(Jitter))
}

// Backoff after n consecutive failures: 1 minute doubling, at most 6 hours.
func Backoff(n int) time.Duration {
	d := time.Minute
	for i := 1; i < n && d < MaxBackoff; i++ {
		d *= 2
	}
	return min(d, MaxBackoff)
}

// MassRemoval reports whether removing n of live items needs confirmation.
func MassRemoval(n, live int) bool {
	return n > MassMin && n*2 > live
}

// FlushAt is when held feed items become a post: FeedQuiet after the last
// new item, at the latest FeedMaxHold after the first held one.
func FlushAt(holdSince, lastNew time.Time) time.Time {
	quiet := lastNew.Add(FeedQuiet)
	latest := holdSince.Add(FeedMaxHold)
	if latest.Before(quiet) {
		return latest
	}
	return quiet
}

// ---------------------------------------------------------------- scheduler

// Due is a source whose next run has come.
type Due struct {
	ID   uuid.UUID
	Host string
}

// Scheduler runs due sources: at most Parallel at once and one per upstream
// host (a family's Immich is a small box). It mirrors the calendar service.
type Scheduler struct {
	// Due lists sources to run now.
	Due func(ctx context.Context, limit int) ([]Due, error)
	// Run syncs one source (the API server's runner).
	Run      func(ctx context.Context, id uuid.UUID)
	Log      *slog.Logger
	Tick     time.Duration // 30 s
	Parallel int           // 4

	mu      sync.Mutex
	cond    *sync.Cond
	running map[uuid.UUID]bool
	hosts   map[string]bool
	active  int
	wg      sync.WaitGroup
	ctx     context.Context
}

func (s *Scheduler) init() {
	if s.running == nil {
		s.running, s.hosts = map[uuid.UUID]bool{}, map[string]bool{}
		s.cond = sync.NewCond(&s.mu)
	}
	if s.Tick <= 0 {
		s.Tick = 30 * time.Second
	}
	if s.Parallel <= 0 {
		s.Parallel = 4
	}
}

// Running reports whether a run of the source is in progress in this process.
func (s *Scheduler) Running(id uuid.UUID) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.init()
	return s.running[id]
}

// Loop runs due sources every Tick until ctx is done.
func (s *Scheduler) Loop(ctx context.Context) {
	s.mu.Lock()
	s.init()
	s.ctx = ctx
	s.mu.Unlock()
	t := time.NewTicker(s.Tick)
	defer t.Stop()
	for {
		s.TickOnce(ctx)
		select {
		case <-ctx.Done():
			s.wg.Wait()
			return
		case <-t.C:
		}
	}
}

// TickOnce starts the due sources that fit the concurrency limits.
func (s *Scheduler) TickOnce(ctx context.Context) {
	s.mu.Lock()
	s.init()
	free := s.Parallel - s.active
	s.mu.Unlock()
	if free <= 0 || s.Due == nil {
		return
	}
	due, err := s.Due(ctx, 50)
	if err != nil {
		if ctx.Err() == nil && s.Log != nil {
			s.Log.Error("photo sources: due", "err", err)
		}
		return
	}
	for _, d := range due {
		s.start(ctx, d, false)
	}
}

// Kick runs a source now in the background (sync now, a new connection),
// waiting for a free slot. A source that is running already is left alone.
func (s *Scheduler) Kick(id uuid.UUID, host string) {
	s.mu.Lock()
	s.init()
	ctx := s.ctx
	s.mu.Unlock()
	if ctx == nil {
		ctx = context.Background()
	}
	s.start(ctx, Due{ID: id, Host: host}, true)
}

// start launches d unless it runs already; wait blocks for a slot instead of skipping.
func (s *Scheduler) start(ctx context.Context, d Due, wait bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running[d.ID] || ctx.Err() != nil {
		return
	}
	if !wait && (s.active >= s.Parallel || s.hosts[d.Host]) {
		return
	}
	s.running[d.ID] = true
	s.wg.Add(1)
	go func() {
		defer crash.Recover("photo-sources")
		defer s.wg.Done()
		s.mu.Lock()
		for s.active >= s.Parallel || s.hosts[d.Host] {
			s.cond.Wait()
		}
		s.active++
		s.hosts[d.Host] = true
		s.mu.Unlock()
		defer func() {
			s.mu.Lock()
			s.active--
			delete(s.hosts, d.Host)
			delete(s.running, d.ID)
			s.cond.Broadcast()
			s.mu.Unlock()
		}()
		if ctx.Err() == nil {
			s.Run(ctx, d.ID)
		}
	}()
}

// Wait blocks until every started run has finished (tests).
func (s *Scheduler) Wait() { s.wg.Wait() }
