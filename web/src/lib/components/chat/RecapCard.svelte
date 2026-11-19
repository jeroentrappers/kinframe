<script lang="ts">
	import type { MonthlyRecap } from '$lib/api/client';
	import { monthDate } from '$lib/engagement';
	import { personName } from '$lib/social';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Thumb from '../Thumb.svelte';

	/** The monthly family recap (README §5.20): about the family, never ranking people. */
	let { recap }: { recap: MonthlyRecap } = $props();
	const month = $derived(new Intl.DateTimeFormat(i18n.tag, { month: 'long', year: 'numeric' }).format(monthDate(recap.month)));
	const newcomers = $derived(new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format((recap.new_members ?? []).map((p) => personName(p, t('social.formerMember')))));
</script>

<section class="recap" aria-label={t('engagement.recapTitle', { month })}>
	<h3><span aria-hidden="true">📅</span> {t('engagement.recapTitle', { month })}</h3>
	<div class="stats">
		<p><strong class="num">{recap.photos_shared}</strong><span>{tn('engagement.recapPhotos', recap.photos_shared)}</span></p>
		{#if recap.videos_shared > 0}<p><strong class="num">{recap.videos_shared}</strong><span>{tn('engagement.recapVideos', recap.videos_shared)}</span></p>{/if}
		<p><strong class="num">{recap.sharer_count}</strong><span>{tn('engagement.recapSharers', recap.sharer_count)}</span></p>
		{#if recap.streak_weeks}<p><strong class="num">🔥{recap.streak_weeks}</strong><span>{tn('engagement.recapStreak', recap.streak_weeks)}</span></p>{/if}
	</div>
	{#if recap.most_loved}
		<a class="loved" href="/posts/{recap.most_loved.post_id}">
			<span class="thumb"><Thumb itemId={recap.most_loved.item.id} variant="thumb_256" kind={recap.most_loved.item.kind} radius="var(--r-sm)" /></span>
			<span><strong>{t('engagement.recapMostLoved')}</strong>{#if recap.most_loved.reaction_count}<span class="muted small"> · {tn('engagement.recapReactions', recap.most_loved.reaction_count)}</span>{/if}</span>
		</a>
	{/if}
	{#if recap.freshest_frames?.length}
		<p class="small"><strong>{t('engagement.recapFreshest')}</strong> {recap.freshest_frames.map((f) => `${f.frame_name} (${tn('engagement.recapDays', f.days_with_new_photos)})`).join(' · ')}</p>
	{/if}
	{#if newcomers}<p class="small">{t('engagement.recapWelcome', { names: newcomers })}</p>{/if}
</section>

<style>
	.recap {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		padding: var(--s-3) var(--s-4);
		border-radius: var(--r-lg);
		background: linear-gradient(160deg, var(--warn-soft), var(--surface));
		border: 1px solid var(--border);
		max-width: 420px;
		text-align: start;
	}
	h3 {
		font-size: 1rem;
	}
	.stats {
		display: flex;
		flex-wrap: wrap;
		gap: var(--s-4);
	}
	.stats p {
		margin: 0;
		display: flex;
		flex-direction: column;
	}
	.stats strong {
		font-size: 1.3rem;
	}
	.stats span {
		font-size: 0.78rem;
		color: var(--text-muted);
	}
	.loved {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		color: inherit;
		text-decoration: none;
	}
	.loved .thumb {
		width: 56px;
		height: 56px;
		flex: none;
	}
	p {
		margin: 0;
	}
</style>
