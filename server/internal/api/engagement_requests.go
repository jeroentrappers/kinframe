package api

// Frame freshness and "Ask for new photos" (README §5.20).

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

const (
	requestEvery      = 24 * time.Hour          // one request per frame per day
	requestSenders    = 6 * 30 * 24 * time.Hour // who sent to the frame recently
	requestShownAfter = 24 * time.Hour          // FrameSummary.photo_request keeps an answered one this long
	requestList       = 30 * 24 * time.Hour     // listFramePhotoRequests
	requestOnFrame    = 7 * 24 * time.Hour      // the `photo_request` change
)

// ---------------------------------------------------------------- freshness on frames and families

// fillFrameFreshness fills FrameSummary.freshness and .photo_request.
func (s *Server) fillFrameFreshness(ctx context.Context, frames []gen.FrameSummary) {
	if len(frames) == 0 {
		return
	}
	ids := make([]uuid.UUID, len(frames))
	for i, f := range frames {
		ids[i] = f.Id
	}
	now := s.now()
	rows, err := s.st.FrameFreshness(ctx, s.st.Q(), ids)
	if err != nil {
		s.log.Warn("frame freshness", "err", err)
		return
	}
	reqs, err := s.st.CurrentPhotoRequests(ctx, s.st.Q(), ids, now.Add(-requestShownAfter))
	if err != nil {
		s.log.Warn("frame photo requests", "err", err)
		return
	}
	people := s.freshnessPeople(ctx, rows, reqs)
	for i := range frames {
		r := rows[frames[i].Id]
		fr := s.freshnessJSON(now, r, people)
		frames[i].Freshness = &fr
		if pr, ok := reqs[frames[i].Id]; ok {
			out := s.photoRequestJSON(&pr, people, &fr)
			frames[i].PhotoRequest = &out
		}
	}
}

func (s *Server) freshnessPeople(ctx context.Context, rows map[uuid.UUID]store.FreshnessRow, reqs map[uuid.UUID]store.PhotoRequest) map[uuid.UUID]store.Person {
	var ids []uuid.UUID
	for _, r := range rows {
		if r.UserID != nil {
			ids = append(ids, *r.UserID)
		}
	}
	for _, r := range reqs {
		if r.AnsweredBy != nil {
			ids = append(ids, *r.AnsweredBy)
		}
	}
	ps, _ := s.st.People(ctx, s.st.Q(), engUnique(ids))
	return ps
}

func (s *Server) freshnessJSON(now time.Time, r store.FreshnessRow, people map[uuid.UUID]store.Person) gen.Freshness {
	quiet := defaultQuietAfter
	if r.QuietAfter != nil {
		quiet = *r.QuietAfter
	}
	fr := freshnessOf(now, r.LastPersonalAt, r.LastSourceAt, quiet)
	if r.UserID != nil && r.LastPersonalAt != nil {
		if p, ok := people[*r.UserID]; ok {
			ref := s.engPersonRef(p)
			fr.LastSharedBy = &ref
		}
	}
	return fr
}

// feedEngagementJSON is what server-engagement adds to FeedSummary (README §5.20).
type feedEngagementJSON struct {
	Freshness *gen.Freshness  `json:"freshness,omitempty"`
	Streak    json.RawMessage `json:"streak,omitempty"` // FeedStreakSummary, or null when the family turned streaks off
}

// fillFeedFreshness fills FeedSummary.freshness and .streak.
func (s *Server) fillFeedFreshness(ctx context.Context, feeds []gen.FeedSummary) {
	if len(feeds) == 0 {
		return
	}
	ids := make([]uuid.UUID, len(feeds))
	for i, f := range feeds {
		ids[i] = f.Id
	}
	now := s.now()
	rows, err := s.st.FeedFreshness(ctx, s.st.Q(), ids)
	if err != nil {
		s.log.Warn("feed freshness", "err", err)
		return
	}
	people := s.freshnessPeople(ctx, rows, nil)
	ens, err := s.st.FeedEngagements(ctx, s.st.Q(), ids)
	if err != nil {
		s.log.Warn("feed engagement", "err", err)
		return
	}
	for i := range feeds {
		fr := s.freshnessJSON(now, rows[feeds[i].Id], people)
		feeds[i].Freshness = &fr
		if fe, ok := ens[feeds[i].Id]; ok && fe.StreakEnabled {
			if sum, err := s.streakSummary(ctx, &fe, now); err == nil {
				feeds[i].Streak = sum
			}
		}
	}
}

// feedEngagementFields renders the engagement fields of one feed for feedSummaryJSON.
func (s *Server) feedEngagementFields(ctx context.Context, feedID uuid.UUID) feedEngagementJSON {
	out := []feedSummaryJSON{{ID: feedID}}
	s.fillFeedEngagement(ctx, out)
	return out[0].feedEngagementJSON
}

// fillFeedEngagement fills the engagement fields of listMyFeeds' summaries (through feedFreshness).
func (s *Server) fillFeedEngagement(ctx context.Context, feeds []feedSummaryJSON) {
	fs := make([]gen.FeedSummary, len(feeds))
	for i := range feeds {
		fs[i].Id = feeds[i].ID
	}
	s.feedFreshness(ctx, fs)
	for i := range feeds {
		e := feedEngagementJSON{Freshness: fs[i].Freshness}
		if fs[i].Streak != nil {
			e.Streak, _ = json.Marshal(fs[i].Streak)
		} else if fs[i].Freshness != nil {
			e.Streak = json.RawMessage("null")
		}
		feeds[i].feedEngagementJSON = e
	}
}

// ---------------------------------------------------------------- rendering requests

func (s *Server) photoRequestJSON(r *store.PhotoRequest, people map[uuid.UUID]store.Person, fr *gen.Freshness) gen.PhotoRequest {
	out := gen.PhotoRequest{Id: r.ID, FrameId: r.FrameID, FrameName: r.FrameName, State: gen.PhotoRequestState(r.State),
		CreatedAt: r.CreatedAt, ExpiresAt: r.ExpiresAt, AnsweredAt: r.AnsweredAt, Freshness: fr, FeedIds: ptr(r.FeedIDs)}
	if out.FeedIds != nil && *out.FeedIds == nil {
		out.FeedIds = &[]uuid.UUID{}
	}
	if r.AnsweredBy != nil {
		if p, ok := people[*r.AnsweredBy]; ok {
			ref := s.engPersonRef(p)
			out.AnsweredBy = &ref
		}
	}
	return out
}

// renderPhotoRequest renders one request with the frame's freshness.
func (s *Server) renderPhotoRequest(ctx context.Context, r *store.PhotoRequest) gen.PhotoRequest {
	rows, _ := s.st.FrameFreshness(ctx, s.st.Q(), []uuid.UUID{r.FrameID})
	people := s.freshnessPeople(ctx, rows, map[uuid.UUID]store.PhotoRequest{r.FrameID: *r})
	fr := s.freshnessJSON(s.now(), rows[r.FrameID], people)
	return s.photoRequestJSON(r, people, &fr)
}

// framePhotoRequestJSON is the frame's view of a request.
func (s *Server) framePhotoRequestJSON(ctx context.Context, r *store.PhotoRequest) gen.FramePhotoRequest {
	out := gen.FramePhotoRequest{Id: r.ID, State: gen.PhotoRequestState(r.State), CreatedAt: r.CreatedAt,
		NextAllowedAt: r.CreatedAt.Add(requestEvery), AnsweredAt: r.AnsweredAt}
	switch {
	case r.AnsweredBy != nil:
		if ps, err := s.st.People(ctx, s.st.Q(), []uuid.UUID{*r.AnsweredBy}); err == nil {
			if p, ok := ps[*r.AnsweredBy]; ok && p.DeletedAt == nil {
				out.AnsweredByName = ptr(engFirstName(p.DisplayName))
			}
		}
	case r.State == "answered" && r.AnsweredLabel != nil && *r.AnsweredLabel != "":
		out.AnsweredByName = ptr(engFirstName(*r.AnsweredLabel))
	}
	return out
}

// ---------------------------------------------------------------- operations

// frameSocialSetting reads settings.social.<key> of a frame (default when unset).
func frameSocialSetting(f *store.Frame, key string, def bool) bool {
	var doc struct {
		Social map[string]any `json:"social"`
	}
	if json.Unmarshal(f.Settings, &doc) != nil {
		return def
	}
	if v, ok := doc.Social[key].(bool); ok {
		return v
	}
	return def
}

func (s *Server) DeviceRequestPhotos(ctx context.Context, _ gen.DeviceRequestPhotosRequestObject) (gen.DeviceRequestPhotosResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	if !frameSocialSetting(f, "ask_for_photos", true) {
		return nil, problem.New(problem.Forbidden, "Ask for new photos is turned off on this frame")
	}
	now := s.now()
	var created bool
	var req *store.PhotoRequest
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFrame(ctx, f.ID); err != nil {
			return err
		}
		prev, err := s.st.LatestPhotoRequest(ctx, tx, f.ID, now.Add(-requestEvery))
		if err == nil {
			req = prev
			return nil
		} else if err != store.ErrNotFound {
			return err
		}
		id := store.NewID()
		if err := tx.CreatePhotoRequest(ctx, id, f.ID, now); err != nil {
			return err
		}
		if err := tx.FeedPut(ctx, f.ID, store.FeedPhotoRequest, f.ID, false); err != nil {
			return err
		}
		created = true
		req, err = s.st.GetPhotoRequest(ctx, tx, id)
		return err
	})
	if err != nil {
		return nil, err
	}
	out := s.framePhotoRequestJSON(ctx, req)
	if !created {
		return ok(out), nil
	}
	s.announcePhotoRequest(ctx, req, now)
	return Response{Status: 201, Body: out}, nil
}

// photoRequestRecipients are who gets the frame_request push (README §5.20): the frame's members with `send`
// and the poster-or-above members of its families who sent to it in the last 6 months, else all of them;
// not people who turned frame requests off.
func (s *Server) photoRequestRecipients(ctx context.Context, frameID uuid.UUID, now time.Time) ([]uuid.UUID, map[uuid.UUID]store.ReminderPrefs, error) {
	cands, err := s.st.PhotoRequestCandidates(ctx, s.st.Q(), frameID, now.Add(-requestSenders))
	if err != nil {
		return nil, nil, err
	}
	var senders, all []uuid.UUID
	for _, c := range cands {
		all = append(all, c.UserID)
		if c.Sent {
			senders = append(senders, c.UserID)
		}
	}
	to := all
	if len(senders) > 0 {
		to = senders
	}
	prefs, err := s.st.ReminderPrefsOf(ctx, s.st.Q(), to)
	if err != nil {
		return nil, nil, err
	}
	var out []uuid.UUID
	for _, u := range to {
		if p, ok := prefs[u]; ok && p.FrameRequests {
			out = append(out, u)
		}
	}
	return out, prefs, nil
}

// announcePhotoRequest sends photo_request.created to the recipients, with the push for those outside their
// quiet hours (the others get it when their quiet hours end, notifyHeldRequests).
func (s *Server) announcePhotoRequest(ctx context.Context, r *store.PhotoRequest, now time.Time) {
	to, prefs, err := s.photoRequestRecipients(ctx, r.FrameID, now)
	if err != nil {
		s.log.Warn("photo request recipients", "frame", r.FrameID, "err", err)
		return
	}
	ev := notify.Event{"type": "photo_request.created", "request": s.renderPhotoRequest(ctx, r)}
	var pushed []uuid.UUID
	for _, u := range to {
		p := prefs[u]
		if quietNow(&p, now) {
			s.notify.User(u, ev, nil)
			continue
		}
		if s.recordRequestNudge(ctx, u, r, now) {
			pushed = append(pushed, u)
			s.notify.User(u, ev, requestPush(r))
		}
	}
	if len(pushed) > 0 {
		if err := s.st.AddPhotoRequestNotified(ctx, r.ID, pushed); err != nil {
			s.log.Warn("photo request notified", "err", err)
		}
	}
	// Everyone else who sees the request in the apps (the frame's other members, the families' readers) gets the
	// event without a push, so their frame strip and frame page show it live.
	s.notify.Users(s.photoRequestOthers(ctx, r.FrameID, to), ev, nil)
}

// photoRequestOthers are the request's viewers who are not in `except`.
func (s *Server) photoRequestOthers(ctx context.Context, frameID uuid.UUID, except []uuid.UUID) []uuid.UUID {
	viewers, err := s.st.PhotoRequestViewers(ctx, s.st.Q(), frameID)
	if err != nil {
		s.log.Warn("photo request viewers", "frame", frameID, "err", err)
		return nil
	}
	skip := make(map[uuid.UUID]bool, len(except))
	for _, u := range except {
		skip[u] = true
	}
	var out []uuid.UUID
	for _, u := range viewers {
		if !skip[u] {
			out = append(out, u)
		}
	}
	return out
}

func requestPush(r *store.PhotoRequest) *notify.Push {
	return &notify.Push{Key: "frame_request", Args: map[string]string{"frame": r.FrameName}, Tag: "frame_request:" + r.FrameID.String(),
		IDs: map[string]string{"frame_id": r.FrameID.String(), "request_id": r.ID.String()}}
}

func (s *Server) recordRequestNudge(ctx context.Context, u uuid.UUID, r *store.PhotoRequest, now time.Time) bool {
	ok, err := s.st.InsertNudge(ctx, store.Nudge{ID: store.NewID(), UserID: u, Kind: "frame_request", FrameID: &r.FrameID,
		SubjectID: &r.ID, DedupeKey: "frame_request:" + r.ID.String(), CountsInBudget: false, SentAt: now})
	if err != nil {
		s.log.Warn("frame request nudge", "err", err)
	}
	return ok
}

// notifyHeldRequests pushes open requests to recipients whose quiet hours have ended since.
func (s *Server) notifyHeldRequests(ctx context.Context, now time.Time) {
	reqs, err := s.st.OpenPhotoRequests(ctx)
	if err != nil {
		s.log.Warn("open photo requests", "err", err)
		return
	}
	for i := range reqs {
		r := &reqs[i]
		done := map[uuid.UUID]bool{}
		for _, u := range r.NotifiedUserIDs {
			done[u] = true
		}
		to, prefs, err := s.photoRequestRecipients(ctx, r.FrameID, r.CreatedAt)
		if err != nil {
			continue
		}
		var pushed []uuid.UUID
		var ev notify.Event
		for _, u := range to {
			p := prefs[u]
			if done[u] || quietNow(&p, now) {
				continue
			}
			if !s.recordRequestNudge(ctx, u, r, now) {
				continue
			}
			if ev == nil {
				ev = notify.Event{"type": "photo_request.created", "request": s.renderPhotoRequest(ctx, r)}
			}
			pushed = append(pushed, u)
			s.notify.User(u, ev, requestPush(r))
		}
		if len(pushed) > 0 {
			_ = s.st.AddPhotoRequestNotified(ctx, r.ID, pushed)
		}
	}
}

// publishPhotoRequestUpdate tells the request's people it was answered or expired, and the frame.
func (s *Server) publishPhotoRequestUpdate(ctx context.Context, r *store.PhotoRequest, now time.Time) {
	if err := s.st.InTx(ctx, func(tx *store.Tx) error {
		return tx.FeedPut(ctx, r.FrameID, store.FeedPhotoRequest, r.FrameID, false)
	}); err != nil {
		s.log.Warn("photo request change", "err", err)
	}
	to, _, err := s.photoRequestRecipients(ctx, r.FrameID, r.CreatedAt)
	if err != nil {
		return
	}
	to = engUnique(append(to, r.NotifiedUserIDs...))
	to = append(to, s.photoRequestOthers(ctx, r.FrameID, to)...)
	s.notify.Users(to, notify.Event{"type": "photo_request.updated", "request": s.renderPhotoRequest(ctx, r)}, nil)
}

// answerPhotoRequests answers open requests of frames the send reached (README §5.20).
func (s *Server) answerPhotoRequests(ctx context.Context, c *store.SendCredit, now time.Time) {
	var by *uuid.UUID
	if c.Credited {
		by = &c.Sender
	}
	answered, err := s.st.AnswerPhotoRequests(ctx, c.SendID, by, now)
	if err != nil {
		s.log.Warn("answer photo requests", "send", c.SendID, "err", err)
		return
	}
	for i := range answered {
		s.publishPhotoRequestUpdate(ctx, &answered[i], now)
	}
}

// expirePhotoRequests closes requests nobody answered within 7 days.
func (s *Server) expirePhotoRequests(ctx context.Context, now time.Time) {
	expired, err := s.st.ExpirePhotoRequests(ctx, now)
	if err != nil {
		s.log.Warn("expire photo requests", "err", err)
		return
	}
	for i := range expired {
		s.publishPhotoRequestUpdate(ctx, &expired[i], now)
	}
}

// canSeePhotoRequests: frame members and members of families with an active subscription to the frame.
func (s *Server) canSeePhotoRequests(ctx context.Context, frameID uuid.UUID) (bool, error) {
	var ok bool
	err := s.st.Q().QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM memberships WHERE frame_id = $1 AND user_id = $2)
		OR EXISTS (SELECT 1 FROM subscriptions su JOIN feed_members fm ON fm.feed_id = su.feed_id AND fm.user_id = $2
		           JOIN feeds f ON f.id = su.feed_id AND f.deleted_at IS NULL WHERE su.frame_id = $1 AND su.status = 'active')`,
		frameID, me(ctx).ID).Scan(&ok)
	return ok, err
}

func (s *Server) GetPhotoRequest(ctx context.Context, req gen.GetPhotoRequestRequestObject) (gen.GetPhotoRequestResponseObject, error) {
	r, err := s.st.GetPhotoRequest(ctx, s.st.Q(), req.RequestId)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "photo request not found")
	} else if err != nil {
		return nil, err
	}
	if ok, err := s.canSeePhotoRequests(ctx, r.FrameID); err != nil {
		return nil, err
	} else if !ok {
		return nil, problem.New(problem.NotFound, "photo request not found")
	}
	return ok(s.renderPhotoRequest(ctx, r)), nil
}

func (s *Server) ListFramePhotoRequests(ctx context.Context, req gen.ListFramePhotoRequestsRequestObject) (gen.ListFramePhotoRequestsResponseObject, error) {
	if _, err := s.membership(ctx, req.FrameId); err != nil {
		return nil, err
	}
	rows, err := s.st.ListPhotoRequests(ctx, s.st.Q(), req.FrameId, s.now().Add(-requestList))
	if err != nil {
		return nil, err
	}
	out := make([]gen.PhotoRequest, len(rows))
	for i := range rows {
		out[i] = s.renderPhotoRequest(ctx, &rows[i])
	}
	return ok(out), nil
}

// framePhotoRequestChange renders the `photo_request` change: the newest request of the last 7 days, or nil.
func (s *Server) framePhotoRequestChange(ctx context.Context, frameID uuid.UUID) (*gen.FramePhotoRequest, error) {
	r, err := s.st.LatestPhotoRequest(ctx, s.st.Q(), frameID, s.now().Add(-requestOnFrame))
	if err == store.ErrNotFound {
		return nil, nil
	} else if err != nil {
		return nil, err
	}
	out := s.framePhotoRequestJSON(ctx, r)
	return &out, nil
}
