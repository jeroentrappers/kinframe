<script lang="ts">
	// For visitors outside the shipping countries: retailers selling a frame the convert tool supports. The operator's
	// links may be affiliate links, so they carry rel="sponsored" and a disclosure. Visitors in a shipping country see
	// their links in ShopDiy instead.
	import { discovery } from '$lib/discovery.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { browserLanguages, countryName, elsewhereSections, outsideCountries, visitorCountry, type Shop, type ShopElsewhereLink } from '$lib/shop';
	import Icon from './Icon.svelte';
	import ShopRetailerLinks from './ShopRetailerLinks.svelte';

	let { shop }: { shop: Shop } = $props();

	const list = (codes: string[]) => new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format(codes);
	const countries = $derived(list(shop.shipping.countries.map((c) => countryName(c, i18n.tag))));
	const where = (l: ShopElsewhereLink) => {
		const codes = outsideCountries(l, shop.shipping.countries);
		return codes.includes('*') ? t('shop.elsewhereOther') : list(codes.map((c) => countryName(c, i18n.tag)));
	};
	const convert = $derived(discovery.appUrl('/convert') ?? '/convert');
	const links = $derived(elsewhereSections(shop, visitorCountry(browserLanguages(), shop.shipping.countries)).outside);
</script>

{#if links}
	<section class="elsewhere" aria-labelledby="elsewhere-title" data-testid="shop-elsewhere">
		<h3 id="elsewhere-title"><Icon name="globe" size={18} />{t('shop.elsewhereTitle', { countries })}</h3>
		<p class="muted">
			{t('shop.elsewhereBody', { countries, brand: discovery.brand })}
			<a href={convert}>{t('shop.elsewhereConvert')}</a>
		</p>
		<ShopRetailerLinks {links} {where} />
	</section>
{/if}

<style>
	.elsewhere {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		padding: var(--s-5);
		border-radius: var(--r-lg);
		border: 1px solid var(--border);
		background: color-mix(in srgb, var(--surface) 70%, transparent);
	}
	h3 {
		display: flex;
		align-items: center;
		gap: 8px;
		font-size: 1.1rem;
	}
</style>
