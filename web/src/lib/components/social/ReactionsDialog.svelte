<script lang="ts">
	import { api, call, type ReactionList, type ReactionTargetKind } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { REACTION_EMOJI } from '$lib/logic';
	import { problemMessage } from '$lib/problem';
	import { PERSON_EMOJI, personName } from '$lib/social';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import { relativeTime } from '$lib/format';
	import Avatar from '../Avatar.svelte';
	import Dialog from '../Dialog.svelte';
	import Icon from '../Icon.svelte';

	let { open = $bindable(false), kind, targetId, postId }: { open?: boolean; kind: ReactionTargetKind; targetId: string; postId?: string } = $props();

	let list = $state<ReactionList | null>(null);
	let error = $state<unknown>(null);

	$effect(() => {
		if (!open) return;
		list = null;
		error = null;
		call(api.GET('/v1/reactions/{targetKind}/{targetId}', { params: { path: { targetKind: kind, targetId }, query: postId ? { post_id: postId } : {} } }))
			.then((l) => (list = l))
			.catch((e) => (error = e));
	});
</script>

<Dialog bind:open title={t('social.whoReacted')}>
	{#if error}
		<p class="callout callout-error" role="alert"><Icon name="alert" size={18} />{problemMessage(error)}</p>
	{:else if !list}
		<div class="stack-sm" aria-busy="true" aria-label={t('common.loading')}>
			<div class="skeleton" style="height:40px"></div>
			<div class="skeleton" style="height:40px"></div>
		</div>
	{:else if list.people.length === 0 && list.frames.length === 0}
		<p class="muted">{t('social.noReactions')}</p>
	{:else}
		<ul class="who" role="list">
			{#each list.frames as f (f.frame_id + (f.item_id ?? ''))}
				<li>
					<span class="frame-icon" aria-hidden="true"><Icon name="frame" size={18} /></span>
					<span class="name">{f.frame_name}</span>
					<span class="emoji" title={t(`reaction.${f.reaction}`)}>{REACTION_EMOJI[f.reaction]}</span>
				</li>
			{/each}
			{#each list.people as p (p.person.user_id)}
				<li>
					<Avatar name={personName(p.person, t('social.formerMember'))} url={p.person.former_member ? null : p.person.avatar_url} size={32} />
					<span class="name">
						{#if p.person.user_id === auth.me?.id}{t('social.you')}{:else if p.person.former_member}{t('social.formerMember')}{:else}<a href="/people/{p.person.user_id}">{p.person.display_name}</a>{/if}
						<span class="faint tiny">{relativeTime(p.at, i18n.tag)}</span>
					</span>
					<span class="emoji" title={t(`social.reaction.${p.emoji}`)}>{PERSON_EMOJI[p.emoji]}</span>
				</li>
			{/each}
		</ul>
	{/if}
</Dialog>

<style>
	.who {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	li {
		display: flex;
		align-items: center;
		gap: var(--s-3);
		min-height: 40px;
	}
	.frame-icon {
		width: 32px;
		height: 32px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--accent-soft);
		color: var(--accent-text);
		flex: none;
	}
	.name {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		font-weight: 600;
	}
	.name a {
		color: inherit;
		text-decoration: none;
	}
	.name a:hover {
		text-decoration: underline;
	}
	.emoji {
		font-size: 1.3rem;
	}
</style>
