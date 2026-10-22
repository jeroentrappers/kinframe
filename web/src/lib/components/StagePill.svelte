<script lang="ts">
	import type { Stage } from '$lib/logic';
	import { t } from '$lib/i18n/index.svelte';
	import Icon, { type IconName } from './Icon.svelte';

	let { stage, label }: { stage: Stage; label?: string } = $props();
	const ICON: Record<Stage, IconName> = {
		processing: 'clock',
		on_its_way: 'send',
		delivered: 'check',
		seen: 'eye',
		failed: 'alert',
		removed: 'x'
	};
</script>

<span class="pill {stage}">
	{#if stage === 'processing'}
		<span class="spin" aria-hidden="true"></span>
	{:else}
		<Icon name={ICON[stage]} size={14} stroke={2.4} />
	{/if}
	{label ?? t(`stage.${stage}`)}
</span>

<style>
	.pill {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 3px 10px 3px 8px;
		border-radius: var(--r-full);
		font-size: 0.8rem;
		font-weight: 650;
		white-space: nowrap;
		background: var(--surface-2);
		color: var(--text-muted);
	}
	.on_its_way {
		background: var(--warn-soft);
		color: var(--warn);
	}
	.delivered {
		background: var(--accent-soft);
		color: var(--accent-text);
	}
	.seen {
		background: var(--accent);
		color: var(--surface);
	}
	.failed {
		background: var(--danger-soft);
		color: var(--danger);
	}
	.spin {
		width: 11px;
		height: 11px;
		border-radius: 50%;
		border: 2px solid currentColor;
		border-right-color: transparent;
		animation: spin 0.9s linear infinite;
	}
</style>
