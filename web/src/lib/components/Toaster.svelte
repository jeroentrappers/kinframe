<script lang="ts">
	import { toasts } from '$lib/toast.svelte';
	import { softFly } from '$lib/motion';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from './Icon.svelte';
</script>

<div class="toaster" role="region" aria-label={t('a11y.notifications')}>
	<div aria-live="polite" aria-atomic="false" class="list">
		{#each toasts.list as toast (toast.id)}
			<div class="toast {toast.kind}" transition:softFly={{ y: 16 }} role={toast.kind === 'error' ? 'alert' : 'status'}>
				{#if toast.kind === 'success'}<Icon name="check" size={18} />{/if}
				{#if toast.kind === 'error'}<Icon name="alert" size={18} />{/if}
				<span class="msg">{toast.message}</span>
				{#if toast.action}
					<button class="action" onclick={() => toast.action?.run()}>{toast.action.label}</button>
				{/if}
				<button class="close" aria-label={t('common.dismiss')} onclick={() => toasts.dismiss(toast.id)}>
					<Icon name="x" size={16} />
				</button>
			</div>
		{/each}
	</div>
</div>

<style>
	.toaster {
		position: fixed;
		z-index: 60;
		left: 0;
		right: 0;
		bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom) + 12px);
		display: flex;
		justify-content: center;
		pointer-events: none;
		padding: 0 var(--s-3);
	}
	@media (min-width: 860px) {
		.toaster {
			bottom: 24px;
		}
	}
	.list {
		display: flex;
		flex-direction: column;
		gap: 8px;
		width: min(480px, 100%);
	}
	.toast {
		pointer-events: auto;
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 10px 8px 10px 16px;
		min-height: 52px;
		border-radius: var(--r);
		background: #2b231e;
		color: #f8f1ea;
		box-shadow: var(--shadow-lg);
		font-size: 0.94rem;
	}
	@media (prefers-color-scheme: dark) {
		.toast {
			background: #f3ebe3;
			color: #231c17;
		}
	}
	.toast.success :global(svg:first-child) {
		color: #8fd3a2;
	}
	.toast.error :global(svg:first-child) {
		color: #ffab9f;
	}
	@media (prefers-color-scheme: dark) {
		.toast.success :global(svg:first-child) {
			color: #2f7a46;
		}
		.toast.error :global(svg:first-child) {
			color: #b3261e;
		}
	}
	.msg {
		flex: 1;
	}
	.action {
		border: 0;
		background: transparent;
		color: #ffb48e;
		font-weight: 700;
		padding: 8px 10px;
		border-radius: 8px;
		cursor: pointer;
		text-transform: uppercase;
		letter-spacing: 0.04em;
		font-size: 0.82rem;
	}
	@media (prefers-color-scheme: dark) {
		.action {
			color: #a7471e;
		}
	}
	.action:hover {
		background: rgb(255 255 255 / 0.08);
	}
	.close {
		border: 0;
		background: transparent;
		color: inherit;
		opacity: 0.7;
		width: 36px;
		height: 36px;
		display: grid;
		place-items: center;
		border-radius: 8px;
		cursor: pointer;
	}
	.close:hover {
		opacity: 1;
	}
</style>
