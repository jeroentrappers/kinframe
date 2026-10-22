<script lang="ts">
	import { weatherKind } from '$lib/calendar';
	import { t, type MessageKey } from '$lib/i18n/index.svelte';

	let { code, size = 22, isDay = true }: { code: number; size?: number; isDay?: boolean } = $props();
	const kind = $derived(weatherKind(code));
	const label = $derived(t(`cal.wx.${kind}` as MessageKey));
	const sun = $derived(kind === 'clear' || kind === 'partly');
</script>

<svg class="wx" width={size} height={size} viewBox="0 0 24 24" role="img" aria-label={label}>
	<title>{label}</title>
	{#if sun}
		{#if isDay}
			<g class="sun" transform={kind === 'partly' ? 'translate(-3 -3) scale(.85)' : ''}>
				<circle cx="12" cy="12" r="4.3" />
				<path d="M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.5 1.5M17.2 17.2l1.5 1.5M5.3 18.7l1.5-1.5M17.2 6.8l1.5-1.5" />
			</g>
		{:else}
			<path class="moon" transform={kind === 'partly' ? 'translate(-3 -3) scale(.85)' : ''} d="M19 14.5A7.5 7.5 0 1 1 9.5 5a6 6 0 0 0 9.5 9.5z" />
		{/if}
	{/if}
	{#if kind !== 'clear'}
		<path
			class="cloud"
			class:small={kind === 'partly'}
			d={kind === 'partly' ? 'M17 20a3.5 3.5 0 0 0 .3-7 5 5 0 0 0-9.6 1.6A2.8 2.8 0 0 0 8 20z' : 'M17.5 16a4 4 0 0 0 .3-8A5.8 5.8 0 0 0 6.6 9.9 3.2 3.2 0 0 0 7 16z'}
		/>
	{/if}
	{#if kind === 'rain' || kind === 'showers' || kind === 'drizzle'}
		<path class="rain" d={kind === 'drizzle' ? 'M9 19v1.5M13 19v1.5M17 19v1.5' : 'M8.5 18.5 7.5 21.5M12.5 18.5l-1 3M16.5 18.5l-1 3'} />
	{:else if kind === 'snow'}
		<g class="snow"><circle cx="8.5" cy="19.5" r="1" /><circle cx="12.5" cy="21" r="1" /><circle cx="16.5" cy="19.5" r="1" /></g>
	{:else if kind === 'storm'}
		<path class="bolt" d="M12.5 16.5 10 20.5h3l-1.5 3" />
	{:else if kind === 'fog'}
		<path class="fog" d="M5 19h14M7 22h10" />
	{/if}
</svg>

<style>
	.wx {
		flex: none;
		overflow: visible;
	}
	.sun circle {
		fill: var(--sun);
	}
	.sun path {
		stroke: var(--sun);
		stroke-width: 1.8;
		stroke-linecap: round;
		fill: none;
	}
	.moon {
		fill: #c9b98f;
	}
	.cloud {
		fill: color-mix(in srgb, var(--text-faint) 30%, var(--surface));
		stroke: color-mix(in srgb, var(--text-faint) 80%, transparent);
		stroke-width: 1.3;
		stroke-linejoin: round;
	}
	.rain,
	.fog {
		stroke: #4f9fdf;
		stroke-width: 1.8;
		stroke-linecap: round;
		fill: none;
	}
	.fog {
		stroke: var(--text-faint);
	}
	.snow circle {
		fill: #87bfeb;
	}
	.bolt {
		stroke: var(--sun);
		stroke-width: 1.8;
		stroke-linecap: round;
		stroke-linejoin: round;
		fill: none;
	}
</style>
