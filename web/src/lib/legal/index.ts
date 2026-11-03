// Loads the privacy policy or the terms of use in the reader's language (en, nl or fr; others get English), each
// as its own small chunk. Pure logic apart from the dynamic imports; unit-tested in legal.test.ts.
import type { LegalDoc, LegalKind } from './types';

export type { LegalBlock, LegalDoc, LegalKind, LegalSection } from './types';

/** The host of the hosted service the texts describe; other servers show a note that their operator is in charge. */
export const HOSTED_HOST = 'kinframe.appmire.be';

/** Languages the legal texts are written in. */
export const LEGAL_LANGUAGES = ['en', 'nl', 'fr'] as const;
export type LegalLanguage = (typeof LEGAL_LANGUAGES)[number];

type Loader = () => Promise<{ default: LegalDoc }>;

const LOADERS: Record<LegalKind, Record<LegalLanguage, Loader>> = {
	privacy: { en: () => import('./privacy.en'), nl: () => import('./privacy.nl'), fr: () => import('./privacy.fr') },
	terms: { en: () => import('./terms.en'), nl: () => import('./terms.nl'), fr: () => import('./terms.fr') }
};

/** The language a legal text is shown in for a UI locale: the locale itself when the text exists, else English. */
export function legalLanguage(locale: string): LegalLanguage {
	const base = locale.toLowerCase().split('-')[0] ?? '';
	return (LEGAL_LANGUAGES as readonly string[]).includes(base) ? (base as LegalLanguage) : 'en';
}

export async function loadLegal(kind: LegalKind, locale: string): Promise<{ doc: LegalDoc; lang: LegalLanguage }> {
	const lang = legalLanguage(locale);
	const mod = await LOADERS[kind][lang]();
	return { doc: mod.default, lang };
}

export type Segment = { text: string; href?: string };

// https URLs (trailing punctuation stays text) and e-mail addresses.
const LINK = /(https?:\/\/[^\s<>()]*[^\s<>().,;:!?'"’])|([A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,})/g;

/** Splits plain text into text and link segments, so the texts need no markup. */
export function linkify(text: string): Segment[] {
	const out: Segment[] = [];
	let last = 0;
	for (const m of text.matchAll(LINK)) {
		const i = m.index ?? 0;
		if (i > last) out.push({ text: text.slice(last, i) });
		const [whole, url, email] = m;
		out.push({ text: whole, href: url ?? `mailto:${email}` });
		last = i + whole.length;
	}
	if (last < text.length) out.push({ text: text.slice(last) });
	return out;
}
