// Telegram bridge (docs/spec/telegram.md, README §5.17): deep links, status and warning texts, code errors.
// Pure functions; the components only render what these return. "Telegram", BotFather and the bot's commands are
// names and stay untranslated.
import type {
	TelegramChat,
	TelegramChatEndedReason,
	TelegramChatKind,
	TelegramChatWarning,
	TelegramCode,
	TelegramCodeErrorCode
} from './api/client';
import { relativeTime } from './format';
import { t } from './i18n/index.svelte';

// ---------------------------------------------------------------- links

const BOT_RE = /^[A-Za-z][A-Za-z0-9_]{3,31}$/;
const CODE_RE = /^[A-Za-z0-9_-]{1,64}$/;

/** The bot's page: `https://t.me/<bot>`; null for a malformed username. */
export function botUrl(bot: string | null | undefined): string | null {
	const b = bot?.replace(/^@/, '').trim();
	return b && BOT_RE.test(b) ? `https://t.me/${b}` : null;
}

/**
 * The deep link for a code: `https://t.me/<bot>?startgroup=<code>` picks a group and adds the bot,
 * `?start=<code>` opens a private chat with it (telegram.md §5.1).
 */
export function deepLink(bot: string | null | undefined, code: string, kind: TelegramChatKind): string | null {
	const base = botUrl(bot);
	if (!base || !CODE_RE.test(code)) return null;
	return `${base}?${kind === 'group' ? 'startgroup' : 'start'}=${code}`;
}

/**
 * The link to open for a created code: the server's `url` when it is a t.me link, else one built from the code
 * and the bot in discovery.
 */
export function codeLink(code: Pick<TelegramCode, 'url' | 'code' | 'chat_kind' | 'purpose'>, bot: string | null | undefined): string | null {
	if (code.url && /^https:\/\/t\.me\//.test(code.url)) return code.url;
	if (!code.code) return null;
	return deepLink(bot, code.code, code.purpose === 'link' ? 'private' : (code.chat_kind ?? 'group'));
}

/** The private-chat variant of a group link (`startgroup` → `start`), for "Open in a private chat instead". */
export function privateVariant(url: string): string {
	return url.replace('?startgroup=', '?start=');
}

/** "@kinframe_bot", for texts. */
export function botHandle(bot: string | null | undefined): string {
	const b = bot?.replace(/^@/, '').trim();
	return b ? `@${b}` : '';
}

// ---------------------------------------------------------------- codes

export type CodeKind = 'group' | 'private_self' | 'private_invite' | 'link';

export function codeBody(kind: CodeKind): { chat_kind: TelegramChatKind; link_account?: boolean } {
	if (kind === 'group') return { chat_kind: 'group' };
	if (kind === 'private_self') return { chat_kind: 'private', link_account: true };
	return { chat_kind: 'private' };
}

/** Whether a followed code is finished (used, expired, revoked) or still waiting for Telegram. */
export function codeDone(c: Pick<TelegramCode, 'status'>): boolean {
	return c.status !== 'open';
}

/** Why redeeming failed, as the app says it (telegram.md §15.2); null without an error. */
export function codeErrorText(code: TelegramCodeErrorCode | null | undefined, targetName = ''): string | null {
	switch (code) {
		case null:
		case undefined:
			return null;
		case 'code_expired':
			return t('telegram.codeErr.code_expired');
		case 'code_used':
			return t('telegram.codeErr.code_used');
		case 'already_connected':
			return t('telegram.codeErr.already_connected');
		case 'limit_reached':
			return t('telegram.codeErr.limit_reached', { name: targetName });
		case 'wrong_chat_kind':
			return t('telegram.codeErr.wrong_chat_kind');
		case 'already_linked':
			return t('telegram.codeErr.already_linked');
		case 'missing_permission':
			return t('telegram.codeErr.missing_permission');
		default:
			return t('telegram.codeErr.other');
	}
}

/** Minutes left on an open code, at least 0. */
export function minutesLeft(expiresAt: string, now = new Date()): number {
	return Math.max(0, Math.ceil((Date.parse(expiresAt) - now.getTime()) / 60_000));
}

// ---------------------------------------------------------------- chats

export type ChatTone = 'ok' | 'attention' | 'paused' | 'ended';

export interface ChatView {
	tone: ChatTone;
	label: string;
	/** The fix for `needs_attention`, or what ended it. */
	detail: string | null;
}

export function chatView(c: TelegramChat, locale = 'en', now = new Date()): ChatView {
	switch (c.status) {
		case 'active':
			return {
				tone: 'ok',
				label: c.last_delivery_at ? t('telegram.statusActiveLast', { when: relativeTime(c.last_delivery_at, locale, now) }) : t('telegram.statusActive'),
				detail: null
			};
		case 'paused':
			return { tone: 'paused', label: t('telegram.statusPaused'), detail: null };
		case 'needs_attention':
			return { tone: 'attention', label: t('telegram.statusAttention'), detail: attentionText(c) };
		default:
			return { tone: 'ended', label: t('telegram.statusDisconnected'), detail: endedText(c.ended_reason, c.ended_by_name, c.title) };
	}
}

/** The fix for a chat that needs attention (telegram.md §15.1). */
export function attentionText(c: Pick<TelegramChat, 'error_code' | 'title' | 'sees_all_messages'>): string {
	if (c.error_code === 'bot_changed') return t('telegram.err.bot_changed');
	return t('telegram.err.privacy_mode', { name: c.title });
}

/** Whether the "can the bot see the photos" fix applies (privacy mode). */
export function needsPrivacyFix(c: Pick<TelegramChat, 'status' | 'error_code' | 'sees_all_messages'>): boolean {
	return (c.status === 'needs_attention' && c.error_code === 'privacy_mode') || (c.status !== 'disconnected' && c.sees_all_messages === false);
}

export function warningText(w: TelegramChatWarning): string {
	return w === 'cannot_post' ? t('telegram.warn.cannot_post') : t('telegram.warn.reactions_off');
}

export function endedText(reason: TelegramChatEndedReason | null | undefined, by: string | null | undefined, title = ''): string | null {
	const name = by?.trim();
	switch (reason) {
		case 'disconnected':
			return name ? t('telegram.ended.disconnectedBy', { name }) : t('telegram.ended.disconnected');
		case 'stopped':
			return name ? t('telegram.ended.stoppedBy', { name }) : t('telegram.ended.stopped');
		case 'bot_removed':
			return t('telegram.ended.bot_removed');
		case 'chat_gone':
			return t('telegram.ended.chat_gone');
		case 'bot_blocked':
			return t('telegram.ended.bot_blocked', { name: title });
		case 'target_deleted':
		default:
			return null;
	}
}

/** Live chats first (attention, active, paused), newest first within each; disconnected ones last ("Earlier"). */
export function sortChats(list: TelegramChat[]): TelegramChat[] {
	const rank: Record<TelegramChat['status'], number> = { needs_attention: 0, active: 1, paused: 2, disconnected: 3 };
	return [...list].sort((a, b) => rank[a.status] - rank[b.status] || Date.parse(b.created_at) - Date.parse(a.created_at));
}

export function isLive(c: Pick<TelegramChat, 'status'>): boolean {
	return c.status !== 'disconnected';
}

export function chatBelongsTo(c: Pick<TelegramChat, 'target'>, target: { kind: 'frame' | 'feed'; id: string }): boolean {
	return target.kind === 'frame' ? c.target.frame_id === target.id : c.target.feed_id === target.id;
}

/** "@carla" or the display name, for the linked account. */
export function linkLabel(l: { display_name: string; username?: string | null }): string {
	return l.username ? `${l.display_name} (@${l.username})` : l.display_name;
}
