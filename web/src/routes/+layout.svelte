<script lang="ts">
	import '@fontsource-variable/fraunces';
	import '../app.css';
	import { onMount, type Snippet } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { frames } from '$lib/frames.svelte';
	import { live } from '$lib/live.svelte';
	import { push } from '$lib/push.svelte';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import AppShell from '$components/AppShell.svelte';
	import Toaster from '$components/Toaster.svelte';
	import Logo from '$components/Logo.svelte';
	import Icon from '$components/Icon.svelte';
	import TvMiniBar from '$components/TvMiniBar.svelte';

	let { children }: { children: Snippet } = $props();

	/** Routes that render without a session. Everything else (including /f/{code} and /i/{token})
	 *  bounces to the landing page with ?next= and comes back after sign-in. */
	const PUBLIC = [/^\/$/, /^\/callback$/, /^\/tv\//];
	const isPublic = $derived(PUBLIC.some((r) => r.test(page.url.pathname)));
	/** The TV page (/tv/{frameId}?t=…) is full screen and needs no session: the link's token is the credential. */
	const isTv = $derived(page.url.pathname.startsWith('/tv/'));

	let booting = $state(true);

	async function boot() {
		booting = true;
		const doc = await discovery.load();
		if (doc) await auth.init();
		booting = false;
	}

	onMount(() => {
		document.documentElement.lang = i18n.locale;
		void boot();
		const off = live.on((e) => {
			if (e.type === 'frame.status') frames.setStatus(e.frame_id, e.online, e.last_seen_at);
			else if (e.type === 'membership.updated') void frames.load(true);
			else if (e.type === 'join_request') {
				const name = frames.get(e.frame_id)?.name ?? '';
				toasts.show(t('live.joinRequest', { name: e.request.user?.display_name ?? '?', frame: name }), {
					action: { label: t('common.review'), run: () => goto(`/frames/${e.frame_id}?tab=people`) },
					duration: 9000
				});
			}
		});
		const flush = () => toasts.flush();
		window.addEventListener('pagehide', flush);
		return () => {
			off();
			window.removeEventListener('pagehide', flush);
			live.stop();
		};
	});

	// Session side effects: live socket, frame list, guards.
	$effect(() => {
		if (auth.status === 'signed_in') {
			live.start(() => auth.getToken());
			void frames.load();
			void push.init();
		} else if (auth.status === 'signed_out') {
			live.stop();
			frames.reset();
		}
	});

	$effect(() => {
		if (booting || auth.status !== 'signed_out' || isPublic) return;
		const next = page.url.pathname + page.url.search;
		void goto(`/?next=${encodeURIComponent(next)}`, { replaceState: true });
	});
</script>

{#if booting}
	<div class="splash" aria-busy="true">
		<Logo size={44} />
		<span class="visually-hidden">{t('common.loading')}</span>
	</div>
{:else if discovery.error}
	<div class="splash">
		<div class="card card-pad unreachable">
			<Icon name="wifi" size={28} />
			<h1>{t('boot.unreachableTitle')}</h1>
			<p class="muted">{t('boot.unreachableBody')}</p>
			<button class="btn btn-primary" onclick={boot}><Icon name="refresh" size={18} />{t('common.tryAgain')}</button>
		</div>
	</div>
{:else if isTv}
	{@render children()}
{:else if auth.status === 'signed_in' && page.url.pathname !== '/' && page.url.pathname !== '/callback'}
	<AppShell>{@render children()}</AppShell>
	<TvMiniBar />
{:else if isPublic}
	{@render children()}
{/if}

<Toaster />

<style>
	.splash {
		min-height: 100dvh;
		display: grid;
		place-items: center;
		padding: var(--s-4);
	}
	.splash :global(.logo) {
		animation: breathe 1.8s ease-in-out infinite;
	}
	@keyframes breathe {
		50% {
			opacity: 0.55;
			transform: scale(0.97);
		}
	}
	.unreachable {
		max-width: 420px;
		text-align: center;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--s-3);
	}
	.unreachable h1 {
		font-size: 1.5rem;
	}
</style>
