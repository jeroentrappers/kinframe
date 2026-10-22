import { describe, expect, it } from 'vitest';
import { mediaCatalogs, tm } from './media';

describe('media strings', () => {
	it('Dutch has every English key with the same placeholders', () => {
		const ph = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
		expect(Object.keys(mediaCatalogs.nl).sort()).toEqual(Object.keys(mediaCatalogs.en).sort());
		for (const [k, v] of Object.entries(mediaCatalogs.en)) expect(ph(mediaCatalogs.nl[k as keyof typeof mediaCatalogs.en]), k).toEqual(ph(v));
	});
	it('falls back to English', () => {
		expect(tm('media.preparingPct', { pct: 40 })).toBe('Preparing… 40%');
	});
});
