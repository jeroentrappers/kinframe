package store_test

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Telegram rows (docs/spec/telegram.md §16): the bot's state, update dedupe
// and per-chat order, the consumer lock, bindings and their supergroup
// migration, links, promotion once, and the target-deleted trigger.

func TestTelegramBotPrivacyAndOffset(t *testing.T) {
	f := newFeedFx(t)
	t0 := time.Date(2026, 11, 19, 10, 0, 0, 0, time.UTC)
	b, err := f.st.SaveTelegramBot(f.ctx, store.TelegramBot{BotID: 1, Username: "kf_bot", CanReadAll: false, Mode: "polling"}, t0)
	if err != nil || b.PrivacyOffSince != nil {
		t.Fatalf("%+v %v", b, err)
	}
	// Privacy mode turned off: recorded once, kept on later starts.
	b, _ = f.st.SaveTelegramBot(f.ctx, store.TelegramBot{BotID: 1, Username: "kf_bot", CanReadAll: true, Mode: "polling"}, t0.Add(time.Hour))
	if b.PrivacyOffSince == nil || !b.PrivacyOffSince.Equal(t0.Add(time.Hour)) {
		t.Fatalf("privacy_off_since %v", b.PrivacyOffSince)
	}
	b, _ = f.st.SaveTelegramBot(f.ctx, store.TelegramBot{BotID: 1, Username: "kf_bot", CanReadAll: true, Mode: "polling"}, t0.Add(2*time.Hour))
	if !b.PrivacyOffSince.Equal(t0.Add(time.Hour)) {
		t.Fatalf("privacy_off_since moved to %v", b.PrivacyOffSince)
	}
	// Updates: stored once, offset advanced in the same step.
	chat := int64(-100)
	ups := []store.TelegramUpdate{{UpdateID: 10, ChatID: &chat, Payload: json.RawMessage(`{"a":1}`)}, {UpdateID: 11, ChatID: &chat, Payload: json.RawMessage(`{}`)}}
	if n, err := f.st.StoreTelegramUpdates(f.ctx, ups, 12); err != nil || n != 2 {
		t.Fatalf("stored %d %v", n, err)
	}
	if n, _ := f.st.StoreTelegramUpdates(f.ctx, ups[:1], 11); n != 0 {
		t.Fatal("a redelivered update was stored twice")
	}
	if off, _ := f.st.TelegramPollOffset(f.ctx); off != 12 {
		t.Fatalf("offset %d (must not go back)", off)
	}
	// A failed attempt holds the chat's later updates back.
	now := time.Now()
	if chats, _ := f.st.PendingTelegramChats(f.ctx, now, 10); len(chats) != 1 || chats[0] != chat {
		t.Fatalf("pending chats %v", chats)
	}
	u, err := f.st.NextTelegramUpdate(f.ctx, chat, now)
	if err != nil || u.UpdateID != 10 {
		t.Fatalf("next %+v %v", u, err)
	}
	if err := f.st.RetryTelegramUpdate(f.ctx, 10, now.Add(time.Minute), 3); err != nil {
		t.Fatal(err)
	}
	if _, err := f.st.NextTelegramUpdate(f.ctx, chat, now); err != store.ErrNotFound {
		t.Fatalf("update 11 ran before 10: %v", err)
	}
	if chats, _ := f.st.PendingTelegramChats(f.ctx, now, 10); len(chats) != 0 {
		t.Fatalf("chat offered while waiting: %v", chats)
	}
	_ = f.st.FinishTelegramUpdate(f.ctx, 10, now)
	if u, _ := f.st.NextTelegramUpdate(f.ctx, chat, now); u == nil || u.UpdateID != 11 {
		t.Fatalf("next after finish %+v", u)
	}
	// Changing the bot restarts the offset and marks old chats.
	owner := f.user("Anna")
	frame := f.frame(owner, `{}`)
	c := &store.TelegramChat{ID: store.NewID(), TgChatID: -100, BotID: 1, ChatKind: "group", Title: "Peeters", OwnerUserID: &owner,
		FrameID: &frame, Status: "active", BotStatus: "member", BotJoinedAt: now}
	f.tx(func(tx *store.Tx) error { return tx.CreateTelegramChat(f.ctx, c) })
	b, _ = f.st.SaveTelegramBot(f.ctx, store.TelegramBot{BotID: 2, Username: "new_bot", CanReadAll: true, Mode: "polling"}, now)
	if b.PollOffset != 0 {
		t.Fatalf("offset %d after a bot change", b.PollOffset)
	}
	if ids, _ := f.st.MarkChatsBotChanged(f.ctx, 2); len(ids) != 1 || ids[0] != c.ID {
		t.Fatalf("bot_changed %v", ids)
	}
}

func TestTelegramAdvisoryLock(t *testing.T) {
	f := newFeedFx(t)
	a := &store.AdvisoryLock{Pool: f.st.Pool, Key: 4242}
	b := &store.AdvisoryLock{Pool: f.st.Pool, Key: 4242}
	if ok, err := a.TryAcquire(f.ctx); !ok || err != nil {
		t.Fatalf("first: %v %v", ok, err)
	}
	if ok, _ := b.TryAcquire(f.ctx); ok {
		t.Fatal("two holders")
	}
	if err := a.Check(f.ctx); err != nil {
		t.Fatal(err)
	}
	a.Release()
	if ok, _ := b.TryAcquire(f.ctx); !ok {
		t.Fatal("not free after release")
	}
	b.Release()
}

func TestTelegramChatsLinksAndPromo(t *testing.T) {
	f := newFeedFx(t)
	anna, carla := f.user("Anna"), f.user("Carla")
	frame := f.frame(anna, `{}`)
	now := time.Now().UTC()
	c := &store.TelegramChat{ID: store.NewID(), TgChatID: -500, BotID: 1, ChatKind: "group", Title: "Peeters", OwnerUserID: &anna,
		FrameID: &frame, Status: "active", BotStatus: "member", BotJoinedAt: now}
	f.tx(func(tx *store.Tx) error { return tx.CreateTelegramChat(f.ctx, c) })
	// One live binding per Telegram chat.
	dup := *c
	dup.ID = store.NewID()
	if err := f.st.InTx(f.ctx, func(tx *store.Tx) error { return tx.CreateTelegramChat(f.ctx, &dup) }); err == nil {
		t.Fatal("second live binding for one chat")
	}
	if n, owned, _ := f.st.CountTelegramChats(f.ctx, f.st.Q(), &frame, nil, anna); n != 1 || owned != 1 {
		t.Fatalf("counts %d %d", n, owned)
	}
	// Supergroup migration: found by the old id too.
	old := c.TgChatID
	c.PreviousChatID, c.TgChatID = &old, -1009
	if err := f.st.SaveTelegramChat(f.ctx, f.st.Q(), c); err != nil {
		t.Fatal(err)
	}
	for _, id := range []int64{-500, -1009} {
		if v, err := f.st.LiveTelegramChat(f.ctx, f.st.Q(), id); err != nil || v.ID != c.ID || v.FrameName == nil {
			t.Fatalf("lookup %d: %v", id, err)
		}
	}
	// Links: one Telegram account per kinframe account and back.
	if ok, err := f.st.LinkTelegram(f.ctx, f.st.Q(), store.TelegramLink{TgUserID: 42, UserID: carla, DisplayName: "Carla"}); !ok || err != nil {
		t.Fatalf("link %v %v", ok, err)
	}
	if ok, _ := f.st.LinkTelegram(f.ctx, f.st.Q(), store.TelegramLink{TgUserID: 42, UserID: anna, DisplayName: "Carla"}); ok {
		t.Fatal("one Telegram account linked to two accounts")
	}
	if ok, _ := f.st.LinkTelegram(f.ctx, f.st.Q(), store.TelegramLink{TgUserID: 43, UserID: carla, DisplayName: "Carla 2"}); ok {
		t.Fatal("two Telegram accounts linked to one account")
	}
	if l, err := f.st.TelegramLinkByTg(f.ctx, f.st.Q(), 42); err != nil || l.UserID != carla || l.UserName != "Carla" {
		t.Fatalf("link %+v %v", l, err)
	}
	// Promotion: once per contributor, and at most once per chat per gap.
	ben, err := f.st.UpsertTelegramContributor(f.ctx, c.ID, 7, "Ben", nil, now)
	if err != nil {
		t.Fatal(err)
	}
	opa, _ := f.st.UpsertTelegramContributor(f.ctx, c.ID, 8, "Opa", nil, now)
	if ok, _ := f.st.ClaimTelegramPromo(f.ctx, c.ID, ben.ID, now, 10*time.Minute); !ok {
		t.Fatal("first promotion refused")
	}
	if ok, _ := f.st.ClaimTelegramPromo(f.ctx, c.ID, opa.ID, now.Add(time.Minute), 10*time.Minute); ok {
		t.Fatal("second promotion within 10 minutes")
	}
	if ok, _ := f.st.ClaimTelegramPromo(f.ctx, c.ID, ben.ID, now.Add(time.Hour), 10*time.Minute); ok {
		t.Fatal("promotion sent twice to one person")
	}
	if ok, _ := f.st.ClaimTelegramPromo(f.ctx, c.ID, opa.ID, now.Add(time.Hour), 10*time.Minute); !ok {
		t.Fatal("Opa's promotion refused after the gap")
	}
	// Deleting the frame (soft) ends the binding with a goodbye due.
	f.exec(`UPDATE frames SET deleted_at = now() WHERE id = $1`, frame)
	v, err := f.st.GetTelegramChat(f.ctx, c.ID)
	if err != nil || v.Status != "disconnected" || v.EndedReason == nil || *v.EndedReason != "target_deleted" || !v.FarewellDue {
		t.Fatalf("after frame deletion: %+v %v", v, err)
	}
	if _, err := f.st.LiveTelegramChat(f.ctx, f.st.Q(), -1009); err != store.ErrNotFound {
		t.Fatalf("still live: %v", err)
	}
	_ = uuid.Nil
}
