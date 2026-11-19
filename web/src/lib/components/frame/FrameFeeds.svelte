<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, call, type Frame, type Subscription } from '$lib/api/client';
	import { feedInviteToken, formatFeedCode, parseEnteredCode } from '$lib/codes';
	import { backfillProgress, groupSubscriptions, historyBody, historyOf, sameHistory, type HistoryChoice } from '$lib/feeds';
	import { feeds } from '$lib/feeds.svelte';
	import { can } from '$lib/frames.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { getFrameSettings, patchFrameSettings } from '$lib/frameSettings';
	import { formatBytes, relativeTime } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import FeedCover from '../feed/FeedCover.svelte';
	import HistoryPicker from '../feed/HistoryPicker.svelte';
	import SubscribeDialog from '../feed/SubscribeDialog.svelte';
	import SubscriptionStatusBadge from '../feed/SubscriptionStatusBadge.svelte';

	/** The feeds a frame subscribes to (feeds.md §3 story 8): managers pause, resume, change history, unsubscribe. */
	let { frame }: { frame: Frame } = $props();

	const manage = $derived(can(frame, 'change_settings'));
	let subs = $state<Subscription[] | null>(null);
	let error = $state<unknown>(null);
	let showEnded = $state(false);
	let playingFeed = $state<string | null>(null);
	let settingsVersion = $state<number | undefined>(undefined);

	async function load() {
		try {
			subs = await call(api.GET('/v1/frames/{frameId}/subscriptions', { params: { path: { frameId: frame.id } } }));
			error = null;
		} catch (e) {
			error = e;
		}
	}

	async function loadPlaying() {
		if (!manage) return;
		try {
			const s = await getFrameSettings(frame.id);
			playingFeed = s.slideshow?.feed_id ?? null;
			settingsVersion = s.version;
		} catch {
			/* not essential */
		}
	}

	$effect(() => {
		void frame.id;
		void load();
		void loadPlaying();
		void feeds.load();
		return live.on((e) => {
			if (e.type === 'subscription.updated' && e.frame_id === frame.id) {
				void load();
				if (e.subscription.status === 'removed') void loadPlaying();
			}
		});
	});

	const groups = $derived(groupSubscriptions(subs ?? [], 'feed'));
	const taken = $derived((subs ?? []).filter((s) => s.status === 'pending' || s.status === 'active' || s.status === 'paused').map((s) => s.feed.id));

	function replace(s: Subscription) {
		subs = (subs ?? []).some((x) => x.id === s.id) ? (subs ?? []).map((x) => (x.id === s.id ? s : x)) : [s, ...(subs ?? [])];
	}

	let busy = $state<string | null>(null);
	async function pauseResume(s: Subscription) {
		busy = s.id;
		try {
			const res =
				s.status === 'paused'
					? await call(api.POST('/v1/subscriptions/{subscriptionId}/resume', { params: { path: { subscriptionId: s.id } } }))
					: await call(api.POST('/v1/subscriptions/{subscriptionId}/pause', { params: { path: { subscriptionId: s.id } } }));
			replace(res);
			toasts.success(t(res.status === 'paused' ? 'frameFeeds.pausedToast' : 'frameFeeds.resumedToast', { feed: s.feed.name }));
		} catch (e) {
			toasts.error(problemMessage(e));
			void load();
		} finally {
			busy = null;
		}
	}

	// ---- change history
	let editing = $state<Subscription | null>(null);
	let editOpen = $state(false);
	let editChoice = $state<HistoryChoice>({ history: 'none', count: 50 });
	let saving = $state(false);
	function startEdit(s: Subscription) {
		editing = s;
		editChoice = historyOf(s);
		editOpen = true;
	}
	async function saveHistory() {
		const s = editing;
		if (!s) return;
		saving = true;
		try {
			const res = await call(
				api.PATCH('/v1/subscriptions/{subscriptionId}', { params: { path: { subscriptionId: s.id } }, body: historyBody(editChoice) })
			);
			replace(res);
			editOpen = false;
			toasts.success(t('frameFeeds.historySaved'));
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			saving = false;
		}
	}

	// ---- birthdays per subscription (birthdays.md §3, frame owner story 1)
	let bdBusy = $state<string | null>(null);
	async function toggleBirthdays(s: Subscription, on: boolean) {
		bdBusy = s.id;
		try {
			const res = await call(api.PATCH('/v1/subscriptions/{subscriptionId}', { params: { path: { subscriptionId: s.id } }, body: { birthdays: on } }));
			replace(res);
			toasts.success(t(on ? 'frameFeeds.birthdaysOn' : 'frameFeeds.birthdaysOff', { feed: s.feed.name }));
		} catch (e) {
			toasts.error(problemMessage(e));
			void load();
		} finally {
			bdBusy = null;
		}
	}

	// ---- unsubscribe / withdraw
	let removing = $state<Subscription | null>(null);
	let removeOpen = $state(false);
	async function unsubscribe() {
		const s = removing;
		if (!s) return;
		removeOpen = false;
		try {
			await call(api.DELETE('/v1/subscriptions/{subscriptionId}', { params: { path: { subscriptionId: s.id } } }));
			toasts.success(t(s.status === 'pending' ? 'frameFeeds.withdrawn' : 'frameFeeds.unsubscribed', { feed: s.feed.name }));
			if (playingFeed === s.feed.id) playingFeed = null;
			void load();
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	// ---- feed mode: play only this feed (slideshow.feed_id, clears album mode)
	let playBusy = $state(false);
	async function togglePlay(s: Subscription) {
		const next = playingFeed === s.feed.id ? null : s.feed.id;
		playBusy = true;
		try {
			const res = await patchFrameSettings(frame.id, { slideshow: next ? { feed_id: next, album_id: null } : { feed_id: null } }, settingsVersion);
			playingFeed = res.slideshow?.feed_id ?? null;
			settingsVersion = res.version;
			toasts.success(next ? t('albums.nowPlaying', { name: s.feed.name, frame: frame.name }) : t('albums.playingAll', { frame: frame.name }));
		} catch (e) {
			toasts.error(problemMessage(e));
		} finally {
			playBusy = false;
		}
	}

	// ---- subscribe
	let subscribeOpen = $state(false);
	let codeOpen = $state(false);
	let codeText = $state('');
	let codeBusy = $state(false);
	let codeError = $state<string | null>(null);
	async function useInvite(e: SubmitEvent) {
		e.preventDefault();
		codeError = null;
		const token = feedInviteToken(codeText);
		if (token) return void goto(`/fi/${encodeURIComponent(token)}`);
		const p = parseEnteredCode(codeText);
		if (p.kind !== 'feed' || !p.complete) {
			codeError = t('frameFeeds.codeInvalid');
			return;
		}
		codeBusy = true;
		try {
			const preview = await call(api.POST('/v1/feed-invites:lookup', { body: { code: p.code } }));
			await goto(`/fi/${encodeURIComponent(preview.token)}`);
		} catch (err) {
			codeError = problemMessage(err);
		} finally {
			codeBusy = false;
		}
	}

	const historyText = (s: Subscription) => t(`subs.historyShort.${s.history}`, { n: s.history_count ?? 0 });
</script>

{#if manage}
	<div class="toolbar">
		{#if feeds.list.length > 0}
			<button class="btn btn-primary" onclick={() => (subscribeOpen = true)}><Icon name="feed" size={18} />{t('frameFeeds.subscribe')}</button>
		{/if}
		<button class="btn" onclick={() => ((codeText = ''), (codeError = null), (codeOpen = true))}><Icon name="link" size={18} />{t('frameFeeds.useInvite')}</button>
	</div>
{/if}

{#if error}
	<div class="callout callout-error" role="alert"><Icon name="alert" />{problemMessage(error)}</div>
{:else if !subs}
	<div class="stack-sm">{#each [0, 1] as i (i)}<div class="skeleton" style="height:72px"></div>{/each}</div>
{:else if groups.pending.length + groups.live.length === 0 && !(manage && groups.ended.length)}
	<div class="card card-pad empty">
		<Icon name="feed" size={28} />
		<h2>{t('frameFeeds.emptyTitle')}</h2>
		<p class="muted">{manage ? t('frameFeeds.emptyBody') : t('frameFeeds.emptyBodyMember')}</p>
	</div>
{:else}
	<ul class="subs" role="list">
		{#each [...groups.pending, ...groups.live] as s (s.id)}
			{@const progress = backfillProgress(s)}
			<li class="card sub" in:softFly>
				<div class="s-head">
					<FeedCover name={s.feed.name} url={s.feed.cover_url} size={48} />
					<div class="who">
						<strong>{s.feed.name}</strong>
						<span class="muted small">
							{#if s.feed.admin_names?.length}{t('frameFeeds.by', { names: new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format(s.feed.admin_names) })}{/if}{#if s.feed.member_count != null} · {tn('feeds.members', s.feed.member_count)}{/if}
						</span>
					</div>
					<SubscriptionStatusBadge status={s.status} />
				</div>
				<p class="small muted meta">
					{historyText(s)}{#if s.status === 'pending'} · {t('frameFeeds.waitingApproval')}{/if}{#if s.status === 'paused' && s.paused_at} · {t('frameFeeds.pausedSince', { when: relativeTime(s.paused_at, i18n.tag) })}{/if}{#if s.estimate?.items} · {tn('frameFeeds.estimate', s.estimate.items, { size: formatBytes(s.estimate.bytes ?? 0, i18n.tag) })}{/if}
				</p>
				{#if s.status === 'paused'}<p class="hint">{t('frameFeeds.pausedHint')}</p>{/if}
				{#if discovery.birthdays && s.status !== 'pending'}
					<div class="bd">
						{#if s.can_manage}
							<label class="switch">
								<input type="checkbox" checked={s.birthdays ?? true} disabled={bdBusy === s.id} onchange={(e) => toggleBirthdays(s, e.currentTarget.checked)} />
								<span class="s-label">{t('frameFeeds.showBirthdays')}{#if s.birthday_count != null} <span class="muted small">· {tn('frameFeeds.birthdayCount', s.birthday_count)}</span>{/if}</span>
							</label>
						{:else if s.birthday_count}
							<span class="small muted"><Icon name="cake" size={14} /> {tn('frameFeeds.birthdayCount', s.birthday_count)}</span>
						{/if}
						{#if (s.birthdays ?? true) && s.frame.birthdays_supported === false}
							<p class="hint"><Icon name="info" size={14} /> {t('frameFeeds.birthdaysUnsupported')}</p>
						{/if}
					</div>
				{/if}
				{#if progress !== null}
					<div class="stack-sm" role="status">
						<span class="small">{t('frameFeeds.backfilling', { placed: s.backfill?.placed ?? 0, total: s.backfill?.total ?? 0 })}</span>
						<div class="progress" aria-hidden="true"><span style="width:{Math.round(progress * 100)}%"></span></div>
					</div>
				{/if}
				{#if s.can_manage}
					<div class="row-wrap actions">
						{#if s.status === 'active' || s.status === 'paused'}
							<button class="btn btn-sm" onclick={() => pauseResume(s)} disabled={busy === s.id}>
								<Icon name={s.status === 'paused' ? 'play' : 'pause'} size={16} />{s.status === 'paused' ? t('frameFeeds.resume') : t('frameFeeds.pause')}
							</button>
							<button class="btn btn-sm" class:btn-soft={playingFeed === s.feed.id} onclick={() => togglePlay(s)} disabled={playBusy || s.status !== 'active'}>
								<Icon name={playingFeed === s.feed.id ? 'x' : 'play'} size={16} />{playingFeed === s.feed.id ? t('albums.stopPlaying') : t('frameFeeds.playOnly')}
							</button>
						{/if}
						<button class="btn btn-ghost btn-sm" onclick={() => startEdit(s)}><Icon name="history" size={16} />{t('frameFeeds.changeHistory')}</button>
						<button class="btn btn-danger-ghost btn-sm" onclick={() => ((removing = s), (removeOpen = true))}>
							<Icon name="x" size={16} />{s.status === 'pending' ? t('frameFeeds.withdraw') : t('frameFeeds.unsubscribe')}
						</button>
					</div>
				{/if}
			</li>
		{/each}
	</ul>
	{#if manage && groups.ended.length > 0}
		<div class="ended">
			<button class="btn btn-ghost btn-sm" aria-expanded={showEnded} onclick={() => (showEnded = !showEnded)}>
				<Icon name="chevronDown" size={16} />{tn('subs.ended', groups.ended.length)}
			</button>
			{#if showEnded}
				<ul class="ended-list" role="list" in:softFly>
					{#each groups.ended as s (s.id)}
						<li class="small">
							<strong>{s.feed.name}</strong>
							<span class="muted">· {s.status === 'rejected' ? t('subs.status.rejected') : t(`subs.reason.${s.removed_reason ?? 'unsubscribed'}`)} · {relativeTime(s.ended_at ?? s.created_at, i18n.tag)}</span>
						</li>
					{/each}
				</ul>
			{/if}
		</div>
	{/if}
{/if}

<SubscribeDialog bind:open={subscribeOpen} frameId={frame.id} {taken} onsubscribed={replace} />

<Dialog bind:open={editOpen} title={t('frameFeeds.historyTitle', { feed: editing?.feed.name ?? '' })}>
	{#if editing}
		<div class="stack">
			<HistoryPicker bind:value={editChoice} />
			<p class="hint">{t('frameFeeds.historyChangeHint')}</p>
		</div>
	{/if}
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (editOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" onclick={saveHistory} disabled={saving || !editing || sameHistory(editChoice, historyOf(editing))}>
			{#if saving}<span class="spinner" aria-hidden="true"></span>{/if}{t('common.save')}
		</button>
	{/snippet}
</Dialog>

<Dialog bind:open={removeOpen} title={removing?.status === 'pending' ? t('frameFeeds.withdrawConfirm', { feed: removing?.feed.name ?? '' }) : t('frameFeeds.unsubscribeConfirm', { feed: removing?.feed.name ?? '' })}>
	<p class="muted">{removing?.status === 'pending' ? t('frameFeeds.withdrawBody') : t('frameFeeds.unsubscribeBody', { frame: frame.name })}</p>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (removeOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" onclick={unsubscribe}>{removing?.status === 'pending' ? t('frameFeeds.withdraw') : t('frameFeeds.unsubscribe')}</button>
	{/snippet}
</Dialog>

<Dialog bind:open={codeOpen} title={t('frameFeeds.useInvite')}>
	<form id="ff-code" class="stack" onsubmit={useInvite}>
		<p class="muted small">{t('frameFeeds.useInviteBody')}</p>
		<div class="field">
			<label for="ff-code-in">{t('frameFeeds.codeLabel')}</label>
			<input
				id="ff-code-in"
				class="input"
				bind:value={codeText}
				placeholder={formatFeedCode('KF7QM2XA')}
				autocapitalize="characters"
				autocomplete="off"
				spellcheck="false"
				aria-invalid={Boolean(codeError)}
			/>
		</div>
		{#if codeError}<p class="error-text" role="alert">{codeError}</p>{/if}
	</form>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (codeOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" type="submit" form="ff-code" disabled={!codeText.trim() || codeBusy}>
			{#if codeBusy}<span class="spinner" aria-hidden="true"></span>{/if}{t('frameFeeds.continue')}
		</button>
	{/snippet}
</Dialog>

<style>
	.toolbar {
		display: flex;
		gap: var(--s-3);
		flex-wrap: wrap;
		margin-bottom: var(--s-4);
	}
	.empty {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		gap: var(--s-2);
		color: var(--text-muted);
	}
	.empty h2 {
		color: var(--text);
		font-size: 1.15rem;
	}
	.subs {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
		max-width: 760px;
	}
	.sub {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
		padding: var(--s-4);
	}
	.s-head {
		display: flex;
		align-items: center;
		gap: var(--s-3);
	}
	.who {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.who strong {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.meta {
		margin: 0;
	}
	.actions {
		margin-top: var(--s-1);
	}
	.bd {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.bd .hint {
		margin: 0;
	}
	.s-label {
		font-weight: 600;
	}
	.progress span {
		background: var(--accent);
	}
	.ended {
		margin-top: var(--s-4);
	}
	.ended-list {
		list-style: none;
		margin: var(--s-2) 0 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
</style>
