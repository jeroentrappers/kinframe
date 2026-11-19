<script lang="ts">
	import { api, call, type GoogleImport, type PhotoSource } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { formatDateTime, relativeTime } from '$lib/format';
	import { importHeadline, upsertImport } from '$lib/googleImport';
	import { live } from '$lib/live.svelte';
	import { sortSources, upsertSource } from '$lib/photoSources';
	import { problemMessage } from '$lib/problem';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import EmptyState from '$components/EmptyState.svelte';
	import Icon from '$components/Icon.svelte';
	import GoogleImportDialog from '$components/sources/GoogleImport.svelte';
	import PhotoSourceCard from '$components/sources/PhotoSourceCard.svelte';
	import ProviderIcon from '$components/sources/ProviderIcon.svelte';

	// Settings → My photo sources: the sources I set up, across albums and feeds, and my recent Google imports.
	let sources = $state<PhotoSource[]>([]);
	let imports = $state<GoogleImport[]>([]);
	let loading = $state(true);
	let error = $state<unknown>(null);
	let resume = $state<string | null>(null);

	const enabled = $derived(discovery.photoSources || discovery.googlePhotosImport);

	async function load() {
		loading = true;
		error = null;
		try {
			const [s, g] = await Promise.all([
				discovery.photoSources ? call(api.GET('/v1/photo-sources')) : Promise.resolve([] as PhotoSource[]),
				discovery.googlePhotosImport ? call(api.GET('/v1/google-imports')) : Promise.resolve([] as GoogleImport[])
			]);
			sources = s;
			imports = g;
		} catch (e) {
			error = e;
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		if (!enabled) return;
		void load();
		return live.on((e) => {
			if (e.type === 'photo_source.updated' && (e.source.is_owner || sources.some((s) => s.id === e.source.id))) sources = upsertSource(sources, e.source);
			else if (e.type === 'photo_source.removed') sources = sources.filter((s) => s.id !== e.source_id);
			else if (e.type === 'google_import.updated') imports = upsertImport(imports, e.import);
		});
	});

	const sorted = $derived(sortSources(sources));
</script>

<svelte:head><title>{t('sources.myTitle')} · kinframe</title></svelte:head>

<div class="container page narrow">
	<a href="/settings" class="back"><Icon name="chevronLeft" size={18} />{t('nav.settings')}</a>
	<header class="page-header">
		<h1>{t('sources.myTitle')}</h1>
		<p class="muted">{t('sources.myLede')}</p>
	</header>

	{#if !enabled}
		<p class="callout"><Icon name="info" size={18} />{t('sources.off')}</p>
	{:else if loading && sources.length === 0 && imports.length === 0}
		<div class="stack" aria-busy="true" aria-label={t('common.loading')}>
			<div class="skeleton" style="height:120px"></div>
			<div class="skeleton" style="height:120px"></div>
		</div>
	{:else if error}
		<div class="callout callout-error" role="alert">
			<Icon name="alert" />
			<div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={load}>{t('common.tryAgain')}</button></div>
		</div>
	{:else}
		{#if discovery.photoSources}
			<section class="stack-sm" aria-labelledby="my-sources">
				<h2 id="my-sources" class="h2">{t('sources.title')}</h2>
				{#if sorted.length === 0}
					<div class="card">
						<EmptyState compact art="library" title={t('sources.myEmptyTitle')} body={t('sources.myEmptyBody')} />
					</div>
				{:else}
					{#each sorted as s (s.id)}
						<PhotoSourceCard source={s} showTarget onupdated={(x) => (sources = upsertSource(sources, x))} onremoved={(id) => (sources = sources.filter((x) => x.id !== id))} />
					{/each}
				{/if}
			</section>
		{/if}

		{#if discovery.googlePhotosImport}
			<section class="stack-sm imports" aria-labelledby="my-imports">
				<h2 id="my-imports" class="h2">{t('gimport.recent')}</h2>
				{#if imports.length === 0}
					<p class="muted small">{t('gimport.recentEmpty')}</p>
				{:else}
					<ul class="card list" role="list">
						{#each imports as imp (imp.id)}
							<li>
								<button class="imp" onclick={() => (resume = imp.id)} data-testid="google-import-row">
									<ProviderIcon service="google_photos" size={32} decorative />
									<span class="i-text">
										<span class="i-title">{imp.target.kind === 'feed' ? imp.target.feed_name : [imp.target.album_name, imp.target.frame_name].filter(Boolean).join(' · ')}</span>
										<span class="muted small">{importHeadline(imp, i18n.tag)}</span>
									</span>
									<time class="faint tiny" datetime={imp.created_at} title={formatDateTime(imp.created_at, i18n.tag)}>{relativeTime(imp.created_at, i18n.tag)}</time>
								</button>
							</li>
						{/each}
					</ul>
				{/if}
			</section>
		{/if}
	{/if}
</div>

<GoogleImportDialog bind:resume onfinished={(i) => (imports = upsertImport(imports, i))} />

<style>
	.narrow {
		max-width: 760px;
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
	.h2 {
		font-size: 1.15rem;
	}
	.imports {
		margin-top: var(--s-6);
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		overflow: hidden;
	}
	.list li + li {
		border-top: 1px solid var(--border);
	}
	.imp {
		width: 100%;
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: var(--s-3) var(--s-4);
		background: none;
		border: 0;
		font: inherit;
		color: inherit;
		text-align: start;
		cursor: pointer;
	}
	.imp:hover {
		background: var(--surface-2);
	}
	.i-text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.i-title {
		font-weight: 600;
		overflow-wrap: anywhere;
	}
</style>
