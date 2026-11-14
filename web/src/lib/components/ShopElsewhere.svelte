<script lang="ts">
	// For visitors outside the shipping countries: retailers selling a frame the convert tool supports. The operator's
	// links may be affiliate links, so they carry rel="sponsored" and a disclosure.
	import { discovery } from '$lib/discovery.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { countryName, type Shop } from '$lib/shop';
	import Icon from './Icon.svelte';

	let { shop }: { shop: Shop } = $props();

	const list = (codes: string[]) => new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format(codes);
	const countries = $derived(list(shop.shipping.countries.map((c) => countryName(c, i18n.tag))));
	const where = (codes: string[]) =>
		codes.includes('*') ? t('shop.elsewhereOther') : list(codes.map((c) => countryName(c, i18n.tag)));
	const convert = $derived(discovery.appUrl('/convert') ?? '/convert');
</script>

{#if shop.elsewhere?.length}
	<section class="elsewhere" aria-labelledby="elsewhere-title">
		<h3 id="elsewhere-title"><Icon name="globe" size={18} />{t('shop.elsewhereTitle', { countries })}</h3>
		<p class="muted">
			{t('shop.elsewhereBody', { countries, brand: discovery.brand })}
			<a href={convert}>{t('shop.elsewhereConvert')}</a>
		</p>
		<ul>
			{#each shop.elsewhere as l, i (i)}
				<li>
					<a href={l.url} target="_blank" rel={l.affiliate ? 'sponsored noopener' : 'noopener'}>
						<span class="where">{where(l.countries)}</span>
						<span class="what">{l.product} · {l.retailer}</span>
						<Icon name="chevronRight" size={16} />
					</a>
				</li>
			{/each}
		</ul>
		{#if shop.elsewhere.some((l) => l.affiliate)}<p class="disclosure">{t('shop.elsewhereDisclosure')}</p>{/if}
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
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: var(--s-2);
	}
	@media (min-width: 760px) {
		ul {
			grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
		}
	}
	li a {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 12px 14px;
		border-radius: var(--r);
		border: 1px solid var(--border);
		background: var(--surface);
		color: inherit;
		text-decoration: none;
	}
	li a:hover {
		border-color: var(--primary);
	}
	.where {
		font-weight: 600;
	}
	.what {
		flex: 1;
		color: var(--text-muted);
		font-size: 0.92rem;
	}
	.disclosure {
		font-size: 0.8rem;
		color: var(--text-muted);
	}
</style>
