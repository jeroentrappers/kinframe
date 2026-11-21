<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, call, type Frame } from '$lib/api/client';
	import { can, canManage, frames } from '$lib/frames.svelte';
	import { FrameDrop } from '$lib/framedrop.svelte';
	import { needsRefreshOnStatus } from '$lib/frameFace';
	import { live } from '$lib/live.svelte';
	import { softFade } from '$lib/motion';
	import { problemMessage } from '$lib/problem';
	import { t, type MessageKey } from '$lib/i18n/index.svelte';
	import Icon, { type IconName } from '$components/Icon.svelte';
	import FrameCard from '$components/FrameCard.svelte';
	import TvDialog from '$components/TvDialog.svelte';
	import FrameOverview from '$components/frame/FrameOverview.svelte';
	import FrameLibrary from '$components/frame/FrameLibrary.svelte';
	import FramePeople from '$components/frame/FramePeople.svelte';
	import FrameSettingsForm from '$components/frame/FrameSettingsForm.svelte';
	import FrameCalendar from '$components/frame/calendar/FrameCalendar.svelte';
	import FrameFeeds from '$components/frame/FrameFeeds.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import EmailInbox from '$components/inbox/EmailInbox.svelte';
	import TelegramSection from '$components/telegram/TelegramSection.svelte';
	import FrameFreshness from '$components/social/FrameFreshness.svelte';
	import RemoteHelpBanner from '$components/frame/RemoteHelpBanner.svelte';

	let frame = $state<Frame | null>(null);
	let error = $state<unknown>(null);
	const id = $derived(page.params.id!);

	async function load(frameId: string) {
		try {
			frame = await call(api.GET('/v1/frames/{frameId}', { params: { path: { frameId } } }));
			error = null;
		} catch (e) {
			error = e;
		}
	}

	$effect(() => {
		const frameId = id;
		frame = null;
		error = null;
		void load(frameId);
		return live.on((e) => {
			if ('frame_id' in e && e.frame_id === frameId) {
				if (e.type === 'frame.status' && frame) {
					const refresh = needsRefreshOnStatus(frame, e.online);
					frame.online = e.online;
					frame.last_seen_at = e.last_seen_at ?? frame.last_seen_at;
					if (refresh) void load(frameId);
				} else if (e.type === 'membership.updated') void load(frameId);
			}
		});
	});

	// The frame screen (simple-app.md §3.3): the card on top, then one list. Each row opens one section (`?tab=`).
	type Section = 'library' | 'calendar' | 'settings';
	const calendarOk = $derived(!!frame && !!discovery.doc?.calendar && (can(frame, 'change_settings') || can(frame, 'send')));
	const section = $derived.by((): Section | null => {
		const q = page.url.searchParams.get('tab');
		if (q === 'library' || q === 'settings') return q;
		if (q === 'calendar' && calendarOk) return q;
		return null;
	});
	const SECTION_TITLE: Record<Section, MessageKey> = {
		library: 'frameRows.photos',
		calendar: 'frameRows.calendar',
		settings: 'frameRows.settings'
	};
	let tvOpen = $state(false);

	// Photos dragged in from the computer and dropped anywhere on the page are sent to this frame.
	const drop = new FrameDrop(() => (frame && can(frame, 'send') ? frame.id : null));

	function onUpdated(f: Frame) {
		frame = f;
		frames.upsert(f);
	}
</script>

<svelte:head><title>{frame?.name ?? t('frames.title')} · kinframe</title></svelte:head>

<svelte:window ondragenter={drop.enter} ondragleave={drop.leave} ondragover={drop.over} ondrop={drop.drop} />

<p class="visually-hidden" role="status" aria-live="polite">{drop.active && drop.target && frame ? t('frames.dropTo', { name: frame.name }) : ''}</p>

<div class="container page">
	{#if section && frame}
		<a href="/frames/{frame.id}" class="back"><Icon name="chevronLeft" size={18} />{frame.name}</a>
	{:else}
		<a href="/frames" class="back"><Icon name="chevronLeft" size={18} />{t('frames.title')}</a>
	{/if}

	{#if error}
		<div class="callout callout-error" role="alert">
			<Icon name="alert" />
			<div class="stack-sm">
				<span>{problemMessage(error)}</span>
				<a class="btn btn-sm" href="/frames">{t('frames.title')}</a>
			</div>
		</div>
	{:else if !frame}
		<div class="stack" aria-busy="true" aria-label={t('common.loading')}>
			<div class="skeleton" style="height:220px"></div>
			<div class="skeleton" style="height:18px;width:30%"></div>
			<div class="skeleton" style="height:180px"></div>
		</div>
	{:else if section}
		<h1 class="section-h">{t(SECTION_TITLE[section])}</h1>
		<div class="panel">
			{#if section === 'library'}
				<FrameLibrary {frame} />
			{:else if section === 'calendar'}
				<FrameCalendar {frame} />
				{#if can(frame, 'change_settings')}
					<a class="card card-pad row between more-link" href="/frames/{frame.id}?tab=settings#wx-title">
						<span><span class="s-label">{t('frameRows.weatherAddons')}</span><span class="hint">{t('frameRows.weatherAddonsHint')}</span></span>
						<Icon name="chevronRight" size={20} />
					</a>
				{/if}
			{:else}
				<div class="stack">
					{#if discovery.feeds}<FrameFeeds {frame} />{/if}
					{#if can(frame, 'change_settings')}
						<FrameSettingsForm {frame} />
						<!-- Email to frame and Telegram (email-inbox.md, telegram.md): frame managers only, like these settings. -->
						{#if discovery.emailInbox}
							<EmailInbox target={{ kind: 'frame', id: frame.id, name: frame.name }} focus={page.url.searchParams.get('email')} />
						{/if}
						{#if discovery.telegram}
							<TelegramSection target={{ kind: 'frame', id: frame.id, name: frame.name }} focus={page.url.searchParams.get('telegram')} />
						{/if}
					{/if}
					<FramePeople {frame} manage={canManage(frame)} onchanged={() => void load(frame!.id)} />
					<FrameOverview {frame} onupdated={onUpdated} />
				</div>
			{/if}
		</div>
	{:else}
		<div class="top">
			<FrameCard {frame} link={false} />
			<RemoteHelpBanner {frame} />
			{#if discovery.engagement}<FrameFreshness {frame} />{/if}
		</div>

		<ul class="rows" role="list">
			<li>
				<a class="row-link" href="/frames/{frame.id}?tab=library">
					<span class="ri"><Icon name="image" size={20} /></span><span class="rt">{t('frameRows.photos')}<span class="hint">{t('frameRows.photosHint')}</span></span><Icon name="chevronRight" size={18} />
				</a>
			</li>
			{#if can(frame, 'send')}
				<li>
					<a class="row-link" href="/send?frame={frame.id}">
						<span class="ri"><Icon name="send" size={20} /></span><span class="rt">{t('frameRows.sendOnly')}<span class="hint">{t('frameRows.sendOnlyHint')}</span></span><Icon name="chevronRight" size={18} />
					</a>
				</li>
			{/if}
			{#if calendarOk}
				<li>
					<a class="row-link" href="/frames/{frame.id}?tab=calendar">
						<span class="ri"><Icon name="calendar" size={20} /></span><span class="rt">{t('frameRows.calendar')}</span><Icon name="chevronRight" size={18} />
					</a>
				</li>
			{/if}
			<li>
				<button class="row-link" onclick={() => (tvOpen = true)}>
					<span class="ri"><Icon name="tv" size={20} /></span><span class="rt">{t('tv.show')}</span><Icon name="chevronRight" size={18} />
				</button>
			</li>
			<li>
				<a class="row-link" href="/frames/{frame.id}?tab=settings">
					<span class="ri"><Icon name="settings" size={20} /></span><span class="rt">{t('frameRows.settings')}<span class="hint">{t('frameRows.settingsHint')}</span></span><Icon name="chevronRight" size={18} />
				</a>
			</li>
		</ul>
		<TvDialog bind:open={tvOpen} frameId={frame.id} frameName={frame.name} scope={{ kind: 'all' }} />
	{/if}
</div>

{#if drop.active && drop.target && frame}
	<div class="drop-veil" transition:softFade aria-hidden="true">
		<div class="veil-card"><Icon name="upload" size={32} />{t('frames.dropTo', { name: frame.name })}</div>
	</div>
{/if}

<style>
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
		max-width: min(90vw, 480px);
		padding: var(--s-6) var(--s-7);
		border-radius: var(--r-xl);
		background: var(--surface);
		box-shadow: var(--shadow-lg);
		font-weight: 650;
		font-size: 1.1rem;
		text-align: center;
		overflow-wrap: anywhere;
		color: var(--primary);
		border: 2px dashed var(--primary);
	}
	.back {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--text-muted);
		text-decoration: none;
		font-weight: 600;
		margin-bottom: var(--s-4);
	}
	.top {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		max-width: 620px;
		margin-bottom: var(--s-4);
	}
	.section-h {
		margin-bottom: var(--s-4);
	}
	.rows {
		list-style: none;
		margin: 0;
		padding: 0;
		max-width: 620px;
		display: flex;
		flex-direction: column;
		border: 1px solid var(--border);
		border-radius: var(--r-lg);
		background: var(--surface);
		overflow: hidden;
	}
	.rows li + li {
		border-top: 1px solid var(--border);
	}
	.row-link {
		width: 100%;
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: var(--s-3) var(--s-4);
		min-height: 60px;
		border: 0;
		background: transparent;
		color: inherit;
		font: inherit;
		font-weight: 600;
		text-align: start;
		text-decoration: none;
		cursor: pointer;
	}
	.row-link:hover {
		background: var(--surface-2);
	}
	.ri {
		display: grid;
		place-items: center;
		width: 36px;
		height: 36px;
		border-radius: var(--r);
		background: var(--surface-2);
		color: var(--text-muted);
		flex: none;
	}
	.rt {
		flex: 1;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.rt .hint {
		font-weight: 400;
	}
	.more-link {
		margin-top: var(--s-4);
		text-decoration: none;
		color: inherit;
	}
	.panel {
		outline: none;
	}
</style>
