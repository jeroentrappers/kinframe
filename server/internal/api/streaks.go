package api

// Family engagement settings and weekly streaks (README §5.20 "Family streak").

import (
	"context"
	"strings"
	"time"

	"github.com/google/uuid"
	openapi_types "github.com/oapi-codegen/runtime/types"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

const maxFreezes = 2

// ---------------------------------------------------------------- settings

func feedEngagementOut(fe *store.FeedEngagement) gen.FeedEngagement {
	return gen.FeedEngagement{FeedId: fe.FeedID, StreakEnabled: fe.StreakEnabled, StreakGoal: fe.StreakGoal,
		PromptsEnabled: fe.PromptsEnabled, QuietAfterDays: fe.QuietAfterDays, MonthlyRecap: fe.MonthlyRecap,
		Timezone: feedZoneName(fe)}
}

func (s *Server) GetFeedEngagement(ctx context.Context, req gen.GetFeedEngagementRequestObject) (gen.GetFeedEngagementResponseObject, error) {
	if _, err := s.feedView(ctx, s.st.Q(), req.FeedId); err != nil {
		return nil, err
	}
	fe, err := s.st.GetFeedEngagement(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, notFound(err, "feed")
	}
	return ok(feedEngagementOut(fe)), nil
}

func (s *Server) UpdateFeedEngagement(ctx context.Context, req gen.UpdateFeedEngagementRequestObject) (gen.UpdateFeedEngagementResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	if err := requireFeedAdmin(v); err != nil {
		return nil, err
	}
	b := req.Body
	p := store.FeedEngagementPatch{StreakEnabled: b.StreakEnabled, StreakGoal: b.StreakGoal, PromptsEnabled: b.PromptsEnabled,
		QuietAfterDays: b.QuietAfterDays, MonthlyRecap: b.MonthlyRecap}
	if b.Timezone != nil {
		tz := strings.TrimSpace(*b.Timezone)
		if tz == "" {
			p.ClearTimezone = true
		} else if _, err := time.LoadLocation(tz); err != nil || tz == "Local" {
			return nil, problem.Validation("timezone", "format", "not a valid time zone")
		} else {
			p.Timezone = &tz
		}
	}
	before, err := s.st.GetFeedEngagement(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, notFound(err, "feed")
	}
	if err := s.st.UpdateFeedEngagement(ctx, s.st.Q(), req.FeedId, p); err != nil {
		return nil, err
	}
	fe, err := s.st.GetFeedEngagement(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	now := s.now()
	if fe.PromptsEnabled != before.PromptsEnabled || feedZoneName(fe) != feedZoneName(before) {
		if fe.PromptsEnabled {
			s.startPromptWeek(ctx, fe, now)
		}
		s.syncPromptFramesOfFeed(ctx, fe.FeedID)
	}
	if fe.StreakEnabled != before.StreakEnabled || fe.StreakGoal != before.StreakGoal {
		s.publishStreak(ctx, fe, now)
	}
	return ok(feedEngagementOut(fe)), nil
}

// ---------------------------------------------------------------- the running week

// streakWeek is the family's open week: its Monday, count and helpers.
type streakWeek struct {
	monday time.Time
	count  store.WeekCount
}

func (s *Server) openWeek(ctx context.Context, fe *store.FeedEngagement, now time.Time) (streakWeek, error) {
	loc := feedZone(fe)
	monday := mondayOf(now, loc)
	from, to := weekBounds(monday, loc)
	c, err := s.st.CountWeek(ctx, s.st.Q(), fe.FeedID, from, to)
	return streakWeek{monday: monday, count: c}, err
}

// shownWeeks is the streak as shown: the closed weeks in a row plus this week once its goal is met.
func shownWeeks(closed int, met bool) int {
	if met {
		return closed + 1
	}
	return closed
}

// streakSummary renders FeedStreakSummary.
func (s *Server) streakSummary(ctx context.Context, fe *store.FeedEngagement, now time.Time) (*gen.FeedStreakSummary, error) {
	w, err := s.openWeek(ctx, fe, now)
	if err != nil {
		return nil, err
	}
	closed := 0
	if st, err := s.st.GetStreak(ctx, s.st.Q(), fe.FeedID); err == nil && !st.WeekOf.Before(w.monday.AddDate(0, 0, -7)) {
		closed = st.CurrentWeeks
	}
	met := w.count.Photos >= fe.StreakGoal
	return &gen.FeedStreakSummary{CurrentWeeks: shownWeeks(closed, met), WeekPhotos: w.count.Photos, Goal: fe.StreakGoal, Met: met}, nil
}

func (s *Server) GetFeedStreak(ctx context.Context, req gen.GetFeedStreakRequestObject) (gen.GetFeedStreakResponseObject, error) {
	if _, err := s.feedView(ctx, s.st.Q(), req.FeedId); err != nil {
		return nil, err
	}
	fe, err := s.st.GetFeedEngagement(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, notFound(err, "feed")
	}
	now := s.now()
	w, err := s.openWeek(ctx, fe, now)
	if err != nil {
		return nil, err
	}
	out := gen.FeedStreak{Enabled: fe.StreakEnabled, Goal: fe.StreakGoal, FreezesLeft: 1}
	if st, err := s.st.GetStreak(ctx, s.st.Q(), fe.FeedID); err == nil {
		closed := st.CurrentWeeks
		if st.WeekOf.Before(w.monday.AddDate(0, 0, -7)) {
			closed = 0 // weeks the scheduler has not closed yet were missed
		}
		out.CurrentWeeks, out.BestWeeks, out.FreezesLeft = closed, st.BestWeeks, st.FreezesLeft
	} else if err != store.ErrNotFound {
		return nil, err
	}
	met := w.count.Photos >= fe.StreakGoal
	out.CurrentWeeks = shownWeeks(out.CurrentWeeks, met)
	out.BestWeeks = max(out.BestWeeks, out.CurrentWeeks)
	out.Week.WeekOf = openapi_types.Date{Time: w.monday}
	out.Week.Photos, out.Week.Met = w.count.Photos, met
	out.Week.Helpers = s.engPersonRefs(ctx, w.count.Helpers)
	hist, err := s.st.StreakHistory(ctx, s.st.Q(), fe.FeedID, 12)
	if err != nil {
		return nil, err
	}
	h := make([]struct {
		Outcome gen.FeedStreakHistoryOutcome `json:"outcome"`
		WeekOf  openapi_types.Date           `json:"week_of"`
	}, len(hist))
	for i, x := range hist {
		h[i].Outcome, h[i].WeekOf = gen.FeedStreakHistoryOutcome(x.Outcome), openapi_types.Date{Time: x.WeekOf}
	}
	out.History = &h
	return ok(out), nil
}

// recountStreak records the open week's count and helpers after a post and tells the family.
func (s *Server) recountStreak(ctx context.Context, feedID uuid.UUID, now time.Time) {
	fe, err := s.st.GetFeedEngagement(ctx, s.st.Q(), feedID)
	if err != nil {
		return
	}
	w, err := s.openWeek(ctx, fe, now)
	if err != nil {
		s.log.Warn("streak week", "feed", feedID, "err", err)
		return
	}
	if err := s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockStreak(ctx, feedID, w.monday, monthOf(now, feedZone(fe))); err != nil {
			return err
		}
		return s.st.PutStreakWeek(ctx, tx, feedID, store.StreakWeek{WeekOf: w.monday, Photos: w.count.Photos, Goal: fe.StreakGoal, Outcome: "open"}, w.count.Helpers)
	}); err != nil {
		s.log.Warn("streak week", "feed", feedID, "err", err)
		return
	}
	s.publishStreak(ctx, fe, now)
}

// publishStreak sends streak.updated to the family (not while streaks are off).
func (s *Server) publishStreak(ctx context.Context, fe *store.FeedEngagement, now time.Time) {
	if !fe.StreakEnabled {
		return
	}
	sum, err := s.streakSummary(ctx, fe, now)
	if err != nil {
		return
	}
	ids, err := s.st.FeedMemberIDs(ctx, s.st.Q(), fe.FeedID)
	if err != nil {
		return
	}
	s.notify.Users(ids, notify.Event{"type": "streak.updated", "feed_id": fe.FeedID, "streak": sum}, nil)
}

// ---------------------------------------------------------------- closing weeks

// streakState is the part of a streak closing a week changes.
type streakState struct {
	Current, Best, Freezes, LastMilestone int
}

// isStreakMilestone: 4, 12, 26 and 52 weeks, then every 52.
func isStreakMilestone(n int) bool {
	return n == 4 || n == 12 || n == 26 || (n >= 52 && n%52 == 0)
}

// closeStreakWeek applies one closed week (README §5.20): met adds a week; missed uses a freeze when one is
// left (the streak stays), else the streak quietly restarts. Returns the outcome and the milestone reached
// (0: none).
func closeStreakWeek(st *streakState, met bool) (string, int) {
	switch {
	case met:
		st.Current++
		st.Best = max(st.Best, st.Current)
		if isStreakMilestone(st.Current) && st.Current > st.LastMilestone {
			st.LastMilestone = st.Current
			return "met", st.Current
		}
		return "met", 0
	case st.Freezes > 0:
		st.Freezes--
		return "frozen", 0
	default:
		st.Current, st.LastMilestone = 0, 0
		return "missed", 0
	}
}

// grantFreeze grants the month's freeze (one on the 1st of every month, at most 2 held).
func grantFreeze(st *streakState, granted **time.Time, month time.Time) {
	if *granted != nil && !(*granted).Before(month) {
		return
	}
	st.Freezes = min(maxFreezes, st.Freezes+1)
	m := month
	*granted = &m
}

// closeStreak closes the family's weeks that ended before now (Monday 00:00 in its time zone), granting the
// monthly freezes on the way, and celebrates milestones in the family chat.
func (s *Server) closeStreak(ctx context.Context, fe *store.FeedEngagement, now time.Time) {
	loc := feedZone(fe)
	current := mondayOf(now, loc)
	var milestones []int
	changed := false
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		milestones, changed = nil, false
		row, err := tx.LockStreak(ctx, fe.FeedID, current, monthOf(now, loc))
		if err != nil {
			return err
		}
		st := streakState{Current: row.CurrentWeeks, Best: row.BestWeeks, Freezes: row.FreezesLeft, LastMilestone: row.LastMilestoneWeeks}
		granted := row.FreezeGrantedMonth
		week := row.WeekOf
		if current.Sub(week) > 60*7*24*time.Hour {
			week = current.AddDate(0, 0, -60*7) // long downtime: older weeks were missed anyway
			st.Current, st.LastMilestone = 0, 0
		}
		for week.Before(current) {
			grantFreeze(&st, &granted, monthOf(week.AddDate(0, 0, 6), time.UTC))
			from, to := weekBounds(week, loc)
			c, err := s.st.CountWeek(ctx, tx, fe.FeedID, from, to)
			if err != nil {
				return err
			}
			outcome, m := closeStreakWeek(&st, c.Photos >= fe.StreakGoal)
			if m > 0 {
				milestones = append(milestones, m)
			}
			if err := s.st.PutStreakWeek(ctx, tx, fe.FeedID, store.StreakWeek{WeekOf: week, Photos: c.Photos, Goal: fe.StreakGoal, Outcome: outcome}, c.Helpers); err != nil {
				return err
			}
			week = week.AddDate(0, 0, 7)
			changed = true
		}
		before := st.Freezes
		grantFreeze(&st, &granted, monthOf(now, loc))
		if st.Freezes != before {
			changed = true
		}
		if !changed {
			return nil
		}
		return tx.SaveStreak(ctx, &store.Streak{FeedID: fe.FeedID, CurrentWeeks: st.Current, BestWeeks: st.Best, FreezesLeft: st.Freezes,
			FreezeGrantedMonth: granted, WeekOf: current, LastMilestoneWeeks: st.LastMilestone})
	})
	if err != nil {
		s.log.Warn("close streak", "feed", fe.FeedID, "err", err)
		return
	}
	if !changed {
		return
	}
	if fe.StreakEnabled {
		for _, m := range milestones {
			s.familySystemMessage(ctx, fe.FeedID, gen.SystemMessage{Type: gen.SystemMessageTypeStreakMilestone, Weeks: ptr(m)})
		}
	}
	s.publishStreak(ctx, fe, now)
}

// familySystemMessage posts a system message in the family chat (server-chat's postSystemMessage).
func (s *Server) familySystemMessage(ctx context.Context, feedID uuid.UUID, msg gen.SystemMessage) uuid.UUID {
	conv, err := s.familyConversationID(ctx, feedID)
	if err != nil {
		s.log.Warn("family chat", "feed", feedID, "err", err)
		return uuid.Nil
	}
	id, err := s.postSystemMessage(ctx, conv, msg)
	if err != nil {
		s.log.Warn("system message", "feed", feedID, "type", msg.Type, "err", err)
	}
	return id
}
