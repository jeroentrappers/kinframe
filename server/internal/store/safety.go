package store

// Reports (README §5.19 "Blocks and reports"): a family's reports go to its owner and admins, everything else
// to the server's operators.

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Report is a stored report with the reported content's author.
type Report struct {
	ID              uuid.UUID  `db:"id"`
	ReporterUserID  *uuid.UUID `db:"reporter_user_id"`
	TargetKind      string     `db:"target_kind"`
	TargetID        uuid.UUID  `db:"target_id"`
	TargetUserID    *uuid.UUID `db:"target_user_id"`
	PostID          *uuid.UUID `db:"post_id"`
	ConversationID  *uuid.UUID `db:"conversation_id"`
	FeedID          *uuid.UUID `db:"feed_id"`
	Reason          string     `db:"reason"`
	Note            *string    `db:"note"`
	Excerpt         *string    `db:"excerpt"`
	ExcerptEnc      []byte     `db:"excerpt_enc"`
	Status          string     `db:"status"`
	ResolvedBy      *uuid.UUID `db:"resolved_by"`
	ResolvedAt      *time.Time `db:"resolved_at"`
	CreatedAt       time.Time  `db:"created_at"`
	AuthorName      *string    `db:"author_name"`
	AuthorAvatarKey *string    `db:"author_avatar_key"`
	AuthorDeleted   bool       `db:"author_deleted"`
}

const reportSelect = `SELECT r.id, r.reporter_user_id, r.target_kind, r.target_id, r.target_user_id, r.post_id, r.conversation_id,
	r.feed_id, r.reason, r.note, r.excerpt, r.excerpt_enc, r.status, r.resolved_by, r.resolved_at, r.created_at,
	u.display_name AS author_name, u.avatar_key AS author_avatar_key, coalesce(u.deleted_at IS NOT NULL, false) AS author_deleted
	FROM reports r LEFT JOIN users u ON u.id = r.target_user_id`

func (s *Store) GetReport(ctx context.Context, q db.DBTX, id uuid.UUID) (*Report, error) {
	return one[Report](q.Query(ctx, reportSelect+` WHERE r.id = $1`, id))
}

// OpenReportOf is the reporter's open report on a target, if any.
func (s *Store) OpenReportOf(ctx context.Context, q db.DBTX, reporter uuid.UUID, kind string, target uuid.UUID) (*Report, error) {
	return one[Report](q.Query(ctx, reportSelect+` WHERE r.reporter_user_id = $1 AND r.target_kind = $2 AND r.target_id = $3 AND r.status = 'open'`,
		reporter, kind, target))
}

// CreateReport stores a report.
func (tx *Tx) CreateReport(ctx context.Context, r Report) error {
	_, err := tx.Exec(ctx, `INSERT INTO reports (id, reporter_user_id, target_kind, target_id, target_user_id, post_id, conversation_id,
			feed_id, reason, note, excerpt, excerpt_enc)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
		r.ID, r.ReporterUserID, r.TargetKind, r.TargetID, r.TargetUserID, r.PostID, r.ConversationID, r.FeedID, r.Reason, r.Note,
		r.Excerpt, r.ExcerptEnc)
	return err
}

// ListFeedReports: open reports first, then those resolved in the last 30 days.
func (s *Store) ListFeedReports(ctx context.Context, feedID uuid.UUID) ([]Report, error) {
	return many[Report](s.Pool.Query(ctx, reportSelect+` WHERE r.feed_id = $1
		AND (r.status = 'open' OR r.resolved_at > now() - interval '30 days')
		ORDER BY r.status <> 'open', r.created_at DESC`, feedID))
}

// ListOperatorReports lists reports outside families: status open, resolved or all.
func (s *Store) ListOperatorReports(ctx context.Context, status string) ([]Report, error) {
	filter := ` AND r.status = 'open'`
	switch status {
	case "resolved":
		filter = ` AND r.status <> 'open'`
	case "all":
		filter = ``
	}
	return many[Report](s.Pool.Query(ctx, reportSelect+` WHERE r.feed_id IS NULL`+filter+` ORDER BY r.status <> 'open', r.created_at DESC LIMIT 500`))
}

// ResolveReport closes an open report; false when someone else was first.
func (tx *Tx) ResolveReport(ctx context.Context, id uuid.UUID, status string, by uuid.UUID) (bool, error) {
	tag, err := tx.Exec(ctx, `UPDATE reports SET status = $2, resolved_by = $3, resolved_at = now() WHERE id = $1 AND status = 'open'`, id, status, by)
	return tag.RowsAffected() == 1, err
}

// ResolveOpenReportsOn closes every other open report on the same target once its content was removed.
func (tx *Tx) ResolveOpenReportsOn(ctx context.Context, kind string, target, by uuid.UUID) error {
	_, err := tx.Exec(ctx, `UPDATE reports SET status = 'removed', resolved_by = $3, resolved_at = now()
		WHERE target_kind = $1 AND target_id = $2 AND status = 'open'`, kind, target, by)
	return err
}

// ---------------------------------------------------------------- operators

// NoteOperator remembers a user who signed in with the operator scope (reports go to them).
func (s *Store) NoteOperator(ctx context.Context, userID uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `INSERT INTO chat_operators (user_id, seen_at) VALUES ($1, now())
		ON CONFLICT (user_id) DO UPDATE SET seen_at = now()`, userID)
	return err
}

// RecentOperators are operators seen in the last 90 days with live accounts.
func (s *Store) RecentOperators(ctx context.Context) ([]uuid.UUID, error) {
	return uuids(s.Pool.Query(ctx, `SELECT o.user_id FROM chat_operators o JOIN users u ON u.id = o.user_id AND u.deleted_at IS NULL
		WHERE o.seen_at > now() - interval '90 days'`))
}
