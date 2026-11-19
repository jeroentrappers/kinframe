// Photo sources (docs/spec/photo-sources.md, README §5.14): link parsing, the add-source wizard's state, status
// texts and the defaults per target. Pure functions; the components only render what these return.
import type {
	ImmichAlbum,
	PhotoSource,
	PhotoSourceConnection,
	PhotoSourceCreate,
	PhotoSourceDeletePolicy,
	PhotoSourceErrorCode,
	PhotoSourceHistory,
	PhotoSourceProvider,
	PhotoSourceTestResult,
	PhotoSourceWarning
} from './api/client';
import { relativeTime, formatDateTime } from './format';
import { t, tn } from './i18n/index.svelte';

// ---------------------------------------------------------------- providers

export type Service = 'immich' | 'icloud' | 'google_photos';

/** Service names are product names: never translated. */
export const SERVICE_LABEL: Record<Service, string> = { immich: 'Immich', icloud: 'iCloud', google_photos: 'Google Photos' };

export function serviceOf(p: PhotoSourceProvider): Service {
	return p === 'icloud_link' ? 'icloud' : 'immich';
}

export function providerLabel(p: PhotoSourceProvider): string {
	return SERVICE_LABEL[serviceOf(p)];
}

/** The order the wizard offers providers in: Immich (link first, recommended), then iCloud. */
export const PROVIDER_ORDER: PhotoSourceProvider[] = ['immich_link', 'immich_api', 'icloud_link'];

/** Providers the server allows, in wizard order (discovery `photo_source_providers`). */
export function offeredProviders(allowed: readonly string[] | undefined | null): PhotoSourceProvider[] {
	const set = new Set(allowed ?? []);
	return PROVIDER_ORDER.filter((p) => set.has(p));
}

// ---------------------------------------------------------------- links

export type LinkProblem = 'empty' | 'invalid' | 'not_immich_link' | 'not_icloud_link' | 'icloud_photos_link' | 'insecure';

export type ImmichLink = { ok: true; url: string; host: string; apiBase: string; key?: string; slug?: string };
export type IcloudLink = { ok: true; url: string; token: string };
export type LinkError = { ok: false; problem: LinkProblem };

function parseUrl(input: string): URL | null {
	const s = input.trim();
	if (!s) return null;
	try {
		const u = new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(s) ? s : `https://${s}`);
		return u.protocol === 'https:' || u.protocol === 'http:' ? u : null;
	} catch {
		return null;
	}
}

/**
 * An Immich shared link: `https://<host>[/<prefix>]/share/<key>` or, on versions with custom slugs,
 * `https://<host>[/<prefix>]/s/<slug>` (photo-sources.md §6.3). Plain http is accepted for private addresses only
 * (the server enforces it; here it's the same rule as `isPrivateHost`).
 */
export function parseImmichLink(input: string): ImmichLink | LinkError {
	if (!input.trim()) return { ok: false, problem: 'empty' };
	const u = parseUrl(input);
	if (!u || (!u.hostname.includes('.') && !isPrivateHost(u.hostname))) return { ok: false, problem: 'invalid' };
	if (isIcloudHost(u.hostname)) return { ok: false, problem: 'not_immich_link' };
	const m = u.pathname.match(/^(.*?)\/(share|s)\/([A-Za-z0-9_-]{4,})\/?$/);
	if (!m) return { ok: false, problem: 'not_immich_link' };
	if (u.protocol === 'http:' && !isPrivateHost(u.hostname)) return { ok: false, problem: 'insecure' };
	const prefix = m[1] ?? '';
	const apiBase = `${u.origin}${prefix}/api`;
	return { ok: true, url: u.toString(), host: u.host, apiBase, ...(m[2] === 'share' ? { key: m[3]! } : { slug: m[3]! }) };
}

function isIcloudHost(host: string): boolean {
	const h = host.toLowerCase();
	return h === 'icloud.com' || h.endsWith('.icloud.com');
}

/**
 * An iCloud shared album's public website: `https://www.icloud.com/sharedalbum/#<token>`, also without `www` and
 * with a language segment (`/sharedalbum/nl-nl/#<token>`). iCloud Links for single photos (`/photos/#…`) are a
 * different service (photo-sources.md §6.5).
 */
export function parseIcloudLink(input: string): IcloudLink | LinkError {
	if (!input.trim()) return { ok: false, problem: 'empty' };
	const u = parseUrl(input);
	if (!u) return { ok: false, problem: 'invalid' };
	if (!isIcloudHost(u.hostname)) return { ok: false, problem: 'not_icloud_link' };
	const path = u.pathname.replace(/\/+$/, '');
	if (/^\/photos$/i.test(path) || /^\/photos\//i.test(u.pathname)) return { ok: false, problem: 'icloud_photos_link' };
	if (!/^\/sharedalbum(\/[a-z]{2}(-[a-z]{2})?)?$/i.test(path)) return { ok: false, problem: 'not_icloud_link' };
	const token = u.hash.replace(/^#/, '').split(/[;?&]/)[0] ?? '';
	if (!/^[A-Za-z0-9]{6,}$/.test(token)) return { ok: false, problem: 'not_icloud_link' };
	if (u.protocol !== 'https:') return { ok: false, problem: 'insecure' };
	return { ok: true, url: `https://www.icloud.com${u.pathname}#${token}`, token };
}

/** Which provider a pasted link belongs to, when that's clear from the link itself. */
export function guessProvider(input: string): 'immich_link' | 'icloud_link' | null {
	if (parseIcloudLink(input).ok) return 'icloud_link';
	if (parseImmichLink(input).ok) return 'immich_link';
	return null;
}

/** An Immich server address for `immich_api`: `https://photos.example.org` (an `/api` suffix is tolerated). */
export function normalizeImmichServer(input: string): { ok: true; url: string; host: string } | LinkError {
	if (!input.trim()) return { ok: false, problem: 'empty' };
	const u = parseUrl(input);
	if (!u || (!u.hostname.includes('.') && !isPrivateHost(u.hostname))) return { ok: false, problem: 'invalid' };
	if (u.protocol === 'http:' && !isPrivateHost(u.hostname)) return { ok: false, problem: 'insecure' };
	const path = u.pathname.replace(/\/+$/, '').replace(/\/api$/i, '');
	return { ok: true, url: `${u.origin}${path}`, host: u.host };
}

/** Addresses only a home network reaches (RFC 1918, CGNAT, loopback, link-local, ULA, `.local`/`.lan`/`.home.arpa`). */
export function isPrivateHost(hostname: string): boolean {
	const h = hostname.toLowerCase().replace(/^\[|\]$/g, '');
	if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.lan') || h.endsWith('.home.arpa') || h.endsWith('.internal')) return true;
	if (!h.includes('.') && !h.includes(':')) return true; // a bare LAN name ("nas")
	const v4 = h.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
	if (v4) {
		const [a, b] = [Number(v4[1]), Number(v4[2])];
		return a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254) || (a === 100 && b >= 64 && b <= 127);
	}
	if (h.includes(':')) return h === '::1' || /^f[cd][0-9a-f]{2}:/.test(h) || /^fe[89ab][0-9a-f]:/.test(h);
	return false;
}

/** The host a link or server address points at, for texts like "Can't reach photos.peeters.be". */
export function hostOf(url: string | null | undefined): string | null {
	if (!url) return null;
	try {
		return new URL(url.replace(/…$/, '')).host || null;
	} catch {
		return null;
	}
}

/** The text for a link problem, or null when the link is fine. */
export function linkProblemText(problem: LinkProblem | null, privateNetworks = true): string | null {
	switch (problem) {
		case null:
		case 'empty':
			return null;
		case 'invalid':
			return t('sources.link.invalid');
		case 'not_immich_link':
			return t('sources.link.notImmich');
		case 'not_icloud_link':
			return t('sources.link.notIcloud');
		case 'icloud_photos_link':
			return t('sources.err.unsupported_link');
		case 'insecure':
			return privateNetworks ? t('sources.link.insecure') : t('sources.privateBlocked');
	}
}

// ---------------------------------------------------------------- targets and defaults

export type SourceTarget =
	| { kind: 'album'; frameId: string; albumId: string; name: string; /** The album already plays only in album mode. */ onlyInAlbumMode?: boolean }
	| { kind: 'feed'; feedId: string; name: string };

export interface SourceSettings {
	history: PhotoSourceHistory;
	historyCount: number;
	onUpstreamDelete: PhotoSourceDeletePolicy;
	includeVideos: boolean;
	notifyReactions: boolean;
}

export const SOURCE_HISTORY_MAX = 10_000;
export const FEED_HISTORY_DEFAULT = 20;

/** Albums mirror the outside album (everything, removals too); feeds start with the last 20 and keep (§8.3, §9). */
export function defaultSettings(kind: SourceTarget['kind']): SourceSettings {
	return kind === 'album'
		? { history: 'all', historyCount: 50, onUpstreamDelete: 'mirror', includeVideos: true, notifyReactions: true }
		: { history: 'last_n', historyCount: FEED_HISTORY_DEFAULT, onUpstreamDelete: 'keep', includeVideos: true, notifyReactions: true };
}

export function clampSourceHistory(n: number): number {
	if (!Number.isFinite(n)) return FEED_HISTORY_DEFAULT;
	return Math.min(SOURCE_HISTORY_MAX, Math.max(1, Math.round(n)));
}

// ---------------------------------------------------------------- wizard

export type WizardStep = 'provider' | 'connect' | 'album' | 'settings';

export interface WizardState {
	step: WizardStep;
	provider: PhotoSourceProvider | null;
	url: string;
	password: string;
	serverUrl: string;
	apiKey: string;
	albumId: string | null;
	albums: ImmichAlbum[];
	/** The last successful test, for the connection as it was then (`testedFor`). */
	test: PhotoSourceTestResult | null;
	testedFor: string | null;
	name: string;
	settings: SourceSettings;
	/** Turn the album's "Only in album mode" on together with the source (suggested for big first imports). */
	albumOnly: boolean;
}

export function newWizard(kind: SourceTarget['kind'], providers: PhotoSourceProvider[]): WizardState {
	const only = providers.length === 1 ? providers[0]! : null;
	return {
		step: only ? 'connect' : 'provider',
		provider: only,
		url: '',
		password: '',
		serverUrl: '',
		apiKey: '',
		albumId: null,
		albums: [],
		test: null,
		testedFor: null,
		name: '',
		settings: defaultSettings(kind),
		albumOnly: false
	};
}

export function chooseProvider(s: WizardState, provider: PhotoSourceProvider): WizardState {
	return { ...s, provider, step: 'connect', test: null, testedFor: null, albums: [], albumId: null };
}

/** The steps the wizard shows for its provider (an API key adds "pick an album"). */
export function wizardSteps(s: Pick<WizardState, 'provider'>, providers: number): WizardStep[] {
	const steps: WizardStep[] = providers > 1 ? ['provider', 'connect'] : ['connect'];
	if (s.provider === 'immich_api') steps.push('album');
	steps.push('settings');
	return steps;
}

/** What's wrong with the connection fields as typed, or null when they can be tested. */
export function connectionProblem(s: WizardState): LinkProblem | 'no_key' | 'no_album' | null {
	switch (s.provider) {
		case 'immich_link': {
			const r = parseImmichLink(s.url);
			return r.ok ? null : r.problem;
		}
		case 'icloud_link': {
			const r = parseIcloudLink(s.url);
			return r.ok ? null : r.problem;
		}
		case 'immich_api': {
			const r = normalizeImmichServer(s.serverUrl);
			if (!r.ok) return r.problem;
			return s.apiKey.trim().length >= 8 ? null : 'no_key';
		}
		default:
			return 'empty';
	}
}

/** The `PhotoSourceConnection` for test and create; `withAlbum` adds the picked Immich album. */
export function connectionBody(s: WizardState, withAlbum = true): PhotoSourceConnection | null {
	if (connectionProblem(s)) return null;
	switch (s.provider) {
		case 'immich_link': {
			const r = parseImmichLink(s.url) as ImmichLink;
			return { provider: 'immich_link', url: r.url, ...(s.password ? { password: s.password } : {}) };
		}
		case 'icloud_link':
			return { provider: 'icloud_link', url: (parseIcloudLink(s.url) as IcloudLink).url };
		case 'immich_api': {
			const r = normalizeImmichServer(s.serverUrl) as { url: string };
			return {
				provider: 'immich_api',
				server_url: r.url,
				api_key: s.apiKey.trim(),
				...(withAlbum && s.albumId ? { immich_album_id: s.albumId } : {})
			};
		}
		default:
			return null;
	}
}

/** A fingerprint of the connection fields: a test is only valid for the fields it was run with. */
export function connectionKey(s: WizardState): string {
	return JSON.stringify(connectionBody(s) ?? null);
}

export function withTest(s: WizardState, test: PhotoSourceTestResult): WizardState {
	const ok = test.ok === true;
	const albums = test.albums ?? s.albums;
	return {
		...s,
		test,
		testedFor: ok ? connectionKey(s) : null,
		albums,
		name: s.name || (ok && test.album_name ? test.album_name.slice(0, 60) : s.name)
	};
}

/** Whether the connection was tested successfully as it stands now. */
export function isTested(s: WizardState): boolean {
	return s.test?.ok === true && s.testedFor === connectionKey(s);
}

export function canContinue(s: WizardState): boolean {
	switch (s.step) {
		case 'provider':
			return s.provider !== null;
		case 'connect':
			// API keys are checked by listing albums (the next step); links by a test.
			return s.provider === 'immich_api' ? connectionProblem(s) === null : isTested(s);
		case 'album':
			return s.albumId !== null && isTested(s);
		case 'settings':
			return isTested(s);
	}
}

export function nextStep(s: WizardState, providers: number): WizardState {
	const steps = wizardSteps(s, providers);
	const i = steps.indexOf(s.step);
	return i >= 0 && i < steps.length - 1 ? { ...s, step: steps[i + 1]! } : s;
}

export function prevStep(s: WizardState, providers: number): WizardState {
	const steps = wizardSteps(s, providers);
	const i = steps.indexOf(s.step);
	return i > 0 ? { ...s, step: steps[i - 1]! } : s;
}

export function pickAlbum(s: WizardState, albumId: string): WizardState {
	const album = s.albums.find((a) => a.id === albumId);
	return { ...s, albumId, test: null, testedFor: null, name: album ? album.name.slice(0, 60) : s.name };
}

/** The body of `createPhotoSource`. */
export function createBody(s: WizardState, target: SourceTarget): PhotoSourceCreate | null {
	const conn = connectionBody(s);
	if (!conn) return null;
	const st = s.settings;
	return {
		...conn,
		...(target.kind === 'album' ? { album_id: target.albumId } : { feed_id: target.feedId }),
		...(s.name.trim() ? { name: s.name.trim().slice(0, 60) } : {}),
		history: st.history,
		...(st.history === 'last_n' ? { history_count: clampSourceHistory(st.historyCount) } : {}),
		on_upstream_delete: st.onUpstreamDelete,
		include_videos: st.includeVideos,
		notify_reactions: st.notifyReactions
	};
}

/** Above this many photos in the first import, the wizard suggests "Only in album mode" (§9.1). */
export const ALBUM_ONLY_SUGGEST_ABOVE = 200;

/** How many photos the first run will bring in, from the test's counts and the history choice; null when unknown. */
export function firstImportCount(s: Pick<WizardState, 'test' | 'settings'>): number | null {
	const test = s.test;
	if (!test?.ok || (test.photos == null && test.videos == null)) return null;
	const total = (test.photos ?? 0) + (s.settings.includeVideos ? (test.videos ?? 0) : 0);
	switch (s.settings.history) {
		case 'none':
			return 0;
		case 'last_n':
			return Math.min(total, clampSourceHistory(s.settings.historyCount));
		case 'all':
			return total;
	}
}

/** Whether to suggest "Only in album mode": an album target whose first import adds more than 200 photos. */
export function suggestAlbumOnly(s: Pick<WizardState, 'test' | 'settings'>, target: SourceTarget): boolean {
	if (target.kind !== 'album' || target.onlyInAlbumMode) return false;
	return (firstImportCount(s) ?? 0) > ALBUM_ONLY_SUGGEST_ABOVE;
}

// ---------------------------------------------------------------- status

export type Tone = 'ok' | 'running' | 'paused' | 'error' | 'attention';

export interface StatusView {
	tone: Tone;
	/** Short headline: "Synced 2 minutes ago", "Paused", "Needs your attention". */
	label: string;
	/** The human error text (error/needs_attention, or a retried failure on an active source). */
	detail: string | null;
	/** "Next sync in 28 minutes", when one is planned. */
	next: string | null;
}

type ErrorSource = Pick<PhotoSource, 'provider' | 'url_hint' | 'server_url' | 'owner' | 'pending_removals' | 'error_since'>;

/** The text for an error code (photo-sources.md §13), in the reader's language. */
export function errorText(code: PhotoSourceErrorCode | null | undefined, src: Partial<ErrorSource> & { provider: PhotoSourceProvider }): string {
	const service = providerLabel(src.provider);
	const host = hostOf(src.url_hint) ?? hostOf(src.server_url) ?? service;
	switch (code) {
		case null:
		case undefined:
			return '';
		case 'unreachable':
			return t('sources.err.unreachable', { host });
		case 'tls_error':
			return t('sources.err.tls_error', { host });
		case 'private_address_blocked':
			return t('sources.err.private_address_blocked');
		case 'auth_failed':
			return src.provider === 'immich_api' ? t('sources.err.auth_failed', { service }) : t('sources.err.auth_failed_password', { service });
		case 'password_required':
			return t('sources.err.password_required');
		case 'permission_missing':
			return t('sources.err.permission_missing');
		case 'link_not_found':
			return src.provider === 'icloud_link' ? t('sources.err.link_not_found_icloud') : t('sources.err.link_not_found');
		case 'link_expired':
			return t('sources.err.link_expired', { service });
		case 'album_not_found':
			return t('sources.err.album_not_found', { service });
		case 'unsupported_link':
			return t('sources.err.unsupported_link');
		case 'too_large':
			return t('sources.err.too_large');
		case 'format_changed':
			return src.provider === 'icloud_link' ? t('sources.err.format_changed_icloud') : t('sources.err.format_changed', { service });
		case 'rate_limited_upstream':
			return t('sources.err.rate_limited_upstream');
		case 'upstream_error':
			return t('sources.err.upstream_error', { host });
		case 'owner_lost_access':
			return src.owner?.display_name ? t('sources.err.owner_lost_access', { name: src.owner.display_name }) : t('sources.err.owner_gone');
		case 'credentials_unreadable':
			return t('sources.err.credentials_unreadable');
		case 'mass_removal':
			return tn('sources.err.mass_removal', src.pending_removals ?? 0, { service });
		default:
			return t('sources.err.unknown');
	}
}

export function warningText(w: PhotoSourceWarning, limit = 10_000, locale = 'en'): string {
	switch (w) {
		case 'downloads_disabled':
			return t('sources.warn.downloads_disabled');
		case 'metadata_hidden':
			return t('sources.warn.metadata_hidden');
		case 'item_limit_reached':
			return t('sources.warn.item_limit_reached', { count: new Intl.NumberFormat(locale).format(limit) });
	}
}

/** The status line of a source card (photo-sources.md §5.1 "UI"). */
export function statusView(src: PhotoSource, locale = 'en', now: Date = new Date()): StatusView {
	const service = providerLabel(src.provider);
	const next = src.status !== 'paused' && src.next_sync_at && !src.running ? t('sources.nextSync', { when: relativeTime(src.next_sync_at, locale, now) }) : null;
	const pending = src.counts?.pending ?? 0;
	if (src.status === 'paused') return { tone: 'paused', label: t('sources.st.paused'), detail: null, next: null };
	if (src.status === 'needs_attention')
		return { tone: 'attention', label: t('sources.st.attention'), detail: errorText(src.last_error_code, src) || t('sources.err.unknown'), next: null };
	if (src.status === 'error') {
		const since = src.error_since ?? src.last_attempt_at;
		return {
			tone: 'error',
			label: since ? t('sources.st.errorSince', { when: formatDateTime(since, locale) }) : t('sources.st.error'),
			detail: errorText(src.last_error_code, src) || t('sources.err.unknown'),
			next
		};
	}
	if (src.running) {
		const imported = src.counts?.imported ?? 0;
		const label = pending > 0 ? t('sources.st.importing', { done: num(imported, locale), total: num(imported + pending, locale) }) : t('sources.st.syncing', { service });
		return { tone: 'running', label, detail: null, next: null };
	}
	const detail = src.last_error_code ? errorText(src.last_error_code, src) : null;
	if (pending > 0) {
		const imported = src.counts?.imported ?? 0;
		return { tone: 'ok', label: t('sources.st.catchingUp', { done: num(imported, locale), total: num(imported + pending, locale) }), detail, next };
	}
	if (!src.last_synced_at) return { tone: 'ok', label: t('sources.st.firstSync'), detail, next };
	return { tone: 'ok', label: t('sources.st.synced', { service, when: relativeTime(src.last_synced_at, locale, now) }), detail, next };
}

const num = (n: number, locale: string) => new Intl.NumberFormat(locale).format(n);

/** The one-tap fix offered with a status (amber "with the fix as a button"). */
export type Fix = 'reconnect' | 'take_over' | 'confirm_removals' | null;

export function fixFor(src: Pick<PhotoSource, 'status' | 'last_error_code' | 'pending_removals'>): Fix {
	if (src.status !== 'needs_attention' && src.status !== 'error') return null;
	switch (src.last_error_code) {
		case 'owner_lost_access':
			return 'take_over';
		case 'mass_removal':
			return (src.pending_removals ?? 0) > 0 ? 'confirm_removals' : null;
		case 'auth_failed':
		case 'password_required':
		case 'permission_missing':
		case 'link_not_found':
		case 'link_expired':
		case 'album_not_found':
		case 'credentials_unreadable':
		case 'tls_error':
		case 'private_address_blocked':
		case 'unsupported_link':
		case 'too_large':
			return 'reconnect';
		default:
			return null;
	}
}

/** Where a source sends its photos: "Garden · Oma's frame" or "Peeters kids". */
export function targetLabel(src: Pick<PhotoSource, 'target'>): string {
	const tg = src.target;
	if (tg.kind === 'feed') return tg.feed_name ?? '';
	return [tg.album_name, tg.frame_name].filter(Boolean).join(' · ');
}

/** The page that shows a source's target (its album in the frame library, or the feed's Sources tab). */
export function targetHref(tg: PhotoSource['target']): string | null {
	if (tg.kind === 'feed' && tg.feed_id) return `/feeds/${tg.feed_id}?tab=sources`;
	if (tg.kind === 'album' && tg.frame_id && tg.album_id) return `/frames/${tg.frame_id}?tab=library&album=${tg.album_id}`;
	return null;
}

/** The key of the problem for a test that didn't pass: the server's code, or a generic one. */
export function testFailureText(test: PhotoSourceTestResult, provider: PhotoSourceProvider): string {
	return errorText(test.error_code ?? null, { provider }) || t('sources.testFailed');
}

/** Sort sources needing a person first, then by name. */
export function sortSources(list: PhotoSource[]): PhotoSource[] {
	const rank = (s: PhotoSource) => (s.status === 'needs_attention' ? 0 : s.status === 'error' ? 1 : 2);
	return [...list].sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name));
}

/** Replace or add a source by id (live updates). */
export function upsertSource(list: PhotoSource[], src: PhotoSource): PhotoSource[] {
	const i = list.findIndex((x) => x.id === src.id);
	if (i < 0) return [...list, src];
	const out = [...list];
	out[i] = src;
	return out;
}

/** Whether a live `photo_source.updated` belongs to this target. */
export function belongsTo(src: Pick<PhotoSource, 'target'>, target: SourceTarget): boolean {
	return target.kind === 'album' ? src.target.album_id === target.albumId : src.target.feed_id === target.feedId;
}

/** Seconds until "Sync now" may be tried again, from a 429's Retry-After. */
export function retryText(seconds: number | undefined, locale = 'en'): string {
	if (!seconds) return t('sources.syncTooSoon');
	const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'always' });
	const when = seconds < 90 ? rtf.format(Math.ceil(seconds), 'second') : rtf.format(Math.ceil(seconds / 60), 'minute');
	return t('sources.syncRetry', { when });
}

