<script lang="ts">
	import { feedInitials } from '$lib/feeds';

	/** A feed's cover (one of its own photos) or, without one, its initials on a warm tile. */
	let { name, url = null, size = 48 }: { name: string; url?: string | null; size?: number } = $props();
	let failed = $state(false);
	const HUES = [18, 32, 145, 200, 265, 340, 48, 170];
	const hue = $derived(HUES[[...(name ?? '')].reduce((a, c) => a + c.charCodeAt(0), 0) % HUES.length]);
</script>

<span class="cover" style="--size:{size}px; --hue:{hue}" aria-hidden="true">
	{#if url && !failed}
		<img src={url} alt="" onerror={() => (failed = true)} />
	{:else}
		<span style="font-size:{Math.round(size * 0.36)}px">{feedInitials(name)}</span>
	{/if}
</span>

<style>
	.cover {
		width: var(--size);
		height: var(--size);
		flex: none;
		border-radius: calc(var(--size) * 0.24);
		display: inline-grid;
		place-items: center;
		overflow: hidden;
		background: linear-gradient(135deg, hsl(var(--hue) 55% 86%), hsl(calc(var(--hue) + 30) 50% 78%));
		color: hsl(var(--hue) 45% 24%);
		font-weight: 700;
		font-family: var(--font-display, inherit);
	}
	@media (prefers-color-scheme: dark) {
		.cover {
			background: linear-gradient(135deg, hsl(var(--hue) 30% 30%), hsl(calc(var(--hue) + 30) 30% 24%));
			color: hsl(var(--hue) 60% 88%);
		}
	}
	img {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
</style>
