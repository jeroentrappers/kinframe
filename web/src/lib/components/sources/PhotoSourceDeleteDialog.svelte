<script lang="ts">
	import { api, call, type PhotoSource } from '$lib/api/client';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';

	/** Stop syncing: keep the imported photos (as the owner's ordinary photos) or remove them (§9.1). */
	let { open = $bindable(false), source, ondeleted }: { open?: boolean; source: PhotoSource; ondeleted: () => void } = $props();
	const uid = $props.id();

	let remove = $state(false);
	let deleting = $state(false);
	const imported = $derived(source.counts.imported ?? 0);
	const feed = $derived(source.target.kind === 'feed');

	$effect(() => {
		if (open) remove = false;
	});

	async function confirm() {
		deleting = true;
		try {
			await call(api.DELETE('/v1/photo-sources/{sourceId}', { params: { path: { sourceId: source.id }, query: { remove_items: remove } } }));
			open = false;
			toasts.success(remove ? t('sources.stoppedRemoved', { name: source.name }) : t('sources.stopped', { name: source.name }));
			ondeleted();
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			deleting = false;
		}
	}
</script>

<Dialog bind:open title={t('sources.stopTitle', { name: source.name })}>
	<div class="stack">
		{#if imported > 0}
			<fieldset class="choice">
				<legend class="label">{t('sources.stopQuestion', { photos: tn('history.photos', imported) })}</legend>
				<label class="opt" class:checked={!remove}>
					<input type="radio" name="{uid}-keep" checked={!remove} onchange={() => (remove = false)} />
					<span><span class="s-label">{feed ? t('sources.keepInFeed') : t('sources.keepOnFrame')}</span><span class="hint">{t('sources.keepHint')}</span></span>
				</label>
				<label class="opt" class:checked={remove}>
					<input type="radio" name="{uid}-keep" checked={remove} onchange={() => (remove = true)} />
					<span><span class="s-label">{feed ? t('sources.removeFromFeed') : t('sources.removeFromFrame')}</span><span class="hint">{t('sources.removeHint')}</span></span>
				</label>
			</fieldset>
		{:else}
			<p class="muted">{t('sources.stopBody')}</p>
		{/if}
	</div>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={confirm} disabled={deleting}>
			{#if deleting}<span class="spinner" aria-hidden="true"></span>{/if}{t('sources.stop')}
		</button>
	{/snippet}
</Dialog>

<style>
	.choice {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.choice legend {
		margin-bottom: 8px;
	}
	.opt {
		display: flex;
		gap: 10px;
		align-items: flex-start;
		padding: 10px 12px;
		border-radius: var(--r);
		border: 1.5px solid var(--border);
		cursor: pointer;
	}
	.opt.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 45%, var(--surface));
	}
	.opt > span {
		display: flex;
		flex-direction: column;
	}
	.s-label {
		font-weight: 600;
	}
	.opt input {
		margin-top: 4px;
	}
</style>
