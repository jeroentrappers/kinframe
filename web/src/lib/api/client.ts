import createClient, { type Middleware } from 'openapi-fetch';
import type { components, paths } from './schema';

export type Schemas = components['schemas'];
export type Discovery = Schemas['Discovery'];
export type Limits = Schemas['Limits'];
export type User = Schemas['User'];
export type FrameSummary = Schemas['FrameSummary'];
export type Frame = Schemas['Frame'];
export type Permissions = Schemas['Permissions'];
export type Role = Schemas['Role'];
export type Member = Schemas['Member'];
export type JoinRequest = Schemas['JoinRequest'];
export type Invite = Schemas['Invite'];
export type InvitePreview = Schemas['InvitePreview'];
export type RedeemResult = Schemas['RedeemResult'];
export type Send = Schemas['Send'];
export type SendFrame = Send['frames'][number];
export type Item = Schemas['Item'];
export type Placement = Schemas['Placement'];
export type FrameSettings = Schemas['FrameSettings'];
export type SleepSchedule = Schemas['SleepSchedule'];
export type FrameHealth = Schemas['FrameHealth'];
export type ProblemCode = Schemas['ProblemCode'];
export type Problem = Schemas['Problem'];
export type ReplyPreset = Schemas['ReplyPreset'];
export type FrameCommandType = Schemas['FrameCommand']['type'];
export type Album = Schemas['Album'];
export type AlbumSchedule = NonNullable<Album['schedule']>;
export type CalendarSource = Schemas['CalendarSource'];
export type CalendarSourceCreate = Schemas['CalendarSourceCreate'];
export type CalendarTestResult = Schemas['CalendarTestResult'];
export type CalendarView = Schemas['CalendarView'];
export type CalendarSchedule = Schemas['CalendarSchedule'];
export type EventInvitation = Schemas['EventInvitation'];

/** An RFC 9457 problem (or a network failure, code `network`). */
export class ApiError extends Error {
	readonly status: number;
	readonly code: ProblemCode | 'network';
	readonly title: string;
	readonly detail?: string;
	readonly retryAfter?: number;
	/** Field-level validation errors (422). */
	readonly errors?: Problem['errors'];

	constructor(
		status: number,
		code: ProblemCode | 'network',
		title: string,
		detail?: string,
		retryAfter?: number,
		errors?: Problem['errors']
	) {
		super(detail ?? title);
		this.name = 'ApiError';
		this.status = status;
		this.code = code;
		this.title = title;
		this.detail = detail;
		this.retryAfter = retryAfter;
		this.errors = errors;
	}

	static fromResponse(response: Response, body: unknown): ApiError {
		const p = (body ?? {}) as Partial<Problem>;
		const fallback: ProblemCode =
			response.status === 401
				? 'unauthorized'
				: response.status === 403
					? 'forbidden'
					: response.status === 404
						? 'not_found'
						: response.status === 412
							? 'version_conflict'
							: response.status === 429
								? 'rate_limited'
								: 'server_error';
		const retry = Number(response.headers.get('Retry-After'));
		return new ApiError(
			response.status,
			p.code ?? fallback,
			p.title ?? response.statusText,
			p.detail,
			Number.isFinite(retry) && retry > 0 ? retry : undefined,
			Array.isArray(p.errors) ? p.errors : undefined
		);
	}
}

// ------------------------------------------------------------------ wiring

interface Hooks {
	token: () => Promise<string | null>;
	locale: () => string;
	onUnauthorized: () => void;
}

let hooks: Hooks = {
	token: async () => null,
	locale: () => 'en',
	onUnauthorized: () => {}
};
let apiBase = '';

export function configureApi(partial: Partial<Hooks>) {
	hooks = { ...hooks, ...partial };
}

/** Origin of the API from discovery's `api` (".../v1"). Empty means same origin. */
export function setApiBase(apiUrl: string | undefined) {
	if (!apiUrl) return;
	try {
		const u = new URL(apiUrl, location.href);
		const base = `${u.origin}${u.pathname.replace(/\/v1\/?$/, '').replace(/\/$/, '')}`;
		apiBase = base === location.origin ? '' : base;
	} catch {
		apiBase = '';
	}
}

export function apiUrl(path: string): string {
	return `${apiBase}${path}`;
}

export async function authHeaders(): Promise<Record<string, string>> {
	const token = await hooks.token();
	return token ? { Authorization: `Bearer ${token}` } : {};
}

const auth: Middleware = {
	async onRequest({ request }) {
		const token = await hooks.token();
		if (token) request.headers.set('Authorization', `Bearer ${token}`);
		request.headers.set('Accept-Language', hooks.locale());
		return request;
	},
	async onResponse({ response }) {
		if (response.status === 401) hooks.onUnauthorized();
		return response;
	}
};

export const api = createClient<paths>({
	baseUrl: '',
	fetch: (req: Request) => {
		if (!apiBase) return fetch(req);
		const u = new URL(req.url);
		return fetch(new Request(`${apiBase}${u.pathname}${u.search}`, req));
	}
});
api.use(auth);

/**
 * Await an openapi-fetch call and return its data, or throw an ApiError.
 * Network failures become ApiError with code `network`.
 */
export async function call<T>(p: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
	let res: { data?: T; error?: unknown; response: Response };
	try {
		res = await p;
	} catch (e) {
		if (e instanceof ApiError) throw e;
		throw new ApiError(0, 'network', 'Network error', e instanceof Error ? e.message : undefined);
	}
	if (!res.response.ok) throw ApiError.fromResponse(res.response, res.error);
	return res.data as T;
}

/** Plain authenticated fetch for things outside the typed client (avatar bytes, media blobs). */
export async function authFetch(path: string, init: RequestInit = {}): Promise<Response> {
	const headers = new Headers(init.headers);
	for (const [k, v] of Object.entries(await authHeaders())) headers.set(k, v);
	headers.set('Accept-Language', hooks.locale());
	let res: Response;
	try {
		res = await fetch(apiUrl(path), { ...init, headers });
	} catch (e) {
		throw new ApiError(0, 'network', 'Network error', e instanceof Error ? e.message : undefined);
	}
	if (res.status === 401) hooks.onUnauthorized();
	if (!res.ok) {
		let body: unknown = null;
		try {
			body = await res.clone().json();
		} catch {
			/* not JSON */
		}
		throw ApiError.fromResponse(res, body);
	}
	return res;
}
