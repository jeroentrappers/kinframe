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
		subscription_count: 5, my_post_count: 0,
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

test('without a family: start one, add the frame later, invite the family', async ({ page }) => {
	const { st } = feedState();
	// Neither a family nor a frame: only the getting-started choices (simple-app.md §8).
	st.feeds = [];
	st.frames = [];
	st.subscriptions = [];
	await signIn(page, st);
	// Getting started (simple-app.md §8) instead of the frames list, Home and the composer.
	await expect(page.getByRole('heading', { name: 'Welcome to kinframe' })).toBeVisible();
	await expect(page.getByRole('button', { name: /Join my family/ })).toBeVisible();
	await page.goto('/send');
	await expect(page.getByRole('heading', { name: 'Welcome to kinframe' })).toBeVisible();

	await page.getByRole('button', { name: /Start a family/ }).click();
	await page.getByLabel('Name of the family').fill('Janssens family');
	await page.getByRole('button', { name: 'Start the family' }).click();
	await expect(page.getByRole('heading', { name: 'Add Oma’s frame now?' })).toBeVisible();
	expect(st.calls.find((c) => c.method === 'POST' && c.path === '/v1/feeds')?.body).toMatchObject({ name: 'Janssens family' });
	await page.getByRole('button', { name: 'Later' }).click();

	// The invite sheet opens by itself.
	const invite = page.getByRole('dialog', { name: 'Invite family to Janssens family' });
	await expect(invite.getByTestId('invite-code')).toHaveText('KF7Q-M2XA');
	await expect(invite.getByRole('button', { name: 'Share' })).toBeVisible();
	await invite.getByRole('button', { name: 'Close' }).click();
	await page.getByRole('button', { name: 'Done' }).click();
	await expect(page).toHaveURL(/\/home$/);
	await expect(page.getByRole('link', { name: 'Family' }).first()).toHaveAttribute('href', new RegExp(`/feeds/${st.feeds[0]!.id}$`));
});

test('without a family or a frame: sent history and settings still open', async ({ page }) => {
	const { st } = feedState();
	st.feeds = [];
	st.frames = [];
	st.subscriptions = [];
	await signIn(page, st);
	await expect(page.getByRole('heading', { name: 'Welcome to kinframe' })).toBeVisible();
	// Sent history (where Ask to keep is answered) and the settings are not behind getting started.
	await page.goto('/history');
	await expect(page.getByRole('heading', { name: 'Welcome to kinframe' })).toHaveCount(0);
	await expect(page.getByRole('main')).toBeVisible();
	await page.goto('/settings');
	await expect(page.getByRole('heading', { name: 'Welcome to kinframe' })).toHaveCount(0);
	await page.goto('/chats');
	await expect(page.getByRole('heading', { name: 'Welcome to kinframe' })).toBeVisible();
});

test('on a frame but in no family: the normal app, with Start a family and Join my family on Home', async ({ page }) => {
	const { st, oma } = feedState();
	st.feeds = [];
	st.subscriptions = [];
	await signIn(page, st);
	// Frames and the frame screen work.
	await expect(page.getByRole('heading', { name: 'Oma’s frame', level: 2 })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Welcome to kinframe' })).toHaveCount(0);
	await page.getByRole('link', { name: 'Oma’s frame', exact: true }).click();
	await expect(page.getByRole('heading', { name: 'Oma’s frame', level: 1 })).toBeVisible();
	await page.goto(`/frames/${oma.id}?tab=settings`);
	await expect(page.getByRole('heading', { name: 'Frame settings and help', level: 1 })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Welcome to kinframe' })).toHaveCount(0);
	// Home: the getting-started choices inline, in the normal app.
	await page.goto('/home');
	const start = page.getByTestId('home-start');
	await expect(start.getByRole('heading', { name: 'Welcome to kinframe' })).toBeVisible();
	await expect(page.getByRole('link', { name: 'Frames', exact: true }).first()).toBeVisible();
	await start.getByRole('button', { name: 'Start a family' }).click();
	await expect(page.getByLabel('Name of the family')).toBeVisible();
	// Cancel goes back to the app: they are on a frame.
	await page.getByRole('button', { name: 'Cancel' }).click();
	await page.getByTestId('home-start').getByRole('button', { name: 'Join my family' }).click();
	await expect(page.getByLabel('Invite link or code')).toBeVisible();
	await page.getByRole('button', { name: 'Cancel' }).click();
	await expect(page.getByTestId('home-start')).toBeVisible();
	await page.goto('/settings');
	await expect(page.getByRole('heading', { name: 'Welcome to kinframe' })).toHaveCount(0);
});

test('the family profile: counts, Photos with Only mine, People, Frames and the gear', async ({ page }) => {
	const { st, feed } = feedState();
	await signIn(page, st);
	// One family: the Family tab opens straight into its profile.
	await page.getByRole('link', { name: 'Family' }).first().click();
	await expect(page).toHaveURL(new RegExp(`/feeds/${feed.id}$`));
	await expect(page.getByRole('heading', { name: 'Peeters kids', level: 1 })).toBeVisible();
	await expect(page.getByText('You and Ben')).toBeVisible();
	await expect(page.getByRole('button', { name: 'Invite family' })).toBeVisible();

	// Photos: every post, or only mine.
	await expect(page.getByRole('tab', { name: 'Photos' })).toHaveAttribute('aria-selected', 'true');
	await expect(page.getByRole('link', { name: '2 photos by Anna' })).toBeVisible();
	await expect(page.getByRole('link', { name: 'Photo by Ben' })).toBeVisible();
	await page.getByText('Only mine').click();
	await expect(page.getByRole('link', { name: 'Photo by Ben' })).toHaveCount(0);
	await expect(page.getByRole('link', { name: '2 photos by Anna' })).toBeVisible();
	await expect(page.getByRole('link', { name: 'Sent history' })).toBeVisible();
	const mine = st.calls.filter((c) => c.method === 'GET' && c.path === `/v1/feeds/${feed.id}/posts`).pop();
	expect(mine?.query?.poster_id).toBe(st.me!.id);

	// People and Frames.
	await page.getByRole('tab', { name: 'People' }).click();
	await expect(page).toHaveURL(/\?tab=members$/);
	await expect(page.getByText('Ben', { exact: true }).first()).toBeVisible();
	await page.getByRole('tab', { name: /Frames/ }).click();
	await expect(page).toHaveURL(/\?tab=subscriptions$/);
	await expect(page.getByText('asked by Ben', { exact: false })).toBeVisible();

	// The gear: Settings with "This family" first.
	await page.getByRole('link', { name: 'Settings' }).first().click();
	await expect(page).toHaveURL(new RegExp(`/settings\\?family=${feed.id}$`));
	await expect(page.getByRole('heading', { name: 'This family' })).toBeVisible();
	await page.getByRole('link', { name: /Invite links/ }).click();
	await expect(page).toHaveURL(new RegExp(`/feeds/${feed.id}\\?tab=invites$`));
	await page.getByRole('link', { name: 'Settings' }).first().click();
	await expect(page).toHaveURL(new RegExp(`/settings\\?family=${feed.id}$`));
});

test('Add a frame: one of my frames shows the family at once, with the last 50 posts', async ({ page }) => {
	const { st, feed } = feedState();
	const kitchen = makeFrame({ name: 'Kitchen', role: 'owner' });
	st.frames.push(kitchen);
	await signIn(page, st);
	await page.goto(`/feeds/${feed.id}`);
	await page.getByRole('button', { name: 'Add a frame' }).click();
	const sheet = page.getByRole('dialog', { name: 'Add a frame' });
	// Oma's frame already shows the family.
	await expect(sheet.getByRole('button', { name: /Oma’s frame/ })).toHaveCount(0);
	await sheet.getByRole('button', { name: /Kitchen/ }).click();
	await expect(page.getByText('Kitchen now shows Peeters kids')).toBeVisible();
	const sub = st.calls.find((c) => c.method === 'POST' && c.path === `/v1/feeds/${feed.id}/subscriptions`);
	expect(sub?.body).toEqual({ frame_id: kitchen.id, history: 'last_n', history_count: 50 });
	expect(st.subscriptions.find((x) => x.frame.id === kitchen.id)?.status).toBe('active');
});

test('Frames: which family each frame shows, Add a family, and frames that show your families', async ({ page }) => {
	const { st, feed } = feedState();
	const signed = (id: string, v: string) => `http://localhost:${process.env.PW_PORT ?? 4173}/v1/media/signed/${id}/${v}?exp=1&sig=x`;
	const kitchen = makeFrame({
		name: 'Kitchen',
		role: 'owner',
		online: true,
		now_showing: { placement_id: uid('pl'), item_id: 'it-screen', source: 'screen', thumb_url: signed('it-screen', 'thumb_512'), display_url: signed('it-screen', 'display'), since: ago(2) },
		reactions_today: { count: 4, item_count: 3 }
	});
	const dad = makeFrame({ name: 'Dad’s frame', role: 'member', permissions: { send: true } });
	st.frames.push(kitchen, dad);
	// Oma's frame is offline: its card shows the last placed photo, and nothing was reacted to today.
	Object.assign(st.frames[0]!, {
		online: false,
		last_seen_at: ago(180),
		now_showing: { placement_id: uid('pl'), item_id: 'it-last', source: 'last_placed', thumb_url: signed('it-last', 'thumb_512'), display_url: null, since: ago(600) },
		reactions_today: { count: 0, item_count: 0 }
	});
	st.subscriptions.push({ id: uid('sb'), status: 'active', history: 'none', feed: { id: feed.id, name: feed.name }, frame: { id: uid('f'), name: 'Tante’s frame' }, created_at: ago(500), activated_at: ago(500) });
	await signIn(page, st);

	// Frames you look after first, by name; then the frames you're only on.
	const cards = page.getByRole('article');
	await expect(cards.getByRole('heading', { level: 2 })).toHaveText(['Kitchen', 'Oma’s frame', 'Dad’s frame']);
	const card = (name: string) => cards.filter({ has: page.getByRole('heading', { name, level: 2 }) });
	await expect(card('Kitchen').getByText('Online · showing now')).toBeVisible();
	await expect(card('Kitchen').locator('img.photo')).toHaveAttribute('src', /it-screen\/thumb_512/);
	await expect(card('Kitchen').getByTestId('reactions-today')).toHaveText('Kitchen loved 3 photos today');
	await expect(card('Oma’s frame').getByText('Shows Peeters kids')).toBeVisible();
	await expect(card('Oma’s frame').getByText(/Offline since/)).toBeVisible();
	await expect(card('Oma’s frame').locator('img.photo')).toHaveAttribute('src', /it-last\/thumb_512/);
	await expect(card('Oma’s frame').getByTestId('reactions-today')).toHaveCount(0);
	// Online, but no slide reported and no library access: no "showing now", no photo, no reactions line.
	await expect(card('Dad’s frame').getByText('Online', { exact: true })).toBeVisible();
	await expect(card('Dad’s frame').locator('img.photo')).toHaveCount(0);
	await expect(card('Dad’s frame').getByTestId('reactions-today')).toHaveCount(0);
	await expect(card('Dad’s frame').getByRole('button', { name: 'Add a family' })).toHaveCount(0);

	// A frame without a family: add one, it shows at once.
	await expect(card('Kitchen').getByText('Doesn’t show a family yet')).toBeVisible();
	await card('Kitchen').getByRole('button', { name: 'Add a family' }).click();
	await page.getByRole('dialog', { name: 'Add a family' }).getByRole('button', { name: /Peeters kids/ }).click();
	await expect(page.getByText('Kitchen now shows Peeters kids')).toBeVisible();
	await expect(card('Kitchen').getByText('Shows Peeters kids')).toBeVisible();
	expect(st.calls.find((c) => c.method === 'POST' && c.path === `/v1/feeds/${feed.id}/subscriptions`)?.body).toEqual({ frame_id: kitchen.id, history: 'last_n', history_count: 50 });

	// Frames you're not on that show one of your families: no photo for you, the frame icon stays.
	await expect(page.getByRole('heading', { name: 'Frames that show your families' })).toBeVisible();
	const tante = page.getByRole('listitem').filter({ hasText: 'Tante’s frame' });
	await expect(tante).toContainText('Shows Peeters kids');
	await expect(tante.locator('img')).toHaveCount(0);

	// The frame screen's card: the larger display rendition, with the thumbnail when there is none.
	await card('Kitchen').getByRole('link', { name: 'Kitchen', exact: true }).click();
	await expect(page.getByRole('heading', { name: 'Kitchen', level: 1 })).toBeVisible();
	await expect(page.locator('article img.photo')).toHaveAttribute('src', /it-screen\/display/);
	await expect(page.getByTestId('reactions-today')).toHaveText('Kitchen loved 3 photos today');
	await page.goto(`/frames/${st.frames[0]!.id}`);
	await expect(page.locator('article img.photo')).toHaveAttribute('src', /it-last\/thumb_512/);
	// No more guessing the photo from the frame's library.
	expect(st.calls.some((c) => /\/v1\/frames\/[^/]+\/placements$/.test(c.path) && c.query?.limit === '1')).toBe(false);
});

test('the home family is the one you post to most, then the one you created', async ({ page }) => {
	const { st, feed } = feedState();
	const oma: S['Feed'] = { ...feed, id: uid('fd'), name: 'Oma’s family', role: 'poster', my_post_count: 7, created_at: ago(3000), owner_name: 'Oma' };
	const club: S['Feed'] = { ...feed, id: uid('fd'), name: 'Book club', role: 'poster', my_post_count: 0, created_at: ago(8000), owner_name: 'Ben' };
	st.feeds.push(club, oma);
	await signIn(page, st);
	// Peeters kids: one post of mine (the mock counts them); Oma's family: seven.
	await page.getByRole('link', { name: 'Family', exact: true }).first().click();
	await expect(page).toHaveURL(/\/family$/);
	await expect(page.getByRole('link', { name: /Peeters kids|Oma’s family|Book club/ })).toHaveText([/Oma’s family/, /Peeters kids/, /Book club/]);
	// The composer sends to the home family by default.
	await page.getByRole('link', { name: 'Share' }).first().click();
	await expect(page.getByTestId('destination')).toContainText('Oma’s family');
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
	await page.getByRole('radio', { name: /Add a frame/ }).check();
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

	// Asked once on this device: normal or simple view.
	await expect(page.getByTestId('view-simple')).toBeVisible();
	await page.getByTestId('view-normal').click();
	await expect(page).toHaveURL(/\/home$/);
	expect(await page.evaluate(() => [localStorage.getItem('kinframe.viewAsked'), localStorage.getItem('kinframe.simpleView')])).toEqual(['1', null]);
});

test('joining as a Reader offers the family, not posting', async ({ page }) => {
	const { st } = feedState();
	const token = 'fiREADER000000000000000000';
	st.feedInvitePreviews[token] = { token, purpose: 'join', feed_name: 'Cousins', invited_by_name: 'Lisa', owner_name: 'Lisa', member_count: 4, requires_approval: false, join_role: 'member' } as S['FeedInvitePreview'];
	// The view question was answered before on this device.
	await page.addInitScript(() => localStorage.setItem('kinframe.viewAsked', '1'));
	await signIn(page, st);
	await page.goto(`/fi/${token}`);
	await page.getByRole('button', { name: 'Join family' }).click();
	await expect(page.getByRole('heading', { name: 'You’re in Cousins' })).toBeVisible();
	await expect(page.getByText('Readers see everything, comment, react and chat, but don’t post photos.')).toBeVisible();
	await expect(page.getByRole('link', { name: 'Share photos' })).toHaveCount(0);
	await expect(page.getByTestId('view-simple')).toHaveCount(0);
	await expect(page.getByRole('link', { name: 'View family' })).toBeVisible();
});

test('share with the family, or with two families at once', async ({ page }) => {
	const { st, feed } = feedState();
	const opa = { ...feed, id: uid('fd'), name: 'Opa & Oma', role: 'poster' as const, subscription_count: 1, last_post_at: ago(9000), pending_subscription_count: 0 };
	st.feeds.push(opa);
	await signIn(page, st);
	await page.getByRole('link', { name: 'Share' }).first().click();
	await expect(page).toHaveURL(/\/send$/);
	// The home family by default: nothing to choose.
	const dest = page.getByTestId('destination');
	await expect(dest).toHaveText('Peeters kids · everyone and its 5 frames');
	await page.getByRole('button', { name: 'Change' }).click();
	const sheet = page.getByRole('dialog', { name: 'Share with' });
	await expect(sheet.getByTestId('family-option')).toHaveCount(2);
	await expect(sheet.getByRole('checkbox', { name: /Peeters kids/ })).toBeChecked();
	await sheet.getByRole('checkbox', { name: /Opa & Oma/ }).check();
	await sheet.getByRole('button', { name: 'Done' }).click();
	await expect(dest).toHaveText('2 families · 6 frames');

	await page.getByTestId('file-input').setInputFiles([{ name: 'beach.png', mimeType: 'image/png', buffer: await gradientPng(320, 240, 10) }]);
	await page.getByRole('button', { name: 'Share', exact: true }).first().click();
	// Shared with families: back to Home.
	await expect(page).toHaveURL(/\/home$/);
	const send = st.calls.find((c) => c.path === '/v1/sends' && c.method === 'POST');
	expect(send?.body).toMatchObject({ feed_ids: [feed.id, opa.id] });
	expect((send?.body as S['SendCreate']).frame_ids ?? []).toEqual([]);
});

test('frame side: pause a subscription and play only that feed', async ({ page }) => {
	const { st, oma, feed } = feedState();
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=settings`);
	await expect(page.getByRole('heading', { name: 'Families on this frame' })).toBeVisible();
	await expect(page.getByRole('strong').filter({ hasText: 'Peeters kids' })).toBeVisible();
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
