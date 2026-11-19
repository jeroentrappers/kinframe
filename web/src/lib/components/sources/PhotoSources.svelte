<script lang="ts">
	import { api, call, type PhotoSource } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { live } from '$lib/live.svelte';
	import { belongsTo, sortSources, upsertSource, type SourceTarget } from '$lib/photoSources';
	import { problemMessage } from '$lib/problem';
	import { t } from '$lib/i18n/index.svelte';
	import Icon from '../Icon.svelte';
	import PhotoSourceCard from './PhotoSourceCard.svelte';
	import PhotoSourceWizard from './PhotoSourceWizard.svelte';

	/**
	 * The "Photo sources" section of an album (album managers) or a feed (owner/admins): the outside albums that
	 * fill it, their status, and "Sync from…" (photo-sources.md §3).
	 */
	let {
		target,
		compact = false,
		hideEmpty = false,
		onchange,
		onalbumonly,
		openWizard = $bindable(false)
	}: {
		target: SourceTarget;
		compact?: boolean;
		/** Render nothing but the wizard while there are no sources (the album offers "Sync from…" itself). */
		hideEmpty?: boolean;
		onchange?: (count: number) => void;
		/** Album targets: turns on "Only in album mode" when the wizard suggested it and the person agreed. */
		onalbumonly?: () => Promise<void>;
		openWizard?: boolean;
	} = $props();

	let list = $state<PhotoSource[]>([]);
	let loading = $state(true);
	let error = $state<unknown>(null);

	const key = $derived(target.kind === 'album' ? `a:${target.albumId}` : `f:${target.feedId}`);
	const full = $derived(list.length >= discovery.limits.max_photo_sources_per_target);
	const sorted = $derived(sortSources(list));

	async function load() {
		loading = true;
		error = null;
		try {
			list =
				target.kind === 'album'
					? await call(api.GET('/v1/frames/{frameId}/albums/{albumId}/photo-sources', { params: { path: { frameId: target.frameId, albumId: target.albumId } } }))
					: await call(api.GET('/v1/feeds/{feedId}/photo-sources', { params: { path: { feedId: target.feedId } } }));
		} catch (e) {
			error = e;
			list = [];
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		void key;
		void load();
		return live.on((e) => {
			if (e.type === 'photo_source.updated' && belongsTo(e.source, target)) set(upsertSource(list, e.source));
			else if (e.type === 'photo_source.removed') set(list.filter((s) => s.id !== e.source_id));
		});
	});

	function set(next: PhotoSource[]) {
		const changed = next.length !== list.length;
		list = next;
		if (changed) onchange?.(next.length);
	}
</script>

{#if !(hideEmpty && list.length === 0 && !error)}
<section class="sources stack-sm" class:compact aria-labelledby="ps-title-{key}" data-testid="photo-sources">
	<div class="row between head">
		<h2 id="ps-title-{key}" class="title"><Icon name="refresh" size={18} />{t('sources.title')}</h2>
		<button class="btn btn-sm" onclick={() => (openWizard = true)} disabled={full} title={full ? t('sources.limitReached', { max: discovery.limits.max_photo_sources_per_target }) : undefined}>
			<Icon name="plus" size={16} />{t('sources.add')}
		</button>
	</div>

	{#if loading && list.length === 0}
		<div class="skeleton" style="height:96px"></div>
	{:else if error}
		<div class="callout callout-error small" role="alert">
			<Icon name="alert" size={18} />
			<div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={load}>{t('common.tryAgain')}</button></div>
		</div>
	{:else if list.length === 0}
		<p class="muted small empty">{target.kind === 'feed' ? t('sources.emptyFeed') : t('sources.emptyAlbum')}</p>
	{:else}
		<div class="stack-sm">
			{#each sorted as s (s.id)}
				<PhotoSourceCard source={s} onupdated={(x) => set(upsertSource(list, x))} onremoved={(id) => set(list.filter((x) => x.id !== id))} />
			{/each}
		</div>
	{/if}
</section>
{/if}

<PhotoSourceWizard bind:open={openWizard} {target} {onalbumonly} oncreated={(s) => set(upsertSource(list, s))} />

<style>
	.sources {
		margin-bottom: var(--s-5);
	}
	.head {
		gap: var(--s-3);
	}
	.title {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		font-size: 1.05rem;
	}
	.empty {
		margin: 0;
	}
</style>
