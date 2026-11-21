// Chats against a real server: the family chat, direct and group chats, a circle's chat, live messages, typing,
// "Seen", badges, photos and voice in chats, Put on a frame and Share in chat.
import { expect, test } from '@playwright/test';
import { gradientPng } from '../mock-api';
import { claimFrame, clean, createFamily, join, newCtx, postOf, send, signedIn, user, type Json } from './real';

test('chats end to end, two people live', async ({ browser }) => {
	const api = await newCtx();
	const anna = await user(api, 'Anna');
	const ben = await user(api, 'Ben');
	const carla = await user(api, 'Carla');
	const feed = await createFamily(anna, 'Peeters');
	await join(anna, ben, feed);
	await join(anna, carla, feed);
	const { dev, frameId } = await claimFrame(api, anna, 'Oma’s frame');

	const A = await signedIn(browser, anna);
	const B = await signedIn(browser, ben);

	// The family chat is there for everyone without anybody creating it.
	await B.page.goto('/chats');
	const famLink = B.page.getByRole('link', { name: /Peeters/ });
	await expect(famLink).toBeVisible();
	await famLink.click();
	await expect(B.page).toHaveURL(/\/chats\/.+/);
	const famId = B.page.url().split('/').pop()!;
	await A.page.goto(`/chats/${famId}`);
	await expect(A.page.getByRole('heading', { name: 'Peeters' })).toBeVisible();

	// Typing and a live message.
	const bBox = B.page.getByRole('combobox', { name: 'Message' });
	await bBox.pressSequentially('Hello fam', { delay: 30 });
	await expect(A.page.getByText('Ben is typing…')).toBeVisible();
	await B.page.getByRole('button', { name: 'Send', exact: true }).click();
	await expect(A.page.getByText('Hello fam')).toBeVisible();
	await expect(A.page.getByText('Ben is typing…')).toHaveCount(0);
	// Family chats never show "Seen" (README §5.19).
	await expect(B.page.getByText(/^Seen/)).toHaveCount(0);

	// Badge: Anna away from the chat gets a count in the title for a new message.
	await A.page.goto('/home');
	await bBox.fill('Are you there?');
	await B.page.getByRole('button', { name: 'Send', exact: true }).click();
	await expect.poll(() => A.page.title()).toMatch(/^\(1\) /);
	expect((await anna.get('/v1/me/badge')).conversations).toBe(1);
	await A.page.goto('/chats');
	await expect(A.page.getByRole('link', { name: /Peeters/ }).getByLabel(/1 unread message/)).toBeVisible();
	await A.page.getByRole('link', { name: /Peeters/ }).click();
	await expect(A.page.getByText('Are you there?')).toBeVisible();
	await expect.poll(() => A.page.title()).not.toMatch(/^\(/);

	// Photos in a chat, then Anna puts Ben's photo on her frame.
	await B.page.getByTestId('chat-file-input').setInputFiles([{ name: 'kids.png', mimeType: 'image/png', buffer: await gradientPng(640, 480, 90) }]);
	await bBox.fill('The kids');
	await B.page.getByRole('button', { name: 'Send', exact: true }).click();
	await expect(A.page.getByText('The kids')).toBeVisible();
	const photoMsg = A.page.locator('[id^="m-"]').filter({ hasText: 'The kids' });
	await expect(photoMsg.getByRole('button', { name: 'Put on a frame' })).toBeVisible({ timeout: 30_000 });
	await photoMsg.getByRole('button', { name: 'Put on a frame' }).click();
	const dlg = A.page.getByRole('dialog', { name: 'Put on a frame' });
	await dlg.locator('label', { hasText: 'Oma’s frame' }).click();
	await dlg.getByRole('button', { name: 'Send' }).click();
	await expect(A.page.getByText(/put a photo on Oma’s frame/)).toBeVisible();
	await expect(B.page.getByText(/put a photo on Oma’s frame/)).toBeVisible();
	await expect
		.poll(async () => ((await dev.get('/v1/device/changes?since=0')).changes as Json[]).filter((c) => c.type === 'placement' || c.kind === 'placement' || c.placement).length)
		.toBeGreaterThan(0);

	// A direct chat from a profile, and a voice message in it.
	await A.page.goto(`/people/${ben.id}`);
	await A.page.getByRole('button', { name: 'Message' }).click();
	await expect(A.page).toHaveURL(/\/chats\/.+/);
	await A.page.getByRole('button', { name: 'Record a voice message' }).click();
	await expect(A.page.getByRole('timer')).toBeVisible();
	await A.page.waitForTimeout(1600);
	await A.page.getByRole('button', { name: 'Stop recording' }).click();
	await A.page.getByRole('button', { name: 'Send voice message' }).click();
	await expect(A.page.getByRole('button', { name: 'Play voice message' })).toBeVisible({ timeout: 20_000 });
	const direct = A.page.url().split('/').pop()!;
	await B.page.goto(`/chats/${direct}`);
	await expect(B.page.getByRole('button', { name: 'Play voice message' })).toBeVisible();
	// Ben has read it: Anna sees "Seen" in the direct chat.
	await expect(A.page.getByText(/^Seen/).first()).toBeVisible();
	const msgs = (await ben.get(`/v1/conversations/${direct}/messages`)).messages as Json[];
	await expect.poll(async () => (((await ben.get(`/v1/conversations/${direct}/messages`)).messages as Json[]).at(-1)!.voice as Json).status, { timeout: 30_000 }).toBe('ready');
	expect(msgs.at(-1)!.voice).toBeTruthy();

	// A group chat with Ben and Carla.
	await A.page.goto('/chats');
	await A.page.getByRole('button', { name: 'New chat' }).first().click();
	const nc = A.page.getByRole('dialog', { name: 'New chat' });
	await nc.locator('label', { hasText: 'Ben' }).click();
	await nc.locator('label', { hasText: 'Carla' }).click();
	await nc.getByLabel('Group name').fill('Cousins weekend');
	await nc.getByRole('button', { name: 'Create group' }).click();
	await expect(A.page.getByRole('heading', { name: 'Cousins weekend' })).toBeVisible();
	await B.page.goto('/chats');
	await expect(B.page.getByRole('link', { name: /Cousins weekend/ })).toBeVisible();

	// Share a post in the direct chat.
	const s = await send(anna, { feeds: [feed], caption: 'Sunday lunch' });
	const post = await postOf(anna, s.id, feed);
	await A.page.goto(`/posts/${post}`);
	await A.page.getByRole('button', { name: 'Post options' }).click();
	await A.page.getByRole('menuitem', { name: 'Share in chat' }).click();
	const share = A.page.getByRole('dialog', { name: 'Share in chat' });
	await share.locator('label', { hasText: 'Ben' }).click();
	await share.getByLabel(/^Message/).fill('Look!');
	await share.getByRole('button', { name: 'Share' }).click();
	await B.page.goto(`/chats/${direct}`);
	await expect(B.page.getByText('Look!')).toBeVisible();
	await expect(B.page.getByText('Peeters').first()).toBeVisible();

	for (const w of [A, B]) clean(w.watch);
	void frameId;
});

test('a circle: its chat, and posts only its members see', async ({ browser }) => {
	const api = await newCtx();
	const anna = await user(api, 'Anna');
	const ben = await user(api, 'Ben');
	const carla = await user(api, 'Carla');
	const feed = await createFamily(anna, 'Peeters');
	await join(anna, ben, feed);
	await join(anna, carla, feed);

	const A = await signedIn(browser, anna);
	await A.page.goto(`/feeds/${feed}?tab=circles`);
	await A.page.getByRole('button', { name: 'New circle' }).click();
	const dlg = A.page.getByRole('dialog', { name: 'New circle' });
	await dlg.getByLabel('Name').fill('The cousins');
	await dlg.locator('label', { hasText: 'Ben' }).click();
	await dlg.getByRole('button', { name: 'Create circle' }).click();
	await expect(A.page.getByRole('heading', { name: 'The cousins' })).toBeVisible();
	const [circle] = (await anna.get(`/v1/feeds/${feed}/circles`)) as unknown as Json[];

	// The circle's chat shows up for Ben, not for Carla.
	const B = await signedIn(browser, ben);
	await B.page.goto('/chats');
	await expect(B.page.getByRole('link', { name: /The cousins/ })).toBeVisible();
	const C = await signedIn(browser, carla);
	await C.page.goto('/chats');
	await expect(C.page.getByRole('link', { name: /Peeters/ })).toBeVisible();
	await expect(C.page.getByRole('link', { name: /The cousins/ })).toHaveCount(0);

	// Ben posts to the circle: Change on the composer's destination row, then the circle as the audience.
	await B.page.goto(`/send?feed=${feed}`);
	await B.page.getByRole('button', { name: 'Change' }).click();
	const sheet = B.page.getByRole('dialog', { name: 'Share with' });
	await sheet.getByTestId('audience').selectOption({ label: 'The cousins' });
	await sheet.getByRole('button', { name: 'Done' }).click();
	await expect(B.page.getByTestId('destination')).toHaveText('Peeters · only The cousins');
	await B.page.getByTestId('file-input').setInputFiles([{ name: 'c.png', mimeType: 'image/png', buffer: await gradientPng(320, 240, 10) }]);
	await B.page.getByLabel('Caption for item 1').fill('Only cousins');
	// A family send lands on Home: the send id comes from the API's answer.
	const sent = B.page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/v1/sends', { timeout: 20_000 });
	await B.page.getByRole('button', { name: 'Share', exact: true }).first().click();
	const sendId = ((await (await sent).json()) as Json).id as string;
	await expect(B.page).toHaveURL(/\/home$/, { timeout: 20_000 });
	const post = await postOf(ben, sendId, feed);
	expect(((await anna.get(`/v1/posts/${post}`)).circle as Json | undefined)?.id ?? (await anna.get(`/v1/posts/${post}`)).circle_id).toBe(circle!.id);

	await A.page.goto('/home');
	await expect(A.page.locator('article').filter({ hasText: 'Only cousins' })).toBeVisible();
	await expect(A.page.locator('article').filter({ hasText: 'Only cousins' }).getByText('The cousins')).toBeVisible();
	await C.page.goto('/home');
	await expect(C.page.getByRole('main')).toBeVisible();
	await C.page.waitForLoadState('networkidle');
	await expect(C.page.locator('article').filter({ hasText: 'Only cousins' })).toHaveCount(0);

	for (const w of [A, B, C]) clean(w.watch);
});
