<script lang="ts">
	import { onMount, tick } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, call, type RedeemResult } from '$lib/api/client';
	import { frames } from '$lib/frames.svelte';
	import { isCompleteCode, normalizeCode } from '$lib/format';
	import { newIdempotencyKey } from '$lib/logic';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import CodeInput from '$components/CodeInput.svelte';
	import Icon from '$components/Icon.svelte';
	import Illustration from '$components/Illustration.svelte';

	let code = $state('');
	let busy = $state(false);
	let error = $state<string | null>(null);
	let result = $state<RedeemResult | null>(null);
	let input: CodeInput | undefined = $state();
	let idemKey = newIdempotencyKey();

	// Naming step after a claim.
	let name = $state('');
	let location = $state('');
	let saving = $state(false);

	onMount(() => {
		const prefill = normalizeCode(page.url.searchParams.get('code') ?? '');
		if (prefill) {
			code = prefill;
			if (isCompleteCode(prefill)) void redeem();
		} else {
			input?.focus();
		}
	});

	async function redeem() {
		if (!isCompleteCode(code) || busy) return;
		busy = true;
		error = null;
		try {
			result = await call(
				api.POST('/v1/frames/redeem', {
					body: { code: normalizeCode(code) },
					params: { header: { 'Idempotency-Key': idemKey } }
				})
			);
			if (result.status !== 'pending') frames.upsert(result.frame);
			name = result.frame.name ?? '';
			location = result.frame.location ?? '';
		} catch (e) {
			error = problemMessage(e);
			idemKey = newIdempotencyKey();
		} finally {
			busy = false;
		}
		if (error) {
			// The input was disabled while checking; give focus back so the code can be fixed.
			await tick();
			input?.focus();
		}
	}

	async function saveName(e: SubmitEvent) {
		e.preventDefault();
		if (!result) return;
		saving = true;
		try {
			const updated = await call(
				api.PATCH('/v1/frames/{frameId}', {
					params: { path: { frameId: result.frame.id } },
					body: { name: name.trim(), location: location.trim() }
				})
			);
			frames.upsert(updated);
			toasts.success(t('add.named', { name: updated.name }));
			await goto(`/send?frame=${updated.id}`);
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			saving = false;
		}
	}

	function reset() {
		result = null;
		code = '';
		error = null;
		idemKey = newIdempotencyKey();
		void tick().then(() => input?.focus());
	}
</script>

<svelte:head><title>{t('add.title')} · kinframe</title></svelte:head>

<div class="container page narrow">
	<a href="/frames" class="back"><Icon name="chevronLeft" size={18} />{t('frames.title')}</a>

	{#if !result}
		<section class="card card-pad panel" in:softFly>
			<div class="art"><Illustration kind="frame" /></div>
			<h1>{t('add.title')}</h1>
			<p class="muted">{t('add.body')}</p>
			<form
				class="code-form"
				onsubmit={(e) => {
					e.preventDefault();
					void redeem();
				}}
			>
				<label for="frame-code" class="visually-hidden">{t('add.codeLabel')}</label>
				<CodeInput
					bind:this={input}
					bind:value={code}
					invalid={Boolean(error)}
					describedby="code-help"
					oncomplete={() => void redeem()}
					disabled={busy}
				/>
				{#if error}
					<p class="error-text" role="alert" id="code-help">{error}</p>
				{:else}
					<p class="hint" id="code-help">{t('add.codeHint')}</p>
				{/if}
				<button class="btn btn-primary btn-lg" type="submit" disabled={!isCompleteCode(code) || busy}>
					{#if busy}<span class="spinner" aria-hidden="true"></span>{t('add.checking')}{:else}{t('add.submit')}{/if}
				</button>
			</form>
		</section>
	{:else}
		<section class="card card-pad panel result" in:softFly role="status">
			{#if result.status === 'claimed'}
				<span class="big-icon ok"><Icon name="sparkle" size={30} /></span>
				<h1>{t('add.claimedTitle')}</h1>
				<p class="muted">{t('add.claimedBody')}</p>
				<form class="stack name-form" onsubmit={saveName}>
					<div class="field">
						<label for="frame-name">{t('frame.name')}</label>
						<input id="frame-name" class="input" required maxlength="40" bind:value={name} placeholder={t('add.namePlaceholder')} />
					</div>
					<div class="field">
						<label for="frame-location">{t('frame.location')} <span class="faint">({t('common.optional')})</span></label>
						<input id="frame-location" class="input" maxlength="40" bind:value={location} placeholder={t('add.locationPlaceholder')} />
					</div>
					<button class="btn btn-primary btn-lg" type="submit" disabled={saving || !name.trim()}>
						{#if saving}<span class="spinner" aria-hidden="true"></span>{/if}
						{t('add.saveAndSend')}
					</button>
					<a href="/frames/{result.frame.id}" class="btn btn-ghost">{t('add.later')}</a>
				</form>
			{:else if result.status === 'joined'}
				<span class="big-icon ok"><Icon name="check" size={30} /></span>
				<h1>{t('add.joinedTitle', { name: result.frame.name })}</h1>
				<p class="muted">{t('add.joinedBody', { owner: result.frame.owner_name ?? '' })}</p>
				<div class="row-wrap center">
					<a class="btn btn-primary btn-lg" href="/send?frame={result.frame.id}"><Icon name="send" size={18} />{t('add.sendFirst')}</a>
					<a class="btn" href="/frames/{result.frame.id}">{t('add.viewFrame')}</a>
				</div>
			{:else if result.status === 'already_member'}
				<span class="big-icon"><Icon name="info" size={30} /></span>
				<h1>{t('add.alreadyTitle')}</h1>
				<p class="muted">{t('add.alreadyBody', { name: result.frame.name })}</p>
				<div class="row-wrap center">
					<a class="btn btn-primary" href="/frames/{result.frame.id}">{t('add.viewFrame')}</a>
					<button class="btn" onclick={reset}>{t('add.another')}</button>
				</div>
			{:else}
				<span class="big-icon wait"><Icon name="clock" size={30} /></span>
				<h1>{t('add.pendingTitle')}</h1>
				<p class="muted">{t('add.pendingBody', { name: result.frame.name })}</p>
				<div class="row-wrap center">
					<a class="btn btn-primary" href="/frames">{t('add.backToFrames')}</a>
				</div>
			{/if}
		</section>
	{/if}
</div>

<style>
	.narrow {
		max-width: 560px;
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
	.back:hover {
		color: var(--text);
	}
	.panel {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		gap: var(--s-3);
	}
	.art {
		width: 160px;
	}
	.panel > p {
		max-width: 40ch;
	}
	.code-form {
		width: 100%;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--s-3);
		margin-top: var(--s-3);
	}
	.code-form .btn {
		width: 100%;
		max-width: 340px;
	}
	.big-icon {
		width: 64px;
		height: 64px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--surface-2);
		color: var(--text-muted);
	}
	.big-icon.ok {
		background: var(--accent-soft);
		color: var(--accent-text);
	}
	.big-icon.wait {
		background: var(--warn-soft);
		color: var(--warn);
	}
	.name-form {
		width: 100%;
		text-align: left;
		margin-top: var(--s-3);
	}
	.center {
		justify-content: center;
		margin-top: var(--s-2);
	}
</style>
