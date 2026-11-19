// Email to frame (docs/spec/email-inbox.md, README §5.16): the share message, slugs, status, outcome and skip texts.
// Pure functions; the components only render what these return.
import type {
	EmailAddress,
	EmailAuthResult,
	EmailHeldReason,
	EmailMessage,
	EmailPendingSender,
	EmailSender,
	EmailSkipCounts
} from './api/client';
import { relativeTime } from './format';
import { t, tn } from './i18n/index.svelte';

// ---------------------------------------------------------------- the friendly part

/** The server's rule for `slug`: 1–24 of `a-z0-9-`, no dash at either end. */
export const SLUG_MAX = 24;
const SLUG_RE = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/;

/**
 * A slug suggestion from a name, like the server's default: lower case, accents removed, anything else a dash
 * ("Oma's frame" → "omas-frame", "Peeters kids" → "peeters-kids").
 */
export function slugify(name: string): string {
	return name
		.normalize('NFKD')
		.replace(/[̀-ͯ]/g, '')
		.toLowerCase()
		.replace(/['’`]/g, '')
		.replace(/[^a-z0-9]+/g, '-')
		.replace(/^-+/, '')
		.slice(0, SLUG_MAX)
		.replace(/-+$/, '');
}

/** What is typed into the slug field, cleaned as one types (keeps a trailing dash so typing "oma-" works). */
export function cleanSlugInput(input: string): string {
	return input
		.normalize('NFKD')
		.replace(/[̀-ͯ]/g, '')
		.toLowerCase()
		.replace(/[^a-z0-9-]+/g, '-')
		.replace(/-{2,}/g, '-')
		.replace(/^-+/, '')
		.slice(0, SLUG_MAX);
}

export function isValidSlug(slug: string): boolean {
	return slug.length >= 1 && slug.length <= SLUG_MAX && SLUG_RE.test(slug);
}

/** "oma-••••••@in.kinframe.appmire.be": what the address will look like before the server picks the random part. */
export function addressPreview(slug: string, domain: string | null | undefined): string {
	const s = slug.replace(/-+$/, '') || 'oma';
	return `${s}-••••••${domain ? `@${domain}` : ''}`;
}

// ---------------------------------------------------------------- sharing

type Shareable = Pick<EmailAddress, 'address' | 'name'> & { owner?: EmailAddress['owner'] };

/**
 * The ready-made message for a relative (README §5.16): "You can now email photos to Oma's frame:
 * oma-k7q2m9@…. Just attach them; the subject becomes the caption. The first time, Anna approves your address."
 */
export function shareMessage(a: Shareable, fallbackOwner = ''): string {
	const owner = a.owner?.display_name?.trim() || fallbackOwner.trim();
	return owner
		? t('email.shareText', { name: a.name, address: a.address, owner })
		: t('email.shareTextNoOwner', { name: a.name, address: a.address });
}

/** A `mailto:` link to try the address, with the frame's name as subject. */
export function mailtoHref(address: string, subject?: string): string {
	const q = subject?.trim() ? `?subject=${encodeURIComponent(subject.trim())}` : '';
	return `mailto:${address}${q}`;
}

/** Splits the address for display: the friendly part, the random part and the domain. */
export function addressParts(address: string, slug: string): { slug: string; random: string; domain: string } {
	const at = address.lastIndexOf('@');
	const local = at >= 0 ? address.slice(0, at) : address;
	const domain = at >= 0 ? address.slice(at + 1) : '';
	const random = local.startsWith(`${slug}-`) ? local.slice(slug.length + 1) : '';
	return { slug: random ? slug : local, random, domain };
}

// ---------------------------------------------------------------- status

export type Tone = 'ok' | 'paused' | 'attention';

export interface AddressView {
	tone: Tone;
	label: string;
	/** "2 people are waiting for your OK". */
	pending: string | null;
}

export function addressView(a: Pick<EmailAddress, 'status' | 'last_received_at' | 'counts'>, locale = 'en', now = new Date()): AddressView {
	const pending = a.counts.pending_senders > 0 ? tn('email.pendingCount', a.counts.pending_senders) : null;
	if (a.status === 'paused') return { tone: 'paused', label: t('email.statusPaused'), pending };
	const label = a.last_received_at ? t('email.statusReadyLast', { when: relativeTime(a.last_received_at, locale, now) }) : t('email.statusReady');
	return { tone: pending ? 'attention' : 'ok', label, pending };
}

/** In the order they were made (the first address stays on top). */
export function sortAddresses(list: EmailAddress[]): EmailAddress[] {
	return [...list].sort((a, b) => Date.parse(a.created_at) - Date.parse(b.created_at));
}

export function upsertById<T extends { id: string }>(list: T[], x: T): T[] {
	const i = list.findIndex((y) => y.id === x.id);
	if (i < 0) return [...list, x];
	const next = [...list];
	next[i] = x;
	return next;
}

/** Whether an address fills this frame or feed. */
export function addressBelongsTo(a: Pick<EmailAddress, 'target'>, target: { kind: 'frame' | 'feed'; id: string }): boolean {
	return target.kind === 'frame' ? a.target.frame_id === target.id : a.target.feed_id === target.id;
}

// ---------------------------------------------------------------- senders

/** "Jan · jan.peeters@telenet.be", or just the address. */
export function senderLine(s: Pick<EmailSender, 'email' | 'display_name'>): string {
	const name = s.display_name?.trim();
	return name ? `${name} · ${s.email}` : s.email;
}

/** The name to show for a pending sender: their display name, else the address. */
export function pendingName(p: Pick<EmailPendingSender, 'email' | 'display_name'>): string {
	return p.display_name?.trim() || p.email;
}

/** Allowed first, then blocked; by name within each. */
export function sortSenders(list: EmailSender[]): EmailSender[] {
	const rank = (s: EmailSender) => (s.state === 'allowed' ? 0 : s.state === 'pending' ? 1 : 2);
	return [...list].sort((a, b) => rank(a) - rank(b) || (a.display_name || a.email).localeCompare(b.display_name || b.email));
}

/** A forgiving email check for the add-sender field (the server validates for real). */
export function looksLikeEmail(s: string): boolean {
	return /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(s.trim());
}

/** The total photos and videos a pending sender is waiting to send. */
export function heldMediaCount(p: Pick<EmailPendingSender, 'messages'>): number {
	return p.messages.reduce((n, m) => n + m.media_count, 0);
}

/** Can "Approve" be offered? Only for authenticated mail (unverified mail: deliver this one, or delete). */
export function canApprove(p: Pick<EmailPendingSender, 'authenticated'>): boolean {
	return p.authenticated;
}

/** The held-mail warning line, or null when it is an ordinary unknown sender. */
export function heldWarning(reason: EmailHeldReason, email: string): string | null {
	return reason === 'unverified_sender' ? t('email.unverified', { email }) : null;
}

/** "Checked: DKIM (example.org) passed" or why it failed, for the held-mail details. */
export function authText(auth: EmailAuthResult): string {
	if (auth.authenticated) {
		const how = auth.aligned === 'spf' ? 'SPF' : 'DKIM';
		const domain = (auth.aligned === 'spf' ? auth.from_domain : auth.dkim_domain) ?? auth.from_domain ?? '';
		return domain ? t('email.authPassDomain', { how, domain }) : t('email.authPass', { how });
	}
	return auth.from_domain ? t('email.authFailDomain', { domain: auth.from_domain }) : t('email.authFail');
}

/** "Expires in 3 days" for held mail. */
export function expiresText(expiresAt: string, locale = 'en', now = new Date()): string {
	return t('email.expires', { when: relativeTime(expiresAt, locale, now) });
}

// ---------------------------------------------------------------- recent mail

/** What became of a mail, as the spec words it for managers (email-inbox.md §13). */
export function outcomeText(m: Pick<EmailMessage, 'outcome' | 'sender_name' | 'sender_email' | 'items'>): string {
	const who = m.sender_name?.trim() || m.sender_email || t('email.someone');
	const domain = m.sender_email?.split('@')[1] ?? '';
	switch (m.outcome) {
		case 'delivered':
			return tn('email.outcome.delivered', m.items ?? 0, { name: who });
		case 'held':
			return t('email.outcome.held', { name: who });
		case 'no_media':
			return t('email.outcome.no_media', { name: who });
		case 'too_large':
			return t('email.outcome.too_large', { name: who });
		case 'encrypted':
			return t('email.outcome.encrypted', { name: who });
		case 'duplicate':
			return t('email.outcome.duplicate', { name: who });
		case 'blocked':
			return t('email.outcome.blocked');
		case 'rejected_dmarc':
			return domain ? t('email.outcome.rejected_dmarc', { domain }) : t('email.outcome.rejected_dmarcNoDomain');
		case 'ignored':
			return t('email.outcome.ignored');
		case 'rate_limited':
			return t('email.outcome.rate_limited');
		default:
			return t('email.outcome.failed');
	}
}

export function outcomeTone(o: EmailMessage['outcome']): 'ok' | 'held' | 'problem' | 'muted' {
	if (o === 'delivered') return 'ok';
	if (o === 'held') return 'held';
	if (o === 'blocked' || o === 'ignored' || o === 'duplicate') return 'muted';
	return 'problem';
}

/** The files a mail had that were not used: "2 too small, 1 already there". Empty when none were skipped. */
export function skipText(s: EmailSkipCounts | null | undefined): string {
	if (!s) return '';
	const parts: string[] = [];
	const add = (n: number | undefined, base: Parameters<typeof tn>[0]) => {
		if (n && n > 0) parts.push(tn(base, n));
	};
	add(s.too_small, 'email.skip.too_small');
	add(s.duplicate, 'email.skip.duplicate');
	add(s.unsupported, 'email.skip.unsupported');
	add(s.too_large, 'email.skip.too_large');
	add(s.too_long, 'email.skip.too_long');
	add(s.over_limit, 'email.skip.over_limit');
	return parts.join(', ');
}

/** The reply the sender got, for the log: "Told: delivered". */
export function replyText(r: EmailMessage['reply']): string | null {
	return r ? t(`email.reply.${r}`) : null;
}

// ---------------------------------------------------------------- targets

/** What an address or a Telegram chat fills: a frame (a direct send per mail) or a feed (a post per mail). */
export type ChannelTarget = { kind: 'frame' | 'feed'; id: string; name: string };
