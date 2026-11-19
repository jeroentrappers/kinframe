package api

// On this day, the weekly "seen and loved" summary, the monthly family recap and personal milestones
// (README §5.20).

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"time"

	"github.com/google/uuid"
	openapi_types "github.com/oapi-codegen/runtime/types"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

const (
	memoryGroups    = 3
	memoryItems     = 12
	summaryHour     = 19 // Sunday 19:00 local
	summaryLateHour = 12 // still sent until Monday 12:00 (quiet hours)
	recapHour       = 10 // the 1st, 10:00 family time
)

// ---------------------------------------------------------------- On this day

// memoryDays are the days of the month whose posts show today: 29 February also shows on 28 February in
// other years.
func memoryDays(today time.Time) []int {
	days := []int{today.Day()}
	if today.Month() == time.February && today.Day() == 28 {
		days = append(days, 29)
	}
	return days
}

func (s *Server) GetOnThisDay(ctx context.Context, _ gen.GetOnThisDayRequestObject) (gen.GetOnThisDayResponseObject, error) {
	u := me(ctx)
	loc := userZone(u.Timezone)
	today := dateOf(s.now().In(loc))
	out := gen.OnThisDay{Date: openapi_types.Date{Time: today}}
	out.Groups = make([]struct {
		FeedId   gen.Id     `json:"feed_id"`
		FeedName string     `json:"feed_name"`
		Items    []gen.Item `json:"items"`
		PostIds  []gen.Id   `json:"post_ids"`
		YearsAgo int        `json:"years_ago"`
	}, 0)
	posts, err := s.st.MemoryPosts(ctx, u.ID, loc.String(), int(today.Month()), memoryDays(today), today.Year())
	if err != nil {
		return nil, err
	}
	type group struct {
		year   int
		feedID uuid.UUID
		name   string
		posts  []uuid.UUID
	}
	var groups []*group
	byKey := map[string]*group{}
	for _, p := range posts {
		key := fmt.Sprintf("%d:%s", p.Year, p.FeedID)
		g := byKey[key]
		if g == nil {
			if len(groups) == memoryGroups {
				continue
			}
			g = &group{year: p.Year, feedID: p.FeedID, name: p.FeedName}
			byKey[key] = g
			groups = append(groups, g)
		}
		g.posts = append(g.posts, p.PostID)
	}
	for _, g := range groups {
		rows, err := s.st.FeedPostItemRows(ctx, s.st.Q(), g.posts)
		if err != nil {
			return nil, err
		}
		items := []gen.Item{}
		seen := map[uuid.UUID]bool{}
		for i := range rows {
			if len(items) == memoryItems {
				break
			}
			if rows[i].MediaStatus != "ready" || seen[rows[i].ItemID] {
				continue
			}
			seen[rows[i].ItemID] = true
			items = append(items, s.itemJSON(&rows[i].ItemRow))
		}
		if len(items) == 0 {
			continue
		}
		out.Groups = append(out.Groups, struct {
			FeedId   gen.Id     `json:"feed_id"`
			FeedName string     `json:"feed_name"`
			Items    []gen.Item `json:"items"`
			PostIds  []gen.Id   `json:"post_ids"`
			YearsAgo int        `json:"years_ago"`
		}{FeedId: g.feedID, FeedName: g.name, Items: items, PostIds: g.posts, YearsAgo: today.Year() - g.year})
	}
	return ok(out), nil
}

// ---------------------------------------------------------------- weekly summary

// computeWeeklySummary builds a user's summary of [from, to).
func (s *Server) computeWeeklySummary(ctx context.Context, userID uuid.UUID, monday, from, to time.Time) (*gen.WeeklySummary, error) {
	frames, err := s.st.SeenOnFrames(ctx, userID, from, to)
	if err != nil {
		return nil, err
	}
	out := gen.WeeklySummary{WeekOf: openapi_types.Date{Time: monday}}
	out.Frames = make([]struct {
		FrameId   gen.Id `json:"frame_id"`
		FrameName string `json:"frame_name"`
		LoveCount int    `json:"love_count"`
		SeenCount int    `json:"seen_count"`
	}, 0)
	for _, f := range frames {
		out.SeenCount += f.Seen
		out.LoveCount += f.Loves
		if f.Seen > 0 {
			out.FrameCount++
		}
		out.Frames = append(out.Frames, struct {
			FrameId   gen.Id `json:"frame_id"`
			FrameName string `json:"frame_name"`
			LoveCount int    `json:"love_count"`
			SeenCount int    `json:"seen_count"`
		}{FrameId: f.FrameID, FrameName: f.FrameName, LoveCount: f.Loves, SeenCount: f.Seen})
	}
	if out.ReactionCount, out.CommentCount, err = s.st.PostReactionsAndComments(ctx, userID, from, to); err != nil {
		return nil, err
	}
	if id, err := s.st.TopItem(ctx, userID, from, to); err != nil {
		return nil, err
	} else if id != nil {
		if rows, err := s.st.ItemRowsByIDs(ctx, s.st.Q(), []uuid.UUID{*id}); err == nil {
			if r, ok := rows[*id]; ok {
				it := s.itemJSON(&r)
				out.TopItem = &it
			}
		}
	}
	return &out, nil
}

// summaryWeek is the week a summary is made for at local time: Sunday from 19:00 the running week, Monday
// until noon the week before; otherwise none.
func summaryWeek(local time.Time) (time.Time, bool) {
	switch {
	case local.Weekday() == time.Sunday && local.Hour() >= summaryHour:
		return mondayOf(local, local.Location()), true
	case local.Weekday() == time.Monday && local.Hour() < summaryLateHour:
		return mondayOf(local, local.Location()).AddDate(0, 0, -7), true
	}
	return time.Time{}, false
}

// sendWeeklySummaries makes and pushes the summaries that are due.
func (s *Server) sendWeeklySummaries(ctx context.Context, now time.Time, prefs map[uuid.UUID]store.ReminderPrefs) {
	users, err := s.st.SeenSenders(ctx, now.Add(-9*24*time.Hour))
	if err != nil {
		s.log.Warn("summary senders", "err", err)
		return
	}
	for _, u := range users {
		p, ok := prefs[u]
		if !ok {
			got, err := s.st.GetReminderPrefs(ctx, s.st.Q(), u)
			if err != nil {
				continue
			}
			p = *got
			prefs[u] = p
		}
		if !p.WeeklySummary {
			continue
		}
		loc := userZone(p.Timezone)
		monday, due := summaryWeek(now.In(loc))
		if !due {
			continue
		}
		row, err := s.st.GetWeeklySummary(ctx, s.st.Q(), u, monday)
		if err == store.ErrNotFound {
			from, to := weekBounds(monday, loc)
			if to.After(now) {
				to = now
			}
			sum, err := s.computeWeeklySummary(ctx, u, monday, from, to)
			if err != nil || sum.SeenCount == 0 {
				continue
			}
			if _, err := s.st.PutWeeklySummary(ctx, u, monday, sum); err != nil {
				continue
			}
			if row, err = s.st.GetWeeklySummary(ctx, s.st.Q(), u, monday); err != nil {
				continue
			}
		} else if err != nil {
			continue
		}
		if row.PushedAt != nil {
			continue
		}
		var sum gen.WeeklySummary
		if json.Unmarshal(row.Data, &sum) != nil {
			continue
		}
		week := monday.Format(dateLayout)
		push := &notify.Push{Key: "weekly_summary", Tag: "weekly_summary", IDs: map[string]string{"week_of": week},
			Args: map[string]string{"seen": strconv.Itoa(sum.SeenCount), "frames": strconv.Itoa(sum.FrameCount), "loved": ""}}
		if sum.LoveCount > 0 {
			push.Args["loved"] = " ❤ " + strconv.Itoa(sum.LoveCount)
		}
		if sum.FrameCount == 1 {
			push.Text = "weekly_summary_one"
			for _, f := range sum.Frames {
				if f.SeenCount > 0 {
					push.Args["frame"] = f.FrameName
				}
			}
		}
		if s.sendReminder(ctx, reminder{kind: "weekly_summary", userID: u, dedupe: "weekly_summary:" + week, push: push}, prefs, now) {
			_, _ = s.st.MarkSummaryPushed(ctx, u, monday, now)
		}
	}
}

func (s *Server) GetWeeklySummary(ctx context.Context, req gen.GetWeeklySummaryRequestObject) (gen.GetWeeklySummaryResponseObject, error) {
	row, err := s.st.GetWeeklySummary(ctx, s.st.Q(), me(ctx).ID, dateOf(req.WeekOf.Time))
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "summary not found")
	} else if err != nil {
		return nil, err
	}
	var out gen.WeeklySummary
	if err := json.Unmarshal(row.Data, &out); err != nil {
		return nil, err
	}
	out.WeekOf = openapi_types.Date{Time: row.WeekOf}
	return ok(out), nil
}

// ---------------------------------------------------------------- monthly recap

// makeRecap makes the family's recap of the month before, on the 1st from 10:00 family time, and posts it in
// the family chat.
func (s *Server) makeRecap(ctx context.Context, fe *store.FeedEngagement, now time.Time) {
	if !fe.MonthlyRecap {
		return
	}
	loc := feedZone(fe)
	local := now.In(loc)
	if local.Day() == 1 && local.Hour() < recapHour {
		return
	}
	month := time.Date(local.Year(), local.Month()-1, 1, 0, 0, 0, 0, time.UTC)
	if _, err := s.st.GetRecap(ctx, s.st.Q(), fe.FeedID, month); err == nil {
		return
	} else if err != store.ErrNotFound {
		return
	}
	from := time.Date(month.Year(), month.Month(), 1, 0, 0, 0, 0, loc)
	to := time.Date(month.Year(), month.Month()+1, 1, 0, 0, 0, 0, loc)
	rc, err := s.buildRecap(ctx, fe, month, from, to)
	if err != nil || rc == nil {
		return
	}
	created, err := s.st.PutRecap(ctx, fe.FeedID, month, rc)
	if err != nil || !created {
		return
	}
	if id := s.familySystemMessage(ctx, fe.FeedID, gen.SystemMessage{Type: gen.SystemMessageTypeMonthlyRecap, Month: &rc.Month, Recap: rc}); id != uuid.Nil {
		_ = s.st.SetRecapMessage(ctx, fe.FeedID, month, id)
	}
}

// buildRecap computes a recap; nil when the family posted nothing that month.
func (s *Server) buildRecap(ctx context.Context, fe *store.FeedEngagement, month, from, to time.Time) (*gen.MonthlyRecap, error) {
	c, err := s.st.MonthCounts(ctx, fe.FeedID, from, to)
	if err != nil || c.Photos+c.Videos == 0 {
		return nil, err
	}
	out := gen.MonthlyRecap{FeedId: fe.FeedID, Month: month.Format("2006-01"), PhotosShared: c.Photos, VideosShared: c.Videos, SharerCount: c.Sharers}
	if ml, err := s.st.MostLovedPost(ctx, fe.FeedID, from, to); err != nil {
		return nil, err
	} else if ml != nil {
		if rows, err := s.st.ItemRowsByIDs(ctx, s.st.Q(), []uuid.UUID{ml.ItemID}); err == nil {
			if r, ok := rows[ml.ItemID]; ok {
				out.MostLoved = &struct {
					Item          gen.Item `json:"item"`
					PostId        gen.Id   `json:"post_id"`
					ReactionCount *int     `json:"reaction_count,omitempty"`
				}{Item: s.itemJSON(&r), PostId: ml.PostID, ReactionCount: ptr(ml.Reactions)}
			}
		}
	}
	fr, err := s.st.FreshestFrames(ctx, fe.FeedID, feedZoneName(fe), from, to, 3)
	if err != nil {
		return nil, err
	}
	frames := make([]struct {
		DaysWithNewPhotos int    `json:"days_with_new_photos"`
		FrameId           gen.Id `json:"frame_id"`
		FrameName         string `json:"frame_name"`
	}, 0, len(fr))
	for _, f := range fr {
		frames = append(frames, struct {
			DaysWithNewPhotos int    `json:"days_with_new_photos"`
			FrameId           gen.Id `json:"frame_id"`
			FrameName         string `json:"frame_name"`
		}{DaysWithNewPhotos: f.Days, FrameId: f.FrameID, FrameName: f.FrameName})
	}
	out.FreshestFrames = &frames
	nm, err := s.st.NewMembers(ctx, fe.FeedID, from, to)
	if err != nil {
		return nil, err
	}
	refs := s.engPersonRefs(ctx, nm)
	out.NewMembers = &refs
	if fe.StreakEnabled {
		if st, err := s.st.GetStreak(ctx, s.st.Q(), fe.FeedID); err == nil {
			out.StreakWeeks = ptr(st.CurrentWeeks)
		}
	}
	return &out, nil
}

func (s *Server) GetFeedRecap(ctx context.Context, req gen.GetFeedRecapRequestObject) (gen.GetFeedRecapResponseObject, error) {
	if _, err := s.feedView(ctx, s.st.Q(), req.FeedId); err != nil {
		return nil, err
	}
	m, err := time.Parse("2006-01", req.Month)
	if err != nil {
		return nil, problem.New(problem.NotFound, "recap not found")
	}
	b, err := s.st.GetRecap(ctx, s.st.Q(), req.FeedId, m)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "recap not found")
	} else if err != nil {
		return nil, err
	}
	return ok(b), nil
}

// ---------------------------------------------------------------- personal milestones

func milestoneOut(m *store.Milestone) gen.Milestone {
	return gen.Milestone{Key: gen.MilestoneKey(m.Key), AchievedAt: m.AchievedAt, Shown: m.Shown, Seen: m.SeenAt != nil}
}

// milestonesFor lists the milestones the statistics reach at now.
func milestonesFor(st *store.SharingStats, now time.Time) []string {
	var out []string
	add := func(ok bool, key string) {
		if ok {
			out = append(out, key)
		}
	}
	add(st.Items >= 1, "first_photo")
	add(st.Items >= 10, "photos_10")
	add(st.Items >= 100, "photos_100")
	add(st.Items >= 1000, "photos_1000")
	add(st.Videos >= 1, "first_video")
	add(st.FirstSend != nil && st.LastSend != nil && !st.FirstSend.After(now.AddDate(0, 0, -365)) && !st.LastSend.Before(now.AddDate(0, 0, -30)), "year_of_sharing")
	add(st.Loves >= 100, "loves_100")
	return out
}

// checkMilestones records the user's newly reached milestones and tells their apps (websocket only).
func (s *Server) checkMilestones(ctx context.Context, userID uuid.UUID, now time.Time) {
	st, err := s.st.UserSharingStats(ctx, userID)
	if err != nil {
		return
	}
	for _, key := range milestonesFor(st, now) {
		created, err := s.st.AchieveMilestone(ctx, userID, key, now)
		if err != nil || !created {
			continue
		}
		s.notify.User(userID, notify.Event{"type": "milestone.achieved", "milestone": gen.Milestone{
			Key: gen.MilestoneKey(key), AchievedAt: now, Shown: false, Seen: false}}, nil)
	}
}

func (s *Server) ListMyMilestones(ctx context.Context, _ gen.ListMyMilestonesRequestObject) (gen.ListMyMilestonesResponseObject, error) {
	ms, err := s.st.Milestones(ctx, s.st.Q(), me(ctx).ID)
	if err != nil {
		return nil, err
	}
	out := make([]gen.Milestone, len(ms))
	for i := range ms {
		out[i] = milestoneOut(&ms[i])
	}
	return ok(out), nil
}

func (s *Server) UpdateMyMilestone(ctx context.Context, req gen.UpdateMyMilestoneRequestObject) (gen.UpdateMyMilestoneResponseObject, error) {
	var seen *time.Time
	if req.Body.Seen != nil && bool(*req.Body.Seen) {
		seen = ptr(s.now())
	}
	m, err := s.st.UpdateMilestone(ctx, me(ctx).ID, string(req.MilestoneKey), req.Body.Shown, seen)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "milestone not found")
	} else if err != nil {
		return nil, err
	}
	return ok(milestoneOut(m)), nil
}
