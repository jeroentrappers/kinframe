import { describe, expect, it } from 'vitest';
import { appUrl, hostName, isStorefrontHost, isStorefrontPath, storefrontLocale } from './storefront';

describe('storefront detection', () => {
	it('matches configured domains by host name, ignoring case, port and a trailing dot', () => {
		const domains = ['shop.example.org', 'frames.example.net'];
		expect(isStorefrontHost('shop.example.org', domains)).toBe(true);
		expect(isStorefrontHost('Shop.Example.org:8443', domains)).toBe(true);
		expect(isStorefrontHost('shop.example.org.', domains)).toBe(true);
		expect(isStorefrontHost('frames.example.net', domains)).toBe(true);
		expect(isStorefrontHost('app.example.org', domains)).toBe(false);
		expect(isStorefrontHost('evil.shop.example.org', domains)).toBe(false);
		expect(isStorefrontHost('example.org', domains)).toBe(false);
		expect(isStorefrontHost('[::1]:4173', domains)).toBe(false);
	});

	it('is never a storefront without a shop or without domains', () => {
		expect(isStorefrontHost('shop.example.org', undefined)).toBe(false);
		expect(isStorefrontHost('shop.example.org', null)).toBe(false);
		expect(isStorefrontHost('shop.example.org', [])).toBe(false);
		expect(isStorefrontHost('', ['shop.example.org'])).toBe(false);
	});

	it('normalizes host names', () => {
		expect(hostName(' Shop.Example.ORG:443 ')).toBe('shop.example.org');
		expect(hostName('localhost')).toBe('localhost');
		expect(hostName('[::1]:80')).toBe('[::1]');
	});
});

describe('storefront paths', () => {
	it('keeps the landing page and the shop pages', () => {
		for (const p of ['/', '/shop', '/shop/', '/shop/cancel']) expect(isStorefrontPath(p), p).toBe(true);
	});
	it('sends everything else to the app', () => {
		for (const p of ['/frames', '/send', '/settings', '/settings/reservations', '/callback', '/f/12345678', '/i/abc', '/tv/x', '/shopping', '/shop/x'])
			expect(isStorefrontPath(p), p).toBe(false);
	});
});

describe('app URL', () => {
	it('uses the web origin from discovery', () => {
		expect(appUrl('https://app.example.org', 'https://app.example.org/v1', '/frames?x=1#y')).toBe('https://app.example.org/frames?x=1#y');
		expect(appUrl('https://app.example.org/', null, 'send')).toBe('https://app.example.org/send');
	});
	it('falls back to the API origin, and never points back at the storefront', () => {
		expect(appUrl(undefined, 'https://app.example.org/v1', '/frames')).toBe('https://app.example.org/frames');
		expect(appUrl('/relative', 'https://app.example.org/v1', '/')).toBe('https://app.example.org/');
		expect(appUrl('https://shop.example.org', 'https://shop.example.org/v1', '/frames', 'https://shop.example.org')).toBeNull();
		expect(appUrl(null, null, '/')).toBeNull();
	});
});

describe('storefront language', () => {
	it('takes the first browser language the shop speaks', () => {
		expect(storefrontLocale(['fr-BE', 'nl'], 'nl')).toBe('fr');
		expect(storefrontLocale(['es-ES', 'de-AT', 'en'], 'nl')).toBe('de');
		expect(storefrontLocale(['en-US'], 'nl')).toBe('en');
		expect(storefrontLocale(['nl-NL'], 'en')).toBe('nl');
	});
	it('else the operator default, else English', () => {
		expect(storefrontLocale(['ja', 'es'], 'nl')).toBe('nl');
		expect(storefrontLocale([], 'nl')).toBe('nl');
		expect(storefrontLocale(['ja'], undefined)).toBe('en');
		expect(storefrontLocale(['ja'], 'ja')).toBe('en');
	});
});
