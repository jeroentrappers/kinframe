<script lang="ts">
	import { i18n, LOCALES, t, type Locale } from '$lib/i18n/index.svelte';
	import Icon from './Icon.svelte';

	let { onchange }: { onchange?: (l: Locale) => void } = $props();
</script>

<label class="lang">
	<Icon name="globe" size={18} />
	<span class="visually-hidden">{t('settings.language')}</span>
	<select
		value={i18n.locale}
		onchange={(e) => {
			const l = e.currentTarget.value as Locale;
			i18n.set(l);
			onchange?.(l);
		}}
	>
		{#each LOCALES as l}
			<option value={l.id}>{l.label}</option>
		{/each}
	</select>
</label>

<style>
	.lang {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 0 6px 0 12px;
		border-radius: var(--r-full);
		border: 1px solid var(--border);
		background: var(--surface);
		color: var(--text-muted);
		min-height: 40px;
	}
	.lang:focus-within {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	select {
		border: 0;
		background: transparent;
		font-weight: 600;
		font-size: 0.9rem;
		padding: 8px 4px;
		cursor: pointer;
		outline: none;
	}
</style>
