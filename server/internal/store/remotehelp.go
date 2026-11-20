package store

// Remote help sessions (docs/spec/remote-help.md, README §5.23).

import (
	"context"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// FeedRemoteHelp is the `remote_help` change (entity: the frame).
const FeedRemoteHelp = "remote_help"

// RemoteHelpSession is a remote_help_sessions row.
type RemoteHelpSession struct {
	ID            uuid.UUID  `db:"id"`
	FrameID       uuid.UUID  `db:"frame_id"`
	DeviceID      *uuid.UUID `db:"device_id"`
	FrameName     string     `db:"frame_name"`
	State         string     `db:"state"` // pending, on, ended
	Reason        string     `db:"reason"`
	StartedBy     *uuid.UUID `db:"started_by"`
	StartedByName string     `db:"started_by_name"`
	StartedAt     time.Time  `db:"started_at"`
	EndsAt        time.Time  `db:"ends_at"`
	OnAt          *time.Time `db:"on_at"`
	EndedAt       *time.Time `db:"ended_at"`
	EndedBy       *string    `db:"ended_by"`
	EndedByUser   *uuid.UUID `db:"ended_by_user"`
	EndedByName   *string    `db:"ended_by_name"`
	Hostname      string     `db:"hostname"`
	TailnetIP     *string    `db:"tailnet_ip"`
	AuthKeyEnc    []byte     `db:"auth_key_enc"`
	AuthKeyID     *string    `db:"auth_key_id"`
	KeyUsed       bool       `db:"key_used"`
	Error         *string    `db:"error"`
}

const remoteHelpCols = `id, frame_id, device_id, frame_name, state, reason, started_by, started_by_name, started_at, ends_at,
	on_at, ended_at, ended_by, ended_by_user, ended_by_name, hostname, tailnet_ip, auth_key_enc, auth_key_id, key_used, error`

// Active reports whether the session is pending or on.
func (r *RemoteHelpSession) Active() bool { return r.State != "ended" }

// CreateRemoteHelp stores a new pending session. The unique index refuses a second active session per frame.
func (tx *Tx) CreateRemoteHelp(ctx context.Context, r RemoteHelpSession) error {
	_, err := tx.Exec(ctx, `INSERT INTO remote_help_sessions (id, frame_id, device_id, frame_name, state, reason, started_by,
			started_by_name, started_at, ends_at, hostname, auth_key_enc, auth_key_id)
		VALUES ($1, $2, $3, $4, 'pending', $5, $6, $7, $8, $9, $10, $11, $12)`,
		r.ID, r.FrameID, r.DeviceID, r.FrameName, r.Reason, r.StartedBy, r.StartedByName, r.StartedAt, r.EndsAt, r.Hostname,
		r.AuthKeyEnc, r.AuthKeyID)
	return err
}

// ActiveRemoteHelp returns the frame's pending or on session.
func (s *Store) ActiveRemoteHelp(ctx context.Context, q db.DBTX, frameID uuid.UUID) (*RemoteHelpSession, error) {
	return one[RemoteHelpSession](q.Query(ctx, `SELECT `+remoteHelpCols+` FROM remote_help_sessions
		WHERE frame_id = $1 AND state <> 'ended'`, frameID))
}

// LockActiveRemoteHelp is ActiveRemoteHelp FOR UPDATE.
func (tx *Tx) LockActiveRemoteHelp(ctx context.Context, frameID uuid.UUID) (*RemoteHelpSession, error) {
	return one[RemoteHelpSession](tx.Query(ctx, `SELECT `+remoteHelpCols+` FROM remote_help_sessions
		WHERE frame_id = $1 AND state <> 'ended' FOR UPDATE`, frameID))
}

// GetRemoteHelp returns one session.
func (s *Store) GetRemoteHelp(ctx context.Context, q db.DBTX, id uuid.UUID) (*RemoteHelpSession, error) {
	return one[RemoteHelpSession](q.Query(ctx, `SELECT `+remoteHelpCols+` FROM remote_help_sessions WHERE id = $1`, id))
}

// ActiveRemoteHelpOf returns the active sessions of these frames, by frame.
func (s *Store) ActiveRemoteHelpOf(ctx context.Context, q db.DBTX, frameIDs []uuid.UUID) (map[uuid.UUID]RemoteHelpSession, error) {
	rows, err := many[RemoteHelpSession](q.Query(ctx, `SELECT `+remoteHelpCols+` FROM remote_help_sessions
		WHERE frame_id = ANY($1) AND state <> 'ended'`, frameIDs))
	if err != nil {
		return nil, err
	}
	out := make(map[uuid.UUID]RemoteHelpSession, len(rows))
	for _, r := range rows {
		out[r.FrameID] = r
	}
	return out, nil
}

// RemoteHelpOn marks a pending session on (the frame joined), with its tailnet address, and forgets the key.
func (tx *Tx) RemoteHelpOn(ctx context.Context, id uuid.UUID, ip *string, now time.Time) error {
	_, err := tx.Exec(ctx, `UPDATE remote_help_sessions SET state = 'on', on_at = COALESCE(on_at, $3),
			tailnet_ip = COALESCE($2, tailnet_ip), auth_key_enc = NULL, key_used = true
		WHERE id = $1 AND state <> 'ended'`, id, ip, now)
	return err
}

// SetRemoteHelpIP updates the tailnet address of an on session.
func (tx *Tx) SetRemoteHelpIP(ctx context.Context, id uuid.UUID, ip string) error {
	_, err := tx.Exec(ctx, `UPDATE remote_help_sessions SET tailnet_ip = $2 WHERE id = $1 AND state = 'on'`, id, ip)
	return err
}

// RemoteHelpEnd describes how a session ended.
type RemoteHelpEnd struct {
	By      string // operator, owner, frame, expired, unpaired, failed
	ByUser  *uuid.UUID
	ByName  *string
	Error   *string
	KeyUsed bool // the frame used the key (it reported failed after trying)
	EndedAt time.Time
}

// EndRemoteHelp ends a session that is still active and forgets its key; false when it had ended already.
func (tx *Tx) EndRemoteHelp(ctx context.Context, id uuid.UUID, e RemoteHelpEnd) (bool, error) {
	tag, err := tx.Exec(ctx, `UPDATE remote_help_sessions SET state = 'ended', ended_at = $2, ended_by = $3, ended_by_user = $4,
			ended_by_name = $5, error = COALESCE($6, error), auth_key_enc = NULL, key_used = key_used OR $7
		WHERE id = $1 AND state <> 'ended'`, id, e.EndedAt, e.By, e.ByUser, e.ByName, e.Error, e.KeyUsed)
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() == 1, nil
}

// SetRemoteHelpError records an error on a session (Headscale clean-up failures).
func (s *Store) SetRemoteHelpError(ctx context.Context, id uuid.UUID, msg string) error {
	_, err := s.Pool.Exec(ctx, `UPDATE remote_help_sessions SET error = $2 WHERE id = $1 AND error IS NULL`, id, msg)
	return err
}

// RemoteHelpDue lists active sessions to end at now: past ends_at (expired), pending for longer than pendingFor
// (failed), or of frames that were deleted or got another (or no) device (unpaired).
func (s *Store) RemoteHelpDue(ctx context.Context, now time.Time, pendingFor time.Duration) (expired, failed, unpaired []RemoteHelpSession, err error) {
	rows, err := many[struct {
		RemoteHelpSession
		Why string `db:"why"`
	}](s.Pool.Query(ctx, `SELECT `+prefixed("r.", remoteHelpCols)+`,
			CASE WHEN f.deleted_at IS NOT NULL OR f.device_id IS DISTINCT FROM r.device_id OR d.revoked_at IS NOT NULL THEN 'unpaired'
			     WHEN r.ends_at <= $1 THEN 'expired'
			     ELSE 'failed' END AS why
		FROM remote_help_sessions r JOIN frames f ON f.id = r.frame_id LEFT JOIN devices d ON d.id = r.device_id
		WHERE r.state <> 'ended' AND (r.ends_at <= $1 OR (r.state = 'pending' AND r.started_at <= $2)
			OR f.deleted_at IS NOT NULL OR f.device_id IS DISTINCT FROM r.device_id OR d.revoked_at IS NOT NULL)`,
		now, now.Add(-pendingFor)))
	if err != nil {
		return nil, nil, nil, err
	}
	for _, r := range rows {
		switch r.Why {
		case "unpaired":
			unpaired = append(unpaired, r.RemoteHelpSession)
		case "expired":
			expired = append(expired, r.RemoteHelpSession)
		default:
			failed = append(failed, r.RemoteHelpSession)
		}
	}
	return expired, failed, unpaired, nil
}

// ListRemoteHelp pages sessions: active first, then newest; after the cursor (ended, started_at, id) when given.
func (s *Store) ListRemoteHelp(ctx context.Context, after *RemoteHelpCursor, limit int) ([]RemoteHelpSession, error) {
	var ended *bool
	var at *time.Time
	var id *uuid.UUID
	if after != nil {
		ended, at, id = &after.Ended, &after.At, &after.ID
	}
	return many[RemoteHelpSession](s.Pool.Query(ctx, `SELECT `+remoteHelpCols+` FROM remote_help_sessions
		WHERE $1::boolean IS NULL OR (state = 'ended') > $1 OR ((state = 'ended') = $1 AND (started_at, id) < ($2, $3))
		ORDER BY (state = 'ended'), started_at DESC, id DESC LIMIT $4`, ended, at, id, limit))
}

// RemoteHelpCursor is a position in ListRemoteHelp.
type RemoteHelpCursor struct {
	Ended bool      `json:"e"`
	At    time.Time `json:"t"`
	ID    uuid.UUID `json:"id"`
}

// PruneRemoteHelp deletes ended sessions that started before `before` (the log keeps 2 years).
func (s *Store) PruneRemoteHelp(ctx context.Context, before time.Time) (int64, error) {
	tag, err := s.Pool.Exec(ctx, `DELETE FROM remote_help_sessions WHERE state = 'ended' AND started_at < $1`, before)
	return tag.RowsAffected(), err
}

// FramesWithFeature reports which of these frames have a device whose latest heartbeat lists the feature.
func (s *Store) FramesWithFeature(ctx context.Context, frameIDs []uuid.UUID, feature string) (map[uuid.UUID]bool, error) {
	rows, err := s.Pool.Query(ctx, `SELECT f.id FROM frames f JOIN devices d ON d.id = f.device_id AND d.revoked_at IS NULL
		WHERE f.id = ANY($1) AND $2 = ANY(d.features)`, frameIDs, feature)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[uuid.UUID]bool{}
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out[id] = true
	}
	return out, rows.Err()
}

// UserTimezones returns the time zones the apps reported for these users (users without one are left out).
func (s *Store) UserTimezones(ctx context.Context, ids []uuid.UUID) (map[uuid.UUID]string, error) {
	out := map[uuid.UUID]string{}
	rows, err := s.Pool.Query(ctx, `SELECT id, timezone FROM users WHERE id = ANY($1) AND timezone IS NOT NULL`, ids)
	if err != nil {
		return out, err
	}
	defer rows.Close()
	for rows.Next() {
		var id uuid.UUID
		var tz string
		if err := rows.Scan(&id, &tz); err != nil {
			return out, err
		}
		out[id] = tz
	}
	return out, rows.Err()
}

// prefixed qualifies each column of a comma-separated list with a table alias.
func prefixed(alias, cols string) string {
	parts := strings.Split(cols, ",")
	for i, p := range parts {
		parts[i] = alias + strings.TrimSpace(p)
	}
	return strings.Join(parts, ", ")
}
