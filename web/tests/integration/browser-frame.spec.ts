// The browser frame (/tv/frame) as a member of the family against a real server: claimed with its code, it shows
// a family post, asks for new photos, hears who answered, and shows the newest comment.
import { expect, test } from '@playwright/test';
import { clean, createFamily, join, newCtx, postOf, send, signedIn, user, type Json } from './real';

test('browser frame: Ask for new photos, New photos from Ben, comment card', async ({ browser }) => {
	const api = await newCtx();
	const anna = await user(api, 'Anna');
	const ben = await user(api, 'Ben');
	const feed = await createFamily(anna, 'Peeters');
	await join(anna, ben, feed);

	const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
	const tv = await ctx.newPage();
	const errors: string[] = [];
	tv.on('pageerror', (e) => errors.push(String(e.stack ?? e).slice(0, 1500)));
	await tv.goto('/tv/frame');
	const codeEl = tv.locator('p.code');
	await expect(codeEl).toBeVisible({ timeout: 20_000 });
	const code = (await codeEl.getAttribute('aria-label'))!;
	const res = await anna.post('/v1/frames/redeem', { code });
	expect(res.status).toBe('claimed');
	const frameId = (res.frame as Json).id as string;
	await anna.patch(`/v1/frames/${frameId}`, { name: 'Living room' });
	const settings = await anna.get(`/v1/frames/${frameId}/settings`);
	const patched = await anna.raw('PATCH', `/v1/frames/${frameId}/settings`, { social: { show_comments: true }, slideshow: { interval_s: 4 } }, { 'If-Match': `"${settings.version}"` });
	expect(patched.status, JSON.stringify(patched.body)).toBe(200);
	await anna.post(`/v1/feeds/${feed}/subscriptions`, { frame_id: frameId }, 201);

	const first = await send(ben, { feeds: [feed], caption: 'Beach day' });
	const post = await postOf(ben, first.id, feed);
	await expect(tv.locator('img.media').first()).toBeVisible({ timeout: 30_000 });

	// Ask for new photos from the menu.
	await tv.keyboard.press('Enter');
	const ask = tv.getByRole('button', { name: 'Ask for new photos' });
	await expect(ask).toBeVisible();
	await ask.click();
	await expect(tv.getByText('Your family has been asked for new photos 💛')).toBeVisible();
	await expect.poll(async () => ((await anna.get(`/v1/frames/${frameId}`)).photo_request as Json | null)?.state).toBe('open');

	// Ben answers through the family; the frame says so.
	const B = await signedIn(browser, ben);
	await send(ben, { feeds: [feed], caption: 'For you' });
	await expect(tv.getByText('New photos from Ben')).toBeVisible({ timeout: 30_000 });
	await tv.keyboard.press('Enter');
	await expect(tv.getByRole('button', { name: 'Ben sent new photos 💛' })).toBeVisible();
	await tv.keyboard.press('Escape');

	// A comment on the post the frame shows comes as a card.
	await ben.post(`/v1/posts/${post}/comments`, { body: 'Look at the waves' }, 201);
	await expect.poll(async () => tv.getByText('Look at the waves').count(), { timeout: 60_000 }).toBeGreaterThan(0);

	expect(errors).toEqual([]);
	clean(B.watch);
	await ctx.close();
});
