#!/usr/bin/env node
// Checks one or more languages' translation files against the English sources:
//   node tools/i18n/check-lang.mjs de fr zh-TW        (or no arguments for every language)
// Android strings (frame app), web catalog, server messages and Logto e-mail templates must have exactly the English
// keys, the same placeholders, and the plural categories the language needs (CLDR via Intl.PluralRules).
import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const LANGS = ['nl', 'ar', 'bg', 'cs', 'da', 'de', 'el', 'es', 'fi', 'fr', 'he', 'hi', 'hr', 'hu', 'id', 'it', 'ja',
	'ko', 'lt', 'lv', 'nb', 'pl', 'pt', 'ro', 'ru', 'sk', 'sl', 'sr', 'sv', 'th', 'tr', 'uk', 'vi', 'zh-CN', 'zh-TW'];

export const androidDir = (lang) => {
	if (lang.includes('-')) { const [l, r] = lang.split('-'); return `values-${l}-r${r}`; }
	return `values-${lang}`;
};
const androidAliases = { he: 'values-iw', id: 'values-in' };

/** Plural categories reachable by whole numbers (what counts of photos can produce). */
export function integerCategories(lang) {
	const pr = new Intl.PluralRules(lang);
	const set = new Set(['other']);
	for (let n = 0; n <= 1000; n++) set.add(pr.select(n));
	for (const n of [1e4, 1e5, 1e6, 1e7]) set.add(pr.select(n));
	return set;
}
/** Does `one` also cover numbers other than 1 (so the text must show the number)? */
const oneIsNotJustOne = (lang) => { const pr = new Intl.PluralRules(lang); for (let n = 2; n <= 1000; n++) if (pr.select(n) === "one") return true; return false; };

function unescapeXml(s) { return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&'); }
function parseAndroid(file) {
	const xml = readFileSync(file, 'utf8');
	const strings = {}, plurals = {}, arrays = {};
	for (const m of xml.matchAll(/<string name="([^"]+)"([^>]*)>([\s\S]*?)<\/string>/g)) {
		if (/translatable="false"/.test(m[2])) continue;
		strings[m[1]] = unescapeXml(m[3]);
	}
	for (const m of xml.matchAll(/<plurals name="([^"]+)">([\s\S]*?)<\/plurals>/g)) {
		plurals[m[1]] = Object.fromEntries([...m[2].matchAll(/<item quantity="(\w+)">([\s\S]*?)<\/item>/g)].map((i) => [i[1], unescapeXml(i[2])]));
	}
	for (const m of xml.matchAll(/<string-array name="([^"]+)">([\s\S]*?)<\/string-array>/g)) {
		arrays[m[1]] = [...m[2].matchAll(/<item>([\s\S]*?)<\/item>/g)].map((i) => i[1]);
	}
	// Unescaped apostrophes break aapt.
	for (const [k, v] of Object.entries(strings)) if (/(^|[^\\])'/.test(v) && !/^".*"$/.test(v)) problems.push(`android ${file}: ${k} has an unescaped apostrophe`);
	return { strings, plurals, arrays };
}
const fmtArgs = (s) => [...s.matchAll(/%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfx%]/g)].map((m) => m[0]).filter((x) => x !== '%%').sort();
const braces = (s) => [...s.matchAll(/\{\{?(\w+(\.\w+)*)\}?\}/g)].map((m) => m[0]).sort();
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

const problems = [];
const fail = (msg) => problems.push(msg);

async function check(lang) {
	const ints = integerCategories(lang === 'zh-CN' || lang === 'zh-TW' ? 'zh' : lang);
	// --- Android
	const enA = parseAndroid(join(root, 'android/frame/src/main/res/values/strings.xml'));
	const dirs = [androidDir(lang), androidAliases[lang]].filter(Boolean);
	for (const d of dirs) {
		const f = join(root, 'android/frame/src/main/res', d, 'strings.xml');
		if (!existsSync(f)) { fail(`android ${d}: missing`); continue; }
		const a = parseAndroid(f);
		for (const k of Object.keys(enA.strings)) {
			if (!(k in a.strings)) fail(`android ${d}: missing string ${k}`);
			else if (!same(fmtArgs(a.strings[k]), fmtArgs(enA.strings[k]))) fail(`android ${d}: ${k} format args ${fmtArgs(a.strings[k])} != ${fmtArgs(enA.strings[k])}`);
		}
		for (const k of Object.keys(a.strings)) if (!(k in enA.strings)) fail(`android ${d}: extra string ${k}`);
		for (const k of Object.keys(enA.plurals)) {
			const p = a.plurals[k];
			if (!p) { fail(`android ${d}: missing plurals ${k}`); continue; }
			// Android lint wants every CLDR category, including those only decimals reach (Czech many…).
			const all = new Set(new Intl.PluralRules(lang).resolvedOptions().pluralCategories);
			const have = new Set(Object.keys(p));
			for (const c of all) if (!have.has(c)) fail(`android ${d}: plurals ${k} lacks quantity ${c}`);
			for (const c of have) if (!all.has(c)) fail(`android ${d}: plurals ${k} has unused quantity ${c}`);
			for (const [q, v] of Object.entries(p)) if (!v.includes('%d') && (q !== 'one' || oneIsNotJustOne(lang))) fail(`android ${d}: plurals ${k}/${q} lacks %d`);
		}
		for (const k of Object.keys(enA.arrays)) if (a.arrays[k]?.length !== enA.arrays[k].length) fail(`android ${d}: array ${k} length`);
	}
	if (dirs.length === 2) {
		const [x, y] = dirs.map((d) => join(root, 'android/frame/src/main/res', d, 'strings.xml'));
		if (existsSync(x) && existsSync(y) && readFileSync(x, 'utf8') !== readFileSync(y, 'utf8')) fail(`android ${dirs[1]} must be an identical copy of ${dirs[0]}`);
	}

	// --- Web
	const webFile = join(root, 'web/src/lib/i18n/catalogs', `${lang}.ts`);
	if (!existsSync(webFile)) fail(`web ${lang}: missing`);
	else {
		const { en } = await import(pathToFileURL(join(root, 'web/src/lib/i18n/en.ts')));
		const cat = (await import(pathToFileURL(webFile))).default;
		const bases = Object.keys(en).filter((k) => k.endsWith('.one')).map((k) => k.slice(0, -4));
		for (const k of Object.keys(en)) {
			if (!(k in cat)) { fail(`web ${lang}: missing ${k}`); continue; }
			const isPlural = bases.some((b) => k.startsWith(b + '.'));
			const want = braces(en[k]).filter((p) => !isPlural || p !== '{count}');
			const have = braces(cat[k]).filter((p) => !isPlural || p !== '{count}');
			if (!same(want, have)) fail(`web ${lang}: ${k} placeholders ${have} != ${want}`);
		}
		for (const k of Object.keys(cat)) {
			if (k in en) continue;
			const b = bases.find((b) => k.startsWith(b + '.'));
			const c = k.slice((b ?? '').length + 1);
			if (!b || !['zero', 'two', 'few', 'many'].includes(c)) fail(`web ${lang}: unexpected key ${k}`);
			else if (!ints.has(c)) fail(`web ${lang}: ${k} is a plural category ${lang} does not use`);
		}
		for (const b of bases) {
			for (const c of ints) if (!(`${b}.${c}` in cat)) fail(`web ${lang}: missing plural ${b}.${c}`);
			if (en[`${b}.other`].includes('{count}')) {
				for (const c of ints) {
					const v = cat[`${b}.${c}`];
					if (v && !v.includes('{count}') && (c !== 'one' || oneIsNotJustOne(lang))) fail(`web ${lang}: ${b}.${c} must show {count}`);
				}
			}
		}
	}

	// --- Server messages
	const sFile = join(root, 'server/internal/i18n/locales', `${lang}.json`);
	if (!existsSync(sFile)) fail(`server ${lang}: missing`);
	else {
		const enS = JSON.parse(readFileSync(join(root, 'server/internal/i18n/locales/en.json'), 'utf8'));
		const s = JSON.parse(readFileSync(sFile, 'utf8'));
		for (const sec of ['messages', 'details']) {
			for (const k of Object.keys(enS[sec])) {
				const v = s[sec]?.[k];
				if (!v) { fail(`server ${lang}: missing ${sec}.${k}`); continue; }
				const verbs = (x) => [...x.matchAll(/%[sdv]/g)].map((m) => m[0]).join();
				if (sec === 'details' && verbs(v) !== verbs(enS[sec][k])) fail(`server ${lang}: details "${k}" verbs differ`);
				if (sec === 'messages' && !same(braces(v), braces(enS[sec][k]))) fail(`server ${lang}: ${k} placeholders differ`);
			}
			for (const k of Object.keys(s[sec] ?? {})) if (!(k in enS[sec])) fail(`server ${lang}: extra ${sec}.${k}`);
		}
	}

	// --- Logto e-mails
	const eFile = join(root, 'server/internal/logtoboot/emails', `${lang}.json`);
	if (!existsSync(eFile)) fail(`email ${lang}: missing`);
	else {
		const enE = JSON.parse(readFileSync(join(root, 'server/internal/logtoboot/emails/en.json'), 'utf8'));
		const e = JSON.parse(readFileSync(eFile, 'utf8'));
		if (!e.footer || !e.signature) fail(`email ${lang}: footer/signature missing`);
		for (const k of Object.keys(enE.templates)) {
			const t = e.templates?.[k];
			if (!t?.subject || !t?.intro) fail(`email ${lang}: ${k} missing`);
			else if (!t.subject.includes('{{code}}')) fail(`email ${lang}: ${k} subject must contain {{code}}`);
			else if (t.intro.includes('{{code}}')) fail(`email ${lang}: ${k} intro must not contain {{code}} (it is added below)`);
		}
	}
}

const langs = process.argv.slice(2).length ? process.argv.slice(2) : LANGS;
for (const l of langs) await check(l);
if (problems.length) {
	console.error(problems.join('\n'));
	console.error(`\n${problems.length} problem(s)`);
	process.exit(1);
}
console.log(`ok: ${langs.join(' ')}`);
