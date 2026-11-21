<script lang="ts">
	import type { Snippet } from 'svelte';
	import { page } from '$app/state';
	import { live } from '$lib/live.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { feeds } from '$lib/feeds.svelte';
	import { familyHref, needsGettingStarted, startFlow, view } from '$lib/family.svelte';
	import { frames } from '$lib/frames.svelte';
	import { t, tn, type MessageKey } from '$lib/i18n/index.svelte';
	import Logo from './Logo.svelte';
	import Icon, { type IconName } from './Icon.svelte';
	import GettingStarted from './family/GettingStarted.svelte';
	import { badge, chats } from '$lib/social.svelte';

	let { children }: { children: Snippet } = $props();

	// The simple family app (docs/spec/simple-app.md §3): Home · Frames · [+] · Chats · Family. `+` opens the composer.
	type Tab = { href: string; match: (path: string) => boolean; label: MessageKey; icon: IconName; badge?: number };
	const under = (prefix: string) => (path: string) => path === prefix || path.startsWith(`${prefix}/`);
	const tabs = $derived<Tab[]>([
		{ href: '/home', match: under('/home'), label: 'nav.home', icon: 'home', badge: badge.badge.threads },
		{ href: '/frames', match: under('/frames'), label: 'nav.frames', icon: 'frame' },
		...(discovery.chats
			? [{ href: '/chats', match: under('/chats'), label: 'nav.chats' as const, icon: 'message' as const, badge: chats.loaded ? chats.unread : badge.badge.conversations }]
			: []),
		{
			href: familyHref(),
			match: (p: string) => under('/family')(p) || under('/feeds')(p) || under('/settings')(p) || under('/history')(p),
			label: 'nav.family',
			icon: 'users'
		}
	]);
	const path = $derived(page.url.pathname);
	const composing = $derived(under('/send')(path));
	const isActive = (tab: Tab) => tab.match(path);

	/**
	 * Getting started (§8): with neither a family nor a frame, the app is the two getting-started choices. Someone on a
	 * frame gets the normal app (Home offers the same choices). Sent history (Ask to keep answers), settings, invites
	 * and pairing always open.
	 */
	const onboarding = $derived(startFlow.step !== null || needsGettingStarted(path, { feedsLoaded: feeds.loaded, families: feeds.list.length, framesKnown: frames.loaded || frames.error !== null, frames: frames.list.length }));
	/** Simple view (§6): one big photo at a time, no tab bar. */
	const bare = $derived(onboarding || (view.simple && under('/home')(path)));
	const phoneTabs = $derived([...tabs.slice(0, 2), null, ...tabs.slice(2)]);
</script>

<a class="skip-link" href="#main">{t('a11y.skip')}</a>

{#if live.status === 'waiting'}
	<span class="reconnecting" role="status"><span class="dot" aria-hidden="true"></span>{t('live.reconnecting')}</span>
{/if}

<div class="shell" class:bare>
	{#if !bare}
		<nav class="sidebar" aria-label={t('a11y.mainNav')}>
			<a href="/home" class="brand" aria-label="kinframe"><Logo size={30} /></a>
			<ul role="list">
				{#each tabs as tab (tab.label)}
					<li>
						<a href={tab.href} class:active={isActive(tab)} aria-current={isActive(tab) ? 'page' : undefined}>
							<Icon name={tab.icon} size={20} stroke={isActive(tab) ? 2.3 : 1.8} />
							<span>{t(tab.label)}</span>
							{#if tab.badge}<span class="nav-badge num" aria-label={tn('nav.unread', tab.badge)}>{tab.badge > 99 ? '99+' : tab.badge}</span>{/if}
						</a>
					</li>
				{/each}
			</ul>
			<a href="/send" class="btn btn-primary share-cta" class:active={composing} aria-current={composing ? 'page' : undefined}>
				<Icon name="plus" size={20} />{t('nav.share')}
			</a>
		</nav>
	{/if}

	<main id="main" tabindex="-1">
		{#if onboarding}
			<GettingStarted />
		{:else}
			{@render children()}
		{/if}
	</main>
</div>

{#if !bare}
	<nav class="tabbar" aria-label={t('a11y.mainNav')} style="--tabs:{phoneTabs.length}">
		{#each phoneTabs as tab, i (tab?.label ?? `plus-${i}`)}
			{#if tab}
				<a href={tab.href} class:active={isActive(tab)} aria-current={isActive(tab) ? 'page' : undefined}>
					<span class="tab-icon"
						><Icon name={tab.icon} size={22} stroke={isActive(tab) ? 2.3 : 1.8} />{#if tab.badge}<span class="tab-badge num" aria-label={tn('nav.unread', tab.badge)}
								>{tab.badge > 99 ? '99+' : tab.badge}</span
							>{/if}</span
					>
					<span class="tab-label">{t(tab.label)}</span>
				</a>
			{:else}
				<a href="/send" class="plus" aria-label={t('nav.share')} aria-current={composing ? 'page' : undefined}>
					<span class="plus-btn"><Icon name="plus" size={26} stroke={2.4} /></span>
				</a>
			{/if}
		{/each}
	</nav>
{/if}

<style>
	.shell {
		min-height: 100dvh;
	}
	.sidebar {
		display: none;
	}
	main {
		outline: none;
		padding-bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom));
	}
	.bare main {
		padding-bottom: 0;
	}
	.reconnecting {
		position: fixed;
		z-index: 40;
		top: 8px;
		left: 50%;
		transform: translateX(-50%);
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 4px 12px;
		border-radius: var(--r-full);
		background: var(--surface);
		box-shadow: var(--shadow-sm);
		font-size: 0.8rem;
		color: var(--warn);
		font-weight: 600;
	}
	.reconnecting .dot {
		background: var(--warn);
	}
	.nav-badge,
	.tab-badge {
		min-width: 18px;
		height: 18px;
		padding: 0 5px;
		border-radius: var(--r-full);
		background: var(--primary);
		color: var(--on-primary);
		font-size: 0.68rem;
		font-weight: 800;
		display: inline-grid;
		place-items: center;
		line-height: 1;
	}
	.tabbar {
		position: fixed;
		z-index: 30;
		left: 0;
		right: 0;
		bottom: 0;
		display: grid;
		grid-template-columns: repeat(var(--tabs, 5), 1fr);
		height: calc(var(--tabbar-h) + env(safe-area-inset-bottom));
		padding-bottom: env(safe-area-inset-bottom);
		background: color-mix(in srgb, var(--surface) 97%, transparent);
		backdrop-filter: saturate(1.4) blur(14px);
		-webkit-backdrop-filter: saturate(1.4) blur(14px);
		border-top: 1px solid var(--border);
	}
	.tabbar a {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 3px;
		color: var(--text-faint);
		text-decoration: none;
		font-size: 0.72rem;
		font-weight: 600;
		border-radius: 12px;
	}
	.tab-icon {
		position: relative;
		display: grid;
		place-items: center;
		width: 56px;
		height: 30px;
		border-radius: var(--r-full);
		transition: background var(--dur) var(--ease);
	}
	.tab-badge {
		position: absolute;
		top: -2px;
		inset-inline-end: 6px;
		box-shadow: 0 0 0 2px var(--surface);
	}
	.tabbar a.active {
		color: var(--primary-soft-text);
	}
	.tabbar a.active .tab-icon {
		background: var(--primary-soft);
	}
	.plus-btn {
		display: grid;
		place-items: center;
		width: 52px;
		height: 44px;
		border-radius: 16px;
		background: var(--primary);
		color: var(--on-primary);
		box-shadow: var(--shadow);
		transition: background var(--dur) var(--ease);
	}
	.tabbar a.plus:hover .plus-btn {
		background: var(--primary-hover);
	}
	@media (min-width: 860px) {
		.shell:not(.bare) {
			display: grid;
			grid-template-columns: 232px 1fr;
		}
		.sidebar {
			position: sticky;
			top: 0;
			height: 100dvh;
			display: flex;
			flex-direction: column;
			gap: var(--s-4);
			padding: var(--s-5) var(--s-3);
			border-inline-end: 1px solid var(--border);
			background: color-mix(in srgb, var(--surface) 60%, var(--bg));
		}
		.brand {
			padding: 0 var(--s-3);
			border-radius: 10px;
			text-decoration: none;
			align-self: flex-start;
		}
		.sidebar ul {
			list-style: none;
			margin: 0;
			padding: 0;
			display: flex;
			flex-direction: column;
			gap: 2px;
		}
		.sidebar ul a {
			display: flex;
			align-items: center;
			gap: 12px;
			padding: 10px 14px;
			border-radius: var(--r);
			color: var(--text-muted);
			text-decoration: none;
			font-weight: 600;
			transition:
				background var(--dur) var(--ease),
				color var(--dur) var(--ease);
		}
		.sidebar ul a span:first-of-type {
			flex: 1;
		}
		.sidebar ul a:hover {
			background: var(--surface-2);
			color: var(--text);
		}
		.sidebar ul a.active {
			background: var(--surface);
			color: var(--text);
			box-shadow: var(--shadow-sm);
		}
		.share-cta {
			justify-content: center;
		}
		.tabbar {
			display: none;
		}
		main {
			padding-bottom: 0;
			min-width: 0;
		}
	}
</style>
