import { en, type MessageKey } from './en';
import { INTL_TAGS, LOCALES, RTL, isLocale, pickLocale, type Locale } from './locales';
import type { Catalog, PluralBase } from './types';

export type { MessageKey, Locale, Catalog };
export { LOCALES };

// Catalogs other than English load on demand (one small chunk per language).
const loaders = import.meta.glob<{ default: Catalog }>('./catalogs/*.ts');
const STORAGE_KEY = 'kinframe.locale';

function loaderFor(locale: Locale) {
	return loaders[`./catalogs/${locale}.ts`];
}

/** The saved choice, else the browser's languages, else English. */
function detect(): Locale {
	try {
		const saved = localStorage.getItem(STORAGE_KEY);
		if (isLocale(saved)) return saved;
	} catch {
		/* storage unavailable */
	}
	if (typeof navigator !== 'undefined') {
		return pickLocale(navigator.languages?.length ? navigator.languages : [navigator.language]) ?? 'en';
	}
	return 'en';
}

export function hasSavedLocale(): boolean {
	try {
		return isLocale(localStorage.getItem(STORAGE_KEY));
	} catch {
		return false;
	}
}

class I18n {
	locale = $state<Locale>('en');
	#catalogs = $state.raw<Partial<Record<Locale, Catalog>>>({ en });
	/** Resolves once the initial language's catalog is loaded. */
	ready: Promise<void>;

	constructor() {
		this.ready = typeof window === 'undefined' ? Promise.resolve() : this.#activate(detect(), false);
	}

	/** Switch language (and remember the choice). Resolves once the catalog has loaded. */
	set(locale: Locale): Promise<void> {
		return this.#activate(locale, true);
	}

	async #activate(locale: Locale, save: boolean) {
		if (!this.#catalogs[locale]) {
			const load = loaderFor(locale);
			if (load) {
				try {
					const mod = await load();
					this.#catalogs = { ...this.#catalogs, [locale]: mod.default };
				} catch {
					return; // chunk failed to load (offline); keep the current language
				}
			}
		}
		this.locale = locale;
		if (save) {
			try {
				localStorage.setItem(STORAGE_KEY, locale);
			} catch {
				/* ignore */
			}
		}
		applyDocumentLocale(locale);
	}

	get catalog(): Catalog {
		return this.#catalogs[this.locale] ?? en;
	}

	/** BCP 47 tag for Intl formatting. */
	get tag() {
		return INTL_TAGS[this.locale] ?? this.locale;
	}

	get dir(): 'rtl' | 'ltr' {
		return RTL.has(this.locale) ? 'rtl' : 'ltr';
	}
}

/** Sets `<html lang dir>` so the browser picks fonts, hyphenation and the text direction. */
export function applyDocumentLocale(locale: Locale) {
	if (typeof document === 'undefined') return;
	document.documentElement.lang = locale;
	document.documentElement.dir = RTL.has(locale) ? 'rtl' : 'ltr';
}

export const i18n = new I18n();

export type Params = Record<string, string | number>;

export function interpolate(template: string, params?: Params): string {
	if (!params) return template;
	return template.replace(/\{(\w+)\}/g, (m, k: string) => (k in params ? String(params[k]) : m));
}

/** Translate a key in the current locale (reactive: reads `i18n.locale`). */
export function t(key: MessageKey, params?: Params): string {
	return interpolate(i18n.catalog[key] ?? en[key] ?? key, params);
}

/**
 * Plural-aware translate: picks `<base>.<category>` for the language's CLDR plural category (`one`, `few`,
 * `many`…), falling back to `.other`, and passes a locale-formatted `{count}`.
 */
export function tn(base: PluralBase, count: number, params?: Params): string {
	const rule = new Intl.PluralRules(i18n.tag).select(count);
	const cat = i18n.catalog as Record<string, string | undefined>;
	const msg = cat[`${base}.${rule}`] ?? cat[`${base}.other`] ?? en[`${base}.other` as MessageKey];
	return interpolate(msg, { count: new Intl.NumberFormat(i18n.tag).format(count), ...params });
}

/** "3 photos, 1 video" for a set of items. */
export function kindSummary(k: { photos: number; videos: number }): string {
	const parts: string[] = [];
	if (k.photos || !k.videos) parts.push(tn('history.photos', k.photos));
	if (k.videos) parts.push(tn('history.videos', k.videos));
	return new Intl.ListFormat(i18n.tag, { style: 'short', type: 'unit' }).format(parts);
}
