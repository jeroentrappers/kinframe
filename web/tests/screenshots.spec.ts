// Visual review: `npx playwright test --project=screenshots`. Writes PNGs to $SCREENSHOT_DIR (default ./screenshots).
import { test, type Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { defaultSettings, emptyState, gradientPng, makeFrame, mockApi, uid, type MockState } from './mock-api';
import { socialState } from './social-fixtures';

type S = components['schemas'];
const DIR = process.env.SCREENSHOT_DIR ?? 'screenshots';
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();

function richState(): MockState {
	const st = emptyState();
	st.discovery.name = 'Trappers family';
	st.me = { id: uid('u'), display_name: 'Jeroen Trappers', email: 'jeroen@example.org', avatar_url: null, locale: 'en', created_at: ago(99999) };
	const oma = makeFrame({ name: 'Oma’s frame', location: 'Living room', role: 'owner', online: true, member_count: 4, placement_count: 148 });
	const cabin = makeFrame({ name: 'The cabin', location: 'Ardennes', role: 'admin', online: false, last_seen_at: ago(190), owner_name: 'Lisa' });
	const lisa = makeFrame({
		name: 'Lisa’s kitchen',
		location: 'Gent',
		role: 'member',
		online: true,
		owner_name: 'Lisa',
		screen: { width: 800, height: 1280, orientation: 'portrait' },
		permissions: { send: true, invite: false, see_library: true, manage_library: false, change_settings: false }
	});
	st.frames = [oma, cabin, lisa];

	const item = (caption: string | null, kind: 'photo' | 'video' = 'photo', status: S['Item']['status'] = 'ready'): S['Item'] => ({
		id: uid('i'),
		kind,
		status,
		sender_id: st.me!.id,
		sender_name: 'Jeroen',
		caption,
		caption_edited: false,
		created_at: ago(30),
		width: 640,
		height: 480,
		duration_ms: kind === 'video' ? 42_000 : null,
		thumb_url: null
	});
	const placements = (items: S['Item'][], status: S['PlacementStatus'][], reactions: (S['Reaction'] | null)[] = [], replies: S['ReplyPreset'][][] = []) =>
		items.map((it, i) => ({
			placement_id: uid('p'),
			item_id: it.id,
			status: status[i % status.length]!,
			reaction: reactions[i] ?? null,
			replies: replies[i] ?? []
		}));

	const s1items = [item('First swim of the summer! 🌊'), item(null), item(null), item(null, 'video'), item(null), item(null)];
	const s2items = [item('Happy birthday Oma ❤️'), item(null)];
	const s3items = [item('The garden in bloom'), item(null, 'photo', 'processing'), item(null, 'photo', 'processing')];
	st.sends = [
		{
			id: uid('5'),
			created_at: ago(4),
			items: s3items,
			frames: [{ frame_id: cabin.id, frame_name: cabin.name, online: false, placements: placements(s3items, ['ready', 'pending', 'pending']) }]
		},
		{
			id: uid('5'),
			created_at: ago(95),
			items: s1items,
			frames: [
				{ frame_id: oma.id, frame_name: oma.name, online: true, placements: placements(s1items, ['seen', 'seen', 'seen', 'delivered'], ['love', 'love', 'laugh'], [['beautiful']]) },
				{ frame_id: lisa.id, frame_name: lisa.name, online: true, placements: placements(s1items, ['delivered', 'seen'], ['wow']) }
			]
		},
		{
			id: uid('5'),
			created_at: ago(60 * 26),
			items: s2items,
			frames: [{ frame_id: oma.id, frame_name: oma.name, online: true, placements: placements(s2items, ['seen'], ['love', 'love'], [['miss_you'], ['call_me']]) }]
		}
	];

	const libItems = Array.from({ length: 18 }, (_, i) => item(i % 4 === 0 ? 'Weekend at the sea' : null, i % 7 === 3 ? 'video' : 'photo'));
	st.placements[oma.id] = libItems.map((it, i) => ({
		id: uid('p'),
		frame_id: oma.id,
		item: { ...it, sender_name: ['Jeroen', 'Lisa', 'Tom'][i % 3] },
		status: 'seen',
		hidden: i === 5 || i === 11,
		trashed: i === 16,
		received_at: ago(i * 300)
	}));
	st.members[oma.id] = [
		{ user_id: st.me.id, display_name: 'Jeroen Trappers', role: 'owner', permissions: {}, joined_at: ago(99999), last_sent_at: ago(4), avatar_url: null },
		{ user_id: uid('u'), display_name: 'Lisa Peeters', role: 'admin', permissions: {}, joined_at: ago(50000), last_sent_at: ago(600), avatar_url: null },
		{ user_id: uid('u'), display_name: 'Tom', role: 'member', permissions: { send: true, invite: false, see_library: true, manage_library: false, change_settings: false }, joined_at: ago(20000), last_sent_at: ago(3000), avatar_url: null },
		{ user_id: uid('u'), display_name: 'Marie Claes', role: 'member', permissions: { send: true }, joined_at: ago(2000), last_sent_at: null, avatar_url: null }
	];
	st.joinRequests[oma.id] = [
		{ id: uid('7'), user: { id: uid('u'), display_name: 'Nonkel Paul', avatar_url: null }, via: 'invite', invited_by_name: 'Lisa', note: 'For Uncle Paul', created_at: ago(12) }
	];
	st.invites[oma.id] = [
		{ id: uid('1'), token: 'abcdefghijklmnopqrstuvwxyz', url: 'https://kinframe.appmire.be/i/abcdefghijklmnopqrstuvwxyz', created_by_name: 'Jeroen', max_uses: null, uses: 2, note: 'Cousins group chat', expires_at: new Date(Date.now() + 5 * 86400_000).toISOString() }
	];
	st.settings[oma.id] = defaultSettings(7);
	st.invitePreviews['tok_invite_preview_000000000'] = { frame_name: 'The cabin', frame_location: 'Ardennes', owner_name: 'Lisa', invited_by_name: 'Lisa', requires_approval: false, already_member: false };
	return st;
}

async function signIn(page: Page) {
	await page.goto('/');
	await page.getByLabel('Email').fill('jeroen@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

async function shot(page: Page, name: string) {
	await page.waitForTimeout(450); // let images fade in
	await page.screenshot({ path: `${DIR}/${name}.png`, fullPage: true });
}

const VIEWPORTS = [
	{ tag: 'phone', viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 },
	{ tag: 'desktop', viewport: { width: 1280, height: 860 }, isMobile: false, hasTouch: false, deviceScaleFactor: 1 }
] as const;

for (const vp of VIEWPORTS) {
	for (const scheme of ['light', 'dark'] as const) {
		test.describe(`${vp.tag} ${scheme}`, () => {
			test.use({ viewport: vp.viewport, isMobile: vp.isMobile, hasTouch: vp.hasTouch, deviceScaleFactor: vp.deviceScaleFactor, colorScheme: scheme });

			test(`pages ${vp.tag} ${scheme}`, async ({ page }) => {
				const st = richState();
				await mockApi(page, st);
				const n = (s: string) => `${vp.tag}-${scheme}-${s}`;
				const oma = st.frames[0]!;

				await page.goto('/');
				await page.getByText('Developer sign-in').waitFor();
				await shot(page, n('01-landing'));
				if (scheme === 'dark') {
					await signIn(page);
					await page.getByText('Oma’s frame').first().waitFor();
					await shot(page, n('02-frames'));
					await page.goto(`/send?frame=${oma.id}`);
					await page.getByTestId('file-input').setInputFiles([
						{ name: 'a.png', mimeType: 'image/png', buffer: await gradientPng(480, 320, 0) },
						{ name: 'b.png', mimeType: 'image/png', buffer: await gradientPng(320, 480, 40) }
					]);
					await shot(page, n('04-send'));
					await page.goto(`/frames/${oma.id}`);
					await page.getByText('Frame settings and help').waitFor();
					await shot(page, n('07-frame-overview'));
					return;
				}

				await signIn(page);
				await page.getByText('Oma’s frame').first().waitFor();
				await shot(page, n('02-frames'));

				await page.goto('/frames/add');
				await shot(page, n('03-add-frame'));

				await page.goto(`/send?frame=${oma.id}`);
				await page.getByText('Drop photos and videos here').waitFor();
				await shot(page, n('04a-send-empty'));
				await page.getByTestId('file-input').setInputFiles([
					{ name: 'beach.png', mimeType: 'image/png', buffer: await gradientPng(480, 320, 0) },
					{ name: 'garden.png', mimeType: 'image/png', buffer: await gradientPng(320, 480, 40) },
					{ name: 'sunset.png', mimeType: 'image/png', buffer: await gradientPng(480, 320, 70) }
				]);
				await page.getByLabel('Caption for item 1').fill('Zomer aan zee ☀️');
				await shot(page, n('04-send'));
				await page.getByRole('button', { name: 'Adjust framing of item 1' }).click();
				await page.getByRole('dialog').waitFor();
				await page.waitForTimeout(300);
				await page.screenshot({ path: `${DIR}/${n('05-send-adjust')}.png` });
				await page.keyboard.press('Escape');

				await page.goto('/history');
				await page.getByText('Today').waitFor();
				await shot(page, n('06-history'));

				await page.goto(`/history/${st.sends[1]!.id}`);
				await page.getByRole('button', { name: 'Unsend' }).waitFor();
				await shot(page, n('06b-history-detail'));

				await page.goto(`/frames/${oma.id}`);
				await page.getByText('Frame settings and help').waitFor();
				await shot(page, n('07-frame-overview'));

				await page.goto(`/frames/${oma.id}?tab=library`);
				await page.getByText('On the frame').first().waitFor();
				await page.getByRole('checkbox', { name: 'Select' }).nth(1).click();
				await page.getByRole('checkbox', { name: 'Select' }).nth(2).click();
				await shot(page, n('08-frame-library'));

				await page.goto(`/frames/${oma.id}?tab=settings`);
				await page.getByText('Waiting to join').waitFor();
				await page.getByRole('button', { name: 'Edit Tom' }).click();
				await shot(page, n('09-frame-people'));

				await page.goto(`/frames/${oma.id}?tab=settings`);
				await page.getByText('Slideshow').first().waitFor();
				await page.getByLabel('Order', { exact: true }).selectOption('shuffle');
				await shot(page, n('10-frame-settings'));
				await page.getByRole('combobox', { name: 'Find a town or city' }).fill('gen');
				await page.getByRole('listbox', { name: 'Places' }).getByRole('option').first().waitFor();
				await page.getByRole('heading', { name: 'Location & weather' }).scrollIntoViewIfNeeded();
				await page.waitForTimeout(300);
				await page.screenshot({ path: `${DIR}/${n('10b-frame-settings-location')}.png` });
				await page.getByRole('combobox', { name: 'Find a town or city' }).press('Enter');
				await shot(page, n('10c-frame-settings-weather'));

				await page.goto('/i/tok_invite_preview_000000000');
				await page.getByText('Join The cabin').waitFor();
				await shot(page, n('11-invite'));

				await page.goto('/settings');
				await shot(page, n('12-settings'));
			});
		});
	}
}

// The simple family app (docs/spec/simple-app.md): Home, the composer, Frames, the Family profile and Settings, the
// getting-started choices and the simple view.
for (const vp of VIEWPORTS) {
	test(`family app ${vp.tag}`, async ({ page }) => {
		test.setTimeout(90_000);
		await page.setViewportSize(vp.viewport);
		const s = socialState();
		// A second family Anna posts to, and a circle: the composer's Change has something to offer.
		s.st.feeds.push({ ...s.feed, id: uid('fd'), name: 'Opa & Oma Trappers', role: 'poster', conversation_id: null, streak: null, last_post_at: ago(3000) });
		s.soc.circles[s.feed.id] = [{ id: uid('ci'), feed_id: s.feed.id, name: 'The cousins', my_role: 'admin', members: [{ user_id: s.me.user_id, display_name: 'Anna', role: 'admin' }], member_count: 1, frame_ids: [], frames_reached: [], conversation_id: null }];
		await mockApi(page, s.st);
		const n = (x: string) => `family-${vp.tag}-${x}`;
		await page.goto('/');
		await page.getByLabel('Email').fill('anna@example.org');
		await page.getByRole('button', { name: 'Continue' }).click();
		await page.waitForURL(/\/home$/);
		await page.getByRole('group', { name: 'Show posts of' }).waitFor();
		await shot(page, n('20-home'));

		await page.getByRole('link', { name: 'Share', exact: true }).first().click();
		await page.getByTestId('destination').waitFor();
		await page.getByTestId('file-input').setInputFiles([
			{ name: 'a.png', mimeType: 'image/png', buffer: await gradientPng(480, 320, 0) },
			{ name: 'b.png', mimeType: 'image/png', buffer: await gradientPng(320, 480, 40) }
		]);
		await shot(page, n('21-share'));
		await page.getByRole('button', { name: 'Change' }).click();
		await page.getByRole('dialog', { name: 'Share with' }).waitFor();
		await page.waitForTimeout(300);
		await page.screenshot({ path: `${DIR}/${n('22-share-change')}.png` });
		await page.keyboard.press('Escape');

		await page.goto('/frames');
		await page.getByText('Shows Peeters').first().waitFor();
		await shot(page, n('23-frames'));
		await page.goto(`/frames/${s.oma.id}`);
		await page.getByText('Frame settings and help').waitFor();
		await shot(page, n('24-frame'));

		await page.goto('/family');
		await page.getByRole('heading', { name: 'Your families' }).waitFor();
		await shot(page, n('25-families'));
		await page.goto(`/feeds/${s.feed.id}`);
		await page.getByRole('button', { name: 'Invite family' }).waitFor();
		await shot(page, n('26-family'));
		await page.goto(`/feeds/${s.feed.id}?tab=members`);
		await page.getByText('Reader').first().waitFor();
		await shot(page, n('27-family-people'));
		await page.goto(`/settings?family=${s.feed.id}`);
		await page.getByRole('heading', { name: 'This family' }).waitFor();
		await shot(page, n('28-settings'));

		await page.evaluate(() => localStorage.setItem('kinframe.simpleView', '1'));
		await page.goto('/home');
		await page.getByRole('button', { name: 'Send a heart' }).waitFor();
		await shot(page, n('29-simple-view'));
	});

	test(`getting started ${vp.tag}`, async ({ page }) => {
		await page.setViewportSize(vp.viewport);
		const s = socialState();
		s.st.feeds = [];
		await mockApi(page, s.st);
		await page.goto('/');
		await page.getByLabel('Email').fill('anna@example.org');
		await page.getByRole('button', { name: 'Continue' }).click();
		await page.getByRole('heading', { name: 'Welcome to kinframe' }).waitFor();
		await shot(page, `family-${vp.tag}-30-getting-started`);
	});
}

// Languages that stress the layout: right to left (Arabic, Hebrew), CJK, long German and Russian words.
for (const locale of ['ar', 'he', 'ja', 'de', 'ru'] as const) {
	for (const vp of VIEWPORTS) {
		test(`localized ${locale} ${vp.tag}`, async ({ page }) => {
			test.setTimeout(60_000);
			await page.setViewportSize(vp.viewport);
			await page.addInitScript((l) => localStorage.setItem('kinframe.locale', l), locale);
			const st = richState();
			await mockApi(page, st);
			const n = (s: string) => `l10n-${locale}-${vp.tag}-${s}`;
			const oma = st.frames[0]!;
			await page.goto('/');
			await page.locator('#dev-email').waitFor();
			await shot(page, n('01-landing'));
			await page.locator('#dev-email').fill('jeroen@example.org');
			await page.locator('form button[type=submit]').click();
			await page.waitForURL(/\/frames$/);
			await page.getByText('Oma’s frame').first().waitFor();
			await shot(page, n('02-frames'));
			await page.goto(`/send?frame=${oma.id}`);
			await page.getByTestId('file-input').setInputFiles([
				{ name: 'a.png', mimeType: 'image/png', buffer: await gradientPng(480, 320, 0) },
				{ name: 'b.png', mimeType: 'image/png', buffer: await gradientPng(320, 480, 40) }
			]);
			await shot(page, n('04-send'));
			await page.goto('/history');
			await page.waitForLoadState('networkidle');
			await shot(page, n('06-history'));
			for (const [tab, name] of [['', '07-frame-overview'], ['?tab=library', '08-frame-library'], ['?tab=settings', '10-frame-settings']] as const) {
				await page.goto(`/frames/${oma.id}${tab}`);
				await page.waitForLoadState('networkidle');
				await shot(page, n(name));
			}
			await page.goto('/settings');
			await page.waitForLoadState('networkidle');
			await shot(page, n('12-settings'));
		});
	}
}
