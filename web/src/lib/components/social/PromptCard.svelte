<script lang="ts">
	import type { FeedPrompt } from '$lib/api/client';
	import { t, tn } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';

	/** This week's prompt of one family: answer it with a photo, or see the answers (README §5.20). */
	let { prompt, familyName, canPost = true }: { prompt: FeedPrompt; familyName: string; canPost?: boolean } = $props();
</script>

<section class="card prompt" aria-labelledby="pr-{prompt.id}">
	<span class="bulb" aria-hidden="true">💡</span>
	<div class="body">
		<p class="eyebrow small muted">{t('engagement.thisWeekIn', { family: familyName })}</p>
		<h2 id="pr-{prompt.id}">{prompt.title}</h2>
		<div class="row-wrap">
			{#if canPost}
				<a class="btn btn-sm btn-primary" href="/send?feed={prompt.feed_id}&prompt={prompt.id}"><Icon name="image" size={16} />{t('engagement.answerPrompt')}</a>
			{/if}
			{#if (prompt.answer_count ?? 0) > 0}
				<a class="btn btn-sm btn-ghost" href="/home?prompt={prompt.id}">{tn('engagement.answerCount', prompt.answer_count ?? 0)}</a>
			{/if}
		</div>
	</div>
</section>

<style>
	.prompt {
		display: flex;
		gap: var(--s-3);
		padding: var(--s-4);
		align-items: flex-start;
		border-inline-start: 4px solid var(--sun, #e9a23b);
	}
	.bulb {
		font-size: 1.6rem;
	}
	.body {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		min-width: 0;
	}
	.eyebrow {
		margin: 0;
	}
	h2 {
		font-size: 1.15rem;
		font-family: var(--font-display);
	}
</style>
