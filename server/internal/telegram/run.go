package telegram

import (
	"context"
	"encoding/json"
	"log/slog"
	"sync"
	"time"
)

// Lock elects the one consumer among the server's processes (a Postgres
// advisory lock held on its own connection, §7).
type Lock interface {
	TryAcquire(ctx context.Context) (bool, error)
	// Check reports an error once the lock is lost (its connection died).
	Check(ctx context.Context) error
	Release()
}

// Lead runs work while this process holds lock, retrying every interval
// until ctx is done. work's context ends when the lock is lost.
func Lead(ctx context.Context, lock Lock, interval time.Duration, log *slog.Logger, work func(ctx context.Context)) {
	for ctx.Err() == nil {
		ok, err := lock.TryAcquire(ctx)
		if err != nil && ctx.Err() == nil && log != nil {
			log.Warn("telegram: consumer lock", "err", err)
		}
		if ok {
			lead(ctx, lock, interval, log, work)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(interval):
		}
	}
}

func lead(ctx context.Context, lock Lock, interval time.Duration, log *slog.Logger, work func(ctx context.Context)) {
	wctx, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() {
		defer close(done)
		work(wctx)
	}()
	t := time.NewTicker(interval)
	defer t.Stop()
loop:
	for {
		select {
		case <-ctx.Done():
			break loop
		case <-done:
			break loop
		case <-t.C:
			if err := lock.Check(ctx); err != nil {
				if ctx.Err() == nil && log != nil {
					log.Warn("telegram: lost the consumer lock", "err", err)
				}
				break loop
			}
		}
	}
	cancel()
	<-done
	lock.Release()
}

// Poller long-polls getUpdates (§7): store hands the raw updates to the
// server (filter, dedupe, store, advance the offset in one step).
type Poller struct {
	Client  *Client
	Timeout int // seconds, default 50
	Offset  func(ctx context.Context) (int64, error)
	Store   func(ctx context.Context, updates []json.RawMessage) error
	Log     *slog.Logger
	// MinBackoff and MaxBackoff bound the wait after errors (1 s to 60 s).
	MinBackoff, MaxBackoff time.Duration
}

// Run polls until ctx is done.
func (p *Poller) Run(ctx context.Context) {
	timeout := p.Timeout
	if timeout <= 0 {
		timeout = 50
	}
	minB, maxB := orDur(p.MinBackoff, time.Second), orDur(p.MaxBackoff, time.Minute)
	backoff := minB
	fail := func(err error) {
		if ctx.Err() != nil {
			return
		}
		if p.Log != nil {
			p.Log.Warn("telegram: polling", "err", err, "retry_in", backoff)
		}
		wait := backoff
		if e := AsError(err); e != nil && e.RetryAfter > wait {
			wait = e.RetryAfter
		}
		select {
		case <-ctx.Done():
		case <-time.After(wait):
		}
		backoff = min(backoff*2, maxB)
	}
	for ctx.Err() == nil {
		off, err := p.Offset(ctx)
		if err != nil {
			fail(err)
			continue
		}
		cctx, cancel := context.WithTimeout(ctx, time.Duration(timeout+20)*time.Second)
		ups, err := p.Client.GetUpdates(cctx, off, timeout)
		cancel()
		if err != nil {
			fail(err)
			continue
		}
		if len(ups) > 0 {
			if err := p.Store(ctx, ups); err != nil {
				fail(err)
				continue
			}
		}
		backoff = minB
	}
}

// Dispatcher processes stored updates in order per chat: one worker at a
// time per chat, at most Workers chats in parallel (§8.2).
type Dispatcher struct {
	Workers int
	// Chats lists chats with pending updates, oldest first.
	Chats func(ctx context.Context) ([]int64, error)
	// Next handles the chat's oldest pending update; false when none is left
	// (or it must wait: an error the handler recorded).
	Next func(ctx context.Context, chatID int64) bool
	Log  *slog.Logger
	Tick time.Duration // default 1 s

	mu     sync.Mutex
	active map[int64]bool
	wake   chan struct{}
	wg     sync.WaitGroup
}

func (d *Dispatcher) init() {
	d.mu.Lock()
	if d.active == nil {
		d.active, d.wake = map[int64]bool{}, make(chan struct{}, 1)
	}
	d.mu.Unlock()
}

// Wake makes the dispatcher look for work now.
func (d *Dispatcher) Wake() {
	d.init()
	select {
	case d.wake <- struct{}{}:
	default:
	}
}

// Run dispatches until ctx is done, then waits for running workers.
func (d *Dispatcher) Run(ctx context.Context) {
	d.init()
	defer d.wg.Wait()
	tick := orDur(d.Tick, time.Second)
	for {
		d.round(ctx)
		select {
		case <-ctx.Done():
			return
		case <-d.wake:
		case <-time.After(tick):
		}
	}
}

func (d *Dispatcher) round(ctx context.Context) {
	chats, err := d.Chats(ctx)
	if err != nil {
		if ctx.Err() == nil && d.Log != nil {
			d.Log.Warn("telegram: pending updates", "err", err)
		}
		return
	}
	workers := max(d.Workers, 1)
	d.mu.Lock()
	defer d.mu.Unlock()
	for _, c := range chats {
		if d.active[c] {
			continue
		}
		if len(d.active) >= workers {
			return
		}
		d.active[c] = true
		d.wg.Add(1)
		go func(chat int64) {
			defer d.wg.Done()
			for ctx.Err() == nil && d.Next(ctx, chat) {
			}
			d.mu.Lock()
			delete(d.active, chat)
			d.mu.Unlock()
			d.Wake()
		}(c)
	}
}

// Idle reports whether no chat is being processed (tests).
func (d *Dispatcher) Idle() bool {
	d.init()
	d.mu.Lock()
	defer d.mu.Unlock()
	return len(d.active) == 0
}
