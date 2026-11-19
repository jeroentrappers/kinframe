// Encouragement to share (docs/spec/family-social.md §9, README §5.20): freshness rings, weeks, milestones. Pure:
// unit-tested in engagement.test.ts.

import type { FeedPrompt, Freshness, MilestoneKey, PhotoRequest } from './api/client';

/** The ring's words: "Fresh", "4 days", "2 weeks quiet", "No photos yet". */
export type FreshnessWords =
	| { key: 'fresh' }
	| { key: 'never' }
	| { key: 'days'; count: number }
	| { key: 'weeksQuiet'; count: number }
	| { key: 'daysQuiet'; count: number };

export function freshnessWords(f: Freshness | null | undefined): FreshnessWords | null {
	if (!f) return null;
	if (f.state === 'never') return { key: 'never' };
	if (f.state === 'fresh') return { key: 'fresh' };
	const d = f.days_quiet ?? 0;
	if (f.state === 'quiet') return d >= 14 ? { key: 'weeksQuiet', count: Math.floor(d / 7) } : { key: 'daysQuiet', count: d };
	return d <= 0 ? { key: 'fresh' } : { key: 'days', count: d };
}

/**
 * The ring around a frame or family: how much of the circle is drawn (`level`, 1 = shared today, 0 after 14 days),
 * and whether it is pale (quiet).
 */
export function ringStyle(f: Freshness | null | undefined): { fraction: number; tone: 'warm' | 'mild' | 'pale' | 'none' } {
	if (!f || f.state === 'never') return { fraction: 0, tone: 'none' };
	const fraction = Math.min(1, Math.max(0, f.level));
	const tone = f.state === 'fresh' ? 'warm' : f.state === 'quiet' ? 'pale' : 'mild';
	return { fraction: Math.max(fraction, 0.06), tone };
}

/** An open request, or one answered in the last day: the "Oma would like new photos" badge. */
export function requestOpen(r: PhotoRequest | null | undefined, now = Date.now()): boolean {
	return !!r && r.state === 'open' && Date.parse(r.expires_at) > now;
}

/** The families to preselect for a frame you're not on: those reaching it that you can post to, in your order. */
export function familiesToPreselect(reaching: readonly string[], postable: readonly string[]): string[] {
	return postable.filter((id) => reaching.includes(id));
}

/** Where "Send photos" for a frame's request goes: the frame itself, or (not on it) the send page finds its families. */
export function requestSendUrl(frameId: string, requestId?: string | null): string {
	return `/send?frame=${frameId}${requestId ? `&request=${requestId}` : ''}`;
}

/** The Monday (YYYY-MM-DD, local time) of the week `d` is in. */
export function weekOf(d: Date): string {
	const x = new Date(d.getFullYear(), d.getMonth(), d.getDate());
	const dow = (x.getDay() + 6) % 7; // Monday 0
	x.setDate(x.getDate() - dow);
	return isoDate(x);
}

/** The Monday of the week before the one `d` is in (the weekly summary is about the week just ended). */
export function lastWeekOf(d: Date): string {
	const x = new Date(d.getFullYear(), d.getMonth(), d.getDate() - 7);
	return weekOf(x);
}

export function isoDate(d: Date): string {
	return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Mondays from this week on, for the admin's prompt planner. */
export function nextMondays(from: Date, n: number): string[] {
	const first = weekOf(from);
	const [y, m, d] = first.split('-').map(Number) as [number, number, number];
	return Array.from({ length: n }, (_, i) => isoDate(new Date(y, m - 1, d + 7 * i)));
}

/** The week's prompt of a family (from `listFeedPrompts`), if there is one and it wasn't skipped. */
export function currentPrompt(prompts: FeedPrompt[]): FeedPrompt | null {
	return prompts.find((p) => p.status === 'current' && p.title.trim() !== '') ?? null;
}

export const MILESTONE_EMOJI: Record<MilestoneKey, string> = {
	first_photo: '🌱',
	photos_10: '📷',
	photos_100: '🖼️',
	photos_1000: '🏛️',
	first_video: '🎬',
	year_of_sharing: '🎂',
	loves_100: '💛'
};

export const MILESTONE_ORDER: MilestoneKey[] = ['first_photo', 'photos_10', 'photos_100', 'photos_1000', 'first_video', 'year_of_sharing', 'loves_100'];

/** "2026-10" → a Date in that month (for Intl month names). */
export function monthDate(month: string): Date {
	const [y, m] = month.split('-').map(Number) as [number, number];
	return new Date(y, (m || 1) - 1, 15);
}
