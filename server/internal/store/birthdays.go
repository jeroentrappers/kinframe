package store

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"sort"
	"strings"
	"time"
	"unicode"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"golang.org/x/text/cases"
	"golang.org/x/text/runes"
	"golang.org/x/text/transform"
	"golang.org/x/text/unicode/norm"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Birthdays: docs/spec/birthdays.md. A feed keeps a list of birthday people;
// every frame with an active subscription that has `birthdays` on gets the
// merged list as one compacted `birthdays` change (entity = frame id),
// rendered from current state at read time. A digest of the merged list
// (frame_birthdays) keeps unchanged content from being emitted again.

// FeedBirthdays is the feed kind of a frame's `birthdays` change.
const FeedBirthdays = "birthdays"

// ---------------------------------------------------------------- dates and names

// NameKey normalizes a display name for comparing (§2): NFKC, case-folded,
// diacritics removed, trimmed, inner whitespace collapsed ("Zoë  " = "zoe").
func NameKey(name string) string {
	s := norm.NFKC.String(name)
	s = cases.Fold().String(s)
	t := transform.Chain(norm.NFD, runes.Remove(runes.In(unicode.Mn)), norm.NFC)
	if out, _, err := transform.String(t, s); err == nil {
		s = out
	}
	return strings.Join(strings.Fields(s), " ")
}

// IsLeap reports whether y is a Gregorian leap year.
func IsLeap(y int) bool { return y%4 == 0 && (y%100 != 0 || y%400 == 0) }

// DaysIn is the longest the month gets (29 for February).
func DaysIn(month int) int {
	switch month {
	case 2:
		return 29
	case 4, 6, 9, 11:
		return 30
	}
	return 31
}

// Occurrence is the person's birthday in year y: 29 February falls on 28
// February in non-leap years (§2).
func Occurrence(month, day, y int) time.Time {
	if month == 2 && day == 29 && !IsLeap(y) {
		day = 28
	}
	return time.Date(y, time.Month(month), day, 0, 0, 0, 0, time.UTC)
}

// OccursOn reports whether (month, day) is a birthday on date d (its year,
// month and day; the zone is ignored).
func OccursOn(month, day int, d time.Time) bool {
	o := Occurrence(month, day, d.Year())
	return o.Month() == d.Month() && o.Day() == d.Day()
}

// NextOccurrence is the first occurrence on or after today (a date). With a
// known birth year, occurrences in the year of birth are not birthdays.
func NextOccurrence(month, day int, birthYear *int, today time.Time) time.Time {
	t := time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, time.UTC)
	o := Occurrence(month, day, t.Year())
	if o.Before(t) || (birthYear != nil && o.Year() <= *birthYear) {
		y := t.Year() + 1
		if birthYear != nil && y <= *birthYear {
			y = *birthYear + 1
		}
		o = Occurrence(month, day, y)
	}
	return o
}

// MonthDaysAround lists the (month, day) pairs that are birthdays on any of
// the given dates (both 28 and 29 February on 28 February of a non-leap year).
func MonthDaysAround(dates ...time.Time) (months, days []int) {
	seen := map[[2]int]bool{}
	add := func(m, d int) {
		k := [2]int{m, d}
		if !seen[k] {
			seen[k] = true
			months, days = append(months, m), append(days, d)
		}
	}
	for _, d := range dates {
		add(int(d.Month()), d.Day())
		if d.Month() == 2 && d.Day() == 28 && !IsLeap(d.Year()) {
			add(2, 29)
		}
	}
	return months, days
}

// ---------------------------------------------------------------- rows

type Birthday struct {
	ID          uuid.UUID  `db:"id"`
	FeedID      uuid.UUID  `db:"feed_id"`
	Status      string     `db:"status"`
	DisplayName string     `db:"display_name"`
	NameKey     string     `db:"name_key"`
	Month       int        `db:"month"`
	Day         int        `db:"day"`
	BirthYear   *int       `db:"birth_year"`
	ShowAge     bool       `db:"show_age"`
	UserID      *uuid.UUID `db:"user_id"`
	PhotoKey    *string    `db:"photo_key"`
	CreatedBy   *uuid.UUID `db:"created_by"`
	Suggested   bool       `db:"suggested"`
	DecidedBy   *uuid.UUID `db:"decided_by"`
	DecidedAt   *time.Time `db:"decided_at"`
	CreatedAt   time.Time  `db:"created_at"`
	UpdatedAt   time.Time  `db:"updated_at"`
	// Joined for the API.
	LinkedName      *string `db:"linked_name"`
	LinkedAvatarKey *string `db:"linked_avatar_key"`
	CreatedByName   *string `db:"created_by_name"`
	DecidedByName   *string `db:"decided_by_name"`
}

// Live reports pending or approved (what the uniqueness rules and the limit count).
func (b *Birthday) Live() bool { return b.Status == "pending" || b.Status == "approved" }

// Photo is the uploaded photo, else the linked member's avatar.
func (b *Birthday) Photo() *string {
	if b.PhotoKey != nil {
		return b.PhotoKey
	}
	return b.LinkedAvatarKey
}

const birthdaySelect = `SELECT b.id, b.feed_id, b.status, b.display_name, b.name_key, b.month, b.day, b.birth_year, b.show_age,
	b.user_id, b.photo_key, b.created_by, b.suggested, b.decided_by, b.decided_at, b.created_at, b.updated_at,
	lu.display_name AS linked_name, lu.avatar_key AS linked_avatar_key, cu.display_name AS created_by_name, du.display_name AS decided_by_name
	FROM feed_birthdays b
	LEFT JOIN users lu ON lu.id = b.user_id AND lu.deleted_at IS NULL
	LEFT JOIN users cu ON cu.id = b.created_by AND cu.deleted_at IS NULL
	LEFT JOIN users du ON du.id = b.decided_by AND du.deleted_at IS NULL`

// birthdayRetention: rejected suggestions are listed this long, then deleted.
const birthdayRetention = `interval '30 days'`

// GetBirthday returns an entry of the feed (rejected ones only within the retention window).
func (s *Store) GetBirthday(ctx context.Context, q db.DBTX, feedID, id uuid.UUID) (*Birthday, error) {
	return one[Birthday](q.Query(ctx, birthdaySelect+` WHERE b.feed_id = $1 AND b.id = $2
		AND (b.status <> 'rejected' OR b.decided_at > now() - `+birthdayRetention+`)`, feedID, id))
}

// LockBirthday takes a row lock on an entry of the feed and returns it.
func (tx *Tx) LockBirthday(ctx context.Context, feedID, id uuid.UUID) (*Birthday, error) {
	if _, err := tx.Exec(ctx, `SELECT 1 FROM feed_birthdays WHERE id = $1 AND feed_id = $2 FOR UPDATE`, id, feedID); err != nil {
		return nil, err
	}
	return one[Birthday](tx.Query(ctx, birthdaySelect+` WHERE b.feed_id = $1 AND b.id = $2`, feedID, id))
}

// ListBirthdays lists a feed's entries (rejected ones within the retention window).
func (s *Store) ListBirthdays(ctx context.Context, q db.DBTX, feedID uuid.UUID) ([]Birthday, error) {
	return many[Birthday](q.Query(ctx, birthdaySelect+` WHERE b.feed_id = $1
		AND (b.status <> 'rejected' OR b.decided_at > now() - `+birthdayRetention+`) ORDER BY b.month, b.day, b.name_key, b.id`, feedID))
}

// CountLiveBirthdays counts a feed's pending and approved entries (the limit).
func (s *Store) CountLiveBirthdays(ctx context.Context, q db.DBTX, feedID uuid.UUID) (int, error) {
	var n int
	err := q.QueryRow(ctx, `SELECT count(*) FROM feed_birthdays WHERE feed_id = $1 AND status IN ('pending', 'approved')`, feedID).Scan(&n)
	return n, err
}

// BirthdayDuplicate returns the id of an entry of the feed with the same name
// key and date in one of statuses, other than exclude.
func (s *Store) BirthdayDuplicate(ctx context.Context, q db.DBTX, feedID uuid.UUID, nameKey string, month, day int, statuses []string, exclude *uuid.UUID) (*uuid.UUID, error) {
	var id uuid.UUID
	err := q.QueryRow(ctx, `SELECT id FROM feed_birthdays WHERE feed_id = $1 AND name_key = $2 AND month = $3 AND day = $4
		AND status = ANY($5) AND ($6::uuid IS NULL OR id <> $6) ORDER BY created_at LIMIT 1`, feedID, nameKey, month, day, statuses, exclude).Scan(&id)
	if db.NotFound(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &id, nil
}

// MemberHasBirthday reports whether userID is linked to a live entry of the feed other than exclude.
func (s *Store) MemberHasBirthday(ctx context.Context, q db.DBTX, feedID, userID uuid.UUID, exclude *uuid.UUID) (bool, error) {
	var ok bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM feed_birthdays WHERE feed_id = $1 AND user_id = $2
		AND status IN ('pending', 'approved') AND ($3::uuid IS NULL OR id <> $3))`, feedID, userID, exclude).Scan(&ok)
	return ok, err
}

// InsertBirthday creates an entry.
func (tx *Tx) InsertBirthday(ctx context.Context, b *Birthday) error {
	_, err := tx.Exec(ctx, `INSERT INTO feed_birthdays (id, feed_id, status, display_name, name_key, month, day, birth_year, show_age,
		user_id, created_by, suggested) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
		b.ID, b.FeedID, b.Status, b.DisplayName, NameKey(b.DisplayName), b.Month, b.Day, b.BirthYear, b.ShowAge, b.UserID, b.CreatedBy, b.Suggested)
	return err
}

// SaveBirthday writes the editable fields of an entry.
func (tx *Tx) SaveBirthday(ctx context.Context, b *Birthday) error {
	_, err := tx.Exec(ctx, `UPDATE feed_birthdays SET display_name = $2, name_key = $3, month = $4, day = $5, birth_year = $6,
		show_age = $7, user_id = $8, updated_at = now() WHERE id = $1`,
		b.ID, b.DisplayName, NameKey(b.DisplayName), b.Month, b.Day, b.BirthYear, b.ShowAge, b.UserID)
	return err
}

// DecideBirthday approves or rejects a pending entry.
func (tx *Tx) DecideBirthday(ctx context.Context, id uuid.UUID, status string, by uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE feed_birthdays SET status = $2, decided_by = $3, decided_at = now(), updated_at = now() WHERE id = $1`, id, status, by)
	return err
}

// DeleteBirthday deletes an entry (its greetings cascade) and returns its photo key.
func (tx *Tx) DeleteBirthday(ctx context.Context, id uuid.UUID) (*string, error) {
	var key *string
	err := tx.QueryRow(ctx, `DELETE FROM feed_birthdays WHERE id = $1 RETURNING photo_key`, id).Scan(&key)
	if db.NotFound(err) {
		return nil, nil
	}
	return key, err
}

// SetBirthdayPhoto sets (or clears) the uploaded photo and returns the previous key.
func (tx *Tx) SetBirthdayPhoto(ctx context.Context, id uuid.UUID, key *string) (*string, error) {
	var old *string
	err := tx.QueryRow(ctx, `UPDATE feed_birthdays b SET photo_key = $2, updated_at = now()
		FROM (SELECT photo_key FROM feed_birthdays WHERE id = $1) o WHERE b.id = $1 RETURNING o.photo_key`, id, key).Scan(&old)
	return old, err
}

// DeleteFeedBirthdays deletes every entry of a feed (it is being deleted) and returns their photo keys.
func (tx *Tx) DeleteFeedBirthdays(ctx context.Context, feedID uuid.UUID) ([]string, error) {
	rows, err := tx.Query(ctx, `DELETE FROM feed_birthdays WHERE feed_id = $1 RETURNING photo_key`, feedID)
	if err != nil {
		return nil, err
	}
	return collectKeys(rows)
}

func collectKeys(rows pgx.Rows) ([]string, error) {
	defer rows.Close()
	var out []string
	for rows.Next() {
		var k *string
		if err := rows.Scan(&k); err != nil {
			return nil, err
		}
		if k != nil {
			out = append(out, *k)
		}
	}
	return out, rows.Err()
}

// UnlinkedBirthday is an entry whose member link was cleared.
type UnlinkedBirthday struct {
	ID     uuid.UUID `db:"id"`
	FeedID uuid.UUID `db:"feed_id"`
}

// UnlinkBirthdays clears the link to userID (in feedID, or every feed when
// nil): the member left, was removed or deleted their account (§10). The
// frames of the affected feeds are synced.
func (tx *Tx) UnlinkBirthdays(ctx context.Context, feedID *uuid.UUID, userID uuid.UUID) ([]UnlinkedBirthday, error) {
	out, err := many[UnlinkedBirthday](tx.Query(ctx, `UPDATE feed_birthdays SET user_id = NULL, updated_at = now()
		WHERE user_id = $1 AND ($2::uuid IS NULL OR feed_id = $2) RETURNING id, feed_id`, userID, feedID))
	if err != nil || len(out) == 0 {
		return out, err
	}
	feeds := make([]uuid.UUID, 0, len(out))
	for _, u := range out {
		feeds = append(feeds, u.FeedID)
	}
	return out, tx.SyncBirthdayFeeds(ctx, feeds)
}

// FeedsUsingAvatar lists the feeds with approved entries linked to userID
// that show the member's avatar (no photo of their own).
func (s *Store) FeedsUsingAvatar(ctx context.Context, q db.DBTX, userID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT DISTINCT feed_id FROM feed_birthdays WHERE user_id = $1 AND status = 'approved' AND photo_key IS NULL
		ORDER BY feed_id`, userID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// SetBirthdayNotifications sets a member's preference for a feed.
func (tx *Tx) SetBirthdayNotifications(ctx context.Context, feedID, userID uuid.UUID, on bool) error {
	_, err := tx.Exec(ctx, `UPDATE feed_members SET birthday_notifications = $3 WHERE feed_id = $1 AND user_id = $2`, feedID, userID, on)
	return err
}

// SetSubscriptionBirthdays flips a subscription's `birthdays` flag; the frame
// (locked by the caller) is synced.
func (tx *Tx) SetSubscriptionBirthdays(ctx context.Context, sub *Subscription, on bool) error {
	if _, err := tx.Exec(ctx, `UPDATE subscriptions SET birthdays = $2 WHERE id = $1`, sub.ID, on); err != nil {
		return err
	}
	return tx.SyncFrameBirthdays(ctx, sub.FrameID)
}

// ---------------------------------------------------------------- the frame's merged list (§6.5)

// FrameBirthdayEntry is one approved entry of a feed the frame shows birthdays of.
type FrameBirthdayEntry struct {
	ID          uuid.UUID  `db:"id"`
	FeedID      uuid.UUID  `db:"feed_id"`
	DisplayName string     `db:"display_name"`
	NameKey     string     `db:"name_key"`
	Month       int        `db:"month"`
	Day         int        `db:"day"`
	BirthYear   *int       `db:"birth_year"`
	ShowAge     bool       `db:"show_age"`
	UserID      *uuid.UUID `db:"user_id"`
	PhotoKey    *string    `db:"photo_key"`
	AvatarKey   *string    `db:"avatar_key"`
	ActivatedAt time.Time  `db:"activated_at"`
	CreatedAt   time.Time  `db:"created_at"`
}

// Photo is the entry's own photo, else its linked member's avatar.
func (e *FrameBirthdayEntry) Photo() *string {
	if e.PhotoKey != nil {
		return e.PhotoKey
	}
	return e.AvatarKey
}

// FrameBirthdayGroup is one person on a frame: entries merged on name key + day + month.
type FrameBirthdayGroup struct {
	ID          uuid.UUID   `json:"id"`
	Name        string      `json:"name"`
	Month       int         `json:"month"`
	Day         int         `json:"day"`
	BirthYear   *int        `json:"birth_year,omitempty"`
	PhotoKey    *string     `json:"photo_key,omitempty"`
	FeedIDs     []uuid.UUID `json:"feed_ids"`
	BirthdayIDs []uuid.UUID `json:"birthday_ids"`
	// Entries are the merged entries, primary order (not part of the digest).
	Entries []FrameBirthdayEntry `json:"-"`
}

const frameBirthdayEntriesSQL = `SELECT b.id, b.feed_id, b.display_name, b.name_key, b.month, b.day, b.birth_year, b.show_age, b.user_id,
	b.photo_key, u.avatar_key, COALESCE(s.activated_at, s.created_at) AS activated_at, b.created_at
	FROM subscriptions s JOIN feeds f ON f.id = s.feed_id AND f.deleted_at IS NULL
	JOIN feed_birthdays b ON b.feed_id = s.feed_id AND b.status = 'approved'
	LEFT JOIN users u ON u.id = b.user_id AND u.deleted_at IS NULL
	WHERE s.frame_id = $1 AND s.status = 'active' AND s.birthdays`

// FrameBirthdayEntries lists the approved entries of every feed the frame has
// an active subscription with `birthdays` to.
func (s *Store) FrameBirthdayEntries(ctx context.Context, q db.DBTX, frameID uuid.UUID) ([]FrameBirthdayEntry, error) {
	return many[FrameBirthdayEntry](q.Query(ctx, frameBirthdayEntriesSQL, frameID))
}

// MergeBirthdays merges entries on name key + day + month (§6.5): the primary
// is the entry with a photo whose subscription was activated first (else the
// first activated; ties: the oldest entry); birth_year only when every entry
// with a year has show_age on, at least one has a year and the years agree.
// Groups are sorted by month and day (then name).
func MergeBirthdays(entries []FrameBirthdayEntry) []FrameBirthdayGroup {
	type key struct {
		name       string
		month, day int
	}
	by := map[key][]FrameBirthdayEntry{}
	var keys []key
	for _, e := range entries {
		k := key{e.NameKey, e.Month, e.Day}
		if _, ok := by[k]; !ok {
			keys = append(keys, k)
		}
		by[k] = append(by[k], e)
	}
	out := make([]FrameBirthdayGroup, 0, len(keys))
	for _, k := range keys {
		es := by[k]
		sort.SliceStable(es, func(i, j int) bool {
			a, b := es[i], es[j]
			if !a.ActivatedAt.Equal(b.ActivatedAt) {
				return a.ActivatedAt.Before(b.ActivatedAt)
			}
			if !a.CreatedAt.Equal(b.CreatedAt) {
				return a.CreatedAt.Before(b.CreatedAt)
			}
			return a.ID.String() < b.ID.String()
		})
		primary := es[0]
		for _, e := range es {
			if e.Photo() != nil {
				primary = e
				break
			}
		}
		g := FrameBirthdayGroup{ID: primary.ID, Name: primary.DisplayName, Month: k.month, Day: k.day, PhotoKey: primary.Photo(),
			BirthYear: MergedBirthYear(es), Entries: es}
		seen := map[uuid.UUID]bool{}
		for _, e := range es {
			g.BirthdayIDs = append(g.BirthdayIDs, e.ID)
			if !seen[e.FeedID] {
				seen[e.FeedID] = true
				g.FeedIDs = append(g.FeedIDs, e.FeedID)
			}
		}
		out = append(out, g)
	}
	sort.SliceStable(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if a.Month != b.Month {
			return a.Month < b.Month
		}
		if a.Day != b.Day {
			return a.Day < b.Day
		}
		if a.Name != b.Name {
			return a.Name < b.Name
		}
		return a.ID.String() < b.ID.String()
	})
	return out
}

// MergedBirthYear is the year shown for merged entries: present only if every
// entry that has a year has show_age on, at least one has a year and all
// given years agree (the most restrictive choice wins).
func MergedBirthYear(es []FrameBirthdayEntry) *int {
	var year *int
	for _, e := range es {
		if e.BirthYear == nil {
			continue
		}
		if !e.ShowAge || (year != nil && *year != *e.BirthYear) {
			return nil
		}
		y := *e.BirthYear
		year = &y
	}
	return year
}

// FrameBirthdays merges what the frame shows now.
func (s *Store) FrameBirthdays(ctx context.Context, q db.DBTX, frameID uuid.UUID) ([]FrameBirthdayGroup, error) {
	es, err := s.FrameBirthdayEntries(ctx, q, frameID)
	if err != nil {
		return nil, err
	}
	return MergeBirthdays(es), nil
}

func birthdayDigest(groups []FrameBirthdayGroup) string {
	if len(groups) == 0 {
		return ""
	}
	b, _ := json.Marshal(groups)
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// SyncFrameBirthdays recomputes the frame's merged list and appends a
// `birthdays` change when it differs from what the frame was last sent. The
// frame must be locked by the caller (LockFrame), which also orders this
// after every concurrent writer of the same frame.
func (tx *Tx) SyncFrameBirthdays(ctx context.Context, frameID uuid.UUID) error {
	es, err := many[FrameBirthdayEntry](tx.Query(ctx, frameBirthdayEntriesSQL, frameID))
	if err != nil {
		return err
	}
	digest := birthdayDigest(MergeBirthdays(es))
	var prev string
	err = tx.QueryRow(ctx, `SELECT digest FROM frame_birthdays WHERE frame_id = $1`, frameID).Scan(&prev)
	if err != nil && !db.NotFound(err) {
		return err
	}
	if prev == digest {
		return nil // unchanged (no row and nothing to show are the same)
	}
	if _, err := tx.Exec(ctx, `INSERT INTO frame_birthdays (frame_id, digest, emitted_at) VALUES ($1, $2, now())
		ON CONFLICT (frame_id) DO UPDATE SET digest = EXCLUDED.digest, emitted_at = now()`, frameID, digest); err != nil {
		return err
	}
	return tx.FeedPut(ctx, frameID, FeedBirthdays, frameID, false)
}

// SyncBirthdayFeeds syncs every frame with an active birthday subscription to
// one of the feeds, locking frames in id order (after any feed lock the
// caller holds, like every multi-frame writer).
func (tx *Tx) SyncBirthdayFeeds(ctx context.Context, feedIDs []uuid.UUID) error {
	if len(feedIDs) == 0 {
		return nil
	}
	rows, err := tx.Query(ctx, `SELECT DISTINCT frame_id FROM subscriptions WHERE feed_id = ANY($1) AND status = 'active' AND birthdays
		ORDER BY frame_id`, feedIDs)
	if err != nil {
		return err
	}
	frames, err := collectIDs(rows)
	if err != nil {
		return err
	}
	sort.Slice(frames, func(i, j int) bool { return frames[i].String() < frames[j].String() })
	for _, f := range frames {
		if _, err := tx.LockFrame(ctx, f); err == ErrNotFound {
			continue
		} else if err != nil {
			return err
		}
		if err := tx.SyncFrameBirthdays(ctx, f); err != nil {
			return err
		}
	}
	return nil
}

// FrameBirthdaysEmittedAt is when the frame's current list was emitted (its generated_at).
func (s *Store) FrameBirthdaysEmittedAt(ctx context.Context, q db.DBTX, frameID uuid.UUID) (time.Time, error) {
	var t time.Time
	err := q.QueryRow(ctx, `SELECT emitted_at FROM frame_birthdays WHERE frame_id = $1`, frameID).Scan(&t)
	if db.NotFound(err) {
		return time.Now().UTC(), nil
	}
	return t, err
}

// ---------------------------------------------------------------- greetings (§7)

type BirthdayGreeting struct {
	ID            uuid.UUID `db:"id"`
	BirthdayID    uuid.UUID `db:"birthday_id"`
	BirthdayName  string    `db:"birthday_name"`
	FeedID        uuid.UUID `db:"feed_id"`
	FrameID       uuid.UUID `db:"frame_id"`
	LocalDate     time.Time `db:"local_date"`
	FrameName     string    `db:"frame_name"`
	FrameLocation *string   `db:"frame_location"`
	CreatedAt     time.Time `db:"created_at"`
}

const greetingSelect = `SELECT g.id, g.birthday_id, b.display_name AS birthday_name, g.feed_id, g.frame_id, g.local_date, g.frame_name,
	g.frame_location, g.created_at FROM birthday_greetings g JOIN feed_birthdays b ON b.id = g.birthday_id`

// InsertGreetings records one greeting per entry (skipping existing ones) and
// returns the new rows.
func (tx *Tx) InsertGreetings(ctx context.Context, birthdayIDs []uuid.UUID, frameID uuid.UUID, date time.Time, frameName string, frameLocation *string, at time.Time) ([]BirthdayGreeting, error) {
	var ids []uuid.UUID
	for _, bid := range birthdayIDs {
		id := NewID()
		tag, err := tx.Exec(ctx, `INSERT INTO birthday_greetings (id, birthday_id, feed_id, frame_id, local_date, frame_name, frame_location, created_at)
			SELECT $1, b.id, b.feed_id, $3, $4, $5, $6, $7 FROM feed_birthdays b WHERE b.id = $2
			ON CONFLICT (birthday_id, frame_id, local_date) DO NOTHING`, id, bid, frameID, date, frameName, frameLocation, at)
		if err != nil {
			return nil, err
		}
		if tag.RowsAffected() > 0 {
			ids = append(ids, id)
		}
	}
	if len(ids) == 0 {
		return nil, nil
	}
	return many[BirthdayGreeting](tx.Query(ctx, greetingSelect+` WHERE g.id = ANY($1) ORDER BY g.feed_id, g.id`, ids))
}

// GreetingsExist reports whether the frame greeted every one of the entries on date.
func (s *Store) GreetingsExist(ctx context.Context, q db.DBTX, birthdayIDs []uuid.UUID, frameID uuid.UUID, date time.Time) (bool, error) {
	var n int
	err := q.QueryRow(ctx, `SELECT count(*) FROM birthday_greetings WHERE birthday_id = ANY($1) AND frame_id = $2 AND local_date = $3`,
		birthdayIDs, frameID, date).Scan(&n)
	return n >= len(birthdayIDs), err
}

// CountFrameGreetingsSince counts the greetings a frame sent since t (one per
// tap: the rows of one greeting share their created_at).
func (s *Store) CountFrameGreetingsSince(ctx context.Context, q db.DBTX, frameID uuid.UUID, t time.Time) (int, error) {
	var n int
	err := q.QueryRow(ctx, `SELECT count(DISTINCT created_at) FROM birthday_greetings WHERE frame_id = $1 AND created_at >= $2`, frameID, t).Scan(&n)
	return n, err
}

// ListGreetings pages a feed's greetings newest first.
func (s *Store) ListGreetings(ctx context.Context, feedID uuid.UUID, birthdayID *uuid.UUID, date *time.Time, before *uuid.UUID, limit int) ([]BirthdayGreeting, error) {
	return many[BirthdayGreeting](s.Pool.Query(ctx, greetingSelect+` WHERE g.feed_id = $1 AND ($2::uuid IS NULL OR g.birthday_id = $2)
		AND ($3::date IS NULL OR g.local_date = $3) AND ($4::uuid IS NULL OR g.id < $4)
		ORDER BY g.id DESC LIMIT $5`, feedID, birthdayID, date, before, limit))
}

// GreetingCounts counts the frames that greeted each entry on date.
func (s *Store) GreetingCounts(ctx context.Context, q db.DBTX, birthdayIDs []uuid.UUID, date time.Time) (map[uuid.UUID]int, error) {
	out := map[uuid.UUID]int{}
	if len(birthdayIDs) == 0 {
		return out, nil
	}
	rows, err := q.Query(ctx, `SELECT birthday_id, count(DISTINCT frame_id)::int FROM birthday_greetings
		WHERE birthday_id = ANY($1) AND local_date = $2 GROUP BY birthday_id`, birthdayIDs, date)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id uuid.UUID
		var n int
		if err := rows.Scan(&id, &n); err != nil {
			return nil, err
		}
		out[id] = n
	}
	return out, rows.Err()
}

// CountGreetingFrames counts the distinct frames that greeted any of the entries on date.
func (s *Store) CountGreetingFrames(ctx context.Context, q db.DBTX, birthdayIDs []uuid.UUID, date time.Time) (int, error) {
	var n int
	err := q.QueryRow(ctx, `SELECT count(DISTINCT frame_id) FROM birthday_greetings WHERE birthday_id = ANY($1) AND local_date = $2`,
		birthdayIDs, date).Scan(&n)
	return n, err
}

// ---------------------------------------------------------------- members and their entries

// BirthdayMember is a feed member as notifications need them.
type BirthdayMember struct {
	FeedID                uuid.UUID `db:"feed_id"`
	UserID                uuid.UUID `db:"user_id"`
	Role                  string    `db:"role"`
	BirthdayNotifications bool      `db:"birthday_notifications"`
	Timezone              *string   `db:"timezone"`
}

// BirthdayMembers lists the members of feeds.
func (s *Store) BirthdayMembers(ctx context.Context, q db.DBTX, feedIDs []uuid.UUID) ([]BirthdayMember, error) {
	return many[BirthdayMember](q.Query(ctx, `SELECT m.feed_id, m.user_id, m.role, m.birthday_notifications, u.timezone
		FROM feed_members m JOIN users u ON u.id = m.user_id AND u.deleted_at IS NULL
		WHERE m.feed_id = ANY($1) ORDER BY m.user_id, m.feed_id`, feedIDs))
}

// MemberBirthdayEntry is an approved entry of a feed a member belongs to.
type MemberBirthdayEntry struct {
	MemberID              uuid.UUID `db:"member_id"`
	MemberTimezone        *string   `db:"member_timezone"`
	BirthdayNotifications bool      `db:"birthday_notifications"`
	FrameBirthdayEntry
}

// MemberEntries lists approved entries on the given (month, day) pairs in the
// live feeds of members (all members when userIDs is nil; only feeds with
// birthday_notifications on when notifiedOnly).
func (s *Store) MemberEntries(ctx context.Context, q db.DBTX, userIDs []uuid.UUID, months, days []int, notifiedOnly bool) ([]MemberBirthdayEntry, error) {
	return many[MemberBirthdayEntry](q.Query(ctx, `SELECT m.user_id AS member_id, u.timezone AS member_timezone, m.birthday_notifications,
		b.id, b.feed_id, b.display_name, b.name_key, b.month, b.day, b.birth_year, b.show_age, b.user_id, b.photo_key,
		lu.avatar_key, b.created_at AS activated_at, b.created_at
		FROM feed_members m JOIN users u ON u.id = m.user_id AND u.deleted_at IS NULL
		JOIN feeds f ON f.id = m.feed_id AND f.deleted_at IS NULL
		JOIN feed_birthdays b ON b.feed_id = m.feed_id AND b.status = 'approved'
		LEFT JOIN users lu ON lu.id = b.user_id AND lu.deleted_at IS NULL
		WHERE ($1::uuid[] IS NULL OR m.user_id = ANY($1)) AND (NOT $4 OR m.birthday_notifications)
		AND (b.month, b.day) IN (SELECT x.m, x.d FROM unnest($2::int[], $3::int[]) AS x(m, d))
		ORDER BY m.user_id, b.created_at, b.id`, userIDs, months, days, notifiedOnly))
}

// FeedFrameZones lists the time zones of the frames with an active
// subscription to each feed (feed -> zones, one per frame).
func (s *Store) FeedFrameZones(ctx context.Context, q db.DBTX, feedIDs []uuid.UUID) (map[uuid.UUID][]string, error) {
	rows, err := q.Query(ctx, `SELECT s.feed_id, COALESCE(NULLIF(fr.settings->'locale'->>'timezone', ''), '') FROM subscriptions s
		JOIN frames fr ON fr.id = s.frame_id AND fr.deleted_at IS NULL
		WHERE s.feed_id = ANY($1) AND s.status = 'active' ORDER BY s.feed_id, s.frame_id`, feedIDs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[uuid.UUID][]string{}
	for rows.Next() {
		var f uuid.UUID
		var tz string
		if err := rows.Scan(&f, &tz); err != nil {
			return nil, err
		}
		if tz != "" {
			out[f] = append(out[f], tz)
		}
	}
	return out, rows.Err()
}

// ClaimBirthdayNotice records today's morning notice for a member; false when
// it was sent already.
func (s *Store) ClaimBirthdayNotice(ctx context.Context, userID uuid.UUID, date time.Time) (bool, error) {
	tag, err := s.Pool.Exec(ctx, `INSERT INTO birthday_notices (user_id, local_date) VALUES ($1, $2) ON CONFLICT DO NOTHING`, userID, date)
	return tag.RowsAffected() > 0, err
}

// PruneBirthdays deletes rejected suggestions after 30 days, greetings after
// 13 months and morning notices after 7 days. Returns the photo keys of the
// deleted entries.
func (s *Store) PruneBirthdays(ctx context.Context) ([]string, error) {
	rows, err := s.Pool.Query(ctx, `DELETE FROM feed_birthdays WHERE status = 'rejected' AND decided_at < now() - `+birthdayRetention+` RETURNING photo_key`)
	if err != nil {
		return nil, err
	}
	keys, err := collectKeys(rows)
	if err != nil {
		return nil, err
	}
	if _, err := s.Pool.Exec(ctx, `DELETE FROM birthday_greetings WHERE created_at < now() - interval '13 months'`); err != nil {
		return keys, err
	}
	_, err = s.Pool.Exec(ctx, `DELETE FROM birthday_notices WHERE local_date < (now() AT TIME ZONE 'UTC')::date - 7`)
	return keys, err
}
