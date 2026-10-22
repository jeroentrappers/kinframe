import { expect, test, type Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { castLoads, fakeCast } from './fake-cast';
import { defaultSettings, emptyState, makeFrame, mockApi, uid, type MockState } from './mock-api';

type S = components['schemas'];
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();
const CAPTIONS = ['Beach day', 'Grandpa’s birthday', 'First snow', null, 'Picnic'];

function tvState(): MockState {
	const st = emptyState();
	st.me = { id: uid('u'), display_name: 'Jeroen', email: 'jeroen@example.org', avatar_url: null, locale: 'en', created_at: ago(9999) };
	const frame = makeFrame({ name: 'Oma’s frame', role: 'owner', member_count: 3 });
	st.frames = [frame];
	st.placements[frame.id] = CAPTIONS.map((caption, i) => ({
		id: uid('p'),
		frame_id: frame.id,
		item: {
			id: uid('i'),
			kind: 'photo' as const,
			status: 'ready' as const,
			sender_id: st.me!.id,
			sender_name: i % 2 ? 'Lisa' : 'Jeroen',
			caption,
			created_at: ago(i * 60),
			captured_at: ago(i * 60 * 24 * 40)
		},
		status: 'seen' as const,
		hidden: false,
		trashed: false,
		received_at: ago(i * 60)
	}));
	st.settings[frame.id] = defaultSettings(3);
	// One send in History with two of those photos.
	const pls = st.placements[frame.id]!;
	const send: S['Send'] = {
		id: uid('s'),
		created_at: ago(30),
		items: pls.slice(0, 2).map((p) => p.item),
		frames: [
			{
				frame_id: frame.id,
				frame_name: frame.name,
				online: true,
				placements: pls.slice(0, 2).map((p) => ({ placement_id: p.id, item_id: p.item.id, status: 'seen' as const }))
			}
		],
		summary: { seen: 2 }
	};
	st.sends = [send];
	return st;
}

async function signIn(page: Page) {
	await page.goto('/');
	await page.getByLabel('Email').fill('jeroen@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

test('show on TV: a TV link plays the library full screen, and turning it off stops it', async ({ page }) => {
	const st = tvState();
	const frame = st.frames[0]!;
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=library`);

	await page.getByRole('button', { name: 'Show on TV' }).first().click();
	const dialog = page.getByRole('dialog', { name: 'Show on TV' });
	await expect(dialog.getByText('All photos and videos on Oma’s frame')).toBeVisible();
	await dialog.getByText('15 s').click();
	await dialog.getByRole('button', { name: /Open on the TV’s browser/ }).click();

	const field = dialog.getByRole('textbox', { name: 'Open this link on the TV' });
	await expect(field).toHaveValue(new RegExp(`/tv/${frame.id}\\?t=tv_`));
	const create = st.calls.find((c) => c.method === 'POST' && c.path.endsWith('/tv-links'));
	expect(create?.body).toMatchObject({ interval_seconds: 15, purpose: 'tv', expires_in_hours: 12 });
	expect(create?.body).not.toHaveProperty('placement_ids');
	const url = new URL(await field.inputValue());

	// The TV: a fresh browser context, nobody signed in.
	const tv = await page.context().browser()!.newPage();
	await mockApi(tv, st);
	await tv.goto(url.pathname + url.search);
	await expect(tv.locator('.cap')).toHaveText('Beach day');
	await expect(tv.locator('.frame-name')).toHaveText('Oma’s frame');
	await expect(tv.getByText(/^Jeroen · /)).toBeVisible();
	await tv.keyboard.press('ArrowRight');
	await expect(tv.locator('.cap')).toHaveText('Grandpa’s birthday');
	await tv.keyboard.press('ArrowLeft');
	await expect(tv.locator('.cap')).toHaveText('Beach day');
	await tv.keyboard.press('Enter');
	await expect(tv.getByText('Paused')).toBeVisible();
	// Media URLs are absolute and signed: nothing on the TV page sends an Authorization header.
	expect(st.calls.filter((c) => c.path.startsWith('/v1/tv/')).every((c) => !c.headers?.['authorization'])).toBe(true);

	// Turning the link off: the TV shows a calm notice on its next fetch.
	await dialog.getByRole('button', { name: 'Turn off' }).click();
	await expect(page.getByText('TV link turned off')).toBeVisible();
	await tv.reload();
	await expect(tv.getByRole('heading', { name: 'This TV link doesn’t work' })).toBeVisible();
	await tv.close();
});

test('TV page: expired and unknown links explain themselves', async ({ page }) => {
	const st = tvState();
	const frame = st.frames[0]!;
	st.tvLinks[frame.id] = [
		{
			id: uid('7'),
			frame_id: frame.id,
			token: 'tv_expired_token_xxxxxxxxxxxx',
			url: '',
			purpose: 'tv',
			interval_seconds: 10,
			expires_at: ago(5),
			created_at: ago(600)
		}
	];
	await mockApi(page, st);
	await page.goto(`/tv/${frame.id}?t=tv_expired_token_xxxxxxxxxxxx`);
	await expect(page.getByRole('heading', { name: 'This TV link has ended' })).toBeVisible();
	await page.goto(`/tv/${frame.id}?t=nope_nope_nope_nope_nope_nope`);
	await expect(page.getByRole('heading', { name: 'This TV link doesn’t work' })).toBeVisible();
});

test('cast a selection to a Chromecast and control it', async ({ page }) => {
	const st = tvState();
	const frame = st.frames[0]!;
	const pls = st.placements[frame.id]!;
	await fakeCast(page);
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${frame.id}?tab=library`);

	await page.getByRole('checkbox', { name: 'Select' }).nth(2).click();
	await page.getByRole('checkbox', { name: 'Select' }).nth(0).click();
	await page.getByRole('toolbar', { name: 'Selection actions' }).getByRole('button', { name: 'Show on TV' }).click();
	const dialog = page.getByRole('dialog', { name: 'Show on TV' });
	await expect(dialog.getByText('2 selected photos and videos')).toBeVisible();
	await dialog.getByRole('button', { name: /Cast to a TV/ }).click();

	await expect(dialog.getByText('Showing on Living room TV')).toBeVisible();
	const link = st.calls.find((c) => c.method === 'POST' && c.path.endsWith('/tv-links'));
	expect(link?.body).toMatchObject({ purpose: 'cast', placement_ids: [pls[0]!.id, pls[2]!.id] });
	expect(await page.evaluate(() => (window as unknown as { __castOptions: { receiverApplicationId: string } }).__castOptions.receiverApplicationId)).toBe('CC1AD845');

	await expect.poll(async () => (await castLoads(page)).length).toBe(1);
	let loads = await castLoads(page);
	expect(loads[0]).toMatchObject({ title: 'Beach day', metadataType: 4 });
	expect(loads[0]!.url).toMatch(/^http:\/\/localhost:4173\/v1\/media\/signed\/.+sig=/);

	await dialog.getByRole('button', { name: 'Next' }).click();
	await expect.poll(async () => (await castLoads(page)).length).toBe(2);
	loads = await castLoads(page);
	expect(loads[1]!.title).toBe('First snow');
	await expect(dialog.getByText('2 of 2')).toBeVisible();

	// Closing the dialog keeps the show going, with a small remote on every page.
	await dialog.getByRole('button', { name: 'Close' }).click();
	const mini = page.getByRole('toolbar', { name: 'Showing on Living room TV' });
	await expect(mini).toBeVisible();
	await mini.getByRole('button', { name: 'Pause' }).click();
	await expect(mini.getByRole('button', { name: 'Play' })).toBeVisible();
	await mini.getByRole('button', { name: 'Stop' }).click();
	await expect(mini).toBeHidden();
	expect(await page.evaluate(() => (window as unknown as { __castEnded?: boolean }).__castEnded)).toBe(true);
	// The cast-only link is cleaned up.
	await expect.poll(() => st.calls.some((c) => c.method === 'DELETE' && c.path.includes('/tv-links/'))).toBe(true);
});

test('History: show one send on the TV', async ({ page }) => {
	const st = tvState();
	const send = st.sends[0]!;
	await fakeCast(page);
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/history/${send.id}`);
	await page.getByRole('button', { name: 'Show on TV' }).click();
	const dialog = page.getByRole('dialog', { name: 'Show on TV' });
	await expect(dialog.getByText('2 selected photos and videos')).toBeVisible();
	await dialog.getByRole('button', { name: /Open on the TV’s browser/ }).click();
	await expect(dialog.getByRole('textbox', { name: 'Open this link on the TV' })).toBeVisible();
	const link = st.calls.find((c) => c.method === 'POST' && c.path.endsWith('/tv-links'));
	expect(link?.path).toBe(`/v1/frames/${send.frames[0]!.frame_id}/tv-links`);
	expect(link?.body).toMatchObject({ placement_ids: send.frames[0]!.placements.map((p) => p.placement_id) });
});
