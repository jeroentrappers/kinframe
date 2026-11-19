package api

// Weekly prompts (README §5.20 "Weekly prompts"): the server's localized list, the fixed rotation, admins'
// choices, tagging answers, the family chat announcement and the frame's `prompts` change.

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	openapi_types "github.com/oapi-codegen/runtime/types"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/i18n"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// promptLibrary is the server's list in its fixed rotation order; titles are i18n keys "prompt.<key>" in
// locales/engagement.
var promptLibrary = []string{
	"cooked", "view_now", "old_photo_of_you", "morning", "favourite_place", "made_you_smile", "window", "pet",
	"hands", "growing", "weekend", "childhood", "grandparents", "sky", "cup", "reading", "walk", "drawing",
	"table", "small_celebration", "together", "workplace", "hobby", "season", "favourite_dish", "memories_place",
	"something_new", "cosy_corner", "treasure", "wedding", "first_ride", "holiday_memory", "school_days", "smile",
	"yellow", "blue", "baked", "sunset", "market", "family_recipe", "then_and_now", "street", "play", "game_night",
	"flowers", "proud", "thankful", "rainy_day", "little_things", "music",
}

func isLibraryKey(k string) bool {
	for _, x := range promptLibrary {
		if x == k {
			return true
		}
	}
	return false
}

func promptTitle(lang, key string) string { return i18n.T(lang, "prompt."+key, nil) }

const (
	promptsPlanned  = 4  // weeks ahead in listFeedPrompts
	promptsPast     = 12 // weeks back
	promptNoRepeat  = 52 // weeks a key is not used again
	promptAnnounceH = 9  // Monday 09:00 family time
)

// promptID is the stable id of the server's prompt for a family's week, so planned prompts keep their id
// when the week starts and its row is made.
func promptID(feedID uuid.UUID, monday time.Time) uuid.UUID {
	return uuid.NewSHA1(feedID, []byte("prompt:"+monday.Format(dateLayout)))
}

// rotationStart spreads families over the list so they don't all get the same prompt the same week.
func rotationStart(feedID uuid.UUID) int {
	sum := sha256.Sum256(feedID[:])
	return int(binary.BigEndian.Uint32(sum[:4]) % uint32(len(promptLibrary)))
}

// weekIndex numbers Mondays (any fixed origin works).
func weekIndex(monday time.Time) int {
	return int(monday.Sub(time.Date(2024, 1, 1, 0, 0, 0, 0, time.UTC)).Hours() / (24 * 7))
}

// rotationKey is the server's choice for a week: the next key in the fixed rotation that the family has not
// had in the 52 weeks before (used: week -> key), or the rotation's own key when every key was used.
func rotationKey(feedID uuid.UUID, monday time.Time, used map[time.Time]string) string {
	n := len(promptLibrary)
	base := ((rotationStart(feedID)+weekIndex(monday))%n + n) % n
	recent := map[string]bool{}
	for w, k := range used {
		if w.Before(monday) && !w.Before(monday.AddDate(0, 0, -7*promptNoRepeat)) {
			recent[k] = true
		}
	}
	for i := 0; i < n; i++ {
		if k := promptLibrary[(base+i)%n]; !recent[k] {
			return k
		}
	}
	return promptLibrary[base]
}

// promptWeek is one week's prompt as planned or stored.
type promptWeek struct {
	monday time.Time
	stored *store.FeedPrompt
	key    string // server prompts: the library key (stored or planned)
}

// planPrompts returns the weeks [from, to] (Mondays) with their stored or planned prompts. Planned weeks
// continue the rotation from the stored ones.
func (s *Server) planPrompts(ctx context.Context, q db.DBTX, feedID uuid.UUID, from, to, current time.Time) ([]promptWeek, error) {
	rows, err := s.st.FeedPrompts(ctx, q, feedID, from.AddDate(0, 0, -7*promptNoRepeat), to)
	if err != nil {
		return nil, err
	}
	byWeek := map[time.Time]*store.FeedPrompt{}
	used := map[time.Time]string{}
	for i := range rows {
		r := &rows[i]
		w := dateOf(r.WeekOf)
		byWeek[w] = r
		if r.LibraryKey != nil {
			used[w] = *r.LibraryKey
		}
	}
	var out []promptWeek
	for w := from; !w.After(to); w = w.AddDate(0, 0, 7) {
		pw := promptWeek{monday: w, stored: byWeek[w]}
		switch {
		case pw.stored != nil && pw.stored.LibraryKey != nil:
			pw.key = *pw.stored.LibraryKey
		case pw.stored == nil && !w.Before(current):
			pw.key = rotationKey(feedID, w, used)
			used[w] = pw.key
		}
		out = append(out, pw)
	}
	return out, nil
}

// promptOut renders a FeedPrompt in lang (nil for past weeks without a prompt).
func (s *Server) promptOut(feedID uuid.UUID, pw promptWeek, current time.Time, lang string, people map[uuid.UUID]store.Person) *gen.FeedPrompt {
	out := gen.FeedPrompt{FeedId: feedID, WeekOf: openapi_types.Date{Time: pw.monday}, Source: "server", AnswerCount: ptr(0)}
	switch {
	case pw.monday.Equal(current):
		out.Status = "current"
	case pw.monday.After(current):
		out.Status = "planned"
	default:
		out.Status = "past"
	}
	if pw.stored != nil {
		p := pw.stored
		out.Id, out.AnswerCount = p.ID, ptr(p.Answers)
		switch p.Source {
		case "custom":
			out.Source, out.Title = "custom", deref(p.Title)
		case "skipped":
			out.Source, out.Status = "custom", "skipped"
		default:
			out.LibraryKey, out.Title = p.LibraryKey, promptTitle(lang, deref(p.LibraryKey))
		}
		if p.SetBy != nil {
			if person, ok := people[*p.SetBy]; ok {
				ref := s.engPersonRef(person)
				out.SetBy = &ref
			}
		}
		return &out
	}
	if pw.key == "" {
		return nil
	}
	out.Id, out.LibraryKey, out.Title = promptID(feedID, pw.monday), ptr(pw.key), promptTitle(lang, pw.key)
	return &out
}

func (s *Server) promptPeople(ctx context.Context, weeks []promptWeek) map[uuid.UUID]store.Person {
	var ids []uuid.UUID
	for _, w := range weeks {
		if w.stored != nil && w.stored.SetBy != nil {
			ids = append(ids, *w.stored.SetBy)
		}
	}
	ps, _ := s.st.People(ctx, s.st.Q(), engUnique(ids))
	return ps
}

func (s *Server) ListFeedPrompts(ctx context.Context, req gen.ListFeedPromptsRequestObject) (gen.ListFeedPromptsResponseObject, error) {
	if _, err := s.feedView(ctx, s.st.Q(), req.FeedId); err != nil {
		return nil, err
	}
	fe, err := s.st.GetFeedEngagement(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, notFound(err, "feed")
	}
	out := []gen.FeedPrompt{}
	if !fe.PromptsEnabled {
		return ok(out), nil
	}
	current := mondayOf(s.now(), feedZone(fe))
	weeks, err := s.planPrompts(ctx, s.st.Q(), fe.FeedID, current.AddDate(0, 0, -7*promptsPast), current.AddDate(0, 0, 7*promptsPlanned), current)
	if err != nil {
		return nil, err
	}
	people := s.promptPeople(ctx, weeks)
	lang := engLang(ctx)
	for i := len(weeks) - 1; i >= 0; i-- {
		if p := s.promptOut(fe.FeedID, weeks[i], current, lang, people); p != nil {
			out = append(out, *p)
		}
	}
	return ok(out), nil
}

func (s *Server) ListPromptLibrary(ctx context.Context, _ gen.ListPromptLibraryRequestObject) (gen.ListPromptLibraryResponseObject, error) {
	lang := engLang(ctx)
	out := make([]gen.PromptLibraryEntry, len(promptLibrary))
	for i, k := range promptLibrary {
		out[i] = gen.PromptLibraryEntry{Key: k, Title: promptTitle(lang, k)}
	}
	return ok(out), nil
}

// adminPromptWeek checks an admin's week: a Monday, the current week or later.
func (s *Server) adminPromptWeek(ctx context.Context, feedID uuid.UUID, weekOf time.Time) (*store.FeedEngagement, time.Time, error) {
	v, err := s.feedView(ctx, s.st.Q(), feedID)
	if err != nil {
		return nil, time.Time{}, err
	}
	if err := requireFeedAdmin(v); err != nil {
		return nil, time.Time{}, err
	}
	fe, err := s.st.GetFeedEngagement(ctx, s.st.Q(), feedID)
	if err != nil {
		return nil, time.Time{}, notFound(err, "feed")
	}
	week := dateOf(weekOf)
	if !isMonday(week) {
		return nil, time.Time{}, problem.Validation("weekOf", "monday", "week_of must be a Monday")
	}
	current := mondayOf(s.now(), feedZone(fe))
	if week.Before(current) {
		return nil, time.Time{}, problem.New(problem.InvalidTransition, "past weeks can't be changed")
	}
	return fe, current, nil
}

func (s *Server) SetFeedPrompt(ctx context.Context, req gen.SetFeedPromptRequestObject) (gen.SetFeedPromptResponseObject, error) {
	fe, current, err := s.adminPromptWeek(ctx, req.FeedId, req.WeekOf.Time)
	if err != nil {
		return nil, err
	}
	b := req.Body
	n := 0
	if b.LibraryKey != nil {
		n++
	}
	if b.Title != nil {
		n++
	}
	if b.Skip != nil && bool(*b.Skip) {
		n++
	}
	if n != 1 {
		return nil, problem.Validation("", "one_of", "choose exactly one of library_key, title or skip")
	}
	week := dateOf(req.WeekOf.Time)
	p := store.FeedPrompt{ID: promptID(fe.FeedID, week), FeedID: fe.FeedID, WeekOf: week, SetBy: &me(ctx).ID} // the planned id
	switch {
	case b.LibraryKey != nil:
		if !isLibraryKey(*b.LibraryKey) {
			return nil, problem.Validation("library_key", "enum", "unknown prompt")
		}
		p.Source, p.LibraryKey = "server", b.LibraryKey
	case b.Title != nil:
		t := strings.TrimSpace(*b.Title)
		if t == "" || utf8.RuneCountInString(t) > 80 {
			return nil, problem.Validation("title", "maxLength", "a title of 1 to 80 characters is required")
		}
		p.Source, p.Title = "custom", &t
	default:
		p.Source = "skipped"
	}
	if _, err := s.st.PutFeedPrompt(ctx, s.st.Q(), p); err != nil {
		return nil, err
	}
	return s.promptChanged(ctx, fe, week, current)
}

func (s *Server) promptChanged(ctx context.Context, fe *store.FeedEngagement, week, current time.Time) (Response, error) {
	weeks, err := s.planPrompts(ctx, s.st.Q(), fe.FeedID, week, week, current)
	if err != nil {
		return Response{}, err
	}
	out := s.promptOut(fe.FeedID, weeks[0], current, engLang(ctx), s.promptPeople(ctx, weeks))
	if week.Equal(current) {
		s.publishPrompt(ctx, fe, weeks[0], current)
		s.syncPromptFramesOfFeed(ctx, fe.FeedID)
	}
	return ok(out), nil
}

func (s *Server) ResetFeedPrompt(ctx context.Context, req gen.ResetFeedPromptRequestObject) (gen.ResetFeedPromptResponseObject, error) {
	fe, current, err := s.adminPromptWeek(ctx, req.FeedId, req.WeekOf.Time)
	if err != nil {
		return nil, err
	}
	week := dateOf(req.WeekOf.Time)
	if !week.Equal(current) {
		if err := s.st.DeleteFeedPrompt(ctx, s.st.Q(), fe.FeedID, week); err != nil {
			return nil, err
		}
		return noContent(), nil
	}
	// The current week goes back to the server's choice in place, so its id and tagged answers stay.
	key, err := s.serverKeyFor(ctx, fe.FeedID, week)
	if err != nil {
		return nil, err
	}
	if _, err := s.st.PutFeedPrompt(ctx, s.st.Q(), store.FeedPrompt{ID: promptID(fe.FeedID, week), FeedID: fe.FeedID, WeekOf: week,
		Source: "server", LibraryKey: &key}); err != nil {
		return nil, err
	}
	if _, err := s.promptChanged(ctx, fe, week, current); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// serverKeyFor is the rotation's key for a week, ignoring what is stored for that week itself.
func (s *Server) serverKeyFor(ctx context.Context, feedID uuid.UUID, week time.Time) (string, error) {
	rows, err := s.st.FeedPrompts(ctx, s.st.Q(), feedID, week.AddDate(0, 0, -7*promptNoRepeat), week.AddDate(0, 0, -7))
	if err != nil {
		return "", err
	}
	used := map[time.Time]string{}
	for _, r := range rows {
		if r.LibraryKey != nil {
			used[dateOf(r.WeekOf)] = *r.LibraryKey
		}
	}
	return rotationKey(feedID, week, used), nil
}

// startPromptWeek makes the current week's row (the server's choice) when the family has prompts on and the
// week has none yet, and tells the family.
func (s *Server) startPromptWeek(ctx context.Context, fe *store.FeedEngagement, now time.Time) {
	if !fe.PromptsEnabled {
		return
	}
	current := mondayOf(now, feedZone(fe))
	if _, err := s.st.GetFeedPromptOfWeek(ctx, s.st.Q(), fe.FeedID, current); err == nil {
		return
	} else if err != store.ErrNotFound {
		s.log.Warn("week prompt", "feed", fe.FeedID, "err", err)
		return
	}
	weeks, err := s.planPrompts(ctx, s.st.Q(), fe.FeedID, current, current, current)
	if err != nil || weeks[0].key == "" {
		return
	}
	if _, err := s.st.PutFeedPrompt(ctx, s.st.Q(), store.FeedPrompt{ID: promptID(fe.FeedID, current), FeedID: fe.FeedID, WeekOf: current,
		Source: "server", LibraryKey: &weeks[0].key}); err != nil {
		s.log.Warn("week prompt", "feed", fe.FeedID, "err", err)
		return
	}
	weeks, err = s.planPrompts(ctx, s.st.Q(), fe.FeedID, current, current, current)
	if err == nil {
		s.publishPrompt(ctx, fe, weeks[0], current)
	}
	s.syncPromptFramesOfFeed(ctx, fe.FeedID)
}

// publishPrompt sends prompt.updated to the family, in each member's language.
func (s *Server) publishPrompt(ctx context.Context, fe *store.FeedEngagement, pw promptWeek, current time.Time) {
	ids, err := s.st.FeedMemberIDs(ctx, s.st.Q(), fe.FeedID)
	if err != nil {
		return
	}
	prefs, _ := s.st.ReminderPrefsOf(ctx, s.st.Q(), ids)
	people := s.promptPeople(ctx, []promptWeek{pw})
	for _, u := range ids {
		lang := i18n.Pick(deref(prefs[u].Locale))
		if p := s.promptOut(fe.FeedID, pw, current, lang, people); p != nil {
			s.notify.User(u, notify.Event{"type": "prompt.updated", "feed_id": fe.FeedID, "prompt": p}, nil)
		}
	}
}

// familyLang is the language of a family's own texts (system messages): its owner's.
func familyLang(fe *store.FeedEngagement) string { return i18n.Pick(deref(fe.OwnerLocale)) }

// announcePrompt posts the week's prompt in the family chat once, from Monday 09:00 family time.
func (s *Server) announcePrompt(ctx context.Context, fe *store.FeedEngagement, now time.Time) {
	if !fe.PromptsEnabled {
		return
	}
	loc := feedZone(fe)
	current := mondayOf(now, loc)
	start, _ := weekBounds(current, loc)
	if now.Before(start.Add(promptAnnounceH * time.Hour)) {
		return
	}
	p, err := s.st.GetFeedPromptOfWeek(ctx, s.st.Q(), fe.FeedID, current)
	if err != nil || p.AnnouncedAt != nil || p.Source == "skipped" {
		return
	}
	if claimed, err := s.st.MarkPromptAnnounced(ctx, p.ID, now); err != nil || !claimed {
		return
	}
	title := deref(p.Title)
	if p.LibraryKey != nil {
		title = promptTitle(familyLang(fe), *p.LibraryKey)
	}
	s.familySystemMessage(ctx, fe.FeedID, gen.SystemMessage{Type: gen.SystemMessageTypePrompt, PromptId: &p.ID, PromptTitle: &title})
}

// ---------------------------------------------------------------- tagging

// taggablePrompt resolves a prompt id of the family's current or previous week (materializing the current
// week's server prompt when the scheduler has not yet). Errors are 422.
func (s *Server) taggablePrompt(ctx context.Context, id uuid.UUID, feeds []uuid.UUID) (*store.FeedPrompt, error) {
	bad := problem.Validation("prompt_id", "not_current", "only this week's or last week's prompt")
	p, err := s.st.GetFeedPrompt(ctx, s.st.Q(), id)
	if err == store.ErrNotFound {
		// A planned id of the current week before its row was made.
		for _, f := range feeds {
			fe, err := s.st.GetFeedEngagement(ctx, s.st.Q(), f)
			if err != nil || !fe.PromptsEnabled {
				continue
			}
			if current := mondayOf(s.now(), feedZone(fe)); promptID(f, current) == id {
				s.startPromptWeek(ctx, fe, s.now())
				p, err = s.st.GetFeedPrompt(ctx, s.st.Q(), id)
				if err == nil {
					break
				}
			}
		}
		if p == nil {
			return nil, bad
		}
	} else if err != nil {
		return nil, err
	}
	found := false
	for _, f := range feeds {
		found = found || f == p.FeedID
	}
	if !found {
		return nil, problem.Validation("prompt_id", "feed", "the prompt's family must be one of feed_ids")
	}
	fe, err := s.st.GetFeedEngagement(ctx, s.st.Q(), p.FeedID)
	if err != nil {
		return nil, err
	}
	current := mondayOf(s.now(), feedZone(fe))
	week := dateOf(p.WeekOf)
	if p.Source == "skipped" || !fe.PromptsEnabled || (!week.Equal(current) && !week.Equal(current.AddDate(0, 0, -7))) {
		return nil, bad
	}
	return p, nil
}

// checkSendPrompt validates SendCreate.prompt_id before the send is made.
func (s *Server) checkSendPrompt(ctx context.Context, promptID *uuid.UUID, feeds []uuid.UUID) (*store.FeedPrompt, error) {
	if promptID == nil {
		return nil, nil
	}
	return s.taggablePrompt(ctx, *promptID, feeds)
}

// tagSendPrompt tags the send's post in the prompt's family.
func (s *Server) tagSendPrompt(ctx context.Context, sendID uuid.UUID, p *store.FeedPrompt) {
	if p == nil {
		return
	}
	var postID uuid.UUID
	if err := s.st.Q().QueryRow(ctx, `SELECT id FROM feed_posts WHERE feed_id = $1 AND send_id = $2 AND removed_at IS NULL`,
		p.FeedID, sendID).Scan(&postID); err != nil {
		return
	}
	if err := s.st.TagPost(ctx, s.st.Q(), postID, &p.ID); err != nil {
		s.log.Warn("tag post", "post", postID, "err", err)
	}
}

func (s *Server) TagPostPrompt(ctx context.Context, req gen.TagPostPromptRequestObject) (gen.TagPostPromptResponseObject, error) {
	var feedID uuid.UUID
	var poster *uuid.UUID
	err := s.st.Q().QueryRow(ctx, `SELECT p.feed_id, p.poster_user_id FROM feed_posts p JOIN feeds f ON f.id = p.feed_id AND f.deleted_at IS NULL
		JOIN feed_members m ON m.feed_id = p.feed_id AND m.user_id = $2
		WHERE p.id = $1 AND p.removed_at IS NULL`, req.PostId, me(ctx).ID).Scan(&feedID, &poster)
	if db.NotFound(err) {
		var isFramePost bool
		_ = s.st.Q().QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM frame_posts WHERE id = $1 AND removed_at IS NULL)`, req.PostId).Scan(&isFramePost)
		if isFramePost {
			if access, _ := s.postAccessFor(ctx, me(ctx).ID, req.PostId); access.Visible || s.isFramePostSender(ctx, req.PostId) {
				return nil, problem.Validation("post_id", "frame_post", "frame posts can't be tagged")
			}
		}
		return nil, problem.New(problem.NotFound, "post not found")
	} else if err != nil {
		return nil, err
	}
	if poster == nil || *poster != me(ctx).ID {
		return nil, problem.New(problem.Forbidden, "only the poster can tag this post")
	}
	var promptID *uuid.UUID
	if req.Body.PromptId != nil {
		p, err := s.taggablePrompt(ctx, *req.Body.PromptId, []uuid.UUID{feedID})
		if err != nil {
			return nil, err
		}
		promptID = &p.ID
	}
	if err := s.st.TagPost(ctx, s.st.Q(), req.PostId, promptID); err != nil {
		return nil, err
	}
	s.reemitPostPlacements(ctx, req.PostId)
	out, err := s.taggedPostOut(ctx, req.PostId)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

func (s *Server) isFramePostSender(ctx context.Context, postID uuid.UUID) bool {
	var ok bool
	_ = s.st.Q().QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM frame_posts WHERE id = $1 AND sender_user_id = $2)`, postID, me(ctx).ID).Scan(&ok)
	return ok
}

// reemitPostPlacements re-sends a post's placements to frames (Placement.prompt_id changed).
func (s *Server) reemitPostPlacements(ctx context.Context, postID uuid.UUID) {
	byFrame, err := s.st.PostPlacements(ctx, s.st.Q(), postID)
	if err != nil || len(byFrame) == 0 {
		return
	}
	frames := make([]uuid.UUID, 0, len(byFrame))
	for f := range byFrame {
		frames = append(frames, f)
	}
	sort.Slice(frames, func(i, j int) bool { return frames[i].String() < frames[j].String() })
	if err := s.st.InTx(ctx, func(tx *store.Tx) error {
		for _, f := range frames {
			if err := tx.FeedPutEntities(ctx, f, store.FeedPlacement, byFrame[f], false); err != nil {
				return err
			}
		}
		return nil
	}); err != nil {
		s.log.Warn("re-emit placements", "post", postID, "err", err)
	}
}

// taggedPostOut renders the feed post after tagging. The thread and reactions are server-social's (the
// timeline renderer); this answer carries the post itself and its prompt.
func (s *Server) taggedPostOut(ctx context.Context, postID uuid.UUID) (gen.TimelinePost, error) {
	var feedID uuid.UUID
	var feedName string
	var poster *uuid.UUID
	var sendID, promptID, circleID *uuid.UUID
	var created time.Time
	err := s.st.Q().QueryRow(ctx, `SELECT p.feed_id, f.name, p.poster_user_id, p.send_id, p.prompt_id, p.circle_id, p.created_at
		FROM feed_posts p JOIN feeds f ON f.id = p.feed_id WHERE p.id = $1`, postID).Scan(&feedID, &feedName, &poster, &sendID, &promptID, &circleID, &created)
	if err != nil {
		return gen.TimelinePost{}, notFound(err, "post")
	}
	out := gen.TimelinePost{Id: postID, Kind: "feed", FeedId: &feedID, FeedName: &feedName, CreatedAt: created, SendId: sendID,
		CircleId: circleID, Items: []gen.Item{},
		Thread: gen.ThreadSummary{PostId: postID, State: "open", LatestComments: []gen.Comment{},
			Reactions: gen.ReactionSummary{Frames: []gen.FrameReactionRef{}}}}
	out.Thread.Reactions.Counts = make([]struct {
		Count int                `json:"count"`
		Emoji gen.PersonReaction `json:"emoji"`
	}, 0)
	out.Thread.Reactions.People = make([]struct {
		Emoji  gen.PersonReaction `json:"emoji"`
		Person gen.PersonRef      `json:"person"`
	}, 0)
	var state string
	var count int
	if s.st.Q().QueryRow(ctx, `SELECT state, comment_count FROM threads WHERE post_id = $1`, postID).Scan(&state, &count) == nil {
		out.Thread.State, out.Thread.CommentCount = gen.ThreadState(state), count
	}
	if poster != nil {
		if refs := s.engPersonRefs(ctx, []uuid.UUID{*poster}); len(refs) == 1 {
			out.Poster = refs[0]
		}
	}
	rows, err := s.st.FeedPostItemRows(ctx, s.st.Q(), []uuid.UUID{postID})
	if err != nil {
		return out, err
	}
	for i := range rows {
		out.Items = append(out.Items, s.itemJSON(&rows[i].ItemRow))
	}
	if promptID != nil {
		if p, err := s.st.GetFeedPrompt(ctx, s.st.Q(), *promptID); err == nil {
			title := deref(p.Title)
			if p.LibraryKey != nil {
				title = promptTitle(engLang(ctx), *p.LibraryKey)
			}
			out.Prompt = &struct {
				Id    gen.Id `json:"id"`
				Title string `json:"title"`
			}{Id: p.ID, Title: title}
		}
	}
	return out, nil
}

// ---------------------------------------------------------------- the frame's `prompts` change

// framePrompts renders the frame's current prompts in its language (nil: none).
func (s *Server) framePrompts(ctx context.Context, q db.DBTX, f *store.Frame) (*gen.FramePrompts, error) {
	var doc struct {
		Locale struct {
			Language string `json:"language"`
		} `json:"locale"`
	}
	_ = json.Unmarshal(f.Settings, &doc)
	lang := i18n.Pick(doc.Locale.Language)
	rows, err := engRows[framePromptFeed](q.Query(ctx, `SELECT su.feed_id FROM subscriptions su
		JOIN feeds fd ON fd.id = su.feed_id AND fd.deleted_at IS NULL
		JOIN feed_engagement fe ON fe.feed_id = su.feed_id AND fe.prompts_enabled
		WHERE su.frame_id = $1 AND su.status = 'active' ORDER BY su.feed_id`, f.ID))
	if err != nil || len(rows) == 0 {
		return nil, err
	}
	out := gen.FramePrompts{GeneratedAt: s.now().UTC().Truncate(time.Second)}
	for _, r := range rows {
		fe, err := s.st.GetFeedEngagement(ctx, q, r.FeedID)
		if err != nil {
			continue
		}
		current := mondayOf(s.now(), feedZone(fe))
		p, err := s.st.GetFeedPromptOfWeek(ctx, q, r.FeedID, current)
		if err != nil || p.Source == "skipped" {
			continue
		}
		title := deref(p.Title)
		if p.LibraryKey != nil {
			title = promptTitle(lang, *p.LibraryKey)
		}
		out.Prompts = append(out.Prompts, struct {
			FeedId   gen.Id             `json:"feed_id"`
			FeedName string             `json:"feed_name"`
			PromptId gen.Id             `json:"prompt_id"`
			Title    string             `json:"title"`
			WeekOf   openapi_types.Date `json:"week_of"`
		}{FeedId: r.FeedID, FeedName: fe.FeedName, PromptId: p.ID, Title: title, WeekOf: openapi_types.Date{Time: current}})
	}
	if len(out.Prompts) == 0 {
		return nil, nil
	}
	return &out, nil
}

type framePromptFeed struct {
	FeedID uuid.UUID `db:"feed_id"`
}

// promptsDigest is what decides whether the frame needs a new `prompts` change.
func promptsDigest(p *gen.FramePrompts) string {
	if p == nil {
		return ""
	}
	b, _ := json.Marshal(p.Prompts)
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// syncFramePrompts appends a `prompts` change when the frame's current prompts changed.
func (s *Server) syncFramePrompts(ctx context.Context, frameID uuid.UUID) error {
	return s.st.InTx(ctx, func(tx *store.Tx) error {
		f, err := tx.LockFrame(ctx, frameID)
		if err != nil {
			if err == store.ErrNotFound {
				return nil
			}
			return err
		}
		p, err := s.framePrompts(ctx, tx, f)
		if err != nil {
			return err
		}
		digest := promptsDigest(p)
		var prev string
		err = tx.QueryRow(ctx, `SELECT digest FROM frame_prompt_cards WHERE frame_id = $1`, frameID).Scan(&prev)
		if err != nil && !db.NotFound(err) {
			return err
		}
		if prev == digest {
			return nil
		}
		if _, err := tx.Exec(ctx, `INSERT INTO frame_prompt_cards (frame_id, digest, emitted_at) VALUES ($1, $2, now())
			ON CONFLICT (frame_id) DO UPDATE SET digest = EXCLUDED.digest, emitted_at = now()`, frameID, digest); err != nil {
			return err
		}
		return tx.FeedPut(ctx, frameID, store.FeedPrompts, frameID, false)
	})
}

// syncPromptFramesOfFeed syncs every frame subscribed to the family.
func (s *Server) syncPromptFramesOfFeed(ctx context.Context, feedID uuid.UUID) {
	rows, err := engRows[engID](s.st.Q().Query(ctx, `SELECT frame_id AS id FROM subscriptions WHERE feed_id = $1 AND status = 'active'
		UNION SELECT frame_id FROM frame_prompt_cards c WHERE EXISTS (SELECT 1 FROM subscriptions su WHERE su.frame_id = c.frame_id AND su.feed_id = $1)
		ORDER BY 1`, feedID))
	if err != nil {
		return
	}
	for _, r := range rows {
		if err := s.syncFramePrompts(ctx, r.ID); err != nil {
			s.log.Warn("frame prompts", "frame", r.ID, "err", err)
		}
	}
}

// syncAllFramePrompts syncs every frame that has or may need prompts (the scheduler).
func (s *Server) syncAllFramePrompts(ctx context.Context) {
	rows, err := engRows[engID](s.st.Q().Query(ctx, `SELECT DISTINCT su.frame_id AS id FROM subscriptions su
		JOIN feed_engagement fe ON fe.feed_id = su.feed_id AND fe.prompts_enabled WHERE su.status = 'active'
		UNION SELECT frame_id FROM frame_prompt_cards WHERE digest <> '' ORDER BY 1`))
	if err != nil {
		return
	}
	for _, r := range rows {
		if err := s.syncFramePrompts(ctx, r.ID); err != nil {
			s.log.Warn("frame prompts", "frame", r.ID, "err", err)
		}
	}
}
