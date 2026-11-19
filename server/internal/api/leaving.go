package api

// Leaving a family or frame with your photos (docs/spec/leaving.md, README §5.21): the leave choices, taking
// photos away with the 30-day notice, the leave sheet's preview, and the places people left where their photos
// still are. Keep requests are in keep.go, the memories filter in memories.go, operator ownership in ownership.go.

import (
	"context"
	"strconv"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// takeAwayNotice is how long taken-away photos wait in the frames' trash (decision C).
const takeAwayNotice = 30 * 24 * time.Hour

// takeAway is one taking with its comment deletes, run in the caller's transaction; announce after commit.
type takeAway struct {
	spec     store.TakeAwaySpec
	comments *uuid.UUID // delete the person's comments in this family

	out      *store.TakeAwayOutcome
	deleted  []deletedComment
	audience *postAudienceSnapshot
}

type deletedComment struct {
	postID uuid.UUID
	before *store.Comment
}

// newTakeAway prepares a taking by u (the clock's now; until is 30 days later).
func (s *Server) newTakeAway(u *store.User, scope string) *takeAway {
	now := s.now().UTC().Truncate(time.Microsecond)
	return &takeAway{spec: store.TakeAwaySpec{UserID: u.ID, UserName: u.DisplayName, Scope: scope, Now: now, Until: now.Add(takeAwayNotice)}}
}

// snapshot remembers who sees the posts the taking may remove (they are told after commit). Call before run.
func (s *Server) snapshotTakeAway(ctx context.Context, ta *takeAway) {
	var posts []uuid.UUID
	if ta.spec.FeedID != nil || ta.spec.All {
		ids, err := s.st.LivePostIDs(ctx, s.st.Q(), ta.spec.FeedID, &ta.spec.UserID, nil)
		if err != nil {
			s.log.Warn("leaving: live posts", "err", err)
		}
		posts = append(posts, ids...)
	}
	if ta.spec.All || len(ta.spec.FrameIDs) > 0 {
		direct, err := s.st.DirectPlacementIDs(ctx, s.st.Q(), ta.spec.UserID, ta.spec.All, ta.spec.FrameIDs)
		if err == nil {
			fps, err := s.st.FramePostsOfPlacements(ctx, s.st.Q(), direct)
			if err == nil {
				posts = append(posts, fps...)
			}
		}
	}
	ta.audience = s.postAudienceBefore(ctx, posts...)
}

// run takes the photos away (and deletes comments) in tx.
func (s *Server) runTakeAway(ctx context.Context, tx *store.Tx, ta *takeAway) error {
	ta.deleted = nil
	out, err := tx.TakeAway(ctx, ta.spec)
	if err != nil {
		return err
	}
	ta.out = out
	if ta.comments != nil {
		cs, err := s.st.CommentsOfUserInFeed(ctx, tx, *ta.comments, ta.spec.UserID)
		if err != nil {
			return err
		}
		posts := map[uuid.UUID]bool{}
		for _, c := range cs {
			before, err := s.st.GetComment(ctx, tx, c.PostID, c.CommentID)
			if err != nil {
				return err
			}
			if !posts[c.PostID] {
				posts[c.PostID] = true
				if _, err := tx.LockThread(ctx, c.PostID); err != nil {
					return err
				}
			}
			if err := tx.DeleteCommentByAuthor(ctx, c.CommentID); err != nil {
				return err
			}
			ta.deleted = append(ta.deleted, deletedComment{postID: c.PostID, before: before})
		}
		for p := range posts {
			if err := tx.RecountThread(ctx, p); err != nil {
				return err
			}
		}
		if out.Taking != nil {
			if err := tx.SetTakingComments(ctx, out.Taking.ID, len(ta.deleted)); err != nil {
				return err
			}
		}
	}
	return nil
}

// result renders the taking for the leave and take-away answers.
func (s *Server) takeAwayResult(ctx context.Context, ta *takeAway) gen.TakeAwayResult {
	res := gen.TakeAwayResult{CommentsDeleted: len(ta.deleted), Frames: []struct {
		FrameId   gen.Id `json:"frame_id"`
		FrameName string `json:"frame_name"`
		Photos    int    `json:"photos"`
	}{}}
	if ta.out != nil && ta.out.Taking != nil {
		res.TakingId = &ta.out.Taking.ID
		res.PhotosTakenAway = ta.out.Items
		res.Until = ptr(ta.out.Taking.Until)
		for _, f := range ta.out.Frames {
			res.Frames = append(res.Frames, struct {
				FrameId   gen.Id `json:"frame_id"`
				FrameName string `json:"frame_name"`
				Photos    int    `json:"photos"`
			}{f.FrameID, f.Name, f.Items})
		}
	}
	if ta.spec.FeedID != nil {
		n, err := s.remoteFrameCount(ctx, s.st.Q(), *ta.spec.FeedID)
		if err != nil {
			s.log.Warn("leaving: remote frames", "err", err)
		}
		if ta.out == nil || ta.out.Taking == nil || len(ta.out.Feeds) == 0 {
			n = 0
		}
		res.RemoteFrames = ptr(n)
	}
	return res
}

// announceTakeAway tells everyone after commit: post.removed for posts gone, placement.updated for the frames'
// library viewers and the person, photos_taken_away for the frames' and families' owners and admins, and the
// comment events of deleted comments.
func (s *Server) announceTakeAway(ctx context.Context, ta *takeAway) {
	ctx = context.WithoutCancel(ctx)
	s.publishTakenPosts(ctx, ta.audience)
	s.publishDeletedComments(ctx, ta.deleted)
	if ta.out == nil || ta.out.Taking == nil {
		return
	}
	t := ta.out.Taking
	date := t.Until.UTC().Format(dateLayout)
	for _, f := range ta.out.Frames {
		ev := notify.Event{"type": "placement.updated", "frame_id": f.FrameID, "placement_ids": f.PlacementIDs, "reason": "taken_away", "taking_id": t.ID}
		viewers, err := s.st.FrameLibraryViewers(ctx, f.FrameID)
		if err != nil {
			s.log.Warn("leaving: frame viewers", "frame", f.FrameID, "err", err)
			continue
		}
		admins, err := s.st.MemberIDs(ctx, f.FrameID, true)
		if err != nil {
			continue
		}
		isAdmin := map[uuid.UUID]bool{}
		for _, a := range admins {
			isAdmin[a] = true
		}
		push := &notify.Push{Key: "photos_taken_away", Text: plural("photos_taken_away", f.Items),
			Args: map[string]string{"name": t.UserName, "count": strconv.Itoa(f.Items), "frame": f.Name, "date": date},
			IDs:  map[string]string{"taking_id": t.ID.String(), "frame_id": f.FrameID.String()},
			Tag:  "photos_taken_away:" + t.ID.String() + ":" + f.FrameID.String()}
		for _, v := range appendUnique(viewers, admins...) {
			if v == ta.spec.UserID {
				continue
			}
			var p *notify.Push
			if isAdmin[v] {
				p = push
			}
			s.notify.User(v, ev, p)
		}
		s.notify.User(ta.spec.UserID, ev, nil)
	}
	for _, fd := range ta.out.Feeds {
		admins, err := s.st.FeedAdminIDs(ctx, s.st.Q(), fd.FeedID)
		if err != nil {
			continue
		}
		push := &notify.Push{Key: "photos_taken_away", Text: plural("photos_taken_away_feed", fd.Items),
			Args: map[string]string{"name": t.UserName, "count": strconv.Itoa(fd.Items), "feed": fd.Name, "date": date},
			IDs:  map[string]string{"taking_id": t.ID.String(), "feed_id": fd.FeedID.String()},
			Tag:  "photos_taken_away:" + t.ID.String() + ":" + fd.FeedID.String()}
		for _, a := range admins {
			if a != ta.spec.UserID && !s.hub.UserConnected(a) {
				s.notify.PushOnly(a, "photos_taken_away", push)
			}
		}
	}
}

// plural picks the "_one" text of a push for one photo.
func plural(key string, n int) string {
	if n == 1 {
		return key + "_one"
	}
	return key
}

// publishTakenPosts sends post.removed for the snapshotted posts that are gone now. Unlike publishPostGone it keeps
// the posts' reactions: a keep request may bring the post back (the purge drops them).
func (s *Server) publishTakenPosts(ctx context.Context, snap *postAudienceSnapshot) {
	if snap == nil {
		return
	}
	for id, before := range snap.posts {
		users := snap.users[id]
		p, err := s.st.SocialPost(ctx, s.st.Q(), users[0], id)
		if err == nil && !p.Gone && p.HasItems {
			continue
		}
		s.notify.Users(users, notify.Event{"type": "post.removed", "post_id": id, "feed_id": before.FeedID, "frame_id": before.FrameID}, nil)
	}
}

// publishDeletedComments sends the events of comments deleted by their author (as deleteComment does).
func (s *Server) publishDeletedComments(ctx context.Context, cs []deletedComment) {
	posts := map[uuid.UUID]bool{}
	var affected []uuid.UUID
	for _, d := range cs {
		posts[d.postID] = true
		affected = append(affected, mentionedUsers(d.before.Mentions)...)
		after, err := s.st.GetComment(ctx, s.st.Q(), d.postID, d.before.ID)
		if err != nil {
			continue
		}
		if after.ParentID == nil && after.ReplyCount > 0 {
			s.publishComment(ctx, d.postID, after.ID, "comment.updated", nil) // a tombstone
			continue
		}
		gone := []uuid.UUID{after.ID}
		if after.ParentID != nil {
			if parent, err := s.st.GetComment(ctx, s.st.Q(), d.postID, *after.ParentID); err == nil && parent.DeletedAt != nil && parent.ReplyCount == 0 {
				gone = append(gone, parent.ID)
			}
		}
		s.publishCommentDeleted(ctx, d.postID, gone, nil)
	}
	for p := range posts {
		s.publishPostUpdated(ctx, p)
	}
	if len(affected) > 0 {
		s.notifyBadge(ctx, affected...)
	}
}

// photosChoice reads the leave body's choice: `photos` wins; older clients' remove_* true means take; else keep
// (decision A). explicit reports whether `photos` was sent (200 with the result instead of 204).
func photosChoice(photos *gen.PhotosChoice, legacy *bool) (take, explicit bool) {
	if photos != nil {
		return *photos == gen.PhotosChoiceTake, true
	}
	return legacy != nil && *legacy, false
}

// ---------------------------------------------------------------- leaving

// leaveFeed is leaveFeed with the choices (README §5.21 "Leaving with a choice").
func (s *Server) leaveFeed(ctx context.Context, v *store.FeedView, body *gen.LeaveFeedRequest) (Response, error) {
	u := me(ctx)
	var b gen.LeaveFeedRequest
	if body != nil {
		b = *body
	}
	take, explicit := photosChoice(b.Photos, b.RemoveMyPosts)
	deleteComments := b.Comments != nil && *b.Comments == gen.CommentsChoiceDelete
	var leaveFrames []uuid.UUID
	if b.LeaveFrameIds != nil {
		if len(*b.LeaveFrameIds) > 50 {
			return Response{}, problem.Validation("leave_frame_ids", "too_many", "you can leave at most 50 frames at once")
		}
		allowed := map[uuid.UUID]bool{}
		frames, err := s.st.FeedLeaveFrames(ctx, s.st.Q(), v.ID, u.ID)
		if err != nil {
			return Response{}, err
		}
		for _, f := range frames {
			if f.Role != "owner" {
				allowed[f.FrameID] = true
			}
		}
		for _, id := range *b.LeaveFrameIds {
			if !allowed[id] {
				return Response{}, problem.Validation("leave_frame_ids", "not_allowed",
					"you can only leave frames of this family that you are a member of and don't own")
			}
			leaveFrames = appendUnique(leaveFrames, id)
		}
	}
	ta := s.newTakeAway(u, "feed")
	if take {
		ta.spec.FeedID, ta.spec.FrameIDs = &v.ID, leaveFrames
	}
	if deleteComments {
		ta.comments = &v.ID
	}
	if take {
		s.snapshotTakeAway(ctx, ta)
	}
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		if take || deleteComments {
			if err := s.runTakeAway(ctx, tx, ta); err != nil {
				return err
			}
		}
		for _, f := range leaveFrames {
			if err := s.removeMember(ctx, tx, f, u.ID, false); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return Response{}, err
	}
	// The family membership ends last (its own transaction, with the birthday unlinking).
	if err := s.removeFeedMember(ctx, v.ID, u.ID, false); err != nil {
		return Response{}, err
	}
	s.announceTakeAway(ctx, ta)
	for _, f := range leaveFrames {
		s.notify.User(u.ID, notify.Event{"type": "membership.updated", "frame_id": f}, nil)
	}
	if !explicit {
		return noContent(), nil
	}
	res := s.takeAwayResult(ctx, ta)
	res.LeftFrameIds = ptr(append([]uuid.UUID{}, leaveFrames...))
	return ok(res), nil
}

// leaveFrame is leaveFrame with the choice.
func (s *Server) leaveFrame(ctx context.Context, m *store.Membership, body *gen.LeaveFrameRequest) (Response, error) {
	u := me(ctx)
	var b gen.LeaveFrameRequest
	if body != nil {
		b = *body
	}
	take, explicit := photosChoice(b.Photos, b.RemoveMyItems)
	ta := s.newTakeAway(u, "frame")
	ta.spec.FrameIDs = []uuid.UUID{m.FrameID}
	if take {
		s.snapshotTakeAway(ctx, ta)
	}
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		if take {
			if err := s.runTakeAway(ctx, tx, ta); err != nil {
				return err
			}
		}
		return s.removeMember(ctx, tx, m.FrameID, u.ID, false)
	})
	if err != nil {
		return Response{}, err
	}
	s.announceTakeAway(ctx, ta)
	if !explicit {
		return noContent(), nil
	}
	return ok(s.takeAwayResult(ctx, ta)), nil
}

// ---------------------------------------------------------------- taking away later

type takeAwayRef struct {
	Scope  string             `json:"scope"`
	ID     uuid.UUID          `json:"id"`
	Result gen.TakeAwayResult `json:"result"`
}

func (s *Server) TakeAwayFromFeed(ctx context.Context, req gen.TakeAwayFromFeedRequestObject) (gen.TakeAwayFromFeedResponseObject, error) {
	u := me(ctx)
	if prev, ok := s.previousTakeAway(ctx, req.Params.IdempotencyKey, "feed", req.FeedId); ok {
		return prev, nil
	}
	if _, live, err := s.st.FeedExists(ctx, s.st.Q(), req.FeedId); err != nil {
		return nil, err
	} else if !live {
		return nil, problem.New(problem.NotFound, "feed not found")
	}
	ta := s.newTakeAway(u, "feed")
	ta.spec.FeedID = &req.FeedId
	if req.Body != nil && req.Body.Comments != nil && *req.Body.Comments == gen.CommentsChoiceDelete {
		ta.comments = &req.FeedId
	}
	return s.takeAwayNow(ctx, ta, req.Params.IdempotencyKey, "feed", req.FeedId)
}

func (s *Server) TakeAwayFromFrame(ctx context.Context, req gen.TakeAwayFromFrameRequestObject) (gen.TakeAwayFromFrameResponseObject, error) {
	u := me(ctx)
	if prev, ok := s.previousTakeAway(ctx, req.Params.IdempotencyKey, "frame", req.FrameId); ok {
		return prev, nil
	}
	if _, live, err := s.st.FrameExists(ctx, s.st.Q(), req.FrameId); err != nil {
		return nil, err
	} else if !live {
		return nil, problem.New(problem.NotFound, "frame not found")
	}
	ta := s.newTakeAway(u, "frame")
	ta.spec.FrameIDs = []uuid.UUID{req.FrameId}
	return s.takeAwayNow(ctx, ta, req.Params.IdempotencyKey, "frame", req.FrameId)
}

func (s *Server) previousTakeAway(ctx context.Context, key *string, scope string, id uuid.UUID) (Response, bool) {
	if key == nil {
		return Response{}, false
	}
	var prev takeAwayRef
	found, err := store.IdempotentResult(ctx, s.st.Q(), me(ctx).ID, "take_away", *key, &prev)
	if err != nil || !found || prev.Scope != scope || prev.ID != id {
		return Response{}, false
	}
	return ok(prev.Result), true
}

// takeAwayNow runs a taking for the take-away endpoints: 404 when the person has nothing live there.
func (s *Server) takeAwayNow(ctx context.Context, ta *takeAway, key *string, scope string, id uuid.UUID) (Response, error) {
	s.snapshotTakeAway(ctx, ta)
	var res gen.TakeAwayResult
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := s.runTakeAway(ctx, tx, ta); err != nil {
			return err
		}
		if ta.out.Taking == nil && len(ta.deleted) == 0 {
			return problem.New(problem.NotFound, "you have no photos here")
		}
		res = s.takeAwayResult(ctx, ta)
		if key != nil {
			return tx.SaveIdempotent(ctx, me(ctx).ID, "take_away", *key, takeAwayRef{Scope: scope, ID: id, Result: res})
		}
		return nil
	})
	if key != nil && db.IsUniqueViolation(err, "idempotency_keys_pkey") {
		if prev, ok := s.previousTakeAway(ctx, key, scope, id); ok {
			return prev, nil
		}
	}
	if err != nil {
		return Response{}, err
	}
	s.announceTakeAway(ctx, ta)
	return ok(res), nil
}

// ---------------------------------------------------------------- preview and former places

func (s *Server) GetLeavePreview(ctx context.Context, req gen.GetLeavePreviewRequestObject) (gen.GetLeavePreviewResponseObject, error) {
	u := me(ctx)
	p := req.Params
	if (p.FeedId == nil) == (p.FrameId == nil) {
		return nil, problem.Validation("feed_id", "exactly_one", "give either feed_id or frame_id")
	}
	if p.FeedId != nil {
		return s.feedLeavePreview(ctx, u, *p.FeedId)
	}
	return s.frameLeavePreview(ctx, u, *p.FrameId)
}

func (s *Server) feedLeavePreview(ctx context.Context, u *store.User, feedID uuid.UUID) (Response, error) {
	name, live, err := s.st.FeedExists(ctx, s.st.Q(), feedID)
	if err != nil {
		return Response{}, err
	}
	if !live {
		return Response{}, problem.New(problem.NotFound, "feed not found")
	}
	m, err := s.st.GetFeedMember(ctx, s.st.Q(), feedID, u.ID)
	if err != nil && err != store.ErrNotFound {
		return Response{}, err
	}
	c, err := s.st.FeedLeaveCounts(ctx, feedID, u.ID)
	if err != nil {
		return Response{}, err
	}
	member := m != nil
	if !member && c.Photos == 0 && c.AlreadyTakenAway == 0 && c.Comments == 0 {
		return Response{}, problem.New(problem.NotFound, "feed not found")
	}
	out := gen.LeavePreview{Scope: gen.LeavePreviewScopeFeed, FeedId: &feedID, Name: name, Member: member,
		Owner: ptr(member && m.Role == "owner"), Photos: c.Photos, Comments: ptr(c.Comments), AlreadyTakenAway: ptr(c.AlreadyTakenAway)}
	frames, err := s.st.FeedLeaveFrames(ctx, s.st.Q(), feedID, u.ID)
	if err != nil {
		return Response{}, err
	}
	list := make([]struct {
		CanLeave     *bool    `json:"can_leave,omitempty"`
		DirectPhotos *int     `json:"direct_photos,omitempty"`
		FrameId      gen.Id   `json:"frame_id"`
		FrameName    string   `json:"frame_name"`
		Role         gen.Role `json:"role"`
		Suggested    bool     `json:"suggested"`
	}, 0, len(frames))
	for _, f := range frames {
		fc, err := s.st.FrameLeaveCounts(ctx, s.st.Q(), f.FrameID, u.ID)
		if err != nil {
			return Response{}, err
		}
		canLeave := f.Role != "owner"
		list = append(list, struct {
			CanLeave     *bool    `json:"can_leave,omitempty"`
			DirectPhotos *int     `json:"direct_photos,omitempty"`
			FrameId      gen.Id   `json:"frame_id"`
			FrameName    string   `json:"frame_name"`
			Role         gen.Role `json:"role"`
			Suggested    bool     `json:"suggested"`
		}{CanLeave: ptr(canLeave), DirectPhotos: ptr(fc.Photos), FrameId: f.FrameID, FrameName: f.FrameName, Role: gen.Role(f.Role),
			Suggested: canLeave && f.Active})
	}
	out.Frames = &list
	return ok(out), nil
}

func (s *Server) frameLeavePreview(ctx context.Context, u *store.User, frameID uuid.UUID) (Response, error) {
	name, live, err := s.st.FrameExists(ctx, s.st.Q(), frameID)
	if err != nil {
		return Response{}, err
	}
	if !live {
		return Response{}, problem.New(problem.NotFound, "frame not found")
	}
	m, err := s.st.GetMembership(ctx, s.st.Q(), frameID, u.ID)
	if err != nil && err != store.ErrNotFound {
		return Response{}, err
	}
	c, err := s.st.FrameLeaveCounts(ctx, s.st.Q(), frameID, u.ID)
	if err != nil {
		return Response{}, err
	}
	member := m != nil
	if !member && c.Photos == 0 && c.AlreadyTakenAway == 0 {
		return Response{}, problem.New(problem.NotFound, "frame not found")
	}
	return ok(gen.LeavePreview{Scope: gen.LeavePreviewScopeFrame, FrameId: &frameID, Name: name, Member: member,
		Owner: ptr(member && m.Role == "owner"), Photos: c.Photos, AlreadyTakenAway: ptr(c.AlreadyTakenAway)}), nil
}

func (s *Server) ListFormerPlaces(ctx context.Context, _ gen.ListFormerPlacesRequestObject) (gen.ListFormerPlacesResponseObject, error) {
	u := me(ctx)
	feeds, err := s.st.FormerFeeds(ctx, u.ID)
	if err != nil {
		return nil, err
	}
	frames, err := s.st.FormerFrames(ctx, u.ID)
	if err != nil {
		return nil, err
	}
	out := gen.FormerPlaces{}
	out.Feeds = make([]struct {
		FeedId gen.Id     `json:"feed_id"`
		LeftAt *time.Time `json:"left_at,omitempty"`
		Name   string     `json:"name"`
		Photos int        `json:"photos"`
	}, 0, len(feeds))
	for _, f := range feeds {
		out.Feeds = append(out.Feeds, struct {
			FeedId gen.Id     `json:"feed_id"`
			LeftAt *time.Time `json:"left_at,omitempty"`
			Name   string     `json:"name"`
			Photos int        `json:"photos"`
		}{FeedId: f.ID, Name: f.Name, Photos: f.Photos})
	}
	out.Frames = make([]struct {
		FrameId gen.Id     `json:"frame_id"`
		LeftAt  *time.Time `json:"left_at,omitempty"`
		Name    string     `json:"name"`
		Photos  int        `json:"photos"`
	}, 0, len(frames))
	for _, f := range frames {
		out.Frames = append(out.Frames, struct {
			FrameId gen.Id     `json:"frame_id"`
			LeftAt  *time.Time `json:"left_at,omitempty"`
			Name    string     `json:"name"`
			Photos  int        `json:"photos"`
		}{FrameId: f.ID, Name: f.Name, Photos: f.Photos})
	}
	return ok(out), nil
}

// ---------------------------------------------------------------- the library's "Taken away" (listPlacements)

// libraryTakenAway prepares listPlacements for `visibility: taken_away` (managers only).
func (s *Server) libraryTakenAway(m *store.Membership, takingID *uuid.UUID, f *store.PlacementFilter) error {
	if f.Visibility != "taken_away" {
		return nil
	}
	if !m.Effective().ManageLibrary {
		return problem.New(problem.MissingPermission, "requires the %s permission", "manage_library")
	}
	f.OnlySender, f.TakingID = nil, takingID
	return nil
}

// libraryTakenAwayCount adds counts.taken_away for managers.
func (s *Server) libraryTakenAwayCount(ctx context.Context, m *store.Membership, counts map[string]int) {
	if !m.Effective().ManageLibrary {
		return
	}
	n, err := s.st.CountTakenAway(ctx, m.FrameID)
	if err != nil {
		s.log.Warn("leaving: count taken away", "frame", m.FrameID, "err", err)
		return
	}
	counts["taken_away"] = n
}

// purgeTakenAway is purgePlacement for a taken-away placement (a manager deletes it from the trash early);
// handled=false when the placement isn't taken away.
func (s *Server) purgeTakenAway(ctx context.Context, frameID, placementID uuid.UUID) (bool, error) {
	var taken bool
	if err := s.st.Q().QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM placements WHERE id = $1 AND frame_id = $2 AND taken_away_at IS NOT NULL)`,
		placementID, frameID).Scan(&taken); err != nil || !taken {
		return false, err
	}
	m, err := s.membership(ctx, frameID)
	if err != nil {
		return true, err
	}
	if !m.Effective().ManageLibrary {
		return true, problem.New(problem.MissingPermission, "requires the %s permission", "manage_library")
	}
	var taking *uuid.UUID
	if err := s.st.InTx(ctx, func(tx *store.Tx) error {
		var err error
		taking, err = tx.PurgeTakenAwayPlacement(ctx, frameID, placementID)
		return err
	}); err != nil {
		return true, err
	}
	if taking != nil {
		s.publishPlacementsUpdated(ctx, frameID, []uuid.UUID{placementID}, "purged", taking, nil)
	}
	return true, nil
}

// publishPlacementsUpdated sends placement.updated to the frame's library viewers and the given people.
func (s *Server) publishPlacementsUpdated(ctx context.Context, frameID uuid.UUID, ids []uuid.UUID, reason string, takingID *uuid.UUID, extra []uuid.UUID) {
	viewers, err := s.st.FrameLibraryViewers(ctx, frameID)
	if err != nil {
		return
	}
	ev := notify.Event{"type": "placement.updated", "frame_id": frameID, "placement_ids": ids, "reason": reason, "taking_id": takingID}
	s.notify.Users(appendUnique(viewers, extra...), ev, nil)
}

// placementTakenAwayFill fills Placement.taken_away for taken-away placements of a frame.
func (s *Server) placementTakenAwayFill(ctx context.Context, frameID uuid.UUID, placements []gen.Placement) {
	ids := make([]uuid.UUID, 0, len(placements))
	for _, p := range placements {
		if p.Status == "removed" {
			ids = append(ids, p.Id)
		}
	}
	if len(ids) == 0 {
		return
	}
	ta, err := s.st.TakenAwayOf(ctx, frameID, ids)
	if err != nil {
		s.log.Warn("leaving: taken away placements", "frame", frameID, "err", err)
		return
	}
	for i := range placements {
		r, ok := ta[placements[i].Id]
		if !ok {
			continue
		}
		out := gen.PlacementTakenAway{TakingId: r.TakingID, ByUserId: r.ByUserID, ByName: r.ByName, At: r.At, Until: r.Until}
		if r.RequestStatus != nil {
			out.KeepRequestStatus = ptr(gen.KeepRequestStatus(*r.RequestStatus))
		}
		placements[i].TakenAway = &out
	}
}
