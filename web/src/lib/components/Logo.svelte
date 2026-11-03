<script lang="ts">
	import { discovery } from '$lib/discovery.svelte';

	let { size = 30, wordmark = true }: { size?: number; wordmark?: boolean } = $props();
</script>

<span class="logo">
	<svg width={size} height={size} viewBox="0 0 64 64" aria-hidden="true">
		<rect x="4" y="8" width="56" height="48" rx="12" fill="var(--primary)" />
		<rect x="11" y="15" width="42" height="34" rx="6" fill="#fbf1e6" />
		<circle cx="41" cy="26" r="5" fill="var(--sun)" />
		<path d="M11 43 23 31l9 9 6-6 15 15H17a6 6 0 0 1-6-6z" fill="#4f7a5a" />
	</svg>
	<!-- On a storefront domain the wordmark is the shop's brand. -->
	{#if wordmark}<span class="word">{discovery.siteName}</span>{/if}
</span>

<style>
	.logo {
		display: inline-flex;
		align-items: center;
		gap: 10px;
	}
	.word {
		font-family: var(--font-display);
		font-weight: 620;
		font-size: 1.3rem;
		letter-spacing: -0.02em;
		color: var(--text);
	}
</style>
