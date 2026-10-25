import type { Schemas } from '$lib/api/client';
import { t } from '$lib/i18n/index.svelte';

type Item = Pick<Schemas['Item'], 'sender_name' | 'imported_from' | 'imported_sender_name'>;

/**
 * Who an item is from, as the library shows it. Items a frame imported from Frameo's leftover data
 * (docs/spec/frameo-import.md) have the frame as sender; they show the original sender instead: "Anna · via Frameo".
 */
export function senderLabel(item: Item): string {
	if (item.imported_from === 'frameo') {
		const name = item.imported_sender_name?.trim();
		return name ? t('library.viaFrameo', { name }) : t('library.viaFrameoNoName');
	}
	return item.sender_name ?? '';
}
