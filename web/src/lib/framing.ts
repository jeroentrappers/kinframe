// Framing: a zoom and a centre per screen orientation (docs/spec/framing.md). The one geometry
// every renderer follows (§3), which view a screen uses (§3.1), and the editor's helpers (§4).
// Pure, unit-tested in framing.test.ts.
import type { Focal } from './logic';

export type Orientation = 'landscape' | 'portrait';

/** openapi `FramingView`: the centre of the visible part (0..1 of the master) and the zoom over the cover crop. */
export interface FramingView {
	x: number;
	y: number;
	zoom: number;
}

/** openapi `Framing`: a view per orientation, either may be missing. */
export interface Framing {
	landscape?: FramingView;
	portrait?: FramingView;
}

export const MIN_ZOOM = 1;
export const MAX_ZOOM = 4;
/** The editor's preview shapes (§4). */
export const LANDSCAPE_ASPECT = 16 / 10;
export const PORTRAIT_ASPECT = 10 / 16;
/** The master rendition's long edge (§3.3) and the soft-warning threshold (§4). */
export const MASTER_LONG_EDGE = 2560;
export const SHARP_MIN_PX = 960;

export const ORIENTATIONS: Orientation[] = ['landscape', 'portrait'];

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
const round3 = (v: number) => Math.round(v * 1000) / 1000;

/** §2: landscape when width ≥ height (a square screen is landscape). */
export function orientationOf(width: number, height: number): Orientation {
	return width >= height ? 'landscape' : 'portrait';
}

/** The box shape (width / height) of an orientation for a screen of `screenAspect` either way round. */
export function boxAspectOf(o: Orientation, screenAspect = LANDSCAPE_ASPECT): number {
	const a = screenAspect > 0 ? screenAspect : LANDSCAPE_ASPECT;
	return o === 'landscape' ? Math.max(a, 1 / a) : Math.min(a, 1 / a);
}

export interface CropRect {
	left: number;
	top: number;
	width: number;
	height: number;
}

/**
 * §3: the part of a `w×h` photo a `W×H` screen shows for `view`, in photo pixels. With zoom 1 and the
 * focal point as centre this is the plain cover crop (FocalCrop.fillSource).
 */
export function cropRect(w: number, h: number, W: number, H: number, view: FramingView): CropRect {
	const zoom = clamp(view.zoom || 1, MIN_ZOOM, MAX_ZOOM);
	const s = Math.max(W / w, H / h);
	const cw = Math.min(w, W / (s * zoom));
	const ch = Math.min(h, H / (s * zoom));
	const left = clamp(view.x * w - cw / 2, 0, w - cw);
	const top = clamp(view.y * h - ch / 2, 0, h - ch);
	return { left, top, width: cw, height: ch };
}

/**
 * Inline style for an absolutely positioned `<img>` inside an `overflow: hidden` box of `boxAspect`
 * that draws the §3 crop to fill the box (percentages of the box, so any box size works).
 */
export function cropStyle(w: number, h: number, boxAspect: number, view: FramingView): string {
	const r = cropRect(w, h, boxAspect, 1, view);
	const pct = (v: number) => `${(v * 100).toFixed(3)}%`;
	return `width:${pct(w / r.width)};height:${pct(h / r.height)};left:${pct(-r.left / r.width)};top:${pct(-r.top / r.height)}`;
}

/**
 * A view as the editor keeps it: zoom in 1..4, and the centre moved so the window stays on the photo
 * (the clamp of §3, for a box of `boxAspect`), rounded to 3 decimals like the server.
 */
export function clampView(view: FramingView, imageAspect: number, boxAspect: number): FramingView {
	const zoom = round3(clamp(view.zoom || 1, MIN_ZOOM, MAX_ZOOM));
	const ia = imageAspect > 0 ? imageAspect : 1;
	const r = cropRect(ia, 1, boxAspect, 1, { x: clamp(view.x, 0, 1), y: clamp(view.y, 0, 1), zoom });
	return { x: round3((r.left + r.width / 2) / ia), y: round3(r.top + r.height / 2), zoom };
}

/** The visible part as fractions of the photo's width and height (for moving by a share of it). */
export function visibleFraction(imageAspect: number, boxAspect: number, zoom: number): { w: number; h: number } {
	const ia = imageAspect > 0 ? imageAspect : 1;
	const r = cropRect(ia, 1, boxAspect, 1, { x: 0.5, y: 0.5, zoom });
	return { w: r.width / ia, h: r.height };
}

/** Moves a view by fractions of its visible part (§4 accessibility: 10% per step), clamped. */
export function moveView(view: FramingView, dx: number, dy: number, imageAspect: number, boxAspect: number): FramingView {
	const v = visibleFraction(imageAspect, boxAspect, view.zoom);
	return clampView({ ...view, x: view.x + dx * v.w, y: view.y + dy * v.h }, imageAspect, boxAspect);
}

/** Sets the zoom keeping the centre (then clamped). */
export function zoomView(view: FramingView, zoom: number, imageAspect: number, boxAspect: number): FramingView {
	return clampView({ ...view, zoom }, imageAspect, boxAspect);
}

/** What a screen shows, and whether the person chose it (§3.1 steps 1 and 3; §3.2, §3.4). */
export interface ChosenView {
	view: FramingView;
	chosen: boolean;
}

export type FramingViews = Record<Orientation, ChosenView>;

export interface ViewSource {
	framingOverride?: Framing | null;
	focalOverride?: Focal | null;
	framing?: Framing | null;
	focal?: Focal | null;
}

/** §3.1: for orientation `o`, the first that exists. */
export function pickView(src: ViewSource, o: Orientation): ChosenView {
	const over = src.framingOverride?.[o];
	if (over) return { view: { x: over.x, y: over.y, zoom: over.zoom }, chosen: true };
	if (src.focalOverride) return { view: { x: src.focalOverride.x, y: src.focalOverride.y, zoom: 1 }, chosen: false };
	const own = src.framing?.[o];
	if (own) return { view: { x: own.x, y: own.y, zoom: own.zoom }, chosen: true };
	if (src.focal) return { view: { x: src.focal.x, y: src.focal.y, zoom: 1 }, chosen: false };
	return { view: { x: 0.5, y: 0.5, zoom: 1 }, chosen: false };
}

export function pickViews(src: ViewSource): FramingViews {
	return { landscape: pickView(src, 'landscape'), portrait: pickView(src, 'portrait') };
}

/** §3.5: a photo framed with zoom > 1 for this orientation is not paired (shown big). */
export function pairable(v: ChosenView): boolean {
	return !(v.chosen && v.view.zoom > 1);
}

/** §3.5: a collage tile uses the view's centre as its focal point and ignores the zoom. */
export function tileFocal(v: ChosenView): Focal {
	return { x: v.view.x, y: v.view.y };
}

/** §4 sending: the landscape view's centre, else the portrait one's, else the tapped point. */
export function sendFocal(framing: Framing | null | undefined, tapped: Focal): Focal {
	const v = framing?.landscape ?? framing?.portrait;
	return v ? { x: v.x, y: v.y } : { x: tapped.x, y: tapped.y };
}

/** A framing with no views is no framing (null), so the send leaves it out. */
export function framingOrNull(f: Framing | null | undefined): Framing | null {
	if (!f) return null;
	const out: Framing = {};
	if (f.landscape) out.landscape = f.landscape;
	if (f.portrait) out.portrait = f.portrait;
	return out.landscape || out.portrait ? out : null;
}

/**
 * §4: tapping the focal point moves every existing view's centre there and keeps its zoom (clamped
 * per orientation). Without views nothing changes (it is just the focal point).
 */
export function recentre(framing: Framing | null, focal: Focal, imageAspect: number, screenAspect = LANDSCAPE_ASPECT): Framing | null {
	if (!framing) return null;
	const out: Framing = {};
	for (const o of ORIENTATIONS) {
		const v = framing[o];
		if (v) out[o] = clampView({ x: focal.x, y: focal.y, zoom: v.zoom }, imageAspect, boxAspectOf(o, screenAspect));
	}
	return framingOrNull(out);
}

/**
 * §4 soft warning: fewer than 960 photo pixels of the master (long edge ≤ 2560) across the screen's
 * long side. False when the photo's size is unknown.
 */
export function looksSoft(view: FramingView, width: number | null, height: number | null, boxAspect: number): boolean {
	if (!width || !height) return false;
	const k = Math.min(1, MASTER_LONG_EDGE / Math.max(width, height));
	const r = cropRect(width * k, height * k, boxAspect, 1, view);
	const across = boxAspect >= 1 ? r.width : r.height;
	return across < SHARP_MIN_PX;
}
