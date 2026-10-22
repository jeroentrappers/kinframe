package api

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

func ptr[T any](v T) *T { return &v }

// avatarURL is a public, unguessable URL (the key contains a random part).
func (s *Server) avatarURL(key *string) *string {
	if key == nil {
		return nil
	}
	return ptr(s.cfg.PublicURL + "/" + *key)
}

// signedURL returns an absolute, short-lived URL for a blob key.
func (s *Server) signedURL(key string, ttl time.Duration) string {
	return s.cfg.PublicURL + s.signer.Sign("/v1/media/signed/"+key, ttl)
}

// Signed URLs are valid for 1-2 h (expiry is rounded up for cacheability).
const thumbURLTTL = time.Hour

func (s *Server) userJSON(ctx context.Context, u *store.User) gen.User {
	return gen.User{
		Id:                 u.ID,
		DisplayName:        u.DisplayName,
		Email:              u.Email,
		AvatarUrl:          s.avatarURL(u.AvatarKey),
		ShareLocationNames: ptr(u.ShareLocationNames),
		Locale:             u.Locale,
		IsOperator:         ptr(isOperator(ctx)),
		CreatedAt:          ptr(u.CreatedAt),
	}
}

func permsJSON(p store.Permissions) gen.Permissions {
	return gen.Permissions{
		Send: ptr(p.Send), Invite: ptr(p.Invite), SeeLibrary: ptr(p.SeeLibrary),
		ManageLibrary: ptr(p.ManageLibrary), ChangeSettings: ptr(p.ChangeSettings),
	}
}

// permsFromJSON overlays a (partial) API permissions object onto base.
func permsFromJSON(base store.Permissions, p gen.Permissions) store.Permissions {
	set := func(dst *bool, src *bool) {
		if src != nil {
			*dst = *src
		}
	}
	set(&base.Send, p.Send)
	set(&base.Invite, p.Invite)
	set(&base.SeeLibrary, p.SeeLibrary)
	set(&base.ManageLibrary, p.ManageLibrary)
	set(&base.ChangeSettings, p.ChangeSettings)
	return base
}

func (s *Server) frameSummary(v *store.FrameView) gen.FrameSummary {
	m := store.Membership{Role: v.Role, Permissions: v.Permissions}
	fs := gen.FrameSummary{
		Id:          v.ID,
		Name:        v.Name,
		Location:    v.Location,
		OwnerName:   v.OwnerName,
		Role:        gen.Role(v.Role),
		Permissions: permsJSON(m.Effective()),
		Online:      s.hub.FrameOnline(v.ID),
		LastSeenAt:  v.LastSeenAt,
		AppVersion:  v.AppVersion,
		NeedsUpdate: ptr(v.AppVersionCode != nil && s.cfg.MinFrameAppVersion > 0 && *v.AppVersionCode < s.cfg.MinFrameAppVersion),
	}
	if fs.Online {
		fs.LastSeenAt = ptr(time.Now().UTC().Truncate(time.Second))
	}
	if v.ScreenW != nil && v.ScreenH != nil {
		sc := gen.Screen{Width: *v.ScreenW, Height: *v.ScreenH, DensityDpi: v.DensityDPI}
		if v.Orientation != nil {
			sc.Orientation = ptr(gen.ScreenOrientation(*v.Orientation))
		}
		fs.Screen = &sc
	}
	return fs
}

func (s *Server) frameJSON(v *store.FrameView) gen.Frame {
	fs := s.frameSummary(v)
	return gen.Frame{
		Id: fs.Id, Name: fs.Name, Location: fs.Location, OwnerName: fs.OwnerName, Role: fs.Role,
		Permissions: fs.Permissions, Online: fs.Online, LastSeenAt: fs.LastSeenAt, Screen: fs.Screen,
		AppVersion: fs.AppVersion, NeedsUpdate: fs.NeedsUpdate,
		DefaultPermissions:     ptr(permsJSON(v.DefaultPermissions)),
		ApproveJoinsOnFrame:    ptr(v.ApproveJoinsOnFrame),
		InviteRequiresApproval: ptr(v.InviteRequiresApproval),
		Moderation:             ptr(v.Moderation),
		MemberCount:            ptr(v.MemberCount),
		PlacementCount:         ptr(v.PlacementCount),
		CreatedAt:              ptr(v.CreatedAt),
	}
}

func (s *Server) memberJSON(m *store.MemberView) gen.Member {
	return gen.Member{
		UserId:      m.UserID,
		DisplayName: m.DisplayName,
		AvatarUrl:   s.avatarURL(m.AvatarKey),
		Role:        gen.Role(m.Role),
		Permissions: permsJSON(m.Effective()),
		JoinedAt:    ptr(m.JoinedAt),
		LastSentAt:  m.LastSentAt,
	}
}

func (s *Server) joinRequestJSON(j *store.JoinRequest) gen.JoinRequest {
	out := gen.JoinRequest{
		Id: j.ID, Via: gen.JoinRequestVia(j.Via), InvitedByName: j.InvitedByName, Note: j.Note, CreatedAt: j.CreatedAt,
	}
	out.User.Id = ptr(j.UserID)
	out.User.DisplayName = ptr(j.UserName)
	out.User.AvatarUrl = s.avatarURL(j.UserAvatarKey)
	return out
}

func (s *Server) inviteJSON(i *store.Invite) gen.Invite {
	return gen.Invite{
		Id: i.ID, Token: i.Token, Url: s.cfg.PublicURL + "/i/" + i.Token, CreatedByName: i.CreatedByName,
		MaxUses: i.MaxUses, Uses: ptr(i.Uses), Note: i.Note, ExpiresAt: i.ExpiresAt,
	}
}

func senderID(it *store.ItemRow) uuid.UUID {
	switch {
	case it.SenderUserID != nil:
		return *it.SenderUserID
	case it.SenderFrameID != nil:
		return *it.SenderFrameID
	}
	return uuid.Nil
}

func (s *Server) itemJSON(it *store.ItemRow) gen.Item {
	out := gen.Item{
		Id:            it.ItemID,
		Kind:          gen.MediaKind(it.Kind),
		Status:        gen.ItemStatus(it.MediaStatus),
		FailureCode:   it.FailureCode,
		SenderId:      senderID(it),
		SenderName:    ptr(it.SenderName),
		Caption:       it.Caption,
		CaptionEdited: ptr(it.CaptionEdited),
		Width:         it.Width,
		Height:        it.Height,
		DurationMs:    it.DurationMS,
		HasAudio:      it.HasAudio,
		CapturedAt:    it.CapturedAt,
		CreatedAt:     it.ItemCreatedAt,
	}
	if out.CapturedAt == nil {
		out.CapturedAt = it.CapturedHint
	}
	if it.IsLive() {
		out.Kind = gen.Live
		mo := gen.ItemMotion{Status: gen.ItemMotionStatusProcessing}
		if *it.MotionStatus == "ready" {
			mo = gen.ItemMotion{Status: gen.ItemMotionStatusReady, Width: it.MotionWidth, Height: it.MotionHeight,
				DurationMs: it.MotionDurationMS, HasAudio: it.MotionHasAudio}
		}
		out.Motion = &mo
	}
	if it.FocalX != nil && it.FocalY != nil {
		out.FocalPoint = &gen.FocalPoint{X: float32(*it.FocalX), Y: float32(*it.FocalY)}
	}
	if it.Fit != nil {
		out.Fit = ptr(gen.Fit(*it.Fit))
	}
	if it.Thumb256Key != nil {
		out.ThumbUrl = ptr(s.signedURL(*it.Thumb256Key, thumbURLTTL))
	}
	return out
}

func (s *Server) placementJSON(p *store.PlacementRow) gen.Placement {
	out := gen.Placement{
		Id:                p.Placement.ID,
		FrameId:           p.FrameID,
		Item:              s.itemJSON(&p.ItemRow),
		Status:            gen.PlacementStatus(p.Status),
		Hidden:            p.Hidden,
		Trashed:           p.TrashedAt != nil,
		TrashedAt:         p.TrashedAt,
		PendingModeration: ptr(p.PendingModeration),
		Rotation:          ptr(gen.PlacementRotation(p.Rotation)),
		DeliveredAt:       p.DeliveredAt,
		SeenAt:            p.SeenAt,
		ReceivedAt:        ptr(p.ReceivedAt),
		AlbumIds:          ptr(p.AlbumIDs),
	}
	if p.RemovedAt != nil {
		out.Status = "removed"
	}
	if p.FitOverride != nil {
		out.FitOverride = ptr(gen.Fit(*p.FitOverride))
	}
	if p.FocalOverrideX != nil && p.FocalOverrideY != nil {
		out.FocalOverride = &gen.FocalPoint{X: float32(*p.FocalOverrideX), Y: float32(*p.FocalOverrideY)}
	}
	if p.Reaction != nil {
		out.Reaction = ptr(gen.Reaction(*p.Reaction))
	}
	replies := make([]struct {
		At     *time.Time       `json:"at,omitempty"`
		Preset *gen.ReplyPreset `json:"preset,omitempty"`
	}, len(p.Replies))
	for i, r := range p.Replies {
		replies[i].At = ptr(r.At)
		replies[i].Preset = ptr(gen.ReplyPreset(r.Preset))
	}
	out.Replies = &replies
	if out.AlbumIds != nil && *out.AlbumIds == nil {
		out.AlbumIds = &[]uuid.UUID{}
	}
	return out
}
