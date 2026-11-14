<script lang="ts">
	// Shops with stock: "In stock · shipped Tuesday 17 November", or the later dispatch day when the frame is out of stock.
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { formatDay, type Shop, type ShopProduct } from '$lib/shop';
	import Icon from './Icon.svelte';

	let { shop, product }: { shop: Shop; product: ShopProduct } = $props();
	const inStock = $derived((product.in_stock ?? 0) > 0);
</script>

{#if shop.stock}
	<p class="avail" class:in={inStock} data-testid="availability">
		<Icon name={inStock ? 'check' : 'clock'} size={16} />
		{inStock
			? t('shop.inStockShips', { date: formatDay(shop.stock.ships_in_stock, i18n.tag) })
			: t('shop.shipsOn', { date: formatDay(shop.stock.ships_out_of_stock, i18n.tag) })}
	</p>
{/if}

<style>
	.avail {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		margin: 0;
		font-size: 0.9rem;
		color: var(--text-muted);
	}
	.avail.in {
		color: var(--accent-text);
		font-weight: 600;
	}
</style>
