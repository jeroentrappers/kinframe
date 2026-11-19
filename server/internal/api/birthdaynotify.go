package api

import (
	"context"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Birthday notifications (birthdays.md §7, §8; README §6.2, §6.3).

// birthdayViewers returns every member of the feed as a viewer of its birthdays.
func (s *Server) birthdayViewers(ctx context.Context, feedID uuid.UUID) ([]bdViewer, *store.Feed) {
	f, err := s.st.GetFeed(ctx, s.st.Q(), feedID)
	if err != nil {
		return nil, nil
	}
	ms, err := s.st.BirthdayMembers(ctx, s.st.Q(), []uuid.UUID{feedID})
	if err != nil {
		s.log.Warn("birthday members", "feed", feedID, "err", err)
		return nil, f
	}
	out := make([]bdViewer, len(ms))
	for i, m := range ms {
		out[i] = bdViewer{userID: m.UserID, role: m.Role, pcab: f.PostersCanAddBirthdays, loc: loadZone(deref(m.Timezone))}
	}
	return out, f
}

// birthdayAudience lists the members who may see the entry.
func (s *Server) birthdayAudience(ctx context.Context, feedID uuid.UUID, b *store.Birthday) []uuid.UUID {
	vs, _ := s.birthdayViewers(ctx, feedID)
	var out []uuid.UUID
	for _, v := range vs {
		if v.canSee(b) {
			out = append(out, v.userID)
		}
	}
	return out
}

// publishBirthday sends birthday.updated, rendered for each member who may
// see the entry (the year depends on who looks), except those in skip.
func (s *Server) publishBirthday(ctx context.Context, feedID, id uuid.UUID, skip map[uuid.UUID]bool) {
	b, err := s.st.GetBirthday(ctx, s.st.Q(), feedID, id)
	if err != nil {
		return
	}
	vs, _ := s.birthdayViewers(ctx, feedID)
	for _, v := range vs {
		if skip[v.userID] || !v.canSee(b) {
			continue
		}
		out, err := s.renderBirthday(ctx, v, b)
		if err != nil {
			s.log.Warn("render birthday", "err", err)
			return
		}
		s.notify.User(v.userID, notify.Event{"type": "birthday.updated", "feed_id": feedID, "birthday": out}, nil)
	}
}

// publishUnlinked tells members about entries whose member link was cleared.
func (s *Server) publishUnlinked(ctx context.Context, us []store.UnlinkedBirthday) {
	for _, u := range us {
		s.publishBirthday(ctx, u.FeedID, u.ID, nil)
	}
}

// announceSuggestion tells the feed's owner and admins about a poster's
// suggestion. Returns who was told (they need no birthday.updated as well).
func (s *Server) announceSuggestion(ctx context.Context, fv *store.FeedView, id uuid.UUID) map[uuid.UUID]bool {
	told := map[uuid.UUID]bool{}
	b, err := s.st.GetBirthday(ctx, s.st.Q(), fv.ID, id)
	if err != nil {
		return told
	}
	vs, _ := s.birthdayViewers(ctx, fv.ID)
	for _, v := range vs {
		if !v.admin() {
			continue
		}
		out, err := s.renderBirthday(ctx, v, b)
		if err != nil {
			return told
		}
		told[v.userID] = true
		s.notify.User(v.userID, notify.Event{"type": "birthday.suggested", "feed_id": fv.ID, "birthday": out},
			&notify.Push{Key: "birthday_suggestion", Args: map[string]string{"name": me(ctx).DisplayName, "person": b.DisplayName, "feed": fv.Name},
				IDs: map[string]string{"feed_id": fv.ID.String(), "birthday_id": id.String()}})
	}
	return told
}

// announceDecision tells the suggester that their suggestion was approved or rejected.
func (s *Server) announceDecision(ctx context.Context, fv *store.FeedView, id, suggester uuid.UUID, approved bool) {
	b, err := s.st.GetBirthday(ctx, s.st.Q(), fv.ID, id)
	if err != nil {
		return
	}
	m, err := s.st.GetFeedMember(ctx, s.st.Q(), fv.ID, suggester)
	if err != nil {
		return // the suggester left the feed
	}
	u, err := s.st.GetUser(ctx, s.st.Q(), suggester)
	if err != nil {
		return
	}
	v := bdViewer{userID: suggester, role: m.Role, pcab: fv.PostersCanAddBirthdays, loc: loadZone(deref(u.Timezone))}
	out, err := s.renderBirthday(ctx, v, b)
	if err != nil {
		return
	}
	text := "birthday_rejected"
	if approved {
		text = "birthday_approved"
	}
	s.notify.User(suggester, notify.Event{"type": "birthday.decided", "feed_id": fv.ID, "birthday": out},
		&notify.Push{Key: "birthday_decided", Text: text, Args: map[string]string{"person": b.DisplayName, "feed": fv.Name},
			IDs: map[string]string{"feed_id": fv.ID.String(), "birthday_id": id.String()}})
}

// memberPerson is the member's own view of one merged person: their entries
// with the same name key and date in all their feeds, oldest first.
func (s *Server) memberPerson(ctx context.Context, userID uuid.UUID, g *store.FrameBirthdayGroup) []store.MemberBirthdayEntry {
	key := g.Entries[0].NameKey
	es, err := s.st.MemberEntries(ctx, s.st.Q(), []uuid.UUID{userID}, []int{g.Month}, []int{g.Day}, false)
	if err != nil {
		s.log.Warn("member birthday entries", "err", err)
		return nil
	}
	var out []store.MemberBirthdayEntry
	for _, e := range es {
		if e.NameKey == key {
			out = append(out, e)
		}
	}
	return out
}

// announceGreetings sends birthday.greeting to every member of the feeds that
// got a greeting, and one push per member (with birthday_notifications on in
// one of them) whose body counts the frames that greeted the person that day.
// Its birthday_id is the member's oldest entry of the person, so greetings
// from different frames share the tag birthday:<birthday_id>:<date>.
func (s *Server) announceGreetings(ctx context.Context, f *store.Frame, g *store.FrameBirthdayGroup, rows []store.BirthdayGreeting, date time.Time) {
	if len(rows) == 0 {
		return
	}
	feeds := make([]uuid.UUID, 0, len(rows))
	for _, r := range rows {
		feeds = append(feeds, r.FeedID)
	}
	ms, err := s.st.BirthdayMembers(ctx, s.st.Q(), feeds)
	if err != nil {
		s.log.Warn("birthday members", "err", err)
		return
	}
	notified := map[uuid.UUID]bool{} // members with birthday_notifications on in a greeted feed
	for _, m := range ms {
		if m.BirthdayNotifications {
			notified[m.UserID] = true
		}
	}
	day := date.Format(dateLayout)
	pushed := map[uuid.UUID]bool{}
	for _, r := range rows {
		ev := notify.Event{"type": "birthday.greeting", "feed_id": r.FeedID, "greeting": greetingOut(&r)}
		for _, m := range ms {
			if m.FeedID != r.FeedID {
				continue
			}
			var push *notify.Push
			if notified[m.UserID] && !pushed[m.UserID] {
				pushed[m.UserID] = true
				push = s.greetingPush(ctx, m.UserID, f, g, r, date, day)
			}
			s.notify.User(m.UserID, ev, push)
		}
	}
}

func (s *Server) greetingPush(ctx context.Context, userID uuid.UUID, f *store.Frame, g *store.FrameBirthdayGroup, r store.BirthdayGreeting, date time.Time, day string) *notify.Push {
	person, tagID, feedID := r.BirthdayName, r.BirthdayID, r.FeedID
	ids := []uuid.UUID{r.BirthdayID}
	if es := s.memberPerson(ctx, userID, g); len(es) > 0 {
		person, tagID, feedID = es[0].DisplayName, es[0].ID, es[0].FeedID
		ids = ids[:0]
		for _, e := range es {
			ids = append(ids, e.ID)
		}
	}
	n, err := s.st.CountGreetingFrames(ctx, s.st.Q(), ids, date)
	if err != nil || n < 1 {
		n = 1
	}
	text := "birthday_greeting"
	switch {
	case n == 2:
		text = "birthday_greeting_two"
	case n > 2:
		text = "birthday_greeting_many"
	}
	return &notify.Push{Key: "birthday_greeting", Text: text, Tag: "birthday:" + tagID.String() + ":" + day,
		Args: map[string]string{"frame": f.Name, "person": person, "count": strconv.Itoa(n - 1)},
		IDs: map[string]string{"feed_id": feedID.String(), "birthday_id": tagID.String(), "greeting_id": r.ID.String(),
			"frame_id": f.ID.String(), "date": day}}
}

// ---------------------------------------------------------------- the morning notification (§8.1)

// morningFrom and morningUntil bound the local hours of the morning notice
// (08:00, catching up until 20:00 when the server was down).
const (
	morningFrom  = 8
	morningUntil = 20
)

// BirthdayMornings sends the morning notices due at now: every member whose
// local time is between 08:00 and 20:00, who has not had today's notice and
// has at least one birthday today in a feed with birthday_notifications on
// (merged like on frames, without their own entry). Returns how many were sent.
func (s *Server) BirthdayMornings(ctx context.Context, now time.Time) (int, error) {
	utc := dateOf(now.UTC())
	// Every zone's local date is within one day of the UTC date.
	months, days := store.MonthDaysAround(utc.AddDate(0, 0, -1), utc, utc.AddDate(0, 0, 1))
	entries, err := s.st.MemberEntries(ctx, s.st.Q(), nil, months, days, true)
	if err != nil {
		return 0, err
	}
	byMember := map[uuid.UUID][]store.MemberBirthdayEntry{}
	var members []uuid.UUID
	var zoneless []uuid.UUID // feeds of members without a zone
	for _, e := range entries {
		if _, ok := byMember[e.MemberID]; !ok {
			members = append(members, e.MemberID)
		}
		byMember[e.MemberID] = append(byMember[e.MemberID], e)
		if e.MemberTimezone == nil || *e.MemberTimezone == "" {
			zoneless = append(zoneless, e.FeedID)
		}
	}
	var zones map[uuid.UUID][]string
	if len(zoneless) > 0 {
		if zones, err = s.st.FeedFrameZones(ctx, s.st.Q(), zoneless); err != nil {
			return 0, err
		}
	}
	sent := 0
	for _, uid := range members {
		es := byMember[uid]
		loc := loadZone(deref(es[0].MemberTimezone), commonZone(es, zones))
		local := now.In(loc)
		if local.Hour() < morningFrom || local.Hour() >= morningUntil {
			continue
		}
		today := dateOf(local)
		var todays []store.FrameBirthdayEntry
		for _, e := range es {
			if store.OccursOn(e.Month, e.Day, today) && (e.BirthYear == nil || *e.BirthYear < today.Year()) {
				todays = append(todays, e.FrameBirthdayEntry)
			}
		}
		var groups []store.FrameBirthdayGroup
		for _, g := range store.MergeBirthdays(todays) {
			own := false
			for _, e := range g.Entries {
				if e.UserID != nil && *e.UserID == uid {
					own = true // nobody needs to be told their own birthday
				}
			}
			if !own {
				groups = append(groups, g)
			}
		}
		if len(groups) == 0 {
			continue
		}
		claimed, err := s.st.ClaimBirthdayNotice(ctx, uid, today)
		if err != nil {
			return sent, err
		}
		if !claimed {
			continue
		}
		s.sendMorning(uid, groups, today)
		sent++
	}
	return sent, nil
}

// commonZone is the time zone most of the frames subscribed to the member's
// feeds have (ties: alphabetical), or "".
func commonZone(es []store.MemberBirthdayEntry, zones map[uuid.UUID][]string) string {
	if len(zones) == 0 {
		return ""
	}
	seen := map[uuid.UUID]bool{}
	count := map[string]int{}
	for _, e := range es {
		if seen[e.FeedID] {
			continue
		}
		seen[e.FeedID] = true
		for _, z := range zones[e.FeedID] {
			count[z]++
		}
	}
	best, n := "", 0
	for z, c := range count {
		if c > n || (c == n && z < best) {
			best, n = z, c
		}
	}
	return best
}

func (s *Server) sendMorning(userID uuid.UUID, groups []store.FrameBirthdayGroup, today time.Time) {
	day := today.Format(dateLayout)
	var list []map[string]any
	var names []string
	for _, g := range groups {
		for _, e := range g.Entries {
			list = append(list, map[string]any{"feed_id": e.FeedID, "birthday_id": e.ID})
		}
		names = append(names, g.Name)
	}
	sort.Strings(names)
	first := groups[0]
	push := &notify.Push{Key: "birthday_today", Text: "birthday_today", Args: map[string]string{"person": first.Name},
		IDs: map[string]string{"date": day, "feed_id": first.Entries[0].FeedID.String(), "birthday_id": first.ID.String()}}
	switch {
	case len(groups) > 1:
		shown := names
		if len(shown) > 3 {
			shown = append(append([]string(nil), shown[:3]...), "…")
		}
		push.Text = "birthday_today_many"
		push.Args = map[string]string{"count": strconv.Itoa(len(groups)), "names": strings.Join(shown, ", ")}
	case first.BirthYear != nil:
		push.Text = "birthday_today_age"
		push.Args["age"] = strconv.Itoa(today.Year() - *first.BirthYear)
	}
	s.notify.User(userID, notify.Event{"type": "birthday.today", "date": day, "birthdays": list}, push)
}

// RunBirthdayMornings checks for due morning notices every 5 minutes until ctx ends.
func (s *Server) RunBirthdayMornings(ctx context.Context) {
	t := time.NewTicker(5 * time.Minute)
	defer t.Stop()
	for {
		if n, err := s.BirthdayMornings(ctx, s.now()); err != nil && ctx.Err() == nil {
			s.log.Error("birthday mornings", "err", err)
		} else if n > 0 {
			s.log.Info("sent birthday notices", "count", n)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// PruneBirthdays deletes old rejected suggestions (and their photos),
// greetings and notices (maintenance loop).
func (s *Server) PruneBirthdays(ctx context.Context) error {
	keys, err := s.st.PruneBirthdays(ctx)
	for _, k := range keys {
		s.deleteBlob(ctx, k)
	}
	return err
}
