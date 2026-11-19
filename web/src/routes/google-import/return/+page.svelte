<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { BACK_KEY, CHANNEL, backUrl, importMessage, parseReturn, readPickerMessage } from '$lib/googleImport';
	import { t } from '$lib/i18n/index.svelte';
	import Logo from '$components/Logo.svelte';

	// Google's consent comes back here (`return_uri`, photo-sources.md §7.3). In the popup: tell the page that
	// started the import (BroadcastChannel, and `window.opener` when Google's isolation left it), which answers with
	// the picker to go on to. When nobody answers (consent ran in this tab because popups were blocked, or the page
	// was closed): go back to the page that started it, which picks the import up again.
	const ret = parseReturn(page.url.searchParams);
	let state = $state<'working' | 'failed' | 'invalid'>(ret ? (ret.status === 'failed' ? 'failed' : 'working') : 'invalid');

	function storedBack(): string | null {
		try {
			const v = sessionStorage.getItem(BACK_KEY);
			sessionStorage.removeItem(BACK_KEY);
			return v;
		} catch {
			return null;
		}
	}

	onMount(() => {
		if (!ret) return;
		const back = storedBack();
		const msg = importMessage(ret.importId, ret.status);
		const channel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(CHANNEL) : null;
		let answered = false;
		const onAnswer = (data: unknown) => {
			const url = readPickerMessage(data, ret.importId);
			if (url === undefined || answered) return;
			answered = true;
			if (url) location.replace(url);
			else window.close();
		};
		const onWindow = (ev: MessageEvent) => {
			if (ev.origin === location.origin) onAnswer(ev.data);
		};
		channel?.addEventListener('message', (ev) => onAnswer(ev.data));
		window.addEventListener('message', onWindow);
		channel?.postMessage(msg);
		try {
			if (window.opener && !window.opener.closed) (window.opener as Window).postMessage(msg, location.origin);
		} catch {
			/* opener gone */
		}
		// A same-tab consent stored where to go back to: a short wait is enough to know no popup opener answers.
		const timer = setTimeout(
			() => {
				if (!answered) void goto(backUrl(back, ret.importId), { replaceState: true });
			},
			back ? 1500 : 6000
		);
		return () => {
			clearTimeout(timer);
			window.removeEventListener('message', onWindow);
			channel?.close();
		};
	});
</script>

<svelte:head><title>Google Photos · kinframe</title></svelte:head>

<main class="ret">
	<Logo size={40} />
	{#if state === 'invalid'}
		<p>{t('gimport.returnInvalid')}</p>
		<a class="btn" href="/">{t('gimport.backToApp')}</a>
	{:else if state === 'failed'}
		<p>{t('gimport.returnFailed')}</p>
		<button class="btn" onclick={() => window.close()}>{t('common.close')}</button>
	{:else}
		<p class="row"><span class="spinner" aria-hidden="true"></span>{t('gimport.returnWorking')}</p>
	{/if}
</main>

<style>
	.ret {
		min-height: 100dvh;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: var(--s-4);
		padding: var(--s-5);
		text-align: center;
	}
	.row {
		display: inline-flex;
		align-items: center;
		gap: 10px;
	}
</style>
