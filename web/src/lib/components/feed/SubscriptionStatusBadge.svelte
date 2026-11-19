<script lang="ts">
	import type { SubscriptionStatus } from '$lib/api/client';
	import { t } from '$lib/i18n/index.svelte';

	let { status }: { status: SubscriptionStatus } = $props();
</script>

<span class="sub-badge {status}">{t(`subs.status.${status}`)}</span>

<style>
	.sub-badge {
		display: inline-flex;
		align-items: center;
		padding: 2px 10px;
		border-radius: var(--r-full);
		font-size: 0.78rem;
		font-weight: 650;
		white-space: nowrap;
		background: var(--surface-2);
		color: var(--text-muted);
	}
	.active {
		background: var(--accent-soft);
		color: var(--accent-text);
	}
	.pending,
	.paused {
		background: var(--warn-soft);
		color: var(--warn);
	}
	.rejected,
	.removed {
		background: var(--danger-soft);
		color: var(--danger);
	}
</style>
