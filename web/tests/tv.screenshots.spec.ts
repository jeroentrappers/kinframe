// Visual review of "Show on TV": `npx playwright test --project=screenshots tv`.
// Writes PNGs to $SCREENSHOT_DIR (default ./screenshots).
import { expect, test, type Page } from '@playwright/test';
import { fakeCast } from './fake-cast';
import { defaultSettings, emptyState, makeFrame, mockApi, uid, type MockState } from './mock-api';

const DIR = process.env.SCREENSHOT_DIR ?? 'screenshots';
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();

function state(): MockState {
	const st = emptyState();
	st.me = { id: uid('u'), display_name: 'Jeroen Trappers', email: 'jeroen@example.org', avatar_url: null, locale: 'en', created_at: ago(99999) };
	const oma = makeFrame({ name: 'Oma’s frame', location: 'Living room', role: 'owner', member_count: 4, placement_count: 12 });
	st.frames = [oma];
	st.placements[oma.id] = Array.from({ length: 12 }, (_, i) => ({
		id: uid('p'),
		frame_id: oma.id,
		item: {
			id: uid('i'),
			kind: 'photo' as const,
			status: 'ready' as const,
			sender_id: st.me!.id,
			sender_name: ['Jeroen', 'Lisa', 'Tom'][i % 3],
			caption: i === 0 ? 'Weekend at the sea with the whole family' : i % 4 === 0 ? 'Grandpa’s birthday' : null,
			created_at: ago(i * 300),
			captured_at: ago(i * 60 * 24 * 21)
		},
		status: 'seen' as const,
		hidden: false,
		trashed: false,
		received_at: ago(i * 300)
	}));
	st.settings[oma.id] = defaultSettings(2);
	return st;
}

async function signIn(page: Page) {
	await page.goto('/');
	await page.getByLabel('Email').fill('jeroen@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

for (const [name, viewport] of [
	['desktop', { width: 1280, height: 860 }],
	['phone', { width: 412, height: 860 }]
] as const) {
	test(`show on TV (${name})`, async ({ page }) => {
		await page.setViewportSize(viewport);
		const st = state();
		const frame = st.frames[0]!;
		await fakeCast(page);
		await mockApi(page, st);
		await signIn(page);
		await page.goto(`/frames/${frame.id}?tab=library`);
		await expect(page.getByRole('button', { name: 'Show on TV' }).first()).toBeVisible();
		await page.waitForTimeout(400);
		await page.screenshot({ path: `${DIR}/tv-library-${name}.png` });

		await page.getByRole('button', { name: 'Show on TV' }).first().click();
		const dialog = page.getByRole('dialog', { name: 'Show on TV' });
		await expect(dialog).toBeVisible();
		await page.screenshot({ path: `${DIR}/tv-dialog-${name}.png` });

		await dialog.getByRole('button', { name: /Open on the TV’s browser/ }).click();
		await expect(dialog.getByRole('textbox')).toBeVisible();
		await page.screenshot({ path: `${DIR}/tv-link-${name}.png` });

		await dialog.getByRole('button', { name: /Cast to a TV/ }).click();
		await expect(dialog.getByText('Showing on Living room TV')).toBeVisible();
		await page.waitForTimeout(300);
		await page.screenshot({ path: `${DIR}/tv-casting-${name}.png` });
		await dialog.getByRole('button', { name: 'Close' }).click();
		await page.waitForTimeout(400);
		await page.screenshot({ path: `${DIR}/tv-minibar-${name}.png` });

		const link = st.tvLinks[frame.id]!.find((l) => l.purpose === 'tv')!;
		const tv = await page.context().browser()!.newPage({ viewport: name === 'desktop' ? { width: 1600, height: 900 } : { width: 412, height: 860 } });
		await mockApi(tv, st);
		await tv.goto(`/tv/${frame.id}?t=${link.token}`);
		await expect(tv.locator('.cap')).toBeVisible();
		await tv.waitForTimeout(1200);
		await tv.screenshot({ path: `${DIR}/tv-page-${name}.png` });
		await tv.mouse.move(400, 300);
		await tv.waitForTimeout(400);
		await tv.screenshot({ path: `${DIR}/tv-page-controls-${name}.png` });
		await tv.close();
	});
}
