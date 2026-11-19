import {
	apiUrl,
	type BirthdayPerson,
	type EmailAddress,
	type EmailPendingSender,
	type EventInvitation,
	type FeedBirthdayGreeting,
	type FeedReceiptSummary,
	type GoogleImport,
	type JoinRequest,
	type PhotoSource,
	type ReplyPreset,
	type Send,
	type Subscription,
	type TelegramChat,
	type TelegramCode,
	type TelegramLink
} from './api/client';
import type { components } from './api/schema';
import { backoffDelay, type Reaction } from './logic';

type E = components['schemas'];

/** User websocket events (README §6.2). Unknown types are ignored. */
export type LiveEvent =
	| { type: 'send.updated'; send: Partial<Send> & { id: string } }
	| {
			type: 'reaction';
			placement_id: string;
			frame_id: string;
			reaction: Reaction | null;
			by_frame_name?: string;
			/** Set when the placement came through a feed (README §6.2). */
			feed_id?: string;
			post_id?: string;
	  }
	| { type: 'reply'; placement_id: string; frame_id: string; preset: ReplyPreset; feed_id?: string; post_id?: string }
	| { type: 'frame.status'; frame_id: string; online: boolean; last_seen_at: string | null }
	| { type: 'join_request'; frame_id: string; request: JoinRequest }
	| { type: 'membership.updated'; frame_id: string }
	| { type: 'event_invitation.decided'; frame_id: string; invitation: EventInvitation }
	// Feeds (docs/spec/feeds.md §8)
	| { type: 'feed_post.updated'; feed_id: string; post_id: string; summary: FeedReceiptSummary }
	| { type: 'subscription.request'; feed_id: string; subscription: Subscription }
	| { type: 'subscription.updated'; feed_id: string; frame_id: string; subscription: Subscription }
	| { type: 'feed.join_request'; feed_id: string; request: JoinRequest }
	| { type: 'feed.membership_updated'; feed_id: string }
	// Birthdays (docs/spec/birthdays.md §8)
	| { type: 'birthday.suggested'; feed_id: string; birthday: BirthdayPerson }
	| { type: 'birthday.decided'; feed_id: string; birthday: BirthdayPerson }
	| { type: 'birthday.updated'; feed_id: string; birthday: BirthdayPerson }
	| { type: 'birthday.removed'; feed_id: string; birthday_id: string }
	| { type: 'birthday.today'; date: string; birthdays: { feed_id: string; birthday_id: string }[] }
	| { type: 'birthday.greeting'; feed_id: string; greeting: FeedBirthdayGreeting }
	// Photo sources and Google Photos import (docs/spec/photo-sources.md §10)
	| { type: 'photo_source.updated'; source: PhotoSource }
	| { type: 'photo_source.removed'; source_id: string; album_id?: string; feed_id?: string }
	| { type: 'google_import.updated'; import: GoogleImport }
	// Email to frame (docs/spec/email-inbox.md §11)
	| { type: 'email_address.updated'; address: EmailAddress }
	| { type: 'email_address.removed'; address_id: string; frame_id?: string; feed_id?: string }
	| { type: 'email_sender.pending'; address_id: string; pending: EmailPendingSender }
	| { type: 'email_sender.decided'; address_id: string; sender_id: string; decision: string }
	// Telegram (docs/spec/telegram.md, README §5.17)
	| { type: 'telegram_chat.updated'; chat: TelegramChat }
	| { type: 'telegram_chat.removed'; chat_id: string; frame_id?: string; feed_id?: string }
	| { type: 'telegram_code.updated'; code: TelegramCode }
	| { type: 'telegram_link.updated'; link: TelegramLink | null }
	// Family social (README §6.2): payloads as in the contract's *Event schemas.
	| E['PostEvent']
	| E['PostRemovedEvent']
	| E['CommentEvent']
	| E['CommentDeletedEvent']
	| E['ThreadReadEvent']
	| E['ReactionUpdatedEvent']
	| E['CircleEvent']
	| E['CircleRemovedEvent']
	| E['ConversationEvent']
	| E['ConversationRemovedEvent']
	| E['MessageEvent']
	| E['ReadUpdatedEvent']
	| E['TypingEvent']
	| E['BadgeEvent']
	| E['ReportEvent']
	| E['PhotoRequestEvent']
	| E['StreakEvent']
	| E['PromptEvent']
	| E['MilestoneEvent']
	// Leaving and families on other servers (README §6.2, §5.21, §5.22)
	| E['KeepRequestEvent']
	| E['PlacementUpdatedEvent']
	| E['RemoteSubscriptionEvent'];

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
