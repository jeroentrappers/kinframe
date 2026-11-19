// Families on other servers (docs/spec/multi-server.md, README §5.22): pure helpers.
import type { Schemas } from './api/client';

export type RemoteSubscription = Schemas['RemoteSubscription'];
export type RemoteInvitePreview = Schemas['RemoteInvitePreview'];
export type RemoteServer = Schemas['RemoteServer'];
export type BlockedServer = Schemas['BlockedServer'];
export type RemoteOnEnd = Schemas['RemoteOnEnd'];

/**
 * A pasted frame invite link: `https://<host>/fi/<token>`. Returns its URL, host and token, or null for anything
 * else (codes, other paths, non-http schemes). A link without a scheme gets https.
 */
export function parseInviteUrl(raw: string): { url: string; host: string; token: string } | null {
	let text = raw.trim();
	if (!text) return null;
	if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(text)) text = `https://${text}`;
	let u: URL;
	try {
		u = new URL(text);
	} catch {
		return null;
	}
	if (u.protocol !== 'https:' && u.protocol !== 'http:') return null;
	const m = u.pathname.match(/^\/fi\/([A-Za-z0-9_-]{22,64})\/?$/);
	if (!m || (!u.hostname.includes('.') && u.hostname !== 'localhost')) return null;
	return { url: `${u.origin}/fi/${m[1]}`, host: u.host, token: m[1]! };
}

/** Whether the link is another server's (this server's links go through the local flow). */
export function isOtherServer(host: string, here: string): boolean {
	return host.toLowerCase() !== here.toLowerCase();
}

/** "Oma's frame (kinframe.appmire.be)" for frame entries and reactions of frames on other servers. */
export function frameLabel(name: string, server: string | null | undefined, fmt: (frame: string, server: string) => string): string {
	const s = server?.trim();
	return s ? fmt(name, s) : name;
}

/** A host from a typed server address (`kinframe.example.org`, `https://kinframe.example.org/…`), or null. */
export function parseHost(raw: string): string | null {
	let text = raw.trim().toLowerCase();
	if (!text) return null;
	if (!/^[a-z][a-z0-9+.-]*:\/\//.test(text)) text = `https://${text}`;
	try {
		const u = new URL(text);
		if (u.protocol !== 'https:' && u.protocol !== 'http:') return null;
		if (!/^[a-z0-9-]+(\.[a-z0-9-]+)+$/.test(u.hostname) && u.hostname !== 'localhost') return null;
		return u.host;
	} catch {
		return null;
	}
}

/** Follows the frame manager can act on (pending, active, error) first, ended ones last. */
export function groupFollows(list: RemoteSubscription[]): { live: RemoteSubscription[]; ended: RemoteSubscription[] } {
	const live = list.filter((s) => s.status === 'pending' || s.status === 'active' || s.status === 'error');
	const ended = list.filter((s) => s.status === 'rejected' || s.status === 'removed');
	const by = (a: RemoteSubscription, b: RemoteSubscription) => b.created_at.localeCompare(a.created_at);
	return { live: live.sort(by), ended: ended.sort(by) };
}

export function upsertFollow(list: RemoteSubscription[], s: RemoteSubscription): RemoteSubscription[] {
	return list.some((x) => x.id === s.id) ? list.map((x) => (x.id === s.id ? s : x)) : [s, ...list];
}

/** The server's display name, else its host. */
export function serverName(s: Pick<RemoteServer, 'server_name' | 'host' | 'server_url'>): string {
	if (s.server_name?.trim()) return s.server_name.trim();
	if (s.host) return s.host;
	try {
		return new URL(s.server_url).host;
	} catch {
		return s.server_url;
	}
}

// ---- the web's links to other servers (decision A: the web stays single-server; the apps merge them)

const KEY = 'kinframe.otherServers';

export function loadOtherServers(storage: Pick<Storage, 'getItem'> | null): string[] {
	try {
		const v = JSON.parse(storage?.getItem(KEY) ?? '[]');
		return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && parseHost(x) === x) : [];
	} catch {
		return [];
	}
}

export function saveOtherServers(storage: Pick<Storage, 'setItem'> | null, hosts: string[]) {
	try {
		storage?.setItem(KEY, JSON.stringify([...new Set(hosts)]));
	} catch {
		/* storage unavailable */
	}
}
