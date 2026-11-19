import { describe, expect, it } from 'vitest';
import {
	answerBody,
	askBody,
	defaultChoice,
	formerList,
	hasPhotoChoice,
	leaveFeedBody,
	readOnlyName,
	requestPlace,
	resultParts,
	sortRequests,
	upsertRequest,
	withMemories,
	withPerson,
	type KeepRequest,
	type LeavePreview
} from './leaving';

const preview: LeavePreview = {
	scope: 'feed',
	name: 'Janssens',
	member: true,
	photos: 214,
	comments: 38,
	frames: [
		{ frame_id: 'oma', frame_name: 'Oma Janssens’s frame', role: 'member', suggested: true, can_leave: true, direct_photos: 3 },
		{ frame_id: 'kitchen', frame_name: 'Kitchen frame', role: 'admin', suggested: false, can_leave: true },
		{ frame_id: 'mine', frame_name: 'My frame', role: 'owner', suggested: true, can_leave: false }
	]
};

const req = (p: Partial<KeepRequest>): KeepRequest => ({
	id: 'r',
	status: 'open',
	scope: 'frame',
	taking_id: 't',
	asked_by_name: 'Lies',
	asked_at: '2026-11-19T10:00:00Z',
	expires_at: '2026-12-19T10:00:00Z',
	item_count: 12,
	...p
});

describe('the leave sheet', () => {
	it('keeps photos and comments by default and checks suggested frames the person can leave', () => {
		expect(defaultChoice(preview)).toEqual({ photos: 'keep', comments: 'keep', frames: ['oma'] });
		expect(defaultChoice(null)).toEqual({ photos: 'keep', comments: 'keep', frames: [] });
	});

	it('always sends photos, and only frames that can be left', () => {
		expect(leaveFeedBody({ photos: 'take', comments: 'delete', frames: ['oma', 'mine', 'gone'] }, preview)).toEqual({
			photos: 'take',
			comments: 'delete',
			leave_frame_ids: ['oma']
		});
		expect(leaveFeedBody({ photos: 'keep', comments: 'keep', frames: [] }, preview)).toEqual({ photos: 'keep', comments: 'keep' });
	});

	it('asks about photos only when there are some', () => {
		expect(hasPhotoChoice(preview)).toBe(true);
		expect(hasPhotoChoice({ photos: 0, frames: [] })).toBe(false);
		expect(hasPhotoChoice({ photos: 0, frames: [{ ...preview.frames![0]!, direct_photos: 2 }] })).toBe(true);
	});

	it('turns the result into lines', () => {
		const parts = resultParts(
			{ taking_id: 't', photos_taken_away: 214, comments_deleted: 38, until: '2026-12-19T00:00:00Z', frames: [], remote_frames: 1, left_frame_ids: ['oma'] },
			(ids) => ids.map((x) => x.toUpperCase())
		);
		expect(parts.map((p) => p.key)).toEqual(['taken', 'remote', 'comments', 'frames']);
		expect(parts[3]!.names).toEqual(['OMA']);
		expect(resultParts({ taking_id: null, photos_taken_away: 0, comments_deleted: 0, frames: [] }, () => [])).toEqual([]);
		expect(resultParts(null, () => [])).toEqual([]);
	});
});

describe('former places', () => {
	it('lists families then frames, by name, only with photos left', () => {
		const list = formerList({
			feeds: [
				{ feed_id: 'b', name: 'Peeters', photos: 4 },
				{ feed_id: 'a', name: 'Janssens', photos: 214, left_at: '2026-11-01T00:00:00Z' },
				{ feed_id: 'c', name: 'Empty', photos: 0 }
			],
			frames: [{ frame_id: 'f', name: 'Oma’s frame', photos: 3 }]
		});
		expect(list.map((x) => `${x.kind}:${x.name}`)).toEqual(['feed:Janssens', 'feed:Peeters', 'frame:Oma’s frame']);
		expect(formerList(null)).toEqual([]);
	});
});

describe('keep requests', () => {
	it('names the place', () => {
		expect(requestPlace(req({ frame_name: 'Oma’s frame' }))).toBe('Oma’s frame');
		expect(requestPlace(req({ scope: 'feed', feed_name: 'Janssens' }))).toBe('Janssens');
	});

	it('answers all, none, or the picked ones of the request', () => {
		expect(answerBody('all', [], ['a', 'b'])).toEqual({ allow: 'all' });
		expect(answerBody('none', ['a'], ['a', 'b'])).toEqual({ allow: 'none' });
		expect(answerBody('some', ['a', 'x'], ['a', 'b', 'c'])).toEqual({ allow: 'some', item_ids: ['a'] });
		expect(answerBody('some', [], ['a', 'b'])).toBeNull();
		expect(answerBody('some', ['a', 'b'], ['a', 'b'])).toEqual({ allow: 'all' });
	});

	it('asks for all, or for the picked photos, with a note', () => {
		expect(askBody({ frameId: 'f' }, 't', null, 12, '  ')).toEqual({ taking_id: 't', frame_id: 'f' });
		expect(askBody({ feedId: 'd' }, 't', ['a', 'b'], 12, 'For Oma')).toEqual({ taking_id: 't', feed_id: 'd', item_ids: ['a', 'b'], note: 'For Oma' });
		expect(askBody({ frameId: 'f' }, 't', ['a', 'b'], 2, '')).toEqual({ taking_id: 't', frame_id: 'f' });
	});

	it('sorts open requests by deadline first, then the rest newest first', () => {
		const list = sortRequests([
			req({ id: 'old', status: 'declined', asked_at: '2026-10-01T00:00:00Z' }),
			req({ id: 'late', expires_at: '2026-12-30T00:00:00Z' }),
			req({ id: 'soon', expires_at: '2026-12-01T00:00:00Z' }),
			req({ id: 'new', status: 'allowed', asked_at: '2026-11-10T00:00:00Z' })
		]);
		expect(list.map((r) => r.id)).toEqual(['soon', 'late', 'new', 'old']);
	});

	it('replaces or adds a request from the websocket', () => {
		const a = req({ id: 'a' });
		expect(upsertRequest([a], { ...a, status: 'allowed' })[0]!.status).toBe('allowed');
		expect(upsertRequest([a], req({ id: 'b' })).map((r) => r.id)).toEqual(['b', 'a']);
	});
});

describe('memories and chats', () => {
	const prefs = { hidden_people: [{ user_id: 'tom', display_name: 'Tom' }], hidden_memories: ['p1'] };
	it('adds and removes people and memories', () => {
		expect(withPerson(prefs, 'ben', true)).toEqual({ hidden_people: ['tom', 'ben'] });
		expect(withPerson(prefs, 'tom', false)).toEqual({ hidden_people: [] });
		expect(withPerson(prefs, 'tom', true)).toEqual({ hidden_people: ['tom'] });
		expect(withMemories(prefs, ['p2', 'p2', 'p1'], true)).toEqual({ hidden_memories: ['p2', 'p1'] });
		expect(withMemories(prefs, ['p1'], false)).toEqual({ hidden_memories: [] });
	});

	it('names the other person of a read-only chat', () => {
		expect(readOnlyName({ name: 'Lies', other: { user_id: 'l', display_name: 'Lies' } }, 'me')).toBe('Lies');
		expect(
			readOnlyName(
				{
					name: 'x',
					members: [
						{ person: { user_id: 'me', display_name: 'Tom' }, role: 'member' },
						{ person: { user_id: 'l', display_name: 'Lies' }, role: 'member' }
					]
				},
				'me'
			)
		).toBe('Lies');
	});
});
