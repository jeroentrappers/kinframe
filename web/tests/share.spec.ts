import { expect, test, type Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { defaultSettings, emptyState, makeFrame, mockApi, uid, type MockState } from './mock-api';

type S = components['schemas'];
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();

const item = (st: MockState, i: number, sender = 'Jeroen'): S['Item'] => ({
	id: uid('i'),
	kind: 'photo',
	status: 'ready',
	sender_id: st.me!.id,
	sender_name: sender,
	caption: i === 0 ? 'Beach day' : null,
	created_at: ago(i * 60)
});

/** Oma's frame with four photos; Dad's frame already has the first; Tom's frame doesn't let us send. */
function shareState() {
	const st = emptyState();
	st.me = { id: uid('u'), display_name: 'Jeroen', email: 'jeroen@example.org', avatar_url: null, locale: 'en', created_at: ago(9999) };
	const oma = makeFrame({ name: 'Oma’s frame', role: 'owner' });
	const dad = makeFrame({ name: 'Dad’s frame', role: 'member', permissions: { send: true } });
	const cabin = makeFrame({ name: 'Cabin', role: 'admin' });
	const tom = makeFrame({ name: 'Tom’s frame', role: 'member', permissions: { send: false, see_library: true } });
	st.frames = [oma, dad, cabin, tom];
	const items = [0, 1, 2, 3].map((i) => item(st, i, i === 3 ? 'Lisa' : 'Jeroen'));
	st.placements[oma.id] = items.map((it, i) => ({
		id: uid('p'),
		frame_id: oma.id,
		item: it,
		status: 'seen' as const,
		hidden: false,
		trashed: false,
		received_at: ago(i * 60),
		...(i === 3 ? { shared_by_user_id: uid('u'), shared_by_name: 'Ben' } : {})
	}));
	st.placements[dad.id] = [{ id: uid('p'), frame_id: dad.id, item: items[0]!, status: 'seen', hidden: false, trashed: false, received_at: ago(5) }];
	for (const f of st.frames) st.settings[f.id] = defaultSettings(1);
	return { st, oma, dad, cabin, tom };
}

async function signIn(page: Page) {
	await page.goto('/');
	await page.getByLabel('Email').fill('jeroen@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

test('library: share a selection to another frame, then undo', async ({ page }) => {
	const { st, oma, dad, cabin } = shareState();
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${oma.id}?tab=library`);

	const picks = page.getByRole('checkbox', { name: 'Select' });
	await picks.nth(0).click();
	await picks.nth(1).click();
	await page.getByRole('toolbar', { name: 'Selection actions' }).getByRole('button', { name: /^Share/ }).click();

	// Only frames you can send to, except this one; Dad's frame already has one of the two.
	const dialog = page.getByRole('dialog', { name: 'Share to another frame' });
	await expect(dialog.getByText('Cabin')).toBeVisible();
	await expect(dialog.locator('label', { hasText: 'Dad’s frame' })).toContainText('Has some');
	await expect(dialog.getByText('Tom’s frame')).toHaveCount(0);
	await expect(dialog.getByText('Oma’s frame')).toHaveCount(0);
	const dry = st.calls.find((c) => c.path.endsWith('/placements:share'));
	expect(dry?.body).toMatchObject({ dry_run: true });

	await dialog.locator('label', { hasText: 'Cabin' }).click();
	await dialog.locator('label', { hasText: 'Dad’s frame' }).click();
	await dialog.getByRole('button', { name: 'Share', exact: true }).click();
	await expect(page.getByText('Shared to Dad’s frame and Cabin')).toBeVisible();

	const shared = st.calls.filter((c) => c.path === `/v1/frames/${oma.id}/placements:share`).at(-1)!;
	expect(shared.headers?.['idempotency-key']).toBeTruthy();
	expect((shared.body as { frame_ids: string[] }).frame_ids.sort()).toEqual([cabin.id, dad.id].sort());
	// No upload: the same items, credited to the sharer.
	expect(st.calls.some((c) => c.path === '/v1/uploads' || c.path === '/v1/sends')).toBe(false);
	expect(st.placements[cabin.id]).toHaveLength(2);
	expect(st.placements[dad.id]).toHaveLength(2);
	expect(st.placements[cabin.id]![0]!.shared_by_name).toBe('Jeroen');
	await expect(page.getByRole('toolbar', { name: 'Selection actions' })).toHaveCount(0);

	// Undo removes exactly what was created.
	await page.getByRole('button', { name: 'Undo' }).click();
	await expect(page.getByText('Sharing undone')).toBeVisible();
	expect(st.placements[cabin.id]).toHaveLength(0);
	expect(st.placements[dad.id]).toHaveLength(1);
	const purges = st.calls.filter((c) => c.path.endsWith('/placements:bulk'));
	expect(purges.map((c) => (c.body as { action: string }).action)).toEqual(['purge', 'purge']);
});

test('library: a photo shared by someone else shows who shared it, and can be passed on', async ({ page }) => {
	const { st, oma, dad, cabin } = shareState();
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/frames/${oma.id}?tab=library`);

	await page.getByRole('button', { name: 'Photo from Lisa' }).click();
	const viewer = page.getByRole('dialog', { name: 'From Lisa' });
	await expect(viewer.getByText('Shared by Ben')).toBeVisible();
	await viewer.getByRole('button', { name: 'Share', exact: true }).click();

	const dialog = page.getByRole('dialog', { name: 'Share to another frame' });
	await dialog.locator('label', { hasText: 'Cabin' }).click();
	await dialog.getByRole('button', { name: 'Share', exact: true }).click();
	await expect(page.getByText('Shared to Cabin')).toBeVisible();
	expect(st.placements[cabin.id]![0]!.item.sender_name).toBe('Lisa');
	expect(st.placements[dad.id]).toHaveLength(1);

	// Sharing it again: that frame now has it.
	await page.getByRole('button', { name: 'Photo from Lisa' }).click();
	await viewer.getByRole('button', { name: 'Share', exact: true }).click();
	await expect(dialog.locator('label', { hasText: 'Cabin' })).toContainText('Already there');
	await expect(dialog.getByRole('checkbox', { name: /Cabin/ })).toBeDisabled();
});

test('history: share a sent photo to another frame', async ({ page }) => {
	const { st, oma, cabin } = shareState();
	const it = item(st, 0);
	st.sends = [
		{
			id: uid('5'),
			created_at: ago(30),
			items: [it],
			frames: [
				{
					frame_id: oma.id,
					frame_name: oma.name,
					online: true,
					placements: [{ placement_id: uid('p'), item_id: it.id, status: 'seen', reaction: 'love', replies: [] }]
				},
				{
					frame_id: cabin.id,
					frame_name: cabin.name,
					online: true,
					placements: [{ placement_id: uid('p'), item_id: it.id, status: 'delivered', reaction: null, replies: [], shared_by_name: 'Ben' }]
				}
			],
			summary: { processing: 0, pending: 0, delivered: 1, seen: 1, failed: 0 }
		}
	];
	st.placements[cabin.id] = [{ id: st.sends[0]!.frames[1]!.placements[0]!.placement_id, frame_id: cabin.id, item: it, status: 'delivered', hidden: false, trashed: false, received_at: ago(10) }];
	await mockApi(page, st);
	await signIn(page);
	await page.goto(`/history/${st.sends[0]!.id}`);

	// Ben shared it to the cabin: the history says so.
	await expect(page.getByText('Shared by Ben')).toBeVisible();
	await page.getByRole('button', { name: 'Share to another frame' }).click();
	const dialog = page.getByRole('dialog', { name: 'Share to another frame' });
	await expect(dialog.locator('label', { hasText: 'Cabin' })).toContainText('Already there');
	await dialog.locator('label', { hasText: 'Dad’s frame' }).click();
	await dialog.getByRole('button', { name: 'Share', exact: true }).click();
	await expect(page.getByText('Shared to Dad’s frame')).toBeVisible();
	const call = st.calls.filter((c) => c.path.endsWith('/placements:share')).at(-1)!;
	expect(call.path).toBe(`/v1/frames/${oma.id}/placements:share`);
	expect(call.body).toMatchObject({ placement_ids: [st.sends[0]!.frames[0]!.placements[0]!.placement_id] });
});
