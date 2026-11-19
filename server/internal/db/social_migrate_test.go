package db_test

import (
	"context"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/testdb"
)

// The family social migrations (00022–00024) on top of data from before them: frame posts, family
// conversations and engagement rows are backfilled, the triggers keep family chats in step with feed members,
// and the migrations go down and up again.
func TestMigrateFamilySocialBackfill(t *testing.T) {
	ctx := context.Background()
	pool, _ := testdb.New(t)
	p, err := db.NewProvider(pool)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := p.DownTo(ctx, 21); err != nil {
		t.Fatalf("down to 21: %v", err)
	}
	exec := func(sql string, args ...any) {
		t.Helper()
		if _, err := pool.Exec(ctx, sql, args...); err != nil {
			t.Fatalf("%s: %v", sql, err)
		}
	}
	const (
		anna  = "01900000-0000-7000-8000-000000000001"
		ben   = "01900000-0000-7000-8000-000000000002"
		frame = "01900000-0000-7000-8000-000000000010"
		feed  = "01900000-0000-7000-8000-000000000020"
		media = "01900000-0000-7000-8000-000000000030"
		item  = "01900000-0000-7000-8000-000000000031"
		send  = "01900000-0000-7000-8000-000000000040"
		plc   = "01900000-0000-7000-8000-000000000050"
		post  = "01900000-0000-7000-8000-000000000060"
	)
	exec(`INSERT INTO users (id, sub, display_name) VALUES ($1, 'a', 'Anna'), ($2, 'b', 'Ben')`, anna, ben)
	exec(`INSERT INTO frames (id, name, default_permissions, settings) VALUES ($1, 'Oma', '{}', '{}')`, frame)
	exec(`INSERT INTO feeds (id, name, members_see_reactions) VALUES ($1, 'Peeters', true)`, feed)
	exec(`INSERT INTO feed_members (feed_id, user_id, role) VALUES ($1, $2, 'owner'), ($1, $3, 'poster')`, feed, anna, ben)
	exec(`INSERT INTO media (id, kind, status) VALUES ($1, 'photo', 'ready')`, media)
	exec(`INSERT INTO items (id, media_id, sender_user_id) VALUES ($1, $2, $3)`, item, media, anna)
	exec(`INSERT INTO sends (id, sender_user_id) VALUES ($1, $2)`, send, anna)
	exec(`INSERT INTO placements (id, frame_id, item_id, send_id) VALUES ($1, $2, $3, $4)`, plc, frame, item, send)
	exec(`INSERT INTO feed_posts (id, feed_id, send_id, poster_user_id) VALUES ($1, $2, $3, $4)`, post, feed, send, anna)

	if _, err := p.Up(ctx); err != nil {
		t.Fatalf("up: %v", err)
	}
	count := func(sql string, args ...any) int {
		t.Helper()
		var n int
		if err := pool.QueryRow(ctx, sql, args...).Scan(&n); err != nil {
			t.Fatalf("%s: %v", sql, err)
		}
		return n
	}
	if n := count(`SELECT count(*) FROM frame_posts WHERE send_id = $1 AND frame_id = $2`, send, frame); n != 1 {
		t.Errorf("frame posts: %d", n)
	}
	if n := count(`SELECT count(*) FROM conversation_members cm JOIN conversations c ON c.id = cm.conversation_id
		WHERE c.kind = 'family' AND c.feed_id = $1`, feed); n != 2 {
		t.Errorf("family chat members: %d", n)
	}
	if n := count(`SELECT count(*) FROM feeds WHERE id = $1 AND members_see_receipts`, feed); n != 1 {
		t.Errorf("members_see_receipts not carried over")
	}
	if n := count(`SELECT count(*) FROM feed_engagement WHERE feed_id = $1 AND streak_goal = 3`, feed); n != 1 {
		t.Errorf("feed_engagement not backfilled")
	}
	if n := count(`SELECT count(*) FROM frame_freshness WHERE frame_id = $1 AND last_personal_user_id = $2`, frame, anna); n != 1 {
		t.Errorf("frame freshness not backfilled")
	}

	// Triggers: a new feed gets its chat and engagement row; members and circle members follow.
	const (
		feed2  = "01900000-0000-7000-8000-000000000021"
		circle = "01900000-0000-7000-8000-000000000070"
	)
	exec(`INSERT INTO feeds (id, name) VALUES ($1, 'Janssens')`, feed2)
	exec(`INSERT INTO feed_members (feed_id, user_id, role) VALUES ($1, $2, 'owner'), ($1, $3, 'member')`, feed2, anna, ben)
	exec(`INSERT INTO circles (id, feed_id, name, created_by) VALUES ($1, $2, 'Cousins', $3)`, circle, feed2, anna)
	exec(`INSERT INTO circle_members (circle_id, feed_id, user_id, role) VALUES ($1, $2, $3, 'admin'), ($1, $2, $4, 'member')`,
		circle, feed2, anna, ben)
	if n := count(`SELECT count(*) FROM conversation_members cm JOIN conversations c ON c.id = cm.conversation_id
		WHERE c.feed_id = $1`, feed2); n != 4 {
		t.Errorf("family + circle chat members: %d, want 4", n)
	}
	if n := count(`SELECT count(*) FROM feed_engagement WHERE feed_id = $1`, feed2); n != 1 {
		t.Errorf("feed_engagement not created")
	}
	exec(`DELETE FROM feed_members WHERE feed_id = $1 AND user_id = $2`, feed2, ben)
	if n := count(`SELECT count(*) FROM conversation_members WHERE user_id = $1 AND conversation_id IN
		(SELECT id FROM conversations WHERE feed_id = $2)`, ben, feed2); n != 0 {
		t.Errorf("leaving the family left %d chat memberships", n)
	}
	if n := count(`SELECT count(*) FROM circle_members WHERE user_id = $1`, ben); n != 0 {
		t.Errorf("leaving the family kept the circle membership")
	}
	exec(`UPDATE feeds SET deleted_at = now() WHERE id = $1`, feed2)
	if n := count(`SELECT count(*) FROM conversations WHERE feed_id = $1 AND archived_at IS NULL`, feed2); n != 0 {
		t.Errorf("deleting the feed left %d chats unarchived", n)
	}

	if _, err := p.DownTo(ctx, 21); err != nil {
		t.Fatalf("down again: %v", err)
	}
	if _, err := p.Up(ctx); err != nil {
		t.Fatalf("up again: %v", err)
	}
}
