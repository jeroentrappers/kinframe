<script lang="ts">
	import type { PhotoSourceDeletePolicy, PhotoSourceHistory } from '$lib/api/client';
	import { clampSourceHistory, SOURCE_HISTORY_MAX, type SourceSettings } from '$lib/photoSources';
	import { t } from '$lib/i18n/index.svelte';

	/** The settings of a source: first import (wizard only), deletions, videos, reaction notifications. */
	let {
		settings = $bindable(),
		kind,
		service,
		showHistory = true,
		showNotify = true
	}: { settings: SourceSettings; kind: 'album' | 'feed'; service: string; showHistory?: boolean; showNotify?: boolean } = $props();
	const uid = $props.id();

	const HISTORY: { id: PhotoSourceHistory; label: 'sources.historyAll' | 'sources.historyLastN' | 'sources.historyNone' }[] = [
		{ id: 'all', label: 'sources.historyAll' },
		{ id: 'last_n', label: 'sources.historyLastN' },
		{ id: 'none', label: 'sources.historyNone' }
	];
	const DELETE: PhotoSourceDeletePolicy[] = ['mirror', 'keep'];
</script>

<div class="stack options">
	{#if showHistory}
		<fieldset class="group">
			<legend class="label">{t('sources.history')}</legend>
			{#each HISTORY as o (o.id)}
				<div class="opt" class:checked={settings.history === o.id}>
					<label class="r">
						<input type="radio" name="{uid}-history" value={o.id} checked={settings.history === o.id} onchange={() => (settings = { ...settings, history: o.id })} />
						<span>{t(o.label)}</span>
					</label>
					{#if o.id === 'last_n' && settings.history === 'last_n'}
						<span class="n">
							<label for="{uid}-n" class="visually-hidden">{t('sources.historyCount')}</label>
							<input
								id="{uid}-n"
								class="input num"
								type="number"
								min="1"
								max={SOURCE_HISTORY_MAX}
								value={settings.historyCount}
								onchange={(e) => (settings = { ...settings, historyCount: clampSourceHistory(Number(e.currentTarget.value)) })}
							/>
							<span class="muted small">{t('sources.historyCountUnit')}</span>
						</span>
					{/if}
				</div>
			{/each}
			<p class="hint">{t('sources.historyHint')}</p>
		</fieldset>
	{/if}

	<fieldset class="group">
		<legend class="label">{t('sources.onDelete', { service })}</legend>
		{#each DELETE as d (d)}
			<div class="opt" class:checked={settings.onUpstreamDelete === d}>
				<label class="r">
					<input type="radio" name="{uid}-del" value={d} checked={settings.onUpstreamDelete === d} onchange={() => (settings = { ...settings, onUpstreamDelete: d })} />
					<span>
						{d === 'mirror' ? (kind === 'feed' ? t('sources.mirrorFeed') : t('sources.mirrorAlbum')) : kind === 'feed' ? t('sources.keepFeed') : t('sources.keepAlbum')}
						{#if (d === 'mirror') === (kind === 'album')}<span class="faint small"> · {t('sources.recommended')}</span>{/if}
					</span>
				</label>
			</div>
		{/each}
	</fieldset>

	<label class="switch">
		<input type="checkbox" checked={settings.includeVideos} onchange={(e) => (settings = { ...settings, includeVideos: e.currentTarget.checked })} />
		<span class="sw"><span class="s-label">{t('sources.includeVideos')}</span><span class="hint">{t('sources.includeVideosHint')}</span></span>
	</label>
	{#if showNotify}
		<label class="switch">
			<input type="checkbox" checked={settings.notifyReactions} onchange={(e) => (settings = { ...settings, notifyReactions: e.currentTarget.checked })} />
			<span class="sw"><span class="s-label">{t('sources.notifyReactions')}</span><span class="hint">{t('sources.notifyReactionsHint')}</span></span>
		</label>
	{/if}
</div>

<style>
	.group {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.group legend {
		margin-bottom: 6px;
	}
	.opt {
		display: flex;
		align-items: center;
		gap: 10px;
		flex-wrap: wrap;
		padding: 8px 12px;
		border-radius: var(--r);
		border: 1.5px solid var(--border);
	}
	.opt.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 45%, var(--surface));
	}
	.r {
		display: inline-flex;
		align-items: center;
		gap: 10px;
		cursor: pointer;
		flex: 1;
		min-width: 0;
	}
	.n {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		margin-inline-start: auto;
	}
	.n .input {
		width: 6.5em;
		padding-block: 4px;
	}
	.sw {
		display: flex;
		flex-direction: column;
	}
	.s-label {
		font-weight: 600;
	}
</style>
