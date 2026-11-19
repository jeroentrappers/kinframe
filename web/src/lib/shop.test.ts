import { describe, expect, it } from 'vitest';
import {
	browserRegions,
	clampQty,
	elsewhereSections,
	outsideCountries,
	paragraphs,
	visitorCountry,
	countryName,
	dispatchDay,
	errorsFromProblem,
	formatDay,
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

describe('stock', () => {
	const stocked: Shop = {
		...shop,
		products: [{ ...shop.products[0]!, in_stock: 2 }, { ...shop.products[1]!, in_stock: 0 }],
		stock: { ships_in_stock: '2026-11-17', ships_out_of_stock: '2026-11-20' }
	};
	it('ships the next business day only when every frame is in stock', () => {
		expect(dispatchDay(shop, { 'frame-10': 1 })).toBeNull();
		expect(dispatchDay(stocked, {})).toBeNull();
		expect(dispatchDay(stocked, { 'frame-10': 2 })).toBe('2026-11-17');
		expect(dispatchDay(stocked, { 'frame-10': 3 })).toBe('2026-11-20');
		expect(dispatchDay(stocked, { 'frame-10': 1, 'frame-15': 1 })).toBe('2026-11-20');
	});
	it('formats a day without shifting it by time zone', () => {
		expect(formatDay('2026-11-17', 'en-GB')).toBe('Tuesday 17 November');
		expect(formatDay('2026-11-17', 'nl-BE')).toBe('dinsdag 17 november');
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
	const ok: ReservationForm = { name: 'An', email: 'an@example.org', country: 'BE', postalCode: '', note: '', consent: true, qty: { 'frame-10': 1 }, street: '', city: '', phone: '' };

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
	it('needs the delivery address when the shop takes payment, and sends it', () => {
		const paid = { ...shop, checkout: true };
		expect(validateReservation(ok, paid)).toEqual({ street: 'required', postalCode: 'required', city: 'required' });
		expect(validateReservation(ok, shop)).toEqual({});
		const full = { ...ok, street: ' Veldstraat 10 ', postalCode: '9000', city: 'Gent', phone: '' };
		expect(validateReservation(full, paid)).toEqual({});
		const body = reservationBody(full, paid, 'nl', '');
		expect(body).toMatchObject({ street: 'Veldstraat 10', city: 'Gent', postal_code: '9000' });
		expect(body).not.toHaveProperty('phone');
		expect(reservationBody(full, shop, 'nl', '')).not.toHaveProperty('street');
		expect(errorsFromProblem([{ field: 'postal_code', code: 'minLength' }, { field: 'street', code: 'minLength' }])).toEqual({
			postalCode: 'required',
			street: 'required'
		});
	});
});

describe('notice, DIY and elsewhere sections', () => {
	const amazonBE = { countries: ['BE'], retailer: 'Amazon', product: 'Denver PFF-1024', url: 'https://www.amazon.com.be/dp/X', affiliate: true };
	const bolBE = { countries: ['BE', 'NL'], retailer: 'bol.com', product: 'Denver PFF-1024', url: 'https://www.bol.com/p/1', secondary: true };
	const amazonNL = { countries: ['NL'], retailer: 'Amazon', product: 'Denver PFF-1024', url: 'https://www.amazon.nl/dp/X', affiliate: true };
	const other = { countries: ['*'], retailer: 'Shop', product: 'Denver PFF-1024', url: 'https://shop.example.org/x' };
	const s = { shipping: { countries: ['BE'], flat_cents: 0 }, elsewhere: [amazonBE, bolBE, amazonNL, other] };

	it('splits plain text at blank lines', () => {
		expect(paragraphs('We resell converted frames.\n\n  Or do it yourself.\nReally. \r\n \r\n\n')).toEqual([
			'We resell converted frames.',
			'Or do it yourself.\nReally.'
		]);
		expect(paragraphs(undefined)).toEqual([]);
		expect(paragraphs('   ')).toEqual([]);
	});

	it('guesses the visitor country from the browser languages, preferring a shipping country', () => {
		expect(browserRegions(['nl-BE', 'nl', 'en-US', 'bad tag!'])).toEqual(['BE', 'NL', 'US']);
		expect(visitorCountry(['fr-FR', 'nl-BE'], ['BE'])).toBe('BE');
		expect(visitorCountry(['nl-NL', 'en'], ['BE'])).toBe('NL');
		// No stated region: the first shipping country, not the country a bare language maps to.
		expect(visitorCountry(['nl'], ['BE'])).toBe('BE');
		expect(visitorCountry(['fr', 'en'], ['BE'])).toBe('BE');
		expect(visitorCountry([], ['BE'])).toBe('BE');
		expect(visitorCountry([], [])).toBeNull();
	});

	it('a visitor in a shipping country gets the DIY links for that country, and still the other countries', () => {
		const { diy, outside } = elsewhereSections(s, 'BE');
		expect(outside?.main).toEqual([amazonNL, other]);
		expect(diy?.main).toEqual([amazonBE]);
		expect(diy?.secondary).toEqual([bolBE]);
		expect(diy?.affiliate).toBe(true);
	});

	it('other visitors keep the outside section, without the links for shipping countries only', () => {
		const { diy, outside } = elsewhereSections(s, 'NL');
		expect(diy).toBeNull();
		expect(outside?.main).toEqual([amazonNL, other]);
		expect(outside?.secondary).toEqual([bolBE]);
		expect(elsewhereSections(s, null).outside?.main).toEqual([amazonNL, other]);
		expect(outsideCountries(bolBE, ['BE'])).toEqual(['NL']);
	});

	it('the disclosure only for affiliate links; no section without links', () => {
		const plain = { ...s, elsewhere: [{ ...amazonBE, affiliate: false }, bolBE] };
		expect(elsewhereSections(plain, 'BE').diy?.affiliate).toBe(false);
		expect(elsewhereSections({ ...s, elsewhere: [amazonNL] }, 'BE').diy).toBeNull();
		expect(elsewhereSections({ ...s, elsewhere: [amazonBE] }, 'DE')).toEqual({ diy: null, outside: null });
		expect(elsewhereSections({ ...s, elsewhere: undefined }, 'DE')).toEqual({ diy: null, outside: null });
	});
});
