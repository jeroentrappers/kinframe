package api

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

func (s *Server) ListMembers(ctx context.Context, req gen.ListMembersRequestObject) (gen.ListMembersResponseObject, error) {
	if _, err := s.membership(ctx, req.FrameId); err != nil {
		return nil, err
	}
	ms, err := s.st.ListMembers(ctx, s.st.Q(), req.FrameId)
	if err != nil {
		return nil, err
	}
	out := make([]gen.Member, len(ms))
	for i := range ms {
		out[i] = s.memberJSON(&ms[i])
	}
	return ok(out), nil
}

// canManageMember enforces "admins cannot change the owner or other admins".
func canManageMember(caller, target *store.Membership) error {
	if err := requireManager(caller); err != nil {
		return err
	}
	if target.Role == "owner" {
		return problem.New(problem.Forbidden, "the owner cannot be changed; transfer ownership instead")
	}
	if caller.Role == "admin" && target.Role == "admin" && caller.UserID != target.UserID {
		return problem.New(problem.Forbidden, "admins cannot change other admins")
	}
	return nil
}

func (s *Server) UpdateMember(ctx context.Context, req gen.UpdateMemberRequestObject) (gen.UpdateMemberResponseObject, error) {
	caller, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	target, err := s.st.GetMembership(ctx, s.st.Q(), req.FrameId, req.UserId)
	if err != nil {
		return nil, notFound(err, "member")
	}
	if err := canManageMember(caller, target); err != nil {
		return nil, err
	}
	var role *string
	if req.Body.Role != nil {
		r := string(*req.Body.Role)
		role = &r
	}
	var perms *store.Permissions
	if req.Body.Permissions != nil {
		p := permsFromJSON(target.Permissions, *req.Body.Permissions)
		perms = &p
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		_, err := tx.UpdateMember(ctx, req.FrameId, req.UserId, role, perms)
		return err
	})
	if err != nil {
		return nil, err
	}
	s.notify.User(req.UserId, notify.Event{"type": "membership.updated", "frame_id": req.FrameId}, nil)
	mv, err := s.st.GetMemberView(ctx, s.st.Q(), req.FrameId, req.UserId)
	if err != nil {
		return nil, err
	}
	return ok(s.memberJSON(mv)), nil
}

func (s *Server) RemoveMember(ctx context.Context, req gen.RemoveMemberRequestObject) (gen.RemoveMemberResponseObject, error) {
	caller, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	target, err := s.st.GetMembership(ctx, s.st.Q(), req.FrameId, req.UserId)
	if err != nil {
		return nil, notFound(err, "member")
	}
	if caller.UserID != target.UserID {
		if err := canManageMember(caller, target); err != nil {
			return nil, err
		}
	} else if target.Role == "owner" {
		return nil, problem.New(problem.OwnerCannotLeave, "")
	}
	removeItems := req.Params.RemoveItems != nil && *req.Params.RemoveItems
	if err := s.st.InTx(ctx, func(tx *store.Tx) error { return s.removeMember(ctx, tx, req.FrameId, req.UserId, removeItems) }); err != nil {
		return nil, err
	}
	s.notify.User(req.UserId, notify.Event{"type": "membership.updated", "frame_id": req.FrameId}, s.membershipPush(ctx, req.FrameId))
	return noContent(), nil
}

func (s *Server) membershipPush(ctx context.Context, frameID uuid.UUID) *notify.Push {
	name := ""
	if f, err := s.st.GetFrame(ctx, s.st.Q(), frameID); err == nil {
		name = f.Name
	}
	return &notify.Push{Key: "membership", Args: map[string]string{"frame": name}, IDs: map[string]string{"frame_id": frameID.String()}}
}

func (s *Server) ListJoinRequests(ctx context.Context, req gen.ListJoinRequestsRequestObject) (gen.ListJoinRequestsResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requireManager(m); err != nil {
		return nil, err
	}
	jrs, err := s.st.ListPendingJoinRequests(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	out := make([]gen.JoinRequest, len(jrs))
	for i := range jrs {
		out[i] = s.joinRequestJSON(&jrs[i])
	}
	return ok(out), nil
}

func (s *Server) DecideJoinRequest(ctx context.Context, req gen.DecideJoinRequestRequestObject) (gen.DecideJoinRequestResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requireManager(m); err != nil {
		return nil, err
	}
	if err := s.decideJoin(ctx, req.FrameId, req.RequestId, req.Decision == gen.DecideJoinRequestParamsDecisionApprove); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// decideJoin resolves a pending join request (from the app or the frame).
func (s *Server) decideJoin(ctx context.Context, frameID, requestID uuid.UUID, approve bool) error {
	var userID uuid.UUID
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		f, err := tx.LockFrame(ctx, frameID)
		if err != nil {
			return notFound(err, "frame")
		}
		jr, err := tx.LockPendingJoinRequest(ctx, frameID, requestID)
		if err != nil {
			return notFound(err, "join request")
		}
		userID = jr.UserID
		status := "denied"
		if approve {
			status = "approved"
			if _, err := tx.AddMember(ctx, frameID, jr.UserID, "member", f.DefaultPermissions); err != nil {
				return err
			}
		}
		return tx.DecideJoinRequest(ctx, jr.ID, status)
	})
	if err != nil {
		return err
	}
	s.notify.User(userID, notify.Event{"type": "membership.updated", "frame_id": frameID}, s.membershipPush(ctx, frameID))
	return nil
}

// ---------------------------------------------------------------- invites

func (s *Server) ListInvites(ctx context.Context, req gen.ListInvitesRequestObject) (gen.ListInvitesResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	var by *uuid.UUID
	if !m.IsManager() {
		by = &m.UserID
	}
	invs, err := s.st.ListActiveInvites(ctx, req.FrameId, by)
	if err != nil {
		return nil, err
	}
	out := make([]gen.Invite, len(invs))
	for i := range invs {
		out[i] = s.inviteJSON(&invs[i])
	}
	return ok(out), nil
}

func (s *Server) CreateInvite(ctx context.Context, req gen.CreateInviteRequestObject) (gen.CreateInviteResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requirePerm(m, m.Effective().Invite, "invite"); err != nil {
		return nil, err
	}
	u := me(ctx)
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev gen.Invite
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "invite", key, &prev); err != nil {
			return nil, err
		} else if found {
			return created(prev), nil
		}
	}
	if ok, retry := s.inviteUser.Allow(u.ID.String()); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	hours := 168
	var note *string
	var maxUses *int
	if b := req.Body; b != nil {
		if b.ExpiresInHours != nil {
			hours = *b.ExpiresInHours
		}
		note, maxUses = b.Note, b.MaxUses
	}
	var out gen.Invite
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		inv, err := s.st.CreateInvite(ctx, tx, store.Invite{
			ID: store.NewID(), FrameID: req.FrameId, Token: auth.RandomToken(24), CreatedBy: &u.ID,
			MaxUses: maxUses, Note: note, ExpiresAt: time.Now().Add(time.Duration(hours) * time.Hour).UTC().Truncate(time.Second),
		})
		if err != nil {
			return err
		}
		out = s.inviteJSON(inv)
		if key != "" {
			return tx.SaveIdempotent(ctx, u.ID, "invite", key, out)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return created(out), nil
}

func (s *Server) RevokeInvite(ctx context.Context, req gen.RevokeInviteRequestObject) (gen.RevokeInviteResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	inv, err := s.st.GetInvite(ctx, req.FrameId, req.InviteId)
	if err != nil {
		return nil, notFound(err, "invite")
	}
	if !m.IsManager() && (inv.CreatedBy == nil || *inv.CreatedBy != m.UserID) {
		return nil, problem.New(problem.Forbidden, "only the creator or an owner/admin can revoke this invite")
	}
	if err := s.st.RevokeInvite(ctx, req.FrameId, req.InviteId); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// usableInvite resolves a token: unknown → 404 invite_invalid, used up/expired/revoked → 410.
func (s *Server) usableInvite(ctx context.Context, q db.DBTX, token string) (*store.Invite, *store.Frame, error) {
	inv, err := s.st.GetInviteByToken(ctx, q, token)
	if err == store.ErrNotFound {
		return nil, nil, problem.New(problem.InviteInvalid, "")
	}
	if err != nil {
		return nil, nil, err
	}
	f, err := s.st.GetFrame(ctx, q, inv.FrameID)
	if err == store.ErrNotFound {
		return nil, nil, problem.New(problem.InviteInvalid, "")
	}
	if err != nil {
		return nil, nil, err
	}
	if !inv.Usable(time.Now()) {
		return nil, nil, problem.New(problem.InviteExpired, "")
	}
	return inv, f, nil
}

func (s *Server) PreviewInvite(ctx context.Context, req gen.PreviewInviteRequestObject) (gen.PreviewInviteResponseObject, error) {
	inv, f, err := s.usableInvite(ctx, s.st.Q(), req.Token)
	if err != nil {
		return nil, err
	}
	owner, err := s.st.FrameOwnerName(ctx, s.st.Q(), f.ID)
	if err != nil {
		return nil, err
	}
	_, merr := s.st.GetMembership(ctx, s.st.Q(), f.ID, me(ctx).ID)
	by := ""
	if inv.CreatedByName != nil {
		by = *inv.CreatedByName
	}
	return ok(gen.InvitePreview{
		FrameName: f.Name, FrameLocation: f.Location, OwnerName: &owner, InvitedByName: by,
		RequiresApproval: f.InviteRequiresApproval, AlreadyMember: ptr(merr == nil),
	}), nil
}

func (s *Server) AcceptInvite(ctx context.Context, req gen.AcceptInviteRequestObject) (gen.AcceptInviteResponseObject, error) {
	u := me(ctx)
	var out redeemOutcome
	var jr *store.JoinRequest
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		inv, f, err := s.usableInvite(ctx, tx, req.Token)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `SELECT 1 FROM invites WHERE id = $1 FOR UPDATE`, inv.ID); err != nil {
			return err
		}
		if _, err := s.st.GetMembership(ctx, tx, f.ID, u.ID); err == nil {
			out = redeemOutcome{Status: "already_member", FrameID: f.ID}
			return nil
		} else if err != store.ErrNotFound {
			return err
		}
		if err := tx.UseInvite(ctx, inv.ID); err != nil {
			return err
		}
		if f.InviteRequiresApproval {
			r, isNew, err := tx.CreateJoinRequest(ctx, f.ID, u.ID, "invite", &inv.ID, inv.CreatedBy, inv.Note)
			if err != nil {
				return err
			}
			out = redeemOutcome{Status: "pending", FrameID: f.ID, JoinRequestID: &r.ID}
			if isNew {
				jr = r
			}
			return nil
		}
		if _, err := tx.AddMember(ctx, f.ID, u.ID, "member", f.DefaultPermissions); err != nil {
			return err
		}
		out = redeemOutcome{Status: "joined", FrameID: f.ID}
		return nil
	})
	if err != nil {
		return nil, err
	}
	if jr != nil {
		s.announceJoinRequest(ctx, out.FrameID, jr)
	}
	return s.redeemResponse(ctx, out)
}
