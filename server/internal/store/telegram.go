package store

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// Telegram bridge rows (docs/spec/telegram.md §16).

// ---------------------------------------------------------------- the bot

// TelegramBot is the bot's own state (one row).
type TelegramBot struct {
	BotID           int64      `db:"bot_id"`
	Username        string     `db:"username"`
	CanReadAll      bool       `db:"can_read_all_group_msgs"`
	PrivacyOffSince *time.Time `db:"privacy_off_since"`
	Mode            string     `db:"mode"`
	PollOffset      int64      `db:"poll_offset"`
	WebhookHash     *string    `db:"webhook_hash"`
	APIBase         *string    `db:"api_base"`
	CommandsHash    *string    `db:"commands_hash"`
}

const telegramBotCols = `bot_id, username, can_read_all_group_msgs, privacy_off_since, mode, poll_offset, webhook_hash, api_base, commands_hash`

// GetTelegramBot returns the stored bot, or ErrNotFound before the first start.
func (s *Store) GetTelegramBot(ctx context.Context) (*TelegramBot, error) {
	return one[TelegramBot](s.Pool.Query(ctx, `SELECT `+telegramBotCols+` FROM telegram_bot`))
}

// SaveTelegramBot records getMe's answer and the delivery mode. The poll
// offset starts over when the bot changed. privacy_off_since is set when the
// bot is first seen with privacy mode off and cleared when it is on again.
func (s *Store) SaveTelegramBot(ctx context.Context, b TelegramBot, now time.Time) (*TelegramBot, error) {
	return one[TelegramBot](s.Pool.Query(ctx, `
		INSERT INTO telegram_bot (bot_id, username, can_read_all_group_msgs, privacy_off_since, mode, updated_at)
		VALUES ($1, $2, $3, CASE WHEN $3 THEN $5::timestamptz END, $4, $5)
		ON CONFLICT (id) DO UPDATE SET username = EXCLUDED.username, can_read_all_group_msgs = EXCLUDED.can_read_all_group_msgs,
			privacy_off_since = CASE WHEN NOT EXCLUDED.can_read_all_group_msgs THEN NULL
				WHEN telegram_bot.bot_id <> EXCLUDED.bot_id OR telegram_bot.privacy_off_since IS NULL THEN $5::timestamptz
				ELSE telegram_bot.privacy_off_since END,
			poll_offset = CASE WHEN telegram_bot.bot_id <> EXCLUDED.bot_id THEN 0 ELSE telegram_bot.poll_offset END,
			webhook_hash = CASE WHEN telegram_bot.bot_id <> EXCLUDED.bot_id THEN NULL ELSE telegram_bot.webhook_hash END,
			commands_hash = CASE WHEN telegram_bot.bot_id <> EXCLUDED.bot_id THEN NULL ELSE telegram_bot.commands_hash END,
			bot_id = EXCLUDED.bot_id, mode = EXCLUDED.mode, updated_at = EXCLUDED.updated_at
		RETURNING `+telegramBotCols, b.BotID, b.Username, b.CanReadAll, b.Mode, now))
}

// SetTelegramBotField sets one of webhook_hash, api_base, commands_hash.
func (s *Store) SetTelegramBotField(ctx context.Context, field string, value *string) error {
	switch field {
	case "webhook_hash", "api_base", "commands_hash":
	default:
		return errors.New("telegram bot: unknown field " + field)
	}
	_, err := s.Pool.Exec(ctx, `UPDATE telegram_bot SET `+field+` = $1, updated_at = now()`, value)
	return err
}

// TelegramPollOffset returns the next getUpdates offset.
func (s *Store) TelegramPollOffset(ctx context.Context) (int64, error) {
	var off int64
	err := s.Pool.QueryRow(ctx, `SELECT poll_offset FROM telegram_bot`).Scan(&off)
	if db.NotFound(err) {
		return 0, nil
	}
	return off, err
}

// MarkChatsBotChanged puts live chats of another bot in needs_attention (§7).
func (s *Store) MarkChatsBotChanged(ctx context.Context, botID int64) ([]uuid.UUID, error) {
	rows, err := s.Pool.Query(ctx, `UPDATE telegram_chats SET status = 'needs_attention', error_code = 'bot_changed', updated_at = now()
		WHERE status <> 'disconnected' AND bot_id <> $1 AND error_code IS DISTINCT FROM 'bot_changed' RETURNING id`, botID)
	if err != nil {
		return nil, err
	}
	return collectIDs(rows)
}

// ---------------------------------------------------------------- consumer lock

// TelegramLockKey is the advisory lock that elects the one Telegram consumer.
const TelegramLockKey int64 = 0x6b66746700000001 // "kftg" 1

// AdvisoryLock is a session-level Postgres advisory lock held on its own
// connection (telegram.Lock).
type AdvisoryLock struct {
	Pool *pgxpool.Pool
	Key  int64
	conn *pgxpool.Conn
}

func (l *AdvisoryLock) TryAcquire(ctx context.Context) (bool, error) {
	if l.conn != nil {
		return true, nil
	}
	c, err := l.Pool.Acquire(ctx)
	if err != nil {
		return false, err
	}
	var ok bool
	if err := c.QueryRow(ctx, `SELECT pg_try_advisory_lock($1)`, l.Key).Scan(&ok); err != nil || !ok {
		c.Release()
		return false, err
	}
	l.conn = c
	return true, nil
}

func (l *AdvisoryLock) Check(ctx context.Context) error {
	if l.conn == nil {
		return errors.New("not held")
	}
	cctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return l.conn.Ping(cctx)
}

func (l *AdvisoryLock) Release() {
	if l.conn == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := l.conn.Exec(ctx, `SELECT pg_advisory_unlock($1)`, l.Key); err != nil {
		// A broken connection must not go back to the pool still holding the lock.
		_ = l.conn.Conn().Close(ctx)
	}
	l.conn.Release()
	l.conn = nil
}

// ---------------------------------------------------------------- updates

// TelegramUpdate is a stored update waiting to be processed.
type TelegramUpdate struct {
	UpdateID int64           `db:"update_id"`
	ChatID   *int64          `db:"chat_id"`
	Payload  json.RawMessage `db:"payload"`
	Attempts int             `db:"attempts"`
}

// StoreTelegramUpdates inserts reduced updates (an existing update id is a
// no-op, §8.1 step 3) and, when offset > 0, advances the poll offset, in one
// transaction. Returns how many were new.
func (s *Store) StoreTelegramUpdates(ctx context.Context, ups []TelegramUpdate, offset int64) (int, error) {
	n := 0
	err := db.InTx(ctx, s.Pool, func(tx pgx.Tx) error {
		for _, u := range ups {
			tag, err := tx.Exec(ctx, `INSERT INTO telegram_updates (update_id, chat_id, payload) VALUES ($1, $2, $3)
				ON CONFLICT (update_id) DO NOTHING`, u.UpdateID, u.ChatID, u.Payload)
			if err != nil {
				return err
			}
			n += int(tag.RowsAffected())
		}
		if offset > 0 {
			_, err := tx.Exec(ctx, `UPDATE telegram_bot SET poll_offset = GREATEST(poll_offset, $1)`, offset)
			return err
		}
		return nil
	})
	return n, err
}

// PendingTelegramChats lists chats with updates due, by their oldest update.
func (s *Store) PendingTelegramChats(ctx context.Context, now time.Time, limit int) ([]int64, error) {
	rows, err := s.Pool.Query(ctx, `SELECT chat FROM (
		SELECT DISTINCT ON (COALESCE(chat_id, 0)) COALESCE(chat_id, 0) AS chat, update_id, next_attempt_at
		FROM telegram_updates WHERE state = 'pending' ORDER BY COALESCE(chat_id, 0), update_id) x
		WHERE next_attempt_at <= $1 ORDER BY update_id LIMIT $2`, now, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []int64
	for rows.Next() {
		var c int64
		if err := rows.Scan(&c); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// NextTelegramUpdate returns the oldest pending update of a chat when it is
// due (a failed attempt holds the chat's later updates back, §8.2).
func (s *Store) NextTelegramUpdate(ctx context.Context, chatID int64, now time.Time) (*TelegramUpdate, error) {
	u, err := one[TelegramUpdate](s.Pool.Query(ctx, `SELECT update_id, chat_id, payload, attempts FROM telegram_updates
		WHERE state = 'pending' AND COALESCE(chat_id, 0) = $1 ORDER BY update_id LIMIT 1`, chatID))
	if err != nil {
		return nil, err
	}
	var due bool
	if err := s.Pool.QueryRow(ctx, `SELECT next_attempt_at <= $2 FROM telegram_updates WHERE update_id = $1`, u.UpdateID, now).Scan(&due); err != nil {
		return nil, err
	}
	if !due {
		return nil, ErrNotFound
	}
	return u, nil
}

// FinishTelegramUpdate marks an update processed (its payload is dropped).
func (s *Store) FinishTelegramUpdate(ctx context.Context, id int64, now time.Time) error {
	_, err := s.Pool.Exec(ctx, `UPDATE telegram_updates SET state = 'done', payload = NULL, processed_at = $2 WHERE update_id = $1`, id, now)
	return err
}

// RetryTelegramUpdate records a failed attempt: retried at next, failed
// (payload dropped) after maxAttempts.
func (s *Store) RetryTelegramUpdate(ctx context.Context, id int64, next time.Time, maxAttempts int) error {
	_, err := s.Pool.Exec(ctx, `UPDATE telegram_updates SET attempts = attempts + 1, next_attempt_at = $2,
		state = CASE WHEN attempts + 1 >= $3 THEN 'failed' ELSE 'pending' END,
		payload = CASE WHEN attempts + 1 >= $3 THEN NULL ELSE payload END,
		processed_at = CASE WHEN attempts + 1 >= $3 THEN now() END
		WHERE update_id = $1`, id, next, maxAttempts)
	return err
}

// ---------------------------------------------------------------- codes

// TelegramCode is a connect, link or join code (stored hashed).
type TelegramCode struct {
	ID           uuid.UUID  `db:"id"`
	CodeHash     string     `db:"code_hash"`
	Purpose      string     `db:"purpose"`
	ChatKind     *string    `db:"chat_kind"`
	LinkAccount  bool       `db:"link_account"`
	CreatedBy    *uuid.UUID `db:"created_by"`
	FrameID      *uuid.UUID `db:"frame_id"`
	FeedID       *uuid.UUID `db:"feed_id"`
	ForTgUserID  *int64     `db:"for_tg_user_id"`
	ForChatID    *uuid.UUID `db:"for_chat_id"`
	ExpiresAt    time.Time  `db:"expires_at"`
	UsedAt       *time.Time `db:"used_at"`
	RevokedAt    *time.Time `db:"revoked_at"`
	ResultChatID *uuid.UUID `db:"result_chat_id"`
	ErrorCode    *string    `db:"error_code"`
	CreatedAt    time.Time  `db:"created_at"`
}

const telegramCodeCols = `id, code_hash, purpose, chat_kind, link_account, created_by, frame_id, feed_id, for_tg_user_id, for_chat_id,
	expires_at, used_at, revoked_at, result_chat_id, error_code, created_at`

// CreateTelegramCode inserts a code. For connect and link codes the
// creator's previous open code of the same kind (and target) is revoked.
func (s *Store) CreateTelegramCode(ctx context.Context, c TelegramCode) error {
	return db.InTx(ctx, s.Pool, func(tx pgx.Tx) error {
		if c.CreatedBy != nil && c.Purpose != "join" {
			if _, err := tx.Exec(ctx, `UPDATE telegram_codes SET revoked_at = now() WHERE created_by = $1 AND purpose = $2
				AND chat_kind IS NOT DISTINCT FROM $3 AND frame_id IS NOT DISTINCT FROM $4 AND feed_id IS NOT DISTINCT FROM $5
				AND used_at IS NULL AND revoked_at IS NULL`, c.CreatedBy, c.Purpose, c.ChatKind, c.FrameID, c.FeedID); err != nil {
				return err
			}
		}
		_, err := tx.Exec(ctx, `INSERT INTO telegram_codes (id, code_hash, purpose, chat_kind, link_account, created_by, frame_id, feed_id,
			for_tg_user_id, for_chat_id, expires_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`,
			c.ID, c.CodeHash, c.Purpose, c.ChatKind, c.LinkAccount, c.CreatedBy, c.FrameID, c.FeedID, c.ForTgUserID, c.ForChatID, c.ExpiresAt)
		return err
	})
}

// GetTelegramCode returns a code by id.
func (s *Store) GetTelegramCode(ctx context.Context, id uuid.UUID) (*TelegramCode, error) {
	return one[TelegramCode](s.Pool.Query(ctx, `SELECT `+telegramCodeCols+` FROM telegram_codes WHERE id = $1`, id))
}

// TelegramCodeByHash returns a code by its hash.
func (s *Store) TelegramCodeByHash(ctx context.Context, q db.DBTX, hash string) (*TelegramCode, error) {
	return one[TelegramCode](q.Query(ctx, `SELECT `+telegramCodeCols+` FROM telegram_codes WHERE code_hash = $1`, hash))
}

// LockTelegramCode returns a code locked for redeeming.
func (tx *Tx) LockTelegramCode(ctx context.Context, id uuid.UUID) (*TelegramCode, error) {
	return one[TelegramCode](tx.Query(ctx, `SELECT `+telegramCodeCols+` FROM telegram_codes WHERE id = $1 FOR UPDATE`, id))
}

// RevokeTelegramCode revokes an open code of its creator.
func (s *Store) RevokeTelegramCode(ctx context.Context, id, creator uuid.UUID) error {
	_, err := s.Pool.Exec(ctx, `UPDATE telegram_codes SET revoked_at = now() WHERE id = $1 AND created_by = $2 AND used_at IS NULL AND revoked_at IS NULL`, id, creator)
	return err
}

// SetTelegramCodeError records why redeeming failed (the code may stay open).
func (s *Store) SetTelegramCodeError(ctx context.Context, q db.DBTX, id uuid.UUID, code string) error {
	_, err := q.Exec(ctx, `UPDATE telegram_codes SET error_code = $2 WHERE id = $1`, id, code)
	return err
}

// UseTelegramCode marks a code used, with the chat it created.
func (tx *Tx) UseTelegramCode(ctx context.Context, id uuid.UUID, chatID *uuid.UUID, now time.Time) error {
	_, err := tx.Exec(ctx, `UPDATE telegram_codes SET used_at = COALESCE(used_at, $3), result_chat_id = COALESCE($2, result_chat_id), error_code = NULL WHERE id = $1`, id, chatID, now)
	return err
}

// JoinCodeFor returns the live join code of a contributor in a chat.
func (s *Store) JoinCodeFor(ctx context.Context, chatID uuid.UUID, tgUserID int64, now time.Time) (*TelegramCode, error) {
	return one[TelegramCode](s.Pool.Query(ctx, `SELECT `+telegramCodeCols+` FROM telegram_codes WHERE purpose = 'join'
		AND for_chat_id = $1 AND for_tg_user_id = $2 AND revoked_at IS NULL AND expires_at > $3 ORDER BY created_at DESC LIMIT 1`, chatID, tgUserID, now))
}

// ---------------------------------------------------------------- chats

// TelegramChat is a binding of one Telegram chat to a frame or feed.
type TelegramChat struct {
	ID              uuid.UUID         `db:"id"`
	TgChatID        int64             `db:"tg_chat_id"`
	PreviousChatID  *int64            `db:"previous_chat_id"`
	BotID           int64             `db:"bot_id"`
	ChatKind        string            `db:"chat_kind"`
	IsForum         bool              `db:"is_forum"`
	Title           string            `db:"title"`
	PrivateTgUserID *int64            `db:"private_tg_user_id"`
	OwnerUserID     *uuid.UUID        `db:"owner_user_id"`
	FrameID         *uuid.UUID        `db:"frame_id"`
	FeedID          *uuid.UUID        `db:"feed_id"`
	Contributors    string            `db:"contributors"`
	FrameReplies    bool              `db:"frame_replies"`
	NotifyReactions bool              `db:"notify_reactions"`
	OfferAppInvite  bool              `db:"offer_app_invite"`
	TopicID         *int64            `db:"topic_id"`
	Topics          map[string]string `db:"topics"`
	Status          string            `db:"status"`
	ErrorCode       *string           `db:"error_code"`
	Warnings        []string          `db:"warnings"`
	EndedReason     *string           `db:"ended_reason"`
	EndedByUserID   *uuid.UUID        `db:"ended_by_user_id"`
	BotStatus       string            `db:"bot_status"`
	BotJoinedAt     time.Time         `db:"bot_joined_at"`
	AttentionPushed map[string]string `db:"attention_pushed"`
	WelcomeSentAt   *time.Time        `db:"welcome_sent_at"`
	ReadySentAt     *time.Time        `db:"ready_sent_at"`
	SpoilerNoticeAt *time.Time        `db:"spoiler_notice_at"`
	LastPromoAt     *time.Time        `db:"last_promo_at"`
	LastDeliveryAt  *time.Time        `db:"last_delivery_at"`
	Counts          map[string]int    `db:"counts"`
	CreatedAt       time.Time         `db:"created_at"`
	UpdatedAt       time.Time         `db:"updated_at"`
	EndedAt         *time.Time        `db:"ended_at"`
	FarewellDue     bool              `db:"farewell_due"`
}

// HasWarning reports a warning code.
func (c *TelegramChat) HasWarning(w string) bool {
	for _, x := range c.Warnings {
		if x == w {
			return true
		}
	}
	return false
}

// TelegramChatView is a chat with names for rendering.
type TelegramChatView struct {
	TelegramChat
	OwnerName      *string `db:"owner_name"`
	OwnerAvatarKey *string `db:"owner_avatar_key"`
	OwnerLanguage  *string `db:"owner_language"`
	FrameName      *string `db:"frame_name"`
	FeedName       *string `db:"feed_name"`
	EndedByName    *string `db:"ended_by_name"`
	ContributorN   int     `db:"contributor_n"`
}

const telegramChatCols = `c.id, c.tg_chat_id, c.previous_chat_id, c.bot_id, c.chat_kind, c.is_forum, c.title, c.private_tg_user_id,
	c.owner_user_id, c.frame_id, c.feed_id, c.contributors, c.frame_replies, c.notify_reactions, c.offer_app_invite, c.topic_id,
	c.topics, c.status, c.error_code, c.warnings, c.ended_reason, c.ended_by_user_id, c.bot_status, c.bot_joined_at,
	c.attention_pushed, c.welcome_sent_at, c.ready_sent_at, c.spoiler_notice_at, c.last_promo_at, c.last_delivery_at, c.counts,
	c.created_at, c.updated_at, c.ended_at, c.farewell_due`

const telegramChatViewSelect = `SELECT ` + telegramChatCols + `,
	ou.display_name AS owner_name, ou.avatar_key AS owner_avatar_key, ou.locale AS owner_language,
	fr.name AS frame_name, fd.name AS feed_name, eu.display_name AS ended_by_name,
	(SELECT count(*) FROM telegram_contributors x WHERE x.chat_id = c.id)::int AS contributor_n
	FROM telegram_chats c LEFT JOIN users ou ON ou.id = c.owner_user_id LEFT JOIN users eu ON eu.id = c.ended_by_user_id
	LEFT JOIN frames fr ON fr.id = c.frame_id LEFT JOIN feeds fd ON fd.id = c.feed_id`

// CreateTelegramChat inserts a binding with the default settings (everyone
// may contribute, join links offered).
func (tx *Tx) CreateTelegramChat(ctx context.Context, c *TelegramChat) error {
	c.Contributors, c.OfferAppInvite = "everyone", true
	_, err := tx.Exec(ctx, `INSERT INTO telegram_chats (id, tg_chat_id, bot_id, chat_kind, is_forum, title, private_tg_user_id, owner_user_id,
		frame_id, feed_id, status, error_code, bot_status, bot_joined_at, welcome_sent_at, topics, contributors, offer_app_invite)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, '{}', $16, $17)`,
		c.ID, c.TgChatID, c.BotID, c.ChatKind, c.IsForum, c.Title, c.PrivateTgUserID, c.OwnerUserID, c.FrameID, c.FeedID, c.Status,
		c.ErrorCode, c.BotStatus, c.BotJoinedAt, c.WelcomeSentAt, c.Contributors, c.OfferAppInvite)
	return err
}

// SaveTelegramChat writes back the mutable fields of a binding.
func (s *Store) SaveTelegramChat(ctx context.Context, q db.DBTX, c *TelegramChat) error {
	if c.Warnings == nil {
		c.Warnings = []string{}
	}
	if c.Topics == nil {
		c.Topics = map[string]string{}
	}
	if c.AttentionPushed == nil {
		c.AttentionPushed = map[string]string{}
	}
	_, err := q.Exec(ctx, `UPDATE telegram_chats SET tg_chat_id = $2, previous_chat_id = $3, is_forum = $4, title = $5, owner_user_id = $6,
		contributors = $7, frame_replies = $8, notify_reactions = $9, offer_app_invite = $10, topic_id = $11, topics = $12, status = $13,
		error_code = $14, warnings = $15, ended_reason = $16, ended_by_user_id = $17, bot_status = $18, bot_joined_at = $19,
		attention_pushed = $20, welcome_sent_at = $21, ready_sent_at = $22, spoiler_notice_at = $23, last_promo_at = $24,
		last_delivery_at = $25, ended_at = $26, farewell_due = $27, updated_at = now() WHERE id = $1`,
		c.ID, c.TgChatID, c.PreviousChatID, c.IsForum, c.Title, c.OwnerUserID, c.Contributors, c.FrameReplies, c.NotifyReactions,
		c.OfferAppInvite, c.TopicID, c.Topics, c.Status, c.ErrorCode, c.Warnings, c.EndedReason, c.EndedByUserID, c.BotStatus,
		c.BotJoinedAt, c.AttentionPushed, c.WelcomeSentAt, c.ReadySentAt, c.SpoilerNoticeAt, c.LastPromoAt, c.LastDeliveryAt,
		c.EndedAt, c.FarewellDue)
	return err
}

// GetTelegramChat returns a binding with names.
func (s *Store) GetTelegramChat(ctx context.Context, id uuid.UUID) (*TelegramChatView, error) {
	return one[TelegramChatView](s.Pool.Query(ctx, telegramChatViewSelect+` WHERE c.id = $1`, id))
}

// LockTelegramChat returns a binding locked for update.
func (tx *Tx) LockTelegramChat(ctx context.Context, id uuid.UUID) (*TelegramChat, error) {
	return one[TelegramChat](tx.Query(ctx, `SELECT `+telegramChatCols+` FROM telegram_chats c WHERE c.id = $1 FOR UPDATE`, id))
}

// LiveTelegramChat finds the live binding of a Telegram chat id, also by
// its id before a supergroup migration.
func (s *Store) LiveTelegramChat(ctx context.Context, q db.DBTX, tgChatID int64) (*TelegramChatView, error) {
	return one[TelegramChatView](q.Query(ctx, telegramChatViewSelect+` WHERE c.status <> 'disconnected'
		AND (c.tg_chat_id = $1 OR c.previous_chat_id = $1) ORDER BY (c.tg_chat_id = $1) DESC LIMIT 1`, tgChatID))
}

// ListTelegramChats lists a frame's or feed's chats: live ones first, then
// those disconnected since `since`.
func (s *Store) ListTelegramChats(ctx context.Context, frameID, feedID *uuid.UUID, since time.Time) ([]TelegramChatView, error) {
	return many[TelegramChatView](s.Pool.Query(ctx, telegramChatViewSelect+`
		WHERE (($1::uuid IS NOT NULL AND c.frame_id = $1) OR ($2::uuid IS NOT NULL AND c.feed_id = $2))
			AND (c.status <> 'disconnected' OR c.ended_at > $3)
		ORDER BY (c.status = 'disconnected'), c.created_at DESC`, frameID, feedID, since))
}

// CountTelegramChats counts live chats of a target and live chats an account owns.
func (s *Store) CountTelegramChats(ctx context.Context, q db.DBTX, frameID, feedID *uuid.UUID, owner uuid.UUID) (target, owned int, err error) {
	err = q.QueryRow(ctx, `SELECT
		count(*) FILTER (WHERE ($1::uuid IS NOT NULL AND frame_id = $1) OR ($2::uuid IS NOT NULL AND feed_id = $2))::int,
		count(*) FILTER (WHERE owner_user_id = $3)::int
		FROM telegram_chats WHERE status <> 'disconnected'`, frameID, feedID, owner).Scan(&target, &owned)
	return
}

// TelegramChatsOwnedOrTargeting lists live chats of a frame or feed.
func (s *Store) LiveTelegramChatsOf(ctx context.Context, frameID, feedID *uuid.UUID) ([]TelegramChat, error) {
	return many[TelegramChat](s.Pool.Query(ctx, `SELECT `+telegramChatCols+` FROM telegram_chats c WHERE c.status <> 'disconnected'
		AND (($1::uuid IS NOT NULL AND c.frame_id = $1) OR ($2::uuid IS NOT NULL AND c.feed_id = $2))`, frameID, feedID))
}

// FarewellTelegramChats lists chats whose target was deleted and that still owe a goodbye.
func (s *Store) FarewellTelegramChats(ctx context.Context) ([]TelegramChatView, error) {
	return many[TelegramChatView](s.Pool.Query(ctx, telegramChatViewSelect+` WHERE c.farewell_due LIMIT 50`))
}

// BumpTelegramCounts adds to a chat's counts (sent, duplicates, skipped, failed).
func (s *Store) BumpTelegramCounts(ctx context.Context, q db.DBTX, chatID uuid.UUID, key string, n int) error {
	_, err := q.Exec(ctx, `UPDATE telegram_chats SET counts = jsonb_set(counts, ARRAY[$2::text],
		to_jsonb(COALESCE((counts->>$2)::int, 0) + $3)) WHERE id = $1`, chatID, key, n)
	return err
}

// ExpiredTelegramChats lists the disconnected chats PruneTelegram deletes.
func (s *Store) ExpiredTelegramChats(ctx context.Context, now time.Time) ([]TelegramChatView, error) {
	return many[TelegramChatView](s.Pool.Query(ctx, telegramChatViewSelect+` WHERE c.status = 'disconnected' AND NOT c.farewell_due
		AND c.ended_at < $1::timestamptz - interval '30 days'`, now))
}

// PruneTelegram deletes processed updates after 48 hours, contributions after
// 90 days, disconnected chats after 30 days and old codes (§16).
func (s *Store) PruneTelegram(ctx context.Context, now time.Time) error {
	for _, q := range []string{
		`DELETE FROM telegram_updates WHERE state <> 'pending' AND received_at < $1::timestamptz - interval '48 hours'`,
		`DELETE FROM telegram_contributions WHERE created_at < $1::timestamptz - interval '90 days'`,
		`DELETE FROM telegram_chats WHERE status = 'disconnected' AND NOT farewell_due AND ended_at < $1::timestamptz - interval '30 days'`,
		`DELETE FROM telegram_codes WHERE purpose <> 'join' AND (used_at < $1::timestamptz - interval '7 days'
			OR revoked_at < $1::timestamptz - interval '7 days' OR expires_at < $1::timestamptz - interval '7 days')`,
		`DELETE FROM telegram_codes WHERE purpose = 'join' AND expires_at < $1::timestamptz - interval '30 days'`,
		`DELETE FROM telegram_strays WHERE leave_at < $1::timestamptz - interval '1 day'`,
	} {
		if _, err := s.Pool.Exec(ctx, q, now); err != nil {
			return err
		}
	}
	return nil
}

// ---------------------------------------------------------------- strays

// TelegramStray is a group the bot was added to without a code.
type TelegramStray struct {
	TgChatID int64      `db:"tg_chat_id"`
	NoticeAt *time.Time `db:"notice_at"`
	LeaveAt  time.Time  `db:"leave_at"`
}

// PutTelegramStray records a group joined without a code.
func (s *Store) PutTelegramStray(ctx context.Context, chat int64, noticeAt, leaveAt time.Time) error {
	_, err := s.Pool.Exec(ctx, `INSERT INTO telegram_strays (tg_chat_id, notice_at, leave_at) VALUES ($1, $2, $3)
		ON CONFLICT (tg_chat_id) DO UPDATE SET notice_at = EXCLUDED.notice_at, leave_at = EXCLUDED.leave_at`, chat, noticeAt, leaveAt)
	return err
}

// DeleteTelegramStray forgets a stray (a code arrived, or the bot left).
func (s *Store) DeleteTelegramStray(ctx context.Context, chat int64) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM telegram_strays WHERE tg_chat_id = $1`, chat)
	return err
}

// DueTelegramStrays returns strays whose notice or departure is due.
func (s *Store) DueTelegramStrays(ctx context.Context, now time.Time) ([]TelegramStray, error) {
	return many[TelegramStray](s.Pool.Query(ctx, `SELECT tg_chat_id, notice_at, leave_at FROM telegram_strays
		WHERE notice_at <= $1 OR leave_at <= $1 LIMIT 50`, now))
}

// TelegramStrayNoticed clears a stray's notice time.
func (s *Store) TelegramStrayNoticed(ctx context.Context, chat int64) error {
	_, err := s.Pool.Exec(ctx, `UPDATE telegram_strays SET notice_at = NULL WHERE tg_chat_id = $1`, chat)
	return err
}

// ---------------------------------------------------------------- links

// TelegramLink ties a Telegram user to a kinframe account.
type TelegramLink struct {
	TgUserID     int64     `db:"tg_user_id"`
	UserID       uuid.UUID `db:"user_id"`
	DisplayName  string    `db:"display_name"`
	Username     *string   `db:"username"`
	LanguageCode *string   `db:"language_code"`
	CanMessage   bool      `db:"can_message"`
	LinkedAt     time.Time `db:"linked_at"`
	// The kinframe account.
	UserName   string  `db:"user_name"`
	UserLocale *string `db:"user_locale"`
}

const telegramLinkSelect = `SELECT l.tg_user_id, l.user_id, l.display_name, l.username, l.language_code, l.can_message, l.linked_at,
	u.display_name AS user_name, u.locale AS user_locale FROM telegram_links l JOIN users u ON u.id = l.user_id`

// TelegramLinkByTg returns the link of a Telegram user (deleted accounts don't count).
func (s *Store) TelegramLinkByTg(ctx context.Context, q db.DBTX, tgUserID int64) (*TelegramLink, error) {
	return one[TelegramLink](q.Query(ctx, telegramLinkSelect+` WHERE l.tg_user_id = $1 AND u.deleted_at IS NULL`, tgUserID))
}

// TelegramLinkOf returns an account's link.
func (s *Store) TelegramLinkOf(ctx context.Context, userID uuid.UUID) (*TelegramLink, error) {
	return one[TelegramLink](s.Pool.Query(ctx, telegramLinkSelect+` WHERE l.user_id = $1`, userID))
}

// LinkTelegram links a Telegram user to an account. ok is false when either
// side is linked to someone else already.
func (s *Store) LinkTelegram(ctx context.Context, q db.DBTX, l TelegramLink) (bool, error) {
	tag, err := q.Exec(ctx, `INSERT INTO telegram_links (tg_user_id, user_id, display_name, username, language_code)
		VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (tg_user_id) DO UPDATE SET display_name = EXCLUDED.display_name, username = EXCLUDED.username,
			language_code = EXCLUDED.language_code, can_message = true
		WHERE telegram_links.user_id = EXCLUDED.user_id`, l.TgUserID, l.UserID, l.DisplayName, l.Username, l.LanguageCode)
	if db.IsUniqueViolation(err, "") {
		return false, nil
	}
	return err == nil && tag.RowsAffected() > 0, err
}

// UnlinkTelegram deletes an account's link; returns whether one existed.
func (s *Store) UnlinkTelegram(ctx context.Context, userID uuid.UUID) (bool, error) {
	tag, err := s.Pool.Exec(ctx, `DELETE FROM telegram_links WHERE user_id = $1`, userID)
	return tag.RowsAffected() > 0, err
}

// SetTelegramCanMessage records whether a linked user's private chat works.
func (s *Store) SetTelegramCanMessage(ctx context.Context, tgUserID int64, can bool) (*uuid.UUID, error) {
	var id uuid.UUID
	err := s.Pool.QueryRow(ctx, `UPDATE telegram_links SET can_message = $2 WHERE tg_user_id = $1 AND can_message <> $2 RETURNING user_id`, tgUserID, can).Scan(&id)
	if db.NotFound(err) {
		return nil, nil
	}
	return &id, err
}

// ---------------------------------------------------------------- contributors

// TelegramContributor is someone whose media the bot saw in a chat.
type TelegramContributor struct {
	ID           uuid.UUID  `db:"id"`
	ChatID       uuid.UUID  `db:"chat_id"`
	TgUserID     int64      `db:"tg_user_id"`
	DisplayName  string     `db:"display_name"`
	LanguageCode *string    `db:"language_code"`
	Blocked      bool       `db:"blocked"`
	PromoSentAt  *time.Time `db:"promo_sent_at"`
	SentCount    int        `db:"sent_count"`
	FirstSeenAt  time.Time  `db:"first_seen_at"`
	LastSeenAt   time.Time  `db:"last_seen_at"`
}

// TelegramContributorView adds the linked account.
type TelegramContributorView struct {
	TelegramContributor
	LinkedUserID   *uuid.UUID `db:"linked_user_id"`
	LinkedUserName *string    `db:"linked_user_name"`
}

const telegramContributorCols = `t.id, t.chat_id, t.tg_user_id, t.display_name, t.language_code, t.blocked, t.promo_sent_at, t.sent_count,
	t.first_seen_at, t.last_seen_at`

// UpsertTelegramContributor records a contributor seen now.
func (s *Store) UpsertTelegramContributor(ctx context.Context, chatID uuid.UUID, tgUserID int64, name string, lang *string, now time.Time) (*TelegramContributor, error) {
	return one[TelegramContributor](s.Pool.Query(ctx, `INSERT INTO telegram_contributors AS t (id, chat_id, tg_user_id, display_name, language_code,
		first_seen_at, last_seen_at) VALUES ($1, $2, $3, $4, $5, $6, $6)
		ON CONFLICT (chat_id, tg_user_id) DO UPDATE SET display_name = EXCLUDED.display_name,
			language_code = COALESCE(EXCLUDED.language_code, t.language_code), last_seen_at = EXCLUDED.last_seen_at
		RETURNING `+telegramContributorCols, NewID(), chatID, tgUserID, name, lang, now))
}

// GetTelegramContributor returns a contributor of a chat.
func (s *Store) GetTelegramContributor(ctx context.Context, chatID, id uuid.UUID) (*TelegramContributorView, error) {
	return one[TelegramContributorView](s.Pool.Query(ctx, `SELECT `+telegramContributorCols+`, l.user_id AS linked_user_id, u.display_name AS linked_user_name
		FROM telegram_contributors t LEFT JOIN telegram_links l ON l.tg_user_id = t.tg_user_id LEFT JOIN users u ON u.id = l.user_id
		WHERE t.chat_id = $1 AND t.id = $2`, chatID, id))
}

// TelegramContributorByID returns a contributor by its id.
func (s *Store) TelegramContributorByID(ctx context.Context, id uuid.UUID) (*TelegramContributor, error) {
	return one[TelegramContributor](s.Pool.Query(ctx, `SELECT `+telegramContributorCols+` FROM telegram_contributors t WHERE t.id = $1`, id))
}

// ListTelegramContributors lists a chat's contributors, most recent first.
func (s *Store) ListTelegramContributors(ctx context.Context, chatID uuid.UUID) ([]TelegramContributorView, error) {
	return many[TelegramContributorView](s.Pool.Query(ctx, `SELECT `+telegramContributorCols+`, l.user_id AS linked_user_id, u.display_name AS linked_user_name
		FROM telegram_contributors t LEFT JOIN telegram_links l ON l.tg_user_id = t.tg_user_id LEFT JOIN users u ON u.id = l.user_id
		WHERE t.chat_id = $1 ORDER BY t.last_seen_at DESC, t.id`, chatID))
}

// SetTelegramContributorBlocked blocks or unblocks a contributor.
func (s *Store) SetTelegramContributorBlocked(ctx context.Context, id uuid.UUID, blocked bool) error {
	_, err := s.Pool.Exec(ctx, `UPDATE telegram_contributors SET blocked = $2 WHERE id = $1`, id, blocked)
	return err
}

// ClaimTelegramPromo marks the promotion as sent to a contributor, once per
// contributor and at most once per chat per gap. Reports whether it may be sent now.
func (s *Store) ClaimTelegramPromo(ctx context.Context, chatID, contributorID uuid.UUID, now time.Time, gap time.Duration) (bool, error) {
	ok := false
	err := db.InTx(ctx, s.Pool, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, `UPDATE telegram_chats SET last_promo_at = $2 WHERE id = $1 AND (last_promo_at IS NULL OR last_promo_at <= $3)`,
			chatID, now, now.Add(-gap))
		if err != nil || tag.RowsAffected() == 0 {
			return err
		}
		tag, err = tx.Exec(ctx, `UPDATE telegram_contributors SET promo_sent_at = $2 WHERE id = $1 AND promo_sent_at IS NULL`, contributorID, now)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return errPromoTaken
		}
		ok = true
		return nil
	})
	if errors.Is(err, errPromoTaken) {
		return false, nil
	}
	return ok, err
}

var errPromoTaken = errors.New("promotion sent already")

// ---------------------------------------------------------------- contributions

// TelegramContribution is one media message (§5.4).
type TelegramContribution struct {
	ChatID        uuid.UUID       `db:"chat_id"`
	MessageID     int64           `db:"message_id"`
	TgChatID      int64           `db:"tg_chat_id"`
	ThreadID      *int64          `db:"thread_id"`
	MediaGroupID  *string         `db:"media_group_id"`
	ContributorID *uuid.UUID      `db:"contributor_id"`
	SenderUserID  *uuid.UUID      `db:"sender_user_id"`
	FileUniqueID  *string         `db:"file_unique_id"`
	State         string          `db:"state"`
	SkipReason    *string         `db:"skip_reason"`
	FailureCode   *string         `db:"failure_code"`
	Attempts      int             `db:"attempts"`
	RetryAt       *time.Time      `db:"retry_at"`
	Media         json.RawMessage `db:"media"`
	Caption       *string         `db:"caption"`
	MessageDate   *time.Time      `db:"message_date"`
	BatchID       *uuid.UUID      `db:"batch_id"`
	ItemID        *uuid.UUID      `db:"item_id"`
	SendID        *uuid.UUID      `db:"send_id"`
	CaptionSource bool            `db:"caption_source"`
	ReactionShown *string         `db:"reaction_shown"`
	RepliedFrames []uuid.UUID     `db:"replied_frames"`
	Closed        bool            `db:"closed"`
	CreatedAt     time.Time       `db:"created_at"`
}

const telegramContributionCols = `chat_id, message_id, tg_chat_id, thread_id, media_group_id, contributor_id, sender_user_id, file_unique_id,
	state, skip_reason, failure_code, attempts, retry_at, media, caption, message_date, batch_id, item_id, send_id, caption_source,
	reaction_shown, replied_frames, closed, created_at`

// PutTelegramContribution inserts or replaces a contribution.
func (s *Store) PutTelegramContribution(ctx context.Context, q db.DBTX, c *TelegramContribution) error {
	if c.RepliedFrames == nil {
		c.RepliedFrames = []uuid.UUID{}
	}
	_, err := q.Exec(ctx, `INSERT INTO telegram_contributions (chat_id, message_id, tg_chat_id, thread_id, media_group_id, contributor_id,
		sender_user_id, file_unique_id, state, skip_reason, failure_code, attempts, retry_at, media, caption, message_date, batch_id,
		item_id, send_id, caption_source, reaction_shown, replied_frames, closed)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23)
		ON CONFLICT (chat_id, message_id) DO UPDATE SET contributor_id = EXCLUDED.contributor_id, sender_user_id = EXCLUDED.sender_user_id,
			file_unique_id = EXCLUDED.file_unique_id, state = EXCLUDED.state, skip_reason = EXCLUDED.skip_reason,
			failure_code = EXCLUDED.failure_code, attempts = EXCLUDED.attempts, retry_at = EXCLUDED.retry_at, media = EXCLUDED.media,
			caption = EXCLUDED.caption, batch_id = EXCLUDED.batch_id, item_id = EXCLUDED.item_id, send_id = EXCLUDED.send_id,
			caption_source = EXCLUDED.caption_source, reaction_shown = EXCLUDED.reaction_shown, replied_frames = EXCLUDED.replied_frames,
			closed = EXCLUDED.closed`,
		c.ChatID, c.MessageID, c.TgChatID, c.ThreadID, c.MediaGroupID, c.ContributorID, c.SenderUserID, c.FileUniqueID, c.State,
		c.SkipReason, c.FailureCode, c.Attempts, c.RetryAt, nullJSON(c.Media), c.Caption, c.MessageDate, c.BatchID, c.ItemID, c.SendID,
		c.CaptionSource, c.ReactionShown, c.RepliedFrames, c.Closed)
	return err
}

func nullJSON(b json.RawMessage) any {
	if len(b) == 0 {
		return nil
	}
	return b
}

// GetTelegramContribution returns a contribution.
func (s *Store) GetTelegramContribution(ctx context.Context, q db.DBTX, chatID uuid.UUID, messageID int64) (*TelegramContribution, error) {
	return one[TelegramContribution](q.Query(ctx, `SELECT `+telegramContributionCols+` FROM telegram_contributions WHERE chat_id = $1 AND message_id = $2`, chatID, messageID))
}

// TelegramContributionsWhere lists contributions matching a condition (package-internal callers only).
func (s *Store) telegramContributions(ctx context.Context, q db.DBTX, where string, args ...any) ([]TelegramContribution, error) {
	return many[TelegramContribution](q.Query(ctx, `SELECT `+telegramContributionCols+` FROM telegram_contributions WHERE `+where, args...))
}

// TelegramAlbum lists the contributions of a media group in a chat, in message order.
func (s *Store) TelegramAlbum(ctx context.Context, q db.DBTX, chatID uuid.UUID, mediaGroupID string) ([]TelegramContribution, error) {
	return s.telegramContributions(ctx, q, `chat_id = $1 AND media_group_id = $2 ORDER BY message_id`, chatID, mediaGroupID)
}

// TelegramBatchContributions lists a batch's contributions in message order.
func (s *Store) TelegramBatchContributions(ctx context.Context, q db.DBTX, batchID uuid.UUID) ([]TelegramContribution, error) {
	return s.telegramContributions(ctx, q, `batch_id = $1 ORDER BY message_id`, batchID)
}

// TelegramContributionsOfItem lists live (not closed) contributions holding an item.
func (s *Store) TelegramContributionsOfItem(ctx context.Context, itemID uuid.UUID) ([]TelegramContribution, error) {
	return s.telegramContributions(ctx, s.Pool, `item_id = $1 AND NOT closed`, itemID)
}

// DueTelegramRetries lists contributions whose download is due again.
func (s *Store) DueTelegramRetries(ctx context.Context, now time.Time) ([]TelegramContribution, error) {
	return s.telegramContributions(ctx, s.Pool, `state = 'collecting' AND item_id IS NULL AND retry_at <= $1 ORDER BY retry_at LIMIT 20`, now)
}

// TelegramFileInTarget finds a live contribution with the same Telegram file in
// any chat of the same frame or feed (§8.6 step 2). Returns its item.
func (s *Store) TelegramFileInTarget(ctx context.Context, frameID, feedID *uuid.UUID, fileUniqueID string) (*uuid.UUID, error) {
	var id uuid.UUID
	err := s.Pool.QueryRow(ctx, `SELECT t.item_id FROM telegram_contributions t JOIN telegram_chats c ON c.id = t.chat_id
		WHERE t.file_unique_id = $3 AND t.item_id IS NOT NULL AND t.state IN ('collecting', 'sent')
			AND (($1::uuid IS NOT NULL AND c.frame_id = $1) OR ($2::uuid IS NOT NULL AND c.feed_id = $2))
		ORDER BY t.created_at LIMIT 1`, frameID, feedID, fileUniqueID).Scan(&id)
	if db.NotFound(err) {
		return nil, nil
	}
	return &id, err
}

// TelegramChatItemsSince counts media a chat accepted since t (rate limit, §14).
func (s *Store) TelegramChatItemsSince(ctx context.Context, chatID uuid.UUID, since time.Time) (int, error) {
	var n int
	err := s.Pool.QueryRow(ctx, `SELECT count(*)::int FROM telegram_contributions WHERE chat_id = $1 AND created_at > $2
		AND state IN ('collecting', 'sent', 'duplicate')`, chatID, since).Scan(&n)
	return n, err
}

// CloseTelegramContributions stops mirroring reactions for a chat's earlier
// messages (a supergroup migration restarts message ids, §12).
func (s *Store) CloseTelegramContributions(ctx context.Context, q db.DBTX, chatID uuid.UUID) error {
	_, err := q.Exec(ctx, `UPDATE telegram_contributions SET closed = true WHERE chat_id = $1`, chatID)
	return err
}

// LatestTelegramReaction returns the most recent frame reaction on any live
// placement of the items (empty when none).
func (s *Store) LatestTelegramReaction(ctx context.Context, itemIDs []uuid.UUID) (string, error) {
	var r *string
	err := s.Pool.QueryRow(ctx, `SELECT reaction FROM placements WHERE item_id = ANY($1) AND removed_at IS NULL AND reaction IS NOT NULL
		ORDER BY reaction_at DESC NULLS LAST LIMIT 1`, itemIDs).Scan(&r)
	if db.NotFound(err) || r == nil {
		return "", nil
	}
	return *r, err
}

// SetTelegramReactionShown records the emoji the bot set on a message.
func (s *Store) SetTelegramReactionShown(ctx context.Context, chatID uuid.UUID, messageID int64, emoji string) error {
	_, err := s.Pool.Exec(ctx, `UPDATE telegram_contributions SET reaction_shown = $3 WHERE chat_id = $1 AND message_id = $2`, chatID, messageID, emoji)
	return err
}

// ClaimTelegramReply records that a frame's quick reply was posted for a
// message; false when it was already.
func (s *Store) ClaimTelegramReply(ctx context.Context, chatID uuid.UUID, messageID int64, frameID uuid.UUID) (bool, error) {
	tag, err := s.Pool.Exec(ctx, `UPDATE telegram_contributions SET replied_frames = array_append(replied_frames, $3)
		WHERE chat_id = $1 AND message_id = $2 AND NOT ($3 = ANY(replied_frames))`, chatID, messageID, frameID)
	return tag.RowsAffected() > 0, err
}

// ---------------------------------------------------------------- batches

// TelegramBatch is an open batch (§8.4).
type TelegramBatch struct {
	ID            uuid.UUID  `db:"id"`
	ChatID        uuid.UUID  `db:"chat_id"`
	ContributorID uuid.UUID  `db:"contributor_id"`
	SenderUserID  uuid.UUID  `db:"sender_user_id"`
	Outside       bool       `db:"outside"`
	Label         string     `db:"label"`
	OpenedAt      time.Time  `db:"opened_at"`
	LastAt        time.Time  `db:"last_at"`
	AlbumAt       *time.Time `db:"album_at"`
	ClosesAt      time.Time  `db:"closes_at"`
	Items         int        `db:"items"`
}

const telegramBatchCols = `id, chat_id, contributor_id, sender_user_id, outside, label, opened_at, last_at, album_at, closes_at, items`

// OpenTelegramBatch returns the open batch of a contributor in a chat for
// one sender decision, locked.
func (tx *Tx) OpenTelegramBatch(ctx context.Context, chatID, contributorID, sender uuid.UUID, outside bool) (*TelegramBatch, error) {
	return one[TelegramBatch](tx.Query(ctx, `SELECT `+telegramBatchCols+` FROM telegram_batches WHERE chat_id = $1 AND contributor_id = $2
		AND sender_user_id = $3 AND outside = $4 ORDER BY opened_at DESC LIMIT 1 FOR UPDATE`, chatID, contributorID, sender, outside))
}

// SaveTelegramBatch inserts or updates a batch.
func (tx *Tx) SaveTelegramBatch(ctx context.Context, b *TelegramBatch) error {
	_, err := tx.Exec(ctx, `INSERT INTO telegram_batches (`+telegramBatchCols+`) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
		ON CONFLICT (id) DO UPDATE SET last_at = EXCLUDED.last_at, album_at = EXCLUDED.album_at, closes_at = EXCLUDED.closes_at,
			items = EXCLUDED.items, label = EXCLUDED.label`,
		b.ID, b.ChatID, b.ContributorID, b.SenderUserID, b.Outside, b.Label, b.OpenedAt, b.LastAt, b.AlbumAt, b.ClosesAt, b.Items)
	return err
}

// DueTelegramBatches lists batches whose closing time has come or that are
// full (or all of a chat's batches with chatID set), oldest first.
func (s *Store) DueTelegramBatches(ctx context.Context, now time.Time, maxItems int, chatID *uuid.UUID) ([]TelegramBatch, error) {
	return many[TelegramBatch](s.Pool.Query(ctx, `SELECT `+telegramBatchCols+` FROM telegram_batches
		WHERE ($3::uuid IS NOT NULL AND chat_id = $3) OR ($3::uuid IS NULL AND (closes_at <= $1 OR items >= $2))
		ORDER BY opened_at LIMIT 100`, now, maxItems, chatID))
}

// LockTelegramBatch locks a batch (ErrNotFound once another closer took it).
func (tx *Tx) LockTelegramBatch(ctx context.Context, id uuid.UUID) (*TelegramBatch, error) {
	return one[TelegramBatch](tx.Query(ctx, `SELECT `+telegramBatchCols+` FROM telegram_batches WHERE id = $1 FOR UPDATE SKIP LOCKED`, id))
}

// DeleteTelegramBatch removes a closed batch.
func (tx *Tx) DeleteTelegramBatch(ctx context.Context, id uuid.UUID) error {
	_, err := tx.Exec(ctx, `DELETE FROM telegram_batches WHERE id = $1`, id)
	return err
}

// ---------------------------------------------------------------- sends

// CreateTelegramSend creates the send of a closed batch with its items in
// order (sends.via = telegram). outside: sent by the chat's owner on the
// contributor's behalf.
func (tx *Tx) CreateTelegramSend(ctx context.Context, id, sender, chatID uuid.UUID, label string, outside bool, items []uuid.UUID) error {
	if _, err := tx.Exec(ctx, `INSERT INTO sends (id, sender_user_id, via, via_label, telegram_chat_id, telegram_outside)
		VALUES ($1, $2, 'telegram', $3, $4, $5)`, id, sender, label, chatID, outside); err != nil {
		return err
	}
	for i, it := range items {
		if err := tx.AddSendItem(ctx, id, it, i); err != nil {
			return err
		}
	}
	return nil
}

// SetTelegramItemCredit marks an item as posted in a Telegram chat.
func (tx *Tx) SetTelegramItemCredit(ctx context.Context, itemID, chatID uuid.UUID, label string) error {
	_, err := tx.Exec(ctx, `UPDATE items SET via = 'telegram', via_label = $3, via_telegram_chat_id = $2 WHERE id = $1`, itemID, chatID, label)
	return err
}

// TelegramReactionsOn reports whether reaction notifications for an item go
// to its sender: false for an outside Telegram contribution of a chat with
// notify_reactions off (§10.2).
func (s *Store) TelegramReactionsOn(ctx context.Context, itemID uuid.UUID) (bool, error) {
	var on bool
	err := s.Pool.QueryRow(ctx, `SELECT COALESCE((SELECT c.notify_reactions FROM send_items si JOIN sends sd ON sd.id = si.send_id
		JOIN telegram_chats c ON c.id = sd.telegram_chat_id WHERE si.item_id = $1 AND sd.via = 'telegram' AND sd.telegram_outside LIMIT 1), true)`, itemID).Scan(&on)
	return on, err
}

// TelegramTargetOwner returns the owner of a frame or feed (§4.4).
func (s *Store) TelegramTargetOwner(ctx context.Context, frameID, feedID *uuid.UUID) (uuid.UUID, error) {
	var id uuid.UUID
	var err error
	if frameID != nil {
		err = s.Pool.QueryRow(ctx, `SELECT user_id FROM memberships WHERE frame_id = $1 AND role = 'owner'`, *frameID).Scan(&id)
	} else {
		err = s.Pool.QueryRow(ctx, `SELECT user_id FROM feed_members WHERE feed_id = $1 AND role = 'owner'`, *feedID).Scan(&id)
	}
	if db.NotFound(err) {
		return id, ErrNotFound
	}
	return id, err
}
