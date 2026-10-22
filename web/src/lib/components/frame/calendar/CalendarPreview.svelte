<script lang="ts">
	import { api, call, type CalendarView } from '$lib/api/client';
	import {
		addDays,
		colorVars,
		daysUntil,
		eventSpan,
		eventsByDay,
		formatTemp,
		isoWeek,
		isPast,
		localTime,
		monthGrid,
		today as todayIn,
		weatherByDate,
		weatherKind,
		weekdayOrder,
		type CalendarEvent,
		type FirstDay,
		type WeatherDay
	} from '$lib/calendar';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn, type MessageKey } from '$lib/i18n/index.svelte';
	import Dialog from '../../Dialog.svelte';
	import Icon from '../../Icon.svelte';
	import WeatherGlyph from './WeatherGlyph.svelte';

	let {
		frameId,
		view,
		firstDay = 'mon',
		weekNumbers = false,
		fadePast = true,
		unit = 'c',
		clock24 = true,
		manage = false,
		onchange
	}: {
		frameId: string;
		view: CalendarView;
		firstDay?: FirstDay;
		weekNumbers?: boolean;
		fadePast?: boolean;
		unit?: 'c' | 'f';
		clock24?: boolean;
		manage?: boolean;
		onchange?: () => void;
	} = $props();

	let mode = $state<'month' | 'agenda'>('agenda');
	const tz = $derived(view.timezone || 'UTC');
	const now = $state(new Date());
	const today = $derived(todayIn(tz, now));
	let cursor = $state<{ y: number; m: number } | null>(null);
	const ym = $derived(cursor ?? { y: Number(today.slice(0, 4)), m: Number(today.slice(5, 7)) });

	const colors = $derived(new Map(view.sources.map((s) => [s.id, s.color])));
	const sourceName = $derived(new Map(view.sources.map((s) => [s.id, s.kind === 'local' && !s.name ? t('cal.received') : s.name])));
	const wx = $derived(weatherByDate(view.weather));
	const weeks = $derived(monthGrid(ym.y, ym.m, firstDay));
	const byDay = $derived(eventsByDay(view.events, tz, addDays(view.window.from, -7), addDays(view.window.to, 7)));
	const dayKeys = $derived(weekdayOrder(firstDay));

	const monthStart = (y: number, m: number) => `${y}-${String(m).padStart(2, '0')}-01`;
	const canPrev = $derived(monthStart(ym.y, ym.m) > view.window.from);
	const canNext = $derived(monthStart(ym.m === 12 ? ym.y + 1 : ym.y, ym.m === 12 ? 1 : ym.m + 1) <= view.window.to);

	function step(dir: 1 | -1) {
		let { y, m } = ym;
		m += dir;
		if (m === 0) [y, m] = [y - 1, 12];
		if (m === 13) [y, m] = [y + 1, 1];
		cursor = { y, m };
	}

	const monthLabel = $derived(new Intl.DateTimeFormat(i18n.tag, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(Date.UTC(ym.y, ym.m - 1, 1))));

	/**
	 * Agenda: today up to the end of the window, days with events (today always). Multi-day events
	 * show once, on their first day in the list, with their date range.
	 */
	const agenda = $derived.by(() => {
		const days: { day: string; events: CalendarEvent[] }[] = [];
		for (let d = today; d <= view.window.to && days.length < 60; d = addDays(d, 1)) {
			const events = (byDay.get(d) ?? []).filter((ev) => {
				const [s, e] = eventSpan(ev, tz);
				return s === e || d === (s > today ? s : today);
			});
			if (d === today || events.length > 0) days.push({ day: d, events });
		}
		return days;
	});

	const dayLabel = (d: string, opts: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(i18n.tag, { ...opts, timeZone: 'UTC' }).format(new Date(`${d}T12:00:00Z`));

	function timeLabel(ev: CalendarEvent, day: string, agendaRow = false): string {
		const [s, e] = eventSpan(ev, tz);
		if (agendaRow && s !== e) return `${dayLabel(s, { day: 'numeric', month: 'short' })} – ${dayLabel(e, { day: 'numeric', month: 'short' })}`;
		if (ev.all_day) return t('cal.allDay');
		if (s !== e && day !== s) return day === e && ev.end_at ? `→ ${localTime(ev.end_at, tz, i18n.tag, !clock24)}` : t('cal.allDay');
		const start = localTime(ev.start_at!, tz, i18n.tag, !clock24);
		return ev.end_at && s === e ? `${start} – ${localTime(ev.end_at, tz, i18n.tag, !clock24)}` : start;
	}

	function weatherLabel(w: WeatherDay): string {
		return t('cal.weatherDay', { kind: t(`cal.wx.${weatherKind(w.code)}` as MessageKey), min: formatTemp(w.min_c, unit), max: formatTemp(w.max_c, unit) });
	}

	// ---- event details and countdowns
	let detail = $state<CalendarEvent | null>(null);
	let detailOpen = $state(false);
	let busy = $state(false);

	function openEvent(ev: CalendarEvent) {
		detail = ev;
		detailOpen = true;
	}

	async function toggleCountdown(ev: CalendarEvent) {
		busy = true;
		const on = !ev.countdown;
		try {
			const params = { params: { path: { frameId, eventId: ev.id } } };
			await call(on ? api.PUT('/v1/frames/{frameId}/countdowns/{eventId}', params) : api.DELETE('/v1/frames/{frameId}/countdowns/{eventId}', params));
			ev.countdown = on;
			for (const e of view.events) if (e.id === ev.id) e.countdown = on;
			toasts.success(on ? t('cal.countdownOn') : t('cal.countdownOff'));
			onchange?.();
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = false;
		}
	}

	function untilLabel(n: number): string {
		return n <= 0 ? t('cal.isToday') : tn('cal.inDays', n);
	}
</script>

<div class="preview">
	<div class="toolbar">
		<div class="segmented" role="group" aria-label={t('cal.previewTitle')}>
			<button type="button" aria-pressed={mode === 'agenda'} onclick={() => (mode = 'agenda')}><Icon name="history" size={16} />{t('cal.viewAgenda')}</button>
			<button type="button" aria-pressed={mode === 'month'} onclick={() => (mode = 'month')}><Icon name="calendar" size={16} />{t('cal.viewMonth')}</button>
		</div>
		{#if mode === 'month'}
			<div class="nav">
				<button type="button" class="btn btn-ghost btn-icon btn-sm" aria-label={t('cal.prev')} disabled={!canPrev} onclick={() => step(-1)}><Icon name="chevronLeft" size={18} /></button>
				<h3 class="month" aria-live="polite">{monthLabel}</h3>
				<button type="button" class="btn btn-ghost btn-icon btn-sm" aria-label={t('cal.next')} disabled={!canNext} onclick={() => step(1)}><Icon name="chevronRight" size={18} /></button>
				<button type="button" class="btn btn-sm" onclick={() => (cursor = null)}>{t('cal.today')}</button>
			</div>
		{/if}
	</div>

	{#if view.countdowns.length > 0}
		<ul class="countdowns" aria-label={t('cal.countdowns')}>
			{#each view.countdowns as c (c.event_id)}
				<li class="cd cal-color" style={colorVars(c.color)}>
					<span class="cd-days num">{untilLabel(daysUntil(c, tz, now))}</span>
					<span class="cd-title">{c.title}</span>
				</li>
			{/each}
		</ul>
	{/if}

	{#if mode === 'agenda'}
		<ol class="agenda">
			{#each agenda as { day: d, events } (d)}
				{@const w = wx.get(d)}
				<li class="day" class:is-today={d === today}>
					<div class="day-head">
						<div class="day-name">
							<span class="dow">{d === today ? t('cal.today') : dayLabel(d, { weekday: 'long' })}</span>
							<span class="date muted">{dayLabel(d, { day: 'numeric', month: 'long' })}</span>
						</div>
						{#if w}
							<div class="wx" title={weatherLabel(w)}>
								<WeatherGlyph code={w.code} size={24} />
								<span class="temps num"><strong>{formatTemp(w.max_c, unit)}</strong><span class="muted">{formatTemp(w.min_c, unit)}</span></span>
								{#if w.precip_prob != null && w.precip_prob >= 10}<span class="rain tiny num">{t('cal.rain', { pct: w.precip_prob })}</span>{/if}
							</div>
						{/if}
					</div>
					{#if events.length === 0}
						<p class="muted small empty">{t('cal.previewEmpty')}</p>
					{:else}
						<ul class="events">
							{#each events as ev (ev.id)}
								<li>
									<button type="button" class="ev cal-color" class:all-day={ev.all_day} class:past={fadePast && isPast(ev, tz, now)} style={colorVars(colors.get(ev.source_id))} onclick={() => openEvent(ev)}>
										<span class="ev-time num">{timeLabel(ev, d, true)}</span>
										<span class="ev-main">
											<span class="ev-title">{ev.title}</span>
											{#if ev.location}<span class="ev-loc tiny"><Icon name="mapPin" size={12} />{ev.location}</span>{/if}
										</span>
										{#if ev.sender_name}<Icon name="heart" size={14} class="ev-heart" label={t('cal.sentBy', { name: ev.sender_name })} />{/if}
										{#if ev.countdown}<Icon name="star" size={14} label={t('cal.countdowns')} />{/if}
									</button>
								</li>
							{/each}
						</ul>
					{/if}
				</li>
			{/each}
		</ol>
	{:else}
		<div class="month-grid" role="grid" aria-label={monthLabel}>
			<div class="mrow head" role="row">
				{#each dayKeys as k}<span role="columnheader" class="dow-h">{t(`day.${k}`)}</span>{/each}
			</div>
			{#each weeks as week (week[0])}
				<div class="mrow" role="row">
					{#each week as d, i (d)}
						{@const events = byDay.get(d) ?? []}
						{@const w = wx.get(d)}
						{@const outside = Number(d.slice(5, 7)) !== ym.m}
						<div class="cell" class:outside class:is-today={d === today} class:past={fadePast && d < today} role="gridcell" aria-label={dayLabel(d, { weekday: 'long', day: 'numeric', month: 'long' })}>
							<div class="cell-head">
								<span class="daynum-wrap">
									<span class="num daynum">{Number(d.slice(8))}</span>
									{#if weekNumbers && i === 0}<span class="wk num" title={t('cal.week', { n: isoWeek(d) })}>{t('cal.weekShort', { n: isoWeek(d) })}</span>{/if}
								</span>
								{#if w}
									<span class="cell-wx" title={weatherLabel(w)}><WeatherGlyph code={w.code} size={16} /><span class="tiny num">{formatTemp(w.max_c, unit)}</span></span>
								{/if}
							</div>
							<div class="cell-events">
								{#each events.slice(0, 3) as ev (ev.id)}
									<button type="button" class="chip-ev cal-color" class:bar={ev.all_day || eventSpan(ev, tz)[0] !== eventSpan(ev, tz)[1]} style={colorVars(colors.get(ev.source_id))} onclick={() => openEvent(ev)} title={ev.title}>
										<span class="t">{ev.title}</span>
									</button>
								{/each}
								{#if events.length > 3}<span class="more tiny">{t('cal.more', { count: events.length - 3 })}</span>{/if}
							</div>
						</div>
					{/each}
				</div>
			{/each}
		</div>
	{/if}

	<p class="foot tiny muted">
		<span><Icon name="clock" size={13} />{t('cal.timesIn', { tz })}</span>
		{#if view.weather}<a href="https://open-meteo.com/" target="_blank" rel="noopener noreferrer"><Icon name="cloud" size={13} />{t('cal.weatherCredit')}</a>{/if}
	</p>
</div>

<Dialog bind:open={detailOpen} title={detail?.title ?? ''}>
	{#if detail}
		{@const [s, e] = eventSpan(detail, tz)}
		<div class="detail stack-sm cal-color" style={colorVars(colors.get(detail.source_id))}>
			<p class="d-row"><span class="d-dot" aria-hidden="true"></span>{sourceName.get(detail.source_id) ?? ''}</p>
			<p class="d-row">
				<Icon name="clock" size={16} />
				{#if detail.all_day}
					{dayLabel(s, { weekday: 'long', day: 'numeric', month: 'long' })}{s !== e ? ` – ${dayLabel(e, { weekday: 'long', day: 'numeric', month: 'long' })}` : ''}
				{:else}
					{dayLabel(s, { weekday: 'long', day: 'numeric', month: 'long' })}, {timeLabel(detail, s)}
				{/if}
			</p>
			{#if detail.recurring}<p class="d-row muted"><Icon name="refresh" size={16} />{t('cal.recurring')}</p>{/if}
			{#if detail.location}<p class="d-row"><Icon name="mapPin" size={16} />{detail.location}</p>{/if}
			{#if detail.sender_name}<p class="d-row"><Icon name="heart" size={16} />{t('cal.sentBy', { name: detail.sender_name })}</p>{/if}
			{#if detail.notes}<p class="notes">{detail.notes}</p>{/if}
			{#if manage}
				<label class="switch">
					<input type="checkbox" checked={detail.countdown} disabled={busy} onchange={() => detail && toggleCountdown(detail)} />
					<span>{t('cal.countdownToggle')}</span>
				</label>
			{/if}
		</div>
	{/if}
</Dialog>

<style>
	.preview {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		min-width: 0;
	}
	.toolbar {
		display: flex;
		flex-wrap: wrap;
		gap: var(--s-3);
		align-items: center;
		justify-content: space-between;
	}
	.nav {
		display: flex;
		align-items: center;
		gap: 4px;
	}
	.month {
		font-size: 1rem;
		min-width: 9.5em;
		text-align: center;
		text-transform: capitalize;
	}
	.countdowns {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		gap: 8px;
		overflow-x: auto;
		scrollbar-width: thin;
	}
	.cd {
		flex: none;
		display: flex;
		flex-direction: column;
		gap: 0;
		padding: 8px 14px 8px 12px;
		border-radius: var(--r);
		background: color-mix(in srgb, var(--ev) 16%, var(--surface));
		border-inline-start: 4px solid var(--ev);
		max-width: 220px;
	}
	.cd-days {
		font-family: var(--font-display);
		font-size: 1.05rem;
		font-weight: 600;
	}
	.cd-title {
		font-size: 0.85rem;
		color: var(--text-muted);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.agenda {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
	}
	.day {
		border-radius: var(--r);
		padding: var(--s-3);
		background: var(--surface-2);
	}
	.day.is-today {
		background: color-mix(in srgb, var(--primary-soft) 70%, var(--surface));
	}
	.day-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-3);
		margin-bottom: 8px;
	}
	.day-name {
		display: flex;
		align-items: baseline;
		gap: 8px;
		flex-wrap: wrap;
		min-width: 0;
	}
	.dow {
		font-weight: 700;
		text-transform: capitalize;
	}
	.date {
		font-size: 0.88rem;
	}
	.wx {
		display: flex;
		align-items: center;
		gap: 6px;
		flex: none;
	}
	.temps {
		display: flex;
		gap: 6px;
		font-size: 0.9rem;
	}
	.rain {
		color: #2f78b7;
		background: color-mix(in srgb, #4f9fdf 14%, transparent);
		padding: 1px 6px;
		border-radius: var(--r-full);
	}
	@media (prefers-color-scheme: dark) {
		.rain {
			color: #87bfeb;
		}
	}
	.empty {
		margin: 0;
	}
	.events {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.ev {
		width: 100%;
		display: flex;
		align-items: center;
		gap: 10px;
		text-align: start;
		padding: 8px 10px;
		border: 0;
		border-inline-start: 4px solid var(--ev);
		border-radius: var(--r-sm);
		background: var(--surface);
		color: var(--text);
		cursor: pointer;
		font: inherit;
	}
	.ev.all-day {
		background: color-mix(in srgb, var(--ev) 22%, var(--surface));
	}
	.ev:hover {
		background: color-mix(in srgb, var(--ev) 12%, var(--surface));
	}
	.ev.past {
		opacity: 0.55;
	}
	.ev-time {
		flex: none;
		min-width: 6.2em;
		font-size: 0.85rem;
		color: var(--text-muted);
	}
	.ev-main {
		display: flex;
		flex-direction: column;
		min-width: 0;
		flex: 1;
	}
	.ev-title {
		font-weight: 600;
		overflow-wrap: anywhere;
	}
	.ev-loc {
		display: inline-flex;
		align-items: center;
		gap: 3px;
		color: var(--text-muted);
	}
	.ev :global(svg:last-child) {
		color: var(--ev);
	}
	/* ---- month */
	.month-grid {
		display: flex;
		flex-direction: column;
		border: 1px solid var(--border);
		border-radius: var(--r);
		overflow: hidden;
	}
	.mrow {
		display: grid;
		grid-template-columns: repeat(7, minmax(0, 1fr));
	}
	.mrow.head {
		background: var(--surface-2);
	}
	.dow-h {
		padding: 6px;
		font-size: 0.75rem;
		font-weight: 700;
		color: var(--text-muted);
		text-align: center;
	}
	.daynum-wrap {
		display: inline-flex;
		align-items: center;
		gap: 2px;
		min-width: 0;
	}
	.wk {
		font-size: 0.62rem;
		font-weight: 650;
		color: var(--text-faint);
		letter-spacing: 0.02em;
	}
	.cell {
		min-height: 92px;
		padding: 4px;
		border-top: 1px solid var(--border);
		border-inline-start: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		gap: 3px;
		min-width: 0;
	}
	.cell.outside {
		background: color-mix(in srgb, var(--surface-2) 60%, transparent);
	}
	.cell.outside .daynum {
		color: var(--text-faint);
	}
	.cell.past .cell-events {
		opacity: 0.55;
	}
	.cell-head {
		flex-wrap: wrap;
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 2px;
	}
	.daynum {
		font-size: 0.82rem;
		font-weight: 650;
		width: 24px;
		height: 24px;
		display: grid;
		place-items: center;
		border-radius: 50%;
	}
	.is-today .daynum {
		background: var(--primary);
		color: var(--on-primary);
	}
	.cell-wx {
		display: inline-flex;
		align-items: center;
		gap: 2px;
		color: var(--text-muted);
	}
	.cell-events {
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.chip-ev {
		display: block;
		width: 100%;
		text-align: start;
		border: 0;
		border-inline-start: 3px solid var(--ev);
		border-radius: 4px;
		padding: 1px 4px;
		background: color-mix(in srgb, var(--ev) 10%, transparent);
		color: var(--text);
		font: inherit;
		font-size: 0.72rem;
		line-height: 1.35;
		cursor: pointer;
		min-width: 0;
	}
	.chip-ev.bar {
		background: color-mix(in srgb, var(--ev) 30%, var(--surface));
	}
	.chip-ev .t {
		display: block;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.more {
		color: var(--text-muted);
		padding-inline-start: 4px;
	}
	@media (max-width: 600px) {
		.cell {
			min-height: 64px;
			padding: 2px;
		}
		.cell-head {
			flex-direction: column;
			align-items: flex-start;
		}
		.cell-wx .tiny {
			display: none;
		}
		.cell-events {
			flex-direction: row;
			flex-wrap: wrap;
			gap: 3px;
		}
		.chip-ev {
			width: 8px;
			height: 8px;
			padding: 0;
			border: 0;
			border-radius: 50%;
			background: var(--ev);
		}
		.chip-ev .t {
			display: none;
		}
		.more {
			font-size: 0.6rem;
		}
	}
	.foot {
		display: flex;
		flex-wrap: wrap;
		gap: var(--s-3);
		justify-content: space-between;
		margin: 0;
	}
	.foot span,
	.foot a {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: inherit;
	}
	.detail p {
		margin: 0;
	}
	.d-row {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.d-dot {
		width: 12px;
		height: 12px;
		border-radius: 50%;
		background: var(--ev);
		margin: 0 2px;
	}
	.notes {
		white-space: pre-wrap;
		padding: 10px 12px;
		border-radius: var(--r-sm);
		background: var(--surface-2);
	}
</style>
