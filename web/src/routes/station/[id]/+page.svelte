<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import Logo from '$components/Logo.svelte';
	import StationChart, { type ChartSeries } from '$components/station/StationChart.svelte';
	import { i18n } from '$lib/i18n/index.svelte';
	import { fill, stationStrings } from '$lib/station/strings';
	import {
		WIND_UNITS,
		beaufort,
		compass,
		hourlyRain,
		pressureTrend,
		uvLevel,
		windUnitLabel,
		windValue,
		type StationPage,
		type Values,
		type WindUnit
	} from '$lib/station/station';

	// A personal weather station's public page (docs/plugins.md §9.4): only for stations whose owner turned it on.
	// Refreshes every minute while visible; the server keeps 48 hours in 5-minute steps.

	const REFRESH_MS = 60_000;
	const PREF = 'kinframe.station.prefs';

	const id = $derived((page.params.id ?? '').trim());
	const s = $derived(stationStrings(i18n.locale));
	const tag = $derived(i18n.tag);

	let data = $state<StationPage | null>(null);
	let status = $state<'loading' | 'ok' | 'missing' | 'error'>('loading');
	let now = $state(Date.now());
	let hours = $state<24 | 48>(24);
	let unit = $state<WindUnit>('kmh');
	let hover = $state<number | null>(null);

	function loadPrefs() {
		try {
			const p = JSON.parse(localStorage.getItem(PREF) ?? '{}');
			if (p.hours === 24 || p.hours === 48) hours = p.hours;
			if (WIND_UNITS.includes(p.unit)) unit = p.unit;
		} catch {
			/* private mode: defaults */
		}
	}
	function savePrefs() {
		try {
			localStorage.setItem(PREF, JSON.stringify({ hours, unit }));
		} catch {
			/* ignore */
		}
	}

	async function load() {
		try {
			const res = await fetch(`/v1/stations/${encodeURIComponent(id)}/public`, { headers: { Accept: 'application/json' } });
			if (res.status === 404) {
				status = 'missing';
				data = null;
				return;
			}
			if (!res.ok) throw new Error(String(res.status));
			data = (await res.json()) as StationPage;
			status = 'ok';
		} catch {
			if (!data) status = 'error'; // keep showing the last data on a passing failure
		}
	}

	onMount(() => {
		loadPrefs();
		void load();
		let timer = setInterval(tick, REFRESH_MS);
		const clock = setInterval(() => (now = Date.now()), 15_000);
		function tick() {
			if (document.visibilityState === 'visible') void load();
		}
		const onVis = () => {
			if (document.visibilityState === 'visible') {
				void load();
				clearInterval(timer);
				timer = setInterval(tick, REFRESH_MS);
			}
		};
		document.addEventListener('visibilitychange', onVis);
		return () => {
			clearInterval(timer);
			clearInterval(clock);
			document.removeEventListener('visibilitychange', onVis);
		};
	});

	// ---------------------------------------------------------------- formatting

	const num = (v: number, d = 1) => new Intl.NumberFormat(tag, { minimumFractionDigits: d, maximumFractionDigits: d }).format(v);
	const temp = (v: number, d = 1) => `${num(v, d)}°`;
	const windText = (ms: number) => (unit === 'bft' ? `${beaufort(ms)} ${s.beaufort}` : `${num(windValue(ms, unit), unit === 'kmh' ? 0 : 1)} ${windUnitLabel(unit, s.beaufort)}`);
	const timeOf = (iso: string | null | undefined) =>
		iso && data ? new Intl.DateTimeFormat(tag, { timeZone: data.timezone, hour: '2-digit', minute: '2-digit' }).format(new Date(iso)) : '';

	function ago(ms: number): string {
		const sec = Math.max(0, Math.round((now - ms) / 1000));
		if (sec < 45) return s.justNow;
		const rtf = new Intl.RelativeTimeFormat(tag, { numeric: 'auto' });
		if (sec < 3600) return rtf.format(-Math.round(sec / 60), 'minute');
		if (sec < 86400) return rtf.format(-Math.round(sec / 3600), 'hour');
		return rtf.format(-Math.round(sec / 86400), 'day');
	}
	function since(ms: number): string {
		const min = Math.max(1, Math.round((now - ms) / 60000));
		const nf = (v: number, u: 'minute' | 'hour') => new Intl.NumberFormat(tag, { style: 'unit', unit: u, unitDisplay: 'long' }).format(v);
		return min < 90 ? nf(min, 'minute') : nf(Math.round(min / 60), 'hour');
	}

	// ---------------------------------------------------------------- derived views

	const r = $derived(data?.reading);
	const receivedMs = $derived(data ? Date.parse(data.received_at) : 0);
	const stale = $derived(data != null && now - receivedMs > 10 * 60_000);
	const title = $derived(data?.name || (data ? fill(s.stationLabel, { id: data.station_id }) : fill(s.stationLabel, { id })));
	const trend = $derived(data ? pressureTrend(data.series) : null);

	const ser = $derived(data?.series);
	const startMs = $derived(ser ? Date.parse(ser.start) : 0);
	const total = $derived(ser?.temp_c.length ?? 0);
	const from = $derived(Math.max(0, total - Math.round((hours * 3600) / (ser?.step_s ?? 300))));

	const conv = (v: Values) => v.map((x) => (x == null ? null : windValue(x, unit)));

	const charts = $derived.by(() => {
		if (!ser) return [];
		const c = (name: string) => `var(--st-${name})`;
		const deg = (v: number) => `${num(v, 1)} °C`;
		type Spec = { key: string; title: string; unit: string; series: ChartSeries[]; format: (v: number) => string; tick: (v: number) => string; zero?: boolean; minSpan?: number; arrows?: Values };
		const whole = (v: number) => num(v, v % 1 ? 1 : 0);
		const windUnit = windUnitLabel(unit, s.beaufort);
		const out: Spec[] = [
			{
				key: 'temp',
				title: s.temperature,
				unit: '°C',
				tick: whole,
				series: [
					{ label: s.temperature, values: ser.temp_c, color: c('temp'), kind: 'line' },
					{ label: s.dewPoint, values: ser.dew_point_c, color: c('dew'), kind: 'line' }
				],
				format: deg,
				minSpan: 4
			},
			{
				key: 'wind',
				title: s.wind,
				unit: windUnit,
				tick: whole,
				series: [
					{ label: s.wind, values: conv(ser.wind_ms), color: c('wind'), kind: 'line' },
					{ label: s.gusts, values: conv(ser.gust_ms), base: conv(ser.wind_ms), color: c('wind'), kind: 'band' }
				],
				format: (v) => `${num(v, unit === 'kmh' ? 0 : 1)} ${windUnit}`,
				zero: true,
				minSpan: unit === 'kmh' ? 10 : unit === 'ms' ? 3 : 2,
				arrows: ser.wind_dir_deg
			},
			{
				key: 'hum',
				title: s.humidity,
				unit: '%',
				tick: whole,
				series: [{ label: s.humidity, values: ser.humidity_pct, color: c('hum'), kind: 'area' }],
				format: (v) => `${num(v, 0)} %`,
				minSpan: 20
			},
			{
				key: 'press',
				title: s.pressure,
				unit: 'hPa',
				tick: whole,
				series: [{ label: s.pressure, values: ser.pressure_hpa, color: c('press'), kind: 'line' }],
				format: (v) => `${num(v, v % 1 ? 1 : 0)} hPa`,
				minSpan: 6
			},
			{
				key: 'rain',
				title: s.rainPerHour,
				unit: 'mm',
				tick: whole,
				series: [{ label: s.rainPerHour, values: hourlyRain(ser.rain_today_mm, startMs, ser.step_s, data?.timezone ?? 'UTC'), color: c('rain'), kind: 'bars', span: Math.round(3600 / ser.step_s) }],
				format: (v) => `${num(v, 1)} mm`,
				zero: true,
				minSpan: 2
			}
		];
		if (ser.solar_wm2.some((v) => v != null))
			out.push({
				key: 'sun',
				title: s.sun,
				unit: 'W/m²',
				tick: whole,
				series: [{ label: s.sun, values: ser.solar_wm2, color: c('sun'), kind: 'area' }],
				format: (v) => `${num(v, 0)} W/m²`,
				zero: true,
				minSpan: 50
			});
		return out;
	});

	/** Hourly rows for the table view (the newest first). */
	const table = $derived.by(() => {
		if (!ser) return [];
		const per = Math.round(3600 / ser.step_s);
		const rows: { at: number; t: number | null; h: number | null; p: number | null; w: number | null; g: number | null; rain: number | null }[] = [];
		for (let i = total - 1; i >= from; i -= per) {
			const v = (a: Values) => a[i] ?? null;
			rows.push({ at: startMs + i * ser.step_s * 1000, t: v(ser.temp_c), h: v(ser.humidity_pct), p: v(ser.pressure_hpa), w: v(ser.wind_ms), g: v(ser.gust_ms), rain: v(ser.rain_today_mm) });
		}
		return rows;
	});
	const rowTime = $derived(data ? new Intl.DateTimeFormat(tag, { timeZone: data.timezone, weekday: 'short', hour: '2-digit', minute: '2-digit' }) : null);

	const uv = $derived(r?.uv_index);
	const dir = $derived(r?.wind_dir_deg);
	const calm = $derived((r?.wind_ms ?? 0) < 0.3);
</script>

<svelte:head>
	<title>{title} · kinframe</title>
	<meta name="robots" content="noindex" />
</svelte:head>

<div class="station">
	<header class="top">
		<a href="/" class="brand" aria-label="kinframe"><Logo size={26} /></a>
	</header>

	{#if status === 'loading'}
		<div class="center" aria-busy="true"><Logo size={40} /></div>
	{:else if status === 'missing'}
		<section class="card card-pad message">
			<h1>{s.notFoundTitle}</h1>
			<p class="muted">{s.notFoundBody}</p>
		</section>
	{:else if status === 'error' || !data || !r}
		<section class="card card-pad message">
			<h1>{s.errorTitle}</h1>
			<button class="btn btn-primary" onclick={() => ((status = 'loading'), load())}>{s.tryAgain}</button>
		</section>
	{:else}
		<section class="hero">
			<div class="heading">
				<h1>{title}</h1>
				<p class="sub">
					{#if data.name}<span>{fill(s.stationLabel, { id: data.station_id })}</span><span aria-hidden="true">·</span>{/if}
					{#if stale}
						<span class="state stale">{fill(s.stale, { ago: since(receivedMs) })}</span>
					{:else}
						<span class="state live"><i aria-hidden="true"></i>{s.live}</span>
						<span>{fill(s.updated, { ago: ago(receivedMs) })}</span>
					{/if}
				</p>
			</div>

			<div class="now">
				<div class="temp">
					{#if r.temp_c != null}
						<span class="big">{temp(r.temp_c)}</span>
						<span class="unit">C</span>
					{:else}
						<span class="big">–</span>
					{/if}
				</div>
				<div class="temp-meta">
					{#if r.feels_like_c != null && r.temp_c != null && Math.abs(r.feels_like_c - r.temp_c) >= 0.5}
						<p>{fill(s.feelsLike, { t: temp(r.feels_like_c) })}</p>
					{/if}
					<dl class="extremes">
						{#if data.today.temp_min_c != null}
							<div>
								<dt>{s.todayLow}</dt>
								<dd><b>{temp(data.today.temp_min_c)}</b> <span>{fill(s.at, { time: timeOf(data.today.temp_min_at) })}</span></dd>
							</div>
						{/if}
						{#if data.today.temp_max_c != null}
							<div>
								<dt>{s.todayHigh}</dt>
								<dd><b>{temp(data.today.temp_max_c)}</b> <span>{fill(s.at, { time: timeOf(data.today.temp_max_at) })}</span></dd>
							</div>
						{/if}
					</dl>
				</div>
			</div>

			<div class="tiles">
				<article class="tile wind">
					<h2>{s.wind}</h2>
					<div class="wind-body">
						<svg class="compass" viewBox="-50 -50 100 100" aria-hidden="true">
							<circle r="44" class="ring" />
							{#each Array(16) as _, k (k)}
								<line class="mark" class:major={k % 4 === 0} x1="0" y1={k % 4 === 0 ? -44 : -44} x2="0" y2={k % 4 === 0 ? -36 : -40} transform="rotate({k * 22.5})" />
							{/each}
							{#each s.directions.split(',').filter((_, k) => k % 4 === 0) as d, k (d)}
								<text class="cardinal" x={Math.sin((k * Math.PI) / 2) * 27} y={-Math.cos((k * Math.PI) / 2) * 27 + 4} text-anchor="middle">{d}</text>
							{/each}
							{#if dir != null && !calm}
								<g transform="rotate({dir + 180})">
									<path class="needle" d="M0,-40 L7,-22 L2,-24 L2,16 L-2,16 L-2,-24 L-7,-22 Z" />
								</g>
							{/if}
						</svg>
						<div>
							<p class="value">{r.wind_ms != null ? windText(r.wind_ms) : '–'}</p>
							<p class="detail">
								{#if calm}{s.calm}{:else if dir != null}{compass(dir, s.directions)}{/if}
								{#if r.gust_ms != null}<br />{s.gusts} {windText(r.gust_ms)}{/if}
							</p>
						</div>
					</div>
					{#if data.today.gust_max_ms != null}
						<p class="foot">{fill(s.gustToday, { v: windText(data.today.gust_max_ms), time: timeOf(data.today.gust_max_at) })}</p>
					{/if}
				</article>

				<article class="tile">
					<h2>{s.humidity}</h2>
					<p class="value">{#if r.humidity_pct != null}{num(r.humidity_pct, 0)}<small>%</small>{:else}–{/if}</p>
					{#if r.humidity_pct != null}
						<div class="meter" aria-hidden="true"><i style:width="{r.humidity_pct}%"></i></div>
					{/if}
					{#if r.dew_point_c != null}<p class="foot">{s.dewPoint} {temp(r.dew_point_c)}</p>{/if}
				</article>

				<article class="tile">
					<h2>{s.pressure}</h2>
					<p class="value">{#if r.pressure_hpa != null}{num(r.pressure_hpa, 1)}<small>hPa</small>{:else}–{/if}</p>
					{#if trend}
						<p class="detail trend {trend.kind}">
							<span aria-hidden="true">{trend.kind === 'rising' ? '↗\uFE0E' : trend.kind === 'falling' ? '↘\uFE0E' : '→\uFE0E'}</span>
							{s[trend.kind]} · {fill(s.in3h, { v: `${trend.change > 0 ? '+' : ''}${num(trend.change, 1)}` })}
						</p>
					{/if}
				</article>

				<article class="tile">
					<h2>{s.rainToday}</h2>
					<p class="value">{#if r.rain_today_mm != null}{num(r.rain_today_mm, 1)}<small>mm</small>{:else}–{/if}</p>
					<p class="detail">
						{#if r.rain_rate_mm_h != null && r.rain_rate_mm_h > 0}{fill(s.rainRate, { v: `${num(r.rain_rate_mm_h, 1)} mm/h` })}{:else}{s.dry}{/if}
					</p>
				</article>

				{#if r.solar_wm2 != null || uv != null}
					<article class="tile">
						<h2>{s.sun}</h2>
						<p class="value">{#if r.solar_wm2 != null}{num(r.solar_wm2, 0)}<small>W/m²</small>{:else}–{/if}</p>
						{#if uv != null}
							<p class="detail"><span class="uv {uvLevel(uv)}">{fill(s.uv, { v: num(uv, 0) })}</span> {s[uvLevel(uv)]}</p>
						{/if}
						{#if data.today.uv_max != null && data.today.uv_max > 0}<p class="foot">{fill(s.uvMaxToday, { v: num(data.today.uv_max, 0) })}</p>{/if}
					</article>
				{/if}
			</div>
		</section>

		<section class="charts" aria-label={s.range}>
			<div class="controls">
				<div class="segmented" role="group" aria-label={s.range}>
					<button aria-pressed={hours === 24} onclick={() => ((hours = 24), savePrefs())}>{s.last24}</button>
					<button aria-pressed={hours === 48} onclick={() => ((hours = 48), savePrefs())}>{s.last48}</button>
				</div>
				<div class="segmented" role="group" aria-label={s.units}>
					{#each WIND_UNITS as u (u)}
						<button aria-pressed={unit === u} onclick={() => ((unit = u), savePrefs())}>{windUnitLabel(u, s.beaufort)}</button>
					{/each}
				</div>
			</div>
			<div class="grid">
				{#each charts as ch (ch.key)}
					<StationChart
						title={ch.title}
						series={ch.series}
						start={startMs}
						stepS={ser?.step_s ?? 300}
						{from}
						to={total}
						timeZone={data.timezone}
						{tag}
						format={ch.format}
						tick={ch.tick}
						unit={ch.unit}
						zero={ch.zero}
						minSpan={ch.minSpan}
						arrows={ch.arrows}
						directions={s.directions}
						empty={s.noData}
						bind:hover
					/>
				{/each}
			</div>

			<details class="table">
				<summary>{s.tableShow}</summary>
				<div class="scroll">
					<table>
						<thead>
							<tr><th>{s.tableTime}</th><th>{s.temperature}</th><th>{s.humidity}</th><th>{s.pressure}</th><th>{s.wind}</th><th>{s.gusts}</th><th>{s.rainToday}</th></tr>
						</thead>
						<tbody>
							{#each table as row (row.at)}
								<tr>
									<td>{rowTime?.format(row.at)}</td>
									<td>{row.t != null ? `${num(row.t, 1)} °C` : '–'}</td>
									<td>{row.h != null ? `${num(row.h, 0)} %` : '–'}</td>
									<td>{row.p != null ? `${num(row.p, 1)} hPa` : '–'}</td>
									<td>{row.w != null ? windText(row.w) : '–'}</td>
									<td>{row.g != null ? windText(row.g) : '–'}</td>
									<td>{row.rain != null ? `${num(row.rain, 1)} mm` : '–'}</td>
								</tr>
							{/each}
						</tbody>
					</table>
				</div>
			</details>
		</section>

		<footer class="foot-note">
			{#each fill(s.footer, { kinframe: '\u0000' }).split('\u0000') as part, k (k)}{#if k > 0}<a href="https://github.com/jeroentrappers/kinframe">kinframe</a>{/if}{part}{/each}
		</footer>
	{/if}
</div>

<style>
	/* Chart hues: the reference palette's validated slots, stepped per theme (orange/blue are adjacent in it). */
	.station {
		--st-temp: #eb6834;
		--st-dew: #2a78d6;
		--st-wind: #2a78d6;
		--st-hum: #1baf7a;
		--st-press: #6250d6;
		--st-rain: #2a78d6;
		--st-sun: #eda100;
		max-width: 1120px;
		margin: 0 auto;
		padding: var(--s-4) var(--s-4) var(--s-7);
	}
	@media (prefers-color-scheme: dark) {
		.station {
			--st-temp: #d95926;
			--st-dew: #3987e5;
			--st-wind: #3987e5;
			--st-hum: #199e70;
			--st-press: #9085e9;
			--st-rain: #3987e5;
			--st-sun: #c98500;
		}
	}
	.top {
		display: flex;
		align-items: center;
		height: 44px;
		margin-bottom: var(--s-4);
	}
	.brand {
		display: inline-flex;
		color: inherit;
	}
	.center {
		min-height: 50dvh;
		display: grid;
		place-items: center;
	}
	.message {
		max-width: 560px;
		margin: var(--s-7) auto;
		text-align: center;
	}
	.message h1 {
		font-family: var(--font-display);
		font-size: 1.5rem;
		margin: 0 0 var(--s-3);
	}

	/* ------------------------------------------------ hero */
	.hero {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: var(--s-5);
		margin-bottom: var(--s-6);
	}
	.heading h1 {
		font-family: var(--font-display);
		font-size: clamp(1.7rem, 4vw, 2.4rem);
		font-weight: 600;
		letter-spacing: -0.01em;
		margin: 0;
	}
	.sub {
		display: flex;
		flex-wrap: wrap;
		gap: 4px 8px;
		margin: var(--s-1) 0 0;
		color: var(--text-muted);
		font-size: 0.92rem;
	}
	.state {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-weight: 600;
	}
	.state.live {
		color: var(--accent-text);
	}
	.state.live i {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: currentColor;
		box-shadow: 0 0 0 0 currentColor;
		animation: pulse 2.4s var(--ease) infinite;
	}
	.state.stale {
		color: var(--warn);
	}
	@keyframes pulse {
		0% {
			box-shadow: 0 0 0 0 color-mix(in srgb, currentColor 45%, transparent);
		}
		70%,
		100% {
			box-shadow: 0 0 0 8px transparent;
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.state.live i {
			animation: none;
		}
	}

	.now {
		display: flex;
		flex-wrap: wrap;
		align-items: flex-end;
		gap: var(--s-3) var(--s-6);
	}
	.temp {
		display: flex;
		align-items: flex-start;
		line-height: 0.9;
	}
	.big {
		font-family: var(--font-display);
		font-size: clamp(4.5rem, 14vw, 7.5rem);
		font-weight: 500;
		letter-spacing: -0.04em;
		font-variant-numeric: tabular-nums;
	}
	.unit {
		font-family: var(--font-display);
		font-size: clamp(1.6rem, 4vw, 2.4rem);
		color: var(--text-muted);
		margin-top: 0.4em;
	}
	.temp-meta p {
		margin: 0 0 var(--s-2);
		color: var(--text-muted);
		font-size: 1.05rem;
	}
	.extremes {
		display: flex;
		gap: var(--s-5);
		margin: 0;
	}
	.extremes dt {
		font-size: 0.78rem;
		text-transform: uppercase;
		letter-spacing: 0.06em;
		color: var(--text-faint);
		font-weight: 600;
	}
	.extremes dd {
		margin: 2px 0 0;
		color: var(--text-muted);
		font-size: 0.9rem;
	}
	.extremes dd b {
		color: var(--text);
		font-size: 1.35rem;
		font-weight: 600;
		font-variant-numeric: tabular-nums;
	}

	.tiles {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(136px, 1fr));
		gap: var(--s-3);
	}
	.tile {
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: var(--r-lg);
		padding: var(--s-4);
		box-shadow: var(--shadow-sm);
		min-width: 0;
	}
	.tile.wind {
		grid-column: span 2;
	}
	/* On a phone the tiles go two by two and the wind, with its compass, takes a row of its own. */
	@media (max-width: 520px) {
		.tile.wind {
			grid-column: 1 / -1;
		}
	}
	.tile h2 {
		margin: 0 0 var(--s-2);
		font-size: 0.78rem;
		text-transform: uppercase;
		letter-spacing: 0.06em;
		color: var(--text-faint);
		font-weight: 600;
	}
	.value {
		margin: 0;
		white-space: nowrap;
		font-size: 1.6rem;
		font-weight: 600;
		font-variant-numeric: tabular-nums;
		letter-spacing: -0.01em;
	}
	.value small {
		font-size: 0.55em;
		font-weight: 500;
		color: var(--text-muted);
		margin-left: 4px;
	}
	.detail {
		margin: var(--s-1) 0 0;
		color: var(--text-muted);
		font-size: 0.92rem;
	}
	.foot {
		margin: var(--s-3) 0 0;
		color: var(--text-faint);
		font-size: 0.82rem;
	}
	.wind-body {
		display: flex;
		align-items: center;
		gap: var(--s-4);
	}
	.compass {
		width: 104px;
		height: 104px;
		flex: none;
	}
	.ring {
		fill: var(--surface-2);
		stroke: var(--border);
	}
	.mark {
		stroke: var(--border-strong);
		stroke-width: 1;
	}
	.mark.major {
		stroke: var(--text-faint);
		stroke-width: 1.5;
	}
	.cardinal {
		fill: var(--text-faint);
		font-size: 10px;
		font-weight: 600;
	}
	.needle {
		fill: var(--st-wind);
		stroke: var(--surface);
		stroke-width: 1;
	}
	.meter {
		height: 6px;
		border-radius: 3px;
		background: var(--surface-3);
		margin-top: var(--s-2);
		overflow: hidden;
	}
	.meter i {
		display: block;
		height: 100%;
		border-radius: 3px;
		background: var(--st-hum);
	}
	.trend.rising,
	.trend.falling {
		color: var(--text);
	}
	.uv {
		display: inline-block;
		padding: 1px 8px;
		border-radius: var(--r-full);
		font-weight: 600;
		font-size: 0.85rem;
		background: var(--surface-3);
		color: var(--text);
	}
	.uv.uvHigh,
	.uv.uvVeryHigh,
	.uv.uvExtreme {
		background: var(--warn-soft);
		color: var(--warn);
	}

	/* ------------------------------------------------ charts */
	.controls {
		display: flex;
		flex-wrap: wrap;
		justify-content: space-between;
		gap: var(--s-2);
		margin-bottom: var(--s-3);
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 460px), 1fr));
		gap: var(--s-3);
	}
	.table {
		margin-top: var(--s-4);
	}
	.table summary {
		cursor: pointer;
		color: var(--text-muted);
		font-weight: 600;
		font-size: 0.92rem;
	}
	.scroll {
		overflow-x: auto;
		margin-top: var(--s-2);
	}
	table {
		border-collapse: collapse;
		width: 100%;
		font-size: 0.88rem;
		font-variant-numeric: tabular-nums;
	}
	th,
	td {
		text-align: right;
		padding: 6px 10px;
		border-bottom: 1px solid var(--border);
		white-space: nowrap;
	}
	th:first-child,
	td:first-child {
		text-align: left;
	}
	th {
		color: var(--text-faint);
		font-weight: 600;
	}
	.foot-note {
		margin-top: var(--s-6);
		color: var(--text-faint);
		font-size: 0.85rem;
		text-align: center;
	}
	.foot-note a {
		color: var(--text-muted);
	}
</style>
