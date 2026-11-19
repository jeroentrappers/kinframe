<script lang="ts">
	import { api, call, type Schemas } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { formatDateTime } from '$lib/format';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Dialog from '$components/Dialog.svelte';
	import Icon from '$components/Icon.svelte';

	// Operator: ownership transfers and the log (leaving.md §4.6, README §5.21).
	type Scope = Schemas['OperatorScope'];
	type Entry = Schemas['OwnershipLogEntry'];

	const allowed = $derived(discovery.leaving && auth.me?.is_operator === true);
	let q = $state('');
	let searching = $state(false);
	let matches = $state<Schemas['OperatorOwnershipMatches'] | null>(null);
	let error = $state<unknown>(null);

	async function search(e?: SubmitEvent) {
		e?.preventDefault();
		if (q.trim().length < 2) return;
		searching = true;
		error = null;
		try {
			matches = await call(api.GET('/v1/operator/ownership/search', { params: { query: { q: q.trim() } } }));
		} catch (err) {
			error = err;
		} finally {
			searching = false;
		}
	}

	// ---- log
	let log = $state<Entry[]>([]);
	let cursor = $state<string | null>(null);
	async function loadLog(more = false) {
		try {
			const page = await call(api.GET('/v1/operator/ownership-log', { params: { query: { limit: 30, ...(more && cursor ? { cursor } : {}) } } }));
			log = more ? [...log, ...page.entries] : page.entries;
			cursor = page.next_cursor;
		} catch {
			if (!more) log = [];
		}
	}
	$effect(() => {
		if (allowed) void loadLog();
	});

	// ---- transfer
	let target = $state<Scope | null>(null);
	let open = $state(false);
	let newOwner = $state('');
	let reason = $state('');
	let removePrevious = $state(false);
	let busy = $state(false);
	const owner = $derived(target?.members.find((m) => m.role === 'owner') ?? null);
	const candidates = $derived((target?.members ?? []).filter((m) => m.role !== 'owner'));

	function start(s: Scope) {
		target = s;
		newOwner = '';
		reason = '';
		removePrevious = false;
		open = true;
	}

	async function transfer() {
		const s = target;
		if (!s || !newOwner || !reason.trim()) return;
		busy = true;
		try {
			const body = { user_id: newOwner, reason: reason.trim().slice(0, 500), remove_previous: removePrevious };
			if (s.kind === 'frame') await call(api.POST('/v1/operator/frames/{frameId}/owner', { params: { path: { frameId: s.id } }, body }));
			else await call(api.POST('/v1/operator/feeds/{feedId}/owner', { params: { path: { feedId: s.id } }, body }));
			open = false;
			toasts.success(t('leaving.opDone', { name: s.members.find((m) => m.user_id === newOwner)?.display_name ?? '', scope: s.name }));
			void search();
			void loadLog();
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			busy = false;
		}
	}
</script>

<svelte:head><title>{t('leaving.opTitle')} · kinframe</title></svelte:head>

{#snippet scopeList(title: string, list: Scope[])}
	{#if list.length > 0}
		<section class="card card-pad stack-sm">
			<h2 class="h3">{title}</h2>
			<ul class="list" role="list">
				{#each list as s (s.id)}
					<li class="row-item">
						<div class="who">
							<strong>{s.name}</strong>
							<span class="muted tiny">
								{s.members
									.slice(0, 6)
									.map((m) => (m.role === 'owner' ? `${m.display_name} (${t('leaving.opOwner')})` : m.display_name))
									.join(', ')}{#if s.members.length > 6} …{/if}
							</span>
						</div>
						<button class="btn btn-sm" onclick={() => start(s)} disabled={s.members.length < 2}><Icon name="crown" size={16} />{t('leaving.opTransfer')}</button>
					</li>
				{/each}
			</ul>
		</section>
	{/if}
{/snippet}

<div class="container page narrow">
	<a href="/settings" class="back"><Icon name="chevronLeft" size={18} />{t('settings.title')}</a>
	<div class="page-header">
		<div>
			<h1>{t('leaving.opTitle')}</h1>
			<p class="lede">{t('leaving.opLede')}</p>
		</div>
	</div>

	{#if !allowed}
		<p class="callout"><Icon name="info" size={18} />{t('chat.operatorOnly')}</p>
	{:else}
		<div class="stack">
			<form class="row-wrap" onsubmit={search} role="search">
				<label for="own-q" class="visually-hidden">{t('leaving.opSearch')}</label>
				<input id="own-q" class="input grow" bind:value={q} minlength="2" maxlength="200" placeholder={t('leaving.opSearch')} />
				<button class="btn btn-primary" type="submit" disabled={q.trim().length < 2 || searching}><Icon name="search" size={16} />{t('leaving.opSearchButton')}</button>
			</form>
			{#if error}<p class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{problemMessage(error)}</p>{/if}
			{#if matches}
				{#if matches.frames.length === 0 && matches.feeds.length === 0}
					<p class="muted small">{t('leaving.opNone')}</p>
				{/if}
				{@render scopeList(t('leaving.opFrames'), matches.frames)}
				{@render scopeList(t('leaving.opFeeds'), matches.feeds)}
			{/if}

			<section class="card card-pad stack-sm" aria-labelledby="own-log">
				<h2 id="own-log" class="h3">{t('leaving.opLog')}</h2>
				{#if log.length === 0}
					<p class="muted small">{t('leaving.opLogEmpty')}</p>
				{:else}
					<ul class="list" role="list">
						{#each log as e (e.id)}
							<li class="log">
								<span>
									{#if e.kind === 'operator_transfer'}
										{t('leaving.opLogTransfer', { scope: e.scope_name ?? '', from: e.from_name ?? '–', to: e.to_name ?? '–' })}
									{:else}
										{t('leaving.opLogHandover', { from: e.from_name ?? '–', n: e.item_count ?? 0 })}
									{/if}
								</span>
								<span class="muted tiny">
									{formatDateTime(e.at, i18n.tag)}{#if e.operator_name} · {t('leaving.opLogBy', { name: e.operator_name })}{/if}{#if e.reason} · “{e.reason}”{/if}
								</span>
							</li>
						{/each}
					</ul>
					{#if cursor}<button class="btn btn-sm btn-ghost align-start" onclick={() => loadLog(true)}>{t('common.loadMore')}</button>{/if}
				{/if}
			</section>
		</div>
	{/if}
</div>

<Dialog bind:open title={t('leaving.opTransferTitle', { name: target?.name ?? '' })}>
	<div class="stack">
		<div class="field">
			<label for="own-new">{t('leaving.opNewOwner')}</label>
			<select id="own-new" class="select" bind:value={newOwner}>
				<option value="" disabled>{t('feedMembers.pickMember')}</option>
				{#each candidates as m (m.user_id)}<option value={m.user_id}>{m.display_name}{m.email ? ` · ${m.email}` : ''}</option>{/each}
			</select>
		</div>
		<div class="field">
			<label for="own-reason">{t('leaving.opReason')}</label>
			<textarea id="own-reason" class="input" rows="2" maxlength="500" bind:value={reason}></textarea>
		</div>
		{#if owner}
			<label class="switch"><input type="checkbox" bind:checked={removePrevious} /><span>{t('leaving.opRemovePrevious', { name: owner.display_name })}</span></label>
		{/if}
	</div>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" onclick={transfer} disabled={busy || !newOwner || !reason.trim()}>{t('leaving.opTransfer')}</button>
	{/snippet}
</Dialog>

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
	.grow {
		flex: 1;
		min-width: 200px;
	}
	.h3 {
		font-size: 1.05rem;
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
		flex-wrap: wrap;
	}
	.who {
		flex: 1;
		min-width: 180px;
		display: flex;
		flex-direction: column;
		overflow-wrap: anywhere;
	}
	.log {
		display: flex;
		flex-direction: column;
		padding: 8px 0;
		overflow-wrap: anywhere;
	}
	.align-start {
		align-self: flex-start;
	}
</style>
