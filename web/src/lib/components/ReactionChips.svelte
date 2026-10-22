<script lang="ts">
	import type { ReplyPreset } from '$lib/api/client';
	import { REACTION_EMOJI, tallyReactions, type Reaction } from '$lib/logic';
	import { t } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';

	let {
		reactions = [],
		replies = []
	}: { reactions?: (Reaction | null | undefined)[]; replies?: ReplyPreset[] } = $props();

	const tally = $derived(tallyReactions(reactions));
	const replyTally = $derived.by(() => {
		const m = new Map<ReplyPreset, number>();
		for (const r of replies) m.set(r, (m.get(r) ?? 0) + 1);
		return [...m.entries()];
	});
</script>

{#if tally.length || replyTally.length}
	<ul class="chips" aria-label={t('history.reactions')}>
		{#each tally as r (r.reaction)}
			<li class="chip reaction" in:softFly title={t(`reaction.${r.reaction}`)}>
				<span class="emoji" aria-hidden="true">{REACTION_EMOJI[r.reaction]}</span>
				<span class="visually-hidden">{t(`reaction.${r.reaction}`)}</span>
				{#if r.count > 1}<span class="n num">{r.count}</span>{/if}
			</li>
		{/each}
		{#each replyTally as [preset, n] (preset)}
			<li class="chip reply" in:softFly>
				<span aria-hidden="true">💬</span>
				{t(`reply.${preset}`)}
				{#if n > 1}<span class="n num">×{n}</span>{/if}
			</li>
		{/each}
	</ul>
{/if}

<style>
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.reaction {
		background: var(--surface);
		padding: 2px 9px;
	}
	.emoji {
		font-size: 1rem;
		line-height: 1;
	}
	.reply {
		background: var(--primary-soft);
		border-color: transparent;
		color: var(--primary-soft-text);
		font-weight: 600;
		font-size: 0.82rem;
	}
	.n {
		font-weight: 700;
		font-size: 0.8rem;
		color: var(--text-muted);
	}
</style>
