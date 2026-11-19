package api

// Family social on the frame (README §5.20 "The frame"): the `comments`, `photo_request` and `prompts`
// changes, Placement.prompt_id and settings.social.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

const (
	frameComments    = 3
	frameCommentRune = 280
)

// engagementChange renders an engagement change of the frame's feed (nil: not an engagement kind, or nothing
// to send in a snapshot). handled is false for other kinds.
func (s *Server) engagementChange(ctx context.Context, f *store.Frame, e store.FeedEntry, snapshot bool) (map[string]any, bool, error) {
	switch e.Kind {
	case store.FeedComments:
		c, err := s.placementComments(ctx, s.st.Q(), f, e.EntityID)
		if err != nil {
			return nil, true, err
		}
		if c == nil && snapshot {
			return nil, true, nil
		}
		return map[string]any{"seq": e.Seq, "type": "comments", "id": e.EntityID, "comments": c}, true, nil
	case store.FeedPhotoRequest:
		r, err := s.framePhotoRequestChange(ctx, f.ID)
		if err != nil {
			return nil, true, err
		}
		if r == nil && snapshot {
			return nil, true, nil
		}
		return map[string]any{"seq": e.Seq, "type": "photo_request", "id": f.ID, "photo_request": r}, true, nil
	case store.FeedPrompts:
		p, err := s.framePrompts(ctx, s.st.Q(), f)
		if err != nil {
			return nil, true, err
		}
		if p == nil && snapshot {
			return nil, true, nil
		}
		return map[string]any{"seq": e.Seq, "type": "prompts", "id": f.ID, "prompts": p}, true, nil
	}
	return nil, false, nil
}

// placementPromptIDs returns Placement.prompt_id for the placements of a change feed page.
func (s *Server) placementPromptIDs(ctx context.Context, placementIDs []uuid.UUID) map[uuid.UUID]uuid.UUID {
	out, err := s.st.PlacementPrompts(ctx, s.st.Q(), placementIDs)
	if err != nil {
		s.log.Warn("placement prompts", "err", err)
	}
	return out
}

// ---------------------------------------------------------------- comments on the frame

type frameCommentRow struct {
	ID        uuid.UUID `db:"id"`
	Author    string    `db:"display_name"`
	Body      *string   `db:"body"`
	BodyEnc   []byte    `db:"body_enc"`
	CreatedAt time.Time `db:"created_at"`
}

// cutRunes shortens text to n characters with "…".
func cutRunes(text string, n int) string {
	r := []rune(strings.TrimSpace(text))
	if len(r) <= n {
		return string(r)
	}
	return strings.TrimSpace(string(r[:n-1])) + "…"
}

// placementComments renders the `comments` payload: the three newest person comments of the placement's
// whole-family feed post, first names only (nil: none, or the frame shows no comments).
func (s *Server) placementComments(ctx context.Context, q db.DBTX, f *store.Frame, placementID uuid.UUID) (*gen.PlacementComments, error) {
	if !frameSocialSetting(f, "show_comments", false) {
		return nil, nil
	}
	if c, handled, err := s.remotePlacementComments(ctx, q, f, placementID); handled { // a copy from another server (README §5.22)
		return c, err
	}
	rows, err := engRows[frameCommentRow](q.Query(ctx, `
		SELECT c.id, u.display_name, c.body, c.body_enc, c.created_at
		FROM placements p
		JOIN feed_deliveries d ON d.placement_id = p.id
		JOIN subscriptions su ON su.id = d.subscription_id AND su.frame_id = p.frame_id
		JOIN feed_posts fp ON fp.id = d.post_id AND fp.removed_at IS NULL AND fp.circle_id IS NULL
		JOIN comments c ON c.post_id = fp.id AND c.kind = 'person' AND c.deleted_at IS NULL AND c.removed_at IS NULL
		JOIN users u ON u.id = c.author_user_id AND u.deleted_at IS NULL
		WHERE p.id = $1 AND p.frame_id = $2 AND p.removed_at IS NULL
		ORDER BY c.id DESC LIMIT $3`, placementID, f.ID, frameComments))
	if err != nil {
		return nil, err
	}
	out := gen.PlacementComments{PlacementId: placementID}
	for _, r := range rows {
		body := deref(r.Body)
		if len(r.BodyEnc) > 0 && s.bodies != nil {
			if b, err := s.bodies.Open(r.BodyEnc); err == nil {
				body = b
			}
		}
		if strings.TrimSpace(body) == "" {
			continue
		}
		out.Comments = append(out.Comments, struct {
			AuthorName string    `json:"author_name"`
			Body       string    `json:"body"`
			CreatedAt  time.Time `json:"created_at"`
			Id         gen.Id    `json:"id"`
		}{AuthorName: engFirstName(r.Author), Body: cutRunes(body, frameCommentRune), CreatedAt: r.CreatedAt, Id: r.ID})
	}
	if len(out.Comments) == 0 {
		return nil, nil
	}
	return &out, nil
}

func commentsDigest(c *gen.PlacementComments) string {
	if c == nil {
		return ""
	}
	b, _ := json.Marshal(c.Comments)
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// syncCommentCards brings the frame's `comments` changes up to date for these placements (nil: every
// placement with comments and every one the frame has cards of).
func (s *Server) syncCommentCards(ctx context.Context, frameID uuid.UUID, placements []uuid.UUID) error {
	return s.st.InTx(ctx, func(tx *store.Tx) error {
		f, err := tx.LockFrame(ctx, frameID)
		if err == store.ErrNotFound {
			return nil
		} else if err != nil {
			return err
		}
		show := frameSocialSetting(f, "show_comments", false)
		ids := placements
		if ids == nil {
			rows, err := engRows[engID](tx.Query(ctx, `
				SELECT DISTINCT p.id FROM placements p JOIN feed_deliveries d ON d.placement_id = p.id
				WHERE $2 AND p.frame_id = $1 AND p.removed_at IS NULL AND EXISTS (
					SELECT 1 FROM comments c WHERE c.post_id = d.post_id AND c.kind = 'person')
				UNION SELECT placement_id FROM frame_comment_cards WHERE frame_id = $1`, frameID, show))
			if err != nil {
				return err
			}
			for _, r := range rows {
				ids = append(ids, r.ID)
			}
		}
		prev := map[uuid.UUID]string{}
		type card struct {
			PlacementID uuid.UUID `db:"placement_id"`
			Digest      string    `db:"digest"`
		}
		cards, err := engRows[card](tx.Query(ctx, `SELECT placement_id, digest FROM frame_comment_cards WHERE frame_id = $1 AND placement_id = ANY($2)`, frameID, ids))
		if err != nil {
			return err
		}
		for _, c := range cards {
			prev[c.PlacementID] = c.Digest
		}
		var changed []uuid.UUID
		for _, id := range ids {
			c, err := s.placementComments(ctx, tx, f, id)
			if err != nil {
				return err
			}
			digest := commentsDigest(c)
			if digest == prev[id] {
				continue
			}
			if digest == "" {
				if _, err := tx.Exec(ctx, `DELETE FROM frame_comment_cards WHERE placement_id = $1`, id); err != nil {
					return err
				}
			} else if _, err := tx.Exec(ctx, `INSERT INTO frame_comment_cards (placement_id, frame_id, digest, emitted_at) VALUES ($1, $2, $3, now())
				ON CONFLICT (placement_id) DO UPDATE SET digest = EXCLUDED.digest, emitted_at = now()`, id, frameID, digest); err != nil {
				return err
			}
			changed = append(changed, id)
		}
		if len(changed) == 0 {
			return nil
		}
		return tx.FeedPutEntities(ctx, frameID, store.FeedComments, changed, false)
	})
}

// syncChangedComments handles posts whose comments changed: their placements' frames get new cards.
func (s *Server) syncChangedComments(ctx context.Context) {
	type changedPost struct {
		PostID    uuid.UUID `db:"post_id"`
		ChangedAt time.Time `db:"changed_at"`
	}
	posts, err := engRows[changedPost](s.st.Q().Query(ctx, `SELECT post_id, changed_at FROM engagement_comment_changes ORDER BY changed_at LIMIT 200`))
	if err != nil || len(posts) == 0 {
		return
	}
	for _, p := range posts {
		byFrame, err := s.st.PostPlacements(ctx, s.st.Q(), p.PostID)
		if err != nil {
			continue
		}
		frames := make([]uuid.UUID, 0, len(byFrame))
		for f := range byFrame {
			frames = append(frames, f)
		}
		sort.Slice(frames, func(i, j int) bool { return frames[i].String() < frames[j].String() })
		failed := false
		for _, f := range frames {
			if err := s.syncCommentCards(ctx, f, byFrame[f]); err != nil {
				s.log.Warn("comment cards", "frame", f, "err", err)
				failed = true
			}
		}
		if !failed {
			_, _ = s.st.Q().Exec(ctx, `DELETE FROM engagement_comment_changes WHERE post_id = $1 AND changed_at <= $2`, p.PostID, p.ChangedAt)
		}
	}
}

// syncAllCommentCards re-syncs frames with "Show comments" on and frames that still have cards (the setting
// was turned off).
func (s *Server) syncAllCommentCards(ctx context.Context) {
	rows, err := engRows[engID](s.st.Q().Query(ctx, `SELECT id FROM frames WHERE deleted_at IS NULL AND (settings->'social'->>'show_comments')::boolean
		UNION SELECT DISTINCT frame_id FROM frame_comment_cards ORDER BY 1`))
	if err != nil {
		return
	}
	for _, r := range rows {
		if err := s.syncCommentCards(ctx, r.ID, nil); err != nil {
			s.log.Warn("comment cards", "frame", r.ID, "err", err)
		}
	}
}

// socialSettingsChanged reacts to a settings patch touching settings.social (comment cards on or off).
func (s *Server) socialSettingsChanged(ctx context.Context, frameID uuid.UUID, paths []string) {
	for _, p := range paths {
		if p == "social" || strings.HasPrefix(p, "social.") {
			if err := s.syncCommentCards(ctx, frameID, nil); err != nil {
				s.log.Warn("comment cards", "frame", frameID, "err", err)
			}
			return
		}
	}
}
