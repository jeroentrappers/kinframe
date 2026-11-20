import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ErrorEvent } from '@sentry/browser';
import { crashReportsEnabled, redactPath, redactUrl, RELEASE, scrubBreadcrumb, scrubEvent, setCrashReportsEnabled } from './crash';

describe('crash reporting', () => {
	afterEach(() => vi.unstubAllGlobals());

	it('names the release kinframe-web@<version>', () => {
		expect(RELEASE).toMatch(/^kinframe-web@[0-9A-Za-z.-]+(\+[0-9a-f]{1,7})?$/);
	});

	it('is on by default and remembers "off" on this device', () => {
		const store = new Map<string, string>();
		vi.stubGlobal('localStorage', {
			getItem: (k: string) => store.get(k) ?? null,
			setItem: (k: string, v: string) => void store.set(k, v),
			removeItem: (k: string) => void store.delete(k)
		});
		expect(crashReportsEnabled()).toBe(true);
		setCrashReportsEnabled(false);
		expect(crashReportsEnabled()).toBe(false);
		setCrashReportsEnabled(true);
		expect(crashReportsEnabled()).toBe(true);
	});

	it('stays on when storage is blocked', () => {
		vi.stubGlobal('localStorage', {
			getItem: () => {
				throw new Error('blocked');
			}
		});
		expect(crashReportsEnabled()).toBe(true);
	});

	it('redacts ids, tokens and codes in paths', () => {
		expect(redactPath('/i/abcdefgh')).toBe('/i/*');
		expect(redactPath('/f/K7Q2XP')).toBe('/f/*');
		expect(redactPath('/frames/0b9e4f3c-6f0e-4c4e-9a59-8e3d4a1f2b7c')).toBe('/frames/*');
		expect(redactPath('/settings/remote-help')).toBe('/settings/remote-help');
		expect(redactPath('/v1/invites/tok/accept')).toBe('/v1/invites/*/accept');
		expect(redactPath('/v1/media/signed/items/x/IMG_0001.HEIC')).toBe('/v1/media/signed/*/*/*');
		expect(redactUrl('https://alice:pw@kinframe.example/tv/abc?t=secret#x')).toBe('https://kinframe.example/tv/*');
		expect(redactUrl('/v1/frames/12?cursor=9')).toBe('/v1/frames/*');
	});

	it('scrubs events: no user, headers but the user agent, query strings, bodies or console', () => {
		const ev = {
			event_id: '1',
			type: undefined,
			user: { id: 'u1', email: 'alice@example.com', ip_address: '{{auto}}' },
			server_name: 'laptop',
			request: {
				url: 'https://kinframe.example/i/SeCrEt?next=/frames',
				headers: { 'User-Agent': 'Mozilla/5.0', Referer: 'https://kinframe.example/f/CODE12', Cookie: 'a=b' },
				query_string: 'next=/frames',
				data: '{"x":1}',
				cookies: { a: 'b' }
			},
			message: 'failed https://kinframe.example/v1/items/9/media/thumb?sig=abc',
			exception: { values: [{ type: 'TypeError', value: 'Failed to fetch https://kinframe.example/v1/tv/tok?t=secret' }] },
			breadcrumbs: [
				{ category: 'console', message: 'hello alice@example.com' },
				{ category: 'ui.click', message: 'button#delete-alice' },
				{ category: 'navigation', data: { from: '/i/SeCrEt', to: '/frames/0b9e4f3c-6f0e-4c4e-9a59-8e3d4a1f2b7c?tab=people' } },
				{ category: 'fetch', type: 'http', data: { method: 'PATCH', url: '/v1/me?x=1', status_code: 500, request_body_size: 10 } }
			],
			extra: { note: 'alice' }
		} as unknown as ErrorEvent;
		const out = scrubEvent(ev);
		expect(out.user).toBeUndefined();
		expect(out.server_name).toBeUndefined();
		expect(out.request).toEqual({ url: 'https://kinframe.example/i/*', headers: { 'User-Agent': 'Mozilla/5.0' } });
		expect(out.message).toBe('failed https://kinframe.example/v1/items/*/media/thumb');
		expect(out.exception?.values?.[0]?.value).toBe('Failed to fetch https://kinframe.example/v1/tv/*');
		expect(out.breadcrumbs?.map((b) => b.category)).toEqual(['navigation', 'fetch']);
		expect(out.breadcrumbs?.[0]?.data).toEqual({ from: '/i/*', to: '/frames/*' });
		expect(out.breadcrumbs?.[1]?.data).toEqual({ method: 'PATCH', url: '/v1/me', status_code: 500 });
		const json = JSON.stringify(out);
		for (const leak of ['alice', 'SeCrEt', 'secret', 'CODE12', 'Cookie', 'sig=']) expect(json).not.toContain(leak);
		expect(scrubBreadcrumb({ category: 'console', message: 'x' })).toBeNull();
	});
});
