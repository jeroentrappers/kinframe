<script lang="ts">
	import { untrack } from 'svelte';
	import type { AlbumSchedule } from '$lib/api/client';
	import { daysInMonth, formatMonthDay, monthDay, parseMonthDay } from '$lib/parity';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	let {
		open = $bindable(false),
		albumName,
		schedule,
		onsave
	}: {
		open?: boolean;
		albumName: string;
		schedule: AlbumSchedule | null | undefined;
		onsave: (s: AlbumSchedule | null) => Promise<void>;
	} = $props();

	const id = $props.id();
	let enabled = $state(false);
	let fromM = $state(12);
	let fromD = $state(1);
	let toM = $state(12);
	let toD = $state(31);
	let busy = $state(false);

	// Reset the form each time the dialog opens (and only then).
	$effect(() => {
		if (!open) return;
		const f = parseMonthDay(schedule?.from_month_day);
		const to = parseMonthDay(schedule?.to_month_day);
		untrack(() => {
			enabled = !!(f && to);
			if (f && to) {
				[fromM, fromD, toM, toD] = [f.month, f.day, to.month, to.day];
			} else {
				// Suggest the month we're in, which is usually what people schedule for.
				const month = new Date().getMonth() + 1;
				[fromM, fromD, toM, toD] = [month, 1, month, daysInMonth(month)];
			}
		});
	});
	// Keep days valid when the month changes (31 → 30 in April).
	$effect(() => {
		if (fromD > daysInMonth(fromM)) fromD = daysInMonth(fromM);
		if (toD > daysInMonth(toM)) toD = daysInMonth(toM);
	});

	const months = $derived(
		Array.from({ length: 12 }, (_, i) => ({
			value: i + 1,
			label: new Intl.DateTimeFormat(i18n.tag, { month: 'long', timeZone: 'UTC' }).format(new Date(Date.UTC(2024, i, 1)))
		}))
	);
	const from = $derived(monthDay(fromM, fromD));
	const to = $derived(monthDay(toM, toD));
	const wraps = $derived(from > to);

	async function save() {
		busy = true;
		try {
			await onsave(enabled ? { from_month_day: from, to_month_day: to } : null);
			open = false;
		} catch {
			/* reported by the caller */
		} finally {
			busy = false;
		}
	}
</script>

<Dialog bind:open title={t('albums.scheduleTitle', { name: albumName })}>
	<div class="stack">
		<p class="muted small">{t('albums.scheduleBody')}</p>
		<label class="switch">
			<input type="checkbox" bind:checked={enabled} />
			<span>{t('albums.scheduleOn')}</span>
		</label>
		{#if enabled}
			<fieldset class="range">
				<legend class="label">{t('albums.from')}</legend>
				<div class="md">
					<select class="select" aria-label={t('albums.fromDay')} bind:value={fromD}>
						{#each Array(daysInMonth(fromM)) as _, d (d)}<option value={d + 1}>{d + 1}</option>{/each}
					</select>
					<select class="select" aria-label={t('albums.fromMonth')} bind:value={fromM}>
						{#each months as m (m.value)}<option value={m.value}>{m.label}</option>{/each}
					</select>
				</div>
			</fieldset>
			<fieldset class="range">
				<legend class="label">{t('albums.to')}</legend>
				<div class="md">
					<select class="select" aria-label={t('albums.toDay')} bind:value={toD}>
						{#each Array(daysInMonth(toM)) as _, d (d)}<option value={d + 1}>{d + 1}</option>{/each}
					</select>
					<select class="select" aria-label={t('albums.toMonth')} bind:value={toM}>
						{#each months as m (m.value)}<option value={m.value}>{m.label}</option>{/each}
					</select>
				</div>
			</fieldset>
			<p class="callout small" id="{id}-summary">
				<Icon name="calendar" size={18} />
				<span>
					{t('albums.scheduleSummary', { from: formatMonthDay(from, i18n.tag), to: formatMonthDay(to, i18n.tag) })}
					{#if wraps}{t('albums.scheduleWraps')}{/if}
				</span>
			</p>
		{/if}
	</div>
	{#snippet footer()}
		<button type="button" class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button type="button" class="btn btn-primary" onclick={save} disabled={busy}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{t('common.save')}
		</button>
	{/snippet}
</Dialog>

<style>
	.range {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
		min-width: 0;
	}
	.md {
		display: grid;
		grid-template-columns: 5.5rem 1fr;
		gap: var(--s-2);
	}
	.md select {
		min-width: 0;
	}
	.callout {
		align-items: flex-start;
	}
</style>
