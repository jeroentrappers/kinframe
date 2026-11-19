<script lang="ts">
	import { api, call, type ReportReason, type ReportTargetKind } from '$lib/api/client';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	/** Report a comment, message or post (README §5.19): the family's admins or the operator get it, anonymously. */
	let { open = $bindable(false), kind, targetId }: { open?: boolean; kind: ReportTargetKind; targetId: string } = $props();

	const REASONS: ReportReason[] = ['unkind', 'spam', 'inappropriate', 'other'];
	let reason = $state<ReportReason>('unkind');
	let note = $state('');
	let busy = $state(false);

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		try {
			await call(api.POST('/v1/reports', { body: { target_kind: kind, target_id: targetId, reason, ...(note.trim() ? { note: note.trim() } : {}) } }));
			open = false;
			toasts.success(t('chat.reported'));
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			busy = false;
		}
	}
</script>

<Dialog bind:open title={t(`chat.reportTitle.${kind}`)}>
	<form id="report-form" class="stack" onsubmit={submit}>
		<p class="muted">{t('chat.reportIntro')}</p>
		<fieldset class="reasons">
			<legend class="label">{t('chat.reportReason')}</legend>
			{#each REASONS as r (r)}
				<label class="radio"><input type="radio" name="reason" value={r} bind:group={reason} />{t(`chat.reason.${r}`)}</label>
			{/each}
		</fieldset>
		<div class="field">
			<label for="report-note">{t('chat.reportNote')} <span class="faint">({t('common.optional')})</span></label>
			<textarea id="report-note" class="textarea" rows="3" maxlength="500" bind:value={note}></textarea>
		</div>
	</form>
	{#snippet footer()}
		<button class="btn" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-danger" type="submit" form="report-form" disabled={busy}>
			{#if busy}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="flag" size={16} />{/if}{t('chat.report')}
		</button>
	{/snippet}
</Dialog>

<style>
	.reasons {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.radio {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		min-height: 36px;
		cursor: pointer;
	}
</style>
