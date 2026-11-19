package store

// Encouragement to share, the family side (README §5.20): engagement settings, weekly streaks, prompts,
// summaries, recaps and personal milestones.

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// FeedEngagement is a family's engagement settings.
type FeedEngagement struct {
	FeedID         uuid.UUID `db:"feed_id"`
	StreakEnabled  bool      `db:"streak_enabled"`
	StreakGoal     int       `db:"streak_goal"`
	PromptsEnabled bool      `db:"prompts_enabled"`
	QuietAfterDays int       `db:"quiet_after_days"`
	MonthlyRecap   bool      `db:"monthly_recap"`
	Timezone       *string   `db:"timezone"`
	// Derived zone inputs (README §5.20 "Family streak").
	FrameZone   *string    `db:"frame_zone"` // the most common locale.timezone of its active subscriptions' frames
	OwnerZone   *string    `db:"owner_zone"`
	FeedName    string     `db:"feed_name"`
	OwnerID     *uuid.UUID `db:"owner_id"`
	OwnerLocale *string    `db:"owner_locale"`
}

const feedEngagementSelect = `SELECT fe.feed_id, fe.streak_enabled, fe.streak_goal::int AS streak_goal, fe.prompts_enabled,
	fe.quiet_after_days::int AS quiet_after_days, fe.monthly_recap, fe.timezone,
	(SELECT fr.settings->'locale'->>'timezone' FROM subscriptions su JOIN frames fr ON fr.id = su.frame_id AND fr.deleted_at IS NULL
	 WHERE su.feed_id = fe.feed_id AND su.status = 'active' AND coalesce(fr.settings->'locale'->>'timezone', '') <> ''
	 GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT 1) AS frame_zone,
	(SELECT u.timezone FROM feed_members o JOIN users u ON u.id = o.user_id WHERE o.feed_id = fe.feed_id AND o.role = 'owner') AS owner_zone,
	f.name AS feed_name,
	(SELECT o.user_id FROM feed_members o WHERE o.feed_id = fe.feed_id AND o.role = 'owner') AS owner_id,
	(SELECT u.locale FROM feed_members o JOIN users u ON u.id = o.user_id WHERE o.feed_id = fe.feed_id AND o.role = 'owner') AS owner_locale
	FROM feed_engagement fe JOIN feeds f ON f.id = fe.feed_id AND f.deleted_at IS NULL`

// GetFeedEngagement returns a family's settings (the row exists for every feed).
func (s *Store) GetFeedEngagement(ctx context.Context, q db.DBTX, feedID uuid.UUID) (*FeedEngagement, error) {
	if _, err := q.Exec(ctx, `INSERT INTO feed_engagement (feed_id) SELECT id FROM feeds WHERE id = $1 ON CONFLICT DO NOTHING`, feedID); err != nil {
		return nil, err
	}
	return one[FeedEngagement](q.Query(ctx, feedEngagementSelect+` WHERE fe.feed_id = $1`, feedID))
}

// FeedEngagements returns the settings of these feeds.
func (s *Store) FeedEngagements(ctx context.Context, q db.DBTX, feedIDs []uuid.UUID) (map[uuid.UUID]FeedEngagement, error) {
	rows, err := many[FeedEngagement](q.Query(ctx, feedEngagementSelect+` WHERE fe.feed_id = ANY($1)`, feedIDs))
	out := make(map[uuid.UUID]FeedEngagement, len(rows))
	for _, r := range rows {
		out[r.FeedID] = r
	}
	return out, err
}

// AllFeedEngagements returns every live feed's settings (the scheduler).
func (s *Store) AllFeedEngagements(ctx context.Context) ([]FeedEngagement, error) {
	return many[FeedEngagement](s.Pool.Query(ctx, feedEngagementSelect+` ORDER BY fe.feed_id`))
}

// FeedEngagementPatch changes settings; nil fields stay. ClearTimezone goes back to the derived zone.
type FeedEngagementPatch struct {
	StreakEnabled  *bool
	StreakGoal     *int
	PromptsEnabled *bool
	QuietAfterDays *int
	MonthlyRecap   *bool
	Timezone       *string
	ClearTimezone  bool
}

// UpdateFeedEngagement applies a patch.
func (s *Store) UpdateFeedEngagement(ctx context.Context, q db.DBTX, feedID uuid.UUID, p FeedEngagementPatch) error {
	_, err := q.Exec(ctx, `UPDATE feed_engagement SET streak_enabled = coalesce($2, streak_enabled),
		streak_goal = coalesce($3, streak_goal), prompts_enabled = coalesce($4, prompts_enabled),
		quiet_after_days = coalesce($5, quiet_after_days), monthly_recap = coalesce($6, monthly_recap),
		timezone = CASE WHEN $8 THEN NULL ELSE coalesce($7, timezone) END, updated_at = now()
		WHERE feed_id = $1`, feedID, p.StreakEnabled, p.StreakGoal, p.PromptsEnabled, p.QuietAfterDays, p.MonthlyRecap, p.Timezone, p.ClearTimezone)
	return err
}

// ---------------------------------------------------------------- streaks

// WeekCount is what a family posted in one week: live items of personal posts and who posted them.
type WeekCount struct {
	Photos  int
	Helpers []uuid.UUID
}

// CountWeek counts the family's personal posts' live items made in [from, to).
func (s *Store) CountWeek(ctx context.Context, q db.DBTX, feedID uuid.UUID, from, to time.Time) (WeekCount, error) {
	var out WeekCount
	err := q.QueryRow(ctx, `SELECT count(pi.item_id)::int FROM feed_posts p JOIN sends s ON s.id = p.send_id
		JOIN feed_post_items pi ON pi.post_id = p.id AND pi.removed_at IS NULL
		WHERE p.feed_id = $1 AND p.removed_at IS NULL AND p.created_at >= $2 AND p.created_at < $3 AND `+PersonalSendSQL,
		feedID, from, to).Scan(&out.Photos)
	if err != nil {
		return out, err
	}
	rows, err := many[idRow](q.Query(ctx, `SELECT DISTINCT p.poster_user_id AS id FROM feed_posts p JOIN sends s ON s.id = p.send_id
		JOIN users u ON u.id = p.poster_user_id AND u.deleted_at IS NULL
		WHERE p.feed_id = $1 AND p.removed_at IS NULL AND p.created_at >= $2 AND p.created_at < $3
		  AND `+PersonalSendSQL+` AND `+creditedSendSQL+` ORDER BY 1`, feedID, from, to))
	for _, r := range rows {
		out.Helpers = append(out.Helpers, r.ID)
	}
	return out, err
}

// Streak is a family's running streak.
type Streak struct {
	FeedID             uuid.UUID  `db:"feed_id"`
	CurrentWeeks       int        `db:"current_weeks"`
	BestWeeks          int        `db:"best_weeks"`
	FreezesLeft        int        `db:"freezes_left"`
	FreezeGrantedMonth *time.Time `db:"freeze_granted_month"`
	WeekOf             time.Time  `db:"week_of"`
	LastMilestoneWeeks int        `db:"last_milestone_weeks"`
}

const streakCols = `feed_id, current_weeks, best_weeks, freezes_left::int AS freezes_left, freeze_granted_month, week_of, last_milestone_weeks`

// GetStreak returns the running streak, or ErrNotFound before the family's first week.
func (s *Store) GetStreak(ctx context.Context, q db.DBTX, feedID uuid.UUID) (*Streak, error) {
	return one[Streak](q.Query(ctx, `SELECT `+streakCols+` FROM feed_streaks WHERE feed_id = $1`, feedID))
}

// Streaks returns the streaks of these feeds.
func (s *Store) Streaks(ctx context.Context, q db.DBTX, feedIDs []uuid.UUID) (map[uuid.UUID]Streak, error) {
	rows, err := many[Streak](q.Query(ctx, `SELECT `+streakCols+` FROM feed_streaks WHERE feed_id = ANY($1)`, feedIDs))
	out := make(map[uuid.UUID]Streak, len(rows))
	for _, r := range rows {
		out[r.FeedID] = r
	}
	return out, err
}

// LockStreak creates the streak row (week of weekOf, one freeze granted for month) if needed and locks it.
func (tx *Tx) LockStreak(ctx context.Context, feedID uuid.UUID, weekOf, month time.Time) (*Streak, error) {
	if _, err := tx.Exec(ctx, `INSERT INTO feed_streaks (feed_id, week_of, freeze_granted_month) VALUES ($1, $2, $3)
		ON CONFLICT (feed_id) DO NOTHING`, feedID, weekOf, month); err != nil {
		return nil, err
	}
	return one[Streak](tx.Query(ctx, `SELECT `+streakCols+` FROM feed_streaks WHERE feed_id = $1 FOR UPDATE`, feedID))
}

// SaveStreak writes the running streak.
func (tx *Tx) SaveStreak(ctx context.Context, st *Streak) error {
	_, err := tx.Exec(ctx, `UPDATE feed_streaks SET current_weeks = $2, best_weeks = $3, freezes_left = $4,
		freeze_granted_month = $5, week_of = $6, last_milestone_weeks = $7, updated_at = now() WHERE feed_id = $1`,
		st.FeedID, st.CurrentWeeks, st.BestWeeks, st.FreezesLeft, st.FreezeGrantedMonth, st.WeekOf, st.LastMilestoneWeeks)
	return err
}

// StreakWeek is one row of a family's weeks.
type StreakWeek struct {
	WeekOf  time.Time `db:"week_of"`
	Photos  int       `db:"photos"`
	Goal    int       `db:"goal"`
	Outcome string    `db:"outcome"`
}

// PutStreakWeek records a week's count, goal and outcome, and replaces its helpers.
func (s *Store) PutStreakWeek(ctx context.Context, q db.DBTX, feedID uuid.UUID, w StreakWeek, helpers []uuid.UUID) error {
	if _, err := q.Exec(ctx, `INSERT INTO feed_streak_weeks (feed_id, week_of, photos, goal, outcome) VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (feed_id, week_of) DO UPDATE SET photos = EXCLUDED.photos, goal = EXCLUDED.goal, outcome = EXCLUDED.outcome`,
		feedID, w.WeekOf, w.Photos, w.Goal, w.Outcome); err != nil {
		return err
	}
	if _, err := q.Exec(ctx, `DELETE FROM feed_streak_helpers WHERE feed_id = $1 AND week_of = $2 AND NOT (user_id = ANY($3))`,
		feedID, w.WeekOf, helpers); err != nil {
		return err
	}
	_, err := q.Exec(ctx, `INSERT INTO feed_streak_helpers (feed_id, week_of, user_id) SELECT $1, $2, u FROM unnest($3::uuid[]) AS u
		JOIN users x ON x.id = u ON CONFLICT DO NOTHING`, feedID, w.WeekOf, helpers)
	return err
}

// GetStreakWeek returns one week's row.
func (s *Store) GetStreakWeek(ctx context.Context, q db.DBTX, feedID uuid.UUID, weekOf time.Time) (*StreakWeek, error) {
	return one[StreakWeek](q.Query(ctx, `SELECT week_of, photos, goal::int AS goal, outcome FROM feed_streak_weeks
		WHERE feed_id = $1 AND week_of = $2`, feedID, weekOf))
}

// StreakHistory returns the family's last closed weeks, newest first.
func (s *Store) StreakHistory(ctx context.Context, q db.DBTX, feedID uuid.UUID, limit int) ([]StreakWeek, error) {
	return many[StreakWeek](q.Query(ctx, `SELECT week_of, photos, goal::int AS goal, outcome FROM feed_streak_weeks
		WHERE feed_id = $1 AND outcome <> 'open' ORDER BY week_of DESC LIMIT $2`, feedID, limit))
}

// StreakHelpers returns who helped in a week.
func (s *Store) StreakHelpers(ctx context.Context, q db.DBTX, feedID uuid.UUID, weekOf time.Time) ([]uuid.UUID, error) {
	rows, err := many[idRow](q.Query(ctx, `SELECT user_id AS id FROM feed_streak_helpers WHERE feed_id = $1 AND week_of = $2 ORDER BY user_id`, feedID, weekOf))
	out := make([]uuid.UUID, len(rows))
	for i, r := range rows {
		out[i] = r.ID
	}
	return out, err
}

// FrequentHelper is a member and how many of the recent weeks they helped in.
type FrequentHelper struct {
	UserID uuid.UUID `db:"user_id"`
	Weeks  int       `db:"weeks"`
}

// FrequentHelpers ranks the feed's current poster-or-above members by the weeks since `since` they helped in.
func (s *Store) FrequentHelpers(ctx context.Context, feedID uuid.UUID, since time.Time) ([]FrequentHelper, error) {
	return many[FrequentHelper](s.Pool.Query(ctx, `SELECT h.user_id, count(*)::int AS weeks FROM feed_streak_helpers h
		JOIN feed_members fm ON fm.feed_id = h.feed_id AND fm.user_id = h.user_id AND fm.role IN ('owner', 'admin', 'poster')
		WHERE h.feed_id = $1 AND h.week_of >= $2 GROUP BY h.user_id ORDER BY weeks DESC, h.user_id`, feedID, since))
}

// FeedsOfSend returns the feeds that have a live post of the send.
func (s *Store) FeedsOfSend(ctx context.Context, q db.DBTX, sendID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := many[idRow](q.Query(ctx, `SELECT DISTINCT p.feed_id AS id FROM feed_posts p JOIN feeds f ON f.id = p.feed_id AND f.deleted_at IS NULL
		WHERE p.send_id = $1 AND p.removed_at IS NULL ORDER BY 1`, sendID))
	out := make([]uuid.UUID, len(rows))
	for i, r := range rows {
		out[i] = r.ID
	}
	return out, err
}

// ---------------------------------------------------------------- prompts

// FeedPrompt is a stored week's prompt.
type FeedPrompt struct {
	ID          uuid.UUID  `db:"id"`
	FeedID      uuid.UUID  `db:"feed_id"`
	WeekOf      time.Time  `db:"week_of"`
	Source      string     `db:"source"`
	LibraryKey  *string    `db:"library_key"`
	Title       *string    `db:"title"`
	SetBy       *uuid.UUID `db:"set_by"`
	AnnouncedAt *time.Time `db:"announced_at"`
	Answers     int        `db:"answers"`
}

const feedPromptSelect = `SELECT p.id, p.feed_id, p.week_of, p.source, p.library_key, p.title, p.set_by, p.announced_at,
	(SELECT count(*)::int FROM feed_posts fp WHERE fp.prompt_id = p.id AND fp.removed_at IS NULL) AS answers
	FROM feed_prompts p`

// FeedPrompts returns the family's stored prompts of weeks in [from, to].
func (s *Store) FeedPrompts(ctx context.Context, q db.DBTX, feedID uuid.UUID, from, to time.Time) ([]FeedPrompt, error) {
	return many[FeedPrompt](q.Query(ctx, feedPromptSelect+` WHERE p.feed_id = $1 AND p.week_of BETWEEN $2 AND $3 ORDER BY p.week_of DESC`, feedID, from, to))
}

// GetFeedPrompt returns one prompt by id.
func (s *Store) GetFeedPrompt(ctx context.Context, q db.DBTX, id uuid.UUID) (*FeedPrompt, error) {
	return one[FeedPrompt](q.Query(ctx, feedPromptSelect+` WHERE p.id = $1`, id))
}

// GetFeedPromptOfWeek returns the stored prompt of one week.
func (s *Store) GetFeedPromptOfWeek(ctx context.Context, q db.DBTX, feedID uuid.UUID, weekOf time.Time) (*FeedPrompt, error) {
	return one[FeedPrompt](q.Query(ctx, feedPromptSelect+` WHERE p.feed_id = $1 AND p.week_of = $2`, feedID, weekOf))
}

// PutFeedPrompt stores a week's prompt, keeping the row's id when the week has one (tagged answers stay).
func (s *Store) PutFeedPrompt(ctx context.Context, q db.DBTX, p FeedPrompt) (uuid.UUID, error) {
	var id uuid.UUID
	err := q.QueryRow(ctx, `INSERT INTO feed_prompts (id, feed_id, week_of, source, library_key, title, set_by)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (feed_id, week_of) DO UPDATE SET source = EXCLUDED.source, library_key = EXCLUDED.library_key,
			title = EXCLUDED.title, set_by = EXCLUDED.set_by, updated_at = now()
		RETURNING id`, p.ID, p.FeedID, p.WeekOf, p.Source, p.LibraryKey, p.Title, p.SetBy).Scan(&id)
	return id, err
}

// DeleteFeedPrompt removes a (future) week's stored prompt.
func (s *Store) DeleteFeedPrompt(ctx context.Context, q db.DBTX, feedID uuid.UUID, weekOf time.Time) error {
	_, err := q.Exec(ctx, `DELETE FROM feed_prompts WHERE feed_id = $1 AND week_of = $2`, feedID, weekOf)
	return err
}

// MarkPromptAnnounced records that the week's prompt was announced in the family chat.
func (s *Store) MarkPromptAnnounced(ctx context.Context, id uuid.UUID, at time.Time) (bool, error) {
	tag, err := s.Pool.Exec(ctx, `UPDATE feed_prompts SET announced_at = $2 WHERE id = $1 AND announced_at IS NULL`, id, at)
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() == 1, nil
}

// TagPost sets or clears a feed post's prompt.
func (s *Store) TagPost(ctx context.Context, q db.DBTX, postID uuid.UUID, promptID *uuid.UUID) error {
	_, err := q.Exec(ctx, `UPDATE feed_posts SET prompt_id = $2 WHERE id = $1`, postID, promptID)
	return err
}

// PostPlacements returns the live placements of a feed post's items, by frame.
func (s *Store) PostPlacements(ctx context.Context, q db.DBTX, postID uuid.UUID) (map[uuid.UUID][]uuid.UUID, error) {
	type row struct {
		FrameID     uuid.UUID `db:"frame_id"`
		PlacementID uuid.UUID `db:"placement_id"`
	}
	rows, err := many[row](q.Query(ctx, `SELECT p.frame_id, p.id AS placement_id FROM feed_deliveries d
		JOIN placements p ON p.id = d.placement_id AND p.removed_at IS NULL WHERE d.post_id = $1 AND d.state = 'placed'`, postID))
	out := map[uuid.UUID][]uuid.UUID{}
	for _, r := range rows {
		out[r.FrameID] = append(out[r.FrameID], r.PlacementID)
	}
	return out, err
}

// PlacementPrompts returns the prompt of the feed post each placement came from (answers only).
func (s *Store) PlacementPrompts(ctx context.Context, q db.DBTX, placementIDs []uuid.UUID) (map[uuid.UUID]uuid.UUID, error) {
	type row struct {
		PlacementID uuid.UUID `db:"placement_id"`
		PromptID    uuid.UUID `db:"prompt_id"`
	}
	out := map[uuid.UUID]uuid.UUID{}
	if len(placementIDs) == 0 {
		return out, nil
	}
	rows, err := many[row](q.Query(ctx, `SELECT DISTINCT ON (d.placement_id) d.placement_id, fp.prompt_id FROM feed_deliveries d
		JOIN feed_posts fp ON fp.id = d.post_id AND fp.prompt_id IS NOT NULL AND fp.removed_at IS NULL
		WHERE d.placement_id = ANY($1) ORDER BY d.placement_id, fp.id DESC`, placementIDs))
	for _, r := range rows {
		out[r.PlacementID] = r.PromptID
	}
	return out, err
}

// ---------------------------------------------------------------- summaries, recaps, milestones

// PutWeeklySummary stores a user's summary of a week (unchanged when it exists).
func (s *Store) PutWeeklySummary(ctx context.Context, userID uuid.UUID, weekOf time.Time, data any) (bool, error) {
	b, err := json.Marshal(data)
	if err != nil {
		return false, err
	}
	tag, err := s.Pool.Exec(ctx, `INSERT INTO weekly_summaries (user_id, week_of, data) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`, userID, weekOf, b)
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() == 1, nil
}

// WeeklySummaryRow is a stored summary.
type WeeklySummaryRow struct {
	WeekOf   time.Time       `db:"week_of"`
	Data     json.RawMessage `db:"data"`
	PushedAt *time.Time      `db:"pushed_at"`
}

// GetWeeklySummary returns one stored summary.
func (s *Store) GetWeeklySummary(ctx context.Context, q db.DBTX, userID uuid.UUID, weekOf time.Time) (*WeeklySummaryRow, error) {
	return one[WeeklySummaryRow](q.Query(ctx, `SELECT week_of, data, pushed_at FROM weekly_summaries WHERE user_id = $1 AND week_of = $2`, userID, weekOf))
}

// MarkSummaryPushed records the summary push.
func (s *Store) MarkSummaryPushed(ctx context.Context, userID uuid.UUID, weekOf, at time.Time) (bool, error) {
	tag, err := s.Pool.Exec(ctx, `UPDATE weekly_summaries SET pushed_at = $3 WHERE user_id = $1 AND week_of = $2 AND pushed_at IS NULL`, userID, weekOf, at)
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() == 1, nil
}

// PruneEngagement deletes summaries older than 12 weeks, nudges older than 180 days and closed requests older
// than 90 days.
func (s *Store) PruneEngagement(ctx context.Context, now time.Time) error {
	for _, q := range []string{
		`DELETE FROM weekly_summaries WHERE week_of < ($1::timestamptz - interval '12 weeks')::date`,
		`DELETE FROM nudges WHERE sent_at < $1::timestamptz - interval '180 days'`,
		`DELETE FROM photo_requests WHERE state <> 'open' AND created_at < $1::timestamptz - interval '90 days'`,
		`DELETE FROM engagement_comment_changes WHERE changed_at < $1::timestamptz - interval '1 day'`,
	} {
		if _, err := s.Pool.Exec(ctx, q, now); err != nil {
			return err
		}
	}
	return nil
}

// PutRecap stores a family's recap of a month; false when it exists.
func (s *Store) PutRecap(ctx context.Context, feedID uuid.UUID, month time.Time, data any) (bool, error) {
	b, err := json.Marshal(data)
	if err != nil {
		return false, err
	}
	tag, err := s.Pool.Exec(ctx, `INSERT INTO feed_recaps (feed_id, month, data) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`, feedID, month, b)
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() == 1, nil
}

// SetRecapMessage records the recap's family chat message.
func (s *Store) SetRecapMessage(ctx context.Context, feedID uuid.UUID, month time.Time, messageID uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `UPDATE feed_recaps SET message_id = $3 WHERE feed_id = $1 AND month = $2 AND EXISTS (SELECT 1 FROM messages WHERE id = $3)`,
		feedID, month, messageID)
	return err
}

// GetRecap returns a stored recap's data.
func (s *Store) GetRecap(ctx context.Context, q db.DBTX, feedID uuid.UUID, month time.Time) (json.RawMessage, error) {
	var b json.RawMessage
	err := q.QueryRow(ctx, `SELECT data FROM feed_recaps WHERE feed_id = $1 AND month = $2`, feedID, month).Scan(&b)
	if db.NotFound(err) {
		return nil, ErrNotFound
	}
	return b, err
}

// Milestone is a user's achieved milestone.
type Milestone struct {
	Key        string     `db:"key"`
	AchievedAt time.Time  `db:"achieved_at"`
	Shown      bool       `db:"shown"`
	SeenAt     *time.Time `db:"seen_at"`
}

// Milestones returns the user's achieved milestones, oldest first.
func (s *Store) Milestones(ctx context.Context, q db.DBTX, userID uuid.UUID) ([]Milestone, error) {
	return many[Milestone](q.Query(ctx, `SELECT key, achieved_at, shown, seen_at FROM user_milestones WHERE user_id = $1 ORDER BY achieved_at, key`, userID))
}

// AchieveMilestone records a milestone; false when the user had it already.
func (s *Store) AchieveMilestone(ctx context.Context, userID uuid.UUID, key string, at time.Time) (bool, error) {
	tag, err := s.Pool.Exec(ctx, `INSERT INTO user_milestones (user_id, key, achieved_at) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`, userID, key, at)
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() == 1, nil
}

// UpdateMilestone sets shown and/or seen.
func (s *Store) UpdateMilestone(ctx context.Context, userID uuid.UUID, key string, shown *bool, seenAt *time.Time) (*Milestone, error) {
	return one[Milestone](s.Pool.Query(ctx, `UPDATE user_milestones SET shown = coalesce($3, shown), seen_at = coalesce(seen_at, $4)
		WHERE user_id = $1 AND key = $2 RETURNING key, achieved_at, shown, seen_at`, userID, key, shown, seenAt))
}

// SharingStats is what personal milestones are computed from.
type SharingStats struct {
	Items     int        `db:"items"`  // photos and videos in the user's own personal sends
	Videos    int        `db:"videos"` // of which videos
	FirstSend *time.Time `db:"first_send"`
	LastSend  *time.Time `db:"last_send"`
	Loves     int        `db:"loves"` // frame love reactions on their placements
}

// UserSharingStats computes a user's sharing statistics.
func (s *Store) UserSharingStats(ctx context.Context, userID uuid.UUID) (*SharingStats, error) {
	return one[SharingStats](s.Pool.Query(ctx, `
		WITH own AS (
			SELECT s.id, s.created_at FROM sends s WHERE s.sender_user_id = $1 AND `+PersonalSendSQL+` AND `+creditedSendSQL+`
		)
		SELECT (SELECT count(DISTINCT si.item_id)::int FROM own JOIN send_items si ON si.send_id = own.id) AS items,
		       (SELECT count(DISTINCT si.item_id)::int FROM own JOIN send_items si ON si.send_id = own.id
		          JOIN items i ON i.id = si.item_id JOIN media m ON m.id = i.media_id WHERE m.kind = 'video') AS videos,
		       (SELECT min(created_at) FROM own) AS first_send, (SELECT max(created_at) FROM own) AS last_send,
		       (SELECT count(*)::int FROM placements p JOIN own ON own.id = p.send_id WHERE p.reaction = 'love') AS loves`, userID))
}

// RecentLoveSenders returns the senders of placements that got a frame love reaction at or after since.
func (s *Store) RecentLoveSenders(ctx context.Context, since time.Time) ([]uuid.UUID, error) {
	rows, err := many[idRow](s.Pool.Query(ctx, `SELECT DISTINCT s.sender_user_id AS id FROM placements p JOIN sends s ON s.id = p.send_id
		WHERE p.reaction = 'love' AND p.reaction_at >= $1 ORDER BY 1`, since))
	out := make([]uuid.UUID, len(rows))
	for i, r := range rows {
		out[i] = r.ID
	}
	return out, err
}

// ActiveSenders returns users with a personal send at or after since.
func (s *Store) ActiveSenders(ctx context.Context, since time.Time) ([]uuid.UUID, error) {
	rows, err := many[idRow](s.Pool.Query(ctx, `SELECT DISTINCT s.sender_user_id AS id FROM sends s
		WHERE s.created_at >= $1 AND `+PersonalSendSQL+` ORDER BY 1`, since))
	out := make([]uuid.UUID, len(rows))
	for i, r := range rows {
		out[i] = r.ID
	}
	return out, err
}

// ItemRowsByIDs returns items as the API renders them.
func (s *Store) ItemRowsByIDs(ctx context.Context, q db.DBTX, ids []uuid.UUID) (map[uuid.UUID]ItemRow, error) {
	out := map[uuid.UUID]ItemRow{}
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := many[ItemRow](q.Query(ctx, `SELECT `+itemRowCols+` FROM items i`+itemJoins+` WHERE i.id = ANY($1)`, ids))
	for _, r := range rows {
		out[r.ItemID] = r
	}
	return out, err
}
