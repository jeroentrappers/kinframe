import { describe, expect, it } from 'vitest';
import type { FeedPost, FeedReaction, Subscription } from './api/client';
import {
	applyPostSummary,
	backfillProgress,
	canEditFeedMember,
	canInviteToFeed,
	canRemovePost,
	clampHistoryCount,
	feedInitials,
	groupReactions,
	groupSubscriptions,
	historyBody,
	historyOf,
	inviteBody,
	receiptHeadline,
	receiptsVisible,
	receiptView,
	recipientProblem,
	recipientsBody,
	sameHistory,
	slideshowSource,
	slideshowSourcePatch,
	sortFeedMembers
} from './feeds';

const ME = 'u-me';

describe('feed roles', () => {
	it('lets owner and admins invite, posters only with posters_can_invite', () => {
		expect(canInviteToFeed({ role: 'owner' })).toBe(true);
		expect(canInviteToFeed({ role: 'admin' })).toBe(true);
		expect(canInviteToFeed({ role: 'poster' })).toBe(false);
		expect(canInviteToFeed({ role: 'poster', posters_can_invite: true })).toBe(true);
	});

	it('lets the owner edit anyone else, admins posters only', () => {
		const poster = { user_id: 'p', role: 'poster' as const };
		const admin = { user_id: 'a', role: 'admin' as const };
		const owner = { user_id: 'o', role: 'owner' as const };
		expect(canEditFeedMember({ role: 'owner', userId: 'o' }, poster)).toBe(true);
		expect(canEditFeedMember({ role: 'owner', userId: 'o' }, admin)).toBe(true);
		expect(canEditFeedMember({ role: 'owner', userId: 'o' }, owner)).toBe(false);
		expect(canEditFeedMember({ role: 'admin', userId: 'a2' }, poster)).toBe(true);
		expect(canEditFeedMember({ role: 'admin', userId: 'a2' }, admin)).toBe(false);
		expect(canEditFeedMember({ role: 'admin', userId: 'a' }, admin)).toBe(false);
		expect(canEditFeedMember({ role: 'poster', userId: 'x' }, poster)).toBe(false);
	});

	it('lets the poster unsend and admins remove any post', () => {
		const own = { poster: { user_id: ME, display_name: 'Me' } };
		const other = { poster: { user_id: 'u-ben', display_name: 'Ben' } };
		expect(canRemovePost('poster', own, ME)).toBe(true);
		expect(canRemovePost('poster', other, ME)).toBe(false);
		expect(canRemovePost('admin', other, ME)).toBe(true);
		expect(canRemovePost('owner', other, undefined)).toBe(true);
		expect(canRemovePost('poster', own, undefined)).toBe(false);
	});

	it('shows receipts to the poster, admins, or everyone with members_see_reactions', () => {
		expect(receiptsVisible('poster', false, true)).toBe(true);
		expect(receiptsVisible('poster', false, false)).toBe(false);
		expect(receiptsVisible('poster', true, false)).toBe(true);
		expect(receiptsVisible('admin', false, false)).toBe(true);
		expect(receiptsVisible('owner', false, false)).toBe(true);
	});

	it('sorts owner, admins, posters, then by name', () => {
		const sorted = sortFeedMembers([
			{ role: 'poster' as const, display_name: 'Zoe' },
			{ role: 'admin' as const, display_name: 'Ben' },
			{ role: 'poster' as const, display_name: 'Anna' },
			{ role: 'owner' as const, display_name: 'Carla' }
		]);
		expect(sorted.map((m) => m.display_name)).toEqual(['Carla', 'Ben', 'Anna', 'Zoe']);
	});
});

describe('receipts', () => {
	it('is null when the viewer may not see them', () => {
		expect(receiptView(null)).toBeNull();
		expect(receiptView(undefined)).toBeNull();
	});

	it('reads "seen on 4 of 5 frames", leaving paused frames out of the total', () => {
		const v = receiptView({ frames: 5, delivered: 5, seen: 4, paused: 1, awaiting_approval: 0, removed: 0 })!;
		expect(v).toMatchObject({ total: 5, seen: 4, delivered: 5, paused: 1, stage: 'delivered' });
		expect(receiptHeadline(v)).toEqual({ key: 'seen', n: 4, total: 5 });
	});

	it('falls back to delivered, then on its way, then no frames', () => {
		expect(receiptHeadline(receiptView({ frames: 3, delivered: 2, seen: 0 })!)).toEqual({ key: 'delivered', n: 2, total: 3 });
		expect(receiptHeadline(receiptView({ frames: 3, delivered: 0, seen: 0 })!)).toEqual({ key: 'waiting', n: 0, total: 3 });
		const none = receiptView({ frames: 0, delivered: 0, seen: 0 })!;
		expect(none.stage).toBe('none');
		expect(receiptHeadline(none).key).toBe('none');
	});

	it('is seen when every frame saw it, and clamps odd counts', () => {
		const v = receiptView({ frames: 2, delivered: 1, seen: 2 })!;
		expect(v.stage).toBe('seen');
		expect(v.delivered).toBe(2);
		expect(v.progress).toBe(1);
		expect(receiptView({ frames: 2, delivered: 9, seen: 9 })!.seen).toBe(2);
	});

	it('groups reactions by emoji with frame names, and lists replies', () => {
		const r = (frame_name: string, reaction: FeedReaction['reaction'], replies: FeedReaction['replies'] = []): FeedReaction => ({
			frame_id: frame_name,
			frame_name,
			item_id: 'i',
			placement_id: `p-${frame_name}`,
			reaction,
			replies
		});
		const g = groupReactions([r('Oma', 'love'), r('Kitchen', 'laugh'), r('Opa', 'love', ['thanks']), r('Oma', 'love')]);
		expect(g.reactions).toEqual([
			{ reaction: 'love', frames: ['Oma', 'Opa'] },
			{ reaction: 'laugh', frames: ['Kitchen'] }
		]);
		expect(g.replies).toEqual([{ preset: 'thanks', frame: 'Opa' }]);
		expect(groupReactions(null)).toEqual({ reactions: [], replies: [] });
	});

	it('applies feed_post.updated only to posts whose receipts the viewer sees', () => {
		const post = (id: string, receipts: FeedPost['receipts']): FeedPost => ({
			id,
			feed_id: 'f',
			poster: { user_id: ME, display_name: 'Me' },
			created_at: '2026-11-18T10:00:00Z',
			items: [],
			receipts
		});
		const posts = [post('a', { frames: 2, delivered: 0, seen: 0 }), post('b', null)];
		expect(applyPostSummary(posts, { post_id: 'a', summary: { frames: 2, delivered: 2, seen: 1 } })).toBe(true);
		expect(posts[0]!.receipts).toEqual({ frames: 2, delivered: 2, seen: 1 });
		expect(applyPostSummary(posts, { post_id: 'b', summary: { frames: 2, delivered: 2, seen: 1 } })).toBe(false);
		expect(posts[1]!.receipts).toBeNull();
		expect(applyPostSummary(posts, { post_id: 'zz', summary: { frames: 1, delivered: 1, seen: 1 } })).toBe(false);
	});
});

describe('recipients', () => {
	it('needs at least one frame or feed', () => {
		expect(recipientProblem([], [])).toBe('none');
		expect(recipientProblem(['fr'], [])).toBeNull();
		expect(recipientProblem([], ['fd'])).toBeNull();
		expect(recipientProblem(['fr'], ['fd'])).toBeNull();
		expect(recipientProblem([], Array.from({ length: 21 }, (_, i) => `f${i}`))).toBe('tooManyFeeds');
	});

	it('sends feed_ids only when there are feeds, without duplicates', () => {
		expect(recipientsBody(['a', 'a'], [])).toEqual({ frame_ids: ['a'] });
		expect(recipientsBody(['a'], ['x', 'x'])).toEqual({ frame_ids: ['a'], feed_ids: ['x'] });
		expect(recipientsBody([], ['x'])).toEqual({ frame_ids: [], feed_ids: ['x'] });
	});
});

describe('request bodies', () => {
	it('maps the invite form to FeedInviteCreate', () => {
		expect(inviteBody({ purpose: 'join', expiresInHours: 168, uses: 'single', note: '  for Ben ' })).toEqual({
			purpose: 'join',
			expires_in_hours: 168,
			max_uses: 1,
			note: 'for Ben'
		});
		expect(inviteBody({ purpose: 'subscribe', expiresInHours: 9999, uses: 'unlimited', note: '' })).toEqual({
			purpose: 'subscribe',
			expires_in_hours: 720,
			max_uses: null
		});
	});

	it('maps the history option, with history_count only for last_n', () => {
		expect(historyBody({ history: 'none', count: 50 })).toEqual({ history: 'none' });
		expect(historyBody({ history: 'all', count: 50 })).toEqual({ history: 'all' });
		expect(historyBody({ history: 'last_n', count: 50 })).toEqual({ history: 'last_n', history_count: 50 });
		expect(historyBody({ history: 'last_n', count: 5000 })).toEqual({ history: 'last_n', history_count: 1000 });
		expect(clampHistoryCount(0)).toBe(1);
		expect(clampHistoryCount(Number.NaN)).toBe(50);
		expect(historyOf({ history: 'last_n', history_count: 20 })).toEqual({ history: 'last_n', count: 20 });
		expect(historyOf({ history: 'none', history_count: null })).toEqual({ history: 'none', count: 50 });
		expect(sameHistory({ history: 'none', count: 1 }, { history: 'none', count: 9 })).toBe(true);
		expect(sameHistory({ history: 'last_n', count: 1 }, { history: 'last_n', count: 9 })).toBe(false);
	});
});

describe('subscriptions', () => {
	const sub = (id: string, status: Subscription['status'], name: string, extra: Partial<Subscription> = {}): Subscription => ({
		id,
		status,
		history: 'none',
		feed: { id: `feed-${id}`, name },
		frame: { id: `frame-${id}`, name },
		created_at: `2026-11-0${id}T10:00:00Z`,
		...extra
	});

	it('groups pending, live and ended', () => {
		const g = groupSubscriptions(
			[sub('1', 'active', 'Zed'), sub('2', 'pending', 'B'), sub('3', 'paused', 'Anna'), sub('4', 'removed', 'X'), sub('5', 'rejected', 'Y')],
			'frame'
		);
		expect(g.pending.map((s) => s.id)).toEqual(['2']);
		expect(g.live.map((s) => s.id)).toEqual(['3', '1']);
		expect(g.ended.map((s) => s.id)).toEqual(['5', '4']);
	});

	it('reports backfill progress only while it runs', () => {
		expect(backfillProgress({ backfill: { state: 'idle', placed: 0, total: 0 } })).toBeNull();
		expect(backfillProgress({})).toBeNull();
		expect(backfillProgress({ backfill: { state: 'running', placed: 25, total: 100 } })).toBe(0.25);
		expect(backfillProgress({ backfill: { state: 'queued' } })).toBe(0);
	});
});

describe('feed mode (slideshow.feed_id)', () => {
	it('shares one select with album mode', () => {
		expect(slideshowSource({ album_id: null, feed_id: null })).toBe('');
		expect(slideshowSource({ album_id: 'a1' })).toBe('album:a1');
		expect(slideshowSource({ feed_id: 'f1', album_id: null })).toBe('feed:f1');
		expect(slideshowSource(undefined)).toBe('');
	});

	it('clears the other mode when one is chosen', () => {
		expect(slideshowSourcePatch('feed:f1')).toEqual({ album_id: null, feed_id: 'f1' });
		expect(slideshowSourcePatch('album:a1')).toEqual({ album_id: 'a1', feed_id: null });
		expect(slideshowSourcePatch('')).toEqual({ album_id: null, feed_id: null });
	});
});

it('makes initials for a feed without a cover', () => {
	expect(feedInitials('Peeters kids')).toBe('PK');
	expect(feedInitials('oma')).toBe('OM');
	expect(feedInitials('')).toBe('');
});
