package api

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/i18n"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/settings"
	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/ws"
)

func (s *Server) ListMyFrames(ctx context.Context, _ gen.ListMyFramesRequestObject) (gen.ListMyFramesResponseObject, error) {
	views, err := s.st.ListFramesForUser(ctx, me(ctx).ID)
	if err != nil {
		return nil, err
	}
	out := make([]gen.FrameSummary, len(views))
	for i := range views {
		out[i] = s.frameSummary(&views[i])
	}
	return ok(out), nil
}

func (s *Server) frameView(ctx context.Context, frameID uuid.UUID) (*store.FrameView, error) {
	v, err := s.st.GetFrameView(ctx, s.st.Q(), me(ctx).ID, frameID)
	return v, notFound(err, "frame")
}

func (s *Server) GetFrame(ctx context.Context, req gen.GetFrameRequestObject) (gen.GetFrameResponseObject, error) {
	v, err := s.frameView(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	return ok(s.frameJSON(v)), nil
}

func (s *Server) UpdateFrame(ctx context.Context, req gen.UpdateFrameRequestObject) (gen.UpdateFrameResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requireManager(m); err != nil {
		return nil, err
	}
	b := req.Body
	p := store.FramePatch{
		Name: b.Name, Location: b.Location, ApproveJoinsOnFrame: b.ApproveJoinsOnFrame,
		InviteRequiresApproval: b.InviteRequiresApproval, Moderation: b.Moderation,
	}
	if _, null := rawField(ctx, "location"); null || (b.Location != nil && *b.Location == "") {
		p.ClearLocation, p.Location = true, nil
	}
	if b.DefaultPermissions != nil {
		f, err := s.st.GetFrame(ctx, s.st.Q(), req.FrameId)
		if err != nil {
			return nil, notFound(err, "frame")
		}
		dp := permsFromJSON(f.DefaultPermissions, *b.DefaultPermissions)
		p.DefaultPermissions = &dp
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.UpdateFrame(ctx, req.FrameId, p); err != nil {
			return err
		}
		if b.Name != nil || b.Location != nil || p.ClearLocation {
			return tx.FeedPut(ctx, req.FrameId, store.FeedFrame, req.FrameId, false)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	v, err := s.frameView(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	return ok(s.frameJSON(v)), nil
}

func (s *Server) RemoveFrame(ctx context.Context, req gen.RemoveFrameRequestObject) (gen.RemoveFrameResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requireOwner(m); err != nil {
		return nil, err
	}
	var deviceID *uuid.UUID
	var members []uuid.UUID
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		f, err := tx.LockFrame(ctx, req.FrameId)
		if err != nil {
			return notFound(err, "frame")
		}
		deviceID = f.DeviceID
		if f.DeviceID != nil {
			if err := tx.RevokeDevice(ctx, *f.DeviceID); err != nil {
				return err
			}
		}
		if _, err := tx.Exec(ctx, `UPDATE placements SET removed_at = now() WHERE frame_id = $1 AND removed_at IS NULL`, f.ID); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, `DELETE FROM memberships WHERE frame_id = $1 RETURNING user_id`, f.ID)
		if err != nil {
			return err
		}
		for rows.Next() {
			var id uuid.UUID
			if err := rows.Scan(&id); err != nil {
				return err
			}
			members = append(members, id)
		}
		if err := rows.Err(); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE invites SET revoked_at = now() WHERE frame_id = $1 AND revoked_at IS NULL`, f.ID); err != nil {
			return err
		}
		return tx.MarkFrameDeleted(ctx, f.ID)
	})
	if err != nil {
		return nil, err
	}
	if deviceID != nil {
		s.hub.Revoke(*deviceID)
	}
	s.notify.Users(members, notify.Event{"type": "membership.updated", "frame_id": req.FrameId}, nil)
	return noContent(), nil
}

func (s *Server) LeaveFrame(ctx context.Context, req gen.LeaveFrameRequestObject) (gen.LeaveFrameResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if m.Role == "owner" {
		return nil, problem.New(problem.OwnerCannotLeave, "")
	}
	removeItems := req.Body != nil && req.Body.RemoveMyItems != nil && *req.Body.RemoveMyItems
	err = s.st.InTx(ctx, func(tx *store.Tx) error { return s.removeMember(ctx, tx, req.FrameId, m.UserID, removeItems) })
	if err != nil {
		return nil, err
	}
	return noContent(), nil
}

func (s *Server) removeMember(ctx context.Context, tx *store.Tx, frameID, userID uuid.UUID, removeItems bool) error {
	if removeItems {
		if _, err := tx.RemovePlacements(ctx, `p.frame_id = $1 AND (p.item_id IN (SELECT id FROM items WHERE sender_user_id = $2) OR p.shared_by_user_id = $2)`, frameID, userID); err != nil {
			return err
		}
	}
	return tx.RemoveMember(ctx, frameID, userID)
}

func (s *Server) TransferOwnership(ctx context.Context, req gen.TransferOwnershipRequestObject) (gen.TransferOwnershipResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requireOwner(m); err != nil {
		return nil, err
	}
	target := req.Body.UserId
	if target == m.UserID {
		return nil, problem.Validation("user_id", "self", "you already own this frame")
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockFrame(ctx, req.FrameId); err != nil {
			return notFound(err, "frame")
		}
		if _, err := s.st.GetMembership(ctx, tx, req.FrameId, target); err != nil {
			if err == store.ErrNotFound {
				return problem.Validation("user_id", "not_member", "the new owner must be a member of the frame")
			}
			return err
		}
		admin, owner := "admin", "owner"
		if _, err := tx.UpdateMember(ctx, req.FrameId, m.UserID, &admin, nil); err != nil {
			return err
		}
		if _, err := tx.UpdateMember(ctx, req.FrameId, target, &owner, nil); err != nil {
			return err
		}
		return tx.FeedPut(ctx, req.FrameId, store.FeedFrame, req.FrameId, false)
	})
	if err != nil {
		return nil, err
	}
	ev := notify.Event{"type": "membership.updated", "frame_id": req.FrameId}
	s.notify.Users([]uuid.UUID{m.UserID, target}, ev, nil)
	return noContent(), nil
}

func (s *Server) GetFrameHealth(ctx context.Context, req gen.GetFrameHealthRequestObject) (gen.GetFrameHealthResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requirePerm(m, m.Effective().ChangeSettings, "change_settings"); err != nil {
		return nil, err
	}
	f, err := s.st.GetFrame(ctx, s.st.Q(), req.FrameId)
	if err != nil {
		return nil, notFound(err, "frame")
	}
	online := s.hub.FrameOnline(f.ID)
	h := gen.FrameHealth{Online: &online}
	if f.DeviceID != nil {
		d, err := s.st.GetDevice(ctx, s.st.Q(), *f.DeviceID)
		if err != nil {
			return nil, err
		}
		h.LastSeenAt, h.LastUncleanShutdownAt = d.LastSeenAt, d.LastUncleanShutdownAt
		if online {
			h.LastSeenAt = ptr(time.Now().UTC().Truncate(time.Second))
		}
		if len(d.LastHeartbeat) > 0 {
			var hb gen.Heartbeat
			if json.Unmarshal(d.LastHeartbeat, &hb) == nil {
				h.LastHeartbeat = &hb
			}
		}
	}
	return ok(h), nil
}

func (s *Server) SendFrameCommand(ctx context.Context, req gen.SendFrameCommandRequestObject) (gen.SendFrameCommandResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	cmd := req.Body
	perms := m.Effective()
	args := map[string]any{}
	if cmd.Type == gen.ShowPlacement {
		if err := requirePerm(m, perms.ManageLibrary || perms.ChangeSettings, "manage_library"); err != nil {
			return nil, err
		}
		if cmd.PlacementId == nil {
			return nil, problem.Validation("placement_id", "required", "show_placement needs a placement_id")
		}
		if _, err := s.st.GetPlacementRow(ctx, s.st.Q(), *cmd.PlacementId, &req.FrameId); err != nil {
			return nil, notFound(err, "placement")
		}
		args["placement_id"] = cmd.PlacementId.String()
	} else if err := requirePerm(m, perms.ChangeSettings, "change_settings"); err != nil {
		return nil, err
	}
	res, err := s.hub.Command(ctx, req.FrameId, string(cmd.Type), args, s.cfg.CommandTimeout)
	switch {
	case errors.Is(err, ws.ErrOffline):
		return nil, problem.New(problem.FrameOffline, "")
	case errors.Is(err, ws.ErrTimeout):
		return nil, problem.New(problem.CommandTimeout, "")
	case err != nil:
		return nil, err
	}
	return ok(gen.CommandResult{Ok: res.OK, ErrorCode: res.ErrorCode}), nil
}

// ---------------------------------------------------------------- codes: redeem and replace

// limitRedeem applies README §5.1 limits: 10/h per user and 30/h per IP.
func (s *Server) limitRedeem(ctx context.Context) error {
	if ok, retry := s.redeemUser.Allow(me(ctx).ID.String()); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return p
	}
	if ok, retry := s.redeemIP.Allow(clientIP(ctx)); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return p
	}
	return nil
}

// lookupCode resolves a frame code to its device; invalid codes all look alike.
func (s *Server) lookupCode(ctx context.Context, q db.DBTX, code string) (*store.Device, error) {
	c, err := s.st.LookupCode(ctx, q, code)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.CodeInvalid, "")
	}
	if err != nil {
		return nil, err
	}
	if time.Now().After(c.ExpiresAt) {
		return nil, problem.New(problem.CodeExpired, "Ask for a new code on the frame.")
	}
	if _, err := q.Exec(ctx, `SELECT 1 FROM devices WHERE id = $1 FOR UPDATE`, c.DeviceID); err != nil {
		return nil, err
	}
	return s.st.GetDevice(ctx, q, c.DeviceID)
}

type redeemOutcome struct {
	Status        string     `json:"status"`
	FrameID       uuid.UUID  `json:"frame_id"`
	JoinRequestID *uuid.UUID `json:"join_request_id,omitempty"`
	ClaimedDevice *uuid.UUID `json:"claimed_device,omitempty"`
}

func (s *Server) RedeemFrameCode(ctx context.Context, req gen.RedeemFrameCodeRequestObject) (gen.RedeemFrameCodeResponseObject, error) {
	u := me(ctx)
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev redeemOutcome
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "redeem", key, &prev); err != nil {
			return nil, err
		} else if found {
			return s.redeemResponse(ctx, prev)
		}
	}
	if err := s.limitRedeem(ctx); err != nil {
		return nil, err
	}
	var out redeemOutcome
	var jr *store.JoinRequest
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		d, err := s.lookupCode(ctx, tx, req.Body.Code)
		if err != nil {
			return err
		}
		if d.FrameID == nil {
			out, err = s.claim(ctx, tx, d, u)
		} else {
			out, jr, err = s.joinByCode(ctx, tx, *d.FrameID, u)
		}
		if err != nil {
			return err
		}
		if key != "" {
			return tx.SaveIdempotent(ctx, u.ID, "redeem", key, out)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	s.afterRedeem(ctx, out, jr, u)
	return s.redeemResponse(ctx, out)
}

func (s *Server) claim(ctx context.Context, tx *store.Tx, d *store.Device, u *store.User) (redeemOutcome, error) {
	// The frame's language is what it reported at enrolment (chosen in its setup wizard); the claiming user's
	// language is the fallback. The default name is in that language, so it reads naturally on the frame.
	lang, tz := "", ""
	if d.Locale != nil {
		lang = i18n.Normalize(*d.Locale)
	}
	if lang == "" && u.Locale != nil {
		lang = i18n.Normalize(*u.Locale)
	}
	if lang == "" {
		lang = i18n.Default
	}
	if d.Timezone != nil {
		tz = *d.Timezone
	}
	doc, _ := json.Marshal(settings.Defaults(lang, tz))
	f, err := tx.CreateFrame(ctx, store.Frame{
		ID: store.NewID(), Name: i18n.DefaultFrameName(lang, u.DisplayName), DeviceID: &d.ID,
		DefaultPermissions: store.DefaultMemberPermissions, Settings: doc,
	})
	if err != nil {
		return redeemOutcome{}, err
	}
	if err := tx.AttachDevice(ctx, f.ID, d.ID); err != nil {
		return redeemOutcome{}, err
	}
	if _, err := tx.AddMember(ctx, f.ID, u.ID, "owner", store.AllPermissions); err != nil {
		return redeemOutcome{}, err
	}
	if err := tx.FeedPut(ctx, f.ID, store.FeedSettings, f.ID, false); err != nil {
		return redeemOutcome{}, err
	}
	if err := tx.FeedPut(ctx, f.ID, store.FeedFrame, f.ID, false); err != nil {
		return redeemOutcome{}, err
	}
	if err := tx.DeleteDeviceCode(ctx, d.ID); err != nil {
		return redeemOutcome{}, err
	}
	return redeemOutcome{Status: "claimed", FrameID: f.ID, ClaimedDevice: &d.ID}, nil
}

func (s *Server) joinByCode(ctx context.Context, tx *store.Tx, frameID uuid.UUID, u *store.User) (redeemOutcome, *store.JoinRequest, error) {
	f, err := tx.LockFrame(ctx, frameID)
	if err != nil {
		return redeemOutcome{}, nil, problem.New(problem.CodeInvalid, "")
	}
	if _, err := s.st.GetMembership(ctx, tx, f.ID, u.ID); err == nil {
		return redeemOutcome{Status: "already_member", FrameID: f.ID}, nil, nil
	} else if err != store.ErrNotFound {
		return redeemOutcome{}, nil, err
	}
	if f.ApproveJoinsOnFrame {
		jr, isNew, err := tx.CreateJoinRequest(ctx, f.ID, u.ID, "frame_code", nil, nil, nil)
		if err != nil {
			return redeemOutcome{}, nil, err
		}
		out := redeemOutcome{Status: "pending", FrameID: f.ID, JoinRequestID: &jr.ID}
		if !isNew {
			return out, nil, nil // already announced
		}
		return out, jr, nil
	}
	if _, err := tx.AddMember(ctx, f.ID, u.ID, "member", f.DefaultPermissions); err != nil {
		return redeemOutcome{}, nil, err
	}
	return redeemOutcome{Status: "joined", FrameID: f.ID}, nil, nil
}

// afterRedeem sends realtime events once the transaction committed.
func (s *Server) afterRedeem(ctx context.Context, out redeemOutcome, jr *store.JoinRequest, u *store.User) {
	switch {
	case out.ClaimedDevice != nil:
		s.hub.BindFrame(*out.ClaimedDevice, out.FrameID)
		s.hub.SendToDevice(*out.ClaimedDevice, map[string]any{"type": "claimed", "owner_name": u.DisplayName})
	case jr != nil:
		s.announceJoinRequest(ctx, out.FrameID, jr)
	}
}

func (s *Server) announceJoinRequest(ctx context.Context, frameID uuid.UUID, jr *store.JoinRequest) {
	req := s.joinRequestJSON(jr)
	s.hub.SendToFrame(frameID, map[string]any{"type": "join_request", "request": req})
	managers, err := s.st.MemberIDs(ctx, frameID, true)
	if err != nil {
		s.log.Warn("list managers", "err", err)
		return
	}
	name := ""
	if f, err := s.st.GetFrame(ctx, s.st.Q(), frameID); err == nil {
		name = f.Name
	}
	s.notify.Users(managers, notify.Event{"type": "join_request", "frame_id": frameID, "request": req},
		&notify.Push{Key: "join_request", Args: map[string]string{"name": jr.UserName, "frame": name},
			IDs: map[string]string{"frame_id": frameID.String(), "request_id": jr.ID.String()}})
}

// redeemResponse renders a RedeemResult; for pending joins the caller is not a
// member yet, so the summary shows what they would get.
func (s *Server) redeemResponse(ctx context.Context, out redeemOutcome) (Response, error) {
	res := gen.RedeemResult{Status: gen.RedeemResultStatus(out.Status), JoinRequestId: out.JoinRequestID}
	if v, err := s.st.GetFrameView(ctx, s.st.Q(), me(ctx).ID, out.FrameID); err == nil {
		res.Frame = s.frameSummary(v)
	} else if err == store.ErrNotFound {
		f, err := s.st.GetFrame(ctx, s.st.Q(), out.FrameID)
		if err != nil {
			return Response{}, notFound(err, "frame")
		}
		owner, _ := s.st.FrameOwnerName(ctx, s.st.Q(), f.ID)
		res.Frame = gen.FrameSummary{
			Id: f.ID, Name: f.Name, Location: f.Location, OwnerName: &owner, Role: gen.RoleMember,
			Permissions: permsJSON(f.DefaultPermissions), Online: s.hub.FrameOnline(f.ID),
		}
	} else {
		return Response{}, err
	}
	return ok(res), nil
}

func (s *Server) ReplaceDevice(ctx context.Context, req gen.ReplaceDeviceRequestObject) (gen.ReplaceDeviceResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requireOwner(m); err != nil {
		return nil, err
	}
	if err := s.limitRedeem(ctx); err != nil {
		return nil, err
	}
	var oldDevice *uuid.UUID
	var newDevice uuid.UUID
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		f, err := tx.LockFrame(ctx, req.FrameId)
		if err != nil {
			return notFound(err, "frame")
		}
		d, err := s.lookupCode(ctx, tx, req.Body.Code)
		if err != nil {
			return err
		}
		if d.FrameID != nil {
			return problem.New(problem.CodeInvalid, "that frame is already set up; reset it first")
		}
		oldDevice, newDevice = f.DeviceID, d.ID
		if f.DeviceID != nil {
			if err := tx.RevokeDevice(ctx, *f.DeviceID); err != nil {
				return err
			}
		}
		if err := tx.AttachDevice(ctx, f.ID, d.ID); err != nil {
			return err
		}
		if err := tx.DeleteDeviceCode(ctx, d.ID); err != nil {
			return err
		}
		// The new device may have another screen size: render what it needs.
		rows, err := tx.Query(ctx, `SELECT i.media_id FROM placements p JOIN items i ON i.id = p.item_id
			WHERE p.frame_id = $1 AND p.removed_at IS NULL
			UNION SELECT i.motion_media_id FROM placements p JOIN items i ON i.id = p.item_id
			WHERE p.frame_id = $1 AND p.removed_at IS NULL AND i.motion_media_id IS NOT NULL`, f.ID)
		if err != nil {
			return err
		}
		var mediaIDs []uuid.UUID
		for rows.Next() {
			var id uuid.UUID
			if err := rows.Scan(&id); err != nil {
				return err
			}
			mediaIDs = append(mediaIDs, id)
		}
		if err := rows.Err(); err != nil {
			return err
		}
		for _, id := range mediaIDs {
			if err := tx.Enqueue(ctx, store.JobEnsureRenditions, map[string]any{"media_id": id}); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	if oldDevice != nil {
		s.hub.Revoke(*oldDevice)
	}
	s.hub.BindFrame(newDevice, req.FrameId)
	s.hub.SendToDevice(newDevice, map[string]any{"type": "claimed", "owner_name": me(ctx).DisplayName})
	v, err := s.frameView(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	return ok(s.frameJSON(v)), nil
}
