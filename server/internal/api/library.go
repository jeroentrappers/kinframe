package api

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

func (s *Server) ListPlacements(ctx context.Context, req gen.ListPlacementsRequestObject) (gen.ListPlacementsResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	f := store.PlacementFilter{FrameID: req.FrameId, Limit: pageLimit(req.Params.Limit), SenderID: req.Params.SenderId, AlbumID: req.Params.AlbumId}
	if !m.Effective().SeeLibrary {
		f.OnlySender = &m.UserID
	}
	if req.Params.Visibility != nil {
		f.Visibility = string(*req.Params.Visibility)
	}
	if req.Params.Sort != nil {
		f.Sort = string(*req.Params.Sort)
	}
	if req.Params.Kind != nil {
		k := string(*req.Params.Kind)
		f.Kind = &k
	}
	if req.Params.Cursor != nil {
		f.Cursor = *req.Params.Cursor
	}
	if d := req.Params.OnThisDay; d != nil {
		if _, err := time.Parse("01-02", *d); err != nil && *d != "02-29" {
			return nil, problem.Validation("on_this_day", "format", "on_this_day must be a month and day, MM-DD").WithStatus(http.StatusBadRequest)
		}
		f.OnThisDay = *d
	}
	rows, next, err := s.st.ListPlacements(ctx, f)
	if err == store.ErrBadCursor {
		return nil, problem.Validation("cursor", "format", "invalid cursor").WithStatus(http.StatusBadRequest)
	}
	if err != nil {
		return nil, err
	}
	counts, err := s.st.CountPlacements(ctx, req.FrameId, f.OnlySender)
	if err != nil {
		return nil, err
	}
	out := make([]gen.Placement, len(rows))
	for i := range rows {
		out[i] = s.placementJSON(&rows[i])
	}
	return ok(map[string]any{
		"placements": out, "next_cursor": next,
		"counts": map[string]int{"photos": counts.Photos, "videos": counts.Videos, "hidden": counts.Hidden, "trashed": counts.Trashed},
	}), nil
}

// canEditPlacement: the item's sender, or manage_library.
func canEditPlacement(m *store.Membership, row *store.PlacementRow) bool {
	return m.Effective().ManageLibrary || (row.SenderUserID != nil && *row.SenderUserID == m.UserID)
}

func (s *Server) editablePlacement(ctx context.Context, frameID, placementID uuid.UUID) (*store.Membership, *store.PlacementRow, error) {
	m, err := s.membership(ctx, frameID)
	if err != nil {
		return nil, nil, err
	}
	row, err := s.st.GetPlacementRow(ctx, s.st.Q(), placementID, &frameID)
	if err != nil {
		return nil, nil, notFound(err, "placement")
	}
	if !m.Effective().SeeLibrary && !canEditPlacement(m, row) {
		return nil, nil, problem.New(problem.NotFound, "placement not found")
	}
	if !canEditPlacement(m, row) {
		return nil, nil, problem.New(problem.MissingPermission, "requires the %s permission", "manage_library")
	}
	return m, row, nil
}

func (s *Server) UpdatePlacement(ctx context.Context, req gen.UpdatePlacementRequestObject) (gen.UpdatePlacementResponseObject, error) {
	m, _, err := s.editablePlacement(ctx, req.FrameId, req.PlacementId)
	if err != nil {
		return nil, err
	}
	b := req.Body
	if b.Approved != nil {
		if err := requireManager(m); err != nil {
			return nil, err
		}
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		return tx.UpdatePlacement(ctx, req.FrameId, req.PlacementId, store.PlacementPatch{Hidden: b.Hidden, Trashed: b.Trashed, Approved: b.Approved})
	})
	if err != nil {
		return nil, err
	}
	row, err := s.st.GetPlacementRow(ctx, s.st.Q(), req.PlacementId, &req.FrameId)
	if err != nil {
		return nil, err
	}
	return ok(s.placementJSON(row)), nil
}

func (s *Server) PurgePlacement(ctx context.Context, req gen.PurgePlacementRequestObject) (gen.PurgePlacementResponseObject, error) {
	if _, _, err := s.editablePlacement(ctx, req.FrameId, req.PlacementId); err != nil {
		return nil, err
	}
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		_, err := tx.RemovePlacements(ctx, `p.id = $1 AND p.frame_id = $2`, req.PlacementId, req.FrameId)
		return err
	})
	if err != nil {
		return nil, err
	}
	return noContent(), nil
}

func (s *Server) BulkUpdatePlacements(ctx context.Context, req gen.BulkUpdatePlacementsRequestObject) (gen.BulkUpdatePlacementsResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	b := req.Body
	action := b.Action
	if action == gen.AddToAlbum || action == gen.RemoveFromAlbum {
		if err := requirePerm(m, m.Effective().ManageLibrary, "manage_library"); err != nil {
			return nil, err
		}
		if b.AlbumId == nil {
			return nil, problem.Validation("album_id", "required", "album_id is required for album actions")
		}
		if _, err := s.st.GetAlbum(ctx, s.st.Q(), req.FrameId, *b.AlbumId); err != nil {
			return nil, notFound(err, "album")
		}
	}
	rows, err := s.st.PlacementRowsByIDs(ctx, b.PlacementIds)
	if err != nil {
		return nil, err
	}
	type failure struct {
		ID   uuid.UUID `json:"id"`
		Code string    `json:"code"`
	}
	okIDs := []uuid.UUID{}
	failed := []failure{}
	var allowed []uuid.UUID
	for _, id := range b.PlacementIds {
		row, found := rows[id]
		switch {
		case !found || row.FrameID != req.FrameId || row.RemovedAt != nil:
			failed = append(failed, failure{id, string(problem.NotFound)})
		case !canEditPlacement(m, &row):
			failed = append(failed, failure{id, string(problem.MissingPermission)})
		default:
			allowed = append(allowed, id)
		}
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		switch action {
		case gen.AddToAlbum:
			return tx.AddToAlbum(ctx, req.FrameId, *b.AlbumId, allowed)
		case gen.RemoveFromAlbum:
			return tx.RemoveFromAlbum(ctx, req.FrameId, *b.AlbumId, allowed)
		case gen.Purge:
			_, err := tx.RemovePlacements(ctx, `p.frame_id = $1 AND p.id = ANY($2)`, req.FrameId, allowed)
			return err
		}
		t, f := true, false
		var p store.PlacementPatch
		switch action {
		case gen.Hide:
			p.Hidden = &t
		case gen.Unhide:
			p.Hidden = &f
		case gen.Trash:
			p.Trashed = &t
		case gen.Restore:
			p.Trashed = &f
		}
		for _, id := range allowed {
			if err := tx.UpdatePlacement(ctx, req.FrameId, id, p); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	okIDs = append(okIDs, allowed...)
	return ok(map[string]any{"ok": okIDs, "failed": failed}), nil
}

// ---------------------------------------------------------------- albums

type albumJSON struct {
	ID               uuid.UUID       `json:"id"`
	Name             string          `json:"name"`
	Version          int             `json:"version"`
	PlacementCount   int             `json:"placement_count"`
	CoverPlacementID *uuid.UUID      `json:"cover_placement_id,omitempty"`
	PlacementIDs     []uuid.UUID     `json:"placement_ids"`
	Schedule         json.RawMessage `json:"schedule"`
}

func albumOut(a *store.Album) albumJSON {
	ids := a.PlacementIDs
	if ids == nil {
		ids = []uuid.UUID{}
	}
	sched := a.Schedule
	if len(sched) == 0 {
		sched = json.RawMessage("null")
	}
	return albumJSON{ID: a.ID, Name: a.Name, Version: a.Version, PlacementCount: len(ids), CoverPlacementID: a.CoverPlacementID, PlacementIDs: ids, Schedule: sched}
}

func (s *Server) ListAlbums(ctx context.Context, req gen.ListAlbumsRequestObject) (gen.ListAlbumsResponseObject, error) {
	if _, err := s.membership(ctx, req.FrameId); err != nil {
		return nil, err
	}
	as, err := s.st.ListAlbums(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	out := make([]albumJSON, len(as))
	for i := range as {
		out[i] = albumOut(&as[i])
	}
	return ok(out), nil
}

func albumWrite(ctx context.Context, b *gen.AlbumWrite) (store.AlbumWrite, error) {
	w := store.AlbumWrite{Name: b.Name, PlacementIDs: b.PlacementIds, CoverPlacementID: b.CoverPlacementId}
	if present, null := rawField(ctx, "schedule"); null {
		raw := json.RawMessage("null")
		w.Schedule = &raw
	} else if present && b.Schedule != nil {
		raw, err := json.Marshal(b.Schedule)
		if err != nil {
			return w, err
		}
		rm := json.RawMessage(raw)
		w.Schedule = &rm
	}
	return w, nil
}

func (s *Server) CreateAlbum(ctx context.Context, req gen.CreateAlbumRequestObject) (gen.CreateAlbumResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requirePerm(m, m.Effective().ManageLibrary, "manage_library"); err != nil {
		return nil, err
	}
	if req.Body.Name == nil {
		return nil, problem.Validation("name", "required", "an album needs a name")
	}
	w, err := albumWrite(ctx, req.Body)
	if err != nil {
		return nil, err
	}
	var id uuid.UUID
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		id, err = tx.CreateAlbum(ctx, req.FrameId, w)
		return err
	})
	if err != nil {
		return nil, err
	}
	a, err := s.st.GetAlbum(ctx, s.st.Q(), req.FrameId, id)
	if err != nil {
		return nil, err
	}
	return Response{Status: http.StatusCreated, Body: albumOut(a), Header: etag(a.Version)}, nil
}

func (s *Server) UpdateAlbum(ctx context.Context, req gen.UpdateAlbumRequestObject) (gen.UpdateAlbumResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requirePerm(m, m.Effective().ManageLibrary, "manage_library"); err != nil {
		return nil, err
	}
	version, err := parseIfMatch(req.Params.IfMatch)
	if err != nil {
		return nil, err
	}
	w, err := albumWrite(ctx, req.Body)
	if err != nil {
		return nil, err
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := s.st.GetAlbum(ctx, tx, req.FrameId, req.AlbumId); err != nil {
			return notFound(err, "album")
		}
		updated, err := tx.UpdateAlbum(ctx, req.FrameId, req.AlbumId, version, w)
		if err != nil {
			return err
		}
		if !updated {
			return problem.New(problem.VersionConflict, "album changed since version %d", version)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	a, err := s.st.GetAlbum(ctx, s.st.Q(), req.FrameId, req.AlbumId)
	if err != nil {
		return nil, err
	}
	return Response{Status: http.StatusOK, Body: albumOut(a), Header: map[string][]string{"ETag": {`"` + strconv.Itoa(a.Version) + `"`}}}, nil
}

func (s *Server) DeleteAlbum(ctx context.Context, req gen.DeleteAlbumRequestObject) (gen.DeleteAlbumResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requirePerm(m, m.Effective().ManageLibrary, "manage_library"); err != nil {
		return nil, err
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		deleted, err := tx.DeleteAlbum(ctx, req.FrameId, req.AlbumId)
		if err == nil && !deleted {
			return problem.New(problem.NotFound, "album not found")
		}
		return err
	})
	if err != nil {
		return nil, err
	}
	// A frame playing the deleted album goes back to all photos.
	if f, err := s.st.GetFrame(ctx, s.st.Q(), req.FrameId); err == nil {
		var doc struct {
			Slideshow struct {
				AlbumID *string `json:"album_id"`
			} `json:"slideshow"`
		}
		if json.Unmarshal(f.Settings, &doc) == nil && doc.Slideshow.AlbumID != nil && *doc.Slideshow.AlbumID == req.AlbumId.String() {
			patch := map[string]any{"slideshow": map[string]any{"album_id": nil}}
			if _, err := s.patchSettings(ctx, req.FrameId, `"`+strconv.Itoa(f.SettingsVersion)+`"`, patch); err != nil {
				s.log.Warn("reset slideshow album", "frame", req.FrameId, "err", err)
			}
		}
	}
	return noContent(), nil
}
