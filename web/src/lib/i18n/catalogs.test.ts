import { describe, expect, it } from 'vitest';
import { en } from './en';
import { interpolate } from './index.svelte';
import { LOCALE_IDS, LOCALES, INTL_TAGS, NATIVE_NAMES, matchLocale, pickLocale, type Locale } from './locales';
import type { Catalog } from './types';

const modules = import.meta.glob<{ default: Catalog }>('./catalogs/*.ts', { eager: true });
const catalogs = Object.fromEntries(
	Object.entries(modules).map(([path, m]) => [path.replace(/^\.\/catalogs\/|\.ts$/g, ''), m.default])
) as Record<string, Catalog>;

const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1] ?? '').sort();
const pluralBases = Object.keys(en)
	.filter((k) => k.endsWith('.one'))
	.map((k) => k.slice(0, -'.one'.length));
const isPluralKey = (k: string) => pluralBases.some((b) => k.startsWith(`${b}.`));
const EXTRA = ['zero', 'two', 'few', 'many'];

/** Plural categories whole numbers reach in a language (what counts of photos can produce). */
function integerCategories(locale: string): Set<string> {
	const pr = new Intl.PluralRules(locale);
	const set = new Set(['other']);
	for (let n = 0; n <= 1000; n++) set.add(pr.select(n));
	for (const n of [1e4, 1e5, 1e6, 1e7]) set.add(pr.select(n));
	return set;
}
/** Whether `one` covers counts above 1 (Russian 21, Croatian 31…), so the text must show the number. */
function oneIsNotJustOne(locale: string): boolean {
	const pr = new Intl.PluralRules(locale);
	for (let n = 2; n <= 1000; n++) if (pr.select(n) === 'one') return true;
	return false;
}

const translated = LOCALE_IDS.filter((l) => l !== 'en');

describe('message catalogs', () => {
	it('there is a catalog for every supported language, and no others', () => {
		expect(Object.keys(catalogs).sort()).toEqual([...translated].sort());
	});

	describe.each(translated)('%s', (locale: Locale) => {
		const cat = catalogs[locale] as Record<string, string>;
		const tag = INTL_TAGS[locale] ?? locale;
		const cats = integerCategories(tag);

		it('has exactly the English keys (plus its plural categories)', () => {
			const missing = Object.keys(en).filter((k) => !(k in cat));
			expect(missing, 'missing keys').toEqual([]);
			const extra = Object.keys(cat).filter((k) => {
				if (k in en) return false;
				const b = pluralBases.find((b) => k.startsWith(`${b}.`));
				return !b || !EXTRA.includes(k.slice(b.length + 1)) || !cats.has(k.slice(b.length + 1));
			});
			expect(extra, 'unexpected keys').toEqual([]);
		});

		it('uses the same placeholders', () => {
			for (const key of Object.keys(en) as (keyof typeof en)[]) {
				if (!cat[key]) continue;
				const drop = (ps: string[]) => (isPluralKey(key) ? ps.filter((p) => p !== 'count') : ps);
				expect(drop(placeholders(cat[key])), key).toEqual(drop(placeholders(en[key])));
			}
		});

		it('has every plural category the language needs', () => {
			for (const b of pluralBases) {
				for (const c of cats) expect(cat, `${b}.${c}`).toHaveProperty([`${b}.${c}`]);
				if (!en[`${b}.other` as keyof typeof en].includes('{count}')) continue;
				for (const c of cats) {
					const v = cat[`${b}.${c}`];
					if (v && (c !== 'one' || oneIsNotJustOne(tag))) expect(v, `${b}.${c} must show {count}`).toContain('{count}');
				}
			}
		});

		it('has no empty messages', () => {
			for (const [k, v] of Object.entries(cat)) expect(v.trim(), k).not.toBe('');
		});
	});

	it('plural keys come in one/other pairs', () => {
		for (const key of Object.keys(en)) {
			if (key.endsWith('.one')) expect(en).toHaveProperty([key.replace(/\.one$/, '.other')]);
		}
	});

	it('has a message for every ProblemCode', () => {
		const codes = ['unauthorized', 'token_expired', 'device_revoked', 'forbidden', 'missing_permission', 'not_found', 'code_invalid', 'code_expired', 'invite_invalid', 'invite_expired', 'already_member', 'validation_failed', 'too_many_items', 'video_too_long', 'upload_too_large', 'upload_not_found', 'unsupported_media', 'corrupt_media', 'version_conflict', 'frame_offline', 'command_timeout', 'owner_cannot_leave', 'rate_limited', 'app_update_required', 'server_error'];
		for (const c of codes) expect(en).toHaveProperty([`problem.${c}`]);
	});

	it('interpolates and leaves unknown placeholders alone', () => {
		expect(interpolate('Hi {name}, {n}', { name: 'Oma' })).toBe('Hi Oma, {n}');
	});
});

describe('locales', () => {
	it('lists 36 languages, each with a native name', () => {
		expect(LOCALE_IDS).toHaveLength(36);
		expect(LOCALES.map((l) => l.id).sort()).toEqual([...LOCALE_IDS].sort());
		for (const l of LOCALE_IDS) expect(NATIVE_NAMES[l]).toBeTruthy();
	});

	it('sorts Latin-script names first, then other scripts', () => {
		const labels = LOCALES.map((l) => l.label);
		expect(labels.indexOf('Deutsch')).toBeLessThan(labels.indexOf('English'));
		expect(labels.indexOf('Tiếng Việt')).toBeLessThan(labels.indexOf('Ελληνικά'));
		expect(labels.indexOf('Ελληνικά')).toBeLessThan(labels.indexOf('Русский'));
		expect(labels.indexOf('Русский')).toBeLessThan(labels.indexOf('العربية'));
	});

	it('matches browser and profile tags', () => {
		expect(matchLocale('nl-BE')).toBe('nl');
		expect(matchLocale('iw')).toBe('he');
		expect(matchLocale('in-ID')).toBe('id');
		expect(matchLocale('no')).toBe('nb');
		expect(matchLocale('zh-Hant-HK')).toBe('zh-TW');
		expect(matchLocale('zh-TW')).toBe('zh-TW');
		expect(matchLocale('zh')).toBe('zh-CN');
		expect(matchLocale('pt_PT')).toBe('pt');
		expect(matchLocale('xx')).toBeNull();
		expect(pickLocale(['xx', 'de-AT', 'en'])).toBe('de');
	});
});
