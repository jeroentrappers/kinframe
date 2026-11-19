<script lang="ts">
	import { api, call, type Report } from '$lib/api/client';
	import { formatDateTime, relativeTime } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { isCode, problemMessage } from '$lib/problem';
	import { personName } from '$lib/social';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import EmptyState from '../EmptyState.svelte';
	import Icon from '../Icon.svelte';

	/** A report queue: a family's (owner and admins) or the operator's (README §5.19). */
	let { load, feedId = null }: { load: () => Promise<Report[]>; feedId?: string | null } = $props();

	let reports = $state<Report[] | null>(null);
	let error = $state<unknown>(null);
	let busy = $state<string | null>(null);

	async function refresh() {
		try {
			reports = await load();
			error = null;
		} catch (e) {
			error = e;
		}
	}

	$effect(() => {
		void refresh();
		return live.on((e) => {
			if (e.type === 'report.created' && (feedId ? e.report.feed_id === feedId : !e.report.feed_id)) {
				reports = [e.report, ...(reports ?? []).filter((r) => r.id !== e.report.id)];
			}
		});
	});

	async function resolve(r: Report, action: 'remove' | 'dismiss') {
		busy = r.id;
		try {
			const next = await call(api.POST('/v1/reports/{reportId}/resolve', { params: { path: { reportId: r.id } }, body: { action } }));
			reports = (reports ?? []).map((x) => (x.id === r.id ? next : x));
			toasts.success(action === 'remove' ? t('chat.reportRemoved') : t('chat.reportDismissed'));
		} catch (e) {
			if (isCode(e, 'already_decided')) {
				toasts.show(t('chat.reportAlreadyDecided'));
				void refresh();
			} else toasts.error(problemMessage(e));
		} finally {
			busy = null;
		}
	}

	const open = $derived((reports ?? []).filter((r) => r.status === 'open'));
	const done = $derived((reports ?? []).filter((r) => r.status !== 'open'));
</script>

{#if error}
	<div class="callout callout-error" role="alert"><Icon name="alert" /><div class="stack-sm"><span>{problemMessage(error)}</span><button class="btn btn-sm" onclick={refresh}>{t('common.tryAgain')}</button></div></div>
{:else if reports === null}
	<div class="skeleton" style="height:120px"></div>
{:else if reports.length === 0}
	<div class="card"><EmptyState compact art="none" title={t('chat.noReports')} body={t('chat.noReportsBody')} /></div>
{:else}
	<ul class="reports" role="list">
		{#each [...open, ...done] as r (r.id)}
			<li class="card card-pad report" class:resolved={r.status !== 'open'}>
				<div class="top">
					<span class="badge">{t(`chat.reason.${r.reason}`)}</span>
					<span class="faint small">{t(`chat.reportKind.${r.target_kind}`)}</span>
					<span class="spacer"></span>
					<time class="faint small" datetime={r.created_at} title={formatDateTime(r.created_at, i18n.tag)}>{relativeTime(r.created_at, i18n.tag)}</time>
				</div>
				{#if r.author}<p class="small"><strong>{personName(r.author, t('social.formerMember'))}</strong></p>{/if}
				{#if r.excerpt}<blockquote>{r.excerpt}</blockquote>{/if}
				{#if r.note}<p class="small muted">{t('chat.reportNoteLabel')}: {r.note}</p>{/if}
				<div class="row-wrap">
					{#if r.post_id}<a class="btn btn-sm btn-ghost" href="/posts/{r.post_id}">{t('chat.viewContext')}</a>{:else if r.conversation_id}<a class="btn btn-sm btn-ghost" href="/chats/{r.conversation_id}">{t('chat.viewContext')}</a>{/if}
					<span class="spacer"></span>
					{#if r.status === 'open'}
						<button class="btn btn-sm" disabled={busy === r.id} onclick={() => resolve(r, 'dismiss')}>{t('chat.keep')}</button>
						<button class="btn btn-sm btn-danger" disabled={busy === r.id} onclick={() => resolve(r, 'remove')}><Icon name="trash" size={15} />{t('chat.removeContent')}</button>
					{:else}
						<span class="small muted">{r.status === 'removed' ? t('chat.statusRemoved') : t('chat.statusDismissed')}{#if r.resolved_at} · {relativeTime(r.resolved_at, i18n.tag)}{/if}</span>
					{/if}
				</div>
			</li>
		{/each}
	</ul>
{/if}

<style>
	.reports {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-3);
	}
	.report {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.report.resolved {
		opacity: 0.7;
	}
	.top {
		display: flex;
		align-items: center;
		gap: var(--s-2);
	}
	blockquote {
		margin: 0;
		padding: var(--s-2) var(--s-3);
		border-inline-start: 3px solid var(--border-strong);
		background: var(--surface-2);
		border-radius: var(--r-sm);
		white-space: pre-wrap;
		overflow-wrap: anywhere;
	}
	p {
		margin: 0;
	}
</style>
