import { expect, test, type Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { defaultSettings, emptyState, makeFrame, mockApi, push, uid, type MockState } from './mock-api';
import { feedPostsOnTimeline } from './mock-social';

// Telegram bridge (docs/spec/telegram.md, README §5.17): connecting a group with a t.me deep link and QR code, chat
// status and settings, contributors, disconnecting, and "Link my Telegram", against the in-memory mock API.

type S = components['schemas'];
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();

function tgState(on = true) {
	const st = emptyState();
	st.discovery.feeds = true;
	st.discovery.telegram = on;
	st.discovery.telegram_bot_username = on ? 'kinframe_bot' : null;
	st.discovery.telegram_privacy_mode = on;
	st.me = { id: uid('u'), display_name: 'Anna', email: 'anna@example.org', avatar_url: null, locale: 'en', created_at: ago(9999) };
	const oma = makeFrame({ name: 'Oma', role: 'owner' });
	st.frames = [oma];
	st.settings[oma.id] = defaultSettings(1);
	const feed: S['Feed'] = { id: uid('fd'), name: 'Peeters kids', cover_url: null, role: 'owner', member_count: 2, subscription_count: 3, created_at: ago(9000), owner_name: 'Anna' };
	st.feeds = [feed];
	st.feedPosts[feed.id] = [];
	return { st, oma, feed };
}

async function signIn(page: Page, st: MockState) {
	await mockApi(page, st);
	await page.goto('/');
	await page.getByLabel('Email').fill('anna@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/(frames|home)$/);
}

function chatFor(st: MockState, frame: S['Frame'], p: Partial<S['TelegramChat']> = {}): S['TelegramChat'] {
	const c: S['TelegramChat'] = {
		id: uid('tg'),
		chat_kind: 'group',
		title: 'Peeters family',
		target: { kind: 'frame', frame_id: frame.id, frame_name: frame.name },
		owner: { user_id: st.me!.id, display_name: 'Anna' },
		status: 'active',
		warnings: [],
		bot_is_admin: true,
		sees_all_messages: true,
		contributors: 'everyone',
		frame_replies: false,
		notify_reactions: false,
		offer_app_invite: true,
		counts: { sent: 12, contributors: 2 },
		last_delivery_at: ago(3),
		can_manage: true,
		is_owner: true,
		created_at: ago(900),
		...p
	};
	st.telegramChats.push(c);
	return c;
}

test('everything stays hidden when the server has no Telegram bot', async ({ page }) => {
	const { st, oma, feed } = tgState(false);
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=settings`);
	await expect(page.getByRole('heading', { name: 'Slideshow' }).first()).toBeVisible();
	await expect(page.getByTestId('telegram-section')).toHaveCount(0);
	await page.goto(`/feeds/${feed.id}?tab=sources`);
	await expect(page.getByRole('heading', { name: 'More ways to add photos' })).toBeVisible();
	await expect(page.getByTestId('telegram-section')).toHaveCount(0);
	await page.goto('/settings');
	await expect(page.getByRole('heading', { name: 'Profile' })).toBeVisible();
	await expect(page.getByTestId('telegram-link')).toHaveCount(0);
	expect(st.calls.some((c) => c.path.includes('telegram'))).toBe(false);
});

test('connects a group: deep link and QR code, then live status until it is connected', async ({ page }) => {
	const { st, oma } = tgState();
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=settings`);
	const section = page.getByTestId('telegram-section');
	await expect(section).toContainText('Photos posted in a Telegram group or sent to the bot appear on Oma.');
	await section.getByRole('button', { name: 'Connect a Telegram group' }).click();

	const dialog = page.getByRole('dialog');
	await expect(dialog).toContainText('Photos posted in the group appear on Oma. Everyone in the group can send.');
	await expect(dialog).toContainText('make the bot an admin of the group');
	const open = dialog.getByTestId('telegram-open');
	await expect(open).toHaveAttribute('href', 'https://t.me/kinframe_bot?startgroup=cA7f9QxK2mPz4LwN8');
	await expect(open).toHaveAttribute('target', '_blank');
	await expect(dialog.getByRole('img', { name: 'QR code for the Telegram link' })).toBeVisible();
	await expect(dialog).toContainText('Waiting for Telegram…');
	const create = st.calls.find((c) => c.method === 'POST' && c.path === `/v1/frames/${oma.id}/telegram-codes`)!;
	expect(create.body).toEqual({ chat_kind: 'group' });

	// Telegram redeems the code: the bot joins the group.
	const code = st.telegramCodes[0]!;
	const chat = chatFor(st, oma, { counts: { sent: 0, contributors: 0 }, last_delivery_at: null });
	Object.assign(code, { status: 'used', chat_id: chat.id });
	push(st, { type: 'telegram_code.updated', code: { ...code, url: null, code: null } });
	await expect(dialog.getByTestId('telegram-done')).toHaveText('Connected to Peeters family');
	await dialog.getByRole('button', { name: 'Done' }).click();
	const card = section.getByTestId('telegram-chat');
	await expect(card).toContainText('Peeters family');
	await expect(card).toContainText('Connected');
	await expect(card).toContainText('Connected by you');

	// A later status change arrives over the websocket.
	push(st, { type: 'telegram_chat.updated', chat: { ...chat, warnings: ['reactions_off'] } });
	await expect(card).toContainText('Reactions are turned off in this group, so the bot confirms photos silently.');
});

test('the private-chat entry points use start links; an expired link offers a new one', async ({ page }) => {
	const { st, oma } = tgState();
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=settings`);
	await page.getByRole('button', { name: 'Send from my Telegram' }).click();
	let dialog = page.getByRole('dialog');
	await expect(dialog).toContainText('Telegram opens a chat with @kinframe_bot.');
	await expect(dialog.getByTestId('telegram-open')).toHaveAttribute('href', 'https://t.me/kinframe_bot?start=pA7f9QxK2mPz4LwN8');
	expect(st.calls.find((c) => c.method === 'POST' && c.path.endsWith('/telegram-codes'))!.body).toEqual({ chat_kind: 'private', link_account: true });

	const code = st.telegramCodes[0]!;
	Object.assign(code, { status: 'expired', error_code: 'code_expired' });
	push(st, { type: 'telegram_code.updated', code });
	await expect(dialog).toContainText('This link has expired. Make a new one.');
	await dialog.getByRole('button', { name: 'New link' }).click();
	await expect(dialog.getByTestId('telegram-open')).toBeVisible();
	await page.keyboard.press('Escape');

	await page.getByRole('button', { name: 'Invite someone to send by Telegram' }).click();
	dialog = page.getByRole('dialog');
	await expect(dialog).toContainText('Share this link with someone who doesn’t use the app.');
	await expect(dialog.getByRole('button', { name: 'Share' })).toBeVisible();
	expect(st.calls.filter((c) => c.method === 'POST' && c.path.endsWith('/telegram-codes')).at(-1)!.body).toEqual({ chat_kind: 'private' });
});

test('a chat that needs attention shows the privacy-mode fix; settings, people and disconnect', async ({ page }) => {
	const { st, oma } = tgState();
	const chat = chatFor(st, oma, { status: 'needs_attention', error_code: 'privacy_mode', sees_all_messages: false, bot_is_admin: false });
	st.telegramContributors[chat.id] = [
		{ id: uid('ct'), display_name: 'Ben', linked: true, blocked: false, sent_count: 9, last_seen_at: ago(10) },
		{ id: uid('ct'), display_name: 'Cousin Tom', linked: false, blocked: false, sent_count: 3, last_seen_at: ago(60) }
	];
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=settings&telegram=${chat.id}`);
	const card = page.getByTestId('telegram-chat');
	await expect(card).toContainText('Needs attention');
	await expect(card.getByTestId('telegram-detail')).toContainText('The bot only sees commands in Peeters family. Make it an admin of the group');
	await expect(card.getByTestId('telegram-detail')).toContainText('BotFather → /setprivacy');

	await card.getByLabel('Who can send').selectOption('linked_only');
	await expect.poll(() => st.calls.find((c) => c.method === 'PATCH' && c.path === `/v1/telegram-chats/${chat.id}`)?.body).toEqual({ contributors: 'linked_only' });
	await card.getByLabel('Post quick replies in the chat').check();
	await expect.poll(() => st.calls.filter((c) => c.method === 'PATCH').at(-1)?.body).toEqual({ frame_replies: true });

	await card.getByRole('button', { name: 'People' }).click();
	const dialog = page.getByRole('dialog');
	const tom = dialog.getByTestId('telegram-contributor').filter({ hasText: 'Cousin Tom' });
	await tom.getByRole('button', { name: 'Block' }).click();
	await expect(page.getByText('Cousin Tom is blocked')).toBeVisible();
	await expect(tom.getByRole('button', { name: 'Unblock' })).toBeVisible();
	await page.keyboard.press('Escape');

	await card.getByRole('button', { name: 'Disconnect' }).click();
	await page.getByRole('dialog').getByRole('button', { name: 'Disconnect' }).click();
	await expect(page.getByText('Peeters family is disconnected')).toBeVisible();
	await page.getByText('Earlier (1)').click();
	await expect(page.getByTestId('telegram-chat')).toContainText('Disconnected by Anna');
	expect(st.calls.some((c) => c.method === 'DELETE' && c.path === `/v1/telegram-chats/${chat.id}`)).toBe(true);
});

test('a feed connects a group too, and its posts say "via Telegram"', async ({ page }) => {
	const { st, feed } = tgState();
	st.feedPosts[feed.id] = [
		{
			id: uid('po'),
			feed_id: feed.id,
			poster: { user_id: st.me!.id, display_name: 'Anna' },
			via: { service: 'telegram', label: 'Ben', telegram_chat_id: uid('tg') },
			created_at: ago(30),
			items: [{ id: uid('i'), kind: 'photo', status: 'ready', sender_id: st.me!.id, sender_name: 'Anna', caption: null, created_at: ago(30) }],
			receipts: null,
			reactions: null
		}
	];
	feedPostsOnTimeline(st);
	await signIn(page, st);
	await page.goto('/home');
	await expect(page.getByText('Ben via Telegram')).toBeVisible();
	await page.goto(`/feeds/${feed.id}?tab=sources`);
	await page.getByTestId('telegram-section').getByRole('button', { name: 'Connect a Telegram group' }).click();
	await expect(page.getByRole('dialog')).toContainText('Photos posted in the group become posts in Peeters kids.');
	expect(st.calls.some((c) => c.method === 'POST' && c.path === `/v1/feeds/${feed.id}/telegram-codes`)).toBe(true);
});

test('join requests from Telegram say so', async ({ page }) => {
	const { st, oma } = tgState();
	st.joinRequests[oma.id] = [{ id: uid('jr'), user: { id: uid('u'), display_name: 'Ben' }, via: 'telegram', via_name: 'Ben · Telegram, Peeters family', via_matches: null, created_at: ago(5) }];
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=settings`);
	await expect(page.getByText('Asked to join from Telegram')).toBeVisible();
	await expect(page.getByTestId('join-via-name')).toHaveText('Ben · Telegram, Peeters family');
});

test('account settings: link my Telegram, then unlink', async ({ page }) => {
	const { st } = tgState();
	await signIn(page, st);
	await page.goto('/settings');
	const section = page.getByTestId('telegram-link');
	await section.getByRole('button', { name: 'Link my Telegram' }).click();
	const dialog = page.getByRole('dialog');
	await expect(dialog.getByTestId('telegram-open')).toHaveAttribute('href', 'https://t.me/kinframe_bot?start=lQ8m2Zx7Kp4Rt9Vw3');
	await expect(dialog.getByRole('img', { name: 'QR code for the Telegram link' })).toBeVisible();

	st.telegramLink = { display_name: 'Carla Peeters', username: 'carla', can_message: true, linked_at: new Date().toISOString() };
	Object.assign(st.telegramCodes[0]!, { status: 'used' });
	push(st, { type: 'telegram_code.updated', code: { ...st.telegramCodes[0]!, url: null, code: null } });
	push(st, { type: 'telegram_link.updated', link: st.telegramLink });
	await expect(dialog.getByTestId('telegram-done')).toHaveText('Your Telegram is linked');
	await dialog.getByRole('button', { name: 'Done' }).click();
	await expect(section).toContainText('Linked to Carla Peeters (@carla)');

	await section.getByRole('button', { name: 'Unlink' }).click();
	await page.getByRole('dialog').getByRole('button', { name: 'Unlink' }).click();
	await expect(page.getByText('Telegram unlinked')).toBeVisible();
	await expect(section.getByRole('button', { name: 'Link my Telegram' })).toBeVisible();
});
