<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { auth, safeNext } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Logo from '$components/Logo.svelte';
	import Icon from '$components/Icon.svelte';
	import Illustration from '$components/Illustration.svelte';
	import LanguagePicker from '$components/LanguagePicker.svelte';
	import SellerFooter from '$components/SellerFooter.svelte';
	import ShopSection from '$components/ShopSection.svelte';
	import Storefront from '$components/Storefront.svelte';

	// The store apps can sign in to any kinframe server, so every server links to them.
	const APP_STORE_URL = 'https://apps.apple.com/app/id6833055734';
	const PLAY_URL = 'https://play.google.com/store/apps/details?id=app.kinframe.family';

	// Home (the timeline) is the landing page for signed-in people once the server has family social.
	const next = $derived(page.url.searchParams.get('next') ? safeNext(page.url.searchParams.get('next')) : discovery.social ? '/home' : '/frames');
	const reason = $derived(next.startsWith('/f/') ? 'frame' : next.startsWith('/i/') ? 'invite' : null);

	$effect(() => {
		if (auth.status === 'signed_in') void goto(next, { replaceState: true });
	});

	let signingIn = $state(false);
	let signInError = $state<string | null>(null);

	async function signIn() {
		signingIn = true;
		signInError = null;
		try {
			await auth.signIn(next);
		} catch (e) {
			signInError = e instanceof Error ? e.message : String(e);
			signingIn = false;
		}
	}

	let devEmail = $state('');
	let devName = $state('');
	let devBusy = $state(false);
	let devError = $state<string | null>(null);

	async function devSignIn(e: SubmitEvent) {
		e.preventDefault();
		devBusy = true;
		devError = null;
		try {
			await auth.devSignIn(devEmail.trim(), devName.trim() || devEmail.split('@')[0]!);
			await goto(next, { replaceState: true });
		} catch (err) {
			devError = err instanceof Error ? err.message : String(err);
		} finally {
			devBusy = false;
		}
	}
</script>

<svelte:head>
	{#if !discovery.storefront}<title>kinframe · {discovery.name}</title>{/if}
</svelte:head>

{#if discovery.storefront && discovery.shop}
	<Storefront shop={discovery.shop} />
{:else}
<div class="landing">
	<header class="container top">
		<Logo size={32} />
		<div class="spacer"></div>
		{#if discovery.shop}
			<a class="btn btn-ghost btn-sm shop-link" href="#shop">{t('shop.title', { brand: discovery.brand })}</a>
		{/if}
		<LanguagePicker />
	</header>

	<main id="main" class="container hero">
		<section class="copy">
			<p class="server-chip">
				<span class="dot dot-online" aria-hidden="true"></span>
				{t('landing.server', { name: discovery.name })}
			</p>
			<h1>{t('landing.title')}</h1>
			<p class="lede">{t('landing.lede')}</p>

			{#if auth.expired}
				<div class="callout callout-warn" role="status"><Icon name="clock" />{t('landing.expired')}</div>
			{:else if reason === 'frame'}
				<div class="callout" role="status"><Icon name="frame" />{t('landing.reasonFrame')}</div>
			{:else if reason === 'invite'}
				<div class="callout" role="status"><Icon name="users" />{t('landing.reasonInvite')}</div>
			{/if}

			<div class="actions">
				{#if discovery.logtoConfigured}
					<button class="btn btn-primary btn-lg" onclick={signIn} disabled={signingIn}>
						{#if signingIn}<span class="spinner" aria-hidden="true"></span>{/if}
						{t('landing.signIn')}
					</button>
					<p class="hint">{t('landing.signInHint')}</p>
				{/if}
				{#if signInError}<p class="error-text" role="alert">{signInError}</p>{/if}
				<div class="store-badges">
					<a class="store-badge" href={APP_STORE_URL} target="_blank" rel="noopener">
						<svg viewBox="0 0 384 512" width="18" height="18" aria-hidden="true" fill="currentColor">
							<path d="M318.7 268.7c-.2-36.7 16.4-64.4 50-84.8-18.8-26.9-47.2-41.7-84.7-44.6-35.5-2.8-74.3 20.7-88.5 20.7-15 0-49.4-19.7-76.4-19.7C63.3 141.2 4 184.8 4 273.5q0 39.3 14.4 81.2c12.8 36.7 59 126.7 107.2 125.2 25.2-.6 43-17.9 75.8-17.9 31.8 0 48.3 17.9 76.4 17.9 48.6-.7 90.4-82.5 102.6-119.3-65.2-30.7-61.7-90-61.7-91.9zm-56.6-164.2c27.3-32.4 24.8-61.9 24-72.5-24.1 1.4-52 16.4-67.9 34.9-17.5 19.8-27.8 44.3-25.6 71.9 26.1 2 49.9-11.4 69.5-34.3z" />
						</svg>
						{t('landing.appStore')}
					</a>
					<a class="store-badge" href={PLAY_URL} target="_blank" rel="noopener">
						<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="currentColor">
							<path d="M3.6 1.8c-.3.3-.5.8-.5 1.4v17.6c0 .6.2 1.1.5 1.4l.1.1 9.9-9.9v-.2L3.7 1.7l-.1.1zm13.3 13.9-3.3-3.3v-.2l3.3-3.3.1.1 3.9 2.2c1.1.6 1.1 1.7 0 2.3l-3.9 2.2h-.1zm.1-.1L13.6 12 3.6 22c.4.4 1 .4 1.7.1L17 15.6M17 8.4 5.3 1.8C4.6 1.4 4 1.5 3.6 1.9l10 10L17 8.4z" />
						</svg>
						{t('landing.googlePlay')}
					</a>
				</div>
			</div>

			{#if discovery.devAuth}
				<form class="card card-pad dev" onsubmit={devSignIn} aria-labelledby="dev-title">
					<div class="row">
						<span class="badge">DEV</span>
						<h2 id="dev-title">{t('landing.devTitle')}</h2>
					</div>
					<p class="hint">{t('landing.devHint')}</p>
					<div class="dev-grid">
						<div class="field">
							<label for="dev-email">{t('landing.devEmail')}</label>
							<input id="dev-email" class="input" type="email" required autocomplete="email" bind:value={devEmail} placeholder="oma@example.org" />
						</div>
						<div class="field">
							<label for="dev-name">{t('landing.devName')}</label>
							<input id="dev-name" class="input" type="text" autocomplete="name" maxlength="40" bind:value={devName} placeholder="Oma" />
						</div>
					</div>
					{#if devError}<p class="error-text" role="alert">{devError}</p>{/if}
					<button class="btn btn-soft" type="submit" disabled={devBusy}>
						{#if devBusy}<span class="spinner" aria-hidden="true"></span>{/if}
						{t('landing.devSubmit')}
					</button>
				</form>
			{:else if !discovery.logtoConfigured}
				<div class="callout callout-warn"><Icon name="alert" />{t('landing.noAuth')}</div>
			{/if}
		</section>

		<div class="art" aria-hidden="true">
			<Illustration kind="hero" />
		</div>
	</main>

	<section class="container features" aria-label={t('landing.featuresLabel')}>
		<article>
			<span class="f-icon"><Icon name="send" size={22} /></span>
			<h2>{t('landing.f1Title')}</h2>
			<p class="muted">{t('landing.f1Body')}</p>
		</article>
		<article>
			<span class="f-icon"><Icon name="heart" size={22} /></span>
			<h2>{t('landing.f2Title')}</h2>
			<p class="muted">{t('landing.f2Body')}</p>
		</article>
		<article>
			<span class="f-icon"><Icon name="shield" size={22} /></span>
			<h2>{t('landing.f3Title')}</h2>
			<p class="muted">{t('landing.f3Body')}</p>
		</article>
	</section>

	{#if discovery.shop}
		<ShopSection shop={discovery.shop} />
	{/if}

	<footer class="container foot">
		{#if discovery.shop}<SellerFooter shop={discovery.shop} />{/if}
		<p class="software">
			<span>{t('landing.footer')}</span>
			{#if discovery.doc?.version}<span class="faint">· v{discovery.doc.version}</span>{/if}
		</p>
		<nav class="legal-links" aria-label={t('legal.links')}>
			<a href="/privacy">{t('legal.privacy')}</a>
			<a href="/terms">{t('legal.terms')}</a>
		</nav>
	</footer>
</div>
{/if}

<style>
	.landing {
		min-height: 100dvh;
		display: flex;
		flex-direction: column;
		background:
			radial-gradient(1200px 600px at 85% -10%, color-mix(in srgb, var(--primary-soft) 70%, transparent), transparent 60%),
			var(--bg);
	}
	.top {
		display: flex;
		align-items: center;
		height: 72px;
	}
	.hero {
		display: grid;
		grid-template-columns: 1fr;
		gap: var(--s-6);
		align-items: center;
		padding-block: var(--s-5) var(--s-7);
	}
	@media (min-width: 900px) {
		.hero {
			grid-template-columns: 1.1fr 0.9fr;
			padding-block: var(--s-7) var(--s-8);
		}
	}
	.copy {
		display: flex;
		flex-direction: column;
		gap: var(--s-4);
		max-width: 560px;
	}
	.server-chip {
		align-self: flex-start;
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 6px 12px;
		border-radius: var(--r-full);
		background: var(--surface);
		border: 1px solid var(--border);
		font-size: 0.85rem;
		font-weight: 600;
		color: var(--text-muted);
	}
	h1 {
		font-size: clamp(2.1rem, 1.5rem + 2.6vw, 3.4rem);
		line-height: 1.05;
		letter-spacing: -0.025em;
	}
	.lede {
		font-size: 1.12rem;
		color: var(--text-muted);
		max-width: 46ch;
	}
	.actions {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: var(--s-2);
		margin-top: var(--s-2);
	}
	.store-badges {
		display: flex;
		flex-wrap: wrap;
		gap: var(--s-2);
		margin-top: var(--s-2);
	}
	.store-badge {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 9px 16px;
		border-radius: var(--r);
		background: var(--text);
		color: var(--bg);
		font-weight: 600;
		font-size: 0.95rem;
		text-decoration: none;
	}
	.store-badge:hover {
		opacity: 0.88;
	}
	.store-badge:focus-visible {
		outline: 2px solid var(--primary);
		outline-offset: 2px;
	}
	.dev {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		border-style: dashed;
	}
	.dev h2 {
		font-size: 1.05rem;
	}
	.dev-grid {
		display: grid;
		gap: var(--s-3);
	}
	@media (min-width: 520px) {
		.dev-grid {
			grid-template-columns: 1fr 1fr;
		}
	}
	.dev button {
		align-self: flex-start;
	}
	.art {
		max-width: 420px;
		width: 56%;
		justify-self: center;
		order: -1;
	}
	@media (min-width: 900px) {
		.art {
			order: 0;
			width: 100%;
		}
	}
	.features {
		display: grid;
		gap: var(--s-4);
		padding-bottom: var(--s-7);
	}
	@media (min-width: 760px) {
		.features {
			grid-template-columns: repeat(3, 1fr);
		}
	}
	.features article {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		padding: var(--s-5);
		border-radius: var(--r-lg);
		background: color-mix(in srgb, var(--surface) 70%, transparent);
		border: 1px solid var(--border);
	}
	.features h2 {
		font-size: 1.1rem;
	}
	.f-icon {
		width: 42px;
		height: 42px;
		border-radius: 12px;
		display: grid;
		place-items: center;
		background: var(--primary-soft);
		color: var(--primary-soft-text);
		margin-bottom: var(--s-1);
	}
	.foot {
		margin-top: auto;
		padding-block: var(--s-5);
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		font-size: 0.85rem;
		color: var(--text-muted);
		border-top: 1px solid var(--border);
	}
	.software {
		display: flex;
		gap: 6px;
	}
	.legal-links {
		display: flex;
		flex-wrap: wrap;
		gap: var(--s-4);
	}
	.legal-links a {
		color: inherit;
	}
	.shop-link {
		margin-inline-end: var(--s-2);
	}
	@media (max-width: 520px) {
		.shop-link {
			display: none;
		}
	}
</style>
