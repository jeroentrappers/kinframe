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

	const next = $derived(safeNext(page.url.searchParams.get('next')));
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
