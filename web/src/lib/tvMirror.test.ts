import { describe, expect, it } from 'vitest';
import { mirrorScreen, mirrorSlide, mirrorUrl, type MirrorState } from './tvMirror';
import { clockSkew, remainingTransition, tileObjectPosition, videoPositionNow } from './tvstage';

const slide = (id: string, o: Partial<MirrorState['next'] & object> = {}) => ({
	placement_id: id, kind: 'photo' as const, url: `https://k.test/v1/media/signed/${id}`, mime: 'image/webp', sender_name: 'Anna',
	received_at: '2026-10-01T10:00:00Z', width: 1600, height: 1200, ...o
});

function state(o: Partial<MirrorState> = {}): MirrorState {
	return {
		type: 'mirror', server_time: '2026-10-25T12:00:10Z', frame_id: 'f', frame_name: 'Oma', online: true, screen: 'on', expires_at: '2026-10-26T00:00:00Z',
		settings: { interval_s: 20, transition: 'kenburns', transition_ms: 800, fill: true, background: 'blur' },
		showing: { placement_id: 'p1', slides: [slide('p1', { focal_point: { x: 0.2, y: 0.4 } })], started_at: '2026-10-25T12:00:08Z', transition: 'crossfade', transition_ms: 800, ken_burns: true, hold_ms: 20800 },
		...o
	};
}

describe('TV mirror', () => {
	it('tells showing, waiting, asleep and offline apart', () => {
		expect(mirrorScreen(null)).toBe('waiting');
		expect(mirrorScreen(state())).toBe('showing');
		expect(mirrorScreen(state({ showing: null }))).toBe('waiting');
		expect(mirrorScreen(state({ screen: 'sleeping' }))).toBe('asleep');
		expect(mirrorScreen(state({ screen: 'off' }))).toBe('asleep');
		expect(mirrorScreen(state({ online: false }))).toBe('offline');
	});

	it('translates server times into local ones and follows the frame', () => {
		// This TV's clock runs 5 s ahead of the server.
		const local = Date.parse('2026-10-25T12:00:15Z');
		const s = mirrorSlide(state(), local)!;
		expect(s.startedAt).toBe(Date.parse('2026-10-25T12:00:13Z'));
		expect(s.kenBurns).toHaveLength(6);
		expect(s.tiles[0]).toMatchObject({ id: 'p1', fit: 'fill', focal: { x: 0.2, y: 0.4 }, rotation: 0 });
		expect(s.key).toBe('p1@2026-10-25T12:00:08Z');
		const v = mirrorSlide(state({ showing: { ...state().showing!, ken_burns: false, video: { position_ms: 4000, playing: true, at: '2026-10-25T12:00:10Z' } } }), local)!;
		expect(v.kenBurns).toBeNull();
		expect(videoPositionNow(v.video!, local + 1000)).toBe(5000);
		expect(mirrorSlide(state({ showing: null }), local)).toBeNull();
	});

	it('connects next to the API', () => {
		expect(mirrorUrl('', 'https://k.test', 'abc')).toBe('wss://k.test/v1/tv/abc/mirror');
		expect(mirrorUrl('http://api.local:8110', 'https://k.test', 'a b')).toBe('ws://api.local:8110/v1/tv/a%20b/mirror');
	});
});

describe('TV stage helpers', () => {
	it('joins a transition where it is and centres fill crops on the focal point', () => {
		expect(remainingTransition(800, 0)).toBe(800);
		expect(remainingTransition(800, 300)).toBe(500);
		expect(remainingTransition(800, 5000)).toBe(0);
		expect(clockSkew('2026-10-25T12:00:00Z', Date.parse('2026-10-25T12:00:02Z'))).toBe(2000);
		expect(tileObjectPosition({ width: 1600, height: 1200, focal: null, rotation: 0 }, 16 / 9)).toBe('50% 50%');
		expect(tileObjectPosition({ width: 1600, height: 1200, focal: { x: 0.5, y: 0 }, rotation: 0 }, 16 / 9)).toBe('50.00% 0.00%');
		expect(tileObjectPosition({ width: 1600, height: 1200, focal: { x: 0.5, y: 0 }, rotation: 90 }, 16 / 9)).toBe('50% 50%');
	});
});
