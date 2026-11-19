package store

// Leaving and the per-send unsend (README §5.21): "Remove from all frames and families" stays immediate, also for
// photos of the send that wait in a frame's trash after a taking: they are purged, and no keep request can bring
// them back.

import (
	"context"

	"github.com/google/uuid"
)

// PurgeTakenAwayOfSend ends the 30-day wait of a send's taken-away placements and post items. Returns the purged
// placements per frame.
func (tx *Tx) PurgeTakenAwayOfSend(ctx context.Context, sendID uuid.UUID) ([]RestoredFrame, error) {
	rows, err := many[framePlacement](tx.Query(ctx, `UPDATE placements SET taken_away_at = NULL, taken_away_until = NULL
		WHERE taken_away_at IS NOT NULL AND (send_id = $1 OR item_id IN (SELECT item_id FROM send_items WHERE send_id = $1))
		RETURNING id, frame_id, item_id`, sendID))
	if err != nil {
		return nil, err
	}
	ids := make([]uuid.UUID, len(rows))
	for i, r := range rows {
		ids[i] = r.ID
	}
	if _, err := tx.Exec(ctx, `DELETE FROM taking_deliveries WHERE placement_id = ANY($1)
		OR item_id IN (SELECT item_id FROM send_items WHERE send_id = $2)`, ids, sendID); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE feed_post_items SET taking_id = NULL WHERE taking_id IS NOT NULL AND removed_at IS NOT NULL
		AND item_id IN (SELECT item_id FROM send_items WHERE send_id = $1)`, sendID); err != nil {
		return nil, err
	}
	var out []RestoredFrame
	for frameID, pids := range byFrame(rows) {
		out = append(out, RestoredFrame{FrameID: frameID, PlacementIDs: pids})
	}
	return out, nil
}
