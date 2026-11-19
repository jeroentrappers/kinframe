package store

import (
	"context"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// PromptTitle is what a timeline post shows of the weekly prompt it answers (feed_prompts is server-engagement's
// table; read only here).
type PromptTitle struct {
	ID         uuid.UUID `db:"id"`
	Source     string    `db:"source"`
	LibraryKey *string   `db:"library_key"`
	Title      *string   `db:"title"`
}

// PromptTitles loads prompts by id.
func (s *Store) PromptTitles(ctx context.Context, q db.DBTX, ids []uuid.UUID) (map[uuid.UUID]PromptTitle, error) {
	out := map[uuid.UUID]PromptTitle{}
	if len(ids) == 0 {
		return out, nil
	}
	ps, err := many[PromptTitle](q.Query(ctx, `SELECT id, source, library_key, title FROM feed_prompts WHERE id = ANY($1)`, ids))
	if err != nil {
		return nil, err
	}
	for _, p := range ps {
		out[p.ID] = p
	}
	return out, nil
}

// PersonReactionsOf lists people's reactions on targets of one kind (target ids are unique across scopes),
// newest first.
func (s *Store) PersonReactionsOf(ctx context.Context, q db.DBTX, kind string, targets []uuid.UUID) ([]PersonReactionRow, error) {
	if len(targets) == 0 {
		return nil, nil
	}
	return many[PersonReactionRow](q.Query(ctx, `SELECT r.target_kind, r.target_id, r.user_id, r.emoji, r.created_at,
		CASE WHEN u.deleted_at IS NULL THEN u.display_name ELSE '' END AS name,
		CASE WHEN u.deleted_at IS NULL THEN u.avatar_key END AS avatar_key, u.deleted_at IS NOT NULL AS deleted
		FROM reactions r JOIN users u ON u.id = r.user_id
		WHERE r.target_kind = $1 AND r.target_id = ANY($2) ORDER BY r.created_at DESC, r.user_id`, kind, targets))
}
