package store_test

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/testdb"
)

type chatFixture struct {
	t    *testing.T
	ctx  context.Context
	pool *pgxpool.Pool
	st   *store.Store
}

func newChat(t *testing.T) *chatFixture {
	pool, _ := testdb.New(t)
	return &chatFixture{t: t, ctx: context.Background(), pool: pool, st: store.New(pool)}
}

func (f *chatFixture) exec(sql string, args ...any) {
	f.t.Helper()
	if _, err := f.pool.Exec(f.ctx, sql, args...); err != nil {
		f.t.Fatalf("%s: %v", sql, err)
	}
}

func (f *chatFixture) count(sql string, args ...any) int {
	f.t.Helper()
	var n int
	if err := f.pool.QueryRow(f.ctx, sql, args...).Scan(&n); err != nil {
		f.t.Fatalf("%s: %v", sql, err)
	}
	return n
}

func (f *chatFixture) user(name string) uuid.UUID {
	f.t.Helper()
	id := store.NewID()
	f.exec(`INSERT INTO users (id, sub, display_name) VALUES ($1, $2, $3)`, id, "sub-"+id.String(), name)
	return id
}

func (f *chatFixture) feed(name string, owner uuid.UUID, members ...uuid.UUID) uuid.UUID {
	f.t.Helper()
	id := store.NewID()
	f.exec(`INSERT INTO feeds (id, name) VALUES ($1, $2)`, id, name)
	f.exec(`INSERT INTO feed_members (feed_id, user_id, role) VALUES ($1, $2, 'owner')`, id, owner)
	for _, m := range members {
		f.exec(`INSERT INTO feed_members (feed_id, user_id, role) VALUES ($1, $2, 'poster')`, id, m)
	}
	return id
}

func (f *chatFixture) members(conv uuid.UUID) map[uuid.UUID]string {
	f.t.Helper()
	ms, err := f.st.ConversationMembers(f.ctx, f.st.Q(), []uuid.UUID{conv})
	if err != nil {
		f.t.Fatal(err)
	}
	out := map[uuid.UUID]string{}
	for _, m := range ms[conv] {
		out[m.UserID] = m.Role
	}
	return out
}

func (f *chatFixture) message(conv uuid.UUID, author *uuid.UUID, text string) uuid.UUID {
	f.t.Helper()
	id := store.NewID()
	err := f.st.InTx(f.ctx, func(tx *store.Tx) error {
		return tx.CreateMessage(f.ctx, store.NewMessage{ID: id, ConversationID: conv, Kind: "person", AuthorUserID: author, Body: &text})
	})
	if err != nil {
		f.t.Fatal(err)
	}
	return id
}

// Family and circle chats follow the feed's and the circle's members through the 00023 triggers; deleting
// archives them.
func TestChatTriggersKeepFamilyAndCircleMembers(t *testing.T) {
	f := newChat(t)
	anna, ben, carl := f.user("Anna"), f.user("Ben"), f.user("Carl")
	feed := f.feed("Peeters", anna, ben, carl)
	var fam uuid.UUID
	if err := f.pool.QueryRow(f.ctx, `SELECT id FROM conversations WHERE kind = 'family' AND feed_id = $1`, feed).Scan(&fam); err != nil {
		t.Fatalf("no family chat: %v", err)
	}
	if m := f.members(fam); len(m) != 3 || m[anna] != "member" {
		t.Fatalf("family members: %v", m)
	}
	circle := store.NewID()
	f.exec(`INSERT INTO circles (id, feed_id, name, created_by) VALUES ($1, $2, 'Cousins', $3)`, circle, feed, anna)
	var cc uuid.UUID
	if err := f.pool.QueryRow(f.ctx, `SELECT id FROM conversations WHERE kind = 'circle' AND circle_id = $1`, circle).Scan(&cc); err != nil {
		t.Fatalf("no circle chat: %v", err)
	}
	f.exec(`INSERT INTO circle_members (circle_id, feed_id, user_id, role) VALUES ($1, $2, $3, 'admin'), ($1, $2, $4, 'member')`, circle, feed, anna, ben)
	if m := f.members(cc); len(m) != 2 || m[carl] != "" {
		t.Fatalf("circle members: %v", m)
	}
	cv, err := f.st.ConversationOf(f.ctx, f.st.Q(), ben, cc)
	if err != nil || cv.CircleName == nil || *cv.CircleName != "Cousins" || cv.FeedName == nil || *cv.FeedName != "Peeters" {
		t.Fatalf("circle chat view: %+v %v", cv, err)
	}
	// Leaving the family leaves the circle (cascade) and both chats.
	f.exec(`DELETE FROM feed_members WHERE feed_id = $1 AND user_id = $2`, feed, ben)
	if m := f.members(cc); len(m) != 1 {
		t.Fatalf("circle after Ben left the family: %v", m)
	}
	if m := f.members(fam); len(m) != 2 {
		t.Fatalf("family after Ben left: %v", m)
	}
	if _, err := f.st.ConversationOf(f.ctx, f.st.Q(), ben, fam); err != store.ErrNotFound {
		t.Fatalf("Ben still sees the family chat: %v", err)
	}
	// Deleting the circle, then the feed, archives their chats.
	f.exec(`UPDATE circles SET deleted_at = now() WHERE id = $1`, circle)
	f.exec(`UPDATE feeds SET deleted_at = now() WHERE id = $1`, feed)
	if n := f.count(`SELECT count(*) FROM conversations WHERE id = ANY($1) AND archived_at IS NOT NULL`, []uuid.UUID{fam, cc}); n != 2 {
		t.Fatalf("archived chats: %d", n)
	}
	views, err := f.st.ConversationsOf(f.ctx, anna, false)
	if err != nil || len(views) != 0 {
		t.Fatalf("archived chats listed: %d %v", len(views), err)
	}
	if views, _ := f.st.ConversationsOf(f.ctx, anna, true); len(views) != 2 {
		t.Fatalf("archived list: %d", len(views))
	}
	// Contacts only through live feeds.
	if ok, _ := f.st.AreContacts(f.ctx, f.st.Q(), anna, carl); ok {
		t.Fatal("contacts through a deleted feed")
	}
}

func TestChatGroupMembersAndAdmins(t *testing.T) {
	f := newChat(t)
	anna, ben, carl := f.user("Anna"), f.user("Ben"), f.user("Carl")
	g := store.NewID()
	name := "Trio"
	err := f.st.InTx(f.ctx, func(tx *store.Tx) error {
		if err := tx.CreateConversation(f.ctx, g, "group", nil, &name, anna); err != nil {
			return err
		}
		for i, u := range []uuid.UUID{anna, ben, carl} {
			role := "member"
			if i == 0 {
				role = "admin"
			}
			if _, err := tx.AddConversationMember(f.ctx, g, u, role, &anna, true); err != nil {
				return err
			}
			time.Sleep(2 * time.Millisecond) // distinct joined_at
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	// Adding twice is a no-op.
	_ = f.st.InTx(f.ctx, func(tx *store.Tx) error {
		added, err := tx.AddConversationMember(f.ctx, g, ben, "member", &anna, true)
		if added || err != nil {
			t.Errorf("second add: %v %v", added, err)
		}
		return nil
	})
	// The admin leaves: the longest-standing member (Ben) becomes admin.
	var promoted *uuid.UUID
	_ = f.st.InTx(f.ctx, func(tx *store.Tx) error {
		_, promoted, err = tx.RemoveConversationMember(f.ctx, g, anna)
		return err
	})
	if err != nil || promoted == nil || *promoted != ben {
		t.Fatalf("promoted %v, %v", promoted, err)
	}
	if m := f.members(g); m[ben] != "admin" || m[carl] != "member" {
		t.Fatalf("members: %v", m)
	}
	// A member leaving while an admin is left promotes nobody.
	_ = f.st.InTx(f.ctx, func(tx *store.Tx) error {
		_, promoted, err = tx.RemoveConversationMember(f.ctx, g, carl)
		return err
	})
	if promoted != nil {
		t.Fatalf("promoted %v", promoted)
	}
}

func TestChatUnreadPagingAndBadge(t *testing.T) {
	f := newChat(t)
	anna, ben, carl := f.user("Anna"), f.user("Ben"), f.user("Carl")
	dm, key := store.NewID(), "dm"
	err := f.st.InTx(f.ctx, func(tx *store.Tx) error {
		if err := tx.CreateConversation(f.ctx, dm, "direct", &key, nil, anna); err != nil {
			return err
		}
		for _, u := range []uuid.UUID{anna, ben} {
			if _, err := tx.AddConversationMember(f.ctx, dm, u, "member", &anna, false); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	var ids []uuid.UUID
	for i := 0; i < 5; i++ {
		ids = append(ids, f.message(dm, &anna, "m"))
	}
	f.message(dm, &ben, "own")
	v, err := f.st.ConversationOf(f.ctx, f.st.Q(), ben, dm)
	if err != nil || v.UnreadCount != 5 {
		t.Fatalf("unread %d %v", v.UnreadCount, err)
	}
	if c, _, _ := f.st.BadgeCounts(f.ctx, ben); c != 1 {
		t.Fatalf("badge conversations %d", c)
	}
	if moved, _ := f.st.MarkRead(f.ctx, dm, ben, ids[2]); !moved {
		t.Fatal("read did not move")
	}
	if moved, _ := f.st.MarkRead(f.ctx, dm, ben, ids[0]); moved {
		t.Fatal("read moved backwards")
	}
	if v, _ := f.st.ConversationOf(f.ctx, f.st.Q(), ben, dm); v.UnreadCount != 2 {
		t.Fatalf("unread after reading: %d", v.UnreadCount)
	}
	// Blocking the author hides their messages from counts and pages.
	if err := f.st.Block(f.ctx, ben, anna); err != nil {
		t.Fatal(err)
	}
	if v, _ := f.st.ConversationOf(f.ctx, f.st.Q(), ben, dm); v.UnreadCount != 0 {
		t.Fatalf("unread from a blocked person: %d", v.UnreadCount)
	}
	page, before, after, err := f.st.MessagePage(f.ctx, store.MessagePageQuery{ConversationID: dm, Viewer: ben, Limit: 10})
	if err != nil || len(page) != 1 || before || after {
		t.Fatalf("blocked page: %d %v %v %v", len(page), before, after, err)
	}
	_ = f.st.Unblock(f.ctx, ben, anna)
	page, before, after, _ = f.st.MessagePage(f.ctx, store.MessagePageQuery{ConversationID: dm, Viewer: ben, Before: &ids[3], Limit: 2})
	if len(page) != 2 || page[0].ID != ids[1] || page[1].ID != ids[2] || !before || !after {
		t.Fatalf("before page: %v %v %v", page, before, after)
	}
	// Muted: off the badge; always is 'infinity'.
	if err := f.st.SetConversationPrefs(f.ctx, dm, ben, store.MemberPrefs{SetMute: true, MuteAlways: true}); err != nil {
		t.Fatal(err)
	}
	if v, _ := f.st.ConversationOf(f.ctx, f.st.Q(), ben, dm); !v.MutedAlways || !v.Muted(time.Now()) {
		t.Fatalf("muted always: %+v", v)
	}
	if c, _, _ := f.st.BadgeCounts(f.ctx, ben); c != 0 {
		t.Fatalf("muted conversation on the badge: %d", c)
	}

	// Threads count when a comment mentions the user or answers their comment, in a feed they're in.
	feed := f.feed("Peeters", anna, ben, carl)
	post := store.NewID()
	f.exec(`INSERT INTO feed_posts (id, feed_id, poster_user_id) VALUES ($1, $2, $3)`, post, feed, anna)
	f.exec(`INSERT INTO threads (post_id, feed_post_id, feed_id) VALUES ($1, $1, $2)`, post, feed)
	top, reply, mention := store.NewID(), store.NewID(), store.NewID()
	f.exec(`INSERT INTO comments (id, post_id, author_user_id, body) VALUES ($1, $2, $3, 'nice')`, top, post, ben)
	f.exec(`INSERT INTO comments (id, post_id, parent_id, author_user_id, body) VALUES ($1, $2, $3, $4, 'thanks')`, reply, post, top, carl)
	if _, th, _ := f.st.BadgeCounts(f.ctx, ben); th != 1 {
		t.Fatalf("reply to Ben: threads %d", th)
	}
	if _, th, _ := f.st.BadgeCounts(f.ctx, anna); th != 0 {
		t.Fatalf("Anna has no mention: threads %d", th)
	}
	f.exec(`INSERT INTO comments (id, post_id, author_user_id, body, mentions) VALUES ($1, $2, $3, '@Anna', $4)`, mention, post, carl,
		json.RawMessage(`[]`))
	f.exec(`INSERT INTO comment_mentions (comment_id, user_id) VALUES ($1, $2)`, mention, anna)
	if _, th, _ := f.st.BadgeCounts(f.ctx, anna); th != 1 {
		t.Fatalf("mention of Anna: threads %d", th)
	}
	f.exec(`INSERT INTO thread_reads (user_id, post_id, read_up_to) VALUES ($1, $2, $3)`, anna, post, mention)
	if _, th, _ := f.st.BadgeCounts(f.ctx, anna); th != 0 {
		t.Fatalf("read thread: %d", th)
	}
	f.exec(`UPDATE thread_reads SET read_up_to = NULL, muted = true WHERE user_id = $1`, anna)
	if _, th, _ := f.st.BadgeCounts(f.ctx, anna); th != 0 {
		t.Fatalf("muted thread: %d", th)
	}
}
