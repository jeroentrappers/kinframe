// Encouragement to share and the frame as a family member, against a real server: Ask for new photos from the
// device, the request answered by a send, freshness, frame reactions in threads, Show comments on the frame, the
// weekly prompt and streak, and the reminder settings.
import { expect, test } from '@playwright/test';
import { claimFrame, clean, createFamily, join, newCtx, postOf, send, signedIn, user, type Json } from './real';

const changesOf = async (dev: { get(p: string): Promise<Json> }) => ((await dev.get('/v1/device/changes?since=0')).changes as Json[]) ?? [];

test('the frame asks for photos, Ben answers; reactions, comments and freshness', async ({ browser }) => {
	const api = await newCtx();
	const anna = await user(api, 'Anna');
	const ben = await user(api, 'Ben');
	const feed = await createFamily(anna, 'Peeters');
	await join(anna, ben, feed);
	const { dev, frameId } = await claimFrame(api, anna, 'Oma’s frame');
	await anna.post(`/v1/feeds/${feed}/subscriptions`, { frame_id: frameId }, 201);
	const first = await send(ben, { feeds: [feed], caption: 'First' });
	const post = await postOf(ben, first.id, feed);
	// The post reaches the frame (fanout) before the frame asks, or the request would count it as the answer.
	await expect.poll(async () => (await changesOf(dev)).some((c) => c.type === 'placement.upsert')).toBe(true);

	const A = await signedIn(browser, anna);
	const B = await signedIn(browser, ben);
	await A.page.goto(`/frames/${frameId}`);
	await B.page.goto('/home');
	// Home is up (the family's people and frames), so the live events reach Ben.
	await expect(B.page.getByRole('navigation', { name: 'Peeters: people and frames' })).toBeVisible();
	await expect(A.page.getByRole('heading', { name: 'Oma’s frame', level: 1 })).toBeVisible();

	// Ask for new photos from the frame: 201, then 200 within the day.
	const r1 = await dev.raw('POST', '/v1/device/photo-requests');
	expect(r1.status).toBe(201);
	expect(r1.body).toMatchObject({ state: 'open' });
	expect((r1.body as Json).next_allowed_at).toBeTruthy();
	const r2 = await dev.raw('POST', '/v1/device/photo-requests');
	expect(r2.status).toBe(200);
	expect((r2.body as Json).id).toBe((r1.body as Json).id);
	await expect.poll(async () => (await changesOf(dev)).some((c) => c.type === 'photo_request')).toBe(true);

	// Anna's frame screen (her frame) shows it.
	await A.page.reload();
	await expect(A.page.getByText('Would like new photos')).toBeVisible();
	// Ben isn't on the frame: he gets a toast; Send photos preselects the family that reaches Oma's frame.
	await B.page.getByRole('button', { name: 'Send photos' }).click();
	await expect(B.page).toHaveURL(/\/send\?frame=.+&request=.+/);
	await expect(B.page.getByTestId('destination')).toHaveText(/^Peeters/);
	await B.page.goto('/home');
	const fr = await anna.get(`/v1/frames/${frameId}`);
	expect((fr.photo_request as Json).state).toBe('open');
	expect(fr.freshness).toBeTruthy();

	// Ben answers by sending; the frame learns who answered.
	await send(ben, { feeds: [feed], caption: 'For Oma' });
	await expect
		.poll(async () => {
			const c = (await changesOf(dev)).filter((x) => x.type === 'photo_request').at(-1);
			return (c?.photo_request as Json | undefined)?.answered_by_name;
		})
		.toBe('Ben');
	await A.page.reload();
	await expect(A.page.getByText('Ben already sent new photos.')).toBeVisible();
	await expect(A.page.getByText('Would like new photos')).toHaveCount(0);

	// The frame reacts: the thread shows the frame's entry and its reaction.
	const placement = (await changesOf(dev)).find((c) => c.type === 'placement.upsert' && (c.placement as Json)?.post_id === post) ?? (await changesOf(dev)).find((c) => c.type === 'placement.upsert');
	expect(placement, 'the frame got the post').toBeTruthy();
	const pid = (placement!.placement as Json).id ?? placement!.id;
	await dev.put(`/v1/device/placements/${pid}/reaction`, { reaction: 'love' });
	await B.page.goto(`/posts/${post}`);
	await expect(B.page.getByText(/Oma’s frame loved this/)).toBeVisible();

	// Show comments: Anna turns it on in the frame settings; the frame gets the comment.
	await A.page.goto(`/frames/${frameId}?tab=settings`);
	await A.page.locator('label.switch', { hasText: 'Show comments' }).click();
	await A.page.getByRole('button', { name: 'Save' }).first().click();
	await expect.poll(async () => ((await anna.get(`/v1/frames/${frameId}/settings`)).social as Json)?.show_comments).toBe(true);
	await B.page.getByRole('combobox', { name: 'Add a comment…' }).fill('Hi Oma!');
	await B.page.getByRole('button', { name: 'Send', exact: true }).click();
	// Comment cards are batched (every 15 s on the server).
	await expect
		.poll(async () => JSON.stringify((await changesOf(dev)).filter((c) => c.type === 'comments')), { timeout: 40_000 })
		.toContain('Hi Oma!');

	// Anna's frame page shows freshness.
	await A.page.goto(`/frames/${frameId}`);
	await expect(A.page.getByText(/Fresh/).first()).toBeVisible();

	for (const w of [A, B]) clean(w.watch);
});

test('weekly prompt, streak and reminder settings', async ({ browser }) => {
	const api = await newCtx();
	const anna = await user(api, 'Anna');
	const ben = await user(api, 'Ben');
	const feed = await createFamily(anna, 'Peeters');
	await join(anna, ben, feed);

	const A = await signedIn(browser, anna);
	await A.page.goto(`/feeds/${feed}?tab=settings`);
	await expect(A.page.getByRole('heading', { name: 'Encouragement' })).toBeVisible();
	await A.page.locator('label.switch', { hasText: 'Weekly theme' }).click();
	await expect.poll(async () => (await anna.get(`/v1/feeds/${feed}/engagement`)).prompts_enabled).toBe(true);

	const prompts = (await anna.get(`/v1/feeds/${feed}/prompts`)) as unknown as Json[];
	expect(prompts.length).toBeGreaterThan(0);
	const current = prompts.find((p) => p.status === 'current') ?? prompts[0]!;

	// Ben answers the prompt from Home.
	const B = await signedIn(browser, ben);
	await B.page.goto('/home');
	if (current.status === 'current') {
		await expect(B.page.getByRole('heading', { name: current.title })).toBeVisible();
		await expect(B.page.getByRole('link', { name: 'Answer with a photo' })).toBeVisible();
		const s = await send(ben, { feeds: [feed], prompt: current.id, n: 3 });
		const post = await postOf(ben, s.id, feed);
		expect(((await ben.get(`/v1/posts/${post}`)).prompt as Json)?.id).toBe(current.id);
	}

	// Streak on the family page.
	await expect.poll(async () => ((await anna.get(`/v1/feeds/${feed}/streak`)).week as Json).photos).toBeGreaterThanOrEqual(current.status === 'current' ? 3 : 0);
	await A.page.goto(`/feeds/${feed}`);
	await A.page.waitForLoadState('networkidle');

	// Reminder settings persist.
	await B.page.goto('/settings');
	await B.page.getByRole('radiogroup', { name: 'How often' }).getByText('Fewer').click();
	await expect.poll(async () => (await ben.get('/v1/me/reminders')).level).toBe('fewer');
	await B.page.locator('label.switch', { hasText: 'Read receipts' }).click();
	await expect.poll(async () => (await ben.get('/v1/me')).show_read_receipts).toBe(false);
	await B.page.reload();
	await expect(B.page.getByRole('radiogroup', { name: 'How often' }).getByRole('radio', { name: /Fewer/ })).toBeChecked();

	for (const w of [A, B]) clean(w.watch);
});
