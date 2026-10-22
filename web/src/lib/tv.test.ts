import { describe, expect, it } from 'vitest';
import { castMedia, detectSupport, dwellMs, keepPlace, linkBody, needsRefresh, parseCommand, slideByline, step, tvPageUrl, type TvSlide } from './tv';

const slide = (id: string, p: Partial<TvSlide> = {}): TvSlide => ({
	placement_id: id,
	kind: 'photo',
	url: `https://k.example/v1/media/signed/${id}?exp=1&sig=x`,
	mime: 'image/webp',
	sender_name: 'Oma',
	received_at: '2026-10-20T10:00:00Z',
	width: 2560,
	height: 1707,
	...p
});

describe('tv helpers', () => {
	it('builds link bodies per scope', () => {
		expect(linkBody({ kind: 'all' }, 10, 'tv')).toEqual({ interval_seconds: 10, purpose: 'tv', expires_in_hours: 12 });
		expect(linkBody({ kind: 'album', albumId: 'a1', name: 'Zomer' }, 15, 'cast')).toEqual({
			interval_seconds: 15,
			purpose: 'cast',
			expires_in_hours: 6,
			album_id: 'a1'
		});
		const many = Array.from({ length: 600 }, (_, i) => `p${i}`);
		expect(linkBody({ kind: 'selection', ids: many }, 5, 'cast').placement_ids).toHaveLength(500);
	});

	it('steps with wrap-around', () => {
		expect(step(0, 3, 1)).toBe(1);
		expect(step(2, 3, 1)).toBe(0);
		expect(step(0, 3, -1)).toBe(2);
		expect(step(0, 0, 1)).toBe(0);
	});

	it('lets videos play out and photos use the interval', () => {
		expect(dwellMs(slide('a'), 10)).toBe(10_000);
		expect(dwellMs(slide('a'), 1)).toBe(3_000);
		expect(dwellMs(slide('v', { kind: 'video', duration_ms: 12_000 }), 10)).toBe(12_500);
		expect(dwellMs(slide('v', { kind: 'video', duration_ms: 3_600_000 }), 10)).toBe(300_000);
	});

	it('refreshes signed URLs in time and keeps the place', () => {
		expect(needsRefresh({ refresh_after: '2026-10-22T10:00:00Z' }, Date.parse('2026-10-22T09:59:00Z'))).toBe(false);
		expect(needsRefresh({ refresh_after: '2026-10-22T10:00:00Z' }, Date.parse('2026-10-22T10:00:01Z'))).toBe(true);
		const next = [slide('x'), slide('b'), slide('c')];
		expect(keepPlace(slide('b'), next, 0)).toBe(1);
		expect(keepPlace(slide('gone'), next, 7)).toBe(2);
		expect(keepPlace(undefined, [], 3)).toBe(0);
	});

	it('describes Cast media for the Default Media Receiver', () => {
		const photo = castMedia(slide('a', { caption: 'Strand', thumb_url: 'https://k.example/t' }), 'Oma’s frame');
		expect(photo).toMatchObject({ contentId: slide('a').url, contentType: 'image/webp', metadataType: 4, title: 'Strand', subtitle: 'Oma', image: 'https://k.example/t' });
		const video = castMedia(slide('v', { kind: 'video', mime: 'video/mp4', poster_url: 'https://k.example/p' }), 'Oma’s frame');
		expect(video).toMatchObject({ contentType: 'video/mp4', metadataType: 0, title: 'Oma’s frame', image: 'https://k.example/p' });
	});

	it('parses presentation commands defensively', () => {
		expect(parseCommand('{"cmd":"next"}')).toEqual({ cmd: 'next' });
		expect(parseCommand({ cmd: 'interval', seconds: 15 })).toEqual({ cmd: 'interval', seconds: 15 });
		expect(parseCommand({ cmd: 'interval', seconds: 1 })).toBeNull();
		expect(parseCommand('nope')).toBeNull();
		expect(parseCommand({ cmd: 'rm -rf' })).toBeNull();
	});

	it('detects ways to reach a TV', () => {
		const chrome = { navigator: { userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/141.0 Safari/537.36' } as Navigator, PresentationRequest: function () {}, HTMLMediaElement: { prototype: { remote: {} } } };
		expect(detectSupport(chrome)).toEqual({ cast: true, presentation: true, remotePlayback: true });
		const firefox = { navigator: { userAgent: 'Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0' } as Navigator, HTMLMediaElement: { prototype: {} } };
		expect(detectSupport(firefox)).toEqual({ cast: false, presentation: false, remotePlayback: false });
		const iosChrome = { navigator: { userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) CriOS/141.0 Mobile Safari/604.1' } as Navigator };
		expect(detectSupport(iosChrome).cast).toBe(false);
		expect(detectSupport(undefined).cast).toBe(false);
	});

	it('formats the TV page URL and byline', () => {
		expect(tvPageUrl('https://k.example', 'f1', 'a b')).toBe('https://k.example/tv/f1?t=a%20b');
		expect(slideByline(slide('a', { captured_at: '2024-05-12T10:00:00Z' }), 'en-GB')).toBe('Oma · 12 May 2024');
	});
});
