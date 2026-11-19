import type { ItemVia, Schemas } from '$lib/api/client';
import { t } from '$lib/i18n/index.svelte';

type Item = Pick<Schemas['Item'], 'sender_name' | 'imported_from' | 'imported_sender_name'> & { via?: ItemVia | null };

/**
 * Who an item is from, as the library shows it. Items a frame imported from Frameo's leftover data
 * (docs/spec/frameo-import.md) have the frame as sender; they show the original sender instead: "Anna · via Frameo".
 */
export function senderLabel(item: Item): string {
	if (item.imported_from === 'frameo') {
		const name = item.imported_sender_name?.trim();
		return name ? t('library.viaFrameo', { name }) : t('library.viaFrameoNoName');
	}
	return withVia(item.sender_name ?? '', item.via);
}

/**
 * "Anna via Immich": the outside service a photo source or a Google Photos import got the photo from
 * (docs/spec/photo-sources.md §9.5). The service name is never translated; nothing changes without `via`.
 */
export function withVia(text: string, via: ItemVia | null | undefined): string {
	const service = via?.label?.trim();
	if (!service) return text;
	return text ? t('credit.via', { name: text, service }) : t('credit.viaOnly', { service });
}

type Origin = Pick<Schemas['Placement'], 'origin' | 'origin_feed_name'> & { item: Item };

/**
 * The library credit of a placement: the sender, plus the feed when it came through one ("Anna · Peeters kids",
 * docs/spec/feeds.md §3, like the frame's caption credit), plus the service: "Anna · Peeters kids via Immich".
 */
export function placementCredit(p: Origin): string {
	const feed = p.origin === 'feed' ? p.origin_feed_name?.trim() : '';
	if (!feed) return senderLabel(p.item);
	const sender = senderLabel({ ...p.item, via: null });
	const base = sender ? t('library.viaFeed', { name: sender, feed }) : feed;
	return p.item.imported_from === 'frameo' ? base : withVia(base, p.item.via);
}

/** A send's or post's "what" line with its service: "14 photos via Immich". */
export function summaryVia(summary: string, via: ItemVia | null | undefined): string {
	const service = via?.label?.trim();
	return service ? t('credit.summaryVia', { summary, service }) : summary;
}
