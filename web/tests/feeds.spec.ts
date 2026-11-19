import { expect, test, type Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { defaultSettings, emptyState, gradientPng, makeFrame, mockApi, push, uid, type MockState } from './mock-api';

// Feeds (docs/spec/feeds.md): the family web app's side, against the in-memory mock API.

type S = components['schemas'];
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();

function feedState(opts: { feeds?: boolean } = {}) {
	const st = emptyState();
	st.discovery.feeds = opts.feeds ?? true;
	st.me = { id: uid('u'), display_name: 'Anna', email: 'anna@example.org', avatar_url: null, locale: 'en', created_at: ago(9999) };
	const oma = makeFrame({ name: 'Oma’s frame', role: 'owner' });
	st.frames = [oma];
	st.settings[oma.id] = defaultSettings(1);
	const feed: S['Feed'] = {
		id: uid('fd'),
		name: 'Peeters kids',
		cover_url: null,
		role: 'owner',
		member_count: 3,
		subscription_count: 5,
		last_post_at: ago(30),
		created_at: ago(9000),
		owner_name: 'Anna',
		cover_item_id: null,
		auto_approve_subscriptions: false,
		invite_requires_approval: true,
		members_see_reactions: false,
		post_count: 2,
		pending_subscription_count: 1,
		pending_join_request_count: 0
	};
	st.feeds = [feed];
	const item = (sender: string): S['Item'] => ({ id: uid('i'), kind: 'photo', status: 'ready', sender_id: uid('u'), sender_name: sender, caption: null, created_at: ago(30) });
	st.feedPosts[feed.id] = [
		{
			id: uid('po'),
			feed_id: feed.id,
			poster: { user_id: st.me.id, display_name: 'Anna' },
			created_at: ago(30),
			items: [item('Anna'), item('Anna')],
			receipts: { frames: 5, delivered: 5, seen: 4, awaiting_approval: 0, paused: 1, removed: 0 },
			reactions: [
				{ frame_id: oma.id, frame_name: 'Oma’s frame', item_id: 'x', placement_id: 'p1', reaction: 'love', replies: [] },
				{ frame_id: uid('f'), frame_name: 'Kitchen', item_id: 'x', placement_id: 'p2', reaction: 'laugh', replies: [] }
			]
		},
		{ id: uid('po'), feed_id: feed.id, poster: { user_id: uid('u'), display_name: 'Ben' }, created_at: ago(90), items: [item('Ben')], receipts: null, reactions: null }
	];
	st.feedMembers[feed.id] = [
		{ user_id: st.me.id, display_name: 'Anna', role: 'owner', joined_at: ago(9000) },
		{ user_id: uid('u'), display_name: 'Ben', role: 'poster', joined_at: ago(5000) }
	];
	st.subscriptions = [
		{ id: uid('sb'), status: 'pending', history: 'last_n', history_count: 50, feed: { id: feed.id, name: feed.name }, frame: { id: uid('f'), name: 'Opa’s frame' }, requested_by_name: 'Ben', created_at: ago(10), can_decide: true },
		{ id: uid('sb'), status: 'active', history: 'none', feed: { id: feed.id, name: feed.name, admin_names: ['Anna'], member_count: 3 }, frame: { id: oma.id, name: oma.name }, created_at: ago(900), activated_at: ago(900), can_manage: true, can_decide: true }
	];
	return { st, oma, feed };
}

async function signIn(page: Page, st: MockState) {
	await mockApi(page, st);
	await page.goto('/');
	await page.getByLabel('Email').fill('anna@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

test('feeds stay hidden when the server has none', async ({ page }) => {
	const { st } = feedState({ feeds: false });
	await signIn(page, st);
	await expect(page.getByRole('link', { name: 'Families' })).toHaveCount(0);
	await page.goto('/feeds');
	await expect(page.getByText('This server doesn’t offer families.')).toBeVisible();
});

test('feed posts show receipts and reactions only where the viewer may see them', async ({ page }) => {
	const { st, feed } = feedState();
	await signIn(page, st);
	await page.getByRole('link', { name: 'Families' }).first().click();
	await page.getByRole('link', { name: /Peeters kids/ }).click();
	await expect(page).toHaveURL(new RegExp(`/feeds/${feed.id}$`));

	await expect(page.getByText('Seen on 4 of 5 frames')).toBeVisible();
	await expect(page.getByText('1 paused')).toBeVisible();
	await expect(page.getByText('Oma’s frame', { exact: true })).toBeVisible();
	await expect(page.getByText('Kitchen', { exact: true })).toBeVisible();
	// Ben's post: receipts are null for us… as the server decides; the page says so.
	await expect(page.getByText('Only the poster and admins see who saw it.')).toBeVisible();

	// Live: the post got seen on one more frame.
	await expect.poll(() => st.ws !== null).toBe(true);
	push(st, { type: 'feed_post.updated', feed_id: feed.id, post_id: st.feedPosts[feed.id]![0]!.id, summary: { frames: 5, delivered: 5, seen: 5 } });
	await expect(page.getByText('Seen on 5 of 5 frames')).toBeVisible();
});

test('approve a frame’s subscription request', async ({ page }) => {
	const { st, feed } = feedState();
	await signIn(page, st);
	await page.goto(`/feeds/${feed.id}?tab=subscriptions`);
	await expect(page.getByText('asked by Ben', { exact: false })).toBeVisible();
	await page.getByRole('button', { name: 'Let in' }).click();
	await expect(page.getByText('Opa’s frame now shows the family')).toBeVisible();
	expect(st.calls.some((c) => c.method === 'POST' && /\/v1\/subscriptions\/.+\/approve$/.test(c.path))).toBe(true);
});

test('create a subscribe invite: link, QR and code', async ({ page }) => {
	const { st, feed } = feedState();
	await signIn(page, st);
	await page.goto(`/feeds/${feed.id}?tab=invites`);
	await page.getByRole('radio', { name: /Show on a frame/ }).check();
	await page.getByRole('button', { name: 'Create invite link' }).click();
	await expect(page.getByTestId('invite-code').first()).toHaveText('KF7Q-M2XA');
	await expect(page.getByRole('img', { name: /QR code/ }).first()).toBeVisible();
	const create = st.calls.find((c) => c.method === 'POST' && c.path === `/v1/feeds/${feed.id}/invites`);
	expect(create?.body).toMatchObject({ purpose: 'subscribe', max_uses: 1, expires_in_hours: 168 });
});

test('a feed code in "Enter a code" opens the feed invite', async ({ page }) => {
	const { st } = feedState();
	const token = 'fiTOKEN0000000000000000000';
	st.feedInvitePreviews[token] = { token, purpose: 'join', feed_name: 'Cousins', invited_by_name: 'Lisa', owner_name: 'Lisa', member_count: 4, requires_approval: false, code: 'AB12CD34' } as S['FeedInvitePreview'];
	await signIn(page, st);
	await page.goto('/frames/add');
	await page.getByLabel('Frame code').fill('ab12-cd34');
	await expect(page).toHaveURL(new RegExp(`/fi/${token}$`));
	await expect(page.getByRole('heading', { name: 'Join Cousins' })).toBeVisible();
	await page.getByRole('button', { name: 'Join family' }).click();
	await expect(page.getByRole('heading', { name: 'You’re in Cousins' })).toBeVisible();
	expect(st.calls.find((c) => c.path === '/v1/feed-invites:lookup')?.body).toEqual({ code: 'AB12CD34' });
});

test('send to a frame and a feed at once', async ({ page }) => {
	const { st, oma, feed } = feedState();
	await signIn(page, st);
	await page.goto(`/send?feed=${feed.id}`);
	await expect(page.getByRole('checkbox', { name: /Peeters kids/ })).toBeChecked();
	await page.locator('label.recipient', { hasText: 'Oma’s frame' }).click();
	await expect(page.getByRole('checkbox', { name: /Oma’s frame/ })).toBeChecked();
	await page.getByTestId('file-input').setInputFiles([{ name: 'beach.png', mimeType: 'image/png', buffer: await gradientPng(320, 240, 10) }]);
	await page.getByRole('button', { name: /^Send/ }).first().click();
	await expect(page).toHaveURL(/\/history\/.+/);
	const send = st.calls.find((c) => c.path === '/v1/sends' && c.method === 'POST');
	expect(send?.body).toMatchObject({ frame_ids: [oma.id], feed_ids: [feed.id] });
	await expect(page.getByRole('link', { name: 'Peeters kids' })).toBeVisible();
});

test('frame side: pause a subscription and play only that feed', async ({ page }) => {
	const { st, oma, feed } = feedState();
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=feeds`);
	await expect(page.getByText('Peeters kids')).toBeVisible();
	await page.getByRole('button', { name: 'Play only this family' }).click();
	await expect.poll(() => st.calls.some((c) => c.method === 'PATCH' && c.path.endsWith('/settings'))).toBe(true);
	const patch = st.calls.find((c) => c.method === 'PATCH' && c.path.endsWith('/settings'));
	expect(patch?.body).toEqual({ slideshow: { feed_id: feed.id, album_id: null } });
	await page.getByRole('button', { name: 'Pause' }).click();
	await expect(page.getByText('Peeters kids paused')).toBeVisible();
	await expect(page.getByRole('button', { name: 'Resume' })).toBeVisible();
});

test('library credits feed photos "Anna · Peeters kids"', async ({ page }) => {
	const { st, oma, feed } = feedState();
	st.placements[oma.id] = [
		{
			id: uid('p'),
			frame_id: oma.id,
			item: { id: uid('i'), kind: 'photo', status: 'ready', sender_id: uid('u'), sender_name: 'Anna', caption: null, created_at: ago(20) },
			status: 'seen',
			hidden: false,
			trashed: false,
			received_at: ago(20),
			origin: 'feed',
			origin_feed_id: feed.id,
			origin_feed_name: 'Peeters kids',
			feed_ids: [feed.id]
		}
	];
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=library`);
	await page.getByRole('button', { name: 'Photo from Anna · Peeters kids' }).click();
	await expect(page.getByText('From the family Peeters kids')).toBeVisible();
});
