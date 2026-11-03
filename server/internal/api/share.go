package api

import (
	"context"
	"sort"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// ---------------------------------------------------------------- placing existing items

// placeItem is an item to put on frames. Source is the placement it was
// shared from (zero for sends and forwards); it only labels skips.
type placeItem struct {
	ItemID  uuid.UUID
	MediaID uuid.UUID
	// MotionMediaID is the clip of a Live/Motion Photo, which needs a rendition for the target too.
	MotionMediaID *uuid.UUID
	SendID        *uuid.UUID
	Source        uuid.UUID
}

type placed struct {
	PlacementID uuid.UUID
	FrameID     uuid.UUID
	ItemID      uuid.UUID
	MediaID     uuid.UUID
	MotionID    *uuid.UUID
	SendID      *uuid.UUID
}

type shareSkip struct {
	PlacementID         uuid.UUID `json:"placement_id"`
	FrameID             uuid.UUID `json:"frame_id"`
	Reason              string    `json:"reason"`
	ExistingPlacementID uuid.UUID `json:"existing_placement_id"`
}

// placeOnFrames creates placements of existing items on frames: the one path
// for sends, forwards and shares. A frame that already holds an item is
// skipped (reported). The target's moderation applies unless the caller
// manages it. sharedBy marks placements shared from another frame's library.
// With dryRun nothing is written, only skips are reported.
//
// No media is copied: placements reference the item, and callers enqueue
// JobEnsureRenditions, which only renders sizes the media lacks.
func (s *Server) placeOnFrames(ctx context.Context, tx *store.Tx, items []placeItem, frames []uuid.UUID,
	members map[uuid.UUID]*store.Membership, sharedBy *uuid.UUID, dryRun bool) ([]placed, []shareSkip, error) {
	var created []placed
	skipped := []shareSkip{}
	for _, fid := range frames {
		var f *store.Frame
		var err error
		if dryRun {
			f, err = s.st.GetFrame(ctx, tx, fid)
		} else {
			f, err = tx.LockFrame(ctx, fid)
		}
		if err != nil {
			return nil, nil, notFound(err, "frame")
		}
		moderated := f.Moderation && !members[fid].IsManager()
		for _, it := range items {
			var existing uuid.UUID
			err := tx.QueryRow(ctx, `SELECT id FROM placements WHERE frame_id = $1 AND item_id = $2 AND removed_at IS NULL`, fid, it.ItemID).Scan(&existing)
			if err == nil {
				skipped = append(skipped, shareSkip{PlacementID: it.Source, FrameID: fid, Reason: "already_on_frame", ExistingPlacementID: existing})
				continue
			}
			if !db.NotFound(err) {
				return nil, nil, err
			}
			if dryRun {
				continue
			}
			p := store.Placement{ID: store.NewID(), FrameID: fid, ItemID: it.ItemID, SendID: it.SendID, Status: "pending",
				PendingModeration: moderated, SharedByUserID: sharedBy}
			if err := tx.CreatePlacement(ctx, p); err != nil {
				return nil, nil, err
			}
			created = append(created, placed{PlacementID: p.ID, FrameID: fid, ItemID: it.ItemID, MediaID: it.MediaID, MotionID: it.MotionMediaID, SendID: it.SendID})
		}
	}
	return created, skipped, nil
}

// ensureRenditionsFor enqueues one rendition check per media that got new placements.
func ensureRenditionsFor(ctx context.Context, tx *store.Tx, created []placed) error {
	done := map[uuid.UUID]bool{}
	for _, p := range created {
		ids := []uuid.UUID{p.MediaID}
		if p.MotionID != nil {
			ids = append(ids, *p.MotionID)
		}
		for _, id := range ids {
			if done[id] {
				continue
			}
			done[id] = true
			if err := tx.Enqueue(ctx, store.JobEnsureRenditions, map[string]any{"media_id": id}); err != nil {
				return err
			}
		}
	}
	return nil
}

// ---------------------------------------------------------------- share

type shareRef struct {
	Placements []uuid.UUID `json:"placements"`
	Skipped    []shareSkip `json:"skipped"`
}

func (s *Server) SharePlacements(ctx context.Context, req gen.SharePlacementsRequestObject) (gen.SharePlacementsResponseObject, error) {
	u := me(ctx)
	b := req.Body
	dryRun := b.DryRun != nil && *b.DryRun
	key := ""
	if req.Params.IdempotencyKey != nil && !dryRun {
		key = *req.Params.IdempotencyKey
		var prev shareRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "share", key, &prev); err != nil {
			return nil, err
		} else if found {
			return s.renderShare(ctx, prev)
		}
	}
	if limit := s.Limits(ctx).MaxItemsPerSend; len(b.PlacementIds) > limit {
		return nil, problem.New(problem.TooManyItems, "at most %d items per send", limit)
	}
	// Source: live placements on this frame the caller may see.
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	rows, err := s.st.PlacementRowsByIDs(ctx, b.PlacementIds)
	if err != nil {
		return nil, err
	}
	var items []placeItem
	senders := map[uuid.UUID]*uuid.UUID{} // item -> original sender
	for _, id := range b.PlacementIds {
		row, found := rows[id]
		if !found || row.FrameID != req.FrameId || row.RemovedAt != nil || row.TrashedAt != nil || row.PendingModeration || row.MediaStatus == "failed" {
			return nil, problem.New(problem.NotFound, "placement not found").WithField("placement_ids", string(problem.NotFound))
		}
		if !canShareFrom(m, &row) {
			if !m.Effective().SeeLibrary {
				return nil, problem.New(problem.NotFound, "placement not found").WithField("placement_ids", string(problem.NotFound))
			}
			return nil, problem.New(problem.MissingPermission, "requires the %s permission", "see_library")
		}
		if _, dup := senders[row.ItemRow.ItemID]; dup {
			continue
		}
		senders[row.ItemRow.ItemID] = row.SenderUserID
		items = append(items, placeItem{ItemID: row.ItemRow.ItemID, MediaID: row.MediaID, MotionMediaID: row.MotionMediaID, SendID: row.SendID, Source: id})
	}
	// Targets: frames the caller may send to.
	frames, members, err := s.sendTargets(ctx, b.FrameIds)
	if err != nil {
		return nil, err
	}
	var created []placed
	var skipped []shareSkip
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		created, skipped, err = s.placeOnFrames(ctx, tx, items, frames, members, &u.ID, dryRun)
		if err != nil || dryRun {
			return err
		}
		if err := ensureRenditionsFor(ctx, tx, created); err != nil {
			return err
		}
		if err := tx.TouchLastSent(ctx, frames, u.ID); err != nil {
			return err
		}
		if key != "" {
			return tx.SaveIdempotent(ctx, u.ID, "share", key, shareResult(created, skipped))
		}
		return nil
	})
	if db.IsUniqueViolation(err, "idempotency_keys_pkey") {
		var prev shareRef
		if found, _ := store.IdempotentResult(ctx, s.st.Q(), u.ID, "share", key, &prev); found {
			return s.renderShare(ctx, prev)
		}
	}
	if err != nil {
		return nil, err
	}
	// The original senders' histories now list the new frames.
	sends := map[uuid.UUID]uuid.UUID{}
	for _, p := range created {
		if sender := senders[p.ItemID]; p.SendID != nil && sender != nil {
			sends[*p.SendID] = *sender
		}
	}
	s.publishSendUpdates(ctx, sends, "")
	return s.renderShare(ctx, shareResult(created, skipped))
}

// canShareFrom: see_library on the source frame, or the caller sent the item
// or shared this placement there.
func canShareFrom(m *store.Membership, row *store.PlacementRow) bool {
	return m.Effective().SeeLibrary ||
		(row.SenderUserID != nil && *row.SenderUserID == m.UserID) ||
		(row.SharedByUserID != nil && *row.SharedByUserID == m.UserID)
}

func shareResult(created []placed, skipped []shareSkip) shareRef {
	out := shareRef{Placements: make([]uuid.UUID, len(created)), Skipped: skipped}
	for i, p := range created {
		out.Placements[i] = p.PlacementID
	}
	if out.Skipped == nil {
		out.Skipped = []shareSkip{}
	}
	return out
}

func (s *Server) renderShare(ctx context.Context, ref shareRef) (gen.SharePlacementsResponseObject, error) {
	out := []gen.Placement{}
	if len(ref.Placements) > 0 {
		rows, err := s.st.PlacementRowsByIDs(ctx, ref.Placements)
		if err != nil {
			return nil, err
		}
		for _, id := range ref.Placements {
			if row, ok := rows[id]; ok {
				out = append(out, s.placementJSON(&row))
			}
		}
	}
	skipped := ref.Skipped
	if skipped == nil {
		skipped = []shareSkip{}
	}
	return ok(map[string]any{"placements": out, "skipped": skipped}), nil
}

// sharedDeliveredPushes tells sharers when everything they shared to the
// frame has arrived there (once per sharer and frame per ack).
func (s *Server) sharedDeliveredPushes(ctx context.Context, f *store.Frame, changes []store.StatusChange) {
	sharers := map[uuid.UUID]bool{}
	for _, c := range changes {
		if c.NewlyDelivered && c.SharedByID != nil {
			sharers[*c.SharedByID] = true
		}
	}
	ids := make([]uuid.UUID, 0, len(sharers))
	for id := range sharers {
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i].String() < ids[j].String() })
	for _, sharer := range ids {
		done, err := s.st.SharedDeliveredOn(ctx, sharer, f.ID)
		if err != nil {
			s.log.Warn("shared delivered check", "frame", f.ID, "err", err)
			continue
		}
		if !done {
			continue
		}
		s.notify.User(sharer, notify.Event{"type": "share.delivered", "frame_id": f.ID, "frame_name": f.Name},
			&notify.Push{Key: "delivered", Args: map[string]string{"frame": f.Name}, IDs: map[string]string{"frame_id": f.ID.String()}})
	}
}
