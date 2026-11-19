<script lang="ts" module>
	import type { IconName } from '../Icon.svelte';
	export interface MenuItem {
		label: string;
		icon?: IconName;
		danger?: boolean;
		run: () => void;
	}
</script>

<script lang="ts">
	import { tick } from 'svelte';
	import Icon from '../Icon.svelte';

	/** A "⋯" button with a small menu (WAI-ARIA menu button: arrows, Home/End, Escape). */
	let { label, items, size = 'sm', align = 'end' }: { label: string; items: MenuItem[]; size?: 'sm' | 'md'; align?: 'start' | 'end' } = $props();

	let open = $state(false);
	let root: HTMLDivElement | undefined = $state();
	let trigger: HTMLButtonElement | undefined = $state();
	const uid = $props.id();

	async function show() {
		open = true;
		await tick();
		root?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus();
	}

	function close(focus = true) {
		open = false;
		if (focus) trigger?.focus();
	}

	function onKey(e: KeyboardEvent) {
		const list = [...(root?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? [])];
		const i = list.indexOf(document.activeElement as HTMLButtonElement);
		if (e.key === 'Escape') {
			e.preventDefault();
			close();
		} else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
			e.preventDefault();
			list[(i + (e.key === 'ArrowDown' ? 1 : -1) + list.length) % list.length]?.focus();
		} else if (e.key === 'Home') list[0]?.focus();
		else if (e.key === 'End') list[list.length - 1]?.focus();
		else if (e.key === 'Tab') close(false);
	}

	$effect(() => {
		if (!open) return;
		const away = (e: PointerEvent) => {
			if (!root?.contains(e.target as Node)) close(false);
		};
		document.addEventListener('pointerdown', away);
		return () => document.removeEventListener('pointerdown', away);
	});
</script>

{#if items.length > 0}
	<div class="menu-root" bind:this={root}>
		<button
			bind:this={trigger}
			class="btn btn-ghost btn-icon {size === 'sm' ? 'btn-sm' : ''}"
			aria-label={label}
			aria-haspopup="menu"
			aria-expanded={open}
			aria-controls="{uid}-menu"
			onclick={() => (open ? close() : show())}
		>
			<Icon name="more" size={size === 'sm' ? 18 : 20} />
		</button>
		{#if open}
			<!-- svelte-ignore a11y_interactive_supports_focus -->
			<div id="{uid}-menu" class="menu {align}" role="menu" aria-label={label} onkeydown={onKey}>
				{#each items as it (it.label)}
					<button
						role="menuitem"
						class:danger={it.danger}
						tabindex="-1"
						onclick={() => {
							close();
							it.run();
						}}
					>
						{#if it.icon}<Icon name={it.icon} size={16} />{/if}{it.label}
					</button>
				{/each}
			</div>
		{/if}
	</div>
{/if}

<style>
	.menu-root {
		position: relative;
		display: inline-flex;
	}
	.menu {
		position: absolute;
		z-index: 40;
		top: calc(100% + 4px);
		min-width: 200px;
		padding: 4px;
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: var(--r);
		box-shadow: var(--shadow-lg);
		display: flex;
		flex-direction: column;
		animation: pop 140ms var(--ease);
	}
	.menu.end {
		inset-inline-end: 0;
	}
	.menu.start {
		inset-inline-start: 0;
	}
	@keyframes pop {
		from {
			opacity: 0;
			transform: translateY(-4px);
		}
	}
	.menu button {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		min-height: 40px;
		padding: 0 12px;
		border: 0;
		border-radius: var(--r-sm);
		background: transparent;
		color: var(--text);
		font: inherit;
		font-size: 0.92rem;
		text-align: start;
		white-space: nowrap;
		cursor: pointer;
	}
	.menu button:hover,
	.menu button:focus-visible {
		background: var(--surface-2);
		outline: none;
	}
	.menu button.danger {
		color: var(--danger);
	}
</style>
