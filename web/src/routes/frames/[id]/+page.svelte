<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, call, type Frame } from '$lib/api/client';
	import { can, canManage, frames } from '$lib/frames.svelte';
	import { live } from '$lib/live.svelte';
	import { problemMessage } from '$lib/problem';
	import { i18n, t, type MessageKey } from '$lib/i18n/index.svelte';
	import Icon, { type IconName } from '$components/Icon.svelte';
	import OnlineStatus from '$components/OnlineStatus.svelte';
	import RoleBadge from '$components/RoleBadge.svelte';
	import FrameOverview from '$components/frame/FrameOverview.svelte';
	import FrameLibrary from '$components/frame/FrameLibrary.svelte';
	import FramePeople from '$components/frame/FramePeople.svelte';
	import FrameSettingsForm from '$components/frame/FrameSettingsForm.svelte';

	type Tab = 'overview' | 'library' | 'people' | 'settings';

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
					frame.online = e.online;
					frame.last_seen_at = e.last_seen_at ?? frame.last_seen_at;
				} else if (e.type === 'membership.updated') void load(frameId);
			}
		});
	});

	const tabs = $derived.by(() => {
		if (!frame) return [];
		const list: { id: Tab; label: MessageKey; icon: IconName }[] = [
			{ id: 'overview', label: 'frame.tabOverview', icon: 'frame' },
			{ id: 'library', label: 'frame.tabLibrary', icon: 'image' },
			{ id: 'people', label: 'frame.tabPeople', icon: 'users' }
		];
		if (can(frame, 'change_settings')) list.push({ id: 'settings', label: 'frame.tabSettings', icon: 'settings' });
		return list;
	});

	const tab = $derived.by((): Tab => {
		const q = page.url.searchParams.get('tab') as Tab | null;
		return tabs.some((x) => x.id === q) ? q! : 'overview';
	});

	function selectTab(next: Tab) {
		const url = new URL(page.url);
		if (next === 'overview') url.searchParams.delete('tab');
		else url.searchParams.set('tab', next);
		void goto(url, { replaceState: true, noScroll: true, keepFocus: true });
	}

	function onTabKey(e: KeyboardEvent, index: number) {
		// Tabs run right to left in Arabic and Hebrew, so the arrow keys follow the reading direction.
		const forward = i18n.dir === 'rtl' ? 'ArrowLeft' : 'ArrowRight';
		const back = i18n.dir === 'rtl' ? 'ArrowRight' : 'ArrowLeft';
		const dir = e.key === forward ? 1 : e.key === back ? -1 : 0;
		if (!dir) return;
		e.preventDefault();
		const next = tabs[(index + dir + tabs.length) % tabs.length]!;
		selectTab(next.id);
		document.getElementById(`tab-${next.id}`)?.focus();
	}

	function onUpdated(f: Frame) {
		frame = f;
		frames.upsert(f);
	}
</script>

<svelte:head><title>{frame?.name ?? t('frames.title')} · kinframe</title></svelte:head>

<div class="container page">
	<a href="/frames" class="back"><Icon name="chevronLeft" size={18} />{t('frames.title')}</a>

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
			<div class="skeleton" style="height:40px;width:45%"></div>
			<div class="skeleton" style="height:18px;width:30%"></div>
			<div class="skeleton" style="height:44px;width:100%;max-width:520px"></div>
			<div class="skeleton" style="height:220px"></div>
		</div>
	{:else}
		<header class="head">
			<div class="title">
				<h1>{frame.name}</h1>
				<div class="row-wrap meta">
					<OnlineStatus online={frame.online} lastSeen={frame.last_seen_at} />
					{#if frame.location}<span class="loc"><Icon name="mapPin" size={15} />{frame.location}</span>{/if}
					<RoleBadge role={frame.role} />
				</div>
			</div>
			{#if can(frame, 'send')}
				<a class="btn btn-primary" href="/send?frame={frame.id}"><Icon name="send" size={18} />{t('nav.sendPhotos')}</a>
			{/if}
		</header>

		<div class="tabs segmented" role="tablist" aria-label={t('frame.sections')}>
			{#each tabs as tb, i (tb.id)}
				<button
					id="tab-{tb.id}"
					role="tab"
					aria-selected={tab === tb.id}
					aria-controls="panel-{tb.id}"
					tabindex={tab === tb.id ? 0 : -1}
					onclick={() => selectTab(tb.id)}
					onkeydown={(e) => onTabKey(e, i)}
				>
					<Icon name={tb.icon} size={16} />{t(tb.label)}
				</button>
			{/each}
		</div>

		<div id="panel-{tab}" role="tabpanel" aria-labelledby="tab-{tab}" class="panel" tabindex="-1">
			{#if tab === 'overview'}
				<FrameOverview {frame} onupdated={onUpdated} />
			{:else if tab === 'library'}
				<FrameLibrary {frame} />
			{:else if tab === 'people'}
				<FramePeople {frame} manage={canManage(frame)} onchanged={() => void load(frame!.id)} />
			{:else if tab === 'settings'}
				<FrameSettingsForm {frame} />
			{/if}
		</div>
	{/if}
</div>

<style>
	.back {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--text-muted);
		text-decoration: none;
		font-weight: 600;
		margin-bottom: var(--s-4);
	}
	.head {
		display: flex;
		align-items: flex-end;
		justify-content: space-between;
		gap: var(--s-4);
		flex-wrap: wrap;
		margin-bottom: var(--s-5);
	}
	.title {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		min-width: 0;
	}
	h1 {
		overflow-wrap: anywhere;
	}
	.meta {
		gap: var(--s-3);
	}
	.loc {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--text-muted);
		font-size: 0.9rem;
	}
	.tabs {
		margin-bottom: var(--s-5);
	}
	@media (max-width: 480px) {
		.tabs :global(svg) {
			display: none;
		}
		.tabs button {
			padding: 0 10px; /* long labels (German, Russian) still fit on a phone */
		}
	}
	.panel {
		outline: none;
	}
</style>
