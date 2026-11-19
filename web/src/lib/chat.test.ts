import { describe, expect, it } from 'vitest';
import type { Conversation, Message } from './api/client';
import {
	activeTypists,
	applyRead,
	bumpConversation,
	canEditMessage,
	chatRows,
	clock,
	lastSeenOwn,
	localId,
	mergeMessages,
	pickAudioMime,
	previewKind,
	recordingFile,
	rmsLevel,
	seenBy,
	sortConversations,
	unreadConversations,
	upsertMessage,
	waveformBars
} from './chat';

const id = (n: number) => `0192f0a0-0000-7000-8000-${String(n).padStart(12, '0')}`;
const EMPTY = { total: 0, counts: [], people: [], frames: [] };
const msg = (n: number, author = 'a', minutes = n, extra: Partial<Message> = {}): Message => ({
	id: id(n),
	conversation_id: 'c',
	kind: 'person',
	author: { user_id: author, display_name: author.toUpperCase() },
	body: `m${n}`,
	items: [],
	reactions: EMPTY,
	deleted: false,
	created_at: new Date(Date.UTC(2026, 10, 19, 10, minutes)).toISOString(),
	...extra
});
const conv = (extra: Partial<Conversation> = {}): Conversation => ({
	id: 'c',
	kind: 'direct',
	name: 'Anna',
	member_count: 2,
	unread_count: 0,
	last_activity_at: '2026-11-19T10:00:00Z',
	muted: false,
	archived: false,
	...extra
});

describe('message lists', () => {
	it('merges pages by id, the newer copy winning', () => {
		const merged = mergeMessages([msg(2), msg(4)], [msg(1), { ...msg(2), body: 'edited' }, msg(3)]);
		expect(merged.map((m) => m.id)).toEqual([id(1), id(2), id(3), id(4)]);
		expect(merged[1]!.body).toBe('edited');
	});

	it('replaces the optimistic bubble with the sent message (client_id)', () => {
		const local = { ...msg(0, 'me'), id: localId('k1'), client_id: 'k1', created_at: '2026-11-19T11:00:00Z' };
		let list = upsertMessage([msg(1)], local);
		expect(list.map((m) => m.id)).toEqual([id(1), localId('k1')]);
		list = upsertMessage(list, msg(2, 'b'));
		expect(list.map((m) => m.id)).toEqual([id(1), id(2), localId('k1')]);
		list = upsertMessage(list, { ...msg(3, 'me'), client_id: 'k1' });
		expect(list.map((m) => m.id)).toEqual([id(1), id(2), id(3)]);
	});

	it('makes day separators and author runs', () => {
		const day = (iso: string) => iso.slice(0, 10);
		const rows = chatRows([msg(1, 'a', 0), msg(2, 'a', 1), msg(3, 'b', 2), msg(4, 'a', 20)], 'b', day);
		expect(rows[0]).toMatchObject({ kind: 'day' });
		const m = rows.filter((r) => r.kind === 'message');
		expect(m.map((r) => (r.kind === 'message' ? [r.first, r.last, r.mine] : null))).toEqual([
			[true, false, false],
			[false, true, false],
			[true, true, true],
			[true, true, false]
		]);
	});
});

describe('read receipts', () => {
	const c = conv({
		read_receipts: true,
		members: [
			{ person: { user_id: 'me', display_name: 'Me' }, role: 'member', last_read_message_id: id(5) },
			{ person: { user_id: 'a', display_name: 'Anna' }, role: 'member', last_read_message_id: id(2) }
		]
	});

	it('lists who has seen a message, never the caller', () => {
		expect(seenBy(c, id(2), 'me').map((p) => p.user_id)).toEqual(['a']);
		expect(seenBy(c, id(3), 'me')).toEqual([]);
		expect(seenBy({ ...c, read_receipts: false }, id(1), 'me')).toEqual([]);
	});

	it('shows "Seen" under the newest own message only', () => {
		expect(lastSeenOwn([msg(1, 'me'), msg(2, 'me'), msg(3, 'a')], c, 'me')).toBe(id(2));
		expect(lastSeenOwn([msg(1, 'me'), msg(3, 'me')], c, 'me')).toBeNull();
	});

	it('applies read.updated, never backwards', () => {
		expect(applyRead(c, 'a', id(4)).members![1]!.last_read_message_id).toBe(id(4));
		expect(applyRead(c, 'a', id(1)).members![1]!.last_read_message_id).toBe(id(2));
	});
});

describe('messages', () => {
	it('lets the author edit text for a while', () => {
		const m = msg(1, 'me', 0);
		const sent = Date.parse(m.created_at);
		expect(canEditMessage(m, 'me', 24, sent + 3_600_000)).toBe(true);
		expect(canEditMessage(m, 'me', 24, sent + 25 * 3_600_000)).toBe(false);
		expect(canEditMessage(m, 'other', 24, sent)).toBe(false);
		expect(canEditMessage({ ...m, deleted: true }, 'me', 24, sent)).toBe(false);
		expect(canEditMessage({ ...m, kind: 'system' }, 'me', 24, sent)).toBe(false);
	});

	it('describes the last message for the list', () => {
		expect(previewKind(null)).toBe('empty');
		expect(previewKind(msg(1))).toBe('text');
		expect(previewKind({ ...msg(1), deleted: true })).toBe('deleted');
		expect(previewKind({ ...msg(1), body: null, voice: { item_id: 'v', status: 'ready' } })).toBe('voice');
		expect(previewKind({ ...msg(1), body: null, items: [{ id: 'i', kind: 'video', status: 'ready', sender_id: 'a', created_at: '' }] })).toBe('videos');
		expect(previewKind({ ...msg(1), kind: 'system', body: null })).toBe('system');
	});
});

describe('the chat list', () => {
	it('sorts by activity and counts unread chats that are not muted', () => {
		const list = [conv({ id: 'x', last_activity_at: '2026-01-01T00:00:00Z', unread_count: 2 }), conv({ id: 'y', last_activity_at: '2026-02-01T00:00:00Z', unread_count: 1, muted: true })];
		expect(sortConversations(list).map((c) => c.id)).toEqual(['y', 'x']);
		expect(unreadConversations(list)).toBe(1);
	});

	it('bumps a conversation on a new message', () => {
		const list = [conv({ id: 'c', last_activity_at: '2026-01-01T00:00:00Z' }), conv({ id: 'd', last_activity_at: '2026-01-02T00:00:00Z' })];
		const m = msg(1, 'a');
		const next = bumpConversation(list, m, 'me', false);
		expect(next[0]!.id).toBe('c');
		expect(next[0]!.unread_count).toBe(1);
		expect(bumpConversation(list, m, 'me', true)[0]!.unread_count).toBe(0);
		expect(bumpConversation(list, msg(1, 'me'), 'me', false)[0]!.unread_count).toBe(0);
	});
});

describe('typing and voice', () => {
	it('keeps only typists that have not expired', () => {
		const map = new Map([
			['a', { userId: 'a', name: 'Anna', activity: 'typing' as const, expires: 2000 }],
			['b', { userId: 'b', name: 'Ben', activity: 'recording' as const, expires: 500 }]
		]);
		expect(activeTypists(map, 1000).map((t) => t.userId)).toEqual(['a']);
	});

	it('picks a recording format the browser supports', () => {
		expect(pickAudioMime((m) => m === 'audio/mp4')).toBe('audio/mp4');
		expect(pickAudioMime((m) => m.startsWith('audio/webm'))).toBe('audio/webm;codecs=opus');
		expect(pickAudioMime(() => false)).toBeNull();
		expect(
			pickAudioMime(() => {
				throw new Error('x');
			})
		).toBeNull();
		expect(recordingFile('audio/webm;codecs=opus')).toEqual({ name: 'voice.webm', type: 'audio/webm' });
		expect(recordingFile('audio/mp4')).toEqual({ name: 'voice.m4a', type: 'audio/mp4' });
		expect(recordingFile('audio/ogg;codecs=opus').name).toBe('voice.ogg');
	});

	it('turns levels into waveform bars', () => {
		expect(waveformBars([], 4)).toEqual([0, 0, 0, 0]);
		expect(waveformBars([0, 1, 0.5, 0.2], 2)).toEqual([100, 50]);
		expect(waveformBars([0.3], 3)).toEqual([30, 30, 30]);
		expect(rmsLevel([128, 128])).toBe(0);
		expect(rmsLevel([255, 0])).toBe(1);
		expect(clock(83_000)).toBe('1:23');
		expect(clock(5_400)).toBe('0:05');
	});
});
