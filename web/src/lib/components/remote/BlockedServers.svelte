<script lang="ts" module>
	export type BlockScope = { kind: 'feed'; feedId: string } | { kind: 'operator' };
</script>

<script lang="ts">
	import { api, call } from '$lib/api/client';
	import { relativeTime } from '$lib/format';
	import { problemMessage } from '$lib/problem';
	import { parseHost, type BlockedServer } from '$lib/remote';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	/** Blocked servers for one family (its owner and admins) or for the whole server (operators), README §5.22. */
	let { scope, prefill = $bindable(null), onchange }: { scope: BlockScope; prefill?: string | null; onchange?: () => void } = $props();

	let list = $state<BlockedServer[] | null>(null);

	async function load() {
		try {
			list =
				scope.kind === 'feed'
					? await call(api.GET('/v1/feeds/{feedId}/blocked-servers', { params: { path: { feedId: scope.feedId } } }))
					: await call(api.GET('/v1/operator/blocked-servers'));
		} catch {
			list = [];
		}
	}
	$effect(() => {
		void scope;
		void load();
	});

	let open = $state(false);
	let host = $state('');
	let reason = $state('');
	let error = $state<string | null>(null);
	let busy = $state(false);

	$effect(() => {
		if (prefill) {
			host = prefill;
			reason = '';
			error = null;
			prefill = null;
			open = true;
		}
	});

	async function block(e: SubmitEvent) {
		e.preventDefault();
		const h = parseHost(host);
		if (!h) {
			error = t('remote.serverInvalid');
			return;
		}
		busy = true;
		try {
			const body = { host: h, ...(reason.trim() ? { reason: reason.trim().slice(0, 300) } : {}) };
			const b =
				scope.kind === 'feed'
					? await call(api.POST('/v1/feeds/{feedId}/blocked-servers', { params: { path: { feedId: scope.feedId } }, body }))
					: await call(api.POST('/v1/operator/blocked-servers', { body }));
			list = [b, ...(list ?? []).filter((x) => x.id !== b.id)];
			open = false;
			toasts.success(t('remote.blockedToast', { host: b.host }));
			onchange?.();
		} catch (err) {
			error = problemMessage(err);
		} finally {
			busy = false;
		}
	}

	async function unblock(b: BlockedServer) {
		try {
			if (scope.kind === 'feed') await call(api.DELETE('/v1/feeds/{feedId}/blocked-servers/{blockedServerId}', { params: { path: { feedId: scope.feedId, blockedServerId: b.id } } }));
			else await call(api.DELETE('/v1/operator/blocked-servers/{blockedServerId}', { params: { path: { blockedServerId: b.id } } }));
			list = (list ?? []).filter((x) => x.id !== b.id);
			toasts.success(t('remote.unblockedToast', { host: b.host }));
			onchange?.();
		} catch (err) {
			toasts.error(problemMessage(err));
		}
	}
</script>

<section class="card card-pad stack-sm" aria-labelledby="blocked-{scope.kind}">
	<div class="head">
		<h2 id="blocked-{scope.kind}">{scope.kind === 'feed' ? t('remote.feedBlockedTitle') : t('remote.blockedTitle')}</h2>
		<button class="btn btn-sm" onclick={() => ((host = ''), (reason = ''), (error = null), (open = true))}><Icon name="ban" size={16} />{t('remote.block')}</button>
	</div>
	{#if scope.kind === 'feed'}<p class="hint">{t('remote.feedBlockedHint')}</p>{/if}
	{#if list && list.length === 0}
		<p class="muted small">{t('remote.blockedNone')}</p>
	{:else if list}
		<ul class="list" role="list">
			{#each list as b (b.id)}
				<li class="row-item">
					<div class="who">
						<strong class="mono">{b.host}</strong>
						<span class="muted tiny">
							{#if b.server_name}{b.server_name}{' · '}{/if}{#if b.reason}{b.reason}{' · '}{/if}{relativeTime(b.created_at, i18n.tag)}{#if b.created_by_name}{' · '}{t('people.byName', { name: b.created_by_name })}{/if}
						</span>
					</div>
					<button class="btn btn-sm btn-ghost" onclick={() => unblock(b)}>{t('remote.unblock')}</button>
				</li>
			{/each}
		</ul>
	{/if}
</section>

<Dialog bind:open title={t('remote.block')}>
	<form id="block-{scope.kind}" class="stack" onsubmit={block}>
		<p class="muted small">{scope.kind === 'feed' ? t('remote.blockBody') : t('remote.opBlockBody', { host: parseHost(host) ?? host })}</p>
		<div class="field">
			<label for="block-host-{scope.kind}">{t('remote.blockHost')}</label>
			<input id="block-host-{scope.kind}" class="input" bind:value={host} placeholder="kinframe.example.org" autocomplete="off" spellcheck="false" aria-invalid={Boolean(error)} />
		</div>
		<div class="field">
			<label for="block-reason-{scope.kind}">{t('remote.blockReason')} <span class="faint">({t('common.optional')})</span></label>
			<input id="block-reason-{scope.kind}" class="input" maxlength="300" bind:value={reason} />
		</div>
		{#if error}<p class="error-text small" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" type="submit" form="block-{scope.kind}" disabled={!host.trim() || busy}>{t('remote.block')}</button>
	{/snippet}
</Dialog>

<style>
	.head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-3);
		flex-wrap: wrap;
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
		padding: 8px 0;
	}
	.who {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		overflow-wrap: anywhere;
	}
	.mono {
		font-family: ui-monospace, Menlo, monospace;
	}
</style>
