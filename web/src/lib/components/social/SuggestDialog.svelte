<script lang="ts">
	import type { FeedPrompt, FrameSummary, Item, OnThisDay } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { freshnessWords, requestOpen } from '$lib/engagement';
	import { relativeTime } from '$lib/format';
	import { personName } from '$lib/social';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import Thumb from '../Thumb.svelte';
	import { resendItems } from './OnThisDayCard.svelte';

	/** "Send something to Oma's frame" (README §5.20): the suggestions the web can offer. */
	let { open = $bindable(false), frame, onThisDay, prompts }: { open?: boolean; frame: FrameSummary; onThisDay: OnThisDay | null; prompts: { prompt: FeedPrompt; family: string }[] } = $props();

	const asked = $derived(requestOpen(frame.photo_request));
	const quiet = $derived.by(() => {
		const w = freshnessWords(frame.freshness);
		if (!w || w.key === 'fresh') return '';
		if (w.key === 'never') return t('engagement.quietNever', { frame: frame.name });
		const days = frame.freshness?.days_quiet ?? 0;
		return tn('engagement.quietFor', days, { frame: frame.name });
	});
	const mine = $derived((onThisDay?.groups ?? []).flatMap((g) => g.items.filter((i) => i.sender_id === auth.me?.id)).slice(0, 12));
	let sending = $state(false);

	async function resend(items: Item[]) {
		sending = true;
		try {
			await resendItems(items, { frame_ids: [frame.id], feed_ids: [], circle_ids: [] });
			toasts.success(t('send.sent'));
			open = false;
		} catch {
			toasts.error(t('problem.server_error'));
		} finally {
			sending = false;
		}
	}
</script>

<Dialog bind:open title={t('engagement.sendSomething', { frame: frame.name })}>
	<div class="stack">
		{#if asked && frame.photo_request}
			<p class="callout callout-warn"><span aria-hidden="true">💛</span>{t('engagement.askedAt', { frame: frame.name, when: relativeTime(frame.photo_request.created_at, i18n.tag) })}</p>
		{:else if frame.photo_request?.state === 'answered'}
			<p class="callout callout-ok"><Icon name="check" size={18} />{t('engagement.answeredBy', { name: personName(frame.photo_request.answered_by, t('social.formerMember')) })}</p>
		{/if}
		{#if quiet}<p class="muted">{quiet}</p>{/if}
		{#if frame.freshness?.last_shared_by}
			<p class="small muted">{t('engagement.lastSharedBy', { name: personName(frame.freshness.last_shared_by, t('social.formerMember')), when: relativeTime(frame.freshness.last_shared_at, i18n.tag) })}</p>
		{/if}
		<a class="btn btn-primary btn-block" href="/send?frame={frame.id}"><Icon name="image" size={18} />{t('engagement.choosePhotos')}</a>

		{#if mine.length > 0}
			<section class="stack-sm" aria-labelledby="sg-otd">
				<h3 id="sg-otd">{t('engagement.onThisDay')}</h3>
				<ul class="thumbs" role="list">
					{#each mine.slice(0, 6) as it (it.id)}<li><Thumb itemId={it.id} variant="thumb_256" kind={it.kind} radius="var(--r-sm)" /></li>{/each}
				</ul>
				<button class="btn btn-soft btn-sm" disabled={sending} onclick={() => resend(mine)}>
					{#if sending}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="send" size={16} />{/if}{tn('engagement.sendTheseTo', mine.length, { frame: frame.name })}
				</button>
			</section>
		{/if}

		{#each prompts as p (p.prompt.id)}
			<section class="prompt-mini">
				<span aria-hidden="true">💡</span>
				<div>
					<p class="small muted">{t('engagement.thisWeekIn', { family: p.family })}</p>
					<a href="/send?feed={p.prompt.feed_id}&prompt={p.prompt.id}">{p.prompt.title}</a>
				</div>
			</section>
		{/each}
	</div>
</Dialog>

<style>
	h3 {
		font-size: 0.95rem;
	}
	.thumbs {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(6, 1fr);
		gap: 4px;
	}
	.thumbs li {
		aspect-ratio: 1;
	}
	.prompt-mini {
		display: flex;
		gap: var(--s-3);
		align-items: flex-start;
		padding: var(--s-3);
		border-radius: var(--r);
		background: var(--warn-soft);
	}
	.prompt-mini p {
		margin: 0;
	}
	.prompt-mini a {
		font-weight: 700;
		color: inherit;
	}
</style>
