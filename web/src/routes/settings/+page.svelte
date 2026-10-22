<script lang="ts">
	import { api, authFetch, call, type User } from '$lib/api/client';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { auth, PLACEHOLDER_NAME, safeNext } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { formatBytes, formatDuration } from '$lib/format';
	import { squareAvatar } from '$lib/media';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Avatar from '$components/Avatar.svelte';
	import Icon from '$components/Icon.svelte';
	import LanguagePicker from '$components/LanguagePicker.svelte';

	// After a first sign-in without a name claim: ask once, then continue where they were going.
	const welcome = page.url.searchParams.get('welcome') === '1';
	const welcomeNext = safeNext(page.url.searchParams.get('next'));
	let name = $state(auth.me?.display_name === PLACEHOLDER_NAME ? '' : (auth.me?.display_name ?? ''));
	let saving = $state(false);
	let uploading = $state(false);
	let avatarInput: HTMLInputElement | undefined = $state();
	const dirty = $derived(name.trim() !== (auth.me?.display_name ?? '') && name.trim().length > 0);

	async function saveName(e: SubmitEvent) {
		e.preventDefault();
		saving = true;
		try {
			auth.me = await call(api.PATCH('/v1/me', { body: { display_name: name.trim() } }));
			toasts.success(t('settings.nameSaved'));
			if (welcome) await goto(welcomeNext);
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			saving = false;
		}
	}

	async function onAvatar(e: Event & { currentTarget: HTMLInputElement }) {
		const file = e.currentTarget.files?.[0];
		e.currentTarget.value = '';
		if (!file) return;
		uploading = true;
		try {
			const blob = await squareAvatar(file);
			const res = await authFetch('/v1/me/avatar', { method: 'PUT', headers: { 'Content-Type': 'image/jpeg' }, body: blob });
			auth.me = (await res.json()) as User;
			toasts.success(t('settings.avatarSaved'));
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			uploading = false;
		}
	}

	async function removeAvatar() {
		try {
			await call(api.DELETE('/v1/me/avatar'));
			if (auth.me) auth.me = { ...auth.me, avatar_url: null };
		} catch (err) {
			toasts.error(problemMessage(err));
		}
	}

	async function saveLocale(l: string) {
		try {
			auth.me = await call(api.PATCH('/v1/me', { body: { locale: l === 'nl' ? 'nl-BE' : 'en' } }));
		} catch {
			/* the UI already switched; the server copy is only for push texts */
		}
	}

	const limits = $derived(discovery.limits);
</script>

<svelte:head><title>{t('settings.title')} · kinframe</title></svelte:head>

<div class="container page narrow">
	<div class="page-header"><h1>{t('settings.title')}</h1></div>

	<div class="stack">
		<section class="card card-pad" aria-labelledby="profile-title">
			<div class="section-title"><h2 id="profile-title">{t('settings.profile')}</h2></div>
			<div class="profile">
				<div class="avatar-col">
					<Avatar name={auth.me?.display_name ?? ''} url={auth.me?.avatar_url} size={88} />
					<input bind:this={avatarInput} type="file" accept="image/jpeg,image/png,image/webp" hidden onchange={onAvatar} />
					<div class="row-wrap">
						<button class="btn btn-sm" onclick={() => avatarInput?.click()} disabled={uploading}>
							{#if uploading}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="upload" size={16} />{/if}
							{t('settings.changePhoto')}
						</button>
						{#if auth.me?.avatar_url}
							<button class="btn btn-ghost btn-sm" onclick={removeAvatar}>{t('settings.removePhoto')}</button>
						{/if}
					</div>
				</div>
				<form class="stack grow" onsubmit={saveName}>
					{#if welcome}
						<div class="welcome" role="status">
							<strong>{t('settings.welcomeTitle')}</strong>
							<span class="muted">{t('settings.welcomeBody')}</span>
						</div>
					{/if}
					<div class="field">
						<label for="display-name">{t('settings.displayName')}</label>
						<!-- svelte-ignore a11y_autofocus -->
						<input id="display-name" class="input" maxlength="40" required bind:value={name} autocomplete="nickname" autofocus={welcome} />
						<span class="hint">{t('settings.displayNameHint')}</span>
					</div>
					{#if auth.me?.email}
						<div class="field">
							<span class="label">{t('settings.email')}</span>
							<span class="muted">{auth.me.email}</span>
						</div>
					{/if}
					<div><button class="btn btn-primary" type="submit" disabled={!dirty || saving}>{t('common.save')}</button></div>
				</form>
			</div>
		</section>

		<section class="card card-pad" aria-labelledby="pref-title">
			<div class="section-title"><h2 id="pref-title">{t('settings.preferences')}</h2></div>
			<div class="row between">
				<div>
					<p class="label">{t('settings.language')}</p>
					<p class="hint">{t('settings.languageHint')}</p>
				</div>
				<LanguagePicker onchange={saveLocale} />
			</div>
		</section>

		<section class="card card-pad" aria-labelledby="server-title">
			<div class="section-title"><h2 id="server-title">{t('settings.server')}</h2></div>
			<dl class="facts">
				<div><dt>{t('settings.serverName')}</dt><dd>{discovery.name}</dd></div>
				<div><dt>{t('settings.version')}</dt><dd class="num">{discovery.doc?.version ?? '–'}</dd></div>
				<div><dt>{t('settings.address')}</dt><dd class="mono">{location.host}</dd></div>
				<div><dt>{t('settings.maxItems')}</dt><dd class="num">{limits.max_items_per_send}</dd></div>
				<div><dt>{t('settings.maxVideo')}</dt><dd class="num">{formatDuration(limits.max_video_seconds)}</dd></div>
				<div><dt>{t('settings.maxUpload')}</dt><dd class="num">{formatBytes(limits.max_upload_bytes, i18n.tag)}</dd></div>
			</dl>
			{#if auth.mode === 'dev'}<p class="hint dev-note"><span class="badge">DEV</span> {t('settings.devMode')}</p>{/if}
		</section>

		<section class="card card-pad row between" aria-label={t('settings.signOut')}>
			<p class="muted small">{t('settings.signedInAs', { name: auth.me?.display_name ?? '' })}</p>
			<button class="btn" onclick={() => auth.signOut()}><Icon name="logout" size={18} />{t('settings.signOut')}</button>
		</section>

		<p class="faint tiny about">{t('settings.about')}</p>
	</div>
</div>

<style>
	.welcome {
		display: grid;
		gap: 4px;
		padding: 14px 16px;
		border-radius: var(--radius, 12px);
		background: var(--accent-soft, color-mix(in srgb, var(--accent) 12%, transparent));
	}
	.narrow {
		max-width: 760px;
	}
	.profile {
		display: flex;
		gap: var(--s-5);
		flex-wrap: wrap;
	}
	.avatar-col {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--s-3);
	}
	.grow {
		flex: 1;
		min-width: 240px;
	}
	.between {
		justify-content: space-between;
		flex-wrap: wrap;
	}
	.facts {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
		gap: var(--s-3);
		margin: 0;
	}
	.facts dt {
		font-size: 0.78rem;
		color: var(--text-faint);
		font-weight: 650;
		text-transform: uppercase;
		letter-spacing: 0.05em;
	}
	.facts dd {
		margin: 2px 0 0;
		font-weight: 600;
		overflow-wrap: anywhere;
	}
	.mono {
		font-family: ui-monospace, Menlo, monospace;
		font-size: 0.9rem;
	}
	.dev-note {
		margin-top: var(--s-3);
	}
	.about {
		text-align: center;
		padding-top: var(--s-3);
	}
</style>
