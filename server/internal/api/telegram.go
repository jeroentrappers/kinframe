package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/i18n"
	"github.com/jeroentrappers/kinframe/server/internal/ratelimit"
	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/telegram"
)

// The Telegram bridge (docs/spec/telegram.md, README §5.17): the bot's start
// (getMe, privacy mode, webhook or polling with one consumer), the webhook,
// intake and the background loops. Chats, commands and codes are in
// telegram_chat.go, media and batches in telegram_media.go, what the bot
// says in telegram_out.go, the endpoints in telegram_api.go.

type telegramBridge struct {
	client  *telegram.Client
	secret  string
	maxDown int64
	out     *telegram.Outbox
	disp    *telegram.Dispatcher
	lock    telegram.Lock

	mu     sync.RWMutex
	bot    *store.TelegramBot // nil until getMe answered
	mode   string             // webhook or polling
	ready  chan struct{}      // closed once the bot is known
	leader bool

	busyMu sync.Mutex
	busy   map[uuid.UUID]int // chats whose media is being downloaded (the batch closer waits)

	codeUser  *ratelimit.Limiter // codes created: 30/h per user
	wrongCode *ratelimit.Limiter // wrong codes: 10/h per chat
	userMedia *ratelimit.Limiter // accepted media: 100/h per Telegram user
	errReply  *ratelimit.Limiter // error replies: 1 per code per chat per 10 min
	daily     *ratelimit.Limiter // once a day per user and notice
	closerMu  sync.Mutex
}

// setupTelegram prepares the bridge when a bot token is configured.
func (s *Server) setupTelegram() error {
	if !s.cfg.TelegramEnabled() {
		return nil
	}
	key := s.cfg.SecretsKey
	if len(key) == 0 {
		key = s.cfg.SigningKey
	}
	secret, err := telegram.WebhookSecret(key)
	if err != nil {
		return err
	}
	client := &telegram.Client{BaseURL: s.cfg.TelegramAPIURL, CloudURL: s.cfg.TelegramCloudURL, Token: s.cfg.TelegramBotToken,
		LocalFilesDir: s.cfg.TelegramLocalFilesDir}
	max := s.cfg.TelegramMaxDownload
	if max <= 0 {
		max = telegram.CloudMaxDownload
		if client.Local() {
			max = 2000 << 20
		}
	}
	t := &telegramBridge{
		client:  client,
		secret:  secret,
		maxDown: max,
		out:     &telegram.Outbox{Log: s.log.With("component", "telegram")},
		ready:   make(chan struct{}),
		busy:    map[uuid.UUID]int{},

		codeUser:  ratelimit.New(30, time.Hour),
		wrongCode: ratelimit.New(10, time.Hour),
		userMedia: ratelimit.New(100, time.Hour),
		errReply:  ratelimit.New(1, 10*time.Minute),
		daily:     ratelimit.New(1, 24*time.Hour),
	}
	workers := s.cfg.TelegramWorkers
	if workers <= 0 {
		workers = 4
	}
	t.disp = &telegram.Dispatcher{Workers: workers, Log: s.log.With("component", "telegram"),
		Chats: func(ctx context.Context) ([]int64, error) { return s.st.PendingTelegramChats(ctx, s.now(), 200) },
		Next:  s.telegramNext,
	}
	t.lock = &store.AdvisoryLock{Pool: s.st.Pool, Key: store.TelegramLockKey}
	s.tg = t
	return nil
}

func (t *telegramBridge) deliveryMode() string {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.mode
}

// telegramBot returns the bot once it is known (nil without Telegram).
func (s *Server) telegramBot() *store.TelegramBot {
	if s.tg == nil {
		return nil
	}
	s.tg.mu.RLock()
	defer s.tg.mu.RUnlock()
	return s.tg.bot
}

// TelegramOutbox exposes the outbound queue (tests wait on it).
func (s *Server) TelegramOutbox() *telegram.Outbox {
	if s.tg == nil {
		return nil
	}
	return s.tg.out
}

// TelegramReady is closed once the bot is known (tests).
func (s *Server) TelegramReady() <-chan struct{} { return s.tg.ready }

// TelegramIdle reports that no update is pending or being processed and no
// call is queued (tests poll it).
func (s *Server) TelegramIdle(ctx context.Context) bool {
	if s.tg == nil {
		return true
	}
	var n int
	_ = s.st.Pool.QueryRow(ctx, `SELECT count(*) FROM telegram_updates WHERE state = 'pending'`).Scan(&n)
	return n == 0 && s.tg.disp.Idle()
}

// webhookCapable reports whether Telegram can call this server (§7): https
// on 443, 80, 88 or 8443, and a host that isn't local or private.
func webhookCapable(public string) bool {
	u, err := url.Parse(public)
	if err != nil || u.Scheme != "https" {
		return false
	}
	switch u.Port() {
	case "", "443", "80", "88", "8443":
	default:
		return false
	}
	h := strings.ToLower(u.Hostname())
	if h == "localhost" || strings.HasSuffix(h, ".localhost") || strings.HasSuffix(h, ".local") || strings.HasSuffix(h, ".internal") || !strings.Contains(h, ".") {
		return false
	}
	if ip := net.ParseIP(h); ip != nil {
		return !(ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsUnspecified())
	}
	return true
}

func (s *Server) telegramMode() string {
	switch s.cfg.TelegramMode {
	case "webhook", "polling":
		return s.cfg.TelegramMode
	}
	if webhookCapable(s.cfg.PublicURL) || s.cfg.TelegramWebhookURL != "" {
		return "webhook"
	}
	return "polling"
}

func (s *Server) telegramWebhookURL() string {
	if s.cfg.TelegramWebhookURL != "" {
		return s.cfg.TelegramWebhookURL
	}
	return s.cfg.PublicURL + "/v1/telegram/webhook"
}

// RunTelegram starts the bot and runs its loops until ctx is done. Every
// process paces its own outbound calls; one process (the advisory lock
// holder) registers the webhook or polls, processes updates and closes batches.
func (s *Server) RunTelegram(ctx context.Context) {
	if s.tg == nil {
		return
	}
	var wg sync.WaitGroup
	wg.Add(1)
	go func() { defer wg.Done(); s.tg.out.Run(ctx) }()
	defer wg.Wait()
	backoff := time.Second
	for {
		err := s.telegramStart(ctx)
		if err == nil {
			break
		}
		if ctx.Err() != nil {
			return
		}
		s.log.Warn("telegram: start", "err", err, "retry_in", backoff)
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, 5*time.Minute)
	}
	telegram.Lead(ctx, s.tg.lock, 15*time.Second, s.log.With("component", "telegram"), s.telegramConsume)
}

// telegramStart calls getMe and records the bot (§6, §7).
func (s *Server) telegramStart(ctx context.Context) error {
	prev, err := s.st.GetTelegramBot(ctx)
	if err != nil && err != store.ErrNotFound {
		return err
	}
	c := s.tg.client
	if c.Local() && (prev == nil || prev.APIBase == nil || *prev.APIBase != s.cfg.TelegramAPIURL) {
		// Telegram requires logOut on the cloud API before a local Bot API server takes the bot (§9.3).
		cctx, cancel := context.WithTimeout(ctx, 30*time.Second)
		if err := c.Cloud().LogOut(cctx); err != nil {
			s.log.Info("telegram: logOut on the cloud Bot API", "err", err)
		}
		cancel()
	}
	cctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	me, err := c.GetMe(cctx)
	cancel()
	if err != nil {
		return err
	}
	mode := s.telegramMode()
	bot, err := s.st.SaveTelegramBot(ctx, store.TelegramBot{BotID: me.ID, Username: me.Username, CanReadAll: me.CanReadAllGroupMessages, Mode: mode}, s.now())
	if err != nil {
		return err
	}
	api := s.cfg.TelegramAPIURL
	if api == "" {
		api = telegram.CloudAPI
	}
	if bot.APIBase == nil || *bot.APIBase != api {
		if err := s.st.SetTelegramBotField(ctx, "api_base", &api); err != nil {
			return err
		}
		bot.APIBase = &api
	}
	if prev != nil && prev.BotID != me.ID {
		s.log.Warn("telegram: the bot changed; connected chats need to be connected again", "old", prev.BotID, "new", me.ID)
	}
	ids, err := s.st.MarkChatsBotChanged(ctx, me.ID)
	if err != nil {
		return err
	}
	s.tg.mu.Lock()
	s.tg.bot, s.tg.mode = bot, mode
	select {
	case <-s.tg.ready:
	default:
		close(s.tg.ready)
	}
	s.tg.mu.Unlock()
	for _, id := range ids {
		s.telegramAttention(ctx, id, "bot_changed")
		s.publishTelegramChat(ctx, id, nil)
	}
	s.log.Info("telegram: bot ready", "username", me.Username, "mode", mode, "privacy_mode", !me.CanReadAllGroupMessages)
	return nil
}

// telegramConsume is the elected consumer's work.
func (s *Server) telegramConsume(ctx context.Context) {
	s.tg.mu.Lock()
	s.tg.leader = true
	mode := s.tg.mode
	s.tg.mu.Unlock()
	defer func() {
		s.tg.mu.Lock()
		s.tg.leader = false
		s.tg.mu.Unlock()
	}()
	log := s.log.With("component", "telegram")
	if err := s.telegramDelivery(ctx, mode); err != nil && ctx.Err() == nil {
		log.Warn("telegram: delivery setup", "mode", mode, "err", err)
	}
	s.telegramCommands(ctx)
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); s.tg.disp.Run(ctx) }()
	go func() { defer wg.Done(); s.telegramCloser(ctx) }()
	if mode == "polling" {
		wg.Add(1)
		go func() {
			defer wg.Done()
			p := &telegram.Poller{Client: s.tg.client, Log: log, Offset: s.st.TelegramPollOffset, Store: s.telegramIntakePoll}
			p.Run(ctx)
		}()
	}
	wg.Wait()
}

// telegramDelivery registers the webhook (when it differs) or removes it
// before polling (a bot can't do both).
func (s *Server) telegramDelivery(ctx context.Context, mode string) error {
	cctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	info, err := s.tg.client.GetWebhookInfo(cctx)
	if err != nil {
		return err
	}
	if mode == "polling" {
		if info.URL == "" {
			return nil
		}
		return s.tg.client.DeleteWebhook(cctx)
	}
	u := s.telegramWebhookURL()
	h := sha256.Sum256([]byte(u + "\n" + s.tg.secret + "\n" + strings.Join(telegram.AllowedUpdates, ",")))
	hash := hex.EncodeToString(h[:])
	bot := s.telegramBot()
	if info.URL == u && sameStrings(info.AllowedUpdates, telegram.AllowedUpdates) && bot != nil && bot.WebhookHash != nil && *bot.WebhookHash == hash {
		return nil
	}
	if err := s.tg.client.SetWebhook(cctx, u, s.tg.secret); err != nil {
		return err
	}
	return s.st.SetTelegramBotField(ctx, "webhook_hash", &hash)
}

func sameStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// telegramCommands registers the command menu per language (§11), once per
// change of the texts.
func (s *Server) telegramCommands(ctx context.Context) {
	build := func(lang string, group bool) []telegram.BotCommand {
		names := []string{"start", "help", "status", "unlink"}
		if group {
			names = []string{"help", "status", "stop"}
		} else {
			names = append(names, "stop")
		}
		out := make([]telegram.BotCommand, len(names))
		for i, n := range names {
			out[i] = telegram.BotCommand{Command: n, Description: i18n.T(lang, "telegram_cmd_"+n, nil)}
		}
		return out
	}
	h := sha256.New()
	for _, l := range i18n.Languages {
		for _, g := range []bool{false, true} {
			b, _ := json.Marshal(build(l, g))
			h.Write(b)
		}
	}
	hash := hex.EncodeToString(h.Sum(nil))
	if bot := s.telegramBot(); bot != nil && bot.CommandsHash != nil && *bot.CommandsHash == hash {
		return
	}
	for _, l := range append([]string{""}, i18n.Languages...) {
		lang, code := l, strings.ToLower(l)
		if lang == "" {
			lang = i18n.Default
		}
		if i := strings.IndexByte(code, '-'); i > 0 {
			code = code[:i] // Telegram takes two-letter ISO 639-1 codes
		}
		if l == "zh-TW" {
			continue // one Chinese menu (zh-CN); Telegram can't tell them apart
		}
		for _, scope := range []string{"all_private_chats", "all_group_chats"} {
			cctx, cancel := context.WithTimeout(ctx, 15*time.Second)
			err := s.tg.client.SetMyCommands(cctx, build(lang, scope == "all_group_chats"), scope, code)
			cancel()
			if err != nil {
				if ctx.Err() == nil {
					s.log.Info("telegram: setMyCommands", "lang", code, "err", err)
				}
				return
			}
		}
	}
	_ = s.st.SetTelegramBotField(ctx, "commands_hash", &hash)
}

// ---------------------------------------------------------------- intake

// telegramIntake filters raw updates and stores the ones the server needs
// (§8.1). offset > 0 advances the poll offset in the same transaction.
func (s *Server) telegramIntake(ctx context.Context, raws []json.RawMessage, polling bool) error {
	bot := s.telegramBot()
	if bot == nil {
		return errors.New("telegram: bot not known yet")
	}
	var rows []store.TelegramUpdate
	var last int64
	for _, raw := range raws {
		r, id, err := telegram.Reduce(raw, bot.BotID, bot.Username)
		if id > last {
			last = id
		}
		if err != nil || r == nil {
			continue // dropped unstored: only the offset moves on
		}
		payload, _ := json.Marshal(r)
		chat := r.ChatID
		rows = append(rows, store.TelegramUpdate{UpdateID: id, ChatID: &chat, Payload: payload})
	}
	off := int64(0)
	if polling && last > 0 {
		off = last + 1
	}
	n, err := s.st.StoreTelegramUpdates(ctx, rows, off)
	if err != nil {
		return err
	}
	if n > 0 {
		s.tg.disp.Wake()
	}
	return nil
}

func (s *Server) telegramIntakePoll(ctx context.Context, raws []json.RawMessage) error {
	return s.telegramIntake(ctx, raws, true)
}

// Telegram's published webhook source ranges (§13.2, core.telegram.org/bots/webhooks).
var telegramNets = func() []*net.IPNet {
	var out []*net.IPNet
	for _, c := range []string{"149.154.160.0/20", "91.108.4.0/22"} {
		_, n, _ := net.ParseCIDR(c)
		out = append(out, n)
	}
	return out
}()

// telegramWebhook receives Telegram's updates (hand-written, excluded from
// codegen): secret header, at most 1 MiB, stored and acknowledged at once.
func (s *Server) telegramWebhook(w http.ResponseWriter, r *http.Request) {
	if s.tg == nil || s.telegramBot() == nil || s.tg.deliveryMode() != "webhook" {
		http.NotFound(w, r)
		return
	}
	if s.cfg.TelegramWebhookIPCheck {
		ip := net.ParseIP(clientIP(r.Context()))
		allowed := false
		for _, n := range telegramNets {
			if ip != nil && n.Contains(ip) {
				allowed = true
			}
		}
		if !allowed {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
	}
	if !telegram.CheckSecret(r.Header.Get(telegram.SecretHeader), s.tg.secret) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20+1))
	if err != nil {
		http.Error(w, "unreadable body", http.StatusBadRequest)
		return
	}
	if len(body) > 1<<20 {
		http.Error(w, "body too large", http.StatusRequestEntityTooLarge)
		return
	}
	if err := s.telegramIntake(r.Context(), []json.RawMessage{body}, false); err != nil {
		// Telegram retries non-2xx answers: a database hiccup isn't lost.
		s.log.Warn("telegram: store update", "err", err)
		http.Error(w, "try again", http.StatusServiceUnavailable)
		return
	}
	w.WriteHeader(http.StatusOK)
}

// ---------------------------------------------------------------- processing

// telegramNext processes a chat's oldest pending update (dispatcher callback).
func (s *Server) telegramNext(ctx context.Context, chatID int64) bool {
	now := s.now()
	u, err := s.st.NextTelegramUpdate(ctx, chatID, now)
	if err != nil {
		if err != store.ErrNotFound && ctx.Err() == nil {
			s.log.Warn("telegram: next update", "chat", chatID, "err", err)
		}
		return false
	}
	var r telegram.Reduced
	if err := json.Unmarshal(u.Payload, &r); err != nil {
		_ = s.st.FinishTelegramUpdate(ctx, u.UpdateID, now)
		return true
	}
	if err := s.telegramHandle(ctx, &r); err != nil {
		if ctx.Err() != nil {
			return false
		}
		s.log.Warn("telegram: update failed", "update", u.UpdateID, "chat", chatID, "attempt", u.Attempts+1, "err", err)
		next := now.Add(time.Duration(u.Attempts+1) * time.Minute)
		if err := s.st.RetryTelegramUpdate(context.WithoutCancel(ctx), u.UpdateID, next, 3); err != nil {
			s.log.Warn("telegram: record failure", "update", u.UpdateID, "err", err)
		}
		return false
	}
	if err := s.st.FinishTelegramUpdate(context.WithoutCancel(ctx), u.UpdateID, s.now()); err != nil {
		s.log.Warn("telegram: finish update", "update", u.UpdateID, "err", err)
		return false
	}
	return true
}

// telegramCloser closes due batches, retries failed downloads, lets stray
// groups go and says goodbye for deleted targets (§8.4, §9.1, §8.2, §12).
func (s *Server) telegramCloser(ctx context.Context) {
	t := time.NewTicker(telegram.CloserTick)
	defer t.Stop()
	for {
		s.TelegramTick(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// TelegramTick runs one round of the closer (tests call it after moving the clock).
func (s *Server) TelegramTick(ctx context.Context) {
	if s.tg == nil || s.telegramBot() == nil {
		return
	}
	s.tg.closerMu.Lock()
	defer s.tg.closerMu.Unlock()
	if err := s.telegramRetryDownloads(ctx); err != nil && ctx.Err() == nil {
		s.log.Warn("telegram: retry downloads", "err", err)
	}
	if err := s.telegramCloseDue(ctx, nil); err != nil && ctx.Err() == nil {
		s.log.Warn("telegram: close batches", "err", err)
	}
	s.telegramStrays(ctx)
	s.telegramFarewells(ctx)
}

// PruneTelegram applies the retention rules (maintenance).
func (s *Server) PruneTelegram(ctx context.Context) error {
	if s.tg == nil {
		// Rows stay while the token is removed: re-adding the same bot resumes them (§12).
		return nil
	}
	gone, err := s.st.ExpiredTelegramChats(ctx, s.now())
	if err != nil {
		return err
	}
	if err := s.st.PruneTelegram(ctx, s.now()); err != nil {
		return err
	}
	for i := range gone {
		s.publishTelegramChatRemoved(ctx, &gone[i])
	}
	return nil
}
