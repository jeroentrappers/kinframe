// Remote help (docs/spec/remote-help.md): pure helpers for the frame banner and the operator's page.
// Unit-tested in remoteHelp.test.ts.

import type { Schemas } from './api/client';
import type { MessageKey } from './i18n/en';

export type RemoteHelpSession = Schemas['RemoteHelpSession'];
export type RemoteHelpSummary = Schemas['FrameRemoteHelpSummary'];

/** Session lengths the operator can pick; 8 hours is preselected (decision C). */
export const REMOTE_HELP_HOURS = [1, 8, 24] as const;
export const REMOTE_HELP_DEFAULT_HOURS = 8;

/** The frame banner after a `remote_help.updated` event: the session in short, or null once it ended. */
export function summaryFromSession(s: RemoteHelpSession): RemoteHelpSummary | null {
	return s.state === 'ended' ? null : { ends_at: s.ends_at, reason: s.reason };
}

/** Whether a summary is still running at `now` (the banner hides itself when the end time passed). */
export function isRunning(s: RemoteHelpSummary | null | undefined, now: Date = new Date()): s is RemoteHelpSummary {
	if (!s) return false;
	const end = new Date(s.ends_at).getTime();
	return Number.isNaN(end) || end > now.getTime();
}

/** "18:00" in the viewer's language and time zone. */
export function formatClock(iso: string, locale = 'en'): string {
	const d = new Date(iso);
	if (Number.isNaN(d.getTime())) return '';
	return new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit' }).format(d);
}

/** Puts an updated session into the operator's lists: active first (newest first), then ended ones by start. */
export function upsertSession(list: RemoteHelpSession[], s: RemoteHelpSession): RemoteHelpSession[] {
	const next = list.filter((x) => x.id !== s.id);
	next.push(s);
	return next.sort((a, b) => {
		const aa = a.state === 'ended' ? 1 : 0;
		const bb = b.state === 'ended' ? 1 : 0;
		if (aa !== bb) return aa - bb;
		return b.started_at.localeCompare(a.started_at);
	});
}

/** How a session ended, as a message key (owner/admin and operator names come from `ended_by_name`). */
export function endedKey(s: RemoteHelpSession): { key: MessageKey; name?: string } | null {
	switch (s.ended_by) {
		case 'operator':
			return s.ended_by_name ? { key: 'remoteHelp.endedOwner', name: s.ended_by_name } : { key: 'remoteHelp.endedOperator' };
		case 'owner':
			return { key: 'remoteHelp.endedOwner', name: s.ended_by_name ?? '–' };
		case 'frame':
			return { key: 'remoteHelp.endedFrame' };
		case 'expired':
			return { key: 'remoteHelp.endedExpired' };
		case 'unpaired':
			return { key: 'remoteHelp.endedUnpaired' };
		case 'failed':
			return { key: 'remoteHelp.endedFailed' };
		default:
			return null;
	}
}
