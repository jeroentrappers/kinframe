<script lang="ts">
	import { api, call, type PhotoSource, type PhotoSourceUpdate } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { formatDateTime } from '$lib/format';
	import { fixFor, providerLabel, retryText, serviceOf, statusView, targetHref, targetLabel, warningText } from '$lib/photoSources';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import ProviderIcon from './ProviderIcon.svelte';
	import PhotoSourceDeleteDialog from './PhotoSourceDeleteDialog.svelte';
	import PhotoSourceEditDialog from './PhotoSourceEditDialog.svelte';

	let {
		source,
		showTarget = false,
		onupdated,
		onremoved
	}: { source: PhotoSource; showTarget?: boolean; onupdated: (s: PhotoSource) => void; onremoved: (id: string) => void } = $props();

	const service = $derived(serviceOf(source.provider));
	const view = $derived(statusView(source, i18n.tag));
	const fix = $derived(fixFor(source));
	const manage = $derived(source.can_manage !== false);
	const href = $derived(showTarget ? targetHref(source.target) : null);
	const c = $derived(source.counts);

	let busy = $state<'sync' | 'pause' | 'confirm' | 'keep' | 'takeover' | null>(null);
	let editOpen = $state(false);
	let editTakeOver = $state(false);
	let deleteOpen = $state(false);
	let confirmOpen = $state(false);
	let takeOverOpen = $state(false);

	async function run<T>(what: typeof busy, p: () => Promise<T>): Promise<T | undefined> {
		busy = what;
		try {
			return await p();
		} catch (e) {
			if (isCode(e, 'rate_limited')) toasts.error(retryText(e.retryAfter, i18n.tag));
			else if (isCode(e, 'not_found')) {
				toasts.show(t('sources.gone'));
				onremoved(source.id);
			} else toasts.error(problemMessage(e));
			return undefined;
		} finally {
			busy = null;
		}
	}

	async function syncNow(confirmRemovals = false) {
		const s = await run(confirmRemovals ? 'confirm' : 'sync', () =>
			call(
				api.POST('/v1/photo-sources/{sourceId}/sync', {
					params: { path: { sourceId: source.id } },
					body: confirmRemovals ? { confirm_removals: true } : {}
				})
			)
		);
		if (!s) return;
		onupdated(s);
		confirmOpen = false;
		toasts.success(confirmRemovals ? t('sources.removalsConfirmed') : t('sources.syncStarted'));
	}

	async function patch(what: typeof busy, body: PhotoSourceUpdate, done: string) {
		const s = await run(what, () => call(api.PATCH('/v1/photo-sources/{sourceId}', { params: { path: { sourceId: source.id } }, body })));
		if (!s) return false;
		onupdated(s);
		toasts.success(done);
		return true;
	}

	function togglePause() {
		const pausing = source.status !== 'paused';
		void patch('pause', { paused: pausing }, pausing ? t('sources.paused', { name: source.name }) : t('sources.resumed', { name: source.name }));
	}

	async function keepThem() {
		if (await patch('keep', { on_upstream_delete: 'keep' }, t('sources.keptRemovals'))) confirmOpen = false;
	}

	function startTakeOver() {
		if (source.provider === 'immich_api') {
			editTakeOver = true;
			editOpen = true;
		} else takeOverOpen = true;
	}

	async function takeOver() {
		if (await patch('takeover', { take_over: true }, t('sources.tookOver', { name: source.name }))) takeOverOpen = false;
	}

	function openEdit() {
		editTakeOver = false;
		editOpen = true;
	}
</script>

<article class="card source tone-{view.tone}" data-testid="photo-source" aria-labelledby="ps-{source.id}">
	<header class="s-head">
		<ProviderIcon {service} />
		<div class="who">
			<h3 id="ps-{source.id}">{source.name}</h3>
			<p class="muted small meta">
				<span>{providerLabel(source.provider)}{#if source.provider === 'immich_api'} · {t('sources.viaApiKey')}{/if}</span>
				{#if source.url_hint || source.server_url}<span class="hint-url" title={source.url_hint ?? source.server_url ?? ''}>{source.url_hint ?? source.server_url}</span>{/if}
				{#if showTarget}
					<span class="target">
						<Icon name={source.target.kind === 'feed' ? 'feed' : 'album'} size={14} />
						{#if href}<a {href}>{targetLabel(source)}</a>{:else}{targetLabel(source)}{/if}
					</span>
				{/if}
			</p>
			{#if !source.is_owner}
				<p class="faint tiny">{source.owner ? t('sources.setUpBy', { name: source.owner.display_name }) : t('sources.ownerGone')}</p>
			{/if}
		</div>
		{#if source.best_effort}<span class="badge best-effort" title={t('sources.icloud.bestEffort')}>{t('sources.bestEffortBadge')}</span>{/if}
	</header>

	<p class="status" role="status">
		{#if view.tone === 'running'}<span class="spinner" aria-hidden="true"></span>{:else}<span class="dot-tone" aria-hidden="true"></span>{/if}
		<span class="s-label">{view.label}</span>
		{#if view.next}<span class="muted small">· {view.next}</span>{/if}
	</p>

	{#if view.detail}
		<div class="callout small" class:callout-warn={view.tone === 'attention' || view.tone === 'ok'} class:callout-error={view.tone === 'error'}>
			<Icon name="alert" size={18} />
			<div class="stack-sm">
				<span>{view.detail}</span>
				{#if manage && fix}
					<div class="row-wrap fix">
						{#if fix === 'reconnect'}
							<button class="btn btn-sm btn-primary" onclick={openEdit}><Icon name="link" size={16} />{t('sources.fixReconnect')}</button>
						{:else if fix === 'take_over'}
							<button class="btn btn-sm btn-primary" onclick={startTakeOver} disabled={busy !== null}>{t('sources.takeOver')}</button>
						{:else if fix === 'confirm_removals'}
							<button class="btn btn-sm btn-primary" onclick={() => (confirmOpen = true)}>{t('sources.reviewRemovals')}</button>
						{/if}
					</div>
				{/if}
			</div>
		</div>
	{/if}

	{#each source.warnings ?? [] as w (w)}
		<p class="callout small warning"><Icon name="info" size={18} />{warningText(w, discovery.limits.max_photo_source_items, i18n.tag)}</p>
	{/each}

	<dl class="counts num">
		<div><dt>{t('sources.count.imported')}</dt><dd>{c.imported}</dd></div>
		{#if c.upstream != null}<div><dt>{t('sources.count.upstream', { service: providerLabel(source.provider) })}</dt><dd>{c.upstream}</dd></div>{/if}
		{#if c.pending}<div><dt>{t('sources.count.pending')}</dt><dd>{c.pending}</dd></div>{/if}
		{#if c.duplicates}<div><dt>{t('sources.count.duplicates')}</dt><dd>{c.duplicates}</dd></div>{/if}
		{#if c.skipped}<div><dt>{t('sources.count.skipped')}</dt><dd>{c.skipped}</dd></div>{/if}
		{#if c.failed}<div><dt>{t('sources.count.failed')}</dt><dd>{c.failed}</dd></div>{/if}
	</dl>

	<p class="faint tiny times">
		{#if source.last_synced_at}<span>{t('sources.lastSynced', { when: formatDateTime(source.last_synced_at, i18n.tag) })}</span>{/if}
		{#if source.last_run?.finished_at && (source.last_run.added || source.last_run.removed)}
			<span>{t('sources.lastRun', { added: source.last_run.added ?? 0, removed: source.last_run.removed ?? 0 })}</span>
		{/if}
		{#if source.target.kind === 'feed'}<span>{t('sources.feedBatching')}</span>{/if}
	</p>

	{#if manage}
		<div class="row-wrap actions">
			{#if source.status !== 'paused'}
				<button class="btn btn-sm" onclick={() => syncNow()} disabled={busy !== null || source.running}>
					{#if busy === 'sync'}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="refresh" size={16} />{/if}{t('sources.syncNow')}
				</button>
			{/if}
			<button class="btn btn-sm btn-ghost" onclick={togglePause} disabled={busy !== null}>
				{#if busy === 'pause'}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name={source.status === 'paused' ? 'play' : 'pause'} size={16} />{/if}
				{source.status === 'paused' ? t('sources.resume') : t('sources.pause')}
			</button>
			<button class="btn btn-sm btn-ghost" onclick={openEdit}><Icon name="edit" size={16} />{t('common.edit')}</button>
			{#if !source.is_owner && fix !== 'take_over'}
				<button class="btn btn-sm btn-ghost" onclick={startTakeOver} disabled={busy !== null}><Icon name="user" size={16} />{t('sources.takeOver')}</button>
			{/if}
			<span class="spacer"></span>
			<button class="btn btn-sm btn-danger-ghost" onclick={() => (deleteOpen = true)}><Icon name="trash" size={16} />{t('sources.stop')}</button>
		</div>
	{/if}
</article>

<PhotoSourceEditDialog bind:open={editOpen} {source} takeOver={editTakeOver} onsaved={onupdated} />
<PhotoSourceDeleteDialog bind:open={deleteOpen} {source} ondeleted={() => onremoved(source.id)} />

<Dialog bind:open={confirmOpen} title={t('sources.removalsTitle')}>
	<p>{tn('sources.err.mass_removal', source.pending_removals ?? 0, { service: providerLabel(source.provider) })}</p>
	<p class="muted small">{t('sources.removalsBody')}</p>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={keepThem} disabled={busy !== null}>
			{#if busy === 'keep'}<span class="spinner" aria-hidden="true"></span>{/if}{t('sources.keepThem')}
		</button>
		<button class="btn btn-danger" onclick={() => syncNow(true)} disabled={busy !== null}>
			{#if busy === 'confirm'}<span class="spinner" aria-hidden="true"></span>{/if}{t('sources.removeThem')}
		</button>
	{/snippet}
</Dialog>

<Dialog bind:open={takeOverOpen} title={t('sources.takeOverTitle', { name: source.name })}>
	<p class="muted">{t('sources.takeOverBody')}</p>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (takeOverOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" onclick={takeOver} disabled={busy !== null}>
			{#if busy === 'takeover'}<span class="spinner" aria-hidden="true"></span>{/if}{t('sources.takeOver')}
		</button>
	{/snippet}
</Dialog>

<style>
	.source {
		padding: var(--s-4);
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		border-inline-start: 4px solid var(--tone, var(--border));
	}
	.tone-ok {
		--tone: var(--accent);
	}
	.tone-running {
		--tone: var(--primary);
	}
	.tone-paused {
		--tone: var(--text-faint);
	}
	.tone-error {
		--tone: var(--danger);
	}
	.tone-attention {
		--tone: var(--warn);
	}
	.s-head {
		display: flex;
		align-items: flex-start;
		gap: var(--s-3);
	}
	.who {
		flex: 1;
		min-width: 0;
	}
	h3 {
		font-size: 1.05rem;
		overflow-wrap: anywhere;
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		gap: 4px 10px;
	}
	.hint-url {
		font-family: ui-monospace, monospace;
		font-size: 0.8rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		max-width: 100%;
	}
	.target {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.best-effort {
		background: var(--warn-soft);
		border-color: transparent;
	}
	.status {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 8px;
		margin: 0;
	}
	.s-label {
		font-weight: 600;
	}
	.dot-tone {
		width: 10px;
		height: 10px;
		border-radius: 50%;
		background: var(--tone);
		flex: none;
	}
	.warning {
		margin: 0;
	}
	.counts {
		display: flex;
		flex-wrap: wrap;
		gap: var(--s-2) var(--s-5);
		margin: 0;
	}
	.counts div {
		display: flex;
		flex-direction: column;
	}
	.counts dt {
		font-size: 0.78rem;
		color: var(--text-muted);
	}
	.counts dd {
		margin: 0;
		font-weight: 700;
	}
	.times {
		display: flex;
		flex-wrap: wrap;
		gap: 2px 12px;
		margin: 0;
	}
	.actions {
		gap: 6px;
	}
	.actions .spacer {
		flex: 1;
	}
	.fix {
		gap: 6px;
	}
</style>
