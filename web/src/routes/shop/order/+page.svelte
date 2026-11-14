<script lang="ts">
	// Where the payment provider sends the customer back (shops with checkout): shows the order and its payment. While
	// the payment is still being processed it checks again every few seconds.
	import { onDestroy, onMount } from 'svelte';
	import { page } from '$app/state';
	import { api, call, ApiError } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { problemMessage } from '$lib/problem';
	import { formatMoney, type ShopReceipt } from '$lib/shop';
	import Icon from '$components/Icon.svelte';
	import LanguagePicker from '$components/LanguagePicker.svelte';
	import Logo from '$components/Logo.svelte';
	import SellerFooter from '$components/SellerFooter.svelte';

	const token = page.url.searchParams.get('token') ?? '';
	let order = $state<ShopReceipt | null>(null);
	let invalid = $state(token.length < 10);
	let failure = $state<string | null>(null);
	let timer: ReturnType<typeof setTimeout> | undefined;
	let polls = 0;

	const shop = $derived(discovery.shop);
	const money = (cents: number) => (shop ? formatMoney(cents, shop.currency, i18n.tag) : '');
	const vat = $derived(shop ? new Intl.NumberFormat(i18n.tag).format(shop.vat_percent) : '');
	// open: not finished at the provider; pending/authorized: the provider is still confirming.
	const phase = $derived.by(() => {
		const s = order?.payment_status;
		if (s === 'paid') return 'paid';
		if (s === 'pending' || s === 'authorized') return 'pending';
		if (s === 'open') return 'open';
		return 'failed';
	});

	async function load() {
		try {
			order = await call(api.POST('/v1/shop/orders/status', { body: { token } }));
			failure = null;
			// Right after the redirect the provider may not have confirmed yet: check again for about two minutes.
			if ((phase === 'pending' || phase === 'open') && polls++ < 40) timer = setTimeout(load, 3000);
		} catch (e) {
			if (e instanceof ApiError && e.status === 404) invalid = true;
			else failure = problemMessage(e);
		}
	}

	onMount(() => {
		if (!invalid) load();
	});
	onDestroy(() => clearTimeout(timer));
</script>

<svelte:head><title>{t('shop.orderRef')} · {discovery.storefront ? discovery.brand : discovery.name}</title></svelte:head>

<div class="order-page">
	<header class="container top">
		<a href="/" aria-label={t('shop.backHome')}><Logo size={30} /></a>
		<div class="spacer"></div>
		<LanguagePicker />
	</header>

	<main id="main" class="container">
		<section class="card card-pad box" aria-live="polite">
			{#if !shop}
				<h1>{t('shop.unavailable')}</h1>
				<a class="btn" href="/">{t('shop.backHome')}</a>
			{:else if invalid}
				<span class="icon warn" aria-hidden="true"><Icon name="alert" size={28} /></span>
				<h1>{t('shop.orderRef')}</h1>
				<p class="callout callout-warn" role="alert">{t('shop.orderInvalid')}</p>
				<a class="btn" href="/">{t('shop.backHome')}</a>
			{:else if !order}
				{#if failure}
					<p class="callout callout-error" role="alert">{failure}</p>
					<button class="btn" onclick={load}>{t('shop.orderTryAgain')}</button>
				{:else}
					<span class="spinner" aria-hidden="true"></span>
				{/if}
			{:else}
				{#if phase === 'paid'}
					<span class="icon ok" aria-hidden="true"><Icon name="check" size={28} /></span>
					<h1 data-testid="order-title">{t('shop.orderPaidTitle')}</h1>
				{:else if phase === 'pending'}
					<span class="spinner" aria-hidden="true"></span>
					<h1 data-testid="order-title">{t('shop.orderPendingTitle')}</h1>
					<p class="muted">{t('shop.orderPendingBody')}</p>
				{:else if phase === 'open'}
					<span class="icon warn" aria-hidden="true"><Icon name="alert" size={28} /></span>
					<h1 data-testid="order-title">{t('shop.orderOpenTitle')}</h1>
					<p class="muted">{t('shop.orderOpenBody')}</p>
					{#if order.checkout_url}<a class="btn btn-primary" href={order.checkout_url}>{t('shop.orderPayNow')}</a>{/if}
				{:else}
					<span class="icon warn" aria-hidden="true"><Icon name="alert" size={28} /></span>
					<h1 data-testid="order-title">{t('shop.orderFailedTitle')}</h1>
					<p class="muted">{t('shop.orderFailedBody')}</p>
					<a class="btn btn-primary" href="/shop">{t('shop.orderTryAgain')}</a>
				{/if}

				<div class="ref">
					<span class="label">{t('shop.orderRef')}</span>
					<strong class="ref-code" data-testid="order-reference">{order.reference}</strong>
				</div>
				<ul class="receipt">
					{#each order.items as l (l.product_id)}
						<li><span>{l.quantity} × {l.name}</span><span class="num">{money(l.line_cents)}</span></li>
					{/each}
					<li>
						<span>{t('shop.shipping')}</span>
						<span class="num">{order.shipping_cents === 0 ? t('shop.shippingFree') : money(order.shipping_cents)}</span>
					</li>
					<li class="total"><span>{t('shop.total')}</span><span class="num">{money(order.total_cents)}</span></li>
				</ul>
				<p class="muted small">{t('shop.vatNote', { vat })}</p>
				{#if phase === 'paid'}
					<p>{t('shop.thanksEmail', { email: order.email })}</p>
					<p class="callout callout-ok"><Icon name="info" size={18} />{t('shop.orderPaidNext')}</p>
				{/if}
				<a class="btn btn-ghost" href="/">{t('shop.backHome')}</a>
			{/if}
		</section>
	</main>

	{#if shop}
		<footer class="container foot"><SellerFooter {shop} /></footer>
	{/if}
</div>

<style>
	.order-page {
		min-height: 100dvh;
		display: flex;
		flex-direction: column;
		background: var(--bg);
	}
	.top {
		display: flex;
		align-items: center;
		height: 72px;
	}
	.box {
		max-width: 560px;
		margin: var(--s-6) auto;
		display: grid;
		gap: var(--s-4);
		justify-items: start;
	}
	h1 {
		font-size: 1.7rem;
	}
	.icon {
		width: 52px;
		height: 52px;
		border-radius: 50%;
		display: grid;
		place-items: center;
	}
	.ok {
		background: var(--accent-soft);
		color: var(--accent);
	}
	.warn {
		background: var(--warn-soft);
		color: var(--warn);
	}
	.ref {
		display: grid;
		gap: 2px;
	}
	.ref .label {
		color: var(--muted);
		font-size: 0.9rem;
	}
	.ref-code {
		font-size: 1.4rem;
		letter-spacing: 0.06em;
	}
	.receipt {
		list-style: none;
		padding: 0;
		margin: 0;
		width: 100%;
	}
	.receipt li {
		display: flex;
		justify-content: space-between;
		gap: var(--s-3);
		padding: 6px 0;
		border-bottom: 1px solid var(--border);
	}
	.receipt .total {
		font-weight: 700;
		border-bottom: none;
	}
	.foot {
		margin-top: auto;
		padding-block: var(--s-5);
		border-top: 1px solid var(--border);
	}
</style>
