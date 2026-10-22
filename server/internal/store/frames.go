package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Permissions per membership (README §4).
type Permissions struct {
	Send           bool `json:"send"`
	Invite         bool `json:"invite"`
	SeeLibrary     bool `json:"see_library"`
	ManageLibrary  bool `json:"manage_library"`
	ChangeSettings bool `json:"change_settings"`
}

// AllPermissions is what owners and admins effectively have.
var AllPermissions = Permissions{true, true, true, true, true}

// DefaultMemberPermissions are a new frame's default_permissions.
var DefaultMemberPermissions = Permissions{Send: true}

type Frame struct {
	ID                     uuid.UUID       `db:"id"`
	Name                   string          `db:"name"`
	Location               *string         `db:"location"`
	DeviceID               *uuid.UUID      `db:"device_id"`
	DefaultPermissions     Permissions     `db:"default_permissions"`
	ApproveJoinsOnFrame    bool            `db:"approve_joins_on_frame"`
	InviteRequiresApproval bool            `db:"invite_requires_approval"`
	Moderation             bool            `db:"moderation"`
	Settings               json.RawMessage `db:"settings"`
	SettingsVersion        int             `db:"settings_version"`
	SettingsFieldVersions  map[string]int  `db:"settings_field_versions"`
	LatestSeq              int64           `db:"latest_seq"`
	FeedFloorSeq           int64           `db:"feed_floor_seq"`
	CreatedAt              time.Time       `db:"created_at"`
	DeletedAt              *time.Time      `db:"deleted_at"`
}

const frameCols = `id, name, location, device_id, default_permissions, approve_joins_on_frame, invite_requires_approval,
	moderation, settings, settings_version, settings_field_versions, latest_seq, feed_floor_seq, created_at, deleted_at`

func (tx *Tx) CreateFrame(ctx context.Context, f Frame) (*Frame, error) {
	return one[Frame](tx.Query(ctx, `
		INSERT INTO frames (id, name, device_id, default_permissions, settings) VALUES ($1, $2, $3, $4, $5)
		RETURNING `+frameCols, f.ID, f.Name, f.DeviceID, f.DefaultPermissions, f.Settings))
}

// GetFrame returns a live (not removed) frame.
func (s *Store) GetFrame(ctx context.Context, q db.DBTX, id uuid.UUID) (*Frame, error) {
	return one[Frame](q.Query(ctx, `SELECT `+frameCols+` FROM frames WHERE id = $1 AND deleted_at IS NULL`, id))
}

// LockFrame returns the frame with a row lock held until the transaction ends.
func (tx *Tx) LockFrame(ctx context.Context, id uuid.UUID) (*Frame, error) {
	return one[Frame](tx.Query(ctx, `SELECT `+frameCols+` FROM frames WHERE id = $1 AND deleted_at IS NULL FOR UPDATE`, id))
}

type FramePatch struct {
	Name                   *string
	Location               *string
	ClearLocation          bool
	DefaultPermissions     *Permissions
	ApproveJoinsOnFrame    *bool
	InviteRequiresApproval *bool
	Moderation             *bool
}

func (tx *Tx) UpdateFrame(ctx context.Context, id uuid.UUID, p FramePatch) (*Frame, error) {
	return one[Frame](tx.Query(ctx, `
		UPDATE frames SET name = COALESCE($2, name),
			location = CASE WHEN $3 THEN NULL ELSE COALESCE($4, location) END,
			default_permissions = COALESCE($5, default_permissions),
			approve_joins_on_frame = COALESCE($6, approve_joins_on_frame),
			invite_requires_approval = COALESCE($7, invite_requires_approval),
			moderation = COALESCE($8, moderation)
		WHERE id = $1 RETURNING `+frameCols,
		id, p.Name, p.ClearLocation, p.Location, p.DefaultPermissions, p.ApproveJoinsOnFrame, p.InviteRequiresApproval, p.Moderation))
}

func (tx *Tx) SetFrameSettings(ctx context.Context, id uuid.UUID, settings json.RawMessage, version int, fieldVersions map[string]int) error {
	_, err := tx.Exec(ctx, `UPDATE frames SET settings = $2, settings_version = $3, settings_field_versions = $4 WHERE id = $1`,
		id, settings, version, fieldVersions)
	return err
}

func (tx *Tx) AttachDevice(ctx context.Context, frameID, deviceID uuid.UUID) error {
	if _, err := tx.Exec(ctx, `UPDATE frames SET device_id = $2 WHERE id = $1`, frameID, deviceID); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `UPDATE devices SET frame_id = $2 WHERE id = $1`, deviceID, frameID)
	return err
}

func (tx *Tx) MarkFrameDeleted(ctx context.Context, id uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE frames SET deleted_at = now(), device_id = NULL WHERE id = $1`, id)
	return err
}

// FrameView is a frame joined with the caller's membership and owner info.
type FrameView struct {
	Frame
	Role           string      `db:"role"`
	Permissions    Permissions `db:"permissions"`
	OwnerName      *string     `db:"owner_name"`
	OwnerID        *uuid.UUID  `db:"owner_id"`
	AppVersion     *string     `db:"app_version"`
	AppVersionCode *int        `db:"app_version_code"`
	ScreenW        *int        `db:"screen_w"`
	ScreenH        *int        `db:"screen_h"`
	DensityDPI     *int        `db:"density_dpi"`
	Orientation    *string     `db:"orientation"`
	LastSeenAt     *time.Time  `db:"last_seen_at"`
	MemberCount    int         `db:"member_count"`
	PlacementCount int         `db:"placement_count"`
}

const frameViewSelect = `
	SELECT f.id, f.name, f.location, f.device_id, f.default_permissions, f.approve_joins_on_frame, f.invite_requires_approval,
		f.moderation, f.settings, f.settings_version, f.settings_field_versions, f.latest_seq, f.feed_floor_seq, f.created_at, f.deleted_at,
		m.role, m.permissions,
		ou.display_name AS owner_name, ou.id AS owner_id,
		d.app_version, d.app_version_code, d.screen_w, d.screen_h, d.density_dpi, d.orientation, d.last_seen_at,
		(SELECT count(*) FROM memberships mm WHERE mm.frame_id = f.id)::int AS member_count,
		(SELECT count(*) FROM placements p WHERE p.frame_id = f.id AND p.removed_at IS NULL)::int AS placement_count
	FROM frames f
	JOIN memberships m ON m.frame_id = f.id AND m.user_id = $1
	LEFT JOIN memberships om ON om.frame_id = f.id AND om.role = 'owner'
	LEFT JOIN users ou ON ou.id = om.user_id
	LEFT JOIN devices d ON d.id = f.device_id
	WHERE f.deleted_at IS NULL`

func (s *Store) ListFramesForUser(ctx context.Context, userID uuid.UUID) ([]FrameView, error) {
	return many[FrameView](s.Pool.Query(ctx, frameViewSelect+` ORDER BY f.name, f.id`, userID))
}

func (s *Store) GetFrameView(ctx context.Context, q db.DBTX, userID, frameID uuid.UUID) (*FrameView, error) {
	return one[FrameView](q.Query(ctx, frameViewSelect+` AND f.id = $2`, userID, frameID))
}

// FrameOwnerName returns the display name of the frame's owner.
func (s *Store) FrameOwnerName(ctx context.Context, q db.DBTX, frameID uuid.UUID) (string, error) {
	var name string
	err := q.QueryRow(ctx, `SELECT u.display_name FROM memberships m JOIN users u ON u.id = m.user_id WHERE m.frame_id = $1 AND m.role = 'owner'`, frameID).Scan(&name)
	if db.NotFound(err) {
		return "", nil
	}
	return name, err
}

// ---------------------------------------------------------------- memberships

type Membership struct {
	FrameID     uuid.UUID   `db:"frame_id"`
	UserID      uuid.UUID   `db:"user_id"`
	Role        string      `db:"role"`
	Permissions Permissions `db:"permissions"`
	JoinedAt    time.Time   `db:"joined_at"`
	LastSentAt  *time.Time  `db:"last_sent_at"`
}

const memberCols = `frame_id, user_id, role, permissions, joined_at, last_sent_at`

// Effective returns the permissions after applying the role.
func (m Membership) Effective() Permissions {
	if m.Role == "owner" || m.Role == "admin" {
		return AllPermissions
	}
	return m.Permissions
}

func (m Membership) IsManager() bool { return m.Role == "owner" || m.Role == "admin" }

func (s *Store) GetMembership(ctx context.Context, q db.DBTX, frameID, userID uuid.UUID) (*Membership, error) {
	return one[Membership](q.Query(ctx, `
		SELECT m.frame_id, m.user_id, m.role, m.permissions, m.joined_at, m.last_sent_at
		FROM memberships m JOIN frames f ON f.id = m.frame_id AND f.deleted_at IS NULL
		WHERE m.frame_id = $1 AND m.user_id = $2`, frameID, userID))
}

func (tx *Tx) AddMember(ctx context.Context, frameID, userID uuid.UUID, role string, perms Permissions) (*Membership, error) {
	m, err := one[Membership](tx.Query(ctx, `
		INSERT INTO memberships (frame_id, user_id, role, permissions) VALUES ($1, $2, $3, $4)
		ON CONFLICT (frame_id, user_id) DO UPDATE SET role = memberships.role
		RETURNING `+memberCols, frameID, userID, role, perms))
	if err != nil {
		return nil, err
	}
	return m, tx.FeedPut(ctx, frameID, FeedMember, userID, false)
}

func (tx *Tx) UpdateMember(ctx context.Context, frameID, userID uuid.UUID, role *string, perms *Permissions) (*Membership, error) {
	m, err := one[Membership](tx.Query(ctx, `
		UPDATE memberships SET role = COALESCE($3, role), permissions = COALESCE($4, permissions)
		WHERE frame_id = $1 AND user_id = $2 RETURNING `+memberCols, frameID, userID, role, perms))
	if err != nil {
		return nil, err
	}
	return m, tx.FeedPut(ctx, frameID, FeedMember, userID, false)
}

func (tx *Tx) RemoveMember(ctx context.Context, frameID, userID uuid.UUID) error {
	if _, err := tx.Exec(ctx, `DELETE FROM memberships WHERE frame_id = $1 AND user_id = $2`, frameID, userID); err != nil {
		return err
	}
	return tx.FeedPut(ctx, frameID, FeedMember, userID, true)
}

func (tx *Tx) TouchLastSent(ctx context.Context, frameIDs []uuid.UUID, userID uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE memberships SET last_sent_at = now() WHERE user_id = $1 AND frame_id = ANY($2)`, userID, frameIDs)
	return err
}

// MemberView is a membership with user display info.
type MemberView struct {
	Membership
	DisplayName string  `db:"display_name"`
	AvatarKey   *string `db:"avatar_key"`
}

const memberViewSelect = `
	SELECT m.frame_id, m.user_id, m.role, m.permissions, m.joined_at, m.last_sent_at, u.display_name, u.avatar_key
	FROM memberships m JOIN users u ON u.id = m.user_id`

func (s *Store) ListMembers(ctx context.Context, q db.DBTX, frameID uuid.UUID) ([]MemberView, error) {
	return many[MemberView](q.Query(ctx, memberViewSelect+` WHERE m.frame_id = $1
		ORDER BY CASE m.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END, m.joined_at`, frameID))
}

func (s *Store) GetMemberView(ctx context.Context, q db.DBTX, frameID, userID uuid.UUID) (*MemberView, error) {
	return one[MemberView](q.Query(ctx, memberViewSelect+` WHERE m.frame_id = $1 AND m.user_id = $2`, frameID, userID))
}

// MemberIDs lists user IDs of a frame's members, optionally only managers.
func (s *Store) MemberIDs(ctx context.Context, frameID uuid.UUID, managersOnly bool) ([]uuid.UUID, error) {
	rows, err := s.Pool.Query(ctx, `SELECT user_id FROM memberships WHERE frame_id = $1 AND ($2 = false OR role IN ('owner','admin'))`, frameID, managersOnly)
	if err != nil {
		return nil, err
	}
	var out []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// FramesOfUser lists frame IDs where the user is a member, with role.
func (s *Store) FramesOfUser(ctx context.Context, q db.DBTX, userID uuid.UUID) ([]Membership, error) {
	return many[Membership](q.Query(ctx, `SELECT `+memberCols+` FROM memberships WHERE user_id = $1`, userID))
}

// ---------------------------------------------------------------- invites

type Invite struct {
	ID            uuid.UUID  `db:"id"`
	FrameID       uuid.UUID  `db:"frame_id"`
	Token         string     `db:"token"`
	CreatedBy     *uuid.UUID `db:"created_by"`
	CreatedByName *string    `db:"created_by_name"`
	MaxUses       *int       `db:"max_uses"`
	Uses          int        `db:"uses"`
	Note          *string    `db:"note"`
	ExpiresAt     time.Time  `db:"expires_at"`
	RevokedAt     *time.Time `db:"revoked_at"`
}

const inviteSelect = `SELECT i.id, i.frame_id, i.token, i.created_by, u.display_name AS created_by_name, i.max_uses, i.uses, i.note,
	i.expires_at, i.revoked_at FROM invites i LEFT JOIN users u ON u.id = i.created_by`

// Usable reports whether the invite can still be accepted.
func (i Invite) Usable(now time.Time) bool {
	return i.RevokedAt == nil && now.Before(i.ExpiresAt) && (i.MaxUses == nil || i.Uses < *i.MaxUses)
}

func (s *Store) CreateInvite(ctx context.Context, q db.DBTX, inv Invite) (*Invite, error) {
	if _, err := q.Exec(ctx, `INSERT INTO invites (id, frame_id, token, created_by, max_uses, note, expires_at) VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		inv.ID, inv.FrameID, inv.Token, inv.CreatedBy, inv.MaxUses, inv.Note, inv.ExpiresAt); err != nil {
		return nil, err
	}
	return one[Invite](q.Query(ctx, inviteSelect+` WHERE i.id = $1`, inv.ID))
}

// ListActiveInvites lists usable invites; createdBy filters unless nil.
func (s *Store) ListActiveInvites(ctx context.Context, frameID uuid.UUID, createdBy *uuid.UUID) ([]Invite, error) {
	return many[Invite](s.Pool.Query(ctx, inviteSelect+`
		WHERE i.frame_id = $1 AND i.revoked_at IS NULL AND i.expires_at > now() AND (i.max_uses IS NULL OR i.uses < i.max_uses)
		AND ($2::uuid IS NULL OR i.created_by = $2) ORDER BY i.id DESC`, frameID, createdBy))
}

func (s *Store) GetInviteByToken(ctx context.Context, q db.DBTX, token string) (*Invite, error) {
	return one[Invite](q.Query(ctx, inviteSelect+` WHERE i.token = $1`, token))
}

func (s *Store) GetInvite(ctx context.Context, frameID, id uuid.UUID) (*Invite, error) {
	return one[Invite](s.Pool.Query(ctx, inviteSelect+` WHERE i.frame_id = $1 AND i.id = $2`, frameID, id))
}

func (s *Store) RevokeInvite(ctx context.Context, frameID, id uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `UPDATE invites SET revoked_at = now() WHERE frame_id = $1 AND id = $2 AND revoked_at IS NULL`, frameID, id)
	return err
}

func (tx *Tx) UseInvite(ctx context.Context, id uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE invites SET uses = uses + 1 WHERE id = $1`, id)
	return err
}

// ---------------------------------------------------------------- join requests

type JoinRequest struct {
	ID            uuid.UUID `db:"id"`
	FrameID       uuid.UUID `db:"frame_id"`
	UserID        uuid.UUID `db:"user_id"`
	UserName      string    `db:"user_name"`
	UserAvatarKey *string   `db:"user_avatar_key"`
	Via           string    `db:"via"`
	InvitedByName *string   `db:"invited_by_name"`
	Note          *string   `db:"note"`
	Status        string    `db:"status"`
	CreatedAt     time.Time `db:"created_at"`
}

const joinSelect = `SELECT j.id, j.frame_id, j.user_id, u.display_name AS user_name, u.avatar_key AS user_avatar_key, j.via,
	ib.display_name AS invited_by_name, j.note, j.status, j.created_at
	FROM join_requests j JOIN users u ON u.id = j.user_id LEFT JOIN users ib ON ib.id = j.invited_by`

// CreateJoinRequest creates a pending request or returns the existing pending one.
func (tx *Tx) CreateJoinRequest(ctx context.Context, frameID, userID uuid.UUID, via string, inviteID, invitedBy *uuid.UUID, note *string) (*JoinRequest, bool, error) {
	existing, err := one[JoinRequest](tx.Query(ctx, joinSelect+` WHERE j.frame_id = $1 AND j.user_id = $2 AND j.status = 'pending'`, frameID, userID))
	if err == nil {
		return existing, false, nil
	}
	if err != ErrNotFound {
		return nil, false, err
	}
	id := NewID()
	if _, err := tx.Exec(ctx, `INSERT INTO join_requests (id, frame_id, user_id, via, invite_id, invited_by, note) VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		id, frameID, userID, via, inviteID, invitedBy, note); err != nil {
		return nil, false, err
	}
	jr, err := one[JoinRequest](tx.Query(ctx, joinSelect+` WHERE j.id = $1`, id))
	return jr, true, err
}

func (s *Store) ListPendingJoinRequests(ctx context.Context, frameID uuid.UUID) ([]JoinRequest, error) {
	return many[JoinRequest](s.Pool.Query(ctx, joinSelect+` WHERE j.frame_id = $1 AND j.status = 'pending' ORDER BY j.created_at`, frameID))
}

// LockPendingJoinRequest returns a pending request of the frame, locked.
func (tx *Tx) LockPendingJoinRequest(ctx context.Context, frameID, id uuid.UUID) (*JoinRequest, error) {
	if _, err := tx.Exec(ctx, `SELECT 1 FROM join_requests WHERE id = $1 FOR UPDATE`, id); err != nil {
		return nil, err
	}
	return one[JoinRequest](tx.Query(ctx, joinSelect+` WHERE j.id = $1 AND j.frame_id = $2 AND j.status = 'pending'`, id, frameID))
}

func (tx *Tx) DecideJoinRequest(ctx context.Context, id uuid.UUID, status string) error {
	_, err := tx.Exec(ctx, `UPDATE join_requests SET status = $2, decided_at = now() WHERE id = $1`, id, status)
	return err
}
