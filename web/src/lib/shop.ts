// Pure logic for the optional pre-order shop (README §5.11): totals with the shipping rule, money
// formatting, localized config texts and form validation. No DOM, no state: unit-tested in shop.test.ts.
// The server recomputes every price; these are only for showing a live total.
import type { Schemas } from './api/client';

export type Shop = Schemas['Shop'];
export type ShopProduct = Schemas['ShopProduct'];
export type ShopReservation = Schemas['ShopReservation'];
export type ShopReservationStatus = Schemas['ShopReservationStatus'];
export type ShopReceipt = Schemas['ShopReservationReceipt'];

export type ShopPaymentStatus = Schemas['ShopPaymentStatus'];

export const RESERVATION_STATUSES: ShopReservationStatus[] = ['new', 'contacted', 'confirmed', 'cancelled'];
/** Statuses for paid orders (shops with checkout). */
export const ORDER_STATUSES: ShopReservationStatus[] = ['new', 'confirmed', 'shipped', 'cancelled'];

/** Quantity per product id. */
export type Quantities = Record<string, number>;

export interface QuoteLine {
	product: ShopProduct;
	quantity: number;
	lineCents: number;
}

export interface Quote {
	lines: QuoteLine[];
	count: number;
	subtotalCents: number;
	shippingCents: number;
	totalCents: number;
	/** Shipping is free from this subtotal (null: never). */
	freeFromCents: number | null;
	/** How much more to add for free shipping; null when already free, empty, or never free. */
	missingForFreeCents: number | null;
}

/** Clamp a quantity to 0…max_qty (non-numbers become 0). */
export function clampQty(n: number, product: Pick<ShopProduct, 'max_qty'>): number {
	if (!Number.isFinite(n)) return 0;
	return Math.min(Math.max(0, Math.trunc(n)), product.max_qty);
}

export function shippingFor(shop: Pick<Shop, 'shipping'>, subtotalCents: number): number {
	if (subtotalCents <= 0) return 0;
	const free = shop.shipping.free_from_cents;
	if (free != null && subtotalCents >= free) return 0;
	return shop.shipping.flat_cents;
}

/** Same rule as the server (shop.Config.Price): unit price × quantity, flat shipping unless free. */
export function quote(shop: Pick<Shop, 'products' | 'shipping'>, qty: Quantities): Quote {
	const lines: QuoteLine[] = [];
	for (const product of shop.products) {
		const quantity = clampQty(qty[product.id] ?? 0, product);
		if (quantity > 0) lines.push({ product, quantity, lineCents: quantity * product.price_cents });
	}
	const subtotalCents = lines.reduce((a, l) => a + l.lineCents, 0);
	const shippingCents = shippingFor(shop, subtotalCents);
	const free = shop.shipping.free_from_cents ?? null;
	return {
		lines,
		count: lines.reduce((a, l) => a + l.quantity, 0),
		subtotalCents,
		shippingCents,
		totalCents: subtotalCents + shippingCents,
		freeFromCents: free,
		missingForFreeCents: subtotalCents > 0 && shippingCents > 0 && free != null ? free - subtotalCents : null
	};
}

/**
 * Shops with stock: the dispatch day (YYYY-MM-DD) of these quantities, the next business day when every frame is in
 * stock, else the later day. Null without stock management or for an empty order.
 */
export function dispatchDay(shop: Pick<Shop, 'products' | 'stock'>, qty: Quantities): string | null {
	if (!shop.stock) return null;
	const chosen = shop.products.filter((p) => (qty[p.id] ?? 0) > 0);
	if (chosen.length === 0) return null;
	const inStock = chosen.every((p) => clampQty(qty[p.id] ?? 0, p) <= (p.in_stock ?? 0));
	return inStock ? shop.stock.ships_in_stock : shop.stock.ships_out_of_stock;
}

/** A calendar day (YYYY-MM-DD) as "Tuesday 17 November" in the UI language. */
export function formatDay(day: string, tag: string): string {
	const [y, m, d] = day.split('-').map(Number);
	const date = new Date(y!, (m ?? 1) - 1, d ?? 1);
	try {
		return new Intl.DateTimeFormat(tag, { weekday: 'long', day: 'numeric', month: 'long' }).format(date);
	} catch {
		return day;
	}
}

/** "€79.00", "€ 79,00", "79,00 €" by the UI language. Whole amounts keep their cents for clarity in totals. */
export function formatMoney(cents: number, currency: string, tag: string, opts: { compact?: boolean } = {}): string {
	const whole = opts.compact && cents % 100 === 0;
	try {
		return new Intl.NumberFormat(tag, {
			style: 'currency',
			currency,
			minimumFractionDigits: whole ? 0 : 2,
			maximumFractionDigits: whole ? 0 : 2
		}).format(cents / 100);
	} catch {
		return `${currency} ${(cents / 100).toFixed(2)}`;
	}
}

/** A text from a {lang: text} map: the exact language, then its base ("zh" for "zh-TW"), then English. */
export function localized<T>(map: Record<string, T> | null | undefined, locale: string): T | undefined {
	if (!map) return undefined;
	return map[locale] ?? map[locale.split('-')[0]!] ?? map.en ?? Object.values(map)[0];
}

/** Country name in the UI language ("België", "Pays-Bas"); the code when Intl can't tell. */
export function countryName(code: string, tag: string): string {
	try {
		return new Intl.DisplayNames([tag], { type: 'region' }).of(code) ?? code;
	} catch {
		return code;
	}
}

export interface ReservationForm {
	name: string;
	email: string;
	country: string;
	postalCode: string;
	note: string;
	consent: boolean;
	qty: Quantities;
	// Delivery address and phone: shops with checkout only.
	street: string;
	city: string;
	phone: string;
}

export type FormField = 'items' | 'name' | 'email' | 'country' | 'street' | 'postalCode' | 'city' | 'consent';
export type FormErrors = Partial<Record<FormField, 'required' | 'format'>>;

// Deliberately simple: something@something.tld. The server checks again.
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@.]{2,}$/;

export function isEmail(s: string): boolean {
	return EMAIL.test(s.trim());
}

/** Client-side checks, in form order. Empty object = OK to submit. */
export function validateReservation(form: ReservationForm, shop: Pick<Shop, 'products' | 'shipping' | 'checkout'>): FormErrors {
	const errors: FormErrors = {};
	if (quote(shop, form.qty).count === 0) errors.items = 'required';
	if (!form.name.trim()) errors.name = 'required';
	if (!form.email.trim()) errors.email = 'required';
	else if (!isEmail(form.email)) errors.email = 'format';
	if (!shop.shipping.countries.includes(form.country)) errors.country = 'required';
	if (shop.checkout) {
		if (!form.street.trim()) errors.street = 'required';
		if (!form.postalCode.trim()) errors.postalCode = 'required';
		if (!form.city.trim()) errors.city = 'required';
	}
	if (!form.consent) errors.consent = 'required';
	return errors;
}

/** Maps the server's field errors (`email`/`format`, `items.0.quantity`/`maximum`…) onto the form. */
export function errorsFromProblem(errs: { field?: string; code?: string }[] | undefined): FormErrors {
	const out: FormErrors = {};
	for (const e of errs ?? []) {
		const f = (e.field ?? '').split('.')[0];
		if (f === 'items') out.items = 'required';
		else if (f === 'email') out.email = e.code === 'format' ? 'format' : 'required';
		else if (f === 'name' || f === 'country' || f === 'consent' || f === 'street' || f === 'city') out[f] = 'required';
		else if (f === 'postal_code') out.postalCode = 'required';
	}
	return out;
}

/** The request body for POST /v1/shop/reservations. Prices are never sent. */
export function reservationBody(form: ReservationForm, shop: Pick<Shop, 'products' | 'shipping' | 'checkout'>, locale: string, honeypot: string) {
	const q = quote(shop, form.qty);
	return {
		name: form.name.trim(),
		email: form.email.trim(),
		country: form.country,
		...(form.postalCode.trim() ? { postal_code: form.postalCode.trim() } : {}),
		items: q.lines.map((l) => ({ product_id: l.product.id, quantity: l.quantity })),
		...(form.note.trim() ? { note: form.note.trim() } : {}),
		...(shop.checkout
			? { street: form.street.trim(), city: form.city.trim(), ...(form.phone.trim() ? { phone: form.phone.trim() } : {}) }
			: {}),
		locale,
		consent: form.consent,
		...(honeypot ? { website: honeypot } : {})
	};
}

export type ShopElsewhereLink = Schemas['ShopElsewhereLink'];

/** A plain text split at blank lines (the shop file's `notice` and `diy.body`), each paragraph trimmed. */
export function paragraphs(text: string | null | undefined): string[] {
	return (text ?? '')
		.replace(/\r\n/g, '\n')
		.split(/\n[ \t]*\n/)
		.map((p) => p.trim())
		.filter(Boolean);
}

/** The region of each browser language ("nl-BE" → BE, "nl" → NL by likely region), in order, without repeats. */
export function browserRegions(languages: readonly string[]): string[] {
	const out: string[] = [];
	for (const l of languages) {
		try {
			const region = new Intl.Locale(l).maximize().region;
			if (region && !out.includes(region)) out.push(region);
		} catch {
			/* malformed tag */
		}
	}
	return out;
}

/**
 * The visitor's country as the shop page guesses it from the browser languages. Only regions the browser states count
 * ("nl-BE", "fr-FR"): the first one the shop ships to (so "fr-FR, nl-BE" is BE for a Belgian shop), else the first one.
 * A browser that states no region at all ("nl", "fr") is taken to be in the first shipping country: on the shop's own
 * domain that is far likelier than the country a bare language maps to (plain "nl" would otherwise mean NL). Null when
 * nothing can be guessed.
 */
export function visitorCountry(languages: readonly string[], shipping: readonly string[]): string | null {
	const stated: string[] = [];
	for (const l of languages) {
		try {
			const region = new Intl.Locale(l).region;
			if (region && !stated.includes(region)) stated.push(region);
		} catch {
			/* malformed tag */
		}
	}
	if (stated.length) return stated.find((r) => shipping.includes(r)) ?? stated[0] ?? null;
	return shipping[0] ?? browserRegions(languages)[0] ?? null;
}

/** Retailer links in one section: buttons, small "also at" text links, and whether to show the affiliate disclosure. */
export interface RetailerLinks {
	main: ShopElsewhereLink[];
	secondary: ShopElsewhereLink[];
	affiliate: boolean;
}

function retailerLinks(links: ShopElsewhereLink[]): RetailerLinks {
	return {
		main: links.filter((l) => !l.secondary),
		secondary: links.filter((l) => !!l.secondary),
		affiliate: links.some((l) => l.affiliate)
	};
}

/**
 * Which retailer section a visitor sees. In a shipping country: `diy`, the links that list that country ("buy it
 * yourself and convert it"). Elsewhere, or when the country is unknown: `outside`, the links for countries the shop
 * doesn't ship to ("Outside Belgium?"). Each is null when it has no links.
 */
export function elsewhereSections(
	shop: Pick<Shop, 'shipping' | 'elsewhere'>,
	country: string | null
): { diy: RetailerLinks | null; outside: RetailerLinks | null } {
	const all = shop.elsewhere ?? [];
	const ships = (c: string) => shop.shipping.countries.includes(c);
	if (country && ships(country)) {
		const diy = all.filter((l) => l.countries.includes(country));
		return { diy: diy.length ? retailerLinks(diy) : null, outside: null };
	}
	const outside = all.filter((l) => l.countries.some((c) => !ships(c)));
	return { diy: null, outside: outside.length ? retailerLinks(outside) : null };
}

/** The countries an outside link is for, without the shipping countries (those see it in the DIY section). */
export function outsideCountries(link: ShopElsewhereLink, shipping: readonly string[]): string[] {
	return link.countries.filter((c) => !shipping.includes(c));
}

/** navigator.languages, empty outside a browser. */
export function browserLanguages(): readonly string[] {
	return typeof navigator !== 'undefined' ? (navigator.languages ?? [navigator.language]) : [];
}
