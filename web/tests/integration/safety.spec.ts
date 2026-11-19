// Blocks, reports and the review queues (family admins, the operator) against a real server.
import { expect, test } from '@playwright/test';
import { BASE, clean, Client, createFamily, join, newCtx, postOf, send, signedIn, user, type Json, type User } from './real';

test('block someone, report a comment and a message, and review them', async ({ browser }) => {
	const api = await newCtx();
	const anna = await user(api, 'Anna');
	const ben = await user(api, 'Ben');
	const carla = await user(api, 'Carla');
	const feed = await createFamily(anna, 'Peeters');
	await join(anna, ben, feed);
	await join(anna, carla, feed);
	const s = await send(anna, { feeds: [feed], caption: 'Garden' });
	const post = await postOf(anna, s.id, feed);
	const bad = await ben.post(`/v1/posts/${post}/comments`, { body: 'Something unkind' }, 201);

	// Carla reports Ben's comment from the thread.
	const C = await signedIn(browser, carla);
	await C.page.goto(`/posts/${post}`);
	await C.page.locator(`#c-${bad.id}`).getByRole('button', { name: 'Comment options' }).click();
	await C.page.getByRole('menuitem', { name: 'Report' }).click();
	const dlg = C.page.getByRole('dialog', { name: 'Report this comment' });
	await dlg.getByText('Unkind or hurtful').click();
	await dlg.getByRole('button', { name: 'Report' }).click();
	await expect(C.page.getByText('Thanks. Someone will have a look.')).toBeVisible();

	// Anna (owner) gets it live and removes the comment from the family's Reports tab.
	const A = await signedIn(browser, anna);
	await A.page.goto(`/feeds/${feed}?tab=reports`);
	await expect(A.page.getByText('Something unkind')).toBeVisible();
	await A.page.getByRole('button', { name: 'Remove it' }).click();
	await expect(A.page.getByText('Removed').first()).toBeVisible();
	await expect.poll(async () => ((await carla.get(`/v1/posts/${post}/comments`)).comments as Json[]).length).toBe(0);

	// Carla blocks Ben from his profile: his new comments are hidden for her, and he can't message her.
	await C.page.goto(`/people/${ben.id}`);
	await C.page.getByRole('button', { name: 'Block' }).click();
	await expect(C.page.getByRole('button', { name: 'Unblock' })).toBeVisible();
	await ben.post(`/v1/posts/${post}/comments`, { body: 'Hidden for Carla' }, 201);
	await C.page.goto(`/posts/${post}`);
	await C.page.waitForLoadState('networkidle');
	await expect(C.page.getByText('Hidden for Carla')).toHaveCount(0);
	const dm = await ben.raw('POST', '/v1/conversations', { kind: 'direct', member_ids: [carla.id] });
	expect(dm.status).toBe(403);
	expect((dm.body as Json).code).toBe('cannot_message');
	await C.page.goto('/settings');
	await expect(C.page.getByText('Ben').first()).toBeVisible();

	// A message in a group (no family) goes to the operator's queue.
	const r = await api.post(`${BASE}/dev/token`, { data: { email: `op-${Date.now()}@example.org`, name: 'Olga', operator: true } });
	const op = new Client(api, ((await r.json()) as Json).access_token as string) as User;
	const group = await anna.post('/v1/conversations', { kind: 'group', member_ids: [ben.id, carla.id], name: 'Lunch' }, 201);
	const pills = `Buy cheap pills ${Date.now()}`;
	const msg = await ben.post(`/v1/conversations/${group.id}/messages`, { body: pills, client_id: 'm1' }, 201);
	await anna.post('/v1/reports', { target_kind: 'message', target_id: msg.id, reason: 'spam' }, 201);
	const O = await signedIn(browser, Object.assign(op, { id: '', name: 'Olga', email: '' }));
	await O.page.goto('/settings/reports');
	// (The operator queue is server-wide: earlier runs leave their reports in it.)
	const item = O.page.getByRole('listitem').filter({ hasText: pills });
	await expect(item).toBeVisible();
	await item.getByRole('button', { name: 'Remove it' }).click();
	await expect.poll(async () => (((await anna.get(`/v1/conversations/${group.id}/messages`)).messages as Json[]).find((m) => m.id === msg.id))?.deleted).toBe(true);

	for (const w of [A, C, O]) clean(w.watch);
});
