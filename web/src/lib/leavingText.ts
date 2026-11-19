// Localized lines for leaving and other servers (README §5.21, §5.22).
import { i18n, t, tn } from './i18n/index.svelte';
import { resultParts, type TakeAwayResult } from './leaving';
import { frameLabel } from './remote';

/** "19 December" (the year only when it isn't this year). */
export function dayMonth(iso: string | null | undefined, now = new Date()): string {
	if (!iso) return '';
	const d = new Date(iso);
	if (Number.isNaN(d.getTime())) return '';
	const opts: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'long', ...(d.getFullYear() !== now.getFullYear() ? { year: 'numeric' } : {}) };
	return new Intl.DateTimeFormat(i18n.tag, opts).format(d);
}

/** The toast after leaving with a choice or taking photos away. */
export function resultMessage(r: TakeAwayResult | null, names: (ids: string[]) => string[], leftName?: string): string {
	const parts = resultParts(r, names);
	const lines: string[] = [];
	if (leftName) lines.push(t('leaving.resultLeft', { name: leftName }));
	for (const p of parts) {
		if (p.key === 'taken') lines.push(t('leaving.resultTaken', { photos: tn('history.photos', p.n ?? 0), date: dayMonth(p.until) }));
		if (p.key === 'remote') lines.push(t('leaving.resultRemote', { n: p.n ?? 0 }));
		if (p.key === 'comments') lines.push(t('leaving.resultComments', { n: p.n ?? 0 }));
		if (p.key === 'frames') lines.push(t('leaving.resultFrames', { frames: new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format(p.names ?? []) }));
	}
	if (!leftName && parts.length === 0) lines.push(t('leaving.resultNothing'));
	return lines.join(' ');
}

/** A frame's name with its server when it is on another server: "Oma's frame (kinframe.appmire.be)". */
export function remoteFrameName(name: string, server: string | null | undefined): string {
	return frameLabel(name, server, (frame, s) => t('remote.frameOnServer', { frame, server: s }));
}
