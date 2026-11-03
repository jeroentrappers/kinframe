<script lang="ts">
	// The cancel link from the confirmation email. It asks first (mail scanners open links), then cancels.
	import { page } from '$app/state';
	import { api, call, ApiError } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import { problemMessage } from '$lib/problem';
	import Icon from '$components/Icon.svelte';
	import LanguagePicker from '$components/LanguagePicker.svelte';
	import Logo from '$components/Logo.svelte';
	import SellerFooter from '$components/SellerFooter.svelte';

	const token = page.url.searchParams.get('token') ?? '';
	let busy = $state(false);
	let done = $state<string | null>(null);
	let invalid = $state(token.length < 10);
	let failure = $state<string | null>(null);

	async function cancel() {
		busy = true;
		failure = null;
		try {
			const r = await call(api.POST('/v1/shop/reservations/cancel', { body: { token } }));
			done = r.reference;
		} catch (e) {
			if (e instanceof ApiError && e.status === 404) invalid = true;
			else failure = problemMessage(e);
		} finally {
			busy = false;
		}
	}
</script>

<svelte:head><title>{t('shop.cancelTitle')} · {discovery.storefront ? discovery.brand : discovery.name}</title></svelte:head>

<div class="cancel-page">
	<header class="container top">
		<a href="/" aria-label={t('shop.backHome')}><Logo size={30} /></a>
		<div class="spacer"></div>
		<LanguagePicker />
	</header>

	<main id="main" class="container">
		<section class="card card-pad box" aria-live="polite">
			{#if !discovery.shop}
				<h1>{t('shop.unavailable')}</h1>
				<a class="btn" href="/">{t('shop.backHome')}</a>
			{:else if done}
				<span class="icon ok" aria-hidden="true"><Icon name="check" size={28} /></span>
				<h1>{t('shop.cancelledTitle')}</h1>
				<p class="muted">{t('shop.cancelledBody', { reference: done })}</p>
				<a class="btn" href="/">{t('shop.backHome')}</a>
			{:else if invalid}
				<span class="icon warn" aria-hidden="true"><Icon name="alert" size={28} /></span>
				<h1>{t('shop.cancelTitle')}</h1>
				<p class="callout callout-warn" role="alert">{t('shop.cancelInvalid')}</p>
				<a class="btn" href="/">{t('shop.backHome')}</a>
			{:else}
				<h1>{t('shop.cancelTitle')}</h1>
				<p class="muted">{t('shop.cancelBody')}</p>
				{#if failure}<p class="callout callout-error" role="alert">{failure}</p>{/if}
				<div class="row-wrap">
					<button class="btn btn-danger" onclick={cancel} disabled={busy}>
						{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}
						{t('shop.cancelConfirm')}
					</button>
					<a class="btn btn-ghost" href="/">{t('shop.cancelKeep')}</a>
				</div>
			{/if}
		</section>
	</main>

	{#if discovery.shop}
		<footer class="container foot"><SellerFooter shop={discovery.shop} /></footer>
	{/if}
</div>

<style>
	.cancel-page {
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
	.foot {
		margin-top: auto;
		padding-block: var(--s-5);
		border-top: 1px solid var(--border);
	}
</style>
