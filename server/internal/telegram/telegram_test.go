package telegram_test

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/telegram"
	"github.com/jeroentrappers/kinframe/server/internal/telegram/telegramtest"
)

func TestWebhookSecret(t *testing.T) {
	key := []byte(strings.Repeat("k", 32))
	a, err := telegram.WebhookSecret(key)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := telegram.WebhookSecret(key)
	if a != b || len(a) != 43 {
		t.Fatalf("secret %q (%d chars) not stable", a, len(a))
	}
	for _, r := range a {
		if !strings.ContainsRune("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-", r) {
			t.Fatalf("secret has %q, not allowed by Telegram", r)
		}
	}
	other, _ := telegram.WebhookSecret([]byte(strings.Repeat("x", 32)))
	if other == a {
		t.Fatal("different keys give the same secret")
	}
	if _, err := telegram.WebhookSecret([]byte("short")); err == nil {
		t.Fatal("short key accepted")
	}
	if !telegram.CheckSecret(a, a) || telegram.CheckSecret(a[:42], a) || telegram.CheckSecret("", a) || telegram.CheckSecret("", "") {
		t.Fatal("CheckSecret")
	}
}

func TestCodes(t *testing.T) {
	seen := map[string]bool{}
	for range 200 {
		c := telegram.NewCode(telegram.PurposeConnectGroup)
		if !telegram.ValidCode(c) || len(c) != 17 || c[0] != 'c' {
			t.Fatalf("bad code %q", c)
		}
		if seen[c] {
			t.Fatalf("repeated code %q", c)
		}
		seen[c] = true
	}
	for _, bad := range []string{"", "x1234567890123456", "c123", "c12345678901234567", "c123456789012345_"} {
		if telegram.ValidCode(bad) {
			t.Errorf("ValidCode(%q)", bad)
		}
	}
	if telegram.HashCode("a") == telegram.HashCode("b") || len(telegram.HashCode("a")) != 64 {
		t.Fatal("HashCode")
	}
}

func TestCaptionAndLanguage(t *testing.T) {
	if got := telegram.Caption("  hi  "); got != "hi" {
		t.Fatalf("%q", got)
	}
	long := strings.Repeat("word ", 100)
	got := telegram.Caption(long)
	if n := len([]rune(got)); n > 280 || !strings.HasSuffix(got, "…") || strings.HasSuffix(got, " …") {
		t.Fatalf("caption %d runes %q", n, got[len(got)-20:])
	}
	for in, want := range map[string]string{"pt-br": "pt", "nn": "nb", "no": "nb", "iw": "he", "in": "id", "nl": "nl", "": "", "zh-hans": "zh-CN"} {
		if got := telegram.Language(in); got != want {
			t.Errorf("Language(%q) = %q, want %q", in, got, want)
		}
	}
	for r, e := range map[string]string{"love": "❤", "laugh": "😁", "wow": "🤩", "sad": "😢", "": "👍"} {
		if telegram.ReactionEmoji(r) != e {
			t.Errorf("ReactionEmoji(%q)", r)
		}
	}
}

func reduce(t *testing.T, u map[string]any) *telegram.Reduced {
	t.Helper()
	raw, _ := json.Marshal(u)
	r, id, err := telegram.Reduce(raw, 777000111, "kinframe_test_bot")
	if err != nil {
		t.Fatal(err)
	}
	if id == 0 {
		t.Fatal("no update id")
	}
	return r
}

func TestReduceFilters(t *testing.T) {
	g := telegramtest.Group(-100, "Peeters family")
	ben := telegramtest.User(42, "Ben", "Peeters", "nl")
	// Text, stickers, voice, other bots' commands: dropped.
	for name, u := range map[string]map[string]any{
		"text":      telegramtest.Text(g, ben, 1, "hello everyone"),
		"other bot": telegramtest.Text(g, ben, 2, "/start@other_bot abc"),
		"unknown":   telegramtest.Text(g, ben, 3, "/weather"),
		"sticker":   telegramtest.Message(map[string]any{"message_id": 4, "date": 1, "chat": g, "from": ben, "sticker": map[string]any{"file_id": "s"}}),
		"pdf":       telegramtest.Document(g, ben, 5, "f1", "application/pdf", 100, nil),
		"joined":    telegramtest.Message(map[string]any{"message_id": 6, "date": 1, "chat": g, "from": ben, "new_chat_members": []any{ben}}),
	} {
		if r := reduce(t, u); r != nil {
			t.Errorf("%s kept: %+v", name, r)
		}
	}
	// Photo: the largest size, caption, album, spoiler; never the text.
	r := reduce(t, telegramtest.Photo(g, ben, 7, "big", map[string]any{"caption": "Kids", "media_group_id": "A1", "has_media_spoiler": true, "text": "secret"}))
	if r == nil || r.Media == nil || r.Media.FileID != "big" || r.Media.Kind != telegram.MediaPhoto || r.Caption != "Kids" || r.MediaGroupID != "A1" || !r.Spoiler {
		t.Fatalf("photo %+v", r)
	}
	raw, _ := json.Marshal(r)
	if strings.Contains(string(raw), "secret") {
		t.Fatal("text stored")
	}
	if r.From == nil || r.From.Name != "Ben Peeters" || r.From.Lang != "nl" {
		t.Fatalf("from %+v", r.From)
	}
	// Image and video documents.
	if r := reduce(t, telegramtest.Document(g, ben, 8, "orig", "image/heic", 5<<20, nil)); r == nil || r.Media.Kind != telegram.MediaImage || r.Media.Size != 5<<20 {
		t.Fatalf("image document %+v", r)
	}
	if r := reduce(t, telegramtest.Document(g, ben, 9, "clip", "video/mp4", 1, nil)); r == nil || r.Media.Kind != telegram.MediaVideo {
		t.Fatalf("video document %+v", r)
	}
	// Commands: bare and addressed, with the code as argument only.
	r = reduce(t, telegramtest.Text(g, ben, 10, "/start@kinframe_test_bot cAbcdefghijklmnop and more"))
	if r == nil || r.Command != "start" || r.CommandArg != "cAbcdefghijklmnop" {
		t.Fatalf("command %+v", r)
	}
	if r := reduce(t, telegramtest.Text(g, ben, 11, "/STATUS")); r == nil || r.Command != "status" {
		t.Fatalf("status %+v", r)
	}
	// Service messages.
	if r := reduce(t, telegramtest.Message(map[string]any{"message_id": 12, "date": 1, "chat": g, "from": ben, "migrate_to_chat_id": -1009})); r == nil || r.MigrateTo != -1009 {
		t.Fatalf("migration %+v", r)
	}
	if r := reduce(t, telegramtest.Message(map[string]any{"message_id": 13, "date": 1, "chat": g, "from": ben, "new_chat_title": "Fam"})); r == nil || r.NewTitle != "Fam" {
		t.Fatalf("title %+v", r)
	}
	if r := reduce(t, telegramtest.Message(map[string]any{"message_id": 14, "date": 1, "chat": g, "from": ben, "message_thread_id": 5, "is_topic_message": true,
		"forum_topic_created": map[string]any{"name": "Holidays"}})); r == nil || r.TopicName != "Holidays" || r.ThreadID != 5 {
		t.Fatalf("topic %+v", r)
	}
	bot := map[string]any{"id": 777000111, "is_bot": true, "first_name": "K"}
	if r := reduce(t, telegramtest.Message(map[string]any{"message_id": 15, "date": 1, "chat": g, "from": ben, "left_chat_member": bot})); r == nil || !r.BotLeft {
		t.Fatalf("bot left %+v", r)
	}
	// my_chat_member for the bot.
	fb := telegramtest.New()
	defer fb.Close()
	if r := reduce(t, fb.MemberUpdate(g, ben, "member", "administrator")); r == nil || r.Member == nil || r.Member.New != "administrator" {
		t.Fatalf("member %+v", r)
	}
	// Edits only matter for media messages.
	if r := reduce(t, map[string]any{"update_id": 99, "edited_message": map[string]any{"message_id": 1, "date": 1, "chat": g, "from": ben, "text": "x"}}); r != nil {
		t.Fatalf("edited text kept %+v", r)
	}
}

func TestBatchRules(t *testing.T) {
	t0 := time.Date(2026, 11, 19, 10, 0, 0, 0, time.UTC)
	var b telegram.Batch
	b.Add(t0, false)
	if !b.ClosesAt().Equal(t0.Add(60 * time.Second)) {
		t.Fatalf("closes %v", b.ClosesAt())
	}
	// A contributor posting every 50 s keeps it open until 10 minutes.
	for i := 1; i <= 15; i++ {
		b.Add(t0.Add(time.Duration(i)*50*time.Second), false)
	}
	if !b.ClosesAt().Equal(t0.Add(10 * time.Minute)) {
		t.Fatalf("cap %v", b.ClosesAt())
	}
	if b.Due(t0.Add(9*time.Minute), 200, false) || !b.Due(t0.Add(10*time.Minute), 200, false) {
		t.Fatal("due at the cap")
	}
	// An album still arriving holds the batch past the cap.
	b.Add(t0.Add(10*time.Minute-time.Second), true)
	if b.Due(t0.Add(10*time.Minute+2*time.Second), 200, false) || !b.Due(t0.Add(10*time.Minute+5*time.Second), 200, false) {
		t.Fatal("album grace")
	}
	if !b.Accepts(t0.Add(time.Hour), 1, true) || b.Accepts(t0.Add(time.Hour), 200, false) {
		t.Fatal("accepts")
	}
	// Full at max items.
	var f telegram.Batch
	for i := range 3 {
		f.Add(t0.Add(time.Duration(i)*time.Second), false)
	}
	if !f.Due(t0.Add(3*time.Second), 3, false) || f.Accepts(t0.Add(3*time.Second), 3, false) {
		t.Fatal("full")
	}
	// Pending downloads hold it up to 10 more minutes.
	if f.Due(t0.Add(2*time.Minute), 200, true) || !f.Due(t0.Add(12*time.Minute), 200, true) {
		t.Fatal("pending")
	}
}

func client(fb *telegramtest.Bot) *telegram.Client {
	return &telegram.Client{BaseURL: fb.URL, Token: telegramtest.Token}
}

func TestClientAgainstFake(t *testing.T) {
	fb := telegramtest.New()
	defer fb.Close()
	c := client(fb)
	ctx := context.Background()
	me, err := c.GetMe(ctx)
	if err != nil || me.ID != fb.BotID || me.Username != "kinframe_test_bot" || !me.CanReadAllGroupMessages {
		t.Fatalf("getMe %+v %v", me, err)
	}
	if err := c.SetWebhook(ctx, "https://k.example/v1/telegram/webhook", "sec"); err != nil {
		t.Fatal(err)
	}
	wi, err := c.GetWebhookInfo(ctx)
	if err != nil || wi.URL != "https://k.example/v1/telegram/webhook" || len(wi.AllowedUpdates) != 3 {
		t.Fatalf("webhook %+v %v", wi, err)
	}
	if _, err := c.GetUpdates(ctx, 0, 0); telegram.AsError(err) == nil || telegram.AsError(err).Code != 409 {
		t.Fatalf("getUpdates with a webhook: %v", err)
	}
	if err := c.DeleteWebhook(ctx); err != nil {
		t.Fatal(err)
	}
	fb.Push(map[string]any{"update_id": 10, "message": map[string]any{"message_id": 1}})
	fb.Push(map[string]any{"update_id": 11, "message": map[string]any{"message_id": 2}})
	ups, err := c.GetUpdates(ctx, 0, 1)
	if err != nil || len(ups) != 2 {
		t.Fatalf("updates %d %v", len(ups), err)
	}
	if ups, _ := c.GetUpdates(ctx, 12, 0); len(ups) != 0 {
		t.Fatal("confirmed updates returned again")
	}
	// Files.
	fb.Lock(func() { fb.Files["f1"] = []byte("JPEGDATA") })
	f, err := c.GetFile(ctx, "f1")
	if err != nil || f.FilePath == "" {
		t.Fatalf("getFile %+v %v", f, err)
	}
	dst := filepath.Join(t.TempDir(), "x")
	d, err := c.Download(ctx, f, dst, 100)
	if err != nil || d.Bytes != 8 || len(d.SHA256) != 64 {
		t.Fatalf("download %+v %v", d, err)
	}
	if _, err := c.Download(ctx, f, filepath.Join(t.TempDir(), "y"), 4); !errors.Is(err, telegram.ErrTooLarge) {
		t.Fatalf("cap: %v", err)
	}
	fb.Lock(func() { fb.FailFile["f1"] = 502 })
	_, err = c.Download(ctx, f, filepath.Join(t.TempDir(), "z"), 100)
	if e := telegram.AsError(err); e == nil || !e.Temporary() || strings.Contains(err.Error(), telegramtest.Token) {
		t.Fatalf("5xx: %v", err)
	}
	if _, err := c.GetFile(ctx, "nope"); telegram.AsError(err) == nil || telegram.AsError(err).Code != 400 {
		t.Fatalf("bad file: %v", err)
	}
	// Messages and reactions.
	id, err := c.SendMessage(ctx, -100, "hi", telegram.SendOptions{ReplyTo: 5, Silent: true, Buttons: [][]telegram.InlineButton{{{Text: "App", URL: "https://x"}}}})
	if err != nil || id == 0 {
		t.Fatal(err)
	}
	if m := fb.SentTo(-100); len(m) != 1 || !m[0].Silent || m[0].ReplyTo != 5 || m[0].Buttons[0] != "https://x" {
		t.Fatalf("sent %+v", m)
	}
	if err := c.SetMessageReaction(ctx, -100, 5, "👍"); err != nil || fb.ReactionOn(-100, 5) != "👍" {
		t.Fatalf("reaction %v", err)
	}
	fb.Lock(func() { fb.ReactionInvalid[-100] = true; fb.Migrated[-200] = -100200 })
	if e := telegram.AsError(c.SetMessageReaction(ctx, -100, 5, "❤")); e == nil || !e.Has("REACTION_INVALID") {
		t.Fatalf("restricted reactions: %v", e)
	}
	_, err = c.SendMessage(ctx, -200, "x", telegram.SendOptions{})
	if e := telegram.AsError(err); e == nil || e.MigrateTo != -100200 {
		t.Fatalf("migration answer: %v", err)
	}
	fb.Lock(func() { fb.RateLimitNext = 1 })
	if e := telegram.AsError(c.LeaveChat(ctx, -100)); e == nil || e.Code != 429 || e.RetryAfter != time.Second {
		t.Fatalf("429: %v", e)
	}
	// Transport errors never carry the token.
	bad := &telegram.Client{BaseURL: "http://127.0.0.1:1", Token: telegramtest.Token}
	if _, err := bad.GetMe(ctx); err == nil || strings.Contains(err.Error(), telegramtest.Token) {
		t.Fatalf("redaction: %v", err)
	}
}

func TestLocalFiles(t *testing.T) {
	dir := t.TempDir()
	tokDir := filepath.Join(dir, telegramtest.Token, "photos")
	if err := os.MkdirAll(tokDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(tokDir, "a.jpg"), []byte("LOCAL"), 0o644); err != nil {
		t.Fatal(err)
	}
	c := &telegram.Client{BaseURL: "http://bot-api:8081", Token: telegramtest.Token, LocalFilesDir: dir}
	if !c.Local() {
		t.Fatal("not local")
	}
	// The local server reports its own absolute path; the volume is mounted elsewhere here.
	f := &telegram.File{FilePath: "/var/lib/telegram-bot-api/" + telegramtest.Token + "/photos/a.jpg"}
	d, err := c.Download(context.Background(), f, filepath.Join(t.TempDir(), "o"), 100)
	if err != nil || d.Bytes != 5 {
		t.Fatalf("%+v %v", d, err)
	}
	if _, err := os.Stat(filepath.Join(tokDir, "a.jpg")); !os.IsNotExist(err) {
		t.Fatal("local file not deleted")
	}
	f.FilePath = "/etc/passwd"
	if _, err := c.Download(context.Background(), f, filepath.Join(t.TempDir(), "p"), 100); err == nil {
		t.Fatal("path outside the files dir accepted")
	}
}

func TestOutboxPacesAndRetries(t *testing.T) {
	o := &telegram.Outbox{GroupGap: 80 * time.Millisecond, ReactionGap: 10 * time.Millisecond}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go o.Run(ctx)
	var mu sync.Mutex
	var times []time.Time
	var tries atomic.Int32
	for i := range 3 {
		o.Add(telegram.Job{ChatID: -1, Group: true, Kind: telegram.OutMessage, Run: func(context.Context) error {
			if i == 1 && tries.Add(1) == 1 {
				return &telegram.Error{Code: 429, RetryAfter: 50 * time.Millisecond}
			}
			mu.Lock()
			times = append(times, time.Now())
			mu.Unlock()
			return nil
		}})
	}
	wctx, wcancel := context.WithTimeout(ctx, 5*time.Second)
	defer wcancel()
	time.Sleep(10 * time.Millisecond)
	o.Wait(wctx)
	mu.Lock()
	defer mu.Unlock()
	if len(times) != 3 {
		t.Fatalf("ran %d jobs", len(times))
	}
	for i := 1; i < len(times); i++ {
		if d := times[i].Sub(times[i-1]); d < 45*time.Millisecond {
			t.Fatalf("messages %d apart only %v", i, d)
		}
	}
}

type fakeLock struct {
	mu       sync.Mutex
	held     bool
	lost     bool
	acquired int
}

func (l *fakeLock) TryAcquire(context.Context) (bool, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.held {
		return false, nil
	}
	l.held, l.lost = true, false
	l.acquired++
	return true, nil
}

func (l *fakeLock) Check(context.Context) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.lost {
		return errors.New("connection lost")
	}
	return nil
}

func (l *fakeLock) Release() { l.mu.Lock(); l.held = false; l.mu.Unlock() }

func TestLeadOnlyOneConsumer(t *testing.T) {
	lock := &fakeLock{}
	ctx, cancel := context.WithCancel(context.Background())
	var running, maxRunning atomic.Int32
	work := func(ctx context.Context) {
		n := running.Add(1)
		for {
			m := maxRunning.Load()
			if n <= m || maxRunning.CompareAndSwap(m, n) {
				break
			}
		}
		<-ctx.Done()
		running.Add(-1)
	}
	var wg sync.WaitGroup
	for range 3 {
		wg.Add(1)
		go func() { defer wg.Done(); telegram.Lead(ctx, lock, 20*time.Millisecond, nil, work) }()
	}
	time.Sleep(100 * time.Millisecond)
	if maxRunning.Load() != 1 {
		t.Fatalf("%d consumers at once", maxRunning.Load())
	}
	// Losing the lock stops the consumer; another process takes over.
	lock.mu.Lock()
	lock.lost = true
	lock.mu.Unlock()
	deadline := time.Now().Add(2 * time.Second)
	for {
		lock.mu.Lock()
		n := lock.acquired
		lock.mu.Unlock()
		if n >= 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("no takeover after the lock was lost")
		}
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	wg.Wait()
	if maxRunning.Load() != 1 || running.Load() != 0 {
		t.Fatalf("max %d, still running %d", maxRunning.Load(), running.Load())
	}
}

func TestPollerStoresAndBacksOff(t *testing.T) {
	fb := telegramtest.New()
	defer fb.Close()
	var mu sync.Mutex
	offset := int64(0)
	var got []int64
	stored := make(chan struct{}, 10)
	p := &telegram.Poller{Client: client(fb), Timeout: 1, MinBackoff: 10 * time.Millisecond, MaxBackoff: 20 * time.Millisecond,
		Offset: func(context.Context) (int64, error) { mu.Lock(); defer mu.Unlock(); return offset, nil },
		Store: func(_ context.Context, ups []json.RawMessage) error {
			mu.Lock()
			defer mu.Unlock()
			for _, u := range ups {
				var h struct {
					UpdateID int64 `json:"update_id"`
				}
				_ = json.Unmarshal(u, &h)
				got = append(got, h.UpdateID)
				offset = h.UpdateID + 1
			}
			stored <- struct{}{}
			return nil
		}}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); p.Run(ctx) }()
	fb.Lock(func() { fb.RateLimitNext = 0 })
	fb.Push(map[string]any{"update_id": 1})
	fb.Push(map[string]any{"update_id": 2})
	<-stored
	fb.Push(map[string]any{"update_id": 3})
	select {
	case <-stored:
	case <-time.After(3 * time.Second):
		t.Fatal("third update not polled")
	}
	cancel()
	<-done
	mu.Lock()
	defer mu.Unlock()
	if len(got) != 3 || got[0] != 1 || got[2] != 3 {
		t.Fatalf("got %v", got)
	}
}

func TestDispatcherOrdersPerChat(t *testing.T) {
	var mu sync.Mutex
	queues := map[int64][]int{1: {1, 2, 3, 4}, 2: {1, 2}, 3: {1}}
	var order = map[int64][]int{}
	var concurrent, maxConc atomic.Int32
	d := &telegram.Dispatcher{Workers: 2, Tick: 5 * time.Millisecond,
		Chats: func(context.Context) ([]int64, error) {
			mu.Lock()
			defer mu.Unlock()
			var out []int64
			for c, q := range queues {
				if len(q) > 0 {
					out = append(out, c)
				}
			}
			return out, nil
		},
		Next: func(_ context.Context, chat int64) bool {
			n := concurrent.Add(1)
			defer concurrent.Add(-1)
			if m := maxConc.Load(); n > m {
				maxConc.Store(n)
			}
			time.Sleep(2 * time.Millisecond)
			mu.Lock()
			defer mu.Unlock()
			if len(queues[chat]) == 0 {
				return false
			}
			order[chat] = append(order[chat], queues[chat][0])
			queues[chat] = queues[chat][1:]
			return true
		}}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); d.Run(ctx) }()
	deadline := time.Now().Add(3 * time.Second)
	for {
		mu.Lock()
		left := len(queues[1]) + len(queues[2]) + len(queues[3])
		mu.Unlock()
		if left == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("not drained")
		}
		time.Sleep(5 * time.Millisecond)
	}
	cancel()
	<-done
	if maxConc.Load() > 2 {
		t.Fatalf("%d workers at once", maxConc.Load())
	}
	if got := order[1]; len(got) != 4 || got[0] != 1 || got[3] != 4 {
		t.Fatalf("chat 1 order %v", got)
	}
}
