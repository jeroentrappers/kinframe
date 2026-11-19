package api

import (
	"context"
	"strconv"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/i18n"
	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/telegram"
)

// Telegram chats (docs/spec/telegram.md §5, §6, §11, §12): what happens to a
// chat's updates other than media, codes, rights and disconnecting.

// tgTarget is the frame or feed of a chat or code.
type tgTarget struct {
	frameID, feedID *uuid.UUID
}

// tgCanManage: frame managers (owner, admin, change_settings) or the feed's owner and admins (§4).
func (s *Server) tgCanManage(ctx context.Context, userID uuid.UUID, t tgTarget) (bool, error) {
	if t.frameID != nil {
		m, err := s.st.GetMembership(ctx, s.st.Q(), *t.frameID, userID)
		if err == store.ErrNotFound {
			return false, nil
		}
		if err != nil {
			return false, err
		}
		return m.Effective().ChangeSettings, nil
	}
	if t.feedID != nil {
		v, err := s.st.GetFeedView(ctx, s.st.Q(), userID, *t.feedID)
		if err == store.ErrNotFound || (err == nil && v.DeletedAt != nil) {
			return false, nil
		}
		if err != nil {
			return false, err
		}
		return v.IsAdmin(), nil
	}
	return false, nil
}

// tgCanSend: a frame member with send, any member of the feed (§4.3).
func (s *Server) tgCanSend(ctx context.Context, userID uuid.UUID, t tgTarget) (bool, *store.Membership, error) {
	if t.frameID != nil {
		m, err := s.st.GetMembership(ctx, s.st.Q(), *t.frameID, userID)
		if err == store.ErrNotFound {
			return false, nil, nil
		}
		if err != nil {
			return false, nil, err
		}
		return m.Effective().Send, m, nil
	}
	v, err := s.st.GetFeedView(ctx, s.st.Q(), userID, *t.feedID)
	if err == store.ErrNotFound {
		return false, nil, nil
	}
	if err != nil {
		return false, nil, err
	}
	return v.Role != "" && v.DeletedAt == nil, nil, nil
}

func chatTarget(c *store.TelegramChat) tgTarget {
	return tgTarget{frameID: c.FrameID, feedID: c.FeedID}
}

// tgTargetName is the frame's or feed's name for messages.
func tgTargetName(v *store.TelegramChatView) string {
	if v.FrameName != nil {
		return *v.FrameName
	}
	if v.FeedName != nil {
		return *v.FeedName
	}
	return "kinframe"
}

func tgOwnerName(v *store.TelegramChatView) string {
	if v.OwnerName != nil {
		return *v.OwnerName
	}
	return "kinframe"
}

// tgGroupLang is the language of messages to the whole chat: the owner's (§11).
func tgGroupLang(v *store.TelegramChatView) string {
	if v != nil && v.OwnerLanguage != nil {
		return i18n.Pick(*v.OwnerLanguage)
	}
	return i18n.Default
}

// tgLang is the language of a message to one person: their kinframe locale
// when linked, else their Telegram language, else the owner's (§11).
func (s *Server) tgLang(ctx context.Context, v *store.TelegramChatView, p *telegram.Person) string {
	if p != nil {
		if l, err := s.st.TelegramLinkByTg(ctx, s.st.Q(), p.ID); err == nil && l.UserLocale != nil && *l.UserLocale != "" {
			return i18n.Pick(*l.UserLocale)
		}
		if p.Lang != "" {
			if l := i18n.Normalize(telegram.Language(p.Lang)); l != "" {
				return l
			}
		}
	}
	return tgGroupLang(v)
}

func tgT(lang, key string, args map[string]string) string { return i18n.T(lang, "telegram_"+key, args) }

// tgSeesPhotos: private chats always; groups when the bot is an admin, or
// privacy mode is off and the bot joined after it was turned off (§6).
func tgSeesPhotos(c *store.TelegramChat, bot *store.TelegramBot) bool {
	if c.ChatKind == "private" || c.BotStatus == "administrator" {
		return true
	}
	return bot != nil && bot.CanReadAll && bot.PrivacyOffSince != nil && !c.BotJoinedAt.Before(*bot.PrivacyOffSince)
}

// telegramHandle processes one reduced update (§8.2).
func (s *Server) telegramHandle(ctx context.Context, r *telegram.Reduced) error {
	var chat *store.TelegramChatView
	lookup := r.ChatID
	if r.MigrateFrom != 0 {
		lookup = r.MigrateFrom
	}
	if v, err := s.st.LiveTelegramChat(ctx, s.st.Q(), lookup); err == nil {
		chat = v
	} else if err != store.ErrNotFound {
		return err
	}
	switch {
	case r.Kind == telegram.KindMember:
		return s.tgMember(ctx, chat, r)
	case r.MigrateTo != 0:
		return s.tgMigrate(ctx, chat, r.ChatID, r.MigrateTo)
	case r.MigrateFrom != 0:
		return s.tgMigrate(ctx, chat, r.MigrateFrom, r.ChatID)
	case chat != nil && r.NewTitle != "":
		chat.Title = r.NewTitle
		if err := s.st.SaveTelegramChat(ctx, s.st.Q(), &chat.TelegramChat); err != nil {
			return err
		}
		s.publishTelegramChat(ctx, chat.ID, nil)
		return nil
	case chat != nil && r.TopicName != "":
		if chat.Topics == nil {
			chat.Topics = map[string]string{}
		}
		chat.Topics[strconv.FormatInt(r.ThreadID, 10)] = r.TopicName
		chat.IsForum = true
		return s.st.SaveTelegramChat(ctx, s.st.Q(), &chat.TelegramChat)
	case r.BotLeft:
		if chat != nil {
			return s.tgEnd(ctx, chat, "bot_removed", nil)
		}
		return s.st.DeleteTelegramStray(ctx, r.ChatID)
	case r.Command != "":
		return s.tgCommand(ctx, chat, r)
	case r.Media != nil && r.Kind == telegram.KindEdited:
		if chat == nil {
			return nil
		}
		return s.tgEdit(ctx, chat, r)
	case r.Media != nil:
		return s.tgMedia(ctx, chat, r)
	}
	return nil
}

func memberIn(status string) bool {
	switch status {
	case "member", "administrator", "creator", "restricted":
		return true
	}
	return false
}

// tgMember follows the bot's own membership (§5.2, §6, §12).
func (s *Server) tgMember(ctx context.Context, chat *store.TelegramChatView, r *telegram.Reduced) error {
	m := r.Member
	now := s.now()
	if r.ChatType == "private" {
		// A private chat: the user blocked (kicked) or restarted the bot.
		uid, err := s.st.SetTelegramCanMessage(ctx, r.ChatID, memberIn(m.New))
		if err != nil {
			return err
		}
		if uid != nil {
			s.publishTelegramLink(ctx, *uid)
		}
		if chat != nil && !memberIn(m.New) {
			return s.tgEnd(ctx, chat, "bot_blocked", nil)
		}
		return nil
	}
	if !memberIn(m.New) {
		if err := s.st.DeleteTelegramStray(ctx, r.ChatID); err != nil {
			return err
		}
		if chat != nil {
			return s.tgEnd(ctx, chat, "bot_removed", nil)
		}
		return nil
	}
	if chat == nil {
		if !memberIn(m.Old) {
			// Added by hand: explain once (unless a code follows at once) and leave after 10 minutes (§8.2).
			return s.st.PutTelegramStray(ctx, r.ChatID, now.Add(20*time.Second), now.Add(10*time.Minute))
		}
		return nil
	}
	c := &chat.TelegramChat
	if !memberIn(m.Old) {
		c.BotJoinedAt = now
	}
	c.BotStatus = "member"
	if m.New == "administrator" || m.New == "creator" {
		c.BotStatus = "administrator"
	}
	if r.ChatTitle != "" {
		c.Title = r.ChatTitle
	}
	return s.tgPrivacy(ctx, chat)
}

// tgPrivacy re-evaluates whether the bot sees photos and moves the chat in
// or out of needs_attention (privacy_mode).
func (s *Server) tgPrivacy(ctx context.Context, chat *store.TelegramChatView) error {
	c := &chat.TelegramChat
	sees := tgSeesPhotos(c, s.telegramBot())
	push := false
	ready := false
	switch {
	case !sees && c.Status == "active":
		c.Status, c.ErrorCode = "needs_attention", ptr("privacy_mode")
		push = true
	case sees && c.Status == "needs_attention" && c.ErrorCode != nil && *c.ErrorCode == "privacy_mode":
		c.Status, c.ErrorCode = "active", nil
		if c.ReadySentAt == nil {
			now := s.now()
			c.ReadySentAt = &now
			ready = true
		}
	}
	if err := s.st.SaveTelegramChat(ctx, s.st.Q(), c); err != nil {
		return err
	}
	if push {
		s.telegramAttention(ctx, c.ID, "privacy_mode")
	}
	if ready {
		s.tgSay(chat, tgT(tgGroupLang(chat), "ready", nil), telegram.SendOptions{Silent: true})
	}
	s.publishTelegramChat(ctx, c.ID, nil)
	return nil
}

// tgMigrate follows a basic group's upgrade to a supergroup (§12).
func (s *Server) tgMigrate(ctx context.Context, chat *store.TelegramChatView, from, to int64) error {
	if chat == nil || chat.TgChatID == to {
		return nil
	}
	return s.st.InTx(ctx, func(tx *store.Tx) error {
		c, err := tx.LockTelegramChat(ctx, chat.ID)
		if err != nil {
			return err
		}
		if c.TgChatID == to {
			return nil
		}
		c.PreviousChatID, c.TgChatID = &from, to
		if err := s.st.CloseTelegramContributions(ctx, tx, c.ID); err != nil {
			return err
		}
		return s.st.SaveTelegramChat(ctx, tx, c)
	})
}

// tgMigrated handles a 400 answer carrying migrate_to_chat_id.
func (s *Server) tgMigrated(old, to int64) {
	ctx := context.Background()
	if v, err := s.st.LiveTelegramChat(ctx, s.st.Q(), old); err == nil {
		if err := s.tgMigrate(ctx, v, old, to); err != nil {
			s.log.Warn("telegram: migration", "chat", old, "err", err)
		}
	}
}

// tgEnd disconnects a chat (§5.2): open batches are sent first, invites of
// the chat are revoked, the owner hears about removals.
func (s *Server) tgEnd(ctx context.Context, chat *store.TelegramChatView, reason string, by *uuid.UUID) error {
	if err := s.telegramCloseDue(ctx, &chat.ID); err != nil {
		s.log.Warn("telegram: flush batches", "chat", chat.ID, "err", err)
	}
	now := s.now()
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		c, err := tx.LockTelegramChat(ctx, chat.ID)
		if err != nil {
			return err
		}
		if c.Status == "disconnected" {
			return nil
		}
		c.Status, c.EndedReason, c.EndedByUserID, c.EndedAt, c.ErrorCode = "disconnected", &reason, by, &now, nil
		if err := s.st.SaveTelegramChat(ctx, tx, c); err != nil {
			return err
		}
		chat.TelegramChat = *c
		return s.tgRevokeInvites(ctx, tx, c.ID, nil)
	})
	if err != nil {
		return err
	}
	switch reason {
	case "bot_removed", "chat_gone", "bot_blocked":
		s.telegramAttention(ctx, chat.ID, reason)
	}
	s.publishTelegramChat(ctx, chat.ID, nil)
	return nil
}

// tgRevokeInvites revokes the chat's join links (all, or one contributor's)
// and their join codes.
func (s *Server) tgRevokeInvites(ctx context.Context, tx *store.Tx, chatID uuid.UUID, contributor *uuid.UUID) error {
	if _, err := tx.Exec(ctx, `UPDATE invites SET revoked_at = now() WHERE via = 'telegram' AND revoked_at IS NULL
		AND via_ref IN (SELECT id::text FROM telegram_contributors WHERE chat_id = $1 AND ($2::uuid IS NULL OR id = $2))`, chatID, contributor); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE feed_invites SET revoked_at = now() WHERE via = 'telegram' AND revoked_at IS NULL
		AND via_ref IN (SELECT id::text FROM telegram_contributors WHERE chat_id = $1 AND ($2::uuid IS NULL OR id = $2))`, chatID, contributor); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `UPDATE telegram_codes SET revoked_at = now() WHERE purpose = 'join' AND for_chat_id = $1 AND revoked_at IS NULL
		AND ($2::uuid IS NULL OR for_tg_user_id = (SELECT tg_user_id FROM telegram_contributors WHERE id = $2))`, chatID, contributor)
	return err
}

// tgDisconnect is a disconnect by a person (app or /stop): goodbye, then the
// bot leaves the group (§10.4).
func (s *Server) tgDisconnect(ctx context.Context, chat *store.TelegramChatView, reason string, by uuid.UUID) error {
	if err := s.tgEnd(ctx, chat, reason, &by); err != nil {
		return err
	}
	s.tgGoodbye(chat)
	return nil
}

func (s *Server) tgGoodbye(chat *store.TelegramChatView) {
	key := "goodbye"
	if chat.ChatKind == "private" {
		key = "goodbye_private"
	}
	s.tgSay(chat, tgT(tgGroupLang(chat), key, map[string]string{"target": tgTargetName(chat)}), telegram.SendOptions{Silent: true})
	if chat.ChatKind == "group" {
		id, tg := chat.TgChatID, s.tg
		tg.out.Add(telegram.Job{ChatID: id, Group: true, Kind: telegram.OutOther, Run: func(ctx context.Context) error {
			err := tg.client.LeaveChat(ctx, id)
			if e := telegram.AsError(err); e != nil && e.Code != 429 {
				s.log.Info("telegram: leaveChat", "chat", id, "err", e.Description)
			}
			return err
		}})
	}
}

// telegramFarewells says goodbye in chats whose frame or feed was deleted.
func (s *Server) telegramFarewells(ctx context.Context) {
	vs, err := s.st.FarewellTelegramChats(ctx)
	if err != nil {
		return
	}
	for i := range vs {
		v := &vs[i]
		v.FarewellDue = false
		if err := s.st.SaveTelegramChat(ctx, s.st.Q(), &v.TelegramChat); err != nil {
			continue
		}
		s.tgGoodbye(v)
		s.publishTelegramChat(ctx, v.ID, nil)
	}
}

// telegramStrays explains once in groups added without a code, then leaves.
func (s *Server) telegramStrays(ctx context.Context) {
	now := s.now()
	due, err := s.st.DueTelegramStrays(ctx, now)
	if err != nil {
		return
	}
	for _, st := range due {
		if _, err := s.st.LiveTelegramChat(ctx, s.st.Q(), st.TgChatID); err == nil {
			_ = s.st.DeleteTelegramStray(ctx, st.TgChatID)
			continue
		}
		chat := &store.TelegramChatView{TelegramChat: store.TelegramChat{TgChatID: st.TgChatID, ChatKind: "group"}}
		if !st.LeaveAt.After(now) {
			_ = s.st.DeleteTelegramStray(ctx, st.TgChatID)
			id, tg := st.TgChatID, s.tg
			tg.out.Add(telegram.Job{ChatID: id, Group: true, Kind: telegram.OutOther, Run: func(ctx context.Context) error {
				return tg.client.LeaveChat(ctx, id)
			}})
			continue
		}
		if st.NoticeAt != nil {
			_ = s.st.TelegramStrayNoticed(ctx, st.TgChatID)
			s.tgSay(chat, tgT(i18n.Default, "stray", nil), telegram.SendOptions{Silent: true, Buttons: s.tgAppButtons(i18n.Default)})
		}
	}
}

// tgOwnerOK re-checks the chat's owner (§4.4): an owner who lost the right
// to connect the chat is replaced by the frame's (feed's) owner.
func (s *Server) tgOwnerOK(ctx context.Context, chat *store.TelegramChatView) error {
	t := chatTarget(&chat.TelegramChat)
	if chat.OwnerUserID != nil {
		u, err := s.st.GetUser(ctx, s.st.Q(), *chat.OwnerUserID)
		if err == nil && u.DeletedAt == nil {
			ok, err := s.tgCanManage(ctx, *chat.OwnerUserID, t)
			if err != nil || ok {
				return err
			}
		} else if err != nil && err != store.ErrNotFound {
			return err
		}
	}
	owner, err := s.st.TelegramTargetOwner(ctx, t.frameID, t.feedID)
	if err != nil {
		return err
	}
	if chat.OwnerUserID != nil && *chat.OwnerUserID == owner {
		return nil
	}
	chat.OwnerUserID = &owner
	if err := s.st.SaveTelegramChat(ctx, s.st.Q(), &chat.TelegramChat); err != nil {
		return err
	}
	if v, err := s.st.GetTelegramChat(ctx, chat.ID); err == nil {
		*chat = *v
	}
	s.telegramAttention(ctx, chat.ID, "owner_changed")
	s.publishTelegramChat(ctx, chat.ID, nil)
	return nil
}

// ---------------------------------------------------------------- commands

// tgCommand answers /start, /help, /status, /stop, /unlink (§11). Command
// replies are normal (not silent) replies to the command.
func (s *Server) tgCommand(ctx context.Context, chat *store.TelegramChatView, r *telegram.Reduced) error {
	lang := s.tgLang(ctx, chat, r.From)
	reply := func(text string, buttons [][]telegram.InlineButton) {
		v := chat
		if v == nil {
			v = &store.TelegramChatView{TelegramChat: store.TelegramChat{TgChatID: r.ChatID, ChatKind: tgKind(r)}}
		}
		s.tgSay(v, text, telegram.SendOptions{ReplyTo: r.MessageID, ThreadID: r.ThreadID, Buttons: buttons})
	}
	switch r.Command {
	case "start":
		if r.CommandArg != "" && r.CommandArg != "help" {
			return s.tgRedeem(ctx, chat, r)
		}
		if r.ChatType == "private" && r.From != nil {
			if l, err := s.st.TelegramLinkByTg(ctx, s.st.Q(), r.From.ID); err == nil && chat == nil {
				reply(tgT(lang, "status_linked", map[string]string{"name": l.UserName})+"\n\n"+s.tgHelpText(lang, chat), s.tgAppButtons(lang))
				return nil
			}
		}
		reply(s.tgHelpText(lang, chat), s.tgAppButtons(lang))
	case "help":
		reply(s.tgHelpText(lang, chat), s.tgAppButtons(lang))
	case "status":
		reply(s.tgStatusText(ctx, lang, chat, r.From), nil)
	case "stop":
		if chat == nil {
			reply(s.tgHelpText(lang, nil), s.tgAppButtons(lang))
			return nil
		}
		if r.From != nil {
			if l, err := s.st.TelegramLinkByTg(ctx, s.st.Q(), r.From.ID); err == nil {
				if ok, err := s.tgCanManage(ctx, l.UserID, chatTarget(&chat.TelegramChat)); err != nil {
					return err
				} else if ok {
					return s.tgDisconnect(ctx, chat, "stopped", l.UserID)
				}
			}
		}
		reply(tgT(lang, "stop_denied", map[string]string{"target": tgTargetName(chat)}), nil)
	case "unlink":
		if r.ChatType != "private" || r.From == nil {
			return nil
		}
		l, err := s.st.TelegramLinkByTg(ctx, s.st.Q(), r.From.ID)
		if err != nil {
			reply(tgT(lang, "not_linked", nil), nil)
			return nil
		}
		if _, err := s.st.UnlinkTelegram(ctx, l.UserID); err != nil {
			return err
		}
		s.publishTelegramLink(ctx, l.UserID)
		reply(tgT(lang, "unlinked", nil), nil)
	}
	return nil
}

func tgKind(r *telegram.Reduced) string {
	if r.ChatType == "private" {
		return "private"
	}
	return "group"
}

func (s *Server) tgHelpText(lang string, chat *store.TelegramChatView) string {
	limit := strconv.FormatInt(s.tg.maxDown>>20, 10) + " MB"
	if chat == nil {
		return tgT(lang, "help_unbound", nil) + "\n\n" + tgT(lang, "help", map[string]string{"limit": limit})
	}
	return tgT(lang, "help", map[string]string{"limit": limit})
}

func (s *Server) tgStatusText(ctx context.Context, lang string, chat *store.TelegramChatView, from *telegram.Person) string {
	if chat == nil {
		return tgT(lang, "help_unbound", nil)
	}
	last := tgT(lang, "status_never", nil)
	if chat.LastDeliveryAt != nil {
		last = chat.LastDeliveryAt.UTC().Format("2006-01-02 15:04") + " UTC"
	}
	key := "status"
	if chat.ChatKind == "private" {
		key = "status_private"
	}
	out := tgT(lang, key, map[string]string{"target": tgTargetName(chat), "owner": tgOwnerName(chat), "last": last})
	if chat.Status == "paused" {
		out += "\n" + tgT(lang, "status_paused", nil)
	}
	// Problems only for a linked manager (§11).
	if from != nil && chat.Status == "needs_attention" {
		if l, err := s.st.TelegramLinkByTg(ctx, s.st.Q(), from.ID); err == nil {
			if ok, _ := s.tgCanManage(ctx, l.UserID, chatTarget(&chat.TelegramChat)); ok && chat.ErrorCode != nil {
				out += "\n" + tgT(lang, "status_"+*chat.ErrorCode, nil)
			}
		}
	}
	return out
}

// ---------------------------------------------------------------- codes

// tgRedeem handles /start <code> (§5.1).
func (s *Server) tgRedeem(ctx context.Context, chat *store.TelegramChatView, r *telegram.Reduced) error {
	lang := s.tgLang(ctx, chat, r.From)
	here := chat
	if here == nil {
		here = &store.TelegramChatView{TelegramChat: store.TelegramChat{TgChatID: r.ChatID, ChatKind: tgKind(r)}}
	}
	reply := func(key string, args map[string]string) {
		s.tgSay(here, tgT(lang, key, args), telegram.SendOptions{ReplyTo: r.MessageID, ThreadID: r.ThreadID})
	}
	arg := r.CommandArg
	var code *store.TelegramCode
	if telegram.ValidCode(arg) {
		c, err := s.st.TelegramCodeByHash(ctx, s.st.Q(), telegram.HashCode(arg))
		if err != nil && err != store.ErrNotFound {
			return err
		}
		code = c
	}
	if code == nil {
		if ok, _ := s.tg.wrongCode.Allow(strconv.FormatInt(r.ChatID, 10)); ok {
			reply("code_expired", nil)
		}
		return nil
	}
	if code.Purpose == "join" {
		return s.tgRedeemJoin(ctx, here, code, r, lang)
	}
	now := s.now()
	fail := func(errCode, key string, args map[string]string) error {
		if err := s.st.SetTelegramCodeError(ctx, s.st.Q(), code.ID, errCode); err != nil {
			return err
		}
		s.publishTelegramCode(ctx, code.ID)
		reply(key, args)
		return nil
	}
	switch {
	case code.UsedAt != nil:
		reply("code_used", nil)
		return nil
	case code.RevokedAt != nil || !now.Before(code.ExpiresAt):
		return fail("code_expired", "code_expired", nil)
	case r.From == nil || r.SenderChat != nil:
		return nil // anonymous admins can't redeem: there is no one to link or bind to
	}
	if code.Purpose == "link" {
		if r.ChatType != "private" {
			return fail("wrong_chat_kind", "wrong_kind_private", nil)
		}
		return s.tgLink(ctx, code, r, lang, reply, fail)
	}
	// connect
	kind := derefStr(code.ChatKind)
	switch {
	case kind == "group" && r.ChatType == "private":
		return fail("wrong_chat_kind", "wrong_kind_group", nil)
	case kind == "private" && r.ChatType != "private":
		return fail("wrong_chat_kind", "wrong_kind_private", nil)
	}
	t := tgTarget{frameID: code.FrameID, feedID: code.FeedID}
	targetName := s.tgTargetNameOf(ctx, t)
	if chat != nil {
		return fail("already_connected", "already_connected", map[string]string{"target": tgTargetName(chat)})
	}
	if code.CreatedBy == nil {
		return fail("missing_permission", "missing_permission", map[string]string{"target": targetName})
	}
	creator := *code.CreatedBy
	if ok, err := s.tgCanManage(ctx, creator, t); err != nil {
		return err
	} else if !ok {
		return fail("missing_permission", "missing_permission", map[string]string{"target": targetName})
	}
	limits := s.Limits(ctx)
	nTarget, nOwned, err := s.st.CountTelegramChats(ctx, s.st.Q(), t.frameID, t.feedID, creator)
	if err != nil {
		return err
	}
	if nTarget >= limits.MaxTelegramChatsPerTarget || nOwned >= limits.MaxTelegramChatsPerUser {
		return fail("limit_reached", "limit_reached", map[string]string{"target": targetName, "count": strconv.Itoa(limits.MaxTelegramChatsPerTarget)})
	}
	if code.LinkAccount {
		ok, err := s.st.LinkTelegram(ctx, s.st.Q(), store.TelegramLink{TgUserID: r.From.ID, UserID: creator, DisplayName: r.From.Name,
			Username: strOrNil(r.From.Username), LanguageCode: strOrNil(r.From.Lang)})
		if err != nil {
			return err
		}
		if !ok {
			return fail("already_linked", "already_linked", nil)
		}
		s.publishTelegramLink(ctx, creator)
	}
	bot := s.telegramBot()
	c := &store.TelegramChat{ID: store.NewID(), TgChatID: r.ChatID, BotID: bot.BotID, ChatKind: kind, IsForum: r.IsForum,
		Title: r.ChatTitle, OwnerUserID: &creator, FrameID: t.frameID, FeedID: t.feedID, Status: "active", BotStatus: "member",
		BotJoinedAt: now, WelcomeSentAt: &now}
	if kind == "private" {
		c.PrivateTgUserID = &r.From.ID
		c.Title = r.From.Name
	} else if st := s.tgBotStatus(ctx, r.ChatID); st == "administrator" {
		c.BotStatus = st
	}
	if c.Title == "" {
		c.Title = "Telegram"
	}
	seesPhotos := tgSeesPhotos(c, bot)
	if !seesPhotos {
		c.Status, c.ErrorCode = "needs_attention", ptr("privacy_mode")
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if _, err := tx.LockTelegramCode(ctx, code.ID); err != nil {
			return err
		}
		if err := tx.CreateTelegramChat(ctx, c); err != nil {
			return err
		}
		return tx.UseTelegramCode(ctx, code.ID, &c.ID, now)
	})
	if err != nil {
		if db.IsUniqueViolation(err, "telegram_chats_live_idx") {
			return fail("already_connected", "already_connected", map[string]string{"target": targetName})
		}
		return err
	}
	_ = s.st.DeleteTelegramStray(ctx, r.ChatID)
	v, err := s.st.GetTelegramChat(ctx, c.ID)
	if err != nil {
		return err
	}
	s.tgWelcome(v, seesPhotos, r.ThreadID)
	if !seesPhotos {
		s.telegramAttention(ctx, c.ID, "privacy_mode")
	}
	s.publishTelegramCode(ctx, code.ID)
	s.publishTelegramChat(ctx, c.ID, nil)
	return nil
}

func (s *Server) tgTargetNameOf(ctx context.Context, t tgTarget) string {
	if t.frameID != nil {
		if f, err := s.st.GetFrame(ctx, s.st.Q(), *t.frameID); err == nil {
			return f.Name
		}
	}
	if t.feedID != nil {
		if f, err := s.st.GetFeed(ctx, s.st.Q(), *t.feedID); err == nil {
			return f.Name
		}
	}
	return "kinframe"
}

// tgBotStatus asks Telegram for the bot's status in a chat ("" when unknown).
func (s *Server) tgBotStatus(ctx context.Context, chatID int64) string {
	bot := s.telegramBot()
	if bot == nil {
		return ""
	}
	cctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	var m telegram.ChatMember
	if err := s.tg.client.Call(cctx, "getChatMember", map[string]any{"chat_id": chatID, "user_id": bot.BotID}, &m); err != nil {
		return ""
	}
	if m.Status == "administrator" || m.Status == "creator" {
		return "administrator"
	}
	return m.Status
}

// tgLink handles a link code in a private chat.
func (s *Server) tgLink(ctx context.Context, code *store.TelegramCode, r *telegram.Reduced, lang string,
	reply func(string, map[string]string), fail func(string, string, map[string]string) error) error {
	if code.CreatedBy == nil {
		return fail("code_expired", "code_expired", nil)
	}
	ok, err := s.st.LinkTelegram(ctx, s.st.Q(), store.TelegramLink{TgUserID: r.From.ID, UserID: *code.CreatedBy, DisplayName: r.From.Name,
		Username: strOrNil(r.From.Username), LanguageCode: strOrNil(r.From.Lang)})
	if err != nil {
		return err
	}
	if !ok {
		return fail("already_linked", "already_linked", nil)
	}
	if err := s.st.InTx(ctx, func(tx *store.Tx) error { return tx.UseTelegramCode(ctx, code.ID, nil, s.now()) }); err != nil {
		return err
	}
	name := ""
	if u, err := s.st.GetUser(ctx, s.st.Q(), *code.CreatedBy); err == nil {
		name = u.DisplayName
	}
	reply("linked", map[string]string{"name": name})
	s.publishTelegramCode(ctx, code.ID)
	s.publishTelegramLink(ctx, *code.CreatedBy)
	return nil
}
