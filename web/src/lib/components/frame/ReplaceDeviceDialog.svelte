<script lang="ts">
	import { api, call, type Frame } from '$lib/api/client';
	import { isCompleteCode } from '$lib/format';
	import { problemMessage } from '$lib/problem';
	import { t } from '$lib/i18n/index.svelte';
	import CodeInput from '../CodeInput.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	let {
		open = $bindable(false),
		frame,
		onreplaced
	}: { open?: boolean; frame: Frame; onreplaced: (f: Frame) => void } = $props();

	const id = $props.id();
	let code = $state('');
	let busy = $state(false);
	let error = $state<string | null>(null);
	let done = $state(false);

	$effect(() => {
		if (open) {
			code = '';
			error = null;
			done = false;
		}
	});

	async function replace() {
		if (!isCompleteCode(code) || busy) return;
		busy = true;
		error = null;
		try {
			const f = await call(api.POST('/v1/frames/{frameId}/replace-device', { params: { path: { frameId: frame.id } }, body: { code } }));
			done = true;
			onreplaced(f);
		} catch (e) {
			error = problemMessage(e);
		} finally {
			busy = false;
		}
	}
</script>

<Dialog bind:open title={done ? t('replace.doneTitle') : t('replace.title', { name: frame.name })}>
	{#if done}
		<div class="stack done" role="status">
			<span class="ok"><Icon name="check" size={28} stroke={2.6} /></span>
			<p>{t('replace.doneBody', { name: frame.name })}</p>
		</div>
	{:else}
		<div class="stack">
			<p class="muted small">{t('replace.body')}</p>
			<ol class="steps">
				<li>{t('replace.step1')}</li>
				<li>{t('replace.step2')}</li>
				<li>{t('replace.step3', { name: frame.name })}</li>
			</ol>
			<div class="field">
				<label for="{id}-code">{t('replace.codeLabel')}</label>
				<CodeInput id="{id}-code" bind:value={code} invalid={Boolean(error)} describedby="{id}-err" oncomplete={() => void replace()} disabled={busy} />
				{#if error}<p id="{id}-err" class="err small" role="alert"><Icon name="alert" size={15} />{error}</p>{/if}
			</div>
			<p class="callout callout-warn small"><Icon name="alert" size={18} />{t('replace.warning')}</p>
		</div>
	{/if}
	{#snippet footer()}
		{#if done}
			<button class="btn btn-primary" onclick={() => (open = false)}>{t('common.done')}</button>
		{:else}
			<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
			<button class="btn btn-primary" onclick={replace} disabled={busy || !isCompleteCode(code)}>
				{#if busy}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="swap" size={18} />{/if}{t('replace.do')}
			</button>
		{/if}
	{/snippet}
</Dialog>

<style>
	.steps {
		margin: 0;
		padding-inline-start: 1.3em;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.err {
		display: flex;
		align-items: center;
		gap: 6px;
		color: var(--danger);
		font-weight: 600;
		margin-top: 6px;
	}
	.done {
		align-items: center;
		text-align: center;
	}
	.ok {
		width: 56px;
		height: 56px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: #3c6a49;
		color: #fff;
	}
</style>
