// Shared by the service worker and the app: what a push message (docs/api/README.md §6.3) means.

export interface PushPayload {
	type?: string;
	key?: string;
	title?: string;
	body?: string;
	/** Set by the server when notifications should replace each other (birthday greetings: `birthday:<id>:<date>`). */
	tag?: string;
	ids?: Record<string, string>;
	/** Notification buttons (README §6.3): `snooze_month`, `fewer_reminders` on reminders. */
	actions?: string[];
	/** The sending server's base URL (README §6.3). The web is signed in to one server, so it only reads it. */
	server?: string;
}

/** The notification buttons the app knows (others are ignored). */
export const PUSH_ACTIONS = ['snooze_month', 'fewer_reminders'] as const;
export type KnownPushAction = (typeof PUSH_ACTIONS)[number];

export function pushActions(p: PushPayload): KnownPushAction[] {
	return (p.actions ?? []).filter((a): a is KnownPushAction => (PUSH_ACTIONS as readonly string[]).includes(a));
}

/** What a notification button changes (`updateReminderPreferences`). */
export function actionBody(a: KnownPushAction): { snooze_days: number } | { level: 'fewer' } {
	return a === 'snooze_month' ? { snooze_days: 30 } : { level: 'fewer' };
}

const ID = /^[0-9a-fA-F-]{8,64}$/;
const id = (v: string | undefined) => (v && ID.test(v) ? v : null);
const TAG = /^[\w:.-]{1,128}$/;

/** The page a notification opens. */
export function pushUrl(p: PushPayload): string {
	const send = id(p.ids?.send_id);
	const frame = id(p.ids?.frame_id);
	const feed = id(p.ids?.feed_id);
	const post = id(p.ids?.post_id);
	// Family social (README §6.3): threads, chats, reports, frame requests and reminders.
	const conv = id(p.ids?.conversation_id);
	const comment = id(p.ids?.comment_id);
	if ((p.key === 'comment' || p.key === 'comment_reply' || p.key === 'mention') && post) return `/posts/${post}${comment ? `?comment=${comment}` : ''}`;
	if ((p.key === 'message' || p.key === 'mention' || p.key === 'conversation_added') && conv) return `/chats/${conv}`;
	if (p.key === 'reaction_person') {
		if (conv) return `/chats/${conv}`;
		if (post) return `/posts/${post}`;
	}
	if (p.key === 'reaction_digest') return '/home';
	if (p.key === 'report') return feed ? `/feeds/${feed}?tab=reports` : '/settings/reports';
	if ((p.key === 'frame_request' || p.key === 'reminder_quiet') && frame) return `/send?frame=${frame}`;
	if (p.key === 'reminder_streak' && feed) return `/feeds/${feed}`;
	if (p.key === 'reminder_birthday' && feed) return `/send?feed=${feed}`;
	if (p.key === 'weekly_summary') {
		const week = p.ids?.week_of;
		return week && /^\d{4}-\d{2}-\d{2}$/.test(week) ? `/home?week=${week}` : '/home';
	}
	// Leaving (README §6.3, §5.21): the frame's trash, the family, the person's history.
	const request = id(p.ids?.request_id);
	if (p.key === 'keep_request') return request ? `/history?keep=${request}` : '/history';
	if (p.key === 'photos_taken_away' || p.key === 'keep_answered') {
		if (frame) return `/frames/${frame}?tab=library&view=trashed`;
		if (feed) return `/feeds/${feed}`;
	}
	if (p.key === 'ownership_changed') {
		if (frame) return `/frames/${frame}`;
		if (feed) return `/feeds/${feed}`;
	}
	// Feeds (README §6.3).
	if (p.key === 'subscription_request' && feed) return `/feeds/${feed}?tab=subscriptions`;
	if (p.key === 'feed_join_request' && feed) return `/feeds/${feed}?tab=members`;
	if (p.key === 'feed_membership') return feed ? `/feeds/${feed}` : '/feeds';
	if ((p.key === 'subscription_decided' || p.key === 'subscription_removed') && frame) return `/frames/${frame}?tab=feeds`;
	if ((p.key === 'subscription_decided' || p.key === 'subscription_removed') && feed) return `/feeds/${feed}`;
	if ((p.key === 'reaction' || p.key === 'reply') && feed && post) return `/feeds/${feed}?post=${post}`;
	// Birthdays (README §6.3): the feed's Birthdays tab.
	if (p.key?.startsWith('birthday_')) return feed ? `/feeds/${feed}?tab=birthdays` : '/feeds';
	// Photo sources (README §6.3): the source's album or feed, else the list of my sources; a finished Google import.
	if (p.key === 'photo_source_attention') {
		const album = id(p.ids?.album_id);
		if (feed) return `/feeds/${feed}?tab=sources`;
		if (frame && album) return `/frames/${frame}?tab=library&album=${album}`;
		return '/photo-sources';
	}
	if (p.key === 'google_import_done') {
		const imp = id(p.ids?.import_id);
		return imp ? `/photo-sources?google_import=${imp}` : '/photo-sources';
	}
	// Email to frame (README §5.16): the address's pending list, in the frame's or feed's settings.
	if (p.key === 'email_pending_sender' || p.key === 'email_pending_expiring') {
		const address = id(p.ids?.address_id);
		const q = address ? `&email=${address}` : '';
		if (frame) return `/frames/${frame}?tab=settings${q}`;
		if (feed) return `/feeds/${feed}?tab=settings${q}`;
	}
	// Telegram (README §5.17): the chat in the frame's or feed's settings.
	if (p.key === 'telegram_attention') {
		const chat = id(p.ids?.chat_id);
		const q = chat ? `&telegram=${chat}` : '';
		if (frame) return `/frames/${frame}?tab=settings${q}`;
		if (feed) return `/feeds/${feed}?tab=settings${q}`;
	}
	if (p.key === 'join_request' && frame) return `/frames/${frame}?tab=people`;
	if ((p.type === 'event_invitation.decided' || p.key?.startsWith('event_invitation')) && frame) return `/frames/${frame}?tab=calendar`;
	if (send) return `/history/${send}`;
	if (frame) return `/frames/${frame}`;
	return '/history';
}

/** Collapses repeated notifications about the same thing (e.g. several reactions on one send). */
export function pushTag(p: PushPayload, now: Date = new Date()): string {
	if (typeof p.tag === 'string' && TAG.test(p.tag)) return p.tag;
	const ids = p.ids ?? {};
	// Greetings for one person and day replace each other (the body counts the frames); the date comes with the push,
	// else the device's local date.
	if (p.key === 'birthday_greeting' && ids.birthday_id) return `birthday:${ids.birthday_id}:${ids.date ?? localDate(now)}`;
	if (p.key === 'birthday_today') return `birthday_today:${ids.date ?? localDate(now)}`;
	return `${p.key ?? p.type ?? 'kinframe'}:${ids.post_id ?? ids.send_id ?? ids.sender_id ?? ids.chat_id ?? ids.source_id ?? ids.import_id ?? ids.subscription_id ?? ids.request_id ?? ids.birthday_id ?? ids.frame_id ?? ids.feed_id ?? ''}`;
}

function localDate(d: Date): string {
	return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** The VAPID public key (base64url) as applicationServerKey bytes. */
export function base64UrlToBytes(s: string): Uint8Array<ArrayBuffer> {
	const pad = '='.repeat((4 - (s.length % 4)) % 4);
	const bin = atob((s + pad).replace(/-/g, '+').replace(/_/g, '/'));
	const out = new Uint8Array(new ArrayBuffer(bin.length));
	for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
	return out;
}

/** Whether a stored subscription was made for this server key (keys can be rotated). */
export function sameKey(a: ArrayBuffer | null | undefined, b: Uint8Array): boolean {
	if (!a) return true; // browsers that don't expose it: assume yes
	const x = new Uint8Array(a);
	return x.length === b.length && x.every((v, i) => v === b[i]);
}
