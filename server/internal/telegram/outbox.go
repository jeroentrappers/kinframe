package telegram

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/crash"
)

// Outbound call kinds, each with its own pace per chat (§14).
const (
	OutMessage  = iota // messages: 1 per 3 s per group chat, 1 per s in private chats
	OutReaction        // reactions: 1 per s per chat
	OutOther           // leaveChat and the like
	outKinds
)

// Job is one outbound Bot API call. Run's *Error with RetryAfter (429) puts
// the job back and pauses the chat; other errors are the job's to handle.
type Job struct {
	ChatID int64
	Group  bool
	Kind   int
	Run    func(ctx context.Context) error
}

// Outbox paces the bot's own calls: per chat and kind, and globally (20 calls
// per second), honouring 429 retry_after. It lives in memory: replies and
// reactions are best effort and are not replayed after a restart.
type Outbox struct {
	GroupGap    time.Duration // between messages in one group (default 3 s)
	PrivateGap  time.Duration // between messages in one private chat (default 1 s)
	ReactionGap time.Duration // between reactions in one chat (default 1 s)
	PerSecond   int           // global calls per second (default 20)
	Log         *slog.Logger

	mu      sync.Mutex
	queues  map[outKey][]Job
	next    map[outKey]time.Time
	paused  map[int64]time.Time // 429: the whole chat waits
	recent  []time.Time         // global window
	running int
	wake    chan struct{}
	idle    *sync.Cond
}

type outKey struct {
	chat int64
	kind int
}

func (o *Outbox) init() {
	if o.queues == nil {
		o.queues, o.next, o.paused = map[outKey][]Job{}, map[outKey]time.Time{}, map[int64]time.Time{}
		o.wake = make(chan struct{}, 1)
		o.idle = sync.NewCond(&o.mu)
	}
}

func (o *Outbox) gap(k outKey, group bool) time.Duration {
	switch k.kind {
	case OutMessage:
		if group {
			return orDur(o.GroupGap, 3*time.Second)
		}
		return orDur(o.PrivateGap, time.Second)
	case OutReaction:
		return orDur(o.ReactionGap, time.Second)
	}
	return 0
}

func orDur(d, def time.Duration) time.Duration {
	if d == 0 {
		return def
	}
	if d < 0 {
		return 0
	}
	return d
}

// Add queues a job.
func (o *Outbox) Add(j Job) {
	o.mu.Lock()
	o.init()
	k := outKey{j.ChatID, j.Kind}
	o.queues[k] = append(o.queues[k], j)
	o.mu.Unlock()
	o.poke()
}

func (o *Outbox) poke() {
	select {
	case o.wake <- struct{}{}:
	default:
	}
}

// Run processes jobs until ctx is done.
func (o *Outbox) Run(ctx context.Context) {
	o.mu.Lock()
	o.init()
	o.mu.Unlock()
	var wg sync.WaitGroup
	defer wg.Wait()
	for {
		wait := o.dispatch(ctx, &wg)
		t := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			t.Stop()
			return
		case <-o.wake:
		case <-t.C:
		}
		t.Stop()
	}
}

// dispatch starts every job that may run now and returns how long until the
// next one may.
func (o *Outbox) dispatch(ctx context.Context, wg *sync.WaitGroup) time.Duration {
	o.mu.Lock()
	defer o.mu.Unlock()
	now := time.Now()
	wait := time.Hour
	per := o.PerSecond
	if per <= 0 {
		per = 20
	}
	cut := 0
	for cut < len(o.recent) && now.Sub(o.recent[cut]) >= time.Second {
		cut++
	}
	o.recent = o.recent[cut:]
	for k, q := range o.queues {
		if len(q) == 0 {
			delete(o.queues, k)
			continue
		}
		at := o.next[k]
		if p := o.paused[k.chat]; p.After(at) {
			at = p
		}
		if now.Before(at) {
			wait = min(wait, at.Sub(now))
			continue
		}
		if len(o.recent) >= per {
			wait = min(wait, time.Second-now.Sub(o.recent[0]))
			continue
		}
		j := q[0]
		o.queues[k] = q[1:]
		o.next[k] = now.Add(o.gap(k, j.Group))
		o.recent = append(o.recent, now)
		o.running++
		wg.Add(1)
		go func() {
			defer crash.Recover("telegram")
			defer wg.Done()
			o.run(ctx, k, j)
		}()
		if len(o.queues[k]) > 0 {
			wait = min(wait, o.gap(k, j.Group))
		}
	}
	return max(wait, time.Millisecond)
}

func (o *Outbox) run(ctx context.Context, k outKey, j Job) {
	cctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	err := j.Run(cctx)
	cancel()
	o.mu.Lock()
	o.running--
	if e := AsError(err); e != nil && e.Code == 429 && ctx.Err() == nil {
		retry := max(e.RetryAfter, time.Second)
		o.paused[k.chat] = time.Now().Add(retry)
		o.queues[k] = append([]Job{j}, o.queues[k]...)
		if o.Log != nil {
			o.Log.Info("telegram: rate limited", "chat", k.chat, "retry_after", retry)
		}
	}
	if o.running == 0 && o.empty() {
		o.idle.Broadcast()
	}
	o.mu.Unlock()
	o.poke()
}

func (o *Outbox) empty() bool {
	for _, q := range o.queues {
		if len(q) > 0 {
			return false
		}
	}
	return true
}

// Wait blocks until no job is queued or running (tests), or ctx is done.
func (o *Outbox) Wait(ctx context.Context) {
	o.mu.Lock()
	o.init()
	stop := context.AfterFunc(ctx, func() { o.mu.Lock(); o.idle.Broadcast(); o.mu.Unlock() })
	defer stop()
	for (o.running > 0 || !o.empty()) && ctx.Err() == nil {
		o.idle.Wait()
	}
	o.mu.Unlock()
}

// SetPace changes the per-chat gaps (tests; negative means none).
func (o *Outbox) SetPace(group, private, reaction time.Duration) {
	o.mu.Lock()
	o.GroupGap, o.PrivateGap, o.ReactionGap = group, private, reaction
	o.mu.Unlock()
}
