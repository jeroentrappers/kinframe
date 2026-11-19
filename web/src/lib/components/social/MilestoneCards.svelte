<script lang="ts">
	import { api, call, type Milestone } from '$lib/api/client';
	import { MILESTONE_EMOJI } from '$lib/engagement';
	import { problemMessage } from '$lib/problem';
	import { toasts } from '$lib/toast.svelte';
	import { t } from '$lib/i18n/index.svelte';
	import { softFly } from '$lib/motion';
	import Icon from '../Icon.svelte';

	/** Personal milestones not dismissed yet (README §5.20): small, quiet, private unless shown. */
	let { milestones = $bindable<Milestone[]>([]) }: { milestones?: Milestone[] } = $props();
	const unseen = $derived(milestones.filter((m) => !m.seen));

	async function patch(m: Milestone, body: { shown?: boolean; seen?: true }) {
		try {
			const next = await call(api.PATCH('/v1/me/milestones/{milestoneKey}', { params: { path: { milestoneKey: m.key } }, body }));
			milestones = milestones.map((x) => (x.key === m.key ? next : x));
			if (body.shown !== undefined) toasts.success(body.shown ? t('engagement.milestoneShown') : t('engagement.milestoneHidden'));
		} catch (e) {
			toasts.error(problemMessage(e));
		}
	}
</script>

{#each unseen as m (m.key)}
	<section class="card milestone" in:softFly aria-labelledby="ms-{m.key}">
		<span class="emoji" aria-hidden="true">{MILESTONE_EMOJI[m.key]}</span>
		<div class="body">
			<h2 id="ms-{m.key}">{t(`engagement.milestone.${m.key}`)}</h2>
			<p class="muted small">{t('engagement.milestonePrivate')}</p>
			<label class="switch small-switch">
				<input type="checkbox" checked={m.shown} onchange={(e) => patch(m, { shown: e.currentTarget.checked })} />
				<span>{t('engagement.milestoneShow')}</span>
			</label>
		</div>
		<button class="btn btn-ghost btn-icon btn-sm" aria-label={t('common.dismiss')} onclick={() => patch(m, { seen: true })}><Icon name="x" size={18} /></button>
	</section>
{/each}

<style>
	.milestone {
		display: flex;
		gap: var(--s-3);
		align-items: flex-start;
		padding: var(--s-4);
	}
	.emoji {
		font-size: 2rem;
		line-height: 1;
	}
	.body {
		flex: 1;
		min-width: 0;
	}
	h2 {
		font-size: 1.02rem;
	}
	p {
		margin: 2px 0 var(--s-2);
	}
	.small-switch {
		min-height: 36px;
		font-size: 0.9rem;
	}
</style>
