<script lang="ts">
	import { untrack } from 'svelte';
	import { api, call, type Send } from '$lib/api/client';
	import { recipientsBody } from '$lib/feeds';
	import { feeds } from '$lib/feeds.svelte';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import FeedCover from './FeedCover.svelte';

	/** Post one of your sends to feeds, without a new upload (`forwardSend` with `feed_ids`, feeds.md §6.1). */
	let { open = $bindable(false), send, onforwarded }: { open?: boolean; send: Send; onforwarded?: (s: Send) => void } = $props();

	const uid = $props.id();
	let chosen = $state<string[]>([]);
	let busy = $state(false);

	const already = $derived(new Set((send.feeds ?? []).filter((f) => !f.removed).map((f) => f.feed_id)));
	const choices = $derived(feeds.list.filter((f) => !already.has(f.id)));

	$effect(() => {
		if (!open) return;
		untrack(() => {
			chosen = [];
			void feeds.load();
		});
	});

	function toggle(id: string) {
		chosen = chosen.includes(id) ? chosen.filter((x) => x !== id) : [...chosen, id];
	}

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		if (chosen.length === 0) return;
		busy = true;
		const ids = [...chosen];
		try {
			const res = await call(
				api.POST('/v1/sends/{sendId}/forward', { params: { path: { sendId: send.id } }, body: recipientsBody([], ids) })
			);
			open = false;
			const names = new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format(ids.map((id) => feeds.get(id)?.name ?? ''));
			toasts.success(t('forward.done', { feeds: names }));
			onforwarded?.(res);
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			busy = false;
		}
	}
</script>

<Dialog bind:open title={t('forward.title')}>
	<form id="{uid}-form" class="stack" onsubmit={submit}>
		<p class="muted small">{t('forward.body')}</p>
		{#if choices.length === 0}
			<p class="callout small"><Icon name="info" size={18} />{t('forward.none')}</p>
		{:else}
			<div class="choices" role="group" aria-label={t('send.feedsGroup')}>
				{#each choices as f (f.id)}
					<label class="choice" class:checked={chosen.includes(f.id)}>
						<input type="checkbox" checked={chosen.includes(f.id)} onchange={() => toggle(f.id)} />
						<FeedCover name={f.name} url={f.cover_url} size={32} />
						<span class="c-name">{f.name}</span>
					</label>
				{/each}
			</div>
		{/if}
	</form>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" type="submit" form="{uid}-form" disabled={chosen.length === 0 || busy}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{t('forward.submit')}
		</button>
	{/snippet}
</Dialog>

<style>
	.choices {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.choice {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 12px;
		border-radius: var(--r);
		border: 1.5px solid var(--border);
		cursor: pointer;
	}
	.choice.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 55%, var(--surface));
	}
	.c-name {
		flex: 1;
		min-width: 0;
		font-weight: 600;
	}
</style>
