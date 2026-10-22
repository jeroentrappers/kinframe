<script lang="ts">
	import { api, call, ApiError } from '$lib/api/client';
	import { formatDateTime } from '$lib/format';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import { DEFAULT_TV_INTERVAL, TV_INTERVALS, type TvLink, type TvScope } from '$lib/tv';
	import { createTvLink, revokeTvLink, tvCast, type TvMode } from '$lib/tvCast.svelte';
	import Dialog from './Dialog.svelte';
	import Icon, { type IconName } from './Icon.svelte';

	let {
		open = $bindable(false),
		frameId,
		frameName,
		scope,
		singleVideo = false
	}: {
		open?: boolean;
		frameId: string;
		frameName: string;
		scope: TvScope;
		/** The scope is one video: offer the Remote Playback API. */
		singleVideo?: boolean;
	} = $props();

	let interval = $state<number>(DEFAULT_TV_INTERVAL);
	let busy = $state<TvMode | 'link' | null>(null);
	let link = $state<TvLink | null>(null);
	let active = $state<TvLink[]>([]);

	const sup = tvCast.support;
	const live = $derived(tvCast.status !== 'idle');
	const cur = $derived(tvCast.current);

	const scopeLabel = $derived(
		scope.kind === 'all'
			? t('tv.scopeAll', { frame: frameName })
			: scope.kind === 'album'
				? t('tv.scopeAlbum', { name: scope.name })
				: tn('tv.scopeSelection', scope.ids.length)
	);

	async function loadActive() {
		try {
			active = await call(api.GET('/v1/frames/{frameId}/tv-links', { params: { path: { frameId } } }));
		} catch {
			active = [];
		}
	}

	$effect(() => {
		if (open) {
			link = null;
			void loadActive();
		}
	});

	async function start(mode: TvMode) {
		busy = mode;
		try {
			await tvCast.start(mode, frameId, scope, interval);
		} catch (e) {
			// Closing the device picker is not an error worth a toast.
			if (cancelled(e)) return;
			toasts.error(e instanceof ApiError && e.status === 404 ? t('tv.nothing') : t('tv.failed'));
		} finally {
			busy = null;
		}
	}

	/** The Cast SDK rejects with the code "cancel"; the Presentation/Remote Playback pickers with AbortError or NotAllowedError. */
	function cancelled(e: unknown): boolean {
		if (e === 'cancel') return true;
		return e instanceof DOMException && (e.name === 'AbortError' || e.name === 'NotAllowedError');
	}

	async function makeLink() {
		busy = 'link';
		try {
			link = await createTvLink(frameId, scope, interval, 'tv');
			void loadActive();
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			busy = null;
		}
	}

	async function copy(url: string) {
		try {
			await navigator.clipboard.writeText(url);
			toasts.success(t('tv.copied'));
		} catch {
			/* the field stays selectable */
		}
	}

	async function revoke(l: TvLink) {
		try {
			await revokeTvLink(frameId, l.id);
			if (link?.id === l.id) link = null;
			active = active.filter((x) => x.id !== l.id);
			toasts.success(t('tv.revoked'));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	async function stop() {
		await tvCast.stop();
		toasts.success(t('tv.stopped'));
	}

	const options = $derived(
		[
			sup.cast && { mode: 'cast' as const, icon: 'cast' as IconName, title: t('tv.cast'), hint: t('tv.castHint') },
			!sup.cast && sup.presentation && { mode: 'presentation' as const, icon: 'tv' as IconName, title: t('tv.present'), hint: t('tv.presentHint') },
			singleVideo && sup.remotePlayback && { mode: 'remote' as const, icon: 'video' as IconName, title: t('tv.remote'), hint: t('tv.remoteHint') }
		].filter(Boolean) as { mode: TvMode; icon: IconName; title: string; hint: string }[]
	);
	const others = $derived(active.filter((l) => l.purpose === 'tv' && l.id !== link?.id));
</script>

<Dialog bind:open title={t('tv.title')} size="md">
	<div class="tv stack">
		{#if live}
			<section class="now card" aria-live="polite">
				<div class="now-thumb">
					{#if cur?.thumb_url}<img src={cur.thumb_url} alt="" />{:else}<Icon name="tv" size={28} />{/if}
				</div>
				<div class="now-body">
					<p class="now-where">
						{#if tvCast.status === 'connecting'}<span class="spinner" aria-hidden="true"></span>{t('tv.connecting')}
						{:else}<Icon name="cast" size={16} />{tvCast.device ? t('tv.castingTo', { device: tvCast.device }) : t('tv.castingNoName')}{/if}
					</p>
					{#if cur}
						<p class="now-cap">{cur.caption ?? cur.sender_name}</p>
						<p class="muted small num">{t('tv.position', { n: tvCast.index + 1, total: tvCast.slides.length })}</p>
					{/if}
				</div>
			</section>
			<div class="controls" role="toolbar" aria-label={t('tv.title')}>
				<button class="btn btn-icon" onclick={() => tvCast.prev()} aria-label={t('tv.prev')} title={t('tv.prev')} disabled={tvCast.mode === 'remote'}><Icon name="prev" size={18} /></button>
				<button class="btn btn-icon btn-primary play" onclick={() => tvCast.toggle()} aria-label={tvCast.playing ? t('tv.pause') : t('tv.play')} title={tvCast.playing ? t('tv.pause') : t('tv.play')}>
					<Icon name={tvCast.playing ? 'pause' : 'play'} size={20} stroke={tvCast.playing ? 2 : 0} />
				</button>
				<button class="btn btn-icon" onclick={() => tvCast.next()} aria-label={t('tv.next')} title={t('tv.next')} disabled={tvCast.mode === 'remote'}><Icon name="next" size={18} /></button>
				<span class="spacer"></span>
				{#if tvCast.mode !== 'remote'}
					<label class="visually-hidden" for="tv-interval-live">{t('tv.interval')}</label>
					<select id="tv-interval-live" class="input select" value={tvCast.interval} onchange={(e) => tvCast.setInterval(Number((e.currentTarget as HTMLSelectElement).value))}>
						{#each TV_INTERVALS as s (s)}<option value={s}>{t('tv.seconds', { n: s })}</option>{/each}
					</select>
				{/if}
				<button class="btn btn-danger-ghost" onclick={stop}><Icon name="x" size={16} />{t('tv.stop')}</button>
			</div>
		{:else}
			<p class="scope"><Icon name={scope.kind === 'album' ? 'album' : 'image'} size={18} />{scopeLabel}</p>

			<div class="field">
				<span class="label" id="tv-interval-label">{t('tv.interval')}</span>
				<div class="segmented" role="radiogroup" aria-labelledby="tv-interval-label">
					{#each TV_INTERVALS as s (s)}
						<label><input type="radio" name="tv-interval" value={s} bind:group={interval} />{t('tv.seconds', { n: s })}</label>
					{/each}
				</div>
			</div>

			<ul class="ways" role="list">
				{#each options as o (o.mode)}
					<li>
						<button class="way" onclick={() => start(o.mode)} disabled={busy !== null}>
							<span class="way-icon"><Icon name={o.icon} size={22} /></span>
							<span class="way-text"><strong>{o.title}</strong><span class="muted small">{o.hint}</span></span>
							{#if busy === o.mode}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="chevronRight" size={18} />{/if}
						</button>
					</li>
				{/each}
				<li>
					<button class="way" onclick={makeLink} disabled={busy !== null}>
						<span class="way-icon"><Icon name="globe" size={22} /></span>
						<span class="way-text"><strong>{t('tv.link')}</strong><span class="muted small">{t('tv.linkHint')}</span></span>
						{#if busy === 'link'}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="chevronRight" size={18} />{/if}
					</button>
				</li>
			</ul>
			{#if !sup.cast}
				<p class="callout small"><Icon name="info" size={18} />{t('tv.noCast')}</p>
			{/if}
		{/if}

		{#if link}
			<section class="linkbox card card-pad stack-sm" aria-labelledby="tv-link-title">
				<h3 id="tv-link-title">{t('tv.linkReady')}</h3>
				<input class="input mono" readonly value={link.url} aria-label={t('tv.linkReady')} onfocus={(e) => (e.currentTarget as HTMLInputElement).select()} />
				<div class="row-wrap">
					<button class="btn btn-primary btn-sm" onclick={() => link && copy(link.url)}><Icon name="copy" size={16} />{t('tv.copy')}</button>
					<a class="btn btn-sm" href={link.url} target="_blank" rel="noopener"><Icon name="maximize" size={16} />{t('tv.open')}</a>
					<span class="spacer"></span>
					<button class="btn btn-ghost btn-sm" onclick={() => link && revoke(link)}>{t('tv.revoke')}</button>
				</div>
				<p class="muted small">{t('tv.validUntil', { when: formatDateTime(link.expires_at, i18n.tag) })}</p>
			</section>
		{/if}

		{#if others.length > 0}
			<details class="active">
				<summary class="small">{tn('tv.activeLinks', others.length)}</summary>
				<ul role="list" class="stack-sm">
					{#each others as l (l.id)}
						<li class="row">
							<Icon name="link" size={16} />
							<span class="small grow">{t('tv.linkBy', { name: l.created_by_name ?? '', when: formatDateTime(l.expires_at, i18n.tag) })}</span>
							<button class="btn btn-ghost btn-sm" onclick={() => revoke(l)}>{t('tv.revoke')}</button>
						</li>
					{/each}
				</ul>
			</details>
		{/if}
	</div>
</Dialog>

<style>
	.scope {
		display: flex;
		align-items: center;
		gap: 8px;
		font-weight: 600;
		margin: 0;
	}
	.ways {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 8px;
	}
	.way {
		width: 100%;
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 12px 14px;
		border-radius: var(--r);
		border: 1px solid var(--border);
		background: var(--surface);
		color: var(--text);
		text-align: start;
		cursor: pointer;
		transition:
			background var(--dur) var(--ease),
			border-color var(--dur) var(--ease);
	}
	.way:hover:not(:disabled) {
		background: var(--surface-2);
		border-color: var(--border-strong);
	}
	.way:disabled {
		opacity: 0.6;
		cursor: default;
	}
	.way-icon {
		flex: none;
		display: grid;
		place-items: center;
		width: 40px;
		height: 40px;
		border-radius: 12px;
		background: var(--accent-soft);
		color: var(--accent-text);
	}
	.way-text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.now {
		display: flex;
		gap: 14px;
		align-items: center;
		padding: 12px;
	}
	.now-thumb {
		flex: none;
		width: 96px;
		height: 64px;
		border-radius: 10px;
		overflow: hidden;
		background: var(--surface-2);
		display: grid;
		place-items: center;
		color: var(--text-muted);
	}
	.now-thumb img {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.now-body {
		min-width: 0;
	}
	.now-body p {
		margin: 0;
	}
	.now-where {
		display: flex;
		align-items: center;
		gap: 6px;
		font-weight: 650;
		color: var(--accent-text);
	}
	.now-cap {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.controls {
		display: flex;
		align-items: center;
		gap: 8px;
		flex-wrap: wrap;
	}
	.controls .play {
		width: 48px;
		height: 48px;
	}
	.select {
		width: auto;
		min-height: 40px;
	}
	.spacer,
	.grow {
		flex: 1;
	}
	.linkbox h3 {
		margin: 0;
		font-size: 1rem;
	}
	.mono {
		font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
		font-size: 0.85rem;
	}
	.active summary {
		cursor: pointer;
		color: var(--text-muted);
	}
	.active ul {
		list-style: none;
		padding: 8px 0 0;
		margin: 0;
	}
</style>
