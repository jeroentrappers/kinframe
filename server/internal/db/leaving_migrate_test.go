package db_test

import (
	"context"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/testdb"
)

// The leaving and federation migrations (00036, 00037) on top of data from before them: existing placements and
// posts are untouched, the federation triggers log post, item, birthday and comment changes only for families with
// a live remote subscriber (never circle posts), and the migrations go down and up again.
func TestMigrateLeavingAndFederation(t *testing.T) {
	ctx := context.Background()
	pool, _ := testdb.New(t)
	p, err := db.NewProvider(pool)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := p.DownTo(ctx, 35); err != nil {
		t.Fatalf("down to 35: %v", err)
	}
	exec := func(sql string, args ...any) {
		t.Helper()
		if _, err := pool.Exec(ctx, sql, args...); err != nil {
			t.Fatalf("%s: %v", sql, err)
		}
	}
	count := func(sql string, args ...any) int {
		t.Helper()
		var n int
		if err := pool.QueryRow(ctx, sql, args...).Scan(&n); err != nil {
			t.Fatalf("%s: %v", sql, err)
		}
		return n
	}
	const (
		anna   = "01900000-0000-7000-8000-000000000001"
		tom    = "01900000-0000-7000-8000-000000000002"
		frame  = "01900000-0000-7000-8000-000000000010"
		feed   = "01900000-0000-7000-8000-000000000020"
		feed2  = "01900000-0000-7000-8000-000000000021"
		media  = "01900000-0000-7000-8000-000000000030"
		item   = "01900000-0000-7000-8000-000000000031"
		send   = "01900000-0000-7000-8000-000000000040"
		send2  = "01900000-0000-7000-8000-000000000041"
		send3  = "01900000-0000-7000-8000-000000000042"
		plc    = "01900000-0000-7000-8000-000000000050"
		post   = "01900000-0000-7000-8000-000000000060"
		post2  = "01900000-0000-7000-8000-000000000061"
		post3  = "01900000-0000-7000-8000-000000000062"
		circle = "01900000-0000-7000-8000-000000000070"
		server = "01900000-0000-7000-8000-000000000080"
		sub    = "01900000-0000-7000-8000-000000000090"
	)
	exec(`INSERT INTO users (id, sub, display_name) VALUES ($1, 'a', 'Anna'), ($2, 't', 'Tom')`, anna, tom)
	exec(`INSERT INTO frames (id, name, default_permissions, settings) VALUES ($1, 'Oma', '{}', '{}')`, frame)
	exec(`INSERT INTO feeds (id, name) VALUES ($1, 'Janssens'), ($2, 'Peeters')`, feed, feed2)
	exec(`INSERT INTO feed_members (feed_id, user_id, role) VALUES ($1, $2, 'owner'), ($1, $3, 'poster')`, feed, anna, tom)
	exec(`INSERT INTO media (id, kind, status) VALUES ($1, 'photo', 'ready')`, media)
	exec(`INSERT INTO items (id, media_id, sender_user_id) VALUES ($1, $2, $3)`, item, media, tom)
	exec(`INSERT INTO sends (id, sender_user_id) VALUES ($1, $2), ($3, $2), ($4, $2)`, send, tom, send2, send3)
	exec(`INSERT INTO placements (id, frame_id, item_id, send_id) VALUES ($1, $2, $3, $4)`, plc, frame, item, send)
	exec(`INSERT INTO feed_posts (id, feed_id, send_id, poster_user_id) VALUES ($1, $2, $3, $4)`, post, feed, send, tom)
	exec(`INSERT INTO feed_post_items (post_id, item_id, position) VALUES ($1, $2, 0)`, post, item)

	if _, err := p.Up(ctx); err != nil {
		t.Fatalf("up: %v", err)
	}
	if n := count(`SELECT count(*) FROM placements WHERE id = $1 AND taken_away_at IS NULL AND removed_at IS NULL
		AND origin_remote_follow_id IS NULL`, plc); n != 1 {
		t.Errorf("placement changed by the migration")
	}
	if n := count(`SELECT count(*) FROM feeds WHERE NOT allow_remote_frames`); n != 2 {
		t.Errorf("allow_remote_frames should default off")
	}

	// Without remote subscribers nothing is logged.
	exec(`UPDATE feed_posts SET removed_at = now() WHERE id = $1`, post)
	if n := count(`SELECT count(*) FROM federation_changes`); n != 0 {
		t.Fatalf("logged %d changes for a family without remote subscribers", n)
	}

	exec(`INSERT INTO remote_servers (id, base_url, host) VALUES ($1, 'https://kinframe.appmire.be', 'kinframe.appmire.be')`, server)
	exec(`INSERT INTO remote_subscribers (id, feed_id, server_id, remote_frame_id, frame_name, status, history, show_comments)
		VALUES ($1, $2, $3, $4, 'Oma''s frame', 'active', 'none', true)`, sub, feed, server, frame)

	// A keep-request restore and a taking show up as post.upsert and post.remove.
	exec(`UPDATE feed_posts SET removed_at = NULL WHERE id = $1`, post)
	exec(`UPDATE feed_post_items SET removed_at = now() WHERE post_id = $1`, post)
	exec(`INSERT INTO feed_posts (id, feed_id, send_id, poster_user_id) VALUES ($1, $2, $3, $4)`, post2, feed, send2, tom)
	exec(`INSERT INTO circles (id, feed_id, name, created_by) VALUES ($1, $2, 'Cousins', $3)`, circle, feed, anna)
	exec(`INSERT INTO feed_posts (id, feed_id, send_id, poster_user_id, circle_id) VALUES ($1, $2, $3, $4, $5)`, post3, feed, send3, tom, circle)
	exec(`INSERT INTO feed_birthdays (id, feed_id, status, display_name, name_key, month, day)
		VALUES ('01900000-0000-7000-8000-0000000000a0', $1, 'approved', 'Oma', 'oma', 3, 14)`, feed)
	exec(`INSERT INTO feed_posts (id, feed_id, send_id, poster_user_id) VALUES ('01900000-0000-7000-8000-000000000063', $1, $2, $3)`, feed2, send, tom)
	rows, err := pool.Query(ctx, `SELECT kind FROM federation_changes ORDER BY seq`)
	if err != nil {
		t.Fatal(err)
	}
	var kinds []string
	for rows.Next() {
		var k string
		if err := rows.Scan(&k); err != nil {
			t.Fatal(err)
		}
		kinds = append(kinds, k)
	}
	rows.Close()
	want := []string{"post.upsert", "items.remove", "post.upsert", "birthdays"}
	if len(kinds) != len(want) {
		t.Fatalf("changes %v, want %v", kinds, want)
	}
	for i := range want {
		if kinds[i] != want[i] {
			t.Fatalf("changes %v, want %v", kinds, want)
		}
	}

	// Leaving tables accept a taking with its placement state.
	exec(`INSERT INTO takings (id, user_id, user_name, scope, feed_id, item_count, until)
		VALUES ('01900000-0000-7000-8000-0000000000b0', $1, 'Tom', 'feed', $2, 1, now() + interval '30 days')`, tom, feed)
	exec(`UPDATE placements SET removed_at = now(), taken_away_at = now(), taken_away_until = now() + interval '30 days',
		taken_away_by = $1, taking_id = '01900000-0000-7000-8000-0000000000b0' WHERE id = $2`, tom, plc)
	exec(`INSERT INTO keep_requests (id, taking_id, frame_id, asked_by_name, expires_at)
		VALUES ('01900000-0000-7000-8000-0000000000c0', '01900000-0000-7000-8000-0000000000b0', $1, 'Anna', now() + interval '30 days')`, frame)
	if _, err := pool.Exec(ctx, `INSERT INTO keep_requests (id, taking_id, frame_id, asked_by_name, expires_at)
		VALUES ('01900000-0000-7000-8000-0000000000c1', '01900000-0000-7000-8000-0000000000b0', $1, 'Anna', now())`, frame); err == nil {
		t.Errorf("a second keep request for the same frame and taking was accepted")
	}

	if _, err := p.DownTo(ctx, 35); err != nil {
		t.Fatalf("down again: %v", err)
	}
	if _, err := p.Up(ctx); err != nil {
		t.Fatalf("up again: %v", err)
	}
}
