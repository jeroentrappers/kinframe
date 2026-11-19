// In-memory leaving and remote families for the mock API (README §5.21, §5.22). Hooked into tests/mock-api.ts;
// answers only while discovery has the area's flag, like the server (whose operations answer 501 until built).
import type { Route } from '@playwright/test';
import type { components } from '../src/lib/api/schema';

type S = components['schemas'];

export interface LeavingMock {
	/** Leave previews by scope key (`feed:<id>`, `frame:<id>`). */
	previews: Record<string, S['LeavePreview']>;
	/** What leaving with `photos: take` or taking away answers, by scope key. */
	results: Record<string, S['TakeAwayResult']>;
	former: S['FormerPlaces'];
	/** Taken-away groups by scope key. */
	takenAway: Record<string, S['TakenAwayGroup'][]>;
	/** Placements of a taking (`listPlacements?visibility=taken_away&taking_id=`). */
	takenPlacements: Record<string, S['Placement'][]>;
	/** Keep requests made to the signed-in person, with their items. */
	myRequests: S['KeepRequest'][];
	/** Keep requests made by managers (createKeepRequest). */
	asked: S['KeepRequest'][];
	memories: S['MemoryPreferences'];
	ownership: S['OperatorOwnershipMatches'];
	log: S['OwnershipLogEntry'][];
	remoteSubs: Record<string, S['RemoteSubscription'][]>;
	invitePreview: S['RemoteInvitePreview'] | null;
	/** Blocked servers by feed id, or `operator`. */
	blocked: Record<string, S['BlockedServer'][]>;
	remoteServers: S['RemoteServer'][];
}

export function emptyLeaving(): LeavingMock {
	return {
		previews: {},
		results: {},
		former: { feeds: [], frames: [] },
		takenAway: {},
		takenPlacements: {},
		myRequests: [],
		asked: [],
		memories: { hidden_people: [], hidden_memories: [] },
		ownership: { frames: [], feeds: [] },
		log: [],
		remoteSubs: {},
		invitePreview: null,
		blocked: {},
		remoteServers: []
	};
}

interface Host {
	discovery: S['Discovery'];
	me: S['User'] | null;
	frames: S['Frame'][];
	feeds: S['Feed'][];
	leaving?: LeavingMock;
}

let seq = 0;
const lid = () => `0194a0a0-0000-7000-8000-${String(++seq).padStart(12, '0')}`;
const now = () => new Date().toISOString();
const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
const none = (route: Route) => route.fulfill({ status: 204 });
const problem = (route: Route, status: number, code: string, title: string) =>
	route.fulfill({ status, contentType: 'application/problem+json', body: JSON.stringify({ type: `https://kinframe.app/errors/${code}`, title, status, code }) });
const nothing = (): S['TakeAwayResult'] => ({ taking_id: null, photos_taken_away: 0, comments_deleted: 0, frames: [] });

export async function leavingRoute(state: Host, route: Route, method: string, path: string, url: URL, body: unknown): Promise<unknown | null> {
	const d = state.discovery;
	const lv = (state.leaving ??= emptyLeaving());
	const b = (body ?? {}) as Record<string, unknown>;
	const leavingOn = d.leaving === true;
	const remoteOn = d.remote_families === true;
	const off = () => problem(route, 501, 'feature_disabled', 'Not implemented');
	let m: RegExpMatchArray | null;

	// ---------------------------------------------------------------- leaving with a choice
	if ((m = path.match(/^\/v1\/feeds\/([^/]+)\/leave$/)) && method === 'POST' && leavingOn && b.photos) {
		const id = m[1]!;
		state.feeds = state.feeds.filter((f) => f.id !== id);
		const left = ((b.leave_frame_ids as string[] | undefined) ?? []).filter((x) => state.frames.some((f) => f.id === x));
		state.frames = state.frames.filter((f) => !left.includes(f.id));
		const r = b.photos === 'take' ? (lv.results[`feed:${id}`] ?? nothing()) : nothing();
		return json(route, 200, { ...r, left_frame_ids: left });
	}
	if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/leave$/)) && method === 'POST' && leavingOn && b.photos) {
		const id = m[1]!;
		const f = state.frames.find((x) => x.id === id);
		if (f?.role === 'owner') return problem(route, 409, 'owner_cannot_leave', 'Transfer ownership first');
		state.frames = state.frames.filter((x) => x.id !== id);
		return json(route, 200, b.photos === 'take' ? (lv.results[`frame:${id}`] ?? nothing()) : nothing());
	}
	if (path === '/v1/me' && method === 'DELETE' && leavingOn) return none(route);

	if (path === '/v1/me/leave-preview' && method === 'GET') {
		if (!leavingOn) return off();
		const feed = url.searchParams.get('feed_id');
		const frame = url.searchParams.get('frame_id');
		const p = lv.previews[feed ? `feed:${feed}` : `frame:${frame}`];
		return p ? json(route, 200, p) : problem(route, 404, 'not_found', 'Nothing here');
	}
	if (path === '/v1/me/former-places' && method === 'GET') return leavingOn ? json(route, 200, lv.former) : off();
	if ((m = path.match(/^\/v1\/(feeds|frames)\/([^/]+)\/take-away$/)) && method === 'POST') {
		if (!leavingOn) return off();
		const key = `${m[1] === 'feeds' ? 'feed' : 'frame'}:${m[2]}`;
		const r = lv.results[key];
		if (!r) return problem(route, 404, 'not_found', 'Nothing live');
		lv.former = { feeds: lv.former.feeds.filter((x) => x.feed_id !== m![2]), frames: lv.former.frames.filter((x) => x.frame_id !== m![2]) };
		return json(route, 200, r);
	}
	if ((m = path.match(/^\/v1\/(feeds|frames)\/([^/]+)\/taken-away$/)) && method === 'GET') {
		if (!leavingOn) return off();
		return json(route, 200, { groups: lv.takenAway[`${m[1] === 'feeds' ? 'feed' : 'frame'}:${m[2]}`] ?? [] });
	}
	if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/placements$/)) && method === 'GET' && url.searchParams.get('visibility') === 'taken_away') {
		const taking = url.searchParams.get('taking_id') ?? '';
		return json(route, 200, { placements: lv.takenPlacements[taking] ?? [], next_cursor: null, counts: {} });
	}

	// ---------------------------------------------------------------- keep requests
	if (path === '/v1/keep-requests' && method === 'POST') {
		if (!leavingOn) return off();
		const scopeKey = b.frame_id ? `frame:${b.frame_id}` : `feed:${b.feed_id}`;
		const g = (lv.takenAway[scopeKey] ?? []).find((x) => x.taking_id === b.taking_id);
		if (!g) return problem(route, 404, 'not_found', 'No such taking');
		if (!g.can_ask) return problem(route, g.cannot_ask_reason === 'blocked' ? 403 : 409, g.cannot_ask_reason === 'blocked' ? 'forbidden' : 'limit_reached', 'Cannot ask');
		const r: S['KeepRequest'] = {
			id: lid(),
			status: 'open',
			scope: b.frame_id ? 'frame' : 'feed',
			frame_id: (b.frame_id as string) ?? null,
			feed_id: (b.feed_id as string) ?? null,
			taking_id: g.taking_id,
			asked_by_name: state.me?.display_name ?? 'Me',
			note: (b.note as string) ?? null,
			asked_at: now(),
			expires_at: g.until,
			item_count: (b.item_ids as string[] | undefined)?.length ?? g.count
		};
		lv.asked.push(r);
		g.keep_request = r;
		g.can_ask = false;
		g.cannot_ask_reason = 'already_asked';
		return json(route, 201, r);
	}
	if (path === '/v1/me/keep-requests' && method === 'GET') {
		if (!leavingOn) return off();
		const all = url.searchParams.get('status') === 'all';
		return json(route, 200, { requests: lv.myRequests.filter((r) => all || r.status === 'open').map(({ items: _items, ...r }) => r) });
	}
	if ((m = path.match(/^\/v1\/keep-requests\/([^/]+)$/)) && method === 'GET') {
		const r = lv.myRequests.find((x) => x.id === m![1]);
		return r ? json(route, 200, r) : problem(route, 404, 'not_found', 'Not found');
	}
	if ((m = path.match(/^\/v1\/keep-requests\/([^/]+)\/answer$/)) && method === 'POST') {
		const r = lv.myRequests.find((x) => x.id === m![1]);
		if (!r) return problem(route, 404, 'not_found', 'Not found');
		if (r.status !== 'open') return problem(route, 409, 'already_decided', 'Already answered');
		const allow = b.allow as 'all' | 'some' | 'none';
		const ids = new Set((b.item_ids as string[] | undefined) ?? []);
		r.items = (r.items ?? []).map((x) => ({ ...x, allowed: allow === 'all' || (allow === 'some' && ids.has(x.item.id)) }));
		r.allowed_count = r.items.filter((x) => x.allowed).length;
		r.status = allow === 'all' ? 'allowed' : allow === 'none' ? 'declined' : 'partly_allowed';
		r.answered_at = now();
		return json(route, 200, r);
	}

	// ---------------------------------------------------------------- memories
	if (path === '/v1/me/memories') {
		if (!leavingOn) return off();
		if (method === 'PUT') {
			if (Array.isArray(b.hidden_people)) {
				const people = (state as { social?: { contacts: S['Person'][] } }).social?.contacts ?? [];
				lv.memories.hidden_people = (b.hidden_people as string[]).map(
					(id) => people.find((p) => p.person.user_id === id)?.person ?? lv.memories.hidden_people.find((p) => p.user_id === id) ?? { user_id: id, display_name: id }
				);
			}
			if (Array.isArray(b.hidden_memories)) lv.memories.hidden_memories = b.hidden_memories as string[];
		}
		return json(route, 200, lv.memories);
	}

	// ---------------------------------------------------------------- operator ownership
	if (path === '/v1/operator/ownership/search' && method === 'GET') {
		if (!leavingOn) return off();
		const q = (url.searchParams.get('q') ?? '').toLowerCase();
		const hit = (s: S['OperatorScope']) => s.name.toLowerCase().includes(q) || s.members.some((x) => x.display_name.toLowerCase().includes(q) || x.email?.toLowerCase().includes(q));
		return json(route, 200, { frames: lv.ownership.frames.filter(hit), feeds: lv.ownership.feeds.filter(hit) });
	}
	if ((m = path.match(/^\/v1\/operator\/(frames|feeds)\/([^/]+)\/owner$/)) && method === 'POST') {
		if (!leavingOn) return off();
		const list = m[1] === 'frames' ? lv.ownership.frames : lv.ownership.feeds;
		const s = list.find((x) => x.id === m![2]);
		const to = s?.members.find((x) => x.user_id === b.user_id);
		if (!s || !to) return problem(route, 422, 'validation_failed', 'Not a member');
		const from = s.members.find((x) => x.role === 'owner');
		if (from) {
			if (b.remove_previous) s.members = s.members.filter((x) => x !== from);
			else from.role = 'admin';
		}
		to.role = 'owner';
		lv.log.unshift({ id: lid(), kind: 'operator_transfer', at: now(), scope_kind: m[1] === 'frames' ? 'frame' : 'feed', scope_id: s.id, scope_name: s.name, from_name: from?.display_name ?? null, to_name: to.display_name, operator_name: state.me?.display_name ?? null, reason: b.reason as string, removed_previous: b.remove_previous === true });
		return none(route);
	}
	if (path === '/v1/operator/ownership-log' && method === 'GET') return leavingOn ? json(route, 200, { entries: lv.log, next_cursor: null }) : off();

	// ---------------------------------------------------------------- remote families
	if (path === '/v1/remote-invites:preview' && method === 'POST') {
		if (!remoteOn) return off();
		const u = String(b.invite_url ?? '');
		if (u.includes('refused')) return problem(route, 403, 'forbidden', 'Refused');
		return lv.invitePreview ? json(route, 200, lv.invitePreview) : problem(route, 404, 'invite_invalid', 'Invalid');
	}
	if ((m = path.match(/^\/v1\/frames\/([^/]+)\/remote-subscriptions$/))) {
		if (!remoteOn) return off();
		const list = (lv.remoteSubs[m[1]!] ??= []);
		if (method === 'POST') {
			const p = lv.invitePreview!;
			const s: S['RemoteSubscription'] = {
				id: lid(),
				frame_id: m[1]!,
				status: p.requires_approval ? 'pending' : 'active',
				feed_name: p.feed_name,
				cover_url: null,
				server: p.server,
				history: (b.history as S['SubscriptionHistory']) ?? 'none',
				history_count: (b.history_count as number) ?? null,
				birthdays: true,
				on_end: (b.on_end as S['RemoteOnEnd']) ?? 'remove',
				item_count: 0,
				requested_by_name: state.me?.display_name ?? null,
				created_at: now()
			};
			list.unshift(s);
			return json(route, 201, s);
		}
		return json(route, 200, list);
	}
	if ((m = path.match(/^\/v1\/frames\/([^/]+)\/remote-subscriptions\/([^/]+)$/))) {
		if (!remoteOn) return off();
		const list = (lv.remoteSubs[m[1]!] ??= []);
		const s = list.find((x) => x.id === m![2]);
		if (!s) return problem(route, 404, 'not_found', 'Not found');
		if (method === 'PATCH') {
			Object.assign(s, b);
			return json(route, 200, s);
		}
		if (method === 'DELETE') {
			Object.assign(s, { status: 'removed', removed_reason: s.status === 'pending' ? 'withdrawn' : 'unsubscribed', ended_at: now() });
			return none(route);
		}
	}
	if ((m = path.match(/^\/v1\/(?:feeds\/([^/]+)|operator)\/blocked-servers(?:\/([^/]+))?$/))) {
		if (!remoteOn) return off();
		const key = m[1] ?? 'operator';
		const list = (lv.blocked[key] ??= []);
		if (method === 'GET') return json(route, 200, list);
		if (method === 'POST') {
			const host = String(b.host ?? '');
			const x: S['BlockedServer'] = { id: lid(), host, reason: (b.reason as string) ?? null, feed_id: m[1] ?? null, created_by_name: state.me?.display_name ?? null, created_at: now() };
			list.unshift(x);
			for (const s of lv.remoteServers) if (s.host === host && key === 'operator') s.blocked = true;
			return json(route, 201, x);
		}
		if (method === 'DELETE') {
			lv.blocked[key] = list.filter((x) => x.id !== m![2]);
			return none(route);
		}
	}
	if (path === '/v1/operator/remote-servers' && method === 'GET') return remoteOn ? json(route, 200, lv.remoteServers) : off();

	return null;
}
