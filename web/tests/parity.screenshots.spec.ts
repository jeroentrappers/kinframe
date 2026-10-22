// Visual review of albums, memories, trimming, ownership and notifications:
// `npx playwright test --project=screenshots parity`. Writes PNGs to $SCREENSHOT_DIR (default ./screenshots).
import { readFileSync } from 'node:fs';
import { test, type Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { defaultSettings, emptyState, gradientPng, makeFrame, mockApi, uid, type MockState } from './mock-api';

type S = components['schemas'];
const DIR = process.env.SCREENSHOT_DIR ?? 'screenshots';
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();

function state(): MockState {
	const st = emptyState();
	st.discovery.name = 'Trappers family';
	st.me = { id: uid('u'), display_name: 'Jeroen Trappers', email: 'jeroen@example.org', avatar_url: null, locale: 'en', created_at: ago(99999) };
	const oma = makeFrame({ name: 'Oma’s frame', location: 'Living room', role: 'owner', member_count: 4, placement_count: 24 });
	st.frames = [oma];
	const now = new Date();
	const onThisDay = (years: number) => new Date(Date.UTC(now.getFullYear() - years, now.getMonth(), now.getDate(), 11)).toISOString();
	st.placements[oma.id] = Array.from({ length: 24 }, (_, i) => ({
		id: uid('p'),
		frame_id: oma.id,
		item: {
			id: uid('i'),
			kind: (i % 7 === 3 ? 'video' : 'photo') as S['MediaKind'],
			status: 'ready' as const,
			sender_id: st.me!.id,
			sender_name: ['Jeroen', 'Lisa', 'Tom'][i % 3],
			caption: i % 5 === 0 ? 'Weekend at the sea' : null,
			created_at: ago(i * 300),
			captured_at: i < 4 ? onThisDay(i + 1) : ago(i * 60 * 24 * 21)
		},
		status: 'seen' as const,
		hidden: false,
		trashed: false,
		received_at: ago(i * 300)
	}));
	const ids = st.placements[oma.id]!.map((p) => p.id);
	st.albums[oma.id] = [
		{ id: uid('a'), name: 'Christmas', version: 3, placement_count: 6, placement_ids: ids.slice(4, 10), cover_placement_id: ids[5], schedule: { from_month_day: '12-01', to_month_day: '01-06' } },
		{ id: uid('a'), name: 'Summer at the coast', version: 1, placement_count: 5, placement_ids: ids.slice(10, 15) },
		{ id: uid('a'), name: 'Grandchildren', version: 1, placement_count: 3, placement_ids: ids.slice(15, 18) }
	];
	st.settings[oma.id] = { ...defaultSettings(4), slideshow: { ...defaultSettings().slideshow, album_id: st.albums[oma.id]![0]!.id, on_this_day: true } };
	st.members[oma.id] = [
		{ user_id: st.me.id, display_name: 'Jeroen Trappers', role: 'owner', permissions: {}, avatar_url: null },
		{ user_id: uid('u'), display_name: 'Lisa Peeters', role: 'admin', permissions: {}, avatar_url: null },
		{ user_id: uid('u'), display_name: 'Tom', role: 'member', permissions: { send: true }, avatar_url: null },
		{ user_id: uid('u'), display_name: 'Marie Claes', role: 'member', permissions: { send: true }, avatar_url: null }
	];
	st.replaceCodes = ['55512345'];
	return st;
}

async function signIn(page: Page) {
	await page.goto('/');
	await page.getByLabel('Email').fill('jeroen@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

async function shot(page: Page, name: string, fullPage = true) {
	await page.waitForTimeout(450);
	await page.screenshot({ path: `${DIR}/${name}.png`, fullPage });
}

const VIEWPORTS = [
	{ tag: 'phone', viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 },
	{ tag: 'desktop', viewport: { width: 1280, height: 860 }, isMobile: false, hasTouch: false, deviceScaleFactor: 1 }
] as const;

for (const vp of VIEWPORTS) {
	for (const scheme of ['light', 'dark'] as const) {
		test.describe(`parity ${vp.tag} ${scheme}`, () => {
			test.use({ viewport: vp.viewport, isMobile: vp.isMobile, hasTouch: vp.hasTouch, deviceScaleFactor: vp.deviceScaleFactor, colorScheme: scheme });

			test(`parity ${vp.tag} ${scheme}`, async ({ page }) => {
				await page.addInitScript(() => {
					let perm: NotificationPermission = 'default';
					class FakeNotification {
						static get permission() {
							return perm;
						}
						static requestPermission() {
							perm = 'granted';
							return Promise.resolve(perm);
						}
					}
					Object.defineProperty(window, 'Notification', { value: FakeNotification, configurable: true });
					const reg = { pushManager: { getSubscription: () => Promise.resolve(null), subscribe: () => Promise.reject(new Error('no')) } };
					Object.defineProperty(navigator, 'serviceWorker', {
						value: { register: () => Promise.resolve(reg), ready: Promise.resolve(reg), getRegistration: () => Promise.resolve(reg), addEventListener: () => {} },
						configurable: true
					});
				});
				const st = state();
				await mockApi(page, st);
				const n = (s: string) => `${vp.tag}-${scheme}-p${s}`;
				const oma = st.frames[0]!;
				await signIn(page);

				// Library: albums bar + "On this day".
				await page.goto(`/frames/${oma.id}?tab=library`);
				await page.getByRole('heading', { name: 'On this day' }).waitFor();
				await shot(page, n('01-library-albums'));

				// Selection with "Add to album", then the picker.
				await page.getByRole('checkbox', { name: 'Select' }).nth(1).click();
				await page.getByRole('checkbox', { name: 'Select' }).nth(2).click();
				await shot(page, n('02-library-select'), false);
				await page.getByRole('toolbar', { name: 'Selection actions' }).getByRole('button', { name: /album/i }).click();
				await page.getByRole('dialog').waitFor();
				await shot(page, n('03-add-to-album'), false);
				await page.keyboard.press('Escape');
				await page.getByRole('button', { name: 'Clear selection' }).click();

				// An album that plays on the frame, on a schedule.
				await page.getByRole('button', { name: /Christmas 6/ }).click();
				await page.getByText('Playing on the frame').waitFor();
				await shot(page, n('04-album'));
				await page.getByRole('button', { name: 'Change the order' }).click();
				await shot(page, n('05-album-arrange'));
				await page.getByRole('button', { name: 'Cancel' }).click();
				await page.getByRole('button', { name: 'Schedule', exact: true }).click();
				await page.getByRole('dialog').waitFor();
				await shot(page, n('06-album-schedule'), false);
				await page.keyboard.press('Escape');

				// Frame settings: album + "On this day".
				await page.goto(`/frames/${oma.id}?tab=settings`);
				await page.getByLabel('Show on the frame').waitFor();
				await shot(page, n('07-settings-album'));

				// Ownership and moving to a new frame.
				await page.goto(`/frames/${oma.id}`);
				await page.getByRole('heading', { name: 'Ownership and device' }).waitFor();
				await shot(page, n('08-overview-ownership'));
				await page.getByRole('button', { name: 'Transfer', exact: true }).click();
				await page.getByRole('dialog').getByRole('radio').first().waitFor();
				await shot(page, n('09-transfer'), false);
				await page.getByRole('dialog').getByRole('button', { name: 'Continue' }).click();
				await page.getByText('You stay on the frame as an admin.').waitFor();
				await shot(page, n('10-transfer-confirm'), false);
				await page.keyboard.press('Escape');
				await page.getByRole('button', { name: 'Move', exact: true }).click();
				await page.getByLabel('Code on the new frame').waitFor();
				await shot(page, n('11-replace'), false);
				await page.keyboard.press('Escape');

				// Trimming a video.
				await page.goto('/send');
				await page.getByTestId('file-input').setInputFiles([
					{ name: 'clip.webm', mimeType: 'video/webm', buffer: readFileSync('tests/fixtures/clip.webm') },
					{ name: 'garden.png', mimeType: 'image/png', buffer: await gradientPng(320, 480, 40) }
				]);
				await page.getByRole('button', { name: 'Adjust framing of item 1' }).click();
				await page.locator('.strip img').nth(9).waitFor();
				const end = page.getByRole('slider', { name: 'End of the part to send' });
				await end.focus();
				await page.keyboard.press('Shift+ArrowLeft');
				await page.keyboard.press('Shift+ArrowLeft');
				await page.getByRole('slider', { name: 'Start of the part to send' }).focus();
				await page.keyboard.press('Shift+ArrowRight');
				await shot(page, n('12-trim'), false);
				await page.getByRole('button', { name: 'Done' }).click();
				await shot(page, n('13-send-trimmed'));

				// The notification offer after a send, and the settings switch.
				await page.getByRole('button', { name: /^Send/ }).first().click();
				await page.getByRole('heading', { name: 'Want to know when it arrives?' }).waitFor();
				await shot(page, n('14-push-offer'));
				await page.goto('/settings');
				await page.getByRole('heading', { name: 'Notifications' }).waitFor();
				await shot(page, n('15-settings-push'));
			});
		});
	}
}
