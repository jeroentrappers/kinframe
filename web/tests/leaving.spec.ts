import { expect, test, type Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { makeFrame, push, uid } from './mock-api';
import { emptyLeaving } from './mock-leaving';
import { call, signIn, socialState } from './social-fixtures';

// Leaving a family and families on other servers (docs/spec/leaving.md, docs/spec/multi-server.md, README §5.21,
// §5.22): the web against the in-memory mock.

type S = components['schemas'];
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();
const inDays = (d: number) => new Date(Date.now() + d * 86_400_000).toISOString();
const TOKEN = '9cQ2xV0kFq7Lm3Zt8bW1rA';

function leavingState(opts: { leaving?: boolean; remote?: boolean } = {}) {
	const s = socialState();
	Object.assign(s.st.discovery, {
		leaving: opts.leaving !== false,
		remote_families: opts.remote !== false,
		server_id: 'srv-1',
		server_name: 'Peeters',
		base_url: 'http://localhost:4173'
	});
	const lv = emptyLeaving();
	s.st.leaving = lv;
	const kitchen = makeFrame({ name: 'Kitchen frame', role: 'member' });
	s.st.frames.push(kitchen);
	s.st.feedMembers[s.readOnly.id] = [
		{ user_id: s.me.user_id, display_name: 'Anna', role: 'member', joined_at: ago(900) },
		{ user_id: uid('u'), display_name: 'Lies', role: 'owner', joined_at: ago(9000) }
	];
	const tom: S['PersonRef'] = { user_id: uid('u'), display_name: 'Tom' };
	return { ...s, lv, kitchen, tom };
}

const item = (sender: string): S['Item'] => ({ id: uid('i'), kind: 'photo', status: 'ready', sender_id: uid('u'), sender_name: sender, caption: null, created_at: ago(60) });
const placement = (frameId: string, it: S['Item']): S['Placement'] => ({ id: uid('p'), frame_id: frameId, item: it, hidden: false, trashed: false, received_at: ago(60) }) as S['Placement'];

async function open(page: Page, path: string) {
	await page.goto(path);
}

test('everything stays hidden until the server has it', async ({ page }) => {
	const s = leavingState({ leaving: false, remote: false });
	delete (s.st.discovery as { server_id?: string }).server_id;
	await signIn(page, s.st);
	await open(page, `/feeds/${s.readOnly.id}?tab=members`);
	await page.getByRole('button', { name: 'Leave family' }).click();
	await expect(page.getByRole('dialog', { name: 'Leave Janssens?' }).getByText('Also remove my posts from every frame')).toBeVisible();
	await page.keyboard.press('Escape');
	await open(page, '/settings');
	await expect(page.getByRole('link', { name: /Memories/ })).toHaveCount(0);
	await expect(page.getByText('You also use kinframe on other servers?')).toHaveCount(0);
	await open(page, `/frames/${s.oma.id}?tab=settings`);
	await expect(page.getByRole('button', { name: 'Add a family from another server' })).toHaveCount(0);
	expect(call(s.st, 'GET', '/v1/me/former-places')).toBeUndefined();
	expect(call(s.st, 'GET', '/v1/me/leave-preview')).toBeUndefined();
});

test('leaving a family: take my photos, delete my comments, leave the kitchen frame too', async ({ page }) => {
	const s = leavingState();
	s.lv.previews[`feed:${s.readOnly.id}`] = {
		scope: 'feed',
		name: 'Janssens',
		member: true,
		owner: false,
		photos: 214,
		comments: 38,
		frames: [
			{ frame_id: s.kitchen.id, frame_name: 'Kitchen frame', role: 'member', suggested: true, can_leave: true, direct_photos: 3 },
			{ frame_id: s.oma.id, frame_name: 'Oma’s frame', role: 'owner', suggested: false, can_leave: false }
		]
	};
	s.lv.results[`feed:${s.readOnly.id}`] = { taking_id: uid('t'), photos_taken_away: 214, comments_deleted: 38, until: inDays(30), frames: [{ frame_id: s.kitchen.id, frame_name: 'Kitchen frame', photos: 214 }], remote_frames: 1 };
	await signIn(page, s.st);
	await open(page, `/feeds/${s.readOnly.id}?tab=members`);
	await page.getByRole('button', { name: 'Leave family' }).click();

	const sheet = page.getByRole('dialog', { name: 'Leave Janssens?' });
	await expect(sheet.getByRole('radio', { name: /Leave them with the family/ })).toBeChecked();
	await expect(sheet.getByRole('group', { name: 'Your photos (214)' })).toBeVisible();
	await expect(sheet.getByRole('checkbox', { name: /Leave Kitchen frame too/ })).toBeChecked();
	await expect(sheet.getByRole('checkbox', { name: /Leave Oma’s frame too/ })).toBeDisabled();
	await expect(sheet.getByText('You can still remove any of your photos later from your history.')).toBeVisible();
	await sheet.getByRole('radio', { name: /Take them with me/ }).check();
	await expect(sheet.getByText('Your photos sent straight to this frame: 3. The same choice applies to them.')).toBeVisible();
	await sheet.getByRole('radio', { name: /Delete them too/ }).check();
	await sheet.getByRole('button', { name: 'Leave', exact: true }).click();

	await expect(page).toHaveURL(/\/home$/);
	await expect(page.getByText(/You left Janssens\. 214 photos taken away\. Frames keep them in their trash until/)).toBeVisible();
	await expect(page.getByText(/You also left Kitchen frame\./)).toBeVisible();
	expect(call(s.st, 'POST', `/v1/feeds/${s.readOnly.id}/leave`)?.body).toEqual({ photos: 'take', comments: 'delete', leave_frame_ids: [s.kitchen.id] });
});

test('leaving a frame keeps the photos by default', async ({ page }) => {
	const s = leavingState();
	s.lv.previews[`frame:${s.kitchen.id}`] = { scope: 'frame', name: 'Kitchen frame', member: true, owner: false, photos: 5 };
	await signIn(page, s.st);
	await open(page, `/frames/${s.kitchen.id}`);
	await page.getByRole('link', { name: /Frame settings and help/ }).click();
	await page.getByRole('button', { name: 'Leave frame' }).click();
	const sheet = page.getByRole('dialog', { name: 'Leave Kitchen frame?' });
	await expect(sheet.getByRole('radio', { name: /Leave them on the frame/ })).toBeChecked();
	await sheet.getByRole('button', { name: 'Leave', exact: true }).click();
	await expect(page).toHaveURL(/\/frames$/);
	expect(call(s.st, 'POST', `/v1/frames/${s.kitchen.id}/leave`)?.body).toEqual({ photos: 'keep' });
});

test('the frame trash: taken away by Tom, ask to keep some, and Lies who doesn’t accept requests', async ({ page }) => {
	const s = leavingState();
	const t1 = uid('t');
	const t2 = uid('t');
	const items = [item('Tom'), item('Tom'), item('Tom')];
	s.lv.takenPlacements[t1] = items.map((i) => placement(s.oma.id, i));
	s.lv.takenAway[`frame:${s.oma.id}`] = [
		{ taking_id: t1, by: s.tom, by_name: 'Tom', taken_at: ago(60), until: inDays(30), count: 3, can_ask: true },
		{ taking_id: t2, by_name: 'Lies', taken_at: ago(600), until: inDays(25), count: 7, can_ask: false, cannot_ask_reason: 'blocked' }
	];
	await signIn(page, s.st);
	await open(page, `/frames/${s.oma.id}?tab=library&view=trashed`);

	await expect(page.getByText('Taken away by Tom')).toBeVisible();
	await expect(page.getByText(/In the trash until/).first()).toBeVisible();
	await expect(page.getByText('Lies doesn’t accept requests')).toBeVisible();
	await page.getByRole('button', { name: 'Ask Tom to keep them' }).click();
	const ask = page.getByRole('dialog', { name: 'Ask Tom to keep photos' });
	await ask.getByRole('tab', { name: 'Pick photos' }).click();
	await expect(ask.getByText('0 of 3 chosen')).toBeVisible();
	await ask.getByRole('checkbox').first().dispatchEvent('click');
	await ask.getByRole('checkbox').nth(1).dispatchEvent('click');
	await expect(ask.getByText('2 of 3 chosen')).toBeVisible();
	await ask.getByLabel(/A few words/).fill('The ones with the children');
	await ask.getByRole('button', { name: 'Ask', exact: true }).click();
	await expect(page.getByText('Asked Tom. You’ll hear when they answer.')).toBeVisible();
	await expect(page.getByText('Your request: Waiting for an answer')).toBeVisible();
	expect(call(s.st, 'POST', '/v1/keep-requests')?.body).toEqual({ taking_id: t1, frame_id: s.oma.id, item_ids: [items[0]!.id, items[1]!.id], note: 'The ones with the children' });
});

test('the history: answer a keep request with some photos, and take photos away from a family I left', async ({ page }) => {
	const s = leavingState();
	const items = [item('Anna'), item('Anna'), item('Anna')];
	const reqId = uid('r');
	s.lv.myRequests = [
		{
			id: reqId,
			status: 'open',
			scope: 'frame',
			frame_id: s.oma.id,
			frame_name: 'Oma Janssens’s frame',
			taking_id: uid('t'),
			asked_by_name: 'Lies',
			note: 'For Oma',
			asked_at: ago(60),
			expires_at: inDays(29),
			item_count: 3,
			items: items.map((i) => ({ item: i, allowed: null }))
		}
	];
	const oldFeed = uid('fd');
	s.lv.former = { feeds: [{ feed_id: oldFeed, name: 'Vermeulen', photos: 12, left_at: ago(60 * 24 * 10) }], frames: [] };
	s.lv.previews[`feed:${oldFeed}`] = { scope: 'feed', name: 'Vermeulen', member: false, photos: 12, comments: 0 };
	s.lv.results[`feed:${oldFeed}`] = { taking_id: uid('t'), photos_taken_away: 12, comments_deleted: 0, until: inDays(30), frames: [] };
	await signIn(page, s.st);
	await open(page, '/history');

	await expect(page.getByText('Lies asks to keep 3 photos on Oma Janssens’s frame.')).toBeVisible();
	await page.getByRole('button', { name: 'Answer' }).click();
	const dlg = page.getByRole('dialog', { name: 'Keep photos on Oma Janssens’s frame?' });
	await dlg.getByRole('radio', { name: 'Allow some' }).check();
	await expect(dlg.getByRole('button', { name: 'Send answer' })).toBeDisabled();
	await dlg.getByRole('checkbox').first().dispatchEvent('click');
	await expect(dlg.getByText('1 of 3 chosen')).toBeVisible();
	await dlg.getByRole('button', { name: 'Send answer' }).click();
	await expect(page.getByText('Your answer was sent to Lies.')).toBeVisible();
	await expect(page.getByText(/Partly allowed · 1 of 3 allowed/)).toBeVisible();
	expect(call(s.st, 'POST', `/v1/keep-requests/${reqId}/answer`)?.body).toEqual({ allow: 'some', item_ids: [items[0]!.id] });

	await expect(page.getByText('12 photos still there')).toBeVisible();
	await page.getByRole('button', { name: 'Take my photos away from Vermeulen' }).click();
	const sheet = page.getByRole('dialog', { name: 'Take your photos away from Vermeulen?' });
	await sheet.getByRole('button', { name: 'Take them away' }).click();
	await expect(page.getByText(/12 photos taken away\./)).toBeVisible();
	await expect(page.getByRole('button', { name: 'Take my photos away from Vermeulen' })).toHaveCount(0);
	expect(call(s.st, 'POST', `/v1/feeds/${oldFeed}/take-away`)?.body).toEqual({ comments: 'keep' });

	// A new request arrives live.
	push(s.st, { type: 'keep_request.updated', request: { ...s.lv.myRequests[0]!, id: uid('r'), status: 'open', asked_by_name: 'Ben', frame_name: 'Kitchen frame', items: undefined } });
	await expect(page.getByText('Ben asks to keep 3 photos on Kitchen frame.')).toBeVisible();
});

test('family admins: taken-away posts in the family, allow frames on other servers, invites and remote frames', async ({ page }) => {
	const s = leavingState();
	s.lv.takenAway[`feed:${s.feed.id}`] = [{ taking_id: uid('t'), by_name: 'Tom', taken_at: ago(60), until: inDays(30), count: 214, can_ask: true }];
	s.st.subscriptions.push({
		id: uid('sb'),
		status: 'active',
		history: 'none',
		feed: { id: s.feed.id, name: s.feed.name },
		frame: { id: uid('f'), name: 'Oma’s frame' },
		created_at: ago(90),
		can_manage: false,
		can_decide: true,
		remote: { server_name: 'Appmire', server_url: 'https://kinframe.appmire.be', host: 'kinframe.appmire.be', requested_by_name: 'Tom' }
	} as S['Subscription']);
	await signIn(page, s.st);

	await open(page, `/feeds/${s.feed.id}`);
	await expect(page.getByText('Taken away by Tom')).toBeVisible();
	await expect(page.getByRole('button', { name: 'Ask Tom to keep them' })).toBeVisible();

	await open(page, `/feeds/${s.feed.id}?tab=settings`);
	await page.getByRole('checkbox', { name: /Allow frames on other servers/ }).check();
	await page.getByRole('button', { name: 'Save', exact: true }).click();
	await expect.poll(() => call(s.st, 'PATCH', `/v1/feeds/${s.feed.id}`)?.body).toEqual({ allow_remote_frames: true });
	await expect(page.getByRole('heading', { name: 'Blocked servers' })).toBeVisible();

	s.st.feeds[0]!.allow_remote_frames = true;
	await open(page, `/feeds/${s.feed.id}?tab=invites`);
	await page.getByRole('radio', { name: /Add a frame/ }).check();
	await page.getByRole('checkbox', { name: /Frames on other servers may use this link/ }).check();
	await page.getByRole('button', { name: 'Create invite link' }).click();
	await expect.poll(() => (call(s.st, 'POST', `/v1/feeds/${s.feed.id}/invites`)?.body as { allow_remote?: boolean })?.allow_remote).toBe(true);

	await open(page, `/feeds/${s.feed.id}?tab=subscriptions`);
	await expect(page.getByText('Appmire (kinframe.appmire.be)')).toBeVisible();
	await page.getByRole('button', { name: 'Block Appmire' }).click();
	const dlg = page.getByRole('dialog', { name: 'Block' });
	await expect(dlg.getByLabel('Server address')).toHaveValue('kinframe.appmire.be');
	await dlg.getByRole('button', { name: 'Block' }).click();
	await expect(page.getByText('kinframe.appmire.be is blocked.')).toBeVisible();
	expect(call(s.st, 'POST', `/v1/feeds/${s.feed.id}/blocked-servers`)?.body).toEqual({ host: 'kinframe.appmire.be' });
});

test('a frame follows a family on another server', async ({ page }) => {
	const s = leavingState();
	s.lv.invitePreview = {
		server: { server_name: 'Janssens', server_url: 'https://kinframe.janssens.be', host: 'kinframe.janssens.be' },
		feed_name: 'Janssens',
		frame_count: 3,
		requires_approval: false,
		this_server_name: 'Peeters',
		this_server_host: 'localhost:4173'
	};
	await signIn(page, s.st);
	await open(page, `/frames/${s.oma.id}?tab=settings`);

	// Pasted in "Use an invite", it opens the remote flow.
	await page.getByRole('button', { name: 'Use an invite' }).click();
	await page.getByRole('dialog', { name: 'Use an invite' }).getByRole('textbox').fill(`https://kinframe.janssens.be/fi/${TOKEN}`);
	await page.getByRole('dialog', { name: 'Use an invite' }).getByRole('button', { name: 'Continue' }).click();

	const dlg = page.getByRole('dialog', { name: 'Add a family from another server' });
	await expect(dlg.getByText('Janssens (on kinframe.janssens.be)')).toBeVisible();
	await expect(dlg.getByText('Photos from this family will be copied to this server (localhost:4173) and shown on Oma’s frame.')).toBeVisible();
	await expect(dlg.getByText(/Whoever runs Peeters can see the copies/)).toBeVisible();
	await dlg.getByRole('radio', { name: /Keep the photos already on the frame/ }).check();
	await dlg.getByRole('button', { name: 'Show on Oma’s frame' }).click();
	await expect(page.getByText('Janssens will show on Oma’s frame.')).toBeVisible();
	expect(call(s.st, 'POST', '/v1/remote-invites:preview')?.body).toEqual({ invite_url: `https://kinframe.janssens.be/fi/${TOKEN}` });
	expect(call(s.st, 'POST', `/v1/frames/${s.oma.id}/remote-subscriptions`)?.body).toMatchObject({ invite_url: `https://kinframe.janssens.be/fi/${TOKEN}`, history: 'last_n', history_count: 50, on_end: 'keep' });

	const list = page.getByRole('region', { name: 'Families on other servers' });
	await expect(list.getByText('Active')).toBeVisible();

	// The other server synced.
	const sub = s.lv.remoteSubs[s.oma.id]![0]!;
	push(s.st, { type: 'remote_subscription.updated', frame_id: s.oma.id, remote_subscription: { ...sub, status: 'error' } });
	await expect(list.getByText('Can’t reach the server')).toBeVisible();

	await list.getByRole('button', { name: 'Stop showing' }).click();
	await page.getByRole('dialog', { name: 'Stop showing Janssens?' }).getByRole('button', { name: 'Stop showing' }).click();
	await expect.poll(() => call(s.st, 'DELETE', `/v1/frames/${s.oma.id}/remote-subscriptions/${sub.id}`)).toBeTruthy();

	// A refused link says why.
	await page.getByRole('button', { name: 'Add a family from another server' }).click();
	await dlg.getByLabel('Invite link').fill(`https://refused.example.org/fi/${TOKEN}`);
	await dlg.getByRole('button', { name: 'Continue' }).click();
	await expect(dlg.getByText(/The other server refused/)).toBeVisible();
});

test('settings: memories, account deletion with leave, and other servers', async ({ page }) => {
	const s = leavingState();
	await signIn(page, s.st);
	await open(page, '/settings');
	await expect(page.getByText('You also use kinframe on other servers? The apps show them all together.')).toBeVisible();
	await page.getByRole('button', { name: 'Add a server' }).click();
	await page.getByLabel('Server address').fill('kinframe.janssens.be');
	await page.getByRole('button', { name: 'Add a server' }).click();
	await expect(page.getByRole('link', { name: 'kinframe.janssens.be' })).toHaveAttribute('href', 'https://kinframe.janssens.be/');

	await page.getByRole('link', { name: /Memories/ }).click();
	await expect(page).toHaveURL(/\/settings\/memories$/);
	await expect(page.getByText('Nobody is left out.')).toBeVisible();
	await page.getByLabel('Add someone').selectOption({ label: 'Ben' });
	await page.getByRole('button', { name: 'Add someone' }).click();
	await expect(page.getByText('Ben is left out of your memories.')).toBeVisible();
	expect(call(s.st, 'PUT', '/v1/me/memories')?.body).toEqual({ hidden_people: [s.ben.user_id] });
	await page.getByRole('button', { name: 'Show again' }).click();
	await expect(page.getByText('Ben is back in your memories.')).toBeVisible();
});

test('account deletion: take my photos (default) or leave them to the families', async ({ page }) => {
	const s = leavingState();
	// Owners hand their frames over first (the existing rule).
	s.st.frames = s.st.frames.filter((f) => f.role !== 'owner');
	await signIn(page, s.st);
	await open(page, '/settings');
	await page.getByRole('button', { name: 'Delete account' }).click();
	const dlg = page.getByRole('dialog', { name: 'Delete your account?' });
	await expect(dlg.getByRole('radio', { name: /Take my photos with me/ })).toBeChecked();
	await expect(dlg.getByText(/credited “Anna \(former member\)”/)).toBeVisible();
	await dlg.getByRole('radio', { name: /Leave my photos to the families/ }).check();
	await dlg.getByRole('button', { name: 'Delete account' }).click();
	await expect.poll(() => call(s.st, 'DELETE', '/v1/me')?.body).toEqual({ photos: 'leave' });
});

test('On this day: don’t show this memory again', async ({ page }) => {
	const s = leavingState();
	await signIn(page, s.st);
	await expect(page.getByRole('heading', { name: 'On this day' })).toBeVisible();
	await page.getByRole('button', { name: 'Don’t show this memory again' }).click();
	await expect(page.getByText('You won’t see this memory again.')).toBeVisible();
	await expect(page.getByRole('heading', { name: 'On this day' })).toHaveCount(0);
	await expect.poll(() => call(s.st, 'PUT', '/v1/me/memories')?.body, { timeout: 15_000 }).toEqual({ hidden_memories: [s.p3] });
});

test('a direct chat with someone you no longer share a family with is read-only', async ({ page }) => {
	const s = leavingState();
	const conv = s.soc.conversations.find((c) => c.id === s.direct)!;
	Object.assign(conv, { read_only: true, read_only_reason: 'no_longer_contacts' });
	await signIn(page, s.st);
	await open(page, `/chats/${s.direct}`);
	await expect(page.getByText('You can no longer message Ben.')).toBeVisible();
	await expect(page.getByRole('combobox', { name: /Message/ })).toHaveCount(0);
});

test('frame entries of frames on other servers show the server', async ({ page }) => {
	const s = leavingState();
	s.soc.comments[s.p1]!.push({ id: uid('c'), post_id: s.p1, kind: 'frame', author: null, frame: { frame_id: uid('rs'), frame_name: 'Opa’s frame', server_name: 'kinframe.appmire.be', reaction: 'love' }, body: null, created_at: ago(5) } as S['Comment']);
	await signIn(page, s.st);
	await open(page, `/posts/${s.p1}`);
	await expect(page.getByText('Opa’s frame (kinframe.appmire.be) loved this')).toBeVisible();
});

test('operator: transfer ownership, the log, and blocked servers', async ({ page }) => {
	const s = leavingState();
	s.st.me!.is_operator = true;
	const fid = uid('f');
	const lies = uid('u');
	s.lv.ownership.frames = [
		{
			kind: 'frame',
			id: fid,
			name: 'Oma Janssens’s frame',
			members: [
				{ user_id: uid('u'), display_name: 'Tom', email: 'tom@example.org', role: 'owner' },
				{ user_id: lies, display_name: 'Lies', email: 'lies@example.org', role: 'admin' }
			]
		}
	];
	s.lv.remoteServers = [{ server_name: 'Janssens', server_url: 'https://kinframe.janssens.be', host: 'kinframe.janssens.be', subscribers: 2, follows: 1, last_contact_at: ago(5), blocked: false }];
	await signIn(page, s.st);
	await open(page, '/settings');
	await page.getByRole('link', { name: /Ownership transfers/ }).click();
	await page.getByRole('searchbox').or(page.getByPlaceholder('Frame, family, email or name')).fill('tom@');
	await page.getByRole('button', { name: 'Search' }).click();
	await expect(page.getByText('Oma Janssens’s frame')).toBeVisible();
	await page.getByRole('button', { name: 'Transfer ownership' }).click();
	const dlg = page.getByRole('dialog', { name: 'Transfer Oma Janssens’s frame' });
	await dlg.getByLabel('New owner').selectOption(lies);
	await dlg.getByLabel(/Reason/).fill('Divorce; Lies keeps the frame');
	await dlg.getByRole('checkbox', { name: 'Remove Tom instead of making them admin' }).check();
	await dlg.getByRole('button', { name: 'Transfer ownership' }).click();
	await expect(page.getByText('Lies now owns Oma Janssens’s frame.')).toBeVisible();
	await expect(page.getByText('Oma Janssens’s frame: Tom → Lies')).toBeVisible();
	expect(call(s.st, 'POST', `/v1/operator/frames/${fid}/owner`)?.body).toEqual({ user_id: lies, reason: 'Divorce; Lies keeps the frame', remove_previous: true });

	await open(page, '/settings/servers');
	await expect(page.getByText('Frames there showing families here: 2')).toBeVisible();
	await page.getByRole('button', { name: 'Block' }).first().click();
	await page.getByRole('dialog', { name: 'Block' }).getByRole('button', { name: 'Block' }).click();
	await expect(page.getByText('kinframe.janssens.be is blocked.')).toBeVisible();
	expect(call(s.st, 'POST', '/v1/operator/blocked-servers')?.body).toEqual({ host: 'kinframe.janssens.be' });
});
