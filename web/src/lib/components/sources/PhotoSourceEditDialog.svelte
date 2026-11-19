<script lang="ts">
	import { api, call, type PhotoSource, type PhotoSourceErrorCode, type PhotoSourceUpdate } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { errorText, linkProblemText, normalizeImmichServer, parseIcloudLink, parseImmichLink, providerLabel, type SourceSettings } from '$lib/photoSources';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import SourceOptions from './SourceOptions.svelte';

	/**
	 * Change a source: its name, the connection (a new link, password or key: tested by the server, then synced at
	 * once), and its settings. With `takeOver` the caller becomes the owner; `immich_api` needs a new key with it.
	 */
	let {
		open = $bindable(false),
		source,
		takeOver = false,
		onsaved
	}: { open?: boolean; source: PhotoSource; takeOver?: boolean; onsaved: (s: PhotoSource) => void } = $props();
	const uid = $props.id();

	const service = $derived(providerLabel(source.provider));
	const kind = $derived(source.target.kind);

	let name = $state('');
	let url = $state('');
	let password = $state('');
	let removePassword = $state(false);
	let serverUrl = $state('');
	let apiKey = $state('');
	let settings = $state<SourceSettings>({ history: 'all', historyCount: 20, onUpstreamDelete: 'mirror', includeVideos: true, notifyReactions: true });
	let saving = $state(false);
	let error = $state<string | null>(null);

	$effect(() => {
		if (!open) return;
		name = source.name;
		url = '';
		password = '';
		removePassword = false;
		serverUrl = source.server_url ?? '';
		apiKey = '';
		error = null;
		settings = {
			history: source.history,
			historyCount: source.history_count ?? 20,
			onUpstreamDelete: source.on_upstream_delete,
			includeVideos: source.include_videos,
			notifyReactions: source.notify_reactions ?? true
		};
	});

	const linkProblem = $derived.by(() => {
		if (!url.trim()) return null;
		if (source.provider === 'immich_link') {
			const r = parseImmichLink(url);
			return r.ok ? null : r.problem;
		}
		if (source.provider === 'icloud_link') {
			const r = parseIcloudLink(url);
			return r.ok ? null : r.problem;
		}
		return null;
	});
	const serverProblem = $derived.by(() => {
		if (source.provider !== 'immich_api' || serverUrl.trim() === (source.server_url ?? '')) return null;
		const r = normalizeImmichServer(serverUrl);
		return r.ok ? null : r.problem;
	});
	const needsKey = $derived(takeOver && source.provider === 'immich_api');

	function body(): PhotoSourceUpdate {
		const b: PhotoSourceUpdate = {};
		if (name.trim() && name.trim() !== source.name) b.name = name.trim().slice(0, 60);
		if (url.trim()) {
			if (source.provider === 'immich_link') {
				const r = parseImmichLink(url);
				if (r.ok) b.url = r.url;
			} else if (source.provider === 'icloud_link') {
				const r = parseIcloudLink(url);
				if (r.ok) b.url = r.url;
			}
		}
		if (source.provider === 'immich_link') {
			if (password) b.password = password;
			else if (removePassword && source.has_password) b.password = null;
		}
		if (source.provider === 'immich_api') {
			const r = normalizeImmichServer(serverUrl);
			if (r.ok && serverUrl.trim() !== (source.server_url ?? '')) b.server_url = r.url;
			if (apiKey.trim()) b.api_key = apiKey.trim();
		}
		if (settings.onUpstreamDelete !== source.on_upstream_delete) b.on_upstream_delete = settings.onUpstreamDelete;
		if (settings.includeVideos !== source.include_videos) b.include_videos = settings.includeVideos;
		if (source.is_owner && settings.notifyReactions !== (source.notify_reactions ?? true)) b.notify_reactions = settings.notifyReactions;
		if (takeOver) b.take_over = true;
		return b;
	}

	const dirty = $derived(Object.keys(body()).length > 0);
	const valid = $derived(!linkProblem && !serverProblem && (!needsKey || apiKey.trim().length >= 8));

	async function save(e: SubmitEvent) {
		e.preventDefault();
		if (!dirty || !valid) return;
		saving = true;
		error = null;
		try {
			const s = await call(api.PATCH('/v1/photo-sources/{sourceId}', { params: { path: { sourceId: source.id } }, body: body() }));
			onsaved(s);
			open = false;
			toasts.success(takeOver ? t('sources.tookOver', { name: s.name }) : t('sources.saved'));
		} catch (err) {
			if (isCode(err, 'source_test_failed')) error = errorText((err.detail as PhotoSourceErrorCode) ?? null, source) || t('sources.testFailed');
			else if (isCode(err, 'validation_failed') && !discovery.photoSourcesPrivateNetworks) error = `${problemMessage(err)} ${t('sources.privateBlocked')}`;
			else error = problemMessage(err);
		} finally {
			saving = false;
		}
	}
</script>

<Dialog bind:open title={takeOver ? t('sources.takeOverTitle', { name: source.name }) : t('sources.editTitle', { name: source.name })} size="md">
	<form id="{uid}-form" class="stack" onsubmit={save}>
		{#if takeOver}
			<p class="callout small"><Icon name="info" size={18} />{t('sources.takeOverBody')}</p>
		{/if}
		<div class="field">
			<label for="{uid}-name">{t('sources.name')}</label>
			<input id="{uid}-name" class="input" maxlength="60" bind:value={name} />
		</div>

		<section class="stack-sm">
			<h3 class="label">{t('sources.connection')}</h3>
			{#if source.provider === 'immich_api'}
				<div class="field">
					<label for="{uid}-server">{t('sources.serverUrl')}</label>
					<input id="{uid}-server" class="input" type="url" inputmode="url" autocomplete="off" spellcheck="false" bind:value={serverUrl} />
					{#if serverProblem}<p class="error-text">{linkProblemText(serverProblem, discovery.photoSourcesPrivateNetworks)}</p>{/if}
				</div>
				<div class="field">
					<label for="{uid}-key">{needsKey ? t('sources.apiKey') : t('sources.newApiKey')}</label>
					<input id="{uid}-key" class="input mono" type="password" autocomplete="off" spellcheck="false" bind:value={apiKey} required={needsKey} />
					<p class="hint">{needsKey ? t('sources.takeOverKeyHint') : t('sources.keyKeptHint')}</p>
				</div>
			{:else}
				<div class="field">
					<label for="{uid}-url">{t('sources.newLink')}</label>
					<input id="{uid}-url" class="input" type="url" inputmode="url" autocomplete="off" spellcheck="false" placeholder={source.url_hint ?? ''} bind:value={url} />
					{#if linkProblem}<p class="error-text">{linkProblemText(linkProblem, discovery.photoSourcesPrivateNetworks)}</p>{:else}<p class="hint">{t('sources.newLinkHint')}</p>{/if}
				</div>
				{#if source.provider === 'immich_link'}
					<div class="field">
						<label for="{uid}-pw">{source.has_password ? t('sources.newPassword') : t('sources.password')}</label>
						<input id="{uid}-pw" class="input" type="password" autocomplete="off" bind:value={password} disabled={removePassword} />
					</div>
					{#if source.has_password}
						<label class="check"><input type="checkbox" bind:checked={removePassword} />{t('sources.removePassword')}</label>
					{/if}
				{/if}
			{/if}
		</section>

		<SourceOptions bind:settings {kind} {service} showHistory={false} showNotify={source.is_owner === true} />

		{#if error}<p class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{error}</p>{/if}
	</form>
	{#snippet footer()}
		<button class="btn btn-ghost" type="button" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" type="submit" form="{uid}-form" disabled={!dirty || !valid || saving}>
			{#if saving}<span class="spinner" aria-hidden="true"></span>{/if}{takeOver ? t('sources.takeOver') : t('common.save')}
		</button>
	{/snippet}
</Dialog>

<style>
	.check {
		display: inline-flex;
		gap: 8px;
		align-items: center;
		font-size: 0.92rem;
	}
	h3.label {
		margin: 0;
	}
	.mono {
		font-family: ui-monospace, monospace;
	}
</style>
