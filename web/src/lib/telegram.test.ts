import { describe, expect, it } from 'vitest';
import type { TelegramChat } from './api/client';
import { attentionText, botUrl, chatView, codeBody, codeErrorText, codeLink, deepLink, endedText, minutesLeft, needsPrivacyFix, privateVariant, sortChats, warningText } from './telegram';

const now = new Date('2026-11-19T12:00:00Z');

function chat(p: Partial<TelegramChat> = {}): TelegramChat {
	return {
		id: 'c1',
		chat_kind: 'group',
		title: 'Peeters family',
		target: { kind: 'frame', frame_id: 'f1', frame_name: 'Oma’s frame' },
		owner: { user_id: 'u1', display_name: 'Anna' },
		status: 'active',
		contributors: 'everyone',
		frame_replies: false,
		notify_reactions: false,
		offer_app_invite: true,
		counts: { sent: 12, contributors: 3 },
		created_at: '2026-11-01T10:00:00Z',
		...p
	};
}

describe('deep links', () => {
	it('builds t.me links for groups and private chats', () => {
		expect(botUrl('kinframe_bot')).toBe('https://t.me/kinframe_bot');
		expect(botUrl('@kinframe_bot')).toBe('https://t.me/kinframe_bot');
		expect(botUrl('no')).toBeNull();
		expect(botUrl('bad/../name')).toBeNull();
		expect(deepLink('kinframe_bot', 'cA7f9QxK2mPz4LwN8', 'group')).toBe('https://t.me/kinframe_bot?startgroup=cA7f9QxK2mPz4LwN8');
		expect(deepLink('kinframe_bot', 'pA7f9QxK2mPz4LwN8', 'private')).toBe('https://t.me/kinframe_bot?start=pA7f9QxK2mPz4LwN8');
		expect(deepLink('kinframe_bot', 'bad code!', 'private')).toBeNull();
		expect(privateVariant('https://t.me/kinframe_bot?startgroup=c1')).toBe('https://t.me/kinframe_bot?start=c1');
	});
	it('prefers the server’s url and falls back to one built from the code', () => {
		const base = { purpose: 'connect' as const, chat_kind: 'group' as const };
		expect(codeLink({ ...base, url: 'https://t.me/other_bot?startgroup=c1', code: 'c1' }, 'kinframe_bot')).toBe('https://t.me/other_bot?startgroup=c1');
		expect(codeLink({ ...base, url: null, code: 'c1' }, 'kinframe_bot')).toBe('https://t.me/kinframe_bot?startgroup=c1');
		expect(codeLink({ purpose: 'link', url: null, code: 'l1' }, 'kinframe_bot')).toBe('https://t.me/kinframe_bot?start=l1');
		expect(codeLink({ ...base, url: 'javascript:alert(1)', code: null }, 'kinframe_bot')).toBeNull();
	});
	it('asks for the right kind of code per entry point', () => {
		expect(codeBody('group')).toEqual({ chat_kind: 'group' });
		expect(codeBody('private_self')).toEqual({ chat_kind: 'private', link_account: true });
		expect(codeBody('private_invite')).toEqual({ chat_kind: 'private' });
	});
	it('counts the minutes a code has left', () => {
		expect(minutesLeft('2026-11-19T12:29:10Z', now)).toBe(30);
		expect(minutesLeft('2026-11-19T11:00:00Z', now)).toBe(0);
	});
});

describe('status texts', () => {
	it('shows a connected chat and when the last photo came', () => {
		expect(chatView(chat(), 'en', now)).toEqual({ tone: 'ok', label: 'Connected', detail: null });
		expect(chatView(chat({ last_delivery_at: '2026-11-19T11:58:00Z' }), 'en', now).label).toBe('Connected · last photo 2 minutes ago');
		expect(chatView(chat({ status: 'paused' }), 'en', now).label).toBe('Paused');
	});
	it('gives the fix for privacy mode and a changed bot', () => {
		const c = chat({ status: 'needs_attention', error_code: 'privacy_mode', sees_all_messages: false });
		const v = chatView(c, 'en', now);
		expect(v.tone).toBe('attention');
		expect(v.detail).toBe(
			'The bot only sees commands in Peeters family. Make it an admin of the group (Group info → Administrators), or ask the server’s operator to turn off privacy mode.'
		);
		expect(needsPrivacyFix(c)).toBe(true);
		expect(needsPrivacyFix(chat())).toBe(false);
		expect(attentionText(chat({ error_code: 'bot_changed' }))).toBe('This server uses a different Telegram bot now. Connect the group again.');
	});
	it('says why a chat ended', () => {
		expect(endedText('disconnected', 'Anna')).toBe('Disconnected by Anna');
		expect(endedText('stopped', 'Ben')).toBe('Disconnected with /stop by Ben');
		expect(endedText('bot_removed', null)).toBe('The bot was removed from the group');
		expect(endedText('chat_gone', null)).toBe('The group no longer exists');
		expect(endedText('bot_blocked', null, 'Opa')).toBe('Opa blocked the bot');
		expect(endedText('target_deleted', null)).toBeNull();
		expect(chatView(chat({ status: 'disconnected', ended_reason: 'disconnected', ended_by_name: 'Anna' }), 'en', now)).toEqual({ tone: 'ended', label: 'Disconnected', detail: 'Disconnected by Anna' });
	});
	it('words warnings and code errors', () => {
		expect(warningText('reactions_off')).toBe('Reactions are turned off in this group, so the bot confirms photos silently.');
		expect(warningText('cannot_post')).toContain('isn’t allowed to post');
		expect(codeErrorText('code_expired')).toBe('This link has expired. Make a new one.');
		expect(codeErrorText('limit_reached', 'Oma’s frame')).toBe('Oma’s frame already has as many Telegram chats as allowed.');
		expect(codeErrorText(null)).toBeNull();
	});
	it('lists chats that need attention first and disconnected ones last', () => {
		const list = sortChats([chat({ id: 'd', status: 'disconnected' }), chat({ id: 'a' }), chat({ id: 'n', status: 'needs_attention' }), chat({ id: 'p', status: 'paused' })]);
		expect(list.map((c) => c.id)).toEqual(['n', 'a', 'p', 'd']);
	});
});
