<script lang="ts">
	import type { Snippet } from 'svelte';
	import type { Freshness } from '$lib/api/client';
	import { ringStyle } from '$lib/engagement';

	/** The freshness ring (README §5.20): full and warm when shared today, thinner and paler over 14 days. */
	let { freshness, size = 64, children }: { freshness: Freshness | null | undefined; size?: number; children: Snippet } = $props();

	const ring = $derived(ringStyle(freshness));
	const stroke = $derived(ring.tone === 'warm' ? 4 : ring.tone === 'mild' ? 3.5 : 3);
	const r = $derived(size / 2 - stroke / 2);
	const circumference = $derived(2 * Math.PI * r);
</script>

<span class="ring {ring.tone}" style="--size:{size}px">
	<svg width={size} height={size} viewBox="0 0 {size} {size}" aria-hidden="true">
		<circle class="track" cx={size / 2} cy={size / 2} {r} stroke-width={stroke} fill="none" />
		{#if ring.fraction > 0}
			<circle
				class="arc"
				cx={size / 2}
				cy={size / 2}
				{r}
				stroke-width={stroke}
				fill="none"
				stroke-linecap="round"
				stroke-dasharray="{circumference * ring.fraction} {circumference}"
				transform="rotate(-90 {size / 2} {size / 2})"
			/>
		{/if}
	</svg>
	<span class="inner" style="--inset:{stroke + 3}px">{@render children()}</span>
</span>

<style>
	.ring {
		position: relative;
		display: inline-grid;
		place-items: center;
		width: var(--size);
		height: var(--size);
		flex: none;
	}
	svg {
		position: absolute;
		inset: 0;
	}
	.track {
		stroke: var(--border);
	}
	.arc {
		transition: stroke-dasharray 600ms var(--ease);
	}
	.warm .arc {
		stroke: var(--sun, #e9a23b);
	}
	.mild .arc {
		stroke: color-mix(in srgb, var(--sun, #e9a23b) 70%, var(--border));
	}
	.pale .arc {
		stroke: color-mix(in srgb, var(--sun, #e9a23b) 35%, var(--border));
	}
	.inner {
		position: absolute;
		inset: var(--inset);
		border-radius: 50%;
		overflow: hidden;
		display: grid;
		place-items: center;
	}
	.inner :global(> *) {
		width: 100% !important;
		height: 100% !important;
	}
</style>
