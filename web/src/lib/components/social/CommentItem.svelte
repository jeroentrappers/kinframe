<script lang="ts">
	import type { Comment, Mention, ReactionSummary } from '$lib/api/client';
	import { auth } from '$lib/auth.svelte';
	import { discovery } from '$lib/discovery.svelte';
	import { formatDateTime, relativeTime } from '$lib/format';
	import { REACTION_EMOJI } from '$lib/logic';
	import { codePoints, personName } from '$lib/social';
	import { i18n, t } from '$lib/i18n/index.svelte';
	import Avatar from '../Avatar.svelte';
	import Icon from '../Icon.svelte';
	import MentionInput, { type MentionCandidate } from './MentionInput.svelte';
	import MoreMenu, { type MenuItem } from './MoreMenu.svelte';
	import ReactionRow from './ReactionRow.svelte';
	import RichText from './RichText.svelte';

	let {
		comment: c,
		reply = false,
		people,
		canComment,
		canModerate,
		highlight = false,
		onreply,
		onsave,
		ondelete,
		onreport,
		onreactions
	}: {
		comment: Comment;
		reply?: boolean;
		people: MentionCandidate[];
		canComment: boolean;
		canModerate: boolean;
		highlight?: boolean;
		onreply: (c: Comment) => void;
		onsave: (c: Comment, body: string, mentions: Mention[]) => Promise<boolean>;
		ondelete: (c: Comment, asModerator: boolean) => void;
		onreport: (c: Comment) => void;
		onreactions: (c: Comment, s: ReactionSummary) => void;
	} = $props();

	const own = $derived(!!c.author && c.author.user_id === auth.me?.id);
	const name = $derived(personName(c.author, t('social.formerMember')));
	const max = $derived(discovery.limits.max_comment_chars);

	let editing = $state(false);
	let draft = $state('');
	let draftMentions = $state<Mention[]>([]);
	let saving = $state(false);

	function startEdit() {
		draft = c.body ?? '';
		draftMentions = [...(c.mentions ?? [])];
		editing = true;
	}

	async function save() {
		if (!draft.trim() || codePoints(draft.trim()) > max) return;
		saving = true;
		if (await onsave(c, draft, draftMentions)) editing = false;
		saving = false;
	}

	const menu = $derived.by((): MenuItem[] => {
		const items: MenuItem[] = [];
		if (c.kind !== 'person' || c.deleted) return items;
		if (c.can_edit) items.push({ label: t('common.edit'), icon: 'edit', run: startEdit });
		if (c.can_delete && own) items.push({ label: t('social.deleteComment'), icon: 'trash', danger: true, run: () => ondelete(c, false) });
		if (!own && (c.can_delete || canModerate)) items.push({ label: t('social.removeComment'), icon: 'trash', danger: true, run: () => ondelete(c, true) });
		if (!own && discovery.chats) items.push({ label: t('chat.report'), icon: 'flag', run: () => onreport(c) });
		return items;
	});
</script>

{#if c.kind === 'frame' && c.frame}
	<div class="entry frame-entry" id="c-{c.id}">
		<span class="frame-ic" aria-hidden="true"><Icon name="frame" size={16} /></span>
		<p>
			{#if c.frame.reply}
				<strong>{c.frame.frame_name}:</strong> {t(`reply.${c.frame.reply}`)}
			{:else if c.frame.reaction}
				{t(`social.frameReacted.${c.frame.reaction}`, { frame: c.frame.frame_name })} <span aria-hidden="true">{REACTION_EMOJI[c.frame.reaction]}</span>
			{/if}
			<time class="faint tiny" datetime={c.created_at}>{relativeTime(c.created_at, i18n.tag)}</time>
		</p>
	</div>
{:else}
	<div class="entry" class:reply class:highlight id="c-{c.id}">
		<Avatar name={name} url={c.author?.former_member ? null : c.author?.avatar_url} size={reply ? 28 : 34} />
		<div class="main">
			{#if c.deleted}
				<p class="tomb faint">{t('social.commentDeleted')}</p>
			{:else if editing}
				<div class="edit">
					<MentionInput bind:value={draft} bind:mentions={draftMentions} {people} label={t('social.editComment')} maxlength={max * 2} rows={2} onsubmit={save} />
					<div class="row">
						<button class="btn btn-sm" onclick={() => (editing = false)}>{t('common.cancel')}</button>
						<button class="btn btn-sm btn-primary" onclick={save} disabled={saving || !draft.trim() || codePoints(draft.trim()) > max}>{t('common.save')}</button>
					</div>
				</div>
			{:else}
				<div class="bubble">
					<span class="who">
						{#if c.author && !c.author.former_member}<a href="/people/{c.author.user_id}">{name}</a>{:else}{name}{/if}
					</span>
					{#if c.quoted}<span class="quoted">@{personName(c.quoted, t('social.formerMember'))}</span>{/if}
					<RichText body={c.body} mentions={c.mentions} />
				</div>
				{#if c.removed_by_admin}<p class="removed small"><Icon name="info" size={14} />{t('social.removedByAdmin')}</p>{/if}
			{/if}
			{#if !c.deleted && !editing}
				<div class="foot">
					<time class="faint tiny" datetime={c.created_at} title={formatDateTime(c.created_at, i18n.tag)}>{relativeTime(c.created_at, i18n.tag)}</time>
					{#if c.edited_at}<span class="faint tiny">· {t('social.edited')}</span>{/if}
					{#if canComment && !c.removed_by_admin}
						<button class="link-btn" onclick={() => onreply(c)}>{t('social.reply')}</button>
					{/if}
					<ReactionRow summary={c.reactions} kind="comment" targetId={c.id} size="sm" canReact={!c.removed_by_admin} onchange={(s) => onreactions(c, s)} />
					<span class="spacer"></span>
					<MoreMenu label={t('social.commentActions')} items={menu} />
				</div>
			{/if}
		</div>
	</div>
{/if}

<style>
	.entry {
		display: flex;
		gap: var(--s-2);
		align-items: flex-start;
		scroll-margin-top: calc(var(--nav-h) + 16px);
	}
	.entry.reply {
		margin-inline-start: 42px;
	}
	.main {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.bubble {
		align-self: flex-start;
		max-width: 100%;
		padding: 8px 12px;
		border-radius: 16px;
		border-start-start-radius: 6px;
		background: var(--surface-2);
	}
	.highlight .bubble {
		box-shadow: 0 0 0 2px var(--primary);
	}
	.who {
		display: block;
		font-weight: 700;
		font-size: 0.86rem;
	}
	.who a {
		color: inherit;
		text-decoration: none;
	}
	.who a:hover {
		text-decoration: underline;
	}
	.quoted {
		color: var(--primary-soft-text);
		font-weight: 650;
		margin-inline-end: 4px;
	}
	.foot {
		display: flex;
		align-items: center;
		gap: var(--s-2);
		flex-wrap: wrap;
		padding-inline-start: 6px;
	}
	.link-btn {
		border: 0;
		background: none;
		padding: 4px 2px;
		color: var(--text-muted);
		font: inherit;
		font-size: 0.8rem;
		font-weight: 700;
		cursor: pointer;
	}
	.link-btn:hover {
		color: var(--text);
		text-decoration: underline;
	}
	.tomb {
		font-style: italic;
		margin: 6px 0;
	}
	.removed {
		margin: 2px 0 0;
		display: inline-flex;
		align-items: center;
		gap: 4px;
		color: var(--danger);
	}
	.edit {
		display: flex;
		flex-direction: column;
		gap: var(--s-2);
	}
	.edit .row {
		justify-content: flex-end;
	}
	.frame-entry {
		align-items: center;
		padding: 4px 0;
		color: var(--text-muted);
		font-size: 0.9rem;
	}
	.frame-entry p {
		margin: 0;
	}
	.frame-ic {
		width: 28px;
		height: 28px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		background: var(--accent-soft);
		color: var(--accent-text);
		flex: none;
		margin-inline: 3px;
	}
</style>
