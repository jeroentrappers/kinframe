import { describe, expect, it } from 'vitest';
import { en } from './en';
import { nl } from './nl';
import { interpolate } from './index.svelte';

const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

describe('message catalogs', () => {
	it('Dutch has exactly the English keys', () => {
		expect(Object.keys(nl).sort()).toEqual(Object.keys(en).sort());
	});

	it('every translation uses the same placeholders', () => {
		for (const key of Object.keys(en) as (keyof typeof en)[]) {
			expect(placeholders(nl[key]), key).toEqual(placeholders(en[key]));
		}
	});

	it('plural keys come in one/other pairs', () => {
		for (const key of Object.keys(en)) {
			if (key.endsWith('.one')) expect(en).toHaveProperty([key.replace(/\.one$/, '.other')]);
		}
	});

	it('has a message for every ProblemCode', () => {
		const codes = ['unauthorized', 'token_expired', 'device_revoked', 'forbidden', 'missing_permission', 'not_found', 'code_invalid', 'code_expired', 'invite_invalid', 'invite_expired', 'already_member', 'validation_failed', 'too_many_items', 'video_too_long', 'upload_too_large', 'upload_not_found', 'unsupported_media', 'corrupt_media', 'version_conflict', 'frame_offline', 'command_timeout', 'owner_cannot_leave', 'rate_limited', 'app_update_required', 'server_error'];
		for (const c of codes) expect(en).toHaveProperty([`problem.${c}`]);
	});

	it('interpolates and leaves unknown placeholders alone', () => {
		expect(interpolate('Hi {name}, {n}', { name: 'Oma' })).toBe('Hi Oma, {n}');
	});
});
