// Ken Burns motion (product spec 4.4), matching the Android frame: a closed loop of
// framings (wide, close, wide, …) on a Catmull–Rom spline, deterministic per placement and
// focal point, so a TV mirroring the frame moves the same way. Time is in segments of
// SEGMENT_MS from the moment the slide appears.

export interface KbKey {
	scale: number;
	x: number;
	y: number;
}

export const WIDE_MIN = 1.02;
export const WIDE_MAX = 1.06;
export const CLOSE_MIN = 1.24;
export const MAX_ZOOM = 1.32;
export const SEGMENT_MS = 9000;
const KEYS = 6;

/** Java's String.hashCode, as a signed 32-bit int. */
export function javaHash(s: string): number {
	let h = 0;
	for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
	return h;
}

const f32 = Math.fround;
const MASK64 = (1n << 64n) - 1n;

/** The frame's random sequence for a placement (java.util.Random-style seed, 64-bit LCG). */
function sequence(id: string): () => number {
	let h = BigInt.asUintN(64, BigInt(javaHash(id))) ^ 0x5deece66dn;
	return () => {
		h = (h * 6364136223846793005n + 1442695040888963407n) & MASK64;
		return f32(Number((h >> 40n) & 0xffffffn) / 16_777_216);
	};
}

export function kenBurnsKeys(id: string, focalX: number | null, focalY: number | null): KbKey[] {
	const rnd = sequence(id);
	const keys: KbKey[] = [];
	for (let k = 0; k < KEYS; k++) {
		const close = k % 2 === 1;
		const scale = close ? f32(CLOSE_MIN + rnd() * (MAX_ZOOM - CLOSE_MIN)) : f32(WIDE_MIN + rnd() * (WIDE_MAX - WIDE_MIN));
		const max = (scale - 1) / 2;
		const clamp = (v: number) => Math.min(max, Math.max(-max, v));
		if (k === 1 && (focalX !== null || focalY !== null)) {
			keys.push({ scale, x: clamp((0.5 - (focalX ?? 0.5)) * (scale - 1) * 2), y: clamp((0.5 - (focalY ?? 0.5)) * (scale - 1) * 2) });
		} else {
			const reach = close ? 0.6 + 0.4 * rnd() : rnd();
			const angle = rnd() * 2 * Math.PI;
			keys.push({ scale, x: Math.cos(angle) * reach * max, y: Math.sin(angle) * reach * max });
		}
	}
	return keys;
}

/** Ken Burns inside a chosen framing (docs/spec/framing.md §3.4): at most this scale and this drift (of the screen). */
export const FRAMED_MAX_SCALE = 1.08;
export const FRAMED_MAX_DRIFT = 0.03;

/**
 * §3.4: with a view the person chose, the motion stays inside it: a loop of two keys, the view itself
 * and a slight push in (1.04..1.08) drifting at most 3% of the screen. Played with a segment of half the
 * hold time, it starts at the view, pushes in and is back at the view when the slide ends.
 */
export function framedKenBurnsKeys(id: string): KbKey[] {
	const rnd = sequence(id);
	const scale = f32(1.04 + rnd() * (FRAMED_MAX_SCALE - 1.04));
	const reach = Math.min(FRAMED_MAX_DRIFT, (scale - 1) / 2) * (0.5 + 0.5 * rnd());
	const angle = rnd() * 2 * Math.PI;
	return [
		{ scale: 1, x: 0, y: 0 },
		{ scale, x: Math.cos(angle) * reach, y: Math.sin(angle) * reach }
	];
}

function catmullRom(p0: number, p1: number, p2: number, p3: number, t: number): number {
	const t2 = t * t;
	const t3 = t2 * t;
	return 0.5 * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t3);
}

/** The framing at t segments (t = 1 is keys[1]; the loop continues past the last key). */
export function kenBurnsAt(keys: KbKey[], t: number): KbKey {
	const n = keys.length;
	const i = Math.floor(t);
	const u = t - i;
	const k = (j: number) => keys[((j % n) + n) % n]!;
	const p0 = k(i - 1),
		p1 = k(i),
		p2 = k(i + 1),
		p3 = k(i + 2);
	const s = Math.min(MAX_ZOOM, Math.max(1, catmullRom(p0.scale, p1.scale, p2.scale, p3.scale, u)));
	const max = (s - 1) / 2;
	const clamp = (v: number) => Math.min(max, Math.max(-max, v));
	return { scale: s, x: clamp(catmullRom(p0.x, p1.x, p2.x, p3.x, u)), y: clamp(catmullRom(p0.y, p1.y, p2.y, p3.y, u)) };
}

/**
 * Web Animations keyframes for `durationMs` of motion, one key every `segmentMs` (sampled four times per segment;
 * linear in between is indistinguishable at this pace). Translations are fractions of the
 * stage size, as CSS percentages of the moving element.
 */
export function kenBurnsKeyframes(keys: KbKey[], durationMs: number, segmentMs = SEGMENT_MS): Keyframe[] {
	const seg = segmentMs > 0 ? segmentMs : SEGMENT_MS;
	const steps = Math.max(2, Math.ceil((durationMs / seg) * 4));
	const out: Keyframe[] = [];
	for (let i = 0; i <= steps; i++) {
		const t = ((i / steps) * durationMs) / seg;
		const f = kenBurnsAt(keys, t);
		out.push({ offset: i / steps, transform: `translate(${(f.x * 100).toFixed(3)}%, ${(f.y * 100).toFixed(3)}%) scale(${f.scale.toFixed(4)})` });
	}
	return out;
}
