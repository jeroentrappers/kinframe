<script lang="ts">
	import { untrack } from 'svelte';
	import { api, call, type Placement } from '$lib/api/client';
	import { can, frames } from '$lib/frames.svelte';
	import { problemMessage } from '$lib/problem';
	import { shareCoverage, type Coverage } from '$lib/share';
	import { toasts } from '$lib/toast.svelte';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Dialog from './Dialog.svelte';
	import Icon from './Icon.svelte';

	let {
		open = $bindable(false),
		frameId,
		placementIds,
		onshared
	}: {
		open?: boolean;
		/** The frame whose library the placements come from. */
		frameId: string;
		placementIds: string[];
		onshared?: (created: Placement[]) => void;
	} = $props();

	const id = $props.id();
	let chosen = $state<string[]>([]);
	let coverage = $state<Record<string, Coverage>>({});
	let checking = $state(false);
	let busy = $state(false);

	// Frames you can send to, except the one the photos come from.
	const targets = $derived(frames.list.filter((f) => f.id !== frameId && can(f, 'send')));

	$effect(() => {
		if (!open) return;
		untrack(() => {
			chosen = [];
			coverage = {};
			void check();
		});
	});

	/** A dry run tells which frames already have (some of) the selection. */
	async function check() {
		await frames.load();
		const ids = targets.map((f) => f.id);
		if (ids.length === 0 || placementIds.length === 0) return;
		checking = true;
		try {
			const res = await call(
				api.POST('/v1/frames/{frameId}/placements:share', {
					params: { path: { frameId } },
					body: { placement_ids: placementIds, frame_ids: ids, dry_run: true }
				})
			);
			coverage = shareCoverage(res.skipped, ids, placementIds.length);
		} catch {
			coverage = {};
		} finally {
			checking = false;
		}
	}

	const names = (ids: string[]) =>
		new Intl.ListFormat(i18n.tag, { type: 'conjunction' }).format(ids.map((x) => frames.get(x)?.name ?? ''));

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		if (chosen.length === 0) return;
		busy = true;
		const frameIds = [...chosen];
		try {
			const res = await call(
				api.POST('/v1/frames/{frameId}/placements:share', {
					params: { path: { frameId }, header: { 'Idempotency-Key': crypto.randomUUID() } },
					body: { placement_ids: placementIds, frame_ids: frameIds }
				})
			);
			open = false;
			onshared?.(res.placements);
			if (res.placements.length === 0) {
				toasts.show(t('share.nothingNew'));
				return;
			}
			const to = targets.map((f) => f.id).filter((x) => res.placements.some((p) => p.frame_id === x));
			toasts.show(t('share.done', { frames: names(to) }), {
				kind: 'success',
				duration: 8000,
				action: { label: t('common.undo'), run: () => void undo(res.placements) }
			});
		} catch (err) {
			toasts.error(problemMessage(err));
		} finally {
			busy = false;
		}
	}

	/** Undo removes exactly the placements this share created (the sharer may purge them). */
	async function undo(created: Placement[]) {
		const byFrame = new Map<string, string[]>();
		for (const p of created) byFrame.set(p.frame_id, [...(byFrame.get(p.frame_id) ?? []), p.id]);
		try {
			for (const [fid, ids] of byFrame) {
				await call(
					api.POST('/v1/frames/{frameId}/placements:bulk', {
						params: { path: { frameId: fid } },
						body: { placement_ids: ids, action: 'purge' }
					})
				);
			}
			toasts.success(t('share.undone'));
		} catch (err) {
			toasts.error(problemMessage(err));
		}
	}
</script>

<Dialog bind:open title={t('share.title')}>
	{#if frames.loaded && targets.length === 0}
		<p class="muted">{t('share.noFrames')}</p>
	{:else}
		<form id="{id}-form" class="stack-sm" onsubmit={submit} aria-busy={checking}>
			<p class="muted small">{t('share.hint')}</p>
			<fieldset class="choices">
				<legend class="visually-hidden">{t('share.pick')}</legend>
				{#each targets as f (f.id)}
					{@const cov = coverage[f.id] ?? 'none'}
					<label class="choice" class:checked={chosen.includes(f.id)} class:full={cov === 'all'}>
						<input type="checkbox" name="{id}-frame" value={f.id} bind:group={chosen} disabled={cov === 'all'} />
						<span class="c-icon"><Icon name={chosen.includes(f.id) || cov === 'all' ? 'check' : 'frame'} size={18} /></span>
						<span class="c-name">{f.name}</span>
						{#if cov === 'all'}
							<span class="tag">{t('share.alreadyThere')}</span>
						{:else if cov === 'some'}
							<span class="tag tag-some">{t('share.partly')}</span>
						{/if}
					</label>
				{/each}
			</fieldset>
		</form>
	{/if}
	{#snippet footer()}
		<button type="button" class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		{#if targets.length > 0}
			<button type="submit" form="{id}-form" class="btn btn-primary" disabled={busy || chosen.length === 0}>
				{#if busy}<span class="spinner" aria-hidden="true"></span>{:else}<Icon name="share" size={16} />{/if}{t('share.submit')}
			</button>
		{/if}
	{/snippet}
</Dialog>

<style>
	.choices {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.choice {
		position: relative;
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 10px 12px;
		border-radius: var(--r);
		border: 1.5px solid var(--border);
		cursor: pointer;
		min-width: 0;
	}
	.choice.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 55%, var(--surface));
	}
	.choice.full {
		cursor: default;
		opacity: 0.7;
	}
	.choice:has(input:focus-visible) {
		outline: 3px solid var(--focus);
		outline-offset: 2px;
	}
	.choice input {
		position: absolute;
		opacity: 0;
		pointer-events: none;
	}
	.c-icon {
		width: 32px;
		height: 32px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--surface-2);
		color: var(--text-muted);
		flex: none;
	}
	.checked .c-icon {
		background: var(--primary);
		color: var(--on-primary);
	}
	.c-name {
		flex: 1;
		min-width: 0;
		font-weight: 600;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.tag {
		flex: none;
		padding: 1px 8px;
		border-radius: var(--r-full);
		background: var(--surface-2);
		color: var(--text-muted);
		font-size: 0.74rem;
		font-weight: 700;
	}
	.tag-some {
		background: var(--warn-soft);
		color: var(--warn);
	}
</style>
