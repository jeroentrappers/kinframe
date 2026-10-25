<script lang="ts">
	// Caption, clock, date and weather over a TV stage, in the TV page's design.
	import { onMount, type Snippet } from 'svelte';
	import { i18n } from '$lib/i18n/index.svelte';
	import Icon, { type IconName } from '$components/Icon.svelte';

	let {
		caption = null,
		byline = '',
		clock = true,
		date = false,
		clock24h = true,
		timeZone,
		weather = null,
		label = '',
		dim = false,
		children
	}: {
		caption?: string | null;
		byline?: string;
		clock?: boolean;
		date?: boolean;
		clock24h?: boolean;
		timeZone?: string;
		weather?: { tempC: number; code: number; isDay: boolean; unit: 'c' | 'f' } | null;
		/** Top-left: the frame name, or "Mirroring …". */
		label?: string;
		dim?: boolean;
		children?: Snippet;
	} = $props();

	let now = $state(new Date());
	onMount(() => {
		const t = setInterval(() => (now = new Date()), 15_000);
		return () => clearInterval(t);
	});

	function fmt(o: Intl.DateTimeFormatOptions): string {
		try {
			return new Intl.DateTimeFormat(i18n.tag, { ...o, timeZone }).format(now);
		} catch {
			return new Intl.DateTimeFormat(i18n.tag, o).format(now);
		}
	}
	const time = $derived(fmt({ hour: '2-digit', minute: '2-digit', hourCycle: clock24h ? 'h23' : 'h12' }));
	const day = $derived(fmt({ weekday: 'long', day: 'numeric', month: 'long' }));
	const temp = $derived(weather ? Math.round(weather.unit === 'f' ? (weather.tempC * 9) / 5 + 32 : weather.tempC) : null);

	/** WMO weather code → one of our icons (clear, cloudy, rain…). */
	function wxIcon(code: number, isDay: boolean): IconName {
		if (code <= 1) return isDay ? 'sun' : 'moon';
		return 'cloud';
	}
</script>

<div class="overlay" class:dim>
	<div class="top">
		<span class="label">{label}</span>
		{#if clock || date || temp !== null}
			<span class="when">
				{#if clock}<span class="clock num">{time}</span>{/if}
				{#if date}<span class="date">{day}</span>{/if}
				{#if weather && temp !== null}<span class="wx num"><Icon name={wxIcon(weather.code, weather.isDay)} size={20} />{temp}°</span>{/if}
			</span>
		{/if}
	</div>
	<div class="bottom">
		{#if caption || byline}
			<div class="caption">
				{#if caption}<p class="cap">{caption}</p>{/if}
				{#if byline}<p class="by">{byline}</p>{/if}
			</div>
		{/if}
		{@render children?.()}
	</div>
</div>

<style>
	.overlay {
		position: absolute;
		inset: 0;
		z-index: 2;
		display: flex;
		flex-direction: column;
		justify-content: space-between;
		padding: clamp(16px, 3vw, 40px) clamp(20px, 4vw, 56px);
		pointer-events: none;
		color: #f6f1ea;
		transition: opacity 600ms ease;
	}
	.top {
		display: flex;
		justify-content: space-between;
		align-items: flex-start;
		gap: 16px;
		text-shadow: 0 1px 8px rgb(0 0 0 / 0.6);
		font-weight: 600;
		font-size: clamp(0.9rem, 1.4vw, 1.2rem);
	}
	.label {
		opacity: 0.85;
	}
	.dim .label {
		opacity: 0.55;
	}
	.when {
		display: flex;
		flex-direction: column;
		align-items: flex-end;
		gap: 2px;
	}
	.clock {
		font-size: clamp(1.4rem, 3vw, 2.6rem);
		line-height: 1.1;
	}
	.date {
		opacity: 0.9;
	}
	.wx {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.bottom {
		display: flex;
		align-items: flex-end;
		justify-content: space-between;
		gap: 16px;
	}
	.caption {
		max-width: min(70ch, 70%);
		padding: 14px 18px;
		border-radius: 16px;
		background: linear-gradient(to top, rgb(0 0 0 / 0.55), rgb(0 0 0 / 0.25));
		backdrop-filter: blur(6px);
	}
	.caption p {
		margin: 0;
	}
	.cap {
		font-family: var(--font-display, Fraunces, serif);
		font-size: clamp(1.2rem, 2.4vw, 2rem);
		line-height: 1.25;
	}
	.by {
		opacity: 0.8;
		font-size: clamp(0.85rem, 1.3vw, 1.1rem);
		margin-top: 4px !important;
	}
	@media (max-width: 640px) {
		.caption {
			max-width: 100%;
		}
	}
</style>
