// Every family-social page, opened by each role against a real server with real data: no failed API calls, no
// page errors. A cheap net under the flow specs.
import { expect, test } from '@playwright/test';
import { claimFrame, clean, createFamily, join, newCtx, postOf, send, signedIn, user } from './real';

test('every social page loads for owner, poster and reader', async ({ browser }) => {
	const api = await newCtx();
	const anna = await user(api, 'Anna');
	const ben = await user(api, 'Ben');
	const carla = await user(api, 'Carla');
	const feed = await createFamily(anna, 'Peeters');
	await join(anna, ben, feed);
	await join(anna, carla, feed);
	await anna.patch(`/v1/feeds/${feed}/members/${carla.id}`, { role: 'member' });
	const { frameId } = await claimFrame(api, anna, 'Oma’s frame');
	await anna.post(`/v1/feeds/${feed}/subscriptions`, { frame_id: frameId }, 201);
	const s = await send(ben, { feeds: [feed], n: 2, caption: 'Beach day' });
	const post = await postOf(ben, s.id, feed);
	await anna.post(`/v1/posts/${post}/comments`, { body: 'Lovely' }, 201);
	const convs = await anna.get('/v1/conversations');
	const famChat = (convs as unknown as { kind: string; id: string }[]).find((c) => c.kind === 'family');
	expect(famChat, 'the family chat appears by itself').toBeTruthy();

	for (const u of [anna, ben, carla]) {
		const { ctx, page, watch } = await signedIn(browser, u);
		for (const path of ['/home', '/chats', `/chats/${famChat!.id}`, `/posts/${post}`, `/feeds/${feed}`, '/feeds', '/frames', '/settings', `/people/${anna.id}`, '/send']) {
			await page.goto(path);
			await page.waitForLoadState('networkidle');
			await expect(page.locator('main')).toBeVisible();
		}
		if (u === anna) {
			await page.goto(`/frames/${frameId}`);
			await page.waitForLoadState('networkidle');
			await page.goto('/settings/reports');
			await page.waitForLoadState('networkidle');
		}
		await test.step(`clean for ${u.name}`, () => clean(watch));
		await ctx.close();
	}
});
