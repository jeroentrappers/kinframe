<script lang="ts">
	import { goto } from '$app/navigation';
	import type { FeedSummary, FrameSummary } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { freshnessWords, requestOpen, type FreshnessWords } from '$lib/engagement';
	import { t, tn } from '$lib/i18n/index.svelte';
	import FeedCover from '../feed/FeedCover.svelte';
	import Icon from '../Icon.svelte';
	import FreshnessRing from './FreshnessRing.svelte';

	let {
		frames,
		families,
		onframe
	}: {
		frames: FrameSummary[];
		families: FeedSummary[];
		/** A quiet frame or one that asked for photos: the page offers suggestions instead of opening it. */
		onframe: (f: FrameSummary) => void;
	} = $props();

	function words(w: FreshnessWords | null): string {
		if (!w) return '';
		if (w.key === 'fresh') return t('engagement.fresh');
		if (w.key === 'never') return t('engagement.never');
		if (w.key === 'days') return tn('engagement.days', w.count);
		if (w.key === 'daysQuiet') return tn('engagement.daysQuiet', w.count);
		return tn('engagement.weeksQuiet', w.count);
	}

	function openFrame(f: FrameSummary) {
		const quiet = f.freshness?.state === 'quiet' || f.freshness?.state === 'never';
		if (requestOpen(f.photo_request) || (quiet && (f.permissions?.send || f.role === 'owner'))) onframe(f);
		else void goto(`/frames/${f.id}`);
	}
</script>

{#if frames.length + families.length > 0}
	<nav class="strip" aria-label={t('social.stripLabel')}>
		<ul role="list">
			{#each frames as f (f.id)}
				{@const asked = requestOpen(f.photo_request)}
				<li>
					<button class="tile" onclick={() => openFrame(f)} aria-describedby="fr-{f.id}">
						<FreshnessRing freshness={discovery.engagement ? f.freshness : null} size={68}>
							<span class="frame-face" aria-hidden="true"><Icon name="frame" size={24} /></span>
						</FreshnessRing>
						{#if asked}<span class="ask-badge" aria-hidden="true">💛</span>{/if}
						<span class="nm">{f.name}</span>
						<span id="fr-{f.id}" class="words" class:asked>
							{#if asked}{t('engagement.wouldLike')}{:else if discovery.engagement}{words(freshnessWords(f.freshness))}{/if}
						</span>
					</button>
				</li>
			{/each}
			{#each families as fd (fd.id)}
				<li>
					<a class="tile" href="/feeds/{fd.id}">
						<FreshnessRing freshness={discovery.engagement ? fd.freshness : null} size={68}>
							<FeedCover name={fd.name} url={fd.cover_url} size={56} />
						</FreshnessRing>
						{#if fd.streak && fd.streak.current_weeks > 0}<span class="streak num" aria-hidden="true">🔥{fd.streak.current_weeks}</span>{/if}
						<span class="nm">{fd.name}</span>
						<span class="words">
							{#if fd.streak && fd.streak.current_weeks > 0}{tn('engagement.streakWeeks', fd.streak.current_weeks)}{:else if discovery.engagement}{words(freshnessWords(fd.freshness))}{/if}
						</span>
					</a>
				</li>
			{/each}
		</ul>
	</nav>
{/if}

<style>
	.strip {
		margin-inline: calc(-1 * var(--s-4));
	}
	ul {
		list-style: none;
		margin: 0;
		padding: var(--s-1) var(--s-4) var(--s-2);
		display: flex;
		gap: var(--s-3);
		overflow-x: auto;
		scrollbar-width: thin;
	}
	.tile {
		position: relative;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 4px;
		width: 92px;
		padding: 6px 2px;
		border: 0;
		border-radius: var(--r);
		background: transparent;
		color: var(--text);
		font: inherit;
		text-decoration: none;
		cursor: pointer;
	}
	.tile:hover {
		background: var(--surface-2);
	}
	.frame-face {
		display: grid;
		place-items: center;
		background: var(--surface-2);
		color: var(--text-muted);
	}
	.nm {
		font-weight: 650;
		font-size: 0.82rem;
		max-width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.words {
		font-size: 0.72rem;
		color: var(--text-muted);
		text-align: center;
		line-height: 1.2;
		min-height: 1.2em;
	}
	.words.asked {
		color: var(--primary-soft-text);
		font-weight: 700;
	}
	.ask-badge,
	.streak {
		position: absolute;
		top: 2px;
		inset-inline-end: 8px;
		padding: 1px 6px;
		border-radius: var(--r-full);
		background: var(--surface);
		box-shadow: var(--shadow-sm);
		font-size: 0.75rem;
		font-weight: 700;
	}
</style>
