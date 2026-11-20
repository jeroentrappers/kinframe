package api

// Ownership by the operator (docs/spec/leaving.md §4.6, README §5.21): when a separated couple disagree about who
// owns a frame or family, the server operator moves ownership to an existing member. Every transfer and every
// account-deletion handover is in the ownership log (kept 2 years).

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

const ownershipSearchLimit = 20

func (s *Server) OperatorSearchOwnership(ctx context.Context, req gen.OperatorSearchOwnershipRequestObject) (gen.OperatorSearchOwnershipResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	q := strings.TrimSpace(req.Params.Q)
	if utf8.RuneCountInString(q) < 2 || utf8.RuneCountInString(q) > 200 {
		return nil, problem.Validation("q", "length", "search with 2 to 200 characters")
	}
	frames, feeds, err := s.st.SearchOwnership(ctx, q, ownershipSearchLimit)
	if err != nil {
		return nil, err
	}
	out := gen.OperatorOwnershipMatches{Frames: []gen.OperatorScope{}, Feeds: []gen.OperatorScope{}}
	if out.Frames, err = s.operatorScopes(ctx, frames, false); err != nil {
		return nil, err
	}
	if err := s.remoteHelpSupported(ctx, out.Frames); err != nil {
		return nil, err
	}
	if out.Feeds, err = s.operatorScopes(ctx, feeds, true); err != nil {
		return nil, err
	}
	return ok(out), nil
}

func (s *Server) operatorScopes(ctx context.Context, scopes []store.OperatorScope, feed bool) ([]gen.OperatorScope, error) {
	out := make([]gen.OperatorScope, 0, len(scopes))
	if len(scopes) == 0 {
		return out, nil
	}
	ids := make([]uuid.UUID, len(scopes))
	for i, sc := range scopes {
		ids[i] = sc.ID
	}
	members, err := s.st.OperatorMembers(ctx, ids, feed)
	if err != nil {
		return nil, err
	}
	by := map[uuid.UUID][]gen.OperatorScopeMember{}
	for _, m := range members {
		by[m.ScopeID] = append(by[m.ScopeID], gen.OperatorScopeMember{UserId: m.UserID, DisplayName: m.DisplayName, Email: m.Email, Role: m.Role})
	}
	kind := gen.OperatorScopeKindFrame
	if feed {
		kind = gen.OperatorScopeKindFeed
	}
	for _, sc := range scopes {
		ms := by[sc.ID]
		if ms == nil {
			ms = []gen.OperatorScopeMember{}
		}
		out = append(out, gen.OperatorScope{Kind: kind, Id: sc.ID, Name: sc.Name, CreatedAt: ptr(sc.CreatedAt), Members: ms})
	}
	return out, nil
}

func ownerTransferReason(b *gen.OperatorOwnerTransfer) (string, error) {
	r := strings.TrimSpace(b.Reason)
	if r == "" || utf8.RuneCountInString(r) > 500 {
		return "", problem.Validation("reason", "length", "give a reason of at most 500 characters")
	}
	return r, nil
}

// ownershipPushes are ownership_changed for the new and the old owner.
func ownershipPushes(name string, ids map[string]string, newOwner, oldOwner uuid.UUID, newName string) map[uuid.UUID]*notify.Push {
	out := map[uuid.UUID]*notify.Push{
		newOwner: {Key: "ownership_changed", Text: "ownership_changed_new", Args: map[string]string{"place": name}, IDs: ids},
	}
	if oldOwner != uuid.Nil && oldOwner != newOwner {
		out[oldOwner] = &notify.Push{Key: "ownership_changed", Text: "ownership_changed_old", Args: map[string]string{"place": name, "owner": newName}, IDs: ids}
	}
	return out
}

func (s *Server) OperatorTransferFrameOwner(ctx context.Context, req gen.OperatorTransferFrameOwnerRequestObject) (gen.OperatorTransferFrameOwnerResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	reason, err := ownerTransferReason(req.Body)
	if err != nil {
		return nil, err
	}
	op := me(ctx)
	target := req.Body.UserId
	removePrev := req.Body.RemovePrevious != nil && *req.Body.RemovePrevious
	var name, targetName string
	var old uuid.UUID
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		f, err := tx.LockFrame(ctx, req.FrameId)
		if err != nil {
			return notFound(err, "frame")
		}
		name = f.Name
		tm, err := s.st.GetMemberView(ctx, tx, req.FrameId, target)
		if err == store.ErrNotFound {
			return problem.Validation("user_id", "not_member", "the new owner must be a member of the frame")
		} else if err != nil {
			return err
		}
		if tm.Role == "owner" {
			return problem.Validation("user_id", "already_owner", "this person already owns it")
		}
		targetName = tm.DisplayName
		old, err = s.st.FrameOwnerID(ctx, tx, req.FrameId)
		if err != nil && err != store.ErrNotFound {
			return err
		}
		var oldName *string
		if old != uuid.Nil {
			if om, err := s.st.GetMemberView(ctx, tx, req.FrameId, old); err == nil {
				oldName = &om.DisplayName
			}
			if removePrev {
				// Removing a member never takes photos away (README §5.21).
				if err := s.removeMember(ctx, tx, req.FrameId, old, false); err != nil {
					return err
				}
			} else {
				admin := "admin"
				if _, err := tx.UpdateMember(ctx, req.FrameId, old, &admin, nil); err != nil {
					return err
				}
			}
		}
		owner := "owner"
		if _, err := tx.UpdateMember(ctx, req.FrameId, target, &owner, nil); err != nil {
			return err
		}
		if err := tx.FeedPut(ctx, req.FrameId, store.FeedFrame, req.FrameId, false); err != nil {
			return err
		}
		kind := "frame"
		return tx.LogOwnership(ctx, store.OwnershipLogEntry{Kind: "operator_transfer", ScopeKind: &kind, FrameID: &req.FrameId, ScopeName: &name,
			FromUserID: nilIfZero(old), FromName: oldName, ToUserID: &target, ToName: &targetName, OperatorUserID: &op.ID,
			OperatorName: &op.DisplayName, Reason: &reason, RemovedPrevious: ptr(removePrev && old != uuid.Nil), CreatedAt: s.now()})
	})
	if err != nil {
		return nil, err
	}
	ids := map[string]string{"frame_id": req.FrameId.String()}
	ev := notify.Event{"type": "membership.updated", "frame_id": req.FrameId}
	for u, p := range ownershipPushes(name, ids, target, old, targetName) {
		s.notify.User(u, ev, p)
	}
	return noContent(), nil
}

func (s *Server) OperatorTransferFeedOwner(ctx context.Context, req gen.OperatorTransferFeedOwnerRequestObject) (gen.OperatorTransferFeedOwnerResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	reason, err := ownerTransferReason(req.Body)
	if err != nil {
		return nil, err
	}
	op := me(ctx)
	target := req.Body.UserId
	removePrev := req.Body.RemovePrevious != nil && *req.Body.RemovePrevious
	var name, targetName string
	var old uuid.UUID
	var unlinked []store.UnlinkedBirthday
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		unlinked = nil
		f, err := tx.LockFeed(ctx, req.FeedId)
		if err != nil {
			return notFound(err, "feed")
		}
		name = f.Name
		tm, err := s.st.GetFeedMember(ctx, tx, req.FeedId, target)
		if err == store.ErrNotFound {
			return problem.Validation("user_id", "not_member", "the new owner must be a member of the feed")
		} else if err != nil {
			return err
		}
		if tm.Role == "owner" {
			return problem.Validation("user_id", "already_owner", "this person already owns it")
		}
		targetName = tm.DisplayName
		old, err = s.st.FeedOwnerID(ctx, tx, req.FeedId)
		if err != nil && err != store.ErrNotFound {
			return err
		}
		var oldName *string
		if old != uuid.Nil {
			if om, err := s.st.GetFeedMember(ctx, tx, req.FeedId, old); err == nil {
				oldName = &om.DisplayName
			}
			if removePrev {
				// Removing a member never takes photos away (README §5.21); their birthday entry stays, unlinked.
				if err := tx.RemoveFeedMember(ctx, req.FeedId, old); err != nil {
					return err
				}
				if unlinked, err = tx.UnlinkBirthdays(ctx, &req.FeedId, old); err != nil {
					return err
				}
			} else if err := tx.SetFeedMemberRole(ctx, req.FeedId, old, "admin"); err != nil {
				return err
			}
		}
		if err := tx.SetFeedMemberRole(ctx, req.FeedId, target, "owner"); err != nil {
			return err
		}
		kind := "feed"
		return tx.LogOwnership(ctx, store.OwnershipLogEntry{Kind: "operator_transfer", ScopeKind: &kind, FeedID: &req.FeedId, ScopeName: &name,
			FromUserID: nilIfZero(old), FromName: oldName, ToUserID: &target, ToName: &targetName, OperatorUserID: &op.ID,
			OperatorName: &op.DisplayName, Reason: &reason, RemovedPrevious: ptr(removePrev && old != uuid.Nil), CreatedAt: s.now()})
	})
	if err != nil {
		return nil, err
	}
	s.publishUnlinked(ctx, unlinked)
	ids := map[string]string{"feed_id": req.FeedId.String()}
	ev := notify.Event{"type": "feed.membership_updated", "feed_id": req.FeedId}
	for u, p := range ownershipPushes(name, ids, target, old, targetName) {
		s.notify.User(u, ev, p)
	}
	return noContent(), nil
}

func nilIfZero(id uuid.UUID) *uuid.UUID {
	if id == uuid.Nil {
		return nil
	}
	return &id
}

type ownershipCursor struct {
	At time.Time `json:"t"`
	ID uuid.UUID `json:"id"`
}

func (s *Server) OperatorListOwnershipLog(ctx context.Context, req gen.OperatorListOwnershipLogRequestObject) (gen.OperatorListOwnershipLogResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	limit := pageLimit(req.Params.Limit)
	var afterAt *time.Time
	var afterID *uuid.UUID
	if req.Params.Cursor != nil && *req.Params.Cursor != "" {
		var c ownershipCursor
		b, err := base64.RawURLEncoding.DecodeString(*req.Params.Cursor)
		if err != nil || json.Unmarshal(b, &c) != nil {
			return nil, problem.Validation("cursor", "format", "invalid cursor")
		}
		afterAt, afterID = &c.At, &c.ID
	}
	rows, err := s.st.ListOwnershipLog(ctx, afterAt, afterID, limit+1)
	if err != nil {
		return nil, err
	}
	out := gen.OwnershipLogPage{Entries: []gen.OwnershipLogEntry{}}
	if len(rows) > limit {
		rows = rows[:limit]
		last := rows[len(rows)-1]
		b, _ := json.Marshal(ownershipCursor{At: last.CreatedAt, ID: last.ID})
		out.NextCursor = ptr(base64.RawURLEncoding.EncodeToString(b))
	}
	for _, r := range rows {
		e := gen.OwnershipLogEntry{Id: r.ID, Kind: gen.OwnershipLogEntryKind(r.Kind), At: r.CreatedAt, ScopeName: r.ScopeName,
			FromName: r.FromName, ToName: r.ToName, OperatorName: r.OperatorName, Reason: r.Reason, RemovedPrevious: r.RemovedPrevious,
			ItemCount: r.ItemCount}
		if r.ScopeKind != nil {
			e.ScopeKind = ptr(gen.OwnershipLogEntryScopeKind(*r.ScopeKind))
		}
		if r.FrameID != nil {
			e.ScopeId = r.FrameID
		} else if r.FeedID != nil {
			e.ScopeId = r.FeedID
		}
		out.Entries = append(out.Entries, e)
	}
	return ok(out), nil
}
