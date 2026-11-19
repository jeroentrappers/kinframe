<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { api, call, type GoogleImport } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import {
		BACK_KEY,
		CHANNEL,
		PICKER_TYPE,
		POPUP_NAME,
		canCancel,
		importDetails,
		importHeadline,
		importProgress,
		isFinal,
		isGoogleUrl,
		pickerUrl,
		readImportMessage,
		resumeId,
		returnUri,
		watching
	} from '$lib/googleImport';
	import type { SourceTarget } from '$lib/photoSources';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { live } from '$lib/live.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import ProviderIcon from './ProviderIcon.svelte';

	/**
	 * "Add from Google Photos" (photo-sources.md §7.3, web row): consent in a popup (same tab when popups are
	 * blocked), the return page reports back, the popup goes on to Google's picker, and this dialog follows the
	 * import until it is done. Without `target` it only resumes imports (`?google_import=<id>` or `resume`).
	 */
	let {
		target = null,
		backPath = null,
		resume = $bindable(null),
		size = 'sm',
		onfinished
	}: {
		target?: SourceTarget | null;
		/** Where a same-tab consent comes back to (this page). */
		backPath?: string | null;
		/** An import to follow (e.g. from "My photo sources"). */
		resume?: string | null;
		size?: 'sm' | 'md';
		onfinished?: (imp: GoogleImport) => void;
	} = $props();

	const FEATURES = 'popup,width=520,height=720';

	let open = $state(false);
	let imp = $state<GoogleImport | null>(null);
	let starting = $state(false);
	let cancelling = $state(false);
	let undoing = $state(false);
	let error = $state<string | null>(null);
	/** The popup could not be opened: show "Continue to Google" instead. */
	let blocked = $state(false);
	let popup: Window | null = null;
	let finishedFor: string | null = null;

	const progress = $derived(imp ? importProgress(imp) : null);
	const details = $derived(imp ? importDetails(imp) : []);
	const title = $derived(imp?.target ? t('gimport.title', { name: (imp.target.kind === 'feed' ? imp.target.feed_name : imp.target.album_name) ?? '' }) : t('gimport.add'));

	function isMine(i: GoogleImport) {
		if (!target) return true;
		return target.kind === 'album' ? i.target.album_id === target.albumId : i.target.feed_id === target.feedId;
	}

	function setImport(next: GoogleImport) {
		imp = next;
		watching.add(next.id);
		if (isFinal(next.status) && finishedFor !== next.id) {
			finishedFor = next.id;
			closePopup();
			onfinished?.(next);
		}
	}

	function closePopup() {
		try {
			popup?.close();
		} catch {
			/* gone */
		}
		popup = null;
	}

	/** Navigate the popup we opened, or report that there is none. */
	function sendPopup(url: string): boolean {
		if (!isGoogleUrl(url)) return false;
		try {
			if (popup && !popup.closed) {
				popup.location.href = url;
				return true;
			}
		} catch {
			/* cross-origin and closed */
		}
		return false;
	}

	async function start() {
		if (!target) return;
		error = null;
		blocked = false;
		imp = null;
		finishedFor = null;
		// Opened right in the click, so popup blockers let it through; it goes to Google once the server answers.
		popup = window.open('', POPUP_NAME, FEATURES);
		try {
			if (popup) popup.document.title = 'Google Photos';
		} catch {
			/* not ours */
		}
		open = true;
		starting = true;
		try {
			const body = {
				...(target.kind === 'album' ? { album_id: target.albumId } : { feed_id: target.feedId }),
				return_uri: returnUri(location.origin)
			};
			const res = await call(api.POST('/v1/google-imports', { body, headers: { 'Idempotency-Key': crypto.randomUUID() } }));
			setImport(res);
			if (res.status === 'needs_auth' && res.auth_url) blocked = !sendPopup(res.auth_url);
			else if (res.status === 'picking' && res.picker_uri) blocked = !sendPopup(pickerUrl(res.picker_uri));
			else closePopup();
		} catch (e) {
			closePopup();
			error = problemMessage(e);
		} finally {
			starting = false;
		}
	}

	function remember() {
		try {
			sessionStorage.setItem(BACK_KEY, backPath ?? location.pathname + location.search);
		} catch {
			/* private mode */
		}
	}

	/** A click: a popup is allowed again; if it still isn't, go to Google in this tab and come back. */
	function continueToGoogle() {
		const url = imp?.status === 'needs_auth' ? imp.auth_url : null;
		if (!url || !isGoogleUrl(url)) return;
		popup = window.open(url, POPUP_NAME, FEATURES);
		if (popup) {
			blocked = false;
			return;
		}
		remember();
		location.href = url;
	}

	function openPicker() {
		const uri = imp?.picker_uri;
		if (!uri || !isGoogleUrl(uri)) return;
		const w = window.open(pickerUrl(uri), '_blank');
		if (!w) location.href = pickerUrl(uri);
	}

	async function refresh(id = imp?.id) {
		if (!id) return;
		try {
			setImport(await call(api.GET('/v1/google-imports/{importId}', { params: { path: { importId: id } } })));
		} catch (e) {
			error = problemMessage(e);
		}
	}

	async function cancel() {
		if (!imp) return;
		cancelling = true;
		try {
			setImport(await call(api.POST('/v1/google-imports/{importId}/cancel', { params: { path: { importId: imp.id } } })));
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			cancelling = false;
		}
	}

	async function undo() {
		if (!imp?.send_ids?.length) return;
		undoing = true;
		try {
			await Promise.all(imp.send_ids.map((sendId) => call(api.DELETE('/v1/sends/{sendId}', { params: { path: { sendId } } }))));
			toasts.success(t('gimport.undone'));
			open = false;
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			undoing = false;
		}
	}

	async function openResume(id: string) {
		try {
			const i = await call(api.GET('/v1/google-imports/{importId}', { params: { path: { importId: id } } }));
			if (!isMine(i)) return;
			finishedFor = isFinal(i.status) ? i.id : null;
			setImport(i);
			blocked = false;
			open = true;
		} catch {
			/* gone (older than 7 days) or not ours */
		}
	}

	// The return page (popup or this tab) tells us consent is over; answer with where to go next.
	async function onReturn(importId: string, channel: BroadcastChannel | null) {
		if (!imp || imp.id !== importId) return;
		await refresh(importId);
		const uri = imp?.status === 'picking' ? imp.picker_uri : null;
		blocked = false;
		channel?.postMessage({ type: PICKER_TYPE, import_id: importId, picker_uri: uri && isGoogleUrl(uri) ? uri : null });
	}

	onMount(() => {
		const channel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(CHANNEL) : null;
		const onChannel = (ev: MessageEvent) => {
			const m = readImportMessage({ origin: location.origin, data: ev.data }, location.origin);
			if (m) void onReturn(m.import_id, channel);
		};
		const onWindow = (ev: MessageEvent) => {
			const m = readImportMessage(ev, location.origin);
			if (m) void onReturn(m.import_id, channel);
		};
		channel?.addEventListener('message', onChannel);
		window.addEventListener('message', onWindow);
		const offLive = live.on((e) => {
			if (e.type === 'google_import.updated' && imp && e.import.id === imp.id) setImport(e.import);
		});
		const fromUrl = resumeId(page.url.searchParams);
		if (fromUrl) void openResume(fromUrl);
		return () => {
			channel?.removeEventListener('message', onChannel);
			channel?.close();
			window.removeEventListener('message', onWindow);
			offLive();
			if (imp) watching.delete(imp.id);
		};
	});

	$effect(() => {
		if (resume) {
			void openResume(resume);
			resume = null;
		}
	});

	// Poll while something is happening (README: every 2–5 s, or follow the websocket).
	$effect(() => {
		if (!open || !imp || isFinal(imp.status)) return;
		const id = imp.id;
		const timer = setInterval(() => void refresh(id), 3000);
		return () => clearInterval(timer);
	});

	$effect(() => {
		if (!open && imp && isFinal(imp.status)) watching.delete(imp.id);
	});
</script>

{#if target && discovery.googlePhotosImport}
	<button class="btn btn-sm gp-btn" onclick={start} disabled={starting}>
		<ProviderIcon service="google_photos" size={20} decorative />{t('gimport.add')}
	</button>
{/if}

<Dialog bind:open {title} {size}>
	<div class="stack gimport" data-testid="google-import" data-status={imp?.status ?? (starting ? 'starting' : '')}>
		{#if starting && !imp}
			<p class="row-sm"><span class="spinner" aria-hidden="true"></span>{t('gimport.starting')}</p>
		{:else if imp}
			<p class="headline" role="status">
				{#if !isFinal(imp.status)}<span class="spinner" aria-hidden="true"></span>{:else if imp.status === 'done'}<Icon name="check" size={20} />{/if}
				<span>{importHeadline(imp, i18n.tag)}</span>
			</p>
			{#if progress !== null}
				<div class="progress" aria-hidden="true"><span style="width:{Math.round(progress * 100)}%"></span></div>
			{/if}
			{#each details as d (d)}<p class="muted small">{d}</p>{/each}

			{#if imp.status === 'needs_auth'}
				{#if blocked}
					<p class="callout callout-warn small"><Icon name="alert" size={18} />{t('gimport.blocked')}</p>
				{:else}
					<p class="muted small">{t('gimport.signInHint')}</p>
				{/if}
				<button class="btn btn-primary" onclick={continueToGoogle}><Icon name="link" size={16} />{t('gimport.continue')}</button>
			{:else if imp.status === 'picking'}
				<p class="muted small">{blocked ? t('gimport.blockedPicker') : t('gimport.pickHint')}</p>
				<button class="btn" class:btn-primary={blocked} onclick={openPicker}><ProviderIcon service="google_photos" size={20} decorative />{t('gimport.openPicker')}</button>
			{:else if imp.status === 'importing'}
				<p class="muted small">{t('gimport.importingHint')}</p>
			{:else if imp.status === 'failed' || imp.status === 'expired'}
				{#if target}<button class="btn btn-primary" onclick={start}><Icon name="refresh" size={16} />{t('common.tryAgain')}</button>{/if}
			{/if}
		{/if}
		{#if error}<p class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{error}</p>{/if}
	</div>
	{#snippet footer()}
		{#if imp && canCancel(imp.status)}
			<button class="btn btn-ghost" onclick={cancel} disabled={cancelling}>
				{#if cancelling}<span class="spinner" aria-hidden="true"></span>{/if}{t('gimport.cancel')}
			</button>
		{/if}
		{#if imp?.status === 'done' && imp.send_ids?.length}
			<button class="btn btn-ghost" onclick={undo} disabled={undoing}>
				{#if undoing}<span class="spinner" aria-hidden="true"></span>{/if}{t('common.undo')}
			</button>
		{/if}
		<button class="btn" class:btn-primary={imp ? isFinal(imp.status) : false} onclick={() => (open = false)}>{imp && !isFinal(imp.status) ? t('common.close') : t('common.done')}</button>
	{/snippet}
</Dialog>

<style>
	.gp-btn {
		gap: 8px;
	}
	.gp-btn :global(.pi) {
		border: 0;
		background: transparent;
	}
	.headline {
		display: flex;
		align-items: center;
		gap: 10px;
		font-weight: 600;
		margin: 0;
	}
	.row-sm {
		display: flex;
		align-items: center;
		gap: 10px;
		margin: 0;
	}
	.gimport .btn {
		align-self: flex-start;
	}
</style>
