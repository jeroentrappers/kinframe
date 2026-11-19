// The shared "Enter a code" field: 8-digit frame codes ("4821 3907") and feed invite codes ("KF7Q-M2XA",
// 8 Crockford base32 characters, never all digits; README §5.12). Pure: unit-tested in codes.test.ts.

import { CODE_LENGTH } from './format';

/** Crockford base32 (no I, L, O, U). */
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
export const FEED_CODE_LENGTH = 8;

export type EnteredCode =
	| { kind: 'empty' }
	/** Digits only: a frame code (claim or join). */
	| { kind: 'frame'; code: string; complete: boolean }
	/** Has a letter: a feed invite code, normalized to upper-case Crockford characters without the hyphen. */
	| { kind: 'feed'; code: string; complete: boolean };

/** Crockford decoding is forgiving: O reads as 0, I and L as 1. Anything else outside the alphabet is dropped. */
function crockford(s: string): string {
	let out = '';
	for (const ch of s.toUpperCase()) {
		const c = ch === 'O' ? '0' : ch === 'I' || ch === 'L' ? '1' : ch;
		if (CROCKFORD.includes(c)) out += c;
	}
	return out;
}

const isDigits = (s: string) => /^\d*$/.test(s);

/**
 * Tell a feed code from a frame code while typing or after a paste. Digits only is a frame code (as before feeds);
 * a letter makes it a feed code. Pasted text like "Code: 4821-3907" or "Join with KF7Q-M2XA" finds the code inside.
 * With `feeds` off, everything is read as a frame code (digits only), exactly like the field always did.
 */
export function parseEnteredCode(raw: string, feeds = true): EnteredCode {
	const digits = raw.replace(/\D/g, '');
	if (!feeds) {
		const code = digits.slice(0, CODE_LENGTH);
		return code ? { kind: 'frame', code, complete: code.length === CODE_LENGTH } : { kind: 'empty' };
	}
	const compact = raw.toUpperCase().replace(/[^0-9A-Z]/g, '');
	if (!compact) return { kind: 'empty' };
	if (isDigits(compact)) {
		const code = compact.slice(0, CODE_LENGTH);
		return { kind: 'frame', code, complete: code.length === CODE_LENGTH };
	}
	// A feed code somewhere in pasted text: two groups of four, hyphen or space optional, with a letter.
	// Words like "JOIN WITH" also look like that, so in longer text only a group with a digit counts.
	const upper = raw.toUpperCase();
	// Overlapping candidates (lookahead), so a word in front never hides the code. The way codes are shown
	// ("KF7Q-M2XA") wins over one word ("KF7QM2XA"), which wins over two words that happen to fit ("CODE KF7Q").
	let best: { code: string; score: number } | null = null;
	for (const m of upper.matchAll(/(?=(?:^|[^0-9A-Z])([0-9A-Z]{4})([-\s]?)([0-9A-Z]{4})(?![0-9A-Z]))/g)) {
		const joined = `${m[1]}${m[3]}`;
		if (!/[A-Z]/.test(joined) || crockford(joined).length !== FEED_CODE_LENGTH) continue;
		const code = crockford(joined);
		if (isDigits(code)) continue;
		if (!/\d/.test(joined) && compact.length > FEED_CODE_LENGTH) continue;
		const score = m[2] === '-' ? 3 : m[2] === '' ? 2 : 1;
		if (!best || score > best.score) best = { code, score };
	}
	if (best) return { kind: 'feed', code: best.code, complete: true };
	// "Code: 4821-3907": words around a frame code.
	if (digits.length >= CODE_LENGTH && /[A-Z]{3,}/.test(compact.replace(/\d/g, ' '))) {
		return { kind: 'frame', code: digits.slice(0, CODE_LENGTH), complete: true };
	}
	const code = crockford(compact).slice(0, FEED_CODE_LENGTH);
	if (!code) return { kind: 'empty' };
	// Letters that only read as digits (O, I, L) in a digit code: still a frame code.
	if (isDigits(code)) return { kind: 'frame', code: code.slice(0, CODE_LENGTH), complete: code.length === CODE_LENGTH };
	return { kind: 'feed', code, complete: code.length === FEED_CODE_LENGTH };
}

/** The value the field keeps: digits for a frame code, Crockford characters for a feed code. */
export function normalizeEnteredCode(raw: string, feeds = true): string {
	const p = parseEnteredCode(raw, feeds);
	return p.kind === 'empty' ? '' : p.code;
}

/** "KF7QM2XA" → "KF7Q-M2XA" (partial: "KF7QM" → "KF7Q-M"). */
export function formatFeedCode(code: string): string {
	const c = crockford(code).slice(0, FEED_CODE_LENGTH);
	return c.length > 4 ? `${c.slice(0, 4)}-${c.slice(4)}` : c;
}

/** How the field shows its value: "4821 3907" or "KF7Q-M2XA". */
export function formatEnteredCode(value: string, feeds = true): string {
	const p = parseEnteredCode(value, feeds);
	if (p.kind === 'empty') return '';
	if (p.kind === 'feed') return formatFeedCode(p.code);
	return p.code.length > 4 ? `${p.code.slice(0, 4)} ${p.code.slice(4)}` : p.code;
}

/** Caret position after reformatting: after as many code characters as there were before the caret. */
export function caretAfterFormatAny(raw: string, caret: number, formatted: string): number {
	const before = raw.slice(0, caret).replace(/[^0-9A-Za-z]/g, '').length;
	if (before === 0) return 0;
	let seen = 0;
	for (let i = 0; i < formatted.length; i++) {
		if (/[0-9A-Z]/i.test(formatted[i]!)) seen++;
		if (seen === before) return i + 1;
	}
	return formatted.length;
}

/** A feed invite link (https://<web>/fi/<token>) pasted where a code is asked for: its token, else null. */
export function feedInviteToken(raw: string): string | null {
	const m = raw.trim().match(/(?:^|\/)fi\/([A-Za-z0-9_-]{22,64})(?:[/?#].*)?$/);
	return m ? m[1]! : null;
}
