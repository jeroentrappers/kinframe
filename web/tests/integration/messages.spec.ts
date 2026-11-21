// Chat messages and chat settings with two people live, and a direct send to a frame with its own thread,
// against a real server.
import { expect, test } from '@playwright/test';
import { claimFrame, clean, createFamily, join, newCtx, ready, send, signedIn, user, type Json } from './real';

test('reply, react, edit, delete; rename, mute, archive and leave a group', async ({ browser }) => {
	const api = await newCtx();
	const anna = await user(api, 'Anna');
	const ben = await user(api, 'Ben');
	const carla = await user(api, 'Carla');
	const feed = await createFamily(anna, 'Peeters');
	await join(anna, ben, feed);
	await join(anna, carla, feed);
	const group = await anna.post('/v1/conversations', { kind: 'group', member_ids: [ben.id, carla.id], name: 'Lunch' }, 201);
	const first = await ben.post(`/v1/conversations/${group.id}/messages`, { body: 'Pizza on Sunday?', client_id: 'b1' }, 201);

	const A = await signedIn(browser, anna);
	const B = await signedIn(browser, ben);
	await A.page.goto(`/chats/${group.id}`);
	await B.page.goto(`/chats/${group.id}`);

	// Anna replies to Ben's message and reacts to it; Ben sees both live.
	const msg = A.page.locator(`#m-${first.id}`);
	await msg.hover();
	await msg.getByRole('button', { name: 'Message options' }).click();
	await A.page.getByRole('menuitem', { name: 'Reply' }).click();
	await A.page.getByRole('combobox', { name: 'Message' }).fill('Yes please');
	await A.page.getByRole('button', { name: 'Send', exact: true }).click();
	const reply = B.page.locator('[id^="m-"]').filter({ hasText: 'Yes please' });
	await expect(reply).toBeVisible();
	await expect(reply.getByText('Pizza on Sunday?')).toBeVisible();
	await msg.hover();
	await msg.getByRole('button', { name: 'React' }).click();
	await A.page.getByRole('toolbar', { name: 'React' }).getByRole('button', { name: 'Thumbs up' }).click();
	await expect.poll(async () => ((await ben.get(`/v1/conversations/${group.id}/messages/${first.id}`)).reactions as Json).total).toBe(1);
	await expect(B.page.locator(`#m-${first.id}`).getByText('👍')).toBeVisible();

	// Anna edits and then deletes her reply; Ben sees "edited" and the tombstone live.
	const mine = A.page.locator('[id^="m-"]').filter({ hasText: 'Yes please' });
	await mine.hover();
	await mine.getByRole('button', { name: 'Message options' }).click();
	await A.page.getByRole('menuitem', { name: 'Edit' }).click();
	await A.page.getByRole('combobox', { name: 'Message' }).fill('Yes please, with olives');
	await A.page.getByRole('button', { name: 'Save' }).click();
	await expect(B.page.getByText('Yes please, with olives')).toBeVisible();
	await expect(B.page.locator('[id^="m-"]').filter({ hasText: 'with olives' }).getByText('edited')).toBeVisible();
	await mine.hover();
	await mine.getByRole('button', { name: 'Message options' }).click();
	await A.page.getByRole('menuitem', { name: 'Delete' }).click();
	await expect(B.page.getByText('This message was deleted')).toBeVisible();

	// Rename; Ben sees the new name live.
	await A.page.getByRole('button', { name: 'Chat options' }).click();
	await A.page.getByRole('menuitem', { name: 'Chat details' }).click();
	const details = A.page.getByRole('dialog', { name: 'Chat details' });
	await details.getByLabel('Group name').fill('Sunday lunch');
	await details.getByRole('button', { name: 'Save' }).click();
	await expect(B.page.getByRole('heading', { name: 'Sunday lunch' })).toBeVisible();

	// Ben mutes and archives it, then leaves.
	await B.page.getByRole('button', { name: 'Chat options' }).click();
	await B.page.getByRole('menuitem', { name: 'Chat details' }).click();
	const bd = B.page.getByRole('dialog', { name: 'Chat details' });
	await bd.getByLabel('Mute').selectOption({ label: 'For 8 hours' });
	await expect.poll(async () => (await ben.get(`/v1/conversations/${group.id}`)).muted).toBe(true);
	await bd.getByRole('button', { name: 'Archive chat' }).click();
	await expect.poll(async () => (await ben.get(`/v1/conversations/${group.id}`)).archived).toBe(true);
	await bd.getByRole('button', { name: 'Leave group' }).click();
	const confirm = B.page.getByRole('alertdialog').or(B.page.getByRole('dialog', { name: /Leave/ }));
	if (await confirm.count()) await confirm.getByRole('button', { name: /Leave/ }).last().click();
	await expect(B.page).toHaveURL(/\/chats$/);
	await expect(A.page.getByText(/Ben left/)).toBeVisible();

	for (const w of [A, B]) clean(w.watch);
});

test('a direct send to a frame has a thread for the frame’s people', async ({ browser }) => {
	const api = await newCtx();
	const anna = await user(api, 'Anna');
	const ben = await user(api, 'Ben');
	const { dev, frameId } = await claimFrame(api, anna, 'Oma’s frame');
	// Ben joins at the frame (its code) and may see its library.
	const { code } = await dev.post('/v1/device/code');
	expect((await ben.post('/v1/frames/redeem', { code })).status).toBe('joined');
	await anna.patch(`/v1/frames/${frameId}/members/${ben.id}`, { permissions: { see_library: true, send: true } });
	const s = await send(anna, { frames: [frameId], caption: 'Just for Oma' });
	await ready(anna, s.id);

	const B = await signedIn(browser, ben);
	await B.page.goto('/home');
	const card = B.page.locator('article').filter({ hasText: 'Just for Oma' });
	await expect(card).toBeVisible();
	await expect(card.getByText('To Oma’s frame')).toBeVisible();
	await card.getByRole('link', { name: /comment/i }).first().click();
	await expect(B.page).toHaveURL(/\/posts\/.+/);
	await B.page.getByRole('combobox', { name: 'Add a comment…' }).fill('Lovely');
	await B.page.getByRole('button', { name: 'Send', exact: true }).click();
	const post = new URL(B.page.url()).pathname.split('/').pop()!;
	await expect.poll(async () => ((await anna.get(`/v1/posts/${post}/comments`)).comments as Json[]).map((c) => c.body)).toEqual(['Lovely']);
	clean(B.watch);
});
