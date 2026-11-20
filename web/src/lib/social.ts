// Family social (docs/spec/family-social.md, README §5.18): reactions, threads, mentions and the timeline. Pure:
// unit-tested in social.test.ts.

import type { Comment, Item, Mention, PersonReaction, PersonRef, ReactionList, ReactionSummary, ThreadSummary, TimelinePost } from './api/client';

/**
 * The media variant for a large post photo: the uncropped display rendition once the server has made it
 * (`display_url`), else the 512 px square thumbnail (older items until the backfill reached them, older servers).
 */
export function postImageVariant(item: Pick<Item, 'display_url'>): 'display' | 'thumb_512' {
	return item.display_url ? 'display' : 'thumb_512';
}

// ---------------------------------------------------------------- reactions

/** The people's set: the frame's four first, so counts merge with frames' reactions. */
export const PERSON_REACTIONS: readonly PersonReaction[] = ['love', 'laugh', 'wow', 'sad', 'thumbs_up', 'pray'];

export const PERSON_EMOJI: Record<PersonReaction, string> = {
	love: '❤️',
	laugh: '😂',
	wow: '😮',
	sad: '😢',
	thumbs_up: '👍',
	pray: '🙏'
};

export const EMPTY_REACTIONS: ReactionSummary = { total: 0, counts: [], people: [], frames: [] };

/** What the reaction row says: the top emoji and "Oma's frame, Anna and 3 others". */
export interface ReactionLine {
	emojis: string[];
	/** Up to two names, frames first (they are the family's grandparents), then people. */
	names: string[];
	/** Reactions beyond the named ones. */
	others: number;
	total: number;
}

/**
 * `frameName` names a frame: frames on other servers get their server ("Oma's frame (Appmire)", README §5.22).
 */
export function reactionLine(
	s: ReactionSummary | null | undefined,
	meId?: string,
	youLabel = 'You',
	frameName: (name: string, server: string | null | undefined) => string = (name) => name
): ReactionLine {
	if (!s || s.total <= 0) return { emojis: [], names: [], others: 0, total: 0 };
	const emojis = s.counts
		.filter((c) => c.count > 0)
		.slice(0, 3)
		.map((c) => PERSON_EMOJI[c.emoji]);
	const all: string[] = [];
	for (const f of s.frames ?? []) {
		const n = frameName(f.frame_name, f.server_name);
		if (!all.includes(n)) all.push(n);
	}
	for (const p of s.people ?? []) {
		const n = p.person.user_id === meId ? youLabel : p.person.display_name;
		if (n && !all.includes(n)) all.push(n);
	}
	const names = all.slice(0, 2);
	return { emojis, names, others: Math.max(0, s.total - names.length), total: s.total };
}

/**
 * The summary right after the caller changed their own reaction, before the server answers (optimistic update):
 * moves one count from the old emoji to the new one.
 */
export function withMyReaction(s: ReactionSummary | null | undefined, next: PersonReaction | null): ReactionSummary {
	const base = s ?? EMPTY_REACTIONS;
	const prev = base.mine ?? null;
	if (prev === next) return base;
	const counts = new Map(base.counts.map((c) => [c.emoji, c.count]));
	if (prev) counts.set(prev, Math.max(0, (counts.get(prev) ?? 0) - 1));
	if (next) counts.set(next, (counts.get(next) ?? 0) + 1);
	const total = Math.max(0, base.total + (next ? 1 : 0) - (prev ? 1 : 0));
	return {
		...base,
		total,
		mine: next,
		counts: [...counts.entries()]
			.filter(([, n]) => n > 0)
			.map(([emoji, count]) => ({ emoji, count }))
			.sort((a, b) => b.count - a.count || PERSON_REACTIONS.indexOf(a.emoji) - PERSON_REACTIONS.indexOf(b.emoji))
	};
}

/**
 * A summary built from a full list (`listReactions`). Single photos of a post have no summary in the timeline, so the
 * photo viewer asks for the list and shows it the same way.
 */
export function summaryFromList(list: ReactionList, meId: string | undefined): ReactionSummary {
	const counts = new Map<PersonReaction, number>();
	for (const p of list.people) counts.set(p.emoji, (counts.get(p.emoji) ?? 0) + 1);
	for (const f of list.frames) counts.set(f.reaction, (counts.get(f.reaction) ?? 0) + 1);
	return {
		total: list.people.length + list.frames.length,
		counts: [...counts.entries()].map(([emoji, count]) => ({ emoji, count })).sort((a, b) => b.count - a.count),
		people: list.people.slice(0, 3).map((p) => ({ person: p.person, emoji: p.emoji })),
		frames: list.frames.slice(0, 3),
		mine: list.people.find((p) => p.person.user_id === meId)?.emoji ?? null
	};
}

// ---------------------------------------------------------------- threads

export interface CommentNode {
	comment: Comment;
	replies: Comment[];
}

/** UUIDv7 ids sort by time; the server's order is the id order (README §5.18). */
export const byId = (a: { id: string }, b: { id: string }) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);

/** The flat, oldest-first thread nested one level: replies under their top comment. */
export function nestComments(flat: Comment[]): CommentNode[] {
	const nodes: CommentNode[] = [];
	const byParent = new Map<string, CommentNode>();
	for (const c of [...flat].sort(byId)) {
		const parent = c.parent_id ? byParent.get(c.parent_id) : undefined;
		if (parent) {
			parent.replies.push(c);
			continue;
		}
		const node: CommentNode = { comment: c, replies: [] };
		nodes.push(node);
		if (!c.parent_id) byParent.set(c.id, node);
	}
	return nodes;
}

/** Insert or replace a comment, keeping id order. */
export function upsertComment(list: Comment[], c: Comment): Comment[] {
	const i = list.findIndex((x) => x.id === c.id);
	if (i >= 0) {
		const next = [...list];
		next[i] = c;
		return next;
	}
	return [...list, c].sort(byId);
}

/** Remove a comment; a moderator's removal also takes its replies (README §5.18). */
export function removeComment(list: Comment[], id: string): Comment[] {
	return list.filter((c) => c.id !== id && c.parent_id !== id);
}

/** The newest person comment, for `markThreadRead`. */
export function lastPersonCommentId(list: Comment[]): string | null {
	let last: string | null = null;
	for (const c of list) if (c.kind === 'person' && (last === null || c.id > last)) last = c.id;
	return last;
}

/** Apply a `post.updated` thread to a timeline list (returns whether something changed). */
export function applyThread(posts: TimelinePost[], postId: string, thread: ThreadSummary): boolean {
	const p = posts.find((x) => x.id === postId);
	if (!p) return false;
	p.thread = thread;
	return true;
}

/** Apply a `reaction.updated` for a post to a timeline list. */
export function applyPostReactions(posts: TimelinePost[], postId: string, summary: ReactionSummary): boolean {
	const p = posts.find((x) => x.id === postId);
	if (!p) return false;
	p.thread = { ...p.thread, reactions: summary };
	return true;
}

// ---------------------------------------------------------------- mentions (offsets in UTF-16 code units)

/** "@Ann" just before the caret: where it starts and what was typed after the @, or null. */
export function mentionQuery(text: string, caret: number): { start: number; query: string } | null {
	const before = text.slice(0, caret);
	const at = before.lastIndexOf('@');
	if (at < 0) return null;
	if (at > 0 && !/\s|[([{"'“‘]/.test(before[at - 1]!)) return null;
	const query = before.slice(at + 1);
	if (query.length > 40 || /[\n\r@]/.test(query) || (query.match(/ /g)?.length ?? 0) > 2 || query.startsWith(' ')) return null;
	return { start: at, query };
}

/** People whose name matches the typed query (start of any word), best first. */
export function matchPeople<P extends { display_name: string }>(people: P[], query: string, max = 6): P[] {
	const q = query.trim().toLocaleLowerCase();
	if (!q) return people.slice(0, max);
	const score = (name: string) => {
		const n = name.toLocaleLowerCase();
		if (n.startsWith(q)) return 0;
		if (n.split(/\s+/).some((w) => w.startsWith(q))) return 1;
		return n.includes(q) ? 2 : -1;
	};
	return people
		.map((p) => ({ p, s: score(p.display_name) }))
		.filter((x) => x.s >= 0)
		.sort((a, b) => a.s - b.s || a.p.display_name.localeCompare(b.p.display_name))
		.slice(0, max)
		.map((x) => x.p);
}

/** Replace `text[start, end)` (the "@Ann" being typed) with "@Anna " and record the span. */
export function insertMention(
	text: string,
	mentions: Mention[],
	start: number,
	end: number,
	person: { user_id: string; display_name: string }
): { text: string; mentions: Mention[]; caret: number } {
	const label = `@${person.display_name}`;
	const after = text.slice(end);
	const sep = after.startsWith(' ') ? '' : ' ';
	const next = text.slice(0, start) + label + sep + after;
	const added = label.length + sep.length;
	const shift = added - (end - start);
	const kept: Mention[] = [];
	for (const m of mentions) {
		if (m.start + m.length <= start) kept.push(m);
		else if (m.start >= end) kept.push({ ...m, start: m.start + shift });
	}
	const all = [...kept, { user_id: person.user_id, start, length: label.length }].sort((a, b) => a.start - b.start);
	return { text: next, mentions: all, caret: start + added };
}

/**
 * Keep mention spans valid after a free edit from `prev` to `next`: spans before the change stay, spans after it
 * move, spans the change touched are dropped (the name was edited, so it is plain text now).
 */
export function reconcileMentions(prev: string, next: string, mentions: Mention[]): Mention[] {
	if (prev === next) return mentions;
	let p = 0;
	const max = Math.min(prev.length, next.length);
	while (p < max && prev[p] === next[p]) p++;
	let s = 0;
	while (s < max - p && prev[prev.length - 1 - s] === next[next.length - 1 - s]) s++;
	const changeEnd = prev.length - s;
	const delta = next.length - prev.length;
	const out: Mention[] = [];
	for (const m of mentions) {
		const end = m.start + m.length;
		if (end <= p) out.push(m);
		else if (m.start >= changeEnd) out.push({ ...m, start: m.start + delta });
	}
	return out.filter((m) => m.start >= 0 && m.start + m.length <= next.length);
}

/** Mentions that fit the body, sorted and non-overlapping (what the server accepts), at most `max`. */
export function cleanMentions(body: string, mentions: Mention[], max = 20): Mention[] {
	const out: Mention[] = [];
	let end = 0;
	for (const m of [...mentions].sort((a, b) => a.start - b.start)) {
		if (m.start < end || m.length < 1 || m.start + m.length > body.length) continue;
		out.push(m);
		end = m.start + m.length;
		if (out.length >= max) break;
	}
	return out;
}

/** The trimmed body with its mentions moved by the characters trimmed at the start. */
export function trimWithMentions(body: string, mentions: Mention[]): { body: string; mentions: Mention[] } {
	const lead = body.length - body.trimStart().length;
	const trimmed = body.trim();
	return { body: trimmed, mentions: cleanMentions(trimmed, mentions.map((m) => ({ ...m, start: m.start - lead }))) };
}

export type Segment = { kind: 'text'; text: string } | { kind: 'mention'; text: string; userId: string } | { kind: 'link'; text: string; href: string };

const LINK = /\bhttps?:\/\/[^\s<>"'`]+/gi;

/** Split plain text into text and links (trailing punctuation stays text). */
export function linkify(text: string): Segment[] {
	const out: Segment[] = [];
	let last = 0;
	for (const m of text.matchAll(LINK)) {
		let url = m[0];
		const unbalanced = (u: string) => u.endsWith(')') && (u.match(/\)/g)?.length ?? 0) > (u.match(/\(/g)?.length ?? 0);
		while (/[.,!?;:\]}’”]$/.test(url) || unbalanced(url)) url = url.slice(0, -1);
		const at = m.index ?? 0;
		if (at > last) out.push({ kind: 'text', text: text.slice(last, at) });
		out.push({ kind: 'link', text: url, href: url });
		last = at + url.length;
	}
	if (last < text.length) out.push({ kind: 'text', text: text.slice(last) });
	return out;
}

/** A comment or message body as text, mention and link segments. */
export function segments(body: string | null | undefined, mentions: Mention[] | null | undefined): Segment[] {
	if (!body) return [];
	const out: Segment[] = [];
	let at = 0;
	for (const m of cleanMentions(body, mentions ?? [], 1000)) {
		if (m.start > at) out.push(...linkify(body.slice(at, m.start)));
		out.push({ kind: 'mention', text: body.slice(m.start, m.start + m.length), userId: m.user_id });
		at = m.start + m.length;
	}
	if (at < body.length) out.push(...linkify(body.slice(at)));
	return out;
}

/** The first http(s) link in a text (for the link preview). */
export function firstLink(text: string): string | null {
	const seg = linkify(text).find((s) => s.kind === 'link');
	return seg && seg.kind === 'link' ? seg.href : null;
}

/** Unicode code points, as the server counts `max_comment_chars` and `max_message_chars`. */
export function codePoints(text: string): number {
	return [...text].length;
}

// ---------------------------------------------------------------- timeline

export type TimelineRow = { kind: 'post'; post: TimelinePost } | { kind: 'prompt'; prompt: { id: string; title: string }; posts: TimelinePost[] };

/** Consecutive posts that answer the same prompt become one group ("6 answers to …"); the order stays. */
export function groupTimeline(posts: TimelinePost[]): TimelineRow[] {
	const rows: TimelineRow[] = [];
	for (const p of posts) {
		const last = rows[rows.length - 1];
		if (p.prompt && last) {
			if (last.kind === 'prompt' && last.prompt.id === p.prompt.id) {
				last.posts.push(p);
				continue;
			}
			if (last.kind === 'post' && last.post.prompt?.id === p.prompt.id) {
				rows[rows.length - 1] = { kind: 'prompt', prompt: p.prompt, posts: [last.post, p] };
				continue;
			}
		}
		rows.push({ kind: 'post', post: p });
	}
	return rows;
}

/** Append an older page without duplicates. */
export function appendPosts(list: TimelinePost[], page: TimelinePost[]): TimelinePost[] {
	const seen = new Set(list.map((p) => p.id));
	return [...list, ...page.filter((p) => !seen.has(p.id))];
}

/** A post as the caller sees it: the family label, or "To Oma's frame". */
export function postPlace(p: Pick<TimelinePost, 'kind' | 'feed_name' | 'circle_name' | 'frame_name'>): { kind: 'feed' | 'circle' | 'frame'; name: string; circle?: string } {
	if (p.kind === 'frame') return { kind: 'frame', name: p.frame_name ?? '' };
	if (p.circle_name) return { kind: 'circle', name: p.feed_name ?? '', circle: p.circle_name };
	return { kind: 'feed', name: p.feed_name ?? '' };
}

/** A person's name, or the localized "Former member" for deleted accounts. */
export function personName(p: PersonRef | null | undefined, formerLabel: string): string {
	if (!p) return formerLabel;
	return p.former_member || !p.display_name ? formerLabel : p.display_name;
}

/**
 * A post's poster. Posts handed over at account deletion (README §5.21) have a former-member poster but keep the
 * frozen name on their items: "Tom (former member)" (`formerNamed`) instead of the bare "Former member".
 */
export function posterName(
	post: { poster: PersonRef; items?: { former_sender_name?: string | null }[] | null },
	formerLabel: string,
	formerNamed: (name: string) => string
): string {
	if (post.poster.former_member) {
		const name = post.items?.map((i) => i.former_sender_name?.trim()).find((n) => !!n);
		if (name) return formerNamed(name);
	}
	return personName(post.poster, formerLabel);
}

// ---------------------------------------------------------------- badge

/** "(3) Home · kinframe": the unread count in the tab title. */
export function badgeTitle(title: string, count: number): string {
	const clean = title.replace(/^\(\d+\+?\)\s*/, '');
	if (count <= 0) return clean;
	return `(${count > 99 ? '99+' : count}) ${clean}`;
}
