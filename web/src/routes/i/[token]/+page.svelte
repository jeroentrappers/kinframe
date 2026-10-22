<script lang="ts">
	import { page } from '$app/state';
	import { api, call, type InvitePreview, type RedeemResult } from '$lib/api/client';
	import { frames } from '$lib/frames.svelte';
	import { problemMessage } from '$lib/problem';
	import { t } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Icon from '$components/Icon.svelte';
	import Illustration from '$components/Illustration.svelte';

	const token = $derived(page.params.token!);
	let preview = $state<InvitePreview | null>(null);
	let error = $state<unknown>(null);
	let result = $state<RedeemResult | null>(null);
	let accepting = $state(false);
	let acceptError = $state<string | null>(null);

	$effect(() => {
		const tk = token;
		preview = null;
		error = null;
		call(api.GET('/v1/invites/{token}', { params: { path: { token: tk } } }))
			.then((p) => (preview = p))
			.catch((e) => (error = e));
	});

	async function accept() {
		accepting = true;
		acceptError = null;
		try {
			result = await call(api.POST('/v1/invites/{token}/accept', { params: { path: { token } } }));
			if (result.status !== 'pending') frames.upsert(result.frame);
		} catch (e) {
			acceptError = problemMessage(e);
		} finally {
			accepting = false;
		}
	}
</script>

<svelte:head><title>{t('invite.title')} · kinframe</title></svelte:head>

<div class="container page narrow">
	<section class="card card-pad panel">
		{#if error}
			<span class="big-icon bad"><Icon name="link" size={28} /></span>
			<h1>{t('invite.invalidTitle')}</h1>
			<p class="muted">{problemMessage(error)}</p>
			<a class="btn btn-primary" href="/frames">{t('add.backToFrames')}</a>
		{:else if !preview}
			<div class="skeleton" style="width:160px;height:120px"></div>
			<div class="skeleton" style="width:70%;height:28px"></div>
			<div class="skeleton" style="width:50%;height:16px"></div>
		{:else if result}
			<div in:softFly class="stack center">
				{#if result.status === 'pending'}
					<span class="big-icon wait"><Icon name="clock" size={28} /></span>
					<h1>{t('invite.pendingTitle')}</h1>
					<p class="muted">{t('invite.pendingBody', { owner: preview.owner_name ?? preview.invited_by_name })}</p>
					<a class="btn btn-primary" href="/frames">{t('add.backToFrames')}</a>
				{:else}
					<span class="big-icon ok"><Icon name="check" size={28} /></span>
					<h1>{t('add.joinedTitle', { name: result.frame.name })}</h1>
					<p class="muted">{t('invite.joinedBody')}</p>
					<div class="row-wrap center-row">
						<a class="btn btn-primary btn-lg" href="/send?frame={result.frame.id}"><Icon name="send" size={18} />{t('add.sendFirst')}</a>
						<a class="btn" href="/frames/{result.frame.id}">{t('add.viewFrame')}</a>
					</div>
				{/if}
			</div>
		{:else}
			<div class="art"><Illustration kind="people" /></div>
			<p class="eyebrow">{t('invite.from', { name: preview.invited_by_name })}</p>
			<h1>{t('invite.join', { name: preview.frame_name })}</h1>
			{#if preview.frame_location}<p class="muted loc"><Icon name="mapPin" size={16} />{preview.frame_location}</p>{/if}
			{#if preview.already_member}
				<p class="callout"><Icon name="info" size={18} />{t('invite.already')}</p>
				<a class="btn btn-primary" href="/frames">{t('add.backToFrames')}</a>
			{:else}
				<p class="muted">{preview.requires_approval ? t('invite.needsApproval', { owner: preview.owner_name ?? '' }) : t('invite.instant')}</p>
				{#if acceptError}<p class="error-text" role="alert">{acceptError}</p>{/if}
				<button class="btn btn-primary btn-lg" onclick={accept} disabled={accepting}>
					{#if accepting}<span class="spinner" aria-hidden="true"></span>{/if}{t('invite.accept')}
				</button>
				<a class="btn btn-ghost" href="/frames">{t('invite.notNow')}</a>
			{/if}
		{/if}
	</section>
</div>

<style>
	.narrow {
		max-width: 520px;
	}
	.panel,
	.center {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		gap: var(--s-3);
	}
	.center-row {
		justify-content: center;
	}
	.art {
		width: 170px;
	}
	.eyebrow {
		font-size: 0.85rem;
		font-weight: 650;
		color: var(--primary-soft-text);
		background: var(--primary-soft);
		padding: 4px 12px;
		border-radius: var(--r-full);
	}
	.loc {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.big-icon {
		width: 60px;
		height: 60px;
		border-radius: 50%;
		display: grid;
		place-items: center;
	}
	.ok {
		background: var(--accent-soft);
		color: var(--accent-text);
	}
	.wait {
		background: var(--warn-soft);
		color: var(--warn);
	}
	.bad {
		background: var(--danger-soft);
		color: var(--danger);
	}
</style>
