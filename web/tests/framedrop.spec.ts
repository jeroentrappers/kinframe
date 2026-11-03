import { expect, test, type Locator, type Page } from '@playwright/test';
import { defaultSettings, emptyState, gradientPng, makeFrame, mockApi, uid } from './mock-api';

/** Oma's frame and Dad's frame take photos from us; Tom's frame doesn't. */
function dropState() {
	const st = emptyState();
	st.me = { id: uid('u'), display_name: 'Jeroen', email: 'jeroen@example.org', avatar_url: null, locale: 'en', created_at: new Date().toISOString() };
	const oma = makeFrame({ name: 'Oma’s frame', role: 'owner', online: true });
	const dad = makeFrame({ name: 'Dad’s frame', role: 'member', permissions: { send: true } });
	const tom = makeFrame({ name: 'Tom’s frame', role: 'member', permissions: { send: false, see_library: true } });
	st.frames = [oma, dad, tom];
	for (const f of st.frames) st.settings[f.id] = defaultSettings(1);
	return { st, oma, dad, tom };
}

async function signIn(page: Page) {
	await page.goto('/');
	await page.getByLabel('Email').fill('jeroen@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

type FakeFile = { name: string; type: string; data: number[] };

/**
 * Fires a synthetic drag event carrying files at an element, the way the browser does when files
 * are dragged in from the desktop. Returns whether the page handled it (preventDefault).
 */
function drag(target: Locator, type: 'dragenter' | 'dragover' | 'dragleave' | 'drop', files: FakeFile[]) {
	return target.evaluate(
		(el, { type, files }) => {
			const dt = new DataTransfer();
			for (const f of files) dt.items.add(new File([new Uint8Array(f.data)], f.name, { type: f.type }));
			const e = new DragEvent(type, { bubbles: true, cancelable: true, composed: true, dataTransfer: dt });
			el.dispatchEvent(e);
			return e.defaultPrevented;
		},
		{ type, files }
	);
}

async function beach(): Promise<FakeFile[]> {
	return [{ name: 'beach.png', type: 'image/png', data: [...(await gradientPng(64, 48, 10))] }];
}

test('frames: drop photos on a frame card to send them to that frame', async ({ page }) => {
	const { st, oma, dad, tom } = dropState();
	await mockApi(page, st);
	await signIn(page);
	const files = await beach();

	const card = (name: string) => page.getByRole('article').filter({ hasText: name });
	await expect(card('Dad’s frame')).toBeVisible();

	// Dragging files in marks the frames that take photos; the others step back.
	await drag(page.locator('main'), 'dragenter', files);
	await expect(card('Oma’s frame')).toHaveAttribute('data-drop-frame', oma.id);
	await expect(card('Oma’s frame')).toHaveClass(/drop-ready/);
	await expect(card('Tom’s frame')).toHaveClass(/drop-off/);
	await expect(card('Tom’s frame')).not.toHaveAttribute('data-drop-frame', tom.id);
	await expect(page.getByRole('status').filter({ hasText: 'Drop them on a frame to send them there' })).toBeAttached();

	// Hovering a card says where the photos will go.
	expect(await drag(card('Dad’s frame').getByRole('heading'), 'dragover', files)).toBe(true);
	await expect(card('Dad’s frame')).toHaveClass(/drop-over/);
	await expect(card('Dad’s frame').getByText('Drop to send to Dad’s frame')).toBeVisible();
	await expect(card('Oma’s frame')).not.toHaveClass(/drop-over/);
	await expect(page.getByRole('status').filter({ hasText: 'Drop to send to Dad’s frame' })).toBeAttached();

	// Dropping opens the send with the photo, and only that frame picked.
	expect(await drag(card('Dad’s frame').getByRole('heading'), 'drop', files)).toBe(true);
	await expect(page).toHaveURL(/\/send$/);
	await expect(page.getByRole('checkbox', { name: /Dad’s frame/ })).toBeChecked();
	await expect(page.getByRole('checkbox', { name: /Oma’s frame/ })).not.toBeChecked();
	await expect(page.getByLabel('Caption for item 1')).toBeVisible();
	await expect(page.getByRole('button', { name: 'Remove item 2' })).toHaveCount(0);

	// It's still the usual review-and-confirm flow.
	await page.getByLabel('Caption for item 1').fill('Strand');
	await page.getByRole('button', { name: 'Send it' }).first().click();
	await expect(page).toHaveURL(/\/history\/.+/);
	const send = st.calls.find((c) => c.path === '/v1/sends' && c.method === 'POST');
	const body = send!.body as { frame_ids: string[]; items: { caption: string | null }[] };
	expect(body.frame_ids).toEqual([dad.id]);
	expect(body.items).toEqual([expect.objectContaining({ caption: 'Strand' })]);
});

test('frames: dropping beside the cards or on a frame you can’t send to does nothing', async ({ page }) => {
	const { st } = dropState();
	await mockApi(page, st);
	await signIn(page);
	const files = await beach();
	const tom = page.getByRole('article').filter({ hasText: 'Tom’s frame' });
	await expect(tom).toBeVisible();

	// The page swallows the drop, so the browser doesn't navigate away to the file.
	await drag(page.locator('main'), 'dragenter', files);
	expect(await drag(page.getByRole('heading', { name: 'Your frames' }), 'dragover', files)).toBe(true);
	expect(await drag(page.getByRole('heading', { name: 'Your frames' }), 'drop', files)).toBe(true);
	await drag(page.locator('main'), 'dragenter', files);
	expect(await drag(tom.getByRole('heading'), 'dragover', files)).toBe(true);
	await expect(tom.getByText('Drop to send to Tom’s frame')).toHaveCount(0);
	expect(await drag(tom.getByRole('heading'), 'drop', files)).toBe(true);

	await expect(page).toHaveURL(/\/frames$/);
	await expect(page.getByRole('article').first()).not.toHaveClass(/drop-ready/);
	// A plain Send link stays for everyone who doesn't drag.
	await page.getByRole('article').filter({ hasText: 'Oma’s frame' }).getByRole('link', { name: 'Send' }).click();
	await expect(page).toHaveURL(/\/send\?frame=/);
	await expect(page.getByLabel('Caption for item 1')).toHaveCount(0);
});

test('frame page: drop anywhere to send to this frame', async ({ page }) => {
	const { st, oma } = dropState();
	await mockApi(page, st);
	await signIn(page);
	const files = await beach();

	await page.goto(`/frames/${oma.id}`);
	await expect(page.getByRole('heading', { name: 'Oma’s frame', level: 1 })).toBeVisible();
	await drag(page.locator('main'), 'dragenter', files);
	expect(await drag(page.getByRole('heading', { level: 1 }), 'dragover', files)).toBe(true);
	await expect(page.locator('.drop-veil')).toContainText('Drop to send to Oma’s frame');
	expect(await drag(page.getByRole('heading', { level: 1 }), 'drop', files)).toBe(true);

	await expect(page).toHaveURL(/\/send$/);
	await expect(page.getByRole('checkbox', { name: /Oma’s frame/ })).toBeChecked();
	await expect(page.getByRole('checkbox', { name: /Dad’s frame/ })).not.toBeChecked();
	await expect(page.getByLabel('Caption for item 1')).toBeVisible();
});

test('frame page: no drop target on a frame you can’t send to', async ({ page }) => {
	const { st, tom } = dropState();
	await mockApi(page, st);
	await signIn(page);
	const files = await beach();

	await page.goto(`/frames/${tom.id}`);
	await expect(page.getByRole('heading', { name: 'Tom’s frame', level: 1 })).toBeVisible();
	await drag(page.locator('main'), 'dragenter', files);
	expect(await drag(page.getByRole('heading', { level: 1 }), 'dragover', files)).toBe(true);
	await expect(page.locator('.drop-veil')).toHaveCount(0);
	expect(await drag(page.getByRole('heading', { level: 1 }), 'drop', files)).toBe(true);
	await expect(page).toHaveURL(new RegExp(`/frames/${tom.id}$`));
});
