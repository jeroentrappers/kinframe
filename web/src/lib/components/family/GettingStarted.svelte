<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, call, type Feed, type Schemas } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { feedInviteToken, parseEnteredCode } from '$lib/codes';
	import { discovery } from '$lib/discovery.svelte';
	import { startFlow, surnameOf } from '$lib/family.svelte';
	import { feeds } from '$lib/feeds.svelte';
	import { frames } from '$lib/frames.svelte';
	import { newIdempotencyKey } from '$lib/logic';
	import { isCode, problemMessage } from '$lib/problem';
	import { t } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Icon from '../Icon.svelte';
	import Illustration from '../Illustration.svelte';
	import Logo from '../Logo.svelte';
	import InviteSheet from './InviteSheet.svelte';

	/** Getting started (simple-app.md §8): start a family, or join one with a link or code. */
	const step = $derived(startFlow.step ?? 'choose');
	const created = $derived(startFlow.feedId ? feeds.get(startFlow.feedId) : undefined);
	// Someone already in a family, or on a frame (spec §8), can go back to the app.
	const canCancel = $derived(feeds.list.length > 0 || frames.list.length > 0);

	// ---- Start a family: the name, prefilled "<surname> family" when the account name has one.
	let name = $state('');
	let busy = $state(false);
	let error = $state<string | null>(null);
	let key = newIdempotencyKey();
	function startNaming() {
		const surname = surnameOf(auth.me?.display_name);
		name = surname ? t('start.namePrefill', { surname }) : '';
		error = null;
		key = newIdempotencyKey();
		startFlow.step = 'name';
	}
	$effect(() => {
		if (startFlow.step === 'name' && !name) {
			const surname = surnameOf(auth.me?.display_name);
			if (surname) name = t('start.namePrefill', { surname });
		}
	});

	async function create(e: SubmitEvent) {
		e.preventDefault();
		const n = name.trim();
		if (!n || busy) return;
		busy = true;
		error = null;
		try {
			const feed = (await call(api.POST('/v1/feeds', { params: { header: { 'Idempotency-Key': key } }, body: { name: n } as Schemas['FeedCreate'] }))) as Feed;
			feeds.upsert(feed);
			startFlow.feedId = feed.id;
			startFlow.step = 'frame';
		} catch (err) {
			error = isCode(err, 'limit_reached') ? t('feeds.limitReached', { max: discovery.limits.max_feeds_per_user }) : problemMessage(err);
			key = newIdempotencyKey();
		} finally {
			busy = false;
		}
	}

	function pairFrame() {
		const id = startFlow.feedId;
		startFlow.end();
		void goto(`/frames/add?family=${id}&then=invite`);
	}

	let inviteOpen = $state(false);
	$effect(() => {
		if (step === 'invite' && created) inviteOpen = true;
	});
	function finish() {
		startFlow.end();
		void goto('/home');
	}

	// ---- Join my family: an invite link (…/fi/<token>, …/i/<token>) or a family code.
	let joinText = $state('');
	let joinBusy = $state(false);
	let joinError = $state<string | null>(null);
	async function join(e: SubmitEvent) {
		e.preventDefault();
		const raw = joinText.trim();
		if (!raw) return;
		joinError = null;
		const token = feedInviteToken(raw);
		if (token) return leaveTo(`/fi/${encodeURIComponent(token)}`);
		const frameInvite = raw.match(/(?:^|\/)i\/([A-Za-z0-9_-]{16,64})(?:[/?#].*)?$/);
		if (frameInvite) return leaveTo(`/i/${encodeURIComponent(frameInvite[1]!)}`);
		const code = parseEnteredCode(raw, true);
		if (code.kind !== 'feed' || !code.complete) {
			joinError = t('start.joinInvalid');
			return;
		}
		joinBusy = true;
		try {
			const preview = await call(api.POST('/v1/feed-invites:lookup', { body: { code: code.code } }));
			leaveTo(`/fi/${encodeURIComponent(preview.token)}`);
		} catch (err) {
			joinError = problemMessage(err);
		} finally {
			joinBusy = false;
		}
	}
	function leaveTo(path: string) {
		startFlow.end();
		void goto(path);
	}
</script>

<svelte:head><title>{t('start.title')} · kinframe</title></svelte:head>

<div class="container page narrow start">
	<div class="brand"><Logo size={36} /></div>
	{#if step === 'choose'}
		<section class="stack" in:softFly>
			<div class="art"><Illustration kind="people" /></div>
			<h1>{t('start.title')}</h1>
			<p class="lede">{t('start.lede')}</p>
			<div class="choices">
				<button class="card choice" onclick={startNaming}>
					<span class="ci"><Icon name="plus" size={24} /></span>
					<span class="cb"><span class="ct">{t('start.start')}</span><span class="muted small">{t('start.startBody')}</span></span>
					<Icon name="chevronRight" size={20} />
				</button>
				<button class="card choice" onclick={() => ((joinError = null), (startFlow.step = 'join'))}>
					<span class="ci"><Icon name="link" size={24} /></span>
					<span class="cb"><span class="ct">{t('start.join')}</span><span class="muted small">{t('start.joinBody')}</span></span>
					<Icon name="chevronRight" size={20} />
				</button>
			</div>
			{#if canCancel}<button class="btn btn-ghost" onclick={finish}>{t('common.cancel')}</button>{/if}
			<a class="btn btn-ghost btn-sm settings-link" href="/settings" onclick={() => startFlow.end()}><Icon name="settings" size={16} />{t('nav.settings')}</a>
		</section>
	{:else if step === 'name'}
		<form class="card card-pad stack" onsubmit={create} in:softFly>
			<h1>{t('start.start')}</h1>
			<div class="field">
				<label for="start-name">{t('start.nameLabel')}</label>
				<!-- svelte-ignore a11y_autofocus -->
				<input id="start-name" class="input" required maxlength="40" bind:value={name} placeholder={t('start.namePlaceholder')} autofocus />
			</div>
			{#if error}<p class="error-text" role="alert">{error}</p>{/if}
			<div class="row-wrap">
				<button class="btn btn-ghost" type="button" onclick={() => (canCancel ? finish() : (startFlow.step = 'choose'))}>{t('common.cancel')}</button>
				<button class="btn btn-primary" type="submit" disabled={!name.trim() || busy}>
					{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{t('start.create')}
				</button>
			</div>
		</form>
	{:else if step === 'frame'}
		<section class="card card-pad stack" in:softFly>
			<div class="art"><Illustration kind="frame" /></div>
			<h1>{t('start.frameTitle')}</h1>
			<p class="muted">{t('start.frameBody', { family: created?.name ?? '' })}</p>
			<div class="row-wrap">
				<button class="btn btn-primary btn-lg" onclick={pairFrame}><Icon name="frame" size={20} />{t('start.framePair')}</button>
				<button class="btn btn-lg" onclick={() => (startFlow.step = 'invite')}>{t('start.frameLater')}</button>
			</div>
		</section>
	{:else if step === 'invite'}
		<section class="card card-pad stack" in:softFly>
			<h1>{t('start.inviteTitle', { family: created?.name ?? '' })}</h1>
			<p class="muted">{t('start.inviteBody')}</p>
			<div class="row-wrap">
				<button class="btn btn-primary" onclick={() => (inviteOpen = true)}><Icon name="share" size={18} />{t('family.invite')}</button>
				<button class="btn" onclick={finish}>{t('common.done')}</button>
			</div>
		</section>
	{:else if step === 'join'}
		<form class="card card-pad stack" onsubmit={join} in:softFly>
			<h1>{t('start.join')}</h1>
			<p class="muted">{t('start.joinHelp')}</p>
			<div class="field">
				<label for="join-text">{t('start.joinLabel')}</label>
				<!-- svelte-ignore a11y_autofocus -->
				<input id="join-text" class="input" required bind:value={joinText} autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder={t('start.joinPlaceholder')} autofocus />
			</div>
			{#if joinError}<p class="error-text" role="alert">{joinError}</p>{/if}
			<div class="row-wrap">
				<button class="btn btn-ghost" type="button" onclick={() => (canCancel ? finish() : (startFlow.step = 'choose'))}>{t('common.cancel')}</button>
				<button class="btn btn-primary" type="submit" disabled={!joinText.trim() || joinBusy}>
					{#if joinBusy}<span class="spinner" aria-hidden="true"></span>{/if}{t('start.joinGo')}
				</button>
			</div>
		</form>
	{/if}
</div>

{#if created}<InviteSheet bind:open={inviteOpen} feed={created} />{/if}

<style>
	.start {
		padding-top: var(--s-6);
	}
	.brand {
		display: flex;
		justify-content: center;
		margin-bottom: var(--s-5);
	}
	.art {
		max-width: 220px;
		margin-inline: auto;
	}
	h1 {
		text-align: center;
	}
	.lede {
		text-align: center;
	}
	form h1,
	.card h1 {
		text-align: start;
	}
	.choices {
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
	}
	.choice {
		display: flex;
		align-items: center;
		gap: var(--s-4);
		padding: var(--s-4);
		border: 1px solid var(--border);
		color: inherit;
		font: inherit;
		text-align: start;
		cursor: pointer;
	}
	.choice:hover {
		border-color: var(--primary);
		box-shadow: var(--shadow);
	}
	.ci {
		display: grid;
		place-items: center;
		width: 52px;
		height: 52px;
		border-radius: 50%;
		background: var(--primary-soft);
		color: var(--primary-soft-text);
		flex: none;
	}
	.cb {
		flex: 1;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.ct {
		font-weight: 700;
		font-size: 1.1rem;
	}
	.settings-link {
		align-self: center;
	}
</style>
