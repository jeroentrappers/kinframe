// Helpers for the integration specs: dev-auth users, devices (frames), uploads and a browser page signed in as a
// user, all against the real server in REAL_SERVER. Every page records failed API calls and page errors so a spec
// can assert that the app never hit a status it didn't expect.
import { createHash } from 'node:crypto';
import { expect, request as pwRequest, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { gradientPng } from '../mock-api';

export const BASE = process.env.REAL_SERVER ?? 'http://localhost:18110';
const run = Date.now().toString(36);

export type Json = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

export class ApiError extends Error {
	constructor(
		public status: number,
		public body: Json | string,
		what: string
	) {
		super(`${what}: HTTP ${status} ${typeof body === 'string' ? body : JSON.stringify(body)}`);
	}
}

async function parse(r: { status(): number; text(): Promise<string> }) {
	const t = await r.text();
	try {
		return t ? (JSON.parse(t) as Json) : {};
	} catch {
		return t;
	}
}

/** A client of the real API with one bearer token (a user's or a device's). */
export class Client {
	constructor(
		public ctx: APIRequestContext,
		public token: string
	) {}

	async raw(method: string, path: string, body?: unknown, headers: Record<string, string> = {}) {
		const r = await this.ctx.fetch(`${BASE}${path}`, {
			method,
			headers: { Authorization: `Bearer ${this.token}`, Accept: 'application/json', ...headers },
			...(body === undefined ? {} : Buffer.isBuffer(body) ? { data: body } : { data: body as object })
		});
		return { status: r.status(), body: await parse(r), headers: r.headers() };
	}

	async call(method: string, path: string, body?: unknown, want?: number | number[]): Promise<Json> {
		const r = await this.raw(method, path, body);
		const ok = want === undefined ? r.status < 300 : ([] as number[]).concat(want).includes(r.status);
		if (!ok) throw new ApiError(r.status, r.body, `${method} ${path}`);
		return r.body as Json;
	}
	get = (p: string) => this.call('GET', p);
	post = (p: string, b?: unknown, want?: number | number[]) => this.call('POST', p, b ?? {}, want);
	put = (p: string, b?: unknown, want?: number | number[]) => this.call('PUT', p, b ?? {}, want);
	patch = (p: string, b?: unknown, want?: number | number[]) => this.call('PATCH', p, b ?? {}, want);
	del = (p: string, want?: number | number[]) => this.call('DELETE', p, undefined, want);
}

export interface User extends Client {
	id: string;
	name: string;
	email: string;
}

export async function newCtx() {
	return pwRequest.newContext();
}

/** A fresh dev-auth account; the email is unique per run so specs can rerun against the same database. */
export async function user(ctx: APIRequestContext, name: string): Promise<User> {
	const email = `${name.toLowerCase().replace(/[^a-z]/g, '')}-${run}-${Math.random().toString(36).slice(2, 6)}@example.org`;
	const r = await ctx.post(`${BASE}/dev/token`, { data: { email, name } });
	expect(r.status(), await r.text()).toBe(200);
	const token = ((await r.json()) as Json).access_token as string;
	const c = new Client(ctx, token);
	const me = await c.get('/v1/me');
	if (me.display_name !== name) await c.patch('/v1/me', { display_name: name });
	return Object.assign(c, { id: me.id as string, name, email });
}

export interface Device extends Client {
	deviceId: string;
	secret: string;
}

export async function enroll(ctx: APIRequestContext): Promise<Device> {
	const r = await ctx.post(`${BASE}/v1/devices/enroll`, {
		data: {
			hardware: { brand: 'denver', model: 'PFF_1024', board: 'rk312x', android_sdk: 23 },
			screen: { width: 1280, height: 800, density_dpi: 160, orientation: 'landscape' },
			app: { version: '0.7.0', version_code: 70099 },
			locale: 'en-GB',
			timezone: 'Europe/Brussels'
		}
	});
	expect(r.status()).toBe(201);
	const d = (await r.json()) as Json;
	const t = (await (await ctx.post(`${BASE}/v1/devices/token`, { data: { device_id: d.device_id, device_secret: d.device_secret } })).json()) as Json;
	return Object.assign(new Client(ctx, t.access_token as string), { deviceId: d.device_id as string, secret: d.device_secret as string });
}

/** Enrolls a device and has `owner` claim it as a frame called `name`. */
export async function claimFrame(ctx: APIRequestContext, owner: User, name: string) {
	const dev = await enroll(ctx);
	const { code } = await dev.post('/v1/device/code');
	const res = await owner.post('/v1/frames/redeem', { code });
	expect(res.status).toBe('claimed');
	const frame = res.frame as Json;
	await owner.patch(`/v1/frames/${frame.id}`, { name });
	return { dev, frameId: frame.id as string };
}

/** tus upload of one PNG; returns the upload id. */
export async function upload(u: Client, hue = 20, name = 'photo.png', w = 640, h = 480) {
	const data = await gradientPng(w, h, hue);
	const sum = createHash('sha256').update(data).digest('hex');
	const b64 = (s: string) => Buffer.from(s).toString('base64');
	const create = await u.raw('POST', '/v1/uploads', undefined, {
		'Tus-Resumable': '1.0.0',
		'Upload-Length': String(data.length),
		'Upload-Metadata': `filename ${b64(name)},filetype ${b64('image/png')},sha256 ${b64(sum)}`
	});
	expect(create.status, JSON.stringify(create.body)).toBe(201);
	const loc = create.headers['location']!;
	const r = await u.ctx.fetch(loc.startsWith('http') ? loc : `${BASE}${loc}`, {
		method: 'PATCH',
		headers: { Authorization: `Bearer ${u.token}`, 'Tus-Resumable': '1.0.0', 'Upload-Offset': '0', 'Content-Type': 'application/offset+octet-stream' },
		data
	});
	expect(r.status()).toBe(204);
	return loc.split('/').pop()!;
}

/** Sends photos to frames and families; returns the send. */
export async function send(u: Client, opts: { frames?: string[]; feeds?: string[]; circles?: string[]; n?: number; caption?: string; prompt?: string }) {
	const items = [];
	for (let i = 0; i < (opts.n ?? 1); i++) items.push({ upload_id: await upload(u, 20 + i * 40), ...(opts.caption ? { caption: opts.caption } : {}) });
	const body: Json = { frame_ids: opts.frames ?? [], feed_ids: opts.feeds ?? [], items };
	if (opts.circles) body.circle_ids = opts.circles;
	if (opts.prompt) body.prompt_id = opts.prompt;
	return u.post('/v1/sends', body, 202);
}

/** Waits until a send's items are processed. */
export async function ready(u: Client, sendId: string) {
	await expect
		.poll(async () => ((await u.get(`/v1/sends/${sendId}`)).items as Json[]).every((i) => i.status === 'ready'), { timeout: 30_000 })
		.toBe(true);
}

/** Waits for a send's post in a feed (and its photos) and returns its id. */
export async function postOf(u: Client, sendId: string, feedId: string): Promise<string> {
	let id: string | undefined;
	await expect
		.poll(async () => {
			const s = await u.get(`/v1/sends/${sendId}`);
			id = (s.feeds as Json[] | undefined)?.find((f) => f.feed_id === feedId)?.post_id;
			return id;
		})
		.toBeTruthy();
	await ready(u, sendId);
	return id!;
}

export async function createFamily(owner: User, name: string, extra: Json = {}) {
	const f = await owner.post('/v1/feeds', { name, ...extra }, 201);
	return f.id as string;
}

/** Joins `u` to the family through a join link of `admin` without approval. */
export async function join(admin: User, u: User, feedId: string) {
	await admin.patch(`/v1/feeds/${feedId}`, { invite_requires_approval: false });
	const inv = await admin.post(`/v1/feeds/${feedId}/invites`, { purpose: 'join' }, 201);
	const r = await u.post(`/v1/feed-invites/${inv.token}/accept`);
	expect(r.status).toBe('joined');
}

export interface Watch {
	/** API responses at or above 400 that the spec did not mark as expected. */
	failures: string[];
	errors: string[];
	allow(re: RegExp): void;
}

/** A browser context signed in as `u`, with failed calls and page errors recorded. */
export async function signedIn(browser: Browser, u: User): Promise<{ ctx: BrowserContext; page: Page; watch: Watch }> {
	const ctx = await browser.newContext();
	await ctx.addInitScript(
		([token]) => {
			try {
				localStorage.setItem('kinframe.devToken', token!);
				localStorage.setItem('kinframe.locale', 'en');
			} catch {
				/* ignore */
			}
		},
		[u.token]
	);
	const page = await ctx.newPage();
	// Weeks without a "seen and loved" summary answer 404 by contract.
	// Right after a send its thumbnails are still being made ("not available (yet)").
	const allowed: RegExp[] = [/^404 GET \/v1\/me\/weekly-summaries\//, /^404 GET \/v1\/items\/[^/]+\/media\/.*not available \(yet\)/];
	const watch: Watch = { failures: [], errors: [], allow: (re) => allowed.push(re) };
	page.on('response', async (r) => {
		if (r.status() < 400 || !r.url().startsWith(BASE)) return;
		const line = `${r.status()} ${r.request().method()} ${r.url().slice(BASE.length)} ${(await r.text().catch(() => '')).slice(0, 300)}`;
		if (!allowed.some((re) => re.test(line))) watch.failures.push(line);
	});
	page.on('pageerror', (e) => watch.errors.push(String(e)));
	page.on('console', (m) => {
		if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) watch.errors.push(m.text());
	});
	return { ctx, page, watch };
}

export function clean(w: Watch) {
	expect(w.failures, 'unexpected failed API calls').toEqual([]);
	expect(w.errors, 'page errors').toEqual([]);
}
