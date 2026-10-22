<script lang="ts">
	import { untrack } from 'svelte';
	import type { Album } from '$lib/api/client';
	import { t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	let {
		open = $bindable(false),
		albums,
		count,
		onpick
	}: {
		open?: boolean;
		albums: Album[];
		/** How many photos are being added. */
		count: number;
		/** An existing album id, or a name for a new album. */
		onpick: (choice: { albumId: string } | { newName: string }) => Promise<void>;
	} = $props();

	const id = $props.id();
	let choice = $state<string>('');
	let newName = $state('');
	let busy = $state(false);

	$effect(() => {
		if (open) {
			choice = untrack(() => albums[0]?.id) ?? 'new';
			newName = '';
		}
	});

	const valid = $derived(choice === 'new' ? newName.trim().length > 0 : choice !== '');

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		if (!valid) return;
		busy = true;
		try {
			await onpick(choice === 'new' ? { newName: newName.trim() } : { albumId: choice });
			open = false;
		} catch {
			/* reported by the caller */
		} finally {
			busy = false;
		}
	}
</script>

<Dialog bind:open title={tn('albums.addTitle', count)}>
	<form id="{id}-form" onsubmit={submit}>
		<fieldset class="choices">
			<legend class="visually-hidden">{t('albums.pick')}</legend>
			{#each albums as a (a.id)}
				<label class="choice" class:checked={choice === a.id}>
					<input type="radio" name="{id}-album" value={a.id} bind:group={choice} />
					<span class="c-icon"><Icon name="album" size={18} /></span>
					<span class="c-name">{a.name}</span>
					<span class="muted small num">{a.placement_count}</span>
				</label>
			{/each}
			<label class="choice" class:checked={choice === 'new'}>
				<input type="radio" name="{id}-album" value="new" bind:group={choice} />
				<span class="c-icon"><Icon name="plus" size={18} /></span>
				<span class="c-name">{t('albums.new')}</span>
			</label>
			{#if choice === 'new'}
				<div class="field new">
					<label for="{id}-name" class="visually-hidden">{t('albums.name')}</label>
					<!-- svelte-ignore a11y_autofocus -->
					<input id="{id}-name" class="input" maxlength="60" bind:value={newName} placeholder={t('albums.namePlaceholder')} autocomplete="off" autofocus />
				</div>
			{/if}
		</fieldset>
	</form>
	{#snippet footer()}
		<button type="button" class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button type="submit" form="{id}-form" class="btn btn-primary" disabled={busy || !valid}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{t('albums.add')}
		</button>
	{/snippet}
</Dialog>

<style>
	.choices {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.choice {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 10px 12px;
		border-radius: var(--r);
		border: 1.5px solid var(--border);
		cursor: pointer;
		min-width: 0;
	}
	.choice.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 55%, var(--surface));
	}
	.choice:has(input:focus-visible) {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	.choice input {
		position: absolute;
		opacity: 0;
		pointer-events: none;
	}
	.c-icon {
		width: 32px;
		height: 32px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--surface-2);
		color: var(--text-muted);
		flex: none;
	}
	.c-name {
		flex: 1;
		min-width: 0;
		font-weight: 600;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.new {
		padding-inline-start: 4px;
	}
</style>
