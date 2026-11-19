import { describe, expect, it } from 'vitest';
import type { PhotoSource } from './api/client';
import {
	belongsTo,
	canContinue,
	chooseProvider,
	connectionBody,
	connectionProblem,
	createBody,
	defaultSettings,
	errorText,
	firstImportCount,
	fixFor,
	suggestAlbumOnly,
	guessProvider,
	isPrivateHost,
	isTested,
	newWizard,
	nextStep,
	normalizeImmichServer,
	offeredProviders,
	parseIcloudLink,
	parseImmichLink,
	pickAlbum,
	prevStep,
	retryText,
	sortSources,
	statusView,
	targetHref,
	warningText,
	withTest,
	wizardSteps
} from './photoSources';

const NOW = new Date('2026-11-19T10:00:00Z');
const ago = (min: number) => new Date(NOW.getTime() - min * 60_000).toISOString();

function source(p: Partial<PhotoSource> = {}): PhotoSource {
	return {
		id: '0192f0a0-0001-7000-8000-000000000001',
		provider: 'immich_link',
		name: 'Garden',
		target: { kind: 'album', frame_id: 'f1', frame_name: 'Oma’s frame', album_id: 'a1', album_name: 'Garden' },
		owner: { user_id: 'u1', display_name: 'Anna' },
		url_hint: 'https://photos.peeters.be/share/…',
		status: 'active',
		running: false,
		history: 'all',
		on_upstream_delete: 'mirror',
		include_videos: true,
		counts: { imported: 220, pending: 0 },
		created_at: ago(9000),
		...p
	};
}

describe('Immich shared links', () => {
	it('accepts /share/<key> and /s/<slug>, with a path prefix', () => {
		const r = parseImmichLink('https://photos.example.org/share/0d1eAbC-x_9');
		expect(r).toMatchObject({ ok: true, host: 'photos.example.org', apiBase: 'https://photos.example.org/api', key: '0d1eAbC-x_9' });
		expect(parseImmichLink('https://example.org/immich/share/abcdef12/')).toMatchObject({ ok: true, apiBase: 'https://example.org/immich/api' });
		expect(parseImmichLink('photos.example.org/s/garden-2026')).toMatchObject({ ok: true, slug: 'garden-2026' });
	});

	it('rejects other links and explains why', () => {
		expect(parseImmichLink('')).toEqual({ ok: false, problem: 'empty' });
		expect(parseImmichLink('not a url')).toEqual({ ok: false, problem: 'invalid' });
		expect(parseImmichLink('https://photos.example.org/albums/123')).toEqual({ ok: false, problem: 'not_immich_link' });
		expect(parseImmichLink('https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz')).toEqual({ ok: false, problem: 'not_immich_link' });
		expect(parseImmichLink('ftp://photos.example.org/share/abcdef')).toEqual({ ok: false, problem: 'invalid' });
	});

	it('allows plain http only on a home network', () => {
		expect(parseImmichLink('http://photos.example.org/share/abcdef12')).toEqual({ ok: false, problem: 'insecure' });
		expect(parseImmichLink('http://192.168.1.10:2283/share/abcdef12')).toMatchObject({ ok: true, host: '192.168.1.10:2283' });
	});

	it('normalizes an Immich server address (an /api suffix is tolerated)', () => {
		expect(normalizeImmichServer('https://photos.peeters.be/api/')).toEqual({ ok: true, url: 'https://photos.peeters.be', host: 'photos.peeters.be' });
		expect(normalizeImmichServer('photos.peeters.be')).toMatchObject({ ok: true, url: 'https://photos.peeters.be' });
		expect(normalizeImmichServer('http://immich.lan:2283')).toMatchObject({ ok: true, url: 'http://immich.lan:2283' });
		expect(normalizeImmichServer('http://photos.peeters.be')).toEqual({ ok: false, problem: 'insecure' });
	});
});

describe('iCloud shared album links', () => {
	it('accepts the public website link, with or without www and a language segment', () => {
		expect(parseIcloudLink('https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz')).toEqual({ ok: true, url: 'https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz', token: 'B0aGWZuqDGxyz' });
		expect(parseIcloudLink('https://icloud.com/sharedalbum/nl-nl/#B0aGWZuqDGxyz')).toMatchObject({ ok: true, token: 'B0aGWZuqDGxyz' });
		expect(parseIcloudLink('www.icloud.com/sharedalbum/#B0aGWZuqDGxyz')).toMatchObject({ ok: true });
	});

	it('rejects iCloud Links for photos and anything else', () => {
		expect(parseIcloudLink('https://www.icloud.com/photos/#0a1b2c3d4e')).toEqual({ ok: false, problem: 'icloud_photos_link' });
		expect(parseIcloudLink('https://www.icloud.com/sharedalbum/')).toEqual({ ok: false, problem: 'not_icloud_link' });
		expect(parseIcloudLink('https://evil.example/sharedalbum/#B0aGWZuqDGxyz')).toEqual({ ok: false, problem: 'not_icloud_link' });
		expect(parseIcloudLink('https://www.icloud.com.evil.example/sharedalbum/#B0aGWZuqDGxyz')).toEqual({ ok: false, problem: 'not_icloud_link' });
	});

	it('guesses the provider of a pasted link', () => {
		expect(guessProvider('https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz')).toBe('icloud_link');
		expect(guessProvider('https://photos.example.org/share/abcdef12')).toBe('immich_link');
		expect(guessProvider('https://example.org')).toBeNull();
	});
});

describe('private hosts', () => {
	it('knows home-network addresses', () => {
		for (const h of ['192.168.1.10', '10.0.0.2', '172.20.1.1', '100.100.1.1', 'localhost', 'nas', 'immich.lan', 'photos.home.arpa', '::1', 'fd12:3456::1'])
			expect(isPrivateHost(h), h).toBe(true);
		for (const h of ['photos.peeters.be', '8.8.8.8', '172.32.0.1', '2001:db8::1']) expect(isPrivateHost(h), h).toBe(false);
	});
});

describe('defaults per target', () => {
	it('albums import everything and mirror; feeds take the last 20 and keep', () => {
		expect(defaultSettings('album')).toMatchObject({ history: 'all', onUpstreamDelete: 'mirror', includeVideos: true, notifyReactions: true });
		expect(defaultSettings('feed')).toMatchObject({ history: 'last_n', historyCount: 20, onUpstreamDelete: 'keep' });
	});

	it('offers only the providers the server allows, in wizard order', () => {
		expect(offeredProviders(['icloud_link', 'immich_link', 'immich_api'])).toEqual(['immich_link', 'immich_api', 'icloud_link']);
		expect(offeredProviders(['immich_link'])).toEqual(['immich_link']);
		expect(offeredProviders(undefined)).toEqual([]);
	});
});

describe('wizard', () => {
	const all = offeredProviders(['immich_link', 'immich_api', 'icloud_link']);
	const album = { kind: 'album' as const, frameId: 'f1', albumId: 'a1', name: 'Garden' };
	const feed = { kind: 'feed' as const, feedId: 'fd1', name: 'Peeters kids' };

	it('starts at the provider choice, or at connect with only one provider', () => {
		expect(newWizard('album', all).step).toBe('provider');
		const one = newWizard('feed', ['immich_link']);
		expect(one).toMatchObject({ step: 'connect', provider: 'immich_link' });
		expect(wizardSteps(one, 1)).toEqual(['connect', 'settings']);
		expect(wizardSteps({ provider: 'immich_api' }, 3)).toEqual(['provider', 'connect', 'album', 'settings']);
	});

	it('needs a passing test for the link as it stands', () => {
		let w = chooseProvider(newWizard('album', all), 'immich_link');
		expect(canContinue(w)).toBe(false);
		w = { ...w, url: 'https://photos.example.org/share/abcdef12', password: 'secret' };
		expect(connectionProblem(w)).toBeNull();
		expect(connectionBody(w)).toEqual({ provider: 'immich_link', url: 'https://photos.example.org/share/abcdef12', password: 'secret' });
		w = withTest(w, { ok: true, album_name: 'Garden 2026', photos: 214, videos: 6 });
		expect(isTested(w)).toBe(true);
		expect(w.name).toBe('Garden 2026');
		expect(canContinue(w)).toBe(true);
		// Editing the link invalidates the test.
		const edited = { ...w, url: 'https://photos.example.org/share/other123' };
		expect(isTested(edited)).toBe(false);
		expect(canContinue(edited)).toBe(false);
		// A failed test doesn't count.
		expect(isTested(withTest(edited, { ok: false, error_code: 'link_not_found' }))).toBe(false);
	});

	it('goes forward and back through the steps', () => {
		let w = chooseProvider(newWizard('album', all), 'immich_api');
		expect(w.step).toBe('connect');
		w = nextStep(w, 3);
		expect(w.step).toBe('album');
		w = prevStep(prevStep(w, 3), 3);
		expect(w.step).toBe('provider');
	});

	it('picks an Immich album with an API key, then tests it', () => {
		let w = chooseProvider(newWizard('album', all), 'immich_api');
		w = { ...w, serverUrl: 'photos.peeters.be/api', apiKey: 'short' };
		expect(connectionProblem(w)).toBe('no_key');
		w = { ...w, apiKey: 'abcdefghijkl' };
		expect(canContinue(w)).toBe(true);
		w = nextStep({ ...w, albums: [{ id: 'im1', name: 'Christmas', asset_count: 80 }] }, 3);
		expect(canContinue(w)).toBe(false);
		w = pickAlbum(w, 'im1');
		expect(w.name).toBe('Christmas');
		expect(connectionBody(w)).toEqual({ provider: 'immich_api', server_url: 'https://photos.peeters.be', api_key: 'abcdefghijkl', immich_album_id: 'im1' });
		expect(connectionBody(w, false)).not.toHaveProperty('immich_album_id');
		w = withTest(w, { ok: true, album_name: 'Christmas' });
		expect(canContinue(w)).toBe(true);
	});

	it('builds the create body for an album or a feed', () => {
		let w = chooseProvider(newWizard('feed', all), 'icloud_link');
		w = { ...w, url: 'https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz', name: '  Kids ' };
		expect(createBody(w, feed)).toEqual({
			provider: 'icloud_link',
			url: 'https://www.icloud.com/sharedalbum/#B0aGWZuqDGxyz',
			feed_id: 'fd1',
			name: 'Kids',
			history: 'last_n',
			history_count: 20,
			on_upstream_delete: 'keep',
			include_videos: true,
			notify_reactions: true
		});
		const a = { ...chooseProvider(newWizard('album', all), 'immich_link'), url: 'https://photos.example.org/share/abcdef12' };
		expect(createBody(a, album)).toMatchObject({ album_id: 'a1', history: 'all', on_upstream_delete: 'mirror' });
		expect(createBody(a, album)).not.toHaveProperty('history_count');
		expect(createBody({ ...a, url: 'nope' }, album)).toBeNull();
	});
});

describe('status texts', () => {
	it('shows when it synced and when it syncs next', () => {
		const v = statusView(source({ last_synced_at: ago(2), next_sync_at: new Date(NOW.getTime() + 28 * 60_000).toISOString() }), 'en', NOW);
		expect(v).toEqual({ tone: 'ok', label: 'Synced from Immich 2 minutes ago', detail: null, next: 'next in 28 minutes' });
	});

	it('shows the first import, catching up, paused', () => {
		expect(statusView(source({ running: true, counts: { imported: 40, pending: 180 } }), 'en', NOW).label).toBe('Importing 40 of 220');
		expect(statusView(source({ running: true }), 'en', NOW)).toMatchObject({ tone: 'running', label: 'Syncing from Immich…' });
		expect(statusView(source({ last_synced_at: null }), 'en', NOW).label).toBe('Waiting for the first sync');
		expect(statusView(source({ status: 'paused', next_sync_at: ago(-5) }), 'en', NOW)).toEqual({ tone: 'paused', label: 'Paused', detail: null, next: null });
	});

	it('explains errors with the host and needs-attention codes with their fix', () => {
		const err = statusView(source({ status: 'error', last_error_code: 'unreachable', error_since: '2026-11-17T10:00:00Z' }), 'en', NOW);
		expect(err.tone).toBe('error');
		expect(err.detail).toBe('Can’t reach photos.peeters.be. Is the server on? We keep trying.');
		const att = statusView(source({ status: 'needs_attention', last_error_code: 'owner_lost_access' }), 'en', NOW);
		expect(att).toMatchObject({ tone: 'attention', label: 'Needs your attention', detail: 'Anna can no longer manage this, so syncing stopped. Take it over or stop syncing.' });
		expect(fixFor(source({ status: 'needs_attention', last_error_code: 'owner_lost_access' }))).toBe('take_over');
		expect(fixFor(source({ status: 'needs_attention', last_error_code: 'link_not_found' }))).toBe('reconnect');
		expect(fixFor(source({ status: 'needs_attention', last_error_code: 'mass_removal', pending_removals: 342 }))).toBe('confirm_removals');
		expect(fixFor(source({ status: 'needs_attention', last_error_code: 'format_changed' }))).toBeNull();
		expect(fixFor(source({ status: 'active', last_error_code: 'auth_failed' }))).toBeNull();
	});

	it('words errors per provider', () => {
		expect(errorText('auth_failed', { provider: 'immich_api' })).toBe('Immich refused the API key. Enter it again.');
		expect(errorText('auth_failed', { provider: 'immich_link' })).toBe('Immich refused the link password. Enter it again.');
		expect(errorText('link_not_found', { provider: 'icloud_link' })).toContain('Public Website');
		expect(errorText('format_changed', { provider: 'icloud_link' })).toContain('once a day');
		expect(errorText('mass_removal', { provider: 'immich_link', pending_removals: 342 })).toBe('342 photos disappeared from the Immich album. Remove them here too?');
		expect(errorText('upstream_error', { provider: 'immich_api', server_url: 'https://photos.peeters.be' })).toBe('photos.peeters.be had a problem. We keep trying.');
		expect(errorText(null, { provider: 'immich_link' })).toBe('');
		expect(warningText('downloads_disabled')).toBe('Photos come in reduced quality because the link doesn’t allow downloads.');
		expect(warningText('item_limit_reached', 10000, 'en')).toBe('Only the newest 10,000 photos are synced.');
	});

	it('words the rate limit of Sync now', () => {
		expect(retryText(90, 'en')).toBe('It was synced a moment ago. You can sync again in 2 minutes.');
		expect(retryText(30, 'en')).toBe('It was synced a moment ago. You can sync again in 30 seconds.');
		expect(retryText(undefined)).toBe('It was synced a moment ago. Try again in a few minutes.');
	});

	it('sorts sources needing a person first and links to the target', () => {
		const list = sortSources([source({ id: 'a', name: 'B' }), source({ id: 'b', name: 'A', status: 'error' }), source({ id: 'c', name: 'Z', status: 'needs_attention' })]);
		expect(list.map((s) => s.id)).toEqual(['c', 'b', 'a']);
		expect(targetHref(source().target)).toBe('/frames/f1?tab=library&album=a1');
		expect(targetHref({ kind: 'feed', feed_id: 'fd1' })).toBe('/feeds/fd1?tab=sources');
		expect(belongsTo(source(), { kind: 'album', frameId: 'f1', albumId: 'a1', name: '' })).toBe(true);
		expect(belongsTo(source(), { kind: 'feed', feedId: 'a1', name: '' })).toBe(false);
	});
});

describe('"Only in album mode" suggestion', () => {
	const album = { kind: 'album' as const, frameId: 'f1', albumId: 'a1', name: 'Garden' };
	const base = newWizard('album', ['immich_link']);
	const tested = (photos: number, videos = 0) => ({ ...base, test: { ok: true, photos, videos } });

	it('counts what the first import adds', () => {
		expect(firstImportCount(tested(214, 6))).toBe(220);
		expect(firstImportCount({ ...tested(214, 6), settings: { ...base.settings, includeVideos: false } })).toBe(214);
		expect(firstImportCount({ ...tested(214, 6), settings: { ...base.settings, history: 'last_n', historyCount: 50 } })).toBe(50);
		expect(firstImportCount({ ...tested(214, 6), settings: { ...base.settings, history: 'none' } })).toBe(0);
		expect(firstImportCount(base)).toBeNull();
	});

	it('suggests it above 200 photos for albums that are not album-only yet', () => {
		expect(suggestAlbumOnly(tested(214, 6), album)).toBe(true);
		expect(suggestAlbumOnly(tested(150, 50), album)).toBe(false);
		expect(suggestAlbumOnly(tested(500), { ...album, onlyInAlbumMode: true })).toBe(false);
		expect(suggestAlbumOnly(tested(500), { kind: 'feed', feedId: 'x', name: 'F' })).toBe(false);
		expect(suggestAlbumOnly(base, album)).toBe(false);
	});
});
