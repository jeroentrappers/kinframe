<script lang="ts">
	import { untrack } from 'svelte';
	import type { Circle, FeedSummary } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { t, tn } from '$lib/i18n/index.svelte';
	import Dialog from '../Dialog.svelte';
	import FeedCover from '../feed/FeedCover.svelte';
	import { myCircles } from '../social/AudiencePicker.svelte';

	/**
	 * The composer's "Change" (simple-app.md §3.2): the person's families as checkboxes (one post to several families
	 * is one send to several feeds), and per family one of its circles instead of the whole family. Single frames are
	 * not offered here.
	 */
	let {
		open = $bindable(false),
		families,
		chosen,
		circleOf,
		onchoose
	}: {
		open?: boolean;
		/** Families the person can post to, home family first. */
		families: FeedSummary[];
		chosen: string[];
		circleOf: Record<string, string | null>;
		onchoose: (ids: string[], circleOf: Record<string, string | null>) => void;
	} = $props();

	const uid = $props.id();
	let picked = $state<string[]>([]);
	let circlePick = $state<Record<string, string | null>>({});
	let circles = $state<Record<string, Circle[]>>({});

	$effect(() => {
		if (!open) return;
		untrack(() => {
			picked = [...chosen];
			circlePick = { ...circleOf };
			if (discovery.social)
				for (const f of families) void myCircles(f.id).then((c) => (circles = { ...circles, [f.id]: c }));
		});
	});

	function toggle(id: string) {
		picked = picked.includes(id) ? picked.filter((x) => x !== id) : [...picked, id];
	}

	function done() {
		if (!picked.length) return;
		onchoose(
			families.map((f) => f.id).filter((id) => picked.includes(id)),
			circlePick
		);
		open = false;
	}
</script>

<Dialog bind:open title={t('compose.changeTitle')}>
	<fieldset>
		<legend class="visually-hidden">{t('compose.family')}</legend>
		{#each families as f (f.id)}
			{@const on = picked.includes(f.id)}
			<div class="fam" class:checked={on}>
				<label class="opt">
					<input type="checkbox" checked={on} onchange={() => toggle(f.id)} data-testid="family-option" />
					<FeedCover name={f.name} url={f.cover_url} size={32} />
					<span class="grow">{f.name}<span class="muted tiny"> · {tn('family.frames', f.subscription_count)}</span></span>
				</label>
				{#if on && (circles[f.id]?.length ?? 0) > 0}
					<label class="circle">
						<span class="visually-hidden">{t('social.shareWith', { family: f.name })}</span>
						<select class="select select-sm" value={circlePick[f.id] ?? ''} onchange={(e) => (circlePick = { ...circlePick, [f.id]: e.currentTarget.value || null })} data-testid="audience">
							<option value="">{t('compose.everyone')}</option>
							{#each circles[f.id] ?? [] as c (c.id)}<option value={c.id}>{c.name}</option>{/each}
						</select>
					</label>
				{/if}
			</div>
		{/each}
	</fieldset>
	{#snippet footer()}
		<button class="btn btn-ghost" onclick={() => (open = false)}>{t('common.cancel')}</button>
		<button class="btn btn-primary" onclick={done} disabled={!picked.length} id="{uid}-done">{t('common.done')}</button>
	{/snippet}
</Dialog>

<style>
	fieldset {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.fam {
		border: 1.5px solid var(--border);
		border-radius: var(--r);
	}
	.fam.checked {
		border-color: var(--primary);
		background: color-mix(in srgb, var(--primary-soft) 55%, var(--surface));
	}
	.opt {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		padding: 8px 12px;
		cursor: pointer;
	}
	.circle {
		display: block;
		padding: 0 12px 10px 52px;
	}
	.circle select {
		width: 100%;
	}
	.grow {
		flex: 1;
		min-width: 0;
	}
</style>
