<script lang="ts">
	// Seller, contact and privacy note for the shop: on the landing page and the shop pages.
	import { t } from '$lib/i18n/index.svelte';
	import type { Shop } from '$lib/shop';

	let { shop }: { shop: Shop } = $props();
</script>

<div class="seller" data-testid="seller-footer">
	<p>
		{t('shop.soldBy', { seller: shop.seller.name })}
		<span class="sep" aria-hidden="true">·</span>
		{t('shop.contact')} <a href="mailto:{shop.seller.email}">{shop.seller.email}</a>
	</p>
	{#if shop.seller.legal}<p class="legal">{shop.seller.legal}</p>{/if}
	<p class="privacy">
		{t('shop.privacyNote')}
		{#if shop.seller.privacy_url}
			<a href={shop.seller.privacy_url} rel="noopener" target="_blank">{t('shop.privacyLink')}</a>
		{/if}
	</p>
</div>

<style>
	.seller {
		display: grid;
		gap: 4px;
		font-size: 0.85rem;
		color: var(--text-muted);
	}
	.legal,
	.privacy {
		color: var(--text-faint);
	}
	.sep {
		margin-inline: 4px;
	}
	a {
		color: inherit;
		text-decoration: underline;
		text-underline-offset: 2px;
	}
</style>
