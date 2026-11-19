package store

// Reminder candidates (README §5.20 "Reminders and their budget").

import (
	"context"
	"time"

	"github.com/google/uuid"
)

// QuietFrame is a frame whose last personal photo is older than its family's quiet threshold.
type QuietFrame struct {
	FrameID        uuid.UUID `db:"frame_id"`
	Name           string    `db:"name"`
	LastPersonalAt time.Time `db:"last_personal_at"`
	QuietAfter     int       `db:"quiet_after"`
}

// QuietFrames returns claimed frames that went quiet: no personal photo for quiet_after days (the smallest of
// their active families', else defaultDays).
func (s *Store) QuietFrames(ctx context.Context, now time.Time, defaultDays int) ([]QuietFrame, error) {
	return many[QuietFrame](s.Pool.Query(ctx, `
		SELECT x.frame_id, x.name, x.last_personal_at, x.quiet_after FROM (
			SELECT f.id AS frame_id, f.name, ff.last_personal_at,
			       coalesce((SELECT min(fe.quiet_after_days)::int FROM subscriptions su
			                 JOIN feeds fd ON fd.id = su.feed_id AND fd.deleted_at IS NULL
			                 JOIN feed_engagement fe ON fe.feed_id = su.feed_id
			                 WHERE su.frame_id = f.id AND su.status = 'active'), $2::int) AS quiet_after
			FROM frame_freshness ff JOIN frames f ON f.id = ff.frame_id AND f.deleted_at IS NULL AND f.device_id IS NOT NULL
			WHERE ff.last_personal_at IS NOT NULL
		) x WHERE x.last_personal_at <= $1::timestamptz - make_interval(days => x.quiet_after)
		ORDER BY x.last_personal_at`, now, defaultDays))
}

// QuietCandidate is someone who could be reminded about a quiet frame.
type QuietCandidate struct {
	UserID       uuid.UUID  `db:"user_id"`
	LastShare    *time.Time `db:"last_share"`
	LastReminded *time.Time `db:"last_reminded"`
}

// QuietCandidates are the people who sent to the frame since sentSince and still may (frame members with
// `send`, poster-or-above members of its active families), the one reminded longest ago (or never) first,
// then the one whose last share is oldest.
func (s *Store) QuietCandidates(ctx context.Context, frameID uuid.UUID, sentSince time.Time) ([]QuietCandidate, error) {
	return many[QuietCandidate](s.Pool.Query(ctx, `
		WITH senders AS (
			SELECT DISTINCT s.sender_user_id AS user_id FROM placements p JOIN sends s ON s.id = p.send_id
			WHERE p.frame_id = $1 AND NOT p.backfill AND p.origin IN ('send', 'feed') AND p.received_at >= $2
			  AND `+PersonalSendSQL+` AND `+creditedSendSQL+`
		), cand AS (
			SELECT m.user_id FROM memberships m
			WHERE m.frame_id = $1 AND (m.role IN ('owner', 'admin') OR coalesce((m.permissions->>'send')::boolean, false))
			UNION
			SELECT fm.user_id FROM subscriptions su
			JOIN feeds f ON f.id = su.feed_id AND f.deleted_at IS NULL
			JOIN feed_members fm ON fm.feed_id = su.feed_id AND fm.role IN ('owner', 'admin', 'poster')
			WHERE su.frame_id = $1 AND su.status = 'active'
		)
		SELECT x.user_id,
		       (SELECT max(s.created_at) FROM sends s WHERE s.sender_user_id = x.user_id AND `+PersonalSendSQL+`) AS last_share,
		       (SELECT max(n.sent_at) FROM nudges n WHERE n.user_id = x.user_id AND n.frame_id = $1 AND n.kind = 'reminder_quiet') AS last_reminded
		FROM senders x JOIN cand c ON c.user_id = x.user_id JOIN users u ON u.id = x.user_id AND u.deleted_at IS NULL
		ORDER BY last_reminded ASC NULLS FIRST, last_share ASC NULLS FIRST, x.user_id`, frameID, sentSince))
}

// UpcomingBirthday is an approved birthday person of a family and the members who may be reminded.
type UpcomingBirthday struct {
	ID          uuid.UUID  `db:"id"`
	FeedID      uuid.UUID  `db:"feed_id"`
	FeedName    string     `db:"feed_name"`
	DisplayName string     `db:"display_name"`
	Month       int        `db:"month"`
	Day         int        `db:"day"`
	UserID      *uuid.UUID `db:"user_id"`
}

// UpcomingBirthdays returns approved birthdays falling on one of these month/day pairs.
func (s *Store) UpcomingBirthdays(ctx context.Context, months, days []int) ([]UpcomingBirthday, error) {
	return many[UpcomingBirthday](s.Pool.Query(ctx, `SELECT b.id, b.feed_id, f.name AS feed_name, b.display_name, b.month::int AS month,
		b.day::int AS day, b.user_id FROM feed_birthdays b JOIN feeds f ON f.id = b.feed_id AND f.deleted_at IS NULL
		JOIN unnest($1::int[], $2::int[]) AS d(m, dd) ON d.m = b.month AND d.dd = b.day
		WHERE b.status = 'approved' ORDER BY b.id`, months, days))
}

// BirthdayPosters are the family's poster-or-above members with birthday notifications on who posted to it
// since `since` (not the birthday person), most posts first.
func (s *Store) BirthdayPosters(ctx context.Context, feedID uuid.UUID, exclude *uuid.UUID, since time.Time, limit int) ([]uuid.UUID, error) {
	rows, err := many[idRow](s.Pool.Query(ctx, `SELECT fm.user_id AS id FROM feed_members fm
		JOIN users u ON u.id = fm.user_id AND u.deleted_at IS NULL
		JOIN feed_posts p ON p.feed_id = fm.feed_id AND p.poster_user_id = fm.user_id AND p.removed_at IS NULL AND p.created_at >= $3
		WHERE fm.feed_id = $1 AND fm.birthday_notifications AND fm.role IN ('owner', 'admin', 'poster')
		  AND ($2::uuid IS NULL OR fm.user_id <> $2)
		GROUP BY fm.user_id ORDER BY count(*) DESC, fm.user_id LIMIT $4`, feedID, exclude, since, limit))
	out := make([]uuid.UUID, len(rows))
	for i, r := range rows {
		out[i] = r.ID
	}
	return out, err
}

// CountDedupe counts the nudges with this dedupe key (all users).
func (s *Store) CountDedupe(ctx context.Context, key string) (int, error) {
	var n int
	err := s.Pool.QueryRow(ctx, `SELECT count(*) FROM nudges WHERE dedupe_key = $1`, key).Scan(&n)
	return n, err
}
