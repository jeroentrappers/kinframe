// The device protocol (README §3.2, §5.4–5.6, §6.1) for a browser enrolled as a frame.
// Plain fetch, no generated client: the device API uses its own short-lived token and
// must work on TV browsers with nothing but fetch and WebSocket.
import type { components } from '../api/schema';
import type { CredentialStore, DeviceCredentials } from './credentials';

type S = components['schemas'];
export type DeviceEnroll = S['DeviceEnroll'];
export type DeviceState = S['DeviceState'];
export type FrameCode = S['FrameCode'];
export type Change = S['Change'];
export type Heartbeat = S['Heartbeat'];
export type Reaction = S['Reaction'];
export type ReplyPreset = S['ReplyPreset'];

export interface ChangesPage {
	changes: Change[];
	next_seq: number;
	has_more: boolean;
}

/** The credential was revoked (frame removed) or is unknown: wipe it and enrol again. */
export class DeviceRevokedError extends Error {
	constructor() {
		super('device revoked');
		this.name = 'DeviceRevokedError';
	}
}

/** Any other failed device call; `status` 0 means the network. */
export class DeviceApiError extends Error {
	constructor(
		readonly status: number,
		readonly code: string
	) {
		super(`${status} ${code}`);
		this.name = 'DeviceApiError';
	}
}

export interface DeviceClientOptions {
	/** API origin from discovery ('' = this page's origin). */
	base: string;
	credentials: CredentialStore;
	fetch?: typeof fetch;
	now?: () => number;
	/** Accept-Language for server texts (default frame name, errors). */
	locale?: () => string;
}

/** Refresh the access token when less than this is left (README §3.2). */
const REFRESH_MARGIN_MS = 5 * 60_000;

export class DeviceClient {
	readonly #base: string;
	readonly #creds: CredentialStore;
	readonly #fetch: typeof fetch;
	readonly #now: () => number;
	readonly #locale: () => string;
	#token: string | null = null;
	#expiresAt = 0;
	#refreshing: Promise<string> | null = null;

	constructor(o: DeviceClientOptions) {
		this.#base = o.base;
		this.#creds = o.credentials;
		this.#fetch = o.fetch ?? ((...a) => fetch(...a));
		this.#now = o.now ?? Date.now;
		this.#locale = o.locale ?? (() => 'en');
	}

	get credentials(): DeviceCredentials | null {
		return this.#creds.load();
	}

	/** POST /v1/devices/enroll as a browser frame; stores and returns the credential. */
	async enroll(body: Omit<DeviceEnroll, 'kind'>): Promise<DeviceCredentials> {
		const res = await this.#raw('POST', '/v1/devices/enroll', { ...body, kind: 'browser' });
		const out = (await res.json()) as { device_id: string; device_secret: string };
		const c: DeviceCredentials = { device_id: out.device_id, device_secret: out.device_secret, enrolled_at: new Date(this.#now()).toISOString() };
		this.#creds.save(c);
		this.#token = null;
		return c;
	}

	/** Forget the credential and token (after revocation). */
	forget(): void {
		this.#creds.clear();
		this.#token = null;
		this.#expiresAt = 0;
	}

	/** A valid access token, refreshed when it is about to expire (or when forced). */
	async token(force = false): Promise<string> {
		if (!force && this.#token && this.#expiresAt - this.#now() > REFRESH_MARGIN_MS) return this.#token;
		this.#refreshing ??= this.#refresh().finally(() => (this.#refreshing = null));
		return this.#refreshing;
	}

	/** The cached token without refreshing (for URLs built while rendering), or null. */
	get currentToken(): string | null {
		return this.#token && this.#expiresAt > this.#now() ? this.#token : null;
	}

	async #refresh(): Promise<string> {
		const c = this.#creds.load();
		if (!c) throw new DeviceRevokedError();
		let res: Response;
		try {
			res = await this.#raw('POST', '/v1/devices/token', { device_id: c.device_id, device_secret: c.device_secret });
		} catch (e) {
			// An unknown device or wrong secret is as final as a revocation.
			if (e instanceof DeviceApiError && (e.code === 'device_revoked' || e.code === 'unauthorized')) throw new DeviceRevokedError();
			throw e;
		}
		const t = (await res.json()) as { access_token: string; expires_in: number };
		this.#token = t.access_token;
		this.#expiresAt = this.#now() + t.expires_in * 1000;
		return t.access_token;
	}

	async #raw(method: string, path: string, body?: unknown, token?: string): Promise<Response> {
		const headers: Record<string, string> = { Accept: 'application/json', 'Accept-Language': this.#locale() };
		if (body !== undefined) headers['Content-Type'] = 'application/json';
		if (token) headers['Authorization'] = `Bearer ${token}`;
		let res: Response;
		try {
			res = await this.#fetch(`${this.#base}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
		} catch {
			throw new DeviceApiError(0, 'network');
		}
		if (!res.ok) {
			let code = '';
			try {
				code = ((await res.json()) as { code?: string }).code ?? '';
			} catch {
				/* not JSON */
			}
			throw new DeviceApiError(res.status, code || String(res.status));
		}
		return res;
	}

	/** An authenticated device call: refreshes and retries once on token_expired (README §3.2). */
	async call<T>(method: string, path: string, body?: unknown): Promise<T | null> {
		for (let attempt = 0; ; attempt++) {
			const token = await this.token(attempt > 0);
			try {
				const res = await this.#raw(method, path, body, token);
				if (res.status === 204) return null;
				const text = await res.text();
				return text ? (JSON.parse(text) as T) : null;
			} catch (e) {
				if (!(e instanceof DeviceApiError)) throw e;
				if (e.code === 'device_revoked') throw new DeviceRevokedError();
				if (e.status === 401 && attempt === 0) continue;
				throw e;
			}
		}
	}

	state(): Promise<DeviceState | null> {
		return this.call<DeviceState>('GET', '/v1/device/state');
	}

	code(): Promise<FrameCode | null> {
		return this.call<FrameCode>('POST', '/v1/device/code');
	}

	changes(since: number, limit = 500): Promise<ChangesPage | null> {
		return this.call<ChangesPage>('GET', `/v1/device/changes?since=${since}&limit=${limit}`);
	}

	acks(delivered: string[], seen: { placement_id: string; at: string }[]): Promise<null> {
		return this.call<null>('POST', '/v1/device/acks', { delivered, seen });
	}

	heartbeat(hb: Heartbeat): Promise<null> {
		return this.call<null>('POST', '/v1/device/heartbeat', hb);
	}

	reaction(placementId: string, reaction: Reaction | null): Promise<null> {
		return this.call<null>('PUT', `/v1/device/placements/${encodeURIComponent(placementId)}/reaction`, { reaction });
	}

	reply(placementId: string, preset: ReplyPreset): Promise<null> {
		return this.call<null>('POST', `/v1/device/placements/${encodeURIComponent(placementId)}/replies`, { preset });
	}

	/**
	 * A frame-rendition URL an <img> or <video> can load: the token goes in the query
	 * string, the one device call besides the websocket that accepts it there.
	 */
	mediaUrl(placementId: string, variant?: 'poster' | 'motion' | 'avatar'): string | null {
		const t = this.currentToken;
		if (!t) return null;
		const q = new URLSearchParams();
		if (variant) q.set('variant', variant);
		q.set('access_token', t);
		return `${this.#base}/v1/device/media/${encodeURIComponent(placementId)}?${q}`;
	}

	/** The device websocket URL with a fresh token (browsers cannot set headers on websockets). */
	async socketUrl(origin: string): Promise<string> {
		const t = await this.token();
		const base = this.#base || origin;
		return `${base.replace(/^http/, 'ws')}/v1/device/ws?access_token=${encodeURIComponent(t)}`;
	}
}
