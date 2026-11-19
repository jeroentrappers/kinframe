package api

// Reminders and their budget (README §5.20): preferences, the budget, quiet hours, rotation, the four
// reminder kinds' scheduling and the outcome log.

import (
	"context"
	"strconv"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

const (
	week             = 7 * 24 * time.Hour
	quietSenders     = 6 * 30 * 24 * time.Hour // who sent to a quiet frame recently
	quietRecipients  = 2                       // per quiet frame
	streakHour       = 17                      // Sunday 17:00 family time
	streakHelperSpan = 8                       // weeks
	birthdayHour     = 10                      // local, 3 days before
	birthdayAhead    = 3                       // days
	birthdayPosters  = 3
	outcomeWindow    = 24 * time.Hour
)

// countedReminders are the kinds whose ignored pushes lower the level (frame requests come from a person and
// the weekly summary asks for nothing).
var countedReminders = []string{"reminder_quiet", "reminder_streak", "reminder_birthday"}

// ---------------------------------------------------------------- preferences

func reminderPrefsOut(p *store.ReminderPrefs, digest bool, now time.Time) gen.ReminderPreferences {
	out := gen.ReminderPreferences{Level: gen.ReminderLevel(p.Level), FrameRequests: p.FrameRequests, WeeklySummary: p.WeeklySummary,
		BirthdayReminders: p.BirthdayReminders, QuietHours: gen.QuietHours{Start: p.QuietStart, End: p.QuietEnd},
		EmailDigest: gen.EmailDigest(p.EmailDigest), EmailDigestAvailable: ptr(digest),
		AutoReduced: ptr(p.LevelAuto && p.Level == "fewer" && p.AutoReducedSeenAt == nil)}
	if p.SnoozedUntil != nil && p.SnoozedUntil.After(now) {
		out.SnoozedUntil = p.SnoozedUntil
	}
	return out
}

func (s *Server) GetReminderPreferences(ctx context.Context, _ gen.GetReminderPreferencesRequestObject) (gen.GetReminderPreferencesResponseObject, error) {
	p, err := s.st.GetReminderPrefs(ctx, s.st.Q(), me(ctx).ID)
	if err != nil {
		return nil, err
	}
	return ok(reminderPrefsOut(p, s.digestAvailable(), s.now())), nil
}

func (s *Server) UpdateReminderPreferences(ctx context.Context, req gen.UpdateReminderPreferencesRequestObject) (gen.UpdateReminderPreferencesResponseObject, error) {
	b := req.Body
	now := s.now()
	p := store.ReminderPrefsPatch{FrameRequests: b.FrameRequests, WeeklySummary: b.WeeklySummary, Birthdays: b.BirthdayReminders}
	if b.Level != nil {
		l := string(*b.Level)
		p.Level, p.LevelAuto = &l, ptr(false)
		if l == "normal" {
			p.Unanswered = ptr(0) // a fresh start
		}
	}
	if b.SnoozeDays != nil {
		var until *time.Time
		if *b.SnoozeDays > 0 {
			until = ptr(now.Add(time.Duration(*b.SnoozeDays) * 24 * time.Hour))
		}
		p.SnoozedUntil = &until
	}
	if b.QuietHours != nil {
		if _, ok1 := parseClock(b.QuietHours.Start); !ok1 {
			return nil, problem.Validation("quiet_hours.start", "pattern", "quiet hours are HH:MM between 00:00 and 23:59")
		}
		if _, ok2 := parseClock(b.QuietHours.End); !ok2 {
			return nil, problem.Validation("quiet_hours.end", "pattern", "quiet hours are HH:MM between 00:00 and 23:59")
		}
		p.QuietStart, p.QuietEnd = &b.QuietHours.Start, &b.QuietHours.End
	}
	if b.EmailDigest != nil {
		d := string(*b.EmailDigest)
		if d != "off" && !s.digestAvailable() {
			return nil, problem.Validation("email_digest", "unavailable", "email digests are not available on this server")
		}
		p.EmailDigest = &d
		if d != "off" {
			// The first digest covers what arrives from now on, not the whole history.
			if cur, err := s.st.GetReminderPrefs(ctx, s.st.Q(), me(ctx).ID); err == nil && cur.EmailDigest == "off" {
				p.DigestSentAt = &now
			}
		}
	}
	if b.AutoReducedSeen != nil && bool(*b.AutoReducedSeen) {
		p.AutoReducedSeen = &now
	}
	if err := s.st.UpdateReminderPrefs(ctx, s.st.Q(), me(ctx).ID, p); err != nil {
		return nil, err
	}
	out, err := s.st.GetReminderPrefs(ctx, s.st.Q(), me(ctx).ID)
	if err != nil {
		return nil, err
	}
	return ok(reminderPrefsOut(out, s.digestAvailable(), now)), nil
}

// ---------------------------------------------------------------- sending a reminder

// reminder is one budgeted push to one person.
type reminder struct {
	kind      string
	userID    uuid.UUID
	dedupe    string
	frameID   *uuid.UUID
	feedID    *uuid.UUID
	subjectID *uuid.UUID
	push      *notify.Push
}

// sendReminder sends a budgeted reminder when the person's preferences, quiet hours and budget allow it and
// its dedupe key is unused. Reports whether it went out.
func (s *Server) sendReminder(ctx context.Context, r reminder, prefs map[uuid.UUID]store.ReminderPrefs, now time.Time) bool {
	p, ok := prefs[r.userID]
	if !ok {
		got, err := s.st.GetReminderPrefs(ctx, s.st.Q(), r.userID)
		if err != nil {
			return false
		}
		p = *got
		prefs[r.userID] = p
	}
	used, err := s.st.BudgetUsed(ctx, r.userID, now.Add(-week))
	if err != nil || !reminderAllowed(&p, r.kind, now, used) {
		return false
	}
	inserted, err := s.st.InsertNudge(ctx, store.Nudge{ID: store.NewID(), UserID: r.userID, Kind: r.kind, FrameID: r.frameID, FeedID: r.feedID,
		SubjectID: r.subjectID, DedupeKey: r.dedupe, CountsInBudget: true, SentAt: now})
	if err != nil {
		s.log.Warn("nudge", "kind", r.kind, "err", err)
		return false
	}
	if !inserted {
		return false
	}
	r.push.Actions = reminderActions
	s.notify.PushAlways(r.userID, notify.Event{"type": r.kind}, r.push)
	return true
}

// ---------------------------------------------------------------- frame is getting quiet

func (s *Server) remindQuietFrames(ctx context.Context, now time.Time, prefs map[uuid.UUID]store.ReminderPrefs) {
	frames, err := s.st.QuietFrames(ctx, now, defaultQuietAfter)
	if err != nil {
		s.log.Warn("quiet frames", "err", err)
		return
	}
	for _, f := range frames {
		frameID := f.FrameID
		if n, err := s.st.LastNudge(ctx, "reminder_quiet", &frameID, nil, nil, now.Add(-week)); err == nil && n != nil {
			continue // once per frame per 7 days
		}
		cands, err := s.st.QuietCandidates(ctx, frameID, now.Add(-quietSenders))
		if err != nil {
			continue
		}
		days := int(now.Sub(f.LastPersonalAt).Hours() / 24)
		sent := 0
		for _, c := range cands {
			if sent >= quietRecipients {
				break
			}
			uid := c.UserID
			if n, err := s.st.LastNudge(ctx, "reminder_quiet", nil, nil, &uid, now.Add(-week)); err == nil && n != nil {
				continue // once per person per 7 days
			}
			if s.sendReminder(ctx, reminder{kind: "reminder_quiet", userID: uid, frameID: &frameID,
				dedupe: "reminder_quiet:" + frameID.String() + ":" + now.UTC().Format(dateLayout),
				push: &notify.Push{Key: "reminder_quiet", Args: map[string]string{"frame": f.Name, "days": strconv.Itoa(days)},
					Tag: "reminder_quiet:" + frameID.String(), IDs: map[string]string{"frame_id": frameID.String()}}}, prefs, now) {
				sent++
			}
		}
	}
}

// ---------------------------------------------------------------- streak at risk

func (s *Server) remindStreak(ctx context.Context, fe *store.FeedEngagement, now time.Time, prefs map[uuid.UUID]store.ReminderPrefs) {
	if !fe.StreakEnabled {
		return
	}
	loc := feedZone(fe)
	local := now.In(loc)
	if local.Weekday() != time.Sunday || local.Hour() < streakHour {
		return
	}
	w, err := s.openWeek(ctx, fe, now)
	if err != nil || w.count.Photos >= fe.StreakGoal {
		return
	}
	key := "reminder_streak:" + fe.FeedID.String() + ":" + w.monday.Format(dateLayout)
	if used, err := s.st.DedupeUsed(ctx, key); err != nil || used {
		return // once a week per family
	}
	helpers, err := s.st.FrequentHelpers(ctx, fe.FeedID, w.monday.AddDate(0, 0, -7*streakHelperSpan))
	if err != nil || len(helpers) == 0 {
		return
	}
	var last *uuid.UUID
	if n, err := s.st.LastNudge(ctx, "reminder_streak", nil, &fe.FeedID, nil, time.Time{}); err == nil && n != nil {
		last = &n.UserID
	}
	order := make([]uuid.UUID, 0, len(helpers))
	for _, h := range helpers {
		if last == nil || h.UserID != *last {
			order = append(order, h.UserID)
		}
	}
	if len(order) == 0 {
		order = append(order, helpers[0].UserID) // the only one who helps
	}
	closed := 0
	if st, err := s.st.GetStreak(ctx, s.st.Q(), fe.FeedID); err == nil && !st.WeekOf.Before(w.monday.AddDate(0, 0, -7)) {
		closed = st.CurrentWeeks
	}
	missing := fe.StreakGoal - w.count.Photos
	text := "reminder_streak"
	switch {
	case closed == 0:
		text = "reminder_streak_start"
	case missing == 1:
		text = "reminder_streak_one"
	}
	push := &notify.Push{Key: "reminder_streak", Text: text, Tag: "reminder_streak:" + fe.FeedID.String(),
		Args: map[string]string{"feed": fe.FeedName, "weeks": strconv.Itoa(closed), "missing": strconv.Itoa(missing)},
		IDs:  map[string]string{"feed_id": fe.FeedID.String(), "week_of": w.monday.Format(dateLayout)}}
	for _, u := range order {
		if s.sendReminder(ctx, reminder{kind: "reminder_streak", userID: u, feedID: &fe.FeedID, dedupe: key, push: push}, prefs, now) {
			return
		}
	}
}

// ---------------------------------------------------------------- birthday coming

func (s *Server) remindBirthdays(ctx context.Context, now time.Time, prefs map[uuid.UUID]store.ReminderPrefs) {
	utc := dateOf(now.UTC())
	var dates []time.Time
	for d := birthdayAhead - 1; d <= birthdayAhead+1; d++ {
		dates = append(dates, utc.AddDate(0, 0, d))
	}
	months, days := store.MonthDaysAround(dates...)
	bs, err := s.st.UpcomingBirthdays(ctx, months, days)
	if err != nil {
		s.log.Warn("upcoming birthdays", "err", err)
		return
	}
	for _, b := range bs {
		posters, err := s.st.BirthdayPosters(ctx, b.FeedID, b.UserID, now.AddDate(-1, 0, 0), birthdayPosters)
		if err != nil {
			continue
		}
		for _, u := range posters {
			p, ok := prefs[u]
			if !ok {
				got, err := s.st.GetReminderPrefs(ctx, s.st.Q(), u)
				if err != nil {
					continue
				}
				p = *got
				prefs[u] = p
			}
			local := now.In(userZone(p.Timezone))
			day := dateOf(local).AddDate(0, 0, birthdayAhead)
			if !store.OccursOn(b.Month, b.Day, day) || local.Hour() < birthdayHour {
				continue
			}
			date := day.Format(dateLayout)
			key := "reminder_birthday:" + b.ID.String() + ":" + date
			if n, err := s.st.CountDedupe(ctx, key); err != nil || n >= birthdayPosters {
				continue
			}
			bid := b.ID
			s.sendReminder(ctx, reminder{kind: "reminder_birthday", userID: u, feedID: &b.FeedID, subjectID: &bid, dedupe: key,
				push: &notify.Push{Key: "reminder_birthday", Tag: "reminder_birthday:" + b.ID.String(),
					Args: map[string]string{"person": engFirstName(b.DisplayName), "feed": b.FeedName},
					IDs:  map[string]string{"feed_id": b.FeedID.String(), "birthday_id": b.ID.String(), "date": date}}}, prefs, now)
		}
	}
}

// ---------------------------------------------------------------- outcomes

// closeNudgeOutcomes marks reminders nobody answered within 24 hours and lowers people who never respond to
// `fewer` after 4 in a row.
func (s *Server) closeNudgeOutcomes(ctx context.Context, now time.Time) {
	ignored, err := s.st.CloseIgnoredNudges(ctx, now.Add(-outcomeWindow), now, countedReminders)
	if err != nil {
		s.log.Warn("nudge outcomes", "err", err)
		return
	}
	for _, ig := range ignored {
		if _, err := s.st.AddUnanswered(ctx, ig.UserID, ig.Count, ignoredLimit); err != nil {
			s.log.Warn("unanswered reminders", "user", ig.UserID, "err", err)
		}
	}
}
