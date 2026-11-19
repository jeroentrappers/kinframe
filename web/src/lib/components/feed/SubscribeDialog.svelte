<script lang="ts">
	import { untrack } from 'svelte';
	import { api, call, type Subscription } from '$lib/api/client';
	import { feeds } from '$lib/feeds.svelte';
	import { can, frames } from '$lib/frames.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { HISTORY_DEFAULT_N, historyBody, type HistoryChoice } from '$lib/feeds';
	import { newIdempotencyKey } from '$lib/logic';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import BirthdaysOption from './BirthdaysOption.svelte';
	import HistoryPicker from './HistoryPicker.svelte';

	/**
	 * "Show on a frame" (feeds.md §3 story 3), from either side: with `feedId` pick one of the frames you manage, with
	 * `frameId` pick one of your feeds. `taken` lists ids (frames or feeds) that already have a live subscription.
	 */
	let {
		open = $bindable(false),
		feedId,
		frameId,
		taken = [],
		onsubscribed
	}: {
		open?: boolean;
		feedId?: string;
		frameId?: string;
		taken?: string[];
		onsubscribed?: (s: Subscription) => void;
	} = $props();

	const uid = $props.id();
	let chosen = $state<string>('');
	let history = $state<HistoryChoice>({ history: 'none', count: HISTORY_DEFAULT_N });
	let birthdays = $state(true);
	let busy = $state(false);
	let error = $state<string | null>(null);
	let key = newIdempotencyKey();

	const frameChoices = $derived(frames.list.filter((f) => can(f, 'change_settings') && !taken.includes(f.id)));
	const feedChoices = $derived(feeds.list.filter((f) => !taken.includes(f.id)));
	const choices = $derived(feedId ? frameChoices.map((f) => ({ id: f.id, name: f.name })) : feedChoices.map((f) => ({ id: f.id, name: f.name })));
	const feedName = $derived(feedId ? (feeds.get(feedId)?.name ?? '') : (feeds.get(chosen)?.name ?? ''));
	$effect(() => {
		if (!open) return;
		untrack(() => {
			void frames.load();
			void feeds.load();
			history = { history: 'none', count: HISTORY_DEFAULT_N };
			birthdays = true;
			error = null;
			key = newIdempotencyKey();
			chosen = '';
		});
	});
	$effect(() => {
		if (open && !chosen && choices.length === 1) chosen = choices[0]!.id;
	});

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		const fid = feedId ?? chosen;
		const frid = frameId ?? chosen;
		if (!fid || !frid) return;
		busy = true;
		error = null;
		try {
			const sub = await call(
				api.POST('/v1/feeds/{feedId}/subscriptions', {
					params: { path: { feedId: fid }, header: { 'Idempotency-Key': key } },
					body: { frame_id: frid, ...historyBody(history), ...(discovery.birthdays ? { birthdays } : {}) }
				})
			);
			open = false;
			toasts.success(
				sub.status === 'pending'
					? t('subs.requested', { feed: sub.feed.name, frame: sub.frame.name })
					: t('subs.subscribedToast', { feed: sub.feed.name, frame: sub.frame.name })
			);
			onsubscribed?.(sub);
		} catch (err) {
			error = isCode(err, 'already_subscribed') ? t('problem.already_subscribed') : problemMessage(err);
			key = newIdempotencyKey();
		} finally {
			busy = false;
		}
	}
</script>

<Dialog bind:open title={feedId ? t('subs.showOnFrameTitle', { feed: feedName }) : t('subs.subscribeFrameTitle')} size="md">
	<form id="{uid}-form" class="stack" onsubmit={submit}>
		{#if choices.length === 0}
			<p class="callout small"><Icon name="info" size={18} />{feedId ? t('subs.noFramesToManage') : t('subs.noFeedsToPick')}</p>
		{:else}
			<fieldset class="choices">
				<legend class="label">{feedId ? t('subs.pickFrame') : t('subs.pickFeed')}</legend>
				{#each choices as c (c.id)}
					<label class="choice" class:checked={chosen === c.id}>
						<input type="radio" name="{uid}-target" value={c.id} bind:group={chosen} />
						<span class="c-name">{c.name}</span>
						<span class="check" aria-hidden="true"><Icon name="check" size={14} stroke={3} /></span>
					</label>
				{/each}
			</fieldset>
			<HistoryPicker bind:value={history} />
			<BirthdaysOption bind:value={birthdays} />
			{#if feedName}<p class="hint privacy"><Icon name="shield" size={15} />{t('subs.privacy', { feed: feedName })}</p>{/if}
		{/if}
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" type="submit" form="{uid}-form" disabled={!chosen || busy}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{t('subs.subscribe')}
		</button>
	{/snippet}
</Dialog>

<style>
	.choices {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.choice {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 10px 12px;
		border-radius: var(--r);
		border: 1.5px solid var(--border);
		cursor: pointer;
	}
	.choice.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 55%, var(--surface));
	}
	.choice:has(input:focus-visible) {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	.choice input {
		position: absolute;
		opacity: 0;
		pointer-events: none;
	}
	.c-name {
		flex: 1;
		min-width: 0;
		font-weight: 600;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.check {
		width: 22px;
		height: 22px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		border: 1.5px solid var(--border-strong);
		color: transparent;
	}
	.checked .check {
		background: var(--primary);
		border-color: var(--primary);
		color: var(--on-primary);
	}
	.privacy {
		display: flex;
		gap: 6px;
		align-items: flex-start;
	}
</style>
