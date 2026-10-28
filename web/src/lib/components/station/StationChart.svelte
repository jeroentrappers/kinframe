<script lang="ts" module>
	import type { Values } from '$lib/station/station';

	export type ChartSeries = {
		label: string;
		values: Values;
		/** CSS colour (a custom property): the series' identity, never its text. */
		color: string;
		kind: 'line' | 'area' | 'bars' | 'band';
		/** For `band`: the lower edge (the band is drawn from it up to `values`). */
		base?: Values;
		/** Leave the series out of the readout (a band's lower edge is already a line of its own). */
		quiet?: boolean;
		/** For `bars`: each bar covers this many steps from where its value is (hourly bars: 12 five-minute steps). */
		span?: number;
	};
</script>

<script lang="ts">
	import { domain, niceTicks, timeTicks, compass } from '$lib/station/station';

	let {
		title,
		unit,
		series,
		start,
		stepS,
		from,
		to,
		timeZone,
		tag,
		format,
		tick,
		zero = false,
		minSpan = 0,
		height = 168,
		arrows,
		directions,
		empty,
		hover = $bindable<number | null>(null)
	}: {
		title: string;
		/** Shown once beside the title; the axis carries bare numbers. */
		unit: string;
		series: ChartSeries[];
		start: number;
		stepS: number;
		from: number;
		to: number;
		timeZone: string;
		tag: string;
		format: (v: number) => string;
		tick: (v: number) => string;
		zero?: boolean;
		minSpan?: number;
		height?: number;
		/** Wind directions per step: small arrows along the bottom, pointing where the wind blows to. */
		arrows?: Values;
		directions?: string;
		empty: string;
		hover?: number | null;
	} = $props();

	let outer = $state(640);
	const gutter = 38;
	const width = $derived(Math.max(0, outer - gutter));
	const padTop = 8;
	const padBottom = 22;
	const arrowRow = $derived(arrows ? 18 : 0);
	const plotH = $derived(height - padTop - padBottom - arrowRow);
	const n = $derived(to - from);

	const visible = $derived(series.map((s) => ({ ...s, v: s.values.slice(from, to), b: s.base?.slice(from, to) })));
	const dom = $derived(domain(visible.flatMap((s) => (s.b ? [s.v, s.b] : [s.v])), { zero, minSpan }));
	const ticks = $derived(dom ? niceTicks(dom[0], dom[1], height > 150 ? 4 : 3) : []);
	const yLo = $derived(ticks[0] ?? 0);
	const yHi = $derived(ticks[ticks.length - 1] ?? 1);

	const x = (i: number) => (n <= 1 ? 0 : (i / (n - 1)) * width);
	const y = (v: number) => padTop + plotH - ((v - yLo) / (yHi - yLo || 1)) * plotH;

	/** Path through the values, broken where a step had no reading. */
	function line(v: Values): string {
		let d = '';
		let pen = false;
		v.forEach((val, i) => {
			if (val == null) {
				pen = false;
				return;
			}
			d += `${pen ? 'L' : 'M'}${x(i).toFixed(1)},${y(val).toFixed(1)}`;
			pen = true;
		});
		return d;
	}

	/** Closed shapes between `upper` and `lower` (or the axis floor), one per unbroken run. */
	function fill(upper: Values, lower?: Values): string {
		let d = '';
		let run: number[] = [];
		const flush = () => {
			if (run.length > 1) {
				const top = run.map((i) => `${x(i).toFixed(1)},${y(upper[i] as number).toFixed(1)}`);
				const bottom = run
					.slice()
					.reverse()
					.map((i) => `${x(i).toFixed(1)},${y(lower ? (lower[i] as number) : yLo).toFixed(1)}`);
				d += `M${top.join('L')}L${bottom.join('L')}Z`;
			}
			run = [];
		};
		upper.forEach((u, i) => {
			if (u == null || (lower && lower[i] == null)) flush();
			else run.push(i);
		});
		flush();
		return d;
	}

	const stepW = $derived(width / Math.max(n - 1, 1));
	/** Bars cover their span with a 2px gap between neighbours. */
	const barW = (span = 1) => Math.max(1, stepW * span - 2);

	/** The value a series shows at step i: a spanning bar's value covers the steps after it. */
	function valueAt(s: { v: Values; span?: number }, i: number): number | null {
		if (!s.span) return s.v[i] ?? null;
		for (let k = i; k >= 0 && i - k < s.span; k--) if (s.v[k] != null) return s.v[k] ?? null;
		return null;
	}

	/** Bands and areas under the lines. */
	const drawOrder = $derived([...visible].sort((a, b) => Number(a.kind === 'line') - Number(b.kind === 'line')));

	const timeAxis = $derived(timeTicks(start, stepS, from, to, timeZone, n > 400 ? 6 : 3));
	const hourFmt = $derived(new Intl.DateTimeFormat(tag, { timeZone, hour: '2-digit', minute: '2-digit' }));
	const dayFmt = $derived(new Intl.DateTimeFormat(tag, { timeZone, weekday: 'short' }));
	const readFmt = $derived(new Intl.DateTimeFormat(tag, { timeZone, weekday: 'short', hour: '2-digit', minute: '2-digit' }));

	const at = (i: number) => start + (from + i) * stepS * 1000;

	/** The step the readout shows: the hovered one, or the latest with any value. */
	const shown = $derived.by(() => {
		if (hover != null && hover >= from && hover < to) return hover - from;
		for (let i = n - 1; i >= 0; i--) if (visible.some((s) => valueAt(s, i) != null)) return i;
		return null;
	});

	const arrowStep = $derived(Math.max(1, Math.round(n / Math.max(8, width / 34))));

	function onMove(e: PointerEvent) {
		const r = (e.currentTarget as SVGElement).getBoundingClientRect();
		const i = Math.round(((e.clientX - r.left - gutter) / Math.max(1, r.width - gutter)) * (n - 1));
		hover = from + Math.max(0, Math.min(n - 1, i));
	}
</script>

<figure class="chart">
	<figcaption>
		<span class="title">{title} <span class="unit">{unit}</span></span>
		<span class="readout" aria-live="off">
			{#if shown != null}
				<span class="when">{hover != null ? readFmt.format(at(shown)) : ''}</span>
				{#each visible.filter((s) => !s.quiet) as s (s.label)}
					<span class="item">
						{#if visible.filter((v) => !v.quiet).length > 1}<i class="swatch" style:background={s.color}></i>{s.label}{/if}
						<b>{valueAt(s, shown) != null ? format(valueAt(s, shown) as number) : '–'}</b>
					</span>
				{/each}
			{/if}
		</span>
	</figcaption>
	<div class="plot" bind:clientWidth={outer}>
		{#if !dom}
			<p class="empty" style:height="{height}px">{empty}</p>
		{:else}
			<!-- svelte-ignore a11y_no_static_element_interactions -->
			<svg width={outer} {height} viewBox="0 0 {outer} {height}" role="img" aria-label={title} onpointermove={onMove} onpointerdown={onMove} onpointerleave={() => (hover = null)}>
				{#each ticks as t (t)}
					<text class="ylab" x={gutter - 8} y={y(t) + 4} text-anchor="end">{tick(t)}</text>
				{/each}
				<g transform="translate({gutter},0)">
				{#each ticks as t (t)}
					<line class="grid" x1="0" x2={width} y1={y(t)} y2={y(t)} />
				{/each}
				{#each timeAxis as t (t.i)}
					<line class={t.midnight ? 'day' : 'tick'} x1={x(t.i - from)} x2={x(t.i - from)} y1={padTop} y2={padTop + plotH} />
					<text class="xlab" class:strong={t.midnight} x={x(t.i - from)} y={height - 6} text-anchor="middle">
						{t.midnight ? dayFmt.format(at(t.i - from)) : hourFmt.format(at(t.i - from))}
					</text>
				{/each}
				{#each drawOrder as s (s.label)}
					{#if s.kind === 'bars'}
						{#each s.v as v, i (i)}
							{#if v != null && v > 0}
								<rect class="bar" x={x(i) + 1} y={y(v)} width={barW(s.span)} height={Math.max(1, y(yLo) - y(v))} rx="2" style:fill={s.color} />
							{/if}
						{/each}
					{:else if s.kind === 'band'}
						<path d={fill(s.v, s.b)} style:fill={s.color} class="band" />
					{:else}
						{#if s.kind === 'area'}<path d={fill(s.v)} style:fill={s.color} class="area" />{/if}
						<path d={line(s.v)} style:stroke={s.color} class="line" />
					{/if}
				{/each}
				{#if arrows && directions}
					{#each arrows.slice(from, to) as d, i (i)}
						{#if d != null && i % arrowStep === Math.floor(arrowStep / 2)}
							<g transform="translate({x(i)},{padTop + plotH + arrowRow / 2 + 2}) rotate({d + 180})">
								<title>{compass(d, directions)}</title>
								<path class="arrow" d="M0,-6 L4,4 L0,2 L-4,4 Z" />
							</g>
						{/if}
					{/each}
				{/if}
				{#if shown != null && hover != null}
					<line class="cross" x1={x(shown)} x2={x(shown)} y1={padTop} y2={padTop + plotH} />
					{#each visible as s (s.label)}
						{#if s.kind !== 'bars' && s.kind !== 'band' && s.v[shown] != null}
							<circle class="dot" cx={x(shown)} cy={y(s.v[shown] as number)} r="4" style:fill={s.color} />
						{/if}
					{/each}
				{/if}
				</g>
			</svg>
		{/if}
	</div>
</figure>

<style>
	.chart {
		margin: 0;
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: var(--r-lg);
		padding: var(--s-4) var(--s-4) var(--s-2);
		box-shadow: var(--shadow-sm);
		min-width: 0;
	}
	figcaption {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		justify-content: space-between;
		gap: var(--s-1) var(--s-3);
		margin-bottom: var(--s-2);
	}
	.title {
		font-family: var(--font-display);
		font-size: 1.05rem;
		font-weight: 560;
	}
	.title .unit {
		font-family: var(--font-body);
		font-size: 0.8rem;
		font-weight: 500;
		color: var(--text-faint);
		margin-left: 4px;
	}
	.readout {
		display: flex;
		flex-wrap: wrap;
		gap: var(--s-1) var(--s-3);
		font-size: 0.85rem;
		color: var(--text-muted);
		font-variant-numeric: tabular-nums;
		min-height: 1.3em;
	}
	.readout b {
		color: var(--text);
		font-weight: 600;
		margin-left: 4px;
	}
	.swatch {
		display: inline-block;
		width: 10px;
		height: 3px;
		border-radius: 2px;
		margin-right: 5px;
		vertical-align: middle;
	}
	.plot {
		width: 100%;
		touch-action: pan-y;
	}
	svg {
		display: block;
		overflow: visible;
		cursor: crosshair;
	}
	.empty {
		display: grid;
		place-items: center;
		margin: 0;
		color: var(--text-faint);
		font-size: 0.9rem;
	}
	.grid {
		stroke: var(--border);
		stroke-width: 1;
	}
	.tick {
		stroke: var(--border);
		stroke-dasharray: 2 4;
	}
	.day {
		stroke: var(--border-strong);
	}
	.ylab,
	.xlab {
		fill: var(--text-faint);
		font-size: 11px;
		font-variant-numeric: tabular-nums;
	}
	.xlab.strong {
		fill: var(--text-muted);
		font-weight: 600;
	}
	.line {
		fill: none;
		stroke-width: 2;
		stroke-linejoin: round;
		stroke-linecap: round;
	}
	.area {
		opacity: 0.16;
	}
	.band {
		opacity: 0.24;
	}
	.arrow {
		fill: var(--text-faint);
	}
	.cross {
		stroke: var(--text-faint);
		stroke-width: 1;
	}
	.dot {
		stroke: var(--surface);
		stroke-width: 2;
	}
</style>
