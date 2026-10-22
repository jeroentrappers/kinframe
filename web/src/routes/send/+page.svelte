<script lang="ts">
	import { onMount } from 'svelte';
	import { flip } from 'svelte/animate';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { compose } from '$lib/compose.svelte';
	import { push } from '$lib/push.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { filesFromDataTransfer } from '$lib/dropfiles';
	import { can, frames } from '$lib/frames.svelte';
	import { formatBytes, formatDuration } from '$lib/format';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import { reducedMotion, softFade } from '$lib/motion';
	import Dialog from '$components/Dialog.svelte';
	import DraftCard from '$components/DraftCard.svelte';
	import Icon from '$components/Icon.svelte';
	import Illustration from '$components/Illustration.svelte';
	import ItemEditor from '$components/ItemEditor.svelte';
	import OnlineStatus from '$components/OnlineStatus.svelte';

	let fileInput: HTMLInputElement | undefined = $state();
	let folderInput: HTMLInputElement | undefined = $state();
	let dragDepth = $state(0);
	let editingKey = $state<string | null>(null);
	let editorOpen = $state(false);

	const sendable = $derived(frames.list.filter((f) => can(f, 'send')));
	const editing = $derived(compose.items.find((i) => i.key === editingKey) ?? null);
	const locked = $derived(compose.phase !== 'compose');
	const limits = $derived(discovery.limits);
	const screenAspect = $derived.by(() => {
		const f = frames.list.find((x) => compose.frameIds.includes(x.id) && x.screen);
		return f?.screen ? f.screen.width / f.screen.height : 16 / 10;
	});
	const totalBytes = $derived(compose.items.reduce((s, i) => s + i.file.size, 0));

	// Preselect: ?frame=<id>, or the only frame you can send to.
	$effect(() => {
		if (!frames.loaded || compose.frameIds.length > 0) return;
		const wanted = page.url.searchParams.get('frame');
		if (wanted && sendable.some((f) => f.id === wanted)) compose.frameIds = [wanted];
		else if (sendable.length === 1) compose.frameIds = [sendable[0]!.id];
	});

	function addFiles(files: File[]) {
		if (locked || files.length === 0) return;
		const { unsupported, overLimit } = compose.add(files);
		if (overLimit > 0) toasts.error(t('send.overLimit', { max: limits.max_items_per_send, n: overLimit }));
		if (unsupported > 0) toasts.show(tn('send.skipped', unsupported));
	}

	onMount(() => {
		const onPaste = (e: ClipboardEvent) => {
			const target = e.target as HTMLElement | null;
			const files = Array.from(e.clipboardData?.files ?? []);
			if (files.length === 0) return;
			if (target?.closest('textarea, input') && files.length === 0) return;
			e.preventDefault();
			addFiles(files);
		};
		const onBeforeUnload = (e: BeforeUnloadEvent) => {
			if (compose.phase === 'uploading') e.preventDefault();
		};
		window.addEventListener('paste', onPaste);
		window.addEventListener('beforeunload', onBeforeUnload);
		return () => {
			window.removeEventListener('paste', onPaste);
			window.removeEventListener('beforeunload', onBeforeUnload);
		};
	});

	async function onDrop(e: DragEvent) {
		e.preventDefault();
		dragDepth = 0;
		if (!e.dataTransfer) return;
		addFiles(await filesFromDataTransfer(e.dataTransfer));
	}

	function onPick(e: Event & { currentTarget: HTMLInputElement }) {
		addFiles(Array.from(e.currentTarget.files ?? []));
		e.currentTarget.value = '';
	}

	function edit(key: string) {
		editingKey = key;
		editorOpen = true;
	}

	async function send() {
		const result = await compose.send();
		if (result) {
			toasts.success(t('send.sent'));
			push.justSent = true;
			await goto(`/history/${result.id}`);
		} else if (compose.error) {
			toasts.error(problemMessage(compose.error));
		}
	}

	const status = $derived.by(() => {
		if (compose.phase === 'uploading')
			return t('send.uploading', { done: compose.uploadedCount, total: compose.items.length, pct: Math.round(compose.progress * 100) });
		if (compose.phase === 'sending') return t('send.finishing');
		return '';
	});
</script>

<svelte:head><title>{t('send.title')} · kinframe</title></svelte:head>

<svelte:window
	ondragenter={(e) => {
		if (e.dataTransfer?.types.includes('Files')) dragDepth++;
	}}
	ondragleave={() => (dragDepth = Math.max(0, dragDepth - 1))}
	ondragover={(e) => e.preventDefault()}
	ondrop={onDrop}
/>

<input bind:this={fileInput} type="file" accept="image/*,video/*,.heic,.heif,.mkv" multiple hidden onchange={onPick} data-testid="file-input" />
<input bind:this={folderInput} type="file" multiple hidden onchange={onPick} {...{ webkitdirectory: true }} />

<div class="container page">
	<div class="page-header">
		<div>
			<h1>{t('send.title')}</h1>
			<p class="lede">{t('send.lede')}</p>
		</div>
	</div>

	{#if frames.loaded && sendable.length === 0}
		<div class="card">
			<div class="no-frames">
				<Icon name="frame" size={28} />
				<div class="stack-sm">
					<h2>{t('send.noFramesTitle')}</h2>
					<p class="muted">{t('send.noFramesBody')}</p>
				</div>
				<a class="btn btn-primary" href="/frames/add"><Icon name="plus" size={18} />{t('frames.add')}</a>
			</div>
		</div>
	{:else}
		<div class="layout">
			<section class="main" aria-label={t('send.itemsLabel')}>
				{#if compose.items.length === 0}
					<div class="dropzone" class:active={dragDepth > 0}>
						<div class="dz-art"><Illustration kind="send" /></div>
						<h2>{t('send.dropTitle')}</h2>
						<p class="muted">{t('send.dropBody', { max: limits.max_items_per_send })}</p>
						<div class="row-wrap center">
							<button class="btn btn-primary btn-lg" onclick={() => fileInput?.click()}>
								<Icon name="image" size={20} />{t('send.choose')}
							</button>
							<button class="btn btn-lg" onclick={() => folderInput?.click()}>
								<Icon name="folder" size={20} />{t('send.chooseFolder')}
							</button>
						</div>
						<p class="hint paste-hint">{t('send.pasteHint')}</p>
					</div>
				{:else}
					<div class="grid-head">
						<p class="muted small num">
							{tn('send.count', compose.items.length, { max: limits.max_items_per_send })} · {formatBytes(totalBytes, i18n.tag)}
						</p>
						{#if !locked}
							<div class="row-wrap">
								<button class="btn btn-sm btn-ghost" onclick={() => fileInput?.click()}><Icon name="plus" size={16} />{t('send.addMore')}</button>
								<button class="btn btn-sm btn-ghost" onclick={() => compose.reset()}><Icon name="trash" size={16} />{t('send.clear')}</button>
							</div>
						{/if}
					</div>
					<ul class="grid" role="list">
						{#each compose.items as item, i (item.key)}
							<li animate:flip={{ duration: reducedMotion() ? 0 : 220 }} transition:softFade>
								<DraftCard {item} index={i} {locked} onedit={() => edit(item.key)} onremove={() => compose.remove(item.key)} />
							</li>
						{/each}
						{#if !locked && compose.items.length < limits.max_items_per_send}
							<li>
								<button class="add-more" onclick={() => fileInput?.click()}>
									<Icon name="plus" size={26} />
									<span>{t('send.addMore')}</span>
								</button>
							</li>
						{/if}
					</ul>
				{/if}
			</section>

			<aside class="side">
				<div class="card card-pad stack sticky">
					<fieldset class="recipients" id="recipients">
						<legend><h2>{t('send.to')}</h2></legend>
						{#if !frames.loaded}
							<div class="skeleton" style="height:56px"></div>
							<div class="skeleton" style="height:56px"></div>
						{:else}
							{#each sendable as frame (frame.id)}
								<label class="recipient" class:checked={compose.frameIds.includes(frame.id)}>
									<input
										type="checkbox"
										checked={compose.frameIds.includes(frame.id)}
										onchange={() => compose.toggleFrame(frame.id)}
										disabled={locked}
									/>
									<span class="r-body">
										<span class="r-name">{frame.name}</span>
										<OnlineStatus online={frame.online} lastSeen={frame.last_seen_at} />
									</span>
									<span class="check" aria-hidden="true"><Icon name="check" size={16} stroke={3} /></span>
								</label>
							{/each}
							{#if compose.frameIds.some((id) => !frames.get(id)?.online)}
								<p class="hint offline-note"><Icon name="info" size={15} />{t('send.offlineNote')}</p>
							{/if}
						{/if}
					</fieldset>

					{#if compose.issues.length > 0}
						<p class="callout callout-error small" role="alert">
							<Icon name="alert" size={18} />{tn('send.issues', compose.issues.length, {
								max: formatDuration(limits.max_video_seconds)
							})}
						</p>
					{/if}

					{#if locked}
						<div class="stack-sm" role="status" aria-live="polite">
							<div class="row small"><span class="spinner" aria-hidden="true"></span>{status}</div>
							<div class="progress"><span style="width:{Math.round(compose.progress * 100)}%"></span></div>
						</div>
						{#if compose.phase === 'uploading'}
							<button class="btn btn-ghost" onclick={() => compose.cancel()}>{t('common.cancel')}</button>
						{/if}
					{:else}
						<button class="btn btn-primary btn-lg btn-block" onclick={send} disabled={!compose.canSend}>
							<Icon name="send" size={20} />
							{compose.items.length > 0 ? tn('send.sendN', compose.items.length) : t('send.sendButton')}
						</button>
						{#if compose.items.length > 0 && compose.frameIds.length === 0}
							<p class="hint center-text">{t('send.pickFrame')}</p>
						{/if}
					{/if}
				</div>
			</aside>
		</div>
	{/if}
</div>

{#if compose.items.length > 0}
	<div class="mobile-bar" transition:softFade>
		{#if locked}
			<div class="mb-status small" role="status"><span class="spinner" aria-hidden="true"></span>{status}</div>
			<div class="progress mb-progress"><span style="width:{Math.round(compose.progress * 100)}%"></span></div>
		{:else if compose.frameIds.length === 0}
			<span class="small muted">{t('send.pickFrame')}</span>
			<button class="btn btn-soft btn-sm" onclick={() => document.getElementById('recipients')?.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth' })}>{t('send.to')}</button>
		{:else}
			<span class="small muted mb-to">{compose.frameIds.map((id) => frames.get(id)?.name).join(', ')}</span>
			<button class="btn btn-primary" onclick={send} disabled={!compose.canSend}><Icon name="send" size={18} />{tn('send.sendN', compose.items.length)}</button>
		{/if}
	</div>
{/if}

{#if dragDepth > 0 && !locked}
	<div class="drop-veil" transition:softFade aria-hidden="true">
		<div class="veil-card"><Icon name="upload" size={32} />{t('send.dropNow')}</div>
	</div>
{/if}

<Dialog bind:open={editorOpen} title={t('send.adjustTitle')} size="md">
	{#if editing}
		<ItemEditor
			item={editing}
			{screenAspect}
			total={compose.items.length}
			onapplyall={(c) => {
				compose.applyCaptionToAll(c);
				toasts.success(tn('send.appliedAll', compose.items.length));
			}}
		/>
	{/if}
	{#snippet footer()}
		<button class="btn btn-primary" onclick={() => (editorOpen = false)}>{t('common.done')}</button>
	{/snippet}
</Dialog>

<style>
	.layout {
		display: grid;
		gap: var(--s-5);
		align-items: start;
	}
	@media (min-width: 960px) {
		.layout {
			grid-template-columns: 1fr 340px;
		}
	}
	.dropzone {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		gap: var(--s-3);
		padding: var(--s-6) var(--s-4);
		border: 2px dashed var(--border-strong);
		border-radius: var(--r-xl);
		background: var(--surface);
		transition:
			border-color var(--dur) var(--ease),
			background var(--dur) var(--ease);
	}
	.dropzone.active {
		border-color: var(--primary);
		background: var(--primary-soft);
	}
	.dz-art {
		width: 170px;
	}
	.center {
		justify-content: center;
	}
	.paste-hint {
		margin-top: var(--s-1);
	}
	@media (hover: none) {
		.paste-hint {
			display: none;
		}
	}
	.grid-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s-2);
		flex-wrap: wrap;
		margin-bottom: var(--s-3);
	}
	.grid {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: var(--s-3);
		grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
	}
	@media (max-width: 420px) {
		.grid {
			grid-template-columns: 1fr 1fr;
			gap: var(--s-2);
		}
	}
	.add-more {
		width: 100%;
		height: 100%;
		min-height: 160px;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 6px;
		border-radius: var(--r-lg);
		border: 2px dashed var(--border-strong);
		background: transparent;
		color: var(--text-muted);
		font-weight: 600;
		cursor: pointer;
	}
	.add-more:hover {
		border-color: var(--primary);
		color: var(--primary);
		background: var(--surface);
	}
	.sticky {
		position: sticky;
		top: calc(var(--nav-h) + 16px);
	}
	.recipients {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.recipients legend {
		padding: 0;
		margin-bottom: 6px;
	}
	.recipients h2 {
		font-size: 1.1rem;
	}
	.recipient {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 10px 12px;
		border-radius: var(--r);
		border: 1.5px solid var(--border);
		background: var(--surface);
		cursor: pointer;
		transition:
			border-color var(--dur) var(--ease),
			background var(--dur) var(--ease);
	}
	.recipient:hover {
		border-color: var(--border-strong);
	}
	.recipient.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 55%, var(--surface));
	}
	.recipient:has(input:focus-visible) {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	.recipient input {
		position: absolute;
		opacity: 0;
		pointer-events: none;
	}
	.r-body {
		display: flex;
		flex-direction: column;
		flex: 1;
		min-width: 0;
	}
	.r-name {
		font-weight: 650;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.check {
		width: 24px;
		height: 24px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		border: 1.5px solid var(--border-strong);
		color: transparent;
		flex: none;
		transition: all var(--dur) var(--ease);
	}
	.checked .check {
		background: var(--primary);
		border-color: var(--primary);
		color: var(--on-primary);
	}
	.offline-note {
		display: flex;
		gap: 6px;
		align-items: flex-start;
	}
	.center-text {
		text-align: center;
	}
	.no-frames {
		display: flex;
		align-items: center;
		gap: var(--s-4);
		padding: var(--s-5);
		flex-wrap: wrap;
	}
	.no-frames .stack-sm {
		flex: 1;
		min-width: 220px;
	}
	.mobile-bar {
		position: fixed;
		z-index: 35;
		left: 0;
		right: 0;
		bottom: calc(var(--tabbar-h) + env(safe-area-inset-bottom));
		display: flex;
		align-items: center;
		gap: var(--s-3);
		flex-wrap: wrap;
		padding: 10px var(--s-4);
		background: color-mix(in srgb, var(--surface) 94%, transparent);
		backdrop-filter: blur(12px);
		border-top: 1px solid var(--border);
	}
	.mobile-bar > :first-child {
		flex: 1;
		min-width: 0;
	}
	.mb-to {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.mb-status {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.mb-progress {
		flex-basis: 100%;
	}
	.layout {
		padding-bottom: 72px;
	}
	@media (min-width: 960px) {
		.mobile-bar {
			display: none;
		}
		.layout {
			padding-bottom: 0;
		}
	}
	.drop-veil {
		position: fixed;
		inset: 0;
		z-index: 50;
		display: grid;
		place-items: center;
		background: color-mix(in srgb, var(--primary) 18%, transparent);
		backdrop-filter: blur(3px);
		pointer-events: none;
	}
	.veil-card {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 10px;
		padding: var(--s-6) var(--s-7);
		border-radius: var(--r-xl);
		background: var(--surface);
		box-shadow: var(--shadow-lg);
		font-weight: 650;
		font-size: 1.1rem;
		color: var(--primary);
		border: 2px dashed var(--primary);
	}
</style>
