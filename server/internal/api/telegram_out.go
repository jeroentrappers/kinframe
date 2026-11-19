package api

import (
	"context"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/i18n"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/telegram"
)

// What the bot says and does in chats (docs/spec/telegram.md §10): replies,
// reactions, the app promotion with its join link, frames' reactions and
// quick replies mirrored back, and the owner's telegram_attention push.

// Public kinframe listings, when the operator configured no store links (§10.3).
const (
	tgAppStoreURL   = "https://apps.apple.com/app/id6833055734"
	tgGooglePlayURL = "https://play.google.com/store/apps/details?id=app.kinframe.family"
)

// tgSay queues a message to a chat. Failures that tell something about the
// chat are recorded: no right to post (warning cannot_post), the user blocked
// the bot, the chat is gone, the group became a supergroup (§12).
func (s *Server) tgSay(chat *store.TelegramChatView, text string, o telegram.SendOptions) {
	if s.tg == nil {
		return
	}
	id, tgID, group := chat.ID, chat.TgChatID, chat.ChatKind == "group"
	if chat.ID != uuid.Nil && chat.HasWarning("cannot_post") {
		return // the bot may not post there; media still flows
	}
	client := s.tg.client
	run := func(ctx context.Context) error {
		_, err := client.SendMessage(ctx, tgID, text, o)
		e := telegram.AsError(err)
		switch {
		case e == nil || e.Code == 429:
			return err
		case e.MigrateTo != 0:
			s.tgMigrated(tgID, e.MigrateTo)
			tgID = e.MigrateTo
			_, err = client.SendMessage(ctx, tgID, text, o)
		case id == uuid.Nil:
		case e.Blocked() || e.ChatGone():
			go s.tgGone(id, e.Blocked())
		case e.Code == 403 || (e.Code == 400 && (e.Has("not enough rights") || e.Has("CHAT_WRITE_FORBIDDEN"))):
			go s.tgWarn(id, "cannot_post", true)
		case e.Has("thread not found") && o.ThreadID != 0:
			o.ThreadID = 0
			_, err = client.SendMessage(ctx, tgID, text, o)
		}
		if err != nil && telegram.AsError(err) != nil && telegram.AsError(err).Code != 429 {
			s.log.Info("telegram: sendMessage", "chat", tgID, "err", telegram.AsError(err).Description)
			return nil
		}
		return err
	}
	s.tg.out.Add(telegram.Job{ChatID: tgID, Group: group, Kind: telegram.OutMessage, Run: run})
}

// tgGone ends a chat after an answer saying it can't be reached.
func (s *Server) tgGone(chatID uuid.UUID, blocked bool) {
	ctx := context.Background()
	v, err := s.st.GetTelegramChat(ctx, chatID)
	if err != nil || v.Status == "disconnected" {
		return
	}
	reason := "chat_gone"
	if blocked {
		reason = "bot_blocked"
		if v.PrivateTgUserID != nil {
			if uid, _ := s.st.SetTelegramCanMessage(ctx, *v.PrivateTgUserID, false); uid != nil {
				s.publishTelegramLink(ctx, *uid)
			}
		}
	}
	if err := s.tgEnd(ctx, v, reason, nil); err != nil {
		s.log.Warn("telegram: end chat", "chat", chatID, "err", err)
	}
}

// tgWarn sets or clears a warning (cannot_post, reactions_off).
func (s *Server) tgWarn(chatID uuid.UUID, warning string, on bool) {
	ctx := context.Background()
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		c, err := tx.LockTelegramChat(ctx, chatID)
		if err != nil || c.HasWarning(warning) == on {
			return err
		}
		if on {
			c.Warnings = append(c.Warnings, warning)
		} else {
			var keep []string
			for _, w := range c.Warnings {
				if w != warning {
					keep = append(keep, w)
				}
			}
			c.Warnings = keep
		}
		return s.st.SaveTelegramChat(ctx, tx, c)
	})
	if err == nil {
		s.publishTelegramChat(ctx, chatID, nil)
	}
}

// tgReact sets the bot's reaction on a message; a group that restricts
// reactions gets warning reactions_off and no more tries (§10.2).
func (s *Server) tgReact(chat *store.TelegramChatView, msgID int64, emoji string) {
	if chat.HasWarning("reactions_off") {
		return
	}
	id, tgID, client := chat.ID, chat.TgChatID, s.tg.client
	s.tg.out.Add(telegram.Job{ChatID: tgID, Group: chat.ChatKind == "group", Kind: telegram.OutReaction, Run: func(ctx context.Context) error {
		err := client.SetMessageReaction(ctx, tgID, msgID, emoji)
		e := telegram.AsError(err)
		switch {
		case e == nil:
			if err := s.st.SetTelegramReactionShown(ctx, id, msgID, emoji); err != nil {
				s.log.Warn("telegram: record reaction", "chat", tgID, "err", err)
			}
			return nil
		case e.Code == 429:
			return err
		case e.Has("REACTION_INVALID") || e.Has("REACTIONS_TOO_MANY") || e.Has("reactions are disabled"):
			go s.tgWarn(id, "reactions_off", true)
		case e.ChatGone():
			go s.tgGone(id, false)
		default:
			s.log.Info("telegram: setMessageReaction", "chat", tgID, "err", e.Description)
		}
		return nil
	}})
}

// tgConfirm puts 👍 on an accepted message; an album gets it once, on its
// first message (§10.2).
func (s *Server) tgConfirm(ctx context.Context, chat *store.TelegramChatView, c *store.TelegramContribution) {
	if c.MediaGroupID != nil {
		album, err := s.st.TelegramAlbum(ctx, s.st.Q(), chat.ID, *c.MediaGroupID)
		if err != nil {
			return
		}
		for _, a := range album {
			if a.State == "collecting" && a.ItemID == nil {
				continue // waiting for a download
			}
			if a.State == "collecting" || a.State == "sent" || a.State == "duplicate" {
				if a.MessageID != c.MessageID {
					return // the album's first message carries it
				}
				break
			}
		}
	}
	s.tgReact(chat, c.MessageID, telegram.EmojiConfirm)
}

// tgAppButtons are the links to get the app: stores and the web (§10.3).
func (s *Server) tgAppButtons(lang string) [][]telegram.InlineButton {
	ios, android := tgAppStoreURL, tgGooglePlayURL
	if p, ok := s.cfg.AppVersions["ios"]; ok && p.StoreURL != "" {
		ios = p.StoreURL
	}
	if p, ok := s.cfg.AppVersions["android"]; ok && p.StoreURL != "" {
		android = p.StoreURL
	}
	row := []telegram.InlineButton{{Text: "App Store", URL: ios}, {Text: "Google Play", URL: android}}
	if strings.HasPrefix(s.cfg.PublicURL, "https://") {
		row = append(row, telegram.InlineButton{Text: tgT(lang, "button_web", nil), URL: s.cfg.PublicURL})
	}
	return [][]telegram.InlineButton{row}
}

// tgAppURL is where "Get the app" leads from a group: the server's web page
// (it offers the stores), else the Play Store listing.
func (s *Server) tgAppURL() string {
	if strings.HasPrefix(s.cfg.PublicURL, "https://") {
		return s.cfg.PublicURL
	}
	if p, ok := s.cfg.AppVersions["android"]; ok && p.StoreURL != "" {
		return p.StoreURL
	}
	return tgGooglePlayURL
}

// tgBotLink is a deep link into a private chat with the bot.
func (s *Server) tgBotLink(param string) string {
	bot := s.telegramBot()
	if bot == nil {
		return ""
	}
	return "https://t.me/" + bot.Username + "?start=" + param
}

// tgWelcome greets a freshly connected chat (§10.1).
func (s *Server) tgWelcome(v *store.TelegramChatView, seesPhotos bool, thread int64) {
	lang := tgGroupLang(v)
	args := map[string]string{"target": tgTargetName(v), "owner": tgOwnerName(v)}
	key := "welcome"
	if v.ChatKind == "private" {
		key = "welcome_private"
	}
	text := tgT(lang, key, args)
	if seesPhotos {
		text += "\n" + tgT(lang, "welcome_quality", args)
	} else {
		text += "\n" + tgT(lang, "welcome_admin", args)
	}
	text += "\n" + tgT(lang, "welcome_note", nil)
	buttons := []telegram.InlineButton{{Text: tgT(lang, "button_app", nil), URL: s.tgAppURL()}, {Text: tgT(lang, "button_help", nil), URL: s.tgBotLink("help")}}
	s.tgSay(v, text, telegram.SendOptions{ThreadID: thread, Silent: true, Buttons: [][]telegram.InlineButton{buttons}})
}

// tgPromote sends the app promotion to a contributor, once per person per
// chat and at most once per chat per 10 minutes (§10.3). refused: their
// media was refused (linked_only), the message asks them to join.
func (s *Server) tgPromote(ctx context.Context, chat *store.TelegramChatView, who *store.TelegramContributor, d *tgDecision, r *telegram.Reduced, refused bool) {
	if who.PromoSentAt != nil || (d.linked && !refused) || r.From == nil {
		return
	}
	now := s.now()
	ok, err := s.st.ClaimTelegramPromo(ctx, chat.ID, who.ID, now, 10*time.Minute)
	if err != nil || !ok {
		return
	}
	code := telegram.NewCode(telegram.PurposeJoin)
	if err := s.st.CreateTelegramCode(ctx, store.TelegramCode{ID: store.NewID(), CodeHash: telegram.HashCode(code), Purpose: "join",
		ForTgUserID: &who.TgUserID, ForChatID: &chat.ID, FrameID: chat.FrameID, FeedID: chat.FeedID, ExpiresAt: now.Add(30 * 24 * time.Hour)}); err != nil {
		s.log.Warn("telegram: join code", "chat", chat.ID, "err", err)
		return
	}
	lang := s.tgLang(ctx, chat, r.From)
	args := map[string]string{"name": i18n.FirstName(d.name), "target": tgTargetName(chat)}
	text, button := tgT(lang, "promo", args), tgT(lang, "button_app", nil)
	if refused {
		text, button = tgT(lang, "promo_linked_only", args), tgT(lang, "button_join", nil)
	}
	s.tgSay(chat, text, telegram.SendOptions{ReplyTo: r.MessageID, ThreadID: r.ThreadID, Silent: true,
		Buttons: [][]telegram.InlineButton{{{Text: button, URL: s.tgBotLink(code)}}}})
}

// tgRedeemJoin answers a join code in a private chat: the app links and,
// for the intended person, the join link (§10.3).
func (s *Server) tgRedeemJoin(ctx context.Context, here *store.TelegramChatView, code *store.TelegramCode, r *telegram.Reduced, lang string) error {
	help := func() error {
		s.tgSay(here, s.tgHelpText(lang, nil), telegram.SendOptions{ReplyTo: r.MessageID, Buttons: s.tgAppButtons(lang)})
		return nil
	}
	if r.ChatType != "private" || r.From == nil || code.ForTgUserID == nil || *code.ForTgUserID != r.From.ID ||
		code.RevokedAt != nil || !s.now().Before(code.ExpiresAt) || code.ForChatID == nil {
		return help() // a group member who isn't the person it was meant for (§10.3)
	}
	chat, err := s.st.GetTelegramChat(ctx, *code.ForChatID)
	if err != nil || chat.Status == "disconnected" {
		return help()
	}
	if err := s.st.InTx(ctx, func(tx *store.Tx) error { return tx.UseTelegramCode(ctx, code.ID, nil, s.now()) }); err != nil {
		return err
	}
	text := tgT(lang, "join_intro", nil)
	buttons := s.tgAppButtons(lang)
	if chat.OfferAppInvite {
		member := false
		if l, err := s.st.TelegramLinkByTg(ctx, s.st.Q(), r.From.ID); err == nil {
			member, _, _ = s.tgCanSend(ctx, l.UserID, chatTarget(&chat.TelegramChat))
		}
		if !member {
			link, err := s.tgJoinLink(ctx, chat, r.From)
			if err != nil {
				return err
			}
			if link != "" {
				text += "\n" + tgT(lang, "join_ask", map[string]string{"target": tgTargetName(chat)})
				buttons = append(buttons, []telegram.InlineButton{{Text: tgT(lang, "button_join", nil), URL: link}})
			}
		}
	}
	s.tgSay(here, text, telegram.SendOptions{ReplyTo: r.MessageID, Buttons: buttons})
	return nil
}

// tgJoinLink returns the contributor's channel invite (one live per chat and
// contributor), made on behalf of the chat's owner: single use, 30 days,
// always needs approval (§10.3, email-inbox.md §10.4).
func (s *Server) tgJoinLink(ctx context.Context, chat *store.TelegramChatView, p *telegram.Person) (string, error) {
	if chat.OwnerUserID == nil {
		return "", nil
	}
	var contributor uuid.UUID
	if err := s.st.Pool.QueryRow(ctx, `SELECT id FROM telegram_contributors WHERE chat_id = $1 AND tg_user_id = $2`, chat.ID, p.ID).Scan(&contributor); err != nil {
		if db.NotFound(err) {
			return "", nil
		}
		return "", err
	}
	ref := contributor.String()
	via := "telegram"
	note := p.Name + " (Telegram)"
	now := time.Now().UTC()
	expires := now.Add(30 * 24 * time.Hour).Truncate(time.Second)
	one := 1
	if chat.FrameID != nil {
		var token string
		err := s.st.Pool.QueryRow(ctx, `SELECT token FROM invites WHERE via = 'telegram' AND via_ref = $1 AND frame_id = $2 AND revoked_at IS NULL
			AND expires_at > now() AND uses < COALESCE(max_uses, 1) ORDER BY expires_at DESC LIMIT 1`, ref, *chat.FrameID).Scan(&token)
		if err == nil {
			return s.cfg.PublicURL + "/i/" + token, nil
		}
		if !db.NotFound(err) {
			return "", err
		}
		inv, err := s.st.CreateInvite(ctx, s.st.Q(), store.Invite{ID: store.NewID(), FrameID: *chat.FrameID, Token: auth.RandomToken(24),
			CreatedBy: chat.OwnerUserID, MaxUses: &one, Note: &note, ExpiresAt: expires, Via: &via, ViaRef: &ref})
		if err != nil {
			return "", err
		}
		return s.cfg.PublicURL + "/i/" + inv.Token, nil
	}
	var token string
	err := s.st.Pool.QueryRow(ctx, `SELECT token FROM feed_invites WHERE via = 'telegram' AND via_ref = $1 AND feed_id = $2 AND revoked_at IS NULL
		AND expires_at > now() AND uses < COALESCE(max_uses, 1) ORDER BY expires_at DESC LIMIT 1`, ref, *chat.FeedID).Scan(&token)
	if err == nil {
		return s.cfg.PublicURL + "/fi/" + token, nil
	}
	if !db.NotFound(err) {
		return "", err
	}
	for attempt := 0; ; attempt++ {
		inv, err := s.st.CreateFeedInvite(ctx, s.st.Q(), store.FeedInvite{ID: store.NewID(), FeedID: *chat.FeedID, Purpose: "join",
			Token: auth.RandomToken(24), Code: newFeedCode(), CreatedBy: chat.OwnerUserID, MaxUses: &one, Note: &note, ExpiresAt: expires,
			Via: &via, ViaRef: &ref})
		if db.IsUniqueViolation(err, "feed_invites_code_key") && attempt < 5 {
			continue
		}
		if err != nil {
			return "", err
		}
		return s.cfg.PublicURL + "/fi/" + inv.Token, nil
	}
}

func init() { channelJoins["telegram"] = telegramChannelJoin }

// telegramChannelJoin fills a join request from a Telegram join link:
// via_name "Ben · Telegram, Peeters family", via_matches null (Telegram can't
// tell whose kinframe account opened it). The bot handed the link to the
// verified Telegram user, so accepting it links that Telegram account to the
// requester: once approved, their photos are their own sends (§10.3).
func telegramChannelJoin(s *Server, ctx context.Context, tx *store.Tx, viaRef string, u *store.User) (store.JoinChannel, error) {
	ref := viaRef
	ch := store.JoinChannel{Ref: &ref}
	id, err := uuid.Parse(viaRef)
	if err != nil {
		return ch, nil
	}
	var name, chatTitle string
	var tgUser int64
	err = tx.QueryRow(ctx, `SELECT t.display_name, c.title, t.tg_user_id FROM telegram_contributors t JOIN telegram_chats c ON c.id = t.chat_id
		WHERE t.id = $1`, id).Scan(&name, &chatTitle, &tgUser)
	if db.NotFound(err) {
		return ch, nil
	}
	if err != nil {
		return ch, err
	}
	vn := name + " · Telegram, " + chatTitle
	ch.Name = &vn
	if tgUser > 0 { // a person, not a channel
		if _, err := s.st.LinkTelegram(ctx, tx, store.TelegramLink{TgUserID: tgUser, UserID: u.ID, DisplayName: name}); err != nil {
			return ch, err
		}
	}
	return ch, nil
}

// ---------------------------------------------------------------- frames' reactions and replies

// TelegramItemReacted mirrors a frame's reaction onto the Telegram message
// that brought the item: the latest reaction of the message's items, or 👍
// again when none is left (§10.2).
func (s *Server) TelegramItemReacted(ctx context.Context, itemID uuid.UUID) {
	if s.tg == nil {
		return
	}
	cs, err := s.st.TelegramContributionsOfItem(ctx, itemID)
	if err != nil {
		return
	}
	for _, c := range cs {
		chat, err := s.st.GetTelegramChat(ctx, c.ChatID)
		if err != nil || chat.Status == "disconnected" || chat.TgChatID != c.TgChatID {
			continue
		}
		first, items := c, []uuid.UUID{itemID}
		if c.MediaGroupID != nil {
			album, err := s.st.TelegramAlbum(ctx, s.st.Q(), c.ChatID, *c.MediaGroupID)
			if err != nil {
				continue
			}
			found := false
			for _, a := range album {
				if a.ItemID == nil || (a.State != "collecting" && a.State != "sent" && a.State != "duplicate") {
					continue
				}
				if !found {
					first, found = a, true
				}
				items = append(items, *a.ItemID)
			}
		}
		reaction, err := s.st.LatestTelegramReaction(ctx, items)
		if err != nil {
			continue
		}
		emoji := telegram.ReactionEmoji(reaction)
		if first.ReactionShown != nil && *first.ReactionShown == emoji {
			continue
		}
		s.tgReact(chat, first.MessageID, emoji)
	}
}

// TelegramItemReplied posts a frame's quick reply into the chat when the chat
// wants them (frame_replies), once per message and frame (§10.2).
func (s *Server) TelegramItemReplied(ctx context.Context, itemID uuid.UUID, frame *store.Frame, preset string) {
	if s.tg == nil {
		return
	}
	cs, err := s.st.TelegramContributionsOfItem(ctx, itemID)
	if err != nil {
		return
	}
	for _, c := range cs {
		chat, err := s.st.GetTelegramChat(ctx, c.ChatID)
		if err != nil || chat.Status == "disconnected" || !chat.FrameReplies || chat.TgChatID != c.TgChatID {
			continue
		}
		if ok, err := s.st.ClaimTelegramReply(ctx, c.ChatID, c.MessageID, frame.ID); err != nil || !ok {
			continue
		}
		lang := tgGroupLang(chat)
		text := tgT(lang, "frame_reply", map[string]string{"frame": frame.Name, "preset": i18n.T(lang, "push.preset."+preset, nil)})
		thread := int64(0)
		if c.ThreadID != nil {
			thread = *c.ThreadID
		}
		s.tgSay(chat, text, telegram.SendOptions{ReplyTo: c.MessageID, ThreadID: thread, Silent: true})
	}
}

// telegramReactionsOn: the owner of an outside contribution hears about
// reactions only with the chat's notify_reactions (§10.2).
func (s *Server) telegramReactionsOn(ctx context.Context, itemID uuid.UUID) bool {
	if s.tg == nil {
		return true
	}
	on, err := s.st.TelegramReactionsOn(ctx, itemID)
	return err != nil || on
}

// ---------------------------------------------------------------- events and pushes

// tgAudience: the target's managers and the owner.
func (s *Server) tgAudience(ctx context.Context, v *store.TelegramChatView) []uuid.UUID {
	var ids []uuid.UUID
	switch {
	case v.FrameID != nil:
		ids, _ = s.st.FrameManagerIDs(ctx, *v.FrameID)
	case v.FeedID != nil:
		ids, _ = s.st.FeedAdminIDs(ctx, s.st.Q(), *v.FeedID)
	}
	if v.OwnerUserID != nil {
		ids = appendUnique(ids, *v.OwnerUserID)
	}
	return ids
}

// publishTelegramChat sends telegram_chat.updated (rendered per recipient).
func (s *Server) publishTelegramChat(ctx context.Context, id uuid.UUID, pushes map[uuid.UUID]*notify.Push) {
	v, err := s.st.GetTelegramChat(ctx, id)
	if err != nil {
		return
	}
	for _, u := range s.tgAudience(ctx, v) {
		out, err := s.telegramChatJSON(ctx, u, v)
		if err != nil {
			continue
		}
		s.notify.User(u, notify.Event{"type": "telegram_chat.updated", "chat": out}, pushes[u])
	}
}

func (s *Server) publishTelegramChatRemoved(ctx context.Context, v *store.TelegramChatView) {
	ev := notify.Event{"type": "telegram_chat.removed", "chat_id": v.ID}
	if v.FrameID != nil {
		ev["frame_id"] = *v.FrameID
	}
	if v.FeedID != nil {
		ev["feed_id"] = *v.FeedID
	}
	s.notify.Users(s.tgAudience(ctx, v), ev, nil)
}

func (s *Server) publishTelegramCode(ctx context.Context, id uuid.UUID) {
	c, err := s.st.GetTelegramCode(ctx, id)
	if err != nil || c.CreatedBy == nil {
		return
	}
	s.notify.User(*c.CreatedBy, notify.Event{"type": "telegram_code.updated", "code": s.telegramCodeJSON(ctx, c, "")}, nil)
}

func (s *Server) publishTelegramLink(ctx context.Context, userID uuid.UUID) {
	var link any
	if l, err := s.st.TelegramLinkOf(ctx, userID); err == nil {
		link = telegramLinkJSON(l)
	}
	s.notify.User(userID, notify.Event{"type": "telegram_link.updated", "link": link}, nil)
}

// telegramAttention pushes telegram_attention to the chat's owner, at most
// once per chat and code per 7 days (§15.1). Recovery pushes nothing.
func (s *Server) telegramAttention(ctx context.Context, chatID uuid.UUID, code string) {
	now := s.now()
	var owner *uuid.UUID
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		c, err := tx.LockTelegramChat(ctx, chatID)
		if err != nil || c.OwnerUserID == nil {
			return err
		}
		if last, ok := c.AttentionPushed[code]; ok && code != "owner_changed" {
			if t, err := time.Parse(time.RFC3339, last); err == nil && now.Sub(t) < 7*24*time.Hour {
				return nil
			}
		}
		if c.AttentionPushed == nil {
			c.AttentionPushed = map[string]string{}
		}
		c.AttentionPushed[code] = now.UTC().Format(time.RFC3339)
		owner = c.OwnerUserID
		return s.st.SaveTelegramChat(ctx, tx, c)
	})
	if err != nil || owner == nil {
		return
	}
	v, err := s.st.GetTelegramChat(ctx, chatID)
	if err != nil {
		return
	}
	ids := map[string]string{"chat_id": chatID.String()}
	if v.FrameID != nil {
		ids["frame_id"] = v.FrameID.String()
	}
	if v.FeedID != nil {
		ids["feed_id"] = v.FeedID.String()
	}
	push := &notify.Push{Key: "telegram_attention", Text: "telegram_" + code, Args: map[string]string{"chat": v.Title, "target": tgTargetName(v)}, IDs: ids}
	out, err := s.telegramChatJSON(ctx, *owner, v)
	if err != nil {
		return
	}
	s.notify.User(*owner, notify.Event{"type": "telegram_chat.updated", "chat": out}, push)
}

// tgCredit adds the Telegram chat to an item's, send's or post's credit (ItemVia.telegram_chat_id).
func tgCredit(v *gen.ItemVia, chatID *uuid.UUID) *gen.ItemVia {
	if v != nil && v.Service == gen.PhotoServiceTelegram {
		v.TelegramChatId = chatID
	}
	return v
}
