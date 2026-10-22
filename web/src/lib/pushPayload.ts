// Shared by the service worker and the app: what a push message (docs/api/README.md §6.3) means.

export interface PushPayload {
	type?: string;
	key?: string;
	title?: string;
	body?: string;
	ids?: Record<string, string>;
}

const ID = /^[0-9a-fA-F-]{8,64}$/;
const id = (v: string | undefined) => (v && ID.test(v) ? v : null);

/** The page a notification opens. */
export function pushUrl(p: PushPayload): string {
	const send = id(p.ids?.send_id);
	const frame = id(p.ids?.frame_id);
	if (p.key === 'join_request' && frame) return `/frames/${frame}?tab=people`;
	if ((p.type === 'event_invitation.decided' || p.key?.startsWith('event_invitation')) && frame) return `/frames/${frame}?tab=calendar`;
	if (send) return `/history/${send}`;
	if (frame) return `/frames/${frame}`;
	return '/history';
}

/** Collapses repeated notifications about the same thing (e.g. several reactions on one send). */
export function pushTag(p: PushPayload): string {
	return `${p.key ?? p.type ?? 'kinframe'}:${p.ids?.send_id ?? p.ids?.request_id ?? p.ids?.frame_id ?? ''}`;
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
