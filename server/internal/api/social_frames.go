package api

import (
	"context"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Frame entries (README §5.18): a frame's reaction or quick reply on a placement shows in the thread of every
// post the placement belongs to (its send's frame post on that frame, and feed posts delivered to the frame).
// One reaction entry per frame and post, following the frame's newest reaction on the post's items; one entry
// per quick reply. They never push.

// postsOfPlacement lists the live posts a placement belongs to.
func (s *Server) postsOfPlacement(ctx context.Context, placementID uuid.UUID) []uuid.UUID {
	var out []uuid.UUID
	if fp, err := s.st.FramePostOfPlacement(ctx, s.st.Q(), placementID); err != nil {
		s.log.Warn("frame post of placement", "err", err)
	} else if fp != nil {
		out = append(out, *fp)
	}
	pp, err := s.st.PostsOfPlacements(ctx, s.st.Q(), []uuid.UUID{placementID})
	if err != nil {
		s.log.Warn("posts of placement", "err", err)
	}
	for _, p := range pp {
		out = appendUnique(out, p.PostID)
	}
	return out
}

// frameReactionEntries brings the frame's reaction entries in line after it reacted (or took a reaction back).
func (s *Server) frameReactionEntries(ctx context.Context, f *store.Frame, pl *store.PlacementRow) {
	for _, postID := range s.postsOfPlacement(ctx, pl.Placement.ID) {
		p, err := s.st.SocialPost(ctx, s.st.Q(), uuid.Nil, postID)
		if err != nil || p.Gone {
			continue
		}
		latest, err := s.st.LatestFrameReaction(ctx, s.st.Q(), postID, f.ID)
		if err != nil {
			s.log.Warn("frame reaction", "err", err)
			continue
		}
		e := store.FrameEntry{PostID: postID, FrameID: f.ID, FrameName: f.Name}
		var reaction *string
		if latest != nil {
			reaction = &latest.Reaction
			e.ItemID = latest.ItemID
			if e.PlacementID, err = s.st.PlacementOnFrame(ctx, s.st.Q(), f.ID, latest.ItemID); err != nil {
				continue
			}
		}
		var id uuid.UUID
		var change string
		err = s.st.InTx(ctx, func(tx *store.Tx) error {
			if err := tx.EnsureThread(ctx, p); err != nil {
				return err
			}
			if _, err := tx.LockThread(ctx, postID); err != nil {
				return err
			}
			var err error
			id, change, err = tx.SetFrameReactionEntry(ctx, e, reaction)
			return err
		})
		if err != nil {
			s.log.Warn("frame reaction entry", "post", postID, "err", err)
			continue
		}
		switch change {
		case "created":
			s.publishComment(ctx, postID, id, "comment.created", nil)
		case "updated":
			s.publishComment(ctx, postID, id, "comment.updated", nil)
		case "deleted":
			s.publishCommentDeleted(ctx, postID, []uuid.UUID{id}, nil)
		}
		s.publishPostUpdated(ctx, postID) // the reaction row
	}
}

// frameReplyEntries adds a quick reply to the threads of the placement's posts.
func (s *Server) frameReplyEntries(ctx context.Context, f *store.Frame, pl *store.PlacementRow, preset string) {
	for _, postID := range s.postsOfPlacement(ctx, pl.Placement.ID) {
		p, err := s.st.SocialPost(ctx, s.st.Q(), uuid.Nil, postID)
		if err != nil || p.Gone {
			continue
		}
		var id uuid.UUID
		err = s.st.InTx(ctx, func(tx *store.Tx) error {
			if err := tx.EnsureThread(ctx, p); err != nil {
				return err
			}
			var err error
			id, err = tx.AddFrameReplyEntry(ctx, store.FrameEntry{PostID: postID, FrameID: f.ID, FrameName: f.Name,
				PlacementID: pl.Placement.ID, ItemID: pl.ItemRow.ItemID}, preset)
			return err
		})
		if err != nil {
			s.log.Warn("frame reply entry", "post", postID, "err", err)
			continue
		}
		s.publishComment(ctx, postID, id, "comment.created", nil)
	}
}
