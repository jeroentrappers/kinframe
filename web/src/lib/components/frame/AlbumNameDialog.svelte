<script lang="ts">
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';

	let {
		open = $bindable(false),
		title,
		submitLabel,
		initial = '',
		hint,
		onsave
	}: {
		open?: boolean;
		title: string;
		submitLabel: string;
		initial?: string;
		hint?: string;
		/** Resolves when saved; throwing keeps the dialog open. */
		onsave: (name: string) => Promise<void>;
	} = $props();

	let name = $state('');
	let busy = $state(false);
	const id = $props.id();

	$effect(() => {
		if (open) name = initial;
	});

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		const n = name.trim();
		if (!n) return;
		busy = true;
		try {
			await onsave(n);
			open = false;
		} catch {
			/* the caller reported it */
		} finally {
			busy = false;
		}
	}
</script>

<Dialog bind:open {title}>
	<form id="{id}-form" class="stack" onsubmit={submit}>
		<div class="field">
			<label for="{id}-name">{t('albums.name')}</label>
			<!-- svelte-ignore a11y_autofocus -->
			<input id="{id}-name" class="input" maxlength="60" required bind:value={name} placeholder={t('albums.namePlaceholder')} autocomplete="off" autofocus />
			{#if hint}<span class="hint">{hint}</span>{/if}
		</div>
	</form>
	{#snippet footer()}
		<button type="button" class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button type="submit" form="{id}-form" class="btn btn-primary" disabled={busy || !name.trim() || name.trim() === initial}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{submitLabel}
		</button>
	{/snippet}
</Dialog>
