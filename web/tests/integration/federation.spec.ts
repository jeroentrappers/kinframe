// Families on different servers against two real servers (docs/spec/multi-server.md, README §5.22): REAL_SERVER is
// server A (the family "Janssens"), REAL_SERVER_B server B (Oma's frame). Both run with KINFRAME_DEV_AUTH=1 and
// KINFRAME_FEDERATION_DEV_LOOPBACK=1 so they reach each other on 127.0.0.1 (see README.md). Skipped without B.
import { expect, test, type Page } from '@playwright/test';
import { BASE, claimFrame, clean, Client, createFamily, join, newCtx, onFrame, postOf, send, signedIn, user, type Json } from './real';

const B = process.env.REAL_SERVER_B ?? '';
const hostA = new URL(BASE).host;
const hostB = B ? new URL(B).host : '';

test.skip(!B, 'needs REAL_SERVER_B (a second server)');
// Pings are debounced 10 s; copies need downloads and renditions on B.
test.setTimeout(240_000);

const remoteSubs = async (u: Client, frameId: string) => (await u.get(`/v1/frames/${frameId}/remote-subscriptions`)) as unknown as Json[];

/** A browser frame on server B claimed by `owner`, named `name`. */
async function browserFrameOnB(browser: import('@playwright/test').Browser, owner: Client, name: string) {
	const ctx = await browser.newContext({ baseURL: B, viewport: { width: 1280, height: 800 } });
	const tv = await ctx.newPage();
	const errors: string[] = [];
	tv.on('pageerror', (e) => errors.push(String(e)));
	await tv.goto('/tv/frame');
	const codeEl = tv.locator('p.code');
	await expect(codeEl).toBeVisible({ timeout: 20_000 });
	const res = await owner.post('/v1/frames/redeem', { code: (await codeEl.getAttribute('aria-label'))! });
	const frameId = (res.frame as Json).id as string;
	await owner.patch(`/v1/frames/${frameId}`, { name });
	return { ctx, tv, errors, frameId };
}

/** The browser frame's device token (it lives in the page's storage), for reading its change feed. */
async function deviceOf(api: Awaited<ReturnType<typeof newCtx>>, tv: Page): Promise<Client> {
	const dump = await tv.evaluate(() => JSON.stringify({ ...localStorage }));
	const store = JSON.parse(dump) as Record<string, string>;
	let id = '';
	let secret = '';
	for (const v of Object.values(store)) {
		try {
			const j = JSON.parse(v) as Json;
			if (j && typeof j === 'object' && j.device_id && j.device_secret) ((id = j.device_id as string), (secret = j.device_secret as string));
		} catch {
			/* not JSON */
		}
	}
	expect(id, `device credentials in ${Object.keys(store).join(', ')}`).toBeTruthy();
	const t = (await (await api.post(`${B}/v1/devices/token`, { data: { device_id: id, device_secret: secret } })).json()) as Json;
	return new Client(api, t.access_token as string, B);
}

test('a frame on server B follows a family on server A', async ({ browser }) => {
	const api = await newCtx();

	// --- identity: discovery and the well-known document on both servers.
	const da = (await (await api.get(`${BASE}/.well-known/kinframe`)).json()) as Json;
	const db = (await (await api.get(`${B}/.well-known/kinframe`)).json()) as Json;
	expect(da.server_id).toBeTruthy();
	expect(db.server_id).toBeTruthy();
	expect(da.server_id).not.toBe(db.server_id);
	expect(da.base_url).toBe(BASE);
	expect((da.federation as Json).enabled).toBe(true);
	expect((da.federation as Json).public_key).toBeTruthy();
	expect(da.remote_families).toBe(true);
	expect(da.server_name).toBeTruthy();
	const nameB = db.server_name as string;

	// --- A: Anna's family allows frames on other servers (in the settings), Lies posts before the frame follows.
	const anna = await user(api, 'Anna');
	const lies = await user(api, 'Lies');
	const feed = await createFamily(anna, 'Janssens');
	await join(anna, lies, feed);
	const before = await send(lies, { feeds: [feed], caption: 'Before' });
	await postOf(lies, before.id, feed);
	const circle = (await anna.post(`/v1/feeds/${feed}/circles`, { name: 'Grown-ups', member_ids: [lies.id] }, 201)).id as string;

	const A = await signedIn(browser, anna);
	await A.page.goto(`/feeds/${feed}?tab=settings`);
	await A.page.getByRole('checkbox', { name: /Allow frames on other servers/ }).check();
	await A.page.getByRole('button', { name: 'Save', exact: true }).click();
	await expect.poll(async () => (await anna.get(`/v1/feeds/${feed}`)).allow_remote_frames).toBe(true);

	// A subscribe invite that other servers may use.
	await A.page.goto(`/feeds/${feed}?tab=invites`);
	await A.page.getByRole('radio', { name: /Add a frame/ }).check();
	await A.page.getByRole('checkbox', { name: /Frames on other servers may use this link/ }).check();
	await A.page.getByRole('button', { name: 'Create invite link' }).click();
	let inviteUrl = '';
	await expect
		.poll(async () => {
			const list = (await anna.get(`/v1/feeds/${feed}/invites`)) as Json;
			const inv = ((Array.isArray(list) ? list : (list.invites ?? list.items)) as Json[]).find((i) => i.allow_remote);
			inviteUrl = (inv?.url as string) ?? '';
			return inviteUrl;
		})
		.toBeTruthy();
	expect(inviteUrl.startsWith(BASE)).toBe(true);

	// --- B: Tom's browser frame "Oma's frame"; he adds the family from server A in the web app.
	const tom = await user(api, 'Tom', { base: B });
	const oma = await browserFrameOnB(browser, tom, 'Oma’s frame');
	const T = await signedIn(browser, tom);
	await T.page.goto(`/frames/${oma.frameId}?tab=settings`);
	await T.page.getByRole('button', { name: 'Add a family from another server' }).click();
	const dlg = T.page.getByRole('dialog', { name: 'Add a family from another server' });
	await dlg.getByLabel('Invite link').fill(inviteUrl);
	await dlg.getByRole('button', { name: 'Continue' }).click();
	await expect(dlg.getByText(`Janssens (on ${hostA})`)).toBeVisible();
	await expect(dlg.getByText(`Photos from this family will be copied to this server (${hostB}) and shown on Oma’s frame.`)).toBeVisible();
	await expect(dlg.getByText(new RegExp(`Whoever runs ${nameB} can see the copies`))).toBeVisible();
	await expect(dlg.getByText('The family’s admins approve the request first.')).toBeVisible();
	await dlg.getByRole('radio', { name: /Everything/ }).check();
	await dlg.getByRole('button', { name: 'Show on Oma’s frame' }).click();
	await expect(T.page.getByText('Asked Janssens to show on Oma’s frame. You’ll hear when they decide.')).toBeVisible();
	const list = T.page.getByRole('region', { name: 'Families on other servers' });
	await expect(list.getByText('Waiting for approval')).toBeVisible();

	// --- A: the request in the subscriptions list, with B's name; Anna lets it in.
	await A.page.goto(`/feeds/${feed}?tab=subscriptions`);
	const waiting = A.page.getByRole('region', { name: 'Frames waiting for approval' });
	await expect(waiting.getByText(`Tom wants to show Janssens on Oma’s frame (${hostB})`)).toBeVisible();
	await expect(waiting.getByText(nameB)).toBeVisible();
	await waiting.getByRole('button', { name: 'Let in' }).first().click();
	const showing = A.page.getByRole('region', { name: 'Frames that show this family' });
	await expect(showing.getByText(/Oma’s frame/).first()).toBeVisible();

	// --- B: active; the history post arrives on the browser frame.
	await expect(list.getByText('Active')).toBeVisible({ timeout: 60_000 });
	await expect(oma.tv.locator('img.media').first()).toBeVisible({ timeout: 90_000 });
	const dev = await deviceOf(api, oma.tv);
	await expect.poll(async () => (await onFrame(dev)).size, { timeout: 90_000 }).toBe(1);

	// A new post on A arrives; a circle post never does.
	const fresh = await send(lies, { feeds: [feed], caption: 'Fresh' });
	const freshPost = await postOf(lies, fresh.id, feed);
	const secret = await send(lies, { feeds: [feed], circles: [circle], caption: 'Grown-ups only' });
	await postOf(lies, secret.id, feed);
	await expect.poll(async () => (await onFrame(dev)).size, { timeout: 90_000 }).toBe(2);

	// --- A frame reaction on B shows in A's thread with B's name.
	const changes = ((await dev.get('/v1/device/changes?since=0')).changes as Json[]).filter((c) => c.type === 'placement.upsert');
	const freshPlacement = changes.find((c) => (c.placement as Json).item && ((c.placement as Json).item as Json).caption === 'Fresh') ?? changes.at(-1)!;
	await dev.put(`/v1/device/placements/${(freshPlacement.placement as Json).id}/reaction`, { reaction: 'love' });
	const L = await signedIn(browser, lies);
	await expect
		.poll(async () => JSON.stringify(((await lies.get(`/v1/posts/${freshPost}`)).thread as Json).reactions), { timeout: 90_000 })
		.toContain(nameB);
	// The reactions list (the dialog under the post) names the server too.
	const rl = await lies.get(`/v1/reactions/post/${freshPost}`);
	expect((rl.frames as Json[]).map((f) => f.server_name)).toEqual([nameB]);
	await L.page.goto(`/posts/${freshPost}`);
	await expect(L.page.getByText(`Oma’s frame (${nameB}) loved this`)).toBeVisible();
	await L.page.goto('/home');
	await expect(L.page.getByText(`Oma’s frame (${nameB})`).first()).toBeVisible();

	// --- A removal on A disappears on B.
	await lies.del(`/v1/feeds/${feed}/posts/${freshPost}`);
	await expect.poll(async () => (await onFrame(dev)).size, { timeout: 90_000 }).toBe(1);
	// The circle post never reached B, after all these syncs.
	const captions = ((await dev.get('/v1/device/changes?since=0')).changes as Json[])
		.filter((c) => c.type === 'placement.upsert')
		.map((c) => ((c.placement as Json).item as Json | undefined)?.caption);
	expect(captions).toContain('Before');
	expect(captions).not.toContain('Grown-ups only');

	// --- Lies leaves the family and takes her photos: the frame on B is told too.
	clean(L.watch);
	const left = await lies.post(`/v1/feeds/${feed}/leave`, { photos: 'take' });
	expect(left.remote_frames).toBe(1);
	await expect.poll(async () => (await onFrame(dev)).size, { timeout: 90_000 }).toBe(0);
	await expect(oma.tv.getByText('Waiting for the first photo')).toBeVisible({ timeout: 30_000 });

	// --- Unsubscribe from A's side: B shows it ended (removed by the family).
	await A.page.reload();
	await A.page.getByRole('button', { name: `Remove Oma’s frame (${nameB}) from the family` }).or(A.page.getByRole('button', { name: /Remove Oma’s frame/ })).first().click();
	await A.page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click();
	await expect.poll(async () => (await remoteSubs(tom, oma.frameId))[0]?.status, { timeout: 60_000 }).toBe('removed');
	await T.page.reload();
	await expect(list.getByText('Active')).toHaveCount(0);

	clean(A.watch);
	clean(T.watch);
	expect(oma.errors).toEqual([]);
	await oma.ctx.close();
});

test('B unfollows; A blocks B for the family; the operator sees B among the known servers', async ({ browser }) => {
	const api = await newCtx();
	const anna = await user(api, 'Anna');
	const op = await user(api, 'Operator', { operator: true });
	const feed = await createFamily(anna, 'Peeters', { allow_remote_frames: true });
	const inv = await anna.post(`/v1/feeds/${feed}/invites`, { purpose: 'subscribe', allow_remote: true }, 201);
	const tom = await user(api, 'Tom', { base: B });
	const { frameId } = await claimFrame(api, tom, 'Opa’s frame');

	// Follow (API), approve on A (API), then unfollow in B's web app.
	const rs = await tom.post(`/v1/frames/${frameId}/remote-subscriptions`, { invite_url: inv.url, history: 'none', on_end: 'remove' }, [200, 201]);
	expect(rs.status).toBe('pending');
	let subId = '';
	await expect
		.poll(async () => {
			const subs = (await anna.get(`/v1/feeds/${feed}/subscriptions`)) as unknown as Json[];
			subId = (subs.find((x) => x.remote && x.status === 'pending')?.id as string) ?? '';
			return subId;
		})
		.toBeTruthy();
	await anna.post(`/v1/subscriptions/${subId}/approve`);
	await expect.poll(async () => (await remoteSubs(tom, frameId))[0]?.status, { timeout: 60_000 }).toBe('active');

	const T = await signedIn(browser, tom);
	await T.page.goto(`/frames/${frameId}?tab=settings`);
	const list = T.page.getByRole('region', { name: 'Families on other servers' });
	await expect(list.getByText('Active')).toBeVisible();
	await list.getByRole('button', { name: 'Stop showing' }).click();
	await T.page.getByRole('dialog', { name: 'Stop showing Peeters?' }).getByRole('button', { name: 'Stop showing' }).click();
	await expect(list.getByText('Active')).toHaveCount(0);
	// A hears it: the remote frame is no longer among the family's frames.
	await expect
		.poll(async () => ((await anna.get(`/v1/feeds/${feed}/subscriptions`)) as unknown as Json[]).filter((x) => x.remote && x.status === 'active').length, {
			timeout: 60_000
		})
		.toBe(0);

	// A's admin blocks server B for the family; B's next try is refused, and the web says so.
	const A = await signedIn(browser, anna);
	await A.page.goto(`/feeds/${feed}?tab=subscriptions`);
	const blocked = A.page.getByRole('region', { name: 'Blocked servers' });
	await blocked.getByRole('button', { name: 'Block' }).click();
	const dlg = A.page.getByRole('dialog', { name: 'Block' });
	await dlg.getByLabel('Server address').fill(hostB);
	await dlg.getByRole('button', { name: 'Block' }).click();
	await expect(A.page.getByText(`${hostB} is blocked.`)).toBeVisible();

	T.watch.allow(/^4\d\d POST \/v1\/remote-invites:preview /);
	await T.page.getByRole('button', { name: 'Add a family from another server' }).click();
	const add = T.page.getByRole('dialog', { name: 'Add a family from another server' });
	await add.getByLabel('Invite link').fill(inv.url as string);
	await add.getByRole('button', { name: 'Continue' }).click();
	await expect(add.getByText(/The other server refused/)).toBeVisible();

	// The operator of A sees B among the known servers.
	const O = await signedIn(browser, op);
	await O.page.goto('/settings/servers');
	await expect(O.page.getByText(hostB).first()).toBeVisible();

	clean(A.watch);
	clean(T.watch);
	clean(O.watch);
});
