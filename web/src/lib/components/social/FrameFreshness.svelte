<script lang="ts">
	import type { FrameSummary } from '$lib/api/client';
	import { freshnessWords, requestOpen } from '$lib/engagement';
	import { relativeTime } from '$lib/format';
	import { personName } from '$lib/social';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';
	import FreshnessRing from './FreshnessRing.svelte';

	/** The frame screen's freshness ring and "Oma would like new photos" (README §5.20). */
	let { frame }: { frame: FrameSummary } = $props();
	const w = $derived(freshnessWords(frame.freshness));
	const words = $derived.by(() => {
		if (!w) return '';
		if (w.key === 'fresh') return t('engagement.fresh');
		if (w.key === 'never') return t('engagement.never');
		if (w.key === 'days') return tn('engagement.days', w.count);
		if (w.key === 'daysQuiet') return tn('engagement.daysQuiet', w.count);
		return tn('engagement.weeksQuiet', w.count);
	});
	const asked = $derived(requestOpen(frame.photo_request));
</script>

{#if frame.freshness}
	<div class="fresh" class:asked>
		<FreshnessRing freshness={frame.freshness} size={44}>
			<span class="face" aria-hidden="true"><Icon name="frame" size={18} /></span>
		</FreshnessRing>
		<div class="text">
			<strong>{asked ? `💛 ${t('engagement.wouldLike')}` : words}</strong>
			{#if frame.photo_request?.state === 'answered' && frame.photo_request.answered_by}
				<span class="small muted">{t('engagement.answeredBy', { name: personName(frame.photo_request.answered_by, t('social.formerMember')) })}</span>
			{:else if frame.freshness.last_shared_by && frame.freshness.last_shared_at}
				<span class="small muted">{t('engagement.lastSharedBy', { name: personName(frame.freshness.last_shared_by, t('social.formerMember')), when: relativeTime(frame.freshness.last_shared_at, i18n.tag) })}</span>
			{/if}
		</div>
	</div>
{/if}

<style>
	.fresh {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: var(--s-2) var(--s-3);
		margin-bottom: var(--s-4);
		border-radius: var(--r-lg);
		background: var(--surface);
		border: 1px solid var(--border);
		max-width: 520px;
	}
	.fresh.asked {
		background: var(--primary-soft);
		border-color: transparent;
	}
	.face {
		display: grid;
		place-items: center;
		background: var(--surface-2);
		color: var(--text-muted);
	}
	.text {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
</style>
