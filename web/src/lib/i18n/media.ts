import { i18n, interpolate, type Params } from './index.svelte';

/**
 * Strings for client-side preparation and Live Photos. Kept apart from the main catalogs until the next
 * translation pass merges them: English and Dutch only for now; every other language falls back to English.
 */
const en = {
	'media.preparing': 'Preparing…',
	'media.preparingPct': 'Preparing… {pct}%',
	'media.live': 'Live',
	'media.liveOn': 'Live Photo: the motion plays on the frame. Tap to send the still only.',
	'media.liveOff': 'Sending the still only. Tap to send it as a Live Photo.',
	'media.liveA11y': 'Live Photo',
	'media.playLive': 'Play Live Photo'
} as const;

export type MediaKey = keyof typeof en;

const nl: Record<MediaKey, string> = {
	'media.preparing': 'Voorbereiden…',
	'media.preparingPct': 'Voorbereiden… {pct}%',
	'media.live': 'Live',
	'media.liveOn': 'Live Photo: de beweging speelt op de fotolijst. Tik om alleen de foto te sturen.',
	'media.liveOff': 'Alleen de foto wordt gestuurd. Tik om hem als Live Photo te sturen.',
	'media.liveA11y': 'Live Photo',
	'media.playLive': 'Live Photo afspelen'
};

const catalogs: Partial<Record<string, Record<MediaKey, string>>> = { en, nl };

/** Translate a media string in the current locale (English fallback). */
export function tm(key: MediaKey, params?: Params): string {
	return interpolate(catalogs[i18n.locale]?.[key] ?? en[key], params);
}

export const mediaCatalogs = { en, nl };
