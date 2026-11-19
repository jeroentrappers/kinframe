<script lang="ts">
	import { discovery } from '$lib/discovery.svelte';
	import { t } from '$lib/i18n/index.svelte';

	/** "Show the feed's birthdays" when subscribing (birthdays.md §3, frame owner story 2): on unless turned off. */
	let { value = $bindable(true) }: { value?: boolean } = $props();
</script>

{#if discovery.birthdays}
	<label class="switch">
		<input type="checkbox" bind:checked={value} />
		<span><span class="s-label">{t('subs.birthdays')}</span><span class="hint">{t('subs.birthdaysHint')}</span></span>
	</label>
{/if}

<style>
	.switch > span {
		display: flex;
		flex-direction: column;
		text-align: start;
	}
	.s-label {
		font-weight: 600;
	}
</style>
