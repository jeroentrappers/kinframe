// Home timeline and threads with two people in two browsers, live over the websocket, against a real server.
import { expect, test } from '@playwright/test';
import { gradientPng } from '../mock-api';
import { clean, createFamily, join, newCtx, postOf, send, signedIn, user, type Json } from './real';

test('post, comment with a mention, reply, react, live updates, read, mute and close', async ({ browser }) => {
	const api = await newCtx();
	const anna = await user(api, 'Anna');
	const ben = await user(api, 'Ben');
	const carla = await user(api, 'Carla');
	const feed = await createFamily(anna, 'Peeters');
	await join(anna, ben, feed);
	await join(anna, carla, feed);
	await anna.patch(`/v1/feeds/${feed}/members/${carla.id}`, { role: 'member' });

	const A = await signedIn(browser, anna);
	const B = await signedIn(browser, ben);

	// Ben posts through the composer, to the family on its destination row.
	await B.page.goto(`/send?feed=${feed}`);
	await expect(B.page.getByTestId('destination')).toHaveText(/^Peeters/);
	await B.page.getByTestId('file-input').setInputFiles([{ name: 'beach.png', mimeType: 'image/png', buffer: await gradientPng(640, 480, 40) }]);
	await B.page.getByLabel('Caption for item 1').fill('Beach day');
	// A family send lands on Home: the send id comes from the API's answer.
	const sent = B.page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/v1/sends', { timeout: 20_000 });
	await B.page.getByRole('button', { name: 'Share', exact: true }).first().click();
	const sendId = ((await (await sent).json()) as Json).id as string;
	await expect(B.page).toHaveURL(/\/home$/, { timeout: 20_000 });
	const post = await postOf(ben, sendId, feed);

	// Anna's Home shows it.
	await A.page.goto('/home');
	const card = A.page.locator('article').filter({ hasText: 'Beach day' });
	await expect(card).toBeVisible();
	await expect(card.getByText('Ben').first()).toBeVisible();

	// Both open the thread; Anna comments mentioning Ben, and Ben sees it live.
	await B.page.goto(`/posts/${post}`);
	await A.page.goto(`/posts/${post}`);
	const box = A.page.getByRole('combobox', { name: 'Add a comment…' });
	await box.click();
	await box.pressSequentially('Look @Be');
	await expect(A.page.getByRole('option', { name: 'Ben' })).toBeVisible();
	await box.press('Enter');
	await box.pressSequentially('!');
	await A.page.getByRole('button', { name: 'Send', exact: true }).click();
	await expect(A.page.getByRole('link', { name: '@Ben' }).first()).toBeVisible();
	await expect(B.page.getByText(/^Look/).first()).toBeVisible();
	await expect(B.page.getByRole('link', { name: '@Ben' }).first()).toBeVisible();

	const [c1] = (await ben.get(`/v1/posts/${post}/comments`)).comments as Json[];
	expect(c1!.mentions).toEqual([{ user_id: ben.id, start: 5, length: 4 }]);

	// Ben replies; Anna sees the reply live under her comment.
	await B.page.locator(`#c-${c1!.id}`).getByRole('button', { name: 'Reply' }).click();
	await B.page.getByRole('combobox', { name: 'Write a reply…' }).fill('Thanks!');
	await B.page.getByRole('button', { name: 'Send', exact: true }).click();
	await expect(A.page.getByText('Thanks!')).toBeVisible();

	// Ben reacts to the post; Anna's reaction row changes live.
	await B.page.getByRole('button', { name: 'React', exact: true }).first().click();
	await B.page.getByRole('toolbar', { name: 'React' }).getByRole('button', { name: 'Love' }).click();
	await expect(B.page.getByRole('button', { name: 'Your reaction: Love. Change it' }).first()).toBeVisible();
	await expect.poll(async () => ((await anna.get(`/v1/posts/${post}`)).thread as Json).reactions.total).toBe(1);
	await expect(A.page.getByText('Ben', { exact: true }).first()).toBeVisible();

	// Carla (Reader) can comment and react, not post.
	const C = await signedIn(browser, carla);
	await C.page.goto(`/posts/${post}`);
	await C.page.getByRole('combobox', { name: 'Add a comment…' }).fill('So nice');
	await C.page.getByRole('button', { name: 'Send', exact: true }).click();
	await expect(A.page.getByText('So nice')).toBeVisible();
	await C.page.goto('/send');
	await expect(C.page.getByRole('heading', { name: 'You can’t share in this family' })).toBeVisible();
	await expect(C.page.getByTestId('destination')).toHaveCount(0);

	// Anna closes the thread: Ben's composer goes away live.
	await A.page.getByRole('button', { name: 'Post options' }).click();
	await A.page.getByRole('menuitem', { name: 'Turn off comments' }).click();
	await expect(A.page.getByText('Comments are turned off').first()).toBeVisible();
	await expect.poll(async () => ((await ben.get(`/v1/posts/${post}`)).thread as Json).state).toBe('closed');
	await B.page.reload();
	await expect(B.page.getByRole('combobox', { name: 'Add a comment…' })).toHaveCount(0);
	await A.page.getByRole('button', { name: 'Post options' }).click();
	await A.page.getByRole('menuitem', { name: 'Turn on comments' }).click();
	await expect(A.page.getByRole('combobox', { name: 'Add a comment…' })).toBeVisible();

	// Ben mutes it.
	await B.page.reload();
	await B.page.getByRole('button', { name: 'Post options' }).click();
	await B.page.getByRole('menuitem', { name: /Mute/ }).click();
	await expect.poll(async () => ((await ben.get(`/v1/posts/${post}`)).thread as Json).muted).toBe(true);

	// Read state: having the thread open marks it read for Ben.
	await expect.poll(async () => ((await ben.get(`/v1/posts/${post}`)).thread as Json).unread_count).toBe(0);

	for (const w of [A, B, C]) clean(w.watch);
});

test('join from an invite link: a poster, and a Reader when new members don’t post', async ({ browser }) => {
	const api = await newCtx();
	const anna = await user(api, 'Anna');
	const ben = await user(api, 'Ben');
	const dave = await user(api, 'Dave');
	const feed = await createFamily(anna, 'Peeters');
	await anna.patch(`/v1/feeds/${feed}`, { invite_requires_approval: false });
	const inv = await anna.post(`/v1/feeds/${feed}/invites`, { purpose: 'join', max_uses: 5 }, 201);

	const B = await signedIn(browser, ben);
	await B.page.goto(`/fi/${inv.token}`);
	await B.page.getByRole('button', { name: 'Join family' }).click();
	await expect(B.page.getByRole('heading', { name: 'You’re in Peeters' })).toBeVisible();
	// A poster joining someone else's family is asked once how to see it (simple-app.md §6).
	await B.page.getByTestId('view-normal').click();
	await expect(B.page).toHaveURL(/\/home$/);

	await anna.patch(`/v1/feeds/${feed}`, { new_members_can_post: false });
	const D = await signedIn(browser, dave);
	await D.page.goto(`/fi/${inv.token}`);
	await D.page.getByRole('button', { name: 'Join family' }).click();
	await expect(D.page.getByRole('heading', { name: 'You’re in Peeters' })).toBeVisible();
	await D.page.getByTestId('view-normal').click();
	await expect(D.page).toHaveURL(/\/home$/);
	// A Reader: the composer says so instead of offering the family.
	await D.page.goto('/send');
	await expect(D.page.getByRole('heading', { name: 'You can’t share in this family' })).toBeVisible();
	expect((await dave.get(`/v1/feeds/${feed}`)).role).toBe('member');

	const A = await signedIn(browser, anna);
	await A.page.goto(`/feeds/${feed}?tab=members`);
	await expect(A.page.getByText('Reader').first()).toBeVisible();
	for (const w of [A, B, D]) clean(w.watch);
});

test('a mention counts on the badge until the thread is read', async ({ browser }) => {
	const api = await newCtx();
	const anna = await user(api, 'Anna');
	const ben = await user(api, 'Ben');
	const feed = await createFamily(anna, 'Peeters');
	await join(anna, ben, feed);
	const s = await send(anna, { feeds: [feed], caption: 'Garden' });
	const post = await postOf(anna, s.id, feed);
	const B = await signedIn(browser, ben);
	await B.page.goto('/home');
	await anna.post(`/v1/posts/${post}/comments`, { body: 'Hey @Ben', mentions: [{ user_id: ben.id, start: 4, length: 4 }] }, 201);
	await expect.poll(async () => (await ben.get('/v1/me/badge')).threads).toBe(1);
	await expect.poll(() => B.page.title()).toMatch(/^\(1\) /);
	await B.page.goto(`/posts/${post}`);
	await expect(B.page.getByText('Hey')).toBeVisible();
	await expect.poll(async () => (await ben.get('/v1/me/badge')).threads).toBe(0);
	await expect.poll(() => B.page.title()).not.toMatch(/^\(/);
	clean(B.watch);
});
