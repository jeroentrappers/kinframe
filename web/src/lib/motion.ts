import { fade, fly, type FadeParams, type FlyParams } from 'svelte/transition';

export function reducedMotion(): boolean {
	return typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** Transitions that collapse to nothing when the user prefers reduced motion. */
export function softFade(node: Element, params: FadeParams = {}) {
	return fade(node, { duration: reducedMotion() ? 0 : 160, ...params });
}

export function softFly(node: Element, params: FlyParams = {}) {
	return reducedMotion() ? fade(node, { duration: 0 }) : fly(node, { y: 8, duration: 200, ...params });
}
