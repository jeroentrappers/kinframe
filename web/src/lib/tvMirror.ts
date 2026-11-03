// TV mirror (README §6.4): the TV follows the physical frame live over
// GET /v1/tv/{token}/mirror. This turns the server's `mirror` messages into what the stage
// renders, and keeps the websocket connected.
import type { components } from './api/schema';
import { backoffMs } from './device/socket';
import { kenBurnsKeys } from './kenburns';
import { clockSkew, type StageSlide, type StageTile } from './tvstage';

export type MirrorState = components['schemas']['MirrorState'];
export type MirrorSlide = components['schemas']['TvSlide'];

export type MirrorScreen = 'showing' | 'waiting' | 'asleep' | 'offline';

/** What the TV page should show for a state. */
export function mirrorScreen(st: MirrorState | null): MirrorScreen {
	if (!st) return 'waiting';
	if (!st.online) return 'offline';
	if (st.screen === 'off' || st.screen === 'sleeping') return 'asleep';
	return st.showing ? 'showing' : 'waiting';
}

export function tileOf(s: MirrorSlide, fill: boolean): StageTile {
	return {
		id: s.placement_id,
		kind: s.kind,
		src: s.url,
		poster: s.poster_url ?? null,
		width: s.width,
		height: s.height,
		fit: s.fit ?? (fill ? 'fill' : 'fit'),
		focal: s.focal_point ?? null,
		rotation: s.rotation ?? 0
	};
}

/**
 * The stage slide for a mirror state, in local time: `localNow` is when the message
 * arrived, so the server's clock is translated with the message's own `server_time`.
 */
export function mirrorSlide(st: MirrorState, localNow: number): StageSlide | null {
	const sh = st.showing;
	if (!sh || !sh.slides.length) return null;
	const skew = clockSkew(st.server_time, localNow);
	const toLocal = (iso: string) => {
		const t = Date.parse(iso);
		return Number.isFinite(t) ? t + skew : localNow;
	};
	const fill = st.settings?.fill ?? true;
	const first = sh.slides[0]!;
	const kb = sh.ken_burns && sh.slides.length === 1 && first.kind !== 'video';
	return {
		key: `${sh.placement_id}@${sh.started_at}`,
		tiles: sh.slides.map((s) => tileOf(s, fill)),
		transition: sh.transition,
		transitionMs: sh.transition_ms,
		direction: (sh.direction ?? 1) < 0 ? -1 : 1,
		startedAt: toLocal(sh.started_at),
		kenBurns: kb ? kenBurnsKeys(first.placement_id, first.focal_point?.x ?? null, first.focal_point?.y ?? null) : null,
		holdMs: sh.hold_ms ?? (st.settings?.interval_s ?? 30) * 1000,
		video: sh.video ? { positionMs: sh.video.position_ms, at: toLocal(sh.video.at), playing: sh.video.playing } : null
	};
}

/** The websocket URL for a mirror token, next to the API. */
export function mirrorUrl(apiBase: string, pageOrigin: string, token: string): string {
	return `${(apiBase || pageOrigin).replace(/^http/, 'ws')}/v1/tv/${encodeURIComponent(token)}/mirror`;
}

export interface MirrorEvents {
	state(st: MirrorState, receivedAt: number): void;
	/** The link was revoked or expired (the page shows the matching notice). */
	ended(reason: 'revoked' | 'expired' | 'invalid'): void;
	connected(open: boolean): void;
}

/** Keeps a mirror websocket open, reconnecting with back-off; stops for good when the link ends. */
export class MirrorConnection {
	#ws: WebSocket | null = null;
	#attempt = 0;
	#timer: ReturnType<typeof setTimeout> | null = null;
	#stopped = false;
	#gotState = false;

	constructor(
		readonly url: string,
		readonly events: MirrorEvents,
		readonly create: (url: string) => WebSocket = (u) => new WebSocket(u),
		/** A plain GET of the same URL tells why an upgrade failed (404, 410, 429): websockets cannot. */
		readonly probe: (httpUrl: string) => Promise<number> = async (u) => (await fetch(u, { headers: { Accept: 'application/json' } })).status
	) {}

	start(): void {
		this.#stopped = false;
		this.#connect();
	}

	stop(): void {
		this.#stopped = true;
		if (this.#timer) clearTimeout(this.#timer);
		this.#ws?.close(1000);
		this.#ws = null;
	}

	reconnectNow(): void {
		if (this.#stopped || this.#ws) return;
		if (this.#timer) clearTimeout(this.#timer);
		this.#timer = null;
		this.#connect();
	}

	#connect(): void {
		let ws: WebSocket;
		try {
			ws = this.create(this.url);
		} catch {
			return this.#retry();
		}
		this.#ws = ws;
		this.#gotState = false;
		ws.onopen = () => this.events.connected(true);
		ws.onmessage = (e) => {
			let m: { type?: string; reason?: string };
			try {
				m = JSON.parse(String(e.data));
			} catch {
				return;
			}
			if (m.type === 'mirror') {
				this.#attempt = 0;
				this.#gotState = true;
				this.events.state(m as MirrorState, Date.now());
			} else if (m.type === 'ended') {
				this.#stopped = true;
				this.events.ended(m.reason === 'expired' ? 'expired' : 'revoked');
			}
		};
		ws.onclose = (e) => {
			if (this.#ws !== ws) return;
			this.#ws = null;
			this.events.connected(false);
			if (this.#stopped) return;
			// Refused before any state (404/410 answer to the upgrade): the link does not work.
			if (e.code === 4401) {
				this.#stopped = true;
				this.events.ended('revoked');
				return;
			}
			if (this.#gotState) return this.#retry();
			void this.probe(this.url.replace(/^ws/, 'http'))
				.then((status) => {
					if (status === 404 || status === 410) {
						this.#stopped = true;
						this.events.ended(status === 410 ? 'expired' : 'invalid');
					} else this.#retry();
				})
				.catch(() => this.#retry());
		};
	}

	#retry(): void {
		if (this.#stopped || this.#timer) return;
		this.#timer = setTimeout(() => {
			this.#timer = null;
			this.#connect();
		}, backoffMs(this.#attempt++));
	}
}
