package store

// Encouragement to share (docs/spec/family-social.md §9, README §5.20): freshness, "Ask for new photos",
// reminder preferences and the nudge log. Streaks, prompts, summaries and milestones are in
// engagement_game.go.

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Change feed kinds of the engagement area (README §5.20).
const (
	FeedComments     = "comments"      // entity: the placement
	FeedPhotoRequest = "photo_request" // entity: the frame
	FeedPrompts      = "prompts"       // entity: the frame
)

// PersonalSendSQL is true for a send made by a person (README §5.20): the apps, email and Telegram; not photo
// sources, Google imports or frame imports. `s` is the sends row.
const PersonalSendSQL = `(s.via IS NULL OR s.via IN ('email', 'telegram'))`

// creditedSendSQL is true when the send's person is its sender: not an outside contribution (Telegram sent by
// the chat's owner for someone else, mail delivered on behalf of an unknown sender).
const creditedSendSQL = `(NOT s.telegram_outside AND NOT EXISTS (SELECT 1 FROM send_items csi JOIN items ci ON ci.id = csi.item_id
	WHERE csi.send_id = s.id AND ci.via_email_address_id IS NOT NULL))`

// Person is the little a PersonRef needs.
type Person struct {
	UserID      uuid.UUID  `db:"user_id"`
	DisplayName string     `db:"display_name"`
	AvatarKey   *string    `db:"avatar_key"`
	DeletedAt   *time.Time `db:"deleted_at"`
}

// People returns these users (deleted ones too, as former members).
func (s *Store) People(ctx context.Context, q db.DBTX, ids []uuid.UUID) (map[uuid.UUID]Person, error) {
	out := map[uuid.UUID]Person{}
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := many[Person](q.Query(ctx, `SELECT id AS user_id, display_name, avatar_key, deleted_at FROM users WHERE id = ANY($1)`, ids))
	for _, p := range rows {
		out[p.UserID] = p
	}
	return out, err
}

// ---------------------------------------------------------------- freshness

// FreshnessRow is what a frame's or feed's freshness ring is computed from.
type FreshnessRow struct {
	ID             uuid.UUID  `db:"id"`
	LastPersonalAt *time.Time `db:"last_personal_at"`
	LastSourceAt   *time.Time `db:"last_source_at"`
	UserID         *uuid.UUID `db:"user_id"`
	QuietAfter     *int       `db:"quiet_after"` // frames: smallest of the active subscriptions' families
}

// FrameFreshness returns the freshness rows of these frames (frames without a row have none).
func (s *Store) FrameFreshness(ctx context.Context, q db.DBTX, frameIDs []uuid.UUID) (map[uuid.UUID]FreshnessRow, error) {
	rows, err := many[FreshnessRow](q.Query(ctx, `
		SELECT x.id, ff.last_personal_at, ff.last_source_at, ff.last_personal_user_id AS user_id,
		       (SELECT min(fe.quiet_after_days)::int FROM subscriptions su
		        JOIN feeds f ON f.id = su.feed_id AND f.deleted_at IS NULL
		        JOIN feed_engagement fe ON fe.feed_id = su.feed_id
		        WHERE su.frame_id = x.id AND su.status = 'active') AS quiet_after
		FROM unnest($1::uuid[]) AS x(id) LEFT JOIN frame_freshness ff ON ff.frame_id = x.id`, frameIDs))
	out := make(map[uuid.UUID]FreshnessRow, len(rows))
	for _, r := range rows {
		out[r.ID] = r
	}
	return out, err
}

// FeedFreshness returns the freshness rows of these feeds with their quiet_after_days.
func (s *Store) FeedFreshness(ctx context.Context, q db.DBTX, feedIDs []uuid.UUID) (map[uuid.UUID]FreshnessRow, error) {
	rows, err := many[FreshnessRow](q.Query(ctx, `
		SELECT x.id, ff.last_personal_at, ff.last_source_at, ff.last_personal_user_id AS user_id,
		       fe.quiet_after_days::int AS quiet_after
		FROM unnest($1::uuid[]) AS x(id) LEFT JOIN feed_freshness ff ON ff.feed_id = x.id
		LEFT JOIN feed_engagement fe ON fe.feed_id = x.id`, feedIDs))
	out := make(map[uuid.UUID]FreshnessRow, len(rows))
	for _, r := range rows {
		out[r.ID] = r
	}
	return out, err
}

// ---------------------------------------------------------------- photo requests

// PhotoRequest is one frame's "Ask for new photos".
type PhotoRequest struct {
	ID              uuid.UUID   `db:"id"`
	FrameID         uuid.UUID   `db:"frame_id"`
	FrameName       string      `db:"frame_name"`
	State           string      `db:"state"`
	CreatedAt       time.Time   `db:"created_at"`
	ExpiresAt       time.Time   `db:"expires_at"`
	AnsweredAt      *time.Time  `db:"answered_at"`
	AnsweredBy      *uuid.UUID  `db:"answered_by_user_id"`
	AnsweredSendID  *uuid.UUID  `db:"answered_send_id"`
	AnsweredLabel   *string     `db:"answered_label"` // the send's credited name (outside contributions)
	NotifiedUserIDs []uuid.UUID `db:"notified_user_ids"`
}

const photoRequestSelect = `SELECT r.id, r.frame_id, f.name AS frame_name, r.state, r.created_at, r.expires_at, r.answered_at,
	r.answered_by_user_id, r.answered_send_id, s.via_label AS answered_label, r.notified_user_ids
	FROM photo_requests r JOIN frames f ON f.id = r.frame_id LEFT JOIN sends s ON s.id = r.answered_send_id`

// PhotoRequestValidity is how long a request stays open.
const PhotoRequestValidity = 7 * 24 * time.Hour

// GetPhotoRequest returns one request.
func (s *Store) GetPhotoRequest(ctx context.Context, q db.DBTX, id uuid.UUID) (*PhotoRequest, error) {
	return one[PhotoRequest](q.Query(ctx, photoRequestSelect+` WHERE r.id = $1 AND f.deleted_at IS NULL`, id))
}

// LatestPhotoRequest returns the frame's newest request created at or after since.
func (s *Store) LatestPhotoRequest(ctx context.Context, q db.DBTX, frameID uuid.UUID, since time.Time) (*PhotoRequest, error) {
	return one[PhotoRequest](q.Query(ctx, photoRequestSelect+` WHERE r.frame_id = $1 AND r.created_at >= $2
		ORDER BY r.created_at DESC LIMIT 1`, frameID, since))
}

// ListPhotoRequests returns the frame's requests created at or after since, newest first.
func (s *Store) ListPhotoRequests(ctx context.Context, q db.DBTX, frameID uuid.UUID, since time.Time) ([]PhotoRequest, error) {
	return many[PhotoRequest](q.Query(ctx, photoRequestSelect+` WHERE r.frame_id = $1 AND r.created_at >= $2
		ORDER BY r.created_at DESC`, frameID, since))
}

// CurrentPhotoRequests returns, per frame, the open request or one answered since answeredSince.
func (s *Store) CurrentPhotoRequests(ctx context.Context, q db.DBTX, frameIDs []uuid.UUID, answeredSince time.Time) (map[uuid.UUID]PhotoRequest, error) {
	rows, err := many[PhotoRequest](q.Query(ctx, photoRequestSelect+` WHERE r.frame_id = ANY($1)
		AND (r.state = 'open' OR (r.state = 'answered' AND r.answered_at >= $2)) ORDER BY r.created_at`, frameIDs, answeredSince))
	out := map[uuid.UUID]PhotoRequest{}
	for _, r := range rows {
		out[r.FrameID] = r // the newest wins
	}
	return out, err
}

// CreatePhotoRequest opens a request; an older open one (more than a day old) expires first.
func (tx *Tx) CreatePhotoRequest(ctx context.Context, id, frameID uuid.UUID, now time.Time) error {
	if _, err := tx.Exec(ctx, `UPDATE photo_requests SET state = 'expired' WHERE frame_id = $1 AND state = 'open'`, frameID); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `INSERT INTO photo_requests (id, frame_id, created_at, expires_at) VALUES ($1, $2, $3, $4)`,
		id, frameID, now, now.Add(PhotoRequestValidity))
	return err
}

// AddPhotoRequestNotified records who got the frame_request push.
func (s *Store) AddPhotoRequestNotified(ctx context.Context, id uuid.UUID, users []uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `UPDATE photo_requests SET notified_user_ids = ARRAY(
		SELECT DISTINCT u FROM unnest(notified_user_ids || $2::uuid[]) AS u) WHERE id = $1`, id, users)
	return err
}

// OpenPhotoRequests returns every open request (the scheduler holds pushes in quiet hours).
func (s *Store) OpenPhotoRequests(ctx context.Context) ([]PhotoRequest, error) {
	return many[PhotoRequest](s.Pool.Query(ctx, photoRequestSelect+` WHERE r.state = 'open' AND f.deleted_at IS NULL ORDER BY r.created_at`))
}

// RequestRecipient is a candidate for a frame's frame_request push.
type RequestRecipient struct {
	UserID uuid.UUID `db:"user_id"`
	Sent   bool      `db:"sent"` // sent something that reached the frame since the cut-off
}

// PhotoRequestCandidates are the frame's members with `send` and the poster-or-above members of families
// with an active subscription to it; Sent marks who sent anything that reached the frame since sentSince.
func (s *Store) PhotoRequestCandidates(ctx context.Context, q db.DBTX, frameID uuid.UUID, sentSince time.Time) ([]RequestRecipient, error) {
	return many[RequestRecipient](q.Query(ctx, `
		WITH cand AS (
			SELECT m.user_id FROM memberships m
			WHERE m.frame_id = $1 AND (m.role IN ('owner', 'admin') OR coalesce((m.permissions->>'send')::boolean, false))
			UNION
			SELECT fm.user_id FROM subscriptions su
			JOIN feeds f ON f.id = su.feed_id AND f.deleted_at IS NULL
			JOIN feed_members fm ON fm.feed_id = su.feed_id AND fm.role IN ('owner', 'admin', 'poster')
			WHERE su.frame_id = $1 AND su.status = 'active'
		), senders AS (
			SELECT DISTINCT s.sender_user_id AS user_id FROM placements p JOIN sends s ON s.id = p.send_id
			WHERE p.frame_id = $1 AND NOT p.backfill AND p.origin IN ('send', 'feed') AND p.received_at >= $2
			  AND `+PersonalSendSQL+` AND `+creditedSendSQL+`
		)
		SELECT c.user_id, EXISTS (SELECT 1 FROM senders x WHERE x.user_id = c.user_id) AS sent
		FROM cand c JOIN users u ON u.id = c.user_id AND u.deleted_at IS NULL
		ORDER BY c.user_id`, frameID, sentSince))
}

// SendCredit is who a personal send is credited to.
type SendCredit struct {
	SendID   uuid.UUID  `db:"send_id"`
	Sender   uuid.UUID  `db:"sender_user_id"`
	Personal bool       `db:"personal"`
	Credited bool       `db:"credited"` // false: an outside contribution
	Label    *string    `db:"via_label"`
	At       time.Time  `db:"created_at"`
	Via      *string    `db:"via"`
	Chat     *uuid.UUID `db:"telegram_chat_id"`
}

// GetSendCredit returns a send's credit.
func (s *Store) GetSendCredit(ctx context.Context, q db.DBTX, sendID uuid.UUID) (*SendCredit, error) {
	return one[SendCredit](q.Query(ctx, `SELECT s.id AS send_id, s.sender_user_id, `+PersonalSendSQL+` AS personal,
		`+creditedSendSQL+` AS credited, s.via_label, s.created_at, s.via, s.telegram_chat_id FROM sends s WHERE s.id = $1`, sendID))
}

// AnswerPhotoRequests answers the open requests of every frame that got a non-backfill placement of the send
// after the request was made. Returns the answered requests.
func (s *Store) AnswerPhotoRequests(ctx context.Context, sendID uuid.UUID, by *uuid.UUID, now time.Time) ([]PhotoRequest, error) {
	ids, err := many[idRow](s.Pool.Query(ctx, `
		UPDATE photo_requests r SET state = 'answered', answered_at = $3, answered_by_user_id = $2, answered_send_id = $1
		WHERE r.state = 'open' AND EXISTS (
			SELECT 1 FROM placements p WHERE p.send_id = $1 AND p.frame_id = r.frame_id AND NOT p.backfill
			  AND p.origin IN ('send', 'feed') AND p.received_at >= r.created_at)
		RETURNING r.id`, sendID, by, now))
	if err != nil || len(ids) == 0 {
		return nil, err
	}
	list := make([]uuid.UUID, len(ids))
	for i, r := range ids {
		list[i] = r.ID
	}
	return many[PhotoRequest](s.Pool.Query(ctx, photoRequestSelect+` WHERE r.id = ANY($1) ORDER BY r.created_at`, list))
}

type idRow struct {
	ID uuid.UUID `db:"id"`
}

// ExpirePhotoRequests closes open requests past their expiry and returns them.
func (s *Store) ExpirePhotoRequests(ctx context.Context, now time.Time) ([]PhotoRequest, error) {
	ids, err := many[idRow](s.Pool.Query(ctx, `UPDATE photo_requests SET state = 'expired'
		WHERE state = 'open' AND expires_at <= $1 RETURNING id`, now))
	if err != nil || len(ids) == 0 {
		return nil, err
	}
	list := make([]uuid.UUID, len(ids))
	for i, r := range ids {
		list[i] = r.ID
	}
	return many[PhotoRequest](s.Pool.Query(ctx, photoRequestSelect+` WHERE r.id = ANY($1) ORDER BY r.created_at`, list))
}

// ---------------------------------------------------------------- reminder preferences

// ReminderPrefs is a user's reminder preferences (defaults when they have no row).
type ReminderPrefs struct {
	UserID             uuid.UUID  `db:"user_id"`
	Level              string     `db:"level"`
	LevelAuto          bool       `db:"level_auto"`
	AutoReducedSeenAt  *time.Time `db:"auto_reduced_seen_at"`
	FrameRequests      bool       `db:"frame_requests"`
	WeeklySummary      bool       `db:"weekly_summary"`
	BirthdayReminders  bool       `db:"birthday_reminders"`
	SnoozedUntil       *time.Time `db:"snoozed_until"`
	QuietStart         string     `db:"quiet_start"` // HH:MM
	QuietEnd           string     `db:"quiet_end"`
	UnansweredInRow    int        `db:"unanswered_in_row"`
	EmailDigest        string     `db:"email_digest"`
	EmailDigestSentAt  *time.Time `db:"email_digest_sent_at"`
	Timezone           *string    `db:"timezone"` // users.timezone
	Locale             *string    `db:"locale"`   // users.locale
	DisplayName        string     `db:"display_name"`
	Email              *string    `db:"email"`
	EmailVerified      bool       `db:"email_verified"`
	DeletedAt          *time.Time `db:"deleted_at"`
	HasPreferencesRow  bool       `db:"has_row"`
	PushEndpointsCount int        `db:"push_endpoints"`
}

const reminderPrefsSelect = `SELECT u.id AS user_id, coalesce(r.level, 'normal') AS level, coalesce(r.level_auto, false) AS level_auto,
	r.auto_reduced_seen_at, coalesce(r.frame_requests, true) AS frame_requests, coalesce(r.weekly_summary, true) AS weekly_summary,
	coalesce(r.birthday_reminders, true) AS birthday_reminders, r.snoozed_until,
	to_char(coalesce(r.quiet_start, '21:00'::time), 'HH24:MI') AS quiet_start, to_char(coalesce(r.quiet_end, '09:00'::time), 'HH24:MI') AS quiet_end,
	coalesce(r.unanswered_in_row, 0) AS unanswered_in_row, coalesce(r.email_digest, 'off') AS email_digest, r.email_digest_sent_at,
	u.timezone, u.locale, u.display_name, u.email, u.email_verified, u.deleted_at, (r.user_id IS NOT NULL) AS has_row,
	(SELECT count(*)::int FROM push_endpoints pe WHERE pe.user_id = u.id) AS push_endpoints
	FROM users u LEFT JOIN reminder_preferences r ON r.user_id = u.id`

// GetReminderPrefs returns one user's preferences.
func (s *Store) GetReminderPrefs(ctx context.Context, q db.DBTX, userID uuid.UUID) (*ReminderPrefs, error) {
	return one[ReminderPrefs](q.Query(ctx, reminderPrefsSelect+` WHERE u.id = $1`, userID))
}

// ReminderPrefsOf returns the preferences of these users (deleted users are left out).
func (s *Store) ReminderPrefsOf(ctx context.Context, q db.DBTX, userIDs []uuid.UUID) (map[uuid.UUID]ReminderPrefs, error) {
	out := map[uuid.UUID]ReminderPrefs{}
	if len(userIDs) == 0 {
		return out, nil
	}
	rows, err := many[ReminderPrefs](q.Query(ctx, reminderPrefsSelect+` WHERE u.id = ANY($1) AND u.deleted_at IS NULL`, userIDs))
	for _, r := range rows {
		out[r.UserID] = r
	}
	return out, err
}

// ReminderPrefsPatch changes preferences; nil fields stay.
type ReminderPrefsPatch struct {
	Level           *string
	LevelAuto       *bool
	AutoReducedSeen *time.Time
	FrameRequests   *bool
	WeeklySummary   *bool
	Birthdays       *bool
	SnoozedUntil    **time.Time
	QuietStart      *string
	QuietEnd        *string
	EmailDigest     *string
	Unanswered      *int
	DigestSentAt    *time.Time
}

// UpdateReminderPrefs upserts the user's row with the patch.
func (s *Store) UpdateReminderPrefs(ctx context.Context, q db.DBTX, userID uuid.UUID, p ReminderPrefsPatch) error {
	var snoozeSet bool
	var snooze *time.Time
	if p.SnoozedUntil != nil {
		snoozeSet, snooze = true, *p.SnoozedUntil
	}
	_, err := q.Exec(ctx, `
		INSERT INTO reminder_preferences AS r (user_id) VALUES ($1) ON CONFLICT (user_id) DO NOTHING;`, userID)
	if err != nil {
		return err
	}
	_, err = q.Exec(ctx, `UPDATE reminder_preferences SET
		level = coalesce($2, level), level_auto = coalesce($3, level_auto),
		auto_reduced_seen_at = coalesce($4, auto_reduced_seen_at),
		frame_requests = coalesce($5, frame_requests), weekly_summary = coalesce($6, weekly_summary),
		birthday_reminders = coalesce($7, birthday_reminders),
		snoozed_until = CASE WHEN $8 THEN $9 ELSE snoozed_until END,
		quiet_start = coalesce($10::time, quiet_start), quiet_end = coalesce($11::time, quiet_end),
		email_digest = coalesce($12, email_digest), unanswered_in_row = coalesce($13, unanswered_in_row),
		email_digest_sent_at = coalesce($14, email_digest_sent_at),
		updated_at = now()
		WHERE user_id = $1`, userID, p.Level, p.LevelAuto, p.AutoReducedSeen, p.FrameRequests, p.WeeklySummary, p.Birthdays,
		snoozeSet, snooze, p.QuietStart, p.QuietEnd, p.EmailDigest, p.Unanswered, p.DigestSentAt)
	return err
}

// ---------------------------------------------------------------- nudges

// Nudge is one reminder or request push in the log.
type Nudge struct {
	ID             uuid.UUID  `db:"id"`
	UserID         uuid.UUID  `db:"user_id"`
	Kind           string     `db:"kind"`
	FrameID        *uuid.UUID `db:"frame_id"`
	FeedID         *uuid.UUID `db:"feed_id"`
	SubjectID      *uuid.UUID `db:"subject_id"`
	DedupeKey      string     `db:"dedupe_key"`
	CountsInBudget bool       `db:"counts_in_budget"`
	SentAt         time.Time  `db:"sent_at"`
	Outcome        string     `db:"outcome"`
}

// InsertNudge records a nudge; false when its dedupe key was used for the user already.
func (s *Store) InsertNudge(ctx context.Context, n Nudge) (bool, error) {
	tag, err := s.Pool.Exec(ctx, `INSERT INTO nudges (id, user_id, kind, frame_id, feed_id, subject_id, dedupe_key, counts_in_budget, sent_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) ON CONFLICT (user_id, dedupe_key) DO NOTHING`,
		n.ID, n.UserID, n.Kind, n.FrameID, n.FeedID, n.SubjectID, n.DedupeKey, n.CountsInBudget, n.SentAt)
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() == 1, nil
}

// BudgetUsed counts the user's budgeted nudges sent at or after since.
func (s *Store) BudgetUsed(ctx context.Context, userID uuid.UUID, since time.Time) (int, error) {
	var n int
	err := s.Pool.QueryRow(ctx, `SELECT count(*) FROM nudges WHERE user_id = $1 AND counts_in_budget AND sent_at >= $2`, userID, since).Scan(&n)
	return n, err
}

// LastNudge returns the newest nudge matching kind and the optional frame, feed or user, sent at or after since.
func (s *Store) LastNudge(ctx context.Context, kind string, frameID, feedID, userID *uuid.UUID, since time.Time) (*Nudge, error) {
	return one[Nudge](s.Pool.Query(ctx, `SELECT id, user_id, kind, frame_id, feed_id, subject_id, dedupe_key, counts_in_budget, sent_at, outcome
		FROM nudges WHERE kind = $1 AND ($2::uuid IS NULL OR frame_id = $2) AND ($3::uuid IS NULL OR feed_id = $3)
		AND ($4::uuid IS NULL OR user_id = $4) AND sent_at >= $5 ORDER BY sent_at DESC LIMIT 1`, kind, frameID, feedID, userID, since))
}

// DedupeUsed reports whether anyone got a nudge with this dedupe key.
func (s *Store) DedupeUsed(ctx context.Context, key string) (bool, error) {
	var ok bool
	err := s.Pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM nudges WHERE dedupe_key = $1)`, key).Scan(&ok)
	return ok, err
}

// NudgesShared marks the user's pending nudges of the last 24 hours as answered by a share and resets the
// unanswered counter.
func (s *Store) NudgesShared(ctx context.Context, userID uuid.UUID, at time.Time) (int64, error) {
	tag, err := s.Pool.Exec(ctx, `UPDATE nudges SET outcome = 'shared', outcome_at = $2
		WHERE user_id = $1 AND outcome = 'pending' AND sent_at >= $2::timestamptz - interval '24 hours' AND sent_at <= $2::timestamptz`, userID, at)
	if err != nil {
		return 0, err
	}
	if tag.RowsAffected() > 0 {
		_, err = s.Pool.Exec(ctx, `UPDATE reminder_preferences SET unanswered_in_row = 0, updated_at = now() WHERE user_id = $1`, userID)
	}
	return tag.RowsAffected(), err
}

// IgnoredNudge is a reminder nobody answered within 24 hours.
type IgnoredNudge struct {
	UserID uuid.UUID `db:"user_id"`
	Count  int       `db:"count"`
}

// CloseIgnoredNudges sets outcome 'ignored' on pending nudges sent before cutoff and returns, per user, how many
// of the kinds that count towards the automatic lowering were closed.
func (s *Store) CloseIgnoredNudges(ctx context.Context, cutoff, now time.Time, counted []string) ([]IgnoredNudge, error) {
	return many[IgnoredNudge](s.Pool.Query(ctx, `
		WITH closed AS (
			UPDATE nudges SET outcome = 'ignored', outcome_at = $2 WHERE outcome = 'pending' AND sent_at < $1
			RETURNING user_id, kind
		)
		SELECT user_id, count(*)::int AS count FROM closed WHERE kind = ANY($3) GROUP BY user_id ORDER BY user_id`, cutoff, now, counted))
}

// AddUnanswered adds n to the user's unanswered reminders and lowers the level to fewer once it reaches limit
// (only from normal). Returns true when the level was lowered now.
func (s *Store) AddUnanswered(ctx context.Context, userID uuid.UUID, n, limit int) (bool, error) {
	if _, err := s.Pool.Exec(ctx, `INSERT INTO reminder_preferences (user_id) VALUES ($1) ON CONFLICT DO NOTHING`, userID); err != nil {
		return false, err
	}
	var lowered bool
	err := s.Pool.QueryRow(ctx, `UPDATE reminder_preferences SET unanswered_in_row = unanswered_in_row + $2,
		level = CASE WHEN level = 'normal' AND unanswered_in_row + $2 >= $3 THEN 'fewer' ELSE level END,
		level_auto = CASE WHEN level = 'normal' AND unanswered_in_row + $2 >= $3 THEN true ELSE level_auto END,
		auto_reduced_seen_at = CASE WHEN level = 'normal' AND unanswered_in_row + $2 >= $3 THEN NULL ELSE auto_reduced_seen_at END,
		updated_at = now()
		WHERE user_id = $1 RETURNING (level = 'fewer' AND level_auto AND auto_reduced_seen_at IS NULL AND unanswered_in_row >= $3
			AND unanswered_in_row - $2 < $3)`, userID, n, limit).Scan(&lowered)
	if err == pgx.ErrNoRows {
		return false, nil
	}
	return lowered, err
}
