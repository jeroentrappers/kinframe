<script lang="ts">
	import { onMount, untrack } from 'svelte';
	import { flip } from 'svelte/animate';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { compose } from '$lib/compose.svelte';
	import { push } from '$lib/push.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { filesFromDataTransfer } from '$lib/dropfiles';
	import { can, frames } from '$lib/frames.svelte';
	import { feeds } from '$lib/feeds.svelte';
	import FeedCover from '$components/feed/FeedCover.svelte';
	import DestinationSheet from '$components/family/DestinationSheet.svelte';
	import { myCircles } from '$components/social/AudiencePicker.svelte';
	import { families } from '$lib/family.svelte';
	import type { Circle } from '$lib/api/client';
	import { canPostToFeed } from '$lib/feeds';
	import { api, call } from '$lib/api/client';
	import { currentPrompt, familiesToPreselect } from '$lib/engagement';
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

	let fileInput: HTMLInputElement | undefined = $state();
	let folderInput: HTMLInputElement | undefined = $state();
	let dragDepth = $state(0);
	let editingKey = $state<string | null>(null);
	let editorOpen = $state(false);

	const sendable = $derived(frames.list.filter((f) => can(f, 'send')));
	/** Every feed role but `member` ("Reader", family social) can post (feeds.md §4.1). Hidden without feeds. */
	const postable = $derived(discovery.feeds ? families.ranked.filter((f) => canPostToFeed(f.role)) : []);
	const recipientsLoaded = $derived(frames.loaded && (!discovery.feeds || feeds.loaded || feeds.error !== null));
	const recipientNames = $derived(
		[...compose.frameIds.map((id) => frames.get(id)?.name), ...compose.feedIds.map((id) => feeds.get(id)?.name)].filter(Boolean).join(', ')
	);
	$effect(() => void feeds.load());
	const editing = $derived(compose.items.find((i) => i.key === editingKey) ?? null);
	const locked = $derived(compose.phase !== 'compose');
	const limits = $derived(discovery.limits);
	const screenAspect = $derived.by(() => {
		const f = frames.list.find((x) => compose.frameIds.includes(x.id) && x.screen);
		return f?.screen ? f.screen.width / f.screen.height : 16 / 10;
	});
	const totalBytes = $derived(compose.items.reduce((s, i) => s + i.bytes, 0));

	// One destination row (simple-app.md §3.2): the home family (or Home's filtered family); "Change" for several
	// families at once and per family a circle. A single frame only from that frame's screen (?frame=<id>).
	const frameOnly = $derived(compose.frameIds.length > 0 ? (frames.get(compose.frameIds[0]!) ?? null) : null);
	const chosen = $derived(compose.feedIds.map((id) => feeds.get(id)).filter((f) => f !== undefined));
	const destination = $derived(chosen[0] ?? null);
	const circleId = $derived(destination ? (compose.circleOf[destination.id] ?? null) : null);
	let circles = $state<Circle[]>([]);
	$effect(() => {
		const id = destination?.id;
		circles = [];
		if (id && discovery.social) void myCircles(id).then((c) => id === destination?.id && (circles = c));
	});
	const circleName = $derived(circleId ? (circles.find((c) => c.id === circleId)?.name ?? null) : null);
	let changeOpen = $state(false);
	/** "Trappers · everyone and its 2 frames", the circle, "3 families · 9 frames", or "Only Oma's frame". */
	const destinationLine = $derived(
		frameOnly
			? t('compose.onlyFrame', { frame: frameOnly.name })
			: chosen.length > 1
				? `${tn('family.families', chosen.length)} · ${tn('family.frames', chosen.reduce((n, f) => n + f.subscription_count, 0))}`
				: destination
					? circleName
						? t('compose.toCircle', { family: destination.name, circle: circleName })
						: tn('compose.toFamily', destination.subscription_count, { family: destination.name })
					: ''
	);
	/** The default: Home's filtered family when it is one you post to, else the home family. */
	const defaultFamily = $derived(
		(families.filter && postable.find((f) => f.id === families.filter!.id)) || families.ranked.find((f) => postable.some((p) => p.id === f.id)) || null
	);

	function chooseFamilies(ids: string[], circleOf: Record<string, string | null>) {
		compose.frameIds = [];
		compose.feedIds = ids;
		compose.circleOf = Object.fromEntries(ids.map((id) => [id, circleOf[id] ?? null]));
	}
	const chooseFamily = (id: string, circle: string | null) => chooseFamilies([id], { [id]: circle });

	// The composer keeps its draft between visits: follow the entry point (?frame= from a frame's screen, else a family).
	$effect(() => {
		if (!recipientsLoaded || locked) return;
		const wanted = page.url.searchParams.get('frame');
		const wantedFeed = page.url.searchParams.get('feed');
		const request = page.url.searchParams.get('request');
		untrack(() => {
			if (wanted && !request && sendable.some((f) => f.id === wanted)) {
				if (compose.frameIds[0] !== wanted || compose.feedIds.length) {
					compose.feedIds = [];
					compose.frameIds = [wanted];
				}
				return;
			}
			if (compose.frameIds.length) compose.frameIds = [];
			if (wantedFeed && postable.some((f) => f.id === wantedFeed)) {
				if (compose.feedIds[0] !== wantedFeed) chooseFamily(wantedFeed, null);
				return;
			}
			if (compose.feedIds.some((id) => postable.some((f) => f.id === id))) return;
			if (wanted && postable.length) void preselectFamiliesOf(wanted, request);
			else {
				if (defaultFamily) chooseFamily(defaultFamily.id, null);
			}
		});
	});

	// A frame request or a quiet-frame reminder: the family whose posts reach that frame (the request names them,
	// `PhotoRequest.feed_ids`; otherwise ask the families), else the home family.
	async function preselectFamiliesOf(frameId: string, requestId: string | null) {
		let reaching: string[] = [];
		if (requestId && discovery.engagement) {
			reaching = await call(api.GET('/v1/photo-requests/{requestId}', { params: { path: { requestId } } }))
				.then((r) => (r.frame_id === frameId ? (r.feed_ids ?? []) : []))
				.catch(() => []);
		}
		if (!reaching.length) {
			const subs = await Promise.all(
				postable.map((f) =>
					call(api.GET('/v1/feeds/{feedId}/subscriptions', { params: { path: { feedId: f.id }, query: { status: 'active' } } }))
						.then((l) => (l.some((s) => s.frame.id === frameId) ? f.id : null))
						.catch(() => null)
				)
			);
			reaching = subs.filter((x): x is string => !!x);
		}
		const ids = familiesToPreselect(reaching, postable.map((f) => f.id));
		if (ids.length && !compose.hasRecipients) chooseFamilies(ids, {});
		else if (defaultFamily && !compose.hasRecipients) chooseFamily(defaultFamily.id, null);
	}

	// Answer a weekly prompt: ?feed=<id>&prompt=<id> from the prompt card (README §5.20).
	$effect(() => {
		const pid = page.url.searchParams.get('prompt');
		const fid = page.url.searchParams.get('feed');
		if (!discovery.engagement || !pid || !fid || compose.prompt?.id === pid) return;
		call(api.GET('/v1/feeds/{feedId}/prompts', { params: { path: { feedId: fid } } }))
			.then((list) => {
				const p = list.find((x) => x.id === pid && (x.status === 'current' || x.status === 'past'));
				if (p) {
					compose.prompt = { id: p.id, feedId: fid, title: p.title };
					if (!compose.feedIds.includes(fid) && postable.some((f) => f.id === fid)) chooseFamily(fid, null);
				}
			})
			.catch(() => {});
	});
	// Otherwise offer this week's prompt of a chosen family.
	let offered = $state<{ id: string; feedId: string; title: string } | null>(null);
	$effect(() => {
		if (!discovery.engagement || compose.prompt || compose.feedIds.length !== 1) {
			offered = null;
			return;
		}
		const fid = compose.feedIds[0]!;
		call(api.GET('/v1/feeds/{feedId}/prompts', { params: { path: { feedId: fid } } }))
			.then((list) => {
				const p = currentPrompt(list);
				offered = p && compose.feedIds[0] === fid ? { id: p.id, feedId: fid, title: p.title } : null;
			})
			.catch(() => (offered = null));
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
		const toFamily = compose.feedIds.length > 0;
		const result = await compose.send();
		if (result) {
			toasts.success(toFamily ? t('compose.shared') : t('send.sent'));
			push.justSent = true;
			await goto(toFamily ? '/home' : `/history/${result.id}`);
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

	{#if recipientsLoaded && postable.length === 0 && !frameOnly}
		<div class="card">
			<div class="no-frames">
				<Icon name="users" size={28} />
				<div class="stack-sm">
					<h2>{t('compose.readerTitle')}</h2>
					<p class="muted">{t('compose.readerBody')}</p>
				</div>
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
					<section class="destination" id="recipients" aria-labelledby="dest-title">
						<h2 id="dest-title" class="visually-hidden">{t('send.to')}</h2>
						{#if !recipientsLoaded}
							<div class="skeleton" style="height:56px"></div>
						{:else if frameOnly}
							<div class="dest-row">
								<span class="dest-icon"><Icon name="frame" size={22} /></span>
								<span class="dest-body"><span class="dest-name" data-testid="destination">{destinationLine}</span><span class="hint">{t('compose.onlyFrameHint')}</span></span>
								{#if postable.length}<button class="btn btn-ghost btn-sm" onclick={() => (changeOpen = true)} disabled={locked}>{t('compose.change')}</button>{/if}
							</div>
						{:else if destination}
							<div class="dest-row">
								{#if chosen.length > 1}<span class="dest-icon"><Icon name="users" size={22} /></span>{:else}<FeedCover name={destination.name} url={destination.cover_url} size={40} />{/if}
								<span class="dest-body"><span class="dest-name" data-testid="destination">{destinationLine}</span></span>
								{#if postable.length > 1 || circles.length > 0}<button class="btn btn-ghost btn-sm" onclick={() => (changeOpen = true)} disabled={locked}>{t('compose.change')}</button>{/if}
							</div>
						{:else}
							<p class="muted small">{t('compose.noFamily')}</p>
						{/if}
						{#if frameOnly && !frameOnly.online}
							<p class="hint offline-note"><Icon name="info" size={15} />{t('send.offlineNote')}</p>
						{/if}
					</section>

					{#if compose.prompt && compose.feedIds.includes(compose.prompt.feedId)}
						<p class="prompt-chip small"><span aria-hidden="true">💡</span><span>{t('engagement.answers', { title: compose.prompt.title })}</span><button class="btn btn-ghost btn-icon btn-sm" aria-label={t('engagement.untag')} onclick={() => (compose.prompt = null)} disabled={locked}><Icon name="x" size={14} /></button></p>
					{:else if offered}
						<label class="switch prompt-offer">
							<input type="checkbox" onchange={(e) => e.currentTarget.checked && (compose.prompt = offered)} disabled={locked} />
							<span><span class="small">{t('engagement.answerThisWeek')}</span><span class="hint">{offered.title}</span></span>
						</label>
					{/if}

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
							{t('nav.share')}
						</button>
						{#if compose.items.length > 0 && !compose.hasRecipients}
							<p class="hint center-text">{t('compose.noFamily')}</p>
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
		{:else if !compose.hasRecipients}
			<span class="small muted">{t('compose.noFamily')}</span>
		{:else}
			<span class="small muted mb-to">{recipientNames}</span>
			<button class="btn btn-primary" onclick={send} disabled={!compose.canSend}><Icon name="send" size={18} />{t('nav.share')}</button>
		{/if}
	</div>
{/if}

{#if dragDepth > 0 && !locked}
	<div class="drop-veil" transition:softFade aria-hidden="true">
		<div class="veil-card"><Icon name="upload" size={32} />{t('send.dropNow')}</div>
	</div>
{/if}

<DestinationSheet bind:open={changeOpen} families={postable} chosen={compose.feedIds} circleOf={compose.circleOf} onchoose={chooseFamilies} />

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
	.dest-row {
		display: flex;
		align-items: center;
		gap: var(--s-3);
	}
	.dest-icon {
		display: grid;
		place-items: center;
		width: 40px;
		height: 40px;
		border-radius: var(--r);
		background: var(--surface-2);
		flex: none;
	}
	.dest-body {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.dest-name {
		font-weight: 650;
		overflow-wrap: anywhere;
	}
	.prompt-chip {
		display: flex;
		align-items: center;
		gap: 6px;
		margin: 0;
		padding: 4px 4px 4px 12px;
		border-radius: var(--r-full);
		background: var(--warn-soft);
		font-weight: 600;
	}
	.prompt-chip span:nth-child(2) {
		flex: 1;
	}
	.prompt-offer .hint {
		display: block;
	}
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
