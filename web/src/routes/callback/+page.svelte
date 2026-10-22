<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { auth } from '$lib/auth.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Logo from '$components/Logo.svelte';
	import Icon from '$components/Icon.svelte';

	let error = $state<string | null>(null);

	onMount(async () => {
		try {
			const next = await auth.handleCallback(location.href);
			await goto(next, { replaceState: true });
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		}
	});
</script>

<svelte:head><title>{t('callback.title')} · kinframe</title></svelte:head>

<main id="main" class="wrap">
	<Logo size={40} />
	{#if error}
		<div class="card card-pad stack" role="alert">
			<h1><Icon name="alert" /> {t('callback.failed')}</h1>
			<p class="muted">{error}</p>
			<a class="btn btn-primary" href="/">{t('callback.back')}</a>
		</div>
	{:else}
		<p class="row muted" role="status"><span class="spinner" aria-hidden="true"></span>{t('callback.working')}</p>
	{/if}
</main>

<style>
	.wrap {
		min-height: 100dvh;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: var(--s-5);
		padding: var(--s-4);
	}
	.card {
		max-width: 420px;
	}
	h1 {
		font-size: 1.3rem;
		display: flex;
		gap: 8px;
		align-items: center;
	}
</style>
