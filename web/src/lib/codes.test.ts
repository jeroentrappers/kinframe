import { describe, expect, it } from 'vitest';
import { caretAfterFormatAny, feedInviteToken, formatEnteredCode, formatFeedCode, normalizeEnteredCode, parseEnteredCode } from './codes';

describe('the shared "Enter a code" field', () => {
	it('reads digits as a frame code', () => {
		expect(parseEnteredCode('4821 3907')).toEqual({ kind: 'frame', code: '48213907', complete: true });
		expect(parseEnteredCode('482')).toEqual({ kind: 'frame', code: '482', complete: false });
		expect(parseEnteredCode('4821-39071234')).toEqual({ kind: 'frame', code: '48213907', complete: true });
		expect(parseEnteredCode('Code: 4821-3907')).toEqual({ kind: 'frame', code: '48213907', complete: true });
		expect(parseEnteredCode('   ')).toEqual({ kind: 'empty' });
	});

	it('reads a code with letters as a feed code', () => {
		expect(parseEnteredCode('KF7Q-M2XA')).toEqual({ kind: 'feed', code: 'KF7QM2XA', complete: true });
		expect(parseEnteredCode('kf7qm2xa')).toEqual({ kind: 'feed', code: 'KF7QM2XA', complete: true });
		expect(parseEnteredCode('KF7Q M2XA')).toEqual({ kind: 'feed', code: 'KF7QM2XA', complete: true });
		expect(parseEnteredCode('KF7Q')).toEqual({ kind: 'feed', code: 'KF7Q', complete: false });
		expect(parseEnteredCode('1234567A')).toEqual({ kind: 'feed', code: '1234567A', complete: true });
	});

	it('is forgiving like Crockford base32: O is 0, I and L are 1, U is dropped', () => {
		expect(parseEnteredCode('KF7Q-M2XO')).toEqual({ kind: 'feed', code: 'KF7QM2X0', complete: true });
		expect(parseEnteredCode('KFIL')).toEqual({ kind: 'feed', code: 'KF11', complete: false });
		expect(parseEnteredCode('KFU7')).toEqual({ kind: 'feed', code: 'KF7', complete: false });
		// Letters that only read as digits make a frame code.
		expect(parseEnteredCode('4821 39O7')).toEqual({ kind: 'frame', code: '48213907', complete: true });
	});

	it('finds the code in pasted text', () => {
		expect(parseEnteredCode('Join us with code KF7Q-M2XA!')).toEqual({ kind: 'feed', code: 'KF7QM2XA', complete: true });
		expect(parseEnteredCode('Code: KF7QM2XA, valid 7 days')).toEqual({ kind: 'feed', code: 'KF7QM2XA', complete: true });
	});

	it('reads only frame codes when the server has no feeds', () => {
		expect(parseEnteredCode('KF7Q-M2XA', false)).toEqual({ kind: 'frame', code: '72', complete: false });
		expect(parseEnteredCode('4821 3907', false)).toEqual({ kind: 'frame', code: '48213907', complete: true });
		expect(normalizeEnteredCode('ab12', false)).toBe('12');
	});

	it('formats both kinds', () => {
		expect(formatEnteredCode('48213907')).toBe('4821 3907');
		expect(formatEnteredCode('48213')).toBe('4821 3');
		expect(formatEnteredCode('KF7QM2XA')).toBe('KF7Q-M2XA');
		expect(formatEnteredCode('KF7QM')).toBe('KF7Q-M');
		expect(formatFeedCode('kf7qm2xa')).toBe('KF7Q-M2XA');
		expect(normalizeEnteredCode('kf7q-m2xa')).toBe('KF7QM2XA');
	});

	it('keeps the caret after the same number of code characters', () => {
		expect(caretAfterFormatAny('KF7QM', 5, 'KF7Q-M')).toBe(6);
		expect(caretAfterFormatAny('KF7Q', 4, 'KF7Q')).toBe(4);
		expect(caretAfterFormatAny('-', 1, '')).toBe(0);
	});

	it('takes the token out of a pasted invite link', () => {
		const tk = 'Ab3dEf6hIj9kLm2nOp5qRs';
		expect(feedInviteToken(`https://kin.example/fi/${tk}`)).toBe(tk);
		expect(feedInviteToken(`https://kin.example/fi/${tk}?x=1`)).toBe(tk);
		expect(feedInviteToken('https://kin.example/i/whatever')).toBeNull();
		expect(feedInviteToken('KF7Q-M2XA')).toBeNull();
	});
});
