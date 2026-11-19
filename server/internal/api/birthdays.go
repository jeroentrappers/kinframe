package api

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/blob"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Birthdays (docs/spec/birthdays.md, README §5.13): the list of a feed,
// suggestions, photos, greetings from frames and the morning notification.
// The frames' `birthdays` change is kept in sync by the store
// (store.SyncFrameBirthdays) inside the transactions that change it.

// defaultZone is the settings default, the last fallback for a member's zone.
const defaultZone = "Europe/Brussels"

// maxGreetingsPerDay caps one frame's greetings per UTC day (against bugs).
const maxGreetingsPerDay = 50

func loadZone(names ...string) *time.Location {
	for _, n := range names {
		if n == "" || n == "Local" {
			continue
		}
		if loc, err := time.LoadLocation(n); err == nil {
			return loc
		}
	}
	loc, _ := time.LoadLocation(defaultZone)
	if loc == nil {
		return time.UTC
	}
	return loc
}

// dateOf is the calendar date of t in its own zone, as midnight UTC.
func dateOf(t time.Time) time.Time {
	return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)
}

const dateLayout = "2006-01-02"

// ---------------------------------------------------------------- permissions (§4.1)

// bdViewer is one member looking at a feed's birthdays.
type bdViewer struct {
	userID uuid.UUID
	role   string // owner, admin, poster
	pcab   bool   // the feed's posters_can_add_birthdays
	loc    *time.Location
}

func (v bdViewer) admin() bool { return v.role == "owner" || v.role == "admin" }

func (v bdViewer) addedByMe(b *store.Birthday) bool {
	return b.CreatedBy != nil && *b.CreatedBy == v.userID
}

func (v bdViewer) own(b *store.Birthday) bool { return b.UserID != nil && *b.UserID == v.userID }

// canSee: approved people to every member; suggestions to owner/admins and the suggester.
func (v bdViewer) canSee(b *store.Birthday) bool {
	return b.Status == "approved" || v.admin() || v.addedByMe(b)
}

// seesYear: owner/admins, whoever added it, the linked member, or show_age on.
func (v bdViewer) seesYear(b *store.Birthday) bool {
	return v.admin() || v.addedByMe(b) || v.own(b) || b.ShowAge
}

// fullEdit: every field (owner/admins; the suggester while pending; posters
// with posters_can_add_birthdays on what they added).
func (v bdViewer) fullEdit(b *store.Birthday) bool {
	return v.admin() || (v.addedByMe(b) && (b.Status == "pending" || v.pcab))
}

// canEdit: some fields at least (the linked member: year, show_age, photo, unlink).
func (v bdViewer) canEdit(b *store.Birthday) bool { return v.fullEdit(b) || v.own(b) }

func (v bdViewer) canDelete(b *store.Birthday) bool {
	return v.admin() || v.own(b) || (v.addedByMe(b) && (b.Status != "approved" || v.pcab))
}

func (v bdViewer) canDecide(b *store.Birthday) bool { return v.admin() && b.Status == "pending" }

func (s *Server) bdViewer(ctx context.Context, fv *store.FeedView) bdViewer {
	u := me(ctx)
	return bdViewer{userID: u.ID, role: fv.Role, pcab: fv.PostersCanAddBirthdays && fv.Role != "member", loc: loadZone(deref(u.Timezone))}
}

// visibleBirthday loads an entry the caller may see; others are 404 like unknown ones.
func (s *Server) visibleBirthday(ctx context.Context, feedID, id uuid.UUID) (*store.FeedView, bdViewer, *store.Birthday, error) {
	fv, err := s.feedView(ctx, s.st.Q(), feedID)
	if err != nil {
		return nil, bdViewer{}, nil, err
	}
	v := s.bdViewer(ctx, fv)
	b, err := s.st.GetBirthday(ctx, s.st.Q(), feedID, id)
	if err != nil {
		return nil, bdViewer{}, nil, notFound(err, "birthday")
	}
	if !v.canSee(b) {
		return nil, bdViewer{}, nil, problem.New(problem.NotFound, "birthday not found")
	}
	return fv, v, b, nil
}

// ---------------------------------------------------------------- JSON

type birthdayJSON struct {
	ID               uuid.UUID  `json:"id"`
	FeedID           uuid.UUID  `json:"feed_id"`
	Status           string     `json:"status"`
	DisplayName      string     `json:"display_name"`
	Day              int        `json:"day"`
	Month            int        `json:"month"`
	BirthYear        *int       `json:"birth_year,omitempty"`
	ShowAge          bool       `json:"show_age"`
	PhotoURL         *string    `json:"photo_url,omitempty"`
	HasOwnPhoto      bool       `json:"has_own_photo"`
	UserID           *uuid.UUID `json:"user_id,omitempty"`
	LinkedMemberName *string    `json:"linked_member_name,omitempty"`
	CreatedByName    *string    `json:"created_by_name,omitempty"`
	CreatedByID      *uuid.UUID `json:"created_by_id,omitempty"`
	Suggested        bool       `json:"suggested"`
	DecidedByName    *string    `json:"decided_by_name,omitempty"`
	DecidedAt        *time.Time `json:"decided_at,omitempty"`
	NextBirthday     string     `json:"next_birthday"`
	Turns            *int       `json:"turns,omitempty"`
	GreetingsToday   int        `json:"greetings_today"`
	CanEdit          bool       `json:"can_edit"`
	CanDecide        bool       `json:"can_decide"`
	CreatedAt        time.Time  `json:"created_at"`
	UpdatedAt        time.Time  `json:"updated_at"`
}

func (s *Server) birthdayOut(b *store.Birthday, v bdViewer, today time.Time, greetings int) birthdayJSON {
	out := birthdayJSON{
		ID: b.ID, FeedID: b.FeedID, Status: b.Status, DisplayName: b.DisplayName, Day: b.Day, Month: b.Month, ShowAge: b.ShowAge,
		PhotoURL: s.avatarURL(b.Photo()), HasOwnPhoto: b.PhotoKey != nil, UserID: b.UserID, LinkedMemberName: b.LinkedName,
		CreatedByName: b.CreatedByName, Suggested: b.Suggested, CanEdit: v.canEdit(b), CanDecide: v.canDecide(b),
		CreatedAt: b.CreatedAt, UpdatedAt: b.UpdatedAt,
	}
	if b.CreatedByName != nil { // the account still exists
		out.CreatedByID = b.CreatedBy
	}
	if b.Suggested && (v.admin() || v.addedByMe(b)) {
		out.DecidedByName, out.DecidedAt = b.DecidedByName, b.DecidedAt
	}
	var year *int
	if v.seesYear(b) {
		year = b.BirthYear
	}
	out.BirthYear = year
	next := store.NextOccurrence(b.Month, b.Day, year, today)
	out.NextBirthday = next.Format(dateLayout)
	if year != nil {
		out.Turns = ptr(next.Year() - *year)
	}
	if next.Equal(today) {
		out.GreetingsToday = greetings
	}
	return out
}

// renderBirthdays renders entries for one viewer (greetings counted for the viewer's today).
func (s *Server) renderBirthdays(ctx context.Context, v bdViewer, bs []store.Birthday) ([]birthdayJSON, error) {
	today := dateOf(s.now().In(v.loc))
	var todays []uuid.UUID
	for i := range bs {
		if store.OccursOn(bs[i].Month, bs[i].Day, today) {
			todays = append(todays, bs[i].ID)
		}
	}
	counts, err := s.st.GreetingCounts(ctx, s.st.Q(), todays, today)
	if err != nil {
		return nil, err
	}
	out := make([]birthdayJSON, len(bs))
	for i := range bs {
		out[i] = s.birthdayOut(&bs[i], v, today, counts[bs[i].ID])
	}
	return out, nil
}

func (s *Server) renderBirthday(ctx context.Context, v bdViewer, b *store.Birthday) (birthdayJSON, error) {
	out, err := s.renderBirthdays(ctx, v, []store.Birthday{*b})
	if err != nil {
		return birthdayJSON{}, err
	}
	return out[0], nil
}

type greetingJSON struct {
	ID            uuid.UUID `json:"id"`
	FeedID        uuid.UUID `json:"feed_id"`
	BirthdayID    uuid.UUID `json:"birthday_id"`
	BirthdayName  string    `json:"birthday_name"`
	FrameID       uuid.UUID `json:"frame_id"`
	FrameName     string    `json:"frame_name"`
	FrameLocation *string   `json:"frame_location,omitempty"`
	Date          string    `json:"date"`
	CreatedAt     time.Time `json:"created_at"`
}

func greetingOut(g *store.BirthdayGreeting) greetingJSON {
	return greetingJSON{ID: g.ID, FeedID: g.FeedID, BirthdayID: g.BirthdayID, BirthdayName: g.BirthdayName, FrameID: g.FrameID,
		FrameName: g.FrameName, FrameLocation: g.FrameLocation, Date: g.LocalDate.Format(dateLayout), CreatedAt: g.CreatedAt}
}

// ---------------------------------------------------------------- validation

// checkBirthdayFields validates a name, date and year (§10) and returns the trimmed name.
func (s *Server) checkBirthdayFields(name string, month, day int, year *int) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return "", problem.Validation("display_name", "minLength", "a birthday needs a name")
	}
	if month < 1 || month > 12 || day < 1 || day > store.DaysIn(month) {
		return "", problem.Validation("day", "date", "not a real date")
	}
	if year != nil {
		if *year < 1900 || *year > s.now().Year() {
			return "", problem.Validation("birth_year", "range", "the year must be between 1900 and this year")
		}
		if month == 2 && day == 29 && !store.IsLeap(*year) {
			return "", problem.Validation("day", "leap", "29 February needs a leap year")
		}
	}
	return name, nil
}

// checkLink: the linked person is a member of the feed without another live entry.
func (s *Server) checkLink(ctx context.Context, q db.DBTX, feedID, userID uuid.UUID, exclude *uuid.UUID) error {
	if _, err := s.st.GetFeedMember(ctx, q, feedID, userID); err == store.ErrNotFound {
		return problem.Validation("user_id", "not_member", "the linked person must be a member of this feed")
	} else if err != nil {
		return err
	}
	taken, err := s.st.MemberHasBirthday(ctx, q, feedID, userID, exclude)
	if err != nil {
		return err
	}
	if taken {
		return problem.Validation("user_id", "taken", "this member already has a birthday in this feed")
	}
	return nil
}

// checkDuplicate answers 409 duplicate_birthday with the existing entry's id.
func (s *Server) checkDuplicate(ctx context.Context, q db.DBTX, b *store.Birthday, statuses []string) error {
	dup, err := s.st.BirthdayDuplicate(ctx, q, b.FeedID, store.NameKey(b.DisplayName), b.Month, b.Day, statuses, &b.ID)
	if err != nil {
		return err
	}
	if dup != nil {
		return problem.New(problem.DuplicateBirthday, "%s", dup.String())
	}
	return nil
}

// uniqueProblem maps the uniqueness indexes to their answers (concurrent writers).
func (s *Server) uniqueProblem(ctx context.Context, err error, b *store.Birthday) error {
	switch {
	case db.IsUniqueViolation(err, "feed_birthdays_unique"):
		if perr := s.checkDuplicate(ctx, s.st.Q(), b, []string{"pending", "approved"}); perr != nil {
			return perr
		}
		return problem.New(problem.DuplicateBirthday, "")
	case db.IsUniqueViolation(err, "feed_birthdays_one_per_user"):
		return problem.Validation("user_id", "taken", "this member already has a birthday in this feed")
	}
	return err
}

var liveStatuses = []string{"pending", "approved"}

// ---------------------------------------------------------------- list and read

func (s *Server) ListFeedBirthdays(ctx context.Context, req gen.ListFeedBirthdaysRequestObject) (gen.ListFeedBirthdaysResponseObject, error) {
	fv, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	v := s.bdViewer(ctx, fv)
	all, err := s.st.ListBirthdays(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	var bs []store.Birthday
	for i := range all {
		if v.canSee(&all[i]) && (req.Params.Status == nil || string(*req.Params.Status) == all[i].Status) {
			bs = append(bs, all[i])
		}
	}
	out, err := s.renderBirthdays(ctx, v, bs)
	if err != nil {
		return nil, err
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].NextBirthday != out[j].NextBirthday {
			return out[i].NextBirthday < out[j].NextBirthday
		}
		return store.NameKey(out[i].DisplayName) < store.NameKey(out[j].DisplayName)
	})
	return ok(out), nil
}

func (s *Server) GetFeedBirthday(ctx context.Context, req gen.GetFeedBirthdayRequestObject) (gen.GetFeedBirthdayResponseObject, error) {
	_, v, b, err := s.visibleBirthday(ctx, req.FeedId, req.BirthdayId)
	if err != nil {
		return nil, err
	}
	out, err := s.renderBirthday(ctx, v, b)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

// rerender returns the entry as the caller sees it now.
func (s *Server) rerender(ctx context.Context, v bdViewer, feedID, id uuid.UUID) (birthdayJSON, error) {
	b, err := s.st.GetBirthday(ctx, s.st.Q(), feedID, id)
	if err != nil {
		return birthdayJSON{}, notFound(err, "birthday")
	}
	return s.renderBirthday(ctx, v, b)
}

// ---------------------------------------------------------------- create

type birthdayRef struct {
	FeedID     uuid.UUID `json:"feed_id"`
	BirthdayID uuid.UUID `json:"birthday_id"`
}

func (s *Server) CreateFeedBirthday(ctx context.Context, req gen.CreateFeedBirthdayRequestObject) (gen.CreateFeedBirthdayResponseObject, error) {
	u := me(ctx)
	fv, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	v := s.bdViewer(ctx, fv)
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev birthdayRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "feed_birthday", key, &prev); err != nil {
			return nil, err
		} else if found && prev.FeedID == req.FeedId {
			out, err := s.rerender(ctx, v, prev.FeedID, prev.BirthdayID)
			if err != nil {
				return nil, err
			}
			return created(out), nil
		}
	}
	if allowed, retry := s.birthdayUser.Allow(u.ID.String()); !allowed {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	body := req.Body
	name, err := s.checkBirthdayFields(body.DisplayName, body.Month, body.Day, body.BirthYear)
	if err != nil {
		return nil, err
	}
	b := &store.Birthday{ID: store.NewID(), FeedID: req.FeedId, Status: "approved", DisplayName: name, Month: body.Month, Day: body.Day,
		BirthYear: body.BirthYear, UserID: body.UserId, CreatedBy: &u.ID}
	if body.ShowAge != nil {
		b.ShowAge = *body.ShowAge
	}
	if !v.admin() && !v.pcab {
		b.Status, b.Suggested = "pending", true
	}
	limit := s.Limits(ctx).MaxBirthdaysPerFeed
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFeed(ctx, req.FeedId); err != nil {
			return notFound(err, "feed")
		}
		n, err := s.st.CountLiveBirthdays(ctx, tx, req.FeedId)
		if err != nil {
			return err
		}
		if n >= limit {
			return problem.New(problem.LimitReached, "a feed can list at most %d birthdays", limit)
		}
		if err := s.checkDuplicate(ctx, tx, b, liveStatuses); err != nil {
			return err
		}
		if b.UserID != nil {
			if err := s.checkLink(ctx, tx, req.FeedId, *b.UserID, nil); err != nil {
				return err
			}
		}
		if err := tx.InsertBirthday(ctx, b); err != nil {
			return err
		}
		if b.Status == "approved" {
			if err := tx.SyncBirthdayFeeds(ctx, []uuid.UUID{req.FeedId}); err != nil {
				return err
			}
		}
		if key != "" {
			return tx.SaveIdempotent(ctx, u.ID, "feed_birthday", key, birthdayRef{req.FeedId, b.ID})
		}
		return nil
	})
	if db.IsUniqueViolation(err, "idempotency_keys_pkey") {
		var prev birthdayRef
		if found, _ := store.IdempotentResult(ctx, s.st.Q(), u.ID, "feed_birthday", key, &prev); found {
			out, err := s.rerender(ctx, v, prev.FeedID, prev.BirthdayID)
			if err != nil {
				return nil, err
			}
			return created(out), nil
		}
	}
	if err != nil {
		return nil, s.uniqueProblem(ctx, err, b)
	}
	skip := map[uuid.UUID]bool{}
	if b.Status == "pending" {
		skip = s.announceSuggestion(ctx, fv, b.ID)
	}
	s.publishBirthday(ctx, req.FeedId, b.ID, skip)
	out, err := s.rerender(ctx, v, req.FeedId, b.ID)
	if err != nil {
		return nil, err
	}
	return created(out), nil
}

// ---------------------------------------------------------------- update

type birthdayPatch struct {
	name, month, day, year, showAge, link bool
	b                                     store.Birthday // the new values of the set fields
}

func (p *birthdayPatch) apply(cur *store.Birthday) store.Birthday {
	n := *cur
	if p.name {
		n.DisplayName = p.b.DisplayName
	}
	if p.month {
		n.Month = p.b.Month
	}
	if p.day {
		n.Day = p.b.Day
	}
	if p.year {
		n.BirthYear = p.b.BirthYear
	}
	if p.showAge {
		n.ShowAge = p.b.ShowAge
	}
	if p.link {
		n.UserID = p.b.UserID
	}
	return n
}

func eqUUIDPtr(a, b *uuid.UUID) bool {
	if a == nil || b == nil {
		return a == b
	}
	return *a == *b
}

// birthdayPatchOf collects the fields of the body that differ from cur
// (fields sent unchanged are not edits, so clients may send whole objects).
func birthdayPatchOf(ctx context.Context, body *gen.BirthdayPersonUpdate, cur *store.Birthday) birthdayPatch {
	var p birthdayPatch
	if body.DisplayName != nil && strings.TrimSpace(*body.DisplayName) != cur.DisplayName {
		p.name, p.b.DisplayName = true, strings.TrimSpace(*body.DisplayName)
	}
	if body.Month != nil && *body.Month != cur.Month {
		p.month, p.b.Month = true, *body.Month
	}
	if body.Day != nil && *body.Day != cur.Day {
		p.day, p.b.Day = true, *body.Day
	}
	if _, null := rawField(ctx, "birth_year"); null {
		p.year = cur.BirthYear != nil
	} else if body.BirthYear != nil && !eqIntPtr(body.BirthYear, cur.BirthYear) {
		p.year, p.b.BirthYear = true, body.BirthYear
	}
	if body.ShowAge != nil && *body.ShowAge != cur.ShowAge {
		p.showAge, p.b.ShowAge = true, *body.ShowAge
	}
	if _, null := rawField(ctx, "user_id"); null {
		p.link = cur.UserID != nil
	} else if body.UserId != nil && !eqUUIDPtr(body.UserId, cur.UserID) {
		p.link, p.b.UserID = true, body.UserId
	}
	return p
}

func (s *Server) UpdateFeedBirthday(ctx context.Context, req gen.UpdateFeedBirthdayRequestObject) (gen.UpdateFeedBirthdayResponseObject, error) {
	_, v, cur, err := s.visibleBirthday(ctx, req.FeedId, req.BirthdayId)
	if err != nil {
		return nil, err
	}
	if !v.canEdit(cur) {
		return nil, problem.New(problem.Forbidden, "you can't change this birthday")
	}
	p := birthdayPatchOf(ctx, req.Body, cur)
	if !v.fullEdit(cur) && (p.name || p.month || p.day || (p.link && p.b.UserID != nil)) {
		return nil, problem.New(problem.Forbidden, "you can only change the year, the age display, the photo and the link of your own entry")
	}
	if !(p.name || p.month || p.day || p.year || p.showAge || p.link) {
		out, err := s.renderBirthday(ctx, v, cur)
		if err != nil {
			return nil, err
		}
		return ok(out), nil
	}
	var next store.Birthday
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFeed(ctx, req.FeedId); err != nil {
			return notFound(err, "feed")
		}
		locked, err := tx.LockBirthday(ctx, req.FeedId, req.BirthdayId)
		if err != nil {
			return notFound(err, "birthday")
		}
		next = p.apply(locked)
		name, err := s.checkBirthdayFields(next.DisplayName, next.Month, next.Day, next.BirthYear)
		if err != nil {
			return err
		}
		next.DisplayName = name
		if p.link && next.UserID != nil {
			if err := s.checkLink(ctx, tx, req.FeedId, *next.UserID, &next.ID); err != nil {
				return err
			}
		}
		if next.Live() && (p.name || p.month || p.day) {
			if err := s.checkDuplicate(ctx, tx, &next, liveStatuses); err != nil {
				return err
			}
		}
		if err := tx.SaveBirthday(ctx, &next); err != nil {
			return err
		}
		if locked.Status == "approved" {
			return tx.SyncBirthdayFeeds(ctx, []uuid.UUID{req.FeedId})
		}
		return nil
	})
	if err != nil {
		return nil, s.uniqueProblem(ctx, err, &next)
	}
	s.publishBirthday(ctx, req.FeedId, req.BirthdayId, nil)
	out, err := s.rerender(ctx, v, req.FeedId, req.BirthdayId)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

// ---------------------------------------------------------------- remove

func (s *Server) RemoveFeedBirthday(ctx context.Context, req gen.RemoveFeedBirthdayRequestObject) (gen.RemoveFeedBirthdayResponseObject, error) {
	_, v, cur, err := s.visibleBirthday(ctx, req.FeedId, req.BirthdayId)
	if err != nil {
		return nil, err
	}
	if !v.canDelete(cur) {
		return nil, problem.New(problem.Forbidden, "you can't change this birthday")
	}
	audience := s.birthdayAudience(ctx, req.FeedId, cur)
	var photo *string
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		locked, err := tx.LockBirthday(ctx, req.FeedId, req.BirthdayId)
		if err == store.ErrNotFound {
			return nil // removed meanwhile
		}
		if err != nil {
			return err
		}
		if photo, err = tx.DeleteBirthday(ctx, locked.ID); err != nil {
			return err
		}
		if locked.Status == "approved" {
			return tx.SyncBirthdayFeeds(ctx, []uuid.UUID{req.FeedId})
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	if photo != nil {
		s.deleteBlob(ctx, *photo)
	}
	s.notify.Users(audience, notify.Event{"type": "birthday.removed", "feed_id": req.FeedId, "birthday_id": req.BirthdayId}, nil)
	return noContent(), nil
}

// ---------------------------------------------------------------- approve and reject

func (s *Server) decideBirthday(ctx context.Context, feedID, id uuid.UUID, approve bool) (Response, error) {
	fv, v, cur, err := s.visibleBirthday(ctx, feedID, id)
	if err != nil {
		return Response{}, err
	}
	if !v.admin() {
		return Response{}, problem.New(problem.Forbidden, "owner or admin only")
	}
	target := "rejected"
	if approve {
		target = "approved"
	}
	changed := false
	var locked *store.Birthday
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		changed = false
		if _, err := tx.LockFeed(ctx, feedID); err != nil {
			return notFound(err, "feed")
		}
		if locked, err = tx.LockBirthday(ctx, feedID, id); err != nil {
			return notFound(err, "birthday")
		}
		switch locked.Status {
		case target:
			return nil
		case "pending":
		default:
			return problem.New(problem.AlreadyDecided, "")
		}
		if approve {
			if err := s.checkDuplicate(ctx, tx, locked, []string{"approved"}); err != nil {
				return err
			}
		}
		if err := tx.DecideBirthday(ctx, id, target, me(ctx).ID); err != nil {
			return err
		}
		changed = true
		if approve {
			return tx.SyncBirthdayFeeds(ctx, []uuid.UUID{feedID})
		}
		return nil
	})
	if err != nil {
		return Response{}, err
	}
	if changed {
		skip := map[uuid.UUID]bool{}
		if cur.Suggested && cur.CreatedBy != nil {
			skip[*cur.CreatedBy] = true
			s.announceDecision(ctx, fv, id, *cur.CreatedBy, approve)
		}
		s.publishBirthday(ctx, feedID, id, skip)
	}
	out, err := s.rerender(ctx, v, feedID, id)
	if err != nil {
		return Response{}, err
	}
	return ok(out), nil
}

func (s *Server) ApproveFeedBirthday(ctx context.Context, req gen.ApproveFeedBirthdayRequestObject) (gen.ApproveFeedBirthdayResponseObject, error) {
	return s.decideBirthday(ctx, req.FeedId, req.BirthdayId, true)
}

func (s *Server) RejectFeedBirthday(ctx context.Context, req gen.RejectFeedBirthdayRequestObject) (gen.RejectFeedBirthdayResponseObject, error) {
	return s.decideBirthday(ctx, req.FeedId, req.BirthdayId, false)
}

// ---------------------------------------------------------------- photo

// setBirthdayPhoto stores (key) or clears (nil) the photo of an entry the caller may edit.
func (s *Server) setBirthdayPhoto(ctx context.Context, v bdViewer, feedID, id uuid.UUID, key *string) (birthdayJSON, error) {
	var old *string
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		locked, err := tx.LockBirthday(ctx, feedID, id)
		if err != nil {
			return notFound(err, "birthday")
		}
		if !v.canEdit(locked) {
			return problem.New(problem.Forbidden, "you can't change this birthday")
		}
		if old, err = tx.SetBirthdayPhoto(ctx, id, key); err != nil {
			return err
		}
		if locked.Status == "approved" {
			return tx.SyncBirthdayFeeds(ctx, []uuid.UUID{feedID})
		}
		return nil
	})
	if err != nil {
		if key != nil {
			s.deleteBlob(ctx, *key)
		}
		return birthdayJSON{}, err
	}
	if old != nil && (key == nil || *old != *key) {
		s.deleteBlob(ctx, *old)
	}
	s.publishBirthday(ctx, feedID, id, nil)
	return s.rerender(ctx, v, feedID, id)
}

// putFeedBirthdayPhoto stores a 256×256 WebP cropped from the centre, like putAvatar.
func (s *Server) putFeedBirthdayPhoto(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	feedID, err1 := uuid.Parse(r.PathValue("feedId"))
	id, err2 := uuid.Parse(r.PathValue("birthdayId"))
	if err1 != nil || err2 != nil {
		problem.Write(w, r, problem.New(problem.NotFound, "birthday not found"))
		return
	}
	_, v, cur, err := s.visibleBirthday(ctx, feedID, id)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	if !v.canEdit(cur) {
		s.writeError(w, r, problem.New(problem.Forbidden, "you can't change this birthday"))
		return
	}
	ct := strings.ToLower(strings.TrimSpace(strings.Split(r.Header.Get("Content-Type"), ";")[0]))
	if ct != "image/jpeg" && ct != "image/png" && ct != "image/webp" {
		problem.Write(w, r, problem.New(problem.UnsupportedMedia, "photos must be JPEG, PNG or WebP").WithStatus(http.StatusUnsupportedMediaType))
		return
	}
	tmp, err := s.tempDir()
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	defer os.RemoveAll(tmp)
	src := filepath.Join(tmp, "in")
	f, err := os.Create(src)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	n, err := io.Copy(f, io.LimitReader(r.Body, 20<<20+1))
	f.Close()
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	if n > 20<<20 {
		problem.Write(w, r, problem.New(problem.UploadTooLarge, "photos are limited to 20 MB"))
		return
	}
	if kind, _, err := media.Sniff(src); err != nil || kind != media.Photo {
		problem.Write(w, r, problem.New(problem.UnsupportedMedia, ""))
		return
	}
	norm, out := filepath.Join(tmp, "norm.webp"), filepath.Join(tmp, "photo.webp")
	size, _, err := s.tools.PhotoMaster(ctx, src, norm)
	if err != nil {
		problem.Write(w, r, problem.New(problem.CorruptMedia, ""))
		return
	}
	if err := s.tools.Thumb(ctx, norm, size, 256, 0.5, 0.5, out); err != nil {
		problem.Write(w, r, problem.New(problem.CorruptMedia, ""))
		return
	}
	// Public at an unguessable URL, served by /avatars/{dir}/{file} like member avatars.
	key := "avatars/b/" + auth.RandomToken(16) + ".webp"
	if err := s.blob.PutFile(ctx, key, out, blob.PutOptions{ContentType: "image/webp"}); err != nil {
		s.writeError(w, r, err)
		return
	}
	res, err := s.setBirthdayPhoto(ctx, v, feedID, id, &key)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(res)
}

func (s *Server) DeleteFeedBirthdayPhoto(ctx context.Context, req gen.DeleteFeedBirthdayPhotoRequestObject) (gen.DeleteFeedBirthdayPhotoResponseObject, error) {
	_, v, cur, err := s.visibleBirthday(ctx, req.FeedId, req.BirthdayId)
	if err != nil {
		return nil, err
	}
	if !v.canEdit(cur) {
		return nil, problem.New(problem.Forbidden, "you can't change this birthday")
	}
	if cur.PhotoKey == nil {
		out, err := s.renderBirthday(ctx, v, cur)
		if err != nil {
			return nil, err
		}
		return ok(out), nil
	}
	out, err := s.setBirthdayPhoto(ctx, v, req.FeedId, req.BirthdayId, nil)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

// ---------------------------------------------------------------- greetings list and preferences

func (s *Server) ListFeedBirthdayGreetings(ctx context.Context, req gen.ListFeedBirthdayGreetingsRequestObject) (gen.ListFeedBirthdayGreetingsResponseObject, error) {
	if _, err := s.feedView(ctx, s.st.Q(), req.FeedId); err != nil {
		return nil, err
	}
	limit := pageLimit(req.Params.Limit)
	var before *uuid.UUID
	if req.Params.Cursor != nil && *req.Params.Cursor != "" {
		id, err := uuid.Parse(*req.Params.Cursor)
		if err != nil {
			return nil, problem.Validation("cursor", "format", "invalid cursor").WithStatus(http.StatusBadRequest)
		}
		before = &id
	}
	var date *time.Time
	if req.Params.Date != nil {
		d := req.Params.Date.Time
		date = &d
	}
	gs, err := s.st.ListGreetings(ctx, req.FeedId, req.Params.BirthdayId, date, before, limit+1)
	if err != nil {
		return nil, err
	}
	var next *string
	if len(gs) > limit {
		gs = gs[:limit]
		next = ptr(gs[limit-1].ID.String())
	}
	out := make([]greetingJSON, len(gs))
	for i := range gs {
		out[i] = greetingOut(&gs[i])
	}
	return ok(map[string]any{"greetings": out, "next_cursor": next}), nil
}

func (s *Server) UpdateFeedPreferences(ctx context.Context, req gen.UpdateFeedPreferencesRequestObject) (gen.UpdateFeedPreferencesResponseObject, error) {
	if _, err := s.feedView(ctx, s.st.Q(), req.FeedId); err != nil {
		return nil, err
	}
	if on := req.Body.BirthdayNotifications; on != nil {
		if err := s.st.InTx(ctx, func(tx *store.Tx) error { return tx.SetBirthdayNotifications(ctx, req.FeedId, me(ctx).ID, *on) }); err != nil {
			return nil, err
		}
	}
	if err := s.setFeedMute(ctx, req.FeedId, req.Body.Mute); err != nil {
		return nil, err
	}
	fv, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	return ok(s.feedOut(fv)), nil
}

// ---------------------------------------------------------------- frames

// frameBirthdays renders the frame's `birthdays` change (nil: none).
func (s *Server) frameBirthdays(ctx context.Context, f *store.Frame) (map[string]any, error) {
	groups, err := s.st.FrameBirthdays(ctx, s.st.Q(), f.ID)
	if err != nil || len(groups) == 0 {
		return nil, err
	}
	at, err := s.st.FrameBirthdaysEmittedAt(ctx, s.st.Q(), f.ID)
	if err != nil {
		return nil, err
	}
	people := make([]map[string]any, len(groups))
	for i, g := range groups {
		p := map[string]any{"id": g.ID, "name": g.Name, "day": g.Day, "month": g.Month, "feed_ids": g.FeedIDs, "birthday_ids": g.BirthdayIDs}
		if g.BirthYear != nil {
			p["birth_year"] = *g.BirthYear
		}
		if u := s.avatarURL(g.PhotoKey); u != nil {
			p["photo_url"] = *u
		}
		people[i] = p
	}
	return map[string]any{"generated_at": at, "people": people}, nil
}

// syncAvatarBirthdays re-emits the frames showing a member's avatar as a birthday photo.
func (s *Server) syncAvatarBirthdays(ctx context.Context, userID uuid.UUID) error {
	feeds, err := s.st.FeedsUsingAvatar(ctx, s.st.Q(), userID)
	if err != nil || len(feeds) == 0 {
		return err
	}
	return s.st.InTx(ctx, func(tx *store.Tx) error { return tx.SyncBirthdayFeeds(ctx, feeds) })
}

func (s *Server) DeviceGreetBirthday(ctx context.Context, req gen.DeviceGreetBirthdayRequestObject) (gen.DeviceGreetBirthdayResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	groups, err := s.st.FrameBirthdays(ctx, s.st.Q(), f.ID)
	if err != nil {
		return nil, err
	}
	var g *store.FrameBirthdayGroup
	for i := range groups {
		for _, id := range groups[i].BirthdayIDs {
			if id == req.BirthdayId {
				g = &groups[i]
			}
		}
	}
	if g == nil {
		return nil, problem.New(problem.NotFound, "birthday not found")
	}
	date := dateOf(req.Date.Time)
	if !store.OccursOn(g.Month, g.Day, date) {
		return nil, problem.Validation("date", "birthday", "not this person's birthday")
	}
	for _, e := range g.Entries {
		if e.BirthYear != nil && *e.BirthYear >= date.Year() {
			// The date of birth itself (or earlier) is not a birthday (§2).
			return nil, problem.Validation("date", "birthday", "not this person's birthday")
		}
	}
	now := s.now().UTC()
	today := dateOf(now)
	if date.Before(today.AddDate(0, 0, -2)) || date.After(today.AddDate(0, 0, 1)) {
		return nil, problem.Validation("date", "window", "this date is too far from today")
	}
	if done, err := s.st.GreetingsExist(ctx, s.st.Q(), g.BirthdayIDs, f.ID, date); err != nil {
		return nil, err
	} else if done {
		return noContent(), nil
	}
	n, err := s.st.CountFrameGreetingsSince(ctx, s.st.Q(), f.ID, today)
	if err != nil {
		return nil, err
	}
	if n >= maxGreetingsPerDay {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = today.AddDate(0, 0, 1).Sub(now)
		return nil, p
	}
	var rows []store.BirthdayGreeting
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		var err error
		rows, err = tx.InsertGreetings(ctx, g.BirthdayIDs, f.ID, date, f.Name, f.Location, now)
		return err
	})
	if err != nil {
		return nil, err
	}
	s.announceGreetings(ctx, f, g, rows, date)
	return noContent(), nil
}
