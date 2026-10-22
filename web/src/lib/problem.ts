import { ApiError } from './api/client';
import { t, type MessageKey } from './i18n/index.svelte';

/**
 * A friendly, localized sentence for any error. We branch on the stable ProblemCode and only
 * fall back to the server's (already localized) title/detail for codes we don't know.
 */
export function problemMessage(e: unknown): string {
	if (e instanceof ApiError) {
		const key = `problem.${e.code}` as MessageKey;
		const msg = t(key);
		if (msg !== key) return msg;
		return e.detail ?? e.title ?? t('problem.server_error');
	}
	if (e instanceof Error && e.message) return e.message;
	return t('problem.server_error');
}

export function isCode(e: unknown, ...codes: string[]): e is ApiError {
	return e instanceof ApiError && codes.includes(e.code);
}

const FAILURES = ['unsupported_media', 'corrupt_media', 'video_too_long'] as const;

/** Why an item failed processing (Item.failure_code), localized. */
export function failureMessage(code: string | null | undefined): string {
	const known = FAILURES.find((f) => f === code);
	return t(known ? `failure.${known}` : 'failure.unknown');
}
