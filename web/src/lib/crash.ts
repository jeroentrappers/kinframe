// Crash reporting (docs/spec/crash-reporting.md): unhandled errors go to the operator's GlitchTip when discovery
// publishes `sentry_dsn` and the user's "Send crash reports" setting (this device, default on) is on. The SDK is
// loaded only then. Reports carry no personal data (§3): no user, IP, cookies, headers but the user agent, query
// strings or bodies; ids and tokens in paths become "*". scrubEvent enforces that in beforeSend.

import type { Breadcrumb, ErrorEvent } from '@sentry/browser';

export const CRASH_REPORTS_KEY = 'kinframe.crashReports';

/** `kinframe-web@<version>[+<revision>]`, set at build time (vite.config.ts); source maps are uploaded under it. */
export const RELEASE = __KINFRAME_WEB_RELEASE__;

/** The user's choice on this device; on unless they turned it off. */
export function crashReportsEnabled(): boolean {
	try {
		return localStorage.getItem(CRASH_REPORTS_KEY) !== 'off';
	} catch {
		return true;
	}
}

/** Takes effect at the next start (spec §4): a running SDK is not stopped. */
export function setCrashReportsEnabled(on: boolean) {
	try {
		if (on) localStorage.removeItem(CRASH_REPORTS_KEY);
		else localStorage.setItem(CRASH_REPORTS_KEY, 'off');
	} catch {
		/* storage blocked: the setting can't be kept */
	}
}

/** Segments after these carry a token, code or id even when it looks like a word (/i/{token}, /f/{code}). */
const PARAM_PARENTS = new Set([
	'i', 'f', 'fi', 'tv', 'station', 'chats', 'feeds', 'frames', 'history', 'people', 'posts',
	'invites', 'feed-invites', 'avatars', 'telegram-codes'
]);
/** Everything after these is a key (/v1/media/signed/{key...}). */
const KEY_PARENTS = new Set(['signed']);
const ROUTE_WORD = /^(v[0-9]{1,2}|\.?[a-z][a-z_:-]{0,39})$/;

/** Keeps a path's route words, replaces ids, tokens and file names with "*": /i/Xy7…/ → /i/*. */
export function redactPath(path: string): string {
	let rest = false;
	return path
		.split('/')
		.map((s, i, segs) => {
			if (s === '') return s;
			const parent = segs[i - 1] ?? '';
			rest ||= KEY_PARENTS.has(parent);
			return !rest && ROUTE_WORD.test(s) && !PARAM_PARENTS.has(parent) ? s : '*';
		})
		.join('/');
}

/** Origin and redacted path of a URL (absolute or relative); no user info, query or fragment. */
export function redactUrl(raw: string): string {
	try {
		const u = new URL(raw, 'http://relative.invalid');
		const path = redactPath(u.pathname);
		return u.origin === 'http://relative.invalid' ? path : u.origin + path;
	} catch {
		return '';
	}
}

/** URLs inside free text (error messages): query and fragment dropped, path redacted. */
export function redactUrlsIn(text: string): string {
	return text.replace(/\bhttps?:\/\/[^\s"'<>)]+/g, (m) => redactUrl(m));
}

/** The beforeSend hook: whatever an integration attached, the event leaves without personal data. */
export function scrubEvent(event: ErrorEvent): ErrorEvent {
	delete event.user;
	delete event.server_name;
	if (event.request) {
		const ua = event.request.headers?.['User-Agent'];
		event.request = { url: event.request.url ? redactUrl(event.request.url) : undefined, headers: ua ? { 'User-Agent': ua } : undefined };
	}
	if (event.message) event.message = redactUrlsIn(event.message);
	for (const ex of event.exception?.values ?? []) {
		if (ex.value) ex.value = redactUrlsIn(ex.value);
	}
	event.breadcrumbs = event.breadcrumbs?.map(scrubBreadcrumb).filter((b): b is Breadcrumb => b !== null);
	delete event.extra;
	return event;
}

/** Navigation keeps redacted from/to; fetch and XHR keep method, redacted URL and status; everything else only
 *  its category and level. */
export function scrubBreadcrumb(b: Breadcrumb): Breadcrumb | null {
	if (b.category === 'console' || b.category?.startsWith('ui.')) return null;
	const d = b.data ?? {};
	const data: Record<string, unknown> = {};
	if (b.category === 'navigation') {
		if (typeof d.from === 'string') data.from = redactUrl(d.from);
		if (typeof d.to === 'string') data.to = redactUrl(d.to);
	} else if (b.category === 'fetch' || b.category === 'xhr') {
		if (typeof d.method === 'string') data.method = d.method;
		if (typeof d.url === 'string') data.url = redactUrl(d.url);
		if (typeof d.status_code === 'number') data.status_code = d.status_code;
	}
	return { type: b.type, category: b.category, level: b.level, timestamp: b.timestamp, message: b.message ? redactUrlsIn(b.message) : undefined, data };
}

let started = false;

/** Starts the SDK once, after discovery loaded: only with a DSN and the user's setting on. */
export async function startCrashReporting(dsn: string | null | undefined): Promise<boolean> {
	if (started || !dsn || !crashReportsEnabled()) return false;
	started = true;
	try {
		const Sentry = await import('@sentry/browser');
		Sentry.init({
			dsn,
			release: RELEASE,
			environment: 'production',
			// SDK 11's replacement for sendDefaultPii: false, and stricter (scrubEvent still has the last word).
			dataCollection: {
				userInfo: false,
				cookies: false,
				httpHeaders: { request: { allow: ['User-Agent'] }, response: false },
				httpBodies: [],
				urlQueryParams: false,
				stackFrameVariables: false
			},
			tracesSampleRate: 0,
			// No session replay, tracing, feedback, console capture or session pings; navigation and request
			// breadcrumbs only.
			defaultIntegrations: false,
			integrations: [
				Sentry.eventFiltersIntegration(),
				Sentry.functionToStringIntegration(),
				Sentry.browserApiErrorsIntegration(),
				Sentry.breadcrumbsIntegration({ dom: false, fetch: true, xhr: true, history: true, sentry: false }),
				Sentry.globalHandlersIntegration(),
				Sentry.linkedErrorsIntegration(),
				Sentry.dedupeIntegration(),
				Sentry.httpContextIntegration()
			],
			maxBreadcrumbs: 30,
			// Turning the setting off stops sending right away; turning it on applies at the next start.
			beforeSend: (event) => (crashReportsEnabled() ? scrubEvent(event) : null),
			beforeBreadcrumb: (b) => scrubBreadcrumb(b)
		});
		reporter = Sentry.captureException;
		return true;
	} catch {
		started = false;
		return false;
	}
}

let reporter: ((e: unknown) => unknown) | null = null;

/** Reports an error the app caught and shows itself (SvelteKit's handleError); a no-op while reporting is off. */
export function reportError(error: unknown) {
	reporter?.(error);
}
