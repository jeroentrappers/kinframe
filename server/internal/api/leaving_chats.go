package api

// Contact that stops (docs/spec/leaving.md §4.8, README §5.21): a direct chat between two people who no longer
// share a live family or frame is read-only for both (readOnly in chat.go asks shareLiveScope); this says why.

import (
	"context"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// readOnlyReason is Conversation.read_only_reason for a read-only conversation. A block by the other person gives
// no reason, as before (nobody learns about blocks).
func (s *Server) readOnlyReason(ctx context.Context, userID uuid.UUID, c *store.Conversation, members []store.ConversationMember) *gen.ConversationReadOnlyReason {
	if c.ArchivedAt != nil {
		return ptr(gen.ConversationReadOnlyReasonArchived)
	}
	if c.Kind != "direct" {
		return nil
	}
	for _, m := range members {
		if m.UserID == userID {
			continue
		}
		if m.Deleted {
			return ptr(gen.ConversationReadOnlyReasonAccountGone)
		}
		if blocked, err := s.st.HasBlocked(ctx, s.st.Q(), m.UserID, userID); err != nil || blocked {
			return nil
		}
		return ptr(gen.ConversationReadOnlyReasonNoLongerContacts)
	}
	return ptr(gen.ConversationReadOnlyReasonAccountGone)
}

// publishUnsentTakenAway tells the frames' library viewers that an unsend purged taken-away placements.
func (s *Server) publishUnsentTakenAway(ctx context.Context, frames []store.RestoredFrame) {
	for _, f := range frames {
		s.publishPlacementsUpdated(ctx, f.FrameID, f.PlacementIDs, "purged", nil, nil)
	}
}
