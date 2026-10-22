import { describe, expect, it } from 'vitest';
import { base64UrlToBytes, pushTag, pushUrl, sameKey } from './pushPayload';

const SEND = '0192f0a0-0005-7000-8000-000000000001';
const FRAME = '0192f0a0-000f-7000-8000-000000000002';

describe('push payloads', () => {
	it('opens the most useful page', () => {
		expect(pushUrl({ key: 'delivered', ids: { send_id: SEND, frame_id: FRAME } })).toBe(`/history/${SEND}`);
		expect(pushUrl({ key: 'reaction', ids: { send_id: SEND, placement_id: 'x', frame_id: FRAME } })).toBe(`/history/${SEND}`);
		expect(pushUrl({ key: 'join_request', ids: { frame_id: FRAME, request_id: 'r' } })).toBe(`/frames/${FRAME}?tab=people`);
		expect(pushUrl({ key: 'membership', ids: { frame_id: FRAME } })).toBe(`/frames/${FRAME}`);
		expect(pushUrl({})).toBe('/history');
		// Never builds a URL from something that isn't an id.
		expect(pushUrl({ ids: { send_id: '../../evil' } })).toBe('/history');
	});

	it('collapses notifications about the same send', () => {
		expect(pushTag({ key: 'reaction', ids: { send_id: SEND } })).toBe(`reaction:${SEND}`);
		expect(pushTag({ type: 'join_request', ids: { request_id: 'r1' } })).toBe('join_request:r1');
	});

	it('decodes VAPID keys and compares them', () => {
		const bytes = base64UrlToBytes('BAEC_-8');
		expect([...bytes]).toEqual([4, 1, 2, 255, 239]);
		expect(sameKey(bytes.buffer, bytes)).toBe(true);
		expect(sameKey(new Uint8Array([1]).buffer, bytes)).toBe(false);
		expect(sameKey(null, bytes)).toBe(true);
	});
});
