// What a full-screen TV stage shows (the mirror of a frame, a browser frame), and the small
// pure helpers its renderer uses. Kept apart from the component so they are unit-tested.
import type { KbKey } from './kenburns';
import { coverPosition } from './logic';

export interface StageTile {
	id: string;
	kind: 'photo' | 'video' | 'live';
	src: string;
	poster?: string | null;
	width: number;
	height: number;
	fit: 'fill' | 'fit';
	focal: { x: number; y: number } | null;
	rotation: number;
}

export interface StageVideo {
	/** Position at `at` (local clock, ms). */
	positionMs: number;
	at: number;
	playing: boolean;
}

export interface StageSlide {
	/** Changes with every new slide: a new layer and a new transition. */
	key: string;
	tiles: StageTile[];
	transition: 'crossfade' | 'slide' | 'cut';
	transitionMs: number;
	direction: 1 | -1;
	/** When the transition started, local clock (ms). */
	startedAt: number;
	kenBurns: KbKey[] | null;
	holdMs: number;
	/** Set to follow someone else's playback (mirror); absent = play from the start. */
	video?: StageVideo | null;
}

/** Remaining part of a transition that started `elapsedMs` ago (a TV tuning in mid-fade). */
export function remainingTransition(totalMs: number, elapsedMs: number): number {
	if (!(totalMs > 0)) return 0;
	return Math.max(0, Math.min(totalMs, totalMs - Math.max(0, elapsedMs)));
}

/** Where a followed video should be now, in ms. */
export function videoPositionNow(v: StageVideo, now: number): number {
	return Math.max(0, v.positionMs + (v.playing ? now - v.at : 0));
}

/** CSS object-position for a tile filling a box of `boxAspect` (centred on its focal point). */
export function tileObjectPosition(t: Pick<StageTile, 'width' | 'height' | 'focal' | 'rotation'>, boxAspect: number): string {
	if (!t.focal || !t.width || !t.height || t.rotation % 180 !== 0) return '50% 50%';
	const p = coverPosition(t.focal, t.width / t.height, boxAspect);
	return `${p.x.toFixed(2)}% ${p.y.toFixed(2)}%`;
}

/** Grid areas for a collage of n tiles (the frame's own layout differs in detail). */
export function collageClass(n: number): string {
	return n <= 1 ? 'one' : n === 2 ? 'two' : n === 3 ? 'three' : n === 4 ? 'four' : 'many';
}

/** Server clock offset: add to a server timestamp to get local time. */
export function clockSkew(serverTime: string, localNow: number): number {
	const t = Date.parse(serverTime);
	return Number.isFinite(t) ? localNow - t : 0;
}
