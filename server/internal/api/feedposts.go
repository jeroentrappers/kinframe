package api

import (
	"context"
	"net/http"
	"sort"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Feed posts and receipts (feeds.md §7). A post is made by createSend or
// forwardSend with feed_ids; the placements follow in the background
// (store.FeedFanout).

// ---------------------------------------------------------------- JSON

type feedReceiptSummaryJSON struct {
	Frames           int `json:"frames"`
	Delivered        int `json:"delivered"`
	Seen             int `json:"seen"`
	AwaitingApproval int `json:"awaiting_approval"`
	Paused           int `json:"paused"`
	Removed          int `json:"removed"`
}

type feedReactionJSON struct {
	FrameID     uuid.UUID `json:"frame_id"`
	FrameName   string    `json:"frame_name"`
	ItemID      uuid.UUID `json:"item_id"`
	PlacementID uuid.UUID `json:"placement_id"`
	Reaction    *string   `json:"reaction"`
	Replies     []string  `json:"replies"`
	At          time.Time `json:"at"`
}

type feedPosterJSON struct {
	UserID      uuid.UUID `json:"user_id"`
	DisplayName string    `json:"display_name"`
	AvatarURL   *string   `json:"avatar_url,omitempty"`
}

type feedPostJSON struct {
	ID        uuid.UUID               `json:"id"`
	FeedID    uuid.UUID               `json:"feed_id"`
	Poster    feedPosterJSON          `json:"poster"`
	SendID    *uuid.UUID              `json:"send_id,omitempty"`
	CreatedAt time.Time               `json:"created_at"`
	Via       *gen.ItemVia            `json:"via,omitempty"`
	Items     []gen.Item              `json:"items"`
	Receipts  *feedReceiptSummaryJSON `json:"receipts"`
	Reactions *[]feedReactionJSON     `json:"reactions"`
}

type feedItemReceiptJSON struct {
	ItemID      uuid.UUID     `json:"item_id"`
	PlacementID *uuid.UUID    `json:"placement_id"`
	Status      string        `json:"status"`
	DeliveredAt *time.Time    `json:"delivered_at,omitempty"`
	SeenAt      *time.Time    `json:"seen_at,omitempty"`
	Reaction    *string       `json:"reaction"`
	Replies     []store.Reply `json:"replies"`
}

type feedFrameReceiptJSON struct {
	SubscriptionID     uuid.UUID             `json:"subscription_id"`
	FrameID            uuid.UUID             `json:"frame_id"`
	FrameName          string                `json:"frame_name"`
	FrameLocation      *string               `json:"frame_location,omitempty"`
	Online             bool                  `json:"online"`
	LastSeenAt         *time.Time            `json:"last_seen_at,omitempty"`
	SubscriptionStatus string                `json:"subscription_status"`
	Items              []feedItemReceiptJSON `json:"items"`
}

type feedPostReceiptsJSON struct {
	PostID  uuid.UUID              `json:"post_id"`
	Summary feedReceiptSummaryJSON `json:"summary"`
	Frames  []feedFrameReceiptJSON `json:"frames"`
}

type sendFeedJSON struct {
	FeedID   uuid.UUID               `json:"feed_id"`
	FeedName string                  `json:"feed_name"`
	PostID   uuid.UUID               `json:"post_id"`
	Removed  bool                    `json:"removed"`
	Summary  *feedReceiptSummaryJSON `json:"summary,omitempty"`
}

// ---------------------------------------------------------------- receipts

// deliveryStatus is one item on one subscribed frame. Hidden and trashed are
// not revealed; who moderated never is.
func deliveryStatus(r *store.DeliveryRow) string {
	switch {
	case r.State == "declined" || r.PlacementID == nil || r.PlacementRemoved:
		return "removed"
	case r.SubStatus == "paused":
		return "suspended"
	case r.PendingModeration != nil && *r.PendingModeration:
		return "awaiting_approval"
	case r.Status != nil:
		return *r.Status
	}
	return "pending"
}

// postReceipts aggregates delivery rows per post.
type postReceipts struct {
	summary   feedReceiptSummaryJSON
	frames    []feedFrameReceiptJSON
	reactions []feedReactionJSON
}

func (s *Server) receiptsOf(ctx context.Context, postIDs []uuid.UUID) (map[uuid.UUID]*postReceipts, error) {
	out := map[uuid.UUID]*postReceipts{}
	if len(postIDs) == 0 {
		return out, nil
	}
	rows, err := s.st.DeliveryRows(ctx, s.st.Q(), postIDs)
	if err != nil {
		return nil, err
	}
	for i := range rows {
		r := &rows[i]
		pr := out[r.PostID]
		if pr == nil {
			pr = &postReceipts{frames: []feedFrameReceiptJSON{}, reactions: []feedReactionJSON{}}
			out[r.PostID] = pr
		}
		if n := len(pr.frames); n == 0 || pr.frames[n-1].SubscriptionID != r.SubscriptionID {
			pr.frames = append(pr.frames, feedFrameReceiptJSON{
				SubscriptionID: r.SubscriptionID, FrameID: r.FrameID, FrameName: r.FrameName, FrameLocation: r.FrameLocation,
				Online: s.hub.FrameOnline(r.FrameID), LastSeenAt: r.LastSeenAt, SubscriptionStatus: r.SubStatus,
				Items: []feedItemReceiptJSON{},
			})
		}
		fr := &pr.frames[len(pr.frames)-1]
		st := deliveryStatus(r)
		replies := r.Replies
		if replies == nil {
			replies = []store.Reply{}
		}
		it := feedItemReceiptJSON{ItemID: r.ItemID, PlacementID: r.PlacementID, Status: st, Replies: replies}
		if st != "removed" {
			it.DeliveredAt, it.SeenAt, it.Reaction = r.DeliveredAt, r.SeenAt, r.Reaction
		}
		fr.Items = append(fr.Items, it)
		if st != "removed" && r.PlacementID != nil && (r.Reaction != nil || len(r.Replies) > 0) {
			re := feedReactionJSON{FrameID: r.FrameID, FrameName: r.FrameName, ItemID: r.ItemID, PlacementID: *r.PlacementID,
				Reaction: r.Reaction, Replies: []string{}}
			if r.ReactionAt != nil {
				re.At = *r.ReactionAt
			}
			for _, rp := range r.Replies {
				re.Replies = append(re.Replies, rp.Preset)
				if rp.At.After(re.At) {
					re.At = rp.At
				}
			}
			pr.reactions = append(pr.reactions, re)
		}
	}
	for _, pr := range out {
		for _, fr := range pr.frames {
			if fr.SubscriptionStatus == "paused" {
				pr.summary.Paused++
				continue
			}
			removed, awaiting, considered, delivered, seen := 0, false, 0, 0, 0
			for _, it := range fr.Items {
				switch it.Status {
				case "removed":
					removed++
				case "awaiting_approval":
					awaiting = true
					considered++
				case "failed":
				case "delivered":
					considered++
					delivered++
				case "seen":
					considered++
					delivered++
					seen++
				default:
					considered++
				}
			}
			if removed == len(fr.Items) {
				pr.summary.Removed++
				continue
			}
			pr.summary.Frames++
			if awaiting {
				pr.summary.AwaitingApproval++
			}
			if considered > 0 && delivered == considered {
				pr.summary.Delivered++
			}
			if considered > 0 && seen == considered {
				pr.summary.Seen++
			}
		}
		sort.SliceStable(pr.reactions, func(i, j int) bool { return pr.reactions[i].At.After(pr.reactions[j].At) })
	}
	return out, nil
}

// canSeeReceipts: own posts, feed owner/admins, or everyone with members_see_reactions.
func canSeeReceipts(ctx context.Context, v *store.FeedView, p *store.FeedPost) bool {
	return v.IsAdmin() || v.MembersSeeReactions || (p.PosterUserID != nil && *p.PosterUserID == me(ctx).ID)
}

// renderPosts builds FeedPost objects in the order of posts, as seen by the caller.
func (s *Server) renderPosts(ctx context.Context, v *store.FeedView, posts []store.FeedPost) ([]feedPostJSON, error) {
	out := make([]feedPostJSON, len(posts))
	if len(posts) == 0 {
		return out, nil
	}
	ids := make([]uuid.UUID, len(posts))
	idx := map[uuid.UUID]int{}
	var visible []uuid.UUID
	for i := range posts {
		p := &posts[i]
		ids[i], idx[p.ID] = p.ID, i
		out[i] = feedPostJSON{ID: p.ID, FeedID: p.FeedID, CreatedAt: p.CreatedAt, Items: []gen.Item{}, Via: viaJSON(p.Via, p.ViaSourceID),
			Poster: feedPosterJSON{DisplayName: p.PosterName, AvatarURL: s.avatarURL(p.PosterAvatarKey)}}
		if p.PosterUserID != nil {
			out[i].Poster.UserID = *p.PosterUserID
			if *p.PosterUserID == me(ctx).ID {
				out[i].SendID = p.SendID
			}
		}
		if canSeeReceipts(ctx, v, p) {
			visible = append(visible, p.ID)
		}
	}
	items, err := s.st.FeedPostItemRows(ctx, s.st.Q(), ids)
	if err != nil {
		return nil, err
	}
	for i := range items {
		k := idx[items[i].PostID]
		out[k].Items = append(out[k].Items, s.itemJSON(&items[i].ItemRow))
	}
	receipts, err := s.receiptsOf(ctx, visible)
	if err != nil {
		return nil, err
	}
	for _, id := range visible {
		k := idx[id]
		sum, reactions := feedReceiptSummaryJSON{}, []feedReactionJSON{}
		if pr := receipts[id]; pr != nil {
			sum, reactions = pr.summary, pr.reactions
		}
		if len(reactions) > 50 {
			reactions = reactions[:50]
		}
		out[k].Receipts, out[k].Reactions = &sum, &reactions
	}
	return out, nil
}

// ---------------------------------------------------------------- handlers

func (s *Server) ListFeedPosts(ctx context.Context, req gen.ListFeedPostsRequestObject) (gen.ListFeedPostsResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
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
	posts, err := s.st.ListFeedPosts(ctx, req.FeedId, req.Params.PosterId, before, limit+1)
	if err != nil {
		return nil, err
	}
	var next *string
	if len(posts) > limit {
		posts = posts[:limit]
		c := posts[limit-1].ID.String()
		next = &c
	}
	out, err := s.renderPosts(ctx, v, posts)
	if err != nil {
		return nil, err
	}
	return ok(map[string]any{"posts": out, "next_cursor": next}), nil
}

func (s *Server) feedPost(ctx context.Context, feedID, postID uuid.UUID) (*store.FeedView, *store.FeedPost, error) {
	v, err := s.feedView(ctx, s.st.Q(), feedID)
	if err != nil {
		return nil, nil, err
	}
	p, err := s.st.GetFeedPost(ctx, s.st.Q(), feedID, postID)
	if err != nil {
		return nil, nil, notFound(err, "post")
	}
	return v, p, nil
}

func (s *Server) GetFeedPost(ctx context.Context, req gen.GetFeedPostRequestObject) (gen.GetFeedPostResponseObject, error) {
	v, p, err := s.feedPost(ctx, req.FeedId, req.PostId)
	if err != nil {
		return nil, err
	}
	out, err := s.renderPosts(ctx, v, []store.FeedPost{*p})
	if err != nil {
		return nil, err
	}
	return ok(out[0]), nil
}

func (s *Server) GetFeedPostReceipts(ctx context.Context, req gen.GetFeedPostReceiptsRequestObject) (gen.GetFeedPostReceiptsResponseObject, error) {
	v, p, err := s.feedPost(ctx, req.FeedId, req.PostId)
	if err != nil {
		return nil, err
	}
	if !canSeeReceipts(ctx, v, p) {
		return nil, problem.New(problem.MissingPermission, "only the poster and the feed's owner and admins see receipts")
	}
	rs, err := s.receiptsOf(ctx, []uuid.UUID{p.ID})
	if err != nil {
		return nil, err
	}
	out := feedPostReceiptsJSON{PostID: p.ID, Frames: []feedFrameReceiptJSON{}}
	if pr := rs[p.ID]; pr != nil {
		out.Summary, out.Frames = pr.summary, pr.frames
	}
	return ok(out), nil
}

func (s *Server) RemoveFeedPost(ctx context.Context, req gen.RemoveFeedPostRequestObject) (gen.RemoveFeedPostResponseObject, error) {
	if err := s.removePostItems(ctx, req.FeedId, req.PostId, nil); err != nil {
		return nil, err
	}
	return noContent(), nil
}

func (s *Server) RemoveFeedPostItem(ctx context.Context, req gen.RemoveFeedPostItemRequestObject) (gen.RemoveFeedPostItemResponseObject, error) {
	if err := s.removePostItems(ctx, req.FeedId, req.PostId, []uuid.UUID{req.ItemId}); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// removePostItems unsends a post or some of its items: the poster or a feed
// owner/admin. The send and its direct placements are untouched.
func (s *Server) removePostItems(ctx context.Context, feedID, postID uuid.UUID, items []uuid.UUID) error {
	v, p, err := s.feedPost(ctx, feedID, postID)
	if err != nil {
		return err
	}
	own := p.PosterUserID != nil && *p.PosterUserID == me(ctx).ID
	if !own && !v.IsAdmin() {
		return problem.New(problem.Forbidden, "only the poster or the feed's owner and admins can remove this post")
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		removed, subs, err := tx.RemovePostItems(ctx, postID, items, &me(ctx).ID)
		if err != nil {
			return err
		}
		if !removed && items != nil {
			return problem.New(problem.NotFound, "item not found")
		}
		return enqueueSweeps(ctx, tx, subs)
	})
	if err != nil {
		return err
	}
	if p.SendID != nil && p.PosterUserID != nil {
		s.publishSendUpdates(ctx, map[uuid.UUID]uuid.UUID{*p.SendID: *p.PosterUserID}, "")
	}
	return nil
}

// ---------------------------------------------------------------- sends

// createPosts makes one post per feed with the send's items, in the send's
// transaction; the placements follow in the fanout job. Feeds that already
// have a live post of the send are skipped (forwards).
func createPosts(ctx context.Context, st *store.Store, tx *store.Tx, sendID, poster uuid.UUID, feeds []uuid.UUID, itemIDs []uuid.UUID) error {
	for _, fid := range feeds {
		if _, err := st.LivePostOfSend(ctx, tx, fid, sendID); err == nil {
			continue
		} else if err != store.ErrNotFound {
			return err
		}
		postID := store.NewID()
		if err := tx.CreateFeedPost(ctx, postID, fid, &sendID, poster, itemIDs); err != nil {
			return err
		}
		if err := tx.Enqueue(ctx, store.JobFeedFanout, store.PostJob{PostID: postID}); err != nil {
			return err
		}
	}
	return nil
}

// sendFeeds renders Send.feeds[] for sends (send -> posts with receipts).
func (s *Server) sendFeeds(ctx context.Context, sendIDs []uuid.UUID) (map[uuid.UUID][]sendFeedJSON, error) {
	out := map[uuid.UUID][]sendFeedJSON{}
	posts, err := s.st.PostsOfSends(ctx, s.st.Q(), sendIDs)
	if err != nil || len(posts) == 0 {
		return out, err
	}
	var live []uuid.UUID
	for _, p := range posts {
		if p.RemovedAt == nil {
			live = append(live, p.ID)
		}
	}
	receipts, err := s.receiptsOf(ctx, live)
	if err != nil {
		return nil, err
	}
	for _, p := range posts {
		if p.SendID == nil {
			continue
		}
		sf := sendFeedJSON{FeedID: p.FeedID, FeedName: p.FeedName, PostID: p.ID, Removed: p.RemovedAt != nil}
		if !sf.Removed {
			sum := feedReceiptSummaryJSON{}
			if pr := receipts[p.ID]; pr != nil {
				sum = pr.summary
			}
			sf.Summary = &sum
		}
		out[*p.SendID] = append(out[*p.SendID], sf)
	}
	return out, nil
}
