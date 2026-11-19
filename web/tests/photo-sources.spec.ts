import { expect, test, type Page } from '@playwright/test';
import type { components } from '../src/lib/api/schema';
import { defaultSettings, emptyState, makeFrame, mockApi, push, uid, type MockState } from './mock-api';

// Photo sources (docs/spec/photo-sources.md): the add-source wizard on albums and feeds, the source cards, and
// "Add from Google Photos", against the in-memory mock API.

type S = components['schemas'];
const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();

function sourcesState(opts: { on?: boolean; google?: boolean; privateNetworks?: boolean; providers?: S['PhotoSourceProvider'][] } = {}) {
	const st = emptyState();
	const on = opts.on ?? true;
	st.discovery.feeds = true;
	st.discovery.photo_sources = on;
	st.discovery.photo_source_providers = on ? (opts.providers ?? ['immich_link', 'immich_api', 'icloud_link']) : [];
	st.discovery.photo_sources_private_networks = opts.privateNetworks ?? true;
	st.discovery.google_photos_import = opts.google ?? true;
	st.me = { id: uid('u'), display_name: 'Anna', email: 'anna@example.org', avatar_url: null, locale: 'en', created_at: ago(9999) };
	const oma = makeFrame({ name: 'Oma’s frame', role: 'owner' });
	st.frames = [oma];
	st.settings[oma.id] = defaultSettings(1);
	const garden: S['Album'] = { id: uid('a'), name: 'Garden', version: 1, placement_count: 0, placement_ids: [], schedule: null, photo_source_count: 0 };
	st.albums[oma.id] = [garden];
	const feed: S['Feed'] = { id: uid('fd'), name: 'Peeters kids', cover_url: null, role: 'owner', member_count: 2, subscription_count: 3, created_at: ago(9000), owner_name: 'Anna' };
	st.feeds = [feed];
	st.feedPosts[feed.id] = [];
	return { st, oma, garden, feed };
}

async function signIn(page: Page, st: MockState) {
	await mockApi(page, st);
	await page.goto('/');
	await page.getByLabel('Email').fill('anna@example.org');
	await page.getByRole('button', { name: 'Continue' }).click();
	await page.waitForURL(/\/frames$/);
}

function existingSource(st: MockState, target: S['PhotoSourceTarget'], p: Partial<S['PhotoSource']> = {}): S['PhotoSource'] {
	const s: S['PhotoSource'] = {
		id: uid('ps'),
		provider: 'immich_link',
		name: 'Garden 2026',
		target,
		owner: { user_id: st.me!.id, display_name: 'Anna' },
		url_hint: 'https://photos.peeters.be/share/…',
		status: 'active',
		running: false,
		warnings: [],
		history: 'all',
		on_upstream_delete: 'mirror',
		include_videos: true,
		notify_reactions: true,
		counts: { upstream: 220, imported: 220, pending: 0 },
		last_synced_at: ago(2),
		next_sync_at: new Date(Date.now() + 28 * 60_000).toISOString(),
		can_manage: true,
		is_owner: true,
		created_at: ago(900),
		...p
	};
	st.photoSources.push(s);
	return s;
}

test('everything stays hidden when the server has no photo sources', async ({ page }) => {
	const { st, oma, garden, feed } = sourcesState({ on: false, google: false });
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=library&album=${garden.id}`);
	await expect(page.getByRole('heading', { name: 'Garden' })).toBeVisible();
	await expect(page.getByRole('button', { name: 'Sync from…' })).toHaveCount(0);
	await expect(page.getByRole('button', { name: 'Add from Google Photos' })).toHaveCount(0);
	await page.goto(`/feeds/${feed.id}`);
	await expect(page.getByRole('heading', { name: 'Peeters kids' })).toBeVisible();
	await expect(page.getByRole('tab', { name: 'Sources' })).toHaveCount(0);
	await page.goto('/settings');
	await expect(page.getByRole('link', { name: /My photo sources/ })).toHaveCount(0);
});

test('connects an Immich shared link to an album, with the album-only suggestion', async ({ page }) => {
	const { st, oma, garden } = sourcesState();
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=library&album=${garden.id}`);
	await page.getByRole('button', { name: 'Sync from…' }).click();
	const dialog = page.getByRole('dialog');
	await expect(dialog.getByRole('heading', { name: 'Sync photos into Garden' })).toBeVisible();
	await dialog.getByRole('button', { name: /Immich shared link/ }).click();
	const link = dialog.getByLabel('Shared link');
	await link.fill('https://photos.peeters.be/albums/123');
	await expect(dialog.getByText('That isn’t an Immich shared link.', { exact: false })).toBeVisible();
	await expect(dialog.getByRole('button', { name: 'Test connection' })).toBeDisabled();
	await link.fill('https://photos.peeters.be/share/0d1eAbCdEf');
	await expect(dialog.getByRole('button', { name: 'Continue' })).toBeDisabled();
	await dialog.getByRole('button', { name: 'Test connection' }).click();
	await expect(dialog.getByTestId('source-test-ok')).toContainText('Garden 2026');
	await expect(dialog.getByTestId('source-test-ok')).toContainText('214 photos, 6 videos');
	await dialog.getByRole('button', { name: 'Continue' }).click();

	// Album defaults: everything, mirror removals.
	await expect(dialog.getByLabel('Everything in the album')).toBeChecked();
	await expect(dialog.getByLabel(/Remove it here too/)).toBeChecked();
	await expect(dialog.getByText('Everyone who can see this frame will see them', { exact: false })).toBeVisible();
	await expect(dialog.getByTestId('suggest-album-only')).toContainText('This adds 220 photos to the album.');
	await dialog.getByTestId('suggest-album-only').getByRole('checkbox').check();
	await dialog.getByRole('button', { name: 'Connect' }).click();

	await expect(page.getByText('Garden 2026 is connected.', { exact: false })).toBeVisible();
	const card = page.getByTestId('photo-source');
	await expect(card).toContainText('Importing 0 of 220');
	const create = st.calls.find((c) => c.method === 'POST' && c.path === '/v1/photo-sources')!;
	expect(create.body).toMatchObject({ provider: 'immich_link', url: 'https://photos.peeters.be/share/0d1eAbCdEf', album_id: garden.id, history: 'all', on_upstream_delete: 'mirror' });
	await expect.poll(() => st.calls.some((c) => c.method === 'PATCH' && c.path.endsWith(`/albums/${garden.id}`) && (c.body as { only_in_album_mode?: boolean }).only_in_album_mode === true)).toBe(true);
	await expect(page.getByRole('checkbox', { name: /Only in album mode/ })).toBeChecked();

	// The run finishes: the websocket carries the new state.
	const src = st.photoSources[0]!;
	push(st, { type: 'photo_source.updated', source: { ...src, running: false, counts: { imported: 220, pending: 0, upstream: 220 }, last_synced_at: new Date().toISOString() } });
	await expect(card).toContainText('Synced from Immich');
});

test('needs attention: confirm a mass removal, and Sync now is rate-limited', async ({ page }) => {
	const { st, oma, garden } = sourcesState();
	garden.photo_source_count = 1;
	const src = existingSource(st, { kind: 'album', frame_id: oma.id, album_id: garden.id, album_name: 'Garden' }, {
		status: 'needs_attention',
		last_error_code: 'mass_removal',
		pending_removals: 142,
		next_sync_at: null
	});
	st.syncRetryAfter = 90;
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=library&album=${garden.id}`);
	const card = page.getByTestId('photo-source');
	await expect(card).toContainText('Needs your attention');
	await expect(card).toContainText('142 photos disappeared from the Immich album. Remove them here too?');

	await card.getByRole('button', { name: 'Sync now' }).click();
	await expect(page.getByText('You can sync again in 2 minutes.', { exact: false })).toBeVisible();
	st.syncRetryAfter = undefined;

	await card.getByRole('button', { name: 'Review' }).click();
	await page.getByRole('dialog').getByRole('button', { name: 'Remove them here too' }).click();
	await expect.poll(() => st.calls.find((c) => c.method === 'POST' && c.path === `/v1/photo-sources/${src.id}/sync` && (c.body as { confirm_removals?: boolean })?.confirm_removals)).toBeTruthy();
	await expect(card).not.toContainText('Needs your attention');
});

test('pauses, then stops a source and removes its photos', async ({ page }) => {
	const { st, oma, garden } = sourcesState();
	garden.photo_source_count = 1;
	const src = existingSource(st, { kind: 'album', frame_id: oma.id, album_id: garden.id, album_name: 'Garden' });
	await signIn(page, st);
	await page.goto(`/frames/${oma.id}?tab=library&album=${garden.id}`);
	const card = page.getByTestId('photo-source');
	await card.getByRole('button', { name: 'Pause' }).click();
	await expect(card).toContainText('Paused');
	await expect(card.getByRole('button', { name: 'Sync now' })).toHaveCount(0);
	await card.getByRole('button', { name: 'Stop syncing' }).click();
	const dialog = page.getByRole('dialog');
	await expect(dialog).toContainText('What happens to the 220 photos it brought in?');
	await dialog.getByLabel('Remove them from the frame').check();
	await dialog.getByRole('button', { name: 'Stop syncing' }).click();
	await expect(page.getByTestId('photo-source')).toHaveCount(0);
	const del = st.calls.find((c) => c.method === 'DELETE' && c.path === `/v1/photo-sources/${src.id}`);
	expect(del).toBeTruthy();
});

test('a feed syncs from an Immich API key, with the feed defaults and the batching note', async ({ page }) => {
	const { st, feed } = sourcesState({ privateNetworks: false });
	await signIn(page, st);
	await page.goto(`/feeds/${feed.id}?tab=sources`);
	await expect(page.getByText('Connect an Immich or iCloud album', { exact: false })).toBeVisible();
	await page.getByRole('button', { name: 'Add a source' }).click();
	const dialog = page.getByRole('dialog');
	await dialog.getByRole('button', { name: /Immich server and API key/ }).click();
	await expect(dialog.getByText('This server can’t reach addresses on your home network.', { exact: false })).toBeVisible();
	await dialog.getByLabel('Immich server address').fill('photos.peeters.be/api');
	await dialog.getByLabel('API key').fill('bad-key-123456');
	await dialog.getByRole('button', { name: 'Continue' }).click();
	await expect(dialog.getByText('Immich refused the API key. Enter it again.')).toBeVisible();
	await dialog.getByLabel('API key').fill('good-key-123456');
	await dialog.getByRole('button', { name: 'Continue' }).click();
	await dialog.getByLabel(/Christmas/).check();
	await expect(dialog.getByTestId('source-test-ok')).toContainText('Christmas');
	await dialog.getByRole('button', { name: 'Continue' }).click();
	await expect(dialog.getByLabel('The newest')).toBeChecked();
	await expect(dialog.getByLabel('Number of photos')).toHaveValue('20');
	await expect(dialog.getByLabel('Keep it in the feed')).toBeChecked();
	await expect(dialog.getByText('New photos are posted in one go once the album has been quiet for a while.')).toBeVisible();
	await expect(dialog.getByTestId('suggest-album-only')).toHaveCount(0);
	await dialog.getByRole('button', { name: 'Connect' }).click();
	await expect(page.getByTestId('photo-source')).toContainText('Christmas');
	const create = st.calls.find((c) => c.method === 'POST' && c.path === '/v1/photo-sources')!;
	expect(create.body).toMatchObject({
		provider: 'immich_api',
		server_url: 'https://photos.peeters.be',
		api_key: 'good-key-123456',
		immich_album_id: 'im-christmas',
		feed_id: feed.id,
		history: 'last_n',
		history_count: 20,
		on_upstream_delete: 'keep'
	});
});

test('iCloud: best-effort note, and an iCloud Link for photos is refused', async ({ page }) => {
	const { st, feed } = sourcesState();
	await signIn(page, st);
	await page.goto(`/feeds/${feed.id}?tab=sources`);
	await page.getByRole('button', { name: 'Add a source' }).click();
	const dialog = page.getByRole('dialog');
	await dialog.getByRole('button', { name: /iCloud shared album/ }).click();
	await expect(dialog.getByText('Best effort: Apple offers no official way to do this.', { exact: false })).toBeVisible();
	await expect(dialog.getByText('turn on Public Website', { exact: false })).toBeVisible();
	const link = dialog.getByLabel('Link to the shared album');
	await link.fill('https://www.icloud.com/photos/#0a1b2c3d4e5f');
	await expect(dialog.getByText('This is an iCloud Link for a few photos, not a shared album.', { exact: false })).toBeVisible();
	await link.fill('https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz');
	await dialog.getByRole('button', { name: 'Test connection' }).click();
	await expect(dialog.getByTestId('source-test-ok')).toContainText('Oma & Opa');
});

test('My photo sources lists my sources with their target, and credits show "via Immich"', async ({ page }) => {
	const { st, oma, garden, feed } = sourcesState();
	existingSource(st, { kind: 'album', frame_id: oma.id, frame_name: oma.name, album_id: garden.id, album_name: 'Garden' });
	existingSource(st, { kind: 'feed', feed_id: feed.id, feed_name: feed.name }, { name: 'Kids', provider: 'icloud_link', best_effort: true, status: 'error', last_error_code: 'unreachable', error_since: ago(600) });
	const item: S['Item'] = { id: uid('it'), kind: 'photo', status: 'ready', created_at: ago(30), sender_name: 'Anna', via: { service: 'immich', label: 'Immich', source_id: null } } as S['Item'];
	st.feedPosts[feed.id] = [
		{ id: uid('po'), feed_id: feed.id, poster: { user_id: st.me!.id, display_name: 'Anna' }, created_at: ago(30), items: [item], via: { service: 'immich', label: 'Immich' } } as S['FeedPost']
	];
	await signIn(page, st);
	await page.goto('/settings');
	await page.getByRole('link', { name: /My photo sources/ }).click();
	await expect(page).toHaveURL(/\/photo-sources$/);
	const cards = page.getByTestId('photo-source');
	await expect(cards).toHaveCount(2);
	await expect(cards.first()).toContainText('Kids');
	await expect(cards.first()).toContainText('best effort');
	await expect(cards.first()).toContainText('Can’t reach photos.peeters.be. Is the server on? We keep trying.');
	await expect(cards.nth(1).getByRole('link', { name: 'Garden · Oma’s frame' })).toHaveAttribute('href', `/frames/${oma.id}?tab=library&album=${garden.id}`);

	await page.goto(`/feeds/${feed.id}`);
	await expect(page.getByText('1 photo via Immich', { exact: false })).toBeVisible();
});

// ------------------------------------------------------------------ Google Photos import

/** Plays Google: consent redirects to `return_uri` (status picking), the picker is a plain page. */
async function fakeGoogle(page: Page, st: MockState) {
	const ctx = page.context();
	await ctx.route('https://accounts.google.com/**', async (route) => {
		const id = new URL(route.request().url()).searchParams.get('state')!;
		const imp = st.googleImports.find((x) => x.id === id)!;
		Object.assign(imp, { status: 'picking', auth_url: null, picker_uri: `https://photos.google.com/picker/${id}` });
		const back = `${imp.return_uri}?import_id=${id}&status=picking`;
		await route.fulfill({ status: 200, contentType: 'text/html', body: `<!doctype html><title>Google</title><script>location.replace(${JSON.stringify(back)})</script>` });
	});
	await ctx.route('https://photos.google.com/**', (route) => route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><title>Picker</title><p>Google Photos picker</p>' }));
}

function progressTo(st: MockState, status: S['GoogleImportStatus'], progress: Partial<S['GoogleImport']['progress']>) {
	const imp = st.googleImports[0]!;
	Object.assign(imp, { status, picker_uri: status === 'picking' ? imp.picker_uri : null, progress: { ...imp.progress, ...progress }, send_ids: status === 'done' ? [uid('s')] : [] });
	const { return_uri: _r, ...out } = imp;
	push(st, { type: 'google_import.updated', import: out });
}

test('Add from Google Photos: consent in a popup, then the picker, then progress and a summary', async ({ page }) => {
	const { st, feed } = sourcesState();
	await signIn(page, st);
	await fakeGoogle(page, st);
	await page.goto(`/feeds/${feed.id}`);
	const popupP = page.waitForEvent('popup');
	await page.getByRole('button', { name: 'Add from Google Photos' }).click();
	const popup = await popupP;
	// Consent → return page → the popup goes on to the picker.
	await popup.waitForURL(/photos\.google\.com\/picker\/.+\/autoclose$/);
	const start = st.calls.find((c) => c.method === 'POST' && c.path === '/v1/google-imports')!;
	expect(start.body).toEqual({ feed_id: feed.id, return_uri: expect.stringMatching(/\/google-import\/return$/) });
	const dialog = page.getByTestId('google-import');
	await expect(dialog).toHaveAttribute('data-status', 'picking');
	await expect(dialog).toContainText('Waiting for Google Photos…');

	progressTo(st, 'importing', { picked: 23, imported: 9 });
	await expect(dialog).toContainText('9 of 23');
	progressTo(st, 'done', { picked: 23, imported: 21, duplicates: 2 });
	await expect(dialog).toContainText('21 photos added');
	await expect(dialog).toContainText('2 were already there');
	await expect(page.getByRole('dialog').getByRole('button', { name: 'Undo' })).toBeVisible();
});

test('Add from Google Photos with popups blocked: same-tab consent comes back and resumes', async ({ page }) => {
	const { st, oma, garden } = sourcesState();
	await page.addInitScript(() => {
		window.open = () => null;
	});
	await signIn(page, st);
	await fakeGoogle(page, st);
	await page.goto(`/frames/${oma.id}?tab=library&album=${garden.id}`);
	await page.getByRole('button', { name: 'Add from Google Photos' }).click();
	const dialog = page.getByTestId('google-import');
	await expect(dialog).toContainText('Your browser blocked the Google window.');
	await page.getByRole('button', { name: 'Continue to Google' }).click();
	// Google → /google-import/return → back to the album, which picks the import up again.
	await page.waitForURL(new RegExp(`/frames/${oma.id}\\?tab=library&album=${garden.id}&google_import=`));
	await expect(page.getByTestId('google-import')).toHaveAttribute('data-status', 'picking');
	await expect(page.getByRole('button', { name: 'Open Google Photos' })).toBeVisible();
	await page.getByRole('button', { name: 'Cancel import' }).click();
	await expect(page.getByTestId('google-import')).toContainText('Cancelled');
});

test('the Google return page refuses a bad link', async ({ page }) => {
	await page.goto('/google-import/return?import_id=../../x&status=picking');
	await expect(page.getByText('This link has expired. Go back to kinframe and try again.')).toBeVisible();
});
