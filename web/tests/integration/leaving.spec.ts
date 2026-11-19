// Leaving a family against a real server (docs/spec/leaving.md, README §5.21): the leave sheet with its choices,
// photos leaving the frames and waiting in the frame trash ("Taken away by Tom"), Ask to keep and the answer from
// the history, account deletion, memories, read-only direct chats, operator ownership transfers and old clients.
import { expect, test } from '@playwright/test';
import { claimFrame, clean, createFamily, heartbeat, join, joinFrame, newCtx, onFrame, postOf, PSQL, ready, send, signedIn, sql, user, type Json } from './real';

test('Tom leaves and takes his photos; Lies asks to keep some, Tom allows one', async ({ browser }) => {
	const api = await newCtx();
	const lies = await user(api, 'Lies');
	const tom = await user(api, 'Tom');
	const feed = await createFamily(lies, 'Janssens');
	await join(lies, tom, feed);
	const oma = await claimFrame(api, lies, 'Oma’s frame');
	const kitchen = await claimFrame(api, lies, 'Kitchen frame');
	await lies.post(`/v1/feeds/${feed}/subscriptions`, { frame_id: oma.frameId }, 201);
	await lies.post(`/v1/feeds/${feed}/subscriptions`, { frame_id: kitchen.frameId }, 201);
	await joinFrame(lies, tom, kitchen.frameId);
	await heartbeat(oma.dev);
	await heartbeat(kitchen.dev);

	const liesSend = await send(lies, { feeds: [feed], caption: 'From Lies' });
	const liesPost = await postOf(lies, liesSend.id, feed);
	const tomSend = await send(tom, { feeds: [feed], n: 3, caption: 'Children' });
	await postOf(tom, tomSend.id, feed);
	const direct = await send(tom, { frames: [kitchen.frameId], caption: 'Straight to the kitchen' });
	await ready(tom, direct.id);
	await tom.post(`/v1/posts/${liesPost}/comments`, { body: 'Lovely' }, 201);
	await expect.poll(async () => (await onFrame(oma.dev)).size).toBe(4);
	await expect.poll(async () => (await onFrame(kitchen.dev)).size).toBe(5);

	// The leave sheet.
	const T = await signedIn(browser, tom);
	await T.page.goto(`/feeds/${feed}?tab=members`);
	await T.page.getByRole('button', { name: 'Leave family' }).click();
	const sheet = T.page.getByRole('dialog', { name: 'Leave Janssens?' });
	await expect(sheet.getByRole('group', { name: 'Your photos (3)' })).toBeVisible();
	await expect(sheet.getByRole('radio', { name: /Leave them with the family/ })).toBeChecked();
	await expect(sheet.getByRole('checkbox', { name: /Leave Kitchen frame too/ })).toBeChecked();
	await sheet.getByRole('radio', { name: /Take them with me/ }).check();
	await expect(sheet.getByText('Your photos sent straight to this frame: 1. The same choice applies to them.')).toBeVisible();
	await sheet.getByRole('radio', { name: /Delete them too/ }).check();
	await sheet.getByRole('button', { name: 'Leave', exact: true }).click();
	await expect(T.page).toHaveURL(/\/feeds$/);
	await expect(T.page.getByText(/You left Janssens\. 4 photos taken away\. Frames keep them in their trash until/)).toBeVisible();
	await expect(T.page.getByText(/You also left Kitchen frame\./)).toBeVisible();

	// Off the frames at once; the comment is a tombstone; Tom is no longer on the kitchen frame.
	await expect.poll(async () => (await onFrame(oma.dev)).size).toBe(1);
	await expect.poll(async () => (await onFrame(kitchen.dev)).size).toBe(1);
	const comments = (await lies.get(`/v1/posts/${liesPost}/comments`)) as Json;
	// Deleted as if one by one: gone, or a tombstone when it had replies.
	expect(((comments.comments ?? comments.items ?? comments) as Json[]).filter((c) => c.body === 'Lovely')).toEqual([]);
	expect(((await lies.get(`/v1/frames/${kitchen.frameId}/members`)) as Json[]).some((m) => m.user_id === tom.id)).toBe(false);

	// Lies sees them in Oma's frame trash and asks to keep two.
	const L = await signedIn(browser, lies);
	await L.page.goto(`/frames/${oma.frameId}?tab=library&view=trashed`);
	await expect(L.page.getByText('Taken away by Tom')).toBeVisible();
	await L.page.getByRole('button', { name: 'Ask Tom to keep them' }).click();
	const ask = L.page.getByRole('dialog', { name: 'Ask Tom to keep photos' });
	await ask.getByRole('tab', { name: 'Pick photos' }).click();
	await expect(ask.getByText('0 of 3 chosen')).toBeVisible();
	await ask.getByRole('checkbox').first().dispatchEvent('click');
	await ask.getByRole('checkbox').nth(1).dispatchEvent('click');
	await ask.getByLabel(/A few words/).fill('The ones with the children');
	await ask.getByRole('button', { name: 'Ask', exact: true }).click();
	await expect(L.page.getByText('Asked Tom. You’ll hear when they answer.')).toBeVisible();
	await expect(L.page.getByText('Your request: Waiting for an answer')).toBeVisible();

	// Tom answers from his history: allow one of the two.
	await T.page.goto('/history');
	await expect(T.page.getByText('Lies asks to keep 2 photos on Oma’s frame.')).toBeVisible();
	await T.page.getByRole('button', { name: 'Answer' }).click();
	const dlg = T.page.getByRole('dialog', { name: 'Keep photos on Oma’s frame?' });
	await dlg.getByRole('radio', { name: 'Allow some' }).check();
	await dlg.getByRole('checkbox').first().dispatchEvent('click');
	await expect(dlg.getByText('1 of 2 chosen')).toBeVisible();
	await dlg.getByRole('button', { name: 'Send answer' }).click();
	await expect(T.page.getByText('Your answer was sent to Lies.')).toBeVisible();
	await expect(T.page.getByText(/Partly allowed · 1 of 2 allowed/)).toBeVisible();

	// The allowed photo is back on Oma's frame (and only there); the trash still holds the other two.
	await expect.poll(async () => (await onFrame(oma.dev)).size).toBe(2);
	expect((await onFrame(kitchen.dev)).size).toBe(1);
	await L.page.reload();
	await expect(L.page.getByText('Taken away by Tom')).toBeVisible();
	await expect(L.page.getByText(/Partly allowed/)).toBeVisible();

	clean(T.watch);
	clean(L.watch);
});

test('leaving a frame with my photos: the browser frame empties, Lies asks for all, Tom declines', async ({ browser }) => {
	const api = await newCtx();
	const lies = await user(api, 'Lies');
	const tom = await user(api, 'Tom');

	// The kitchen frame is a browser frame (/tv/frame) claimed by Lies; Tom is a member and sends two photos.
	const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
	const tv = await ctx.newPage();
	const tvErrors: string[] = [];
	tv.on('pageerror', (e) => tvErrors.push(String(e)));
	await tv.goto('/tv/frame');
	const codeEl = tv.locator('p.code');
	await expect(codeEl).toBeVisible({ timeout: 20_000 });
	const res = await lies.post('/v1/frames/redeem', { code: (await codeEl.getAttribute('aria-label'))! });
	const frameId = (res.frame as Json).id as string;
	await lies.patch(`/v1/frames/${frameId}`, { name: 'Kitchen frame' });
	await joinFrame(lies, tom, frameId);
	const s = await send(tom, { frames: [frameId], n: 2 });
	await ready(tom, s.id);
	await expect(tv.locator('img.media').first()).toBeVisible({ timeout: 30_000 });

	const T = await signedIn(browser, tom);
	await T.page.goto(`/frames/${frameId}`);
	await T.page.getByRole('button', { name: 'Leave frame' }).click();
	const sheet = T.page.getByRole('dialog', { name: 'Leave Kitchen frame?' });
	await expect(sheet.getByRole('radio', { name: /Leave them on the frame/ })).toBeChecked();
	await sheet.getByRole('radio', { name: /Take them with me/ }).check();
	await sheet.getByRole('button', { name: 'Leave', exact: true }).click();
	await expect(T.page).toHaveURL(/\/frames$/);

	// The browser frame drops them at once and waits for photos again.
	await expect(tv.getByText('Waiting for the first photo')).toBeVisible({ timeout: 30_000 });

	// Lies: the trash lists them; she asks for all of them.
	const L = await signedIn(browser, lies);
	await L.page.goto(`/frames/${frameId}?tab=library&view=trashed`);
	await expect(L.page.getByText('Taken away by Tom')).toBeVisible();
	await L.page.getByRole('button', { name: 'Ask Tom to keep them' }).click();
	const ask = L.page.getByRole('dialog', { name: 'Ask Tom to keep photos' });
	await ask.getByRole('button', { name: 'Ask', exact: true }).click();
	await expect(L.page.getByText('Asked Tom. You’ll hear when they answer.')).toBeVisible();

	// Tom declines: nothing comes back; the request is final.
	await T.page.goto('/history');
	await expect(T.page.getByText('Lies asks to keep 2 photos on Kitchen frame.')).toBeVisible();
	await T.page.getByRole('button', { name: 'Answer' }).click();
	const dlg = T.page.getByRole('dialog', { name: 'Keep photos on Kitchen frame?' });
	await dlg.getByRole('radio', { name: 'Decline' }).check();
	await dlg.getByRole('button', { name: 'Send answer' }).click();
	await expect(T.page.getByText('Your answer was sent to Lies.')).toBeVisible();
	await L.page.reload();
	await expect(L.page.getByText(/Your request: Declined/)).toBeVisible();
	await expect(L.page.getByRole('button', { name: 'Ask Tom to keep them' })).toHaveCount(0);
	await expect(tv.getByText('Waiting for the first photo')).toBeVisible();

	expect(tvErrors).toEqual([]);
	clean(T.watch);
	clean(L.watch);
	await ctx.close();
});

test('removed by an admin: the photos stay; Tom takes them away later from his history; the family asks, Tom allows all', async ({ browser }) => {
	const api = await newCtx();
	const lies = await user(api, 'Lies');
	const tom = await user(api, 'Tom');
	const feed = await createFamily(lies, 'Janssens');
	await join(lies, tom, feed);
	const oma = await claimFrame(api, lies, 'Oma’s frame');
	await heartbeat(oma.dev);
	await lies.post(`/v1/feeds/${feed}/subscriptions`, { frame_id: oma.frameId }, 201);
	const s = await send(tom, { feeds: [feed], n: 2, caption: 'Tom’s pair' });
	await postOf(tom, s.id, feed);
	await expect.poll(async () => (await onFrame(oma.dev)).size).toBe(2);

	await lies.del(`/v1/feeds/${feed}/members/${tom.id}`);
	expect((await onFrame(oma.dev)).size).toBe(2);

	const T = await signedIn(browser, tom);
	await T.page.goto('/history');
	await expect(T.page.getByText('2 photos still there')).toBeVisible();
	await T.page.getByRole('button', { name: 'Take my photos away from Janssens' }).click();
	const sheet = T.page.getByRole('dialog', { name: 'Take your photos away from Janssens?' });
	await sheet.getByRole('button', { name: 'Take them away' }).click();
	await expect(T.page.getByText(/2 photos taken away\./)).toBeVisible();
	await expect(T.page.getByRole('button', { name: 'Take my photos away from Janssens' })).toHaveCount(0);
	await expect.poll(async () => (await onFrame(oma.dev)).size).toBe(0);

	// The family's admins see the taking too and ask to keep them all; Tom allows all.
	const L = await signedIn(browser, lies);
	await L.page.goto(`/feeds/${feed}`);
	await expect(L.page.getByText('Taken away by Tom')).toBeVisible();
	await L.page.getByRole('button', { name: 'Ask Tom to keep them' }).click();
	await L.page.getByRole('dialog', { name: 'Ask Tom to keep photos' }).getByRole('button', { name: 'Ask', exact: true }).click();
	await expect(L.page.getByText('Asked Tom. You’ll hear when they answer.')).toBeVisible();

	await T.page.reload();
	await expect(T.page.getByText('Lies asks to keep 2 photos on Janssens.')).toBeVisible();
	await T.page.getByRole('button', { name: 'Answer' }).click();
	const dlg = T.page.getByRole('dialog', { name: 'Keep photos on Janssens?' });
	await dlg.getByRole('radio', { name: 'Allow all' }).check();
	await dlg.getByRole('button', { name: 'Send answer' }).click();
	await expect(T.page.getByText('Your answer was sent to Lies.')).toBeVisible();

	// Back in the family and on the frame.
	await expect.poll(async () => (await onFrame(oma.dev)).size).toBe(2);
	await L.page.goto('/home');
	await expect(L.page.getByText('Tom’s pair')).toBeVisible();

	clean(T.watch);
	clean(L.watch);
});

test('old clients: leaving without a body keeps the photos', async () => {
	const api = await newCtx();
	const lies = await user(api, 'Lies');
	const tom = await user(api, 'Tom');
	const feed = await createFamily(lies, 'Janssens');
	await join(lies, tom, feed);
	const oma = await claimFrame(api, lies, 'Oma’s frame');
	await joinFrame(lies, tom, oma.frameId);
	await lies.post(`/v1/feeds/${feed}/subscriptions`, { frame_id: oma.frameId }, 201);
	const s1 = await send(tom, { feeds: [feed] });
	await postOf(tom, s1.id, feed);
	const s2 = await send(tom, { frames: [oma.frameId] });
	await ready(tom, s2.id);
	await expect.poll(async () => (await onFrame(oma.dev)).size).toBe(2);

	const leftFeed = await tom.raw('POST', `/v1/feeds/${feed}/leave`);
	expect(leftFeed.status, JSON.stringify(leftFeed.body)).toBeLessThan(300);
	const leftFrame = await tom.raw('POST', `/v1/frames/${oma.frameId}/leave`);
	expect(leftFrame.status, JSON.stringify(leftFrame.body)).toBeLessThan(300);
	expect((await onFrame(oma.dev)).size).toBe(2);
	expect(((await lies.get(`/v1/frames/${oma.frameId}/taken-away`)).groups as Json[]).length).toBe(0);
});

test('account deletion: leave my photos to the families (former member credit), or take them', async ({ browser }) => {
	const api = await newCtx();
	const lies = await user(api, 'Lies');
	const tom = await user(api, 'Tom');
	const ben = await user(api, 'Ben');
	const feed = await createFamily(lies, 'Janssens');
	await join(lies, tom, feed);
	await join(lies, ben, feed);
	const oma = await claimFrame(api, lies, 'Oma’s frame');
	await lies.post(`/v1/feeds/${feed}/subscriptions`, { frame_id: oma.frameId }, 201);
	const st = await send(tom, { feeds: [feed], caption: 'From Tom' });
	const tomPost = await postOf(tom, st.id, feed);
	const sb = await send(ben, { feeds: [feed], caption: 'From Ben' });
	await postOf(ben, sb.id, feed);
	await expect.poll(async () => (await onFrame(oma.dev)).size).toBe(2);

	// Tom leaves his photos to the families.
	const T = await signedIn(browser, tom);
	await T.page.goto('/settings');
	await T.page.getByRole('button', { name: 'Delete account' }).click();
	const dlg = T.page.getByRole('dialog', { name: 'Delete your account?' });
	await expect(dlg.getByRole('radio', { name: /Take my photos with me/ })).toBeChecked();
	await dlg.getByRole('radio', { name: /Leave my photos to the families/ }).check();
	await dlg.getByRole('button', { name: 'Delete account' }).click();
	await expect.poll(async () => (await tom.raw('GET', '/v1/me')).status).toBe(401);
	expect((await onFrame(oma.dev)).size).toBe(2);
	const L = await signedIn(browser, lies);
	await L.page.goto(`/posts/${tomPost}`);
	await expect(L.page.getByText('From Tom')).toBeVisible();
	await expect(L.page.getByText('Tom (former member)').first()).toBeVisible();

	// Ben takes his (the default).
	const del = await ben.raw('DELETE', '/v1/me', { photos: 'take' });
	expect(del.status, JSON.stringify(del.body)).toBeLessThan(300);
	await expect.poll(async () => (await onFrame(oma.dev)).size).toBe(1);
	await L.page.goto(`/frames/${oma.frameId}?tab=library&view=trashed`);
	await expect(L.page.getByText(/Taken away by/)).toBeVisible();

	clean(L.watch);
});

test('memories: leave Tom out, and hide a single memory on On this day', async ({ browser }) => {
	test.skip(!PSQL, 'needs REAL_PSQL to move posts a year back');
	const api = await newCtx();
	const lies = await user(api, 'Lies');
	const tom = await user(api, 'Tom');
	const ben = await user(api, 'Ben');
	const feed = await createFamily(lies, 'Janssens');
	await join(lies, tom, feed);
	await join(lies, ben, feed);
	const s1 = await send(tom, { feeds: [feed], caption: 'Tom a year ago' });
	const p1 = await postOf(tom, s1.id, feed);
	const s2 = await send(ben, { feeds: [feed], caption: 'Ben a year ago' });
	const p2 = await postOf(ben, s2.id, feed);
	sql(`UPDATE feed_posts SET created_at = now() - interval '1 year' WHERE id IN ('${p1}', '${p2}');`);
	const memoryPosts = async (u: typeof lies) => ((await u.get('/v1/me/on-this-day')).groups as Json[]).flatMap((g) => g.post_ids as string[]);
	await expect.poll(async () => (await memoryPosts(lies)).length, { timeout: 30_000 }).toBe(2);

	const L = await signedIn(browser, lies);
	await L.page.goto('/settings');
	await L.page.getByRole('link', { name: /Memories/ }).click();
	await expect(L.page.getByText('Nobody is left out.')).toBeVisible();
	await L.page.getByLabel('Add someone').selectOption({ label: 'Tom' });
	await L.page.getByRole('button', { name: 'Add someone' }).click();
	await expect(L.page.getByText('Tom is left out of your memories.')).toBeVisible();
	expect(await memoryPosts(lies)).toEqual([p2]);
	// Tom's own memories are untouched.
	expect((await memoryPosts(tom)).length).toBe(2);

	await L.page.goto('/home');
	await expect(L.page.getByRole('heading', { name: 'On this day' })).toBeVisible();
	await L.page.getByRole('button', { name: 'Don’t show this memory again' }).click();
	await expect(L.page.getByText('You won’t see this memory again.')).toBeVisible();
	await expect(L.page.getByRole('heading', { name: 'On this day' })).toHaveCount(0);
	await expect.poll(async () => (await memoryPosts(lies)).length).toBe(0);
	expect((await lies.get('/v1/me/memories')).hidden_memories).toEqual([p2]);
	clean(L.watch);
});

test('a direct chat becomes read-only when Tom and Lies no longer share a family', async ({ browser }) => {
	const api = await newCtx();
	const lies = await user(api, 'Lies');
	const tom = await user(api, 'Tom');
	const feed = await createFamily(lies, 'Janssens');
	await join(lies, tom, feed);
	const conv = await lies.post('/v1/conversations', { kind: 'direct', member_ids: [tom.id] }, [200, 201]);
	await lies.post(`/v1/conversations/${conv.id}/messages`, { body: 'Hi Tom' }, 201);

	await tom.post(`/v1/feeds/${feed}/leave`, { photos: 'keep' });
	const r = await lies.raw('POST', `/v1/conversations/${conv.id}/messages`, { body: 'Still there?' });
	expect(r.status).toBe(403);
	expect((r.body as Json).code).toBe('cannot_message');

	const L = await signedIn(browser, lies);
	await L.page.goto(`/chats/${conv.id}`);
	await expect(L.page.getByText('Hi Tom')).toBeVisible();
	await expect(L.page.getByText('You can no longer message Tom.')).toBeVisible();
	await expect(L.page.getByRole('textbox', { name: /Message/ })).toHaveCount(0);
	clean(L.watch);
});

test('operator: transfer a frame to Lies, removing Tom; the log', async ({ browser }) => {
	const api = await newCtx();
	const op = await user(api, 'Operator', { operator: true });
	const tom = await user(api, 'Tom');
	const lies = await user(api, 'Lies');
	const oma = await claimFrame(api, tom, 'Oma Janssens’s frame');
	await joinFrame(tom, lies, oma.frameId);

	const O = await signedIn(browser, op);
	await O.page.goto('/settings');
	await O.page.getByRole('link', { name: /Ownership transfers/ }).click();
	await O.page.getByPlaceholder('Frame, family, email or name').fill(tom.email);
	await O.page.getByRole('button', { name: 'Search' }).click();
	await expect(O.page.getByText('Oma Janssens’s frame')).toBeVisible();
	await O.page.getByRole('button', { name: 'Transfer ownership' }).first().click();
	const dlg = O.page.getByRole('dialog', { name: 'Transfer Oma Janssens’s frame' });
	await dlg.getByLabel('New owner').selectOption(lies.id);
	await dlg.getByLabel(/Reason/).fill('Divorce; Lies keeps the frame');
	await dlg.getByRole('checkbox', { name: 'Remove Tom instead of making them admin' }).check();
	await dlg.getByRole('button', { name: 'Transfer ownership' }).click();
	await expect(O.page.getByText('Lies now owns Oma Janssens’s frame.')).toBeVisible();
	await expect(O.page.getByText('Oma Janssens’s frame: Tom → Lies').first()).toBeVisible();

	expect((await lies.get(`/v1/frames/${oma.frameId}`)).role).toBe('owner');
	expect((await tom.raw('GET', `/v1/frames/${oma.frameId}`)).status).toBe(404);
	clean(O.watch);
});

test('the owner hands the frame over and leaves through the leave sheet, taking the photos', async ({ browser }) => {
	const api = await newCtx();
	const tom = await user(api, 'Tom');
	const lies = await user(api, 'Lies');
	const oma = await claimFrame(api, tom, 'Oma’s frame');
	await joinFrame(tom, lies, oma.frameId);
	const s = await send(tom, { frames: [oma.frameId], n: 2 });
	await ready(tom, s.id);
	await expect.poll(async () => (await onFrame(oma.dev)).size).toBe(2);

	const T = await signedIn(browser, tom);
	await T.page.goto(`/frames/${oma.frameId}`);
	await T.page.getByRole('button', { name: 'Leave frame' }).click();
	const tr = T.page.getByRole('dialog', { name: 'Leave Oma’s frame' });
	await tr.getByRole('radio', { name: /Lies/ }).check();
	await tr.getByRole('button', { name: 'Continue' }).click();
	await T.page.getByRole('dialog', { name: 'Make Lies the owner?' }).getByRole('button', { name: 'Transfer and leave' }).click();
	const sheet = T.page.getByRole('dialog', { name: 'Leave Oma’s frame?' });
	await expect(sheet.getByRole('radio', { name: /Leave them on the frame/ })).toBeChecked();
	await sheet.getByRole('radio', { name: /Take them with me/ }).check();
	await sheet.getByRole('button', { name: 'Leave', exact: true }).click();
	await expect(T.page).toHaveURL(/\/frames$/);
	await expect(T.page.getByText(/You left Oma’s frame\. 2 photos taken away\./)).toBeVisible();

	expect((await lies.get(`/v1/frames/${oma.frameId}`)).role).toBe('owner');
	await expect.poll(async () => (await onFrame(oma.dev)).size).toBe(0);
	const L = await signedIn(browser, lies);
	await L.page.goto(`/frames/${oma.frameId}?tab=library&view=trashed`);
	await expect(L.page.getByRole('tab', { name: /Trash\s*2/ })).toBeVisible();
	await expect(L.page.getByText('Taken away by Tom')).toBeVisible();
	clean(T.watch);
	clean(L.watch);
});
