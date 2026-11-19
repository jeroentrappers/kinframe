<script lang="ts">
	import type { SubscriptionHistory } from '$lib/api/client';
	import { clampHistoryCount, HISTORY_MAX, type HistoryChoice } from '$lib/feeds';
	import { t } from '$lib/i18n/index.svelte';

	/** What a subscription places from before it started: only new posts, the last N, or everything (feeds.md §5.2). */
	let { value = $bindable(), critical = false }: { value: HistoryChoice; critical?: boolean } = $props();
	const uid = $props.id();

	const OPTIONS: { id: SubscriptionHistory; label: 'subs.historyNone' | 'subs.historyLastN' | 'subs.historyAll' }[] = [
		{ id: 'none', label: 'subs.historyNone' },
		{ id: 'last_n', label: 'subs.historyLastN' },
		{ id: 'all', label: 'subs.historyAll' }
	];
</script>

<fieldset class="history">
	<legend class="label">{t('subs.history')}</legend>
	{#each OPTIONS as o (o.id)}
		<div class="opt" class:checked={value.history === o.id}>
			<label class="r">
				<input type="radio" name="{uid}-history" value={o.id} checked={value.history === o.id} onchange={() => (value = { ...value, history: o.id })} />
				<span>{t(o.label)}</span>
			</label>
			{#if o.id === 'last_n' && value.history === 'last_n'}
				<span class="n">
					<label for="{uid}-n" class="visually-hidden">{t('subs.historyCount')}</label>
					<input
						id="{uid}-n"
						class="input num"
						type="number"
						min="1"
						max={HISTORY_MAX}
						value={value.count}
						onchange={(e) => (value = { ...value, count: clampHistoryCount(Number(e.currentTarget.value)) })}
					/>
					<span class="muted small">{t('subs.historyCountUnit')}</span>
				</span>
			{/if}
		</div>
	{/each}
	<p class="hint">{t('subs.historyHint')}</p>
	{#if critical && value.history !== 'none'}
		<p class="callout callout-warn small">{t('subs.storageCritical')}</p>
	{/if}
</fieldset>

<style>
	.history {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.opt {
		display: flex;
		align-items: center;
		gap: 10px;
		flex-wrap: wrap;
		padding: 8px 12px;
		border-radius: var(--r);
		border: 1.5px solid var(--border);
	}
	.r {
		display: inline-flex;
		align-items: center;
		gap: 10px;
		cursor: pointer;
		flex: 1;
		min-width: 0;
	}
	.opt.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 45%, var(--surface));
	}
	.n {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		margin-inline-start: auto;
	}
	.n .input {
		width: 6.5em;
		padding-block: 4px;
	}
</style>
