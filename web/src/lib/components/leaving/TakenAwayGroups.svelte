<script lang="ts">
	import { api, call, type Item, type Placement } from '$lib/api/client';
	import { askBody, type TakenAwayGroup } from '$lib/leaving';
	import { dayMonth } from '$lib/leavingText';
	import { relativeTime } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { newIdempotencyKey } from '$lib/logic';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import Thumb from '../Thumb.svelte';
	import PhotoPicker from './PhotoPicker.svelte';

	/**
	 * "Taken away by Tom" (leaving.md story 2): the takings still in their 30 days, in a frame's trash or a family's
	 * posts, for the scope's managers, with "Ask Tom to keep them" (story 3).
	 */
	let { scope, id, onchanged }: { scope: 'frame' | 'feed'; id: string; onchanged?: () => void } = $props();

	let groups = $state<TakenAwayGroup[]>([]);
	let open = $state<string | null>(null);
	let placements = $state<Record<string, Placement[]>>({});

	async function load() {
		try {
			const res =
				scope === 'frame'
					? await call(api.GET('/v1/frames/{frameId}/taken-away', { params: { path: { frameId: id } } }))
					: await call(api.GET('/v1/feeds/{feedId}/taken-away', { params: { path: { feedId: id } } }));
			groups = res.groups;
		} catch {
			groups = [];
		}
	}

	async function loadPlacements(takingId: string): Promise<Placement[]> {
		if (scope !== 'frame') return [];
		if (placements[takingId]) return placements[takingId]!;
		const all: Placement[] = [];
		let cursor: string | null = null;
		do {
			const res: { placements: Placement[]; next_cursor: string | null } = await call(
				api.GET('/v1/frames/{frameId}/placements', {
					params: { path: { frameId: id }, query: { visibility: 'taken_away', taking_id: takingId, limit: 200, ...(cursor ? { cursor } : {}) } }
				})
			);
			all.push(...res.placements);
			cursor = res.next_cursor;
		} while (cursor && all.length < 2000);
		placements = { ...placements, [takingId]: all };
		return all;
	}

	$effect(() => {
		void id;
		void load();
		return live.on((e) => {
			if (e.type === 'keep_request.updated') {
				const r = e.request;
				if ((scope === 'frame' && r.frame_id === id) || (scope === 'feed' && r.feed_id === id)) {
					placements = {};
					void load();
					onchanged?.();
				}
			}
			if (e.type === 'placement.updated' && scope === 'frame' && e.frame_id === id) {
				placements = {};
				void load();
			}
		});
	});

	async function toggle(g: TakenAwayGroup) {
		if (open === g.taking_id) {
			open = null;
			return;
		}
		open = g.taking_id;
		try {
			await loadPlacements(g.taking_id);
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	// ---- ask to keep
	let asking = $state<TakenAwayGroup | null>(null);
	let askOpen = $state(false);
	let askMode = $state<'all' | 'pick'>('all');
	let picked = $state(new Set<string>());
	let note = $state('');
	let askItems = $state<Item[]>([]);
	let busy = $state(false);

	async function startAsk(g: TakenAwayGroup) {
		asking = g;
		askMode = 'all';
		picked = new Set();
		note = '';
		askItems = [];
		askOpen = true;
		try {
			askItems = (await loadPlacements(g.taking_id)).map((p) => p.item);
		} catch {
			askItems = [];
		}
	}

	async function ask() {
		const g = asking;
		if (!g) return;
		busy = true;
		try {
			const body = askBody(scope === 'frame' ? { frameId: id } : { feedId: id }, g.taking_id, askMode === 'pick' ? [...picked] : null, g.count, note);
			const r = await call(api.POST('/v1/keep-requests', { params: { header: { 'Idempotency-Key': newIdempotencyKey() } }, body }));
			groups = groups.map((x) => (x.taking_id === g.taking_id ? { ...x, keep_request: r, can_ask: false, cannot_ask_reason: 'already_asked' } : x));
			askOpen = false;
			toasts.success(t('leaving.asked', { name: g.by_name }));
		} catch (e) {
			toasts.error(problemMessage(e));
			void load();
		} finally {
			busy = false;
		}
	}
</script>

{#if groups.length > 0}
	<section class="card card-pad taken" aria-labelledby="taken-{id}" in:softFly>
		<div class="section-title"><h2 id="taken-{id}"><Icon name="clock" size={18} /> {t('leaving.takenAwayTitle')}</h2></div>
		<ul class="list" role="list">
			{#each groups as g (g.taking_id)}
				<li class="group">
					<div class="g-head">
						<div class="who">
							<strong>{t('leaving.takenAwayBy', { name: g.by_name })}</strong>
							<span class="muted small">{tn('history.photos', g.count)} · {t('leaving.takenOn', { when: relativeTime(g.taken_at, i18n.tag) })}</span>
							<span class="small until">{t('leaving.takenUntil', { date: dayMonth(g.until) })}</span>
							{#if g.keep_request}
								<span class="tiny muted">
									{t('leaving.requestStatus', { status: t(`leaving.status.${g.keep_request.status}`) })}{#if g.keep_request.status === 'partly_allowed'} · {t('leaving.allowedOf', { n: g.keep_request.allowed_count ?? 0, total: g.keep_request.item_count })}{/if}
								</span>
							{/if}
						</div>
						{#if (g.preview_thumb_urls?.length ?? 0) > 0}
							<ul class="peek" role="list" aria-hidden="true">
								{#each (g.preview_thumb_urls ?? []).slice(0, 4) as u (u)}<li><img src={u} alt="" loading="lazy" /></li>{/each}
							</ul>
						{/if}
					</div>
					<div class="row-wrap">
						{#if g.can_ask}
							<button class="btn btn-sm btn-primary" onclick={() => startAsk(g)}><Icon name="heart" size={16} />{t('leaving.askToKeep', { name: g.by_name })}</button>
						{:else if g.cannot_ask_reason && g.cannot_ask_reason !== 'already_asked'}
							<span class="small muted reason"><Icon name="info" size={14} />{t(`leaving.cannotAsk.${g.cannot_ask_reason}`, { name: g.by_name })}</span>
						{/if}
						{#if scope === 'frame'}
							<button class="btn btn-sm btn-ghost" aria-expanded={open === g.taking_id} onclick={() => toggle(g)}>
								<Icon name="chevronDown" size={16} />{open === g.taking_id ? t('leaving.hidePhotos') : t('leaving.showPhotos')}
							</button>
						{/if}
					</div>
					{#if open === g.taking_id}
						<ul class="thumbs" role="list" in:softFly>
							{#each placements[g.taking_id] ?? [] as p (p.id)}
								<li><Thumb itemId={p.item.id} variant="thumb_256" kind={p.item.kind} radius="var(--r-sm)" /></li>
							{/each}
						</ul>
					{/if}
				</li>
			{/each}
		</ul>
	</section>
{/if}

<Dialog bind:open={askOpen} title={t('leaving.askTitle', { name: asking?.by_name ?? '' })} size="md">
	{#if asking}
		<div class="stack">
			<p class="muted small">{t('leaving.askBody', { name: asking.by_name })}</p>
			{#if askItems.length > 0}
				<div class="segmented" role="tablist" aria-label={t('leaving.askPick')}>
					<button role="tab" aria-selected={askMode === 'all'} onclick={() => (askMode = 'all')}>{t('leaving.askAll', { n: asking.count })}</button>
					<button role="tab" aria-selected={askMode === 'pick'} onclick={() => (askMode = 'pick')}>{t('leaving.askPick')}</button>
				</div>
				{#if askMode === 'pick'}<PhotoPicker items={askItems} bind:picked label={t('leaving.askPick')} />{/if}
			{:else}
				<p class="label">{t('leaving.askAll', { n: asking.count })}</p>
			{/if}
			<div class="field">
				<label for="ask-note-{id}">{t('leaving.askNote')} <span class="faint">({t('common.optional')})</span></label>
				<input id="ask-note-{id}" class="input" maxlength="300" bind:value={note} placeholder={t('leaving.askNotePlaceholder')} />
			</div>
		</div>
	{/if}
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (askOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" onclick={ask} disabled={busy || (askMode === 'pick' && picked.size === 0)}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{t('leaving.askSend')}
		</button>
	{/snippet}
</Dialog>

<style>
	.taken {
		border-color: color-mix(in srgb, var(--warn) 35%, var(--border));
		background: color-mix(in srgb, var(--warn-soft) 30%, var(--surface));
		margin-bottom: var(--s-4);
	}
	.taken h2 {
		display: flex;
		align-items: center;
		gap: 8px;
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
	.group {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		padding: 12px 0;
	}
	.g-head {
		display: flex;
		gap: var(--s-3);
		align-items: flex-start;
		justify-content: space-between;
		flex-wrap: wrap;
	}
	.who {
		display: flex;
		flex-direction: column;
		min-width: 0;
		flex: 1;
	}
	.until {
		color: var(--warn);
		font-weight: 600;
	}
	.peek {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		gap: 4px;
	}
	.peek img {
		width: 44px;
		height: 44px;
		object-fit: cover;
		border-radius: var(--r-sm);
		display: block;
	}
	.reason {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.thumbs {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(76px, 1fr));
		gap: 6px;
	}
	.thumbs li {
		aspect-ratio: 1;
	}
</style>
