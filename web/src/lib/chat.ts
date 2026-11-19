// Chats (README §5.19): message lists, read receipts, typing, voice recording helpers. Pure: unit-tested in
// chat.test.ts.

import type { Conversation, Message, PersonRef } from './api/client';
import { byId } from './social';

// ---------------------------------------------------------------- messages

/** Merge a page into what we have: one entry per id, oldest first; the newer copy wins. */
export function mergeMessages(have: Message[], page: Message[]): Message[] {
	const map = new Map(have.map((m) => [m.id, m]));
	for (const m of page) map.set(m.id, m);
	return [...map.values()].sort(byId);
}

/**
 * Insert or replace a message. A message carrying the `client_id` of an optimistic bubble replaces that bubble
 * (the bubble's id is `local:<client_id>`).
 */
export function upsertMessage(list: Message[], m: Message): Message[] {
	const without = list.filter((x) => x.id !== m.id && !(m.client_id && x.id === localId(m.client_id)));
	return [...without, m].sort(sortWithPending);
}

export const localId = (clientId: string) => `local:${clientId}`;
export const isLocal = (m: Pick<Message, 'id'>) => m.id.startsWith('local:');

/** Server messages by id; optimistic bubbles stay at the end in the order they were made. */
function sortWithPending(a: Message, b: Message): number {
	const la = isLocal(a);
	const lb = isLocal(b);
	if (la !== lb) return la ? 1 : -1;
	if (la && lb) return a.created_at < b.created_at ? -1 : 1;
	return byId(a, b);
}

export type ChatRow =
	| { kind: 'day'; key: string; date: string }
	| { kind: 'message'; key: string; message: Message; mine: boolean; first: boolean; last: boolean };

const GAP_MS = 5 * 60_000;

/** Day separators, and runs of one author's messages (the name and avatar show on the first). */
export function chatRows(messages: Message[], meId: string | undefined, dayOf: (iso: string) => string): ChatRow[] {
	const rows: ChatRow[] = [];
	let day = '';
	for (let i = 0; i < messages.length; i++) {
		const m = messages[i]!;
		const d = dayOf(m.created_at);
		if (d !== day) {
			rows.push({ kind: 'day', key: `day:${d}`, date: m.created_at });
			day = d;
		}
		const prev = messages[i - 1];
		const next = messages[i + 1];
		const same = (a?: Message, b?: Message) =>
			!!a &&
			!!b &&
			a.kind === 'person' &&
			b.kind === 'person' &&
			a.author?.user_id === b.author?.user_id &&
			Math.abs(Date.parse(a.created_at) - Date.parse(b.created_at)) < GAP_MS &&
			dayOf(a.created_at) === dayOf(b.created_at);
		rows.push({
			kind: 'message',
			key: m.id,
			message: m,
			mine: !!meId && m.author?.user_id === meId,
			first: !same(prev, m),
			last: !same(m, next)
		});
	}
	return rows;
}

/** Who has read up to (at least) this message, besides the caller (read receipts, README §5.19). */
export function seenBy(conv: Pick<Conversation, 'members' | 'read_receipts'>, messageId: string, meId: string | undefined): PersonRef[] {
	if (!conv.read_receipts || isLocal({ id: messageId })) return [];
	return (conv.members ?? [])
		.filter((m) => m.person.user_id !== meId && m.last_read_message_id && m.last_read_message_id >= messageId)
		.map((m) => m.person);
}

/** The caller's newest own message that someone has seen (only that one shows "Seen"). */
export function lastSeenOwn(messages: Message[], conv: Pick<Conversation, 'members' | 'read_receipts'>, meId: string | undefined): string | null {
	for (let i = messages.length - 1; i >= 0; i--) {
		const m = messages[i]!;
		if (m.author?.user_id !== meId || isLocal(m)) continue;
		return seenBy(conv, m.id, meId).length > 0 ? m.id : null;
	}
	return null;
}

/** Apply a `read.updated` to the conversation's members. */
export function applyRead(conv: Conversation, userId: string, lastRead: string): Conversation {
	if (!conv.members) return conv;
	return {
		...conv,
		members: conv.members.map((m) =>
			m.person.user_id === userId && (!m.last_read_message_id || m.last_read_message_id < lastRead) ? { ...m, last_read_message_id: lastRead } : m
		)
	};
}

/** The author may edit text within `limits.message_edit_hours`. */
export function canEditMessage(m: Message, meId: string | undefined, hours: number, now = Date.now()): boolean {
	return (
		m.kind === 'person' &&
		!m.deleted &&
		!isLocal(m) &&
		!!meId &&
		m.author?.user_id === meId &&
		!!m.body &&
		!m.voice &&
		now - Date.parse(m.created_at) < hours * 3_600_000
	);
}

export type Preview = 'deleted' | 'voice' | 'photos' | 'videos' | 'post' | 'system' | 'text' | 'empty';

/** What the chat list shows for the last message. */
export function previewKind(m: Message | null | undefined): Preview {
	if (!m) return 'empty';
	if (m.deleted) return 'deleted';
	if (m.kind === 'system') return 'system';
	if (m.voice) return 'voice';
	if (m.body) return 'text';
	if (m.items.length) return m.items.every((i) => i.kind === 'video') ? 'videos' : 'photos';
	if (m.shared_post) return 'post';
	return 'empty';
}

/** Newest activity first (the server's order; used again after live updates). */
export function sortConversations(list: Conversation[]): Conversation[] {
	return [...list].sort((a, b) => (a.last_activity_at < b.last_activity_at ? 1 : a.last_activity_at > b.last_activity_at ? -1 : 0));
}

/** The number of conversations with unread messages that aren't muted (the Chats tab's badge). */
export function unreadConversations(list: Conversation[]): number {
	return list.filter((c) => !c.muted && !c.archived && c.unread_count > 0).length;
}

/** After a `message.created` for a conversation in the list: newest message, unread count, activity. */
export function bumpConversation(list: Conversation[], m: Message, meId: string | undefined, open: boolean): Conversation[] {
	const i = list.findIndex((c) => c.id === m.conversation_id);
	if (i < 0) return list;
	const c = list[i]!;
	const own = m.author?.user_id === meId;
	const counts = !own && !open && m.kind === 'person';
	const next: Conversation = {
		...c,
		last_message: m,
		last_activity_at: m.created_at > c.last_activity_at ? m.created_at : c.last_activity_at,
		unread_count: counts ? c.unread_count + 1 : open ? 0 : c.unread_count,
		archived: false
	};
	return sortConversations([...list.slice(0, i), next, ...list.slice(i + 1)]);
}

// ---------------------------------------------------------------- typing

export interface Typist {
	userId: string;
	name: string;
	activity: 'typing' | 'recording';
	expires: number;
}

/** Who is typing in one conversation right now (from `typing` events; each expires after its `expires_at`). */
export function activeTypists(all: Map<string, Typist>, now = Date.now()): Typist[] {
	return [...all.values()].filter((t) => t.expires > now).sort((a, b) => a.name.localeCompare(b.name));
}

// ---------------------------------------------------------------- voice messages

/**
 * What this browser can record: AAC in MP4 (Safari) or Opus in WebM/Ogg (Chrome, Firefox); the server stores AAC
 * (README §5.19). Null when none works.
 */
export function pickAudioMime(isSupported: (mime: string) => boolean): string | null {
	for (const m of ['audio/mp4;codecs=mp4a.40.2', 'audio/mp4', 'audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/webm']) {
		try {
			if (isSupported(m)) return m;
		} catch {
			/* some browsers throw for unknown types */
		}
	}
	return null;
}

/** The file name and type for the tus upload of a recording. */
export function recordingFile(mime: string): { name: string; type: string } {
	const type = mime.split(';')[0]!;
	const ext = type === 'audio/mp4' ? 'm4a' : type === 'audio/ogg' ? 'ogg' : 'webm';
	return { name: `voice.${ext}`, type };
}

/** Loudness levels (any length, 0–1) as `n` bars of 0–100, like the server's waveform. */
export function waveformBars(levels: number[], n = 64): number[] {
	if (levels.length === 0) return Array.from({ length: n }, () => 0);
	const out: number[] = [];
	for (let i = 0; i < n; i++) {
		const from = Math.floor((i * levels.length) / n);
		const to = Math.max(from + 1, Math.floor(((i + 1) * levels.length) / n));
		let peak = 0;
		for (let j = from; j < to && j < levels.length; j++) peak = Math.max(peak, levels[j]!);
		out.push(Math.round(Math.min(1, Math.max(0, peak)) * 100));
	}
	return out;
}

/** RMS of a time-domain buffer (0–255 bytes around 128) as 0–1, a little boosted so speech fills the bars. */
export function rmsLevel(samples: ArrayLike<number>): number {
	if (samples.length === 0) return 0;
	let sum = 0;
	for (let i = 0; i < samples.length; i++) {
		const v = (samples[i]! - 128) / 128;
		sum += v * v;
	}
	return Math.min(1, Math.sqrt(sum / samples.length) * 2.5);
}

/** 83 000 ms → "1:23". */
export function clock(ms: number): string {
	const s = Math.max(0, Math.round(ms / 1000));
	return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
