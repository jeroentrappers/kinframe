<script lang="ts">
	import type { Snippet } from 'svelte';
	import { page } from '$app/state';
	import { auth } from '$lib/auth.svelte';
	import { live } from '$lib/live.svelte';
	import { t, type MessageKey } from '$lib/i18n/index.svelte';
	import Logo from './Logo.svelte';
	import Icon, { type IconName } from './Icon.svelte';
	import Avatar from './Avatar.svelte';

	let { children }: { children: Snippet } = $props();

	const tabs: { href: string; label: MessageKey; icon: IconName }[] = [
		{ href: '/frames', label: 'nav.frames', icon: 'frame' },
		{ href: '/send', label: 'nav.send', icon: 'send' },
		{ href: '/history', label: 'nav.history', icon: 'history' },
		{ href: '/settings', label: 'nav.settings', icon: 'settings' }
	];

	const isActive = (href: string) => page.url.pathname === href || page.url.pathname.startsWith(`${href}/`);
</script>

<a class="skip-link" href="#main">{t('a11y.skip')}</a>

<header class="topbar">
	<div class="container bar">
		<a href="/frames" class="brand" aria-label="kinframe">
			<Logo size={30} />
		</a>
		<nav class="desktop-nav" aria-label={t('a11y.mainNav')}>
			{#each tabs.slice(0, 3) as tab}
				<a href={tab.href} class:active={isActive(tab.href)} aria-current={isActive(tab.href) ? 'page' : undefined}>
					<Icon name={tab.icon} size={18} />
					{t(tab.label)}
				</a>
			{/each}
		</nav>
		<div class="spacer"></div>
		{#if live.status === 'waiting'}
			<span class="reconnecting" role="status">
				<span class="dot" aria-hidden="true"></span>{t('live.reconnecting')}
			</span>
		{/if}
		{#if !isActive('/send')}
			<a href="/send" class="btn btn-primary btn-sm send-cta">
				<Icon name="plus" size={18} />
				{t('nav.sendPhotos')}
			</a>
		{/if}
		<a
			href="/settings"
			class="me"
			class:active={isActive('/settings')}
			aria-label={t('nav.settings')}
			aria-current={isActive('/settings') ? 'page' : undefined}
		>
			<Avatar name={auth.me?.display_name ?? ''} url={auth.me?.avatar_url} size={34} />
		</a>
	</div>
</header>

<main id="main" tabindex="-1">
	{@render children()}
</main>

<nav class="tabbar" aria-label={t('a11y.mainNav')}>
	{#each tabs as tab}
		<a href={tab.href} class:active={isActive(tab.href)} aria-current={isActive(tab.href) ? 'page' : undefined}>
			<span class="tab-icon"><Icon name={tab.icon} size={22} stroke={isActive(tab.href) ? 2.3 : 1.8} /></span>
			<span class="tab-label">{t(tab.label)}</span>
		</a>
	{/each}
</nav>

<style>
	.topbar {
		position: sticky;
		top: 0;
		z-index: 30;
		background: color-mix(in srgb, var(--bg) 97%, transparent);
		backdrop-filter: saturate(1.4) blur(12px);
		-webkit-backdrop-filter: saturate(1.4) blur(12px);
		border-bottom: 1px solid color-mix(in srgb, var(--border) 70%, transparent);
	}
	.bar {
		height: var(--nav-h);
		display: flex;
		align-items: center;
		gap: var(--s-4);
	}
	.brand {
		text-decoration: none;
		border-radius: 10px;
	}
	.desktop-nav {
		display: none;
		gap: 2px;
		margin-inline-start: var(--s-4);
	}
	.desktop-nav a {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 8px 14px;
		border-radius: var(--r-full);
		color: var(--text-muted);
		text-decoration: none;
		font-weight: 600;
		font-size: 0.95rem;
		transition:
			background var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.desktop-nav a:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	.desktop-nav a.active {
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-sm);
	}
	.send-cta {
		display: none;
	}
	.me {
		border-radius: 50%;
		display: none;
		text-decoration: none;
	}
	.me.active :global(.avatar) {
		box-shadow: 0 0 0 2px var(--primary);
	}
	.reconnecting {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-size: 0.8rem;
		color: var(--warn);
		font-weight: 600;
	}
	.reconnecting .dot {
		background: var(--warn);
	}
	main {
		outline: none;
		padding-bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom));
	}
	.tabbar {
		position: fixed;
		z-index: 30;
		left: 0;
		right: 0;
		bottom: 0;
		display: grid;
		grid-template-columns: repeat(4, 1fr);
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
		display: grid;
		place-items: center;
		width: 56px;
		height: 30px;
		border-radius: var(--r-full);
		transition: background var(--dur) var(--ease);
	}
	.tabbar a.active {
		color: var(--primary-soft-text);
	}
	.tabbar a.active .tab-icon {
		background: var(--primary-soft);
	}
	@media (min-width: 860px) {
		.desktop-nav,
		.send-cta,
		.me {
			display: inline-flex;
		}
		.tabbar {
			display: none;
		}
		main {
			padding-bottom: 0;
		}
	}
</style>
