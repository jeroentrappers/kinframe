<script lang="ts">
	import { api, call, type Feed, type FeedMute, type FeedStreak } from '$lib/api/client';
	import { discovery } from '$lib/discovery.svelte';
	import { live } from '$lib/live.svelte';
	import { problemMessage } from '$lib/problem';
	import { personName } from '$lib/social';
	import { toasts } from '$lib/toast.svelte';
	import { t, tn } from '$lib/i18n/index.svelte';
	import Avatar from '../Avatar.svelte';
	import Icon from '../Icon.svelte';

	/** Under the family header: the streak ("🔥 12 weeks", who helped this week), the family chat and the mute. */
	let { feed, onupdated }: { feed: Feed; onupdated?: (f: Partial<Feed>) => void } = $props();

	let streak = $state<FeedStreak | null>(null);
	$effect(() => {
		const id = feed.id;
		if (!discovery.engagement) return;
		const loadStreak = () =>
			call(api.GET('/v1/feeds/{feedId}/streak', { params: { path: { feedId: id } } }))
				.then((s) => (streak = s))
				.catch(() => (streak = null));
		void loadStreak();
		return live.on((e) => {
			if (e.type === 'streak.updated' && e.feed_id === id) void loadStreak();
		});
	});

	const MUTES: FeedMute[] = ['none', 'posts', 'chat', 'all'];
	let mute = $state<FeedMute>('none');
	$effect(() => {
		mute = feed.mute ?? 'none';
	});

	async function setMute() {
		try {
			await call(api.PATCH('/v1/feeds/{feedId}/preferences', { params: { path: { feedId: feed.id } }, body: { mute } }));
			onupdated?.({ mute });
			toasts.success(mute === 'none' ? t('social.familyUnmuted') : t('social.familyMuted'));
		} catch (e) {
			mute = feed.mute ?? 'none';
			toasts.error(problemMessage(e));
		}
	}

	const helpers = $derived(streak?.week.helpers ?? []);
</script>

<div class="bar">
	{#if streak?.enabled}
		<div class="streak" aria-label={t('engagement.streakA11y', { weeks: streak.current_weeks, photos: streak.week.photos, goal: streak.goal })}>
			<span class="flame" aria-hidden="true">🔥</span>
			<span class="txt">
				<strong>{streak.current_weeks > 0 ? tn('engagement.streakWeeks', streak.current_weeks) : t('engagement.streakStart')}</strong>
				<span class="small muted">{streak.week.met ? t('engagement.weekMet') : tn('engagement.weekProgress', streak.goal, { photos: streak.week.photos })}</span>
			</span>
			{#if helpers.length}
				<span class="helpers" aria-label={t('engagement.helpers', { names: helpers.map((h) => personName(h, t('social.formerMember'))).join(', ') })}>
					{#each helpers.slice(0, 6) as h (h.user_id)}<Avatar name={personName(h, '')} url={h.avatar_url} size={24} />{/each}
				</span>
			{/if}
			{#if streak.freezes_left > 0}<span class="freeze small faint" title={tn('engagement.freezes', streak.freezes_left)}>❄️{streak.freezes_left}</span>{/if}
		</div>
	{/if}
	<span class="spacer"></span>
	{#if discovery.chats && feed.conversation_id}
		<a class="btn btn-sm btn-soft" href="/chats/{feed.conversation_id}"><Icon name="message" size={16} />{t('social.familyChat')}</a>
	{/if}
	<label class="mute small">
		<Icon name={mute === 'none' ? 'bell' : 'bellOff'} size={16} />
		<span class="visually-hidden">{t('social.familyNotifications')}</span>
		<select class="select select-sm" bind:value={mute} onchange={setMute}>
			{#each MUTES as m (m)}<option value={m}>{t(`social.mute.${m}`)}</option>{/each}
		</select>
	</label>
</div>

<style>
	.bar {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		flex-wrap: wrap;
		margin: calc(-1 * var(--s-2)) 0 var(--s-4);
	}
	.streak {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		padding: 6px 12px;
		border-radius: var(--r-full);
		background: var(--warn-soft);
	}
	.flame {
		font-size: 1.2rem;
	}
	.txt {
		display: flex;
		flex-direction: column;
		line-height: 1.2;
	}
	.helpers {
		display: flex;
	}
	.helpers :global(.avatar) {
		margin-inline-start: -6px;
	}
	.mute {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--text-muted);
	}
	.select-sm {
		min-height: 34px;
		font-size: 0.85rem;
		width: auto;
	}
</style>
