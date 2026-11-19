<script lang="ts">
	import { api, call } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { relativeTime } from '$lib/format';
	import { serverName, type RemoteServer } from '$lib/remote';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Icon from '$components/Icon.svelte';
	import BlockedServers from '$components/remote/BlockedServers.svelte';

	// Operator: known servers and the instance-wide block list (README §5.22 "Trust settings").
	const allowed = $derived(discovery.remoteFamilies && auth.me?.is_operator === true);
	let servers = $state<RemoteServer[] | null>(null);
	let prefill = $state<string | null>(null);

	async function load() {
		try {
			servers = await call(api.GET('/v1/operator/remote-servers'));
		} catch {
			servers = [];
		}
	}
	$effect(() => {
		if (allowed) void load();
	});
</script>

<svelte:head><title>{t('remote.opTitle')} · kinframe</title></svelte:head>

<div class="container page narrow">
	<a href="/settings" class="back"><Icon name="chevronLeft" size={18} />{t('settings.title')}</a>
	<div class="page-header">
		<div>
			<h1>{t('remote.opTitle')}</h1>
			<p class="lede">{t('remote.opLede')}</p>
		</div>
	</div>

	{#if !allowed}
		<p class="callout"><Icon name="info" size={18} />{t('chat.operatorOnly')}</p>
	{:else}
		<div class="stack">
			<section class="card card-pad stack-sm" aria-labelledby="srv-known">
				<h2 id="srv-known">{t('remote.opKnown')}</h2>
				{#if servers && servers.length === 0}
					<p class="muted small">{t('remote.opKnownNone')}</p>
				{:else if servers}
					<ul class="list" role="list">
						{#each servers as s (s.server_url)}
							<li class="row-item">
								<div class="who">
									<strong>{serverName(s)} {#if s.blocked}<span class="badge">{t('remote.blocked')}</span>{/if}</strong>
									<span class="muted small mono">{s.host ?? s.server_url}</span>
									<span class="muted tiny">
										{t('remote.opSubscribers', { n: s.subscribers ?? 0 })} · {t('remote.opFollows', { n: s.follows ?? 0 })} · {s.last_contact_at ? t('remote.lastContact', { when: relativeTime(s.last_contact_at, i18n.tag) }) : t('remote.never')}
									</span>
									{#if s.last_error}<span class="tiny error-text">{s.last_error}</span>{/if}
								</div>
								{#if !s.blocked}
									<button class="btn btn-sm btn-danger-ghost" onclick={() => (prefill = s.host ?? s.server_url)}><Icon name="ban" size={16} />{t('remote.block')}</button>
								{/if}
							</li>
						{/each}
					</ul>
				{/if}
			</section>
			<BlockedServers scope={{ kind: 'operator' }} bind:prefill onchange={load} />
		</div>
	{/if}
</div>

<style>
	.narrow {
		max-width: 760px;
	}
	.back {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--text-muted);
		text-decoration: none;
		font-weight: 600;
		margin-bottom: var(--s-4);
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
	}
	.list > li + li {
		border-top: 1px solid var(--border);
	}
	.row-item {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 10px 0;
		flex-wrap: wrap;
	}
	.who {
		flex: 1;
		min-width: 200px;
		display: flex;
		flex-direction: column;
		overflow-wrap: anywhere;
	}
	.mono {
		font-family: ui-monospace, Menlo, monospace;
	}
</style>
