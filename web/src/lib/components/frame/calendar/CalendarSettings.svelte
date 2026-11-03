<script lang="ts">
	import { untrack } from 'svelte';
	import type { CalendarSchedule, FrameSettings } from '$lib/api/client';
	import { ALL_DAYS, newTimedView, type Day } from '$lib/calendar';
	import { patchFrameSettings } from '$lib/frameSettings';
	import { mergePatchDiff, type JsonObject } from '$lib/logic';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Icon from '../../Icon.svelte';

	let { frameId, settings, onsaved }: { frameId: string; settings: FrameSettings; onsaved: (s: FrameSettings) => void } = $props();

	type Form = {
		calendar: {
			view: 'four_day' | 'week' | 'month';
			start_on_today: boolean;
			fade_past: boolean;
			week_numbers: boolean;
			past_days: number;
			future_days: number;
			timed_views: CalendarSchedule[];
		};
		widgets: { calendar_sidebar: boolean; countdowns: boolean };
	};

	function fromSettings(s: FrameSettings): Form {
		const c = s.calendar ?? {};
		return JSON.parse(
			JSON.stringify({
				calendar: {
					view: c.view ?? 'week',
					start_on_today: c.start_on_today ?? true,
					fade_past: c.fade_past ?? true,
					week_numbers: c.week_numbers ?? false,
					past_days: c.past_days ?? 30,
					future_days: c.future_days ?? 30,
					timed_views: c.timed_views ?? []
				},
				widgets: { calendar_sidebar: s.widgets?.calendar_sidebar ?? false, countdowns: s.widgets?.countdowns ?? true }
			})
		);
	}

	const base = $derived(fromSettings(settings));
	let form = $state<Form>(untrack(() => fromSettings(settings)));
	$effect(() => {
		form = fromSettings(settings);
	});
	const patch = $derived(mergePatchDiff(base as unknown as JsonObject, form as unknown as JsonObject));
	const dirty = $derived(Object.keys(patch).length > 0);
	let saving = $state(false);

	const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, Math.round(Number.isFinite(v) ? v : lo)));

	async function save() {
		if (!dirty) return;
		form.calendar.past_days = clamp(form.calendar.past_days, 1, 365);
		form.calendar.future_days = clamp(form.calendar.future_days, 7, 365);
		saving = true;
		try {
			const saved = await patchFrameSettings(frameId, patch, settings.version);
			onsaved(saved);
			toasts.success(t('cal.settingsSaved'));
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			saving = false;
		}
	}

	function toggleDay(s: CalendarSchedule, d: Day) {
		s.days = s.days.includes(d) ? s.days.filter((x) => x !== d) : ALL_DAYS.filter((x) => x === d || s.days.includes(x));
	}
</script>

<form
	class="stack"
	onsubmit={(e) => {
		e.preventDefault();
		void save();
	}}
>
	<fieldset class="fs">
		<legend class="label">{t('cal.setView')}</legend>
		<div class="segmented">
			<label><input type="radio" name="cal-view" value="four_day" bind:group={form.calendar.view} />{t('cal.setViewFour')}</label>
			<label><input type="radio" name="cal-view" value="week" bind:group={form.calendar.view} />{t('cal.setViewWeek')}</label>
			<label><input type="radio" name="cal-view" value="month" bind:group={form.calendar.view} />{t('cal.setViewMonth')}</label>
		</div>
	</fieldset>
	<label class="switch">
		<input type="checkbox" bind:checked={form.calendar.start_on_today} />
		<span><span class="s-label">{t('cal.setStartToday')}</span><span class="hint">{t('cal.setStartTodayHint')}</span></span>
	</label>
	<label class="switch"><input type="checkbox" bind:checked={form.calendar.fade_past} /><span>{t('cal.setFadePast')}</span></label>
	<label class="switch"><input type="checkbox" bind:checked={form.calendar.week_numbers} /><span>{t('cal.setWeekNumbers')}</span></label>
	<label class="switch">
		<input type="checkbox" bind:checked={form.widgets.calendar_sidebar} />
		<span><span class="s-label">{t('cal.setSidebar')}</span><span class="hint">{t('cal.setSidebarHint')}</span></span>
	</label>
	<label class="switch">
		<input type="checkbox" bind:checked={form.widgets.countdowns} />
		<span><span class="s-label">{t('cal.setCountdowns')}</span><span class="hint">{t('cal.setCountdownsHint')}</span></span>
	</label>
	<fieldset class="fs">
		<legend class="label">{t('cal.setWindow')}</legend>
		<div class="window">
			<label><span class="tiny muted">{t('cal.setPast')}</span><input class="input num" type="number" min="1" max="365" bind:value={form.calendar.past_days} /></label>
			<label><span class="tiny muted">{t('cal.setFuture')}</span><input class="input num" type="number" min="7" max="365" bind:value={form.calendar.future_days} /></label>
		</div>
	</fieldset>

	<div class="timed">
		<div class="section-title">
			<h3>{t('cal.timedTitle')}</h3>
			<button type="button" class="btn btn-ghost btn-sm" onclick={() => (form.calendar.timed_views = [...form.calendar.timed_views, newTimedView(crypto.randomUUID())])}>
				<Icon name="plus" size={16} />{t('cal.timedAdd')}
			</button>
		</div>
		<p class="hint">{t('cal.timedHint')}</p>
		{#if form.calendar.timed_views.length === 0}
			<p class="muted small empty">{t('cal.timedNone')}</p>
		{/if}
		{#each form.calendar.timed_views as s, i (s.id)}
			<fieldset class="schedule" class:off={!s.enabled} in:softFly>
				<legend class="visually-hidden">{t('cal.timedN', { n: i + 1 })}</legend>
				<div class="sched-top">
					<label class="switch compact"><input type="checkbox" bind:checked={s.enabled} /><span class="visually-hidden">{t('settingsForm.enabled')}</span></label>
					<div class="times">
						<label><span class="tiny muted">{t('cal.timedFrom')}</span><input class="input time" type="time" bind:value={s.from} required /></label>
						<span class="arrow" aria-hidden="true">→</span>
						<label><span class="tiny muted">{t('cal.timedTo')}</span><input class="input time" type="time" bind:value={s.to} required /></label>
					</div>
					<button
						type="button"
						class="btn btn-danger-ghost btn-icon btn-sm"
						aria-label={t('cal.timedRemove')}
						onclick={() => (form.calendar.timed_views = form.calendar.timed_views.filter((x) => x.id !== s.id))}
					>
						<Icon name="trash" size={16} />
					</button>
				</div>
				<div class="days" role="group" aria-label={t('settingsForm.days')}>
					{#each ALL_DAYS as d}
						<button type="button" class="day" aria-pressed={s.days.includes(d)} onclick={() => toggleDay(s, d)}>{t(`day.${d}`)}</button>
					{/each}
				</div>
			</fieldset>
		{/each}
	</div>

	<div class="actions">
		<button type="button" class="btn btn-ghost btn-sm" disabled={!dirty || saving} onclick={() => (form = fromSettings(settings))}>{t('settingsForm.discard')}</button>
		<button type="submit" class="btn btn-primary btn-sm" disabled={!dirty || saving}>
			{#if saving}<span class="spinner" aria-hidden="true"></span>{/if}{t('common.save')}
		</button>
	</div>
</form>

<style>
	.fs {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
		min-width: 0;
	}
	.fs .segmented {
		align-self: flex-start;
	}
	.switch > span:not(.visually-hidden) {
		display: flex;
		flex-direction: column;
	}
	.s-label {
		font-weight: 500;
	}
	.window {
		display: flex;
		gap: var(--s-3);
	}
	.window label {
		display: flex;
		flex-direction: column;
		gap: 2px;
		width: 120px;
	}
	h3 {
		font-size: 1.02rem;
	}
	.timed {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		padding-top: var(--s-3);
		border-top: 1px solid var(--border);
	}
	.timed .section-title {
		margin: 0;
	}
	.timed .hint {
		margin: 0;
	}
	.empty {
		padding: var(--s-3);
		border-radius: var(--r);
		background: var(--surface-2);
		text-align: center;
		margin: 0;
	}
	.schedule {
		min-width: 0;
		border: 1px solid var(--border);
		border-radius: var(--r);
		padding: var(--s-3);
		margin: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
	}
	.schedule.off .times,
	.schedule.off .days {
		opacity: 0.5;
	}
	.sched-top {
		display: flex;
		align-items: center;
		gap: var(--s-2);
	}
	.switch.compact {
		min-height: 0;
	}
	.times {
		min-width: 0;
		display: flex;
		align-items: flex-end;
		gap: var(--s-2);
		flex: 1;
	}
	.times label {
		flex: 1;
		min-width: 0;
		max-width: 150px;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.time {
		width: 100%;
		min-width: 0;
		min-height: 40px;
		padding: 6px 4px 6px 8px;
		font-variant-numeric: tabular-nums;
	}
	.arrow {
		padding-bottom: 9px;
		color: var(--text-faint);
	}
	.days {
		display: flex;
		gap: 4px;
		flex-wrap: wrap;
	}
	.day {
		min-width: 40px;
		height: 36px;
		padding: 0 8px;
		border-radius: var(--r-full);
		border: 1px solid var(--border-strong);
		background: var(--surface);
		font-weight: 600;
		font-size: 0.8rem;
		cursor: pointer;
		color: var(--text);
	}
	.day[aria-pressed='true'] {
		background: var(--primary);
		border-color: var(--primary);
		color: var(--on-primary);
	}
	.actions {
		display: flex;
		justify-content: flex-end;
		gap: 8px;
	}
</style>
