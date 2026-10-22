package api

import (
	"context"
	"fmt"
	"net/http"
	"sort"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// ---------------------------------------------------------------- Send JSON

type sendPlacementJSON struct {
	PlacementID uuid.UUID `json:"placement_id"`
	ItemID      uuid.UUID `json:"item_id"`
	Status      string    `json:"status"`
	Reaction    *string   `json:"reaction"`
	Replies     []string  `json:"replies"`
}

type sendFrameJSON struct {
	FrameID    uuid.UUID           `json:"frame_id"`
	FrameName  string              `json:"frame_name"`
	Online     bool                `json:"online"`
	Placements []sendPlacementJSON `json:"placements"`
}

type sendSummary struct {
	Processing int `json:"processing"`
	Pending    int `json:"pending"`
	Delivered  int `json:"delivered"`
	Seen       int `json:"seen"`
	Failed     int `json:"failed"`
}

type sendJSON struct {
	ID        uuid.UUID       `json:"id"`
	CreatedAt time.Time       `json:"created_at"`
	Items     []gen.Item      `json:"items"`
	Frames    []sendFrameJSON `json:"frames"`
	Summary   sendSummary     `json:"summary"`
}

// renderSends builds Send objects in the order of sends.
func (s *Server) renderSends(ctx context.Context, q db.DBTX, sends []store.Send) ([]sendJSON, error) {
	if len(sends) == 0 {
		return []sendJSON{}, nil
	}
	ids := make([]uuid.UUID, len(sends))
	out := make([]sendJSON, len(sends))
	idx := map[uuid.UUID]int{}
	for i, sd := range sends {
		ids[i] = sd.ID
		idx[sd.ID] = i
		out[i] = sendJSON{ID: sd.ID, CreatedAt: sd.CreatedAt, Items: []gen.Item{}, Frames: []sendFrameJSON{}}
	}
	items, err := s.st.SendItemRows(ctx, q, ids)
	if err != nil {
		return nil, err
	}
	for i := range items {
		k := idx[items[i].SendID]
		out[k].Items = append(out[k].Items, s.itemJSON(&items[i].ItemRow))
	}
	pls, err := s.st.SendPlacementRows(ctx, q, ids)
	if err != nil {
		return nil, err
	}
	frameIdx := map[[2]uuid.UUID]int{}
	for _, p := range pls {
		k := idx[p.SendID]
		sd := &out[k]
		fk := [2]uuid.UUID{p.SendID, p.FrameID}
		fi, ok := frameIdx[fk]
		if !ok {
			fi = len(sd.Frames)
			frameIdx[fk] = fi
			sd.Frames = append(sd.Frames, sendFrameJSON{FrameID: p.FrameID, FrameName: p.FrameName, Online: s.hub.FrameOnline(p.FrameID), Placements: []sendPlacementJSON{}})
		}
		status := p.Status
		switch {
		case p.Removed:
			status = "removed"
		case p.MediaStatus == "processing":
			sd.Summary.Processing++
			status = "pending"
		case p.Status == "pending" || p.Status == "ready":
			sd.Summary.Pending++
		case p.Status == "delivered":
			sd.Summary.Delivered++
		case p.Status == "seen":
			sd.Summary.Seen++
		case p.Status == "failed":
			sd.Summary.Failed++
		}
		replies := p.Replies
		if replies == nil {
			replies = []string{}
		}
		sd.Frames[fi].Placements = append(sd.Frames[fi].Placements, sendPlacementJSON{
			PlacementID: p.PlacementID, ItemID: p.ItemID, Status: status, Reaction: p.Reaction, Replies: replies,
		})
	}
	return out, nil
}

func (s *Server) renderSend(ctx context.Context, id uuid.UUID) (*sendJSON, error) {
	sd, err := s.st.GetSend(ctx, s.st.Q(), id)
	if err != nil {
		return nil, err
	}
	out, err := s.renderSends(ctx, s.st.Q(), []store.Send{*sd})
	if err != nil {
		return nil, err
	}
	return &out[0], nil
}

// ---------------------------------------------------------------- create

// minTrimMS is the shortest part of a video a sender may keep (product spec 4.3).
const minTrimMS = 3000

type preparedItem struct {
	reuse   *store.ItemRow // existing item whose media is reused
	upload  *store.Upload
	path    string
	kind    media.Kind
	mime    string
	trimS   *int
	trimE   *int
	audio   bool
	durMS   *int
	caption *string
	focalX  *float64
	focalY  *float64
	fit     *string
	hint    *time.Time
}

// sendTargets validates that the caller may send to every frame.
func (s *Server) sendTargets(ctx context.Context, frameIDs []uuid.UUID) ([]uuid.UUID, map[uuid.UUID]*store.Membership, error) {
	seen := map[uuid.UUID]bool{}
	var ids []uuid.UUID
	ms := map[uuid.UUID]*store.Membership{}
	for _, id := range frameIDs {
		if seen[id] {
			continue
		}
		seen[id] = true
		m, err := s.membership(ctx, id)
		if err != nil {
			return nil, nil, err
		}
		if !m.Effective().Send {
			return nil, nil, problem.New(problem.MissingPermission, "you can't send to frame %s", id)
		}
		ids = append(ids, id)
		ms[id] = m
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i].String() < ids[j].String() })
	return ids, ms, nil
}

func (s *Server) prepareItems(ctx context.Context, body *gen.CreateSendJSONRequestBody) ([]preparedItem, error) {
	u := me(ctx)
	limits := s.Limits(ctx)
	maxMS := limits.MaxVideoSeconds * 1000
	out := make([]preparedItem, len(body.Items))
	var first *problem.Problem
	fail := func(i int, code problem.Code, field, detail string) {
		if first == nil {
			first = problem.New(code, "%s", detail)
		}
		first.WithField(fmt.Sprintf("items.%d.%s", i, field), string(code))
	}
	usedUploads := map[string]bool{}
	for i, it := range body.Items {
		p := preparedItem{caption: it.Caption, hint: it.CapturedAt, audio: it.IncludeAudio == nil || *it.IncludeAudio}
		if p.caption != nil && *p.caption == "" {
			p.caption = nil
		}
		if it.FocalPoint != nil {
			x, y := float64(it.FocalPoint.X), float64(it.FocalPoint.Y)
			p.focalX, p.focalY = &x, &y
		}
		if it.Fit != nil {
			f := string(*it.Fit)
			p.fit = &f
		}
		if usedUploads[it.UploadId] {
			fail(i, problem.ValidationFailed, "upload_id", "the same upload appears twice")
			continue
		}
		usedUploads[it.UploadId] = true
		if itemID, err := uuid.Parse(it.UploadId); err == nil {
			if row, err := s.st.GetItemRow(ctx, s.st.Q(), itemID); err == nil && row.SenderUserID != nil && *row.SenderUserID == u.ID && row.MediaStatus != "failed" {
				if it.Trim != nil {
					fail(i, problem.ValidationFailed, "trim", "a reused item cannot be trimmed; upload the file again")
					continue
				}
				p.reuse = row
				p.kind = media.Kind(row.Kind)
				out[i] = p
				continue
			}
		}
		up, err := s.st.GetUpload(ctx, s.st.Q(), it.UploadId)
		if err != nil || up.UserID != u.ID || up.CompletedAt == nil || up.ConsumedAt != nil {
			fail(i, problem.UploadNotFound, "upload_id", "upload not found, incomplete or already used")
			continue
		}
		p.upload, p.path = up, s.uploadPath(up.ID)
		kind, mime, err := media.Sniff(p.path)
		if err != nil {
			fail(i, problem.UnsupportedMedia, "upload_id", "this file type is not supported")
			continue
		}
		p.kind, p.mime = kind, mime
		if kind == media.Video {
			info, err := s.tools.ProbeVideo(ctx, p.path)
			if err != nil {
				fail(i, problem.CorruptMedia, "upload_id", "this video could not be read")
				continue
			}
			start, end := 0, info.DurationMS
			if it.Trim != nil {
				if it.Trim.StartMs != nil {
					start = *it.Trim.StartMs
				}
				if it.Trim.EndMs != nil && *it.Trim.EndMs < end {
					end = *it.Trim.EndMs
				}
				if start >= end {
					fail(i, problem.ValidationFailed, "trim", "trim must select a non-empty part of the video")
					continue
				}
				if end-start < minTrimMS && info.DurationMS > minTrimMS {
					fail(i, problem.ValidationFailed, "trim", "a trimmed video must be at least 3 seconds long")
					continue
				}
				p.trimS, p.trimE = it.Trim.StartMs, it.Trim.EndMs
			}
			if end-start > maxMS {
				fail(i, problem.VideoTooLong, "upload_id", fmt.Sprintf("videos can be at most %d seconds", limits.MaxVideoSeconds))
				continue
			}
			d := end - start
			p.durMS = &d
		}
		out[i] = p
	}
	if first != nil {
		return nil, first
	}
	return out, nil
}

type sendRef struct {
	SendID uuid.UUID `json:"send_id"`
}

func (s *Server) CreateSend(ctx context.Context, req gen.CreateSendRequestObject) (gen.CreateSendResponseObject, error) {
	u := me(ctx)
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev sendRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "send", key, &prev); err != nil {
			return nil, err
		} else if found {
			sd, err := s.renderSend(ctx, prev.SendID)
			if err != nil {
				return nil, err
			}
			return accepted(sd), nil
		}
	}
	limits := s.Limits(ctx)
	if len(req.Body.Items) > limits.MaxItemsPerSend {
		return nil, problem.New(problem.TooManyItems, "at most %d items per send", limits.MaxItemsPerSend)
	}
	frames, members, err := s.sendTargets(ctx, req.Body.FrameIds)
	if err != nil {
		return nil, err
	}
	items, err := s.prepareItems(ctx, req.Body)
	if err != nil {
		return nil, err
	}
	sendID := store.NewID()
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.CreateSend(ctx, sendID, u.ID); err != nil {
			return err
		}
		itemIDs := make([]uuid.UUID, len(items))
		for i, p := range items {
			itemID := store.NewID()
			itemIDs[i] = itemID
			var mediaID uuid.UUID
			if p.reuse != nil {
				mediaID = p.reuse.MediaID
			} else {
				ok, err := tx.ConsumeUpload(ctx, p.upload.ID, u.ID)
				if err != nil {
					return err
				}
				if !ok {
					return problem.New(problem.UploadNotFound, "upload already used").WithField(fmt.Sprintf("items.%d.upload_id", i), "upload_not_found")
				}
				mediaID = store.NewID()
				path, mime := p.path, p.mime
				if err := tx.CreateMedia(ctx, store.Media{
					ID: mediaID, OwnerUserID: &u.ID, Kind: string(p.kind), SourcePath: &path, SourceMime: &mime,
					TrimStartMS: p.trimS, TrimEndMS: p.trimE, IncludeAudio: p.audio, DurationMS: p.durMS,
				}); err != nil {
					return err
				}
				if err := tx.Enqueue(ctx, store.JobProcessMedia, map[string]any{"media_id": mediaID}); err != nil {
					return err
				}
			}
			if err := tx.CreateItem(ctx, store.Item{
				ID: itemID, MediaID: mediaID, SenderUserID: &u.ID, Caption: p.caption,
				FocalX: p.focalX, FocalY: p.focalY, Fit: p.fit, CapturedHint: p.hint,
			}); err != nil {
				return err
			}
			if err := tx.AddSendItem(ctx, sendID, itemID, i); err != nil {
				return err
			}
			if p.reuse != nil {
				if err := tx.Enqueue(ctx, store.JobThumbs, map[string]any{"item_id": itemID}); err != nil {
					return err
				}
				if err := tx.Enqueue(ctx, store.JobEnsureRenditions, map[string]any{"media_id": mediaID}); err != nil {
					return err
				}
			}
		}
		if err := s.placeItems(ctx, tx, sendID, itemIDs, frames, members); err != nil {
			return err
		}
		if err := tx.TouchLastSent(ctx, frames, u.ID); err != nil {
			return err
		}
		if key != "" {
			return tx.SaveIdempotent(ctx, u.ID, "send", key, sendRef{sendID})
		}
		return nil
	})
	if db.IsUniqueViolation(err, "idempotency_keys_pkey") {
		var prev sendRef
		if found, _ := store.IdempotentResult(ctx, s.st.Q(), u.ID, "send", key, &prev); found {
			sendID = prev.SendID
			err = nil
		}
	}
	if err != nil {
		return nil, err
	}
	sd, err := s.renderSend(ctx, sendID)
	if err != nil {
		return nil, err
	}
	return accepted(sd), nil
}

// placeItems creates placements of items on frames (skipping ones already there).
func (s *Server) placeItems(ctx context.Context, tx *store.Tx, sendID uuid.UUID, itemIDs, frames []uuid.UUID, members map[uuid.UUID]*store.Membership) error {
	for _, fid := range frames {
		f, err := tx.LockFrame(ctx, fid)
		if err != nil {
			return notFound(err, "frame")
		}
		moderated := f.Moderation && !members[fid].IsManager()
		for _, itemID := range itemIDs {
			sid := sendID
			_, err := tx.Exec(ctx, `
				INSERT INTO placements (id, frame_id, item_id, send_id, status, pending_moderation)
				SELECT $1, $2, $3, $4, 'pending', $5
				WHERE NOT EXISTS (SELECT 1 FROM placements WHERE frame_id = $2 AND item_id = $3 AND removed_at IS NULL)`,
				store.NewID(), fid, itemID, sid, moderated)
			if err != nil {
				return err
			}
		}
	}
	return nil
}

// ---------------------------------------------------------------- read, unsend, forward

func (s *Server) ownSend(ctx context.Context, id uuid.UUID) (*store.Send, error) {
	sd, err := s.st.GetSend(ctx, s.st.Q(), id)
	if err != nil {
		return nil, notFound(err, "send")
	}
	if sd.SenderID != me(ctx).ID {
		return nil, problem.New(problem.NotFound, "send not found")
	}
	return sd, nil
}

func (s *Server) GetSend(ctx context.Context, req gen.GetSendRequestObject) (gen.GetSendResponseObject, error) {
	if _, err := s.ownSend(ctx, req.SendId); err != nil {
		return nil, err
	}
	sd, err := s.renderSend(ctx, req.SendId)
	if err != nil {
		return nil, err
	}
	return ok(sd), nil
}

func pageLimit(l *int) int {
	if l == nil {
		return 50
	}
	return max(1, min(*l, 200))
}

func (s *Server) ListMySends(ctx context.Context, req gen.ListMySendsRequestObject) (gen.ListMySendsResponseObject, error) {
	limit := pageLimit(req.Params.Limit)
	var after *uuid.UUID
	if req.Params.Cursor != nil && *req.Params.Cursor != "" {
		id, err := uuid.Parse(*req.Params.Cursor)
		if err != nil {
			return nil, problem.Validation("cursor", "format", "invalid cursor").WithStatus(http.StatusBadRequest)
		}
		after = &id
	}
	state := "all"
	if req.Params.State != nil {
		state = string(*req.Params.State)
	}
	sends, err := s.st.ListSends(ctx, me(ctx).ID, state, after, limit+1)
	if err != nil {
		return nil, err
	}
	var next *string
	if len(sends) > limit {
		sends = sends[:limit]
		c := sends[limit-1].ID.String()
		next = &c
	}
	out, err := s.renderSends(ctx, s.st.Q(), sends)
	if err != nil {
		return nil, err
	}
	return ok(map[string]any{"sends": out, "next_cursor": next}), nil
}

func (s *Server) Unsend(ctx context.Context, req gen.UnsendRequestObject) (gen.UnsendResponseObject, error) {
	if _, err := s.ownSend(ctx, req.SendId); err != nil {
		return nil, err
	}
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		_, err := tx.RemovePlacements(ctx, `p.send_id = $1`, req.SendId)
		return err
	})
	if err != nil {
		return nil, err
	}
	s.publishSendUpdates(ctx, map[uuid.UUID]uuid.UUID{req.SendId: me(ctx).ID}, "")
	return noContent(), nil
}

func (s *Server) ForwardSend(ctx context.Context, req gen.ForwardSendRequestObject) (gen.ForwardSendResponseObject, error) {
	if _, err := s.ownSend(ctx, req.SendId); err != nil {
		return nil, err
	}
	frames, members, err := s.sendTargets(ctx, req.Body.FrameIds)
	if err != nil {
		return nil, err
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		rows, err := s.st.SendItemRows(ctx, tx, []uuid.UUID{req.SendId})
		if err != nil {
			return err
		}
		itemIDs := make([]uuid.UUID, 0, len(rows))
		medias := map[uuid.UUID]bool{}
		for _, r := range rows {
			itemIDs = append(itemIDs, r.ItemID)
			medias[r.MediaID] = true
		}
		if err := s.placeItems(ctx, tx, req.SendId, itemIDs, frames, members); err != nil {
			return err
		}
		for m := range medias {
			if err := tx.Enqueue(ctx, store.JobEnsureRenditions, map[string]any{"media_id": m}); err != nil {
				return err
			}
		}
		return tx.TouchLastSent(ctx, frames, me(ctx).ID)
	})
	if err != nil {
		return nil, err
	}
	sd, err := s.renderSend(ctx, req.SendId)
	if err != nil {
		return nil, err
	}
	return accepted(sd), nil
}

// ---------------------------------------------------------------- items

func (s *Server) UpdateItem(ctx context.Context, req gen.UpdateItemRequestObject) (gen.UpdateItemResponseObject, error) {
	u := me(ctx)
	it, err := s.st.GetItemRow(ctx, s.st.Q(), req.ItemId)
	if err != nil {
		return nil, notFound(err, "item")
	}
	frames, err := s.st.ItemFrames(ctx, s.st.Q(), req.ItemId)
	if err != nil {
		return nil, err
	}
	if it.SenderUserID == nil || *it.SenderUserID != u.ID {
		if len(frames) == 0 {
			return nil, problem.New(problem.NotFound, "item not found")
		}
		for _, f := range frames {
			m, err := s.membership(ctx, f)
			if err != nil {
				return nil, err
			}
			if err := requirePerm(m, m.Effective().ManageLibrary, "manage_library"); err != nil {
				return nil, err
			}
		}
	}
	b := req.Body
	p := store.ItemPatch{Caption: b.Caption}
	if _, null := rawField(ctx, "caption"); null || (b.Caption != nil && *b.Caption == "") {
		p.Caption, p.ClearCaption = nil, true
	}
	if b.FocalPoint != nil {
		x, y := float64(b.FocalPoint.X), float64(b.FocalPoint.Y)
		p.FocalX, p.FocalY = &x, &y
	}
	if b.Fit != nil {
		f := string(*b.Fit)
		p.Fit = &f
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.UpdateItem(ctx, req.ItemId, p); err != nil {
			return err
		}
		if p.FocalX != nil {
			if err := tx.Enqueue(ctx, store.JobThumbs, map[string]any{"item_id": req.ItemId}); err != nil {
				return err
			}
		}
		rows, err := tx.Query(ctx, `SELECT id, frame_id FROM placements WHERE item_id = $1 AND removed_at IS NULL`, req.ItemId)
		if err != nil {
			return err
		}
		type pf struct{ id, frame uuid.UUID }
		var pfs []pf
		for rows.Next() {
			var x pf
			if err := rows.Scan(&x.id, &x.frame); err != nil {
				return err
			}
			pfs = append(pfs, x)
		}
		if err := rows.Err(); err != nil {
			return err
		}
		sort.Slice(pfs, func(i, j int) bool { return pfs[i].frame.String() < pfs[j].frame.String() })
		for _, x := range pfs {
			if err := tx.FeedPut(ctx, x.frame, store.FeedPlacement, x.id, false); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	row, err := s.st.GetItemRow(ctx, s.st.Q(), req.ItemId)
	if err != nil {
		return nil, err
	}
	return ok(s.itemJSON(row)), nil
}

// getItemMedia redirects to a short-lived signed URL for a thumbnail or master.
func (s *Server) getItemMedia(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	itemID, err := uuid.Parse(r.PathValue("itemId"))
	if err != nil {
		problem.Write(w, problem.New(problem.NotFound, ""))
		return
	}
	allowed, err := s.st.UserCanSeeItem(ctx, me(ctx).ID, itemID)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	if !allowed {
		problem.Write(w, problem.New(problem.NotFound, "item not found"))
		return
	}
	it, err := s.st.GetItemRow(ctx, s.st.Q(), itemID)
	if err != nil {
		s.writeError(w, r, notFound(err, "item"))
		return
	}
	var key *string
	switch r.PathValue("variant") {
	case "thumb_256":
		key = it.Thumb256Key
	case "thumb_512":
		key = it.Thumb512Key
	case "master":
		key = it.MasterKey
	case "poster":
		key = it.PosterKey
	default:
		problem.Write(w, problem.Validation("variant", "enum", "unknown variant").WithStatus(http.StatusBadRequest))
		return
	}
	if key == nil {
		problem.Write(w, problem.New(problem.NotFound, "not available (yet)"))
		return
	}
	http.Redirect(w, r, s.signedURL(*key, 5*time.Minute), http.StatusFound)
}

// signedMedia serves blobs behind a signed URL (no auth header needed, so
// <img> and <video> tags work).
func (s *Server) signedMedia(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	q := r.URL.Query()
	if !s.signer.Valid("/v1/media/signed/"+key, q.Get("exp"), q.Get("sig")) {
		problem.Write(w, problem.New(problem.Forbidden, "invalid or expired link"))
		return
	}
	s.serveBlobOrRedirect(w, r, key, "", "", "private, max-age=3600")
}

// ---------------------------------------------------------------- events

// publishSendUpdates sends send.updated to the senders of the given sends
// (send -> sender). pushKey, if set, also pushes when the sender is offline.
func (s *Server) publishSendUpdates(ctx context.Context, sends map[uuid.UUID]uuid.UUID, pushKey string) {
	s.publishSendUpdatesWith(ctx, sends, pushKey, nil)
}

// publishSendUpdatesWith is publishSendUpdates with explicit pushes per send,
// which win over pushKey.
func (s *Server) publishSendUpdatesWith(ctx context.Context, sends map[uuid.UUID]uuid.UUID, pushKey string, pushes map[uuid.UUID]*notify.Push) {
	for sendID, sender := range sends {
		sd, err := s.renderSend(ctx, sendID)
		if err != nil {
			s.log.Warn("render send update", "send", sendID, "err", err)
			continue
		}
		var push *notify.Push
		if pushKey != "" {
			frame := ""
			if len(sd.Frames) > 0 {
				frame = sd.Frames[0].FrameName
			}
			push = &notify.Push{Key: pushKey, Args: map[string]string{"frame": frame}, IDs: map[string]string{"send_id": sendID.String()}}
		}
		if p := pushes[sendID]; p != nil {
			push = p
		}
		s.notify.User(sender, notify.Event{"type": "send.updated", "send": sd}, push)
	}
}

// PlacementsChanged implements worker.Events.
func (s *Server) PlacementsChanged(changes []store.StatusChange) {
	s.placementsChanged(context.Background(), changes, nil)
}

func (s *Server) placementsChanged(ctx context.Context, changes []store.StatusChange, pushes map[uuid.UUID]*notify.Push) {
	sends := map[uuid.UUID]uuid.UUID{}
	for _, c := range changes {
		if c.SendID != nil && c.SenderID != nil {
			sends[*c.SendID] = *c.SenderID
		}
	}
	s.publishSendUpdatesWith(ctx, sends, "", pushes)
}

// deliveredPushes returns a "delivered" push for every send that this ack
// completed on the frame (all its placements there reached the frame), so the
// sender hears once per frame, when the last photo arrives.
func (s *Server) deliveredPushes(ctx context.Context, f *store.Frame, changes []store.StatusChange) map[uuid.UUID]*notify.Push {
	out := map[uuid.UUID]*notify.Push{}
	checked := map[uuid.UUID]bool{}
	for _, c := range changes {
		if !c.NewlyDelivered || c.SendID == nil || c.SenderID == nil || checked[*c.SendID] {
			continue
		}
		checked[*c.SendID] = true
		done, err := s.st.SendDeliveredOn(ctx, *c.SendID, f.ID)
		if err != nil {
			s.log.Warn("delivered check", "send", *c.SendID, "err", err)
			continue
		}
		if done {
			out[*c.SendID] = &notify.Push{Key: "delivered", Args: map[string]string{"frame": f.Name},
				IDs: map[string]string{"send_id": c.SendID.String(), "frame_id": f.ID.String()}}
		}
	}
	return out
}

// MediaFailed implements worker.Events.
func (s *Server) MediaFailed(mediaID uuid.UUID) {
	ctx := context.Background()
	rows, err := s.st.Pool.Query(ctx, `SELECT DISTINCT si.send_id, s.sender_user_id FROM send_items si
		JOIN items i ON i.id = si.item_id JOIN sends s ON s.id = si.send_id WHERE i.media_id = $1`, mediaID)
	if err != nil {
		s.log.Warn("media failed lookup", "err", err)
		return
	}
	sends := map[uuid.UUID]uuid.UUID{}
	for rows.Next() {
		var sid, uid uuid.UUID
		if rows.Scan(&sid, &uid) == nil {
			sends[sid] = uid
		}
	}
	rows.Close()
	s.publishSendUpdates(ctx, sends, "failed")
}
