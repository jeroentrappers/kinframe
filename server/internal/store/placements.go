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
	// SharedByUserID is the member who shared this placement from another
	// frame's library; nil when the item's sender placed it.
	SharedByUserID *uuid.UUID `db:"shared_by_user_id"`
	// Origin is how the placement got onto the frame: send, share, feed or import
	// (docs/spec/feeds-data-model.md). "" on create means send, or share with SharedByUserID.
	// (Column alias placement_origin: items have an origin column too.)
	Origin       string     `db:"placement_origin"`
	OriginFeedID *uuid.UUID `db:"origin_feed_id"`
	// HeldDirectly: a send, forward, share or import holds it (not only feeds).
	HeldDirectly bool `db:"held_directly"`
	// FeedSuspended: only feeds hold it and none of their subscriptions is active (paused).
	FeedSuspended bool `db:"feed_suspended"`
	// Backfill: placed as feed history; frames don't treat it as new.
	Backfill bool `db:"backfill"`
}

const placementCols = `p.id, p.frame_id, p.item_id, p.send_id, p.status, p.hidden, p.trashed_at, p.pending_moderation,
	p.fit_override, p.focal_override_x, p.focal_override_y, p.rotation, p.reaction, p.delivered_at, p.seen_at,
	p.received_at, p.removed_at, p.shared_by_user_id, p.origin AS placement_origin, p.origin_feed_id, p.held_directly, p.feed_suspended, p.backfill`

type Reply struct {
	Preset string    `json:"preset"`
	At     time.Time `json:"at"`
}

// PlacementRow is a placement with its item, replies and albums.
type PlacementRow struct {
	Placement
	ItemRow
	Replies      []Reply     `db:"replies"`
	AlbumIDs     []uuid.UUID `db:"album_ids"`
	SharedByName *string     `db:"shared_by_name"`
	// FeedIDs are the feeds whose active subscriptions hold the placement.
	FeedIDs        []uuid.UUID `db:"feed_ids"`
	OriginFeedName *string     `db:"origin_feed_name"`
}

// placementRowCols omits p.item_id: ItemRow supplies the item_id column for both structs.
const placementRowCols = `p.id, p.frame_id, p.send_id, p.status, p.hidden, p.trashed_at, p.pending_moderation,
	p.fit_override, p.focal_override_x, p.focal_override_y, p.rotation, p.reaction, p.delivered_at, p.seen_at,
	p.received_at, p.removed_at, p.shared_by_user_id, p.origin AS placement_origin, p.origin_feed_id, p.held_directly, p.feed_suspended, p.backfill`

const placementRowSelect = `SELECT ` + placementRowCols + `, ` + itemRowCols + `,
	(SELECT sb.display_name FROM users sb WHERE sb.id = p.shared_by_user_id) AS shared_by_name,
	(SELECT fd.name FROM feeds fd WHERE fd.id = p.origin_feed_id AND fd.deleted_at IS NULL) AS origin_feed_name,
	COALESCE((SELECT array_agg(DISTINCT s.feed_id ORDER BY s.feed_id) FROM feed_deliveries d
		JOIN subscriptions s ON s.id = d.subscription_id AND s.status = 'active'
		WHERE d.placement_id = p.id AND d.state = 'placed'), '{}') AS feed_ids,
	COALESCE((SELECT json_agg(json_build_object('preset', r.preset, 'at', r.at) ORDER BY r.at)
		FROM placement_replies r WHERE r.placement_id = p.id), '[]'::json) AS replies,
	COALESCE((SELECT array_agg(ap.album_id ORDER BY ap.album_id) FROM album_placements ap WHERE ap.placement_id = p.id), '{}') AS album_ids
	FROM placements p JOIN items i ON i.id = p.item_id` + itemJoins

// CreatePlacement inserts a placement. A zero ReceivedAt means now; Hidden and
// Rotation are only set by imports that carry them over from another app.
func (tx *Tx) CreatePlacement(ctx context.Context, p Placement) error {
	var received *time.Time
	if !p.ReceivedAt.IsZero() {
		received = &p.ReceivedAt
	}
	origin := p.Origin
	if origin == "" {
		origin = "send"
		if p.SharedByUserID != nil {
			origin = "share"
		}
	}
	held := origin != "feed"
	_, err := tx.Exec(ctx, `
		INSERT INTO placements (id, frame_id, item_id, send_id, status, pending_moderation, shared_by_user_id, hidden, rotation, received_at,
			origin, origin_feed_id, held_directly, backfill)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, COALESCE($10, now()), $11, $12, $13, $14)`,
		p.ID, p.FrameID, p.ItemID, p.SendID, p.Status, p.PendingModeration, p.SharedByUserID, p.Hidden, p.Rotation, received,
		origin, p.OriginFeedID, held, p.Backfill)
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
// Feed deliveries that held them become tombstones ('declined'), so backfills
// never place those items on that frame again (the frame, or someone with
// rights on it, removed them). Returns the removed placements.
func (tx *Tx) RemovePlacements(ctx context.Context, where string, args ...any) ([]Placement, error) {
	removed, err := many[Placement](tx.Query(ctx, `UPDATE placements p SET removed_at = now() WHERE p.removed_at IS NULL AND (`+where+`) RETURNING `+placementCols, args...))
	if err != nil {
		return nil, err
	}
	if len(removed) > 0 {
		ids := make([]uuid.UUID, len(removed))
		for i, p := range removed {
			ids[i] = p.ID
		}
		if _, err := tx.Exec(ctx, `UPDATE feed_deliveries SET state = 'declined', placement_id = NULL
			WHERE placement_id = ANY($1) AND state = 'placed'`, ids); err != nil {
			return nil, err
		}
		// Album sources of the frame never import these again (photo-sources.md §5.2).
		frames, items := make([]uuid.UUID, len(removed)), make([]uuid.UUID, len(removed))
		for i, p := range removed {
			frames[i], items[i] = p.FrameID, p.ItemID
		}
		if err := tx.markRemovedLocally(ctx, `(ps.frame_id, psi.item_id) IN (SELECT * FROM unnest($1::uuid[], $2::uuid[]))`, frames, items); err != nil {
			return nil, err
		}
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
	// SharedByID is set for placements shared from another frame's library.
	SharedByID *uuid.UUID `db:"shared_by_user_id"`
	// Origin of the placement (feed placements get no per-send "delivered" push).
	Origin string `db:"origin"`
	// NewlyDelivered is set when this change delivered the placement (it was pending or ready).
	NewlyDelivered bool `db:"newly_delivered"`
	// SourceSend: the placement belongs to a photo source's or Google import's send (no "delivered" push).
	SourceSend bool `db:"source_send"`
}

// MarkDelivered moves ready placements on the frame to delivered.
func (tx *Tx) MarkDelivered(ctx context.Context, frameID uuid.UUID, ids []uuid.UUID) ([]StatusChange, error) {
	return many[StatusChange](tx.Query(ctx, `
		UPDATE placements p SET status = 'delivered', delivered_at = now() FROM items i
		WHERE i.id = p.item_id AND p.frame_id = $1 AND p.id = ANY($2) AND p.status IN ('pending', 'ready') AND p.removed_at IS NULL
		RETURNING p.id, p.frame_id, p.send_id, i.sender_user_id, p.shared_by_user_id, p.origin, true AS newly_delivered,
			EXISTS (SELECT 1 FROM sends s WHERE s.id = p.send_id AND s.via IS NOT NULL) AS source_send`, frameID, ids))
}

// MarkSeen moves placements to seen (delivered implied) at the given time.
func (tx *Tx) MarkSeen(ctx context.Context, frameID, id uuid.UUID, at time.Time) ([]StatusChange, error) {
	return many[StatusChange](tx.Query(ctx, `
		UPDATE placements p SET status = 'seen', seen_at = $3, delivered_at = COALESCE(p.delivered_at, $3)
		FROM items i, (SELECT status AS old_status FROM placements WHERE id = $2) o
		WHERE i.id = p.item_id AND p.frame_id = $1 AND p.id = $2 AND p.status IN ('pending', 'ready', 'delivered') AND p.removed_at IS NULL
		RETURNING p.id, p.frame_id, p.send_id, i.sender_user_id, p.shared_by_user_id, p.origin, o.old_status IN ('pending', 'ready') AS newly_delivered,
			EXISTS (SELECT 1 FROM sends s WHERE s.id = p.send_id AND s.via IS NOT NULL) AS source_send`, frameID, id, at))
}

// MarkReady moves pending placements of a media on a frame to ready.
func (tx *Tx) MarkReady(ctx context.Context, mediaID, frameID uuid.UUID) ([]StatusChange, error) {
	changed, err := many[StatusChange](tx.Query(ctx, `
		UPDATE placements p SET status = 'ready' FROM items i
		WHERE i.id = p.item_id AND i.media_id = $1 AND p.frame_id = $2 AND p.status = 'pending' AND p.removed_at IS NULL
		RETURNING p.id, p.frame_id, p.send_id, i.sender_user_id, p.shared_by_user_id, p.origin, false AS newly_delivered,
			EXISTS (SELECT 1 FROM sends s WHERE s.id = p.send_id AND s.via IS NOT NULL) AS source_send`, mediaID, frameID))
	if err != nil {
		return nil, err
	}
	return changed, nil
}

// SendDeliveredOn reports whether every live placement of a send on a frame
// has reached the frame (delivered or seen; failed ones don't count against it).
func (s *Store) SendDeliveredOn(ctx context.Context, sendID, frameID uuid.UUID) (bool, error) {
	var done bool
	err := s.Pool.QueryRow(ctx, `SELECT NOT EXISTS (SELECT 1 FROM placements WHERE send_id = $1 AND frame_id = $2
		AND removed_at IS NULL AND shared_by_user_id IS NULL AND origin <> 'feed' AND status IN ('pending', 'ready'))`, sendID, frameID).Scan(&done)
	return done, err
}

// SharedDeliveredOn reports whether every live placement a member shared to a
// frame has reached it (the sharer's "delivered" notification).
func (s *Store) SharedDeliveredOn(ctx context.Context, sharerID, frameID uuid.UUID) (bool, error) {
	var done bool
	err := s.Pool.QueryRow(ctx, `SELECT NOT EXISTS (SELECT 1 FROM placements WHERE shared_by_user_id = $1 AND frame_id = $2
		AND removed_at IS NULL AND status IN ('pending', 'ready'))`, sharerID, frameID).Scan(&done)
	return done, err
}

// LivePlacementsOfMedia lists live placement IDs of a media on a frame (for feed bumps).
func (s *Store) LivePlacementsOfMedia(ctx context.Context, q db.DBTX, mediaID, frameID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT p.id FROM placements p JOIN items i ON i.id = p.item_id
		WHERE (i.media_id = $1 OR i.motion_media_id = $1) AND p.frame_id = $2 AND p.removed_at IS NULL AND p.status <> 'failed'`, mediaID, frameID)
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
	OnlySender *uuid.UUID // restrict to the caller's own placements (sent or shared by them)
	Visibility string     // visible, hidden, trashed, pending_moderation, all
	SenderID   *uuid.UUID
	Kind       *string
	AlbumID    *uuid.UUID
	FeedID     *uuid.UUID // only placements held by this feed (Placement.feed_ids)
	OnThisDay  string     // "MM-DD": captured on this day in an earlier year
	Sort       string     // received_desc, received_asc, taken_desc, taken_asc
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
		sortKey = `COALESCE(i.captured_override, m.captured_at, i.captured_hint, p.received_at)`
	}
	desc := f.Sort == "" || f.Sort == "received_desc" || f.Sort == "taken_desc"
	cmp, dir := ">", "ASC"
	if desc {
		cmp, dir = "<", "DESC"
	}
	// Placements of paused feed subscriptions are not on the frame (feeds.md §5.1).
	where := `p.frame_id = $1 AND p.removed_at IS NULL AND NOT p.feed_suspended`
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
		a := arg(*f.OnlySender)
		where += ` AND (i.sender_user_id = ` + a + ` OR p.shared_by_user_id = ` + a + `)`
	}
	if f.SenderID != nil {
		where += ` AND (i.sender_user_id = ` + arg(*f.SenderID) + ` OR i.sender_frame_id = $` + fmt.Sprint(len(args)) + `)`
	}
	if f.Kind != nil && *f.Kind == "live" {
		// Live photos also count as photos (kind=photo includes them).
		where += ` AND mm.id IS NOT NULL AND mm.status <> 'failed'`
	} else if f.Kind != nil {
		where += ` AND m.kind = ` + arg(*f.Kind)
	}
	if f.AlbumID != nil {
		where += ` AND EXISTS (SELECT 1 FROM album_placements ap WHERE ap.placement_id = p.id AND ap.album_id = ` + arg(*f.AlbumID) + `)`
	}
	if f.FeedID != nil {
		where += ` AND EXISTS (SELECT 1 FROM feed_deliveries d JOIN subscriptions s ON s.id = d.subscription_id AND s.status = 'active'
			WHERE d.placement_id = p.id AND d.state = 'placed' AND s.feed_id = ` + arg(*f.FeedID) + `)`
	}
	if f.OnThisDay != "" {
		taken := `(COALESCE(i.captured_override, m.captured_at, i.captured_hint) AT TIME ZONE 'UTC')`
		where += ` AND to_char(` + taken + `, 'MM-DD') = ` + arg(f.OnThisDay) +
			` AND EXTRACT(year FROM ` + taken + `) < EXTRACT(year FROM now() AT TIME ZONE 'UTC')`
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
		WHERE p.frame_id = $1 AND p.removed_at IS NULL AND NOT p.feed_suspended AND ($2::uuid IS NULL OR i.sender_user_id = $2 OR p.shared_by_user_id = $2)`, frameID, onlySender))
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
	// Via is set on sends a photo source or Google import made (immich, icloud, google_photos).
	Via           *string    `db:"via"`
	PhotoSourceID *uuid.UUID `db:"photo_source_id"`
}

func (s *Store) GetSend(ctx context.Context, q db.DBTX, id uuid.UUID) (*Send, error) {
	return one[Send](q.Query(ctx, `SELECT id, sender_user_id, created_at, via, photo_source_id FROM sends WHERE id = $1`, id))
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
	return many[Send](s.Pool.Query(ctx, `SELECT s.id, s.sender_user_id, s.created_at, s.via, s.photo_source_id FROM sends s
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
	PlacementID  uuid.UUID  `db:"id"`
	SendID       uuid.UUID  `db:"send_id"`
	ItemID       uuid.UUID  `db:"item_id"`
	FrameID      uuid.UUID  `db:"frame_id"`
	FrameName    string     `db:"frame_name"`
	Status       string     `db:"status"`
	Removed      bool       `db:"removed"`
	Reaction     *string    `db:"reaction"`
	Replies      []string   `db:"replies"`
	DeviceID     *uuid.UUID `db:"device_id"`
	MediaStatus  string     `db:"media_status"`
	SharedByName *string    `db:"shared_by_name"`
}

func (s *Store) SendPlacementRows(ctx context.Context, q db.DBTX, sendIDs []uuid.UUID) ([]SendPlacementRow, error) {
	return many[SendPlacementRow](q.Query(ctx, `
		SELECT p.id, p.send_id, p.item_id, p.frame_id, f.name AS frame_name, p.status, p.removed_at IS NOT NULL AS removed,
			p.reaction, COALESCE((SELECT array_agg(r.preset ORDER BY r.at) FROM placement_replies r WHERE r.placement_id = p.id), '{}') AS replies,
			f.device_id, m.status AS media_status,
			(SELECT sb.display_name FROM users sb WHERE sb.id = p.shared_by_user_id) AS shared_by_name
		FROM placements p JOIN frames f ON f.id = p.frame_id JOIN items i ON i.id = p.item_id JOIN media m ON m.id = i.media_id
		WHERE p.send_id = ANY($1) AND p.origin <> 'feed' ORDER BY f.name, p.frame_id, p.received_at, p.id`, sendIDs))
}

// SendFrames lists frames a send already targets.
func (s *Store) SendFrames(ctx context.Context, q db.DBTX, sendID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT DISTINCT frame_id FROM placements WHERE send_id = $1 AND removed_at IS NULL`, sendID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}
