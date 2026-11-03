// Storefront mode (README §5.11, discovery `shop.domains`): on one of the operator's storefront domains the web
// app is the brand's shop only: landing page, /shop and /shop/cancel. Sign-in and the family app stay on the main
// origin (discovery `web`), where Logto's redirect URIs are registered. Pure logic, unit-tested in storefront.test.ts.
import { matchLocale, type Locale } from './i18n/locales';

/** Languages the shop fully speaks (its emails too); the server picks a storefront's link-preview language the same way. */
export const STOREFRONT_LANGUAGES: readonly Locale[] = ['en', 'nl', 'fr', 'de'];

/** "Shop.Example.org:443" → "shop.example.org". */
export function hostName(host: string): string {
	let h = host.trim().toLowerCase();
	if (h.startsWith('[')) return h.slice(0, h.indexOf(']') + 1) || h;
	const colon = h.lastIndexOf(':');
	if (colon >= 0) h = h.slice(0, colon);
	return h.replace(/\.$/, '');
}

/** Whether `host` (location.host) is one of the storefront domains. */
export function isStorefrontHost(host: string, domains: readonly string[] | null | undefined): boolean {
	if (!host || !domains?.length) return false;
	const h = hostName(host);
	return domains.some((d) => hostName(d) === h);
}

/** The pages a storefront serves itself; every other path belongs to the app. */
export function isStorefrontPath(pathname: string): boolean {
	return /^\/(shop(\/cancel)?)?$/.test(pathname.replace(/\/+$/, '') || '/');
}

/**
 * The same path on the app's origin: discovery `web`, else the origin of `api`. Null when neither is a usable
 * absolute URL, or when it would point back at the storefront itself.
 */
export function appUrl(web: string | null | undefined, api: string | null | undefined, path: string, here?: string): string | null {
	for (const base of [web, api]) {
		if (!base) continue;
		try {
			const origin = new URL(base).origin;
			if (origin === 'null' || origin === here) continue;
			return origin + (path.startsWith('/') ? path : `/${path}`);
		} catch {
			/* not absolute */
		}
	}
	return null;
}

/**
 * The storefront's language when the visitor hasn't picked one: the first browser language the shop speaks,
 * else the operator's default (discovery `shop.default_locale`), else English.
 */
export function storefrontLocale(languages: readonly string[], fallback: string | null | undefined): Locale {
	for (const tag of languages) {
		const l = matchLocale(tag);
		if (l && STOREFRONT_LANGUAGES.includes(l)) return l;
	}
	const f = matchLocale(fallback);
	return f && STOREFRONT_LANGUAGES.includes(f) ? f : 'en';
}
