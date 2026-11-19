<script lang="ts">
	import { api, call, type PhotoSource, type PhotoSourceErrorCode, type PhotoSourceProvider } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { formatBytes } from '$lib/format';
	import {
		canContinue,
		chooseProvider,
		connectionBody,
		connectionProblem,
		createBody,
		errorText,
		guessProvider,
		hostOf,
		isPrivateHost,
		isTested,
		linkProblemText,
		newWizard,
		nextStep,
		pickAlbum,
		prevStep,
		providerLabel,
		serviceOf,
		firstImportCount,
		suggestAlbumOnly,
		testFailureText,
		warningText,
		withTest,
		wizardSteps,
		type SourceTarget,
		type WizardState
	} from '$lib/photoSources';
	import { isCode, problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, kindSummary, t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import ProviderIcon from './ProviderIcon.svelte';
	import SourceOptions from './SourceOptions.svelte';

	/** "Sync from…": connect an Immich or iCloud album to an album or a feed (photo-sources.md §3). */
	let {
		open = $bindable(false),
		target,
		oncreated,
		onalbumonly
	}: {
		open?: boolean;
		target: SourceTarget;
		oncreated: (s: PhotoSource) => void;
		/** Turn on the album's "Only in album mode" (the album page writes it). */
		onalbumonly?: () => Promise<void>;
	} = $props();
	const uid = $props.id();

	const providers = $derived(discovery.photoSourceProviders);
	let w = $state<WizardState>(newWizard('album', []));
	let testing = $state(false);
	let creating = $state(false);
	let testError = $state<string | null>(null);
	let createError = $state<string | null>(null);
	let showPassword = $state(false);

	$effect(() => {
		if (!open) return;
		w = newWizard(target.kind, providers);
		testError = null;
		createError = null;
		showPassword = false;
	});

	const steps = $derived(wizardSteps(w, providers.length));
	const stepIndex = $derived(steps.indexOf(w.step));
	const problem = $derived(connectionProblem(w));
	const service = $derived(w.provider ? providerLabel(w.provider) : '');
	const typedHost = $derived(w.provider === 'immich_api' ? hostOf(normalizeForHost(w.serverUrl)) : hostOf(normalizeForHost(w.url)));
	const privateTyped = $derived(!!typedHost && isPrivateHost(typedHost.replace(/:\d+$/, '')));
	const guessed = $derived(w.provider && problem && problem !== 'empty' ? guessProvider(w.url) : null);
	const tested = $derived(isTested(w));
	const suggestOnly = $derived(!!onalbumonly && suggestAlbumOnly(w, target));

	function normalizeForHost(s: string) {
		const v = s.trim();
		return v && !/^[a-z]+:\/\//i.test(v) ? `https://${v}` : v;
	}

	function fieldText(p: typeof problem): string | null {
		if (!p || p === 'empty' || p === 'no_key' || p === 'no_album') return null;
		return linkProblemText(p, discovery.photoSourcesPrivateNetworks);
	}

	const TITLE: Record<PhotoSourceProvider, 'sources.provider.immichLink' | 'sources.provider.immichApi' | 'sources.provider.icloud'> = {
		immich_link: 'sources.provider.immichLink',
		immich_api: 'sources.provider.immichApi',
		icloud_link: 'sources.provider.icloud'
	};
	const BODY: Record<PhotoSourceProvider, 'sources.provider.immichLinkBody' | 'sources.provider.immichApiBody' | 'sources.provider.icloudBody'> = {
		immich_link: 'sources.provider.immichLinkBody',
		immich_api: 'sources.provider.immichApiBody',
		icloud_link: 'sources.provider.icloudBody'
	};

	async function test() {
		const body = connectionBody(w);
		if (!body) return;
		testing = true;
		testError = null;
		try {
			const r = await call(api.POST('/v1/photo-sources:test', { body }));
			w = withTest(w, r);
			if (!r.ok) {
				testError = testFailureText(r, w.provider!);
				if (r.error_code === 'password_required' || r.error_code === 'auth_failed') showPassword = true;
			}
		} catch (e) {
			testError = problemMessage(e);
		} finally {
			testing = false;
		}
	}

	async function loadAlbums() {
		const body = connectionBody(w, false);
		if (!body || body.provider !== 'immich_api') return;
		testing = true;
		testError = null;
		try {
			const r = await call(api.POST('/v1/photo-sources:immich-albums', { body: { server_url: body.server_url!, api_key: body.api_key! } }));
			if (!r.ok) {
				testError = errorText(r.error_code ?? null, { provider: 'immich_api', server_url: body.server_url }) || t('sources.testFailed');
				return;
			}
			w = nextStep({ ...w, albums: r.albums, albumId: null, test: null, testedFor: null }, providers.length);
		} catch (e) {
			testError = problemMessage(e);
		} finally {
			testing = false;
		}
	}

	async function choose(albumId: string) {
		w = pickAlbum(w, albumId);
		await test();
	}

	async function next() {
		testError = null;
		if (w.step === 'connect' && w.provider === 'immich_api') return loadAlbums();
		if (w.step === 'settings') return create();
		w = nextStep(w, providers.length);
	}

	function back() {
		testError = null;
		createError = null;
		w = prevStep(w, providers.length);
	}

	async function create() {
		const body = createBody(w, target);
		if (!body) return;
		creating = true;
		createError = null;
		try {
			const s = await call(api.POST('/v1/photo-sources', { body, headers: { 'Idempotency-Key': crypto.randomUUID() } }));
			open = false;
			toasts.success(t('sources.created', { name: s.name }));
			oncreated(s);
			if (suggestOnly && w.albumOnly) await onalbumonly?.().catch((e) => toasts.error(problemMessage(e)));
		} catch (e) {
			if (isCode(e, 'duplicate_source')) createError = t('problem.duplicate_source');
			else if (isCode(e, 'source_test_failed')) createError = errorText((e.detail as PhotoSourceErrorCode) ?? null, { provider: w.provider! }) || t('sources.testFailed');
			else if (isCode(e, 'limit_reached')) createError = t('sources.limitReached', { max: discovery.limits.max_photo_sources_per_target });
			else if (isCode(e, 'validation_failed') && !discovery.photoSourcesPrivateNetworks && privateTyped) createError = t('sources.privateBlocked');
			else createError = problemMessage(e);
		} finally {
			creating = false;
		}
	}

	function onPaste() {
		// A pasted iCloud link in the Immich field (or the other way round): offer the right provider.
		queueMicrotask(() => {
			const g = guessProvider(w.url);
			if (g && g !== w.provider && providers.includes(g) && w.provider !== 'immich_api') w = { ...chooseProvider(w, g), url: w.url };
		});
	}
</script>

<Dialog bind:open title={t('sources.addTitle', { name: target.name })} size="md">
	<div class="wizard stack" data-step={w.step}>
		{#if steps.length > 1}
			<ol class="steps" aria-label={t('sources.steps')}>
				{#each steps as s, i (s)}
					<li class:done={i < stepIndex} class:current={i === stepIndex} aria-current={i === stepIndex ? 'step' : undefined}>
						<span class="num">{i + 1}</span><span class="visually-hidden">{t(`sources.step.${s}`)}</span>
					</li>
				{/each}
			</ol>
		{/if}

		{#if w.step === 'provider'}
			<p class="muted">{t('sources.chooseProvider')}</p>
			<ul class="providers" role="list">
				{#each providers as p (p)}
					<li><button class="provider card" onclick={() => (w = chooseProvider(w, p))}>
						<ProviderIcon service={serviceOf(p)} size={40} decorative />
						<span class="p-text">
							<span class="p-title">
								{t(TITLE[p])}
								{#if p === 'immich_link'}<span class="badge badge-admin">{t('sources.recommended')}</span>{/if}
								{#if p === 'icloud_link'}<span class="badge best-effort">{t('sources.bestEffortBadge')}</span>{/if}
							</span>
							<span class="hint">{t(BODY[p])}</span>
						</span>
						<Icon name="chevronRight" size={18} />
					</button></li>
				{/each}
			</ul>
		{:else if w.step === 'connect' && w.provider}
			<div class="p-head">
				<ProviderIcon service={serviceOf(w.provider)} size={32} decorative />
				<h3>{t(TITLE[w.provider])}</h3>
			</div>

			{#if w.provider === 'icloud_link'}
				<p class="callout callout-warn small"><Icon name="alert" size={18} />{t('sources.icloud.bestEffort')}</p>
				<ol class="howto small">
					<li>{t('sources.icloud.step1')}</li>
					<li>{t('sources.icloud.step2')}</li>
					<li>{t('sources.icloud.step3')}</li>
				</ol>
			{:else if w.provider === 'immich_link'}
				<p class="muted small">{t('sources.immich.linkHowto')}</p>
			{:else}
				<p class="muted small">{t('sources.immich.apiHowto')}</p>
				<p class="hint perms">{t('sources.immich.permissions')} <code>album.read</code> <code>asset.read</code> <code>asset.view</code> <code>asset.download</code></p>
			{/if}

			{#if w.provider === 'immich_api'}
				<div class="field">
					<label for="{uid}-server">{t('sources.serverUrl')}</label>
					<input id="{uid}-server" class="input" type="url" inputmode="url" autocomplete="off" spellcheck="false" placeholder="https://photos.example.org" bind:value={w.serverUrl} />
					{#if fieldText(problem)}<p class="error-text">{fieldText(problem)}</p>{/if}
				</div>
				<div class="field">
					<label for="{uid}-key">{t('sources.apiKey')}</label>
					<input id="{uid}-key" class="input mono" type="password" autocomplete="off" spellcheck="false" bind:value={w.apiKey} />
				</div>
			{:else}
				<div class="field">
					<label for="{uid}-url">{w.provider === 'icloud_link' ? t('sources.icloud.link') : t('sources.immich.link')}</label>
					<input
						id="{uid}-url"
						class="input"
						type="url"
						inputmode="url"
						autocomplete="off"
						spellcheck="false"
						placeholder={w.provider === 'icloud_link' ? 'https://www.icloud.com/sharedalbum/#…' : 'https://photos.example.org/share/…'}
						bind:value={w.url}
						onpaste={onPaste}
					/>
					{#if fieldText(problem)}<p class="error-text">{fieldText(problem)}</p>{/if}
					{#if guessed && guessed !== w.provider && providers.includes(guessed)}
						<button class="btn btn-sm btn-soft switch-provider" onclick={() => (w = { ...chooseProvider(w, guessed), url: w.url })}>
							{guessed === 'icloud_link' ? t('sources.useIcloud') : t('sources.useImmich')}
						</button>
					{/if}
				</div>
				{#if w.provider === 'immich_link'}
					{#if showPassword || w.password}
						<div class="field">
							<label for="{uid}-pw">{t('sources.password')} <span class="faint small">({t('common.optional')})</span></label>
							<input id="{uid}-pw" class="input" type="password" autocomplete="off" bind:value={w.password} />
						</div>
					{:else}
						<button class="btn btn-ghost btn-sm add-pw" onclick={() => (showPassword = true)}><Icon name="shield" size={16} />{t('sources.hasPassword')}</button>
					{/if}
				{/if}
			{/if}

			{#if w.provider !== 'icloud_link' && !discovery.photoSourcesPrivateNetworks}
				<p class="callout small" class:callout-warn={privateTyped}><Icon name="info" size={18} />{t('sources.privateBlocked')}</p>
			{/if}

			{#if w.provider !== 'immich_api'}
				<div class="row-wrap test-row">
					<button class="btn" onclick={test} disabled={problem !== null || testing}>
						{#if testing}<span class="spinner" aria-hidden="true"></span>{t('sources.testing')}{:else}<Icon name="refresh" size={16} />{t('sources.test')}{/if}
					</button>
				</div>
			{/if}
		{:else if w.step === 'album'}
			<p class="muted">{t('sources.immich.pickAlbum')}</p>
			{#if w.albums.length === 0}
				<p class="callout small"><Icon name="info" size={18} />{t('sources.immich.noAlbums')}</p>
			{:else}
				<ul class="albums" role="list">
					{#each w.albums as a (a.id)}
						<li>
							<label class="album" class:checked={w.albumId === a.id}>
								<input type="radio" name="{uid}-album" checked={w.albumId === a.id} onchange={() => choose(a.id)} disabled={testing} />
								<span class="a-text">
									<span class="a-name">{a.name}</span>
									<span class="hint num">{tn('sources.immich.assets', a.asset_count)}{#if a.shared && a.owner_name} · {t('sources.immich.sharedBy', { name: a.owner_name })}{/if}</span>
								</span>
								{#if testing && w.albumId === a.id}<span class="spinner" aria-hidden="true"></span>{/if}
							</label>
						</li>
					{/each}
				</ul>
			{/if}
		{:else if w.step === 'settings'}
			<p class="callout small"><Icon name={target.kind === 'feed' ? 'feed' : 'album'} size={18} />{target.kind === 'feed' ? t('sources.audienceFeed', { name: target.name }) : t('sources.audienceAlbum', { name: target.name })}</p>
			<div class="field">
				<label for="{uid}-name">{t('sources.name')}</label>
				<input id="{uid}-name" class="input" maxlength="60" bind:value={w.name} />
			</div>
			<SourceOptions bind:settings={w.settings} kind={target.kind} {service} />
			{#if target.kind === 'feed'}<p class="hint">{t('sources.feedBatching')}</p>{/if}
			{#if suggestOnly}
				<div class="callout small suggest" data-testid="suggest-album-only">
					<Icon name="album" size={18} />
					<label class="switch">
						<input type="checkbox" bind:checked={w.albumOnly} />
						<span class="sw"><span class="s-label">{t('albums.onlyInAlbumMode')}</span><span class="hint">{t('sources.suggestAlbumOnly', { photos: tn('history.photos', firstImportCount(w) ?? 0) })}</span></span>
					</label>
				</div>
			{/if}
			{#if createError}<p class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{createError}</p>{/if}
		{/if}

		{#if (w.step === 'connect' || w.step === 'album') && w.test}
			{#if w.test.ok && tested}
				<div class="callout callout-ok small result" role="status" data-testid="source-test-ok">
					<Icon name="check" size={18} />
					<div class="stack-sm">
						<span>
							<strong>{w.test.album_name ?? service}</strong>
							{#if w.test.photos != null || w.test.videos != null} · {kindSummary({ photos: w.test.photos ?? 0, videos: w.test.videos ?? 0 })}{/if}
							{#if w.test.estimate_bytes} · {t('sources.about', { size: formatBytes(w.test.estimate_bytes, i18n.tag) })}{/if}
						</span>
						{#each w.test.warnings ?? [] as warn (warn)}<span class="muted">{warningText(warn, discovery.limits.max_photo_source_items, i18n.tag)}</span>{/each}
						{#if w.test.server_version}<span class="faint tiny">Immich {w.test.server_version}</span>{/if}
					</div>
				</div>
			{/if}
		{/if}
		{#if testError}<p class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{testError}</p>{/if}
	</div>

	{#snippet footer()}
		{#if stepIndex > 0}
			<button class="btn btn-ghost" onclick={back} disabled={testing || creating}>{t('sources.back')}</button>
		{:else}
			<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		{/if}
		{#if w.step !== 'provider'}
			<button class="btn btn-primary" onclick={next} disabled={!canContinue(w) || testing || creating}>
				{#if creating || (testing && w.step === 'connect' && w.provider === 'immich_api')}<span class="spinner" aria-hidden="true"></span>{/if}
				{w.step === 'settings' ? t('sources.connect') : t('sources.continue')}
			</button>
		{/if}
	{/snippet}
</Dialog>

<style>
	.steps {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		gap: 6px;
	}
	.steps li {
		flex: 1;
		height: 4px;
		border-radius: var(--r-full);
		background: var(--surface-3);
		overflow: hidden;
	}
	.steps li .num {
		display: none;
	}
	.steps li.done,
	.steps li.current {
		background: var(--primary);
	}
	.providers {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.provider {
		width: 100%;
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: var(--s-3) var(--s-4);
		text-align: start;
		font: inherit;
		color: inherit;
		cursor: pointer;
		border: 1.5px solid var(--border);
	}
	.provider:hover {
		border-color: var(--primary);
	}
	.p-text {
		flex: 1;
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.p-title {
		font-weight: 650;
		display: inline-flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 6px;
	}
	.best-effort {
		background: var(--warn-soft);
		border-color: transparent;
	}
	.p-head {
		display: flex;
		align-items: center;
		gap: var(--s-3);
	}
	.p-head h3 {
		font-size: 1.05rem;
	}
	.howto {
		margin: 0;
		padding-inline-start: 1.3em;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.perms code {
		font-size: 0.8rem;
		background: var(--surface-2);
		padding: 1px 5px;
		border-radius: var(--r-sm);
	}
	.mono {
		font-family: ui-monospace, monospace;
	}
	.switch-provider,
	.add-pw {
		align-self: flex-start;
	}
	.albums {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
		max-height: 320px;
		overflow-y: auto;
	}
	.album {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 12px;
		border-radius: var(--r);
		border: 1.5px solid var(--border);
		cursor: pointer;
	}
	.album.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 45%, var(--surface));
	}
	.a-text {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.a-name {
		font-weight: 600;
		overflow-wrap: anywhere;
	}
	.suggest .switch {
		flex: 1;
	}
	.sw {
		display: flex;
		flex-direction: column;
	}
	.s-label {
		font-weight: 600;
	}
	.result strong {
		font-weight: 650;
	}
</style>
