<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, call, type Item, type ReplyPreset, type Send } from '$lib/api/client';
	import { CAPTION_MAX, captionRemaining, formatDateTime, formatDuration } from '$lib/format';
	import { frameDeliveries, kindCounts, stageOf } from '$lib/logic';
	import { failureMessage, problemMessage } from '$lib/problem';
	import { followSends } from '$lib/sendsLive';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, kindSummary, t, tn } from '$lib/i18n/index.svelte';
	import Icon from '$components/Icon.svelte';
	import PushOffer from '$components/PushOffer.svelte';
	import { push } from '$lib/push.svelte';
	import ReactionChips from '$components/ReactionChips.svelte';
	import StagePill from '$components/StagePill.svelte';
	import Thumb from '$components/Thumb.svelte';

	let send = $state<Send | null>(null);
	let error = $state<unknown>(null);
	let removed = $state(false);

	const id = $derived(page.params.id!);

	$effect(() => {
		const sendId = id;
		send = null;
		error = null;
		call(api.GET('/v1/sends/{sendId}', { params: { path: { sendId } } }))
			.then((s) => (send = s))
			.catch((e) => (error = e));
		return followSends(
			() => (send ? [send] : []),
			(s) => (send = s)
		);
	});

	const deliveries = $derived(send ? frameDeliveries(send) : []);
	const kinds = $derived(send ? kindCounts(send.items) : { photos: 0, videos: 0 });

	function placementsFor(itemId: string) {
		if (!send) return [];
		return send.frames.map((f) => ({
			frame: f.frame_name,
			frameId: f.frame_id,
			p: f.placements.find((p) => p.item_id === itemId)
		}));
	}

	// ---- caption editing
	let editingId = $state<string | null>(null);
	let draft = $state('');
	let saving = $state(false);

	function startEdit(item: Item) {
		editingId = item.id;
		draft = item.caption ?? '';
	}

	async function saveCaption(item: Item) {
		if (captionRemaining(draft) < 0) return;
		saving = true;
		const before = item.caption;
		const next = draft.trim() || null;
		item.caption = next; // optimistic
		try {
			const updated = await call(
				api.PATCH('/v1/items/{itemId}', { params: { path: { itemId: item.id } }, body: { caption: next } })
			);
			Object.assign(item, updated);
			editingId = null;
			toasts.success(t('detail.captionSaved'));
		} catch (e) {
			item.caption = before;
			toasts.error(problemMessage(e));
		} finally {
			saving = false;
		}
	}

	// ---- unsend (undo instead of "are you sure")
	function unsend() {
		if (!send) return;
		const sendId = send.id;
		toasts.deferred({
			message: t('detail.unsent'),
			undoLabel: t('common.undo'),
			apply: () => (removed = true),
			rollback: () => (removed = false),
			commit: () => call(api.DELETE('/v1/sends/{sendId}', { params: { path: { sendId } } })),
			onCommitError: (e) => toasts.error(problemMessage(e))
		});
	}
</script>

<svelte:head><title>{t('detail.title')} · kinframe</title></svelte:head>

<div class="container page narrow">
	<a href="/history" class="back"><Icon name="chevronLeft" size={18} />{t('history.title')}</a>

	{#if error}
		<div class="callout callout-error" role="alert"><Icon name="alert" />{problemMessage(error)}</div>
	{:else if !send}
		<div class="stack" aria-busy="true">
			<div class="skeleton" style="height:36px; width:50%"></div>
			<div class="skeleton" style="height:120px"></div>
			<div class="skeleton" style="height:120px"></div>
		</div>
	{:else if removed}
		<div class="card card-pad removed" role="status">
			<Icon name="check" size={26} />
			<h1>{t('detail.removedTitle')}</h1>
			<p class="muted">{t('detail.removedBody')}</p>
			<div class="row-wrap">
				<button class="btn" onclick={() => toasts.flush()}>{t('detail.removeNow')}</button>
				<button class="btn btn-primary" onclick={() => goto('/history')}>{t('history.title')}</button>
			</div>
		</div>
	{:else}
		<header class="page-header">
			<div>
				<h1>
					{kindSummary(kinds)}
				</h1>
				<p class="lede">{t('detail.sentAt', { when: formatDateTime(send.created_at, i18n.tag) })}</p>
			</div>
			<button class="btn btn-danger-ghost" onclick={unsend}><Icon name="trash" size={18} />{t('detail.unsend')}</button>
		</header>

		{#if push.justSent && push.shouldOffer}
			<PushOffer />
		{/if}

		<section class="card card-pad frames" aria-label={t('detail.deliveryLabel')}>
			{#each deliveries as d (d.frameId)}
				<div class="frame-sum">
					<div class="row">
						<span class="dot" class:dot-online={d.online} aria-hidden="true"></span>
						<strong class="fname">{d.frameName}</strong>
						<span class="spacer"></span>
						<StagePill stage={d.stage} />
					</div>
					<p class="small muted num">
						{t('detail.counts', { seen: d.counts.seen, delivered: d.counts.delivered + d.counts.seen, total: d.counts.total })}
						{#if !d.online && d.stage !== 'seen'} · {t('detail.offlineWaiting')}{/if}
					</p>
					<div class="progress" aria-hidden="true"><span style="width:{Math.round(d.progress * 100)}%"></span></div>
				</div>
			{/each}
		</section>

		<ul class="items" role="list">
			{#each send.items as item, i (item.id)}
				<li class="card item">
					<div class="ithumb">
						{#if item.status === 'failed'}
							<span class="failed"><Icon name="alert" size={22} /></span>
						{:else if item.status === 'processing'}
							<span class="skeleton fill"></span>
						{:else}
							<Thumb itemId={item.id} kind={item.kind} alt={item.caption ?? t('detail.itemAlt', { n: i + 1 })} />
						{/if}
						{#if item.kind === 'video' && item.duration_ms}
							<span class="dur num">{formatDuration(item.duration_ms / 1000)}</span>
						{/if}
					</div>
					<div class="ibody">
						{#if item.status === 'failed'}
							<p class="error-text">{failureMessage(item.failure_code)}</p>
						{/if}
						{#if editingId === item.id}
							<form
								class="stack-sm"
								onsubmit={(e) => {
									e.preventDefault();
									void saveCaption(item);
								}}
							>
								<label for="cap-{item.id}" class="visually-hidden">{t('send.caption')}</label>
								<!-- svelte-ignore a11y_autofocus -->
								<textarea id="cap-{item.id}" class="textarea" rows="2" bind:value={draft} autofocus aria-invalid={captionRemaining(draft) < 0}></textarea>
								<div class="row">
									<span class="hint num" class:over={captionRemaining(draft) < 0}>{t('send.charsLeft', { n: captionRemaining(draft), max: CAPTION_MAX })}</span>
									<span class="spacer"></span>
									<button type="button" class="btn btn-ghost btn-sm" onclick={() => (editingId = null)}>{t('common.cancel')}</button>
									<button type="submit" class="btn btn-primary btn-sm" disabled={saving || captionRemaining(draft) < 0}>{t('common.save')}</button>
								</div>
							</form>
						{:else}
							<div class="cap-line">
								{#if item.caption}
									<p class="cap">{item.caption}</p>
									{#if item.caption_edited}<span class="badge">{t('detail.edited')}</span>{/if}
								{:else}
									<p class="faint">{t('detail.noCaption')}</p>
								{/if}
								<button class="btn btn-ghost btn-sm" onclick={() => startEdit(item)} aria-label={t('detail.editCaptionN', { n: i + 1 })}>
									<Icon name="edit" size={16} />{t('common.edit')}
								</button>
							</div>
						{/if}
						<ul class="per-frame" role="list">
							{#each placementsFor(item.id) as row (row.frameId)}
								{#if row.p}
									<li>
										<span class="pf-name">{row.frame}</span>
										<StagePill stage={stageOf(row.p.status, item.status)} />
										<ReactionChips reactions={[row.p.reaction]} replies={(row.p.replies ?? []) as ReplyPreset[]} />
									</li>
								{/if}
							{/each}
						</ul>
					</div>
				</li>
			{/each}
		</ul>
	{/if}
</div>

<style>
	.narrow {
		max-width: 860px;
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
	.frames {
		display: grid;
		gap: var(--s-4);
		margin-bottom: var(--s-5);
	}
	@media (min-width: 640px) {
		.frames {
			grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
		}
	}
	.frame-sum {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.fname {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.frame-sum .progress span {
		background: var(--accent);
	}
	.items {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
	}
	.item {
		display: grid;
		grid-template-columns: 96px 1fr;
		gap: var(--s-4);
		padding: var(--s-3);
	}
	@media (min-width: 640px) {
		.item {
			grid-template-columns: 140px 1fr;
		}
	}
	.ithumb {
		position: relative;
		aspect-ratio: 1;
		border-radius: var(--r);
		overflow: hidden;
		background: var(--surface-2);
	}
	.fill {
		position: absolute;
		inset: 0;
	}
	.failed {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		background: var(--danger-soft);
		color: var(--danger);
	}
	.dur {
		position: absolute;
		right: 6px;
		bottom: 6px;
		padding: 1px 7px;
		border-radius: var(--r-full);
		background: rgb(20 14 10 / 0.65);
		color: #fff;
		font-size: 0.72rem;
		font-weight: 700;
	}
	.ibody {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		min-width: 0;
	}
	.cap-line {
		display: flex;
		align-items: flex-start;
		gap: var(--s-2);
		flex-wrap: wrap;
	}
	.cap {
		flex: 1;
		min-width: 0;
		overflow-wrap: anywhere;
	}
	.cap-line .faint {
		flex: 1;
		font-style: italic;
	}
	.per-frame {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.per-frame li {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		flex-wrap: wrap;
	}
	.pf-name {
		font-size: 0.86rem;
		font-weight: 600;
		color: var(--text-muted);
		min-width: 90px;
	}
	.over {
		color: var(--danger);
	}
	.removed {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: var(--s-3);
	}
	.removed :global(svg) {
		color: var(--accent);
	}
</style>
