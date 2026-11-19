<script lang="ts">
	// The operator's notice (shop file `notice`), shown prominently above the products in the visitor's language:
	// plain text, a blank line starts a new paragraph.
	import { i18n } from '$lib/i18n/index.svelte';
	import { localized, paragraphs, type Shop } from '$lib/shop';
	import Icon from './Icon.svelte';

	let { shop }: { shop: Shop } = $props();
	const paras = $derived(paragraphs(localized(shop.notice, i18n.locale)));
</script>

{#if paras.length}
	<div class="notice" role="note" data-testid="shop-notice">
		<span class="icon" aria-hidden="true"><Icon name="info" size={22} /></span>
		<div class="text">
			{#each paras as p, i (i)}<p>{p}</p>{/each}
		</div>
	</div>
{/if}

<style>
	.notice {
		display: flex;
		gap: var(--s-3);
		align-items: flex-start;
		padding: var(--s-4) var(--s-5);
		border-radius: var(--r-lg);
		border: 1px solid color-mix(in srgb, var(--primary) 35%, transparent);
		border-inline-start: 4px solid var(--primary);
		background: var(--primary-soft);
		color: var(--primary-soft-text);
		font-size: 1.02rem;
		line-height: 1.55;
	}
	.icon {
		flex: none;
		display: grid;
		margin-top: 1px;
		color: var(--primary);
	}
	.text {
		display: grid;
		gap: var(--s-2);
		min-width: 0;
	}
	p {
		margin: 0;
		color: var(--text);
	}
</style>
