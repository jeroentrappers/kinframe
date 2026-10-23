<script lang="ts">
	// The privacy policy (/privacy) and the terms of use (/terms): public, signed in or not, with their own header
	// like the shop. The texts live in $lib/legal (en, nl, fr; other languages read English).
	import { tick } from 'svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { HOSTED_HOST, linkify, loadLegal, type LegalBlock, type LegalDoc, type LegalKind, type LegalLanguage } from '$lib/legal';
	import Icon from './Icon.svelte';
	import LanguagePicker from './LanguagePicker.svelte';
	import Logo from './Logo.svelte';

	let { kind }: { kind: LegalKind } = $props();

	let doc = $state<LegalDoc | null>(null);
	let lang = $state<LegalLanguage>('en');
	let failed = $state(false);
	let scrolled = false;

	$effect(() => {
		const locale = i18n.locale;
		let stale = false;
		failed = false;
		loadLegal(kind, locale)
			.then(async (r) => {
				if (stale) return;
				doc = r.doc;
				lang = r.lang;
				// The text arrives after the browser looked for #delete: jump there once it is rendered.
				if (!scrolled && location.hash.length > 1) {
					scrolled = true;
					await tick();
					document.getElementById(decodeURIComponent(location.hash.slice(1)))?.scrollIntoView();
				}
			})
			.catch(() => {
				if (!stale) failed = true;
			});
		return () => {
			stale = true;
		};
	});

	const updated = $derived(
		doc ? new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : lang === 'nl' ? 'nl-BE' : 'fr-BE', { dateStyle: 'long' }).format(new Date(`${doc.updated}T12:00:00Z`)) : ''
	);
	const fallback = $derived(doc !== null && lang !== i18n.locale);
	const otherServer = $derived(typeof location !== 'undefined' && location.hostname !== HOSTED_HOST);
	const other = $derived(kind === 'privacy' ? { href: '/terms', label: t('legal.terms') } : { href: '/privacy', label: t('legal.privacy') });
</script>

<svelte:head>
	<title>{doc?.title ?? (kind === 'privacy' ? t('legal.privacy') : t('legal.terms'))} · {discovery.siteName}</title>
</svelte:head>

{#snippet rich(text: string)}
	{#each linkify(text) as s, i (i)}{#if s.href}<a href={s.href} rel={s.href.startsWith('http') ? 'noopener' : undefined}>{s.text}</a>{:else}{s.text}{/if}{/each}
{/snippet}

{#snippet blocks(list: LegalBlock[])}
	{#each list as b, i (i)}
		{#if typeof b === 'string'}
			<p>{@render rich(b)}</p>
		{:else}
			<ul>
				{#each b.list as item, j (j)}<li>{@render rich(item)}</li>{/each}
			</ul>
		{/if}
	{/each}
{/snippet}

<div class="legal-page">
	<header class="container top">
		<a href="/" class="home" aria-label={t('shop.backHome')}><Logo size={30} /></a>
		<div class="spacer"></div>
		<LanguagePicker />
	</header>

	<main id="main" class="container">
		{#if failed}
			<div class="card card-pad center">
				<p>{t('boot.unreachableBody')}</p>
				<a class="btn" href="/">{t('shop.backHome')}</a>
			</div>
		{:else if !doc}
			<div class="loading" aria-busy="true"><span class="spinner" aria-hidden="true"></span><span class="visually-hidden">{t('common.loading')}</span></div>
		{:else}
			<article class="doc" lang={lang} data-testid="legal-{kind}">
				<header class="head">
					<h1>{doc.title}</h1>
					<p class="updated">{t('legal.lastUpdated', { date: updated })}</p>
				</header>

				{#if fallback}
					<p class="callout small" role="note"><Icon name="globe" size={18} /><span lang={i18n.locale}>{t('legal.fallbackNote')}</span></p>
				{/if}
				{#if otherServer}
					<p class="callout callout-warn small" role="note" data-testid="legal-other-server">
						<Icon name="info" size={18} /><span lang={i18n.locale}>{t('legal.otherServer', { host: HOSTED_HOST })}</span>
					</p>
				{/if}

				<div class="intro">{@render blocks(doc.intro)}</div>

				<nav class="toc card card-pad" aria-labelledby="toc-title">
					<h2 id="toc-title" lang={i18n.locale}>{t('legal.contents')}</h2>
					<ol>
						{#each doc.sections as s (s.id)}<li><a href="#{s.id}">{s.title}</a></li>{/each}
					</ol>
				</nav>

				{#each doc.sections as s, i (s.id)}
					<section id={s.id} aria-labelledby="{s.id}-title">
						<h2 id="{s.id}-title"><span class="n" aria-hidden="true">{i + 1}.</span> {s.title}</h2>
						{@render blocks(s.body)}
					</section>
				{/each}
			</article>
		{/if}
	</main>

	<footer class="container foot">
		<nav aria-label={t('legal.links')}><a href={other.href}>{other.label}</a></nav>
		<span class="faint">{t('landing.footer')}</span>
	</footer>
</div>

<style>
	.legal-page {
		min-height: 100dvh;
		display: flex;
		flex-direction: column;
		background:
			radial-gradient(1000px 500px at 90% -10%, color-mix(in srgb, var(--primary-soft) 60%, transparent), transparent 60%),
			var(--bg);
	}
	.top {
		display: flex;
		align-items: center;
		height: 72px;
	}
	.home {
		text-decoration: none;
	}
	main {
		padding-bottom: var(--s-7);
	}
	.center {
		text-align: center;
		display: grid;
		justify-items: center;
		gap: var(--s-4);
	}
	.loading {
		display: grid;
		place-items: center;
		min-height: 40vh;
	}
	.doc {
		max-width: 720px;
		margin-inline: auto;
		display: grid;
		gap: var(--s-4);
		line-height: 1.6;
		overflow-wrap: anywhere;
	}
	.head {
		margin-top: var(--s-4);
		display: grid;
		gap: var(--s-2);
	}
	h1 {
		font-size: clamp(1.9rem, 1.4rem + 2vw, 2.8rem);
		line-height: 1.08;
		letter-spacing: -0.02em;
	}
	.updated {
		color: var(--text-muted);
	}
	.callout {
		margin: 0;
	}
	.intro {
		display: grid;
		gap: var(--s-3);
		font-size: 1.05rem;
	}
	.toc h2 {
		font-size: 1.1rem;
		margin-bottom: var(--s-2);
	}
	.toc ol {
		margin: 0;
		padding-inline-start: 1.4em;
		display: grid;
		gap: 4px;
	}
	section {
		display: grid;
		gap: var(--s-3);
		padding-top: var(--s-4);
		scroll-margin-top: var(--s-4);
	}
	section h2 {
		font-size: 1.35rem;
		line-height: 1.25;
	}
	.n {
		color: var(--text-faint);
	}
	p,
	ul {
		margin: 0;
	}
	ul {
		padding-inline-start: 1.3em;
		display: grid;
		gap: var(--s-2);
	}
	.foot {
		margin-top: auto;
		padding-block: var(--s-5);
		border-top: 1px solid var(--border);
		display: flex;
		flex-wrap: wrap;
		gap: var(--s-2) var(--s-4);
		font-size: 0.85rem;
		color: var(--text-muted);
	}
	.foot a {
		color: inherit;
	}
</style>
