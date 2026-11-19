package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Feeds: docs/spec/feeds.md (product) and docs/spec/feeds-data-model.md (tables,
// materialization). This file holds the rows and their reads; feedsync.go the
// placement algorithm.

// ---------------------------------------------------------------- feeds

type Feed struct {
	ID                       uuid.UUID  `db:"id"`
	Name                     string     `db:"name"`
	CoverItemID              *uuid.UUID `db:"cover_item_id"`
	AutoApproveSubscriptions bool       `db:"auto_approve_subscriptions"`
	InviteRequiresApproval   bool       `db:"invite_requires_approval"`
	MembersSeeReactions      bool       `db:"members_see_reactions"`
	PostersCanInvite         bool       `db:"posters_can_invite"`
	PostersCanAddBirthdays   bool       `db:"posters_can_add_birthdays"`
	// Family social (README §5.18): receipts for every member, the role joiners get, who creates circles.
	MembersSeeReceipts      bool       `db:"members_see_receipts"`
	NewMembersCanPost       bool       `db:"new_members_can_post"`
	MembersCanCreateCircles bool       `db:"members_can_create_circles"`
	CreatedAt               time.Time  `db:"created_at"`
	DeletedAt               *time.Time `db:"deleted_at"`
	// Families on different servers (README §5.22): frames on other servers may follow it.
	AllowRemoteFrames bool `db:"allow_remote_frames"`
}

// FeedView is a feed with counts and the viewer's role ("" when not a member).
type FeedView struct {
	Feed
	Role                     string     `db:"role"`
	MemberCount              int        `db:"member_count"`
	SubscriptionCount        int        `db:"subscription_count"`
	PostCount                int        `db:"post_count"`
	PendingSubscriptionCount int        `db:"pending_subscription_count"`
	PendingJoinRequestCount  int        `db:"pending_join_request_count"`
	LastPostAt               *time.Time `db:"last_post_at"`
	OwnerName                *string    `db:"owner_name"`
	CoverThumbKey            *string    `db:"cover_thumb_key"`
	// Birthdays (birthdays.md): the viewer's own preference and the list's counts.
	BirthdayNotifications bool `db:"birthday_notifications"`
	BirthdayCount         int  `db:"birthday_count"`
	PendingBirthdayCount  int  `db:"pending_birthday_count"`
	// Family social: the viewer's mute (FeedMute) and the circles they can see.
	Mute        string `db:"mute"`
	CircleCount int    `db:"circle_count"`
}

// IsAdmin: owner or admin of the feed.
func (v *FeedView) IsAdmin() bool { return v.Role == "owner" || v.Role == "admin" }

const feedCols = `f.id, f.name, f.cover_item_id, f.auto_approve_subscriptions, f.invite_requires_approval,
	f.members_see_reactions, f.posters_can_invite, f.posters_can_add_birthdays,
	f.members_see_receipts, f.new_members_can_post, f.members_can_create_circles, f.created_at, f.deleted_at,
	f.allow_remote_frames`

const feedViewSelect = `SELECT ` + feedCols + `, COALESCE(m.role, '') AS role,
	(SELECT count(*) FROM feed_members x WHERE x.feed_id = f.id)::int AS member_count,
	(SELECT count(*) FROM subscriptions s WHERE s.feed_id = f.id AND s.status IN ('active', 'paused'))::int AS subscription_count,
	(SELECT count(*) FROM feed_posts p WHERE p.feed_id = f.id AND p.removed_at IS NULL)::int AS post_count,
	(SELECT count(*) FROM subscriptions s WHERE s.feed_id = f.id AND s.status = 'pending')::int AS pending_subscription_count,
	(SELECT count(*) FROM feed_join_requests j WHERE j.feed_id = f.id AND j.status = 'pending')::int AS pending_join_request_count,
	(SELECT max(p.created_at) FROM feed_posts p WHERE p.feed_id = f.id AND p.removed_at IS NULL) AS last_post_at,
	(SELECT u.display_name FROM feed_members o JOIN users u ON u.id = o.user_id WHERE o.feed_id = f.id AND o.role = 'owner') AS owner_name,
	(SELECT i.thumb512_key FROM items i WHERE i.id = f.cover_item_id) AS cover_thumb_key,
	COALESCE(m.birthday_notifications, true) AS birthday_notifications,
	(SELECT count(*) FROM feed_birthdays b WHERE b.feed_id = f.id AND b.status = 'approved')::int AS birthday_count,
	(SELECT count(*) FROM feed_birthdays b WHERE b.feed_id = f.id AND b.status = 'pending')::int AS pending_birthday_count,
	COALESCE(m.mute, 'none') AS mute,
	(SELECT count(*) FROM circles c WHERE c.feed_id = f.id AND c.deleted_at IS NULL AND (m.role IN ('owner', 'admin')
		OR EXISTS (SELECT 1 FROM circle_members cm WHERE cm.circle_id = c.id AND cm.user_id = $1)))::int AS circle_count
	FROM feeds f LEFT JOIN feed_members m ON m.feed_id = f.id AND m.user_id = $1
	WHERE f.deleted_at IS NULL`

// GetFeedView returns a live feed as seen by userID (Role "" for non-members).
func (s *Store) GetFeedView(ctx context.Context, q db.DBTX, userID, feedID uuid.UUID) (*FeedView, error) {
	return one[FeedView](q.Query(ctx, feedViewSelect+` AND f.id = $2`, userID, feedID))
}

// ListFeedsForUser lists the live feeds userID is a member of.
func (s *Store) ListFeedsForUser(ctx context.Context, userID uuid.UUID) ([]FeedView, error) {
	return many[FeedView](s.Pool.Query(ctx, feedViewSelect+` AND m.user_id IS NOT NULL ORDER BY f.name, f.id`, userID))
}

// CountOwnedFeeds counts the live feeds userID owns.
func (s *Store) CountOwnedFeeds(ctx context.Context, q db.DBTX, userID uuid.UUID) (int, error) {
	var n int
	err := q.QueryRow(ctx, `SELECT count(*) FROM feed_members m JOIN feeds f ON f.id = m.feed_id AND f.deleted_at IS NULL
		WHERE m.user_id = $1 AND m.role = 'owner'`, userID).Scan(&n)
	return n, err
}

// LockFeed returns a live feed with a row lock (NO KEY UPDATE, so inserting
// posts and subscriptions, which only take KEY SHARE, is not blocked).
func (tx *Tx) LockFeed(ctx context.Context, id uuid.UUID) (*Feed, error) {
	return one[Feed](tx.Query(ctx, `SELECT `+feedCols+` FROM feeds f WHERE f.id = $1 AND f.deleted_at IS NULL FOR NO KEY UPDATE`, id))
}

// GetFeed returns a feed (deleted ones too, so jobs can finish their work).
func (s *Store) GetFeed(ctx context.Context, q db.DBTX, id uuid.UUID) (*Feed, error) {
	return one[Feed](q.Query(ctx, `SELECT `+feedCols+` FROM feeds f WHERE f.id = $1`, id))
}

// CreateFeed inserts a feed with ownerID as its owner.
func (tx *Tx) CreateFeed(ctx context.Context, f Feed, ownerID uuid.UUID) error {
	if _, err := tx.Exec(ctx, `INSERT INTO feeds (id, name, auto_approve_subscriptions, invite_requires_approval, members_see_reactions, posters_can_invite,
		posters_can_add_birthdays, members_see_receipts, new_members_can_post, members_can_create_circles)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`, f.ID, f.Name, f.AutoApproveSubscriptions, f.InviteRequiresApproval,
		f.MembersSeeReactions, f.PostersCanInvite, f.PostersCanAddBirthdays, f.MembersSeeReceipts, f.NewMembersCanPost,
		f.MembersCanCreateCircles); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `INSERT INTO feed_members (feed_id, user_id, role) VALUES ($1, $2, 'owner')`, f.ID, ownerID)
	return err
}

type FeedPatch struct {
	Name                     *string
	CoverItemID              *uuid.UUID
	ClearCover               bool
	AutoApproveSubscriptions *bool
	InviteRequiresApproval   *bool
	MembersSeeReactions      *bool
	PostersCanInvite         *bool
	PostersCanAddBirthdays   *bool
	MembersSeeReceipts       *bool
	NewMembersCanPost        *bool
	MembersCanCreateCircles  *bool
}

func (tx *Tx) UpdateFeed(ctx context.Context, id uuid.UUID, p FeedPatch) error {
	_, err := tx.Exec(ctx, `UPDATE feeds SET name = COALESCE($2, name),
		cover_item_id = CASE WHEN $3 THEN NULL ELSE COALESCE($4, cover_item_id) END,
		auto_approve_subscriptions = COALESCE($5, auto_approve_subscriptions),
		invite_requires_approval = COALESCE($6, invite_requires_approval),
		members_see_reactions = COALESCE($7, members_see_reactions),
		posters_can_invite = COALESCE($8, posters_can_invite),
		posters_can_add_birthdays = COALESCE($9, posters_can_add_birthdays),
		members_see_receipts = COALESCE($10, members_see_receipts),
		new_members_can_post = COALESCE($11, new_members_can_post),
		members_can_create_circles = COALESCE($12, members_can_create_circles)
		WHERE id = $1`, id, p.Name, p.ClearCover, p.CoverItemID, p.AutoApproveSubscriptions, p.InviteRequiresApproval,
		p.MembersSeeReactions, p.PostersCanInvite, p.PostersCanAddBirthdays, p.MembersSeeReceipts, p.NewMembersCanPost,
		p.MembersCanCreateCircles)
	return err
}

// FeedHasLiveItem reports whether itemID is a live item of a live post of the feed.
func (s *Store) FeedHasLiveItem(ctx context.Context, q db.DBTX, feedID, itemID uuid.UUID) (bool, error) {
	var ok bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM feed_post_items pi JOIN feed_posts p ON p.id = pi.post_id
		WHERE p.feed_id = $1 AND pi.item_id = $2 AND p.removed_at IS NULL AND pi.removed_at IS NULL)`, feedID, itemID).Scan(&ok)
	return ok, err
}

// MarkFeedDeleted soft-deletes a feed; the feed row is deleted once its
// subscriptions have been swept (FinishFeedDelete).
func (tx *Tx) MarkFeedDeleted(ctx context.Context, id uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE feeds SET deleted_at = now() WHERE id = $1 AND deleted_at IS NULL`, id)
	return err
}

// FinishFeedDelete deletes a soft-deleted feed (cascading its rows) once no
// delivery rows of its subscriptions are left. Reports whether it was deleted.
func (s *Store) FinishFeedDelete(ctx context.Context, id uuid.UUID) (bool, error) {
	tag, err := s.Pool.Exec(ctx, `DELETE FROM feeds f WHERE f.id = $1 AND f.deleted_at IS NOT NULL
		AND NOT EXISTS (SELECT 1 FROM feed_deliveries d JOIN subscriptions s ON s.id = d.subscription_id WHERE s.feed_id = f.id)`, id)
	return tag.RowsAffected() > 0, err
}

// ---------------------------------------------------------------- members

type FeedMemberRow struct {
	FeedID       uuid.UUID  `db:"feed_id"`
	UserID       uuid.UUID  `db:"user_id"`
	Role         string     `db:"role"`
	JoinedAt     time.Time  `db:"joined_at"`
	LastPostedAt *time.Time `db:"last_posted_at"`
	DisplayName  string     `db:"display_name"`
	AvatarKey    *string    `db:"avatar_key"`
}

const feedMemberSelect = `SELECT m.feed_id, m.user_id, m.role, m.joined_at, m.last_posted_at, u.display_name, u.avatar_key
	FROM feed_members m JOIN users u ON u.id = m.user_id`

func (s *Store) ListFeedMembers(ctx context.Context, q db.DBTX, feedID uuid.UUID) ([]FeedMemberRow, error) {
	return many[FeedMemberRow](q.Query(ctx, feedMemberSelect+` WHERE m.feed_id = $1
		ORDER BY CASE m.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END, m.joined_at, m.user_id`, feedID))
}

func (s *Store) GetFeedMember(ctx context.Context, q db.DBTX, feedID, userID uuid.UUID) (*FeedMemberRow, error) {
	return one[FeedMemberRow](q.Query(ctx, feedMemberSelect+` WHERE m.feed_id = $1 AND m.user_id = $2`, feedID, userID))
}

// FeedAdminIDs lists the owner and admins of a feed.
func (s *Store) FeedAdminIDs(ctx context.Context, q db.DBTX, feedID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT user_id FROM feed_members WHERE feed_id = $1 AND role IN ('owner', 'admin') ORDER BY role DESC, user_id`, feedID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// FeedMemberIDs lists every member of a feed.
func (s *Store) FeedMemberIDs(ctx context.Context, q db.DBTX, feedID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT user_id FROM feed_members WHERE feed_id = $1 ORDER BY user_id`, feedID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// FeedAdminNames lists the display names of the owner (first) and admins.
func (s *Store) FeedAdminNames(ctx context.Context, q db.DBTX, feedID uuid.UUID) ([]string, error) {
	rows, err := q.Query(ctx, `SELECT u.display_name FROM feed_members m JOIN users u ON u.id = m.user_id
		WHERE m.feed_id = $1 AND m.role IN ('owner', 'admin') ORDER BY m.role DESC, m.joined_at`, feedID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowTo[string])
}

// AddFeedMember adds a member (no-op when already a member).
func (tx *Tx) AddFeedMember(ctx context.Context, feedID, userID uuid.UUID, role string) error {
	_, err := tx.Exec(ctx, `INSERT INTO feed_members (feed_id, user_id, role) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`, feedID, userID, role)
	return err
}

func (tx *Tx) SetFeedMemberRole(ctx context.Context, feedID, userID uuid.UUID, role string) error {
	_, err := tx.Exec(ctx, `UPDATE feed_members SET role = $3 WHERE feed_id = $1 AND user_id = $2`, feedID, userID, role)
	return err
}

func (tx *Tx) RemoveFeedMember(ctx context.Context, feedID, userID uuid.UUID) error {
	_, err := tx.Exec(ctx, `DELETE FROM feed_members WHERE feed_id = $1 AND user_id = $2`, feedID, userID)
	return err
}

// FeedMembershipsOfUser lists the user's feed memberships (live feeds).
func (s *Store) FeedMembershipsOfUser(ctx context.Context, q db.DBTX, userID uuid.UUID) ([]FeedMemberRow, error) {
	return many[FeedMemberRow](q.Query(ctx, feedMemberSelect+` JOIN feeds f ON f.id = m.feed_id AND f.deleted_at IS NULL
		WHERE m.user_id = $1 ORDER BY m.feed_id`, userID))
}

// ---------------------------------------------------------------- invites

type FeedInvite struct {
	ID            uuid.UUID  `db:"id"`
	FeedID        uuid.UUID  `db:"feed_id"`
	Purpose       string     `db:"purpose"`
	Token         string     `db:"token"`
	Code          string     `db:"code"`
	CreatedBy     *uuid.UUID `db:"created_by"`
	CreatedByName *string    `db:"created_by_name"`
	MaxUses       *int       `db:"max_uses"`
	Uses          int        `db:"uses"`
	Note          *string    `db:"note"`
	ExpiresAt     time.Time  `db:"expires_at"`
	RevokedAt     *time.Time `db:"revoked_at"`
	// Via, ViaRef: a channel invite (see Invite).
	Via    *string `db:"via"`
	ViaRef *string `db:"via_ref"`
	// AllowRemote: frames on other servers may use this subscribe invite (README §5.22).
	AllowRemote bool `db:"allow_remote"`
}

// Usable reports whether the invite can still be accepted.
func (i FeedInvite) Usable(now time.Time) bool {
	return i.RevokedAt == nil && now.Before(i.ExpiresAt) && (i.MaxUses == nil || i.Uses < *i.MaxUses)
}

const feedInviteSelect = `SELECT i.id, i.feed_id, i.purpose, i.token, i.code, i.created_by, u.display_name AS created_by_name,
	i.max_uses, i.uses, i.note, i.expires_at, i.revoked_at, i.via, i.via_ref, i.allow_remote FROM feed_invites i LEFT JOIN users u ON u.id = i.created_by`

// CreateFeedInvite inserts an invite; a unique violation on the code means
// the caller should retry with another code.
func (s *Store) CreateFeedInvite(ctx context.Context, q db.DBTX, inv FeedInvite) (*FeedInvite, error) {
	if _, err := q.Exec(ctx, `INSERT INTO feed_invites (id, feed_id, purpose, token, code, created_by, max_uses, note, expires_at, via, via_ref)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`,
		inv.ID, inv.FeedID, inv.Purpose, inv.Token, inv.Code, inv.CreatedBy, inv.MaxUses, inv.Note, inv.ExpiresAt, inv.Via, inv.ViaRef); err != nil {
		return nil, err
	}
	return one[FeedInvite](q.Query(ctx, feedInviteSelect+` WHERE i.id = $1`, inv.ID))
}

// ListActiveFeedInvites lists usable invites; createdBy filters unless nil. Channel invites are not listed.
func (s *Store) ListActiveFeedInvites(ctx context.Context, feedID uuid.UUID, createdBy *uuid.UUID) ([]FeedInvite, error) {
	return many[FeedInvite](s.Pool.Query(ctx, feedInviteSelect+`
		WHERE i.feed_id = $1 AND i.via IS NULL AND i.revoked_at IS NULL AND i.expires_at > now() AND (i.max_uses IS NULL OR i.uses < i.max_uses)
		AND ($2::uuid IS NULL OR i.created_by = $2) ORDER BY i.id DESC`, feedID, createdBy))
}

func (s *Store) GetFeedInvite(ctx context.Context, feedID, id uuid.UUID) (*FeedInvite, error) {
	return one[FeedInvite](s.Pool.Query(ctx, feedInviteSelect+` WHERE i.feed_id = $1 AND i.id = $2`, feedID, id))
}

func (s *Store) GetFeedInviteByToken(ctx context.Context, q db.DBTX, token string) (*FeedInvite, error) {
	return one[FeedInvite](q.Query(ctx, feedInviteSelect+` WHERE i.token = $1`, token))
}

func (s *Store) GetFeedInviteByCode(ctx context.Context, q db.DBTX, code string) (*FeedInvite, error) {
	return one[FeedInvite](q.Query(ctx, feedInviteSelect+` WHERE i.code = $1`, code))
}

func (s *Store) RevokeFeedInvite(ctx context.Context, feedID, id uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `UPDATE feed_invites SET revoked_at = now() WHERE feed_id = $1 AND id = $2 AND revoked_at IS NULL`, feedID, id)
	return err
}

// UseFeedInvite counts a use, locking the invite first.
func (tx *Tx) UseFeedInvite(ctx context.Context, id uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE feed_invites SET uses = uses + 1 WHERE id = $1`, id)
	return err
}

// LockFeedInvite takes a row lock on the invite.
func (tx *Tx) LockFeedInvite(ctx context.Context, id uuid.UUID) error {
	_, err := tx.Exec(ctx, `SELECT 1 FROM feed_invites WHERE id = $1 FOR UPDATE`, id)
	return err
}

// ---------------------------------------------------------------- join requests

const feedJoinSelect = `SELECT j.id, j.feed_id AS frame_id, j.user_id, u.display_name AS user_name, u.avatar_key AS user_avatar_key,
	j.via, ib.display_name AS invited_by_name, j.note, j.status, j.created_at, j.via_name, j.via_matches
	FROM feed_join_requests j JOIN users u ON u.id = j.user_id LEFT JOIN users ib ON ib.id = j.invited_by`

// CreateFeedJoinRequest creates a pending request or returns the existing pending
// one. The returned JoinRequest's FrameID holds the feed id.
func (tx *Tx) CreateFeedJoinRequest(ctx context.Context, feedID, userID uuid.UUID, inviteID, invitedBy *uuid.UUID, note *string) (*JoinRequest, bool, error) {
	existing, err := one[JoinRequest](tx.Query(ctx, feedJoinSelect+` WHERE j.feed_id = $1 AND j.user_id = $2 AND j.status = 'pending'`, feedID, userID))
	if err == nil {
		return existing, false, nil
	}
	if err != ErrNotFound {
		return nil, false, err
	}
	id := NewID()
	if _, err := tx.Exec(ctx, `INSERT INTO feed_join_requests (id, feed_id, user_id, invite_id, invited_by, note) VALUES ($1, $2, $3, $4, $5, $6)`,
		id, feedID, userID, inviteID, invitedBy, note); err != nil {
		return nil, false, err
	}
	jr, err := one[JoinRequest](tx.Query(ctx, feedJoinSelect+` WHERE j.id = $1`, id))
	return jr, true, err
}

func (s *Store) ListPendingFeedJoinRequests(ctx context.Context, feedID uuid.UUID) ([]JoinRequest, error) {
	return many[JoinRequest](s.Pool.Query(ctx, feedJoinSelect+` WHERE j.feed_id = $1 AND j.status = 'pending' ORDER BY j.created_at`, feedID))
}

// GetFeedJoinRequest returns a request of the feed, locked.
func (tx *Tx) LockFeedJoinRequest(ctx context.Context, feedID, id uuid.UUID) (*JoinRequest, error) {
	if _, err := tx.Exec(ctx, `SELECT 1 FROM feed_join_requests WHERE id = $1 FOR UPDATE`, id); err != nil {
		return nil, err
	}
	return one[JoinRequest](tx.Query(ctx, feedJoinSelect+` WHERE j.id = $1 AND j.feed_id = $2`, id, feedID))
}

// SetFeedJoinRequestChannel marks a feed join request as coming from an outside channel.
func (tx *Tx) SetFeedJoinRequestChannel(ctx context.Context, id uuid.UUID, c JoinChannel) (*JoinRequest, error) {
	if _, err := tx.Exec(ctx, `UPDATE feed_join_requests SET via = $2, via_name = $3, via_ref = $4, via_matches = $5 WHERE id = $1`,
		id, c.Via, c.Name, c.Ref, c.Matches); err != nil {
		return nil, err
	}
	return one[JoinRequest](tx.Query(ctx, feedJoinSelect+` WHERE j.id = $1`, id))
}

func (tx *Tx) DecideFeedJoinRequest(ctx context.Context, id uuid.UUID, status string) error {
	_, err := tx.Exec(ctx, `UPDATE feed_join_requests SET status = $2, decided_at = now() WHERE id = $1`, id, status)
	return err
}

// ---------------------------------------------------------------- subscriptions

type Subscription struct {
	ID                uuid.UUID  `db:"id"`
	FeedID            uuid.UUID  `db:"feed_id"`
	FrameID           uuid.UUID  `db:"frame_id"`
	Status            string     `db:"status"`
	History           string     `db:"history"`
	HistoryCount      *int       `db:"history_count"`
	RequestedBy       *uuid.UUID `db:"requested_by"`
	ViaInviteID       *uuid.UUID `db:"via_invite_id"`
	ViaInvite         bool       `db:"via_invite"`
	DecidedBy         *uuid.UUID `db:"decided_by"`
	RemovedReason     *string    `db:"removed_reason"`
	ActivatedAt       *time.Time `db:"activated_at"`
	PausedAt          *time.Time `db:"paused_at"`
	EndedAt           *time.Time `db:"ended_at"`
	BackfillState     string     `db:"backfill_state"`
	BackfillPlaced    int        `db:"backfill_placed"`
	BackfillTotal     int        `db:"backfill_total"`
	ReconciledVersion int        `db:"reconciled_version"`
	Birthdays         bool       `db:"birthdays"` // show the feed's birthday people while active
	CreatedAt         time.Time  `db:"created_at"`
}

// Live reports pending, active or paused.
func (s *Subscription) Live() bool {
	return s.Status == "pending" || s.Status == "active" || s.Status == "paused"
}

const subCols = `s.id, s.feed_id, s.frame_id, s.status, s.history, s.history_count, s.requested_by, s.via_invite_id, s.via_invite,
	s.decided_by, s.removed_reason, s.activated_at, s.paused_at, s.ended_at, s.backfill_state, s.backfill_placed, s.backfill_total,
	s.reconciled_version, s.birthdays, s.created_at`

// SubscriptionView adds what the API shows about both sides.
type SubscriptionView struct {
	Subscription
	FeedName        string     `db:"feed_name"`
	FeedCoverKey    *string    `db:"feed_cover_key"`
	FeedMemberCount int        `db:"feed_member_count"`
	FrameName       string     `db:"frame_name"`
	FrameLocation   *string    `db:"frame_location"`
	FrameDeviceID   *uuid.UUID `db:"frame_device_id"`
	LastSeenAt      *time.Time `db:"last_seen_at"`
	RequestedByName *string    `db:"requested_by_name"`
	FrameFeatures   []string   `db:"frame_features"`
	BirthdayCount   int        `db:"birthday_count"`
}

const subViewSelect = `SELECT ` + subCols + `, fd.name AS feed_name,
	(SELECT i.thumb512_key FROM items i WHERE i.id = fd.cover_item_id) AS feed_cover_key,
	(SELECT count(*) FROM feed_members m WHERE m.feed_id = fd.id)::int AS feed_member_count,
	fr.name AS frame_name, fr.location AS frame_location, fr.device_id AS frame_device_id, d.last_seen_at,
	ru.display_name AS requested_by_name, COALESCE(d.features, '{}') AS frame_features,
	(SELECT count(*) FROM feed_birthdays b WHERE b.feed_id = s.feed_id AND b.status = 'approved')::int AS birthday_count
	FROM subscriptions s JOIN feeds fd ON fd.id = s.feed_id JOIN frames fr ON fr.id = s.frame_id
	LEFT JOIN devices d ON d.id = fr.device_id LEFT JOIN users ru ON ru.id = s.requested_by`

// subRetention: rejected and removed subscriptions are listed this long.
const subRetention = `interval '30 days'`

func (s *Store) GetSubscriptionView(ctx context.Context, q db.DBTX, id uuid.UUID) (*SubscriptionView, error) {
	return one[SubscriptionView](q.Query(ctx, subViewSelect+` WHERE s.id = $1 AND fd.deleted_at IS NULL`, id))
}

// ListFeedSubscriptions lists a feed's subscriptions (statuses nil = all),
// ended ones only within the retention window.
func (s *Store) ListFeedSubscriptions(ctx context.Context, feedID uuid.UUID, statuses []string) ([]SubscriptionView, error) {
	return many[SubscriptionView](s.Pool.Query(ctx, subViewSelect+` WHERE s.feed_id = $1 AND ($2::text[] IS NULL OR s.status = ANY($2))
		AND (s.ended_at IS NULL OR s.ended_at > now() - `+subRetention+`)
		ORDER BY s.created_at DESC, s.id`, feedID, statuses))
}

// ListFrameSubscriptions lists a frame's subscriptions (statuses nil = all).
func (s *Store) ListFrameSubscriptions(ctx context.Context, frameID uuid.UUID, statuses []string) ([]SubscriptionView, error) {
	return many[SubscriptionView](s.Pool.Query(ctx, subViewSelect+` WHERE s.frame_id = $1 AND ($2::text[] IS NULL OR s.status = ANY($2))
		AND (s.ended_at IS NULL OR s.ended_at > now() - `+subRetention+`) AND fd.deleted_at IS NULL
		ORDER BY fd.name, s.created_at DESC, s.id`, frameID, statuses))
}

// LockSubscription returns a subscription with a row lock. Callers hold the
// frame lock first (LockFrame), which serializes everything that places on
// that frame.
func (tx *Tx) LockSubscription(ctx context.Context, id uuid.UUID) (*Subscription, error) {
	return one[Subscription](tx.Query(ctx, `SELECT `+subCols+` FROM subscriptions s WHERE s.id = $1 FOR UPDATE`, id))
}

func (s *Store) GetSubscription(ctx context.Context, q db.DBTX, id uuid.UUID) (*Subscription, error) {
	return one[Subscription](q.Query(ctx, `SELECT `+subCols+` FROM subscriptions s WHERE s.id = $1`, id))
}

// LiveSubscription returns the frame's live (pending, active, paused)
// subscription to the feed.
func (s *Store) LiveSubscription(ctx context.Context, q db.DBTX, feedID, frameID uuid.UUID) (*Subscription, error) {
	return one[Subscription](q.Query(ctx, `SELECT `+subCols+` FROM subscriptions s
		WHERE s.feed_id = $1 AND s.frame_id = $2 AND s.status IN ('pending', 'active', 'paused')`, feedID, frameID))
}

// CountLiveSubscriptions counts a feed's pending, active and paused subscriptions.
func (s *Store) CountLiveSubscriptions(ctx context.Context, q db.DBTX, feedID uuid.UUID) (int, error) {
	var n int
	err := q.QueryRow(ctx, `SELECT count(*) FROM subscriptions WHERE feed_id = $1 AND status IN ('pending', 'active', 'paused')`, feedID).Scan(&n)
	return n, err
}

// CreateSubscription inserts a pending subscription. A unique violation on
// subscriptions_one_live means the frame already has a live one.
func (tx *Tx) CreateSubscription(ctx context.Context, sub Subscription) error {
	_, err := tx.Exec(ctx, `INSERT INTO subscriptions (id, feed_id, frame_id, status, history, history_count, requested_by, via_invite_id, via_invite, birthdays)
		VALUES ($1, $2, $3, 'pending', $4, $5, $6, $7, $8, $9)`,
		sub.ID, sub.FeedID, sub.FrameID, sub.History, sub.HistoryCount, sub.RequestedBy, sub.ViaInviteID, sub.ViaInviteID != nil, sub.Birthdays)
	return err
}

// LiveSubscriptionsOfFeed lists the feed's pending, active and paused subscriptions, in frame order.
func (s *Store) LiveSubscriptionsOfFeed(ctx context.Context, q db.DBTX, feedID uuid.UUID) ([]Subscription, error) {
	return many[Subscription](q.Query(ctx, `SELECT `+subCols+` FROM subscriptions s
		WHERE s.feed_id = $1 AND s.status IN ('pending', 'active', 'paused') ORDER BY s.frame_id`, feedID))
}

// PruneSubscriptions deletes rejected and removed subscriptions after the
// retention window, once nothing of them is left on frames.
func (s *Store) PruneSubscriptions(ctx context.Context) (int64, error) {
	tag, err := s.Pool.Exec(ctx, `DELETE FROM subscriptions s WHERE s.status IN ('rejected', 'removed')
		AND s.ended_at < now() - `+subRetention+`
		AND NOT EXISTS (SELECT 1 FROM feed_deliveries d WHERE d.subscription_id = s.id AND d.state = 'placed')`)
	return tag.RowsAffected(), err
}

// SubscriptionEstimate is the size of what a history option places.
type SubscriptionEstimate struct {
	Items int   `db:"items"`
	Bytes int64 `db:"bytes"`
}

// EstimateSubscription counts the items (and their master bytes) the
// subscription's current history option wants on the frame.
func (s *Store) EstimateSubscription(ctx context.Context, sub *Subscription, maxBackfill int) (*SubscriptionEstimate, error) {
	boundary := time.Now()
	if sub.ActivatedAt != nil {
		boundary = *sub.ActivatedAt
	}
	return one[SubscriptionEstimate](s.Pool.Query(ctx, `WITH wanted AS (`+wantedSQL+`)
		SELECT count(*)::int AS items, COALESCE(sum(COALESCE(m.master_bytes, 0)), 0)::bigint AS bytes
		FROM wanted w JOIN items i ON i.id = w.item_id JOIN media m ON m.id = i.media_id JOIN feed_posts p ON p.id = w.post_id
		WHERE `+circleReachSQL("$4"),
		sub.FeedID, boundary, historyLimit(sub, maxBackfill), sub.FrameID))
}

// ---------------------------------------------------------------- posts

type FeedPost struct {
	ID              uuid.UUID  `db:"id"`
	FeedID          uuid.UUID  `db:"feed_id"`
	SendID          *uuid.UUID `db:"send_id"`
	PosterUserID    *uuid.UUID `db:"poster_user_id"`
	PosterName      string     `db:"poster_name"`
	PosterAvatarKey *string    `db:"poster_avatar_key"`
	CreatedAt       time.Time  `db:"created_at"`
	RemovedAt       *time.Time `db:"removed_at"`
	FeedName        string     `db:"feed_name"`
	// Via is the send's credit (photo sources and Google imports).
	Via         *string    `db:"via"`
	ViaSourceID *uuid.UUID `db:"via_source_id"`
	// ViaLabel and ViaEmailAddressID: email posts (the credited name, the inbox address).
	ViaLabel          *string    `db:"via_label"`
	ViaEmailAddressID *uuid.UUID `db:"via_email_address_id"`
	// ViaTelegramChatID: Telegram posts.
	ViaTelegramChatID *uuid.UUID `db:"via_telegram_chat_id"`
	// CircleID, CircleName: the audience when shared with one circle (README §5.18).
	CircleID   *uuid.UUID `db:"circle_id"`
	CircleName *string    `db:"circle_name"`
}

const feedPostSelect = `SELECT p.id, p.feed_id, p.send_id, p.poster_user_id, COALESCE(u.display_name, '') AS poster_name,
	u.avatar_key AS poster_avatar_key, p.created_at, p.removed_at, fd.name AS feed_name, sd.via, sd.photo_source_id AS via_source_id,
	sd.via_label, sd.email_address_id AS via_email_address_id, sd.telegram_chat_id AS via_telegram_chat_id,
	p.circle_id, (SELECT c.name FROM circles c WHERE c.id = p.circle_id) AS circle_name
	FROM feed_posts p JOIN feeds fd ON fd.id = p.feed_id LEFT JOIN users u ON u.id = p.poster_user_id LEFT JOIN sends sd ON sd.id = p.send_id`

// CreateFeedPost inserts a post with the given items (in order).
func (tx *Tx) CreateFeedPost(ctx context.Context, id, feedID uuid.UUID, sendID *uuid.UUID, posterID uuid.UUID, itemIDs []uuid.UUID) error {
	if _, err := tx.Exec(ctx, `INSERT INTO feed_posts (id, feed_id, send_id, poster_user_id) VALUES ($1, $2, $3, $4)`, id, feedID, sendID, posterID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO feed_post_items (post_id, item_id, position)
		SELECT $1, e.id, e.ord - 1 FROM unnest($2::uuid[]) WITH ORDINALITY AS e(id, ord)`, id, itemIDs); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `UPDATE feed_members SET last_posted_at = now() WHERE feed_id = $1 AND user_id = $2`, feedID, posterID)
	return err
}

// LivePostOfSend returns the live post of a send in a feed, if any.
func (s *Store) LivePostOfSend(ctx context.Context, q db.DBTX, feedID, sendID uuid.UUID) (*FeedPost, error) {
	return one[FeedPost](q.Query(ctx, feedPostSelect+` WHERE p.feed_id = $1 AND p.send_id = $2 AND p.removed_at IS NULL`, feedID, sendID))
}

// GetFeedPost returns a live post of the feed.
func (s *Store) GetFeedPost(ctx context.Context, q db.DBTX, feedID, postID uuid.UUID) (*FeedPost, error) {
	return one[FeedPost](q.Query(ctx, feedPostSelect+` WHERE p.feed_id = $1 AND p.id = $2 AND p.removed_at IS NULL`, feedID, postID))
}

// ListFeedPosts pages live posts newest first, as viewer may see them: circle posts only for the circle's
// members and the poster (README §5.18).
func (s *Store) ListFeedPosts(ctx context.Context, viewer, feedID uuid.UUID, poster *uuid.UUID, before *uuid.UUID, limit int) ([]FeedPost, error) {
	return many[FeedPost](s.Pool.Query(ctx, feedPostSelect+` WHERE p.feed_id = $1 AND p.removed_at IS NULL
		AND ($2::uuid IS NULL OR p.poster_user_id = $2) AND ($3::uuid IS NULL OR p.id < $3)
		AND (p.circle_id IS NULL OR p.poster_user_id = $5 OR EXISTS (SELECT 1 FROM circle_members cm WHERE cm.circle_id = p.circle_id AND cm.user_id = $5))
		ORDER BY p.id DESC LIMIT $4`, feedID, poster, before, limit, viewer))
}

// PostsOfSends lists the posts (live and removed) of sends, for Send.feeds[].
func (s *Store) PostsOfSends(ctx context.Context, q db.DBTX, sendIDs []uuid.UUID) ([]FeedPost, error) {
	return many[FeedPost](q.Query(ctx, feedPostSelect+` WHERE p.send_id = ANY($1) AND fd.deleted_at IS NULL ORDER BY fd.name, p.id`, sendIDs))
}

// FeedPostItemRow is a live item of a post.
type FeedPostItemRow struct {
	PostID   uuid.UUID `db:"post_id"`
	Position int       `db:"position"`
	ItemRow
}

func (s *Store) FeedPostItemRows(ctx context.Context, q db.DBTX, postIDs []uuid.UUID) ([]FeedPostItemRow, error) {
	return many[FeedPostItemRow](q.Query(ctx, `SELECT pi.post_id, pi.position, `+itemRowCols+`
		FROM feed_post_items pi JOIN items i ON i.id = pi.item_id`+itemJoins+`
		WHERE pi.post_id = ANY($1) AND pi.removed_at IS NULL ORDER BY pi.post_id, pi.position`, postIDs))
}

// RemovePostItems marks items of a post removed (all of them when itemIDs is
// nil), and the post when none are left. Returns the subscriptions that hold
// deliveries of the removed items: they need a sweep (FeedSweep). removed is
// false when nothing was live.
func (tx *Tx) RemovePostItems(ctx context.Context, postID uuid.UUID, itemIDs []uuid.UUID, by *uuid.UUID) (removed bool, subs []uuid.UUID, err error) {
	gone, err := tx.Query(ctx, `UPDATE feed_post_items SET removed_at = now()
		WHERE post_id = $1 AND removed_at IS NULL AND ($2::uuid[] IS NULL OR item_id = ANY($2)) RETURNING item_id`, postID, itemIDs)
	if err != nil {
		return false, nil, err
	}
	goneIDs, err := collectIDs(gone)
	if err != nil {
		return false, nil, err
	}
	// Feed sources never post these items again (photo-sources.md §9.2).
	if len(goneIDs) > 0 {
		if err := tx.markRemovedLocally(ctx, `ps.feed_id = (SELECT feed_id FROM feed_posts WHERE id = $1) AND psi.item_id = ANY($2)`, postID, goneIDs); err != nil {
			return false, nil, err
		}
	}
	if _, err := tx.Exec(ctx, `UPDATE feed_posts p SET removed_at = now(), removed_by = $2 WHERE p.id = $1 AND p.removed_at IS NULL
		AND NOT EXISTS (SELECT 1 FROM feed_post_items pi WHERE pi.post_id = p.id AND pi.removed_at IS NULL)`, postID, by); err != nil {
		return false, nil, err
	}
	rows, err := tx.Query(ctx, `SELECT DISTINCT d.subscription_id FROM feed_deliveries d JOIN feed_post_items pi
		ON pi.post_id = d.post_id AND pi.item_id = d.item_id WHERE d.post_id = $1 AND pi.removed_at IS NOT NULL`, postID)
	if err != nil {
		return false, nil, err
	}
	subs, err = collectIDs(rows)
	return len(goneIDs) > 0, subs, err
}

// LivePostIDs lists live posts by a poster (all feeds when feedID is nil), or
// of a send (sendID).
func (s *Store) LivePostIDs(ctx context.Context, q db.DBTX, feedID, posterID, sendID *uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT id FROM feed_posts WHERE removed_at IS NULL AND ($1::uuid IS NULL OR feed_id = $1)
		AND ($2::uuid IS NULL OR poster_user_id = $2) AND ($3::uuid IS NULL OR send_id = $3) ORDER BY id`, feedID, posterID, sendID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// ---------------------------------------------------------------- receipts

// DeliveryRow is one post item on one active or paused subscription, with its
// placement's state (for receipts).
type DeliveryRow struct {
	PostID            uuid.UUID  `db:"post_id"`
	SubscriptionID    uuid.UUID  `db:"subscription_id"`
	SubStatus         string     `db:"sub_status"`
	FrameID           uuid.UUID  `db:"frame_id"`
	FrameName         string     `db:"frame_name"`
	FrameLocation     *string    `db:"frame_location"`
	DeviceID          *uuid.UUID `db:"device_id"`
	LastSeenAt        *time.Time `db:"last_seen_at"`
	ItemID            uuid.UUID  `db:"item_id"`
	Position          int        `db:"position"`
	State             string     `db:"state"`
	PlacementID       *uuid.UUID `db:"placement_id"`
	Status            *string    `db:"status"`
	PendingModeration *bool      `db:"pending_moderation"`
	PlacementRemoved  bool       `db:"placement_removed"`
	DeliveredAt       *time.Time `db:"delivered_at"`
	SeenAt            *time.Time `db:"seen_at"`
	Reaction          *string    `db:"reaction"`
	ReactionAt        *time.Time `db:"reaction_at"`
	Replies           []Reply    `db:"replies"`
}

func (s *Store) DeliveryRows(ctx context.Context, q db.DBTX, postIDs []uuid.UUID) ([]DeliveryRow, error) {
	return many[DeliveryRow](q.Query(ctx, `
		SELECT d.post_id, d.subscription_id, s.status AS sub_status, s.frame_id, f.name AS frame_name, f.location AS frame_location,
			f.device_id, dv.last_seen_at, d.item_id, pi.position, d.state, d.placement_id, p.status, p.pending_moderation,
			(p.id IS NOT NULL AND p.removed_at IS NOT NULL) AS placement_removed, p.delivered_at, p.seen_at, p.reaction, p.reaction_at,
			COALESCE((SELECT json_agg(json_build_object('preset', r.preset, 'at', r.at) ORDER BY r.at)
				FROM placement_replies r WHERE r.placement_id = p.id), '[]'::json) AS replies
		FROM feed_deliveries d
		JOIN subscriptions s ON s.id = d.subscription_id AND s.status IN ('active', 'paused')
		JOIN frames f ON f.id = s.frame_id AND f.deleted_at IS NULL
		LEFT JOIN devices dv ON dv.id = f.device_id
		JOIN feed_post_items pi ON pi.post_id = d.post_id AND pi.item_id = d.item_id AND pi.removed_at IS NULL
		LEFT JOIN placements p ON p.id = d.placement_id
		WHERE d.post_id = ANY($1)
		ORDER BY d.post_id, f.name, s.frame_id, pi.position`, postIDs))
}

// PostsOfPlacements maps placements to the feed posts that hold them
// (placement -> [{feed, post}]), for receipts updates and reaction pushes.
type PlacementPost struct {
	PlacementID uuid.UUID `db:"placement_id"`
	FeedID      uuid.UUID `db:"feed_id"`
	PostID      uuid.UUID `db:"post_id"`
}

func (s *Store) PostsOfPlacements(ctx context.Context, q db.DBTX, placementIDs []uuid.UUID) ([]PlacementPost, error) {
	return many[PlacementPost](q.Query(ctx, `SELECT DISTINCT d.placement_id, s.feed_id, d.post_id FROM feed_deliveries d
		JOIN subscriptions s ON s.id = d.subscription_id JOIN feed_posts fp ON fp.id = d.post_id AND fp.removed_at IS NULL
		JOIN feeds fd ON fd.id = s.feed_id AND fd.deleted_at IS NULL
		WHERE d.placement_id = ANY($1) AND d.state = 'placed' ORDER BY d.placement_id, s.feed_id, d.post_id`, placementIDs))
}

// ---------------------------------------------------------------- frame settings

// ResetSlideshowFeed sets settings.slideshow.feed_id to null when it points at
// feedID (a subscription ended). The frame must be locked by the caller.
func (tx *Tx) ResetSlideshowFeed(ctx context.Context, frameID, feedID uuid.UUID) error {
	var raw json.RawMessage
	var version int
	var fv map[string]int
	err := tx.QueryRow(ctx, `SELECT settings, settings_version, settings_field_versions FROM frames WHERE id = $1`, frameID).Scan(&raw, &version, &fv)
	if db.NotFound(err) {
		return nil
	}
	if err != nil {
		return err
	}
	var doc map[string]any
	if json.Unmarshal(raw, &doc) != nil {
		return nil
	}
	ss, _ := doc["slideshow"].(map[string]any)
	if cur, _ := ss["feed_id"].(string); cur == "" || cur != feedID.String() {
		return nil
	}
	delete(ss, "feed_id") // merge-patch semantics: null is absent
	version++
	if fv == nil {
		fv = map[string]int{}
	}
	fv["slideshow.feed_id"] = version
	b, err := json.Marshal(doc)
	if err != nil {
		return err
	}
	if err := tx.SetFrameSettings(ctx, frameID, b, version, fv); err != nil {
		return err
	}
	return tx.FeedPut(ctx, frameID, FeedSettings, frameID, false)
}
