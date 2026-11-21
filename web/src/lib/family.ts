// The simple family app (docs/spec/simple-app.md): pure helpers, unit-tested in family.test.ts.

import type { FeedSummary, Subscription } from './api/client';

export interface MyMembership {
	/** When I joined the family (FeedMember.joined_at). */
	joined_at?: string | null;
}

/**
 * The home family (spec §3): the family the person posts to most (`FeedSummary.my_post_count`), ties broken by the
 * one they created, else the oldest membership (when the membership isn't known yet, the family's age stands in).
 */
export function rankFamilies<T extends Pick<FeedSummary, 'id' | 'name' | 'role' | 'created_at' | 'my_post_count'>>(list: T[], mine: Record<string, MyMembership | undefined> = {}): T[] {
	const joined = (f: T) => mine[f.id]?.joined_at ?? f.created_at ?? '';
	return [...list].sort(
		(a, b) =>
			(b.my_post_count ?? 0) - (a.my_post_count ?? 0) ||
			Number(b.role === 'owner') - Number(a.role === 'owner') ||
			joined(a).localeCompare(joined(b)) ||
			a.name.localeCompare(b.name)
	);
}

/**
 * A post sent to several families comes back once per family: show it once (spec §3.1). The same send has the same
 * poster and the same items.
 */
export function dedupePosts<T extends { id: string; poster: { user_id?: string | null }; items: { id: string }[]; send_id?: string | null }>(posts: T[]): T[] {
	const seen = new Set<string>();
	return posts.filter((p) => {
		const key = p.send_id ? `s:${p.send_id}` : p.items.length ? `i:${p.poster.user_id ?? ''}:${p.items.map((i) => i.id).join(',')}` : `p:${p.id}`;
		if (seen.has(key)) return false;
		seen.add(key);
		return true;
	});
}

/** "Peeters" from "Jan Peeters": the surname for "Peeters family", when the name has one. */
export function surnameOf(displayName: string | null | undefined): string | null {
	const parts = (displayName ?? '').trim().split(/\s+/).filter(Boolean);
	return parts.length >= 2 ? parts[parts.length - 1]! : null;
}

export interface FamilyFrame {
	id: string;
	name: string;
	online: boolean;
	last_seen_at: string | null;
	status: Subscription['status'];
	subscription_id: string;
}

/** Active (and paused) frames first, then the ones waiting for an admin; ended subscriptions are left out. */
export function framesOf(subs: Subscription[]): FamilyFrame[] {
	const live = subs.filter((s) => s.status === 'active' || s.status === 'paused' || s.status === 'pending');
	const order = (s: Subscription) => (s.status === 'pending' ? 1 : 0);
	return [...live]
		.sort((a, b) => order(a) - order(b) || a.frame.name.localeCompare(b.frame.name))
		.map((s) => ({
			id: s.frame.id,
			name: s.frame.name,
			online: s.frame.online === true,
			last_seen_at: s.frame.last_seen_at ?? null,
			status: s.status,
			subscription_id: s.id
		}));
}


/** Where someone without a family and without a frame only gets the getting-started choices (spec §8). */
const GATED = /^\/(home|family|frames(?!\/add)|send|chats)(\/|$)/;

/**
 * Getting started (spec §8): someone with neither a family nor a frame (owner, admin or member) sees only the two
 * choices on the app's main screens. Sent history (Ask to keep answers), settings, invites and pairing stay open,
 * and someone on a frame gets the normal app. Undecided until both lists are known.
 */
export function needsGettingStarted(path: string, s: { feedsLoaded: boolean; families: number; framesKnown: boolean; frames: number }): boolean {
	if (!s.feedsLoaded || !s.framesKnown || s.families > 0 || s.frames > 0) return false;
	return GATED.test(path);
}
