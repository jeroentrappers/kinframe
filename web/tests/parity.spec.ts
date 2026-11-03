import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { defaultSettings, emptyState, gradientPng, makeFrame, mockApi, uid, type MockState } from './mock-api';

type S = components['schemas'];
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();

function libraryState(): MockState {
	const st = emptyState();
	st.me = { id: uid('u'), display_name: 'Jeroen', email: 'jeroen@example.org', avatar_url: null, locale: 'en', created_at: ago(9999) };
	const frame = makeFrame({ name: 'Oma’s frame', role: 'owner', member_count: 3 });
	st.frames = [frame];
	const now = new Date();
	const threeYearsAgo = new Date(Date.UTC(now.getFullYear() - 3, now.getMonth(), now.getDate(), 12)).toISOString();
	st.placements[frame.id] = Array.from({ length: 8 }, (_, i) => ({
		id: uid('p'),
		frame_id: frame.id,
		item: {
			id: uid('i'),
			kind: 'photo' as const,
			status: 'ready' as const,
			sender_id: st.me!.id,
			sender_name: 'Jeroen',
			caption: i === 0 ? 'Beach day' : null,
			created_at: ago(i * 60),
			captured_at: i === 0 ? threeYearsAgo : ago(i * 60 * 24 * 40)
		},
		status: 'seen' as const,
		hidden: false,
		trashed: false,
		received_at: ago(i * 60)
	}));
	st.members[frame.id] = [
		{ user_id: st.me.id, display_name: 'Jeroen', role: 'owner', permissions: {}, avatar_url: null },
		{ user_id: uid('u'), display_name: 'Lisa', role: 'admin', permissions: {}, avatar_url: null },
		{ user_id: uid('u'), display_name: 'Tom', role: 'member', permissions: { send: true }, avatar_url: null }
	];
	st.settings[frame.id] = defaultSettings(3);
	return st;
}

async function signIn(page: Page) {
	await page.goto('/');
	await page.getByLabel('Email').fill('jeroen@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

test('albums: create, fill, play on the frame, schedule, reorder, delete', async ({ page }) => {
	const st = libraryState();
	const frame = st.frames[0]!;
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=library`);

	// "On this day" shows the photo from three years ago.
	await expect(page.getByRole('heading', { name: 'On this day' })).toBeVisible();
	await expect(page.getByRole('button', { name: /3 years ago · Beach day/ })).toBeVisible();

	// New album from the album bar.
	await page.getByRole('button', { name: 'New album' }).click();
	await page.getByLabel('Album name').fill('Christmas');
	await page.getByRole('button', { name: 'Create album' }).click();
	await expect(page.getByRole('heading', { name: 'This album is empty' })).toBeVisible();
	await page.getByRole('button', { name: 'Choose photos' }).click();

	// Select three photos and add them to the album.
	const picks = page.getByRole('checkbox', { name: 'Select' });
	await picks.nth(0).click();
	await picks.nth(1).click();
	await picks.nth(2).click();
	await page.getByRole('toolbar', { name: 'Selection actions' }).getByRole('button', { name: /album/i }).click();
	const dialog = page.getByRole('dialog', { name: 'Add 3 photos to an album' });
	await expect(dialog.getByRole('radio', { name: /Christmas/ })).toBeChecked();
	await dialog.getByRole('button', { name: 'Add' }).click();
	await expect(page.getByText('3 photos added to “Christmas”')).toBeVisible();
	const bulk = st.calls.find((c) => c.path.endsWith('/placements:bulk'));
	expect(bulk?.body).toMatchObject({ action: 'add_to_album', album_id: st.albums[frame.id]![0]!.id });

	// Nothing overflows sideways, even with the album bar and the memories strip.
	expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);

	// Open the album and play it on the frame.
	await page.getByRole('button', { name: /Christmas 3/ }).click();
	await expect(page.getByRole('heading', { name: 'Christmas' })).toBeVisible();
	await page.getByRole('button', { name: 'Play on frame' }).click();
	await expect(page.getByText('Oma’s frame now plays “Christmas”')).toBeVisible();
	const play = st.calls.find((c) => c.method === 'PATCH' && c.path.endsWith('/settings'));
	expect(play?.headers?.['if-match']).toBe('"3"');
	expect(play?.body).toEqual({ slideshow: { album_id: st.albums[frame.id]![0]!.id } });
	await expect(page.getByText('Playing on the frame')).toBeVisible();

	// Schedule it for December.
	await page.getByRole('button', { name: 'Schedule', exact: true }).click();
	await page.getByRole('checkbox', { name: 'Play automatically every year' }).check();
	await page.getByLabel('From month').selectOption('12');
	await page.getByLabel('From day').selectOption('1');
	await page.getByLabel('Until month').selectOption('12');
	await page.getByLabel('Until day').selectOption('31');
	await expect(page.getByText('Plays from 1 December until 31 December, every year.')).toBeVisible();
	await page.getByRole('button', { name: 'Save' }).click();
	await expect(page.getByText('1 December – 31 December')).toBeVisible();
	const sched = st.calls.find((c) => c.method === 'PATCH' && c.path.includes('/albums/'));
	expect(sched?.body).toEqual({ schedule: { from_month_day: '12-01', to_month_day: '12-31' } });

	// Reorder with the keyboard and save.
	const before = [...st.albums[frame.id]![0]!.placement_ids!];
	await page.getByRole('button', { name: 'Change the order' }).click();
	await page.getByRole('button', { name: 'Move photo 1 of 3' }).focus();
	await page.keyboard.press('ArrowRight');
	await page.getByRole('button', { name: 'Save order' }).click();
	await expect(page.getByText('Order saved')).toBeVisible();
	expect(st.albums[frame.id]![0]!.placement_ids).toEqual([before[1], before[0], before[2]]);

	// Rename, then delete: the frame goes back to all photos.
	await page.getByRole('button', { name: 'Rename' }).click();
	await page.getByLabel('Album name').fill('Kerst');
	await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
	await expect(page.getByRole('heading', { name: 'Kerst' })).toBeVisible();
	await page.getByRole('button', { name: 'Delete album' }).click();
	await expect(page.getByText('the frame goes back to showing all photos')).toBeVisible();
	await page.getByRole('dialog').getByRole('button', { name: 'Delete album' }).click();
	await expect(page.getByText('Album “Kerst” deleted')).toBeVisible();
	await expect(page.getByRole('button', { name: 'All photos' })).toHaveAttribute('aria-pressed', 'true');
});

test('settings: on this day and the album to show', async ({ page }) => {
	const st = libraryState();
	const frame = st.frames[0]!;
	st.albums[frame.id] = [{ id: uid('a'), name: 'Summer', version: 1, placement_count: 0, placement_ids: [] }];
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=settings`);
	await page.getByLabel('Show on the frame').selectOption({ label: 'Summer' });
	await page.getByRole('checkbox', { name: /On this day/ }).check();
	await page.getByRole('button', { name: 'Save' }).click();
	await expect(page.getByText('Settings sent to Oma’s frame')).toBeVisible();
	const saved = st.calls.find((c) => c.method === 'PATCH' && c.path.endsWith('/settings'));
	expect(saved?.body).toEqual({ slideshow: { album_id: st.albums[frame.id]![0]!.id, on_this_day: true } });
});

test('ownership: transfer to an admin, then leave', async ({ page }) => {
	const st = libraryState();
	const frame = st.frames[0]!;
	const lisa = st.members[frame.id]![1]!;
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}`);

	// The owner can't simply leave: leaving starts a hand-over.
	await page.getByRole('button', { name: 'Leave frame' }).click();
	const dlg = page.getByRole('dialog', { name: 'Leave Oma’s frame' });
	await expect(dlg.getByRole('radio', { name: /Lisa/ })).toBeChecked(); // admins first
	await dlg.getByRole('button', { name: 'Continue' }).click();
	await expect(page.getByRole('dialog', { name: 'Make Lisa the owner?' })).toBeVisible();
	await expect(page.getByText('You leave the frame right after.')).toBeVisible();
	await page.getByRole('button', { name: 'Back' }).click();
	await page.getByRole('dialog').getByRole('button', { name: 'Continue' }).click();
	await page.getByRole('button', { name: 'Transfer and leave' }).click();

	await expect(page).toHaveURL(/\/frames$/);
	await expect(page.getByText('Lisa now owns Oma’s frame. You left the frame.')).toBeVisible();
	const calls = st.calls.filter((c) => c.method === 'POST' && /\/(transfer|leave)$/.test(c.path)).map((c) => [c.path.split('/').pop(), c.body]);
	expect(calls).toEqual([
		['transfer', { user_id: lisa.user_id }],
		['leave', { remove_my_items: false }]
	]);
});

test('ownership: make a member owner from the people list', async ({ page }) => {
	const st = libraryState();
	const frame = st.frames[0]!;
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=people`);
	await page.getByRole('button', { name: 'Edit Tom' }).click();
	await page.getByRole('button', { name: 'Make owner' }).click();
	await expect(page.getByRole('dialog').getByRole('radio', { name: /Tom/ })).toBeChecked();
	await page.getByRole('dialog').getByRole('button', { name: 'Continue' }).click();
	await expect(page.getByText('You stay on the frame as an admin.')).toBeVisible();
	await page.getByRole('button', { name: 'Transfer ownership' }).click();
	await expect(page.getByText('Tom is now the owner')).toBeVisible();
	await expect(page.getByRole('button', { name: 'Edit Tom' })).toHaveCount(0); // admins can't edit the owner
});

test('move to a new frame with its code', async ({ page }) => {
	const st = libraryState();
	const frame = st.frames[0]!;
	st.replaceCodes = ['55512345'];
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}`);
	await page.getByRole('button', { name: 'Move', exact: true }).click();
	const code = page.getByLabel('Code on the new frame');
	await code.fill('1111 2222');
	await expect(page.getByRole('dialog').getByRole('alert')).toContainText('doesn’t match a frame');
	await code.fill('5551 2345');
	await expect(page.getByRole('heading', { name: 'All moved' })).toBeVisible();
	const call = st.calls.filter((c) => c.path.endsWith('/replace-device')).pop();
	expect(call?.body).toEqual({ code: '55512345' });
});

test('trim a video and send it without sound', async ({ page }) => {
	const st = libraryState();
	await mockApi(page, st);
	await signIn(page);
	await page.goto('/send');
	await page.getByTestId('file-input').setInputFiles({ name: 'clip.webm', mimeType: 'video/webm', buffer: readFileSync('tests/fixtures/clip.webm') });
	await expect(page.locator('.duration')).toContainText('0:08');
	await page.getByRole('button', { name: 'Adjust framing of item 1' }).click();
	await expect(page.getByRole('heading', { name: 'Trim video' })).toBeVisible();
	await expect(page.locator('.strip img')).toHaveCount(10);

	// Move the start to 2 s and the end to 6.5 s with the keyboard.
	const start = page.getByRole('slider', { name: 'Start of the part to send' });
	const end = page.getByRole('slider', { name: 'End of the part to send' });
	await start.focus();
	await page.keyboard.press('Shift+ArrowRight');
	await page.keyboard.press('Shift+ArrowRight');
	await end.focus();
	for (let i = 0; i < 15; i++) await page.keyboard.press('ArrowLeft');
	await expect(start).toHaveAttribute('aria-valuenow', '2');
	await expect(end).toHaveAttribute('aria-valuenow', '6.5');
	// Never shorter than 3 s.
	await start.focus();
	await page.keyboard.press('PageUp');
	await expect(start).toHaveAttribute('aria-valuenow', '3.5');
	await page.keyboard.press('Shift+ArrowLeft');
	await page.keyboard.press('ArrowLeft');
	await page.keyboard.press('ArrowLeft');
	await page.keyboard.press('ArrowLeft');
	await page.keyboard.press('ArrowLeft');
	await page.keyboard.press('ArrowLeft');
	await expect(start).toHaveAttribute('aria-valuenow', '2');
	await page.getByRole('checkbox', { name: /Include sound/ }).uncheck();
	await page.getByRole('button', { name: 'Done' }).click();
	await expect(page.locator('.duration')).toContainText('0:05');

	await page.getByRole('button', { name: /^Send/ }).first().click();
	await expect(page).toHaveURL(/\/history\/.+/);
	const send = st.calls.find((c) => c.path === '/v1/sends' && c.method === 'POST');
	const item = (send!.body as S['SendCreate']).items[0]!;
	expect(item.include_audio).toBe(false);
	if (st.uploads[item.upload_id]?.prepared === 'video-master-v1') {
		// WebCodecs made the master: the trim is already applied, so none is sent.
		expect(item.trim).toBeUndefined();
		expect(item.duration_ms).toBeGreaterThan(4300);
		expect(item.duration_ms).toBeLessThan(4700);
		expect(st.uploads[item.upload_id]!.filename).toBe('clip.mp4');
		return;
	}
	// The clip is 8.008 s long, so the end handle started at 8.008 and the start was pushed from there.
	expect(item.trim!.start_ms).toBeGreaterThanOrEqual(2000);
	expect(item.trim!.start_ms).toBeLessThan(2020);
	expect(item.trim!.end_ms! - item.trim!.start_ms!).toBe(4500);
});

test('notifications: offered after the first send, managed in settings', async ({ page }) => {
	await page.addInitScript(() => {
		let perm: NotificationPermission = 'default';
		const w = window as unknown as { __sub: unknown };
		w.__sub = null;
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
		const sub = {
			endpoint: 'https://push.example.net/send/abc',
			options: { applicationServerKey: null },
			toJSON: () => ({ endpoint: 'https://push.example.net/send/abc', keys: { p256dh: 'BPk-key', auth: 'auth-secret' } }),
			unsubscribe: () => {
				w.__sub = null;
				return Promise.resolve(true);
			}
		};
		const reg = {
			pushManager: {
				getSubscription: () => Promise.resolve(w.__sub),
				subscribe: () => {
					w.__sub = sub;
					return Promise.resolve(sub);
				}
			}
		};
		Object.defineProperty(navigator, 'serviceWorker', {
			value: { register: () => Promise.resolve(reg), ready: Promise.resolve(reg), getRegistration: () => Promise.resolve(reg), addEventListener: () => {} },
			configurable: true
		});
	});
	const st = libraryState();
	await mockApi(page, st);
	await signIn(page);

	// Not asked on load.
	await expect(page.getByText('Want to know when it arrives?')).toHaveCount(0);

	// After a send: a gentle offer.
	await page.goto('/send');
	await page.getByTestId('file-input').setInputFiles({ name: 'a.png', mimeType: 'image/png', buffer: await gradientPng(64, 48) });
	await page.getByRole('button', { name: /^Send/ }).first().click();
	await expect(page).toHaveURL(/\/history\/.+/);
	await expect(page.getByRole('heading', { name: 'Want to know when it arrives?' })).toBeVisible();
	await page.getByRole('button', { name: 'Turn on' }).click();
	await expect(page.getByText('Notifications are on')).toBeVisible();
	await expect(page.getByText('Want to know when it arrives?')).toHaveCount(0);
	expect(st.pushEndpoints).toHaveLength(1);
	expect(st.pushEndpoints[0]).toMatchObject({
		kind: 'webpush',
		endpoint: 'https://push.example.net/send/abc',
		webpush_keys: { p256dh: 'BPk-key', auth: 'auth-secret' },
		locale: 'en-GB'
	});

	// Settings: the switch reflects it and turns it off again.
	await page.getByRole('link', { name: 'Settings' }).first().click();
	const toggle = page.getByRole('checkbox', { name: /Notifications on this device/ });
	await expect(toggle).toBeChecked();
	await toggle.uncheck();
	await expect(page.getByText('Notifications are off on this device')).toBeVisible();
	await expect.poll(() => st.pushEndpoints.length).toBe(0);
});
