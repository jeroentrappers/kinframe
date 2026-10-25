// The device websocket (README §6.1) with the reconnect policy of README §6:
// exponential back-off from 1 s to 60 s with ±20 % jitter.

/** Close code for a revoked device or an expired token (README §3.2). */
export const UNAUTHORIZED = 4401;

/** Delay before reconnect attempt n (0-based). */
export function backoffMs(attempt: number, random: () => number = Math.random): number {
	const base = Math.min(60_000, 1000 * 2 ** Math.min(attempt, 6));
	return Math.round(base * (0.8 + 0.4 * random()));
}

export type ServerMessage =
	| { type: 'hello'; server_time?: string; latest_seq?: number }
	| { type: 'changes'; latest_seq?: number }
	| { type: 'command'; id: string; command_type: string; args?: Record<string, unknown> }
	| { type: 'claimed'; owner_name?: string }
	| { type: 'revoked' }
	| { type: string; [k: string]: unknown };

export interface SocketEvents {
	message(msg: ServerMessage): void;
	/** true once open, false when it drops. */
	status(open: boolean): void;
	/** Closed with 4401: refresh the token (or find out the device was revoked). Return false to stop. */
	unauthorized(): Promise<boolean>;
}

type WsLike = Pick<WebSocket, 'send' | 'close' | 'readyState'> & {
	onopen: ((e: Event) => void) | null;
	onmessage: ((e: MessageEvent) => void) | null;
	onclose: ((e: CloseEvent) => void) | null;
	onerror: ((e: Event) => void) | null;
};

export interface SocketOptions {
	url: () => Promise<string>;
	events: SocketEvents;
	create?: (url: string) => WsLike;
	random?: () => number;
	setTimer?: (fn: () => void, ms: number) => unknown;
	clearTimer?: (t: unknown) => void;
}

export class DeviceSocket {
	readonly #o: Required<SocketOptions>;
	#ws: WsLike | null = null;
	#attempt = 0;
	#timer: unknown = null;
	#stopped = true;

	constructor(o: SocketOptions) {
		this.#o = {
			create: (url) => new WebSocket(url) as unknown as WsLike,
			random: Math.random,
			setTimer: (fn, ms) => setTimeout(fn, ms),
			clearTimer: (t) => clearTimeout(t as ReturnType<typeof setTimeout>),
			...o
		};
	}

	get open(): boolean {
		return this.#ws?.readyState === 1;
	}

	start(): void {
		if (!this.#stopped) return;
		this.#stopped = false;
		void this.#connect();
	}

	stop(): void {
		this.#stopped = true;
		if (this.#timer) this.#o.clearTimer(this.#timer);
		this.#timer = null;
		const ws = this.#ws;
		this.#ws = null;
		try {
			ws?.close(1000, 'bye');
		} catch {
			/* already closed */
		}
	}

	/** Reconnect now (network came back, page became visible). */
	reconnectNow(): void {
		if (this.#stopped || this.open) return;
		if (this.#timer) this.#o.clearTimer(this.#timer);
		this.#timer = null;
		this.#attempt = 0;
		void this.#connect();
	}

	send(msg: unknown): boolean {
		if (!this.open) return false;
		try {
			this.#ws!.send(JSON.stringify(msg));
			return true;
		} catch {
			return false;
		}
	}

	async #connect(): Promise<void> {
		if (this.#stopped) return;
		let url: string;
		try {
			url = await this.#o.url();
		} catch {
			return this.#retry();
		}
		if (this.#stopped) return;
		let ws: WsLike;
		try {
			ws = this.#o.create(url);
		} catch {
			return this.#retry();
		}
		this.#ws = ws;
		ws.onopen = () => {
			this.#attempt = 0;
			this.#o.events.status(true);
		};
		ws.onmessage = (e) => {
			let msg: ServerMessage;
			try {
				msg = JSON.parse(String(e.data)) as ServerMessage;
			} catch {
				return;
			}
			if (msg && typeof msg.type === 'string') this.#o.events.message(msg);
		};
		ws.onerror = () => {
			/* onclose follows */
		};
		ws.onclose = (e) => {
			if (this.#ws !== ws) return;
			this.#ws = null;
			this.#o.events.status(false);
			if (this.#stopped) return;
			if (e.code === UNAUTHORIZED) {
				void this.#o.events.unauthorized().then((again) => (again ? this.#retry() : this.stop()));
				return;
			}
			this.#retry();
		};
	}

	#retry(): void {
		if (this.#stopped || this.#timer) return;
		const ms = backoffMs(this.#attempt++, this.#o.random);
		this.#timer = this.#o.setTimer(() => {
			this.#timer = null;
			void this.#connect();
		}, ms);
	}
}
