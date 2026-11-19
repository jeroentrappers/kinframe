package telegram

import "time"

// Batching (§8.4): one send per burst of one contributor in one chat.
const (
	BatchQuiet    = 60 * time.Second // closes after the contributor paused this long
	BatchMax      = 10 * time.Minute // …or this long after it opened
	AlbumGrace    = 5 * time.Second  // never while an album it holds got a message this recently
	PendingWait   = 10 * time.Minute // pending downloads hold a batch at most this long
	CloserTick    = 5 * time.Second
	AlbumMaxItems = 10 // Telegram's album limit
)

// Batch is what the closing rule needs to know about an open batch.
type Batch struct {
	OpenedAt time.Time
	LastAt   time.Time // the contributor's latest accepted media
	// AlbumAt is the latest message of a media group the batch holds (zero: none).
	AlbumAt time.Time
	Items   int
}

// ClosesAt is when the batch closes unless more media arrives: 60 s after
// the last media, at the latest 10 minutes after it opened, but never while
// one of its albums is still arriving.
func (b Batch) ClosesAt() time.Time {
	at := b.LastAt.Add(BatchQuiet)
	if cap := b.OpenedAt.Add(BatchMax); cap.Before(at) {
		at = cap
	}
	if !b.AlbumAt.IsZero() {
		if g := b.AlbumAt.Add(AlbumGrace); g.After(at) {
			at = g
		}
	}
	return at
}

// Accepts reports whether one more item may join the batch at now: it is
// still open and not full. Items of an album the batch already holds always
// join (albums are never split), so a batch can exceed maxItems by at most
// one album.
func (b Batch) Accepts(now time.Time, maxItems int, sameAlbum bool) bool {
	if sameAlbum {
		return true
	}
	return now.Before(b.ClosesAt()) && b.Items < maxItems
}

// Add records an item arriving at t (album: it belongs to a media group).
func (b *Batch) Add(t time.Time, album bool) {
	if b.OpenedAt.IsZero() {
		b.OpenedAt = t
	}
	if t.After(b.LastAt) {
		b.LastAt = t
	}
	if album && t.After(b.AlbumAt) {
		b.AlbumAt = t
	}
	b.Items++
}

// Due reports whether the batch should close at now: its time has come, or
// it is full (and no album of it is still arriving). Pending downloads hold
// it at most PendingWait longer.
func (b Batch) Due(now time.Time, maxItems int, pending bool) bool {
	full := b.Items >= maxItems && (b.AlbumAt.IsZero() || !now.Before(b.AlbumAt.Add(AlbumGrace)))
	if !full && now.Before(b.ClosesAt()) {
		return false
	}
	if pending && now.Before(b.ClosesAt().Add(PendingWait)) {
		return false
	}
	return true
}
