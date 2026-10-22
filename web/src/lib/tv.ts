// Show a frame's library on a TV (product spec 4.11). Pure helpers shared by the cast
// controller (Google Cast / Presentation / Remote Playback) and the /tv page.
import type { components } from './api/schema';

export type TvSlide = components['schemas']['TvSlide'];
export type TvSlideshow = components['schemas']['TvSlideshow'];
export type TvLink = components['schemas']['TvLink'];
export type TvLinkCreate = components['schemas']['TvLinkCreate'];

/** Slide intervals offered in the controls, in seconds. */
export const TV_INTERVALS = [5, 10, 15, 30, 60] as const;
export const DEFAULT_TV_INTERVAL = 10;

/** What to show: a selection, one album or the whole visible library. */
export type TvScope = { kind: 'selection'; ids: string[] } | { kind: 'album'; albumId: string; name: string } | { kind: 'all' };

export function linkBody(scope: TvScope, interval: number, purpose: 'tv' | 'cast'): TvLinkCreate {
	const body: TvLinkCreate = { interval_seconds: interval, purpose, expires_in_hours: purpose === 'cast' ? 6 : 12 };
	if (scope.kind === 'selection') body.placement_ids = scope.ids.slice(0, 500);
	if (scope.kind === 'album') body.album_id = scope.albumId;
	return body;
}

/** Wrap-around step through n slides. */
export function step(index: number, n: number, dir: 1 | -1): number {
	if (n <= 0) return 0;
	return (((index + dir) % n) + n) % n;
}

/** How long a slide stays up: photos use the interval; videos play to the end (capped at 5 min). */
export function dwellMs(slide: TvSlide | undefined, intervalS: number): number {
	if (slide?.kind === 'video' && slide.duration_ms) return Math.min(slide.duration_ms + 500, 5 * 60_000);
	return Math.max(3, intervalS) * 1000;
}

/** Signed media URLs expire; refetch the slideshow after `refresh_after` (and keep the place). */
export function needsRefresh(show: Pick<TvSlideshow, 'refresh_after'>, now = Date.now()): boolean {
	const at = Date.parse(show.refresh_after);
	return Number.isFinite(at) && now >= at;
}

/** After a refetch, stay on the same placement if it is still there. */
export function keepPlace(prev: TvSlide | undefined, next: TvSlide[], fallbackIndex: number): number {
	if (prev) {
		const i = next.findIndex((s) => s.placement_id === prev.placement_id);
		if (i >= 0) return i;
	}
	return next.length ? Math.min(fallbackIndex, next.length - 1) : 0;
}

export function tvPageUrl(origin: string, frameId: string, token: string): string {
	return `${origin}/tv/${frameId}?t=${encodeURIComponent(token)}`;
}

/** The second line under a caption: "Oma · 12 May 2024". */
export function slideByline(s: TvSlide, tag: string): string {
	const when = s.captured_at ?? s.received_at;
	const d = when ? new Date(when) : null;
	const date = d && !Number.isNaN(d.getTime()) ? new Intl.DateTimeFormat(tag, { day: 'numeric', month: 'long', year: 'numeric' }).format(d) : '';
	return [s.sender_name, date].filter(Boolean).join(' · ');
}

// ---------------------------------------------------------------- Google Cast

/** Default Media Receiver metadata types (chrome.cast.media.MetadataType). */
const GENERIC = 0;
const PHOTO = 4;

/** A plain description of the Cast MediaInfo for a slide (built into SDK objects by the controller). */
export function castMedia(s: TvSlide, frameName: string) {
	return {
		contentId: s.url,
		contentType: s.mime,
		streamType: 'BUFFERED' as const,
		metadataType: s.kind === 'video' ? GENERIC : PHOTO,
		title: s.caption || frameName,
		subtitle: s.sender_name,
		image: s.kind === 'video' ? (s.poster_url ?? s.thumb_url ?? null) : (s.thumb_url ?? null),
		width: s.width,
		height: s.height,
		creationDateTime: s.captured_at ?? undefined
	};
}

/** Messages between the sender and the TV page when it is presented (Presentation API). */
export type TvCommand =
	| { cmd: 'next' }
	| { cmd: 'prev' }
	| { cmd: 'play' }
	| { cmd: 'pause' }
	| { cmd: 'interval'; seconds: number }
	| { cmd: 'stop' };
export interface TvState {
	type: 'state';
	index: number;
	total: number;
	playing: boolean;
	interval: number;
	caption: string | null;
	placement_id: string | null;
}

export function parseCommand(data: unknown): TvCommand | null {
	let v: unknown = data;
	if (typeof data === 'string') {
		try {
			v = JSON.parse(data);
		} catch {
			return null;
		}
	}
	if (!v || typeof v !== 'object') return null;
	const c = v as { cmd?: unknown; seconds?: unknown };
	switch (c.cmd) {
		case 'next':
		case 'prev':
		case 'play':
		case 'pause':
		case 'stop':
			return { cmd: c.cmd };
		case 'interval':
			return typeof c.seconds === 'number' && c.seconds >= 3 && c.seconds <= 600 ? { cmd: 'interval', seconds: Math.round(c.seconds) } : null;
		default:
			return null;
	}
}

/** Which ways to reach a TV this browser has. Cast needs a Chromium browser (the SDK only works there). */
export interface TvSupport {
	cast: boolean;
	presentation: boolean;
	remotePlayback: boolean;
}

export function detectSupport(w: { navigator?: Navigator; PresentationRequest?: unknown; HTMLMediaElement?: unknown; chrome?: unknown } | undefined): TvSupport {
	if (!w?.navigator) return { cast: false, presentation: false, remotePlayback: false };
	const ua = w.navigator.userAgent ?? '';
	const brands = (w.navigator as Navigator & { userAgentData?: { brands?: { brand: string }[] } }).userAgentData?.brands ?? [];
	const chromium = brands.some((b) => /Chromium|Google Chrome|Microsoft Edge/.test(b.brand)) || /Chrome\/|Chromium\//.test(ua);
	// iOS browsers are WebKit underneath ("CriOS"), where the Cast web SDK does nothing.
	const mobileIos = /iPhone|iPad|CriOS/.test(ua);
	const media = w.HTMLMediaElement as { prototype?: object } | undefined;
	return {
		cast: chromium && !mobileIos,
		presentation: typeof w.PresentationRequest === 'function',
		remotePlayback: !!media?.prototype && 'remote' in media.prototype
	};
}
