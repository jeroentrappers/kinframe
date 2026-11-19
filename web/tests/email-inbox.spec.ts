import { expect, test, type Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { defaultSettings, emptyState, makeFrame, mockApi, push, uid, type MockState } from './mock-api';

// Email to frame (docs/spec/email-inbox.md, README §5.16): addresses on frames and feeds, senders, the pending list
// with held mail, recent mail, and the "via email" credit, against the in-memory mock API.

type S = components['schemas'];
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();
const later = (days: number) => new Date(Date.now() + days * 86_400_000).toISOString();

function inboxState(on = true) {
	const st = emptyState();
	st.discovery.feeds = true;
	st.discovery.email_inbox = on;
	st.discovery.email_inbox_domain = on ? 'in.kinframe.appmire.be' : null;
	st.me = { id: uid('u'), display_name: 'Anna', email: 'anna@example.org', avatar_url: null, locale: 'en', created_at: ago(9999) };
	const oma = makeFrame({ name: 'Oma', role: 'owner' });
	st.frames = [oma];
	st.settings[oma.id] = defaultSettings(1);
	const feed: S['Feed'] = { id: uid('fd'), name: 'Peeters kids', cover_url: null, role: 'owner', member_count: 2, subscription_count: 3, created_at: ago(9000), owner_name: 'Anna' };
	st.feeds = [feed];
	st.feedPosts[feed.id] = [];
	return { st, oma, feed };
}

async function signIn(page: Page, st: MockState) {
	// navigator.share is captured so the ready-made message can be checked.
	await page.addInitScript(() => {
		const w = window as unknown as { __shared: unknown[] };
		w.__shared = [];
		Object.defineProperty(navigator, 'share', { configurable: true, value: async (d: unknown) => void w.__shared.push(d) });
	});
	await mockApi(page, st);
	await page.goto('/');
	await page.getByLabel('Email').fill('anna@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

function existingAddress(st: MockState, frame: S['Frame'], p: Partial<S['EmailAddress']> = {}): S['EmailAddress'] {
	const a: S['EmailAddress'] = {
		id: uid('ea'),
		address: 'oma-k7q2m9@in.kinframe.appmire.be',
		slug: 'oma',
		name: 'Oma’s frame',
		target: { kind: 'frame', frame_id: frame.id, frame_name: frame.name },
		owner: { user_id: st.me!.id, display_name: 'Anna' },
		status: 'active',
		anyone_may_send: false,
		send_confirmations: true,
		offer_app_invite: true,
		notify_reactions: true,
		replies_available: true,
		counts: { allowed_senders: 0, pending_senders: 0, held_messages: 0 },
		can_manage: true,
		is_owner: true,
		created_at: ago(900),
		...p
	};
	st.emailAddresses.push(a);
	return a;
}

function pendingSender(st: MockState, a: S['EmailAddress'], p: { name: string; email: string; subject: string; authenticated?: boolean; photos?: number }) {
	const authenticated = p.authenticated ?? true;
	const msgId = uid('hm');
	const thumbs = Array.from({ length: p.photos ?? 3 }, (_, i) => `/v1/media/signed/${msgId}-${i}`);
	const sender: S['EmailPendingSender'] = {
		id: uid('ps'),
		email: p.email,
		display_name: p.name,
		authenticated,
		first_held_at: ago(20),
		last_held_at: ago(20),
		messages: [{ id: msgId, reason: authenticated ? 'unknown_sender' : 'unverified_sender', subject: p.subject, received_at: ago(20), expires_at: later(13), media_count: thumbs.length, authenticated, thumb_urls: thumbs }]
	};
	(st.emailPending[a.id] ??= []).push(sender);
	st.emailHeld[msgId] = {
		id: msgId,
		address_id: a.id,
		sender_id: sender.id,
		sender_email: p.email,
		sender_name: p.name,
		reason: authenticated ? 'unknown_sender' : 'unverified_sender',
		subject: p.subject,
		caption: p.subject,
		received_at: ago(20),
		expires_at: later(13),
		auth: authenticated ? { authenticated: true, from_domain: p.email.split('@')[1], dkim: 'pass', dkim_domain: p.email.split('@')[1], aligned: 'dkim' } : { authenticated: false, from_domain: p.email.split('@')[1], spf: 'fail', dkim: 'none' },
		media: thumbs.map((u) => ({ kind: 'photo', width: 1600, height: 1200, thumb_url: u })),
		skipped: { too_small: 1 }
	};
	a.counts.pending_senders += 1;
	a.counts.held_messages += 1;
	return sender;
}

test('everything stays hidden when the server has no email inbox', async ({ page }) => {
	const { st, oma, feed } = inboxState(false);
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=settings`);
	await expect(page.getByRole('heading', { name: 'Slideshow' }).first()).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Email photos to this frame' })).toHaveCount(0);
	await page.goto(`/feeds/${feed.id}?tab=settings`);
	await expect(page.getByRole('heading', { name: 'Peeters kids' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Email photos to this feed' })).toHaveCount(0);
	expect(st.calls.some((c) => c.path.includes('email-addresses'))).toBe(false);
});

test('creates an address with a friendly part, then copies and shares it', async ({ page, context }) => {
	await context.grantPermissions(['clipboard-read', 'clipboard-write']);
	const { st, oma } = inboxState();
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=settings`);
	const section = page.getByTestId('email-inbox');
	await expect(section.getByRole('heading', { name: 'Email photos to this frame' })).toBeVisible();
	await expect(section).toContainText('Anyone else needs your OK the first time.');
	await section.getByRole('button', { name: 'Create an address' }).click();

	const dialog = page.getByRole('dialog');
	const slug = dialog.getByLabel('Start of the address');
	await expect(slug).toHaveValue('oma');
	await slug.fill('Oma Peeters');
	await expect(slug).toHaveValue('oma-peeters');
	await expect(dialog.getByText('oma-peeters-••••••@in.kinframe.appmire.be', { exact: false })).toBeVisible();
	await slug.fill('oma');
	await dialog.getByRole('button', { name: 'Create address' }).click();
	await expect(page.getByText('The address is ready.', { exact: false })).toBeVisible();
	const create = st.calls.find((c) => c.method === 'POST' && c.path === `/v1/frames/${oma.id}/email-addresses`)!;
	expect(create.body).toMatchObject({ slug: 'oma', name: 'Oma' });

	const card = page.getByTestId('email-address');
	await expect(card.getByTestId('email-address-value')).toHaveText('oma-k7q2m9@in.kinframe.appmire.be');
	await expect(card).toContainText('Ready');
	await expect(card.getByRole('link', { name: 'Send an email' })).toHaveAttribute('href', 'mailto:oma-k7q2m9@in.kinframe.appmire.be?subject=Oma');

	await card.getByRole('button', { name: 'Copy' }).click();
	await expect(page.getByText('Address copied')).toBeVisible();
	expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('oma-k7q2m9@in.kinframe.appmire.be');

	await card.getByRole('button', { name: 'Share' }).click();
	await expect
		.poll(() => page.evaluate(() => (window as unknown as { __shared: { text: string }[] }).__shared[0]?.text))
		.toBe('You can now email photos to Oma: oma-k7q2m9@in.kinframe.appmire.be. Just attach them; the subject becomes the caption. The first time, Anna approves your address.');
});

test('pause, resume, regenerate (with a warning) and delete', async ({ page }) => {
	const { st, oma } = inboxState();
	existingAddress(st, oma);
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=settings`);
	const card = page.getByTestId('email-address');

	await card.getByRole('button', { name: 'Pause' }).click();
	await expect(card).toContainText('Paused: emails to it bounce');
	await card.getByRole('button', { name: 'Resume' }).click();
	await expect(card).toContainText('Ready');

	await card.getByRole('button', { name: 'New address' }).click();
	const dialog = page.getByRole('dialog');
	await expect(dialog).toContainText('oma-k7q2m9@in.kinframe.appmire.be stops working at once.');
	await dialog.getByRole('button', { name: 'Make a new address' }).click();
	await expect(card.getByTestId('email-address-value')).toHaveText('oma-p3wd8h@in.kinframe.appmire.be');

	await card.getByRole('button', { name: 'Delete' }).click();
	await page.getByRole('dialog').getByRole('button', { name: 'Delete address' }).click();
	await expect(page.getByTestId('email-address')).toHaveCount(0);
	await expect(page.getByRole('button', { name: 'Create an address' })).toBeVisible();
	expect(st.calls.some((c) => c.method === 'DELETE' && c.path.startsWith('/v1/email-addresses/'))).toBe(true);
});

test('a notification opens the pending list: approve one sender, block another, deliver unverified mail by hand', async ({ page }) => {
	const { st, oma } = inboxState();
	const a = existingAddress(st, oma);
	const rita = pendingSender(st, a, { name: 'Rita', email: 'rita@gmail.com', subject: 'Garden party' });
	pendingSender(st, a, { name: 'Spammer', email: 'win@prizes.example', subject: 'You won', photos: 1 });
	const fakeMessage = pendingSender(st, a, { name: 'Jan', email: 'jan.peeters@telenet.be', subject: 'Pics from Sunday', authenticated: false, photos: 2 }).messages[0]!.id;
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=settings&email=${a.id}`);

	const dialog = page.getByRole('dialog');
	await expect(dialog.getByRole('heading', { name: 'Waiting for your OK · Oma’s frame' })).toBeVisible();
	const senders = dialog.getByTestId('email-pending-sender');
	await expect(senders).toHaveCount(3);
	const ritaRow = senders.filter({ hasText: 'rita@gmail.com' });
	await expect(ritaRow).toContainText('Wants to send 3 photos to Oma');
	await expect(ritaRow).toContainText('Garden party');
	await expect(ritaRow).toContainText('Verified');
	await expect(ritaRow.locator('img')).toHaveCount(3);

	await ritaRow.getByRole('button', { name: 'Approve' }).click();
	await expect(page.getByText('Rita may send now', { exact: false })).toBeVisible();
	const approve = st.calls.find((c) => c.path === `/v1/email-addresses/${a.id}/pending-senders/${rita.id}/approve`)!;
	expect(approve.body).toEqual({ deliver: true });

	await senders.filter({ hasText: 'win@prizes.example' }).getByRole('button', { name: 'Block' }).click();
	await expect(page.getByText('Spammer is blocked')).toBeVisible();
	expect(st.calls.find((c) => c.path.endsWith('/reject'))!.body).toEqual({ block: true });

	// Mail that couldn't be verified: no Approve, only "Deliver this one" or delete.
	const janRow = senders.filter({ hasText: 'jan.peeters@telenet.be' });
	await expect(janRow).toContainText('Not verified');
	await expect(janRow).toContainText('We couldn’t verify that this really comes from jan.peeters@telenet.be.');
	await expect(janRow.getByRole('button', { name: 'Approve' })).toHaveCount(0);
	await janRow.getByRole('button', { name: 'View' }).click();
	const held = dialog.getByTestId('email-held');
	await expect(dialog.getByRole('heading', { name: 'Pics from Sunday' })).toBeVisible();
	await expect(held).toContainText('telenet.be doesn’t vouch for this email');
	await expect(held).toContainText('Skipped: 1 too small');
	await expect(held.locator('figure img')).toHaveCount(2);
	await held.getByRole('button', { name: 'Deliver this one' }).click();
	await expect(page.getByText('Sent to Oma')).toBeVisible();
	expect(st.calls.some((c) => c.method === 'POST' && c.path === `/v1/email-addresses/${a.id}/held-messages/${fakeMessage}/deliver`)).toBe(true);
	await expect(dialog.getByText('No one is waiting.')).toBeVisible();

	// Another manager decided meanwhile: the websocket removes it (and updates the count).
	await page.keyboard.press('Escape');
	push(st, { type: 'email_address.updated', address: { ...a, counts: { ...a.counts, pending_senders: 0 } } });
	await expect(page.getByTestId('email-pending-callout')).toHaveCount(0);
});

test('allowed senders: add, refuse a duplicate, block, unblock and remove', async ({ page }) => {
	const { st, oma } = inboxState();
	const a = existingAddress(st, oma);
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=settings`);
	await page.getByTestId('email-address').getByRole('button', { name: 'Senders' }).click();
	const dialog = page.getByRole('dialog');
	await expect(dialog).toContainText('Members of Oma can send from their account email');
	await dialog.getByLabel('Email address').fill('jan.peeters@telenet.be');
	await dialog.getByLabel(/^Name/).fill('Jan');
	await dialog.getByRole('button', { name: 'Allow' }).click();
	await expect(page.getByText('jan.peeters@telenet.be may send now')).toBeVisible();
	const row = dialog.getByTestId('email-sender');
	await expect(row).toContainText('Jan');
	expect(st.calls.find((c) => c.method === 'POST' && c.path === `/v1/email-addresses/${a.id}/senders`)!.body).toEqual({ email: 'jan.peeters@telenet.be', display_name: 'Jan' });

	await dialog.getByLabel('Email address').fill('Jan.Peeters@telenet.be');
	await dialog.getByRole('button', { name: 'Allow' }).click();
	await expect(page.getByText('That address is already on the list.')).toBeVisible();

	await row.getByRole('button', { name: 'Block' }).click();
	await expect(row).toContainText('Blocked');
	await row.getByRole('button', { name: 'Unblock' }).click();
	await expect(row).not.toContainText('Blocked');
	await row.getByRole('button', { name: 'Remove Jan' }).click();
	await expect(dialog.getByTestId('email-sender')).toHaveCount(0);
});

test('recent mail says what became of each email', async ({ page }) => {
	const { st, oma } = inboxState();
	const a = existingAddress(st, oma, { last_received_at: ago(60) });
	st.emailMessages[a.id] = [
		{ id: uid('em'), received_at: ago(60), sender_name: 'Jan', sender_email: 'jan@telenet.be', authenticated: true, outcome: 'delivered', items: 4, skipped: { too_long: 1 }, reply: 'delivered' },
		{ id: uid('em'), received_at: ago(600), sender_name: 'Jan', sender_email: 'jan@telenet.be', authenticated: true, outcome: 'no_media', reply: 'no_media' },
		{ id: uid('em'), received_at: ago(900), sender_email: 'x@example.org', authenticated: false, outcome: 'rejected_dmarc' }
	];
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=settings`);
	await expect(page.getByTestId('email-address')).toContainText('Ready · last email 1 hour ago');
	await page.getByRole('button', { name: 'Recent emails' }).click();
	const entries = page.getByTestId('email-log-entry');
	await expect(entries).toHaveCount(3);
	await expect(entries.nth(0)).toContainText('Jan · 4 photos delivered');
	await expect(entries.nth(0)).toContainText('Skipped: 1 video too long');
	await expect(entries.nth(0)).toContainText('Told: the photos arrived');
	await expect(entries.nth(1)).toContainText('Jan · no photos found');
	await expect(entries.nth(2)).toContainText('Not really from example.org · refused');
});

test('settings: slug change warns, and the feed gets its own address', async ({ page }) => {
	const { st, oma, feed } = inboxState();
	existingAddress(st, oma);
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=settings`);
	await page.getByTestId('email-address').getByRole('button', { name: 'Settings' }).click();
	const dialog = page.getByRole('dialog');
	await dialog.getByLabel('Start of the address').fill('grandma');
	await expect(dialog).toContainText('the old one stops working at once');
	await dialog.getByLabel('Anyone with the address may send').check();
	await dialog.getByRole('button', { name: 'Save' }).click();
	await expect(page.getByText('The address is now grandma-k7q2m9@in.kinframe.appmire.be')).toBeVisible();
	expect(st.calls.find((c) => c.method === 'PATCH' && c.path.startsWith('/v1/email-addresses/'))!.body).toEqual({ slug: 'grandma', anyone_may_send: true });

	await page.goto(`/feeds/${feed.id}?tab=settings`);
	const section = page.getByTestId('email-inbox');
	await expect(section.getByRole('heading', { name: 'Email photos to this feed' })).toBeVisible();
	await section.getByRole('button', { name: 'Create an address' }).click();
	await expect(page.getByRole('dialog').getByLabel('Start of the address')).toHaveValue('peeters-kids');
	await page.getByRole('dialog').getByRole('button', { name: 'Create address' }).click();
	await expect(section.getByTestId('email-address-value')).toHaveText('peeters-kids-k7q2m9@in.kinframe.appmire.be');
	expect(st.calls.some((c) => c.method === 'POST' && c.path === `/v1/feeds/${feed.id}/email-addresses`)).toBe(true);
});

test('credit and join requests: "Grandpa via email" and "asked to join from the email link"', async ({ page }) => {
	const { st, oma, feed } = inboxState();
	st.feedPosts[feed.id] = [
		{
			id: uid('po'),
			feed_id: feed.id,
			poster: { user_id: st.me!.id, display_name: 'Anna' },
			via: { service: 'email', label: 'Grandpa', address_id: uid('ea') },
			created_at: ago(30),
			items: Array.from({ length: 2 }, () => ({ id: uid('i'), kind: 'photo' as const, status: 'ready' as const, sender_id: st.me!.id, sender_name: 'Anna', caption: null, created_at: ago(30) })),
			receipts: null,
			reactions: null
		}
	];
	st.joinRequests[oma.id] = [
		{ id: uid('jr'), user: { id: uid('u'), display_name: 'Jan Peeters' }, via: 'email', via_name: 'Jan · jan.peeters@telenet.be', via_matches: true, created_at: ago(5) }
	];
	await signIn(page, st);
	await page.goto(`/feeds/${feed.id}`);
	await expect(page.getByText('Grandpa via email')).toBeVisible();
	await expect(page.getByText('2 photos', { exact: false }).first()).toBeVisible();

	await page.goto(`/frames/${oma.id}?tab=people`);
	await expect(page.getByText('Asked to join from the email link')).toBeVisible();
	const via = page.getByTestId('join-via-name');
	await expect(via).toContainText('Jan · jan.peeters@telenet.be');
	await expect(via.getByTitle('Their account uses this email address')).toBeVisible();
});
