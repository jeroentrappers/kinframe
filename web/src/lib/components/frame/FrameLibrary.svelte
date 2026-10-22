<script lang="ts">
	import { api, call, type Frame, type Placement } from '$lib/api/client';
	import { can } from '$lib/frames.svelte';
	import { formatDateTime } from '$lib/format';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Dialog from '../Dialog.svelte';
	import EmptyState from '../EmptyState.svelte';
	import Icon from '../Icon.svelte';
	import Thumb from '../Thumb.svelte';

	type View = 'visible' | 'hidden' | 'trashed';
	type Action = 'hide' | 'unhide' | 'trash' | 'restore';

	let { frame }: { frame: Frame } = $props();

	let view = $state<View>('visible');
	let items = $state<Placement[]>([]);
	let counts = $state<{ photos?: number; videos?: number; hidden?: number; trashed?: number }>({});
	let cursor = $state<string | null>(null);
	let loading = $state(true);
	let loadingMore = $state(false);
	let error = $state<unknown>(null);
	let selected = $state<Set<string>>(new Set());
	let viewing = $state<Placement | null>(null);
	let viewerOpen = $state(false);

	const seeAll = $derived(can(frame, 'see_library'));

	async function load(reset = true) {
		if (reset) {
			loading = true;
			selected = new Set();
		} else loadingMore = true;
		error = null;
		try {
			const res = await call(
				api.GET('/v1/frames/{frameId}/placements', {
					params: {
						path: { frameId: frame.id },
						query: { visibility: view, limit: 60, ...(reset || !cursor ? {} : { cursor }) }
					}
				})
			);
			items = reset ? res.placements : [...items, ...res.placements];
			counts = res.counts;
			cursor = res.next_cursor;
		} catch (e) {
			error = e;
		} finally {
			loading = false;
			loadingMore = false;
		}
	}

	$effect(() => {
		void frame.id;
		void view;
		void load();
	});

	function toggle(id: string) {
		const next = new Set(selected);
		if (next.has(id)) next.delete(id);
		else next.add(id);
		selected = next;
	}

	function open(p: Placement) {
		if (selected.size > 0) return toggle(p.id);
		viewing = p;
		viewerOpen = true;
	}

	const ACTIONS: Record<View, Action[]> = {
		visible: ['hide', 'trash'],
		hidden: ['unhide', 'trash'],
		trashed: ['restore']
	};

	function bulk(action: Action, ids: string[] = [...selected]) {
		if (ids.length === 0) return;
		const snapshot = items;
		const snapshotCounts = { ...counts };
		const n = ids.length;
		viewerOpen = false;
		toasts.deferred({
			message: tn(`library.done_${action}`, n),
			undoLabel: t('common.undo'),
			apply: () => {
				items = items.filter((p) => !ids.includes(p.id));
				selected = new Set();
				if (action === 'hide') counts.hidden = (counts.hidden ?? 0) + n;
				if (action === 'unhide') counts.hidden = Math.max(0, (counts.hidden ?? 0) - n);
				if (action === 'trash') {
					counts.trashed = (counts.trashed ?? 0) + n;
					if (view === 'hidden') counts.hidden = Math.max(0, (counts.hidden ?? 0) - n);
				}
				if (action === 'restore') counts.trashed = Math.max(0, (counts.trashed ?? 0) - n);
			},
			rollback: () => {
				items = snapshot;
				counts = snapshotCounts;
			},
			commit: async () => {
				const res = await call(
					api.POST('/v1/frames/{frameId}/placements:bulk', {
						params: { path: { frameId: frame.id } },
						body: { placement_ids: ids, action }
					})
				);
				if (res.failed?.length) {
					toasts.error(tn('library.someFailed', res.failed.length));
					void load();
				}
			},
			onCommitError: (e) => toasts.error(problemMessage(e))
		});
	}
</script>

<div class="toolbar">
	<div class="segmented" role="tablist" aria-label={t('library.filter')}>
		<button role="tab" aria-selected={view === 'visible'} onclick={() => (view = 'visible')}>{t('library.visible')}</button>
		<button role="tab" aria-selected={view === 'hidden'} onclick={() => (view = 'hidden')}>
			{t('library.hidden')}{#if counts.hidden}<span class="count num">{counts.hidden}</span>{/if}
		</button>
		<button role="tab" aria-selected={view === 'trashed'} onclick={() => (view = 'trashed')}>
			{t('library.trashTab')}{#if counts.trashed}<span class="count num">{counts.trashed}</span>{/if}
		</button>
	</div>
	{#if view === 'visible' && (counts.photos != null || counts.videos != null)}
		<p class="muted small num">
			{tn('history.photos', counts.photos ?? 0)}{#if counts.videos}, {tn('history.videos', counts.videos)}{/if}
		</p>
	{/if}
</div>

{#if !seeAll}
	<p class="callout small own-only"><Icon name="info" size={18} />{t('library.ownOnly')}</p>
{/if}
{#if view === 'trashed' && items.length > 0}
	<p class="callout small own-only"><Icon name="trash" size={18} />{t('library.trashNote')}</p>
{/if}

{#if loading}
	<div class="grid" aria-busy="true" aria-label={t('common.loading')}>
		{#each Array(12) as _, i (i)}<div class="skeleton tile-skel"></div>{/each}
	</div>
{:else if error}
	<div class="callout callout-error" role="alert">
		<Icon name="alert" />
		<div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={() => load()}>{t('common.tryAgain')}</button></div>
	</div>
{:else if items.length === 0}
	<div class="card">
		<EmptyState
			compact
			art="library"
			title={view === 'visible' ? t('library.emptyTitle') : view === 'hidden' ? t('library.emptyHidden') : t('library.emptyTrash')}
			body={view === 'visible' ? t('library.emptyBody') : undefined}
		>
			{#snippet actions()}
				{#if view === 'visible' && can(frame, 'send')}
					<a class="btn btn-primary" href="/send?frame={frame.id}"><Icon name="send" size={18} />{t('nav.sendPhotos')}</a>
				{/if}
			{/snippet}
		</EmptyState>
	</div>
{:else}
	<ul class="grid" role="list" aria-label={t('frame.tabLibrary')}>
		{#each items as p (p.id)}
			{@const isSel = selected.has(p.id)}
			<li class="tile" class:selected={isSel} class:selecting={selected.size > 0}>
				<button class="tile-btn" onclick={() => open(p)} aria-label={p.item.caption ?? t('library.itemBy', { name: p.item.sender_name ?? '' })}>
					<Thumb itemId={p.item.id} variant="thumb_256" kind={p.item.kind} radius="0" />
				</button>
				<button
					class="pick"
					role="checkbox"
					aria-checked={isSel}
					aria-label={t('library.select')}
					onclick={() => toggle(p.id)}
				>
					<Icon name="check" size={14} stroke={3} />
				</button>
				{#if p.pending_moderation}<span class="tag">{t('library.pending')}</span>{/if}
			</li>
		{/each}
	</ul>
	{#if cursor}
		<div class="more"><button class="btn" onclick={() => load(false)} disabled={loadingMore}>{t('common.loadMore')}</button></div>
	{/if}
{/if}

{#if selected.size > 0}
	<div class="bulkbar" role="toolbar" aria-label={t('library.bulk')} transition:softFly={{ y: 24 }}>
		<button class="btn btn-ghost btn-icon btn-sm" aria-label={t('library.clearSelection')} onclick={() => (selected = new Set())}><Icon name="x" size={18} /></button>
		<span class="sel num">{tn('library.selected', selected.size)}</span>
		<span class="spacer"></span>
		<button class="btn btn-ghost btn-sm select-all" onclick={() => (selected = new Set(items.map((p) => p.id)))}>{t('library.selectAll')}</button>
		{#each ACTIONS[view] as a (a)}
			<button class="btn btn-sm" class:btn-danger={a === 'trash'} class:btn-primary={a !== 'trash'} onclick={() => bulk(a)}>
				<Icon name={a === 'hide' ? 'eyeOff' : a === 'unhide' ? 'eye' : a === 'trash' ? 'trash' : 'restore'} size={16} />{t(`library.${a}`)}
			</button>
		{/each}
	</div>
{/if}

<Dialog bind:open={viewerOpen} title={viewing?.item.sender_name ? t('library.from', { name: viewing.item.sender_name }) : t('library.photo')} size="md">
	{#if viewing}
		<div class="viewer stack">
			<div class="big">
				<Thumb itemId={viewing.item.id} variant={viewing.item.kind === 'video' ? 'poster' : 'master'} kind={viewing.item.kind} alt={viewing.item.caption ?? ''} />
			</div>
			{#if viewing.item.caption}<p>{viewing.item.caption}</p>{/if}
			<p class="muted small">{t('library.received', { when: formatDateTime(viewing.received_at, i18n.tag) })}</p>
		</div>
	{/if}
	{#snippet footer()}
		{#if viewing}
			{#each ACTIONS[view] as a (a)}
				<button class="btn" class:btn-danger-ghost={a === 'trash'} onclick={() => viewing && bulk(a, [viewing.id])}>
					<Icon name={a === 'hide' ? 'eyeOff' : a === 'unhide' ? 'eye' : a === 'trash' ? 'trash' : 'restore'} size={16} />{t(`library.${a}`)}
				</button>
			{/each}
		{/if}
	{/snippet}
</Dialog>

<style>
	.toolbar {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-3);
		flex-wrap: wrap;
		margin-bottom: var(--s-4);
	}
	.count {
		min-width: 20px;
		padding: 0 6px;
		border-radius: var(--r-full);
		background: var(--surface-3);
		font-size: 0.74rem;
		line-height: 20px;
		text-align: center;
	}
	.own-only {
		margin-bottom: var(--s-4);
	}
	.grid {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
		gap: 6px;
	}
	@media (max-width: 480px) {
		.grid {
			grid-template-columns: repeat(3, 1fr);
			gap: 3px;
		}
	}
	.tile-skel {
		aspect-ratio: 1;
		border-radius: 10px;
	}
	.tile {
		position: relative;
		aspect-ratio: 1;
		border-radius: 10px;
		overflow: hidden;
		background: var(--surface-2);
	}
	.tile-btn {
		all: unset;
		position: absolute;
		inset: 0;
		cursor: pointer;
		transition: transform 160ms var(--ease);
	}
	.tile-btn:focus-visible {
		outline: 3px solid var(--focus);
		outline-offset: -3px;
	}
	.tile.selected .tile-btn {
		transform: scale(0.88);
		border-radius: 8px;
		overflow: hidden;
	}
	.tile.selected {
		background: var(--primary-soft);
	}
	.pick {
		position: absolute;
		top: 6px;
		inset-inline-start: 6px;
		width: 26px;
		height: 26px;
		border-radius: 50%;
		border: 2px solid #fff;
		background: rgb(0 0 0 / 0.25);
		color: transparent;
		display: grid;
		place-items: center;
		cursor: pointer;
		opacity: 0;
		transition: opacity var(--dur) var(--ease);
		box-shadow: 0 1px 3px rgb(0 0 0 / 0.3);
	}
	.tile:hover .pick,
	.pick:focus-visible,
	.selecting .pick {
		opacity: 1;
	}
	@media (hover: none) {
		.pick {
			opacity: 1;
		}
	}
	.selected .pick {
		background: var(--primary);
		border-color: var(--primary);
		color: var(--on-primary);
	}
	.tag {
		position: absolute;
		bottom: 6px;
		inset-inline-end: 6px;
		padding: 1px 7px;
		border-radius: var(--r-full);
		background: var(--warn-soft);
		color: var(--warn);
		font-size: 0.7rem;
		font-weight: 700;
	}
	.more {
		display: flex;
		justify-content: center;
		margin-top: var(--s-4);
	}
	.bulkbar {
		position: fixed;
		z-index: 40;
		left: 50%;
		transform: translateX(-50%);
		bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom) + 12px);
		width: min(640px, calc(100vw - 24px));
		display: flex;
		align-items: center;
		gap: 6px;
		flex-wrap: wrap;
		padding: 8px 10px;
		border-radius: var(--r-lg);
		background: var(--surface);
		border: 1px solid var(--border);
		box-shadow: var(--shadow-lg);
	}
	@media (min-width: 860px) {
		.bulkbar {
			bottom: 24px;
		}
	}
	.sel {
		font-weight: 650;
	}
	@media (max-width: 480px) {
		.select-all {
			display: none;
		}
		.bulkbar {
			flex-wrap: nowrap;
		}
	}
	.big {
		aspect-ratio: 4 / 3;
		border-radius: var(--r);
		overflow: hidden;
		background: #000;
	}
	.big :global(img) {
		object-fit: contain !important;
	}
</style>
