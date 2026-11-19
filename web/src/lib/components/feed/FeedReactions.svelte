<script lang="ts">
	import type { FeedReaction } from '$lib/api/client';
	import { groupReactions } from '$lib/feeds';
	import { REACTION_EMOJI } from '$lib/logic';
	import { i18n, t } from '$lib/i18n/index.svelte';

	/** Reactions and quick replies of a feed post, with the frames they came from ("❤️ Oma's frame, Kitchen"). */
	let { reactions }: { reactions: FeedReaction[] | null | undefined } = $props();

	const groups = $derived(groupReactions(reactions));
	const list = (names: string[]) => new Intl.ListFormat(i18n.tag, { type: 'conjunction', style: 'short' }).format(names);
</script>

{#if groups.reactions.length || groups.replies.length}
	<ul class="reactions" aria-label={t('history.reactions')}>
		{#each groups.reactions as g (g.reaction)}
			<li class="chip">
				<span class="emoji" aria-hidden="true">{REACTION_EMOJI[g.reaction]}</span>
				<span class="visually-hidden">{t(`reaction.${g.reaction}`)}:</span>
				<span class="who">{list(g.frames)}</span>
			</li>
		{/each}
		{#each groups.replies as r, i (i)}
			<li class="chip reply">
				<span aria-hidden="true">💬</span>
				<span>{t(`reply.${r.preset}`)}</span>
				<span class="who muted">· {r.frame}</span>
			</li>
		{/each}
	</ul>
{/if}

<style>
	.reactions {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}
	.chip {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 3px 10px;
		border-radius: var(--r-full);
		background: var(--surface-2);
		font-size: 0.82rem;
		max-width: 100%;
	}
	.who {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.emoji {
		font-size: 1rem;
	}
</style>
