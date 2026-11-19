package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Families on different servers (docs/spec/multi-server.md, README §5.22; tables in 00037_federation.sql and
// 00045_federation_sync.sql). "A" is the server with the family, "B" the server with the frame.

// FederationOne and FederationMany scan rows into structs by column name (for the federation queries in package api).
func FederationOne[T any](rows pgx.Rows, err error) (*T, error) { return one[T](rows, err) }

// FederationMany: see FederationOne.
func FederationMany[T any](rows pgx.Rows, err error) ([]T, error) { return many[T](rows, err) }

// placementRemoteJSON is PlacementRow.OriginRemote (p: the placement, i: its item).
const placementRemoteJSON = `(SELECT json_build_object('remote_subscription_id', rf.id,
	'server_name', COALESCE(NULLIF(rsv.server_name, ''), rsv.host), 'server_url', rsv.base_url, 'feed_name', rf.feed_name,
	'sender_name', (SELECT rsd.display_name FROM remote_senders rsd WHERE rsd.id = i.remote_sender_id))
	FROM remote_follows rf JOIN remote_servers rsv ON rsv.id = rf.server_id WHERE rf.id = p.origin_remote_follow_id) AS origin_remote`

// remoteFrameReactionsSQL adds frames on other servers to FrameReactions ($1: the posts): frame_id is the remote
// subscription's id.
const remoteFrameReactionsSQL = `SELECT r.post_id, x.id, x.frame_name, r.item_id, r.reaction, r.at, COALESCE(NULLIF(rs.server_name, ''), rs.host)
		FROM remote_frame_reactions r JOIN remote_subscribers x ON x.id = r.subscriber_id AND x.status = 'active'
		JOIN remote_servers rs ON rs.id = x.server_id
		JOIN feed_post_items pi ON pi.post_id = r.post_id AND pi.item_id = r.item_id AND pi.removed_at IS NULL
		WHERE r.post_id = ANY($1)`

// ---------------------------------------------------------------- identity and keys

// FederationServerID returns this server's stable id, creating it at first use.
func (s *Store) FederationServerID(ctx context.Context) (string, error) {
	var id string
	err := s.Pool.QueryRow(ctx, `SELECT server_id FROM server_identity`).Scan(&id)
	if err == nil {
		return id, nil
	}
	if !db.NotFound(err) {
		return "", err
	}
	if _, err := s.Pool.Exec(ctx, `INSERT INTO server_identity (server_id) VALUES ($1) ON CONFLICT DO NOTHING`, NewID().String()); err != nil {
		return "", err
	}
	err = s.Pool.QueryRow(ctx, `SELECT server_id FROM server_identity`).Scan(&id)
	return id, err
}

// FederationKey is a signing key row; PrivateKeyEnc is the 32-byte seed, sealed when at-rest encryption is set.
type FederationKey struct {
	KeyID         string     `db:"key_id"`
	PublicKey     []byte     `db:"public_key"`
	PrivateKeyEnc []byte     `db:"private_key_enc"`
	IsCurrent     bool       `db:"is_current"`
	CreatedAt     time.Time  `db:"created_at"`
	RetiredAt     *time.Time `db:"retired_at"`
	ExpiresAt     *time.Time `db:"expires_at"`
}

// FederationKeys lists the keys still valid (the current one first, then rotated ones until they expire).
func (s *Store) FederationKeys(ctx context.Context) ([]FederationKey, error) {
	return many[FederationKey](s.Pool.Query(ctx, `SELECT key_id, public_key, private_key_enc, is_current, created_at, retired_at, expires_at
		FROM federation_keys WHERE is_current OR expires_at > now() ORDER BY is_current DESC, created_at DESC`))
}

// AddFederationKey makes k the current key; the previous current key is retired and stays valid for keep.
// A concurrent creation of the first key loses quietly (the caller reloads).
func (s *Store) AddFederationKey(ctx context.Context, k FederationKey, keep time.Duration) error {
	err := s.InTx(ctx, func(tx *Tx) error {
		if _, err := tx.Exec(ctx, `UPDATE federation_keys SET is_current = false, retired_at = now(), expires_at = now() + make_interval(secs => $1)
			WHERE is_current`, keep.Seconds()); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `INSERT INTO federation_keys (key_id, public_key, private_key_enc, is_current) VALUES ($1, $2, $3, true)`,
			k.KeyID, k.PublicKey, k.PrivateKeyEnc)
		return err
	})
	if db.IsUniqueViolation(err, "federation_keys_one_current") {
		return nil
	}
	return err
}

// DeleteExpiredFederationKeys removes rotated keys past their 7 days.
func (s *Store) DeleteExpiredFederationKeys(ctx context.Context) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM federation_keys WHERE NOT is_current AND expires_at <= now()`)
	return err
}

// ---------------------------------------------------------------- other servers

// RemoteServer is another server as this one knows it.
type RemoteServer struct {
	ID            uuid.UUID       `db:"id"`
	BaseURL       string          `db:"base_url"`
	Host          string          `db:"host"`
	ServerID      *string         `db:"server_id"`
	ServerName    *string         `db:"server_name"`
	Document      json.RawMessage `db:"document"`
	Keys          json.RawMessage `db:"keys"`
	FetchedAt     *time.Time      `db:"fetched_at"`
	LastContactAt *time.Time      `db:"last_contact_at"`
	LastError     *string         `db:"last_error"`
	CreatedAt     time.Time       `db:"created_at"`
}

// Name is the server's label: its server_name, else its host.
func (r *RemoteServer) Name() string {
	if r.ServerName != nil && *r.ServerName != "" {
		return *r.ServerName
	}
	return r.Host
}

const remoteServerCols = `id, base_url, host, server_id, server_name, document, keys, fetched_at, last_contact_at, last_error, created_at`

// RemoteServerByURL returns the server with that normalized base URL.
func (s *Store) RemoteServerByURL(ctx context.Context, q db.DBTX, base string) (*RemoteServer, error) {
	return one[RemoteServer](q.Query(ctx, `SELECT `+remoteServerCols+` FROM remote_servers WHERE base_url = $1`, base))
}

// RemoteServerByID returns a known server.
func (s *Store) RemoteServerByID(ctx context.Context, q db.DBTX, id uuid.UUID) (*RemoteServer, error) {
	return one[RemoteServer](q.Query(ctx, `SELECT `+remoteServerCols+` FROM remote_servers WHERE id = $1`, id))
}

// EnsureRemoteServer returns the row of base, creating it.
func (s *Store) EnsureRemoteServer(ctx context.Context, base, host string) (*RemoteServer, error) {
	if _, err := s.Pool.Exec(ctx, `INSERT INTO remote_servers (id, base_url, host) VALUES ($1, $2, $3) ON CONFLICT (base_url) DO NOTHING`,
		NewID(), base, host); err != nil {
		return nil, err
	}
	return s.RemoteServerByURL(ctx, s.Pool, base)
}

// SaveRemoteDocument stores a freshly fetched well-known document and its keys.
func (s *Store) SaveRemoteDocument(ctx context.Context, id uuid.UUID, serverID, name string, doc, keys json.RawMessage) error {
	_, err := s.Pool.Exec(ctx, `UPDATE remote_servers SET server_id = $2, server_name = $3, document = $4, keys = $5, fetched_at = now(),
		last_error = NULL WHERE id = $1`, id, serverID, name, doc, keys)
	return err
}

// RemoteServerFailed records the last error talking to a server.
func (s *Store) RemoteServerFailed(ctx context.Context, id uuid.UUID, msg string) {
	_, _ = s.Pool.Exec(ctx, `UPDATE remote_servers SET last_error = left($2, 300) WHERE id = $1`, id, msg)
}

// TouchRemoteServer records a successful contact.
func (s *Store) TouchRemoteServer(ctx context.Context, id uuid.UUID) {
	_, _ = s.Pool.Exec(ctx, `UPDATE remote_servers SET last_contact_at = now(), last_error = NULL WHERE id = $1`, id)
}

// RemoteServerRow is a server with the operator's counts.
type RemoteServerRow struct {
	RemoteServer
	Subscribers int  `db:"subscribers"`
	Follows     int  `db:"follows"`
	Blocked     bool `db:"blocked"`
}

// ListRemoteServers lists every known server with live subscribers and follows.
func (s *Store) ListRemoteServers(ctx context.Context) ([]RemoteServerRow, error) {
	return many[RemoteServerRow](s.Pool.Query(ctx, `SELECT `+remoteServerCols+`,
		(SELECT count(*) FROM remote_subscribers x WHERE x.server_id = r.id AND x.status IN ('pending', 'active'))::int AS subscribers,
		(SELECT count(*) FROM remote_follows f WHERE f.server_id = r.id AND f.status IN ('pending', 'active', 'error'))::int AS follows,
		EXISTS (SELECT 1 FROM blocked_servers b WHERE b.host = r.host AND b.feed_id IS NULL) AS blocked
		FROM remote_servers r ORDER BY r.host, r.base_url`))
}

// ---------------------------------------------------------------- blocks

// BlockedServer is a block (feed nil: instance-wide).
type BlockedServer struct {
	ID            uuid.UUID  `db:"id"`
	Host          string     `db:"host"`
	FeedID        *uuid.UUID `db:"feed_id"`
	ServerName    *string    `db:"server_name"`
	Reason        *string    `db:"reason"`
	CreatedBy     *uuid.UUID `db:"created_by"`
	CreatedByName *string    `db:"created_by_name"`
	CreatedAt     time.Time  `db:"created_at"`
}

const blockedSelect = `SELECT b.id, b.host, b.feed_id, b.server_name, b.reason, b.created_by, u.display_name AS created_by_name, b.created_at
	FROM blocked_servers b LEFT JOIN users u ON u.id = b.created_by`

// ListBlockedServers lists the blocks of a feed, or the instance-wide ones (feed nil).
func (s *Store) ListBlockedServers(ctx context.Context, feedID *uuid.UUID) ([]BlockedServer, error) {
	return many[BlockedServer](s.Pool.Query(ctx, blockedSelect+` WHERE b.feed_id IS NOT DISTINCT FROM $1 ORDER BY b.host`, feedID))
}

// BlockServer adds a block or returns the existing one for the host (idempotent).
func (tx *Tx) BlockServer(ctx context.Context, b BlockedServer) (*BlockedServer, error) {
	if _, err := tx.Exec(ctx, `INSERT INTO blocked_servers (id, host, feed_id, server_name, reason, created_by) VALUES ($1, $2, $3, $4, $5, $6)
		ON CONFLICT DO NOTHING`, b.ID, b.Host, b.FeedID, b.ServerName, b.Reason, b.CreatedBy); err != nil {
		return nil, err
	}
	return one[BlockedServer](tx.Query(ctx, blockedSelect+` WHERE b.host = $1 AND b.feed_id IS NOT DISTINCT FROM $2`, b.Host, b.FeedID))
}

// UnblockServer removes a block of a feed (or an instance-wide one, feed nil).
func (s *Store) UnblockServer(ctx context.Context, feedID *uuid.UUID, id uuid.UUID) (bool, error) {
	tag, err := s.Pool.Exec(ctx, `DELETE FROM blocked_servers WHERE id = $1 AND feed_id IS NOT DISTINCT FROM $2`, id, feedID)
	return tag.RowsAffected() > 0, err
}

// ServerBlocked reports whether host is blocked instance-wide, or for feedID when it is set.
func (s *Store) ServerBlocked(ctx context.Context, q db.DBTX, host string, feedID *uuid.UUID) (bool, error) {
	var b bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM blocked_servers WHERE host = $1 AND (feed_id IS NULL OR feed_id = $2))`, host, feedID).Scan(&b)
	return b, err
}

// ---------------------------------------------------------------- A: remote subscribers

// RemoteSubscriber is a frame on another server following a family here.
type RemoteSubscriber struct {
	ID              uuid.UUID  `db:"id"`
	FeedID          uuid.UUID  `db:"feed_id"`
	ServerID        uuid.UUID  `db:"server_id"`
	RemoteFrameID   uuid.UUID  `db:"remote_frame_id"`
	FollowID        *uuid.UUID `db:"follow_id"`
	FrameName       string     `db:"frame_name"`
	RequestedByName *string    `db:"requested_by_name"`
	Status          string     `db:"status"`
	History         string     `db:"history"`
	HistoryCount    *int       `db:"history_count"`
	Birthdays       bool       `db:"birthdays"`
	ShowComments    bool       `db:"show_comments"`
	ViaInviteID     *uuid.UUID `db:"via_invite_id"`
	DecidedBy       *uuid.UUID `db:"decided_by"`
	RemovedReason   *string    `db:"removed_reason"`
	ActivatedAt     *time.Time `db:"activated_at"`
	EndedAt         *time.Time `db:"ended_at"`
	LastPullAt      *time.Time `db:"last_pull_at"`
	LastPingAt      *time.Time `db:"last_ping_at"`
	PingedSeq       int64      `db:"pinged_seq"`
	CreatedAt       time.Time  `db:"created_at"`
	NotifyEnd       bool       `db:"notify_end"`
	// The other server.
	ServerURL       string  `db:"server_url"`
	ServerHost      string  `db:"server_host"`
	ServerName      *string `db:"server_name"`
	RemoteServerKey *string `db:"remote_server_key"`
	FeedName        string  `db:"feed_name"`
}

// Live: pending or active.
func (r *RemoteSubscriber) Live() bool { return r.Status == "pending" || r.Status == "active" }

// ServerLabel is the other server's name, else its host.
func (r *RemoteSubscriber) ServerLabel() string {
	if r.ServerName != nil && *r.ServerName != "" {
		return *r.ServerName
	}
	return r.ServerHost
}

const remoteSubscriberSelect = `SELECT x.id, x.feed_id, x.server_id, x.remote_frame_id, x.follow_id, x.frame_name, x.requested_by_name, x.status,
	x.history, x.history_count, x.birthdays, x.show_comments, x.via_invite_id, x.decided_by, x.removed_reason, x.activated_at, x.ended_at,
	x.last_pull_at, x.last_ping_at, x.pinged_seq, x.created_at, x.notify_end,
	rs.base_url AS server_url, rs.host AS server_host, rs.server_name, rs.server_id AS remote_server_key, f.name AS feed_name
	FROM remote_subscribers x JOIN remote_servers rs ON rs.id = x.server_id JOIN feeds f ON f.id = x.feed_id`

// RemoteSubscriberByID returns a remote subscriber.
func (s *Store) RemoteSubscriberByID(ctx context.Context, q db.DBTX, id uuid.UUID) (*RemoteSubscriber, error) {
	return one[RemoteSubscriber](q.Query(ctx, remoteSubscriberSelect+` WHERE x.id = $1`, id))
}

// LockRemoteSubscriber returns a remote subscriber with a row lock.
func (tx *Tx) LockRemoteSubscriber(ctx context.Context, id uuid.UUID) (*RemoteSubscriber, error) {
	return one[RemoteSubscriber](tx.Query(ctx, remoteSubscriberSelect+` WHERE x.id = $1 FOR UPDATE OF x`, id))
}

// LiveRemoteSubscriber is the live subscription of a remote frame to a feed.
func (s *Store) LiveRemoteSubscriber(ctx context.Context, q db.DBTX, feedID, serverID, remoteFrameID uuid.UUID) (*RemoteSubscriber, error) {
	return one[RemoteSubscriber](q.Query(ctx, remoteSubscriberSelect+` WHERE x.feed_id = $1 AND x.server_id = $2 AND x.remote_frame_id = $3
		AND x.status IN ('pending', 'active')`, feedID, serverID, remoteFrameID))
}

// ListRemoteSubscribers lists a feed's remote subscribers with those statuses (ended ones for 30 days).
func (s *Store) ListRemoteSubscribers(ctx context.Context, feedID uuid.UUID, statuses []string) ([]RemoteSubscriber, error) {
	return many[RemoteSubscriber](s.Pool.Query(ctx, remoteSubscriberSelect+` WHERE x.feed_id = $1 AND x.status = ANY($2)
		AND (x.ended_at IS NULL OR x.ended_at > now() - interval '30 days') ORDER BY x.created_at, x.id`, feedID, statuses))
}

// LiveRemoteSubscribersWhere lists live subscribers matching a condition on x (subscriber) and rs (server).
func (s *Store) LiveRemoteSubscribersWhere(ctx context.Context, q db.DBTX, where string, args ...any) ([]RemoteSubscriber, error) {
	return many[RemoteSubscriber](q.Query(ctx, remoteSubscriberSelect+` WHERE x.status IN ('pending', 'active') AND `+where+` ORDER BY x.id`, args...))
}

// CountLiveRemoteSubscribers counts a feed's pending and active remote subscribers.
func (s *Store) CountLiveRemoteSubscribers(ctx context.Context, q db.DBTX, feedID uuid.UUID) (int, error) {
	var n int
	err := q.QueryRow(ctx, `SELECT count(*) FROM remote_subscribers WHERE feed_id = $1 AND status IN ('pending', 'active')`, feedID).Scan(&n)
	return n, err
}

// CreateRemoteSubscriber records a remote subscription (status pending or active).
func (tx *Tx) CreateRemoteSubscriber(ctx context.Context, r RemoteSubscriber) error {
	_, err := tx.Exec(ctx, `INSERT INTO remote_subscribers (id, feed_id, server_id, remote_frame_id, follow_id, frame_name, requested_by_name,
		status, history, history_count, birthdays, show_comments, via_invite_id, decided_by, activated_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, CASE WHEN $8 = 'active' THEN now() END)`,
		r.ID, r.FeedID, r.ServerID, r.RemoteFrameID, r.FollowID, r.FrameName, r.RequestedByName, r.Status, r.History, r.HistoryCount,
		r.Birthdays, r.ShowComments, r.ViaInviteID, r.DecidedBy)
	if err != nil {
		return err
	}
	return tx.LogSubscriptionChange(ctx, r.FeedID, r.ID)
}

// ActivateRemoteSubscriber: pending -> active (the history boundary is set once).
func (tx *Tx) ActivateRemoteSubscriber(ctx context.Context, r *RemoteSubscriber, by *uuid.UUID) error {
	if _, err := tx.Exec(ctx, `UPDATE remote_subscribers SET status = 'active', activated_at = COALESCE(activated_at, now()),
		decided_by = COALESCE($2, decided_by) WHERE id = $1`, r.ID, by); err != nil {
		return err
	}
	return tx.LogSubscriptionChange(ctx, r.FeedID, r.ID)
}

// EndRemoteSubscriber ends a live remote subscription (status rejected or removed); notify asks the loop to tell
// the other server with a signed DELETE (not when the other server ended it itself).
func (tx *Tx) EndRemoteSubscriber(ctx context.Context, r *RemoteSubscriber, status, reason string, by *uuid.UUID, notify bool) error {
	if _, err := tx.Exec(ctx, `UPDATE remote_subscribers SET status = $2, removed_reason = $3, decided_by = COALESCE($4, decided_by),
		ended_at = now(), notify_end = $5 WHERE id = $1`, r.ID, status, reason, by, notify); err != nil {
		return err
	}
	return tx.LogSubscriptionChange(ctx, r.FeedID, r.ID)
}

// LogSubscriptionChange writes the `subscription` change of one subscriber (status changes reach B through the
// change log as well as by the DELETE).
func (tx *Tx) LogSubscriptionChange(ctx context.Context, feedID, subscriberID uuid.UUID) error {
	_, err := tx.Exec(ctx, `INSERT INTO federation_changes (feed_id, kind, subscriber_id) VALUES ($1, 'subscription', $2)`, feedID, subscriberID)
	return err
}

// FederationChange is one row of the change log.
type FederationChange struct {
	Seq          int64       `db:"seq"`
	FeedID       uuid.UUID   `db:"feed_id"`
	Kind         string      `db:"kind"`
	PostID       *uuid.UUID  `db:"post_id"`
	ItemIDs      []uuid.UUID `db:"item_ids"`
	SubscriberID *uuid.UUID  `db:"subscriber_id"`
	CreatedAt    time.Time   `db:"created_at"`
}

// FederationChangesAfter lists a subscriber's changes after seq (its own `subscription` rows and the feed's others).
func (s *Store) FederationChangesAfter(ctx context.Context, feedID, subscriberID uuid.UUID, after int64, limit int) ([]FederationChange, error) {
	return many[FederationChange](s.Pool.Query(ctx, `SELECT seq, feed_id, kind, post_id, item_ids, subscriber_id, created_at FROM federation_changes
		WHERE feed_id = $1 AND seq > $2 AND (subscriber_id IS NULL OR subscriber_id = $3) ORDER BY seq LIMIT $4`, feedID, after, subscriberID, limit))
}

// FederationHeadSeq is the newest change of a feed (0 without any).
func (s *Store) FederationHeadSeq(ctx context.Context, feedID uuid.UUID) (int64, error) {
	var n int64
	err := s.Pool.QueryRow(ctx, `SELECT COALESCE(max(seq), 0) FROM federation_changes WHERE feed_id = $1`, feedID).Scan(&n)
	return n, err
}

// PruneFederationChanges drops change rows older than the retention.
func (s *Store) PruneFederationChanges(ctx context.Context, keep time.Duration) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM federation_changes WHERE created_at < now() - make_interval(secs => $1)`, keep.Seconds())
	return err
}

// PingDue is a subscriber whose family has unpinged changes, settled for the debounce.
type PingDue struct {
	ID        uuid.UUID `db:"id"`
	ServerID  uuid.UUID `db:"server_id"`
	ServerURL string    `db:"server_url"`
	Host      string    `db:"host"`
	Head      int64     `db:"head"`
}

// DuePings lists live subscribers with changes after pinged_seq whose newest change is older than settle, or whose
// oldest unpinged change is older than force (so a steady stream still pings).
func (s *Store) DuePings(ctx context.Context, settle, force time.Duration) ([]PingDue, error) {
	return many[PingDue](s.Pool.Query(ctx, `SELECT x.id, x.server_id, rs.base_url AS server_url, rs.host, c.head FROM remote_subscribers x
		JOIN remote_servers rs ON rs.id = x.server_id
		JOIN LATERAL (SELECT max(seq) AS head, max(created_at) AS newest, min(created_at) AS oldest FROM federation_changes
			WHERE feed_id = x.feed_id AND seq > x.pinged_seq AND (subscriber_id IS NULL OR subscriber_id = x.id)) c ON c.head IS NOT NULL
		WHERE x.status IN ('pending', 'active', 'rejected', 'removed') AND (x.ended_at IS NULL OR x.ended_at > now() - interval '1 day')
			AND (c.newest < now() - make_interval(secs => $1) OR c.oldest < now() - make_interval(secs => $2))
		ORDER BY rs.base_url, x.id`, settle.Seconds(), force.Seconds()))
}

// MarkPinged records that the subscribers were pinged up to their heads.
func (s *Store) MarkPinged(ctx context.Context, due []PingDue) error {
	for _, d := range due {
		if _, err := s.Pool.Exec(ctx, `UPDATE remote_subscribers SET pinged_seq = GREATEST(pinged_seq, $2), last_ping_at = now() WHERE id = $1`,
			d.ID, d.Head); err != nil {
			return err
		}
	}
	return nil
}

// ---------------------------------------------------------------- B: remote follows

// RemoteFollow is a frame here following a family on another server.
type RemoteFollow struct {
	ID                   uuid.UUID       `db:"id"`
	FrameID              uuid.UUID       `db:"frame_id"`
	ServerID             uuid.UUID       `db:"server_id"`
	RemoteSubscriptionID *uuid.UUID      `db:"remote_subscription_id"`
	InviteURL            string          `db:"invite_url"`
	FeedName             string          `db:"feed_name"`
	CoverURL             *string         `db:"cover_url"`
	Status               string          `db:"status"`
	History              string          `db:"history"`
	HistoryCount         *int            `db:"history_count"`
	Birthdays            bool            `db:"birthdays"`
	BirthdayPeople       json.RawMessage `db:"birthday_people"`
	OnEnd                string          `db:"on_end"`
	RequestedBy          *uuid.UUID      `db:"requested_by"`
	PullCursor           *string         `db:"pull_cursor"`
	NextPullAt           *time.Time      `db:"next_pull_at"`
	LastPullAt           *time.Time      `db:"last_pull_at"`
	LastError            *string         `db:"last_error"`
	ErrorSince           *time.Time      `db:"error_since"`
	RemovedReason        *string         `db:"removed_reason"`
	UnsubscribePending   bool            `db:"unsubscribe_pending"`
	ActivatedAt          *time.Time      `db:"activated_at"`
	EndedAt              *time.Time      `db:"ended_at"`
	CreatedAt            time.Time       `db:"created_at"`
	ResyncSince          *time.Time      `db:"resync_since"`
	// Joined.
	ServerURL       string  `db:"server_url"`
	ServerHost      string  `db:"server_host"`
	ServerName      *string `db:"server_name"`
	RemoteServerKey *string `db:"remote_server_key"`
	RequestedByName *string `db:"requested_by_name"`
	ItemCount       int     `db:"item_count"`
}

// Live: pending, active or error (retried).
func (f *RemoteFollow) Live() bool {
	return f.Status == "pending" || f.Status == "active" || f.Status == "error"
}

// ServerLabel is the other server's name, else its host.
func (f *RemoteFollow) ServerLabel() string {
	if f.ServerName != nil && *f.ServerName != "" {
		return *f.ServerName
	}
	return f.ServerHost
}

const remoteFollowSelect = `SELECT f.id, f.frame_id, f.server_id, f.remote_subscription_id, f.invite_url, f.feed_name, f.cover_url, f.status,
	f.history, f.history_count, f.birthdays, f.birthday_people, f.on_end, f.requested_by, f.pull_cursor, f.next_pull_at, f.last_pull_at,
	f.last_error, f.error_since, f.removed_reason, f.unsubscribe_pending, f.activated_at, f.ended_at, f.created_at, f.resync_since,
	rs.base_url AS server_url, rs.host AS server_host, rs.server_name, rs.server_id AS remote_server_key, u.display_name AS requested_by_name,
	(SELECT count(*) FROM remote_items ri JOIN placements p ON p.item_id = ri.item_id AND p.frame_id = f.frame_id AND p.removed_at IS NULL
		WHERE ri.follow_id = f.id)::int AS item_count
	FROM remote_follows f JOIN remote_servers rs ON rs.id = f.server_id LEFT JOIN users u ON u.id = f.requested_by`

// RemoteFollowByID returns a follow.
func (s *Store) RemoteFollowByID(ctx context.Context, q db.DBTX, id uuid.UUID) (*RemoteFollow, error) {
	return one[RemoteFollow](q.Query(ctx, remoteFollowSelect+` WHERE f.id = $1`, id))
}

// LockRemoteFollow returns a follow with a row lock.
func (tx *Tx) LockRemoteFollow(ctx context.Context, id uuid.UUID) (*RemoteFollow, error) {
	return one[RemoteFollow](tx.Query(ctx, remoteFollowSelect+` WHERE f.id = $1 FOR UPDATE OF f`, id))
}

// RemoteFollowsWhere lists follows matching a condition on f and rs.
func (s *Store) RemoteFollowsWhere(ctx context.Context, q db.DBTX, where string, args ...any) ([]RemoteFollow, error) {
	return many[RemoteFollow](q.Query(ctx, remoteFollowSelect+` WHERE `+where+` ORDER BY f.created_at, f.id`, args...))
}

// ---------------------------------------------------------------- B: copies

// RemoteSenderFor returns the remote sender of a poster on a server, creating it or updating its name.
func (tx *Tx) RemoteSenderFor(ctx context.Context, serverID uuid.UUID, posterKey, name string) (uuid.UUID, error) {
	var id uuid.UUID
	err := tx.QueryRow(ctx, `INSERT INTO remote_senders (id, server_id, poster_key, display_name) VALUES ($1, $2, $3, $4)
		ON CONFLICT (server_id, poster_key) DO UPDATE SET display_name = EXCLUDED.display_name, updated_at = now()
		RETURNING id`, NewID(), serverID, posterKey, name).Scan(&id)
	return id, err
}

// RemoteItem is a copied item.
type RemoteItem struct {
	ItemID       uuid.UUID  `db:"item_id"`
	FollowID     uuid.UUID  `db:"follow_id"`
	RemotePostID uuid.UUID  `db:"remote_post_id"`
	RemoteItemID uuid.UUID  `db:"remote_item_id"`
	SenderID     *uuid.UUID `db:"sender_id"`
}

// RemoteItemsWhere lists copies matching a condition on ri.
func (s *Store) RemoteItemsWhere(ctx context.Context, q db.DBTX, where string, args ...any) ([]RemoteItem, error) {
	return many[RemoteItem](q.Query(ctx, `SELECT ri.item_id, ri.follow_id, ri.remote_post_id, ri.remote_item_id, ri.sender_id
		FROM remote_items ri WHERE `+where, args...))
}
