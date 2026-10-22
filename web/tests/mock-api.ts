// In-memory mock of the kinframe API for Playwright (route interception, no server needed).
// Shapes follow docs/api/openapi.yaml.
import type { Page, Route, WebSocketRoute } from '@playwright/test';
import type { components } from '../src/lib/api/schema';

type S = components['schemas'];
type Send = S['Send'];

let idSeq = 0;
export const uid = (p = '0000') => `0192f0a0-${p.padStart(4, '0')}-7000-8000-${String(++idSeq).padStart(12, '0')}`;

const iso = (minutesAgo: number) => new Date(Date.now() - minutesAgo * 60_000).toISOString();

const PALETTES = [
	['#fbd7b4', '#f2a76f', '#e7a23a', '#4f7a5a', '#3c6147'],
	['#cfe3f2', '#9ec5e3', '#fff3c4', '#6c8f62', '#4d6e46'],
	['#f6c9c9', '#e89aa5', '#ffd27a', '#7d6b8f', '#5c4e6c'],
	['#e3ecd9', '#bcd3a6', '#f7e08a', '#5f8a55', '#3f6339'],
	['#fde2c4', '#f7b98b', '#ffffff', '#3d5a7a', '#2a405a'],
	['#d9e8ef', '#aac9d8', '#ffe8a3', '#c27d4c', '#8f5532']
];

/** A small, pleasant SVG "photo": sky, sun, two hills and sometimes people silhouettes. */
export function photoSvg(seed: number): string {
	const [sky1, sky2, sun, hill1, hill2] = PALETTES[seed % PALETTES.length]!;
	const sx = 140 + ((seed * 97) % 340);
	const people = seed % 3 === 0;
	return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 480" width="640" height="480">
<defs><linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${sky1}"/><stop offset="1" stop-color="${sky2}"/></linearGradient></defs>
<rect width="640" height="480" fill="url(#s)"/>
<circle cx="${sx}" cy="${120 + (seed % 4) * 18}" r="${38 + (seed % 3) * 8}" fill="${sun}" opacity="0.95"/>
<path d="M0 330 Q160 ${250 + (seed % 5) * 10} 320 320 T640 300 V480 H0z" fill="${hill1}"/>
<path d="M0 390 Q200 ${330 + (seed % 4) * 12} 420 380 T640 370 V480 H0z" fill="${hill2}"/>
${people ? `<g fill="#2b2420" opacity="0.85"><circle cx="300" cy="318" r="13"/><rect x="288" y="332" width="24" height="44" rx="10"/><circle cx="336" cy="326" r="10"/><rect x="327" y="337" width="18" height="36" rx="8"/></g>` : ''}
</svg>`;
}

export interface MockState {
	discovery: S['Discovery'] & { dev_auth?: boolean };
	me: S['User'] | null;
	frames: S['Frame'][];
	codes: Record<string, { frameId: string; status: 'claimed' | 'joined' | 'pending' }>;
	sends: Send[];
	placements: Record<string, S['Placement'][]>;
	members: Record<string, S['Member'][]>;
	joinRequests: Record<string, S['JoinRequest'][]>;
	invites: Record<string, S['Invite'][]>;
	settings: Record<string, S['FrameSettings']>;
	uploads: Record<string, { length: number; offset: number; filename: string }>;
	invitePreviews: Record<string, S['InvitePreview']>;
	albums: Record<string, S['Album'][]>;
	pushEndpoints: S['PushEndpoint'][];
	/** Codes of fresh, unclaimed frames for "Move to a new frame". */
	replaceCodes: string[];
	calls: { method: string; path: string; body?: unknown; headers?: Record<string, string> }[];
	ws: WebSocketRoute | null;
}

export function emptyState(): MockState {
	return {
		discovery: {
			name: 'Testfamilie',
			version: '0.1.0',
			api: 'http://localhost:4173/v1',
			auth: { issuer: '', mobile_client_id: '', web_client_id: '', api_resource: 'http://localhost:4173/v1' },
			limits: { max_items_per_send: 200, max_video_seconds: 300, max_upload_bytes: 2147483648, trash_retention_days: 30 },
			dev_auth: true,
			weather: true,
			webpush_public_key: 'BNNL5ZaTfK81qhXOx23-wewhigUeFb632jN6LvRWCFH1ubQr77FE_9qV1FuojuRmHP42zmf34rXgW80OvUVDgTk'
		},
		me: null,
		frames: [],
		codes: {},
		sends: [],
		placements: {},
		members: {},
		joinRequests: {},
		invites: {},
		settings: {},
		uploads: {},
		invitePreviews: {},
		albums: {},
		pushEndpoints: [],
		replaceCodes: [],
		calls: [],
		ws: null
	};
}

/** Geocoding fixtures for GET /v1/geocode (matched by case-insensitive prefix). */
export const PLACES: S['Place'][] = [
	{ name: 'Gent', admin1: 'Flanders', country: 'Belgium', country_code: 'BE', lat: 51.05, lon: 3.71667, timezone: 'Europe/Brussels' },
	{ name: 'Gentbrugge', admin1: 'Flanders', country: 'Belgium', country_code: 'BE', lat: 51.03826, lon: 3.76196, timezone: 'Europe/Brussels' },
	{ name: 'Genthin', admin1: 'Saxony-Anhalt', country: 'Germany', country_code: 'DE', lat: 52.40668, lon: 12.15920, timezone: 'Europe/Berlin' },
	{ name: 'Amsterdam', admin1: 'North Holland', country: 'Netherlands', country_code: 'NL', lat: 52.37403, lon: 4.88969, timezone: 'Europe/Amsterdam' }
];

export function defaultSettings(version = 1): S['FrameSettings'] {
	return {
		version,
		slideshow: { interval_s: 30, transition: 'crossfade', order: 'received', reverse: false, fill: true, background: 'blur' },
		widgets: { caption: true, clock: true, avatar: true, date: true, weather: false, clock_24h: true },
		display: {
			brightness: 204,
			sleep_schedules: [
				{ id: uid('5100'), sleep_at: '23:00', wake_at: '07:00', days: ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'], enabled: true, mode: 'off' }
			]
		},
		kiosk: { allow_remove_members_on_frame: true, owner_pin_set: false }
	};
}

const ALL: S['Permissions'] = { send: true, invite: true, see_library: true, manage_library: true, change_settings: true };

export function makeFrame(p: Partial<S['Frame']> & { name: string }): S['Frame'] {
	return {
		id: uid('f'),
		location: null,
		owner_name: 'Jeroen',
		role: 'owner',
		permissions: ALL,
		online: true,
		last_seen_at: iso(1),
		screen: { width: 1280, height: 800, orientation: 'landscape' },
		app_version: '0.1.0',
		needs_update: false,
		default_permissions: { send: true, invite: false, see_library: true, manage_library: false, change_settings: false },
		approve_joins_on_frame: false,
		invite_requires_approval: true,
		moderation: false,
		member_count: 1,
		placement_count: 0,
		created_at: iso(60 * 24 * 30),
		...p
	};
}

function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
	return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body), headers });
}

function problem(route: Route, status: number, code: S['ProblemCode'], title: string) {
	return route.fulfill({
		status,
		contentType: 'application/problem+json',
		body: JSON.stringify({ type: `https://kinframe.app/errors/${code}`, title, status, code })
	});
}

/** Install all routes on the page. Returns the live state for assertions and ws pushes. */
export async function mockApi(page: Page, state: MockState = emptyState()): Promise<MockState> {
	await page.route('**/.well-known/kinframe', (r) => json(r, 200, state.discovery));

	await page.route('**/dev/token', async (r) => {
		const body = r.request().postDataJSON() as { email: string; name: string };
		state.me ??= { id: uid('u'), display_name: body.name, email: body.email, avatar_url: null, locale: 'en', created_at: iso(5) };
		return json(r, 200, { access_token: `dev.${body.email}`, token_type: 'Bearer', expires_in: 86400 });
	});

	await page.routeWebSocket(/\/v1\/ws/, (ws) => {
		state.ws = ws;
		ws.onMessage(() => {});
	});

	await page.route(/\/v1\//, async (route) => {
		const req = route.request();
		const url = new URL(req.url());
		const path = url.pathname;
		const method = req.method();
		let body: unknown;
		try {
			body = req.postDataJSON();
		} catch {
			body = undefined;
		}
		state.calls.push({ method, path, body, headers: req.headers() });

		if (!req.headers()['authorization']?.startsWith('Bearer ') && !path.startsWith('/v1/uploads/'))
			return problem(route, 401, 'unauthorized', 'Unauthorized');

		let m: RegExpMatchArray | null;

		// ---- me
		if (path === '/v1/me' && method === 'GET') return state.me ? json(route, 200, state.me) : problem(route, 401, 'unauthorized', 'x');
		if (path === '/v1/me' && method === 'PATCH') {
			Object.assign(state.me!, body);
			return json(route, 200, state.me);
		}
		if (path === '/v1/me/push-endpoints' && method === 'GET') return json(route, 200, state.pushEndpoints);
		if (path === '/v1/me/push-endpoints' && method === 'POST') {
			const b = body as S['PushEndpointCreate'];
			let ep = state.pushEndpoints.find((e) => e.kind === b.kind && e.endpoint === b.endpoint);
			if (ep) Object.assign(ep, b);
			else state.pushEndpoints.push((ep = { ...b, id: uid('e'), created_at: new Date().toISOString() }));
			return json(route, 200, ep);
		}
		if ((m = path.match(/^\/v1\/me\/push-endpoints\/([^/]+)$/)) && method === 'DELETE') {
			state.pushEndpoints = state.pushEndpoints.filter((e) => e.id !== m![1]);
			return route.fulfill({ status: 204 });
		}

		// ---- geocode
		if (path === '/v1/geocode' && method === 'GET') {
			const q = (url.searchParams.get('q') ?? '').trim().toLowerCase();
			if (q.length < 2) return problem(route, 422, 'validation_failed', 'q too short');
			if (q === 'busy') return problem(route, 429, 'rate_limited', 'Too many requests');
			if (q === 'down') return problem(route, 502, 'upstream_unavailable', 'Upstream unavailable');
			const count = Number(url.searchParams.get('count') ?? 8);
			return json(route, 200, PLACES.filter((p) => p.name.toLowerCase().startsWith(q)).slice(0, count));
		}

		// ---- frames
		if (path === '/v1/frames' && method === 'GET') return json(route, 200, state.frames);
		if (path === '/v1/frames/redeem' && method === 'POST') {
			const code = (body as { code: string }).code;
			const entry = state.codes[code];
			if (!entry) return problem(route, 404, 'code_invalid', 'Invalid code');
			const frame = state.frames.find((f) => f.id === entry.frameId) ?? null;
			return json(route, 200, { status: entry.status, frame });
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)$/))) {
			const f = state.frames.find((x) => x.id === m![1]);
			if (!f) return problem(route, 404, 'not_found', 'Not found');
			if (method === 'PATCH') Object.assign(f, body);
			return json(route, 200, f);
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/transfer$/)) && method === 'POST') {
			const f = state.frames.find((x) => x.id === m![1])!;
			const list = state.members[f.id] ?? [];
			const to = list.find((x) => x.user_id === (body as { user_id: string }).user_id);
			if (!to) return problem(route, 404, 'not_found', 'Not a member');
			for (const x of list) if (x.role === 'owner') x.role = 'admin';
			to.role = 'owner';
			f.role = 'admin';
			f.owner_name = to.display_name;
			return route.fulfill({ status: 204 });
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/leave$/)) && method === 'POST') {
			const f = state.frames.find((x) => x.id === m![1])!;
			if (f.role === 'owner') return problem(route, 409, 'owner_cannot_leave', 'Transfer ownership first');
			state.frames = state.frames.filter((x) => x.id !== f.id);
			return route.fulfill({ status: 204 });
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/replace-device$/)) && method === 'POST') {
			const f = state.frames.find((x) => x.id === m![1])!;
			const code = (body as { code: string }).code;
			if (!state.replaceCodes.includes(code)) return problem(route, 404, 'code_invalid', 'Invalid code');
			state.replaceCodes = state.replaceCodes.filter((c) => c !== code);
			return json(route, 200, f);
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/albums$/))) {
			const list = (state.albums[m[1]!] ??= []);
			if (method === 'POST') {
				const b = body as S['AlbumWrite'];
				const a: S['Album'] = {
					id: uid('a'),
					name: b.name!,
					version: 1,
					placement_count: b.placement_ids?.length ?? 0,
					placement_ids: b.placement_ids ?? [],
					schedule: b.schedule ?? null
				};
				list.push(a);
				return json(route, 201, a);
			}
			return json(route, 200, [...list].sort((x, y) => x.name.localeCompare(y.name)));
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/albums\/([^/]+)$/))) {
			const list = state.albums[m[1]!] ?? [];
			const a = list.find((x) => x.id === m![2]);
			if (!a) return problem(route, 404, 'not_found', 'No album');
			if (method === 'DELETE') {
				state.albums[m[1]!] = list.filter((x) => x !== a);
				const s = state.settings[m[1]!];
				if (s?.slideshow?.album_id === a.id) s.slideshow.album_id = null;
				return route.fulfill({ status: 204 });
			}
			if (req.headers()['if-match'] !== `"${a.version}"`) return problem(route, 412, 'version_conflict', 'Conflict');
			const b = body as S['AlbumWrite'];
			if (b.name) a.name = b.name;
			if (b.cover_placement_id) a.cover_placement_id = b.cover_placement_id;
			if ('schedule' in b) a.schedule = b.schedule ?? null;
			if (b.placement_ids) a.placement_ids = b.placement_ids;
			a.placement_count = a.placement_ids?.length ?? 0;
			a.version++;
			return json(route, 200, a);
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/health$/))) {
			const f = state.frames.find((x) => x.id === m![1])!;
			return json(route, 200, {
				online: f.online,
				last_seen_at: f.last_seen_at,
				last_heartbeat: {
					app_version: '0.1.0',
					uptime_s: 3 * 86400 + 4 * 3600,
					wifi_ssid: 'Trappers',
					wifi_rssi_dbm: -58,
					storage_free_bytes: 5.2 * 1024 ** 3,
					storage_total_bytes: 8 * 1024 ** 3,
					placements_cached: 142,
					placements_missing: 2,
					screen_state: 'on',
					last_shutdown_clean: true,
					crash_count_24h: 0
				},
				last_unclean_shutdown_at: null
			});
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/commands$/))) {
			const f = state.frames.find((x) => x.id === m![1])!;
			if (!f.online) return problem(route, 409, 'frame_offline', 'Frame offline');
			return json(route, 200, { ok: true, error_code: null });
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/members$/))) return json(route, 200, state.members[m[1]!] ?? []);
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/members\/([^/]+)$/))) {
			const list = state.members[m[1]!] ?? [];
			const mem = list.find((x) => x.user_id === m![2]);
			if (method === 'DELETE') {
				state.members[m[1]!] = list.filter((x) => x.user_id !== m![2]);
				return route.fulfill({ status: 204 });
			}
			Object.assign(mem!, body);
			return json(route, 200, mem);
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/join-requests$/))) return json(route, 200, state.joinRequests[m[1]!] ?? []);
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/join-requests\/([^/]+)\/(approve|deny)$/))) {
			state.joinRequests[m[1]!] = (state.joinRequests[m[1]!] ?? []).filter((r) => r.id !== m![2]);
			return route.fulfill({ status: 204 });
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/invites$/))) {
			if (method === 'POST') {
				const token = 'tok_' + Math.random().toString(36).slice(2).padEnd(24, 'x');
				const inv: S['Invite'] = {
					id: uid('1'),
					token,
					url: `http://localhost:4173/i/${token}`,
					created_by_name: state.me?.display_name ?? 'Me',
					max_uses: (body as { max_uses: number | null }).max_uses,
					uses: 0,
					note: (body as { note?: string }).note ?? null,
					expires_at: new Date(Date.now() + ((body as { expires_in_hours: number }).expires_in_hours ?? 168) * 3600_000).toISOString()
				};
				(state.invites[m[1]!] ??= []).unshift(inv);
				return json(route, 201, inv);
			}
			return json(route, 200, state.invites[m[1]!] ?? []);
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/invites\/([^/]+)$/))) {
			state.invites[m[1]!] = (state.invites[m[1]!] ?? []).filter((i) => i.id !== m![2]);
			return route.fulfill({ status: 204 });
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/settings$/))) {
			const s = (state.settings[m[1]!] ??= defaultSettings());
			if (method === 'PATCH') {
				const ifMatch = req.headers()['if-match'];
				if (ifMatch !== `"${s.version}"`) return problem(route, 412, 'version_conflict', 'Conflict');
				const merged = deepMerge(s as Record<string, unknown>, body as Record<string, unknown>) as S['FrameSettings'];
				merged.version = s.version + 1;
				state.settings[m[1]!] = merged;
				return json(route, 200, merged);
			}
			return json(route, 200, s);
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/placements$/))) {
			const vis = url.searchParams.get('visibility') ?? 'visible';
			const all = state.placements[m[1]!] ?? [];
			let list = all.filter((p) => (vis === 'trashed' ? p.trashed : vis === 'hidden' ? p.hidden && !p.trashed : !p.hidden && !p.trashed));
			const albumId = url.searchParams.get('album_id');
			if (albumId) {
				const ids = state.albums[m[1]!]?.find((a) => a.id === albumId)?.placement_ids ?? [];
				list = list.filter((p) => ids.includes(p.id));
			}
			const otd = url.searchParams.get('on_this_day');
			if (otd) {
				const year = new Date().getFullYear();
				list = list.filter((p) => p.item.captured_at && p.item.captured_at.slice(5, 10) === otd && Number(p.item.captured_at.slice(0, 4)) < year);
			}
			return json(route, 200, {
				placements: list,
				next_cursor: null,
				counts: {
					photos: all.filter((p) => p.item.kind === 'photo' && !p.trashed).length,
					videos: all.filter((p) => p.item.kind === 'video' && !p.trashed).length,
					hidden: all.filter((p) => p.hidden && !p.trashed).length,
					trashed: all.filter((p) => p.trashed).length
				}
			});
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/placements:bulk$/))) {
			const b = body as { placement_ids: string[]; action: string; album_id?: string };
			const album = b.album_id ? state.albums[m[1]!]?.find((a) => a.id === b.album_id) : undefined;
			if (album) {
				const ids = album.placement_ids ?? [];
				if (b.action === 'add_to_album') album.placement_ids = [...ids, ...b.placement_ids.filter((id) => !ids.includes(id))];
				if (b.action === 'remove_from_album') album.placement_ids = ids.filter((id) => !b.placement_ids.includes(id));
				album.placement_count = album.placement_ids?.length ?? 0;
				album.version++;
			}
			for (const p of state.placements[m[1]!] ?? []) {
				if (!b.placement_ids.includes(p.id)) continue;
				if (b.action === 'hide') p.hidden = true;
				if (b.action === 'unhide') p.hidden = false;
				if (b.action === 'trash') p.trashed = true;
				if (b.action === 'restore') p.trashed = false;
			}
			return json(route, 200, { ok: b.placement_ids, failed: [] });
		}

		// ---- invites (recipient side)
		if ((m = path.match(/^\/v1\/invites\/([^/]+)$/))) {
			const p = state.invitePreviews[m[1]!];
			return p ? json(route, 200, p) : problem(route, 404, 'invite_invalid', 'Invalid');
		}

		// ---- uploads (tus)
		if (path === '/v1/uploads' && method === 'POST') {
			const id = `up_${Object.keys(state.uploads).length + 1}`;
			const meta = Object.fromEntries(
				(req.headers()['upload-metadata'] ?? '').split(',').map((kv) => {
					const [k, v] = kv.trim().split(' ');
					return [k, v ? Buffer.from(v, 'base64').toString() : ''];
				})
			);
			state.uploads[id] = { length: Number(req.headers()['upload-length']), offset: 0, filename: meta.filename ?? '' };
			return route.fulfill({
				status: 201,
				headers: { Location: `http://localhost:4173/v1/uploads/${id}`, 'Tus-Resumable': '1.0.0', 'Access-Control-Expose-Headers': 'Location, Upload-Offset' }
			});
		}
		if ((m = path.match(/^\/v1\/uploads\/([^/]+)$/))) {
			const u = state.uploads[m[1]!];
			if (!u) return route.fulfill({ status: 404 });
			if (method === 'PATCH') u.offset += req.postDataBuffer()?.length ?? 0;
			return route.fulfill({ status: method === 'HEAD' ? 200 : 204, headers: { 'Upload-Offset': String(u.offset), 'Upload-Length': String(u.length), 'Tus-Resumable': '1.0.0' } });
		}

		// ---- sends
		if (path === '/v1/sends' && method === 'POST') {
			const b = body as S['SendCreate'];
			const items: S['Item'][] = b.items.map((it, i) => ({
				id: uid('i'),
				kind: state.uploads[it.upload_id]?.filename.match(/\.(mp4|mov)$/) ? 'video' : 'photo',
				status: 'processing',
				sender_id: state.me!.id,
				sender_name: state.me!.display_name,
				caption: it.caption ?? null,
				caption_edited: false,
				focal_point: it.focal_point,
				fit: it.fit,
				created_at: new Date().toISOString(),
				width: 640,
				height: 480,
				thumb_url: null,
				duration_ms: null,
				captured_at: null,
				place_name: null,
				failure_code: null,
				has_audio: null,
				...(i < 0 ? {} : {})
			}));
			const send: Send = {
				id: uid('5'),
				created_at: new Date().toISOString(),
				items,
				frames: b.frame_ids.map((fid) => {
					const f = state.frames.find((x) => x.id === fid)!;
					return {
						frame_id: fid,
						frame_name: f.name,
						online: f.online,
						placements: items.map((it) => ({ placement_id: uid('p'), item_id: it.id, status: 'pending' as const, reaction: null, replies: [] }))
					};
				}),
				summary: { processing: items.length, pending: 0, delivered: 0, seen: 0, failed: 0 }
			};
			state.sends.unshift(send);
			return json(route, 202, send);
		}
		if (path === '/v1/sends' && method === 'GET') {
			const st = url.searchParams.get('state') ?? 'all';
			const list = state.sends.filter((s) => {
				if (st === 'all') return true;
				const done = s.frames.every((f) => f.placements.every((p) => p.status === 'delivered' || p.status === 'seen'));
				return st === 'delivered' ? done : !done;
			});
			return json(route, 200, { sends: list, next_cursor: null });
		}
		if ((m = path.match(/^\/v1\/sends\/([^/]+)$/))) {
			const s = state.sends.find((x) => x.id === m![1]);
			if (!s) return problem(route, 404, 'not_found', 'Not found');
			if (method === 'DELETE') {
				state.sends = state.sends.filter((x) => x.id !== s.id);
				return route.fulfill({ status: 204 });
			}
			return json(route, 200, s);
		}

		// ---- items
		if ((m = path.match(/^\/v1\/items\/([^/]+)\/media\/([^/]+)$/))) {
			const seed = [...m[1]!].reduce((a, c) => a + c.charCodeAt(0), 0);
			return route.fulfill({ status: 200, contentType: 'image/svg+xml', body: photoSvg(seed) });
		}
		if ((m = path.match(/^\/v1\/items\/([^/]+)$/)) && method === 'PATCH') {
			for (const s of state.sends) {
				const it = s.items.find((x) => x.id === m![1]);
				if (it) {
					Object.assign(it, body, { caption_edited: true });
					return json(route, 200, it);
				}
			}
			return problem(route, 404, 'not_found', 'Not found');
		}

		return problem(route, 404, 'not_found', `No mock for ${method} ${path}`);
	});

	return state;
}

function deepMerge(a: Record<string, unknown>, b: Record<string, unknown>): Record<string, unknown> {
	const out = { ...a };
	for (const [k, v] of Object.entries(b)) {
		if (v === null) delete out[k];
		else if (typeof v === 'object' && !Array.isArray(v)) out[k] = deepMerge((a[k] as Record<string, unknown>) ?? {}, v as Record<string, unknown>);
		else out[k] = v;
	}
	return out;
}

/** Push a user-websocket event (README §6.2) to the page. */
export function push(state: MockState, event: Record<string, unknown>) {
	state.ws?.send(JSON.stringify(event));
}

/** Tiny PNG encoder for upload fixtures (gradient, valid file, no deps). */
export async function gradientPng(width: number, height: number, hue = 20): Promise<Buffer> {
	const { deflateSync, crc32 } = await import('node:zlib');
	const raw = Buffer.alloc((width * 3 + 1) * height);
	for (let y = 0; y < height; y++) {
		raw[y * (width * 3 + 1)] = 0;
		for (let x = 0; x < width; x++) {
			const o = y * (width * 3 + 1) + 1 + x * 3;
			const t = y / height;
			const sky = t < 0.65;
			const [r, g, b] = sky
				? [250 - t * 60 + hue, 210 - t * 80, 170 - t * 40 + (x / width) * 30]
				: [79 + hue / 2, 122 - (t - 0.65) * 80, 90];
			raw[o] = Math.max(0, Math.min(255, r));
			raw[o + 1] = Math.max(0, Math.min(255, g));
			raw[o + 2] = Math.max(0, Math.min(255, b));
		}
	}
	const chunk = (type: string, data: Buffer) => {
		const len = Buffer.alloc(4);
		len.writeUInt32BE(data.length);
		const td = Buffer.concat([Buffer.from(type), data]);
		const crc = Buffer.alloc(4);
		crc.writeUInt32BE(crc32(td) >>> 0);
		return Buffer.concat([len, td, crc]);
	};
	const ihdr = Buffer.alloc(13);
	ihdr.writeUInt32BE(width, 0);
	ihdr.writeUInt32BE(height, 4);
	ihdr[8] = 8;
	ihdr[9] = 2;
	return Buffer.concat([
		Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
		chunk('IHDR', ihdr),
		chunk('IDAT', deflateSync(raw)),
		chunk('IEND', Buffer.alloc(0))
	]);
}
