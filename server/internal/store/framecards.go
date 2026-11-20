package store

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Frame cards (README §5.5, "Frame cards"): what FrameSummary shows besides the frame itself.

// FrameCardQuery asks for one frame's card. DayStart is midnight of the frame's local day; Reported is the
// placement its screen reported (uuid.Nil when none).
type FrameCardQuery struct {
	FrameID  uuid.UUID
	DayStart time.Time
	Reported uuid.UUID
}

// FrameCard is one frame's now showing placement (nil fields when there is none) and today's reactions.
type FrameCard struct {
	FrameID           uuid.UUID  `db:"frame_id"`
	PlacementID       *uuid.UUID `db:"placement_id"`
	ItemID            *uuid.UUID `db:"item_id"`
	ThumbKey          *string    `db:"thumb_key"`
	DisplayKey        *string    `db:"display_key"`
	ReceivedAt        *time.Time `db:"received_at"`
	Reported          bool       `db:"reported"` // the placement is the reported one (still visible on the frame)
	ReactionCount     int        `db:"reaction_count"`
	ReactionItemCount int        `db:"reaction_item_count"`
}

// FrameCards returns the cards of these frames in one query, keyed by frame. The now showing placement is the
// reported one while it is still visible, else the newest visible placement the frame has (delivered or seen,
// not suspended). Reactions count the frame's reactions and quick replies since DayStart on live placements.
func (s *Store) FrameCards(ctx context.Context, q db.DBTX, in []FrameCardQuery) (map[uuid.UUID]FrameCard, error) {
	out := make(map[uuid.UUID]FrameCard, len(in))
	if len(in) == 0 {
		return out, nil
	}
	ids, starts, reported := make([]uuid.UUID, len(in)), make([]time.Time, len(in)), make([]uuid.UUID, len(in))
	for i, c := range in {
		ids[i], starts[i], reported[i] = c.FrameID, c.DayStart, c.Reported
	}
	rows, err := many[FrameCard](q.Query(ctx, `
		SELECT x.frame_id, n.placement_id, n.item_id, n.thumb_key, n.display_key, n.received_at,
			COALESCE(n.reported, false) AS reported, r.reaction_count, r.reaction_item_count
		FROM unnest($1::uuid[], $2::timestamptz[], $3::uuid[]) AS x(frame_id, day_start, reported)
		LEFT JOIN LATERAL (
			SELECT p.id AS placement_id, p.item_id, COALESCE(i.thumb512_key, i.thumb256_key) AS thumb_key, i.display_key,
				p.received_at, p.id = x.reported AS reported
			FROM placements p JOIN items i ON i.id = p.item_id
			WHERE p.frame_id = x.frame_id AND p.removed_at IS NULL AND NOT p.hidden AND p.trashed_at IS NULL
				AND NOT p.pending_moderation
				AND (p.id = x.reported OR (NOT p.feed_suspended AND p.status IN ('delivered', 'seen')))
			ORDER BY p.id = x.reported DESC, p.received_at DESC, p.id DESC
			LIMIT 1) n ON true
		CROSS JOIN LATERAL (
			SELECT count(*)::int AS reaction_count, count(DISTINCT t.item_id)::int AS reaction_item_count FROM (
				SELECT p.item_id FROM placements p
				WHERE p.frame_id = x.frame_id AND p.removed_at IS NULL AND p.reaction IS NOT NULL AND p.reaction_at >= x.day_start
				UNION ALL
				SELECT p.item_id FROM placement_replies pr JOIN placements p ON p.id = pr.placement_id
				WHERE p.frame_id = x.frame_id AND p.removed_at IS NULL AND pr.at >= x.day_start) t) r`,
		ids, starts, reported))
	if err != nil {
		return nil, err
	}
	for _, r := range rows {
		out[r.FrameID] = r
	}
	return out, nil
}
