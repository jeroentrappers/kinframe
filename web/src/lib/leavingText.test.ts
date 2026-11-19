import { describe, expect, it } from 'vitest';
import { dayMonth, remoteFrameName, resultMessage } from './leavingText';
import { pushUrl } from './pushPayload';
import { placementCredit, senderLabel } from './sender';

const FRAME = '0192f0a0-0000-7000-8000-000000000001';
const FEED = '0192f0a0-0000-7000-8000-000000000002';
const REQ = '0192f0a0-0000-7000-8000-000000000003';

describe('leaving texts', () => {
	it('says what happened after leaving or taking away', () => {
		const until = '2026-12-19T12:00:00Z';
		const msg = resultMessage(
			{ taking_id: 't', photos_taken_away: 214, comments_deleted: 2, until, frames: [], remote_frames: 1, left_frame_ids: ['a'] },
			() => ['Oma’s frame'],
			'Janssens'
		);
		expect(msg).toContain('You left Janssens.');
		expect(msg).toContain('214 photos taken away. Frames keep them in their trash until 19 December');
		expect(msg).toContain('Frames on other servers told to remove them: 1.');
		expect(msg).toContain('Comments deleted: 2.');
		expect(msg).toContain('You also left Oma’s frame.');
		expect(resultMessage({ taking_id: null, photos_taken_away: 0, comments_deleted: 0, frames: [] }, () => [])).toBe('There was nothing left to take away.');
		expect(resultMessage(null, () => [], 'Janssens')).toBe('You left Janssens.');
	});

	it('formats the trash date, with the year only when it differs', () => {
		expect(dayMonth('2026-12-19T12:00:00Z', new Date('2026-11-19T12:00:00Z'))).toBe('19 December');
		expect(dayMonth('2027-01-02T12:00:00Z', new Date('2026-11-19T12:00:00Z'))).toBe('2 January 2027');
		expect(dayMonth(null)).toBe('');
	});

	it('names frames on other servers with their server', () => {
		expect(remoteFrameName('Oma’s frame', 'kinframe.appmire.be')).toBe('Oma’s frame (kinframe.appmire.be)');
		expect(remoteFrameName('Oma’s frame', null)).toBe('Oma’s frame');
	});

	it('credits handed-over and copied photos', () => {
		expect(senderLabel({ sender_name: 'Lies', former_sender_name: 'Tom' })).toBe('Tom (former member)');
		expect(senderLabel({ sender_name: 'x', remote_sender: { name: 'Lies', server_name: 'Janssens', server_url: 'https://kinframe.janssens.be' } })).toBe('Lies');
		expect(
			placementCredit({
				origin: 'feed',
				origin_feed_name: 'Janssens',
				item: { sender_name: 'x', remote_sender: { name: 'Lies', server_name: 'Janssens', server_url: 'https://kinframe.janssens.be' } }
			})
		).toBe('Lies · Janssens');
	});

	it('opens the right page for leaving notifications', () => {
		expect(pushUrl({ key: 'photos_taken_away', ids: { taking_id: REQ, frame_id: FRAME } })).toBe(`/frames/${FRAME}?tab=library&view=trashed`);
		expect(pushUrl({ key: 'photos_taken_away', ids: { taking_id: REQ, feed_id: FEED } })).toBe(`/feeds/${FEED}`);
		expect(pushUrl({ key: 'keep_request', ids: { request_id: REQ, frame_id: FRAME } })).toBe(`/history?keep=${REQ}`);
		expect(pushUrl({ key: 'keep_answered', ids: { request_id: REQ, frame_id: FRAME } })).toBe(`/frames/${FRAME}?tab=library&view=trashed`);
		expect(pushUrl({ key: 'ownership_changed', ids: { feed_id: FEED } })).toBe(`/feeds/${FEED}`);
		expect(pushUrl({ key: 'ownership_changed', ids: { frame_id: FRAME }, server: 'https://kinframe.appmire.be' })).toBe(`/frames/${FRAME}`);
	});
});
