<script lang="ts">
	// "Get a {brand}" on the landing page: only rendered when discovery has a shop.
	import { discovery } from '$lib/discovery.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { countryName, formatMoney, localized, type Shop } from '$lib/shop';
	import Icon from './Icon.svelte';
	import ShopFrameArt from './ShopFrameArt.svelte';

	let { shop }: { shop: Shop } = $props();

	const brand = $derived(discovery.brand);
	const money = (cents: number, compact = true) => formatMoney(cents, shop.currency, i18n.tag, { compact });
	const vat = $derived(new Intl.NumberFormat(i18n.tag).format(shop.vat_percent));
	const when = $derived(localized(shop.estimated_delivery, i18n.locale));
	const countries = $derived(
		new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format(shop.shipping.countries.map((c) => countryName(c, i18n.tag)))
	);
	const free = $derived(shop.shipping.free_from_cents ?? null);
</script>

<section id="shop" class="container shop" aria-labelledby="shop-title">
	<div class="intro">
		<p class="eyebrow">{t('shop.eyebrow')}</p>
		<h2 id="shop-title">{t('shop.title', { brand })}</h2>
		<p class="lede">{t('shop.lede', { brand })}</p>
		{#if when}<p class="delivery"><Icon name="calendar" size={16} />{t('shop.delivery', { when })}</p>{/if}
	</div>

	<div class="products">
		{#each shop.products as p, i (p.id)}
			{@const specs = localized(p.specs, i18n.locale) ?? []}
			<article class="product" aria-labelledby="product-{p.id}">
				<div class="art"><ShopFrameArt image={p.image ?? (i === 0 ? 'frame-10' : 'frame-15')} /></div>
				<div class="body">
					<div class="name-row">
						<h3 id="product-{p.id}">{p.name}</h3>
						{#if p.size}<span class="size">{p.size}</span>{/if}
					</div>
					<p class="price">
						<span class="amount num">{money(p.price_cents)}</span>
						<span class="vat">{t('shop.inclVat', { vat })}</span>
					</p>
					{#if specs.length}
						<ul class="specs">
							{#each specs as s (s)}
								<li><Icon name="check" size={16} /><span>{s}</span></li>
							{/each}
						</ul>
					{/if}
					<a class="btn btn-primary reserve" href="/shop?product={encodeURIComponent(p.id)}" aria-label={t('shop.reserveProduct', { name: p.name })}>
						{t('shop.reserve')}
						<Icon name="chevronRight" size={18} />
					</a>
				</div>
			</article>
		{/each}
	</div>

	<ul class="points">
		<li>
			<span class="p-icon"><Icon name="sparkle" size={20} /></span>
			<div>
				<strong>{t('shop.pointNoSub')}</strong>
				<p>{t('shop.pointNoSubBody')}</p>
			</div>
		</li>
		<li>
			<span class="p-icon"><Icon name="heart" size={20} /></span>
			<div>
				<strong>{t('shop.pointPayLater')}</strong>
				<p>{t('shop.pointPayLaterBody')}</p>
			</div>
		</li>
		<li>
			<span class="p-icon"><Icon name="send" size={20} /></span>
			<div>
				{#if free != null}
					<strong>{t('shop.pointShipping', { amount: money(free) })}</strong>
					<p>{t('shop.pointShippingBody', { countries, amount: money(free), flat: money(shop.shipping.flat_cents) })}</p>
				{:else}
					<strong>{t('shop.pointShippingFlat', { flat: money(shop.shipping.flat_cents) })}</strong>
					<p>{t('shop.pointShippingFlatBody', { countries })}</p>
				{/if}
			</div>
		</li>
	</ul>
</section>

<style>
	.shop {
		display: flex;
		flex-direction: column;
		gap: var(--s-6);
		padding-block: var(--s-6) var(--s-7);
	}
	.intro {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		gap: var(--s-3);
		max-width: 640px;
		margin-inline: auto;
	}
	.eyebrow {
		font-size: 0.8rem;
		font-weight: 700;
		letter-spacing: 0.08em;
		text-transform: uppercase;
		color: var(--primary);
	}
	h2 {
		font-size: clamp(1.8rem, 1.4rem + 1.6vw, 2.6rem);
		line-height: 1.1;
		letter-spacing: -0.02em;
	}
	.lede {
		font-size: 1.08rem;
		color: var(--text-muted);
	}
	.delivery {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 6px 14px;
		border-radius: var(--r-full);
		background: var(--accent-soft);
		color: var(--accent-text);
		font-weight: 600;
		font-size: 0.9rem;
	}
	.products {
		display: grid;
		gap: var(--s-5);
	}
	@media (min-width: 760px) {
		.products {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}
	.product {
		display: flex;
		flex-direction: column;
		border-radius: var(--r-xl);
		background: var(--surface);
		border: 1px solid var(--border);
		box-shadow: var(--shadow);
		overflow: hidden;
	}
	.art {
		padding: var(--s-5) var(--s-5) var(--s-2);
		background:
			radial-gradient(70% 90% at 50% 100%, color-mix(in srgb, var(--primary-soft) 80%, transparent), transparent 70%),
			var(--bg-tint);
	}
	.body {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		padding: var(--s-5);
		flex: 1;
	}
	.name-row {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: var(--s-3);
		flex-wrap: wrap;
	}
	h3 {
		font-size: 1.3rem;
	}
	.size {
		font-weight: 700;
		color: var(--primary-soft-text);
		background: var(--primary-soft);
		border-radius: var(--r-full);
		padding: 2px 10px;
		font-size: 0.9rem;
	}
	.price {
		display: flex;
		align-items: baseline;
		gap: 10px;
		flex-wrap: wrap;
	}
	.amount {
		font-family: var(--font-display);
		font-size: 2rem;
		font-weight: 650;
		letter-spacing: -0.01em;
	}
	.vat {
		color: var(--text-muted);
		font-size: 0.9rem;
	}
	.specs {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 6px;
		color: var(--text-muted);
	}
	.specs li {
		display: flex;
		gap: 8px;
		align-items: flex-start;
	}
	.specs :global(svg) {
		flex: none;
		margin-top: 3px;
		color: var(--accent);
	}
	.reserve {
		margin-top: auto;
		align-self: stretch;
		justify-content: center;
	}
	.points {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: var(--s-4);
	}
	@media (min-width: 760px) {
		.points {
			grid-template-columns: repeat(3, minmax(0, 1fr));
		}
	}
	.points li {
		display: flex;
		gap: var(--s-3);
		align-items: flex-start;
	}
	.points p {
		color: var(--text-muted);
		font-size: 0.95rem;
		margin-top: 2px;
	}
	.p-icon {
		flex: none;
		width: 40px;
		height: 40px;
		border-radius: 12px;
		display: grid;
		place-items: center;
		background: var(--primary-soft);
		color: var(--primary-soft-text);
	}
</style>
