// Pure logic for the optional pre-order shop (README §5.10): totals with the shipping rule, money
// formatting, localized config texts and form validation. No DOM, no state: unit-tested in shop.test.ts.
// The server recomputes every price; these are only for showing a live total.
import type { Schemas } from './api/client';

export type Shop = Schemas['Shop'];
export type ShopProduct = Schemas['ShopProduct'];
export type ShopReservation = Schemas['ShopReservation'];
export type ShopReservationStatus = Schemas['ShopReservationStatus'];
export type ShopReceipt = Schemas['ShopReservationReceipt'];

export const RESERVATION_STATUSES: ShopReservationStatus[] = ['new', 'contacted', 'confirmed', 'cancelled'];

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
}

export type FormField = 'items' | 'name' | 'email' | 'country' | 'consent';
export type FormErrors = Partial<Record<FormField, 'required' | 'format'>>;

// Deliberately simple: something@something.tld. The server checks again.
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@.]{2,}$/;

export function isEmail(s: string): boolean {
	return EMAIL.test(s.trim());
}

/** Client-side checks, in form order. Empty object = OK to submit. */
export function validateReservation(form: ReservationForm, shop: Pick<Shop, 'products' | 'shipping'>): FormErrors {
	const errors: FormErrors = {};
	if (quote(shop, form.qty).count === 0) errors.items = 'required';
	if (!form.name.trim()) errors.name = 'required';
	if (!form.email.trim()) errors.email = 'required';
	else if (!isEmail(form.email)) errors.email = 'format';
	if (!shop.shipping.countries.includes(form.country)) errors.country = 'required';
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
		else if (f === 'name' || f === 'country' || f === 'consent') out[f] = 'required';
	}
	return out;
}

/** The request body for POST /v1/shop/reservations. Prices are never sent. */
export function reservationBody(form: ReservationForm, shop: Pick<Shop, 'products' | 'shipping'>, locale: string, honeypot: string) {
	const q = quote(shop, form.qty);
	return {
		name: form.name.trim(),
		email: form.email.trim(),
		country: form.country,
		...(form.postalCode.trim() ? { postal_code: form.postalCode.trim() } : {}),
		items: q.lines.map((l) => ({ product_id: l.product.id, quantity: l.quantity })),
		...(form.note.trim() ? { note: form.note.trim() } : {}),
		locale,
		consent: form.consent,
		...(honeypot ? { website: honeypot } : {})
	};
}
