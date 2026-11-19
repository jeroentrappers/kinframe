// Feeds and subscriptions (docs/spec/feeds.md): permissions, receipts, request bodies. Pure: unit-tested in
// feeds.test.ts.

import type {
	FeedInviteCreate,
	FeedInvitePurpose,
	FeedMember,
	FeedPost,
	FeedReaction,
	FeedReceiptSummary,
	FeedRole,
	ReplyPreset,
	Subscription,
	SubscriptionHistory
} from './api/client';
import type { Reaction } from './logic';

// ---------------------------------------------------------------- roles (feeds.md §4.1)

export const FEED_ROLE_ORDER: FeedRole[] = ['owner', 'admin', 'poster', 'member'];

/** Every role but `member` ("Reader": reads, comments and chats, docs/spec/family-social.md §3) posts photos. */
export function canPostToFeed(role: FeedRole | undefined | null): boolean {
	return !!role && role !== 'member';
}

export function isFeedAdmin(role: FeedRole | undefined | null): boolean {
	return role === 'owner' || role === 'admin';
}

/** Owner and admins create invites; posters too when the feed has `posters_can_invite`. */
export function canInviteToFeed(feed: { role: FeedRole; posters_can_invite?: boolean }): boolean {
	return isFeedAdmin(feed.role) || feed.posters_can_invite === true;
}

/** Change role or remove: the owner anyone but themself; admins posters and members (readers) only. */
export function canEditFeedMember(actor: { role: FeedRole; userId: string | undefined }, member: Pick<FeedMember, 'user_id' | 'role'>): boolean {
	if (member.user_id === actor.userId || member.role === 'owner') return false;
	if (actor.role === 'owner') return true;
	return actor.role === 'admin' && (member.role === 'poster' || member.role === 'member');
}

/** The poster unsends; owner and admins remove anyone's post. */
export function canRemovePost(role: FeedRole, post: Pick<FeedPost, 'poster'>, meId: string | undefined): boolean {
	return isFeedAdmin(role) || (meId !== undefined && post.poster.user_id === meId);
}

/**
 * Whether receipts and reactions of a post may be shown (§4.1, §7). The server sends them as null when not; this is
 * the same rule, for deciding whether to ask for the per-frame detail.
 */
export function receiptsVisible(role: FeedRole, membersSeeReactions: boolean, own: boolean): boolean {
	return own || isFeedAdmin(role) || membersSeeReactions;
}

export function sortFeedMembers<T extends Pick<FeedMember, 'role' | 'display_name'>>(members: T[]): T[] {
	return [...members].sort(
		(a, b) => FEED_ROLE_ORDER.indexOf(a.role) - FEED_ROLE_ORDER.indexOf(b.role) || a.display_name.localeCompare(b.display_name)
	);
}

// ---------------------------------------------------------------- receipts (feeds.md §7)

export type ReceiptStage = 'none' | 'waiting' | 'delivered' | 'seen';

export interface ReceiptView {
	/** Frames holding the post (active subscriptions); paused ones are not counted here. */
	total: number;
	seen: number;
	/** Delivered, including seen. */
	delivered: number;
	awaiting: number;
	paused: number;
	removed: number;
	stage: ReceiptStage;
	/** 0…1 for a progress bar. */
	progress: number;
}

/** Null when the viewer may not see receipts (the server sent null). */
export function receiptView(s: FeedReceiptSummary | null | undefined): ReceiptView | null {
	if (!s) return null;
	const total = Math.max(0, s.frames);
	const seen = Math.min(total, Math.max(0, s.seen));
	const delivered = Math.min(total, Math.max(seen, s.delivered));
	const stage: ReceiptStage = total === 0 ? 'none' : seen === total ? 'seen' : delivered === total ? 'delivered' : 'waiting';
	return {
		total,
		seen,
		delivered,
		awaiting: s.awaiting_approval ?? 0,
		paused: s.paused ?? 0,
		removed: s.removed ?? 0,
		stage,
		progress: total === 0 ? 0 : (delivered + seen) / (2 * total)
	};
}

/** Which sentence the summary shows: "Seen on 4 of 5 frames", "Delivered to 3 of 5 frames", "Not on a frame yet". */
export function receiptHeadline(v: ReceiptView): { key: 'seen' | 'delivered' | 'waiting' | 'none'; n: number; total: number } {
	if (v.total === 0) return { key: 'none', n: 0, total: 0 };
	if (v.seen > 0) return { key: 'seen', n: v.seen, total: v.total };
	if (v.delivered > 0) return { key: 'delivered', n: v.delivered, total: v.total };
	return { key: 'waiting', n: 0, total: v.total };
}

export interface ReactionGroup {
	reaction: Reaction;
	frames: string[];
}

/** "❤ Oma's frame, Kitchen": one group per reaction, frame names once each, most frames first. */
export function groupReactions(reactions: FeedReaction[] | null | undefined): {
	reactions: ReactionGroup[];
	replies: { preset: ReplyPreset; frame: string }[];
} {
	const groups = new Map<Reaction, Set<string>>();
	const replies: { preset: ReplyPreset; frame: string }[] = [];
	for (const r of reactions ?? []) {
		if (r.reaction) {
			const set = groups.get(r.reaction as Reaction) ?? new Set<string>();
			set.add(r.frame_name);
			groups.set(r.reaction as Reaction, set);
		}
		for (const preset of r.replies ?? []) replies.push({ preset, frame: r.frame_name });
	}
	return {
		reactions: [...groups.entries()].map(([reaction, f]) => ({ reaction, frames: [...f] })).sort((a, b) => b.frames.length - a.frames.length),
		replies
	};
}

// ---------------------------------------------------------------- recipients (feeds.md §6.1)

export const MAX_FEEDS_PER_SEND = 20;

export type RecipientProblem = 'none' | 'tooManyFeeds' | null;

/** A send needs at least one frame or feed; feeds are capped at 20 per send. */
export function recipientProblem(frameIds: string[], feedIds: string[]): RecipientProblem {
	if (frameIds.length === 0 && feedIds.length === 0) return 'none';
	if (feedIds.length > MAX_FEEDS_PER_SEND) return 'tooManyFeeds';
	return null;
}

/** The recipient part of SendCreate (and forward): `feed_ids` only when there are any, so older servers are unaffected. */
export function recipientsBody(frameIds: string[], feedIds: string[]): { frame_ids: string[]; feed_ids?: string[] } {
	const unique = (ids: string[]) => [...new Set(ids)];
	return feedIds.length > 0 ? { frame_ids: unique(frameIds), feed_ids: unique(feedIds) } : { frame_ids: unique(frameIds) };
}

// ---------------------------------------------------------------- invites

export type InviteUses = 'single' | 'unlimited';

export function inviteBody(form: { purpose: FeedInvitePurpose; expiresInHours: number; uses: InviteUses; note: string }): FeedInviteCreate {
	const note = form.note.trim();
	return {
		purpose: form.purpose,
		expires_in_hours: Math.min(720, Math.max(1, Math.round(form.expiresInHours))),
		max_uses: form.uses === 'single' ? 1 : null,
		...(note ? { note: note.slice(0, 80) } : {})
	};
}

// ---------------------------------------------------------------- subscriptions (feeds.md §5)

export const HISTORY_MAX = 1000;
export const HISTORY_DEFAULT_N = 50;

export interface HistoryChoice {
	history: SubscriptionHistory;
	/** Used with `last_n`. */
	count: number;
}

export function clampHistoryCount(n: number): number {
	if (!Number.isFinite(n)) return HISTORY_DEFAULT_N;
	return Math.min(HISTORY_MAX, Math.max(1, Math.round(n)));
}

/** The history part of SubscriptionRequest / SubscriptionUpdate / FeedInviteAccept. */
export function historyBody(c: HistoryChoice): { history: SubscriptionHistory; history_count?: number } {
	return c.history === 'last_n' ? { history: 'last_n', history_count: clampHistoryCount(c.count) } : { history: c.history };
}

export function historyOf(sub: Pick<Subscription, 'history' | 'history_count'>): HistoryChoice {
	return { history: sub.history, count: sub.history_count ?? HISTORY_DEFAULT_N };
}

export function sameHistory(a: HistoryChoice, b: HistoryChoice): boolean {
	return a.history === b.history && (a.history !== 'last_n' || clampHistoryCount(a.count) === clampHistoryCount(b.count));
}

/** Pending first, then active/paused by feed or frame name, then ended (rejected/removed) newest first. */
export function groupSubscriptions(subs: Subscription[], by: 'feed' | 'frame'): {
	pending: Subscription[];
	live: Subscription[];
	ended: Subscription[];
} {
	const name = (s: Subscription) => (by === 'feed' ? s.feed.name : s.frame.name);
	return {
		pending: subs.filter((s) => s.status === 'pending').sort((a, b) => a.created_at.localeCompare(b.created_at)),
		live: subs.filter((s) => s.status === 'active' || s.status === 'paused').sort((a, b) => name(a).localeCompare(name(b))),
		ended: subs
			.filter((s) => s.status === 'rejected' || s.status === 'removed')
			.sort((a, b) => (b.ended_at ?? b.created_at).localeCompare(a.ended_at ?? a.created_at))
	};
}

/** Backfill progress 0…1, or null when nothing runs. */
export function backfillProgress(sub: Pick<Subscription, 'backfill'>): number | null {
	const b = sub.backfill;
	if (!b || b.state === 'idle' || b.state === undefined) return null;
	if (!b.total) return 0;
	return Math.min(1, (b.placed ?? 0) / b.total);
}

// ---------------------------------------------------------------- frame side: feed mode (slideshow.feed_id)

/** One select for "what the slideshow plays": '' (everything), 'album:<id>' or 'feed:<id>'. */
export function slideshowSource(s: { album_id?: string | null; feed_id?: string | null } | undefined): string {
	if (s?.feed_id) return `feed:${s.feed_id}`;
	if (s?.album_id) return `album:${s.album_id}`;
	return '';
}

/** Album and feed mode exclude each other: choosing one clears the other. */
export function slideshowSourcePatch(value: string): { album_id: string | null; feed_id: string | null } {
	if (value.startsWith('feed:')) return { album_id: null, feed_id: value.slice(5) || null };
	if (value.startsWith('album:')) return { album_id: value.slice(6) || null, feed_id: null };
	return { album_id: null, feed_id: null };
}

// ---------------------------------------------------------------- live updates

/** Apply `feed_post.updated` to a list of posts in place. Returns true when a post changed. */
export function applyPostSummary(posts: FeedPost[], e: { post_id: string; summary: FeedReceiptSummary }): boolean {
	const p = posts.find((x) => x.id === e.post_id);
	if (!p || p.receipts === null) return false; // the viewer doesn't see this post's receipts
	p.receipts = e.summary;
	return true;
}

/** Initials for a feed without a cover. */
export function feedInitials(name: string): string {
	const words = name.trim().split(/\s+/).filter(Boolean);
	const first = [...(words[0] ?? '')][0] ?? '';
	const second = words.length > 1 ? ([...(words[words.length - 1] ?? '')][0] ?? '') : ([...(words[0] ?? '')][1] ?? '');
	return (first + second).toUpperCase();
}
