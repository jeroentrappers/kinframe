package ratelimit

import (
	"testing"
	"time"
)

func TestLimiter(t *testing.T) {
	now := time.Unix(1000, 0)
	l := New(3, time.Hour)
	l.now = func() time.Time { return now }
	for i := 0; i < 3; i++ {
		if ok, _ := l.Allow("a"); !ok {
			t.Fatalf("event %d denied", i)
		}
	}
	ok, retry := l.Allow("a")
	if ok || retry != time.Hour {
		t.Fatalf("4th allowed=%v retry=%v", ok, retry)
	}
	if ok, _ := l.Allow("b"); !ok {
		t.Fatal("other key limited")
	}
	now = now.Add(time.Hour)
	if ok, _ := l.Allow("a"); !ok {
		t.Fatal("window did not reset")
	}
}
