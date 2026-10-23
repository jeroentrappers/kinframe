// The privacy policy and terms of use as plain data: long prose stays out of the per-locale message catalogs
// (docs/i18n.md). Texts exist in English, Dutch and French; every other language shows the English text.
// No markup: e-mail addresses, URLs and "#anchors" in the text become links when rendered (see linkify).

/** A paragraph, or a bulleted list of paragraphs. */
export type LegalBlock = string | { list: string[] };

export interface LegalSection {
	/** Stable anchor (same in every language), e.g. `delete` for /privacy#delete. */
	id: string;
	title: string;
	body: LegalBlock[];
}

export interface LegalDoc {
	title: string;
	/** ISO date (YYYY-MM-DD) of the last change; the same in every language. */
	updated: string;
	intro: LegalBlock[];
	sections: LegalSection[];
}

export type LegalKind = 'privacy' | 'terms';
