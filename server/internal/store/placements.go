package store

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

type Placement struct {
	ID                uuid.UUID  `db:"id"`
	FrameID           uuid.UUID  `db:"frame_id"`
	ItemID            uuid.UUID  `db:"item_id"`
	SendID            *uuid.UUID `db:"send_id"`
	Status            string     `db:"status"`
	Hidden            bool       `db:"hidden"`
	TrashedAt         *time.Time `db:"trashed_at"`
	PendingModeration bool       `db:"pending_moderation"`
	FitOverride       *string    `db:"fit_override"`
	FocalOverrideX    *float64   `db:"focal_override_x"`
	FocalOverrideY    *float64   `db:"focal_override_y"`
	Rotation          int        `db:"rotation"`
	Reaction          *string    `db:"reaction"`
	DeliveredAt       *time.Time `db:"delivered_at"`
	SeenAt            *time.Time `db:"seen_at"`
	ReceivedAt        time.Time  `db:"received_at"`
	RemovedAt         *time.Time `db:"removed_at"`
}

const placementCols = `p.id, p.frame_id, p.item_id, p.send_id, p.status, p.hidden, p.trashed_at, p.pending_moderation,
	p.fit_override, p.focal_override_x, p.focal_override_y, p.rotation, p.reaction, p.delivered_at, p.seen_at,
	p.received_at, p.removed_at`

type Reply struct {
	Preset string    `json:"preset"`
	At     time.Time `json:"at"`
}

// PlacementRow is a placement with its item, replies and albums.
type PlacementRow struct {
	Placement
	ItemRow
	Replies  []Reply     `db:"replies"`
	AlbumIDs []uuid.UUID `db:"album_ids"`
}

// placementRowCols omits p.item_id: ItemRow supplies the item_id column for both structs.
const placementRowCols = `p.id, p.frame_id, p.send_id, p.status, p.hidden, p.trashed_at, p.pending_moderation,
	p.fit_override, p.focal_override_x, p.focal_override_y, p.rotation, p.reaction, p.delivered_at, p.seen_at,
	p.received_at, p.removed_at`

const placementRowSelect = `SELECT ` + placementRowCols + `, ` + itemRowCols + `,
	COALESCE((SELECT json_agg(json_build_object('preset', r.preset, 'at', r.at) ORDER BY r.at)
		FROM placement_replies r WHERE r.placement_id = p.id), '[]'::json) AS replies,
	COALESCE((SELECT array_agg(ap.album_id ORDER BY ap.album_id) FROM album_placements ap WHERE ap.placement_id = p.id), '{}') AS album_ids
	FROM placements p JOIN items i ON i.id = p.item_id` + itemJoins

func (tx *Tx) CreatePlacement(ctx context.Context, p Placement) error {
	_, err := tx.Exec(ctx, `
		INSERT INTO placements (id, frame_id, item_id, send_id, status, pending_moderation) VALUES ($1, $2, $3, $4, $5, $6)`,
		p.ID, p.FrameID, p.ItemID, p.SendID, p.Status, p.PendingModeration)
	return err
}

// GetPlacementRow returns a live placement (optionally constrained to a frame).
func (s *Store) GetPlacementRow(ctx context.Context, q db.DBTX, id uuid.UUID, frameID *uuid.UUID) (*PlacementRow, error) {
	return one[PlacementRow](q.Query(ctx, placementRowSelect+` WHERE p.id = $1 AND p.removed_at IS NULL AND ($2::uuid IS NULL OR p.frame_id = $2)`, id, frameID))
}

// PlacementRowsByIDs loads placements including removed ones (for the feed).
func (s *Store) PlacementRowsByIDs(ctx context.Context, ids []uuid.UUID) (map[uuid.UUID]PlacementRow, error) {
	rows, err := many[PlacementRow](s.Pool.Query(ctx, placementRowSelect+` WHERE p.id = ANY($1)`, ids))
	if err != nil {
		return nil, err
	}
	out := make(map[uuid.UUID]PlacementRow, len(rows))
	for _, r := range rows {
		out[r.Placement.ID] = r
	}
	return out, nil
}

// LockPlacement returns a live placement on the frame, locked.
func (tx *Tx) LockPlacement(ctx context.Context, frameID, id uuid.UUID) (*Placement, error) {
	return one[Placement](tx.Query(ctx, `SELECT `+placementCols+` FROM placements p WHERE p.id = $1 AND p.frame_id = $2 AND p.removed_at IS NULL FOR UPDATE`, id, frameID))
}

type PlacementPatch struct {
	Hidden         *bool
	Trashed        *bool
	Approved       *bool
	FitOverride    *string
	FocalOverrideX *float64
	FocalOverrideY *float64
	Rotation       *int
}

// UpdatePlacement applies a patch and records the change in the frame's feed.
func (tx *Tx) UpdatePlacement(ctx context.Context, frameID, id uuid.UUID, p PlacementPatch) error {
	_, err := tx.Exec(ctx, `
		UPDATE placements SET
			hidden = COALESCE($3, hidden),
			trashed_at = CASE WHEN $4::boolean IS NULL THEN trashed_at WHEN $4 THEN COALESCE(trashed_at, now()) ELSE NULL END,
			pending_moderation = CASE WHEN $5::boolean IS NULL THEN pending_moderation ELSE NOT $5 END,
			fit_override = COALESCE($6, fit_override),
			focal_override_x = COALESCE($7, focal_override_x), focal_override_y = COALESCE($8, focal_override_y),
			rotation = COALESCE($9, rotation)
		WHERE id = $1 AND frame_id = $2`, id, frameID, p.Hidden, p.Trashed, p.Approved, p.FitOverride, p.FocalOverrideX, p.FocalOverrideY, p.Rotation)
	if err != nil {
		return err
	}
	return tx.FeedPut(ctx, frameID, FeedPlacement, id, false)
}

// RemovePlacements soft-removes placements and records removals in the feed.
// Returns the removed placements.
func (tx *Tx) RemovePlacements(ctx context.Context, where string, args ...any) ([]Placement, error) {
	removed, err := many[Placement](tx.Query(ctx, `UPDATE placements p SET removed_at = now() WHERE p.removed_at IS NULL AND (`+where+`) RETURNING `+placementCols, args...))
	if err != nil {
		return nil, err
	}
	for _, p := range removed {
		if err := tx.FeedPut(ctx, p.FrameID, FeedPlacement, p.ID, true); err != nil {
			return nil, err
		}
	}
	return removed, nil
}

func (tx *Tx) SetReaction(ctx context.Context, frameID, id uuid.UUID, reaction *string) error {
	_, err := tx.Exec(ctx, `UPDATE placements SET reaction = $3, reaction_at = now() WHERE id = $1 AND frame_id = $2`, id, frameID, reaction)
	if err != nil {
		return err
	}
	return tx.FeedPut(ctx, frameID, FeedPlacement, id, false)
}

func (tx *Tx) AddReply(ctx context.Context, placementID uuid.UUID, preset string) error {
	_, err := tx.Exec(ctx, `INSERT INTO placement_replies (id, placement_id, preset) VALUES ($1, $2, $3)`, NewID(), placementID, preset)
	return err
}

// StatusChange is a placement whose delivery status changed.
type StatusChange struct {
	PlacementID uuid.UUID  `db:"id"`
	FrameID     uuid.UUID  `db:"frame_id"`
	SendID      *uuid.UUID `db:"send_id"`
	SenderID    *uuid.UUID `db:"sender_user_id"`
}

// MarkDelivered moves ready placements on the frame to delivered.
func (tx *Tx) MarkDelivered(ctx context.Context, frameID uuid.UUID, ids []uuid.UUID) ([]StatusChange, error) {
	return many[StatusChange](tx.Query(ctx, `
		UPDATE placements p SET status = 'delivered', delivered_at = now() FROM items i
		WHERE i.id = p.item_id AND p.frame_id = $1 AND p.id = ANY($2) AND p.status IN ('pending', 'ready') AND p.removed_at IS NULL
		RETURNING p.id, p.frame_id, p.send_id, i.sender_user_id`, frameID, ids))
}

// MarkSeen moves placements to seen (delivered implied) at the given time.
func (tx *Tx) MarkSeen(ctx context.Context, frameID, id uuid.UUID, at time.Time) ([]StatusChange, error) {
	return many[StatusChange](tx.Query(ctx, `
		UPDATE placements p SET status = 'seen', seen_at = $3, delivered_at = COALESCE(p.delivered_at, $3) FROM items i
		WHERE i.id = p.item_id AND p.frame_id = $1 AND p.id = $2 AND p.status IN ('pending', 'ready', 'delivered') AND p.removed_at IS NULL
		RETURNING p.id, p.frame_id, p.send_id, i.sender_user_id`, frameID, id, at))
}

// MarkReady moves pending placements of a media on a frame to ready.
func (tx *Tx) MarkReady(ctx context.Context, mediaID, frameID uuid.UUID) ([]StatusChange, error) {
	changed, err := many[StatusChange](tx.Query(ctx, `
		UPDATE placements p SET status = 'ready' FROM items i
		WHERE i.id = p.item_id AND i.media_id = $1 AND p.frame_id = $2 AND p.status = 'pending' AND p.removed_at IS NULL
		RETURNING p.id, p.frame_id, p.send_id, i.sender_user_id`, mediaID, frameID))
	if err != nil {
		return nil, err
	}
	return changed, nil
}

// LivePlacementsOfMedia lists live placement IDs of a media on a frame (for feed bumps).
func (s *Store) LivePlacementsOfMedia(ctx context.Context, q db.DBTX, mediaID, frameID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT p.id FROM placements p JOIN items i ON i.id = p.item_id
		WHERE i.media_id = $1 AND p.frame_id = $2 AND p.removed_at IS NULL AND p.status <> 'failed'`, mediaID, frameID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

func collectIDs(rows pgx.Rows) ([]uuid.UUID, error) {
	defer rows.Close()
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

// PurgeExpiredTrash removes placements whose trash retention elapsed.
func (s *Store) PurgeExpiredTrash(ctx context.Context, days int) (int, error) {
	n := 0
	err := s.InTx(ctx, func(tx *Tx) error {
		removed, err := tx.RemovePlacements(ctx, `p.trashed_at < now() - make_interval(days => $1)`, days)
		n = len(removed)
		return err
	})
	return n, err
}

// ---------------------------------------------------------------- library listing

type PlacementFilter struct {
	FrameID    uuid.UUID
	OnlySender *uuid.UUID // restrict to the caller's own placements
	Visibility string     // visible, hidden, trashed, pending_moderation, all
	SenderID   *uuid.UUID
	Kind       *string
	AlbumID    *uuid.UUID
	Sort       string // received_desc, received_asc, taken_desc, taken_asc
	Cursor     string
	Limit      int
}

type cursor struct {
	T  time.Time `json:"t"`
	ID uuid.UUID `json:"id"`
}

func encodeCursor(t time.Time, id uuid.UUID) string {
	b, _ := json.Marshal(cursor{t, id})
	return base64.RawURLEncoding.EncodeToString(b)
}

func decodeCursor(s string) (*cursor, error) {
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return nil, fmt.Errorf("bad cursor")
	}
	var c cursor
	if err := json.Unmarshal(b, &c); err != nil {
		return nil, fmt.Errorf("bad cursor")
	}
	return &c, nil
}

// ErrBadCursor is returned for an undecodable cursor.
var ErrBadCursor = fmt.Errorf("bad cursor")

func (s *Store) ListPlacements(ctx context.Context, f PlacementFilter) ([]PlacementRow, *string, error) {
	sortKey := `p.received_at`
	if f.Sort == "taken_desc" || f.Sort == "taken_asc" {
		sortKey = `COALESCE(m.captured_at, i.captured_hint, p.received_at)`
	}
	desc := f.Sort == "" || f.Sort == "received_desc" || f.Sort == "taken_desc"
	cmp, dir := ">", "ASC"
	if desc {
		cmp, dir = "<", "DESC"
	}
	where := `p.frame_id = $1 AND p.removed_at IS NULL`
	args := []any{f.FrameID}
	arg := func(v any) string { args = append(args, v); return fmt.Sprintf("$%d", len(args)) }
	switch f.Visibility {
	case "", "visible":
		where += ` AND NOT p.hidden AND p.trashed_at IS NULL AND NOT p.pending_moderation`
	case "hidden":
		where += ` AND p.hidden AND p.trashed_at IS NULL`
	case "trashed":
		where += ` AND p.trashed_at IS NOT NULL`
	case "pending_moderation":
		where += ` AND p.pending_moderation AND p.trashed_at IS NULL`
	}
	if f.OnlySender != nil {
		where += ` AND i.sender_user_id = ` + arg(*f.OnlySender)
	}
	if f.SenderID != nil {
		where += ` AND (i.sender_user_id = ` + arg(*f.SenderID) + ` OR i.sender_frame_id = $` + fmt.Sprint(len(args)) + `)`
	}
	if f.Kind != nil {
		where += ` AND m.kind = ` + arg(*f.Kind)
	}
	if f.AlbumID != nil {
		where += ` AND EXISTS (SELECT 1 FROM album_placements ap WHERE ap.placement_id = p.id AND ap.album_id = ` + arg(*f.AlbumID) + `)`
	}
	if f.Cursor != "" {
		c, err := decodeCursor(f.Cursor)
		if err != nil {
			return nil, nil, ErrBadCursor
		}
		where += fmt.Sprintf(` AND (%s, p.id) %s (%s, %s)`, sortKey, cmp, arg(c.T), arg(c.ID))
	}
	q := placementRowSelect + ` WHERE ` + where + fmt.Sprintf(` ORDER BY %s %s, p.id %s LIMIT %d`, sortKey, dir, dir, f.Limit+1)
	rows, err := many[PlacementRow](s.Pool.Query(ctx, q, args...))
	if err != nil {
		return nil, nil, err
	}
	var next *string
	if len(rows) > f.Limit {
		rows = rows[:f.Limit]
		last := rows[len(rows)-1]
		t := last.ReceivedAt
		if f.Sort == "taken_desc" || f.Sort == "taken_asc" {
			switch {
			case last.CapturedAt != nil:
				t = *last.CapturedAt
			case last.CapturedHint != nil:
				t = *last.CapturedHint
			}
		}
		c := encodeCursor(t, last.Placement.ID)
		next = &c
	}
	return rows, next, nil
}

type PlacementCounts struct {
	Photos  int `db:"photos"`
	Videos  int `db:"videos"`
	Hidden  int `db:"hidden"`
	Trashed int `db:"trashed"`
}

func (s *Store) CountPlacements(ctx context.Context, frameID uuid.UUID, onlySender *uuid.UUID) (*PlacementCounts, error) {
	return one[PlacementCounts](s.Pool.Query(ctx, `
		SELECT count(*) FILTER (WHERE m.kind = 'photo' AND NOT p.hidden AND p.trashed_at IS NULL)::int AS photos,
			count(*) FILTER (WHERE m.kind = 'video' AND NOT p.hidden AND p.trashed_at IS NULL)::int AS videos,
			count(*) FILTER (WHERE p.hidden AND p.trashed_at IS NULL)::int AS hidden,
			count(*) FILTER (WHERE p.trashed_at IS NOT NULL)::int AS trashed
		FROM placements p JOIN items i ON i.id = p.item_id JOIN media m ON m.id = i.media_id
		WHERE p.frame_id = $1 AND p.removed_at IS NULL AND ($2::uuid IS NULL OR i.sender_user_id = $2)`, frameID, onlySender))
}

// ---------------------------------------------------------------- sends

func (tx *Tx) CreateSend(ctx context.Context, id, senderID uuid.UUID) error {
	_, err := tx.Exec(ctx, `INSERT INTO sends (id, sender_user_id) VALUES ($1, $2)`, id, senderID)
	return err
}

func (tx *Tx) AddSendItem(ctx context.Context, sendID, itemID uuid.UUID, pos int) error {
	_, err := tx.Exec(ctx, `INSERT INTO send_items (send_id, item_id, position) VALUES ($1, $2, $3)`, sendID, itemID, pos)
	return err
}

type Send struct {
	ID        uuid.UUID `db:"id"`
	SenderID  uuid.UUID `db:"sender_user_id"`
	CreatedAt time.Time `db:"created_at"`
}

func (s *Store) GetSend(ctx context.Context, q db.DBTX, id uuid.UUID) (*Send, error) {
	return one[Send](q.Query(ctx, `SELECT id, sender_user_id, created_at FROM sends WHERE id = $1`, id))
}

// ListSends pages the sender's sends, newest first. state: ongoing, delivered or all.
func (s *Store) ListSends(ctx context.Context, senderID uuid.UUID, state string, after *uuid.UUID, limit int) ([]Send, error) {
	filter := ""
	switch state {
	case "ongoing":
		filter = ` AND EXISTS (SELECT 1 FROM placements p WHERE p.send_id = s.id AND p.removed_at IS NULL AND p.status IN ('pending','ready'))`
	case "delivered":
		filter = ` AND EXISTS (SELECT 1 FROM placements p WHERE p.send_id = s.id AND p.removed_at IS NULL)
			AND NOT EXISTS (SELECT 1 FROM placements p WHERE p.send_id = s.id AND p.removed_at IS NULL AND p.status IN ('pending','ready'))`
	}
	return many[Send](s.Pool.Query(ctx, `SELECT s.id, s.sender_user_id, s.created_at FROM sends s
		WHERE s.sender_user_id = $1 AND ($2::uuid IS NULL OR s.id < $2)`+filter+` ORDER BY s.id DESC LIMIT $3`, senderID, after, limit))
}

// SendItemRows returns the items of sends, in send order.
type SendItemRow struct {
	SendID   uuid.UUID `db:"send_id"`
	Position int       `db:"position"`
	ItemRow
}

func (s *Store) SendItemRows(ctx context.Context, q db.DBTX, sendIDs []uuid.UUID) ([]SendItemRow, error) {
	return many[SendItemRow](q.Query(ctx, `SELECT si.send_id, si.position, `+itemRowCols+`
		FROM send_items si JOIN items i ON i.id = si.item_id`+itemJoins+`
		WHERE si.send_id = ANY($1) ORDER BY si.send_id, si.position`, sendIDs))
}

type SendPlacementRow struct {
	PlacementID uuid.UUID  `db:"id"`
	SendID      uuid.UUID  `db:"send_id"`
	ItemID      uuid.UUID  `db:"item_id"`
	FrameID     uuid.UUID  `db:"frame_id"`
	FrameName   string     `db:"frame_name"`
	Status      string     `db:"status"`
	Removed     bool       `db:"removed"`
	Reaction    *string    `db:"reaction"`
	Replies     []string   `db:"replies"`
	DeviceID    *uuid.UUID `db:"device_id"`
	MediaStatus string     `db:"media_status"`
}

func (s *Store) SendPlacementRows(ctx context.Context, q db.DBTX, sendIDs []uuid.UUID) ([]SendPlacementRow, error) {
	return many[SendPlacementRow](q.Query(ctx, `
		SELECT p.id, p.send_id, p.item_id, p.frame_id, f.name AS frame_name, p.status, p.removed_at IS NOT NULL AS removed,
			p.reaction, COALESCE((SELECT array_agg(r.preset ORDER BY r.at) FROM placement_replies r WHERE r.placement_id = p.id), '{}') AS replies,
			f.device_id, m.status AS media_status
		FROM placements p JOIN frames f ON f.id = p.frame_id JOIN items i ON i.id = p.item_id JOIN media m ON m.id = i.media_id
		WHERE p.send_id = ANY($1) ORDER BY f.name, p.frame_id, p.received_at, p.id`, sendIDs))
}

// SendFrames lists frames a send already targets.
func (s *Store) SendFrames(ctx context.Context, q db.DBTX, sendID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT DISTINCT frame_id FROM placements WHERE send_id = $1 AND removed_at IS NULL`, sendID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}
