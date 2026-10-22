// Package ratelimit implements in-memory fixed-window rate limits keyed by
// strings (user IDs, IPs). Fine for a single server process.
package ratelimit

import (
	"sync"
	"time"
)

type window struct {
	start time.Time
	count int
}

type Limiter struct {
	limit  int
	period time.Duration
	now    func() time.Time

	mu      sync.Mutex
	windows map[string]*window
	sweep   time.Time
}

// New allows limit events per period per key.
func New(limit int, period time.Duration) *Limiter {
	return &Limiter{limit: limit, period: period, now: time.Now, windows: map[string]*window{}}
}

// Allow records an event and reports whether it is within the limit; if not,
// it returns how long until the window resets.
func (l *Limiter) Allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	if now.Sub(l.sweep) > l.period {
		for k, w := range l.windows {
			if now.Sub(w.start) >= l.period {
				delete(l.windows, k)
			}
		}
		l.sweep = now
	}
	w, ok := l.windows[key]
	if !ok || now.Sub(w.start) >= l.period {
		w = &window{start: now}
		l.windows[key] = w
	}
	if w.count >= l.limit {
		return false, w.start.Add(l.period).Sub(now)
	}
	w.count++
	return true, 0
}
