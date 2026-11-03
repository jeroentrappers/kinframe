// A fake kinframe server for the device-protocol unit tests: answers the device calls
// the browser frame makes, from an in-memory frame (README §3.2, §5.4).
import type { Change } from './client';

export interface FakeCall {
	method: string;
	path: string;
	body: unknown;
	auth: string | null;
}

export function placementChange(id: string, o: Partial<{ caption: string; sender: string; hidden: boolean; kind: 'photo' | 'video'; received: string; albums: string[]; media: boolean }> = {}): NonNullable<Change['placement']> {
	return {
		id,
		frame_id: 'f1',
		status: 'ready',
		hidden: o.hidden ?? false,
		trashed: false,
		received_at: o.received ?? '2026-10-01T10:00:00Z',
		album_ids: o.albums ?? [],
		item: {
			id: `i-${id}`,
			kind: o.kind ?? 'photo',
			status: 'ready',
			sender_id: 'u1',
			sender_name: o.sender ?? 'Anna',
			caption: o.caption ?? null,
			created_at: o.received ?? '2026-10-01T10:00:00Z',
			width: 1600,
			height: 1200
		},
		...(o.media === false ? {} : { media: { url: `https://k.test/v1/device/media/${id}`, sha256: 'x', bytes: 1, mime: 'image/webp', width: 1600, height: 1200 } })
	} as NonNullable<Change['placement']>;
}

export class FakeServer {
	calls: FakeCall[] = [];
	claimed = false;
	revoked = false;
	tokenSeq = 0;
	/** Expire the next authenticated call's token once. */
	expireOnce = false;
	feed: Change[] = [];
	frameName = 'TV frame';

	fetch = async (input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> => {
		const url = new URL(String(input), 'https://k.test');
		const method = init.method ?? 'GET';
		const headers = new Headers(init.headers);
		const body = init.body ? JSON.parse(String(init.body)) : undefined;
		const auth = headers.get('Authorization');
		this.calls.push({ method, path: url.pathname + url.search, body, auth });
		const json = (status: number, v: unknown) => new Response(v === null ? null : JSON.stringify(v), { status, headers: { 'Content-Type': 'application/json' } });
		const problem = (status: number, code: string) => json(status, { type: 'about:blank', title: code, status, code });

		if (url.pathname === '/v1/devices/enroll') return json(201, { device_id: 'd1', device_secret: 'secret' });
		if (url.pathname === '/v1/devices/token') {
			if (this.revoked) return problem(401, 'device_revoked');
			return json(200, { access_token: `tok${++this.tokenSeq}`, expires_in: 3600 });
		}
		if (url.pathname.startsWith('/v1/device/')) {
			if (this.revoked) return problem(401, 'device_revoked');
			if (!auth?.startsWith('Bearer tok')) return problem(401, 'unauthorized');
			if (this.expireOnce) {
				this.expireOnce = false;
				return problem(401, 'token_expired');
			}
		}
		switch (`${method} ${url.pathname}`) {
			case 'GET /v1/device/state':
				return json(200, { device_id: 'd1', claimed: this.claimed, latest_seq: this.feed.length, ...(this.claimed ? { frame_id: 'f1', frame_name: this.frameName, owner_name: 'Olga' } : {}) });
			case 'POST /v1/device/code':
				return json(200, { code: '12345678', expires_at: new Date(Date.now() + 12 * 3600_000).toISOString(), join_url: 'https://k.test/f/12345678', claimed: this.claimed });
			case 'GET /v1/device/changes': {
				const since = Number(url.searchParams.get('since'));
				const changes = this.feed.filter((c) => c.seq > since);
				return json(200, { changes, next_seq: changes.length ? changes[changes.length - 1]!.seq : since, has_more: false });
			}
			case 'POST /v1/device/acks':
			case 'POST /v1/device/heartbeat':
				return json(204, null);
		}
		if (method === 'PUT' && /^\/v1\/device\/placements\/[^/]+\/reaction$/.test(url.pathname)) return json(204, null);
		return problem(404, 'not_found');
	};

	push(c: Omit<Change, 'seq'>): void {
		this.feed.push({ ...c, seq: this.feed.length + 1 } as Change);
	}
}

/** A websocket stand-in the tests drive by hand. */
export class FakeSocket {
	static last: FakeSocket | null = null;
	readyState = 0;
	sent: unknown[] = [];
	onopen: ((e: Event) => void) | null = null;
	onmessage: ((e: MessageEvent) => void) | null = null;
	onclose: ((e: CloseEvent) => void) | null = null;
	onerror: ((e: Event) => void) | null = null;

	constructor(readonly url: string) {
		FakeSocket.last = this;
	}

	open(): void {
		this.readyState = 1;
		this.onopen?.(new Event('open'));
	}

	receive(msg: unknown): void {
		this.onmessage?.({ data: JSON.stringify(msg) } as MessageEvent);
	}

	send(data: string): void {
		this.sent.push(JSON.parse(data));
	}

	close(code = 1000): void {
		this.readyState = 3;
		this.onclose?.({ code } as CloseEvent);
	}
}
