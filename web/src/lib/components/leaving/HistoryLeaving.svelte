<script lang="ts">
	import { api, call } from '$lib/api/client';
	import { answerBody, formerList, requestPlace, sortRequests, upsertRequest, type FormerPlaces, type KeepAnswer, type KeepRequest } from '$lib/leaving';
	import { dayMonth, resultMessage } from '$lib/leavingText';
	import { relativeTime } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t, tn } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import ChoiceGroup from './ChoiceGroup.svelte';
	import LeaveSheet from './LeaveSheet.svelte';
	import PhotoPicker from './PhotoPicker.svelte';

	/**
	 * The sent history's leaving parts (leaving.md stories 3 and 4): keep requests to answer, and "Take my photos
	 * away from Janssens" for families and frames the person left or was removed from.
	 */
	let { focus = null }: { focus?: string | null } = $props();

	let requests = $state<KeepRequest[]>([]);
	let former = $state<FormerPlaces | null>(null);

	async function loadRequests() {
		try {
			requests = sortRequests((await call(api.GET('/v1/me/keep-requests', { params: { query: { status: 'all' } } }))).requests);
		} catch {
			requests = [];
		}
	}
	async function loadFormer() {
		try {
			former = await call(api.GET('/v1/me/former-places'));
		} catch {
			former = null;
		}
	}

	$effect(() => {
		void loadRequests();
		void loadFormer();
		return live.on((e) => {
			if (e.type === 'keep_request.updated') requests = sortRequests(upsertRequest(requests, e.request));
		});
	});

	// Requests stay listed while open, and for a while after (answered or expired) so the person sees what happened.
	const shown = $derived(requests.filter((r) => r.status === 'open' || Date.now() - Date.parse(r.answered_at ?? r.expires_at) < 14 * 86_400_000));
	const places = $derived(formerList(former));

	// ---- answer
	let answering = $state<KeepRequest | null>(null);
	let answerOpen = $state(false);
	let allow = $state<KeepAnswer>('all');
	let picked = $state(new Set<string>());
	let busy = $state(false);

	async function openAnswer(r: KeepRequest) {
		allow = 'all';
		picked = new Set();
		answering = r;
		answerOpen = true;
		try {
			answering = await call(api.GET('/v1/keep-requests/{requestId}', { params: { path: { requestId: r.id } } }));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}

	let focused = false;
	$effect(() => {
		if (focused || !focus) return;
		const r = requests.find((x) => x.id === focus && x.status === 'open');
		if (r) {
			focused = true;
			void openAnswer(r);
		}
	});

	const requestedIds = $derived((answering?.items ?? []).map((x) => x.item.id));
	const body = $derived(answering ? answerBody(allow, picked, requestedIds) : null);

	async function sendAnswer() {
		const r = answering;
		if (!r || !body) return;
		busy = true;
		try {
			const res = await call(api.POST('/v1/keep-requests/{requestId}/answer', { params: { path: { requestId: r.id } }, body }));
			requests = sortRequests(upsertRequest(requests, res));
			answerOpen = false;
			toasts.success(t('leaving.answered', { name: r.asked_by_name }));
		} catch (e) {
			toasts.error(problemMessage(e));
			void loadRequests();
		} finally {
			busy = false;
		}
	}

	// ---- take away later
	let take = $state<{ kind: 'feed' | 'frame'; id: string; name: string } | null>(null);
	let takeOpen = $state(false);
</script>

{#if shown.length > 0}
	<section class="card card-pad stack-sm" aria-labelledby="keep-title" in:softFly>
		<div class="section-title"><h2 id="keep-title"><Icon name="heart" size={18} /> {t('leaving.requestsTitle')}</h2></div>
		<ul class="list" role="list">
			{#each shown as r (r.id)}
				<li class="row-item">
					<div class="who">
						<span>{t('leaving.requestLine', { name: r.asked_by_name, photos: tn('history.photos', r.item_count), place: requestPlace(r) })}</span>
						{#if r.note}<span class="small note">“{r.note}”</span>{/if}
						<span class="tiny muted">
							{#if r.status === 'open'}{t('leaving.requestAnswerBy', { date: dayMonth(r.expires_at) })}{:else}{t(`leaving.status.${r.status}`)}{#if r.status === 'partly_allowed'} · {t('leaving.allowedOf', { n: r.allowed_count ?? 0, total: r.item_count })}{/if}{/if}
						</span>
					</div>
					{#if r.status === 'open'}
						<button class="btn btn-sm btn-primary" onclick={() => openAnswer(r)}>{t('leaving.answer')}</button>
					{/if}
				</li>
			{/each}
		</ul>
	</section>
{/if}

{#if places.length > 0}
	<section class="card card-pad stack-sm" aria-labelledby="former-title" in:softFly>
		<div class="section-title"><h2 id="former-title">{t('leaving.formerTitle')}</h2></div>
		<p class="muted small">{t('leaving.formerLede')}</p>
		<ul class="list" role="list">
			{#each places as p (p.kind + p.id)}
				<li class="row-item wrap">
					<span class="p-icon"><Icon name={p.kind === 'feed' ? 'users' : 'frame'} size={18} /></span>
					<div class="who">
						<strong>{p.name}</strong>
						<span class="muted small">{t('leaving.formerStill', { photos: tn('history.photos', p.photos) })}{#if p.left_at} · {t('leaving.leftOn', { when: relativeTime(p.left_at, i18n.tag) })}{/if}</span>
					</div>
					<button class="btn btn-sm btn-danger-ghost" onclick={() => ((take = p), (takeOpen = true))}>{t('leaving.takeAwayFrom', { name: p.name })}</button>
				</li>
			{/each}
		</ul>
	</section>
{/if}

{#if take}
	<LeaveSheet
		bind:open={takeOpen}
		scope={take.kind}
		id={take.id}
		name={take.name}
		mode="take"
		ondone={({ result, frameNames }) => {
			toasts.success(resultMessage(result, frameNames));
			void loadFormer();
		}}
	/>
{/if}

<Dialog bind:open={answerOpen} title={t('leaving.answerTitle', { place: answering ? requestPlace(answering) : '' })} size="md">
	{#if answering}
		<div class="stack">
			<p>{t('leaving.requestLine', { name: answering.asked_by_name, photos: tn('history.photos', answering.item_count), place: requestPlace(answering) })}</p>
			{#if answering.note}<p class="small note">“{answering.note}”</p>{/if}
			<p class="muted small">{t('leaving.answerBody', { date: dayMonth(answering.expires_at) })}</p>
			<ChoiceGroup
				name="keep-allow"
				legend={t('leaving.answer')}
				bind:value={allow}
				options={[
					{ id: 'all', label: t('leaving.allowAll') },
					...(requestedIds.length > 1 ? [{ id: 'some' as const, label: t('leaving.allowSome') }] : []),
					{ id: 'none', label: t('leaving.allowNone') }
				]}
			/>
			{#if allow === 'some'}
				<PhotoPicker items={(answering.items ?? []).map((x) => x.item)} bind:picked label={t('leaving.allowSome')} />
			{/if}
			<p class="hint">{t('leaving.answerFinal')}</p>
		</div>
	{/if}
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (answerOpen = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" onclick={sendAnswer} disabled={busy || !body}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{t('leaving.sendAnswer')}
		</button>
	{/snippet}
</Dialog>

<style>
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
	}
	.list > li + li {
		border-top: 1px solid var(--border);
	}
	.row-item {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 10px 0;
	}
	.wrap {
		flex-wrap: wrap;
	}
	.who {
		flex: 1;
		min-width: 200px;
		display: flex;
		flex-direction: column;
	}
	.note {
		font-style: italic;
	}
	.p-icon {
		width: 36px;
		height: 36px;
		border-radius: 10px;
		display: grid;
		place-items: center;
		background: var(--surface-2);
		color: var(--text-muted);
		flex: none;
	}
</style>
