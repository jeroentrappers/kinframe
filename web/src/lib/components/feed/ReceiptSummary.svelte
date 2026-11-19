<script lang="ts">
	import type { FeedReceiptSummary } from '$lib/api/client';
	import { receiptHeadline, receiptView } from '$lib/feeds';
	import { t, tn } from '$lib/i18n/index.svelte';
	import StagePill from '../StagePill.svelte';

	/** "Seen on 4 of 5 frames" plus what's waiting, paused or removed. Renders nothing when receipts are hidden. */
	let { summary, compact = false }: { summary: FeedReceiptSummary | null | undefined; compact?: boolean } = $props();

	const view = $derived(receiptView(summary));
	const head = $derived(view ? receiptHeadline(view) : null);
	const extras = $derived.by(() => {
		if (!view) return [];
		const out: string[] = [];
		if (view.awaiting) out.push(tn('feedPosts.awaiting', view.awaiting));
		if (view.paused) out.push(tn('feedPosts.paused', view.paused));
		if (view.removed) out.push(tn('feedPosts.removedOn', view.removed));
		return out;
	});
</script>

{#if view && head}
	<div class="receipt" class:compact data-testid="receipt">
		<div class="line">
			<span class="headline">
				{#if head.key === 'none'}{t('feedPosts.noFrames')}
				{:else if head.key === 'seen'}{tn('feedPosts.seenOn', head.total, { n: head.n })}
				{:else if head.key === 'delivered'}{tn('feedPosts.deliveredTo', head.total, { n: head.n })}
				{:else}{tn('feedPosts.waitingFor', head.total)}{/if}
			</span>
			{#if !compact && view.stage !== 'none'}
				<StagePill stage={view.stage === 'waiting' ? 'on_its_way' : view.stage} />
			{/if}
		</div>
		{#if view.total > 0}
			<div class="progress slim" aria-hidden="true"><span style="width:{Math.round(view.progress * 100)}%"></span></div>
		{/if}
		{#if extras.length}<p class="extras muted tiny">{extras.join(' · ')}</p>{/if}
	</div>
{/if}

<style>
	.receipt {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.line {
		display: flex;
		align-items: center;
		gap: 8px;
		flex-wrap: wrap;
	}
	.headline {
		font-weight: 600;
		font-size: 0.9rem;
	}
	.compact .headline {
		font-size: 0.85rem;
	}
	.progress.slim {
		height: 4px;
	}
	.progress span {
		background: var(--accent);
	}
	.extras {
		margin: 0;
	}
</style>
