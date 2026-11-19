package api

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/telegram"
)

// Telegram media (docs/spec/telegram.md §4.3, §8, §9): the sender decision,
// limits, downloads, dedupe, batches and the send or post each batch becomes.

// tgDecision is who a media message counts as (§4.3).
type tgDecision struct {
	key     int64  // contributor: the Telegram user, or the sender chat
	name    string // Telegram display name (or author signature / channel title)
	lang    string
	sender  uuid.UUID // the kinframe sender
	outside bool      // sent by the owner on the contributor's behalf
	label   string    // ItemVia.label: the person's name
	linked  bool      // a linked account (no promotion)
	member  bool      // already a member of the target (no join link)
	refuse  string    // "": accepted; "silent"; "linked_only" (reply with a join link)
	ignore  bool      // other bots: no row at all
}

func (s *Server) tgDecide(ctx context.Context, chat *store.TelegramChatView, r *telegram.Reduced) (*tgDecision, error) {
	d := &tgDecision{sender: *chat.OwnerUserID, outside: true}
	linkedOnly := chat.Contributors == "linked_only"
	switch {
	case r.SenderChat != nil:
		// Anonymous group admin or a post on behalf of a channel.
		d.key, d.name = r.SenderChat.ID, r.SenderChat.Title
		if r.AuthorSignature != "" {
			d.name = r.AuthorSignature
		}
		d.label = d.name
		if linkedOnly {
			d.refuse = "silent"
		}
		return d, nil
	case r.From == nil || r.From.IsBot:
		d.ignore = true
		return d, nil
	}
	d.key, d.name, d.lang, d.label = r.From.ID, r.From.Name, r.From.Lang, r.From.Name
	if l, err := s.st.TelegramLinkByTg(ctx, s.st.Q(), r.From.ID); err == nil {
		d.linked = true
		d.label = l.UserName
		ok, m, err := s.tgCanSend(ctx, l.UserID, chatTarget(&chat.TelegramChat))
		if err != nil {
			return nil, err
		}
		if ok {
			_ = m
			d.sender, d.outside, d.member = l.UserID, false, true
			return d, nil
		}
	} else if err != store.ErrNotFound {
		return nil, err
	}
	if linkedOnly {
		d.refuse = "linked_only"
	}
	return d, nil
}

// tgPending is what a contribution needs to be downloaded later (retries).
type tgPending struct {
	Media    telegram.Media `json:"media"`
	Label    string         `json:"label"`
	Outside  bool           `json:"outside"`
	Linked   bool           `json:"linked"`
	Member   bool           `json:"member"`
	Name     string         `json:"name"`
	Lang     string         `json:"lang,omitempty"`
	Hint     *time.Time     `json:"hint,omitempty"`
	ThreadID int64          `json:"thread_id,omitempty"`
	TgUserID int64          `json:"tg_user_id"`
}

func (s *Server) tgBusy(chat uuid.UUID, delta int) {
	s.tg.busyMu.Lock()
	s.tg.busy[chat] += delta
	if s.tg.busy[chat] <= 0 {
		delete(s.tg.busy, chat)
	}
	s.tg.busyMu.Unlock()
}

func (s *Server) tgIsBusy(chat uuid.UUID) bool {
	s.tg.busyMu.Lock()
	defer s.tg.busyMu.Unlock()
	return s.tg.busy[chat] > 0
}

// tgMedia handles a media message (§8.2 steps 4–6).
func (s *Server) tgMedia(ctx context.Context, chat *store.TelegramChatView, r *telegram.Reduced) error {
	if chat == nil {
		if r.ChatType == "private" && r.From != nil {
			if ok, _ := s.tg.daily.Allow("unbound:" + strconv.FormatInt(r.From.ID, 10)); ok {
				lang := s.tgLang(ctx, nil, r.From)
				here := &store.TelegramChatView{TelegramChat: store.TelegramChat{TgChatID: r.ChatID, ChatKind: "private"}}
				s.tgSay(here, tgT(lang, "not_connected", nil), telegram.SendOptions{ReplyTo: r.MessageID, Silent: true, Buttons: s.tgAppButtons(lang)})
			}
		}
		return nil
	}
	if _, err := s.st.GetTelegramContribution(ctx, s.st.Q(), chat.ID, r.MessageID); err == nil {
		return nil // processed already (§8.6 step 1)
	} else if err != store.ErrNotFound {
		return err
	}
	date := time.Unix(r.Date, 0).UTC()
	c := &store.TelegramContribution{ChatID: chat.ID, MessageID: r.MessageID, TgChatID: r.ChatID, FileUniqueID: strOrNil(r.Media.FileUniqueID),
		MediaGroupID: strOrNil(r.MediaGroupID), MessageDate: &date}
	if r.ThreadID != 0 {
		c.ThreadID = &r.ThreadID
	}
	if cap := telegram.Caption(r.Caption); cap != "" {
		c.Caption = &cap
	}
	skip := func(reason string) error {
		c.State, c.SkipReason = "skipped", &reason
		if err := s.st.PutTelegramContribution(ctx, s.st.Q(), c); err != nil {
			return err
		}
		return s.st.BumpTelegramCounts(ctx, s.st.Q(), chat.ID, "skipped", 1)
	}
	if chat.Status == "paused" {
		return skip("not_allowed")
	}
	if chat.TopicID != nil {
		thread := r.ThreadID
		if thread == 0 {
			thread = 1 // General
		}
		if thread != *chat.TopicID {
			return skip("topic")
		}
	}
	if chat.Status == "needs_attention" && chat.ErrorCode != nil && *chat.ErrorCode != "privacy_mode" {
		// Whatever the bot still sees in privacy mode is accepted; other problems refuse (§5.2).
		if r.From != nil {
			if ok, _ := s.tg.daily.Allow("attention:" + chat.ID.String() + ":" + strconv.FormatInt(r.From.ID, 10)); ok {
				lang := s.tgLang(ctx, chat, r.From)
				s.tgSay(chat, tgT(lang, "needs_attention", map[string]string{"target": tgTargetName(chat), "owner": tgOwnerName(chat)}),
					telegram.SendOptions{ReplyTo: r.MessageID, ThreadID: r.ThreadID, Silent: true})
			}
		}
		return skip("not_allowed")
	}
	if err := s.tgOwnerOK(ctx, chat); err != nil {
		return err
	}
	if chat.OwnerUserID == nil {
		return skip("not_allowed")
	}
	d, err := s.tgDecide(ctx, chat, r)
	if err != nil {
		return err
	}
	if d.ignore {
		return nil
	}
	now := s.now()
	var lang *string
	if d.lang != "" {
		lang = &d.lang
	}
	who, err := s.st.UpsertTelegramContributor(ctx, chat.ID, d.key, d.name, lang, now)
	if err != nil {
		return err
	}
	c.ContributorID = &who.ID
	if who.Blocked || d.refuse == "silent" {
		return skip("not_allowed")
	}
	if d.refuse == "linked_only" {
		if err := skip("not_allowed"); err != nil {
			return err
		}
		s.tgPromote(ctx, chat, who, d, r, true)
		return nil
	}
	reply := func(code, key string, args map[string]string, buttons bool) {
		if ok, _ := s.tg.errReply.Allow(chat.ID.String() + ":" + code); !ok {
			return
		}
		l := s.tgLang(ctx, chat, r.From)
		o := telegram.SendOptions{ReplyTo: r.MessageID, ThreadID: r.ThreadID, Silent: true}
		if buttons {
			o.Buttons = s.tgAppButtons(l)
		}
		s.tgSay(chat, tgT(l, key, args), o)
	}
	limits := s.Limits(ctx)
	if n, err := s.st.TelegramChatItemsSince(ctx, chat.ID, now.Add(-time.Hour)); err != nil {
		return err
	} else if n >= limits.MaxTelegramItemsPerChatHour {
		reply("rate_limited", "rate_limited", nil, true)
		return skip("rate_limited")
	}
	if ok, _ := s.tg.userMedia.Allow(strconv.FormatInt(d.key, 10)); !ok {
		reply("rate_limited", "rate_limited", nil, true)
		return skip("rate_limited")
	}
	if r.Spoiler {
		s.tgReact(chat, r.MessageID, telegram.EmojiSpoiler)
		if chat.SpoilerNoticeAt == nil {
			chat.SpoilerNoticeAt = &now
			if err := s.st.SaveTelegramChat(ctx, s.st.Q(), &chat.TelegramChat); err != nil {
				return err
			}
			l := s.tgLang(ctx, chat, r.From)
			s.tgSay(chat, tgT(l, "spoiler", nil), telegram.SendOptions{ReplyTo: r.MessageID, ThreadID: r.ThreadID, Silent: true})
		}
		return skip("spoiler")
	}
	if reason := s.tgTooBig(r.Media.Size, limits.MaxUploadBytes); reason != "" {
		s.tgSkipReply(ctx, chat, r.MessageID, r.ThreadID, r.From, reason, limits)
		return skip(reason)
	}
	if (r.Media.Kind == telegram.MediaVideo || r.Media.Kind == telegram.MediaAnimation) && r.Media.Duration > limits.MaxVideoSeconds {
		s.tgSkipReply(ctx, chat, r.MessageID, r.ThreadID, r.From, "too_long", limits)
		return skip("too_long")
	}
	c.SenderUserID = &d.sender
	hint := date
	if r.ForwardDate != 0 {
		hint = time.Unix(r.ForwardDate, 0).UTC() // forwarded: the original message's date (§8.5)
	}
	p := &tgPending{Media: *r.Media, Label: d.label, Outside: d.outside, Linked: d.linked, Member: d.member, Name: d.name, Lang: d.lang,
		Hint: &hint, ThreadID: r.ThreadID, TgUserID: d.key}
	if r.Media.FileUniqueID != "" {
		if item, err := s.st.TelegramFileInTarget(ctx, chat.FrameID, chat.FeedID, r.Media.FileUniqueID); err != nil {
			return err
		} else if item != nil {
			return s.tgDuplicate(ctx, chat, c, *item)
		}
	}
	accepted, err := s.tgFetch(ctx, chat, c, p)
	if err != nil || !accepted {
		return err
	}
	if !d.linked {
		s.tgPromote(ctx, chat, who, d, r, false)
	}
	return nil
}

// tgTooBig checks the reported size before downloading (§9.1).
func (s *Server) tgTooBig(size, maxUpload int64) string {
	switch {
	case size > maxUpload:
		return "too_large"
	case size > s.tg.maxDown:
		return "too_large_for_bot"
	}
	return ""
}

// tgSkipReply answers a refused file (§15.2), at most once per code per chat per 10 minutes.
func (s *Server) tgSkipReply(ctx context.Context, chat *store.TelegramChatView, msgID, thread int64, from *telegram.Person, reason string, limits config.Limits) {
	switch reason {
	case "too_large_for_bot", "too_large", "too_long", "unsupported":
	default:
		return
	}
	if ok, _ := s.tg.errReply.Allow(chat.ID.String() + ":" + reason); !ok {
		return
	}
	lang := s.tgLang(ctx, chat, from)
	o := telegram.SendOptions{ReplyTo: msgID, ThreadID: thread, Silent: true}
	if reason == "too_large_for_bot" || reason == "too_long" {
		o.Buttons = s.tgAppButtons(lang)
	}
	s.tgSay(chat, tgT(lang, reason, s.tgReasonArgs(reason, limits.MaxUploadBytes, limits.MaxVideoSeconds)), o)
}

func (s *Server) tgDuplicate(ctx context.Context, chat *store.TelegramChatView, c *store.TelegramContribution, item uuid.UUID) error {
	c.State, c.ItemID, c.Media, c.RetryAt = "duplicate", &item, nil, nil
	if err := s.st.PutTelegramContribution(ctx, s.st.Q(), c); err != nil {
		return err
	}
	if err := s.st.BumpTelegramCounts(ctx, s.st.Q(), chat.ID, "duplicates", 1); err != nil {
		return err
	}
	s.tgConfirm(ctx, chat, c)
	return nil
}

// tgFetch downloads a contribution's file and adds it to a batch. A
// temporary failure keeps it collecting for a retry (3 attempts within 30
// minutes, §9.1). Reports whether it was accepted.
func (s *Server) tgFetch(ctx context.Context, chat *store.TelegramChatView, c *store.TelegramContribution, p *tgPending) (bool, error) {
	s.tgBusy(chat.ID, 1)
	defer s.tgBusy(chat.ID, -1)
	limits := s.Limits(ctx)
	skip := func(reason string) (bool, error) {
		c.State, c.SkipReason, c.Media, c.RetryAt = "skipped", &reason, nil, nil
		if err := s.st.PutTelegramContribution(ctx, s.st.Q(), c); err != nil {
			return false, err
		}
		from := &telegram.Person{ID: p.TgUserID, Lang: p.Lang}
		s.tgSkipReply(ctx, chat, c.MessageID, p.ThreadID, from, reason, limits)
		return false, s.st.BumpTelegramCounts(ctx, s.st.Q(), chat.ID, "skipped", 1)
	}
	retry := func(code string) (bool, error) {
		c.Attempts++
		if c.Attempts >= 3 {
			c.State, c.FailureCode, c.Media, c.RetryAt = "failed", &code, nil, nil
			if err := s.st.PutTelegramContribution(ctx, s.st.Q(), c); err != nil {
				return false, err
			}
			return false, s.st.BumpTelegramCounts(ctx, s.st.Q(), chat.ID, "failed", 1)
		}
		next := s.now().Add([]time.Duration{2 * time.Minute, 10 * time.Minute}[c.Attempts-1])
		raw, _ := json.Marshal(p)
		c.State, c.Media, c.RetryAt, c.FailureCode = "collecting", raw, &next, &code
		return false, s.st.PutTelegramContribution(ctx, s.st.Q(), c)
	}
	f, err := s.tg.client.GetFile(ctx, p.Media.FileID)
	if err != nil {
		e := telegram.AsError(err)
		switch {
		case e != nil && e.Has("file is too big"):
			return skip("too_large_for_bot")
		case e != nil && e.Temporary():
			return retry("unreachable")
		case e != nil:
			return skip("unsupported")
		}
		return retry("unreachable")
	}
	if reason := s.tgTooBig(f.FileSize, limits.MaxUploadBytes); reason != "" {
		return skip(reason)
	}
	path := s.scratchPath()
	dl, err := s.tg.client.Download(ctx, f, path, min(s.tg.maxDown, limits.MaxUploadBytes))
	if err != nil {
		if errors.Is(err, telegram.ErrTooLarge) {
			return skip(s.tgTooBig(s.tg.maxDown+1, limits.MaxUploadBytes))
		}
		if ctx.Err() != nil {
			return false, ctx.Err()
		}
		return retry("unreachable")
	}
	keep := false
	defer func() {
		if !keep {
			_ = os.Remove(dl.Path)
		}
	}()
	kind, mime, err := media.Sniff(dl.Path)
	if err != nil {
		return skip("unsupported")
	}
	wantVideo := p.Media.Kind == telegram.MediaVideo || p.Media.Kind == telegram.MediaAnimation
	if (kind == media.Video) != wantVideo && p.Media.Kind != telegram.MediaImage {
		return skip("unsupported")
	}
	fx := &fetched{path: dl.Path, kind: kind, mime: mime, sha: dl.SHA256, size: dl.Bytes}
	if kind == media.Video {
		info, err := s.tools.ProbeVideo(ctx, dl.Path)
		if err != nil {
			return skip("unsupported")
		}
		if info.DurationMS > limits.MaxVideoSeconds*1000 {
			return skip("too_long")
		}
		fx.dur = info.DurationMS
	}
	// The same photo already in the target (§8.6 step 3): 👍 anyway, nothing new.
	var dup uuid.UUID
	var found bool
	if chat.FrameID != nil {
		_, dup, found, err = s.st.DuplicateOnFrame(ctx, s.st.Q(), *chat.FrameID, fx.sha, "")
	} else {
		dup, found, err = s.st.DuplicateInFeed(ctx, s.st.Q(), *chat.FeedID, fx.sha, "")
	}
	if err != nil {
		return false, err
	}
	if found {
		return false, s.tgDuplicate(ctx, chat, c, dup)
	}
	sender := *c.SenderUserID
	now := s.now()
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		mediaID, err := s.tgMediaFor(ctx, tx, sender, fx, p.Media.Kind != telegram.MediaAnimation)
		if err != nil {
			return err
		}
		item := store.Item{ID: store.NewID(), MediaID: mediaID, SenderUserID: &sender, Caption: c.Caption, CapturedHint: p.Hint}
		if err := tx.CreateItem(ctx, item); err != nil {
			return err
		}
		if err := tx.SetTelegramItemCredit(ctx, item.ID, chat.ID, p.Label); err != nil {
			return err
		}
		b, err := s.tgBatchFor(ctx, tx, chat, c, p, now)
		if err != nil {
			return err
		}
		c.State, c.ItemID, c.BatchID, c.Media, c.RetryAt, c.FailureCode = "collecting", &item.ID, &b.ID, nil, nil, nil
		return s.st.PutTelegramContribution(ctx, tx, c)
	})
	if err != nil {
		return false, err
	}
	keep = true // the pipeline owns the file now
	s.tgConfirm(ctx, chat, c)
	return true, nil
}

// tgMediaFor creates the media of a download, or reuses the sender's media
// of the same file (§8.6 step 4).
func (s *Server) tgMediaFor(ctx context.Context, tx *store.Tx, owner uuid.UUID, f *fetched, audio bool) (uuid.UUID, error) {
	if audio {
		return s.mediaFor(ctx, tx, owner, f)
	}
	id := store.NewID()
	path, mime := f.path, f.mime
	md := store.Media{ID: id, OwnerUserID: &owner, Kind: string(f.kind), SourcePath: &path, SourceMime: &mime, IncludeAudio: false, SourceSHA256: &f.sha}
	if f.kind == media.Video {
		md.DurationMS = &f.dur
	}
	if err := tx.CreateMedia(ctx, md); err != nil {
		return uuid.Nil, err
	}
	return id, tx.Enqueue(ctx, store.JobProcessMedia, map[string]any{"media_id": id})
}

// tgBatchFor returns the open batch the contribution joins, opening one when
// there is none or the open one may not take it (§8.4).
func (s *Server) tgBatchFor(ctx context.Context, tx *store.Tx, chat *store.TelegramChatView, c *store.TelegramContribution, p *tgPending, now time.Time) (*store.TelegramBatch, error) {
	max := s.Limits(ctx).MaxItemsPerSend
	b, err := tx.OpenTelegramBatch(ctx, chat.ID, *c.ContributorID, *c.SenderUserID, p.Outside)
	if err != nil && err != store.ErrNotFound {
		return nil, err
	}
	album := c.MediaGroupID != nil
	if b != nil {
		same := false
		if album {
			if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM telegram_contributions WHERE batch_id = $1 AND media_group_id = $2)`,
				b.ID, *c.MediaGroupID).Scan(&same); err != nil {
				return nil, err
			}
		}
		if !tgBatchOf(b).Accepts(now, max, same) {
			b = nil
		}
	}
	if b == nil {
		b = &store.TelegramBatch{ID: store.NewID(), ChatID: chat.ID, ContributorID: *c.ContributorID, SenderUserID: *c.SenderUserID,
			Outside: p.Outside, Label: p.Label, OpenedAt: now}
	}
	tb := tgBatchOf(b)
	tb.Add(now, album)
	b.LastAt, b.Items, b.ClosesAt = tb.LastAt, tb.Items, tb.ClosesAt()
	if !tb.AlbumAt.IsZero() {
		at := tb.AlbumAt
		b.AlbumAt = &at
	}
	b.Label = p.Label
	return b, tx.SaveTelegramBatch(ctx, b)
}

func tgBatchOf(b *store.TelegramBatch) telegram.Batch {
	tb := telegram.Batch{OpenedAt: b.OpenedAt, LastAt: b.LastAt, Items: b.Items}
	if b.AlbumAt != nil {
		tb.AlbumAt = *b.AlbumAt
	}
	return tb
}

// telegramRetryDownloads tries failed downloads again (§9.1); a late
// success joins the contributor's open batch or opens a new one.
func (s *Server) telegramRetryDownloads(ctx context.Context) error {
	rows, err := s.st.DueTelegramRetries(ctx, s.now())
	if err != nil {
		return err
	}
	for i := range rows {
		c := &rows[i]
		var p tgPending
		if json.Unmarshal(c.Media, &p) != nil || c.SenderUserID == nil || c.ContributorID == nil {
			c.State, c.Media, c.RetryAt = "failed", nil, nil
			_ = s.st.PutTelegramContribution(ctx, s.st.Q(), c)
			continue
		}
		chat, err := s.st.GetTelegramChat(ctx, c.ChatID)
		if err != nil || chat.Status == "disconnected" {
			c.State, c.Media, c.RetryAt = "failed", nil, nil
			_ = s.st.PutTelegramContribution(ctx, s.st.Q(), c)
			continue
		}
		if _, err := s.tgFetch(ctx, chat, c, &p); err != nil {
			return err
		}
	}
	return nil
}

// telegramCloseDue closes due batches; with chatID every batch of that chat
// (disconnecting flushes them).
func (s *Server) telegramCloseDue(ctx context.Context, chatID *uuid.UUID) error {
	now := s.now()
	max := s.Limits(ctx).MaxItemsPerSend
	bs, err := s.st.DueTelegramBatches(ctx, now, max, chatID)
	if err != nil {
		return err
	}
	for i := range bs {
		b := &bs[i]
		if chatID == nil {
			var pending bool
			if err := s.st.Pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM telegram_contributions WHERE chat_id = $1 AND contributor_id = $2
				AND state = 'collecting' AND item_id IS NULL)`, b.ChatID, b.ContributorID).Scan(&pending); err != nil {
				return err
			}
			if !tgBatchOf(b).Due(now, max, pending || s.tgIsBusy(b.ChatID)) {
				continue
			}
		}
		if err := s.tgCloseBatch(ctx, b.ID); err != nil {
			return err
		}
	}
	return nil
}

// tgCloseBatch turns a batch into one send (§8.4): placements on the frame
// (moderated per §8.7) or one post in the feed, items in message order.
func (s *Server) tgCloseBatch(ctx context.Context, batchID uuid.UUID) error {
	sendID := store.NewID()
	var sender uuid.UUID
	var chatID uuid.UUID
	sent := 0
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		b, err := tx.LockTelegramBatch(ctx, batchID)
		if err == store.ErrNotFound {
			return nil // another closer has it
		}
		if err != nil {
			return err
		}
		if err := tx.DeleteTelegramBatch(ctx, b.ID); err != nil {
			return err
		}
		chatID, sender = b.ChatID, b.SenderUserID
		rows, err := s.st.TelegramBatchContributions(ctx, tx, b.ID)
		if err != nil {
			return err
		}
		var items []*store.TelegramContribution
		for i := range rows {
			if rows[i].State == "collecting" && rows[i].ItemID != nil {
				items = append(items, &rows[i])
			}
		}
		if len(items) == 0 {
			return nil
		}
		chat, err := tx.LockTelegramChat(ctx, b.ChatID)
		if err != nil {
			return err
		}
		if err := s.tgAlbumCaptions(ctx, tx, items); err != nil {
			return err
		}
		ids := make([]uuid.UUID, len(items))
		place := make([]placeItem, len(items))
		for i, c := range items {
			ids[i] = *c.ItemID
			row, err := s.st.GetItemRow(ctx, tx, *c.ItemID)
			if err != nil {
				return err
			}
			place[i] = placeItem{ItemID: row.ItemID, MediaID: row.MediaID, MotionMediaID: row.MotionMediaID, SendID: &sendID}
		}
		switch {
		case chat.FrameID != nil:
			if err := tx.CreateTelegramSend(ctx, sendID, b.SenderUserID, chat.ID, b.Label, b.Outside, ids); err != nil {
				return err
			}
			// Outside contributions are always moderated on a moderated frame (§8.7): their sender didn't choose them.
			m := &store.Membership{Role: "member"}
			if !b.Outside {
				if mm, err := s.st.GetMembership(ctx, tx, *chat.FrameID, b.SenderUserID); err == nil {
					m = mm
				}
			}
			created, _, err := s.placeOnFrames(ctx, tx, place, []uuid.UUID{*chat.FrameID}, map[uuid.UUID]*store.Membership{*chat.FrameID: m}, nil, false)
			if err != nil {
				return err
			}
			if err := ensureRenditionsFor(ctx, tx, created); err != nil {
				return err
			}
			if !b.Outside {
				if err := tx.TouchLastSent(ctx, []uuid.UUID{*chat.FrameID}, b.SenderUserID); err != nil {
					return err
				}
			}
		case chat.FeedID != nil:
			if err := tx.CreateTelegramSend(ctx, sendID, b.SenderUserID, chat.ID, b.Label, b.Outside, ids); err != nil {
				return err
			}
			if err := createPosts(ctx, s.st, tx, sendID, b.SenderUserID, []uuid.UUID{*chat.FeedID}, ids); err != nil {
				return err
			}
		default:
			for _, c := range items {
				c.State, c.SkipReason = "skipped", ptr("not_allowed")
				if err := s.st.PutTelegramContribution(ctx, tx, c); err != nil {
					return err
				}
			}
			return s.st.BumpTelegramCounts(ctx, tx, chat.ID, "skipped", len(items))
		}
		for _, c := range items {
			c.State, c.SendID = "sent", &sendID
			if err := s.st.PutTelegramContribution(ctx, tx, c); err != nil {
				return err
			}
		}
		if _, err := tx.Exec(ctx, `UPDATE telegram_contributors SET sent_count = sent_count + $2 WHERE id = $1`, b.ContributorID, len(items)); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE telegram_chats SET last_delivery_at = $2, updated_at = now() WHERE id = $1`, chat.ID, s.now()); err != nil {
			return err
		}
		sent = len(items)
		return s.st.BumpTelegramCounts(ctx, tx, chat.ID, "sent", len(items))
	})
	if err != nil || sent == 0 {
		return err
	}
	s.afterPersonalSend(ctx, sendID) // engagement
	s.publishSendUpdates(ctx, map[uuid.UUID]uuid.UUID{sendID: sender}, "")
	s.publishTelegramChat(ctx, chatID, nil)
	return nil
}

// tgAlbumCaptions applies an album's one caption to all its items (§8.5):
// when exactly one message of an album carries a caption, the others get it.
func (s *Server) tgAlbumCaptions(ctx context.Context, tx *store.Tx, items []*store.TelegramContribution) error {
	groups := map[string][]*store.TelegramContribution{}
	for _, c := range items {
		if c.MediaGroupID != nil {
			groups[*c.MediaGroupID] = append(groups[*c.MediaGroupID], c)
		}
	}
	for g := range groups {
		all, err := s.st.TelegramAlbum(ctx, tx, items[0].ChatID, g)
		if err != nil {
			return err
		}
		if err := s.tgApplyAlbumCaption(ctx, tx, all); err != nil {
			return err
		}
	}
	return nil
}

func (s *Server) tgApplyAlbumCaption(ctx context.Context, tx *store.Tx, all []store.TelegramContribution) error {
	var src *store.TelegramContribution
	n := 0
	for i := range all {
		if all[i].Caption != nil {
			n++
			src = &all[i]
		}
	}
	for i := range all {
		c := &all[i]
		if c.ItemID == nil || (c.State != "collecting" && c.State != "sent") {
			continue
		}
		var want *string
		switch {
		case c.Caption != nil:
			want = c.Caption
		case n == 1:
			want = src.Caption
		}
		ch, err := tx.SetUpstreamCaption(ctx, *c.ItemID, want)
		if err != nil {
			return err
		}
		if ch && c.State == "sent" {
			if err := s.touchItemPlacements(ctx, tx, *c.ItemID); err != nil {
				return err
			}
		}
		isSrc := n == 1 && src != nil && c.MessageID == src.MessageID
		if c.CaptionSource != isSrc {
			c.CaptionSource = isSrc
			if _, err := tx.Exec(ctx, `UPDATE telegram_contributions SET caption_source = $3 WHERE chat_id = $1 AND message_id = $2`, c.ChatID, c.MessageID, isSrc); err != nil {
				return err
			}
		}
	}
	return nil
}

// tgEdit follows caption edits of accepted messages (§8.3); replaced media is ignored.
func (s *Server) tgEdit(ctx context.Context, chat *store.TelegramChatView, r *telegram.Reduced) error {
	c, err := s.st.GetTelegramContribution(ctx, s.st.Q(), chat.ID, r.MessageID)
	if err == store.ErrNotFound {
		return nil
	}
	if err != nil || c.ItemID == nil || (c.State != "collecting" && c.State != "sent") {
		return err
	}
	var caption *string
	if cap := telegram.Caption(r.Caption); cap != "" {
		caption = &cap
	}
	return s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.Exec(ctx, `UPDATE telegram_contributions SET caption = $3 WHERE chat_id = $1 AND message_id = $2`, chat.ID, r.MessageID, caption); err != nil {
			return err
		}
		if c.MediaGroupID != nil {
			all, err := s.st.TelegramAlbum(ctx, tx, chat.ID, *c.MediaGroupID)
			if err != nil {
				return err
			}
			return s.tgApplyAlbumCaption(ctx, tx, all)
		}
		ch, err := tx.SetUpstreamCaption(ctx, *c.ItemID, caption)
		if err != nil || !ch {
			return err
		}
		return s.touchItemPlacements(ctx, tx, *c.ItemID)
	})
}

// tgReasonArgs fills the placeholders of a refusal text.
func (s *Server) tgReasonArgs(reason string, maxUpload int64, maxVideo int) map[string]string {
	switch reason {
	case "too_large_for_bot":
		return map[string]string{"limit": strconv.FormatInt(s.tg.maxDown>>20, 10) + " MB"}
	case "too_large":
		gb := float64(maxUpload) / (1 << 30)
		if gb >= 1 {
			return map[string]string{"limit": strings.TrimSuffix(strconv.FormatFloat(gb, 'f', 1, 64), ".0") + " GB"}
		}
		return map[string]string{"limit": strconv.FormatInt(maxUpload>>20, 10) + " MB"}
	case "too_long":
		return map[string]string{"minutes": strconv.Itoa(max(1, (maxVideo+59)/60))}
	}
	return nil
}
