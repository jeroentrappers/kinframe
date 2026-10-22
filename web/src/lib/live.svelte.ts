import { apiUrl, type EventInvitation, type JoinRequest, type ReplyPreset, type Send } from './api/client';
import { backoffDelay, type Reaction } from './logic';

/** User websocket events (README §6.2). Unknown types are ignored. */
export type LiveEvent =
	| { type: 'send.updated'; send: Partial<Send> & { id: string } }
	| { type: 'reaction'; placement_id: string; frame_id: string; reaction: Reaction | null; by_frame_name?: string }
	| { type: 'reply'; placement_id: string; frame_id: string; preset: ReplyPreset }
	| { type: 'frame.status'; frame_id: string; online: boolean; last_seen_at: string | null }
	| { type: 'join_request'; frame_id: string; request: JoinRequest }
	| { type: 'membership.updated'; frame_id: string }
	| { type: 'event_invitation.decided'; frame_id: string; invitation: EventInvitation };

type Handler = (e: LiveEvent) => void;

class LiveSocket {
	status = $state<'idle' | 'connecting' | 'open' | 'waiting'>('idle');

	#ws: WebSocket | null = null;
	#handlers = new Set<Handler>();
	#attempt = 0;
	#timer: ReturnType<typeof setTimeout> | null = null;
	#token: (() => Promise<string | null>) | null = null;
	#stopped = true;

	start(token: () => Promise<string | null>) {
		this.#token = token;
		if (!this.#stopped) return;
		this.#stopped = false;
		window.addEventListener('online', this.#kick);
		document.addEventListener('visibilitychange', this.#kick);
		void this.#connect();
	}

	stop() {
		this.#stopped = true;
		window.removeEventListener('online', this.#kick);
		document.removeEventListener('visibilitychange', this.#kick);
		if (this.#timer) clearTimeout(this.#timer);
		this.#timer = null;
		this.#ws?.close(1000);
		this.#ws = null;
		this.status = 'idle';
	}

	on(handler: Handler): () => void {
		this.#handlers.add(handler);
		return () => this.#handlers.delete(handler);
	}

	/** Reconnect right away when the tab comes back or the network returns. */
	#kick = () => {
		if (this.#stopped || document.visibilityState === 'hidden') return;
		if (this.status === 'waiting') {
			if (this.#timer) clearTimeout(this.#timer);
			this.#attempt = 0;
			void this.#connect();
		}
	};

	async #connect() {
		if (this.#stopped) return;
		this.status = 'connecting';
		const token = await this.#token?.();
		if (this.#stopped) return;
		if (!token) return this.#schedule();
		const http = new URL(apiUrl('/v1/ws'), location.href);
		http.protocol = http.protocol === 'https:' ? 'wss:' : 'ws:';
		http.searchParams.set('access_token', token);
		let ws: WebSocket;
		try {
			ws = new WebSocket(http.toString());
		} catch {
			return this.#schedule();
		}
		this.#ws = ws;
		ws.onopen = () => {
			this.#attempt = 0;
			this.status = 'open';
		};
		ws.onmessage = (msg) => {
			let event: LiveEvent;
			try {
				event = JSON.parse(String(msg.data));
			} catch {
				return;
			}
			if (!event || typeof event.type !== 'string') return;
			for (const h of this.#handlers) {
				try {
					h(event);
				} catch (e) {
					console.error('live handler failed', e);
				}
			}
		};
		ws.onclose = (ev) => {
			if (this.#ws !== ws) return;
			this.#ws = null;
			if (this.#stopped) return;
			// 4401: token expired; reconnect immediately with a fresh one.
			if (ev.code === 4401) {
				this.#attempt = 0;
				void this.#connect();
				return;
			}
			this.#schedule();
		};
	}

	#schedule() {
		if (this.#stopped) return;
		this.status = 'waiting';
		const delay = backoffDelay(this.#attempt++);
		this.#timer = setTimeout(() => void this.#connect(), delay);
	}
}

export const live = new LiveSocket();
