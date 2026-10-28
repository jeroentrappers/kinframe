<script lang="ts">
	import { onMount } from 'svelte';
	import Logo from '$components/Logo.svelte';
	import type { components } from '$lib/api/schema';
	import { i18n } from '$lib/i18n/index.svelte';
	import { fill, stationStrings } from '$lib/station/strings';
	import { WIND_UNITS, beaufort, compass, windUnitLabel, windValue, type WindUnit } from '$lib/station/station';

	// The overview of weather stations with a public page (docs/plugins.md §9.4), each linking to its own page.
	// Refreshes every two minutes while visible.

	type Summary = components['schemas']['PublicStationSummary'];

	const REFRESH_MS = 120_000;
	const s = $derived(stationStrings(i18n.locale));
	const tag = $derived(i18n.tag);

	let stations = $state<Summary[] | null>(null);
	let failed = $state(false);
	let now = $state(Date.now());
	let unit = $state<WindUnit>('kmh');

	async function load() {
		try {
			const res = await fetch('/v1/stations/public', { headers: { Accept: 'application/json' } });
			if (!res.ok) throw new Error(String(res.status));
			stations = ((await res.json()) as { stations: Summary[] }).stations;
			failed = false;
		} catch {
			if (!stations) failed = true;
		}
	}

	onMount(() => {
		try {
			// The wind unit chosen on a station's page.
			const p = JSON.parse(localStorage.getItem('kinframe.station.prefs') ?? '{}');
			if (WIND_UNITS.includes(p.unit)) unit = p.unit;
		} catch {
			/* defaults */
		}
		void load();
		const timer = setInterval(() => document.visibilityState === 'visible' && void load(), REFRESH_MS);
		const clock = setInterval(() => (now = Date.now()), 15_000);
		return () => {
			clearInterval(timer);
			clearInterval(clock);
		};
	});

	const num = (v: number, d = 1) =>
		new Intl.NumberFormat(tag, { minimumFractionDigits: d, maximumFractionDigits: d }).format(v);
	const windText = (ms: number) =>
		unit === 'bft'
			? `${beaufort(ms)} ${s.beaufort}`
			: `${num(windValue(ms, unit), unit === 'kmh' ? 0 : 1)} ${windUnitLabel(unit, s.beaufort)}`;

	function ago(iso: string): string {
		const sec = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
		if (sec < 45) return s.justNow;
		const rtf = new Intl.RelativeTimeFormat(tag, { numeric: 'auto' });
		if (sec < 3600) return rtf.format(-Math.round(sec / 60), 'minute');
		if (sec < 86400) return rtf.format(-Math.round(sec / 3600), 'hour');
		return rtf.format(-Math.round(sec / 86400), 'day');
	}
	const stale = (iso: string) => now - Date.parse(iso) > 10 * 60_000;

	/** The 24 hourly temperatures as a small line (gaps where an hour had nothing). */
	function spark(values: (number | null)[], w = 120, h = 36): { d: string; lo: number; hi: number } | null {
		const v = values.filter((x): x is number => x != null);
		if (v.length < 2) return null;
		const lo = Math.min(...v);
		const hi = Math.max(...v);
		const span = Math.max(hi - lo, 2);
		const mid = (hi + lo) / 2;
		const y = (t: number) => h - 3 - ((t - (mid - span / 2)) / span) * (h - 6);
		let d = '';
		let pen = false;
		values.forEach((t, i) => {
			if (t == null) return void (pen = false);
			d += `${pen ? 'L' : 'M'}${((i / (values.length - 1)) * w).toFixed(1)},${y(t).toFixed(1)}`;
			pen = true;
		});
		return { d, lo, hi };
	}
</script>

<svelte:head>
	<title>{s.overviewTitle} · kinframe</title>
	<meta name="robots" content="noindex" />
</svelte:head>

<div class="stations">
	<header class="top">
		<a href="/" class="brand" aria-label="kinframe"><Logo size={26} /></a>
	</header>

	<h1>{s.overviewTitle}</h1>
	<p class="intro">{s.overviewIntro}</p>

	{#if stations === null && !failed}
		<div class="center" aria-busy="true"><Logo size={40} /></div>
	{:else if failed}
		<section class="card card-pad message">
			<h2>{s.errorTitle}</h2>
			<button class="btn btn-primary" onclick={() => ((failed = false), load())}>{s.tryAgain}</button>
		</section>
	{:else if stations && stations.length === 0}
		<section class="card card-pad message"><p class="muted">{s.overviewEmpty}</p></section>
	{:else if stations}
		<ul class="grid">
			{#each stations as st (st.station_id)}
				{@const r = st.reading}
				{@const line = spark(st.temp_24h)}
				<li>
					<a class="station-card" class:stale={stale(st.received_at)} href="/station/{encodeURIComponent(st.station_id)}">
						<div class="head">
							<div class="names">
								<h2>{st.name || st.station_id}</h2>
								{#if st.name}<span class="id">{st.station_id}</span>{/if}
							</div>
							<span class="temp">{r.temp_c != null ? `${num(r.temp_c)}°` : '–'}</span>
						</div>
						{#if line}
							<svg class="spark" viewBox="0 0 120 36" preserveAspectRatio="none" role="img" aria-label="{s.last24h}: {num(line.lo)}° – {num(line.hi)}°">
								<path d={line.d} />
							</svg>
							<p class="range">{s.last24h} · {num(line.lo)}° – {num(line.hi)}°</p>
						{/if}
						<dl class="facts">
							{#if r.wind_ms != null}
								<div>
									<dt>{s.wind}</dt>
									<dd>{windText(r.wind_ms)}{#if r.wind_dir_deg != null && r.wind_ms >= 0.3}&nbsp;{compass(r.wind_dir_deg, s.directions)}{/if}</dd>
								</div>
							{/if}
							{#if r.humidity_pct != null}
								<div><dt>{s.humidity}</dt><dd>{num(r.humidity_pct, 0)} %</dd></div>
							{/if}
							{#if r.rain_today_mm != null}
								<div><dt>{s.rainToday}</dt><dd>{num(r.rain_today_mm, 1)} mm</dd></div>
							{/if}
						</dl>
						<p class="when">
							{#if stale(st.received_at)}{fill(s.updated, { ago: ago(st.received_at) })}{:else}<i aria-hidden="true"></i>{s.live} · {ago(st.received_at)}{/if}
						</p>
					</a>
				</li>
			{/each}
		</ul>
	{/if}
</div>

<style>
	.stations {
		--st-temp: #eb6834;
		max-width: 1120px;
		margin: 0 auto;
		padding: var(--s-4) var(--s-4) var(--s-7);
	}
	@media (prefers-color-scheme: dark) {
		.stations {
			--st-temp: #d95926;
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
	h1 {
		font-family: var(--font-display);
		font-size: clamp(1.7rem, 4vw, 2.4rem);
		font-weight: 600;
		margin: 0;
	}
	.intro {
		color: var(--text-muted);
		margin: var(--s-1) 0 var(--s-5);
		max-width: 60ch;
	}
	.center {
		min-height: 40dvh;
		display: grid;
		place-items: center;
	}
	.message {
		max-width: 560px;
		text-align: center;
	}
	.message h2 {
		font-family: var(--font-display);
		margin: 0 0 var(--s-3);
	}
	.grid {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 280px), 1fr));
		gap: var(--s-3);
	}
	.station-card {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		height: 100%;
		padding: var(--s-4);
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: var(--r-lg);
		box-shadow: var(--shadow-sm);
		color: inherit;
		text-decoration: none;
		transition:
			box-shadow var(--dur) var(--ease),
			transform var(--dur) var(--ease);
	}
	.station-card:hover {
		box-shadow: var(--shadow);
		transform: translateY(-1px);
	}
	.station-card:focus-visible {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	.station-card.stale {
		opacity: 0.7;
	}
	.head {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: var(--s-3);
	}
	.names {
		min-width: 0;
	}
	h2 {
		font-family: var(--font-display);
		font-size: 1.25rem;
		font-weight: 600;
		margin: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.id {
		color: var(--text-faint);
		font-size: 0.82rem;
	}
	.temp {
		font-family: var(--font-display);
		font-size: 2.2rem;
		font-weight: 500;
		line-height: 1;
		letter-spacing: -0.02em;
		font-variant-numeric: tabular-nums;
	}
	.spark {
		width: 100%;
		height: 40px;
	}
	.spark path {
		fill: none;
		stroke: var(--st-temp);
		stroke-width: 2;
		stroke-linejoin: round;
		stroke-linecap: round;
		vector-effect: non-scaling-stroke;
	}
	.range {
		margin: -4px 0 0;
		color: var(--text-faint);
		font-size: 0.8rem;
	}
	.facts {
		display: flex;
		flex-wrap: wrap;
		gap: var(--s-1) var(--s-4);
		margin: 0;
	}
	.facts dt {
		font-size: 0.72rem;
		text-transform: uppercase;
		letter-spacing: 0.06em;
		color: var(--text-faint);
		font-weight: 600;
	}
	.facts dd {
		margin: 0;
		font-weight: 600;
		font-variant-numeric: tabular-nums;
	}
	.when {
		margin: auto 0 0;
		padding-top: var(--s-1);
		color: var(--text-muted);
		font-size: 0.85rem;
		display: flex;
		align-items: center;
		gap: 6px;
	}
	.when i {
		width: 7px;
		height: 7px;
		border-radius: 50%;
		background: var(--accent-text);
	}
</style>
