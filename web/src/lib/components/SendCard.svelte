<script lang="ts">
	import type { Send } from '$lib/api/client';
	import { formatDateTime, relativeTime } from '$lib/format';
	import { frameDeliveries, kindCounts } from '$lib/logic';
	import { i18n, kindSummary, t, tn } from '$lib/i18n/index.svelte';
	import type { ReplyPreset } from '$lib/api/client';
	import Icon from './Icon.svelte';
	import ReactionChips from './ReactionChips.svelte';
	import StagePill from './StagePill.svelte';
	import Thumb from './Thumb.svelte';

	let { send, highlight = false }: { send: Send; highlight?: boolean } = $props();

	const deliveries = $derived(frameDeliveries(send));
	const kinds = $derived(kindCounts(send.items));
	const shown = $derived(send.items.slice(0, 4));
	const more = $derived(send.items.length - shown.length);
	const firstCaption = $derived(send.items.find((i) => i.caption)?.caption ?? null);
</script>

<article class="card send-card" class:highlight>
	<a href="/history/{send.id}" class="thumbs" aria-label={t('history.openSend', { when: formatDateTime(send.created_at, i18n.tag) })}>
		{#each shown as item, i (item.id)}
			<span class="t t{shown.length}-{i}">
				{#if item.status === 'failed'}
					<span class="failed-thumb"><Icon name="alert" size={18} /></span>
				{:else if item.status === 'processing'}
					<span class="skeleton processing"></span>
				{:else}
					<Thumb itemId={item.id} variant="thumb_256" kind={item.kind} radius="0" />
				{/if}
				{#if i === shown.length - 1 && more > 0}<span class="more num">+{more}</span>{/if}
			</span>
		{/each}
	</a>
	<div class="body">
		<div class="head">
			<p class="what">
				{kindSummary(kinds)}
			</p>
			<time class="muted small" datetime={send.created_at} title={formatDateTime(send.created_at, i18n.tag)}>{relativeTime(send.created_at, i18n.tag)}</time>
		</div>
		{#if firstCaption}<p class="caption">“{firstCaption}”</p>{/if}
		<ul class="frames" role="list">
			{#each deliveries as d (d.frameId)}
				<li class="frame-row">
					<div class="fr-top">
						<span class="fname">{d.frameName}</span>
						<StagePill
							stage={d.stage}
							label={d.stage === 'seen' || d.stage === 'delivered'
								? t(`stage.${d.stage}`)
								: d.counts.seen + d.counts.delivered > 0
									? t('history.partial', { done: d.counts.seen + d.counts.delivered, total: d.counts.total })
									: undefined}
						/>
					</div>
					<div class="progress slim" aria-hidden="true"><span style="width:{Math.round(d.progress * 100)}%"></span></div>
					<ReactionChips reactions={d.reactions} replies={d.replies as ReplyPreset[]} />
				</li>
			{/each}
		</ul>
	</div>
</article>

<style>
	.send-card {
		display: grid;
		grid-template-columns: 1fr;
		overflow: hidden;
		transition: box-shadow 600ms var(--ease);
	}
	.send-card.highlight {
		box-shadow: 0 0 0 3px var(--primary), var(--shadow);
	}
	@media (min-width: 640px) {
		.send-card {
			grid-template-columns: 200px 1fr;
		}
	}
	.thumbs {
		display: grid;
		grid-template-columns: 1fr 1fr;
		grid-template-rows: 1fr 1fr;
		gap: 2px;
		aspect-ratio: 16 / 9;
		background: var(--surface-2);
		border-radius: 0;
	}
	@media (min-width: 640px) {
		.thumbs {
			aspect-ratio: auto;
			min-height: 160px;
		}
	}
	.t {
		position: relative;
		overflow: hidden;
		display: block;
	}
	.t1-0 {
		grid-column: 1 / 3;
		grid-row: 1 / 3;
	}
	.t2-0,
	.t2-1 {
		grid-row: 1 / 3;
	}
	.t3-0 {
		grid-row: 1 / 3;
	}
	.processing {
		position: absolute;
		inset: 0;
		border-radius: 0;
	}
	.failed-thumb {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		color: var(--danger);
		background: var(--danger-soft);
	}
	.more {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		background: rgb(20 14 10 / 0.5);
		color: #fff;
		font-weight: 700;
		font-size: 1.2rem;
	}
	.body {
		padding: var(--s-4);
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		min-width: 0;
	}
	.head {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: var(--s-2);
	}
	.what {
		font-weight: 650;
	}
	.caption {
		color: var(--text-muted);
		font-style: italic;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.frames {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		margin-top: 4px;
	}
	.frame-row {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.fr-top {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-2);
	}
	.fname {
		font-weight: 600;
		font-size: 0.92rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.progress.slim {
		height: 4px;
	}
	.progress.slim span {
		background: var(--accent);
	}
</style>
