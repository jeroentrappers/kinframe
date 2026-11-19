import type { ItemVia, Schemas } from '$lib/api/client';
import { t } from '$lib/i18n/index.svelte';

type Item = Pick<Schemas['Item'], 'sender_name' | 'imported_from' | 'imported_sender_name'> &
	Partial<Pick<Schemas['Item'], 'former_sender_name' | 'remote_sender'>> & { via?: ItemVia | null };

/**
 * The sender's name: "Tom (former member)" for photos handed over at account deletion, the poster's name for copies
 * from a family on another server (README §5.21, §5.22), else `sender_name`.
 */
function senderName(item: Item): string {
	const former = item.former_sender_name?.trim();
	if (former) return t('leaving.formerMember', { name: former });
	const remote = item.remote_sender?.name?.trim();
	if (remote) return remote;
	return item.sender_name ?? '';
}

/**
 * Who an item is from, as the library shows it. Items a frame imported from Frameo's leftover data
 * (docs/spec/frameo-import.md) have the frame as sender; they show the original sender instead: "Anna · via Frameo".
 */
export function senderLabel(item: Item): string {
	if (item.imported_from === 'frameo') {
		const name = item.imported_sender_name?.trim();
		return name ? t('library.viaFrameo', { name }) : t('library.viaFrameoNoName');
	}
	return withVia(senderName(item), item.via);
}

/**
 * "Anna via Immich": the outside service a photo source or a Google Photos import got the photo from
 * (docs/spec/photo-sources.md §9.5). The service name is never translated; nothing changes without `via`.
 */
export function withVia(text: string, via: ItemVia | null | undefined): string {
	const label = via?.label?.trim();
	if (!label) return text;
	// Email and Telegram: the label is the person who mailed or posted it, shown instead of the sender (who may be
	// the address's or chat's owner sending on their behalf): "Jan via email" (email-inbox.md §9.2, telegram.md §2).
	if (isChannel(via)) return channelCredit(label, via!.service);
	return text ? t('credit.via', { name: text, service: label }) : t('credit.viaOnly', { service: label });
}

/** Whether `via` is an outside channel (email, Telegram) whose label is a person, not a service. */
export function isChannel(via: Pick<ItemVia, 'service'> | null | undefined): boolean {
	return via?.service === 'email' || via?.service === 'telegram';
}

/** "Jan via email", "Jan · Peeters kids via Telegram". */
export function channelCredit(name: string, service: ItemVia['service']): string {
	return service === 'telegram' ? t('credit.viaTelegram', { name }) : t('credit.viaEmail', { name });
}

/** A feed post's or send's author line: the person behind an email or Telegram send, else the poster. */
export function authorCredit(poster: string, via: ItemVia | null | undefined): string {
	return isChannel(via) && via?.label?.trim() ? channelCredit(via.label.trim(), via.service) : poster;
}

type Origin = Pick<Schemas['Placement'], 'origin' | 'origin_feed_name'> & { item: Item };

/**
 * The library credit of a placement: the sender, plus the feed when it came through one ("Anna · Peeters kids",
 * docs/spec/feeds.md §3, like the frame's caption credit), plus the service: "Anna · Peeters kids via Immich".
 */
export function placementCredit(p: Origin): string {
	const feed = p.origin === 'feed' ? p.origin_feed_name?.trim() : '';
	if (!feed) return senderLabel(p.item);
	const via = p.item.imported_from === 'frameo' ? null : p.item.via;
	const label = via?.label?.trim();
	if (isChannel(via) && label) return channelCredit(t('library.viaFeed', { name: label, feed }), via!.service);
	const sender = senderLabel({ ...p.item, via: null });
	const base = sender ? t('library.viaFeed', { name: sender, feed }) : feed;
	return p.item.imported_from === 'frameo' ? base : withVia(base, p.item.via);
}

/** A send's or post's "what" line with its service: "14 photos via Immich". */
export function summaryVia(summary: string, via: ItemVia | null | undefined): string {
	const label = via?.label?.trim();
	if (!label) return summary;
	// The owner's history: "4 photos via email from Jan" (email-inbox.md §9.1).
	if (via!.service === 'email') return t('credit.summaryViaEmail', { summary, name: label });
	if (via!.service === 'telegram') return t('credit.summaryViaTelegram', { summary, name: label });
	return t('credit.summaryVia', { summary, service: label });
}
