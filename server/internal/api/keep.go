package api

// Ask to keep (docs/spec/leaving.md §4.2, README §5.21): a frame's or family's managers see what was taken away
// from them ("Taken away by Tom"), ask once per taking to keep it, and the person answers all, some or none.

import (
	"context"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

const (
	keepScopeListDays = 60 // the scope lists show requests of the last 60 days
	takenAwayThumbs   = 6
)

// frameLibraryManager: the frame's owner, admins and manage_library members (404 for non-members).
func (s *Server) frameLibraryManager(ctx context.Context, frameID uuid.UUID) (*store.Membership, error) {
	m, err := s.membership(ctx, frameID)
	if err != nil {
		return nil, err
	}
	if !m.Effective().ManageLibrary {
		return nil, problem.New(problem.MissingPermission, "requires the %s permission", "manage_library")
	}
	return m, nil
}

// feedManager: the family's owner and admins (404 for non-members).
func (s *Server) feedManager(ctx context.Context, feedID uuid.UUID) (*store.FeedView, error) {
	v, err := s.feedView(ctx, s.st.Q(), feedID)
	if err != nil {
		return nil, err
	}
	return v, requireFeedAdmin(v)
}

// isScopeManager reports whether userID manages the request's scope (and so may see it).
func (s *Server) isScopeManager(ctx context.Context, userID uuid.UUID, frameID, feedID *uuid.UUID) bool {
	if frameID != nil {
		m, err := s.st.GetMembership(ctx, s.st.Q(), *frameID, userID)
		return err == nil && m.Effective().ManageLibrary
	}
	if feedID != nil {
		v, err := s.st.GetFeedView(ctx, s.st.Q(), userID, *feedID)
		return err == nil && v.IsAdmin()
	}
	return false
}

// scopeManagers lists who manages the request's scope.
func (s *Server) scopeManagers(ctx context.Context, frameID, feedID *uuid.UUID) []uuid.UUID {
	var ids []uuid.UUID
	var err error
	switch {
	case frameID != nil:
		ids, err = s.st.FrameLibraryManagers(ctx, *frameID)
	case feedID != nil:
		ids, err = s.st.FeedAdminIDs(ctx, s.st.Q(), *feedID)
	}
	if err != nil {
		s.log.Warn("keep request managers", "err", err)
	}
	return ids
}

func (s *Server) keepRequestJSON(r *store.KeepRequest, items []store.KeepRequestItem) gen.KeepRequest {
	out := gen.KeepRequest{Id: r.ID, Status: gen.KeepRequestStatus(r.Status), TakingId: r.TakingID, FrameId: r.FrameID, FrameName: r.FrameName,
		FeedId: r.FeedID, FeedName: r.FeedName, AskedByName: r.AskedByName, Note: r.Note, AskedAt: r.CreatedAt, ExpiresAt: r.ExpiresAt,
		AnsweredAt: r.AnsweredAt, ItemCount: r.ItemCount, AllowedCount: ptr(r.AllowedCount)}
	out.Scope = gen.KeepRequestScopeFrame
	if r.FeedID != nil {
		out.Scope = gen.KeepRequestScopeFeed
	}
	if r.PersonID != nil {
		p := s.personRef(*r.PersonID, r.PersonName, r.PersonAvatarKey, r.PersonDeleted)
		if r.PersonDeleted {
			p.DisplayName = r.PersonName
		}
		out.Person = &p
	}
	if r.AskedBy != nil {
		p := s.personRef(*r.AskedBy, r.AskedByName, r.AskedByAvatarKey, r.AskedByDeleted)
		out.AskedBy = &p
	}
	if items != nil {
		list := make([]struct {
			Allowed *bool    `json:"allowed,omitempty"`
			Item    gen.Item `json:"item"`
		}, len(items))
		for i := range items {
			list[i].Item = s.itemJSON(&items[i].ItemRow)
			list[i].Allowed = items[i].Allowed
		}
		out.Items = &list
	}
	return out
}

// publishKeepRequest sends keep_request.updated to the person asked and the scope's managers, with pushes.
func (s *Server) publishKeepRequest(ctx context.Context, id uuid.UUID, pushes map[uuid.UUID]*notify.Push) {
	r, err := s.st.GetKeepRequest(ctx, s.st.Q(), id)
	if err != nil {
		return
	}
	ev := notify.Event{"type": "keep_request.updated", "request": s.keepRequestJSON(r, nil)}
	audience := s.scopeManagers(ctx, r.FrameID, r.FeedID)
	if r.PersonID != nil && !r.PersonDeleted {
		audience = appendUnique(audience, *r.PersonID)
	}
	for u := range pushes {
		audience = appendUnique(audience, u)
	}
	for _, u := range audience {
		s.notify.User(u, ev, pushes[u])
	}
}

// keepScopeName is the frame's or family's name of a request.
func keepScopeName(r *store.KeepRequest) string {
	if r.FrameName != nil {
		return *r.FrameName
	}
	return deref(r.FeedName)
}

func keepScopeIDs(r *store.KeepRequest) map[string]string {
	ids := map[string]string{"request_id": r.ID.String()}
	if r.FrameID != nil {
		ids["frame_id"] = r.FrameID.String()
	}
	if r.FeedID != nil {
		ids["feed_id"] = r.FeedID.String()
	}
	return ids
}

// ---------------------------------------------------------------- taken away groups

func (s *Server) ListFrameTakenAway(ctx context.Context, req gen.ListFrameTakenAwayRequestObject) (gen.ListFrameTakenAwayResponseObject, error) {
	if _, err := s.frameLibraryManager(ctx, req.FrameId); err != nil {
		return nil, err
	}
	gs, err := s.st.FrameTakenAwayGroups(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	return ok(gen.TakenAwayGroupList{Groups: s.takenAwayGroups(ctx, gs, &req.FrameId, nil)}), nil
}

func (s *Server) ListFeedTakenAway(ctx context.Context, req gen.ListFeedTakenAwayRequestObject) (gen.ListFeedTakenAwayResponseObject, error) {
	if _, err := s.feedManager(ctx, req.FeedId); err != nil {
		return nil, err
	}
	gs, err := s.st.FeedTakenAwayGroups(ctx, req.FeedId)
	if err != nil {
		return nil, err
	}
	return ok(gen.TakenAwayGroupList{Groups: s.takenAwayGroups(ctx, gs, nil, &req.FeedId)}), nil
}

func (s *Server) takenAwayGroups(ctx context.Context, gs []store.TakenAwayGroup, frameID, feedID *uuid.UUID) []gen.TakenAwayGroup {
	out := make([]gen.TakenAwayGroup, 0, len(gs))
	now := s.now()
	caller := me(ctx).ID
	for i := range gs {
		g := &gs[i]
		row := gen.TakenAwayGroup{TakingId: g.ID, ByName: g.UserName, TakenAt: g.CreatedAt, Until: g.Until, Count: g.Count}
		if g.UserID != nil && !g.UserDeleted {
			p := s.personRef(*g.UserID, g.UserName, g.UserAvatarKey, false)
			row.By = &p
		}
		var items []uuid.UUID
		var err error
		if frameID != nil {
			items, err = s.st.TakenItemsOnFrame(ctx, s.st.Q(), g.ID, *frameID)
		} else {
			items, err = s.st.TakenItemsInFeed(ctx, s.st.Q(), g.ID, *feedID)
		}
		if err == nil {
			if len(items) > takenAwayThumbs {
				items = items[:takenAwayThumbs]
			}
			if keys, err := s.st.ThumbKeys(ctx, items); err == nil {
				urls := make([]string, len(keys))
				for j, k := range keys {
					urls[j] = s.signedURL(k, thumbURLTTL)
				}
				row.PreviewThumbUrls = &urls
			}
		}
		kr, err := s.st.KeepRequestOf(ctx, s.st.Q(), g.ID, frameID, feedID)
		if err == nil {
			j := s.keepRequestJSON(kr, nil)
			row.KeepRequest = &j
		}
		reason := s.cannotAsk(ctx, &g.Taking, kr != nil, caller, now)
		row.CanAsk = reason == ""
		if reason != "" {
			row.CannotAskReason = ptr(gen.TakenAwayGroupCannotAskReason(reason))
		}
		out = append(out, row)
	}
	return out
}

// cannotAsk is why the caller can't ask to keep a taking now ("" when they can). The caller manages the scope.
func (s *Server) cannotAsk(ctx context.Context, t *store.Taking, asked bool, caller uuid.UUID, now time.Time) string {
	switch {
	case t.UserID == nil || t.UserDeleted:
		return "account_deleted"
	case asked:
		return "already_asked"
	case t.PurgedAt != nil || !now.Before(t.Until):
		return "expired"
	case *t.UserID == caller:
		return "not_allowed"
	}
	blocked, err := s.st.HasBlocked(ctx, s.st.Q(), *t.UserID, caller)
	if err != nil {
		return "not_allowed"
	}
	if blocked {
		return "blocked"
	}
	return ""
}

// ---------------------------------------------------------------- asking

type keepRef struct {
	ID uuid.UUID `json:"id"`
}

func (s *Server) CreateKeepRequest(ctx context.Context, req gen.CreateKeepRequestRequestObject) (gen.CreateKeepRequestResponseObject, error) {
	u := me(ctx)
	b := req.Body
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev keepRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "keep_request", key, &prev); err != nil {
			return nil, err
		} else if found {
			return s.renderCreatedKeep(ctx, prev.ID)
		}
	}
	if (b.FrameId == nil) == (b.FeedId == nil) {
		return nil, problem.Validation("frame_id", "exactly_one", "give either feed_id or frame_id")
	}
	var scopeName string
	if b.FrameId != nil {
		if _, err := s.frameLibraryManager(ctx, *b.FrameId); err != nil {
			return nil, err
		}
		f, err := s.st.GetFrame(ctx, s.st.Q(), *b.FrameId)
		if err != nil {
			return nil, notFound(err, "frame")
		}
		scopeName = f.Name
	} else {
		v, err := s.feedManager(ctx, *b.FeedId)
		if err != nil {
			return nil, err
		}
		scopeName = v.Name
	}
	t, err := s.st.GetTaking(ctx, s.st.Q(), b.TakingId)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "nothing of these photos is waiting here")
	} else if err != nil {
		return nil, err
	}
	var items []uuid.UUID
	if b.FrameId != nil {
		items, err = s.st.TakenItemsOnFrame(ctx, s.st.Q(), t.ID, *b.FrameId)
	} else {
		items, err = s.st.TakenItemsInFeed(ctx, s.st.Q(), t.ID, *b.FeedId)
	}
	if err != nil {
		return nil, err
	}
	if len(items) == 0 {
		return nil, problem.New(problem.NotFound, "nothing of these photos is waiting here")
	}
	now := s.now()
	switch s.cannotAsk(ctx, t, false, u.ID, now) {
	case "account_deleted":
		return nil, problem.New(problem.InvalidTransition, "nobody can be asked: the account was deleted")
	case "expired":
		return nil, problem.New(problem.InvalidTransition, "the 30 days are over")
	case "not_allowed":
		return nil, problem.New(problem.Forbidden, "you can't ask yourself to keep your photos")
	case "blocked":
		return nil, problem.New(problem.Forbidden, "this person doesn't accept requests")
	}
	all := true
	if b.ItemIds != nil {
		if len(*b.ItemIds) == 0 {
			return nil, problem.Validation("item_ids", "empty", "pick at least one photo")
		}
		in := map[uuid.UUID]bool{}
		for _, id := range items {
			in[id] = true
		}
		var picked []uuid.UUID
		for _, id := range *b.ItemIds {
			if !in[id] {
				return nil, problem.Validation("item_ids", "not_taken_away", "these photos are not among the ones taken away here")
			}
			picked = appendUnique(picked, id)
		}
		all = len(picked) == len(items)
		items = picked
	}
	var note *string
	if b.Note != nil {
		n := trimRunes(*b.Note, 300)
		if n != "" {
			note = &n
		}
	}
	k := store.NewKeepRequest{ID: store.NewID(), TakingID: t.ID, FrameID: b.FrameId, FeedID: b.FeedId, AskedBy: u.ID,
		AskedByName: u.DisplayName, Note: note, AllItems: all, ItemIDs: items, CreatedAt: now, ExpiresAt: t.Until}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.CreateKeepRequest(ctx, k); err != nil {
			return err
		}
		if key != "" {
			return tx.SaveIdempotent(ctx, u.ID, "keep_request", key, keepRef{k.ID})
		}
		return nil
	})
	if key != "" && db.IsUniqueViolation(err, "idempotency_keys_pkey") {
		var prev keepRef
		if found, _ := store.IdempotentResult(ctx, s.st.Q(), u.ID, "keep_request", key, &prev); found {
			return s.renderCreatedKeep(ctx, prev.ID)
		}
	}
	if err == store.ErrKeepAsked {
		return nil, problem.New(problem.LimitReached, "you already asked to keep these photos")
	}
	if err != nil {
		return nil, err
	}
	push := &notify.Push{Key: "keep_request", Text: plural("keep_request", len(items)),
		Args: map[string]string{"name": u.DisplayName, "count": strconv.Itoa(len(items)), "place": scopeName},
		IDs:  map[string]string{"request_id": k.ID.String()}}
	if b.FrameId != nil {
		push.IDs["frame_id"] = b.FrameId.String()
	} else {
		push.IDs["feed_id"] = b.FeedId.String()
	}
	s.publishKeepRequest(ctx, k.ID, map[uuid.UUID]*notify.Push{*t.UserID: push})
	return s.renderCreatedKeep(ctx, k.ID)
}

func (s *Server) renderCreatedKeep(ctx context.Context, id uuid.UUID) (Response, error) {
	r, err := s.st.GetKeepRequest(ctx, s.st.Q(), id)
	if err != nil {
		return Response{}, notFoundKeep(err)
	}
	return created(s.keepRequestJSON(r, nil)), nil
}

func notFoundKeep(err error) error {
	if err == store.ErrNotFound {
		return problem.New(problem.NotFound, "keep request not found")
	}
	return err
}

// trimRunes trims space and cuts s to n runes.
func trimRunes(s string, n int) string {
	r := []rune(strings.TrimSpace(s))
	if len(r) > n {
		r = r[:n]
	}
	return strings.TrimSpace(string(r))
}

// ---------------------------------------------------------------- lists

func (s *Server) ListMyKeepRequests(ctx context.Context, req gen.ListMyKeepRequestsRequestObject) (gen.ListMyKeepRequestsResponseObject, error) {
	u := me(ctx)
	onlyOpen := req.Params.Status == nil || *req.Params.Status == gen.ListMyKeepRequestsParamsStatusOpen
	return s.keepList(ctx, store.KeepRequestFilter{PersonID: &u.ID, OnlyOpen: onlyOpen})
}

func (s *Server) ListFrameKeepRequests(ctx context.Context, req gen.ListFrameKeepRequestsRequestObject) (gen.ListFrameKeepRequestsResponseObject, error) {
	if _, err := s.frameLibraryManager(ctx, req.FrameId); err != nil {
		return nil, err
	}
	return s.keepList(ctx, store.KeepRequestFilter{FrameID: &req.FrameId, Since: s.now().AddDate(0, 0, -keepScopeListDays)})
}

func (s *Server) ListFeedKeepRequests(ctx context.Context, req gen.ListFeedKeepRequestsRequestObject) (gen.ListFeedKeepRequestsResponseObject, error) {
	if _, err := s.feedManager(ctx, req.FeedId); err != nil {
		return nil, err
	}
	return s.keepList(ctx, store.KeepRequestFilter{FeedID: &req.FeedId, Since: s.now().AddDate(0, 0, -keepScopeListDays)})
}

func (s *Server) keepList(ctx context.Context, f store.KeepRequestFilter) (Response, error) {
	rs, err := s.st.ListKeepRequests(ctx, f)
	if err != nil {
		return Response{}, err
	}
	out := gen.KeepRequestList{Requests: make([]gen.KeepRequest, len(rs))}
	for i := range rs {
		out.Requests[i] = s.keepRequestJSON(&rs[i], nil)
	}
	return ok(out), nil
}

// visibleKeepRequest is a request the caller may see: the person asked or the scope's managers (404 otherwise).
func (s *Server) visibleKeepRequest(ctx context.Context, r *store.KeepRequest) (person bool, err error) {
	u := me(ctx)
	if r.PersonID != nil && *r.PersonID == u.ID {
		return true, nil
	}
	if s.isScopeManager(ctx, u.ID, r.FrameID, r.FeedID) {
		return false, nil
	}
	return false, problem.New(problem.NotFound, "keep request not found")
}

func (s *Server) GetKeepRequest(ctx context.Context, req gen.GetKeepRequestRequestObject) (gen.GetKeepRequestResponseObject, error) {
	r, err := s.st.GetKeepRequest(ctx, s.st.Q(), req.RequestId)
	if err != nil {
		return nil, notFoundKeep(err)
	}
	if _, err := s.visibleKeepRequest(ctx, r); err != nil {
		return nil, err
	}
	items, err := s.st.KeepRequestItems(ctx, s.st.Q(), r.ID)
	if err != nil {
		return nil, err
	}
	if items == nil {
		items = []store.KeepRequestItem{}
	}
	return ok(s.keepRequestJSON(r, items)), nil
}

// ---------------------------------------------------------------- answering

func (s *Server) AnswerKeepRequest(ctx context.Context, req gen.AnswerKeepRequestRequestObject) (gen.AnswerKeepRequestResponseObject, error) {
	r0, err := s.st.GetKeepRequest(ctx, s.st.Q(), req.RequestId)
	if err != nil {
		return nil, notFoundKeep(err)
	}
	person, err := s.visibleKeepRequest(ctx, r0)
	if err != nil {
		return nil, err
	}
	if !person {
		return nil, problem.New(problem.Forbidden, "only the person asked can answer")
	}
	b := req.Body
	now := s.now()
	var restoredFrames []store.RestoredFrame
	var restoredPosts []uuid.UUID
	var allowed []uuid.UUID
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		restoredFrames, restoredPosts, allowed = nil, nil, nil
		r, err := tx.LockKeepRequest(ctx, req.RequestId)
		if err != nil {
			return notFoundKeep(err)
		}
		if r.Status == "expired" || (r.Status == "open" && !now.Before(r.ExpiresAt)) {
			return problem.New(problem.InvalidTransition, "the 30 days are over")
		}
		if r.Status != "open" {
			return problem.New(problem.AlreadyDecided, "")
		}
		asked, err := s.st.KeepRequestItemIDs(ctx, tx, r.ID)
		if err != nil {
			return err
		}
		status := "declined"
		switch b.Allow {
		case gen.KeepRequestAnswerAllowAll:
			allowed, status = asked, "allowed"
		case gen.KeepRequestAnswerAllowSome:
			if b.ItemIds == nil || len(*b.ItemIds) == 0 {
				return problem.Validation("item_ids", "empty", "pick at least one photo")
			}
			in := map[uuid.UUID]bool{}
			for _, id := range asked {
				in[id] = true
			}
			for _, id := range *b.ItemIds {
				if !in[id] {
					return problem.Validation("item_ids", "not_requested", "these photos are not part of this request")
				}
				allowed = appendUnique(allowed, id)
			}
			status = "partly_allowed"
			if len(allowed) == len(asked) {
				status = "allowed"
			}
		case gen.KeepRequestAnswerAllowNone:
			allowed = []uuid.UUID{}
		default:
			return problem.Validation("allow", "enum", "allow must be all, some or none")
		}
		if err := tx.AnswerKeepRequest(ctx, r.ID, allowed, status, now); err != nil {
			return err
		}
		if len(allowed) == 0 {
			return nil
		}
		if r.FrameID != nil {
			ids, err := tx.RestoreOnFrame(ctx, r.TakingID, *r.FrameID, allowed)
			if err != nil {
				return err
			}
			if len(ids) > 0 {
				restoredFrames = []store.RestoredFrame{{FrameID: *r.FrameID, PlacementIDs: ids}}
			}
			return nil
		}
		restoredPosts, restoredFrames, err = tx.RestoreInFeed(ctx, r.TakingID, *r.FeedID, allowed)
		return err
	})
	if err != nil {
		return nil, err
	}
	u := me(ctx)
	for _, f := range restoredFrames {
		s.publishPlacementsUpdated(ctx, f.FrameID, f.PlacementIDs, "restored", &r0.TakingID, []uuid.UUID{u.ID})
	}
	for _, p := range restoredPosts {
		s.publishPostUpdated(ctx, p)
	}
	r, err := s.st.GetKeepRequest(ctx, s.st.Q(), req.RequestId)
	if err != nil {
		return nil, err
	}
	pushes := map[uuid.UUID]*notify.Push{}
	if r.AskedBy != nil && !r.AskedByDeleted {
		text := "keep_answered_none"
		switch r.Status {
		case "allowed":
			text = plural("keep_answered_all", r.ItemCount)
		case "partly_allowed":
			text = "keep_answered_some"
		}
		pushes[*r.AskedBy] = &notify.Push{Key: "keep_answered", Text: text,
			Args: map[string]string{"name": u.DisplayName, "count": strconv.Itoa(r.ItemCount), "allowed": strconv.Itoa(r.AllowedCount),
				"place": keepScopeName(r)},
			IDs: keepScopeIDs(r)}
	}
	s.publishKeepRequest(ctx, r.ID, pushes)
	items, err := s.st.KeepRequestItems(ctx, s.st.Q(), r.ID)
	if err != nil {
		return nil, err
	}
	if items == nil {
		items = []store.KeepRequestItem{}
	}
	return ok(s.keepRequestJSON(r, items)), nil
}
