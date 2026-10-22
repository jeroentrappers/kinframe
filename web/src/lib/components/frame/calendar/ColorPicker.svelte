<script lang="ts">
	import { COLORS, colorVars, type CalendarColor } from '$lib/calendar';
	import { t } from '$lib/i18n/index.svelte';

	let { value = $bindable(), name = 'color', label }: { value: CalendarColor; name?: string; label: string } = $props();
</script>

<fieldset class="colors">
	<legend class="label">{label}</legend>
	<div class="swatches">
		{#each COLORS as c (c)}
			<label class="swatch cal-color" style={colorVars(c)} title={c}>
				<input type="radio" {name} value={c} bind:group={value} aria-label={t('cal.colorName', { name: c })} />
				<span aria-hidden="true"></span>
			</label>
		{/each}
	</div>
</fieldset>

<style>
	.colors {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.swatches {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(36px, 1fr));
		gap: 6px;
		max-width: 300px;
	}
	.swatch {
		position: relative;
		display: grid;
		place-items: center;
		width: 36px;
		height: 36px;
		cursor: pointer;
	}
	.swatch input {
		position: absolute;
		inset: 0;
		opacity: 0;
		margin: 0;
		cursor: pointer;
	}
	.swatch span {
		width: 26px;
		height: 26px;
		border-radius: 50%;
		background: var(--ev);
		box-shadow: inset 0 0 0 1px rgb(0 0 0 / 0.08);
		transition: transform var(--dur) var(--ease);
	}
	.swatch input:checked + span {
		transform: scale(1.12);
		box-shadow:
			0 0 0 2px var(--surface),
			0 0 0 4px var(--ev);
	}
	.swatch input:focus-visible + span {
		outline: 2px solid var(--focus);
		outline-offset: 4px;
	}
</style>
