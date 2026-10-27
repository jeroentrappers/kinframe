import { describe, expect, it } from 'vitest';
import { LEGAL_LANGUAGES, legalLanguage, linkify, loadLegal, type LegalBlock, type LegalKind } from './index';

const text = (blocks: LegalBlock[]) => blocks.flatMap((b) => (typeof b === 'string' ? [b] : b.list));

describe('legalLanguage', () => {
	it('uses en, nl and fr as they are and English for every other language', () => {
		expect(legalLanguage('nl')).toBe('nl');
		expect(legalLanguage('fr')).toBe('fr');
		expect(legalLanguage('de')).toBe('en');
		expect(legalLanguage('zh-CN')).toBe('en');
	});
});

describe('linkify', () => {
	it('links e-mail addresses and https URLs, leaving trailing punctuation as text', () => {
		expect(linkify('Mail jeroen@appmire.be.')).toEqual([
			{ text: 'Mail ' },
			{ text: 'jeroen@appmire.be', href: 'mailto:jeroen@appmire.be' },
			{ text: '.' }
		]);
		expect(linkify('See https://www.gegevensbeschermingsautoriteit.be/burger, then')).toEqual([
			{ text: 'See ' },
			{ text: 'https://www.gegevensbeschermingsautoriteit.be/burger', href: 'https://www.gegevensbeschermingsautoriteit.be/burger' },
			{ text: ', then' }
		]);
		expect(linkify('no links here')).toEqual([{ text: 'no links here' }]);
	});
});

describe.each(['privacy', 'terms'] as LegalKind[])('%s texts', (kind) => {
	it('exist in every language with the same sections, anchors and date', async () => {
		const docs = await Promise.all(LEGAL_LANGUAGES.map((l) => loadLegal(kind, l)));
		const [en, ...others] = docs.map((d) => d.doc);
		expect(en!.updated).toBe('2026-10-27');
		for (const d of others) {
			expect(d.updated).toBe(en!.updated);
			expect(d.sections.map((s) => s.id)).toEqual(en!.sections.map((s) => s.id));
			for (const [i, s] of d.sections.entries()) {
				const e = en!.sections[i]!;
				// Same shape: as many paragraphs and lists, so no part is missing from a translation.
				expect(s.body.map((b) => (typeof b === 'string' ? 'p' : `ul${b.list.length}`)), `${kind}#${s.id}`).toEqual(
					e.body.map((b) => (typeof b === 'string' ? 'p' : `ul${b.list.length}`))
				);
			}
		}
		for (const d of docs.map((x) => x.doc)) {
			expect(new Set(d.sections.map((s) => s.id)).size).toBe(d.sections.length);
			const all = [...text(d.intro), ...d.sections.flatMap((s) => text(s.body))].join('\n');
			expect(all).toContain('jeroen@appmire.be');
			expect(all).toContain('0535.825.723');
		}
	});

	if (kind === 'privacy') {
		it('has the data deletion section at #delete (Meta asks for that URL)', async () => {
			for (const l of LEGAL_LANGUAGES) {
				const { doc } = await loadLegal('privacy', l);
				expect(doc.sections.some((s) => s.id === 'delete')).toBe(true);
			}
		});
	}
});
