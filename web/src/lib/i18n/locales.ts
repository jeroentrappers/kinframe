// The supported UI languages (same list as the frame app and the server, see docs/i18n.md).
// Pure data and helpers, no Svelte state, so tests and the server-facing code can import it.

export const LOCALE_IDS = [
	'en', 'nl', 'ar', 'bg', 'cs', 'da', 'de', 'el', 'es', 'fi', 'fr', 'he', 'hi', 'hr', 'hu', 'id', 'it', 'ja',
	'ko', 'lt', 'lv', 'nb', 'pl', 'pt', 'ro', 'ru', 'sk', 'sl', 'sr', 'sv', 'th', 'tr', 'uk', 'vi', 'zh-CN', 'zh-TW'
] as const;

export type Locale = (typeof LOCALE_IDS)[number];

/** Each language's name in that language. */
export const NATIVE_NAMES: Record<Locale, string> = {
	en: 'English',
	nl: 'Nederlands',
	ar: 'العربية',
	bg: 'Български',
	cs: 'Čeština',
	da: 'Dansk',
	de: 'Deutsch',
	el: 'Ελληνικά',
	es: 'Español',
	fi: 'Suomi',
	fr: 'Français',
	he: 'עברית',
	hi: 'हिन्दी',
	hr: 'Hrvatski',
	hu: 'Magyar',
	id: 'Bahasa Indonesia',
	it: 'Italiano',
	ja: '日本語',
	ko: '한국어',
	lt: 'Lietuvių',
	lv: 'Latviešu',
	nb: 'Norsk bokmål',
	pl: 'Polski',
	pt: 'Português',
	ro: 'Română',
	ru: 'Русский',
	sk: 'Slovenčina',
	sl: 'Slovenščina',
	sr: 'Српски',
	sv: 'Svenska',
	th: 'ไทย',
	tr: 'Türkçe',
	uk: 'Українська',
	vi: 'Tiếng Việt',
	'zh-CN': '简体中文',
	'zh-TW': '繁體中文'
};

/** Right-to-left languages. */
export const RTL: ReadonlySet<Locale> = new Set<Locale>(['ar', 'he']);

/**
 * BCP 47 tag for Intl formatting. English keeps day-month dates (en-GB) and Dutch uses Belgian conventions,
 * as before; Portuguese is Brazilian; Serbian is Cyrillic.
 */
export const INTL_TAGS: Partial<Record<Locale, string>> = {
	en: 'en-GB',
	nl: 'nl-BE',
	pt: 'pt-BR',
	sr: 'sr-Cyrl',
	'zh-CN': 'zh-Hans-CN',
	'zh-TW': 'zh-Hant-TW'
};

/** Languages sorted for a picker: by native name, Latin scripts first, then the others by script (root collation). */
export const LOCALES: { id: Locale; label: string }[] = LOCALE_IDS.map((id) => ({ id, label: NATIVE_NAMES[id] })).sort(
	(a, b) => new Intl.Collator('und').compare(a.label, b.label)
);

export function isLocale(v: unknown): v is Locale {
	return typeof v === 'string' && (LOCALE_IDS as readonly string[]).includes(v);
}

/**
 * Maps any BCP 47 tag (browser languages, a profile's locale) to a supported language, or null.
 * Handles legacy codes (iw, in), Norwegian (no/nn → nb) and Chinese scripts/regions.
 */
export function matchLocale(tag: string | null | undefined): Locale | null {
	if (!tag) return null;
	const parts = tag.replace(/_/g, '-').toLowerCase().split('-');
	const lang = parts[0] ?? '';
	const rest = parts.slice(1);
	if (lang === 'zh') {
		if (rest.includes('hant') || rest.some((p) => p === 'tw' || p === 'hk' || p === 'mo')) return 'zh-TW';
		return 'zh-CN';
	}
	const alias: Record<string, Locale> = { iw: 'he', in: 'id', no: 'nb', nn: 'nb' };
	const id = alias[lang] ?? lang;
	return isLocale(id) ? id : null;
}

/** The first supported language in a preference list. */
export function pickLocale(tags: readonly (string | null | undefined)[]): Locale | null {
	for (const t of tags) {
		const l = matchLocale(t);
		if (l) return l;
	}
	return null;
}
