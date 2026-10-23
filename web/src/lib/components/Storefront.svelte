<script lang="ts">
	// The landing page on a storefront domain (discovery shop.domains): the brand's shop, no sign-in. The family
	// app lives on the main origin, linked from the footer.
	import { discovery } from '$lib/discovery.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { countryName, formatMoney, localized, type Shop } from '$lib/shop';
	import Icon from './Icon.svelte';
	import LanguagePicker from './LanguagePicker.svelte';
	import Logo from './Logo.svelte';
	import SellerFooter from './SellerFooter.svelte';
	import ShopSection from './ShopSection.svelte';
	import StorefrontArt from './StorefrontArt.svelte';

	let { shop }: { shop: Shop } = $props();

	const brand = $derived(discovery.brand);
	const money = (cents: number) => formatMoney(cents, shop.currency, i18n.tag, { compact: true });
	const fromPrice = $derived(Math.min(...shop.products.map((p) => p.price_cents)));
	const when = $derived(localized(shop.estimated_delivery, i18n.locale));
	const countries = $derived(
		new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format(shop.shipping.countries.map((c) => countryName(c, i18n.tag)))
	);
	const free = $derived(shop.shipping.free_from_cents ?? null);
	const app = $derived(discovery.appUrl('/'));
	const title = $derived(t('storefront.metaTitle', { brand }));

	// Link previews come from the server's index.html; this keeps the description in step with the language.
	$effect(() => {
		const desc = t('storefront.metaDescription');
		document.querySelector('meta[name="description"]')?.setAttribute('content', desc);
		document.querySelector('meta[property="og:description"]')?.setAttribute('content', desc);
		document.querySelector('meta[property="og:title"]')?.setAttribute('content', title);
	});

	const steps = $derived([
		{ icon: 'send' as const, title: t('storefront.step1Title'), body: t('storefront.step1Body') },
		{ icon: 'heart' as const, title: t('storefront.step2Title'), body: t('storefront.step2Body') },
		{ icon: 'sparkle' as const, title: t('storefront.step3Title'), body: t('storefront.step3Body') }
	]);
</script>

<svelte:head><title>{title}</title></svelte:head>

<div class="storefront">
	<header class="container top">
		<a href="/" class="home"><Logo size={32} /></a>
		<div class="spacer"></div>
		<a class="btn btn-primary btn-sm reserve-top" href="#shop">{t('shop.reserve')}</a>
		<LanguagePicker />
	</header>

	<main id="main">
		<section class="container hero" aria-labelledby="hero-title">
			<div class="copy">
				<p class="eyebrow"><span class="dot" aria-hidden="true"></span>{t('shop.eyebrow')}</p>
				<h1 id="hero-title">{t('storefront.title')}</h1>
				<p class="lede">{t('storefront.lede')}</p>
				<div class="actions">
					<a class="btn btn-primary btn-lg" href="#shop">{t('shop.pageTitle', { brand })}<Icon name="chevronDown" size={18} /></a>
					<a class="btn btn-ghost btn-lg" href="#how">{t('storefront.howTitle')}</a>
				</div>
				<p class="from">{t('storefront.priceFrom', { price: money(fromPrice) })}</p>
			</div>
			<div class="art"><StorefrontArt /></div>
		</section>

		<section id="how" class="container how" aria-labelledby="how-title">
			<h2 id="how-title">{t('storefront.howTitle')}</h2>
			<ol>
				{#each steps as s, i (s.icon)}
					<li>
						<span class="step-icon"><Icon name={s.icon} size={22} /><span class="n" aria-hidden="true">{i + 1}</span></span>
						<h3>{s.title}</h3>
						<p>{s.body}</p>
					</li>
				{/each}
			</ol>
		</section>

		<ShopSection {shop} />

		<section class="container faq" aria-labelledby="faq-title">
			<h2 id="faq-title">{t('storefront.faqTitle')}</h2>
			<div class="qa">
				<details>
					<summary>{t('storefront.faqIncludedQ')}<Icon name="chevronDown" size={18} /></summary>
					<p>{t('storefront.faqIncludedA', { brand })}</p>
				</details>
				<details>
					<summary>{t('storefront.faqShippingQ')}<Icon name="chevronDown" size={18} /></summary>
					<p>
						{#if free != null}
							{t('shop.pointShippingBody', { countries, amount: money(free), flat: money(shop.shipping.flat_cents) })}
						{:else}
							{t('shop.pointShippingFlat', { flat: money(shop.shipping.flat_cents) })}.
							{t('shop.pointShippingFlatBody', { countries })}
						{/if}
					</p>
				</details>
				<details>
					<summary>{t('storefront.faqWhenQ')}<Icon name="chevronDown" size={18} /></summary>
					<p>{when ? `${t('shop.delivery', { when })}. ` : ''}{t('storefront.faqWhenA')}</p>
				</details>
				<details>
					<summary>{t('storefront.faqPayQ')}<Icon name="chevronDown" size={18} /></summary>
					<p>{t('shop.how1')} {t('shop.how2')} {t('shop.how3')}</p>
				</details>
			</div>
		</section>
	</main>

	<footer class="foot">
		{#if app}
			<div class="container app-band">
				<span>{t('storefront.haveFrame')}</span>
				<a class="btn btn-soft btn-sm" href={app} data-testid="open-app">{t('storefront.openApp')}<Icon name="chevronRight" size={16} /></a>
			</div>
		{/if}
		<div class="container legal">
			<SellerFooter {shop} />
			<p class="software">{t('landing.footer')}</p>
			<nav class="legal-links" aria-label={t('legal.links')}>
				<a href={discovery.appUrl('/privacy') ?? '/privacy'}>{t('legal.privacy')}</a>
				<a href={discovery.appUrl('/terms') ?? '/terms'}>{t('legal.terms')}</a>
			</nav>
		</div>
	</footer>
</div>

<style>
	.storefront {
		min-height: 100dvh;
		display: flex;
		flex-direction: column;
		background:
			radial-gradient(1100px 620px at 90% -12%, color-mix(in srgb, var(--primary-soft) 75%, transparent), transparent 62%),
			var(--bg);
	}
	.top {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		height: 72px;
	}
	.home {
		text-decoration: none;
	}
	.hero {
		display: grid;
		gap: var(--s-5);
		align-items: center;
		padding-block: var(--s-3) var(--s-7);
	}
	@media (min-width: 900px) {
		.hero {
			grid-template-columns: 1.05fr 0.95fr;
			gap: var(--s-6);
			padding-block: var(--s-6) var(--s-8);
		}
	}
	.copy {
		display: flex;
		flex-direction: column;
		gap: var(--s-4);
		max-width: 580px;
	}
	.eyebrow {
		align-self: flex-start;
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 6px 12px;
		border-radius: var(--r-full);
		background: var(--surface);
		border: 1px solid var(--border);
		font-size: 0.85rem;
		font-weight: 650;
		color: var(--primary-soft-text);
	}
	.dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--primary);
	}
	h1 {
		font-size: clamp(2.3rem, 1.6rem + 3vw, 3.8rem);
		line-height: 1.03;
		letter-spacing: -0.03em;
		text-wrap: balance;
	}
	.lede {
		font-size: 1.15rem;
		color: var(--text-muted);
		max-width: 46ch;
	}
	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: var(--s-2);
		margin-top: var(--s-1);
	}
	.from {
		font-size: 0.92rem;
		font-weight: 600;
		color: var(--text-muted);
	}
	.art {
		order: -1;
		width: 100%;
		max-width: 400px;
		justify-self: center;
	}
	@media (min-width: 900px) {
		.art {
			order: 0;
			max-width: none;
		}
	}
	@media (max-width: 520px) {
		.actions .btn {
			flex: 1 1 100%;
			justify-content: center;
		}
	}

	h2 {
		font-size: clamp(1.7rem, 1.35rem + 1.4vw, 2.4rem);
		line-height: 1.1;
		letter-spacing: -0.02em;
		text-align: center;
	}
	.how {
		padding-block: var(--s-6);
		display: flex;
		flex-direction: column;
		gap: var(--s-5);
		scroll-margin-top: var(--s-4);
	}
	.how ol {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: var(--s-4);
	}
	@media (min-width: 760px) {
		.how ol {
			grid-template-columns: repeat(3, minmax(0, 1fr));
		}
	}
	.how li {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		padding: var(--s-5);
		border-radius: var(--r-lg);
		background: var(--surface);
		border: 1px solid var(--border);
		box-shadow: var(--shadow-sm);
	}
	.how h3 {
		font-size: 1.15rem;
	}
	.how p {
		color: var(--text-muted);
	}
	.step-icon {
		position: relative;
		width: 46px;
		height: 46px;
		border-radius: 14px;
		display: grid;
		place-items: center;
		background: var(--primary-soft);
		color: var(--primary-soft-text);
		margin-bottom: var(--s-1);
	}
	.n {
		position: absolute;
		top: -7px;
		inset-inline-end: -7px;
		width: 22px;
		height: 22px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--primary);
		color: var(--on-primary);
		font-size: 0.75rem;
		font-weight: 700;
	}

	.faq {
		padding-block: var(--s-6) var(--s-7);
		display: flex;
		flex-direction: column;
		gap: var(--s-5);
	}
	.qa {
		max-width: 760px;
		width: 100%;
		margin-inline: auto;
		display: grid;
		gap: var(--s-2);
	}
	details {
		border-radius: var(--r);
		background: var(--surface);
		border: 1px solid var(--border);
	}
	summary {
		list-style: none;
		cursor: pointer;
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-3);
		padding: var(--s-4) var(--s-5);
		font-weight: 650;
		font-size: 1.05rem;
	}
	summary::-webkit-details-marker {
		display: none;
	}
	summary :global(svg) {
		flex: none;
		color: var(--text-muted);
		transition: transform var(--dur) var(--ease);
	}
	details[open] summary :global(svg) {
		transform: rotate(180deg);
	}
	details p {
		padding: 0 var(--s-5) var(--s-4);
		color: var(--text-muted);
	}

	.foot {
		margin-top: auto;
		border-top: 1px solid var(--border);
		background: color-mix(in srgb, var(--surface) 55%, transparent);
	}
	.app-band {
		display: flex;
		align-items: center;
		justify-content: center;
		flex-wrap: wrap;
		gap: var(--s-2) var(--s-3);
		padding-block: var(--s-4);
		font-weight: 600;
		border-bottom: 1px solid var(--border);
	}
	.legal {
		padding-block: var(--s-5);
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		font-size: 0.85rem;
		color: var(--text-muted);
	}
	.software {
		color: var(--text-faint);
	}
	.legal-links {
		display: flex;
		flex-wrap: wrap;
		gap: var(--s-4);
	}
	.legal-links a {
		color: inherit;
	}
	/* On a phone the hero's own button is right below; the header keeps the brand and the language. */
	@media (max-width: 560px) {
		.reserve-top {
			display: none;
		}
		.art {
			max-width: 300px;
		}
	}
</style>
