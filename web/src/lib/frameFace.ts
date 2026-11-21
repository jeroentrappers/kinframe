// What a frame card shows (simple-app.md §3.3), from the server's frame card fields (README §5.5, "Frame cards"):
// the photo on screen or the last placed one, whether it is showing now, and today's reactions. Pure helpers,
// unit-tested in frameFace.test.ts.

import type { FrameSummary } from './api/client';

type CardFrame = Pick<FrameSummary, 'online' | 'now_showing' | 'reactions_today'>;

/**
 * The card's photo URL: the thumbnail for cards and faces, the display rendition for the large card on the frame
 * screen (falling back to the other one). Null without `now_showing` (nothing placed yet, no `see_library`, or a
 * frame that only shows your family): the card keeps its placeholder.
 */
export function framePhotoUrl(frame: Pick<FrameSummary, 'now_showing'> | null | undefined, large = false): string | null {
	const n = frame?.now_showing;
	if (!n) return null;
	return (large ? (n.display_url ?? n.thumb_url) : (n.thumb_url ?? n.display_url)) ?? null;
}

/** "Online · showing now": the frame is connected and reported this slide on screen. */
export function showingNow(frame: Pick<CardFrame, 'online' | 'now_showing'>): boolean {
	return frame.online === true && frame.now_showing?.source === 'screen';
}

/** The number of photos the frame reacted to today ("Oma loved 3 photos today"), or null to hide the line. */
export function reactedToday(frame: Pick<CardFrame, 'reactions_today'>): number | null {
	const r = frame.reactions_today;
	if (!r || r.count <= 0 || r.item_count <= 0) return null;
	return r.item_count;
}

/**
 * An online frame that was showing a slide went offline: its card should switch to the last placed photo, which only
 * the server knows. True when the frame list needs a fresh look.
 */
export function needsRefreshOnStatus(frame: Pick<CardFrame, 'online' | 'now_showing'> | undefined, online: boolean): boolean {
	if (!frame || frame.online === online) return false;
	return online || frame.now_showing?.source === 'screen';
}
