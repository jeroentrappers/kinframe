<script lang="ts">
	import { api, call } from '$lib/api/client';
	import { newIdempotencyKey } from '$lib/logic';
	import { defaultChoice, hasPhotoChoice, leaveFeedBody, type LeaveChoice, type LeavePreview, type TakeAwayResult } from '$lib/leaving';
	import { isCode, problemMessage } from '$lib/problem';
	import { t } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';
	import ChoiceGroup from './ChoiceGroup.svelte';

	/**
	 * The leave sheet (leaving.md §3 stories 1 and 6) and the take-away sheet for places left earlier (story 4):
	 * photos stay (default) or go with a 30-day notice, comments stay or are deleted, and "Leave this frame too".
	 */
	let {
		open = $bindable(false),
		scope,
		id,
		name,
		mode = 'leave',
		ondone
	}: {
		open?: boolean;
		scope: 'feed' | 'frame';
		id: string;
		name: string;
		mode?: 'leave' | 'take';
		ondone: (r: { result: TakeAwayResult | null; left: boolean; frameNames: (ids: string[]) => string[] }) => void;
	} = $props();

	const uid = $props.id();
	let preview = $state<LeavePreview | null>(null);
	let error = $state<unknown>(null);
	let choice = $state<LeaveChoice>(defaultChoice(null));
	let busy = $state(false);

	$effect(() => {
		if (!open) return;
		preview = null;
		error = null;
		const query = scope === 'feed' ? { feed_id: id } : { frame_id: id };
		call(api.GET('/v1/me/leave-preview', { params: { query } }))
			.then((p) => {
				preview = p;
				choice = { ...defaultChoice(p), photos: mode === 'take' ? 'take' : 'keep' };
			})
			.catch((e) => {
				// Nothing of the caller's there: leaving still works, there's just nothing to choose.
				if (isCode(e, 'not_found') && mode === 'leave') {
					preview = { scope, name, member: true, photos: 0, comments: 0, frames: [] };
					choice = defaultChoice(null);
				} else error = e;
			});
	});

	const frameNames = (ids: string[]) => ids.map((x) => preview?.frames?.find((f) => f.frame_id === x)?.frame_name ?? '').filter(Boolean);
	const blocked = $derived(mode === 'leave' && preview?.owner === true);

	function toggleFrame(frameId: string, on: boolean) {
		choice = { ...choice, frames: on ? [...choice.frames, frameId] : choice.frames.filter((x) => x !== frameId) };
	}

	async function submit() {
		busy = true;
		try {
			let result: TakeAwayResult | null = null;
			const header = { 'Idempotency-Key': newIdempotencyKey() };
			if (mode === 'take') {
				result =
					scope === 'feed'
						? await call(api.POST('/v1/feeds/{feedId}/take-away', { params: { path: { feedId: id }, header }, body: { comments: choice.comments } }))
						: await call(api.POST('/v1/frames/{frameId}/take-away', { params: { path: { frameId: id }, header } }));
			} else if (scope === 'feed') {
				result = (await call(api.POST('/v1/feeds/{feedId}/leave', { params: { path: { feedId: id } }, body: leaveFeedBody(choice, preview) }))) ?? null;
			} else {
				result = (await call(api.POST('/v1/frames/{frameId}/leave', { params: { path: { frameId: id } }, body: { photos: choice.photos } }))) ?? null;
			}
			open = false;
			ondone({ result, left: mode === 'leave', frameNames });
		} catch (e) {
			error = e;
		} finally {
			busy = false;
		}
	}
</script>

<Dialog bind:open title={mode === 'take' ? t('leaving.takeAwayTitle', { name }) : t('leaving.leaveTitle', { name })} size="md">
	{#if !preview && !error}
		<div class="stack-sm" aria-busy="true">{#each [0, 1, 2] as i (i)}<div class="skeleton" style="height:56px"></div>{/each}</div>
	{:else if error && !preview}
		<p class="callout callout-error small" role="alert"><Icon name="alert" size={18} />{problemMessage(error)}</p>
	{:else if preview}
		<div class="stack">
			{#if blocked}
				<p class="callout callout-warn small"><Icon name="crown" size={18} />{t('leaving.ownerFirst', { name })}</p>
			{:else}
				{#if mode === 'take'}<p class="muted small">{t('leaving.takeAwayBody', { name })}</p>{/if}
				{#if mode === 'leave'}
					{#if hasPhotoChoice(preview)}
						<ChoiceGroup
							name="{uid}-photos"
							legend={t('leaving.photosLegend', { n: preview.photos })}
							bind:value={choice.photos}
							options={scope === 'feed'
								? [
										{ id: 'keep', label: t('leaving.photosKeep'), hint: t('leaving.photosKeepHint') },
										{ id: 'take', label: t('leaving.photosTake'), hint: t('leaving.photosTakeHint') }
									]
								: [
										{ id: 'keep', label: t('leaving.photosKeepFrame'), hint: t('leaving.photosKeepFrameHint', { name }) },
										{ id: 'take', label: t('leaving.photosTake'), hint: t('leaving.photosTakeFrameHint') }
									]}
						/>
					{:else}
						<p class="muted small">{t('leaving.noPhotos')}</p>
					{/if}
				{:else}
					<p class="label">{t('leaving.photosLegend', { n: preview.photos })}</p>
				{/if}
				{#if (preview.already_taken_away ?? 0) > 0}
					<p class="hint">{t('leaving.alreadyTaken', { n: preview.already_taken_away ?? 0 })}</p>
				{/if}

				{#if scope === 'feed' && (preview.comments ?? 0) > 0}
					<ChoiceGroup
						name="{uid}-comments"
						legend={t('leaving.commentsLegend', { n: preview.comments ?? 0 })}
						bind:value={choice.comments}
						options={[
							{ id: 'keep', label: t('leaving.commentsKeep') },
							{ id: 'delete', label: t('leaving.commentsDelete'), hint: t('leaving.commentsDeleteHint') }
						]}
					/>
				{/if}

				{#if mode === 'leave' && scope === 'feed' && (preview.frames?.length ?? 0) > 0}
					<fieldset class="frames">
						<legend class="label">{t('leaving.framesLegend')}</legend>
						{#each preview.frames ?? [] as f (f.frame_id)}
							<label class="frame-opt" class:off={f.can_leave === false}>
								<input
									type="checkbox"
									checked={choice.frames.includes(f.frame_id)}
									disabled={f.can_leave === false}
									onchange={(e) => toggleFrame(f.frame_id, e.currentTarget.checked)}
								/>
								<span>
									<strong>{t('leaving.leaveFrameToo', { frame: f.frame_name })}</strong>
									{#if f.can_leave === false}<span class="hint">{t('leaving.frameOwner')}</span>
									{:else if (f.direct_photos ?? 0) > 0 && choice.photos === 'take'}<span class="hint">{t('leaving.frameDirect', { n: f.direct_photos ?? 0 })}</span>{/if}
								</span>
							</label>
						{/each}
					</fieldset>
				{/if}

				<p class="hint later"><Icon name="history" size={14} /> {t('leaving.laterLine')}</p>
				{#if error}<p class="error-text small" role="alert">{problemMessage(error)}</p>{/if}
			{/if}
		</div>
	{/if}
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		{#if preview && !blocked}
			<button class="btn btn-danger" onclick={submit} disabled={busy || (mode === 'take' && preview.photos === 0)}>
				{#if busy}<span class="spinner" aria-hidden="true"></span>{/if}{mode === 'take' ? t('leaving.takeAwayButton') : t('leaving.leave')}
			</button>
		{/if}
	{/snippet}
</Dialog>

<style>
	.frames {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
		min-width: 0;
	}
	.frame-opt {
		display: flex;
		gap: 10px;
		align-items: flex-start;
		padding: 8px 0;
		cursor: pointer;
	}
	.frame-opt.off {
		cursor: default;
		opacity: 0.75;
	}
	.frame-opt input {
		margin-top: 4px;
		flex: none;
	}
	.frame-opt > span {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.later {
		display: flex;
		gap: 6px;
		align-items: center;
	}
</style>
