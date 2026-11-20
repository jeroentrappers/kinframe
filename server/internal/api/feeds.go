package api

import (
	"context"
	"crypto/rand"
	"math/big"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Feeds (docs/spec/feeds.md, README §5.12): feeds, members, join requests and
// invites. Posts and receipts are in feedposts.go, subscriptions in
// subscriptions.go, background jobs and notifications in feedjobs.go.

// ---------------------------------------------------------------- JSON

type feedSummaryJSON struct {
	ID                uuid.UUID  `json:"id"`
	Name              string     `json:"name"`
	CoverURL          *string    `json:"cover_url,omitempty"`
	Role              string     `json:"role"`
	MemberCount       int        `json:"member_count"`
	SubscriptionCount int        `json:"subscription_count"`
	MyPostCount       int        `json:"my_post_count"` // the caller's live posts (home family, simple-app.md §3)
	LastPostAt        *time.Time `json:"last_post_at,omitempty"`
	CreatedAt         time.Time  `json:"created_at"`
	ConversationID    *uuid.UUID `json:"conversation_id,omitempty"` // the family chat (chats: feedChats)

	feedEngagementJSON // engagement: freshness and streak (listMyFeeds, getFeed)
}

type feedJSON struct {
	feedSummaryJSON
	OwnerName                string     `json:"owner_name"`
	CoverItemID              *uuid.UUID `json:"cover_item_id,omitempty"`
	AutoApproveSubscriptions bool       `json:"auto_approve_subscriptions"`
	InviteRequiresApproval   bool       `json:"invite_requires_approval"`
	MembersSeeReactions      bool       `json:"members_see_reactions"`
	PostersCanInvite         bool       `json:"posters_can_invite"`
	PostersCanAddBirthdays   bool       `json:"posters_can_add_birthdays"`
	BirthdayNotifications    bool       `json:"birthday_notifications"`
	BirthdayCount            int        `json:"birthday_count"`
	PendingBirthdayCount     int        `json:"pending_birthday_count"`
	PostCount                int        `json:"post_count"`
	PendingSubscriptionCount int        `json:"pending_subscription_count"`
	PendingJoinRequestCount  int        `json:"pending_join_request_count"`
	// Family social (README §5.18).
	MembersSeeReceipts      bool   `json:"members_see_receipts"`
	NewMembersCanPost       bool   `json:"new_members_can_post"`
	MembersCanCreateCircles bool   `json:"members_can_create_circles"`
	CircleCount             int    `json:"circle_count"`
	Mute                    string `json:"mute"`
	// Families on different servers (README §5.22).
	AllowRemoteFrames bool `json:"allow_remote_frames"`
}

func (s *Server) feedCoverURL(key *string) *string {
	if key == nil {
		return nil
	}
	return ptr(s.signedURL(*key, thumbURLTTL))
}

func (s *Server) feedSummaryOut(v *store.FeedView) feedSummaryJSON {
	return feedSummaryJSON{
		ID: v.ID, Name: v.Name, CoverURL: s.feedCoverURL(v.CoverThumbKey), Role: v.Role, MemberCount: v.MemberCount,
		SubscriptionCount: v.SubscriptionCount, MyPostCount: v.MyPostCount, LastPostAt: v.LastPostAt, CreatedAt: v.CreatedAt,
	}
}

func (s *Server) feedOut(v *store.FeedView) feedJSON {
	out := feedJSON{
		feedSummaryJSON: s.feedSummaryOut(v), CoverItemID: v.CoverItemID,
		AutoApproveSubscriptions: v.AutoApproveSubscriptions, InviteRequiresApproval: v.InviteRequiresApproval,
		MembersSeeReactions: v.MembersSeeReactions, PostersCanInvite: v.PostersCanInvite, PostCount: v.PostCount,
		PostersCanAddBirthdays: v.PostersCanAddBirthdays, BirthdayNotifications: v.BirthdayNotifications, BirthdayCount: v.BirthdayCount,
		MembersSeeReceipts: v.MembersSeeReceipts, NewMembersCanPost: v.NewMembersCanPost, MembersCanCreateCircles: v.MembersCanCreateCircles,
		CircleCount: v.CircleCount, Mute: v.Mute, AllowRemoteFrames: v.AllowRemoteFrames,
	}
	if v.OwnerName != nil {
		out.OwnerName = *v.OwnerName
	}
	if v.IsAdmin() {
		out.PendingSubscriptionCount, out.PendingJoinRequestCount = v.PendingSubscriptionCount, v.PendingJoinRequestCount
		out.PendingBirthdayCount = v.PendingBirthdayCount
	}
	return out
}

type feedMemberJSON struct {
	UserID       uuid.UUID  `json:"user_id"`
	DisplayName  string     `json:"display_name"`
	AvatarURL    *string    `json:"avatar_url,omitempty"`
	Role         string     `json:"role"`
	JoinedAt     time.Time  `json:"joined_at"`
	LastPostedAt *time.Time `json:"last_posted_at,omitempty"`
}

func (s *Server) feedMemberOut(m *store.FeedMemberRow) feedMemberJSON {
	return feedMemberJSON{UserID: m.UserID, DisplayName: m.DisplayName, AvatarURL: s.avatarURL(m.AvatarKey), Role: m.Role,
		JoinedAt: m.JoinedAt, LastPostedAt: m.LastPostedAt}
}

type feedInviteJSON struct {
	ID            uuid.UUID `json:"id"`
	Purpose       string    `json:"purpose"`
	Token         string    `json:"token"`
	Code          string    `json:"code"`
	URL           string    `json:"url"`
	CreatedByName string    `json:"created_by_name"`
	MaxUses       *int      `json:"max_uses"`
	Uses          int       `json:"uses"`
	Note          *string   `json:"note,omitempty"`
	ExpiresAt     time.Time `json:"expires_at"`
	AllowRemote   bool      `json:"allow_remote"` // frames on other servers may use it (README §5.22)
}

func (s *Server) feedInviteOut(i *store.FeedInvite) feedInviteJSON {
	out := feedInviteJSON{ID: i.ID, Purpose: i.Purpose, Token: i.Token, Code: i.Code, URL: s.cfg.PublicURL + "/fi/" + i.Token,
		MaxUses: i.MaxUses, Uses: i.Uses, Note: i.Note, ExpiresAt: i.ExpiresAt, AllowRemote: i.AllowRemote}
	if i.CreatedByName != nil {
		out.CreatedByName = *i.CreatedByName
	}
	return out
}

// ---------------------------------------------------------------- access

// feedView returns the caller's view of a feed they are a member of; unknown
// and foreign feeds are both 404 (feeds are never discoverable).
func (s *Server) feedView(ctx context.Context, q db.DBTX, feedID uuid.UUID) (*store.FeedView, error) {
	v, err := s.st.GetFeedView(ctx, q, me(ctx).ID, feedID)
	if err == store.ErrNotFound || (err == nil && v.Role == "") {
		return nil, problem.New(problem.NotFound, "feed not found")
	}
	return v, err
}

func requireFeedAdmin(v *store.FeedView) error {
	if !v.IsAdmin() {
		return problem.New(problem.Forbidden, "owner or admin only")
	}
	return nil
}

func requireFeedOwner(v *store.FeedView) error {
	if v.Role != "owner" {
		return problem.New(problem.Forbidden, "owner only")
	}
	return nil
}

// feedTargets validates that the caller is a member of every feed (any role).
func (s *Server) feedTargets(ctx context.Context, feedIDs []uuid.UUID) ([]uuid.UUID, error) {
	seen := map[uuid.UUID]bool{}
	var out []uuid.UUID
	for _, id := range feedIDs {
		if seen[id] {
			continue
		}
		seen[id] = true
		v, err := s.feedView(ctx, s.st.Q(), id)
		if err != nil {
			return nil, err
		}
		if v.Role == "member" { // reads, comments and reacts, but does not post photos (README §5.18)
			return nil, problem.New(problem.MissingPermission, "members of this family can't post photos")
		}
		out = append(out, id)
	}
	return out, nil
}

// ---------------------------------------------------------------- feeds

func (s *Server) ListMyFeeds(ctx context.Context, _ gen.ListMyFeedsRequestObject) (gen.ListMyFeedsResponseObject, error) {
	vs, err := s.st.ListFeedsForUser(ctx, me(ctx).ID)
	if err != nil {
		return nil, err
	}
	out := make([]feedSummaryJSON, len(vs))
	for i := range vs {
		out[i] = s.feedSummaryOut(&vs[i])
	}
	s.fillFeedEngagement(ctx, out) // engagement
	s.feedChats(ctx, out)
	return ok(out), nil
}

type feedRef struct {
	FeedID uuid.UUID `json:"feed_id"`
}

func (s *Server) CreateFeed(ctx context.Context, req gen.CreateFeedRequestObject) (gen.CreateFeedResponseObject, error) {
	u := me(ctx)
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev feedRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "feed", key, &prev); err != nil {
			return nil, err
		} else if found {
			v, err := s.feedView(ctx, s.st.Q(), prev.FeedID)
			if err != nil {
				return nil, err
			}
			return created(s.feedOut(v)), nil
		}
	}
	b := req.Body
	name := strings.TrimSpace(b.Name)
	if name == "" {
		return nil, problem.Validation("name", "minLength", "a feed needs a name")
	}
	f := store.Feed{ID: store.NewID(), Name: name, InviteRequiresApproval: true,
		MembersSeeReactions: true, NewMembersCanPost: true, MembersCanCreateCircles: true}
	if b.AutoApproveSubscriptions != nil {
		f.AutoApproveSubscriptions = *b.AutoApproveSubscriptions
	}
	if b.InviteRequiresApproval != nil {
		f.InviteRequiresApproval = *b.InviteRequiresApproval
	}
	if b.MembersSeeReactions != nil {
		f.MembersSeeReactions = *b.MembersSeeReactions
	}
	if b.PostersCanInvite != nil {
		f.PostersCanInvite = *b.PostersCanInvite
	}
	if b.PostersCanAddBirthdays != nil {
		f.PostersCanAddBirthdays = *b.PostersCanAddBirthdays
	}
	socialFeedCreate(&f, b)
	limit := s.Limits(ctx).MaxFeedsPerUser
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		// Serialize this user's feed creations so the limit holds.
		if _, err := tx.Exec(ctx, `SELECT 1 FROM users WHERE id = $1 FOR UPDATE`, u.ID); err != nil {
			return err
		}
		n, err := s.st.CountOwnedFeeds(ctx, tx, u.ID)
		if err != nil {
			return err
		}
		if n >= limit {
			return problem.New(problem.LimitReached, "you can own at most %d feeds", limit)
		}
		if err := tx.CreateFeed(ctx, f, u.ID); err != nil {
			return err
		}
		if err := s.federationFeedSettings(ctx, tx, f.ID, b.AllowRemoteFrames); err != nil { // federation_settings.go
			return err
		}
		if key != "" {
			return tx.SaveIdempotent(ctx, u.ID, "feed", key, feedRef{f.ID})
		}
		return nil
	})
	if db.IsUniqueViolation(err, "idempotency_keys_pkey") {
		var prev feedRef
		if found, _ := store.IdempotentResult(ctx, s.st.Q(), u.ID, "feed", key, &prev); found {
			f.ID, err = prev.FeedID, nil
		}
	}
	if err != nil {
		return nil, err
	}
	v, err := s.feedView(ctx, s.st.Q(), f.ID)
	if err != nil {
		return nil, err
	}
	return created(s.feedOut(v)), nil
}

func (s *Server) GetFeed(ctx context.Context, req gen.GetFeedRequestObject) (gen.GetFeedResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	out := s.feedOut(v)
	out.feedEngagementJSON = s.feedEngagementFields(ctx, v.ID) // engagement
	s.feedChat(ctx, &out.feedSummaryJSON)
	return ok(out), nil
}

func (s *Server) UpdateFeed(ctx context.Context, req gen.UpdateFeedRequestObject) (gen.UpdateFeedResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	if err := requireFeedAdmin(v); err != nil {
		return nil, err
	}
	b := req.Body
	p := store.FeedPatch{AutoApproveSubscriptions: b.AutoApproveSubscriptions, InviteRequiresApproval: b.InviteRequiresApproval,
		MembersSeeReactions: b.MembersSeeReactions, PostersCanInvite: b.PostersCanInvite, PostersCanAddBirthdays: b.PostersCanAddBirthdays,
		MembersSeeReceipts: b.MembersSeeReceipts, NewMembersCanPost: b.NewMembersCanPost, MembersCanCreateCircles: b.MembersCanCreateCircles}
	renamed := false
	if b.Name != nil {
		name := strings.TrimSpace(*b.Name)
		if name == "" {
			return nil, problem.Validation("name", "minLength", "a feed needs a name")
		}
		p.Name, renamed = &name, name != v.Name
	}
	if _, null := rawField(ctx, "cover_item_id"); null {
		p.ClearCover = true
	} else if b.CoverItemId != nil {
		has, err := s.st.FeedHasLiveItem(ctx, s.st.Q(), req.FeedId, *b.CoverItemId)
		if err != nil {
			return nil, err
		}
		if !has {
			return nil, problem.Validation("cover_item_id", "not_in_feed", "the cover must be a photo of this feed")
		}
		p.CoverItemID = b.CoverItemId
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFeed(ctx, req.FeedId); err != nil {
			return notFound(err, "feed")
		}
		if err := tx.UpdateFeed(ctx, req.FeedId, p); err != nil {
			return err
		}
		if err := s.federationFeedSettings(ctx, tx, req.FeedId, b.AllowRemoteFrames); err != nil { // federation_settings.go
			return err
		}
		if renamed {
			// Frames show "Sender · Feed name": re-emit the feed's placements.
			return tx.BumpFeedPlacements(ctx, req.FeedId)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	if v, err = s.feedView(ctx, s.st.Q(), req.FeedId); err != nil {
		return nil, err
	}
	return ok(s.feedOut(v)), nil
}

func (s *Server) DeleteFeed(ctx context.Context, req gen.DeleteFeedRequestObject) (gen.DeleteFeedResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	if err := requireFeedOwner(v); err != nil {
		return nil, err
	}
	members, err := s.st.FeedMemberIDs(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	var ended []store.Subscription
	var photos []string
	var sources []removedSource
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		ended = nil
		if _, err := tx.LockFeed(ctx, req.FeedId); err != nil {
			return notFound(err, "feed")
		}
		// Its photo sources go with it (the posts go anyway).
		var err error
		if sources, err = s.deleteSourcesOf(ctx, tx, nil, &req.FeedId, false); err != nil {
			return err
		}
		if err := tx.MarkFeedDeleted(ctx, req.FeedId); err != nil {
			return err
		}
		// Its birthday people, photos and greetings go at once; ending the
		// subscriptions below sends the frames their new lists.
		if photos, err = tx.DeleteFeedBirthdays(ctx, req.FeedId); err != nil {
			return err
		}
		// All posts go: nothing is placed any more, and the sweeps remove what feeds alone held.
		if _, err := tx.Exec(ctx, `UPDATE feed_post_items SET removed_at = now() WHERE removed_at IS NULL
			AND post_id IN (SELECT id FROM feed_posts WHERE feed_id = $1)`, req.FeedId); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE feed_posts SET removed_at = now(), removed_by = $2 WHERE feed_id = $1 AND removed_at IS NULL`, req.FeedId, me(ctx).ID); err != nil {
			return err
		}
		subs, err := s.st.LiveSubscriptionsOfFeed(ctx, tx, req.FeedId)
		if err != nil {
			return err
		}
		if err := s.federationFeedEnded(ctx, tx, req.FeedId, "feed_deleted"); err != nil { // frames on other servers
			return err
		}
		for _, sub0 := range subs {
			if _, err := tx.LockFrame(ctx, sub0.FrameID); err != nil && err != store.ErrNotFound {
				return err
			}
			sub, err := tx.LockSubscription(ctx, sub0.ID)
			if err != nil {
				return err
			}
			if err := tx.EndSubscription(ctx, sub, "removed", "feed_deleted", nil); err != nil {
				return err
			}
			ended = append(ended, *sub)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	for _, k := range photos {
		s.deleteBlob(ctx, k)
	}
	s.announceRemovedSources(sources)
	if _, err := s.st.FinishFeedDelete(ctx, req.FeedId); err != nil {
		s.log.Warn("finish feed delete", "feed", req.FeedId, "err", err)
	}
	for i := range ended {
		if ended[i].Status == "active" || ended[i].Status == "paused" {
			s.notifySubscriptionRemoved(ctx, &ended[i], v.Name)
		}
	}
	s.notify.Users(members, notify.Event{"type": "feed.membership_updated", "feed_id": req.FeedId}, nil)
	return noContent(), nil
}

// ---------------------------------------------------------------- members

func (s *Server) ListFeedMembers(ctx context.Context, req gen.ListFeedMembersRequestObject) (gen.ListFeedMembersResponseObject, error) {
	if _, err := s.feedView(ctx, s.st.Q(), req.FeedId); err != nil {
		return nil, err
	}
	ms, err := s.st.ListFeedMembers(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	out := make([]feedMemberJSON, len(ms))
	for i := range ms {
		out[i] = s.feedMemberOut(&ms[i])
	}
	return ok(out), nil
}

func (s *Server) feedMemberTarget(ctx context.Context, feedID, userID uuid.UUID) (*store.FeedView, *store.FeedMemberRow, error) {
	v, err := s.feedView(ctx, s.st.Q(), feedID)
	if err != nil {
		return nil, nil, err
	}
	t, err := s.st.GetFeedMember(ctx, s.st.Q(), feedID, userID)
	if err != nil {
		return nil, nil, notFound(err, "member")
	}
	return v, t, nil
}

func (s *Server) checkFeedMemberChange(ctx context.Context, caller *store.FeedView, target *store.FeedMemberRow) error {
	if err := requireFeedAdmin(caller); err != nil {
		return err
	}
	if target.Role == "owner" {
		return problem.New(problem.Forbidden, "the owner cannot be changed; transfer ownership instead")
	}
	if caller.Role == "admin" && target.Role == "admin" && target.UserID != me(ctx).ID {
		return problem.New(problem.Forbidden, "admins cannot change other admins")
	}
	return nil
}

func (s *Server) UpdateFeedMember(ctx context.Context, req gen.UpdateFeedMemberRequestObject) (gen.UpdateFeedMemberResponseObject, error) {
	v, target, err := s.feedMemberTarget(ctx, req.FeedId, req.UserId)
	if err != nil {
		return nil, err
	}
	if err := s.checkFeedMemberChange(ctx, v, target); err != nil {
		return nil, err
	}
	role := string(req.Body.Role)
	if role != target.Role {
		if err := s.st.InTx(ctx, func(tx *store.Tx) error { return tx.SetFeedMemberRole(ctx, req.FeedId, req.UserId, role) }); err != nil {
			return nil, err
		}
		s.notifyFeedMembership(ctx, req.FeedId, v.Name, req.UserId)
	}
	m, err := s.st.GetFeedMember(ctx, s.st.Q(), req.FeedId, req.UserId)
	if err != nil {
		return nil, err
	}
	return ok(s.feedMemberOut(m)), nil
}

func (s *Server) RemoveFeedMember(ctx context.Context, req gen.RemoveFeedMemberRequestObject) (gen.RemoveFeedMemberResponseObject, error) {
	v, target, err := s.feedMemberTarget(ctx, req.FeedId, req.UserId)
	if err != nil {
		return nil, err
	}
	if target.UserID == me(ctx).ID {
		if target.Role == "owner" {
			return nil, problem.New(problem.OwnerCannotLeave, "")
		}
	} else if err := s.checkFeedMemberChange(ctx, v, target); err != nil {
		return nil, err
	}
	removePosts := req.Params.RemovePosts != nil && *req.Params.RemovePosts
	if err := s.removeFeedMember(ctx, req.FeedId, req.UserId, removePosts); err != nil {
		return nil, err
	}
	if target.UserID != me(ctx).ID {
		s.notifyFeedMembership(ctx, req.FeedId, v.Name, req.UserId)
	}
	return noContent(), nil
}

func (s *Server) LeaveFeed(ctx context.Context, req gen.LeaveFeedRequestObject) (gen.LeaveFeedResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	if v.Role == "owner" {
		return nil, problem.New(problem.OwnerCannotLeave, "")
	}
	// Leaving (README §5.21): keep or take the photos, comments, frames left with it (leaving.go).
	return s.leaveFeed(ctx, v, req.Body)
}

// removeFeedMember ends a membership, optionally unsending the member's posts
// (removed from the feed and every subscribed frame).
func (s *Server) removeFeedMember(ctx context.Context, feedID, userID uuid.UUID, removePosts bool) error {
	var sweeps []uuid.UUID
	var unlinked []store.UnlinkedBirthday
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		sweeps = nil
		if removePosts {
			posts, err := s.st.LivePostIDs(ctx, tx, &feedID, &userID, nil)
			if err != nil {
				return err
			}
			for _, p := range posts {
				_, subs, err := tx.RemovePostItems(ctx, p, nil, &userID)
				if err != nil {
					return err
				}
				sweeps = append(sweeps, subs...)
			}
			if err := enqueueSweeps(ctx, tx, sweeps); err != nil {
				return err
			}
		}
		if err := tx.RemoveFeedMember(ctx, feedID, userID); err != nil {
			return err
		}
		// Their birthday entry stays, unlinked; frames lose the avatar it may have used (birthdays.md §10).
		var err error
		unlinked, err = tx.UnlinkBirthdays(ctx, &feedID, userID)
		return err
	})
	if err == nil {
		s.publishUnlinked(ctx, unlinked)
	}
	return err
}

func enqueueSweeps(ctx context.Context, tx *store.Tx, subs []uuid.UUID) error {
	seen := map[uuid.UUID]bool{}
	for _, id := range subs {
		if seen[id] {
			continue
		}
		seen[id] = true
		if err := tx.Enqueue(ctx, store.JobFeedSweep, store.SweepJob{SubscriptionID: id}); err != nil {
			return err
		}
	}
	return nil
}

func (s *Server) TransferFeedOwnership(ctx context.Context, req gen.TransferFeedOwnershipRequestObject) (gen.TransferFeedOwnershipResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	if err := requireFeedOwner(v); err != nil {
		return nil, err
	}
	target := req.Body.UserId
	if target == me(ctx).ID {
		return nil, problem.Validation("user_id", "self", "you already own this feed")
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFeed(ctx, req.FeedId); err != nil {
			return notFound(err, "feed")
		}
		if _, err := s.st.GetFeedMember(ctx, tx, req.FeedId, target); err != nil {
			if err == store.ErrNotFound {
				return problem.Validation("user_id", "not_member", "the new owner must be a member of the feed")
			}
			return err
		}
		if err := tx.SetFeedMemberRole(ctx, req.FeedId, me(ctx).ID, "admin"); err != nil {
			return err
		}
		return tx.SetFeedMemberRole(ctx, req.FeedId, target, "owner")
	})
	if err != nil {
		return nil, err
	}
	s.notifyFeedMembership(ctx, req.FeedId, v.Name, target)
	return noContent(), nil
}

// ---------------------------------------------------------------- join requests

func (s *Server) ListFeedJoinRequests(ctx context.Context, req gen.ListFeedJoinRequestsRequestObject) (gen.ListFeedJoinRequestsResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	if err := requireFeedAdmin(v); err != nil {
		return nil, err
	}
	jrs, err := s.st.ListPendingFeedJoinRequests(ctx, req.FeedId)
	if err != nil {
		return nil, err
	}
	out := make([]gen.JoinRequest, len(jrs))
	for i := range jrs {
		out[i] = s.joinRequestJSON(&jrs[i])
	}
	return ok(out), nil
}

func (s *Server) DecideFeedJoinRequest(ctx context.Context, req gen.DecideFeedJoinRequestRequestObject) (gen.DecideFeedJoinRequestResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	if err := requireFeedAdmin(v); err != nil {
		return nil, err
	}
	approve := req.Decision == gen.DecideFeedJoinRequestParamsDecisionApprove
	var userID uuid.UUID
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		jr, err := tx.LockFeedJoinRequest(ctx, req.FeedId, req.RequestId)
		if err != nil {
			return notFound(err, "join request")
		}
		if jr.Status != "pending" {
			return problem.New(problem.AlreadyDecided, "")
		}
		userID = jr.UserID
		status := "denied"
		if approve {
			status = "approved"
			if err := tx.AddFeedMember(ctx, req.FeedId, jr.UserID, joinRole(&v.Feed)); err != nil {
				return err
			}
		}
		return tx.DecideFeedJoinRequest(ctx, jr.ID, status)
	})
	if err != nil {
		return nil, err
	}
	s.notifyFeedMembership(ctx, req.FeedId, v.Name, userID)
	return noContent(), nil
}

// ---------------------------------------------------------------- invites

// Crockford base32 without I, L, O and U: codes are read aloud and typed.
const codeAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// newFeedCode returns 8 Crockford base32 characters, never all digits (so one
// "Enter a code" field tells them from 8-digit frame codes).
func newFeedCode() string {
	for {
		b := make([]byte, 8)
		digits := true
		for i := range b {
			n, err := rand.Int(rand.Reader, big.NewInt(int64(len(codeAlphabet))))
			if err != nil {
				panic(err)
			}
			b[i] = codeAlphabet[n.Int64()]
			if b[i] > '9' {
				digits = false
			}
		}
		if !digits {
			return string(b)
		}
	}
}

// normalizeFeedCode upper-cases a typed code and drops the hyphen and spaces;
// the look-alikes I/L (1) and O (0) are read as Crockford does.
func normalizeFeedCode(code string) string {
	code = strings.ToUpper(strings.NewReplacer("-", "", " ", "").Replace(code))
	return strings.NewReplacer("I", "1", "L", "1", "O", "0").Replace(code)
}

func (s *Server) ListFeedInvites(ctx context.Context, req gen.ListFeedInvitesRequestObject) (gen.ListFeedInvitesResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	var by *uuid.UUID
	if !v.IsAdmin() {
		if !v.PostersCanInvite || v.Role == "member" {
			return nil, problem.New(problem.Forbidden, "owner or admin only")
		}
		by = &me(ctx).ID
	}
	invs, err := s.st.ListActiveFeedInvites(ctx, req.FeedId, by)
	if err != nil {
		return nil, err
	}
	out := make([]feedInviteJSON, len(invs))
	for i := range invs {
		out[i] = s.feedInviteOut(&invs[i])
	}
	return ok(out), nil
}

func (s *Server) CreateFeedInvite(ctx context.Context, req gen.CreateFeedInviteRequestObject) (gen.CreateFeedInviteResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	b := req.Body
	purpose := string(b.Purpose)
	if !v.IsAdmin() && (!v.PostersCanInvite || purpose != "join" || v.Role == "member") {
		return nil, problem.New(problem.Forbidden, "owner or admin only")
	}
	u := me(ctx)
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev feedInviteJSON
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "feed_invite", key, &prev); err != nil {
			return nil, err
		} else if found {
			return created(prev), nil
		}
	}
	if ok, retry := s.inviteUser.Allow(u.ID.String()); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	hours := 168
	if b.ExpiresInHours != nil {
		hours = *b.ExpiresInHours
	}
	note := b.Note
	if note != nil && strings.TrimSpace(*note) == "" {
		note = nil
	}
	var out feedInviteJSON
	for attempt := 0; ; attempt++ {
		err = s.st.InTx(ctx, func(tx *store.Tx) error {
			inv, err := s.st.CreateFeedInvite(ctx, tx, store.FeedInvite{
				ID: store.NewID(), FeedID: req.FeedId, Purpose: purpose, Token: auth.RandomToken(24), Code: newFeedCode(),
				CreatedBy: &u.ID, MaxUses: b.MaxUses, Note: note,
				ExpiresAt: time.Now().Add(time.Duration(hours) * time.Hour).UTC().Truncate(time.Second),
			})
			if err != nil {
				return err
			}
			if inv, err = s.federationInviteSettings(ctx, tx, inv, v, b.AllowRemote); err != nil { // federation_settings.go
				return err
			}
			out = s.feedInviteOut(inv)
			if key != "" {
				return tx.SaveIdempotent(ctx, u.ID, "feed_invite", key, out)
			}
			return nil
		})
		if db.IsUniqueViolation(err, "feed_invites_code_key") && attempt < 5 {
			continue
		}
		break
	}
	if db.IsUniqueViolation(err, "idempotency_keys_pkey") {
		var prev feedInviteJSON
		if found, _ := store.IdempotentResult(ctx, s.st.Q(), u.ID, "feed_invite", key, &prev); found {
			return created(prev), nil
		}
	}
	if err != nil {
		return nil, err
	}
	return created(out), nil
}

func (s *Server) RevokeFeedInvite(ctx context.Context, req gen.RevokeFeedInviteRequestObject) (gen.RevokeFeedInviteResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	inv, err := s.st.GetFeedInvite(ctx, req.FeedId, req.InviteId)
	if err != nil {
		return nil, notFound(err, "invite")
	}
	if !v.IsAdmin() && (inv.CreatedBy == nil || *inv.CreatedBy != me(ctx).ID) {
		return nil, problem.New(problem.Forbidden, "only the creator or an owner/admin can revoke this invite")
	}
	if err := s.st.RevokeFeedInvite(ctx, req.FeedId, req.InviteId); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// usableFeedInvite resolves an invite: unknown (or of a deleted feed) → 404
// invite_invalid, used up/expired/revoked → 410 invite_expired.
func (s *Server) usableFeedInvite(ctx context.Context, q db.DBTX, inv *store.FeedInvite, err error) (*store.FeedInvite, *store.FeedView, error) {
	if err == store.ErrNotFound {
		return nil, nil, problem.New(problem.InviteInvalid, "")
	}
	if err != nil {
		return nil, nil, err
	}
	v, err := s.st.GetFeedView(ctx, q, me(ctx).ID, inv.FeedID)
	if err == store.ErrNotFound {
		return nil, nil, problem.New(problem.InviteInvalid, "")
	}
	if err != nil {
		return nil, nil, err
	}
	if !inv.Usable(time.Now()) {
		return nil, nil, problem.New(problem.InviteExpired, "")
	}
	return inv, v, nil
}

type feedInviteFrameJSON struct {
	FrameID            uuid.UUID `json:"frame_id"`
	FrameName          string    `json:"frame_name"`
	SubscriptionStatus *string   `json:"subscription_status"`
}

type feedInvitePreviewJSON struct {
	Token            string                `json:"token"`
	Purpose          string                `json:"purpose"`
	FeedName         string                `json:"feed_name"`
	CoverURL         *string               `json:"cover_url,omitempty"`
	OwnerName        string                `json:"owner_name"`
	InvitedByName    string                `json:"invited_by_name"`
	MemberCount      int                   `json:"member_count"`
	RequiresApproval bool                  `json:"requires_approval"`
	AlreadyMember    bool                  `json:"already_member"`
	Frames           []feedInviteFrameJSON `json:"frames,omitempty"`
}

func (s *Server) feedInvitePreview(ctx context.Context, inv *store.FeedInvite, v *store.FeedView) (feedInvitePreviewJSON, error) {
	out := feedInvitePreviewJSON{
		Token: inv.Token, Purpose: inv.Purpose, FeedName: v.Name, CoverURL: s.feedCoverURL(v.CoverThumbKey),
		MemberCount: v.MemberCount, RequiresApproval: inv.Purpose == "join" && (v.InviteRequiresApproval || inv.Via != nil), AlreadyMember: v.Role != "",
	}
	if v.OwnerName != nil {
		out.OwnerName = *v.OwnerName
	}
	if inv.CreatedByName != nil {
		out.InvitedByName = *inv.CreatedByName
	}
	if inv.Purpose == "subscribe" {
		out.Frames = []feedInviteFrameJSON{}
		frames, err := s.st.ListFramesForUser(ctx, me(ctx).ID)
		if err != nil {
			return out, err
		}
		for _, f := range frames {
			m := store.Membership{Role: f.Role, Permissions: f.Permissions}
			if !m.Effective().ChangeSettings {
				continue
			}
			fj := feedInviteFrameJSON{FrameID: f.ID, FrameName: f.Name}
			if sub, err := s.st.LiveSubscription(ctx, s.st.Q(), v.ID, f.ID); err == nil {
				fj.SubscriptionStatus = &sub.Status
			} else if err != store.ErrNotFound {
				return out, err
			}
			out.Frames = append(out.Frames, fj)
		}
	}
	return out, nil
}

func (s *Server) PreviewFeedInvite(ctx context.Context, req gen.PreviewFeedInviteRequestObject) (gen.PreviewFeedInviteResponseObject, error) {
	inv, err := s.st.GetFeedInviteByToken(ctx, s.st.Q(), req.Token)
	inv, v, err := s.usableFeedInvite(ctx, s.st.Q(), inv, err)
	if err != nil {
		return nil, err
	}
	out, err := s.feedInvitePreview(ctx, inv, v)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

func (s *Server) LookupFeedInvite(ctx context.Context, req gen.LookupFeedInviteRequestObject) (gen.LookupFeedInviteResponseObject, error) {
	if err := s.limitRedeem(ctx); err != nil {
		return nil, err
	}
	inv, err := s.st.GetFeedInviteByCode(ctx, s.st.Q(), normalizeFeedCode(req.Body.Code))
	inv, v, err := s.usableFeedInvite(ctx, s.st.Q(), inv, err)
	if err != nil {
		return nil, err
	}
	out, err := s.feedInvitePreview(ctx, inv, v)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

type feedInviteResultJSON struct {
	Status        string             `json:"status"`
	Feed          feedSummaryJSON    `json:"feed"`
	JoinRequestID *uuid.UUID         `json:"join_request_id,omitempty"`
	Subscriptions []subscriptionJSON `json:"subscriptions,omitempty"`
}

type feedInviteRef struct {
	Status        string      `json:"status"`
	FeedID        uuid.UUID   `json:"feed_id"`
	JoinRequestID *uuid.UUID  `json:"join_request_id,omitempty"`
	Subscriptions []uuid.UUID `json:"subscriptions,omitempty"`
}

func (s *Server) AcceptFeedInvite(ctx context.Context, req gen.AcceptFeedInviteRequestObject) (gen.AcceptFeedInviteResponseObject, error) {
	u := me(ctx)
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev feedInviteRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "feed_invite_accept", key, &prev); err != nil {
			return nil, err
		} else if found {
			return s.feedInviteResult(ctx, prev)
		}
	}
	inv, err := s.st.GetFeedInviteByToken(ctx, s.st.Q(), req.Token)
	inv, v, err := s.usableFeedInvite(ctx, s.st.Q(), inv, err)
	if err != nil {
		return nil, err
	}
	var out feedInviteRef
	var jr *store.JoinRequest
	var activated []uuid.UUID
	switch inv.Purpose {
	case "join":
		err = s.st.InTx(ctx, func(tx *store.Tx) error {
			jr, out = nil, feedInviteRef{FeedID: v.ID}
			if err := tx.LockFeedInvite(ctx, inv.ID); err != nil {
				return err
			}
			if _, err := s.st.GetFeedMember(ctx, tx, v.ID, u.ID); err == nil {
				out.Status = "already_member"
				return nil
			} else if err != store.ErrNotFound {
				return err
			}
			cur, err := s.st.GetFeedInviteByToken(ctx, tx, req.Token)
			if err != nil {
				return err
			}
			if !cur.Usable(time.Now()) {
				return problem.New(problem.InviteExpired, "")
			}
			if err := tx.UseFeedInvite(ctx, inv.ID); err != nil {
				return err
			}
			if v.InviteRequiresApproval || inv.Via != nil { // channel invites always need approval
				r, isNew, err := tx.CreateFeedJoinRequest(ctx, v.ID, u.ID, &inv.ID, inv.CreatedBy, inv.Note)
				if err != nil {
					return err
				}
				if isNew && inv.Via != nil {
					if r, err = s.markChannelJoin(ctx, tx, r, inv.Via, inv.ViaRef, u, true); err != nil {
						return err
					}
				}
				out.Status, out.JoinRequestID = "pending", &r.ID
				if isNew {
					jr = r
				}
			} else {
				if err := tx.AddFeedMember(ctx, v.ID, u.ID, joinRole(&v.Feed)); err != nil {
					return err
				}
				out.Status = "joined"
			}
			if key != "" {
				return tx.SaveIdempotent(ctx, u.ID, "feed_invite_accept", key, out)
			}
			return nil
		})
	case "subscribe":
		b := req.Body
		if b == nil || b.FrameIds == nil || len(*b.FrameIds) == 0 {
			return nil, problem.Validation("frame_ids", "required", "choose the frames to show this feed on")
		}
		history, count, perr := subscriptionHistory(b.History, b.HistoryCount)
		if perr != nil {
			return nil, perr
		}
		frames, ferr := s.managedFrames(ctx, *b.FrameIds)
		if ferr != nil {
			return nil, ferr
		}
		limit := s.Limits(ctx).MaxSubscriptionsPerFeed
		err = s.st.InTx(ctx, func(tx *store.Tx) error {
			out, activated = feedInviteRef{Status: "subscribed", FeedID: v.ID}, nil
			if err := tx.LockFeedInvite(ctx, inv.ID); err != nil {
				return err
			}
			cur, err := s.st.GetFeedInviteByToken(ctx, tx, req.Token)
			if err != nil {
				return err
			}
			if !cur.Usable(time.Now()) {
				return problem.New(problem.InviteExpired, "")
			}
			used := false
			for _, fid := range frames {
				if _, err := tx.LockFrame(ctx, fid); err != nil {
					return notFound(err, "frame")
				}
				if sub, err := s.st.LiveSubscription(ctx, tx, v.ID, fid); err == nil {
					out.Subscriptions = append(out.Subscriptions, sub.ID)
					continue
				} else if err != store.ErrNotFound {
					return err
				}
				n, err := s.st.CountLiveSubscriptions(ctx, tx, v.ID)
				if err != nil {
					return err
				}
				if n >= limit {
					return problem.New(problem.LimitReached, "a feed can be shown on at most %d frames", limit)
				}
				id := store.NewID()
				if err := tx.CreateSubscription(ctx, store.Subscription{ID: id, FeedID: v.ID, FrameID: fid, History: history,
					HistoryCount: count, RequestedBy: &u.ID, ViaInviteID: &inv.ID, Birthdays: b.Birthdays == nil || *b.Birthdays}); err != nil {
					return err
				}
				// An admin made the link: pre-approved.
				if err := tx.ActivateSubscription(ctx, id, inv.CreatedBy); err != nil {
					return err
				}
				out.Subscriptions = append(out.Subscriptions, id)
				activated = append(activated, id)
				used = true
			}
			if used {
				if err := tx.UseFeedInvite(ctx, inv.ID); err != nil {
					return err
				}
			}
			if key != "" {
				return tx.SaveIdempotent(ctx, u.ID, "feed_invite_accept", key, out)
			}
			return nil
		})
	}
	if db.IsUniqueViolation(err, "idempotency_keys_pkey") {
		var prev feedInviteRef
		if found, _ := store.IdempotentResult(ctx, s.st.Q(), u.ID, "feed_invite_accept", key, &prev); found {
			return s.feedInviteResult(ctx, prev)
		}
	}
	if db.IsUniqueViolation(err, "subscriptions_one_live") {
		return nil, problem.New(problem.AlreadySubscribed, "")
	}
	if err != nil {
		return nil, err
	}
	if jr != nil {
		s.announceFeedJoinRequest(ctx, v, jr)
	}
	for _, id := range activated {
		s.publishSubscription(ctx, id, nil)
	}
	return s.feedInviteResult(ctx, out)
}

func (s *Server) feedInviteResult(ctx context.Context, ref feedInviteRef) (Response, error) {
	v, err := s.st.GetFeedView(ctx, s.st.Q(), me(ctx).ID, ref.FeedID)
	if err != nil {
		return Response{}, notFound(err, "feed")
	}
	out := feedInviteResultJSON{Status: ref.Status, Feed: s.feedSummaryOut(v), JoinRequestID: ref.JoinRequestID}
	if ref.Status == "subscribed" {
		out.Subscriptions = []subscriptionJSON{}
		for _, id := range ref.Subscriptions {
			sv, err := s.st.GetSubscriptionView(ctx, s.st.Q(), id)
			if err != nil {
				continue
			}
			out.Subscriptions = append(out.Subscriptions, s.subscriptionOut(ctx, sv, s.viewerOf(ctx, me(ctx).ID, sv)))
		}
	}
	return ok(out), nil
}

// managedFrames checks that the caller manages every frame (owner, admin or
// change_settings) and returns them deduplicated, in frame order.
func (s *Server) managedFrames(ctx context.Context, frameIDs []uuid.UUID) ([]uuid.UUID, error) {
	seen := map[uuid.UUID]bool{}
	var out []uuid.UUID
	for _, id := range frameIDs {
		if seen[id] {
			continue
		}
		seen[id] = true
		m, err := s.membership(ctx, id)
		if err != nil {
			return nil, err
		}
		if err := requirePerm(m, m.Effective().ChangeSettings, "change_settings"); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	sortIDs(out)
	return out, nil
}
