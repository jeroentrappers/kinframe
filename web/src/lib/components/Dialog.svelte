<script lang="ts">
	import type { Snippet } from 'svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from './Icon.svelte';

	let {
		open = $bindable(false),
		title,
		children,
		footer,
		size = 'sm'
	}: { open?: boolean; title: string; children: Snippet; footer?: Snippet; size?: 'sm' | 'md' } = $props();

	let dialog: HTMLDialogElement | undefined = $state();

	$effect(() => {
		if (!dialog) return;
		if (open && !dialog.open) dialog.showModal();
		if (!open && dialog.open) dialog.close();
	});
</script>

<dialog
	bind:this={dialog}
	class={size}
	aria-labelledby="dlg-title"
	onclose={() => (open = false)}
	onclick={(e) => {
		if (e.target === dialog) open = false;
	}}
>
	{#if open}
		<div class="inner">
			<header>
				<h2 id="dlg-title">{title}</h2>
				<button class="btn btn-ghost btn-icon btn-sm" aria-label={t('common.close')} onclick={() => (open = false)}>
					<Icon name="x" size={18} />
				</button>
			</header>
			<div class="body">{@render children()}</div>
			{#if footer}<footer>{@render footer()}</footer>{/if}
		</div>
	{/if}
</dialog>

<style>
	dialog {
		border: 0;
		padding: 0;
		border-radius: var(--r-lg);
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-lg);
		width: min(440px, calc(100vw - 24px));
		max-height: calc(100dvh - 48px);
	}
	dialog.md {
		width: min(620px, calc(100vw - 24px));
	}
	dialog::backdrop {
		background: rgb(30 20 12 / 0.45);
		backdrop-filter: blur(2px);
	}
	dialog[open] {
		animation: pop 180ms var(--ease);
	}
	@keyframes pop {
		from {
			opacity: 0;
			transform: translateY(8px) scale(0.98);
		}
	}
	.inner {
		display: flex;
		flex-direction: column;
	}
	header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-3);
		padding: var(--s-4) var(--s-4) 0 var(--s-5);
	}
	h2 {
		font-size: 1.2rem;
	}
	.body {
		padding: var(--s-4) var(--s-5);
	}
	footer {
		display: flex;
		justify-content: flex-end;
		gap: var(--s-2);
		padding: 0 var(--s-5) var(--s-5);
		flex-wrap: wrap;
	}
</style>
