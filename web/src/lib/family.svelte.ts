// The simple family app (docs/spec/simple-app.md): the person's families (home family, Home's filter), each family's
// people and frames, the frames' families, and the per-device view choice (normal or simple).

import { api, call, type FeedMember, type FeedSummary, type Subscription } from './api/client';
import { HISTORY_DEFAULT_N, historyBody } from './feeds';
import { newIdempotencyKey } from './logic';
import { auth } from './auth.svelte';
import { framesOf, rankFamilies, type FamilyFrame } from './family';
import { feeds } from './feeds.svelte';

export { dedupePosts, needsGettingStarted, surnameOf, type FamilyFrame } from './family';

const FILTER_KEY = 'kinframe.homeFilter';
const SIMPLE_KEY = 'kinframe.simpleView';
const ASKED_KEY = 'kinframe.viewAsked';

function read(key: string): string | null {
	try {
		return localStorage.getItem(key);
	} catch {
		return null;
	}
}

function write(key: string, value: string | null) {
	try {
		if (value === null) localStorage.removeItem(key);
		else localStorage.setItem(key, value);
	} catch {
		/* storage unavailable: the choice lasts for this visit */
	}
}

/**
 * The person's families (spec §2, §3): there is no "current family". Home and the composer work across all of them;
 * the home family (posted to most) is the composer default and listed first. Home can be filtered to one family, and
 * that choice is remembered on this device.
 */
class Families {
	#filter = $state<string | null>(typeof window === 'undefined' ? null : read(FILTER_KEY));

	/** Home family first. */
	get ranked(): FeedSummary[] {
		const me = auth.me?.id;
		const mine = Object.fromEntries(feeds.list.map((f) => [f.id, (directory.members[f.id] ?? []).find((m) => m.user_id === me)]));
		return rankFamilies(feeds.list, mine);
	}

	get home(): FeedSummary | null {
		return this.ranked[0] ?? null;
	}

	get several(): boolean {
		return feeds.list.length > 1;
	}

	/** Home's filter chip: one family, or null for All. */
	get filter(): FeedSummary | null {
		return this.several && this.#filter ? (feeds.get(this.#filter) ?? null) : null;
	}

	setFilter(id: string | null) {
		this.#filter = id;
		write(FILTER_KEY, id);
	}
}

export const families = new Families();

/** Where the Family tab goes: straight to the profile with one family, the list with several. */
export function familyHref(): string {
	if (feeds.list.length === 1) return `/feeds/${feeds.list[0]!.id}`;
	return feeds.list.length > 1 ? '/family' : '/home';
}

class ViewChoice {
	simple = $state(typeof window === 'undefined' ? false : read(SIMPLE_KEY) === '1');
	asked = $state(typeof window === 'undefined' ? true : read(ASKED_KEY) === '1');

	set(simple: boolean) {
		this.simple = simple;
		this.asked = true;
		write(SIMPLE_KEY, simple ? '1' : null);
		write(ASKED_KEY, '1');
	}
}

/** Normal or simple view (spec §6): a per-device setting, asked once after joining someone else's family. */
export const view = new ViewChoice();

/** A family's people and the frames that show it (active and pending), loaded once per family and refreshed on demand. */
class FamilyDirectory {
	members = $state<Record<string, FeedMember[]>>({});
	frames = $state<Record<string, FamilyFrame[]>>({});
	#inflight = new Map<string, Promise<void>>();

	load(feedId: string, force = false): Promise<void> {
		if (!force && this.members[feedId] && this.frames[feedId]) return Promise.resolve();
		let p = this.#inflight.get(feedId);
		if (p) return p;
		p = (async () => {
			const [members, subs] = await Promise.all([
				call(api.GET('/v1/feeds/{feedId}/members', { params: { path: { feedId } } })).catch(() => this.members[feedId] ?? []),
				call(api.GET('/v1/feeds/{feedId}/subscriptions', { params: { path: { feedId } } })).catch(() => null)
			]);
			this.members = { ...this.members, [feedId]: members };
			if (subs) this.frames = { ...this.frames, [feedId]: framesOf(subs) };
			else if (!this.frames[feedId]) this.frames = { ...this.frames, [feedId]: [] };
		})().finally(() => this.#inflight.delete(feedId));
		this.#inflight.set(feedId, p);
		return p;
	}

	setOnline(frameId: string, online: boolean, lastSeen: string | null) {
		for (const list of Object.values(this.frames)) {
			const f = list.find((x) => x.id === frameId);
			if (f) {
				f.online = online;
				f.last_seen_at = lastSeen ?? f.last_seen_at;
			}
		}
	}

	reset() {
		this.members = {};
		this.frames = {};
	}
}

export const directory = new FamilyDirectory();

/**
 * "Add a frame" (spec §5, §7): ask for the frame to show the family, with the last 50 posts and no questions. Active at
 * once when you manage the frame and are in the family; otherwise it waits for an admin of the family (`pending`).
 */
export async function addFrameToFamily(feedId: string, frameId: string): Promise<Subscription> {
	const sub = await call(
		api.POST('/v1/feeds/{feedId}/subscriptions', {
			params: { path: { feedId }, header: { 'Idempotency-Key': newIdempotencyKey() } },
			body: { frame_id: frameId, ...historyBody({ history: 'last_n', count: HISTORY_DEFAULT_N }) }
		})
	);
	void directory.load(feedId, true);
	void shows.load(frameId, true);
	void feeds.load(true);
	return sub;
}

/**
 * The getting-started flow (spec §8) stays on screen while it runs, also once the new family exists:
 * Start a family → name → "Add Oma's frame now?" → invite.
 */
class StartFlow {
	step = $state<'choose' | 'name' | 'join' | 'frame' | 'invite' | null>(null);
	feedId = $state<string | null>(null);

	begin(step: 'choose' | 'name' | 'join' = 'choose') {
		this.step = step;
		this.feedId = null;
	}

	end() {
		this.step = null;
		this.feedId = null;
	}
}

export const startFlow = new StartFlow();

/** Which families each frame shows (its active and pending subscriptions), for the frame cards and Frame settings. */
class FrameShows {
	byFrame = $state<Record<string, Subscription[]>>({});
	#inflight = new Map<string, Promise<void>>();

	load(frameId: string, force = false): Promise<void> {
		if (!force && this.byFrame[frameId]) return Promise.resolve();
		let p = this.#inflight.get(frameId);
		if (p) return p;
		p = call(api.GET('/v1/frames/{frameId}/subscriptions', { params: { path: { frameId } } }))
			.then((subs) => {
				this.byFrame = { ...this.byFrame, [frameId]: subs.filter((s) => s.status === 'active' || s.status === 'paused' || s.status === 'pending') };
			})
			.catch(() => {
				if (!this.byFrame[frameId]) this.byFrame = { ...this.byFrame, [frameId]: [] };
			})
			.finally(() => this.#inflight.delete(frameId));
		this.#inflight.set(frameId, p);
		return p;
	}

	reset() {
		this.byFrame = {};
	}
}

export const shows = new FrameShows();
