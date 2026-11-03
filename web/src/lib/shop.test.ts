import { describe, expect, it } from 'vitest';
import {
	clampQty,
	countryName,
	errorsFromProblem,
	formatMoney,
	isEmail,
	localized,
	quote,
	reservationBody,
	shippingFor,
	validateReservation,
	type ReservationForm,
	type Shop
} from './shop';

const shop: Shop = {
	brand: 'kinframe',
	currency: 'EUR',
	vat_percent: 21,
	products: [
		{ id: 'frame-10', name: 'kinframe 10.1"', price_cents: 7900, max_qty: 5, image: 'frame-10', specs: { en: ['Wi-Fi'], nl: ['wifi'] } },
		{ id: 'frame-15', name: 'kinframe 15.6"', price_cents: 15900, max_qty: 5, image: 'frame-15' }
	],
	shipping: { countries: ['BE', 'NL'], flat_cents: 695, free_from_cents: 10000 },
	seller: { name: 'Example BV', email: 'shop@example.org' }
};

describe('quote', () => {
	it('charges shipping below the free-shipping threshold and says how much is missing', () => {
		const q = quote(shop, { 'frame-10': 1 });
		expect(q).toMatchObject({ count: 1, subtotalCents: 7900, shippingCents: 695, totalCents: 8595, missingForFreeCents: 2100 });
	});

	it('ships two small frames or one large frame free', () => {
		expect(quote(shop, { 'frame-10': 2 })).toMatchObject({ subtotalCents: 15800, shippingCents: 0, totalCents: 15800, missingForFreeCents: null });
		expect(quote(shop, { 'frame-15': 1 })).toMatchObject({ subtotalCents: 15900, shippingCents: 0, totalCents: 15900 });
	});

	it('is free from exactly the threshold', () => {
		expect(shippingFor(shop, 9999)).toBe(695);
		expect(shippingFor(shop, 10000)).toBe(0);
	});

	it('is empty with nothing chosen (no shipping, nothing missing)', () => {
		expect(quote(shop, {})).toMatchObject({ lines: [], count: 0, totalCents: 0, shippingCents: 0, missingForFreeCents: null });
	});

	it('never ships free without a threshold', () => {
		const q = quote({ ...shop, shipping: { ...shop.shipping, free_from_cents: null } }, { 'frame-15': 3 });
		expect(q.shippingCents).toBe(695);
		expect(q.missingForFreeCents).toBeNull();
	});

	it('clamps quantities to 0…max and ignores unknown products', () => {
		expect(clampQty(9, { max_qty: 5 })).toBe(5);
		expect(clampQty(-2, { max_qty: 5 })).toBe(0);
		expect(clampQty(Number.NaN, { max_qty: 5 })).toBe(0);
		expect(quote(shop, { 'frame-10': 7, toaster: 3 })).toMatchObject({ count: 5, subtotalCents: 39500 });
	});
});

describe('formatting', () => {
	it('formats money by language', () => {
		expect(formatMoney(7900, 'EUR', 'en-GB')).toBe('€79.00');
		expect(formatMoney(695, 'EUR', 'nl-BE').replace(/\s/g, ' ')).toBe('€ 6,95');
		expect(formatMoney(15900, 'EUR', 'fr-BE').replace(/\s/g, ' ')).toBe('159,00 €');
		expect(formatMoney(7900, 'EUR', 'en-GB', { compact: true })).toBe('€79');
		expect(formatMoney(695, 'EUR', 'en-GB', { compact: true })).toBe('€6.95');
	});

	it('picks localized texts with fallbacks', () => {
		const m = { en: 'early 2027', nl: 'begin 2027', zh: '2027 年初' };
		expect(localized(m, 'nl')).toBe('begin 2027');
		expect(localized(m, 'zh-TW')).toBe('2027 年初');
		expect(localized(m, 'fr')).toBe('early 2027');
		expect(localized(undefined, 'fr')).toBeUndefined();
	});

	it('names countries in the UI language', () => {
		expect(countryName('BE', 'nl-BE')).toBe('België');
		expect(countryName('NL', 'fr-BE')).toBe('Pays-Bas');
	});
});

describe('validation', () => {
	const ok: ReservationForm = { name: 'An', email: 'an@example.org', country: 'BE', postalCode: '', note: '', consent: true, qty: { 'frame-10': 1 } };

	it('accepts a complete form', () => {
		expect(validateReservation(ok, shop)).toEqual({});
	});

	it('flags every missing or wrong field', () => {
		expect(validateReservation({ ...ok, name: ' ', email: 'an@', country: 'DE', consent: false, qty: {} }, shop)).toEqual({
			items: 'required',
			name: 'required',
			email: 'format',
			country: 'required',
			consent: 'required'
		});
		expect(validateReservation({ ...ok, email: '' }, shop)).toEqual({ email: 'required' });
	});

	it('checks emails loosely', () => {
		expect(isEmail('a@b.be')).toBe(true);
		expect(isEmail(' a@b.be ')).toBe(true);
		expect(isEmail('a@b')).toBe(false);
		expect(isEmail('a b@c.be')).toBe(false);
	});

	it('maps server field errors onto the form', () => {
		expect(errorsFromProblem([{ field: 'email', code: 'format' }, { field: 'items.1.quantity', code: 'maximum' }, { field: 'consent', code: 'required' }])).toEqual({
			email: 'format',
			items: 'required',
			consent: 'required'
		});
	});

	it('builds a body without prices, trimming and dropping empty optional fields', () => {
		const body = reservationBody({ ...ok, name: ' An ', note: '  ', postalCode: ' 9000 ', qty: { 'frame-10': 2, 'frame-15': 0 } }, shop, 'nl', '');
		expect(body).toEqual({
			name: 'An',
			email: 'an@example.org',
			country: 'BE',
			postal_code: '9000',
			items: [{ product_id: 'frame-10', quantity: 2 }],
			locale: 'nl',
			consent: true
		});
		expect(JSON.stringify(body)).not.toContain('cents');
		expect(reservationBody(ok, shop, 'en', 'spam')).toHaveProperty('website', 'spam');
	});
});
