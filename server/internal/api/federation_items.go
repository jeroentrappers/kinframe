package api

import (
	"context"
	"encoding/json"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Rendering of copies from other servers on B (README §5.22): Item.remote_sender and Placement.origin_remote,
// filled from the item and placement queries (store.ItemRow.RemoteSender, store.PlacementRow.OriginRemote).

func remoteSenderJSON(it *store.ItemRow) *gen.ItemRemoteSender {
	if it.RemoteSenderID == nil || len(it.RemoteSender) == 0 {
		return nil
	}
	var out gen.ItemRemoteSender
	if json.Unmarshal(it.RemoteSender, &out) != nil {
		return nil
	}
	return &out
}

// remoteReceipts adds the frames on other servers to post receipts (A's side): an item is delivered once the
// frame's server pulled after the post was made, seen and reacted to as the frame reported. frame_id and
// subscription_id are the remote subscription's id; the name is "Oma's frame (kinframe.appmire.be)".
func (s *Server) remoteReceipts(ctx context.Context, postIDs []uuid.UUID, out map[uuid.UUID]*postReceipts) error {
	rows, err := s.st.RemoteReceipts(ctx, postIDs)
	if err != nil || len(rows) == 0 {
		return err
	}
	for _, r := range rows {
		pr := out[r.PostID]
		if pr == nil {
			pr = &postReceipts{frames: []feedFrameReceiptJSON{}, reactions: []feedReactionJSON{}}
			out[r.PostID] = pr
		}
		if n := len(pr.frames); n == 0 || pr.frames[n-1].SubscriptionID != r.SubscriberID {
			pr.frames = append(pr.frames, feedFrameReceiptJSON{SubscriptionID: r.SubscriberID, FrameID: r.SubscriberID,
				FrameName: remoteLabel(r.FrameName, r.ServerName), SubscriptionStatus: "active", Items: []feedItemReceiptJSON{}})
		}
		fr := &pr.frames[len(pr.frames)-1]
		it := feedItemReceiptJSON{ItemID: r.ItemID, Status: "pending", Reaction: r.Reaction, Replies: []store.Reply{}}
		switch {
		case r.SeenAt != nil:
			it.Status, it.SeenAt, it.DeliveredAt = "seen", r.SeenAt, r.SeenAt
		case r.Pulled:
			it.Status = "delivered"
		}
		fr.Items = append(fr.Items, it)
		if r.Reaction != nil {
			re := feedReactionJSON{FrameID: r.SubscriberID, FrameName: remoteLabel(r.FrameName, r.ServerName), ItemID: r.ItemID,
				Reaction: r.Reaction, Replies: []string{}}
			if r.ReactionAt != nil {
				re.At = *r.ReactionAt
			}
			pr.reactions = append(pr.reactions, re)
		}
	}
	return nil
}

func placementRemoteJSON(p *store.PlacementRow) *gen.PlacementRemote {
	if len(p.OriginRemote) == 0 || string(p.OriginRemote) == "null" {
		return nil
	}
	var out gen.PlacementRemote
	if json.Unmarshal(p.OriginRemote, &out) != nil {
		return nil
	}
	return &out
}
