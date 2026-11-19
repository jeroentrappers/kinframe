package api

// The engagement scheduler (README §5.20): every 15 minutes streaks close, prompts start and are announced,
// recaps are made, reminders and summaries go out within the budget, requests expire, outcomes are closed and
// digests are mailed. A short loop keeps the frames' comment cards current. Everything is idempotent
// (dedupe keys, claimed rows), so a tick may run twice or late.

import (
	"context"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

const (
	jobEngagementTick   = "engagement.tick"
	engagementInterval  = 15 * time.Minute
	engagementFirstTick = 2 * time.Minute
	commentCardInterval = 15 * time.Second
)

// engagementDaily remembers the last day the daily chores ran (pruning, year-of-sharing checks).
var engagementDaily struct {
	sync.Mutex
	day time.Time
}

func (s *Server) runEngagementLoop(ctx context.Context) {
	slow := time.NewTimer(engagementFirstTick)
	defer slow.Stop()
	fast := time.NewTicker(commentCardInterval)
	defer fast.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-fast.C:
			s.syncChangedComments(ctx)
		case <-slow.C:
			s.EngagementTick(ctx, s.now())
			slow.Reset(engagementInterval)
		}
	}
}

// EngagementTick runs one round of the scheduler at now (tests call it with their clock).
func (s *Server) EngagementTick(ctx context.Context, now time.Time) {
	s.expirePhotoRequests(ctx, now)
	s.notifyHeldRequests(ctx, now)
	s.closeNudgeOutcomes(ctx, now)

	feeds, err := s.st.AllFeedEngagements(ctx)
	if err != nil {
		if ctx.Err() == nil {
			s.log.Error("engagement feeds", "err", err)
		}
		return
	}
	for i := range feeds {
		fe := &feeds[i]
		s.closeStreak(ctx, fe, now)
		s.startPromptWeek(ctx, fe, now)
		s.announcePrompt(ctx, fe, now)
		s.makeRecap(ctx, fe, now)
	}

	// Reminders, in the order the budget prefers them: birthday, streak, quiet, summary.
	prefs := map[uuid.UUID]store.ReminderPrefs{}
	s.remindBirthdays(ctx, now, prefs)
	for i := range feeds {
		s.remindStreak(ctx, &feeds[i], now, prefs)
	}
	s.remindQuietFrames(ctx, now, prefs)
	s.sendWeeklySummaries(ctx, now, prefs)

	s.syncAllFramePrompts(ctx)
	s.syncAllCommentCards(ctx)
	s.syncChangedComments(ctx)

	if users, err := s.st.RecentLoveSenders(ctx, now.Add(-engagementInterval-time.Minute)); err == nil {
		for _, u := range users {
			s.checkMilestones(ctx, u, now)
		}
	}
	s.sendDigests(ctx, now)

	day := dateOf(now.UTC())
	engagementDaily.Lock()
	daily := !engagementDaily.day.Equal(day)
	engagementDaily.day = day
	engagementDaily.Unlock()
	if daily {
		if users, err := s.st.ActiveSenders(ctx, now.AddDate(0, 0, -30)); err == nil {
			for _, u := range users {
				s.checkMilestones(ctx, u, now) // year_of_sharing comes with time, not with a send
			}
		}
		if err := s.st.PruneEngagement(ctx, now); err != nil && ctx.Err() == nil {
			s.log.Warn("prune engagement", "err", err)
		}
	}
}

// engagementAfterSend is afterPersonalSend's body.
func (s *Server) engagementAfterSend(ctx context.Context, sendID uuid.UUID) {
	c, err := s.st.GetSendCredit(ctx, s.st.Q(), sendID)
	if err != nil || !c.Personal {
		return
	}
	now := s.now()
	s.answerPhotoRequests(ctx, c, now)
	feeds, err := s.st.FeedsOfSend(ctx, s.st.Q(), sendID)
	if err == nil {
		for _, f := range feeds {
			s.recountStreak(ctx, f, now)
		}
	}
	if c.Credited {
		if _, err := s.st.NudgesShared(ctx, c.Sender, now); err != nil {
			s.log.Warn("nudge outcome", "err", err)
		}
		s.checkMilestones(ctx, c.Sender, now)
	}
}

// AfterPersonalSend runs afterPersonalSend (tests, and sends made outside the API's own handlers).
func (s *Server) AfterPersonalSend(ctx context.Context, sendID uuid.UUID) {
	s.afterPersonalSend(ctx, sendID)
}

// engagementAfterFanout follows a feed post's fanout: placements on subscribed frames answer their photo
// requests (the post's streak count was made by afterPersonalSend).
func (s *Server) engagementAfterFanout(ctx context.Context, postID uuid.UUID) {
	var sendID *uuid.UUID
	if err := s.st.Q().QueryRow(ctx, `SELECT send_id FROM feed_posts WHERE id = $1`, postID).Scan(&sendID); err != nil || sendID == nil {
		return
	}
	c, err := s.st.GetSendCredit(ctx, s.st.Q(), *sendID)
	if err != nil || !c.Personal {
		return
	}
	s.answerPhotoRequests(ctx, c, s.now())
}
