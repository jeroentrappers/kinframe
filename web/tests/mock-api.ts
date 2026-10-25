// In-memory mock of the kinframe API for Playwright (route interception, no server needed).
// Shapes follow docs/api/openapi.yaml.
import type { Page, Route, WebSocketRoute } from '@playwright/test';
import type { components } from '../src/lib/api/schema';

type S = components['schemas'];
type Send = S['Send'];

const BASE = `http://localhost:${process.env.PW_PORT ?? 4173}`;

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
	uploads: Record<string, { length: number; offset: number; filename: string; prepared?: string; data?: Buffer[] }>;
	invitePreviews: Record<string, S['InvitePreview']>;
	albums: Record<string, S['Album'][]>;
	pushEndpoints: S['PushEndpoint'][];
	/** Codes of fresh, unclaimed frames for "Move to a new frame". */
	replaceCodes: string[];
	/** TV links per frame (README §5.9); `expired` tokens answer 410. */
	tvLinks: Record<string, (S['TvLink'] & { placement_ids?: string[]; revoked?: boolean })[]>;
	calendars: Record<string, S['CalendarSource'][]>;
	calendarViews: Record<string, S['CalendarView']>;
	eventInvitations: Record<string, S['EventInvitation'][]>;
	/** Shop reservations (README §5.11); only reachable when discovery has a shop. */
	reservations: S['ShopReservation'][];
	calls: { method: string; path: string; body?: unknown; headers?: Record<string, string> }[];
	ws: WebSocketRoute | null;
}

export function emptyState(): MockState {
	return {
		discovery: {
			name: 'Testfamilie',
			version: '0.1.0',
			api: `${BASE}/v1`,
			auth: { issuer: '', mobile_client_id: '', web_client_id: '', api_resource: `${BASE}/v1` },
			limits: { max_items_per_send: 200, max_video_seconds: 300, max_upload_bytes: 2147483648, trash_retention_days: 30 },
			dev_auth: true,
			weather: true,
			calendar: true,
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
		tvLinks: {},
		calendars: {},
		calendarViews: {},
		eventInvitations: {},
		reservations: [],
		calls: [],
		ws: null
	};
}

/** A shop like the hosted instance's (placeholder seller). Assign to `state.discovery.shop` to enable it. */
export function shopFixture(): S['Shop'] {
	return {
		brand: 'kinframe',
		currency: 'EUR',
		vat_percent: 21,
		products: [
			{
				id: 'frame-10',
				name: 'kinframe 10.1"',
				size: '10.1"',
				price_cents: 7900,
				max_qty: 5,
				image: 'frame-10',
				specs: {
					en: ['1280×800 IPS touchscreen', 'Wi-Fi', 'kinframe preinstalled'],
					nl: ['1280×800 IPS-touchscreen', 'Wifi', 'kinframe vooraf geïnstalleerd']
				}
			},
			{
				id: 'frame-15',
				name: 'kinframe 15.6"',
				size: '15.6"',
				price_cents: 15900,
				max_qty: 5,
				image: 'frame-15',
				specs: { en: ['Full HD 1920×1080 IPS touchscreen', 'Wi-Fi', 'kinframe preinstalled'] }
			}
		],
		shipping: { countries: ['BE', 'NL'], flat_cents: 695, free_from_cents: 10000 },
		estimated_delivery: { en: 'early 2027', nl: 'begin 2027', fr: 'début 2027' },
		seller: { name: 'Example BV', email: 'shop@example.org', legal: 'Example BV · VAT BE0123.456.789' }
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
		// Third-party scripts with /v1/ in their path (the Cast SDK) are someone else's.
		// (*.localhost is the app too: storefront tests use storefront.localhost.)
		if (!/^([a-z0-9-]+\.)?localhost(:|$)/.test(url.host)) return route.fallback();
		const path = url.pathname;
		const method = req.method();
		let body: unknown;
		try {
			body = req.postDataJSON();
		} catch {
			body = undefined;
		}
		state.calls.push({ method, path, body, headers: req.headers() });

		let m: RegExpMatchArray | null;

		// ---- public: TV slideshow and signed media (no Authorization header, like a TV or Chromecast)
		if ((m = path.match(/^\/v1\/media\/signed\/(.+)$/))) {
			const seed = [...m[1]!].reduce((a, c) => a + c.charCodeAt(0), 0);
			return route.fulfill({ status: 200, contentType: 'image/svg+xml', body: photoSvg(seed), headers: { 'Access-Control-Allow-Origin': '*' } });
		}
		if ((m = path.match(/^\/v1\/tv\/([^/]+)$/)) && method === 'GET') {
			const token = decodeURIComponent(m[1]!);
			for (const [frameId, links] of Object.entries(state.tvLinks)) {
				const l = links.find((x) => x.token === token);
				if (!l) continue;
				if (l.revoked) break;
				if (Date.parse(l.expires_at) < Date.now()) return problem(route, 410, 'link_expired', 'This link has expired');
				const frame = state.frames.find((f) => f.id === frameId)!;
				let pls = (state.placements[frameId] ?? []).filter((p) => !p.hidden && !p.trashed);
				if (l.placement_ids?.length) pls = l.placement_ids.map((id) => pls.find((p) => p.id === id)).filter((p): p is S['Placement'] => !!p);
				else if (l.album_id) pls = pls.filter((p) => p.album_ids?.includes(l.album_id!));
				const origin = 'http://localhost:4173';
				const slides: S['TvSlide'][] = pls.map((p) => ({
					placement_id: p.id,
					kind: p.item.kind,
					url: `${origin}/v1/media/signed/${p.item.id}/master?exp=1&sig=x`,
					mime: p.item.kind === 'video' ? 'video/mp4' : 'image/svg+xml',
					thumb_url: `${origin}/v1/media/signed/${p.item.id}/thumb?exp=1&sig=x`,
					poster_url: p.item.kind === 'video' ? `${origin}/v1/media/signed/${p.item.id}/poster?exp=1&sig=x` : null,
					caption: p.item.caption ?? null,
					sender_name: p.item.sender_name ?? '',
					captured_at: p.item.captured_at ?? null,
					received_at: p.received_at ?? p.item.created_at,
					width: 640,
					height: 480,
					duration_ms: p.item.duration_ms ?? null
				}));
				return json(route, 200, {
					frame_id: frameId,
					frame_name: frame.name,
					album_name: null,
					interval_seconds: l.interval_seconds,
					expires_at: l.expires_at,
					refresh_after: new Date(Date.now() + 45 * 60_000).toISOString(),
					slides
				} satisfies S['TvSlideshow']);
			}
			return problem(route, 404, 'not_found', 'This TV link is not valid');
		}

		// ---- public: shop reservations (no sign-in)
		if (path === '/v1/shop/reservations' && method === 'POST') {
			const shop = state.discovery.shop;
			if (!shop) return problem(route, 404, 'not_found', 'Not found');
			const b = body as S['ShopReservationCreate'];
			const errors: { field: string; code: string }[] = [];
			if (!/^[^\s@]+@[^\s@]+\.[^\s@.]{2,}$/.test(b.email)) errors.push({ field: 'email', code: 'format' });
			if (!shop.shipping.countries.includes(b.country)) errors.push({ field: 'country', code: 'enum' });
			if (!b.consent) errors.push({ field: 'consent', code: 'required' });
			if (!b.items.length) errors.push({ field: 'items', code: 'minItems' });
			if (errors.length)
				return route.fulfill({
					status: 422,
					contentType: 'application/problem+json',
					body: JSON.stringify({ type: 'x', title: 'Validation failed', status: 422, code: 'validation_failed', errors })
				});
			const items = b.items.map((it) => {
				const p = shop.products.find((x) => x.id === it.product_id)!;
				return { product_id: p.id, name: p.name, quantity: it.quantity, unit_cents: p.price_cents, line_cents: p.price_cents * it.quantity };
			});
			const sub = items.reduce((a, l) => a + l.line_cents, 0);
			const ship = shop.shipping.free_from_cents != null && sub >= shop.shipping.free_from_cents ? 0 : shop.shipping.flat_cents;
			const r: S['ShopReservation'] = {
				id: uid('r'),
				reference: `KF-${String(state.reservations.length + 1).padStart(2, '0')}QM`,
				status: 'new',
				name: b.name,
				email: b.email,
				country: b.country,
				postal_code: b.postal_code ?? null,
				note: b.note ?? null,
				locale: b.locale ?? 'en',
				items,
				subtotal_cents: sub,
				shipping_cents: ship,
				total_cents: sub + ship,
				currency: shop.currency,
				internal_note: null,
				created_at: new Date().toISOString(),
				updated_at: new Date().toISOString(),
				cancelled_at: null
			};
			if (!b.website) state.reservations.unshift(r);
			const { reference, status, email, subtotal_cents, shipping_cents, total_cents, currency } = r;
			return json(route, 201, { reference, status, email, items, subtotal_cents, shipping_cents, total_cents, currency });
		}
		if (path === '/v1/shop/reservations/cancel' && method === 'POST') {
			const token = (body as { token: string }).token;
			const r = state.reservations.find((x) => `tok-${x.reference}` === token);
			if (!state.discovery.shop || !r) return problem(route, 404, 'not_found', 'Not found');
			r.status = 'cancelled';
			r.cancelled_at = new Date().toISOString();
			return json(route, 200, { reference: r.reference, status: r.status });
		}

		if (!req.headers()['authorization']?.startsWith('Bearer ') && !path.startsWith('/v1/uploads/'))
			return problem(route, 401, 'unauthorized', 'Unauthorized');

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

		// ---- operator: shop reservations
		if (path === '/v1/operator/reservations' && method === 'GET') {
			if (!state.me?.is_operator) return problem(route, 403, 'forbidden', 'Operator only');
			const st = url.searchParams.get('status');
			return json(route, 200, { reservations: state.reservations.filter((r) => !st || r.status === st) });
		}
		if (path === '/v1/operator/reservations.csv' && method === 'GET') {
			const rows = state.reservations.map((r) => `${r.reference},${r.status},${r.email}`);
			return route.fulfill({ status: 200, contentType: 'text/csv', body: ['reference,status,email', ...rows].join('\n') });
		}
		if ((m = path.match(/^\/v1\/operator\/reservations\/([^/]+)$/)) && method === 'PATCH') {
			const r = state.reservations.find((x) => x.id === m![1]);
			if (!r) return problem(route, 404, 'not_found', 'Not found');
			Object.assign(r, body, { updated_at: new Date().toISOString() });
			return json(route, 200, r);
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
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/tv-links$/))) {
			const list = (state.tvLinks[m[1]!] ??= []);
			if (method === 'POST') {
				const b = (body ?? {}) as S['TvLinkCreate'];
				const token = 'tv_' + Math.random().toString(36).slice(2).padEnd(28, 'x');
				const l = {
					id: uid('7'),
					frame_id: m[1]!,
					token,
					url: `http://localhost:4173/tv/${m[1]!}?t=${token}${b.purpose === 'mirror' ? '&mode=mirror' : ''}`,
					purpose: b.purpose ?? 'tv',
					album_id: b.album_id ?? null,
					placement_count: b.placement_ids?.length ? b.placement_ids.length : null,
					placement_ids: b.placement_ids,
					kind: b.kind ?? null,
					interval_seconds: b.interval_seconds ?? 10,
					created_by_name: state.me?.display_name ?? 'Me',
					expires_at: new Date(Date.now() + (b.expires_in_hours ?? 12) * 3600_000).toISOString(),
					created_at: new Date().toISOString()
				};
				list.unshift(l);
				const { placement_ids: _ids, ...out } = l;
				return json(route, 201, out);
			}
			return json(
				route,
				200,
				list.filter((l) => !l.revoked && Date.parse(l.expires_at) > Date.now()).map(({ placement_ids: _ids, revoked: _r, ...l }) => l)
			);
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/tv-links\/([^/]+)$/)) && method === 'DELETE') {
			const l = (state.tvLinks[m[1]!] ?? []).find((x) => x.id === m![2]);
			if (l) l.revoked = true;
			return route.fulfill({ status: 204 });
		}
		// ---- calendars (README §5.8)
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/calendars$/))) {
			const list = (state.calendars[m[1]!] ??= []);
			if (method === 'POST') {
				const b = body as S['CalendarSourceCreate'];
				const used = list.map((s) => s.color);
				const color = b.color ?? (CAL_COLORS.find((c) => !used.includes(c)) ?? 'sky');
				const host = (() => {
					try {
						return new URL(b.url.replace(/^webcal:/i, 'https:')).host;
					} catch {
						return 'calendar';
					}
				})();
				const src: S['CalendarSource'] = {
					id: uid('c'),
					kind: b.kind,
					name: b.name ?? host,
					color,
					enabled: b.enabled ?? true,
					url_hint: `https://${host}/…`,
					username: b.username ?? null,
					has_password: !!b.password,
					status: 'syncing',
					last_error: null,
					last_synced_at: null,
					event_count: 0,
					created_at: new Date().toISOString()
				};
				list.push(src);
				return json(route, 201, src);
			}
			// Syncs "finish" by the next poll.
			for (const s of list) {
				if (s.status === 'syncing') {
					s.status = s.enabled ? 'ok' : 'disabled';
					s.last_synced_at = new Date().toISOString();
					s.event_count = s.event_count || 6;
				}
			}
			return json(route, 200, list);
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/calendars:test$/)) && method === 'POST') {
			const b = body as S['CalendarSourceCreate'];
			if (/bad|broken/.test(b.url)) return json(route, 200, { ok: false, status: 'failed', error: 'HTTP 404 Not Found' });
			if (b.kind === 'caldav' && b.password === 'wrong') return json(route, 200, { ok: false, status: 'auth_failed', error: 'HTTP 401 Unauthorized' });
			if (b.kind === 'caldav')
				return json(route, 200, {
					ok: true,
					status: 'ok',
					error: null,
					calendar_name: null,
					event_count: 9,
					calendars: [
						{ url: `${b.url}/remote.php/dav/calendars/oma/personal/`, name: 'Personal', color: '#0082c9' },
						{ url: `${b.url}/remote.php/dav/calendars/oma/birthdays/`, name: 'Birthdays', color: '#e5726a' }
					]
				});
			return json(route, 200, { ok: true, status: 'ok', error: null, calendar_name: 'Family', event_count: 12, calendars: [] });
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/calendars\/([^/]+)\/sync$/)) && method === 'POST') {
			const s = (state.calendars[m[1]!] ?? []).find((x) => x.id === m![2]);
			if (!s) return problem(route, 404, 'not_found', 'No calendar');
			s.status = 'syncing';
			return json(route, 202, s);
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/calendars\/([^/]+)$/))) {
			const list = state.calendars[m[1]!] ?? [];
			const s = list.find((x) => x.id === m![2]);
			if (!s) return problem(route, 404, 'not_found', 'No calendar');
			if (method === 'DELETE') {
				state.calendars[m[1]!] = list.filter((x) => x !== s);
				const v = state.calendarViews[m[1]!];
				if (v) {
					v.sources = v.sources.filter((x) => x.id !== s.id);
					v.events = v.events.filter((e) => e.source_id !== s.id);
				}
				return route.fulfill({ status: 204 });
			}
			const b = body as S['CalendarSourceUpdate'];
			Object.assign(s, { ...b, password: undefined, has_password: s.has_password || !!b.password });
			delete (s as Record<string, unknown>).password;
			if (b.enabled === false) s.status = 'disabled';
			else if (b.enabled === true && s.status === 'disabled') s.status = 'syncing';
			if (b.url || b.password) s.status = 'syncing';
			const v = state.calendarViews[m[1]!];
			const info = v?.sources.find((x) => x.id === s.id);
			if (info) Object.assign(info, { name: s.name, color: s.color, status: s.status });
			return json(route, 200, s);
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/calendar$/)) && method === 'GET') {
			const v = (state.calendarViews[m[1]!] ??= sampleCalendar(state.calendars[m[1]!] ?? [], { events: false }));
			// Only enabled sources show.
			const enabled = new Set((state.calendars[m[1]!] ?? []).filter((s) => s.enabled).map((s) => s.id));
			return json(route, 200, { ...v, events: v.events.filter((e) => enabled.has(e.source_id) || !state.calendars[m![1]!]?.some((s) => s.id === e.source_id)) });
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/countdowns\/([^/]+)$/))) {
			const v = state.calendarViews[m[1]!];
			const ev = v?.events.find((e) => e.id === m![2]);
			if (!v || !ev) return problem(route, 404, 'not_found', 'No such event');
			ev.countdown = method === 'PUT';
			v.countdowns = v.countdowns.filter((c) => c.event_id !== ev.id);
			if (ev.countdown) {
				const color = v.sources.find((s) => s.id === ev.source_id)?.color ?? 'sky';
				v.countdowns.push({ event_id: ev.id, source_id: ev.source_id, title: ev.title, all_day: ev.all_day, start_at: ev.start_at ?? null, start_date: ev.start_date ?? null, color });
				v.countdowns.sort((a, b) => (a.start_date ?? a.start_at ?? '').localeCompare(b.start_date ?? b.start_at ?? ''));
			}
			return route.fulfill({ status: 204 });
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/events$/))) {
			const list = (state.eventInvitations[m[1]!] ??= []);
			if (method === 'POST') {
				const b = body as S['EventInvitationCreate'];
				const inv: S['EventInvitation'] = {
					id: uid('v'),
					frame_id: m[1]!,
					sender_id: state.me!.id,
					sender_name: state.me!.display_name,
					status: 'pending',
					title: b.title,
					all_day: b.all_day ?? false,
					start_at: b.start_at ?? null,
					end_at: b.end_at ?? null,
					start_date: b.start_date ?? null,
					end_date: b.end_date ?? null,
					location: b.location ?? null,
					notes: b.notes ?? null,
					has_photo: !!b.photo_upload_id,
					photo_placement: b.photo_placement ?? 'top',
					created_at: new Date().toISOString(),
					decided_at: null
				};
				list.unshift(inv);
				return json(route, 201, inv);
			}
			return json(route, 200, list);
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/events\/([^/]+)$/)) && method === 'DELETE') {
			const inv = (state.eventInvitations[m[1]!] ?? []).find((x) => x.id === m![2]);
			if (!inv) return problem(route, 404, 'not_found', 'No invitation');
			if (inv.status !== 'pending') return problem(route, 409, 'already_decided', 'Already decided');
			inv.status = 'withdrawn';
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
			if (b.action === 'purge') state.placements[m[1]!] = (state.placements[m[1]!] ?? []).filter((p) => !b.placement_ids.includes(p.id));
			for (const p of state.placements[m[1]!] ?? []) {
				if (!b.placement_ids.includes(p.id)) continue;
				if (b.action === 'hide') p.hidden = true;
				if (b.action === 'unhide') p.hidden = false;
				if (b.action === 'trash') p.trashed = true;
				if (b.action === 'restore') p.trashed = false;
			}
			return json(route, 200, { ok: b.placement_ids, failed: [] });
		}
		if ((m = path.match(/^\/v1\/frames\/([^/:]+)\/placements:share$/)) && method === 'POST') {
			// README §5.3 "Sharing between frames": new placements of the same items, duplicates skipped.
			const b = body as { placement_ids: string[]; frame_ids: string[]; dry_run?: boolean };
			const sources = b.placement_ids.map((pid) => ({ pid, item: findItem(state, m![1]!, pid) }));
			if (sources.some((s) => !s.item)) return problem(route, 404, 'not_found', 'Placement not found');
			for (const fid of b.frame_ids) {
				const f = state.frames.find((x) => x.id === fid);
				if (!f) return problem(route, 404, 'not_found', 'Frame not found');
				if (f.role === 'member' && !f.permissions.send) return problem(route, 403, 'missing_permission', 'Cannot send');
			}
			const created: S['Placement'][] = [];
			const skipped: { placement_id: string; frame_id: string; reason: 'already_on_frame'; existing_placement_id: string }[] = [];
			for (const fid of b.frame_ids) {
				const list = (state.placements[fid] ??= []);
				for (const s of sources) {
					const existing = list.find((p) => p.item.id === s.item!.id);
					if (existing) {
						skipped.push({ placement_id: s.pid, frame_id: fid, reason: 'already_on_frame', existing_placement_id: existing.id });
						continue;
					}
					if (b.dry_run) continue;
					const f = state.frames.find((x) => x.id === fid)!;
					const p: S['Placement'] = {
						id: uid('p'),
						frame_id: fid,
						item: s.item!,
						status: 'pending',
						hidden: false,
						trashed: false,
						pending_moderation: f.moderation === true && f.role === 'member',
						received_at: new Date().toISOString(),
						shared_by_user_id: state.me!.id,
						shared_by_name: state.me!.display_name
					};
					list.unshift(p);
					created.push(p);
				}
			}
			return json(route, 200, { placements: created, skipped });
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
			state.uploads[id] = { length: Number(req.headers()['upload-length']), offset: 0, filename: meta.filename ?? '', prepared: meta.prepared };
			return route.fulfill({
				status: 201,
				headers: { Location: `${BASE}/v1/uploads/${id}`, 'Tus-Resumable': '1.0.0', 'Access-Control-Expose-Headers': 'Location, Upload-Offset' }
			});
		}
		if ((m = path.match(/^\/v1\/uploads\/([^/]+)$/))) {
			const u = state.uploads[m[1]!];
			if (!u) return route.fulfill({ status: 404 });
			if (method === 'PATCH') {
				const chunk = req.postDataBuffer();
				u.offset += chunk?.length ?? 0;
				if (chunk) (u.data ??= []).push(chunk);
			}
			return route.fulfill({ status: method === 'HEAD' ? 200 : 204, headers: { 'Upload-Offset': String(u.offset), 'Upload-Length': String(u.length), 'Tus-Resumable': '1.0.0' } });
		}

		// ---- sends
		if (path === '/v1/sends' && method === 'POST') {
			const b = body as S['SendCreate'];
			const items: S['Item'][] = b.items.map((it, i) => ({
				id: uid('i'),
				kind: it.live_video_upload_id ? 'live' : state.uploads[it.upload_id]?.filename.match(/\.(mp4|mov)$/) ? 'video' : 'photo',
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

/** The item behind a placement on a frame: from the frame's library, else from the sent history. */
function findItem(state: MockState, frameId: string, placementId: string): S['Item'] | undefined {
	const lib = state.placements[frameId]?.find((p) => p.id === placementId);
	if (lib) return lib.item;
	for (const s of state.sends) {
		const pl = s.frames.find((f) => f.frame_id === frameId)?.placements.find((p) => p.placement_id === placementId);
		if (pl) return s.items.find((i) => i.id === pl.item_id);
	}
	return undefined;
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

// ------------------------------------------------------------------ calendars

export const CAL_COLORS: S['CalendarColor'][] = ['coral', 'tangerine', 'amber', 'olive', 'mint', 'sage', 'teal', 'sky', 'ocean', 'lavender', 'plum', 'rose'];

const dayStr = (offset: number) => {
	const d = new Date();
	d.setHours(12, 0, 0, 0);
	d.setDate(d.getDate() + offset);
	return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};
/** An instant on a day (offset from today) at a local hour in the browser's zone. */
const at = (offset: number, hour: number, minute = 0) => {
	const d = new Date();
	d.setDate(d.getDate() + offset);
	d.setHours(hour, minute, 0, 0);
	return d.toISOString();
};

/** Three sources a family would have: the received-events calendar, a shared ICS link and a CalDAV calendar. */
export function sampleSources(): S['CalendarSource'][] {
	const now = new Date().toISOString();
	const base = { enabled: true, last_error: null, last_synced_at: new Date(Date.now() - 4 * 60_000).toISOString(), created_at: now, has_password: false, username: null };
	return [
		{ ...base, id: uid('c1'), kind: 'local', name: '', color: 'rose', url_hint: null, status: 'ok', event_count: 2 },
		{ ...base, id: uid('c2'), kind: 'ics', name: 'Family', color: 'teal', url_hint: 'https://calendar.google.com/…/basic.ics', status: 'ok', event_count: 14 },
		{ ...base, id: uid('c3'), kind: 'caldav', name: 'Oma’s appointments', color: 'amber', url_hint: 'https://cloud.example.org/…', username: 'oma', has_password: true, status: 'ok', event_count: 5 },
		{ ...base, id: uid('c4'), kind: 'ics', name: 'Football club', color: 'ocean', url_hint: 'https://club.example.be/…/team.ics', status: 'failed', last_error: 'HTTP 404 Not Found', event_count: 0 }
	];
}

/** A merged calendar around today, with a week of weather (README §5.8). */
export function sampleCalendar(sources: S['CalendarSource'][], opts: { events?: boolean; timezone?: string } = {}): S['CalendarView'] {
	const tz = opts.timezone ?? Intl.DateTimeFormat().resolvedOptions().timeZone;
	const id = (k: string) => `ev_${k}_000000000000`.slice(0, 24);
	const [local, family, oma] = [sources.find((s) => s.kind === 'local'), sources.find((s) => s.kind === 'ics'), sources.find((s) => s.kind === 'caldav')];
	const events: S['CalendarEvent'][] = [];
	if (opts.events !== false && family && oma) {
		const push = (e: Omit<S['CalendarEvent'], 'recurring' | 'countdown'> & Partial<S['CalendarEvent']>) => events.push({ recurring: false, countdown: false, ...e });
		push({ id: id('trip'), source_id: family.id, title: 'Holiday in the Ardennes', all_day: true, start_date: dayStr(9), end_date: dayStr(13), location: 'La Roche' });
		push({ id: id('bday'), source_id: family.id, title: 'Lisa’s birthday', all_day: true, start_date: dayStr(3), end_date: dayStr(3), countdown: true });
		push({ id: id('xmas'), source_id: family.id, title: 'Christmas at Oma’s', all_day: true, start_date: dayStr(24), end_date: dayStr(24), countdown: true });
		push({ id: id('swim'), source_id: family.id, title: 'Swimming lesson', all_day: false, start_at: at(0, 16, 30), end_at: at(0, 17, 15), recurring: true });
		push({ id: id('swim2'), source_id: family.id, title: 'Swimming lesson', all_day: false, start_at: at(7, 16, 30), end_at: at(7, 17, 15), recurring: true });
		push({ id: id('doc'), source_id: oma.id, title: 'Doctor', all_day: false, start_at: at(0, 10, 0), end_at: at(0, 10, 30), location: 'Kerkstraat 12' });
		push({ id: id('cards'), source_id: oma.id, title: 'Card club', all_day: false, start_at: at(1, 14, 0), end_at: at(1, 17, 0), recurring: true });
		push({ id: id('cards2'), source_id: oma.id, title: 'Card club', all_day: false, start_at: at(8, 14, 0), end_at: at(8, 17, 0), recurring: true });
		push({ id: id('hair'), source_id: oma.id, title: 'Hairdresser', all_day: false, start_at: at(2, 9, 30), end_at: at(2, 10, 30) });
		push({ id: id('market'), source_id: oma.id, title: 'Market with Marie', all_day: false, start_at: at(5, 9, 0), end_at: at(5, 11, 0), location: 'Vrijdagmarkt' });
		push({ id: id('past'), source_id: oma.id, title: 'Physio', all_day: false, start_at: at(-2, 11, 0), end_at: at(-2, 12, 0) });
		push({ id: id('dinner'), source_id: family.id, title: 'Dinner with Tom', all_day: false, start_at: at(2, 18, 30), end_at: at(2, 21, 0) });
		push({ id: id('school'), source_id: family.id, title: 'School trip', all_day: false, start_at: at(4, 8, 0), end_at: at(4, 15, 30) });
		if (local) {
			push({ id: id('lunch'), source_id: local.id, title: 'Sunday lunch at ours', all_day: false, start_at: at(5, 12, 30), end_at: at(5, 15, 0), sender_name: 'Jeroen', location: 'Gent', has_photo: true, photo_placement: 'top' });
			push({ id: id('call'), source_id: local.id, title: 'Video call with Lisa', all_day: false, start_at: at(1, 19, 0), end_at: at(1, 19, 30), sender_name: 'Lisa' });
		}
	}
	const codes = [2, 61, 3, 0, 80, 1, 71];
	const daily = codes.map((code, i) => ({
		date: dayStr(i),
		min_c: [6, 8, 7, 4, 9, 5, -1][i]!,
		max_c: [13, 12, 14, 15, 11, 16, 3][i]!,
		code,
		precip_mm: code >= 60 ? 4.2 : 0,
		precip_prob: [10, 80, 30, 0, 60, 5, 70][i]!,
		sunrise: null,
		sunset: null
	}));
	const countdowns = events
		.filter((e) => e.countdown)
		.map((e) => ({ event_id: e.id, source_id: e.source_id, title: e.title, all_day: e.all_day, start_at: e.start_at ?? null, start_date: e.start_date ?? null, color: sources.find((s) => s.id === e.source_id)!.color }));
	return {
		generated_at: new Date().toISOString(),
		timezone: tz,
		window: { from: dayStr(-30), to: dayStr(30) },
		sources: sources.map((s) => ({ id: s.id, kind: s.kind, name: s.name, color: s.color, status: s.status, last_synced_at: s.last_synced_at ?? null })),
		events,
		countdowns,
		weather: {
			fetched_at: new Date().toISOString(),
			location: { name: 'Gent', lat: 51.05, lon: 3.72 },
			timezone: tz,
			current: { temp_c: 11.4, code: 2, is_day: true },
			daily
		}
	};
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
