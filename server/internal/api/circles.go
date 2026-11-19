package api

import (
	"context"
	"strings"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Circles: smaller groups inside a family (README §5.18 "Circles"). Their chats are kept by the database.

// ---------------------------------------------------------------- JSON

// circlesOut renders circles for viewer (feedAdmin: the viewer is the feed's owner or an admin).
func (s *Server) circlesOut(ctx context.Context, viewer uuid.UUID, cs []store.Circle) ([]gen.Circle, error) {
	out := make([]gen.Circle, len(cs))
	if len(cs) == 0 {
		return out, nil
	}
	ids := make([]uuid.UUID, len(cs))
	idx := map[uuid.UUID]int{}
	for i, c := range cs {
		ids[i], idx[c.ID] = c.ID, i
		created := c.CreatedAt
		out[i] = gen.Circle{Id: c.ID, FeedId: c.FeedID, Name: c.Name, Members: []gen.CircleMember{}, CreatedAt: &created,
			FrameIds: &[]gen.Id{}, FramesReached: &[]struct {
				FrameId   gen.Id `json:"frame_id"`
				FrameName string `json:"frame_name"`
			}{}}
	}
	ms, err := s.st.CircleMembers(ctx, s.st.Q(), ids)
	if err != nil {
		return nil, err
	}
	for _, m := range ms {
		k := idx[m.CircleID]
		added := m.AddedAt
		out[k].Members = append(out[k].Members, gen.CircleMember{UserId: m.UserID, DisplayName: m.DisplayName,
			AvatarUrl: s.avatarURL(m.AvatarKey), Role: gen.CircleRole(m.Role), AddedAt: &added})
		out[k].MemberCount++
		if m.UserID == viewer {
			out[k].MyRole = ptr(gen.CircleRole(m.Role))
		}
	}
	fs, err := s.st.CircleFrameIDs(ctx, s.st.Q(), ids)
	if err != nil {
		return nil, err
	}
	for _, f := range fs {
		k := idx[f.CircleID]
		*out[k].FrameIds = append(*out[k].FrameIds, f.FrameID)
	}
	reached, err := s.st.FramesReached(ctx, s.st.Q(), ids)
	if err != nil {
		return nil, err
	}
	for _, f := range reached {
		k := idx[f.CircleID]
		*out[k].FramesReached = append(*out[k].FramesReached, struct {
			FrameId   gen.Id `json:"frame_id"`
			FrameName string `json:"frame_name"`
		}{f.FrameID, f.FrameName})
	}
	convs, err := s.st.CircleConversations(ctx, s.st.Q(), ids)
	if err != nil {
		return nil, err
	}
	for i := range out {
		if out[i].MyRole != nil {
			if c, ok := convs[out[i].Id]; ok {
				out[i].ConversationId = &c
			}
		}
	}
	return out, nil
}

func (s *Server) circleOut(ctx context.Context, viewer uuid.UUID, c *store.Circle) (gen.Circle, error) {
	out, err := s.circlesOut(ctx, viewer, []store.Circle{*c})
	if err != nil {
		return gen.Circle{}, err
	}
	return out[0], nil
}

// ---------------------------------------------------------------- access

// circleAccess is the caller's view of a circle: members and the feed's owner/admins; 404 otherwise.
type circleAccess struct {
	feed   *store.FeedView
	circle *store.Circle
	role   string // the caller's circle role, "" when not in it
}

func (a *circleAccess) canManage() bool { return a.role == "admin" || a.feed.IsAdmin() }

func (s *Server) circleAccess(ctx context.Context, feedID, circleID uuid.UUID) (*circleAccess, error) {
	fv, err := s.feedView(ctx, s.st.Q(), feedID)
	if err != nil {
		return nil, err
	}
	c, err := s.st.GetCircle(ctx, s.st.Q(), feedID, circleID)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "circle not found")
	}
	if err != nil {
		return nil, err
	}
	role, err := s.st.CircleRole(ctx, s.st.Q(), circleID, me(ctx).ID)
	if err != nil {
		return nil, err
	}
	if role == "" && !fv.IsAdmin() {
		return nil, problem.New(problem.NotFound, "circle not found")
	}
	return &circleAccess{feed: fv, circle: c, role: role}, nil
}

func (s *Server) requireCircleManager(a *circleAccess) error {
	if !a.canManage() {
		return problem.New(problem.Forbidden, "only the circle's admins and the family's owner and admins can change this circle")
	}
	return nil
}

// circleName trims and checks a circle name.
func circleName(raw string) (string, error) {
	name := strings.TrimSpace(raw)
	if name == "" {
		return "", problem.Validation("name", "minLength", "a circle needs a name")
	}
	return name, nil
}

func duplicateCircleName() error {
	return problem.Validation("name", "duplicate", "a circle with this name already exists in this family")
}

// circleMembersOK checks that users are members of the feed.
func (s *Server) circleMembersOK(ctx context.Context, feedID uuid.UUID, users []uuid.UUID, field string) error {
	if len(users) == 0 {
		return nil
	}
	in, err := s.st.FeedMembersAmong(ctx, s.st.Q(), feedID, users)
	if err != nil {
		return err
	}
	for _, u := range users {
		if !in[u] {
			return problem.Validation(field, "not_member", "only members of the family can be in a circle")
		}
	}
	return nil
}

// circleFramesOK checks that frames have an active subscription to the feed.
func (s *Server) circleFramesOK(ctx context.Context, feedID uuid.UUID, frames []uuid.UUID) error {
	if len(frames) == 0 {
		return nil
	}
	in, err := s.st.ActiveSubscribedFrames(ctx, s.st.Q(), feedID, frames)
	if err != nil {
		return err
	}
	for _, f := range frames {
		if !in[f] {
			return problem.Validation("frame_ids", "not_subscribed", "only frames subscribed to the family can be added")
		}
	}
	return nil
}

func uniqueIDs(ids []uuid.UUID, except ...uuid.UUID) []uuid.UUID {
	seen := map[uuid.UUID]bool{}
	for _, e := range except {
		seen[e] = true
	}
	out := []uuid.UUID{}
	for _, id := range ids {
		if !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	return out
}

// ---------------------------------------------------------------- events

// circleAudience: the circle's members and the feed's owner/admins.
func (s *Server) circleAudience(ctx context.Context, c *store.Circle) []uuid.UUID {
	to, _ := s.st.CircleMemberIDs(ctx, s.st.Q(), c.ID)
	if admins, err := s.st.FeedAdminIDs(ctx, s.st.Q(), c.FeedID); err == nil {
		to = appendUnique(to, admins...)
	}
	return to
}

// publishCircle sends circle.updated, rendered for each receiver, to the audience and extra (people just removed).
func (s *Server) publishCircle(ctx context.Context, c *store.Circle, extra ...uuid.UUID) {
	for _, r := range appendUnique(s.circleAudience(ctx, c), extra...) {
		if !s.hub.UserConnected(r) {
			continue
		}
		out, err := s.circleOut(ctx, r, c)
		if err != nil {
			s.log.Warn("render circle", "circle", c.ID, "err", err)
			return
		}
		s.notify.User(r, notify.Event{"type": "circle.updated", "feed_id": c.FeedID, "circle": out}, nil)
	}
}

// ---------------------------------------------------------------- handlers

func (s *Server) ListCircles(ctx context.Context, req gen.ListCirclesRequestObject) (gen.ListCirclesResponseObject, error) {
	fv, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	cs, err := s.st.ListCircles(ctx, s.st.Q(), req.FeedId, me(ctx).ID, fv.IsAdmin())
	if err != nil {
		return nil, err
	}
	out, err := s.circlesOut(ctx, me(ctx).ID, cs)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

type circleRef struct {
	CircleID uuid.UUID `json:"circle_id"`
}

func (s *Server) CreateCircle(ctx context.Context, req gen.CreateCircleRequestObject) (gen.CreateCircleResponseObject, error) {
	u := me(ctx)
	fv, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	if !fv.IsAdmin() && !fv.MembersCanCreateCircles {
		return nil, problem.New(problem.Forbidden, "only the family's owner and admins can create circles")
	}
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev circleRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "circle", key, &prev); err != nil {
			return nil, err
		} else if found {
			return s.circleResponse(ctx, req.FeedId, prev.CircleID, true)
		}
	}
	b := req.Body
	name, err := circleName(b.Name)
	if err != nil {
		return nil, err
	}
	members := uniqueIDs(b.MemberIds, u.ID)
	if err := s.circleMembersOK(ctx, req.FeedId, members, "member_ids"); err != nil {
		return nil, err
	}
	var frames []uuid.UUID
	if b.FrameIds != nil {
		frames = uniqueIDs(*b.FrameIds)
	}
	if err := s.circleFramesOK(ctx, req.FeedId, frames); err != nil {
		return nil, err
	}
	c := store.Circle{ID: store.NewID(), FeedID: req.FeedId, Name: name}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFeed(ctx, req.FeedId); err != nil {
			return notFound(err, "feed")
		}
		n, err := s.st.CountCircles(ctx, tx, req.FeedId)
		if err != nil {
			return err
		}
		if n >= maxCirclesPerFeed {
			return problem.New(problem.LimitReached, "a family can have at most %d circles", maxCirclesPerFeed)
		}
		if taken, err := s.st.CircleNameTaken(ctx, tx, req.FeedId, name, nil); err != nil {
			return err
		} else if taken {
			return duplicateCircleName()
		}
		if err := tx.CreateCircle(ctx, c, u.ID, members, frames); err != nil {
			return err
		}
		if key != "" {
			return tx.SaveIdempotent(ctx, u.ID, "circle", key, circleRef{c.ID})
		}
		return nil
	})
	if db.IsUniqueViolation(err, "idempotency_keys_pkey") {
		var prev circleRef
		if found, _ := store.IdempotentResult(ctx, s.st.Q(), u.ID, "circle", key, &prev); found {
			return s.circleResponse(ctx, req.FeedId, prev.CircleID, true)
		}
	}
	if db.IsUniqueViolation(err, "circles_name_unique") {
		return nil, duplicateCircleName()
	}
	if err != nil {
		return nil, err
	}
	cc, err := s.st.GetCircle(ctx, s.st.Q(), req.FeedId, c.ID)
	if err != nil {
		return nil, err
	}
	s.publishCircle(ctx, cc)
	return s.circleResponse(ctx, req.FeedId, c.ID, true)
}

// circleResponse renders a circle for the caller (201 when created).
func (s *Server) circleResponse(ctx context.Context, feedID, id uuid.UUID, isNew bool) (Response, error) {
	c, err := s.st.GetCircle(ctx, s.st.Q(), feedID, id)
	if err != nil {
		return Response{}, notFound(err, "")
	}
	out, err := s.circleOut(ctx, me(ctx).ID, c)
	if err != nil {
		return Response{}, err
	}
	if isNew {
		return created(out), nil
	}
	return ok(out), nil
}

func (s *Server) GetCircle(ctx context.Context, req gen.GetCircleRequestObject) (gen.GetCircleResponseObject, error) {
	if _, err := s.circleAccess(ctx, req.FeedId, req.CircleId); err != nil {
		return nil, err
	}
	return s.circleResponse(ctx, req.FeedId, req.CircleId, false)
}

func (s *Server) UpdateCircle(ctx context.Context, req gen.UpdateCircleRequestObject) (gen.UpdateCircleResponseObject, error) {
	a, err := s.circleAccess(ctx, req.FeedId, req.CircleId)
	if err != nil {
		return nil, err
	}
	if err := s.requireCircleManager(a); err != nil {
		return nil, err
	}
	b := req.Body
	var name *string
	if b.Name != nil {
		n, err := circleName(*b.Name)
		if err != nil {
			return nil, err
		}
		name = &n
	}
	var frames []uuid.UUID
	if b.FrameIds != nil {
		frames = uniqueIDs(*b.FrameIds)
		if err := s.circleFramesOK(ctx, req.FeedId, frames); err != nil {
			return nil, err
		}
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockCircle(ctx, req.FeedId, req.CircleId); err != nil {
			return problem.New(problem.NotFound, "circle not found")
		}
		if name != nil {
			if taken, err := s.st.CircleNameTaken(ctx, tx, req.FeedId, *name, &req.CircleId); err != nil {
				return err
			} else if taken {
				return duplicateCircleName()
			}
			if err := tx.RenameCircle(ctx, req.CircleId, *name); err != nil {
				return err
			}
		}
		if b.FrameIds != nil {
			if err := tx.SetCircleFrames(ctx, req.CircleId, frames, me(ctx).ID); err != nil {
				return err
			}
		}
		return tx.TouchCircle(ctx, req.CircleId)
	})
	if db.IsUniqueViolation(err, "circles_name_unique") {
		return nil, duplicateCircleName()
	}
	if err != nil {
		return nil, err
	}
	return s.afterCircleChange(ctx, req.FeedId, req.CircleId)
}

// afterCircleChange tells the circle's audience (and extra) and renders it for the caller.
func (s *Server) afterCircleChange(ctx context.Context, feedID, circleID uuid.UUID, extra ...uuid.UUID) (Response, error) {
	c, err := s.st.GetCircle(ctx, s.st.Q(), feedID, circleID)
	if err != nil {
		return Response{}, err
	}
	s.publishCircle(ctx, c, extra...)
	return s.circleResponse(ctx, feedID, circleID, false)
}

func (s *Server) DeleteCircle(ctx context.Context, req gen.DeleteCircleRequestObject) (gen.DeleteCircleResponseObject, error) {
	a, err := s.circleAccess(ctx, req.FeedId, req.CircleId)
	if err != nil {
		return nil, err
	}
	if err := s.requireCircleManager(a); err != nil {
		return nil, err
	}
	audience := s.circleAudience(ctx, a.circle)
	posts, err := s.st.LiveCirclePosts(ctx, s.st.Q(), req.CircleId)
	if err != nil {
		return nil, err
	}
	snap := s.postAudienceBefore(ctx, posts...)
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockCircle(ctx, req.FeedId, req.CircleId); err != nil {
			return problem.New(problem.NotFound, "circle not found")
		}
		var sweeps []uuid.UUID
		for _, p := range posts {
			_, subs, err := tx.RemovePostItems(ctx, p, nil, &me(ctx).ID)
			if err != nil {
				return err
			}
			sweeps = append(sweeps, subs...)
		}
		if err := enqueueSweeps(ctx, tx, sweeps); err != nil {
			return err
		}
		return tx.MarkCircleDeleted(ctx, req.CircleId)
	})
	if err != nil {
		return nil, err
	}
	s.publishPostGone(ctx, snap)
	s.notify.Users(audience, notify.Event{"type": "circle.removed", "feed_id": req.FeedId, "circle_id": req.CircleId}, nil)
	return noContent(), nil
}

func (s *Server) AddCircleMembers(ctx context.Context, req gen.AddCircleMembersRequestObject) (gen.AddCircleMembersResponseObject, error) {
	a, err := s.circleAccess(ctx, req.FeedId, req.CircleId)
	if err != nil {
		return nil, err
	}
	if err := s.requireCircleManager(a); err != nil {
		return nil, err
	}
	users := uniqueIDs(req.Body.UserIds)
	if err := s.circleMembersOK(ctx, req.FeedId, users, "user_ids"); err != nil {
		return nil, err
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockCircle(ctx, req.FeedId, req.CircleId); err != nil {
			return problem.New(problem.NotFound, "circle not found")
		}
		if _, err := tx.AddCircleMembers(ctx, req.CircleId, req.FeedId, users, me(ctx).ID); err != nil {
			return err
		}
		return tx.TouchCircle(ctx, req.CircleId)
	})
	if err != nil {
		return nil, err
	}
	return s.afterCircleChange(ctx, req.FeedId, req.CircleId)
}

func (s *Server) UpdateCircleMember(ctx context.Context, req gen.UpdateCircleMemberRequestObject) (gen.UpdateCircleMemberResponseObject, error) {
	a, err := s.circleAccess(ctx, req.FeedId, req.CircleId)
	if err != nil {
		return nil, err
	}
	if err := s.requireCircleManager(a); err != nil {
		return nil, err
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockCircle(ctx, req.FeedId, req.CircleId); err != nil {
			return problem.New(problem.NotFound, "circle not found")
		}
		found, err := tx.SetCircleMemberRole(ctx, req.CircleId, req.UserId, string(req.Body.Role))
		if err != nil {
			return err
		}
		if !found {
			return problem.New(problem.NotFound, "member not found")
		}
		return tx.TouchCircle(ctx, req.CircleId)
	})
	if err != nil {
		return nil, err
	}
	return s.afterCircleChange(ctx, req.FeedId, req.CircleId)
}

func (s *Server) RemoveCircleMember(ctx context.Context, req gen.RemoveCircleMemberRequestObject) (gen.RemoveCircleMemberResponseObject, error) {
	a, err := s.circleAccess(ctx, req.FeedId, req.CircleId)
	if err != nil {
		return nil, err
	}
	if req.UserId != me(ctx).ID {
		if err := s.requireCircleManager(a); err != nil {
			return nil, err
		}
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockCircle(ctx, req.FeedId, req.CircleId); err != nil {
			return problem.New(problem.NotFound, "circle not found")
		}
		found, err := tx.RemoveCircleMember(ctx, req.CircleId, req.UserId)
		if err != nil {
			return err
		}
		if !found {
			return problem.New(problem.NotFound, "member not found")
		}
		return tx.TouchCircle(ctx, req.CircleId)
	})
	if err != nil {
		return nil, err
	}
	if _, err := s.afterCircleChange(ctx, req.FeedId, req.CircleId, req.UserId); err != nil {
		return nil, err
	}
	return noContent(), nil
}
