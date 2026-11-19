<script lang="ts">
	import type { WeeklySummary } from '$lib/api/client';
	import { t, tn } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Icon from '../Icon.svelte';
	import Thumb from '../Thumb.svelte';

	/** "Your photos were seen 58 times on 3 frames last week; Oma loved 4" (README §5.20). */
	let { summary, ondismiss }: { summary: WeeklySummary; ondismiss?: () => void } = $props();
	const topFrame = $derived([...summary.frames].sort((a, b) => b.love_count - a.love_count)[0]);
</script>

<section class="card weekly" in:softFly aria-labelledby="ws-title">
	<div class="text">
		<h2 id="ws-title"><span aria-hidden="true">💛</span> {t('engagement.weeklyTitle')}</h2>
		<p>{tn('engagement.weeklySeen', summary.seen_count, { frames: tn('engagement.onFrames', summary.frame_count) })}</p>
		<ul class="facts" role="list">
			{#if summary.love_count > 0}
				<li>{topFrame && topFrame.love_count > 0 ? tn('engagement.weeklyLovedBy', topFrame.love_count, { frame: topFrame.frame_name }) : tn('engagement.weeklyLoves', summary.love_count)}</li>
			{/if}
			{#if summary.reaction_count > 0}<li>{tn('engagement.weeklyReactions', summary.reaction_count)}</li>{/if}
			{#if summary.comment_count > 0}<li>{tn('engagement.weeklyComments', summary.comment_count)}</li>{/if}
		</ul>
	</div>
	{#if summary.top_item}
		<span class="top"><Thumb itemId={summary.top_item.id} variant="thumb_256" kind={summary.top_item.kind} radius="var(--r)" /></span>
	{/if}
	{#if ondismiss}
		<button class="btn btn-ghost btn-icon btn-sm close" aria-label={t('common.dismiss')} onclick={ondismiss}><Icon name="x" size={18} /></button>
	{/if}
</section>

<style>
	.weekly {
		position: relative;
		display: flex;
		gap: var(--s-4);
		padding: var(--s-4);
		align-items: center;
		background: linear-gradient(160deg, var(--primary-soft), var(--surface) 70%);
	}
	.text {
		flex: 1;
		min-width: 0;
	}
	h2 {
		font-size: 1.05rem;
		padding-inline-end: 28px;
	}
	p {
		margin: var(--s-1) 0;
	}
	.facts {
		margin: 0;
		padding-inline-start: 1.1em;
		color: var(--text-muted);
		font-size: 0.9rem;
	}
	.top {
		width: 84px;
		height: 84px;
		flex: none;
	}
	.close {
		position: absolute;
		top: 6px;
		inset-inline-end: 6px;
	}
</style>
