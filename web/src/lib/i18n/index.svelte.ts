import { en, type MessageKey } from './en';
import { nl } from './nl';

export type { MessageKey };
export type Locale = 'en' | 'nl';
export const LOCALES: { id: Locale; label: string }[] = [
	{ id: 'en', label: 'English' },
	{ id: 'nl', label: 'Nederlands' }
];

const catalogs: Record<Locale, Record<MessageKey, string>> = { en, nl };
const STORAGE_KEY = 'kinframe.locale';

function detect(): Locale {
	try {
		const saved = localStorage.getItem(STORAGE_KEY);
		if (saved === 'en' || saved === 'nl') return saved;
	} catch {
		/* storage unavailable */
	}
	if (typeof navigator !== 'undefined' && navigator.language?.toLowerCase().startsWith('nl')) return 'nl';
	return 'en';
}

class I18n {
	locale = $state<Locale>(typeof window === 'undefined' ? 'en' : detect());

	set(locale: Locale) {
		this.locale = locale;
		try {
			localStorage.setItem(STORAGE_KEY, locale);
		} catch {
			/* ignore */
		}
		if (typeof document !== 'undefined') document.documentElement.lang = locale;
	}

	/** BCP 47 tag for Intl formatting. */
	get tag() {
		return this.locale === 'nl' ? 'nl-BE' : 'en-GB';
	}
}

export const i18n = new I18n();

export type Params = Record<string, string | number>;

export function interpolate(template: string, params?: Params): string {
	if (!params) return template;
	return template.replace(/\{(\w+)\}/g, (m, k: string) => (k in params ? String(params[k]) : m));
}

/** Translate a key in the current locale (reactive: reads `i18n.locale`). */
export function t(key: MessageKey, params?: Params): string {
	return interpolate(catalogs[i18n.locale][key] ?? en[key] ?? key, params);
}

type PluralBase = MessageKey extends infer K ? (K extends `${infer B}.one` ? B : never) : never;

/** Plural-aware translate: uses `<key>.one` / `<key>.other`, passing `{count}`. */
export function tn(base: PluralBase, count: number, params?: Params): string {
	const rule = new Intl.PluralRules(i18n.locale).select(count);
	const key = `${base}.${rule === 'one' ? 'one' : 'other'}` as MessageKey;
	return t(key, { count: new Intl.NumberFormat(i18n.tag).format(count), ...params });
}

/** "3 photos, 1 video" for a set of items. */
export function kindSummary(k: { photos: number; videos: number }): string {
	const parts: string[] = [];
	if (k.photos || !k.videos) parts.push(tn('history.photos', k.photos));
	if (k.videos) parts.push(tn('history.videos', k.videos));
	return parts.join(', ');
}
