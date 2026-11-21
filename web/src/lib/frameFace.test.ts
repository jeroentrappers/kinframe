import { describe, expect, it } from 'vitest';
import type { FrameSummary } from './api/client';
import { framePhotoUrl, needsRefreshOnStatus, reactedToday, showingNow } from './frameFace';

type NowShowing = NonNullable<FrameSummary['now_showing']>;
const ns = (source: NowShowing['source'], urls: Partial<Pick<NowShowing, 'thumb_url' | 'display_url'>> = { thumb_url: 'https://x/t', display_url: 'https://x/d' }): NowShowing => ({
	placement_id: 'pl',
	item_id: 'it',
	source,
	since: '2026-11-21T08:00:00Z',
	...urls
});

describe('framePhotoUrl', () => {
	it('uses the thumbnail on cards and the display rendition on the large card', () => {
		const f = { now_showing: ns('screen') };
		expect(framePhotoUrl(f)).toBe('https://x/t');
		expect(framePhotoUrl(f, true)).toBe('https://x/d');
	});
	it('falls back to the other rendition', () => {
		expect(framePhotoUrl({ now_showing: ns('last_placed', { display_url: 'https://x/d' }) })).toBe('https://x/d');
		expect(framePhotoUrl({ now_showing: ns('last_placed', { thumb_url: 'https://x/t', display_url: null }) }, true)).toBe('https://x/t');
		expect(framePhotoUrl({ now_showing: ns('last_placed', {}) })).toBeNull();
	});
	it('has none without now_showing (keep the placeholder)', () => {
		expect(framePhotoUrl({})).toBeNull();
		expect(framePhotoUrl({ now_showing: null })).toBeNull();
		expect(framePhotoUrl(undefined)).toBeNull();
	});
});

describe('showingNow', () => {
	it('only for an online frame reporting its screen', () => {
		expect(showingNow({ online: true, now_showing: ns('screen') })).toBe(true);
		expect(showingNow({ online: true, now_showing: ns('last_placed') })).toBe(false);
		expect(showingNow({ online: false, now_showing: ns('screen') })).toBe(false);
		expect(showingNow({ online: true })).toBe(false);
	});
});

describe('reactedToday', () => {
	it('counts the photos reacted to', () => {
		expect(reactedToday({ reactions_today: { count: 5, item_count: 3 } })).toBe(3);
		expect(reactedToday({ reactions_today: { count: 1, item_count: 1 } })).toBe(1);
	});
	it('is hidden when absent or zero', () => {
		expect(reactedToday({})).toBeNull();
		expect(reactedToday({ reactions_today: { count: 0, item_count: 0 } })).toBeNull();
	});
});

describe('needsRefreshOnStatus', () => {
	it('when a frame showing a slide goes offline, or one comes online', () => {
		expect(needsRefreshOnStatus({ online: true, now_showing: ns('screen') }, false)).toBe(true);
		expect(needsRefreshOnStatus({ online: true, now_showing: ns('last_placed') }, false)).toBe(false);
		expect(needsRefreshOnStatus({ online: false, now_showing: ns('last_placed') }, true)).toBe(true);
	});
	it('not when nothing changed or the frame is unknown', () => {
		expect(needsRefreshOnStatus({ online: true, now_showing: ns('screen') }, true)).toBe(false);
		expect(needsRefreshOnStatus(undefined, false)).toBe(false);
	});
});
