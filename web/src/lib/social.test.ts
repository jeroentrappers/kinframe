import { describe, expect, it } from 'vitest';
import type { Comment, ReactionSummary, TimelinePost } from './api/client';
import {
	posterName,
	appendPosts,
	badgeTitle,
	cleanMentions,
	codePoints,
	firstLink,
	groupTimeline,
	insertMention,
	lastPersonCommentId,
	linkify,
	matchPeople,
	mentionQuery,
	nestComments,
	personName,
	postPlace,
	reactionLine,
	reconcileMentions,
	removeComment,
	segments,
	summaryFromList,
	trimWithMentions,
	upsertComment,
	withMyReaction
} from './social';

const id = (n: number) => `0192f0a0-0000-7000-8000-${String(n).padStart(12, '0')}`;
const comment = (n: number, parent?: number, extra: Partial<Comment> = {}): Comment => ({
	id: id(n),
	post_id: 'p',
	kind: 'person',
	parent_id: parent ? id(parent) : null,
	author: { user_id: `u${n}`, display_name: `User ${n}` },
	body: `c${n}`,
	created_at: '2026-11-19T10:00:00Z',
	...extra
});

describe('reaction row', () => {
	const s: ReactionSummary = {
		total: 5,
		counts: [
			{ emoji: 'love', count: 3 },
			{ emoji: 'laugh', count: 2 }
		],
		people: [
			{ person: { user_id: 'me', display_name: 'Ben' }, emoji: 'love' },
			{ person: { user_id: 'a', display_name: 'Anna' }, emoji: 'laugh' }
		],
		frames: [{ frame_id: 'f', frame_name: 'Oma’s frame', reaction: 'love' }],
		mine: 'love'
	};

	it('names frames first, then people, and counts the rest', () => {
		const l = reactionLine(s, 'other');
		expect(l.emojis).toEqual(['❤️', '😂']);
		expect(l.names).toEqual(['Oma’s frame', 'Ben']);
		expect(l.others).toBe(3);
	});

	it('says "You" for the caller', () => {
		expect(reactionLine(s, 'me', 'You').names).toEqual(['Oma’s frame', 'You']);
	});

	it('names frames on other servers with their server', () => {
		const remote = { ...s, frames: [{ frame_id: 'rs', frame_name: 'Oma’s frame', reaction: 'love' as const, server_name: 'Appmire' }] };
		expect(reactionLine(remote, 'me', 'You', (n, srv) => (srv ? `${n} (${srv})` : n)).names).toEqual(['Oma’s frame (Appmire)', 'You']);
	});

	it('is empty without reactions', () => {
		expect(reactionLine(null).total).toBe(0);
		expect(reactionLine({ total: 0, counts: [], people: [], frames: [] }).names).toEqual([]);
	});

	it('moves my reaction optimistically', () => {
		const changed = withMyReaction(s, 'laugh');
		expect(changed.mine).toBe('laugh');
		expect(changed.total).toBe(5);
		expect(changed.counts).toEqual([
			{ emoji: 'laugh', count: 3 },
			{ emoji: 'love', count: 2 }
		]);
		const removed = withMyReaction(s, null);
		expect(removed.total).toBe(4);
		expect(removed.mine).toBeNull();
		const added = withMyReaction(null, 'pray');
		expect(added).toMatchObject({ total: 1, mine: 'pray', counts: [{ emoji: 'pray', count: 1 }] });
		expect(withMyReaction(s, 'love')).toBe(s);
	});

	it('builds a summary from the full list (single photos of a post)', () => {
		const sum = summaryFromList(
			{
				people: [
					{ person: { user_id: 'me', display_name: 'Ben' }, emoji: 'wow', at: '' },
					{ person: { user_id: 'a', display_name: 'Anna' }, emoji: 'love', at: '' }
				],
				frames: [{ frame_id: 'f', frame_name: 'Oma', reaction: 'love' }]
			},
			'me'
		);
		expect(sum.total).toBe(3);
		expect(sum.mine).toBe('wow');
		expect(sum.counts[0]).toEqual({ emoji: 'love', count: 2 });
	});
});

describe('threads', () => {
	it('nests replies under their top comment, in id order', () => {
		const nodes = nestComments([comment(3, 1), comment(1), comment(2), comment(4, 2), comment(5, 1)]);
		expect(nodes.map((n) => n.comment.id)).toEqual([id(1), id(2)]);
		expect(nodes[0]!.replies.map((r) => r.id)).toEqual([id(3), id(5)]);
		expect(nodes[1]!.replies.map((r) => r.id)).toEqual([id(4)]);
	});

	it('keeps replies whose parent is missing as their own rows', () => {
		expect(nestComments([comment(4, 2)]).map((n) => n.comment.id)).toEqual([id(4)]);
	});

	it('upserts, removes with replies, and finds the newest person comment', () => {
		let list = [comment(1), comment(3)];
		list = upsertComment(list, comment(2));
		expect(list.map((c) => c.id)).toEqual([id(1), id(2), id(3)]);
		list = upsertComment(list, { ...comment(2), body: 'edited' });
		expect(list[1]!.body).toBe('edited');
		list = upsertComment(list, comment(4, 1));
		expect(removeComment(list, id(1)).map((c) => c.id)).toEqual([id(2), id(3)]);
		expect(lastPersonCommentId([...list, comment(9, undefined, { kind: 'frame', author: null })])).toBe(id(4));
		expect(lastPersonCommentId([])).toBeNull();
	});
});

describe('mentions', () => {
	it('finds the "@name" being typed', () => {
		expect(mentionQuery('Hi @An', 6)).toEqual({ start: 3, query: 'An' });
		expect(mentionQuery('@', 1)).toEqual({ start: 0, query: '' });
		expect(mentionQuery('mail@example', 12)).toBeNull();
		expect(mentionQuery('Hi @Anna Maria Jo Ex', 20)).toBeNull();
		expect(mentionQuery('Hi @ An', 7)).toBeNull();
		expect(mentionQuery('(@Ben', 5)).toEqual({ start: 1, query: 'Ben' });
	});

	it('matches names by any word, best first', () => {
		const people = [{ display_name: 'Anna Peeters' }, { display_name: 'Ben' }, { display_name: 'Oma Anneke' }];
		expect(matchPeople(people, 'an').map((p) => p.display_name)).toEqual(['Anna Peeters', 'Oma Anneke']);
		expect(matchPeople(people, '').length).toBe(3);
		expect(matchPeople(people, 'zz')).toEqual([]);
	});

	it('inserts a mention with its span in UTF-16 units', () => {
		const r = insertMention('😀 hi @An', [], 6, 9, { user_id: 'a', display_name: 'Anna' });
		expect(r.text).toBe('😀 hi @Anna ');
		expect(r.mentions).toEqual([{ user_id: 'a', start: 6, length: 5 }]);
		expect(r.text.slice(6, 11)).toBe('@Anna');
		expect(r.caret).toBe(12);
	});

	it('moves later mentions when one is inserted before them', () => {
		const text = '@Ben and @';
		const r = insertMention(text, [{ user_id: 'b', start: 0, length: 4 }], 9, 10, { user_id: 'a', display_name: 'Anna' });
		expect(r.text).toBe('@Ben and @Anna ');
		expect(r.mentions).toEqual([
			{ user_id: 'b', start: 0, length: 4 },
			{ user_id: 'a', start: 9, length: 5 }
		]);
		const before = insertMention('@ and @Ben', [{ user_id: 'b', start: 6, length: 4 }], 0, 1, { user_id: 'a', display_name: 'Anna' });
		expect(before.text).toBe('@Anna and @Ben');
		expect(before.mentions[1]).toEqual({ user_id: 'b', start: 10, length: 4 });
	});

	it('keeps spans valid across edits and drops the ones edited inside', () => {
		const m = [{ user_id: 'a', start: 4, length: 5 }];
		expect(reconcileMentions('Hi, @Anna!', 'Hey, @Anna!', [{ user_id: 'a', start: 4, length: 5 }])).toEqual([{ user_id: 'a', start: 5, length: 5 }]);
		expect(reconcileMentions('Hi, @Anna!', 'Hi, @Anna! ok', [{ user_id: 'a', start: 4, length: 5 }])).toEqual([{ user_id: 'a', start: 4, length: 5 }]);
		expect(reconcileMentions('Hi, @Anna!', 'Hi, @Ana!', m)).toEqual([]);
		expect(reconcileMentions('same', 'same', m)).toBe(m);
	});

	it('cleans and trims for the server', () => {
		expect(cleanMentions('@A @B', [{ user_id: 'b', start: 3, length: 2 }, { user_id: 'a', start: 0, length: 2 }, { user_id: 'x', start: 1, length: 2 }, { user_id: 'y', start: 4, length: 9 }])).toEqual([
			{ user_id: 'a', start: 0, length: 2 },
			{ user_id: 'b', start: 3, length: 2 }
		]);
		expect(trimWithMentions('  hi @Ben  ', [{ user_id: 'b', start: 5, length: 4 }])).toEqual({ body: 'hi @Ben', mentions: [{ user_id: 'b', start: 3, length: 4 }] });
	});

	it('renders text, mentions and links', () => {
		expect(segments('Look @Anna: https://example.org/a.', [{ user_id: 'a', start: 5, length: 5 }])).toEqual([
			{ kind: 'text', text: 'Look ' },
			{ kind: 'mention', text: '@Anna', userId: 'a' },
			{ kind: 'text', text: ': ' },
			{ kind: 'link', text: 'https://example.org/a', href: 'https://example.org/a' },
			{ kind: 'text', text: '.' }
		]);
		expect(segments(null, [])).toEqual([]);
		expect(linkify('see (https://x.org/a_(b)) now').find((s) => s.kind === 'link')).toMatchObject({ href: 'https://x.org/a_(b)' });
		expect(linkify('javascript:alert(1)')).toEqual([{ kind: 'text', text: 'javascript:alert(1)' }]);
		expect(firstLink('a http://one.be b https://two.be')).toBe('http://one.be');
		expect(firstLink('nothing')).toBeNull();
	});

	it('counts code points like the server', () => {
		expect(codePoints('😀😀')).toBe(2);
		expect('😀😀'.length).toBe(4);
	});
});

describe('timeline', () => {
	const post = (n: number, prompt?: string): TimelinePost => ({
		id: id(n),
		kind: 'feed',
		feed_name: 'Peeters',
		poster: { user_id: 'u', display_name: 'U' },
		created_at: '',
		items: [],
		prompt: prompt ? { id: prompt, title: `P ${prompt}` } : null,
		thread: { post_id: id(n), state: 'open', comment_count: 0, latest_comments: [], reactions: { total: 0, counts: [], people: [], frames: [] } }
	});

	it('groups consecutive answers to the same prompt', () => {
		const rows = groupTimeline([post(9, 'a'), post(8, 'a'), post(7, 'a'), post(6), post(5, 'b'), post(4, 'a')]);
		expect(rows.map((r) => (r.kind === 'prompt' ? `prompt:${r.prompt.id}:${r.posts.length}` : r.post.id))).toEqual(['prompt:a:3', id(6), id(5), id(4)]);
	});

	it('appends pages without duplicates and labels the place', () => {
		expect(appendPosts([post(3), post(2)], [post(2), post(1)]).map((p) => p.id)).toEqual([id(3), id(2), id(1)]);
		expect(postPlace({ kind: 'frame', frame_name: 'Oma’s frame' })).toEqual({ kind: 'frame', name: 'Oma’s frame' });
		expect(postPlace({ kind: 'feed', feed_name: 'Peeters', circle_name: 'Cousins' })).toEqual({ kind: 'circle', name: 'Peeters', circle: 'Cousins' });
		expect(postPlace({ kind: 'feed', feed_name: 'Peeters' })).toEqual({ kind: 'feed', name: 'Peeters' });
	});

	it('shows former members by the localized label', () => {
		expect(personName({ user_id: 'x', display_name: '', former_member: true }, 'Former member')).toBe('Former member');
		expect(personName({ user_id: 'x', display_name: 'Anna' }, 'Former member')).toBe('Anna');
		expect(personName(null, 'Former member')).toBe('Former member');
	});
});

describe('badge', () => {
	it('puts the count in the title once', () => {
		expect(badgeTitle('Home · kinframe', 3)).toBe('(3) Home · kinframe');
		expect(badgeTitle('(3) Home · kinframe', 4)).toBe('(4) Home · kinframe');
		expect(badgeTitle('(3) Home · kinframe', 0)).toBe('Home · kinframe');
		expect(badgeTitle('Home', 120)).toBe('(99+) Home');
		expect(badgeTitle('(99+) Home', 0)).toBe('Home');
	});
});

describe('posterName', () => {
	const named = (n: string) => `${n} (former member)`;
	it('names handed-over posts by the frozen name on their items', () => {
		const poster = { user_id: 'u1', display_name: '', former_member: true };
		expect(posterName({ poster, items: [{ former_sender_name: null }, { former_sender_name: 'Tom' }] }, 'Former member', named)).toBe('Tom (former member)');
		expect(posterName({ poster, items: [] }, 'Former member', named)).toBe('Former member');
		expect(posterName({ poster: { user_id: 'u2', display_name: 'Lies' }, items: [{ former_sender_name: 'Tom' }] }, 'Former member', named)).toBe('Lies');
	});
});
