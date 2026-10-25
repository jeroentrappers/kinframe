// The two live TV modes: mirroring a frame, and a browser that is a frame itself.
import { expect, test, type Page, type WebSocketRoute } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { defaultSettings, emptyState, makeFrame, mockApi, photoSvg, uid, type MockState } from './mock-api';

type S = components['schemas'];
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();

function state(): MockState {
	const st = emptyState();
	st.me = { id: uid('u'), display_name: 'Jeroen', email: 'jeroen@example.org', avatar_url: null, locale: 'en', created_at: ago(9999) };
	const frame = makeFrame({ name: 'Oma’s frame', role: 'owner', member_count: 2 });
	st.frames = [frame];
	st.placements[frame.id] = [
		{
			id: uid('p'), frame_id: frame.id, status: 'seen', hidden: false, trashed: false, received_at: ago(60),
			item: { id: uid('i'), kind: 'photo', status: 'ready', sender_id: st.me.id, sender_name: 'Lisa', caption: 'Beach day', created_at: ago(60) }
		}
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

function mirrorState(frameId: string, o: Partial<S['MirrorState']> = {}): S['MirrorState'] {
	const now = new Date().toISOString();
	return {
		type: 'mirror', server_time: now, frame_id: frameId, frame_name: 'Oma’s frame', online: true, screen: 'on', expires_at: ago(-600),
		settings: { interval_s: 30, transition: 'crossfade', transition_ms: 800, fill: true, background: 'blur', caption: true, clock: true },
		showing: {
			placement_id: 'p-1', started_at: now, transition: 'crossfade', transition_ms: 800, ken_burns: false,
			slides: [{ placement_id: 'p-1', kind: 'photo', url: 'http://localhost:4173/v1/media/signed/p1/master?sig=x', mime: 'image/svg+xml', caption: 'Beach day', sender_name: 'Lisa', received_at: now, width: 640, height: 480 }]
		},
		...o
	};
}

test('mirror this frame: the TV follows what the frame shows, and sleeps with it', async ({ page }) => {
	const st = state();
	const frame = st.frames[0]!;
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=library`);
	await page.getByRole('button', { name: 'Show on TV' }).first().click();
	const dialog = page.getByRole('dialog', { name: 'Show on TV' });
	await dialog.getByText('Mirror this frame').click();
	await dialog.getByRole('button', { name: /Open on the TV’s browser/ }).click();
	const field = dialog.getByRole('textbox', { name: 'Open this link on the TV' });
	await expect(field).toHaveValue(/mode=mirror/);
	const create = st.calls.find((c) => c.method === 'POST' && c.path.endsWith('/tv-links'));
	expect(create?.body).toMatchObject({ purpose: 'mirror' });
	expect(create?.body).not.toHaveProperty('placement_ids');

	// The frame's remote moves the frame (and so the TV).
	await dialog.getByRole('toolbar', { name: 'Move the frame and the TV together' }).getByRole('button', { name: 'Next' }).click();
	await expect.poll(() => st.calls.find((c) => c.path.endsWith('/commands'))?.body).toEqual({ type: 'next' });

	const url = new URL(await field.inputValue());
	const tv = await page.context().browser()!.newPage();
	let socket: WebSocketRoute | null = null;
	await tv.routeWebSocket(/\/v1\/tv\/[^/]+\/mirror$/, (ws) => {
		socket = ws;
		ws.send(JSON.stringify(mirrorState(frame.id)));
	});
	await mockApi(tv, st);
	await tv.goto(url.pathname + url.search);
	await expect(tv.locator('.cap')).toHaveText('Beach day');
	await expect(tv.getByText('Live from Oma’s frame')).toBeVisible();
	await expect.poll(() => socket !== null).toBe(true);
	socket!.send(JSON.stringify(mirrorState(frame.id, { screen: 'sleeping' })));
	await expect(tv.getByRole('heading', { name: 'Oma’s frame is asleep' })).toBeVisible();
	socket!.send(JSON.stringify(mirrorState(frame.id, { online: false, showing: null })));
	await expect(tv.getByRole('heading', { name: 'Oma’s frame is offline' })).toBeVisible();
	socket!.send(JSON.stringify({ type: 'ended', reason: 'revoked' }));
	await expect(tv.getByRole('heading', { name: 'This TV link doesn’t work' })).toBeVisible();
	await tv.close();
});

test('a browser becomes a frame: code, claim, photos, a reaction, removal', async ({ page }) => {
	const st = emptyState();
	await mockApi(page, st);
	let claimed = false;
	const calls: { method: string; path: string; body: unknown }[] = [];
	const placement = {
		id: 'pl-1', frame_id: 'f-1', status: 'ready', hidden: false, trashed: false, received_at: ago(5), album_ids: [],
		item: { id: 'it-1', kind: 'photo', status: 'ready', sender_id: 'u-1', sender_name: 'Anna', caption: 'Hello from the beach', created_at: ago(5), width: 640, height: 480 },
		media: { url: 'http://localhost:4173/v1/device/media/pl-1', sha256: 'x', bytes: 1, mime: 'image/svg+xml', width: 640, height: 480 }
	};
	await page.route(/\/v1\/devices?\//, async (route) => {
		const req = route.request();
		const path = new URL(req.url()).pathname;
		let body: unknown;
		try {
			body = req.postDataJSON();
		} catch {
			body = undefined;
		}
		calls.push({ method: req.method(), path, body });
		const json = (status: number, v: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(v) });
		if (path === '/v1/devices/enroll') return json(201, { device_id: 'd-1', device_secret: 's' });
		if (path === '/v1/devices/token') return json(200, { access_token: 'devtok', expires_in: 3600 });
		if (path === '/v1/device/state') return json(200, { device_id: 'd-1', claimed, latest_seq: 3, ...(claimed ? { frame_id: 'f-1', frame_name: 'Living room TV', owner_name: 'Jeroen' } : {}) });
		if (path === '/v1/device/code') return json(200, { code: '12345678', expires_at: ago(-600), join_url: 'http://localhost:4173/f/12345678', claimed });
		if (path === '/v1/device/changes')
			return json(200, {
				changes: [
					{ seq: 1, type: 'settings', settings: { version: 1, slideshow: { interval_s: 30 }, widgets: { caption: true } } },
					{ seq: 2, type: 'frame', frame: { name: 'Living room TV', owner_name: 'Jeroen' } },
					{ seq: 3, type: 'placement.upsert', placement }
				],
				next_seq: 3,
				has_more: false
			});
		if (path.startsWith('/v1/device/media/')) return route.fulfill({ status: 200, contentType: 'image/svg+xml', body: photoSvg(2) });
		return route.fulfill({ status: 204 });
	});
	let socket: WebSocketRoute | null = null;
	await page.routeWebSocket(/\/v1\/device\/ws/, (ws) => {
		socket = ws;
		ws.onMessage(() => {});
		ws.send(JSON.stringify({ type: 'hello', latest_seq: 3 }));
	});

	await page.goto('/tv/frame');
	await expect(page.getByRole('heading', { name: 'Make this screen a kinframe' })).toBeVisible();
	await expect(page.getByText('1234 5678')).toBeVisible();
	expect(calls.find((c) => c.path === '/v1/devices/enroll')?.body).toMatchObject({ kind: 'browser', hardware: { brand: 'web' } });
	expect(await page.evaluate(() => localStorage.getItem('kinframe.browser-frame.v1'))).toContain('d-1');

	claimed = true;
	await expect.poll(() => socket !== null).toBe(true);
	socket!.send(JSON.stringify({ type: 'claimed', owner_name: 'Jeroen' }));
	await expect(page.locator('.cap')).toHaveText('Hello from the beach');
	// Media load with the device token in the query string (no headers on <img>).
	await expect(page.locator('img.media')).toHaveAttribute('src', /\/v1\/device\/media\/pl-1\?access_token=devtok/);

	await page.keyboard.press('Enter');
	const bar = page.getByRole('toolbar', { name: 'React' });
	await expect(bar).toBeVisible();
	await page.keyboard.press('Enter');
	await expect.poll(() => calls.find((c) => c.method === 'PUT')?.body).toEqual({ reaction: 'love' });
	await expect(page.getByText('Sent to Anna')).toBeVisible();

	socket!.send(JSON.stringify({ type: 'revoked' }));
	await expect(page.getByRole('heading', { name: 'This screen was removed' })).toBeVisible();
	expect(await page.evaluate(() => localStorage.getItem('kinframe.browser-frame.v1'))).toBeNull();
});
