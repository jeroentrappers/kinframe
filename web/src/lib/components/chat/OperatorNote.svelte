<script lang="ts">
	import { discovery } from '$lib/discovery.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';

	/** Decision B (docs/spec/family-social.md §12): say plainly who runs the server and that they could read messages. */
	let { compact = false }: { compact?: boolean } = $props();
</script>

<p class="note" class:compact><Icon name="shield" size={15} />{t('chat.operatorNote', { server: discovery.name })}</p>

<style>
	.note {
		display: flex;
		gap: 8px;
		align-items: flex-start;
		margin: var(--s-4) 0 0;
		color: var(--text-faint);
		font-size: 0.82rem;
		line-height: 1.4;
	}
	.note :global(svg) {
		margin-top: 2px;
	}
	.compact {
		margin: 0;
		font-size: 0.78rem;
	}
</style>
