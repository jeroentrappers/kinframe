<script lang="ts">
	import { page } from '$app/state';
	import { api, call, type FeedInvitePreview, type FeedInviteResult } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { HISTORY_DEFAULT_N, historyBody, type HistoryChoice } from '$lib/feeds';
	import { feeds } from '$lib/feeds.svelte';
	import { frames } from '$lib/frames.svelte';
	import { newIdempotencyKey } from '$lib/logic';
	import { problemMessage } from '$lib/problem';
	import { t, tn } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Icon from '$components/Icon.svelte';
	import FeedCover from '$components/feed/FeedCover.svelte';
	import BirthdaysOption from '$components/feed/BirthdaysOption.svelte';
	import HistoryPicker from '$components/feed/HistoryPicker.svelte';
	import SubscriptionStatusBadge from '$components/feed/SubscriptionStatusBadge.svelte';

	// Feed invite link https://<web>/fi/<token> (README §5.12): join a feed as poster, or subscribe frames you manage.
	const token = $derived(page.params.token!);
	let preview = $state<FeedInvitePreview | null>(null);
	let error = $state<unknown>(null);
	let result = $state<FeedInviteResult | null>(null);
	let accepting = $state(false);
	let acceptError = $state<string | null>(null);
	let chosen = $state<string[]>([]);
	let history = $state<HistoryChoice>({ history: 'none', count: HISTORY_DEFAULT_N });
	let birthdays = $state(true);
	let key = newIdempotencyKey();

	$effect(() => {
		const tk = token;
		preview = null;
		error = null;
		result = null;
		call(api.GET('/v1/feed-invites/{token}', { params: { path: { token: tk } } }))
			.then((p) => {
				preview = p;
				const open = (p.frames ?? []).filter((f) => !f.subscription_status);
				chosen = open.length === 1 ? [open[0]!.frame_id] : [];
			})
			.catch((e) => (error = e));
	});

	const subscribe = $derived(preview?.purpose === 'subscribe');
	const available = $derived((preview?.frames ?? []).filter((f) => !f.subscription_status));

	function toggle(id: string) {
		chosen = chosen.includes(id) ? chosen.filter((x) => x !== id) : [...chosen, id];
	}

	async function accept() {
		if (subscribe && chosen.length === 0) return;
		accepting = true;
		acceptError = null;
		try {
			result = await call(
				api.POST('/v1/feed-invites/{token}/accept', {
					params: { path: { token }, header: { 'Idempotency-Key': key } },
					body: subscribe ? { frame_ids: chosen, ...historyBody(history), ...(discovery.birthdays ? { birthdays } : {}) } : {}
				})
			);
			if (result.status === 'joined' || result.status === 'already_member') feeds.upsert(result.feed);
			if (result.status === 'subscribed') void frames.load(true);
		} catch (e) {
			acceptError = problemMessage(e);
			key = newIdempotencyKey();
		} finally {
			accepting = false;
		}
	}
</script>

<svelte:head><title>{t('fi.title')} · kinframe</title></svelte:head>

<div class="container page narrow">
	<section class="card card-pad panel">
		{#if error}
			<span class="big-icon bad"><Icon name="link" size={28} /></span>
			<h1>{t('invite.invalidTitle')}</h1>
			<p class="muted">{problemMessage(error)}</p>
			<a class="btn btn-primary" href="/feeds">{t('fi.toFeeds')}</a>
		{:else if !preview}
			<div class="skeleton" style="width:96px;height:96px"></div>
			<div class="skeleton" style="width:70%;height:28px"></div>
			<div class="skeleton" style="width:50%;height:16px"></div>
		{:else if result}
			<div in:softFly class="stack center">
				{#if result.status === 'pending'}
					<span class="big-icon wait"><Icon name="clock" size={28} /></span>
					<h1>{t('invite.pendingTitle')}</h1>
					<p class="muted">{t('fi.pendingBody', { feed: preview.feed_name })}</p>
					<a class="btn btn-primary" href="/feeds">{t('fi.toFeeds')}</a>
				{:else if result.status === 'subscribed'}
					<span class="big-icon ok"><Icon name="check" size={28} /></span>
					<h1>{t('fi.subscribedTitle', { feed: preview.feed_name })}</h1>
					<ul class="subs" role="list">
						{#each result.subscriptions ?? [] as s (s.id)}
							<li><a href="/frames/{s.frame.id}?tab=feeds">{s.frame.name}</a> <SubscriptionStatusBadge status={s.status} /></li>
						{/each}
					</ul>
					<p class="muted">{t('fi.subscribedBody')}</p>
					<a class="btn btn-primary" href="/frames">{t('add.backToFrames')}</a>
				{:else}
					<span class="big-icon ok"><Icon name="check" size={28} /></span>
					<h1>{t('fi.joinedTitle', { feed: result.feed.name })}</h1>
					<p class="muted">{t('fi.joinedBody')}</p>
					<div class="row-wrap center-row">
						<a class="btn btn-primary btn-lg" href="/send?feed={result.feed.id}"><Icon name="send" size={18} />{t('fi.postFirst')}</a>
						<a class="btn" href="/feeds/{result.feed.id}">{t('fi.viewFeed')}</a>
					</div>
				{/if}
			</div>
		{:else}
			<FeedCover name={preview.feed_name} url={preview.cover_url} size={96} />
			<p class="eyebrow">{t('invite.from', { name: preview.invited_by_name })}</p>
			<h1>{subscribe ? t('fi.subscribeHeading', { feed: preview.feed_name }) : t('fi.joinHeading', { feed: preview.feed_name })}</h1>
			<p class="muted small">
				{#if preview.owner_name}{t('feed.ownedBy', { name: preview.owner_name })}{/if}{#if preview.member_count != null} · {tn('feeds.members', preview.member_count)}{/if}
			</p>
			{#if !subscribe && preview.already_member}
				<p class="callout"><Icon name="info" size={18} />{t('fi.already')}</p>
				<a class="btn btn-primary" href="/feeds">{t('fi.toFeeds')}</a>
			{:else if subscribe}
				<p class="muted">{t('fi.subscribeBody')}</p>
				{#if (preview.frames ?? []).length === 0}
					<p class="callout small"><Icon name="info" size={18} />{t('fi.noFrames')}</p>
					<a class="btn btn-primary" href="/frames/add">{t('frames.add')}</a>
				{:else}
					<fieldset class="frames">
						<legend class="label">{t('fi.pickFrames')}</legend>
						{#each preview.frames ?? [] as f (f.frame_id)}
							<label class="fr" class:checked={chosen.includes(f.frame_id)} class:disabled={!!f.subscription_status}>
								<input type="checkbox" checked={chosen.includes(f.frame_id)} disabled={!!f.subscription_status} onchange={() => toggle(f.frame_id)} />
								<span class="fr-name">{f.frame_name}</span>
								{#if f.subscription_status}<SubscriptionStatusBadge status={f.subscription_status} />{/if}
							</label>
						{/each}
					</fieldset>
					{#if available.length > 0}
						<div class="left"><HistoryPicker bind:value={history} /></div>
						<div class="left"><BirthdaysOption bind:value={birthdays} /></div>
						<p class="hint privacy"><Icon name="shield" size={15} />{t('subs.privacy', { feed: preview.feed_name })}</p>
					{/if}
					{#if acceptError}<p class="error-text" role="alert">{acceptError}</p>{/if}
					<button class="btn btn-primary btn-lg" onclick={accept} disabled={accepting || chosen.length === 0}>
						{#if accepting}<span class="spinner" aria-hidden="true"></span>{/if}{tn('fi.subscribeN', Math.max(1, chosen.length))}
					</button>
				{/if}
				<a class="btn btn-ghost" href="/frames">{t('invite.notNow')}</a>
			{:else}
				<p class="muted">{preview.requires_approval ? t('fi.needsApproval') : t('fi.instant')}</p>
				<p class="hint">{t('fi.joinExplain')}</p>
				{#if acceptError}<p class="error-text" role="alert">{acceptError}</p>{/if}
				<button class="btn btn-primary btn-lg" onclick={accept} disabled={accepting}>
					{#if accepting}<span class="spinner" aria-hidden="true"></span>{/if}{t('fi.join')}
				</button>
				<a class="btn btn-ghost" href="/feeds">{t('invite.notNow')}</a>
			{/if}
		{/if}
	</section>
</div>

<style>
	.narrow {
		max-width: 560px;
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
	.eyebrow {
		font-size: 0.85rem;
		font-weight: 650;
		color: var(--primary-soft-text);
		background: var(--primary-soft);
		padding: 4px 12px;
		border-radius: var(--r-full);
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
	.frames {
		border: 0;
		margin: 0;
		padding: 0;
		width: 100%;
		display: flex;
		flex-direction: column;
		gap: 6px;
		text-align: start;
	}
	.fr {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 10px 12px;
		border: 1.5px solid var(--border);
		border-radius: var(--r);
		cursor: pointer;
	}
	.fr.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 45%, var(--surface));
	}
	.fr.disabled {
		opacity: 0.7;
		cursor: default;
	}
	.fr-name {
		flex: 1;
		min-width: 0;
		font-weight: 600;
	}
	.left {
		width: 100%;
		text-align: start;
	}
	.privacy {
		display: flex;
		gap: 6px;
		align-items: flex-start;
		text-align: start;
	}
	.subs {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
</style>
