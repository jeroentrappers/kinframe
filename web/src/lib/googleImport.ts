// "Add from Google Photos" (docs/spec/photo-sources.md §7, README §5.14): the web side of consent, picker and
// return. Pure helpers; GoogleImportDialog drives the flow.
import type { GoogleImport, GoogleImportStatus } from './api/client';
import { t, tn } from './i18n/index.svelte';

/** The SPA page Google's consent comes back to (`<web>/google-import/return`). */
export const RETURN_PATH = '/google-import/return';
/** The `postMessage` type the return page sends to the page that opened the popup. */
export const MESSAGE_TYPE = 'kinframe.google-import';
/** The popup's window name: one Google window at a time. */
export const POPUP_NAME = 'kinframe-google';
/** sessionStorage key: where to come back to when consent ran in this tab (popup blocked). */
export const BACK_KEY = 'kinframe.googleImport.back';
/**
 * BroadcastChannel between the return page and the page that started the import. Google's sign-in pages isolate
 * the popup (Cross-Origin-Opener-Policy), so `window.opener` is often gone when it comes back; same-origin channels
 * still work.
 */
export const CHANNEL = 'kinframe.google-import';
/** The opener's answer on the channel: where the popup goes next (Google's picker). */
export const PICKER_TYPE = 'kinframe.google-import.picker';

const STATUSES: GoogleImportStatus[] = ['needs_auth', 'picking', 'importing', 'done', 'failed', 'cancelled', 'expired'];
const ID = /^[0-9a-fA-F-]{8,64}$/;

export function returnUri(origin: string): string {
	return `${origin.replace(/\/+$/, '')}${RETURN_PATH}`;
}

export function isImportStatus(s: unknown): s is GoogleImportStatus {
	return typeof s === 'string' && (STATUSES as string[]).includes(s);
}

/** `?import_id=…&status=…` as the OAuth callback adds it to `return_uri`; null when anything is off. */
export function parseReturn(params: URLSearchParams): { importId: string; status: GoogleImportStatus } | null {
	const importId = params.get('import_id') ?? '';
	const status = params.get('status');
	if (!ID.test(importId) || !isImportStatus(status)) return null;
	return { importId, status };
}

export interface ImportMessage {
	type: typeof MESSAGE_TYPE;
	import_id: string;
	status: GoogleImportStatus;
}

export function importMessage(importId: string, status: GoogleImportStatus): ImportMessage {
	return { type: MESSAGE_TYPE, import_id: importId, status };
}

/** A message from our return page (same origin, our shape), or null. */
export function readImportMessage(ev: { origin: string; data: unknown }, origin: string): ImportMessage | null {
	if (ev.origin !== origin) return null;
	const d = ev.data as Partial<ImportMessage> | null;
	if (!d || d.type !== MESSAGE_TYPE || typeof d.import_id !== 'string' || !ID.test(d.import_id) || !isImportStatus(d.status)) return null;
	return { type: MESSAGE_TYPE, import_id: d.import_id, status: d.status };
}

export interface PickerMessage {
	type: typeof PICKER_TYPE;
	import_id: string;
	/** Google's picker when the import is waiting for a pick; null acknowledges the message without one. */
	picker_uri: string | null;
}

/**
 * The opener's answer for this import: the picker URL to go on to, `null` when the opener got the message but there
 * is no picker (consent failed: just close), `undefined` when the message is not an answer for us.
 */
export function readPickerMessage(data: unknown, importId: string): string | null | undefined {
	const d = data as Partial<PickerMessage> | null;
	if (!d || d.type !== PICKER_TYPE || d.import_id !== importId) return undefined;
	if (d.picker_uri == null) return null;
	return isGoogleUrl(d.picker_uri) ? pickerUrl(d.picker_uri) : null;
}

/** Where a page without an opener goes back to after consent (the page that started it, else My photo sources). */
export function backUrl(stored: string | null, importId: string): string {
	const path = stored && stored.startsWith('/') && !stored.startsWith('//') ? stored : '/photo-sources';
	const u = new URL(path, 'https://x.invalid');
	u.searchParams.set('google_import', importId);
	return `${u.pathname}${u.search}`;
}

/** Web clients append `/autoclose`: Google closes the window after Done (README §5.14). */
export function pickerUrl(uri: string): string {
	const u = uri.replace(/\/+$/, '');
	return u.endsWith('/autoclose') ? u : `${u}/autoclose`;
}

/** Only Google's own pages are opened (never a URL from anywhere else). */
export function isGoogleUrl(url: string | null | undefined): boolean {
	if (!url) return false;
	try {
		const u = new URL(url);
		return u.protocol === 'https:' && /(^|\.)(google\.com|googleapis\.com)$/.test(u.hostname);
	} catch {
		return false;
	}
}

export function isFinal(status: GoogleImportStatus): boolean {
	return status === 'done' || status === 'failed' || status === 'cancelled' || status === 'expired';
}

export function canCancel(status: GoogleImportStatus): boolean {
	return status === 'needs_auth' || status === 'picking' || status === 'importing';
}

/** 0…1 while importing, null otherwise. */
export function importProgress(imp: Pick<GoogleImport, 'status' | 'progress'>): number | null {
	if (imp.status !== 'importing') return null;
	const { picked, imported, duplicates = 0, skipped = 0, failed = 0 } = imp.progress;
	if (!picked) return 0;
	return Math.min(1, (imported + duplicates + skipped + failed) / picked);
}

/** The headline for an import's state. */
export function importHeadline(imp: Pick<GoogleImport, 'status' | 'progress' | 'error_code'>, locale = 'en'): string {
	const n = (x: number) => new Intl.NumberFormat(locale).format(x);
	const p = imp.progress;
	switch (imp.status) {
		case 'needs_auth':
			return t('gimport.st.needsAuth');
		case 'picking':
			return t('gimport.st.picking');
		case 'importing':
			return p.picked ? t('gimport.st.importing', { done: n(p.imported + (p.duplicates ?? 0) + (p.skipped ?? 0) + (p.failed ?? 0)), total: n(p.picked) }) : t('gimport.st.importingStart');
		case 'done':
			return tn('gimport.added', p.imported);
		case 'failed':
			return importErrorText(imp.error_code);
		case 'expired':
			return t('gimport.st.expired');
		case 'cancelled':
			return t('gimport.st.cancelled');
	}
}

/** The extra lines of a finished import: "2 were already there", "1 couldn't be copied". */
export function importDetails(imp: Pick<GoogleImport, 'status' | 'progress'>): string[] {
	if (imp.status !== 'done' && imp.status !== 'cancelled') return [];
	const out: string[] = [];
	const p = imp.progress;
	if (imp.status === 'cancelled' && p.imported) out.push(tn('gimport.added', p.imported));
	if (p.duplicates) out.push(tn('gimport.duplicates', p.duplicates));
	if (p.skipped) out.push(tn('gimport.skipped', p.skipped));
	if (p.failed) out.push(tn('gimport.failed', p.failed));
	return out;
}

export function importErrorText(code: GoogleImport['error_code']): string {
	switch (code) {
		case 'consent_denied':
			return t('gimport.err.consent_denied');
		case 'auth_failed':
			return t('gimport.err.auth_failed');
		case 'picker_expired':
			return t('gimport.st.expired');
		case 'google_unavailable':
			return t('gimport.err.google_unavailable');
		case 'target_gone':
			return t('gimport.err.target_gone');
		case 'download_failed':
			return t('gimport.err.download_failed');
		default:
			return t('gimport.err.server_error');
	}
}

/** Replace or add an import by id (live updates, polling). */
export function upsertImport(list: GoogleImport[], imp: GoogleImport): GoogleImport[] {
	const i = list.findIndex((x) => x.id === imp.id);
	if (i < 0) return [imp, ...list];
	const out = [...list];
	out[i] = imp;
	return out;
}

/** Imports a dialog on this page is following (the layout only toasts about the others). */
export const watching = new Set<string>();

/** The import a page should resume from `?google_import=<id>` (after a same-tab consent), else null. */
export function resumeId(params: URLSearchParams): string | null {
	const id = params.get('google_import');
	return id && ID.test(id) ? id : null;
}
