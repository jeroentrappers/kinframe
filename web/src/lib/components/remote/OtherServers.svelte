<script lang="ts">
	import { discovery } from '$lib/discovery.svelte';
	import { loadOtherServers, parseHost, saveOtherServers } from '$lib/remote';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';

	/**
	 * Multi-server (multi-server.md §2.2, decision A): the web signs in to its own server only. It says the apps show
	 * every server together and keeps the person's own links to other servers' web apps (in this browser only).
	 */
	const store = typeof localStorage !== 'undefined' ? localStorage : null;
	let hosts = $state<string[]>(loadOtherServers(store));
	let adding = $state(false);
	let text = $state('');
	let error = $state<string | null>(null);
	const here = typeof location !== 'undefined' ? location.host : '';

	function add(e: SubmitEvent) {
		e.preventDefault();
		const h = parseHost(text);
		if (!h || h === here) {
			error = t('remote.serverInvalid');
			return;
		}
		hosts = [...hosts.filter((x) => x !== h), h];
		saveOtherServers(store, hosts);
		text = '';
		error = null;
		adding = false;
	}

	function remove(h: string) {
		hosts = hosts.filter((x) => x !== h);
		saveOtherServers(store, hosts);
	}
</script>

<div class="others stack-sm">
	<p class="hint row-gap"><Icon name="server" size={16} /> {t('remote.otherServersHint')}</p>
	<h3 class="label">{t('remote.otherServersTitle')}</h3>
	<p class="hint">{t('remote.otherServersBody', { server: discovery.serverLabel })}</p>
	<ul class="servers" role="list">
		<li><span class="badge">{t('remote.thisServer')}</span> <span class="mono">{here}</span></li>
		{#each hosts as h (h)}
			<li>
				<a class="btn btn-sm btn-ghost" href="https://{h}/" target="_blank" rel="noopener"><Icon name="globe" size={16} />{h}</a>
				<button class="btn btn-ghost btn-icon btn-sm" aria-label={t('remote.removeServer', { name: h })} title={t('remote.removeServer', { name: h })} onclick={() => remove(h)}><Icon name="x" size={14} /></button>
			</li>
		{/each}
	</ul>
	{#if adding}
		<form class="row-wrap" onsubmit={add}>
			<label for="other-server" class="visually-hidden">{t('remote.serverAddress')}</label>
			<input id="other-server" class="input grow" bind:value={text} placeholder="kinframe.example.org" autocomplete="off" spellcheck="false" aria-invalid={Boolean(error)} />
			<button class="btn btn-sm btn-primary" type="submit" disabled={!text.trim()}>{t('remote.addServer')}</button>
		</form>
		{#if error}<p class="error-text small" role="alert">{error}</p>{/if}
	{:else}
		<button class="btn btn-sm btn-soft align-start" onclick={() => (adding = true)}><Icon name="plus" size={16} />{t('remote.addServer')}</button>
	{/if}
</div>

<style>
	.others {
		margin-top: var(--s-4);
		padding-top: var(--s-3);
		border-top: 1px solid var(--border);
	}
	.row-gap {
		display: flex;
		gap: 6px;
		align-items: center;
	}
	.servers {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.servers li {
		display: flex;
		align-items: center;
		gap: 6px;
		overflow-wrap: anywhere;
	}
	.mono {
		font-family: ui-monospace, Menlo, monospace;
		font-size: 0.9rem;
	}
	.grow {
		flex: 1;
		min-width: 180px;
	}
	.align-start {
		align-self: flex-start;
	}
</style>
