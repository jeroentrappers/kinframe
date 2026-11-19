package api

import (
	"context"
	"sort"
	"strconv"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/telegram"
)

// Telegram endpoints (tag telegram, README §5.17): connect codes, chats,
// contributors and the linked account.

const tgCodeTTL = 30 * time.Minute

func (s *Server) tgEnabled() error {
	if s.tg == nil || s.telegramBot() == nil {
		return problem.New(problem.FeatureDisabled, "Telegram is not set up on this server")
	}
	return nil
}

// ---------------------------------------------------------------- rendering

func (s *Server) telegramChatJSON(ctx context.Context, userID uuid.UUID, v *store.TelegramChatView) (gen.TelegramChat, error) {
	manage, err := s.tgCanManage(ctx, userID, chatTarget(&v.TelegramChat))
	if err != nil {
		return gen.TelegramChat{}, err
	}
	out := gen.TelegramChat{
		Id: v.ID, ChatKind: gen.TelegramChatKind(v.ChatKind), Contributors: gen.TelegramContributorsMode(v.Contributors),
		CreatedAt: v.CreatedAt, EndedAt: v.EndedAt, FrameReplies: v.FrameReplies, NotifyReactions: v.NotifyReactions,
		OfferAppInvite: v.OfferAppInvite, Status: gen.TelegramChatStatus(v.Status), Title: v.Title, TopicId: v.TopicID,
		LastDeliveryAt: v.LastDeliveryAt, CanManage: ptr(manage), IsOwner: ptr(v.OwnerUserID != nil && *v.OwnerUserID == userID),
	}
	out.Counts.Sent = v.Counts["sent"]
	out.Counts.Contributors = v.ContributorN
	out.Counts.Duplicates = ptr(v.Counts["duplicates"])
	out.Counts.Skipped = ptr(v.Counts["skipped"])
	out.Counts.Failed = ptr(v.Counts["failed"])
	if v.ErrorCode != nil && v.Status == "needs_attention" {
		out.ErrorCode = ptr(gen.TelegramChatErrorCode(*v.ErrorCode))
	}
	if v.EndedReason != nil {
		out.EndedReason = ptr(gen.TelegramChatEndedReason(*v.EndedReason))
		out.EndedByName = v.EndedByName
	}
	if v.ChatKind == "group" {
		out.BotIsAdmin = ptr(v.BotStatus == "administrator")
		out.SeesAllMessages = ptr(tgSeesPhotos(&v.TelegramChat, s.telegramBot()))
		out.IsForum = ptr(v.IsForum)
	}
	if v.IsForum {
		topics := []gen.TelegramTopic{{Id: 1, Name: "General"}}
		var ids []int64
		for k := range v.Topics {
			if n, err := strconv.ParseInt(k, 10, 64); err == nil && n != 1 {
				ids = append(ids, n)
			}
		}
		sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
		for _, id := range ids {
			topics = append(topics, gen.TelegramTopic{Id: id, Name: v.Topics[strconv.FormatInt(id, 10)]})
		}
		out.Topics = &topics
	}
	if len(v.Warnings) > 0 && v.Status != "disconnected" {
		ws := make([]gen.TelegramChatWarning, len(v.Warnings))
		for i, w := range v.Warnings {
			ws[i] = gen.TelegramChatWarning(w)
		}
		out.Warnings = &ws
	}
	out.Target = tgTargetJSON(v.FrameID, v.FeedID, v.FrameName, v.FeedName)
	if v.OwnerUserID != nil && v.OwnerName != nil {
		out.Owner = &struct {
			AvatarUrl   *string `json:"avatar_url,omitempty"`
			DisplayName string  `json:"display_name"`
			UserId      gen.Id  `json:"user_id"`
		}{AvatarUrl: s.avatarURL(v.OwnerAvatarKey), DisplayName: *v.OwnerName, UserId: *v.OwnerUserID}
	}
	return out, nil
}

func tgTargetJSON(frameID, feedID *uuid.UUID, frameName, feedName *string) gen.TelegramChatTarget {
	if frameID != nil {
		return gen.TelegramChatTarget{Kind: gen.TelegramChatTargetKindFrame, FrameId: frameID, FrameName: frameName}
	}
	return gen.TelegramChatTarget{Kind: gen.TelegramChatTargetKindFeed, FeedId: feedID, FeedName: feedName}
}

func (s *Server) telegramCodeJSON(ctx context.Context, c *store.TelegramCode, code string) gen.TelegramCode {
	out := gen.TelegramCode{Id: c.ID, ExpiresAt: c.ExpiresAt, Purpose: gen.TelegramCodePurpose(c.Purpose), ChatId: c.ResultChatID}
	switch {
	case c.UsedAt != nil:
		out.Status = gen.TelegramCodeStatusUsed
	case c.RevokedAt != nil:
		out.Status = gen.TelegramCodeStatusRevoked
	case !s.now().Before(c.ExpiresAt):
		out.Status = gen.TelegramCodeStatusExpired
	default:
		out.Status = gen.TelegramCodeStatusOpen
	}
	if c.ErrorCode != nil {
		out.ErrorCode = ptr(gen.TelegramCodeErrorCode(*c.ErrorCode))
	}
	if c.ChatKind != nil {
		out.ChatKind = ptr(gen.TelegramChatKind(*c.ChatKind))
	}
	if c.Purpose == "connect" {
		out.LinkAccount = ptr(c.LinkAccount)
		t := tgTargetJSON(c.FrameID, c.FeedID, nil, nil)
		if c.FrameID != nil {
			if f, err := s.st.GetFrame(ctx, s.st.Q(), *c.FrameID); err == nil {
				t.FrameName = &f.Name
			}
		} else if c.FeedID != nil {
			if f, err := s.st.GetFeed(ctx, s.st.Q(), *c.FeedID); err == nil {
				t.FeedName = &f.Name
			}
		}
		out.Target = &t
	}
	if code != "" {
		out.Code = &code
		if bot := s.telegramBot(); bot != nil {
			param := "start"
			if c.Purpose == "connect" && c.ChatKind != nil && *c.ChatKind == "group" {
				param = "startgroup"
			}
			out.Url = ptr("https://t.me/" + bot.Username + "?" + param + "=" + code)
		}
	}
	return out
}

func telegramLinkJSON(l *store.TelegramLink) gen.TelegramLink {
	return gen.TelegramLink{CanMessage: l.CanMessage, DisplayName: l.DisplayName, LinkedAt: l.LinkedAt, Username: l.Username}
}

// ---------------------------------------------------------------- access

// tgManagedChat loads a chat the caller manages; others get 404 (§4.5).
func (s *Server) tgManagedChat(ctx context.Context, id uuid.UUID) (*store.TelegramChatView, error) {
	if err := s.tgEnabled(); err != nil {
		return nil, err
	}
	v, err := s.st.GetTelegramChat(ctx, id)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "Telegram chat not found")
	}
	if err != nil {
		return nil, err
	}
	ok, err := s.tgCanManage(ctx, me(ctx).ID, chatTarget(&v.TelegramChat))
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, problem.New(problem.NotFound, "Telegram chat not found")
	}
	return v, nil
}

func (s *Server) tgListChats(ctx context.Context, t tgTarget) ([]gen.TelegramChat, error) {
	if err := s.tgEnabled(); err != nil {
		return nil, err
	}
	u := me(ctx)
	ok, err := s.tgCanManage(ctx, u.ID, t)
	if err != nil {
		return nil, err
	}
	if !ok {
		if t.frameID != nil {
			if _, err := s.membership(ctx, *t.frameID); err != nil {
				return nil, err
			}
			return nil, problem.New(problem.MissingPermission, "requires the %s permission", "change_settings")
		}
		if _, err := s.feedView(ctx, s.st.Q(), *t.feedID); err != nil {
			return nil, err
		}
		return nil, problem.New(problem.Forbidden, "owner or admin only")
	}
	vs, err := s.st.ListTelegramChats(ctx, t.frameID, t.feedID, s.now().Add(-30*24*time.Hour))
	if err != nil {
		return nil, err
	}
	out := make([]gen.TelegramChat, 0, len(vs))
	for i := range vs {
		c, err := s.telegramChatJSON(ctx, u.ID, &vs[i])
		if err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, nil
}

func (s *Server) ListFrameTelegramChats(ctx context.Context, req gen.ListFrameTelegramChatsRequestObject) (gen.ListFrameTelegramChatsResponseObject, error) {
	out, err := s.tgListChats(ctx, tgTarget{frameID: &req.FrameId})
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

func (s *Server) ListFeedTelegramChats(ctx context.Context, req gen.ListFeedTelegramChatsRequestObject) (gen.ListFeedTelegramChatsResponseObject, error) {
	out, err := s.tgListChats(ctx, tgTarget{feedID: &req.FeedId})
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

// ---------------------------------------------------------------- codes

func (s *Server) tgCreateCode(ctx context.Context, t *tgTarget, body *gen.TelegramConnectCodeCreate, key *string) (Response, error) {
	if err := s.tgEnabled(); err != nil {
		return Response{}, err
	}
	u := me(ctx)
	scope := "telegram_link_code"
	if t != nil {
		scope = "telegram_code"
	}
	if key != nil && *key != "" {
		var prev gen.TelegramCode
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, scope, *key, &prev); err != nil {
			return Response{}, err
		} else if found {
			return created(prev), nil
		}
	}
	c := store.TelegramCode{ID: store.NewID(), CreatedBy: &u.ID, ExpiresAt: s.now().Add(tgCodeTTL).UTC().Truncate(time.Second)}
	var prefix byte = telegram.PurposeLink
	if t == nil {
		c.Purpose = "link"
		if _, err := s.st.TelegramLinkOf(ctx, u.ID); err == nil {
			return Response{}, problem.New(problem.TelegramAlreadyLinked, "unlink your Telegram account first")
		} else if err != store.ErrNotFound {
			return Response{}, err
		}
	} else {
		ok, err := s.tgCanManage(ctx, u.ID, *t)
		if err != nil {
			return Response{}, err
		}
		if !ok {
			if t.frameID != nil {
				if _, err := s.membership(ctx, *t.frameID); err != nil {
					return Response{}, err
				}
				return Response{}, problem.New(problem.MissingPermission, "requires the %s permission", "change_settings")
			}
			if _, err := s.feedView(ctx, s.st.Q(), *t.feedID); err != nil {
				return Response{}, err
			}
			return Response{}, problem.New(problem.Forbidden, "owner or admin only")
		}
		kind := string(body.ChatKind)
		link := body.LinkAccount != nil && *body.LinkAccount
		if link && kind != "private" {
			return Response{}, problem.Validation("link_account", "enum", "link_account is only for private chats")
		}
		if link {
			if _, err := s.st.TelegramLinkOf(ctx, u.ID); err == nil {
				return Response{}, problem.New(problem.TelegramAlreadyLinked, "unlink your Telegram account first")
			} else if err != store.ErrNotFound {
				return Response{}, err
			}
		}
		limits := s.Limits(ctx)
		nTarget, nOwned, err := s.st.CountTelegramChats(ctx, s.st.Q(), t.frameID, t.feedID, u.ID)
		if err != nil {
			return Response{}, err
		}
		if nTarget >= limits.MaxTelegramChatsPerTarget {
			return Response{}, problem.New(problem.LimitReached, "at most %d Telegram chats per frame or feed", limits.MaxTelegramChatsPerTarget)
		}
		if nOwned >= limits.MaxTelegramChatsPerUser {
			return Response{}, problem.New(problem.LimitReached, "you can own at most %d Telegram chats", limits.MaxTelegramChatsPerUser)
		}
		c.Purpose, c.ChatKind, c.LinkAccount, c.FrameID, c.FeedID = "connect", &kind, link, t.frameID, t.feedID
		prefix = telegram.PurposeConnectGroup
		if kind == "private" {
			prefix = telegram.PurposeConnectPrivate
		}
	}
	if ok, retry := s.tg.codeUser.Allow(u.ID.String()); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return Response{}, p
	}
	code := telegram.NewCode(prefix)
	c.CodeHash = telegram.HashCode(code)
	if err := s.st.CreateTelegramCode(ctx, c); err != nil {
		return Response{}, err
	}
	stored, err := s.st.GetTelegramCode(ctx, c.ID)
	if err != nil {
		return Response{}, err
	}
	out := s.telegramCodeJSON(ctx, stored, code)
	if key != nil && *key != "" {
		_ = s.st.InTx(ctx, func(tx *store.Tx) error { return tx.SaveIdempotent(ctx, u.ID, scope, *key, out) })
	}
	return created(out), nil
}

func (s *Server) CreateFrameTelegramCode(ctx context.Context, req gen.CreateFrameTelegramCodeRequestObject) (gen.CreateFrameTelegramCodeResponseObject, error) {
	r, err := s.tgCreateCode(ctx, &tgTarget{frameID: &req.FrameId}, req.Body, req.Params.IdempotencyKey)
	if err != nil {
		return nil, err
	}
	return r, nil
}

func (s *Server) CreateFeedTelegramCode(ctx context.Context, req gen.CreateFeedTelegramCodeRequestObject) (gen.CreateFeedTelegramCodeResponseObject, error) {
	r, err := s.tgCreateCode(ctx, &tgTarget{feedID: &req.FeedId}, req.Body, req.Params.IdempotencyKey)
	if err != nil {
		return nil, err
	}
	return r, nil
}

func (s *Server) CreateTelegramLinkCode(ctx context.Context, req gen.CreateTelegramLinkCodeRequestObject) (gen.CreateTelegramLinkCodeResponseObject, error) {
	r, err := s.tgCreateCode(ctx, nil, nil, req.Params.IdempotencyKey)
	if err != nil {
		return nil, err
	}
	return r, nil
}

func (s *Server) ownTelegramCode(ctx context.Context, id uuid.UUID) (*store.TelegramCode, error) {
	if err := s.tgEnabled(); err != nil {
		return nil, err
	}
	c, err := s.st.GetTelegramCode(ctx, id)
	if err == store.ErrNotFound || (err == nil && (c.CreatedBy == nil || *c.CreatedBy != me(ctx).ID || c.Purpose == "join")) {
		return nil, problem.New(problem.NotFound, "Telegram code not found")
	}
	return c, err
}

func (s *Server) GetTelegramCode(ctx context.Context, req gen.GetTelegramCodeRequestObject) (gen.GetTelegramCodeResponseObject, error) {
	c, err := s.ownTelegramCode(ctx, req.CodeId)
	if err != nil {
		return nil, err
	}
	return ok(s.telegramCodeJSON(ctx, c, "")), nil
}

func (s *Server) RevokeTelegramCode(ctx context.Context, req gen.RevokeTelegramCodeRequestObject) (gen.RevokeTelegramCodeResponseObject, error) {
	c, err := s.ownTelegramCode(ctx, req.CodeId)
	if err != nil {
		return nil, err
	}
	if err := s.st.RevokeTelegramCode(ctx, c.ID, me(ctx).ID); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// ---------------------------------------------------------------- chats

func (s *Server) GetTelegramChat(ctx context.Context, req gen.GetTelegramChatRequestObject) (gen.GetTelegramChatResponseObject, error) {
	v, err := s.tgManagedChat(ctx, req.ChatId)
	if err != nil {
		return nil, err
	}
	out, err := s.telegramChatJSON(ctx, me(ctx).ID, v)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

func (s *Server) UpdateTelegramChat(ctx context.Context, req gen.UpdateTelegramChatRequestObject) (gen.UpdateTelegramChatResponseObject, error) {
	v, err := s.tgManagedChat(ctx, req.ChatId)
	if err != nil {
		return nil, err
	}
	b := req.Body
	u := me(ctx)
	if v.Status == "disconnected" {
		return nil, problem.New(problem.InvalidTransition, "this chat is disconnected")
	}
	takeOver := b.TakeOver != nil && *b.TakeOver
	isOwner := v.OwnerUserID != nil && *v.OwnerUserID == u.ID
	if b.NotifyReactions != nil && *b.NotifyReactions != v.NotifyReactions && !isOwner && !takeOver {
		return nil, problem.New(problem.Forbidden, "only the chat's owner can change its reaction notifications")
	}
	if present, null := rawField(ctx, "topic_id"); present {
		switch {
		case null:
			v.TopicID = nil
		case b.TopicId != nil:
			_, seen := v.Topics[strconv.FormatInt(*b.TopicId, 10)]
			if !v.IsForum || (*b.TopicId != 1 && !seen) {
				return nil, problem.Validation("topic_id", "enum", "choose one of the topics the bot has seen")
			}
			v.TopicID = b.TopicId
		}
	}
	if b.Contributors != nil {
		v.Contributors = string(*b.Contributors)
	}
	if b.FrameReplies != nil {
		v.FrameReplies = *b.FrameReplies
	}
	if b.OfferAppInvite != nil {
		v.OfferAppInvite = *b.OfferAppInvite
	}
	if takeOver {
		v.OwnerUserID = &u.ID
	}
	if b.NotifyReactions != nil {
		v.NotifyReactions = *b.NotifyReactions
	}
	if b.Paused != nil {
		switch {
		case *b.Paused && v.Status != "paused":
			v.Status = "paused"
		case !*b.Paused && v.Status == "paused":
			v.Status = "active"
			v.ErrorCode = nil
			if !tgSeesPhotos(&v.TelegramChat, s.telegramBot()) {
				v.Status, v.ErrorCode = "needs_attention", ptr("privacy_mode")
			}
		}
	}
	if err := s.st.SaveTelegramChat(ctx, s.st.Q(), &v.TelegramChat); err != nil {
		return nil, err
	}
	s.publishTelegramChat(ctx, v.ID, nil)
	nv, err := s.st.GetTelegramChat(ctx, v.ID)
	if err != nil {
		return nil, err
	}
	out, err := s.telegramChatJSON(ctx, u.ID, nv)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

func (s *Server) DisconnectTelegramChat(ctx context.Context, req gen.DisconnectTelegramChatRequestObject) (gen.DisconnectTelegramChatResponseObject, error) {
	v, err := s.tgManagedChat(ctx, req.ChatId)
	if err != nil {
		return nil, err
	}
	if v.Status != "disconnected" {
		if err := s.tgDisconnect(ctx, v, "disconnected", me(ctx).ID); err != nil {
			return nil, err
		}
	}
	return noContent(), nil
}

// ---------------------------------------------------------------- contributors

func (s *Server) telegramContributorJSON(ctx context.Context, chat *store.TelegramChatView, c *store.TelegramContributorView) gen.TelegramContributor {
	out := gen.TelegramContributor{Id: c.ID, Blocked: c.Blocked, DisplayName: c.DisplayName, FirstSeenAt: &c.FirstSeenAt, LastSeenAt: c.LastSeenAt,
		SentCount: c.SentCount, Linked: c.LinkedUserID != nil}
	if c.LinkedUserID != nil {
		if c.LinkedUserName != nil {
			out.DisplayName = *c.LinkedUserName
		}
		if ok, _, _ := s.tgCanSend(ctx, *c.LinkedUserID, chatTarget(&chat.TelegramChat)); ok {
			out.UserId = c.LinkedUserID
		}
	}
	return out
}

func (s *Server) ListTelegramContributors(ctx context.Context, req gen.ListTelegramContributorsRequestObject) (gen.ListTelegramContributorsResponseObject, error) {
	v, err := s.tgManagedChat(ctx, req.ChatId)
	if err != nil {
		return nil, err
	}
	cs, err := s.st.ListTelegramContributors(ctx, v.ID)
	if err != nil {
		return nil, err
	}
	out := make([]gen.TelegramContributor, len(cs))
	for i := range cs {
		out[i] = s.telegramContributorJSON(ctx, v, &cs[i])
	}
	return ok(out), nil
}

func (s *Server) UpdateTelegramContributor(ctx context.Context, req gen.UpdateTelegramContributorRequestObject) (gen.UpdateTelegramContributorResponseObject, error) {
	v, err := s.tgManagedChat(ctx, req.ChatId)
	if err != nil {
		return nil, err
	}
	c, err := s.st.GetTelegramContributor(ctx, v.ID, req.ContributorId)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "contributor not found")
	}
	if err != nil {
		return nil, err
	}
	if err := s.st.SetTelegramContributorBlocked(ctx, c.ID, req.Body.Blocked); err != nil {
		return nil, err
	}
	if req.Body.Blocked {
		if err := s.st.InTx(ctx, func(tx *store.Tx) error { return s.tgRevokeInvites(ctx, tx, v.ID, &c.ID) }); err != nil {
			return nil, err
		}
	}
	c.Blocked = req.Body.Blocked
	return ok(s.telegramContributorJSON(ctx, v, c)), nil
}

// ---------------------------------------------------------------- linked account

func (s *Server) GetTelegramLink(ctx context.Context, _ gen.GetTelegramLinkRequestObject) (gen.GetTelegramLinkResponseObject, error) {
	if err := s.tgEnabled(); err != nil {
		return nil, err
	}
	l, err := s.st.TelegramLinkOf(ctx, me(ctx).ID)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "no Telegram account is linked")
	}
	if err != nil {
		return nil, err
	}
	return ok(telegramLinkJSON(l)), nil
}

func (s *Server) UnlinkTelegram(ctx context.Context, _ gen.UnlinkTelegramRequestObject) (gen.UnlinkTelegramResponseObject, error) {
	if err := s.tgEnabled(); err != nil {
		return nil, err
	}
	removed, err := s.st.UnlinkTelegram(ctx, me(ctx).ID)
	if err != nil {
		return nil, err
	}
	if removed {
		s.publishTelegramLink(ctx, me(ctx).ID)
	}
	return noContent(), nil
}

// ---------------------------------------------------------------- discovery and limits

// telegramDiscovery fills discovery's Telegram fields (§19).
func (s *Server) telegramDiscovery(d *gen.Discovery) {
	bot := s.telegramBot()
	d.Telegram = ptr(bot != nil)
	if bot == nil {
		return
	}
	d.TelegramBotUsername = ptr(bot.Username)
	d.TelegramPrivacyMode = ptr(!bot.CanReadAll)
	d.TelegramMaxFileBytes = ptr(int(s.tg.maxDown))
}

func telegramLimitsJSON(l config.Limits, out *gen.Limits) {
	out.MaxTelegramChatsPerTarget = ptr(l.MaxTelegramChatsPerTarget)
	out.MaxTelegramChatsPerUser = ptr(l.MaxTelegramChatsPerUser)
	out.MaxTelegramItemsPerChatHour = ptr(l.MaxTelegramItemsPerChatHour)
}

// setTelegramLimits keeps the current Telegram limits unless the body sets them.
func setTelegramLimits(l *config.Limits, cur config.Limits, b *gen.Limits) {
	l.MaxTelegramChatsPerTarget, l.MaxTelegramChatsPerUser = cur.MaxTelegramChatsPerTarget, cur.MaxTelegramChatsPerUser
	l.MaxTelegramItemsPerChatHour = cur.MaxTelegramItemsPerChatHour
	if b == nil {
		return
	}
	for _, f := range []struct {
		dst *int
		src *int
	}{{&l.MaxTelegramChatsPerTarget, b.MaxTelegramChatsPerTarget}, {&l.MaxTelegramChatsPerUser, b.MaxTelegramChatsPerUser},
		{&l.MaxTelegramItemsPerChatHour, b.MaxTelegramItemsPerChatHour}} {
		if f.src != nil && *f.src > 0 {
			*f.dst = *f.src
		}
	}
}
