<script lang="ts">
	import { initials } from '$lib/format';

	let { name, url = null, size = 36 }: { name: string; url?: string | null; size?: number } = $props();
	let failed = $state(false);

	// Warm, deterministic background per name.
	const HUES = [18, 32, 145, 200, 265, 340, 48, 170];
	const hue = $derived(HUES[[...(name ?? '')].reduce((a, c) => a + c.charCodeAt(0), 0) % HUES.length]);
</script>

<span
	class="avatar"
	style="--size:{size}px; --hue:{hue}"
	aria-hidden="true"
>
	{#if url && !failed}
		<img src={url} alt="" onerror={() => (failed = true)} />
	{:else}
		<span style="font-size:{Math.round(size * 0.38)}px">{initials(name)}</span>
	{/if}
</span>

<style>
	.avatar {
		width: var(--size);
		height: var(--size);
		flex: none;
		border-radius: 50%;
		display: inline-grid;
		place-items: center;
		overflow: hidden;
		background: hsl(var(--hue) 45% 86%);
		color: hsl(var(--hue) 45% 26%);
		font-weight: 700;
		letter-spacing: 0.02em;
		box-shadow: 0 0 0 2px var(--surface);
	}
	@media (prefers-color-scheme: dark) {
		.avatar {
			background: hsl(var(--hue) 30% 28%);
			color: hsl(var(--hue) 60% 88%);
		}
	}
	img {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
</style>
