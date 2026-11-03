import { expect, test } from '@playwright/test';
import { emptyState, gradientPng, makeFrame, mockApi, push } from './mock-api';

test('dev sign-in → add frame → send → history', async ({ page }) => {
	const state = emptyState();
	const frame = makeFrame({ name: 'New frame', role: 'owner', online: true });
	state.frames = []; // the frame only appears in the list after redeeming
	state.codes['48213907'] = { frameId: frame.id, status: 'claimed' };
	await mockApi(page, state);
	// redeem makes the frame visible to /v1/frames
	await page.route('**/v1/frames/redeem', async (route) => {
		state.frames.push(frame);
		await route.fallback();
	});

	// 1. Landing shows the server name and the dev sign-in form.
	await page.goto('/');
	await expect(page.getByText('Connected to Testfamilie')).toBeVisible();
	await page.getByLabel('Email').fill('oma@example.org');
	await page.getByLabel('Name').fill('Oma');
	await page.getByRole('button', { name: 'Continue' }).click();

	// 2. Empty frames list with a next step.
	await expect(page).toHaveURL(/\/frames$/);
	await expect(page.getByRole('heading', { name: 'Add your first frame' })).toBeVisible();
	await page.getByRole('link', { name: 'Add your first frame' }).click();

	// 3. Add frame by code; pasting with a space is fine and redeems automatically.
	await expect(page).toHaveURL(/\/frames\/add/);
	const code = page.getByLabel('Frame code');
	await code.fill('4821 3907');
	await expect(page.getByRole('heading', { name: 'It’s yours!' })).toBeVisible();
	const redeem = state.calls.find((c) => c.path === '/v1/frames/redeem');
	expect(redeem?.body).toEqual({ code: '48213907' });
	expect(redeem?.headers?.['idempotency-key']).toBeTruthy();

	await page.getByLabel('Frame name').fill('Oma’s frame');
	await page.getByLabel(/Where is it/).fill('Living room');
	await page.getByRole('button', { name: 'Save and send photos' }).click();

	// 4. Send: the new frame is preselected; add two photos with a caption.
	await expect(page).toHaveURL(/\/send\?frame=/);
	await expect(page.getByRole('checkbox', { name: /Oma’s frame/ })).toBeChecked();
	await page.getByTestId('file-input').setInputFiles([
		{ name: 'beach.png', mimeType: 'image/png', buffer: await gradientPng(320, 240, 10) },
		{ name: 'garden.png', mimeType: 'image/png', buffer: await gradientPng(240, 320, 40) }
	]);
	await expect(page.getByLabel('Caption for item 1')).toBeVisible();
	await page.getByLabel('Caption for item 1').fill('Zomer aan zee ☀️');
	await page.getByRole('button', { name: 'Send all 2' }).first().click();

	// 5. Lands on the send in history with per-frame status.
	await expect(page).toHaveURL(/\/history\/.+/);
	await expect(page.getByText('Oma’s frame').first()).toBeVisible();
	await expect(page.getByText('Zomer aan zee ☀️')).toBeVisible();

	const send = state.calls.find((c) => c.path === '/v1/sends' && c.method === 'POST');
	expect(send?.headers?.['idempotency-key']).toBeTruthy();
	const body = send!.body as { frame_ids: string[]; items: { upload_id: string; caption: string | null; fit: string }[] };
	expect(body.frame_ids).toEqual([frame.id]);
	expect(body.items).toHaveLength(2);
	expect(body.items[0]).toMatchObject({ upload_id: 'up_1', caption: 'Zomer aan zee ☀️', fit: 'fill' });
	expect(Object.values(state.uploads).every((u) => u.offset === u.length)).toBe(true);

	// 6. Live: delivery + a reaction arrive over the websocket.
	const s = state.sends[0]!;
	for (const it of s.items) it.status = 'ready';
	for (const p of s.frames[0]!.placements) p.status = 'seen';
	await expect.poll(() => state.ws !== null).toBe(true);
	push(state, { type: 'send.updated', send: { id: s.id } });
	await expect(page.getByText('2 seen · 2 of 2 delivered')).toBeVisible();
	push(state, { type: 'reaction', placement_id: s.frames[0]!.placements[0]!.placement_id, frame_id: frame.id, reaction: 'love', by_frame_name: 'Oma’s frame' });
	await expect(page.getByText('Love').first()).toBeAttached();

	// 7. History list shows it.
	await page.goto('/history');
	await expect(page.getByRole('heading', { name: 'Today' })).toBeVisible();
	await expect(page.getByText('2 photos')).toBeVisible();
});

test('frame-code deep link survives sign-in', async ({ page }) => {
	const state = emptyState();
	const frame = makeFrame({ name: 'Opa’s frame', role: 'member', owner_name: 'Jeroen' });
	state.frames = [frame];
	state.codes['12345678'] = { frameId: frame.id, status: 'joined' };
	await mockApi(page, state);

	await page.goto('/f/12345678');
	await expect(page.getByText('Sign in to add the frame')).toBeVisible();
	await page.getByLabel('Email').fill('lisa@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await expect(page.getByRole('heading', { name: 'You joined Opa’s frame' })).toBeVisible();
});

test('wrong code shows a friendly, localized error', async ({ page }) => {
	await mockApi(page);
	await page.goto('/');
	await page.getByLabel('Email').fill('x@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.goto('/frames/add');
	const code = page.getByLabel('Frame code');
	await code.pressSequentially('1111');
	await expect(code).toHaveValue('1111');
	await code.pressSequentially('2222');
	await expect(code).toHaveValue('1111 2222');
	await expect(page.getByRole('alert')).toContainText('doesn’t match a frame');
	await expect(code).toBeFocused();
});

test('set a frame location from place search and turn the weather on', async ({ page }) => {
	const state = emptyState();
	const frame = makeFrame({ name: 'Oma’s frame', role: 'owner' });
	state.frames = [frame];
	await mockApi(page, state);
	await page.goto('/');
	await page.getByLabel('Email').fill('jeroen@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);

	await page.goto(`/frames/${frame.id}?tab=settings`);
	await expect(page.getByRole('heading', { name: 'Location & weather' })).toBeVisible();
	const weather = page.getByRole('checkbox', { name: /Weather forecast/ });
	await expect(weather).toBeDisabled();

	// A friendly message when the search is rate-limited.
	const search = page.getByRole('combobox', { name: 'Find a town or city' });
	await search.fill('busy');
	await expect(page.getByRole('alert')).toContainText('Too many searches');

	// Debounced suggestions, keyboard selection.
	await search.fill('gen');
	const options = page.getByRole('listbox', { name: 'Places' }).getByRole('option');
	await expect(options).toHaveCount(3);
	await expect(options.first()).toContainText('Flanders, Belgium');
	await search.press('ArrowDown');
	await expect(options.nth(1)).toHaveAttribute('aria-selected', 'true');
	await search.press('ArrowUp');
	await search.press('Enter');
	await expect(page.getByText('51.05°, 3.72°')).toBeVisible();
	await expect(weather).toBeEnabled();
	await expect(weather).toBeChecked();
	const geocode = state.calls.filter((c) => c.path === '/v1/geocode');
	expect(geocode.every((c) => c.method === 'GET')).toBe(true);

	await page.getByText('°F').click();
	await page.getByRole('button', { name: 'Save' }).click();
	await expect(page.getByText('Settings sent to Oma’s frame')).toBeVisible();
	const saved = state.calls.find((c) => c.method === 'PATCH' && c.path.endsWith('/settings'));
	expect(saved?.headers?.['if-match']).toBe('"1"');
	expect(saved?.body).toEqual({
		location: { name: 'Gent', lat: 51.05, lon: 3.7167 },
		widgets: { weather: true },
		locale: { temperature_unit: 'f' }
	});

	// Removing the place sends null.
	await page.getByRole('button', { name: 'Remove', exact: true }).click();
	await expect(weather).toBeDisabled();
	await page.getByRole('button', { name: 'Save' }).click();
	await expect.poll(() => state.calls.filter((c) => c.method === 'PATCH').length).toBe(2);
	const removed = state.calls.filter((c) => c.method === 'PATCH')[1]!;
	expect(removed.headers?.['if-match']).toBe('"2"');
	expect(removed.body).toEqual({ location: null, widgets: { weather: false } });
});
