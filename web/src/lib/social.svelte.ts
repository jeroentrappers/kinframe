// Family social state shared across pages: the app badge and the chat list (README §5.18–§5.19).

import { api, call, type Badge, type Conversation, type Message } from './api/client';
import { auth } from './auth.svelte';
import { bumpConversation, sortConversations, unreadConversations } from './chat';
import { discovery } from './discovery.svelte';
import type { LiveEvent } from './live.svelte';
import { badgeTitle } from './social';

const FAVICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect x="4" y="8" width="56" height="48" rx="10" fill="#b4532a"/><rect x="11" y="15" width="42" height="34" rx="5" fill="#fbf1e6"/><circle cx="41" cy="25" r="5" fill="#e9a23b"/><path d="M11 44l12-12 9 9 6-6 15 15H16a5 5 0 0 1-5-5z" fill="#4f7a5a"/>';

/** The app badge (`getBadge`, `badge.updated`): in the tab title, on the favicon, and on installed apps' icons. */
class BadgeState {
	badge = $state<Badge>({ total: 0, conversations: 0, threads: 0 });
	#observer: MutationObserver | null = null;

	get enabled() {
		return discovery.social || discovery.chats;
	}

	async load() {
		if (!this.enabled) return;
		try {
			this.set(await call(api.GET('/v1/me/badge')));
		} catch {
			/* keep the last known count */
		}
	}

	set(b: Badge) {
		this.badge = b;
		this.#paint();
	}

	reset() {
		this.badge = { total: 0, conversations: 0, threads: 0 };
		this.#paint();
	}

	/** Keep the count in the tab title even when pages change their <title>. */
	watchTitle() {
		if (typeof document === 'undefined' || this.#observer) return;
		this.#observer = new MutationObserver(() => this.#title());
		this.#observer.observe(document.head, { subtree: true, childList: true, characterData: true });
		this.#title();
	}

	#title() {
		const want = badgeTitle(document.title, this.badge.total);
		if (document.title !== want) document.title = want;
	}

	#paint() {
		if (typeof document === 'undefined') return;
		this.#title();
		const link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
		if (link) {
			const svg = this.badge.total > 0 ? `${FAVICON}<circle cx="52" cy="12" r="11" fill="#d93f3f" stroke="#fff" stroke-width="3"/></svg>` : `${FAVICON}</svg>`;
			link.href = `data:image/svg+xml,${encodeURIComponent(svg)}`;
		}
		const nav = navigator as Navigator & { setAppBadge?: (n?: number) => Promise<void>; clearAppBadge?: () => Promise<void> };
		try {
			if (this.badge.total > 0) void nav.setAppBadge?.(this.badge.total)?.catch(() => {});
			else void nav.clearAppBadge?.()?.catch(() => {});
		} catch {
			/* not an installed app */
		}
	}
}

export const badge = new BadgeState();

/** The caller's conversations (the Chats tab), kept current by websocket events. */
class ChatsState {
	list = $state<Conversation[]>([]);
	loaded = $state(false);
	error = $state<unknown>(null);
	/** The conversation on screen: its new messages don't count as unread. */
	openId = $state<string | null>(null);
	#inflight: Promise<void> | null = null;

	get unread() {
		return unreadConversations(this.list);
	}

	load(force = false): Promise<void> {
		if (!discovery.chats) return Promise.resolve();
		if (this.loaded && !force) return Promise.resolve();
		this.#inflight ??= (async () => {
			try {
				this.list = sortConversations(await call(api.GET('/v1/conversations')));
				this.error = null;
				this.loaded = true;
			} catch (e) {
				this.error = e;
			} finally {
				this.#inflight = null;
			}
		})();
		return this.#inflight;
	}

	get(id: string) {
		return this.list.find((c) => c.id === id);
	}

	upsert(c: Conversation) {
		const i = this.list.findIndex((x) => x.id === c.id);
		const next = i >= 0 ? [...this.list.slice(0, i), c, ...this.list.slice(i + 1)] : [...this.list, c];
		this.list = sortConversations(next.filter((x) => !x.archived));
	}

	remove(id: string) {
		this.list = this.list.filter((c) => c.id !== id);
	}

	markRead(id: string) {
		const c = this.get(id);
		if (c && (c.unread_count || c.unread_mentions)) this.upsert({ ...c, unread_count: 0, unread_mentions: 0 });
	}

	#message(m: Message) {
		this.list = bumpConversation(this.list, m, auth.me?.id, this.openId === m.conversation_id && document.visibilityState === 'visible');
	}

	/** Websocket events for the list (README §6.2). */
	handle(e: LiveEvent) {
		if (!discovery.chats) return;
		if (e.type === 'conversation.updated') {
			if (e.conversation.archived) this.remove(e.conversation.id);
			else this.upsert(e.conversation);
		} else if (e.type === 'conversation.removed') this.remove(e.conversation_id);
		else if (e.type === 'message.created') {
			if (this.get(e.conversation_id)) this.#message(e.message);
			else void this.load(true); // un-archived, or a chat we didn't know yet
		} else if (e.type === 'message.updated') {
			const c = this.get(e.conversation_id);
			if (c?.last_message?.id === e.message.id) this.upsert({ ...c, last_message: e.message });
		} else if (e.type === 'read.updated' && e.user_id === auth.me?.id) this.markRead(e.conversation_id);
		// Family and circle chats follow their family and circle (README §5.18).
		else if (e.type === 'circle.updated' || e.type === 'circle.removed' || e.type === 'feed.membership_updated') void this.load(true);
	}

	reset() {
		this.list = [];
		this.loaded = false;
		this.openId = null;
	}
}

export const chats = new ChatsState();
