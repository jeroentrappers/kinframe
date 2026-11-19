package store

// Leaving a family: who hears about takings and keep requests (README §5.21, §6.2).

import (
	"context"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// DirectPlacementIDs lists a person's live direct placements (sent or shared by them, held by no family delivery)
// on these frames, or on every frame with all.
func (s *Store) DirectPlacementIDs(ctx context.Context, q db.DBTX, userID uuid.UUID, all bool, frameIDs []uuid.UUID) ([]uuid.UUID, error) {
	return uuids(q.Query(ctx, directPlacementSQL, userID, all, idsOrEmpty(frameIDs)))
}

// FrameLibraryViewers lists the members who see a frame's library (owner, admins, see_library, manage_library).
func (s *Store) FrameLibraryViewers(ctx context.Context, frameID uuid.UUID) ([]uuid.UUID, error) {
	return uuids(s.Pool.Query(ctx, `SELECT user_id FROM memberships WHERE frame_id = $1
		AND (role IN ('owner', 'admin') OR (permissions->>'see_library')::boolean OR (permissions->>'manage_library')::boolean)
		ORDER BY user_id`, frameID))
}

// FrameLibraryManagers lists the members who manage a frame's library (owner, admins, manage_library): they see
// the frame's "Taken away" groups and may ask to keep.
func (s *Store) FrameLibraryManagers(ctx context.Context, frameID uuid.UUID) ([]uuid.UUID, error) {
	return uuids(s.Pool.Query(ctx, `SELECT user_id FROM memberships WHERE frame_id = $1
		AND (role IN ('owner', 'admin') OR (permissions->>'manage_library')::boolean) ORDER BY user_id`, frameID))
}
