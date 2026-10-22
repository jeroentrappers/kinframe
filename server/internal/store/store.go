// Package store is the PostgreSQL data access layer.
package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// ErrNotFound is returned when a row does not exist.
var ErrNotFound = errors.New("not found")

// Store wraps the pool. OnFeed is called after commit for every frame whose
// change feed advanced, with the new head seq; OnJob after a job is enqueued.
type Store struct {
	Pool   *pgxpool.Pool
	OnFeed func(frameID uuid.UUID, seq int64)
	OnJob  func()
}

func New(pool *pgxpool.Pool) *Store { return &Store{Pool: pool} }

// Q returns the pool as a query interface (for reads outside transactions).
func (s *Store) Q() db.DBTX { return s.Pool }

// Tx is a transaction that tracks feed and job side effects to publish after commit.
type Tx struct {
	pgx.Tx
	touched map[uuid.UUID]int64
	jobs    int
}

// InTx runs fn in a transaction and publishes feed hints after commit.
func (s *Store) InTx(ctx context.Context, fn func(tx *Tx) error) error {
	var t *Tx
	err := db.InTx(ctx, s.Pool, func(ptx pgx.Tx) error {
		t = &Tx{Tx: ptx, touched: map[uuid.UUID]int64{}}
		return fn(t)
	})
	if err != nil {
		return err
	}
	if s.OnFeed != nil {
		for f, seq := range t.touched {
			s.OnFeed(f, seq)
		}
	}
	if t.jobs > 0 && s.OnJob != nil {
		s.OnJob()
	}
	return nil
}

// NewID returns a UUIDv7.
func NewID() uuid.UUID { return uuid.Must(uuid.NewV7()) }

// ---------------------------------------------------------------- change feed

// Feed entity kinds.
const (
	FeedPlacement = "placement"
	FeedAlbum     = "album"
	FeedMember    = "member"
	FeedSettings  = "settings"
	FeedFrame     = "frame"
)

// FeedPut records that an entity on a frame changed (or was removed). It bumps
// the frame's seq under the frame row lock, which serialises feed writes per
// frame so readers never observe a gap.
func (tx *Tx) FeedPut(ctx context.Context, frameID uuid.UUID, kind string, entityID uuid.UUID, removed bool) error {
	var seq int64
	err := tx.QueryRow(ctx, `UPDATE frames SET latest_seq = latest_seq + 1 WHERE id = $1 RETURNING latest_seq`, frameID).Scan(&seq)
	if err != nil {
		return fmt.Errorf("bump seq: %w", err)
	}
	_, err = tx.Exec(ctx, `
		INSERT INTO feed (frame_id, kind, entity_id, seq, removed, updated_at) VALUES ($1, $2, $3, $4, $5, now())
		ON CONFLICT (frame_id, kind, entity_id) DO UPDATE SET seq = EXCLUDED.seq, removed = EXCLUDED.removed, updated_at = now()`,
		frameID, kind, entityID, seq, removed)
	if err != nil {
		return err
	}
	tx.touched[frameID] = seq
	return nil
}

// FeedPutMany is FeedPut for one entity on several frames, locking frames in a
// stable order to avoid deadlocks between concurrent multi-frame writers.
func (tx *Tx) FeedPutMany(ctx context.Context, frameIDs []uuid.UUID, kind string, entityID uuid.UUID, removed bool) error {
	ids := append([]uuid.UUID(nil), frameIDs...)
	sort.Slice(ids, func(i, j int) bool { return ids[i].String() < ids[j].String() })
	for _, f := range ids {
		if err := tx.FeedPut(ctx, f, kind, entityID, removed); err != nil {
			return err
		}
	}
	return nil
}

// FeedEntry is one row of the compacted feed.
type FeedEntry struct {
	Kind     string    `db:"kind"`
	EntityID uuid.UUID `db:"entity_id"`
	Seq      int64     `db:"seq"`
	Removed  bool      `db:"removed"`
}

// FeedSince returns up to limit entries with seq > since, in seq order.
func (s *Store) FeedSince(ctx context.Context, frameID uuid.UUID, since int64, limit int) ([]FeedEntry, error) {
	rows, err := s.Pool.Query(ctx, `SELECT kind, entity_id, seq, removed FROM feed WHERE frame_id = $1 AND seq > $2 ORDER BY seq LIMIT $3`,
		frameID, since, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByName[FeedEntry])
}

// PruneFeed deletes removal entries older than the retention window and raises
// each affected frame's floor so devices older than that get a reset.
func (s *Store) PruneFeed(ctx context.Context, retention time.Duration) (int64, error) {
	tag, err := s.Pool.Exec(ctx, `
		WITH gone AS (
			DELETE FROM feed WHERE removed AND updated_at < now() - make_interval(secs => $1)
			RETURNING frame_id, seq
		), floors AS (SELECT frame_id, max(seq) AS seq FROM gone GROUP BY frame_id)
		UPDATE frames f SET feed_floor_seq = GREATEST(f.feed_floor_seq, floors.seq + 1)
		FROM floors WHERE f.id = floors.frame_id`, retention.Seconds())
	return tag.RowsAffected(), err
}

// ---------------------------------------------------------------- jobs

// Job kinds.
const (
	JobProcessMedia     = "process_media"
	JobEnsureRenditions = "ensure_renditions"
	JobThumbs           = "thumbs"
)

type Job struct {
	ID       int64           `db:"id"`
	Kind     string          `db:"kind"`
	Payload  json.RawMessage `db:"payload"`
	Attempts int             `db:"attempts"`
}

// Enqueue adds a job; it becomes visible to workers at commit.
func (tx *Tx) Enqueue(ctx context.Context, kind string, payload any) error {
	b, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO jobs (kind, payload) VALUES ($1, $2)`, kind, b); err != nil {
		return err
	}
	tx.jobs++
	return nil
}

// ClaimJob leases the next runnable job for lease, or returns nil.
func (s *Store) ClaimJob(ctx context.Context, lease time.Duration) (*Job, error) {
	row := s.Pool.QueryRow(ctx, `
		UPDATE jobs SET locked_until = now() + make_interval(secs => $1), attempts = attempts + 1
		WHERE id = (
			SELECT id FROM jobs
			WHERE run_after <= now() AND (locked_until IS NULL OR locked_until < now())
			ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1)
		RETURNING id, kind, payload, attempts`, lease.Seconds())
	var j Job
	if err := row.Scan(&j.ID, &j.Kind, &j.Payload, &j.Attempts); err != nil {
		if db.NotFound(err) {
			return nil, nil
		}
		return nil, err
	}
	return &j, nil
}

// CompleteJob deletes a finished job.
func (s *Store) CompleteJob(ctx context.Context, id int64) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM jobs WHERE id = $1`, id)
	return err
}

// RetryJob releases a job to run again after delay.
func (s *Store) RetryJob(ctx context.Context, id int64, delay time.Duration, cause error) error {
	_, err := s.Pool.Exec(ctx, `UPDATE jobs SET locked_until = NULL, run_after = now() + make_interval(secs => $2), last_error = $3 WHERE id = $1`,
		id, delay.Seconds(), cause.Error())
	return err
}

// QueueLength counts pending jobs.
func (s *Store) QueueLength(ctx context.Context) (int, error) {
	var n int
	err := s.Pool.QueryRow(ctx, `SELECT count(*) FROM jobs`).Scan(&n)
	return n, err
}

// ---------------------------------------------------------------- idempotency

// IdempotentResult loads a stored response for (user, scope, key) into out.
func IdempotentResult(ctx context.Context, q db.DBTX, userID uuid.UUID, scope, key string, out any) (bool, error) {
	var raw []byte
	err := q.QueryRow(ctx, `SELECT response FROM idempotency_keys WHERE user_id = $1 AND scope = $2 AND key = $3`, userID, scope, key).Scan(&raw)
	if db.NotFound(err) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, json.Unmarshal(raw, out)
}

// SaveIdempotent stores a response. A unique violation means a concurrent
// request with the same key won; callers roll back and re-read.
func (tx *Tx) SaveIdempotent(ctx context.Context, userID uuid.UUID, scope, key string, response any) error {
	b, err := json.Marshal(response)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `INSERT INTO idempotency_keys (user_id, scope, key, response) VALUES ($1, $2, $3, $4)`, userID, scope, key, b)
	return err
}

// PruneIdempotency removes keys older than a day.
func (s *Store) PruneIdempotency(ctx context.Context) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM idempotency_keys WHERE created_at < now() - interval '24 hours'`)
	return err
}

// ---------------------------------------------------------------- server settings

// GetServerSetting loads a JSON value into out; ok is false if unset.
func (s *Store) GetServerSetting(ctx context.Context, key string, out any) (bool, error) {
	var raw []byte
	err := s.Pool.QueryRow(ctx, `SELECT value FROM server_settings WHERE key = $1`, key).Scan(&raw)
	if db.NotFound(err) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, json.Unmarshal(raw, out)
}

func (s *Store) PutServerSetting(ctx context.Context, key string, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	_, err = s.Pool.Exec(ctx, `INSERT INTO server_settings (key, value) VALUES ($1, $2) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value`, key, b)
	return err
}

func one[T any](rows pgx.Rows, err error) (*T, error) {
	if err != nil {
		return nil, err
	}
	v, err := pgx.CollectExactlyOneRow(rows, pgx.RowToAddrOfStructByName[T])
	if db.NotFound(err) {
		return nil, ErrNotFound
	}
	return v, err
}

func many[T any](rows pgx.Rows, err error) ([]T, error) {
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByName[T])
}
