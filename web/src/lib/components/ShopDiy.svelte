<script lang="ts">
	// For visitors in a shipping country: "Buy it yourself and convert it", the shop file's elsewhere links that list
	// their country (the same browser-language guess as the order form's country), with the convert tool link and the
	// affiliate disclosure. Title and text come from the shop file's `diy`, else the defaults.
	import { discovery } from '$lib/discovery.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { browserLanguages, elsewhereSections, localized, paragraphs, visitorCountry, type Shop } from '$lib/shop';
	import Icon from './Icon.svelte';
	import ShopRetailerLinks from './ShopRetailerLinks.svelte';

	let { shop }: { shop: Shop } = $props();

	const links = $derived(elsewhereSections(shop, visitorCountry(browserLanguages(), shop.shipping.countries)).diy);
	const title = $derived(localized(shop.diy?.title, i18n.locale) || t('shop.diyTitle'));
	const body = $derived(paragraphs(localized(shop.diy?.body, i18n.locale) || t('shop.diyBody', { brand: discovery.brand })));
	const convert = $derived(discovery.appUrl('/convert') ?? '/convert');
</script>

{#if links}
	<section class="diy" aria-labelledby="diy-title" data-testid="shop-diy">
		<h2 id="diy-title"><Icon name="frame" size={20} />{title}</h2>
		{#each body as p, i (i)}<p class="muted">{p}</p>{/each}
		<p><a class="convert" href={convert}>{t('shop.elsewhereConvert')}<Icon name="chevronRight" size={16} /></a></p>
		<ShopRetailerLinks {links} />
	</section>
{/if}

<style>
	.diy {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		padding: var(--s-5);
		border-radius: var(--r-lg);
		border: 1px solid var(--border);
		background: var(--surface);
		box-shadow: var(--shadow);
	}
	h2 {
		display: flex;
		align-items: center;
		gap: 8px;
		font-size: 1.25rem;
	}
	h2 :global(svg) {
		color: var(--primary);
	}
	.convert {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-weight: 600;
	}
</style>
